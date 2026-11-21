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
 * DRAUPNE Flight (F-070b): when an update's install that waits - for Android, or for the tap on its "update this
 * app?" - is taken back.
 * <p>
 * Found by a reader of the code (2026-11-22): a remote update that needs the tap told the PC so for five minutes
 * (the PC refuses to arm meanwhile), then said nothing more, and Android's prompt stayed on the phone's screen for
 * good. Armed and flying, one tap on it installed the update: the app ends, and the sticks with it.
 * <p>
 * Now the install session is abandoned (Android's prompt is then void: a tap on it installs nothing):
 * <ul>
 * <li>the moment the flight controller is not known to be disarmed - armed, or unknown, as every guard counts it;</li>
 * <li>a remote one, when the phone no longer tells the PC that it waits: the PC may arm from then on;</li>
 * <li>at the app's next start, when an earlier run left one ({@link #leftOver}).</li>
 * </ul>
 * Pure: the phone's service asks once a second, the PC's tests check the cases.
 */
public final class UpdateWithdraw {
    /** What {@link #now} says: let it wait; withdraw it, the flight controller is not known disarmed; withdraw it, the PC is no longer told. */
    public static final int KEEP = 0, NOT_DISARMED = 1, NO_LONGER_REPORTED = 2;

    private UpdateWithdraw() {
    }

    /**
     * An install session of this app's waits.
     * @param fcKnownDisarmed the flight controller reads disarmed now (not armed, not unknown)
     * @param remote the PC asked for this update (else the pilot began it on the phone)
     * @param reported the phone still tells the PC "installing" or "tap needed"
     */
    public static int now(boolean fcKnownDisarmed, boolean remote, boolean reported) {
        if (!fcKnownDisarmed) return NOT_DISARMED;
        if (remote && !reported) return NO_LONGER_REPORTED;
        return KEEP;
    }

    /**
     * At the app's start: the session whose id is kept on the disk is one an earlier run left - not none, and not the
     * one this run began (the app's window opened again while its own update is under way).
     */
    public static boolean leftOver(int keptId, int thisRunsId, int none) {
        return keptId != none && keptId != thisRunsId;
    }
}
