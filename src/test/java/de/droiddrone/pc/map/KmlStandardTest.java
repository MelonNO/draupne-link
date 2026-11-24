package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What EXPORT, CLEAR ALL's copy and the mirror write, held against KML 2.2 itself and read by the JDK's own XML parser
 * (namespace-aware, nothing of the app's reading): the namespace, the element order of a Feature as the schema gives
 * it, lon,lat,alt tuples, closed rings of four tuples or more, and names with markup characters and other scripts
 * coming back as they were.
 */
class KmlStandardTest {
    private static final String NS = "http://www.opengis.net/kml/2.2";
    /**
     * KML 2.2's AbstractFeatureType sequence (ogckml22.xsd), then Placemark's geometry: the order a Placemark's own
     * elements must keep.
     */
    private static final List<String> FEATURE_ORDER = List.of("name", "visibility", "open", "author", "link", "address",
            "AddressDetails", "phoneNumber", "Snippet", "description", "Camera", "LookAt", "TimeStamp", "TimeSpan", "styleUrl",
            "Style", "StyleMap", "Region", "Metadata", "ExtendedData", "Point", "LineString", "LinearRing", "Polygon",
            "MultiGeometry", "Model");

    private static final String ODD = "Ridge & valley <B> \"north\" 'gate' Ærøy Tromsø Сектор 北 🚁";

    private static String sample() {
        List<MapGraphics.LatLon> three = List.of(new MapGraphics.LatLon(-33.45, -70.66), new MapGraphics.LatLon(-33.44, -70.65),
                new MapGraphics.LatLon(-33.46, -70.64));
        List<MapGraphics.Item> items = List.of(
                MapGraphics.Item.area(ODD, three, 0x12ab34, 3, true),
                MapGraphics.Item.line("Line", three, 0xff0000, 2),
                MapGraphics.Item.arrow("Arrow", three.subList(0, 2), 0x0000ff, 4),
                MapGraphics.Item.measurement("Loop", three, true),
                MapGraphics.Item.measurement("Leg", three.subList(0, 2), false),
                MapGraphics.Item.symbol("Unit & co", three.get(0), MapGraphics.UnitType.UNIT, MapGraphics.Affiliation.FRIEND, null));
        return Kml.write(items, List.of(new Pins.Pin(ODD, 59.9123456, 10.7543210)), ODD);
    }

    private static Document parse(String kml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(kml.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Element> children(Node n) {
        List<Element> out = new ArrayList<>();
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) out.add(e);
        return out;
    }

    @Test
    void itIsKml22InItsNamespaceEveryElementInIt() throws Exception {
        Document doc = parse(sample());
        Element root = doc.getDocumentElement();
        assertEquals(NS, root.getNamespaceURI());
        assertEquals("kml", root.getLocalName());
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) assertEquals(NS, all.item(i).getNamespaceURI(), all.item(i).getNodeName());
        assertEquals(7, doc.getElementsByTagNameNS(NS, "Placemark").getLength());
    }

    /** Before, a drawing's ExtendedData came before its Style: the other way round from KML 2.2's schema. */
    @Test
    void aPlacemarksElementsKeepTheSchemasOrder() throws Exception {
        NodeList marks = parse(sample()).getElementsByTagNameNS(NS, "Placemark");
        for (int i = 0; i < marks.getLength(); i++) {
            int last = -1;
            for (Element e : children(marks.item(i))) {
                int at = FEATURE_ORDER.indexOf(e.getLocalName());
                assertTrue(at >= 0, "an element KML 2.2 has: " + e.getLocalName());
                String before = last < 0 ? "its start" : FEATURE_ORDER.get(last);
                assertTrue(at >= last, "placemark " + i + ": " + e.getLocalName() + " after " + before);
                last = at;
            }
        }
    }

    @Test
    void coordinatesAreLonLatAltAndRingsAreClosed() throws Exception {
        Document doc = parse(sample());
        NodeList rings = doc.getElementsByTagNameNS(NS, "LinearRing");
        assertEquals(2, rings.getLength(), "the area and the closed measurement");
        for (int i = 0; i < rings.getLength(); i++) {
            String[] tuples = ((Element) rings.item(i)).getElementsByTagNameNS(NS, "coordinates").item(0).getTextContent().trim().split("\\s+");
            assertTrue(tuples.length >= 4, "a LinearRing has four tuples or more");
            assertEquals(tuples[0], tuples[tuples.length - 1], "closed: the last tuple is the first");
        }
        NodeList coords = doc.getElementsByTagNameNS(NS, "coordinates");
        for (int i = 0; i < coords.getLength(); i++) {
            for (String t : coords.item(i).getTextContent().trim().split("\\s+")) {
                String[] v = t.split(",");
                assertEquals(3, v.length, "lon,lat,alt with no space inside: " + t);
                assertTrue(Math.abs(Double.parseDouble(v[0])) <= 180 && Math.abs(Double.parseDouble(v[1])) <= 90);
            }
        }
        // the pin: longitude first, south-west kept for the drawings
        Element pin = (Element) doc.getElementsByTagNameNS(NS, "Placemark").item(6);
        assertEquals("10.7543210,59.9123456,0", pin.getElementsByTagNameNS(NS, "coordinates").item(0).getTextContent());
        Element area = (Element) doc.getElementsByTagNameNS(NS, "Placemark").item(0);
        assertTrue(area.getElementsByTagNameNS(NS, "coordinates").item(0).getTextContent().startsWith("-70.6600000,-33.4500000,0 "));
        NodeList lines = doc.getElementsByTagNameNS(NS, "LineString");
        for (int i = 0; i < lines.getLength(); i++) {
            assertTrue(((Element) lines.item(i)).getElementsByTagNameNS(NS, "coordinates").item(0).getTextContent().trim().split("\\s+").length >= 2);
        }
    }

    @Test
    void namesWithMarkupCharactersAndOtherScriptsComeBackAsTheyWere() throws Exception {
        Document doc = parse(sample());
        assertEquals(ODD, children(doc.getDocumentElement()).get(0).getElementsByTagNameNS(NS, "name").item(0).getTextContent(), "the document");
        NodeList marks = doc.getElementsByTagNameNS(NS, "Placemark");
        assertEquals(ODD, children(marks.item(0)).get(0).getTextContent(), "a drawing");
        assertEquals("Unit & co", children(marks.item(5)).get(0).getTextContent(), "a symbol");
        assertEquals(ODD, children(marks.item(6)).get(0).getTextContent(), "a pin");
        // a control character pasted into a name is left out, never written into the file as XML can't hold it
        String withControl = Kml.write(List.of(), List.of(new Pins.Pin("A\u0001B\u001fC", 59.9, 10.7)), "x");
        assertEquals("ABC", parse(withControl).getElementsByTagNameNS(NS, "Placemark").item(0).getFirstChild().getTextContent());
    }
}
