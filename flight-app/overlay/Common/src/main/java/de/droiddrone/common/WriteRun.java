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

import java.util.function.BooleanSupplier;

/**
 * DRAUPNE Flight (F-070c): when failed writes to the flight controller's USB port count as a lost device.
 * <p>
 * Found by a reader of the code (2026-11-22): a write that failed was logged and no more; only a failed read closed
 * the port and looked for the device again. A port whose reads stand still while every write fails stayed as it was -
 * no stick reached the flight controller again until the app was started anew.
 * <p>
 * The rule, as careful as it has to be with a drone in the air - opening the port anew costs the sticks for as long
 * as it takes, so a port that still works must never be closed for a few late writes:
 * <ul>
 * <li>a write that goes out starts the count over ({@link #wentOut});</li>
 * <li>from the second failure in a row the caller is asked whether the device is still on USB (at most every
 * {@link #ASK_MS}: it asks Android) - gone, it is lost at once, as a failed read finds it;</li>
 * <li>still there, it is lost after {@link #FAILURES} failures in a row that span {@link #RUN_MS}: nothing went
 * out in that time. A stick write gives up after 50 ms and a request after 20 ms, so writes that only time out are
 * six or more in a row by then; errors that come back at once (250 stick writes a second) need the time as well - a
 * burst of failed transfers is no lost port.</li>
 * </ul>
 * Why 300 ms: Betaflight counts the receiver lost after 100 ms without sticks and holds the last ones for 300 ms -
 * until then a stall that ends by itself has cost nothing, after it nothing is left to lose by opening the port
 * anew; and more than a second is left for that before Betaflight's stage 2 (1.5 s: land or disarm). The reader
 * waits as long: four failed reads, 100 ms apart.
 * <p>
 * Pure; one writer thread, one of these for each writer. Times: any monotonic clock, ms.
 */
public final class WriteRun {
    public static final int FAILURES = 4;
    public static final long RUN_MS = 300, ASK_MS = 100;

    private int failed;
    private long sinceMs, askedMs;

    /** A write went out. */
    public void wentOut() {
        failed = 0;
    }

    /**
     * A write failed: its time ran out, or an error.
     * @param attached whether the device is still on USB; not asked at a first failure
     * @return whether the device counts as lost now
     */
    public boolean failed(long nowMs, BooleanSupplier attached) {
        if (failed == 0) sinceMs = nowMs;
        failed++;
        if (failed == 2 || failed > 2 && nowMs - askedMs >= ASK_MS) {
            askedMs = nowMs;
            if (!attached.getAsBoolean()) return true;
        }
        return failed >= FAILURES && nowMs - sinceMs >= RUN_MS;
    }
}
