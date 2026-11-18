package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The field round of 2026-11-18: a drone asleep on purpose is silent - before the phone lets go of the flight
 * controller's port it switches Betaflight's lost-receiver sounds off (the buzzer's RX_LOST and RX_LOST_LANDING, the
 * motors' RX_LOST beacon), and writes back what was there when it wakes.
 */
class BeeperQuietTest {
    /** MSP_BEEPER_CONFIG as Betaflight answers: off flags u32, beacon tone u8, beacon off flags u32, little-endian. */
    private static byte[] config(int beeperOff, int tone, int beaconOff) {
        return new byte[]{(byte) beeperOff, (byte) (beeperOff >>> 8), (byte) (beeperOff >>> 16), (byte) (beeperOff >>> 24), (byte) tone,
                (byte) beaconOff, (byte) (beaconOff >>> 8), (byte) (beaconOff >>> 16), (byte) (beaconOff >>> 24)};
    }

    @Test
    void theLostReceiversSoundsGoOffAndNothingElseChanges() {
        // as Drone setup leaves a drone: everything off but RX_LOST (bit 1), RX_LOST_LANDING (2), RX_SET (9), ON_USB (17); both beacons on
        int keep = (1 << 1) | (1 << 2) | (1 << 9) | (1 << 17);
        int offFlags = 0x00FFFFFF & ~keep;
        byte[] read = config(offFlags, 3, 0);
        assertFalse(BeeperQuiet.isQuiet(read));
        byte[] quiet = BeeperQuiet.quiet(read);
        assertArrayEquals(config(offFlags | BeeperQuiet.RX_LOST | BeeperQuiet.RX_LOST_LANDING, 3, BeeperQuiet.RX_LOST), quiet);
        assertTrue(BeeperQuiet.isQuiet(quiet));
        assertArrayEquals(read, config(offFlags, 3, 0), "what was read is not written on");
        // RX_SET (the BEEPER button) stays as it was, for the beeper and the beacon
        assertEquals(0, quiet[1] & 0x02, "RX_SET, bit 9, still on");
    }

    @Test
    void aBetaflightDefaultWithEverythingOnIsQuietedTheSameWay() {
        byte[] quiet = BeeperQuiet.quiet(config(0, 1, 0));
        assertArrayEquals(config(0x06, 1, 0x02), quiet);
    }

    @Test
    void aReplyThatIsNotUnderstoodIsLeftAlone() {
        assertNull(BeeperQuiet.quiet(null));
        assertNull(BeeperQuiet.quiet(new byte[]{1, 2, 3, 4}), "an older Betaflight's short reply: nothing written");
        assertFalse(BeeperQuiet.understood(new byte[8]));
        assertTrue(BeeperQuiet.understood(new byte[9]));
        assertFalse(BeeperQuiet.isQuiet(null));
        assertNull(BeeperQuiet.asRead(new byte[3]));
        assertEquals(9, BeeperQuiet.asRead(new byte[12]).length, "bytes a newer Betaflight adds are not written back");
    }

    @Test
    void whatWasReadIsKeptAsTextAndComesBackTheSame() {
        byte[] read = config(0x00FDF9F9, 5, 0x00000200);
        String hex = BeeperQuiet.hex(read);
        assertEquals(18, hex.length());
        assertArrayEquals(read, BeeperQuiet.fromHex(hex));
        assertNull(BeeperQuiet.fromHex(""));
        assertNull(BeeperQuiet.fromHex("zz"));
        assertNull(BeeperQuiet.fromHex("0102"));
        assertEquals(184, BeeperQuiet.MSP_BEEPER_CONFIG);
        assertEquals(185, BeeperQuiet.MSP_SET_BEEPER_CONFIG);
    }
}
