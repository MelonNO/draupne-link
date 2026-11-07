package de.droiddrone.pc.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the pilot draws on the map (1.0.35, MAP TOOLS): measurements, lines, areas, arrows and NATO unit symbols.
 * Kept between sessions in Documents\DRAUPNE Link\graphics.kml - KML is the file format too (EXPORT / IMPORT), so
 * Google Earth and ATAK read what the pilot drew and DRAUPNE reads it back. Thread-safe like {@link Pins}; the map
 * window and the minimap share one of these and repaint on {@link #onChange}.
 */
public final class MapGraphics {
    /** What an item is: the tool that made it. */
    public enum Kind {
        MEASUREMENT("Measurement"), LINE("Line"), AREA("Area"), ARROW("Arrow"), SYMBOL("Symbol");

        public final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    /** APP-6 / MIL-STD-2525 affiliation: the frame's shape and colour. */
    public enum Affiliation {
        FRIEND("Friend", 0x80e0ff), HOSTILE("Hostile", 0xff8080), NEUTRAL("Neutral", 0xaaffaa), UNKNOWN("Unknown", 0xffff80);

        public final String title;
        /** The frame's fill, the standard's light colour. */
        public final int fillRgb;

        Affiliation(String title, int fillRgb) {
            this.title = title;
            this.fillRgb = fillRgb;
        }
    }

    /** The unit type: the icon inside the frame. */
    public enum UnitType {
        INFANTRY("Infantry"), MECHANIZED("Mechanised infantry"), ARMOUR("Armour"), ARTILLERY("Artillery"), MORTAR("Mortar"),
        ROCKET_ARTILLERY("Rocket artillery"), RECON("Reconnaissance"), ANTI_TANK("Anti-tank"), ENGINEER("Engineer"), AIR_DEFENCE("Air defence"),
        ISR_FIXED("ISR drone, fixed-wing"), ATTACK_FIXED("Attack drone, fixed-wing"), ISR_ROTOR("ISR drone, multirotor"),
        ATTACK_ROTOR("Attack drone, multirotor"),
        /** Recovery and maintenance units (a land unit; 1.0.35 - 1.0.38 drew it as a utility drone). The name stays for saved graphics. */
        UTILITY("Recovery / maintenance"), SUPPLY("Supply"), MEDIC("Medical"),
        HQ("Headquarters"), UNIT("Unit");

        public final String title;

        UnitType(String title) {
            this.title = title;
        }
    }

    /** The echelon (the standard's size marks above the frame). */
    public enum Echelon {
        TEAM("Team", "Ø"), SQUAD("Squad", "●"), SECTION("Section", "●●"), PLATOON("Platoon", "●●●"),
        COMPANY("Company", "|"), BATTALION("Battalion", "||"), REGIMENT("Regiment", "|||"), BRIGADE("Brigade", "X"), DIVISION("Division", "XX");

        public final String title;
        /** The mark drawn above the frame. */
        public final String mark;

        Echelon(String title, String mark) {
            this.title = title;
            this.mark = mark;
        }
    }

    public record LatLon(double lat, double lon) {}

    /**
     * One item. Points: a measurement's, line's or arrow's vertices in order; an area's corners (the last joins the
     * first); a symbol's one place. The colour is 0xRRGGBB; width in pixels; filled = a see-through fill for an area;
     * closed = a measurement that ends where it began (its area counts). Unit, affiliation and echelon are a symbol's.
     */
    public record Item(Kind kind, String name, List<LatLon> points, int colorRgb, int widthPx, boolean filled, boolean closed,
                       UnitType unit, Affiliation affiliation, Echelon echelon) {
        public Item {
            points = List.copyOf(points);
        }

        public static Item measurement(String name, List<LatLon> points, boolean closed) {
            return new Item(Kind.MEASUREMENT, name, points, 0xe31b23, 2, false, closed, null, null, null);
        }

        public static Item line(String name, List<LatLon> points, int colorRgb, int widthPx) {
            return new Item(Kind.LINE, name, points, colorRgb, widthPx, false, false, null, null, null);
        }

        public static Item arrow(String name, List<LatLon> points, int colorRgb, int widthPx) {
            return new Item(Kind.ARROW, name, points, colorRgb, widthPx, false, false, null, null, null);
        }

        public static Item area(String name, List<LatLon> corners, int colorRgb, int widthPx, boolean filled) {
            return new Item(Kind.AREA, name, corners, colorRgb, widthPx, filled, true, null, null, null);
        }

        public static Item symbol(String name, LatLon at, UnitType unit, Affiliation affiliation, Echelon echelon) {
            return new Item(Kind.SYMBOL, name, List.of(at), 0, 0, false, false, unit, affiliation, echelon);
        }

        public Item withName(String newName) {
            return new Item(kind, newName, points, colorRgb, widthPx, filled, closed, unit, affiliation, echelon);
        }

        public Item withPoints(List<LatLon> newPoints) {
            return new Item(kind, name, newPoints, colorRgb, widthPx, filled, closed, unit, affiliation, echelon);
        }

        /** The legs' lengths in metres, in order (for an area and a closed measurement the closing leg too). */
        public double[] legsM() {
            int n = points.size();
            if (n < 2) return new double[0];
            boolean around = closed || kind == Kind.AREA;
            double[] legs = new double[around ? n : n - 1];
            for (int i = 0; i < legs.length; i++) {
                LatLon a = points.get(i), b = points.get((i + 1) % n);
                legs[i] = Mercator.distanceM(a.lat(), a.lon(), b.lat(), b.lon());
            }
            return legs;
        }

        /** The whole length in metres: the legs added up. */
        public double lengthM() {
            double sum = 0;
            for (double leg : legsM()) sum += leg;
            return sum;
        }

        /**
         * The enclosed area in square metres for an area or a closed measurement (0 otherwise): the shoelace formula
         * on a local flat projection, exact enough for anything a drone flies round.
         */
        public double areaM2() {
            boolean around = closed || kind == Kind.AREA;
            int n = points.size();
            if (!around || n < 3) return 0;
            double lat0 = 0;
            for (LatLon p : points) lat0 += p.lat();
            lat0 /= n;
            double mPerDegLat = 111_320, mPerDegLon = 111_320 * Math.cos(Math.toRadians(lat0));
            double sum = 0;
            for (int i = 0; i < n; i++) {
                LatLon a = points.get(i), b = points.get((i + 1) % n);
                double ax = a.lon() * mPerDegLon, ay = a.lat() * mPerDegLat, bx = b.lon() * mPerDegLon, by = b.lat() * mPerDegLat;
                sum += ax * by - bx * ay;
            }
            return Math.abs(sum) / 2;
        }

        /** The smallest box round the item: south, north, west, east. */
        public double[] bounds() {
            double s = 90, n = -90, w = 180, e = -180;
            for (LatLon p : points) {
                s = Math.min(s, p.lat());
                n = Math.max(n, p.lat());
                w = Math.min(w, p.lon());
                e = Math.max(e, p.lon());
            }
            return new double[]{s, n, w, e};
        }
    }

    private final Path file;
    private final List<Item> items = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** @param file where they are kept (KML); null keeps them in memory only */
    public MapGraphics(Path file) {
        this.file = file;
        if (file != null && Files.isRegularFile(file)) {
            try {
                items.addAll(Kml.read(Files.readString(file, StandardCharsets.UTF_8)).items());
            } catch (IOException | RuntimeException e) {
                // a broken file (1.0.49, M12): moved aside whole before anything is written over it, so what it held
                // can still be had by hand; this session starts empty
                brokenMovedTo = moveAside(file);
                keepOff = brokenMovedTo == null; // it couldn't be moved: never written over
            }
        }
    }

    private Path brokenMovedTo;
    private boolean keepOff;

    /** Where a file that couldn't be read was moved at start (name.broken-yyyyMMdd-HHmmss), or null. */
    public Path brokenMovedTo() {
        return brokenMovedTo;
    }

    /**
     * Moves a file that couldn't be read out of the way: renamed to name.broken-yyyyMMdd-HHmmss beside it (-2, -3 ...
     * when that is taken). Null when it couldn't be moved - then the caller must not write over it either.
     */
    public static Path moveAside(Path file) {
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.ROOT));
        Path target = file.resolveSibling(file.getFileName() + ".broken-" + stamp);
        for (int n = 2; Files.exists(target); n++) target = file.resolveSibling(file.getFileName() + ".broken-" + stamp + "-" + n);
        try {
            return Files.move(file, target);
        } catch (IOException e) {
            return null;
        }
    }

    public static Path defaultFile() throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("user.home"), "Documents", "DRAUPNE Link")).resolve("graphics.kml");
    }

    /** Called (on the caller's thread) after every change: the maps repaint. */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    public synchronized List<Item> list() {
        return new ArrayList<>(items);
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    /** "Line 3", "Infantry 2": the kind (a symbol's unit type) and the first free number. */
    public synchronized String nextName(Kind kind, UnitType unit) {
        String base = kind == Kind.SYMBOL && unit != null ? unit.title : kind.title;
        for (int i = 1; ; i++) {
            String name = base + " " + i;
            boolean taken = false;
            for (Item it : items) if (it.name().equalsIgnoreCase(name)) taken = true;
            if (!taken) return name;
        }
    }

    public Item add(Item item) {
        synchronized (this) {
            items.add(item);
            save();
        }
        changed();
        return item;
    }

    public void addAll(List<Item> more) {
        synchronized (this) {
            items.addAll(more);
            save();
        }
        changed();
    }

    public Item rename(Item item, String name) {
        Item renamed;
        synchronized (this) {
            int i = items.indexOf(item);
            if (i < 0 || name == null || name.isBlank()) return item;
            renamed = item.withName(name.trim());
            items.set(i, renamed);
            save();
        }
        changed();
        return renamed;
    }

    /** The same item with new points (a corner dragged): kept in place. */
    public Item replace(Item item, Item with) {
        synchronized (this) {
            int i = items.indexOf(item);
            if (i < 0) return item;
            items.set(i, with);
            save();
        }
        changed();
        return with;
    }

    public void remove(Item item) {
        boolean removed;
        synchronized (this) {
            removed = items.remove(item);
            if (removed) save();
        }
        if (removed) changed();
    }

    public void clear() {
        synchronized (this) {
            if (items.isEmpty()) return;
            items.clear();
            save();
        }
        changed();
    }

    /** The items with any point inside a box (south, north, west, east): what an EXPORT of that box takes. */
    public synchronized List<Item> inside(double south, double north, double west, double east) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            for (LatLon p : it.points()) {
                if (p.lat() >= south && p.lat() <= north && p.lon() >= west && p.lon() <= east) {
                    out.add(it);
                    break;
                }
            }
        }
        return out;
    }

    private void changed() {
        for (Runnable r : listeners) r.run();
    }

    private void save() {
        if (file == null || keepOff) return;
        try {
            writeKml(file, Kml.write(items, List.of(), "DRAUPNE Link graphics"));
        } catch (IOException ignored) {
            // they stay in memory for this session
        }
    }

    /** Writes a KML text whole, then moves it into place (never a half file). */
    public static void writeKml(Path target, String kml) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(tmp, kml, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** "245 M" / "1.24 KM", as the map writes distances. */
    public static String distanceText(double m) {
        return m >= 1000 ? String.format(Locale.ROOT, "%.2f KM", m / 1000) : String.format(Locale.ROOT, "%.0f M", m);
    }

    /** "1 250 M2" under a hectare, "2.4 HA" under a square kilometre, "1.25 KM2" from there. */
    public static String areaText(double m2) {
        if (m2 >= 1_000_000) return String.format(Locale.ROOT, "%.2f KM2", m2 / 1_000_000);
        if (m2 >= 10_000) return String.format(Locale.ROOT, "%.1f HA", m2 / 10_000);
        return String.format(Locale.ROOT, "%,.0f M2", m2).replace(',', ' ');
    }
}
