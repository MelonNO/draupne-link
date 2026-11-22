package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The faults the rig can ask the keeper for ({@link FcKeeper}, FAULT on the control channel), each on the made-up
 * board with a stand-in phone sending sticks 250 times a second: cut, starve, stall, delay, garbage - and that
 * garbage never goes to a real board.
 */
class FcKeeperFaultTest {
    @TempDir
    Path dir;
    private final FakeFlightBoard board = KeeperBench.armable();
    private final KeeperBench bench = new KeeperBench(board);
    private StandInPhone phone;

    @AfterEach
    void end() {
        if (phone != null) phone.close();
        bench.close();
    }

    private void sticksFlow(String when) throws InterruptedException {
        long before = bench.sticksAtBoard();
        Waits.waitFor(() -> bench.sticksAtBoard() > before + 50, 5000, "stick frames at the board " + when, bench.said);
    }

    @Test
    void cutIsAPulledCableUntilItIsCleared() throws Exception {
        bench.start();
        phone = bench.phone().startSticks();
        sticksFlow("before the cut");
        assertEquals("OK", bench.ask("FAULT cut"));
        Waits.waitFor(phone::ended, 3000, "the phone's connection to be closed", bench.said);
        assertEquals("cut", bench.state().get("faults"));
        long at = bench.sticksAtBoard();
        for (int i = 0; i < 5; i++) {
            try (StandInPhone again = bench.phone().startSticks()) {
                Waits.waitFor(again::ended, 3000, "a connection during the cut to be closed at once", bench.said);
                assertEquals(0, again.answers().size(), "and not one byte comes back");
            }
        }
        assertEquals(at, bench.sticksAtBoard(), "nothing reaches the board while it is cut");
        assertTrue(bench.state().number("refused") >= 5);
        assertEquals("OK", bench.ask("FAULT clear"));
        try (StandInPhone back = bench.phone().startSticks()) {
            sticksFlow("after the cut");
            assertFalse(back.ended());
        }
        // for a time: over by itself
        assertEquals("OK", bench.ask("FAULT cut 300"));
        Waits.waitFor(() -> bench.state().get("faults").equals("-"), 3000, "the cut to end by itself", bench.said);
        try (StandInPhone back = bench.phone().startSticks()) {
            sticksFlow("after the timed cut");
        }
        assertTrue(bench.saidOf("FAULT: cut for 300 ms.") && bench.saidOf("FAULT over: none any more."), bench.said.toString());
    }

    @Test
    void starveTakesThePhonesFramesAndPassesNoneOn() throws Exception {
        bench.start();
        phone = bench.phone().startSticks();
        sticksFlow("before");
        assertEquals("OK", bench.ask("FAULT starve"));
        Thread.sleep(100); // what was on its way
        long at = bench.sticksAtBoard(), sent = phone.sticksSent(), answered = phone.answersTo(Msp.STATUS);
        Waits.waitFor(() -> phone.sticksSent() > sent + 200, 5000, "the phone to go on sending (its frames are taken)", bench.said);
        assertTrue(bench.sticksAtBoard() <= at + 1, "the board gets none of them");
        assertTrue(phone.answersTo(Msp.STATUS) <= answered + 1, "and so nothing comes back to the phone");
        assertFalse(phone.ended(), "the connection itself stays");
        assertTrue(board.armingLocked());
        assertEquals("OK", bench.ask("FAULT clear"));
        sticksFlow("after");
    }

    @Test
    void stallStopsReadingThePhoneAndLosesNothing() throws Exception {
        bench.start();
        phone = bench.phone().sticksOnly().startSticks();
        sticksFlow("before");
        assertEquals("OK", bench.ask("FAULT stall"));
        Thread.sleep(150); // the read that was under way
        long at = bench.sticksAtBoard();
        long sent = phone.sticksSent();
        Waits.waitFor(() -> phone.sticksSent() > sent + 150, 5000, "the phone to go on writing into a connection nobody reads", bench.said);
        assertTrue(bench.sticksAtBoard() <= at + 2, "none of it is read, so none of it reaches the board: " + at + " -> " + bench.sticksAtBoard());
        assertEquals("OK", bench.ask("FAULT clear"));
        phone.stopSticks();
        long all = phone.sticksSent();
        Waits.waitFor(() -> bench.sticksAtBoard() >= all, 5000, "every frame written during the stall to reach the board after it (" + all + ")", bench.said);
        assertEquals(all, bench.sticksAtBoard(), "held back, not lost - and none twice");
    }

    @Test
    void delayHoldsEveryFrameBothWaysAndKeepsTheirOrder() throws Exception {
        bench.folder(dir).start();
        phone = bench.phone();
        phone.request(Msp.STATUS, null);
        Waits.waitFor(() -> phone.answersTo(Msp.STATUS) == 1, 3000, "an answer without a delay", bench.said);
        assertEquals("OK", bench.ask("SCENARIO delay"));
        assertEquals("OK", bench.ask("FAULT delay 150"));
        long t0 = System.nanoTime();
        phone.request(Msp.STATUS, null);
        Waits.waitFor(() -> phone.answersTo(Msp.STATUS) == 2, 5000, "the delayed answer", bench.said);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 280, "150 ms to the board and 150 ms back: " + ms + " ms");
        // order: a run of stick frames with the throttle counting up arrives as it was sent
        for (int i = 0; i < 40; i++) phone.send(StandInPhone.stickFrame(1000 + i, 1000));
        long before = bench.sticksAtBoard();
        Waits.waitFor(() -> bench.sticksAtBoard() >= before + 40, 5000, "the forty held frames at the board", bench.said);
        assertEquals("OK", bench.ask("FAULT clear"));
        assertEquals("OK", bench.ask("SCENARIO -"));
        List<String> frames = Files.readAllLines(dir.resolve("frames-delay.log"));
        int last = 999, seen = 0;
        for (String line : frames) {
            if (!line.contains(" phone>board v2 < " + Msp.SET_RAW_RC + " ")) continue;
            int throttle = Integer.parseInt(line.replaceAll(".* thr=(\\d+) .*", "$1"));
            long held = Long.parseLong(line.replaceAll(".* held=(\\d+) .*", "$1"));
            assertEquals(last + 1, throttle, "in the order they were sent");
            assertTrue(held >= 145_000, "each held its 150 ms: " + line);
            last = throttle;
            seen++;
        }
        assertEquals(40, seen);
    }

    @Test
    void garbageGoesToThePhoneAndToAMadeUpBoardButNeverToARealOne() throws Exception {
        bench.start();
        phone = bench.phone();
        phone.request(Msp.STATUS, null);
        Waits.waitFor(() -> phone.answersTo(Msp.STATUS) == 1, 3000, "an answer", bench.said);
        assertEquals("OK", bench.ask("FAULT garbage phone 64"));
        Waits.waitFor(() -> phone.junkIn() == 64, 3000, "64 bytes of garbage at the phone", bench.said);
        phone.request(Msp.STATUS, null);
        Waits.waitFor(() -> phone.answersTo(Msp.STATUS) == 2, 3000, "answers after the garbage", bench.said);

        int restarts = board.restarts;
        assertEquals("OK", bench.ask("FAULT garbage board 256"));
        phone.request(Msp.STATUS, null);
        Waits.waitFor(() -> phone.answersTo(Msp.STATUS) == 3, 3000, "the made-up board to answer after the garbage", bench.said);
        assertFalse(board.inCli() || board.inBootloader(), "garbage is noise: no '#', no 'R'");
        assertEquals(restarts, board.restarts);
        assertTrue(bench.ask("FAULT garbage radio 3").startsWith("NO garbage to the phone or to the board?"));
        assertTrue(bench.ask("FAULT flood").startsWith("NO not a fault the keeper knows"));
        assertTrue(bench.ask("FAULT delay").startsWith("NO FAULT delay takes numbers"));
        bench.close();

        // the same keeper on a board that calls itself real: the made-up board behind a side that says so
        FakeFlightBoard other = KeeperBench.armable();
        FcKeeper.BoardSide real = new FcKeeper.BoardSide() {
            @Override
            public BetaflightUsb.Wire open() throws IOException {
                return other.usb().open(other.port());
            }

            @Override
            public String name() {
                return "a board that counts as real";
            }

            @Override
            public boolean real() {
                return true;
            }
        };
        try (KeeperBench strict = new KeeperBench(other)) {
            strict.config.failsafeEndsWindow = true; // what a keeper has to be told for a board that counts as real
            strict.config.readSettings = true;
            strict.config.outputLimitMax = 15;
            strict.start(real);
            try (StandInPhone p = strict.phone()) {
                p.request(Msp.STATUS, null);
                Waits.waitFor(() -> p.answersTo(Msp.STATUS) == 1, 3000, "an answer", strict.said);
                assertEquals("NO garbage is never sent to a real board", strict.ask("FAULT garbage board 16"));
                assertEquals("OK", strict.ask("FAULT garbage phone 16"), "toward the phone it is allowed");
                Waits.waitFor(() -> p.junkIn() == 16, 3000, "the garbage at the phone", strict.said);
            }
        }
    }
}
