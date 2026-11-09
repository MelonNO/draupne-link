package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.TileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What painting costs on the Swing thread, which also shows the video: the OSD is drawn over every picture, the map at
 * each fix and while it is dragged. Measured off-screen (software, so slower than the real window); the budgets are
 * ten times what this PC measured, to catch a change that makes it many times dearer, not a slow runner. Measured
 * 2026-11-09 on a Ryzen 7 8840HS: OSD at 1920 x 1080 2.7 ms, the map at 1200 x 800 1.1 ms bare and 7 ms with a
 * 1-hour track (two wide anti-aliased strokes over its 4000 kept points - the length stroked, not the points, is the cost).
 */
class PaintCostTest {
    /** Mean ms of a paint, after a warm-up. */
    private static double mean(Runnable paint, int warm, int n) {
        for (int i = 0; i < warm; i++) paint.run();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) paint.run();
        return (System.nanoTime() - t0) / 1e6 / n;
    }

    @Test
    void theOsdOverAFullHdPictureStaysCheap() {
        OsdRenderer.Data d = new OsdPreview().data();
        OsdRenderer r = new OsdRenderer();
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        OsdLayout every = OsdLayout.defaults();
        for (OsdElement e : OsdElement.values()) every = every.with(e, new OsdLayout.Placement(true, every.get(e).x(), every.get(e).y()));
        OsdLayout layout = every;
        double ms = mean(() -> {
            Graphics2D g = img.createGraphics();
            r.paint(g, 0, 0, 1920, 1080, d, layout);
            g.dispose();
        }, 150, 200);
        assertTrue(ms < 27, "the OSD with every element: " + ms + " ms a picture");
    }

    @Test
    void theMapWithAnHourLongTrackStaysCheap(@TempDir Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        FlightTrack track = new FlightTrack(null);
        long now = System.currentTimeMillis();
        int points = 18_000; // an hour at 5 fixes a second
        for (int i = 0; i < points; i++) {
            double a = i / 300.0;
            track.sample(new FcTelemetry.Gps(2, 14, 40.0, 80, -1, 0, 59.9139 + 0.01 * Math.sin(a) + i * 1e-7, 10.7522 + 0.02 * Math.cos(a),
                    -1, 45, 0.8, now - (points - i) * 200L), true);
        }
        map.update(track, null, 45, false, true);
        map.setSize(1200, 800);
        map.fitTrack();
        double ms = mean(() -> map.snapshot(1200, 800), 20, 40);
        assertTrue(ms < 70, "the map with the track: " + ms + " ms a paint");
        assertTrue(map.trackPath().size() <= de.droiddrone.pc.map.TrackPath.MAX_POINTS + 1, "the line stays thinned");
    }
}
