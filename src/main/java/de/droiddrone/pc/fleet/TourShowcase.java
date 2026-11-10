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
    /** Its own fleet (closed with it), or a shared one (DEMO mode: only its drones go). */
    private final boolean ownsFleet;

    private TourShowcase(Fleet fleet, List<VirtualDrone> drones, boolean ownsFleet) {
        this.fleet = fleet;
        this.drones = drones;
        this.ownsFleet = ownsFleet;
    }

    /**
     * The six drones up and announced, each with its scene.
     * @param config       DRAUPNE Link's settings (the key; the names its map has, which the drones don't take)
     * @param announceFile where DRAUPNE Link looks for virtual drones ({@link VirtualFleet#file()}), or null (tests)
     */
    public static TourShowcase start(PcConfig config, Path announceFile, Consumer<String> log) throws IOException {
        Fleet fleet = new Fleet(config, announceFile, log);
        try {
            TourShowcase s = new TourShowcase(fleet, addSix(fleet), true);
            fleet.startHeartbeat();
            return s;
        } catch (IOException | RuntimeException e) {
            fleet.close();
            throw e;
        }
    }

    /** The six in a fleet that runs already (DEMO mode's, with the other demo drones): closing takes only these. */
    public static TourShowcase startIn(Fleet fleet) throws IOException {
        List<VirtualDrone> drones = addSix(fleet);
        fleet.announce();
        return new TourShowcase(fleet, drones, false);
    }

    private static List<VirtualDrone> addSix(Fleet fleet) throws IOException {
        double[] launch = Coordinates.parse(FleetLayout.LAUNCH);
        List<VirtualDrone> drones = new ArrayList<>();
        try {
            for (ShowScene.Kind k : SCENES) {
                VirtualDrone d = fleet.addAtLaunch(fleet.freeName(), launch[0], launch[1]);
                d.showScene(new ShowScene(k, d.fc.northM(), d.fc.eastM(), d.fc.headingDeg()));
                d.setBeeperless(true);
                drones.add(d);
            }
            return drones;
        } catch (IOException | RuntimeException e) {
            for (VirtualDrone d : drones) fleet.remove(d);
            throw e;
        }
    }

    /** The drone team's drone: the one the tour has the pilot fly. */
    public VirtualDrone racer() {
        return drones.get(0);
    }

    /**
     * Each of the six back in normal mode (2026-11-10, the pilot: "in the tour with the observation mode the virtual
     * drone 6 is stuck at waiting for picture"): the racer, put in STANDBY a few steps before, refused to be observed -
     * as a real phone does - so its tile waited for good.
     */
    public void wakeAll(String from) {
        for (VirtualDrone d : drones) if (d.mode() != VirtualDrone.NORMAL) d.requestMode(VirtualDrone.NORMAL, 0, from);
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
        if (ownsFleet) fleet.close();
        else for (VirtualDrone d : drones) fleet.remove(d);
    }
}
