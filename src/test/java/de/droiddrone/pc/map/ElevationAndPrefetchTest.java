package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ElevationAndPrefetchTest {
    @Test
    void kartverketInNorwayOpenMeteoElsewhere() {
        assertTrue(Elevation.url(59.9139, 10.7522).startsWith("https://ws.geonorge.no/hoydedata/v1/punkt?nord=59.913900&ost=10.752200"));
        assertTrue(Elevation.url(52.52, 13.41).startsWith("https://api.open-meteo.com/v1/elevation?latitude=52.520000&longitude=13.410000"));
        assertTrue(Elevation.url(59.33, 18.07).startsWith("https://api.open-meteo.com"), "Stockholm: not Kartverket");
    }

    @Test
    void parsesBothAnswers() {
        assertEquals(4.9, Elevation.parse("{\"koordsys\":4258,\"punkter\":[{\"datakilde\":\"dtm1\",\"terreng\":\"BymessigBebyggelse\",\"x\":10.7522,\"y\":59.9139,\"z\":4.9}]}"), 1e-9);
        assertEquals(38.0, Elevation.parse("{\"elevation\":[38.0]}"), 1e-9);
        assertEquals(-2.5, Elevation.parse("{\"elevation\":[-2.5]}"), 1e-9);
        assertNull(Elevation.parse("{\"error\":true}"));
        assertNull(Elevation.parse(null));
    }

    @Test
    void placesTenMetresApartShareAnAnswer() {
        Elevation e = new Elevation("test", false);
        assertEquals(Elevation.key(59.91390, 10.75220), Elevation.key(59.91394, 10.75223));
        assertNotEquals(Elevation.key(59.9139, 10.7522), Elevation.key(59.9149, 10.7522));
        Double[] got = {0.0};
        e.lookup(59.9139, 10.7522, z -> got[0] = z);
        // no network: the answer (null) comes on the lookup thread; nothing to assert but no exception
    }

    @Test
    void thePrefetchPlanTakesTheNearestLevelsFirstWithinTheBudget() {
        // a view about 1.1 km across at zoom 15 around Oslo
        List<TileCache.Tile> plan = TileCache.plan(15, 12, 18, 59.910, 59.918, 10.745, 10.760, 100000);
        assertEquals(15, plan.get(0).z(), "the level looked at first");
        int[] perZoom = new int[19];
        for (TileCache.Tile t : plan) perZoom[t.z()]++;
        assertTrue(perZoom[15] >= 2 && perZoom[15] <= 6, "z15: " + perZoom[15]);
        assertTrue(perZoom[16] >= 2 * perZoom[15] && perZoom[16] <= 6 * perZoom[15], "about four times as many a level down: " + perZoom[16] + " vs " + perZoom[15]);
        assertEquals(1, perZoom[12], "far out: one tile");
        assertTrue(perZoom[18] > 100, "the deepest level has the most");
        int firstDeep = -1, firstFar = -1;
        for (int i = 0; i < plan.size(); i++) {
            if (firstDeep < 0 && plan.get(i).z() == 16) firstDeep = i;
            if (firstFar < 0 && plan.get(i).z() == 14) firstFar = i;
        }
        assertTrue(firstDeep < firstFar, "one level in before one level out");

        List<TileCache.Tile> capped = TileCache.plan(15, 12, 18, 59.910, 59.918, 10.745, 10.760, 50);
        assertEquals(50, capped.size(), "the budget caps it");
    }

    @Test
    void openStreetMapIsNeverPrefetched() {
        assertFalse(TileSource.OSM.prefetchAllowed);
        assertTrue(TileSource.KARTVERKET_TOPO.prefetchAllowed);
    }
}
