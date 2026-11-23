package de.droiddrone.pc.air;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Safe velocities for the followers, one by one (PRD AC-8 "followers always give way", AC-1's protection radius):
 * ORCA, optimal reciprocal collision avoidance (van den Berg, Guy, Lin, Manocha 2011; the linear programs as in the
 * RVO2 library), in the horizontal plane. Each drone the PC flies gets the velocity closest to the one it wants that
 * keeps it clear of every other drone near its height for {@link #TAU_S} seconds: two drones the PC flies each take
 * half of the avoiding; against a drone it can't fly - the lead, one cut off, one without GNSS, one of another group -
 * the follower takes all of it. Heights are never changed here: drones near each other's height are the only ones
 * that count (a layer spacing apart, the cube's stacked columns, never meet), and a drone changing height counts
 * against those it may pass within the look ahead.
 */
public final class Orca {
    public static final double TAU_S = 6;

    /** A drone: where, how fast, where it wants to go (controlled ones), how fast it may go; err widens its radius. */
    public record Agent(String id, V2 pos, double alt, V2 vel, double vu, V2 pref, double maxSpeed, boolean controlled, double err, boolean firm,
                        boolean falling) {
        /**
         * firm: flown by the PC, but the others take all of the avoiding (it may stop and come down where it is any moment);
         * falling: really coming down now (its own climb rate) - it goes on down, so whatever is under it is in its way.
         */
        public Agent(String id, V2 pos, double alt, V2 vel, double vu, V2 pref, double maxSpeed, boolean controlled, double err, boolean firm) {
            this(id, pos, alt, vel, vu, pref, maxSpeed, controlled, err, firm, false);
        }

        public Agent(String id, V2 pos, double alt, V2 vel, double vu, V2 pref, double maxSpeed, boolean controlled, double err) {
            this(id, pos, alt, vel, vu, pref, maxSpeed, controlled, err, false, false);
        }
    }

    private record Line(V2 point, V2 dir) {}

    private Orca() {
    }

    /** The safe velocity of every controlled agent. */
    public static Map<String, V2> solve(List<Agent> agents, double radiusM, double heightM) {
        Map<String, V2> out = new HashMap<>();
        for (Agent a : agents) {
            if (!a.controlled()) continue;
            List<Line> lines = new ArrayList<>();
            for (Agent b : agents) {
                if (b == a) continue;
                double dz = Math.abs(a.alt() - b.alt());
                // one coming down comes down all the way: whatever is under it is in its way, however far below
                boolean under = b.falling() && a.alt() < b.alt() || a.falling() && b.alt() < a.alt();
                if (!under && dz - Math.abs(a.vu() - b.vu()) * TAU_S >= heightM) continue; // a layer apart, staying so
                double r = radiusM + b.err() + a.err();
                if (b.pos().dist(a.pos()) > r + (a.maxSpeed() + b.vel().length()) * TAU_S) continue;
                lines.add(line(a, b, r, b.controlled() && !b.firm() ? 0.5 : 1.0));
            }
            out.put(a.id(), velocity(lines, a.pref().capped(a.maxSpeed()), a.maxSpeed()));
        }
        return out;
    }

    private static double det(V2 a, V2 b) {
        return a.e() * b.n() - a.n() * b.e();
    }

    /** The half-plane of a's velocities that keep it clear of b for TAU_S, its share of the avoiding taken. */
    private static Line line(Agent a, Agent b, double r, double share) {
        V2 relPos = b.pos().minus(a.pos()), relVel = a.vel().minus(b.vel());
        if (relPos.length() < 0.05) { // straight over each other: a way out each, opposite (no direction would be none)
            relPos = new V2(a.id().compareTo(b.id()) < 0 ? 0.05 : -0.05, 0);
        }
        double distSq = relPos.dot(relPos), rSq = r * r;
        V2 dir, u;
        if (distSq > rSq) {
            V2 w = relVel.minus(relPos.times(1 / TAU_S));
            double wLenSq = w.dot(w), dot1 = w.dot(relPos);
            if (dot1 < 0 && dot1 * dot1 > rSq * wLenSq) { // the cut-off circle
                double wLen = Math.sqrt(wLenSq);
                V2 unitW = w.times(1 / wLen);
                dir = new V2(unitW.n(), -unitW.e());
                u = unitW.times(r / TAU_S - wLen);
            } else { // a leg
                double leg = Math.sqrt(distSq - rSq);
                if (det(relPos, w) > 0) {
                    dir = new V2(relPos.e() * leg - relPos.n() * r, relPos.e() * r + relPos.n() * leg).times(1 / distSq);
                } else {
                    dir = new V2(relPos.e() * leg + relPos.n() * r, -relPos.e() * r + relPos.n() * leg).times(-1 / distSq);
                }
                double dot2 = relVel.dot(dir);
                u = dir.times(dot2).minus(relVel);
            }
        } else { // already inside: out within a second
            double inv = 1.0;
            V2 w = relVel.minus(relPos.times(inv));
            double wLen = w.length();
            V2 unitW = wLen < 1e-9 ? relPos.unit().times(-1) : w.times(1 / wLen);
            dir = new V2(unitW.n(), -unitW.e());
            u = unitW.times(r * inv - wLen);
        }
        return new Line(a.vel().plus(u.times(share)), dir);
    }

    /** The velocity in every half-plane closest to pref within maxSpeed; when none is in them all, the least bad. */
    static V2 velocity(List<Line> lines, V2 pref, double maxSpeed) {
        V2[] result = {pref};
        int fail = lp2(lines, maxSpeed, pref, false, result);
        if (fail < lines.size()) lp3(lines, fail, maxSpeed, result);
        return result[0];
    }

    private static boolean lp1(List<Line> lines, int no, double radius, V2 opt, boolean dirOpt, V2[] result) {
        Line ln = lines.get(no);
        double dot = ln.point().dot(ln.dir());
        double disc = dot * dot + radius * radius - ln.point().dot(ln.point());
        if (disc < 0) return false;
        double sq = Math.sqrt(disc), tLeft = -dot - sq, tRight = -dot + sq;
        for (int i = 0; i < no; i++) {
            Line o = lines.get(i);
            double denom = det(ln.dir(), o.dir()), numer = det(o.dir(), ln.point().minus(o.point()));
            if (Math.abs(denom) <= 1e-9) {
                if (numer < 0) return false;
                continue;
            }
            double t = numer / denom;
            if (denom >= 0) tRight = Math.min(tRight, t);
            else tLeft = Math.max(tLeft, t);
            if (tLeft > tRight) return false;
        }
        if (dirOpt) {
            result[0] = ln.point().plus(ln.dir().times(opt.dot(ln.dir()) > 0 ? tRight : tLeft));
        } else {
            double t = ln.dir().dot(opt.minus(ln.point()));
            t = Math.max(tLeft, Math.min(tRight, t));
            result[0] = ln.point().plus(ln.dir().times(t));
        }
        return true;
    }

    private static int lp2(List<Line> lines, double radius, V2 opt, boolean dirOpt, V2[] result) {
        if (dirOpt) result[0] = opt.times(radius);
        else if (opt.dot(opt) > radius * radius) result[0] = opt.unit().times(radius);
        else result[0] = opt;
        for (int i = 0; i < lines.size(); i++) {
            Line ln = lines.get(i);
            if (det(ln.dir(), ln.point().minus(result[0])) > 0) {
                V2 keep = result[0];
                if (!lp1(lines, i, radius, opt, dirOpt, result)) {
                    result[0] = keep;
                    return i;
                }
            }
        }
        return lines.size();
    }

    private static void lp3(List<Line> lines, int begin, double radius, V2[] result) {
        double distance = 0;
        for (int i = begin; i < lines.size(); i++) {
            Line ln = lines.get(i);
            if (det(ln.dir(), ln.point().minus(result[0])) > distance) {
                List<Line> proj = new ArrayList<>();
                for (int j = 0; j < i; j++) {
                    Line o = lines.get(j);
                    double d = det(ln.dir(), o.dir());
                    V2 point;
                    if (Math.abs(d) <= 1e-9) {
                        if (ln.dir().dot(o.dir()) > 0) continue;
                        point = ln.point().plus(o.point()).times(0.5);
                    } else {
                        point = ln.point().plus(ln.dir().times(det(o.dir(), ln.point().minus(o.point())) / d));
                    }
                    proj.add(new Line(point, o.dir().minus(ln.dir()).unit()));
                }
                V2 keep = result[0];
                if (lp2(proj, radius, new V2(-ln.dir().n(), ln.dir().e()), true, result) < proj.size()) result[0] = keep;
                distance = det(ln.dir(), ln.point().minus(result[0]));
            }
        }
    }
}
