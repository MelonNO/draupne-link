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
import de.droiddrone.pc.audio.AudioConfig;

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

        /**
         * The parity of one group of a frame's packets (bad coverage mode, phone app 2.3.0-pc.26): any one lost
         * packet of that group can be rebuilt, so a frame survives one loss per group, not one in all.
         */
        default void onVideoParityGroup(boolean keyFrame, short frameNum, int frameSize, int group, byte[] buf, int dataOffset, int length) {
        }

        /** The decoder's time per frame over the time between frames; above 1 it falls behind (0 = unknown). */
        default double decodeLoad() {
            return 0;
        }
    }

    /**
     * Receives the phone's live audio (1.0.45): its config and its raw AAC frames. Called on the link's receive and
     * buffer threads (or, watching, the spectator thread): hand the data on and return at once.
     */
    public interface AudioListener {
        void onAudioConfig(AudioConfig config);

        /**
         * One raw AAC frame (a copy, the listener's to keep) of the stream config describes; arrivedMs on
         * MonoClock.ms, as the video's (1.0.49).
         */
        void onAudioFrame(AudioConfig config, byte[] frame, long arrivedMs);
    }

    /** The first phone app build that sends live audio on request (AUDIO, PcAudio). */
    public static final int AUDIO_MIN_PC_BUILD = 34;

    /** What the live audio does now, for the main window. */
    public enum AudioState {
        /** Not flying with a link up (and, watching, no sound from the pilot PC). */
        NONE,
        /** The phone app is older than pc.34. */
        OLD_PHONE,
        /** Asked for (or the phone's version not said yet), nothing yet. */
        WAITING,
        /** The sound comes. */
        ON,
        /** The phone holds it back: its flight controller isn't known disarmed (armed, or no fresh status). */
        HELD,
        /** The phone couldn't start its microphone or encoder. */
        FAILED,
        /** Muted long enough that the phone was asked to stop. */
        STOPPED
    }

    /**
     * The camera always runs at a steady 30 fps. A range (30-60) let it switch with the light, which looked jumpy, and
     * 60 is dark indoors. Not a setting. Phone app 2.3.0-pc.12 matches it to the camera's nearest supported range.
     */
    static final int CAMERA_FPS = 30;
    /** A frame's parity packet: [0x6C][frame number i16][frame size i32][key frame u8][parity] (phone app pc.15). */
    static final byte VIDEO_PARITY = 0x6C;
    /**
     * The parity of one group of {@link de.droiddrone.pc.video.FrameAssembler#PARITY_GROUP} packets of a frame:
     * [0x71][frame number i16][frame size i32][key frame u8][group u8][parity] (phone app pc.26, asked for by
     * profile bit 1; older PCs drop the packet).
     */
    static final byte VIDEO_PARITY_GROUP = 0x71;
    /**
     * Sets the phone's bitrate step outright: [0x72][step u8], the index into BitrateControl.STEPS_BPS (phone app
     * pc.26; older apps drop it and get the relative ChangeBitRate instead). A filling uplink queue is answered with
     * one packet to the floor, not one step per second.
     */
    static final byte SET_BITRATE = 0x72;
    /** The first phone app build that takes SET_BITRATE, the group parity, the 16-bit stick sequence and profile bit 2. */
    static final int PC26_BUILD = 26;
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
     * uplink. Under it (1.0.33) the survival tier: 480x270 at 15 fps, up to 250 kbit/s, for a link that can't even
     * carry bad coverage mode - some picture rather than none. Above the normal picture (1.0.34) the sharp tier: the
     * sharp size and bitrate limit from the settings (1920x1080, up to 8 Mbit/s), for a link that carries them.
     * VIDEO > Video: AUTO goes sharp after SHARP_AFTER_MS calm at the normal top bitrate and leaves it at the
     * first stall, and goes to bad coverage mode and under when it must. Every tier AUTO can pick is a choice of its
     * own too (1.0.39, the pilot: "include buttons for all the video adjustments that the program may change it to"):
     * SHARP, NORMAL, ROBUST (bad coverage mode) and SURVIVAL hold that tier, whatever the link, from the moment they
     * are chosen - from any tier (up to 1.0.38 ROBUST did nothing while the sharp tier was on).
     */
    public enum VideoMode { AUTO, SHARP, NORMAL, ROBUST, SURVIVAL;
        public static VideoMode parse(String s) {
            for (VideoMode m : values()) if (m.name().equalsIgnoreCase(s == null ? "" : s.trim())) return m;
            return AUTO;
        }

        /** The tier this choice holds; null for AUTO. */
        public Tier forced() {
            return switch (this) {
                case AUTO -> null;
                case SHARP -> Tier.SHARP;
                case NORMAL -> Tier.NORMAL;
                case ROBUST -> Tier.ROBUST;
                case SURVIVAL -> Tier.SURVIVAL;
            };
        }
    }
    /** How much of the picture the link gets, best first: the sharp tier, the normal picture, bad coverage mode, the survival tier (see VideoMode). */
    public enum Tier { SHARP, NORMAL, ROBUST, SURVIVAL }
    public static final String VIDEO_MODE_KEY = "videoMode";
    static final int ROBUST_WIDTH = 640, ROBUST_HEIGHT = 360;
    static final int SURVIVAL_WIDTH = 480, SURVIVAL_HEIGHT = 270;
    static final int SURVIVAL_FPS = 15;
    /** The bitrate step bad coverage mode stays at or under: 1 Mbit/s (BitrateControl.STEPS_BPS). */
    public static final int ROBUST_MAX_STEP = 3;
    /** The survival tier's top: 250 kbit/s; its floor is the phone's lowest, 150 kbit/s (phone app pc.26). */
    public static final int SURVIVAL_MAX_STEP = 1;
    /**
     * Tells the phone the video profile: [0x6B][flags]. Bit 0 = send key frames twice (pc.15), bit 1 = a parity
     * packet per group of packets instead of one per frame (pc.26), bit 2 = encode H.264 instead of H.265 (pc.26:
     * FFmpeg conceals a damaged H.264 frame from the one before; its H.265 decoder shows the damage).
     */
    static final byte VIDEO_PROFILE = 0x6B;
    static final int PROFILE_KEY_FRAMES_TWICE = 1, PROFILE_GROUP_PARITY = 2, PROFILE_AVC = 4;
    /** The bitrate at or under which bad coverage mode goes to the survival tier (after LOW_RES_AFTER_MS). */
    static final int SURVIVAL_BPS = 250_000;
    /** Bad coverage mode has to have had this long before the survival tier is judged. */
    static final long SURVIVAL_AFTER_MS = 5000;
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
    /**
     * AUTO goes to the sharp tier after this long calm (no stall) at the normal top bitrate; doubled (up to 5 min)
     * when it had to leave the sharp tier within a minute, back to this after a sharp spell that lasted.
     */
    static final long SHARP_AFTER_MS = 20_000;
    /** The sharp tier ends when the phone's bitrate sat under the normal top for this long: the link can't carry it. */
    static final long SHARP_DROP_MS = 5000;

    /** Upstream's Control app asks for the stream every 2.5 s until the codec config arrives. */
    private static final int START_VIDEO_RETRY_MS = 2500;
    /**
     * The link back after a loss: this long later the PC looks whether the video came back with it. The phone's
     * stream usually never stopped (the loss was the link, not the phone), and asking for it again would close and
     * rebuild the phone's encoder and camera session: 3-5 s of no picture. Only a stream that really stopped (the
     * phone app restarted) is asked for again.
     */
    static final long VIDEO_CHECK_MS = 1500;
    /** Telemetry comes but no video fragment for this long: the phone's camera or encoder died; StartVideo again. */
    static final long VIDEO_DEAD_MS = 3000;
    /** A size or codec switch was sent: the video watchdog waits this long for the phone's restart. */
    private static final long VIDEO_SWITCH_QUIET_MS = 5000;
    /**
     * The flight controller's state (FcInfo, the mode names the armed flag is read by) is kept this long into a
     * link loss: a short loss ends with the same FC, and the sticks' armed-resume rule needs the state. Longer, and
     * a different FC or a restarted phone app is likely enough to read it all again.
     */
    static final long FC_STATE_KEEP_MS = 60_000;
    /** Connect goes out this often while reconnecting: for RECONNECT_FAST_MS after a loss, then every CONNECT_MS. */
    static final long CONNECT_FAST_MS = 150, CONNECT_MS = 500, RECONNECT_FAST_MS = 5000;
    /** Config goes out this often until the phone has taken it. */
    static final long CONFIG_RETRY_MS = 250;
    /** The session thread's tick while connecting: a Connect answered is followed by the Config within this. */
    private static final long SESSION_TICK_MS = 50;

    private final PcConfig config;
    private final InetAddress remoteIp;
    private final int remotePort;
    private final int localPort;
    private final String key;
    private DatagramSocket socket;
    private UdpSender udpSender;
    private volatile ReceiverBuffer receiverBuffer;
    private volatile boolean running;
    private volatile boolean configReceived;
    private volatile long readyAt;
    /** The link has been READY once: from then on the sticks and pings go out whether or not the phone is heard. */
    private volatile boolean everReady;
    /** When the link was last lost (MonoClock.ms); 0 = never. */
    private volatile long lostAt;
    /** When the link last came back after a loss; 0 = never. */
    private volatile long reconnectedAt;
    /** Ask the phone for FcInfo and the mode names once more (the link came back: kept meanwhile, refreshed now). */
    private boolean refreshFc;
    /**
     * When that refresh was asked and not answered yet (0 = none open): asked again every FC_REFRESH_RETRY_MS. The
     * transport's acknowledgement doesn't promise the answer was used (a skipped gap is acknowledged too).
     */
    private volatile long fcRefreshAskedAt;
    static final long FC_REFRESH_RETRY_MS = 1500;
    /** When to look whether the video came back with the link (0 = not due). */
    private long videoCheckAt;
    /** The video watchdog is quiet until then (a switch was sent). */
    private long videoQuietUntil;
    /** When the last video fragment and the last telemetry packet came from the phone; 0 = none yet. */
    private volatile long lastVideoFragmentAt, lastTelemetryAt;
    /** Tailscale's way to the phone is a relay (DERP): the sticks ride TCP there, and AUTO holds bad coverage mode. */
    private volatile boolean relayed;
    private volatile long lastVideoConfigRequest;
    private volatile boolean versionMismatch;
    private volatile FcInfo fcInfo;
    private volatile int pingMs;
    private volatile long lastPingTimestamp;
    /** When anything last arrived from the phone (MonoClock.ms); 0 = nothing yet. */
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
    /** Bad coverage mode or the survival tier (see VideoMode, Tier). */
    private volatile Tier tier = Tier.NORMAL;
    private volatile VideoMode videoMode;
    private long lowRateSince = -1;
    private long survivalRateSince = -1;
    private long tierSince;
    private final java.util.ArrayDeque<Long> stalls = new java.util.ArrayDeque<>();
    private boolean inStall;
    private long robustSince;
    private long lastCalmEnd;
    private long robustHoldMs = ROBUST_HOLD_MS;
    private long leftRobustAt = -1;
    private long sharpHoldMs = SHARP_AFTER_MS;
    /** Since when the phone's bitrate has been under the normal top (-1: it isn't), for the sharp tier's end. */
    private long belowTopSince = -1;
    /** Since when this PC's decoder hasn't kept up with the sharp picture (decodeLoad over 1); -1 = it keeps up. */
    private long decodeSlowSince = -1;
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
    private volatile AudioListener audioListener;
    /** The phone's audio config (its AudioInitialFrame), or null: none came on this link. */
    private volatile AudioConfig audioConfig;
    /** When the last audio frame came (MonoClock.ms); 0 = none. */
    private volatile long lastAudioFrameAt;
    private final AudioRequest audioRequest = new AudioRequest();
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

    /** The live audio's listener (1.0.45); may be set while the link runs. */
    public void setAudioListener(AudioListener listener) {
        audioListener = listener;
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
        watchOnly(false);
    }

    /**
     * @param pilotPc the address this link was given is the pilot PC's (1.0.35: a PC picked by name), not the phone's:
     *                its stream is asked for straight (HELLO), without asking a phone who flies
     */
    public void watchOnly(boolean pilotPc) {
        watchOnly = true;
        watchPilotPc = pilotPc;
    }

    private boolean watchPilotPc;

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
        if (localPort == lastClosedPort) lastClosedPort = -1; // this port is a live session of this PC again
        try {
            socket.setReceiveBufferSize(UdpCommon.packetLength * 300);
            // room for a burst (config, requests, pings and a stick frame in the same moment): with one packet's
            // worth, the second packet in a tick is dropped by the socket, not delayed
            socket.setSendBufferSize(64 * 1024);
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
            spectator = new SpectatorClient(socket, remoteIp, remotePort, key, spectatorDelayMs,
                    watchPilotPc ? new Spectate.Address(remoteIp, remotePort) : null, this::playSpectated);
            if (watchPilotPc) spectator.pilotIsFixed();
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
                byte[] d = packet.getData();
                if (packet.getLength() > 0 && d[0] == SLEEP_HELLO) { // the sleeping phone's check: answered at once;
                    onSleepHello(d, packet.getLength());               // not a sign of the link, which is down on purpose
                    continue;
                }
                lastReceivedAt = MonoClock.ms();
                if (handleVideoFragment(packet)) continue;
                if (packet.getLength() > 0 && d[0] == UdpCommon.AudioFrame) { // unnumbered: the queue adds nothing
                    if (isConnected()) onAudioFrame(d, packet.getLength(), true);
                    continue;
                }
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

    /** The reply to another PC's NAME_QUERY (1.0.35): set by the main window, which knows this PC's name. */
    private volatile java.util.function.Supplier<byte[]> nameReply;

    public void setNameReply(java.util.function.Supplier<byte[]> reply) {
        nameReply = reply;
    }

    /** Not from the phone: a spectator asking this PC, (when this PC watches) the pilot PC, or a PC asking who this is. */
    /** Another phone's sleep check-in while this link holds the port: the handler's answer (MainWindow: sleep on). */
    private volatile java.util.function.BiFunction<String, byte[], Boolean> foreignSleepHandler;

    public void setForeignSleepHandler(java.util.function.BiFunction<String, byte[], Boolean> handler) {
        foreignSleepHandler = handler;
    }

    /** FLEET MANAGEMENT (1.0.52): a sleeping phone's check-in (address, hello) - its queued FLEET_MODE, or null. */
    private volatile java.util.function.BiFunction<String, byte[], byte[]> fleetHello;

    public void setFleetHello(java.util.function.BiFunction<String, byte[], byte[]> hook) {
        fleetHello = hook;
    }

    /**
     * 1.0.47: where a phone's overwatch packets go (OverwatchHub.offer: a copy into its queue, nothing more). They come
     * from the phone's own overwatch socket, never the link's port, so they arrive here as foreign packets.
     */
    private volatile OverwatchSink overwatchSink;

    public interface OverwatchSink {
        void offer(byte[] d, int len, java.net.InetAddress from, int port);
    }

    public void setOverwatchSink(OverwatchSink sink) {
        overwatchSink = sink;
    }

    private void onForeignPacket(DatagramPacket packet) {
        if (packet.getLength() < 1) return;
        if (de.droiddrone.common.OverwatchProtocol.isFromPhone(packet.getData(), packet.getLength())) {
            OverwatchSink sink = overwatchSink;
            if (sink != null) sink.offer(packet.getData(), packet.getLength(), packet.getAddress(), packet.getPort());
            return;
        }
        if (packet.getLength() >= 6 && packet.getData()[0] == SLEEP_HELLO) {
            java.util.function.BiFunction<String, byte[], Boolean> h = foreignSleepHandler;
            byte[] hello = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
            NameService.sendFleetMode(socket, packet, fleetHello, hello);
            Boolean wake = h == null ? null : h.apply(packet.getAddress().getHostAddress(), hello);
            if (wake != null) {
                try {
                    socket.send(new DatagramPacket(new byte[]{SLEEP_ANSWER, (byte) (wake ? 1 : 0)}, 2, packet.getAddress(), packet.getPort()));
                } catch (IOException ignored) {
                }
            }
            return;
        }
        if (packet.getData()[0] == Names.NAME_QUERY) {
            java.util.function.Supplier<byte[]> r = nameReply;
            if (r != null) NameService.answer(socket, packet, config.getKey(), r);
            return;
        }
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
        if (isThisPc(pilot)) {
            // the phone still holds this PC's previous session (seen 2026-11-02 after a link loss: it named this
            // very PC as the pilot, and this PC sat as a spectator of itself, never asking to connect again). Not
            // a pilot to watch: keep asking, a little faster, until the phone lets go
            long now = MonoClock.ms();
            if (now - ownSessionNamedLog >= 2000) {
                ownSessionNamedLog = now;
                events.accept("Phone still holds this PC's old session - reconnecting.");
            }
            ownSessionNamedAt = now;
            return;
        }
        if (c != null) {
            c.onPilotAt(pilot);
            return;
        }
        synchronized (this) {
            if (spectator != null || !running) return;
            spectator = new SpectatorClient(socket, remoteIp, remotePort, key, spectatorDelayMs, pilot, this::playSpectated);
        }
        events.accept("Another PC (" + pilot.ip().getHostAddress() + ") flies: spectating, "
                + spectatorDelayMs + " ms behind. No sticks sent.");
    }

    /** A session of this PC closed this recently may still be the phone's idea of the pilot. */
    static final long RECENT_SESSION_MS = 10_000;
    /** Connect goes out this often while the phone names this PC's old session as the pilot (500 ms otherwise). */
    static final long OWN_SESSION_RETRY_MS = 250;
    private volatile long ownSessionNamedAt, ownSessionNamedLog;
    /** This PC's addresses, read from the interfaces when first needed (the Tailscale 100.x one among them). */
    private volatile java.util.Set<InetAddress> ownAddresses;
    /** The last session's local port and when it closed, process-wide: a new link may be asked about the old one. */
    private static volatile int lastClosedPort = -1;
    private static volatile long lastClosedAt;

    /**
     * The pilot the phone names is this PC: one of its own addresses, at this link's port or at a session's that
     * closed within RECENT_SESSION_MS. Another PC on the same machine's address at another port (a second DRAUPNE
     * Link, as the tests run) is a pilot like any other.
     */
    boolean isThisPc(Spectate.Address pilot) {
        boolean ownPort = pilot.port() == localPort
                || pilot.port() == lastClosedPort && MonoClock.ms() - lastClosedAt <= RECENT_SESSION_MS;
        return ownPort && ownAddresses().contains(pilot.ip());
    }

    private java.util.Set<InetAddress> ownAddresses() {
        java.util.Set<InetAddress> own = ownAddresses;
        if (own != null) return own;
        own = new java.util.HashSet<>();
        try {
            for (java.net.NetworkInterface nif : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                own.addAll(java.util.Collections.list(nif.getInetAddresses()));
            }
        } catch (IOException e) {
            log("Own addresses unknown: " + e); // then only another PC can be the pilot, as before
        }
        own.add(InetAddress.getLoopbackAddress());
        ownAddresses = own;
        return own;
    }

    /** A packet the pilot PC passed on, played at its time (spectator thread). */
    private void playSpectated(byte[] d) {
        if (d.length < 1) return;
        if (d[0] == UdpCommon.VideoFrame || d[0] == UdpCommon.KeyFrame || d[0] == VIDEO_PARITY || d[0] == VIDEO_PARITY_GROUP) {
            deliverVideoFragment(d, d.length);
            return;
        }
        if (d[0] == UdpCommon.TelemetryData) telemetryPacketsReceived.incrementAndGet();
        lastReceivedAt = MonoClock.ms();
        if (d[0] == UdpCommon.AudioFrame) {
            onAudioFrame(d, d.length, false);
            return;
        }
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
        if (len < 1 || (d[0] != UdpCommon.VideoFrame && d[0] != UdpCommon.KeyFrame && d[0] != VIDEO_PARITY && d[0] != VIDEO_PARITY_GROUP)) return false;
        // shown whenever the stream's config is known: before 1.0.33 the fragments were thrown away while the link
        // counted as lost (2 s of silence), so a picture that was arriving again stayed black until the handshake
        if (!isConnected() && !videoConfigReceived) return true;
        lastVideoFragmentAt = MonoClock.ms();
        deliverVideoFragment(d, len);
        SpectatorHost h = host;
        if (h != null) h.forward(d, len); // after our own picture: a copy into a queue, nothing without spectators
        return true;
    }

    private void deliverVideoFragment(byte[] d, int len) {
        deliverVideo(videoListener, d, len);
    }

    /**
     * A video packet (fragment or parity) to a listener; the observation's links (1.0.48) read the phone's packets the
     * same way.
     */
    public static void deliverVideo(VideoListener listener, byte[] d, int len) {
        if (listener == null || len < 7) return;
        if (d[0] == VIDEO_PARITY) {
            if (len > 8) listener.onVideoParity(d[7] != 0, (short) (((d[1] & 0xff) << 8) | (d[2] & 0xff)), readInt(d, 3), d, 8, len - 8);
            return;
        }
        if (d[0] == VIDEO_PARITY_GROUP) {
            if (len > 9) listener.onVideoParityGroup(d[7] != 0, (short) (((d[1] & 0xff) << 8) | (d[2] & 0xff)), readInt(d, 3), d[8] & 0xff, d, 9, len - 9);
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

    /**
     * The phone's audio frame, [13][raw AAC frame] (upstream Flight Udp.sendAudioFrame): to the listener, and to the
     * spectators (a copy into their queue). Not played until the stream's config has come: it can't be decoded.
     */
    private void onAudioFrame(byte[] d, int len, boolean forward) {
        if (len < 2) return;
        lastAudioFrameAt = MonoClock.ms();
        AudioConfig c = audioConfig;
        AudioListener l = audioListener;
        if (c != null && l != null) l.onAudioFrame(c, java.util.Arrays.copyOfRange(d, 1, len), MonoClock.ms());
        SpectatorHost h = host;
        if (forward && h != null) h.forward(d, len);
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
                // also while the phone isn't heard, once the link has been up: on a one-way outage (the phone's
                // uplink dead, its downlink fine) the pings keep the phone's session alive for the sticks
                if (isConnected() || everReady && !dormant && spectator == null) udpSender.sendPing(true);
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
     * (if wanted) and ask for FC info. After a loss (1.0.33): Connect every CONNECT_FAST_MS, the Config the moment
     * the phone answers, the FC's state and the video config kept - the sticks never stopped (see sendRc).
     */
    private void sessionRun() {
        boolean wasConnected = false;
        long lastStartVideo = 0;
        long lastFcInfoRequest = 0;
        long lastModesRequest = 0;
        long lastConnect = 0;
        long lastConfig = 0;
        while (running) {
            try {
                if (spectator != null) { // watching: the spectator thread does the talking, never a Connect again
                    Thread.sleep(250);
                    continue;
                }
                long now = MonoClock.ms();
                boolean connected = isConnected();
                if (wasConnected && !connected) {
                    log("Link lost, reconnecting..." + (everReady ? " Sticks still sent." : ""));
                    lostAt = now;
                    configReceived = false;
                    // kept: the flight controller's state (for FC_STATE_KEEP_MS, see below) and the video config -
                    // the phone's stream usually never stopped, and asking for it again would rebuild its encoder
                }
                if (!wasConnected && connected) audioRequest.newSession(); // the audio request goes out at once
                if (!wasConnected && connected && lostAt > 0) {
                    log("Link back after " + (now - lostAt) + " ms.");
                    refreshFc = fcInfo != null;
                    reconnectedAt = now;
                    videoCheckAt = videoListener != null && videoConfigReceived ? now + VIDEO_CHECK_MS : 0;
                }
                wasConnected = connected;
                if (!connected && lostAt > 0 && fcInfo != null && now - lostAt > FC_STATE_KEEP_MS) {
                    log("No link for " + FC_STATE_KEEP_MS / 1000 + " s: FC state is re-read on reconnect.");
                    fcInfo = null;
                    telemetry.forgetBoxIds(); // a restarted phone app has to read them again
                    telemetry.forgetBoxNames(); // and a different FC has its modes on other bits: read them again too
                }
                if (versionMismatch) {
                    Thread.sleep(1000);
                } else if (!connected && dormant) {
                    Thread.sleep(250); // the phone sleeps on purpose: a Connect in its listening window would wake it
                } else if (!connected) {
                    boolean phoneHoldsOldSession = now - ownSessionNamedAt < RECENT_SESSION_MS;
                    long every = phoneHoldsOldSession ? OWN_SESSION_RETRY_MS
                            : lostAt > 0 && now - lostAt < RECONNECT_FAST_MS ? CONNECT_FAST_MS : CONNECT_MS;
                    if (now - lastConnect >= every) {
                        sendConnect();
                        lastConnect = now;
                    }
                    Thread.sleep(SESSION_TICK_MS);
                } else if (!configReceived) {
                    if (now - lastConfig >= CONFIG_RETRY_MS) {
                        decideForcedTier(now); // ROBUST set: the first config already carries the small size
                        sendProfile(); // the phone keeps its profile across sessions (pc.26): told every time, normal too
                        sendConfig();
                        lastConfig = now;
                    }
                    Thread.sleep(SESSION_TICK_MS);
                } else {
                    boolean refreshUnanswered = fcRefreshAskedAt > 0 && now - fcRefreshAskedAt >= FC_REFRESH_RETRY_MS;
                    if (refreshFc || refreshUnanswered) { // the same FC, most likely: its state was kept, and is asked for once more
                        refreshFc = false;
                        fcRefreshAskedAt = now;
                        sendRequest(UdpCommon.FcInfo);
                        sendRequest(UdpCommon.BoxNames);
                        lastFcInfoRequest = lastModesRequest = now;
                    }
                    if (videoListener != null) {
                        if (videoCheckAt > 0 && now >= videoCheckAt) {
                            videoCheckAt = 0;
                            if (lastVideoFragmentAt < reconnectedAt) {
                                log("No video since link came back: requesting stream.");
                                videoConfigReceived = false;
                            }
                        }
                        boolean telemetryFlows = now - lastTelemetryAt < 1000;
                        // the phone's own watchdog is reopening its camera (1.5-2.6 s): a StartVideo now would only
                        // restart that open, so this one waits for it
                        FcTelemetry.PhoneState ps = telemetry.getPhoneState();
                        boolean phoneRestarting = ps != null && now - ps.receivedAt() < 1000 && ps.videoRestarting();
                        boolean videoDead = videoConfigReceived && telemetryFlows && now - lastVideoFragmentAt > VIDEO_DEAD_MS
                                && now >= videoQuietUntil && !phoneRestarting;
                        if (!videoConfigReceived && now - lastStartVideo >= START_VIDEO_RETRY_MS) {
                            sendStartVideo();
                            lastStartVideo = now;
                        } else if (videoDead && now - lastStartVideo >= START_VIDEO_RETRY_MS) {
                            log("Telemetry but no video for " + (now - lastVideoFragmentAt) + " ms: requesting stream.");
                            sendStartVideo();
                            lastStartVideo = now;
                        }
                    }
                    adjustResolution(now);
                    sendAudioRequest(now);
                    sendDetectionIfDue(now);
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

    /** The Connect, sent on this thread (upstream's sendConnect starts a thread per call). */
    private void sendConnect() throws IOException {
        UdpPacketData packetData = new UdpPacketData(UdpCommon.Connect, -1);
        packetData.daos.writeByte(1);
        packetData.daos.writeUTF(key);
        packetData.daos.writeShort(SettingsCommon.versionCompatibleCode);
        udpSender.sendPacket(packetData.getData());
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
                    pingMs = (int) (UdpSender.pingClockMs() - time); // 1.0.49: UdpSender's monotonic stamp, not the wall clock
                    lastPingTimestamp = MonoClock.ms();
                    maxPingMs.accumulateAndGet(pingMs, Math::max);
                }
            }
            case UdpCommon.ConfigReceived -> {
                if (!configReceived) readyAt = MonoClock.ms();
                configReceived = true;
                everReady = true;
            }
            case UdpCommon.VersionMismatch -> {
                configReceived = false;
                versionMismatch = true;
            }
            case UdpCommon.FcInfo -> {
                fcRefreshAskedAt = 0;
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
                lastTelemetryAt = MonoClock.ms();
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
            case UdpCommon.AudioInitialFrame -> {
                AudioConfig c = AudioConfig.parse(data, 3, data.length - 3);
                if (c == null) return;
                if (!c.sameAs(audioConfig)) log("Live audio: AAC " + c.sampleRate() + " Hz, " + (c.channels() == 1 ? "mono" : c.channels() + " channels"));
                audioConfig = c;
                AudioListener l = audioListener;
                if (l != null) l.onAudioConfig(c);
                SpectatorHost h = host;
                if (h != null) {
                    h.rememberAudioConfig(data);
                    h.forward(data, data.length);
                }
            }
            default -> {
                // the phone's OSD packets are not used: this PC draws its OSD from the telemetry
            }
        }
    }

    /**
     * The live audio's request to the phone (AUDIO, PcAudio; phone app pc.34 on): on unless muted for 10 s
     * (AudioRequest), at every change and every second. Unnumbered and straight out, like the video profile: the next
     * one follows within a second. Not to an older phone app, which would drop it anyway.
     */
    private void sendAudioRequest(long now) {
        if (spectator != null || telemetry.getPhonePcBuild() < AUDIO_MIN_PC_BUILD) return;
        boolean missingConfig = audioConfig == null && lastAudioFrameAt != 0 && now - lastAudioFrameAt < 1000;
        byte[] p = audioRequest.due(now, missingConfig);
        if (p == null) return;
        try {
            socket.send(new DatagramPacket(p, p.length, remoteIp, remotePort));
        } catch (IOException e) {
            // sent again within a second
        }
    }

    /**
     * MUTE (1.0.45): the caller silences this PC's speakers at once (LiveAudio); the phone is asked to stop sending
     * after AudioRequest.STOP_AFTER_MUTED_MS muted, and to start again at the unmute.
     */
    public void setAudioMuted(boolean muted) {
        audioRequest.setMuted(muted, MonoClock.ms());
    }

    /** The live audio now (see AudioState). */
    public AudioState audioState() {
        long now = MonoClock.ms();
        boolean flowing = lastAudioFrameAt != 0 && now - lastAudioFrameAt < 1000;
        if (spectator != null) return flowing ? AudioState.ON : AudioState.NONE;
        if (getState() != State.READY) return AudioState.NONE;
        int build = telemetry.getPhonePcBuild();
        if (build < 0) return AudioState.WAITING;
        if (build < AUDIO_MIN_PC_BUILD) return AudioState.OLD_PHONE;
        if (!audioRequest.streamWanted(now)) return AudioState.STOPPED;
        if (flowing) return AudioState.ON;
        FcTelemetry.PhoneState ps = telemetry.getPhoneState();
        if (ps != null && now - ps.receivedAt() < 1000) {
            if (ps.audioHeld()) return AudioState.HELD;
            if (ps.audioFailed()) return AudioState.FAILED;
        }
        return AudioState.WAITING;
    }

    private void sendRequest(byte packetName) throws IOException {
        udpSender.sendPacket(new UdpPacketData(packetName).getData());
    }

    private void sendStartVideo() throws IOException {
        UdpPacketData packetData = new UdpPacketData(UdpCommon.StartVideo);
        packetData.daos.writeBoolean(wantHevc());
        udpSender.sendPacket(packetData.getData());
    }

    /** H.265 as set, but H.264 in bad coverage mode and under (see PROFILE_AVC). */
    private boolean wantHevc() {
        return videoHevc && !isRobust();
    }

    /**
     * Asks the phone for the stream's config again (GetVideoConfig; it answers from its cache, nothing restarts):
     * a stream (re)started but its VideoInitialFrame never came, or frames don't decode under the config in hand.
     * At most once a second; not while watching.
     */
    public void requestVideoConfig() {
        long now = MonoClock.ms();
        if (!running || spectator != null || !isConnected() || now - lastVideoConfigRequest < 1000) return;
        lastVideoConfigRequest = now;
        try {
            sendRequest(UdpCommon.GetVideoConfig);
        } catch (IOException e) {
            // asked again with the next frame that needs it
        }
    }

    /**
     * Tailscale's way to the phone, from the main window's checks ("direct", "relay fra", ...): over a relay the
     * sticks ride TCP (one lost segment holds every frame behind it), so AUTO holds bad coverage mode there.
     */
    public void setLinkPath(String path) {
        relayed = path != null && path.startsWith("relay");
    }

    /** The way to the phone goes over a Tailscale relay now (RELAY on the OSD). */
    public boolean isRelayed() {
        return relayed;
    }

    /**
     * The phone's power mode (phone app pc.21 on; older apps drop the packet): [0x6E][mode u8][checkIntervalMin u8].
     * Mode 0 = normal, 1 = STANDBY (no video, GPS and telemetry slowed, screen dimmed; the link stays), 2 = DEEP
     * SLEEP (the phone drops the link, sleeps, and listens for this PC's Connect for 20 s every checkIntervalMin
     * minutes). The phone answers with its PHONE_POWER record; the caller repeats this until it does. checkIntervalMin
     * SleepCheck.ALWAYS (1.0.50, CHECK EVERY "0 min") is a window that never closes; it goes only to pc.39 on - an older
     * app is sent 1 min instead (sendPowerMode and sendOverwatch see to it).
     */
    public static final byte POWER_MODE = 0x6E;
    public static final int POWER_NORMAL = 0, POWER_STANDBY = 1, POWER_DEEP_SLEEP = 2;
    /**
     * 1.0.47 (phone app pc.36 on): OVERWATCH SLEEP - deep sleep with the camera and the motion detection on; a
     * detection calls this PC (OverwatchHub). The request carries more: OverwatchProtocol.request.
     */
    public static final int POWER_OVERWATCH = de.droiddrone.common.OverwatchProtocol.MODE;

    /**
     * A remote update of the phone app (phone app pc.32 on; an older one drops it): [0x77][version UTF][APK url UTF]
     * [its SHA-256 UTF], unnumbered; the caller repeats it until the phone's PHONE_UPDATE record answers. The phone
     * takes it only for a newer version and with the flight controller known to be disarmed.
     */
    public static final byte REMOTE_UPDATE = 0x77;

    public void sendRemoteUpdate(String version, String url, String sha256) {
        if (!running || !isConnected() || spectator != null) return;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            out.writeByte(REMOTE_UPDATE);
            out.writeUTF(version);
            out.writeUTF(url);
            out.writeUTF(sha256);
            udpSender.sendPacket(bytes.toByteArray());
        } catch (IOException e) {
            log("sendRemoteUpdate error: " + e);
        }
    }

    public void sendPowerMode(int mode, int checkIntervalMin) {
        if (!running || !isConnected() || spectator != null) return;
        udpSender.sendPacket(new byte[]{POWER_MODE, (byte) mode, (byte) intervalForPhone(checkIntervalMin)});
    }

    /** The check interval as this phone app can take it: SleepCheck.ALWAYS only to pc.39 on (an older one would read 255 min). */
    int intervalForPhone(int checkIntervalMin) {
        return de.droiddrone.common.SleepCheck.forPhone(checkIntervalMin, telemetry.getPhonePcBuild());
    }

    /**
     * OVERWATCH (1.0.47): [0x6E][3][checkIntervalMin u8][sensitivity u8][flags u8][token u32] - only to a phone of
     * pc.36 or newer (an older one says "no such mode" and stays as it is).
     */
    public void sendOverwatch(int checkIntervalMin, int sensitivity, int flags, int token) {
        if (!running || !isConnected() || spectator != null) return;
        udpSender.sendPacket(de.droiddrone.common.OverwatchProtocol.request(POWER_MODE, intervalForPhone(checkIntervalMin), sensitivity, flags, token));
    }

    /**
     * Dormant: the phone is in deep sleep and must not be woken - no Connect goes out while the link is down. WAKE
     * clears it, and the Connects resume at once (the phone takes one in its next listening window).
     */
    private volatile boolean dormant;

    public void setDormant(boolean dormant) {
        this.dormant = dormant;
        if (dormant) log("Phone in DEEP SLEEP: quiet until it is woken.");
    }

    public boolean isDormant() {
        return dormant;
    }

    /** After STANDBY: the phone stopped its stream, so StartVideo goes out again as if the stream never started. */
    public void restartVideo() {
        videoConfigReceived = false;
    }

    /**
     * The sleeping phone's "still here" from each check window (phone app pc.22 on): [0x6F][battery % u8, 255 =
     * unknown][charging u8][nextCheckMin u8][mV u16]. This PC answers [0x70][wake u8] at once: 0 while dormant (the
     * phone closes its window and sleeps on, saving the 20 s of listening), 1 after WAKE (it wakes fully).
     */
    public static final byte SLEEP_HELLO = 0x6F, SLEEP_ANSWER = 0x70;

    /**
     * owFlags (pc.36 on; 0 before): OverwatchProtocol.HELLO_OVERWATCH - it overwatches, HELLO_EVENT - an event runs;
     * floorReason: why it left overwatch for deep sleep by itself (PowerFloor.REASON_*, 0 none).
     */
    public record SleepHello(int batteryPercent, boolean charging, int nextCheckMin, int milliVolts, long receivedAt,
                             int owFlags, int floorReason, de.droiddrone.common.FleetProtocol.Status status) {
        public SleepHello(int batteryPercent, boolean charging, int nextCheckMin, int milliVolts, long receivedAt) {
            this(batteryPercent, charging, nextCheckMin, milliVolts, receivedAt, 0, 0, null);
        }

        public boolean overwatch() {
            return (owFlags & de.droiddrone.common.OverwatchProtocol.HELLO_OVERWATCH) != 0;
        }

        /**
         * The hello's bytes: [0x6F][battery][charging][nextCheckMin][mV u16], then (pc.36) [owFlags][floorReason], then
         * (pc.40) the fleet status block (FleetProtocol.Status; null without it).
         */
        public static SleepHello read(byte[] d, int len, long receivedAt) {
            int pct = d[1] & 0xff;
            return new SleepHello(pct == 255 ? -1 : pct, d[2] != 0, d[3] & 0xff, ((d[4] & 0xff) << 8) | (d[5] & 0xff), receivedAt,
                    len >= 7 ? d[6] & 0xff : 0, len >= 8 ? d[7] & 0xff : 0, de.droiddrone.common.FleetProtocol.Status.read(d, 8, len));
        }
    }

    private volatile SleepHello sleepHello;

    /** The latest "still here" from the sleeping phone, or null. */
    public SleepHello getSleepHello() {
        return sleepHello;
    }

    private void onSleepHello(byte[] d, int len) {
        if (len < 6) return;
        sleepHello = SleepHello.read(d, len, MonoClock.ms());
        java.util.function.BiFunction<String, byte[], byte[]> f = fleetHello;
        byte[] mode = f == null || remoteIp == null ? null : f.apply(remoteIp.getHostAddress(), java.util.Arrays.copyOf(d, len));
        if (mode != null) udpSender.sendPacket(mode); // a fleet mode queued for this dormant phone: ahead of the answer
        udpSender.sendPacket(new byte[]{SLEEP_ANSWER, (byte) (dormant ? 0 : 1)});
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

    /**
     * Sets the phone's bitrate step outright (SET_BITRATE, phone app pc.26 on): straight to the floor when the uplink
     * queue fills, instead of one step a second. An older phone app gets one relative step towards it.
     * @param from the step the PC counts the phone on now
     */
    public void sendBitrateStep(int step, int from) {
        if (!running || !isConnected() || spectator != null || step == from) return;
        if (telemetry.getPhonePcBuild() < PC26_BUILD) {
            sendChangeBitRate(step > from);
            return;
        }
        try {
            socket.send(new DatagramPacket(new byte[]{SET_BITRATE, (byte) step}, 2, remoteIp, remotePort));
        } catch (IOException e) {
            log("sendBitrateStep error: " + e);
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
            packetData.daos.writeShort(cameraFps()); // min = max: steady
            packetData.daos.writeShort(cameraFps());
            packetData.daos.writeBoolean(SettingsCommon.useUsbCamera);
            packetData.daos.writeByte(SettingsCommon.usbCameraFrameFormat);
            packetData.daos.writeBoolean(SettingsCommon.usbCameraReset);
        }
        // video
        packetData.daos.writeByte(bitrateLimitMbps()); // the tier's: the phone allows no step over it
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

    /** The camera width asked for: the sharp tier's, as set, the bad coverage size, or the survival tier's. */
    int cameraWidth() {
        return switch (tier) {
            case SHARP -> config.getSharpWidth();
            case NORMAL -> config.getCameraWidth();
            case ROBUST -> Math.min(ROBUST_WIDTH, config.getCameraWidth() * 3 / 4) / 2 * 2;
            case SURVIVAL -> Math.min(SURVIVAL_WIDTH, config.getCameraWidth() / 2) / 2 * 2;
        };
    }

    int cameraHeight() {
        return switch (tier) {
            case SHARP -> config.getSharpHeight();
            case NORMAL -> config.getCameraHeight();
            case ROBUST -> Math.min(ROBUST_HEIGHT, config.getCameraHeight() * 3 / 4) / 2 * 2;
            case SURVIVAL -> Math.min(SURVIVAL_HEIGHT, config.getCameraHeight() / 2) / 2 * 2;
        };
    }

    /** The camera's frame rate asked for: steady 30, 15 in the survival tier (twice the bits per frame). */
    int cameraFps() {
        return tier == Tier.SURVIVAL ? SURVIVAL_FPS : CAMERA_FPS;
    }

    /** SETTINGS > Video, also while connected. */
    public void setVideoMode(VideoMode mode) {
        videoMode = mode;
    }

    private VideoMode videoMode() {
        VideoMode m = videoMode;
        return m != null ? m : VideoMode.parse(config.getString(VIDEO_MODE_KEY));
    }

    /**
     * The highest bitrate step now: 1 Mbit/s in bad coverage mode, 250 kbit/s in the survival tier, the sharp limit
     * in the sharp tier, else the set limit.
     */
    public int maxBitrateStep() {
        if (tier == Tier.SURVIVAL) return SURVIVAL_MAX_STEP;
        if (tier == Tier.ROBUST) return ROBUST_MAX_STEP;
        return stepFor(bitrateLimitMbps());
    }

    /** The bitrate limit the tier allows, Mbit/s: the sharp tier's or the set one (the config packet carries it). */
    int bitrateLimitMbps() {
        return tier == Tier.SHARP ? config.getSharpBitrateLimitMbps() : config.getBitrateLimitMbps();
    }

    /** The highest step at or under a limit in Mbit/s. */
    static int stepFor(int limitMbps) {
        int limit = limitMbps * 1_000_000;
        int[] steps = de.droiddrone.pc.video.BitrateControl.STEPS_BPS;
        int s = 0;
        while (s < steps.length - 1 && steps[s + 1] <= limit) s++;
        return s;
    }

    /** The settings give the sharp tier something over the normal picture: a bigger size or a higher limit. */
    private boolean sharpAvailable() {
        return config.getSharpWidth() > config.getCameraWidth() || config.getSharpBitrateLimitMbps() > config.getBitrateLimitMbps();
    }

    /** Tests: AUTO's wait before the sharp tier. */
    void setSharpAfterMsForTest(long ms) {
        sharpHoldMs = ms;
    }

    /**
     * The tier (see VideoMode, Tier): the size and frame rate go out as a config (the phone changes them on the open
     * camera, phone app 2.3.0-pc.15), the profile as a packet of its own; both sent again until the phone's stream
     * shows it.
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
            boolean switched = videoWidth > 0 && switch (tier) {
                case SHARP -> videoWidth >= config.getSharpWidth();
                case NORMAL -> videoWidth >= config.getCameraWidth()
                        && (config.getSharpWidth() <= config.getCameraWidth() || videoWidth < config.getSharpWidth());
                case ROBUST -> videoWidth < config.getCameraWidth();
                case SURVIVAL -> videoWidth <= Math.min(ROBUST_WIDTH, config.getCameraWidth() * 3 / 4); // or the nearest size it has
            };
            if (switched || verifyTries >= VERIFY_TRIES) {
                if (!switched) events.accept("Video: phone kept the picture size (" + videoWidth + " wide).");
                verifyFrom = -1;
            } else {
                verifyTries++;
                verifyFrom = now + VERIFY_AFTER_MS;
                log("Video size not applied yet: resending (" + verifyTries + ")");
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
        survivalRateSince = bps > 0 && bps <= SURVIVAL_BPS ? (survivalRateSince < 0 ? now : survivalRateSince) : -1;
        VideoMode mode = videoMode();
        int normalTopBps = de.droiddrone.pc.video.BitrateControl.STEPS_BPS[stepFor(config.getBitrateLimitMbps())];
        belowTopSince = bps > 0 && bps < normalTopBps ? (belowTopSince < 0 ? now : belowTopSince) : -1;
        Tier want;
        String why;
        Tier forced = mode.forced();
        if (forced != null) { // a tier chosen: held, whatever the link
            want = forced;
            why = "VIDEO is " + mode.name();
        } else if (tier == Tier.SHARP) {
            // the sharp tier holds while the link carries it: a stall, a relay, or the bitrate under the normal top for
            // SHARP_DROP_MS (the bitrate control had to step down) ends it - back to the normal picture first
            boolean cantCarry = belowTopSince >= 0 && now - belowTopSince >= SHARP_DROP_MS;
            // or this PC can't decode 1080p in real time (software decoding): the picture would drift late
            VideoListener v = videoListener;
            boolean slow = v != null && v.decodeLoad() > 1.0;
            if (!slow) decodeSlowSince = -1;
            else if (decodeSlowSince < 0) decodeSlowSince = now;
            boolean cantDecode = decodeSlowSince >= 0 && now - decodeSlowSince >= SHARP_DROP_MS;
            boolean watched = watched();
            want = !stalls.isEmpty() || relayed || cantCarry || cantDecode || watched ? Tier.NORMAL : Tier.SHARP;
            why = !stalls.isEmpty() ? "a stall" : relayed ? "relayed"
                    : watched ? "a spectator: upload carries the picture"
                    : cantDecode ? "decoder too slow"
                    : "under " + normalTopBps / 1_000_000 + " Mbit/s for " + SHARP_DROP_MS / 1000 + " s";
        } else if (tier == Tier.NORMAL) {
            boolean lowRate = lowRateSince >= 0 && now - lowRateSince >= LOW_RES_AFTER_MS;
            boolean stalls3 = stalls.size() >= STALLS_FOR_ROBUST;
            if (lowRate || stalls3 || relayed) {
                want = Tier.ROBUST;
                why = lowRate ? "bitrate at " + bps / 1000 + " kbit/s"
                        : stalls3 ? stalls.size() + " stalls in " + STALLS_WINDOW_MS / 1000 + " s" : "relayed";
                if (leftRobustAt >= 0 && now - leftRobustAt < 60_000) {
                    robustHoldMs = Math.min(MAX_ROBUST_HOLD_MS, robustHoldMs * 2); // back too soon: stay longer next time
                }
            } else {
                // calm at the normal top bitrate for sharpHoldMs, no stall in that time: the sharp tier (a phone that
                // reports no bitrate setting never gets there)
                boolean atTop = bps >= normalTopBps;
                boolean calm = now - tierSince >= sharpHoldMs && now - lastCalmEnd >= sharpHoldMs && stalls.isEmpty();
                want = sharpAvailable() && atTop && calm && !watched() ? Tier.SHARP : Tier.NORMAL;
                why = "calm at " + normalTopBps / 1_000_000 + " Mbit/s for " + sharpHoldMs / 1000 + " s";
            }
        } else if (tier == Tier.ROBUST) {
            // bad coverage mode isn't enough: the bitrate sits at the bottom or the stalls go on - the survival tier
            boolean lowRate = survivalRateSince >= 0 && now - survivalRateSince >= LOW_RES_AFTER_MS;
            boolean stalls3 = stalls.size() >= STALLS_FOR_ROBUST;
            boolean worse = now - tierSince >= SURVIVAL_AFTER_MS && (lowRate || stalls3);
            if (worse) {
                want = Tier.SURVIVAL;
                why = lowRate ? "still " + bps / 1000 + " kbit/s" : "still " + stalls.size() + " stalls";
                if (leftRobustAt >= 0 && now - leftRobustAt < 60_000) robustHoldMs = Math.min(MAX_ROBUST_HOLD_MS, robustHoldMs * 2);
            } else if (relayed) {
                want = Tier.ROBUST;
                why = "relayed";
            } else {
                // calm long enough in bad coverage mode: no stall, and the bitrate not struggling under its 1 Mbit/s
                boolean calm = now - tierSince >= robustHoldMs && now - lastCalmEnd >= robustHoldMs
                        && (bps <= 0 || bps > LOW_RES_BPS);
                want = calm ? Tier.NORMAL : Tier.ROBUST;
                why = "calm for " + robustHoldMs / 1000 + " s";
            }
        } else {
            boolean calm = now - tierSince >= robustHoldMs && now - lastCalmEnd >= robustHoldMs;
            want = calm ? Tier.ROBUST : Tier.SURVIVAL;
            why = "calm for " + robustHoldMs / 1000 + " s";
        }
        if (want == tier) return;
        Tier was = tier;
        long held = now - tierSince;
        tier = want;
        tierSince = now;
        belowTopSince = -1;
        if (want.ordinal() > was.ordinal()) { // down
            stalls.clear();
            if (was == Tier.SHARP && forced == null) { // left within a minute: the next sharp spell waits longer; a spell that lasted: the usual wait
                sharpHoldMs = held < 60_000 ? Math.min(MAX_ROBUST_HOLD_MS, sharpHoldMs * 2) : SHARP_AFTER_MS;
            }
        } else if (was.ordinal() >= Tier.ROBUST.ordinal()) {
            leftRobustAt = now;
        }
        events.accept(tierMessage(want, was, why));
        sendTier(now);
    }

    /** What the log says when the tier changes. */
    private String tierMessage(Tier want, Tier was, String why) {
        return switch (want) {
            case SHARP -> "Video: sharp (" + why + "): " + cameraWidth() + "x" + cameraHeight() + ", max "
                    + config.getSharpBitrateLimitMbps() + " Mbit/s.";
            case ROBUST -> was.ordinal() < Tier.ROBUST.ordinal()
                    ? "Video: bad coverage mode (" + why + "): " + cameraWidth() + "x" + cameraHeight()
                            + ", max 1 Mbit/s, key frames doubled."
                    : "Video: bad coverage mode again (" + why + "): " + cameraWidth() + "x" + cameraHeight() + ".";
            case SURVIVAL -> "Video: survival tier (" + why + "): " + cameraWidth() + "x" + cameraHeight() + " at " + cameraFps()
                    + " fps, max 250 kbit/s.";
            case NORMAL -> was == Tier.SHARP
                    ? "Video: normal (" + why + "): " + cameraWidth() + "x" + cameraHeight() + ", max "
                            + config.getBitrateLimitMbps() + " Mbit/s."
                    : "Video: normal mode again (" + why + "): " + cameraWidth() + "x" + cameraHeight() + ".";
        };
    }

    /**
     * VIDEO is a tier of its own (SHARP, NORMAL, ROBUST, SURVIVAL): decided before the first config goes out, so it already carries the size.
     * Before 1.0.33 the first config said 1280x720 and the small size came while the phone's camera was still
     * opening - which the phone took as done; every ROBUST session then ran at the full size. The caller sends the
     * profile for the tier right after this, before the config.
     */
    private void decideForcedTier(long now) {
        // a session (re)starts: nothing calm about the link has been seen yet (1.0.53: the calm counted from 0, and a
        // phone already at the normal top went sharp on the first tick)
        lastCalmEnd = now;
        VideoMode mode = videoMode();
        Tier forced = mode.forced();
        if (forced != null && tier != forced) {
            Tier was = tier;
            tier = forced;
            tierSince = now;
            events.accept(tierMessage(forced, was, "VIDEO is " + mode.name()));
            if (isRobust()) { // its profile (key frames twice, parity) with the first config
                profileSends = 2;
                nextProfileSend = now + 700;
            }
            verifyFrom = videoListener == null ? -1 : now + VERIFY_AFTER_MS;
            verifyTries = 0;
        } else if (forced == null && tier == Tier.SHARP) {
            tier = Tier.NORMAL; // a new session starts on the normal picture and earns the sharp tier again
            tierSince = now;
        }
    }

    /** The tier's size, frame rate and profile to the phone, again until its stream shows the change. */
    private void sendTier(long now) throws IOException {
        configResends = 1;
        nextConfigResend = now + 1000;
        profileSends = 2;
        nextProfileSend = now + 700;
        verifyFrom = videoListener == null ? -1 : now + VERIFY_AFTER_MS; // without video there is nothing to check
        verifyTries = 0;
        videoQuietUntil = now + VIDEO_SWITCH_QUIET_MS; // the phone restarts its encoder for it: no watchdog meanwhile
        sendProfile(); // first: the phone's restart for the config reads the codec from it
        sendConfig();
    }

    /**
     * The phone's video profile (phone app 2.3.0-pc.15 on; older ones drop it): in bad coverage mode and under, key
     * frames twice, a parity per group of packets and H.264 (pc.26; older apps read bit 0 only). Sent with the first config
     * of every session, the normal profile too (1.0.34): the phone keeps the flags of the session before, so a phone left
     * in bad coverage mode would send key frames twice, parity and H.264 to a PC that takes it for normal.
     */
    private void sendProfile() {
        try {
            socket.send(new DatagramPacket(new byte[]{VIDEO_PROFILE, (byte) profileFlags()}, 2, remoteIp, remotePort));
        } catch (IOException e) {
            // sent again with the next round
        }
    }

    int profileFlags() {
        if (!isRobust()) return 0;
        return PROFILE_KEY_FRAMES_TWICE | PROFILE_GROUP_PARITY | (videoHevc ? PROFILE_AVC : 0);
    }

    /** Bad coverage mode (or the survival tier) is on. */
    public boolean isRobust() {
        return tier.ordinal() >= Tier.ROBUST.ordinal();
    }

    public Tier getTier() {
        return tier;
    }

    /**
     * Asks the phone for a key frame now (phone app 2.3.0-pc.13 on; older ones drop the packet): a frame came
     * damaged or lost, a recording or a spectator starts. At most every KEY_FRAME_REQUEST_MS; not while watching.
     */
    public void requestKeyFrame() {
        long now = MonoClock.ms();
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
     * <p>
     * Once the link has been up, frames go out whether or not the phone is heard (1.0.33): on 4G the phone's uplink
     * is the weak direction, and a drone whose sticks still arrive is a drone under control while its video and
     * telemetry are gone. The phone takes them as long as it has a session for this PC (its own 4 s timer), and the
     * Connects that reconnect it go out meanwhile. Before, the sticks stopped 2 s into every silence from the phone.
     * @return true if a frame was sent (false while not connected/configured)
     */
    public boolean sendRc(short[] channels) {
        if (channels.length < 4 || channels.length > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT) {
            throw new IllegalArgumentException("RC channel count must be 4.." + FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT);
        }
        if (!sticksWanted() || !(everReady || configReceived && isConnected())) return false;
        short[] frame = padForRxMap(channels);
        try {
            int build = telemetry.getPhonePcBuild();
            byte[] data = build >= RC_LAG_MIN_PC_BUILD ? rcFrame(frame, rcSeq, (int) MonoClock.ms(), maxStickLagMs)
                    : rcFrame(frame, build >= RC_SEQ_MIN_PC_BUILD ? rcSeq : -1, build >= PC26_BUILD);
            rcSeq = (rcSeq + 1) & 0xffff;
            // straight out, once: upstream's sendPacket retries ten times and swallows the error, so a dead adapter
            // looked like frames sent - and a frame late is a frame wrong
            socket.send(new DatagramPacket(data, data.length, remoteIp, remotePort));
            rcFramesSent.incrementAndGet();
            long now = System.nanoTime();
            if (lastRcNs != 0) maxRcGapNs.accumulateAndGet(now - lastRcNs, Math::max);
            lastRcNs = now;
            return true;
        } catch (IOException e) {
            long now = MonoClock.ms();
            if (now - lastRcErrorLog >= 1000) { // 250 a second otherwise
                lastRcErrorLog = now;
                log("sendRc error: " + e);
            }
            return false;
        }
    }

    /**
     * The stick frame as upstream lays it out, [24][count u8][count x int16], plus one byte DRAUPNE Flight 2.3.0-pc.19
     * and newer read: a sequence number (0-255, wrapping) that lets the phone drop a frame overtaken by a newer one on
     * the way - over 4G frames arrive bunched and out of order, and a frame late is a frame wrong. Older phone apps
     * refuse a frame with a byte too many, so they get the old layout (seq &lt; 0). Phone app pc.26 and newer read
     * two bytes (0-65535): with one, a frame 128 or more behind looked newer (a cell handover flushing old packets),
     * and the fresh frames after it were dropped until the count passed it - up to half a second without sticks.
     */
    static byte[] rcFrame(short[] frame, int seq, boolean wide) throws IOException {
        UdpPacketData packetData = new UdpPacketData(UdpCommon.RcFrame);
        packetData.daos.writeByte(frame.length);
        for (short channel : frame) packetData.daos.writeShort(channel);
        if (seq >= 0 && wide) packetData.daos.writeShort(seq);
        else if (seq >= 0) packetData.daos.writeByte(seq);
        return packetData.getData();
    }

    static byte[] rcFrame(short[] frame, int seq) throws IOException {
        return rcFrame(frame, seq, false);
    }

    /**
     * Phone app pc.31 on: the 16-bit sequence frame plus [sentMs u16][maxLagCs u8] - this PC's monotonic ms (low 16
     * bits) when the frame left, and the lateness past which the phone drops it, in 10 ms steps (0 = never). The phone
     * measures each frame's delay over the quickest of the last few seconds (the clocks needn't agree: only the
     * difference counts) and reports it (FcTelemetry.PHONE_RC_LAG). A frame seconds late is a command for a drone
     * that has moved on; with a limit set, Betaflight's failsafe takes over instead.
     */
    static byte[] rcFrame(short[] frame, int seq, int sentMs, int maxLagMs) throws IOException {
        UdpPacketData packetData = new UdpPacketData(UdpCommon.RcFrame);
        packetData.daos.writeByte(frame.length);
        for (short channel : frame) packetData.daos.writeShort(channel);
        packetData.daos.writeShort(seq);
        packetData.daos.writeShort(sentMs & 0xffff);
        packetData.daos.writeByte(Math.max(0, Math.min(255, (maxLagMs + 9) / 10)));
        return packetData.getData();
    }

    /**
     * The idle sticks (1.0.44, phone app pc.33 on; an older one drops it): [0x78][count u8][count x int16], unnumbered,
     * again each second (RcSession). The phone keeps the last ones while it runs and feeds them to Betaflight
     * only while it knows the drone DISARMED, from a status read after its last stick frame from the PC, and no frame
     * has come for a moment - so a disarmed drone on the phone never sees its receiver lost, and beeps only when the
     * phone's USB is out. Never while armed: a link lost then is Betaflight's failsafe, as before.
     */
    public static final byte IDLE_STICKS = 0x78;
    /** The first phone app build that takes the idle sticks. */
    static final int IDLE_STICKS_MIN_PC_BUILD = 33;

    /** @return true if sent (a phone app that takes them, the link up) */
    public boolean sendIdleSticks(short[] channels) {
        if (channels.length < 4 || channels.length > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT) return false;
        if (!sticksWanted() || !(everReady || configReceived && isConnected())) return false;
        if (telemetry.getPhonePcBuild() < IDLE_STICKS_MIN_PC_BUILD) return false;
        try {
            byte[] data = idleSticksFrame(padForRxMap(channels));
            socket.send(new DatagramPacket(data, data.length, remoteIp, remotePort));
            return true;
        } catch (IOException e) {
            return false; // the stick frames log a dead adapter already
        }
    }

    static byte[] idleSticksFrame(short[] frame) throws IOException {
        UdpPacketData packetData = new UdpPacketData(IDLE_STICKS);
        packetData.daos.writeByte(frame.length);
        for (short channel : frame) packetData.daos.writeShort(channel);
        return packetData.getData();
    }

    /**
     * The phone's motion detection (1.0.46, phone app pc.35 on; an older one drops it): DETECTION_SET [0x7C]
     * [sensitivity u8: 0 off, 1 low, 2 normal, 3 high], unnumbered, once a second from the session thread - never
     * from the stick thread. The phone switches it off itself 5 s after the last one, and only ever runs it while the
     * flight controller reads DISARMED; what it finds comes back as the DETECTION record (FcTelemetry.getMotion).
     */
    public static final byte DETECTION_SET = 0x7C;
    /** The first phone app build with the motion detection. */
    static final int DETECTION_MIN_PC_BUILD = 35;
    static final long DETECTION_EVERY_MS = 1000;
    private volatile int detection;
    private long detectionSentAt;

    /** 0 off, 1 low, 2 normal, 3 high (MotionDetector's); told to the phone within a second. */
    public void setDetection(int sensitivity) {
        detection = Math.max(0, Math.min(3, sensitivity));
        detectionSentAt = 0;
    }

    public int getDetection() {
        return detection;
    }

    /** The phone app takes DETECTION_SET (a phone of pc.35 or newer). */
    public boolean detectionSupported() {
        return telemetry.getPhonePcBuild() >= DETECTION_MIN_PC_BUILD;
    }

    /** From the session thread while READY: DETECTION_SET once a second to a phone that takes it. */
    private void sendDetectionIfDue(long now) {
        if (!detectionSupported() || now - detectionSentAt < DETECTION_EVERY_MS) return;
        detectionSentAt = now;
        try {
            socket.send(new DatagramPacket(new byte[]{DETECTION_SET, (byte) detection}, 2, remoteIp, remotePort));
        } catch (IOException e) {
            // again in a second
        }
    }

    /** The first phone app build that takes the stick frame's send time and lateness limit. */
    static final int RC_LAG_MIN_PC_BUILD = 31;
    /** rc.maxLagMs (PcConfig.getMaxStickLagMs): 0 = the phone never drops a late frame. */
    private volatile int maxStickLagMs;

    public void setMaxStickLagMs(int ms) {
        maxStickLagMs = Math.max(0, Math.min(2550, ms));
    }

    /** The first phone app build that reads the stick frame's sequence byte. */
    static final int RC_SEQ_MIN_PC_BUILD = 19;
    /** The next stick frame's sequence number; the input thread alone touches it. */
    private int rcSeq;

    /**
     * Stick frames are wanted: the link is up, or has been and may come back - never while watching, asleep on
     * purpose, or refused for the version. What RcSession's gate waits for; see {@link #sendRc}.
     */
    public boolean sticksWanted() {
        return running && spectator == null && !versionMismatch && !dormant && (everReady || getState() == State.READY);
    }

    /** The link has been READY once since start(). */
    public boolean wasEverReady() {
        return everReady;
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

    /**
     * Nothing at all from the phone for this long: the link is lost. Upstream's buffer only gives up after 4000 of its
     * ticks (4-5 s on a PC, longer with the coarse Windows timer); whichever comes first counts, so a reconnect
     * (Connect, config, video again) starts sooner after a real loss.
     */
    static final long LINK_LOST_MS = 2000;

    public boolean isConnected() {
        if (receiverBuffer == null || !receiverBuffer.isConnected()) return false;
        long last = lastReceivedAt;
        return last == 0 || MonoClock.ms() - last <= LINK_LOST_MS;
    }

    /**
     * The link to the phone is up, but the phone isn't passing on anything from the flight controller (USB/OTG
     * cable out, FC unpowered or rebooting). The sticks don't reach the drone then either.
     */
    public boolean isFcLost() {
        if (getState() != State.READY || isLinkStalled()) return false; // a stalled link explains the silence itself
        long now = MonoClock.ms();
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
        return MonoClock.ms() - lastPingTimestamp > PING_STALE_MS ? -1 : pingMs;
    }

    /**
     * The link is up but nothing has come from the phone for STALL_MS: the sticks most likely aren't getting through
     * either. Seen within a third of a second, long before the link counts as lost.
     */
    public boolean isLinkStalled() {
        long last = lastReceivedAt;
        if (getState() != State.READY) return false;
        long now = MonoClock.ms();
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
        String phone = c.pilotPhone();
        String left = c.pilotLeft();
        PilotView view = c.pilotView();
        if (left != null) {
            return "SPECTATOR: pilot PC disconnected" + (left.isEmpty() ? "" : " from " + left) + " on purpose - not a link loss. "
                    + (watchOnly ? "Waiting for a pilot." : "Nobody flies: DISCONNECT, CONNECT to fly from here.");
        }
        if (c.phase() == SpectatorClient.Phase.WATCHING && view != null && !view.linkReady()) {
            return "SPECTATOR: " + who + " LINK LOST to " + (phone == null ? "the drone" : phone) + ", reconnecting.";
        }
        return switch (c.phase()) {
            case WATCHING -> "SPECTATOR: watching " + who + (phone == null ? "" : " fly " + phone) + ", " + c.delayMs()
                    + " ms behind. No sticks sent.";
            case JOINING -> p == null ? "SPECTATOR: asking phone which PC flies..."
                    : "SPECTATOR: joining " + who + "...";
            case REFUSED_NOT_FLYING -> "SPECTATOR: " + who + " not flying yet - waiting.";
            case REFUSED_FULL -> "SPECTATOR: " + who + " has " + SpectatorHost.MAX_SPECTATORS + " spectators - waiting for a place.";
            case REFUSED_OFF -> "SPECTATOR: " + who + " allows no spectators.";
            case NO_PILOT -> watchOnly ? "SPECTATOR: nobody flies yet - waiting for a pilot."
                    : "SPECTATOR: nobody flies. DISCONNECT, CONNECT to fly from here.";
        };
    }

    /**
     * What a PC's own OSD warns of, for its spectators to show the same (1.0.41; the pilot: "it should just be a mirror
     * of the pilot pc" - a pilot without a radio showed RC OFF - NO RADIO, its spectator nothing).
     * @param rcWaiting what the sticks wait for (NO RADIO, THROTTLE DOWN ...) while not live, or null
     * @param banner    the pilot's live banner (STANDBY, STICKS LATE, LINK BACK ...), or null
     * @param linkHint  under LINK LOST: why, or null
     * @param channels  the sticks the pilot sends (the OSD's stick boxes), or null
     */
    public record PilotView(boolean linkReady, boolean rcLive, boolean radioFrozen, boolean armSwitchUnknown, boolean relayed,
                            boolean linkStalled, String rcWaiting, String banner, String linkHint, short[] channels,
                            String phoneName, boolean upsideDown) {
        /** Without the phone and the flip. */
        public PilotView(boolean linkReady, boolean rcLive, boolean radioFrozen, boolean armSwitchUnknown, boolean relayed,
                         boolean linkStalled, String rcWaiting, String banner, String linkHint, short[] channels) {
            this(linkReady, rcLive, radioFrozen, armSwitchUnknown, relayed, linkStalled, rcWaiting, banner, linkHint, channels, null, false);
        }
    }

    /** While watching: the pilot disconnected on purpose (not a link loss) and no pilot since - the phone it flew ("" unknown); else null. */
    public String getPilotLeft() {
        SpectatorClient c = spectator;
        return c == null ? null : c.pilotLeft();
    }

    /** While watching: the phone the pilot flies (or last flew), or null. */
    public String getPilotPhone() {
        SpectatorClient c = spectator;
        return c == null ? null : c.pilotPhone();
    }

    /** Flying with spectators: what this PC's OSD warns of now, passed on to them. */
    public void setPilotView(PilotView v) {
        SpectatorHost h = host;
        if (h != null && v != null) h.setPilotView(Spectate.pilotView(v));
    }

    /** While watching: what the pilot PC's own OSD warns of, or null (none yet, lapsed, or a pilot older than 1.0.41). */
    public PilotView getPilotView() {
        SpectatorClient c = spectator;
        return c == null ? null : c.pilotView();
    }

    /** While watching: the pilot PC holds its stream because its own link to the drone is slow (PAUSED on the OSD). */
    public boolean isSpectatorPaused() {
        SpectatorClient c = spectator;
        return c != null && c.isPaused();
    }

    /**
     * Someone watches through this PC: AUTO stays off the sharp tier (a SHARP chosen in VIDEO stays). The relay sends the
     * picture out again through this PC's upload; at 8 Mbit/s that clogged it and the ping to the drone - the sticks -
     * went from 50 ms to 300 - 700 ms (2026-11-05).
     */
    private boolean watched() {
        return getSpectatorCount() + watchersForTest > 0;
    }

    private volatile int watchersForTest;

    /** For tests: as if this many spectators watched. */
    void setWatchersForTest(int n) {
        watchersForTest = n;
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
        return last == 0 ? -1 : MonoClock.ms() - last;
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
        lastClosedPort = localPort;
        lastClosedAt = MonoClock.ms();
        SpectatorClient c = spectator;
        if (c != null) c.close();
        SpectatorHost h = host;
        if (h != null) h.close();
        if (udpSender != null) udpSender.close();
        if (receiverBuffer != null) receiverBuffer.close();
        if (socket != null) socket.close();
    }
}
