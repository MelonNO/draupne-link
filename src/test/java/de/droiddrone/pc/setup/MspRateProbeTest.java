package de.droiddrone.pc.setup;

import de.droiddrone.bench.hw.FakeFlightBoard;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.MspFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gradlew mspRateProbe's parts, and the probe itself on a made-up board ({@link FakeFlightBoard}) - once behind a
 * USB that is as slow as a code read says an F4's is with Betaflight 4.4.1 ({@link SlowVcp}). No COM port.
 */
class MspRateProbeTest {
    private static final int RAW_RC = MspRateProbe.MSP_SET_RAW_RC, ATTITUDE = MspRateProbe.MSP_ATTITUDE;

    private static long ms(double ms) {
        return Math.round(ms * 1_000_000L);
    }

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);

    private String printed() {
        return printed.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ pairing and statistics

    private static MspRateProbe.Pairing pairing(Object[][] sent, Object[][] answers) {
        MspRateProbe.Pairing p = new MspRateProbe.Pairing();
        for (Object[] s : sent) p.sent(((Number) s[0]).intValue(), ms(((Number) s[1]).doubleValue()));
        for (Object[] a : answers) p.answer(((Number) a[0]).intValue(), a.length > 2, ms(((Number) a[1]).doubleValue()));
        return p;
    }

    @Test
    void eachAnswerGetsItsEarliestAndLatestPossibleFrame() {
        MspRateProbe.Pairing p = pairing(
                new Object[][]{{RAW_RC, 0}, {ATTITUDE, 1}, {RAW_RC, 2}, {RAW_RC, 3}, {ATTITUDE, 4}},
                new Object[][]{{RAW_RC, 10}, {RAW_RC, 12}, {FcCommon.MSP_RX_MAP, 13}, {ATTITUDE, 14, "error"}});
        int[] early = MspRateProbe.earliest(p.sent(), p.answers());
        // the attitude request between the first two stick frames got no answer; which two of three stick frames did is open
        assertArrayEquals(new int[]{0, 2, -1, 4}, early, "an answer to nothing sent here: -1");
        assertArrayEquals(new int[]{2, 3, -1, 4}, MspRateProbe.latest(p.sent(), p.answers(), early));
        assertEquals(ms(14), p.lastAnswerNs(0));
        assertTrue(p.answers().get(3).error());
    }

    @Test
    void anAnswerIsNeverTakenForAFrameSentAfterItCame() {
        MspRateProbe.Pairing p = pairing(new Object[][]{{ATTITUDE, 0}, {ATTITUDE, 20}}, new Object[][]{{ATTITUDE, 5}, {ATTITUDE, 15}});
        int[] early = MspRateProbe.earliest(p.sent(), p.answers());
        assertArrayEquals(new int[]{0, -1}, early);
        assertArrayEquals(new int[]{0, -1}, MspRateProbe.latest(p.sent(), p.answers(), early));
        assertEquals(ms(7), new MspRateProbe.Pairing().lastAnswerNs(ms(7)), "no answer yet");
    }

    @Test
    void delaysAreOfTheWindowsSecondHalfAndAnAnswerAfterTheLostTimeIsLost() {
        MspRateProbe.Pairing p = pairing(
                new Object[][]{{RAW_RC, 0}, {ATTITUDE, 50}, {RAW_RC, 100}, {RAW_RC, 600}, {RAW_RC, 700}, {ATTITUDE, 800}},
                new Object[][]{{RAW_RC, 10}, {ATTITUDE, 55}, {RAW_RC, 120}, {RAW_RC, 630}, {RAW_RC, 3701}}); // the last: 3001 ms, too late
        MspRateProbe.Window w = MspRateProbe.summary(250, p.sent(), p.answers(), 0, ms(1000), 3000, ms(2701));
        assertEquals(4, w.sticks().sent());
        assertEquals(3, w.sticks().answered());
        assertEquals(1, w.sticks().lost());
        assertEquals(1, w.sticks().longest().count(), "only the frame sent at 600 ms: the one at 700 was answered too late");
        assertEquals("median 30 ms, 90 % 30 ms, max 30 ms", w.sticks().delays(), "nothing lost before it: one answer, one frame");
        assertEquals(2, w.requests().sent());
        assertEquals(1, w.requests().lost());
        assertEquals("no answers", w.requests().delays());
        assertEquals(0, w.strays());
    }

    @Test
    void aLostFrameAmongAlikeOnesLeavesTheDelayARange() {
        MspRateProbe.Pairing p = pairing(new Object[][]{{RAW_RC, 0}, {RAW_RC, 4}, {RAW_RC, 8}}, new Object[][]{{RAW_RC, 100}, {RAW_RC, 104}});
        MspRateProbe.Window w = MspRateProbe.summary(250, p.sent(), p.answers(), -ms(10), ms(10), 3000, ms(96));
        assertEquals(1, w.sticks().lost());
        assertEquals("median 96-100 ms, 90 % 96-100 ms, max 96-100 ms", w.sticks().delays());
    }

    @Test
    void percentilesAreByNearestRank() {
        Long[] tenToOne = new Long[10];
        for (int i = 0; i < 10; i++) tenToOne[i] = ms(10 - i);
        MspRateProbe.Delays d = MspRateProbe.Delays.of(Arrays.asList(tenToOne));
        assertEquals(5, d.median(), 1e-9);
        assertEquals(9, d.p90(), 1e-9);
        assertEquals(10, d.max(), 1e-9);
        assertEquals(7, MspRateProbe.Delays.of(List.of(ms(7))).p90(), 1e-9);
        assertEquals("5", MspRateProbe.range(5.2, 4.9));
        assertEquals("96-100", MspRateProbe.range(100, 96));
        assertEquals("7", MspRateProbe.range(Double.NaN, 7));
    }

    // ------------------------------------------------------------------ what it sends

    @Test
    void theIdleSticksGoWhereTheBoardsChannelMapSaysAsThePhoneSendsThem() {
        byte[] f = MspRateProbe.stickFrame(new int[]{0, 1, 3, 2, 4, 5, 6, 7}, 8); // Betaflight's AETR1234
        assertEquals(25, f.length, "MSP v2, 8 channels: the phone's frame");
        MspFrames.Frame frame = new MspFrames().feed(f, f.length).get(0);
        assertEquals(MspFrames.V2, frame.version);
        assertEquals(RAW_RC, frame.code);
        int[] raw = new int[8];
        for (int i = 0; i < 8; i++) raw[i] = (frame.payload[2 * i] & 0xff) | (frame.payload[2 * i + 1] & 0xff) << 8;
        assertArrayEquals(new int[]{1500, 1500, 1000, 1500, 1000, 1000, 1000, 1000}, raw, "throttle low on the board's throttle channel");
        assertNull(MspRateProbe.stickFrame(new int[]{0, 1, 2, 9, 4, 5, 6, 7}, 8), "a map beyond the frame: no sticks");
        assertNull(MspRateProbe.stickFrame(new int[]{0, 0, 2, 3, 4, 5, 6, 7}, 8), "two channels on one place: no sticks");
        assertEquals(8, MspRateProbe.channelCount(new int[][]{{0, 1700, 2100}}));
        assertEquals(11, MspRateProbe.channelCount(new int[][]{{6, 1700, 2100}}), "ARM on AUX7: sent too, at 1000");
    }

    @Test
    void thePhonesMixIs42ASecondSpreadOver() {
        int[] cycle = MspRateProbe.requestCycle(true);
        assertEquals(42, cycle.length);
        assertEquals(20, Arrays.stream(cycle).filter(c -> c == ATTITUDE).count());
        assertEquals(4, Arrays.stream(cycle).filter(c -> c == FcCommon.MSP_STATUS).count());
        assertEquals(4, Arrays.stream(cycle).filter(c -> c == FcCommon.MSP_ALTITUDE).count());
        for (int i = 1; i < cycle.length; i++) assertFalse(cycle[i] != ATTITUDE && cycle[i] == cycle[i - 1], "spread: " + Arrays.toString(cycle));
        assertArrayEquals(new int[]{ATTITUDE}, MspRateProbe.requestCycle(false));
    }

    @Test
    void theSettingsAreTheBriefsAndOnlySensibleOnes() {
        MspRateProbe.Settings s = MspRateProbe.Settings.parse("", "", "", "");
        assertArrayEquals(new int[]{250, 100, 50}, s.rates());
        assertEquals(10_000, s.windowMs());
        assertEquals(42, s.requests());
        assertTrue(s.phoneMix());
        assertEquals(1000, s.quietMs());
        assertEquals(3000, s.lostMs());
        assertFalse(MspRateProbe.Settings.parse("0,250", "4", "20", "attitude").phoneMix());
        assertThrows(IllegalArgumentException.class, () -> MspRateProbe.Settings.parse("0", "", "0", ""), "nothing to send");
        assertThrows(IllegalArgumentException.class, () -> MspRateProbe.Settings.parse("250", "1", "", ""));
        assertThrows(IllegalArgumentException.class, () -> MspRateProbe.Settings.parse("fast", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> MspRateProbe.Settings.parse("", "", "", "all"));
    }

    // ------------------------------------------------------------------ on a made-up board

    private static MspRateProbe.Settings quick(int... rates) {
        return new MspRateProbe.Settings(rates, 400, 20, true, 200, 3000);
    }

    @Test
    void anArmedBoardGetsNothing() {
        FakeFlightBoard board = new FakeFlightBoard();
        board.arm();
        MspRateProbe.Outcome o = new MspRateProbe(board.connect(), quick(50), out).run();
        assertTrue(o.stopped().startsWith("ARMED"), printed());
        assertEquals("Board left: as found (nothing written to it).", o.boardLeft());
        assertNothingWritten(board);
        assertTrue(board.armed());
    }

    @Test
    void aStickFrameThatCouldArmIsNeverSent() {
        FakeFlightBoard board = new FakeFlightBoard().setUp("aux 0 0 0 900 1300 0 0"); // ARM on AUX1 low
        MspRateProbe.Outcome o = new MspRateProbe(board.connect(), quick(50), out).run();
        assertTrue(o.stopped().startsWith("ARM could be on"), printed());
        assertNothingWritten(board);
    }

    @Test
    void a3dBoardGetsNothing() {
        FakeFlightBoard board = new FakeFlightBoard().features(FakeFlightBoard.DEFAULT_FEATURES | MotorTest.FEATURE_3D);
        MspRateProbe.Outcome o = new MspRateProbe(board.connect(), quick(50), out).run();
        assertTrue(o.stopped().startsWith("3D mode"), printed());
        assertNothingWritten(board);
    }

    private static void assertNothingWritten(FakeFlightBoard board) {
        for (int code : List.of(BetaflightUsb.MSP_SET_ARMING_DISABLED, RAW_RC, MotorTest.MSP_SET_MOTOR)) {
            assertFalse(board.mspRequests().contains(code), code + " in " + board.mspRequests());
        }
    }

    @Test
    void theArmingBlockIsLeftAsItWasFoundAndTheMotorsAtStop() throws IOException {
        FakeFlightBoard off = new FakeFlightBoard();
        MspRateProbe.Outcome o = new MspRateProbe(off.connect(), quick(0, 50), out).run();
        assertNull(o.stopped(), printed());
        assertEquals(2, o.windows().size());
        assertEquals(0, o.windows().get(0).sticks().sent(), "rate 0: the requests alone");
        assertTrue(o.windows().get(0).requests().answered() > 0, printed());
        assertTrue(o.windows().get(1).sticks().answered() > 0, printed());
        assertEquals("Board left: disarmed, arming block off (as before).", o.boardLeft());
        assertFalse(off.armingLocked());
        assertFalse(off.armed());
        assertArrayEquals(new int[]{1000, 1000, 1000, 1000}, off.motors());
        List<Integer> seen = off.mspRequests();
        assertTrue(seen.indexOf(BetaflightUsb.MSP_SET_ARMING_DISABLED) < seen.indexOf(RAW_RC), "blocked before the first stick frame: " + seen);
        assertTrue(seen.lastIndexOf(MotorTest.MSP_SET_MOTOR) > seen.lastIndexOf(RAW_RC), "STOP after the last stick frame: " + seen);

        FakeFlightBoard on = new FakeFlightBoard();
        on.connect().write(MspFrames.v1Request(BetaflightUsb.MSP_SET_ARMING_DISABLED, new byte[]{1})); // somebody else's block
        assertTrue(on.armingLocked());
        o = new MspRateProbe(on.connect(), quick(50), out).run();
        assertEquals("Board left: disarmed, arming block on (as before).", o.boardLeft(), printed());
        assertTrue(on.armingLocked());
    }

    /**
     * The measurement itself, on a board that takes one MSP frame every 10 ms behind a 2048-byte buffer: at 250 stick
     * frames a second and the phone's 42 requests more come than it takes, the buffer fills, frames are lost and every
     * answer waits for the queue - its delay pinned down by the mix's kinds; at 50 a second (and 20 requests) it keeps up.
     */
    @Test
    @Timeout(60)
    void sticksQueueUpOnABoardThatTakesOneFrameEvery10Ms() {
        FakeFlightBoard board = new FakeFlightBoard();
        SlowVcp vcp = new SlowVcp(board.connect());
        MspRateProbe.Outcome o = new MspRateProbe(vcp, new MspRateProbe.Settings(new int[]{250, 50}, 2000, 42, true, 300, 3000), out).run();
        String text = printed();
        System.out.println(text); // what the probe printed: in the test report
        assertNull(o.stopped(), text);
        MspRateProbe.Window fast = o.windows().get(0), slow = o.windows().get(1);
        MspRateProbe.Kind requests = fast.requests();
        assertTrue(requests.shortest().median() > 300, "a queue at 250/s, however the answers are read:\n" + text);
        assertTrue(requests.longest().median() - requests.shortest().median() < 100, "the mix pins the delay down:\n" + text);
        assertTrue(fast.sticks().lost() > 0, text);
        assertTrue(fast.drainNs() > ms(300), text);
        assertTrue(vcp.dropped() > 0, text);
        assertTrue(slow.requests().longest().median() < 100, "none at 50/s:\n" + text);
        assertEquals(0, slow.sticks().lost(), text);
        assertEquals(0, slow.requests().lost(), text);
        assertTrue(text.contains("Summary"), text);
        assertEquals("Board left: disarmed, arming block off (as before).", o.boardLeft(), text);
        assertFalse(board.armed());
    }

    /**
     * A board's USB as the code read has an F4's with Betaflight 4.4.1: a 2048-byte receive buffer, a write (one USB
     * packet) that does not fit whole dropped, and one MSP frame taken from it each serial task run - every 10 ms
     * (serial_update_rate_hz 100). The answers come straight back.
     */
    private static final class SlowVcp implements BetaflightUsb.Wire {
        private final BetaflightUsb.Wire board;
        private final byte[] buffer = new byte[2048];
        private int used, dropped;
        private volatile boolean open = true;

        SlowVcp(BetaflightUsb.Wire board) {
            this.board = board;
            Thread task = new Thread(this::serialTask, "slow-vcp");
            task.setDaemon(true);
            task.start();
        }

        private void serialTask() {
            long next = System.nanoTime();
            try {
                while (open) {
                    next += 10_000_000L;
                    // a coarse timer wakes it late: it then takes the frames it owes at once - 100 a second on average
                    while (open && System.nanoTime() < next) Thread.sleep(1);
                    byte[] frame = take();
                    if (frame != null) board.write(frame);
                }
            } catch (IOException | InterruptedException e) {
                open = false;
            }
        }

        /** The first whole MSP frame in the buffer, taken out (bytes before it that start none go too); null: none yet. */
        private synchronized byte[] take() {
            int at = 0;
            while (at < used && buffer[at] != MspFrames.START) at++;
            System.arraycopy(buffer, at, buffer, 0, used - at);
            used -= at;
            if (used < 3) return null;
            boolean v2 = buffer[1] == MspFrames.V2;
            int header = v2 ? MspFrames.V2_HEADER_SIZE : MspFrames.V1_HEADER_SIZE;
            if (used < header) return null;
            int size = v2 ? (buffer[6] & 0xff) | (buffer[7] & 0xff) << 8 : buffer[3] & 0xff;
            int length = header + size + MspFrames.CRC_SIZE;
            if (used < length) return null;
            byte[] frame = Arrays.copyOf(buffer, length);
            System.arraycopy(buffer, length, buffer, 0, used - length);
            used -= length;
            return frame;
        }

        synchronized int dropped() {
            return dropped;
        }

        @Override
        public int read(byte[] buf) throws IOException {
            return board.read(buf);
        }

        @Override
        public int available() throws IOException {
            return board.available();
        }

        @Override
        public synchronized void write(byte[] data) throws IOException {
            if (!open) throw new IOException("closed");
            if (used + data.length > buffer.length) {
                dropped++;
                return;
            }
            System.arraycopy(data, 0, buffer, used, data.length);
            used += data.length;
        }

        @Override
        public boolean isOpen() {
            return open && board.isOpen();
        }

        @Override
        public void close() {
            open = false;
            board.close();
        }
    }
}
