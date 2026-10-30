package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RcSessionTest {
    private final long[] now = {1_000_000};
    private final List<String> events = new ArrayList<>();
    private final RcMapper mapper = new RcMapper(new String[]{"axis0", "axis1", "axis2", "axis3"});
    private final RcSession session;

    RcSessionTest() throws java.io.IOException {
        // never started: sendRc just returns false, which is all the gate needs
        ControlLink link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), 46390, 46391);
        session = new RcSession(link, mapper, events::add, () -> now[0]);
    }

    /** Throttle (axis3) from -1 (1000) to 1 (2000). */
    private static Joystick.State sticks(float throttle) {
        return new Joystick.State(new float[]{0, 0, 0, throttle}, new boolean[0]);
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
