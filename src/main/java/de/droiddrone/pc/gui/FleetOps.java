package de.droiddrone.pc.gui;

import de.droiddrone.common.FleetStatus;
import de.droiddrone.pc.FleetSwitches;
import de.droiddrone.pc.air.GroupLink;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fleet PRD G1, FL-1, FL-6, FL-14, GU-2, GU-6 in the window: FOLLOW GROUP from the FLEET rows' and the map's right-click
 * menu - the drone this PC flies is the lead; drones added there follow it - DISCARD LEAD from the radio's switch (the
 * new lead connected at once: the pilot's sticks go to it), the floor -/+ 5 m, the group's events in the log, the
 * critical ones with the alert sound. One group at a time.
 */
final class FleetOps {
    static final double FLOOR_STEP_M = 5, FLOOR_MIN_M = 10, FLOOR_WARN_M = 120;

    /** What it needs of the window. */
    interface Host {
        /** The phone this PC flies now (an ArduPilot drone's), or null. */
        String flownPhone();

        /** A phone's address on the link (its name's), or null. */
        InetSocketAddress addressOf(String phone);

        String key();

        void log(String line);

        /** A critical alert (GU-6): banner and sound. */
        void alert(String text);

        /** Fly this drone now (the session goes to it). */
        void flyNow(String phone);

        /** Fleet AL-7: wake this phone at its next check-in (FLEET's NORMAL mode, queued). */
        default void wake(String phone) {
        }

        /** How long until this phone's next check-in: 0 awake or listening, -1 not known. */
        default long checkInWaitMs(String phone) {
            return -1;
        }

        /** The pilot's yes to a question (AL-2); false: no. */
        default boolean confirm(String question) {
            return false;
        }

        /** The drone is marked OUT OF ACTION on this PC. */
        default boolean outOfAction(String phone) {
            return false;
        }

        /** Where a dispatch's routes take the ground from: the offline store (TerrainStore.onDisk). */
        default de.droiddrone.pc.route.TerrainSource terrain() {
            return de.droiddrone.pc.route.TerrainStore.onDisk();
        }

        /** FD-9: the dispatch preview's question with its own title; false: no. */
        default boolean confirmTitled(String title, String text) {
            return confirm(text);
        }

        /** C3: the drone's camera on demand (a small window). */
        default void view(String phone) {
        }

        /** The elevation service, for terrain a dispatch's routes lack (null: none - the offline store only). */
        default de.droiddrone.pc.map.Elevation elevation() {
            return null;
        }
    }

    // ------------------------------------------------------------------ area C: DISPATCH

    /** One drone's dispatch under way: its leg, its route's block, how far it got. */
    private static final class Sent {
        final de.droiddrone.pc.route.Dispatch.Leg leg;
        final int block;
        final long launchAt;
        double homeLat = Double.NaN, homeLon = Double.NaN; // where it left from (FD-2: its battery home)
        boolean uploaded, launched, started;
        long sentAt;

        Sent(de.droiddrone.pc.route.Dispatch.Leg leg, int block, long launchAt) {
            this.leg = leg;
            this.block = block;
            this.launchAt = launchAt;
        }
    }

    private final java.util.Map<String, Sent> dispatched = new java.util.LinkedHashMap<>();
    private int nextBlock = (int) (System.nanoTime() & 0x7fff) + 1;
    static final double DISPATCH_HEIGHT_M = 60;

    /** The map's right-click on a place: DISPATCH every drone marked SAFE TO AUTOLAUNCH there (FD-3). */
    List<JMenuItem> placeItems(double lat, double lon) {
        List<JMenuItem> out = new ArrayList<>();
        int n = 0;
        for (String d : marked.keySet()) if (!dispatched.containsKey(d)) n++;
        if (n == 0) return out;
        JMenuItem go = new JMenuItem("DISPATCH " + n + " marked drone" + (n == 1 ? "" : "s") + " here (" + Math.round(DISPATCH_HEIGHT_M) + " m above ground)");
        go.addActionListener(e -> dispatch(lat, lon));
        out.add(go);
        return out;
    }

    /** FD-3: each a slot, a layer and a terrain route of its own, staggered launches; at the slot it holds (YOUR TURN). */
    void dispatch(double lat, double lon) {
        List<de.droiddrone.pc.route.Dispatch.Drone> drones = new ArrayList<>();
        GroupLink l;
        try {
            l = ensureLink();
        } catch (java.io.IOException e) {
            host.alert("DISPATCH: " + e.getMessage());
            return;
        }
        for (String d : marked.keySet()) {
            if (dispatched.containsKey(d)) continue;
            FleetStatus s = l.status(d);
            if (s == null || !s.has(FleetStatus.GNSS)) {
                host.alert("DISPATCH: " + d + " hasn't reported a position with a fix - left out.");
                continue;
            }
            drones.add(new de.droiddrone.pc.route.Dispatch.Drone(d, s.lat, s.lon, s.homeAmsl));
        }
        if (drones.isEmpty()) return;
        de.droiddrone.pc.route.TerrainSource terrain = host.terrain();
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    List<de.droiddrone.pc.route.Dispatch.Leg> legs = de.droiddrone.pc.route.Dispatch.plan(drones, lat, lon, DISPATCH_HEIGHT_M,
                            terrain, de.droiddrone.pc.route.RoutePlanner.Limits.ARDUCOPTER_DEFAULT);
                    java.util.Map<String, double[]> starts = new java.util.HashMap<>();
                    for (de.droiddrone.pc.route.Dispatch.Drone d : drones) starts.put(d.name(), new double[]{d.lat(), d.lon()});
                    SwingUtilities.invokeLater(() -> {
                        // FD-9: the preview first - OK sends, CANCEL doesn't
                        if (!host.confirmTitled("DISPATCH", previewText(preview(legs, starts::get)))) {
                            host.log("DISPATCH called off.");
                            return;
                        }
                        begin(legs, starts);
                    });
                    return;
                } catch (de.droiddrone.pc.route.RoutePlanner.MissingTerrain m) {
                    de.droiddrone.pc.map.Elevation el = host.elevation();
                    if (el == null || attempt > 0 || !(terrain instanceof de.droiddrone.pc.route.TerrainStore store)) {
                        SwingUtilities.invokeLater(() -> host.alert("DISPATCH: the terrain there isn't known (" + m.nodes().size() + " heights)."));
                        return;
                    }
                    SwingUtilities.invokeLater(() -> host.log("DISPATCH: looking up " + m.nodes().size() + " terrain heights ..."));
                    de.droiddrone.pc.route.TerrainFill.fill(store, m.nodes(), de.droiddrone.pc.route.TerrainFill.of(el), (a, b, c) -> { }, () -> false);
                }
            }
        }, "dispatchPlan");
        t.setDaemon(true);
        t.start();
    }

    private void begin(List<de.droiddrone.pc.route.Dispatch.Leg> legs, java.util.Map<String, double[]> starts) {
        long now = System.currentTimeMillis();
        for (de.droiddrone.pc.route.Dispatch.Leg leg : legs) {
            Sent s = new Sent(leg, nextBlock++ & 0xffff, now + Math.round(leg.launchAfterS() * 1000));
            double[] from = starts.get(leg.name());
            if (from != null) {
                s.homeLat = from[0];
                s.homeLon = from[1];
            }
            dispatched.put(leg.name(), s);
            host.wake(leg.name());
            sendRoute(leg.name(), s);
        }
        host.log("DISPATCH: " + legs.size() + " drones, each its own slot and layer; launches " + Math.round(de.droiddrone.pc.route.Dispatch.STAGGER_S) + " s apart.");
    }

    private void sendRoute(String drone, Sent s) {
        GroupLink l = link;
        InetSocketAddress a = host.addressOf(drone);
        if (l == null || a == null) return;
        l.phone(drone, a);
        l.command(drone, de.droiddrone.common.MissionBlock.packet(s.block, s.leg.mission().items()));
        s.sentAt = System.currentTimeMillis();
    }

    /** Once a second: each dispatch on to its next step. */
    private void tickDispatch() {
        GroupLink l = link;
        if (l == null) return;
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Sent> e : dispatched.entrySet()) {
            String d = e.getKey();
            Sent s = e.getValue();
            FleetStatus st = l.status(d);
            if (!s.uploaded) {
                if (st != null && st.routeBlock == s.block && st.routeState == de.droiddrone.common.MissionUpload.DONE) {
                    s.uploaded = true;
                    host.log("DISPATCH: " + d + "'s route is on the drone, read back.");
                } else if (st != null && st.routeBlock == s.block && st.routeState == de.droiddrone.common.MissionUpload.FAILED) {
                    host.alert("DISPATCH: " + d + "'s route upload failed.");
                    s.uploaded = true;
                    s.launched = s.started = true; // not launched: no route
                } else if (now - s.sentAt >= 1000) {
                    sendRoute(d, s);
                }
                continue;
            }
            if (!s.launched && now >= s.launchAt) {
                Marked m = marked.get(d);
                if (m == null) continue;
                de.droiddrone.common.launch.LaunchWire.Order o = new de.droiddrone.common.launch.LaunchWire.Order();
                o.markId = m.markId();
                o.callId = s.block;
                o.approachHeightM = s.leg.climbToM();
                o.floorM = Math.min(floorM, s.leg.climbToM());
                o.columnLat = st == null ? m.lat() : st.lat;
                o.columnLon = st == null ? m.lon() : st.lon;
                pending.put(d, o);
                sendOrder(d);
                s.launched = true;
                host.log("DISPATCH: " + d + " launching (climbs to " + Math.round(s.leg.climbToM()) + " m).");
            }
        }
    }

    /** The launch past the floor: the route starts (AUTO). Called from onLaunchStatus. */
    private boolean dispatchClimbed(String drone, de.droiddrone.common.launch.LaunchWire.Status st) {
        Sent s = dispatched.get(drone);
        if (s == null || s.started || !st.passedFloor) return s != null;
        GroupLink l = link;
        if (l == null) return true;
        l.command(drone, de.droiddrone.common.FcCommand.simple(de.droiddrone.common.FcCommand.MISSION_START));
        s.started = true;
        marked.remove(drone);
        host.log("DISPATCH: " + drone + " TRANSIT to its slot. At the end it holds: YOUR TURN.");
        return true;
    }

    /** FD-4: TAKE CONTROL of a dispatched drone (the sticks to it, centred pickup: it holds in LOITER at its slot). */
    private void takeControlItems(String phone, List<JMenuItem> out) {
        Sent s = dispatched.get(phone);
        if (s == null || !s.started) return;
        JMenuItem take = new JMenuItem("TAKE CONTROL: " + phone);
        take.addActionListener(e -> host.flyNow(phone));
        out.add(take);
    }

    // ------------------------------------------------------------------ area F: reserves

    /** A drone marked SAFE TO AUTOLAUNCH: its mark id and where it was when marked. */
    record Marked(int markId, double lat, double lon) {}

    private final java.util.Map<String, Marked> marked = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> joined = new java.util.HashSet<>();
    private final java.util.Map<String, Long> orderSentAt = new java.util.HashMap<>();
    private final java.util.Map<String, de.droiddrone.common.launch.LaunchWire.Order> pending = new java.util.HashMap<>();
    private de.droiddrone.pc.launch.ReserveQueue reserves;
    private int nextMark = (int) (System.nanoTime() & 0x3fffffff) + 1;

    private GroupLink ensureLink() throws java.io.IOException {
        if (link == null) link = new GroupLink(host.key(), new GroupLink.Listener() {
            public void event(String text) {
                SwingUtilities.invokeLater(() -> {
                    if (critical(text)) host.alert(text);
                    else host.log(text);
                });
            }

            public void newLead(String drone) {
                SwingUtilities.invokeLater(() -> {
                    host.alert("NEW LEAD: " + drone);
                    host.flyNow(drone);
                });
            }

            public void launchStatus(String drone, de.droiddrone.common.launch.LaunchWire.Status s) {
                SwingUtilities.invokeLater(() -> onLaunchStatus(drone, s));
            }
        });
        return link;
    }

    /** AL-1 / AL-2: marked on the pilot's word that it can climb straight up to the floor with nothing above it. */
    private void mark(String phone) {
        String why = de.droiddrone.pc.launch.MarkRules.refusal(true, true, host.outOfAction(phone));
        if (why != null) {
            host.alert("SAFE TO AUTOLAUNCH: " + why);
            return;
        }
        if (!host.confirm(de.droiddrone.pc.launch.MarkRules.question(phone, floorM))) return;
        InetSocketAddress a = host.addressOf(phone);
        if (a == null) {
            host.alert("SAFE TO AUTOLAUNCH: " + phone + " isn't reachable now.");
            return;
        }
        try {
            GroupLink l = ensureLink();
            l.phone(phone, a);
            int id = nextMark++;
            l.command(phone, de.droiddrone.common.FleetCmd.mark(id, floorM));
            l.track(List.of(phone));
            FleetStatus s = l.status(phone);
            marked.put(phone, new Marked(id, s == null ? Double.NaN : s.lat, s == null ? Double.NaN : s.lon));
            host.log("SAFE TO AUTOLAUNCH: " + phone + " marked (its phone checks it is level, landed, with a fix).");
        } catch (java.io.IOException e) {
            host.alert("SAFE TO AUTOLAUNCH: " + e.getMessage());
        }
    }

    private void unmark(String phone) {
        marked.remove(phone);
        GroupLink l = link;
        if (l != null) l.command(phone, de.droiddrone.common.FleetCmd.mark(0, floorM));
        host.log("SAFE TO AUTOLAUNCH cleared: " + phone);
    }

    boolean isMarked(String phone) {
        return marked.containsKey(phone);
    }

    private java.util.List<de.droiddrone.pc.launch.ReserveChoice.Reserve> reserveList() {
        java.util.List<de.droiddrone.pc.launch.ReserveChoice.Reserve> out = new ArrayList<>();
        GroupLink l = link;
        for (java.util.Map.Entry<String, Marked> e : marked.entrySet()) {
            if (dispatched.containsKey(e.getKey())) continue; // on its way somewhere else
            Marked m = e.getValue();
            FleetStatus s = l == null ? null : l.status(e.getKey());
            double lat = s != null ? s.lat : m.lat(), lon = s != null ? s.lon : m.lon();
            boolean ready = !host.outOfAction(e.getKey()) && !Double.isNaN(lat);
            out.add(new de.droiddrone.pc.launch.ReserveChoice.Reserve(e.getKey(), true, m.markId(), lat, lon,
                    host.checkInWaitMs(e.getKey()), approachFor(e.getKey()), ready));
        }
        return out;
    }

    /** AC-5: its own approach layer above the group (the group's next), in metres above its take-off point. */
    private double approachFor(String phone) {
        GroupLink l = link;
        if (l == null || l.controller() == null) return floorM + 20;
        FleetStatus s = l.status(phone);
        double home = s == null ? 0 : s.homeAmsl;
        return Math.max(floorM, l.controller().nextApproachAlt() - home);
    }

    /** CALL RESERVE (AL-5): each press one more drone - the one that would be there first. */
    void callReserve() {
        GroupLink.Picture p = picture();
        if (p == null) {
            host.alert("CALL RESERVE: no follower group to join.");
            return;
        }
        if (reserves == null) {
            reserves = new de.droiddrone.pc.launch.ReserveQueue(new de.droiddrone.pc.launch.ReserveChoice(
                    de.droiddrone.pc.launch.ReserveChoice.Pace.DEFAULT), new ColumnsFree());
        }
        reserves.setFloor(floorM);
        handle(reserves.press(System.currentTimeMillis(), reserveList(), p.lat(), p.lon()));
    }

    /** The orders to their phones (woken at their check-in first, AL-7), the events to the log. */
    private void handle(de.droiddrone.pc.launch.ReserveQueue.Out out) {
        for (String ev : out.events()) {
            if (ev.contains("NO RESERVE") || ev.contains("FAILED")) host.alert(ev);
            else host.log(ev);
        }
        for (de.droiddrone.common.launch.LaunchWire.Order o : out.orders()) {
            for (java.util.Map.Entry<String, Marked> e : marked.entrySet()) {
                if (e.getValue().markId() != o.markId) continue;
                pending.put(e.getKey(), o);
                host.wake(e.getKey());
                sendOrder(e.getKey());
            }
        }
    }

    /** Once a second (the window's refresh): calls waiting for their column, orders sent again until answered. */
    void tickReserves() {
        tickDispatch();
        if (reserves == null) {
            for (String d : new ArrayList<>(pending.keySet())) {
                Long at = orderSentAt.get(d);
                if (at == null || System.currentTimeMillis() - at >= 2000) sendOrder(d);
            }
            return;
        }
        handle(reserves.tick(System.currentTimeMillis()));
        for (String d : new ArrayList<>(pending.keySet())) {
            Long at = orderSentAt.get(d);
            if (at == null || System.currentTimeMillis() - at >= 2000) sendOrder(d);
        }
    }

    private void sendOrder(String drone) {
        de.droiddrone.common.launch.LaunchWire.Order o = pending.get(drone);
        GroupLink l = link;
        InetSocketAddress a = host.addressOf(drone);
        if (o == null || l == null || a == null) return;
        l.phone(drone, a);
        l.command(drone, de.droiddrone.common.FleetCmd.launch(de.droiddrone.common.launch.LaunchWire.encode(o)));
        orderSentAt.put(drone, System.currentTimeMillis());
    }

    private void onLaunchStatus(String drone, de.droiddrone.common.launch.LaunchWire.Status s) {
        pending.remove(drone); // its phone has the order
        keepLaunch(drone, s);
        if (dispatchClimbed(drone, s)) { // a dispatched drone: its route, not the group
            if (s.state == de.droiddrone.common.launch.LaunchSequence.LAUNCH_FAILED) host.alert("LAUNCH FAILED: " + drone + " - "
                    + de.droiddrone.common.launch.LaunchSequence.reasonWord(s.reason));
            return;
        }
        if (reserves == null) return;
        GroupLink.Picture p = picture();
        handle(reserves.onStatus(System.currentTimeMillis(), drone, s, reserveList(), p == null ? 0 : p.lat(), p == null ? 0 : p.lon()));
        if (s.state == de.droiddrone.common.launch.LaunchSequence.LAUNCH_FAILED || s.state == de.droiddrone.common.launch.LaunchSequence.NOT_LAUNCHED) {
            marked.remove(drone);
            if (s.state == de.droiddrone.common.launch.LaunchSequence.LAUNCH_FAILED) host.alert("LAUNCH FAILED: " + drone + " - "
                    + de.droiddrone.common.launch.LaunchSequence.reasonWord(s.reason) + ". The next reserve is called.");
        }
        // AC-5 / AL-9: past the floor and on its way: the group takes it (a new cell at the top, through a shaft)
        GroupLink l = link;
        if (s.passedFloor && !joined.contains(drone) && l != null && l.controller() != null) {
            FleetStatus st = l.status(drone);
            if (st != null) {
                joined.add(drone);
                marked.remove(drone);
                l.controller().addReserve(drone, st.battery == 255 ? 50 : st.battery,
                        new de.droiddrone.pc.air.V2(0, 0), l.controller().nextApproachAlt(), System.currentTimeMillis());
                host.log(drone + " joins the group.");
            }
        }
    }

    /** AC-4 / AC-5: a reserve's column and its approach layer - one each, never two columns closer than 2 x 10 m. */
    private final class ColumnsFree implements de.droiddrone.pc.launch.LaunchAirspace {
        private final java.util.Map<String, double[]> columns = new java.util.HashMap<>();

        public double approachLayerFor(String drone, double lat, double lon) {
            return approachFor(drone);
        }

        public boolean reserveColumn(String drone, double lat, double lon, double radiusM, double topM) {
            for (java.util.Map.Entry<String, double[]> c : columns.entrySet()) {
                if (c.getKey().equals(drone)) continue;
                if (de.droiddrone.common.group.GeoLocal.distance(c.getValue()[0], c.getValue()[1], lat, lon) < 2 * radiusM) return false;
            }
            columns.put(drone, new double[]{lat, lon});
            return true;
        }

        public void releaseColumn(String drone) {
            columns.remove(drone);
        }

        public void releaseLayer(String drone) {
        }
    }

    private final Host host;
    private final Set<String> chosen = new LinkedHashSet<>();
    private GroupLink link;
    private double floorM = 40;

    FleetOps(Host host) {
        this.host = host;
    }

    boolean active() {
        GroupLink l = link;
        return l != null && l.controller() != null;
    }

    /** The radio's switch: DISCARD LEAD (FL-6) here; CALL RESERVE is the reserves'. Called off the stick thread's back. */
    void onSwitch(int event) {
        if (event == FleetSwitches.CALL_RESERVE) {
            SwingUtilities.invokeLater(this::callReserve);
            return;
        }
        if (event != FleetSwitches.DISCARD_LEAD) return;
        SwingUtilities.invokeLater(() -> {
            GroupLink l = link;
            if (l == null || l.controller() == null) {
                host.log("DISCARD LEAD: no follower group.");
                return;
            }
            String next = l.discardLead(System.currentTimeMillis());
            if (next == null) host.alert("DISCARD LEAD: no follower in the bottom row to take over.");
        });
    }

    /** The right-click menu's fleet items for a drone. */
    List<JMenuItem> items(String phone) {
        List<JMenuItem> out = new ArrayList<>();
        takeControlItems(phone, out); // FD-4
        viewItems(phone, out); // C3
        // AL-1: SAFE TO AUTOLAUNCH on any drone but the one flown
        if (!phone.equalsIgnoreCase(String.valueOf(host.flownPhone()))) {
            JMenuItem m = new JMenuItem(isMarked(phone) ? "SAFE TO AUTOLAUNCH: clear " + phone : "SAFE TO AUTOLAUNCH: mark " + phone);
            m.addActionListener(e -> {
                if (isMarked(phone)) unmark(phone);
                else mark(phone);
            });
            out.add(m);
        }
        String lead = host.flownPhone();
        if (!active()) {
            if (lead != null && !phone.equalsIgnoreCase(lead)) {
                boolean in = chosen.contains(phone);
                JMenuItem add = new JMenuItem((in ? "FOLLOW GROUP: leave out " : "FOLLOW GROUP: add ") + phone);
                add.addActionListener(e -> {
                    if (in) chosen.remove(phone);
                    else chosen.add(phone);
                });
                out.add(add);
            }
            if (lead != null && !chosen.isEmpty()) {
                JMenuItem start = new JMenuItem("FOLLOW GROUP: start (" + chosen.size() + " follow " + lead + ")");
                start.addActionListener(e -> start(lead));
                out.add(start);
            }
        } else {
            JMenuItem up = new JMenuItem("Floor +5 m (now " + Math.round(floorM) + " m)");
            up.addActionListener(e -> floor(floorM + FLOOR_STEP_M));
            JMenuItem down = new JMenuItem("Floor -5 m");
            down.addActionListener(e -> floor(floorM - FLOOR_STEP_M));
            JMenuItem end = new JMenuItem("FOLLOW GROUP: end (all hold)");
            end.addActionListener(e -> end());
            out.add(up);
            out.add(down);
            out.add(end);
        }
        return out;
    }

    /** FL-14: one floor for the group, never under 10 m; above 120 m a warning about the airspace limit. */
    void floor(double m) {
        GroupLink l = link;
        if (l == null) return;
        floorM = Math.max(FLOOR_MIN_M, m);
        l.setFloor(floorM);
        host.log("Group floor " + Math.round(floorM) + " m above the ground.");
        if (floorM > FLOOR_WARN_M) host.alert("Floor over 120 m: check the airspace's height limit.");
    }

    private void start(String lead) {
        List<String> followers = new ArrayList<>(chosen);
        try {
            ensureLink();
        } catch (java.io.IOException e) {
            host.alert("FOLLOW GROUP: " + e.getMessage());
            return;
        }
        List<String> all = new ArrayList<>(followers);
        all.add(lead);
        for (String d : all) {
            InetSocketAddress a = host.addressOf(d);
            if (a == null) {
                host.alert("FOLLOW GROUP: " + d + " isn't reachable (no address).");
                return;
            }
            link.phone(d, a);
        }
        link.track(all);
        host.log("FOLLOW GROUP: asking " + all.size() + " drones where they are ...");
        GroupLink l = link;
        Thread t = new Thread(() -> {
            long until = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < until && all.stream().anyMatch(d -> l.status(d) == null)) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
            FleetStatus ls = l.status(lead);
            String why = ls == null ? "the lead hasn't reported (phone app pc.58 or newer)." : l.form(lead, followers, ls.homeAmsl);
            SwingUtilities.invokeLater(() -> {
                if (why != null) host.alert("FOLLOW GROUP not started: " + why);
                else chosen.clear();
            });
        }, "followGroupStart");
        t.setDaemon(true);
        t.start();
    }

    /** The group for the map (G4), or null: none. */
    GroupLink.Picture picture() {
        GroupLink l = link;
        return l == null ? null : l.picture();
    }

    void end() {
        GroupLink l = link;
        if (l != null) l.disband();
    }

    void close() {
        launchViews.close();
        GroupLink l = link;
        link = null;
        if (l != null) {
            l.disband();
            l.close();
        }
    }

    // ------------------------------------------------------------------ GU-5 / FD-2 / FD-5 / FL-10: what the OSD and FLEET say

    /** The route speed the trip home is counted at (ArduPilot's WPNAV_SPEED as setup writes it), and a margin. */
    static final double CRUISE_MS = 10, HOME_MARGIN_S = 30, HOME_FACTOR = 1.25;
    /** AC-8: TRAFFIC within twice the protection radius sideways and twice the layer spacing in height. */
    static final double TRAFFIC_H_M = 20, TRAFFIC_V_M = 20;
    /** The battery's fall is measured over at least this long before a time left is said. */
    static final long DRAIN_WINDOW_MS = 30_000;

    /** A drone's battery % over time, for its rate of fall (FD-2, FD-5). */
    static final class Drain {
        private final java.util.ArrayDeque<double[]> samples = new java.util.ArrayDeque<>();

        void add(long now, double percent) {
            if (Double.isNaN(percent)) return;
            double[] lastS = samples.peekLast();
            if (lastS != null && now - lastS[0] < 2000) return;
            samples.addLast(new double[]{now, percent});
            while (samples.size() > 2 && now - samples.peekFirst()[0] > 5 * 60_000) samples.pollFirst();
        }

        /** % a second it falls, NaN while not measured long enough (or rising: charging, a new pack). */
        double ratePerS() {
            double[] a = samples.peekFirst(), b = samples.peekLast();
            if (a == null || b == a || b[0] - a[0] < DRAIN_WINDOW_MS) return Double.NaN;
            double r = (a[1] - b[1]) / ((b[0] - a[0]) / 1000.0);
            return r > 0 ? r : Double.NaN;
        }
    }

    private final java.util.Map<String, Drain> drains = new java.util.HashMap<>();
    private final Drain leadDrain = new Drain();
    private final java.util.Map<String, de.droiddrone.common.launch.LaunchWire.Status> launches = new java.util.HashMap<>();
    private final de.droiddrone.pc.launch.MiniViews miniViews = new de.droiddrone.pc.launch.MiniViews();
    private final LaunchViews launchViews = new LaunchViews();
    private boolean leadHadFix;
    private long flownTrackedAt = Long.MIN_VALUE / 2;

    /** FD-5: "LEFT m:ss" to 3 % at the measured fall; null not known. */
    static String timeLeft(double percent, double ratePerS) {
        if (Double.isNaN(percent) || Double.isNaN(ratePerS) || ratePerS <= 0) return null;
        long s = Math.max(0, Math.round((percent - 3) / ratePerS));
        return "LEFT " + s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
    }

    /** FD-5: the trip home at cruise speed needs the rest (with a quarter and 30 s to spare). */
    static boolean homeNow(double percent, double ratePerS, double homeDistanceM) {
        if (Double.isNaN(percent) || Double.isNaN(ratePerS) || ratePerS <= 0 || homeDistanceM < 0) return false;
        double left = (percent - 3) / ratePerS, home = homeDistanceM / CRUISE_MS;
        return left <= home * HOME_FACTOR + HOME_MARGIN_S;
    }

    /** FD-2: the battery when home again, at the measured fall; NaN not known. */
    static double batteryAfterHome(double percent, double ratePerS, double homeDistanceM) {
        if (Double.isNaN(percent) || Double.isNaN(ratePerS) || homeDistanceM < 0) return Double.NaN;
        return percent - ratePerS * homeDistanceM / CRUISE_MS;
    }

    /**
     * FD-2 / FL-10: a drone's state for FLEET and the map - its launch while one runs, the group's word for a follower,
     * TRANSIT / YOUR TURN / RETURNING / HOLDING for a dispatched drone, RESERVE for a marked one; with the battery left
     * after the trip home where it is known. Null: nothing of the fleet's.
     */
    String stateOf(String drone) {
        String word = null;
        de.droiddrone.common.launch.LaunchWire.Status ls = launches.get(drone);
        GroupLink l = link;
        FleetStatus st = l == null ? null : l.status(drone);
        if (ls != null && ls.state != de.droiddrone.common.launch.LaunchSequence.IDLE
                && ls.state != de.droiddrone.common.launch.LaunchSequence.IN_GROUP && !dispatched.containsKey(drone)) {
            word = de.droiddrone.common.launch.LaunchSequence.stateWord(ls.state);
        } else if (l != null && l.controller() != null && l.controller().members().containsKey(drone)) {
            de.droiddrone.pc.air.GroupController.Member m = l.controller().members().get(drone);
            word = switch (m.state) {
                case IN_CELL -> st != null && st.phase == de.droiddrone.common.group.FollowerBrain.HOLD ? "HOLDING" : "IN GROUP";
                case MOVING -> "CATCHING UP";
                case APPROACH -> "ON THE WAY";
                case LEAVING -> "LEAVING";
                case LANDING -> "LANDING";
                case LOST -> "LOST";
                case GNSS_LOST -> "GNSS LOST";
                case GONE -> "GONE";
            };
        } else if (dispatched.containsKey(drone)) {
            Sent s = dispatched.get(drone);
            if (!s.started) word = s.launched ? "LAUNCHING" : s.uploaded ? "READY" : "ROUTE ...";
            else if (st == null) word = "TRANSIT";
            else if (st.mode == de.droiddrone.pc.ArduModes.AUTO) word = "TRANSIT";
            else if (de.droiddrone.pc.ArduModes.isReturnOrLand(st.mode)) word = "RETURNING";
            else if (st.mode == de.droiddrone.pc.ArduModes.LOITER) word = "YOUR TURN";
            else word = "HOLDING";
        } else if (marked.containsKey(drone)) {
            word = "RESERVE";
        }
        if (word == null) return null;
        Drain d = drains.get(drone);
        Marked home = marked.get(drone);
        double[] start = home != null ? new double[]{home.lat(), home.lon()} : dispatched.containsKey(drone)
                ? new double[]{dispatched.get(drone).homeLat, dispatched.get(drone).homeLon} : null;
        if (st != null && d != null && start != null && st.battery != 255) {
            double dist = de.droiddrone.common.group.GeoLocal.distance(st.lat, st.lon, start[0], start[1]);
            double after = batteryAfterHome(st.battery, d.ratePerS(), dist);
            if (!Double.isNaN(after)) word += "  " + st.battery + "% > " + Math.max(0, Math.round(after)) + "% HOME";
        }
        return word;
    }

    /**
     * GU-5: the OSD's fleet figures now, for the drone this PC flies (lead: its telemetry). The launch views' alerts go to
     * the alert line once their 10 s are up (AL-21). Called by the window's refresh.
     */
    /** At most this often worked out again: the OSD asks for its data at the picture's rate, the recorder too. */
    static final long OSD_EVERY_MS = 250;
    private OsdFleet osdCached = OsdFleet.NONE;
    private long osdAt = Long.MIN_VALUE / 2;

    synchronized OsdFleet osd(de.droiddrone.pc.FcTelemetry lead, long now) {
        if (now - osdAt < OSD_EVERY_MS) return osdCached;
        osdAt = now;
        osdCached = osdNow(lead, now);
        return osdCached;
    }

    private OsdFleet osdNow(de.droiddrone.pc.FcTelemetry lead, long now) {
        GroupLink l = link;
        if (l != null) {
            for (String d : new ArrayList<>(dispatched.keySet())) feedDrain(l, d, now);
            if (l.controller() != null) for (String d : l.controller().members().keySet()) feedDrain(l, d, now);
        }
        String reservesLine = reserves == null ? null : reserves.osdLine(now);
        if (reservesLine != null && reservesLine.isEmpty()) reservesLine = null;
        List<OsdFleet.Mini> minis = List.of();
        if (reserves != null) {
            minis = launchViews.update(miniViews.views(now, reserves.all(), floorM), host::addressOf, host.key());
            for (String a : miniViews.takeAlerts()) SwingUtilities.invokeLater(() -> host.alert("LAUNCH FAILED: " + a)); // off the paint
        }
        GroupLink.Picture p = picture();
        de.droiddrone.pc.FcTelemetry.Gps gps = lead == null ? null : lead.getGps();
        double edge = Double.NaN;
        if (p != null && gps != null && gps.positionOk()) {
            double e = de.droiddrone.common.group.GeoLocal.east(p.lat(), p.lon(), gps.latDeg(), gps.lonDeg());
            double n = de.droiddrone.common.group.GeoLocal.north(p.lat(), p.lon(), gps.latDeg(), gps.lonDeg());
            edge = Math.max(Math.abs(e), Math.abs(n)) - p.edgeHalfM();
        }
        de.droiddrone.pc.FcTelemetry.Status status = lead == null ? null : lead.getStatus();
        String gnss = null;
        if (status != null && status.isArduPilot()) {
            boolean fix = gps != null && gps.fix() >= 2;
            if (fix) leadHadFix = true;
            gnss = fix ? "GNSS" : leadHadFix ? "GNSS LOST" : "NO FIX";
        }
        // FD-5 for the flown drone: its battery's fall, its way home
        de.droiddrone.pc.FcTelemetry.Battery b = lead == null ? null : lead.getBattery();
        double pct = b == null || !b.cellCountKnown() ? Double.NaN : de.droiddrone.common.CellVolts.percent(b.cellVolts());
        leadDrain.add(now, pct);
        double rate = leadDrain.ratePerS();
        int homeM = gps == null ? -1 : gps.distanceHomeM();
        String left = timeLeft(pct, rate);
        boolean alarm = homeNow(pct, rate, homeM);
        String homeText = alarm ? "HOME NOW" + (left == null ? "" : "  " + left) : left;
        String flown = host.flownPhone();
        // AC-8: the flown drone's own report (its height above sea level) while other fleet drones are about
        if (l != null && flown != null && !known().isEmpty() && now - flownTrackedAt > 10_000) {
            flownTrackedAt = now;
            InetSocketAddress a = host.addressOf(flown);
            if (a != null) {
                l.phone(flown, a);
                l.track(List.of(flown));
            }
        }
        FleetStatus leadSt = l == null || flown == null ? null : l.status(flown);
        boolean leadDown = active() && leadSt != null && !leadSt.has(FleetStatus.ARMED);
        return new OsdFleet(reservesLine, p == null ? null : "GROUP " + p.followers(), edge, p == null ? Double.NaN : p.floorM(), gnss,
                traffic(l, known(), flown, leadSt), homeText, alarm, leadDown, minis);
    }

    private void feedDrain(GroupLink l, String d, long now) {
        FleetStatus st = l.status(d);
        if (st != null && st.battery != 255) drains.computeIfAbsent(d, k -> new Drain()).add(now, st.battery);
    }

    /** AC-8: the nearest other drone within 20 m sideways and 20 m in height of the flown one: "TRAFFIC 14 M NE  -3 M". */
    static String traffic(GroupLink l, java.util.Collection<String> drones, String flown, FleetStatus me) {
        if (l == null || me == null || flown == null) return null;
        String best = null;
        double bestH = Double.MAX_VALUE;
        for (String d : drones) {
            if (d.equals(flown)) continue;
            FleetStatus o = l.status(d);
            if (o == null || o.has(FleetStatus.LANDED)) continue;
            double h = de.droiddrone.common.group.GeoLocal.distance(me.lat, me.lon, o.lat, o.lon), v = o.altAmsl - me.altAmsl;
            if (h > TRAFFIC_H_M || Math.abs(v) > TRAFFIC_V_M || h >= bestH) continue;
            bestH = h;
            String dir = compass(de.droiddrone.common.group.GeoLocal.bearing(me.lat, me.lon, o.lat, o.lon));
            best = "TRAFFIC " + Math.round(h) + " M " + dir + "  " + (v >= 0 ? "+" : "") + Math.round(v) + " M";
        }
        return best;
    }

    static String compass(double bearingDeg) {
        String[] w = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return w[(int) Math.round(((bearingDeg % 360) + 360) % 360 / 45.0) % 8];
    }

    /** Every drone this PC has told something of the fleet: marked, dispatched, in the group, launching. */
    private java.util.Set<String> known() {
        java.util.Set<String> s = new java.util.LinkedHashSet<>(marked.keySet());
        s.addAll(dispatched.keySet());
        s.addAll(launches.keySet());
        s.addAll(joined);
        GroupLink l = link;
        if (l != null && l.controller() != null) s.addAll(l.controller().members().keySet());
        return s;
    }

    /** The launch's status, kept for FLEET's state (FD-2) as well as the queue's. */
    private void keepLaunch(String drone, de.droiddrone.common.launch.LaunchWire.Status s) {
        launches.put(drone, s);
    }

    // ------------------------------------------------------------------ FD-9: the dispatch preview

    /** FD-9's assumptions: the pilot flies each drone this long at its slot; a full battery lasts this long. */
    static final double PILOTED_S = 5 * 60, ENDURANCE_S = 23 * 60, CLIMB_MS = 2.5;

    /** One drone's times as the preview counts them. */
    record Preview(String name, double transitS, double waitS, double pilotedS, double returnS, boolean runsLow) {}

    /**
     * Each leg's transit (climb and route), the wait for the pilot (the drones before it in turn, each PILOTED_S after
     * it arrives), PILOTED_S flown, the way back at cruise speed; runs low when it all comes over 90 % of a battery.
     */
    static List<Preview> preview(List<de.droiddrone.pc.route.Dispatch.Leg> legs, java.util.function.Function<String, double[]> startOf) {
        List<Preview> out = new ArrayList<>();
        double pilotFreeAt = 0;
        for (de.droiddrone.pc.route.Dispatch.Leg leg : legs) {
            double[] s = startOf.apply(leg.name());
            double dist = s == null ? 0 : de.droiddrone.common.group.GeoLocal.distance(s[0], s[1], leg.slotLat(), leg.slotLon());
            double transit = leg.launchAfterS() + leg.climbToM() / CLIMB_MS + dist / CRUISE_MS;
            double wait = Math.max(0, pilotFreeAt - transit);
            pilotFreeAt = Math.max(pilotFreeAt, transit) + PILOTED_S;
            double back = dist / CRUISE_MS;
            out.add(new Preview(leg.name(), transit, wait, PILOTED_S, back, transit + wait + PILOTED_S + back > ENDURANCE_S * 0.9));
        }
        return out;
    }

    /** The preview as the dialog shows it. */
    static String previewText(List<Preview> ps) {
        StringBuilder sb = new StringBuilder("DISPATCH - each drone's times (min:s)\n\n");
        sb.append(String.format(java.util.Locale.ROOT, "%-14s %7s %7s %7s %7s%n", "DRONE", "TRANSIT", "WAIT", "FLOWN", "BACK"));
        for (Preview p : ps) {
            sb.append(String.format(java.util.Locale.ROOT, "%-14s %7s %7s %7s %7s%s%n", p.name(), ms(p.transitS()), ms(p.waitS()),
                    ms(p.pilotedS()), ms(p.returnS()), p.runsLow() ? "  RUNS LOW" : ""));
        }
        sb.append("\nYour turn with each: ").append(Math.round(PILOTED_S / 60)).append(" min assumed. Send?");
        return sb.toString();
    }

    private static String ms(double s) {
        long t = Math.round(s);
        return t / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", t % 60);
    }

    // ------------------------------------------------------------------ C3: a drone's picture on demand

    /** C3: VIEW a drone this PC commands - its camera, small, in a window of its own. */
    private void viewItems(String phone, List<JMenuItem> out) {
        if (phone.equalsIgnoreCase(String.valueOf(host.flownPhone()))) return;
        if (!dispatched.containsKey(phone) && !(link != null && link.controller() != null && link.controller().members().containsKey(phone))
                && !marked.containsKey(phone)) return;
        JMenuItem view = new JMenuItem("VIEW " + phone);
        view.addActionListener(e -> host.view(phone));
        out.add(view);
    }

    /** GU-6: critical alerts get the banner and the sound; the rest is a FLEET / log line. */
    static boolean critical(String event) {
        String e = event.toUpperCase(java.util.Locale.ROOT);
        return e.contains("LINK LOST") || e.contains("GNSS LOST") || e.contains("TRAFFIC") || e.contains("LEAD DOWN")
                || e.contains("LEAD TAKEN OVER") || e.contains("LAUNCH FAILED") || e.contains("HOME NOW");
    }
}
