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

import java.util.Arrays;

/**
 * DRAUPNE Flight (F-069): the beeper settings a sleep switched off ({@link BeeperQuiet}) surely back after it, and
 * only on the board they were read from.
 * <p>
 * Found by a reader of the code (2026-11-22): at the wake the phone deleted the settings it had kept, then wrote them
 * back once - through the one-place slot the channel map's request shares, with a 20 ms limit, no second try and no
 * look at what the flight controller then had. One write lost, and the lost-receiver buzzer and the motors' beacon
 * stayed off until the flight controller lost power: a drone that can no longer be found by its sound. And the kept
 * settings went to whichever flight controller was on the port at the wake.
 * <p>
 * The rule, at every read of the beeper settings while awake ({@link #read}): the kept settings are written, the
 * flight controller is asked again, and they count as back - and are forgotten - only when what it answers equals
 * them. A write that did not hold is made again, one every {@link #WRITE_GAP_MS} at the most and
 * {@link #MAX_WRITES} in one connection of the flight controller; then it is said (once) and left until the next
 * connection, the settings still kept. They go to one board only: the one whose id ({@link #boardId}) was kept
 * with them. A board that is not identified yet is not written to; another board never.
 * <p>
 * Settings kept by an app from before this rule carry no board. They go back only to a board that is exactly as that
 * quiet left it, which is then theirs ({@link #ADOPT}); for any other board they are forgotten.
 * <p>
 * Going to sleep the same care: the "off" write is read back too ({@link #holds}, {@link #QUIET_TRIES}).
 * <p>
 * Pure, with one connection's memory ({@link #restart} at each new one). Times: any monotonic clock, ms.
 */
public final class BeeperBack {
    /** Betaflight's MSP_UID: its processor's unique id, 12 bytes. */
    public static final int MSP_UID = 160;
    /** Writes in one connection of the flight controller before it is said that the settings do not hold. */
    public static final int MAX_WRITES = 5;
    /** No second write sooner after one: that one's read-back comes first. */
    public static final long WRITE_GAP_MS = 400;
    /** Going to sleep: how often the "off" write is made, and how long each waits for its read-back, ms. */
    public static final int QUIET_TRIES = 3;
    public static final long QUIET_WAIT_MS = 150;

    /**
     * What {@link #read} says: nothing is kept; not now (the board is not identified, the answer not understood, the
     * last write's read-back still to come); write the kept settings and ask again; they are back - forget them;
     * another board's - leave it; the writes did not hold; the kept settings fit no board - forget them; kept without
     * a board and this is theirs - keep its id with them, and write.
     */
    public static final int NOTHING = 0, WAIT = 1, WRITE = 2, DONE = 3, OTHER_BOARD = 4, GAVE_UP = 5, FORGET = 6, ADOPT = 7;

    private int writes;
    private long lastWriteMs;
    private int said;

    /** The board's id: the first 12 bytes of MSP_UID's answer as hex; null when there are fewer, or all are zero. */
    public static String boardId(byte[] uid) {
        if (uid == null || uid.length < 12) return null;
        boolean zero = true;
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 12; i++) {
            if (uid[i] != 0) zero = false;
            sb.append(String.format("%02x", uid[i] & 0xff));
        }
        return zero ? null : sb.toString();
    }

    /** What the flight controller answers is what was written (its first nine bytes; more are a newer Betaflight's). */
    public static boolean holds(byte[] want, byte[] read) {
        return want != null && BeeperQuiet.understood(read) && Arrays.equals(BeeperQuiet.asRead(read), want);
    }

    /** The board has exactly what quieting the kept settings leaves: it is the one that was quieted. */
    private static boolean asTheQuietLeftIt(byte[] kept, byte[] read) {
        return holds(BeeperQuiet.quiet(kept), read);
    }

    /**
     * A read of the beeper settings while awake, with settings kept from before a sleep.
     * @param kept the kept settings (BeeperQuiet.asRead), null when none
     * @param keptBoard the id of the board they were read from; empty when an app before this rule kept them
     * @param board the id of the board on the port now, null while it is not read
     * @param read its beeper settings as it answers now
     */
    public synchronized int read(byte[] kept, String keptBoard, String board, byte[] read, long nowMs) {
        if (kept == null) return NOTHING;
        if (board == null || !BeeperQuiet.understood(read)) return WAIT;
        boolean adopt = false;
        if (keptBoard == null || keptBoard.isEmpty()) {
            if (holds(kept, read)) return DONE;
            if (!asTheQuietLeftIt(kept, read)) return FORGET;
            adopt = true;
        } else if (!keptBoard.equals(board)) {
            return OTHER_BOARD;
        }
        if (holds(kept, read)) return DONE;
        if (writes > 0 && nowMs - lastWriteMs < WRITE_GAP_MS) return WAIT;
        if (writes >= MAX_WRITES) return GAVE_UP;
        writes++;
        lastWriteMs = nowMs;
        return adopt ? ADOPT : WRITE;
    }

    /** Writes asked for on this connection. */
    public synchronized int writes() {
        return writes;
    }

    /** True the first time it is asked for this answer on this connection: a line in the log, not one a second. */
    public synchronized boolean sayOnce(int what) {
        int bit = 1 << what;
        if ((said & bit) != 0) return false;
        said |= bit;
        return true;
    }

    /** The flight controller's port was opened anew - it may be another board: tried, and said, anew. */
    public synchronized void restart() {
        writes = 0;
        lastWriteMs = 0;
        said = 0;
    }

    /**
     * Before a sleep's quiet: whether the settings read now are the ones to keep for the wake. Not when this board's
     * are kept already and what it has now is quiet: that is the quiet of a sleep before, not the pilot's own. With
     * another board's kept (or none) this board's are: the phone keeps one board's settings, the one it quiets.
     */
    public static boolean keepWhatIsRead(byte[] read, String board, byte[] kept, String keptBoard) {
        if (kept == null) return true;
        boolean thisBoards = keptBoard == null || keptBoard.isEmpty() ? asTheQuietLeftIt(kept, read) : keptBoard.equals(board);
        return !thisBoards || !BeeperQuiet.isQuiet(read);
    }
}
