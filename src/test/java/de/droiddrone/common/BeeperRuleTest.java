package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The field round of 2026-11-18, the pilot: "the esc beeper still goes off when the drone is not controlled" (after
 * DISCONNECT / standby, in deep sleep and overwatch; the buzzer and the motors) - and "it should beep if link is lost,
 * usb connection to FC is lost, or out of action is pressed, or beeper is pressed", stopping "when link and FC
 * connection is back".
 */
class BeeperRuleTest {
    @Test
    void aPhoneNobodyHasConnectedToIsFedIdleSticksAndSilent() {
        BeeperRule r = new BeeperRule();
        r.session(false);
        assertTrue(r.feedIdle(0), "standby from FLEET, or just started: silent");
        assertFalse(r.linkLost());
    }

    @Test
    void afterThePcsDisconnectTheDroneStaysSilent() {
        BeeperRule r = new BeeperRule();
        r.sessionTaken();
        r.session(true);
        r.disconnectSaid();
        r.session(false);
        assertTrue(r.feedIdle(1000), "ended on purpose");
        assertFalse(r.linkLost());
    }

    @Test
    void aLinkThatGoesWithoutADisconnectBeepsUntilAPcConnectsAgain() {
        BeeperRule r = new BeeperRule();
        r.sessionTaken();
        r.session(true);
        assertTrue(r.feedIdle(0), "connected, disarmed, no sticks: idle sticks, silent");
        r.session(false); // timed out
        assertTrue(r.linkLost());
        assertFalse(r.feedIdle(5000), "no idle sticks: Betaflight beeps for its lost receiver");
        assertFalse(r.feedIdle(3_600_000), "for as long as nobody comes back");
        r.sessionTaken();
        r.session(true);
        assertFalse(r.linkLost());
        assertTrue(r.feedIdle(3_600_100), "the link is back: silent");
    }

    @Test
    void aDisconnectHeardAfterTheLinkWasLostSilencesItToo() {
        BeeperRule r = new BeeperRule();
        r.sessionTaken();
        r.session(true);
        r.session(false);
        assertTrue(r.linkLost());
        r.disconnectSaid(); // a late one, or the PC's next try
        assertTrue(r.feedIdle(0));
    }

    @Test
    void thePcsBeeperHoldsTheIdleSticksBackForAsLongAsAskedTenSecondsAtMost() {
        BeeperRule r = new BeeperRule();
        r.sessionTaken();
        r.session(true);
        r.beep(1000, 4000);
        assertFalse(r.feedIdle(1000));
        assertTrue(r.beeping(4999));
        assertFalse(r.feedIdle(4999));
        assertTrue(r.feedIdle(5000), "over: silent again");
        r.beep(10_000, 60_000);
        assertFalse(r.feedIdle(10_000 + BeeperRule.BEEP_MAX_MS - 1));
        assertTrue(r.feedIdle(10_000 + BeeperRule.BEEP_MAX_MS), "one press never beeps longer than the button's own limit");
        r.beep(30_000, 5000);
        r.beep(31_000, 0); // let go
        assertTrue(r.feedIdle(31_000));
    }

    @Test
    void aSleepSwitchesTheLostSignalSoundsOffButNotForADroneOutOfAction() {
        assertTrue(BeeperRule.muteForSleep(false), "deep sleep and overwatch: silent");
        assertFalse(BeeperRule.muteForSleep(true), "OUT OF ACTION: it is to be found by its beeping");
    }

    @Test
    void thePhonesOwnIdleSticksAreLowAndCentredAndNoneWhenTheyCouldArm() {
        short[] idle = BeeperRule.ownIdle(frame -> false);
        assertNotNull(idle);
        assertEquals(BeeperRule.CHANNELS, idle.length);
        assertArrayEquals(new short[]{1500, 1500, 1500, 1000}, java.util.Arrays.copyOf(idle, 4), "roll, pitch, yaw centred; throttle low");
        for (int i = 4; i < idle.length; i++) assertEquals(1000, idle[i], "AUX" + (i - 3) + " low (BEEPER too)");
        // checked against Betaflight's own ARM ranges: ARM on AUX1 from 1300 up can't be reached by 1000 ...
        int[][] armHigh = {{0, 1300, 2100}};
        assertNotNull(BeeperRule.ownIdle(frame -> ArmRanges.mayArm(frame, frame.length, armHigh)));
        // ... an ARM range that holds 1000, or ranges not read yet: no idle sticks of the phone's own
        int[][] armLow = {{0, 900, 1300}};
        assertNull(BeeperRule.ownIdle(frame -> ArmRanges.mayArm(frame, frame.length, armLow)));
        assertNull(BeeperRule.ownIdle(frame -> ArmRanges.mayArm(frame, frame.length, null)));
    }
}
