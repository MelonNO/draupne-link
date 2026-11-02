/*
 *  This file is part of DRAUPNE Link's build of DRAUPNE Flight.
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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.NonNull;

/**
 * The phone's own GPS, for DRAUPNE Link's map: where the drone is when its flight controller (and so its GPS) is
 * gone, as long as the phone lives and has coverage. Once a second from the GPS; nothing without the location
 * permission, which the service runs without. Sent by {@link Udp} as {@link PcLinkStats#PHONE_POSITION_CODE}.
 */
final class PhoneLocation {
    private static LocationManager manager;
    private static LocationListener listener;
    private static volatile Location last;

    private PhoneLocation() {
    }

    static synchronized void start(Context context) {
        if (manager != null) return;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationManager m = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (m == null) return;
        LocationListener l = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                last = location;
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
            }
        };
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, l, Looper.getMainLooper());
            last = m.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException | IllegalArgumentException e) {
            return; // no GPS on this phone, or the permission went
        }
        manager = m;
        listener = l;
    }

    static synchronized void stop() {
        if (manager != null && listener != null) manager.removeUpdates(listener);
        manager = null;
        listener = null;
    }

    /** The newest position, or null. */
    static Location last() {
        return last;
    }
}
