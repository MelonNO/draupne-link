package de.droiddrone.pc.fleet;

import de.droiddrone.common.MotionDetector;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/** The tour's BTR scene: the model, the picture, the detector finding it, the hit box, the sound's direction. */
class BtrSceneTest {
    /** Pictures for a look by eye when BTR_SHOTS names a folder (nothing is written otherwise). */
    private static void shot(BufferedImage img, String name) throws Exception {
        String dir = System.getenv("BTR_SHOTS");
        if (dir == null || dir.isEmpty()) return;
        new File(dir).mkdirs();
        ImageIO.write(img, "png", new File(dir, name));
    }

    /** The vehicle's dark or sand-coloured pixels: neither the pale world nor its blue lines. */
    private static boolean vehicle(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return Math.max(r, Math.max(g, b)) < 150 && b < g + 10;
    }

    private static int count(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if (vehicle(img.getRGB(x, y))) n++;
        return n;
    }

    @Test
    void theModelIsABtrSizedVehicleWithEightTurningWheels() {
        BtrScene.Model m = BtrScene.model();
        assertEquals(7.65, m.length(), 0.05);
        assertEquals(2.94, m.width(), 0.1);
        assertEquals(2.35, m.height(), 0.1);
        assertTrue(m.tri().length / 3 > 7000, "triangles " + m.tri().length / 3);
        int wheels = 0;
        for (float[] a : m.wheelAxle()) if (a != null) wheels++;
        assertEquals(8, wheels, "four axles, two sides");
    }

    @Test
    void theVehicleWaitsOutOfThePictureThenDrivesThroughIt() throws Exception {
        long t0 = 1_000_000;
        BtrScene s = new BtrScene(0, 0, 0, t0);
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 0);
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        s.render(img, v, t0 - 1000, false);
        shot(img, "btr-waiting.png");
        assertTrue(count(img) < 30, "waiting out of the picture: " + count(img));
        for (long t : new long[]{9000, 15000, 24000, 36000}) {
            s.render(img, v, t0 + t, false);
            shot(img, "btr-video-" + t + ".png");
            assertTrue(count(img) > 250, "the vehicle at " + t + " ms: " + count(img) + " pixels");
        }
        int pale = 0;
        for (int x = 0; x < 1280; x += 8) if ((img.getRGB(x, 20) & 0xff) > 200) pale++;
        assertTrue(pale > 140, "a pale sky");
        BufferedImage small = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        s.render(small, v, t0 + 9000, true);
        shot(small, "btr-analysis.png");
        // closer, from above: a drone flying in
        s.render(img, new Scene.View(10, 3, 5, 0, -12, 10), t0 + 9000, false);
        shot(img, "btr-close.png");
        if (System.getenv("BTR_SHOTS") != null) { // how long a 720p picture takes, for a look
            long a = System.nanoTime();
            for (int i = 0; i < 30; i++) s.render(img, new Scene.View(10, 3, 5, 0, -12, 10), t0 + 9000 + i * 33, false);
            System.out.println("BTR 720p picture: " + (System.nanoTime() - a) / 30 / 1_000_000.0 + " ms");
        }
    }

    @Test
    void theRealMotionDetectorFindsTheVehicleDrivingInOnceTheWatchingIsOlderThanItsStrictStart() {
        long t0 = 2_000_000;
        BtrScene s = new BtrScene(0, 0, 0, t0);
        s.driveFrom(t0 + TourScenario.DRIVE_IN_AFTER_MS);
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 0);
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        MotionDetector d = new MotionDetector(MotionDetector.NORMAL);
        long foundAt = -1;
        for (long t = 0; t <= 45_000 && foundAt < 0; t += 125) {
            s.render(img, v, t0 + t, true);
            int[] size = new int[2];
            byte[] y = Scene.luma(Scene.pixels(img), 320, 180, 320, size);
            MotionDetector.Result r = d.process(y, size[0], size[1], null, null, t0 + t);
            if (!r.detections.isEmpty()) foundAt = t;
        }
        assertTrue(foundAt >= 0, "the detector never found the BTR");
        assertTrue(foundAt >= TourScenario.DRIVE_IN_AFTER_MS, "nothing before it drives in (" + foundAt + " ms)");
        assertTrue(foundAt <= TourScenario.DRIVE_IN_AFTER_MS + 12_000, "found " + (foundAt - TourScenario.DRIVE_IN_AFTER_MS) + " ms after it set off");
    }

    @Test
    void aDroneInsideTheVehiclesBoxHitsItAndOneBesideItDoesNot() {
        long t0 = 3_000_000;
        BtrScene s = new BtrScene(0, 0, 0, t0);
        long at = t0 + 14_000;
        double[] q = s.pose(at);
        assertTrue(s.hits(q[0], q[1], 1.5, at), "in the middle of it");
        assertTrue(s.hits(q[0], q[1], 2.5, at), "touching its roof");
        assertFalse(s.hits(q[0], q[1], 3.5, at), "a metre over it");
        double h = Math.toRadians(q[2]);
        assertFalse(s.hits(q[0] - Math.sin(h) * 3, q[1] + Math.cos(h) * 3, 1, at), "3 m to its side");
        assertTrue(Math.hypot(q[0], q[1]) > 50, "the route is well away from the drone");
    }

    @Test
    void theRealRecordingsLoadAndArmingSpinsTheMotorsUp() {
        for (String n : new String[]{"btr-idle", "btr-drive", "fpv-spinup", "fpv-flight"}) {
            SoundSample s = SoundSample.of(n);
            assertTrue(s.seconds() > (n.equals("fpv-spinup") ? 1.0 : 1.5), n + ": " + s.seconds() + " s");
            double e = 0;
            for (float v : s.data) e += v * v;
            assertTrue(Math.sqrt(e / s.data.length) > 0.05, n + " is not silence");
        }
        // the arming is a normal FPV's motors starting, not a whoop's high whine ("sounds like a jet engine", the
        // pilot): its sound sits low - few zero crossings a second (the whoop cut had about 4 kHz of centroid)
        SoundSample spin = SoundSample.of("fpv-spinup");
        assertEquals(22050, spin.rate, 1, "the arming is kept at 22.05 kHz");
        int crossings = 0;
        for (int i = 1; i < spin.data.length; i++) if ((spin.data[i - 1] < 0) != (spin.data[i] < 0)) crossings++;
        double perSecond = crossings / spin.seconds();
        assertTrue(perSecond < 3000, "the arming sounds low, like a quad's motors: " + Math.round(perSecond) + " zero crossings/s");
        SoundMaker m = new SoundMaker(1);
        m.setQuiet(true);
        double off = 0, on = 0;
        for (int i = 0; i < 20; i++) for (float v : m.next(-1)) off += v * v; // disarmed: the breeze alone
        for (int i = 0; i < 20; i++) for (float v : m.next(0.1)) on += v * v; // armed: the spin-up
        assertTrue(on > 20 * off, "the motors are heard at the arming: " + on + " vs " + off);
    }

    @Test
    void itPatrolsFarOffWithBendsSpeedChangesAndHaltsNeverALoop() {
        long t0 = 4_000_000;
        BtrScene s = new BtrScene(0, 0, 0, t0);
        double nearest = 1e9, farthest = 0, top = 0;
        int halts = 0;
        boolean halted = false;
        java.util.Set<Long> headings = new java.util.HashSet<>();
        double[] first = null;
        int returns = 0;
        for (long t = 10_000; t <= 600_000; t += 500) {
            double[] q = s.pose(t0 + t);
            double d = Math.hypot(q[0], q[1]);
            nearest = Math.min(nearest, d);
            farthest = Math.max(farthest, d);
            double v = s.speedAt(t0 + t);
            top = Math.max(top, v);
            if (v == 0 && !halted) halts++;
            halted = v == 0;
            headings.add(Math.round(((q[2] % 360) + 360) % 360 / 30));
            if (first == null) first = q;
            else if (t > 60_000 && Math.hypot(q[0] - first[0], q[1] - first[1]) < 2) returns++;
        }
        assertTrue(nearest > BtrScene.NEAR_M - 15, "never close to the drone: " + nearest);
        assertTrue(farthest < BtrScene.FAR_M * 1.6, "within its area: " + farthest);
        assertTrue(top > 6 && top < 10, "a dash now and then: " + top);
        assertTrue(halts >= 3, "halts on the way: " + halts);
        assertTrue(headings.size() >= 8, "it turns every way over ten minutes: " + headings.size());
        assertTrue(returns < 3, "not a loop: back at its first place " + returns + " times");
    }

    @Test
    void theEngineIsHeardFromItsSideAndLouderNear() {
        float[] l = new float[AacEncoder.FRAME], r = new float[AacEncoder.FRAME];
        BtrSound right = new BtrSound(7);
        double ll = 0, rr = 0;
        for (int i = 0; i < 40; i++) {
            java.util.Arrays.fill(l, 0);
            java.util.Arrays.fill(r, 0);
            right.add(l, r, 20, Math.toRadians(70), 0, 6); // 20 m away, to the right
            for (int k = 0; k < l.length; k++) {
                ll += l[k] * l[k];
                rr += r[k] * r[k];
            }
        }
        assertTrue(rr > 3 * ll, "louder on the right: L " + ll + " R " + rr);
        double near = energy(new BtrSound(7), 10), far = energy(new BtrSound(7), 120);
        assertTrue(near > 10 * far, "nearer is louder: " + near + " vs " + far);
    }

    @Test
    void theScenarioSoundIsEncodedInStereo() throws Exception {
        try (AacEncoder enc = new AacEncoder(96_000, 2)) {
            // the AudioSpecificConfig's first two bytes (FFmpeg may add an extension after them)
            assertArrayEquals(new byte[]{0x11, (byte) 0x90}, java.util.Arrays.copyOf(enc.config(), 2), "AAC-LC, 48 kHz, two channels");
            float[] l = new float[AacEncoder.FRAME], r = new float[AacEncoder.FRAME];
            BtrSound s = new BtrSound(1);
            int frames = 0;
            for (int i = 0; i < 10; i++) {
                java.util.Arrays.fill(l, 0);
                java.util.Arrays.fill(r, 0);
                s.add(l, r, 15, 1, 0, 6);
                frames += enc.encode(l, r).size();
            }
            assertTrue(frames >= 8, "frames out: " + frames);
        }
    }

    private static double energy(BtrSound s, double dist) {
        float[] l = new float[AacEncoder.FRAME], r = new float[AacEncoder.FRAME];
        double e = 0;
        for (int i = 0; i < 40; i++) {
            java.util.Arrays.fill(l, 0);
            java.util.Arrays.fill(r, 0);
            s.add(l, r, dist, 0, 0, 6);
            for (float x : l) e += x * x;
        }
        return e;
    }
}
