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
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import static de.droiddrone.common.Logcat.log;

/**
 * pc.32: this app was just updated. After a remote update ({@link RemoteUpdate}) it opens again by itself - the same
 * full-screen notification and bright wake lock as a wake from deep sleep, the sanctioned way to start an activity from
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
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            manager.createNotificationChannel(new NotificationChannel(DDService.WAKE_CHANNEL_ID,
                    context.getString(R.string.dd_wake_channel), NotificationManager.IMPORTANCE_HIGH)); // kept as it was if it exists
            Intent open = new Intent(context, MainActivity.class).setAction(MainActivity.ACTION_WAKE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent full = PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new NotificationCompat.Builder(context, DDService.WAKE_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.dd_updated_text))
                    .setSmallIcon(R.drawable.baseline_rocket_launch_24)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setContentIntent(full)
                    .setFullScreenIntent(full, true)
                    .setAutoCancel(true)
                    .build();
            manager.notify(NOTIFICATION_ID, n);
        } catch (Exception e) {
            log("Updated notification error: " + e);
        }
        try {
            PowerManager pm = context.getSystemService(PowerManager.class);
            if (pm != null) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock bright = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "DRAUPNE:updated");
                bright.acquire(5000);
            }
        } catch (Exception e) {
            log("Updated wake lock error: " + e);
        }
    }
}
