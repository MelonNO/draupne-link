package de.droiddrone.pc.gui;

import de.droiddrone.pc.fleet.TourScenario;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.pc.map.CameraFootprint;

import javax.swing.SwingUtilities;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * The tour's fleet steps and finale (1.0.52, the pilot): three virtual drones from {@link TourScenario} for the fleet
 * panel, the watcher in OVERWATCH with a BTR driving into its picture, the alert, CONNECT, the BTR heard in the air,
 * and the crash into it that ends the tour. Started by the fleet steps, always closed when the tour ends.
 * <p>
 * Not with the pilot's own virtual fleet running: both would write the same announcement file, so the finale is
 * refused rather than the pilot's fleet stopped.
 */
final class TourFinale implements AutoCloseable {
    private final MainWindow main;
    private TourScenario scenario;
    private String watcher;
    private volatile boolean alerted, connected, crashed;
    /** Why it didn't start; null when it did (or hasn't been asked). */
    private String refused;

    TourFinale(MainWindow main) {
        this.main = main;
    }

    /** The three drones (Swing thread); false with {@link #refused()} saying why. */
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
        refused = null;
        return true;
    }

    /** The modes: once DRAUPNE Link knows the watcher's overwatch token, the BTR sets off 26 s later. */
    void placeModes() {
        if (scenario == null) return;
        double[] p = scenario.watcherPose();
        CameraFootprint.Pose pose = new CameraFootprint.Pose(p[0], p[1], 0.5, p[2], 0, 0, 0, p[3], p[3] * 9 / 16);
        int token = main.tourWatch(watcher, pose);
        scenario.placeModes(new InetSocketAddress(InetAddress.getLoopbackAddress(), main.tourLinkPort()), token, 2);
    }

    String watcher() {
        return watcher;
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
            if (watcher != null) main.tourForget(watcher);
        }
        main.tourKeepSound(false);
    }
}
