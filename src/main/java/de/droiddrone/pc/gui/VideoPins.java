package de.droiddrone.pc.gui;

import de.droiddrone.pc.CameraView;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.Pins;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The pins on the video (1.0.70, the pilot: "make all pins virtual AR pins in the camera view"): each pin the maps
 * show, drawn on the live picture where it stands - the map's camera view turned round
 * ({@link CameraFootprint#toPicture}), from the pose MARK casts its rays from. A pin the terrain hides is grey; the
 * nearest pins beside or behind the picture get an arrow on its edge, pointing the way. As exact as the heading and
 * the camera's angle and tilt settings: 5 degrees of heading put a pin a fourteenth of the picture to the side.
 * <p>
 * The picture is some 200 ms old, the attitude newer: with the picture's delay known (G2G, phone app pc.53) the pose
 * is the one from when the picture was taken, out of the last ones kept here; without it the newest - the pins then
 * trail the scene in a fast turn. Which pins the terrain hides is worked out off the Swing thread every HIDDEN_MS
 * (its sight lines march the terrain); the rest is a few sums per pin and paint.
 */
final class VideoPins {
    /** Pins further than this are not drawn. */
    static final double MAX_RANGE_M = 10_000;
    /** Arrows on the edge for the nearest this many pins outside the picture: more would fence it in. */
    static final int MAX_ARROWS = 5;
    /** The arrows stand this far out on the picture (-1..1), clear of its edge. */
    static final double EDGE = 0.9;
    /** Two arrows nearer than this along an edge (picture units, the edge's own way) are moved apart. */
    static final double GAP_SIDE = 0.1, GAP_TOP_BOTTOM = 0.3;
    /** Poses kept for the picture's delay. */
    static final long HISTORY_MS = 1500;
    /** A picture older than this is a frozen one: the pose from this long ago at most. */
    static final long MAX_SHIFT_MS = 1000;
    /** Which pins the terrain hides: worked out again this often. */
    static final long HIDDEN_MS = 500;

    /**
     * One mark. label "ALPHA 850M" ("~ALPHA ..." a rough pin); (u, v) -1..1 across and down the picture as it is shown:
     * a pin's foot in the picture, or an arrow's place on the edge, (du, dv) the way it points (picture units);
     * hidden: the terrain stands between (grey).
     */
    record Mark(String label, double u, double v, boolean arrow, double du, double dv, boolean rough, boolean hidden, double distM) {
    }

    /** A pin with its ground's height and whether the terrain hides it, as last worked out. */
    record Placed(Pins.Pin pin, double groundM, boolean hidden) {
    }

    /** The pose at an attitude's arrival (MonoClock ms). */
    record Sample(long at, CameraFootprint.Pose pose) {
    }

    private final CameraView view;
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "videoPins");
        th.setDaemon(true);
        th.setPriority(Thread.MIN_PRIORITY);
        return th;
    });
    private volatile Map<Pins.Pin, Placed> placed = Map.of();
    private volatile boolean working;
    private long placedAt = Long.MIN_VALUE / 2;

    VideoPins(CameraView view) {
        this.view = view;
    }

    /**
     * The marks for the picture shown now; none without a pose (no position, heading or attitude). shiftMs: how much
     * older the picture is than the newest attitude ({@link #shiftMs}); now: MonoClock.
     */
    synchronized List<Mark> marks(FcTelemetry t, FlightTrack track, List<Pins.Pin> pins, boolean armed, long shiftMs, long now) {
        CameraFootprint.Pose p = pins == null || pins.isEmpty() ? null : view.pinPose(t, track, armed);
        if (p == null) {
            history.clear();
            return List.of();
        }
        long at = t.getAttitude().receivedAt(); // pinPose has one, fresh
        if (!history.isEmpty() && history.peekLast().at() == at) history.removeLast(); // the same attitude, a newer position
        history.addLast(new Sample(at, p));
        while (history.size() > 1 && history.peekFirst().at() < now - HISTORY_MS) history.removeFirst();
        CameraFootprint.Pose shown = shiftMs > 0 ? at(history, now - shiftMs) : p;
        refresh(t, track, p, pins, now);
        Map<Pins.Pin, Placed> known = placed;
        List<Placed> list = new ArrayList<>(pins.size());
        for (Pins.Pin pin : pins) {
            Placed x = known.get(pin); // one just dropped: its ground now, hidden from the next round
            list.add(x != null ? x : new Placed(pin, view.groundM(t, track, p, pin.lat(), pin.lon()), false));
        }
        return marks(shown, list);
    }

    /** Which pins the terrain hides, worked out again off the Swing thread once HIDDEN_MS has passed and none runs. */
    private void refresh(FcTelemetry t, FlightTrack track, CameraFootprint.Pose p, List<Pins.Pin> pins, long now) {
        if (working || now - placedAt < HIDDEN_MS) return;
        working = true;
        placedAt = now;
        List<Pins.Pin> all = List.copyOf(pins);
        try {
            pool.execute(() -> {
                try {
                    Map<Pins.Pin, Placed> next = new HashMap<>();
                    for (Pins.Pin pin : all) {
                        if (!(Mercator.distanceM(p.lat(), p.lon(), pin.lat(), pin.lon()) <= MAX_RANGE_M)) continue;
                        double ground = view.groundM(t, track, p, pin.lat(), pin.lon());
                        next.put(pin, new Placed(pin, ground, view.hidden(p, pin.lat(), pin.lon(), ground)));
                    }
                    placed = next;
                } catch (RuntimeException ignored) {
                    // the next round tries again
                } finally {
                    working = false;
                }
            });
        } catch (RejectedExecutionException e) {
            working = false;
        }
    }

    /**
     * How much older the picture on the screen is than the newest attitude: its delay from the camera to the screen
     * (G2G, and the view's own time to paint) less the attitude's way here (half the ping). 0 while the delay isn't
     * known (a phone app before pc.53; watching): the newest pose.
     */
    static long shiftMs(int g2gMs, double displayMs, int pingMs) {
        if (g2gMs < 0) return 0;
        long s = Math.round(g2gMs + Math.max(0, displayMs) - Math.max(0, pingMs) / 2.0);
        return Math.max(0, Math.min(MAX_SHIFT_MS, s));
    }

    /** The pose at time t (MonoClock): between the two kept round it, else the oldest or the newest. */
    static CameraFootprint.Pose at(Iterable<Sample> history, long t) {
        Sample before = null, first = null;
        for (Sample s : history) {
            if (first == null) first = s;
            if (s.at() <= t) {
                before = s;
                continue;
            }
            if (before == null) return s.pose(); // older than any kept
            return between(before.pose(), s.pose(), (double) (t - before.at()) / (s.at() - before.at()));
        }
        return before != null ? before.pose() : first == null ? null : first.pose();
    }

    /** f of the way from a to b; the heading the short way round. */
    static CameraFootprint.Pose between(CameraFootprint.Pose a, CameraFootprint.Pose b, double f) {
        double turn = ((b.headingDeg() - a.headingDeg()) % 360 + 540) % 360 - 180;
        return new CameraFootprint.Pose(a.lat() + (b.lat() - a.lat()) * f, a.lon() + (b.lon() - a.lon()) * f,
                a.altM() + (b.altM() - a.altM()) * f, ((a.headingDeg() + turn * f) % 360 + 360) % 360,
                a.pitchDeg() + (b.pitchDeg() - a.pitchDeg()) * f, a.rollDeg() + (b.rollDeg() - a.rollDeg()) * f,
                b.tiltDeg(), b.hfovDeg(), b.vfovDeg());
    }

    /**
     * The marks from pose p: every pin within MAX_RANGE_M in the picture, the furthest first (the nearer drawn over
     * them); then arrows for the nearest MAX_ARROWS outside it - one ahead where the line from the picture's middle
     * to it crosses the edge, one behind on the side it is to turn to - moved apart where they would stand on each other.
     */
    static List<Mark> marks(CameraFootprint.Pose p, List<Placed> pins) {
        List<Mark> in = new ArrayList<>(), out = new ArrayList<>();
        for (Placed x : pins) {
            Pins.Pin pin = x.pin();
            double dist = Mercator.distanceM(p.lat(), p.lon(), pin.lat(), pin.lon());
            if (!(dist <= MAX_RANGE_M)) continue;
            double[] s = CameraFootprint.toPicture(p, pin.lat(), pin.lon(), x.groundM());
            double u = s[0], v = s[1];
            if (!Double.isFinite(u) || !Double.isFinite(v)) continue;
            String label = label(pin, dist);
            if (s[2] > 0 && Math.abs(u) <= 1 && Math.abs(v) <= 1) {
                in.add(new Mark(label, u, v, false, 0, 0, pin.rough(), x.hidden(), dist));
            } else if (s[2] > 0) {
                double k = EDGE / Math.max(Math.abs(u), Math.abs(v));
                out.add(new Mark(label, u * k, v * k, true, u, v, pin.rough(), x.hidden(), dist));
            } else {
                double side = u >= 0 ? 1 : -1;
                out.add(new Mark(label, side * EDGE, 0, true, side, 0, pin.rough(), x.hidden(), dist));
            }
        }
        in.sort(Comparator.comparingDouble(Mark::distM).reversed());
        out.sort(Comparator.comparingDouble(Mark::distM));
        List<Mark> marks = new ArrayList<>(in);
        List<Mark> arrows = new ArrayList<>();
        for (Mark m : out) {
            if (arrows.size() >= MAX_ARROWS) break;
            arrows.add(apart(m, arrows));
        }
        marks.addAll(arrows);
        return marks;
    }

    /**
     * The arrow moved along its edge until its label is clear of those placed before it: a step at a time towards the
     * picture's middle (from the middle itself, down), the other way once the edge's end is reached.
     */
    private static Mark apart(Mark m, List<Mark> placed) {
        boolean side = Math.abs(m.u()) >= EDGE - 1e-9; // on the left or right edge: moved up or down
        double u = m.u(), v = m.v();
        double along = side ? v : u, step = side ? GAP_SIDE : GAP_TOP_BOTTOM;
        double dir = along > 0 ? -1 : 1;
        for (int tries = 0; tries < 4 * MAX_ARROWS && overlaps(u, v, placed); tries++) {
            double next = along + dir * step;
            if (Math.abs(next) > EDGE) {
                dir = -dir;
                next = along + dir * step;
            }
            along = clamp(next);
            if (side) v = along;
            else u = along;
        }
        return new Mark(m.label(), u, v, true, m.du(), m.dv(), m.rough(), m.hidden(), m.distM());
    }

    /** A label at (u, v) would stand on one of these: about 0.3 of the picture wide and 0.1 high. */
    private static boolean overlaps(double u, double v, List<Mark> placed) {
        for (Mark o : placed) {
            if (Math.abs(o.u() - u) < GAP_TOP_BOTTOM - 1e-9 && Math.abs(o.v() - v) < GAP_SIDE - 1e-9) return true;
        }
        return false;
    }

    private static double clamp(double x) {
        return Math.max(-EDGE, Math.min(EDGE, x));
    }

    /** "ALPHA 850M", "~BRAVO 2.4KM", "CHARLIE 12KM". */
    static String label(Pins.Pin pin, double distM) {
        return (pin.rough() ? "~" : "") + pin.name().toUpperCase(Locale.ROOT) + " " + distance(distM);
    }

    static String distance(double m) {
        if (m < 100) return Math.round(m) + "M";
        if (m < 1000) return Math.round(m / 10) * 10 + "M";
        if (m < 10_000) return String.format(Locale.ROOT, "%.1fKM", m / 1000);
        return Math.round(m / 1000) + "KM";
    }
}
