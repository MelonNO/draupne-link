package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The small text rules of the map: KML colours, XML text, positions typed in, distances and areas written. */
class MapTextTest {
    @Test
    void kmlColoursGoBothWaysAndABadOneIsMinusOne() {
        assertEquals("ff231be3", Kml.abgr(0xe31b23, 255), "KML writes alpha, blue, green, red");
        assertEquals(0xe31b23, Kml.rgbOf("ff231be3"));
        assertEquals(0xe31b23, Kml.rgbOf(" FF231BE3 "), "upper case and spaces read too");
        assertEquals(-1, Kml.rgbOf(null));
        assertEquals(-1, Kml.rgbOf("231be3"), "six digits: not KML's");
        assertEquals(-1, Kml.rgbOf("ffzz1be3"));
    }

    @Test
    void xmlTextIsEscapedAndKeepsWhatXmlAllows() {
        assertEquals("a &lt;b&gt; &amp; c", Kml.xml("a <b> & c"));
        assertEquals("", Kml.xml(null));
        assertEquals("tab\tand\nline", Kml.xml("tab\tand\nline"), "tabs and line breaks are fine in XML");
        assertEquals("øæå 🚁", Kml.xml("øæå 🚁"));
    }

    @Test
    void aForeignKmlWithOddCoordinatesKeepsWhatReads() {
        String kml = "<kml><Document><Placemark><name>Path</name><LineString><coordinates>"
                + "10.7,59.9,0 bad 10.8,59.95 200,59.9 10.9,59.99,12</coordinates></LineString></Placemark></Document></kml>";
        Kml.Parsed p = Kml.read(kml);
        assertEquals(1, p.items().size());
        List<MapGraphics.LatLon> pts = p.items().get(0).points();
        assertEquals(3, pts.size(), "the bad tuple and the longitude past 180 are left out");
        assertEquals(59.95, pts.get(1).lat(), 1e-9);
    }

    @Test
    void distancesAndAreasAreWrittenAsTheMapWritesThem() {
        assertEquals("245 M", MapGraphics.distanceText(245.4));
        assertEquals("1.24 KM", MapGraphics.distanceText(1240));
        assertEquals("1 250 M2", MapGraphics.areaText(1250));
        assertEquals("2.4 HA", MapGraphics.areaText(24_000));
        assertEquals("1.25 KM2", MapGraphics.areaText(1_250_000));
    }

    @Test
    void secondsNeverReadSixty() {
        assertEquals("59°55'00.0\"N", Coordinates.dms(59.91666, true), "59.95 seconds and up carry into the minute");
        assertEquals("10°00'00.0\"W", Coordinates.dms(-9.999999, false), "and the minute into the degree");
    }

    @Test
    void textThatIsNoPositionReadsAsNone() {
        assertNull(Coordinates.parse(null));
        assertNull(Coordinates.parse("   "));
        assertNull(Coordinates.parse("hello"));
        assertNull(Coordinates.parse("95 10"), "a latitude past 90");
        assertNull(Coordinates.parse("32V NM 9725 426"), "MGRS digits of uneven halves");
        assertNull(Coordinates.parse("61V 597254 6642617"), "zone 61");
        assertNotNull(Coordinates.parse("59.9139,10.7522"));
    }

    @Test
    void anMgrsPositionReadsBackToItselfAcrossTheBands() {
        double[][] places = {{59.9139, 10.7522}, {-33.9, 18.4}, {78.22, 15.63}, {0.5, 30.2}, {-0.5, 30.2}, {63.99, 11.99}};
        for (double[] ll : places) {
            String m = Coordinates.mgrs(ll[0], ll[1], 5);
            double[] back = Coordinates.parse(m);
            assertNotNull(back, m);
            // the grid at 1 m truncates: back within the metre square's diagonal
            assertTrue(Mercator.distanceM(ll[0], ll[1], back[0], back[1]) < 1.5, m + " came back " + back[0] + ", " + back[1]);
        }
    }
}
