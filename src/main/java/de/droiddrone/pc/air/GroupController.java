package de.droiddrone.pc.air;

import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.group.GroupOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The PC's follower group (PRD areas E and K): one lead flown by the pilot, its followers in a {@link HollowCube}.
 * {@link #tick} five times a second takes what the drones last reported and gives each follower's phone a
 * {@link GroupOrder} and, at least every {@link AirParams#refreshMs} and at every change, a proven {@link ExitPlan}.
 * Positions are metres east / north of the airspace's reference place (lat0, lon0); heights above sea level.
 * <p>
 * What it does, by the PRD:
 * <ul>
 * <li>Leash (FL-12, AC-18): the group stays while the lead is within the leash of its centre; past it, the whole group
 *   moves straight towards the lead and stops at the keeping distance, every follower given the same centre and
 *   velocity, accelerating within {@link AirParams#accelMs2}; slower when a follower falls behind its cell.</li>
 * <li>Give way (AC-19, AC-34, K4): the lead predicted 5 s ahead inside the keep-out zone: the whole group moves away
 *   from it as one, at up to its top speed; TRAFFIC. The same for another group's lead.</li>
 * <li>Stops (AC-40): any follower that is not following (stopped, LOITER, its link lost) stops the group; it moves on
 *   when that drone is normal again or the group is clear of it.</li>
 * <li>One drone cut off (AC-37): the group stops; after 30 s it moves 20 m away from it and carries on; until the drone
 *   has gone (its hold, its delay, its lane and its climb) the parts of the airspace near it and its lane are held for
 *   it and the group is kept from closing on it. Its link back before the move: it carries on; after: it rejoins like a
 *   reserve (AC-38). The PC can't hear it, but it has its exit plan: {@link #predicted} says where it is now - TRAFFIC
 *   for the pilot and ADS-B targets for the lead's own avoidance (AC-8, AC-11) - and a drone nobody flies (an old
 *   lead) is stepped out of its way ({@link Out#clear}). Safe choices: it is taken to hang where its brake stopped it,
 *   not where it was last heard; with wind (it may have lost GNSS too, unseen) the group moves out of its drift line
 *   at once - but only where a way clear of everyone exists, else the PRD's own rule.</li>
 * <li>One drone without GNSS (AC-42): the group moves away at once until its edge is clear of the drone's estimated
 *   place by a protection radius plus its error circle. Which way: 24 are played out 20 s ahead and the one that keeps
 *   every drone near its height furthest from it (and from any drone hanging cut off) is taken - "upwind and
 *   sideways" drags drones on the far side of a drifting one through its path, so it is not taken blindly. Two or more
 *   (AC-20, K8): GNSS lost for the group - those without GNSS land first, each once nothing is below it, the group
 *   kept out of their drift; then, the group holding still, the rest land column by column, bottom first.</li>
 * <li>A new lead (FL-5, FL-6, AC-7, AC-32, E3, E5): DISCARD LEAD or a lead silent 30 s: the bottom row's lowest
 *   battery drops into the abort layer and leaves by a corner exit; nobody else moves until it is clear; then it is the
 *   lead, and the battery order fills the gap.</li>
 * <li>LEAD DOWN (FL-4): the lead landed: the group holds where it is.</li>
 * <li>Reserves and rejoins (AC-5, AC-38, FA8): to above a free transit shaft in an approach layer of their own, down it
 *   to a new cell at the top.</li>
 * <li>Battery order (AC-30): at every enter or exit, or every 5 min; moves through the transit areas at low
 *   priority. 3 %: a bottom-row drone lands straight down; one above leaves for a shaft in time and lands down it
 *   (AC-24, AC-33, K10).</li>
 * <li>One by one (AC-8, "followers always give way"): every follower the PC flies gets, each tick, the velocity closest
 *   to the one that takes it to its place that keeps it clear of every drone near its height ({@link Orca}): half and
 *   half between two it flies, wholly against the lead, a drone cut off (where its plan has it; still, where its brake
 *   may have stopped it, and in wind its drift corridor), one without GNSS, one landing or near 3 % ({@link #nearCritical}),
 *   other groups'. Only sideways. Its order carries its place now and that velocity.</li>
 * <li>Every move is granted stage by stage ({@link TransitScheduler}); a mover predicted to lose separation with
 *   anyone ({@link ConflictPredictor}) holds where it is. While any move goes on the group is no faster than
 *   {@link AirParams#moveCapMs} (AC-39).</li>
 * <li>Floor (FL-14): changed live, never under 10 m; above 120 m the event says so.</li>
 * </ul>
 * Safe choices where the PRD leaves a gap are said where they are made.
 */
public final class GroupController {
    /** The PC hears nothing from a follower for this long: its link counts as lost (orders and reports at 5 Hz). */
    public static final long SILENT_MS = 1000;
    /** A follower above the bottom row at or under this goes to a shaft to land (3 % plus the way there, AC-24). */
    public static final double LOW_ABOVE_BOTTOM_PCT = 7;
    public static final double LAND_PCT = 3;
    /** How long out to a shaft and down it may take: a drone above the bottom row leaves its cell this long before 3 %. */
    public static final double SHAFT_LANDING_S = 90;
    /** Its battery reaches 3 % within {@link #LANDING_WARN_S} at the rate it drains: its FC may land it where it is soon. */
    private boolean nearCritical(Member m) {
        return m.battery < 50 && (m.battery - LAND_PCT) / Math.max(m.drainPerS, 1e-6) < LANDING_WARN_S;
    }

    /** Within this of 3 % a drone is taken as about to come down where it is: the drones under it move out first. */
    public static final double LANDING_WARN_S = 40;
    /** Within these of a point of a move: there. */
    public static final double REACHED_H_M = 2.0, REACHED_V_M = 1.5;
    /** Exit plans proven at most this often (a burst of changes is one proof). */
    public static final long PROOF_MIN_GAP_MS = 1000;

    /** What a drone last told the PC; pos in the airspace frame, heard at the PC's clock. */
    public record Report(String id, V2 pos, double altAmsl, V2 vel, double vu, double battery, boolean gnssOk, double errM,
                         long heardAt, boolean following, boolean landed, double homeAmsl) {}

    /** A drone of no concern to this group's orders that it must keep clear of (another group's, an old lead). */
    public record Other(String id, V2 pos, double altAmsl, V2 vel, boolean lead) {}

    /** What one tick says: orders, plans when (re)made, events, the lead's RTL height above its home. */
    public record Out(Map<String, GroupOrder> orders, Map<String, ExitPlan> plans, List<String> events, double leadRtlAltRelM,
                      String newLead, Map<String, V2> clear) {
        public Out(Map<String, GroupOrder> orders, Map<String, ExitPlan> plans, List<String> events, double leadRtlAltRelM, String newLead) {
            this(orders, plans, events, leadRtlAltRelM, newLead, Map.of());
        }
    }

    public enum State { IN_CELL, MOVING, APPROACH, LEAVING, LANDING, LOST, GNSS_LOST, GONE }

    /** One follower as the PC keeps it. */
    public static final class Member {
        public final String id;
        public State state;
        public BatteryOrder.Place place;
        List<HollowCube.Stage> stages = List.of();
        int stage = -1, point;
        Set<String> pendingRelease = Set.of();
        HollowCube.Point holdAt;
        State goal = State.IN_CELL;
        BatteryOrder.Place target;
        int dir;
        long lostAt = -1, gnssAt = -1, goneBy = -1;
        int lostColumn = -1;
        /** How fast its pack drains, % a second (smoothed); and when its battery was last read. */
        double drainPerS;
        long batteryAt = -1;
        HollowCube.Face joinFace;
        /** The last exit plan it was sent: what it flies if it is cut off. */
        ExitPlan plan;
        boolean shifted;
        double approachAlt = Double.NaN;
        V2 lastPos = V2.ZERO, lastVel = V2.ZERO, lostCentre;
        /** Where a cut-off drone was last heard (lastPos is then where its brake will have stopped it, at the most). */
        V2 heardPos;
        /** The offset from the centre its last order carried (its phone's exit lane runs that way); kept at its loss. */
        V2 sentOff, lostOff;
        double lastAlt, battery = 100, homeAmsl, errM;
        V2 rally;
        State before;

        Member(String id) {
            this.id = id;
        }

        public State state() {
            return state;
        }
    }

    private final AirParams base;
    private AirParams p;
    private HollowCube cube;
    private final double groundAmsl, lat0, lon0;
    private final TransitScheduler scheduler = new TransitScheduler();
    private ConflictPredictor predictor;
    private final ExitPlanner.Prover prover;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private V2 centre, vel = V2.ZERO, shiftTarget;
    private String leadId;
    private boolean leashing, landMode, leadDownSaid, traffic, trafficSaid, leaving, emergency;
    private long lastTick = -1, lastOrderCheck = -1, lastPlansAt = -1, lastProofAt = -1;
    private boolean changed = true;
    private int orderSeq, planSeq;
    private Map<String, ExitPlan> plans = new HashMap<>();
    private double leadRtlAltRel = Double.NaN;
    private V2 rallySite;
    private final Map<String, V2> blocked = new HashMap<>(); // lost drones' airspace held: id -> where it was

    /**
     * @param groundAmsl the highest ground under the group's area (its layers' base, AC-2)
     * @param lat0       the airspace frame's reference place
     * @param prover     flies a set of exit plans before it is sent; null: plans are not proven
     */
    public GroupController(AirParams p, double groundAmsl, double lat0, double lon0, ExitPlanner.Prover prover) {
        this.base = p;
        this.p = p;
        this.cube = new HollowCube(p);
        this.predictor = new ConflictPredictor(p);
        this.groundAmsl = groundAmsl;
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.prover = prover;
    }

    public AirParams params() {
        return p;
    }

    public HollowCube cube() {
        return cube;
    }

    public V2 centre() {
        return centre;
    }

    public V2 velocity() {
        return vel;
    }

    public String lead() {
        return leadId;
    }

    public Map<String, Member> members() {
        return members;
    }

    public TransitScheduler scheduler() {
        return scheduler;
    }

    /** A follower's cell in the airspace frame and its height (AMSL). */
    public double[] cellPlace(String id) {
        Member m = members.get(id);
        if (m == null || m.place == null) return null;
        V2 at = centre.plus(cube.cell(m.place.cell()));
        return new double[]{at.e(), at.n(), groundAmsl + cube.layerAlt(m.place.layer())};
    }

    /** The height (AMSL) of a cube layer (0 = the bottom row). */
    public double layerAmsl(int layer) {
        return groundAmsl + cube.layerAlt(layer);
    }

    /**
     * FOLLOW GROUP (FL-1): these followers (id -> battery) in cells by battery order round the given centre, the lead
     * named. The caller has them there (in the tests they start in their cells). Rally points round the site.
     */
    public void form(String lead, Map<String, Double> followers, V2 centre, V2 rallySite) {
        this.leadId = lead;
        this.centre = centre;
        this.rallySite = rallySite;
        Map<String, BatteryOrder.Place> none = new HashMap<>();
        for (String id : followers.keySet()) none.put(id, null);
        Map<String, BatteryOrder.Place> places = BatteryOrder.assign(new HashMap<>(none), followers);
        Map<String, Double> bearings = new HashMap<>();
        for (Map.Entry<String, BatteryOrder.Place> e : places.entrySet()) {
            Member m = new Member(e.getKey());
            m.state = State.IN_CELL;
            m.place = e.getValue();
            m.battery = followers.get(e.getKey());
            members.put(m.id, m);
            scheduler.hold(m.id, Set.of(HollowCube.cellRes(m.place.layer(), m.place.cell())));
            V2 c = cube.cell(m.place.cell());
            bearings.put(m.id, Math.toDegrees(Math.atan2(c.e(), c.n())));
        }
        Map<String, V2> rallies = RallyPoints.assign(bearings, centre, rallySite, p);
        for (Member m : members.values()) m.rally = rallies.get(m.id);
        changed = true;
    }

    /** CALL RESERVE's drone (AL-8, AC-5), up in its approach layer at (pos): it joins at the top. rally: its own. */
    public void addReserve(String id, double battery, V2 rally, double approachAltAmsl, long now) {
        Member m = new Member(id);
        m.state = State.APPROACH;
        m.battery = battery;
        m.rally = rally;
        m.goal = State.IN_CELL;
        members.put(id, m);
        m.target = topCell(m.id);
        m.approachAlt = Double.isNaN(approachAltAmsl) ? approachAlt(m) : approachAltAmsl;
        changed = true;
    }

    /** The approach height (AMSL) the next reserve called should climb to: a layer of its own over the group (AC-5). */
    public double nextApproachAlt() {
        return approachAlt(null);
    }

    /** The approach layer for a reserve: two spacings over the top layer and one more for each other approacher. */
    private double approachAlt(Member who) {
        int top = 0;
        for (Member m : members.values()) {
            if (m.place != null) top = Math.max(top, m.place.layer());
            if (m.target != null) top = Math.max(top, m.target.layer());
        }
        Set<Double> taken = new HashSet<>();
        for (Member m : members.values()) if (m != who && !Double.isNaN(m.approachAlt) && m.state == State.APPROACH) taken.add(m.approachAlt);
        double a = groundAmsl + cube.layerAlt(top) + 2 * p.layerM();
        while (taken.contains(a)) a += p.layerM();
        return a;
    }

    /** FA8: always a new cell at the top - a free one in the top layer, else the first of a new layer above. */
    private BatteryOrder.Place topCell(String forId) {
        int top = -1;
        Map<Integer, Set<Integer>> used = new HashMap<>();
        for (Member m : members.values()) {
            if (m.id.equals(forId) || m.state == State.GONE) continue;
            BatteryOrder.Place pl = m.target != null && m.state != State.IN_CELL ? m.target : m.place;
            if (pl == null) continue;
            top = Math.max(top, pl.layer());
            used.computeIfAbsent(pl.layer(), k -> new HashSet<>()).add(pl.cell());
        }
        if (top < 0) return new BatteryOrder.Place(0, 1);
        Set<Integer> u = used.getOrDefault(top, Set.of());
        for (int c = 0; c < HollowCube.CELLS; c++) if (!u.contains(c)) return new BatteryOrder.Place(top, c);
        return new BatteryOrder.Place(top + 1, 1);
    }

    /** DISCARD LEAD (E4, FL-6): the next lead leaves by the abort layer; returns its id, null when nobody can. */
    public String discardLead(long now) {
        if (leaving) return null;
        Map<String, BatteryOrder.Place> places = new HashMap<>();
        Map<String, Double> batt = new HashMap<>();
        for (Member m : members.values()) {
            if (m.state == State.IN_CELL && m.place != null) {
                places.put(m.id, m.place);
                batt.put(m.id, m.battery);
            }
        }
        String next = BatteryOrder.nextLead(places, batt);
        if (next == null) return null;
        Member m = members.get(next);
        scheduler.releaseAll(m.id);
        m.state = State.LEAVING;
        m.goal = State.GONE;
        m.stages = List.of(cube.abortExit(m.place.cell()));
        m.stage = -1;
        m.point = 0;
        m.holdAt = new HollowCube.Point(cube.cell(m.place.cell()), cube.layerAlt(m.place.layer()));
        leaving = true;
        changed = true;
        return next;
    }

    /** FL-14: one floor for the group, live; never under 10 m. Returns the floor taken. */
    public double setFloor(double floorM) {
        double f = Math.max(10, floorM);
        p = new AirParams(p.cellM(), p.layerM(), p.radiusM(), p.keepOutM(), p.leashM(), p.keepM(), f, p.moveCapMs(), p.catchUpMs(),
                p.accelMs2(), p.lostWaitMs(), p.lostMoveM(), p.holdMs(), p.refreshMs(), p.leadSilentMs(), p.orderCheckMs(),
                p.transitWaitMs(), p.ceilingM(), p.brakeMs2());
        cube = new HollowCube(p);
        predictor = new ConflictPredictor(p);
        changed = true;
        return f;
    }

    // ------------------------------------------------------------------ the tick

    public Out tick(long now, Map<String, Report> reports, Report lead, List<Other> others, V2 wind) {
        double dt = lastTick < 0 ? 0.2 : Math.min(1.0, (now - lastTick) / 1000.0);
        lastTick = now;
        List<String> events = new ArrayList<>();
        String newLead = null;
        updateMembers(now, reports, events);
        // the lead
        boolean leadFresh = lead != null && now - lead.heardAt() <= SILENT_MS;
        if (lead != null && lead.landed()) {
            if (!leadDownSaid) events.add("LEAD DOWN");
            leadDownSaid = true;
        } else {
            leadDownSaid = false;
        }
        if (lead != null && now - lead.heardAt() > p.leadSilentMs() && !leaving) {
            String taker = discardLead(now);
            if (taker != null) events.add("LEAD TAKEN OVER " + taker);
        }
        // the centre
        V2 want = centreVelocity(now, lead, leadFresh, others, wind, events);
        boolean moving = members.values().stream().anyMatch(m -> m.state == State.MOVING || m.state == State.LEAVING
                || m.state == State.APPROACH && m.stage >= 0 || m.state == State.LANDING);
        if (moving && !landMode) want = want.capped(p.moveCapMs());
        // a drone in the group that may stop or come down any moment (near 3 %, landing, not flying as told, cut off): the
        // group no faster than during a move, so the others have time to get round it
        boolean frail = members.values().stream().anyMatch(m -> m.state == State.LOST || m.state == State.LANDING
                || m.state != State.GONE && (nearCritical(m) || !followingNow.getOrDefault(m.id, true)));
        if (frail) want = want.capped(p.moveCapMs());
        if (!landMode && behind(reports) && !traffic) want = want.times(0.5);
        V2 dv = want.minus(vel).capped((emergency ? p.brakeMs2() : p.accelMs2()) * dt);
        vel = vel.plus(dv);
        centre = centre.plus(vel.times(dt));
        // moves
        runMoves(now, reports, lead, others, events);
        for (Member m : members.values()) {
            if (m.state == State.LEAVING && m.stage >= 0 && m.stage == m.stages.size()) {
                m.state = State.GONE;
                scheduler.releaseAll(m.id);
                leadId = m.id;
                leaving = false;
                newLead = m.id;
                events.add("NEW LEAD " + m.id);
                changed = true;
            }
        }
        if (!landMode && !leaving && (changed || lastOrderCheck < 0 || now - lastOrderCheck >= p.orderCheckMs())) orderCheck(now);
        // orders
        leadTraffic(now, lead, reports, others, events);
        forceLanding(reports, others, wind, events);
        Map<String, GroupOrder> orders = orders(now, lead, leadFresh, events, reports, others, wind);
        // exit plans
        Map<String, ExitPlan> sent = null;
        if ((changed || lastPlansAt < 0 || now - lastPlansAt >= p.refreshMs()) && (lastProofAt < 0 || now - lastProofAt >= PROOF_MIN_GAP_MS)) {
            sent = makePlans(now, reports, lead, events);
            changed = false;
        }
        members.values().removeIf(m -> m.state == State.GONE && m.goneBy < 0);
        return new Out(orders, sent, events, leadRtlAltRel, newLead, clearOthers(now, others, events));
    }

    private void updateMembers(long now, Map<String, Report> reports, List<String> events) {
        int gnssLost = 0;
        for (Member m : members.values()) {
            Report r = reports.get(m.id);
            boolean fresh = r != null && now - r.heardAt() <= SILENT_MS;
            if (r != null) landedNow.put(m.id, r.landed());
            if (r != null && fresh) {
                m.lastPos = r.pos();
                m.lastVel = r.vel();
                m.lastAlt = r.altAmsl();
                if (m.batteryAt > 0 && now > m.batteryAt) { // how fast its pack drains, smoothed (AC-24's "in time")
                    double rate = Math.max(0, (m.battery - r.battery()) / ((now - m.batteryAt) / 1000.0));
                    m.drainPerS = 0.8 * m.drainPerS + 0.2 * rate;
                }
                m.batteryAt = now;
                m.battery = r.battery();
                m.homeAmsl = r.homeAmsl();
                m.errM = r.errM();
            }
            if (fresh && r.landed() && m.state != State.APPROACH) {
                if (m.state != State.GONE) events.add("LANDED " + m.id);
                scheduler.releaseAll(m.id);
                m.state = State.GONE;
                changed = true;
                continue;
            }
            switch (m.state) {
                case GONE, LEAVING -> {
                }
                case LOST -> {
                    if (fresh) {
                        if (landMode) { // the group lands (AC-20): it holds where it is and lands in its turn
                            scheduler.releaseAll(m.id);
                            m.state = State.APPROACH;
                            m.place = null;
                            m.stages = List.of();
                            m.stage = -1;
                            m.holdAt = new HollowCube.Point(m.lastPos.minus(centre), m.lastAlt - groundAmsl);
                            events.add("LINK BACK " + m.id);
                        } else if (!m.shifted) { // back within the wait: it carries on where it was (AC-37)
                            m.state = m.before;
                            events.add("LINK BACK " + m.id);
                        } else { // the group has moved: it comes back like a reserve (AC-38)
                            rejoin(m, events);
                        }
                        m.lostAt = -1;
                        unblock(m.id);
                        changed = true;
                    } else if (m.goneBy > 0 && now > m.goneBy) {
                        m.state = State.GONE;
                        m.goneBy = -1;
                        unblock(m.id);
                        changed = true;
                    }
                }
                case GNSS_LOST -> {
                    gnssLost++;
                    if (fresh && r.gnssOk() && !landMode) {
                        events.add("GNSS BACK " + m.id);
                        unblock(m.id);
                        rejoin(m, events);
                        changed = true;
                    }
                }
                default -> {
                    if (!fresh) {
                        m.before = m.state == State.LANDING ? State.LANDING : m.state;
                        m.state = State.LOST;
                        // its phone brakes it into LOITER: it comes to rest a braking distance on from where it was last heard
                        // (its phone flies on for its silence threshold before it brakes)
                        m.heardPos = m.lastPos;
                        m.lostOff = m.sentOff;
                        double sp = m.lastVel.length();
                        m.lastPos = m.lastPos.plus(m.lastVel.times(SILENT_MS / 1000.0)).plus(m.lastVel.unit().times(sp * sp / (2 * p.brakeMs2())));
                        m.lastVel = V2.ZERO;
                        m.lostAt = now - (r == null ? 0 : Math.min(now - r.heardAt(), SILENT_MS));
                        m.lostCentre = centre;
                        m.shifted = false;
                        m.goneBy = -1;
                        events.add("LINK LOST " + m.id);
                        changed = true;
                    } else if (!r.gnssOk()) {
                        m.state = State.GNSS_LOST;
                        m.gnssAt = now;
                        scheduler.releaseAll(m.id);
                        m.lostColumn = m.place != null ? m.place.cell() : -1;
                        m.place = null;
                        events.add("GNSS LOST " + m.id);
                        gnssLost++;
                        changed = true;
                    } else if (m.state == State.IN_CELL && m.place != null) {
                        if (m.place.layer() == 0 && m.battery <= LAND_PCT) {
                            startLanding(m, true);
                            events.add("LAND 3% " + m.id);
                        } else if (m.place.layer() > 0 && (m.battery <= LOW_ABOVE_BOTTOM_PCT
                                || m.battery - m.drainPerS * SHAFT_LANDING_S <= LAND_PCT + 1)) { // AC-24: in time, at its drain rate
                            startLanding(m, false);
                            events.add("LAND 3% " + m.id);
                        }
                    }
                }
            }
        }
        if (gnssLost >= 2 && !landMode) {
            landMode = true;
            events.add("GNSS LOST FOR THE GROUP");
            for (Member m : members.values()) {
                if (m.state == State.MOVING || m.state == State.APPROACH || m.state == State.LANDING) {
                    scheduler.releaseAll(m.id);
                }
            }
        }
    }

    private void rejoin(Member m, List<String> events) {
        scheduler.releaseAll(m.id);
        m.state = State.APPROACH;
        m.place = null;
        m.target = topCell(m.id);
        m.approachAlt = approachAlt(m);
        m.stages = List.of();
        m.stage = -1;
        m.goal = State.IN_CELL;
        events.add("REJOIN " + m.id);
    }

    private void startLanding(Member m, boolean bottom) {
        scheduler.releaseAll(m.id);
        m.state = State.LANDING;
        m.goal = State.GONE;
        HollowCube.Face f = HollowCube.faces(m.place.cell()).get(0);
        m.stages = bottom ? List.of(cube.landBottom(m.place.cell())) : cube.landThroughShaft(m.place.layer(), m.place.cell(), f);
        m.stage = -1;
        m.point = 0;
        m.holdAt = new HollowCube.Point(cube.cell(m.place.cell()), cube.layerAlt(m.place.layer()));
        changed = true;
    }

    /** Some follower in its cell is more than half the cell margin behind it (AC-18): the group slows. */
    private boolean behind(Map<String, Report> reports) {
        double margin = (p.cellM() - p.radiusM()) / 2;
        for (Member m : members.values()) {
            if (m.state != State.IN_CELL || m.place == null) continue;
            Report r = reports.get(m.id);
            if (r == null) continue;
            V2 want = centre.plus(cube.cell(m.place.cell()));
            if (r.pos().dist(want) > margin / 2 + vel.length() * 0.3) return true;
        }
        return false;
    }

    private V2 centreVelocity(long now, Report lead, boolean leadFresh, List<Other> others, V2 wind, List<String> events) {
        traffic = false;
        emergency = false;
        V2 want = V2.ZERO;
        boolean stop = leaving || lead != null && lead.landed();
        Member lost = null;
        for (Member m : members.values()) {
            if (m.state == State.LOST && !m.shifted) {
                if (now - m.lostAt < p.lostWaitMs()) stop = true;
                else lost = m;
            }
        }
        if (lost != null && !landMode) { // AC-37: 20 m sideways away from it, once
            V2 away = centre.minus(lost.lastPos);
            shiftTarget = centre.plus(away.unit().times(p.lostMoveM()));
            leaveBehind(lost, events);
        }
        // a follower that stopped (AC-40)
        for (Member m : members.values()) {
            if (m.state == State.IN_CELL && m.place != null && !followingNow.getOrDefault(m.id, true)) stop = true;
        }
        List<Member> drifting = new ArrayList<>();
        for (Member m : members.values()) if (m.state == State.GNSS_LOST && !landedNow.getOrDefault(m.id, false)) drifting.add(m);
        // a drone cut off may have lost GNSS too - nobody could tell: with wind, the group does not stay in its way
        if (wind.length() >= 1 && vel.length() < 1) { // after the stop for it (AC-40): the shape is aligned again
            for (Member m : members.values()) {
                if (m.state != State.LOST) continue;
                m.errM = 2 + 0.5 * (now - m.lostAt) / 1000.0;
                if (escapeScore(V2.ZERO, List.of(m), wind) < p.radiusM() + 5) drifting.add(m);
            }
        }
        V2 escape = driftEscape(drifting, wind, lead != null && leadFresh && !lead.landed() ? lead.pos() : null);
        boolean onlyMaybe = drifting.stream().allMatch(m -> m.state == State.LOST);
        if (escape != null && onlyMaybe && lastEscapeScore < p.radiusM() + 5) {
            // a precaution only where a way that is clear of everyone exists: else the PRD's own rule (stopped, 30 s, 20 m)
            escape = null;
            lastEscape = null;
        }
        if (escape != null) for (Member m : drifting) if (m.state == State.LOST && !m.shifted) leaveBehind(m, events);
        if (escape != null) { // AC-42: at once, out of its drift line, then upwind
            want = escape;
            emergency = true;
        } else if (landMode) {
            want = V2.ZERO; // the drones that can hold a place hold it while the group lands (AC-20)
        } else if (shiftTarget != null) {
            V2 d = shiftTarget.minus(centre);
            if (d.length() < 0.5) shiftTarget = null;
            else want = d.unit().times(Math.min(p.moveCapMs(), Math.sqrt(2 * p.accelMs2() * d.length())));
        } else if (!stop && lead != null && leadFresh && !lead.landed()) {
            V2 rel = lead.pos().minus(centre);
            double d = rel.length();
            if (!leashing && d > p.leashM()) leashing = true;
            if (leashing) {
                V2 to = lead.pos().minus(rel.unit().times(p.keepM()));
                double togo = to.minus(centre).length();
                if (togo < 1 || d <= p.keepM()) leashing = false;
                else want = to.minus(centre).unit().times(Math.min(p.catchUpMs(), Math.sqrt(2 * p.accelMs2() * togo)));
            }
        }
        // give way to a lead (ours or another group's) that will be inside the keep-out zone within the time the group
        // needs to stop and get away: its motion relative to the group's, looked at every half second (AC-34, K4)
        double horizon = 5 + vel.length() / p.brakeMs2();
        List<V2[]> leads = new ArrayList<>();
        if (lead != null && leadFresh && !lead.landed()) leads.add(new V2[]{lead.pos(), lead.vel()});
        for (Other o : others) if (o.lead()) leads.add(new V2[]{o.pos(), o.vel()});
        for (V2[] l : leads) {
            // a drifting drone is the nearer danger: the keep-out buffer gives way to the escape unless the lead is at the edge
            if (escape != null && cube.outsideEdge(l[0].minus(centre)) > p.radiusM() + 5) continue;
            V2 rel0 = l[0].minus(centre), relV = l[1].minus(vel);
            for (double t = 0; t <= horizon + 1e-9; t += 0.5) {
                V2 rel = rel0.plus(relV.times(t));
                if (cube.outsideEdge(rel) < p.keepOutM()) {
                    V2 away = rel0.length() < 1 ? relV.unit().times(-1) : rel0.unit().times(-1);
                    // sideways too when it comes straight on: off its path, not only ahead of it
                    V2 side = relV.length() > 0.5 ? relV.unit().left() : V2.ZERO;
                    if (side.dot(rel0) > 0) side = side.times(-1);
                    want = away.plus(side.times(0.5)).unit().times(p.catchUpMs());
                    traffic = true;
                    emergency = true;
                    break;
                }
            }
        }
        if (traffic && !trafficSaid) events.add("TRAFFIC"); // once as it starts: the OSD shows it while it lasts
        trafficSaid = traffic;
        if (!traffic && stop && escape == null) {
            want = V2.ZERO;
            emergency = true;
        }
        // never close on what must be kept clear: a lost drone's place and lane, a drone without GNSS, other drones -
        // looked at from as far out as the group needs to stop
        List<double[]> keep = new ArrayList<>(); // e, n, radius
        for (Member m : members.values()) {
            if (m.state == State.LOST && m.shifted) {
                V2 out = m.lastPos.minus(m.lostCentre == null ? centre : m.lostCentre).unit();
                for (int k = 0; k <= 10; k++) {
                    V2 at = m.lastPos.plus(out.times(k * p.laneEndBaseM() / 10));
                    keep.add(new double[]{at.e(), at.n(), p.radiusM() + 5});
                }
            }
            if (m.state == State.GNSS_LOST && escape == null) keep.add(new double[]{m.lastPos.e(), m.lastPos.n(), p.radiusM() + m.errM + 5});
        }
        for (Other o : others) if (!o.lead()) keep.add(new double[]{o.pos().e(), o.pos().n(), p.radiusM() + 5});
        for (double[] k : keep) {
            V2 rel = new V2(k[0], k[1]).minus(centre);
            V2 to = rel.unit();
            double closing = Math.max(0, vel.dot(to));
            double margin = 20 + closing * closing / (2 * p.brakeMs2()) + closing * 0.5;
            double gap = cube.outsideEdge(rel) - k[2];
            if (gap < margin) {
                double comp = want.dot(to);
                if (comp > 0) {
                    want = want.minus(to.times(comp));
                    emergency = true;
                }
            }
        }
        return want.capped(p.catchUpMs());
    }

    /**
     * The group moves off and leaves a cut-off drone where it hangs: its cell is free, the airspace round it and its lane
     * is held for it until it has gone, and if its link comes back it joins again like a reserve (AC-37, AC-38).
     */
    /** When a cut-off drone's plan has it down at its rally point (and a minute more); MAX without a plan. */
    private long landedBy(Member m) {
        if (m.plan == null) return Long.MAX_VALUE;
        for (long t = m.lostAt; t < m.lostAt + 3_600_000; t += 5000) {
            double[] at = predicted(m.id, t);
            if (at != null && at[2] <= m.homeAmsl + 0.5 && t > m.lostAt + p.holdMs()) return t + 60_000;
        }
        return Long.MAX_VALUE;
    }

    private void leaveBehind(Member lost, List<String> events) {
        lost.shifted = true;
        lost.goneBy = landedBy(lost); // by its plan; without one it holds where it is and is never taken as gone
        if (lost.place != null) scheduler.release(lost.id, Set.of(HollowCube.cellRes(lost.place.layer(), lost.place.cell())));
        blocked.put(lost.id, lost.lastPos);
        events.add("MOVE AWAY FROM " + lost.id);
    }

    /** Whether each drone without GNSS was last reported on the ground. */
    private final Map<String, Boolean> landedNow = new HashMap<>();

    /**
     * AC-42 (and AC-20's drifting drones): the velocity that keeps the group out of the way of drones that drift with
     * the wind, or null when they are clear of it. A drone without GNSS holds its height and drifts downwind; only the
     * drones near its height can meet it (every other layer is a spacing or more above or below). Which way is safe
     * depends on where it is: a drifting drone on the group's downwind side - move upwind; on the upwind side - run
     * downwind ahead of it; beside it - sideways. "Upwind and sideways" is not always the safe way: moving a group
     * sideways drags the drones on the far side of a drifting one right through its path. So 24 ways are tried, each
     * played out for {@link #ESCAPE_LOOK_S} seconds - the group speeding up from its velocity now at its braking rate,
     * the drifting drone speeding up towards the wind - and the one that keeps every drone near its height furthest
     * from it is taken (not towards the lead when two are as good). Done when its estimated place plus its error circle
     * is a protection radius outside the group's edge.
     */
    private V2 driftEscape(List<Member> drifting, V2 wind, V2 leadPos) {
        if (drifting.isEmpty()) return null;
        boolean near = false;
        for (Member d : drifting) if (cube.outsideEdge(d.lastPos.minus(centre)) < p.radiusM() + d.errM + 5) near = true;
        if (!near) {
            lastEscape = null;
            return null;
        }
        V2 best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int k = 0; k < 24; k++) {
            double ang = Math.toRadians(k * 15);
            V2 dir = new V2(Math.sin(ang), Math.cos(ang));
            double score = escapeScore(dir, drifting, wind);
            if (leadPos != null) score -= 0.01 * Math.max(0, dir.dot(leadPos.minus(centre).unit())); // a tie: away from the lead
            if (lastEscape != null && dir.dot(lastEscape) > 0.99) score += 0.5; // keep a way once taken unless another is clearly better
            if (score > bestScore) {
                bestScore = score;
                best = dir;
            }
        }
        lastEscape = best;
        lastEscapeScore = bestScore;
        return best.times(p.catchUpMs());
    }

    public static final double ESCAPE_LOOK_S = 20;
    private V2 lastEscape;
    private double lastEscapeScore;

    /**
     * The smallest distance, over the look ahead, between a hazard and any drone near its height, the group taking
     * this way: drones without GNSS drifting towards the wind; cut-off drones that may drift (with wind) or hang still
     * at their rest point - both played out; every other cut-off drone hanging still where its brake stopped it (a
     * group escaping one danger must not run its drones into another).
     */
    private double escapeScore(V2 dir, List<Member> drifting, V2 wind) {
        double dt = 0.25;
        int steps = (int) (ESCAPE_LOOK_S / dt) + 1;
        V2[] gp = new V2[steps];
        V2 gv = vel, at = V2.ZERO;
        for (int i = 0; i < steps; i++) {
            gp[i] = at;
            gv = gv.plus(dir.times(p.catchUpMs()).minus(gv).capped(p.brakeMs2() * dt));
            at = at.plus(gv.times(dt));
        }
        double worst = Double.MAX_VALUE;
        List<Member> hazards = new ArrayList<>(drifting);
        for (Member m : members.values()) if (m.state == State.LOST && !hazards.contains(m)) hazards.add(m);
        for (Member m : members.values()) { // and one its own FC is landing where it is: it will not move aside
            if (!hazards.contains(m) && m.state != State.GONE && m.state != State.LOST && (!followingNow.getOrDefault(m.id, true)
                    || m.state == State.LANDING || nearCritical(m))) hazards.add(m); // or about to: it may stop and come down any moment
        }
        for (Member d : hazards) {
            boolean drifts = drifting.contains(d);
            List<V2> mates = new ArrayList<>();
            for (Member o : members.values()) {
                if (o == d || o.state == State.GONE || o.state == State.LOST || landedNow.getOrDefault(o.id, false)) continue;
                if (drifting.contains(o)) continue; // drifts itself: not moved by the group
                double dz = o.lastAlt - d.lastAlt;
                boolean sameHeight = Math.abs(dz) < p.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M;
                boolean underItsLanding = dz < 0 && landing.contains(d.id); // below it: only if it comes down
                if (!sameHeight && !underItsLanding) continue;
                mates.add(o.lastPos.minus(centre));
            }
            if (mates.isEmpty()) continue;
            V2 still = d.lastPos.minus(centre), dp = still, dv = V2.ZERO;
            for (int i = 0; i < steps; i++) {
                for (V2 m : mates) {
                    V2 there = m.plus(gp[i]);
                    if (drifts) worst = Math.min(worst, dp.dist(there) - d.errM);
                    if (!drifts || d.state == State.LOST) worst = Math.min(worst, still.dist(there) - 2);
                }
                if (drifts) {
                    dv = dv.plus(wind.minus(dv).capped(3 * dt));
                    dp = dp.plus(dv.times(dt));
                }
            }
        }
        return worst;
    }

    /** Whether each in-cell follower is flying as told (GUIDED, the phone following), from the last reports. */
    private final Map<String, Boolean> followingNow = new HashMap<>();

    // ------------------------------------------------------------------ moves

    private void runMoves(long now, Map<String, Report> reports, Report lead, List<Other> others, List<String> events) {
        followingNow.clear();
        for (Member m : members.values()) {
            Report r = reports.get(m.id);
            if (r != null) followingNow.put(m.id, r.following());
        }
        if (landMode) {
            landColumns(reports);
            return;
        }
        // the conflict guard's tracks
        List<ConflictPredictor.Track> tracks = new ArrayList<>();
        if (lead != null) tracks.add(new ConflictPredictor.Track(leadId == null ? "lead" : leadId, lead.pos().e(), lead.pos().n(), lead.altAmsl(),
                lead.vel().e(), lead.vel().n(), lead.vu(), 0));
        for (Member m : members.values()) {
            Report r = reports.get(m.id);
            if (r == null || r.landed() || m.state == State.GONE && m.goneBy < 0) continue;
            // who can give way: only a drone on a move. One in its cell moves with the group, one cut off or without GNSS
            // can't: they count as the lead does (never moved), and a mover gives way to them
            int prio = switch (m.state) {
                case LEAVING, LANDING -> TransitScheduler.PRIO_LANDING;
                case MOVING, APPROACH -> TransitScheduler.PRIO_TRANSIT;
                default -> TransitScheduler.PRIO_LEAD;
            };
            if (m.state == State.LOST) { // not heard: where its brake stopped it, still (the PC knows no more)
                V2 v = vel.times(-1);
                tracks.add(new ConflictPredictor.Track(m.id, m.lastPos.e() - centre.e(), m.lastPos.n() - centre.n(), m.lastAlt, v.e(), v.n(), 0, prio));
                continue;
            }
            // in-cell drones move with the group: predicted relative to it, they stand still
            V2 v = r.vel().minus(vel);
            tracks.add(new ConflictPredictor.Track(m.id, r.pos().e() - centre.e(), r.pos().n() - centre.n(), r.altAmsl(), v.e(), v.n(), r.vu(), prio));
        }
        if (lead != null) { // the lead too, relative to the group
            ConflictPredictor.Track l = tracks.get(0);
            tracks.set(0, new ConflictPredictor.Track(l.id(), l.e() - centre.e(), l.n() - centre.n(), l.u(), l.ve() - vel.e(), l.vn() - vel.n(), l.vu(), 0));
        }
        for (Other o : others) tracks.add(new ConflictPredictor.Track(o.id(), o.pos().e() - centre.e(), o.pos().n() - centre.n(), o.altAmsl(),
                o.vel().e() - vel.e(), o.vel().n() - vel.n(), 0, o.lead() ? 0 : 1));
        Set<String> giveWay = new HashSet<>();
        for (ConflictPredictor.Conflict c : predictor.predict(tracks)) if (c.inS() <= 3) giveWay.add(c.givesWay());
        // blocked airspace of lost drones: held under their names so nobody is granted it
        for (Map.Entry<String, V2> b : blocked.entrySet()) holdNear("held:" + b.getKey(), b.getValue().minus(centre));
        // ask for the next stage of every mover
        for (Member m : members.values()) {
            boolean mover = m.state == State.MOVING || m.state == State.APPROACH || m.state == State.LEAVING || m.state == State.LANDING;
            if (!mover) continue;
            if (leaving && m.state != State.LEAVING) continue; // AC-7: nobody else moves while the new lead leaves
            if (m.state == State.APPROACH && m.stages.isEmpty()) {
                if (reports.get(m.id) == null) continue; // not heard yet: where it is is not known
                planJoin(m);
            }
            if (m.stage < 0 || m.stage < m.stages.size() && stageDone(m, reports.get(m.id))) {
                // its stage is done: it has passed that stage's first point, so what the stage before held is behind it -
                // let go before anyone is granted anything (else it could take a shaft back that another waits for)
                if (m.stage >= 0 && !m.pendingRelease.isEmpty()) {
                    scheduler.release(m.id, m.pendingRelease);
                    m.pendingRelease = Set.of();
                }
                int next = m.stage + 1;
                if (next < m.stages.size()) {
                    int prio = m.state == State.LEAVING ? TransitScheduler.PRIO_LEAD : m.state == State.LANDING ? TransitScheduler.PRIO_LANDING
                            : m.state == State.APPROACH ? TransitScheduler.PRIO_TRANSIT : TransitScheduler.PRIO_WAITING;
                    scheduler.ask(m.id, m.stages.get(next), prio, now);
                } else if (m.stage >= 0 && m.stage < m.stages.size()) {
                    m.stage = m.stages.size(); // done
                }
            }
        }
        Set<String> granted = scheduler.decide();
        for (Member m : members.values()) {
            if (granted.contains(m.id)) {
                Set<String> before = m.stage >= 0 && m.stage < m.stages.size() ? m.stages.get(m.stage).resources() : Set.of();
                // what the stage before last still had to let go of: it has passed it now (its stage is done) - but not what
                // the new stage holds again
                Set<String> old = new HashSet<>(m.pendingRelease);
                old.removeAll(m.stages.get(m.stage + 1).resources());
                scheduler.release(m.id, old);
                m.stage++;
                m.point = 0;
                Set<String> rel = new HashSet<>(before);
                rel.removeAll(m.stages.get(m.stage).resources());
                m.pendingRelease = rel;
                changed = true;
            }
        }
        // fly the granted stages
        for (Member m : members.values()) {
            boolean mover = m.state == State.MOVING || m.state == State.APPROACH || m.state == State.LEAVING || m.state == State.LANDING;
            if (!mover || m.stage < 0 || m.stage >= m.stages.size()) {
                if (mover && m.stage >= m.stages.size() && m.stage >= 0) finishMove(m, events);
                else if (mover && m.stage < 0 && scheduler.waitedMs(m.id, now) > p.transitWaitMs()) {
                    if (m.stage < 0) scheduler.forgetWait(m.id);
                }
                continue;
            }
            Report r = reports.get(m.id);
            HollowCube.Stage s = m.stages.get(m.stage);
            HollowCube.Point target = s.points().get(Math.min(m.point, s.points().size() - 1));
            if (r != null && reached(r, target)) {
                if (m.point == 0 && !m.pendingRelease.isEmpty()) {
                    scheduler.release(m.id, m.pendingRelease);
                    m.pendingRelease = Set.of();
                }
                m.holdAt = target;
                if (m.point < s.points().size() - 1) m.point++;
            }
            // a hop waited too long: the other way round (AC-29)
            if (m.state == State.MOVING && m.stage + 1 < m.stages.size() - 1 && stageDone(m, r)
                    && scheduler.waitedMs(m.id, now) > p.transitWaitMs() && m.target != null) {
                reroute(m);
                scheduler.forgetWait(m.id);
                events.add("OTHER WAY ROUND " + m.id);
            }
            // in a shaft, waiting to come in (its lane or its cell busy - the drone in that cell may need this very shaft to
            // leave): out beyond the group at its own height, the shaft let go, and back for it - no drone ever holds a
            // shaft while it waits for something a shaft's user holds
            if ((m.state == State.MOVING || m.state == State.APPROACH) && m.stage >= 0 && m.stage == m.stages.size() - 2 && stageDone(m, r)
                    && scheduler.waitedMs(m.id, now) > p.transitWaitMs()) {
                HollowCube.Face at = null;
                for (String res : scheduler.heldBy(m.id)) if (res.startsWith("shaft:")) at = HollowCube.Face.valueOf(res.substring(6));
                if (at != null) {
                    double alt = m.holdAt.alt();
                    HollowCube.Stage aside = new HollowCube.Stage(Set.of("wait:" + at + ":" + Math.round(alt)),
                            List.of(new HollowCube.Point(cube.waitPoint(at), alt)), false, false);
                    HollowCube.Stage back = new HollowCube.Stage(Set.of(HollowCube.shaftRes(at)), List.of(new HollowCube.Point(cube.shaft(at), alt)), true, false);
                    List<HollowCube.Stage> redo = new ArrayList<>(m.stages.subList(0, m.stage + 1));
                    redo.add(aside);
                    redo.add(back);
                    redo.add(m.stages.get(m.stages.size() - 1));
                    m.stages = redo;
                    scheduler.forgetWait(m.id);
                    events.add("STEP ASIDE " + m.id);
                }
            }
        }
        this.giveWayNow = giveWay;
    }

    private Set<String> giveWayNow = Set.of();

    private boolean stageDone(Member m, Report r) {
        if (m.stage < 0) return true;
        if (m.stage >= m.stages.size()) return true;
        HollowCube.Stage s = m.stages.get(m.stage);
        return r != null && m.point == s.points().size() - 1 && reached(r, s.points().get(m.point));
    }

    private boolean reached(Report r, HollowCube.Point pt) {
        V2 at = centre.plus(pt.at());
        double alt = groundAmsl + pt.alt();
        if (pt.alt() <= 0) return r.landed() || r.altAmsl() - groundAmsl < 1.5 && r.pos().dist(at) < REACHED_H_M;
        return r.pos().dist(at) < REACHED_H_M && Math.abs(r.altAmsl() - alt) < REACHED_V_M;
    }

    private void finishMove(Member m, List<String> events) {
        switch (m.goal) {
            case IN_CELL -> {
                scheduler.releaseAll(m.id);
                boolean joined = m.state == State.APPROACH;
                m.state = State.IN_CELL;
                m.place = m.target;
                m.target = null;
                scheduler.hold(m.id, Set.of(HollowCube.cellRes(m.place.layer(), m.place.cell())));
                m.approachAlt = Double.NaN;
                m.stages = List.of();
                m.stage = -1;
                events.add((joined ? "IN GROUP " : "IN CELL ") + m.id);
                changed = true;
            }
            case GONE -> {
                if (m.state == State.LANDING) { // the land itself: by the order's flag, the resources held until down
                    m.stage = m.stages.size();
                }
            }
            default -> {
            }
        }
    }

    private void planJoin(Member m) {
        // the shaft nearest the reserve, else any: the scheduler picks among the free ones by asking each in turn
        V2 rel = m.lastPos.minus(centre);
        // a shaft on its new cell's own face (any other would take it along the lane across a neighbour's cell), the one
        // fewer other joiners are headed for, the nearer of those
        HollowCube.Face best = HollowCube.faces(m.target.cell()).get(0);
        double bestScore = Double.MAX_VALUE;
        for (HollowCube.Face f : HollowCube.faces(m.target.cell())) {
            long others = members.values().stream().filter(o -> o != m && o.state == State.APPROACH && o.joinFace == f).count();
            double score = others * 10_000 + rel.dist(cube.shaft(f));
            if (score < bestScore) {
                bestScore = score;
                best = f;
            }
        }
        m.joinFace = best;
        List<HollowCube.Stage> s = new ArrayList<>();
        // first up in its own column to its approach layer, the airspace round it held (a rejoin from inside the edge)
        double climbTo = m.approachAlt - groundAmsl;
        if (m.lastAlt < m.approachAlt - 1) {
            // inside the group's edge (a rejoin: it hung where its cell was): first out sideways in its own layer, past
            // the edge, and only there up - a height change never over or under anyone (AC-4)
            V2 up = rel;
            if (cube.outsideEdge(rel) < p.radiusM() + 3) {
                up = (rel.length() < 0.5 ? new V2(0, 1) : rel.unit()).times(p.edgeM() * Math.sqrt(2) + 2 * p.radiusM());
                Set<String> out = new HashSet<>(resourcesNear(rel));
                out.add("out:" + m.id);
                s.add(new HollowCube.Stage(out, List.of(new HollowCube.Point(up, m.lastAlt - groundAmsl)), false, false));
            }
            Set<String> near = new HashSet<>(resourcesNear(up));
            near.add("climb:" + m.id);
            s.add(new HollowCube.Stage(near, List.of(new HollowCube.Point(up, climbTo)), false, false));
        }
        s.addAll(cube.join(best, climbTo, m.target.layer(), m.target.cell()));
        m.stages = s;
        m.stage = -1;
        m.point = 0;
        m.holdAt = new HollowCube.Point(rel, Math.max(climbTo, m.lastAlt - groundAmsl));
        m.dir = 0;
    }

    /** The other way round from the shaft it is in (AC-29). */
    private void reroute(Member m) {
        HollowCube.Face cur = null;
        for (String r : scheduler.heldBy(m.id)) if (r.startsWith("shaft:")) cur = HollowCube.Face.valueOf(r.substring(6));
        if (cur == null || m.target == null) return;
        HollowCube.Face goal = HollowCube.faces(m.target.cell()).get(0);
        int dir = m.dir == 0 ? -1 : -m.dir;
        List<HollowCube.Stage> s = new ArrayList<>(m.stages.subList(0, m.stage + 1));
        s.addAll(cube.hopsFrom(cur, goal, dir, m.holdAt.alt()));
        s.add(cube.in(goal, m.target.layer(), m.target.cell(), true));
        m.stages = s;
        m.dir = dir;
    }

    /** A lost drone has gone, or is back: what was held for it is free again. */
    private void unblock(String id) {
        blocked.remove(id);
        scheduler.releaseAll("held:" + id);
    }

    /** Hold the parts of the airspace near a place (relative to the centre) under a name: a lost drone's. */
    private void holdNear(String who, V2 rel) {
        Set<String> res = resourcesNear(rel);
        if (res.isEmpty()) return;
        scheduler.ask(who, new HollowCube.Stage(res, List.of(), false, true), TransitScheduler.PRIO_LEAD, 0);
    }

    /** Lane sides, shafts and hop corners within two protection radii of a place, in any layer. */
    Set<String> resourcesNear(V2 rel) {
        Set<String> out = new HashSet<>();
        double near = 2 * p.radiusM();
        int layers = 0;
        for (Member m : members.values()) {
            if (m.place != null) layers = Math.max(layers, m.place.layer() + 1);
            if (m.target != null) layers = Math.max(layers, m.target.layer() + 1);
        }
        for (HollowCube.Face f : HollowCube.Face.values()) {
            V2 a = cube.lanePoint(f, f.out.left().times(p.laneM())), b = cube.lanePoint(f, f.out.left().times(-p.laneM()));
            if (V2.segDist(rel, a, b) < near) for (int l = 0; l < layers; l++) out.add(HollowCube.lane(l, f));
            if (rel.dist(cube.shaft(f)) < near + p.radiusM()) out.add(HollowCube.shaftRes(f));
            HollowCube.Face g = f.next();
            if (V2.segDist(rel, cube.shaft(f), cube.hopCorner(f, g)) < near || V2.segDist(rel, cube.hopCorner(f, g), cube.shaft(g)) < near) {
                out.add(HollowCube.corner(f, g));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ GNSS lost for the group: column by column

    private void landColumns(Map<String, Report> reports) {
        landing.clear();
        landingSoon.clear();
        // first the drones without GNSS (they drift - the sooner down the better): each once nothing is below it, so it
        // comes down onto nobody; the group is moved out of their drift lines meanwhile
        boolean drifting = false;
        for (Member m : members.values()) {
            if (m.state != State.GNSS_LOST || landedNow.getOrDefault(m.id, false)) continue;
            // one drifted far off (its estimate, less its error circle as the safe velocities keep it, beyond the
            // keep-out zone round the group) no longer holds the group's landings back: it comes down where it is
            boolean far = m.lastPos.dist(centre) - Math.min(m.errM, GNSS_ERR_CAP_M) > p.edgeM() * Math.sqrt(2) + p.keepOutM() + p.radiusM();
            if (!far) drifting = true;
            boolean under = false; // anyone below it within two radii and its error circle: it waits (drifting clear)
            for (Member o : members.values()) {
                if (o == m || o.state == State.GONE || landedNow.getOrDefault(o.id, false)) continue;
                if (o.lastAlt < m.lastAlt - 1 && o.lastPos.dist(m.lastPos) < 2 * p.radiusM() + m.errM + o.errM) under = true;
            }
            if (!under) landing.add(m.id);
        }
        if (drifting || vel.length() > 0.3) return; // the rest only when the group holds still (a landing drone holds its place)
        // then column by column, bottom first (AC-20, K8): one column at a time, its lowest drone first
        for (int c = 0; c < HollowCube.CELLS; c++) {
            Member bottom = null;
            for (Member m : members.values()) {
                if (m.state == State.GONE || m.place == null || m.place.cell() != c) continue;
                if (bottom == null || m.place.layer() < bottom.place.layer()) bottom = m;
            }
            if (bottom != null) { // once nothing is under it (a drone of another column may have stepped aside under it)
                landingSoon.add(bottom.id);
                if (clearBelow(bottom, reports)) landing.add(bottom.id);
                return;
            }
        }
        // last, any caught on the lane or in a shaft when GNSS went: one at a time
        for (Member m : members.values()) {
            if (m.state != State.GONE) {
                landingSoon.add(m.id);
                if (clearBelow(m, reports)) landing.add(m.id);
                return;
            }
        }
    }

    private final Set<String> landing = new HashSet<>();
    /** Whose turn to land it is, landed or not yet (shown to the safe velocities as coming down, so those under it clear out). */
    private final Set<String> landingSoon = new HashSet<>();

    private Integer columnOf(Member m) {
        if (m.place != null) return m.place.cell();
        // a drone without GNSS: the column it was in (its cell cleared when it lost GNSS)
        if (m.state == State.GNSS_LOST && m.lostColumn >= 0) return m.lostColumn;
        return null;
    }

    private int layerOf(Member m) {
        if (m.place != null) return m.place.layer();
        return (int) Math.round((m.lastAlt - groundAmsl - p.floorM()) / p.layerM()) - 1;
    }

    // ------------------------------------------------------------------ battery order

    private void orderCheck(long now) {
        // not while a drone is cut off and still in its cell, or drifts without GNSS: nobody is moved round it
        for (Member m : members.values()) if (m.state == State.LOST && !m.shifted || m.state == State.GNSS_LOST) return;
        lastOrderCheck = now;
        Map<String, BatteryOrder.Place> places = new HashMap<>();
        Map<String, Double> batt = new HashMap<>();
        for (Member m : members.values()) {
            if (m.state == State.IN_CELL && m.place != null) {
                places.put(m.id, m.place);
                batt.put(m.id, m.battery);
            }
        }
        // cells promised to drones on their way are not given out again
        Set<String> busy = new HashSet<>();
        for (Member m : members.values()) {
            BatteryOrder.Place t = m.state == State.MOVING || m.state == State.APPROACH ? m.target : null;
            if (t != null) busy.add(t.layer() + ":" + t.cell());
        }
        Map<String, BatteryOrder.Place> want = BatteryOrder.assign(places, batt);
        // one move at a time (AC-30: "at low priority"): a drone into a free cell first; if every target is taken, one
        // swapping pair - the one steps aside out of the way while the other goes by. The next starts when it is in.
        boolean inFlight = members.values().stream().anyMatch(m -> m.state == State.MOVING);
        if (inFlight) return;
        Map<String, String> occupant = new HashMap<>();
        for (Member m : members.values()) if (m.state == State.IN_CELL && m.place != null) occupant.put(m.place.layer() + ":" + m.place.cell(), m.id);
        List<String> start = new ArrayList<>();
        for (Map.Entry<String, BatteryOrder.Place> e : want.entrySet()) {
            Member m = members.get(e.getKey());
            BatteryOrder.Place to = e.getValue();
            if (to.layer() == m.place.layer() || busy.contains(to.layer() + ":" + to.cell())) continue;
            if (!occupant.containsKey(to.layer() + ":" + to.cell())) {
                start = List.of(m.id);
                break;
            }
            if (start.isEmpty()) {
                String other = occupant.get(to.layer() + ":" + to.cell());
                BatteryOrder.Place back = want.get(other);
                if (back != null && back.layer() != members.get(other).place.layer()) start = List.of(m.id, other);
            }
        }
        for (String id : start) {
            Member m = members.get(id);
            BatteryOrder.Place to = want.get(id);
            m.state = State.MOVING;
            m.goal = State.IN_CELL;
            m.target = to;
            m.stages = cube.move(m.place.layer(), m.place.cell(), to.layer(), to.cell(), 0);
            m.stage = -1;
            m.point = 0;
            m.dir = 0;
            m.holdAt = new HollowCube.Point(cube.cell(m.place.cell()), cube.layerAlt(m.place.layer()));
            m.place = null;
            changed = true;
        }
    }

    // ------------------------------------------------------------------ orders

    /** One follower's order as wanted, before the safe velocities. */
    private record Want(Member m, int flags, V2 off, double alt, double cap) {}

    private Map<String, GroupOrder> orders(long now, Report lead, boolean leadFresh, List<String> events, Map<String, Report> reports,
                                           List<Other> others, V2 wind) {
        Map<String, GroupOrder> out = new LinkedHashMap<>();
        List<Want> wants = new ArrayList<>();
        double[] c = GeoLocal.offset(lat0, lon0, centre.e(), centre.n());
        double[] l = lead == null ? new double[]{0, 0} : GeoLocal.offset(lat0, lon0, lead.pos().e(), lead.pos().n());
        boolean stopped = vel.length() < 0.05;
        int seq = ++orderSeq;
        double floorAmsl = groundAmsl + p.floorM();
        for (Member m : members.values()) {
            int flags = (stopped ? GroupOrder.FLAG_STOPPED : 0) | (lead != null && leadFresh ? GroupOrder.FLAG_LEAD_KNOWN : 0);
            V2 off;
            double alt;
            double cap = p.catchUpMs() + 2;
            switch (m.state) {
                case IN_CELL -> {
                    off = cube.cell(m.place.cell());
                    alt = groundAmsl + cube.layerAlt(m.place.layer());
                    if (m.place.layer() == 0) flags |= GroupOrder.FLAG_BOTTOM_ROW;
                    if (landMode && landing.contains(m.id)) flags |= GroupOrder.FLAG_LAND;
                }
                case MOVING, APPROACH, LEAVING, LANDING -> {
                    HollowCube.Point pt;
                    boolean granted = m.stage >= 0 && m.stage < m.stages.size();
                    if (giveWayNow.contains(m.id) && m.state != State.LEAVING) { // AC-9: holds where it is
                        pt = new HollowCube.Point(m.lastPos.minus(centre), m.lastAlt - groundAmsl);
                    } else if (granted) {
                        HollowCube.Stage s = m.stages.get(m.stage);
                        pt = s.points().isEmpty() ? m.holdAt : s.points().get(Math.min(m.point, s.points().size() - 1));
                    } else if (m.state == State.APPROACH && m.stage < 0 && m.stages.size() > 0 && m.holdAt != null) {
                        pt = m.holdAt;
                    } else {
                        pt = m.holdAt;
                    }
                    if (pt == null) pt = new HollowCube.Point(m.lastPos.minus(centre), m.lastAlt - groundAmsl);
                    off = pt.at();
                    alt = groundAmsl + Math.max(pt.alt(), p.floorM());
                    cap = vel.length() + (m.state == State.APPROACH && m.stage <= 0 ? p.catchUpMs() : p.moveCapMs());
                    if (m.state == State.LANDING && granted) {
                        HollowCube.Stage s = m.stages.get(m.stage);
                        boolean down = s.points().size() == 1 && s.points().get(0).alt() <= 0;
                        if (down) {
                            flags |= GroupOrder.FLAG_LAND;
                            off = s.points().get(0).at();
                        }
                    }
                    if (m.state == State.LEAVING || m.place != null && m.place.layer() == 0) flags |= GroupOrder.FLAG_BOTTOM_ROW;
                    if (landMode && landing.contains(m.id)) flags |= GroupOrder.FLAG_LAND;
                }
                case GNSS_LOST -> {
                    boolean forced = forceLand.contains(m.id) && clearBelow(m, reports);
                    if (!(landMode && landing.contains(m.id)) && !forced) continue;
                    flags |= GroupOrder.FLAG_LAND;
                    off = m.lastPos.minus(centre);
                    alt = m.lastAlt;
                }
                default -> {
                    continue;
                }
            }
            if (landMode && !landing.contains(m.id) && m.state != State.GNSS_LOST) {
                // drift with the group, hold the layer: nobody else moves (AC-20)
                flags &= ~GroupOrder.FLAG_LAND;
            }
            wants.add(new Want(m, flags, off, alt, cap));
        }
        Map<String, V2> safe = safeVelocities(wants, reports, lead, leadFresh, others, wind);
        for (Want w : wants) {
            Member m = w.m();
            V2 v = safe.get(m.id), off = w.off();
            int flags = w.flags();
            if (v != null) { // its own safe velocity: its place now, a moment ahead along it
                off = reports.get(m.id).pos().minus(centre).plus(v.times(ORDER_LEAD_S));
                flags = v.length() < 0.05 ? flags | GroupOrder.FLAG_STOPPED : flags & ~GroupOrder.FLAG_STOPPED;
            } else {
                v = vel;
            }
            m.sentOff = off;
            out.put(m.id, new GroupOrder(flags, seq, c[0], c[1], v.e(), v.n(), off.e(), off.n(), w.alt(), floorAmsl, w.cap(),
                    l[0], l[1], lead == null ? 0 : lead.altAmsl(), lead == null ? 0 : lead.vel().e(), lead == null ? 0 : lead.vel().n()));
        }
        return out;
    }

    /** An order's place is where the drone is plus this much of its safe velocity (0: there, the velocity the feed-forward). */
    static final double ORDER_LEAD_S = 0;
    /** How hard a follower is drawn to its place: its wanted velocity is the group's plus this much per metre off. */
    static final double PLACE_GAIN = 0.6;
    /** Kept on top of the protection radius by the safe velocities: what the drones' response (the PC's tick, the phone, their acceleration) may cost. */
    static final double ORCA_BUFFER_M = 3;
    /** How late a cut-off drone may set off on its plan against the PC's prediction (its phone's silence, its brake): s. */
    static final double LOST_START_SLACK_S = 3;
    /** Coming down faster than this (m/s, its own climb rate): it goes on down, whatever is under it is in its way. */
    static final double FALLING_MS = 0.5;
    /**
     * The most of a GNSS-less drone's error circle the safe velocities keep clear of, m: its estimate drifts off a little
     * more each second (14.7 m after 50 s at 0.3 m/s, its circle 26 m); past this, a circle that big would wedge every drone near it - by
     * then the group has moved away from it (AC-42) or it is landed (AC-20).
     */
    static final double GNSS_ERR_CAP_M = 20;

    /**
     * Every follower the PC flies (in its cell, on a move) gets the velocity closest to the one that takes it to its
     * place, clear of every drone near its height ({@link Orca}). Followers give way, one by one (AC-8): to each other
     * half and half; to the lead, to drones cut off (still where their brake stopped them, and - in wind - perhaps
     * drifting unseen), to drones without GNSS (drifting, their error circle added), to drones landing and to other
     * groups' drones wholly. Only sideways: no height is changed here.
     */
    private Map<String, V2> safeVelocities(List<Want> wants, Map<String, Report> reports, Report lead, boolean leadFresh,
                                           List<Other> others, V2 wind) {
        List<Orca.Agent> agents = new ArrayList<>();
        java.util.Set<String> flown = new HashSet<>();
        for (Want w : wants) {
            Report r = reports.get(w.m().id);
            // landing, or its FC no longer flying as told (its own battery failsafe landing it): it goes where it goes
            if (r == null || r.landed() || (w.flags() & GroupOrder.FLAG_LAND) != 0 || !r.following()) continue;
            V2 target = centre.plus(w.off());
            V2 pref = vel.plus(target.minus(r.pos()).times(PLACE_GAIN)).capped(Math.max(w.cap(), 0.5));
            // near 3 %: its FC may land it where it is any moment (BATT_FS_CRT_ACT): shown as coming down already, so
            // nobody stays under it
            Member mm = w.m();
            double toCritical = (mm.battery - LAND_PCT) / Math.max(mm.drainPerS, 1e-6);
            double vu = toCritical < LANDING_WARN_S && mm.battery < 50 || landingSoon.contains(mm.id) ? Math.min(r.vu(), -1.5) : r.vu();
            boolean firm = nearCritical(mm) || mm.state == State.LANDING || landingSoon.contains(mm.id);
            agents.add(new Orca.Agent(w.m().id, r.pos(), r.altAmsl(), r.vel(), vu, pref, p.catchUpMs(), true, 0, firm, r.vu() < -FALLING_MS));
            flown.add(w.m().id);
        }
        if (lead != null && leadFresh && !lead.landed()) {
            agents.add(new Orca.Agent("lead", lead.pos(), lead.altAmsl(), lead.vel(), lead.vu(), lead.vel(), 0, false, 0, false, lead.vu() < -FALLING_MS));
        }
        for (Member m : members.values()) {
            if (flown.contains(m.id) || m.state == State.GONE) continue;
            Report r = reports.get(m.id);
            if (m.state == State.LOST) { // where its plan has it: still through its hold, then out along its lane and home
                double[] at = predicted(m.id, lastTick);
                V2 pos = at == null ? m.lastPos : new V2(at[0], at[1]), v = at == null ? V2.ZERO : new V2(at[3], at[4]);
                agents.add(new Orca.Agent(m.id, pos, at == null ? m.lastAlt : at[2], v, at == null ? 0 : at[5], v, 0, false, 1, false, at != null && at[5] < -FALLING_MS));
                if (v.length() >= 0.01) { // on its way: when it set off isn't known to the second (its phone's silence, its brake),
                    // so it may be anywhere behind that place along its track - kept clear back that far, moving as it does
                    double back = v.length() * LOST_START_SLACK_S + (m.heardPos == null ? 0 : m.heardPos.dist(m.lastPos));
                    V2 behind = v.unit().times(-1);
                    for (double s = 4; s <= back + 1e-9; s += 4) {
                        agents.add(new Orca.Agent(m.id + "<" + Math.round(s), pos.plus(behind.times(s)), at[2], v, at[5], v, 0, false, 1));
                    }
                }
                if (v.length() < 0.01 && m.heardPos != null) { // still: somewhere between where it was heard and its furthest stop
                    double way = m.heardPos.dist(m.lastPos);
                    for (double s = 0; s < way; s += 4) {
                        V2 on = m.heardPos.plus(m.lastPos.minus(m.heardPos).unit().times(s));
                        agents.add(new Orca.Agent(m.id + "@" + Math.round(s), on, m.lastAlt, V2.ZERO, 0, V2.ZERO, 0, false, 1));
                    }
                }
                if (wind.length() >= 1) { // it may have lost GNSS too and be drifting since: its whole downwind corridor
                    double since = Math.max(0, (lastTick - m.lostAt) / 1000.0);
                    double reach = Math.min(600, wind.length() * since);
                    for (V2 from : m.heardPos == null ? List.of(m.lastPos) : List.of(m.lastPos, m.heardPos)) { // from anywhere it may have stopped
                        for (double s = 0; s <= reach + 1e-9; s += 8) {
                            V2 drifted = from.plus(wind.unit().times(s));
                            agents.add(new Orca.Agent(m.id + "~" + from.hashCode() + ":" + Math.round(s), drifted, m.lastAlt, wind, 0, wind, 0, false, 1));
                        }
                    }
                    agents.add(new Orca.Agent(m.id + "~", m.lastPos.plus(wind.times(since)), m.lastAlt, wind, 0, wind, 0, false, 1));
                }
            } else if (r != null && !r.landed()) { // without GNSS, or landing: it goes where it goes
                double err = m.state == State.GNSS_LOST ? Math.min(m.errM, GNSS_ERR_CAP_M) : 0; // AC-42: its estimate and its growing error circle
                double vu = forceLand.contains(m.id) ? Math.min(r.vu(), -1.5) : r.vu(); // about to be landed: shown coming down
                if (r.gnssOk() && m.state != State.GNSS_LOST && r.vel().length() > 0.3) {
                    // landing on its own GNSS (LAND holds its place): it brakes to a stop, it doesn't fly on - kept clear
                    // where it is and all the way to where it will stand
                    V2 stop = r.pos().plus(r.vel().times(r.vel().length() / (2 * p.brakeMs2())));
                    double way = r.pos().dist(stop);
                    for (double s = 0; s <= way + 1e-9; s += 4) {
                        V2 on = r.pos().plus(stop.minus(r.pos()).unit().times(s));
                        agents.add(new Orca.Agent(m.id + "#" + Math.round(s), on, r.altAmsl(), V2.ZERO, vu, V2.ZERO, 0, false, 0, false, r.vu() < -FALLING_MS));
                    }
                    agents.add(new Orca.Agent(m.id, stop, r.altAmsl(), V2.ZERO, vu, V2.ZERO, 0, false, 0, false, r.vu() < -FALLING_MS));
                    continue;
                }
                agents.add(new Orca.Agent(m.id, m.state == State.GNSS_LOST ? m.lastPos : r.pos(), r.altAmsl(), r.vel(), vu, r.vel(), 0, false, err, false, r.vu() < -FALLING_MS));
            }
        }
        for (Other o : others) agents.add(new Orca.Agent(o.id(), o.pos(), o.altAmsl(), o.vel(), 0, o.vel(), 0, false, 0));
        return Orca.solve(agents, p.radiusM() + ORCA_BUFFER_M, p.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M);
    }

    /**
     * A drone without GNSS drifting into a drone nobody can move (one cut off, hanging; one without GNSS; another
     * group's) at its height within {@link #FORCE_LAND_LOOK_S} s: it is landed (AC-42 says it holds its height and
     * waits; this is the safe choice where waiting means hitting something - coming down takes it out of that layer).
     * Not while anything nobody can move is under it.
     */
    private void forceLanding(Map<String, Report> reports, List<Other> others, V2 wind, List<String> events) {
        for (Member d : members.values()) {
            if (d.state != State.GNSS_LOST || forceLand.contains(d.id) || landedNow.getOrDefault(d.id, false)) continue;
            Report r = reports.get(d.id);
            V2 dv = r != null && r.vel().length() > wind.length() ? r.vel() : wind;
            List<double[]> stuck = new ArrayList<>(); // e, n, alt
            for (Member o : members.values()) {
                if (o == d || o.state == State.GONE || landedNow.getOrDefault(o.id, false)) continue;
                if (o.state == State.LOST || o.state == State.GNSS_LOST) stuck.add(new double[]{o.lastPos.e(), o.lastPos.n(), o.lastAlt});
            }
            for (Other o : others) stuck.add(new double[]{o.pos().e(), o.pos().n(), o.altAmsl()});
            boolean hits = false, under = false;
            for (double[] s : stuck) {
                double dz = d.lastAlt - s[2];
                V2 sp = new V2(s[0], s[1]);
                if (dz > 1 && sp.dist(d.lastPos) < 2 * p.radiusM() + d.errM) under = true;
                if (Math.abs(dz) >= p.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M) continue;
                for (double t = 0; t <= FORCE_LAND_LOOK_S; t += 0.5) {
                    if (d.lastPos.plus(dv.times(t)).dist(sp) < p.radiusM() + d.errM + 3) hits = true;
                }
            }
            if (hits && !under) {
                forceLand.add(d.id);
                events.add("LAND " + d.id + " (DRIFTING INTO A DRONE NOBODY CAN MOVE)");
            }
        }
    }

    public static final double FORCE_LAND_LOOK_S = 15;

    /** Nothing airborne under this drone within a protection radius (and 3 m, and its error circle): it may come down now. */
    private boolean clearBelow(Member d, Map<String, Report> reports) {
        for (Member o : members.values()) {
            if (o == d || o.state == State.GONE || landedNow.getOrDefault(o.id, false)) continue;
            if (o.state == State.LOST) { // where its plan takes it in the time it takes this one to come down
                boolean crosses = false;
                for (int k = 0; k <= 15 && !crosses; k++) {
                    double[] at = predicted(o.id, lastTick + k * 2000L);
                    // (10 m more: where its brake stopped it is known only that well)
                    if (at != null && at[2] < d.lastAlt - 1 && new V2(at[0], at[1]).dist(d.lastPos) < p.radiusM() + 13 + d.errM) crosses = true;
                }
                if (crosses) return false;
            }
            Report r = reports.get(o.id);
            V2 at = r != null && o.state != State.LOST ? r.pos() : o.lastPos;
            double alt = r != null && o.state != State.LOST ? r.altAmsl() : o.lastAlt;
            if (alt < d.lastAlt - 1 && at.dist(d.lastPos) < p.radiusM() + 3 + d.errM) return false;
        }
        return true;
    }
    private final Set<String> forceLand = new HashSet<>();

    // ------------------------------------------------------------------ exit plans

    private Map<String, ExitPlan> makePlans(long now, Map<String, Report> reports, Report lead, List<String> events) {
        lastProofAt = now;
        List<ExitPlanner.Input> in = new ArrayList<>();
        int topLayer = 0;
        double top = groundAmsl + cube.layerAlt(0);
        for (Member m : members.values()) {
            if (m.place != null) topLayer = Math.max(topLayer, m.place.layer());
            if (m.target != null) topLayer = Math.max(topLayer, m.target.layer());
            if (!Double.isNaN(m.approachAlt)) top = Math.max(top, m.approachAlt);
            top = Math.max(top, m.lastAlt);
        }
        top = Math.max(top, groundAmsl + cube.layerAlt(topLayer));
        // above every cut-off drone's return height from an earlier set: whoever flies home over it is higher (AC-36,
        // losses one after another)
        for (Member m : members.values()) if (m.state == State.LOST && m.plan != null) top = Math.max(top, m.homeAmsl + m.plan.returnAltRelM);
        if (lead != null) top = Math.max(top, lead.altAmsl());
        for (Member m : members.values()) {
            if (m.state == State.GONE || m.state == State.LOST || m.state == State.GNSS_LOST || m.state == State.LEAVING) continue;
            Report r = reports.get(m.id);
            V2 pos = r == null ? m.lastPos : r.pos();
            double alt = r == null ? m.lastAlt : r.altAmsl();
            boolean cell = m.state == State.IN_CELL && m.place != null;
            int above = cell ? topLayer - m.place.layer() : 0;
            V2 rally = m.rally != null ? m.rally : (rallySite != null ? rallySite : pos);
            in.add(new ExitPlanner.Input(m.id, cell ? ExitPlanner.Where.CELL : ExitPlanner.Where.OUTSIDE, pos.minus(centre),
                    alt, above, cell && m.place.layer() == 0, m.homeAmsl, rally));
        }
        if (in.isEmpty()) {
            lastPlansAt = now;
            return null;
        }
        ExitPlanner.Result res = new ExitPlanner(p).plan(centre, vel, in, top, prover);
        if (prover != null && !res.proven()) {
            events.add("EXIT PLANS NOT PROVEN");
            return null; // the drones keep the last proven set
        }
        lastPlansAt = now;
        int seq = ++planSeq;
        Map<String, ExitPlan> out = new LinkedHashMap<>();
        Map<String, ExitPlanner.Input> inputs = new HashMap<>();
        for (ExitPlanner.Input i : in) inputs.put(i.id(), i);
        for (ExitPlanner.Plan pl : res.plans().values()) {
            double[] rally = GeoLocal.offset(lat0, lon0, pl.rally().e(), pl.rally().n());
            double delay = Math.max(pl.startDelayS(), waitForHanging(inputs.get(pl.id()), pl, now));
            out.put(pl.id(), new ExitPlan(pl.bottomRow() ? ExitPlan.FLAG_BOTTOM_ROW : 0, seq, pl.laneEndRadiusM(), delay,
                    pl.returnAltRelM(), rally[0], rally[1], pl.layerAltAmsl()));
        }
        plans = out;
        for (Map.Entry<String, ExitPlan> e : out.entrySet()) {
            Member m = members.get(e.getKey());
            if (m != null) m.plan = e.getValue();
        }
        if (lead != null) leadRtlAltRel = res.leadReturnAltAmsl() - lead.homeAmsl();
        return out;
    }

    /**
     * Where a cut-off follower is now, as its exit plan says it flies (AC-37: its lane, its climb and its way home are
     * kept clear; AC-11: it goes to the lead's flight controller as an ADS-B target though the PC can't hear it): the
     * hold where it was, the start delay, out along its lane at the FC's speed in its layer, the climb at the lane's
     * end, the flight to its rally point, the descent. {e, n, altAmsl, ve, vn, vu}; null when its plan isn't known.
     * ArduPilot's own speeds are taken as set up (8 m/s along the lane, 2.5 m/s up, 10 m/s home, 1.5 m/s down).
     */
    public double[] predicted(String id, long now) {
        Member m = members.get(id);
        ExitPlan pl = m == null ? null : m.plan;
        if (m == null || pl == null || m.lostAt < 0) return null;
        double t = (now - m.lostAt) / 1000.0 - (p.holdMs() / 1000.0 + pl.startDelayS);
        V2 from = m.lastPos;
        if (t <= 0) return new double[]{from.e(), from.n(), m.lastAlt, 0, 0, 0};
        V2 off = m.lostOff != null ? m.lostOff : m.lastPos.minus(m.lostCentre == null ? centre : m.lostCentre); // as its phone has it
        V2 dir = off.length() < 0.5 ? new V2(0, 1) : off.unit();
        double lane = Math.max(pl.laneEndRadiusM - off.length(), 0), laneS = lane / 8;
        if (t < laneS) {
            V2 at = from.plus(dir.times(8 * t));
            return new double[]{at.e(), at.n(), m.lastAlt, dir.e() * 8, dir.n() * 8, 0};
        }
        V2 end = from.plus(dir.times(lane));
        double retAlt = m.homeAmsl + pl.returnAltRelM, climbS = Math.max(0, retAlt - m.lastAlt) / 2.5;
        t -= laneS;
        if (t < climbS) return new double[]{end.e(), end.n(), m.lastAlt + 2.5 * t, 0, 0, 2.5};
        t -= climbS;
        V2 rally = new V2(GeoLocal.east(lat0, lon0, pl.rallyLat, pl.rallyLon), GeoLocal.north(lat0, lon0, pl.rallyLat, pl.rallyLon));
        V2 way = rally.minus(end);
        double cruiseS = way.length() / 10;
        if (t < cruiseS) {
            V2 at = end.plus(way.unit().times(10 * t));
            return new double[]{at.e(), at.n(), retAlt, way.unit().e() * 10, way.unit().n() * 10, 0};
        }
        t -= cruiseS;
        double alt = Math.max(m.homeAmsl, retAlt - 1.5 * t);
        return new double[]{rally.e(), rally.n(), alt, 0, 0, alt > m.homeAmsl ? -1.5 : 0};
    }

    /** The cut-off followers' predicted places now ({@link #predicted}), for TRAFFIC and the lead's ADS-B targets. */
    public Map<String, double[]> unheard(long now) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Member m : members.values()) {
            if (m.state != State.LOST) continue;
            double[] at = predicted(m.id, now);
            if (at != null) out.put(m.id, at);
        }
        return out;
    }

    /** TRAFFIC for the pilot (AC-8): drones near the lead, heard ones and cut-off ones where their plans have them. */
    private void leadTraffic(long now, Report lead, Map<String, Report> reports, List<Other> others, List<String> events) {
        Set<String> near = new HashSet<>();
        if (lead != null && !lead.landed()) {
            ConflictPredictor.Track l = new ConflictPredictor.Track("lead", lead.pos().e(), lead.pos().n(), lead.altAmsl(), lead.vel().e(), lead.vel().n(), lead.vu(), 0);
            List<ConflictPredictor.Track> t = new ArrayList<>();
            for (Map.Entry<String, double[]> u : unheard(now).entrySet()) {
                double[] a = u.getValue();
                t.add(new ConflictPredictor.Track(u.getKey(), a[0], a[1], a[2], a[3], a[4], a[5], 0));
            }
            for (Other o : others) if (!o.lead()) t.add(new ConflictPredictor.Track(o.id(), o.pos().e(), o.pos().n(), o.altAmsl(), o.vel().e(), o.vel().n(), 0, 0));
            for (ConflictPredictor.Track x : predictor.traffic(l, t)) near.add(x.id());
        }
        for (String id : near) if (!leadTrafficSaid.contains(id)) events.add("TRAFFIC " + id);
        leadTrafficSaid.clear();
        leadTrafficSaid.addAll(near);
    }

    private final Set<String> leadTrafficSaid = new HashSet<>();

    /**
     * AC-37 for drones the group does not fly but the PC can (an old lead keeping its mode, E3): one in the predicted
     * way of a cut-off follower - its lane, its climb, its way home, the next 60 s of it - is sent three protection
     * radii aside, across that way, at its own height. id -> where (airspace frame). The lead is never moved (AC-8): it
     * gets TRAFFIC and its own avoidance.
     */
    private final Set<String> clearSaid = new HashSet<>();

    private Map<String, V2> clearOthers(long now, List<Other> others, List<String> events) {
        Map<String, V2> out = new LinkedHashMap<>();
        for (Member m : members.values()) {
            if (m.state != State.LOST) continue;
            for (Other o : others) {
                if (o.lead() || out.containsKey(o.id())) continue;
                for (int s = 0; s <= 60; s += 2) {
                    double[] at = predicted(m.id, now + s * 1000L);
                    if (at == null) break;
                    V2 there = new V2(at[0], at[1]);
                    if (there.dist(o.pos()) < 2 * p.radiusM() && Math.abs(at[2] - o.altAmsl()) < 2 * p.layerM()) {
                        V2 way = new V2(at[3], at[4]);
                        V2 side = way.length() < 0.5 ? o.pos().minus(there).unit() : way.unit().left();
                        if (side.length() < 0.5) side = new V2(1, 0);
                        if (side.dot(o.pos().minus(there)) < 0) side = side.times(-1);
                        out.put(o.id(), o.pos().plus(side.times(3 * p.radiusM())));
                        if (clearSaid.add(o.id() + ">" + m.id)) events.add("CLEAR THE WAY " + o.id() + " FOR " + m.id);
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * A drone cut off earlier hangs where it is until its hold is over: a plan whose lane passes it (within two radii, at
     * its height) gets a start delay that lets it go first (AC-36 for losses that come one after another - the set's
     * proof is for everyone cut off at once). Seconds after this drone's own hold; 0: nothing in its way.
     */
    private double waitForHanging(ExitPlanner.Input i, ExitPlanner.Plan pl, long now) {
        if (i == null) return 0;
        V2 from = centre.plus(i.rel()), dir = i.rel().length() < 0.5 ? new V2(0, 1) : i.rel().unit();
        V2 to = from.plus(dir.times(Math.max(pl.laneEndRadiusM() - i.rel().length(), 0)));
        double wait = 0;
        for (Member l : members.values()) {
            if (l.state != State.LOST || l.plan == null) continue;
            if (Math.abs(l.lastAlt - i.altAmsl()) >= p.layerM() - ExitPlanner.VERTICAL_TOLERANCE_M) continue;
            if (V2.segDist(l.lastPos, from, to) >= 2 * p.radiusM() + 5) continue;
            double gone = (l.lostAt + p.holdMs() - now) / 1000.0 + l.plan.startDelayS + (2 * p.radiusM() + 5) / 8 + 10;
            wait = Math.max(wait, gone - p.holdMs() / 1000.0);
        }
        return wait;
    }

    public Map<String, ExitPlan> plans() {
        return plans;
    }

    public boolean landMode() {
        return landMode;
    }
}
