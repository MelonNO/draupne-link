package de.droiddrone.pc.route;

import de.droiddrone.common.MissionItem;
import de.droiddrone.pc.map.Airspace;
import de.droiddrone.pc.map.MapGraphics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet AP-9: airspace areas and FENCE rectangles as ArduPilot's fence items. Places made up. */
class FenceBuilderTest {
    static Airspace area(String name, double lat, double lon, boolean later) {
        return new Airspace(name, name, Airspace.Kind.RESTRICTED, Airspace.circle(lat, lon, 1000), "GND", "1000 FT", List.of(), later);
    }

    static MapGraphics.Item rect(String name, double lat, double lon) {
        List<MapGraphics.LatLon> c = List.of(new MapGraphics.LatLon(lat, lon), new MapGraphics.LatLon(lat + 0.01, lon),
                new MapGraphics.LatLon(lat + 0.01, lon + 0.01), new MapGraphics.LatLon(lat, lon + 0.01));
        return MapGraphics.Item.area(name, c, 0xff0000, 2, false);
    }

    @Test
    void nearAreasAndFenceRectanglesOnly() {
        List<FenceBuilder.Polygon> p = FenceBuilder.polygons(
                List.of(area("R1", 60.05, 10, false), area("FAR", 62, 10, false), area("LATER", 60.02, 10, true)),
                List.of(rect("Field", 60.1, 10.1), rect("FENCE IN work area", 59.9, 9.9), rect("fence out hut", 60.01, 10.01)),
                60, 10);
        List<String> names = new ArrayList<>();
        for (FenceBuilder.Polygon x : p) names.add(x.name() + (x.inclusion() ? " IN" : " OUT"));
        assertEquals(3, p.size(), names.toString());
        assertTrue(names.contains("R1 OUT"));
        assertTrue(names.contains("FENCE IN work area IN"));
        assertTrue(names.contains("fence out hut OUT"));
        assertFalse(names.toString().contains("Field"), "a rectangle not named FENCE is left alone");
        assertFalse(names.toString().contains("FAR"), "too far");
        assertFalse(names.toString().contains("LATER"), "not active yet");
    }

    @Test
    void itemsCountTheirPolygonsCornersAndFit() {
        List<FenceBuilder.Polygon> p = FenceBuilder.polygons(List.of(area("R1", 60.05, 10, false)), List.of(), 60, 10);
        List<MissionItem> items = FenceBuilder.items(p);
        int n = p.get(0).corners().size();
        assertTrue(n <= FenceBuilder.MAX_CORNERS && n >= 3);
        assertEquals(n, items.size());
        for (MissionItem m : items) {
            assertEquals(FenceBuilder.POLYGON_EXCLUSION, m.command);
            assertEquals(n, Math.round(m.param1), "param1 = the polygon's corner count");
        }
        List<Airspace> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(area("A" + i, 60 + i * 0.01, 10, false));
        assertTrue(FenceBuilder.items(FenceBuilder.polygons(many, List.of(), 60, 10)).size() <= FenceBuilder.MAX_ITEMS);
    }
}
