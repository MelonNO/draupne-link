package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Mercator;

import java.awt.geom.Point2D;

/**
 * Where the map looks, worked out once a paint and handed to every layer: the centre and zoom, the tile level drawn
 * (zi: the zoom's whole part, at most the source's deepest level), how much that level is blown up (scale, 1 to 2 below
 * the source's deepest level), the centre in that level's world pixels (wcx, wcy) and the view's size.
 */
record MapProjection(double centerLat, double centerLon, double zoom, int zi, double scale, double wcx, double wcy, int w, int h) {

    static MapProjection of(double centerLat, double centerLon, double zoom, int maxZoom, int w, int h) {
        int zi = (int) Math.max(0, Math.min(maxZoom, Math.floor(zoom)));
        double scale = Math.pow(2, zoom - zi);
        return new MapProjection(centerLat, centerLon, zoom, zi, scale, Mercator.x(centerLon, zi), Mercator.y(centerLat, zi), w, h);
    }

    /** Where a place is drawn. */
    Point2D toScreen(double lat, double lon) {
        return new Point2D.Double((Mercator.x(lon, zi) - wcx) * scale + w / 2.0, (Mercator.y(lat, zi) - wcy) * scale + h / 2.0);
    }

    /** The place under a pixel, {lat, lon}. */
    double[] toLatLon(double x, double y) {
        double lon = Mercator.lon(Mercator.x(centerLon, zoom) + x - w / 2.0, zoom);
        double lat = Mercator.lat(Mercator.y(centerLat, zoom) + y - h / 2.0, zoom);
        return new double[]{lat, lon};
    }

    /** Zoom-0 world pixels to level-zi world pixels (2^zi). */
    double level() {
        return Mercator.worldSize(zi) / Mercator.TILE;
    }

    /** A level-zi world pixel x on the screen. */
    double screenX(double worldX) {
        return (worldX - wcx) * scale + w / 2.0;
    }

    /** A level-zi world pixel y on the screen. */
    double screenY(double worldY) {
        return (worldY - wcy) * scale + h / 2.0;
    }
}
