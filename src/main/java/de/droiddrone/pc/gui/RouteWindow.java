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
    private final Elevation elevation;
    private final Supplier<ControlLink> link;
    private final Supplier<FlightTrack> track;
    private final TerrainStore terrain = TerrainStore.onDisk();
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
        super(owner, "Route", ModalityType.MODELESS);
        this.map = map;
        this.elevation = elevation;
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
                SwingUtilities.invokeLater(() -> say("Can't plan: " + e.getMessage(), Theme.BAD));
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
        filling = TerrainFill.start(terrain, nodes, TerrainFill.of(elevation),
                (done, total, found) -> SwingUtilities.invokeLater(() -> say("Terrain: " + done + " of " + total + " looked up ...", Theme.WARN)),
                () -> serial != planSerial || !isDisplayable(),
                found -> SwingUtilities.invokeLater(() -> {
                    try {
                        terrain.flush();
                    } catch (java.io.IOException ignored) {
                        // kept in memory: planned all the same
                    }
                    if (serial == planSerial) replan();
                }));
    }

    private static List<double[]> planned(RoutePlanner.Plan p) {
        List<double[]> out = new ArrayList<>();
        for (RoutePlanner.Waypoint w : p.out().waypoints()) out.add(new double[]{w.lat(), w.lon()});
        return out;
    }

    private void upload() {
        ControlLink l = link.get();
        RoutePlanner.Plan p = plan;
        if (l == null || p == null) {
            say("No ArduPilot drone of this PC's own connected.", Theme.WARN);
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
        upload.setEnabled(false);
        say("Sending the route ...", Theme.WARN);
        List<MissionItem> items = m.items();
        RouteSender.send(items, l::sendMissionBlock, l.getTelemetry()::getRouteUpload,
                pct -> SwingUtilities.invokeLater(() -> say("On the drone: " + pct + " %", Theme.WARN)),
                r -> SwingUtilities.invokeLater(() -> {
                    say(r.text(), r.done() ? Theme.OK : Theme.BAD);
                    if (r.done()) mission = m;
                    refreshButtons();
                }));
    }

    /**
     * AP-9: the geofence round the drone this PC flies - the AIRSPACE areas shown and the rectangles named FENCE - up
     * to it, read back, then FENCE_TYPE polygon, FENCE_ACTION brake or land, FENCE_ENABLE on (through the tunnel).
     */
    private void fence() {
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
        RouteSender.send(de.droiddrone.common.MissionUpload.TYPE_FENCE, items, l::sendMissionBlock, l.getTelemetry()::getRouteUpload, null,
                r -> {
                    if (!r.done()) {
                        SwingUtilities.invokeLater(() -> say(r.text(), Theme.BAD));
                        return;
                    }
                    String on = fenceOn(l, !items.isEmpty());
                    SwingUtilities.invokeLater(() -> say(on == null ? r.text() + (items.isEmpty() ? " Fence off." : " Fence on.") : on, on == null ? Theme.OK : Theme.BAD));
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
        upload.setEnabled(plan != null && ardu);
        go.setEnabled(mission != null && ardu);
        // GU-8: a watching PC draws and plans routes, and sends none
        boolean watch = watchOnly.getAsBoolean();
        for (JButton b : new JButton[]{upload, go, fence}) b.setVisible(!watch);
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
