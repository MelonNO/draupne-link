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
 * DRAUPNE Flight: when the link's socket is opened anew ({@link LinkSocket#reopen}) because nothing leaves it (pc.55).
 * <p>
 * The rule: sends have stood still for {@link #STUCK_MS} - not one datagram out in that time - and for the last
 * {@link #HEARD_FOR_MS} of it the phone has heard packets all along (each within {@link #HEARD_MS}). Hearing matters:
 * with no coverage at all nothing arrives either, the sends wait for the network, and a new socket would help
 * nothing; and when coverage returns after such a hole the way up gets those seconds to move by itself before the
 * socket is taken for the trouble.
 * <p>
 * Heard but mute can also be the way up alone that is gone (a weak uplink at range): then the sends wait for the
 * network too, and a new socket costs something - what waited on the old one (30 packets at most) still leaves when
 * the uplink returns, in front of what the new one took. So the rule is slow on purpose: five seconds before the
 * first reopen, and when the sends stand still again before they have flowed for {@link #CALM_MS}, the next one
 * comes after {@link #AGAIN_MS} only - one more socket's worth of old video per half minute at most. A socket that
 * was the trouble flows again for a long time after its reopen, and the wait is five seconds again.
 * <p>
 * A real socket found closed (a reopen that could not bind) is tried again every second. Pure: the phone's Udp asks
 * it twice a second with its monotonic clock; the PC's tests check it.
 */
public final class SendWatch {
    public static final long STUCK_MS = 5000, AGAIN_MS = 30_000, HEARD_MS = 1500, HEARD_FOR_MS = 3000, CALM_MS = 10_000, BROKEN_RETRY_MS = 1000;
    private long waitMs = STUCK_MS;
    private long flowingSinceMs = -1, brokenTriedAtMs = -1, heardStuckSinceMs = -1;

    /**
     * @param nowMs a monotonic clock
     * @param stuckMs {@link LinkSocket#stuckMs}
     * @param heardAgoMs how long ago the last datagram arrived, any at all
     * @param broken {@link LinkSocket#broken}
     * @return whether to open the socket anew now
     */
    public boolean tick(long nowMs, long stuckMs, long heardAgoMs, boolean broken) {
        if (broken) {
            if (brokenTriedAtMs >= 0 && nowMs - brokenTriedAtMs < BROKEN_RETRY_MS) return false;
            brokenTriedAtMs = nowMs;
            return true;
        }
        brokenTriedAtMs = -1;
        if (stuckMs < 500) { // sends go out (or there is nothing to send)
            heardStuckSinceMs = -1;
            if (flowingSinceMs < 0) flowingSinceMs = nowMs;
            if (nowMs - flowingSinceMs >= CALM_MS) waitMs = STUCK_MS;
            return false;
        }
        flowingSinceMs = -1;
        // heard at every look since: one packet after a hole in the coverage is not "heard but mute" yet
        if (heardAgoMs > HEARD_MS) heardStuckSinceMs = -1;
        else if (heardStuckSinceMs < 0) heardStuckSinceMs = nowMs;
        if (stuckMs < waitMs || heardStuckSinceMs < 0 || nowMs - heardStuckSinceMs < HEARD_FOR_MS) return false;
        waitMs = AGAIN_MS;
        heardStuckSinceMs = -1;
        return true;
    }

    /** How long sends must stand still before the next reopen, ms. */
    public long waitMs() {
        return waitMs;
    }
}
