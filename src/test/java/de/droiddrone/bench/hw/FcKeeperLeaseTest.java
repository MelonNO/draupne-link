package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keeper's two leases on the board ({@link FcKeeper}): the phone's, and Drone setup's through the app's own door
 * ({@link KeeperWire}) - never both at once, as one USB socket has one plug in it. Drone setup gets the board only
 * while it is disarmed and no arm window is open; while it has it the phone's side is cut; when it is done the board
 * is taken anew - locked, and its channel map and ARM switch read again.
 */
class FcKeeperLeaseTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING;
    private final FakeFlightBoard board = KeeperBench.armable();
    private final KeeperBench bench = new KeeperBench(board);
    private final List<String> log = new ArrayList<>();
    private StandInPhone phone;
    private BetaflightUsb.Port port;

    @BeforeEach
    void theAppsDoorLeadsToTheKeeper() throws Exception {
        bench.start().beat();
        BetaflightUsb.use(KeeperWire.usb(bench.keeper.setupPort())); // what rig.usb.plug(...) gives the app in the bench
        port = BetaflightUsb.findFlightControllers().get(0);
        assertEquals(KeeperWire.PORT_NAME, port.systemName());
    }

    @AfterEach
    void end() {
        if (phone != null) phone.close();
        bench.close();
        BetaflightUsb.use(null);
    }

    @Test
    void whileDroneSetupHasTheBoardThePhoneIsCutAndAfterItTheBoardIsTakenAnew() throws Exception {
        phone = bench.phone().sticks(1000, 1000).startSticks();
        Waits.waitFor(() -> bench.sticksAtBoard() > 20, 5000, "the phone's sticks at the board", bench.said);
        assertEquals("phone", bench.state().lease());

        try (BetaflightUsb fc = BetaflightUsb.open(port)) {
            assertEquals("BTFL", fc.firmware(), "MSP through the keeper, as on USB");
            assertEquals("setup", bench.state().lease());
            Waits.waitFor(phone::ended, 3000, "the phone's connection to be cut", bench.said);
            long sticks = bench.sticksAtBoard();
            for (int i = 0; i < 10; i++) { // the phone app comes again at once, and again: each time the plug is taken
                try (StandInPhone again = bench.phone().sticks(1000, 2000).startSticks()) {
                    Waits.waitFor(again::ended, 3000, "a phone connection during Drone setup's lease to be closed at once", bench.said);
                    assertEquals(0, again.answers().size());
                }
            }
            assertTrue(bench.ask("OPEN 5").startsWith("NO Drone setup has the board"), "no arm window while Drone setup has the board");
            KeeperWire second = KeeperWire.open(bench.keeper.setupPort());
            assertTrue(second.refusal().contains("Drone setup has it already"), second.refusal());
            assertEquals(-1, second.read(new byte[8]), "a lease not given is a wire that is gone");
            // a CLI visit, typed as the app types it: the keeper passes every byte and says nothing of its own between them
            fc.enterCli();
            assertTrue(board.inCli());
            assertTrue(fc.command("get small_angle").contains("small_angle = 25"));
            assertEquals("small_angle set to 33", fc.command("set small_angle = 33").trim());
            fc.command("aux 1 1 1 900 1300 0 0");
            fc.saveAndReboot();
            assertEquals(sticks, bench.sticksAtBoard(), "not one stick frame reached the board while Drone setup had it");
        }
        assertEquals(1, board.saves);
        assertEquals(List.of("get small_angle", "set small_angle = 33", "aux 1 1 1 900 1300 0 0", "save"), board.cli());
        assertTrue(board.saved().contains("set small_angle = 33"));

        // Betaflight restarted: its memory's lock is gone - and set again before anybody gets a lease
        Waits.waitFor(() -> bench.state().boardUp() && bench.state().lease().equals("none"), 5000, "the keeper to take the board anew", bench.said);
        assertTrue(board.armingLocked());
        assertFalse(board.inCli());
        bench.waitForSaid("Drone setup's lease has ended");
        try (StandInPhone back = bench.phone().sticks(1000, 2000).startSticks()) {
            long before = bench.sticksAtBoard();
            Waits.waitFor(() -> bench.sticksAtBoard() > before + 50, 5000, "the phone's sticks at the board again", bench.said);
            assertEquals("phone", bench.state().lease());
            assertFalse(board.armed());
        }
    }

    @Test
    void droneSetupIsRefusedWhileAnArmWindowIsOpenOrTheBoardIsArmed() throws Exception {
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=20000", bench.ask("OPEN 20"));
        IOException window = assertThrows(IOException.class, () -> BetaflightUsb.open(port));
        assertTrue(window.getMessage().contains("in use") && window.getMessage().contains("an arm window is open"), window.getMessage());
        bench.arm(phone);
        IOException armed = assertThrows(IOException.class, () -> BetaflightUsb.open(port));
        assertTrue(armed.getMessage().contains("an arm window is open"), armed.getMessage());
        assertFalse(phone.ended(), "a lease that is refused cuts nobody");
        assertTrue(board.armed());
        assertTrue(bench.ask("CLOSE").startsWith("OK safe=1"));
        try (BetaflightUsb fc = BetaflightUsb.open(port)) { // closed, locked, disarmed: now it is given
            assertEquals("BTFL", fc.firmware());
        }
        bench.waitForSaid("Drone setup is not given the board: an arm window is open.");
    }

    @Test
    void theAppsOwnDroneSetupRoundGoesThroughTheKeeperAndItsNewSwitchesAreRead() throws Exception {
        // a stock board: a serial receiver, ARM on no switch - Drone setup makes MSP its receiver and puts ARM on AUX1
        try (KeeperBench stock = new KeeperBench(new FakeFlightBoard())) {
            stock.board.battery(16.4, 4);
            stock.start().beat();
            assertTrue(stock.saidOf("ARM on NO SWITCH - NOT on AUX1 as the rig was told"), stock.said.toString());
            BetaflightUsb.use(KeeperWire.usb(stock.keeper.setupPort()));
            List<String> written = AppWrites.droneSetup(port, T, log::add);
            assertTrue(written.stream().anyMatch(c -> c.startsWith("aux ")), written.toString());
            assertEquals(1, stock.board.saves);
            Waits.waitFor(() -> stock.state().boardUp() && stock.state().lease().equals("none") && stock.board.armingLocked(), 8000,
                    "the keeper to take the board anew after the app's save", stock.said);
            Waits.waitFor(() -> stock.saidOf("ARM on AUX1 1700-2100, PREARM on AUX3 1700-2100"), 5000, "the switches Drone setup wrote to be read", stock.said);
            assertEquals(1, stock.state().number("armAux"));
            assertEquals(3, stock.state().number("prearmAux"), "the rig is told which switch has to go up before ARM");
            // and with it the whole way: a window, the phone's sticks, PREARM up, armed - and closed
            try (StandInPhone p = stock.phone().sticks(1000, 1000).startSticks()) {
                assertEquals("OK window=10000", stock.ask("OPEN 10"));
                p.sticks(1000, 2000);
                Thread.sleep(150);
                assertFalse(stock.board.armed(), "ARM alone does not arm a board with a PREARM switch");
                p.prearm(2000);
                stock.arm(p);
                assertTrue(stock.ask("CLOSE").startsWith("OK safe=1 locked=1 armed=0"));
            }
        }
    }

    @Test
    void aSetupClientThatDiedInTheCliLeavesABoardTheKeeperTakesOutOfIt() throws Exception {
        board.cutTakesPower = false;
        KeeperWire wire = KeeperWire.open(bench.keeper.setupPort());
        assertNull(wire.refusal());
        wire.write("#".getBytes(StandardCharsets.US_ASCII));
        Waits.waitFor(board::inCli, 3000, "the CLI open", bench.said);
        wire.write("set small_angle = 99\n".getBytes(StandardCharsets.US_ASCII));
        Waits.waitFor(() -> board.cli().contains("set small_angle = 99"), 3000, "the line typed", bench.said);
        wire.close(); // the tool dies: no exit, the board sits in its CLI and answers no MSP
        bench.waitForSaid("answers no MSP: Enter and \"exit\" sent in case a tool left its CLI open");
        Waits.waitFor(() -> bench.state().boardUp() && board.armingLocked(), 8000, "the keeper to have the board again, locked", bench.said);
        assertFalse(board.inCli());
        assertEquals("exit", board.cli().get(board.cli().size() - 1));
        assertEquals(0, board.saves, "what the dead tool typed is dropped with the restart");
        assertFalse(board.inMemory().contains("small_angle = 99"));
    }
}
