package de.droiddrone.pc.air;

/** Metres east and north, in a group's or the airspace's flat frame. */
public record V2(double e, double n) {
    public static final V2 ZERO = new V2(0, 0);

    public V2 plus(V2 o) {
        return new V2(e + o.e, n + o.n);
    }

    public V2 minus(V2 o) {
        return new V2(e - o.e, n - o.n);
    }

    public V2 times(double k) {
        return new V2(e * k, n * k);
    }

    public double length() {
        return Math.hypot(e, n);
    }

    public double dist(V2 o) {
        return Math.hypot(e - o.e, n - o.n);
    }

    /** This direction at length 1; ZERO stays ZERO. */
    public V2 unit() {
        double l = length();
        return l < 1e-9 ? ZERO : new V2(e / l, n / l);
    }

    /** At most this long. */
    public V2 capped(double max) {
        double l = length();
        return l <= max || l < 1e-9 ? this : times(max / l);
    }

    public double dot(V2 o) {
        return e * o.e + n * o.n;
    }

    /** Turned 90 degrees to the left (counter-clockwise seen from above). */
    public V2 left() {
        return new V2(-n, e);
    }

    /** The shortest distance from p to the segment a-b. */
    public static double segDist(V2 p, V2 a, V2 b) {
        V2 ab = b.minus(a);
        double l2 = ab.dot(ab);
        double t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, p.minus(a).dot(ab) / l2));
        return p.dist(a.plus(ab.times(t)));
    }
}
