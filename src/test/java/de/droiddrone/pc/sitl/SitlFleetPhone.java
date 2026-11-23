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

    SitlFleetPhone(ArduSitl sitl, String key) throws IOException {
        this.sitl = sitl;
        this.fc = sitl.fc;
        this.key = key;
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

    private void receive() {
        byte[] buf = new byte[2048];
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
                        GroupOrder o = GroupOrder.decode(c.data, c.at + 1);
                        if (o == null) break;
                        if (brain == null) brain = new FollowerBrain(1000);
                        brain.order(o, now);
                    }
                    case FleetCmd.EXIT -> {
                        ExitPlan plan = ExitPlan.decode(c.data, c.at + 1);
                        if (plan != null && brain != null) {
                            brain.plan(plan);
                            rtlAlt(plan.returnAltRelM);
                        }
                    }
                    case FleetCmd.DISBAND -> brain = null;
                    case FleetCmd.TRACK -> reportUntil = now + (c.data[c.at + 1] & 0xff) * 1000L;
                    case FleetCmd.MARK -> mark(c.data, c.at, c.length);
                    case FleetCmd.LAUNCH -> {
                        LaunchWire.Order o = LaunchWire.decodeOrder(c.data, c.at + 1, c.length - 1);
                        if (o != null) act(seq.order(o, now));
                    }
                    case FcCommand.PACKET -> {
                        FcCommand k = FcCommand.read(java.util.Arrays.copyOfRange(c.data, c.at, c.at + c.length), c.length);
                        if (k != null && k.kind == FcCommand.MODE) mode(k.mode);
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
                    launchTick(now);
                    FollowerBrain b = brain;
                    double[] p = fc.position();
                    if (b != null && p != null) {
                        FleetIntent i = b.tick(now, commanderHeardAt, p[0], p[1], 80, fc.gpsFix() >= 3);
                        act(i, now);
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

    /** As DRAUPNE Flight's FleetRole.act. */
    private void act(FleetIntent i, long now) throws IOException, InterruptedException {
        boolean changed = i.kind != last.kind;
        switch (i.kind) {
            case FleetIntent.GUIDED -> {
                if (changed || now - lastGuided >= 200) {
                    lastGuided = now;
                    if (fc.customMode() != GUIDED) mode(GUIDED);
                    int mask = 0x1C0 | 0x800 | (Double.isNaN(i.yawDeg) ? 0x400 : 0);
                    fc.send(new msg_set_position_target_global_int(0, (int) Math.round(i.lat * 1e7), (int) Math.round(i.lon * 1e7),
                            (float) i.altAmsl, (float) i.velN, (float) i.velE, 0f, 0f, 0f, 0f, Double.isNaN(i.yawDeg) ? 0f : (float) Math.toRadians(i.yawDeg),
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
        r.imuHealthy = r.compassHealthy = r.fcHealthy = true;
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
