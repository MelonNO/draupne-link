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
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.droiddrone.common.CameraWatchdog;
import de.droiddrone.common.ClipStore;
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
 * <p>
 * pc.38: the clips live in {@link ClipStore} - on disk with their facts, recorded in segments, scanned at the app's
 * start, within a quota, a stuck one given up after its attempts - and are hashed on a thread of their own, so ending
 * overwatch never waits on a long clip (the review's M19, M20, L3). The recorder's surface leaves the camera's session
 * before the recorder stops (H10).
 */
final class Overwatch {
    private static volatile int sensitivity = MotionDetector.NORMAL, flags, token;
    private static volatile Overwatch current;
    private static ClipStore clips;
    /** pc.38: clips are hashed (and the folder scanned) here, never on the overwatch thread. */
    private static final ExecutorService hasher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "overwatchHash");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger hashing = new AtomicInteger();

    /** pc.38: the clip folder, scanned once (on the hashing thread) the first time it is asked for - at the app's start. */
    static synchronized ClipStore clips(android.content.Context c) {
        if (clips == null) {
            File[] media = c.getExternalMediaDirs();
            File base = media != null && media.length > 0 && media[0] != null ? media[0] : c.getFilesDir();
            final ClipStore store = new ClipStore(new File(base, "Overwatch"));
            clips = store;
            hashing.incrementAndGet();
            hasher.execute(() -> {
                try {
                    store.scan(line -> log(line));
                    for (ClipStore.Clip gone : store.enforceQuota(usableSpace(store.dir()))) {
                        log("Overwatch clips: " + gone.file.getName() + " deleted - over the folder's quota");
                    }
                } catch (RuntimeException e) {
                    log("Overwatch clips: the scan stopped: " + e);
                } finally {
                    hashing.decrementAndGet();
                }
            });
        }
        return clips;
    }

    /** The room on the storage that holds dir (its nearest folder that exists); 0 when not known. */
    private static long usableSpace(File dir) {
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (f.exists()) return f.getUsableSpace();
        }
        return 0;
    }

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
        return OverwatchProtocol.HELLO_OVERWATCH | (o.event.isActive() ? OverwatchProtocol.HELLO_EVENT : 0)
                | OverwatchProtocol.HELLO_PICTURES | (MotionWatch.analysing(OverwatchProtocol.ANALYSING_MS) ? OverwatchProtocol.HELLO_ANALYSING : 0);
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
    /**
     * pc.50 (the pilot: "every pc"): every PC an alert goes to - the one that set overwatch first, then each PC that
     * asked this phone's name in the last day (Udp.alertPcs, at their link ports). The alert, the motion's end and the
     * camera's word go to all; the pictures before the alert to each that acknowledges; the preview to each that asks;
     * the clips to the PC that set overwatch.
     */
    private volatile List<InetSocketAddress> pcs = java.util.Collections.emptyList();
    /** The PCs that have this event's alert, its end, its pictures before the alert; and when each last asked for the preview. */
    private final java.util.Set<java.net.InetAddress> alertHave = java.util.concurrent.ConcurrentHashMap.newKeySet(),
            endedHave = java.util.concurrent.ConcurrentHashMap.newKeySet(), preRolled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<java.net.InetAddress, Long> previewFor = new java.util.concurrent.ConcurrentHashMap<>();

    /** Every PC told has it (nobody to tell: yes). */
    private boolean allHave(java.util.Set<java.net.InetAddress> have) {
        for (InetSocketAddress a : pcs) if (!have.contains(a.getAddress())) return false;
        return true;
    }

    /**
     * pc.50: still to be sent again (OverwatchEvent.resendOwed) - someone lacks it, and nobody has it yet or it was first
     * said under a minute ago. It went on until ALL had it: one PC switched off kept the phone sending.
     */
    private boolean owed(java.util.Set<java.net.InetAddress> have, long sinceSaidMs) {
        return OverwatchEvent.resendOwed(allHave(have), !have.isEmpty(), sinceSaidMs);
    }

    /** To every PC told that doesn't have it yet (have null: to all). */
    private void sendToAll(byte[] p, java.util.Set<java.net.InetAddress> have) {
        for (InetSocketAddress a : pcs) if (have == null || !have.contains(a.getAddress())) sendTo(a, p);
    }

    /** Where a PC that sent a command is told: its link port (as the list has it), else the commanding PC's port. */
    private InetSocketAddress addressOf(java.net.InetAddress from) {
        for (InetSocketAddress a : pcs) if (a.getAddress().equals(from)) return a;
        InetSocketAddress c = pc;
        return from == null ? c : c == null ? null : new InetSocketAddress(from, c.getPort());
    }
    private volatile boolean watching, closed;
    private final OverwatchEvent event = new OverwatchEvent();
    private final PreRoll preRoll = new PreRoll();
    private final PowerFloor floor = new PowerFloor();
    private int[] position = {0, 0, 0, 0};
    private long positionAt;
    private long floorCheckAt;
    private int droppedReason, statusLeft;
    private long statusSentAt;
    /** pc.49: what the STATUS being sent says (STATE_DROPPED, or the camera's STATE_BLIND / STATE_WATCHING). */
    private int statusState = OverwatchProtocol.STATE_DROPPED, statusReason;
    /** pc.49: the camera's watchdog (the worker thread). */
    private final CameraWatchdog cameraWatchdog = new CameraWatchdog();

    // ---- the event (worker thread). pc.50: its intervals count by elapsedRealtime (a step of the wall clock stalled
    // the resends); the wall clock only where the PC or the clip store is told a time
    private OverwatchProtocol.Alert alert;
    // (pc.50: who has the alert and the pictures before it is kept per PC - alertHave, preRolled)
    private long alertSentAt;
    /** pc.50: when this alert was first sent (mono), and when the head clip was first announced (wall) - their resends slow down. */
    private long alertAskedAt, announcedAt;
    /** pc.50: said once per alert - it rests for the PCs that never answered. */
    private boolean restSaid;
    /** pc.50: this event has (had) a recording: MOTION ENDED says a clip follows. */
    private boolean eventRecorded;
    private List<PreRoll.Frame> eventPreRoll;
    private volatile long previewWantedAt = -1;
    private volatile boolean previewing;
    /** pc.50: a PC began asking for the preview: the config and a key frame once more (the sender's thread takes it). */
    private volatile boolean previewJoined;
    /** When the detector's boxes last went with the preview (the analysis thread's, mono; BOXES_EVERY_MS apart). */
    private long boxesSentAt;
    /** The last boxes sent were none: an empty set goes once, then nothing until something is seen again. */
    private boolean boxesEmpty = true;
    private static final long BOXES_EVERY_MS = 250;
    private OverwatchRecorder recorder;
    /** pc.38: the segment being written, and the next one while a cut waits for its key frame. */
    private ClipStore.Clip recordingClip, nextClip;
    private boolean eventVideo;
    // ---- the motion's end, until the PC has it
    private int endedEvent = -1;
    private long endedAtWall, endedSentAt;
    private boolean endedClip;
    /** pc.50: MOTION ENDED was first said then (its resends slow down and stop, OverwatchProtocol.endedDue). */
    private long endedSaidAt;
    /**
     * pc.50: the alert of an event that ended before any PC acknowledged it - it goes on (OverwatchProtocol.missedDue)
     * until missedUntil: 30 min, or a minute after the first PC has it.
     */
    private OverwatchProtocol.Alert missed;
    private long missedSince, missedSentAt, missedUntil;
    // ---- clips waiting for the PC (pc.38: on disk, in the store)
    private final ClipStore store;
    private volatile long infoSentAt;
    private long chunksAskedAt;
    /** pc.38: the clip whose INFO went last - the only one served (a CLIP_GET names the event, not the segment). */
    private ClipStore.Clip announced;
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
        this.store = clips(service);
        event.continueFrom(lastEvent); // pc.50: the numbering goes on - a PC left open took "event 1" again for a repeat
    }

    /** pc.50: the last event's number, across this app's overwatches. */
    private static volatile int lastEvent;

    // ------------------------------------------------------------------ watching

    private void watch(int[] where) {
        pc = udp.lastPcAddress();
        pcs = udp.alertPcs();
        if (pcs.isEmpty()) log("Overwatch: no PC to call (this phone hasn't flown with one since the app started) - it watches and records anyway");
        if (!openSocket()) log("Overwatch: no socket - alerts can't go out");
        if (where != null) position = where;
        positionAt = SystemClock.elapsedRealtime();
        floor.reset();
        preRoll.clear();
        droppedReason = 0;
        cameraWatchdog.reset();
        MotionWatch.overwatch(sensitivity);
        MotionWatch.setSink(this::onPicture);
        udp.overwatchCamera(true);
        watching = true;
        startSender();
        pace(TICK_MS);
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
        missed = null; // the PC that woke it is there now
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

    /**
     * pc.50: the tick's pace - ten a second while anything is under way, two a second while it only watches (it ran ten
     * a second all night: the camera's watchdog, the floor and the detection's switch count in seconds). A detection
     * and the PC's commands don't wait for a tick.
     */
    private static final long TICK_MS = 100, IDLE_TICK_MS = 500;
    private long tickEvery;

    /** The tick every this many ms from now on (worker thread; also from inside a tick). */
    private void pace(long every) {
        if (closed) return;
        if (ticking != null && every == tickEvery) return;
        if (ticking != null) ticking.cancel(false);
        tickEvery = every;
        ticking = worker.scheduleAtFixedRate(this::tickSafe, every, every, TimeUnit.MILLISECONDS);
    }

    /** The event is over (OverwatchEvent said ENDED): said why when it wasn't the quiet, then ended. */
    private void eventOver(long mono) {
        if (mono - event.lastDetectionAt() < OverwatchEvent.QUIET_MS) {
            log("Overwatch: event " + event.event() + " ends with its clip (" + OverwatchEvent.MAX_CLIP_MS / 60_000 + " min); what still moves starts the next");
        }
        endEvent();
    }

    private void tick() {
        long now = System.currentTimeMillis(), mono = SystemClock.elapsedRealtime();
        if (watching) {
            MotionWatch.tick(service.fcArmedState() == FcArmedState.DISARMED, service.camera());
            watchdog(mono);
            if (mono >= floorCheckAt) {
                floorCheckAt = mono + PowerFloor.CHECK_MS;
                int[] r = service.batteryReadings();
                int reason = floor.check(r[0], r[1] != 0, r[2], r[3]);
                if (reason != PowerFloor.REASON_NONE) dropToDeepSleep(reason, r[0], r[3]);
            }
        }
        // pc.50: at the clip's cap the event ends too (it stopped its clip and went on, unrecorded and without a ring)
        if (event.tick(mono) == OverwatchEvent.Action.ENDED) eventOver(mono);
        OverwatchRecorder rec = recorder;
        if (rec != null) { // pc.38 (L3): the recording in segments
            OverwatchRecorder.Segment seg;
            while ((seg = rec.pollFinished()) != null) onSegment(seg);
            if (nextClip == null && now - rec.segmentStartedAt() >= ClipStore.SEGMENT_MS && rec.writing()) cutSegment(rec, now);
        }
        ClipStore.Clip unconfirmed = store.tick(now);
        if (unconfirmed != null) {
            log("Overwatch: clip " + unconfirmed.file.getName() + " went whole but the PC didn't confirm it (attempt " + unconfirmed.attempts()
                    + (unconfirmed.givenUp() ? ") - given up, kept on the phone" : ") - the others first"));
            closeServing();
            infoSentAt = 0;
        }
        if (event.isActive() && !event.isSilenced(mono) && !allHave(alertHave)) {
            long since = mono - alertAskedAt;
            if (owed(alertHave, since)) {
                if (mono - alertSentAt >= OverwatchProtocol.resendEvery(since, OverwatchProtocol.ALERT_RESEND_MS, OverwatchProtocol.ALERT_SLOW_AFTER_MS,
                        OverwatchProtocol.ALERT_SLOW_MS)) sendAlert(mono);
            } else if (!restSaid) { // pc.50: a PC has it; the ones that never answered aren't called on through the event
                restSaid = true;
                log("Overwatch: " + alertHave.size() + " of " + pcs.size() + " PCs have the alert - it rests for the others (" + OverwatchEvent.STRAGGLER_MS / 1000 + " s unanswered)");
            }
        }
        previewing = eventVideo && event.isActive() && !event.isSilenced(mono) && previewWantedAt >= 0
                && mono - previewWantedAt < OverwatchProtocol.PREVIEW_FRESH_MS;
        if (eventVideo) { // pc.50: the preview's encoder gets pictures only while a PC asks for them
            Camera c = service.camera();
            if (c != null) c.setStreamTarget(previewing);
        }
        if (endedEvent >= 0 && owed(endedHave, mono - endedSaidAt) && OverwatchProtocol.endedDue(mono - endedSaidAt, mono - endedSentAt)) {
            endedSentAt = mono;
            sendToAll(OverwatchProtocol.clipEnded(token, endedEvent, endedAtWall, endedClip), endedHave);
        }
        // pc.50: the alert no PC ever had, its event over - still told, slowly
        if (missed != null && !event.isActive()) {
            if (mono >= missedUntil) missed = null;
            else if (OverwatchProtocol.missedDue(mono - missedSince, mono - missedSentAt)) {
                missedSentAt = mono;
                sendToAll(OverwatchProtocol.alert(missed), alertHave);
            }
        }
        ClipStore.Clip head = store.head();
        // pc.38: a new head is announced at once - the PC's asks name the event alone, and an event's segments share it
        if (head != null && (head != announced || now - chunksAskedAt >= OverwatchProtocol.INFO_EVERY_MS && now - infoSentAt >= OverwatchProtocol.resendEvery(
                now - Math.max(announcedAt, chunksAskedAt), OverwatchProtocol.INFO_EVERY_MS, OverwatchProtocol.INFO_SLOW_AFTER_MS, OverwatchProtocol.INFO_SLOW_MS))) {
            if (head != announced) announcedAt = now;
            infoSentAt = now;
            announced = head;
            store.offered(head);
            send(OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(token, head.event, head.size(), head.sha256(), head.durationMs(),
                    head.startedAt, head.sound, head.width, head.height, head.part, head.eventStartedAt)));
        }
        if (statusLeft > 0 && mono - statusSentAt >= 1000) {
            statusLeft--;
            statusSentAt = mono;
            int[] r = service.batteryReadings();
            sendToAll(OverwatchProtocol.status(token, statusState, statusReason, r[0] < 0 ? 255 : r[0], r[3] == PowerFloor.UNKNOWN ? 0 : r[3]), null);
        }
        boolean endedDone = endedEvent < 0 || !owed(endedHave, mono - endedSaidAt) || mono - endedSaidAt >= OverwatchProtocol.ENDED_FOR_MS;
        boolean clips = recorder != null || hashing.get() != 0 || store.head() != null;
        if (!watching && !clips && endedDone && statusLeft == 0) {
            closeSocket();
            return;
        }
        // pc.50: nothing under way but the watch itself, and it has its pictures (a camera starting or opened again is
        // followed at the fast pace, as before) - the slow tick
        boolean idle = watching && !event.isActive() && !eventVideo && !clips && endedDone && statusLeft == 0 && missed == null
                && MotionWatch.analysing(MotionWatch.NO_PICTURE_MS);
        pace(idle ? IDLE_TICK_MS : TICK_MS);
    }

    /** pc.49: 5 s without a picture opens the camera again ({@link CameraWatchdog}); the PC is told why. */
    private void watchdog(long mono) {
        CameraWatchdog.Action a = cameraWatchdog.tick(mono, MotionWatch.analysing(MotionWatch.NO_PICTURE_MS), eventVideo);
        if (a == CameraWatchdog.Action.NONE) return;
        if (a == CameraWatchdog.Action.SAY_BACK) {
            log("Overwatch: the camera gives pictures again");
            tellStatus(OverwatchProtocol.STATE_WATCHING, 0);
            return;
        }
        Camera c = service.camera();
        int reason = c == null ? OverwatchProtocol.BLIND_CLOSED : c.blindReason();
        if (a == CameraWatchdog.Action.SAY_BLIND) {
            // pc.50: blind in an event's session (it records without the picture, or never started - the encoder didn't
            // come up, the camera was taken): the phone saw nothing for the whole event and nobody was told
            tellStatus(OverwatchProtocol.STATE_BLIND, reason);
            if (c != null && c.isStarted()) { // it records: not broken off; the watch sees again when the event ends
                log("Overwatch: no pictures in this event's session for " + cameraWatchdog.blindFor(mono) / 1000 + " s ("
                        + OverwatchProtocol.describeBlind(reason) + ") - it records on, blind until the event ends");
                return;
            }
            log("Overwatch: this event's session gives nothing (" + OverwatchProtocol.describeBlind(reason)
                    + ") - the event ends here, back to the watch's picture");
            if (event.isActive()) {
                endEvent(); // what it recorded so far is kept; what still moves starts a new event, with a new try at its video
                event.abandon();
            }
            return;
        }
        log("Overwatch: no pictures for " + cameraWatchdog.blindFor(mono) / 1000 + " s (" + OverwatchProtocol.describeBlind(reason)
                + ") - the camera opened again (" + cameraWatchdog.reopens() + ")");
        if (a == CameraWatchdog.Action.REOPEN_AND_SAY_BLIND) tellStatus(OverwatchProtocol.STATE_BLIND, reason);
        udp.overwatchCameraAgain();
    }

    /** pc.49: the camera's word to the PC - never over the floor's drop, which goes first. */
    private void tellStatus(int state, int reason) {
        if (statusLeft > 0 && statusState == OverwatchProtocol.STATE_DROPPED) return;
        statusState = state;
        statusReason = reason;
        statusLeft = 3;
        statusSentAt = 0;
    }

    /** The floor: deep sleep now, and the PC told why. */
    private void dropToDeepSleep(int reason, int battery, int tempDeci) {
        log("Overwatch: " + PowerFloor.describe(reason) + " (battery " + battery + " %, " + tempDeci / 10.0 + " C) - deep sleep instead");
        droppedReason = reason;
        statusState = OverwatchProtocol.STATE_DROPPED;
        statusReason = reason;
        statusLeft = 5;
        statusSentAt = 0;
        sendToAll(OverwatchProtocol.status(token, OverwatchProtocol.STATE_DROPPED, reason, Math.max(0, battery), tempDeci == PowerFloor.UNKNOWN ? 0 : tempDeci), null);
        PowerMode.floorDrop(reason);
    }

    // ------------------------------------------------------------------ the pictures (the analysis thread)

    private void onPicture(byte[] y, byte[] u, byte[] v, int w, int h, MotionDetector.Result r, long wallMs, long monoMs) {
        if (!watching) return;
        // pc.50: the pictures before an alert are kept by the clock that never steps; each carries its wall time for the PC
        if (preRoll.due(monoMs, r != null && !r.detections.isEmpty())) {
            byte[] jpeg = jpeg(y, u, v, w, h);
            if (jpeg != null) preRoll.add(monoMs, wallMs, jpeg);
        }
        if (previewing && r != null) sendBoxes(r, wallMs, monoMs);
        if (r == null || r.detections.isEmpty()) return;
        MotionRecord.Report report = MotionRecord.of(MotionRecord.FLAG_ACTIVE | (r.night ? MotionRecord.FLAG_NIGHT : 0), MotionWatch.fieldOfView(), r);
        if (report.boxes.isEmpty()) return;
        final MotionRecord.Box box = report.boxes.get(0);
        final boolean night = r.night;
        worker.execute(() -> onDetection(box, night, report.hfovDeg));
    }

    /** The detector's boxes now, with the live preview (pc.40): the PC's card box follows the thing. */
    private void sendBoxes(MotionDetector.Result r, final long wallMs, long monoMs) {
        boolean none = r.detections.isEmpty();
        if (none && boxesEmpty || monoMs - boxesSentAt < BOXES_EVERY_MS && !(none && !boxesEmpty)) return;
        boxesSentAt = monoMs;
        boxesEmpty = none;
        final java.util.List<float[]> boxes = new java.util.ArrayList<>();
        for (MotionDetector.Detection d : r.detections) boxes.add(new float[]{d.x, d.y, d.w, d.h, d.light ? 1 : 0});
        worker.execute(() -> {
            if (previewing) sendUnit(OverwatchProtocol.UNIT_BOXES, OverwatchProtocol.boxesUnit(wallMs, boxes), null);
        });
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
        float cx = box.x + box.w / 2, cy = box.y + box.h / 2;
        OverwatchEvent.Action a = event.onDetection(mono, cx, cy);
        if (a == OverwatchEvent.Action.NONE && event.tick(mono) == OverwatchEvent.Action.ENDED) {
            // pc.50: the event is at its clip's cap - it ends here, and this detection is the next event's first (not
            // lost to the wait for the tick): a new alert, the pictures before it, a new clip
            eventOver(mono);
            now = System.currentTimeMillis();
            mono = SystemClock.elapsedRealtime();
            a = event.onDetection(mono, cx, cy);
        }
        if (a == OverwatchEvent.Action.START) {
            lastEvent = event.event();
            alert = makeAlert(box, night, hfov, now);
            pcs = udp.alertPcs(); // a PC that came since the watch began hears of it too
            alertHave.clear();
            preRolled.clear();
            previewFor.clear();
            // pc.50: the new alert is the news now - and a late ACK of the last event's isn't taken for this one's
            missed = null;
            alertAskedAt = mono;
            restSaid = false;
            eventPreRoll = preRoll.snapshot(mono);
            previewWantedAt = -1;
            sendAlert(mono);
            startRecording(now);
            pace(TICK_MS);
            log("Overwatch: MOTION - event " + event.event() + (pcs.isEmpty() ? "" : ", calling " + pcs.size() + " PC" + (pcs.size() == 1 ? "" : "s")));
        } else if (a == OverwatchEvent.Action.ALERT_AGAIN) {
            // pc.50: also in an event nobody dismissed (OverwatchEvent: new motion, a minute apart at least). Its clip
            // runs all along - an event ends with its clip - so there is nothing to start here but the alert
            alert = makeAlert(box, night, hfov, alert == null ? now : alert.startedAt);
            alertHave.clear();
            alertAskedAt = mono;
            restSaid = false;
            sendAlert(mono);
            log("Overwatch: new motion (or still moving 10 min after a dismiss) - the alert again, no. " + event.alertNo());
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

    private void sendAlert(long mono) {
        alertSentAt = mono;
        if (alert != null) sendToAll(OverwatchProtocol.alert(alert), alertHave);
    }

    private void sendPreRoll(java.net.InetAddress to) {
        if (to == null) return;
        preRolled.add(to);
        List<PreRoll.Frame> frames = eventPreRoll;
        if (frames == null) return;
        for (PreRoll.Frame f : frames) {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream(f.jpeg.length + 8);
                DataOutputStream out = new DataOutputStream(b);
                out.writeLong(f.atMs);
                out.write(f.jpeg);
                sendUnit(OverwatchProtocol.UNIT_PREROLL, b.toByteArray(), to);
            } catch (IOException ignored) {
            }
        }
    }

    /** The full-quality recording and the preview's encoder, in the camera's session with the analysis picture. */
    private void startRecording(long now) {
        Camera camera = service.camera();
        Surface surface = null;
        recorder = null;
        eventRecorded = false;
        if (camera != null && camera.getWidth() > 0) {
            OverwatchRecorder r = new OverwatchRecorder(camera.getWidth(), camera.getHeight(), camera.getTargetFps(), audioSource);
            surface = r.prepare();
            File file = store.newFile(now, event.event(), 1);
            boolean sound = (flags & OverwatchProtocol.MODE_FLAG_SOUND) != 0
                    && service.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (surface != null && r.start(file, sound)) {
                recorder = r;
                eventRecorded = true;
                long eventStart = alert == null ? now : alert.startedAt; // the first segment is the alert's (the PC links them by it)
                recordingClip = store.begin(file, event.event(), 1, eventStart, eventStart, r.hasSound(), r.width(), r.height());
                nextClip = null;
            } else {
                r.stop();
                surface = null;
                log("Overwatch: no full-quality clip of this event (the recorder didn't start); the PC keeps the preview");
            }
        }
        udp.overwatchEventVideo(true, surface);
        eventVideo = true;
    }

    /**
     * The recording ends: its last segment (and any cut just before) joins the clips waiting for the PC, hashed on the
     * hashing thread. The caller has taken the recorder's surface out of the camera's session first (H10).
     */
    private void finishClip() {
        OverwatchRecorder r = recorder;
        recorder = null;
        if (r == null) return;
        File f = r.stop();
        OverwatchRecorder.Segment seg;
        while ((seg = r.pollFinished()) != null) onSegment(seg); // cut before the stop
        if (nextClip != null) { // a cut whose key frame never came: no such file
            store.discard(nextClip);
            nextClip = null;
        }
        ClipStore.Clip c = recordingClip;
        recordingClip = null;
        if (c == null) return;
        if (f == null || !f.isFile() || f.length() == 0) {
            log("Overwatch: the clip came out empty");
            store.discard(c);
            return;
        }
        hashLater(c, r.durationMs());
    }

    /** pc.38 (L3): the segment is long enough - the recorder goes on in a new file from its next key frame. */
    private void cutSegment(OverwatchRecorder r, long now) {
        ClipStore.Clip cur = recordingClip;
        if (cur == null) return;
        int part = cur.part + 1;
        File file = store.newFile(now, cur.event, part);
        nextClip = store.begin(file, cur.event, part, now, cur.eventStartedAt, r.hasSound(), r.width(), r.height());
        if (!r.cut(file)) { // nothing written yet (the encoders' formats not in): later
            store.discard(nextClip);
            nextClip = null;
        }
    }

    /** A cut happened: the closed segment goes to be hashed, the next one is the one being written. */
    private void onSegment(OverwatchRecorder.Segment seg) {
        ClipStore.Clip done = recordingClip;
        recordingClip = nextClip;
        nextClip = null;
        if (done == null) return;
        if (seg.file == null || !seg.file.isFile() || seg.file.length() == 0) {
            log("Overwatch: a segment came out empty");
            store.discard(done);
            return;
        }
        hashLater(done, seg.durationMs);
    }

    /**
     * pc.38 (M19): the SHA-256 on the hashing thread - a long clip took seconds on the overwatch thread, and ending
     * overwatch waited on it. The clip is offered once it is in the store; the quota is kept then.
     */
    private void hashLater(final ClipStore.Clip c, final long durationMs) {
        hashing.incrementAndGet();
        try {
            hasher.execute(() -> {
                try {
                    byte[] sha = ClipStore.sha256(c.file);
                    store.finish(c, durationMs, sha);
                    log("Overwatch: clip " + c.file.getName() + " (" + c.size() / 1024 + " KB) waits for the PC");
                    for (ClipStore.Clip gone : store.enforceQuota(usableSpace(store.dir()))) {
                        log("Overwatch clips: " + gone.file.getName() + " deleted - over the folder's quota");
                    }
                    infoSentAt = 0; // announced at the next tick
                } catch (IOException | RuntimeException e) {
                    log("Overwatch: the clip can't be read: " + e);
                    store.discard(c);
                } finally {
                    hashing.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            hashing.decrementAndGet();
            log("Overwatch: the clip wasn't hashed now (" + e + "); the next start takes it back");
        }
    }

    /** pc.38: waits for the camera's session change (H10: the recorder's surface out of it) - 3 s at most. */
    private static void awaitCamera(Future<?> change) {
        try {
            change.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log("Overwatch: the camera's session change didn't finish in time: " + e);
        }
    }

    /** MOTION ENDED: the clip finished, back to the analysis picture alone; the PC is told until it acknowledges. */
    private void endEvent() {
        if (eventVideo) {
            eventVideo = false;
            previewing = false;
            // pc.38 (H10): the recorder's surface out of the session (the old one waited out) before the recorder
            // stops - also when the watch ends, the camera closing right after
            awaitCamera(udp.overwatchEventVideo(false, null));
        }
        finishClip();
        endedEvent = event.event();
        endedAtWall = System.currentTimeMillis();
        endedHave.clear();
        endedClip = eventRecorded; // its last segment may still be hashing
        long mono = SystemClock.elapsedRealtime();
        endedSaidAt = mono;
        endedSentAt = 0;
        // pc.50: never acknowledged by any PC (a dismiss is an answer too: its PC acknowledged first) - the alert goes on
        // after its event. Not for one PC of several that never answered: it kept the phone sending for 30 min after
        // every event
        if (alertHave.isEmpty() && !pcs.isEmpty() && alert != null && watching) {
            missed = alert;
            missedSince = mono;
            missedSentAt = mono;
            missedUntil = mono + OverwatchProtocol.MISSED_FOR_MS;
            log("Overwatch: no PC had this alert - it is told on, every " + OverwatchProtocol.MISSED_EVERY_MS / 1000 + " s");
        } else {
            missed = null;
        }
        log("Overwatch: the motion ended (event " + endedEvent + ")");
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
            boolean needKey = true, join = false;
            int joinWaited = 0;
            while (gen == senderGen && enc != null) {
                try {
                    // pc.44: never beside the live video - the link's sender takes the same queue (left running, this
                    // took every other frame: a flickering picture). Only in OVERWATCH, where the link's video is stopped.
                    if (PowerMode.mode() != PowerMode.OVERWATCH) {
                        needKey = true;
                        Thread.sleep(500);
                        continue;
                    }
                    // pc.50: 1 s (was 0.1 s: ten wake-ups a second all night with no event; a frame ends the wait at once)
                    MediaCodecBuffer b = enc.videoStreamOutputBuffer.poll(1000, TimeUnit.MILLISECONDS);
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
                    if (previewJoined) { // pc.50: another PC asks now - the config and a key frame for it, the stream goes on
                        previewJoined = false;
                        join = true;
                        joinWaited = 0;
                    }
                    if (needKey) {
                        if (!key) {
                            enc.requestKeyFrame();
                            continue;
                        }
                        byte[] cfg = videoConfig;
                        if (cfg != null) sendUnit(OverwatchProtocol.UNIT_CONFIG, cfg, null);
                        needKey = false;
                        join = false;
                    } else if (join) {
                        // the PCs that have the stream keep getting its frames meanwhile (the one that joined skips them);
                        // the same config again changes nothing for them
                        if (key) {
                            byte[] cfg = videoConfig;
                            if (cfg != null) sendUnit(OverwatchProtocol.UNIT_CONFIG, cfg, null);
                            join = false;
                        } else if (joinWaited++ % 15 == 0) {
                            enc.requestKeyFrame(); // asked again every 15 frames, should one request go nowhere
                        }
                    }
                    sendUnit(key ? OverwatchProtocol.UNIT_KEY_FRAME : OverwatchProtocol.UNIT_FRAME, b.data, null);
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

    /** A unit in its fragments: to one PC (the pictures before the alert), or - to null - to every PC that asks for the preview now. */
    private synchronized void sendUnit(int type, byte[] data, java.net.InetAddress to) {
        java.util.List<InetSocketAddress> targets = new java.util.ArrayList<>();
        if (to != null) {
            InetSocketAddress a = addressOf(to);
            if (a != null) targets.add(a);
        } else {
            long mono = SystemClock.elapsedRealtime();
            for (java.util.Map.Entry<java.net.InetAddress, Long> e : previewFor.entrySet()) {
                InetSocketAddress a = mono - e.getValue() < OverwatchProtocol.PREVIEW_FRESH_MS ? addressOf(e.getKey()) : null;
                if (a != null) targets.add(a);
            }
        }
        if (targets.isEmpty()) return;
        int u = unit = (unit + 1) & 0xffff;
        List<byte[]> parts = OverwatchProtocol.fragments(token, event.event(), u, type, data);
        for (int i = 0; i < parts.size(); i++) {
            for (InetSocketAddress a : targets) sendTo(a, parts.get(i));
            if (i % 8 == 7) SystemClock.sleep(1); // a key frame's 60 packets not all in one burst
        }
    }

    // ------------------------------------------------------------------ the PC's commands

    private void onCommand(OverwatchProtocol.Command c, java.net.InetAddress from) {
        long mono = SystemClock.elapsedRealtime();
        int ev = event.event();
        switch (c.cmd) {
            case OverwatchProtocol.CMD_ACK:
                if (c.event == ev && event.isActive()) {
                    if (alertHave.add(from)) log("Overwatch: the PC at " + from.getHostAddress() + " has the alert");
                    if (!preRolled.contains(from)) sendPreRoll(from);
                } else if (missed != null && c.event == missed.event) { // pc.50: late, its event over
                    if (alertHave.add(from)) {
                        log("Overwatch: the PC at " + from.getHostAddress() + " has the alert now, " + (mono - missedSince) / 1000
                                + " s after the motion ended");
                        // one has it: the others are told a minute more, then it rests (it went on until all had it)
                        missedUntil = Math.min(missedUntil, mono + OverwatchEvent.STRAGGLER_MS);
                    }
                    if (allHave(alertHave)) missed = null;
                    if (!preRolled.contains(from)) sendPreRoll(from);
                    if (endedEvent == c.event) { // and that it ended, again from the start
                        endedHave.remove(from);
                        endedSaidAt = mono;
                        endedSentAt = 0;
                    }
                }
                break;
            case OverwatchProtocol.CMD_PREVIEW:
                if (c.event == ev) {
                    previewWantedAt = mono;
                    Long before = previewFor.put(from, mono);
                    // pc.50: a PC that begins to ask while another has the stream running got no config and key frame of
                    // its own (the sender sent them once, when the stream began): nothing to decode until the next key frame
                    if (before == null || mono - before >= OverwatchProtocol.PREVIEW_FRESH_MS) previewJoined = true;
                }
                break;
            case OverwatchProtocol.CMD_PREROLL:
                if (c.event == ev) sendPreRoll(from);
                break;
            case OverwatchProtocol.CMD_DISMISS:
                if (c.event == ev && event.isActive()) {
                    event.dismiss(mono);
                    previewWantedAt = -1;
                    log("Overwatch: dismissed by the PC - silent until new motion (10 min at most)");
                }
                break;
            case OverwatchProtocol.CMD_CONNECT:
                if (watching) {
                    log("Overwatch: the PC connects (CONNECT on its alert)");
                    PowerMode.request(PowerMode.NORMAL, 0, "PC CONNECT on alert");
                }
                break;
            case OverwatchProtocol.CMD_ENDED_ACK:
                if (c.event == endedEvent) endedHave.add(from);
                break;
            case OverwatchProtocol.CMD_CLIP_GET:
                serveChunks(c);
                break;
            case OverwatchProtocol.CMD_CLIP_DONE: {
                if (c.offset == 0) break;
                ClipStore.Clip done;
                if (c.clipStartedAt != OverwatchProtocol.NO_CLIP_START) {
                    done = store.find(c.event, c.clipStartedAt); // pc.38: the PC names the segment
                } else { // a PC before 1.0.49: the event alone - the head, once offered
                    done = store.head();
                    if (done != null && (done.event != c.event || !store.wasOffered(done))) done = null;
                }
                if (done == null) break;
                if (done.file.equals(servingFile)) closeServing();
                if (store.delivered(done)) log("Overwatch: the PC has clip " + done.file.getName() + " (checked): deleted here");
                else log("Overwatch: the PC has clip " + done.file.getName() + "; it couldn't be deleted here");
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
        long now = System.currentTimeMillis();
        chunksAskedAt = now;
        ClipStore.Clip head = store.head();
        if (head == null || head != announced || head.event != c.event || service.isFcArmed()) return;
        long size = head.size();
        try {
            if (serving == null || !head.file.equals(servingFile)) {
                closeServing();
                serving = new RandomAccessFile(head.file, "r");
                servingFile = head.file;
            }
            byte[] buf = new byte[OverwatchProtocol.FRAGMENT];
            long end = c.offset;
            for (int i = 0; i < Math.min(c.count, 256); i++) {
                long off = c.offset + (long) i * OverwatchProtocol.FRAGMENT;
                if (off >= size) break;
                int n = (int) Math.min(OverwatchProtocol.FRAGMENT, size - off);
                serving.seek(off);
                serving.readFully(buf, 0, n);
                send(OverwatchProtocol.clipChunk(token, head.event, off, buf, 0, n));
                end = off + n;
                if (i % 8 == 7) SystemClock.sleep(1);
            }
            store.served(head, c.offset, end, now); // pc.38: the attempts (M20)
        } catch (IOException e) {
            log("Overwatch: clip " + head.file.getName() + " can't be read (" + e + ") - given up, the others go on");
            store.giveUp(head);
            closeServing();
            infoSentAt = 0;
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
                if (c == null || c.token != token || token == 0) continue; // only a PC that has this overwatch's token
                final java.net.InetAddress from = p.getAddress();
                worker.execute(() -> onCommand(c, from));
            } catch (IOException e) {
                if (!s.isClosed()) log("Overwatch receive error: " + e);
            } catch (RuntimeException e) {
                // the worker is gone (shut down): nothing to do
            }
        }
    }

    /** To the PC that set overwatch (the clips are its). */
    private void send(byte[] p) {
        sendTo(pc, p);
    }

    private void sendTo(InetSocketAddress to, byte[] p) {
        DatagramSocket s = socket;
        if (s == null || to == null || s.isClosed()) return;
        try {
            s.send(new DatagramPacket(p, p.length, to));
        } catch (IOException e) {
            // no network now (4G gone a moment): the alert and the rest go again
        }
    }

    private void closeSocket() {
        if (closed) return; // pc.50: and with no socket (it never opened) the 0.1 s tick still ends - it ran on for ever
        if (socket != null) socket.close();
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
