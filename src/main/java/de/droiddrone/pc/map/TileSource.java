package de.droiddrone.pc.map;

import java.util.Locale;

/**
 * Where the map tiles come from. Kartverket's open cache (CC BY 4.0, no key, caching allowed) is the best map of
 * Norway there is; outside it OpenStreetMap, within its tile policy: a User-Agent that names the app, tiles cached,
 * nothing fetched the pilot isn't looking at. Satellite pictures from Esri. The pilot picks in SETTINGS or on the map.
 */
public enum TileSource {
    KARTVERKET_TOPO("kartverket", "Kartverket topo",
            "https://cache.kartverket.no/v1/wmts/1.0.0/topo/default/webmercator/{z}/{y}/{x}.png",
            "© Kartverket", 18, true, true),
    KARTVERKET_RASTER("kartverket-raster", "Kartverket paper map",
            "https://cache.kartverket.no/v1/wmts/1.0.0/toporaster/default/webmercator/{z}/{y}/{x}.png",
            "© Kartverket", 18, true, true),
    /** Esri's world imagery, the satellite layer most web maps use; Norway's own aerial photos have no open service. */
    SATELLITE("satellite", "Satellite",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            "© Esri, Maxar, Earthstar Geographics", 19, false, true),
    /** Its tile policy: only what is being looked at, so no prefetching. */
    OSM("osm", "OpenStreetMap",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "© OpenStreetMap contributors", 19, false, false);

    /** The settings value for "pick by where the drone is". */
    public static final String AUTO = "auto";

    public final String id;
    public final String title;
    private final String template;
    public final String attribution;
    public final int maxZoom;
    /** Covers Norway only (blank tiles elsewhere). */
    public final boolean norwayOnly;
    /** The levels under the place looked at may be fetched ahead, for offline use. */
    public final boolean prefetchAllowed;

    TileSource(String id, String title, String template, String attribution, int maxZoom, boolean norwayOnly, boolean prefetchAllowed) {
        this.id = id;
        this.title = title;
        this.template = template;
        this.attribution = attribution;
        this.maxZoom = maxZoom;
        this.norwayOnly = norwayOnly;
        this.prefetchAllowed = prefetchAllowed;
    }

    public String url(int z, int x, int y) {
        return template.replace("{z}", Integer.toString(z)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
    }

    /** The settings value (mapSource); null for auto or anything unknown. */
    public static TileSource parse(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (TileSource s : values()) if (s.id.equals(v)) return s;
        return null;
    }

    /** Auto: Kartverket over Norway (mainland, Svalbard, Jan Mayen), OpenStreetMap anywhere else. */
    public static TileSource forPosition(double latDeg, double lonDeg) {
        return inNorway(latDeg, lonDeg) ? KARTVERKET_TOPO : OSM;
    }

    /** The mainland's outline, roughly (lat, lon), clockwise from the south-west: Sweden and Finland stay outside. */
    private static final double[][] MAINLAND = {
            {58.0, 5.0}, {62.0, 4.5}, {65.0, 10.0}, {68.0, 12.5}, {70.0, 17.0}, {71.3, 25.0}, {70.9, 31.2}, {69.6, 30.6},
            {69.1, 28.9}, {70.1, 27.9}, {69.0, 26.0}, {68.9, 24.9}, {69.0, 22.3}, {68.4, 20.3}, {68.0, 18.1}, {66.0, 15.5},
            {64.0, 14.1}, {62.0, 12.4}, {60.0, 12.6}, {59.0, 11.8}, {58.6, 11.3}, {58.0, 8.0}};

    public static boolean inNorway(double latDeg, double lonDeg) {
        if (latDeg >= 74.0 && latDeg <= 81.0 && lonDeg >= 10.0 && lonDeg <= 35.0) return true;  // Svalbard
        if (latDeg >= 70.5 && latDeg <= 71.3 && lonDeg >= -9.5 && lonDeg <= -7.5) return true;  // Jan Mayen
        boolean inside = false; // the mainland: a ray east from the point crosses the outline an odd number of times
        for (int i = 0, j = MAINLAND.length - 1; i < MAINLAND.length; j = i++) {
            double lat1 = MAINLAND[i][0], lon1 = MAINLAND[i][1], lat2 = MAINLAND[j][0], lon2 = MAINLAND[j][1];
            if ((lat1 > latDeg) != (lat2 > latDeg) && lonDeg < (lon2 - lon1) * (latDeg - lat1) / (lat2 - lat1) + lon1) inside = !inside;
        }
        return inside;
    }
}
