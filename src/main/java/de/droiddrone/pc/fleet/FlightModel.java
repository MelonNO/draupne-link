package de.droiddrone.pc.fleet;

import de.droiddrone.common.ArmRanges;

/**
 * A virtual drone's flight controller and airframe: Betaflight's rules for arming, its RX-loss failsafe and its modes,
 * and a simple angle-mode quad (about 600 g, thrust to weight 2.5, a 4S 1500 mAh pack) - good enough that the OSD, the
 * map and the warnings behave as with a real drone, nothing more. Pure: the caller hands in the sticks and the time.
 * <p>
 * The rules, as Betaflight has them (HANDOFF, "Betaflight facts"):
 * <ul>
 * <li>Arming: the ARM switch going into its range (MSP_MODE_RANGES, {@link #modeRanges}) with the throttle under
 * {@link #MIN_CHECK}, at least {@link #ARM_CLEAN_MS} of clean stick data, past the boot grace, not in a failsafe, level.
 * A switch already on while arming was blocked counts only after it went off again (ARMSWITCH).</li>
 * <li>Disarming: the switch out of its range; a failsafe that drops; landing at the end of a failsafe landing; a crash.</li>
 * <li>RX loss: no sticks for {@link #RX_LOSS_MS} sets RXLOSS and holds the last sticks for {@link #HOLD_MS}; then stage
 * 1 (sticks centred, throttle low) for {@link #FAILSAFE_DELAY_MS}; then stage 2: DROP (disarm now) or LAND (a gentle
 * descent, disarmed on the ground). Sticks back during stage 1 end it; after stage 2 the drone stays disarmed and the
 * switch must go off and on again.</li>
 * </ul>
 * Channels are in Betaflight's own order (roll, pitch, yaw, throttle, AUX1, ...): the phone's channel map is the
 * identity on a virtual drone.
 */
public final class FlightModel {
    // ------------------------------------------------------------------ Betaflight's numbers
    /** Throttle under this arms (min_check). */
    public static final int MIN_CHECK = 1050;
    /** No sticks for this long: RXLOSS. */
    public static final long RX_LOSS_MS = 100;
    /** The last sticks are held this long into an RX loss. */
    public static final long HOLD_MS = 300;
    /** Stage 1 (sticks centred, throttle low) lasts this long (failsafe_delay 15). */
    public static final long FAILSAFE_DELAY_MS = 1500;
    /** Clean stick data needed before arming. */
    public static final long ARM_CLEAN_MS = 500;
    /** No arming this long after power-up (BOOTGRACE). */
    public static final long BOOT_GRACE_MS = 3000;
    /** The GPS has a 3D fix this long after power-up. */
    public static final long GPS_FIX_AFTER_MS = 6000;
    /** A touchdown faster than this is a crash: disarmed. */
    static final double CRASH_MS = 6.0;

    // the arming disable flags' bits (FcTelemetry.BETAFLIGHT_BLOCKERS)
    static final int FLAG_FAILSAFE = 1 << 1, FLAG_RXLOSS = 1 << 2, FLAG_THROTTLE = 1 << 7, FLAG_ANGLE = 1 << 8,
            FLAG_BOOTGRACE = 1 << 9, FLAG_ARMSWITCH = 1 << 25;

    /**
     * The flight modes this virtual Betaflight offers, as MSP_BOXNAMES lists them (the mode flags of MSP_STATUS are in
     * this order) and their permanent ids (MSP_BOXIDS).
     */
    public static final String[] BOX_NAMES = {"ARM", "ANGLE", "HORIZON", "HEADFREE", "FAILSAFE", "BEEPER", "OSD DISABLE",
            "BLACKBOX", "AIR MODE", "GPS RESCUE", "FLIP OVER AFTER CRASH"};
    public static final int[] BOX_IDS = {0, 1, 2, 6, 27, 13, 19, 26, 28, 46, 35};
    static final int BOX_ARM = 0, BOX_ANGLE = 1, BOX_FAILSAFE = 4, BOX_BEEPER = 5;
    /** Permanent mode ids in MSP_MODE_RANGES. */
    static final int MODE_ARM = 0, MODE_ANGLE = 1, MODE_BEEPER = 13;

    public enum Failsafe { NONE, HOLD, STAGE1, DROPPED, LANDING }

    public enum Procedure { DROP, LAND }

    /** Where the switches sit (AUX index: 0 = AUX1) and their ranges in us, start inclusive, end exclusive. */
    public static final class Setup {
        public int armAux = 0, armStart = 1700, armEnd = 2100;
        public int angleAux = 1, angleStart = 1300, angleEnd = 2100;
        /** BEEPER on AUX14 (index 13), where Drone setup puts it (drone.beeperSet). */
        public int beeperAux = 13, beeperStart = 1700, beeperEnd = 2100;
        public Procedure procedure = Procedure.DROP;
        public int cells = 4;
        public int capacityMah = 1500;
    }

    private final Setup setup;
    private final double homeLat, homeLon;
    private final long bootAt;

    // the sticks as the FC has them
    private final short[] rc = new short[18];
    private long lastRxAt = Long.MIN_VALUE / 2;
    private long rxCleanSince = -1;
    private boolean hadRx;

    // arming
    private boolean armed;
    private boolean armSwitchWas;
    /** The arm switch was on while arming was blocked: it must go off first (ARMSWITCH). */
    private boolean armSwitchBlocked;
    private boolean crashed;
    private String lastDisarm = "";
    private long armedAt;
    private int arms;

    // failsafe
    private Failsafe failsafe = Failsafe.NONE;
    private long failsafeSince;

    // the airframe (metres, m/s, degrees; north / east from the start point, altitude over the ground)
    private double north, east, alt, vn, ve, vz;
    private double roll, pitch, heading;
    private double throttleOut;
    private double maxAlt, travelled;
    private boolean homeSet;
    private double homeNorth, homeEast;

    // battery
    private double mahDrawn, amps;

    /** Air: thrust to weight at full throttle, drag (per s), the angle mode's limit. */
    static final double G = 9.81, THRUST_TO_WEIGHT = 2.5, DRAG_H = 0.35, DRAG_V = 0.8, MAX_ANGLE = 55, YAW_RATE = 200, ACRO_RATE = 400;

    public FlightModel(Setup setup, double lat, double lon, double headingDeg, long nowMs) {
        this.setup = setup;
        this.homeLat = lat;
        this.homeLon = lon;
        this.heading = norm(headingDeg);
        this.bootAt = nowMs;
        java.util.Arrays.fill(rc, (short) 1500);
        rc[3] = 1000;
        for (int i = 4; i < rc.length; i++) rc[i] = 1000;
    }

    // ------------------------------------------------------------------ in

    /**
     * A stick frame written to the FC (the phone's MSP_SET_RAW_RC): count channels in Betaflight's order. A channel at 0
     * (not sent, 1.0.49's gap before AUX14) keeps the value it had, as rx_msp holds it.
     */
    public synchronized void sticks(short[] frame, int count, long nowMs) {
        if (nowMs - lastRxAt > RX_LOSS_MS) rxCleanSince = nowMs;
        lastRxAt = nowMs;
        hadRx = true;
        for (int i = 0; i < Math.min(count, rc.length); i++) {
            int v = frame[i];
            if (v >= 885 && v <= 2115) rc[i] = (short) v;
        }
    }

    /** One step of dt seconds ending at nowMs: the failsafe, the switches, the air, the battery. */
    public synchronized void step(long nowMs, double dt) {
        dt = Math.max(0, Math.min(0.2, dt));
        boolean rxOk = nowMs - lastRxAt <= RX_LOSS_MS;
        stepFailsafe(nowMs, rxOk);
        boolean armSwitch = rangeActive(setup.armAux, setup.armStart, setup.armEnd);
        if (!armed) {
            int blockers = blockers(nowMs) & ~FLAG_ARMSWITCH;
            if (armSwitch && !armSwitchWas) {
                if (blockers == 0 && !armSwitchBlocked) arm(nowMs);
                else armSwitchBlocked = true;
            }
            if (!armSwitch) armSwitchBlocked = false;
        } else if (!armSwitch && failsafe == Failsafe.NONE) {
            disarm("the arm switch", nowMs);
        }
        armSwitchWas = armSwitch;
        stepAir(nowMs, dt);
        stepBattery(dt);
    }

    private void stepFailsafe(long nowMs, boolean rxOk) {
        if (rxOk) {
            if (failsafe == Failsafe.HOLD || failsafe == Failsafe.STAGE1) failsafe = Failsafe.NONE;
            if (failsafe == Failsafe.DROPPED && !armed) failsafe = Failsafe.NONE; // recovered: the switch rule blocks re-arming
            return;
        }
        long lost = nowMs - lastRxAt - RX_LOSS_MS;
        if (!armed) {
            if (failsafe == Failsafe.HOLD || failsafe == Failsafe.STAGE1) failsafe = Failsafe.NONE;
            return;
        }
        switch (failsafe) {
            case NONE -> {
                failsafe = Failsafe.HOLD;
                failsafeSince = nowMs;
            }
            case HOLD -> {
                if (lost >= HOLD_MS) {
                    failsafe = Failsafe.STAGE1;
                    failsafeSince = nowMs;
                }
            }
            case STAGE1 -> {
                if (nowMs - failsafeSince >= FAILSAFE_DELAY_MS) {
                    failsafeSince = nowMs;
                    if (setup.procedure == Procedure.LAND && alt > 0.05) {
                        failsafe = Failsafe.LANDING;
                    } else {
                        failsafe = Failsafe.DROPPED;
                        disarm("failsafe (drop)", nowMs);
                        armSwitchBlocked = true; // Betaflight: the switch must go off and on after a failsafe
                    }
                }
            }
            default -> {
            }
        }
    }

    private void arm(long nowMs) {
        armed = true;
        arms++;
        armedAt = nowMs;
        crashed = false;
        lastDisarm = "";
        if (!homeSet || alt < 0.1) { // Betaflight sets home at arming
            homeNorth = north;
            homeEast = east;
            homeSet = true;
        }
    }

    private void disarm(String why, long nowMs) {
        if (!armed) return;
        armed = false;
        lastDisarm = why;
    }

    /** The sticks the FC flies by now: the frame, the held frame, or stage 1's centred sticks with the throttle low. */
    private int stick(int i) {
        if (failsafe == Failsafe.STAGE1 || failsafe == Failsafe.DROPPED) return i == 3 ? 1000 : 1500;
        if (failsafe == Failsafe.LANDING) return i == 3 ? 1330 : 1500; // failsafe_throttle: a slow descent
        return rc[i];
    }

    private void stepAir(long nowMs, double dt) {
        double t = armed ? Math.max(0.05, (stick(3) - 1000) / 1000.0) : 0; // motors idle while armed (air mode off)
        throttleOut = t;
        double rStick = (stick(0) - 1500) / 500.0, pStick = (stick(1) - 1500) / 500.0, yStick = (stick(2) - 1500) / 500.0;
        boolean onGround = alt <= 0.001 && vz <= 0.001;
        if (armed) {
            heading = norm(heading + yStick * YAW_RATE * dt * (onGround && t < 0.3 ? 0 : 1));
            if (angleMode() || failsafe != Failsafe.NONE) {
                double k = Math.min(1, dt / 0.12);
                roll += (rStick * MAX_ANGLE - roll) * k;
                pitch += (-pStick * MAX_ANGLE - pitch) * k; // pitch stick forward: nose down (pitch negative)
            } else {
                roll = clampAngle(roll + rStick * ACRO_RATE * dt);
                pitch = clampAngle(pitch - pStick * ACRO_RATE * dt);
            }
            if (onGround && t < 0.3) { // sitting on its legs
                roll *= 0.5;
                pitch *= 0.5;
            }
        } else if (onGround) {
            roll = 0;
            pitch = 0;
        }
        double thrust = t * THRUST_TO_WEIGHT * G;
        double rr = Math.toRadians(roll), pr = Math.toRadians(pitch), hr = Math.toRadians(heading);
        double forward = thrust * Math.sin(-pr), right = thrust * Math.sin(rr) * Math.cos(pr);
        double up = thrust * Math.cos(rr) * Math.cos(pr) - G;
        double an = forward * Math.cos(hr) - right * Math.sin(hr);
        double ae = forward * Math.sin(hr) + right * Math.cos(hr);
        vn += (an - vn * DRAG_H) * dt;
        ve += (ae - ve * DRAG_H) * dt;
        vz += (up - vz * DRAG_V) * dt;
        double oldN = north, oldE = east;
        north += vn * dt;
        east += ve * dt;
        alt += vz * dt;
        if (alt <= 0) {
            double impact = -vz;
            alt = 0;
            if (vz < 0) vz = 0;
            vn = 0; // on the ground: no sliding
            ve = 0;
            north = oldN;
            east = oldE;
            if (armed && impact > CRASH_MS) {
                crashed = true;
                disarm("a crash (" + Math.round(impact) + " m/s)", nowMs);
            } else if (armed && failsafe == Failsafe.LANDING) {
                failsafe = Failsafe.DROPPED;
                disarm("failsafe (landed)", nowMs);
                armSwitchBlocked = true;
            }
        }
        travelled += Math.hypot(north - oldN, east - oldE);
        maxAlt = Math.max(maxAlt, alt);
    }

    private void stepBattery(double dt) {
        amps = armed ? 1.5 + 45 * Math.pow(throttleOut, 1.6) : 0.3;
        mahDrawn += amps * dt / 3.6;
    }

    // ------------------------------------------------------------------ the switches

    private boolean rangeActive(int aux, int start, int end) {
        int ch = 4 + aux;
        if (ch >= rc.length) return false;
        int v = Math.max(900, Math.min(2099, rc[ch]));
        return v >= start && v < end;
    }

    public boolean angleMode() {
        return rangeActive(setup.angleAux, setup.angleStart, setup.angleEnd);
    }

    /** BEEPER mode on (its switch), as Betaflight's mode flags say. */
    public boolean beeperMode() {
        return rangeActive(setup.beeperAux, setup.beeperStart, setup.beeperEnd);
    }

    /**
     * Why the drone beeps now, or null: its BEEPER switch, an RX loss while armed (Betaflight's RX_LOST) or disarmed
     * after it had sticks (2025.12 beeps for that too - what the PC's idle sticks keep away), a failsafe landing.
     */
    public String beeping(long nowMs) {
        if (beeperMode() && nowMs - lastRxAt <= RX_LOSS_MS) return "BEEPER";
        if (failsafe == Failsafe.LANDING) return "RX_LOST_LANDING";
        if (hadRx && nowMs - lastRxAt > 1000) return "RX_LOST";
        return null;
    }

    /**
     * MSP_MODE_RANGES as this Betaflight answers it: 20 slots of [mode id][aux][start step][end step] (a step is 25 us
     * from 900), ARM, ANGLE and BEEPER used, the rest empty - what the phone's idle sticks check reads (ArmRanges).
     */
    public byte[] modeRanges() {
        byte[] p = new byte[20 * 4];
        slot(p, 0, MODE_ARM, setup.armAux, setup.armStart, setup.armEnd);
        slot(p, 1, MODE_ANGLE, setup.angleAux, setup.angleStart, setup.angleEnd);
        slot(p, 2, MODE_BEEPER, setup.beeperAux, setup.beeperStart, setup.beeperEnd);
        return p;
    }

    private static void slot(byte[] p, int i, int mode, int aux, int start, int end) {
        p[i * 4] = (byte) mode;
        p[i * 4 + 1] = (byte) aux;
        p[i * 4 + 2] = (byte) ((start - 900) / 25);
        p[i * 4 + 3] = (byte) ((end - 900) / 25);
    }

    /** Whether a frame could arm by this Betaflight's own ranges - the phone's idle sticks check (shared ArmRanges). */
    public boolean frameMayArm(short[] frame, int count) {
        return ArmRanges.mayArm(frame, count, ArmRanges.parse(modeRanges()));
    }

    // ------------------------------------------------------------------ out

    /** Betaflight's arming disable flags now (FcTelemetry.BETAFLIGHT_BLOCKERS bits). */
    public int blockers(long nowMs) {
        int f = 0;
        boolean rxOk = nowMs - lastRxAt <= RX_LOSS_MS;
        if (!rxOk) f |= FLAG_RXLOSS;
        if (failsafe != Failsafe.NONE && failsafe != Failsafe.HOLD) f |= FLAG_FAILSAFE;
        if (!armed) {
            if (rxOk && rc[3] >= MIN_CHECK) f |= FLAG_THROTTLE;
            if (nowMs - bootAt < BOOT_GRACE_MS) f |= FLAG_BOOTGRACE;
            if (Math.abs(roll) > 25 || Math.abs(pitch) > 25) f |= FLAG_ANGLE;
            if (rxOk && (rxCleanSince < 0 || nowMs - rxCleanSince < ARM_CLEAN_MS)) f |= FLAG_RXLOSS; // not clean long enough yet
            if (armSwitchBlocked) f |= FLAG_ARMSWITCH;
        }
        return f;
    }

    /** MSP_STATUS's mode flags, one bit per BOX_NAMES entry. */
    public int modeFlags(long nowMs) {
        int m = 0;
        if (armed) m |= 1 << BOX_ARM;
        if (angleMode() || failsafe == Failsafe.STAGE1 || failsafe == Failsafe.LANDING) m |= 1 << BOX_ANGLE;
        if (failsafe == Failsafe.STAGE1 || failsafe == Failsafe.LANDING || failsafe == Failsafe.DROPPED && nowMs - failsafeSince < 3000) {
            m |= 1 << BOX_FAILSAFE;
        }
        if (beeperMode()) m |= 1 << BOX_BEEPER;
        return m;
    }

    public boolean isArmed() {
        return armed;
    }

    public Failsafe failsafe() {
        return failsafe;
    }

    /** Why it disarmed last ("" while armed or never). */
    public String lastDisarm() {
        return lastDisarm;
    }

    public int arms() {
        return arms;
    }

    /** ms the sticks have been missing (0 while they come). */
    public long rxMissingMs(long nowMs) {
        long gap = nowMs - lastRxAt;
        return hadRx && gap > RX_LOSS_MS ? gap : 0;
    }

    public boolean hadSticks() {
        return hadRx;
    }

    /** The last sticks the FC took (Betaflight order), a copy. */
    public short[] channels() {
        return rc.clone();
    }

    public double rollDeg() {
        return roll;
    }

    public double pitchDeg() {
        return pitch;
    }

    public double headingDeg() {
        return heading;
    }

    public double altitudeM() {
        return alt;
    }

    public double varioMs() {
        return vz;
    }

    public double groundSpeedMs() {
        return Math.hypot(vn, ve);
    }

    /** The course over the ground, degrees (the heading while standing). */
    public double courseDeg() {
        return groundSpeedMs() < 0.3 ? heading : norm(Math.toDegrees(Math.atan2(ve, vn)));
    }

    public double latDeg() {
        return homeLat + north / 111_320.0;
    }

    public double lonDeg() {
        return homeLon + east / (111_320.0 * Math.cos(Math.toRadians(homeLat)));
    }

    public double travelledM() {
        return travelled;
    }

    public boolean gpsFix(long nowMs) {
        return nowMs - bootAt >= GPS_FIX_AFTER_MS;
    }

    public int satellites(long nowMs) {
        return gpsFix(nowMs) ? 12 + (int) ((nowMs / 7000) % 3) : (int) Math.min(4, (nowMs - bootAt) / 1500);
    }

    /** Metres to home (where it was armed first), -1 before a home was set. */
    public double homeDistanceM() {
        return homeSet ? Math.hypot(north - homeNorth, east - homeEast) : -1;
    }

    /** The direction to home from the drone, degrees. */
    public double homeDirectionDeg() {
        return homeSet ? norm(Math.toDegrees(Math.atan2(homeEast - east, homeNorth - north))) : 0;
    }

    /** The pack's voltage: its cells' rest voltage by the charge left, less the sag under load. */
    public double volts() {
        double soc = Math.max(0, 1 - mahDrawn / setup.capacityMah);
        double cell = 3.3 + 0.75 * soc + 0.15 * Math.pow(soc, 0.25) - 0.05 * (1 - soc) * (1 - soc);
        return Math.max(setup.cells * 3.0, setup.cells * cell - amps * 0.035);
    }

    public double amps() {
        return amps;
    }

    /** The motors' throttle 0..1 (0 disarmed). */
    public double throttleOut() {
        return throttleOut;
    }

    /** It flew into something (the tour's BTR): disarmed where it is, and it falls. */
    public synchronized void crash(String why, long nowMs) {
        if (!armed) return;
        crashed = true;
        vn = 0;
        ve = 0;
        vz = Math.min(vz, 0);
        disarm(why, nowMs);
    }

    /** It disarmed on a hard touchdown. */
    public boolean crashed() {
        return crashed;
    }

    public int mahDrawn() {
        return (int) Math.round(mahDrawn);
    }

    public int cells() {
        return setup.cells;
    }

    public int capacityMah() {
        return setup.capacityMah;
    }

    /**
     * A pack used down to this rest voltage per cell (the tour's drones, back from a flight and out for hours), only on
     * the ground: the charge drawn that leaves it there.
     */
    public synchronized void useTo(double restCellVolts) {
        if (armed) return;
        double lo = 0, hi = setup.capacityMah;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            double soc = Math.max(0, 1 - mid / setup.capacityMah);
            double cell = 3.3 + 0.75 * soc + 0.15 * Math.pow(soc, 0.25) - 0.05 * (1 - soc) * (1 - soc);
            if (cell > restCellVolts) lo = mid;
            else hi = mid;
        }
        mahDrawn = (lo + hi) / 2;
    }

    /** A fresh pack (the panel's NEW BATTERY), only on the ground. */
    public synchronized void newBattery() {
        if (!armed) mahDrawn = 0;
    }

    /** Seconds armed in this flight (0 disarmed). */
    public int armedSeconds(long nowMs) {
        return armed ? (int) ((nowMs - armedAt) / 1000) : 0;
    }

    public int onSeconds(long nowMs) {
        return (int) ((nowMs - bootAt) / 1000);
    }

    /** Puts the drone somewhere else on the ground (the panel's start position), only disarmed. */
    public synchronized boolean moveTo(double northM, double eastM) {
        if (armed) return false;
        north = northM;
        east = eastM;
        alt = 0;
        vn = ve = vz = 0;
        homeSet = false;
        return true;
    }

    public double northM() {
        return north;
    }

    public double eastM() {
        return east;
    }

    /**
     * A recorded flight's state laid over the model (the panel's REPLAY TRACK): position, height, attitude, armed - the
     * telemetry then tells that flight. Sticks are still read, but they fly nothing while a track plays.
     */
    public synchronized void overlay(double lat, double lon, double altM, double rollDeg, double pitchDeg, double headingDeg, boolean isArmed) {
        north = (lat - homeLat) * 111_320.0;
        east = (lon - homeLon) * 111_320.0 * Math.cos(Math.toRadians(homeLat));
        alt = Math.max(0, altM);
        roll = rollDeg;
        pitch = pitchDeg;
        heading = norm(headingDeg);
        vn = ve = vz = 0;
        if (isArmed && !armed) {
            armed = true;
            armedAt = 0;
        } else if (!isArmed && armed) {
            armed = false;
        }
    }

    static double norm(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    private static double clampAngle(double a) {
        a = ((a + 180) % 360 + 360) % 360 - 180;
        return a;
    }
}
