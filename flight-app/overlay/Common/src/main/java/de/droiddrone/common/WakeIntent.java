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

import java.util.Random;

/**
 * DRAUPNE Flight: the main screen's WAKE - the app put in front of the lock screen, the screen lit - when the PC wakes
 * the phone from a sleep, after an update the PC asked for, and when the app opens itself again after an error.
 * <ul>
 * <li>{@link #own}: only the app's own WAKE counts. The main screen is open to other apps (the launcher, a flight
 * controller plugged in), and before, any of them could send the WAKE action and put the app over the lock screen. The
 * app's WAKE intents carry a token made once and kept in the app's own storage; one without it opens the app as a
 * plain start does, behind the lock.</li>
 * <li>{@link OverLock}: over the lock screen for the one showing it was woken for - once that screen has been shown and
 * then left (the screen off, another app in front), the lock screen is in front of the app again. Before, it stayed
 * over the lock screen for as long as the app ran.</li>
 * </ul>
 * Pure: the phone's MainActivity asks it on its own thread; the PC's tests check it.
 */
public final class WakeIntent {
    /** The token's length in bytes (32 hex digits). */
    public static final int TOKEN_BYTES = 16;

    private WakeIntent() {
    }

    /** A new token, from the random source given (the phone's SecureRandom). */
    public static String newToken(Random random) {
        byte[] b = new byte[TOKEN_BYTES];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(2 * TOKEN_BYTES);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    /** Whether a WAKE carrying {@code got} is the app's own: the token kept, whole, compared in constant time. */
    public static boolean own(String kept, String got) {
        if (kept == null || got == null || kept.length() != 2 * TOKEN_BYTES || got.length() != kept.length()) return false;
        int diff = 0;
        for (int i = 0; i < kept.length(); i++) diff |= kept.charAt(i) ^ got.charAt(i);
        return diff == 0;
    }

    /** Whether the main screen is over the lock screen, and when that ends: the activity's lifecycle, told here. */
    public static final class OverLock {
        private boolean on, shown;

        /** A WAKE of the app's own: over the lock screen from now. */
        public void woken() {
            on = true;
            shown = false;
        }

        /** The screen came up (onResume). */
        public void resumed() {
            if (on) shown = true;
        }

        /**
         * The screen went (onStop).
         * @return true when the over-the-lock-screen flags are to be cleared now: the showing it was woken for is over.
         *         Not before it was shown - a stop on the way (the screen still dark) keeps them for the showing.
         */
        public boolean stopped() {
            if (!on || !shown) return false;
            on = false;
            shown = false;
            return true;
        }

        public boolean on() {
            return on;
        }
    }
}
