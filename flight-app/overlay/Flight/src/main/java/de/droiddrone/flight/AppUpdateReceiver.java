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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import static de.droiddrone.common.Logcat.log;

/** Android's installer answers here (see {@link AppUpdater}): show its prompt, or say why it didn't install. */
public class AppUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            if (DDService.isRunning) {
                // connected since the tap: an update now would end the app and the sticks with it
                log("Update not offered: connected.");
                Toast.makeText(context, R.string.dd_update_connected, Toast.LENGTH_LONG).show();
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm); // Android's own "update this app?" prompt
            } catch (Exception e) {
                log("Cannot show the install prompt: " + e);
            }
            return;
        }
        AppUpdater.cleanUp(context);
        if (status != PackageInstaller.STATUS_SUCCESS) {
            String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            log("Update not installed: " + status + " " + why);
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(context, context.getString(R.string.dd_update_failed, why == null ? "" : why), Toast.LENGTH_LONG).show();
            }
        }
    }
}
