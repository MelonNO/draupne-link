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

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.droiddrone.common.KeptPilot;

/**
 * Who flew this phone, in the app's own storage (KeptPilot's line), so that the app started anew still knows: read at
 * the service's start, written from Udp's telemetry thread once a session, forgotten at the PC's DISCONNECT and at
 * STANDBY. Writes go to a thread of their own, and only the newest waits: never the link's threads' disk.
 */
final class PilotStore {
    private static final String PREFS = "dd_pilot", KEY = "pilot";
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pilotStore");
        t.setDaemon(true);
        return t;
    });
    private static final Object lock = new Object();
    /** Under lock: the line to be on the disk, the one there, and whether a write is queued (at most one is). */
    private static String wanted, written;
    private static boolean queued;

    private PilotStore() {
    }

    /** The pilot kept, or null; at the service's start, before the link's socket is open. */
    static KeptPilot read(Context c) {
        String line = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        synchronized (lock) {
            written = line;
            wanted = line;
        }
        return KeptPilot.decode(line);
    }

    /**
     * This line kept (null: none) - on the writer's thread; nothing when it is what is kept already.
     * @return whether it is another than the one kept, or about to be
     */
    static boolean keep(Context c, String line) {
        if (c == null) return false;
        final Context app = c.getApplicationContext();
        boolean changed;
        synchronized (lock) {
            changed = !same(line, wanted);
            wanted = line;
            if (queued || same(line, written)) return changed;
            queued = true;
        }
        writer.execute(() -> {
            String v;
            synchronized (lock) {
                v = wanted;
                queued = false;
                if (same(v, written)) return;
                written = v;
            }
            SharedPreferences.Editor e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (v == null) e.remove(KEY);
            else e.putString(KEY, v);
            e.commit(); // on this thread: the line is on the disk should the app end next
        });
        return changed;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
