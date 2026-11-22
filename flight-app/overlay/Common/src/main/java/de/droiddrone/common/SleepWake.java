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
 * DRAUPNE Flight (pc.50's listening in deep sleep): which packets keep a sleeping phone awake for their handling (the
 * phone's 3 s packet lock). The kinds that can concern a sleeping phone - a Connect, the PC's answer to its check-in, a
 * fleet mode, an observer's request, a spectator's question - and of those only one with this phone's key where the
 * packet carries one, and the check-in's answer (it carries none) only from the PC the phone checks in with. Before, the
 * kind alone was enough: any datagram that began with one of those bytes kept the phone awake 3 s, key or not.
 * <p>
 * Pure, allocation-free until a key is read: {@link #kind} is a few byte compares the receive thread makes for every
 * packet as before (a stick frame is none of these kinds); the rest is asked only while the phone listens asleep.
 */
public final class SleepWake {
    private SleepWake() {
    }

    /** A kind of packet that may concern a sleeping phone (the byte compares of before). */
    public static boolean kind(byte type) {
        return type == UdpCommon.Connect || type == PacketIds.SLEEP_ANSWER || type == FleetProtocol.MODE
                || type == Observe.OBSERVE || type == Observe.STOP || type == PacketIds.SPECTATE_PILOT_QUERY;
    }

    /**
     * Whether this packet keeps the sleeping phone awake for its handling.
     * @param gate       the phone's key check (the key in a Connect at 4, in the others at 1)
     * @param fromLastPc the sender is the PC the phone checks in with (for the check-in's answer, which has no key)
     */
    public static boolean holdsAwake(byte[] d, int len, SessionGate gate, boolean fromLastPc) {
        if (d == null || len < 1) return false;
        byte type = d[0];
        if (type == PacketIds.SLEEP_ANSWER) return fromLastPc && len >= 2;
        if (type == UdpCommon.Connect) return gate.keyMatches(d, len, 4);
        if (type == FleetProtocol.MODE || type == Observe.OBSERVE || type == Observe.STOP || type == PacketIds.SPECTATE_PILOT_QUERY) {
            return gate.keyMatches(d, len, 1);
        }
        return false;
    }
}
