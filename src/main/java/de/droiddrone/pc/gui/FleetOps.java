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
            });
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
