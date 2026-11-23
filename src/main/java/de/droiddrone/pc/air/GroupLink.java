package de.droiddrone.pc.air;

import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.group.GroupOrder;
import de.droiddrone.common.group.Traffic;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fleet PRD FD-1 / area E on the wire: one follower group flown from this PC. Its own UDP socket sends the fleet
 * commands (FleetCmd, keyed) to each drone's phone and takes their reports (FleetStatus); {@link GroupController}
 * decides five times a second (tick) from them. The lead is tracked the same way (its report carries its height above
 * sea level, which the pilot link doesn't). What it sends: each follower's GroupOrder every tick, its ExitPlan when
 * made again and every {@link #PLAN_EVERY_MS} besides, the lead's RTL height, a step aside to drones in the way, and once a second each drone the others as ADS-B traffic (TRAFFIC).
 * Events (LINK LOST, GNSS LOST, TRAFFIC ...) go to the window. Nothing here runs on the stick or video path.
 */
public final class GroupLink implements AutoCloseable {
    public static final long TICK_MS = 200, PLAN_EVERY_MS = 10_000, TRACK_S = 20;
    /** FD-8: TRAFFIC to each drone once a second; a drone not heard for this long is no longer sent as traffic. */
    public static final long TRAFFIC_EVERY_MS = 1000, TRAFFIC_STALE_MS = 10_000;

    /** What the window hears. */
    public interface Listener {
        void event(String text);

        /** A new lead: the pilot's sticks go to it (DISCARD LEAD, or the lead silent 30 s). */
        void newLead(String drone);

        /** Fleet area F: a reserve's launch status (LaunchWire), from its phone. */
        default void launchStatus(String drone, de.droiddrone.common.launch.LaunchWire.Status s) {
        }
    }

    private final DatagramSocket socket;
    private final String key;
    private final Map<String, InetSocketAddress> phones = new ConcurrentHashMap<>();
    private final Map<String, FleetStatus> last = new ConcurrentHashMap<>();
    private final Map<String, Long> heardAt = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, String> byAddress = new ConcurrentHashMap<>();
    private final Map<String, ExitPlan> plans = new HashMap<>();
    private final Map<String, Long> planSentAt = new HashMap<>();
    private final Listener listener;
    private volatile GroupController group;
    private volatile String lead;
    private double lat0 = Double.NaN, lon0 = Double.NaN;
    private volatile boolean open = true;
    private long trackedAt, trafficAt;
    /**
     * GU-9: what the window reads of the group - the map's picture, each member's state, the next reserve's approach
     * height - made again at the end of every tick and change, on this lock. The window reads it without the lock: the
     * controller's own maps are the tick thread's, and a tick (with its planner) must never hold up the Swing thread.
     */
    private volatile View view = View.NONE;

    /** The group as the window shows it, as of the last tick (at most {@link #TICK_MS} old). */
    public record View(Picture picture, Map<String, GroupController.State> states, double nextApproachAlt) {
        static final View NONE = new View(null, Map.of(), Double.NaN);
    }

    public GroupLink(String key, Listener listener) throws IOException {
        this(key, listener, new DatagramSocket());
    }

    GroupLink(String key, Listener listener, DatagramSocket socket) {
        this.key = key;
        this.listener = listener;
        this.socket = socket;
        Thread r = new Thread(this::receive, "groupLinkReceive");
        r.setDaemon(true);
        r.start();
        Thread t = new Thread(this::run, "groupLinkTick");
        t.setDaemon(true);
        t.start();
    }

    /** A drone's phone and where it is reached (NameService's address, the link's port). */
    public void phone(String drone, InetSocketAddress at) {
        InetSocketAddress before = phones.put(drone, at);
        if (before != null) byAddress.remove(before);
        byAddress.put(at, drone);
    }

    /** The newest report of a drone, or null. */
    public FleetStatus status(String drone) {
        return last.get(drone);
    }

    public String lead() {
        return lead;
    }

    /** A report this fresh counts as the drone's state now (FL-6's hand-over). */
    public static final long REPORT_FRESH_MS = 1500;

    /** FL-6: the drone's last report, under {@link #REPORT_FRESH_MS} old (wall clock), says armed and in the air. */
    public boolean reportsFlying(String drone, long now) {
        FleetStatus s = last.get(drone);
        Long at = heardAt.get(drone);
        return s != null && at != null && now - at <= REPORT_FRESH_MS && s.has(FleetStatus.ARMED) && !s.has(FleetStatus.LANDED);
    }

    /** The controller itself: the tick thread's (and the tests'). The window reads {@link #view} instead. */
    public GroupController controller() {
        return group;
    }

    /** A follower group is formed (as of the last tick or change). */
    public boolean active() {
        return view.picture() != null || !view.states().isEmpty();
    }

    /** A member's state (IN_CELL, MOVING ...), or null: not in the group. No lock: the last tick's. */
    public GroupController.State memberState(String drone) {
        return view.states().get(drone);
    }

    /** The group's members as of the last tick. */
    public java.util.Set<String> members() {
        return view.states().keySet();
    }

    /** AC-5: the approach height (AMSL) for the next reserve, as of the last tick; NaN without a group. */
    public double nextApproachAlt() {
        return view.nextApproachAlt();
    }

    /** AC-5 / AL-9: a reserve past the floor joins (a new cell at the top). On the tick's lock: never while a tick runs. */
    public synchronized void addReserve(String drone, double battery, double approachAltAmsl, long now) {
        GroupController g = group;
        if (g == null) return;
        g.addReserve(drone, battery, new V2(0, 0), approachAltAmsl, now);
        refreshView();
    }

    /** The view made again from the controller (on this lock). */
    private void refreshView() {
        GroupController g = group;
        if (g == null) {
            view = View.NONE;
            return;
        }
        Map<String, GroupController.State> states = new LinkedHashMap<>();
        for (Map.Entry<String, GroupController.Member> m : g.members().entrySet()) states.put(m.getKey(), m.getValue().state());
        view = new View(makePicture(), java.util.Collections.unmodifiableMap(states), g.nextApproachAlt());
    }

    /** Asks these drones for their reports (the start of FOLLOW GROUP: where are they?). */
    public void track(List<String> drones) {
        for (String d : drones) send(d, FleetCmd.track((int) TRACK_S));
    }

    /**
     * FOLLOW GROUP (FL-1): the group formed round where the followers are, the lead flown by the pilot. Every drone must
     * have reported (track() first). Returns why not, or null.
     */
    public synchronized String form(String leadDrone, List<String> followers, double groundAmsl) {
        Map<String, Double> batteries = new LinkedHashMap<>();
        double sumLat = 0, sumLon = 0;
        for (String f : followers) {
            FleetStatus s = last.get(f);
            if (s == null) return f + " hasn't reported.";
            if (!s.has(FleetStatus.ARMED)) return f + " is on the ground.";
            if (!s.has(FleetStatus.GNSS)) return f + " has no GPS.";
            batteries.put(f, s.battery == 255 ? 50.0 : s.battery);
            sumLat += s.lat;
            sumLon += s.lon;
        }
        if (followers.isEmpty()) return "No followers.";
        FleetStatus ls = last.get(leadDrone);
        if (ls == null) return "The lead hasn't reported.";
        lat0 = sumLat / followers.size();
        lon0 = sumLon / followers.size();
        AirParams ap = params; // FL-9: the group designer's settings
        GroupController g = new GroupController(ap, groundAmsl, lat0, lon0,
                de.droiddrone.pc.air.virt.VirtualAirspace.prover(ap, groundAmsl));
        V2 rally = local(ls.lat, ls.lon);
        g.form(leadDrone, batteries, new V2(0, 0), rally);
        group = g;
        lead = leadDrone;
        plans.clear();
        planSentAt.clear();
        send(leadDrone, FleetCmd.lead(true, 0));
        refreshView();
        listener.event("FOLLOW GROUP: " + followers.size() + " followers, lead " + leadDrone);
        return null;
    }

    /** DISCARD LEAD (E4, FL-6): the next lead from the bottom row; the window gives it the pilot's sticks. */
    public synchronized String discardLead(long now) {
        GroupController g = group;
        if (g == null) return null;
        String was = lead;
        String next = g.discardLead(now);
        if (next != null) {
            lead = next;
            if (was != null) send(was, FleetCmd.lead(false, 0));
            send(next, FleetCmd.disband()); // a lead is no follower: its brain lets go, the pilot flies it
            send(next, FleetCmd.lead(true, 0));
            refreshView();
            listener.newLead(next);
        }
        return next;
    }

    /** FL-9: the settings the next FOLLOW GROUP is formed with (the group designer's; the PRD's by default). */
    private volatile AirParams params = AirParams.defaults();

    public void setParams(AirParams p) {
        params = p == null ? AirParams.defaults() : p;
    }

    /** AC-10: the solo drones' rally points ({lat, lon} by drone) not yet taken by the tick; null: none new. */
    private volatile Map<String, double[]> soloRalliesAsked;
    /** The followers' rally points ({lat, lon} by drone) as of the last tick. */
    private volatile Map<String, double[]> followerRallies = Map.of();

    /**
     * AC-10: the rally points of the drones on their own (RallyPlan, by drone: {lat, lon}). Taken at the next tick, on
     * its thread: the followers' exit-plan rally points keep two protection radii from them (setOutsideRallies).
     */
    public void setSoloRallies(Map<String, double[]> solo) {
        soloRalliesAsked = Map.copyOf(solo);
    }

    /** The followers' rally points as of the last tick ({lat, lon} by drone): RallyPlan keeps the solo drones' clear of them. */
    public Map<String, double[]> followerRallies() {
        return followerRallies;
    }

    /** A floor asked for and not yet taken by the tick (NaN: none). */
    private volatile double floorAsked = Double.NaN;

    /** The floor for the group (FL-14), live: taken at the next tick (within {@link #TICK_MS}), on its thread. */
    public void setFloor(double m) {
        floorAsked = m;
    }

    /** The group ends: every follower holds (LOITER) and leaves the group. */
    public synchronized void disband() {
        GroupController g = group;
        group = null;
        followerRallies = Map.of();
        if (g == null) return;
        refreshView();
        for (String d : phones.keySet()) send(d, FleetCmd.disband());
        listener.event("Group ended: the followers hold where they are.");
    }

    /**
     * GU-3 / G4: the group as the map draws it - the centre, each cell, the four transit shafts (lat, lon), the half
     * widths of its edge and of the keep-out zone round it, the leash, the floor, how many follow, the lead.
     */
    public record Picture(double lat, double lon, List<double[]> cells, List<double[]> shafts, double edgeHalfM,
                          double keepOutHalfM, double leashM, double shaftHalfM, double floorM, int followers, String lead) {}

    /** The map's picture as of the last tick or change (no lock: never waits for a tick); null without a group. */
    public Picture picture() {
        return view.picture();
    }

    private Picture makePicture() {
        GroupController g = group;
        if (g == null || Double.isNaN(lat0)) return null;
        AirParams p = g.params();
        HollowCube cube = g.cube();
        V2 c = g.centre();
        double[] centre = GeoLocal.offset(lat0, lon0, c.e(), c.n());
        List<double[]> cells = new ArrayList<>(), shafts = new ArrayList<>();
        for (int i = 0; i < HollowCube.CELLS; i++) { // the ring round the empty core
            V2 at = c.plus(cube.cell(i));
            cells.add(GeoLocal.offset(lat0, lon0, at.e(), at.n()));
        }
        double edge = 0;
        for (HollowCube.Face f : HollowCube.Face.values()) {
            V2 s = c.plus(cube.shaft(f));
            shafts.add(GeoLocal.offset(lat0, lon0, s.e(), s.n()));
            edge = Math.max(edge, Math.abs(cube.shaft(f).e()) + Math.abs(cube.shaft(f).n()) + p.radiusM());
        }
        return new Picture(centre[0], centre[1], cells, shafts, edge, edge + p.keepOutM(), p.leashM(), p.radiusM(),
                p.floorM(), g.members().size(), lead);
    }

    private V2 local(double lat, double lon) {
        return new V2(GeoLocal.east(lat0, lon0, lat, lon), GeoLocal.north(lat0, lon0, lat, lon));
    }

    // ------------------------------------------------------------------ the tick

    private void run() {
        while (open) {
            try {
                Thread.sleep(TICK_MS);
                tick(System.currentTimeMillis());
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                listener.event("Group: " + e);
            }
        }
    }

    synchronized void tick(long now) {
        GroupController g = group;
        if (g == null) return;
        double floor = floorAsked;
        if (!Double.isNaN(floor)) {
            floorAsked = Double.NaN;
            g.setFloor(floor);
        }
        Map<String, double[]> solo = soloRalliesAsked;
        if (solo != null) { // AC-10: the followers' rally points clear of the solo drones'
            soloRalliesAsked = null;
            Map<String, V2> local = new HashMap<>();
            for (Map.Entry<String, double[]> e : solo.entrySet()) local.put(e.getKey(), local(e.getValue()[0], e.getValue()[1]));
            g.setOutsideRallies(local);
        }
        Map<String, double[]> fr = new LinkedHashMap<>();
        for (Map.Entry<String, V2> e : g.rallies().entrySet()) fr.put(e.getKey(), GeoLocal.offset(lat0, lon0, e.getValue().e(), e.getValue().n()));
        followerRallies = fr;
        if (now - trackedAt > 5000) { // the reports keep coming (each command does that too)
            trackedAt = now;
            track(new ArrayList<>(phones.keySet()));
        }
        Map<String, GroupController.Report> reports = new HashMap<>();
        GroupController.Report leadReport = null;
        List<GroupController.Other> others = new ArrayList<>();
        for (Map.Entry<String, FleetStatus> e : last.entrySet()) {
            FleetStatus s = e.getValue();
            long heard = heardAt.getOrDefault(e.getKey(), 0L);
            GroupController.Report r = new GroupController.Report(e.getKey(), local(s.lat, s.lon), s.altAmsl, new V2(s.velE, s.velN),
                    s.velU, s.battery == 255 ? Double.NaN : s.battery, s.has(FleetStatus.GNSS), s.errM, heard,
                    s.phase != 255, s.has(FleetStatus.LANDED), s.homeAmsl);
            if (e.getKey().equals(lead)) leadReport = r;
            else if (g.members().containsKey(e.getKey())) reports.put(e.getKey(), r);
            else others.add(new GroupController.Other(e.getKey(), r.pos(), r.altAmsl(), r.vel(), false));
        }
        GroupController.Out out = g.tick(now, reports, leadReport, others, new V2(0, 0));
        refreshView();
        for (Map.Entry<String, GroupOrder> o : out.orders().entrySet()) send(o.getKey(), FleetCmd.group(o.getValue().encode()));
        Map<String, ExitPlan> made = out.plans() == null ? Map.of() : out.plans(); // null: no plans made this tick
        plans.putAll(made);
        for (Map.Entry<String, ExitPlan> p : plans.entrySet()) {
            Long at = planSentAt.get(p.getKey());
            if (made.containsKey(p.getKey()) || at == null || now - at >= PLAN_EVERY_MS) {
                send(p.getKey(), FleetCmd.exit(p.getValue().encode()));
                planSentAt.put(p.getKey(), now);
            }
        }
        if (lead != null && out.leadRtlAltRelM() > 0) send(lead, FleetCmd.lead(true, out.leadRtlAltRelM()));
        for (Map.Entry<String, V2> c : out.clear().entrySet()) {
            double[] ll = GeoLocal.offset(lat0, lon0, c.getValue().e(), c.getValue().n());
            FleetStatus s = last.get(c.getKey());
            // AC-12: one without GNSS is never moved sideways (it couldn't fly GUIDED anyway: its FC holds its height)
            if (s != null && s.has(FleetStatus.GNSS)) send(c.getKey(), FleetCmd.clear(ll[0], ll[1], s.altAmsl));
        }
        if (now - trafficAt >= TRAFFIC_EVERY_MS) { // FD-8 / AC-11: each FC gets the others as ADS-B targets
            trafficAt = now;
            List<String> to = new ArrayList<>(g.members().keySet());
            if (lead != null && !to.contains(lead)) to.add(lead);
            Map<String, double[]> unheard = g.unheard(now);
            for (String d : to) {
                Traffic t = trafficFor(d, now, unheard);
                if (t != null) send(d, FleetCmd.traffic(t.encode()));
            }
        }
        for (String ev : out.events()) listener.event(ev);
        if (out.newLead() != null && !out.newLead().equals(lead)) { // the lead silent 30 s: taken over (FL-5)
            String was = lead;
            lead = out.newLead();
            if (was != null) send(was, FleetCmd.lead(false, 0));
            send(lead, FleetCmd.disband());
            send(lead, FleetCmd.lead(true, out.leadRtlAltRelM()));
            listener.newLead(lead);
        }
    }

    /**
     * FD-8 / AC-11: the other drones for one drone's FC - every drone heard in the last {@link #TRAFFIC_STALE_MS} that
     * is armed and not landed, the cut-off followers where their exit plans have them now (GroupController.unheard),
     * the {@link Traffic#MAX} nearest to it first. Null when there is nobody else.
     */
    synchronized Traffic trafficFor(String to, long now, Map<String, double[]> unheard) {
        if (Double.isNaN(lat0)) return null;
        FleetStatus me = last.get(to);
        record Near(double d, Traffic.Entry e) {}
        List<Near> near = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Map.Entry<String, double[]> u : unheard.entrySet()) {
            if (u.getKey().equals(to)) continue;
            double[] p = u.getValue();
            double[] ll = GeoLocal.offset(lat0, lon0, p[0], p[1]);
            FleetStatus s = last.get(u.getKey());
            int flags = Traffic.PREDICTED | (s != null && !s.has(FleetStatus.GNSS) ? Traffic.NO_GNSS : 0);
            near.add(new Near(apart(me, ll[0], ll[1], p[2]), Traffic.Entry.of(Traffic.address(u.getKey()), ll[0], ll[1], p[2],
                    p[3], p[4], p[5], flags, age(u.getKey(), now))));
            done.add(u.getKey());
        }
        for (Map.Entry<String, FleetStatus> e : last.entrySet()) {
            String d = e.getKey();
            FleetStatus s = e.getValue();
            if (d.equals(to) || done.contains(d) || !s.has(FleetStatus.ARMED) || s.has(FleetStatus.LANDED)) continue;
            if (now - heardAt.getOrDefault(d, 0L) > TRAFFIC_STALE_MS) continue;
            near.add(new Near(apart(me, s.lat, s.lon, s.altAmsl), Traffic.Entry.of(Traffic.address(d), s.lat, s.lon, s.altAmsl,
                    s.velE, s.velN, s.velU, s.has(FleetStatus.GNSS) ? 0 : Traffic.NO_GNSS, age(d, now))));
        }
        if (near.isEmpty()) return null;
        near.sort(java.util.Comparator.comparingDouble(Near::d));
        List<Traffic.Entry> out = new ArrayList<>();
        for (Near n : near) if (out.size() < Traffic.MAX) out.add(n.e());
        return new Traffic(out);
    }

    private int age(String drone, long now) {
        Long at = heardAt.get(drone);
        return at == null ? 255 : (int) Math.max(0, Math.min(255, (now - at) / 1000));
    }

    /** Metres between a report and a place; 0 when the report isn't known (then the order is as found). */
    private static double apart(FleetStatus me, double lat, double lon, double alt) {
        if (me == null) return 0;
        return Math.hypot(Math.hypot(GeoLocal.east(me.lat, me.lon, lat, lon), GeoLocal.north(me.lat, me.lon, lat, lon)), alt - me.altAmsl);
    }

    // ------------------------------------------------------------------ the wire

    /** A fleet command to one drone (its phone must be known: phone()). */
    public void command(String drone, byte[] inner) {
        send(drone, inner);
    }

    void send(String drone, byte[] inner) {
        InetSocketAddress to = phones.get(drone);
        if (to == null || !open) return;
        try {
            byte[] p = FleetCmd.wrap(key, inner);
            socket.send(new DatagramPacket(p, p.length, to.getAddress(), to.getPort()));
        } catch (IOException ignored) {
            // the next tick sends again
        }
    }

    private void receive() {
        byte[] buf = new byte[512];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (open) {
            try {
                socket.receive(p);
            } catch (IOException e) {
                if (!open) return;
                continue;
            }
            String drone = byAddress.get(new InetSocketAddress(p.getAddress(), p.getPort()));
            if (drone == null) continue; // a phone this group doesn't know
            if (p.getLength() > 1 && p.getData()[0] == FleetCmd.LAUNCH_STATUS) {
                de.droiddrone.common.launch.LaunchWire.Status ls = de.droiddrone.common.launch.LaunchWire.decodeStatus(p.getData(), 1, p.getLength() - 1);
                if (ls != null) listener.launchStatus(drone, ls);
                continue;
            }
            FleetStatus s = FleetStatus.decode(p.getData(), p.getLength());
            if (s == null) continue;
            last.put(drone, s);
            heardAt.put(drone, System.currentTimeMillis());
        }
    }

    @Override
    public void close() {
        open = false;
        socket.close();
    }
}
