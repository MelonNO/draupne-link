package de.droiddrone.pc.air;

import java.util.ArrayList;
import java.util.List;

/**
 * PRD AC-9, AC-8: every drone predicted 5 s ahead (straight on at its velocity), looked at five times a second by its
 * caller. A loss of separation is inside the protection radius sideways AND inside the layer spacing in height; it is
 * solved by the drone with the lower priority (a higher number) holding or giving way. The lead (priority 0) is never
 * moved. TRAFFIC for the pilot: any drone within twice the radius sideways and twice the spacing in height of the lead.
 */
public final class ConflictPredictor {
    public static final double HORIZON_S = 5, STEP_S = 0.25;

    /** A drone as the predictor sees it: where, how fast, how important (lower = more). */
    public record Track(String id, double e, double n, double u, double ve, double vn, double vu, int priority) {}

    /** Two drones predicted to lose separation: the first must give way; inS seconds from now. */
    public record Conflict(String givesWay, String other, double inS) {}

    private final AirParams p;

    public ConflictPredictor(AirParams p) {
        this.p = p;
    }

    /** Every predicted loss within the horizon, each pair once, the lower priority (or, on a tie, the later id) giving way. */
    public List<Conflict> predict(List<Track> tracks) {
        List<Conflict> out = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            for (int j = i + 1; j < tracks.size(); j++) {
                Track a = tracks.get(i), b = tracks.get(j);
                double t = firstLoss(a, b, p.radiusM(), p.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M);
                if (t < 0) continue;
                boolean aGives = a.priority() > b.priority() || a.priority() == b.priority() && a.id().compareTo(b.id()) > 0;
                if (aGives && a.priority() == 0) aGives = false; // the lead never gives way
                if (!aGives && b.priority() == 0 && a.priority() != 0) aGives = true;
                out.add(aGives ? new Conflict(a.id(), b.id(), t) : new Conflict(b.id(), a.id(), t));
            }
        }
        return out;
    }

    /** The drones near enough the lead for TRAFFIC (AC-8). */
    public List<Track> traffic(Track lead, List<Track> others) {
        List<Track> out = new ArrayList<>();
        for (Track o : others) {
            if (o.id().equals(lead.id())) continue;
            if (firstLoss(lead, o, 2 * p.radiusM(), 2 * p.layerM()) >= 0) out.add(o);
        }
        return out;
    }

    /** a and b predicted inside h sideways and v in height within the horizon. */
    public static boolean lossWithin(Track a, Track b, double h, double v) {
        return firstLoss(a, b, h, v) >= 0;
    }

    /** Seconds until a and b are first inside h sideways and v in height (0: now), -1: not within the horizon. */
    static double firstLoss(Track a, Track b, double h, double v) {
        for (double t = 0; t <= HORIZON_S + 1e-9; t += STEP_S) {
            double de = (a.e() + a.ve() * t) - (b.e() + b.ve() * t);
            double dn = (a.n() + a.vn() * t) - (b.n() + b.vn() * t);
            double du = (a.u() + a.vu() * t) - (b.u() + b.vu() * t);
            if (Math.hypot(de, dn) < h && Math.abs(du) < v) return t;
        }
        return -1;
    }
}
