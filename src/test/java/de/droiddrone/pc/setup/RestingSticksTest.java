package de.droiddrone.pc.setup;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.MspFrames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static de.droiddrone.testing.Waits.until;
import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.72 (the pilot, 2026-11-23: setting up a new drone, its motors beeped without end from CONFIRM on): while Drone
 * setup is open, the board it read gets resting sticks over USB, so Betaflight doesn't take its MSP receiver (the phone,
 * not plugged in) as lost. The frame, the holder that keeps the port between Drone setup's steps, the motor test's own
 * connection, and when nothing is fed. A made-up USB and a made-up board whose receiver is lost 100 ms after the last
 * sticks, as Betaflight's; no real port.
 */
class RestingSticksTest {
    private static final String PORT = "MADEUP1";
    private final FakeUsb usb = new FakeUsb();
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void madeUpUsb() {
        BetaflightUsb.use(usb);
    }

    @AfterEach
    void realUsbBack() throws InterruptedException {
        RestingSticks.stop();
        waitFor(() -> !RestingSticks.holding(), 3000, "the holder letting go");
        BetaflightUsb.use(null);
    }

    private Board plug(Board board) {
        usb.plug(PORT, "Made-up Betaflight board", board::connect);
        return board;
    }

    // ------------------------------------------------------------------ the frame

    private static byte[] ranges(int... slots) {
        byte[] out = new byte[slots.length];
        for (int i = 0; i < slots.length; i++) out[i] = (byte) slots[i];
        return out;
    }

    private static int channel(byte[] payload, int raw) {
        return (payload[raw * 2] & 0xff) | (payload[raw * 2 + 1] & 0xff) << 8;
    }

    @Test
    void theFrameIsSticksCentredThrottleAndEveryAuxLowInTheBoardsOrder() {
        // Betaflight's default map AETR1234: roll 0, pitch 1, yaw 3, throttle 2 (MSP_RX_MAP, per logical channel)
        byte[] p = RestingSticks.payload(new byte[]{0, 1, 3, 2, 4, 5, 6, 7}, ranges(0, 0, 32, 48)); // ARM on AUX1 1700-2100
        assertNotNull(p);
        assertEquals(FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT * 2, p.length);
        assertEquals(1500, channel(p, 0), "roll");
        assertEquals(1500, channel(p, 1), "pitch");
        assertEquals(1000, channel(p, 2), "throttle, where AETR has it");
        assertEquals(1500, channel(p, 3), "yaw");
        for (int raw = 4; raw < 18; raw++) assertEquals(1000, channel(p, raw), "AUX" + (raw - 3));
    }

    @Test
    void noFrameWhenItCouldArmOrTheBoardIsNotUnderstood() {
        byte[] map = {0, 1, 3, 2, 4, 5, 6, 7};
        assertNull(RestingSticks.payload(map, ranges(0, 0, 0, 16)), "ARM on AUX1 900-1300: low is armed");
        assertNull(RestingSticks.payload(map, ranges(0, 20, 32, 48)), "ARM on a channel the frame doesn't carry");
        assertNull(RestingSticks.payload(map, new byte[]{0, 0, 32}), "ranges not understood");
        assertNull(RestingSticks.payload(new byte[]{0, 1, 2, 2}, ranges(0, 0, 32, 48)), "two channels on one place");
        assertNull(RestingSticks.payload(new byte[]{0, 1, 3, 40}, ranges(0, 0, 32, 48)), "a place past the frame");
        assertNull(RestingSticks.payload(new byte[0], ranges(0, 0, 32, 48)), "no map");
        assertNotNull(RestingSticks.payload(map, new byte[0]), "ARM on no switch: nothing to turn on");
    }

    // ------------------------------------------------------------------ the holder

    @Test
    void whileHeldTheReceiverIsNotLostAndOnStopItIsAgain() throws Exception {
        Board board = plug(new Board(1));
        RestingSticks.hold(PORT, board.id(), log::add);
        waitFor(RestingSticks::holding, 3000, "the holder's connection", log);
        assertFalse(until(board::receiverLost, 1500), "the receiver stays: the drone doesn't beep");
        assertTrue(log.contains("Drone setup: resting sticks to the FC over USB - no lost-link beeping while it is set up."), log.toString());
        assertTrue(board.cli().isEmpty(), "nothing written to its settings: " + board.cli());
        RestingSticks.stop();
        waitFor(board::receiverLost, 3000, "Betaflight's own lost receiver after Drone setup closed");
    }

    @Test
    void anotherConnectionGetsThePortFirstAndTheHolderTakesItBackAfter() throws Exception {
        Board board = plug(new Board(1));
        RestingSticks.hold(PORT, board.id(), log::add);
        waitFor(RestingSticks::holding, 3000, "the holder's connection", log);
        try (BetaflightUsb fc = BetaflightUsb.open(new BetaflightUsb.Port(PORT, ""))) {
            assertFalse(RestingSticks.holding(), "the holder let go before the other opened");
            assertEquals("BTFL", fc.firmware());
        }
        waitFor(RestingSticks::holding, 3000, "the holder taking the port back", log);
        waitFor(() -> !board.receiverLost(), 3000, "its sticks again");
        assertFalse(until(board::receiverLost, 500));
        assertFalse(board.twoAtOnce, "two connections at once");
    }

    @Test
    void theMotorTestsConnectionToTheHeldBoardIsFed() throws Exception {
        Board board = plug(new Board(1));
        RestingSticks.hold(PORT, board.id(), log::add);
        waitFor(RestingSticks::holding, 3000, "the holder's connection", log);
        MotorTest test = MotorTest.open(new BetaflightUsb.Port(PORT, ""));
        try {
            assertFalse(RestingSticks.holding());
            long before = board.sticks();
            waitFor(() -> board.sticks() > before + 5, 3000, "sticks on the motor test's connection");
            waitFor(() -> !board.receiverLost(), 3000, "the receiver back");
            assertFalse(until(board::receiverLost, 500), "the motors don't beep between the test's spins");
            assertEquals(0, test.poll().armed() ? 1 : 0, "its own requests are answered beside the sticks");
        } finally {
            test.close();
        }
        waitFor(RestingSticks::holding, 3000, "the holder taking the port back", log);
    }

    @Test
    void theCliTakesNoSticksAndItsAnswerHasNoneOfTheirs() throws Exception {
        Board board = plug(new Board(1));
        try (BetaflightUsb fc = BetaflightUsb.open(new BetaflightUsb.Port(PORT, ""))) {
            fc.feedRestingSticks(RestingSticks.payload(new byte[]{0, 1, 3, 2, 4, 5, 6, 7}, new byte[0]));
            waitFor(() -> board.sticks() > 3, 3000, "a few frames");
            String banner = fc.enterCli();
            assertFalse(fc.feeding());
            assertFalse(banner.contains("$M"), "an MSP answer in the CLI's banner: " + banner);
            assertEquals("version", fc.command("version"));
        }
        for (String line : board.cli()) assertTrue(line.equals("version") || line.equals("exit"), "garbage in the CLI: " + board.cli());
    }

    @Test
    void aBoardWhoseReceiverIsNotMspIsLeftAlone() throws Exception {
        Board board = plug(new Board(1));
        board.features = BetaflightPlan.FEATURE_RX_SERIAL;
        RestingSticks.hold(PORT, board.id(), log::add);
        waitFor(() -> log.contains("Drone setup: no resting sticks to the FC - its receiver is not MSP."), 3000, "the reason", log);
        assertFalse(RestingSticks.holding(), "the port is let go");
        assertEquals(0, board.sticks());
    }

    @Test
    void aBoardWhoseArmRangeTakesTheRestingFrameGetsNone() throws Exception {
        Board board = plug(new Board(1));
        board.ranges = ranges(0, 0, 0, 16);
        RestingSticks.hold(PORT, board.id(), log::add);
        waitFor(() -> log.contains("Drone setup: no resting sticks to the FC - by its ARM range they could arm."), 3000, "the reason", log);
        assertEquals(0, board.sticks());
        assertFalse(RestingSticks.holding());
    }

    @Test
    void anArmedBoardGetsNoneAndTheHolderLetsGo() throws Exception {
        Board board = plug(new Board(1));
        RestingSticks.hold(PORT, board.id(), log::add);
        waitFor(RestingSticks::holding, 3000, "the holder's connection", log);
        board.armed = true;
        waitFor(() -> !RestingSticks.holding(), 3000, "the holder letting go of an armed board", log);
        assertTrue(log.contains("Drone setup: the FC reads ARMED - resting sticks stopped."), log.toString());
        long after = board.sticks();
        assertFalse(until(() -> board.sticks() > after, 500), "no sticks to an armed board");
    }

    @Test
    void anotherBoardOnThePortIsAskedWhatItIsAndNothingMore() throws Exception {
        Board other = plug(new Board(2));
        RestingSticks.hold(PORT, new Board(1).id(), log::add);
        waitFor(() -> other.opens > 0, 3000, "the holder looking");
        assertFalse(until(RestingSticks::holding, 500));
        assertTrue(java.util.Set.of(2, 160).containsAll(other.requests()), other.requests().toString());
    }

    @Test
    void aBoardWithoutAnIdIsNotHeld() throws Exception {
        Board board = plug(new Board(1));
        RestingSticks.hold(PORT, null, log::add);
        assertFalse(until(() -> board.opens > 0, 500), "no telling it is the same board after a restart");
    }

    // ------------------------------------------------------------------ a made-up Betaflight board

    /**
     * Just enough Betaflight for this: its id, an MSP receiver lost 100 ms after the last MSP_SET_RAW_RC (or before the
     * first), the channel map AETR1234, ARM on AUX1 1700-2100, the motor test's requests, and a CLI that echoes what
     * it is sent. One connection at a time: a new one ends the one before, as a USB port does.
     */
    private static final class Board {
        final byte[] uid;
        volatile int features = BetaflightPlan.FEATURE_RX_MSP;
        volatile byte[] ranges = ranges(0, 0, 32, 48);
        volatile boolean armed, twoAtOnce;
        volatile int opens;
        private volatile long lastSticksNs;
        private final List<Integer> requests = Collections.synchronizedList(new ArrayList<>());
        private final List<String> cli = Collections.synchronizedList(new ArrayList<>());
        private Link current;

        Board(int n) {
            uid = new byte[]{0x4d, 0x41, 0x44, 0x45, 0x2d, 0x55, 0x50, 0, 0, 0, 0, (byte) n};
        }

        String id() {
            return SetupConfirm.boardId(uid, null);
        }

        boolean receiverLost() {
            return lastSticksNs == 0 || System.nanoTime() - lastSticksNs > 100_000_000L;
        }

        long sticks() {
            return requests().stream().filter(c -> c == FcCommon.MSP_SET_RAW_RC).count();
        }

        List<Integer> requests() {
            synchronized (requests) {
                return List.copyOf(requests);
            }
        }

        List<String> cli() {
            synchronized (cli) {
                return List.copyOf(cli);
            }
        }

        synchronized BetaflightUsb.Wire connect() {
            opens++;
            if (current != null && current.open) twoAtOnce = true;
            if (current != null) current.open = false;
            current = new Link();
            return current;
        }

        private byte[] answer(int code, byte[] p) {
            requests.add(code);
            switch (code) {
                case 2: return "BTFL".getBytes(StandardCharsets.US_ASCII);
                case 160: return uid;
                case 36: return new byte[]{(byte) features, (byte) (features >> 8), (byte) (features >> 16), (byte) (features >> 24)};
                case 64: return new byte[]{0, 1, 3, 2, 4, 5, 6, 7};
                case 34: return ranges;
                case 101: return new byte[]{0, 0, 0, 0, 0, 0, (byte) (armed ? 1 : 0), 0, 0, 0, 0};
                case 200:
                    if (p.length >= 8) lastSticksNs = System.nanoTime();
                    return new byte[0];
                case 3: return new byte[]{4, 4, 1}; // MSP_FC_VERSION
                case 42: return new byte[]{3, 0}; // MSP_MIXER_CONFIG: quad X
                case 104: return new byte[]{(byte) 0xE8, 3, (byte) 0xE8, 3, (byte) 0xE8, 3, (byte) 0xE8, 3, 0, 0, 0, 0, 0, 0, 0, 0}; // MSP_MOTOR: four at 1000
                case 110: return new byte[]{(byte) 0xA0, 0x0F, 0, 0, 0, 0, 0, 0, 0}; // MSP_ANALOG
                case 130: return new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}; // MSP_BATTERY_STATE: none
                case 214: return new byte[0]; // MSP_SET_MOTOR
                case 99: return new byte[0]; // MSP_SET_ARMING_DISABLED: the arming block before the CLI (F-078)
                default: return null;
            }
        }

        private final class Link implements BetaflightUsb.Wire {
            volatile boolean open = true;
            private final MspFrames frames = new MspFrames(MspFrames.V1);
            private final ByteArrayOutputStream out = new ByteArrayOutputStream();
            private final StringBuilder line = new StringBuilder();
            private boolean inCli;

            @Override
            public synchronized int read(byte[] buf) throws IOException {
                if (!open) return -1;
                if (out.size() == 0) {
                    try {
                        wait(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] b = out.toByteArray();
                int n = Math.min(buf.length, b.length);
                System.arraycopy(b, 0, buf, 0, n);
                out.reset();
                out.write(b, n, b.length - n);
                return n;
            }

            @Override
            public synchronized int available() {
                return open ? out.size() : -1;
            }

            @Override
            public synchronized void write(byte[] data) throws IOException {
                if (!open) throw new IOException("gone");
                if (!inCli && data.length == 1 && data[0] == '#') {
                    inCli = true;
                    out.writeBytes("\r\nEntering CLI Mode, type 'exit' to return, or 'help'\r\n# ".getBytes(StandardCharsets.US_ASCII));
                } else if (inCli) {
                    for (byte b : data) {
                        if (b != '\n') {
                            line.append((char) b);
                            continue;
                        }
                        String l = line.toString().trim();
                        line.setLength(0);
                        cli.add(l);
                        if (l.equals("exit")) {
                            open = false; // Betaflight restarts
                            return;
                        }
                        out.writeBytes((l + "\r\n" + l + "\r\n# ").getBytes(StandardCharsets.US_ASCII));
                    }
                } else {
                    for (MspFrames.Frame f : frames.feed(data, data.length)) {
                        if (f.type != MspFrames.REQUEST) continue;
                        byte[] p = f.payload == null ? new byte[0] : f.payload;
                        byte[] a = answer(f.code, p);
                        out.writeBytes(a == null ? frame(MspFrames.ERROR, f.code, new byte[0]) : frame(MspFrames.RESPONSE, f.code, a));
                    }
                }
                notifyAll();
            }

            private byte[] frame(byte type, int code, byte[] p) {
                byte[] r = new byte[6 + p.length];
                r[0] = '$';
                r[1] = 'M';
                r[2] = type;
                r[3] = (byte) p.length;
                r[4] = (byte) code;
                int x = p.length ^ code;
                for (int i = 0; i < p.length; i++) {
                    r[5 + i] = p[i];
                    x ^= p[i];
                }
                r[5 + p.length] = (byte) x;
                return r;
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() {
                open = false;
            }
        }
    }
}
