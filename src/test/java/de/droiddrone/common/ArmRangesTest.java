package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** H1 (1.0.48 review): the phone refuses idle sticks that Betaflight's own ARM ranges would arm on. */
class ArmRangesTest {
    /** MSP_MODE_RANGES as Betaflight writes it: 20 slots of [mode][aux][start step][end step], empty ones ARM 0 0 0. */
    private static byte[] ranges(int[]... used) {
        byte[] p = new byte[20 * 4];
        for (int i = 0; i < used.length; i++) {
            for (int k = 0; k < 4; k++) p[i * 4 + k] = (byte) used[i][k];
        }
        return p;
    }

    /** A step from us: (us - 900) / 25. */
    private static int step(int us) {
        return (us - 900) / 25;
    }

    /** The idle frame DRAUPNE Link builds with ARM saved on AUX1 (forced low there), the radio's AUX2 high. */
    private static final short[] IDLE = {1500, 1500, 1500, 1000, 1000, 2000, 1500, 1000};

    @Test
    void emptySlotsAreNotArm() {
        int[][] none = ArmRanges.parse(ranges());
        assertEquals(0, none.length);
        assertFalse(ArmRanges.mayArm(IDLE, IDLE.length, none), "ARM on no switch: nothing arms");
        assertNull(ArmRanges.parse(new byte[]{0, 1, 2}), "not understood");
    }

    @Test
    void anIdleFrameIsRefusedWhenArmMovedToASwitchItCarriesOn() {
        // Drone setup saw ARM on AUX1; since then it was moved to AUX2 (aux index 1), high = armed
        int[][] moved = ArmRanges.parse(ranges(new int[]{0, 1, step(1700), step(2100)}, new int[]{1, 0, step(1300), step(1700)}));
        assertEquals(1, moved.length, "ANGLE's slot is not ARM");
        assertTrue(ArmRanges.mayArm(IDLE, IDLE.length, moved), "the radio's AUX2 is high: that would arm");
        int[][] stayed = ArmRanges.parse(ranges(new int[]{0, 0, step(1700), step(2100)}));
        assertFalse(ArmRanges.mayArm(IDLE, IDLE.length, stayed), "ARM where the PC thinks: its low value is off");
    }

    @Test
    void theRangeIsBetaflightsStartInclusiveEndExclusiveOnAClampedValue() {
        int[][] low = ArmRanges.parse(ranges(new int[]{0, 0, step(900), step(1100)})); // inverted: arms low
        short[] f = IDLE.clone();
        f[4] = 1000;
        assertTrue(ArmRanges.mayArm(f, f.length, low));
        f[4] = 1100;
        assertFalse(ArmRanges.mayArm(f, f.length, low), "the end is exclusive");
        f[4] = 890; // a valid pulse under 900: Betaflight clamps it to 900
        assertTrue(ArmRanges.mayArm(f, f.length, low));
        int[][] top = ArmRanges.parse(ranges(new int[]{0, 0, step(1700), step(2100)}));
        f[4] = 2110; // clamped to 2099
        assertTrue(ArmRanges.mayArm(f, f.length, top));
    }

    @Test
    void aChannelNotSentOrSentAsZeroIsUnknownAndRefused() {
        int[][] aux6 = ArmRanges.parse(ranges(new int[]{0, 5, step(1700), step(2100)})); // ARM on AUX6: channel 9
        assertTrue(ArmRanges.mayArm(IDLE, IDLE.length, aux6), "the frame stops at 8: held by Betaflight, unknown");
        short[] zero = new short[12];
        System.arraycopy(IDLE, 0, zero, 0, IDLE.length);
        assertTrue(ArmRanges.mayArm(zero, zero.length, aux6), "0 is no pulse: unknown");
        zero[9] = 1000;
        assertFalse(ArmRanges.mayArm(zero, zero.length, aux6));
        assertTrue(ArmRanges.mayArm(zero, 8, aux6), "only the count sent counts");
    }

    @Test
    void unknownRangesRefuseEverything() {
        assertTrue(ArmRanges.mayArm(IDLE, IDLE.length, null));
    }
}
