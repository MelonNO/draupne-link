package de.droiddrone.pc.fleet;

import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.map.Coordinates;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The tour's six drones for its PILOT and OBSERVER steps (2026-11-09, the pilot: select a virtual phone, PILOT UAV
 * onto it; then observe six drones, each with something to see; 2026-11-10: tactical scenes): a fleet of their own
 * round the virtual fleet's launch point, announced to DRAUPNE Link like the fleet program's (the PHONE list,
 * observation and the map find them). The first, {@link #racer()}, sees the drone team ({@link ShowScene.Kind#DRONES});
 * each of the others its own scene.
 * Closed before the tour's finale starts its own fleet (both would write the one announcement file), and when the tour
 * ends.
 */
public final class TourShowcase implements AutoCloseable {
    /** What each drone sees, in the order they are made. */
    static final ShowScene.Kind[] SCENES = ShowScene.Kind.values();

    private final Fleet fleet;
    private final List<VirtualDrone> drones;

    private TourShowcase(Fleet fleet, List<VirtualDrone> drones) {
        this.fleet = fleet;
        this.drones = drones;
    }

    /**
     * The six drones up and announced, each with its scene.
     * @param config       DRAUPNE Link's settings (the key; the names its map has, which the drones don't take)
     * @param announceFile where DRAUPNE Link looks for virtual drones ({@link VirtualFleet#file()}), or null (tests)
     */
    public static TourShowcase start(PcConfig config, Path announceFile, Consumer<String> log) throws IOException {
        Fleet fleet = new Fleet(config, announceFile, log);
        try {
            double[] launch = Coordinates.parse(FleetLayout.LAUNCH);
            List<VirtualDrone> drones = new ArrayList<>();
            for (ShowScene.Kind k : SCENES) {
                VirtualDrone d = fleet.addAtLaunch(fleet.freeName(), launch[0], launch[1]);
                d.showScene(new ShowScene(k, d.fc.northM(), d.fc.eastM(), d.fc.headingDeg()));
                d.setBeeperless(true);
                drones.add(d);
            }
            fleet.startHeartbeat();
            return new TourShowcase(fleet, drones);
        } catch (IOException | RuntimeException e) {
            fleet.close();
            throw e;
        }
    }

    /** The drone team's drone: the one the tour has the pilot fly. */
    public VirtualDrone racer() {
        return drones.get(0);
    }

    public List<VirtualDrone> drones() {
        return List.copyOf(drones);
    }

    /** Its fleet (the tour's spectator steps put their virtual pilot PC in it). */
    public Fleet fleet() {
        return fleet;
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (VirtualDrone d : drones) out.add(d.name());
        return out;
    }

    @Override
    public void close() {
        fleet.close();
    }
}
