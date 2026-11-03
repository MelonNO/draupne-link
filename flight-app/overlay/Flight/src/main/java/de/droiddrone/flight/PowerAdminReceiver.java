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

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import static de.droiddrone.common.Logcat.log;

/**
 * Device admin with one policy, force-lock (res/xml/device_admin.xml): deep sleep can turn the screen off itself
 * (DevicePolicyManager.lockNow) instead of waiting for the screen timeout. The pilot activates it from the status
 * card or the phone's DEEP SLEEP button; without it deep sleep still works, the screen just times out on its own.
 */
public class PowerAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        log("Device admin (screen lock) activated");
    }

    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        log("Device admin (screen lock) deactivated");
    }
}
