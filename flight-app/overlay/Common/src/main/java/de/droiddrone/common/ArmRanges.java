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
 * DRAUPNE Flight pc.38: where the flight controller's own ARM mode sits, read from MSP_MODE_RANGES, so the phone can
 * refuse an idle-sticks frame (DRAUPNE Link's IDLE_STICKS 0x78) that would turn ARM on. The PC builds that frame from
 * where Drone setup last saw ARM; moved in Configurator since, the radio's real ARM switch passed through it (1.0.48's
 * H1). Betaflight's and INAV's layout is the same: per slot [permanent mode id u8][aux channel index u8][start step u8]
 * [end step u8], a step being 25 us from 900; ARM is mode 0, and a slot is in use only with start &lt; end (an empty
 * slot reads as ARM 0 0 0). The check is Betaflight's isRangeActive: value constrained to 900..2099, start inclusive,
 * end exclusive. A channel the frame doesn't carry, or carries as no valid pulse (0), Betaflight holds at a value the
 * phone can't know: that counts as possibly armed. Pure: the phone feeds it, the PC's tests check it.
 */
public final class ArmRanges {
    /** MSP_MODE_RANGES (34), not among upstream's FcCommon codes. */
    public static final short MSP_MODE_RANGES = 34;
    /** Betaflight's / INAV's permanent id for ARM. */
    static final int MODE_ARM = 0;
    /** Betaflight's rx_min_usec / rx_max_usec defaults: a value outside isn't a valid pulse, and is held. */
    static final int PULSE_MIN = 885, PULSE_MAX = 2115;

    private ArmRanges() {
    }

    /**
     * The ARM slots in use from an MSP_MODE_RANGES reply: {aux index (0 = AUX1), start us, end us (exclusive)} each.
     * An empty array when ARM is on no switch; null when the reply isn't understood.
     */
    public static int[][] parse(byte[] payload) {
        if (payload == null || payload.length % 4 != 0) return null;
        int n = 0;
        int[][] all = new int[payload.length / 4][];
        for (int i = 0; i + 3 < payload.length; i += 4) {
            int mode = payload[i] & 0xff, aux = payload[i + 1] & 0xff;
            int start = payload[i + 2] & 0xff, end = payload[i + 3] & 0xff;
            if (mode != MODE_ARM || start >= end) continue;
            all[n++] = new int[]{aux, 900 + start * 25, 900 + end * 25};
        }
        int[][] arm = new int[n][];
        System.arraycopy(all, 0, arm, 0, n);
        return arm;
    }

    /**
     * Whether this frame (PC order: roll, pitch, yaw, throttle, AUX1 ... - Betaflight's own channel order once the
     * phone has applied its channel map) could turn ARM on with these slots. Unknown slots (null): yes.
     */
    public static boolean mayArm(short[] frame, int count, int[][] armSlots) {
        if (armSlots == null || frame == null) return true;
        for (int[] slot : armSlots) {
            int channel = 4 + slot[0];
            if (channel >= count || channel >= frame.length) return true; // not sent: held at what nobody knows
            int v = frame[channel];
            if (v < PULSE_MIN || v > PULSE_MAX) return true; // no valid pulse (0): held too
            v = Math.max(900, Math.min(2099, v));
            if (v >= slot[1] && v < slot[2]) return true;
        }
        return false;
    }
}
