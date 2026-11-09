/*
 *  This file is part of DRAUPNE Flight, the phone app of DRAUPNE Link (based on DroidDrone).
 *
 *  It is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 *  version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details. You should have received a copy of the GNU General Public License along with it. If not, see
 *  <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

import de.droiddrone.common.PacketIds;
import de.droiddrone.common.SleepCheck;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The phone's power modes (pc.21): the PC (packet {@link #PACKET}) or the pilot on the phone asks for one, the phone
 * reports the one in force once a second ({@link PcLinkStats#PHONE_POWER_CODE}).
 * <ul>
 * <li>{@link #NORMAL}: everything runs.</li>
 * <li>{@link #STANDBY}, before take-off: no camera and encoder, the GPS every 30 s, the flight controller polled
 * once a second, the screen at its dimmest and allowed to go off; the service, the wake lock and the link stay (the
 * PC's pings keep coming and are answered), so the PC takes the phone back with mode 0 at once.</li>
 * <li>{@link #DEEP_SLEEP}: after a short countdown the phone lets go of everything - the flight controller's port,
 * the GPS, the video, the UDP socket (the link drops; the PC expects that), the wake lock - and locks its screen when
 * it may ({@link PowerAdminReceiver}). An exact alarm ({@link PowerAlarmReceiver}) brings it back every
 * checkIntervalMin minutes for a {@link #CHECK_WINDOW_MS} window with the socket open: a Connect with the right key
 * (the PC wants it back) wakes it fully; nothing, and it sleeps on until the next alarm. The service stays a
 * foreground service throughout, or Android would end it. pc.39: CHECK EVERY "0 min" ({@link SleepCheck#ALWAYS}) is
 * a window that opens {@link SleepCheck#ALWAYS_OPEN_AFTER_MS} after the sleep began and never closes - no alarms, the
 * socket open, a "still here" every minute, the PC's "sleep on" doesn't close it, a Connect wakes the phone at once;
 * the window's wake lock is renewed with every hello.</li>
 * <li>{@link #OVERWATCH} (pc.36): deep sleep with the camera and the motion detection on and the CPU kept up for
 * them (the service's partial wake lock stays); a detection calls the PC ({@link Overwatch}). The checks for the PC
 * go on as in deep sleep. Under 20 % battery or hot it becomes deep sleep by itself ({@link #floorDrop}).</li>
 * </ul>
 * Mode 1, 2 and 3 are refused while the flight controller reports armed, and standby ends by itself when it arms.
 * Everything here runs on one worker thread; the mode and the flags are volatile for the threads that read them
 * (the receive thread drops stick frames in standby, the stats sender reports, the activity draws).
 */
final class PowerMode {
    static final int NORMAL = 0;
    static final int STANDBY = 1;
    static final int DEEP_SLEEP = 2;
    /** pc.36: overwatch sleep (OverwatchProtocol.MODE). */
    static final int OVERWATCH = de.droiddrone.common.OverwatchProtocol.MODE;
    /** PC to phone, unnumbered like StartVideo: [0x6E][mode u8][checkIntervalMin u8]. */
    static final byte PACKET = PacketIds.POWER_MODE;
    /** pc.22: the sleep check's "still here" to the PC, and the PC's answer (0 = sleep on, 1 = wake). */
    static final byte SLEEP_HELLO = PacketIds.SLEEP_HELLO, SLEEP_ANSWER = PacketIds.SLEEP_ANSWER;
    /**
     * The "still here" goes out this soon after the window opens and then this often for the whole window (pc.30:
     * three in the first second often went nowhere - a Tailscale tunnel that slept with the phone drops the first
     * packets after a wake - and the PC heard nothing of that check).
     */
    static final long HELLO_FIRST_MS = 200, HELLO_GAP_MS = 1500;
    static final int DEFAULT_CHECK_MIN = 5;
    /** How long the socket listens for the PC's Connect after an alarm. */
    static final long CHECK_WINDOW_MS = 20_000;
    /**
     * When the mode changes the PHONE_POWER record goes three times, this far apart; the three before deep sleep
     * carry sleepInS 3, 2, 1, and the sleep begins at {@link #SLEEP_COUNTDOWN_MS} - the burst has left by then.
     */
    static final long BURST_GAP_MS = 300;
    static final int BURST_COUNT = 3;
    static final long SLEEP_COUNTDOWN_MS = 1000;
    /** How long a window's wake lock outlasts the time it is asked for. */
    private static final long WINDOW_LOCK_SPARE_MS = 10_000;
    /** How often the check window looks whether the PC has connected. */
    private static final long WINDOW_POLL_MS = 250;
    /** Preferences: the prompts that are made only once (the status card offers them again). */
    static final String PREF_ADMIN_ASKED = "powerAdminAsked";
    static final String PREF_EXACT_ALARM_ASKED = "powerExactAlarmAsked";

    private static volatile PowerMode current;
    /** pc.36: why overwatch last gave way to deep sleep by itself (PowerFloor.REASON_*), for the sleep hello; 0 none. */
    private static volatile int floorReason;
    /** The last thing that happened here, for the status card (the app has no log view). */
    private static volatile String lastEvent = "";

    private final DDService service;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "powerMode");
        t.setDaemon(true);
        return t;
    });
    private volatile int mode = NORMAL;
    private volatile int checkIntervalMin = DEFAULT_CHECK_MIN;
    /** Deep sleep begins then; 0 when not counting down. */
    private volatile long sleepAt;
    /** The socket is closed: between the countdown and a check window, or between windows. */
    private volatile boolean asleep;
    /** A check window is open: the socket listens for the PC. */
    private volatile boolean checking;
    private volatile long nextCheckAt;
    private long windowOpenedAt;
    /** When the last hello of this window went (worker thread). */
    private long lastHelloAt;
    // the burst of records after a change; guarded by this
    private int burstLeft;
    private long nextBurstAt;
    /** The countdown, or the window's poll: whichever is scheduled. Worker thread only. */
    private ScheduledFuture<?> pending;
    /** The hellos through the window; cancelled with the poll. */
    private ScheduledFuture<?> helloPending;
    /** Keeps the CPU up through a check window: the alarm's own grace is only about 10 s. */
    private PowerManager.WakeLock windowLock;

    private PowerMode(DDService service) {
        this.service = service;
    }

    /** The service started: modes can be asked for. */
    static void start(DDService service) {
        PowerMode old = current;
        if (old != null) old.shutdown();
        current = new PowerMode(service);
        lastEvent = "";
    }

    /** The service stops: no alarm is left behind, and the mode is normal again (nobody runs it). */
    static void stop() {
        PowerMode p = current;
        current = null;
        if (p != null) p.shutdown();
    }

    private void shutdown() {
        cancelAlarm(service);
        releaseWindowLock();
        mode = NORMAL;
        asleep = false;
        checking = false;
        sleepAt = 0;
        worker.shutdownNow();
    }

    static int mode() {
        PowerMode p = current;
        return p == null ? NORMAL : p.mode;
    }

    /** Deep sleep or overwatch: the link's socket closed between checks, the checks running (pc.36). */
    static boolean isSleepMode() {
        int m = mode();
        return m == DEEP_SLEEP || m == OVERWATCH;
    }

    private boolean sleeping() {
        return mode == DEEP_SLEEP || mode == OVERWATCH;
    }

    /** pc.39: CHECK EVERY "0 min" - the listening window stays open. */
    private boolean always() {
        return SleepCheck.isAlways(checkIntervalMin);
    }

    /** Asleep and always listening (pc.39): the status card and the notification say so instead of a next check. */
    static boolean isAlwaysListening() {
        PowerMode p = current;
        return p != null && p.sleeping() && p.always();
    }

    static int floorReason() {
        return floorReason;
    }

    /**
     * pc.36: overwatch's floor (PowerFloor) - deep sleep instead, now, without a countdown (everything but the camera
     * is let go of already).
     */
    static void floorDrop(int reason) {
        PowerMode p = current;
        if (p == null) return;
        p.worker.execute(() -> {
            if (p.mode != OVERWATCH) return;
            floorReason = reason;
            if (p.asleep || p.checking) {
                p.mode = DEEP_SLEEP;
                p.service.overwatchToDeepSleep(p.nextCheckAt);
                event("Overwatch ended: " + de.droiddrone.common.PowerFloor.describe(reason));
                p.burst();
            } else {
                p.apply(DEEP_SLEEP, 0, de.droiddrone.common.PowerFloor.describe(reason));
            }
        });
    }

    /** Stick frames are dropped in every mode but normal: the PC sends none, and a stray one must not reach the FC. */
    static boolean dropSticks() {
        return mode() != NORMAL;
    }

    /** The socket is closed (deep sleep between checks): the service's timer has nothing to do. */
    static boolean isAsleep() {
        PowerMode p = current;
        return p != null && p.asleep;
    }

    static boolean isChecking() {
        PowerMode p = current;
        return p != null && p.checking;
    }

    static int checkIntervalMin() {
        PowerMode p = current;
        return p == null ? DEFAULT_CHECK_MIN : p.checkIntervalMin;
    }

    /** When the next check window opens (deep sleep), or 0. */
    static long nextCheckAt() {
        PowerMode p = current;
        return p == null || !p.asleep ? 0 : p.nextCheckAt;
    }

    static String lastEvent() {
        return lastEvent;
    }

    /**
     * Asks for a mode: the PC's packet, the phone's own buttons, the app being opened while asleep. intervalMin 0
     * keeps the interval there is. Refused (and said so) while the flight controller is armed.
     */
    static void request(int mode, int intervalMin, String from) {
        PowerMode p = current;
        if (p == null) {
            log("Power mode " + name(mode) + " asked for (" + from + ") while the service isn't running: ignored");
            return;
        }
        if (mode < NORMAL || mode > OVERWATCH) {
            log("Power mode " + mode + " asked for (" + from + "): no such mode");
            return;
        }
        p.worker.execute(() -> p.apply(mode, intervalMin, from));
    }

    /**
     * The service's timer, once a second while the flight controller reports armed: standby ends by itself, and so
     * does a deep sleep's countdown (pc.31; it went on to close the FC's port).
     */
    static void fcArmed() {
        PowerMode p = current;
        if (p == null || p.mode == NORMAL) return;
        p.worker.execute(() -> {
            if (p.mode == STANDBY || p.sleeping() && p.sleepAt != 0 && !p.asleep && !p.checking) {
                p.apply(NORMAL, 0, "FC armed");
            }
        });
    }

    /** The alarm fired ({@link PowerAlarmReceiver}): a check window, if still asleep. */
    static void alarmFired(Context context) {
        PowerMode p = current;
        if (p == null) {
            log("Sleep check alarm: the service isn't running, nothing to wake");
            return;
        }
        // the lock now, on the receiver's thread: the system's own grace ends with onReceive
        p.acquireWindowLock(context);
        p.worker.execute(p::openWindow);
    }

    /** Whether a record of the burst after a mode change is due now (the stats sender asks every 0.1 s). */
    static boolean burstDue(long now) {
        PowerMode p = current;
        if (p == null) return false;
        synchronized (p) {
            if (p.burstLeft <= 0 || now < p.nextBurstAt) return false;
            p.burstLeft--;
            p.nextBurstAt = now + BURST_GAP_MS;
            return true;
        }
    }

    /** The PHONE_POWER record, last in its packet. */
    static void writeRecordTo(DataOutputStream out) throws IOException {
        PowerMode p = current;
        if (p == null) {
            PcLinkStats.writePhonePowerTo(out, NORMAL, DEFAULT_CHECK_MIN, 0, 0);
            return;
        }
        int sleepInS;
        synchronized (p) {
            // counting down: the burst's three records say 3, 2, 1
            sleepInS = p.sleepAt == 0 ? 0 : Math.max(1, p.burstLeft + 1);
        }
        PcLinkStats.writePhonePowerTo(out, p.mode, p.checkIntervalMin, sleepInS, caps(p.service));
    }

    private synchronized void burst() {
        burstLeft = BURST_COUNT;
        nextBurstAt = 0;
    }

    // ---- on the worker thread ----

    private void apply(int wanted, int intervalMin, String from) {
        if (intervalMin > 0) checkIntervalMin = Math.min(255, intervalMin);
        if (from.equals("PC")) floorReason = 0; // the PC chose again
        // pc.31: refused unless the FC is known to be disarmed (FcArmedState: unknown counts as armed)
        if (wanted != NORMAL && service.isFcArmed()) {
            if (mode == NORMAL) {
                event(name(wanted) + " refused (" + from + "): FC " + FcArmedState.name(service.fcArmedState()));
                burst(); // the PC asks again until it hears the mode back: mode 0 it is
                return;
            }
            wanted = NORMAL; // armed in standby: out of it
            from = "FC armed";
        }
        switch (wanted) {
            case NORMAL:
                wake(from);
                break;
            case STANDBY:
                if (mode == STANDBY) return;
                if (mode == DEEP_SLEEP) wake(from); // a sleeping phone the PC reached: everything back first
                mode = STANDBY;
                service.powerStandby(true);
                event("Standby (" + from + ")");
                burst();
                break;
            case DEEP_SLEEP:
                if (mode == DEEP_SLEEP) return;
                if (mode == OVERWATCH && (asleep || checking)) { // pc.36: from overwatch, nothing to count down
                    mode = DEEP_SLEEP;
                    service.overwatchToDeepSleep(nextCheckAt);
                    event("Deep sleep instead of overwatch (" + from + ")");
                    burst();
                    break;
                }
                mode = DEEP_SLEEP;
                sleepAt = System.currentTimeMillis() + SLEEP_COUNTDOWN_MS;
                event("Deep sleep in " + SLEEP_COUNTDOWN_MS / 1000 + " s (" + from + "), " + checksText());
                burst();
                cancelPending();
                pending = worker.schedule(this::fallAsleep, SLEEP_COUNTDOWN_MS, TimeUnit.MILLISECONDS);
                break;
            case OVERWATCH:
                if (mode == OVERWATCH) return;
                if (mode != NORMAL) wake(from); // from standby or deep sleep: everything back first, then down again
                mode = OVERWATCH;
                sleepAt = System.currentTimeMillis() + SLEEP_COUNTDOWN_MS;
                event("Overwatch in " + SLEEP_COUNTDOWN_MS / 1000 + " s (" + from + "), " + checksText());
                burst();
                cancelPending();
                pending = worker.schedule(this::fallAsleep, SLEEP_COUNTDOWN_MS, TimeUnit.MILLISECONDS);
                break;
        }
    }

    private String checksText() {
        return always() ? "always listening" : "checks every " + checkIntervalMin + " min";
    }

    /** Normal mode from whatever this was: the countdown, standby, sleep between checks or a check window. */
    private void wake(String from) {
        cancelPending();
        boolean wasStandby = mode == STANDBY;
        boolean wasOverwatch = mode == OVERWATCH;
        boolean wasDeep = mode == DEEP_SLEEP || wasOverwatch;
        boolean wasDown = asleep || checking; // things really let go of, not just the countdown
        mode = NORMAL;
        sleepAt = 0;
        if (wasStandby) service.powerStandby(false);
        if (wasOverwatch) Overwatch.end("woken: " + from); // its clip so far kept for the PC, the camera closed
        if (wasDeep) {
            cancelAlarm(service);
            if (asleep) service.powerListen(); // the socket open again
            asleep = false;
            checking = false;
            releaseWindowLock();
        }
        if (wasStandby || wasDeep) {
            service.powerWake(wasDown);
            event("Awake (" + from + ")");
        } else {
            event("Normal mode (" + from + ")");
        }
        burst();
    }

    private void fallAsleep() {
        if (!sleeping()) return;
        // pc.31: asked again at the end of the countdown - it was only checked when deep sleep was asked for, and the
        // flight controller's port closes here
        int armed = service.fcArmedState();
        if (armed != FcArmedState.DISARMED) {
            event("Deep sleep refused: FC " + FcArmedState.name(armed));
            wake("FC " + FcArmedState.name(armed)); // mode 0 goes back to the PC, as a refusal does
            return;
        }
        sleepAt = 0;
        asleep = true;
        if (always()) {
            // pc.39: no alarm - the window opens shortly, on this thread, and stays open; its lock keeps the CPU up
            // from now (the service's goes with the sleep)
            nextCheckAt = System.currentTimeMillis() + SleepCheck.ALWAYS_OPEN_AFTER_MS;
            acquireWindowLock(service, SleepCheck.ALWAYS_OPEN_AFTER_MS + WINDOW_LOCK_SPARE_MS);
            if (mode == OVERWATCH) service.powerOverwatch(nextCheckAt);
            else service.powerSleep(nextCheckAt);
            event((mode == OVERWATCH ? "Overwatch: " : "Deep sleep: ") + "always listening from "
                    + time(service, nextCheckAt));
            cancelPending();
            pending = worker.schedule(this::openWindow, SleepCheck.ALWAYS_OPEN_AFTER_MS, TimeUnit.MILLISECONDS);
            return;
        }
        nextCheckAt = System.currentTimeMillis() + checkIntervalMin * 60_000L;
        scheduleAlarm(service, nextCheckAt); // before the wake lock goes
        if (mode == OVERWATCH) {
            service.powerOverwatch(nextCheckAt);
            event("Overwatch: next check " + time(service, nextCheckAt));
        } else {
            service.powerSleep(nextCheckAt);
            event("Deep sleep: next check " + time(service, nextCheckAt));
        }
    }

    private void openWindow() {
        if (!sleeping() || !asleep || checking) {
            log("Sleep check alarm in mode " + name(mode) + (checking ? " (a window is open)" : "") + ": ignored");
            releaseWindowLock();
            return;
        }
        asleep = false;
        checking = true;
        windowOpenedAt = System.currentTimeMillis();
        if (always()) nextCheckAt = 0; // no next check: this window is it
        service.powerListen();
        event(always() ? "Always listening" : "Sleep check: listening " + CHECK_WINDOW_MS / 1000 + " s");
        cancelPending();
        pending = worker.scheduleAtFixedRate(this::pollWindow, WINDOW_POLL_MS, WINDOW_POLL_MS, TimeUnit.MILLISECONDS);
        // "still here" to the PC, again and again through the window: its answer ends the window early (sleep on)
        // or wakes the phone. Always listening (pc.39): as in a window's first seconds, then once a minute, the
        // window's wake lock renewed with each
        lastHelloAt = 0;
        helloPending = worker.scheduleAtFixedRate(() -> {
            if (!checking) return;
            long now = System.currentTimeMillis();
            if (always()) {
                if (now - windowOpenedAt > SleepCheck.ALWAYS_HELLO_BURST_MS && now - lastHelloAt < SleepCheck.ALWAYS_HELLO_MS) return;
                acquireWindowLock(service, SleepCheck.ALWAYS_HELLO_MS + WINDOW_LOCK_SPARE_MS);
            }
            lastHelloAt = now;
            service.powerHello(checkIntervalMin);
        }, HELLO_FIRST_MS, HELLO_GAP_MS, TimeUnit.MILLISECONDS);
    }

    /** The PC answered the hello: sleep on (the window closes now) or wake. */
    static void sleepAnswer(boolean wake) {
        PowerMode p = current;
        if (p == null) return;
        p.worker.execute(() -> {
            if (!p.sleeping() || !p.checking) return;
            if (wake) p.wake("PC called");
            else if (!p.always()) { // always listening (pc.39): the window stays open whatever the PC says
                p.event("Sleep check: PC says sleep on");
                p.closeWindow();
            }
        });
    }

    private void pollWindow() {
        if (!sleeping() || !checking) {
            cancelPending();
            return;
        }
        if (service.isPcConnected()) {
            wake("PC called");
            return;
        }
        if (!always() && System.currentTimeMillis() - windowOpenedAt >= CHECK_WINDOW_MS) closeWindow();
    }

    private void closeWindow() {
        cancelPending();
        checking = false;
        asleep = true;
        service.powerListenEnd();
        nextCheckAt = System.currentTimeMillis() + checkIntervalMin * 60_000L;
        scheduleAlarm(service, nextCheckAt);
        service.powerSleepOn(nextCheckAt);
        event("Sleep check: no PC; next check " + time(service, nextCheckAt));
        releaseWindowLock(); // in overwatch the service's own lock keeps the CPU up for the camera
    }

    private void cancelPending() {
        if (pending != null) pending.cancel(false);
        pending = null;
        if (helloPending != null) helloPending.cancel(false);
        helloPending = null;
    }

    private void acquireWindowLock(Context context) {
        acquireWindowLock(context, CHECK_WINDOW_MS + WINDOW_LOCK_SPARE_MS); // a window that never closes still lets go
    }

    /**
     * Held for timeoutMs from now. Not reference counted, so asking again while it is held renews the timeout (pc.39:
     * an always-listening window's hellos keep it from lapsing, and a stuck worker still lets it go).
     */
    private synchronized void acquireWindowLock(Context context, long timeoutMs) {
        try {
            if (windowLock == null) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                windowLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DRAUPNE:sleepCheck");
                windowLock.setReferenceCounted(false);
            }
            windowLock.acquire(timeoutMs);
        } catch (Exception e) {
            log("Sleep check wake lock error: " + e);
        }
    }

    private synchronized void releaseWindowLock() {
        try {
            if (windowLock != null && windowLock.isHeld()) windowLock.release();
        } catch (Exception e) {
            log("Sleep check wake lock release error: " + e);
        }
    }

    private static void event(String text) {
        lastEvent = text;
        log("Power: " + text);
    }

    static String name(int mode) {
        switch (mode) {
            case STANDBY:
                return "standby";
            case DEEP_SLEEP:
                return "deep sleep";
            case OVERWATCH:
                return "overwatch";
            default:
                return "normal";
        }
    }

    /** HH:MM as the phone shows times. */
    static String time(Context context, long at) {
        return android.text.format.DateFormat.getTimeFormat(context).format(new Date(at));
    }

    // ---- the alarm ----

    private static PendingIntent alarmIntent(Context context) {
        Intent intent = new Intent(context, PowerAlarmReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * The next check. setExactAndAllowWhileIdle fires in doze too (setAlarmClock would as well, but shows an alarm
     * icon in the status bar and is meant for alarm clocks); without the exact-alarm permission (Android 12 and up,
     * asked for from the activity) the inexact kind, which doze may hold back for minutes.
     */
    private static void scheduleAlarm(Context context, long at) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(context);
        try {
            if (canExactAlarms(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                log("Sleep check alarm set (exact) for " + time(context, at));
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                log("Sleep check alarm set (inexact: exact alarms not allowed) for " + time(context, at));
            }
        } catch (SecurityException e) {
            log("Sleep check alarm error: " + e);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static void cancelAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(context));
    }

    // ---- what the phone may do (the caps byte, and the status card's lines) ----

    static ComponentName adminComponent(Context context) {
        return new ComponentName(context, PowerAdminReceiver.class);
    }

    /** Whether the app is a device admin with force-lock: it can turn the screen off itself in deep sleep. */
    static boolean isDeviceAdmin(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(adminComponent(context));
    }

    /** The system's "activate device admin" screen, with why. */
    static Intent addDeviceAdminIntent(Context context) {
        return new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.dd_power_admin_why));
    }

    /** Whether exact alarms may be set: always before Android 12, the pilot's choice from then on. */
    static boolean canExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /** Whether the app is off the battery optimisation list: in doze it may then use the network during a check. */
    static boolean ignoresBatteryOptimisation(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static int caps(Context context) {
        int caps = 0;
        if (isDeviceAdmin(context)) caps |= PcLinkStats.CAP_DEVICE_ADMIN;
        if (canExactAlarms(context)) caps |= PcLinkStats.CAP_EXACT_ALARMS;
        if (ignoresBatteryOptimisation(context)) caps |= PcLinkStats.CAP_NO_BATTERY_OPTIMISATION;
        // pc.36: overwatch can watch - unless the internal camera refused the motion detection's picture
        Config config = MainActivity.config;
        if (config != null && (config.isUseUsbCamera() || !InternalCamera.analysisRefused(config.getCameraId()))) caps |= PcLinkStats.CAP_OVERWATCH;
        return caps;
    }
}
