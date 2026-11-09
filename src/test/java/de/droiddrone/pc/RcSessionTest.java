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
        session = new RcSession(link, mapper, events::add, RcSession.Inputs.of(() -> now[0], () -> linkUp[0]));
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
        RcSession armed = new RcSession(new RcSessionTest.NoLink().link, five, events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> false, new ArmSwitch(4, java.util.List.of(new int[]{1700, 2100}))));
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
        RcSession armed = new RcSession(new NoLink().link, five, events::add,
                new RcSession.Inputs(() -> now[0], () -> linkUp[0], () -> fcArmed[0], () -> false, new ArmSwitch(4, java.util.List.of(new int[]{1700, 2100}))));
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

    /** H9 (1.0.49): while the phone installs an update, no frame that could arm a disarmed drone goes out. */
    @Test
    void noArmingWhileThePhoneInstallsAnUpdate() {
        boolean[] fcArmed = {false}, installing = {false};
        RcMapper five = new RcMapper(new String[]{"axis0", "axis1", "axis2", "axis3", "axis4"});
        RcSession s = new RcSession(new NoLink().link, five, events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> fcArmed[0], () -> false, new ArmSwitch(4, java.util.List.of(new int[]{1700, 2100}))));
        s.setArmBlocked(() -> installing[0]);
        Joystick.State idle = new Joystick.State(new float[]{0, 0, 0, -1f, -1f}, new boolean[0]);
        Joystick.State arm = new Joystick.State(new float[]{0, 0, 0, -1f, 1f}, new boolean[0]);
        s.onInput(idle);
        assertTrue(s.isRcEnabled());
        installing[0] = true;
        s.onInput(idle);
        assertTrue(s.isRcEnabled(), "arm switch off: the sticks go on (they can't arm)");
        s.onInput(arm);
        assertFalse(s.isRcEnabled(), "the arm switch flipped while the phone installs: stopped");
        assertTrue(events.get(events.size() - 1).contains("installs an update"));
        assertEquals("PHONE UPDATING", s.waitingFor());
        s.onInput(idle);
        assertTrue(s.isRcEnabled(), "arm off again: sticks again (still no arming)");
        s.onInput(arm);
        assertFalse(s.isRcEnabled());
        installing[0] = false;
        s.onInput(arm);
        assertFalse(s.isRcEnabled(), "done installing: the usual arm-off rule, no arming straight from the switch");
        s.onInput(idle);
        s.onInput(arm);
        assertTrue(s.isRcEnabled(), "and then it arms as ever");
        // an armed drone keeps its sticks whatever the phone says
        installing[0] = true;
        fcArmed[0] = true;
        s.onInput(arm);
        assertTrue(s.isRcEnabled());
    }

    /** H9: an unknown arm switch can't be judged - nothing goes out while the phone installs. */
    @Test
    void anUnknownArmSwitchSendsNothingWhileThePhoneInstalls() {
        boolean[] installing = {true};
        RcSession s = new RcSession(new NoLink().link, mapper, events::add, new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> false, null));
        s.setArmBlocked(() -> installing[0]);
        s.onInput(sticks(-1f));
        assertFalse(s.isRcEnabled());
        installing[0] = false;
        s.onInput(sticks(-1f));
        assertTrue(s.isRcEnabled());
    }

    /** The FC disarmed (or nothing is known): the rule as before - throttle down and the arm switch off. */
    @Test
    void aDisarmedOrUnknownDroneStillNeedsTheThrottleLowAfterALinkLoss() {
        boolean[] fcArmed = {false};
        RcSession s = new RcSession(new NoLink().link, mapper, events::add, new RcSession.Inputs(() -> now[0], () -> linkUp[0], () -> fcArmed[0], () -> false, null));
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
        RcSession s = new RcSession(new NoLink().link, mapper, events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> false, new ArmSwitch(4, java.util.List.of(new int[]{900, 1100}))));
        s.onInput(sticks(-1f));
        assertFalse(s.isRcEnabled(), "AUX1 goes out as 1000: the switch is on");
        assertEquals("ARM SWITCH OFF", s.waitingFor());
        RcSession usual = new RcSession(new NoLink().link, mapper, events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> false, new ArmSwitch(4, java.util.List.of(new int[]{1700, 2100}))));
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
        RcSession known = new RcSession(new NoLink().link, mapper, events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> false, new ArmSwitch(4, java.util.List.of(new int[]{1700, 2100}))));
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
        RcSession s = new RcSession(new NoLink().link, mapper, events::add, new RcSession.Inputs(() -> now[0], () -> true, () -> fcArmed[0], () -> false, null));
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
        assertEquals("Radio moves again - sticks resumed.", events.get(events.size() - 1));
        now[0] += 60_000;
        fcArmed[0] = false;
        s.onInput(sticks(-0.99f));
        assertFalse(s.isFrozenStopped(), "disarmed: nothing to hand to the failsafe");
    }

    /** The BEEPER's channel in the frame (AUX14 from 1.0.49), and an AUX next to it that isn't (AUX12, or AUX14 before). */
    private static final int BEEPER_AT = 3 + de.droiddrone.pc.setup.ModeSwitch.BEEPER_AUX;
    private static final int OTHER_AT = 3 + (de.droiddrone.pc.setup.ModeSwitch.BEEPER_AUX == 12 ? 14 : 12);

    /** The sticks on axes 0-3 and button 0 on the given channel: the radio's mapping drives it. */
    private static RcMapper withButtonOn(int channel) {
        String[] specs = new String[channel + 1];
        for (int i = 0; i < 4; i++) specs[i] = "axis" + i;
        specs[channel] = "button0";
        return new RcMapper(specs);
    }

    private static Joystick.State lowThrottleButton(boolean down) {
        return new Joystick.State(new float[]{0, 0, 0, -1f}, new boolean[]{down});
    }

    /** A link that records the idle sticks handed to it (never started: no stick frame goes anywhere). */
    static final class IdleRecorder extends ControlLink {
        final List<short[]> idle = new ArrayList<>();

        IdleRecorder() throws java.io.IOException {
            super(PcConfig.defaults(), InetAddress.getLoopbackAddress(), 46394, 46395);
        }

        @Override
        public boolean sendIdleSticks(short[] channels) {
            idle.add(channels.clone());
            return true;
        }
    }

    @Test
    void framesCarryTheRadiosChannelsOnlyAndAux14WhenTheBeeperIsSetUp() {
        // 1.0.44 - 1.0.48 padded every frame to 16 with 1000: a low range on a switch the radio never sent came on
        short[] four = {1500, 1500, 1500, 1000};
        short[] plain = RcSession.withBeeper(four, -1, false);
        assertEquals(8, plain.length, "padded to 8 for the phone's channel map, as before 1.0.44");
        assertEquals(1000, plain[7]);
        short[] held = RcSession.withBeeper(four, BEEPER_AT, true);
        assertEquals(BEEPER_AT + 1, held.length);
        assertEquals(2000, held[BEEPER_AT]);
        assertEquals(1000, held[7], "the first 8 padded as before");
        for (int i = 8; i < BEEPER_AT; i++) assertEquals(0, held[i], "between: not sent (0), as Betaflight would have it");
        assertEquals(1000, RcSession.withBeeper(four, BEEPER_AT, false)[BEEPER_AT]);
        assertEquals(4, four.length, "the radio's frame untouched");

        RcSession s = new RcSession(new NoLink().link, mapper, events::add, new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> true, null));
        s.onInput(sticks(-1f));
        assertEquals(8, s.getLastChannels().length, "BEEPER not set up: no AUX14");
        s.setBeeperSetUp(true);
        s.onInput(sticks(-1f));
        assertEquals(BEEPER_AT + 1, s.getLastChannels().length);
        assertEquals(1000, s.getLastChannels()[BEEPER_AT]);
    }

    /** 1.0.48's C1: with ARM on the BEEPER's channel, holding BEEPER sent 2000 there and armed the drone. */
    @Test
    void theBeeperNeverDrivesTheArmChannel() {
        for (int[] range : new int[][]{{1700, 2100}, {900, 1100}}) {
            ArmSwitch arm = new ArmSwitch(BEEPER_AT, List.of(range));
            boolean inverted = range[0] < 1000;
            RcSession s = new RcSession(new NoLink().link, withButtonOn(BEEPER_AT), events::add,
                    new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> true, arm));
            s.setBeeperSetUp(true);
            assertNotNull(s.beeperOff(), "ARM there: BEEPER is off");
            assertTrue(s.beeperOff().contains("in use by ARM"), s.beeperOff());
            s.setBeeper(true);
            s.onInput(lowThrottleButton(inverted)); // the radio's switch off: 1000, or 2000 for an inverted range
            assertTrue(s.isRcEnabled(), "throttle low, arm off: the sticks go");
            assertFalse(arm.on(s.getLastChannels()), "BEEPER held: ARM's channel stays the radio's");
            assertFalse(s.isBeeping());
        }
        // the radio doesn't carry ARM's channel (inverted range): never extended to it, unknown, the sticks wait
        ArmSwitch inverted = new ArmSwitch(BEEPER_AT, List.of(new int[]{900, 1100}));
        RcSession s = new RcSession(new NoLink().link, mapper, events::add, new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> true, inverted));
        s.setBeeperSetUp(true);
        s.setBeeper(true);
        s.onInput(sticks(-1f));
        assertTrue(s.getLastChannels().length <= BEEPER_AT, "the frame doesn't reach ARM's channel");
        assertFalse(s.isRcEnabled());
        assertEquals("ARM SWITCH NOT SENT", s.waitingFor());
    }

    @Test
    void theBeeperLeavesArmOnAnotherChannelAlone() {
        // ARM on the AUX next to the BEEPER's, inverted (arms low): the radio holds it high, BEEPER beeps on its own
        ArmSwitch arm = new ArmSwitch(OTHER_AT, List.of(new int[]{900, 1100}));
        RcSession s = new RcSession(new NoLink().link, withButtonOn(OTHER_AT), events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> true, arm));
        s.setBeeperSetUp(true);
        assertNull(s.beeperOff());
        s.onInput(lowThrottleButton(true));
        assertTrue(s.isRcEnabled());
        s.setBeeper(true);
        s.onInput(lowThrottleButton(true));
        short[] f = s.getLastChannels();
        assertEquals(2000, f[BEEPER_AT]);
        assertEquals(2000, f[OTHER_AT], "ARM's channel as the radio has it");
        assertFalse(arm.on(f));
        assertTrue(s.isBeeping());
    }

    @Test
    void theBeeperBeepsOnlyOnAFreshDisarmedAndLetsGoAfterTenSeconds() {
        boolean[] disarmed = {false};
        RcSession s = new RcSession(new NoLink().link, mapper, events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> disarmed[0], null));
        s.setBeeperSetUp(true);
        s.setBeeper(true);
        s.onInput(sticks(-1f));
        assertEquals(1000, s.getLastChannels()[BEEPER_AT], "armed or not known: no beep");
        disarmed[0] = true;
        s.onInput(sticks(-1f));
        assertEquals(2000, s.getLastChannels()[BEEPER_AT]);
        now[0] += RcSession.BEEPER_MAX_MS;
        s.onInput(sticks(-1f));
        assertEquals(1000, s.getLastChannels()[BEEPER_AT], "held 10 s: let go");
        assertTrue(events.get(events.size() - 1).startsWith("BEEPER let go"));
        s.setBeeper(true); // the button's model fires again while still down: not a new press
        s.onInput(sticks(-1f));
        assertEquals(1000, s.getLastChannels()[BEEPER_AT]);
        s.setBeeper(false);
        s.setBeeper(true);
        s.onInput(sticks(-1f));
        assertEquals(2000, s.getLastChannels()[BEEPER_AT], "a new press beeps again");
    }

    @Test
    void theBeeperIsOffWhereTheRadioDrivesItsChannelOrNothingSetItUp() {
        RcSession s = new RcSession(new NoLink().link, withButtonOn(BEEPER_AT), events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> true, null));
        assertEquals(RcSession.BEEPER_NOT_SET_UP, s.beeperOff());
        s.setBeeperSetUp(true);
        assertTrue(s.beeperOff().contains("radio"), s.beeperOff());
        s.setBeeper(true);
        s.onInput(lowThrottleButton(false));
        assertEquals(1000, s.getLastChannels()[BEEPER_AT], "the radio's own value");
    }

    @Test
    void theBeeperCountsAsSetUpOnlyAloneOnItsAuxWithTheButtonsRange() {
        int aux = de.droiddrone.pc.setup.ModeSwitch.BEEPER_AUX - 1; // the CLI counts AUX1 as 0
        assertTrue(RcSession.beeperSetUp("aux 0 0 0 1700 2100 0 0\naux 1 13 " + aux + " 1700 2100 0 0"));
        assertFalse(RcSession.beeperSetUp("aux 0 0 0 1700 2100 0 0"), "no BEEPER");
        assertFalse(RcSession.beeperSetUp("aux 1 13 " + aux + " 1700 2100 0 0\naux 2 13 3 1700 2100 0 0"), "on a radio switch too");
        assertFalse(RcSession.beeperSetUp("aux 1 13 " + aux + " 900 2100 0 0"), "low beeps too");
        assertFalse(RcSession.beeperSetUp("aux 1 13 " + (aux - 2) + " 1700 2100 0 0"), "another AUX");
    }

    /** M1: a channel the frame doesn't carry was taken as 0 = off; Betaflight holds it, so it is unknown. */
    @Test
    void anArmChannelTheRadioDoesntCarryIsUnknownNeverOff() {
        RcSession s = new RcSession(new NoLink().link, mapper, events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> false, new ArmSwitch(9, List.of(new int[]{1700, 2100})))); // ARM on AUX6: beyond the padded 8
        s.onInput(sticks(-1f));
        assertFalse(s.isRcEnabled());
        assertEquals("ARM SWITCH NOT SENT", s.waitingFor());
    }

    /** H1, the PC's lock: idle sticks only while the radio's own frame passes the arm gate. */
    @Test
    void idleSticksGoOnlyWhileTheRadioPassesTheArmGate() throws java.io.IOException {
        IdleRecorder link = new IdleRecorder();
        RcMapper five = new RcMapper(new String[]{"axis0", "axis1", "axis2", "axis3", "axis4"});
        RcSession s = new RcSession(link, five, events::add,
                new RcSession.Inputs(() -> now[0], () -> true, () -> false, () -> false, new ArmSwitch(4, List.of(new int[]{1700, 2100}))));
        s.onInput(new Joystick.State(new float[]{0, 0, 0, -1f, 1f}, new boolean[0])); // ARM on
        now[0] += 1000;
        s.onInput(new Joystick.State(new float[]{0, 0, 0, 0.5f, -1f}, new boolean[0])); // throttle up
        assertTrue(link.idle.isEmpty(), "the radio armed or throttle up: no idle sticks");
        now[0] += 1000;
        s.onInput(new Joystick.State(new float[]{0, 0, 0, -1f, -1f}, new boolean[0]));
        assertEquals(1, link.idle.size());
        assertEquals(1000, link.idle.get(0)[4]);
    }

    @Test
    void idleSticksCanNeverArm() {
        short[] radio = {1200, 1800, 1300, 1900, 2000, 1500, 2000, 1000};
        // ARM up on AUX1 (channel 4): idle has it low, throttle low, sticks centred, the other AUX as the radio has them
        ArmSwitch armUp = new ArmSwitch(4, List.of(new int[]{1700, 2100}));
        short[] idle = RcSession.idleSticks(radio, armUp);
        assertNotNull(idle);
        assertEquals(1000, idle[3]);
        assertEquals(1500, idle[0]);
        assertFalse(armUp.on(idle));
        assertEquals(1500, idle[5]);
        // an inverted switch (arms low): idle puts it high
        ArmSwitch armLow = new ArmSwitch(4, List.of(new int[]{900, 1300}));
        short[] idleLow = RcSession.idleSticks(radio, armLow);
        assertEquals(2000, idleLow[4]);
        assertFalse(armLow.on(idleLow));
        // ARM beyond the frame: the idle frame reaches it, the channels in between not sent (0)
        ArmSwitch far = new ArmSwitch(12, List.of(new int[]{1700, 2100}));
        short[] idleFar = RcSession.idleSticks(radio, far);
        assertEquals(13, idleFar.length);
        assertEquals(1000, idleFar[12]);
        assertEquals(0, idleFar[9]);
        assertFalse(far.on(idleFar));
        // ARM not known, or a range over the whole stick: nothing
        assertNull(RcSession.idleSticks(radio, null));
        assertNull(RcSession.idleSticks(radio, new ArmSwitch(4, List.of(new int[]{900, 2100}))));
    }
}
