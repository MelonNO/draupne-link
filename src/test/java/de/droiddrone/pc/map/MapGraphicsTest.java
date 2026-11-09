package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The map's graphics (1.0.35): lengths and areas, the KML they are kept and exchanged in, names. */
class MapGraphicsTest {
    private static final MapGraphics.LatLon A = new MapGraphics.LatLon(59.9000, 10.7000);
    private static final MapGraphics.LatLon B = new MapGraphics.LatLon(59.9000, 10.7100); // ~559 m east of A
    private static final MapGraphics.LatLon C = new MapGraphics.LatLon(59.9050, 10.7100); // ~556 m north of B
    private static final MapGraphics.LatLon D = new MapGraphics.LatLon(59.9050, 10.7000);

    @Test
    void aMeasurementAddsItsLegsAndAClosedOneHasAnArea() {
        MapGraphics.Item open = MapGraphics.Item.measurement("M", List.of(A, B, C), false);
        double[] legs = open.legsM();
        assertEquals(2, legs.length);
        assertEquals(559, legs[0], 5, "A to B");
        assertEquals(556, legs[1], 5, "B to C");
        assertEquals(legs[0] + legs[1], open.lengthM(), 0.001);
        assertEquals(0, open.areaM2(), "open: no area");

        MapGraphics.Item square = MapGraphics.Item.measurement("S", List.of(A, B, C, D), true);
        assertEquals(4, square.legsM().length, "closed: the leg back to the start counts");
        assertEquals(559 * 2 + 556 * 2, square.lengthM(), 20);
        assertEquals(559 * 556, square.areaM2(), 559 * 556 * 0.02, "about 31 ha");
        assertEquals("31.1 HA", MapGraphics.areaText(square.areaM2()));
        assertEquals("1 250 M2", MapGraphics.areaText(1250));
        assertEquals("1.50 KM2", MapGraphics.areaText(1_500_000));
        assertEquals("2.23 KM", MapGraphics.distanceText(2230));
    }

    @Test
    void everythingSurvivesTheKmlRoundTrip() {
        List<MapGraphics.Item> items = List.of(
                MapGraphics.Item.measurement("Walk", List.of(A, B, C), false),
                MapGraphics.Item.measurement("Field", List.of(A, B, C, D), true),
                MapGraphics.Item.line("Route 1", List.of(A, C), 0x3b82f6, 4),
                MapGraphics.Item.arrow("Attack", List.of(D, A, B), 0xe31b23, 6),
                MapGraphics.Item.area("Box", List.of(A, B, C, D), 0x009639, 2, true),
                MapGraphics.Item.symbol("Infantry 1", A, MapGraphics.UnitType.INFANTRY, MapGraphics.Affiliation.HOSTILE, MapGraphics.Echelon.PLATOON),
                MapGraphics.Item.symbol("HQ", B, MapGraphics.UnitType.HQ, MapGraphics.Affiliation.FRIEND, null));
        List<Pins.Pin> pins = List.of(new Pins.Pin("Alpha", 59.91, 10.71));
        String kml = Kml.write(items, pins, "Test");
        assertTrue(kml.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\">"));
        assertTrue(kml.contains("<Polygon>"), "areas and closed measurements are polygons");
        assertTrue(kml.contains("<LineString>"));
        assertTrue(kml.contains("ff231be3"), "the accent red as KML's aabbggrr: " + kml);

        Kml.Parsed back = Kml.read(kml);
        assertEquals(items.size(), back.items().size());
        for (int i = 0; i < items.size(); i++) {
            MapGraphics.Item want = items.get(i), got = back.items().get(i);
            assertEquals(want.kind(), got.kind(), want.name());
            assertEquals(want.name(), got.name());
            assertEquals(want.points().size(), got.points().size(), want.name() + " points");
            for (int p = 0; p < want.points().size(); p++) {
                assertEquals(want.points().get(p).lat(), got.points().get(p).lat(), 1e-6);
                assertEquals(want.points().get(p).lon(), got.points().get(p).lon(), 1e-6);
            }
            if (want.kind() != MapGraphics.Kind.SYMBOL) {
                assertEquals(want.colorRgb(), got.colorRgb(), want.name() + " colour");
                assertEquals(want.widthPx(), got.widthPx(), want.name() + " width");
                assertEquals(want.filled(), got.filled(), want.name() + " fill");
                assertEquals(want.closed(), got.closed(), want.name() + " closed");
            } else {
                assertEquals(want.unit(), got.unit());
                assertEquals(want.affiliation(), got.affiliation());
                assertEquals(want.echelon(), got.echelon());
            }
        }
        assertEquals(1, back.pins().size());
        assertEquals("Alpha", back.pins().get(0).name());
        assertEquals(59.91, back.pins().get(0).lat(), 1e-6);
    }

    /** Google Earth's own KML: a path, a polygon and a placemark, without DRAUPNE's ExtendedData. */
    @Test
    void anotherProgramsKmlBecomesLinesAreasAndPins() {
        String kml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><name>Path</name><Style><LineStyle><color>ff00ff00</color><width>2.5</width></LineStyle></Style>"
                + "<LineString><tessellate>1</tessellate><coordinates>10.70,59.90,0\n 10.71,59.90,12.5</coordinates></LineString></Placemark>"
                + "<Placemark><name>Zone</name><Style><PolyStyle><color>7f0000ff</color><fill>1</fill></PolyStyle></Style>"
                + "<Polygon><outerBoundaryIs><LinearRing><coordinates>10.70,59.90,0 10.71,59.90,0 10.71,59.905,0 10.70,59.90,0</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>"
                + "<Placemark><name>Camp</name><Point><coordinates>10.705,59.902,0</coordinates></Point></Placemark>"
                + "</Document></kml>";
        Kml.Parsed p = Kml.read(kml);
        assertEquals(2, p.items().size());
        MapGraphics.Item path = p.items().get(0);
        assertEquals(MapGraphics.Kind.LINE, path.kind());
        assertEquals(0x00ff00, path.colorRgb(), "KML's aabbggrr read back");
        assertEquals(3, path.widthPx(), "2.5 rounded");
        MapGraphics.Item zone = p.items().get(1);
        assertEquals(MapGraphics.Kind.AREA, zone.kind());
        assertEquals(3, zone.points().size(), "the closing point is not a corner");
        assertTrue(zone.filled());
        assertEquals(1, p.pins().size());
        assertEquals("Camp", p.pins().get(0).name());
        assertThrows(IllegalArgumentException.class, () -> Kml.read("not xml at all"));
    }

    @Test
    void itemsAreKeptInTheFileAndNamedInOrder(@TempDir Path dir) {
        Path file = dir.resolve("graphics.kml");
        MapGraphics g = new MapGraphics(file);
        int[] changes = {0};
        g.onChange(() -> changes[0]++);
        assertEquals("Line 1", g.nextName(MapGraphics.Kind.LINE, null));
        g.add(MapGraphics.Item.line("Line 1", List.of(A, B), 0xffffff, 2));
        assertEquals("Line 2", g.nextName(MapGraphics.Kind.LINE, null));
        assertEquals("Infantry 1", g.nextName(MapGraphics.Kind.SYMBOL, MapGraphics.UnitType.INFANTRY));
        MapGraphics.Item s = g.add(MapGraphics.Item.symbol("Infantry 1", C, MapGraphics.UnitType.INFANTRY, MapGraphics.Affiliation.FRIEND, MapGraphics.Echelon.COMPANY));
        g.rename(s, "Recce");
        assertEquals(3, changes[0], "add, add, rename");
        assertTrue(Files.exists(file));

        MapGraphics again = new MapGraphics(file);
        assertEquals(2, again.list().size());
        assertEquals("Recce", again.list().get(1).name());
        assertEquals(1, again.inside(59.90, 59.91, 10.69, 10.705).size(), "only the line has a point in that box");
        again.remove(again.list().get(0));
        again.clear();
        assertTrue(new MapGraphics(file).isEmpty());
    }

    /**
     * A name with a character XML can't hold (a control character pasted into the name box) is written without it: it
     * made graphics.kml unreadable, and the next start moved every drawing aside.
     */
    @Test
    void aNameXmlCantHoldStillLeavesAReadableFile(@TempDir Path dir) {
        Path file = dir.resolve("graphics.kml");
        MapGraphics g = new MapGraphics(file);
        g.add(MapGraphics.Item.line("Road\u0001block\u0000", List.of(A, B), 0xffffff, 2));
        MapGraphics again = new MapGraphics(file);
        assertNull(again.brokenMovedTo(), "the file reads");
        assertEquals(1, again.list().size());
        assertEquals("Roadblock", again.list().get(0).name());
    }
}
