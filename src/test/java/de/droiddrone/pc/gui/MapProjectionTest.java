package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Mercator;
import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map's projection: places to pixels and back, the tile level and its scale, the edges of the Mercator world. */
class MapProjectionTest {

    private static void roundTrip(MapProjection p, double lat, double lon) {
        Point2D s = p.toScreen(lat, lon);
        double[] back = p.toLatLon(s.getX(), s.getY());
        assertEquals(lat, back[0], 1e-9, "lat at zoom " + p.zoom());
        assertEquals(lon, back[1], 1e-9, "lon at zoom " + p.zoom());
    }

    @Test
    void theCentreIsTheMiddleOfTheView() {
        MapProjection p = MapProjection.of(59.91, 10.75, 14.6, 18, 800, 600);
        Point2D c = p.toScreen(59.91, 10.75);
        assertEquals(400, c.getX(), 1e-9);
        assertEquals(300, c.getY(), 1e-9);
        double[] ll = p.toLatLon(400, 300);
        assertEquals(59.91, ll[0], 1e-9);
        assertEquals(10.75, ll[1], 1e-9);
    }

    @Test
    void placesComeBackFromTheirPixels() {
        for (double zoom : new double[]{2, 4.3, 11.3, 14.6, 16, 17.95}) {
            MapProjection p = MapProjection.of(59.91, 10.75, zoom, 18, 1200, 800);
            for (double[] ll : new double[][]{{59.91, 10.75}, {59.95, 10.70}, {59.87, 10.81}, {60.5, 9.0}}) roundTrip(p, ll[0], ll[1]);
        }
        roundTrip(MapProjection.of(-33.86, 151.21, 15.2, 19, 333, 217), -33.87, 151.2);
        roundTrip(MapProjection.of(78.22, 15.65, 13, 18, 600, 400), 78.25, 15.6); // Svalbard
    }

    @Test
    void theLevelIsTheZoomsWholePartUpToTheSourcesDeepest() {
        MapProjection p = MapProjection.of(59.91, 10.75, 14.6, 18, 800, 600);
        assertEquals(14, p.zi());
        assertEquals(Math.pow(2, 0.6), p.scale(), 1e-12);
        assertEquals(Math.pow(2, 14), p.level());
        MapProjection deep = MapProjection.of(59.91, 10.75, 20.5, 18, 800, 600);
        assertEquals(18, deep.zi(), "past the source's deepest level its tiles are blown up");
        assertEquals(Math.pow(2, 2.5), deep.scale(), 1e-12);
        assertEquals(0, MapProjection.of(0, 0, -1, 18, 800, 600).zi());
    }

    @Test
    void worldPixelsAndPlacesLandOnTheSamePixel() {
        MapProjection p = MapProjection.of(59.91, 10.75, 15.4, 18, 800, 600);
        Point2D s = p.toScreen(59.92, 10.77);
        assertEquals(s.getX(), p.screenX(Mercator.x(10.77, p.zi())), 1e-9);
        assertEquals(s.getY(), p.screenY(Mercator.y(59.92, p.zi())), 1e-9);
        // a zoom-0 world pixel (TrackPath, the coverage grid) times level()
        assertEquals(s.getX(), p.screenX(Mercator.x(10.77, 0) * p.level()), 1e-6);
        assertEquals(s.getY(), p.screenY(Mercator.y(59.92, 0) * p.level()), 1e-6);
    }

    @Test
    void theMapDoesNotWrapRoundTheAntimeridian() {
        MapProjection p = MapProjection.of(-17.0, 179.998, 15, 18, 600, 400);
        Point2D east = p.toScreen(-17.0, 179.9995);
        assertTrue(east.getX() > 300 && east.getX() < 600, "just east, still this side: " + east.getX());
        roundTrip(p, -17.0, 179.9995);
        // over the line: the place is drawn a whole world to the left (Web Mercator here has one copy of the world)
        Point2D over = p.toScreen(-17.0, -179.9995);
        assertEquals(-Mercator.worldSize(15) + 2 * 0.0005 / 360 * Mercator.worldSize(15), over.getX() - east.getX(), 1e-3);
        assertTrue(over.getX() < -1e6, "off the screen, not beside the drone: " + over.getX());
    }

    @Test
    void thePolesAreCutWhereWebMercatorEnds() {
        MapProjection p = MapProjection.of(84.9, 0, 6, 18, 800, 600);
        Point2D cut = p.toScreen(85.05112878, 0);
        assertEquals(cut.getY(), p.toScreen(89.9, 0).getY(), 1e-9, "past 85.05 degrees: on the world's top edge");
        assertEquals(p.screenY(0), cut.getY(), 1e-6, "the top edge is world pixel 0");
        roundTrip(p, 85.0, 20);
        roundTrip(MapProjection.of(-84.9, 0, 6, 18, 800, 600), -85.0, -20);
    }
}
