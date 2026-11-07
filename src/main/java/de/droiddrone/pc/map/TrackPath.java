package de.droiddrone.pc.map;

import java.util.List;

/**
 * A flight's track as the map draws it (1.0.49): its points thinned to at most MAX_POINTS, in the map's own
 * coordinates (Mercator at zoom 0, 0..256 across the world), taken in as they come. Before, both maps turned every
 * point of the track into a screen point twice a paint, 30 times a second - a whole flight's, with no limit, on the
 * Swing thread that also shows the video. Thinning: a point closer than the spacing to the last one kept is left
 * out; when more than MAX_POINTS are kept, the spacing doubles and the kept ones are thinned again (a long flight is
 * drawn a little coarser - never more than the spacing off). The newest point is always the line's end, and the
 * newest RECENT points are kept as they came, for the dots the map shows each report as when zoomed in.
 * Not thread-safe: the Swing thread's.
 */
public final class TrackPath {
    public static final int MAX_POINTS = 4000;
    public static final int RECENT = 3000;
    /** The first spacing: about 1.5 cm on the ground (every point kept until there are too many). */
    static final double FIRST_SPACING = 1e-7;

    private final int maxPoints;
    private double[] xs = new double[256], ys = new double[256];
    private int count;
    private double spacing = FIRST_SPACING;
    private final double[] recentX = new double[RECENT], recentY = new double[RECENT];
    private int recentCount, recentAt;
    private double lastX, lastY;
    private boolean any;
    /** How many of the track's points have been taken in. */
    private int taken;

    public TrackPath() {
        this(MAX_POINTS);
    }

    TrackPath(int maxPoints) {
        this.maxPoints = maxPoints;
    }

    /** A whole track at once (an earlier flight's). */
    public static TrackPath of(List<FlightTrack.Point> points) {
        TrackPath p = new TrackPath();
        p.add(points);
        return p;
    }

    /** The track's points from {@link #taken()} on (it only grows). */
    public void add(List<FlightTrack.Point> points) {
        for (FlightTrack.Point p : points) add(Mercator.x(p.lon(), 0), Mercator.y(p.lat(), 0));
    }

    private void add(double x, double y) {
        taken++;
        lastX = x;
        lastY = y;
        any = true;
        recentX[recentAt] = x;
        recentY[recentAt] = y;
        recentAt = (recentAt + 1) % RECENT;
        if (recentCount < RECENT) recentCount++;
        if (count > 0 && Math.hypot(x - xs[count - 1], y - ys[count - 1]) < spacing) return;
        if (count == xs.length) {
            xs = java.util.Arrays.copyOf(xs, count * 2);
            ys = java.util.Arrays.copyOf(ys, count * 2);
        }
        xs[count] = x;
        ys[count] = y;
        count++;
        while (count > maxPoints) thin();
    }

    /** The spacing doubles; the kept points are thinned to it (the first stays). */
    private void thin() {
        spacing *= 2;
        int kept = 1;
        for (int i = 1; i < count; i++) {
            if (Math.hypot(xs[i] - xs[kept - 1], ys[i] - ys[kept - 1]) < spacing) continue;
            xs[kept] = xs[i];
            ys[kept] = ys[i];
            kept++;
        }
        count = kept;
    }

    public int taken() {
        return taken;
    }

    /** The points of the line: the kept ones, then the newest if it wasn't kept. */
    public int size() {
        return count + (endsApart() ? 1 : 0);
    }

    public double x(int i) {
        return i < count ? xs[i] : lastX;
    }

    public double y(int i) {
        return i < count ? ys[i] : lastY;
    }

    private boolean endsApart() {
        return any && count > 0 && (xs[count - 1] != lastX || ys[count - 1] != lastY);
    }

    /** The newest points as they came (at most RECENT), oldest first. */
    public int recentSize() {
        return recentCount;
    }

    public double recentX(int i) {
        return recentX[(recentAt - recentCount + i + RECENT) % RECENT];
    }

    public double recentY(int i) {
        return recentY[(recentAt - recentCount + i + RECENT) % RECENT];
    }

    /** The spacing the points are kept at now, in zoom-0 map units (tests). */
    double spacing() {
        return spacing;
    }
}
