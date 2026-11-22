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

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import de.droiddrone.common.ScreenWake;

import static de.droiddrone.common.Logcat.log;

/**
 * F-083: the full-screen notice that puts the app in front from the background - after an error ended it, after it
 * was ended asleep, after an update the PC asked for (AppReplacedReceiver.openAgain), and when a PC wakes it from deep
 * sleep (DDService.wakeScreen). The rule is {@link ScreenWake}'s.
 * <p>
 * Before, the notice and a bright wake lock went out together; the wake lock lit the screen before Android looked at
 * the notice, and on a phone with no lock screen a lit screen is an unlocked one: a banner, the app stayed closed (a
 * real phone, Samsung, Android 15, screen lock None, 2026-11-22). Now the notice is posted with the screen as it is,
 * and only when the app has not come up {@link ScreenWake#LIGHT_AFTER_MS} later is the screen lit (the same wake
 * lock) - over a lock screen with the notice posted once more.
 */
final class WakeNotice {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WakeNotice() {
    }

    /**
     * The notice posted now (one of its number still there is taken away first: a changed notice opens nothing), the
     * screen as it is.
     * @param id   the notice's number
     * @param what for the log: why the app comes to the front
     * @param done run once all is asked for (a receiver's goAsync finish); null for none
     */
    static void post(Context context, int id, Notification n, String what, Runnable done) {
        final Context app = context.getApplicationContext();
        final NotificationManager manager = app.getSystemService(NotificationManager.class);
        final long postedAt;
        try {
            if (manager == null) {
                log("Wake notice (" + what + "): no notification service");
                finish(done);
                return;
            }
            boolean allowed = allowed(manager), important = important(manager, n);
            boolean interactive = interactive(app), lockScreen = lockScreen(app);
            manager.cancel(id);
            manager.notify(id, n);
            postedAt = SystemClock.elapsedRealtime();
            log("Wake notice (" + what + "): " + ScreenWake.said(allowed, important, interactive, lockScreen) + disturb(manager));
        } catch (RuntimeException e) {
            log("Wake notice (" + what + ") error: " + e);
            finish(done);
            return;
        }
        MAIN.postDelayed(() -> afterWait(app, manager, id, n, what, postedAt, done), ScreenWake.LIGHT_AFTER_MS);
    }

    /** {@link ScreenWake#LIGHT_AFTER_MS} after the notice: done if the app came up, else the screen lit (and the notice again over a lock screen). */
    private static void afterWait(Context app, NotificationManager manager, int id, Notification n, String what, long postedAt, Runnable done) {
        try {
            ScreenWake.Step step = ScreenWake.afterWait(cameUp(postedAt), lockScreen(app));
            if (step == ScreenWake.Step.DONE) {
                finish(done);
                return;
            }
            light(app);
            if (step == ScreenWake.Step.LIGHT) {
                log("Wake notice (" + what + "): not open after " + ScreenWake.LIGHT_AFTER_MS + " ms - screen lit, the notice left to tap");
                finish(done);
                return;
            }
            log("Wake notice (" + what + "): not open after " + ScreenWake.LIGHT_AFTER_MS + " ms - screen lit, posted again over the lock screen");
        } catch (RuntimeException e) {
            log("Wake notice (" + what + ") error: " + e);
            finish(done);
            return;
        }
        MAIN.postDelayed(() -> {
            try {
                if (cameUp(postedAt)) return; // up meanwhile: no second notice to linger
                manager.cancel(id);
                manager.notify(id, n);
            } catch (RuntimeException e) {
                log("Wake notice (" + what + ") error: " + e);
            } finally {
                finish(done);
            }
        }, ScreenWake.POST_AGAIN_AFTER_MS);
    }

    private static boolean cameUp(long postedAt) {
        return ScreenWake.cameUp(MainActivity.inFront, MainActivity.cameUpAt, postedAt);
    }

    /** The screen lit by the app: a bright wake lock for 5 s, as a wake from deep sleep had it from the start. */
    private static void light(Context app) {
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            if (pm == null) return;
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock bright = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "DRAUPNE:wakeNotice");
            bright.acquire(5000);
        } catch (RuntimeException e) {
            log("Wake notice: wake lock error: " + e);
        }
    }

    /** Android 14+: whether the app may use full-screen notices at all (the pilot can take it away; Google Play may). */
    private static boolean allowed(NotificationManager manager) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent();
    }

    private static boolean important(NotificationManager manager, Notification n) {
        NotificationChannel channel = manager.getNotificationChannel(n.getChannelId());
        return channel == null || channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    }

    private static boolean interactive(Context app) {
        PowerManager pm = app.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private static boolean lockScreen(Context app) {
        KeyguardManager guard = app.getSystemService(KeyguardManager.class);
        return guard != null && guard.isKeyguardLocked();
    }

    /** Do Not Disturb may hold a full-screen notice back as well: said when it is on. */
    private static String disturb(NotificationManager manager) {
        int filter = manager.getCurrentInterruptionFilter();
        return filter > NotificationManager.INTERRUPTION_FILTER_ALL ? "; Do Not Disturb on (" + filter + ")" : "";
    }

    /** At the app's start: whether Android lets it open itself (Android 14+). */
    static void sayAllowed(Context context) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                log("Full-screen notices: allowed (Android " + Build.VERSION.RELEASE + ")");
            } else if (manager.canUseFullScreenIntent()) {
                log("Full-screen notices: allowed");
            } else {
                log("Full-screen notices: NOT allowed - the app cannot open itself (Settings, Apps, Special access)");
            }
        } catch (RuntimeException e) {
            log("Full-screen notices: " + e);
        }
    }

    static void finish(Runnable done) {
        if (done == null) return;
        try {
            done.run();
        } catch (RuntimeException e) {
            log("Wake notice: " + e);
        }
    }
}
