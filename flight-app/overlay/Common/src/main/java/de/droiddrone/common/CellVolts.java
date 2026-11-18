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
 * The drone's battery by its voltage per cell (the pilot, 2026-11-18, after flying Li-ion packs: "lower the low
 * battery warning to be empty at 2,8 and low at 3,2", the per cent "should follow a LIION curve"). One place for the
 * limits and the charge every screen shows: the warnings, the gauges, the per cent, the time and range left, and what
 * Drone setup writes to the flight controller.
 * The curve is a Li-ion cell's resting voltage against its charge, straight between the points - an estimate: a pack
 * sags under load, so the figure reads low while the drone flies and comes back when it rests.
 * Pure: both apps may use it, the PC's tests the cases.
 */
public final class CellVolts {
    /** A full cell. */
    public static final double FULL = 4.2;
    /** At or under this: LOW BATTERY. */
    public static final double LOW = 3.2;
    /** At or under this: empty. */
    public static final double EMPTY = 2.8;

    /** Betaflight's unit for its cell limits: 0.01 V. */
    public static final int LOW_CENTIVOLTS = 320, EMPTY_CENTIVOLTS = 280;

    private static final double[] VOLTS = {2.8, 3.0, 3.2, 3.4, 3.6, 3.8, 4.0, 4.2};
    private static final double[] FILL = {0.00, 0.03, 0.09, 0.20, 0.40, 0.62, 0.84, 1.00};

    private CellVolts() {
    }

    /** The charge, 0..1; NaN for an unknown voltage. */
    public static double fill(double cellVolts) {
        if (Double.isNaN(cellVolts)) return Double.NaN;
        if (cellVolts <= VOLTS[0]) return 0;
        for (int i = 1; i < VOLTS.length; i++) {
            if (cellVolts <= VOLTS[i]) {
                double part = (cellVolts - VOLTS[i - 1]) / (VOLTS[i] - VOLTS[i - 1]);
                return FILL[i - 1] + part * (FILL[i] - FILL[i - 1]);
            }
        }
        return 1;
    }

    /** The charge in per cent, 0..100; -1 for an unknown voltage. */
    public static int percent(double cellVolts) {
        double fill = fill(cellVolts);
        return Double.isNaN(fill) ? -1 : (int) Math.round(fill * 100);
    }

    /** LOW BATTERY: at or under {@link #LOW} (an unknown voltage is not low). */
    public static boolean low(double cellVolts) {
        return cellVolts <= LOW;
    }

    /** Empty: at or under {@link #EMPTY}. */
    public static boolean empty(double cellVolts) {
        return cellVolts <= EMPTY;
    }
}
