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
 * DRAUPNE Flight pc.54: a drone that is put to rest on purpose is silent (the pilot, 2026-11-18: "the esc beeper still
 * goes off when the drone is not controlled" - after deep sleep and overwatch, the buzzer and the motors both).
 * Deep sleep and overwatch let go of the flight controller's USB port; Betaflight then sees its receiver lost and
 * sounds its RX_LOST beeper (the buzzer) and, where it is on, the DShot beacon (the motors). Before the port closes
 * the phone switches both off in the flight controller's memory - not saved: a power cycle brings them back, and so
 * does the phone when it wakes ({@link #MSP_SET_BEEPER_CONFIG} with what it read before).
 * Betaflight's MSP_BEEPER_CONFIG, read and written alike: {@code [beeper off flags u32][DShot beacon tone u8][DShot
 * beacon off flags u32]}, little-endian; a flag set = that sound off; a condition's flag is bit (number - 1), and
 * RX_LOST (2) and RX_LOST_LANDING (3) are among the first three in every Betaflight this app meets (the bits that
 * moved between 4.5 and 2025.12 are further up).
 * Pure: the phone sends what this builds, the PC's tests the cases.
 */
public final class BeeperQuiet {
    public static final int MSP_BEEPER_CONFIG = 184, MSP_SET_BEEPER_CONFIG = 185;
    /** The receiver is lost / its failsafe landing: the beeper's flags. */
    public static final int RX_LOST = 1 << 1, RX_LOST_LANDING = 1 << 2;
    /** The whole reply: flags, tone, beacon flags. */
    static final int LENGTH = 9;

    private BeeperQuiet() {
    }

    /** The reply is one this class understands (an older Betaflight's has no beacon flags: left alone). */
    public static boolean understood(byte[] config) {
        return config != null && config.length >= LENGTH;
    }

    /**
     * What to write so the lost receiver makes no sound: the config as read, with RX_LOST and RX_LOST_LANDING off for
     * the beeper and RX_LOST off for the beacon. Null when the reply isn't understood (nothing is written then).
     */
    public static byte[] quiet(byte[] config) {
        if (!understood(config)) return null;
        byte[] out = java.util.Arrays.copyOf(config, LENGTH);
        putInt(out, 0, getInt(out, 0) | RX_LOST | RX_LOST_LANDING);
        putInt(out, 5, getInt(out, 5) | RX_LOST);
        return out;
    }

    /** The config already has all of that off (what was read is then not "the pilot's own" to put back). */
    public static boolean isQuiet(byte[] config) {
        if (!understood(config)) return false;
        return (getInt(config, 0) & (RX_LOST | RX_LOST_LANDING)) == (RX_LOST | RX_LOST_LANDING) && (getInt(config, 5) & RX_LOST) != 0;
    }

    /** The first nine bytes, to write back as they were read; null when not understood. */
    public static byte[] asRead(byte[] config) {
        return understood(config) ? java.util.Arrays.copyOf(config, LENGTH) : null;
    }

    /** "0a1b..." for the phone's settings (the config read before the first quiet, kept until it is written back). */
    public static String hex(byte[] config) {
        if (config == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : config) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** The bytes of {@link #hex}; null for anything else. */
    public static byte[] fromHex(String hex) {
        if (hex == null || hex.length() != LENGTH * 2) return null;
        byte[] out = new byte[LENGTH];
        try {
            for (int i = 0; i < LENGTH; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static int getInt(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
        b[at + 3] = (byte) (v >>> 24);
    }
}
