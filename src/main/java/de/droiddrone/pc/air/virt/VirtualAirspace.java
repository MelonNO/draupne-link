package de.droiddrone.pc.air.virt;

import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.FleetIntent;
import de.droiddrone.common.group.FollowerBrain;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.group.GroupOrder;
import de.droiddrone.pc.air.AirParams;
import de.droiddrone.pc.air.ExitPlanner;
import de.droiddrone.pc.air.GroupController;
import de.droiddrone.pc.air.V2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A virtual piece of airspace for the fleet's group rules (PRD AC-14, AC-26): {@link VirtualCopter}s, each follower
 * flown by the phone's own {@link FollowerBrain} exactly as the phone app runs it, the PC's {@link GroupController}
 * five times a second, a link per drone that can be cut, the whole PC that can go, wind, GNSS that can be lost, and the
 * closest approach of every pair in the air measured every step. Deterministic: no clock but its own, no randomness but
 * a seed. Flat ground at {@link #groundAmsl}.
 * <p>
 * The lead is flown by a {@link Pilot}; while the PC is silent to it, a stand-in for the phone's lead rule
 * (ArduLinkLossRule: LOITER after 1 s, plain RTL at the group's lead height after 20 s) flies it.
 */
public final class VirtualAirspace {
    /** The pilot of the lead: where to fly it at time t (seconds): {e, n, altAmsl}. */
    public interface Pilot {
        double[] at(double tS, VirtualCopter lead);
    }

    public static final long STEP_MS = 100, PC_MS = 200;

    public final double lat0 = 60.0, lon0 = 10.0;
    public final double groundAmsl;
    public final AirParams params;
    public V2 wind = V2.ZERO;
    public final Map<String, VirtualCopter> copters = new LinkedHashMap<>();
    public final Map<String, FollowerBrain> phones = new LinkedHashMap<>();
    private final Map<String, Boolean> link = new HashMap<>();
    private final Map<String, Long> phoneHeardPc = new HashMap<>(), pcHeard = new HashMap<>();
    public boolean pcAlive = true;
    public GroupController gc;
    public String leadId;
    public Pilot pilot;
    /** Drones that are none of the group's business (an old lead): they stay where they are, the group keeps clear. */
    public final Set<String> outside = new HashSet<>();
    private final Map<String, V2> outsideTarget = new HashMap<>();
    private final Map<String, Double> launching = new LinkedHashMap<>(); // reserve -> approach height
    private final Map<String, Double> reserveBattery = new HashMap<>();
    private final Set<String> climbing = new HashSet<>();
    public final List<String> events = new ArrayList<>();
    public long now;
    private final long seed;
    private double leadRtlAltRel = 60;
    /** Called with each new lead (DISCARD LEAD, a taken-over lead): the test flies it from then on. */
    public java.util.function.Consumer<String> onNewLead = id -> { };

    // the closest approach so far
    private double worst = Double.MAX_VALUE, worstH = Double.NaN, worstV = Double.NaN;
    private String worstA, worstB;
    private double worstAtS;
    private double minSameHeight = Double.MAX_VALUE, minOverhead = Double.MAX_VALUE;

    /** The smallest distance sideways between two drones at the same height (within the spacing, less its tolerance). */
    public double minSameHeightM() {
        return minSameHeight;
    }

    /** The smallest difference in height between two drones less than a protection radius apart sideways. */
    public double minOverheadM() {
        return minOverhead;
    }

    public VirtualAirspace(AirParams p, double groundAmsl, long seed) {
        this.params = p;
        this.groundAmsl = groundAmsl;
        this.seed = seed;
    }

    public VirtualCopter add(String id, double e, double n, double u) {
        VirtualCopter c = new VirtualCopter(id, e, n, u, groundAmsl, seed);
        copters.put(id, c);
        link.put(id, true);
        phoneHeardPc.put(id, now);
        pcHeard.put(id, now);
        return c;
    }

    public FollowerBrain follower(String id) {
        FollowerBrain b = new FollowerBrain(GroupController.SILENT_MS);
        b.holdMs = params.holdMs();
        phones.put(id, b);
        return b;
    }

    public void setLink(String id, boolean up) {
        link.put(id, up);
    }

    public boolean linkUp(String id) {
        return pcAlive && link.getOrDefault(id, false);
    }

    public double t() {
        return now / 1000.0;
    }

    /**
     * A reserve on the ground at (e, n): called now. It climbs straight up to an approach layer of its own once its
     * column is clear (AC-4, AC-5), then joins (the group keeps clear of its column meanwhile).
     */
    public void launchReserve(String id, double e, double n, double battery) {
        VirtualCopter c = add(id, e, n, groundAmsl);
        c.battery = battery;
        c.homeAmsl = groundAmsl;
        double alt = gc.nextApproachAlt() + launching.size() * params.layerM();
        launching.put(id, alt);
        reserveBattery.put(id, battery);
    }

    /** One step of {@link #STEP_MS}. */
    public void step() {
        now += STEP_MS;
        double dt = STEP_MS / 1000.0;
        for (VirtualCopter c : copters.values()) if (linkUp(c.id)) pcHeard.put(c.id, now);
        if (pcAlive && now % PC_MS == 0 && gc != null) pcTick();
        launches();
        for (Map.Entry<String, FollowerBrain> e : phones.entrySet()) {
            if (paused.contains(e.getKey())) continue;
            VirtualCopter c = copters.get(e.getKey());
            double[] ll = GeoLocal.offset(lat0, lon0, c.e, c.n);
            FleetIntent i = e.getValue().tick(now, phoneHeardPc.getOrDefault(c.id, 0L), ll[0], ll[1], c.battery, c.gnssOk);
            if (!c.landed) c.apply(i, lat0, lon0);
        }
        flyLead();
        for (String id : outside) {
            VirtualCopter c = copters.get(id);
            if (c == null || c.landed) continue;
            V2 aside = outsideTarget.get(id);
            if (linkUp(id) && aside != null) { // the PC steps it out of a cut-off drone's way (AC-37)
                double[] ll = GeoLocal.offset(lat0, lon0, aside.e(), aside.n());
                c.apply(FleetIntent.guided(ll[0], ll[1], c.u, Double.NaN, 5), lat0, lon0);
            } else             if (linkUp(id)) c.apply(FleetIntent.HOLD, lat0, lon0); // keeps its mode (E3): here, it holds
            else standIn(c, now - phoneHeardPc.getOrDefault(id, now));
        }
        for (String id : paused) {
            VirtualCopter c = copters.get(id);
            if (c != null && !c.landed) c.apply(FleetIntent.HOLD, lat0, lon0);
        }
        for (VirtualCopter c : copters.values()) {
            if (c.battery <= 3 && !c.landed && c.mode() != VirtualCopter.Mode.LAND && c.mode() != VirtualCopter.Mode.RTL) {
                c.apply(FleetIntent.DOWN, lat0, lon0); // ArduPilot's own critical battery action (BATT_FS_CRT_ACT = Land)
            }
            c.step(dt, wind.e(), wind.n(), groundAmsl);
        }
        measure();
    }

    public void run(double seconds) {
        long until = now + Math.round(seconds * 1000);
        while (now < until) step();
    }

    private void launches() {
        for (Map.Entry<String, Double> e : new ArrayList<>(launching.entrySet())) {
            VirtualCopter c = copters.get(e.getKey());
            if (!climbing.contains(c.id)) {
                boolean clear = true;
                for (VirtualCopter o : copters.values()) {
                    if (o == c || o.landed) continue;
                    if (Math.hypot(o.e - c.e, o.n - c.n) < 2 * params.radiusM() && o.u < e.getValue() + params.layerM()) clear = false;
                }
                if (clear) {
                    climbing.add(c.id);
                    c.climbTo(e.getValue());
                }
            } else if (c.u >= e.getValue() - 0.5) {
                launching.remove(c.id);
                climbing.remove(c.id);
                follower(c.id);
                gc.addReserve(c.id, reserveBattery.get(c.id), new V2(c.homeE, c.homeN), e.getValue(), now);
            }
        }
    }

    private void pcTick() {
        Map<String, GroupController.Report> reports = new HashMap<>();
        for (String id : gc.members().keySet()) {
            VirtualCopter c = copters.get(id);
            if (c != null) reports.put(id, report(c));
        }
        GroupController.Report lead = leadId == null || copters.get(leadId) == null ? null : report(copters.get(leadId));
        List<GroupController.Other> others = new ArrayList<>();
        for (String id : outside) {
            VirtualCopter c = copters.get(id);
            if (c != null && !c.landed) others.add(new GroupController.Other(id, c.estimate(), c.u, new V2(c.ve, c.vn), false));
        }
        for (String id : launching.keySet()) {
            VirtualCopter c = copters.get(id);
            others.add(new GroupController.Other(id, c.pos(), c.u, V2.ZERO, false));
        }
        GroupController.Out out = gc.tick(now, reports, lead, others, wind);
        for (Map.Entry<String, GroupOrder> e : out.orders().entrySet()) {
            FollowerBrain b = phones.get(e.getKey());
            if (b != null && linkUp(e.getKey())) {
                b.order(e.getValue(), now);
                phoneHeardPc.put(e.getKey(), now);
            }
        }
        if (out.plans() != null) {
            for (Map.Entry<String, ExitPlan> e : out.plans().entrySet()) {
                FollowerBrain b = phones.get(e.getKey());
                if (b != null && linkUp(e.getKey())) {
                    b.plan(e.getValue());
                    VirtualCopter c = copters.get(e.getKey()); // the phone uploads the rally point to the FC
                    c.rallyE = GeoLocal.east(lat0, lon0, e.getValue().rallyLat, e.getValue().rallyLon);
                    c.rallyN = GeoLocal.north(lat0, lon0, e.getValue().rallyLat, e.getValue().rallyLon);
                }
            }
        }
        if (leadId != null && linkUp(leadId)) phoneHeardPc.put(leadId, now);
        if (!Double.isNaN(out.leadRtlAltRelM())) leadRtlAltRel = out.leadRtlAltRelM();
        for (String ev : out.events()) events.add(String.format(java.util.Locale.ROOT, "%.1f %s", t(), ev));
        outsideTarget.putAll(out.clear());
        if (out.newLead() != null) {
            if (leadId != null) outside.add(leadId); // the old lead keeps its mode (E3): here it holds where it is
            leadId = out.newLead();
            phones.remove(leadId);
            onNewLead.accept(leadId);
        }
    }

    private GroupController.Report report(VirtualCopter c) {
        long heard = pcHeard.getOrDefault(c.id, 0L);
        boolean following = c.mode() == VirtualCopter.Mode.GUIDED || c.mode() == VirtualCopter.Mode.PILOT;
        return new GroupController.Report(c.id, c.estimate(), c.u, new V2(c.ve, c.vn), c.vu, c.battery, c.gnssOk, c.errorM(t()),
                heard, following, c.landed, c.homeAmsl);
    }

    private void flyLead() {
        if (leadId == null) return;
        VirtualCopter lead = copters.get(leadId);
        if (lead == null || lead.landed && pilot == null) return;
        long silent = now - phoneHeardPc.getOrDefault(leadId, now);
        if (silent >= 1000) standIn(lead, silent);
        else if (adsbThreat(lead)) lead.apply(FleetIntent.HOLD, lat0, lon0); // ArduPilot's avoidance: stop and hold (AC-11)
        else if (pilot != null) {
            double[] to = pilot.at(t(), lead);
            if (to != null) lead.pilot(to[0], to[1], to[2]);
        }
    }

    /**
     * PRD AC-11, the backstop on the lead's own flight controller: the PC gives it every other DRAUPNE drone as an
     * ADS-B target - the heard ones where they are, the cut-off ones where their exit plans have them
     * ({@link GroupController#unheard}) - and ArduPilot's avoidance stops and holds when one is predicted inside the
     * protection radius and the layer spacing within 5 s.
     */
    private boolean adsbThreat(VirtualCopter lead) {
        if (gc == null) return false;
        de.droiddrone.pc.air.ConflictPredictor.Track me = new de.droiddrone.pc.air.ConflictPredictor.Track(lead.id, lead.e, lead.n, lead.u, lead.ve, lead.vn, lead.vu, 0);
        List<de.droiddrone.pc.air.ConflictPredictor.Track> them = new ArrayList<>();
        for (Map.Entry<String, double[]> u : gc.unheard(now).entrySet()) {
            double[] x = u.getValue();
            them.add(new de.droiddrone.pc.air.ConflictPredictor.Track(u.getKey(), x[0], x[1], x[2], x[3], x[4], x[5], 0));
        }
        for (VirtualCopter c : copters.values()) {
            if (c == lead || c.landed || !linkUp(c.id)) continue;
            them.add(new de.droiddrone.pc.air.ConflictPredictor.Track(c.id, c.e, c.n, c.u, c.ve, c.vn, c.vu, 0));
        }
        for (de.droiddrone.pc.air.ConflictPredictor.Track o : them) {
            if (de.droiddrone.pc.air.ConflictPredictor.lossWithin(me, o, params.radiusM(), params.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M)) return true;
        }
        return false;
    }

    /** The lead rule's stand-in (the phone's ArduLinkLossRule): LOITER after 1 s of PC silence, plain RTL after 20 s. */
    private void standIn(VirtualCopter c, long silent) {
        if (silent >= 20_000) c.apply(FleetIntent.rtl(leadRtlAltRel), lat0, lon0);
        else if (silent >= 1000) c.apply(FleetIntent.HOLD, lat0, lon0);
    }

    /** A follower stopped by its own pilot or flight controller (LOITER), its phone's rules not flying it (AC-40). */
    public final Set<String> paused = new HashSet<>();

    private void measure() {
        List<VirtualCopter> air = new ArrayList<>();
        for (VirtualCopter c : copters.values()) if (!c.landed && c.u > groundAmsl + 0.5) air.add(c);
        for (int i = 0; i < air.size(); i++) {
            for (int j = i + 1; j < air.size(); j++) {
                VirtualCopter a = air.get(i), b = air.get(j);
                double h = Math.hypot(a.e - b.e, a.n - b.n), v = Math.abs(a.u - b.u);
                // under 1: inside the radius sideways AND the spacing (less its stated tolerance) in height
                if (v < params.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M) minSameHeight = Math.min(minSameHeight, h);
                if (h < params.radiusM()) minOverhead = Math.min(minOverhead, v);
                double score = Math.max(h / params.radiusM(), (v + ExitPlanner.VERTICAL_TOLERANCE_M) / params.layerM());
                if (score < worst) {
                    worst = score;
                    worstH = h;
                    worstV = v;
                    worstA = a.id;
                    worstB = b.id;
                    worstAtS = t();
                }
            }
        }
    }

    /** The closest approach so far: the pair nearest to losing separation (h / radius and v / spacing both small). */
    public ExitPlanner.Closest closest() {
        return worstA == null ? null : new ExitPlanner.Closest(worstA, worstB, worstH, worstV);
    }

    public double closestAtS() {
        return worstAtS;
    }

    public void resetClosest() {
        worst = Double.MAX_VALUE;
        worstA = worstB = null;
    }

    /** No pair has been inside the protection radius sideways AND the layer spacing in height. */
    public boolean separated() {
        ExitPlanner.Closest c = closest();
        return c == null || !c.loss(params);
    }

    // ------------------------------------------------------------------ the exit plans' proof

    /**
     * {@link ExitPlanner.Prover}: every follower where it is, moving with the group, cut off from the PC at once: it
     * brakes into LOITER and flies its plan by the phone's own rules (the 2 min hold left out - everybody holds still
     * through it, so the start is the same). Flat ground under the group's base; homes as the inputs say.
     */
    public static ExitPlanner.Prover prover(AirParams p, double groundAmsl) {
        return (centre, velocity, inputs, plans, leadAlt) -> {
            VirtualAirspace a = new VirtualAirspace(p, groundAmsl, 1);
            double[] c = GeoLocal.offset(a.lat0, a.lon0, centre.e(), centre.n());
            for (ExitPlanner.Input in : inputs) {
                V2 at = centre.plus(in.rel());
                VirtualCopter k = a.add(in.id(), at.e(), at.n(), in.altAmsl());
                k.ve = velocity.e();
                k.vn = velocity.n();
                k.homeAmsl = in.homeAmsl();
                ExitPlanner.Plan pl = plans.get(in.id());
                k.rallyE = pl.rally().e();
                k.rallyN = pl.rally().n();
                FollowerBrain b = a.follower(in.id());
                b.holdMs = 0;
                boolean still = velocity.length() < 0.05;
                b.order(new GroupOrder(still ? GroupOrder.FLAG_STOPPED : 0, 1, c[0], c[1], velocity.e(), velocity.n(), in.rel().e(), in.rel().n(),
                        in.altAmsl(), groundAmsl, 20, 0, 0, 0, 0, 0), 0);
                double[] rally = GeoLocal.offset(a.lat0, a.lon0, pl.rally().e(), pl.rally().n());
                b.plan(new ExitPlan(pl.bottomRow() ? ExitPlan.FLAG_BOTTOM_ROW : 0, 1, pl.laneEndRadiusM(), pl.startDelayS(),
                        pl.returnAltRelM(), rally[0], rally[1], pl.layerAltAmsl()));
            }
            a.pcAlive = false;
            for (int k = 0; k < 6000; k++) { // at most 10 min
                a.step();
                boolean all = true;
                for (VirtualCopter v : a.copters.values()) if (!v.landed) all = false;
                if (all) break;
            }
            return a.closest();
        };
    }
}
