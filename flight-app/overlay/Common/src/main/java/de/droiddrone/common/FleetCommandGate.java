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
 * DRAUPNE Flight (fleet PRD FD-1): which PC may give a drone fleet commands (FleetCmd) - a keyed one (checked before
 * this), and:
 * <ul>
 *   <li>in a pilot session: the pilot's own PC only;</li>
 *   <li>otherwise its commander - the PC that piloted it last, or that commanded it last;</li>
 *   <li>another PC becomes the commander only as the takeover rule lets a new pilot in (SessionGate): the flight
 *       controller known DISARMED and the commander silent {@link #TAKEOVER_SILENT_MS} - never of a drone in the air,
 *       whose orders and exit plan are its commander's.</li>
 * </ul>
 * Java 11, pure.
 */
public final class FleetCommandGate {
    public static final long TAKEOVER_SILENT_MS = 10_000;
    public static final int REFUSE = 0, ACCEPT = 1, NEW_COMMANDER = 2;

    private FleetCommandGate() {
    }

    /**
     * @param inSession         a pilot session is up
     * @param fromPilot         the sender is that session's PC
     * @param commanderKnown    this phone has a commander (or a last pilot)
     * @param fromCommander     the sender is it
     * @param commanderSilentMs how long the commander has been silent
     * @param fcDisarmed        the flight controller is known DISARMED (unknown counts as armed)
     */
    public static int decide(boolean inSession, boolean fromPilot, boolean commanderKnown, boolean fromCommander,
                             long commanderSilentMs, boolean fcDisarmed) {
        if (inSession) return fromPilot ? ACCEPT : REFUSE;
        if (commanderKnown && fromCommander) return ACCEPT;
        if (!fcDisarmed) return REFUSE;
        if (!commanderKnown || commanderSilentMs >= TAKEOVER_SILENT_MS) return NEW_COMMANDER;
        return REFUSE;
    }
}
