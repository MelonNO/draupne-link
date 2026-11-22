package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IMPORT's limits: one KML file is taken up to a size, a count of placemarks and a depth of nesting, and over any of
 * them it is refused with a short line - never a window that stops answering, never an Error. Files of the sizes the
 * app writes and other programs export come in whole, and the pins of an import are saved once.
 */
class KmlImportTest {
    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n<name>Test</name>\n";
    private static final String FOOT = "</Document>\n</kml>\n";

    @TempDir
    Path dir;

    private Path file(String name, String kml) throws Exception {
        return Files.writeString(dir.resolve(name), kml, StandardCharsets.UTF_8);
    }

    private static String points(int n) {
        StringBuilder sb = new StringBuilder(HEAD);
        for (int i = 0; i < n; i++) {
            sb.append("<Placemark><name>P").append(i).append("</name><Point><coordinates>10.").append(String.format("%05d", i % 100_000))
                    .append(",59.9,0</coordinates></Point></Placemark>\n");
        }
        return sb.append(FOOT).toString();
    }

    private static void assertShort(String line) {
        assertNotNull(line);
        assertTrue(line.length() <= 60 && !line.contains("\n"), "a short line for the pilot: " + line);
    }

    @Test
    void aFileOfFiftyThousandPlacemarksIsRefusedWithAShortLine() throws Exception {
        Path f = file("many.kml", points(50_000));
        assertTrue(Files.size(f) < Kml.MAX_IMPORT_BYTES, "it is the count that refuses it, not the size: " + Files.size(f));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Kml.readImport(f));
        assertShort(refused.getMessage());
        assertTrue(refused.getMessage().contains(Integer.toString(Kml.MAX_IMPORT_PLACEMARKS)), refused.getMessage());
        // one over the limit is refused, the limit itself is taken
        assertThrows(IllegalArgumentException.class, () -> Kml.readImport(file("over.kml", points(Kml.MAX_IMPORT_PLACEMARKS + 1))));
        assertEquals(Kml.MAX_IMPORT_PLACEMARKS, Kml.readImport(file("full.kml", points(Kml.MAX_IMPORT_PLACEMARKS))).pins().size());
    }

    @Test
    void aNameNestedAHundredThousandDeepIsRefusedNotAnError() throws Exception {
        String deep = HEAD + "<Placemark><name>" + "<b>".repeat(100_000) + "x" + "</b>".repeat(100_000)
                + "</name><Point><coordinates>10.7,59.9,0</coordinates></Point></Placemark>\n" + FOOT;
        Path f = file("deep.kml", deep);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Kml.readImport(f));
        assertShort(refused.getMessage());
        // the same text as the app's own drawings file, or as a pilot PC's map sent to a spectator: refused there too
        assertThrows(IllegalArgumentException.class, () -> Kml.read(deep));
        Path own = file("graphics.kml", deep);
        MapGraphics g = new MapGraphics(own);
        assertTrue(g.isEmpty());
        assertNotNull(g.brokenMovedTo(), "moved aside as any file that can't be read");
    }

    @Test
    void aFileOverTheSizeLimitIsRefused() throws Exception {
        // a small KML made big with blank space: nothing in it but its size is over a limit
        StringBuilder big = new StringBuilder((int) Kml.MAX_IMPORT_BYTES + 1024).append(HEAD)
                .append("<Placemark><name>One</name><Point><coordinates>10.7,59.9,0</coordinates></Point></Placemark>\n");
        big.append(" ".repeat((int) Kml.MAX_IMPORT_BYTES)).append(FOOT);
        Path f = file("big.kml", big.toString());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Kml.readImport(f));
        assertShort(refused.getMessage());
        assertTrue(refused.getMessage().contains((Kml.MAX_IMPORT_BYTES >> 20) + " MB"), refused.getMessage());
    }

    @Test
    void filesOfUsualSizesComeInWhole() throws Exception {
        // what the app itself writes: 200 drawings of 50 points each and pins up to the limit
        List<MapGraphics.Item> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            List<MapGraphics.LatLon> line = new ArrayList<>();
            for (int k = 0; k < 50; k++) line.add(new MapGraphics.LatLon(59.9 + i * 1e-3, 10.7 + k * 1e-3));
            items.add(i % 2 == 0 ? MapGraphics.Item.line("Line " + i, line, 0xff0000, 3) : MapGraphics.Item.area("Area " + i, line, 0x00ff00, 2, true));
        }
        List<Pins.Pin> pins = new ArrayList<>();
        for (int i = 0; i < Kml.MAX_IMPORT_PLACEMARKS - 200; i++) pins.add(new Pins.Pin("Pin " + i, 59.9 + i * 1e-5, 10.7));
        Path own = file("export.kml", Kml.write(items, pins, "DRAUPNE Link export"));
        assertTrue(Files.size(own) < Kml.MAX_IMPORT_BYTES / 2, "an export at the count's limit is well under the size's: " + Files.size(own));
        Kml.Parsed back = Kml.readImport(own);
        assertEquals(200, back.items().size());
        assertEquals(pins.size(), back.pins().size());
        assertEquals("Area 199", back.items().get(199).name());

        // another program's: folders in folders (30 deep), a description with markup of its own, a polygon
        StringBuilder other = new StringBuilder(HEAD);
        for (int i = 0; i < 30; i++) other.append("<Folder><name>Layer ").append(i).append("</name>\n");
        other.append("<Placemark><name>Field</name><description><![CDATA[<div><table><tr><td><b>Owner</b></td></tr></table></div>]]></description>")
                .append("<Style><LineStyle><color>ff0000ff</color><width>2</width></LineStyle></Style>")
                .append("<Polygon><outerBoundaryIs><LinearRing><coordinates>10.7,59.9,0 10.8,59.9,0 10.8,60.0,0 10.7,59.9,0</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n");
        for (int i = 0; i < 30; i++) other.append("</Folder>\n");
        Kml.Parsed parsed = Kml.readImport(file("layers.kml", other.append(FOOT).toString()));
        assertEquals(1, parsed.items().size());
        assertEquals(MapGraphics.Kind.AREA, parsed.items().get(0).kind());
        assertEquals(3, parsed.items().get(0).points().size());
    }

    /** The app's own drawings file grows with every import: it is read whole whatever it holds, never moved aside for its count. */
    @Test
    void theAppsOwnDrawingsFileHasNoLimitOnItsCount() throws Exception {
        List<MapGraphics.Item> items = new ArrayList<>();
        List<MapGraphics.LatLon> two = List.of(new MapGraphics.LatLon(59.9, 10.7), new MapGraphics.LatLon(59.91, 10.71));
        for (int i = 0; i < Kml.MAX_IMPORT_PLACEMARKS + 500; i++) items.add(MapGraphics.Item.line("Line " + i, two, 0xff0000, 3));
        String text = Kml.write(items, List.of(), "DRAUPNE Link graphics");
        assertEquals(items.size(), Kml.read(text).items().size());
        MapGraphics g = new MapGraphics(file("graphics.kml", text));
        assertNull(g.brokenMovedTo());
        assertEquals(items.size(), g.list().size());
    }

    @Test
    void anImportsPinsAreSavedOnce() throws Exception {
        Path file = dir.resolve("pins.txt");
        Pins pins = new Pins(file);
        pins.add("Before", 59.0, 10.0);
        int before = pins.saves();
        List<Pins.Pin> more = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) more.add(new Pins.Pin(i == 7 ? "two\nlines\there" : i == 8 ? " " : "Imported " + i, 59.9 + i * 1e-5, 10.7));
        long t0 = System.currentTimeMillis();
        pins.addAll(more);
        assertEquals(before + 1, pins.saves(), "the file written once for the whole import");
        List<Pins.Pin> read = new Pins(file).list();
        assertEquals(2_001, read.size());
        assertNull(new Pins(file).brokenMovedTo(), "a name over two lines is one line in the file");
        assertEquals("two lines here", read.get(8).name());
        assertEquals("Alpha", read.get(9).name(), "a pin without a name gets the next free one, as a dropped pin does");
        assertTrue(read.get(1).createdMs() >= t0, "dropped now");
        pins.addAll(List.of());
        assertEquals(before + 1, pins.saves(), "nothing to add: nothing written");
    }
}
