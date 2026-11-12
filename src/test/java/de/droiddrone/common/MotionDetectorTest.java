package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The motion detector on made-up pictures (SyntheticScene): 320 x 180 at 8 a second, as the phone feeds it. The
 * pilot's target is at most one false alarm an hour of wind in trees; no real footage exists here, so the windy scenes
 * are as unkind as could be made up - strong gusts, leaves out of step, the camera shaking - and must give none at all.
 */
class MotionDetectorTest {
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

    /**
     * 1.0.53, the pilot: "the movement detection should be able to find a person moving from inside the woods directly
     * towards the camera", without colour (other cameras have none; people and vehicles are the surroundings' colour).
     * Someone in camouflage of the ground's brightness walks out of the wood from 45 m straight at a grey camera, hardly
     * moving sideways - growing, hidden by trunks until past them: found while still 15 m away at least, the box on them.
     */
    @Test
    void someoneWalkingOutOfTheWoodsStraightAtTheCameraIsFound() {
        for (boolean grey : new boolean[]{true, false}) {
            SyntheticScene scene = woodsEdge(11);
            SyntheticScene.Approacher a = new SyntheticScene.Approacher(0.6, 45, 1.4, 25, 52, 90, 30);
            scene.approachers.add(a);
            MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
            Run r = grey ? runGrey(scene, d, 0, 50) : run(scene, d, 0, 50, null);
            assertTrue(r.firstAt >= 25, (grey ? "grey" : "colour") + ": nothing before the walker: " + r.firstAt + " " + r.reportsPerId);
            double at = a.distance(r.firstAt);
            assertTrue(at >= 15, (grey ? "grey" : "colour") + ": found " + String.format("%.1f", at) + " m away (at " + r.firstAt + " s)");
            float[] truth = scene.box(a, r.lastAt);
            assertEquals((truth[0] + truth[2] / 2) / W, r.last.centreX(), 0.05, "box centre x on the walker");
            assertEquals((truth[1] + truth[3] / 2) / H, r.last.centreY(), 0.08, "box centre y on the walker");
        }
    }

    /**
     * As above, but at an angle: out of the wood between the trunks towards the camera and a little across. (Not through
     * the swaying bush: inside leaves that sway, brightness can't tell a person from the leaves - found once out of them.)
     */
    @Test
    void someoneComingOutOfTheWoodsAtAnAngleIsFound() {
        SyntheticScene scene = woodsEdge(13);
        SyntheticScene.Approacher a = new SyntheticScene.Approacher(-6, 40, 1.4, 20, 45, 90, 30);
        a.driftMps = 0.25; // to the right while coming nearer
        scene.approachers.add(a);
        Run r = runGrey(scene, new MotionDetector(MotionDetector.NORMAL), 0, 45);
        assertTrue(r.firstAt >= 20 && a.distance(r.firstAt) >= 15, "found " + a.distance(r.firstAt) + " m away (" + r.firstAt + " s)");
    }

    /**
     * pc.50: someone coming slowly - a wary walk, 0.5 - 0.7 m/s - out of the wood from 45 m straight at a grey camera.
     * The growth the window asks (a quarter taller in 6 s) he reaches only some 15 m off, and half a minute after his
     * first pixel stood out, far off, the reference had taken him and nothing was left to grow: found 11 - 12 m off at
     * 0.5 m/s, 10 m off at 0.7 m/s (seed 13; seed 11 by luck 21.6 m). With the slow look - the same growth over 18 s,
     * and what keeps a thing's shape and moves within itself left out of the reference meanwhile: SLOW_FOUND_M off at
     * least, the box on him.
     */
    @Test
    void someoneComingSlowlyOutOfTheWoodsIsFoundWellBeforeHeIsClose() {
        for (long seed : new long[]{11, 13}) {
            for (double speed : new double[]{0.5, 0.7}) {
                SyntheticScene scene = woodsEdge(seed);
                double until = 25 + (45 - 10) / speed; // he is then 10 m off
                SyntheticScene.Approacher a = new SyntheticScene.Approacher(0.6, 45, speed, 25, until, 90, 30);
                scene.approachers.add(a);
                Run r = runGrey(scene, new MotionDetector(MotionDetector.NORMAL), 0, until);
                String who = "seed " + seed + " at " + speed + " m/s";
                assertTrue(r.firstAt >= 25, who + ": found, and nothing before the walker: " + r.firstAt + " " + r.reportsPerId);
                double at = a.distance(r.firstAt);
                assertTrue(at >= SLOW_FOUND_M, who + ": found " + String.format("%.1f", at) + " m away (at " + r.firstAt + " s)");
                float[] truth = scene.box(a, r.lastAt);
                assertEquals((truth[0] + truth[2] / 2) / W, r.last.centreX(), 0.05, who + ": box centre x on the walker");
                assertEquals((truth[1] + truth[3] / 2) / H, r.last.centreY(), 0.08, who + ": box centre y on the walker");
            }
        }
    }

    static final double SLOW_FOUND_M = 15;

    /** The wood's edge in the gale for twenty minutes, without colour: nothing - the growing test must not take leaves. */
    @Test
    void theWoodsEdgeInTheWindGivesNoAlarmWithoutColour() {
        for (int sens = MotionDetector.LOW; sens <= MotionDetector.HIGH; sens++) {
            Run r = runGrey(woodsEdge(23 + sens), new MotionDetector(sens), 0, sens == MotionDetector.NORMAL ? 1200 : 300);
            assertEquals(Set.of(), r.ids, "sensitivity " + sens + ": false alarms " + r.reportsPerId);
        }
    }

    @Test
    void windInTheTreesForTwentyMinutesGivesNoAlarm() {
        SyntheticScene scene = windyWoods(11);
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        Run r = run(scene, d, 0, 20 * 60, null);
        assertEquals(Set.of(), r.ids, "false alarms in 20 minutes of wind");
        assertTrue(d.tracksMade() >= 10, "the wind made candidates for the judges: " + d.tracksMade());
    }

    @Test
    void highSensitivityKeepsQuietInTheWindToo() {
        SyntheticScene scene = windyWoods(23);
        MotionDetector d = new MotionDetector(MotionDetector.HIGH);
        Run r = run(scene, d, 0, 5 * 60, null);
        assertEquals(Set.of(), r.ids, "false alarms in 5 minutes of wind on HIGH");
    }

    /**
     * 1.0.50: the track's times were kept as floats of the raw clock - with the PC's wall clock (the virtual fleet) or
     * a phone up for a week, a float can't tell pictures apart and nothing ever travelled.
     */
    @Test
    void aBigClockStillFindsTheWalker() {
        for (long clock : new long[]{7L * 24 * 3600_000, 1_794_092_657_440L}) {
            SyntheticScene scene = windyWoods(5);
            scene.movers.add(new SyntheticScene.Mover(20, 95, 10, 26, 18, 1, 10, 25, 70, 140, 122, true));
            Run r = run(scene, new MotionDetector(MotionDetector.NORMAL), 0, 25, null, clock);
            assertTrue(r.firstAt >= 10 && r.firstAt <= 13, "clock " + clock + ": found 3 s at most after it appeared: " + r.firstAt);
        }
    }

    @Test
    void someoneWalkingPastTheTreesIsFoundWithOneId() {
        SyntheticScene scene = windyWoods(5);
        // a person 10 x 26 px walking right at 18 px/s through the field, from 10 s
        scene.movers.add(new SyntheticScene.Mover(20, 95, 10, 26, 18, 1, 10, 25, 70, 140, 122, true));
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        Run r = run(scene, d, 0, 25, null);
        assertTrue(r.firstAt >= 10 && r.firstAt <= 13, "found 3 s at most after it appeared: " + r.firstAt);
        int most = r.reportsPerId.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(most >= 0.9 * r.reports, "one id for the walker: " + r.reportsPerId);
        assertFalse(r.last.light);
        // the box where the walker was (the last report's time)
        float[] truth = scene.movers.get(0).at(r.lastAt);
        float cx = (truth[0] + truth[2] / 2) / W, cy = (truth[1] + truth[3] / 2) / H;
        assertEquals(cx, r.last.centreX(), 0.05, "box centre x");
        assertEquals(cy, r.last.centreY(), 0.08, "box centre y");
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

    /**
     * A sentry in camouflage of the net's grain, a little darker than it (30 grey levels, as the tour's sentry is against
     * the BTR's net), walking his beat in front of it (see below).
     */
    static SyntheticScene.Mover sentry(SyntheticScene scene) {
        scene.patches.add(new SyntheticScene.Patch(160, 95, 70, 20, 0, 0, 0)); // the net: leaves that don't move
        SyntheticScene.Mover m = new SyntheticScene.Mover(0, 0, 4, 14, 0, 0, 0, 1e6f, 70, 124, 126, false);
        m.camo = 0.7f;
        m.path = t -> {
            double c = t % SENTRY_CYCLE;
            double f = c < SENTRY_LEG ? c / SENTRY_LEG : c < SENTRY_LEG + 3 ? 1 : c < 2 * SENTRY_LEG + 3 ? 1 - (c - SENTRY_LEG - 3) / SENTRY_LEG : 0;
            return new float[]{(float) (120 + SENTRY_BEAT_PX * f), 88};
        };
        scene.movers.add(m);
        return m;
    }

    /** His beat (px), clear of the swaying leaves, at 1.2 m/s (9.6 px/s some 20 m off). */
    static final double SENTRY_BEAT_PX = 56, SENTRY_LEG = SENTRY_BEAT_PX / 9.6, SENTRY_CYCLE = 2 * (SENTRY_LEG + 3);

    static boolean walking(double t) {
        double c = t % SENTRY_CYCLE;
        return c < SENTRY_LEG || c >= SENTRY_LEG + 3 && c < 2 * SENTRY_LEG + 3;
    }

    /**
     * 1.0.58, the pilot: the motion detection missed the sentry by the camouflaged BTR and the infantry on patrol - "real
     * life does not have a white background". A sentry in camouflage of the net's grain, 4 x 14 px (a man some 20 m off),
     * walking his 56 px beat in front of a camouflage net and back at 1.2 m/s, 3 s at each end, the camera on the ground
     * trembling a little, the woods round him in the gale - boxed OFF, as the user would (on HIGH, leaves a gust starts
     * after a calm look like someone walking): on HIGH boxed in over half the pictures while he walks, both ways and every time
     * round, and nothing boxed anywhere else. On NORMAL (as up to 1.0.57) he is hardly ever found.
     */
    @Test
    void onHighACamouflagedSentryWalkingHisBeatInFrontOfANetIsFound() {
        for (int sens : new int[]{MotionDetector.HIGH, MotionDetector.NORMAL}) {
            SyntheticScene scene = windyWoods(17);
            scene.shake = 0.3f;
            DetectionAreas leaves = foliage(scene, MotionDetector.OFF);
            SyntheticScene.Mover sentry = sentry(scene);
            MotionDetector d = new MotionDetector(sens);
            d.setAreas(leaves);
            int walking = 0, boxed = 0;
            for (long i = 0; i < Math.round(90 * FPS); i++) {
                double t = i / FPS;
                byte[][] f = scene.frame(t);
                MotionDetector.Result res = d.process(f[0], W, H, null, null, Math.round(t * 1000));
                float[] at = sentry.at(t);
                float cx = (at[0] + 2) / W, cy = (at[1] + 7) / H;
                boolean on = false;
                for (MotionDetector.Detection det : res.detections) {
                    boolean his = Math.abs(det.centreX() - cx) < 0.04 && Math.abs(det.centreY() - cy) < 0.08;
                    assertTrue(his, String.format("sensitivity %d at %.1f s: a box away from the sentry: %.2f, %.2f (he is at %.2f, %.2f)",
                            sens, t, det.centreX(), det.centreY(), cx, cy));
                    on = true;
                }
                if (t < 25 || !walking(t)) continue;
                walking++;
                if (on) boxed++;
            }
            if (sens == MotionDetector.HIGH) assertTrue(boxed > 0.5 * walking, "on HIGH the sentry boxed in " + boxed + " of " + walking + " pictures while he walked");
            else assertTrue(boxed < 0.1 * walking, "on NORMAL as before: boxed in " + boxed + " of " + walking);
        }
    }

    /**
     * 1.0.58: the price of HIGH, and what areas are for. The camera still on the ground in the gale, no one there: with
     * the foliage boxed LOW or OFF nothing in five minutes; the same unboxed raises alarms - leaves a gust starts after a
     * calm travel like someone walking for a few seconds.
     */
    @Test
    void onHighTheSwayingLeavesBoxedLowOrOffGiveNoAlarm() {
        int unboxed = 0;
        for (long seed : new long[]{5, 17}) {
            for (int level : new int[]{MotionDetector.LOW, MotionDetector.OFF}) {
                SyntheticScene scene = windyWoods(seed);
                scene.shake = 0;
                MotionDetector d = new MotionDetector(MotionDetector.HIGH);
                d.setAreas(foliage(scene, level));
                Run r = run(scene, d, 0, 300, null);
                assertEquals(Set.of(), r.ids, "seed " + seed + ", leaves boxed " + DetectionAreas.word(level) + ": false alarms " + r.reportsPerId);
            }
            SyntheticScene scene = windyWoods(seed);
            scene.shake = 0;
            unboxed += run(scene, new MotionDetector(MotionDetector.HIGH), 0, 300, null).ids.size();
        }
        assertTrue(unboxed > 0, "unboxed, HIGH finds the leaves a gust starts (what the areas are for)");
    }

    /** 1.0.58: someone walking through an OFF area is never boxed there, and boxed soon after leaving it. */
    @Test
    void nothingIsReportedFromAnOffArea() {
        SyntheticScene scene = windyWoods(5);
        scene.movers.add(new SyntheticScene.Mover(20, 95, 10, 26, 18, 1, 10, 25, 70, 140, 122, true));
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        d.setAreas(DetectionAreas.of(List.of(new DetectionAreas.Area(0, 0.4f, 0.5f, 0.6f, MotionDetector.OFF))));
        boolean inside = false;
        double firstAt = -1;
        for (long i = 0; i < Math.round(25 * FPS); i++) {
            double t = i / FPS;
            byte[][] f = scene.frame(t);
            for (MotionDetector.Detection det : d.process(f[0], W, H, null, null, Math.round(t * 1000)).detections) {
                if (det.centreX() < 0.5f && det.centreY() > 0.4f) inside = true;
                if (firstAt < 0) firstAt = t;
            }
        }
        assertFalse(inside, "a box inside the OFF area");
        // the walker's middle crosses x = 160 px (the area's edge) at about 17.5 s: found within 3 s of leaving it
        assertTrue(firstAt >= 17 && firstAt <= 22, "found once out of the OFF area: " + firstAt);
    }

    /** 1.0.58: the areas travel to the phone after DETECTION_SET's sensitivity byte, and into the settings and back. */
    @Test
    void theAreasGoOverTheWireAndIntoTheSettings() {
        DetectionAreas a = DetectionAreas.of(List.of(new DetectionAreas.Area(0.1f, 0.2f, 0.3f, 0.4f, MotionDetector.OFF),
                new DetectionAreas.Area(0.9f, 0.9f, -0.5f, -0.2f, MotionDetector.HIGH)));
        byte[] block = a.encode();
        assertEquals(1 + 2 * DetectionAreas.AREA_BYTES, block.length);
        assertEquals(a, DetectionAreas.read(new DataReader(block, true)));
        assertEquals(0.4f, a.list().get(1).x, 1e-6, "a box drawn backwards is turned round");
        assertEquals(a, DetectionAreas.parse(a.toText()));
        assertEquals(DetectionAreas.NONE, DetectionAreas.read(new DataReader(new byte[]{0}, true)), "n = 0 clears them");
        assertEquals(null, DetectionAreas.read(new DataReader(new byte[0], true)), "no block: kept as they are");
        assertEquals(null, DetectionAreas.read(new DataReader(java.util.Arrays.copyOf(block, block.length - 1), true)), "cut short: kept");
        List<DetectionAreas.Area> many = new java.util.ArrayList<>();
        for (int k = 0; k < 12; k++) many.add(new DetectionAreas.Area(0, 0, 0.1f, 0.1f, 1));
        assertEquals(DetectionAreas.MAX, DetectionAreas.of(many).list().size());
        assertEquals(DetectionAreas.NONE, DetectionAreas.parse("junk;0,0,1"));
    }

    /**
     * 1.0.52, the pilot's recording: a red car with the ground's brightness was seen as its wheels and windows only. 1.0.52
     * filled it in by its colour; 1.0.53 takes no colour (the pilot: other cameras have none, and vehicles wear the
     * surroundings' colours), so a car of exactly the ground's brightness is seen where brightness shows it - against
     * ground of another brightness: found soon after it drove in (also while the detector is young), the box on it. Over
     * ground of its own brightness it can't be told from the ground, and the box is lost until it is out of it.
     */
    @Test
    void aCarOfTheGroundsBrightnessIsFoundEarlyWithoutItsColour() {
        SyntheticScene scene = windyWoods(7);
        // 30 x 12 px, flat, the ground's mean brightness, red (V high), 40 px/s to the right from 7 s
        scene.movers.add(new SyntheticScene.Mover(-30, 120, 30, 12, 40, 0, 7, 16, 108, 110, 190, false));
        Run r = run(scene, new MotionDetector(MotionDetector.NORMAL), 0, 12, null); // it is then in the middle
        assertTrue(r.firstAt >= 7 && r.firstAt <= 9.25, "found soon after it drove in: " + r.firstAt);
        float[] truth = scene.movers.get(0).at(r.firstAt);
        assertEquals((truth[0] + truth[2] / 2) / W, r.first.centreX(), truth[2] / 2 / W, "the first box's centre x on the car");
    }

    /**
     * 1.0.52: a long vehicle at walking pace read as flicker to a young detector (its first 20 s) and was found only once
     * it had grown - an overwatch phone just put down would miss it. Its slow way that never comes back now counts.
     */
    @Test
    void aLongSlowVehicleIsFoundWhileTheDetectorIsYoung() {
        SyntheticScene scene = windyWoods(9);
        scene.movers.add(new SyntheticScene.Mover(-60, 112, 60, 16, 8, 0, 5, 30, 60, 120, 132, true));
        Run r = run(scene, new MotionDetector(MotionDetector.NORMAL), 0, 20, null);
        assertTrue(r.firstAt > 5 && r.firstAt <= 12, "found while the detector is young: " + r.firstAt);
    }

    @Test
    void aBirdCrossingIsFound() {
        // a sky over a tree line swaying in the gale, the camera shaking
        SyntheticScene scene = new SyntheticScene(W, H, 8);
        scene.skyRows = 60;
        scene.patches.add(new SyntheticScene.Patch(80, 95, 60, 30, 12f, 0.7f, 0.5f));
        scene.patches.add(new SyntheticScene.Patch(240, 100, 60, 35, 15f, 0.5f, 2.2f));
        scene.shake = 4;
        // small and quick: 6 x 4 px at 50 px/s across the sky
        scene.movers.add(new SyntheticScene.Mover(10, 22, 6, 4, 50, 3, 10, 16, 40, 128, 128, false));
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        Run r = run(scene, d, 0, 16, null);
        assertTrue(r.firstAt >= 10 && r.firstAt <= 13, "the bird found: " + r.firstAt);
    }

    @Test
    void theCameraShakingIsTakenOut() {
        SyntheticScene scene = new SyntheticScene(W, H, 3);
        scene.shake = 6; // +-3 working pixels, a new place each picture
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        Run quiet = run(scene, d, 0, 120, null);
        assertEquals(Set.of(), quiet.ids, "shake alone");
        // the shift found follows the shake (working pixels are half the input's; the content moves the other way)
        for (int i = 0; i < 8; i++) {
            byte[][] f = scene.frame(120 + i / FPS);
            double shakeX = scene.shakeX(), shakeY = scene.shakeY();
            d.process(f[0], W, H, f[1], f[2], Math.round((120 + i / FPS) * 1000));
            float sx = d.shiftX(), sy = d.shiftY();
            f = scene.frame(120 + (i + 0.5) / FPS);
            d.process(f[0], W, H, f[1], f[2], Math.round((120 + (i + 0.5) / FPS) * 1000));
            assertEquals(-(scene.shakeX() - shakeX) / 2, d.shiftX() - sx, 0.3, "shift x");
            assertEquals(-(scene.shakeY() - shakeY) / 2, d.shiftY() - sy, 0.3, "shift y");
        }
        scene.movers.add(new SyntheticScene.Mover(300, 60, 12, 20, -22, 0, 121, 135, 200, 128, 128, true));
        Run found = run(scene, d, 121, 135, null);
        assertTrue(found.firstAt > 0 && found.firstAt <= 124, "found through the shake: " + found.firstAt);
        assertEquals(1, found.ids.size(), "one id: " + found.reportsPerId);
    }

    @Test
    void anExposureJumpIsIgnored() {
        SyntheticScene scene = windyWoods(17);
        scene.gain = t -> t < 20 ? 1 : t < 40 ? 1.5 : t < 41 ? 1.2 : 0.7; // auto exposure: up a step, down twice
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        Run quiet = run(scene, d, 0, 70, null);
        assertEquals(Set.of(), quiet.ids, "exposure steps");
        scene.movers.add(new SyntheticScene.Mover(40, 100, 10, 24, 20, 0, 72, 85, 160, 128, 128, true));
        Run found = run(scene, d, 70, 85, null);
        assertTrue(found.firstAt > 0 && found.firstAt <= 75, "found after the steps: " + found.firstAt);
    }

    @Test
    void aCloudsShadowIsIgnored() {
        SyntheticScene scene = windyWoods(29);
        scene.shadows.add(new SyntheticScene.Shadow(0.55f, 40, 15)); // a slow edge: crosses the picture in about 9 s
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        Run r = run(scene, d, 0, 40, null);
        assertEquals(Set.of(), r.ids, "a shadow's edge crossing");
        scene = windyWoods(31);
        scene.shadows.add(new SyntheticScene.Shadow(0.6f, 250, 15)); // a cloud's shadow over all of it in 1.5 s
        scene.shadows.add(new SyntheticScene.Shadow(1 / 0.6f, 250, 30)); // and the sun back
        d = new MotionDetector(MotionDetector.NORMAL);
        r = run(scene, d, 0, 45, null);
        assertEquals(Set.of(), r.ids, "a cloud's shadow over the whole picture");
    }

    @Test
    void aLightTravellingAtNightIsFoundAndNoiseIsNot() {
        SyntheticScene scene = new SyntheticScene(W, H, 41);
        scene.darkness = 0.1f; // mean luma about 12
        scene.noiseSigma = 6;
        scene.hotPixels = 40;
        scene.shake = 1;
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        Run quiet = run(scene, d, 0, 180, null);
        assertTrue(quiet.anyNight, "the dark picture is night");
        assertEquals(Set.of(), quiet.ids, "sensor noise in the dark");
        // a torch 4 x 4 px walking left at 15 px/s
        scene.movers.add(new SyntheticScene.Mover(280, 120, 4, 4, -15, -1, 181, 195, 240, 128, 128, false));
        Run found = run(scene, d, 180, 195, null);
        assertTrue(found.firstAt > 181 && found.firstAt <= 184, "the torch found: " + found.firstAt);
        assertTrue(found.anyLight, "reported as a light");
        assertEquals(1, found.ids.size(), "one id: " + found.reportsPerId);
    }

    // ------------------------------------------------------------------ pc.50: the night's lights

    /** The night of the test above: mean luma about 12, a noisy sensor with hot pixels, the camera trembling. */
    static SyntheticScene night(long seed) {
        SyntheticScene scene = new SyntheticScene(W, H, seed);
        scene.darkness = 0.1f;
        scene.noiseSigma = 6;
        scene.hotPixels = 40;
        scene.shake = 1;
        return scene;
    }

    /**
     * A torch carried left at 15 px/s from t0, swinging towards the camera and away at 0.8 Hz: its spot 17 - 34 px
     * across, 0.5 - 2 % of the picture - in the dark a tenth to four tenths of all the light in it.
     */
    static SyntheticScene.Mover swingingTorch(SyntheticScene scene, double t0, double t1) {
        SyntheticScene.Mover m = new SyntheticScene.Mover(0, 0, 24, 24, 0, 0, (float) t0, (float) t1, 240, 128, 128, false);
        m.size = t -> {
            float s = (float) (25.5 + 8.5 * Math.sin(2 * Math.PI * 0.8 * t));
            return new float[]{s, s};
        };
        m.path = t -> {
            float s = m.size.apply(t)[0];
            return new float[]{(float) (285 - 15 * (t - t0)) - s / 2, 110 - s / 2};
        };
        scene.movers.add(m);
        return m;
    }

    /**
     * A vehicle driving right at 40 px/s from t0, seen from the side: its lamp, and ahead of it the ground in its beam
     * (64 x 24 px, 2.7 % of the picture, eight times the night's brightness).
     */
    static SyntheticScene.Mover headlights(SyntheticScene scene, double t0) {
        SyntheticScene.Mover beam = new SyntheticScene.Mover(-70, 118, 64, 24, 40, 0, (float) t0, (float) t0 + 12, 95, 128, 128, true);
        beam.glows = true;
        scene.movers.add(beam);
        scene.movers.add(new SyntheticScene.Mover(-78, 112, 6, 4, 40, 0, (float) t0, (float) t0 + 12, 250, 128, 128, false));
        return beam;
    }

    /**
     * pc.50: in the dark the picture's sum is small, and a light in it moved the factor of the sums as the camera's
     * exposure would. A torch swinging towards the camera and away (0.5 - 2 % of the picture) "stepped" on a quarter of
     * the pictures - each step rescaled the background and threw the tracks away: in 18 s not one report, 33 - 36 of
     * the 144 pictures not judged. The exposure is now that of the middle half of the picture's cells: found 2 s after
     * it shows, as one light, every picture judged.
     */
    @Test
    void aTorchSwingingAtNightIsFoundAsALight() {
        for (long seed : new long[]{41, 43}) {
            SyntheticScene scene = night(seed);
            MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
            Run quiet = run(scene, d, 0, 40, null);
            assertTrue(quiet.anyNight, "the dark picture is night");
            assertEquals(Set.of(), quiet.ids, "seed " + seed + ": the dark alone");
            swingingTorch(scene, 40, 58);
            Run found = run(scene, d, 40, 58, null);
            assertEquals(0, found.settling, "seed " + seed + ": every picture with the torch in it is judged");
            assertTrue(found.firstAt > 40 && found.firstAt <= 43, "seed " + seed + ": the torch found: " + found.firstAt);
            assertTrue(found.anyLight, "reported as a light");
            assertEquals(1, found.ids.size(), "seed " + seed + ": one id: " + found.reportsPerId);
            assertTrue(found.boxed >= 0.8 * 18 * FPS, "seed " + seed + ": boxed while it is there: " + found.boxed + " of 144 pictures");
        }
    }

    /**
     * pc.50: headlights. A vehicle crossing in the dark, the ground in its beam 2.7 % of the picture: as it drove in the
     * picture's sum rose by a third - 5 pictures not judged, the tracks thrown away each time, 17 - 23 boxes in all.
     * Now every picture is judged, and it is boxed, as a light, in HEADLIGHTS_BOXED pictures at least (47 - 58 boxes).
     * (Its two ids are the shift search's doing: in the dark it follows a bright patch now and then.)
     */
    @Test
    void headlightsCrossingAtNightAreFoundAsALight() {
        for (long seed : new long[]{41, 43}) {
            SyntheticScene scene = night(seed);
            MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
            assertEquals(Set.of(), run(scene, d, 0, 40, null).ids, "seed " + seed + ": the dark alone");
            headlights(scene, 40); // all of it in the picture from 42 s, its beam's front out again at 46.4 s, gone at 49.9 s
            Run found = run(scene, d, 40, 50, null);
            assertEquals(0, found.settling, "seed " + seed + ": every picture with the headlights in it is judged");
            assertTrue(found.firstAt > 40 && found.firstAt <= 44.5, "seed " + seed + ": found as it drives in: " + found.firstAt);
            assertTrue(found.anyLight, "reported as a light");
            assertTrue(found.boxed >= HEADLIGHTS_BOXED, "seed " + seed + ": boxed in " + found.boxed + " of 80 pictures");
        }
    }

    static final int HEADLIGHTS_BOXED = 30;

    /**
     * pc.50: and the camera's own exposure in the dark is still a step - every pixel 1.3 times as bright, or as dark:
     * that one picture is not judged, the background takes the new level at once, nothing is reported, and a torch
     * after it is found as before.
     */
    @Test
    void anExposureStepAtNightIsStillAStep() {
        for (double g : new double[]{1.3, 1 / 1.3}) {
            SyntheticScene scene = night(41);
            scene.gain = t -> t < 40 ? 1 : g;
            MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
            run(scene, d, 0, 40, null);
            Run step = run(scene, d, 40, 40.125, null);
            assertEquals(1, step.settling, "x " + g + ": the picture of the step is not judged");
            Run after = run(scene, d, 40.125, 60, null);
            assertEquals(Set.of(), after.ids, "x " + g + ": no alarm from the step: " + after.reportsPerId);
            assertEquals(0, after.settling, "x " + g + ": one step, taken at once");
            swingingTorch(scene, 60, 70);
            Run found = run(scene, d, 60, 70, null);
            assertTrue(found.firstAt > 60 && found.firstAt <= 63 && found.anyLight, "x " + g + ": a torch after the step: " + found.firstAt);
        }
    }

    // ------------------------------------------------------------------ pc.50: far off

    /**
     * Someone far off crossing the field between the trees: wide x 10 px, dark, walking right at 8 px/s and a little
     * downhill (so he lies on the working picture's rows every which way).
     */
    static SyntheticScene.Mover farWalker(float wide, double t0, double t1) {
        return new SyntheticScene.Mover(70, 103, wide, 10, 8, 0.3f, (float) t0, (float) t1, 60, 128, 128, false);
    }

    /**
     * pc.50: someone far off. A man 38 m away is 1.5 x 5 of the detector's pixels (1.8 m tall and 0.55 m wide through a
     * 74 degree lens, 160 working pixels across; here 3 x 10 px of the 320 wide picture), walking at 1.4 m/s: 8 px/s.
     * He is mostly one working pixel wide, and the noise rule (two foreground neighbours) took both ends of him: three
     * pixels left, under NORMAL's four - a box in 27 of 144 pictures with the camera still, in none in the gale's
     * shake. With the ends of an upright line kept: found 2 s after he shows, boxed in most pictures, the box on him,
     * camera still or shaken. (LOW asks six pixels, 30 m: not him. HIGH found him before, on the fine picture. A man
     * one pixel wide all the time - 2 px here, 55 m - is half hidden between two columns every other picture: still
     * not found on NORMAL.)
     */
    @Test
    void someoneFortyMetresOffOnePixelWideIsFoundOnNormal() {
        for (float shake : new float[]{0.5f, 4}) {
            SyntheticScene scene = windyWoods(5);
            scene.shake = shake;
            SyntheticScene.Mover walker = farWalker(3, 30, 48);
            scene.movers.add(walker);
            MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
            assertEquals(Set.of(), run(scene, d, 0, 30, null).ids, "shake " + shake + ": nothing before him");
            Run r = run(scene, d, 30, 48, null);
            assertTrue(r.firstAt >= 30 && r.firstAt <= 33, "shake " + shake + ": found within 3 s: " + r.firstAt);
            assertTrue(r.boxed >= 0.75 * 18 * FPS, "shake " + shake + ": boxed in " + r.boxed + " of 144 pictures");
            float[] truth = walker.at(r.lastAt);
            assertEquals((truth[0] + truth[2] / 2) / W, r.last.centreX(), 0.03, "shake " + shake + ": box centre x on him");
            assertEquals((truth[1] + truth[3] / 2) / H, r.last.centreY(), 0.05, "shake " + shake + ": box centre y on him");
        }
    }

    @Test
    void nothingIsJudgedWhileTheBackgroundLearns() {
        SyntheticScene scene = new SyntheticScene(W, H, 2);
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        byte[][] f = scene.frame(0);
        MotionDetector.Result first = d.process(f[0], W, H, f[1], f[2], 0);
        assertTrue(first.settling);
        assertTrue(first.detections.isEmpty());
        f = scene.frame(1);
        assertTrue(d.process(f[0], W, H, null, null, 1000).settling, "warming up (and without colour)");
    }

    // ------------------------------------------------------------------ close to the camera (1.0.56)

    /**
     * A room as a phone on a table sees it: textured walls and things, the phone trembling a little, nothing else
     * moving.
     */
    static SyntheticScene room(long seed) {
        SyntheticScene s = new SyntheticScene(W, H, seed);
        s.shake = 0.6f;
        return s;
    }

    /** The share of the picture a mover covers at a time. */
    static double cover(SyntheticScene.Mover m, double t) {
        if (!m.on(t)) return 0;
        float[] b = m.at(t);
        double x0 = Math.max(0, b[0]), x1 = Math.min(W, b[0] + b[2]), y0 = Math.max(0, b[1]), y1 = Math.min(H, b[1] + b[3]);
        return Math.max(0, x1 - x0) * Math.max(0, y1 - y0) / (W * H);
    }

    /** Whether a detection's box overlaps the mover's (picture coordinates, 0..1) at a time. */
    static boolean on(MotionDetector.Detection d, SyntheticScene.Mover m, double t) {
        float[] b = m.at(t);
        float x0 = Math.max(0, b[0]) / W, x1 = Math.min(W, b[0] + b[2]) / W, y0 = Math.max(0, b[1]) / H, y1 = Math.min(H, b[1] + b[3]) / H;
        return d.x < x1 && d.x + d.w > x0 && d.y < y1 && d.y + d.h > y0;
    }

    /**
     * 1.0.56, the pilot (phone pc.43, indoors): "filming myself and moving" - nothing was found. Someone 1.5 m from the
     * camera crosses its picture in about 2 s and covers 40 % of it, the exposure following them: found in 1.2 s, the
     * box on them. Before, the travel rules asked 2 s of a straight way and the big change read as the whole picture
     * changing - never found.
     */
    @Test
    void someoneCrossingCloseToTheCameraIsFound() {
        for (float luma : new float[]{55, 205}) {
            SyntheticScene scene = room(7);
            SyntheticScene.Mover p = new SyntheticScene.Mover(-130, 5, 130, 175, 200, 0, 10, 13, luma, 128, 128, true);
            scene.movers.add(p);
            scene.gain = t -> 1 + (luma < 128 ? 0.3 : -0.25) * cover(p, t);
            Run r = run(scene, new MotionDetector(MotionDetector.NORMAL), 0, 16, null);
            assertTrue(r.firstAt >= 10 && r.firstAt <= 11.2, "luma " + luma + ": found at " + r.firstAt + " " + r.reportsPerId);
            assertTrue(on(r.first, p, r.firstAt), "luma " + luma + ": the box on them");
        }
    }

    /**
     * Someone walks up to the camera (overwatch: the pilot pointed it at himself) and stands in front of it, filling 60 %
     * of the picture and moving a little: found as they come in, and still boxed while they stand there.
     */
    @Test
    void someoneWalkingUpToTheCameraAndStandingThereIsFound() {
        SyntheticScene scene = room(8);
        SyntheticScene.Mover p = new SyntheticScene.Mover(-200, 0, 200, 175, 0, 0, 10, 40, 60, 128, 128, true);
        p.path = t -> new float[]{(float) (t < 11.5 ? -200 + 173 * (t - 10) : 60 + 6 * Math.sin(2 * Math.PI * 0.5 * t)), 0};
        scene.movers.add(p);
        scene.gain = t -> 1 + 0.3 * cover(p, t);
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        Run r = run(scene, d, 0, 13, null);
        assertTrue(r.firstAt >= 10 && r.firstAt <= 11.5, "found as they came in: " + r.firstAt + " " + r.reportsPerId);
        Run standing = run(scene, d, 13, 16, null);
        assertTrue(standing.reports >= 0.8 * 3 * FPS, "boxed while standing there: " + standing.reports);
        assertTrue(on(standing.last, p, standing.lastAt), "the box on them");
    }

    /**
     * Someone sitting in view from the start (learnt as the room) waves at the camera, the hand and forearm beside them
     * against the room: found within 2 s, the box on the arm.
     */
    @Test
    void someoneSittingInViewWhoWavesIsFound() {
        SyntheticScene scene = room(9);
        scene.movers.add(new SyntheticScene.Mover(95, 15, 130, 165, 0, 0, 0, 60, 60, 128, 128, true));
        SyntheticScene.Mover arm = new SyntheticScene.Mover(0, 0, 40, 25, 0, 0, 10, 60, 90, 128, 128, true);
        arm.path = t -> new float[]{(float) (245 + 30 * Math.sin(2 * Math.PI * t)), (float) (30 + 15 * Math.cos(2 * Math.PI * t))};
        scene.movers.add(arm);
        Run r = run(scene, new MotionDetector(MotionDetector.NORMAL), 0, 14, null);
        assertTrue(r.firstAt >= 10 && r.firstAt <= 12, "found at " + r.firstAt + " " + r.reportsPerId);
        assertTrue(on(r.first, arm, r.firstAt), "the box on the arm");
    }

    /**
     * Someone in view from the start (learnt as the room) gets up and walks off at a slow pace: found within 2.5 s of
     * starting to move (before 1.0.56: 4.6 s).
     */
    @Test
    void someoneInViewWhoWalksOffIsFound() {
        SyntheticScene scene = room(5);
        SyntheticScene.Mover p = new SyntheticScene.Mover(90, 5, 130, 170, 0, 0, 0, 40, 60, 128, 128, true);
        p.path = t -> new float[]{(float) (t < 12 ? 90 : 90 + 30 * (t - 12)), 5};
        scene.movers.add(p);
        Run r = run(scene, new MotionDetector(MotionDetector.NORMAL), 0, 20, null);
        assertTrue(r.firstAt >= 12 && r.firstAt <= 14.5, "found at " + r.firstAt + " " + r.reportsPerId);
    }

    /**
     * A leafy branch right in front of the camera, filling much of the picture and swaying in gusts, the camera shaking:
     * the close-up rule (1.0.56) never takes it - it asks for a place that was calm before, and leaves flicker in place.
     * (The travel rules, made for trees 30 m off, do raise alarms on a branch this close: they did before 1.0.56 as well
     * - about as many in these scenes. Not solved here: keep foliage out of the picture's near part.)
     */
    @Test
    void aBranchCloseToTheCameraIsNeverTakenForSomethingClose() {
        for (long seed : new long[]{51, 53}) {
            for (int sens = MotionDetector.NORMAL; sens <= MotionDetector.HIGH; sens++) {
                SyntheticScene scene = new SyntheticScene(W, H, seed);
                scene.patches.add(new SyntheticScene.Patch(110, 80, 110, 70, 20f, 0.5f, 0.7f));
                scene.patches.add(new SyntheticScene.Patch(280, 30, 50, 40, 24f, 0.9f, 2.3f));
                scene.shake = 3;
                MotionDetector d = new MotionDetector(sens);
                run(scene, d, 0, 180, null);
                assertEquals(0, d.nearMade, "seed " + seed + ", sensitivity " + sens + ": taken for something close");
            }
        }
    }

    /** The windy woods and the wood's edge: the close-up rule takes nothing there either. */
    @Test
    void theWoodsAreNeverTakenForSomethingClose() {
        for (int sens = MotionDetector.LOW; sens <= MotionDetector.HIGH; sens++) {
            MotionDetector d = new MotionDetector(sens);
            run(woodsEdge(23 + sens), d, 0, 300, null);
            assertEquals(0, d.nearMade, "sensitivity " + sens);
        }
    }

    /** The camera knocked: moved a little and left so, then moved far (past what the shift search reaches): nothing. */
    @Test
    void aKnockedCameraGivesNoAlarm() {
        SyntheticScene scene = room(61);
        scene.shove = t -> t < 20 ? null : t < 40 ? new double[]{7, -4} : new double[]{-45, 20};
        Run r = run(scene, new MotionDetector(MotionDetector.NORMAL), 0, 60, null);
        assertEquals(Set.of(), r.ids, "false alarms " + r.reportsPerId);
    }

    /** A room's light switched on and off (the exposure follows a little late): nothing. */
    @Test
    void aRoomsLightSwitchedOnAndOffGivesNoAlarm() {
        SyntheticScene scene = room(62);
        scene.gain = t -> t < 15 ? 1 : t < 15.25 ? 1.9 : t < 30 ? 1.5 : t < 30.25 ? 0.75 : 1;
        Run r = run(scene, new MotionDetector(MotionDetector.NORMAL), 0, 45, null);
        assertEquals(Set.of(), r.ids, "false alarms " + r.reportsPerId);
    }

    @Test
    void theRecordCarriesTheBoxesBothWays() throws Exception {
        List<MotionRecord.Box> boxes = List.of(
                new MotionRecord.Box(7, 0.1f, 0.2f, 0.05f, 0.1f, -12.5f, 0.8f, 0.1f, MotionRecord.KIND_MOVING),
                new MotionRecord.Box(65535, 0.9f, 0.5f, 0.02f, 0.02f, 33.25f, 0.55f, 0.3f, MotionRecord.KIND_LIGHT));
        MotionRecord.Report sent = new MotionRecord.Report(MotionRecord.FLAG_ACTIVE | MotionRecord.FLAG_NIGHT, 68.4f, boxes);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        MotionRecord.write(new java.io.DataOutputStream(bytes), sent);
        byte[] data = bytes.toByteArray();
        assertEquals(2 + 4 + 2 * 15, data.length);
        DataReader in = new DataReader(data, true);
        assertEquals(MotionRecord.CODE, in.readShort());
        MotionRecord.Report got = MotionRecord.read(in);
        assertEquals(0, in.getRemaining());
        assertTrue(got.active());
        assertTrue(got.night());
        assertEquals(68.4f, got.hfovDeg, 0.01);
        assertEquals(2, got.boxes.size());
        MotionRecord.Box a = got.boxes.get(0), b = got.boxes.get(1);
        assertEquals(7, a.id);
        assertEquals(0.1f, a.x, 1e-4);
        assertEquals(0.2f, a.y, 1e-4);
        assertEquals(0.05f, a.w, 1e-4);
        assertEquals(0.1f, a.h, 1e-4);
        assertEquals(-12.5f, a.angleDeg, 0.01);
        assertEquals(0.8f, a.confidence, 0.01);
        assertEquals(0.1f, a.vegetation, 0.01);
        assertFalse(a.light());
        assertEquals(65535, b.id);
        assertTrue(b.light());
        assertEquals(33.25f, b.angleDeg, 0.01);
    }

    @Test
    void theAngleOffTheAxisFollowsThePinholeCamera() {
        assertEquals(0, MotionRecord.angleDeg(0.5f, 70), 1e-4);
        assertEquals(35, MotionRecord.angleDeg(1f, 70), 1e-3);
        assertEquals(-35, MotionRecord.angleDeg(0f, 70), 1e-3);
        // a quarter of the way in from the right edge: atan(0.5 tan 35) - less than half the half-angle's 17.5 + ...
        assertEquals(Math.toDegrees(Math.atan(0.5 * Math.tan(Math.toRadians(35)))), MotionRecord.angleDeg(0.75f, 70), 1e-3);
        assertEquals(0, MotionRecord.angleDeg(0.9f, 0), 0, "unknown field of view: no angle");
    }
}
