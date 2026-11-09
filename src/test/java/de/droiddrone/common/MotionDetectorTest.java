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
