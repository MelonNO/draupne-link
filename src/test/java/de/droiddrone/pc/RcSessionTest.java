package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RcSessionTest {
    /** A link that is never started (sendRc returns false), for sessions made inside a test. */
    static final class NoLink {
        final ControlLink link;

        NoLink() {
            try {
                link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), 46392, 46393);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final long[] now = {1_000_000};
    private final boolean[] linkUp = {true};
    private final List<String> events = new ArrayList<>();
    private final RcMapper mapper = new RcMapper(new String[]{"axis0", "axis1", "axis2", "axis3"});
    private final RcSession session;

    RcSessionTest() throws java.io.IOException {
        // never started: sendRc just returns false, which is all the gate needs
        ControlLink link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), 46390, 46391);
        session = new RcSession(link, mapper, events::add, () -> now[0], () -> linkUp[0]);
    }

    /** Throttle (axis3) from -1 (1000) to 1 (2000). */
    private static Joystick.State sticks(float throttle) {
        return new Joystick.State(new float[]{0, 0, 0, throttle}, new boolean[0]);
    }

    @Test
    void nothingIsSentBeforeTheLinkIsUpAndTheThrottleIsLowThen() {
        linkUp[0] = false;
        session.onInput(sticks(-1f));
        assertFalse(session.isRcEnabled(), "link still connecting");
        session.onInput(sticks(0.5f)); // throttle raised while waiting
        linkUp[0] = true;
        session.onInput(sticks(0.5f));
        assertFalse(session.isRcEnabled(), "link up with the throttle up: nothing yet");
        session.onInput(sticks(-1f));
        assertTrue(session.isRcEnabled());
    }

    @Test
    void aLinkLossNeedsTheThrottleLowAgain() {
        session.onInput(sticks(-1f));
        session.onInput(sticks(0.4f)); // flying
        linkUp[0] = false;
        session.onInput(sticks(0.4f));
        assertFalse(session.isRcEnabled());
        now[0] += 300;
        linkUp[0] = true;
        session.onInput(sticks(0.4f));
        assertFalse(session.isRcEnabled(), "unlike a radio blip: the link was down long enough to notice");
        session.onInput(sticks(-1f));
        assertTrue(session.isRcEnabled());
    }

    @Test
    void theArmSwitchMustBeOffToo() {
        // arm switch on AUX1 (the frame's 5th channel), armed at 1700-2100
        RcMapper five = new RcMapper(new String[]{"axis0", "axis1", "axis2", "axis3", "axis4"});
        RcSession armed = new RcSession(new RcSessionTest.NoLink().link, five, events::add, () -> now[0], () -> true,
                new ArmSwitch(4, java.util.List.of(new int[]{1700, 2100})));
        armed.onInput(new Joystick.State(new float[]{0, 0, 0, -1f, 1f}, new boolean[0])); // throttle down, arm switch ON
        assertFalse(armed.isRcEnabled(), "arm switch on: the sticks don't start");
        assertEquals("ARM SWITCH OFF", armed.waitingFor());
        armed.onInput(new Joystick.State(new float[]{0, 0, 0, -1f, -1f}, new boolean[0]));
        assertTrue(armed.isRcEnabled());
    }

    @Test
    void aRadioThatStopsChangingIsNoticed() {
        session.onInput(sticks(-1f));
        now[0] += 6000;
        session.onInput(sticks(-1f)); // the very same values, 6 s later
        assertEquals(6000, session.msSinceInputChanged());
        session.onInput(sticks(-0.99f));
        assertEquals(0, session.msSinceInputChanged(), "any change resets it");
    }

    @Test
    void outputStartsOnlyWithTheThrottleLow() {
        session.onInput(sticks(0.5f));
        assertFalse(session.isRcEnabled(), "throttle up at link-up: nothing is sent");
        session.onInput(sticks(-1f));
        assertTrue(session.isRcEnabled());
    }

    @Test
    void aShortRadioBlipInFlightResumesAtOnce() {
        session.onInput(sticks(-1f));
        session.onInput(sticks(0.4f)); // flying
        session.onInput(null);         // USB blip
        assertFalse(session.isRcEnabled());
        now[0] += 300;
        session.onInput(sticks(0.4f));
        assertTrue(session.isRcEnabled(), "back within the window: resumes with the throttle up");
        assertEquals(1700, session.getLastChannels()[PcConfig.THROTTLE_INDEX], "axis 0.4 -> 1700");
        assertTrue(events.get(events.size() - 1).startsWith("Joystick back after 300 ms"));
    }

    @Test
    void aLongGapNeedsTheThrottleLowAgain() {
        session.onInput(sticks(-1f));
        session.onInput(sticks(0.4f));
        session.onInput(null);
        now[0] += RcSession.RESUME_WINDOW_MS + 1;
        session.onInput(sticks(0.4f));
        assertFalse(session.isRcEnabled(), "Betaflight has failed safe by now: throttle-low rule again");
        session.onInput(sticks(0.4f));
        assertFalse(session.isRcEnabled());
        session.onInput(sticks(-1f));
        assertTrue(session.isRcEnabled());
    }

    @Test
    void aRadioMissingFromTheStartNeverSkipsTheThrottleRule() {
        session.onInput(null);
        session.onInput(sticks(0.4f));
        assertFalse(session.isRcEnabled());
    }

    /**
     * The link was down long enough for the failsafe, but the FC says it is still armed (GPS rescue flying it home):
     * the sticks go live as they are - a frame with the arm switch off would disarm it in the air.
     */
    @Test
    void anArmedDroneGetsTheSticksBackAtOnceAfterALinkLoss() {
        boolean[] fcArmed = {false};
        RcMapper five = new RcMapper(new String[]{"axis0", "axis1", "axis2", "axis3", "axis4"});
        RcSession armed = new RcSession(new NoLink().link, five, events::add, () -> now[0], () -> linkUp[0], () -> fcArmed[0],
                new ArmSwitch(4, java.util.List.of(new int[]{1700, 2100})));
        armed.onInput(new Joystick.State(new float[]{0, 0, 0, -1f, -1f}, new boolean[0])); // throttle down, arm off
        assertTrue(armed.isRcEnabled());
        Joystick.State flying = new Joystick.State(new float[]{0, 0, 0, 0.4f, 1f}, new boolean[0]); // throttle up, arm ON
        armed.onInput(flying);
        linkUp[0] = false;
        armed.onInput(flying);
        assertFalse(armed.isRcEnabled());
        now[0] += 5000;
        linkUp[0] = true;
        armed.onInput(flying);
        assertFalse(armed.isRcEnabled(), "no fresh word from the FC yet: the throttle-low, arm-off rule");
        assertEquals("THROTTLE DOWN, ARM OFF", armed.waitingFor());
        assertNull(armed.lastResumeBanner());
        fcArmed[0] = true;
        armed.onInput(flying);
        assertTrue(armed.isRcEnabled(), "the FC is armed and flying: the sticks as they are, now");
        assertEquals("Link back - FC armed - sticks live.", events.get(events.size() - 1));
        assertEquals(new RcSession.Banner(RcSession.ARMED_RESUME_BANNER, now[0]), armed.lastResumeBanner());
        assertEquals(1700, armed.getLastChannels()[PcConfig.THROTTLE_INDEX], "with the throttle where it is");
        assertNull(armed.waitingFor());
    }

    /** The FC disarmed (or nothing is known): the rule as before - throttle down and the arm switch off. */
    @Test
    void aDisarmedOrUnknownDroneStillNeedsTheThrottleLowAfterALinkLoss() {
        boolean[] fcArmed = {false};
        RcSession s = new RcSession(new NoLink().link, mapper, events::add, () -> now[0], () -> linkUp[0], () -> fcArmed[0], null);
        s.onInput(sticks(-1f));
        s.onInput(sticks(0.4f));
        linkUp[0] = false;
        s.onInput(sticks(0.4f));
        now[0] += 5000;
        linkUp[0] = true;
        s.onInput(sticks(0.4f));
        assertFalse(s.isRcEnabled(), "disarmed: the throttle-low rule");
        s.onInput(sticks(0.4f));
        assertFalse(s.isRcEnabled());
        assertEquals("THROTTLE DOWN", s.waitingFor());
        assertNull(s.lastResumeBanner(), "no banner: nothing out of the ordinary happened");
        s.onInput(sticks(-1f));
        assertTrue(s.isRcEnabled());
        assertEquals("Throttle low - RC output enabled.", events.get(events.size() - 1));
    }

    /**
     * The radio sends 4 channels, the arm switch sits on AUX1 with an inverted range (900-1100 arms, as a switch
     * wired "on when down" would): the frame goes out padded to 8 with AUX1 = 1000, which is inside that range.
     */
    @Test
    void anArmChannelTheRadioDoesntSendIsJudgedAsThePaddedFrame() {
        RcSession s = new RcSession(new NoLink().link, mapper, events::add, () -> now[0], () -> true,
                new ArmSwitch(4, java.util.List.of(new int[]{900, 1100})));
        s.onInput(sticks(-1f));
        assertFalse(s.isRcEnabled(), "AUX1 goes out as 1000: the switch is on");
        assertEquals("ARM SWITCH OFF", s.waitingFor());
        RcSession usual = new RcSession(new NoLink().link, mapper, events::add, () -> now[0], () -> true,
                new ArmSwitch(4, java.util.List.of(new int[]{1700, 2100})));
        usual.onInput(sticks(-1f));
        assertTrue(usual.isRcEnabled(), "the usual range: 1000 is off");
    }

    @Test
    void waitingForSaysWhyThereIsNothingToSend() {
        assertEquals("NO RADIO", session.waitingFor(), "nothing seen yet");
        session.onInput(null);
        assertEquals("NO RADIO", session.waitingFor());
        session.onInput(new Joystick.State(new float[]{0, 0}, new boolean[0])); // a device without the throttle axis
        assertEquals("MAPPING INCOMPLETE", session.waitingFor());
        session.onInput(sticks(0.5f));
        assertEquals("THROTTLE DOWN", session.waitingFor());
        session.onInput(sticks(-1f));
        assertNull(session.waitingFor());
    }

    @Test
    void theArmSwitchIsUnknownUntilDroneSetupReadIt() {
        assertTrue(session.isArmSwitchUnknown());
        RcSession known = new RcSession(new NoLink().link, mapper, events::add, () -> now[0], () -> true,
                new ArmSwitch(4, java.util.List.of(new int[]{1700, 2100})));
        assertFalse(known.isArmSwitchUnknown());
    }

    /** STANDBY isn't a radio blip: a quick WAKE must not skip the throttle-low rule. */
    @Test
    void aWakeRightAfterStandbyStillNeedsTheThrottleLow() {
        session.onInput(sticks(-1f));
        session.onInput(sticks(0.4f));
        assertTrue(session.isRcEnabled());
        session.onPaused(sticks(0.4f)); // STANDBY
        assertFalse(session.isRcEnabled());
        now[0] += 300; // well inside the blip window
        session.onInput(sticks(0.4f)); // WAKE, throttle still up
        assertFalse(session.isRcEnabled(), "a pause is no blip: throttle-low first");
        session.onInput(sticks(-1f));
        assertTrue(session.isRcEnabled());
    }

    @Test
    void aButtonChangeCountsAsTheRadioMoving() {
        session.onInput(new Joystick.State(new float[]{0, 0, 0, -1f}, new boolean[]{false}));
        now[0] += 6000;
        session.onInput(new Joystick.State(new float[]{0, 0, 0, -1f}, new boolean[]{true}));
        assertEquals(0, session.msSinceInputChanged(), "a switch flipped: the radio is alive");
    }

    /** With radio.frozenStopS set, an armed drone on a hung radio is left to Betaflight's failsafe; off by default. */
    @Test
    void aFrozenRadioStopsTheSticksOnlyWhenAskedAndOnlyWhileArmed() {
        boolean[] fcArmed = {true};
        RcSession s = new RcSession(new NoLink().link, mapper, events::add, () -> now[0], () -> true, () -> fcArmed[0], null);
        s.onInput(sticks(-1f));
        now[0] += 60_000;
        s.onInput(sticks(-1f));
        assertFalse(s.isFrozenStopped(), "no limit set: never");
        s.setFrozenStopMs(10_000);
        s.onInput(sticks(-1f));
        assertTrue(s.isFrozenStopped(), "armed, unchanged for a minute, limit 10 s");
        assertTrue(events.get(events.size() - 1).startsWith("RADIO FROZEN"));
        assertTrue(s.isRcEnabled(), "still live: it goes on the moment the radio moves");
        s.onInput(sticks(-0.99f));
        assertFalse(s.isFrozenStopped());
        assertEquals("The radio moves again - sticks sent again.", events.get(events.size() - 1));
        now[0] += 60_000;
        fcArmed[0] = false;
        s.onInput(sticks(-0.99f));
        assertFalse(s.isFrozenStopped(), "disarmed: nothing to hand to the failsafe");
    }
}
