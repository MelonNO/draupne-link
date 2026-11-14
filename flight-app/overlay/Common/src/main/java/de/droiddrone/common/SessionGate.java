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
 * The session id (planned, not on the wire yet): a PC that knows it ends its Connect with {@code [sessionId u32]},
 * behind the version - {@code [8][num i16][client type][key UTF][version i16][sessionId u32]}; an older phone reads
 * past it, and the phone's answer (the Connect sent back) carries it home. With it the phone need not tell by the
 * clock which Connects are one session: the overloads that take the id ({@link #sessionId} reads it) count the live
 * session's own id as that session at any age and another id as a new session at once. A Connect without one
 * ({@link #NO_ID}, an older PC) is judged by the rules above, to the letter. {@link Ids} is the PC's side.
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
    /** A Connect without a session id: from a PC that sends none. */
    public static final long NO_ID = -1;
    /**
     * The id the live session was started with, and the start it goes with: a session started since by a call that
     * takes no id has none (its start is another).
     */
    private volatile long liveId = NO_ID;
    private volatile long liveIdStartedMs;

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

    /**
     * The session id a Connect ends with, 0..2^32-1; {@link #NO_ID} for a Connect without one, or a packet that is no
     * Connect (so every packet from the pilot can be handed to the overload below as it comes).
     */
    public static long sessionId(byte[] d, int len) {
        if (len < 6 || d[0] != UdpCommon.Connect) return NO_ID;
        int at = 6 + (((d[4] & 0xff) << 8) | (d[5] & 0xff)) + 2; // behind the key and the version
        if (len < at + 4) return NO_ID;
        return Bytes.readInt(d, at) & 0xFFFFFFFFL;
    }

    private boolean liveIdKnown() {
        return liveId != NO_ID && liveIdStartedMs == sessionStartedMs;
    }

    /** The session that just started (or was just recognised) has this id. */
    private void liveIdIs(long sessionId) {
        liveId = sessionId;
        liveIdStartedMs = sessionStartedMs;
    }

    /**
     * {@link #fromPilot(byte[], int, long)} for a PC that may send a session id ({@link #sessionId}). Without one:
     * that method's verdict, unchanged. With one, a keyed Connect is SAME_SESSION when the id is the live session's -
     * however long ago that started: its Connects still in flight on a slow path, a second and more after the first
     * - and NEW_SESSION at once when it is another: the PC has let the old session go. Where the live session has no
     * id (it was started through a call that takes none), a Connect within {@link #SAME_SESSION_MS} of its start is
     * one of its own and gives it its id.
     * <p>
     * SAME_SESSION is dropped unanswered, as ever: if the phone's answer to the first Connect was lost, the same id
     * gets none again. The PC must change the id when its Connects stay unanswered - {@link Ids}.
     */
    public Verdict fromPilot(byte[] d, int len, long now, long sessionId) {
        if (sessionId == NO_ID) {
            Verdict v = fromPilot(d, len, now);
            if (v == Verdict.NEW_SESSION) liveIdIs(NO_ID);
            return v;
        }
        lastPilotMs = now;
        if (d[0] != UdpCommon.Connect || !keyMatches(d, len, 4)) return Verdict.TAKE;
        if (liveIdKnown() ? liveId == sessionId : now - sessionStartedMs < SAME_SESSION_MS) {
            liveIdIs(sessionId);
            return Verdict.SAME_SESSION;
        }
        sessionStartedMs = now;
        liveIdIs(sessionId);
        return Verdict.NEW_SESSION;
    }

    /**
     * {@link #fromOther(byte[], int, boolean, boolean, long)}, its verdict unchanged; the session it starts has this
     * id ({@link #NO_ID}: none).
     */
    public Verdict fromOther(byte[] d, int len, boolean sameAddress, boolean disarmed, long now, long sessionId) {
        Verdict v = fromOther(d, len, sameAddress, disarmed, now);
        if (v == Verdict.NEW_SESSION) liveIdIs(sessionId);
        return v;
    }

    /**
     * {@link #noSession(byte[], int, boolean, boolean, boolean, BooleanSupplier, long)}, its verdict unchanged; the
     * session it starts has this id ({@link #NO_ID}: none).
     */
    public Verdict noSession(byte[] d, int len, boolean pilotKnown, boolean samePc, boolean sameAddress,
                             BooleanSupplier disarmed, long now, long sessionId) {
        Verdict v = noSession(d, len, pilotKnown, samePc, sameAddress, disarmed, now);
        if (v == Verdict.NEW_SESSION) liveIdIs(sessionId);
        return v;
    }

    /**
     * The PC's side of the session id: the id its next Connect carries. Pure; ControlLink's session thread asks it.
     * <ul>
     * <li>One id for the Connects of one try: all those of {@link #SAME_SESSION_MS}, or of the whole round on a slow
     * path (ControlLink.connectRoundMs, as it stood when the id was first sent) - the phone answers the first and
     * drops the rest, however late they come.</li>
     * <li>A new id when that long has passed unanswered: the answer may have been lost, and the phone, holding the
     * session, would drop the same id for good.</li>
     * <li>A new id after every session that was up: this side has let that session go, so the phone must - with the
     * old id its Connects would be dropped as that session's until it timed out.</li>
     * <li>Counted up from a seed taken at random at every start of the app (SecureRandom): two runs of the PC
     * counting through the same ids would meet the phone holding one of them.</li>
     * </ul>
     */
    public static final class Ids {
        private int id;
        /** When the id was first sent, and how long it is kept unanswered. */
        private long since, keepMs;
        /** The id was a session's (or none was given out yet): the next Connect takes a new one. */
        private boolean spent = true;

        public Ids(int seed) {
            id = seed;
        }

        /**
         * A Connect goes out at now: its id, for the packet's tail.
         * @param roundMs how long the Connects of a try beginning now and their answer are given (ControlLink's
         *                connectRoundLen; 0 on a path not known slow)
         */
        public synchronized long next(long now, long roundMs) {
            if (spent || now - since >= keepMs) {
                id++;
                since = now;
                keepMs = Math.max(SAME_SESSION_MS, roundMs);
                spent = false;
            }
            return id & 0xFFFFFFFFL;
        }

        /** The phone answered and the session is up: its id is spent. */
        public synchronized void sessionUp() {
            spent = true;
        }

        /** The id of the last Connect sent - the session's, once it is up (an answer carrying another is an older try's). */
        public synchronized long current() {
            return id & 0xFFFFFFFFL;
        }
    }
}
