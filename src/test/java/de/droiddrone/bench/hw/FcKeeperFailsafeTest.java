package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keeper and the board's own failsafe ({@link FcKeeper.Config#failsafeEndsWindow}). The rig's real board holds its
 * throttle and its ARM switch for twenty seconds when its sticks stop and then starts a GPS rescue - a throttle the
 * flight controller makes itself, which no stick frame carries and the keeper's throttle cap never sees. So on a real
 * board the keeper ends an open arm window itself, long before: the lock and STOP as soon as the board says its
 * receiver is lost, and at the latest two seconds (or half the board's failsafe_delay) after the last stick frame.
 * Here on the made-up board, which is told to notice that its sticks have stopped ({@link FakeFlightBoard#modelsRxLoss}).
 */
class FcKeeperFailsafeTest {
    private static final int[] AT_REST = {1000, 1000, 1000, 1000};
    private final FakeFlightBoard board = KeeperBench.armable();
    private final KeeperBench bench = new KeeperBench(board);
    private StandInPhone phone;

    @AfterEach
    void end() {
        if (phone != null) phone.close();
        bench.close();
        BetaflightUsb.use(null);
    }

    /** As the rig's real board is set: GPS rescue after 20 s of stage 1, the throttle and the ARM switch held meanwhile. */
    private void asTheRealBoardIsSet() {
        board.setUp("set failsafe_procedure = GPS-RESCUE", "set failsafe_delay = 200", "set failsafe_throttle_low_delay = 200", "rxfail 3 h", "rxfail 4 h");
        board.modelsRxLoss = true;
    }

    private void sticksReachTheBoard() throws InterruptedException {
        long before = bench.sticksAtBoard();
        Waits.waitFor(() -> bench.sticksAtBoard() > before + 30, 5000, "stick frames at the board", bench.said);
    }

    @Test
    void aCutInAnOpenWindowIsEndedByTheKeeperAsSoonAsTheBoardSaysItsReceiverIsLost() throws Exception {
        asTheRealBoardIsSet();
        bench.config.failsafeEndsWindow = true;
        bench.start().beat();
        assertEquals(2000, bench.state().number("stickLimitMs"), "the keeper's own clock: 2 s, the shorter of that and half of 20 s");
        phone = bench.phone().sticks(1000, 1000).startSticks();
        sticksReachTheBoard();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        bench.arm(phone);

        long cut = System.nanoTime();
        assertEquals("OK", bench.ask("FAULT starve")); // the phone's frames no longer reach the board: a cut, the keeper alive
        bench.waitForSaid("TRIP: RECEIVER LOST: the board says RXLOSS in an open arm window");
        Waits.waitFor(() -> !board.armed() && board.armingLocked(), 3000, "the board locked and disarmed", bench.said);
        long ms = (System.nanoTime() - cut) / 1_000_000;
        assertTrue(ms < 1500, "on the board's own word, before the keeper's 2 s - and 20 s before the board's stage 2: " + ms + " ms");
        assertEquals(0, board.stage2(), "the board's failsafe never ran");
        assertArrayEquals(AT_REST, board.motors());
        bench.waitForSaid("After that: SAFE: arming locked, disarmed, motors read [1000, 1000, 1000, 1000] (at rest).");
        KeeperClient.State s = bench.state();
        assertEquals(0, s.windowMs());
        assertTrue(s.lastEnd().startsWith("RECEIVER LOST: the board says RXLOSS"), s.toString());
        assertNull(s.tripped(), "a cut is what the week tests: this end asks for no CLEAR");

        // the chain recovers: the sticks reach the board again, the next window opens, and with ARM off and on again it arms
        assertEquals("OK", bench.ask("FAULT clear"));
        phone.sticks(1000, 1000);
        sticksReachTheBoard();
        Waits.waitFor(() -> bench.state().get("blocks").equals("MSP"), 3000, "the board to say its receiver is back", bench.said);
        assertEquals("OK window=10000", bench.ask("OPEN 10"));
        bench.arm(phone);
        assertTrue(bench.ask("CLOSE").startsWith("OK safe=1 locked=1 armed=0"));
    }

    @Test
    void aPulledCableInAnOpenWindowEndsItTheSameWay() throws Exception {
        asTheRealBoardIsSet();
        bench.config.failsafeEndsWindow = true;
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        sticksReachTheBoard();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        bench.arm(phone);
        assertEquals("OK", bench.ask("FAULT cut 800")); // the phone's connection closed and refused for a moment
        Waits.waitFor(() -> bench.state().windowMs() == 0 && !board.armed() && board.armingLocked(), 3000, "the window ended, the board locked and disarmed", bench.said);
        assertTrue(bench.state().lastEnd().startsWith("RECEIVER LOST") || bench.state().lastEnd().startsWith("STICKS STOPPED"), bench.state().toString());
        assertEquals(0, board.stage2());
        assertNull(bench.state().tripped());
        // the cable in again: the phone app connects anew, and the board is as it should be - locked, at rest
        Waits.waitFor(() -> bench.state().get("faults").equals("-"), 3000, "the cut to be over", bench.said);
        try (StandInPhone again = bench.phone().sticks(1000, 1000).startSticks()) {
            sticksReachTheBoard();
            assertFalse(board.armed());
            assertArrayEquals(AT_REST, board.motors());
        }
    }

    @Test
    void aBoardThatSaysNothingOfItIsEndedByTheKeepersOwnClockOnTheStickFrames() throws Exception {
        // this board never says its receiver is lost (as the made-up board was): the keeper has only what it sees pass
        board.setUp("set failsafe_procedure = GPS-RESCUE", "set failsafe_delay = 10"); // 1 s of stage 1: the limit is half of it
        bench.config.failsafeEndsWindow = true;
        bench.start().beat();
        assertEquals(500, bench.state().number("stickLimitMs"));
        phone = bench.phone().sticks(1000, 1000).startSticks();
        sticksReachTheBoard();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        bench.arm(phone);
        long stopped = System.nanoTime();
        phone.stopSticks(); // the phone app goes on being connected and sends no stick frame any more
        bench.waitForSaid("TRIP: STICKS STOPPED: no stick frame has reached the board for");
        long ms = (System.nanoTime() - stopped) / 1_000_000;
        assertTrue(ms >= 400 && ms < 2500, "half a second after the last stick frame, not before: " + ms + " ms");
        Waits.waitFor(() -> !board.armed() && board.armingLocked(), 3000, "the board locked and disarmed", bench.said);
        assertTrue(bench.saidOf("in an open arm window (the keeper's limit is 500 ms: the shorter of 2000 ms and half the board's failsafe_delay of 1.0 s) - the arm window is closed."),
                bench.said.toString());
        assertTrue(bench.state().lastEnd().startsWith("STICKS STOPPED"), bench.state().toString());
        assertNull(bench.state().tripped());
        assertArrayEquals(AT_REST, board.motors());
    }

    @Test
    void noWindowOpensWithoutSticksAtTheBoardWhenTheKeeperGuardsItsFailsafe() throws Exception {
        asTheRealBoardIsSet();
        bench.config.failsafeEndsWindow = true;
        bench.start().beat();
        String no = bench.ask("OPEN 10"); // no phone: nothing sends sticks
        assertTrue(no.startsWith("NO the board says its receiver is lost (RXLOSS): no sticks reach it"), no);
        assertNull(bench.state().tripped(), "a refusal is an answer");
        assertTrue(board.armingLocked());
        bench.close();

        // and on a board that does not say so: by the stick frames the keeper has seen pass
        FakeFlightBoard quiet = KeeperBench.armable();
        try (KeeperBench other = new KeeperBench(quiet)) {
            other.config.failsafeEndsWindow = true;
            other.start().beat();
            assertTrue(other.ask("OPEN 10").startsWith("NO no stick frame has reached the board in the last 750 ms"), other.ask("STATE"));
            try (StandInPhone p = other.phone().sticks(1000, 1000).startSticks()) {
                long before = other.sticksAtBoard();
                Waits.waitFor(() -> other.sticksAtBoard() > before + 30, 5000, "stick frames at the board", other.said);
                assertEquals("OK window=10000", other.ask("OPEN 10"));
                assertTrue(other.ask("CLOSE").startsWith("OK safe=1"));
            }
        }
    }

    @Test
    void withTheGuardOffTheBoardsOwnFailsafeRunsAndOnlyTheLockStopsIt() throws Exception {
        // what the guard is for, seen on the made-up board (whose rescue is made up too: the worst that is feared of the real one)
        board.setUp("set failsafe_procedure = GPS-RESCUE", "set failsafe_delay = 5", "rxfail 3 h", "rxfail 4 h");
        board.modelsRxLoss = true;
        bench.start().beat(); // failsafeEndsWindow is off: the made-up board's and Betaflight-as-a-program's own way
        assertEquals(0, bench.state().number("stickLimitMs"), "no clock of the keeper's on the stick frames");
        phone = bench.phone().sticks(1000, 1000).startSticks();
        sticksReachTheBoard();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        bench.arm(phone);
        assertEquals("OK", bench.ask("FAULT starve"));
        Waits.waitFor(() -> board.stage2() == 1, 4000, "the board's failsafe to reach its stage 2", bench.said);
        assertTrue(board.armed(), "a GPS rescue: armed still, the ARM switch held as rxfail says");
        assertArrayEquals(new int[]{1700, 1700, 1700, 1700}, board.motors(), "its motors far over the rig's 1100 - made by the flight controller: no stick frame, nothing for the throttle cap");
        Waits.waitFor(() -> bench.state().get("blocks").contains("FAILSAFE"), 3000, "the keeper to read FAILSAFE in the board's status", bench.said);
        assertTrue(bench.state().windowMs() > 0, "and nobody has ended the window");
        String closed = bench.ask("CLOSE");
        assertTrue(closed.startsWith("OK safe=1 locked=1 armed=0 atRest=1"), closed);
        assertFalse(board.armed());
    }

    /** The made-up board behind a side that calls itself a real board. */
    private FcKeeper.BoardSide asIfReal() {
        return new FcKeeper.BoardSide() {
            @Override
            public BetaflightUsb.Wire open() throws IOException {
                return board.usb().open(board.port());
            }

            @Override
            public String name() {
                return "REAL1";
            }

            @Override
            public boolean real() {
                return true;
            }
        };
    }

    @Test
    void onARealBoardTheFailsafeIsLetRunOnlyWithBothWordsAndItsSettingsAreAlwaysRead() {
        FcKeeper.Config c = KeeperBench.quick();
        c.readSettings = true;
        c.outputLimitMax = 15;
        IllegalArgumentException guard = assertThrows(IllegalArgumentException.class, () -> new FcKeeper(c, asIfReal(), null));
        assertTrue(guard.getMessage().contains("board.letFailsafeRun=true") && guard.getMessage().contains("board.failsafeSeenOnSitl=true"), guard.getMessage());
        c.failsafeSeenOnSitl = true; // both words: somebody has seen on Betaflight as a program what this procedure does
        new FcKeeper(c, asIfReal(), null);
        c.failsafeSeenOnSitl = false;
        c.failsafeEndsWindow = true; // the rule itself
        new FcKeeper(c, asIfReal(), null);
        c.readSettings = false;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new FcKeeper(c, asIfReal(), null)).getMessage().contains("read"));
        c.readSettings = true;
        c.outputLimitMax = 0;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new FcKeeper(c, asIfReal(), null)).getMessage().contains("board.outputLimitMax"));
        c.outputLimitMax = 101;
        assertThrows(IllegalArgumentException.class, () -> new FcKeeper(c, asIfReal(), null));
    }
}
