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
 * DRAUPNE Flight pc.39: the check interval of deep sleep and overwatch as it goes on the wire (POWER_MODE's and
 * OVERWATCH's checkIntervalMin byte, the PHONE_POWER record, the sleep hello's nextCheckMin). 1..254 are minutes
 * between listening windows; {@link #ALWAYS} (CHECK EVERY "0 min") is a window that opens after the countdown and
 * never closes - no alarms, the socket open, a "still here" every {@link #ALWAYS_HELLO_MS}, a Connect wakes the
 * phone at once. 0 means "keep the interval there is" (pc.21), which is why "0 min" isn't sent as 0. An older phone
 * would read 255 as 255 minutes, so the PC sends it only to pc.39 on ({@link #forPhone}).
 * Pure: both apps use it, the PC's tests the cases.
 */
public final class SleepCheck {
    /** Always listening: the listening window stays open. */
    public static final int ALWAYS = 255;
    /** The first phone app that knows {@link #ALWAYS}. */
    public static final int ALWAYS_MIN_PC_BUILD = 39;
    /** What an older phone is sent instead of {@link #ALWAYS}: the shortest interval. */
    public static final int FALLBACK_MIN = 1;
    /** Always listening: the "still here" this often (the PC treats it as a check every minute). */
    public static final long ALWAYS_HELLO_MS = 60_000;
    /**
     * Before that, for this long after the window opens, the hellos come as close as a timed window's (1.5 s): a tunnel
     * that slept with the phone drops the first packets after a wake (pc.30).
     */
    public static final long ALWAYS_HELLO_BURST_MS = 5_000;
    /**
     * Always listening: the window opens this long after the sleep began, not at once - the PC's link must first see
     * the phone gone and stay quiet, or its reconnecting Connect would wake the phone straight back up.
     */
    public static final long ALWAYS_OPEN_AFTER_MS = 10_000;

    private SleepCheck() {
    }

    public static boolean isAlways(int intervalMin) {
        return intervalMin == ALWAYS;
    }

    /** pc.50: a timed window's hellos after its first {@link #ALWAYS_HELLO_BURST_MS}: this far apart (was 1.5 s all along). */
    public static final long WINDOW_HELLO_LATER_MS = 5_000;

    /**
     * pc.50: whether the window's hello task (every 1.5 s) sends one now. A timed window: 1.5 s apart for the first 5 s
     * (a tunnel that slept drops the first packets), then 5 s apart - 7 a window instead of 14, the radio quieter. Always
     * listening: the same burst, then once a minute; in deep sleep (the processor sleeping, pc.50) the minute's hello
     * comes from an alarm instead - never from this task.
     * @param sinceOpenMs since the window opened
     * @param sinceLastMs since the last hello (Long.MAX_VALUE: none yet)
     * @param alarmHellos the minute's hellos come from the alarm
     */
    public static boolean helloDue(boolean always, boolean alarmHellos, long sinceOpenMs, long sinceLastMs) {
        if (sinceOpenMs <= ALWAYS_HELLO_BURST_MS) return true;
        if (!always) return sinceLastMs >= WINDOW_HELLO_LATER_MS;
        return !alarmHellos && sinceLastMs >= ALWAYS_HELLO_MS;
    }

    /**
     * The interval to send a phone app of this build (FcTelemetry.pcBuild; -1 unknown): {@link #ALWAYS} only to pc.39
     * on, {@link #FALLBACK_MIN} to anything older or not known; minutes as they are.
     */
    public static int forPhone(int intervalMin, int phonePcBuild) {
        if (!isAlways(intervalMin)) return intervalMin;
        return phonePcBuild >= ALWAYS_MIN_PC_BUILD ? ALWAYS : FALLBACK_MIN;
    }

    /** Minutes between the phone's check-ins, for the PC's lateness rule: always listening says hello every minute. */
    public static int checkInMinutes(int intervalMin) {
        return isAlways(intervalMin) ? 1 : Math.max(1, intervalMin);
    }

    /** "1 min", "5 min", or "Constant" for always listening (as the CHECK EVERY list shows it; 1.0.56: was "0 min"). */
    public static String label(int intervalMin) {
        return isAlways(intervalMin) ? "Constant" : intervalMin + " min";
    }
}
