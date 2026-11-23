package de.droiddrone.pc.route;

import java.util.ArrayList;
import java.util.List;

/**
 * Fleet PRD RT-7: what the pilot sees before an upload - the terrain and the line along the way out, the lowest
 * clearance and where, the distance, the flight time, the battery for the way out and for out and home, and the
 * stretches without 4G coverage. RT-6: the heights are over bare ground ({@link #NOTE}).
 *
 * @param outM / backM          the way out / home, metres
 * @param outS / backS          flight times, seconds (takeoff and landing climbs included)
 * @param outPercent            battery for the way out, %; totalPercent for out and home
 * @param lowestClearanceM      the least height over the ground along the way out, and lowestAtM where
 * @param noCoverage            stretches of the way out without 4G, each {from m, to m} (empty: covered, or not known)
 * @param along / ground / line the way out for the chart (at most {@link #CHART_POINTS} points)
 */
public record RouteProfile(double outM, double backM, double outS, double backS, double outPercent, double totalPercent,
                           double lowestClearanceM, double lowestAtM, List<double[]> noCoverage,
                           double[] along, double[] ground, double[] line) {
    /** RT-6, the route view's own line. */
    public static final String NOTE = "Height is above bare ground: trees, masts and houses are not in the map.";
    static final int CHART_POINTS = 1500;
    /** 4G coverage is looked at every this many metres. */
    static final double COVERAGE_STEP_M = 50;

    /** How fast the battery goes in flight: % a minute (the pilot, 2026-11-13: a full battery lasts 23 min). */
    public record Battery(double percentPerMinute) {
        public static final Battery DEFAULT = new Battery(100 / 23.0);
    }

    /** Whether a place has 4G at a height over the ground (the map's COVERAGE estimate); null: not known. */
    @FunctionalInterface
    public interface CoverageAt {
        boolean covered(double lat, double lon, double heightAglM);
    }

    public static RouteProfile of(RoutePlanner.Plan plan, Battery battery, CoverageAt coverage) {
        RoutePlanner.Limits lim = plan.limits();
        RoutePlanner.Line out = plan.out(), back = plan.back();
        double outS = time(out, lim), backS = time(back, lim);
        if (plan.takeoff()) {
            outS += Math.max(0, out.waypoints().get(0).altAmslM() - out.ground()[0]) / lim.climbMs();
            int last = back.ground().length - 1;
            backS += Math.max(0, back.waypoints().get(back.waypoints().size() - 1).altAmslM() - back.ground()[last]) / lim.descentMs();
        }
        double perS = battery.percentPerMinute() / 60;
        int low = out.lowestClearanceIndex();
        List<double[]> gaps = new ArrayList<>();
        if (coverage != null) {
            double next = 0, gapFrom = -1;
            int n = out.along().length;
            for (int i = 0; i < n; i++) {
                if (out.along()[i] < next && i != n - 1) continue;
                next = out.along()[i] + COVERAGE_STEP_M;
                boolean ok = coverage.covered(out.lat()[i], out.lon()[i], out.flown()[i] - out.ground()[i]);
                if (!ok && gapFrom < 0) gapFrom = out.along()[i];
                if (ok && gapFrom >= 0) {
                    gaps.add(new double[]{gapFrom, out.along()[i]});
                    gapFrom = -1;
                }
            }
            if (gapFrom >= 0) gaps.add(new double[]{gapFrom, out.lengthM()});
        }
        int n = out.along().length, stride = Math.max(1, (int) Math.ceil(n / (double) CHART_POINTS));
        List<Integer> picked = new ArrayList<>();
        for (int i = 0; i < n; i += stride) picked.add(i);
        if (picked.get(picked.size() - 1) != n - 1) picked.add(n - 1); // the end always shown
        double[] along = new double[picked.size()], ground = new double[picked.size()], line = new double[picked.size()];
        for (int k = 0; k < picked.size(); k++) {
            int i = picked.get(k);
            along[k] = out.along()[i];
            ground[k] = out.ground()[i];
            line[k] = out.flown()[i];
        }
        return new RouteProfile(out.lengthM(), back.lengthM(), outS, backS, outS * perS, (outS + backS) * perS,
                out.flown()[low] - out.ground()[low], out.along()[low], gaps, along, ground, line);
    }

    /** Leg by leg: the horizontal at cruise, or the climb / descent at its speed, whichever takes longer. */
    static double time(RoutePlanner.Line line, RoutePlanner.Limits lim) {
        double s = 0;
        List<RoutePlanner.Waypoint> w = line.waypoints();
        for (int i = 1; i < w.size(); i++) {
            double h = w.get(i).alongM() - w.get(i - 1).alongM(), dz = w.get(i).altAmslM() - w.get(i - 1).altAmslM();
            s += Math.max(h / lim.cruiseMs(), dz >= 0 ? dz / lim.climbMs() : -dz / lim.descentMs());
        }
        return s;
    }

    /** "4.2 KM  7:40  OUT 33 %  OUT+HOME 61 %  LOWEST 58 M" - the profile's summary line, short as the app's text. */
    public String summary() {
        long s = Math.round(outS);
        return String.format(java.util.Locale.ROOT, "%.1f KM  %d:%02d  OUT %.0f %%  OUT+HOME %.0f %%  LOWEST %.0f M%s",
                outM / 1000, s / 60, s % 60, outPercent, totalPercent, lowestClearanceM,
                noCoverage.isEmpty() ? "" : "  NO COVERAGE: " + noCoverage.size());
    }

    /** More battery than there is (or none left for landing): the upload asks first. */
    public boolean overBattery(double percentAvailable) {
        return totalPercent > percentAvailable;
    }
}
