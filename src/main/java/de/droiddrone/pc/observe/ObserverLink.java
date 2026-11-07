package de.droiddrone.pc.observe;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.Observe;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.audio.AudioConfig;
import de.droiddrone.pc.audio.ObservationAudio;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.function.Consumer;

import static de.droiddrone.common.Log.log;

/**
 * One observed phone (1.0.48): a socket of its own (not the link port - this PC flies nothing), OBSERVE once a second
 * with the picture its tile wants, and what comes back handed on - the video packets to the tile's pipeline straight
 * from the receive thread, the sound to the mix, the observer record (and the motion detection's) to a telemetry of its
 * own, the phone's OBSERVE_STATE kept. Never a stick frame, never a Connect: the phone gives an observer no session.
 */
public final class ObserverLink implements AutoCloseable {
    /** The tile's two pictures: the grid's small one, and the sharp one for the selected or enlarged tile. */
    public record Quality(int width, int height, int fps, int maxKbps) {
        public static final Quality GRID = new Quality(Observe.GRID_WIDTH, Observe.GRID_HEIGHT, Observe.GRID_FPS, Observe.GRID_KBPS);
    }

    static final long REQUEST_EVERY_MS = 1000;
    /** A config asked for at most this often (the phone also sends it ahead of every key frame). */
    static final long CONFIG_ASK_MS = 1000;
    /** Nothing from the phone for this long: the tile says so. */
    public static final long SILENT_MS = 3000;

    private final String phone;
    private final InetAddress ip;
    private final int port;
    private final String key;
    private final ControlLink.VideoListener video;
    private final ObservationAudio audio;
    private final int audioId;
    private final Consumer<String> events;
    private final FcTelemetry telemetry = new FcTelemetry();
    private DatagramSocket socket;
    private volatile boolean running;
    private volatile Quality quality = Quality.GRID;
    private volatile boolean audioWanted = true;
    private volatile int detection;
    private volatile int askFlags;
    private volatile long lastAskAt, lastConfigAskAt, lastAudioAskAt;
    private volatile Observe.State state;
    private volatile long stateAt, lastPacketAt;
    private volatile AudioConfig audioConfig;
    private volatile boolean videoConfigured;
    private Thread sender;

    /**
     * @param video  the tile's pipeline (its key frame and config requests come back through {@link #askVideoConfig})
     * @param audio  the shared mix, audioId this phone's place in it
     * @param events a line for the main window's log (the phone refused, observation ended): any thread
     */
    public ObserverLink(String phone, InetAddress ip, int port, String key, ControlLink.VideoListener video,
                        ObservationAudio audio, int audioId, Consumer<String> events) {
        this.phone = phone;
        this.ip = ip;
        this.port = port;
        this.key = key;
        this.video = video;
        this.audio = audio;
        this.audioId = audioId;
        this.events = events;
    }

    public void start() throws IOException {
        socket = new DatagramSocket();
        socket.setReceiveBufferSize(UdpCommon.packetLength * 300);
        running = true;
        Thread r = new Thread(this::receiveLoop, "observe-" + phone);
        r.setDaemon(true);
        r.start();
        sender = new Thread(this::requestLoop, "observeAsk-" + phone);
        sender.setDaemon(true);
        sender.setPriority(Thread.MIN_PRIORITY);
        sender.start();
    }

    public String phone() {
        return phone;
    }

    public FcTelemetry telemetry() {
        return telemetry;
    }

    /** The phone's last OBSERVE_STATE, or null before its first. */
    public Observe.State state() {
        return state;
    }

    /** When the last packet of any kind came (MonoClock), 0 before the first. */
    public long lastPacketAt() {
        return lastPacketAt;
    }

    public long stateAt() {
        return stateAt;
    }

    /** The tile's picture; a change is asked for at once. */
    public void setQuality(Quality q) {
        if (q.equals(quality)) return;
        quality = q;
        askNow(0);
    }

    public Quality quality() {
        return quality;
    }

    /** The tile's sound (MUTE for 10 s: the phone may stop it). */
    public void setAudioWanted(boolean on) {
        if (on == audioWanted) return;
        audioWanted = on;
        askNow(0);
    }

    public void setDetection(int sensitivity) {
        detection = sensitivity;
    }

    /** The pipeline's key frame or config request: the next OBSERVE asks for the config and a key frame, at once. */
    public void askVideoConfig() {
        long now = MonoClock.ms();
        if (now - lastConfigAskAt < CONFIG_ASK_MS) return;
        lastConfigAskAt = now;
        askNow(Observe.FLAG_VIDEO_CONFIG);
    }

    private void askNow(int flags) {
        askFlags |= flags;
        Thread s = sender;
        if (s != null) s.interrupt();
    }

    private void requestLoop() {
        while (running) {
            try {
                long now = MonoClock.ms();
                if (now - lastAskAt >= REQUEST_EVERY_MS || askFlags != 0 || lastAskAt == 0) send(now);
                Thread.sleep(REQUEST_EVERY_MS);
            } catch (InterruptedException e) {
                // asked to send now
            }
        }
    }

    private synchronized void send(long now) {
        if (!running) return;
        int flags = askFlags;
        askFlags = 0;
        if (audioWanted) flags |= Observe.FLAG_AUDIO;
        if (!videoConfigured) flags |= Observe.FLAG_VIDEO_CONFIG;
        Quality q = quality;
        byte[] d = Observe.encodeRequest(key, q.width(), q.height(), q.fps(), q.maxKbps(), flags, detection);
        try {
            socket.send(new DatagramPacket(d, d.length, ip, port));
        } catch (IOException e) {
            if (running) log("Observe " + phone + ": " + e.getMessage());
        }
        lastAskAt = now;
    }

    private void receiveLoop() {
        byte[] buf = new byte[UdpCommon.packetLength + 64];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(p);
                if (!p.getAddress().equals(ip)) continue; // the phone's answers only
                int len = p.getLength();
                if (len < 1) continue;
                lastPacketAt = MonoClock.ms();
                onPacket(buf, len);
            } catch (IOException e) {
                if (running) log("Observe " + phone + " receive: " + e.getMessage());
            } catch (RuntimeException e) {
                log("Observe " + phone + ": " + e);
            }
        }
    }

    /** One packet from the phone (receive thread): video straight to the pipeline, as the pilot's link does. */
    void onPacket(byte[] d, int len) {
        byte type = d[0];
        switch (type) {
            case UdpCommon.VideoFrame, UdpCommon.KeyFrame, VIDEO_PARITY, VIDEO_PARITY_GROUP -> {
                if (videoConfigured) ControlLink.deliverVideo(video, d, len);
            }
            case UdpCommon.VideoInitialFrame -> {
                // [0][num i16][width i16][height i16][hevc u8][front u8][codec config]
                if (len < 9) return;
                int w = ((d[3] & 0xff) << 8) | (d[4] & 0xff), h = ((d[5] & 0xff) << 8) | (d[6] & 0xff);
                byte[] cfg = java.util.Arrays.copyOfRange(d, 9, len);
                video.onVideoConfig(w, h, d[7] != 0, d[8] != 0, cfg);
                videoConfigured = true;
            }
            case UdpCommon.AudioInitialFrame -> {
                AudioConfig c = AudioConfig.parse(d, 3, len - 3);
                if (c == null) return;
                audioConfig = c;
                if (audio != null) audio.onConfig(audioId, c);
            }
            case UdpCommon.AudioFrame -> {
                if (len < 2 || audio == null) return;
                if (audioConfig == null) { // frames but no config: asked for, once a second
                    long now = MonoClock.ms();
                    if (now - lastAudioAskAt >= 1000) {
                        lastAudioAskAt = now;
                        askNow(Observe.FLAG_AUDIO_CONFIG);
                    }
                    return;
                }
                audio.onFrame(audioId, java.util.Arrays.copyOfRange(d, 1, len));
            }
            case UdpCommon.TelemetryData -> {
                int at = UdpCommon.isPacketNumbered(type) ? 3 : 1;
                if (len > at) telemetry.parse(new DataReader(java.util.Arrays.copyOfRange(d, at, len), true), null);
            }
            case Observe.STATE -> {
                Observe.State s = Observe.decodeState(d, len);
                if (s == null) return;
                Observe.State before = state;
                state = s;
                stateAt = MonoClock.ms();
                if (s.state != Observe.STREAMING && (before == null || before.state != s.state)) {
                    events.accept(phone + ": no observation - " + Observe.stateName(s.state) + ".");
                }
            }
            default -> {
                // nothing else is meant for an observer
            }
        }
    }

    static final byte VIDEO_PARITY = 0x6C, VIDEO_PARITY_GROUP = 0x71;

    /** OBSERVE_STOP twice (a lost one leaves the phone streaming 3 s until it expires us), then the socket closed. */
    @Override
    public void close() {
        if (!running) return;
        running = false;
        byte[] stop = Observe.encodeStop(key);
        try {
            socket.send(new DatagramPacket(stop, stop.length, ip, port));
            socket.send(new DatagramPacket(stop, stop.length, ip, port));
        } catch (IOException ignored) {
        }
        socket.close();
        Thread s = sender;
        if (s != null) s.interrupt();
        if (audio != null) audio.removeSource(audioId);
    }
}
