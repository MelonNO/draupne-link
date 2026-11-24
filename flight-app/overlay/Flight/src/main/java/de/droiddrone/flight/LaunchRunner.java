/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.launch.ClimbWatchdog;
import de.droiddrone.common.launch.LaunchIntent;
import de.droiddrone.common.launch.LaunchMark;
import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;
import de.droiddrone.common.launch.PreArmCheck;

import java.util.List;

/**
 * DRAUPNE Flight (fleet PRD area F, AL-1 - AL-19): the safe autolaunch on the reserve's own phone - no 4G round trip
 * between a sign of trouble and the motors stopping. The mark (SAFE TO AUTOLAUNCH, kept across the app's starts) and
 * the launch order come as fleet commands; {@link LaunchSequence} (the shared rules: only a marked drone, only on the
 * pilot's call, ever arms by itself) decides at 10 Hz from the flight controller's attitude, GPS, battery, health,
 * climb and throttle and the phone's own accelerometer and gyro (50 Hz while a launch runs); what it decides is done
 * here - the 2 s warning on the phone's speaker, GUIDED, arm, take-off, motors off at once - and reported to the
 * commander. Off the stick path: a reserve has no pilot.
 */
final class LaunchRunner {
    static final String PREFS = "fleetLaunch";

    private final PreArmCheck check = new PreArmCheck(0);
    private final LaunchSequence seq = new LaunchSequence(check);
    private final PreArmCheck.Reading reading = new PreArmCheck.Reading();
    private final ClimbWatchdog.Sample sample = new ClimbWatchdog.Sample();
    private volatile double accelPeakG = Double.NaN, gyroPeakDps = Double.NaN;
    private boolean sensorsOn;
    private android.media.ToneGenerator tone;
    private Context context;
    private boolean markLoaded;

    private final SensorEventListener motion = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            double mag = Math.sqrt(x * x + y * y + z * z);
            long now = android.os.SystemClock.elapsedRealtime();
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                double g = mag / SensorManager.GRAVITY_EARTH;
                accelPeakG = Double.isNaN(accelPeakG) ? g : Math.max(accelPeakG, g);
                synchronized (seqLock) {
                    check.motion(now, g, Double.NaN);
                }
            } else {
                double dps = Math.toDegrees(mag);
                gyroPeakDps = Double.isNaN(gyroPeakDps) ? dps : Math.max(gyroPeakDps, dps);
                synchronized (seqLock) {
                    check.motion(now, Double.NaN, dps);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };
    private final Object seqLock = new Object();

    /** The mark kept on the phone, read once at the first command (the app may have started in between). */
    private void loadMark(Context c) {
        if (markLoaded || c == null) return;
        markLoaded = true;
        context = c;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int id = p.getInt("id", 0);
        if (id == 0) return;
        LaunchMark m = new LaunchMark(id, Double.longBitsToDouble(p.getLong("lat", 0)), Double.longBitsToDouble(p.getLong("lon", 0)),
                Double.longBitsToDouble(p.getLong("heading", 0)), p.getLong("at", 0), p.getInt("floorDm", 400) / 10.0);
        synchronized (seqLock) {
            seq.setMark(m);
        }
        log("SAFE TO AUTOLAUNCH mark kept: " + id);
    }

    private void saveMark(LaunchMark m) {
        Context c = context;
        if (c == null) return;
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (m == null) {
            e.clear();
        } else {
            e.putInt("id", m.id).putLong("lat", Double.doubleToLongBits(m.lat)).putLong("lon", Double.doubleToLongBits(m.lon))
                    .putLong("heading", Double.doubleToLongBits(m.headingDeg)).putLong("at", m.markedAtMs).putInt("floorDm", (int) Math.round(m.floorM * 10));
        }
        e.apply();
    }

    /** MARK [id i32][floor dm u16]: marked here and now (id 0 clears). Only a landed, disarmed drone with a 3D fix. */
    void mark(Udp udp, Mavlink m, byte[] d, int at, int len) {
        loadMark(udp.context());
        if (len < 7) return;
        int id = (d[at + 1] & 0xff) << 24 | (d[at + 2] & 0xff) << 16 | (d[at + 3] & 0xff) << 8 | (d[at + 4] & 0xff);
        double floor = ((d[at + 5] & 0xff) << 8 | (d[at + 6] & 0xff)) / 10.0;
        long now = android.os.SystemClock.elapsedRealtime();
        if (id == 0) {
            synchronized (seqLock) {
                seq.setMark(null);
            }
            saveMark(null);
            log("SAFE TO AUTOLAUNCH mark cleared by the PC");
            return;
        }
        if (m.armedNow(now) || !m.gnssOk() || !m.isArduPilot()) {
            log("SAFE TO AUTOLAUNCH refused: " + (m.armedNow(now) ? "armed" : !m.gnssOk() ? "no 3D fix" : "not an ArduPilot FC"));
            return;
        }
        LaunchMark mark = new LaunchMark(id, m.latNow(), m.lonNow(), m.headingDeg(), System.currentTimeMillis(), floor);
        synchronized (seqLock) {
            seq.setMark(mark);
        }
        saveMark(mark);
        log("SAFE TO AUTOLAUNCH marked: " + id + ", floor " + Math.round(floor) + " m");
    }

    /** LAUNCH: the pilot's call, decoded; the sequence takes it only with its mark (AL-12). */
    void order(Udp udp, Mavlink m, byte[] d, int at, int len) {
        loadMark(udp.context());
        LaunchWire.Order o = LaunchWire.decodeOrder(d, at + 1, len - 1);
        if (o == null) return;
        List<LaunchIntent> todo;
        synchronized (seqLock) {
            todo = seq.order(o, android.os.SystemClock.elapsedRealtime());
        }
        act(udp, m, todo);
    }

    /** OUT OF ACTION marked on the PC: the mark goes, a failed launch's silence ends (AL-18). */
    void outOfAction(Udp udp, Mavlink m) {
        List<LaunchIntent> todo;
        synchronized (seqLock) {
            todo = seq.outOfAction(android.os.SystemClock.elapsedRealtime());
        }
        act(udp, m, todo);
    }

    /**
     * A launch is under way - from the call until the drone is in the group (or the launch has ended): the lead / solo
     * link-loss rule stands aside meanwhile (a reserve has no pilot session; an old session's silence must not brake it).
     */
    /**
     * The group's order to this reserve (the receive thread): at the group and in transit, IN GROUP once it is at its
     * order's place (LaunchSequence.followerOrder) - the launch's states move on and its link-loss rule stands aside.
     */
    void followerOrder(Udp udp, Mavlink m, de.droiddrone.common.group.GroupOrder o) {
        long now = android.os.SystemClock.elapsedRealtime();
        double[] p = de.droiddrone.common.group.FollowerRule.place(o, 0);
        double lat = m.latNow(), lon = m.lonNow(), alt = m.altAmslNow();
        double d = Double.isNaN(lat) || Double.isNaN(lon) ? Double.NaN : de.droiddrone.common.group.GeoLocal.distance(p[0], p[1], lat, lon);
        List<LaunchIntent> todo;
        synchronized (seqLock) {
            todo = seq.followerOrder(now, d, alt - p[2]);
        }
        act(udp, m, todo);
    }

    /** The launch's state now (LaunchSequence's). */
    int state() {
        synchronized (seqLock) {
            return seq.state();
        }
    }

    boolean underWay() {
        synchronized (seqLock) {
            int s = seq.state();
            return s != LaunchSequence.IDLE && !seq.finished();
        }
    }

    /** The phone's 10 Hz ArduPilot thread. */
    void tick(Udp udp, Mavlink m, long now) {
        int state;
        boolean finished;
        synchronized (seqLock) { // both under the lock: an order on the receive thread changes them together
            state = seq.state();
            finished = seq.finished();
        }
        boolean running = state != LaunchSequence.IDLE && !finished;
        sensors(udp.context(), running);
        if (!running) return;
        reading.rollDeg = m.rollDeg();
        reading.pitchDeg = m.pitchDeg();
        reading.headingDeg = m.headingDeg();
        reading.lat = m.latNow();
        reading.lon = m.lonNow();
        reading.gpsFix3d = m.gnssOk();
        double b = m.batteryNow();
        reading.batteryPercent = Double.isNaN(b) ? -1 : (int) Math.round(b);
        reading.imuHealthy = m.imuHealthy();
        reading.compassHealthy = m.compassHealthy();
        reading.fcHealthy = m.isArduPilot() && m.preArmOk(); // ArduPilot's own pre-arm checks too
        long age = m.fcDataAgeMs(now); // an FC rebooting mid-launch sends nothing: its last words are no reading
        reading.fcAgeMs = age > Integer.MAX_VALUE ? -1 : age;
        ClimbWatchdog.Sample s = null;
        if (state == LaunchSequence.CLIMBING) {
            s = sample;
            s.atMs = now;
            s.tiltDeg = Math.max(Math.abs(m.rollDeg()), Math.abs(m.pitchDeg()));
            s.accelG = accelPeakG;
            s.climbMps = m.velUp();
            s.throttlePercent = m.throttlePercent();
            LaunchMark mk = seq.mark();
            s.driftM = mk == null ? Double.NaN : de.droiddrone.common.group.GeoLocal.distance(mk.lat, mk.lon, m.latNow(), m.lonNow());
            s.heightM = m.heightAboveTakeoffM();
            s.rpmRatio = Double.NaN;
            accelPeakG = Double.NaN;
        }
        List<LaunchIntent> todo;
        synchronized (seqLock) {
            seq.fcReady(m.isArduPilot());
            todo = seq.tick(now, reading, m.armedNow(now), m.armedKnown(now), s); // a heartbeat gap is no disarm in the climb
        }
        act(udp, m, todo);
    }

    private void act(Udp udp, Mavlink m, List<LaunchIntent> todo) {
        for (LaunchIntent i : todo) {
            switch (i.type) {
                case LaunchIntent.BEEP:
                    beep(i.ms);
                    break;
                case LaunchIntent.SET_MODE_GUIDED:
                    m.setMode(Mavlink.COPTER_GUIDED);
                    break;
                case LaunchIntent.ARM:
                    log("AUTOLAUNCH: arming");
                    m.arm();
                    break;
                case LaunchIntent.TAKEOFF:
                    m.takeoff(i.heightM);
                    break;
                case LaunchIntent.MOTORS_OFF:
                    log("AUTOLAUNCH: MOTORS OFF");
                    m.forceDisarm();
                    break;
                case LaunchIntent.CLEAR_MARK:
                    saveMark(null);
                    log("SAFE TO AUTOLAUNCH mark cleared (" + i.why + ")");
                    break;
                case LaunchIntent.REPORT: {
                    byte[] st = LaunchWire.encode(i.status);
                    byte[] p = new byte[1 + st.length];
                    p[0] = FleetCmd.LAUNCH_STATUS;
                    System.arraycopy(st, 0, p, 1, st.length);
                    udp.sendToCommander(p);
                    break;
                }
                default: // the beeper's rules: an ArduPilot reserve has no lost-receiver beeper of DRAUPNE's
                    break;
            }
        }
    }

    private void beep(long ms) {
        try {
            if (tone == null) tone = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100);
            if (ms <= 0) tone.stopTone();
            else tone.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, (int) Math.min(ms, 10_000));
        } catch (RuntimeException e) {
            log("AUTOLAUNCH: no warning tone: " + e);
        }
    }

    private void sensors(Context c, boolean on) {
        if (c == null || on == sensorsOn) return;
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        sensorsOn = on;
        if (on) {
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (a != null) sm.registerListener(motion, a, 20_000);
            if (g != null) sm.registerListener(motion, g, 20_000);
        } else {
            sm.unregisterListener(motion);
        }
    }
}
