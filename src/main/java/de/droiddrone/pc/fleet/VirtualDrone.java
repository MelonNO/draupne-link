package de.droiddrone.pc.fleet;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.DetectionAreas;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.MotionRecord;
import de.droiddrone.common.Observe;
import de.droiddrone.common.PcAudio;
import de.droiddrone.common.ReceiverBuffer;
import de.droiddrone.common.SavedPacket;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.SleepCheck;
import de.droiddrone.common.Takeover;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;
import de.droiddrone.common.UdpSender;
import de.droiddrone.common.VoiceActivity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static de.droiddrone.common.PacketIds.DETECTION_SET;
import static de.droiddrone.common.PacketIds.IDLE_STICKS;
import static de.droiddrone.common.PacketIds.KEY_FRAME_REQUEST;
import static de.droiddrone.common.PacketIds.NAME_ASSIGN;
import static de.droiddrone.common.PacketIds.NAME_QUERY;
import static de.droiddrone.common.PacketIds.NAME_REPLY;
import static de.droiddrone.common.PacketIds.POWER_MODE;
import static de.droiddrone.common.PacketIds.REMOTE_UPDATE;
import static de.droiddrone.common.PacketIds.SET_BITRATE;
import static de.droiddrone.common.PacketIds.SLEEP_ANSWER;
import static de.droiddrone.common.PacketIds.SLEEP_HELLO;
import static de.droiddrone.common.PacketIds.SPECTATE_NO_PILOT;
import static de.droiddrone.common.PacketIds.SPECTATE_PILOT_AT;
import static de.droiddrone.common.PacketIds.SPECTATE_PILOT_QUERY;
import static de.droiddrone.common.PacketIds.VIDEO_PROFILE;

/**
 * One virtual drone: DRAUPNE Flight's side of the link (overlay Flight Udp, PowerMode, the observation) in front of a
 * virtual Betaflight ({@link FlightModel}). It speaks the real protocol with the shared Common code - ReceiverBuffer and
 * UdpSender as the phone app is built with them, Takeover, PcAudio, Observe, MotionDetector, MotionRecord - so DRAUPNE
 * Link's real link, session, telemetry, video and sound paths run end to end against it. It listens on a loopback
 * address of its own ({@link VirtualFleet}) and never opens a serial port.
 * <p>
 * What it does as the phone does: the session rules (a keyed Connect only, the pilot's own re-Connect, Takeover from
 * another PC, PILOT_AT / NO_PILOT), the stick frames' sequence and lateness rules, the idle sticks fed to the FC while
 * it is disarmed and its own ARM ranges say they can't arm, the PC's Config (picture size, frame rate, bitrate limit),
 * StartVideo, the video profile, SET_BITRATE, key frame requests, GetVideoConfig, AUDIO, DETECTION_SET, the power
 * modes (STANDBY, DEEP SLEEP with its check windows and hellos, OVERWATCH), OBSERVE, NAME_QUERY / NAME_ASSIGN, and its
 * telemetry at the phone's rates. What differs is said where it does.
 */
public final class VirtualDrone {
    /**
     * What it says it is: the newest phone app, so the PC uses every packet layout it knows - the one this PC hands out
     * (was a fixed pc.40, and the PC warned "old phone app" on every virtual drone).
     */
    public static final String VERSION = de.droiddrone.pc.setup.ApkShare.VERSION;
    public static final String DEVICE = "VIRTUAL DRONE";
    /** The phone's encoder steps, bit/s (StreamEncoder.baseBitRates). */
    static final int[] STEPS = {150_000, 250_000, 500_000, 1_000_000, 2_000_000, 4_000_000, 6_000_000, 8_000_000, 10_000_000,
            15_000_000, 20_000_000};
    static final short PHONE_UPDATE = 0x40F7;
    public static final int NORMAL = 0, STANDBY = 1, DEEP_SLEEP = 2, OVERWATCH = 3;
    /** The phone's own times (overlay Udp, PowerMode). */
    static final long SAME_SESSION_MS = 1000, RC_SEQ16_FORGET_MS = 1000, IDLE_AFTER_MS = 300, IDLE_EVERY_MS = 50,
            SLEEP_COUNTDOWN_MS = 1000, CHECK_WINDOW_MS = 20_000, HELLO_FIRST_MS = 200, HELLO_GAP_MS = 1500, DETECTION_KEEP_MS = 5000,
            RESTART_MS = 3000;

    // ------------------------------------------------------------------ who it is
    private final int slot;
    private final String key;
    private final InetAddress address;
    private volatile int port;
    private volatile String name;
    private final Consumer<String> log;

    // ------------------------------------------------------------------ the drone
    final FlightModel fc;
    final LinkImpairment link = new LinkImpairment();
    /**
     * Each way, under every link problem the panel sets: 30 ms, so the round trip is 60 ms longer than this PC's
     * loopback - a virtual drone feels like a real one on 4G / 5G (1.0.52, the pilot).
     */
    static final int NET_BASE_ONE_WAY_MS = 30;
    /** Above this the drone is in the air: a BTR it sees flees (metres). */
    static final double ESCAPE_ALT_M = 2;

    {
        link.setBaseMs(NET_BASE_ONE_WAY_MS);
    }
    final Scene scene;
    final SoundMaker sound;
    private volatile Recording recording;
    /** The phone gets nothing from its flight controller (the panel's FC LOST). */
    private volatile boolean fcLost;

    // ------------------------------------------------------------------ the tour's scenario (TourScenario)

    /** What the tour hears from its drone: on the fleet's threads, quick. */
    interface Events {
        /** Its overwatch found something and calls the PC (the event's first alert). */
        void alert(VirtualDrone d);

        /** A PC connected to it as pilot. */
        void connected(VirtualDrone d);

        /** It flew into the scenario's vehicle: broken, silent from now on. */
        void crashed(VirtualDrone d);
    }

    /** The scenario's vehicle and world (null: the usual scene), and who hears of it. */
    private volatile BtrScene target;
    private volatile Events events;
    private volatile BtrSound targetSound;
    /** It hit the vehicle: the link stays silent, as a broken drone's. */
    private volatile boolean broken;
    private boolean wasConnected;
    private final long startedAt = System.currentTimeMillis();

    // ------------------------------------------------------------------ the link
    private volatile ImpairedSocket socket;
    private volatile UdpSender udpSender;
    private volatile ReceiverBuffer receiverBuffer;
    private volatile int threadsGen;
    private volatile boolean running;
    private volatile long lastPilotPacketMs, sessionStartedMs;
    private volatile InetAddress lastPcIp;
    private volatile int lastPcPort;
    private volatile boolean restarting;

    // the sticks (the receive thread)
    private int lastRcSeq = -1;
    private boolean rcSeqValid;
    private volatile long lastRcTakenMs, lastRcWriteMs;
    private volatile int lastRcSeqReported = -1;
    private volatile short[] idleSticks;
    private volatile boolean idleFeeding;
    private int rcLagMinNow = -1, rcLagMinBefore = -1;
    private long rcLagBucketAt;
    private volatile int rcLagMs = -1, rcLagMaxMs = -1, rcLagDropped;
    private volatile boolean rcLagFromPc;
    private volatile long maxRcInGapMs;
    private final Object rcLock = new Object();

    // what the PC asked for
    private volatile int cameraWidth = 1280, cameraHeight = 720, cameraFps = 30, bitrateLimitMbps = 4;
    private volatile int step = 5;
    private volatile boolean keyFramesTwice, groupParity;
    private volatile boolean videoWanted;
    private final PcAudio pcAudio = new PcAudio();
    private volatile int detectionLevel;
    private volatile long detectionAskedAt;
    private volatile String updateRefusal;
    private volatile long updateRefusedAt;

    // observation
    private final Observe.Observers<String> observers = new Observe.Observers<>(Observe.MAX_OBSERVERS);
    private final java.util.Map<String, InetSocketAddress> observerAddresses = new java.util.HashMap<>();
    private volatile InetSocketAddress[] observerTargets = new InetSocketAddress[0];
    private volatile boolean observing, observeAudio;
    private volatile Observe.Request observeWant;
    private long observeKeyFrameAt;
    /** pc.45: "sound heard" to observers that muted it (the tick thread). */
    private final Observe.HeardReport observeHeard = new Observe.HeardReport();

    // power modes (the tick thread, and requests from the buffer thread: under powerLock)
    private final Object powerLock = new Object();
    private volatile int mode = NORMAL;
    private volatile int checkIntervalMin = 5;
    private long sleepAt, nextCheckAt, windowOpenedAt, nextHelloAt;
    private volatile boolean asleep, checking;
    private int burstLeft;
    private long nextBurstAt;
    final VirtualOverwatch overwatch;

    // the motion detection (the tick thread)
    private final VirtualMotionWatch motion;

    // the streams
    private final VirtualVideo video;
    private volatile Thread audioThread;
    private volatile int audioGen;
    private volatile boolean audioOn, audioHeld;
    /** pc.45: the sound thread only listens (observers that muted it): soundHeard, nothing sent. */
    private volatile boolean audioListening, soundHeard;
    private volatile String lastBeep;

    VirtualDrone(int slot, String name, String key, InetAddress address, double lat, double lon, double headingDeg,
                 FlightModel.Setup setup, Consumer<String> log) {
        this.slot = slot;
        this.key = key;
        this.address = address;
        this.name = name == null ? "" : name;
        this.log = msg -> log.accept((this.name.isEmpty() ? "Virtual drone " + (slot + 1) : this.name) + ": " + msg);
        this.fc = new FlightModel(setup, lat, lon, headingDeg, System.currentTimeMillis());
        this.scene = new Scene(this.name.isEmpty() ? "DRONE " + (slot + 1) : this.name, 1000L + slot);
        this.sound = new SoundMaker(2000L + slot);
        this.overwatch = new VirtualOverwatch(this, this.log);
        this.motion = new VirtualMotionWatch(scene, overwatch);
        this.video = new VirtualVideo(new VirtualVideo.Drone() {
            @Override public boolean running() { return running; }
            @Override public String name() { return name; }
            @Override public int cameraWidthNow() { return VirtualDrone.this.cameraWidthNow(); }
            @Override public int cameraHeightNow() { return VirtualDrone.this.cameraHeightNow(); }
            @Override public int cameraFpsNow() { return VirtualDrone.this.cameraFpsNow(); }
            @Override public int bitRateNow() { return VirtualDrone.this.bitRateNow(); }
            @Override public boolean keyFramesTwice() { return keyFramesTwice; }
            @Override public boolean groupParity() { return groupParity; }
            @Override public boolean observedOnly() { return observing && !isConnected(); }
            @Override public void renderPicture(BufferedImage img, long nowMs) { VirtualDrone.this.renderPicture(img, nowMs); }
            @Override public void streamOut(byte[] data) { VirtualDrone.this.streamOut(data); }
        }, slot, this.log);
    }

    // ------------------------------------------------------------------ start, stop, restart

    /** Opens its socket on its loopback address (port 0: any free one) and starts its threads. */
    void start(int wantedPort) throws IOException {
        running = true;
        openSocket(wantedPort);
        Thread tick = new Thread(this::tickRun, "fleetTick" + slot);
        tick.setDaemon(true);
        tick.setPriority(Thread.MIN_PRIORITY + 1);
        tick.start();
    }

    private void openSocket(int wantedPort) throws IOException {
        ImpairedSocket s = new ImpairedSocket(new InetSocketAddress(address, wantedPort), link);
        s.setReceiveBufferSize(256 * 1024);
        s.setSendBufferSize(UdpCommon.packetLength * 64);
        port = s.getLocalPort();
        socket = s;
        UdpSender sender = new UdpSender(s);
        udpSender = sender;
        receiverBuffer = new ReceiverBuffer(sender, true, key, null);
        int gen = ++threadsGen;
        startThread(() -> receiveRun(s, gen), "fleetReceive" + slot, Thread.NORM_PRIORITY);
        startThread(() -> bufferRun(gen), "fleetBuffer" + slot, Thread.MIN_PRIORITY + 1);
    }

    private void closeSocket() {
        threadsGen++;
        ImpairedSocket s = socket;
        socket = null;
        UdpSender sender = udpSender;
        if (sender != null) sender.close();
        ReceiverBuffer b = receiverBuffer;
        if (b != null) b.close();
        if (s != null) s.close();
    }

    private static void startThread(Runnable r, String name, int priority) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(priority);
        t.start();
    }

    /** Everything stops; the socket closes. */
    void stop() {
        running = false;
        video.stop();
        stopAudio();
        overwatch.shutdown();
        closeSocket();
        Recording r = recording;
        recording = null;
        if (r != null) r.close();
    }

    /**
     * The phone app restarts (the panel's PHONE RESTART): everything of the app goes - its session, the idle sticks, the
     * observers, the power mode, where the PC was - and comes back RESTART_MS later on the same port. The flight
     * controller runs on and loses its sticks meanwhile: a drone in the air goes to failsafe, as it would.
     */
    public void restartApp() {
        if (restarting || !running) return;
        restarting = true;
        log.accept("phone app restarts (back in " + RESTART_MS / 1000 + " s)");
        video.stop();
        stopAudio();
        overwatch.end("the app restarted");
        int keep = port;
        closeSocket();
        ImpairedSocket.LATER.schedule(() -> {
            try {
                if (!running) return;
                lastPcIp = null;
                lastPcPort = 0;
                idleSticks = null;
                pcAudio.forget();
                synchronized (observers) {
                    observers.clear();
                    observerAddresses.clear();
                    refreshObserverTargets();
                }
                observing = false;
                synchronized (powerLock) {
                    mode = NORMAL;
                    asleep = checking = false;
                    sleepAt = 0;
                }
                videoWanted = false;
                if (broken && target == null) { // crashed into the panel's BTR or a soldier: a new drone on the spot
                    broken = false;
                    link.clear();
                }
                openSocket(keep);
                log.accept("phone app back: new session on next Connect");
            } catch (IOException e) {
                log.accept("phone app can't reopen its socket: " + e.getMessage());
            } finally {
                restarting = false;
            }
        }, RESTART_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * The handover's address change (no Tailscale): the phone's socket moves to another port, as a phone whose network
     * changed would be seen from outside. The PC's link, fixed on the old port, loses it.
     */
    public void moveToNewPort() {
        try {
            closeSocket();
            openSocket(0);
            log.accept("now on port " + port + " (new address, like a cell change without Tailscale)");
        } catch (IOException e) {
            log.accept("can't move to a new port: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ receiving (the phone's receiverRun)

    private void receiveRun(ImpairedSocket s, int gen) {
        byte[] buf = new byte[UdpCommon.packetLength + 64];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running && gen == threadsGen && !s.isClosed()) {
            try {
                p.setLength(buf.length);
                s.receive(p);
                if (p.getLength() < 1) continue;
                long delay = link.decide(false, System.currentTimeMillis());
                if (delay < 0) continue;
                byte[] d = java.util.Arrays.copyOf(p.getData(), p.getLength());
                InetAddress from = p.getAddress();
                int fromPort = p.getPort();
                if (delay == 0) handle(d, from, fromPort);
                else ImpairedSocket.LATER.schedule(() -> handle(d, from, fromPort), delay, TimeUnit.MILLISECONDS);
            } catch (IOException e) {
                if (running && gen == threadsGen && !s.isClosed()) log.accept("receive error: " + e.getMessage());
            } catch (RuntimeException e) {
                log.accept("receive error: " + e);
            }
        }
    }

    /** One datagram from the network: the phone's receive thread's rules, in its order. */
    synchronized void handle(byte[] d, InetAddress from, int fromPort) {
        if (socket == null) return;
        int len = d.length;
        if (d[0] == NAME_QUERY || d[0] == NAME_ASSIGN) {
            answerName(d, len, from, fromPort);
            return;
        }
        if (d[0] == Observe.OBSERVE || d[0] == Observe.STOP) {
            onObserve(d, len, from, fromPort);
            return;
        }
        if (d[0] == de.droiddrone.common.FleetProtocol.MODE) { // pc.40: fleet management, in any state but a session
            onFleetMode(d, len, from);
            return;
        }
        ReceiverBuffer buffer = receiverBuffer;
        UdpSender sender = udpSender;
        if (buffer == null || sender == null) return;
        long now = System.currentTimeMillis();
        if (buffer.isConnected()) {
            if (from.equals(sender.getIp()) && fromPort == sender.getPort()) {
                lastPilotPacketMs = now;
                lastPcIp = from;
                lastPcPort = fromPort;
                if (d[0] == UdpCommon.Connect && keyMatches(d, len, 4)) {
                    if (now - sessionStartedMs < SAME_SESSION_MS) return;
                    log.accept("pilot reconnects (link was lost): new session");
                    newSession();
                    sessionStartedMs = now;
                    buffer = receiverBuffer;
                }
            } else {
                if (!otherPcWhileConnected(d, len, from, fromPort)) return;
                buffer = receiverBuffer;
            }
        } else {
            if (d[0] == SPECTATE_PILOT_QUERY) {
                answerSpectator(d, len, from, fromPort, false);
                return;
            }
            rcSeqValid = false;
            resetRcLag();
            if (d[0] == SLEEP_ANSWER) {
                if (len >= 2 && from.equals(lastPcIp)) sleepAnswer(d[1] != 0);
                return;
            }
            if (d[0] != UdpCommon.Connect) return;
            if (!keyMatches(d, len, 4)) {
                log.accept("PC at " + from.getHostAddress() + " tried another key");
                return;
            }
            if (lastPcIp != null && !(from.equals(lastPcIp) && fromPort == lastPcPort)) {
                if (!Takeover.allowed(from.equals(lastPcIp), fcDisarmed(), now - lastPilotPacketMs)) {
                    answerSpectator(d, len, from, fromPort, true);
                    return;
                }
                if (!from.equals(lastPcIp)) log.accept("new session from " + from.getHostAddress() + " (pilot silent, FC disarmed)");
            }
            lastPilotPacketMs = now;
            sessionStartedMs = now;
            idleSticks = null;
        }
        if (d[0] == IDLE_STICKS) {
            if (buffer.isConnected()) handleIdleSticks(d, len);
            return;
        }
        if (d[0] == UdpCommon.RcFrame) {
            if (buffer.isConnected() && mode == NORMAL) handleRcFrame(d, len);
            return;
        }
        buffer.addPacket(new DatagramPacket(d, len, from, fromPort));
    }

    private boolean otherPcWhileConnected(byte[] d, int len, InetAddress from, int fromPort) {
        long now = System.currentTimeMillis();
        long silent = now - lastPilotPacketMs;
        boolean disarmed = fcDisarmed();
        boolean pilotAlive = silent < Takeover.PILOT_ALIVE_MS || !disarmed;
        if (d[0] == UdpCommon.Connect) {
            if (!keyMatches(d, len, 4)) return false;
            boolean sameAddress = from.equals(udpSender.getIp());
            if (Takeover.allowed(sameAddress, disarmed, silent)) {
                log.accept("new session from " + from.getHostAddress() + ":" + fromPort + (sameAddress ? " (pilot's address)" : " (pilot silent)"));
                newSession();
                sessionStartedMs = now;
                return true;
            }
        }
        answerSpectator(d, len, from, fromPort, pilotAlive);
        return false;
    }

    private void newSession() {
        ReceiverBuffer old = receiverBuffer;
        receiverBuffer = new ReceiverBuffer(udpSender, true, key, null);
        if (old != null) old.close();
        rcSeqValid = false;
        resetRcLag();
        idleSticks = null;
    }

    private boolean keyMatches(byte[] d, int len, int keyAt) {
        if (len < keyAt + 2) return false;
        int n = ((d[keyAt] & 0xff) << 8) | (d[keyAt + 1] & 0xff);
        return keyAt + 2 + n <= len && key.equals(new String(d, keyAt + 2, n, java.nio.charset.StandardCharsets.UTF_8));
    }

    private void answerSpectator(byte[] d, int len, InetAddress from, int fromPort, boolean flying) {
        int keyAt;
        if (d[0] == SPECTATE_PILOT_QUERY) keyAt = 1;
        else if (flying && d[0] == UdpCommon.Connect) keyAt = 4;
        else return;
        if (!keyMatches(d, len, keyAt)) return;
        UdpSender sender = udpSender;
        InetAddress pilot = sender == null ? null : sender.getIp();
        byte[] answer;
        if (flying && pilot != null) {
            byte[] a = pilot.getAddress();
            answer = new byte[4 + a.length];
            answer[0] = SPECTATE_PILOT_AT;
            answer[1] = (byte) a.length;
            System.arraycopy(a, 0, answer, 2, a.length);
            answer[2 + a.length] = (byte) (sender.getPort() >> 8);
            answer[3 + a.length] = (byte) sender.getPort();
        } else {
            answer = new byte[]{SPECTATE_NO_PILOT};
        }
        sendRaw(answer, new InetSocketAddress(from, fromPort));
    }

    private void sendRaw(byte[] data, InetSocketAddress to) {
        ImpairedSocket s = socket;
        if (s == null || to == null) return;
        try {
            s.send(new DatagramPacket(data, data.length, to));
        } catch (IOException ignored) {
            // as lost
        }
    }

    // ------------------------------------------------------------------ the sticks

    private void handleRcFrame(byte[] d, int len) {
        if (len < 2) return;
        int count = d[1] & 0xff;
        if (count < 4 || count > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT) return;
        int remaining = len - 2;
        long now = System.currentTimeMillis();
        if (remaining == count * 2 + 5) {
            int seq = ((d[len - 5] & 0xff) << 8) | (d[len - 4] & 0xff);
            int sentMs = ((d[len - 3] & 0xff) << 8) | (d[len - 2] & 0xff);
            int maxLagMs = (d[len - 1] & 0xff) * 10;
            rcLagFromPc = true;
            if (!takeSeq16(seq, now)) return;
            if (tooLate(sentMs, maxLagMs, now)) return;
            lastRcSeq = seq;
            rcSeqValid = true;
        } else if (remaining == count * 2 + 2) {
            int seq = ((d[len - 2] & 0xff) << 8) | (d[len - 1] & 0xff);
            rcLagFromPc = false;
            if (!takeSeq16(seq, now)) return;
            lastRcSeq = seq;
            rcSeqValid = true;
        } else if (remaining == count * 2) {
            rcLagFromPc = false;
            lastRcSeq = -1;
        } else {
            return;
        }
        short[] frame = new short[count];
        for (int i = 0, at = 2; i < count; i++, at += 2) frame[i] = (short) (((d[at] & 0xff) << 8) | (d[at + 1] & 0xff));
        if (lastRcTakenMs != 0) maxRcInGapMs = Math.max(maxRcInGapMs, now - lastRcTakenMs);
        synchronized (rcLock) {
            lastRcTakenMs = now;
            if (!fcLost) {
                fc.sticks(frame, count, now);
                lastRcWriteMs = now;
                lastRcSeqReported = lastRcSeq;
            }
        }
    }

    private boolean takeSeq16(int seq, long now) {
        if (rcSeqValid && now - lastRcTakenMs > RC_SEQ16_FORGET_MS) rcSeqValid = false;
        return !rcSeqValid || (short) (seq - lastRcSeq) > 0;
    }

    /** The stick lag (the phone's pc.31 rule): the frame's transit over the best of the last two 10 s buckets. */
    private boolean tooLate(int sentMs, int maxLagMs, long nowMs) {
        int transit = (int) ((nowMs - sentMs) & 0xFFFF);
        if (nowMs - rcLagBucketAt >= 10_000) {
            rcLagMinBefore = rcLagMinNow;
            rcLagMinNow = -1;
            rcLagBucketAt = nowMs;
        }
        if (rcLagMinNow < 0 || (short) (transit - rcLagMinNow) < 0) rcLagMinNow = transit;
        int best = rcLagMinNow;
        if (rcLagMinBefore >= 0 && (short) (rcLagMinBefore - best) < 0) best = rcLagMinBefore;
        int lag = Math.max(0, (short) (transit - best));
        rcLagMs = lag;
        if (lag > rcLagMaxMs) rcLagMaxMs = lag;
        if (maxLagMs > 0 && lag > maxLagMs) {
            rcLagDropped++;
            return true;
        }
        return false;
    }

    private void resetRcLag() {
        rcLagMinNow = -1;
        rcLagMinBefore = -1;
        rcLagBucketAt = System.currentTimeMillis();
        rcLagMs = -1;
        rcLagMaxMs = -1;
        rcLagDropped = 0;
    }

    private void handleIdleSticks(byte[] d, int len) {
        if (len < 2) return;
        int count = d[1] & 0xff;
        if (count < 4 || count > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT || len != 2 + count * 2) return;
        short[] frame = new short[count];
        for (int i = 0, at = 2; i < count; i++, at += 2) frame[i] = (short) (((d[at] & 0xff) << 8) | (d[at + 1] & 0xff));
        if (idleSticks == null) log.accept("idle sticks from the PC: fed to the FC while disarmed without sticks");
        idleSticks = frame;
    }

    /** The idle sticks to the FC (the phone's idleSticksRun): disarmed, no stick frame for 300 ms, its ARM ranges can't arm. */
    private void feedIdleSticks(long now) {
        boolean feed;
        synchronized (rcLock) {
            short[] idle = idleSticks;
            feed = idle != null && !fcLost && mode != DEEP_SLEEP && mode != OVERWATCH && now - lastRcTakenMs >= IDLE_AFTER_MS
                    && !fc.isArmed() && !fc.frameMayArm(idle, idle.length);
            if (feed) {
                fc.sticks(idle, idle.length, now);
                lastRcWriteMs = now;
            }
        }
        if (feed != idleFeeding) log.accept(feed ? "idle sticks to the FC (disarmed, no PC sticks)" : "idle sticks stopped");
        idleFeeding = feed;
    }

    /** The FC is known DISARMED (FcArmedState): no FC data counts as not known. */
    boolean fcDisarmed() {
        return !fcLost && !fc.isArmed();
    }

    // ------------------------------------------------------------------ the session's packets (the buffer thread)

    private void bufferRun(int gen) {
        while (running && gen == threadsGen) {
            try {
                ReceiverBuffer b = receiverBuffer;
                if (b == null) {
                    Thread.sleep(5);
                    continue;
                }
                SavedPacket packet;
                while ((packet = b.getNextPacket()) != null) processData(packet.data);
                b.processTimer(); // its timers count the clock's milliseconds (1.0.54), not calls
                Thread.sleep(2);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.accept("buffer error: " + e);
            }
        }
    }

    private void processData(byte[] data) {
        DataReader buffer = new DataReader(data, true);
        byte packetName = buffer.readByte();
        if (UdpCommon.isPacketNumbered(packetName)) buffer.readShort();
        try {
            switch (packetName) {
                case UdpCommon.Ping -> {
                    boolean toEndPoint = buffer.readBoolean();
                    long time = buffer.readLong();
                    byte target = buffer.getRemaining() > 0 ? buffer.readByte() : -1;
                    udpSender.sendPong(toEndPoint, time, target);
                }
                case UdpCommon.Config -> onConfig(buffer);
                case UdpCommon.StartVideo -> {
                    if (mode != NORMAL) {
                        log.accept("StartVideo in " + modeName(mode) + ": not started");
                        return;
                    }
                    videoWanted = true;
                    if (!video.isOn()) video.start();
                    else video.resendConfig(); // the stream runs: its config again, then a key frame
                    video.requestKeyFrame();
                }
                case UdpCommon.GetVideoConfig -> video.resendConfig();
                case UdpCommon.Disconnect -> {
                    log.accept("the PC disconnected");
                    videoWanted = false;
                    pcAudio.forget();
                    if (!observing) video.stop();
                }
                case UdpCommon.FcInfo -> {
                    if (!fcLost) udpSender.sendPacket(TelemetryRecords.fcInfoPacket());
                }
                case UdpCommon.BoxNames -> {
                    if (!fcLost) sendRecords(out -> TelemetryRecords.boxNames(out, FlightModel.BOX_NAMES));
                }
                case UdpCommon.BoxIds -> {
                    if (!fcLost) sendRecords(out -> TelemetryRecords.boxIds(out, FlightModel.BOX_IDS));
                }
                case UdpCommon.BatteryConfig -> {
                    if (!fcLost) sendRecords(out -> TelemetryRecords.batteryConfig(out, 3.3, 4.3, 3.5));
                }
                case SET_BITRATE -> {
                    if (buffer.getRemaining() > 0) setStep(buffer.readUnsignedByteAsInt());
                }
                case UdpCommon.ChangeBitRate -> setStep(step + (buffer.readBoolean() ? 1 : -1));
                case VIDEO_PROFILE -> {
                    int flags = buffer.getRemaining() > 0 ? buffer.readByte() : 0;
                    boolean twice = (flags & 1) != 0, groups = (flags & 2) != 0;
                    if (twice != keyFramesTwice || groups != groupParity) {
                        log.accept("video profile: key frames " + (twice ? "twice" : "once") + ", parity " + (groups ? "per group" : "off"));
                    }
                    keyFramesTwice = twice;
                    groupParity = groups;
                }
                case KEY_FRAME_REQUEST -> video.requestKeyFrame();
                case PcAudio.PACKET -> {
                    PcAudio.Request r = PcAudio.decode(data, data.length);
                    pcAudio.onRequest(r, System.currentTimeMillis());
                    if (r != null && r.resendConfig) audioConfigResend = true;
                }
                case DETECTION_SET -> {
                    if (buffer.getRemaining() > 0) {
                        int level = buffer.readUnsignedByteAsInt();
                        if (level != detectionLevel) log.accept("motion detection " + (level == 0 ? "off" : "sensitivity " + level) + " (the PC)");
                        detectionLevel = level;
                        detectionAreas(DetectionAreas.read(buffer)); // 1.0.58: the areas after it, as the phone takes them
                        detectionAskedAt = System.currentTimeMillis();
                    }
                }
                case REMOTE_UPDATE -> {
                    if (updateRefusal == null) log.accept("remote update asked: virtual drones take none");
                    updateRefusal = "a virtual drone takes no update";
                    updateRefusedAt = System.currentTimeMillis();
                }
                case POWER_MODE -> {
                    if (buffer.getRemaining() < 1) return;
                    int m = buffer.readUnsignedByteAsInt();
                    int interval = buffer.getRemaining() > 0 ? buffer.readUnsignedByteAsInt() : 0;
                    if (m == OVERWATCH) {
                        if (buffer.getRemaining() < 6) return;
                        int sensitivity = buffer.readUnsignedByteAsInt();
                        int flags = buffer.readUnsignedByteAsInt();
                        int token = buffer.readInt();
                        detectionAreas(DetectionAreas.read(buffer));
                        overwatch.configure(sensitivity, flags, token);
                    }
                    requestMode(m, interval, "the PC");
                }
                default -> {
                    // OsdConfig, recording on the phone, camera change: nothing a virtual phone has
                }
            }
        } catch (IOException | RuntimeException e) {
            log.accept("packet " + packetName + " error: " + e);
        }
    }

    private volatile boolean audioConfigResend;

    /** The PC's Config (upstream Config.processReceivedConfig, the parts that matter here). */
    private void onConfig(DataReader b) throws IOException {
        int version = b.readShort();
        if (version != SettingsCommon.versionCompatibleCode) {
            udpSender.sendPacket(new UdpPacketData(UdpCommon.VersionMismatch).getData());
            return;
        }
        int w = 0, h = 0, fps = 30;
        for (int i = 0; i < SettingsCommon.maxCamerasCount; i++) {
            if (i > 0) b.readBoolean();
            b.readUTF();
            int cw = b.readShort(), ch = b.readShort();
            b.readShort(); // fps min
            int fpsMax = b.readShort();
            b.readBoolean();
            b.readByte();
            b.readBoolean();
            if (i == 0) {
                w = cw;
                h = ch;
                fps = fpsMax;
            }
        }
        int limit = b.readUnsignedByteAsInt();
        udpSender.sendPacket(new UdpPacketData(UdpCommon.ConfigReceived).getData());
        w = Math.max(160, Math.min(1920, w)) / 2 * 2;
        h = Math.max(90, Math.min(1080, h)) / 2 * 2;
        fps = Math.max(5, Math.min(60, fps));
        if (w != cameraWidth || h != cameraHeight || fps != cameraFps || limit != bitrateLimitMbps) {
            log.accept("config: " + w + "x" + h + " at " + fps + " fps, up to " + limit + " Mbit/s");
        }
        cameraWidth = w;
        cameraHeight = h;
        cameraFps = fps;
        bitrateLimitMbps = limit;
        if (limit > 0 && STEPS[step] > limit * 1_000_000) setStep(step);
    }

    private void setStep(int s) {
        int max = STEPS.length - 1;
        if (bitrateLimitMbps > 0) while (max > 0 && STEPS[max] > bitrateLimitMbps * 1_000_000) max--;
        step = Math.max(0, Math.min(max, s));
    }

    private interface RecordWriter {
        void write(DataOutputStream out) throws IOException;
    }

    /** Records in a TelemetryData packet of their own, to the pilot. */
    private void sendRecords(RecordWriter w) throws IOException {
        UdpPacketData p = new UdpPacketData(UdpCommon.TelemetryData);
        w.write(p.daos);
        UdpSender sender = udpSender;
        if (sender != null) sender.sendPacket(p.getData());
    }

    public boolean isConnected() {
        ReceiverBuffer b = receiverBuffer;
        return b != null && b.isConnected();
    }

    // ------------------------------------------------------------------ names

    private void answerName(byte[] d, int len, InetAddress from, int fromPort) {
        String k = de.droiddrone.pc.Names.keyOf(d, len);
        if (!key.equals(k)) return;
        if (d[0] == NAME_ASSIGN) {
            String assigned = de.droiddrone.pc.Names.parseAssign(d, len, key);
            if (assigned != null && !assigned.equals(name)) {
                log.accept("named " + assigned + " by the PC at " + from.getHostAddress());
                setName(assigned);
            }
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(NAME_REPLY);
            out.writeUTF(key);
            out.writeByte(0);
            out.writeUTF(name);
            out.writeUTF(DEVICE);
            out.writeUTF(VERSION);
            out.writeByte(1); // where the drone is (pc.32)
            long now = System.currentTimeMillis();
            boolean fromFc = !fcLost && fc.gpsFix(now);
            out.writeByte(fromFc ? 1 : 2);
            out.writeInt((int) Math.round(fc.latDeg() * 1e7));
            out.writeInt((int) Math.round(fc.lonDeg() * 1e7));
            out.writeShort(0);
            out.writeByte(fcLost ? 2 : fc.isArmed() ? 1 : 0);
            boolean flown = isConnected();
            out.writeByte(flown ? 1 : 0);
            InetAddress pilot = flown ? udpSender.getIp() : null;
            byte[] a = pilot == null ? new byte[0] : pilot.getAddress();
            out.writeByte(a.length);
            out.write(a);
            synchronized (powerLock) {
                out.writeByte(mode); // pc.39: its power mode, as the phone says it
            }
            fleetStatus().writeTo(out); // pc.40: how it is doing, for the fleet cards
            sendRaw(bytes.toByteArray(), new InetSocketAddress(from, fromPort));
        } catch (IOException ignored) {
        }
    }

    public void setName(String n) {
        name = n == null ? "" : n;
        scene.setName(name.isEmpty() ? "DRONE " + (slot + 1) : name);
    }

    // ------------------------------------------------------------------ observation (the phone's onObserve / observeTick)

    private void onObserve(byte[] d, int len, InetAddress from, int fromPort) {
        String k = Observe.keyOf(d, len);
        if (k == null || !k.equals(key)) return;
        InetSocketAddress who = new InetSocketAddress(from, fromPort);
        String id = who.toString();
        if (d[0] == Observe.STOP) {
            boolean was;
            synchronized (observers) {
                was = observers.remove(id);
                observerAddresses.remove(id);
                refreshObserverTargets();
            }
            if (was) log.accept("observer " + from.getHostAddress() + " left");
            answerObserver(who, Observe.STOPPED);
            return;
        }
        Observe.Request r = Observe.decodeRequest(d, len);
        if (r == null) return;
        long now = System.currentTimeMillis();
        int state;
        boolean pilotNear = isConnected() || now - lastPilotPacketMs < Takeover.PILOT_ALIVE_MS && !from.equals(lastPcIp);
        if (pilotNear) state = Observe.REFUSED_PILOT;
        else if (!fcDisarmed()) state = Observe.REFUSED_ARMED;
        else if (mode != NORMAL || overwatch.isWatching()) state = Observe.REFUSED_POWER;
        else {
            boolean added;
            synchronized (observers) {
                added = !observerAddresses.containsKey(id);
                state = observers.onRequest(id, r, now);
                if (state == Observe.STREAMING) {
                    observerAddresses.put(id, who);
                    refreshObserverTargets();
                } else {
                    added = false;
                }
            }
            if (added) log.accept("observer " + from.getHostAddress() + " watches (" + r.width + "x" + r.height + ")");
        }
        answerObserver(who, state);
        if (state != Observe.STREAMING) return;
        if (r.videoConfig()) video.resendConfig();
        if (r.audioConfig()) audioConfigResend = true;
        if (r.videoConfig() && now - observeKeyFrameAt >= 1000) {
            observeKeyFrameAt = now;
            video.requestKeyFrame();
        }
    }

    private void answerObserver(InetSocketAddress who, int state) {
        int count;
        synchronized (observers) {
            count = observers.size();
        }
        int flags = state == Observe.STREAMING && isSoundHeard() ? Observe.STATE_SOUND_HEARD : 0; // pc.45
        sendRaw(Observe.encodeState(state, cameraWidthNow(), cameraHeightNow(), cameraFpsNow(), count, flags), who);
    }

    private void refreshObserverTargets() {
        observerTargets = observerAddresses.values().toArray(new InetSocketAddress[0]);
    }

    /** Every 0.1 s: observers expired or sent away; the picture they want set; the stream started or stopped. */
    private void observeTick(long now) {
        InetSocketAddress[] ended = null;
        int endState = 0;
        Observe.Request want;
        synchronized (observers) {
            for (String gone : observers.expire(now)) {
                observerAddresses.remove(gone);
                log.accept("observer " + gone + " went silent");
            }
            if (!observers.isEmpty()) {
                boolean pilot = isConnected();
                boolean armed = !fcDisarmed();
                boolean power = mode != NORMAL || overwatch.isWatching();
                if (pilot || armed || power) {
                    endState = pilot ? Observe.ENDED_PILOT : armed ? Observe.REFUSED_ARMED : Observe.REFUSED_POWER;
                    ended = observerAddresses.values().toArray(new InetSocketAddress[0]);
                    observers.clear();
                    observerAddresses.clear();
                }
            }
            refreshObserverTargets();
            want = observers.wanted();
        }
        if (ended != null) {
            log.accept("observation ended: " + Observe.stateName(endState));
            for (InetSocketAddress who : ended) answerObserver(who, endState);
        }
        observeWant = want;
        if (want == null) {
            observeHeard.reset();
            if (observing) {
                observing = false;
                observeAudio = false;
                if (!isConnected() || !videoWanted) video.stop();
                log.accept("observation: nobody watches now");
            }
            return;
        }
        if (!observing) {
            observing = true;
            keyFramesTwice = false;
            groupParity = true;
            log.accept("observation: streaming to observers");
        }
        observeAudio = want.audio();
        if (!video.isOn()) video.start();
        if (observeHeard.due(isSoundHeard(), now)) { // pc.45: the muted tiles' speaking highlight
            for (InetSocketAddress who : observerTargets) answerObserver(who, Observe.STREAMING);
        }
    }

    private void sendToObservers(byte[] data) {
        ImpairedSocket s = socket;
        if (s == null) return;
        for (InetSocketAddress to : observerTargets) {
            try {
                s.send(new DatagramPacket(data, data.length, to));
            } catch (IOException ignored) {
            }
        }
    }

    /** The stream's packets: to the pilot's session, or, observed, a copy to each observer. */
    private void streamOut(byte[] data) {
        if (observing && !isConnected()) {
            sendToObservers(data);
            return;
        }
        UdpSender sender = udpSender;
        if (sender != null) sender.sendPacket(data);
    }

    private int cameraWidthNow() {
        Observe.Request w = observeWant;
        return observing && !isConnected() && w != null ? w.width : cameraWidth;
    }

    private int cameraHeightNow() {
        Observe.Request w = observeWant;
        return observing && !isConnected() && w != null ? w.height : cameraHeight;
    }

    private int cameraFpsNow() {
        Observe.Request w = observeWant;
        return observing && !isConnected() && w != null ? Math.max(1, w.fps) : cameraFps;
    }

    private int bitRateNow() {
        Observe.Request w = observeWant;
        if (observing && !isConnected() && w != null) return STEPS[Observe.stepFor(w.maxKbps)];
        return STEPS[step];
    }

    // ------------------------------------------------------------------ the video

    /** The picture: the recording when one plays, else the scene from the drone's state. */
    private void renderPicture(BufferedImage img, long nowMs) {
        Recording r = recording;
        if (r != null && r.picture(img, nowMs)) {
            scene.paintTargets(img, viewAt(nowMs), nowMs); // a walker, vehicle or light crosses an old flight's picture too
            scene.burnIn(img, "REPLAY"); // an old flight's picture says VIRTUAL too
            return;
        }
        scene.render(img, viewAt(nowMs), nowMs, overlayText(nowMs));
    }

    Scene.View view() {
        return new Scene.View(fc.northM(), fc.eastM(), fc.altitudeM(), fc.rollDeg(), fc.pitchDeg(), fc.headingDeg());
    }

    /** The drone's pose at the last two ticks, with their times (the tick thread writes, the picture reads). */
    private record Pose(long atMs, Scene.View v) {}

    private volatile Pose posePrev, poseLast;

    /** After each step of the flight model: its pose now, for the picture's {@link #viewAt}. Tick thread. */
    private void notePose(long nowMs) {
        posePrev = poseLast;
        poseLast = new Pose(nowMs, view());
    }

    /**
     * The pose at this moment, carried on from the last tick at the rate of the last two (1.0.52, the pilot: "the BTR
     * is very jittery"): the model steps every 20-30 ms, the picture comes every 33 ms, so read straight from the model
     * some pictures moved two steps and some none - the whole picture juddered. At most one tick (40 ms) ahead.
     */
    Scene.View viewAt(long nowMs) {
        Pose a = posePrev, b = poseLast;
        if (b == null) return view();
        if (a == null) return b.v();
        return carryOn(a.atMs(), a.v(), b.atMs(), b.v(), nowMs);
    }

    /** The pose q (at qMs) carried on to nowMs at the rate from p (at pMs) to q: at most 40 ms ahead, one tick back. */
    static Scene.View carryOn(long pMs, Scene.View p, long qMs, Scene.View q, long nowMs) {
        if (qMs <= pMs) return q;
        double span = (qMs - pMs) / 1000.0;
        double dt = Math.max(-span, Math.min(0.04, (nowMs - qMs) / 1000.0));
        double k = dt / span;
        return new Scene.View(q.north() + (q.north() - p.north()) * k, q.east() + (q.east() - p.east()) * k,
                q.alt() + (q.alt() - p.alt()) * k, q.rollDeg() + angleStep(p.rollDeg(), q.rollDeg()) * k,
                q.pitchDeg() + angleStep(p.pitchDeg(), q.pitchDeg()) * k, q.headingDeg() + angleStep(p.headingDeg(), q.headingDeg()) * k);
    }

    /** From a to b the short way round, degrees. */
    private static double angleStep(double a, double b) {
        double d = (b - a) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private String overlayText(long nowMs) {
        if (fc.isArmed()) return String.format(java.util.Locale.ROOT, "ARMED  %.0f M  %.0f KM/H", fc.altitudeM(), fc.groundSpeedMs() * 3.6);
        FlightModel.Failsafe f = fc.failsafe();
        return f == FlightModel.Failsafe.NONE ? "DISARMED" : "FAILSAFE";
    }

    // ------------------------------------------------------------------ the sound

    /** The phone's audio gate: the PC asks (or an observer does), the FC is DISARMED, normal power, a session or observers. */
    private boolean audioAllowed(long now) {
        boolean observed = observing && observeAudio && !isConnected();
        boolean wanted = pcAudio.wanted(now) || observed;
        boolean disarmed = wanted && fcDisarmed();
        boolean normal = mode == NORMAL;
        boolean allow = isConnected() ? pcAudio.allowed(now, disarmed, normal) : observed && disarmed && normal;
        audioHeld = wanted && !allow;
        return allow;
    }

    /** pc.45: observed, no observer wants the sound: listened to for their "sound heard", as the phone app does. */
    private boolean listenWanted() {
        return observing && !observeAudio && !isConnected() && fcDisarmed() && mode == NORMAL;
    }

    /** pc.45: the listening hears something now. */
    boolean isSoundHeard() {
        return audioListening && soundHeard;
    }

    private void audioTick(long now) {
        boolean allow = audioAllowed(now);
        boolean listen = !allow && listenWanted();
        if (allow && (audioThread == null || audioListening)) {
            if (audioThread != null) stopAudio();
            int gen = ++audioGen;
            int kbps = isConnected() ? pcAudio.bitRate() / 1000 : PcAudio.DEFAULT_KBPS;
            startAudioThread(() -> audioRun(gen, kbps, false));
            log.accept("live audio to the PC: on");
        } else if (listen && audioThread == null) {
            int gen = ++audioGen;
            audioListening = true;
            startAudioThread(() -> audioRun(gen, 0, true));
            log.accept("listening only (observers muted)");
        } else if (!allow && audioThread != null && !(listen && audioListening)) {
            boolean wasListening = audioListening;
            stopAudio();
            if (!wasListening) log.accept("live audio to the PC: off" + (audioHeld && !fcDisarmed() ? " (FC not known disarmed)" : ""));
        }
    }

    private void startAudioThread(Runnable r) {
        Thread t = new Thread(r, "fleetAudio" + slot);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        audioThread = t;
        t.start();
    }

    void stopAudio() {
        audioGen++;
        audioThread = null;
        audioOn = false;
        audioListening = false;
        soundHeard = false;
    }

    /**
     * The sound, made and sent; or (pc.45, listen) made and only heard - a VoiceActivity on it, nothing encoded or sent
     * (a recording's sound is AAC already: not heard then).
     */
    private void audioRun(int gen, int kbps, boolean listen) {
        Recording rec = recording;
        boolean fromRecording = rec != null && rec.hasAudio();
        BtrScene scenario = fromRecording ? null : target;
        BtrSound vehicle = targetSound;
        // stereo for the tour's BTR, and (1.0.52) for every generated picture: a BTR sent by the panel is heard from
        // where it drives, whenever it comes
        int channels = fromRecording ? 1 : 2;
        float[] left = new float[AacEncoder.FRAME], right = new float[AacEncoder.FRAME], heard = new float[AacEncoder.FRAME];
        VoiceActivity voice = listen ? new VoiceActivity() : null;
        try (AacEncoder enc = fromRecording || listen ? null : new AacEncoder(Math.max(16, Math.min(64, kbps)) * 1000 * channels, channels)) {
            byte[] asc = listen ? null : fromRecording ? rec.audioConfig() : enc.config();
            if (!listen) {
                sendAudioConfig(asc, channels);
                audioOn = true;
            }
            long frameNs = 1_000_000_000L * AacEncoder.FRAME / AacEncoder.SAMPLE_RATE;
            double frameMs = frameNs / 1e6;
            long next = System.nanoTime();
            while (gen == audioGen && running) {
                if (!listen && audioConfigResend) {
                    audioConfigResend = false;
                    sendAudioConfig(asc, channels);
                }
                List<byte[]> frames = List.of();
                if (fromRecording) {
                    frames = rec.audioFrames(System.currentTimeMillis());
                } else if (channels == 2) {
                    // the drone's own sound in the middle, each vehicle from where it is
                    float[] own = sound.next(fc.isArmed() ? fc.throttleOut() : -1, lastBeep);
                    System.arraycopy(own, 0, left, 0, own.length);
                    System.arraycopy(own, 0, right, 0, own.length);
                    long now = System.currentTimeMillis();
                    Scene.View view = view();
                    if (scenario != null && vehicle != null) {
                        double[] rel = scenario.relative(view, now);
                        vehicle.add(left, right, rel[0], rel[1], rel[2], scenario.speedAt(now));
                    }
                    for (Scene.Target t : scene.targets(now)) {
                        if (t.btr == null) continue;
                        double[] rel = t.btr.relative(view, now);
                        t.sound.add(left, right, rel[0], rel[1], rel[2], t.btr.speedAt(now));
                    }
                    if (scene.world() instanceof Scene.Sounding world) world.addSound(left, right, view, now); // the tour's scenes
                    if (listen) {
                        for (int i = 0; i < heard.length; i++) heard[i] = (left[i] + right[i]) / 2;
                    } else {
                        frames = enc.encode(left, right);
                    }
                } else if (listen) {
                    System.arraycopy(sound.next(fc.isArmed() ? fc.throttleOut() : -1, lastBeep), 0, heard, 0, heard.length);
                } else {
                    frames = enc.encode(sound.next(fc.isArmed() ? fc.throttleOut() : -1, lastBeep));
                }
                if (listen) { // heard, never sent
                    boolean on = !fromRecording && voice.frame(VoiceActivity.levelDb(heard, 0, heard.length), frameMs, System.currentTimeMillis());
                    if (gen == audioGen) soundHeard = on;
                    frames = List.of();
                }
                for (byte[] f : frames) {
                    byte[] p = new byte[f.length + 1];
                    p[0] = UdpCommon.AudioFrame;
                    System.arraycopy(f, 0, p, 1, f.length);
                    streamOut(p);
                }
                next += frameNs;
                long wait = next - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                else if (wait < -500_000_000L) next = System.nanoTime();
            }
        } catch (IOException | RuntimeException e) {
            log.accept("sound stopped: " + e.getMessage());
        } finally {
            audioOn = false;
        }
    }

    private void sendAudioConfig(byte[] asc, int channels) throws IOException {
        UdpPacketData p = new UdpPacketData(UdpCommon.AudioInitialFrame);
        p.daos.writeInt(AacEncoder.SAMPLE_RATE);
        p.daos.writeByte(channels);
        p.daos.writeByte(2); // Android's ENCODING_PCM_16BIT, as the phone says
        p.daos.write(asc);
        streamOut(p.getData());
    }

    // ------------------------------------------------------------------ power modes (PowerMode)

    /** A mode asked for (the PC's POWER_MODE, the panel's buttons). Refused while the FC isn't known disarmed. */
    public void requestMode(int wanted, int intervalMin, String from) {
        synchronized (powerLock) {
            if (intervalMin > 0) checkIntervalMin = Math.min(255, intervalMin);
            if (wanted < NORMAL || wanted > OVERWATCH) return;
            if (wanted != NORMAL && !fcDisarmed()) {
                if (mode == NORMAL) {
                    log.accept(modeName(wanted) + " refused (" + from + "): FC not known disarmed");
                    burst();
                    return;
                }
                wanted = NORMAL;
                from = "FC armed";
            }
            if (wanted == mode && wanted != NORMAL) return;
            switch (wanted) {
                case NORMAL -> wake(from);
                case STANDBY -> {
                    if (mode != NORMAL) wake(from);
                    mode = STANDBY;
                    video.stop();
                    log.accept("standby (" + from + ")");
                    burst();
                }
                default -> { // deep sleep or overwatch: a 1 s countdown, then asleep
                    if (mode != NORMAL) wake(from);
                    mode = wanted;
                    sleepAt = System.currentTimeMillis() + SLEEP_COUNTDOWN_MS;
                    log.accept(modeName(wanted) + " in 1 s (" + from + "), " + (always() ? "always listening for the PC" : "checks every " + checkIntervalMin + " min"));
                    burst();
                }
            }
        }
    }

    /** Back to normal from anything: the socket open again if it was closed. Under powerLock. */
    private void wake(String from) {
        boolean wasDown = asleep || checking;
        boolean wasOverwatch = mode == OVERWATCH;
        boolean changed = mode != NORMAL || wasDown;
        mode = NORMAL;
        sleepAt = 0;
        asleep = false;
        checking = false;
        if (wasOverwatch) overwatch.end("woken: " + from);
        if (wasDown && socket == null) {
            try {
                openSocket(port);
            } catch (IOException e) {
                log.accept("could not open the socket again: " + e.getMessage());
            }
        }
        if (changed) log.accept((wasDown ? "awake" : "normal mode") + " (" + from + ")");
        burst();
    }

    private void burst() {
        burstLeft = 3;
        nextBurstAt = 0;
    }

    /** The PC answered the sleep hello (pc.22): sleep on (the window closes now) or wake. */
    private void sleepAnswer(boolean wakeUp) {
        synchronized (powerLock) {
            if (!checking) return;
            if (wakeUp) wake("the PC called");
            else if (!always()) { // always listening (pc.39): the window stays open whatever the PC says
                log.accept("sleep check: the PC says sleep on");
                closeWindow(System.currentTimeMillis());
            }
        }
    }

    /** Placed by the tour: its first check-in comes as soon as it is asleep, then every interval. Under powerLock. */
    private boolean checkInAtOnce;
    static final long TOUR_FIRST_CHECK_MS = 1000;

    /** The panel's CHECK NOW: the next check window opens now (the phone's alarm, early). */
    public void checkNow() {
        synchronized (powerLock) {
            if (asleep) nextCheckAt = System.currentTimeMillis();
        }
    }

    private void powerTick(long now) {
        synchronized (powerLock) {
            if (sleepAt != 0 && now >= sleepAt) {
                sleepAt = 0;
                if (!fcDisarmed()) {
                    log.accept("deep sleep refused at countdown end: FC not known disarmed");
                    wake("FC armed");
                    return;
                }
                asleep = true;
                video.stop();
                stopAudio();
                InetSocketAddress pc = lastPcIp == null ? null : new InetSocketAddress(lastPcIp, lastPcPort);
                closeSocket(); // the link's socket, the FC's port, the GPS: let go
                // always listening (pc.39): no alarm - the window opens shortly (the PC's link quiet by then) and stays
                // the tour's drones (never linked, nothing to let go quiet) check in within a second: listed at once
                nextCheckAt = now + (checkInAtOnce ? TOUR_FIRST_CHECK_MS : always() ? SleepCheck.ALWAYS_OPEN_AFTER_MS : checkIntervalMin * 60_000L);
                checkInAtOnce = false;
                if (mode == OVERWATCH) overwatch.begin(pc);
                log.accept(modeName(mode) + (always() ? ": always listening for the PC in " + SleepCheck.ALWAYS_OPEN_AFTER_MS / 1000 + " s"
                        : ": next check in " + checkIntervalMin + " min"));
            }
            if (asleep && now >= nextCheckAt) {
                asleep = false;
                checking = true;
                windowOpenedAt = now;
                nextHelloAt = now + HELLO_FIRST_MS;
                try {
                    openSocket(port);
                } catch (IOException e) {
                    log.accept("sleep check: no socket: " + e.getMessage());
                }
                log.accept(always() ? "always listening for the PC" : "sleep check: listening for the PC for " + CHECK_WINDOW_MS / 1000 + " s");
            }
            if (checking) {
                if (isConnected()) {
                    wake("the PC called");
                } else if (!always() && now - windowOpenedAt >= CHECK_WINDOW_MS) {
                    closeWindow(now);
                } else if (now >= nextHelloAt) {
                    // always listening: as a window's first seconds, then once a minute
                    nextHelloAt = now + (always() && now - windowOpenedAt > SleepCheck.ALWAYS_HELLO_BURST_MS ? SleepCheck.ALWAYS_HELLO_MS : HELLO_GAP_MS);
                    sendSleepHello();
                }
            }
        }
    }

    /** Under powerLock. */
    private void closeWindow(long now) {
        checking = false;
        asleep = true;
        closeSocket();
        nextCheckAt = now + checkIntervalMin * 60_000L;
        log.accept("sleep check over: next check in " + checkIntervalMin + " min");
    }

    private void sendSleepHello() {
        InetAddress ip = lastPcIp;
        if (ip == null) return;
        int pct = phoneBatteryPercent();
        int mv = 3700 + pct * 5;
        byte[] head = {SLEEP_HELLO, (byte) pct, 0, (byte) Math.min(255, checkIntervalMin), (byte) (mv >> 8), (byte) mv,
                (byte) overwatch.helloFlags(), 0};
        // pc.40: the status block, then who and where it is (as the phone's Udp.sleepWhere)
        de.droiddrone.common.FleetProtocol.Where where = new de.droiddrone.common.FleetProtocol.Where(name, 1, fc.latDeg(), fc.lonDeg(), 0);
        sendRaw(de.droiddrone.common.FleetProtocol.hello(head, fleetStatus(), where, key), new InetSocketAddress(ip, lastPcPort));
    }

    // ------------------------------------------------------------------ fleet management (pc.40, FleetProtocol)

    /** The last FLEET_MODE taken (its token; 0 none). */
    private volatile int fleetToken;

    /** As the phone's Udp.onFleetMode: refused (not taken) in a session or unless the FC is known disarmed. */
    private void onFleetMode(byte[] d, int len, InetAddress from) {
        de.droiddrone.common.FleetProtocol.Request r = de.droiddrone.common.FleetProtocol.parseRequest(d, len, key);
        if (r == null || r.token == fleetToken) return;
        if (isConnected()) {
            log.accept("fleet: " + modeName(r.mode) + " from " + from.getHostAddress() + " refused: a pilot is connected");
            return;
        }
        if (!fcDisarmed()) {
            log.accept("fleet: " + modeName(r.mode) + " from " + from.getHostAddress() + " refused: FC not known disarmed");
            return;
        }
        fleetToken = r.token;
        lastPcIp = from;
        lastPcPort = r.pcPort;
        if (r.mode == OVERWATCH) overwatch.configure(r.sensitivity, r.flags, r.owToken);
        requestMode(r.mode, r.intervalMin, "PC fleet");
    }

    /** How this virtual phone is doing: its battery, its signal bars, the FC's GPS, its temperature. */
    de.droiddrone.common.FleetProtocol.Status fleetStatus() {
        long now = System.currentTimeMillis();
        boolean gps = !fcLost && !asleep && !checking;
        int m, interval;
        synchronized (powerLock) {
            m = mode;
            interval = checkIntervalMin;
        }
        return new de.droiddrone.common.FleetProtocol.Status(phoneBatteryPercent(), false, signalBars,
                gps ? (fc.gpsFix(now) ? 1 : 0) : de.droiddrone.common.FleetProtocol.UNKNOWN,
                gps ? fc.satellites(now) : de.droiddrone.common.FleetProtocol.UNKNOWN, phoneTempDeci, m, interval, fleetToken);
    }

    static String modeName(int m) {
        return switch (m) {
            case NORMAL -> "NORMAL";
            case STANDBY -> "STANDBY";
            case DEEP_SLEEP -> "DEEP SLEEP";
            case OVERWATCH -> "OVERWATCH";
            default -> "MODE " + m;
        };
    }

    // ------------------------------------------------------------------ the tick: the FC, the telemetry, the rest

    private void tickRun() {
        long last = System.currentTimeMillis();
        long nextTelemetry = 0, nextStats = 0, nextObserve = 0, nextAudioGate = 0, nextObserverFcInfo = 0;
        int statsSent = 0, telemetryTick = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                double dt = (now - last) / 1000.0;
                last = now;
                Recording rec = recording;
                if (rec != null && rec.track(now, fc)) {
                    // a recorded flight's track plays: it sets the state
                } else {
                    feedIdleSticks(now);
                    fc.step(now, dt);
                }
                notePose(now);
                BtrScene t = target;
                // in the air, the BTRs it sees try to get away (1.0.52, the pilot)
                boolean airborne = !broken && fc.isArmed() && fc.altitudeM() > ESCAPE_ALT_M;
                if (t != null) t.threat(fc.northM(), fc.eastM(), airborne, now);
                scene.threat(fc.northM(), fc.eastM(), airborne, now);
                if (t != null && !broken && fc.isArmed() && t.hits(fc.northM(), fc.eastM(), fc.altitudeM(), now)) crashIntoTarget(now, "the BTR");
                else if (!broken && fc.isArmed()) { // the panel's BTR and soldiers (1.0.52): flown into, the drone breaks and they go
                    Scene.Target hit = scene.hit(fc.northM(), fc.eastM(), fc.altitudeM(), now);
                    if (hit != null) crashIntoTarget(now, hit.kind == Scene.Kind.VEHICLE ? "the BTR" : "a soldier");
                }
                boolean nowConnected = isConnected();
                if (nowConnected && !wasConnected && events != null) events.connected(this);
                wasConnected = nowConnected;
                noteBeeping(now);
                powerTick(now);
                overwatch.tick(now);
                if (now >= nextObserve) {
                    nextObserve = now + 100;
                    observeTick(now);
                    motionTick(now);
                }
                if (now >= nextAudioGate) {
                    nextAudioGate = now + 50;
                    audioTick(now);
                }
                boolean connected = isConnected() && !asleep && !checking;
                long telemetryGap = mode == STANDBY ? 1000 : 30;
                // observed with no pilot: the FC's records to the observers too, and once a second what FC it is
                // (the PC reads the records only knowing it), as phone app pc.43 does (1.0.55)
                boolean observed = observing && !isConnected() && mode == NORMAL;
                if (observed && !fcLost && now >= nextObserverFcInfo) {
                    nextObserverFcInfo = now + 1000;
                    sendToObservers(TelemetryRecords.fcInfoPacket());
                }
                if (now >= nextTelemetry && (connected || observed)) {
                    nextTelemetry = now + telemetryGap;
                    sendTelemetry(now, telemetryTick++, mode == STANDBY);
                }
                if (now >= nextStats && connected) {
                    nextStats = now + 100;
                    sendStats(now, statsSent++);
                }
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            } catch (IOException | RuntimeException e) {
                log.accept("tick error: " + e);
            }
        }
    }

    private void noteBeeping(long now) {
        String b = mode == DEEP_SLEEP || mode == OVERWATCH || fcLost ? null : fc.beeping(now);
        if (b != null && !b.equals(lastBeep)) log.accept("beeping (" + b + ")");
        else if (b == null && lastBeep != null) log.accept("beeping stopped");
        lastBeep = b;
    }

    /** The flight controller's records, as the phone forwards them: every tick a few, by their rates. */
    private void sendTelemetry(long now, int tick, boolean slow) throws IOException {
        if (fcLost) return;
        UdpPacketData p = new UdpPacketData(UdpCommon.TelemetryData);
        DataOutputStream out = p.daos;
        boolean every = slow; // standby: all of them, once a second
        TelemetryRecords.attitude(out, fc.rollDeg(), fc.pitchDeg(), fc.headingDeg());
        if (every || tick % 3 == 0) {
            TelemetryRecords.status(out, TelemetryRecords.SENSOR_ACC | TelemetryRecords.SENSOR_GYRO | TelemetryRecords.SENSOR_GPS,
                    fc.modeFlags(now), fc.blockers(now), 12, 38);
            TelemetryRecords.altitude(out, fc.altitudeM(), fc.varioMs());
        }
        if (every || tick % 7 == 1) {
            TelemetryRecords.analog(out, fc.mahDrawn(), link.rssi(now), fc.amps(), fc.volts());
            TelemetryRecords.rawGps(out, fc.gpsFix(now) ? 2 : 0, fc.satellites(now), fc.latDeg(), fc.lonDeg(), (int) Math.round(fc.altitudeM()),
                    fc.groundSpeedMs(), fc.courseDeg(), fc.gpsFix(now) ? 0.9 : 9.9, (int) fc.travelledM());
        }
        if (every || tick % 17 == 2) {
            TelemetryRecords.batteryState(out, fc.cells(), fc.capacityMah(), fc.mahDrawn(), fc.amps(), fc.volts());
            double dist = fc.homeDistanceM();
            if (dist >= 0) TelemetryRecords.compGps(out, (int) Math.round(dist), (int) Math.round(fc.homeDirectionDeg()));
        }
        if (every || tick % 33 == 5) {
            TelemetryRecords.timers(out, fc.onSeconds(now), fc.armedSeconds(now), fc.armedSeconds(now));
            TelemetryRecords.phoneBattery(out, phoneBatteryPercent(), false);
            TelemetryRecords.cameraFps(out, !video.isOn() ? 0 : cameraFpsNow());
            TelemetryRecords.videoBitRate(out, video.sentBitRate() / 1_000_000f);
            TelemetryRecords.network(out, 4, link.signalPercent(now));
        }
        if (every || tick % 167 == 11) TelemetryRecords.batteryConfig(out, 3.3, 4.3, 3.5);
        streamOut(p.getData()); // the pilot, or the observers while only observed
    }

    /** The phone's own stats packet, ten times a second (overlay Udp.telemetrySenderRun). */
    private void sendStats(long now, int n) throws IOException {
        UdpPacketData p = new UdpPacketData(UdpCommon.TelemetryData);
        DataOutputStream out = p.daos;
        long gap = maxRcInGapMs;
        maxRcInGapMs = 0;
        TelemetryRecords.linkStats(out, (int) gap, (int) gap, 1);
        if (n % 10 == 0) TelemetryRecords.version(out, VERSION);
        if (n % 5 == 1) TelemetryRecords.encoder(out, bitRateNow());
        if (n % 10 == 5) {
            TelemetryRecords.phonePosition(out, fc.latDeg(), fc.lonDeg(), (int) Math.round(fc.altitudeM()), 5, fc.groundSpeedMs() * 3.6,
                    (int) Math.round(fc.courseDeg()), 0);
        }
        int flags = (fcLost ? 0 : TelemetryRecords.STATE_RX_MAP_KNOWN) | (video.isOn() ? TelemetryRecords.STATE_CAMERA_OPEN : 0)
                | (audioOn ? TelemetryRecords.STATE_AUDIO_ON : 0) | (audioHeld ? TelemetryRecords.STATE_AUDIO_HELD : 0);
        long writeAge = lastRcWriteMs == 0 ? -1 : now - lastRcWriteMs;
        TelemetryRecords.phoneState(out, flags, lastRcSeqReported, writeAge, lastRcWriteMs == 0 ? -1 : 1);
        boolean burstNow;
        int sleepIn;
        synchronized (powerLock) {
            burstNow = burstLeft > 0 && now >= nextBurstAt;
            if (burstNow) {
                burstLeft--;
                nextBurstAt = now + 300;
            }
            sleepIn = sleepAt == 0 ? 0 : Math.max(1, burstLeft + 1);
        }
        boolean last = false;
        if (n % 10 == 7 || burstNow) {
            TelemetryRecords.phonePower(out, mode, checkIntervalMin, sleepIn, overwatch.caps());
            last = true;
        }
        if (!last && n % 10 == 3 && rcLagFromPc) {
            int max = rcLagMaxMs;
            rcLagMaxMs = -1;
            TelemetryRecords.rcLag(out, rcLagMs, max, rcLagDropped);
            last = true;
        }
        if (!last && n % 5 == 4 && updateRefusal != null && now - updateRefusedAt < 30_000) {
            out.writeShort(PHONE_UPDATE);
            out.writeByte(4); // failed
            out.writeByte(0);
            out.writeUTF(updateRefusal);
        }
        UdpSender sender = udpSender;
        if (sender != null) sender.sendPacket(p.getData());
    }

    int phoneBatteryPercent() {
        long minutes = (System.currentTimeMillis() - startedAt) / 60_000;
        return (int) Math.max(5, phoneStartPercent - minutes / 6);
    }

    /** The phone's battery at the start, its temperature (tenths of a degree) and its signal bars (the tour's drones set them). */
    private volatile int phoneStartPercent = 92, phoneTempDeci = 310, signalBars = 3;

    /**
     * As if out in the field for hours (the tour's drones): the drone's pack at this rest voltage per cell after its
     * flight, the phone at this battery, temperature and signal; only the breeze and the motors in its microphone.
     */
    void setOutside(double restCellVolts, int phonePercent, double phoneTempC, int bars) {
        fc.useTo(restCellVolts);
        phoneStartPercent = phonePercent;
        phoneTempDeci = (int) Math.round(phoneTempC * 10);
        signalBars = bars;
        sound.setQuiet(true);
    }

    // ------------------------------------------------------------------ the motion detection (MotionWatch's part)

    /** New detection areas (null: none came, the ones known stay), as the phone keeps them (MotionWatch.areas). */
    private void detectionAreas(DetectionAreas a) {
        if (a == null || a.equals(motion.areas())) return;
        log.accept("motion detection: " + a + " (the PC)");
        motion.setAreas(a);
    }

    /** The level asked for now: the pilot's DETECTION_SET within 5 s, an observer's, or overwatch's; 0 none. */
    private int detectionWanted(long now) {
        if (overwatch.isWatching()) return overwatch.sensitivity();
        int level = 0;
        if (isConnected() && now - detectionAskedAt < DETECTION_KEEP_MS) level = detectionLevel;
        Observe.Request w = observeWant;
        if (observing && w != null) level = Math.max(level, w.detection);
        return level;
    }

    /** Eight pictures a second through the shared MotionDetector while asked and disarmed (VirtualMotionWatch); the record to the PC. */
    private void motionTick(long now) {
        MotionRecord.Report report = motion.tick(now, detectionWanted(now), fcDisarmed(), view(), recording);
        if (report == null) return;
        try {
            UdpPacketData p = new UdpPacketData(UdpCommon.TelemetryData);
            MotionRecord.write(p.daos, report);
            byte[] data = p.getData();
            if (isConnected()) {
                UdpSender sender = udpSender;
                if (sender != null) sender.sendPacket(data);
            } else if (observing) {
                sendToObservers(data);
            }
        } catch (IOException ignored) {
        }
        if (observing && !isConnected()) sendObserverRecord(now);
    }

    /** The observer record (0x40FE), five times a second while observed. */
    private long nextObserverRecordAt;

    private void sendObserverRecord(long now) {
        if (now < nextObserverRecordAt) return;
        nextObserverRecordAt = now + 200;
        try {
            UdpPacketData p = new UdpPacketData(UdpCommon.TelemetryData);
            int flags = Observe.POS_VALID | Observe.POS_FROM_FC | Observe.FC_COMPASS | (fc.isArmed() ? Observe.ARMED : 0);
            Observe.writeRecord(p.daos, new Observe.Position(flags, fc.latDeg(), fc.lonDeg(), (int) Math.round(fc.altitudeM()), 0,
                    fc.headingDeg(), fc.headingDeg(), 3, 5, Scene.HFOV_DEG));
            sendToObservers(p.getData());
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ for overwatch

    /** Where the PC last flew it from (the link port): overwatch calls there. */
    InetSocketAddress lastPcAddress() {
        InetAddress ip = lastPcIp;
        return ip == null ? null : new InetSocketAddress(ip, lastPcPort);
    }

    public String name() {
        return name;
    }

    /** The tour's scenario: this drone's camera sees the BTR's world, its microphone hears it, flying into it breaks it. */
    void attachTarget(BtrScene s, Events e) {
        target = s;
        events = e;
        targetSound = s == null ? null : new BtrSound(3000L + slot);
        scene.setWorld(s);
    }

    /**
     * The tour's six drones (2026-11-09): this drone's camera sees the scene (and its microphone hears it, when it is
     * {@link Scene.Sounding}); null gives it the usual picture back.
     */
    void showScene(Scene.World w) {
        scene.setWorld(w);
    }

    BtrScene target() {
        return target;
    }

    public boolean isBroken() {
        return broken;
    }

    /**
     * Into a power mode as if this PC had asked (the tour's scenario): its check-ins and overwatch's calls go to pc.
     * Refused like the PC's request while the FC isn't known disarmed.
     */
    void placeInMode(InetSocketAddress pc, int wanted, int intervalMin, int sensitivity, int flags, int overwatchToken) {
        checkInAtOnce = true; // the tour: the PC's lists show it asleep at once, not at its first check in minutes
        lastPcIp = pc.getAddress();
        lastPcPort = pc.getPort();
        if (wanted == OVERWATCH) overwatch.configure(sensitivity, flags, overwatchToken);
        requestMode(wanted, intervalMin, "the tour");
    }

    /** Overwatch's first alert of an event (VirtualOverwatch). */
    void noteAlert() {
        Events e = events;
        if (e != null) e.alert(this);
    }

    /**
     * It flew into the vehicle (or a soldier): down, disarmed, and the link silent as a broken drone's. In the fleet
     * window PHONE RESTART brings it back (a new drone on the spot); the tour's ends there.
     */
    private void crashIntoTarget(long now, String what) {
        broken = true;
        fc.crash("flew into " + what, now);
        link.silence(now, 24L * 3600_000);
        log.accept("CRASH: flew into " + what + " - the drone is broken, its link is gone" + (target == null ? " (PHONE RESTART: a new one)" : ""));
        Events e = events;
        if (e != null) e.crashed(this);
    }

    /** Where it is now, {lat, lon} (PLACE PHONE's map). */
    public double[] position() {
        return new double[]{fc.latDeg(), fc.lonDeg()};
    }

    public boolean isArmed() {
        return fc.isArmed();
    }

    /** The pack's voltage per cell now. */
    public double cellVolts() {
        return fc.volts() / fc.cells();
    }

    public int slot() {
        return slot;
    }

    public InetAddress address() {
        return address;
    }

    public int port() {
        return port;
    }

    public int mode() {
        return mode;
    }

    public boolean asleep() {
        return asleep;
    }

    /** A listening window is open (a check, or always listening). */
    public boolean listening() {
        return checking;
    }

    /** CHECK EVERY "0 min" (pc.39): the window stays open. */
    private boolean always() {
        return SleepCheck.isAlways(checkIntervalMin);
    }

    // ------------------------------------------------------------------ the panel

    public void setFcLost(boolean lost) {
        if (lost != fcLost) log.accept(lost ? "FC LOST: phone gets nothing from the FC" : "FC answers again");
        fcLost = lost;
    }

    public boolean isFcLost() {
        return fcLost;
    }

    /** The video's source: a recorded flight (null: the generated picture). */
    void setRecording(Recording r) {
        Recording old = recording;
        recording = r;
        if (old != null) old.close();
        video.requestKeyFrame();
        if (audioThread != null) stopAudio(); // starts again from the new source
    }

    Recording recording() {
        return recording;
    }

    /** The 4G of this drone (the panel's LINK PROBLEMS). */
    public LinkImpairment link() {
        return link;
    }

    /**
     * Loops an old flight as the camera (null: the generated picture again).
     * @param track also play the flight CSV beside it as the telemetry, when there is one
     * @return a word for the panel: what plays
     */
    public String playRecording(java.nio.file.Path file, boolean track) throws IOException {
        if (file == null) {
            setRecording(null);
            log.accept("video: the generated picture");
            return "generated picture";
        }
        Recording r = new Recording(file);
        r.setPlayTrack(track);
        setRecording(r);
        String what = file.getFileName() + (r.hasAudio() ? " with its sound" : "") + (r.playsTrack() ? " and its track" : track ? " (no track beside it)" : "");
        log.accept("video: " + what);
        return what;
    }

    /** Something crossing the picture: "walker", "vehicle" or "light" (the panel's DETECTION buttons). */
    public void addTarget(String kind) {
        Scene.Kind k = switch (kind) {
            case "vehicle" -> Scene.Kind.VEHICLE;
            case "light" -> Scene.Kind.LIGHT;
            default -> Scene.Kind.WALKER;
        };
        scene.addTarget(k, view(), System.currentTimeMillis()); // in front of the camera where it is now (1.0.50)
        int level = detectionWanted(System.currentTimeMillis());
        log.accept("a " + kind + " crosses the picture" + (level > 0 ? " (motion detection " + level + ")"
                : " - no motion detection asked (VIDEO > Motion detection, observation or OVERWATCH)")
                + (fc.isArmed() ? " - armed: detection waits until disarmed" : ""));
    }

    public void setNight(boolean night) {
        scene.setNight(night);
    }

    public boolean isNight() {
        return scene.isNight();
    }

    /**
     * The panel's SOMEONE NOW (1.0.50, was ALERT NOW, which faked a detection): someone walks across 6 m in front of
     * the camera (with a torch at night). Whatever follows - boxes, an overwatch alert - is the real detector's doing.
     */
    public void someoneNow() {
        scene.addTarget(scene.isNight() ? Scene.Kind.LIGHT : Scene.Kind.WALKER, view(), System.currentTimeMillis(), Scene.CLOSE_M);
        int level = detectionWanted(System.currentTimeMillis());
        log.accept("someone walks across close to the camera" + (level > 0 ? " (motion detection " + level + ")" : " - no motion detection asked"));
    }

    /** Its motors beep now: the ESC tune on its microphone (the panel's BEEP). */
    public void speakNow() {
        sound.speakNow();
    }

    public void setVoices(boolean on) {
        sound.setVoices(on);
    }

    /** No beeping through its motors (the tour's drones); motors, breeze and birds stay. */
    public void setBeeperless(boolean on) {
        sound.setBeeperless(on);
    }

    /** A fresh pack, on the ground only. */
    public void newBattery() {
        fc.newBattery();
    }

    /**
     * Turns the disarmed drone to look this way (the fleet panel's LOOK, 1.0.52): its camera, its overwatch picture and
     * the heading it reports - the PC's map turns its view cone with the next telemetry.
     */
    public boolean lookTo(double headingDeg) {
        return fc.turnTo(headingDeg);
    }

    /** Where it looks (degrees clockwise from north). */
    public double headingDeg() {
        return fc.headingDeg();
    }

    /** Its flight controller's home at the launch point, kept through armings: it flew from there (1.0.52). */
    public void setLaunch(double lat, double lon) {
        fc.setLaunch(lat, lon);
    }

    /** Puts the disarmed drone on the ground somewhere else (a new start place). */
    public boolean moveTo(double lat, double lon) {
        double north = (lat - fc.latDeg()) * 111_320.0 + fc.northM();
        double east = (lon - fc.lonDeg()) * 111_320.0 * Math.cos(Math.toRadians(fc.latDeg())) + fc.eastM();
        return fc.moveTo(north, east);
    }

    /** What the panel shows for this drone. */
    public record Readout(String name, String address, boolean armed, String flightMode, String failsafe, double volts, double altM,
                   double speedKmh, String link, String pilot, String asked, String power, String extra) {}

    public Readout readout() {
        long now = System.currentTimeMillis();
        String flight = fc.failsafe() != FlightModel.Failsafe.NONE ? "FAILSAFE " + fc.failsafe() : fc.angleMode() ? "ANGLE" : "ACRO";
        UdpSender sender = udpSender;
        boolean connected = isConnected();
        String pilot = connected && sender != null && sender.getIp() != null ? sender.getIp().getHostAddress() + ":" + sender.getPort() : "-";
        int obs;
        synchronized (observers) {
            obs = observers.size();
        }
        String linkText = restarting ? "APP RESTARTING" : socket == null ? (asleep ? "ASLEEP" : "CLOSED")
                : link.silent(now) ? "NET LOST " + link.silentForMs(now) / 1000 + " s" : connected ? "SESSION" : obs > 0 ? "OBSERVED x" + obs : "IDLE";
        if (link.active(now) && !link.silent(now)) {
            linkText += String.format(java.util.Locale.ROOT, "  (loss %.0f%%, jitter %d ms, bunch %d ms)", link.lossPercent(), link.jitterMs(), link.bunchMs());
        }
        String asked = (!video.isOn() ? "video off" : "video " + video.state()) + ", step " + step + (keyFramesTwice ? ", robust" : "")
                + (audioOn ? ", sound on" : audioHeld ? ", sound held" : "") + (detectionWanted(now) > 0 ? ", detection " + detectionWanted(now) : "");
        String power = modeName(mode) + (checking ? " (check window)" : asleep ? " (asleep)" : sleepAt != 0 ? " (countdown)" : "")
                + overwatch.status();
        String extra = (fcLost ? "FC LOST  " : "") + (lastBeep != null ? "BEEPING " + lastBeep + "  " : "")
                + (idleFeeding ? "idle sticks  " : "") + (fc.lastDisarm().isEmpty() ? "" : "last disarm: " + fc.lastDisarm());
        return new Readout(name.isEmpty() ? "(no name yet)" : name, address.getHostAddress() + ":" + port, fc.isArmed(), flight,
                fc.failsafe().name(), fc.volts(), fc.altitudeM(), fc.groundSpeedMs() * 3.6, linkText, pilot, asked, power, extra.trim());
    }
}
