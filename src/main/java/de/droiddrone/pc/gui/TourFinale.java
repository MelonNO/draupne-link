package de.droiddrone.pc.gui;

import de.droiddrone.pc.fleet.TourScenario;
import de.droiddrone.pc.fleet.TourShowcase;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.pc.map.CameraFootprint;

import javax.swing.SwingUtilities;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The tour's fleet steps and finale (1.0.52, the pilot): four virtual drones from {@link TourScenario} round their
 * launch point - three in OVERWATCH, one in DEEP SLEEP - with their flights and two units on the map, shown before
 * anything moves; then, at the pilot's NEXT on the eyes, the BTR drives into the first one's sector, the alert,
 * CONNECT, the BTR heard, and the crash into it that ends the tour. Started by the fleet steps, always closed when the
 * tour ends.
 * <p>
 * Not with the pilot's own virtual fleet running: both would write the same announcement file, so the finale is
 * refused rather than the pilot's fleet stopped.
 */
final class TourFinale implements AutoCloseable {
    private final MainWindow main;
    private TourScenario scenario;
    private String watcher;
    private final List<String> names = new ArrayList<>();
    private volatile boolean alerted, connected, crashed;
    /** Why it didn't start; null when it did (or hasn't been asked). */
    private String refused;
    /** It didn't start because the pilot's own virtual fleet runs (the tour then offers to close it). */
    private boolean fleetOpen;

    /** DEMO mode's fleet, which every part shares (the six, the pilot PC, the four); null: each part its own (the tour). */
    private final java.util.function.Supplier<de.droiddrone.pc.fleet.Fleet> shared;
    /** What its log lines start with. */
    private final String prefix;

    TourFinale(MainWindow main) {
        this(main, null, "Tour: ");
    }

    TourFinale(MainWindow main, java.util.function.Supplier<de.droiddrone.pc.fleet.Fleet> shared, String prefix) {
        this.main = main;
        this.shared = shared;
        this.prefix = prefix;
    }

    // ---- the six drones of the PILOT and OBSERVER steps (2026-11-09)

    private TourShowcase showcase;
    private List<String> showcaseNames = List.of();

    /** The six drones up, the racer first (Swing thread); false with {@link #refused()} saying why. */
    boolean startShowcase() {
        if (showcase != null) return true;
        fleetOpen = false;
        if (shared == null && !VirtualFleet.announcedNow().isEmpty()) {
            refused = "Close the virtual fleet first.";
            fleetOpen = true;
            return false;
        }
        try {
            showcase = shared != null ? TourShowcase.startIn(shared.get()) : TourShowcase.start(main.config(), VirtualFleet.file(),
                    line -> SwingUtilities.invokeLater(() -> main.logLine(prefix + line)));
        } catch (java.io.IOException | RuntimeException e) {
            refused = "The tour's drones didn't start: " + e.getMessage();
            return false;
        }
        showcaseNames = showcase.names();
        main.tourHost().begin(showcaseNames);
        main.tourScanNames(); // the PHONE list and observation know them within a second or two
        refused = null;
        return true;
    }

    boolean showcaseStarted() {
        return showcase != null;
    }

    /** The drone team's drone, flown in the PILOT steps; null without the six. */
    String racer() {
        TourShowcase s = showcase;
        return s == null ? null : s.racer().name();
    }

    /** The six's names (empty without them). */
    List<String> showcaseNames() {
        return showcaseNames;
    }

    /** The six in normal mode, observable (the OBSERVER steps; the racer was put in STANDBY before). */
    void wakeShowcase() {
        TourShowcase s = showcase;
        if (s != null) s.wakeAll("the tour");
    }

    /** The six gone (the finale's fleet comes next, or the tour is over): off this PC's lists now, their files at the end. */
    void closeShowcase() {
        TourShowcase s = showcase;
        showcase = null;
        if (s == null) return;
        s.close();
        main.tourHost().forget(showcaseNames);
    }

    /** The four drones, their flights and the units on the map (Swing thread); false with {@link #refused()} saying why. */
    boolean start() {
        if (scenario != null) return true;
        if (shared == null) closeShowcase(); // one fleet of the tour's at a time: both would write the one announcement file
        fleetOpen = false;
        // read now, not the cached list (2026-11-09: a fleet closed a moment ago still counted as open)
        if (shared == null && !VirtualFleet.announcedNow().isEmpty()) {
            refused = "Close the virtual fleet first.";
            fleetOpen = true;
            return false;
        }
        alerted = connected = crashed = false;
        TourScenario.Listener heard = new TourScenario.Listener() {
                        @Override
                        public void overwatchAlert(VirtualDrone w) {
                            alerted = true;
                        }

                        @Override
                        public void connected(VirtualDrone w) {
                            connected = true;
                        }

                        @Override
                        public void crashed(VirtualDrone w) {
                            crashed = true;
                        }
                    };
        try {
            scenario = shared != null ? TourScenario.startIn(shared.get(), heard) : TourScenario.start(main.config(), VirtualFleet.file(),
                    line -> SwingUtilities.invokeLater(() -> main.logLine(prefix + line)), heard);
        } catch (java.io.IOException | RuntimeException e) {
            refused = "The tour's drones didn't start: " + e.getMessage();
            return false;
        }
        watcher = scenario.watcher().name();
        for (VirtualDrone d : scenario.drones()) names.add(d.name());
        main.tourHost().begin(names);
        List<MapView.Kept> flights = new ArrayList<>();
        for (TourScenario.Flight f : scenario.flights()) flights.add(new MapView.Kept(f.name(), f.points()));
        main.tourHost().flights(flights);
        main.tourUnits(TourScenario.units());
        // each drone's pack as this PC read it when it landed
        for (int i = 0; i < scenario.drones().size(); i++) {
            TourScenario.Flight f = scenario.flights().get(i);
            long landed = f.points().get(f.points().size() - 1).timeMs();
            main.tourLastCell(scenario.drones().get(i).name(), scenario.drones().get(i).cellVolts(), System.currentTimeMillis() - landed);
        }
        refused = null;
        return true;
    }

    /** The modes: each overwatch drone's token registered with DRAUPNE Link first, so its eye and camera view show. */
    void placeModes() {
        if (scenario == null) return;
        Map<String, Integer> tokens = new HashMap<>();
        for (VirtualDrone d : scenario.overwatchers()) {
            double[] p = scenario.pose(d);
            CameraFootprint.Pose pose = new CameraFootprint.Pose(p[0], p[1], 0.5, p[2], 0, 0, 0, p[3], p[3] * 9 / 16);
            tokens.put(d.name(), main.tourWatch(d.name(), pose));
        }
        // HIGH (1.0.58): the patrol and the sentry by the camouflaged BTR are camouflage on camouflage, found on HIGH only
        scenario.placeModes(new InetSocketAddress(InetAddress.getLoopbackAddress(), main.tourLinkPort()), tokens, 3);
    }

    /** The BTR sets off (the eyes' step, at the pilot's NEXT). */
    void driveIn() {
        if (scenario != null) scenario.driveInNow();
    }

    /** The middle of the tour's area {lat, lon}: the drones, the units and the launch point. */
    double[] centre() {
        double[] lo = {90, 180}, hi = {-90, -180};
        List<String> all = new ArrayList<>();
        all.add(TourScenario.LAUNCH);
        for (de.droiddrone.pc.fleet.FleetLayout.Place p : TourScenario.PLACES) all.add(p.at());
        for (TourScenario.Unit u : TourScenario.UNITS) all.add(u.at());
        for (String g : all) {
            double[] p = de.droiddrone.pc.map.Coordinates.parse(g);
            for (int k = 0; k < 2; k++) {
                lo[k] = Math.min(lo[k], p[k]);
                hi[k] = Math.max(hi[k], p[k]);
            }
        }
        return new double[]{(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2};
    }

    String watcher() {
        return watcher;
    }

    /** The tour's drones' names. */
    List<String> names() {
        return List.copyOf(names);
    }

    String refused() {
        return refused;
    }

    /** The last start was refused because the pilot's virtual fleet runs. */
    boolean fleetOpen() {
        return fleetOpen;
    }

    boolean started() {
        return scenario != null;
    }

    /**
     * The tour's virtual fleet while its drones run - the six of the showcase, or the finale's - else null (the
     * spectator steps, which come right after the showcase's, put their pilot PC in it).
     */
    de.droiddrone.pc.fleet.Fleet fleet() {
        if (shared != null) return shared.get();
        TourScenario s = scenario;
        if (s != null) return s.fleet();
        TourShowcase c = showcase;
        return c == null ? null : c.fleet();
    }

    boolean alerted() {
        return alerted;
    }

    boolean connected() {
        return connected;
    }

    boolean crashed() {
        return crashed;
    }

    /**
     * The four gone, with their flights, units and traces - the six and the rest stay (DEMO mode's sequence again).
     */
    void closeScenario() {
        TourScenario s = scenario;
        scenario = null;
        if (s == null) return;
        s.close();
        main.tourHost().forget(List.copyOf(names));
        names.clear();
        watcher = null;
        alerted = connected = crashed = false;
        main.tourHost().flights(List.of());
        main.tourUnits(List.of());
    }

    @Override
    public void close() {
        closeShowcase();
        TourScenario s = scenario;
        scenario = null;
        if (s != null) s.close();
        main.tourHost().cleanUp(); // every trace of the tour's drones and flights (1.0.52, the pilot)
        names.clear();
        main.tourHost().flights(List.of());
        main.tourUnits(List.of());
    }
}
