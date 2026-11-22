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
 * DRAUPNE Flight (F-068): what the armed guards are told while no flight controller has answered - another PC taking
 * the session, standby and the sleeps, a FLEET mode, the remote update, the idle sticks, the microphone, observers,
 * the motion detection. They act only on a known DISARMED; unknown counts as armed (the phone's FcArmedState).
 * <p>
 * Before, "no flight controller initialised" read DISARMED unless this app itself had seen it armed in the last
 * minute. An app that has just started - after an error, ended by Android, opened by hand - has seen nothing: for the
 * second or two until the flight controller's first answers it said DISARMED of a drone in the air, and for good when
 * the board was there and did not answer (its USB permission not given again, say). Now three cases are told apart
 * by what the flight controller's port says:
 * <ul>
 * <li>{@link #PORT_BOARD}: a board is on the port - found on USB, asked for, opened, failed - and has not answered
 * yet: not read, UNKNOWN. For as long as it does not answer.</li>
 * <li>{@link #PORT_NOTHING}: the app has looked and there is no board on USB at all, for {@link #NOTHING_AFTER_MS}
 * (the look itself takes a moment, and a board that fell off may be back at the next one): nothing to protect,
 * DISARMED - a phone on the bench with no board can still go to standby. As before: UNKNOWN while this app saw the
 * flight controller armed within {@link #ARMED_MEMORY_MS} (its USB link fell off in the air).</li>
 * <li>{@link #PORT_CLOSED}: the app is not looking. Asleep (deep sleep, overwatch) it closed the port itself, right
 * after a DISARMED read: DISARMED - overwatch's motion detection asks all through the sleep. Otherwise it has not
 * begun to look (the app's first moments, the moment of a wake): UNKNOWN.</li>
 * </ul>
 * Pure: the phone's FcArmedState hands in what its port says, the PC's tests check it.
 */
public final class FcUnread {
    /** The two answers there are without the flight controller's own, as the phone's FcArmedState numbers them. */
    public static final int DISARMED = 0, UNKNOWN = 2;
    /** What the flight controller's port says: not looked for; looked for and nothing on USB; a board that has not answered. */
    public static final int PORT_CLOSED = 0, PORT_NOTHING = 1, PORT_BOARD = 2;
    /**
     * "Nothing on USB" counts after this long without a break: the port's first look for a board comes a moment after
     * it says "nothing" (the app's start, a lost board's new search), and it looks again every 2 s.
     */
    public static final long NOTHING_AFTER_MS = 3000;
    /** No board on USB: UNKNOWN if this app saw the flight controller armed this recently. */
    public static final long ARMED_MEMORY_MS = 60_000;

    private FcUnread() {
    }

    /**
     * @param port         PORT_CLOSED, PORT_NOTHING or PORT_BOARD
     * @param nothingForMs how long the port has said PORT_NOTHING without a break (asked for that port only)
     * @param asleep       the phone is in deep sleep or overwatch (asked for PORT_CLOSED only)
     * @param armedAgoMs   how long ago this app last read the flight controller ARMED; negative: never
     * @return DISARMED or UNKNOWN
     */
    public static int state(int port, long nothingForMs, boolean asleep, long armedAgoMs) {
        if (port == PORT_NOTHING) {
            if (nothingForMs < NOTHING_AFTER_MS) return UNKNOWN;
            return armedAgoMs >= 0 && armedAgoMs < ARMED_MEMORY_MS ? UNKNOWN : DISARMED;
        }
        if (port == PORT_CLOSED) return asleep ? DISARMED : UNKNOWN;
        return UNKNOWN;
    }
}
