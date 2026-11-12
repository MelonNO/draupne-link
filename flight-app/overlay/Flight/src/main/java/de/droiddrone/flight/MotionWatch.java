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

import android.media.Image;
import android.os.Process;
import android.os.SystemClock;

import com.serenegiant.libuvccamera.IFrameCallback;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.droiddrone.common.AnalysisSize;
import de.droiddrone.common.DetectionAreas;
import de.droiddrone.common.MotionDetector;
import de.droiddrone.common.MotionRecord;
import de.droiddrone.common.PacketIds;

import static de.droiddrone.common.Logcat.log;

/**
 * pc.35: motion detection while the drone sits disarmed (DRAUPNE Link 1.0.46, the pilot: "when the drone is disarmed
 * the phone should detect movements ... not on movement caused by vegetation"). The PC asks with DETECTION_SET
 * [0x7C][sensitivity u8: 0 off, 1 low, 2 normal, 3 high] once a second; silent for {@link #REQUEST_FRESH_MS} it is
 * off. While it is asked for, the camera runs and the flight controller reads DISARMED, small pictures (at most
 * {@link #MAX_WIDTH} wide, {@link #FRAME_EVERY_MS} apart) go to the {@link MotionDetector} on a thread of its own at
 * background priority; what it finds goes to the PC as the DETECTION record ({@link MotionRecord}).
 *
 * <p>Where the pictures come from: the internal camera gives a second small YUV stream next to the encoder's surface
 * (an ImageReader in its capture session, {@link InternalCamera}); a USB camera its frame callback ({@link UsbCamera}).
 * Never the encoder's frames: nothing here touches the video's way to the PC. Both are switched on only while
 * analysing and off within 0.1 s of the FC reading anything but DISARMED (the telemetry thread's {@link #tick}) - so
 * in the air the camera does exactly what it did before. A phone that refuses the extra stream (its session can't be
 * configured with it) goes on without: the record says NO_PICTURE.
 *
 * <p>pc.36, overwatch sleep: {@link Overwatch} asks for it itself ({@link #overwatch}), ticks it from its own thread
 * (the link's telemetry thread is gone then) and takes each analysed picture ({@link Sink}) for the pre-roll and the
 * alert.
 *
 * <p>pc.50: every interval here counts by SystemClock.elapsedRealtime (it was the wall clock: a time correction
 * backwards by T made the detector take no picture for T while {@link #analysing} said all was well). The wall time
 * goes only where the PC is told when something was ({@link Sink}).
 */
final class MotionWatch {
    static final byte PACKET = PacketIds.DETECTION_SET;
    /** The PC sends DETECTION_SET once a second: silent this long, detection is off. */
    static final long REQUEST_FRESH_MS = 5000;
    /** Pictures about this far apart (8 a second); the gate itself follows the camera's frame rate ({@link #setFrameRate}). */
    static final long FRAME_EVERY_MS = de.droiddrone.common.WatchRun.FRAME_EVERY_MS;
    /** Pictures are taken at most this wide (the detector works at half that); a wider camera picture is averaged down. */
    static final int MAX_WIDTH = 320;
    /** No picture for this long while analysing: the record says NO_PICTURE. */
    static final long NO_PICTURE_MS = 3000;
    /** The record: every this while something is shown, else once a second. */
    /** pc.40: every new result while boxes show (it was 200 ms: the box drew a car length behind a car). */
    static final long REPORT_BOXES_MS = 100, REPORT_IDLE_MS = 1000;

    private static volatile int sensitivity;
    private static volatile long requestedAt;
    private static volatile boolean active;
    private static volatile long activeSince;
    private static volatile Camera activeCamera;
    /** The camera's field of view across the picture, degrees (0 = unknown): the boxes' angles. */
    private static volatile float hfovDeg;
    private static volatile long lastFrameAt, takenAt;
    private static volatile MotionDetector.Result lastResult;
    private static volatile long resultAt, sentAt, sentResultAt;
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final MotionDetector detector = new MotionDetector(MotionDetector.NORMAL);
    private static final ExecutorService analysis = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); // never before the video or the sticks
            r.run();
        }, "motionWatch");
        t.setDaemon(true);
        return t;
    });
    // one picture at a time (busy): its buffers are reused
    private static byte[] y = new byte[0], u = new byte[0], v = new byte[0];
    private static int width, height;

    private MotionWatch() {
    }

    /**
     * pc.46: the areas with a sensitivity of their own (the PC's DETECTION_SET after its byte, or its overwatch
     * request): kept until new ones come, also while overwatch sleeps.
     */
    private static volatile DetectionAreas areas = DetectionAreas.NONE;

    /** The PC's DETECTION_SET; areas null when it carried none (an older PC): the ones known are kept. */
    static void request(int s, DetectionAreas a) {
        areas(a);
        request(s);
    }

    /** New areas (null: none came, the ones known stay). */
    static void areas(DetectionAreas a) {
        if (a == null || a.equals(areas)) return;
        log("Motion detection: " + a);
        areas = a;
    }

    /** The PC's DETECTION_SET. */
    static void request(int s) {
        if (s != sensitivity) log("Motion detection " + (s == 0 ? "off" : "asked for: " + new String[]{"", "low", "normal", "high"}[Math.min(3, s)]));
        sensitivity = Math.max(0, Math.min(3, s));
        requestedAt = SystemClock.elapsedRealtime();
    }

    static boolean wanted() {
        return overwatch > 0 || sensitivity > 0 && SystemClock.elapsedRealtime() - requestedAt < REQUEST_FRESH_MS;
    }

    /**
     * pc.36, overwatch sleep: analysing at this sensitivity whatever the PC's DETECTION_SET (there is no link to
     * carry it); 0 gives it back to DETECTION_SET. Overwatch's thread calls {@link #tick} then.
     */
    static void overwatch(int s) {
        overwatch = Math.max(0, Math.min(3, s));
    }

    private static volatile int overwatch;

    /** pc.50: the detector is overwatch's now - every camera session then gives it the watch's own picture (InternalCamera). */
    static boolean forOverwatch() {
        return overwatch > 0;
    }

    /** pc.36: what overwatch does with each analysed picture (the pre-roll, the alert); called on the analysis thread. */
    interface Sink {
        /**
         * The picture (I420 planes, w x h, reused after this returns) and what the detector made of it.
         * @param wallMs when it came, for the PC; monoMs the same moment on elapsedRealtime, for intervals (pc.50)
         */
        void onPicture(byte[] y, byte[] u, byte[] v, int w, int h, MotionDetector.Result r, long wallMs, long monoMs);
    }

    private static volatile Sink sink;

    static void setSink(Sink s) {
        sink = s;
    }

    static void setFieldOfView(float deg) {
        hfovDeg = deg;
    }

    /** pc.50: the video's shape - a picture of another shape is cut to it (InternalCamera, at each camera start). */
    private static volatile int videoWidth, videoHeight;

    static void setVideoShape(int w, int h) {
        videoWidth = w;
        videoHeight = h;
    }

    /** pc.50: the next picture is taken when the last is this old (WatchRun.frameGateMs of the session's frame rate). */
    private static volatile long frameGate = de.droiddrone.common.WatchRun.frameGateMs(0);

    /** pc.50: the frame rate of the camera's session (each session says it; 0: not known). */
    static void setFrameRate(int fps) {
        frameGate = de.droiddrone.common.WatchRun.frameGateMs(fps);
    }

    /**
     * pc.50: the camera stamps its pictures on elapsedRealtime (Camera2's SENSOR_INFO_TIMESTAMP_SOURCE is REALTIME):
     * the detector then counts by when a picture was taken, not by when this thread got to it. Any other source (its
     * base isn't known, and may differ after the camera is opened again) keeps the arrival time.
     */
    private static volatile boolean sensorClock;
    /** A picture's stamp older than this on arrival (or from the future) isn't that clock after all. */
    private static final long SENSOR_OLDEST_MS = 1000;

    static void setSensorClock(boolean realtime) {
        sensorClock = realtime;
    }

    private static long pictureTime(Image image, long arrival) {
        if (!sensorClock) return arrival;
        long sensor = image.getTimestamp() / 1_000_000;
        if (sensor > arrival + 5 || arrival - sensor > SENSOR_OLDEST_MS) { // off until the camera starts again: no hopping between clocks
            sensorClock = false;
            log("Motion detection: the camera's picture times aren't the phone's clock (" + (arrival - sensor) + " ms off) - counted as they come");
            return arrival;
        }
        return Math.min(sensor, arrival);
    }

    /** The camera's angle across the picture, degrees (0 = unknown): for overwatch's alert (pc.36) and the observers' map (pc.37). */
    static float fieldOfView() {
        return hfovDeg;
    }

    /**
     * Every 0.1 s from the telemetry thread: analysing while asked for, the FC DISARMED and the camera running;
     * stops the camera's analysis picture the moment one of them goes. (pc.50: from overwatch's own thread it comes
     * twice a second while that only watches - nothing here counts finer than WatchRun's seconds.)
     */
    static void tick(boolean disarmed, Camera camera) {
        // pc.50: a session change (an overwatch event's start or end) is not a stop - see WatchRun
        boolean ready = wanted() && disarmed && camera != null;
        boolean started = ready && camera.isStarted();
        long mono = SystemClock.elapsedRealtime();
        if (started) startedAt = mono;
        Camera was = activeCamera;
        boolean run = de.droiddrone.common.WatchRun.runs(ready, started, active, was == camera, mono - startedAt);
        if (was != null && was != camera) was.setAnalysis(false); // the camera was replaced (USB <-> internal)
        if (camera != null) camera.setAnalysis(run); // the camera does nothing when it is already so
        activeCamera = run ? camera : null;
        if (run != active) {
            active = run;
            activeSince = mono;
            log(run ? "Motion detection on (disarmed, camera running)" : "Motion detection paused" + (disarmed ? "" : " - the FC isn't known to be disarmed"));
            if (run) analysis.execute(detector::reset); // a new start learns the picture again
        }
        int s = overwatch > 0 ? overwatch : sensitivity;
        if (s > 0 && s != sensitivitySet) {
            sensitivitySet = s;
            analysis.execute(() -> detector.setSensitivity(s));
        }
        DetectionAreas a = areas;
        if (a != areasSet) {
            areasSet = a;
            analysis.execute(() -> detector.setAreas(a));
        }
        armedPause = wanted() && !disarmed;
    }

    private static volatile boolean armedPause;
    /** pc.50: when the camera was last seen started (monotonic ms). */
    private static volatile long startedAt;
    private static int sensitivitySet; // the telemetry thread's own
    private static DetectionAreas areasSet; // as sensitivitySet

    /** pc.47: the detection runs and had a picture within the last ms (overwatch's hello says so to the PC). */
    static boolean analysing(long ms) {
        return active && SystemClock.elapsedRealtime() - lastFrameAt < ms;
    }

    /** A picture taken at this time is wanted: analysing, the last one done, the time come. Cheap: the camera's threads ask it per frame. */
    private static boolean wantFrame(long at) {
        return active && !busy.get() && at - takenAt >= frameGate;
    }

    /** From the internal camera's ImageReader thread: a YUV_420_888 picture, copied smaller at once (the caller closes it). */
    static void offer(Image image) {
        final long arrival = SystemClock.elapsedRealtime();
        final long at = pictureTime(image, arrival);
        // pc.50: the gate counts from picture to picture (it counted from the end of the last copy to this call)
        if (!wantFrame(at) || !busy.compareAndSet(false, true)) return;
        try {
            Image.Plane[] p = image.getPlanes();
            // pc.50: overwatch's own picture may be of another shape (320x240 for a 16:9 video): its centred cut is the
            // video's view, so the detector gets the same 320x180 as from the video's own 16:9 picture
            int[] cut = AnalysisSize.centreCrop(image.getWidth(), image.getHeight(), videoWidth, videoHeight);
            int iw = cut[2], ih = cut[3];
            int step = Math.max(1, (iw + MAX_WIDTH - 1) / MAX_WIDTH);
            size(iw / step & ~1, ih / step & ~1);
            // pc.43: the luma averaged over each step x step block - a phone with no small picture of the video's shape
            // gives 1280x720 or more (AnalysisSize), and one pixel in 16 flickers with the lens's stabiliser
            AnalysisSize.shrink(p[0].getBuffer(), cut[1] * p[0].getRowStride() + cut[0] * p[0].getPixelStride(), p[0].getRowStride(),
                    p[0].getPixelStride(), step, y, width, height);
            int cx = cut[0] / 2, cy = cut[1] / 2;
            copyPlane(p[1].getBuffer(), cy * p[1].getRowStride() + cx * p[1].getPixelStride(), p[1].getRowStride(), p[1].getPixelStride(), step, u, width / 2, height / 2);
            copyPlane(p[2].getBuffer(), cy * p[2].getRowStride() + cx * p[2].getPixelStride(), p[2].getRowStride(), p[2].getPixelStride(), step, v, width / 2, height / 2);
        } catch (RuntimeException e) { // a picture closed under us, a plane of another layout: this one is skipped
            busy.set(false);
            return;
        }
        analyse(at, arrival);
    }

    /**
     * A USB camera's frames (NV21, its full size), on the camera library's capture thread - not the one that draws
     * into the encoder. Copied smaller only when a picture is wanted; every other frame returns at once.
     */
    static IFrameCallback uvcFrames(final int frameWidth, final int frameHeight) {
        return frame -> {
            final long arrival = SystemClock.elapsedRealtime();
            if (!wantFrame(arrival) || !busy.compareAndSet(false, true)) return;
            try {
                int step = Math.max(1, (frameWidth + MAX_WIDTH - 1) / MAX_WIDTH);
                size(frameWidth / step & ~1, frameHeight / step & ~1);
                AnalysisSize.shrink(frame, frameWidth, 1, step, y, width, height); // pc.43: averaged, as the internal camera's
                // NV21: after the luma, V and U by turns at half size each way
                ByteBuffer vu = frame.duplicate();
                vu.position(frameWidth * frameHeight);
                ByteBuffer chroma = vu.slice();
                copyPlane(chroma, 0, frameWidth, 2, step, v, width / 2, height / 2);
                ByteBuffer uOnly = chroma.duplicate();
                uOnly.position(1);
                copyPlane(uOnly.slice(), 0, frameWidth, 2, step, u, width / 2, height / 2);
            } catch (RuntimeException e) {
                busy.set(false);
                return;
            }
            analyse(arrival, arrival);
        };
    }

    private static void size(int w, int h) {
        width = Math.max(16, w);
        height = Math.max(16, h);
        if (y.length != width * height) {
            y = new byte[width * height];
            u = new byte[(width / 2) * (height / 2)];
            v = new byte[(width / 2) * (height / 2)];
        }
    }

    /** Every step-th pixel of a plane each way into a packed out x oh array. */
    private static void copyPlane(ByteBuffer src, int offset, int rowStride, int pixelStride, int step, byte[] dst, int ow, int oh) {
        int limit = src.limit();
        for (int yy = 0; yy < oh; yy++) {
            int row = offset + yy * step * rowStride;
            int o = yy * ow;
            for (int xx = 0; xx < ow; xx++) {
                int at = row + xx * step * pixelStride;
                dst[o + xx] = at < limit ? src.get(at) : 0;
            }
        }
    }

    /** @param at the picture's time, for the detector and the gate; arrival: when it came (both elapsedRealtime ms) */
    private static void analyse(final long at, final long arrival) {
        final long wall = System.currentTimeMillis(); // for the PC only
        takenAt = at;
        lastFrameAt = arrival;
        analysis.execute(() -> {
            try {
                MotionDetector.Result r = detector.process(y, width, height, u, v, at);
                lastResult = r;
                resultAt = SystemClock.elapsedRealtime();
                Sink k = sink;
                if (k != null) k.onPicture(y, u, v, width, height, r, wall, arrival); // pc.36: before the buffers are reused
            } catch (RuntimeException e) {
                log("Motion detection error: " + e);
            } finally {
                busy.set(false);
            }
        });
    }

    /** A record is due: asked for, and boxes to show REPORT_BOXES_MS after the last, else once a second. */
    static boolean reportDue() {
        if (!wanted()) return false;
        long now = SystemClock.elapsedRealtime();
        MotionDetector.Result r = lastResult;
        boolean boxes = active && r != null && !r.detections.isEmpty() && now - resultAt < 1000;
        if (boxes && resultAt != sentResultAt && now - sentAt >= REPORT_BOXES_MS) return true;
        return now - sentAt >= REPORT_IDLE_MS;
    }

    /** The DETECTION record (its own packet: an older PC stops reading at it). */
    static void writeRecordTo(DataOutputStream out) throws IOException {
        long now = SystemClock.elapsedRealtime();
        sentAt = now;
        sentResultAt = resultAt;
        int flags = 0;
        MotionDetector.Result r = null;
        if (active) {
            flags |= MotionRecord.FLAG_ACTIVE;
            Camera c = activeCamera;
            boolean noPicture = c != null && !c.analysisAvailable()
                    || now - activeSince > NO_PICTURE_MS && now - lastFrameAt > NO_PICTURE_MS;
            if (noPicture) flags |= MotionRecord.FLAG_NO_PICTURE;
            r = now - resultAt < 1000 ? lastResult : null;
        } else if (armedPause) {
            flags |= MotionRecord.FLAG_ARMED;
        }
        MotionRecord.write(out, MotionRecord.of(flags, hfovDeg, r));
    }
}
