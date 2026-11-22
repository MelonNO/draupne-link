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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import de.droiddrone.common.CrashRestarts;

import static de.droiddrone.common.Logcat.log;

/**
 * pc.55: the app opens itself again after an error ended it. Seen in the bench's night of 2026-11-20: one exception
 * on the camera's thread, Android ended the whole app and planned the service's restart for 77 minutes later - a
 * restart that then finds no settings and stops. Nothing else brings the link back; no pilot can open the app on a
 * drone in the air or in a field.
 * <p>
 * An error no thread caught: said in the log, kept for the next start, and an alarm set for a moment later
 * ({@link PowerAlarmReceiver}, in the new process) - then the app ends itself at once (F-076: Android's own handling
 * may wait for a tap on "has stopped" first); an app that was not at work gets Android's own handling as before. The
 * alarm opens MainActivity the way a wake from deep sleep does; opened, it starts the service and listens for the
 * PC (pc.28). The same when the sleep check's alarm rings and finds no app: it was ended while asleep (a phone
 * maker's battery saver does that). Not for ever: {@link CrashRestarts}.
 * <p>
 * The phone comes back awake and listening whatever it was doing - asleep before, it is awake now: a drone in reach
 * with its battery draining is found; one asleep for good is not.
 */
final class CrashGuard {
    static final String ACTION_REOPEN = "de.droiddrone.flight.pc.REOPEN";
    private static final String STORE = "crashGuard", RESTARTS = "restarts", LAST = "last", LAST_AT = "lastAt";
    private static volatile boolean installed;

    private CrashGuard() {
    }

    /** Once per process, from whatever starts first (the activity, the service). */
    static void install(Context context) {
        if (installed) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            boolean atWork = false;
            try {
                log("FATAL on thread " + thread.getName() + ": " + error);
                // only a phone that was at work: the app open on a bench with nothing running stays closed
                atWork = DDService.isRunning;
                if (atWork) planReopen(app, thread.getName() + ": " + error);
            } catch (Throwable ignored) {
                // nothing here may stand in the way of the app's end
            }
            if (atWork || before == null) {
                endNow(app, thread, error);
            } else {
                before.uncaughtException(thread, error);
            }
        });
    }

    /**
     * F-076 (the bench's Android 8 emulator, 2026-11-22): Android's own handling of an error shows "DRAUPNE Flight has
     * stopped" and ends the app only when that is answered - on Android 8 and 9 at every error, on newer ones after
     * errors that come close together ("keeps stopping"). Until then the app was half alive: its window and main thread
     * gone, its camera, video and link still running - the PC saw a link and nothing wrong, and the alarm that opens
     * the app again rang for a process that could not take it. Nobody answers that on a drone. So an app at work ends
     * itself at once, as Android's handler does after the question: the same two lines in the log under Android's own
     * name (what the bench and a reader of the phone's log look for), then the process ended - the alarm finds none
     * and opens a new one.
     */
    private static void endNow(Context app, Thread thread, Throwable error) {
        try {
            android.util.Log.e("AndroidRuntime", "FATAL EXCEPTION: " + thread.getName() + "\nProcess: " + app.getPackageName() + ", PID: "
                    + android.os.Process.myPid(), error);
        } catch (Throwable ignored) {
            // the end comes all the same
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }

    /** An alarm for {@link CrashRestarts#DELAY_MS} from now, unless the app has opened itself too often. */
    static boolean planReopen(Context app, String why) {
        SharedPreferences store = app.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String next = CrashRestarts.next(store.getString(RESTARTS, ""), now);
        SharedPreferences.Editor e = store.edit().putString(LAST, why).putLong(LAST_AT, now);
        if (next != null) e.putString(RESTARTS, next);
        e.commit(); // not apply(): the process may be gone before it is written
        if (next == null) {
            log("Not opened again: " + CrashRestarts.MAX + " times in " + CrashRestarts.WINDOW_MS / 60_000 + " min already");
            return false;
        }
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        Intent intent = new Intent(app, PowerAlarmReceiver.class).setAction(ACTION_REOPEN);
        PendingIntent pi = PendingIntent.getBroadcast(app, 3, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long at = SystemClock.elapsedRealtime() + CrashRestarts.DELAY_MS;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (SecurityException noExact) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
        log("Opening again in " + CrashRestarts.DELAY_MS + " ms");
        return true;
    }

    /**
     * The alarm rang, in a new process: the app in front again; done is run when all is asked for.
     * <p>
     * Android lets an app that is not on the screen put itself there in one way only: a notification's full-screen
     * intent, and that opens the app only while the screen is off or locked - on a screen that is on and unlocked it
     * is a notification to tap (seen in the emulator, 2026-11-20: "Background activity launch blocked"). So a screen
     * that is on is locked first, when the phone allows the app that (the screen lock for deep sleep, on the status
     * card): then it goes exactly as a wake from deep sleep does. Without that leave, and with the screen on, there
     * is only the notification - said in the log.
     */
    static void reopen(Context context, Runnable done) {
        SharedPreferences store = context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
        log("Opening again after the app was ended (" + store.getString(LAST, "?") + ")");
        bringToFront(context, R.string.dd_reopened_text, done);
    }

    /**
     * The app in front from the background - after an error ended it, and after an update the PC asked for
     * (AppReplacedReceiver; pc.55: that one posted its notification over a lit screen, where it is only a notification -
     * the updated app stayed closed and the drone out of reach). A lit, unlocked screen is locked first where the app
     * may lock it; then the full-screen notification opens it. F-083: and nothing lights the screen before Android has
     * looked at the notification - the app's own wake lock did, and on a phone with no lock screen the lit screen was
     * an unlocked one: a banner (WakeNotice). done runs once the notice's check is over, about 2 s after it.
     */
    static void bringToFront(Context context, int text, Runnable done) {
        boolean lockedNow = false;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            android.app.KeyguardManager guard = (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            boolean inUse = pm != null && pm.isInteractive() && guard != null && !guard.isKeyguardLocked();
            if (inUse && PowerMode.isDeviceAdmin(context)) {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                dpm.lockNow();
                lockedNow = true;
                log("Opening again: the screen was on - locked first, as before a deep sleep");
            } else if (inUse) {
                log("Opening again: the screen is on and the app may not lock it (the status card's line): a notification to tap, no more");
            }
        } catch (RuntimeException e) {
            log("Opening again: the screen's state: " + e);
        }
        if (!lockedNow) {
            AppReplacedReceiver.openAgain(context, text, done); // done runs after its check (F-083: WakeNotice)
            return;
        }
        // the lock takes a moment (about a second in the emulator): asked for too early, the full-screen intent
        // still meets a lit, unlocked screen and stays a notification
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final long giveUpAt = SystemClock.elapsedRealtime() + 4000;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean dark;
                try {
                    android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    android.app.KeyguardManager guard = (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    dark = pm == null || !pm.isInteractive() || guard != null && guard.isKeyguardLocked();
                } catch (RuntimeException e) {
                    dark = true;
                }
                if (!dark && SystemClock.elapsedRealtime() < giveUpAt) {
                    main.postDelayed(this, 150);
                    return;
                }
                AppReplacedReceiver.openAgain(context, text, done); // runs done in every case, after its check
            }
        }, 300);
    }

    /**
     * Android started the app's service again in a process of its own, without the app's window (so without its
     * settings). After an error that ended the app it does that as well - the app is then opening already, and a
     * second count of the same end used up its restarts twice as fast (seen in the emulator, 2026-11-20: two of three).
     * Only when nothing ended the app in the last half minute is this asked as an opening of its own.
     */
    static void serviceWithoutApp(Context app) {
        SharedPreferences store = app.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        long ago = System.currentTimeMillis() - store.getLong(LAST_AT, 0);
        if (ago >= 0 && ago < 30_000) {
            log("Its service was started again by Android: the app is opening already (" + store.getString(LAST, "?") + ")");
            return;
        }
        planReopen(app, "its service was started again by Android, without the app");
    }

    /** The sleep check's alarm rang and no service is there: the app was ended while asleep. */
    static void goneAtTheAlarm(Context context) {
        if (MainActivity.config != null) {
            // The alarm started a new process for the one that was ended, and Android has put the app's window
            // back with it (it does, for a window that was in front): opened, the app starts its service and
            // listens. Nothing more to ask for
            log("Sleep check alarm: the app was ended while asleep and is open again: it listens");
            return;
        }
        if (planReopen(context.getApplicationContext(), "ended while asleep")) return;
        log("Sleep check alarm: the service isn't running, nothing to wake");
    }

    /** What ended the app last and when (ms, wall clock), "" and 0 when nothing did: for the log at the next start. */
    static String last(Context context) {
        SharedPreferences store = context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
        long at = store.getLong(LAST_AT, 0);
        return at == 0 ? "" : store.getString(LAST, "") + " (" + (System.currentTimeMillis() - at) / 1000 + " s ago)";
    }
}
