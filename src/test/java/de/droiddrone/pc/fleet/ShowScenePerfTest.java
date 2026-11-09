package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a tour drone's 1280 x 720 picture costs (2026-11-10, the tactical scenes): a drone on its post (the camera
 * still: what stands still is kept, only what moves is drawn), the drone the pilot flies (the camera moving: all of it
 * each picture) and the motion detection's 320 x 180. On this PC about 1-3 ms, 20-35 ms and 1 ms. The budgets are
 * several times that, to catch a slip without failing on a busy release runner.
 */
class ShowScenePerfTest {
    @Test
    void aPictureFitsTheVideosFrameTime() {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB), small = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Scene.View still = new Scene.View(0, 0, 0, 0, 0, 30);
        for (ShowScene.Kind k : ShowScene.Kind.values()) {
            ShowScene s = new ShowScene(k, 0, 0, 30, 0);
            // warm the JIT, then time
            for (int i = 0; i < 15; i++) {
                s.render(img, still, 1000 + i * 33, false);
                s.render(img, flown(i), 1000 + i * 33, false);
                s.render(small, still, 1000 + i * 33, true);
            }
            int n = 20;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) s.render(img, still, 5000 + i * 33, false);
            double onPost = (System.nanoTime() - t0) / 1e6 / n;
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) s.render(img, flown(i), 5000 + i * 33, false);
            double flying = (System.nanoTime() - t0) / 1e6 / n;
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) s.render(small, still, 5000 + i * 33, true);
            double analysis = (System.nanoTime() - t0) / 1e6 / n;
            System.out.printf("%-10s 720p on its post %.1f ms, flown %.1f ms; 320 x 180 analysis %.2f ms%n", k, onPost, flying, analysis);
            assertTrue(onPost < 25, k + ": " + onPost + " ms on its post");
            assertTrue(flying < 150, k + ": " + flying + " ms flown");
            assertTrue(analysis < 15, k + ": " + analysis + " ms the analysis picture");
        }
    }

    /** A drone in the air, turning a little each picture. */
    private static Scene.View flown(int i) {
        return new Scene.View(i * 0.05, 0, 5 + i * 0.01, 2, -8, 30 + i * 0.3);
    }
}
