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
 * DRAUPNE Flight pc.36: when overwatch sleep gives way to deep sleep by itself (the pilot's floor): the battery under
 * {@link #BATTERY_MIN} % and not charging, or the phone hot - Android's thermal status at SEVERE or worse (API 29 on),
 * or, where that isn't known, the battery at {@link #HOT_DECI_C} or more. A camera running for hours in a closed box in
 * the sun is the case it is there for. One reading is not enough: {@link #READINGS} in a row (they come
 * {@link #CHECK_MS} apart), so a glitch of the fuel gauge or one warm moment doesn't end the watch.
 * Pure: the phone feeds it the readings, the PC's tests the cases.
 */
public final class PowerFloor {
    public static final int REASON_NONE = 0, REASON_BATTERY = 1, REASON_HEAT = 2;
    public static final int BATTERY_MIN = 20;
    /** PowerManager.THERMAL_STATUS_SEVERE (the API's own constant, here so the PC can test with it). */
    public static final int THERMAL_SEVERE = 3;
    /** Battery temperature in tenths of a degree C (BatteryManager.EXTRA_TEMPERATURE's unit): 45 C. */
    public static final int HOT_DECI_C = 450;
    public static final long CHECK_MS = 30_000;
    public static final int READINGS = 2;
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private int batteryLow, hot;

    /**
     * One reading.
     * @param batteryPercent 0..100, or UNKNOWN
     * @param thermalStatus  PowerManager's thermal status (0 none .. 6 shutdown), or UNKNOWN (before Android 10)
     * @param batteryDeciC   the battery's temperature in tenths of a degree, or UNKNOWN
     * @return the reason to leave for deep sleep now, or REASON_NONE
     */
    public int check(int batteryPercent, boolean charging, int thermalStatus, int batteryDeciC) {
        boolean low = batteryPercent != UNKNOWN && batteryPercent >= 0 && batteryPercent < BATTERY_MIN && !charging;
        boolean heat = thermalStatus != UNKNOWN ? thermalStatus >= THERMAL_SEVERE
                : batteryDeciC != UNKNOWN && batteryDeciC >= HOT_DECI_C;
        batteryLow = low ? batteryLow + 1 : 0;
        hot = heat ? hot + 1 : 0;
        if (hot >= READINGS) return REASON_HEAT; // heat first: it harms the phone, a low battery only ends the watch
        if (batteryLow >= READINGS) return REASON_BATTERY;
        return REASON_NONE;
    }

    public void reset() {
        batteryLow = 0;
        hot = 0;
    }

    public static String describe(int reason) {
        switch (reason) {
            case REASON_BATTERY:
                return "battery under " + BATTERY_MIN + " %";
            case REASON_HEAT:
                return "phone too hot";
            default:
                return "";
        }
    }
}
