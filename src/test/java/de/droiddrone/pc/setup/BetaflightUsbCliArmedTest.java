package de.droiddrone.pc.setup;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.bench.hw.FakeFlightBoard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-078: every CLI write of Drone setup (CONFIRM, FIND GPS, the orientation check's SAVE, the compass calibration)
 * goes in through BetaflightUsb.enterCli, and none looked at "armed". Betaflight 4.4.1 ignores '#' while armed
 * (tasks.c, msp_serial.c): the pilot read "No answer from the flight controller", then "Replug the drone's USB". And
 * it opens the CLI about 100 ms after the '#' without looking again, blocking arming only once it is open (cli.c) -
 * a board armed in between got its settings changed and saved armed. Here the CLI's way in reads the status first
 * (armed: nothing sent, and said so), and blocks arming (MSP_SET_ARMING_DISABLED) before the '#'; every way out of
 * Betaflight's CLI restarts it, which takes the block off, and one that never opened has it taken off at close.
 * Made-up board (FakeFlightBoard), no real port.
 */
@Timeout(30)
class BetaflightUsbCliArmedTest {
    private static final int MSP_STATUS = 101, MSP_SET_ARMING_DISABLED = 99;
    private final FakeUsb usb = new FakeUsb();
    private final FakeFlightBoard board = new FakeFlightBoard("MADEUP1");
    /** What the app wrote, write by write, in order: "MSP 101", "MSP 99 1", or the text. */
    private final List<String> written = new ArrayList<>();
    /** The board hears no '#' (a CLI that does not open). */
    private volatile boolean dropHash;

    @AfterEach
    void close() {
        BetaflightUsb.use(null);
    }

    private BetaflightUsb open() throws IOException {
        usb.plug("MADEUP1", "Made-up Betaflight board", () -> new Recording(board.connect()));
        BetaflightUsb.use(usb);
        return BetaflightUsb.open(usb.ports().get(0));
    }

    @Test
    void anArmedBoardsCliIsNotEnteredAndThePilotIsToldItIsArmed() throws Exception {
        board.arm();
        try (BetaflightUsb fc = open()) {
            IOException e = assertThrows(IOException.class, fc::enterCli);
            assertEquals("The drone is armed. Disarm it first.", e.getMessage());
            assertFalse(fc.inCli());
        }
        assertTrue(board.armed(), "still armed: nothing that disarms it was sent");
        assertFalse(board.mspRequests().contains(MSP_SET_ARMING_DISABLED), board.mspRequests().toString());
        assertFalse(written.contains("#"), "no '#': " + written);
        assertEquals(List.of(), board.cli());
    }

    @Test
    void armingIsBlockedBeforeTheHashAndTheRestartAtExitTakesTheBlockOff() throws Exception {
        int restarts = board.restarts;
        try (BetaflightUsb fc = open()) {
            fc.enterCli();
            assertTrue(board.inCli());
            assertTrue(board.armingLocked(), "arming blocked while the CLI is open");
            int status = written.indexOf("MSP " + MSP_STATUS), block = written.indexOf("MSP " + MSP_SET_ARMING_DISABLED + " 1"),
                    hash = written.indexOf("#");
            assertTrue(status >= 0 && status < block && block < hash, "status read, arming blocked, then '#': " + written);
            fc.exitCli();
        }
        assertEquals(restarts + 1, board.restarts, "exit restarts Betaflight");
        assertFalse(board.armingLocked(), "the restart took the block off");
        assertFalse(written.contains("MSP " + MSP_SET_ARMING_DISABLED + " 0"), "nothing more to take off: " + written);
    }

    @Test
    void saveRestartsBetaflightAndTheBlockGoesWithIt() throws Exception {
        try (BetaflightUsb fc = open()) {
            fc.enterCli();
            fc.command("set craft_name = Bench");
            fc.saveAndReboot();
        }
        assertFalse(board.armingLocked());
        assertTrue(board.saved().contains("set craft_name = Bench"));
    }

    @Test
    void aCliThatDoesNotOpenHasItsArmingBlockTakenOffAtClose() throws Exception {
        dropHash = true;
        int restarts = board.restarts;
        try (BetaflightUsb fc = open()) {
            assertThrows(IOException.class, fc::enterCli);
            assertTrue(board.armingLocked(), "blocked from before the '#'");
        }
        assertFalse(board.armingLocked(), "no restart came: the block is taken off - the board is left as it was found");
        assertEquals(restarts, board.restarts);
        assertTrue(written.indexOf("MSP " + MSP_SET_ARMING_DISABLED + " 1") < written.indexOf("MSP " + MSP_SET_ARMING_DISABLED + " 0"), written.toString());
    }

    @Test
    void aBlockThatWasOnBeforeIsLeftOn() throws Exception {
        try (BetaflightUsb other = open()) {
            other.msp(MSP_SET_ARMING_DISABLED, new byte[]{1}); // somebody else's (Betaflight Configurator's, a keeper's)
        }
        dropHash = true;
        written.clear();
        try (BetaflightUsb fc = open()) {
            assertThrows(IOException.class, fc::enterCli);
        }
        assertTrue(board.armingLocked(), "not ours to take off");
        assertFalse(written.contains("MSP " + MSP_SET_ARMING_DISABLED + " 0"), written.toString());
    }

    /** Writes the app makes, noted down; a '#' is kept from the board when the test says so. */
    private final class Recording implements BetaflightUsb.Wire {
        private final BetaflightUsb.Wire wire;

        Recording(BetaflightUsb.Wire wire) {
            this.wire = wire;
        }

        @Override
        public int read(byte[] buf) throws IOException {
            return wire.read(buf);
        }

        @Override
        public int available() throws IOException {
            return wire.available();
        }

        @Override
        public void write(byte[] data) throws IOException {
            boolean msp = data.length >= 6 && data[0] == '$' && data[1] == 'M' && data[2] == '<';
            written.add(msp ? "MSP " + (data[4] & 0xff) + (data[3] > 0 ? " " + data[5] : "") : new String(data, java.nio.charset.StandardCharsets.US_ASCII));
            if (dropHash && data.length == 1 && data[0] == '#') return;
            wire.write(data);
        }

        @Override
        public boolean isOpen() {
            return wire.isOpen();
        }

        @Override
        public void close() {
            wire.close();
        }
    }
}
