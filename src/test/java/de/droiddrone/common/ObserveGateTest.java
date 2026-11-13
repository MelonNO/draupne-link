package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static de.droiddrone.common.Observe.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Who may observe the phone, when it ends for all, and the observers' sound (moved out of the phone's Udp). Written
 * from the code as it stood before the move - Udp.java of phone app pc.52 (commit 2047470); each test names the lines
 * it pins, and {@link #everyCaseAnswersAsTheOldCodeDid} runs every combination against those lines copied here.
 */
class ObserveGateTest {
    private static final BooleanSupplier DISARMED = () -> true, NOT_DISARMED = () -> false;
    private static final BooleanSupplier NOT_ASKED = () -> fail("the FC is not asked here");
    /** The pilot was last heard long ago (or never). */
    private static final long SILENT = 60_000;
    /** The phone's power modes (PowerMode in the phone app). */
    private static final int NORMAL = 0, STANDBY = 1, DEEP_SLEEP = 2, OVERWATCH = 3;

    /** Lines 2555-2571: nothing in the way - on to the table (2572-2582), which says streaming or full. */
    @Test
    void anIdleDisarmedPhoneInNormalModeGoesOnToTheTable() {
        assertEquals(STREAMING, ObserveGate.request(false, SILENT, false, DISARMED, true, false));
        assertEquals(STREAMING, ObserveGate.request(false, SILENT, true, DISARMED, true, false));
    }

    /** Lines 2555-2557: isConnected() comes first - whoever asks, its own PC too, and nothing else is looked at. */
    @Test
    void aConnectedPilotRefusesEveryObserver() {
        assertEquals(REFUSED_PILOT, ObserveGate.request(true, 0, false, NOT_ASKED, true, false));
        assertEquals(REFUSED_PILOT, ObserveGate.request(true, 0, true, NOT_ASKED, true, false), "the pilot's own PC too");
        assertEquals(REFUSED_PILOT, ObserveGate.request(true, SILENT, false, NOT_ASKED, false, true), "before the FC and the power mode");
    }

    /** Lines 2555-2556 and 182: within OBSERVE_AFTER_PILOT_MS (10 s) of the pilot's last packet, but for that very PC. */
    @Test
    void aPilotHeardWithinTenSecondsRefusesEveryPcButItsOwn() {
        assertEquals(10_000, ObserveGate.AFTER_PILOT_MS, "Takeover.PILOT_ALIVE_MS, as OBSERVE_AFTER_PILOT_MS was");
        assertEquals(Takeover.PILOT_ALIVE_MS, ObserveGate.AFTER_PILOT_MS);
        assertEquals(REFUSED_PILOT, ObserveGate.request(false, 0, false, NOT_ASKED, true, false));
        assertEquals(REFUSED_PILOT, ObserveGate.request(false, ObserveGate.AFTER_PILOT_MS - 1, false, NOT_ASKED, true, false));
        assertEquals(STREAMING, ObserveGate.request(false, ObserveGate.AFTER_PILOT_MS, false, DISARMED, true, false), "10 s to the ms: no longer near");
        assertEquals(STREAMING, ObserveGate.request(false, 0, true, DISARMED, true, false), "its own PC, back at the grid: at once");
        assertEquals(STREAMING, ObserveGate.request(false, ObserveGate.AFTER_PILOT_MS - 1, true, DISARMED, true, false));
    }

    /** Line 2558: FcArmedState != DISARMED (armed or unknown) - after the pilot's check, before the power mode's. */
    @Test
    void anFcNotKnownDisarmedRefuses() {
        assertEquals(REFUSED_ARMED, ObserveGate.request(false, SILENT, false, NOT_DISARMED, true, false));
        assertEquals(REFUSED_ARMED, ObserveGate.request(false, SILENT, true, NOT_DISARMED, true, false), "the last pilot's PC too");
        assertEquals(REFUSED_ARMED, ObserveGate.request(false, SILENT, false, NOT_DISARMED, false, true), "said before the power mode");
        assertEquals(REFUSED_PILOT, ObserveGate.request(false, 500, false, NOT_ASKED, true, false), "a pilot near is said first");
    }

    /** Lines 2555-2558: FcArmedState.of (it notes "seen armed") is called once, and only when no pilot is near. */
    @Test
    void theFcIsAskedOnceAndOnlyWhenNoPilotIsNear() {
        int[] asked = {0};
        BooleanSupplier fc = () -> {
            asked[0]++;
            return true;
        };
        ObserveGate.request(true, SILENT, false, fc, true, false);
        ObserveGate.request(false, 100, false, fc, true, false);
        assertEquals(0, asked[0], "a pilot connected or near: not asked");
        ObserveGate.request(false, SILENT, false, fc, true, false);
        assertEquals(1, asked[0]);
        ObserveGate.request(false, SILENT, false, fc, false, true);
        assertEquals(2, asked[0], "asked before the power mode is looked at");
    }

    /**
     * Lines 2559-2562 (pc.38): PowerMode.mode() != NORMAL || Overwatch.isWatching() - standby, deep sleep, overwatch, and
     * overwatch still ending after the mode went back to normal.
     */
    @Test
    void outsideNormalPowerOrWhileOverwatchWatchesItIsRefused() {
        assertEquals(REFUSED_POWER, ObserveGate.request(false, SILENT, false, DISARMED, false, false), "standby or deep sleep");
        assertEquals(REFUSED_POWER, ObserveGate.request(false, SILENT, false, DISARMED, false, true), "overwatch");
        assertEquals(REFUSED_POWER, ObserveGate.request(false, SILENT, false, DISARMED, true, true), "normal again, overwatch still ending");
        assertEquals(REFUSED_POWER, ObserveGate.request(false, SILENT, true, DISARMED, false, false), "the last pilot's PC too");
    }

    /**
     * Lines 2563-2570 (pc.52): inside the power refusal only - PowerMode.mode() == STANDBY && !Overwatch.isWatching()
     * logs "asks in standby: waking" and asks for NORMAL; the answer stays REFUSED_POWER.
     */
    @Test
    void onlyAnObserverRefusedInStandbyWakesThePhone() {
        int refused = ObserveGate.request(false, SILENT, false, DISARMED, false, false);
        assertEquals(REFUSED_POWER, refused, "this request is still refused: the next one, a second on, gets the stream");
        assertTrue(ObserveGate.wakes(refused, true, false), "standby: woken");
        assertFalse(ObserveGate.wakes(refused, false, false), "deep sleep or overwatch: stays refused, not woken");
        assertFalse(ObserveGate.wakes(refused, true, true), "overwatch still watching: not woken");
        assertFalse(ObserveGate.wakes(refused, false, true));
        // refused for another reason, the phone in standby: the wake is never reached
        assertFalse(ObserveGate.wakes(REFUSED_PILOT, true, false), "a pilot near (standby keeps its session)");
        assertFalse(ObserveGate.wakes(REFUSED_ARMED, true, false), "the FC not known disarmed");
        assertFalse(ObserveGate.wakes(STREAMING, true, false));
        assertFalse(ObserveGate.wakes(REFUSED_FULL, true, false));
    }

    /** Lines 2668-2673: with observers in the table - pilot || armed || power ends it, said in that order. */
    @Test
    void withObserversItEndsForAllAndTheFirstReasonIsSaid() {
        assertEquals(STREAMING, ObserveGate.ended(false, true, true, false), "nothing in the way: it goes on");
        assertEquals(ENDED_PILOT, ObserveGate.ended(true, true, true, false));
        assertEquals(ENDED_PILOT, ObserveGate.ended(true, false, false, true), "a pilot is said first");
        assertEquals(REFUSED_ARMED, ObserveGate.ended(false, false, true, false));
        assertEquals(REFUSED_ARMED, ObserveGate.ended(false, false, false, true), "armed before the power mode");
        assertEquals(REFUSED_POWER, ObserveGate.ended(false, true, false, false), "standby, a sleep");
        assertEquals(REFUSED_POWER, ObserveGate.ended(false, true, true, true), "pc.38: overwatch watches");
        assertEquals(REFUSED_POWER, ObserveGate.ended(false, true, false, true));
    }

    /** Line 2610 (pc.45): state == STREAMING && isSoundHeard() ? STATE_SOUND_HEARD : 0. */
    @Test
    void soundHeardGoesOnlyWithAStreamingAnswer() {
        assertEquals(STATE_SOUND_HEARD, ObserveGate.stateFlags(STREAMING, true));
        assertEquals(0, ObserveGate.stateFlags(STREAMING, false));
        for (int state : new int[]{REFUSED_PILOT, ENDED_PILOT, REFUSED_FULL, REFUSED_POWER, STOPPED, REFUSED_ARMED}) {
            assertEquals(0, ObserveGate.stateFlags(state, true), stateName(state));
        }
    }

    /** Lines 747 and 751 (audioGateRun): observed = observing && observeAudio && !isConnected(); allowed = observed && disarmed && normal. */
    @Test
    void theObserversSoundStreamsOnlyWantedDisarmedInNormalModeWithNoPilot() {
        assertTrue(ObserveGate.soundWanted(true, true, false));
        assertFalse(ObserveGate.soundWanted(false, true, false), "nobody observes");
        assertFalse(ObserveGate.soundWanted(true, false, false), "every tile muted");
        assertFalse(ObserveGate.soundWanted(true, true, true), "a pilot is connected: its own AUDIO decides");
        assertTrue(ObserveGate.soundAllowed(true, true, true));
        assertFalse(ObserveGate.soundAllowed(false, true, true));
        assertFalse(ObserveGate.soundAllowed(true, false, true), "never while not known disarmed");
        assertFalse(ObserveGate.soundAllowed(true, true, false), "standby, a sleep");
    }

    /** Lines 758-760 (pc.45): listen = !allow && observing && !observeAudio && !isConnected() && normal && FC DISARMED. */
    @Test
    void mutedObserversAreListenedForOnlyDisarmedInNormalModeWithNoPilot() {
        assertTrue(ObserveGate.listens(false, true, false, false, true, DISARMED));
        assertFalse(ObserveGate.listens(true, true, false, false, true, NOT_ASKED), "the sound itself streams");
        assertFalse(ObserveGate.listens(false, false, false, false, true, NOT_ASKED), "nobody observes");
        assertFalse(ObserveGate.listens(false, true, true, false, true, NOT_ASKED), "an observer wants the sound: that takes the microphone");
        assertFalse(ObserveGate.listens(false, true, false, true, true, NOT_ASKED), "a pilot is connected");
        assertFalse(ObserveGate.listens(false, true, false, false, false, NOT_ASKED), "not in normal power mode");
        assertFalse(ObserveGate.listens(false, true, false, false, true, NOT_DISARMED), "the FC asked last: not known disarmed");
    }

    // ------------------------------------------------------------------ against the old code, case by case

    /** Udp.onObserve lines 2554-2571 as they were, the phone's reads as arguments: {state, or -1 for "the table"; wakes}. */
    private static int[] oldRequest(boolean isConnected, long silentMs, boolean fromLastPcIp, boolean fcDisarmed, int mode, boolean isWatching) {
        int state;
        boolean wake = false;
        boolean pilotNear = isConnected || silentMs < 10_000
                && !fromLastPcIp;
        if (pilotNear) state = REFUSED_PILOT;
        else if (!fcDisarmed) state = REFUSED_ARMED;
        else if (mode != NORMAL || isWatching) {
            state = REFUSED_POWER;
            if (mode == STANDBY && !isWatching) wake = true;
        } else {
            state = -1;
        }
        return new int[]{state, wake ? 1 : 0};
    }

    /** Udp.observeTick lines 2669-2673 as they were: the end state, or -1 while nothing ends. */
    private static int oldEnded(boolean pilot, boolean fcDisarmed, int mode, boolean isWatching) {
        boolean armed = !fcDisarmed;
        boolean power = mode != NORMAL || isWatching;
        if (pilot || armed || power) return pilot ? ENDED_PILOT : armed ? REFUSED_ARMED : REFUSED_POWER;
        return -1;
    }

    @Test
    void everyCaseAnswersAsTheOldCodeDid() {
        boolean[] yesNo = {false, true};
        long[] silences = {0, 1, 9_999, 10_000, 10_001, SILENT, Long.MAX_VALUE / 2};
        int requests = 0;
        for (boolean connected : yesNo) for (long silent : silences) for (boolean lastPc : yesNo) for (boolean disarmed : yesNo)
            for (int mode = NORMAL; mode <= OVERWATCH; mode++) for (boolean watching : yesNo) {
                int[] old = oldRequest(connected, silent, lastPc, disarmed, mode, watching);
                String what = "connected " + connected + ", silent " + silent + ", last PC " + lastPc + ", disarmed " + disarmed
                        + ", mode " + mode + ", watching " + watching;
                int state = ObserveGate.request(connected, silent, lastPc, () -> disarmed, mode == NORMAL, watching);
                assertEquals(old[0] < 0 ? STREAMING : old[0], state, what);
                assertEquals(old[1] == 1, ObserveGate.wakes(state, mode == STANDBY, watching), "wakes: " + what);
                requests++;
            }
        assertEquals(2 * 7 * 2 * 2 * 4 * 2, requests);
        for (boolean pilot : yesNo) for (boolean disarmed : yesNo) for (int mode = NORMAL; mode <= OVERWATCH; mode++) for (boolean watching : yesNo) {
            int old = oldEnded(pilot, disarmed, mode, watching);
            assertEquals(old < 0 ? STREAMING : old, ObserveGate.ended(pilot, disarmed, mode == NORMAL, watching),
                    "pilot " + pilot + ", disarmed " + disarmed + ", mode " + mode + ", watching " + watching);
        }
        for (boolean observing : yesNo) for (boolean observeAudio : yesNo) for (boolean connected : yesNo) for (boolean disarmed : yesNo)
            for (boolean normal : yesNo) for (boolean pilotAllows : yesNo) {
                // audioGateRun lines 747-751 and 759-760 as they were (pilotAllows: what pcAudio.allowed said)
                boolean observed = observing && observeAudio && !connected;
                boolean allow = connected ? pilotAllows : observed && disarmed && normal;
                boolean listen = !allow && observing && !observeAudio && !connected && normal
                        && disarmed;
                boolean wanted = ObserveGate.soundWanted(observing, observeAudio, connected);
                assertEquals(observed, wanted);
                boolean allowed = connected ? pilotAllows : ObserveGate.soundAllowed(wanted, disarmed, normal);
                assertEquals(allow, allowed);
                assertEquals(listen, ObserveGate.listens(allowed, observing, observeAudio, connected, normal, () -> disarmed));
            }
    }
}
