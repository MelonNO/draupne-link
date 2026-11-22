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
 * DRAUPNE Flight: whether the phone takes a NAME_ASSIGN (its DRAUPNE name, MobileAlpha ...). Before, it took one with
 * the key in any state from any address - two phones' names could be swapped in flight, and the next PILOT UAV went to
 * the other drone. Now:
 * <ul>
 * <li>only with the flight controller known DISARMED (unknown counts as armed, as for every guard);</li>
 * <li>while a pilot's session is up, only from the pilot PC's address;</li>
 * <li>otherwise only from a PC that asked this phone's name lately ({@link KnownPcs#NOTICE_MS}) - the naming master
 * names a phone after it asked who it is (the scan, NAME_QUERY), and the name map's RENAME is sent again with each
 * scan's query until the phone answers with the new name.</li>
 * </ul>
 * A refused assign changes nothing and is not answered as taken: the PC goes on asking, as for a lost one, and gives a
 * rename up after its 30 s (the old name put back). Pure: the phone's Udp and the virtual fleet's phone ask it.
 */
public final class NameAssignGate {
    public static final int TAKEN = 0, REFUSED_NOT_DISARMED = 1, REFUSED_SENDER = 2;

    private NameAssignGate() {
    }

    /**
     * @param fcDisarmed     the flight controller is known DISARMED
     * @param pilotConnected a pilot's session is up
     * @param fromPilot      the assign comes from the pilot PC's address (asked only with a session up)
     * @param askedLately    the sender's address asked this phone's name within {@link KnownPcs#NOTICE_MS}
     */
    public static int verdict(boolean fcDisarmed, boolean pilotConnected, boolean fromPilot, boolean askedLately) {
        if (!fcDisarmed) return REFUSED_NOT_DISARMED;
        if (pilotConnected) return fromPilot ? TAKEN : REFUSED_SENDER;
        return askedLately ? TAKEN : REFUSED_SENDER;
    }

    public static String why(int verdict) {
        switch (verdict) {
            case TAKEN: return "taken";
            case REFUSED_NOT_DISARMED: return "refused: the FC is not known disarmed";
            default: return "refused: not from the pilot's PC, nor from a PC that asked this phone's name";
        }
    }
}
