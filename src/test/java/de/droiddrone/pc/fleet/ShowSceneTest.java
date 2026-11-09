package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tour's six drones' pictures (2026-11-09): each scene draws its models in front of the drone, something in it
 * moves from one picture to the next, and a picture is quick enough for the video. With SHOW_SCENES_DIR set, each
 * scene's pictures are written there as PNGs (to look at).
 */
class ShowSceneTest {
    private static final int W = 640, H = 360;

    private static BufferedImage picture(ShowScene s, Scene.View v, long at, boolean analysis) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        s.render(img, v, at, analysis);
        return img;
    }

    /** Pixels that differ by more than a few grey levels. */
    private static int changed(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255));
                if (d > 24) n++;
            }
        }
        return n;
    }

    @Test
    void everySceneShowsSomethingMovingAndDrawsFastEnough() throws Exception {
        String dir = System.getenv("SHOW_SCENES_DIR");
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 30);
        for (ShowScene.Kind k : ShowScene.Kind.values()) {
            ShowScene s = new ShowScene(k, 0, 0, 30, 0);
            BufferedImage empty = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            new SimWorld(0, 0).paint(empty.createGraphics(), new Scene.Camera(v, W, H), v, W, H, 1000, true, false);
            BufferedImage a = picture(s, v, 1000, true), b = picture(s, v, 1400, true);
            assertTrue(changed(empty, a) > W * H / 100, k + ": its models are in the picture (" + changed(empty, a) + " px)");
            assertTrue(changed(a, b) > 200, k + ": something moves in 0.4 s (" + changed(a, b) + " px)");
            for (int i = 0; i < 5; i++) picture(s, v, 2000 + i * 33, false); // warm
            long t0 = System.nanoTime();
            int frames = 10;
            for (int i = 0; i < frames; i++) picture(s, v, 3000 + i * 33, false);
            double ms = (System.nanoTime() - t0) / 1e6 / frames;
            assertTrue(ms < 60, k + ": " + ms + " ms a picture");
            if (dir != null) {
                Files.createDirectories(Path.of(dir));
                for (int i = 0; i < 3; i++) {
                    ImageIO.write(picture(s, v, 1000 + i * 700L, false), "png", Path.of(dir, k.name().toLowerCase() + "-" + i + ".png").toFile());
                }
                System.out.println(k + ": " + String.format("%.1f", ms) + " ms a picture");
            }
        }
    }

    @Test
    void theRaceTurnsAndBanksIntoTheTurn() {
        // the loop's near side: the quads cross from left to right, so they turn left all the way round
        ShowScene.Lap near = ShowScene.lap(8, 4, 7, 0);
        assertEquals(4, near.ahead(), 1e-9);
        assertEquals(0, near.right(), 1e-9);
        assertEquals(90, near.heading(), 1e-9, "heading right across the picture");
        assertTrue(near.turn() < 0, "turning left (anticlockwise seen from above)");
        assertEquals(4.0 / 49, -near.turn(), 1e-9, "an ellipse's curvature at the end of a semi-axis a: a / b^2");
    }

    @Test
    void theFpvDroneIsThinnedForTheVideo() {
        Raster3D.Mesh m = Raster3D.fpvDrone();
        assertTrue(m.triangles() > 1000 && m.triangles() < 20_000, m.triangles() + " triangles");
    }

    @Test
    void theTourSixSeeSixScenesTheRaceFirst() {
        assertEquals(6, TourShowcase.SCENES.length);
        assertEquals(ShowScene.Kind.RACE, TourShowcase.SCENES[0], "the first is the racer the pilot flies");
        assertEquals(6, java.util.Set.of(TourShowcase.SCENES).size());
    }

    @Test
    void aDroneOnTheGroundLooksFromItsMastOneInTheAirFromWhereItIs() {
        Scene.View ground = new Scene.View(1, 2, 0.1, 0, 0, 40);
        Scene.View eye = ShowScene.eye(ground);
        assertEquals(ShowScene.MAST_M, eye.alt());
        assertEquals(ShowScene.MAST_PITCH_DEG, eye.pitchDeg());
        assertEquals(40, eye.headingDeg());
        Scene.View flying = new Scene.View(1, 2, 12, 5, -20, 40);
        assertSame(flying, ShowScene.eye(flying));
    }

    @Test
    void theRaceIsHeardFromWhereTheQuadsAre() {
        ShowScene s = new ShowScene(ShowScene.Kind.RACE, 0, 0, 0, 0);
        float[] l = new float[1024], r = new float[1024];
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 0);
        for (int i = 0; i < 10; i++) s.addSound(l, r, v, 1000 + i * 21L);
        double e = 0;
        for (int i = 0; i < l.length; i++) e += l[i] * l[i] + r[i] * r[i];
        assertTrue(e > 0.01, "heard: " + e);
        float[] l2 = new float[1024], r2 = new float[1024];
        new ShowScene(ShowScene.Kind.JUMP, 0, 0, 0, 0).addSound(l2, r2, v, 1000);
        for (float x : l2) assertEquals(0f, x, "the jumper is silent");
    }
}
