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

    /** A zip of {name, bytes} entries, in that order. */
    private Path kmz(String name, Object... entries) throws Exception {
        Path f = dir.resolve(name);
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(f))) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new java.util.zip.ZipEntry((String) entries[i]));
                Object body = entries[i + 1];
                zip.write(body instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : (byte[]) body);
                zip.closeEntry();
            }
        }
        return f;
    }

    private static String onePin(String name) {
        return HEAD + "<Placemark><name>" + name + "</name><Point><coordinates>10.7,59.9,0</coordinates></Point></Placemark>\n" + FOOT;
    }

    /**
     * I-015: a KMZ (Google Earth's and My Maps' zip) is read: its doc.kml, else its first .kml, as Google Earth does;
     * the pictures beside it are left in the zip and nothing is written to disk. Known by its first bytes, whatever its
     * name says.
     */
    @Test
    void aKmzsKmlIsRead() throws Exception {
        byte[] picture = new byte[5000];
        new java.util.Random(1).nextBytes(picture);
        Path earth = kmz("earth.kmz", "files/photo.png", picture, "files/other.kml", onePin("Other"), "doc.kml", onePin("Doc"));
        assertEquals("Doc", Kml.readImport(earth).pins().get(0).name(), "doc.kml, wherever it is in the zip");
        Path noDoc = kmz("nodoc.kmz", "files/photo.png", picture, "layers/first.kml", onePin("First"), "layers/second.kml", onePin("Second"));
        assertEquals("First", Kml.readImport(noDoc).pins().get(0).name(), "no doc.kml: the first .kml");
        Path named = kmz("zipped.kml", "doc.kml", onePin("Zipped"));
        assertEquals("Zipped", Kml.readImport(named).pins().get(0).name(), "a zip named .kml");
        assertEquals("Plain", Kml.readImport(file("plain.kmz", onePin("Plain"))).pins().get(0).name(), "a KML named .kmz");
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("earth.kmz", "nodoc.kmz", "plain.kmz", "zipped.kml"), files.map(p -> p.getFileName().toString()).sorted().toList(),
                    "nothing unzipped to disk");
        }
    }

    /**
     * I-015: the size limit holds for a KMZ's KML as it unzips, counted while it is read: a small zip that unpacks huge
     * is refused with a short line, at once, never taken in whole. A zip without KML, or broken, is refused too.
     */
    @Test
    void aKmzThatUnpacksHugeOrHasNoKmlIsRefusedWithAShortLine() throws Exception {
        byte[] blanks = (HEAD + " ".repeat((int) Kml.MAX_IMPORT_BYTES * 3) + FOOT).getBytes(StandardCharsets.UTF_8);
        Path bomb = kmz("bomb.kmz", "doc.kml", blanks);
        assertTrue(Files.size(bomb) < 1 << 20, "a small file: " + Files.size(bomb));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Kml.readImport(bomb));
        assertShort(refused.getMessage());
        assertTrue(refused.getMessage().contains((Kml.MAX_IMPORT_BYTES >> 20) + " MB"), refused.getMessage());

        Path pictures = kmz("pictures.kmz", "files/photo.png", new byte[100]);
        refused = assertThrows(IllegalArgumentException.class, () -> Kml.readImport(pictures));
        assertEquals("no KML in the KMZ", refused.getMessage());
        Path broken = Files.write(dir.resolve("broken.kmz"), "PK\u0003\u0004 and then nothing a zip has".getBytes(StandardCharsets.ISO_8859_1));
        refused = assertThrows(IllegalArgumentException.class, () -> Kml.readImport(broken));
        assertShort(refused.getMessage());
    }

    /**
     * I-015: the placemark limit counts the parts of a MultiGeometry, each of which now comes in as an item or pin of
     * its own: one placemark of 5 001 points is refused as 5 001 pins would be; one of 5 000 comes in whole.
     */
    @Test
    void theLimitCountsEveryPart() throws Exception {
        java.util.function.IntFunction<String> multi = n -> {
            StringBuilder sb = new StringBuilder(HEAD).append("<Placemark><name>Many</name><MultiGeometry>");
            for (int i = 0; i < n; i++) sb.append("<Point><coordinates>10.7,59.").append(i).append("</coordinates></Point>");
            return sb.append("</MultiGeometry></Placemark>\n").append(FOOT).toString();
        };
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Kml.readImport(file("over.kml", multi.apply(Kml.MAX_IMPORT_PLACEMARKS + 1))));
        assertShort(refused.getMessage());
        assertTrue(refused.getMessage().contains(Integer.toString(Kml.MAX_IMPORT_PLACEMARKS)), refused.getMessage());
        assertEquals(Kml.MAX_IMPORT_PLACEMARKS, Kml.readImport(file("full.kml", multi.apply(Kml.MAX_IMPORT_PLACEMARKS))).pins().size());
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
