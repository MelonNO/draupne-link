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

/**
 * DRAUPNE Link (pc.31): whether the flight controller is armed, as the guards need it - another PC taking over,
 * standby, deep sleep, a config change that restarts the FC's port. Before, every guard read Msp.isArmed, which was
 * false whenever it didn't know: while the FC came back after a USB glitch, before its mode ids were read, when its
 * status replies stopped coming (the writer skips requests on a slow USB link), and always on a MAVLink FC. Now a
 * guard acts only on a fresh {@link #DISARMED}; {@link #UNKNOWN} counts as armed.
 * <p>
 * F-068: with no flight controller answering, DISARMED only when there is no board on USB at all or the port was
 * closed for a sleep - a board that has not answered yet (an app that has just started, in the air perhaps) is
 * UNKNOWN (FcUnread, in Common, has the rule and its tests).
 */
final class FcArmedState {
    static final int DISARMED = 0, ARMED = 1, UNKNOWN = 2;
    /**
     * No board on USB: UNKNOWN if it was seen armed this recently (its USB link fell off in the air), else DISARMED -
     * a phone on the bench with no FC can still go to standby.
     */
    static final long ARMED_MEMORY_MS = de.droiddrone.common.FcUnread.ARMED_MEMORY_MS;
    /** A MAVLink heartbeat (about one a second) older than this says nothing any more. */
    static final long MAVLINK_FRESH_MS = 3000;
    private static volatile long lastArmedMs;
    private static volatile long mavlinkAtMs;
    private static volatile boolean mavlinkArmed;
    /** F-068: since when every look here found the port saying "no board on USB" (elapsedRealtime), 0: it does not. */
    private static volatile long nothingSinceMs;

    private FcArmedState() {
    }

    /** A MAVLink heartbeat's armed flag (Udp reads it from Mavlink's DD_MAVLINK_MODE record). */
    static void mavlinkHeartbeat(boolean armed) {
        mavlinkArmed = armed;
        mavlinkAtMs = android.os.SystemClock.elapsedRealtime();
    }

    /** pc.38: every age here on SystemClock.elapsedRealtime (Msp's status stamps too): a clock step can't freshen one. */
    static int of(Msp msp, Mavlink mavlink) {
        long now = android.os.SystemClock.elapsedRealtime();
        int state;
        if (msp != null && msp.isInitialized()) {
            state = msp.armedState(now);
            if (nothingSinceMs != 0) nothingSinceMs = 0;
        } else if (mavlink != null && mavlink.isInitialized()) {
            state = now - mavlinkAtMs <= MAVLINK_FRESH_MS ? (mavlinkArmed ? ARMED : DISARMED) : UNKNOWN;
            if (nothingSinceMs != 0) nothingSinceMs = 0;
        } else {
            state = unread(msp, now);
        }
        if (state == ARMED) lastArmedMs = now;
        return state;
    }

    /**
     * F-068: no flight controller answers - by what its port says (FcUnread). Before: DISARMED unless this app had
     * seen it armed within the minute, so an app that had just started said DISARMED of a drone in the air until the
     * flight controller's first answers came, and for good when they never came. A few volatile reads; asked by the
     * guards, never for a stick frame.
     */
    private static int unread(Msp msp, long now) {
        int status = msp == null ? Serial.STATUS_NOT_INITIALIZED : msp.portStatus();
        int port = status == Serial.STATUS_NOT_INITIALIZED ? de.droiddrone.common.FcUnread.PORT_CLOSED
                : status == Serial.STATUS_DEVICE_NOT_CONNECTED ? de.droiddrone.common.FcUnread.PORT_NOTHING
                : de.droiddrone.common.FcUnread.PORT_BOARD;
        long since = nothingSinceMs;
        if (port != de.droiddrone.common.FcUnread.PORT_NOTHING) {
            if (since != 0) nothingSinceMs = 0;
            since = now;
        } else if (since == 0) {
            nothingSinceMs = since = now;
        }
        long armedAt = lastArmedMs;
        return de.droiddrone.common.FcUnread.state(port, now - since, PowerMode.isSleepMode(), armedAt == 0 ? -1 : now - armedAt);
    }

    /**
     * pc.50: the FC was just read DISARMED and its port is closed on purpose (deep sleep, overwatch): "seen armed
     * lately" is forgotten. It made overwatch begun within a minute of a disarm read UNKNOWN - the motion detection
     * paused, and the camera's watchdog called the watch blind.
     */
    static void closedDisarmed() {
        lastArmedMs = 0;
    }

    static String name(int state) {
        switch (state) {
            case DISARMED: return "disarmed";
            case ARMED: return "armed";
            default: return "unknown";
        }
    }
}
