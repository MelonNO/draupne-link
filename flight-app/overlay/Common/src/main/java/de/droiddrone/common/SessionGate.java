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

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * DRAUPNE Flight: who gets a session on the phone - the key, the pilot's own Connects and another PC's (moved out of the
 * phone's Udp, rules unchanged). Pure: Udp asks it on the receive thread with its monotonic clock and does what the
 * {@link Verdict} says; the PC's tests check it. {@link Takeover} decides between PCs.
 * <ul>
 * <li>A session up, the pilot's address and port: anything is taken. A keyed Connect is the pilot counting the link
 * as lost (pc.26): a new session at once - but the Connects in flight after a loss (one every 150 ms) are one
 * session, not one each (pc.31: each answer restarted the PC's numbering), so a repeat within
 * {@link #SAME_SESSION_MS} is dropped.</li>
 * <li>A session up, another address or port: a keyed Connect that Takeover allows starts a new session (before, such
 * a PC was told PILOT_AT - the dead session's address - and a restarted PC never connected); anything else is told
 * PILOT_AT, as a spectator.</li>
 * <li>No session (pc.31): nothing but a keyed Connect - the dead session's buffer took any packet, keyless, from any
 * device on the network, and came back to life on it. From another PC than the last pilot's, Takeover's rule holds on
 * after the 4 s timeout: a 4G fade of 4 s handed an armed drone to any PC left on CONNECT.</li>
 * </ul>
 */
public final class SessionGate {
    /** A pilot's Connect within this long of the session it started is the same session. */
    public static final long SAME_SESSION_MS = 1000;

    public enum Verdict {
        /** A packet of the session: on to its buffer. */
        TAKE,
        /** A new session starts with this Connect. */
        NEW_SESSION,
        /** One of the Connects in flight after a loss, for the session that started: dropped. */
        SAME_SESSION,
        /** A Connect with another key: dropped without a word (the phone's screen says so). */
        WRONG_KEY,
        /** A keyed Connect Takeover refused: told PILOT_AT. */
        REFUSED,
        /** Anything else from another PC: told PILOT_AT, as a spectator. */
        SPECTATOR,
        /** No session and no Connect: dropped. */
        DROP
    }

    private final String key;
    /** When the pilot PC was last heard from (any packet from its address and port). */
    private volatile long lastPilotMs;
    /** When the pilot's Connect last started a session here. */
    private volatile long sessionStartedMs;

    public SessionGate(String key) {
        this.key = key;
    }

    /** Whether the key written (UTF) at keyAt - 4 in a Connect, 1 in a PILOT_QUERY - is this phone's. */
    public boolean keyMatches(byte[] d, int len, int keyAt) {
        if (len < keyAt + 2) return false;
        int n = ((d[keyAt] & 0xff) << 8) | (d[keyAt + 1] & 0xff);
        return keyAt + 2 + n <= len && key.equals(new String(d, keyAt + 2, n, StandardCharsets.UTF_8));
    }

    /** How long the pilot has been silent. */
    public long silentMs(long now) {
        return now - lastPilotMs;
    }

    /**
     * Whether a spectator is told the pilot is there: heard within {@link Takeover#PILOT_ALIVE_MS}, or the FC not known
     * DISARMED - armed, the pilot's silence is a fade, not an absence.
     */
    public boolean pilotAlive(boolean disarmed, long now) {
        return silentMs(now) < Takeover.PILOT_ALIVE_MS || !disarmed;
    }

    /** A packet from the pilot's address and port while its session is up: TAKE, NEW_SESSION or SAME_SESSION. */
    public Verdict fromPilot(byte[] d, int len, long now) {
        lastPilotMs = now;
        if (d[0] != UdpCommon.Connect || !keyMatches(d, len, 4)) return Verdict.TAKE;
        if (now - sessionStartedMs < SAME_SESSION_MS) return Verdict.SAME_SESSION;
        sessionStartedMs = now;
        return Verdict.NEW_SESSION;
    }

    /**
     * A packet from another address or port while a session is up: NEW_SESSION, WRONG_KEY, REFUSED or SPECTATOR.
     * @param sameAddress from the pilot's address (on another port)
     * @param disarmed    the flight controller is known DISARMED (fresh status; unknown counts as armed)
     */
    public Verdict fromOther(byte[] d, int len, boolean sameAddress, boolean disarmed, long now) {
        if (d[0] != UdpCommon.Connect) return Verdict.SPECTATOR;
        if (!keyMatches(d, len, 4)) return Verdict.WRONG_KEY;
        if (!Takeover.allowed(sameAddress, disarmed, silentMs(now))) return Verdict.REFUSED;
        sessionStartedMs = now;
        return Verdict.NEW_SESSION;
    }

    /**
     * A packet while no session is up: NEW_SESSION, WRONG_KEY, SPECTATOR (another PC Takeover refused) or DROP.
     * @param pilotKnown  a pilot PC has flown this phone since the app started
     * @param samePc      from that pilot's address and port
     * @param sameAddress from that pilot's address
     * @param disarmed    asked only when Takeover needs it
     */
    public Verdict noSession(byte[] d, int len, boolean pilotKnown, boolean samePc, boolean sameAddress,
                             BooleanSupplier disarmed, long now) {
        if (d[0] != UdpCommon.Connect) return Verdict.DROP;
        if (!keyMatches(d, len, 4)) return Verdict.WRONG_KEY;
        if (pilotKnown && !samePc && !Takeover.allowed(sameAddress, disarmed.getAsBoolean(), silentMs(now))) return Verdict.SPECTATOR;
        lastPilotMs = now; // heard from: no takeover in its first half second
        sessionStartedMs = now;
        return Verdict.NEW_SESSION;
    }
}
