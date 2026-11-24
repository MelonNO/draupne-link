package de.droiddrone.pc.map;

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
import java.util.Locale;

/**
 * KML for the map's graphics and pins (1.0.35): what DRAUPNE writes, Google Earth and ATAK read - a line is a
 * LineString, an area a Polygon, a symbol or pin a Point - and what they write, DRAUPNE reads back. DRAUPNE's own
 * details (the kind, the width, a symbol's unit, affiliation and echelon) ride in ExtendedData, which the others
 * keep but ignore. A Point without them is a pin.
 */
public final class Kml {
    private Kml() {
    }

    /** What a file held: DRAUPNE's items, and plain points as pins. */
    public record Parsed(List<MapGraphics.Item> items, List<Pins.Pin> pins) {}

    public static String write(List<MapGraphics.Item> items, List<Pins.Pin> pins, String name) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n<name>" + xml(name) + "</name>\n");
        for (MapGraphics.Item it : items) placemark(sb, it);
        for (Pins.Pin p : pins) {
            sb.append("<Placemark><name>").append(xml(p.name())).append("</name><Point><coordinates>")
                    .append(coord(p.lat(), p.lon())).append("</coordinates></Point></Placemark>\n");
        }
        return sb.append("</Document>\n</kml>\n").toString();
    }

    /**
     * One drawing: name, Style, ExtendedData, then the geometry - KML 2.2's order for a Feature (a Style before the
     * ExtendedData; before, the other way round, which a reader that holds to the schema refuses).
     */
    private static void placemark(StringBuilder sb, MapGraphics.Item it) {
        sb.append("<Placemark><name>").append(xml(it.name())).append("</name>\n");
        if (it.kind() != MapGraphics.Kind.SYMBOL) {
            sb.append("<Style><LineStyle><color>").append(abgr(it.colorRgb(), 255)).append("</color><width>").append(it.widthPx())
                    .append("</width></LineStyle><PolyStyle><color>").append(abgr(it.colorRgb(), it.filled() ? 90 : 0))
                    .append("</color><fill>").append(it.filled() ? 1 : 0).append("</fill></PolyStyle></Style>\n");
        }
        sb.append("<ExtendedData>");
        data(sb, "kind", it.kind().name());
        data(sb, "width", Integer.toString(it.widthPx()));
        data(sb, "filled", Boolean.toString(it.filled()));
        data(sb, "closed", Boolean.toString(it.closed()));
        if (it.unit() != null) data(sb, "unit", it.unit().name());
        if (it.affiliation() != null) data(sb, "affiliation", it.affiliation().name());
        if (it.echelon() != null) data(sb, "echelon", it.echelon().name());
        sb.append("</ExtendedData>\n");
        boolean ring = it.kind() == MapGraphics.Kind.AREA || (it.kind() == MapGraphics.Kind.MEASUREMENT && it.closed());
        if (it.kind() == MapGraphics.Kind.SYMBOL) {
            MapGraphics.LatLon p = it.points().get(0);
            sb.append("<Point><coordinates>").append(coord(p.lat(), p.lon())).append("</coordinates></Point>\n");
        } else if (ring) {
            sb.append("<Polygon><outerBoundaryIs><LinearRing><coordinates>");
            for (MapGraphics.LatLon p : it.points()) sb.append(coord(p.lat(), p.lon())).append(' ');
            MapGraphics.LatLon first = it.points().get(0);
            sb.append(coord(first.lat(), first.lon())).append("</coordinates></LinearRing></outerBoundaryIs></Polygon>\n");
        } else {
            sb.append("<LineString><tessellate>1</tessellate><coordinates>");
            for (MapGraphics.LatLon p : it.points()) sb.append(coord(p.lat(), p.lon())).append(' ');
            sb.setLength(sb.length() - 1);
            sb.append("</coordinates></LineString>\n");
        }
        sb.append("</Placemark>\n");
    }

    private static void data(StringBuilder sb, String name, String value) {
        sb.append("<Data name=\"").append(name).append("\"><value>").append(xml(value)).append("</value></Data>");
    }

    private static String coord(double lat, double lon) {
        return String.format(Locale.ROOT, "%.7f,%.7f,0", lon, lat);
    }

    /** KML's colour order: aabbggrr. */
    static String abgr(int rgb, int alpha) {
        return String.format(Locale.ROOT, "%02x%02x%02x%02x", alpha, rgb & 0xff, (rgb >> 8) & 0xff, (rgb >> 16) & 0xff);
    }

    /** KML's aabbggrr back to 0xRRGGBB; -1 for a bad one. */
    static int rgbOf(String abgr) {
        String s = abgr == null ? "" : abgr.trim();
        if (s.length() != 8) return -1;
        try {
            int b = Integer.parseInt(s.substring(2, 4), 16), g = Integer.parseInt(s.substring(4, 6), 16), r = Integer.parseInt(s.substring(6, 8), 16);
            return (r << 16) | (g << 8) | b;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Text for XML: escaped, and the characters XML 1.0 can't hold left out (a pasted control character made the file unreadable). */
    static String xml(String text) {
        return text == null ? "" : text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]", "")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * What IMPORT takes of one file. 10 MB: the app writes some 110 bytes a pin and 0.5 KB a drawing plus 22 bytes a
     * point, so its own export at the placemark limit is under 1 MB, and another program's with styles and
     * descriptions a few times that; read whole and held as a tree, a 9 MB file took 45 MB of memory and half a
     * second (2026-11-22, a busy PC). 5 000 placemarks (pins, drawings and symbols together): a busy overlay has tens
     * to a few hundred, and every pin within 10 km is worked out for the live picture on each paint - 0.7 ms a
     * picture for 500 pins, 2.9 ms for 5 000, 6.3 ms for 10 000 (the same day). 100 elements deep: KML's own
     * nesting is about 10, folders in folders a few more.
     */
    public static final long MAX_IMPORT_BYTES = 10L << 20;
    public static final int MAX_IMPORT_PLACEMARKS = 5_000, MAX_DEPTH = 100;

    /**
     * IMPORT's read of a file: refused over {@link #MAX_IMPORT_BYTES} before any of it is taken in, over
     * {@link #MAX_IMPORT_PLACEMARKS} or {@link #MAX_DEPTH} as soon as the count passes, each with a short line as the
     * exception's message. Not for the Swing thread: a file at the limits takes a second or two.
     * @throws IllegalArgumentException over a limit, or not KML
     */
    public static Parsed readImport(java.nio.file.Path file) throws java.io.IOException {
        String tooBig = "file over " + (MAX_IMPORT_BYTES >> 20) + " MB";
        if (java.nio.file.Files.size(file) > MAX_IMPORT_BYTES) throw new IllegalArgumentException(tooBig);
        byte[] bytes;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            bytes = in.readNBytes((int) MAX_IMPORT_BYTES + 1); // never more than the limit, should the file grow meanwhile
        }
        if (bytes.length > MAX_IMPORT_BYTES) throw new IllegalArgumentException(tooBig);
        return read(bytes, MAX_IMPORT_PLACEMARKS);
    }

    /**
     * Reads a KML text: DRAUPNE's items by their ExtendedData, anybody's lines, polygons and points otherwise. Any
     * count of placemarks (the app's own drawings file grows with every import); refused when nested deeper than
     * {@link #MAX_DEPTH}.
     */
    public static Parsed read(String kml) {
        return read(kml.getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE);
    }

    /** A limit passed while the file is looked through: the look ends there. */
    private static final class OverLimit extends org.xml.sax.SAXException {
        OverLimit(String line) {
            super(line);
        }
    }

    /**
     * The file looked through once without keeping any of it: how deep it nests and how many placemarks it has,
     * ended at the first one over. The reading below walks the tree by recursion (a text's parts, element by
     * element), so a text nested deep enough ended in a StackOverflowError.
     */
    private static void checkLimits(byte[] xml, int maxPlacemarks) {
        try {
            javax.xml.parsers.SAXParserFactory f = javax.xml.parsers.SAXParserFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.newSAXParser().parse(new ByteArrayInputStream(xml), new org.xml.sax.helpers.DefaultHandler() {
                private int depth, placemarks;

                @Override
                public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes) throws OverLimit {
                    if (++depth > MAX_DEPTH) throw new OverLimit("nested over " + MAX_DEPTH + " deep");
                    if ("Placemark".equals(qName) && ++placemarks > maxPlacemarks) throw new OverLimit("over " + maxPlacemarks + " placemarks");
                }

                @Override
                public void endElement(String uri, String localName, String qName) {
                    depth--;
                }
            });
        } catch (OverLimit e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a KML file: " + e.getMessage(), e);
        }
    }

    private static Parsed read(byte[] xml, int maxPlacemarks) {
        checkLimits(xml, maxPlacemarks);
        List<MapGraphics.Item> items = new ArrayList<>();
        List<Pins.Pin> pins = new ArrayList<>();
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a KML file: " + e.getMessage(), e);
        }
        NodeList marks = doc.getElementsByTagName("Placemark");
        for (int i = 0; i < marks.getLength(); i++) {
            Element mark = (Element) marks.item(i);
            String name = text(first(mark, "name"));
            String kindName = data(mark, "kind");
            Element point = first(mark, "Point"), line = first(mark, "LineString"), polygon = first(mark, "Polygon");
            if (point != null) {
                List<MapGraphics.LatLon> pts = coords(first(point, "coordinates"));
                if (pts.isEmpty()) continue;
                if ("SYMBOL".equals(kindName)) {
                    items.add(MapGraphics.Item.symbol(name.isEmpty() ? "Symbol" : name, pts.get(0),
                            enumOf(MapGraphics.UnitType.class, data(mark, "unit"), MapGraphics.UnitType.UNIT),
                            enumOf(MapGraphics.Affiliation.class, data(mark, "affiliation"), MapGraphics.Affiliation.UNKNOWN),
                            enumOf(MapGraphics.Echelon.class, data(mark, "echelon"), null)));
                } else {
                    pins.add(new Pins.Pin(name.isEmpty() ? "Point" : name, pts.get(0).lat(), pts.get(0).lon()));
                }
                continue;
            }
            int color = rgbOf(text(first(first(mark, "LineStyle"), "color")));
            if (color < 0) color = 0xe31b23;
            int width = intOf(data(mark, "width"), intOf(text(first(first(mark, "LineStyle"), "width")), 3));
            boolean filled = "true".equals(data(mark, "filled")) || "1".equals(text(first(first(mark, "PolyStyle"), "fill")));
            if (polygon != null) {
                List<MapGraphics.LatLon> pts = coords(first(polygon, "coordinates"));
                if (pts.size() >= 2 && pts.get(0).equals(pts.get(pts.size() - 1))) pts = pts.subList(0, pts.size() - 1);
                if (pts.size() < 3) continue;
                if ("MEASUREMENT".equals(kindName)) items.add(MapGraphics.Item.measurement(name.isEmpty() ? "Measurement" : name, pts, true));
                else items.add(MapGraphics.Item.area(name.isEmpty() ? "Area" : name, pts, color, width, filled));
            } else if (line != null) {
                List<MapGraphics.LatLon> pts = coords(first(line, "coordinates"));
                if (pts.size() < 2) continue;
                MapGraphics.Item item = switch (kindName == null ? "" : kindName) {
                    case "MEASUREMENT" -> MapGraphics.Item.measurement(name.isEmpty() ? "Measurement" : name, pts, false);
                    case "ARROW" -> MapGraphics.Item.arrow(name.isEmpty() ? "Arrow" : name, pts, color, width);
                    default -> MapGraphics.Item.line(name.isEmpty() ? "Line" : name, pts, color, width);
                };
                items.add(item);
            }
        }
        return new Parsed(items, pins);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int intOf(String s, int fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Math.max(1, Math.min(12, (int) Math.round(Double.parseDouble(s.trim()))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "lon,lat,alt lon,lat,alt ..." (KML's order) to points. */
    static List<MapGraphics.LatLon> coords(Element coordinates) {
        List<MapGraphics.LatLon> out = new ArrayList<>();
        if (coordinates == null) return out;
        for (String tuple : text(coordinates).trim().split("\\s+")) {
            String[] p = tuple.split(",");
            if (p.length < 2) continue;
            try {
                double lon = Double.parseDouble(p[0]), lat = Double.parseDouble(p[1]);
                if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) out.add(new MapGraphics.LatLon(lat, lon));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** The value of {@code <Data name="...">} in the placemark's ExtendedData, or null. */
    private static String data(Element mark, String name) {
        Element ext = first(mark, "ExtendedData");
        if (ext == null) return null;
        NodeList all = ext.getElementsByTagName("Data");
        for (int i = 0; i < all.getLength(); i++) {
            Element d = (Element) all.item(i);
            if (name.equals(d.getAttribute("name"))) return text(first(d, "value"));
        }
        return null;
    }

    /** The first element of that tag under a node (any depth), or null. */
    private static Element first(Element under, String tag) {
        if (under == null) return null;
        NodeList all = under.getElementsByTagName(tag);
        return all.getLength() == 0 ? null : (Element) all.item(0);
    }

    private static String text(Node node) {
        return node == null ? "" : node.getTextContent() == null ? "" : node.getTextContent().trim();
    }
}
