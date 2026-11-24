package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IMPORT of files shaped as other programs write them - Google Earth Pro, Google My Maps, QGIS - with what comes with
 * them: shared styles, folders, descriptions, a byte order mark, Windows line ends, other encodings, and the white
 * space the files put in and round their coordinates.
 */
class KmlFromOtherProgramsTest {
    @TempDir
    Path dir;

    private Kml.Parsed importBytes(String name, byte[] bytes) throws Exception {
        return Kml.readImport(Files.write(dir.resolve(name), bytes));
    }

    /** Google Earth Pro's "Save Place As" (KML): shared styles, a folder, indented coordinates, a byte order mark, CRLF. */
    @Test
    void aGoogleEarthFileWithItsStylesFoldersBomAndCrlf() throws Exception {
        String kml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2" xmlns:kml="http://www.opengis.net/kml/2.2" xmlns:atom="http://www.w3.org/2005/Atom">
                <Document>
                	<name>Øvelse.kml</name>
                	<Style id="s_ylw-pushpin"><IconStyle><scale>1.1</scale></IconStyle><LineStyle><color>ff0000ff</color><width>3</width></LineStyle></Style>
                	<StyleMap id="m_ylw-pushpin"><Pair><key>normal</key><styleUrl>#s_ylw-pushpin</styleUrl></Pair><Pair><key>highlight</key><styleUrl>#s_ylw-pushpin</styleUrl></Pair></StyleMap>
                	<Folder>
                		<name>Rute</name>
                		<open>1</open>
                		<Placemark>
                			<name>Vei nord</name>
                			<styleUrl>#m_ylw-pushpin</styleUrl>
                			<LineString>
                				<tessellate>1</tessellate>
                				<coordinates>
                					10.70123,59.90456,0 10.71234,59.91567,0\s
                				</coordinates>
                			</LineString>
                		</Placemark>
                		<Placemark>
                			<name>Felt</name>
                			<Style><LineStyle><color>ff00ff00</color><width>2</width></LineStyle><PolyStyle><color>7f00ff00</color></PolyStyle></Style>
                			<Polygon>
                				<tessellate>1</tessellate>
                				<outerBoundaryIs><LinearRing><coordinates>
                					10.7,59.9,0 10.8,59.9,0 10.8,60.0,0 10.7,59.9,0\s
                				</coordinates></LinearRing></outerBoundaryIs>
                			</Polygon>
                		</Placemark>
                		<Placemark>
                			<name>Samlingsplass</name>
                			<description><![CDATA[<b>Møt</b> her & vent]]></description>
                			<LookAt><longitude>10.75</longitude><latitude>59.92</latitude><range>500</range></LookAt>
                			<styleUrl>#m_ylw-pushpin</styleUrl>
                			<Point><gx:drawOrder>1</gx:drawOrder><coordinates>10.75,59.92,0</coordinates></Point>
                		</Placemark>
                	</Folder>
                </Document>
                </kml>
                """.replace("\n", "\r\n");
        byte[] text = kml.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[text.length + 3];
        bom[0] = (byte) 0xef;
        bom[1] = (byte) 0xbb;
        bom[2] = (byte) 0xbf;
        System.arraycopy(text, 0, bom, 3, text.length);
        Kml.Parsed p = importBytes("google-earth.kml", bom);
        assertEquals(2, p.items().size());
        MapGraphics.Item line = p.items().get(0), area = p.items().get(1);
        assertEquals(MapGraphics.Kind.LINE, line.kind());
        assertEquals("Vei nord", line.name());
        assertEquals(59.90456, line.points().get(0).lat(), 1e-9, "longitude first in the file");
        assertEquals(10.70123, line.points().get(0).lon(), 1e-9);
        assertEquals(MapGraphics.Kind.AREA, area.kind());
        assertEquals(3, area.points().size(), "the closing tuple dropped");
        assertEquals(0x00ff00, area.colorRgb(), "its own style's aabbggrr");
        assertEquals(1, p.pins().size());
        assertEquals("Samlingsplass", p.pins().get(0).name());
        assertEquals(59.92, p.pins().get(0).lat(), 1e-9);
    }

    /** Google My Maps' KML export: a folder per layer, its own fields as ExtendedData, two-value tuples. */
    @Test
    void aMyMapsExportWithItsFieldsAndLayers() throws Exception {
        String kml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <kml xmlns="http://www.opengis.net/kml/2.2">
                  <Document>
                    <name>Øving</name>
                    <Style id="line-0288D1-1200-nodesc-normal"><LineStyle><color>ffd18802</color><width>1.2</width></LineStyle></Style>
                    <Folder>
                      <name>Lag uten navn</name>
                      <Placemark>
                        <name>Startpunkt</name>
                        <styleUrl>#icon-1899-0288D1</styleUrl>
                        <ExtendedData><Data name="Kategori"><value>Base</value></Data><Data name="kind"><value>landing</value></Data></ExtendedData>
                        <Point><coordinates>10.7522,59.9139,0</coordinates></Point>
                      </Placemark>
                      <Placemark>
                        <name>Linje 1</name>
                        <styleUrl>#line-0288D1-1200-nodesc</styleUrl>
                        <LineString><tessellate>1</tessellate><coordinates>
                          10.7522,59.9139
                          10.7601,59.9201
                          10.7700,59.9150
                        </coordinates></LineString>
                      </Placemark>
                    </Folder>
                  </Document>
                </kml>
                """;
        Kml.Parsed p = importBytes("my-maps.kml", kml.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, p.pins().size(), "a field called kind is not DRAUPNE's: a point stays a pin");
        assertEquals("Startpunkt", p.pins().get(0).name());
        assertEquals(1, p.items().size());
        assertEquals(3, p.items().get(0).points().size(), "tuples on lines of their own, no altitude");
        assertEquals(59.9201, p.items().get(0).points().get(1).lat(), 1e-9);
    }

    /** QGIS's (OGR's) export: no name, the fields as SchemaData, a style of its own, two-value tuples. */
    @Test
    void aQgisExport() throws Exception {
        String kml = """
                <?xml version="1.0" encoding="utf-8" ?>
                <kml xmlns="http://www.opengis.net/kml/2.2">
                <Document id="root_doc">
                <Schema name="omraader" id="omraader">
                	<SimpleField name="navn" type="string"></SimpleField>
                </Schema>
                <Folder><name>omraader</name>
                  <Placemark>
                	<Style><LineStyle><color>ff0000ff</color></LineStyle><PolyStyle><fill>0</fill></PolyStyle></Style>
                	<ExtendedData><SchemaData schemaUrl="#omraader">
                		<SimpleData name="navn">Sone A &amp; B</SimpleData>
                	</SchemaData></ExtendedData>
                      <Polygon><outerBoundaryIs><LinearRing><coordinates>10.7,59.9 10.8,59.9 10.8,60.0 10.7,59.9</coordinates></LinearRing></outerBoundaryIs></Polygon>
                  </Placemark>
                </Folder>
                </Document></kml>
                """;
        Kml.Parsed p = importBytes("qgis.kml", kml.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, p.items().size());
        assertEquals("Area", p.items().get(0).name(), "no name of its own");
        assertEquals(0xff0000, p.items().get(0).colorRgb());
        assertFalse(p.items().get(0).filled());
    }

    @Test
    void otherEncodingsAreReadByTheirDeclaration() throws Exception {
        String utf16 = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><name>Tromsø</name><Point><coordinates>18.95,69.65,0</coordinates></Point></Placemark></Document></kml>";
        assertEquals("Tromsø", importBytes("utf16.kml", utf16.getBytes(StandardCharsets.UTF_16)).pins().get(0).name(), "UTF-16 with its BOM");
        String latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><name>Bodø</name><Point><coordinates>14.4,67.28,0</coordinates></Point></Placemark></Document></kml>";
        assertEquals("Bodø", importBytes("latin.kml", latin.getBytes(StandardCharsets.ISO_8859_1)).pins().get(0).name());
    }

    /**
     * A space after a tuple's commas ("10.7, 59.9, 0"): KML asks for none, but hand-made files and some converters
     * have it. Split at it, every point of such a line was dropped and IMPORT said "0 drawings".
     */
    @Test
    void spacesInsideATupleKeepItsPoints() throws Exception {
        String kml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><name>Spaced</name><LineString><coordinates>10.7, 59.9, 0\n  10.8 ,59.95 , 0\t10.9,\t60.0</coordinates></LineString></Placemark>"
                + "<Placemark><name>Pin</name><Point><coordinates> 10.75 , 59.92 </coordinates></Point></Placemark></Document></kml>";
        Kml.Parsed p = importBytes("spaced.kml", kml.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, p.items().size());
        assertEquals(3, p.items().get(0).points().size());
        assertEquals(59.95, p.items().get(0).points().get(1).lat(), 1e-9);
        assertEquals(10.9, p.items().get(0).points().get(2).lon(), 1e-9);
        assertEquals(1, p.pins().size());
        assertEquals(59.92, p.pins().get(0).lat(), 1e-9);
    }

    /** Megabytes of blanks inside one coordinates element, within IMPORT's size: read at once, not held up. */
    @Test
    void aLongBlankRunInsideCoordinatesIsReadQuickly() throws Exception {
        String kml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><name>Blank</name><LineString><coordinates>10.7,59.9" + " ".repeat(4_000_000) + ",0 10.8,59.9,0"
                + " ".repeat(4_000_000) + "</coordinates></LineString></Placemark></Document></kml>";
        long t0 = System.nanoTime();
        Kml.Parsed p = importBytes("blank.kml", kml.getBytes(StandardCharsets.UTF_8));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(2, p.items().get(0).points().size());
        assertTrue(ms < 5_000, "read in " + ms + " ms");
    }
}
