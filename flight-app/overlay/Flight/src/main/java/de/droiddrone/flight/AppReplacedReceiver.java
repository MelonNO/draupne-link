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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import static de.droiddrone.common.Logcat.log;

/**
 * pc.32: this app was just updated. After a remote update ({@link RemoteUpdate}) it opens again by itself - the same
 * full-screen notification as a wake from deep sleep ({@link WakeNotice}), the sanctioned way to start an activity from
 * the background - and listens for the PC, which reconnects. After an update the pilot started on the phone, nothing.
 */
public class AppReplacedReceiver extends BroadcastReceiver {
    private static final int NOTIFICATION_ID = 7702;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        AppUpdater.cleanUp(context);
        if (!RemoteUpdate.takePending(context)) return;
        log("Updated by the PC: opening again");
        final PendingResult pending = goAsync(); // it may wait a moment for the screen's lock
        CrashGuard.bringToFront(context, R.string.dd_updated_text, pending::finish);
    }

    /** The app is open: the notification that opened it (or asked to be tapped) has done its work. */
    static void opened(Context context) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The app in front again from the background: after an update by the PC, and after an error ended it (pc.55,
     * CrashGuard). F-083: the notice meets the screen as it is - nothing lights it before Android has looked; the
     * screen is lit only if the app is not up 2 s later (WakeNotice). done runs after that.
     */
    static void openAgain(Context context, int text, Runnable done) {
        Notification n;
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) {
                WakeNotice.finish(done);
                return;
            }
            manager.createNotificationChannel(new NotificationChannel(DDService.WAKE_CHANNEL_ID,
                    context.getString(R.string.dd_wake_channel), NotificationManager.IMPORTANCE_HIGH)); // kept as it was if it exists
            Intent open = MainActivity.wakeIntent(context); // the app's own WAKE: with its token
            PendingIntent full = PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            n = new NotificationCompat.Builder(context, DDService.WAKE_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(text))
                    .setSmallIcon(R.drawable.baseline_rocket_launch_24)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setContentIntent(full)
                    .setFullScreenIntent(full, true)
                    .setAutoCancel(true)
                    .build();
        } catch (Exception e) {
            log("Updated notification error: " + e);
            WakeNotice.finish(done);
            return;
        }
        // pc.55: the one from last time goes first (WakeNotice cancels it). A notification that is still there is only
        // changed by a new one of its number, and a changed notification opens nothing: the second time the app was to
        // come back by itself it did not (seen in the emulator, 2026-11-20)
        WakeNotice.post(context, NOTIFICATION_ID, n, text == R.string.dd_updated_text ? "updated" : "opening again", done);
    }
}
