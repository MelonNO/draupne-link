package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RADIO HORIZON (1.0.39): the line of sight over a 4/3 earth, the terrain and the forest read off the map's colours. */
class RadioHorizonTest {
    private static final double LAT = 60.0, LON = 10.7;
    private static final RadioHorizon.Cover OPEN = (lat, lon) -> RadioHorizon.OPEN;

    private static RadioHorizon.Terrain flat(double h) {
        return (lat, lon) -> h;
    }

    @Test
    void overFlatGroundTheReachIsTheSmoothEarthHorizonAndNeverBeyond30Km() {
        RadioHorizon.Result low = RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(2, 10, 15, 2400), flat(100), OPEN);
        double expected = RadioHorizon.smoothEarthM(2, 10); // 5.8 km + 13.0 km
        assertEquals(18_870, expected, 100);
        for (int b = 0; b < RadioHorizon.BEARINGS; b += 45) {
            assertEquals(expected, low.reachM(b), 2 * RadioHorizon.STEP_M, "bearing " + b);
            assertEquals(2, low.spans()[b].length, "one stretch from the mast out");
        }
        RadioHorizon.Result high = RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 120, 15, 2400), flat(100), OPEN);
        assertTrue(high.smoothEarthM() > 50_000);
        for (int b = 0; b < RadioHorizon.BEARINGS; b++) assertEquals(RadioHorizon.MAX_RANGE_M, high.reachM(b), 0.1, "cut at 30 km");
        assertFalse(high.terrainMissing());
        assertEquals(0, high.forestUnknownPct());
    }

    @Test
    void aRidgeShadowsTheGroundBehindItButNotTheOtherWay() {
        // a 300 m ridge running north-south 5 km east of the mast
        RadioHorizon.Terrain ridge = (lat, lon) -> {
            double eastM = (lon - LON) * 111_320 * Math.cos(Math.toRadians(LAT));
            return eastM > 4_800 && eastM < 5_200 ? 400 : 100;
        };
        RadioHorizon.Result r = RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 120, 15, 2400), ridge, OPEN);
        assertTrue(r.reachM(90) < 5_300, "east: nothing past the ridge at 120 m (" + r.reachM(90) + ")");
        assertEquals(RadioHorizon.MAX_RANGE_M, r.reachM(270), 0.1, "west: open to 30 km");
        // high enough over the ridge, the aircraft is seen far behind it
        RadioHorizon.Result over = RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 2_000, 15, 2400), ridge, OPEN);
        assertEquals(RadioHorizon.MAX_RANGE_M, over.reachM(90), 0.1);
        // just over the ridge's top: the shadow behind it starts where the line over the top meets the aircraft's altitude
        RadioHorizon.Result just = RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 320, 15, 2400), ridge, OPEN);
        double topSlope = (400 - 110.0) / 4_800, expected = (420 - 110.0) / topSlope; // ~5.1 km, a little less for the bulge
        assertEquals(expected, just.reachM(90), 120, "shadowed from about " + (int) expected + " m");
        assertEquals(2, just.spans()[90].length, "one stretch from the mast out: behind the ridge the line never clears it at 320 m above the ground");
    }

    @Test
    void theForestCostsSignalByItsDepthAndTheFrequency() {
        // FITU-R in leaf: 0.39 f^0.39 d^0.25 (f in MHz)
        assertEquals(0.39 * Math.pow(1000, 0.39) * 2, RadioHorizon.foliageLossDb(1000, 16), 1e-9);
        assertEquals(0, RadioHorizon.foliageLossDb(2400, 0), 1e-9);
        double at800 = RadioHorizon.forestDepthM(800), at2400 = RadioHorizon.forestDepthM(2400), at5800 = RadioHorizon.forestDepthM(5800);
        assertEquals(204, at800, 3);
        assertEquals(37, at2400, 1.5);
        assertEquals(9.1, at5800, 0.5, "5.8 GHz: a few trees are enough (Weissberger let it through 45 m)");
        assertTrue(at5800 < at2400 && at2400 < at800, "the higher, the less forest it gets through");
        assertEquals(RadioHorizon.MAX_FOLIAGE_LOSS_DB, RadioHorizon.foliageLossDb(2400, at2400), 1e-6);
    }

    @Test
    void aMastInTheWoodReachesOverAThinBeltButNotThroughALongOne() {
        RadioHorizon.Settings s = new RadioHorizon.Settings(10, 120, 15, 2400);
        double open = RadioHorizon.compute(LAT, LON, s, flat(0), OPEN).reachM(0);
        assertEquals(RadioHorizon.MAX_RANGE_M, open, 0.1);
        // the mast in a belt of trees taller than it, 35 m deep: 30 m of canopy in the line (looked at every 10 m), under 37 m at 2.4 GHz
        RadioHorizon.Cover belt = (lat, lon) -> Mercator.distanceM(LAT, LON, lat, lon) < 35 ? RadioHorizon.FOREST : RadioHorizon.OPEN;
        assertEquals(open, RadioHorizon.compute(LAT, LON, s, flat(0), belt).reachM(0), 0.1, "a thin belt: seen all the way");
        assertTrue(RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 120, 15, 5800), flat(0), belt).reachM(0) < 2_000,
                "the same belt stops 5.8 GHz (9 m) close in");
        // in a wood 300 m deep the line from a 10 m mast runs under the 15 m treetops for x < 5 d / 110 m
        RadioHorizon.Cover wood = (lat, lon) -> Mercator.distanceM(LAT, LON, lat, lon) < 300 ? RadioHorizon.FOREST : RadioHorizon.OPEN;
        double high = RadioHorizon.compute(LAT, LON, s, flat(0), wood).reachM(0);
        double low = RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 120, 15, 800), flat(0), wood).reachM(0);
        // 2.4 GHz: the second 20 m of trees (40 m > 37 m) joins once x = 40 m is under the treetops: d = 40 * 110 / 5
        assertEquals(40 * 110 / 5.0, high, 2 * 20);
        // 800 MHz: 204 m, the eleventh 20 m (220 m) is too much: d = 220 * 110 / 5
        assertEquals(220 * 110 / 5.0, low, 4 * 20, "a little less for the earth's bulge");
        assertEquals(open, RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 120, 0, 2400), flat(0), wood).reachM(0), 0.1,
                "trees of 0 m: the forest is ignored");
    }

    @Test
    void theQuickForestCountIsTheSameAsCountingEveryTree() {
        java.util.Random random = new java.util.Random(7);
        RadioHorizon.Sweep sweep = new RadioHorizon.Sweep();
        RadioHorizon.Settings s = new RadioHorizon.Settings(10, 60, 18, 900);
        for (int round = 0; round < 20; round++) {
            long seed = random.nextLong();
            RadioHorizon.Terrain hills = (lat, lon) -> 100 + 40 * Math.sin(lat * 3_000 + seed % 7) * Math.cos(lon * 2_000);
            RadioHorizon.Cover patches = (lat, lon) -> (Math.floorMod((long) (lat * 20_000) * 31 + (long) (lon * 10_000) + seed, 7) < 3)
                    ? RadioHorizon.FOREST : RadioHorizon.OPEN;
            sweep.fill(LAT, LON, random.nextInt(360), 100, s, hills, patches);
            double source = 100 + 10, maxWood = RadioHorizon.forestDepthM(900);
            double[] target = new double[sweep.n + 1];
            for (int i = 1; i <= sweep.n; i++) target[i] = 100 + 60 + random.nextInt(80);
            sweep.run(source, target, maxWood);
            for (int i = 1; i <= sweep.n; i++) {
                double d = i * RadioHorizon.STEP_M, slope = (target[i] - sweep.drop[i] - source) / d;
                boolean ok = target[i] >= sweep.ground[i] + sweep.drop[i];
                double inTrees = 0;
                for (int j = 1; j <= i && ok; j++) {
                    if (j < i && (sweep.ground[j] - source) / (j * RadioHorizon.STEP_M) > slope) ok = false; // a hill
                    if (sweep.top[j] != Double.NEGATIVE_INFINITY && (sweep.top[j] - source) / (j * RadioHorizon.STEP_M) > slope) inTrees += sweep.wood[j];
                }
                ok &= inTrees <= maxWood + 1e-9;
                assertEquals(ok, sweep.seen[i], "round " + round + ", sample " + i);
            }
        }
    }

    @Test
    void theAircraftsAltitudeIsAboveTheGroundUnderIt() {
        // the ground rises to 200 m above the mast's past 2 km; the aircraft keeps 120 m above it (the pilot: "above
        // ground from the point of the aircraft"), so it is seen up on the plateau until the plateau's edge hides it:
        // the line from the mast (110 m) over the edge (300 m at the first sample past 2 km) meets 420 m at ~3.3 km
        RadioHorizon.Terrain hill = (lat, lon) -> Mercator.distanceM(LAT, LON, lat, lon) > 2_000 ? 300 : 100;
        RadioHorizon.Result r = RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 120, 15, 2400), hill, OPEN);
        double edge = 2_000 + RadioHorizon.STEP_M, expected = (420 - 110.0) / ((300 - 110.0) / edge);
        assertEquals(expected, r.reachM(45), 3 * RadioHorizon.STEP_M);
        assertEquals(2, r.spans()[45].length, "seen from the mast out, over the edge, then hidden");
    }

    @Test
    void unknownTerrainAndForestAreSaid() {
        RadioHorizon.Result r = RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 120, 15, 2400), (lat, lon) -> Double.NaN,
                (lat, lon) -> RadioHorizon.UNKNOWN);
        assertTrue(r.terrainMissing());
        assertEquals(100, r.forestUnknownPct());
    }

    @Test
    void theMapsColoursReadAsForestOrOpen() {
        // Kartverket topo, measured at zoom 12-15: forest in the light and in the hill shade; fields, bare mountain, water
        for (int c : new int[]{0xd2e096, 0xdfeda3, 0xcedc92, 0xc4d288}) assertTrue(RadioHorizon.isForest(c, false), Integer.toHexString(c));
        for (int c : new int[]{0xf1ecb1, 0xe8d7aa, 0xe0fefe, 0xffffff, 0x000000, 0x9e9e9e}) assertFalse(RadioHorizon.isForest(c, false), Integer.toHexString(c));
        // OpenStreetMap: forest and its tree marks; not meadow, park, scrub, farmland or water
        for (int c : new int[]{0xadd19e, 0xaed2a1, 0x93c586}) assertTrue(RadioHorizon.isForest(c, true), Integer.toHexString(c));
        for (int c : new int[]{0xcdebb0, 0xc8facc, 0xc8d7ab, 0xeef0d6, 0xaad3df, 0xf2efe9}) assertFalse(RadioHorizon.isForest(c, true), Integer.toHexString(c));
    }

    @Test
    void terrainTilesDecodeAndInterpolate() {
        assertEquals(0, RadioHorizon.terrariumHeight(0x800000), 1e-9);
        assertEquals(1234.5, RadioHorizon.terrariumHeight((128 + 4) << 16 | 210 << 8 | 128), 1e-9);
        RadioHorizon.Data data = new RadioHorizon.Data(LAT, LON, false);
        int z = RadioHorizon.TERRAIN_ZOOM;
        int tx = (int) Math.floor(Mercator.x(LON, z) / Mercator.TILE), ty = (int) Math.floor(Mercator.y(LAT, z) / Mercator.TILE);
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) img.setRGB(x, y, (128 << 16) | (x << 8)); // height = x metres
        data.putTerrain(tx, ty, img);
        double px = (tx * 256 + 100.5), py = ty * 256 + 50.5; // a pixel's centre: exactly its height
        assertEquals(100, data.heightM(Mercator.lat(py, z), Mercator.lon(px, z)), 0.05);
        assertEquals(100.5, data.heightM(Mercator.lat(py, z), Mercator.lon(px + 0.5, z)), 0.05, "between two pixels: between their heights");
        assertTrue(Double.isNaN(data.heightM(Mercator.lat(py, z), Mercator.lon(px + 400, z))), "no tile there: unknown");
    }

    @Test
    void aForestTileKeepsItsWoodUnderTheContourLinesAndNames() {
        RadioHorizon.Data data = new RadioHorizon.Data(LAT, LON, false);
        int z = RadioHorizon.FOREST_ZOOM_NORWAY;
        int tx = (int) Math.floor(Mercator.x(LON, z) / Mercator.TILE), ty = (int) Math.floor(Mercator.y(LAT, z) / Mercator.TILE);
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int c = x < 128 ? 0xd2e096 : 0xf1ecb1; // forest west, field east
                if (y == 64 || x == 40) c = 0xb4783c; // a contour line through both
                img.setRGB(x, y, c);
            }
        }
        data.putForest(tx, ty, img);
        double wy = ty * 256 + 64.5;
        assertEquals(RadioHorizon.FOREST, data.at(Mercator.lat(wy, z), Mercator.lon(tx * 256 + 20.5, z)), "on the line through the wood: wood");
        assertEquals(RadioHorizon.FOREST, data.at(Mercator.lat(ty * 256 + 10.5, z), Mercator.lon(tx * 256 + 40.5, z)));
        assertEquals(RadioHorizon.OPEN, data.at(Mercator.lat(wy, z), Mercator.lon(tx * 256 + 200.5, z)), "the field stays open");
        assertEquals(RadioHorizon.UNKNOWN, data.at(Mercator.lat(wy, z), Mercator.lon(tx * 256 + 300.5, z)), "no tile yet");
    }

    @Test
    void theTilesAroundAMastAreTheCircleNearestFirst() {
        List<int[]> tiles = RadioHorizon.tilesAround(LAT, LON, RadioHorizon.FOREST_ZOOM_NORWAY);
        int z = RadioHorizon.FOREST_ZOOM_NORWAY;
        int[] first = tiles.get(0);
        assertEquals((int) Math.floor(Mercator.x(LON, z) / Mercator.TILE), first[0], "the mast's own tile first");
        assertEquals((int) Math.floor(Mercator.y(LAT, z) / Mercator.TILE), first[1]);
        double tileM = Mercator.metersPerPixel(LAT, z) * Mercator.TILE;
        double square = Math.pow(2 * (RadioHorizon.MAX_RANGE_M + 500) / tileM, 2);
        assertTrue(tiles.size() < square * 0.85, "the corners of the square are left out: " + tiles.size() + " of " + (int) square);
        assertTrue(tiles.size() > square * 0.7);
        for (int i = 1; i < tiles.size(); i++) assertTrue(tiles.get(i)[2] >= tiles.get(i - 1)[2], "nearest first");
    }

    @Test
    void overFlatOpenGroundTheWholeAreaSeesTheMiddleAndLessTowardsTheEdge() {
        double[] box = {LAT - 0.005, LAT + 0.005, LON - 0.01, LON + 0.01}; // about 1.1 km square
        RadioHorizon.Search r = RadioHorizon.search(box[0], box[1], box[2], box[3], new RadioHorizon.Settings(10, 120, 15, 2400),
                flat(50), OPEN, null, null);
        int mid = r.size() / 2;
        assertEquals(1f, r.at(mid, mid), "the middle: every point of the area sees it");
        assertEquals(1f, r.bestScore());
        assertTrue(Mercator.distanceM(r.lat(), r.lon(), r.bestLat(), r.bestLon()) < RadioHorizon.SEARCH_CELL_M, "the best is the one nearest the centre");
        assertTrue(Float.isNaN(r.at(0, 0)), "outside the 30 km circle: nothing");
        // at the circle's edge the area's far side is more than 30 km away
        int edge = 0;
        while (Float.isNaN(r.at(edge, mid))) edge++;
        assertTrue(r.at(edge, mid) < 1f, "the circle's west edge: " + r.at(edge, mid));
        assertFalse(r.terrainMissing());
    }

    @Test
    void aWallHidesTheAreaFromEverythingBehindItButItsTopSeesAll() {
        // a 1 km high wall running north-south 5 km west of the area, 180 m thick: a mast near its near edge sees over it
        // (one in its middle would not: the near edge is higher than the line from a low aircraft)
        double cosLat = Math.cos(Math.toRadians(LAT));
        RadioHorizon.Terrain wall = (lat, lon) -> {
            double eastM = (lon - LON) * 111_320 * cosLat;
            return eastM > -5_100 && eastM < -4_920 ? 1_100 : 100;
        };
        double[] box = {LAT - 0.004, LAT + 0.004, LON - 0.008, LON + 0.008};
        int[] progress = {0};
        RadioHorizon.Search r = RadioHorizon.search(box[0], box[1], box[2], box[3], new RadioHorizon.Settings(10, 120, 0, 2400),
                wall, OPEN, done -> progress[0] = Math.max(progress[0], done), null);
        assertEquals(RadioHorizon.SEARCH_POINTS * RadioHorizon.SEARCH_POINTS, progress[0], "told of every point");
        int mid = r.size() / 2, cellsPerKm = (int) Math.round(1000 / RadioHorizon.SEARCH_CELL_M);
        assertEquals(1f, r.at(mid, mid));
        assertEquals(0f, r.at(mid - 10 * cellsPerKm, mid), "10 km west, behind the wall: nothing of the area");
        assertEquals(1f, r.at(mid - 5 * cellsPerKm, mid), "30 m in from the wall's near edge (the cell at 4 950 m west): all of it");
        assertEquals(1f, r.at(mid + 10 * cellsPerKm, mid), "10 km east, open: all of it");
    }

    @Test
    void offlineNothingLoadsAndTheResultSaysSo(@TempDir Path dir) {
        TileCache cache = new TileCache(dir, "test", false);
        int[] calls = {0};
        RadioHorizon.Data data = RadioHorizon.load(cache, LAT, LON, (d, td, tt, fd, ft) -> calls[0]++, () -> false);
        assertTrue(calls[0] >= 2, "told after the terrain and at the end of the forest");
        RadioHorizon.Result r = RadioHorizon.compute(LAT, LON, new RadioHorizon.Settings(10, 120, 15, 2400), data, data);
        assertTrue(r.terrainMissing());
        assertEquals(100, r.forestUnknownPct());
        assertEquals(RadioHorizon.MAX_RANGE_M, r.reachM(0), 0.1, "taken as flat and open");
    }
}
