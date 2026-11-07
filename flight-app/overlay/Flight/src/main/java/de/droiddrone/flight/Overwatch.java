/*
 *  This file is part of DRAUPNE Flight, the phone app of DRAUPNE Link (based on DroidDrone).
 *
 *  It is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 *  version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details. You should have received a copy of the GNU General Public License along with it. If not, see
 *  <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.flight;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.MediaCodec;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import de.droiddrone.common.MediaCodecBuffer;
import de.droiddrone.common.MotionDetector;
import de.droiddrone.common.MotionRecord;
import de.droiddrone.common.OverwatchEvent;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PowerFloor;
import de.droiddrone.common.PreRoll;

import static de.droiddrone.common.Logcat.log;

/**
 * pc.36, OVERWATCH SLEEP (DRAUPNE Link 1.0.47, the pilot's spec): the phone sleeps as in deep sleep - screen off, the
 * flight controller's port, the GPS, the link's socket, the telemetry and the microphone closed - but the camera gives
 * the motion detection its small picture (a watch-only session, {@link InternalCamera}) and a partial wake lock keeps
 * the CPU up for it. On a detection that travels ({@link MotionWatch}'s detector: not vegetation; lights at night
 * too) it calls the PC that put it to sleep at once - the ALERT, again every second until that PC acknowledges (a
 * Tailscale path that slept may take seconds) - with the 5 s before it ({@link PreRoll}: small JPEGs, two a second),
 * streams a live preview while the PC's card asks for it (the stream encoder, H.264 at 1 Mbit/s), and records the
 * event in full quality ({@link OverwatchRecorder}) until 30 s without a detection ({@link OverwatchEvent}). After
 * the event the clip waits on the phone until the PC pulls it in chunks and checks it; then it is deleted here.
 * <p>
 * Below 20 % battery or hot ({@link PowerFloor}) it goes to deep sleep by itself and says why. Its packets
 * ({@link OverwatchProtocol}) go from a socket of its own to the PC's link port, never through the link.
 * Everything of an event runs on one thread at background priority; the analysis thread only makes the JPEGs and
 * hands detections over.
 */
final class Overwatch {
    private static volatile int sensitivity = MotionDetector.NORMAL, flags, token;
    private static volatile Overwatch current;

    /** The PC's OVERWATCH request's settings (POWER_MODE mode 3); the token goes into every overwatch packet. */
    static void configure(int sens, int fl, int tok) {
        sensitivity = Math.max(1, Math.min(3, sens));
        flags = fl;
        token = tok;
    }

    /** The sleep hello's flags: overwatching, an event running. */
    static int helloFlags() {
        Overwatch o = current;
        if (o == null || !o.watching) return 0;
        return OverwatchProtocol.HELLO_OVERWATCH | (o.event.isActive() ? OverwatchProtocol.HELLO_EVENT : 0);
    }

    static boolean isWatching() {
        Overwatch o = current;
        return o != null && o.watching;
    }

    /** Overwatch begins (PowerMode, the countdown over). position: where the drone was (Udp.positionNow) before the GPS went. */
    static void begin(DDService service, Udp udp, AudioSource audio, int[] position) {
        Overwatch o = current;
        if (o == null || o.closed) {
            o = new Overwatch(service, udp, audio);
            current = o;
        }
        final Overwatch w = o;
        w.worker.execute(() -> w.watch(position));
    }

    /**
     * Overwatch ends (woken, the floor): the event's clip is finished and kept for the PC, the camera closed. The
     * socket stays while a clip, the motion's end or the floor's word is still to go. Waits up to 3 s for it.
     */
    static void end(String why) {
        Overwatch o = current;
        if (o == null || o.closed) return;
        try {
            o.worker.submit(() -> o.stopWatching(why)).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log("Overwatch end: " + e);
        }
    }

    /** The service stops: everything goes. */
    static void shutdown() {
        Overwatch o = current;
        current = null;
        if (o != null) o.close();
    }

    private final DDService service;
    private final Udp udp;
    private final AudioSource audioSource;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "overwatch");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> ticking;
    private DatagramSocket socket;
    private InetSocketAddress pc;
    private volatile boolean watching, closed;
    private final OverwatchEvent event = new OverwatchEvent();
    private final PreRoll preRoll = new PreRoll();
    private final PowerFloor floor = new PowerFloor();
    private int[] position = {0, 0, 0, 0};
    private long positionAt;
    private long floorCheckAt;
    private int droppedReason, statusLeft;
    private long statusSentAt;

    // ---- the event (worker thread)
    private OverwatchProtocol.Alert alert;
    private boolean alertAcked, preRollSent;
    private long alertSentAt;
    private List<PreRoll.Frame> eventPreRoll;
    private volatile long previewWantedAt = -1;
    private volatile boolean previewing;
    private OverwatchRecorder recorder;
    private File recording;
    private long recordingStartedAt;
    private boolean eventVideo;
    // ---- the motion's end, until the PC has it
    private int endedEvent = -1;
    private long endedAtWall, endedSentAt;
    private boolean endedAcked, endedClip;
    private int endedSends;
    // ---- clips waiting for the PC, oldest first
    private static final class Pending {
        final int event;
        final File file;
        final long size, startedAt, durationMs;
        final byte[] sha256;
        final boolean sound;
        final int width, height;

        Pending(int event, File file, byte[] sha256, long startedAt, long durationMs, boolean sound, int width, int height) {
            this.event = event;
            this.file = file;
            this.size = file.length();
            this.sha256 = sha256;
            this.startedAt = startedAt;
            this.durationMs = durationMs;
            this.sound = sound;
            this.width = width;
            this.height = height;
        }
    }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private long infoSentAt, chunksAskedAt;
    private RandomAccessFile serving;
    private File servingFile;
    // ---- the preview's sender
    private Thread sender;
    private volatile int senderGen;
    private volatile byte[] videoConfig;
    private int unit;

    private Overwatch(DDService service, Udp udp, AudioSource audio) {
        this.service = service;
        this.udp = udp;
        this.audioSource = audio;
    }

    // ------------------------------------------------------------------ watching

    private void watch(int[] where) {
        pc = udp.lastPcAddress();
        if (pc == null) log("Overwatch: no PC to call (this phone hasn't flown with one since the app started) - it watches and records anyway");
        if (!openSocket()) log("Overwatch: no socket - alerts can't go out");
        if (where != null) position = where;
        positionAt = SystemClock.elapsedRealtime();
        floor.reset();
        preRoll.clear();
        droppedReason = 0;
        MotionWatch.overwatch(sensitivity);
        MotionWatch.setSink(this::onPicture);
        udp.overwatchCamera(true);
        watching = true;
        startSender();
        if (ticking == null) ticking = worker.scheduleAtFixedRate(this::tickSafe, 100, 100, TimeUnit.MILLISECONDS);
        log("Overwatch: watching (sensitivity " + sensitivity + (((flags & OverwatchProtocol.MODE_FLAG_SOUND) != 0) ? ", clips with sound" : "") + ")");
    }

    private void stopWatching(String why) {
        if (!watching) return;
        watching = false;
        MotionWatch.setSink(null);
        MotionWatch.overwatch(0);
        Camera camera = service.camera();
        if (camera != null) camera.setAnalysis(false);
        if (event.isActive()) {
            endEvent(); // the clip so far is kept and goes to the PC later
            event.abandon();
        }
        stopSender();
        udp.overwatchCamera(false);
        log("Overwatch: stopped (" + why + ")");
    }

    private void tickSafe() {
        try {
            tick();
        } catch (Throwable e) {
            log("Overwatch tick error: " + e);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis(), mono = SystemClock.elapsedRealtime();
        if (watching) {
            MotionWatch.tick(service.fcArmedState() == FcArmedState.DISARMED, service.camera());
            if (mono >= floorCheckAt) {
                floorCheckAt = mono + PowerFloor.CHECK_MS;
                int[] r = service.batteryReadings();
                int reason = floor.check(r[0], r[1] != 0, r[2], r[3]);
                if (reason != PowerFloor.REASON_NONE) dropToDeepSleep(reason, r[0], r[3]);
            }
        }
        switch (event.tick(mono)) {
            case ENDED:
                endEvent();
                break;
            case STOP_CLIP:
                log("Overwatch: the clip stops at " + OverwatchEvent.MAX_CLIP_MS / 60_000 + " min; the alert goes on");
                finishClip();
                udp.overwatchEventVideo(true, null); // the session without the finished recorder's surface
                break;
            default:
                break;
        }
        if (event.isActive() && !event.isSilenced(mono) && !alertAcked && now - alertSentAt >= OverwatchProtocol.ALERT_RESEND_MS) sendAlert(now);
        previewing = eventVideo && event.isActive() && !event.isSilenced(mono) && previewWantedAt >= 0
                && mono - previewWantedAt < OverwatchProtocol.PREVIEW_FRESH_MS;
        if (endedEvent >= 0 && !endedAcked && now - endedSentAt >= 1000 && endedSends < 120) { // 2 min at most
            endedSends++;
            endedSentAt = now;
            send(OverwatchProtocol.clipEnded(token, endedEvent, endedAtWall, endedClip));
        }
        Pending head = pending.peek();
        if (head != null && now - infoSentAt >= 5000 && now - chunksAskedAt >= 5000) {
            infoSentAt = now;
            send(OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(token, head.event, head.size, head.sha256, head.durationMs,
                    head.startedAt, head.sound, head.width, head.height)));
        }
        if (statusLeft > 0 && now - statusSentAt >= 1000) {
            statusLeft--;
            statusSentAt = now;
            int[] r = service.batteryReadings();
            send(OverwatchProtocol.status(token, OverwatchProtocol.STATE_DROPPED, droppedReason, r[0] < 0 ? 255 : r[0], r[3] == PowerFloor.UNKNOWN ? 0 : r[3]));
        }
        if (!watching && pending.isEmpty() && (endedEvent < 0 || endedAcked || endedSends >= 120) && statusLeft == 0) closeSocket();
    }

    /** The floor: deep sleep now, and the PC told why. */
    private void dropToDeepSleep(int reason, int battery, int tempDeci) {
        log("Overwatch: " + PowerFloor.describe(reason) + " (battery " + battery + " %, " + tempDeci / 10.0 + " C) - deep sleep instead");
        droppedReason = reason;
        statusLeft = 5;
        statusSentAt = 0;
        send(OverwatchProtocol.status(token, OverwatchProtocol.STATE_DROPPED, reason, Math.max(0, battery), tempDeci == PowerFloor.UNKNOWN ? 0 : tempDeci));
        PowerMode.floorDrop(reason);
    }

    // ------------------------------------------------------------------ the pictures (the analysis thread)

    private void onPicture(byte[] y, byte[] u, byte[] v, int w, int h, MotionDetector.Result r, long wallMs) {
        if (!watching) return;
        if (preRoll.due(wallMs)) {
            byte[] jpeg = jpeg(y, u, v, w, h);
            if (jpeg != null) preRoll.add(wallMs, jpeg);
        }
        if (r == null || r.detections.isEmpty()) return;
        MotionRecord.Report report = MotionRecord.of(MotionRecord.FLAG_ACTIVE | (r.night ? MotionRecord.FLAG_NIGHT : 0), MotionWatch.fieldOfView(), r);
        if (report.boxes.isEmpty()) return;
        final MotionRecord.Box box = report.boxes.get(0);
        final boolean night = r.night;
        worker.execute(() -> onDetection(box, night, report.hfovDeg));
    }

    /** I420 to NV21 to a small JPEG (Android's own encoder): 5-12 KB, a few ms on a background thread. */
    private static byte[] jpeg(byte[] y, byte[] u, byte[] v, int w, int h) {
        try {
            int frame = w * h;
            byte[] nv21 = new byte[frame + frame / 2];
            System.arraycopy(y, 0, nv21, 0, frame);
            int q = frame / 4;
            for (int i = 0, o = frame; i < q && o + 1 < nv21.length; i++) {
                nv21[o++] = v[i];
                nv21[o++] = u[i];
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), 55, out);
            return out.toByteArray();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the event (worker thread)

    private void onDetection(MotionRecord.Box box, boolean night, float hfov) {
        if (!watching) return;
        long now = System.currentTimeMillis(), mono = SystemClock.elapsedRealtime();
        OverwatchEvent.Action a = event.onDetection(mono);
        if (a == OverwatchEvent.Action.START) {
            alert = makeAlert(box, night, hfov, now);
            alertAcked = false;
            preRollSent = false;
            eventPreRoll = preRoll.snapshot(now);
            previewWantedAt = -1;
            sendAlert(now);
            startRecording(now);
            log("Overwatch: MOTION - event " + event.event() + (pc == null ? "" : ", calling the PC"));
        } else if (a == OverwatchEvent.Action.ALERT_AGAIN) {
            alert = makeAlert(box, night, hfov, alert == null ? now : alert.startedAt);
            alertAcked = false;
            sendAlert(now);
            log("Overwatch: still moving 10 min after the dismiss - the alert again");
        }
    }

    private OverwatchProtocol.Alert makeAlert(MotionRecord.Box b, boolean night, float hfov, long startedAt) {
        int fl = (b.light() ? OverwatchProtocol.ALERT_LIGHT : 0) | (night ? OverwatchProtocol.ALERT_NIGHT : 0)
                | (event.alertNo() > 0 ? OverwatchProtocol.ALERT_AGAIN : 0);
        int[] p = position;
        long ageS = p[0] == 0 ? 0xffff : p[3] + (SystemClock.elapsedRealtime() - positionAt) / 1000;
        int[] r = service.batteryReadings();
        return new OverwatchProtocol.Alert(token, event.event(), event.alertNo(), fl, startedAt, b.x, b.y, b.w, b.h, b.angleDeg, hfov,
                p[1], p[2], (int) Math.min(0xffff, ageS), r[0] < 0 ? 255 : r[0], MainActivity.phoneName());
    }

    private void sendAlert(long now) {
        alertSentAt = now;
        if (alert != null) send(OverwatchProtocol.alert(alert));
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

    /** The full-quality recording and the preview's encoder, in the camera's session with the analysis picture. */
    private void startRecording(long now) {
        Camera camera = service.camera();
        Surface surface = null;
        recorder = null;
        if (camera != null && camera.getWidth() > 0) {
            OverwatchRecorder r = new OverwatchRecorder(camera.getWidth(), camera.getHeight(), camera.getTargetFps(), audioSource);
            surface = r.prepare();
            String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date(now)) + "_" + event.event() + ".mp4";
            File dir = new File(service.getExternalMediaDirs()[0], "Overwatch");
            boolean sound = (flags & OverwatchProtocol.MODE_FLAG_SOUND) != 0
                    && service.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (surface != null && r.start(new File(dir, name), sound)) {
                recorder = r;
                recording = new File(dir, name);
                recordingStartedAt = now;
            } else {
                r.stop();
                surface = null;
                log("Overwatch: no full-quality clip of this event (the recorder didn't start); the PC keeps the preview");
            }
        }
        udp.overwatchEventVideo(true, surface);
        eventVideo = true;
    }

    /** The recording ends: the file joins the clips waiting for the PC (its SHA-256 worked out here, once). */
    private void finishClip() {
        OverwatchRecorder r = recorder;
        recorder = null;
        if (r == null) return;
        File f = r.stop();
        if (f == null || !f.isFile() || f.length() == 0) {
            log("Overwatch: the clip came out empty");
            return;
        }
        byte[] sha = sha256(f);
        if (sha == null) return;
        pending.add(new Pending(event.event(), f, sha, alert == null ? recordingStartedAt : alert.startedAt, r.durationMs(), r.hasSound(), r.width(), r.height()));
        infoSentAt = 0; // announced at the next tick
        log("Overwatch: clip " + f.getName() + " (" + f.length() / 1024 + " KB) waits for the PC");
    }

    /** MOTION ENDED: the clip finished, back to the analysis picture alone; the PC is told until it acknowledges. */
    private void endEvent() {
        boolean hadRecorder = recorder != null;
        finishClip();
        if (eventVideo) {
            eventVideo = false;
            previewing = false;
            if (watching) udp.overwatchEventVideo(false, null);
        }
        endedEvent = event.event();
        endedAtWall = System.currentTimeMillis();
        endedAcked = false;
        endedClip = hadRecorder && !pending.isEmpty() && pending.peekLast().event == endedEvent;
        endedSends = 0;
        endedSentAt = 0;
        log("Overwatch: the motion ended (event " + endedEvent + ")");
    }

    private static byte[] sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return md.digest();
        } catch (Exception e) {
            log("Overwatch: the clip can't be read: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------ the preview's sender

    /**
     * Takes the stream encoder's frames while overwatch runs: sent while the PC's card wants them, dropped otherwise
     * (the queue must not grow while a USB camera's normal session encodes all along). After a pause it waits for a
     * key frame and sends the config first.
     */
    private void startSender() {
        stopSender();
        final int gen = ++senderGen;
        sender = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            StreamEncoder enc = service.streamEncoder();
            boolean needKey = true;
            while (gen == senderGen && enc != null) {
                try {
                    MediaCodecBuffer b = enc.videoStreamOutputBuffer.poll(100, TimeUnit.MILLISECONDS);
                    if (b == null) continue;
                    if ((b.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        videoConfig = b.data;
                        needKey = true;
                        continue;
                    }
                    if (!previewing) {
                        needKey = true;
                        continue;
                    }
                    boolean key = (b.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (needKey) {
                        if (!key) {
                            enc.requestKeyFrame();
                            continue;
                        }
                        byte[] cfg = videoConfig;
                        if (cfg != null) sendUnit(OverwatchProtocol.UNIT_CONFIG, cfg);
                        needKey = false;
                    }
                    sendUnit(key ? OverwatchProtocol.UNIT_KEY_FRAME : OverwatchProtocol.UNIT_FRAME, b.data);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // a frame lost; the next one goes
                }
            }
        }, "overwatchPreview");
        sender.setDaemon(true);
        sender.start();
    }

    private void stopSender() {
        senderGen++;
        sender = null;
    }

    private synchronized void sendUnit(int type, byte[] data) {
        int u = unit = (unit + 1) & 0xffff;
        List<byte[]> parts = OverwatchProtocol.fragments(token, event.event(), u, type, data);
        for (int i = 0; i < parts.size(); i++) {
            send(parts.get(i));
            if (i % 8 == 7) SystemClock.sleep(1); // a key frame's 60 packets not all in one burst
        }
    }

    // ------------------------------------------------------------------ the PC's commands

    private void onCommand(OverwatchProtocol.Command c) {
        long mono = SystemClock.elapsedRealtime();
        int ev = event.event();
        switch (c.cmd) {
            case OverwatchProtocol.CMD_ACK:
                if (c.event == ev && event.isActive()) {
                    if (!alertAcked) log("Overwatch: the PC has the alert");
                    alertAcked = true;
                    if (!preRollSent) sendPreRoll();
                }
                break;
            case OverwatchProtocol.CMD_PREVIEW:
                if (c.event == ev) previewWantedAt = mono;
                break;
            case OverwatchProtocol.CMD_PREROLL:
                if (c.event == ev) sendPreRoll();
                break;
            case OverwatchProtocol.CMD_DISMISS:
                if (c.event == ev && event.isActive()) {
                    event.dismiss(mono);
                    previewWantedAt = -1;
                    log("Overwatch: dismissed by the PC - silent until the motion ends (10 min at most)");
                }
                break;
            case OverwatchProtocol.CMD_CONNECT:
                if (watching) {
                    log("Overwatch: the PC connects (CONNECT on its alert)");
                    PowerMode.request(PowerMode.NORMAL, 0, "the PC's CONNECT on an overwatch alert");
                }
                break;
            case OverwatchProtocol.CMD_ENDED_ACK:
                if (c.event == endedEvent) endedAcked = true;
                break;
            case OverwatchProtocol.CMD_CLIP_GET:
                serveChunks(c);
                break;
            case OverwatchProtocol.CMD_CLIP_DONE: {
                Pending head = pending.peek();
                if (head == null || head.event != c.event || c.offset == 0) break;
                pending.poll();
                closeServing();
                if (head.file.delete()) log("Overwatch: the PC has clip " + head.file.getName() + " (checked): deleted here");
                else log("Overwatch: the PC has clip " + head.file.getName() + "; it couldn't be deleted here");
                infoSentAt = 0;
                break;
            }
            case OverwatchProtocol.CMD_STATUS_ACK:
                statusLeft = 0;
                break;
            default:
                break;
        }
    }

    /** The chunks asked for, from the oldest clip waiting - never while the flight controller may be armed. */
    private void serveChunks(OverwatchProtocol.Command c) {
        chunksAskedAt = System.currentTimeMillis();
        Pending head = pending.peek();
        if (head == null || head.event != c.event || service.isFcArmed()) return;
        try {
            if (serving == null || !head.file.equals(servingFile)) {
                closeServing();
                serving = new RandomAccessFile(head.file, "r");
                servingFile = head.file;
            }
            byte[] buf = new byte[OverwatchProtocol.FRAGMENT];
            for (int i = 0; i < Math.min(c.count, 256); i++) {
                long off = c.offset + (long) i * OverwatchProtocol.FRAGMENT;
                if (off >= head.size) break;
                int n = (int) Math.min(OverwatchProtocol.FRAGMENT, head.size - off);
                serving.seek(off);
                serving.readFully(buf, 0, n);
                send(OverwatchProtocol.clipChunk(token, head.event, off, buf, 0, n));
                if (i % 8 == 7) SystemClock.sleep(1);
            }
        } catch (IOException e) {
            log("Overwatch: the clip can't be read: " + e);
            closeServing();
        }
    }

    private void closeServing() {
        try {
            if (serving != null) serving.close();
        } catch (IOException ignored) {
        }
        serving = null;
        servingFile = null;
    }

    // ------------------------------------------------------------------ the socket

    private boolean openSocket() {
        if (socket != null && !socket.isClosed()) return true;
        try {
            socket = new DatagramSocket(); // a port of its own: never the link's
            socket.setSendBufferSize(256 * 1024);
            final DatagramSocket s = socket;
            Thread t = new Thread(() -> receive(s), "overwatchReceive");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException e) {
            log("Overwatch socket error: " + e);
            socket = null;
            return false;
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
                if (c == null || c.token != token || token == 0) continue; // only the PC that asked, by its token
                worker.execute(() -> onCommand(c));
            } catch (IOException e) {
                if (!s.isClosed()) log("Overwatch receive error: " + e);
            } catch (RuntimeException e) {
                // the worker is gone (shut down): nothing to do
            }
        }
    }

    private void send(byte[] p) {
        DatagramSocket s = socket;
        InetSocketAddress to = pc;
        if (s == null || to == null || s.isClosed()) return;
        try {
            s.send(new DatagramPacket(p, p.length, to));
        } catch (IOException e) {
            // no network now (4G gone a moment): the alert and the rest go again
        }
    }

    private void closeSocket() {
        if (socket == null) return;
        socket.close();
        socket = null;
        if (ticking != null) ticking.cancel(false);
        ticking = null;
        closed = true;
        if (current == this) current = null;
        closeServing();
        worker.shutdown();
    }

    private void close() {
        try {
            worker.submit(() -> {
                stopWatching("the service stops");
                OverwatchRecorder r = recorder;
                recorder = null;
                if (r != null) r.stop();
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        stopSender();
        if (socket != null) socket.close();
        socket = null;
        closed = true;
        closeServing();
        worker.shutdownNow();
    }
}
