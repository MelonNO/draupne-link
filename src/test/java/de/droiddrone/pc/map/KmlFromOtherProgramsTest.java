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
        assertEquals(0, p.skipped());
        assertEquals(2, p.items().size());
        MapGraphics.Item line = p.items().get(0), area = p.items().get(1);
        assertEquals(MapGraphics.Kind.LINE, line.kind());
        assertEquals("Vei nord", line.name());
        assertEquals(0xff0000, line.colorRgb(), "the shared style's colour, through its StyleMap (before: the app's own red)");
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

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\"><Document>\n";
    private static final String FOOT = "</Document></kml>\n";

    /**
     * I-015: every part of a MultiGeometry is an item of its own. Before, only the first: a Point beside a Polygon (as
     * Google Earth writes a labelled area) came in as a pin and the area was lost, and a QGIS layer of many parts lost
     * every part after the first.
     */
    @Test
    void everyPartOfAMultiGeometryComesIn() throws Exception {
        String kml = HEAD + """
                <Placemark><name>Felt nord</name>
                  <MultiGeometry>
                    <Point><coordinates>10.75,59.95,0</coordinates></Point>
                    <Polygon><outerBoundaryIs><LinearRing><coordinates>10.7,59.9,0 10.8,59.9,0 10.8,60.0,0 10.7,59.9,0</coordinates></LinearRing></outerBoundaryIs></Polygon>
                  </MultiGeometry>
                </Placemark>
                <Placemark>
                  <Style><LineStyle><color>ff00ff00</color><width>2</width></LineStyle></Style>
                  <ExtendedData><SchemaData schemaUrl="#lag"><SimpleData name="navn">Teig</SimpleData></SchemaData></ExtendedData>
                  <MultiGeometry>
                    <Polygon><outerBoundaryIs><LinearRing><coordinates>11.0,60.0 11.1,60.0 11.1,60.1 11.0,60.0</coordinates></LinearRing></outerBoundaryIs></Polygon>
                    <Polygon><outerBoundaryIs><LinearRing><coordinates>11.2,60.0 11.3,60.0 11.3,60.1 11.2,60.0</coordinates></LinearRing></outerBoundaryIs></Polygon>
                    <MultiGeometry>
                      <LineString><coordinates>11.4,60.0 11.5,60.1</coordinates></LineString>
                      <LineString><coordinates>11.6,60.0 11.7,60.1</coordinates></LineString>
                    </MultiGeometry>
                  </MultiGeometry>
                </Placemark>
                """ + FOOT;
        Kml.Parsed p = importBytes("multi.kml", kml.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, p.pins().size(), "the label's point a pin");
        assertEquals("Felt nord", p.pins().get(0).name());
        assertEquals(5, p.items().size(), "the area beside it, and all four parts of the other, the nested ones too");
        assertEquals(MapGraphics.Kind.AREA, p.items().get(0).kind());
        assertEquals("Felt nord", p.items().get(0).name());
        for (int i = 1; i <= 2; i++) {
            assertEquals(MapGraphics.Kind.AREA, p.items().get(i).kind());
            assertEquals(0x00ff00, p.items().get(i).colorRgb(), "each part with the placemark's style");
        }
        assertEquals(11.2, p.items().get(2).points().get(0).lon(), 1e-9);
        assertEquals(MapGraphics.Kind.LINE, p.items().get(4).kind());
        assertEquals(60.1, p.items().get(4).points().get(1).lat(), 1e-9);
        assertEquals(2, p.items().get(4).widthPx());
        assertEquals(0, p.skipped());
    }

    /** I-015: Google Earth's GPS tracks (gx:Track, its points as "lon lat alt") and gx:MultiTrack come in as lines; before, dropped. */
    @Test
    void googleEarthsGpsTracksComeInAsLines() throws Exception {
        String kml = HEAD + """
                <Style id="track"><LineStyle><color>ffff0000</color><width>4</width></LineStyle></Style>
                <Placemark><name>Tur</name><styleUrl>#track</styleUrl>
                  <gx:Track>
                    <altitudeMode>clampToGround</altitudeMode>
                    <when>2026-11-20T10:00:00Z</when><when>2026-11-20T10:00:05Z</when><when>2026-11-20T10:00:10Z</when>
                    <gx:coord>10.70 59.90 120.5</gx:coord>
                    <gx:coord>10.71 59.91 121</gx:coord>
                    <gx:coord>10.72 59.915 122</gx:coord>
                    <ExtendedData><SchemaData schemaUrl="#schema"><gx:SimpleArrayData name="hr"><gx:value>90</gx:value></gx:SimpleArrayData></SchemaData></ExtendedData>
                  </gx:Track>
                </Placemark>
                <Placemark>
                  <gx:MultiTrack><gx:interpolate>0</gx:interpolate>
                    <gx:Track><when>2026-11-20T11:00:00Z</when><when>2026-11-20T11:00:05Z</when><gx:coord>10.8 59.9 0</gx:coord><gx:coord>10.81 59.9 0</gx:coord></gx:Track>
                    <gx:Track><when>2026-11-20T11:10:00Z</when><when>2026-11-20T11:10:05Z</when><gx:coord>10.9 59.9 0</gx:coord><gx:coord>10.91 59.9 0</gx:coord></gx:Track>
                  </gx:MultiTrack>
                </Placemark>
                """ + FOOT;
        Kml.Parsed p = importBytes("tracks.kml", kml.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, p.items().size(), "one track, and the two of the multi-track each its own line");
        MapGraphics.Item tur = p.items().get(0);
        assertEquals(MapGraphics.Kind.LINE, tur.kind());
        assertEquals("Tur", tur.name());
        assertEquals(3, tur.points().size());
        assertEquals(59.915, tur.points().get(2).lat(), 1e-9, "longitude first");
        assertEquals(10.72, tur.points().get(2).lon(), 1e-9);
        assertEquals(0x0000ff, tur.colorRgb(), "its shared style");
        assertEquals(4, tur.widthPx());
        assertEquals("Track", p.items().get(1).name(), "no name of its own");
        assertEquals(10.9, p.items().get(2).points().get(0).lon(), 1e-9);
        assertEquals(0, p.skipped());
    }

    /**
     * I-015: a styleUrl to a Style or StyleMap in the same file gives its line colour, width and fill, as a style of
     * the placemark's own does; before, such drawings came in red, 3 wide. My Maps' way: a StyleMap per look, its
     * normal pair a Style of its own.
     */
    @Test
    void sharedStylesGiveTheirColourWidthAndFill() throws Exception {
        String kml = HEAD + """
                <Style id="poly-0288D1-1200-77-nodesc-normal"><LineStyle><color>ffd18802</color><width>1.2</width></LineStyle><PolyStyle><color>4dd18802</color><fill>1</fill><outline>1</outline></PolyStyle></Style>
                <Style id="poly-0288D1-1200-77-nodesc-highlight"><LineStyle><color>ff000000</color><width>1.8</width></LineStyle></Style>
                <StyleMap id="poly-0288D1-1200-77-nodesc">
                  <Pair><key>highlight</key><styleUrl>#poly-0288D1-1200-77-nodesc-highlight</styleUrl></Pair>
                  <Pair><key>normal</key><styleUrl>#poly-0288D1-1200-77-nodesc-normal</styleUrl></Pair>
                </StyleMap>
                <StyleMap id="inline-pair"><Pair><key>normal</key><Style><LineStyle><color>ff00ffff</color><width>6</width></LineStyle></Style></Pair></StyleMap>
                <Style id="fill-only"><PolyStyle><color>8000ff00</color></PolyStyle></Style>
                <Placemark><name>Sone</name><styleUrl>#poly-0288D1-1200-77-nodesc</styleUrl>
                  <Polygon><outerBoundaryIs><LinearRing><coordinates>10.7,59.9 10.8,59.9 10.8,60.0 10.7,59.9</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
                <Placemark><name>Gul</name><styleUrl>#inline-pair</styleUrl><LineString><coordinates>10.7,59.9 10.8,59.9</coordinates></LineString></Placemark>
                <Placemark><name>Bredere</name><styleUrl>#poly-0288D1-1200-77-nodesc-normal</styleUrl>
                  <Style><LineStyle><width>7</width></LineStyle></Style><LineString><coordinates>10.7,59.9 10.8,59.9</coordinates></LineString></Placemark>
                <Placemark><name>Grønn</name><styleUrl>#fill-only</styleUrl>
                  <Polygon><outerBoundaryIs><LinearRing><coordinates>10.7,59.9 10.8,59.9 10.8,60.0 10.7,59.9</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
                <Placemark><name>Ukjent</name><styleUrl>#not-in-this-file</styleUrl><LineString><coordinates>10.7,59.9 10.8,59.9</coordinates></LineString></Placemark>
                """ + FOOT;
        Kml.Parsed p = importBytes("styles.kml", kml.getBytes(StandardCharsets.UTF_8));
        assertEquals(5, p.items().size());
        MapGraphics.Item sone = p.items().get(0), gul = p.items().get(1), bredere = p.items().get(2), gronn = p.items().get(3), ukjent = p.items().get(4);
        assertEquals(0x0288d1, sone.colorRgb(), "the StyleMap's normal look, not its highlight");
        assertEquals(1, sone.widthPx());
        assertTrue(sone.filled());
        assertEquals(0xffff00, gul.colorRgb(), "a normal pair with a Style of its own");
        assertEquals(6, gul.widthPx());
        assertEquals(0x0288d1, bredere.colorRgb(), "what its own style does not say, from the shared one");
        assertEquals(7, bredere.widthPx(), "its own style first");
        assertEquals(0x00ff00, gronn.colorRgb(), "an area with only a fill colour takes it");
        assertEquals(0xe31b23, ukjent.colorRgb(), "a style not in the file: the app's red, as before");
        assertEquals(3, ukjent.widthPx());
    }

    /**
     * I-015: what IMPORT does not read is counted, to be said as skipped - before, it was dropped without a word: a
     * picture laid on the ground or the screen, a network link, a tour, a 3D model, a placemark with nothing on the
     * map, an area's hole, a line of one point.
     */
    @Test
    void whatIsNotReadIsCounted() throws Exception {
        String kml = HEAD + """
                <GroundOverlay><name>Kart</name><Icon><href>files/kart.png</href></Icon><LatLonBox><north>60</north><south>59.9</south><east>10.8</east><west>10.7</west></LatLonBox></GroundOverlay>
                <ScreenOverlay><name>Logo</name><Icon><href>files/logo.png</href></Icon></ScreenOverlay>
                <NetworkLink><name>Live</name><Link><href>https://example.com/live.kml</href></Link></NetworkLink>
                <gx:Tour><name>Tur</name><gx:Playlist/></gx:Tour>
                <Placemark><name>Hus</name><Model><Link><href>files/hus.dae</href></Link></Model></Placemark>
                <Placemark><name>Bare navn</name></Placemark>
                <Placemark><name>Med hull</name><Polygon>
                  <outerBoundaryIs><LinearRing><coordinates>10.7,59.9 10.8,59.9 10.8,60.0 10.7,59.9</coordinates></LinearRing></outerBoundaryIs>
                  <innerBoundaryIs><LinearRing><coordinates>10.75,59.92 10.76,59.92 10.76,59.93 10.75,59.92</coordinates></LinearRing></innerBoundaryIs>
                </Polygon></Placemark>
                <Placemark><name>Ett punkt</name><LineString><coordinates>10.7,59.9</coordinates></LineString></Placemark>
                <Placemark><name>Ring</name><LinearRing><coordinates>10.7,59.9 10.8,59.9 10.8,60.0</coordinates></LinearRing></Placemark>
                <Placemark><name>Pin</name><Point><coordinates>10.75,59.92</coordinates></Point></Placemark>
                """ + FOOT;
        Kml.Parsed p = importBytes("other.kml", kml.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, p.items().size(), "the area without its hole, the ring as its outline");
        assertEquals(MapGraphics.Kind.AREA, p.items().get(0).kind());
        MapGraphics.Item ring = p.items().get(1);
        assertEquals(MapGraphics.Kind.LINE, ring.kind());
        assertEquals(4, ring.points().size(), "closed: it ends where it began");
        assertEquals(ring.points().get(0), ring.points().get(3));
        assertEquals(1, p.pins().size());
        assertEquals(8, p.skipped(), "overlay, screen overlay, network link, tour, model, empty placemark, hole, one-point line");
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
