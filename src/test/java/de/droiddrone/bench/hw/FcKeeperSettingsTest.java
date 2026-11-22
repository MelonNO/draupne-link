package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the keeper learns of a board before anybody gets a lease on it ({@link FcKeeper.Config#readSettings}): its
 * failsafe settings, rxfail and motor_output_limit, read in one visit to its CLI (the visit ends with "exit":
 * Betaflight restarts once and is locked again) and said in the keeper's first lines - with the two things that
 * follow from them on a real board: the keeper's own clock on the stick frames, and no arm window while the board's
 * motor_output_limit is over what the rig allows ({@link FcKeeper.Config#outputLimitMax}). And a board that cannot
 * arm for reasons of its own is said to be so; that is no failure of the keeper's.
 */
class FcKeeperSettingsTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING;
    private final FakeFlightBoard board = KeeperBench.armable();
    private final KeeperBench bench = new KeeperBench(board);
    private final List<String> log = new ArrayList<>();
    private StandInPhone phone;

    @AfterEach
    void end() {
        if (phone != null) phone.close();
        bench.close();
        BetaflightUsb.use(null);
    }

    /** As a real board's keeper is set up from the hardware file. */
    private static void asForARealBoard(KeeperBench b) {
        b.config.readSettings = true;
        b.config.outputLimitMax = 15;
        b.config.failsafeEndsWindow = true;
    }

    /** The line the keeper said that has these words. */
    private String line(String words) {
        for (String l : bench.said) if (l.contains(words)) return l;
        throw new AssertionError("The keeper said nothing with \"" + words + "\":\n" + String.join("\n", bench.said));
    }

    private void sticksReachTheBoard(KeeperBench b) throws InterruptedException {
        long before = b.sticksAtBoard();
        Waits.waitFor(() -> b.sticksAtBoard() > before + 30, 5000, "stick frames at the board", b.said);
    }

    @Test
    void theBoardsFailsafeAndOutputLimitAreReadInItsCliBeforeAnyLeaseAndSaidInTheFirstLines() throws Exception {
        // as the rig's real board is set
        board.setUp("set failsafe_procedure = GPS-RESCUE", "set failsafe_delay = 200", "set failsafe_throttle_low_delay = 200", "rxfail 3 h",
                "aux 2 36 2 1700 2100 0 0");
        board.modelsRxLoss = true;
        asForARealBoard(bench);
        bench.start().beat();

        assertEquals(List.of("profile", "get failsafe_procedure", "get failsafe_delay", "get failsafe_off_delay", "get failsafe_throttle", "get failsafe_switch_mode",
                "get failsafe_throttle_low_delay", "rxfail", "profile 0", "get motor_output_limit", "profile 1", "get motor_output_limit", "profile 2", "profile 0",
                "exit"), board.cli(), "one visit: read, nothing set, and left with exit");
        assertEquals(1, board.restarts, "leaving the CLI restarts Betaflight, once");
        String up = line("THE KEEPER IS UP");
        assertTrue(up.endsWith(" used). When the sticks stop in an arm window the keeper ends it itself - the lock, STOP - before the board's own failsafe can run. "
                + "No arm window on a board whose motor_output_limit is over 15 (board.outputLimitMax; gradlew boardCap writes it). "
                + "The board's settings are read in its CLI first: Betaflight restarts once before the board is taken."), up);
        assertEquals(0, board.saves);
        assertTrue(board.armingLocked(), "and it is locked again when it is back");
        assertFalse(board.inCli());

        assertTrue(line("The board's own settings").endsWith("The board's own settings, read in its CLI (Betaflight restarts now, and is locked again when it is back): "
                + "failsafe_procedure = GPS-RESCUE, failsafe_delay = 200 (20.0 s), failsafe_off_delay = 10, failsafe_throttle = 1000, failsafe_switch_mode = STAGE1, "
                + "failsafe_throttle_low_delay = 200 - MSP_FAILSAFE_CONFIG says the same; rxfail 0 a, 1 a, 2 a, 3 h, 4 h, 5 h, 6 h, 7 h; "
                + "motor_output_limit = 100 in PID profile 0 (the one in use), 100 in profile 1."), line("The board's own settings"));
        String taken = line("The board is taken");
        assertTrue(taken.contains("The board is taken: MADEUP1, BTFL 4.4.1, disarmed; the arming lock is set and shows as arming-disable flag 16; "
                + "channel map AETR1234 (throttle is raw channel 3), ARM on AUX1 1700-2100, PREARM on AUX3 1700-2100; "), taken);
        assertTrue(taken.contains("; when its sticks stop: rxfail holds the throttle at its last value (3 h) and holds the ARM switch (AUX1: 4 h) for failsafe_delay 200 (20.0 s), "
                + "then GPS-RESCUE - in an arm window the keeper ends it first: the lock and STOP when the board says RXLOSS or FAILSAFE, at the latest 2000 ms after the last "
                + "stick frame; "), taken);
        assertTrue(taken.contains("; motor_output_limit 100 - OVER the 15 this rig allows (board.outputLimitMax): NO ARM WINDOW until gradlew boardCap -Pport=MADEUP1 -Plimit=15 (with the keeper ended: one program has the port); "), taken);
        assertTrue(taken.contains("; motors read [1000, 1000, 1000, 1000] (at rest); 16.40 V, 4.10 V a cell (4 cells), floor 3.50; "), taken);
        assertTrue(taken.endsWith("; cannot arm now: RXLOSS (beside the keeper's own lock)."), taken);

        KeeperClient.State s = bench.state();
        assertEquals("GPS-RESCUE", s.get("failsafe"));
        assertEquals(200, s.number("failsafeDelay"));
        assertEquals(2000, s.number("stickLimitMs"));
        assertEquals(100, s.number("outputLimit"));
        assertEquals(15, s.number("outputLimitMax"));

        // sticks, a heartbeat, a full battery: and no window, for the one reason, said with what to do about it
        phone = bench.phone().sticks(1000, 1000).startSticks();
        sticksReachTheBoard(bench);
        String no = bench.ask("OPEN 10");
        assertEquals("NO the board's motor_output_limit is 100 (in PID profile 0), over the 15 this rig allows (board.outputLimitMax): "
                + "gradlew boardCap -Pport=MADEUP1 -Plimit=15 writes it (with the keeper ended: one program has the port)", no);
        assertNull(s.tripped());
        assertTrue(board.armingLocked());
    }

    @Test
    void withTheCapWrittenInEveryProfileAWindowOpens() throws Exception {
        BetaflightUsb.use(board.usb());
        assertTrue(BoardCap.run(board.port(), 15, T, log::add).capped(), log.toString()); // as gradlew boardCap does it, the keeper not running
        asForARealBoard(bench);
        bench.start().beat();
        assertTrue(line("The board is taken").contains("; motor_output_limit 15 (at or under the 15 this rig allows, in every PID profile); "), line("The board is taken"));
        phone = bench.phone().sticks(1000, 1000).startSticks();
        sticksReachTheBoard(bench);
        assertEquals("OK window=10000", bench.ask("OPEN 10"));
        bench.arm(phone);
        assertTrue(bench.ask("CLOSE").startsWith("OK safe=1 locked=1 armed=0"));
        bench.close();

        // one profile left as it was: no window, and which one is said - Betaflight changes profiles by itself (a pack of other cells) and by the sticks
        FakeFlightBoard half = KeeperBench.armable().setUp("set motor_output_limit = 15", "profile 1", "set motor_output_limit = 60", "profile 0");
        try (KeeperBench other = new KeeperBench(half)) {
            asForARealBoard(other);
            other.start().beat();
            try (StandInPhone p = other.phone().sticks(1000, 1000).startSticks()) {
                sticksReachTheBoard(other);
                assertTrue(other.ask("OPEN 10").startsWith("NO the board's motor_output_limit is 60 (in PID profile 1), over the 15 this rig allows"), other.ask("STATE"));
            }
            assertEquals(60, other.state().number("outputLimit"), "the largest of them");
        }
    }

    @Test
    void whatMspSaysOfTheFailsafeIsHeldToWhatTheCliSays() throws Exception {
        board.setUp("set failsafe_procedure = GPS-RESCUE", "set failsafe_delay = 200");
        // a firmware whose MSP_FAILSAFE_CONFIG is laid out otherwise than the keeper reads it: read so, it says DROP after 1.5 s
        board.mspFailsafeConfig = new byte[]{15, 10, (byte) 0xe8, 3, 0, 100, 0, 1};
        asForARealBoard(bench);
        bench.start().beat();
        assertTrue(line("FINDING: MSP_FAILSAFE_CONFIG").contains("FINDING: MSP_FAILSAFE_CONFIG (75) reads failsafe_procedure DROP, failsafe_delay 15 - the board's CLI says "
                + "GPS-RESCUE, 200: the CLI's word is taken"), line("FINDING: MSP_FAILSAFE_CONFIG"));
        assertEquals("GPS-RESCUE", bench.state().get("failsafe"));
        assertEquals(2000, bench.state().number("stickLimitMs"), "by the CLI's 20 s, not by MSP's 1.5 s");
    }

    @Test
    void afterDroneSetupTheSettingsAreReadAgain() throws Exception {
        asForARealBoard(bench);
        bench.start().beat();
        assertEquals("DROP", bench.state().get("failsafe"));
        assertEquals(750, bench.state().number("stickLimitMs"), "half of the stock 1.5 s");
        int said = bench.said.size();
        BetaflightUsb.use(KeeperWire.usb(bench.keeper.setupPort()));
        try (BetaflightUsb fc = BetaflightUsb.open(BetaflightUsb.findFlightControllers().get(0))) { // the app's Drone setup changes the failsafe
            fc.enterCli();
            fc.command("set failsafe_procedure = AUTO-LANDING");
            fc.command("set failsafe_delay = 30");
            fc.saveAndReboot();
        }
        Waits.waitFor(() -> bench.state().boardUp() && bench.state().get("failsafe").equals("AUTO-LANDING"), 8000, "the keeper to read the board's settings anew", bench.said);
        assertEquals(1500, bench.state().number("stickLimitMs"));
        assertTrue(bench.said.subList(said, bench.said.size()).stream().anyMatch(l -> l.contains("The board's own settings, read in its CLI")
                && l.contains("failsafe_procedure = AUTO-LANDING, failsafe_delay = 30 (3.0 s)")), bench.said.toString());
        assertEquals(1, board.saves);
        assertEquals(3, board.restarts, "the first read's exit, Drone setup's save, the second read's exit");
        assertTrue(board.armingLocked());
    }

    @Test
    void aCliVisitThatIsCutIsMadeAgainAndASettingThatCannotBeReadMeansNoWindow() throws Exception {
        board.cutAfterCliCommands = 2; // the wire goes in the middle of the first visit (a battery keeps the board up)
        board.cutTakesPower = false;
        asForARealBoard(bench);
        bench.start().beat();
        assertEquals("DROP", bench.state().get("failsafe"), "read at the second visit");
        assertEquals(100, bench.state().number("outputLimit"));
        assertFalse(board.inCli(), "the board the cut left in its CLI was taken out of it");
        bench.close();

        FakeFlightBoard odd = KeeperBench.armable();
        odd.refuseCli("get motor_output_limit"); // a firmware that has no such setting
        try (KeeperBench other = new KeeperBench(odd)) {
            asForARealBoard(other);
            other.start().beat();
            assertTrue(other.saidOf("; motor_output_limit NOT READ (its CLI: ###ERROR IN get: REFUSED (the test asked for it)###): NO ARM WINDOW on this board; "), other.said.toString());
            assertEquals(-1, other.state().number("outputLimit"));
            try (StandInPhone p = other.phone().sticks(1000, 1000).startSticks()) {
                long before = other.sticksAtBoard();
                Waits.waitFor(() -> other.sticksAtBoard() > before + 30, 5000, "stick frames at the board", other.said);
                assertTrue(other.ask("OPEN 10").startsWith("NO the board's motor_output_limit is not known"), other.ask("STATE"));
            }
            assertTrue(odd.armingLocked());
        }
    }

    @Test
    void aBoardThatCannotArmWithItsBatteryOutIsSaidSoAndNothingOfItIsAFailure() throws Exception {
        board.setUp("set dshot_bidir = ON"); // bidirectional DShot, as the rig's board has it
        board.battery(0.3, 0);               // the battery out: USB power alone, no ESC answers the flight controller
        board.modelsRxLoss = true;
        asForARealBoard(bench);
        bench.start().beat();
        assertTrue(line("The board is taken").endsWith("; 0.30 V, 0.08 V a cell (4 cells) - UNDER the floor of 3.50; cannot arm now: RXLOSS+RPMFILTER (beside the keeper's own lock)."),
                line("The board is taken"));
        assertEquals("RXLOSS+MSP+RPMFILTER", bench.state().get("blocks"));
        phone = bench.phone().sticks(1000, 2000).startSticks(); // sticks that ask for ARM
        sticksReachTheBoard(bench);
        Waits.waitFor(() -> bench.state().get("blocks").equals("MSP+RPMFILTER+ARMSWITCH"), 3000, "the board's blocks with sticks reaching it", bench.said);
        assertFalse(board.armed());
        assertTrue(bench.ask("OPEN 10").startsWith("NO the battery is at 0.08 V a cell"), "no window either: the battery is out");
        KeeperClient.State s = bench.state();
        assertEquals("RPMFILTER+ARMSWITCH", s.cannotArm());
        // the hardware scenario's film line for it: the board's word, and the scenario stays green
        assertEquals("cannot arm: RPMFILTER+ARMSWITCH - the board's own word beside the keeper's lock, and no failure of the keeper's; RPMFILTER is bidirectional "
                + "DShot with no ESC answering (the battery out, or an ESC that says nothing).", BoardChainScenario.cannotArm(s));
        assertNull(s.tripped());
        assertEquals(0, s.number("trips"));
        assertEquals(0, s.number("findings"), "what the board cannot do is no finding of the keeper's: " + bench.said);
        String ended = bench.ask("END");
        assertTrue(ended.startsWith("OK safe=1 locked=1 armed=0 atRest=1"), ended);
        assertTrue(bench.keeper.awaitEnd().safe() && !bench.keeper.failed());
    }

    @Test
    void aWindowOnABoardThatStillCannotArmSaysWhatHoldsIt() throws Exception {
        board.setUp("set dshot_bidir = ON");
        board.escTelemetry = false; // the pack is in and no ESC answers (a plug off, an ESC firmware without it)
        BetaflightUsb.use(board.usb());
        assertTrue(BoardCap.run(board.port(), 15, T, log::add).capped(), log.toString());
        asForARealBoard(bench);
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        sticksReachTheBoard(bench);
        assertEquals("OK window=10000 cannotArm=RPMFILTER", bench.ask("OPEN 10"));
        bench.waitForSaid("ARM WINDOW OPEN for 10 s: the arming lock is off - and the board cannot arm yet: RPMFILTER.");
        phone.sticks(1000, 2000);
        Thread.sleep(200);
        assertFalse(board.armed());
        assertTrue(bench.state().get("blocks").startsWith("RPMFILTER"), bench.state().toString());
        assertTrue(bench.ask("CLOSE").startsWith("OK safe=1 locked=1 armed=0"));
        assertNull(bench.state().tripped());
    }
}
