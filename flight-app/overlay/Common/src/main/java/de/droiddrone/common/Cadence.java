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
 * DRAUPNE Flight: "every period ms" for a loop that polls at its own tick, on the clock instead of a count of ticks. A
 * count runs slow when the loop sleeps longer than asked, and a mode that changes the tick changes what a count means
 * (1.0.53: standby's status poll stopped on a count that skipped the multiples of 5). Due once the period is up, half a
 * tick early counting (slackMs: a loop that sleeps the period lands on it every time); it keeps its phase, but after a
 * stall it starts over from now instead of firing the missed ones in a row. Due at the first call. Pure: the caller
 * passes its monotonic clock; one thread.
 */
public final class Cadence {
    private long dueMs;
    private boolean started;

    /** Whether the period is up at nowMs; if so, the next one starts. */
    public boolean due(long nowMs, long periodMs, long slackMs) {
        if (started && nowMs < dueMs - slackMs) return false;
        dueMs = started && nowMs - dueMs < periodMs ? dueMs + periodMs : nowMs + periodMs;
        started = true;
        return true;
    }

    /** Due again at the next call. */
    public void restart() {
        started = false;
    }
}
