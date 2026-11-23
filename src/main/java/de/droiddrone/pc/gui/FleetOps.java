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
        if (reserves == null) return;
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
        GroupLink l = link;
        link = null;
        if (l != null) {
            l.disband();
            l.close();
        }
    }

    /** GU-6: critical alerts get the banner and the sound; the rest is a FLEET / log line. */
    static boolean critical(String event) {
        String e = event.toUpperCase(java.util.Locale.ROOT);
        return e.contains("LINK LOST") || e.contains("GNSS LOST") || e.contains("TRAFFIC") || e.contains("LEAD DOWN")
                || e.contains("LEAD TAKEN OVER") || e.contains("LAUNCH FAILED") || e.contains("HOME NOW");
    }
}
