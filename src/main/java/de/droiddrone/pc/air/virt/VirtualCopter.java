package de.droiddrone.pc.air.virt;

import de.droiddrone.common.group.FleetIntent;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.air.V2;

/**
 * A kinematic ArduPilot copter for the fleet's virtual flights (no flight controller code, no physics beyond a point
 * mass): it flies what a {@link FleetIntent} asks the way ArduPilot Copter's modes do, within speed, climb, descent
 * and acceleration limits. GUIDED: to the target, braking in time. LOITER: holds the place it had when it got there.
 * ALT_HOLD: holds its height, drifts with the wind (no position hold - what a drone without GNSS does with centred
 * sticks). RTL: climbs to max(its height, home + RTL_ALT) where it is, flies to its rally point at that height, comes
 * down and lands. LAND: straight down (drifting without GNSS). PILOT: the lead, flown to a point the test's pilot
 * gives each tick. Without GNSS every position mode becomes ALT_HOLD (ArduPilot's EKF failsafe would do no better).
 * <p>
 * Positions are metres east / north of the airspace's reference place; u is the height above sea level.
 */
public final class VirtualCopter {
    public enum Mode { GUIDED, LOITER, ALT_HOLD, RTL, LAND, PILOT }

    public final String id;
    public double e, n, u, ve, vn, vu;
    public double homeE, homeN, homeAmsl;
    public double rallyE, rallyN;
    public double battery = 100, drainPerS = 0;
    public boolean gnssOk = true, landed;
    public double maxSpeed = 15, climb = 2.5, descent = 1.5, landSpeed = 1.5, accel = 3;
    /** GUIDED's speed when the intent names none (ArduPilot's WPNAV_SPEED). */
    public double guidedSpeed = 8;

    private Mode mode = Mode.LOITER;
    private double tgtE, tgtN, tgtU, tgtSpeed, tgtVe, tgtVn;
    private double rtlAltRel;
    private int rtlPhase;
    private FleetIntent last = FleetIntent.NOTHING;
    /** The PC's view without GNSS: a dead-reckoning estimate drifting off, and how sure it is. */
    private double biasE, biasN, gnssLostAtS = -1;
    private final double biasDirDeg;

    public VirtualCopter(String id, double e, double n, double u, double groundAmsl, long seed) {
        this.id = id;
        this.e = e;
        this.n = n;
        this.u = u;
        homeE = e;
        homeN = n;
        homeAmsl = groundAmsl;
        rallyE = e;
        rallyN = n;
        landed = u <= groundAmsl + 0.01;
        tgtE = e;
        tgtN = n;
        tgtU = u;
        biasDirDeg = new java.util.Random(seed ^ id.hashCode()).nextDouble() * 360;
    }

    public Mode mode() {
        return mode;
    }

    public V2 pos() {
        return new V2(e, n);
    }

    /** The pilot flies the lead: to this point at this height (each tick), at up to maxSpeed. */
    public void pilot(double toE, double toN, double toU) {
        mode = Mode.PILOT;
        last = FleetIntent.NOTHING;
        tgtE = toE;
        tgtN = toN;
        tgtU = toU;
        tgtVe = 0;
        tgtVn = 0;
        tgtSpeed = maxSpeed;
        if (landed && toU > u + 0.5) landed = false;
    }

    /** Take off straight up to this height (a reserve's launch climb, in its cleared column). */
    public void climbTo(double toU) {
        mode = Mode.GUIDED;
        last = FleetIntent.NOTHING;
        tgtE = e;
        tgtN = n;
        tgtVe = 0;
        tgtVn = 0;
        tgtU = toU;
        tgtSpeed = guidedSpeed;
        landed = false;
    }

    /** What the phone asked; only a change does anything (the phone commands on change, as the real one does). */
    public void apply(FleetIntent i, double refLat, double refLon) {
        if (i == null || i.kind == FleetIntent.NONE || landed) return;
        if (sameAs(i)) return;
        last = i;
        switch (i.kind) {
            case FleetIntent.LOITER:
                mode = Mode.LOITER;
                tgtVe = 0;
                tgtVn = 0;
                tgtE = e + ve * Math.abs(ve) / (2 * accel);
                tgtN = n + vn * Math.abs(vn) / (2 * accel);
                tgtU = u;
                break;
            case FleetIntent.GUIDED:
                mode = Mode.GUIDED;
                tgtE = GeoLocal.east(refLat, refLon, i.lat, i.lon);
                tgtN = GeoLocal.north(refLat, refLon, i.lat, i.lon);
                tgtU = i.altAmsl;
                tgtSpeed = Double.isNaN(i.speedMs) || i.speedMs <= 0 ? guidedSpeed : Math.min(maxSpeed, i.speedMs);
                tgtVe = Double.isNaN(i.velE) ? 0 : i.velE;
                tgtVn = Double.isNaN(i.velN) ? 0 : i.velN;
                break;
            case FleetIntent.ALT_HOLD:
                mode = Mode.ALT_HOLD;
                tgtU = u;
                break;
            case FleetIntent.RTL:
                mode = Mode.RTL;
                rtlAltRel = i.rtlAltRelM;
                rtlPhase = 0;
                break;
            case FleetIntent.LAND:
                mode = Mode.LAND;
                tgtE = e;
                tgtN = n;
                break;
            default:
                break; // SMART_RTL / RESUME: not used by followers
        }
    }

    private boolean sameAs(FleetIntent i) {
        if (i.kind != last.kind) return false;
        if (i.kind != FleetIntent.GUIDED) return i.kind != FleetIntent.RTL || i.rtlAltRelM == last.rtlAltRelM;
        return i.lat == last.lat && i.lon == last.lon && i.altAmsl == last.altAmsl && i.speedMs == last.speedMs
                && Double.compare(i.velE, last.velE) == 0 && Double.compare(i.velN, last.velN) == 0;
    }

    /** The PC's estimate of where it is: the truth with GNSS, a drifting dead-reckoning guess without. */
    public V2 estimate() {
        return gnssOk ? pos() : new V2(e + biasE, n + biasN);
    }

    /** How sure the estimate is, metres (0 with GNSS): grows by 0.5 m/s from 2 m. */
    public double errorM(double nowS) {
        return gnssOk || gnssLostAtS < 0 ? 0 : 2 + 0.5 * (nowS - gnssLostAtS);
    }

    public void setGnss(boolean ok, double nowS) {
        if (ok == gnssOk) return;
        gnssOk = ok;
        gnssLostAtS = ok ? -1 : nowS;
        biasE = 0;
        biasN = 0;
    }

    /** One step of dt seconds in this wind (m/s east / north), over ground at groundAmsl. */
    public void step(double dt, double windE, double windN, double groundAmsl) {
        if (drainPerS > 0 && !landed) battery = Math.max(0, battery - drainPerS * dt);
        if (!gnssOk) {
            biasE += Math.cos(Math.toRadians(biasDirDeg)) * 0.3 * dt;
            biasN += Math.sin(Math.toRadians(biasDirDeg)) * 0.3 * dt;
        }
        if (landed) {
            ve = vn = vu = 0;
            return;
        }
        Mode m = mode;
        if (!gnssOk && (m == Mode.GUIDED || m == Mode.LOITER || m == Mode.RTL)) m = Mode.ALT_HOLD;
        double wantU = tgtU, speed = tgtSpeed;
        boolean drift = false;
        switch (m) {
            case RTL: {
                if (rtlPhase == 0) { // climb where it is first (ArduPilot's RTL, RTL_CONE_SLOPE 0)
                    tgtE = e + ve * Math.abs(ve) / (2 * accel);
                    tgtN = n + vn * Math.abs(vn) / (2 * accel);
                    tgtU = Math.max(u, homeAmsl + rtlAltRel);
                    rtlPhase = 1;
                }
                if (rtlPhase == 1) {
                    if (u >= tgtU - 0.3) rtlPhase = 2;
                    double[] v = horizontal(tgtE - e, tgtN - n, maxSpeed);
                    move(v[0], v[1], tgtU, dt, groundAmsl, false);
                    return;
                }
                if (rtlPhase == 2) {
                    double[] v = horizontal(rallyE - e, rallyN - n, 10);
                    if (Math.hypot(rallyE - e, rallyN - n) < 1 && Math.hypot(ve, vn) < 0.5) rtlPhase = 3;
                    move(v[0], v[1], tgtU, dt, groundAmsl, false);
                    return;
                }
                double[] v = horizontal(rallyE - e, rallyN - n, 2);
                move(v[0], v[1], Double.NEGATIVE_INFINITY, dt, groundAmsl, true);
                return;
            }
            case LAND: {
                double[] v = gnssOk ? horizontal(tgtE - e, tgtN - n, 2) : new double[]{windE, windN};
                move(v[0], v[1], Double.NEGATIVE_INFINITY, dt, groundAmsl, true);
                return;
            }
            case ALT_HOLD:
                drift = true;
                break;
            default:
                break;
        }
        if (drift) {
            move(windE, windN, tgtU, dt, groundAmsl, false);
            return;
        }
        if (m == Mode.GUIDED) { // position + velocity target: the target moves on at its velocity
            tgtE += tgtVe * dt;
            tgtN += tgtVn * dt;
        }
        double ffE = m == Mode.GUIDED ? tgtVe : 0, ffN = m == Mode.GUIDED ? tgtVn : 0;
        double[] v = horizontal(tgtE - e, tgtN - n, m == Mode.LOITER ? maxSpeed : speed);
        double we = ffE + v[0], wn = ffN + v[1], ws = Math.hypot(we, wn);
        if (ws > maxSpeed) {
            we *= maxSpeed / ws;
            wn *= maxSpeed / ws;
        }
        move(we, wn, wantU, dt, groundAmsl, false);
    }

    /** The velocity that gets to (dE, dN) at up to max, braking in time (a P-controller under the braking curve). */
    private double[] horizontal(double dE, double dN, double max) {
        double d = Math.hypot(dE, dN);
        if (d < 1e-6) return new double[]{0, 0};
        double s = Math.min(max, Math.min(Math.sqrt(2 * accel * 0.8 * d), 1.2 * d));
        return new double[]{dE / d * s, dN / d * s};
    }

    private void move(double wantVe, double wantVn, double wantU, double dt, double groundAmsl, boolean landing) {
        double dve = wantVe - ve, dvn = wantVn - vn;
        double dv = Math.hypot(dve, dvn), cap = accel * dt;
        if (dv > cap) {
            dve *= cap / dv;
            dvn *= cap / dv;
        }
        ve += dve;
        vn += dvn;
        double wantVu = landing ? -landSpeed : Math.max(-descent, Math.min(climb, 1.0 * (wantU - u)));
        double dvu = Math.max(-accel * dt, Math.min(accel * dt, wantVu - vu));
        vu += dvu;
        e += ve * dt;
        n += vn * dt;
        u += vu * dt;
        if (u <= groundAmsl) {
            u = groundAmsl;
            if (landing || vu < 0) {
                landed = true;
                ve = vn = vu = 0;
            }
        }
    }
}
