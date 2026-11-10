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

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.Log;

/**
 * pc.47 (2026-11-10, the pilot: "make all phones go into muted and vibrationless mode so they dont make any noise when
 * getting notifications"): while DRAUPNE Flight runs, the drone's phone neither rings nor buzzes - its ringer silent,
 * which is no sound and no vibration for calls and notifications (media, the camera and the microphone are not
 * touched). Android lets an app go silent only with Do Not Disturb access; without it the app's screen shows a line
 * that opens the setting. The mode the phone had is kept and put back when the service stops.
 */
final class Silent {
    private Silent() {
    }

    private static final String PREFS = "dd_silent", BEFORE = "ringerBefore";

    /** Android lets this app make the phone silent. */
    static boolean allowed(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    /** The phone silent and still, the mode it had kept to put back (once: a second call keeps the first one's). */
    static void on(Context c) {
        if (!allowed(c)) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null) return;
        try {
            int now = am.getRingerMode();
            if (now == AudioManager.RINGER_MODE_SILENT) return;
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!p.contains(BEFORE)) p.edit().putInt(BEFORE, now).apply();
            am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            Log.i("DD", "Silent: ringer off, no vibration (was mode " + now + ")");
        } catch (RuntimeException e) { // a SecurityException when the access was taken away meanwhile
            Log.w("DD", "Silent: not allowed: " + e);
        }
    }

    /** The mode it had before, back; nothing when this app didn't change it. */
    static void off(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains(BEFORE)) return;
        int before = p.getInt(BEFORE, AudioManager.RINGER_MODE_NORMAL);
        p.edit().remove(BEFORE).apply();
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null || !allowed(c)) return;
        try {
            // only if it is still the silence this app made: the pilot may have changed it by hand meanwhile
            if (am.getRingerMode() == AudioManager.RINGER_MODE_SILENT) am.setRingerMode(before);
        } catch (RuntimeException e) {
            Log.w("DD", "Silent: could not put the ringer back: " + e);
        }
    }
}
