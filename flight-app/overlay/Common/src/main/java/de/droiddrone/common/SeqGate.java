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
 * DRAUPNE fleet: one sender's 16-bit sequence on an unnumbered stream (FleetStatus, GroupOrder, ExitPlan, TRAFFIC, the
 * launch status) - a datagram delayed behind newer ones is older than the newest seen and dropped; before, a late report
 * overwrote a newer one and counted as fresh, and a follower took an older order. Wrapping (serial-number arithmetic).
 * Restart-safe: senders start at a random number ({@link #randomStart}); one older by more than {@link #WINDOW} is a
 * sender started again, as is anything after {@link #RESYNC_MS} without a newer one. A copy (the same number) is taken:
 * it says the same. -1 (an older peer with no sequence): always taken. Thread-safe. Java 11.
 */
public final class SeqGate {
    /** Older than the newest by at most this many: a delayed datagram, dropped. */
    public static final int WINDOW = 1024;
    /** Nothing newer for this long: the next is taken whatever its number (the sender started again near the old one). */
    public static final long RESYNC_MS = 3000;

    private int last = -1;
    private long lastAt;

    /** @return true if this datagram is the newest (or a copy of it): take it; false: older, drop it */
    public synchronized boolean take(int seq, long nowMs) {
        if (seq < 0) return true;
        seq &= 0xffff;
        int d = last < 0 ? 1 : (seq - last) & 0xffff;
        boolean older = d >= 0x8000 && 0x10000 - d <= WINDOW;
        if (older && nowMs - lastAt < RESYNC_MS) return false;
        last = seq;
        lastAt = nowMs;
        return true;
    }

    /** A sender's first number: random at every start, so a restarted sender is no copy of its old self. */
    public static int randomStart() {
        return new java.security.SecureRandom().nextInt(0x10000);
    }
}
