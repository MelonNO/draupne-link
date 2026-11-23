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
import javax.swing.JOptionPane;
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
    private Route route;
    private RoutePlanner.Plan plan;
    private MissionBuilder.Mission mission;
    private int planSerial;
    private volatile Thread filling;

    RouteWindow(Window owner, MapView map, Elevation elevation, Supplier<ControlLink> link, Supplier<FlightTrack> track) {
        super(owner, "Route", ModalityType.MODELESS);
        this.map = map;
        this.elevation = elevation;
        this.link = link;
        this.track = track;
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
        for (JButton b : new JButton[]{reverse, save, load, clear, upload, go}) buttons.add(b);
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
        if (!isVisible()) setVisible(true);
        toFront();
    }

    void setRoute(Route r) {
        route = r;
        plan = null;
        mission = null;
        settings.setRoute(r);
        profile.setProfile(null);
        map.setRoute(r, null);
        if (r != null) replan();
        else say("Draw a route: ROUTE, then click points on the map.", Theme.MUTED);
        refreshButtons();
    }

    private void edited() {
        settings.setRoute(route);
        mission = null;
        map.setRoute(route, null);
        replan();
        refreshButtons();
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
            JOptionPane.showMessageDialog(this, "Not a route: " + e.getMessage());
        }
    }

    private void refreshButtons() {
        ControlLink l = link.get();
        boolean ardu = l != null && l.getFcInfo() != null && l.getFcInfo().getFcVariant() == de.droiddrone.common.FcInfo.FC_VARIANT_ARDUPILOT;
        upload.setEnabled(plan != null && ardu);
        go.setEnabled(mission != null && ardu);
    }

    private void say(String text, java.awt.Color c) {
        status.setText(text);
        status.setForeground(c);
    }
}
