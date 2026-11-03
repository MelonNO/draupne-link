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
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's own position, for DRAUPNE Link's map: where the drone is when its flight controller (and so its GPS)
 * is gone, as long as the phone lives and has coverage. From every provider the phone has, once a second: the GPS,
 * the network's (Wi-Fi and cell: what Google Maps shows indoors, where the bare GPS never gets a fix) and, on
 * Android 12 and up, the fused one. A GPS fix from the last few seconds wins; otherwise the newest there is, with its
 * accuracy sent along. Nothing without the location permission, which the service runs without. Sent by {@link Udp}
 * as {@link PcLinkStats#PHONE_POSITION_CODE}.
 */
final class PhoneLocation {
    /** A GPS fix this fresh beats a network position however new. */
    private static final long GPS_WINS_MS = 5000;

    /** How often a position is asked for: every second, or every 30 s in standby (pc.21), where nobody flies on it. */
    static final long NORMAL_INTERVAL_MS = 1000;
    static final long STANDBY_INTERVAL_MS = 30000;

    private static LocationManager manager;
    private static final List<LocationListener> listeners = new ArrayList<>();
    private static volatile Location gps, other;
    private static Context appContext;
    private static long intervalMs = NORMAL_INTERVAL_MS;

    private PhoneLocation() {
    }

    static synchronized void start(Context context) {
        start(context, intervalMs);
    }

    /** The rate from now on; takes effect at once when running, and at the next start when not. */
    static synchronized void setInterval(long ms) {
        if (ms == intervalMs) return;
        intervalMs = ms;
        if (manager == null || appContext == null) return;
        stop();
        start(appContext, ms);
    }

    private static synchronized void start(Context context, long interval) {
        if (manager != null) return;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        appContext = context.getApplicationContext();
        intervalMs = interval;
        LocationManager m = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (m == null) return;
        List<String> providers = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) providers.add(LocationManager.FUSED_PROVIDER);
        providers.add(LocationManager.GPS_PROVIDER);
        providers.add(LocationManager.NETWORK_PROVIDER);
        for (String provider : providers) {
            LocationListener l = new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location location) {
                    take(location);
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
                m.requestLocationUpdates(provider, interval, 0, l, Looper.getMainLooper());
                take(m.getLastKnownLocation(provider));
                listeners.add(l);
            } catch (SecurityException | IllegalArgumentException e) {
                // this phone hasn't got that provider, or the permission went
            }
        }
        if (listeners.isEmpty()) return;
        manager = m;
    }

    static synchronized void stop() {
        if (manager != null) for (LocationListener l : listeners) manager.removeUpdates(l);
        listeners.clear();
        manager = null;
    }

    private static void take(Location l) {
        if (l == null) return;
        if (LocationManager.GPS_PROVIDER.equals(l.getProvider())) gps = l;
        else other = l;
    }

    /** Milliseconds since the position was measured. */
    static long ageMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
    }

    /** The newest position worth sending, or null: a fresh GPS fix, else whichever is newest. */
    static Location last() {
        Location g = gps, o = other;
        if (g == null) return o;
        if (o == null || ageMs(g) <= GPS_WINS_MS || ageMs(g) <= ageMs(o)) return g;
        return o;
    }

    /** Whether the GPS is being asked at all (the permission was there when the service started). */
    static boolean isRunning() {
        return manager != null;
    }
}
