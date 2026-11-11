package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Airspace;
import de.droiddrone.pc.map.TileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 1.0.64 AIRSPACE on the map: drawn while on - TOOLS out or not - never in an exported picture; a click says what it is. */
class AirspaceOverlayTest {
    private static MapView map(Path dir) {
        MapView map = new MapView(new TileCache(dir, "test", false));
        map.setSize(400, 300);
        map.setFollow(false);
        map.centreOn(59.91, 10.73, 13);
        return map;
    }

    /** An R-area round the middle of the view. */
    private static Airspace restricted(MapView map) {
        double[] v = map.visibleArea();
        double dLat = (v[1] - v[0]) / 4, dLon = (v[3] - v[2]) / 4;
        double cLat = (v[0] + v[1]) / 2, cLon = (v[2] + v[3]) / 2;
        List<double[]> ring = List.of(new double[]{cLat - dLat, cLon - dLon}, new double[]{cLat - dLat, cLon + dLon},
                new double[]{cLat + dLat, cLon + dLon}, new double[]{cLat + dLat, cLon - dLon});
        return new Airspace("r1", "EN R102 OSLO", Airspace.Kind.RESTRICTED, ring, "GND", "3500 FT AMSL",
                List.of("EN R102 OSLO", "R-AREA  GND - 3500 FT AMSL"), false);
    }

    private static int reddish(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y), r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                if (r > g + 60 && r > b + 60) n++;
            }
        }
        return n;
    }

    @Test
    void anAreaIsDrawnWhileOnAndStaysWhenToolsFold(@TempDir Path dir) {
        MapView map = map(dir);
        int before = reddish(map.snapshot(400, 300));
        int pictureBefore = reddish(map.picture(400, 300));
        map.setAirspace(List.of(restricted(map)), "Airspace: openAIP (CC BY-NC)");
        int on = reddish(map.snapshot(400, 300));
        assertTrue(on > before + 300, before + " -> " + on);
        map.setToolOverlaysShown(false);
        assertEquals(on, reddish(map.snapshot(400, 300)), "kept on the map when TOOLS folds - needed in flight");
        assertEquals(pictureBefore, reddish(map.picture(400, 300)), "not in an exported picture");
        map.setAirspace(List.of(), null);
        assertEquals(before, reddish(map.snapshot(400, 300)));
    }

    @Test
    void aClickInsideSaysWhatItIsAndAClickOutsideClosesIt(@TempDir Path dir) {
        MapView map = map(dir);
        Airspace r = restricted(map);
        map.setAirspace(List.of(r), null);
        click(map, 200, 150);
        assertEquals(r, map.airspaceInfo());
        click(map, 5, 290);
        assertNull(map.airspaceInfo(), "outside: the box goes");
        assertEquals(r, MapAirspaceLayer.at(List.of(r), map.projection(), 200, 150));
        assertNull(MapAirspaceLayer.at(List.of(r), map.projection(), 5, 290));
    }

    /** To look at it: AIRSPACE_SHOT=path.png renders an R-area, a D-area and a NOTAM, the R-area's box open. */
    @Test
    void shot(@TempDir Path dir) throws Exception {
        String out = System.getenv("AIRSPACE_SHOT");
        if (out == null) return;
        MapView map = map(dir);
        map.setSize(700, 450);
        map.centreOn(59.91, 10.73, 12);
        double[] v = map.visibleArea();
        Airspace r = restricted(map);
        Airspace d = new Airspace("d1", "EN D301 TEST", Airspace.Kind.DANGER, List.of(new double[]{v[0] + 0.01, v[2] + 0.02},
                new double[]{v[0] + 0.01, v[2] + 0.12}, new double[]{v[0] + 0.06, v[2] + 0.07}), "GND", "FL95", List.of("EN D301 TEST", "D-AREA  GND - FL95"), false);
        Airspace n = new Airspace("n1", "A1250/26", Airspace.Kind.NOTAM, Airspace.circle(v[1] - 0.025, v[3] - 0.06, 1500), "GND", "400FT AGL",
                List.of("NOTAM A1250/26", "10 Nov 08:00Z - PERM", "GND - 400FT AGL", "UAS ACTIVITY PSN 600000N 0110000E RADIUS 500M."), true);
        map.setAirspace(List.of(r, d, n), "Airspace: openAIP (CC BY-NC)");
        map.setSize(700, 450);
        click(map, 350, 225);
        javax.imageio.ImageIO.write(map.snapshot(700, 450), "png", new java.io.File(out));
    }

    private static void click(MapView map, int x, int y) {
        for (java.awt.event.MouseListener l : map.getMouseListeners()) {
            l.mouseClicked(new MouseEvent(map, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x, y, 1,
                    false, MouseEvent.BUTTON1));
        }
    }
}
