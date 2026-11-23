package de.droiddrone.pc.gui;

import de.droiddrone.pc.route.Route;
import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet RT-1: a route over the antimeridian is drawn, hit and saved the short way - not across the whole world. */
class MapRouteLayerAntimeridianTest {
    @Test
    void theShortWayOnTheMapAndInTheFile() {
        MapProjection proj = MapProjection.of(-16.75, 179.9999, 17, 19, 800, 600);
        Route r = new Route("Dateline");
        r.add(-16.75, 179.9995);
        r.add(-16.75, 180.0005); // a click right of the line, as the map gives it
        assertEquals(-179.9995, r.point(1).lon(), 1e-9, "kept in -180..180");
        Point2D a = MapRouteLayer.at(proj, r.point(0).lat(), r.point(0).lon()), b = MapRouteLayer.at(proj, r.point(1).lat(), r.point(1).lon());
        assertTrue(a.getX() > 0 && a.getX() < 800 && b.getX() > 0 && b.getX() < 800, "both on the screen: " + a + " " + b);
        assertTrue(b.getX() > a.getX(), "east of the first, the short way");
        int midX = (int) ((a.getX() + b.getX()) / 2), midY = (int) ((a.getY() + b.getY()) / 2);
        assertEquals(0, MapRouteLayer.legAt(r, proj, midX, midY), "the leg is hit where it is drawn");
        assertEquals(1, MapRouteLayer.pointAt(r, proj, (int) b.getX(), (int) b.getY()));
        Route back = Route.fromText(r.toText());
        assertEquals(r.point(1).lon(), back.point(1).lon(), 1e-6, "saved and loaded again");
    }
}
