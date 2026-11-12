package de.droiddrone.pc.fleet;

import de.droiddrone.pc.video.H264Encoder;
import de.droiddrone.common.MotionRecord;
import de.droiddrone.common.OverwatchEvent;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PreRoll;
import de.droiddrone.pc.video.StreamRecorder;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Consumer;

/**
 * A virtual drone's OVERWATCH SLEEP (DRAUPNE Flight's Overwatch, pc.36-38), on the shared Common parts: the event's
 * rules (OverwatchEvent), the pre-roll (PreRoll) and every packet (OverwatchProtocol). While it watches, the drone's
 * small analysis picture goes through the real MotionDetector ({@link VirtualDrone}); a detection that travels calls
 * the PC that put it to sleep - the ALERT, again every second until acknowledged - with the 5 s before it as JPEGs,
 * a live preview while the PC's card asks (H.264, 640x360, 1 Mbit/s), and records the event until 30 s without a
 * detection. The clip then waits until the PC pulls it in chunks and checks its SHA-256; then it is deleted here.
 * <p>
 * What differs from the phone: the clip is one file per event (no 60 s segments), written as MKV (the PC's player reads
 * any container) at the preview's quality, without sound; no battery or heat floor. Its packets go from a socket of
 * its own on the drone's loopback address, as the phone's overwatch socket is its own.
 */
final class VirtualOverwatch {
    static final int PREVIEW_W = 640, PREVIEW_H = 360, PREVIEW_FPS = 15, PREVIEW_BPS = 1_000_000;

    private final VirtualDrone drone;
    private final Consumer<String> log;
    private volatile int sensitivity = 2, flags, token;
    private volatile boolean watching;
    private volatile DatagramSocket socket;
    private volatile InetSocketAddress pc;
    private final OverwatchEvent event = new OverwatchEvent();
    private final PreRoll preRoll = new PreRoll();
    private final Object lock = new Object();

    // the event (under lock)
    private OverwatchProtocol.Alert alert;
    private boolean alertAcked, preRollSent;
    private long alertSentAt;
    private List<PreRoll.Frame> eventPreRoll;
    private volatile long previewWantedAt = -1;
    private volatile Thread eventVideo;
    private volatile int eventGen;
    private int unit;
    private int endedEvent = -1, endedSends;
    private long endedAtWall, endedSentAt;
    private boolean endedAcked, endedClip;
    /** The clip waiting for the PC (one at a time), and when its INFO went last. */
    private Clip waiting;
    private long infoSentAt, chunksAskedAt;
    private volatile String lastNews = "";

    private record Clip(Path file, int event, long size, byte[] sha256, long durationMs, long startedAt, int width, int height) {}

    VirtualOverwatch(VirtualDrone drone, Consumer<String> log) {
        this.drone = drone;
        this.log = log;
    }

    /** The PC's OVERWATCH request's settings (POWER_MODE mode 3). */
    void configure(int sens, int fl, int tok) {
        sensitivity = Math.max(1, Math.min(3, sens));
        flags = fl;
        token = tok;
    }

    int sensitivity() {
        return sensitivity;
    }

    boolean isWatching() {
        return watching;
    }

    /** PHONE_POWER's caps: screen lock, exact alarms, battery optimisation off, the camera can give the analysis picture. */
    int caps() {
        return 1 | 2 | 4 | 8;
    }

    int helloFlags() {
        if (!watching) return 0;
        return OverwatchProtocol.HELLO_OVERWATCH | (event.isActive() ? OverwatchProtocol.HELLO_EVENT : 0);
    }

    String status() {
        if (!watching && waiting == null && endedEvent < 0) return "";
        synchronized (lock) {
            String s = watching ? (event.isActive() ? ", EVENT " + event.event() + (alertAcked ? " (PC has it)" : " (calling the PC)") : ", watching") : "";
            if (waiting != null) s += ", clip waiting (" + waiting.size / 1024 + " KB)";
            if (!lastNews.isEmpty()) s += ", " + lastNews;
            return s;
        }
    }

    // ------------------------------------------------------------------ watching

    /** Overwatch begins (the countdown over): the PC it calls is where the drone last flew from. */
    void begin(InetSocketAddress pcAddress) {
        pc = pcAddress;
        if (pc == null) log.accept("overwatch: no PC to call (none flew it yet) - watching anyway");
        openSocket();
        preRoll.clear();
        watching = true;
        log.accept("overwatch: watching (sensitivity " + sensitivity + ")");
    }

    /** Overwatch ends (woken): a running event's clip is finished and kept for the PC. */
    void end(String why) {
        if (!watching) return;
        watching = false;
        synchronized (lock) {
            if (event.isActive()) {
                endEvent();
                event.abandon();
            }
        }
        log.accept("overwatch: stopped (" + why + ")");
    }

    void shutdown() {
        watching = false;
        eventGen++;
        DatagramSocket s = socket;
        socket = null;
        if (s != null) s.close();
    }

    private void openSocket() {
        if (socket != null && !socket.isClosed()) return;
        try {
            DatagramSocket s = new DatagramSocket(new InetSocketAddress(drone.address(), 0));
            s.setSendBufferSize(256 * 1024);
            socket = s;
            Thread t = new Thread(() -> receive(s), "fleetOverwatch" + drone.slot());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (IOException e) {
            log.accept("overwatch: no socket: " + e.getMessage());
        }
    }

    private void send(byte[] data) {
        DatagramSocket s = socket;
        InetSocketAddress to = pc;
        if (s == null || to == null || drone.link.silent(System.currentTimeMillis())) return;
        try {
            s.send(new DatagramPacket(data, data.length, to));
        } catch (IOException ignored) {
        }
    }

    private void receive(DatagramSocket s) {
        byte[] buf = new byte[1500];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (!s.isClosed()) {
            try {
                p.setLength(buf.length);
                s.receive(p);
                OverwatchProtocol.Command c = OverwatchProtocol.readCommand(p.getData(), p.getLength());
                if (c == null || c.token != token || token == 0) continue;
                onCommand(c);
            } catch (IOException e) {
                return;
            } catch (RuntimeException e) {
                log.accept("overwatch: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ pictures and detections (the drone's tick thread)

    /**
     * The analysis picture and the real MotionDetector's verdict on it, five a second while watching. Only that
     * verdict raises an alert (1.0.50, the pilot: nothing here may tell the program to warn - something must move in
     * the picture and the detection find it, as on the phone; ALERT NOW, which faked a detection, is gone).
     */
    void onPicture(BufferedImage analysis, MotionRecord.Report report, long now) {
        if (!watching) return;
        if (preRoll.due(now, report != null && !report.boxes.isEmpty())) {
            byte[] jpeg = jpeg(analysis);
            if (jpeg != null) preRoll.add(now, jpeg);
        }
        if (report != null) sendBoxes(report, now);
        if (report == null || report.boxes.isEmpty()) return;
        onDetection(report.boxes.get(0), report.night(), report.hfovDeg, now);
    }

    /** When the boxes last went with the preview; whether that was none (an empty set goes once). As the phone (pc.40). */
    private long boxesSentAt;
    private boolean boxesEmpty = true;

    /** The detector's boxes now, with the live preview: the PC's card box follows the thing (UNIT_BOXES). */
    private void sendBoxes(MotionRecord.Report report, long now) {
        boolean previewing;
        synchronized (lock) {
            previewing = event.isActive() && previewWantedAt >= 0 && now - previewWantedAt < OverwatchProtocol.PREVIEW_FRESH_MS
                    && !event.isSilenced(now);
        }
        if (!previewing) return;
        boolean none = report.boxes.isEmpty();
        if (none && boxesEmpty || now - boxesSentAt < 250 && !(none && !boxesEmpty)) return;
        boxesSentAt = now;
        boxesEmpty = none;
        List<float[]> boxes = new java.util.ArrayList<>();
        for (MotionRecord.Box b : report.boxes) boxes.add(new float[]{b.x, b.y, b.w, b.h, b.light() ? 1 : 0});
        sendUnit(OverwatchProtocol.UNIT_BOXES, OverwatchProtocol.boxesUnit(System.currentTimeMillis(), boxes));
    }

    /** The detector found something that travels: onPicture is the only way here. */
    private void onDetection(MotionRecord.Box box, boolean night, float hfov, long now) {
        synchronized (lock) {
            OverwatchEvent.Action a = event.onDetection(now);
            if (a == OverwatchEvent.Action.START) {
                alert = makeAlert(box, night, hfov, now);
                alertAcked = false;
                preRollSent = false;
                eventPreRoll = preRoll.snapshot(now);
                previewWantedAt = -1;
                sendAlert(now);
                startEventVideo(now);
                log.accept("overwatch: MOTION - event " + event.event() + (pc == null ? "" : ", calling the PC"));
                ImpairedSocket.LATER.execute(drone::noteAlert); // the tour's scenario hears of it (never under this lock)
                lastNews = "motion";
            } else if (a == OverwatchEvent.Action.ALERT_AGAIN) {
                alert = makeAlert(box, night, hfov, alert == null ? now : alert.startedAt);
                alertAcked = false;
                sendAlert(now);
            }
        }
    }

    private OverwatchProtocol.Alert makeAlert(MotionRecord.Box b, boolean night, float hfov, long startedAt) {
        int fl = (b.light() ? OverwatchProtocol.ALERT_LIGHT : 0) | (night ? OverwatchProtocol.ALERT_NIGHT : 0)
                | (event.alertNo() > 0 ? OverwatchProtocol.ALERT_AGAIN : 0);
        FlightModel fc = drone.fc;
        return new OverwatchProtocol.Alert(token, event.event(), event.alertNo(), fl, startedAt, b.x, b.y, b.w, b.h, b.angleDeg, hfov,
                (int) Math.round(fc.latDeg() * 1e7), (int) Math.round(fc.lonDeg() * 1e7), 0, drone.phoneBatteryPercent(),
                drone.name());
    }

    private void sendAlert(long now) {
        alertSentAt = now;
        if (alert != null) send(OverwatchProtocol.alert(alert));
    }

    /** Every tick of the drone: the event's end, the alert's resends, the motion's end until acknowledged, the clip's INFO. */
    void tick(long now) {
        synchronized (lock) {
            if (event.tick(now) == OverwatchEvent.Action.ENDED) endEvent();
            if (event.isActive() && !event.isSilenced(now) && !alertAcked && now - alertSentAt >= OverwatchProtocol.ALERT_RESEND_MS) sendAlert(now);
            if (endedEvent >= 0 && !endedAcked && now - endedSentAt >= 1000 && endedSends < 120) {
                endedSends++;
                endedSentAt = now;
                send(OverwatchProtocol.clipEnded(token, endedEvent, endedAtWall, endedClip));
            }
            Clip c = waiting;
            if (c != null && now - infoSentAt >= 5000 && now - chunksAskedAt >= 5000) {
                infoSentAt = now;
                send(OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(token, c.event, c.size, c.sha256, c.durationMs, c.startedAt,
                        false, c.width, c.height, 1, c.startedAt)));
            }
        }
    }

    // ------------------------------------------------------------------ the event's video: preview and clip

    private void startEventVideo(long startedAt) {
        int gen = ++eventGen;
        Path file;
        try {
            file = Files.createTempFile("draupne-fleet-clip-", ".mkv");
        } catch (IOException e) {
            log.accept("overwatch: no clip file: " + e.getMessage());
            file = null;
        }
        Path clipFile = file;
        Thread t = new Thread(() -> eventVideoRun(gen, clipFile, startedAt), "fleetOverwatchVideo" + drone.slot());
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        eventVideo = t;
        t.start();
    }

    private void eventVideoRun(int gen, Path file, long startedAt) {
        BufferedImage img = new BufferedImage(PREVIEW_W, PREVIEW_H, BufferedImage.TYPE_INT_RGB);
        StreamRecorder clip = null;
        long next = System.nanoTime();
        boolean needKey = true;
        try (H264Encoder enc = new H264Encoder(PREVIEW_W, PREVIEW_H, PREVIEW_FPS, PREVIEW_BPS)) {
            if (file != null) clip = new StreamRecorder(file, false, PREVIEW_W, PREVIEW_H, enc.config());
            boolean first = true;
            while (gen == eventGen) {
                long now = System.currentTimeMillis();
                drone.scene.render(img, drone.view(), now, "OVERWATCH EVENT " + event.event());
                boolean previewing = previewWantedAt >= 0 && now - previewWantedAt < OverwatchProtocol.PREVIEW_FRESH_MS && !event.isSilenced(now);
                boolean wantKey = first || previewing && needKey;
                first = false;
                for (H264Encoder.Packet p : enc.encode(Scene.pixels(img), wantKey)) {
                    if (clip != null) clip.write(p.data(), p.keyFrame(), now);
                    if (!previewing) {
                        needKey = true;
                        continue;
                    }
                    if (needKey) {
                        if (!p.keyFrame()) continue;
                        sendUnit(OverwatchProtocol.UNIT_CONFIG, enc.config());
                        needKey = false;
                    }
                    sendUnit(p.keyFrame() ? OverwatchProtocol.UNIT_KEY_FRAME : OverwatchProtocol.UNIT_FRAME, p.data());
                }
                next += 1_000_000_000L / PREVIEW_FPS;
                long wait = next - System.nanoTime();
                if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
            }
        } catch (IOException | RuntimeException e) {
            log.accept("overwatch: event video stopped: " + e.getMessage());
        } finally {
            if (clip != null) {
                clip.close();
                finishClip(file, startedAt);
            }
        }
    }

    private void finishClip(Path file, long startedAt) {
        try {
            long size = Files.size(file);
            if (size == 0) {
                Files.deleteIfExists(file);
                return;
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] sum = sha.digest(Files.readAllBytes(file));
            synchronized (lock) {
                Clip old = waiting;
                if (old != null) Files.deleteIfExists(old.file()); // one at a time here: the newest
                waiting = new Clip(file, event.event(), size, sum, Math.max(0, System.currentTimeMillis() - startedAt), startedAt, PREVIEW_W, PREVIEW_H);
                infoSentAt = 0;
            }
            log.accept("overwatch: clip of " + size / 1024 + " KB waits for the PC");
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            log.accept("overwatch: the clip can't be read: " + e.getMessage());
        }
    }

    /** Under lock: the motion ended (or overwatch did): the video stops, the clip goes into waiting, the PC is told. */
    private void endEvent() {
        boolean hadVideo = eventVideo != null;
        eventGen++;
        eventVideo = null;
        previewWantedAt = -1;
        endedEvent = event.event();
        endedAtWall = System.currentTimeMillis();
        endedAcked = false;
        endedClip = hadVideo;
        endedSends = 0;
        endedSentAt = 0;
        lastNews = "motion ended";
        log.accept("overwatch: motion ended (event " + endedEvent + ")");
    }

    private void sendUnit(int type, byte[] data) {
        int u;
        synchronized (lock) {
            u = unit = (unit + 1) & 0xffff;
        }
        List<byte[]> parts = OverwatchProtocol.fragments(token, event.event(), u, type, data);
        for (int i = 0; i < parts.size(); i++) {
            send(parts.get(i));
            if (i % 8 == 7) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
    }

    private void sendPreRoll() {
        preRollSent = true;
        List<PreRoll.Frame> frames = eventPreRoll;
        if (frames == null) return;
        for (PreRoll.Frame f : frames) {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream(f.jpeg.length + 8);
                DataOutputStream out = new DataOutputStream(b);
                out.writeLong(f.atMs);
                out.write(f.jpeg);
                sendUnit(OverwatchProtocol.UNIT_PREROLL, b.toByteArray());
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] jpeg(BufferedImage img) {
        try {
            BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            ImageIO.write(copy, "jpg", out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the PC's commands

    private void onCommand(OverwatchProtocol.Command c) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            int ev = event.event();
            switch (c.cmd) {
                case OverwatchProtocol.CMD_ACK -> {
                    if (c.event == ev && event.isActive()) {
                        if (!alertAcked) log.accept("overwatch: the PC has the alert");
                        alertAcked = true;
                        lastNews = "PC has the alert";
                        if (!preRollSent) sendPreRoll();
                    }
                }
                case OverwatchProtocol.CMD_PREVIEW -> {
                    if (c.event == ev) previewWantedAt = now;
                }
                case OverwatchProtocol.CMD_PREROLL -> {
                    if (c.event == ev) sendPreRoll();
                }
                case OverwatchProtocol.CMD_DISMISS -> {
                    if (c.event == ev && event.isActive()) {
                        event.dismiss(now);
                        previewWantedAt = -1;
                        lastNews = "dismissed";
                        log.accept("overwatch: dismissed by the PC");
                    }
                }
                case OverwatchProtocol.CMD_CONNECT -> {
                    if (watching) {
                        log.accept("overwatch: PC connects (CONNECT on its alert)");
                        ImpairedSocket.LATER.execute(() -> drone.requestMode(VirtualDrone.NORMAL, 0, "the PC's CONNECT on an overwatch alert"));
                    }
                }
                case OverwatchProtocol.CMD_ENDED_ACK -> {
                    if (c.event == endedEvent) endedAcked = true;
                }
                case OverwatchProtocol.CMD_CLIP_GET -> serveChunks(c, now);
                case OverwatchProtocol.CMD_CLIP_DONE -> {
                    Clip w = waiting;
                    if (c.offset == 0 || w == null || w.event() != c.event) return;
                    waiting = null;
                    try {
                        Files.deleteIfExists(w.file());
                    } catch (IOException ignored) {
                    }
                    lastNews = "the PC has the clip";
                    log.accept("overwatch: PC has the clip (checked), deleted here");
                }
                default -> {
                }
            }
        }
    }

    /** Under lock: the chunks asked for - never while the FC may be armed. */
    private void serveChunks(OverwatchProtocol.Command c, long now) {
        chunksAskedAt = now;
        Clip w = waiting;
        if (w == null || w.event() != c.event || !drone.fcDisarmed()) return;
        try (RandomAccessFile f = new RandomAccessFile(w.file().toFile(), "r")) {
            byte[] buf = new byte[OverwatchProtocol.FRAGMENT];
            for (int i = 0; i < Math.min(c.count, 256); i++) {
                long off = c.offset + (long) i * OverwatchProtocol.FRAGMENT;
                if (off >= w.size()) break;
                int n = (int) Math.min(OverwatchProtocol.FRAGMENT, w.size() - off);
                f.seek(off);
                f.readFully(buf, 0, n);
                send(OverwatchProtocol.clipChunk(token, w.event(), off, buf, 0, n));
                if (i % 8 == 7) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
        } catch (IOException e) {
            log.accept("overwatch: the clip can't be read: " + e.getMessage());
        }
    }
}
