package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraFootprintTest {
    private static final double LAT = 60, LON = 10;
    private static final RadioHorizon.Terrain FLAT = (lat, lon) -> 0;

    private static double northM(double[] ll) {
        return (ll[0] - LAT) * 111_195;
    }

    private static double eastM(double[] ll) {
        return (ll[1] - LON) * 111_195 * Math.cos(Math.toRadians(LAT));
    }

    @Test
    void aCameraLookingStraightDownSeesASquareUnderTheDrone() {
        CameraFootprint.Pose p = new CameraFootprint.Pose(LAT, LON, 100, 0, 0, 0, -90, 90, 90);
        CameraFootprint.View v = CameraFootprint.compute(p, FLAT, Double.NaN);
        assertEquals(0, northM(v.centre()), 0.5);
        assertEquals(0, eastM(v.centre()), 0.5);
        assertEquals(100, v.slantRangeM(), 0.5);
        // the picture's top is the drone's front (north): the upper left corner 100 m north and 100 m west
        assertEquals(100, northM(v.corners()[0]), 1);
        assertEquals(-100, eastM(v.corners()[0]), 1);
        assertEquals(-100, northM(v.corners()[2]), 1);
        assertEquals(100, eastM(v.corners()[2]), 1);
        assertFalse(v.flatGround());
    }

    /** Q-011: out at sea the tiles give the sea floor, and the view met a ground 305 m under the water. Now the water. */
    @Test
    void overTheSeaTheCameraMeetsTheWaterNotTheSeaFloor() {
        double[] beach = RadioHorizonTest.tileCentre(58.75, 5.5), out = RadioHorizon.destination(beach[0], beach[1], 90, 6000);
        RadioHorizon.Data shore = RadioHorizonTest.shore(beach[0], beach[1], 8000);
        CameraFootprint.View v = CameraFootprint.compute(new CameraFootprint.Pose(out[0], out[1], 100, 0, 0, 0, -90, 90, 90), shore, Double.NaN);
        assertEquals(100, v.slantRangeM(), 0.5, "100 m over the water");
        assertEquals(0, v.centre()[2], 0.01);
        assertFalse(v.flatGround());
    }

    @Test
    void aLevelCameraSeesTheGroundOnlyInTheLowerHalf() {
        CameraFootprint.Pose p = new CameraFootprint.Pose(LAT, LON, 100, 0, 0, 0, 0, 90, 60);
        CameraFootprint.View v = CameraFootprint.compute(p, FLAT, Double.NaN);
        assertNull(v.centre(), "the centre looks at the horizon");
        assertNull(v.corners()[0], "the top corners show sky");
        assertNotNull(v.corners()[3], "the bottom ones the ground");
        double[] bottomMiddle = v.outline().get(2 * CameraFootprint.PER_EDGE + CameraFootprint.PER_EDGE / 2);
        assertEquals(100 / Math.tan(Math.toRadians(30)), northM(bottomMiddle), 1.5);
        double[] topMiddle = v.outline().get(CameraFootprint.PER_EDGE / 2);
        assertEquals(CameraFootprint.MAX_RANGE_M, northM(topMiddle), 6, "sky ends at the range, on its bearing");
    }

    @Test
    void noseDownPitchAndHeadingTurnTheView() {
        // MISB's pitch: nose up positive, so -30 looks 30 degrees down; heading 90 = east
        CameraFootprint.Pose p = new CameraFootprint.Pose(LAT, LON, 100, 90, -30, 0, 0, 70, 40);
        CameraFootprint.View v = CameraFootprint.compute(p, FLAT, Double.NaN);
        assertEquals(100 / Math.tan(Math.toRadians(30)), eastM(v.centre()), 1.5);
        assertEquals(0, northM(v.centre()), 1);
        assertEquals(200, v.slantRangeM(), 1);
    }

    @Test
    void theCameraTiltAddsToThePitch() {
        // an FPV camera tilted up 20 degrees on a frame pitched 50 degrees nose down looks 30 degrees down
        CameraFootprint.Pose p = new CameraFootprint.Pose(LAT, LON, 100, 0, -50, 0, 20, 70, 40);
        CameraFootprint.View v = CameraFootprint.compute(p, FLAT, Double.NaN);
        assertEquals(100 / Math.tan(Math.toRadians(30)), northM(v.centre()), 1.5);
    }

    @Test
    void rollingRightTipsThePicturesRightSideDown() {
        // right wing down: the picture turns round its centre (the roll axis), its right side sees nearer ground
        CameraFootprint.Pose level = new CameraFootprint.Pose(LAT, LON, 100, 0, -45, 0, 0, 60, 40);
        CameraFootprint.Pose rolled = new CameraFootprint.Pose(LAT, LON, 100, 0, -45, 30, 0, 60, 40);
        CameraFootprint.View a = CameraFootprint.compute(level, FLAT, Double.NaN), b = CameraFootprint.compute(rolled, FLAT, Double.NaN);
        assertEquals(northM(a.centre()), northM(b.centre()), 0.5, "the centre stays: it is on the roll axis");
        assertEquals(northM(a.corners()[2]), northM(a.corners()[3]), 0.5, "level: both lower corners as near");
        assertTrue(northM(b.corners()[2]) < northM(b.corners()[3]) - 10, "rolled right: the lower right corner nearer");
    }

    @Test
    void aHillStopsTheRayEarly() {
        RadioHorizon.Terrain hill = (lat, lon) -> (lat - LAT) * 111_195 > 200 ? 80 : 0;
        CameraFootprint.Pose p = new CameraFootprint.Pose(LAT, LON, 100, 0, -10, 0, 0, 70, 40);
        CameraFootprint.View flat = CameraFootprint.compute(p, FLAT, Double.NaN);
        CameraFootprint.View v = CameraFootprint.compute(p, hill, Double.NaN);
        assertEquals(100 / Math.tan(Math.toRadians(10)), northM(flat.centre()), 3);
        assertEquals(200, northM(v.centre()), 3, "the ray meets the hill's face");
        assertEquals(80, v.centre()[2], 0.01);
    }

    @Test
    void unknownTerrainFallsBackToTheGivenGround() {
        CameraFootprint.Pose p = new CameraFootprint.Pose(LAT, LON, 150, 0, -30, 0, 0, 70, 40);
        CameraFootprint.View v = CameraFootprint.compute(p, (lat, lon) -> Double.NaN, 50);
        assertTrue(v.flatGround());
        assertEquals(100 / Math.tan(Math.toRadians(30)), northM(v.centre()), 1.5);
    }

    /**
     * OVERWATCH (1.0.50, the pilot: "way too big" and gone after a few seconds): a drone sitting on a hill at 300 m whose
     * own height is 0 (barometric, over launch) still sees the ground round it, and nothing past the watch range.
     */
    @Test
    void aSittingDroneSeesTheGroundRoundItOutToTheWatchRange() {
        CameraFootprint.Pose p = new CameraFootprint.Pose(61.0, 10.0, 0, 90, 0, 0, 0, 90, 50);
        for (RadioHorizon.Terrain t : new RadioHorizon.Terrain[]{(lat, lon) -> 300.0, null}) {
            CameraFootprint.View v = CameraFootprint.sitting(p, t);
            double far = 0;
            for (double[] ll : v.outline()) {
                double north = (ll[0] - 61.0) * 111_320, east = (ll[1] - 10.0) * 111_320 * Math.cos(Math.toRadians(61.0));
                far = Math.max(far, Math.hypot(north, east));
            }
            assertTrue(far > 100, "it sees out a fair way, not a point under the hill: " + far);
            assertTrue(far <= CameraFootprint.WATCH_RANGE_M + 5, "and no further than the watch range: " + far);
        }
    }

    // ------------------------------------------------------------------ 1.0.70: a ground point in the picture

    /** The pins on the video: the point a ray through (u, v) meets is drawn back at (u, v), however the drone turns. */
    @Test
    void aGroundPointShowsWhereItsRayLeftThePicture() {
        CameraFootprint.Pose[] poses = {
                new CameraFootprint.Pose(LAT, LON, 100, 0, 0, 0, -45, 90, 90),
                new CameraFootprint.Pose(LAT, LON, 120, 237, -12, 18, 20, 70, 40),
                new CameraFootprint.Pose(LAT, LON, 60, 355, 8, -25, -30, 100, 62),
        };
        double[][] points = {{0, 0.5}, {-0.8, 0.9}, {0.6, 0.7}, {0.95, 0.99}};
        for (CameraFootprint.Pose p : poses) {
            for (double[] uv : points) {
                CameraFootprint.Ray ray = CameraFootprint.ray(p, uv[0], uv[1], FLAT, Double.NaN);
                if (ray.ground() == null) continue; // sky there in this pose
                double[] back = CameraFootprint.toPicture(p, ray.ground()[0], ray.ground()[1], ray.ground()[2]);
                String at = p + " at " + uv[0] + ", " + uv[1];
                assertEquals(uv[0], back[0], 2e-3, at);
                assertEquals(uv[1], back[1], 2e-3, at);
                assertEquals(ray.slantRangeM(), Math.sqrt(back[2] * back[2] * (1 + Math.pow(back[0] * Math.tan(Math.toRadians(p.hfovDeg() / 2)), 2)
                        + Math.pow(back[1] * Math.tan(Math.toRadians(p.vfovDeg() / 2)), 2))), 0.5, "ahead is the way along the look: " + at);
            }
        }
    }

    @Test
    void aPointBehindTheCameraSaysWhichSideItIsOn() {
        CameraFootprint.Pose north = new CameraFootprint.Pose(LAT, LON, 100, 0, 0, 0, 0, 70, 40);
        double[] southEast = CameraFootprint.toPicture(north, LAT - 0.005, LON + 0.004, 0);
        assertTrue(southEast[2] <= 0, "behind");
        assertTrue(southEast[0] > 0, "on the right");
        double[] southWest = CameraFootprint.toPicture(north, LAT - 0.005, LON - 0.004, 0);
        assertTrue(southWest[2] <= 0 && southWest[0] < 0, "behind, on the left");
        double[] ahead = CameraFootprint.toPicture(north, LAT + 0.02, LON, 100); // 2.2 km ahead at the camera's height
        assertEquals(0, ahead[0], 1e-6);
        assertTrue(ahead[1] > 0 && ahead[1] < 0.01, "a little under the middle: the earth curves away - " + ahead[1]);
        assertEquals(2226, ahead[2], 2);
    }

    /** A ridge 100 m high from 400 to 500 m north of the drone, 400 m long across its way north. */
    private static final RadioHorizon.Terrain RIDGE = (lat, lon) -> {
        double[] ll = {lat, lon};
        double n = northM(ll);
        return n >= 400 && n <= 500 && Math.abs(eastM(ll)) <= 200 ? 100 : 0;
    };

    @Test
    void aRidgeHidesWhatIsBehindItFromALowDroneOnly() {
        CameraFootprint.Pose low = new CameraFootprint.Pose(LAT, LON, 50, 0, 0, 0, 0, 70, 40);
        double behind = LAT + 1000 / 111_320.0, before = LAT + 300 / 111_320.0;
        assertTrue(CameraFootprint.hidden(low, behind, LON, 0, RIDGE), "1 km out, behind the ridge");
        assertFalse(CameraFootprint.hidden(low, before, LON, 0, RIDGE), "300 m out, before it");
        assertFalse(CameraFootprint.hidden(low, behind, LON + 0.02, 0, RIDGE), "1.1 km out to the east: the ridge isn't there");
        CameraFootprint.Pose high = new CameraFootprint.Pose(LAT, LON, 600, 0, 0, 0, 0, 70, 40);
        assertFalse(CameraFootprint.hidden(high, behind, LON, 0, RIDGE), "from 600 m it sees over it");
        assertFalse(CameraFootprint.hidden(low, behind, LON, 0, (lat, lon) -> Double.NaN), "no terrain known: nothing hidden");
        assertFalse(CameraFootprint.hidden(low, behind, LON, 0, null));
    }

    @Test
    void aDroneSittingInTheGrassIsNotHiddenByItsOwnGround() {
        // the launch ground plus the barometer: a metre under the terrain's height there - on flat ground all the same
        CameraFootprint.Pose sitting = new CameraFootprint.Pose(LAT, LON, -1, 0, 0, 0, 0, 70, 40);
        assertFalse(CameraFootprint.hidden(sitting, LAT + 800 / 111_320.0, LON, 0, FLAT));
        // a pin on a crest: the terrain's cells round it are not in the way
        RadioHorizon.Terrain hill = (lat, lon) -> northM(new double[]{lat, lon}) > 900 ? 80 : 0;
        assertFalse(CameraFootprint.hidden(sitting, LAT + 920 / 111_320.0, LON, 80, hill));
    }
}
