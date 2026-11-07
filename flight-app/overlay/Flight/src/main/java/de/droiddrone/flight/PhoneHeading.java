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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import de.droiddrone.common.Observe;

/**
 * pc.37: which way the phone's camera looks, from its rotation vector sensor (the phone's compass and gyro fused) -
 * the heading DRAUPNE Link's observation map uses for a drone whose flight controller has no compass. Listened to
 * only while observers watch (a few readings a second; the sensor costs battery). The accuracy is Android's
 * (unreliable .. high: a compass that wants its figure-of-eight) and the sensor's own heading error estimate.
 */
final class PhoneHeading {
    private static SensorEventListener listener;
    private static volatile double azimuthDeg = Double.NaN;
    private static volatile int accuracy = -1;
    private static volatile int errorDeg = -1;
    private static volatile long at;
    /** A reading older than this is no heading. */
    static final long STALE_MS = 3000;
    static volatile boolean frontCamera;

    private PhoneHeading() {
    }

    static synchronized void start(Context context) {
        if (listener != null || context == null) return;
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor rv = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv == null) {
            log("Observation: no rotation vector sensor - the heading comes from the flight controller only");
            return;
        }
        final float[] r = new float[9];
        listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                try {
                    SensorManager.getRotationMatrixFromVector(r, e.values);
                    azimuthDeg = Observe.cameraAzimuthDeg(r, frontCamera);
                    // values[4]: the estimated heading accuracy, radians (-1 unknown; not on every phone)
                    errorDeg = e.values.length > 4 && e.values[4] >= 0 ? (int) Math.round(Math.toDegrees(e.values[4])) : -1;
                    at = android.os.SystemClock.elapsedRealtime();
                } catch (RuntimeException ex) {
                    // a reading short of values: the next one
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int acc) {
                accuracy = acc;
            }
        };
        sm.registerListener(listener, rv, 200_000); // 5 a second: a map, not a gyro
        log("Observation: the phone's heading sensor on");
    }

    static synchronized void stop(Context context) {
        if (listener == null) return;
        SensorManager sm = context == null ? null : (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) sm.unregisterListener(listener);
        listener = null;
        azimuthDeg = Double.NaN;
        log("Observation: the phone's heading sensor off");
    }

    /** Degrees from north, or NaN (none, or stale). */
    static double azimuthDeg() {
        return android.os.SystemClock.elapsedRealtime() - at > STALE_MS ? Double.NaN : azimuthDeg;
    }

    static int accuracy() {
        return accuracy;
    }

    static int errorDeg() {
        return errorDeg;
    }
}
