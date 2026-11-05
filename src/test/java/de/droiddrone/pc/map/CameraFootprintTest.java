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
}
