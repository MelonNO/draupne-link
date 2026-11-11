package de.droiddrone.pc.gui;

import de.droiddrone.pc.Names;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.FlightTrack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The map's arrow and FLEET's overwatch view (1.0.62, the pilot: "the orientation in map does not match the aircraft
 * orientation, both the icon and sector, when in Overwatch").
 */
class HeadingOnMapTest {
    private static FlightTrack.Point at(double kmh, int course) {
        return new FlightTrack.Point(System.currentTimeMillis(), 61.149, 11.394, 200, kmh, course, 0, false, true, 5);
    }

    @Test
    void theArrowPointsTheKnownWayEvenOnThePhonesGps() {
        assertEquals(79, MapView.arrowHeading(79, false, at(0, 0)), "connected, standing, phone GPS: the heading");
        assertEquals(79, MapView.arrowHeading(79, true, at(0.5, 300)), "asleep since, standing still: where it last looked");
    }

    @Test
    void standingStillWithNoHeadingIsARoundMarkNotTheGpsWander() {
        assertEquals(-1, MapView.arrowHeading(-1, false, at(0.8, 213)));
        assertEquals(-1, MapView.arrowHeading(-1, true, at(0, 0)));
    }

    @Test
    void movingOnAStaleLinkTheCourseSaysWhereItGoes() {
        assertEquals(140, MapView.arrowHeading(79, true, at(30, 140)));
        assertEquals(140, MapView.arrowHeading(-1, false, at(30, 140)));
        assertEquals(79, MapView.arrowHeading(79, false, at(30, 140)), "fresh: the heading");
    }

    private static CameraFootprint.Pose pose(double lat, double lon, double heading) {
        return new CameraFootprint.Pose(lat, lon, 200, heading, 0, 0, 0, 70, 43);
    }

    private static Names.Position standing(double lat, double lon) {
        return new Names.Position(false, lat, lon, 0, false, false, null, 0);
    }

    @Test
    void fleetsOverwatchTakesTheLastConnectedViewWhereTheDroneStillStands() {
        CameraFootprint.Pose connected = pose(61.1490, 11.3937, 79), old = pose(61.1490, 11.3937, 234);
        assertSame(connected, FleetController.overwatchPose(connected, old, standing(61.14901, 11.39371)));
        assertSame(old, FleetController.overwatchPose(null, old, standing(61.14901, 11.39371)));
        assertSame(old, FleetController.overwatchPose(null, old, null), "where it stands not known: the old view");
    }

    @Test
    void aDroneThatStandsElsewhereNowHasNoView() {
        CameraFootprint.Pose old = pose(61.1490, 11.3937, 234);
        assertNull(FleetController.overwatchPose(null, old, standing(61.1500, 11.3937)), "111 m away: none, not a wrong one");
        assertNull(FleetController.overwatchPose(old, null, standing(61.1490, 11.3990)));
    }
}
