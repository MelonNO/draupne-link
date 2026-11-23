package de.droiddrone.pc.air;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every follower's way out without the PC (PRD AC-36, AC-10, AC-41, AC-25, K20), made by the PC and proven before it
 * is sent.
 * <ul>
 * <li>Lane: straight out from the group's centre through where the drone is, in its own layer, to past the keep-out
 *   zone; a lower layer's lanes end further out ({@link #LANE_STEP_M} a layer), and a drone in a transit shaft, on the
 *   lane, in an approach layer or the abort layer ends beyond every cube lane of its side.</li>
 * <li>Return height: one of its own for each drone, a layer spacing apart, all above the highest drone. A drone whose
 *   way home (from its lane's end to its rally point) passes within two protection radii of another's climb point or
 *   rally point flies higher than that one (so it is never in the other's way while that one climbs or comes down).
 *   Ordered so; where the rule goes round in a circle the order is cut and the later drone is given a start delay.</li>
 * <li>Rally point: its own, from {@link RallyPoints}, at least two protection radii from every other drone's (AC-10).</li>
 * <li>Proof: the whole set is flown in the kinematic model ({@link Prover}) with every drone cut off at once; a pair
 *   inside the protection radius sideways and the layer spacing in height fails it, and the later of the pair gets a
 *   start delay; tried {@link #MAX_TRIES} times. A set that can't be proven is not sent (the drones keep the last proven
 *   one) and {@link Result#proven} says so.</li>
 * </ul>
 */
public final class ExitPlanner {
    public static final double LANE_STEP_M = 15;
    public static final int MAX_TRIES = 12;
    public static final double DELAY_STEP_S = 15;

    /** Where a follower is: in a cell, or elsewhere round the cube (lane, shaft, approach or abort layer). */
    public enum Where { CELL, OUTSIDE }

    /**
     * One follower: its id, where it is from the group's centre, its height (AMSL), its layer counted from the top
     * (0 = the top cube layer; only for a CELL), bottom row, its home's height (AMSL) and its rally point (airspace
     * frame).
     */
    public record Input(String id, Where where, V2 rel, double altAmsl, int layersAbove, boolean bottomRow, double homeAmsl, V2 rally) {}

    /** The plan for one follower; the return height is above sea level (the phone gets it above its home). */
    public record Plan(String id, double laneEndRadiusM, double returnAltAmsl, double startDelayS, V2 rally, double layerAltAmsl,
                       boolean bottomRow, double homeAmsl) {
        public double returnAltRelM() {
            return returnAltAmsl - homeAmsl;
        }
    }

    /**
     * How much less than the layer spacing still counts as the spacing. The PRD stacks the cube's layers exactly one
     * spacing (10 m) apart, straight over each other, and calls a pair "inside the layer spacing in height" a loss: by
     * that measure every column of the cube is on the line all the time, and a height held to a few centimetres (as the
     * kinematic model holds it, never quite reaching a target) reads as 9.99 m. Half a metre is allowed here and said
     * so; on real drones the barometers' agreement (AC-2) is what this rests on - see the report.
     */
    public static final double VERTICAL_TOLERANCE_M = 0.5;

    /** The worst moment of a proof: who, how far apart sideways and in height. */
    public record Closest(String a, String b, double horizontalM, double verticalM) {
        public boolean loss(AirParams p) {
            return horizontalM < p.radiusM() && verticalM < p.layerM() - VERTICAL_TOLERANCE_M;
        }
    }

    public record Result(Map<String, Plan> plans, boolean proven, Closest closest, double leadReturnAltAmsl) {}

    /**
     * Flies a set of plans in the kinematic model with everyone cut off at once - moving with the group at its velocity
     * as they are, braking into LOITER as the phones do; returns the worst moment.
     */
    public interface Prover {
        Closest fly(V2 centre, V2 velocity, List<Input> inputs, Map<String, Plan> plans, double leadReturnAltAmsl);
    }

    private final AirParams p;

    public ExitPlanner(AirParams p) {
        this.p = p;
    }

    /**
     * @param centre      the group's centre, airspace frame
     * @param velocity    the group's velocity now (the drones brake from it when the PC goes)
     * @param topAltAmsl  the highest drone near the group (its top layer, approach layers)
     * @param prover      null: no proof (the plans are made, not proven)
     */
    public Result plan(V2 centre, V2 velocity, List<Input> inputs, double topAltAmsl, Prover prover) {
        Map<String, Double> radius = new HashMap<>();
        int maxBelow = inputs.stream().filter(i -> i.where() == Where.CELL).mapToInt(Input::layersAbove).max().orElse(0);
        for (Input i : inputs) {
            double r = i.where() == Where.CELL ? p.laneEndBaseM() + i.layersAbove() * LANE_STEP_M
                    : p.laneEndBaseM() + (maxBelow + 1) * LANE_STEP_M;
            radius.put(i.id(), Math.max(r, i.rel().length() + p.radiusM()));
        }
        Map<String, V2> climb = new HashMap<>();
        for (Input i : inputs) {
            V2 dir = i.rel().length() < 0.5 ? new V2(0, 1) : i.rel().unit();
            climb.put(i.id(), centre.plus(dir.times(radius.get(i.id()))));
        }
        // who must be above whom: j above i when j's way home passes near i's climb point or rally point
        Map<String, Set<String>> above = new HashMap<>(); // j -> the i's it must be above
        for (Input j : inputs) {
            Set<String> s = new HashSet<>();
            for (Input i : inputs) {
                if (i == j) continue;
                double near = 2 * p.radiusM();
                if (V2.segDist(climb.get(i.id()), climb.get(j.id()), j.rally()) < near
                        || V2.segDist(i.rally(), climb.get(j.id()), j.rally()) < near) s.add(i.id());
            }
            above.put(j.id(), s);
        }
        List<String> order = new ArrayList<>(); // lowest return height first
        Map<String, Double> delay = new HashMap<>();
        Set<String> placed = new HashSet<>();
        // where nothing says otherwise: the drone whose climb point is furthest towards home lowest (RallyPoints gives
        // it the nearest rally point), so those that fly over it on the way home are above it
        V2 rallyMean = V2.ZERO;
        for (Input i : inputs) rallyMean = rallyMean.plus(i.rally().times(1.0 / inputs.size()));
        V2 homeDir = rallyMean.minus(centre).unit();
        List<Input> left = new ArrayList<>(inputs);
        left.sort(Comparator.comparingDouble((Input i) -> -climb.get(i.id()).minus(centre).dot(homeDir))
                .thenComparingDouble(i -> radius.get(i.id())).thenComparing(Input::id));
        while (!left.isEmpty()) {
            Input next = null;
            for (Input c : left) {
                if (placed.containsAll(above.get(c.id()))) {
                    next = c;
                    break;
                }
            }
            if (next == null) { // a circle: cut it at the first left; it leaves later than those it can't be above
                next = left.get(0);
                delay.put(next.id(), DELAY_STEP_S * 4);
            }
            order.add(next.id());
            placed.add(next.id());
            left.remove(next);
        }
        double base = topAltAmsl + p.layerM();
        Map<String, Input> byId = new HashMap<>();
        for (Input i : inputs) byId.put(i.id(), i);
        double highest = base;
        Map<String, Plan> plans = new LinkedHashMap<>();
        for (int k = 0; k < order.size(); k++) {
            Input i = byId.get(order.get(k));
            double ret = base + k * p.layerM();
            highest = ret;
            plans.put(i.id(), new Plan(i.id(), radius.get(i.id()), ret, delay.getOrDefault(i.id(), 0.0), i.rally(), i.altAmsl(),
                    i.bottomRow(), i.homeAmsl()));
        }
        double leadAlt = highest + p.layerM();
        if (prover == null) return new Result(plans, false, null, leadAlt);
        Closest worst = null;
        for (int t = 0; t < MAX_TRIES; t++) {
            worst = prover.fly(centre, velocity, inputs, plans, leadAlt);
            if (worst == null || !worst.loss(p)) return new Result(plans, true, worst, leadAlt);
            String later = order.indexOf(worst.a()) > order.indexOf(worst.b()) ? worst.a() : worst.b();
            Plan old = plans.get(later);
            if (old == null) break; // the lead in the worst pair with no follower to delay: give up
            plans.put(later, new Plan(old.id(), old.laneEndRadiusM(), old.returnAltAmsl(), old.startDelayS() + DELAY_STEP_S, old.rally(),
                    old.layerAltAmsl(), old.bottomRow(), old.homeAmsl()));
        }
        return new Result(plans, false, worst, leadAlt);
    }
}
