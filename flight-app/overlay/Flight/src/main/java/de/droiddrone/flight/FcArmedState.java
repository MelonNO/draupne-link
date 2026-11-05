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
 */
final class FcArmedState {
    static final int DISARMED = 0, ARMED = 1, UNKNOWN = 2;
    /**
     * No flight controller connected: UNKNOWN if it was seen armed this recently (its USB link fell off in the air),
     * else DISARMED - a phone on the bench with no FC can still go to standby.
     */
    static final long ARMED_MEMORY_MS = 60_000;
    /** A MAVLink heartbeat (about one a second) older than this says nothing any more. */
    static final long MAVLINK_FRESH_MS = 3000;
    private static volatile long lastArmedMs;
    private static volatile long mavlinkAtMs;
    private static volatile boolean mavlinkArmed;

    private FcArmedState() {
    }

    /** A MAVLink heartbeat's armed flag (Udp reads it from Mavlink's DD_MAVLINK_MODE record). */
    static void mavlinkHeartbeat(boolean armed) {
        mavlinkArmed = armed;
        mavlinkAtMs = System.currentTimeMillis();
    }

    static int of(Msp msp, Mavlink mavlink) {
        long now = System.currentTimeMillis();
        int state;
        if (msp != null && msp.isInitialized()) {
            state = msp.armedState(now);
        } else if (mavlink != null && mavlink.isInitialized()) {
            state = now - mavlinkAtMs <= MAVLINK_FRESH_MS ? (mavlinkArmed ? ARMED : DISARMED) : UNKNOWN;
        } else {
            state = lastArmedMs != 0 && now - lastArmedMs < ARMED_MEMORY_MS ? UNKNOWN : DISARMED;
        }
        if (state == ARMED) lastArmedMs = now;
        return state;
    }

    static String name(int state) {
        switch (state) {
            case DISARMED: return "disarmed";
            case ARMED: return "armed";
            default: return "unknown";
        }
    }
}
