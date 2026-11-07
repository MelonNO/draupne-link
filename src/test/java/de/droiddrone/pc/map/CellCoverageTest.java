package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 4G COVERAGE (1.0.44): OpenCelliD's answers and files, the boxes the area is fetched in, and the estimate. No network. */
class CellCoverageTest {
    private static final RadioHorizon.Terrain FLAT = (lat, lon) -> 100;

    // ---------------------------------------------------------------- OpenCelliD

    @Test
    void readsTheAreaApisJsonAndItsErrors() {
        String json = "{\"count\":2,\"cells\":[{\"lat\":59.91,\"lon\":10.75,\"mcc\":242,\"mnc\":1,\"lac\":7033,\"cellid\":17811,"
                + "\"averageSignalStrength\":0,\"range\":1898,\"samples\":12,\"changeable\":1,\"radio\":\"LTE\"},"
                + "{\"lat\":59.92,\"lon\":10.76,\"mcc\":242,\"mnc\":2,\"range\":500,\"samples\":3,\"radio\":\"NR\"}]}";
        OpenCellId.Answer a = OpenCellId.parseJson(json);
        assertNotNull(a.cells());
        assertEquals(2, a.cells().size());
        OpenCellId.Cell c = a.cells().get(0);
        assertEquals(new OpenCellId.Cell("LTE", 242, 1, 59.91, 10.75, 1898, 12), c);
        assertEquals("NR", a.cells().get(1).radio());

        assertEquals(List.of(), OpenCellId.parseJson("{\"count\":0,\"cells\":[]}").cells());
        assertEquals(List.of(), OpenCellId.parseJson("{\"error\":\"Cell not found\",\"code\":1}").cells(), "nothing found is an empty box");
        OpenCellId.Answer key = OpenCellId.parseJson("{\"error\":\"API Key not known\",\"code\":2}");
        assertNull(key.cells());
        assertEquals(2, key.errorCode());
        assertEquals(7, OpenCellId.parseJson("{\"error\":\"Daily limit exceeded for your API key\",\"code\":7}").errorCode());
        assertNull(OpenCellId.parseJson("<html>gateway timeout</html>").cells(), "not JSON: an error, never an empty box kept for a month");
    }

    @Test
    void readsTheCsvFilesKeepingOnly4gAnd5g() {
        String download = "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal\n"
                + "GSM,242,1,100,200,,10.70,59.90,3000,5,1,1409522613,1409522613,0\n"
                + "LTE,242,2,300,400,12,10.71,59.91,1200,40,1,1409522613,1409522613,0\n"
                + "NR,242,14,300,401,12,10.72,59.92,600,2,1,1409522613,1409522613,0\n"
                + "LTE,broken line\n";
        List<OpenCellId.Cell> cells = OpenCellId.parseCsv(download);
        assertEquals(2, cells.size());
        assertEquals(new OpenCellId.Cell("LTE", 242, 2, 59.91, 10.71, 1200, 40), cells.get(0));
        assertEquals(new OpenCellId.Cell("NR", 242, 14, 59.92, 10.72, 600, 2), cells.get(1));

        // the download files have no header line: the same columns
        assertEquals(cells, OpenCellId.parseCsv(download.substring(download.indexOf('\n') + 1)));
        // the API's csv answer names its columns in another order
        String api = "lat,lon,mcc,mnc,lac,cellid,averageSignalStrength,range,samples,changeable,radio\n59.91,10.71,242,2,300,400,0,1200,40,1,LTE\n";
        assertEquals(List.of(cells.get(0)), OpenCellId.parseCsv(api));
        // and a box's own file reads back as written
        assertEquals(cells, OpenCellId.parseCsv(OpenCellId.toCsv(cells)));
    }

    @Test
    void namesNorwaysNetworks() {
        assertEquals(OpenCellId.Operator.TELENOR, OpenCellId.Operator.of(242, 1));
        assertEquals(OpenCellId.Operator.TELIA, OpenCellId.Operator.of(242, 2));
        assertEquals(OpenCellId.Operator.ICE, OpenCellId.Operator.of(242, 14));
        assertEquals(OpenCellId.Operator.TELIA, OpenCellId.Operator.of(242, 8));
        assertNull(OpenCellId.Operator.of(242, 99), "TampNet offshore: none of the three");
        assertNull(OpenCellId.Operator.of(240, 1), "a Swedish cell");
        OpenCellId.Cell swedish = new OpenCellId.Cell("LTE", 240, 1, 59, 12, 0, 0);
        assertTrue(OpenCellId.Operator.ALL.has(swedish));
        assertFalse(OpenCellId.Operator.TELENOR.has(swedish));
        assertEquals(OpenCellId.Operator.ICE, OpenCellId.Operator.parse("ice"));
        assertEquals(OpenCellId.Operator.ALL, OpenCellId.Operator.parse(""));
    }

    @Test
    void theBoxesStayUnderTheApisLimitAndCoverTheArea() {
        for (double lat : new double[]{0.5, 30, 45, 58.1, 59.9, 63.4, 69.6, 70.9, 78.2, -45}) {
            OpenCellId.Tile t = OpenCellId.Tile.at(lat, 10.7);
            assertTrue(t.areaM2() <= OpenCellId.MAX_AREA_M2, "at " + lat + ": " + t.areaM2());
            assertTrue(t.areaM2() > OpenCellId.MAX_AREA_M2 / 3, "not needlessly small at " + lat + ": " + t.areaM2());
            assertTrue(t.contains(lat, 10.7));
            assertTrue(t.south() <= lat && lat < t.north() && t.west() <= 10.7 && 10.7 < t.east());
        }
        // an area of about 6 x 6 km over Oslo: every point of it in one of the boxes, the middle's box first
        double minLat = 59.88, maxLat = 59.94, minLon = 10.68, maxLon = 10.80;
        List<OpenCellId.Tile> tiles = OpenCellId.tiles(minLat, maxLat, minLon, maxLon);
        for (double lat = minLat; lat <= maxLat; lat += 0.004) {
            for (double lon = minLon; lon <= maxLon; lon += 0.004) assertTrue(tiles.contains(OpenCellId.Tile.at(lat, lon)), lat + ", " + lon);
        }
        assertEquals(OpenCellId.Tile.at(59.91, 10.74), tiles.get(0));
        assertEquals(tiles.size(), new java.util.HashSet<>(tiles).size(), "no box twice");
        assertTrue(tiles.size() < 30, "about 36 km2 in boxes of about 3 km2: " + tiles.size());
        assertTrue(OpenCellId.Tile.at(59.9, 10.7).bbox().matches("59\\.\\d+,10\\.\\d+,59\\.\\d+,10\\.\\d+"), "latmin,lonmin,latmax,lonmax");
    }

    @Test
    void aBoxIsFetchedAgainAfterThirtyDays(@TempDir Path dir) throws Exception {
        long now = 1_800_000_000_000L, day = 24 * 3600 * 1000L;
        assertTrue(OpenCellId.fresh(now - 29 * day, now));
        assertFalse(OpenCellId.fresh(now - 31 * day, now));
        assertFalse(OpenCellId.fresh(now + 5 * day, now), "a time in the future (a wrong clock) is not trusted");

        OpenCellId store = new OpenCellId(dir, "test", false);
        OpenCellId.Tile t = OpenCellId.Tile.at(59.9, 10.7), other = new OpenCellId.Tile(t.row() + 1, t.col());
        assertNull(store.cached(t));
        assertFalse(store.isFresh(t, now));
        OpenCellId.Cell c = new OpenCellId.Cell("LTE", 242, 1, 59.9, 10.7, 900, 7);
        store.store(t, List.of(c), System.currentTimeMillis() - 40 * day);
        store.store(other, List.of(), System.currentTimeMillis());
        long today = System.currentTimeMillis();
        assertEquals(List.of(c), store.cached(t), "an old box is still used until it is fetched again");
        assertFalse(store.isFresh(t, today));
        assertTrue(store.isFresh(other, today), "a box without cells is remembered too");
        assertEquals(List.of(), store.cached(other));
        // no key: nothing asked, an error answer
        assertEquals(2, store.fetch(t, " ").errorCode());
        // offline: nothing kept, the old box stays
        assertNull(store.fetch(t, "test-key").cells());
        assertEquals(List.of(c), store.cached(t));
    }

    @Test
    void anImportedCountryFileCoversItsArea(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("242.csv");
        java.nio.file.Files.writeString(file, "LTE,242,1,1,1,1,10.0,59.0,1000,5,1,0,0,0\nLTE,242,2,1,2,1,11.0,60.0,1000,5,1,0,0,0\nGSM,242,2,1,3,,10.5,59.5,1000,5,1,0,0,0\n");
        OpenCellId store = new OpenCellId(dir.resolve("store"), "test", false);
        assertFalse(store.hasImport());
        assertEquals(2, store.importCsv(file));
        assertTrue(store.hasImport());
        assertTrue(store.isFresh(OpenCellId.Tile.at(59.5, 10.5), System.currentTimeMillis()), "inside: no requests");
        assertFalse(store.isFresh(OpenCellId.Tile.at(62, 10.5), System.currentTimeMillis()));
        assertEquals(1, store.importedIn(58.9, 59.1, 9.9, 10.1).size());
        assertTrue(new OpenCellId(dir.resolve("store"), "test", false).hasImport(), "kept for the next start");
    }

    // ---------------------------------------------------------------- the estimate

    @Test
    void cellsOfOneMastAreOneSite() {
        List<OpenCellId.Cell> cells = List.of(
                new OpenCellId.Cell("LTE", 242, 1, 59.90000, 10.70000, 0, 0),
                new OpenCellId.Cell("LTE", 242, 1, 59.90010, 10.70010, 0, 0),
                new OpenCellId.Cell("NR", 242, 1, 59.90005, 10.70000, 0, 0),
                new OpenCellId.Cell("LTE", 242, 2, 59.90000, 10.70000, 0, 0),
                new OpenCellId.Cell("LTE", 242, 1, 59.95, 10.70, 0, 0));
        List<CellCoverage.Site> all = CellCoverage.sites(cells, OpenCellId.Operator.ALL);
        assertEquals(3, all.size(), "Telenor's three cells together, Telia's apart, the far one apart");
        List<CellCoverage.Site> telenor = CellCoverage.sites(cells, OpenCellId.Operator.TELENOR);
        assertEquals(2, telenor.size());
        CellCoverage.Site mast = telenor.get(0);
        assertEquals(3, mast.cells());
        assertTrue(mast.nr());
        assertEquals(OpenCellId.Operator.TELENOR, mast.operator());
        assertTrue(CellCoverage.sites(cells, OpenCellId.Operator.ICE).isEmpty());
    }

    @Test
    void fartherIsWorseAndARidgeIsWorseThanTheOpen() {
        double lat = 60.0, lon = 10.0;
        double[] near = RadioHorizon.destination(lat, lon, 90, 3000), far = RadioHorizon.destination(lat, lon, 90, 12000);
        double rNear = CellCoverage.rsrpDbm(lat, lon, near[0], near[1], 50, FLAT);
        double rFar = CellCoverage.rsrpDbm(lat, lon, far[0], far[1], 50, FLAT);
        assertTrue(rNear > rFar, rNear + " vs " + rFar);
        assertEquals(CellCoverage.GOOD, CellCoverage.level(rNear), "3 km in the open at 50 m: " + rNear);

        // a 250 m ridge halfway between the mast and a point 8 km east
        double[] to = RadioHorizon.destination(lat, lon, 90, 8000);
        double ridgeLon = (lon + to[1]) / 2;
        RadioHorizon.Terrain ridge = (la, lo) -> Math.abs(lo - ridgeLon) < 0.004 ? 350 : 100;
        double open = CellCoverage.rsrpDbm(lat, lon, to[0], to[1], 50, FLAT);
        double behind = CellCoverage.rsrpDbm(lat, lon, to[0], to[1], 50, ridge);
        assertTrue(behind < open - 15, open + " vs " + behind);
        // higher up the drone looks further over it
        double high = CellCoverage.rsrpDbm(lat, lon, to[0], to[1], 120, ridge);
        assertTrue(high > behind, behind + " vs " + high);
        // on the ground it is worse than in the air, even in the open
        assertTrue(CellCoverage.rsrpDbm(lat, lon, to[0], to[1], 2, FLAT) < open);
        // no terrain known at either end: no answer
        assertTrue(Double.isNaN(CellCoverage.rsrpDbm(lat, lon, to[0], to[1], 50, (la, lo) -> Double.NaN)));
    }

    @Test
    void theModelsPartsBehave() {
        assertEquals(0, CellCoverage.knifeEdgeDb(-1), 1e-9, "clear of the edge");
        assertEquals(6.0, CellCoverage.knifeEdgeDb(0), 0.1, "grazing: about 6 dB");
        assertTrue(CellCoverage.knifeEdgeDb(2.4) > 19 && CellCoverage.knifeEdgeDb(2.4) < 22);
        assertEquals(90.5, CellCoverage.freeSpaceDb(1000), 0.1, "800 MHz over 1 km");
        assertEquals(CellCoverage.CLUTTER_DB, CellCoverage.clutterDb(2), 1e-9);
        assertEquals(0, CellCoverage.clutterDb(50), 1e-9);
        assertEquals(CellCoverage.GOOD, CellCoverage.level(-80));
        assertEquals(CellCoverage.FAIR, CellCoverage.level(-100));
        assertEquals(CellCoverage.WEAK, CellCoverage.level(-110));
        assertEquals(CellCoverage.NONE, CellCoverage.level(-125));
        assertEquals(CellCoverage.UNKNOWN, CellCoverage.level(Double.NaN));
    }

    @Test
    void theGridIsGoodNearTheMastNoneFarAndBlankWhereNoCellsAreKnown() {
        double[] area = {59.90, 60.30, 10.0, 10.8}; // about 45 x 45 km
        List<CellCoverage.Site> sites = List.of(new CellCoverage.Site(59.95, 10.05, OpenCellId.Operator.TELENOR, 3, false));
        CellCoverage.Grid g = CellCoverage.predict(area, 20, 20, sites, 2, OpenCellId.Operator.TELENOR, FLAT,
                (lat, lon) -> lat < 60.25, null);
        assertNotNull(g);
        assertEquals(CellCoverage.GOOD, g.at(1, 17), "next to the mast");
        assertEquals(CellCoverage.NONE, g.at(19, 5), "beyond 20 km: no mast counts");
        assertEquals(CellCoverage.UNKNOWN, g.at(5, 0), "the northern strip has no cell data");
        int[] counts = g.counts();
        assertTrue(counts[CellCoverage.GOOD] > 0 && counts[CellCoverage.NONE] > 0);
        assertTrue(g.x1() > g.x0() && g.y1() > g.y0(), "row 0 is north");
        assertNull(CellCoverage.predict(area, 20, 20, sites, 2, OpenCellId.Operator.TELENOR, FLAT, (a, b) -> true, () -> true), "cancelled");
    }

    @Test
    void growsAnAreaByMetres() {
        double[] a = CellCoverage.grow(new double[]{60, 60.1, 10, 10.2}, 1000);
        assertEquals(1000, Mercator.distanceM(a[0], 10, 60, 10), 5);
        assertEquals(1000, Mercator.distanceM(60.05, a[3], 60.05, 10.2), 10);
    }
}
