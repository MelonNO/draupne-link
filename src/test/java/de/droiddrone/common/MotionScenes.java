package de.droiddrone.common;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the motion detector's tests share (MotionDetectorTest and the MotionDetector...Test classes beside it,
 * AnalysisSizeTest, MotionDetectorPerfTest): the picture's size and pace, a run of the detector over a made-up scene
 * and what it saw, and the scenes several of them are set in - the windy woods, the wood's edge, the boxes a user
 * would draw over the foliage. Nothing is kept here between tests: each makes its own scene and detector, so the
 * classes can run side by side.
 */
final class MotionScenes {
    static final int W = 320, H = 180;
    static final double FPS = 8;

    /** What a run saw: the ids reported, the first time any was, and each report's detections. */
    static final class Run {
        final Set<Integer> ids = new HashSet<>();
        final Map<Integer, Integer> reportsPerId = new HashMap<>();
        double firstAt = -1;
        int reports;
        boolean anyNight, anyLight;
        MotionDetector.Detection first, last;
        double lastAt;
        /** pc.50: pictures with a box, and pictures not judged (settling). */
        int boxed, settling;
    }

    static Run run(SyntheticScene scene, MotionDetector d, double fromS, double toS, Run into) {
        return run(scene, d, fromS, toS, into, 0);
    }

    /** @param clockMs what the clock the pictures are timed by says at the scene's 0 s */
    static Run run(SyntheticScene scene, MotionDetector d, double fromS, double toS, Run into, long clockMs) {
        Run r = into == null ? new Run() : into;
        for (long i = Math.round(fromS * FPS); i < Math.round(toS * FPS); i++) {
            double t = i / FPS;
            byte[][] f = scene.frame(t);
            MotionDetector.Result res = d.process(f[0], W, H, f[1], f[2], clockMs + Math.round(t * 1000));
            if (res.night) r.anyNight = true;
            if (res.settling) r.settling++;
            if (!res.detections.isEmpty()) r.boxed++;
            for (MotionDetector.Detection det : res.detections) {
                r.ids.add(det.id);
                r.reportsPerId.merge(det.id, 1, Integer::sum);
                r.reports++;
                if (r.firstAt < 0) {
                    r.firstAt = t;
                    r.first = det;
                }
                if (det.light) r.anyLight = true;
                r.last = det;
                r.lastAt = t;
            }
        }
        return r;
    }

    /**
     * Woods round a field in a gale: four patches of foliage swaying 10 - 21 px (3 - 7 % of the picture's width) at
     * 0.4 - 1.4 Hz in gusts, the camera shaking up to 4 px.
     */
    static SyntheticScene windyWoods(long seed) {
        SyntheticScene s = new SyntheticScene(W, H, seed);
        s.patches.add(new SyntheticScene.Patch(50, 50, 45, 40, 10f, 0.6f, 0.3f));
        s.patches.add(new SyntheticScene.Patch(270, 60, 50, 45, 15f, 0.9f, 1.7f));
        s.patches.add(new SyntheticScene.Patch(160, 30, 35, 25, 21f, 0.4f, 2.9f)); // a big branch, slow and wide
        s.patches.add(new SyntheticScene.Patch(230, 150, 30, 25, 12f, 1.4f, 4.1f)); // a bush, quick
        s.shake = 4;
        return s;
    }

    /**
     * The edge of a wood seen from 1.5 m up (1.0.53): the windy woods' foliage swaying in the canopy and the bushes, and
     * trunks 18 - 45 m away across the middle of the picture, where someone in the wood would walk out.
     */
    static SyntheticScene woodsEdge(long seed) {
        SyntheticScene s = windyWoods(seed);
        s.patches.add(new SyntheticScene.Patch(120, 40, 40, 22, 9f, 0.8f, 1.1f)); // canopy over the trunks
        s.patches.add(new SyntheticScene.Patch(200, 82, 18, 10, 6f, 1.1f, 3.3f)); // a bush at the wood's edge
        double[][] trunks = {{-9, 30, 0.5}, {-5, 22, 0.4}, {-2.2, 38, 0.45}, {1.4, 26, 0.35}, {3.5, 18, 0.5}, {6, 34, 0.4}, {10, 45, 0.6}};
        for (double[] k : trunks) s.trunks.add(new SyntheticScene.Trunk(k[0], k[1], k[2], 55));
        return s;
    }

    /** The detector on a scene without colour (a grey camera): u and v null. */
    static Run runGrey(SyntheticScene scene, MotionDetector d, double fromS, double toS) {
        Run r = new Run();
        for (long i = Math.round(fromS * FPS); i < Math.round(toS * FPS); i++) {
            double t = i / FPS;
            byte[][] f = scene.frame(t);
            MotionDetector.Result res = d.process(f[0], W, H, null, null, Math.round(t * 1000));
            for (MotionDetector.Detection det : res.detections) {
                r.ids.add(det.id);
                r.reportsPerId.merge(det.id, 1, Integer::sum);
                r.reports++;
                if (r.firstAt < 0) r.firstAt = t;
                r.last = det;
                r.lastAt = t;
            }
        }
        return r;
    }

    /** Boxes over the scene's swaying foliage (its reach included), at this sensitivity: what the user would draw. */
    static DetectionAreas foliage(SyntheticScene s, int level) {
        List<DetectionAreas.Area> l = new java.util.ArrayList<>();
        for (SyntheticScene.Patch p : s.patches) {
            if (p.amplitude <= 0) continue;
            // (SyntheticScene: a ragged edge up to 1.15 of the ellipse, the sway up to 1.3 x the amplitude, 0.4 of it up and down)
            float rx = p.rx * 1.15f + 1.3f * p.amplitude + 2, ry = p.ry * 1.15f + 0.52f * p.amplitude + 2;
            l.add(new DetectionAreas.Area((p.cx - rx) / W, (p.cy - ry) / H, 2 * rx / W, 2 * ry / H, level));
        }
        return DetectionAreas.of(l);
    }

    private MotionScenes() {
    }
}
