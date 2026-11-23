package de.droiddrone.pc.sitl;

import com.MAVLink.common.msg_param_set;
import com.MAVLink.common.msg_set_position_target_global_int;
import de.droiddrone.common.FcCommand;
import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.FleetIntent;
import de.droiddrone.common.group.FollowerBrain;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.group.GroupOrder;
import de.droiddrone.common.launch.ClimbWatchdog;
import de.droiddrone.common.launch.LaunchIntent;
import de.droiddrone.common.launch.LaunchMark;
import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;
import de.droiddrone.common.launch.PreArmCheck;
import de.droiddrone.pc.setup.ardu.MavSession;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Fleet PRD phases 4 and 5 against real ArduPilot: a drone's phone on the fleet link with SITL for its flight
 * controller - the phone's fleet role as DRAUPNE Flight's FleetRole and LaunchRunner carry it out (the shared
 * FollowerBrain and LaunchSequence decide; GUIDED targets as SET_POSITION_TARGET_GLOBAL_INT with the velocity fed
 * forward, DO_SET_MODE, RTL_ALT, arm, NAV_TAKEOFF, the forced disarm), its reports (FleetStatus) and launch statuses to
 * the commander. Only for the SITL tests; the phone's own copies are Android code.
 */
final class SitlFleetPhone implements AutoCloseable {
    static final int GUIDED = 4, LOITER = 5, RTL = 6, LAND = 9, ALT_HOLD = 2;

    final ArduSitl sitl;
    final MavSession fc;
    private final String key;
    private final DatagramSocket socket;
    private volatile InetSocketAddress commander;
    private volatile long commanderHeardAt, reportUntil;
    private volatile FollowerBrain brain;
    private FleetIntent last = FleetIntent.NOTHING;
    private long lastGuided;
    private final PreArmCheck check = new PreArmCheck(0);
    private final LaunchSequence seq = new LaunchSequence(check);
    private volatile boolean open = true;
    volatile boolean linkUp = true;

    /** The blocks' parts, put together as the phone does. */
    final de.droiddrone.common.MissionBlock.Assembler parts = new de.droiddrone.common.MissionBlock.Assembler();

    SitlFleetPhone(ArduSitl sitl, String key) throws IOException {
        this.sitl = sitl;
        this.fc = sitl.fc;
        this.key = key;
        SitlMission.feed(fc, () -> upload);
        fc.onText(t -> { if (texts.size() < 200) texts.add(t); });
        socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        Thread r = new Thread(this::receive, "sitlFleetPhone" + sitl.instance);
        r.setDaemon(true);
        r.start();
        Thread t = new Thread(this::run, "sitlFleetPhoneTick" + sitl.instance);
        t.setDaemon(true);
        t.start();
    }

    InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort());
    }

    boolean isFollower() {
        return brain != null;
    }

    int launchState() {
        return seq.state();
    }

    /** ArduPilot's texts of this drone (PreArm and the rest). */
    final java.util.List<String> texts = new java.util.concurrent.CopyOnWriteArrayList<>();

    String launchWhy() {
        return LaunchSequence.stateWord(seq.state()) + " (" + LaunchSequence.reasonWord(seq.reason()) + ")";
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
            if (c == null || !c.key.equals(key)) continue;
            long now = System.currentTimeMillis();
            commander = new InetSocketAddress(p.getAddress(), p.getPort());
            commanderHeardAt = now;
            reportUntil = Math.max(reportUntil, now + 15_000);
            synchronized (this) {
                try {
                switch (c.inner()) {
                    case FleetCmd.GROUP -> {
                        if (piloted) break; // the piloted drone is never a follower (FL-7), as the phone in a session
                        GroupOrder o = GroupOrder.decode(c.data, c.at + 1);
                        if (o == null) break;
                        if (brain == null) brain = new FollowerBrain(1000);
                        brain.order(o, now);
                    }
                    case FleetCmd.EXIT -> {
                        ExitPlan plan = ExitPlan.decode(c.data, c.at + 1);
                        if (plan != null && brain != null) {
                            brain.plan(plan);
                            lastPlan = plan;
                            rtlAlt(plan.returnAltRelM);
                            exitRally(plan, now); // as FleetRole: its RTL to its own rally point (AC-10), when it changed
                        }
                    }
                    case FleetCmd.DISBAND -> brain = null;
                    case FleetCmd.LEAD -> { // as FleetRole: a lead's RTL height (AC-41)
                        if (c.length >= 4 && c.data[c.at + 1] != 0) leadRtlAltM = ((c.data[c.at + 2] & 0xff) << 8 | (c.data[c.at + 3] & 0xff)) / 10.0;
                    }
                    case FleetCmd.RETURN -> {
                        double h = FleetCmd.readReturnHeight(c);
                        if (h > 0) rtlAltSet(h);
                    }
                    case FleetCmd.TRACK -> reportUntil = now + (c.data[c.at + 1] & 0xff) * 1000L;
                    case FleetCmd.MARK -> mark(c.data, c.at, c.length);
                    case FleetCmd.LAUNCH -> {
                        LaunchWire.Order o = LaunchWire.decodeOrder(c.data, c.at + 1, c.length - 1);
                        if (o != null) act(seq.order(o, now));
                    }
                    case FcCommand.PACKET -> {
                        FcCommand k = FcCommand.read(java.util.Arrays.copyOfRange(c.data, c.at, c.at + c.length), c.length);
                        if (k != null && k.kind == FcCommand.MODE) mode(k.mode);
                        if (k != null && k.kind == FcCommand.MISSION_START) { // as the phone's Mavlink.command: AUTO, then MISSION_START
                            mode(3);
                            fc.command(300, 0, 0, 0, 0, 0, 0, 0, 0);
                        }
                    }
                    case de.droiddrone.common.MissionBlock.PACKET -> { // the route, uploaded and read back as the phone does over USB
                        // one part: whole when every part has come (the phone's Mavlink.missionPart)
                        de.droiddrone.common.MissionBlock b = parts.offer(de.droiddrone.common.MissionBlock.readPart(c.data, c.at + 1, c.length - 1));
                        if (b != null && b.blockId != routeBlock) {
                            routeBlock = b.blockId;
                            upload = new de.droiddrone.common.MissionUpload(b.items, SitlMission.link(fc, b.missionType), b.missionType); // its own list (a fence went up as a route)
                            upload.start(now);
                            if (b.missionType == de.droiddrone.common.MissionUpload.TYPE_RALLY) rallyInclHomeOff(); // as the phone: RTL to the rally point
                        }
                    }
                    default -> {
                    }
                }
                } catch (Exception ignored) {
                    // the commander sends it again
                }
            }
        }
    }

    private volatile de.droiddrone.common.MissionUpload upload;

    private double rallyLat = Double.NaN, rallyLon = Double.NaN;

    /** As Mavlink.rallyPoint on the phone: the exit plan's rally point uploaded (read back) when it changed. */
    private void exitRally(ExitPlan plan, long now) {
        if (!Double.isNaN(rallyLat) && GeoLocal.distance(rallyLat, rallyLon, plan.rallyLat, plan.rallyLon) < 1) return;
        de.droiddrone.common.MissionUpload u = upload;
        if (u != null && u.state() != de.droiddrone.common.MissionUpload.DONE && u.state() != de.droiddrone.common.MissionUpload.FAILED) return; // one at a time: the next plan brings it again
        rallyLat = plan.rallyLat;
        rallyLon = plan.rallyLon;
        upload = new de.droiddrone.common.MissionUpload(de.droiddrone.pc.launch.RallyPlan.items(plan.rallyLat, plan.rallyLon, plan.returnAltRelM),
                SitlMission.link(fc, de.droiddrone.common.MissionUpload.TYPE_RALLY), de.droiddrone.common.MissionUpload.TYPE_RALLY);
        upload.start(now);
        rallyInclHomeOff();
    }

    /** The upload under way (or ended), and its block. */
    de.droiddrone.common.MissionUpload uploadNow() {
        return upload;
    }

    int routeBlockNow() {
        return routeBlock;
    }

    /** The upload under way, for a failing test's message. */
    String uploadWhy() {
        de.droiddrone.common.MissionUpload u = upload;
        return u == null ? "no upload" : "block " + routeBlock + " type " + u.missionType() + " state " + u.state() + " " + u.percent() + " % " + u.reason();
    }
    /** AC-10: the rally list ArduPilot read back (null: none yet), and RALLY_INCL_HOME as ArduPilot answered it. */
    volatile java.util.List<de.droiddrone.common.MissionItem> rallyRead;
    volatile float rallyInclHome = Float.NaN;

    /** RT-9: RTL_ALT as ArduPilot answered the last RETURN (dm), -1 none yet. */
    volatile int rtlAltDmRead = -1;
    private volatile boolean rtlBusy;

    /** As the phone's FleetRole: RETURN - PARAM_SET RTL_ALT; the answer (PARAM_VALUE) goes into the reports. */
    private void rtlAltSet(double relM) {
        if (rtlBusy) return; // the PC sends it again in a second
        rtlBusy = true;
        Thread t = new Thread(() -> {
            try {
                float got;
                try {
                    got = fc.setParam("RTL_ALT_M", (float) (Math.round(relM * 10) / 10.0), 3000) * 100; // Copter 4.7
                } catch (IOException none) {
                    got = fc.setParam("RTL_ALT", Math.round(relM * 10) * 10, 3000); // 4.6 and before
                }
                texts.add("phone: RTL_ALT " + relM + " m asked, ArduPilot said " + got);
                if (!Float.isNaN(got)) rtlAltDmRead = Math.round(got / 10);
            } catch (Exception e) {
                texts.add("phone: RTL_ALT " + e);
                // asked again with the next RETURN
            } finally {
                rtlBusy = false;
            }
        }, "sitlRtlAlt");
        t.setDaemon(true);
        t.start();
    }

    private void rallyInclHomeOff() {
        Thread t = new Thread(() -> {
            try {
                rallyInclHome = fc.setParam("RALLY_INCL_HOME", 0, 3000);
            } catch (Exception ignored) {
                // asked again with the next rally block
            }
        }, "sitlRallyInclHome");
        t.setDaemon(true);
        t.start();
    }
    private volatile int routeBlock = -1;

    private void mark(byte[] d, int at, int len) {
        if (len < 7 || fc.armedFlag()) return;
        int id = (d[at + 1] & 0xff) << 24 | (d[at + 2] & 0xff) << 16 | (d[at + 3] & 0xff) << 8 | (d[at + 4] & 0xff);
        double floor = ((d[at + 5] & 0xff) << 8 | (d[at + 6] & 0xff)) / 10.0;
        double[] p = fc.position();
        if (id == 0 || p == null) {
            seq.setMark(null);
            return;
        }
        seq.setMark(new LaunchMark(id, p[0], p[1], 0, System.currentTimeMillis(), floor));
    }

    private void run() {
        long lastReport = 0;
        while (open) {
            try {
                Thread.sleep(100);
                long now = System.currentTimeMillis();
                synchronized (this) {
                    de.droiddrone.common.MissionUpload u = upload;
                    if (u != null) u.tick(now);
                    if (u != null && u.done() && u.missionType() == de.droiddrone.common.MissionUpload.TYPE_RALLY) rallyRead = u.items();
                    launchTick(now);
                    FollowerBrain b = brain;
                    double[] p = fc.position();
                    if (b != null && p != null && !piloted) {
                        FleetIntent i = b.tick(now, commanderHeardAt, p[0], p[1], 80, fc.gpsFix() >= 3);
                        act(i, now);
                        holdSticks(); // as FleetRole: a follower is fed centred sticks (FL-7) - LOITER holds its height on them
                    }
                }
                if (now - lastReport >= 500) {
                    lastReport = now;
                    report(now);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // the next tick
            }
        }
    }


    /** As FleetRole.holdSticks for a drone never flown by the pilot: the four sticks centred, the switches left alone. */
    private void holdSticks() throws IOException {
        int u = 65535;
        fc.send(new com.MAVLink.common.msg_rc_channels_override(1500, 1500, 1500, 1500, u, u, u, u,
                (short) Math.max(1, fc.fcSysId()), (short) 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
    }

    /** As DRAUPNE Flight's FleetRole.act. */
    private void act(FleetIntent i, long now) throws IOException, InterruptedException {
        boolean changed = i.kind != last.kind;
        switch (i.kind) {
            case FleetIntent.GUIDED -> {
                if (changed || now - lastGuided >= 200) {
                    lastGuided = now;
                    if (fc.customMode() != GUIDED) mode(GUIDED);
                    boolean noVel = Double.isNaN(i.velE) || Double.isNaN(i.velN); // as Mavlink.guidedTarget: position only
                    int mask = 0x1C0 | 0x800 | (Double.isNaN(i.yawDeg) ? 0x400 : 0) | (noVel ? 0x38 : 0);
                    fc.send(new msg_set_position_target_global_int(0, (int) Math.round(i.lat * 1e7), (int) Math.round(i.lon * 1e7),
                            (float) i.altAmsl, noVel ? 0f : (float) i.velN, noVel ? 0f : (float) i.velE, 0f, 0f, 0f, 0f, Double.isNaN(i.yawDeg) ? 0f : (float) Math.toRadians(i.yawDeg),
                            0f, mask, (short) Math.max(1, fc.fcSysId()), (short) 1, (short) 5, MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
                }
            }
            case FleetIntent.LOITER -> {
                if (changed) mode(LOITER);
            }
            case FleetIntent.ALT_HOLD -> {
                if (changed) mode(ALT_HOLD);
            }
            case FleetIntent.LAND -> {
                if (changed) mode(LAND);
            }
            case FleetIntent.RTL -> {
                if (changed) {
                    rtlAlt(i.rtlAltRelM);
                    mode(RTL);
                }
            }
            default -> {
            }
        }
        last = i;
    }

    void mode(int m) throws IOException, InterruptedException {
        fc.command(176, 1, m, 0, 0, 0, 0, 0, 0);
    }

    private void rtlAlt(double relM) throws IOException {
        byte[] id = new byte[16];
        System.arraycopy("RTL_ALT".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, id, 0, 7);
        fc.send(new msg_param_set((float) Math.round(relM * 100), (short) Math.max(1, fc.fcSysId()), (short) 1, id, (short) 9,
                MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
        byte[] m = new byte[16]; // Copter 4.7: RTL_ALT_M (m), as the phone sends both
        System.arraycopy("RTL_ALT_M".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, m, 0, 9);
        fc.send(new msg_param_set((float) (Math.round(relM * 10) / 10.0), (short) Math.max(1, fc.fcSysId()), (short) 1, m, (short) 9,
                MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
    }

    /** As DRAUPNE Flight's LaunchRunner, with SITL's attitude taken as level (its IMU is the simulated one). */
    private void launchTick(long now) throws IOException, InterruptedException {
        if (seq.state() == LaunchSequence.IDLE) return;
        double[] p = fc.position();
        if (p == null) return;
        check.motion(now, 1.0, 0.0);
        PreArmCheck.Reading r = new PreArmCheck.Reading();
        r.rollDeg = 0;
        r.pitchDeg = 0;
        r.headingDeg = 0;
        r.lat = p[0];
        r.lon = p[1];
        r.gpsFix3d = fc.gpsFix() >= 3;
        r.batteryPercent = 80;
        r.imuHealthy = r.compassHealthy = true;
        r.fcHealthy = fc.preArmOk(); // as the phone: ArduPilot's own pre-arm checks
        ClimbWatchdog.Sample s = null;
        if (seq.state() == LaunchSequence.CLIMBING) {
            s = new ClimbWatchdog.Sample();
            s.atMs = now;
            s.tiltDeg = 2;
            s.accelG = 1.0;
            s.climbMps = p[4];
            s.throttlePercent = 55;
            LaunchMark m = seq.mark();
            s.driftM = m == null ? 0 : GeoLocal.distance(m.lat, m.lon, p[0], p[1]);
            s.heightM = p[3];
        }
        seq.fcReady(true);
        act(seq.tick(now, r, fc.armedFlag(), s));
    }

    private void act(List<LaunchIntent> todo) {
        for (LaunchIntent i : todo) {
            try {
                switch (i.type) {
                    case LaunchIntent.SET_MODE_GUIDED -> mode(GUIDED);
                    case LaunchIntent.ARM -> fc.command(400, 1, 0, 0, 0, 0, 0, 0, 0);
                    case LaunchIntent.TAKEOFF -> fc.command(22, 0, 0, 0, 0, 0, 0, (float) i.heightM, 0);
                    case LaunchIntent.MOTORS_OFF -> fc.command(400, 0, 21196, 0, 0, 0, 0, 0, 0);
                    case LaunchIntent.REPORT -> {
                        InetSocketAddress to = commander;
                        if (to == null || !linkUp) break;
                        byte[] st = LaunchWire.encode(i.status);
                        byte[] pk = new byte[1 + st.length];
                        pk[0] = FleetCmd.LAUNCH_STATUS;
                        System.arraycopy(st, 0, pk, 1, st.length);
                        socket.send(new DatagramPacket(pk, pk.length, to.getAddress(), to.getPort()));
                    }
                    default -> {
                    }
                }
            } catch (Exception ignored) {
                // the sequence's own timeouts catch what didn't happen
            }
        }
    }

    private void report(long now) throws IOException {
        InetSocketAddress to = commander;
        double[] p = fc.position();
        if (to == null || p == null || !linkUp || now >= reportUntil) return;
        FleetStatus s = new FleetStatus();
        s.lat = p[0];
        s.lon = p[1];
        s.altAmsl = p[2];
        s.velU = p[4];
        s.velE = p[5];
        s.velN = p[6];
        s.battery = 80;
        s.homeAmsl = sitl.homeAlt;
        s.rtlAltDm = rtlAltDmRead;
        de.droiddrone.common.MissionUpload u = upload;
        if (u != null) {
            s.routeBlock = routeBlock;
            s.routeState = u.state();
            s.routePercent = u.percent();
        }
        s.mode = Math.max(0, Math.min(254, fc.customMode()));
        if (fc.armedFlag()) s.flags |= FleetStatus.ARMED;
        else s.flags |= FleetStatus.LANDED;
        if (fc.gpsFix() >= 3) s.flags |= FleetStatus.GNSS;
        if (now - commanderHeardAt <= 3000) s.flags |= FleetStatus.PC_HEARD;
        FollowerBrain b = brain;
        s.phase = b == null ? 255 : b.phase();
        byte[] d = s.encode();
        socket.send(new DatagramPacket(d, d.length, to.getAddress(), to.getPort()));
    }


    /** The pilot flies it now (a session as the phone has it): no group orders, its follower brain let go. */
    volatile boolean piloted;
    /** The RTL height (m above home) the commander last gave it as a lead; NaN: none. */
    volatile double leadRtlAltM = Double.NaN;

    void setPiloted(boolean on) {
        piloted = on;
        if (on) brain = null;
    }

    /** The pilot's stand-in: a GUIDED position target (as sticks would take it there). */
    void pilotTo(double lat, double lon, double altAmsl) throws IOException, InterruptedException {
        if (fc.customMode() != GUIDED) mode(GUIDED);
        fc.send(new msg_set_position_target_global_int(0, (int) Math.round(lat * 1e7), (int) Math.round(lon * 1e7), (float) altAmsl,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0x1C0 | 0x400 | 0x800 | 0x38, (short) Math.max(1, fc.fcSysId()), (short) 1, (short) 5,
                MavSession.GCS_SYSID, MavSession.GCS_COMPID, true).pack());
    }

    /** RTL at this height above home (the lead's link-loss rule's last step, AP-5 / AC-41). */
    void rtl(double relM) throws IOException, InterruptedException {
        rtlAlt(relM);
        mode(RTL);
    }

    /** The exit plan it holds now (null: none). */
    ExitPlan plan() {
        FollowerBrain b = brain;
        return b == null ? null : lastPlan;
    }

    private volatile ExitPlan lastPlan;
    /** Test help: off the ground in GUIDED to heightM above home (as a dispatch or a pilot would have it). */
    void takeOff(double heightM) throws Exception {
        mode(GUIDED);
        long end = System.currentTimeMillis() + 60_000;
        while (!fc.armedFlag() && System.currentTimeMillis() < end) {
            fc.command(400, 1, 0, 0, 0, 0, 0, 0, 2000);
            Thread.sleep(1500);
        }
        fc.command(22, 0, 0, 0, 0, 0, 0, (float) heightM, 2000);
    }

    @Override
    public void close() {
        open = false;
        socket.close();
    }
}
