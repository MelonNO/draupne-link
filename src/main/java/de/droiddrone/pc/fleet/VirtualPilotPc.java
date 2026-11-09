package de.droiddrone.pc.fleet;

import de.droiddrone.common.PacketIds;
import de.droiddrone.pc.AppInfo;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.Mirror;
import de.droiddrone.pc.MirrorHost;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.observe.ObserverLink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Another pilot's PC in the virtual fleet (1.0.56, the tour's spectator steps: "connect to a simulated other pilot PC
 * flying ... have it change from pilot to observation mode"): DRAUPNE Link's own pilot code without a window, on a
 * loopback address of its own, announced with the drones and named as a PC (a free PC name, given). Spectators are always
 * allowed here.
 * <ul>
 * <li>{@link #fly}: a virtual drone of its own, flown by a real {@link ControlLink} - an autopilot on the sticks arms
 * it, climbs and circles - so a spectator gets its picture, OSD and position as from any pilot PC.</li>
 * <li>{@link #observe}: the drone left (BYE: the pilot disconnected), and the given drones observed by real
 * {@link ObserverLink}s, their streams passed to spectators through its {@link MirrorHost}, as the window does.</li>
 * </ul>
 * Built for the tour to drive; nothing here touches a window.
 */
public final class VirtualPilotPc implements AutoCloseable {
    public static final String DEVICE = "VIRTUAL PC";
    /** Its loopback address: clear of the drones' (127.0.0.11 up, at most {@link Fleet#MAX_DRONES}). */
    public static final String ADDRESS = "127.0.0.60";
    /** The circle it flies: height, m. */
    static final double CRUISE_M = 25;
    /** Landing before it observes: the descent, m/s; given up after LAND_MAX_MS. */
    static final double DESCENT_MS = 5;
    static final long LAND_MAX_MS = 15_000;
    private volatile boolean landing;

    private final Fleet fleet;
    /** Its name: a valid DRAUPNE PC name no PC has, or the name map would never file it. */
    private final String name;
    private final PcConfig config;
    private final Consumer<String> log;
    private final InetAddress address;
    private final int port;
    private final MirrorHost mirror;
    private final VirtualFleet.Drone node;
    private volatile boolean running = true;
    /** The link port while no link holds it: name queries, spectators' asks. */
    private DatagramSocket holder;
    private Thread holderThread;
    private final Object portLock = new Object();
    // flying
    private VirtualDrone drone;
    private ControlLink link;
    private Thread pilot;
    // observing
    private final List<ObserverLink> observed = new ArrayList<>();
    private volatile int mode = Mirror.IDLE;

    /**
     * Up at {@link #ADDRESS}, announced with the fleet's drones, idle.
     * @param config DRAUPNE Link's settings (the key); never written
     * @param name a free PC name (Names.firstFree)
     */
    public VirtualPilotPc(Fleet fleet, PcConfig config, String name, Consumer<String> log) throws IOException {
        this.fleet = fleet;
        this.name = name;
        this.config = config;
        this.log = log;
        address = InetAddress.getByName(ADDRESS);
        mirror = new MirrorHost(config.getKey(), () -> true, line -> log.accept(name + ": " + line), address);
        DatagramSocket s = new DatagramSocket(0, address);
        port = s.getLocalPort();
        holdPort(s);
        node = new VirtualFleet.Drone(ADDRESS, port);
        fleet.announceExtra(node);
        setMode();
        log.accept(name + ": up at " + ADDRESS + ":" + port);
    }

    public String name() {
        return name;
    }

    public String address() {
        return ADDRESS;
    }

    public int port() {
        return port;
    }

    /** Mirror.IDLE, PILOT or OBSERVER. */
    public int mode() {
        return mode;
    }

    /** The drone it flies, or null. */
    public VirtualDrone drone() {
        return drone;
    }

    // ------------------------------------------------------------------ the port while no link holds it

    private void holdPort(DatagramSocket s) throws SocketException {
        s.setSoTimeout(200);
        holder = s;
        Thread t = new Thread(() -> holdLoop(s), "virtualPilotPort");
        t.setDaemon(true);
        holderThread = t;
        t.start();
    }

    private void holdLoop(DatagramSocket s) {
        byte[] buf = new byte[1500];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running && !s.isClosed()) {
            try {
                p.setLength(buf.length);
                s.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return;
            }
            int len = p.getLength();
            if (len < 1) continue;
            if (buf[0] == Names.NAME_QUERY) answerName(s, p);
            else if (buf[0] == Mirror.HELLO) mirror.onHello(p, s);
            else if (buf[0] == PacketIds.SPECTATE_HELLO) refuseNotFlying(s, p);
        }
    }

    private void answerName(DatagramSocket s, DatagramPacket p) {
        String k = Names.keyOf(p.getData(), p.getLength());
        if (k == null || !k.equals(config.getKey())) return;
        byte[] r = nameReply();
        try {
            s.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
        } catch (IOException ignored) {
        }
    }

    private byte[] nameReply() {
        return Names.reply(config.getKey(), Names.Kind.PC, name, DEVICE, AppInfo.version());
    }

    /** A spectator's HELLO while nothing flies: told so (it keeps asking, and joins once it flies). */
    private static void refuseNotFlying(DatagramSocket s, DatagramPacket p) {
        try {
            s.send(new DatagramPacket(new byte[]{PacketIds.SPECTATE_REFUSED, 1}, 2, p.getAddress(), p.getPort()));
        } catch (IOException ignored) {
        }
    }

    private void releasePort() {
        DatagramSocket s = holder;
        holder = null;
        if (s != null) s.close();
        Thread t = holderThread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ PILOT

    /** Takes off: a drone of its own near (lat, lon), flown by a real link with an autopilot on the sticks. */
    public void fly(double lat, double lon) throws IOException {
        synchronized (portLock) {
            stopObserving();
            if (link != null) return;
            if (drone == null) {
                drone = fleet.add(fleet.freeName(), lat, lon, 0);
                drone.setBeeperless(true); // the tour's: no motor beeps
            }
            releasePort();
            grounded = false;
            ControlLink l = new ControlLink(config, drone.address(), drone.port(), port);
            l.setLocalAddress(address);
            l.setVideoListener(new ControlLink.VideoListener() {
                @Override
                public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
                }

                @Override
                public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
                }
            }, false);
            l.enableSpectators(true, 600);
            l.setNameReply(this::nameReply);
            l.setMirrorHost(mirror);
            l.setEvents(line -> log.accept(name + ": " + line));
            try {
                l.start();
            } catch (IOException | RuntimeException e) {
                holdPort(new DatagramSocket(port, address));
                throw e;
            }
            link = l;
            mode = Mirror.PILOT;
            setMode();
            Thread t = new Thread(() -> autopilot(l), "virtualPilotSticks");
            t.setDaemon(true);
            pilot = t;
            t.start();
            log.accept(name + ": flying " + drone.name());
        }
    }

    /**
     * The sticks, 50 a second: the arm switch flicked until it arms, then a climb to {@link #CRUISE_M} and a wide
     * circle (pitch a little forward, yaw a little right). The FC's own figures steer the throttle.
     */
    private void autopilot(ControlLink l) {
        VirtualDrone d = drone;
        long armToggleAt = 0;
        boolean armOn = false;
        long start = System.currentTimeMillis();
        while (running && link == l) {
            long now = System.currentTimeMillis();
            FlightModel fc = d.fc;
            short throttle = 1000;
            short roll = 1500, pitch = 1500, yaw = 1500;
            // the throttle that holds it level in the air, tilted as it flies (FlightModel's thrust to weight)
            double hover = 1 / (FlightModel.THRUST_TO_WEIGHT * Math.cos(Math.toRadians(fc.pitchDeg())) * Math.cos(Math.toRadians(fc.rollDeg())));
            double alt = fc.altitudeM(), vz = fc.varioMs();
            if (!fc.isArmed() && (landing || grounded)) {
                armOn = false; // landed: it stays down
            } else if (!fc.isArmed()) {
                // the FC's boot grace and GPS fix first; then off, on - again every 2 s until it arms
                if (now - start > FlightModel.GPS_FIX_AFTER_MS + 500 && now >= armToggleAt) {
                    armOn = !armOn;
                    armToggleAt = now + (armOn ? 1500 : 600);
                }
            } else if (landing) { // down at DESCENT_MS, slower near the ground; the switch off once on it
                double want = -Math.min(DESCENT_MS, 0.8 + alt / 3);
                throttle = throttle(hover + 0.15 * (want - vz));
                armOn = alt > 0.15;
            } else {
                armOn = true;
                throttle = throttle(hover + 0.03 * (CRUISE_M - alt) - 0.15 * vz);
                if (alt > 10) { // a wide circle
                    pitch = 1600;
                    yaw = 1560;
                }
            }
            short arm = (short) (armOn ? 1900 : 1000);
            l.sendRc(new short[]{roll, pitch, yaw, throttle, arm, 1500, 1000, 1000});
            l.setPilotView(new ControlLink.PilotView(l.getState() == ControlLink.State.READY, true, false, false, false,
                    false, null, null, null, null, d.name(), false));
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static short throttle(double t) {
        return (short) Math.round(1000 + 1000 * Math.max(0.06, Math.min(0.95, t)));
    }

    /** Down where it is and disarmed (at most LAND_MAX_MS): the pilot lands before it observes. Waits. */
    public void land() {
        VirtualDrone d = drone;
        if (link == null || d == null) return;
        landing = true;
        long until = System.currentTimeMillis() + LAND_MAX_MS;
        while (d.fc.isArmed() && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        grounded = true;
        landing = false;
    }

    /** Landed by {@link #land}: never armed again on this flight. */
    private volatile boolean grounded;

    /** Lands where it is: the link closed (the spectators hear the pilot disconnected), the drone left standing. */
    private void stopFlying() {
        ControlLink l = link;
        if (l == null) return;
        link = null;
        Thread t = pilot;
        pilot = null;
        if (t != null) t.interrupt();
        l.close();
        try {
            holdPort(new DatagramSocket(port, address));
        } catch (IOException e) {
            log.accept(name + ": its port back: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ OBSERVER

    /** OBSERVER mode: the flight ended, these drones observed and their streams passed to the spectators. */
    public void observe(List<VirtualDrone> drones) throws IOException {
        land();
        synchronized (portLock) {
            stopFlying();
            stopObserving();
            List<ObserverLink> links = new ArrayList<>();
            for (int i = 0; i < drones.size() && i < MirrorHost.MAX_TILES; i++) {
                VirtualDrone d = drones.get(i);
                final int tile = i;
                ObserverLink o = new ObserverLink(d.name(), d.address(), d.port(), config.getKey(), new ControlLink.VideoListener() {
                    @Override
                    public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
                    }

                    @Override
                    public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
                    }
                }, null, -1, line -> log.accept(name + ": " + line));
                o.setTap((data, len) -> mirror.tile(tile, data, len));
                o.start();
                links.add(o);
            }
            observed.addAll(links);
            mirror.setTiles(i -> {
                if (i < links.size()) links.get(i).askVideoConfig();
            });
            mode = Mirror.OBSERVER;
            setMode();
            log.accept(name + ": observing " + drones.size() + " drones");
        }
    }

    private void stopObserving() {
        for (ObserverLink o : observed) o.close();
        observed.clear();
        if (mode == Mirror.OBSERVER) {
            mode = Mirror.IDLE;
            mirror.setTiles(null);
            setMode();
        }
    }

    private void setMode() {
        List<String> tiles = new ArrayList<>();
        for (ObserverLink o : observed) tiles.add(o.phone());
        VirtualDrone d = drone;
        mirror.setSection(Mirror.MODE, new Mirror.Mode(mode, mode == Mirror.PILOT && d != null ? d.name() : "", tiles,
                mode == Mirror.OBSERVER ? 0 : -1, -1).encode());
    }

    /** How many spectators mirror it now. */
    public int spectators() {
        return mirror.count();
    }

    @Override
    public void close() {
        synchronized (portLock) {
            if (!running) return;
            stopObserving();
            stopFlying();
            running = false;
            releasePort();
            mirror.close();
            fleet.withdrawExtra(node);
            VirtualDrone d = drone;
            drone = null;
            if (d != null) fleet.remove(d);
        }
    }
}
