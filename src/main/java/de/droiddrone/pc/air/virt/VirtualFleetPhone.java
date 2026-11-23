package de.droiddrone.pc.air.virt;

import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.FleetCommandGate;
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
        this.home = latLon(); // where it is now: ArduPilot's home
        socket = new DatagramSocket(0, bind);
        sender = new de.droiddrone.common.UdpSender(socket);
        session = new de.droiddrone.common.ReceiverBuffer(sender, true, key, null);
        gate = new de.droiddrone.common.SessionGate(key);
        heartbeatAt = start + Math.floorMod(id.hashCode(), 1000); // ArduPilot's heartbeat: once a second, its own phase
        Thread s = new Thread(this::sessionRun, "virtualFleetPhoneSession-" + id);
        s.setDaemon(true);
        s.start();
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
        byte[] buf = new byte[de.droiddrone.common.UdpCommon.packetLength]; // the real phone's limit: a longer datagram is cut
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
                int delay = sessionDelayMs;
                if (delay <= 0) {
                    notFleet(p);
                } else {
                    DatagramPacket copy = new DatagramPacket(java.util.Arrays.copyOf(p.getData(), p.getLength()), p.getLength(),
                            p.getAddress(), p.getPort());
                    LATER.schedule(() -> notFleet(copy), delay, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                continue;
            }
            if (!c.key.equals(key)) continue;
            long now = System.currentTimeMillis();
            if (!commandTaken(p.getAddress(), now)) continue;
            commander = new InetSocketAddress(p.getAddress(), p.getPort());
            commanderHeardAt = now;
            reportUntil = Math.max(reportUntil, now + 15_000);
            switch (c.inner()) {
                case FleetCmd.GROUP -> {
                    GroupOrder o = GroupOrder.decode(c.data, c.at + 1);
                    if (o == null || inSession()) break; // the piloted drone is never a follower (FL-7)
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
                    // one part of it: whole when every part has come (as the phone's Mavlink.missionPart)
                    de.droiddrone.common.MissionBlock b = parts.offer(de.droiddrone.common.MissionBlock.readPart(c.data, c.at + 1, c.length - 1));
                    if (b != null && b.missionType == de.droiddrone.common.MissionUpload.TYPE_RALLY) synchronized (copter) {
                        rally = b.items; // AC-10: the FC's rally list - its RTL lands there
                        rallyBlocks.add(b.blockId);
                        routeBlock = b.blockId;
                        if (!b.items.isEmpty()) {
                            double rLat = b.items.get(0).x / 1e7, rLon = b.items.get(0).y / 1e7;
                            copter.rallyE = GeoLocal.east(refLat, refLon, rLat, rLon);
                            copter.rallyN = GeoLocal.north(refLat, refLon, rLat, rLon);
                        }
                    } else if (b != null) synchronized (copter) {
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
                case FleetCmd.TRAFFIC -> {
                    de.droiddrone.common.group.Traffic t = de.droiddrone.common.group.Traffic.decode(c.data, c.at + 1, c.length - 1);
                    if (t != null) {
                        traffic = t;
                        trafficCount.incrementAndGet();
                    }
                }
                case FleetCmd.DISBAND -> {
                    if (brain != null) leftGroupAt = now;
                    brain = null;
                }
                case FleetCmd.LEAD -> { // as FleetRole: a follower told to lead lets its commander's session in (FL-6)
                    if (c.length < 4) break;
                    boolean lead = c.data[c.at + 1] != 0;
                    boolean follower = brain != null || leftGroupAt != 0 && now - leftGroupAt <= FleetCommandGate.LEFT_GROUP_MS;
                    if (!lead) leadOrderedAt = 0;
                    else if (!inSession() && follower && (leadOrderedAt == 0 || now - leadOrderedAt > FleetCommandGate.LEAD_CONNECT_MS)) leadOrderedAt = now;
                }
                case FleetCmd.RETURN -> { // RT-9 / FD-6: as FleetRole - its RTL_ALT, "read back" at once
                    double h = FleetCmd.readReturnHeight(c);
                    if (h > 0 && !ignoreReturn) rtlAltDm = (int) Math.round(h * 10);
                }
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
                if (b != null && now - lastBrain >= 100 && !inSession()) {
                    lastBrain = now;
                    double[] ll = latLon();
                    FleetIntent i = b.tick(now, commanderHeardAt, ll[0], ll[1], copter.battery, copter.gnssOk);
                    if (i.kind != FleetIntent.NONE) copter.apply(i, refLat, refLon);
                }
                copter.step(STEP_MS / 1000.0, 0, 0, groundAmsl);
            }
            report(now);
            sessionTelemetry(now);
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
            s.routeState = route == null && rally == null ? 0 : de.droiddrone.common.MissionUpload.DONE;
            s.routePercent = route == null ? 0 : 100;
            s.mode = auto || holding ? 3 : 5; // at its LOITER_UNLIM ArduPilot stays in AUTO
            if (armed && !copter.landed) s.flags |= FleetStatus.ARMED;
            if (copter.gnssOk) s.flags |= FleetStatus.GNSS;
            if (copter.landed) s.flags |= FleetStatus.LANDED;
            if (now - commanderHeardAt <= 3000) s.flags |= FleetStatus.PC_HEARD;
            s.errM = copter.errorM((now - start) / 1000.0);
            s.rtlAltDm = ignoreReturn ? -1 : rtlAltDm;
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

    /** AC-10: the rally list the PC sent (null: none). */
    private volatile java.util.List<de.droiddrone.common.MissionItem> rally;

    public java.util.List<de.droiddrone.common.MissionItem> rally() {
        return rally;
    }

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
    /** FD-8 / AC-11: the last TRAFFIC from the PC - what its FC would get as ADS-B targets - and how many came. */
    public volatile de.droiddrone.common.group.Traffic traffic;
    /** RT-9: RTL_ALT on its "FC" (dm above home): ArduPilot Copter's default 15 m until the PC sets it. */
    public volatile int rtlAltDm = 150;
    /** An older phone: RETURN unknown to it, its reports without RTL_ALT (tests). */
    public volatile boolean ignoreReturn;

    public double rtlAltM() {
        return rtlAltDm / 10.0;
    }
    public final java.util.concurrent.atomic.AtomicInteger trafficCount = new java.util.concurrent.atomic.AtomicInteger();

    /** Every ARM this phone gave, with whether it held a mark then (the AL-12 invariant, for the tests). */
    public final java.util.List<String> arms = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** AC-10: every rally block it was sent (the same one again counts again). */
    public final java.util.List<Integer> rallyBlocks = new java.util.concurrent.CopyOnWriteArrayList<>();

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
            if (armed() || !copter.gnssOk) return; // as the phone's LaunchRunner: never marked without a 3D fix (AL-11)
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
                    arms.add(copter.id + (seq.mark() != null || seq.order() != null ? " marked" : " UNMARKED") + (rally == null ? " NO RALLY" : ""));
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

    // ---- the pilot's session (fleet FL-6): the phone's session rules (SessionGate, the hand-over's FleetCommandGate),
    // the Connect answered, the Config taken, pings, ArduPilot's armed word once a second, the stick frames counted

    private final de.droiddrone.common.UdpSender sender;
    private volatile de.droiddrone.common.ReceiverBuffer session;
    private final de.droiddrone.common.SessionGate gate;
    private final Object sessionLock = new Object();
    /** The PC that flew it last (the phone's lastPcIp / lastPcPort), or null. */
    private volatile InetAddress lastPcIp;
    private volatile int lastPcPort;
    /** FL-6 as FleetRole: when its commander's order to lead came to it as a follower (0: none), and when it left the group. */
    private volatile long leadOrderedAt, leftGroupAt;
    private volatile int sessions, sticksTaken;
    /** When the current session's first stick frame came (wall clock; 0: none yet). */
    private volatile long firstStickAt;
    private long heartbeatAt;
    /** ArduPilot Copter 4.6 on a quad, as the phone reports it. */
    static final de.droiddrone.common.FcInfo ARDUCOPTER = new de.droiddrone.common.FcInfo(
            de.droiddrone.common.FcInfo.FC_VARIANT_ARDUPILOT, 4, 6, 0, 0, 0, 3, 2);

    /** The pilot session's packets wait this long on their way in (0: none). */
    private volatile int sessionDelayMs;
    private static final java.util.concurrent.ScheduledExecutorService LATER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "virtualFleetPhoneLater");
        t.setDaemon(true);
        return t;
    });

    /**
     * A mobile network's round trip for the pilot's session: every packet of it waits this long on its way in - the whole
     * round trip on one way, a little worse than the real thing (a stick frame arrives a round trip after it went, not half).
     */
    public void setSessionDelayMs(int roundTripMs) {
        sessionDelayMs = Math.max(0, roundTripMs);
    }

    /** No fleet command: the pilot's session's, or the other handler's (a name query). */
    private void notFleet(DatagramPacket p) {
        if (!open || pilotPacket(p)) return;
        java.util.function.Consumer<DatagramPacket> h = other;
        if (h != null) h.accept(p);
    }

    /** This PC flew it last (its pilot session's address and port), and commands it since (FleetRole.pilotWas). */
    public void flownBy(InetSocketAddress pc) {
        lastPilot(pc);
        commander = pc;
        commanderHeardAt = System.currentTimeMillis();
    }

    /** Only who flew it last (its commander stays): a reserve set up on another PC, then commanded from this one. */
    public void lastPilot(InetSocketAddress pc) {
        lastPcIp = pc.getAddress();
        lastPcPort = pc.getPort();
    }

    /** A pilot's session is up. */
    public boolean inSession() {
        de.droiddrone.common.ReceiverBuffer b = session;
        return b != null && b.isConnected();
    }

    /** Pilot sessions started so far. */
    public int sessions() {
        return sessions;
    }

    /** The pilot's stick frames taken so far. */
    public int sticksTaken() {
        return sticksTaken;
    }

    /** When the session's first stick frame came (wall clock), 0: none in this session. */
    public long firstStickAt() {
        return firstStickAt;
    }

    /**
     * A fleet command (keyed): FleetCommandGate as FleetRole asks it. A phone made without a past (no commander, no
     * pilot: the tests' and the show's) takes the first, as before.
     */
    private boolean commandTaken(InetAddress from, long now) {
        InetSocketAddress c = commander;
        if (c == null && lastPcIp == null) return true;
        boolean in = inSession();
        boolean fromPilot = in && from.equals(sender.getIp());
        int v = de.droiddrone.common.FleetCommandGate.decide(in, fromPilot, c != null, c != null && c.getAddress().equals(from),
                c == null ? Long.MAX_VALUE : now - commanderHeardAt, !armed || copter.landed);
        return v != de.droiddrone.common.FleetCommandGate.REFUSE;
    }

    /** A packet of a pilot's session (the phone's receive thread's rules); false: not one, for the other handler. */
    private boolean pilotPacket(DatagramPacket p) {
        int len = p.getLength();
        if (len < 1) return true;
        byte[] d = java.util.Arrays.copyOf(p.getData(), len);
        if (d[0] == de.droiddrone.common.PacketIds.NAME_QUERY || d[0] == de.droiddrone.common.PacketIds.NAME_ASSIGN) return false;
        InetAddress from = p.getAddress();
        int port = p.getPort();
        long now = System.currentTimeMillis();
        long id = de.droiddrone.common.SessionGate.sessionId(d, len);
        synchronized (sessionLock) {
            de.droiddrone.common.ReceiverBuffer b = session;
            if (b.isConnected()) {
                if (from.equals(sender.getIp()) && port == sender.getPort()) {
                    lastPcIp = from;
                    lastPcPort = port;
                    de.droiddrone.common.SessionGate.Verdict v = gate.fromPilot(d, len, now, id);
                    if (v == de.droiddrone.common.SessionGate.Verdict.SAME_SESSION) return true;
                    if (v == de.droiddrone.common.SessionGate.Verdict.NEW_SESSION) b = newSession(b, from, port);
                } else {
                    de.droiddrone.common.SessionGate.Verdict v = gate.fromOther(d, len, from.equals(sender.getIp()), !armed, now, id);
                    if (v != de.droiddrone.common.SessionGate.Verdict.NEW_SESSION) return true; // the phone tells it PILOT_AT
                    b = newSession(b, from, port);
                }
            } else {
                boolean pilotKnown = lastPcIp != null, sameAddress = from.equals(lastPcIp), samePc = sameAddress && port == lastPcPort;
                de.droiddrone.common.SessionGate.Verdict v = gate.noSession(d, len, pilotKnown, samePc, sameAddress, () -> !armed, now, id);
                if (v == de.droiddrone.common.SessionGate.Verdict.SPECTATOR && mayLead(from, now)) v = gate.leadSession(now, id);
                if (v != de.droiddrone.common.SessionGate.Verdict.NEW_SESSION) return v != de.droiddrone.common.SessionGate.Verdict.DROP;
                sessionStarted(from, port);
            }
            if (d[0] == de.droiddrone.common.UdpCommon.RcFrame) {
                if (b.isConnected()) {
                    if (firstStickAt == 0) firstStickAt = now;
                    sticksTaken++;
                }
                return true;
            }
            if (d[0] == de.droiddrone.common.UdpCommon.Connect) de.droiddrone.common.SessionGate.markAnswer(d, len);
            b.addPacket(new DatagramPacket(d, len, from, port));
        }
        return true;
    }

    private boolean mayLead(InetAddress from, long now) {
        InetSocketAddress c = commander;
        long at = leadOrderedAt;
        if (!de.droiddrone.common.FleetCommandGate.leadConnect(at != 0, c != null && c.getAddress().equals(from), now - at)) return false;
        leadOrderedAt = 0;
        return true;
    }

    private de.droiddrone.common.ReceiverBuffer newSession(de.droiddrone.common.ReceiverBuffer old, InetAddress pilot, int port) {
        de.droiddrone.common.ReceiverBuffer b = new de.droiddrone.common.ReceiverBuffer(sender, true, key, null);
        session = b;
        old.close();
        sessionStarted(pilot, port);
        return b;
    }

    private void sessionStarted(InetAddress pilot, int port) {
        sessions++;
        firstStickAt = 0;
        InetSocketAddress c = commander; // FleetRole.pilotWas: the pilot's PC commands it from now on
        if (c == null || !c.getAddress().equals(pilot)) commander = new InetSocketAddress(pilot, port);
        commanderHeardAt = System.currentTimeMillis();
    }

    /** The session's numbered packets (the phone's buffer thread). */
    private void sessionRun() {
        while (open) {
            try {
                de.droiddrone.common.ReceiverBuffer b = session;
                de.droiddrone.common.SavedPacket packet;
                while ((packet = b.getNextPacket()) != null) sessionData(packet.data);
                b.processTimer();
                Thread.sleep(2);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // the next packet
            }
        }
    }

    private void sessionData(byte[] data) {
        de.droiddrone.common.DataReader r = new de.droiddrone.common.DataReader(data, true);
        byte name = r.readByte();
        if (de.droiddrone.common.UdpCommon.isPacketNumbered(name)) r.readShort();
        try {
            switch (name) {
                case de.droiddrone.common.UdpCommon.Ping -> {
                    boolean toEndPoint = r.readBoolean();
                    long time = r.readLong();
                    byte target = r.getRemaining() > 0 ? r.readByte() : -1;
                    sender.sendPong(toEndPoint, time, target);
                }
                case de.droiddrone.common.UdpCommon.Config -> {
                    int version = r.readShort();
                    byte answer = version == de.droiddrone.common.SettingsCommon.versionCompatibleCode
                            ? de.droiddrone.common.UdpCommon.ConfigReceived : de.droiddrone.common.UdpCommon.VersionMismatch;
                    sender.sendPacket(new de.droiddrone.common.UdpPacketData(answer).getData());
                }
                case de.droiddrone.common.UdpCommon.FcInfo -> {
                    de.droiddrone.common.UdpPacketData p = new de.droiddrone.common.UdpPacketData(de.droiddrone.common.UdpCommon.FcInfo);
                    p.daos.writeByte(ARDUCOPTER.getFcVariant());
                    p.daos.writeByte(4);
                    p.daos.writeByte(6);
                    p.daos.writeByte(0);
                    p.daos.writeByte(0);
                    p.daos.writeByte(0);
                    p.daos.writeByte(3);
                    p.daos.writeByte(2);
                    sender.sendPacket(p.getData());
                }
                case de.droiddrone.common.MissionBlock.PACKET -> sessionBlock(r);
                case de.droiddrone.common.FcCommand.PACKET -> { // the route window's GO, the mode bar: as Mavlink.command
                    byte[] c = new byte[1 + r.getRemaining()];
                    c[0] = name;
                    r.read(c, 1, c.length - 1);
                    de.droiddrone.common.FcCommand k = de.droiddrone.common.FcCommand.read(c, c.length);
                    if (k != null) synchronized (copter) {
                        if (k.kind == de.droiddrone.common.FcCommand.MISSION_START) {
                            routeNext = 1;
                            holding = false;
                            auto = route != null;
                        } else if (k.kind == de.droiddrone.common.FcCommand.CONTINUE) {
                            auto = route != null;
                        } else if (k.kind == de.droiddrone.common.FcCommand.PAUSE || k.kind == de.droiddrone.common.FcCommand.MODE) {
                            auto = false;
                        }
                    }
                }
                default -> {
                    // video, audio, the profile: a fleet phone here has no camera
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // the next one
        }
    }

    // ---- fleet RT-8 in the pilot's session: UPLOAD from the route window, as the real phone takes it (Udp's MissionBlock
    // case, then Mavlink's upload over USB and the status record) - here "read back" at once

    /** The phone app's version as it reports it: pc.58 and up take FcCommand and MissionBlock. */
    public static final String APP_VERSION = "2.3.0-pc.58-fleet";
    private final java.util.List<de.droiddrone.common.MissionBlock> sessionBlocks = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The last block's status, sent with the session's telemetry until the next (the real phone says it again too). */
    private volatile byte[] blockStatus;
    /** Where it took off: ArduPilot's home. */
    private final double[] home;

    /** The blocks the pilot's session sent (routes, fences, rally points), in order. */
    public java.util.List<de.droiddrone.common.MissionBlock> sessionBlocks() {
        return java.util.List.copyOf(sessionBlocks);
    }

    /** Refuses the next session blocks with this reason (null: takes them). */
    public volatile String refuseBlocks;

    /** The parts of the blocks sent to this phone (both paths), put together as the real phone does. */
    private final de.droiddrone.common.MissionBlock.Assembler parts = new de.droiddrone.common.MissionBlock.Assembler();

    private void sessionBlock(de.droiddrone.common.DataReader r) {
        byte[] buf = new byte[r.getRemaining()];
        if (r.read(buf, 0, buf.length) != buf.length) return;
        de.droiddrone.common.MissionBlock.Part part = de.droiddrone.common.MissionBlock.readPart(buf, 0, buf.length);
        if (part == null) return;
        de.droiddrone.common.MissionBlock b = parts.offer(part);
        if (b == null) { // not whole yet: the phone says how many of its parts came
            int got = parts.percent(part.blockId);
            if (got >= 0) {
                blockStatus = de.droiddrone.common.MissionBlock.status(part.blockId, de.droiddrone.common.MissionBlock.RECEIVING, got, null);
                sessionTelemetryNow();
            }
            return;
        }
        String no = refuseBlocks;
        if (no == null && b.missionType == de.droiddrone.common.MissionUpload.TYPE_MISSION) synchronized (copter) {
            route = b.items; // flown at GO (MISSION_START), as a dispatched route is
            routeBlock = b.blockId;
            routeNext = 1;
            holding = false;
        }
        if (no == null) sessionBlocks.add(b);
        blockStatus = de.droiddrone.common.MissionBlock.status(b.blockId, no == null ? de.droiddrone.common.MissionUpload.DONE
                : de.droiddrone.common.MissionUpload.FAILED, no == null ? 100 : 0, no);
        sessionTelemetryNow();
    }

    /** In a session: ArduPilot's heartbeat - its mode and armed word - as the phone forwards it, once a second. */
    private void sessionTelemetry(long now) {
        if (now < heartbeatAt) return;
        heartbeatAt += 1000;
        if (now >= heartbeatAt) heartbeatAt = now + 1000;
        sessionTelemetryNow();
    }

    /**
     * The session's telemetry: the mode and armed word, the app's version, GPS, position and home as the phone forwards
     * ArduPilot's, and the last route block's status.
     */
    private void sessionTelemetryNow() {
        if (!inSession() || !linkUp) return;
        try {
            de.droiddrone.common.UdpPacketData p = new de.droiddrone.common.UdpPacketData(de.droiddrone.common.UdpCommon.TelemetryData);
            double[] ll;
            double rel;
            synchronized (copter) {
                ll = latLon();
                rel = copter.u - groundAmsl;
            }
            de.droiddrone.common.DataWriter w = new de.droiddrone.common.DataWriter(true);
            w.writeInt(auto ? 3 : 5);
            w.writeBoolean(armed && !copter.landed);
            record(p, de.droiddrone.common.FcCommon.DD_MAVLINK_MODE, w);
            w = new de.droiddrone.common.DataWriter(true);
            w.writeByte((byte) 3); // 3D fix
            w.writeShort((short) 0);
            w.writeByte((byte) 14);
            record(p, de.droiddrone.common.FcCommon.DD_MAVLINK_GPS_RAW_INT, w);
            w = new de.droiddrone.common.DataWriter(true);
            w.writeInt((int) Math.round(ll[0] * 1e7));
            w.writeInt((int) Math.round(ll[1] * 1e7));
            w.writeInt((int) Math.round(rel * 1000));
            w.writeShort((short) 0);
            w.writeInt(0);
            w.writeInt(0);
            w.writeShort((short) 0);
            record(p, de.droiddrone.common.FcCommon.DD_MAVLINK_GLOBAL_POSITION_INT, w);
            w = new de.droiddrone.common.DataWriter(true);
            w.writeInt((int) Math.round(home[0] * 1e7));
            w.writeInt((int) Math.round(home[1] * 1e7));
            record(p, de.droiddrone.common.FcCommon.DD_MAVLINK_HOME_POSITION, w);
            byte[] status = blockStatus;
            if (status != null) {
                w = new de.droiddrone.common.DataWriter(true);
                w.writeArray(status, 0, status.length);
                record(p, de.droiddrone.common.MissionBlock.STATUS_RECORD, w);
            }
            byte[] v = APP_VERSION.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            p.daos.writeShort(0x40F1); // the phone's own record: its app version (FcTelemetry.PHONE_APP_VERSION)
            p.daos.writeShort(v.length);
            p.daos.write(v);
            sender.sendPacket(p.getData());
        } catch (IOException | RuntimeException ignored) {
            // the next heartbeat
        }
    }

    private static void record(de.droiddrone.common.UdpPacketData p, short code, de.droiddrone.common.DataWriter w) throws IOException {
        de.droiddrone.common.TelemetryRecords.write(new de.droiddrone.common.TelemetryData(code, w.getData()), ARDUCOPTER, p.daos);
    }

    @Override
    public void close() {
        open = false;
        sender.close();
        session.close();
        socket.close();
    }
}
