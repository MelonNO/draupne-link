package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.TileCache;
import org.junit.jupiter.api.Tag;
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
@Tag("perf")
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

    /**
     * I-011: what LINK HISTORY adds to a 1080p OSD - the editor's sample (a link that goes bad: three colours, marks, a
     * gap), and the worst a link can give it: every tick another colour and a mark. The strip's own cost is the paint
     * with it less the paint without it, each the best of seven rounds taken in turn (a busy PC slows some rounds).
     * Measured 2026-11-22 on a busy PC (a long test run beside it, this at low priority): 0.19 ms, the worst 0.38 ms.
     */
    @Test
    void theLinkHistoryStripCostsWellUnderAMillisecond() {
        OsdRenderer r = new OsdRenderer();
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        OsdLayout without = OsdLayout.defaults();
        for (OsdElement e : OsdElement.values()) without = without.with(e, new OsdLayout.Placement(false, without.get(e).x(), without.get(e).y()));
        OsdLayout with = without.with(OsdElement.LINK_HISTORY, new OsdLayout.Placement(true, OsdElement.LINK_HISTORY.defaultX, OsdElement.LINK_HISTORY.defaultY));
        double[] ms = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        OsdLayout[] layouts = {without, with, with};
        for (int round = 0; round < 7; round++) {
            for (int i = 0; i < 3; i++) {
                // stamped up to now before each run: the moment a run takes leaves the window as full
                LinkHistory h = new LinkHistory();
                long now = de.droiddrone.pc.MonoClock.ms();
                if (i < 2) {
                    OsdPreview.sampleHistory(h, now, 38);
                } else {
                    for (int k = 0; k < LinkHistory.SIZE; k++) {
                        h.add(now - (LinkHistory.SIZE - 1 - k) * 100L, k % 3 == 0 ? 50 : k % 3 == 1 ? 140 : 260, 38, k, k / 2, false);
                    }
                }
                OsdRenderer.Data d = new OsdRenderer.Data(true, true, 42, true, false, null, null, false, null, null, false, false, false, false, null,
                        false, null, false).withLinkHistory(h);
                OsdLayout layout = layouts[i];
                ms[i] = Math.min(ms[i], mean(() -> {
                    Graphics2D g = img.createGraphics();
                    r.paint(g, 0, 0, 1920, 1080, d, layout);
                    g.dispose();
                }, round == 0 ? 300 : 20, 200));
            }
        }
        double strip = ms[1] - ms[0], worstStrip = ms[2] - ms[0];
        System.out.printf(java.util.Locale.ROOT, "LINK HISTORY at 1080p: %.3f ms (a link that goes bad), %.3f ms (every tick another colour)%n", strip, worstStrip);
        assertTrue(strip < 1, "the strip: " + strip + " ms a picture");
        assertTrue(worstStrip < 2, "its worst: " + worstStrip + " ms a picture");
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
