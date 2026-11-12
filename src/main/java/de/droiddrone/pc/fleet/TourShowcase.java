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
                // beside the layout's places, never on them: the scenario's four are put exactly there (2026-11-12, the
                // pilot: "spawned ... on top of the already existing drones")
                double[] at = FleetLayout.beside(fleet.drones().size(), launch[0], launch[1]);
                VirtualDrone d = fleet.add(fleet.freeName(), at[0], at[1], at[2]);
                d.fc.setLaunch(launch[0], launch[1]); // it flew from the launch point, as addAtLaunch's
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

    /** Their scenes' drones the demo's other pilot observes: the convoy, the patrol, the mortar - something moves in each. */
    static final ShowScene.Kind[] OTHER_PILOT_WATCHES = {ShowScene.Kind.CONVOY, ShowScene.Kind.PATROL, ShowScene.Kind.MORTAR};

    public List<VirtualDrone> forOtherPilot() {
        List<VirtualDrone> out = new ArrayList<>();
        for (ShowScene.Kind k : OTHER_PILOT_WATCHES) out.add(drones.get(java.util.Arrays.asList(SCENES).indexOf(k)));
        return out;
    }

    /**
     * DEMO mode's modes (2026-11-10, the pilot: "realistic and variable ... modes. Now all spawn with the piloted icon"):
     * shuffled each time over the six - two in STANDBY, two in DEEP SLEEP (always listening or checking every 1 or 2
     * min), one idle, the sixth either. No OVERWATCH: their scenes move, and an alert before START SCENARIO would give
     * the surprise away (the scenario's four overwatch).
     * @param pc this PC's link (where the sleepers check in)
     * @return each drone's name and what it was put in, for the log
     */
    public List<String> placeDemoModes(java.net.InetSocketAddress pc, java.util.Random r) {
        List<Integer> modes = new ArrayList<>(List.of(VirtualDrone.STANDBY, VirtualDrone.STANDBY, VirtualDrone.DEEP_SLEEP,
                VirtualDrone.DEEP_SLEEP, VirtualDrone.NORMAL, r.nextBoolean() ? VirtualDrone.STANDBY : VirtualDrone.DEEP_SLEEP));
        java.util.Collections.shuffle(modes, r);
        int[] every = {0, 1, 2};
        List<String> said = new ArrayList<>();
        for (int i = 0; i < drones.size() && i < modes.size(); i++) {
            VirtualDrone d = drones.get(i);
            int m = modes.get(i);
            switch (m) {
                case VirtualDrone.STANDBY -> {
                    d.requestMode(VirtualDrone.STANDBY, 0, "the demo");
                    said.add(d.name() + " STANDBY");
                }
                case VirtualDrone.DEEP_SLEEP -> {
                    int e = every[r.nextInt(every.length)];
                    d.placeInMode(pc, VirtualDrone.DEEP_SLEEP, e, 0, 0, 0, "the demo");
                    said.add(d.name() + " DEEP SLEEP");
                }
                default -> said.add(d.name() + " idle");
            }
        }
        return said;
    }

    /** These of the six back in normal mode now (DEMO mode's observation: as woken at their check-in, at once). */
    public List<String> wake(java.util.Collection<String> names, String from) {
        List<String> woken = new ArrayList<>();
        for (VirtualDrone d : drones) {
            if (d.mode() == VirtualDrone.NORMAL) continue;
            for (String n : names) {
                if (!n.equalsIgnoreCase(d.name())) continue;
                d.requestMode(VirtualDrone.NORMAL, 0, from);
                woken.add(d.name());
            }
        }
        return woken;
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
