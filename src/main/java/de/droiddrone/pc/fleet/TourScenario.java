package de.droiddrone.pc.fleet;

import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.MapGraphics;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The tour's fleet steps and finale (the pilot, 2026-11-08): four virtual drones that look and behave like real ones,
 * set out round a launch point as a unit would leave them - three in OVERWATCH, each looking its own way, one in DEEP
 * SLEEP - each with the flight that took it there, batteries and phones as after hours outside, two units on the map,
 * and a BTR on patrol in front of the first one for the pilot to find, connect to, hear and fly into. Built for the
 * tour to drive; nothing here touches the window.
 * <p>
 * How a tour uses it, in order:
 * <ol>
 * <li>{@link #start}: a fleet of its own with the four drones at {@link #PLACES} (names: the first free from Zulu
 * down), announced to DRAUPNE Link through the given file like the fleet program's drones (the PHONE list, the map,
 * the fleet panel find them; VIRTUAL wherever a list shows them). Nothing moves yet: the BTR waits out of the watcher's
 * picture. {@link #flights()} and {@link #units()} are for the map.</li>
 * <li>{@link #placeModes}: once DRAUPNE Link has registered each overwatch drone's token with its OverwatchHub
 * ({@code hub.watch(name, token, pose)}; {@link #pose} gives the pose), this puts the three to OVERWATCH and the
 * fourth to DEEP SLEEP, all calling DRAUPNE Link's link address. Their eyes and camera views show on the map.</li>
 * <li>{@link #driveInNow} (the tour's step on the eyes, when the pilot presses NEXT): the BTR sets off and drives into
 * the {@link #watcher()}'s picture; the real detector finds it and the watcher's overwatch alert goes to DRAUPNE Link
 * as any phone's does: {@link Listener#overwatchAlert}. (Placed at least {@link #DRIVE_IN_AFTER_MS} before, the
 * detection's strict first seconds are over by then.)</li>
 * <li>The pilot presses CONNECT on the alert card: the watcher wakes and DRAUPNE Link connects as usual:
 * {@link Listener#connected}. Its microphone carries the BTR (a real recording) in stereo from where it is. At the
 * arming the motors spin up (a real FPV quad), and DRAUPNE Link mutes the armed drone as always.</li>
 * <li>The pilot arms and flies with the radio, as with any virtual drone (no radio: as connecting to a real phone
 * without one). Flying into the BTR: the drone disarms, falls, and its link goes silent for good, as a broken drone's:
 * {@link Listener#crashed} (DRAUPNE Link sees LINK LOST).</li>
 * <li>{@link #close}: the drones stop and the announcement goes.</li>
 * </ol>
 * Listeners run on the fleet's own threads: hand anything for the window to the Swing thread.
 */
public final class TourScenario implements AutoCloseable {
    /** Placed this long before {@link #driveInNow}, the BTR is found at once: the overwatch countdown (1 s), the detection's warm-up (3 s) and young phase (20 s), and a margin. */
    public static final long DRIVE_IN_AFTER_MS = 26_000;

    /** Where all four took off: {@link FleetLayout#LAUNCH} (the virtual fleet's layout too). */
    public static final String LAUNCH = FleetLayout.LAUNCH;

    /** The pilot's layout ({@link FleetLayout#PLACES}, shared with the virtual fleet): the first one sees the BTR. */
    public static final List<FleetLayout.Place> PLACES = FleetLayout.PLACES;

    /**
     * After hours outside, all different: the drone's pack at rest after its flight (V per cell), the phone's battery
     * (%), its temperature (C, a cold day), its signal bars, its CHECK EVERY (minutes; 255 always listening), how long
     * ago it landed (minutes).
     */
    private static final double[][] OUTSIDE = {
            {3.74, 63, 8.5, 3, SleepCheck.ALWAYS, 152},
            {3.66, 48, 6.0, 2, 2, 175},
            {3.79, 71, 9.5, 4, 5, 131},
            {3.58, 39, 5.5, 1, 1, 214}};

    /** A unit on the map for the fleet steps: a friendly infantry platoon, a hostile mechanised company. */
    public record Unit(String at, MapGraphics.UnitType type, MapGraphics.Affiliation side, MapGraphics.Echelon size, String name) {}

    public static final List<Unit> UNITS = List.of(
            new Unit("32V PN 49558 50584", MapGraphics.UnitType.INFANTRY, MapGraphics.Affiliation.FRIEND, MapGraphics.Echelon.PLATOON, "1 PLT"),
            new Unit("32V PN 48490 51933", MapGraphics.UnitType.MECHANIZED, MapGraphics.Affiliation.HOSTILE, MapGraphics.Echelon.COMPANY, "ENY COY"));

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

    /** A drone's flight from the launch point to where it sits, named as DRAUPNE Link names a kept flight. */
    public record Flight(String name, List<FlightTrack.Point> points) {}

    private final Fleet fleet;
    private final List<VirtualDrone> drones;
    private final BtrScene btr;
    private final List<Flight> flights;

    /** Its own fleet (closed with it), or a shared one (DEMO mode: only its drones go). */
    private boolean ownsFleet = true;

    private TourScenario(Fleet fleet, List<VirtualDrone> drones, BtrScene btr, List<Flight> flights) {
        this.fleet = fleet;
        this.drones = drones;
        this.btr = btr;
        this.flights = flights;
    }

    /** The four in a fleet that runs already (DEMO mode's, with the other demo drones): closing takes only these. */
    public static TourScenario startIn(Fleet fleet, Listener listener) throws IOException {
        TourScenario s = setUp(fleet, listener);
        s.ownsFleet = false;
        fleet.announce();
        return s;
    }

    /**
     * Starts the four drones where {@link #PLACES} puts them (the BTR waiting out of the picture).
     * @param config       DRAUPNE Link's settings (the key; the names its map has, which the drones don't take)
     * @param announceFile where DRAUPNE Link looks for virtual drones ({@link VirtualFleet#file()}), or null (tests)
     */
    public static TourScenario start(PcConfig config, Path announceFile, Consumer<String> log, Listener listener) throws IOException {
        Fleet fleet = new Fleet(config, announceFile, log);
        try {
            TourScenario s = setUp(fleet, listener);
            fleet.startHeartbeat();
            return s;
        } catch (IOException | RuntimeException e) {
            fleet.close();
            throw e;
        }
    }

    /** The four drones, their flights and the BTR, in fleet (the caller starts its heartbeat or has). */
    private static TourScenario setUp(Fleet fleet, Listener listener) throws IOException {
        List<VirtualDrone> made = new ArrayList<>();
        try {
            double[] launch = Coordinates.parse(LAUNCH);
            List<VirtualDrone> drones = new ArrayList<>();
            List<Flight> flights = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < PLACES.size(); i++) {
                double[] placed = FleetLayout.place(i, launch[0], launch[1]);
                double[] at = {placed[0], placed[1]};
                VirtualDrone d = fleet.add(fleet.freeName(), at[0], at[1], placed[2]);
                made.add(d);
                d.fc.setLaunch(launch[0], launch[1]); // flew from the launch point: Betaflight's home is there
                double[] o = OUTSIDE[i];
                d.setOutside(o[0], (int) o[1], o[2], (int) o[3]);
                drones.add(d);
                long landed = now - (long) o[5] * 60_000;
                flights.add(new Flight(d.name() + " " + java.time.Instant.ofEpochMilli(landed).atZone(java.time.ZoneId.systemDefault())
                        .toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), track(launch, at, landed, new Random(77 + i))));
            }
            VirtualDrone watcher = drones.get(0);
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
            return new TourScenario(fleet, List.copyOf(drones), btr, List.copyOf(flights));
        } catch (IOException | RuntimeException e) {
            for (VirtualDrone d : made) fleet.remove(d);
            throw e;
        }
    }

    /** Degrees clockwise from north, from a to b ({lat, lon}). */
    static double bearing(double[] a, double[] b) {
        return FleetLayout.bearing(a, b);
    }

    /**
     * A flight as a GPS logs it, once a second: climb from the launch point to 35-60 m, a cruise that bends a little
     * (a pilot flies round things, not a ruler line), slowing and coming down near the end, landed where it sits; a
     * metre or two of GPS wander on every fix.
     */
    static List<FlightTrack.Point> track(double[] from, double[] to, long landedAt, Random r) {
        double cos = Math.cos(Math.toRadians(from[0]));
        double dn = (to[0] - from[0]) * 111_320.0, de = (to[1] - from[1]) * 111_320.0 * cos;
        double dist = Math.hypot(dn, de);
        double bend = (r.nextDouble() * 2 - 1) * 0.25 * dist; // the control point off the straight line
        double cn = dn / 2 - de / Math.max(1, dist) * bend, ce = de / 2 + dn / Math.max(1, dist) * bend;
        double cruise = 35 + r.nextDouble() * 25, speed = 11 + r.nextDouble() * 5;
        // along the curve by distance: a table of the quadratic Bezier's length
        int steps = 400;
        double[] len = new double[steps + 1];
        double[][] pt = new double[steps + 1][];
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps, u = 1 - t;
            pt[i] = new double[]{2 * u * t * cn + t * t * dn, 2 * u * t * ce + t * t * de};
            if (i > 0) len[i] = len[i - 1] + Math.hypot(pt[i][0] - pt[i - 1][0], pt[i][1] - pt[i - 1][1]);
        }
        double total = len[steps];
        List<double[]> fixes = new ArrayList<>(); // {n, e, alt, speed m/s}
        double s = 0, v = 0, alt = 0;
        int sec = 0;
        boolean climbing = true;
        while (sec < 2000) {
            fixes.add(new double[]{s, alt, v});
            double toGo = total - s;
            if (toGo <= 3 && !climbing) { // over its spot: straight down, then landed
                if (alt <= 0) break;
                s = total;
                v = 0;
                alt = Math.max(0, alt - 2.5);
            } else if (climbing) { // up first, moving off once clear of the launch point's trees
                alt = Math.min(cruise, alt + 4.5);
                if (alt > 12) v = Math.min(speed, v + 2.5);
                if (alt >= cruise || toGo < 40) climbing = false;
                s = Math.min(total, s + v);
            } else { // cruise, slowing and coming down over the last stretch
                double want = toGo < 60 ? Math.max(1.5, toGo / 6) : speed + (r.nextDouble() * 2 - 1);
                v += Math.max(-3, Math.min(2.5, want - v));
                double height = toGo < 90 ? Math.max(3, cruise * toGo / 90) : cruise;
                alt += Math.max(-3, Math.min(2, height - alt));
                s = Math.min(total, s + v);
            }
            sec++;
        }
        long t0 = landedAt - (fixes.size() - 1) * 1000L;
        List<FlightTrack.Point> out = new ArrayList<>();
        for (int k = 0; k < fixes.size(); k++) {
            double[] f = fixes.get(k);
            int i = java.util.Arrays.binarySearch(len, f[0]);
            i = Math.min(steps, Math.max(0, i < 0 ? -i - 1 : i));
            double n = pt[i][0] + r.nextGaussian() * 1.2, e = pt[i][1] + r.nextGaussian() * 1.2;
            int j = Math.min(steps, i + 4), h = Math.max(0, i - 4);
            int course = (int) Math.round((Math.toDegrees(Math.atan2(pt[j][1] - pt[h][1], pt[j][0] - pt[h][0])) + 360) % 360);
            boolean last = k == fixes.size() - 1;
            out.add(new FlightTrack.Point(t0 + k * 1000L, from[0] + n / 111_320.0, from[1] + e / (111_320.0 * cos),
                    (int) Math.round(f[1]), f[2] * 3.6, course, 13 + r.nextInt(5), !last, false, 2 + r.nextInt(3)));
        }
        return out;
    }

    /**
     * The modes, calling DRAUPNE Link: the three to OVERWATCH (each with the token DRAUPNE Link registered for it), the
     * fourth to DEEP SLEEP. The BTR waits for {@link #driveInNow}.
     * @param pcLink      DRAUPNE Link's link address as a phone sees it (127.0.0.1 and its link port)
     * @param tokens      each overwatch drone's token (OverwatchHub.watch), by name
     * @param sensitivity overwatch's 1 LOW .. 3 HIGH
     */
    public void placeModes(InetSocketAddress pcLink, Map<String, Integer> tokens, int sensitivity) {
        for (int i = 0; i < drones.size(); i++) {
            VirtualDrone d = drones.get(i);
            int every = (int) OUTSIDE[i][4];
            if (PLACES.get(i).mode() == VirtualDrone.OVERWATCH) {
                Integer token = tokens.get(d.name());
                d.placeInMode(pcLink, VirtualDrone.OVERWATCH, every, sensitivity, 0, token == null ? 0 : token);
            } else {
                d.placeInMode(pcLink, VirtualDrone.DEEP_SLEEP, every, 2, 0, 0);
            }
        }
    }

    /** The BTR sets off now. */
    public void driveInNow() {
        btr.driveFrom(System.currentTimeMillis());
    }

    /** All four, in {@link #PLACES}' order. */
    /** The fleet its drones run in (the tour's spectator steps add their pilot PC to it). */
    public Fleet fleet() {
        return fleet;
    }

    public List<VirtualDrone> drones() {
        return drones;
    }

    /** The three in OVERWATCH. */
    public List<VirtualDrone> overwatchers() {
        List<VirtualDrone> out = new ArrayList<>();
        for (int i = 0; i < drones.size(); i++) if (PLACES.get(i).mode() == VirtualDrone.OVERWATCH) out.add(drones.get(i));
        return out;
    }

    /** The overwatch drone the BTR drives in front of. */
    public VirtualDrone watcher() {
        return drones.get(0);
    }

    /** The one put to DEEP SLEEP. */
    public VirtualDrone sleeper() {
        return drones.get(drones.size() - 1);
    }

    /** Each drone's flight from the launch point, for the map. */
    public List<Flight> flights() {
        return flights;
    }

    /** The launch point {lat, lon}. */
    public static double[] launch() {
        return Coordinates.parse(LAUNCH);
    }

    /** The units for the map, as drawings that are never saved. */
    public static List<MapGraphics.Item> units() {
        List<MapGraphics.Item> out = new ArrayList<>();
        for (Unit u : UNITS) {
            double[] p = Coordinates.parse(u.at());
            out.add(MapGraphics.Item.symbol(u.name(), new MapGraphics.LatLon(p[0], p[1]), u.type(), u.side(), u.size()));
        }
        return out;
    }

    /** Where a drone stands and looks: {lat, lon, heading degrees, camera's horizontal field of view degrees}. */
    public double[] pose(VirtualDrone d) {
        return new double[]{d.fc.latDeg(), d.fc.lonDeg(), d.fc.headingDeg(), Scene.HFOV_DEG};
    }

    /** The watcher's pose (see {@link #pose}). */
    public double[] watcherPose() {
        return pose(watcher());
    }

    /** Where the BTR is now: {lat, lon, heading degrees} (for a map, e.g.). */
    public double[] btrPosition() {
        VirtualDrone w = watcher();
        double[] q = btr.pose(System.currentTimeMillis());
        double lat0 = w.fc.latDeg() - w.fc.northM() / 111_320.0;
        double lon0 = w.fc.lonDeg() - w.fc.eastM() / (111_320.0 * Math.cos(Math.toRadians(lat0)));
        return new double[]{lat0 + q[0] / 111_320.0, lon0 + q[1] / (111_320.0 * Math.cos(Math.toRadians(lat0))), q[2]};
    }

    /** The BTR (tests). */
    BtrScene btr() {
        return btr;
    }

    @Override
    public void close() {
        if (ownsFleet) fleet.close();
        else for (VirtualDrone d : drones) fleet.remove(d);
    }
}
