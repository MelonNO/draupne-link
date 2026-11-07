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
 * DRAUPNE Flight: who may start a new session while a pilot PC has (or just had) one - a keyed Connect from another
 * address or port than the pilot's. Pure: the phone's Udp asks it, the PC's tests check it.
 * <ul>
 * <li>Another address: only with the flight controller known DISARMED and the pilot silent {@link #PILOT_ALIVE_MS}
 * (pc.26 / pc.31).</li>
 * <li>The pilot's own address on a new port - the pilot's PC started again, or a second DRAUPNE Link on that PC or
 * behind the same NAT: at once while the FC is known DISARMED; otherwise only after the pilot has been silent
 * {@link #SAME_IP_SILENT_MS} (pc.38: before, at once whatever the FC said, and a second app on the pilot's PC could
 * take an armed drone in the air).</li>
 * </ul>
 * Refused: the asker is told PILOT_AT, as any other PC.
 */
public final class Takeover {
    /** Another PC takes over only after this much silence from the pilot (and only disarmed). */
    public static final long PILOT_ALIVE_MS = 10_000;
    /** The pilot's address on a new port, while the FC is not known DISARMED: after this much silence from the pilot. */
    public static final long SAME_IP_SILENT_MS = 2000;

    private Takeover() {
    }

    /**
     * @param sameAddress the Connect comes from the pilot's address (on another port)
     * @param disarmed    the flight controller is known DISARMED (fresh status; unknown counts as armed)
     * @param silentMs    how long since the pilot's last packet
     * @return true if the Connect starts a new session
     */
    public static boolean allowed(boolean sameAddress, boolean disarmed, long silentMs) {
        if (sameAddress) return disarmed || silentMs >= SAME_IP_SILENT_MS;
        return disarmed && silentMs >= PILOT_ALIVE_MS;
    }
}
