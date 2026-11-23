package de.droiddrone.pc.route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet RT-14: power lines and masts near a route, from a made-up Overpass answer. */
class PowerLinesTest {
    // the route: due east from (60.0, 10.0) for about 1.1 km
    static final List<double[]> ROUTE = List.of(new double[]{60.0, 10.0}, new double[]{60.0, 10.02});
    static final String ANSWER = "{\"elements\":["
            // a line crossing north-south about 557 m along
            + "{\"type\":\"way\",\"id\":1,\"tags\":{\"power\":\"line\"},\"geometry\":[{\"lat\":59.99,\"lon\":10.01},{\"lat\":60.01,\"lon\":10.01}]},"
            // its pylon 100 m north of the crossing: not listed again
            + "{\"type\":\"node\",\"id\":2,\"lat\":60.0009,\"lon\":10.01,\"tags\":{\"power\":\"tower\"}},"
            // a mast 60 m south of the route near its start, 45 m high
            + "{\"type\":\"node\",\"id\":3,\"lat\":59.99946,\"lon\":10.002,\"tags\":{\"man_made\":\"mast\",\"height\":\"45 m\"}},"
            // a mast 2 km away
            + "{\"type\":\"node\",\"id\":4,\"lat\":60.02,\"lon\":10.01,\"tags\":{\"man_made\":\"mast\"}},"
            // something else
            + "{\"type\":\"node\",\"id\":5,\"lat\":60.0,\"lon\":10.005,\"tags\":{\"amenity\":\"bench\"}}]}";

    @Test
    void nearOnesListedInOrderAlongTheRoute() {
        List<PowerLines.Feature> f = PowerLines.parse(ANSWER);
        assertEquals(4, f.size(), "the bench isn't one");
        List<PowerLines.Near> near = PowerLines.near(ROUTE, f, PowerLines.NEAR_M);
        assertEquals(2, near.size(), near.toString());
        PowerLines.Near mast = near.get(0), line = near.get(1);
        assertEquals(PowerLines.Kind.MAST, mast.kind());
        assertEquals(60, mast.offM(), 3);
        assertEquals(111, mast.alongM(), 5);
        assertEquals(45, mast.heightM(), 0.01);
        assertEquals(PowerLines.Kind.LINE, line.kind());
        assertEquals(0, line.offM(), 0.01);
        assertEquals(557, line.alongM(), 5);
        assertEquals(60.0, line.lat(), 1e-5);
        assertEquals(10.01, line.lon(), 1e-5);
        assertTrue(line.word().contains("crossed"), line.word());
        assertTrue(mast.word().startsWith("Mast 111 m, 60 m off"), mast.word());
    }

    @Test
    void theBoxQueryAndCache(@TempDir Path dir) throws Exception {
        double[] b = PowerLines.box(ROUTE, 300);
        assertTrue(b[0] < 60 && b[2] > 60 && b[1] < 10 && b[3] > 10.02);
        String q = PowerLines.query(b);
        assertTrue(q.contains("power") && q.contains("minor_line") && q.contains("man_made") && q.contains("out geom"), q);
        Path f = dir.resolve(String.format(java.util.Locale.ROOT, "%.3f_%.3f_%.3f_%.3f.json", b[0], b[1], b[2], b[3]));
        Files.writeString(f, ANSWER);
        // fresh in the cache: no network asked (a null client would throw)
        assertEquals(4, PowerLines.fetch(b, dir, null).size());
    }
}
