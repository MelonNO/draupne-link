package de.droiddrone.pc.fleet;

import de.droiddrone.common.MotionDetector;
import de.droiddrone.common.MotionRecord;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A virtual drone's motion detection (the phone's MotionWatch): eight pictures a second through the shared
 * MotionDetector while asked and disarmed, each one to overwatch; the DETECTION record when it is due. Its drone's tick
 * thread only. Out of VirtualDrone, which sends the record.
 */
final class VirtualMotionWatch {
    private final MotionDetector detector = new MotionDetector(MotionDetector.NORMAL);
    private final Scene scene;
    private final VirtualOverwatch overwatch;
    private BufferedImage analysis;
    private long nextAnalysisAt, nextMotionReportAt;
    private MotionRecord.Report lastMotion;
    private int lastBoxCount;

    VirtualMotionWatch(Scene scene, VirtualOverwatch overwatch) {
        this.scene = scene;
        this.overwatch = overwatch;
    }

    /**
     * Every 100 ms: level is the sensitivity asked for (0 none), view the camera now, rec an old flight playing (or
     * null). Returns the record to send now, or null.
     */
    MotionRecord.Report tick(long now, int level, boolean disarmed, Scene.View view, Recording rec) {
        if (level <= 0) {
            if (lastMotion != null) detector.reset();
            lastMotion = null;
            return null;
        }
        MotionRecord.Report report;
        if (!disarmed) {
            report = new MotionRecord.Report(MotionRecord.FLAG_ARMED, (float) Scene.HFOV_DEG, List.of());
            detector.reset();
        } else if (now >= nextAnalysisAt) {
            nextAnalysisAt = now + 125;
            if (analysis == null) analysis = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
            if (rec == null || !rec.picture(analysis, now)) scene.render(analysis, view, now, null, false);
            else scene.paintTargets(analysis, view, now); // the same picture the video shows, models and all
            int[] size = new int[2];
            int[] px = Scene.pixels(analysis);
            byte[] y = Scene.luma(px, 320, 180, 320, size);
            byte[][] uv = Scene.chroma(px, 320, 180); // the colour too, as the phone gives it (1.0.52)
            detector.setSensitivity(level);
            MotionDetector.Result r = detector.process(y, size[0], size[1], uv[0], uv[1], now);
            int flags = MotionRecord.FLAG_ACTIVE | (r.night ? MotionRecord.FLAG_NIGHT : 0) | (r.settling ? MotionRecord.FLAG_SETTLING : 0);
            report = MotionRecord.of(flags, (float) Scene.HFOV_DEG, r);
            overwatch.onPicture(analysis, report, now);
        } else {
            return null;
        }
        lastMotion = report;
        int boxes = report.boxes.size();
        boolean due = now >= nextMotionReportAt || boxes != lastBoxCount;
        if (!due) return null;
        nextMotionReportAt = now + (boxes > 0 ? 100 : 1000); // every picture while boxes show: the box keeps up (1.0.52)
        lastBoxCount = boxes;
        return report;
    }
}
