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
 *  Modified for DRAUPNE Link (phone app pc.26 / DRAUPNE Link 1.0.33): no empty RequestPackets (upstream sent one
 *  on every poll while a gap was already asked for: a packet per ms on a link that is losing packets). The client
 *  side (the PC, or the phone over a relay) takes the far side's answer to its own Connect while it still counts
 *  the old session as up: the far side has started a new session, and this side starts over with it. Upstream
 *  dropped that answer, and the two sides then disagreed on the packet numbers (the new session's Config never
 *  taken) until the old session's 4 s timer ran out - seconds without sticks after a short blackout.
 *  pc.31 / DRAUPNE Link 1.0.39: a timed-out session is not brought back by a stray packet (addPacket tested the
 *  field, not isConnected(): any packet, keyless, revived it); a gap is waited for (1.5 x ping, asked for meanwhile)
 *  before it is skipped, the buffer is at least MIN_BUFFER_SIZE (it settled at 1 on sparse traffic, so every gap
 *  was skipped at once and RequestPackets never went); only true duplicates are acknowledged, never a packet that
 *  was skipped over (the sender then stopped resending a packet nobody took); the nearest packet after a gap is
 *  found across the number wrap; one lock for the receive and buffer threads. (The Connects in flight after a loss
 *  each restarted the numbering: a pc.31 phone answers only the first, see its Udp.receiverRun - this side still
 *  takes every answer, as an older phone starts a new session on each.)
 */

package de.droiddrone.common;

import static de.droiddrone.common.Log.log;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ReceiverBuffer {
    private final boolean isServer;
    private final UdpSender udpSender;
    private final String controlKey, viewerKey;
    private final int disconnectTimeMs = 4000;
    private final ConcurrentHashMap<Short, SavedPacket> numberedBuffer = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SavedPacket> unnumberedBuffer = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SavedPacket> rejectedPackets = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Short, Long> lastRequestedPackets = new ConcurrentHashMap<>();
    /** pc.31: the receive thread (addPacket) and the buffer thread (getNextPacket, the timer) share the numbering. */
    private final Object lock = new Object();
    private volatile short nextPacketNum;
    private volatile int bufferSize;
    private int packetCount, packetTimer, pingTimer;
    private final ArrayList<Integer> bufferSizes = new ArrayList<>();
    private final ArrayList<Integer> pings = new ArrayList<>();
    private volatile int pingMs;
    private volatile int lastPacketTimer;
    private volatile boolean isActive;
    private volatile boolean isConnected;
    private int recoverCounter;
    private final long createdTimestamp;
    /** pc.31: the buffer never counts fewer packets than this (on sparse traffic it settled at 1: no gap waited for). */
    static final int MIN_BUFFER_SIZE = 4;
    /** pc.31: a gap is waited for at least this long (and 1.5 x ping) before it is skipped. */
    static final int MIN_GAP_WAIT_MS = 30;
    private static final int MAX_PACKET_NUM_DIFF = 1000;
    /** Numbers skipped over (never delivered): a late copy is not acknowledged as if it had been taken. */
    private final java.util.LinkedHashMap<Short, Boolean> skipped = new java.util.LinkedHashMap<Short, Boolean>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Short, Boolean> eldest) {
            return size() > 256;
        }
    };

    public ReceiverBuffer(UdpSender udpSender, boolean isServer, String controlKey, String viewerKey){
        this.udpSender = udpSender;
        this.isServer = isServer;
        this.controlKey = controlKey;
        this.viewerKey = viewerKey;
        bufferSize = 30;
        packetCount = 0;
        packetTimer = 0;
        pingTimer = 0;
        nextPacketNum = 0;
        pingMs = 100;
        lastPacketTimer = 0;
        recoverCounter = 0;
        isActive = true;
        isConnected = false;
        createdTimestamp = System.currentTimeMillis();
    }

    public long getCreatedTimestamp(){
        return createdTimestamp;
    }

    public void addPacket(DatagramPacket packet) {
        if (!isActive) return;
        int packetSize = packet.getLength();
        InetAddress ip = packet.getAddress();
        int port = packet.getPort();
        if (packetSize < 1) return;
        byte[] tmp = packet.getData();
        byte[] data = new byte[packetSize];
        System.arraycopy(tmp, 0, data, 0, packetSize);
        DataReader buffer = new DataReader(data, true);
        byte packetName = buffer.readByte();
        synchronized (lock) {
            if (UdpCommon.isPacketNumbered(packetName)){
                short num = buffer.readShort();
                if (num < -1) return;
                if (packetName == UdpCommon.Connect){
                    if (isConnected() && isServer) return; // a client's Connect answered: a new session (see the header)
                    byte clientType = buffer.readByte();
                    String key = buffer.readUTF();
                    if (key == null) return;
                    if ((clientType == 0 || clientType == 1) && key.equals(controlKey) || clientType == 2 && key.equals(viewerKey)){
                        // every answer is taken (an older phone starts a new session on each Connect); a pc.31 phone
                        // answers only the first of the Connects in flight after a loss (Udp.receiverRun)
                        restart();
                        udpSender.connect(ip, port);
                        isConnected = true;
                        lastPacketTimer = disconnectTimeMs;
                    }else{
                        return;
                    }
                }
                if (!isConnected()) return; // pc.31: not the field - a timed-out session stays down until a Connect
                lastPacketTimer = disconnectTimeMs;
                if (ahead(num) < MAX_PACKET_NUM_DIFF){
                    numberedBuffer.put(num, new SavedPacket(packetName, num, data, ip, port));
                    packetCount++;
                    recoverCounter = 0;
                }else{
                    // taken before (its acknowledgement lost): acknowledged again; skipped over: not (it never was)
                    if (isDuplicate(num)) rejectedPackets.add(new SavedPacket(packetName, num, data, ip, port));
                    // every packet outside the window counts, as upstream: the two sides' numbering apart (a restart
                    // on one side only) mends itself
                    recoverCounter++;
                    if (recoverCounter > bufferSize * 2){
                        restart();
                        numberedBuffer.put(num, new SavedPacket(packetName, num, data, ip, port));
                        nextPacketNum = num;
                    }
                }
            }else{
                if (!isConnected()) return;
                lastPacketTimer = disconnectTimeMs;
                unnumberedBuffer.add(new SavedPacket(packetName, (short) -1, data, ip, port));
            }
        }
    }

    /** How far num is ahead of the next number to deliver, across the wrap (0..32767). */
    private int ahead(short num) {
        if (nextPacketNum == -1) return num + 1; // after a restart: the Connect (-1), then 0, 1, ...
        if (num < 0) return Integer.MAX_VALUE;
        return (num - nextPacketNum + 32768) % 32768;
    }

    /** A number delivered already: just behind the next one, and not skipped over. */
    private boolean isDuplicate(short num) {
        if (num < 0 || nextPacketNum < 0) return false;
        int behind = (nextPacketNum - num + 32768) % 32768;
        return behind >= 1 && behind <= MAX_PACKET_NUM_DIFF && !skipped.containsKey(num);
    }

    private void advance() {
        nextPacketNum = nextPacketNum == Short.MAX_VALUE ? 0 : (short) (nextPacketNum + 1);
    }

    public SavedPacket getNextPacket(){
        // pc.31: a loop, not recursion, and the packets' handling (pongs, resends, the echo) outside the lock
        while (isActive) {
            SavedPacket packet = unnumberedBuffer.poll();
            if (packet == null) packet = nextNumbered();
            if (packet == null) return null;
            if (checkPacket(packet)) return packet;
        }
        return null;
    }

    /** The next numbered packet in order, or null: a gap is asked for, and skipped only after it was waited for. */
    private SavedPacket nextNumbered() {
        ArrayList<Integer> requestedPackets = null;
        synchronized (lock) {
            SavedPacket packet = numberedBuffer.remove(nextPacketNum);
            if (packet != null) {
                advance();
                return packet;
            }
            if (numberedBuffer.isEmpty()) return null;
            long now = System.currentTimeMillis();
            short first = -1;
            int nearest = Integer.MAX_VALUE;
            long oldest = Long.MAX_VALUE; // the gap is as old as the oldest packet waiting behind it
            for (SavedPacket waiting : numberedBuffer.values()) {
                int a = ahead(waiting.packetNum);
                if (a < nearest) {
                    nearest = a;
                    first = waiting.packetNum;
                }
                oldest = Math.min(oldest, waiting.timestampCreated);
            }
            int waitMs = Math.max(MIN_GAP_WAIT_MS, Math.round(pingMs * 1.5f));
            if (nearest != Integer.MAX_VALUE && (numberedBuffer.size() >= bufferSize || now - oldest >= waitMs)) {
                for (int i = 0; i < nearest && i < 256 && nextPacketNum >= 0; i++) {
                    skipped.put(nextPacketNum, Boolean.TRUE);
                    advance();
                }
                nextPacketNum = first;
                packet = numberedBuffer.remove(first);
                if (packet != null) advance();
                return packet;
            }
            requestedPackets = new ArrayList<>();
            int num;
            for (int i = 0; i < 10; i++) {
                num = nextPacketNum + i;
                if (num > Short.MAX_VALUE) num = num - Short.MAX_VALUE - 1;
                if (num < 0) continue;
                if (numberedBuffer.containsKey((short)num)) break;
                if (lastRequestedPackets.containsKey((short)num)) continue;
                requestedPackets.add(num);
                lastRequestedPackets.put((short)num, now + pingMs + 20);
            }
        }
        if (!requestedPackets.isEmpty()) udpSender.requestPacket(requestedPackets);
        return null;
    }

    private boolean checkPacket(SavedPacket packet){
        try {
            DataReader buffer = new DataReader(packet.data, true);
            byte packetName = buffer.readByte();
            short num;
            if (UdpCommon.isPacketNumbered(packetName)){
                num = buffer.readShort();
                if (UdpCommon.isSendPacketReceived(packetName)) udpSender.sendPacketReceived(num);
            }
            switch (packetName) {
                case UdpCommon.Ping:
                case UdpCommon.Pong: {
                    boolean toEndPoint = buffer.readBoolean();
                    long time = buffer.readLong();
                    byte target = -1;
                    if (buffer.getRemaining() > 0) target = buffer.readByte();
                    if (toEndPoint && isServer) return true;
                    if (packetName == UdpCommon.Ping) {
                        udpSender.sendPong(toEndPoint, time, target);
                    } else {
                        if (toEndPoint) {
                            return true;
                        } else {
                            calculatePing(time);
                        }
                    }
                    return false;
                }
                case UdpCommon.PacketReceived:
                {
                    num = buffer.readShort();
                    udpSender.removePacket(num);
                    return false;
                }
                case UdpCommon.RequestPackets:
                {
                    int c = buffer.getRemaining() / 2;
                    for (int i = 0; i < c; i++) {
                        num = buffer.readShort();
                        udpSender.resendPacket(num);
                    }
                    return false;
                }
                case UdpCommon.Connect:
                {
                    if (isServer) udpSender.sendPacket(packet.data);
                    return false;
                }
            }
        }catch (Exception e){
            log("checkPacket error: " + e);
        }
        return true;
    }

    public void processTimer(){
        if (!isActive) return;
        bufferCleanup();
        processLastPacketTimer();
        processPing();
        calculateBufferSize();
        processRejectedPackets();
    }

    private void bufferCleanup(){
        ArrayList<Short> keysToRemove = new ArrayList<>();
        long currentTime = System.currentTimeMillis();
        for (ConcurrentHashMap.Entry<Short, Long> entry : lastRequestedPackets.entrySet()) {
            short key = entry.getKey();
            long time = entry.getValue();
            if (currentTime >= time) keysToRemove.add(key);
        }
        for (short key : keysToRemove) lastRequestedPackets.remove(key);

        keysToRemove.clear();
        final int maxLatency = pingMs * 4;
        for (ConcurrentHashMap.Entry<Short, SavedPacket> entry : numberedBuffer.entrySet()) {
            short key = entry.getKey();
            SavedPacket packet = entry.getValue();
            if (currentTime >= packet.timestampCreated + UdpCommon.getPacketLifeTimeMs(packet.packetName) + maxLatency) keysToRemove.add(key);
        }
        for (short key : keysToRemove) {
            numberedBuffer.remove(key);
        }
    }

    private void processLastPacketTimer(){
        if (lastPacketTimer > 0) lastPacketTimer--;
    }

    private void processPing(){
        if (!isConnected()) return;
        pingTimer++;
        if (pingTimer >= 500){
            pingTimer = 0;
            udpSender.sendPing(false);
        }
    }

    private void calculateBufferSize(){
        packetTimer++;
        if (packetTimer >= pingMs){
            packetTimer = 0;
            int size = Math.round(packetCount * 1.5f) + 1;
            packetCount = 0;
            bufferSizes.add(size);
            if (bufferSizes.size() >= 10){
                int avgSize = 0;
                for (int c : bufferSizes) avgSize += c;
                avgSize = avgSize / bufferSizes.size();
                bufferSizes.remove(0);
                bufferSize = Math.max(MIN_BUFFER_SIZE, avgSize);
            }
        }
    }

    private void processRejectedPackets(){
        SavedPacket packet = rejectedPackets.poll();
        while (packet != null){
            if (UdpCommon.isPacketNumbered(packet.packetName) && UdpCommon.isSendPacketReceived(packet.packetName)){
                udpSender.sendPacketReceived(packet.packetNum);
            }
            packet = rejectedPackets.poll();
        }
    }

    private void calculatePing(long time){
        int ping = (int) (UdpSender.pingClockMs() - time); // DRAUPNE: the clock UdpSender stamped the ping with
        if (Math.abs(ping - pingMs) > 1000) return;
        pings.add(ping);
        int avgPing = 0;
        for (int p : pings) avgPing += p;
        avgPing = avgPing / pings.size();
        pingMs = avgPing;
        udpSender.setPing(avgPing);
        if (pings.size() >= 10) pings.remove(0);
    }

    public int getCurrentPingMs(){
        return pingMs;
    }

    public boolean isConnected(){
        return (isActive && isConnected && lastPacketTimer > 0);
    }

    /** Under the lock (addPacket). */
    private void restart(){
        nextPacketNum = -1;
        recoverCounter = 0;
        skipped.clear();
        numberedBuffer.clear();
        unnumberedBuffer.clear();
        rejectedPackets.clear();
        lastRequestedPackets.clear();
    }

    public void close(){
        synchronized (lock) {
            isActive = false;
            isConnected = false;
            lastPacketTimer = 0;
            numberedBuffer.clear();
            unnumberedBuffer.clear();
            rejectedPackets.clear();
            lastRequestedPackets.clear();
        }
    }
}
