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
}
