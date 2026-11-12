/*
 *  This file is part of DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 *
 *  Modified for DRAUPNE Link (phone app pc.26 / DRAUPNE Link 1.0.33): the resend timer of a session is cancelled
 *  when the next session starts (see connect). Upstream's timer task cancelled the `timer` field, which by then was
 *  the NEW session's timer: from the first reconnect on, no acknowledged packet (Config, StartVideo, the video
 *  config, FcInfo...) was ever resent on either side, and a timer thread was left running per Connect.
 *  pc.31 / DRAUPNE Link 1.0.39: a Connect is not kept for resending (both apps send it on their own schedule and
 *  each one is answered: the resends doubled the Connects in flight after a loss, and each answer restarted the
 *  numbering); one try per packet, not ten in a row on a dead network, and the error logged at most every 2 s; a
 *  packet's number and its saved copy are taken under the same lock as the reset in connect, so a packet of the
 *  old session can't be saved under the new numbering.
 *  pc.50 / DRAUPNE Link 1.0.64: the resend timer on a ScheduledExecutorService (the monotonic clock). java.util.Timer
 *  waits on the wall clock: a time correction backwards by N seconds stopped every resend for N seconds; and an
 *  exception in one pass ended the timer for the rest of the session.
 */

package de.droiddrone.common;

import static de.droiddrone.common.Log.log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UdpSender {
    private final DatagramSocket socket;
    private final Object packetNumberLock = new Object();
    private short nextPacketNumber;
    private final ConcurrentHashMap<Short, SavedPacket> savedPackets = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService timer;
    private volatile InetAddress ip;
    private volatile int port;
    private volatile boolean active;
    private volatile int pingMs;
    private volatile int timerId;
    /** pc.31: send errors are logged at most every ERROR_LOG_GAP_MS, with the count since the last line. */
    private static final long ERROR_LOG_GAP_MS = 2000;
    private long lastErrorLogMs;
    private int errorsSinceLog;

    public UdpSender(DatagramSocket socket) {
        this.socket = socket;
        timerId = 0;
        pingMs = 100;
        nextPacketNumber = -1;
        active = false;
        ip = null;
        port = -1;
    }

    public void setAddress(InetAddress ip, int port){
        this.ip = ip;
        this.port = port;
    }

    public void connect(InetAddress ip, int port){
        setAddress(ip, port);
        synchronized (packetNumberLock) {
            nextPacketNumber = -1;
            savedPackets.clear(); // pc.31: under the lock that numbers and saves a packet (sendPacket)
        }
        active = true;
        timerId++;
        ScheduledExecutorService old = timer;
        if (old != null) old.shutdown(); // the last session's timer: gone, not left to cancel the new one
        final ScheduledExecutorService own = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "udpResend");
            t.setDaemon(true);
            return t;
        });
        timer = own;
        final int id = timerId;
        own.scheduleWithFixedDelay(() -> {
            if (active && id == timerId) {
                try {
                    timerRun();
                } catch (RuntimeException e) {
                    sendError("resend timer", e); // this pass lost; the next one runs
                }
            } else {
                own.shutdown(); // its own timer, never the field (a newer session's)
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
    }

    private void timerRun() {
        ArrayList<Short> keysToRemove = new ArrayList<>();
        long time = SavedPacket.nowMs(); // 1.0.53: the stamps' clock, not the wall clock
        for (ConcurrentHashMap.Entry<Short, SavedPacket> entry : savedPackets.entrySet()) {
            short key = entry.getKey();
            SavedPacket packet = entry.getValue();
            if (time >= packet.timestampCreated + UdpCommon.getPacketLifeTimeMs(packet.packetName)) keysToRemove.add(key);
            if (UdpCommon.isSendPacketReceived(packet.packetName) && time >= packet.timestamp + (long)(pingMs * 1.2f) + 5){
                packet.timestamp = time;
                resendPacket(key);
            }
        }
        for (short key : keysToRemove) savedPackets.remove(key);
    }
    
    public InetAddress getIp(){
    	return this.ip;
    }
    
    public int getPort(){
    	return this.port;
    }

    public void setPing(int pingMs){
        if (pingMs < 0 || pingMs > 1000) return;
        this.pingMs = pingMs;
    }

    public void sendPacket(byte[] data){
        if (data == null || !active || socket == null || socket.isClosed()) return;
        int size = data.length;
        if (size == 0) return;
        byte packetName = data[0];
        boolean isNumbered = UdpCommon.isPacketNumbered(packetName);
        if (isNumbered) {
            if (size < 3) return;
            short num = -1;
            synchronized (packetNumberLock) {
                // a Connect keeps -1 and isn't saved: the apps send it again themselves, and the far side answers
                // each (pc.31; the resend timer sent every Connect three or four times more on a slow link)
                if (packetName != UdpCommon.Connect) num = getNextPacketNumber();
                data[1] = (byte) (num >> 8);
                data[2] = (byte) num;
                if (packetName != UdpCommon.Connect) savedPackets.put(num, new SavedPacket(packetName, num, data, ip, port));
            }
        }
        try {
            // pc.31: one try - upstream tried ten times in a row with a stack trace each, a flood on a dead network
            socket.send(new DatagramPacket(data, size, ip, port));
        }catch (Exception e){
            sendError("sendPacket", e);
        }
    }

    /** A send error, logged at most every ERROR_LOG_GAP_MS (a dead network fails every packet). */
    private void sendError(String what, Exception e) {
        long now = SavedPacket.nowMs();
        synchronized (packetNumberLock) {
            errorsSinceLog++;
            if (now - lastErrorLogMs < ERROR_LOG_GAP_MS) return;
            lastErrorLogMs = now;
            int n = errorsSinceLog;
            errorsSinceLog = 0;
            log(what + " error: " + e + (n > 1 ? " (" + n + " send errors in the last " + ERROR_LOG_GAP_MS / 1000 + " s)" : ""));
        }
    }

    public void requestPacket(ArrayList<Integer> packetNumbers){
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.RequestPackets);
            for (int num : packetNumbers) {
                packetData.daos.writeShort(num);
            }
            sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("requestPacket error: " + e);
        }
    }

    public void resendPacket(short packetNumber){
        if (!active) return;
        try {
            SavedPacket packet = savedPackets.get(packetNumber);
            if (packet == null) return;
            byte[] data = packet.data;
            if (data == null || data.length == 0) return;
            DatagramPacket pak = new DatagramPacket(data, data.length, ip, port);
            socket.send(pak);
        } catch (Exception e) {
            sendError("resendPacket", e);
        }
    }

    public void removePacket(short packetNumber){
        savedPackets.remove(packetNumber);
    }

    public void sendConnect(int clientType, String key, int version){
        Thread th = new Thread(() -> {
            try {
                UdpPacketData packetData = new UdpPacketData(UdpCommon.Connect, -1);
                packetData.daos.writeByte(clientType);
                packetData.daos.writeUTF(key);
                packetData.daos.writeShort(version);
                sendPacket(packetData.getData());
            } catch (Exception e) {
                e.printStackTrace();
                log("sendConnect error: " + e);
            }
        });
        th.start();
    }

    /**
     * DRAUPNE (1.0.49 / pc.38): what a Ping is stamped with and its Pong measured against - a monotonic ms, as only the
     * sender reads it back (the far side echoes it). On the wall clock a time correction gave one wild ping, which
     * could drop a video tier.
     */
    public static long pingClockMs() {
        return System.nanoTime() / 1_000_000;
    }

    public void sendPing(boolean toEndPoint){
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.Ping);
            packetData.daos.writeBoolean(toEndPoint);
            packetData.daos.writeLong(pingClockMs());
            sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendPing error: " + e);
        }
    }

    public void sendPingForViewer(){
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.Ping);
            packetData.daos.writeBoolean(true);
            packetData.daos.writeLong(pingClockMs());
            packetData.daos.writeByte(0);//clientId placeholder
            sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendPingForViewer error: " + e);
        }
    }

    public void sendPong(boolean toEndPoint, long time, byte target){
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.Pong);
            packetData.daos.writeBoolean(toEndPoint);
            packetData.daos.writeLong(time);
            if (target != -1) packetData.daos.writeByte(target);
            sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendPong error: " + e);
        }
    }

    public void sendPacketReceived(short num){
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.PacketReceived);
            packetData.daos.writeShort(num);
            sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendPacketReceived error: " + e);
        }
    }

    private short getNextPacketNumber() {
        synchronized (packetNumberLock) {
            if (nextPacketNumber == Short.MAX_VALUE) nextPacketNumber = -1;
            nextPacketNumber++;
            return nextPacketNumber;
        }
    }

    public boolean isActive(){
        return active;
    }

    public void close(){
        active = false;
        timerId++;
        ScheduledExecutorService t = timer;
        if (t != null) t.shutdown();
        savedPackets.clear();
    }
}
