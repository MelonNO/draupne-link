package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.CliWrite;
import de.droiddrone.pc.setup.EscFirmware;
import de.droiddrone.pc.setup.EscLink;
import de.droiddrone.pc.setup.FakeEscBoard;
import de.droiddrone.pc.setup.MotorTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The made-up flight controller itself, asked by the app's own code through BetaflightUsb's door: it must behave as
 * Betaflight does where the rig's tools count on it - memory against saved, restarts, the CLI shut while armed.
 */
class FakeFlightBoardTest {
    private static final BoardPort.Timing T = FakeFlightBoard.NO_WAITING;
    private final FakeFlightBoard board = new FakeFlightBoard();
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void plugIn() {
        BetaflightUsb.use(board.usb());
    }

    @AfterEach
    void thisPcsPortsAgain() {
        BetaflightUsb.use(null);
    }

    private BetaflightUsb open() throws IOException {
        return BoardPort.open(board.port(), T, log::add);
    }

    @Test
    void theCliChangesMemoryAndOnlySaveKeepsIt() throws IOException {
        try (BetaflightUsb fc = open()) {
            assertEquals("BTFL", fc.firmware());
            fc.enterCli();
            assertEquals(100, BetaflightPlan.serialRate(fc.command("get serial_update_rate_hz")));
            assertEquals("serial_update_rate_hz set to 2000", fc.command("set serial_update_rate_hz = 2000"));
            assertEquals(2000, BetaflightPlan.serialRate(fc.command("get serial_update_rate_hz")));
            assertTrue(BetaflightPlan.refused(fc.command("set serial_update_rate_hz = 5")), "out of its range");
            assertTrue(BetaflightPlan.refused(fc.command("set no_such_setting = 1")));
            assertTrue(BetaflightPlan.refused(fc.command("flash_erase")), "a command it doesn't have");
            assertTrue(fc.command("version").startsWith("# Betaflight / STM32F405 (S405) 4.4.1"));
        } // closed with the CLI open: exit
        assertEquals("exit", board.cli().get(board.cli().size() - 1));
        assertEquals(1, board.restarts);
        assertFalse(board.saved().contains("serial_update_rate_hz = 2000"), "left without save: dropped");
        assertFalse(board.inMemory().contains("serial_update_rate_hz = 2000"));
        try (BetaflightUsb fc = open()) {
            fc.enterCli();
            fc.command("set serial_update_rate_hz = 2000");
            fc.saveAndReboot();
        }
        assertEquals(1, board.saves);
        assertTrue(board.saved().contains("set serial_update_rate_hz = 2000"));
        assertTrue(board.savedDiff().contains("# master\nset serial_update_rate_hz = 2000"), board.savedDiff());
    }

    @Test
    void itsDiffAllPutOnAFreshBoardMakesTheSameBoard() {
        FakeFlightBoard flown = FakeFlightBoard.flown();
        String diff = flown.savedDiff();
        assertTrue(CliWrite.usableBackup(diff));
        for (String has : List.of("batch start", "defaults nosave", "board_name MADEUPF405", "mcu_id 4544414d", "feature GPS",
                "beeper -GYRO_CALIBRATED", "serial 1 2 115200 57600 0 115200", "aux 0 0 2 1700 2100 0 0", "set craft_name = Bench",
                "# profile 1\nset p_roll = 60", "# rateprofile 1\nset thr_mid = 40", "# save configuration\nsave")) {
            assertTrue(diff.contains(has), has + " in\n" + diff);
        }
        assertFalse(diff.contains("min_check"), "a diff has only what differs from a fresh board");
        assertTrue(flown.saved().contains("set min_check = 1050"), "a dump has everything");
        List<String> lines = new ArrayList<>();
        for (String line : diff.split("\n")) if (!line.isBlank() && !line.startsWith("#") && !line.equals("save")) lines.add(line);
        FakeFlightBoard other = new FakeFlightBoard().setUp("set small_angle = 90", "feature TELEMETRY", "aux 5 13 13 1700 2100 0 0")
                .setUp(lines.toArray(new String[0]));
        assertEquals(flown.saved(), other.saved());
        assertNotEquals(flown.saved(), new FakeFlightBoard().saved());
    }

    @Test
    void theMotorTestSpinsReordersAndTurnsThePropsDirection() throws IOException {
        board.battery(16.4, 4);
        try (MotorTest test = MotorTest.open(board.port())) {
            assertEquals("4.4.1", test.info().version());
            assertTrue(test.info().quadX() && test.info().dshot());
            assertEquals(4, test.info().motorCount());
            assertFalse(test.info().reversed());
            test.set(new int[]{1000, 1080, 1000, 5000});
            assertArrayEquals(new int[]{1000, 1080, 1000, MotorTest.MAX}, board.motors());
            MotorTest.Live live = test.poll();
            assertArrayEquals(new int[]{1000, 1080, 1000, MotorTest.MAX}, live.motors());
            assertEquals(16.4, live.volts(), 1e-9);
            assertTrue(live.batteryIn());
            assertFalse(live.armed());
            assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7}, test.outputOrder());
            test.setOutputOrderAndRestart(new int[]{1, 0, 3, 2, 4, 5, 6, 7});
        }
        assertEquals(1, board.restarts);
        assertArrayEquals(new int[]{1000, 1000, 1000, 1000}, board.motors(), "stopped before the write, and by the restart");
        assertTrue(board.savedDiff().contains("set motor_output_reordering = 1,0,3,2,4,5,6,7"), "the CLI names what MSP wrote");
        assertEquals(List.of(), BetaflightUsb.findFlightControllers(), "restarting: the port is away");
        assertThrows(IOException.class, () -> BetaflightUsb.open(board.port()));
        AppWrites.propsOut(board.port(), T, true);
        assertTrue(board.savedDiff().contains("set yaw_motors_reversed = ON"));
        try (MotorTest test = AppWrites.motorTest(board.port(), T)) {
            assertTrue(test.info().reversed());
            assertArrayEquals(new int[]{1, 0, 3, 2, 4, 5, 6, 7}, test.outputOrder());
            // one program at a time, as a COM port
            IOException inUse = assertThrows(IOException.class, () -> BetaflightUsb.open(board.port()));
            assertTrue(inUse.getMessage().contains("in use"), inUse.getMessage());
        }
    }

    @Test
    void anArmedBoardKeepsItsCliShutItsMotorsToItselfAndItsMemoryUnsaved() throws IOException {
        board.arm();
        try (BetaflightUsb fc = open()) {
            assertTrue(Msp.armed(fc.msp(Msp.STATUS, null)));
            fc.msp(Msp.SET_MOTOR, Msp.allMotors(MotorTest.STOP));
            assertArrayEquals(new int[]{FakeFlightBoard.RUNNING, FakeFlightBoard.RUNNING, FakeFlightBoard.RUNNING, FakeFlightBoard.RUNNING},
                    Msp.motors(java.util.Arrays.copyOf(fc.msp(Msp.MOTOR, null), 8)), "an armed board takes no motor values from USB");
            fc.setFeatureMask(FakeFlightBoard.DEFAULT_FEATURES | BetaflightPlan.FEATURE_GPS);
            IOException e = assertThrows(IOException.class, () -> fc.msp(Msp.EEPROM_WRITE, null));
            assertTrue(e.getMessage().contains("refused"), e.getMessage());
            assertEquals(0, board.saves);
        }
        IOException noMotorTest = assertThrows(IOException.class, () -> MotorTest.open(board.port()));
        assertTrue(noMotorTest.getMessage().contains("armed"), noMotorTest.getMessage());
        // the CLI, at the wire: '#' on an armed board is not looked at
        BetaflightUsb.Wire wire = board.connect();
        wire.write("#".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0, wire.available());
        assertFalse(board.inCli());
        board.disarm();
        wire.write("#".getBytes(StandardCharsets.US_ASCII));
        assertTrue(wire.available() > 0, "the CLI's banner");
        assertTrue(board.inCli());
        // and in the CLI no MSP is answered
        int before = wire.available();
        wire.write(new byte[]{'$', 'M', '<', 0, 101, 101});
        byte[] all = new byte[1024];
        int n = wire.read(all);
        assertFalse(new String(all, before, n - before, StandardCharsets.US_ASCII).contains("$M>"));
        wire.close();
    }

    @Test
    void outsideTheCliItLooksOnlyForTheCliAndTheBootloader() throws IOException {
        BetaflightUsb.Wire wire = board.connect();
        wire.write("exit\n\nsave\n".getBytes(StandardCharsets.US_ASCII)); // CLI words to a board that is not in its CLI
        assertEquals(0, wire.available());
        assertFalse(board.inCli());
        assertEquals(0, board.restarts + board.saves);
        // a frame with a wrong checksum is dropped whole, whatever is in it
        byte[] bad = de.droiddrone.common.MspFrames.v1Request(Msp.SET_MOTOR, "R#R#R#R#R#R#R#R#".getBytes(StandardCharsets.US_ASCII));
        bad[bad.length - 1] ^= 1;
        wire.write(bad);
        assertEquals(0, wire.available());
        assertFalse(board.inCli() || board.inBootloader());
        assertArrayEquals(new int[]{1000, 1000, 1000, 1000}, board.motors());
        // an R: Betaflight's way into its bootloader - no Betaflight and no port until the power was off and on
        wire.write("R".getBytes(StandardCharsets.US_ASCII));
        assertTrue(board.inBootloader());
        assertFalse(wire.isOpen());
        assertEquals(List.of(), BetaflightUsb.findFlightControllers());
        board.unplug();
        board.plugIn();
        assertFalse(board.inBootloader());
        assertEquals(List.of(board.port()), BetaflightUsb.findFlightControllers());
        // in the CLI, Enter on an empty line says nothing - not even the prompt
        wire = board.connect();
        wire.write("#".getBytes(StandardCharsets.US_ASCII));
        byte[] buf = new byte[1024];
        assertTrue(wire.read(buf) > 0, "the banner");
        wire.write("\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0, wire.available());
        wire.write("Reboot me not\n".getBytes(StandardCharsets.US_ASCII)); // an R in the CLI is a letter
        assertFalse(board.inBootloader());
        assertTrue(new String(buf, 0, wire.read(buf), StandardCharsets.US_ASCII).contains("###ERROR"));
        wire.close();
    }

    @Test
    void sticksOverMspArmItWhenTheArmRangeHolds() throws IOException {
        board.setUp("aux 0 0 0 1700 2100 0 0").features(BetaflightPlan.mspReceiverMask(FakeFlightBoard.DEFAULT_FEATURES));
        try (BetaflightUsb fc = open()) {
            fc.msp(Msp.SET_RAW_RC, rc(1500, 1500, 1000, 1500, 1000));
            assertFalse(board.armed());
            fc.msp(Msp.SET_RAW_RC, rc(1500, 1500, 1400, 1500, 2000));
            assertFalse(board.armed(), "the throttle is up: no arming");
            fc.msp(Msp.SET_RAW_RC, rc(1500, 1500, 1000, 1500, 2000));
            assertTrue(board.armed());
            assertTrue(Msp.armed(fc.msp(Msp.STATUS, null)));
            fc.msp(Msp.SET_RAW_RC, rc(1500, 1500, 1000, 1500, 1000));
            assertFalse(board.armed());
            assertArrayEquals(new int[]{1000, 1000, 1000, 1000}, board.motors());
        }
        // a serial receiver: MSP's sticks are not the receiver's
        FakeFlightBoard serial = new FakeFlightBoard().setUp("aux 0 0 0 1700 2100 0 0");
        BetaflightUsb.Wire wire = serial.connect();
        wire.write(de.droiddrone.common.MspFrames.v1Request(Msp.SET_RAW_RC, rc(1500, 1500, 1000, 1500, 2000)));
        assertFalse(serial.armed());
    }

    private static byte[] rc(int... channels) {
        byte[] p = new byte[channels.length * 2];
        for (int i = 0; i < channels.length; i++) Msp.putU16(p, 2 * i, channels[i]);
        return p;
    }

    @Test
    void aRefusalOnDemandStopsAWriteAndACutWireEndsItUnsaved() throws IOException {
        String before = board.saved();
        board.refuseCli("set small_angle");
        try (BetaflightUsb fc = open()) {
            fc.enterCli();
            CliWrite.Result r = CliWrite.run(fc::command, List.of("set min_check = 1100", "set small_angle = 60", "set serial_update_rate_hz = 2000"), log::add);
            assertFalse(r.complete());
            assertTrue(r.stopped().contains("refused \"set small_angle = 60\""), r.stopped());
        }
        assertEquals(List.of("set min_check = 1100", "set small_angle = 60", "exit"), board.cli());
        assertEquals(before, board.saved());
        board.refuseNothing();
        board.cutAfterCliCommands = 1;
        BetaflightUsb fc = open();
        fc.enterCli();
        fc.command("set min_check = 1100");
        IOException cut = assertThrows(IOException.class, () -> fc.command("set small_angle = 60"));
        assertTrue(cut.getMessage().contains("disconnected"), cut.getMessage());
        fc.close();
        assertEquals(before, board.saved(), "USB power gone with the wire: it starts again from what was saved");
        assertEquals(before, board.inMemory());
        assertFalse(board.inCli());
        assertEquals(0, board.saves);
        // another board is told apart by its id
        byte[] first;
        try (BetaflightUsb again = open()) {
            first = again.msp(Msp.UID, null);
            board.uid = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
            assertFalse(java.util.Arrays.equals(first, again.msp(Msp.UID, null)));
        }
    }

    @Test
    void theEscsAreBehindThePassthroughAndKeepASavedSpinDirection() throws IOException {
        board.battery(16.4, 4);
        try (BetaflightUsb fc = open()) {
            List<EscFirmware.Info> all;
            try (EscLink escs = fc.escs(log::add)) {
                assertEquals(4, escs.count());
                assertTrue(board.inPassthrough());
                all = escs.infoAll();
            }
            assertEquals("4 x Bluejay 0.19 - beacon 10 min", EscFirmware.summary(all));
            assertFalse(board.inPassthrough());
            assertEquals("BTFL", fc.firmware(), "MSP answers again");
        }
        for (FakeEscBoard.Esc e : board.escs()) assertEquals(0, e.erases + e.writes);
        assertEquals(1, board.escs().get(2).memory[0x1A00 + EscFirmware.MOTOR_DIRECTION]);
        AppWrites.reverse(board.port(), T, 3, true);
        assertEquals(List.of("motor 3: 8 12"), board.dshotCommands());
        assertEquals(2, board.escs().get(2).memory[0x1A00 + EscFirmware.MOTOR_DIRECTION], "saved inside the ESC, not in Betaflight");
        assertEquals(new FakeFlightBoard().saved(), board.saved());
    }
}
