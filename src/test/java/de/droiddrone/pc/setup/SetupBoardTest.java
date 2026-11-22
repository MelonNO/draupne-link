package de.droiddrone.pc.setup;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.bench.hw.FakeFlightBoard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-071 (and F-060 before it): the one way in for Drone setup's USB tools - the board its first step read, by port
 * and by id, and no other. The motor test's window has its own tests (MotorTestBoardTest), the other tools theirs
 * (DroneSetupUsbToolsTest); here the rule's corners, on a made-up USB with made-up boards. No real port.
 */
class SetupBoardTest {
    private static final Set<Integer> WHAT_IT_IS = Set.of(2, 160); // MSP_FC_VARIANT, MSP_UID
    private final FakeUsb usb = new FakeUsb();

    @BeforeEach
    void madeUpUsb() {
        BetaflightUsb.use(usb);
    }

    @AfterEach
    void realUsbBack() {
        BetaflightUsb.use(null); // nothing here leaves a thread behind
    }

    private FakeFlightBoard plug(String port, int id) {
        FakeFlightBoard board = new FakeFlightBoard(port);
        board.uid = new byte[]{0x4d, 0x41, 0x44, 0x45, 0x2d, 0x55, 0x50, 0, 0, 0, 0, (byte) id};
        usb.plug(port, "Made-up Betaflight board", board::connect);
        return board;
    }

    private static String id(FakeFlightBoard board) {
        return SetupConfirm.boardId(board.uid, null);
    }

    private static SetupBoard.NotThere refused(SetupBoard target) {
        return assertThrows(SetupBoard.NotThere.class, () -> SetupBoard.open(target).close());
    }

    @Test
    void theBoardThatWasReadIsOpenedAndTheOtherIsLeftAlone() throws Exception {
        FakeFlightBoard a = plug("MADEUP1", 1), b = plug("MADEUP2", 2);
        try (BetaflightUsb fc = SetupBoard.open(new SetupBoard("MADEUP2", id(b)))) {
            assertEquals("BTFL", fc.firmware());
        }
        assertEquals(0, a.opens);
        assertEquals(1, b.opens);
    }

    @Test
    void anotherBoardOnThePortIsAskedWhatItIsAndNothingMore() {
        FakeFlightBoard other = plug("MADEUP1", 2);
        SetupBoard.NotThere e = refused(new SetupBoard("MADEUP1", SetupConfirm.boardId(new byte[]{0x4d, 0x41, 0x44, 0x45, 0x2d, 0x55, 0x50, 0, 0, 0, 0, 1}, null)));
        assertEquals(SetupBoard.NotThere.Why.OTHER_BOARD, e.why());
        assertFalse(e.waiting(), "an error, not a wait");
        assertEquals("<b>MADEUP1</b>: not the board Drone setup read. Nothing sent.<br>Plug that one in, or <b>Back</b> and <b>Read again</b>.",
                e.html(SetupBoard.BACK_AND_READ_AGAIN));
        assertEquals("<b>MADEUP1</b>: not the board Drone setup read. Nothing sent.<br>Plug that one in, or <b>Read again</b>.",
                e.html(SetupBoard.READ_AGAIN));
        assertEquals("MADEUP1: not the board Drone setup read. Nothing sent. Plug that one in, or Read again.", e.getMessage(),
                "in plain words, for a row of the first step and for the log");
        assertTrue(WHAT_IT_IS.containsAll(other.mspRequests()), other.mspRequests().toString());
    }

    @Test
    void theBoardThatWasReadNotPluggedInIsAWaitThatSaysWhere() {
        FakeFlightBoard other = plug("MADEUP1", 1);
        SetupBoard.NotThere e = refused(new SetupBoard("MADEUP2", "uid:4d4144452d55500000000002"));
        assertEquals(SetupBoard.NotThere.Why.NOT_PLUGGED, e.why());
        assertTrue(e.waiting());
        assertEquals("Drone setup's board is not on <b>MADEUP2</b>.<br>Plug it in, or <b>Read again</b>.", e.html(SetupBoard.READ_AGAIN));
        assertEquals(0, other.opens, "the board that is there is not opened");
    }

    @Test
    void aPortAloneIsWhicheverBetaflightBoardIsOnIt() throws Exception {
        FakeFlightBoard a = plug("MADEUP1", 1), b = plug("MADEUP2", 2);
        try (BetaflightUsb fc = SetupBoard.open(new SetupBoard("MADEUP2", null))) { // the pilot's port choice, nothing read on it yet
            assertEquals("BTFL", fc.firmware());
        }
        assertEquals(0, a.opens, "the pilot's port only");
        assertEquals(1, b.opens);
        usb.unplug("MADEUP2");
        SetupBoard.NotThere e = refused(new SetupBoard("MADEUP2", null));
        assertEquals(SetupBoard.NotThere.Why.NOT_FOUND, e.why());
        assertEquals("Not found. Plug in a USB data cable.", e.html(SetupBoard.READ_AGAIN));
        assertEquals(0, a.opens);
    }

    @Test
    void forNoBoardTheOneThatIsAloneIsTakenAndNoneOfTwo() throws Exception {
        SetupBoard.NotThere none = refused(null);
        assertEquals(SetupBoard.NotThere.Why.NOT_FOUND, none.why());
        FakeFlightBoard a = plug("MADEUP1", 1);
        try (BetaflightUsb fc = SetupBoard.open(null)) {
            assertEquals("BTFL", fc.firmware());
        }
        FakeFlightBoard b = plug("MADEUP2", 2);
        SetupBoard.NotThere e = refused(null);
        assertEquals(SetupBoard.NotThere.Why.SEVERAL, e.why());
        assertEquals("2 flight controllers on USB. Unplug all but one.", e.html(SetupBoard.BACK_AND_READ_AGAIN));
        assertEquals("2 flight controllers on USB. Unplug all but one.", e.getMessage());
        assertTrue(Set.of(2).containsAll(b.mspRequests()), "asked only whether it is Betaflight: " + b.mspRequests());
        assertEquals(List.of(), a.cli());
    }

    /** A GPS or ESC adapter looks like a flight controller's port to Windows: it is no second board. */
    @Test
    void aPortThatAnswersNothingIsNoSecondBoard() throws Exception {
        usb.plug("ADAPTER", "USB serial adapter", SetupBoardTest::silentWire);
        FakeFlightBoard board = plug("MADEUP1", 1);
        try (BetaflightUsb fc = SetupBoard.open(null)) {
            assertEquals("BTFL", fc.firmware());
        }
        assertTrue(board.opens >= 1);
    }

    @Test
    void aBoardThatDoesNotRunBetaflightIsNotOpened() {
        FakeFlightBoard inav = plug("MADEUP1", 1);
        inav.variant = "INAV";
        SetupBoard.NotThere e = refused(new SetupBoard("MADEUP1", null));
        assertEquals(SetupBoard.NotThere.Why.FAILED, e.why());
        assertEquals("MADEUP1: This flight controller doesn't run Betaflight (INAV).", e.html(SetupBoard.READ_AGAIN));
    }

    /** A wire nothing answers on. */
    private static BetaflightUsb.Wire silentWire() {
        return new BetaflightUsb.Wire() {
            private boolean open = true;

            @Override
            public int read(byte[] buf) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }

            @Override
            public int available() {
                return 0;
            }

            @Override
            public void write(byte[] data) throws IOException {
                if (!open) throw new IOException("closed");
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() {
                open = false;
            }
        };
    }
}
