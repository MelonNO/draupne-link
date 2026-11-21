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
 * DRAUPNE Flight (F-070d): upstream's pace of the stick writes - a frame that comes within half a frame time of the
 * one before is dropped - on a clock that cannot hold the sticks back.
 * <p>
 * Found by a reader of the code (2026-11-22): the pace was kept on the wall clock. The network corrects that clock,
 * in flight too, and set back N seconds every frame was "too soon after the last one" until the clock had caught up:
 * no sticks to the flight controller for N seconds. MSP only below the PC's rate of 255 (at 255 the phone paces
 * nothing: the PC numbers its frames), MAVLink at every rate.
 * <p>
 * Now the callers pass a monotonic clock (SystemClock.elapsedRealtime), and the rule itself holds nothing back when
 * a clock reads less than at the frame before: that frame goes on and the pace is kept from there.
 * <p>
 * Pure. One thread takes frames (the link's receive thread; the idle sticks under the same lock); the pace may be
 * set from another.
 */
public final class RcPace {
    private volatile int minPeriodMs;
    private long lastMs;
    private boolean any;

    /** Frames closer together than this are dropped, ms; 0 or less: none is. */
    public void setMinPeriod(int ms) {
        minPeriodMs = ms;
    }

    /** Whether any frame can be dropped: when not, the caller need not read a clock at all. */
    public boolean pacing() {
        return minPeriodMs > 0;
    }

    /** A frame at nowMs: whether it goes on to the flight controller. */
    public boolean take(long nowMs) {
        int min = minPeriodMs;
        if (min > 0 && any && nowMs >= lastMs && nowMs - lastMs < min) return false;
        lastMs = nowMs;
        any = true;
        return true;
    }
}
