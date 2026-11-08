package de.droiddrone.pc.fleet;

import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The virtual drones of the fleet program: added and removed, each on a loopback address of its own (127.0.0.11 for
 * the first slot, 127.0.0.12 for the next ...), and announced to DRAUPNE Link through {@link VirtualFleet}'s file while
 * they run (a heartbeat every two seconds; the file goes when the fleet closes).
 */
public final class Fleet implements AutoCloseable {
    /** As many as this: one PC's observation grid holds six, and a few more for the PHONE list. */
    public static final int MAX_DRONES = 12;
    /**
     * Where the drones stand unless the pilot picks a place (the pilot's choice, 1.0.50): the middle of the MGRS
     * 100 km square 32V PN. The first drone there, the others 30 m apart to the east.
     */
    public static final String DEFAULT_PLACE = "32V PN 50000 50000";
    public static final double DEFAULT_LAT, DEFAULT_LON;

    static {
        double[] at = de.droiddrone.pc.map.Coordinates.parse(DEFAULT_PLACE);
        // the middle of that metre, so it reads back as 50000 50000 (its corner can come out as 49999)
        DEFAULT_LAT = at[0] + 0.5 / 111_320.0;
        DEFAULT_LON = at[1] + 0.5 / (111_320.0 * Math.cos(Math.toRadians(at[0])));
    }

    private final String key;
    private final Path announceFile;
    private final Set<String> namesTaken;
    private final Consumer<String> base;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final List<VirtualDrone> drones = new CopyOnWriteArrayList<>();
    private final FlightModel.Setup setup = new FlightModel.Setup();
    private volatile boolean closed;
    private Thread heartbeat;

    /**
     * @param config       DRAUPNE Link's settings, read only: the link key (the drones answer to it) and the names its map
     *                     has already (the drones take others)
     * @param announceFile where DRAUPNE Link looks for the fleet (VirtualFleet.file()), or null: not announced (tests)
     */
    public Fleet(PcConfig config, Path announceFile, Consumer<String> log) {
        this.key = config.getKey();
        this.announceFile = announceFile;
        this.base = log;
        java.util.HashSet<String> taken = new java.util.HashSet<>();
        for (String k : config.keys()) if (k.startsWith("name.")) taken.add(k.substring(5).toLowerCase(Locale.ROOT));
        namesTaken = taken;
    }

    /**
     * Every line the drones log goes here too (the panel's log), the ones from before it was added first (the drones
     * come up before the window opens).
     */
    public void onLog(Consumer<String> listener) {
        synchronized (history) {
            for (String line : history) listener.accept(line);
            listeners.add(listener);
        }
    }

    /** The last lines, for a listener added late. */
    private final java.util.ArrayDeque<String> history = new java.util.ArrayDeque<>();

    private void log(String line) {
        base.accept(line);
        synchronized (history) {
            history.addLast(line);
            if (history.size() > 200) history.removeFirst();
            for (Consumer<String> l : listeners) l.accept(line);
        }
    }

    public String key() {
        return key;
    }

    /** The switch layout every new drone gets (ARM on AUX1 by default). */
    public FlightModel.Setup setup() {
        return setup;
    }

    public List<VirtualDrone> drones() {
        return new ArrayList<>(drones);
    }

    /**
     * A name for a new drone: the first Mobile letter from Zulu down that DRAUPNE Link's name map doesn't have and no
     * drone here has - so a virtual drone never takes the name of one of the pilot's phones.
     */
    public String freeName() {
        for (int i = Names.LETTERS.length - 1; i >= 0; i--) {
            String n = Names.Kind.PHONE.name(i);
            if (namesTaken.contains(n.toLowerCase(Locale.ROOT))) continue;
            boolean used = false;
            for (VirtualDrone d : drones) if (d.name().equalsIgnoreCase(n)) used = true;
            if (!used) return n;
        }
        return "";
    }

    /** Whether DRAUPNE Link's name map has this name already (one of the pilot's phones, or a drone seen before). */
    public boolean nameTaken(String name) {
        return name != null && namesTaken.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * A new drone, started and announced.
     * @param name its DRAUPNE name (MobileXxx), or "" to be named by DRAUPNE Link's master PC
     */
    public synchronized VirtualDrone add(String name, double lat, double lon, double headingDeg) throws IOException {
        if (closed) throw new IOException("the fleet is closed");
        if (drones.size() >= MAX_DRONES) throw new IOException("at most " + MAX_DRONES + " virtual drones");
        int slot = 0;
        while (slotTaken(slot)) slot++;
        InetAddress address = InetAddress.getByName("127.0.0." + (VirtualFleet.FIRST_HOST + slot));
        FlightModel.Setup s = copy(setup);
        VirtualDrone d = new VirtualDrone(slot, name, key, address, lat, lon, headingDeg, s, this::log);
        d.start(0);
        drones.add(d);
        announce();
        log((name.isEmpty() ? "Virtual drone " + (slot + 1) : name) + ": up at " + address.getHostAddress() + ":" + d.port());
        return d;
    }

    /** The next drone's start place: DEFAULT_LAT/LON, 30 m east of the last. */
    public double[] nextPlace(double lat, double lon) {
        int n = drones.size();
        return new double[]{lat, lon + n * 30 / (111_320.0 * Math.cos(Math.toRadians(lat)))};
    }

    private boolean slotTaken(int slot) {
        for (VirtualDrone d : drones) if (d.slot() == slot) return true;
        return false;
    }

    private static FlightModel.Setup copy(FlightModel.Setup s) {
        FlightModel.Setup c = new FlightModel.Setup();
        c.armAux = s.armAux;
        c.armStart = s.armStart;
        c.armEnd = s.armEnd;
        c.angleAux = s.angleAux;
        c.angleStart = s.angleStart;
        c.angleEnd = s.angleEnd;
        c.beeperAux = s.beeperAux;
        c.beeperStart = s.beeperStart;
        c.beeperEnd = s.beeperEnd;
        c.procedure = s.procedure;
        c.cells = s.cells;
        c.capacityMah = s.capacityMah;
        return c;
    }

    public synchronized void remove(VirtualDrone d) {
        if (!drones.remove(d)) return;
        d.stop();
        announce();
        log((d.name().isEmpty() ? "Virtual drone " + (d.slot() + 1) : d.name()) + ": removed");
    }

    /** The file written now: every drone's address and port, and the heartbeat. */
    public void announce() {
        if (announceFile == null || closed) return;
        List<VirtualFleet.Drone> list = new ArrayList<>();
        for (VirtualDrone d : drones) list.add(new VirtualFleet.Drone(d.address().getHostAddress(), d.port()));
        try {
            VirtualFleet.write(announceFile, list, System.currentTimeMillis());
        } catch (IOException e) {
            log("Fleet: can't announce the drones (" + e.getMessage() + ") - DRAUPNE Link won't find them");
        }
    }

    /** The heartbeat: the file written again every VirtualFleet.HEARTBEAT_MS (a port that moved goes with it). */
    public synchronized void startHeartbeat() {
        if (heartbeat != null || announceFile == null) return;
        heartbeat = new Thread(() -> {
            while (!closed) {
                announce();
                try {
                    Thread.sleep(VirtualFleet.HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "fleetHeartbeat");
        heartbeat.setDaemon(true);
        heartbeat.setPriority(Thread.MIN_PRIORITY);
        heartbeat.start();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (heartbeat != null) heartbeat.interrupt();
        for (VirtualDrone d : drones) d.stop();
        drones.clear();
        if (announceFile != null) VirtualFleet.withdraw(announceFile);
    }
}
