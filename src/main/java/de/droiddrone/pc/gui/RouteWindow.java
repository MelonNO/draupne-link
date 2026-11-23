package de.droiddrone.pc.gui;

import de.droiddrone.common.FcCommand;
import de.droiddrone.common.MissionItem;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.Elevation;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.route.MissionBuilder;
import de.droiddrone.pc.route.Route;
import de.droiddrone.pc.route.RoutePlanner;
import de.droiddrone.pc.route.RouteProfile;
import de.droiddrone.pc.route.RouteSender;
import de.droiddrone.pc.route.TerrainFill;
import de.droiddrone.pc.route.TerrainStore;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Fleet PRD area B (RT-1 - RT-9, RT-15, B1 - B6): the ROUTE window beside the map. The pilot draws the route on the map
 * (MapView's ROUTE tool), drags its points, inserts and deletes them there; here: the height above the ground and the
 * smoothing, each point's own height, the profile (RT-7), REVERSE, SAVE, LOAD, then UPLOAD to the drone this PC flies
 * (planned from where it is, read back item by item on the phone) and GO. The terrain is filled from the elevation
 * service into the offline store the first time an area is planned; planning runs off Swing's thread.
 */
final class RouteWindow extends JDialog {
    private final MapView map;
    private final TerrainFill.Lookup lookup;
    private final Supplier<ControlLink> link;
    private final Supplier<FlightTrack> track;
    private final TerrainStore terrain;
    private final RouteSettingsPanel settings = new RouteSettingsPanel();
    private final RouteProfileView profile = new RouteProfileView();
    private final JLabel status = Theme.label(" ", Theme.font(Font.BOLD, 13f), Theme.MUTED);
    private final JButton upload = Theme.primaryButton("UPLOAD");
    private final JButton go = Theme.primaryButton("GO");
    private final JButton fence = Theme.secondaryButton("FENCE");
    private final java.util.function.BooleanSupplier watchOnly;
    private Route route;
    private RoutePlanner.Plan plan;
    private MissionBuilder.Mission mission;
    private int planSerial;
    private volatile Thread filling;

    RouteWindow(Window owner, MapView map, Elevation elevation, Supplier<ControlLink> link, Supplier<FlightTrack> track) {
        this(owner, map, elevation, link, track, () -> false);
    }

    /** @param watchOnly GU-8: this PC only watches (a spectator, an observation PC) - routes are drawn and planned, never sent */
    RouteWindow(Window owner, MapView map, Elevation elevation, Supplier<ControlLink> link, Supplier<FlightTrack> track,
                java.util.function.BooleanSupplier watchOnly) {
        this(owner, map, TerrainStore.onDisk(), (lat, lon) -> elevation == null ? null : elevation.heightNow(lat, lon), link, track, watchOnly);
    }

    /** @param terrain the ground heights kept; @param lookup the height service, for what they lack (the tests' made-up ground) */
    RouteWindow(Window owner, MapView map, TerrainStore terrain, TerrainFill.Lookup lookup, Supplier<ControlLink> link,
                Supplier<FlightTrack> track, java.util.function.BooleanSupplier watchOnly) {
        super(owner, "Route", ModalityType.MODELESS);
        this.map = map;
        this.terrain = terrain;
        this.lookup = lookup;
        this.link = link;
        this.track = track;
        this.watchOnly = watchOnly;
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(Theme.PANEL);
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(settings, BorderLayout.NORTH);
        profile.setPreferredSize(new java.awt.Dimension(460, 170));
        root.add(profile, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(status, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        JButton reverse = Theme.secondaryButton("REVERSE"), save = Theme.secondaryButton("SAVE"), load = Theme.secondaryButton("LOAD"),
                clear = Theme.secondaryButton("CLEAR");
        fence.setToolTipText("Airspace areas near the drone, and rectangles named FENCE, as its own geofence: it stops at the edge (AP-9)");
        fence.addActionListener(e -> fence());
        JButton masts = Theme.secondaryButton("MASTS");
        masts.setToolTipText("Power lines, pylons and masts near the route, from OpenStreetMap (not complete). Not while flying.");
        masts.addActionListener(e -> masts());
        for (JButton b : new JButton[]{reverse, save, load, clear, fence, masts, upload, go}) buttons.add(b);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        reverse.addActionListener(e -> {
            if (route == null) return;
            route.reverse();
            edited();
        });
        save.addActionListener(e -> save());
        load.addActionListener(e -> load());
        clear.addActionListener(e -> setRoute(null));
        upload.addActionListener(e -> upload());
        go.addActionListener(e -> go());
        upload.setToolTipText("The route to the drone this PC flies, planned from where it is; read back on the drone");
        go.setToolTipText("Flies the route (AUTO). At its end it holds and calls you");
        settings.setListener(new RouteSettingsPanel.Listener() {
            public void heightChanged(double h) {
                if (route != null) {
                    route.setHeightM(h);
                    edited();
                }
            }

            public void smoothingChanged(double s) {
                if (route != null) {
                    route.setSmoothingM(s);
                    edited();
                }
            }

            public void pointHeightChanged(int i, double h) {
                if (route != null && i >= 0 && i < route.size()) {
                    route.setOwnHeight(i, h);
                    edited();
                }
            }
        });
        map.setRouteHost(new MapView.RouteHost() {
            public void drawn(List<MapGraphics.LatLon> points) {
                Route r = new Route("Route");
                for (MapGraphics.LatLon p : points) r.add(p.lat(), p.lon());
                setRoute(r);
            }

            public void edited() {
                RouteWindow.this.edited();
            }

            public void selected(int index) {
                settings.selectPoint(index);
            }

            public void flyToHere(double lat, double lon) {
                setRoute(Route.flyToHere(lat, lon));
                open();
            }

            public void askHeight(int index) {
                settings.selectPoint(index);
                open();
            }
        });
        refreshButtons();
        buttonTimer.start();
        pack();
        setLocationRelativeTo(owner);
    }

    void open() {
        refreshButtons();
        if (!isVisible()) setVisible(true);
        toFront();
    }

    void setRoute(Route r) {
        route = r;
        plan = null;
        mission = null;
        planSerial++; // CLEAR while the old route was planned: its plan came in after and UPLOAD sent a cleared route
        settings.setRoute(r);
        profile.setProfile(null);
        profile.setObstacles(null);
        map.setObstacles(null);
        map.setRoute(r, null);
        if (r != null) replan();
        else say("Draw a route: ROUTE, then click points on the map.", Theme.MUTED);
        refreshButtons();
    }

    private void edited() {
        settings.setRoute(route);
        mission = null;
        plan = null; // the plan was of the route before the edit: UPLOAD sent it (a deleted point flown all the same)
        planSerial++; // a plan of the route before the edit, still on its way, is not taken
        profile.setObstacles(null); // RT-14: looked up again with MASTS
        map.setObstacles(null);
        map.setRoute(route, null);
        replan();
        refreshButtons();
    }

    /** RT-14: power lines and masts near the route (start included), on the click only, never while a drone may fly. */
    private void masts() {
        if (de.droiddrone.pc.PossiblyFlying.now()) {
            say("Not while a drone may be flying.", Theme.WARN);
            return;
        }
        double[] from = start();
        if (route == null || route.size() == 0 || from == null) return;
        List<double[]> pts = new java.util.ArrayList<>();
        pts.add(from);
        for (Route.Point p : route.points()) pts.add(new double[]{p.lat(), p.lon()});
        say("Looking up power lines and masts ...", Theme.MUTED);
        Thread t = new Thread(() -> {
            try {
                double[] box = de.droiddrone.pc.route.PowerLines.box(pts, de.droiddrone.pc.route.PowerLines.MARGIN_M);
                java.nio.file.Path cache = de.droiddrone.pc.AppPaths.dataDir().resolve("powerlines");
                java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
                List<de.droiddrone.pc.route.PowerLines.Near> near = de.droiddrone.pc.route.PowerLines.near(pts,
                        de.droiddrone.pc.route.PowerLines.fetch(box, cache, http), de.droiddrone.pc.route.PowerLines.NEAR_M);
                SwingUtilities.invokeLater(() -> {
                    profile.setObstacles(near);
                    map.setObstacles(near);
                    if (near.isEmpty()) say("None on the map near the route (the map isn't complete).", Theme.TEXT);
                    else say(near.size() + " near: " + near.stream().limit(3).map(de.droiddrone.pc.route.PowerLines.Near::word)
                            .collect(java.util.stream.Collectors.joining("; ")) + (near.size() > 3 ? " ..." : ""), Theme.WARN);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> say("Masts: " + e.getMessage(), Theme.BAD));
            }
        }, "routeMasts");
        t.setDaemon(true);
        t.start();
    }

    /** Where the route starts: the drone this PC flies (its GPS), else the route's first point (a plan to look at). */
    private double[] start() {
        ControlLink l = link.get();
        FcTelemetry.Gps g = l == null ? null : l.getTelemetry().getGps();
        if (g != null && g.positionOk()) return new double[]{g.latDeg(), g.lonDeg()};
        return route == null || route.size() == 0 ? null : new double[]{route.point(0).lat(), route.point(0).lon()};
    }

    private void replan() {
        Route r = route;
        double[] from = start();
        if (r == null || from == null) return;
        int serial = ++planSerial;
        Route copy = r.copy();
        ControlLink l = link.get();
        boolean airborne = l != null && l.getTelemetry().isArmedNow(de.droiddrone.pc.MonoClock.ms(), 3000);
        Thread t = new Thread(() -> {
            try {
                RoutePlanner.Plan p = RoutePlanner.plan(copy, from, !airborne, terrain, RoutePlanner.Limits.ARDUCOPTER_DEFAULT);
                RouteProfile prof = RouteProfile.of(p, RouteProfile.Battery.DEFAULT, null);
                SwingUtilities.invokeLater(() -> {
                    if (serial != planSerial) return;
                    plan = p;
                    profile.setProfile(prof);
                    map.setRoute(route, planned(p));
                    say(prof.summary(), Theme.TEXT);
                    refreshButtons();
                });
            } catch (RoutePlanner.MissingTerrain m) {
                SwingUtilities.invokeLater(() -> {
                    if (serial == planSerial) fill(m.nodes(), serial);
                });
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    if (serial == planSerial) say("Can't plan: " + e.getMessage(), Theme.BAD);
                });
            }
        }, "routePlan");
        t.setDaemon(true);
        t.start();
    }

    /** RT-2: the terrain the plan lacks, looked up once and kept on disk; then planned again. */
    private void fill(List<double[]> nodes, int serial) {
        Thread running = filling;
        if (running != null && running.isAlive()) return;
        say("Terrain: 0 of " + nodes.size() + " looked up ...", Theme.WARN);
        filling = TerrainFill.start(terrain, nodes, lookup,
                (done, total, found) -> SwingUtilities.invokeLater(() -> {
                    if (serial == planSerial) say("Terrain: " + done + " of " + total + " looked up ...", Theme.WARN);
                }),
                () -> serial != planSerial || !isDisplayable(),
                found -> SwingUtilities.invokeLater(() -> {
                    try {
                        terrain.flush();
                    } catch (java.io.IOException ignored) {
                        // kept in memory: planned all the same
                    }
                    if (serial != planSerial) return;
                    // nothing found (offline, or no heights there): planned again it lacked the same and looked them up
                    // again, for ever - the service asked over and over. Each round must bring some, else it stops here
                    if (found > 0) replan();
                    else say("No ground heights here (offline?): can't plan. Edit the route to try again.", Theme.BAD);
                }));
    }

    private static List<double[]> planned(RoutePlanner.Plan p) {
        List<double[]> out = new ArrayList<>();
        for (RoutePlanner.Waypoint w : p.out().waypoints()) out.add(new double[]{w.lat(), w.lon()});
        return out;
    }

    /** An upload or a fence is on its way: one at a time (the phone takes one block at a time), GO waits for it. */
    private boolean sending;

    /** The plan's start this far from the drone now, or planned on the ground and now in the air (or back): planned again first. */
    static final double REPLAN_MOVED_M = 25;

    private void upload() {
        if (watchOnly.getAsBoolean() || sending) return; // GU-8: a watching PC sends nothing; one upload at a time
        ControlLink l = link.get();
        RoutePlanner.Plan p = plan;
        if (l == null || p == null) {
            say("No ArduPilot drone of this PC's own connected.", Theme.WARN);
            return;
        }
        // planned from where the drone was then: moved since (or taken off, or landed), the first leg and the take-off
        // or landing were of another start - planned again from where it is now
        double[] from = start();
        boolean airborne = l.getTelemetry().isArmedNow(de.droiddrone.pc.MonoClock.ms(), 3000);
        if (from == null || p.takeoff() == airborne
                || de.droiddrone.pc.map.Mercator.distanceM(p.startLat(), p.startLon(), from[0], from[1]) > REPLAN_MOVED_M) {
            plan = null;
            refreshButtons();
            say("The drone moved since the plan: planned again. UPLOAD then.", Theme.WARN);
            replan();
            return;
        }
        FcTelemetry.Home home = l.getTelemetry().getHome();
        if (home == null) {
            say("The drone has no home yet (no GPS fix since power-on).", Theme.WARN);
            return;
        }
        double homeAmsl = terrain.heightM(home.latDeg(), home.lonDeg());
        if (Double.isNaN(homeAmsl)) {
            say("The ground height at home isn't known yet: plan again in a moment.", Theme.WARN);
            replan();
            return;
        }
        MissionBuilder.Mission m = MissionBuilder.build(p, home.latDeg(), home.lonDeg(), homeAmsl);
        if (m.size() > MissionItem.MAX_ITEMS) { // the phone drops a bigger block unread: "didn't answer" after 15 s
            say("Too long: " + m.size() + " waypoints, " + MissionItem.MAX_ITEMS + " at most. Shorter, or more SMOOTHING.", Theme.BAD);
            return;
        }
        sending = true; // rally point and route: one upload, UPLOAD / GO / FENCE wait for both
        mission = null; // the drone's mission is being replaced: no GO on the one before
        refreshButtons();
        // AC-10: its own rally point first (where it took off, or the nearest free spot), sent only when it changed
        double[] at = rallyPlace.apply(home.latDeg(), home.lonDeg());
        returnHome = new double[]{home.latDeg(), home.lonDeg()}; // RT-9: its way home, after the route (setReturn)
        returnRally = at;
        returnHomeAmsl = homeAmsl;
        List<MissionItem> rally = de.droiddrone.pc.launch.RallyPlan.items(at[0], at[1], route == null ? 60 : route.heightM());
        if (!sameItems(rally, rallySent)) {
            say("Sending its rally point ...", Theme.WARN);
            RouteSender.send(de.droiddrone.common.MissionUpload.TYPE_RALLY, rally, l::sendMissionBlock, l.getTelemetry()::getRouteUpload, null,
                    r -> SwingUtilities.invokeLater(() -> {
                        if (!r.done()) {
                            sending = false;
                            say("Rally point: " + r.text(), Theme.BAD);
                            refreshButtons();
                            return;
                        }
                        rallySent = rally;
                        sendRoute(l, m, p);
                    }));
            return;
        }
        sendRoute(l, m, p);
    }

    /** The rally list last read back on the drone (null: none sent from here). */
    private List<MissionItem> rallySent;
    /** AC-10: where the flown drone's rally point goes, from where it took off (FleetOps.rallyForFlown). */
    private java.util.function.BiFunction<Double, Double, double[]> rallyPlace = (lat, lon) -> new double[]{lat, lon};

    void setRallyPlace(java.util.function.BiFunction<Double, Double, double[]> place) {
        rallyPlace = place == null ? (lat, lon) -> new double[]{lat, lon} : place;
    }

    static boolean sameItems(List<MissionItem> a, List<MissionItem> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).sameAs(b.get(i))) return false;
        return true;
    }

    private void sendRoute(ControlLink l, MissionBuilder.Mission m, RoutePlanner.Plan p) {
        say("Sending the route ...", Theme.WARN);
        List<MissionItem> items = m.items();
        RouteSender.send(items, l::sendMissionBlock, l.getTelemetry()::getRouteUpload,
                pct -> SwingUtilities.invokeLater(() -> {
                    if (sending) say("On the drone: " + pct + " %", Theme.WARN);
                }),
                r -> SwingUtilities.invokeLater(() -> {
                    // GO only for the plan that went up: edited meanwhile, the drone has the route before the edit
                    if (r.done() && plan == p) {
                        setReturn(l, m, p, r.text()); // RT-9: its RTL_ALT before GO
                        return;
                    }
                    sending = false;
                    say(r.text(), r.done() ? Theme.OK : Theme.BAD);
                    if (r.done()) say("Route edited while it went up: UPLOAD again.", Theme.WARN);
                    refreshButtons();
                }));
    }

    /** RT-9 / FD-6: the drone's height given the route's own (FleetOps.returnForFlown: planned with the others out alone). */
    interface ReturnPlan {
        double height(double minM, double farLat, double farLon, double homeAmsl);
    }

    private ReturnPlan returnPlan = (minM, farLat, farLon, homeAmsl) -> minM;
    private java.util.function.IntConsumer returnOnDrone = dm -> { };
    /** Sets RTL_ALT (cm) on the drone and reads it back: null when taken, else why not. The tunnel; a stand-in in tests. */
    private java.util.function.BiFunction<ControlLink, Integer, String> rtlSetter = RouteWindow::rtlThroughTunnel;

    void setReturnPlan(ReturnPlan plan, java.util.function.IntConsumer onDrone) {
        returnPlan = plan == null ? (minM, farLat, farLon, homeAmsl) -> minM : plan;
        returnOnDrone = onDrone == null ? dm -> { } : onDrone;
    }

    void setRtlSetter(java.util.function.BiFunction<ControlLink, Integer, String> setter) {
        rtlSetter = setter == null ? RouteWindow::rtlThroughTunnel : setter;
    }

    /** Where the route's RTL goes: the home and rally point of the last upload. */
    private double[] returnHome, returnRally;
    private double returnHomeAmsl;

    /**
     * RT-9: RTL_ALT over the highest ground on the straight way home (and to its rally point) from anywhere on the route,
     * clear by the route's lowest set height - a failsafe RTL clears it too - set and read back before GO.
     */
    private void setReturn(ControlLink l, MissionBuilder.Mission m, RoutePlanner.Plan p, String routeText) {
        say("Setting its return height ...", Theme.WARN);
        double[] home = returnHome, rally = returnRally;
        double homeAmsl = returnHomeAmsl;
        Route r = p.route();
        double clearance = Double.MAX_VALUE, far = -1;
        double[] farthest = {home[0], home[1]};
        for (int i = 0; i < r.size(); i++) {
            clearance = Math.min(clearance, r.heightAt(i));
            double d = de.droiddrone.pc.map.Mercator.distanceM(home[0], home[1], r.point(i).lat(), r.point(i).lon());
            if (d > far) {
                far = d;
                farthest = new double[]{r.point(i).lat(), r.point(i).lon()};
            }
        }
        double clear = clearance, farLat = farthest[0], farLon = farthest[1];
        Thread t = new Thread(() -> {
            double min = Double.NaN;
            String why = null;
            for (int attempt = 0; attempt < 2 && Double.isNaN(min); attempt++) {
                try {
                    min = RoutePlanner.rtlAltCm(p, terrain, home[0], home[1], rally[0], rally[1], clear) / 100.0;
                } catch (RoutePlanner.MissingTerrain mt) {
                    if (attempt > 0 || TerrainFill.fill(terrain, mt.nodes(), lookup, (a, b, c) -> { }, () -> false) == 0) why = "terrain not known";
                }
            }
            double[] h = {Double.NaN};
            if (why == null) {
                double lowest = min;
                try {
                    SwingUtilities.invokeAndWait(() -> h[0] = Math.max(lowest, returnPlan.height(lowest, farLat, farLon, homeAmsl)));
                } catch (Exception e) {
                    why = "stopped";
                }
            }
            int dm = Double.isNaN(h[0]) ? -1 : (int) Math.ceil(h[0] * 10 - 1e-6);
            if (why == null) why = rtlSetter.apply(l, dm * 10);
            String no = why;
            SwingUtilities.invokeLater(() -> {
                sending = false;
                if (no != null) {
                    say("Return height: " + no + " No GO.", Theme.BAD);
                } else if (plan == p) {
                    mission = m;
                    returnOnDrone.accept(dm);
                    say(routeText + " Comes home " + Math.round(dm / 10.0) + " m up.", Theme.OK);
                } else {
                    say("Route edited while it went up: UPLOAD again.", Theme.WARN);
                }
                refreshButtons();
            });
        }, "routeReturn");
        t.setDaemon(true);
        t.start();
    }

    /** RTL_ALT through the tunnel (MavSession), read back; null when taken. */
    static String rtlThroughTunnel(ControlLink l, int cm) {
        de.droiddrone.pc.setup.ardu.TunnelWire wire = new de.droiddrone.pc.setup.ardu.TunnelWire(l);
        try (de.droiddrone.pc.setup.ardu.MavSession s = new de.droiddrone.pc.setup.ardu.MavSession(wire, false)) {
            return s.setParam("RTL_ALT", cm, 2000) == cm ? null : "RTL_ALT not taken.";
        } catch (Exception e) {
            return "RTL_ALT: " + e.getMessage();
        }
    }

    /**
     * AP-9: the geofence round the drone this PC flies - the AIRSPACE areas shown and the rectangles named FENCE - up
     * to it, read back, then FENCE_TYPE polygon, FENCE_ACTION brake or land, FENCE_ENABLE on (through the tunnel).
     */
    private void fence() {
        if (watchOnly.getAsBoolean() || sending) return; // GU-8; one block at a time
        ControlLink l = link.get();
        FcTelemetry.Gps g = l == null ? null : l.getTelemetry().getGps();
        if (l == null || g == null || !g.positionOk()) {
            say("FENCE: no ArduPilot drone of this PC's own with a GPS fix.", Theme.WARN);
            return;
        }
        java.util.List<de.droiddrone.pc.route.FenceBuilder.Polygon> polys = de.droiddrone.pc.route.FenceBuilder.polygons(map.airspaceShown(),
                map.drawings(), g.latDeg(), g.lonDeg());
        java.util.List<MissionItem> items = de.droiddrone.pc.route.FenceBuilder.items(polys);
        say("FENCE: " + polys.size() + " areas, " + items.size() + " corners ...", Theme.WARN);
        sending = true;
        refreshButtons();
        RouteSender.send(de.droiddrone.common.MissionUpload.TYPE_FENCE, items, l::sendMissionBlock, l.getTelemetry()::getRouteUpload, null,
                r -> {
                    String on = r.done() ? fenceOn(l, !items.isEmpty()) : null;
                    SwingUtilities.invokeLater(() -> {
                        sending = false;
                        refreshButtons();
                        if (!r.done()) say(r.text(), Theme.BAD);
                        else say(on == null ? r.text() + (items.isEmpty() ? " Fence off." : " Fence on.") : on, on == null ? Theme.OK : Theme.BAD);
                    });
                });
    }

    /** The fence's parameters through the tunnel; null when all read back, else why not. */
    static String fenceOn(ControlLink l, boolean on) {
        de.droiddrone.pc.setup.ardu.TunnelWire wire = new de.droiddrone.pc.setup.ardu.TunnelWire(l);
        try (de.droiddrone.pc.setup.ardu.MavSession s = new de.droiddrone.pc.setup.ardu.MavSession(wire, false)) {
            if (on) {
                if (s.setParam("FENCE_TYPE", 4, 2000) != 4f) return "FENCE_TYPE not taken.";
                if (s.setParam("FENCE_ACTION", 4, 2000) != 4f) return "FENCE_ACTION not taken.";
            }
            float want = on ? 1 : 0;
            if (s.setParam("FENCE_ENABLE", want, 2000) != want) return "FENCE_ENABLE not taken.";
            return null;
        } catch (Exception e) {
            return "FENCE: " + e.getMessage();
        }
    }

    private void go() {
        if (watchOnly.getAsBoolean() || sending) return; // GU-8; not while a new route goes up
        ControlLink l = link.get();
        if (l == null || mission == null) return;
        if (!l.sendFcCommand(FcCommand.simple(FcCommand.MISSION_START))) say("GO not sent: no ArduPilot drone of this PC's own.", Theme.WARN);
        else say("GO: flying the route. At its end it holds and calls you.", Theme.OK);
    }

    private void save() {
        if (route == null) return;
        JFileChooser c = new JFileChooser(Route.folder().toFile());
        c.setSelectedFile(Route.folder().resolve(route.name() + ".route").toFile());
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            route.save(c.getSelectedFile().toPath());
            say("Saved.", Theme.OK);
        } catch (java.io.IOException e) {
            say("Not saved: " + e.getMessage(), Theme.BAD);
        }
    }

    private void load() {
        JFileChooser c = new JFileChooser(Route.folder().toFile());
        if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            setRoute(Route.load(c.getSelectedFile().toPath()));
        } catch (java.io.IOException | RuntimeException e) {
            Dialogs.message(this, "Not a route: " + e.getMessage());
        }
    }

    private void refreshButtons() {
        ControlLink l = link.get();
        boolean ardu = l != null && l.getFcInfo() != null && l.getFcInfo().getFcVariant() == de.droiddrone.common.FcInfo.FC_VARIANT_ARDUPILOT;
        // GU-8: a watching PC draws and plans routes, and sends none (hidden AND grey: a hidden button still clicks)
        boolean watch = watchOnly.getAsBoolean();
        upload.setEnabled(plan != null && ardu && !sending && !watch);
        go.setEnabled(mission != null && ardu && !sending && !watch);
        fence.setEnabled(!sending && !watch);
        for (JButton b : new JButton[]{upload, go, fence}) b.setVisible(!watch);
    }

    @Override
    public void dispose() {
        buttonTimer.stop();
        super.dispose();
    }

    /** The drone connects, or the PC starts watching, while the window is open: the buttons follow within a second. */
    private final javax.swing.Timer buttonTimer = new javax.swing.Timer(1000, e -> refreshButtons());

    /** What the window says now (tests). */
    String statusText() {
        return status.getText();
    }

    /** A plan of the route as it is now is ready (tests). */
    boolean planned() {
        return plan != null;
    }

    /** The buttons that send to a drone, for tests: UPLOAD, GO, FENCE. */
    JButton[] sendButtons() {
        return new JButton[]{upload, go, fence};
    }

    private void say(String text, java.awt.Color c) {
        status.setText(text);
        status.setForeground(c);
    }
}
