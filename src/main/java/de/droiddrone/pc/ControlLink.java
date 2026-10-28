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

import static de.droiddrone.common.Log.log;

/**
 * The PC side of a DroidDrone link: plays the role of the upstream Control app towards the Flight app.
 * Ported from upstream Control/Udp.java and MainActivity's connection thread (RC + handshake only, no video yet).
 */
public class ControlLink {
    public static final int MIN_RC_RATE = 10;
    public static final int MAX_RC_RATE = 250;
    /**
     * RC rate we announce to the Flight app. Its only use there is Msp.setRawRc's minimum gap of 1000 / rate / 2 ms
     * (integer maths): frames arriving closer together are DROPPED, and over 4G frames often arrive bunched, so the drop
     * would discard the newest sticks. 255, the largest value the packet's unsigned byte holds, shrinks that window to
     * 1 ms. How often we actually send is our own rcRate.
     */
    static final int ANNOUNCED_RC_RATE = 255;
    /** Betaflight's MSP_RX_MAP has 8 entries (roll, pitch, yaw, throttle, AUX1-4). */
    static final int RX_MAP_SIZE = 8;

    public enum State { CONNECTING, CONFIGURING, READY, VERSION_MISMATCH, CLOSED }

    /** Receives the video stream. Called on the network receive thread: copy what you keep, return quickly. */
    public interface VideoListener {
        /** Stream (re)starts: size, codec and the codec config (Annex-B VPS/SPS/PPS). */
        void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig);

        /** One UDP fragment of a frame; frameSize is only known (> 0) on the fragment at offset 0. */
        void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length);
    }

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
    private volatile boolean versionMismatch;
    private volatile FcInfo fcInfo;
    private volatile int pingMs;
    private volatile long lastPingTimestamp;
    private final AtomicLong rcFramesSent = new AtomicLong();
    private final AtomicLong telemetryPacketsReceived = new AtomicLong();
    private final FcTelemetry telemetry = new FcTelemetry();
    private volatile VideoListener videoListener;
    private volatile boolean videoHevc = true;
    private volatile boolean videoConfigReceived;

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

    public void start() throws IOException {
        socket = new DatagramSocket(localPort);
        socket.setReceiveBufferSize(UdpCommon.packetLength * 300);
        socket.setSendBufferSize(UdpCommon.packetLength);
        socket.setTrafficClass(0x10);
        udpSender = new UdpSender(socket);
        udpSender.connect(remoteIp, remotePort);
        receiverBuffer = new ReceiverBuffer(udpSender, false, key, key);
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
        byte[] buf = new byte[UdpCommon.packetLength];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(packet);
                if (!packet.getAddress().equals(remoteIp) || packet.getPort() != remotePort) continue;
                if (handleVideoFragment(packet)) continue;
                receiverBuffer.addPacket(packet);
            } catch (Exception e) {
                if (running) log("Receiver error: " + e);
            }
        }
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
        VideoListener listener = videoListener;
        if (listener == null || !isConnected() || len < 7) return true;
        short frameNum = (short) (((d[1] & 0xff) << 8) | (d[2] & 0xff));
        int offset = readInt(d, 3);
        int frameSize = 0;
        int header = 7;
        if (offset == 0) {
            if (len < 11) return true;
            frameSize = readInt(d, 7);
            header = 11;
        }
        listener.onVideoFragment(d[0] == UdpCommon.KeyFrame, frameNum, offset, frameSize, d, header, len - header);
        return true;
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
                    if (packet != null) processData(packet);
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
                Thread.sleep(250); // the bitrate control watches the ping for queueing on the uplink
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
        long lastBoxNamesRequest = 0;
        while (running) {
            try {
                boolean connected = isConnected();
                if (wasConnected && !connected) {
                    log("Link lost, reconnecting...");
                    configReceived = false;
                    videoConfigReceived = false;
                    fcInfo = null;
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
                    if (fcInfo == null && now - lastFcInfoRequest >= 1000) {
                        sendRequest(UdpCommon.FcInfo);
                        lastFcInfoRequest = now;
                    } else if (fcInfo != null && !telemetry.hasBoxNames() && now - lastBoxNamesRequest >= 2000) {
                        sendRequest(UdpCommon.BoxNames); // names of the flight modes, to read MSP_STATUS mode flags
                        lastBoxNamesRequest = now;
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

    private void processData(SavedPacket packet) {
        DataReader buffer = new DataReader(packet.data, true);
        byte packetName = buffer.readByte();
        if (UdpCommon.isPacketNumbered(packetName)) buffer.readShort();
        switch (packetName) {
            case UdpCommon.Pong -> {
                boolean toEndPoint = buffer.readBoolean();
                long time = buffer.readLong();
                if (toEndPoint) {
                    pingMs = (int) (System.currentTimeMillis() - time);
                    lastPingTimestamp = System.currentTimeMillis();
                }
            }
            case UdpCommon.ConfigReceived -> configReceived = true;
            case UdpCommon.VersionMismatch -> {
                configReceived = false;
                versionMismatch = true;
            }
            case UdpCommon.FcInfo -> fcInfo = new FcInfo(buffer.readByte(), buffer.readByte(), buffer.readByte(),
                    buffer.readByte(), buffer.readByte(), buffer.readByte(), buffer.readByte(), buffer.readByte());
            case UdpCommon.TelemetryData -> {
                telemetryPacketsReceived.incrementAndGet();
                telemetry.parse(buffer, fcInfo);
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
        if (!running || !isConnected()) return;
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

    /** Field-for-field the same packet as upstream Control Udp.sendConfig, filled from droiddrone-pc.properties. */
    byte[] buildConfigPacket() throws IOException {
        UdpPacketData packetData = new UdpPacketData(UdpCommon.Config);
        packetData.daos.writeShort(SettingsCommon.versionCompatibleCode);
        // cameras: only the first one is used for now
        for (int i = 0; i < SettingsCommon.maxCamerasCount; i++) {
            if (i > 0) packetData.daos.writeBoolean(false);
            packetData.daos.writeUTF(i == 0 ? config.getCameraId() : SettingsCommon.cameraId[i]);
            packetData.daos.writeShort(config.getCameraWidth());
            packetData.daos.writeShort(config.getCameraHeight());
            packetData.daos.writeShort(config.getCameraFpsMin());
            packetData.daos.writeShort(config.getCameraFpsMax());
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
            udpSender.sendPacket(packetData.getData());
            rcFramesSent.incrementAndGet();
            return true;
        } catch (IOException e) {
            log("sendRc error: " + e);
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

    public State getState() {
        if (!running) return State.CLOSED;
        if (versionMismatch) return State.VERSION_MISMATCH;
        if (!isConnected()) return State.CONNECTING;
        if (!configReceived) return State.CONFIGURING;
        return State.READY;
    }

    /** @return round-trip time to the Flight phone in ms, or -1 if no recent pong */
    public int getPing() {
        return System.currentTimeMillis() - lastPingTimestamp > 2000 ? -1 : pingMs;
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

    /** Tells the Flight app we are leaving (it stops streaming), then closes the socket. */
    public void close() {
        if (!running) return;
        try {
            if (isConnected()) udpSender.sendPacket(new UdpPacketData(UdpCommon.Disconnect).getData());
        } catch (IOException e) {
            log("sendDisconnect error: " + e);
        }
        running = false;
        if (udpSender != null) udpSender.close();
        if (receiverBuffer != null) receiverBuffer.close();
        if (socket != null) socket.close();
    }
}
