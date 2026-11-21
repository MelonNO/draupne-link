package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static de.droiddrone.pc.ArmedCheck.ARMED;
import static de.droiddrone.pc.ArmedCheck.CLEAR;
import static de.droiddrone.pc.ArmedCheck.MAY_FLY;
import static de.droiddrone.pc.ArmedCheck.NOT_KNOWN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F-062: right after a (re)connect "armed: not known yet" was DISARMED to this PC's guards. A status read before
 * Betaflight's mode names have come says "not armed" whatever the drone does; the guards now count that - and a link
 * just up, with no status at all - as armed, and say so. No flight controller on the phone, or no link up yet, asks
 * nothing: the pilot is never held.
 */
class ArmedCheckTest {
    private static final long NOW = 5_000_000, MIN = 60_000, NEVER = Long.MAX_VALUE;
    /** MSP_STATUS's mode bits of the test's drone: ARM is the first box, ANGLE the second. */
    private static final int ARM_BIT = 0b01, ANGLE_BIT = 0b10;

    private static FcTelemetry.Status status(boolean armed, boolean known, long at) {
        return new FcTelemetry.Status(armed, List.of(), List.of(), 5, 40, at, known);
    }

    // ------------------------------------------------------------------ what the link's telemetry says

    @Test
    void aStatusReadBeforeTheModeNamesSaysNothingOfArmed() throws Exception {
        FcTelemetry t = new FcTelemetry();
        // the drone is in the air: the ARM box is on, but its name hasn't come on this new connection yet
        t.parse(new DataReader(FcTelemetryTest.betaflightPacket(0, ARM_BIT | ANGLE_BIT), true), FcTelemetryTest.BETAFLIGHT);
        FcTelemetry.Status first = t.getStatus();
        assertFalse(first.armed(), "ARM can't be found among the bits yet: the status reads not armed");
        assertFalse(first.armedKnown(), "and that says nothing: the names aren't here");

        t.parse(new DataReader(FcTelemetryTest.boxNamesPacket("ARM;ANGLE;"), true), FcTelemetryTest.BETAFLIGHT);
        assertFalse(t.getStatus().armedKnown(), "the names alone change nothing: the status in hand was read without them");
        t.parse(new DataReader(FcTelemetryTest.betaflightPacket(0, ARM_BIT | ANGLE_BIT), true), FcTelemetryTest.BETAFLIGHT);
        assertTrue(t.getStatus().armed() && t.getStatus().armedKnown(), "the next status is the flight controller's own word");
        t.parse(new DataReader(FcTelemetryTest.betaflightPacket(0, ANGLE_BIT), true), FcTelemetryTest.BETAFLIGHT);
        assertTrue(!t.getStatus().armed() && t.getStatus().armedKnown(), "DISARMED, said");

        t.forgetBoxNames(); // a loss longer than ControlLink.FC_STATE_KEEP_MS: read again on reconnect
        t.parse(new DataReader(FcTelemetryTest.betaflightPacket(0, ARM_BIT | ANGLE_BIT), true), FcTelemetryTest.BETAFLIGHT);
        assertFalse(t.getStatus().armed());
        assertFalse(t.getStatus().armedKnown(), "not known again until the names are back");
    }

    @Test
    void theGuardsTakeANewLinksFirstStatusesAsNotKnown() throws Exception {
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(FcTelemetryTest.betaflightPacket(0, ARM_BIT | ANGLE_BIT), true), FcTelemetryTest.BETAFLIGHT);
        long now = MonoClock.ms();
        assertEquals(NOT_KNOWN, ArmedCheck.of(true, false, t.msSinceFcData(now), t.getStatus(), 0, now),
                "a fresh status without the mode names: armed in the air, read as not armed");
        t.parse(new DataReader(FcTelemetryTest.boxNamesPacket("ARM;ANGLE;"), true), FcTelemetryTest.BETAFLIGHT);
        t.parse(new DataReader(FcTelemetryTest.betaflightPacket(0, ARM_BIT | ANGLE_BIT), true), FcTelemetryTest.BETAFLIGHT);
        now = MonoClock.ms();
        assertEquals(ARMED, ArmedCheck.of(true, false, t.msSinceFcData(now), t.getStatus(), 0, now));
        t.parse(new DataReader(FcTelemetryTest.betaflightPacket(0, ANGLE_BIT), true), FcTelemetryTest.BETAFLIGHT);
        now = MonoClock.ms();
        assertEquals(CLEAR, ArmedCheck.of(true, false, t.msSinceFcData(now), t.getStatus(), 0, now), "DISARMED, said: nothing asked");
    }

    // ------------------------------------------------------------------ the rule, case by case

    @Test
    void aLinkJustUpIsNotKnownUntilItsFlightControllerHasSaid() {
        assertEquals(NOT_KNOWN, ArmedCheck.of(true, false, NEVER, null, 0, NOW), "READY, nothing from the flight controller yet");
        assertEquals(NOT_KNOWN, ArmedCheck.of(true, false, 40, null, 0, NOW), "its attitude comes, no status yet");
        assertEquals(NOT_KNOWN, ArmedCheck.of(true, false, 40, status(false, false, NOW - 100), 0, NOW), "statuses, the mode names not yet");
        assertEquals(ARMED, ArmedCheck.of(true, false, 40, status(true, true, NOW - 100), 0, NOW));
        assertEquals(CLEAR, ArmedCheck.of(true, false, 40, status(false, true, NOW - 100), 0, NOW));
        assertTrue(NOT_KNOWN.asks() && ARMED.asks() && MAY_FLY.asks());
        assertFalse(CLEAR.asks());
    }

    @Test
    void afterALongLossADroneArmedBeforeMayStillFlyWhateverTheFirstStatusesRead() {
        // armed 2 min ago, the link lost for 90 s (the mode names forgotten), back: fresh statuses that read "not armed"
        assertEquals(MAY_FLY, ArmedCheck.of(true, false, 40, status(false, false, NOW - 100), NOW - 2 * MIN, NOW));
        assertEquals(NOT_KNOWN, ArmedCheck.of(true, false, 40, status(false, false, NOW - 100), NOW - 11 * MIN, NOW),
                "armed longer ago than the lost-drone window: not known, still asked");
        assertEquals(CLEAR, ArmedCheck.of(true, false, 40, status(false, true, NOW - 100), NOW - 2 * MIN, NOW),
                "the names back and DISARMED said: it is down");
        assertEquals(MAY_FLY, ArmedCheck.of(false, false, 5000, status(true, true, NOW - 5000), 0, NOW),
                "armed in its last status, whether or not the window's own refresh noted when");
    }

    /** What was asked before F-062, and what was not, stays so. */
    @Test
    void whatWasAskedBeforeIsAskedAsBefore() {
        assertEquals(MAY_FLY, ArmedCheck.of(false, false, 5000, status(true, true, NOW - 5000), NOW - 5000, NOW), "gone quiet armed");
        assertEquals(MAY_FLY, ArmedCheck.of(true, true, 5000, status(true, true, NOW - 5000), NOW - 5000, NOW), "the flight controller lost in the air");
        assertEquals(CLEAR, ArmedCheck.of(false, false, 11 * MIN, status(true, true, NOW - 11 * MIN), NOW - 11 * MIN, NOW),
                "the lost-drone window is over");
        assertEquals(CLEAR, ArmedCheck.of(false, false, 5000, status(false, true, NOW - 5000), 0, NOW), "its last word DISARMED, then the link lost");
        assertEquals(CLEAR, ArmedCheck.of(true, false, 40, status(false, true, NOW - 5000), 0, NOW), "its last word DISARMED");
    }

    @Test
    void aPhoneWithNoFlightControllerAsksNothing() {
        assertEquals(NOT_KNOWN, ArmedCheck.of(true, false, NEVER, null, 0, NOW), "the grace after the link came up: too early to say");
        assertEquals(CLEAR, ArmedCheck.of(true, true, NEVER, null, 0, NOW), "FC LOST: no sticks reach a drone, nothing to protect");
        assertEquals(CLEAR, ArmedCheck.of(false, false, NEVER, null, 0, NOW), "and when its link is down again");
        assertEquals(CLEAR, ArmedCheck.of(true, true, 4000, status(false, false, NOW - 4000), 0, NOW), "the flight controller gone before it was read");
        assertEquals(CLEAR, ArmedCheck.of(true, true, 4000, status(false, true, NOW - 4000), 0, NOW), "gone, DISARMED before");
    }

    @Test
    void noLinkUpYetAsksNothingAndNotKnownIsNotForEver() {
        assertEquals(CLEAR, ArmedCheck.of(false, false, NEVER, null, 0, NOW), "connecting to a phone that is off or asleep: no sticks went");
        // heard on this connection but never read (the names never came, a flight controller that sends no status), then lost
        assertEquals(NOT_KNOWN, ArmedCheck.of(false, false, 30_000, status(false, false, NOW - 30_000), 0, NOW));
        assertEquals(NOT_KNOWN, ArmedCheck.of(false, false, 30_000, null, 0, NOW));
        assertEquals(CLEAR, ArmedCheck.of(false, false, 11 * MIN, status(false, false, NOW - 11 * MIN), 0, NOW),
                "silent longer than the lost-drone window: no more asked");
        assertEquals(CLEAR, ArmedCheck.of(null, 0, NOW), "no link");
    }

    // ------------------------------------------------------------------ what the pilot reads

    @Test
    void theQuestionSaysWhatIsKnown() {
        String failsafe = " stops the sticks: Betaflight failsafe takes over (GPS rescue, land or drop, as set).";
        assertEquals("The drone is ARMED.\nStandby mode" + failsafe, ARMED.question("Standby mode"));
        assertEquals("Standby mode while armed?", ARMED.title("Standby mode"));
        assertEquals("The drone was armed moments ago and may still be flying.\nDEEP SLEEP" + failsafe, MAY_FLY.question("DEEP SLEEP"));
        assertEquals("DEEP SLEEP while armed?", MAY_FLY.title("DEEP SLEEP"));
        assertEquals("Not known yet if the drone is armed.\nClose DRAUPNE Link stops the sticks: in the air, Betaflight failsafe takes over "
                + "(GPS rescue, land or drop, as set).", NOT_KNOWN.question("Close DRAUPNE Link"));
        assertEquals("Close DRAUPNE Link - armed?", NOT_KNOWN.title("Close DRAUPNE Link"));
        assertFalse(NOT_KNOWN.question("Standby mode").contains("ARMED"), "it doesn't claim armed");
        assertNull(CLEAR.question("Standby mode"));
        assertNull(CLEAR.title("Standby mode"));
        assertEquals("while armed", ARMED.confirmedIn());
        assertEquals("while possibly flying", MAY_FLY.confirmedIn());
        assertEquals("with the armed state not known", NOT_KNOWN.confirmedIn());
    }

    // ------------------------------------------------------------------ "possibly flying"

    @Test
    void aNewLinksFirstStatusDoesNotEndALostDronesWindow() {
        long[] now = {NOW};
        PossiblyFlying flag = new PossiblyFlying(() -> now[0]);
        FcTelemetry.Status[] heard = {status(true, true, NOW)};
        Object first = new Object();
        flag.attach(first, "MobileZulu", () -> PossiblyFlying.of(heard[0]));
        flag.detach(first); // the app closed (asked, confirmed) with the drone in the air
        assertTrue(flag.holds());

        now[0] += 20_000; // started again, PILOT UAV: the first statuses come before the mode names
        heard[0] = status(false, false, now[0]);
        Object again = new Object();
        flag.attach(again, "MobileZulu", () -> PossiblyFlying.of(heard[0]));
        assertNull(PossiblyFlying.of(heard[0]).armed(), "not known - not DISARMED");
        assertTrue(flag.holds(), "a status that can't say doesn't prove the drone is down");
        PossiblyFlying.Hold h = flag.why();
        assertTrue(h.live() && h.notKnown());
        assertEquals("MobileZulu may be armed (not known yet): a drone here may be flying", flag.reason());

        heard[0] = status(true, true, now[0] + 1500); // the names are here: armed, as it was
        now[0] += 1500;
        assertFalse(flag.why().notKnown());
        assertEquals("MobileZulu is armed: a drone here is flying", flag.reason());
        heard[0] = status(false, true, now[0] + 60_000); // landed, DISARMED said
        now[0] += 60_000;
        assertFalse(flag.holds(), "the flight controller's own DISARMED ends it");
    }

    @Test
    void aLinkEndedBeforeItsDroneWasReadHoldsTheWindowAndSaysSo() {
        long[] now = {NOW};
        PossiblyFlying flag = new PossiblyFlying(() -> now[0]);
        Object link = new Object();
        flag.attach(link, "MobileZulu", () -> PossiblyFlying.of(status(false, false, NOW)));
        flag.detach(link);
        now[0] += 3 * MIN;
        assertTrue(flag.holds(), "ended not knowing: it may be flying");
        assertTrue(flag.resumable(), "the pilot's IT'S DOWN - RESUME ends it");
        assertTrue(flag.reason().startsWith("MobileZulu was lost (armed: not known) 3 min ago; a drone here may be flying (until "), flag.reason());
        now[0] += PossiblyFlying.LOST_DRONE_WINDOW_MS;
        assertFalse(flag.holds(), "and the window ends by itself");
        assertEquals(PossiblyFlying.Word.NOTHING, PossiblyFlying.of(null), "no status at all: nothing heard, as before");
    }

    // ------------------------------------------------------------------ FLEET's modes

    @Test
    void aFleetModeIsNotQueuedOnAPhonesFreshNotKnown() {
        Names.Position notKnown = new Names.Position(true, 59.9, 10.7, 0, null, false, "");
        Names.Position disarmed = new Names.Position(true, 59.9, 10.7, 0, false, false, "");
        Names.Position armed = new Names.Position(true, 59.9, 10.7, 0, true, false, "");
        assertEquals("Not known if armed: try again.", FleetManager.refusal("MobileAlpha", notKnown, 2000, false, false, false, 47));
        assertNull(FleetManager.refusal("MobileAlpha", notKnown, FleetManager.FLOWN_FRESH_MS + 1, false, false, false, 47),
                "an old word: queued, the phone decides when it next listens");
        assertNull(FleetManager.refusal("MobileAlpha", null, -1, false, false, false, 47), "no word of its drone: queued as before");
        assertNull(FleetManager.refusal("MobileAlpha", disarmed, 2000, false, false, false, 47));
        assertEquals("Armed: land and disarm first.", FleetManager.refusal("MobileAlpha", armed, 2000, false, false, false, 47));
        assertEquals("Armed: land and disarm first.", FleetManager.refusal("MobileAlpha", notKnown, 2000, true, true, false, 47), "this PC flies it");
        assertEquals("MobileAlpha is flown by another PC.", FleetManager.refusal("MobileAlpha", notKnown, 2000, false, false, true, 47));
    }
}
