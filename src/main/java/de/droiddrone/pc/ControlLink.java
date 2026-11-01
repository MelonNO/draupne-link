package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.ReceiverBuffer;
import de.droiddrone.common.SavedPacket;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;
import de.droiddrone.common.UdpSender;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static de.droiddrone.common.Log.log;

/**
 * The PC side of a DroidDrone link: plays the role of the upstream Control app towards the Flight app.
 * Ported from upstream Control/Udp.java and MainActivity's connection thread (RC + handshake only, no video yet).
 */
public class ControlLink {
    public static final int MIN_RC_RATE = 10;
    public static final int MAX_RC_RATE = 250;
    /** As in the settings template. */
    public static final int DEFAULT_RC_RATE = 250;
    /**
     * RC rate we announce to the Flight app. Its only use there is Msp.setRawRc's minimum gap of 1000 / rate / 2 ms
     * (integer maths): frames arriving closer together are DROPPED, and over 4G frames often arrive bunched, so the drop
     * would discard the newest sticks. 255, the largest value the packet's unsigned byte holds, shrinks that window to
     * 1 ms. How often we actually send is our own rcRate.
     */
    static final int ANNOUNCED_RC_RATE = 255;
    /** Betaflight's MSP_RX_MAP has 8 entries (roll, pitch, yaw, throttle, AUX1-4). */
    static final int RX_MAP_SIZE = 8;

    /** SPECTATING / SPECTATOR_WAITING: another PC flies; this one watches through it (see {@link Spectate}). */
    public enum State { CONNECTING, CONFIGURING, READY, VERSION_MISMATCH, SPECTATING, SPECTATOR_WAITING, CLOSED }

    /** No FC data for this long while the link is up: the phone has lost the flight controller. */
    static final long FC_SILENCE_MS = 1500;
    /**
     * Nothing at all from the phone for this long while the link is up (video, telemetry and pongs normally arrive many
     * times a second): a stall. Betaflight has RXLOSS after 100 ms; the link only counts as lost after about 4 s.
     */
    static final long STALL_MS = 300;
    /** A pong older than this: no ping shown (it would look fine while the link is stuck). */
    static final long PING_STALE_MS = 500;
    /** Time after the link comes up for the first FC data to arrive. */
    static final long FC_GRACE_MS = 3000;

    /** Receives the video stream. Called on the network receive thread: copy what you keep, return quickly. */
    public interface VideoListener {
        /** Stream (re)starts: size, codec and the codec config (Annex-B VPS/SPS/PPS). */
        void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig);

        /** One UDP fragment of a frame; frameSize is only known (> 0) on the fragment at offset 0. */
        void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length);

        /** A frame's parity packet (bad coverage mode, phone app 2.3.0-pc.15): a single lost packet can be rebuilt. */
        default void onVideoParity(boolean keyFrame, short frameNum, int frameSize, byte[] buf, int dataOffset, int length) {
        }
    }

    /**
     * The camera always runs at a steady 30 fps. A range (30-60) let it switch with the light, which looked jumpy, and
     * 60 is dark indoors. Not a setting. Phone app 2.3.0-pc.12 matches it to the camera's nearest supported range.
     */
    static final int CAMERA_FPS = 30;
    /** A frame's parity packet: [0x6C][frame number i16][frame size i32][key frame u8][parity] (phone app pc.15). */
    static final byte VIDEO_PARITY = 0x6C;
    /** Asks the phone for a key frame (DRAUPNE Flight 2.3.0-pc.13 on; older apps ignore it). */
    static final byte KEY_FRAME_REQUEST = 0x6A;
    /**
     * Key frame requests at most this often. Each answer is the biggest burst there is: at 0.5 s the far link of
     * 2026-11-01 asked 1.3-1.5 times a second and its key frames came damaged in turn.
     */
    static final long KEY_FRAME_REQUEST_MS = 1500;
    /**
     * Bad coverage mode: 640x360 (at most 3/4 of the set size), up to 1 Mbit/s, key frames sent twice (phone app 2.3.0-pc.15
     * on; the PC waits a moment for the second copy). Small, light frames in short bursts: far less is lost on a weak
     * uplink. SETTINGS > Video: AUTO switches by itself, SHARP never, ROBUST always.
     */
    public enum VideoMode { AUTO, SHARP, ROBUST;
        public static VideoMode parse(String s) {
            for (VideoMode m : values()) if (m.name().equalsIgnoreCase(s == null ? "" : s.trim())) return m;
            return AUTO;
        }
    }
    public static final String VIDEO_MODE_KEY = "videoMode";
    static final int ROBUST_WIDTH = 640, ROBUST_HEIGHT = 360;
    /** The bitrate step bad coverage mode stays at or under: 1 Mbit/s. */
    public static final int ROBUST_MAX_STEP = 2;
    /** Tells the phone the video profile: [0x6B][flags], bit 0 = send key frames twice. */
    static final byte VIDEO_PROFILE = 0x6B;
    /** AUTO goes to bad coverage when the phone's bitrate stays at or under LOW_RES_BPS for LOW_RES_AFTER_MS... */
    static final int LOW_RES_BPS = 500_000;
    static final long LOW_RES_AFTER_MS = 5000;
    /** ...or the link stalls (no pong for STALL_EVENT_MS) STALLS_FOR_ROBUST times within STALLS_WINDOW_MS. */
    static final long STALL_EVENT_MS = 500;
    static final int STALLS_FOR_ROBUST = 3;
    static final long STALLS_WINDOW_MS = 30_000;
    /** Back to normal after this long calm in bad coverage mode; doubled (up to 5 min) when it had to go back soon. */
    static final long ROBUST_HOLD_MS = 20_000;
    private static final long MAX_ROBUST_HOLD_MS = 300_000;

    /** Upstream's Control app asks for the stream every 2.5 s until the codec config arrives. */
    private static final int START_VIDEO_RETRY_MS = 2500;

    private final PcConfig config;
    private final InetAddress remoteIp;
    private final int remotePort;
    private final int localPort;
    private final String key;
    private DatagramSocket socket;
    private UdpSender udpSender;
    private ReceiverBuffer receiverBuffer;
    private volatile boolean running;
    private volatile boolean configReceived;
    private volatile long readyAt;
    private volatile boolean versionMismatch;
    private volatile FcInfo fcInfo;
    private volatile int pingMs;
    private volatile long lastPingTimestamp;
    /** When anything last arrived from the phone (System.currentTimeMillis); 0 = nothing yet. */
    private volatile long lastReceivedAt;
    private long lastRcErrorLog;
    private final AtomicLong rcFramesSent = new AtomicLong();
    private final AtomicLong telemetryPacketsReceived = new AtomicLong();
    /** Longest pause between two RC frames sent / telemetry packets received since last taken (link log). */
    private final AtomicLong maxRcGapNs = new AtomicLong();
    private final AtomicLong maxTelemetryGapNs = new AtomicLong();
    private final AtomicLong maxPingMs = new AtomicLong();
    private long lastRcNs;
    private long lastTelemetryNs;
    private final FcTelemetry telemetry = new FcTelemetry();
    private volatile long lastKeyFrameRequest;
    private final AtomicLong keyFrameRequests = new AtomicLong();
    /** Bad coverage mode is on (see VideoMode). */
    private volatile boolean robust;
    private volatile VideoMode videoMode;
    private long lowRateSince = -1;
    private final java.util.ArrayDeque<Long> stalls = new java.util.ArrayDeque<>();
    private boolean inStall;
    private long robustSince;
    private long lastCalmEnd;
    private long robustHoldMs = ROBUST_HOLD_MS;
    private long leftRobustAt = -1;
    private int profileSends;
    private long nextProfileSend;
    private int configResends;
    private long nextConfigResend;
    /** The picture width of the phone's last video stream (its VideoInitialFrame); 0 before the first. */
    private volatile int videoWidth;
    /** A size switch not yet seen in the phone's stream: checked from this time on, at most VERIFY_TRIES times. */
    private long verifyFrom = -1;
    private int verifyTries;
    static final long VERIFY_AFTER_MS = 3000;
    static final int VERIFY_TRIES = 3;
    private volatile VideoListener videoListener;
    private volatile boolean videoHevc = true;
    private volatile boolean videoConfigReceived;
    /** Spectators may watch through this PC (direct phone mode only: the phone points them here). */
    private boolean spectatorsEnabled;
    private boolean spectatorsAllowed;
    private int spectatorDelayMs = SpectatorClient.DEFAULT_DELAY_MS;
    private volatile SpectatorHost host;
    /** Set once the phone said another PC flies: from then on this link only watches. */
    private volatile SpectatorClient spectator;
    /** Connected as a spectator on purpose (Connect as: Spectator): never asks the phone to fly, from the start. */
    private boolean watchOnly;
    private volatile Consumer<String> events = msg -> log(msg);

    public ControlLink(PcConfig config, InetAddress remoteIp, int remotePort, int localPort) {
        this.config = config;
        this.remoteIp = remoteIp;
        this.remotePort = remotePort;
        this.localPort = localPort;
        this.key = config.getKey();
    }

    /** Ask for the video stream (call before start). hevc = request H.265, else H.264. */
    public void setVideoListener(VideoListener listener, boolean hevc) {
        videoListener = listener;
        videoHevc = hevc;
    }

    /**
     * Spectator mode (call before start): a PC that connects while another flies is told so by the phone and watches
     * through that PC. allowed = false: this PC lets nobody watch (a spectator is told so).
     */
    public void enableSpectators(boolean allowed, int delayMs) {
        spectatorsEnabled = true;
        spectatorsAllowed = allowed;
        spectatorDelayMs = delayMs;
    }

    /**
     * Connect as a spectator (call before start, after {@link #enableSpectators}): this link never asks the phone to
     * connect; it asks who flies and watches through that PC, and waits while nobody does.
     */
    public void watchOnly() {
        watchOnly = true;
    }

    /** Connected as a spectator on purpose, rather than turned into one because another PC flies. */
    public boolean isWatchOnly() {
        return watchOnly;
    }

    /** Allow spectators switched while connected: from now on (off sends those watching away). */
    public void setSpectatorsAllowed(boolean allowed) {
        spectatorsAllowed = allowed;
        SpectatorHost h = host;
        if (h != null) h.setAllowed(allowed);
    }

    /** Where spectator news goes (joined, left, paused); the app log otherwise. */
    public void setEvents(Consumer<String> events) {
        this.events = events;
    }

    public void start() throws IOException {
        socket = new DatagramSocket(localPort);
        try {
            socket.setReceiveBufferSize(UdpCommon.packetLength * 300);
            socket.setSendBufferSize(UdpCommon.packetLength);
            socket.setTrafficClass(0x10);
        } catch (IOException | RuntimeException e) {
            socket.close(); // or the port stays taken and the next CONNECT fails
            throw e;
        }
        udpSender = new UdpSender(socket);
        udpSender.connect(remoteIp, remotePort);
        receiverBuffer = new ReceiverBuffer(udpSender, false, key, key);
        if (watchOnly) {
            // asks the phone who flies (PILOT_QUERY, never a Connect), then joins that PC
            spectator = new SpectatorClient(socket, remoteIp, remotePort, key, spectatorDelayMs, null, this::playSpectated);
        } else if (spectatorsEnabled) {
            try {
                host = new SpectatorHost(key, spectatorsAllowed, socket, () -> getState() == State.READY, this::getPing,
                        msg -> events.accept(msg), this::requestKeyFrame); // a new spectator starts at a key frame
            } catch (IOException e) {
                log("Spectators can't watch: " + e); // flying works as before
            }
        }
        running = true;
        startThread(this::receiverRun, "receiverThread", Thread.MAX_PRIORITY);
        startThread(this::bufferRun, "bufferThread", Thread.NORM_PRIORITY);
        startThread(this::pingRun, "pingThread", Thread.NORM_PRIORITY);
        startThread(this::sessionRun, "sessionThread", Thread.NORM_PRIORITY);
    }

    private void startThread(Runnable run, String name, int priority) {
        Thread t = new Thread(run, name);
        t.setDaemon(true);
        t.setPriority(priority);
        t.start();
    }

    private void receiverRun() {
        // room for the phone's packets and, when watching, the same with the pilot PC's STREAM header in front: a
        // datagram longer than the buffer is cut off without a word, and a cut video packet breaks its frame
        byte[] buf = new byte[UdpCommon.packetLength + Spectate.STREAM_HEADER + 16];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(packet);
                if (!packet.getAddress().equals(remoteIp) || packet.getPort() != remotePort) {
                    onForeignPacket(packet);
                    continue;
                }
                lastReceivedAt = System.currentTimeMillis();
                if (handleVideoFragment(packet)) continue;
                byte[] d = packet.getData();
                if (packet.getLength() > 0 && (d[0] == Spectate.PILOT_AT || d[0] == Spectate.NO_PILOT)) {
                    onPhoneSpectateAnswer(d, packet.getLength());
                    continue;
                }
                receiverBuffer.addPacket(packet);
            } catch (Exception e) {
                if (running) log("Receiver error: " + e);
            }
        }
    }

    /** Not from the phone: a spectator asking this PC, or (when this PC watches) the pilot PC. */
    private void onForeignPacket(DatagramPacket packet) {
        if (packet.getLength() < 1) return;
        SpectatorHost h = host;
        SpectatorClient c = spectator;
        if (h != null && packet.getData()[0] == Spectate.HELLO) {
            h.onHello(packet);
        } else if (c != null) {
            c.onPacket(packet);
        }
    }

    /**
     * The phone serves another PC. Answer to our Connect (or, once watching, to a PILOT_QUERY): this link turns into
     * a spectator for good - it stops asking the phone to connect, so it can't take the drone over.
     */
    private void onPhoneSpectateAnswer(byte[] d, int len) {
        if (isConnected() || !spectatorsEnabled) return;
        SpectatorClient c = spectator;
        if (d[0] == Spectate.NO_PILOT) {
            if (c != null) c.onNoPilot();
            return;
        }
        Spectate.Address pilot = Spectate.readPilotAt(d, len);
        if (pilot == null) return;
        if (c != null) {
            c.onPilotAt(pilot);
            return;
        }
        synchronized (this) {
            if (spectator != null || !running) return;
            spectator = new SpectatorClient(socket, remoteIp, remotePort, key, spectatorDelayMs, pilot, this::playSpectated);
        }
        events.accept("Another PC flies the drone (" + pilot.ip().getHostAddress() + "): watching as a spectator, "
                + spectatorDelayMs + " ms behind it. This PC sends no sticks.");
    }

    /** A packet the pilot PC passed on, played at its time (spectator thread). */
    private void playSpectated(byte[] d) {
        if (d.length < 1) return;
        if (d[0] == UdpCommon.VideoFrame || d[0] == UdpCommon.KeyFrame || d[0] == VIDEO_PARITY) {
            deliverVideoFragment(d, d.length);
            return;
        }
        if (d[0] == UdpCommon.TelemetryData) telemetryPacketsReceived.incrementAndGet();
        lastReceivedAt = System.currentTimeMillis();
        processData(d);
    }

    /**
     * Video fragments are unnumbered fire-and-forget packets, so the reliability buffer adds nothing for them but a
     * queue: its thread polls every ~1-2 ms. They go straight to the listener from the receive thread instead.
     * Layout (upstream Flight Udp.sendVideoFrame): [1|2][frameNum i16][offset i32][frameSize i32 if offset == 0][data].
     */
    private boolean handleVideoFragment(DatagramPacket packet) {
        byte[] d = packet.getData();
        int len = packet.getLength();
        if (len < 1 || (d[0] != UdpCommon.VideoFrame && d[0] != UdpCommon.KeyFrame && d[0] != VIDEO_PARITY)) return false;
        if (!isConnected()) return true;
        deliverVideoFragment(d, len);
        SpectatorHost h = host;
        if (h != null) h.forward(d, len); // after our own picture: a copy into a queue, nothing without spectators
        return true;
    }

    private void deliverVideoFragment(byte[] d, int len) {
        VideoListener listener = videoListener;
        if (listener == null || len < 7) return;
        if (d[0] == VIDEO_PARITY) {
            if (len > 8) listener.onVideoParity(d[7] != 0, (short) (((d[1] & 0xff) << 8) | (d[2] & 0xff)), readInt(d, 3), d, 8, len - 8);
            return;
        }
        short frameNum = (short) (((d[1] & 0xff) << 8) | (d[2] & 0xff));
        int offset = readInt(d, 3);
        int frameSize = 0;
        int header = 7;
        if (offset == 0) {
            if (len < 11) return;
            frameSize = readInt(d, 7);
            header = 11;
        }
        listener.onVideoFragment(d[0] == UdpCommon.KeyFrame, frameNum, offset, frameSize, d, header, len - header);
    }

    private static int readInt(byte[] d, int i) {
        return ((d[i] & 0xff) << 24) | ((d[i + 1] & 0xff) << 16) | ((d[i + 2] & 0xff) << 8) | (d[i + 3] & 0xff);
    }

    private void bufferRun() {
        while (running) {
            SavedPacket packet = null;
            try {
                do {
                    packet = receiverBuffer.getNextPacket();
                    if (packet != null) processData(packet.data);
                } while (packet != null);
                receiverBuffer.processTimer();
                Thread.sleep(1);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log("Receiver buffer error: " + e + ", packetName: " + (packet == null ? -1 : packet.packetName));
            }
        }
    }

    private void pingRun() {
        while (running) {
            try {
                if (isConnected()) udpSender.sendPing(true);
                // 20 a second: the bitrate control watches the ping for queueing on the uplink, and the link log
                // sees stalls of 0.1 s and more (where Betaflight flags RXLOSS) as the age of the last pong
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Same order as upstream MainActivity.startConnectionThread: connect, send config, then start the video stream
     * (if wanted) and ask for FC info.
     */
    private void sessionRun() {
        boolean wasConnected = false;
        long lastStartVideo = 0;
        long lastFcInfoRequest = 0;
        long lastModesRequest = 0;
        while (running) {
            try {
                if (spectator != null) { // watching: the spectator thread does the talking, never a Connect again
                    Thread.sleep(250);
                    continue;
                }
                boolean connected = isConnected();
                if (wasConnected && !connected) {
                    log("Link lost, reconnecting...");
                    configReceived = false;
                    videoConfigReceived = false;
                    fcInfo = null;
                    telemetry.forgetBoxIds(); // a restarted phone app has to read them again
                }
                wasConnected = connected;
                if (versionMismatch) {
                    Thread.sleep(1000);
                } else if (!connected) {
                    udpSender.sendConnect(1, key, SettingsCommon.versionCompatibleCode);
                    Thread.sleep(500);
                } else if (!configReceived) {
                    sendConfig();
                    Thread.sleep(500);
                } else {
                    long now = System.currentTimeMillis();
                    if (videoListener != null && !videoConfigReceived && now - lastStartVideo >= START_VIDEO_RETRY_MS) {
                        sendStartVideo();
                        lastStartVideo = now;
                    }
                    adjustResolution(now);
                    if (fcInfo == null && now - lastFcInfoRequest >= 1000) {
                        sendRequest(UdpCommon.FcInfo);
                        lastFcInfoRequest = now;
                    } else if (fcInfo != null && !telemetry.hasBoxNames() && now - lastModesRequest >= 2000) {
                        sendRequest(UdpCommon.BoxNames); // names of the flight modes, to read MSP_STATUS mode flags
                        lastModesRequest = now;
                    } else if (fcInfo != null && !telemetry.hasBoxIds() && now - lastModesRequest >= 2000) {
                        // the Flight app finds ARM by the mode IDs and only reads them when asked (the original Control
                        // app asks): without them it never sees the drone armed, so its fly time, travelled distance
                        // and camera switch recording stay off. Once per connection, a few small MSP requests.
                        sendRequest(UdpCommon.BoxIds);
                        lastModesRequest = now;
                    }
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log("Session thread error: " + e);
            }
        }
    }

    private void processData(byte[] data) {
        DataReader buffer = new DataReader(data, true);
        byte packetName = buffer.readByte();
        if (UdpCommon.isPacketNumbered(packetName)) buffer.readShort();
        switch (packetName) {
            case UdpCommon.Pong -> {
                boolean toEndPoint = buffer.readBoolean();
                long time = buffer.readLong();
                if (toEndPoint) {
                    pingMs = (int) (System.currentTimeMillis() - time);
                    lastPingTimestamp = System.currentTimeMillis();
                    maxPingMs.accumulateAndGet(pingMs, Math::max);
                }
            }
            case UdpCommon.ConfigReceived -> {
                if (!configReceived) readyAt = System.currentTimeMillis();
                configReceived = true;
            }
            case UdpCommon.VersionMismatch -> {
                configReceived = false;
                versionMismatch = true;
            }
            case UdpCommon.FcInfo -> {
                fcInfo = new FcInfo(buffer.readByte(), buffer.readByte(), buffer.readByte(),
                        buffer.readByte(), buffer.readByte(), buffer.readByte(), buffer.readByte(), buffer.readByte());
                SpectatorHost h = host;
                if (h != null) {
                    h.rememberFcInfo(data);
                    h.forward(data, data.length);
                }
            }
            case UdpCommon.TelemetryData -> {
                if (spectator == null) telemetryPacketsReceived.incrementAndGet(); // a spectator counts on play
                long now = System.nanoTime();
                if (lastTelemetryNs != 0) maxTelemetryGapNs.accumulateAndGet(now - lastTelemetryNs, Math::max);
                lastTelemetryNs = now;
                boolean hadNames = telemetry.hasBoxNames();
                boolean hadIds = telemetry.hasBoxIds();
                telemetry.parse(buffer, fcInfo);
                SpectatorHost h = host;
                if (h != null) {
                    if (!hadNames && telemetry.hasBoxNames()) h.rememberBoxNames(data);
                    if (!hadIds && telemetry.hasBoxIds()) h.rememberBoxIds(data);
                    h.forward(data, data.length);
                }
            }
            case UdpCommon.VideoInitialFrame -> {
                int width = buffer.readShort();
                int height = buffer.readShort();
                boolean hevc = buffer.readBoolean();
                boolean front = buffer.readBoolean();
                videoWidth = width;
                byte[] codecConfig = new byte[buffer.getRemaining()];
                buffer.read(codecConfig, 0, codecConfig.length);
                videoConfigReceived = true;
                VideoListener listener = videoListener;
                if (listener != null) listener.onVideoConfig(width, height, hevc, front, codecConfig);
                SpectatorHost h = host;
                if (h != null) {
                    h.rememberVideoConfig(data);
                    h.forward(data, data.length);
                }
            }
            default -> {
                // audio and OSD packets are handled in later milestones
            }
        }
    }

    private void sendRequest(byte packetName) throws IOException {
        udpSender.sendPacket(new UdpPacketData(packetName).getData());
    }

    private void sendStartVideo() throws IOException {
        UdpPacketData packetData = new UdpPacketData(UdpCommon.StartVideo);
        packetData.daos.writeBoolean(videoHevc);
        udpSender.sendPacket(packetData.getData());
    }

    /** Asks the phone's encoder for a lower or higher bitrate (upstream steps through a fixed list of rates). */
    public void sendChangeBitRate(boolean increase) {
        if (!running || !isConnected() || spectator != null) return;
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.ChangeBitRate);
            packetData.daos.writeBoolean(increase);
            udpSender.sendPacket(packetData.getData());
        } catch (IOException e) {
            log("sendChangeBitRate error: " + e);
        }
    }

    /** What the flight controller reports (armed state, modes, arming blockers, battery). */
    public FcTelemetry getTelemetry() {
        return telemetry;
    }

    public boolean isVideoConfigReceived() {
        return videoConfigReceived;
    }

    /** Field-for-field the same packet as upstream Control Udp.sendConfig, filled from draupne-link.properties. */
    byte[] buildConfigPacket() throws IOException {
        UdpPacketData packetData = new UdpPacketData(UdpCommon.Config);
        packetData.daos.writeShort(SettingsCommon.versionCompatibleCode);
        // cameras: only the first one is used for now
        for (int i = 0; i < SettingsCommon.maxCamerasCount; i++) {
            if (i > 0) packetData.daos.writeBoolean(false);
            packetData.daos.writeUTF(i == 0 ? config.getCameraId() : SettingsCommon.cameraId[i]);
            packetData.daos.writeShort(cameraWidth());
            packetData.daos.writeShort(cameraHeight());
            packetData.daos.writeShort(CAMERA_FPS); // min = max: steady
            packetData.daos.writeShort(CAMERA_FPS);
            packetData.daos.writeBoolean(SettingsCommon.useUsbCamera);
            packetData.daos.writeByte(SettingsCommon.usbCameraFrameFormat);
            packetData.daos.writeBoolean(SettingsCommon.usbCameraReset);
        }
        // video
        packetData.daos.writeByte(config.getBitrateLimitMbps());
        packetData.daos.writeBoolean(SettingsCommon.useExtraEncoder);
        packetData.daos.writeByte(SettingsCommon.videoRecorderCodec);
        packetData.daos.writeByte(SettingsCommon.recordedVideoBitrate / 1000000);
        // audio
        packetData.daos.writeBoolean(SettingsCommon.sendAudioStream);
        packetData.daos.writeShort(SettingsCommon.audioStreamBitrate / 1000);
        packetData.daos.writeBoolean(SettingsCommon.recordAudio);
        packetData.daos.writeShort(SettingsCommon.recordedAudioBitrate / 1000);
        // fc
        packetData.daos.writeByte(config.getTelemetryRate());
        packetData.daos.writeByte(ANNOUNCED_RC_RATE);
        packetData.daos.writeInt(config.getSerialBaudRate());
        packetData.daos.writeByte(SettingsCommon.usbSerialPortIndex);
        packetData.daos.writeBoolean(SettingsCommon.useNativeSerialPort);
        packetData.daos.writeUTF(SettingsCommon.nativeSerialPort);
        packetData.daos.writeByte(FcCommon.FC_PROTOCOL_MSP);
        packetData.daos.writeByte(SettingsCommon.mavlinkTargetSysId);
        packetData.daos.writeByte(SettingsCommon.mavlinkGcsSysId);
        packetData.daos.writeByte(SettingsCommon.MavlinkUdpBridge.disabled);
        packetData.daos.writeUTF(SettingsCommon.mavlinkUdpBridgeIp);
        packetData.daos.writeShort(SettingsCommon.mavlinkUdpBridgePort);
        return packetData.getData();
    }

    /** The camera width asked for: as set, or the bad coverage size. */
    int cameraWidth() {
        return robust ? Math.min(ROBUST_WIDTH, config.getCameraWidth() * 3 / 4) / 2 * 2 : config.getCameraWidth();
    }

    int cameraHeight() {
        return robust ? Math.min(ROBUST_HEIGHT, config.getCameraHeight() * 3 / 4) / 2 * 2 : config.getCameraHeight();
    }

    /** SETTINGS > Video, also while connected. */
    public void setVideoMode(VideoMode mode) {
        videoMode = mode;
    }

    private VideoMode videoMode() {
        VideoMode m = videoMode;
        return m != null ? m : VideoMode.parse(config.getString(VIDEO_MODE_KEY));
    }

    /** The highest bitrate step now: 1 Mbit/s in bad coverage mode, else the set limit (bitrateLimitMbps). */
    public int maxBitrateStep() {
        if (robust) return ROBUST_MAX_STEP;
        int limit = config.getBitrateLimitMbps() * 1_000_000;
        int[] steps = de.droiddrone.pc.video.BitrateControl.STEPS_BPS;
        int s = 0;
        while (s < steps.length - 1 && steps[s + 1] <= limit) s++;
        return s;
    }

    /**
     * Bad coverage mode on or off (see VideoMode): the size goes out as a config (the phone changes it on the open camera,
     * phone app 2.3.0-pc.15), the profile as a packet of its own; both sent again until the phone's stream shows it.
     */
    private void adjustResolution(long now) throws IOException {
        if (configResends > 0 && now >= nextConfigResend) {
            configResends--;
            nextConfigResend = now + 1000;
            sendConfig();
        }
        if (profileSends > 0 && now >= nextProfileSend) {
            profileSends--;
            nextProfileSend = now + 700;
            sendProfile();
        }
        // did the phone really switch? Its new stream says (2026-11-01: a switch back to 720p never happened, and PC
        // and phone disagreed for 3 minutes). If not, the size goes out again
        if (verifyFrom >= 0 && now >= verifyFrom) {
            boolean switched = videoWidth > 0 && (robust ? videoWidth < config.getCameraWidth() : videoWidth >= config.getCameraWidth());
            if (switched || verifyTries >= VERIFY_TRIES) {
                if (!switched) events.accept("Video: the phone didn't change the picture size (still " + videoWidth + " wide).");
                verifyFrom = -1;
            } else {
                verifyTries++;
                verifyFrom = now + VERIFY_AFTER_MS;
                log("Video size not changed by the phone yet: sending it again (" + verifyTries + ")");
                sendConfig();
                sendProfile();
            }
        }
        if (spectator != null) return;
        // stalls: no pong for STALL_EVENT_MS, counted once each
        long sincePong = msSinceLastPong();
        boolean stalled = sincePong > STALL_EVENT_MS;
        if (stalled && !inStall) stalls.addLast(now);
        inStall = stalled;
        while (!stalls.isEmpty() && now - stalls.peekFirst() > STALLS_WINDOW_MS) stalls.pollFirst();
        if (stalled) lastCalmEnd = now;
        int bps = telemetry.getPhoneTargetBitRate();
        lowRateSince = bps > 0 && bps <= LOW_RES_BPS ? (lowRateSince < 0 ? now : lowRateSince) : -1;
        VideoMode mode = videoMode();
        boolean want;
        String why;
        if (mode == VideoMode.ROBUST) {
            want = true;
            why = "SETTINGS > Video is ROBUST";
        } else if (mode == VideoMode.SHARP) {
            want = false;
            why = "SETTINGS > Video is SHARP";
        } else if (!robust) {
            boolean lowRate = lowRateSince >= 0 && now - lowRateSince >= LOW_RES_AFTER_MS;
            want = lowRate || stalls.size() >= STALLS_FOR_ROBUST;
            why = lowRate ? "the bitrate stayed at " + bps / 1000 + " kbit/s" : stalls.size() + " stalls in " + STALLS_WINDOW_MS / 1000 + " s";
            if (want && leftRobustAt >= 0 && now - leftRobustAt < 60_000) {
                robustHoldMs = Math.min(MAX_ROBUST_HOLD_MS, robustHoldMs * 2); // back too soon: stay longer next time
            }
        } else {
            // calm long enough in bad coverage mode: no stall, and the bitrate not struggling under its 1 Mbit/s
            boolean calm = now - robustSince >= robustHoldMs && now - lastCalmEnd >= robustHoldMs
                    && (bps <= 0 || bps > LOW_RES_BPS);
            want = !calm;
            why = "calm for " + robustHoldMs / 1000 + " s";
        }
        if (want == robust) return;
        robust = want;
        if (robust) {
            robustSince = now;
            stalls.clear();
        } else {
            leftRobustAt = now;
        }
        events.accept(robust
                ? "Video: bad coverage mode (" + why + "): " + cameraWidth() + "x" + cameraHeight()
                        + ", up to 1 Mbit/s, key frames sent twice - softer, but it holds."
                : "Video: normal mode again (" + why + "): " + cameraWidth() + "x" + cameraHeight() + ".");
        configResends = 1;
        nextConfigResend = now + 1000;
        profileSends = 2;
        nextProfileSend = now + 700;
        verifyFrom = videoListener == null ? -1 : now + VERIFY_AFTER_MS; // without video there is nothing to check
        verifyTries = 0;
        sendConfig();
        sendProfile();
    }

    /** The phone's video profile (phone app 2.3.0-pc.15 on; older ones drop it): key frames twice in bad coverage. */
    private void sendProfile() {
        try {
            socket.send(new DatagramPacket(new byte[]{VIDEO_PROFILE, (byte) (robust ? 1 : 0)}, 2, remoteIp, remotePort));
        } catch (IOException e) {
            // sent again with the next round
        }
    }

    /** Bad coverage mode is on. */
    public boolean isRobust() {
        return robust;
    }

    /**
     * Asks the phone for a key frame now (phone app 2.3.0-pc.13 on; older ones drop the packet): a frame came
     * damaged or lost, a recording or a spectator starts. At most every KEY_FRAME_REQUEST_MS; not while watching.
     */
    public void requestKeyFrame() {
        long now = System.currentTimeMillis();
        if (!running || spectator != null || !isConnected() || now - lastKeyFrameRequest < KEY_FRAME_REQUEST_MS) return;
        lastKeyFrameRequest = now;
        try {
            socket.send(new DatagramPacket(new byte[]{KEY_FRAME_REQUEST}, 1, remoteIp, remotePort));
            keyFrameRequests.incrementAndGet();
        } catch (IOException e) {
            // the next damaged frame asks again
        }
    }

    public long getKeyFrameRequests() {
        return keyFrameRequests.get();
    }

    private void sendConfig() throws IOException {
        udpSender.sendPacket(buildConfigPacket());
    }

    /**
     * Sends stick values (AETR+AUX order) right now, on the caller's thread: no queue or timer between reading the
     * joystick and the packet leaving. Call once per input tick; frames only go out while the caller keeps calling,
     * so a stalled input loop means no RC, the phone stops feeding MSP and Betaflight fails safe.
     * @return true if a frame was sent (false while not connected/configured)
     */
    public boolean sendRc(short[] channels) {
        if (channels.length < 4 || channels.length > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT) {
            throw new IllegalArgumentException("RC channel count must be 4.." + FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT);
        }
        if (!running || !configReceived || !isConnected()) return false;
        short[] frame = padForRxMap(channels);
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.RcFrame);
            packetData.daos.writeByte(frame.length);
            for (short channel : frame) packetData.daos.writeShort(channel);
            byte[] data = packetData.getData();
            // straight out, once: upstream's sendPacket retries ten times and swallows the error, so a dead adapter
            // looked like frames sent - and a frame late is a frame wrong
            socket.send(new DatagramPacket(data, data.length, remoteIp, remotePort));
            rcFramesSent.incrementAndGet();
            long now = System.nanoTime();
            if (lastRcNs != 0) maxRcGapNs.accumulateAndGet(now - lastRcNs, Math::max);
            lastRcNs = now;
            return true;
        } catch (IOException e) {
            long now = System.currentTimeMillis();
            if (now - lastRcErrorLog >= 1000) { // 250 a second otherwise
                lastRcErrorLog = now;
                log("sendRc error: " + e);
            }
            return false;
        }
    }

    /**
     * Upstream Flight Msp.processRxMap reorders channels to Betaflight's channel map (8 entries), but silently skips
     * the reordering when a frame has fewer than 8 channels - with any map other than AETR1234 the sticks would then
     * land on the wrong channels. So frames are always at least 8 long; extra AUX channels idle at 1000.
     */
    static short[] padForRxMap(short[] channels) {
        if (channels.length >= RX_MAP_SIZE) return channels;
        short[] padded = java.util.Arrays.copyOf(channels, RX_MAP_SIZE);
        for (int i = channels.length; i < RX_MAP_SIZE; i++) padded[i] = (short) RcMapper.MIN_CHANNEL_LEVEL;
        return padded;
    }

    public boolean isConnected() {
        return receiverBuffer != null && receiverBuffer.isConnected();
    }

    /**
     * The link to the phone is up, but the phone isn't passing on anything from the flight controller (USB/OTG
     * cable out, FC unpowered or rebooting). The sticks don't reach the drone then either.
     */
    public boolean isFcLost() {
        if (getState() != State.READY || isLinkStalled()) return false; // a stalled link explains the silence itself
        long now = System.currentTimeMillis();
        return now - readyAt > FC_GRACE_MS && telemetry.msSinceFcData(now) > FC_SILENCE_MS;
    }

    public State getState() {
        if (!running) return State.CLOSED;
        SpectatorClient c = spectator;
        if (c != null) return c.phase() == SpectatorClient.Phase.WATCHING ? State.SPECTATING : State.SPECTATOR_WAITING;
        if (versionMismatch) return State.VERSION_MISMATCH;
        if (!isConnected()) return State.CONNECTING;
        if (!configReceived) return State.CONFIGURING;
        return State.READY;
    }

    /** @return round-trip time to the Flight phone in ms, or -1 if no recent pong */
    public int getPing() {
        return System.currentTimeMillis() - lastPingTimestamp > PING_STALE_MS ? -1 : pingMs;
    }

    /**
     * The link is up but nothing has come from the phone for STALL_MS: the sticks most likely aren't getting through
     * either. Seen within a third of a second, long before the link counts as lost.
     */
    public boolean isLinkStalled() {
        long last = lastReceivedAt;
        if (getState() != State.READY) return false;
        long now = System.currentTimeMillis();
        if (last != 0 && now - last > STALL_MS) return true;
        // the phone app stuck while its telemetry keeps coming (2.3.0-pc.13 on a test phone): pings go unanswered,
        // and the sticks - handled by the same part of the app - don't reach the flight controller either. Only once
        // pongs came on this link and then stopped: a relay, or a phone that never answers pings, isn't a stall
        long pong = lastPingTimestamp;
        return pong > readyAt && now - pong > PONG_SILENCE_MS;
    }

    /** No pong for this long while the link is up (pings go out 20 times a second): the phone app isn't answering. */
    static final long PONG_SILENCE_MS = 1000;

    /** True once another PC flies and this one watches; it stays so until disconnected. */
    public boolean isSpectator() {
        return spectator != null;
    }

    /** While watching: what is going on, for the main window; null when flying. */
    public String spectatorStatus() {
        SpectatorClient c = spectator;
        if (c == null) return null;
        Spectate.Address p = c.pilot();
        String who = p == null ? "the pilot PC" : "the pilot PC (" + p.ip().getHostAddress() + ")";
        return switch (c.phase()) {
            case WATCHING -> "SPECTATOR: watching " + who + ", " + c.delayMs() + " ms behind it. This PC sends no sticks.";
            case JOINING -> p == null ? "SPECTATOR: asking the phone which PC flies..."
                    : "SPECTATOR: another PC flies the drone - joining " + who + "...";
            case REFUSED_NOT_FLYING -> "SPECTATOR: " + who + " is not flying yet - waiting.";
            case REFUSED_FULL -> "SPECTATOR: " + who + " already has " + SpectatorHost.MAX_SPECTATORS + " spectators - waiting for a place.";
            case REFUSED_OFF -> "SPECTATOR: " + who + " lets nobody watch (SETTINGS > Allow spectators is off there).";
            case NO_PILOT -> watchOnly ? "SPECTATOR: nobody flies the drone yet - waiting for a pilot to connect."
                    : "SPECTATOR: nobody flies the drone now. To fly it from this PC, DISCONNECT and CONNECT again.";
        };
    }

    /** How many PCs watch through this one. */
    public int getSpectatorCount() {
        SpectatorHost h = host;
        return h == null ? 0 : h.count();
    }

    public FcInfo getFcInfo() {
        return fcInfo;
    }

    public long getRcFramesSent() {
        return rcFramesSent.get();
    }

    public long getTelemetryPacketsReceived() {
        return telemetryPacketsReceived.get();
    }

    /** Longest pause between two RC frames sent since the last call, in ms (0 if fewer than two were sent). */
    public long takeMaxRcGapMs() {
        return maxRcGapNs.getAndSet(0) / 1_000_000;
    }

    /** Highest round trip since the last call, in ms (0 if no pong came back). */
    public long takeMaxPingMs() {
        return maxPingMs.getAndSet(0);
    }

    /** How long ago the last pong came back (pings go out 20 times a second), in ms; -1 before the first. */
    public long msSinceLastPong() {
        long last = lastPingTimestamp;
        return last == 0 ? -1 : System.currentTimeMillis() - last;
    }

    /** Longest pause between two telemetry packets received since the last call, in ms. */
    public long takeMaxTelemetryGapMs() {
        return maxTelemetryGapNs.getAndSet(0) / 1_000_000;
    }

    /** Tells the Flight app we are leaving (it stops streaming), then closes the socket. */
    public void close() {
        if (!running) {
            if (socket != null) socket.close(); // start() may have stopped half way
            return;
        }
        try {
            if (isConnected()) udpSender.sendPacket(new UdpPacketData(UdpCommon.Disconnect).getData());
        } catch (IOException e) {
            log("sendDisconnect error: " + e);
        }
        running = false;
        SpectatorClient c = spectator;
        if (c != null) c.close();
        SpectatorHost h = host;
        if (h != null) h.close();
        if (udpSender != null) udpSender.close();
        if (receiverBuffer != null) receiverBuffer.close();
        if (socket != null) socket.close();
    }
}
