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

    static boolean inNorway(double latDeg, double lonDeg) {
        if (latDeg >= 57.5 && latDeg <= 71.5 && lonDeg >= 4.0 && lonDeg <= 31.5) return true;   // mainland
        if (latDeg >= 74.0 && latDeg <= 81.0 && lonDeg >= 10.0 && lonDeg <= 35.0) return true;  // Svalbard
        return latDeg >= 70.5 && latDeg <= 71.3 && lonDeg >= -9.5 && lonDeg <= -7.5;             // Jan Mayen
    }
}
