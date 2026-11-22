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
 *  The timers (the 4 s session timeout, the ping every 0.5 s, the buffer size's window of one ping) counted calls of
 *  processTimer, one a millisecond as long as the loop's 1 ms sleep was 1 ms: a busy phone or Windows' timer made
 *  the 4 s timeout 6 s or more. They count the clock's milliseconds now (at most MAX_STEP_MS a call).
 *  The unnumbered packets waiting for the buffer thread are held to MAX_UNNUMBERED (the oldest dropped, DropOldest).
 */

package de.droiddrone.common;

import static de.droiddrone.common.Log.log;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

public class ReceiverBuffer {
    private final boolean isServer;
    private final UdpSender udpSender;
    private final String controlKey, viewerKey;
    private final int disconnectTimeMs = 4000;
    private final ConcurrentHashMap<Short, SavedPacket> numberedBuffer = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SavedPacket> unnumberedBuffer = new ConcurrentLinkedQueue<>();
    /**
     * DRAUPNE Link: the most unnumbered packets waiting for the buffer thread, which takes them every millisecond or so:
     * over half a minute of the PC's (a ping and a few small packets a second) or of the phone's telemetry, 1.1 MB at
     * most. One more drops the oldest (DropOldest, said once a minute). Before there was no bound. Counted beside the
     * queue (its own size() walks it): one atomic add per packet, nothing more.
     */
    static final int MAX_UNNUMBERED = 1024;
    private final java.util.concurrent.atomic.AtomicInteger unnumberedCount = new java.util.concurrent.atomic.AtomicInteger();
    private final DropOldest.Said unnumberedDropped = new DropOldest.Said("Unnumbered packets", MAX_UNNUMBERED);
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
    /** The timers' monotonic clock (SavedPacket.nowMs; a test's own) and its reading at the last processTimer. */
    private final LongSupplier clock;
    private long timerAtMs;
    /**
     * The most one processTimer call counts: a stall of the buffer thread itself (it is the one that counts) is not
     * the far side's silence, and a packet that came meanwhile still finds its session up.
     */
    static final int MAX_STEP_MS = 100;
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
        this(udpSender, isServer, controlKey, viewerKey, SavedPacket::nowMs);
    }

    /** With the timers on the given monotonic ms clock (tests). */
    public ReceiverBuffer(UdpSender udpSender, boolean isServer, String controlKey, String viewerKey, LongSupplier clock){
        this.udpSender = udpSender;
        this.clock = clock;
        timerAtMs = clock.getAsLong();
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
                        answerAwaited = false;
                    }else{
                        return;
                    }
                }
                if (!isConnected()) return; // pc.31: not the field - a timed-out session stays down until a Connect
                lastPacketTimer = disconnectTimeMs;
                if (packetName != UdpCommon.Connect && freshSessionOfTheFarSide(num)) {
                    // F-057 (DRAUPNE Link 1.0.71): this side's Connect began the far side's new session and only its
                    // answer was lost (a one-way cut of 2 - 4 s): the new session's first numbers come. Started over
                    // as the answer would have - before, the numbering stayed apart until recoverCounter mended it,
                    // a second more to LINK READY
                    restart();
                    udpSender.connect(ip, port);
                    nextPacketNum = 0;
                    answerAwaited = false;
                    unansweredStarts++;
                }
                if (ahead(num) < MAX_PACKET_NUM_DIFF){
                    if (packetName != UdpCommon.Connect) answerAwaited = false; // the session's numbering goes on: no new one
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
                if (unnumberedCount.get() >= MAX_UNNUMBERED && unnumberedBuffer.poll() != null) {
                    unnumberedCount.decrementAndGet();
                    String said = unnumberedDropped.dropped(clock.getAsLong());
                    if (said != null) log(said);
                }
                unnumberedBuffer.add(new SavedPacket(packetName, (short) -1, data, ip, port));
                unnumberedCount.incrementAndGet();
            }
        }
    }

    /** F-057: a Connect of this side (the client) is out and unanswered; set by the link, cleared by an answer or the session's own numbers. */
    private volatile boolean answerAwaited;
    /** Behind the next number by this many at most: a late copy of this session's, never a new session's first. */
    static final int LATE_COPY_WINDOW = 256;
    /** How often the far side's new session was taken without its answer (F-057). */
    private volatile int unansweredStarts;

    /** The client side has sent a Connect: until an answer, or a packet in the session's own numbering, comes. */
    public void awaitAnswer() {
        if (!isServer) answerAwaited = true;
    }

    public int getUnansweredStarts() {
        return unansweredStarts;
    }

    /**
     * Under the lock. A numbered packet that can only be the far side's new session: a Connect of ours is out
     * unanswered, and the number is a new session's first ones (0 .. MAX_PACKET_NUM_DIFF) far outside this session's
     * window - neither ahead of the next number nor within LATE_COPY_WINDOW behind it (a late copy of this session's).
     */
    private boolean freshSessionOfTheFarSide(short num) {
        if (isServer || !answerAwaited || num < 0 || num >= MAX_PACKET_NUM_DIFF || nextPacketNum < 0) return false;
        int behind = (nextPacketNum - num + 32768) % 32768;
        return ahead(num) >= MAX_PACKET_NUM_DIFF && behind > LATE_COPY_WINDOW;
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
            if (packet != null && unnumberedCount.decrementAndGet() < 0) unnumberedCount.set(0); // a clear in between
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
            long now = SavedPacket.nowMs(); // 1.0.53: the stamps' clock
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

    /** The timers and the clean-up; called every millisecond or so by the buffer thread. */
    public void processTimer(){
        if (!isActive) return;
        long now = clock.getAsLong();
        int elapsed = (int) Math.max(0, Math.min(MAX_STEP_MS, now - timerAtMs));
        timerAtMs = now;
        bufferCleanup();
        processLastPacketTimer(elapsed);
        processPing(elapsed);
        calculateBufferSize(elapsed);
        processRejectedPackets();
    }

    private void bufferCleanup(){
        ArrayList<Short> keysToRemove = new ArrayList<>();
        long currentTime = SavedPacket.nowMs(); // 1.0.53: the stamps' clock
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

    private void processLastPacketTimer(int elapsed){
        if (lastPacketTimer > 0) lastPacketTimer = Math.max(0, lastPacketTimer - elapsed);
    }

    private void processPing(int elapsed){
        if (!isConnected()) return;
        pingTimer += elapsed;
        if (pingTimer >= 500){
            pingTimer = 0;
            udpSender.sendPing(false);
        }
    }

    private void calculateBufferSize(int elapsed){
        packetTimer += elapsed;
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
        unnumberedCount.set(0);
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
            unnumberedCount.set(0);
            rejectedPackets.clear();
            lastRequestedPackets.clear();
        }
    }
}
