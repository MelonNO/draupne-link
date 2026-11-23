package de.droiddrone.pc.route;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Fleet PRD RT-1 / RT-15: a route the pilot draws on the map - numbered points (1, 2, ... as shown), each with an
 * optional height of its own, and the route's settings saved with it: the height above ground (starts at
 * {@link #DEFAULT_HEIGHT_M}, PRD B1) and the smoothing (how far above that height the line may run to save
 * waypoints, {@link #DEFAULT_SMOOTHING_M}). Heights are above bare ground (RT-6). Not thread-safe: the map's thread
 * edits it; the planner gets a {@link #copy()}.
 */
public final class Route {
    public static final double DEFAULT_HEIGHT_M = 60;
    public static final double DEFAULT_SMOOTHING_M = 5;
    /** No route is planned lower than this over the ground (the follower floor's own lower limit, PRD FL-14). */
    public static final double MIN_HEIGHT_M = 10;

    /** One point: lat / lon in degrees, heightM its own height above ground or NaN (the route's). */
    public record Point(double lat, double lon, double heightM) {
        public Point(double lat, double lon) {
            this(lat, lon, Double.NaN);
        }

        public boolean hasOwnHeight() {
            return !Double.isNaN(heightM);
        }
    }

    private String name;
    private double heightM = DEFAULT_HEIGHT_M;
    private double smoothingM = DEFAULT_SMOOTHING_M;
    private final List<Point> points = new ArrayList<>();

    public Route(String name) {
        this.name = name == null || name.isBlank() ? "ROUTE" : name.trim();
    }

    /** "Fly to here": a one-point route; the drone flies to it from where it is (RT-1). */
    public static Route flyToHere(double lat, double lon) {
        Route r = new Route("FLY TO HERE");
        r.add(lat, lon);
        return r;
    }

    public Route copy() {
        Route r = new Route(name);
        r.heightM = heightM;
        r.smoothingM = smoothingM;
        r.points.addAll(points);
        return r;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) this.name = name.trim();
    }

    public double heightM() {
        return heightM;
    }

    /** The route's height above ground, at least {@link #MIN_HEIGHT_M}. */
    public void setHeightM(double h) {
        if (!Double.isFinite(h)) return;
        heightM = Math.max(MIN_HEIGHT_M, h);
    }

    public double smoothingM() {
        return smoothingM;
    }

    public void setSmoothingM(double s) {
        if (!Double.isFinite(s)) return;
        smoothingM = Math.max(0, s);
    }

    public List<Point> points() {
        return Collections.unmodifiableList(points);
    }

    public int size() {
        return points.size();
    }

    public Point point(int index) {
        return points.get(index);
    }

    /** The height above ground at point index: its own, else the route's. */
    public double heightAt(int index) {
        Point p = points.get(index);
        return p.hasOwnHeight() ? p.heightM() : heightM;
    }

    public void add(double lat, double lon) {
        points.add(new Point(lat, lon));
    }

    /** Before point index (0 = first); index == size() adds at the end. */
    public void insert(int index, double lat, double lon) {
        points.add(index, new Point(lat, lon));
    }

    public void move(int index, double lat, double lon) {
        Point p = points.get(index);
        points.set(index, new Point(lat, lon, p.heightM()));
    }

    public void delete(int index) {
        points.remove(index);
    }

    /** The point's own height above ground; NaN gives it the route's again. At least {@link #MIN_HEIGHT_M}. */
    public void setOwnHeight(int index, double h) {
        Point p = points.get(index);
        if (Double.isInfinite(h)) return;
        points.set(index, new Point(p.lat(), p.lon(), Double.isNaN(h) ? Double.NaN : Math.max(MIN_HEIGHT_M, h)));
    }

    public void reverse() {
        Collections.reverse(points);
    }

    // --- the file: plain text, one setting or point a line ---

    static final String MAGIC = "draupne-route 1";

    public String toText() {
        StringBuilder b = new StringBuilder(MAGIC).append('\n');
        b.append("name ").append(name.replace('\n', ' ')).append('\n');
        b.append(String.format(Locale.ROOT, "height %.1f%n", heightM));
        b.append(String.format(Locale.ROOT, "smoothing %.1f%n", smoothingM));
        for (Point p : points) {
            b.append(String.format(Locale.ROOT, "point %.7f %.7f %s%n", p.lat(), p.lon(),
                    p.hasOwnHeight() ? String.format(Locale.ROOT, "%.1f", p.heightM()) : "-"));
        }
        return b.toString();
    }

    /** {@link #toText()} read back; IllegalArgumentException for anything that is not a route. */
    public static Route fromText(String text) {
        String[] lines = text.replace("\r", "").split("\n");
        if (lines.length == 0 || !lines[0].trim().equals(MAGIC)) throw new IllegalArgumentException("not a DRAUPNE route");
        Route r = new Route("ROUTE");
        try {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("name ")) {
                    r.setName(line.substring(5));
                } else if (line.startsWith("height ")) {
                    r.setHeightM(Double.parseDouble(line.substring(7).trim()));
                } else if (line.startsWith("smoothing ")) {
                    r.setSmoothingM(Double.parseDouble(line.substring(10).trim()));
                } else if (line.startsWith("point ")) {
                    String[] f = line.substring(6).trim().split("\\s+");
                    double lat = Double.parseDouble(f[0]), lon = Double.parseDouble(f[1]);
                    // NaN passes every comparison: "point NaN 10" was a point nowhere
                    if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) throw new IllegalArgumentException("point off the globe: " + line);
                    double own = f.length > 2 && !f[2].equals("-") ? Double.parseDouble(f[2]) : Double.NaN;
                    if (Double.isInfinite(own)) throw new IllegalArgumentException("height off the scale: " + line);
                    r.points.add(new Point(lat, lon, Double.NaN));
                    r.setOwnHeight(r.points.size() - 1, own); // never under MIN_HEIGHT_M, as one set on the map (a file said 2 m)
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("broken route file: " + e.getMessage());
        }
        return r;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(tmp, toText(), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static Route load(Path file) throws IOException {
        return fromText(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Where routes are kept: %APPDATA%\DraupneLink\routes. */
    public static Path folder() {
        return de.droiddrone.pc.AppPaths.dataDir().resolve("routes");
    }
}
