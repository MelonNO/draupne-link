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

    /** How long a follower's order to lead lets its commander's pilot session in (see {@link #leadConnect}). */
    public static final long LEAD_CONNECT_MS = 5_000;
    /** A DISBAND this recent still counts as a follower (the new lead's DISBAND and LEAD come together). */
    public static final long LEFT_GROUP_MS = 2_000;

    /**
     * Fleet FL-5 / FL-6, the hand-over: a keyed Connect the session rules refused (SessionGate: the drone armed, its last
     * pilot another PC) is let in only when all of this holds - the drone was a follower when its commander, the PC
     * whose group it follows, ordered it to lead (FleetCmd.LEAD); the Connect comes from that commander; and within
     * {@link #LEAD_CONNECT_MS} of the order. Nobody else gets more than SessionGate gives.
     * @param leadOrderedAsFollower the commander's order to lead came while this drone was a follower
     * @param fromCommander         the Connect comes from the commander's address
     * @param sinceLeadOrderMs      how long ago that order came
     */
    public static boolean leadConnect(boolean leadOrderedAsFollower, boolean fromCommander, long sinceLeadOrderMs) {
        return leadOrderedAsFollower && fromCommander && sinceLeadOrderMs >= 0 && sinceLeadOrderMs <= LEAD_CONNECT_MS;
    }
}
