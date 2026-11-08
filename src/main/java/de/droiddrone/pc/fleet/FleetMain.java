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
    public static final int START_DRONES = 3;

    private FleetMain() {
    }

    public static void run(Path configFile) throws Exception {
        PcConfig config = PcConfig.load(Files.exists(configFile) ? configFile : null); // read only: never upgraded or saved
        java.util.function.Consumer<String> console = line -> System.out.println(line);
        Fleet fleet = new Fleet(config, VirtualFleet.file(), console);
        Runtime.getRuntime().addShutdownHook(new Thread(fleet::close, "fleetClose"));
        for (int i = 0; i < START_DRONES; i++) {
            double[] at = fleet.nextPlace(Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON);
            fleet.add(fleet.freeName(), at[0], at[1], 0);
        }
        fleet.startHeartbeat();
        System.out.println("Virtual fleet: " + fleet.drones().size() + " drones up. In DRAUPNE Link they show in the PHONE list as VIRTUAL.");
        SwingUtilities.invokeLater(() -> de.droiddrone.pc.gui.FleetPanel.open(fleet, configFile));
    }
}
