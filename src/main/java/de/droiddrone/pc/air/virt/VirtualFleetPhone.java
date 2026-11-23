package de.droiddrone.pc.air.virt;

import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.FleetIntent;
import de.droiddrone.common.group.FollowerBrain;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.group.GroupOrder;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Fleet PRD AP-8 (in part): a virtual ArduPilot drone's phone on the fleet link - the phone's fleet role as DRAUPNE
 * Flight has it (FleetRole: the key, the follower's rules FollowerBrain, reports twice a second) over a real UDP socket,
 * with a kinematic copter ({@link VirtualCopter}) for the flight controller. Lets the PC's GroupLink be flown end to end
 * on loopback, and the virtual fleet show the follower group. Not ArduPilot itself: real SITL needs Windows' Linux part.
 */
public final class VirtualFleetPhone implements AutoCloseable {
    public static final long STEP_MS = 50;

    public final VirtualCopter copter;
    private final double refLat, refLon, groundAmsl;
    private final String key;
    private final DatagramSocket socket;
    private volatile InetSocketAddress commander;
    private volatile long commanderHeardAt, reportUntil, reportedAt;
    private volatile FollowerBrain brain;
    private volatile boolean open = true, armed = true;
    private volatile boolean linkUp = true;
    private final long start = System.currentTimeMillis();

    public VirtualFleetPhone(String id, double refLat, double refLon, double groundAmsl, double e, double n, double altAmsl, String key)
            throws IOException {
        this(id, InetAddress.getLoopbackAddress(), refLat, refLon, groundAmsl, e, n, altAmsl, key);
    }

    /** On a loopback address of its own (the virtual fleet's 127.0.0.x, so the name service finds it as a phone). */
    public VirtualFleetPhone(String id, InetAddress bind, double refLat, double refLon, double groundAmsl, double e, double n,
                             double altAmsl, String key) throws IOException {
        this.refLat = refLat;
        this.refLon = refLon;
        this.groundAmsl = groundAmsl;
        this.key = key;
        this.copter = new VirtualCopter(id, e, n, altAmsl, groundAmsl, id.hashCode());
        socket = new DatagramSocket(0, bind);
        Thread r = new Thread(this::receive, "virtualFleetPhone-" + id);
        r.setDaemon(true);
        r.start();
        Thread t = new Thread(this::run, "virtualFleetPhoneFly-" + id);
        t.setDaemon(true);
        t.start();
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
    }

    /** Packets that are not fleet commands (a name query: the virtual fleet answers it as a phone does). */
    private volatile java.util.function.Consumer<DatagramPacket> other;

    public void onOther(java.util.function.Consumer<DatagramPacket> handler) {
        other = handler;
    }

    /** A datagram from this phone's own socket. */
    public void sendRaw(byte[] data, InetSocketAddress to) {
        try {
            socket.send(new DatagramPacket(data, data.length, to.getAddress(), to.getPort()));
        } catch (IOException ignored) {
            // the next one
        }
    }

    /** The phone's 4G cut (nothing in, nothing out) or back. */
    public void setLink(boolean up) {
        linkUp = up;
    }

    public boolean isFollower() {
        return brain != null;
    }

    public double[] latLon() {
        return GeoLocal.offset(refLat, refLon, copter.e, copter.n);
    }

    private void receive() {
        byte[] buf = new byte[512];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (open) {
            try {
                socket.receive(p);
            } catch (IOException e) {
                if (!open) return;
                continue;
            }
            if (!linkUp) continue;
            FleetCmd c = FleetCmd.read(p.getData(), p.getLength());
            if (c == null) {
                java.util.function.Consumer<DatagramPacket> h = other;
                if (h != null) h.accept(p);
                continue;
            }
            if (!c.key.equals(key)) continue;
            long now = System.currentTimeMillis();
            commander = new InetSocketAddress(p.getAddress(), p.getPort());
            commanderHeardAt = now;
            reportUntil = Math.max(reportUntil, now + 15_000);
            switch (c.inner()) {
                case FleetCmd.GROUP -> {
                    GroupOrder o = GroupOrder.decode(c.data, c.at + 1);
                    if (o == null) break;
                    FollowerBrain b = brain;
                    if (b == null) brain = b = new FollowerBrain(1000);
                    b.order(o, now);
                }
                case FleetCmd.EXIT -> {
                    ExitPlan plan = ExitPlan.decode(c.data, c.at + 1);
                    FollowerBrain b = brain;
                    if (plan != null && b != null) b.plan(plan);
                }
                case de.droiddrone.common.MissionBlock.PACKET -> { // a route: kept, "read back" at once (the real phone uploads it over USB)
                    de.droiddrone.common.MissionBlock b = de.droiddrone.common.MissionBlock.read(c.data, c.at + 1, c.length - 1);
                    if (b != null) synchronized (copter) {
                        route = b.items;
                        routeBlock = b.blockId;
                        routeNext = 1;
                        holding = false;
                    }
                }
                case de.droiddrone.common.FcCommand.PACKET -> {
                    de.droiddrone.common.FcCommand k = de.droiddrone.common.FcCommand.read(java.util.Arrays.copyOfRange(c.data, c.at, c.at + c.length), c.length);
                    if (k != null && k.kind == de.droiddrone.common.FcCommand.MISSION_START) synchronized (copter) {
                        auto = route != null;
                    }
                }
                case FleetCmd.MARK -> launchMark(c.data, c.at, c.length, now);
                case FleetCmd.LAUNCH -> {
                    de.droiddrone.common.launch.LaunchWire.Order o = de.droiddrone.common.launch.LaunchWire.decodeOrder(c.data, c.at + 1, c.length - 1);
                    if (o != null) synchronized (copter) {
                        act(seq.order(o, now));
                    }
                }
                case FleetCmd.DISBAND -> brain = null;
                case FleetCmd.TRACK -> reportUntil = now + (c.data[c.at + 1] & 0xff) * 1000L;
                default -> {
                }
            }
        }
    }

    private void run() {
        long lastBrain = 0;
        while (open) {
            try {
                Thread.sleep(STEP_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            synchronized (copter) {
                autoTick();
                launchTick(now);
                FollowerBrain b = brain;
                if (b != null && now - lastBrain >= 100) {
                    lastBrain = now;
                    double[] ll = latLon();
                    FleetIntent i = b.tick(now, commanderHeardAt, ll[0], ll[1], copter.battery, copter.gnssOk);
                    if (i.kind != FleetIntent.NONE) copter.apply(i, refLat, refLon);
                }
                copter.step(STEP_MS / 1000.0, 0, 0, groundAmsl);
            }
            report(now);
        }
    }

    private void report(long now) {
        InetSocketAddress to = commander;
        if (to == null || !linkUp || now >= reportUntil || now - reportedAt < 500) return;
        reportedAt = now;
        FleetStatus s = new FleetStatus();
        synchronized (copter) {
            double[] ll = latLon();
            s.lat = ll[0];
            s.lon = ll[1];
            s.altAmsl = copter.u;
            s.velE = copter.ve;
            s.velN = copter.vn;
            s.velU = copter.vu;
            s.battery = (int) Math.round(copter.battery);
            s.homeAmsl = copter.homeAmsl;
            s.routeBlock = routeBlock;
            s.routeState = route == null ? 0 : de.droiddrone.common.MissionUpload.DONE;
            s.routePercent = route == null ? 0 : 100;
            s.mode = auto ? 3 : 5;
            if (armed && !copter.landed) s.flags |= FleetStatus.ARMED;
            if (copter.gnssOk) s.flags |= FleetStatus.GNSS;
            if (copter.landed) s.flags |= FleetStatus.LANDED;
            if (now - commanderHeardAt <= 3000) s.flags |= FleetStatus.PC_HEARD;
            s.errM = copter.errorM((now - start) / 1000.0);
            FollowerBrain b = brain;
            s.phase = b == null ? 255 : b.phase();
        }
        byte[] d = s.encode();
        try {
            socket.send(new DatagramPacket(d, d.length, to.getAddress(), to.getPort()));
        } catch (IOException ignored) {
            // the next report
        }
    }

    // ---- fleet FD-3: a route flown in AUTO (waypoints one after another; at the end it holds, as B5 says)

    private java.util.List<de.droiddrone.common.MissionItem> route;
    private int routeBlock, routeNext;
    private volatile boolean auto;

    public boolean flyingRoute() {
        return auto;
    }

    /** At the route's hold (or its end): YOUR TURN. */
    public boolean routeDone() {
        return route != null && (holding || routeNext >= route.size());
    }

    private boolean holding;

    private void autoTick() {
        if (!auto || route == null) return;
        while (routeNext < route.size() && route.get(routeNext).command != de.droiddrone.common.MissionItem.NAV_WAYPOINT
                && route.get(routeNext).command != de.droiddrone.common.MissionItem.NAV_LOITER_UNLIM) routeNext++;
        if (routeNext >= route.size() || holding) { // the end, or its hold: it stays there (LOITER) until told
            auto = false;
            copter.apply(FleetIntent.HOLD, refLat, refLon);
            return;
        }
        de.droiddrone.common.MissionItem w = route.get(routeNext);
        double lat = w.x / 1e7, lon = w.y / 1e7;
        copter.apply(FleetIntent.guided(lat, lon, w.z, Double.NaN, copter.guidedSpeed), refLat, refLon);
        double[] ll = latLon();
        if (GeoLocal.distance(ll[0], ll[1], lat, lon) < 3 && Math.abs(copter.u - w.z) < 2) {
            if (w.command == de.droiddrone.common.MissionItem.NAV_LOITER_UNLIM) holding = true; // B5: holds, calls the pilot
            else routeNext++;
        }
    }

    // ---- fleet area F: the safe autolaunch as the phone runs it (LaunchSequence, the shared rules)

    private final de.droiddrone.common.launch.PreArmCheck check = new de.droiddrone.common.launch.PreArmCheck(0);
    private final de.droiddrone.common.launch.LaunchSequence seq = new de.droiddrone.common.launch.LaunchSequence(check);
    /** Every ARM this phone gave, with whether it held a mark then (the AL-12 invariant, for the tests). */
    public final java.util.List<String> arms = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** On the ground, disarmed: a reserve (call before the PC marks it). */
    public void landed() {
        synchronized (copter) {
            copter.u = groundAmsl;
            copter.landed = true;
            armed = false;
        }
    }

    /** As an FC says it: armed from the arming on, on the ground too. */
    public boolean armed() {
        return armed;
    }

    public int launchState() {
        return seq.state();
    }

    public String launchReason() {
        return de.droiddrone.common.launch.LaunchSequence.reasonWord(seq.reason());
    }

    private void launchMark(byte[] d, int at, int len, long now) {
        if (len < 7) return;
        int id = (d[at + 1] & 0xff) << 24 | (d[at + 2] & 0xff) << 16 | (d[at + 3] & 0xff) << 8 | (d[at + 4] & 0xff);
        double floor = ((d[at + 5] & 0xff) << 8 | (d[at + 6] & 0xff)) / 10.0;
        synchronized (copter) {
            if (id == 0) {
                seq.setMark(null);
                return;
            }
            if (armed()) return;
            double[] ll = latLon();
            seq.setMark(new de.droiddrone.common.launch.LaunchMark(id, ll[0], ll[1], 0, now, floor));
        }
    }

    private void launchTick(long now) {
        if (seq.state() == de.droiddrone.common.launch.LaunchSequence.IDLE) return;
        check.motion(now, 1.0, 0.0); // at rest on the ground, or flying smoothly
        de.droiddrone.common.launch.PreArmCheck.Reading r = new de.droiddrone.common.launch.PreArmCheck.Reading();
        double[] ll = latLon();
        r.rollDeg = 0;
        r.pitchDeg = 0;
        r.headingDeg = 0;
        r.lat = ll[0];
        r.lon = ll[1];
        r.gpsFix3d = copter.gnssOk;
        r.batteryPercent = (int) Math.round(copter.battery);
        r.imuHealthy = r.compassHealthy = r.fcHealthy = true;
        de.droiddrone.common.launch.ClimbWatchdog.Sample s = null;
        if (seq.state() == de.droiddrone.common.launch.LaunchSequence.CLIMBING) {
            s = new de.droiddrone.common.launch.ClimbWatchdog.Sample();
            s.atMs = now;
            s.tiltDeg = 2;
            s.accelG = 1.0;
            s.climbMps = copter.vu;
            s.throttlePercent = 55;
            de.droiddrone.common.launch.LaunchMark m = seq.mark();
            s.driftM = m == null ? 0 : GeoLocal.distance(m.lat, m.lon, ll[0], ll[1]);
            s.heightM = copter.u - groundAmsl;
        }
        seq.fcReady(true);
        act(seq.tick(now, r, armed(), s));
    }

    private void act(java.util.List<de.droiddrone.common.launch.LaunchIntent> todo) {
        for (de.droiddrone.common.launch.LaunchIntent i : todo) {
            switch (i.type) {
                case de.droiddrone.common.launch.LaunchIntent.ARM -> {
                    arms.add(copter.id + (seq.mark() != null || seq.order() != null ? " marked" : " UNMARKED"));
                    armed = true;
                }
                case de.droiddrone.common.launch.LaunchIntent.TAKEOFF -> copter.climbTo(groundAmsl + i.heightM);
                case de.droiddrone.common.launch.LaunchIntent.MOTORS_OFF -> {
                    armed = false;
                    copter.landed = true;
                }
                case de.droiddrone.common.launch.LaunchIntent.REPORT -> {
                    InetSocketAddress to = commander;
                    if (to == null || !linkUp) break;
                    byte[] st = de.droiddrone.common.launch.LaunchWire.encode(i.status);
                    byte[] p = new byte[1 + st.length];
                    p[0] = FleetCmd.LAUNCH_STATUS;
                    System.arraycopy(st, 0, p, 1, st.length);
                    try {
                        socket.send(new DatagramPacket(p, p.length, to.getAddress(), to.getPort()));
                    } catch (IOException ignored) {
                        // the next report
                    }
                }
                default -> {
                }
            }
        }
    }

    @Override
    public void close() {
        open = false;
        socket.close();
    }
}
