package de.droiddrone.pc.fleet;

import de.droiddrone.pc.PcConfig;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The virtual fleet program: `gradlew run --args=fleet`, or the installed app started with the argument `fleet`
 * ("DRAUPNE Link.exe" fleet, or "Virtual fleet.bat" in the zip). A separate process: it reads DRAUPNE Link's settings
 * (the link key, the names already given) and never writes them, never opens a serial port and never touches Tailscale.
 * Three virtual drones start at once; the fleet panel adds more, removes them and plays situations
 * ({@code de.droiddrone.pc.gui.FleetPanel}). DRAUPNE Link, started as usual, finds them by itself ({@link VirtualFleet}).
 */
public final class FleetMain {
    /** How many drones the fleet starts with. */
    public static final int START_DRONES = FleetLayout.PLACES.size(); // the layout's four (1.0.52)

    private FleetMain() {
    }

    public static void run(Path configFile) throws Exception {
        PcConfig config = PcConfig.load(Files.exists(configFile) ? configFile : null); // read only: never upgraded or saved
        java.util.function.Consumer<String> console = line -> System.out.println(line);
        Fleet fleet = new Fleet(config, VirtualFleet.file(), console);
        Runtime.getRuntime().addShutdownHook(new Thread(fleet::close, "fleetClose"));
        VirtualFleet.clearStop(VirtualFleet.stopFile()); // an old request isn't for this fleet
        for (int i = 0; i < START_DRONES; i++) {
            fleet.addAtLaunch(fleet.freeName(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON);
        }
        fleet.startHeartbeat();
        watchForStop(VirtualFleet.stopFile());
        System.out.println("Virtual fleet: " + fleet.drones().size() + " drones up. In DRAUPNE Link they show in the PHONE list as VIRTUAL.");
        SwingUtilities.invokeLater(() -> de.droiddrone.pc.gui.FleetPanel.open(fleet, configFile));
    }

    /**
     * DRAUPNE Link's stop request (the Overwatch demo, 2026-11-09): the program ends as closing its window does - the
     * shutdown hook closes the fleet and takes its announcement away.
     */
    private static void watchForStop(Path stop) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(VirtualFleet.STOP_POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (VirtualFleet.stopRequested(stop)) {
                    System.out.println("Virtual fleet: DRAUPNE Link asked it to close.");
                    System.exit(0);
                }
            }
        }, "fleetStopWatch");
        t.setDaemon(true);
        t.start();
    }
}
