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
 * <p>
 * IMPORT takes what Google Earth, Google My Maps and QGIS write whole (I-015, 2026-11-24): a KMZ's KML, every part of
 * a MultiGeometry (nested ones too) as an item of its own, Google Earth's GPS tracks (gx:Track, gx:MultiTrack) as
 * lines, and shared styles (a styleUrl to a Style or StyleMap in the same file). What it still does not read - an
 * image laid on the ground or the screen, a network link, a tour, a 3D model, an area's holes, a shape with too few
 * points - is counted, and IMPORT says how many were skipped.
 */
public final class Kml {
    private Kml() {
    }

    /** What a file held: DRAUPNE's items, plain points as pins, and how many things in it were not read. */
    public record Parsed(List<MapGraphics.Item> items, List<Pins.Pin> pins, int skipped) {}

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
     * second (2026-11-22, a busy PC). For a KMZ the limit is on its KML as it unzips. 5 000 placemarks (pins,
     * drawings and symbols together; each part of a MultiGeometry or MultiTrack counts as one): a busy overlay has
     * tens to a few hundred, and every pin within 10 km is worked out for the live picture on each paint - 0.7 ms a
     * picture for 500 pins, 2.9 ms for 5 000, 6.3 ms for 10 000 (the same day). 100 elements deep: KML's own
     * nesting is about 10, folders in folders a few more.
     */
    public static final long MAX_IMPORT_BYTES = 10L << 20;
    public static final int MAX_IMPORT_PLACEMARKS = 5_000, MAX_DEPTH = 100;

    /**
     * IMPORT's read of a file, KML or KMZ (a zip: known by its first bytes, whatever its name): refused over
     * {@link #MAX_IMPORT_BYTES} before any more of it is taken in, over {@link #MAX_IMPORT_PLACEMARKS} or
     * {@link #MAX_DEPTH} as soon as the count passes, each with a short line as the exception's message. Not for the
     * Swing thread: a file at the limits takes a second or two.
     * @throws IllegalArgumentException over a limit, or not KML / KMZ
     */
    public static Parsed readImport(java.nio.file.Path file) throws java.io.IOException {
        if (isZip(file)) return read(kmlInKmz(file), MAX_IMPORT_PLACEMARKS);
        if (java.nio.file.Files.size(file) > MAX_IMPORT_BYTES) throw new IllegalArgumentException(TOO_BIG);
        byte[] bytes;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            bytes = in.readNBytes((int) MAX_IMPORT_BYTES + 1); // never more than the limit, should the file grow meanwhile
        }
        if (bytes.length > MAX_IMPORT_BYTES) throw new IllegalArgumentException(TOO_BIG);
        return read(bytes, MAX_IMPORT_PLACEMARKS);
    }

    private static final String TOO_BIG = "file over " + (MAX_IMPORT_BYTES >> 20) + " MB";

    /** A zip's first bytes, "PK" (a KML text starts with "<", white space or a byte order mark). */
    private static boolean isZip(java.nio.file.Path file) throws java.io.IOException {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] head = in.readNBytes(2);
            return head.length == 2 && head[0] == 'P' && head[1] == 'K';
        }
    }

    /**
     * A KMZ's KML: its doc.kml, else the first .kml in it, as Google Earth takes it. Only that one is unzipped, its
     * bytes counted as they come and refused past {@link #MAX_IMPORT_BYTES} (a zip can say anything of its own sizes,
     * and a small one can unpack to gigabytes); the pictures and other files beside it are left in the zip, nothing
     * is written to disk.
     */
    private static byte[] kmlInKmz(java.nio.file.Path file) throws java.io.IOException {
        // names as ISO-8859-1: any byte reads, so an old zip's names in another code page are no reason to refuse it
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile(), StandardCharsets.ISO_8859_1)) {
            java.util.zip.ZipEntry kml = null;
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                java.util.zip.ZipEntry entry = e.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || !name.endsWith(".kml")) continue;
                if (name.equals("doc.kml")) {
                    kml = entry;
                    break;
                }
                if (kml == null) kml = entry;
            }
            if (kml == null) throw new IllegalArgumentException("no KML in the KMZ");
            try (java.io.InputStream in = zip.getInputStream(kml)) {
                byte[] bytes = in.readNBytes((int) MAX_IMPORT_BYTES + 1);
                if (bytes.length > MAX_IMPORT_BYTES) throw new IllegalArgumentException("KML in the KMZ over " + (MAX_IMPORT_BYTES >> 20) + " MB");
                return bytes;
            }
        } catch (java.util.zip.ZipException | java.io.EOFException e) { // a broken zip, or one cut short
            throw new IllegalArgumentException("Not a KMZ file: " + e.getMessage(), e);
        }
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

    /** An element's name without its prefix: "gx:Track" is a Track. */
    private static String local(String tag) {
        return tag.substring(tag.indexOf(':') + 1);
    }

    /** What becomes an item or a pin of its own: one count towards {@link #MAX_IMPORT_PLACEMARKS}. */
    private static boolean isShape(String local) {
        return local.equals("Point") || local.equals("LineString") || local.equals("Polygon") || local.equals("LinearRing")
                || local.equals("Track");
    }

    /**
     * The file looked through once without keeping any of it: how deep it nests and how many placemarks (each part
     * of one counted) it has, ended at the first one over. The reading below walks the tree by recursion (a text's
     * parts, element by element), so a text nested deep enough ended in a StackOverflowError.
     */
    private static void checkLimits(byte[] xml, int maxPlacemarks) {
        try {
            javax.xml.parsers.SAXParserFactory f = javax.xml.parsers.SAXParserFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.newSAXParser().parse(new ByteArrayInputStream(xml), new org.xml.sax.helpers.DefaultHandler() {
                private int depth, count, partsHere, inPolygon;
                private boolean inPlacemark;

                @Override
                public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes) throws OverLimit {
                    if (++depth > MAX_DEPTH) throw new OverLimit("nested over " + MAX_DEPTH + " deep");
                    String local = local(qName);
                    if (local.equals("Placemark")) {
                        inPlacemark = true;
                        partsHere = 0;
                        counted(); // the placemark counts once, with its first part
                    } else if (inPlacemark && isShape(local) && !(local.equals("LinearRing") && inPolygon > 0) && ++partsHere > 1) {
                        counted();
                    }
                    if (local.equals("Polygon")) inPolygon++;
                }

                private void counted() throws OverLimit {
                    if (++count > maxPlacemarks) throw new OverLimit("over " + maxPlacemarks + " placemarks or parts");
                }

                @Override
                public void endElement(String uri, String localName, String qName) {
                    depth--;
                    String local = local(qName);
                    if (local.equals("Polygon")) inPolygon--;
                    if (local.equals("Placemark")) inPlacemark = false;
                }
            });
        } catch (OverLimit e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a KML file: " + e.getMessage(), e);
        }
    }

    /** What else a KML file can hold that IMPORT does not read: each one is said as skipped. */
    private static final List<String> NOT_READ = List.of("GroundOverlay", "ScreenOverlay", "PhotoOverlay", "NetworkLink", "gx:Tour");

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
        List<Element> marks = new ArrayList<>();
        java.util.Map<String, Element> styles = new java.util.HashMap<>();
        int skipped = walk(doc, marks, styles);
        for (Element mark : marks) {
            List<Element> parts = new ArrayList<>();
            int notRead = parts(mark, parts);
            if (parts.isEmpty()) {
                skipped += Math.max(1, notRead); // nothing on the map from it
                continue;
            }
            skipped += notRead;
            String name = text(first(mark, "name")), kindName = data(mark, "kind");
            Look look = null; // a pin has none: worked out for the first line or area only
            for (Element part : parts) {
                if (look == null && !local(part.getTagName()).equals("Point")) look = look(mark, styles);
                skipped += add(mark, name, kindName, part, look, items, pins);
            }
        }
        return new Parsed(items, pins, skipped);
    }

    /**
     * The file's elements looked through once, in order: its placemarks, its shared styles by id (for a styleUrl
     * "#id"), and the count of what else it holds that IMPORT does not read. Recursive: {@link #checkLimits} has seen
     * that it nests no deeper than {@link #MAX_DEPTH}.
     */
    private static int walk(Node under, List<Element> marks, java.util.Map<String, Element> styles) {
        int notRead = 0;
        for (Node n = under.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) continue;
            String tag = e.getTagName();
            if (tag.equals("Placemark")) {
                marks.add(e);
            } else if (tag.equals("Style") || tag.equals("StyleMap")) {
                if (!e.getAttribute("id").isEmpty()) styles.putIfAbsent(e.getAttribute("id"), e);
            } else if (NOT_READ.contains(tag)) {
                notRead++;
            } else {
                notRead += walk(e, marks, styles);
            }
        }
        return notRead;
    }

    /**
     * A placemark's shapes into parts, MultiGeometry and gx:MultiTrack opened (in them, in them too); what is a shape
     * but not read here (a 3D Model) is counted and returned.
     */
    private static int parts(Element under, List<Element> parts) {
        int notRead = 0;
        for (Node n = under.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) continue;
            String local = local(e.getTagName());
            if (local.equals("MultiGeometry") || local.equals("MultiTrack")) notRead += parts(e, parts);
            else if (isShape(local)) parts.add(e);
            else if (local.equals("Model")) notRead++;
        }
        return notRead;
    }

    /**
     * One part onto the items or pins, its name the placemark's; what of it was not read is returned: the part itself
     * when it has too few points, an area's holes.
     */
    private static int add(Element mark, String name, String kindName, Element part, Look look, List<MapGraphics.Item> items,
                           List<Pins.Pin> pins) {
        switch (local(part.getTagName())) {
            case "Point" -> {
                List<MapGraphics.LatLon> pts = coords(first(part, "coordinates"));
                if (pts.isEmpty()) return 1;
                if ("SYMBOL".equals(kindName)) {
                    items.add(MapGraphics.Item.symbol(name.isEmpty() ? "Symbol" : name, pts.get(0),
                            enumOf(MapGraphics.UnitType.class, data(mark, "unit"), MapGraphics.UnitType.UNIT),
                            enumOf(MapGraphics.Affiliation.class, data(mark, "affiliation"), MapGraphics.Affiliation.UNKNOWN),
                            enumOf(MapGraphics.Echelon.class, data(mark, "echelon"), null)));
                } else {
                    pins.add(new Pins.Pin(name.isEmpty() ? "Point" : name, pts.get(0).lat(), pts.get(0).lon()));
                }
                return 0;
            }
            case "Polygon" -> {
                Element outer = child(part, "outerBoundaryIs");
                List<MapGraphics.LatLon> pts = coords(first(outer != null ? outer : part, "coordinates"));
                if (pts.size() >= 2 && pts.get(0).equals(pts.get(pts.size() - 1))) pts = pts.subList(0, pts.size() - 1);
                if (pts.size() < 3) return 1;
                if ("MEASUREMENT".equals(kindName)) items.add(MapGraphics.Item.measurement(name.isEmpty() ? "Measurement" : name, pts, true));
                else items.add(MapGraphics.Item.area(name.isEmpty() ? "Area" : name, pts, look.color(true), look.width(), look.filled()));
                int holes = 0;
                for (Node n = part.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element e && local(e.getTagName()).equals("innerBoundaryIs")) holes += Math.max(1, e.getElementsByTagName("LinearRing").getLength());
                }
                return holes;
            }
            case "LinearRing" -> { // a ring on its own: drawn as its outline, a line that ends where it began
                List<MapGraphics.LatLon> pts = new ArrayList<>(coords(first(part, "coordinates")));
                if (pts.size() >= 2 && !pts.get(0).equals(pts.get(pts.size() - 1))) pts.add(pts.get(0));
                if (pts.size() < 3) return 1;
                items.add(MapGraphics.Item.line(name.isEmpty() ? "Line" : name, pts, look.color(false), look.width()));
                return 0;
            }
            case "Track" -> {
                List<MapGraphics.LatLon> pts = trackCoords(part);
                if (pts.size() < 2) return 1;
                items.add(MapGraphics.Item.line(name.isEmpty() ? "Track" : name, pts, look.color(false), look.width()));
                return 0;
            }
            default -> { // LineString
                List<MapGraphics.LatLon> pts = coords(first(part, "coordinates"));
                if (pts.size() < 2) return 1;
                items.add(switch (kindName == null ? "" : kindName) {
                    case "MEASUREMENT" -> MapGraphics.Item.measurement(name.isEmpty() ? "Measurement" : name, pts, false);
                    case "ARROW" -> MapGraphics.Item.arrow(name.isEmpty() ? "Arrow" : name, pts, look.color(false), look.width());
                    default -> MapGraphics.Item.line(name.isEmpty() ? "Line" : name, pts, look.color(false), look.width());
                });
                return 0;
            }
        }
    }

    /** A gx:Track's points: its gx:coord elements, "lon lat alt" each, in order. */
    private static List<MapGraphics.LatLon> trackCoords(Element track) {
        List<MapGraphics.LatLon> out = new ArrayList<>();
        for (Node n = track.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || !local(e.getTagName()).equals("coord")) continue;
            String[] v = text(e).split("[\\s,]+");
            if (v.length < 2) continue;
            try {
                double lon = Double.parseDouble(v[0]), lat = Double.parseDouble(v[1]);
                if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) out.add(new MapGraphics.LatLon(lat, lon));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * How a placemark's lines and areas look: its own Style first, what it does not say from the shared Style its
     * styleUrl names, then red, 3 px wide. DRAUPNE's own width and fill (ExtendedData) before either.
     */
    private record Look(String lineColor, String polyColor, int width, boolean filled) {
        /** 0xRRGGBB: the line's colour; an area takes its fill's when no line colour is given. */
        int color(boolean area) {
            int c = rgbOf(lineColor);
            if (c < 0 && area) c = rgbOf(polyColor);
            return c < 0 ? 0xe31b23 : c;
        }
    }

    private static Look look(Element mark, java.util.Map<String, Element> styles) {
        Element shared = sharedStyle(mark, styles);
        Element ownLine = first(mark, "LineStyle"), ownPoly = first(mark, "PolyStyle");
        Element sharedLine = first(shared, "LineStyle"), sharedPoly = first(shared, "PolyStyle");
        String fill = either(text(first(ownPoly, "fill")), text(first(sharedPoly, "fill")));
        int width = intOf(data(mark, "width"), intOf(either(text(first(ownLine, "width")), text(first(sharedLine, "width"))), 3));
        return new Look(either(text(first(ownLine, "color")), text(first(sharedLine, "color"))),
                either(text(first(ownPoly, "color")), text(first(sharedPoly, "color"))),
                width, "true".equals(data(mark, "filled")) || "1".equals(fill) || "true".equals(fill));
    }

    private static String either(String first, String second) {
        return first == null || first.isEmpty() ? second : first;
    }

    /**
     * The Style a placemark's styleUrl names in the same file ("#id"), or null: a StyleMap is followed to its normal
     * pair's Style (Google Earth's and My Maps' way: one look normal, one when the mouse is over it), a few steps at
     * most should one point back at another.
     */
    private static Element sharedStyle(Element mark, java.util.Map<String, Element> styles) {
        String url = text(child(mark, "styleUrl"));
        for (int step = 0; step < 4; step++) {
            if (!url.startsWith("#")) return null; // none, or in another file
            Element e = styles.get(url.substring(1));
            if (e == null) return null;
            if (e.getTagName().equals("Style")) return e;
            Element pair = null;
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element p && p.getTagName().equals("Pair") && (pair == null || "normal".equals(text(child(p, "key"))))) pair = p;
            }
            if (pair == null) return null;
            Element inline = child(pair, "Style");
            if (inline != null) return inline;
            url = text(child(pair, "styleUrl"));
        }
        return null;
    }

    /** The first child element of that tag (no deeper), or null. */
    private static Element child(Element under, String tag) {
        if (under == null) return null;
        for (Node n = under.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local(e.getTagName()).equals(tag)) return e;
        }
        return null;
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

    /**
     * "lon,lat,alt lon,lat,alt ..." (KML's order) to points. A space after a comma ("10.7, 59.9, 0"), which KML asks
     * not to have but hand-made files and some converters write, is taken as part of the tuple: split at it, every
     * point of such a line was dropped and the import said 0.
     */
    static List<MapGraphics.LatLon> coords(Element coordinates) {
        List<MapGraphics.LatLon> out = new ArrayList<>();
        if (coordinates == null) return out;
        for (String tuple : tightCommas(text(coordinates)).trim().split("\\s+")) {
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

    /**
     * The text with the white space on either side of each comma taken out, in one pass: a regular expression for it
     * takes the square of a long blank run's length, and a file of blanks within IMPORT's 10 MB would hold the import.
     */
    private static String tightCommas(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ',') {
                b.append(c);
                continue;
            }
            int end = b.length();
            while (end > 0 && Character.isWhitespace(b.charAt(end - 1))) end--;
            b.setLength(end);
            b.append(',');
            while (i + 1 < s.length() && Character.isWhitespace(s.charAt(i + 1))) i++;
        }
        return b.toString();
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
