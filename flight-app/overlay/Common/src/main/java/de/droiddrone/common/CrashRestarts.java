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

package de.droiddrone.common;

/**
 * pc.55: how often the phone app opens itself again after it was ended by an error (or found gone when its sleep
 * check's alarm rang). Seen in the bench's night of 2026-11-20: the camera was closed while a change of the picture's
 * size was being set up, one exception on the camera's thread, and Android ended the whole app - with nothing to
 * bring it back, the drone was out of reach until someone opened the app by hand.
 * <p>
 * Now the app asks to be opened again a moment later - but not for ever: an app that ends at every start would
 * open, end and open again all day. {@link #MAX} times within {@link #WINDOW_MS}, then it stays down until a hand
 * opens it. Pure: the times are kept by the caller (they must outlive the app), on the wall clock, as text.
 */
public final class CrashRestarts {
    /** So many restarts in {@link #WINDOW_MS} at most. */
    public static final int MAX = 3;
    public static final long WINDOW_MS = 10 * 60_000L;
    /** This long after the end: Android has cleared the old process away, the port is free. */
    public static final long DELAY_MS = 1500;

    private CrashRestarts() {
    }

    /**
     * The app was ended now: may it open again?
     * @param stored what the last call returned ("" or null at first; anything unreadable counts as nothing)
     * @return what to store from now on when it may ("t1,t2,..."), null when it has opened itself too often
     */
    public static String next(String stored, long nowMs) {
        StringBuilder kept = new StringBuilder();
        int count = 0;
        if (stored != null) {
            for (String part : stored.split(",")) {
                long at;
                try {
                    at = Long.parseLong(part.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                // a time in the future is a clock that was set back since: it counts for nothing
                if (at > nowMs || nowMs - at > WINDOW_MS) continue;
                kept.append(at).append(',');
                count++;
            }
        }
        if (count >= MAX) return null;
        return kept.append(nowMs).toString();
    }
}
