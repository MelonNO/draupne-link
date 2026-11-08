package de.droiddrone.pc.gui;

import de.droiddrone.pc.fleet.TourScenario;
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

    TourFinale(MainWindow main) {
        this.main = main;
    }

    /** The four drones, their flights and the units on the map (Swing thread); false with {@link #refused()} saying why. */
    boolean start() {
        if (scenario != null) return true;
        if (!VirtualFleet.announced().isEmpty()) {
            refused = "Close the virtual fleet first.";
            return false;
        }
        try {
            scenario = TourScenario.start(main.config(), VirtualFleet.file(),
                    line -> SwingUtilities.invokeLater(() -> main.logLine("Tour: " + line)), new TourScenario.Listener() {
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
                    });
        } catch (java.io.IOException | RuntimeException e) {
            refused = "The tour's drones didn't start: " + e.getMessage();
            return false;
        }
        watcher = scenario.watcher().name();
        for (VirtualDrone d : scenario.drones()) names.add(d.name());
        List<MapView.Kept> flights = new ArrayList<>();
        for (TourScenario.Flight f : scenario.flights()) flights.add(new MapView.Kept(f.name(), f.points()));
        main.tourFlights(flights);
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
        scenario.placeModes(new InetSocketAddress(InetAddress.getLoopbackAddress(), main.tourLinkPort()), tokens, 2);
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

    boolean started() {
        return scenario != null;
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

    @Override
    public void close() {
        TourScenario s = scenario;
        scenario = null;
        if (s != null) {
            s.close();
            for (String n : names) main.tourForget(n);
        }
        names.clear();
        main.tourFlights(List.of());
        main.tourUnits(List.of());
    }
}
