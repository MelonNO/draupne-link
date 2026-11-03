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
 *  Video packets are paced (a key frame no longer leaves as one burst whose end the 4G uplink drops); the PC can ask for a key frame (KEY_FRAME_REQUEST); the encoder state goes to the PC with the stats.
 *
 *  Modified for DRAUPNE Link: measures stick packets arriving and sends PcLinkStats (and this app's version)
 *  to the PC every 0.1 s, as its own telemetry packet. Spectator mode: a second PC (same key) that asks while one
 *  is connected is told which PC flies, so it can watch through that PC (DRAUPNE Link's Spectate.java).
 *  pc.19: stick frames go to the flight controller straight from the receive thread (handleRcFrame), with the
 *  PC's sequence byte keeping a late frame from going out after a newer one; a PC that restarts gets its session
 *  back at once (receiverRun). pc.21: the PC's PowerMode packet and the PHONE_POWER record (PowerMode); no sticks
 *  and no StartVideo in standby or deep sleep; the socket closes and opens again for deep sleep's checks.
 */

package de.droiddrone.flight;

import android.media.MediaCodec;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.Log;
import de.droiddrone.common.MediaCodecBuffer;
import de.droiddrone.common.MediaCommon;
import de.droiddrone.common.ReceiverBuffer;
import de.droiddrone.common.SavedPacket;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.TelemetryData;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;
import de.droiddrone.common.UdpSender;

import static de.droiddrone.common.Logcat.log;

public class Udp {
    private final String destIpStr;
    private final int port;
    private final String key;
    private final int connectionMode;
    private final byte[] receiverBuf = new byte[UdpCommon.packetLength];
    private final StreamEncoder streamEncoder;
    private final Mp4Recorder mp4Recorder;
    private final CameraManager cameraManager;
    private final Msp msp;
    private final Mavlink mavlink;
    private final PhoneTelemetry phoneTelemetry;
    private final Config config;
    private final Serial serial;
    private final MavlinkUdpBridge mavlinkUdpBridge;
    private DatagramSocket socket;
    private InetAddress destIp;
    private DatagramPacket receiverPacket;
    private int videoFrameNum = 0;
    private Thread receiverThread;
    private byte[] videoInitialFrame = null;
    private byte[] audioInitialFrame = null;
    private int videoSenderThreadId, audioSenderThreadId, udpThreadsId;
    private UdpSender udpSender;
    // DRAUPNE Link: replaced on the receive thread when the PC starts a new session, read by the buffer thread
    private volatile ReceiverBuffer receiverBuffer;
    private boolean isVideoStarting, isAudioStarting;
    private final Object udpSync = new Object();
    /** When the pilot PC was last heard from (any packet from its address and port). */
    private volatile long lastPilotPacketMs;
    /** A pilot silent for this long is not announced to a Connect from elsewhere (PILOT_AT): that PC takes over. */
    private static final long PILOT_ALIVE_MS = 1000;
    /**
     * The sequence byte of the last stick frame taken (pc.19: [24][count u8][count x i16][seq u8]; a PC that knows
     * only the older layout sends none). Only read and written on the receive thread.
     */
    private volatile int lastRcSeq = -1; // volatile: the stats thread reports it
    private boolean rcSeqValid;
    /** The channels of the last stick frame, filled in place on the receive thread: no allocation per frame. */
    private final short[] rcFrame = new short[FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT];
    /** A PC that tried to connect with another key (shown on the screen): its address and when. */
    static volatile String wrongKeyFrom;
    static volatile long wrongKeyAt;
    private boolean isCameraRestarting;
    /** A StartVideo refused in standby or deep sleep was logged (once, not every 2.5 s). */
    private boolean videoRefusedSaid;
    // spectator mode (DRAUPNE Link Spectate.java): codes clear of upstream's packet names
    private static final byte SPECTATE_PILOT_QUERY = 0x60;
    private static final byte SPECTATE_PILOT_AT = 0x61;
    private static final byte SPECTATE_NO_PILOT = 0x62;
    /**
     * DRAUPNE Link: starting the camera and encoder runs here, not on the thread that handles the PC's packets - that
     * one also passes the sticks to the flight controller and answers pings. In 2.3.0-pc.13 a video start that hung
     * held up both (the PC saw telemetry, but no pongs and no picture).
     */
    private final ExecutorService videoStarter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "videoStarter");
        t.setDaemon(true);
        return t;
    });
    /**
     * DRAUPNE Link: the PC's video profile, [0x6B][flags]: bit 0 = bad coverage mode - key frames sent twice, and
     * after every frame a parity packet (VIDEO_PARITY) from which the PC rebuilds any one lost packet of it.
     */
    private static final byte VIDEO_PROFILE = 0x6B;
    private volatile boolean keyFramesTwice;
    /**
     * [0x6C][frame number i16][frame size i32][key frame u8][XOR of the frame's packet payloads, each zero-padded to
     * the longest]. A weak 4G link (2026-11-01) lost a packet in about one frame of five, at random: with this the PC
     * rebuilds it instead of showing a broken picture. About one packet more per frame.
     */
    private static final byte VIDEO_PARITY = 0x6C;

    /** XORs a packet's payload into the frame's parity. */
    private static void addParity(byte[] parity, byte[] buf, int offset, int length) {
        for (int i = 0; i < length; i++) parity[i] ^= buf[offset + i];
    }

    private void sendParity(short frameNum, int size, boolean keyFrame, byte[] parity) throws Exception {
        UdpPacketData packetData = new UdpPacketData(VIDEO_PARITY);
        packetData.daos.writeShort(frameNum);
        packetData.daos.writeInt(size);
        packetData.daos.writeByte(keyFrame ? 1 : 0);
        packetData.daos.write(parity, 0, parity.length);
        udpSender.sendPacket(packetData.getData());
    }
    /** DRAUPNE Link: the PC asks for a key frame (one arrived damaged or lost). */
    private static final byte KEY_FRAME_REQUEST = 0x6A;
    /** Answers go out from here: the receive thread, which carries the sticks, never waits on a send. One for the app. */
    private static final ExecutorService spectatorAnswers = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spectatorAnswers");
        t.setDaemon(true);
        return t;
    });
    private long lastSpectatorAnswer;

    public Udp(String destIpStr, int port, String key, int connectionMode, StreamEncoder streamEncoder,
               Mp4Recorder mp4Recorder, CameraManager cameraManager, Msp msp, Mavlink mavlink, PhoneTelemetry phoneTelemetry,
               Config config, Serial serial, MavlinkUdpBridge mavlinkUdpBridge){
        this.destIpStr = destIpStr;
        this.port = port;
        this.key = key;
        this.connectionMode = connectionMode;
        this.streamEncoder = streamEncoder;
        this.mp4Recorder = mp4Recorder;
        this.cameraManager = cameraManager;
        this.msp = msp;
        this.mavlink = mavlink;
        this.phoneTelemetry = phoneTelemetry;
        this.config = config;
        this.serial = serial;
        this.mavlinkUdpBridge = mavlinkUdpBridge;
        videoSenderThreadId = 0;
        audioSenderThreadId = 0;
        udpThreadsId = 0;
        isVideoStarting = false;
        isAudioStarting = false;
    }

    public boolean initialize(){
        if (connectionMode == SettingsCommon.ConnectionMode.overServer) {
            try {
                destIp = InetAddress.getByName(destIpStr);
            } catch (UnknownHostException e) {
                log("socketInit InetAddress error: " + e);
                return false;
            }
        }
        try {
            if (socket != null) socket.close();
            socket = new DatagramSocket(port);
            // DRAUPNE Link: upstream's 2 KB held two packets: a 4G burst (a key frame's packets delivered at once,
            // sticks behind them) tail-dropped the newest frames. 256 KB holds the burst; the sequence byte and
            // the writer's newest-first rule keep stale sticks from going out
            socket.setReceiveBufferSize(256 * 1024);
            socket.setSendBufferSize(UdpCommon.packetLength * 30);
            socket.setTrafficClass(0x10);
            udpSender = new UdpSender(socket);
            if (connectionMode == SettingsCommon.ConnectionMode.overServer) {
                udpSender.connect(destIp, port);
            }
            receiverBuffer = new ReceiverBuffer(udpSender, (connectionMode != SettingsCommon.ConnectionMode.overServer), key, null);
            receiverPacket = new DatagramPacket(receiverBuf, receiverBuf.length);
            receiverThread = new Thread(receiverRun);
            receiverThread.setDaemon(false);
            receiverThread.setName("receiverThread");
            receiverThread.start();
            Thread bufferThread = new Thread(bufferRun);
            bufferThread.setDaemon(false);
            bufferThread.setName("bufferThread");
            bufferThread.start();
            Thread telemetrySenderThread = new Thread(telemetrySenderRun);
            telemetrySenderThread.setDaemon(false);
            telemetrySenderThread.setName("telemetrySenderThread");
            telemetrySenderThread.start();
            // DRAUPNE Link: no rcThread any more - stick frames go to the flight controller from the receive thread
            // (handleRcFrame), which runs at MAX_PRIORITY like the serial writer that takes them
            return true;
        } catch (Exception e) {
            log("socketInit error: " + e);
            e.printStackTrace();
            return false;
        }
    }

    public void sendConnect(){
        UdpSender sender = udpSender;
        if (sender == null) return; // the socket is closed (deep sleep)
        sender.sendConnect(0, key, SettingsCommon.versionCompatibleCode);
    }

    /**
     * DRAUPNE Link: standby and deep sleep stop the camera and the encoder (on the videoStarter thread, like a
     * Disconnect). The PC's next StartVideo, in normal mode, starts them again as after a Disconnect.
     */
    public void stopVideo(){
        videoInitialFrame = null;
        videoStarter.execute(this::stopAudioVideo);
    }

    public void disconnect(){
        Thread th = new Thread(() -> {
            try {
                UdpPacketData packetData = new UdpPacketData(UdpCommon.Disconnect);
                udpSender.sendPacket(packetData.getData());
            } catch (Exception e) {
                e.printStackTrace();
                Log.log("sendDisconnect error: " + e);
            }
            close();
        });
        th.start();
    }

    private final Runnable receiverRun = new Runnable() {
        public void run() {
            final int id = udpThreadsId;
            receiverThread.setPriority(Thread.MAX_PRIORITY);
            log("Start receiver thread - OK");
            while (socket != null && !socket.isClosed() && id == udpThreadsId) {
                try {
                    socket.receive(receiverPacket);
                    int len = receiverPacket.getLength();
                    if (len < 1) continue;
                    byte[] d = receiverPacket.getData();
                    ReceiverBuffer buffer = receiverBuffer;
                    // check IP & port
                    if (connectionMode == SettingsCommon.ConnectionMode.overServer){
                        if (!receiverPacket.getAddress().equals(destIp)
                                || receiverPacket.getPort() != port) continue;
                    }else if (buffer.isConnected()){
                        if (receiverPacket.getAddress().equals(udpSender.getIp())
                                && receiverPacket.getPort() == udpSender.getPort()) {
                            lastPilotPacketMs = System.currentTimeMillis();
                        } else {
                            if (!otherPcWhileConnected(receiverPacket, d, len)) continue;
                            buffer = receiverBuffer; // the new session
                        }
                    }else{
                        if (d[0] == SPECTATE_PILOT_QUERY) {
                            answerSpectator(receiverPacket, false);
                            continue;
                        }
                        rcSeqValid = false; // every (re)connect passes here: the PC's stick sequence starts over
                        if (d[0] == UdpCommon.Connect) {
                            if (!keyMatches(d, len, 4)) {
                                noteWrongKey(receiverPacket);
                                continue;
                            }
                            lastPilotPacketMs = System.currentTimeMillis(); // heard from: no takeover in its first half second
                        }
                    }
                    // DRAUPNE Link: sticks straight to the flight controller from here - not through the buffer, a
                    // polling thread and a hand-over (the frame is unnumbered and never acknowledged, so the buffer
                    // had nothing to do with it). The session stays alive on the PC's pings (20 a second)
                    if (d[0] == UdpCommon.RcFrame) {
                        // pc.21: none in standby or deep sleep - the PC sends none, and a stray one must not fly
                        if (buffer.isConnected() && !PowerMode.dropSticks()) handleRcFrame(d, len);
                        continue;
                    }
                    buffer.addPacket(receiverPacket);
                } catch (Exception e) {
                    log("Receiver error: " + e);
                }
            }
        }
    };

    /**
     * A stick frame, on the receive thread: [24][count u8][count x i16][seq u8 - pc.19, only from a PC that knows
     * this app takes it]. With a sequence, a frame older than (or the same as) the last one taken - a late packet
     * arriving after a newer one - is dropped, except the first after a (re)connect, where the count starts over.
     * No allocation: the channels are read into {@link #rcFrame} in place.
     */
    private void handleRcFrame(byte[] d, int len) {
        if (len < 2) return;
        int count = d[1] & 0xff;
        if (count < 4 || count > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT) return;
        int remaining = len - 2;
        if (remaining == count * 2 + 1) {
            int seq = d[len - 1] & 0xff;
            if (rcSeqValid && (byte) (seq - lastRcSeq) <= 0) return;
            lastRcSeq = seq;
            rcSeqValid = true;
        } else if (remaining == count * 2) {
            lastRcSeq = -1;
        } else {
            return;
        }
        int at = 2;
        for (int i = 0; i < count; i++, at += 2) {
            rcFrame[i] = (short) (((d[at] & 0xff) << 8) | (d[at + 1] & 0xff));
        }
        PcLinkStats.rcIn();
        if (msp.isInitialized()) msp.setRawRc(rcFrame, count);
        if (mavlink.isInitialized()) mavlink.setRcChannelsOverride(java.util.Arrays.copyOf(rcFrame, count));
    }

    /**
     * A packet from another address or port while a PC is connected. A keyed Connect from the pilot's own address
     * (it started again, on a new port) or from anywhere while the pilot has been silent for {@link #PILOT_ALIVE_MS}
     * (its session is only still open because the 4 s timeout hasn't run out) starts the new session now: before,
     * such a PC was told PILOT_AT - the dead session's address - and became a spectator of itself for up to 4 s, a
     * restarted PC never connecting. Anything else is a spectator's question, or a wrong key.
     * @return true if the packet belongs to a (new) session and is to be taken
     */
    private boolean otherPcWhileConnected(DatagramPacket packet, byte[] d, int len) {
        boolean pilotAlive = System.currentTimeMillis() - lastPilotPacketMs < PILOT_ALIVE_MS;
        if (d[0] == UdpCommon.Connect) {
            if (!keyMatches(d, len, 4)) {
                noteWrongKey(packet);
                return false;
            }
            if (packet.getAddress().equals(udpSender.getIp()) || !pilotAlive) {
                log("New session from " + packet.getAddress().getHostAddress() + ":" + packet.getPort()
                        + (pilotAlive ? " (the pilot's address)" : " (the pilot is silent)"));
                newSession();
                return true;
            }
        }
        answerSpectator(packet, pilotAlive);
        return false;
    }

    /** A new receiver session: the next Connect taken starts it (ReceiverBuffer.addPacket). */
    private void newSession() {
        ReceiverBuffer old = receiverBuffer;
        receiverBuffer = new ReceiverBuffer(udpSender, (connectionMode != SettingsCommon.ConnectionMode.overServer), key, null);
        if (old != null) old.close();
        rcSeqValid = false;
    }

    /** Whether the key written (UTF) at keyAt - 4 in a Connect, 1 in a PILOT_QUERY - is this phone's. */
    private boolean keyMatches(byte[] d, int len, int keyAt) {
        if (len < keyAt + 2) return false;
        int n = ((d[keyAt] & 0xff) << 8) | (d[keyAt + 1] & 0xff);
        return keyAt + 2 + n <= len && key.equals(new String(d, keyAt + 2, n, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A PC tried to connect with another key: ReceiverBuffer drops it without a word; the screen says so. */
    private void noteWrongKey(DatagramPacket packet) {
        long now = System.currentTimeMillis();
        String from = packet.getAddress().getHostAddress();
        if (now - wrongKeyAt > 5000 || !from.equals(wrongKeyFrom)) log("A PC at " + from + " tried to connect with another key");
        wrongKeyFrom = from;
        wrongKeyAt = now;
    }

    private final Runnable telemetrySenderRun = new Runnable() {
        public void run() {
            final int id = udpThreadsId;
            log("Start telemetry sender thread - OK");
            long nextStats = 0;
            int statsSent = 0;
            while (id == udpThreadsId) {
                try {
                    sendTelemetryData();
                    long now = System.currentTimeMillis();
                    if (now >= nextStats && isConnected()) {
                        nextStats = now + 100;
                        UdpPacketData stats = new UdpPacketData(UdpCommon.TelemetryData);
                        stats.daos.writeShort(PcLinkStats.CODE);
                        PcLinkStats.writeTo(stats.daos);
                        String version = MainActivity.versionName;
                        if (statsSent++ % 10 == 0 && version != null) PcLinkStats.writeVersionTo(stats.daos, version);
                        // last in the packet: an older PC stops reading at a record it doesn't know
                        if (statsSent % 5 == 1) PcLinkStats.writeEncoderTo(stats.daos, streamEncoder.getTargetBitRate(), streamEncoder.isIntraRefresh());
                        if (statsSent % 10 == 5) { // once a second, last in its packet too
                            android.location.Location l = PhoneLocation.last();
                            if (l != null) PcLinkStats.writePhonePositionTo(stats.daos, l);
                        }
                        if (statsSent % 10 == 3) PcLinkStats.writePhoneStateTo(stats.daos, phoneStateFlags(), lastRcSeq); // pc.19, once a second
                        // pc.21, once a second and three times when the mode changes; always the last record: a
                        // pc.20 PC stops reading at it
                        if (statsSent % 10 == 7 || PowerMode.burstDue(now)) PowerMode.writeRecordTo(stats.daos);
                        udpSender.sendPacket(stats.getData());
                    }
                    Thread.sleep(10);
                } catch (Exception e) {
                    log("Telemetry thread error: " + e);
                }
            }
        }
    };

    private final Runnable bufferRun = new Runnable() {
        public void run() {
            final int id = udpThreadsId;
            log("Start buffer thread - OK");
            SavedPacket packet = null;
            while (socket != null && !socket.isClosed() && id == udpThreadsId) {
                try {
                    do{
                        packet = receiverBuffer.getNextPacket();
                        if (packet != null) processData(packet);
                    }while (packet != null);
                    receiverBuffer.processTimer();
                    Thread.sleep(1);
                } catch (Exception e) {
                    if (packet != null) {
                        log("Receiver buffer error: " + e + ", packetName: " + packet.packetName);
                    }else{
                        log("Receiver buffer error: " + e);
                    }
                }
            }
        }
    };

    private void startVideoStream(boolean isHevc) {
        if (isVideoStarting) return;
        isVideoStarting = true;
        try {
            startVideoStreamNow(isHevc);
        } catch (RuntimeException e) {
            log("Video start error: " + e);
        } finally {
            // DRAUPNE Link: upstream left the flag set when the start threw, and every later start was skipped
            isVideoStarting = false;
        }
    }

    private void startVideoStreamNow(boolean isHevc) {
        videoSenderThreadId++;
        if (!cameraManager.getCamera().initialize(streamEncoder, mp4Recorder)) {
            log("Camera initialize error.");
            isVideoStarting = false;
            return;
        }
        streamEncoder.close();
        streamEncoder.setDefaultHevc(isHevc);
        mp4Recorder.close();
        videoFrameNum = 0;
        if (cameraManager.getCamera().isOpened(config.getCameraId())) {
            cameraManager.getCamera().startPreview();
        }else{
            if (!cameraManager.getCamera().openCamera()) {
                log("Camera open error.");
                isVideoStarting = false;
                return;
            }
        }
        streamEncoder.startSendFrames();
        Thread videoSenderThread = new Thread(videoSenderRun);
        videoSenderThread.setDaemon(false);
        videoSenderThread.setName("videoSenderThread");
        videoSenderThread.start();
        isVideoStarting = false;
    }

    private void stopAudioVideo(){
        videoSenderThreadId++;
        audioSenderThreadId++;
        streamEncoder.close();
        if (mp4Recorder.isRecording()) mp4Recorder.close();
        cameraManager.getCamera().close();
    }

    private void stopAudioStream(){
        audioSenderThreadId++;
        streamEncoder.stopAudioEncoder();
    }

    private void startAudioStream(){
        if (isAudioStarting) return;
        isAudioStarting = true;
        if (streamEncoder.isAudioSending()){
            if (streamEncoder.getAudioBitRate() == config.getAudioStreamBitrate()) {
                sendAudioInitialFrame(audioInitialFrame);
                isAudioStarting = false;
                return;
            }else{
                streamEncoder.stopAudioEncoder();
            }
        }
        audioSenderThreadId++;
        audioInitialFrame = null;
        streamEncoder.startAudioEncoder();
        if (streamEncoder.isAudioSending()){
            Thread audioSenderThread = new Thread(audioSenderRun);
            audioSenderThread.setDaemon(false);
            audioSenderThread.setName("audioSenderThread");
            audioSenderThread.start();
        }
        isAudioStarting = false;
    }

    /**
     * Another PC asks to connect (or who flies) while one is connected: tells it where that PC is, so it can watch
     * through it. Only with the right key, and at most 5 answers a second.
     */
    private void answerSpectator(DatagramPacket packet, boolean flying) {
        byte[] d = packet.getData();
        int len = packet.getLength();
        if (len < 1) return;
        int keyAt;
        if (d[0] == SPECTATE_PILOT_QUERY) keyAt = 1;
        else if (flying && d[0] == UdpCommon.Connect) keyAt = 4; // [8][num i16][client type u8][key UTF]
        else return;
        if (!keyMatches(d, len, keyAt)) return;
        long now = System.currentTimeMillis();
        if (now - lastSpectatorAnswer < 200) return;
        lastSpectatorAnswer = now;
        byte[] answer;
        InetAddress pilotIp = udpSender.getIp();
        if (flying && pilotIp != null) {
            byte[] a = pilotIp.getAddress();
            int pilotPort = udpSender.getPort();
            answer = new byte[4 + a.length];
            answer[0] = SPECTATE_PILOT_AT;
            answer[1] = (byte) a.length;
            System.arraycopy(a, 0, answer, 2, a.length);
            answer[2 + a.length] = (byte) (pilotPort >> 8);
            answer[3 + a.length] = (byte) pilotPort;
        } else {
            answer = new byte[]{SPECTATE_NO_PILOT};
        }
        final DatagramPacket out = new DatagramPacket(answer, answer.length, packet.getAddress(), packet.getPort());
        final DatagramSocket s = socket;
        spectatorAnswers.execute(() -> {
            try {
                s.send(out);
            } catch (Exception e) {
                log("Spectator answer error: " + e);
            }
        });
    }

    public boolean isConnected(){
        ReceiverBuffer buffer = receiverBuffer;
        return (buffer != null && buffer.isConnected());
    }

    /** The PHONE_STATE flags: what the PC needs to say why no sticks reach the drone, or why there is no picture. */
    private int phoneStateFlags() {
        int flags = 0;
        if (msp.isRxMapKnown()) flags |= PcLinkStats.STATE_RX_MAP_KNOWN;
        int serialStatus = serial.getStatus();
        if (serialStatus == Serial.STATUS_USB_PERMISSION_REQUESTED) flags |= PcLinkStats.STATE_USB_PERMISSION_PENDING;
        if (serialStatus == Serial.STATUS_USB_PERMISSION_DENIED) flags |= PcLinkStats.STATE_USB_PERMISSION_DENIED;
        Camera camera = cameraManager.getCamera();
        if (camera != null && camera.isOpened(config.getCameraId())) flags |= PcLinkStats.STATE_CAMERA_OPEN;
        return flags;
    }

    public InetAddress getSenderIp(){
        UdpSender udpSender = this.udpSender;
        if (udpSender == null) return null;
        return udpSender.getIp();
    }

    private void processData(SavedPacket packet) {
        DataReader buffer = new DataReader(packet.data, true);
        byte packetName = buffer.readByte();
        if (UdpCommon.isPacketNumbered(packetName)) {
            short num = buffer.readShort();
        }
        switch (packetName) {
            case UdpCommon.StartVideo:
            {
                boolean isHevc = buffer.readBoolean();
                if (PowerMode.mode() != PowerMode.NORMAL) {
                    // pc.21: no camera in standby or during a sleep check; the PC asks again every 2.5 s, and the
                    // first StartVideo after mode 0 starts it
                    if (!videoRefusedSaid) log("StartVideo in " + PowerMode.name(PowerMode.mode()) + ": not started");
                    videoRefusedSaid = true;
                    break;
                }
                videoRefusedSaid = false;
                videoStarter.execute(() -> {
                    startVideoStream(isHevc);
                    if (config.isSendAudioStream()) startAudioStream();
                });
                break;
            }
            case UdpCommon.GetVideoConfig:
            {
                sendVideoInitialFrame(null);
                if (config.isSendAudioStream()) sendAudioInitialFrame(null);
                break;
            }
            case VIDEO_PROFILE:
            {
                boolean twice = buffer.getRemaining() > 0 && (buffer.readByte() & 1) != 0;
                if (twice != keyFramesTwice) log("Video profile: key frames " + (twice ? "twice" : "once"));
                keyFramesTwice = twice;
                break;
            }
            case KEY_FRAME_REQUEST:
            {
                streamEncoder.requestKeyFrame();
                break;
            }
            case UdpCommon.ChangeBitRate:
            {
                boolean increase = buffer.readBoolean();
                streamEncoder.changeBitRate(increase);
                break;
            }
            case PowerMode.PACKET:
            {
                // pc.21: [0x6E][mode u8][checkIntervalMin u8]; the PC sends it again until the mode comes back
                if (buffer.getRemaining() < 1) break;
                int mode = buffer.readUnsignedByteAsInt();
                int intervalMin = buffer.getRemaining() > 0 ? buffer.readUnsignedByteAsInt() : 0;
                PowerMode.request(mode, intervalMin, "the PC");
                break;
            }
            case UdpCommon.Ping:
            {
                boolean toEndPoint = buffer.readBoolean();
                long time = buffer.readLong();
                byte target = -1;
                if (buffer.getRemaining() > 0) target = buffer.readByte();
                udpSender.sendPong(toEndPoint, time, target);
                break;
            }
            case UdpCommon.FcInfo:
            {
                if (msp.isInitialized()){
                    sendFcInfo(msp.getFcInfo());
                }else if (mavlink.isInitialized()){
                    sendFcInfo(mavlink.getFcInfo());
                }
                break;
            }
            case UdpCommon.OsdConfig:
            {
                if (msp.isInitialized()) {
                    msp.runGetOsdConfig();
                }else if (mavlink.isInitialized()){
                    mavlink.runGetOsdConfig();
                }
                break;
            }
            case UdpCommon.BatteryConfig:
            {
                if (!msp.isInitialized()) break;
                msp.runGetBatteryConfig();
                break;
            }
            case UdpCommon.BoxIds:
            {
                if (!msp.isInitialized()) break;
                msp.runGetBoxIds();
                break;
            }
            case UdpCommon.BoxNames:
            {
                if (!msp.isInitialized()) break;
                msp.runGetBoxNames();
                break;
            }
            case UdpCommon.StartStopRecording:
            {
                boolean start = buffer.readBoolean();
                // DRAUPNE Link: on the videoStarter thread, like StartVideo - the muxer's start and stop take hundreds
                // of ms, and this thread delivers the PC's packets
                videoStarter.execute(() -> {
                    boolean withAudio = config.isRecordAudio();
                    if (start){
                        if (config.isUseExtraEncoder()){
                            mp4Recorder.startRecording(withAudio);
                        }else{
                            mp4Recorder.startRecording(withAudio, streamEncoder);
                        }
                    }else{
                        mp4Recorder.stopRecording();
                    }
                });
                break;
            }
            case UdpCommon.Config:
            {
                final Camera oldCamera = cameraManager.getCamera();
                // DRAUPNE Link: only the picture size changed (the PC's switch to 3/4 size on a weak link)?
                final String oldId = config.getCameraId();
                final int oldFpsMin = config.getCameraFpsMin(), oldFpsMax = config.getCameraFpsMax();
                final boolean oldUsb = config.isUseUsbCamera();
                int resCode = config.processReceivedConfig(buffer);
                if (resCode == 0) {
                    sendConfigReceived();
                    final boolean sizeOnly = oldId != null && oldId.equals(config.getCameraId()) && oldFpsMin == config.getCameraFpsMin()
                            && oldFpsMax == config.getCameraFpsMax() && oldUsb == config.isUseUsbCamera();
                    Thread t1 = new Thread(() -> {
                        checkConfigUpdate(oldCamera, sizeOnly);
                    });
                    t1.start();
                }else if (resCode == -1){
                    sendVersionMismatch();
                }
                break;
            }
            // UdpCommon.RcFrame never gets here: the receive thread takes it (handleRcFrame)
            case UdpCommon.Disconnect:
            {
                // stop stream when controller disconnected - on the videoStarter thread (the camera and muxer take
                // their time to close, and this thread delivers the PC's packets)
                videoInitialFrame = null;
                videoStarter.execute(this::stopAudioVideo);
                break;
            }
            case UdpCommon.ChangeCamera:
            {
                if (isCameraRestarting) break;
                final Camera oldCamera = cameraManager.getCamera();
                config.changeCamera();
                Thread t1 = new Thread(() -> {
                    checkConfigUpdate(oldCamera);
                });
                t1.start();
                break;
            }
            case UdpCommon.MavlinkRawPacket:
            {
                int dataSize = buffer.getRemaining();
                byte[] buf = new byte[dataSize];
                int read = buffer.read(buf, 0, dataSize);
                if (read == dataSize) {
                    mavlink.processReceivedUdpData(buf);
                }
                break;
            }
        }
    }

    public void sendMavlinkPacket(byte[] buf) {
        if (socket == null || socket.isClosed() || buf == null) return;
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.MavlinkRawPacket);
            packetData.daos.write(buf, 0, buf.length);
            udpSender.sendPacket(packetData.getData());
        } catch (Exception e) {
            log("sendMavlinkPacket error: " + e);
        }
    }

    private void checkConfigUpdate(Camera camera){
        checkConfigUpdate(camera, false);
    }

    /**
     * DRAUPNE Link: a change of picture size only, with the camera open and not recording on the phone: a new encoder
     * and capture session on the open camera, without closing it. Measured 1.5-2.6 s without video the other way.
     * @return true if done here
     */
    private boolean resizeOnOpenCamera(Camera camera){
        if (!(camera instanceof InternalCamera) || camera != cameraManager.getCamera() || !camera.isOpened(config.getCameraId())
                || mp4Recorder.isRecording() || isCameraRestarting) return false;
        isCameraRestarting = true;
        try {
            long start = System.currentTimeMillis();
            boolean isHevc = MediaCommon.hevcCodecMime.equals(streamEncoder.getCurrentCodecType());
            ((InternalCamera) camera).stopSession();
            startVideoStream(isHevc); // initialize (the new size), then on the open camera: a new encoder and session
            log("Picture size changed on the open camera in " + (System.currentTimeMillis() - start) + " ms");
        } catch (RuntimeException e) {
            log("Picture size change error: " + e);
        } finally {
            isCameraRestarting = false;
        }
        return true;
    }

    private void checkConfigUpdate(Camera camera, boolean sizeOnly){
        if (config.isRcConfigChanged()) {
            msp.setRcMinPeriod();
            mavlink.setRcMinPeriod();
            config.rcConfigUpdated();
        }
        if (config.isFcConfigChanged()) {
            serial.restart();
            config.fcConfigUpdated();
        }
        if (config.isMavlinkUdpBridgeConfigChanged()){
            mavlinkUdpBridge.close();
            if (config.getMavlinkUdpBridge() != SettingsCommon.MavlinkUdpBridge.disabled
                    || config.getMavlinkUdpBridge() != SettingsCommon.MavlinkUdpBridge.redirectFromControlDevice){
                mavlinkUdpBridge.initialize();
            }
            config.mavlinkUdpBridgeConfigUpdated();
        }
        if (config.isCameraConfigChanged()) {
            // DRAUPNE Link: a camera restart still under way: wait for it (upstream marked the change done without
            // making it - the picture size then stayed wrong until the next change, seen 2026-11-01)
            for (int i = 0; i < 30 && isCameraRestarting; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            if (isCameraRestarting) { // no restart takes 3 s: a flag left set by one that went wrong
                log("Camera restart flag stuck: cleared");
                isCameraRestarting = false;
            }
            if (sizeOnly && (camera.isStarted() || videoInitialFrame != null) && resizeOnOpenCamera(camera)) {
                // done without closing the camera
            } else if ((camera.isStarted() || videoInitialFrame != null) && !isCameraRestarting){
                isCameraRestarting = true;
                boolean isRecording = mp4Recorder.isRecording();
                boolean isHevc = MediaCommon.hevcCodecMime.equals(streamEncoder.getCurrentCodecType());
                try {
                    if (isRecording) mp4Recorder.stopRecording(camera);
                    stopAudioVideo();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                    startVideoStream(isHevc);
                } catch (RuntimeException e) {
                    log("Camera restart error: " + e); // the flag goes back below, as after a good restart
                }
                if (config.isSendAudioStream()) {
                    startAudioStream();
                    config.audioStreamConfigUpdated();
                }
                if (isRecording) {
                    Thread t1 = new Thread(() -> {
                        for (int i = 0; i < 5; i++) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ignored) {
                            }
                            if (cameraManager.getCamera().isStarted()) {
                                startStopRecording();
                                break;
                            }
                        }
                        isCameraRestarting = false;
                    });
                    t1.start();
                    config.recorderConfigUpdated();
                }else{
                    isCameraRestarting = false;
                }
            }
            config.cameraConfigUpdated();
        }
        if (config.isRecorderConfigChanged()) {
            boolean isRecording = mp4Recorder.isRecording();
            if (isRecording) {
                mp4Recorder.stopRecording();
                startStopRecording();
            }
            config.recorderConfigUpdated();
        }
        if (config.isAudioStreamConfigChanged()){
            stopAudioStream();
            if (config.isSendAudioStream()) startAudioStream();
            config.audioStreamConfigUpdated();
        }
    }

    private void startStopRecording() {
        if (mp4Recorder.isRecording()) {
            mp4Recorder.stopRecording();
        }else{
            boolean withAudio = config.isRecordAudio();
            if (config.isUseExtraEncoder()) {
                mp4Recorder.startRecording(withAudio);
            }else{
                mp4Recorder.startRecording(withAudio, streamEncoder);
            }
        }
    }

    private final Runnable videoSenderRun = new Runnable() {
        public void run() {
            final int id = videoSenderThreadId;
            log("Video sender thread is running");
            while (id == videoSenderThreadId) {
                try {
                    // DRAUPNE Link: waits for the encoder (upstream polled in a tight loop: a full core all flight)
                    MediaCodecBuffer buf = streamEncoder.videoStreamOutputBuffer.poll(100, TimeUnit.MILLISECONDS);
                    if (buf == null) continue;
                    switch (buf.flags){
                        case MediaCodec.BUFFER_FLAG_CODEC_CONFIG:
                            sendVideoInitialFrame(buf.data);
                            break;
                        case MediaCodec.BUFFER_FLAG_KEY_FRAME:
                            sendKeyFrame(buf.data);
                            break;
                        default:
                            sendVideoFrame(buf.data);
                            break;
                    }
                    if (streamEncoder.videoStreamOutputBuffer.size() > Math.round(5 * cameraManager.getCamera().getCurrentFps() / 30f)){
                        streamEncoder.changeBitRate(false);
                    }
                } catch (Exception e) {
                    // do nothing
                }
            }
        }
    };

    private final Runnable audioSenderRun = new Runnable() {
        public void run() {
            final int id = audioSenderThreadId;
            log("Audio sender thread is running");
            while (id == audioSenderThreadId && streamEncoder != null && streamEncoder.isAudioSending()) {
                try {
                    if (!config.isSendAudioStream()){
                        streamEncoder.stopAudioEncoder();
                        break;
                    }
                    MediaCodecBuffer buf = streamEncoder.audioOutputBuffer.poll(100, TimeUnit.MILLISECONDS);
                    if (buf == null) continue;
                    if (buf.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                        sendAudioInitialFrame(buf.data);
                    } else {
                        sendAudioFrame(buf.data);
                    }
                } catch (Exception e) {
                    // do nothing
                }
            }
        }
    };

    private void sendTelemetryData(){
        try {
            TelemetryData buf = null;
            boolean sendData = false;
            UdpPacketData packetData = new UdpPacketData(UdpCommon.TelemetryData);
            do{
                if (msp.isInitialized()) {
                    buf = msp.telemetryOutputBuffer.poll();
                    if (buf != null) {
                        sendData = true;
                        fillTelemetryPacketData(buf, packetData);
                    }
                }
                if (mavlink.isInitialized()) {
                    buf = mavlink.telemetryOutputBuffer.poll();
                    if (buf != null) {
                        sendData = true;
                        fillTelemetryPacketData(buf, packetData);
                    }
                }
                buf = phoneTelemetry.telemetryOutputBuffer.poll();
                if (buf != null){
                    sendData = true;
                    fillTelemetryPacketData(buf, packetData);
                }
            }while (buf != null);
            if (sendData) udpSender.sendPacket(packetData.getData());
        } catch (Exception e) {
            log("sendTelemetryData error: " + e);
        }
    }

    private void fillTelemetryPacketData(TelemetryData buf, UdpPacketData packetData){
        FcInfo fcInfo = msp.getFcInfo();
        try {
            if (fcInfo == null && buf.code < FcCommon.DD_TIMERS) return;
            packetData.daos.writeShort(buf.code);
            DataReader buffer = new DataReader(buf.data, false);
            switch (buf.code){
                case FcCommon.MSP_ATTITUDE: {
                    packetData.daos.writeShort(buffer.readShort());//roll
                    packetData.daos.writeShort(buffer.readShort());//pitch
                    packetData.daos.writeShort(buffer.readShort());//yaw
                    break;
                }
                case FcCommon.MSP_ALTITUDE: {
                    packetData.daos.writeInt(buffer.readInt());//altitude
                    packetData.daos.writeShort(buffer.readShort());//altVelocity
                    if (fcInfo.getFcVariant() == FcInfo.FC_VARIANT_INAV){
                        packetData.daos.writeInt(buffer.readInt());//altBaro
                    }
                    break;
                }
                case FcCommon.MSP_ANALOG: {
                    buffer.readByte();//legacyVoltage
                    packetData.daos.writeShort(buffer.readShort());//mahDrawn
                    packetData.daos.writeShort(buffer.readShort());//rssi
                    packetData.daos.writeShort(buffer.readShort());//amperage
                    if (fcInfo.getFcVariant() == FcInfo.FC_VARIANT_BETAFLIGHT) {
                        packetData.daos.writeShort(buffer.readShort());//voltage
                    }
                    break;
                }
                case FcCommon.MSP_BOXIDS:
                case FcCommon.MSP_BOXNAMES: {
                    byte[] data = buffer.getData();
                    packetData.daos.writeShort(data.length);
                    packetData.daos.write(data, 0, data.length);
                    break;
                }
                case FcCommon.MSP_BATTERY_CONFIG: {
                    switch (fcInfo.getFcVariant()){
                        case FcInfo.FC_VARIANT_INAV:{
                            buffer.readShort();//voltageScale
                            buffer.readByte();//voltageSource
                            buffer.readByte();//configCells
                            buffer.readShort();//cellDetect
                            packetData.daos.writeShort(buffer.readShort());//minCellVoltage
                            packetData.daos.writeShort(buffer.readShort());//maxCellVoltage
                            packetData.daos.writeShort(buffer.readShort());//warningCellVoltage
                            buffer.readShort();//currentOffset
                            buffer.readShort();//currentScale
                            buffer.readInt();//batteryCapacity
                            buffer.readInt();//capacityWarning
                            buffer.readInt();//capacityCritical
                            buffer.readByte();//capacityUnit
                            break;
                        }
                        case FcInfo.FC_VARIANT_BETAFLIGHT:{
                            buffer.readByte();//vbatMinCellVoltage - legacy
                            buffer.readByte();//vbatMaxCellVoltage - legacy
                            buffer.readByte();//vbatWarningCellVoltage - legacy
                            buffer.readShort();//batteryCapacity
                            buffer.readByte();//voltageMeterSource
                            buffer.readByte();//currentMeterSource
                            packetData.daos.writeShort(buffer.readShort());//minCellVoltage
                            packetData.daos.writeShort(buffer.readShort());//maxCellVoltage
                            packetData.daos.writeShort(buffer.readShort());//warningCellVoltage
                            break;
                        }
                    }
                    break;
                }
                case FcCommon.MSP_OSD_CONFIG: {
                    packetData.daos.writeShort(SettingsCommon.versionCompatibleCode);
                    switch (fcInfo.getFcVariant()){
                        case FcInfo.FC_VARIANT_INAV:{
                            byte driver = buffer.readByte();
                            packetData.daos.writeByte(driver);
                            if (driver == 0) break;
                            packetData.daos.writeByte(buffer.readByte());//videoSystem
                            packetData.daos.writeByte(buffer.readByte());//units
                            packetData.daos.writeByte(buffer.readByte());//rssiAlarm
                            packetData.daos.writeShort(buffer.readShort());//capacityWarning
                            packetData.daos.writeShort(buffer.readShort());//timeAlarm
                            packetData.daos.writeShort(buffer.readShort());//altAlarm
                            packetData.daos.writeShort(buffer.readShort());//distAlarm
                            packetData.daos.writeShort(buffer.readShort());//negAltAlarm
                            int osdItemCount = buffer.getRemaining() / 2;
                            packetData.daos.writeShort(osdItemCount);
                            if (osdItemCount == 0) break;
                            for (int i = 0; i < osdItemCount; i++) {
                                packetData.daos.writeShort(buffer.readShort());//osdItem
                            }
                            break;
                        }
                        case FcInfo.FC_VARIANT_BETAFLIGHT:{
                            buffer.readByte();//osdFlags
                            packetData.daos.writeByte(buffer.readByte());//videoSystem
                            packetData.daos.writeByte(buffer.readByte());//units
                            packetData.daos.writeByte(buffer.readByte());//rssiAlarm
                            packetData.daos.writeShort(buffer.readShort());//capacityWarning
                            buffer.readByte();//unused
                            int osdItemCount = buffer.readUnsignedByteAsInt();
                            packetData.daos.writeShort(buffer.readShort());//altAlarm
                            packetData.daos.writeShort(osdItemCount);
                            if (osdItemCount <= 0) break;
                            for (int i = 0; i < osdItemCount; i++) {
                                packetData.daos.writeShort(buffer.readShort());//osdItem
                            }
                            int osdStatCount = buffer.readUnsignedByteAsInt();
                            packetData.daos.writeByte(osdStatCount);
                            if (osdStatCount > 0){
                                for (int i = 0; i < osdStatCount; i++) {
                                    packetData.daos.writeByte(buffer.readByte());//osdStat
                                }
                            }
                            int osdTimerCount = buffer.readUnsignedByteAsInt();
                            packetData.daos.writeByte(osdTimerCount);
                            if (osdTimerCount > 0){
                                for (int i = 0; i < osdTimerCount; i++) {
                                    packetData.daos.writeShort(buffer.readShort());//osdTimer
                                }
                            }
                            buffer.readShort();//legacyWarnings
                            packetData.daos.writeByte(buffer.readByte());//osdWarningsCount
                            packetData.daos.writeInt(buffer.readInt());//enabledWarnings
                            buffer.readByte();//osdProfileCount
                            packetData.daos.writeByte(buffer.readByte());//osdSelectedProfile
                            buffer.readByte();//overlayRadioMode
                            packetData.daos.writeByte(buffer.readByte());//cameraFrameWidth
                            packetData.daos.writeByte(buffer.readByte());//cameraFrameHeight
                            if (buffer.getRemaining() >= 2) { // 1.46
                                buffer.readShort();//linkQualityAlarm
                            }
                            if (buffer.getRemaining() >= 2) { // 1.47
                                buffer.readShort();//rssiDbmAlarm
                            }
                            break;
                        }
                    }
                    break;
                }
                case FcCommon.MSP_VTX_CONFIG: {
                    int vtxDevice = buffer.readUnsignedByteAsInt();
                    packetData.daos.writeShort(vtxDevice);
                    if (vtxDevice == FcCommon.VTXDEV_UNKNOWN) break;
                    packetData.daos.writeByte(buffer.readByte());//band
                    packetData.daos.writeByte(buffer.readByte());//channel
                    packetData.daos.writeByte(buffer.readByte());//power
                    packetData.daos.writeByte(buffer.readByte());//pitMode
                    if (fcInfo.getFcVariant() == FcInfo.FC_VARIANT_BETAFLIGHT) packetData.daos.writeShort(buffer.readShort());//frequency
                    packetData.daos.writeByte(buffer.readByte());//deviceIsReady
                    packetData.daos.writeByte(buffer.readByte());//lowPowerDisarm
                    break;
                }
                case FcCommon.MSP_BATTERY_STATE: {
                    packetData.daos.writeByte(buffer.readByte());//cellCount
                    packetData.daos.writeShort(buffer.readShort());//capacity
                    buffer.readByte();//legacyVoltage
                    packetData.daos.writeShort(buffer.readShort());//mahDrawn
                    packetData.daos.writeShort(buffer.readShort());//amperage
                    packetData.daos.writeByte(buffer.readByte());//batteryState
                    packetData.daos.writeShort(buffer.readShort());//voltage
                    break;
                }
                case FcCommon.MSP_STATUS: {
                    packetData.daos.writeShort(buffer.readShort());//cycleTime
                    packetData.daos.writeShort(buffer.readShort());//i2cErrorCount
                    packetData.daos.writeShort(buffer.readShort());//sensorStatus
                    switch (fcInfo.getFcVariant()){
                        case FcInfo.FC_VARIANT_INAV:{
                            buffer.readInt();//legacyModeFlags
                            packetData.daos.writeByte(buffer.readByte());//configProfile
                            break;
                        }
                        case FcInfo.FC_VARIANT_BETAFLIGHT:{
                            int firstModeFlag = buffer.readInt();
                            packetData.daos.writeByte(buffer.readByte());//currentPidProfileIndex
                            packetData.daos.writeShort(buffer.readShort());//averageSystemLoad
                            buffer.readShort();//unused
                            byte[] modeFlags;
                            int modeFlagsSize = buffer.readByte()+4;
                            packetData.daos.writeByte(modeFlagsSize);
                            if (modeFlagsSize > 0) {
                                modeFlags = new byte[modeFlagsSize];
                                modeFlags[0] = (byte) (firstModeFlag & 0xFF);
                                modeFlags[1] = (byte) (firstModeFlag >> 8 & 0xFF);
                                modeFlags[2] = (byte) (firstModeFlag >> 16 & 0xFF);
                                modeFlags[3] = (byte) (firstModeFlag >> 24 & 0xFF);
                                for (int i = 4; i < modeFlagsSize; i++) {
                                    modeFlags[i] = buffer.readByte();
                                }
                                for (int i = 0; i < modeFlagsSize; i++) {
                                    packetData.daos.writeByte(modeFlags[i]);
                                }
                            }
                            buffer.readByte();//armingFlagsCount
                            packetData.daos.writeInt(buffer.readInt());//armingFlags
                            packetData.daos.writeByte(buffer.readByte());//configStateFlags
                            short coreTemperatureCelsius = 0;
                            if (buffer.getRemaining() >= 2) { // 1.46
                                coreTemperatureCelsius = buffer.readShort();
                            }
                            packetData.daos.writeShort(coreTemperatureCelsius);
                            break;
                        }
                    }
                    break;
                }
                case FcCommon.MSP_COMP_GPS: {
                    packetData.daos.writeShort(buffer.readShort());//distanceToHome
                    packetData.daos.writeShort(buffer.readShort());//directionToHome
                    packetData.daos.writeByte(buffer.readByte());//gpsHeartbeat
                    break;
                }
                case FcCommon.MSP_OSD_CANVAS: {
                    packetData.daos.writeByte(buffer.readByte());//cols
                    packetData.daos.writeByte(buffer.readByte());//rows
                    break;
                }
                case FcCommon.MSP2_INAV_STATUS: {
                    packetData.daos.writeShort(buffer.readShort());//cycleTime
                    packetData.daos.writeShort(buffer.readShort());//i2cErrorCount
                    packetData.daos.writeShort(buffer.readShort());//sensorStatus
                    packetData.daos.writeShort(buffer.readShort());//averageSystemLoad
                    packetData.daos.writeByte(buffer.readByte());//profiles
                    packetData.daos.writeInt(buffer.readInt());//armingFlags
                    int modeFlagsSize = (int)Math.ceil((buffer.getRemaining() - 1) / 4.0);
                    packetData.daos.writeByte(modeFlagsSize);
                    if (modeFlagsSize > 0) {
                        for (int i = 0; i < modeFlagsSize; i++) {
                            packetData.daos.writeInt(buffer.readInt());//modeFlags
                        }
                    }
                    break;
                }
                case FcCommon.MSP2_INAV_ANALOG: {
                    packetData.daos.writeByte(buffer.readByte());//batteryInfo
                    packetData.daos.writeShort(buffer.readShort());//voltage
                    packetData.daos.writeShort(buffer.readShort());//amperage
                    packetData.daos.writeInt(buffer.readInt());//power
                    packetData.daos.writeInt(buffer.readInt());//mahDrawn
                    packetData.daos.writeInt(buffer.readInt());//mwhDrawn
                    packetData.daos.writeInt(buffer.readInt());//batteryRemainingCapacity
                    packetData.daos.writeByte(buffer.readByte());//batteryPercentage
                    packetData.daos.writeShort(buffer.readShort());//rssi
                    break;
                }
                case FcCommon.DD_TIMERS: {
                    packetData.daos.writeInt(buffer.readInt());//onTime
                    packetData.daos.writeInt(buffer.readInt());//flyTime
                    packetData.daos.writeInt(buffer.readInt());//lastArmTime
                    break;
                }
                case FcCommon.DD_PHONE_BATTERY_STATE: {
                    packetData.daos.writeByte(buffer.readByte());//batteryLevel
                    packetData.daos.writeBoolean(buffer.readBoolean());//isCharging
                    break;
                }
                case FcCommon.DD_CAMERA_FPS: {
                    packetData.daos.writeShort(buffer.readShort());//cameraFps
                    break;
                }
                case FcCommon.DD_VIDEO_BIT_RATE: {
                    packetData.daos.writeFloat(buffer.readFloat());//videoBitRate
                    break;
                }
                case FcCommon.DD_VIDEO_RECORDER_STATE: {
                    packetData.daos.writeBoolean(buffer.readBoolean());//isRecording
                    packetData.daos.writeInt(buffer.readInt());//recordingTimeSec
                    break;
                }
                case FcCommon.DD_VIDEO_RECORDER_START_STOP: {
                    videoStarter.execute(this::startStopRecording); // the muxer's start / stop, off this thread
                    break;
                }
                case FcCommon.DD_NETWORK_STATE: {
                    packetData.daos.writeByte(buffer.readByte());//networkType
                    packetData.daos.writeByte(buffer.readByte());//rssi
                    break;
                }
                // already Big Endian encoded, can be written as a whole array
                case FcCommon.MSP_RAW_GPS:
                case FcCommon.DD_AP_OSD_CONFIG:
                case FcCommon.DD_MAVLINK_MODE:
                case FcCommon.DD_MAVLINK_BATTERY_STATUS:
                case FcCommon.DD_MAVLINK_SYS_STATUS:
                case FcCommon.DD_MAVLINK_STATUS_TEXT:
                case FcCommon.DD_MAVLINK_GPS_RAW_INT:
                case FcCommon.DD_MAVLINK_GLOBAL_POSITION_INT:
                case FcCommon.DD_MAVLINK_HOME_POSITION:
                case FcCommon.DD_MAVLINK_SYSTEM_TIME:
                case FcCommon.DD_MAVLINK_RC_CHANNELS:
                case FcCommon.DD_MAVLINK_SCALED_PRESSURE:
                case FcCommon.DD_AP_VTX_POWER:
                case FcCommon.DD_MAVLINK_VFR_HUD:
                case FcCommon.DD_MAVLINK_ATTITUDE: {
                    packetData.daos.write(buffer.getData());
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            log("fillTelemetryPacketData error: " + e + ", Code: " + buf.code);
        }
    }

    private void sendFcInfo(FcInfo fcInfo){
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.FcInfo);
            packetData.daos.writeByte(fcInfo.getFcVariant());
            packetData.daos.writeByte(fcInfo.getFcVersionMajor());
            packetData.daos.writeByte(fcInfo.getFcVersionMinor());
            packetData.daos.writeByte(fcInfo.getFcVersionPatchLevel());
            packetData.daos.writeByte(fcInfo.getApiProtocolVersion());
            packetData.daos.writeByte(fcInfo.getApiVersionMajor());
            packetData.daos.writeByte(fcInfo.getApiVersionMinor());
            packetData.daos.writeByte(fcInfo.getPlatformType());
            udpSender.sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendFcInfo error: " + e);
        }
    }

    private void sendVideoInitialFrame(byte[] buf) {
        if (videoInitialFrame == null && (buf == null || buf.length == 0)) return;
        if (buf == null) {
            buf = videoInitialFrame;
        } else {
            videoInitialFrame = buf.clone();
        }
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.VideoInitialFrame);
            packetData.daos.writeShort(cameraManager.getCamera().getWidth());
            packetData.daos.writeShort(cameraManager.getCamera().getHeight());
            packetData.daos.writeBoolean(streamEncoder.getCurrentCodecType().equals(MediaCommon.hevcCodecMime));
            packetData.daos.writeBoolean(cameraManager.getCamera().isFrontFacing());
            packetData.daos.write(buf, 0, buf.length);
            udpSender.sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendVideoInitialFrame error: " + e);
        }
    }

    private void sendKeyFrame(byte[] buf) {
        if (socket == null || socket.isClosed()) return;
        if (videoFrameNum == Short.MAX_VALUE) videoFrameNum = 0;
        videoFrameNum++;
        int size = buf.length;
        long gapNs = packetGapNs(size);
        long start = System.nanoTime();
        // DRAUPNE Link: in bad coverage mode the whole key frame goes twice: a packet the 4G uplink dropped from the
        // first copy usually comes in the second (the PC waits for it)
        byte[] parity = keyFramesTwice ? new byte[UdpCommon.packetLength - 9] : null;
        for (int copy = 0; copy < (keyFramesTwice ? 2 : 1); copy++) {
            int offset = 0;
            while (offset < size && udpSender != null) {
                try {
                    synchronized (udpSync) {
                        UdpPacketData packetData = new UdpPacketData(UdpCommon.KeyFrame);
                        packetData.daos.writeShort(videoFrameNum);
                        packetData.daos.writeInt(offset);
                        int headerSize = 9;
                        if (offset == 0) {
                            packetData.daos.writeInt(size);
                            headerSize += 4;
                        }
                        int dataSize = UdpCommon.packetLength - headerSize;
                        int segment = offset;
                        if (size - offset <= dataSize) {
                            packetData.daos.write(buf, offset, size - offset);
                            offset = size;
                        } else {
                            packetData.daos.write(buf, offset, dataSize);
                            offset += dataSize;
                        }
                        if (parity != null && copy == 0) addParity(parity, buf, segment, offset - segment);
                        udpSender.sendPacket(packetData.getData());
                        if (offset < size) udpSync.wait(gapNs / 1_000_000L, (int) (gapNs % 1_000_000L));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    log("sendKeyFrame error: " + e);
                }
            }
            if (parity != null && copy == 0 && udpSender != null) {
                try {
                    sendParity((short) videoFrameNum, size, true, parity);
                } catch (Exception e) {
                    log("sendParity error: " + e);
                }
            }
        }
        long timeMs = (System.nanoTime() - start) / 1000000;
        processFrameSendTime(timeMs);
    }

    /**
     * DRAUPNE Link: the PC decides the bitrate. Here only an emergency brake: a key frame that took over three frame
     * times to leave (pacing spreads it over half a frame time on purpose) means the uplink can't keep up at all.
     */
    private void processFrameSendTime(long timeMs){
        int frameTimeMs = 1000 / Math.max(1, cameraManager.getCamera().getCurrentFps());
        if (timeMs > frameTimeMs * 3){
            streamEncoder.emergencyDown();
        }else{
            streamEncoder.setLockIncreaseBitrate(false);
        }
        if (config.getBitrateLimit() != 0 && streamEncoder.getTargetBitRate() > config.getBitrateLimit()){
            streamEncoder.changeBitRate(false);
        }
    }

    /**
     * DRAUPNE Link: the gap between a frame's packets. Upstream sent them 0.2 ms apart: a key frame left as one burst
     * of 20-100 KB, and a weak 4G uplink dropped its end (the bottom of the picture went grey). Now at four times the
     * bitrate (at least 4 Mbit/s), but a frame's packets spread over half a frame time at most, so frames never queue.
     */
    private long packetGapNs(int frameBytes){
        int packets = Math.max(1, (frameBytes + UdpCommon.packetLength - 1) / UdpCommon.packetLength);
        long paceBps = Math.max(4_000_000L, 4L * streamEncoder.getTargetBitRate());
        long gap = UdpCommon.packetLength * 8L * 1_000_000_000L / paceBps;
        long frameNs = 1_000_000_000L / Math.max(1, cameraManager.getCamera().getCurrentFps());
        return Math.max(200_000L, Math.min(gap, frameNs / 2 / packets));
    }

    private void sendVideoFrame(byte[] buf) {
        if (socket == null || socket.isClosed()) return;
        if (videoFrameNum == Short.MAX_VALUE) videoFrameNum = 0;
        videoFrameNum++;
        int size = buf.length;
        int offset = 0;
        long gapNs = packetGapNs(size);
        byte[] parity = keyFramesTwice ? new byte[UdpCommon.packetLength - 9] : null;
        while (offset < size && udpSender != null) {
            try {
                synchronized (udpSync) {
                    UdpPacketData packetData = new UdpPacketData(UdpCommon.VideoFrame);
                    packetData.daos.writeShort(videoFrameNum);
                    packetData.daos.writeInt(offset);
                    int headerSize = 9;
                    if (offset == 0) {
                        packetData.daos.writeInt(size);
                        headerSize += 4;
                    }
                    int dataSize = UdpCommon.packetLength - headerSize;
                    int segment = offset;
                    if (size - offset <= dataSize) {
                        packetData.daos.write(buf, offset, size - offset);
                        offset = size;
                    } else {
                        packetData.daos.write(buf, offset, dataSize);
                        offset += dataSize;
                    }
                    if (parity != null) addParity(parity, buf, segment, offset - segment);
                    udpSender.sendPacket(packetData.getData());
                    if (offset < size) udpSync.wait(gapNs / 1_000_000L, (int) (gapNs % 1_000_000L));
                }
            } catch (Exception e) {
                e.printStackTrace();
                log("sendVideoFrame error: " + e);
            }
        }
        if (parity != null && udpSender != null) {
            try {
                sendParity((short) videoFrameNum, size, false, parity);
            } catch (Exception e) {
                log("sendParity error: " + e);
            }
        }
    }

    private void sendAudioInitialFrame(byte[] buf) {
        if (audioInitialFrame == null && (buf == null || buf.length == 0)) return;
        if (buf == null) {
            buf = audioInitialFrame;
        } else {
            audioInitialFrame = buf.clone();
        }
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.AudioInitialFrame);
            packetData.daos.writeInt(streamEncoder.getAudioFormat().getSampleRate());
            packetData.daos.writeByte(streamEncoder.getAudioFormat().getChannelCount());
            packetData.daos.writeByte(streamEncoder.getAudioFormat().getEncoding());
            packetData.daos.write(buf, 0, buf.length);
            udpSender.sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendAudioInitialFrame error: " + e);
        }
    }

    private void sendAudioFrame(byte[] buf) {
        if (socket == null || socket.isClosed() || buf == null) return;
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.AudioFrame);
            packetData.daos.write(buf, 0, buf.length);
            udpSender.sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendAudioFrame error: " + e);
        }
    }

    private void sendConfigReceived() {
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.ConfigReceived);
            udpSender.sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendConfigReceived error: " + e);
        }
    }

    private void sendVersionMismatch() {
        try {
            UdpPacketData packetData = new UdpPacketData(UdpCommon.VersionMismatch);
            udpSender.sendPacket(packetData.getData());
        } catch (Exception e) {
            e.printStackTrace();
            log("sendVersionMismatch error: " + e);
        }
    }

    public void close() {
        videoSenderThreadId++;
        audioSenderThreadId++;
        udpThreadsId++;
        if (receiverBuffer != null) {
            receiverBuffer.close();
            receiverBuffer = null;
        }
        if (udpSender != null) {
            udpSender.close();
            udpSender = null;
        }
        if (socket != null) socket.close();
        videoInitialFrame = null;
        audioInitialFrame = null;
        isCameraRestarting = false;
    }
}
