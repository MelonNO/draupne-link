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

package de.droiddrone.common;

import java.util.function.BooleanSupplier;

/**
 * DRAUPNE Flight pc.40: whether the phone takes a FLEET_MODE ({@link FleetProtocol}) from a PC's fleet management - a
 * power mode without a session (moved out of the phone's Udp, rules unchanged). Each token is taken once: the PC
 * repeats it until the phone's status says so. Refused - not taken, the PC goes on asking - while a pilot is connected
 * or the FC isn't known to be disarmed (unknown counts as armed). Pure: Udp asks it on the receive thread, the PC's
 * tests check it.
 */
public final class FleetGate {
    public enum Verdict {
        /** Taken: its PC is the one this phone checks in with and calls with overwatch alerts, and the mode is asked for. */
        TAKE,
        /** The token taken last, again: nothing to do, nothing said. */
        REPEAT,
        /** Refused: a pilot is connected. */
        REFUSED_PILOT,
        /** Refused: the FC isn't known disarmed. */
        REFUSED_ARMED
    }

    private FleetGate() {
    }

    /**
     * @param token          the request's
     * @param lastToken      the last FLEET_MODE taken (0: none)
     * @param pilotConnected a pilot's session is up
     * @param disarmed       the flight controller is known DISARMED; asked only for a new token with no pilot connected
     */
    public static Verdict request(int token, int lastToken, boolean pilotConnected, BooleanSupplier disarmed) {
        if (token == lastToken) return Verdict.REPEAT;
        if (pilotConnected) return Verdict.REFUSED_PILOT;
        if (!disarmed.getAsBoolean()) return Verdict.REFUSED_ARMED;
        return Verdict.TAKE;
    }
}
