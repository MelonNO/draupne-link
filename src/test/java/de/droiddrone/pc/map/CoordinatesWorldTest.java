package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every position format the map writes, read back, all over the world (the bench, 2026-11-19): what GO TO and
 * "Place at..." get when the pilot copies a grid off the map and pastes it. The poles (UPS) are left out: the map
 * says so itself.
 */
class CoordinatesWorldTest {
    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static List<double[]> places() {
        List<double[]> all = new ArrayList<>();
        Random random = new Random(20261119L);
        for (int i = 0; i < 20_000; i++) all.add(new double[]{-79.9 + random.nextDouble() * 163.8, -180 + random.nextDouble() * 360});
        // the seams: zone edges, the equator, Greenwich, the date line, band edges, Norway's and Svalbard's zones
        double[] lats = {-79.99, -72, -8, -0.0001, 0, 0.0001, 8, 56, 59.9, 63.9999, 64, 71.9999, 72, 78.2, 83.99};
        double[] lons = {-180, -179.9999, -6, -0.0001, 0, 0.0001, 2.9999, 3, 5.9999, 6, 8.9999, 9, 11.9999, 12, 20.9999, 21, 33, 41.9999, 42, 174, 179.9999};
        for (double lat : lats) for (double lon : lons) all.add(new double[]{lat, lon});
        return all;
    }

    @Test
    void everyFormatReadsBackToThePlaceItWasWrittenFor() {
        List<String> wrong = new ArrayList<>();
        for (double[] p : places()) {
            for (Coordinates.Format f : Coordinates.Format.values()) {
                String text = Coordinates.format(f, p[0], p[1]);
                double[] back = Coordinates.parse(text);
                double limit = f == Coordinates.Format.DMS ? 4 : 2.5; // a tenth of a second is 3 m; the others are to the metre
                if (back == null) wrong.add(f + " \"" + text + "\" of " + p[0] + ", " + p[1] + " is not read");
                else if (metres(p[0], p[1], back[0], back[1]) > limit) {
                    wrong.add(f + " \"" + text + "\" of " + p[0] + ", " + p[1] + " reads as " + back[0] + ", " + back[1]
                            + ", " + Math.round(metres(p[0], p[1], back[0], back[1])) + " m off");
                }
                if (wrong.size() > 12) break;
            }
            if (wrong.size() > 12) break;
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }
}
