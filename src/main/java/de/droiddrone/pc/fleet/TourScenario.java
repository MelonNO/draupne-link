package de.droiddrone.pc.fleet;

import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.PcConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The tour's finale (the pilot, 2026-11-08): three virtual drones that look and behave like real ones, one in each
 * mode the fleet panel explains, and a BTR in front of the overwatch drone for the pilot to find, connect to, hear and
 * fly into. Built for the tour to drive; nothing here touches the window.
 * <p>
 * How a tour uses it, in order:
 * <ol>
 * <li>{@link #start}: a fleet of its own with three drones - {@link #watcher()} (the first free name from Zulu down,
 * the BTR's watcher), {@link #sleeper()} and {@link #active()} - 30 m apart at the fleet's usual place, announced to
 * DRAUPNE Link through the given file like the fleet program's drones (the PHONE list, the map, the fleet panel find
 * them; they are VIRTUAL wherever they show, as every virtual drone is). The watcher's camera sees the simulation
 * world: the BTR waits out of the picture.</li>
 * <li>{@link #placeModes}: once DRAUPNE Link has registered the watcher's overwatch token with its OverwatchHub
 * ({@code hub.watch(watcher().name(), token, pose)}; {@link #watcherPose()} gives the pose), this puts the sleeper to
 * DEEP SLEEP (checks every minute) and the watcher to OVERWATCH (always listening), both calling DRAUPNE Link's link
 * address; the active one stays awake (ACTIVE). The BTR sets off {@link #DRIVE_IN_AFTER_MS} later - the motion
 * detection's strict first seconds over - drives into the picture and loops there; the real detector finds it and
 * the watcher's overwatch alert goes to DRAUPNE Link as any phone's does: {@link Listener#overwatchAlert}.
 * {@link #driveInNow} brings it in at once (the detection may then take up to its 20 s young phase).</li>
 * <li>The pilot presses CONNECT on the alert card: the watcher wakes and DRAUPNE Link connects as usual:
 * {@link Listener#connected}. Its microphone now carries the BTR in stereo - the engine panned and muffled by where it
 * is from the drone's nose, louder near, Doppler-shifted - mixed with the drone's own sound. Note: DRAUPNE Link's live
 * sound mutes itself at arming; the tour unmutes it (MUTE / M) if the BTR should be heard in the air.</li>
 * <li>The pilot arms and flies with the radio, exactly as with any virtual drone (no radio: as connecting to a real
 * phone without one - nothing special here). Flying into the BTR's box: the drone disarms, falls, and its link goes
 * silent for good, as a broken drone's: {@link Listener#crashed} (DRAUPNE Link sees LINK LOST).</li>
 * <li>{@link #close}: the drones stop and the announcement goes.</li>
 * </ol>
 * Listeners run on the fleet's own threads: hand anything for the window to the Swing thread.
 */
public final class TourScenario implements AutoCloseable {
    /** The BTR sets off this long after {@link #placeModes}: the overwatch countdown (1 s), the detection's warm-up (3 s) and young phase (20 s), and a margin. */
    public static final long DRIVE_IN_AFTER_MS = 26_000;

    /** What happens in the scenario. Default methods: implement what you need. Called on the fleet's threads. */
    public interface Listener {
        /** The watcher's overwatch found the BTR and is calling DRAUPNE Link. */
        default void overwatchAlert(VirtualDrone watcher) {
        }

        /** DRAUPNE Link connected to the watcher as pilot. */
        default void connected(VirtualDrone watcher) {
        }

        /** The watcher flew into the BTR: broken, its link silent. */
        default void crashed(VirtualDrone watcher) {
        }
    }

    private final Fleet fleet;
    private final VirtualDrone watcher, sleeper, active;
    private final BtrScene btr;

    private TourScenario(Fleet fleet, VirtualDrone watcher, VirtualDrone sleeper, VirtualDrone active, BtrScene btr) {
        this.fleet = fleet;
        this.watcher = watcher;
        this.sleeper = sleeper;
        this.active = active;
        this.btr = btr;
    }

    /**
     * Starts the three drones (the BTR waiting out of the picture).
     * @param config       DRAUPNE Link's settings (the key; the names its map has, which the drones don't take)
     * @param announceFile where DRAUPNE Link looks for virtual drones ({@link VirtualFleet#file()}), or null (tests)
     */
    public static TourScenario start(PcConfig config, Path announceFile, Consumer<String> log, Listener listener) throws IOException {
        Fleet fleet = new Fleet(config, announceFile, log);
        try {
            double lat = Fleet.DEFAULT_LAT, lon = Fleet.DEFAULT_LON;
            VirtualDrone watcher = fleet.add(fleet.freeName(), lat, lon, 0);
            double[] p = fleet.nextPlace(lat, lon);
            VirtualDrone sleeper = fleet.add(fleet.freeName(), p[0], p[1], 0);
            p = fleet.nextPlace(lat, lon);
            VirtualDrone active = fleet.add(fleet.freeName(), p[0], p[1], 0);
            BtrScene btr = new BtrScene(watcher.fc.northM(), watcher.fc.eastM(), watcher.fc.headingDeg(), Long.MAX_VALUE / 4);
            Listener l = listener == null ? new Listener() {
            } : listener;
            watcher.attachTarget(btr, new VirtualDrone.Events() {
                @Override
                public void alert(VirtualDrone d) {
                    l.overwatchAlert(d);
                }

                @Override
                public void connected(VirtualDrone d) {
                    l.connected(d);
                }

                @Override
                public void crashed(VirtualDrone d) {
                    l.crashed(d);
                }
            });
            fleet.startHeartbeat();
            return new TourScenario(fleet, watcher, sleeper, active, btr);
        } catch (IOException | RuntimeException e) {
            fleet.close();
            throw e;
        }
    }

    /**
     * The modes, calling DRAUPNE Link: the sleeper to DEEP SLEEP, the watcher to OVERWATCH; the BTR sets off
     * {@link #DRIVE_IN_AFTER_MS} later.
     * @param pcLink         DRAUPNE Link's link address as a phone sees it (127.0.0.1 and its link port)
     * @param overwatchToken the token DRAUPNE Link registered for the watcher (OverwatchHub.watch)
     * @param sensitivity    overwatch's 1 LOW .. 3 HIGH
     */
    public void placeModes(InetSocketAddress pcLink, int overwatchToken, int sensitivity) {
        sleeper.placeInMode(pcLink, VirtualDrone.DEEP_SLEEP, 1, 2, 0, 0);
        watcher.placeInMode(pcLink, VirtualDrone.OVERWATCH, SleepCheck.ALWAYS, sensitivity, 0, overwatchToken);
        btr.driveFrom(System.currentTimeMillis() + DRIVE_IN_AFTER_MS);
    }

    /** The BTR sets off now. */
    public void driveInNow() {
        btr.driveFrom(System.currentTimeMillis());
    }

    /** The overwatch drone, the BTR in its picture. */
    public VirtualDrone watcher() {
        return watcher;
    }

    /** The one put to DEEP SLEEP. */
    public VirtualDrone sleeper() {
        return sleeper;
    }

    /** The one left awake (ACTIVE). */
    public VirtualDrone active() {
        return active;
    }

    /** Where the watcher stands and looks: {lat, lon, heading degrees, camera's horizontal field of view degrees}. */
    public double[] watcherPose() {
        return new double[]{watcher.fc.latDeg(), watcher.fc.lonDeg(), watcher.fc.headingDeg(), Scene.HFOV_DEG};
    }

    /** Where the BTR is now: {lat, lon, heading degrees} (for a map, e.g.). */
    public double[] btrPosition() {
        double[] q = btr.pose(System.currentTimeMillis());
        double lat0 = watcher.fc.latDeg() - watcher.fc.northM() / 111_320.0;
        double lon0 = watcher.fc.lonDeg() - watcher.fc.eastM() / (111_320.0 * Math.cos(Math.toRadians(lat0)));
        return new double[]{lat0 + q[0] / 111_320.0, lon0 + q[1] / (111_320.0 * Math.cos(Math.toRadians(lat0))), q[2]};
    }

    @Override
    public void close() {
        fleet.close();
    }
}
