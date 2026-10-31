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
    }

    /** The first phone app that makes any frame-rate range safe (it picks the camera's nearest supported one). */
    static final String STEADY_FPS_PHONE_APP = "2.3.0-pc.12";
    /** How long to wait for the phone app's version before the settings go out (it comes within ~0.1 s of connecting). */
    static final long PHONE_VERSION_WAIT_MS = 1500;

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
        if (spectatorsEnabled) {
            try {
                host = new SpectatorHost(key, spectatorsAllowed, socket, () -> getState() == State.READY, this::getPing,
                        msg -> events.accept(msg));
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
        if (d[0] == UdpCommon.VideoFrame || d[0] == UdpCommon.KeyFrame) {
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
        if (len < 1 || (d[0] != UdpCommon.VideoFrame && d[0] != UdpCommon.KeyFrame)) return false;
        if (!isConnected()) return true;
        deliverVideoFragment(d, len);
        SpectatorHost h = host;
        if (h != null) h.forward(d, len); // after our own picture: a copy into a queue, nothing without spectators
        return true;
    }

    private void deliverVideoFragment(byte[] d, int len) {
        VideoListener listener = videoListener;
        if (listener == null || len < 7) return;
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
        long connectedAt = 0;
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
                if (connected && !wasConnected) connectedAt = System.currentTimeMillis();
                wasConnected = connected;
                if (versionMismatch) {
                    Thread.sleep(1000);
                } else if (!connected) {
                    udpSender.sendConnect(1, key, SettingsCommon.versionCompatibleCode);
                    Thread.sleep(500);
                } else if (!configReceived) {
                    // a steady frame rate goes only to a phone app that can take it: wait for its version first
                    if (wantsSteadyFps() && telemetry.getPhoneAppVersion() == null
                            && System.currentTimeMillis() - connectedAt < PHONE_VERSION_WAIT_MS) {
                        Thread.sleep(50);
                        continue;
                    }
                    sendConfig();
                    Thread.sleep(500);
                } else {
                    long now = System.currentTimeMillis();
                    if (videoListener != null && !videoConfigReceived && now - lastStartVideo >= START_VIDEO_RETRY_MS) {
                        sendStartVideo();
                        lastStartVideo = now;
                    }
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
            packetData.daos.writeShort(config.getCameraWidth());
            packetData.daos.writeShort(config.getCameraHeight());
            int[] fps = cameraFps();
            packetData.daos.writeShort(fps[0]);
            packetData.daos.writeShort(fps[1]);
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

    private boolean wantsSteadyFps() {
        return config.getCameraFpsMin() == config.getCameraFpsMax();
    }

    /**
     * The frame-rate range for the phone: as set, but a steady one (min = max) only to phone app 2.3.0-pc.12 and on.
     * Older apps hand the range to the camera unchecked, and a camera that doesn't list it may stop the picture: they
     * get 30-60, as before.
     */
    int[] cameraFps() {
        int min = config.getCameraFpsMin(), max = config.getCameraFpsMax();
        if (min != max) return new int[]{min, max};
        String app = telemetry.getPhoneAppVersion();
        if (app != null && !de.droiddrone.pc.setup.UpdateCheck.isNewer(STEADY_FPS_PHONE_APP, app)) return new int[]{min, max};
        if (!steadyFpsWarned) {
            steadyFpsWarned = true;
            events.accept("Steady " + max + " fps needs phone app " + STEADY_FPS_PHONE_APP + " or newer (this phone: "
                    + (app == null ? "an older one" : app) + "): the camera runs at 30-60 fps. Update the phone app.");
        }
        return new int[]{30, 60};
    }

    private boolean steadyFpsWarned;

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
        return getState() == State.READY && last != 0 && System.currentTimeMillis() - last > STALL_MS;
    }

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
            case JOINING -> "SPECTATOR: another PC flies the drone - joining " + who + "...";
            case REFUSED_NOT_FLYING -> "SPECTATOR: " + who + " is not flying yet - waiting.";
            case REFUSED_FULL -> "SPECTATOR: " + who + " already has " + SpectatorHost.MAX_SPECTATORS + " spectators - waiting for a place.";
            case REFUSED_OFF -> "SPECTATOR: " + who + " lets nobody watch (SETTINGS > Allow spectators is off there).";
            case NO_PILOT -> "SPECTATOR: nobody flies the drone now. To fly it from this PC, DISCONNECT and CONNECT again.";
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
