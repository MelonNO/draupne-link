package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.GpsSetup;
import de.droiddrone.pc.setup.ModeSwitch;
import de.droiddrone.pc.setup.Orientation;
import de.droiddrone.pc.setup.SetupPlanner;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Drone setup row on the real board ({@link HwDroneSetupScenario}) held before it meets the board: which CLI lines
 * change a board, what setting a dump line is, the judge that holds the board after Drone setup to what Drone setup
 * said, the requests that never may reach the board, the choices answered as the board has them, the live view's
 * line, a page's words - and the whole of it on the made-up board behind a real keeper: Drone setup's own round
 * through the keeper's lease and the tap, the board read before and after, every difference said, nothing else; a
 * second round on the board it set up changes nothing; a change somebody else made is caught.
 */
class HwDroneSetupTest {
    private static final BoardPort.Timing T = new BoardPort.Timing(20, 15_000);
    private static final BetaflightUsb.Port KEEPER = new BetaflightUsb.Port(KeeperWire.PORT_NAME, KeeperWire.DESCRIPTION);

    @AfterEach
    void end() {
        BetaflightUsb.use(null);
    }

    // ------------------------------------------------------------------ the CLI's lines

    @Test
    void aLineThatOnlyAsksIsToldFromOneThatWrites() {
        for (String read : List.of("get serial_update_rate_hz", "diff all", "dump all", "beeper list", "aux", "serial", "beeper", "beacon", "version", "board_name",
                "get craft_name", "name", "exit", "save", "", "#", "status", "feature", "map")) {
            assertTrue(HwDroneSetup.reads(read), read);
        }
        for (String write : List.of("set serial_update_rate_hz = 2000", "aux 0 0 0 1700 2100 0 0", "feature GPS", "feature -GPS", "beeper -GYRO_CALIBRATED",
                "beacon RX_SET", "serial 2 2 115200 57600 0 115200", "defaults nosave", "map AETR1234", "profile 1", "rxfail 3 h", "batch start")) {
            assertFalse(HwDroneSetup.reads(write), write);
        }
    }

    @Test
    void aWrittenCommandAndTheDumpLineItChangesHaveOneKey() {
        assertEquals("set vbat_min_cell_voltage", HwDroneSetup.key("set vbat_min_cell_voltage = 280"));
        assertEquals("set vbat_min_cell_voltage", HwDroneSetup.key("set vbat_min_cell_voltage=330"));
        assertEquals("set mag_hardware", HwDroneSetup.key("set MAG_HARDWARE = NONE"));
        assertEquals("aux 3", HwDroneSetup.key("aux 3 36 2 1700 2100 0 0"));
        assertEquals("feature GPS", HwDroneSetup.key("feature -GPS"));
        assertEquals("feature GPS", HwDroneSetup.key("feature GPS"));
        assertEquals("beeper GYRO_CALIBRATED", HwDroneSetup.key("beeper -GYRO_CALIBRATED"));
        assertEquals("beacon RX_SET", HwDroneSetup.key("beacon RX_SET"));
        assertEquals("serial 2", HwDroneSetup.key("serial 2 2 115200 57600 0 115200"));
        assertEquals("resource MOTOR 1", HwDroneSetup.key("resource MOTOR 1 B00"));
        assertEquals("map", HwDroneSetup.key("map TAER1234"));
        assertEquals("set acc_calibration", HwDroneSetup.dumpLine("dump all: only on the board now: set acc_calibration = 12,-3,40,1").replaceFirst(" =.*", ""));
        assertEquals("set p_roll = 45", HwDroneSetup.dumpLine("dump all: only in the snapshot: [profile 0] set p_roll = 45"));
        assertNull(HwDroneSetup.dumpLine("MSP features: 00 in the snapshot, 01 now"));
        assertEquals("features", HwDroneSetup.mspFact("MSP features: 00 in the snapshot, 01 now"));
    }

    /**
     * The judge's words for each command are the app's own descriptions: every command a Drone setup round plans on a
     * flown board - the stick delay, the cells, the battery warnings, the GPS's port, its baud, the compass, the
     * beepers and beacons, the failsafe, arming without a fix, the modes - is named in the description it comes in.
     */
    @Test
    void everyCommandOfTheAppsPlanIsNamedInItsOwnDescription() throws Exception {
        FakeFlightBoard board = FakeFlightBoard.flown(); // GPS on UART2, a compass on AUTO, ARM on AUX3, the failsafe DROP
        BetaflightUsb.use(board.usb());
        List<BetaflightPlan.Change> plan;
        try (BetaflightUsb fc = BetaflightUsb.open(board.port())) {
            Set<Integer> modes = fc.boxIds();
            int mask = fc.featureMask();
            fc.enterCli();
            plan = SetupPlanner.plan(fc::command, new SetupPlanner.Fc(modes, mask, false, true),
                    new SetupPlanner.Wanted(4, true, ModeSwitch.DEFAULT, ModeSwitch.DEFAULT_RESCUE_AUX, ModeSwitch.Failsafe.GPS_RESCUE, ModeSwitch.DEFAULT_ARM_AUX,
                            ModeSwitch.DEFAULT_PREARM_AUX, SetupPlanner.Gps.none(true, 3, false))).plan();
        }
        // and the GPS turned on, on a board that has it off
        plan = new ArrayList<>(plan);
        plan.addAll(GpsSetup.gpsPlan(0, "serial 0 64 115200 57600 0 115200\nserial 2 0 115200 57600 0 115200", "gps_auto_baud = ON", 3));
        Set<String> kinds = new java.util.TreeSet<>();
        for (BetaflightPlan.Change c : plan) {
            for (String command : c.commands()) {
                assertFalse(HwDroneSetup.reads(command), command);
                assertTrue(c.description().contains(HwDroneSetup.named(command)), "\"" + command + "\" in \"" + c.description() + "\": " + HwDroneSetup.named(command));
                kinds.add(HwDroneSetup.key(command).replaceFirst(" .*", "") + (command.startsWith("set ") ? " " + HwDroneSetup.key(command).substring(4) : ""));
            }
        }
        for (String kind : List.of("set serial_update_rate_hz", "set force_battery_cell_count", "set vbat_min_cell_voltage", "set vbat_warning_cell_voltage",
                "set gps_auto_baud", "set mag_hardware", "set failsafe_procedure", "set gps_rescue_allow_arming_without_fix", "serial", "beeper", "beacon", "aux",
                "feature")) {
            assertTrue(kinds.contains(kind), kind + " is among the plan's commands: " + kinds);
        }
    }

    // ------------------------------------------------------------------ the judge

    private static final String SAID = "Receiver: MSP RX input, now RX_SERIAL; Battery: low at 3.2 V a cell, empty at 2.8 V (warnings only); Modes: ARM on AUX1.";

    @Test
    void theJudgeTakesWhatDroneSetupSaidItChangedAndNothingElse() {
        List<String> differences = List.of(
                "dump all: only in the snapshot: set vbat_min_cell_voltage = 330",
                "dump all: only on the board now: set vbat_min_cell_voltage = 280",
                "dump all: only in the snapshot: feature RX_SERIAL",
                "dump all: only on the board now: feature RX_MSP",
                "dump all: only in the snapshot: aux 0 0 2 1700 2100 0 0",
                "dump all: only on the board now: aux 0 0 0 1700 2100 0 0",
                "MSP features: 08000000 in the snapshot, 00400000 now",
                "MSP mode_ranges: 0002 in the snapshot, 0000 now");
        List<String> written = List.of("set vbat_min_cell_voltage = 280", "aux 0 0 0 1700 2100 0 0", "set vbat_warning_cell_voltage = 320");
        HwDroneSetup.Verdict v = HwDroneSetup.judge(differences, written, List.of(SAID), written, false, true);
        assertEquals(List.of(), v.wrong());
        assertEquals(8, v.differences().size());
        assertTrue(v.differences().stream().allMatch(HwDroneSetup.Difference::said), v.differences().toString());
        assertTrue(v.notes().stream().anyMatch(n -> n.contains("vbat_warning_cell_voltage")), "written and so already: a note, " + v.notes());

        // what nobody said: a PID setting, the channel map, the calibration without CALIBRATE, the receiver unsaid
        HwDroneSetup.Verdict other = HwDroneSetup.judge(List.of("dump all: only in the snapshot: [profile 0] set p_roll = 45",
                        "MSP rx_map: 00010203 in the snapshot, 01000203 now", "dump all: only on the board now: set acc_calibration = 12,-3,40,1",
                        "dump all: only on the board now: feature RX_MSP"),
                List.of(), List.of("Battery: low at 3.2 V a cell"), List.of(), false, true);
        assertEquals(4, other.wrong().size(), other.wrong().toString());
        assertTrue(other.wrong().get(0).contains("set p_roll = 45"), other.wrong().get(0));
        // CALIBRATE pressed: its calibration is said
        HwDroneSetup.Verdict calibrated = HwDroneSetup.judge(List.of("dump all: only on the board now: set acc_calibration = 12,-3,40,1",
                "dump all: only in the snapshot: set acc_calibration = 0,0,0,1"), List.of(), List.of(), List.of(), true, false);
        assertEquals(List.of(), calibrated.wrong());

        // a command written and not named; a line sent that the log has not; a line the log has that was not sent
        HwDroneSetup.Verdict unnamed = HwDroneSetup.judge(List.of("dump all: only on the board now: set small_angle = 33"), List.of("set small_angle = 33"),
                List.of("Stick delay: serial_update_rate_hz 100 -> 2000"), List.of("set small_angle = 33", "set craft_name = X"), false, false);
        assertEquals(2, unnamed.wrong().size(), unnamed.wrong().toString());
        assertTrue(unnamed.wrong().get(0).contains("its words do not name it"), unnamed.wrong().get(0));
        assertTrue(unnamed.wrong().get(1).contains("set craft_name = X") && unnamed.wrong().get(1).contains("no such write"), unnamed.wrong().get(1));
        HwDroneSetup.Verdict notSent = HwDroneSetup.judge(List.of(), List.of("set serial_update_rate_hz = 2000"), List.of("Stick delay: serial_update_rate_hz 100 -> 2000"),
                List.of(), false, false);
        assertTrue(notSent.wrong().size() == 1 && notSent.wrong().get(0).contains("was not sent it"), notSent.wrong().toString());
        assertTrue(HwDroneSetup.judge(List.of(), List.of(), List.of(), null, false, false).wrong().isEmpty(), "no tap: nothing to hold the log to");
    }

    @Test
    void theAppsLogSaysWhatItWroteAndWhatItSaid() {
        List<String> log = List.of("10:01:02.003 Betaflight CLI: set serial_update_rate_hz = 2000 -> (ok)",
                "10:01:02.050 Betaflight CLI: aux 0 0 0 1700 2100 0 0 -> aux 0 0 0 1700 2100 0 0",
                "10:01:03.000 Betaflight: Stick delay: serial_update_rate_hz 100 -> 2000; Modes: ARM on AUX1. Its settings before: C:\\x\\betaflight-1.txt",
                "10:01:04.000 Betaflight: not the confirmed flight controller - nothing written.");
        assertEquals(List.of("set serial_update_rate_hz = 2000", "aux 0 0 0 1700 2100 0 0"), HwDroneSetup.written(log));
        assertEquals(List.of("Stick delay: serial_update_rate_hz 100 -> 2000; Modes: ARM on AUX1"), HwDroneSetup.said(log));
    }

    @Test
    void whatNeverMayReachTheBoardIsFoundInTheTapsRequests() {
        String stop = Msp.hex(Msp.allMotors(1000)), spin = Msp.hex(Msp.allMotors(1100));
        List<SetupTap.Event> fine = List.of(new SetupTap.Event(0, 1, "msp", Msp.SET_MOTOR, stop), new SetupTap.Event(0, 1, "msp", Msp.STATUS, ""),
                new SetupTap.Event(0, 1, "msp", Msp.SET_FEATURE_CONFIG, "00400000"), new SetupTap.Event(0, 1, "cli", 0, "save"));
        assertEquals(List.of(), HwDroneSetup.forbidden(fine, false));
        List<SetupTap.Event> bad = List.of(new SetupTap.Event(0, 1, "msp", Msp.SET_MOTOR, spin), new SetupTap.Event(0, 1, "msp", Msp.SET_PASSTHROUGH, ""),
                new SetupTap.Event(0, 1, "msp2", Msp.SEND_DSHOT_COMMAND, "010003070c"), new SetupTap.Event(0, 1, "msp2", Msp.SET_MOTOR_OUTPUT_REORDERING, "0401000203"),
                new SetupTap.Event(0, 1, "msp", Msp.SET_MIXER_CONFIG, "0301"), new SetupTap.Event(0, 1, "msp", Msp.EEPROM_WRITE, ""),
                new SetupTap.Event(0, 1, "msp", Msp.REBOOT, ""), new SetupTap.Event(0, 1, "msp", HwDroneSetup.ACC_CALIBRATION, ""));
        assertEquals(8, HwDroneSetup.forbidden(bad, false).size(), HwDroneSetup.forbidden(bad, false).toString());
        assertEquals(7, HwDroneSetup.forbidden(bad, true).size(), "CALIBRATE pressed: its request is the row's own");
        assertTrue(HwDroneSetup.stopOnly(stop));
        assertFalse(HwDroneSetup.stopOnly(spin));
    }

    // ------------------------------------------------------------------ the choices, the live view, a page's words

    private static final String[] CELL_KEYS = {"drone.cellCount", "drone.cellCountConfirmed"};

    @Test
    void theQuestionsAreAnsweredAsTheBoardHasThem() {
        String flown = FakeFlightBoard.flown().saved(); // GPS on UART2, mag_hardware AUTO, the cells counted by Betaflight
        HwDroneSetup.Choices c = HwDroneSetup.choices(flown, 4, CELL_KEYS);
        assertEquals(Map.of(GpsSetup.GPS_KEY, "yes", GpsSetup.UART_KEY, "2", GpsSetup.COMPASS_KEY, "yes", "drone.cellCount", "4", "drone.cellCountConfirmed", "true"),
                c.settings());
        assertNull(c.refuse());
        HwDroneSetup.Choices none = HwDroneSetup.choices(flown, 0, CELL_KEYS);
        assertNotNull(none.refuse(), "counted by Betaflight and no count in the hardware file: CONFIRM would force 6S");
        assertFalse(none.settings().containsKey("drone.cellCount"));
        String stock = new FakeFlightBoard().setUp("set force_battery_cell_count = 4", "set mag_hardware = NONE").saved();
        HwDroneSetup.Choices s = HwDroneSetup.choices(stock, 0, CELL_KEYS);
        assertEquals(Map.of(GpsSetup.GPS_KEY, "no", GpsSetup.COMPASS_KEY, "no"), s.settings(), "no GPS, no compass; 4S forced: the page takes it itself");
        assertNull(s.refuse());
        assertTrue(String.join(" ", s.words()).contains("4S"), s.words().toString());
    }

    @Test
    void theLiveViewsLineIsReadAsRollAndPitch() {
        assertArrayEquals(new double[]{0, 0}, HwDroneSetup.attitude("Betaflight: nose level, sides level"));
        assertArrayEquals(new double[]{-1, -2}, HwDroneSetup.attitude("Betaflight: nose up 2°, left side down 1°"));
        assertArrayEquals(new double[]{3, -1}, HwDroneSetup.attitude("Betaflight: " + Orientation.describe(2.6, -1.2)), "the app's own words");
        assertArrayEquals(new double[]{-4, 5}, HwDroneSetup.attitude("Betaflight: " + Orientation.describe(-4.2, 5.1)));
        assertTrue(HwDroneSetup.flat(HwDroneSetup.attitude("Betaflight: nose down 3°, right side down 2°")));
        assertFalse(HwDroneSetup.flat(HwDroneSetup.attitude("Betaflight: nose down 4°, sides level")));
        assertNull(HwDroneSetup.attitude("no reading shown"));
        assertNull(HwDroneSetup.attitude("COM9: Flight controller lost."));
        assertFalse(HwDroneSetup.flat(null));
    }

    @Test
    void aSettingsLineIsReadAsItsLabelItsChoicesAndItsWords() {
        JPanel root = new JPanel();
        root.setLayout(new javax.swing.BoxLayout(root, javax.swing.BoxLayout.Y_AXIS));
        JPanel line = new JPanel(new BorderLayout(10, 0)), left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        left.add(new JLabel("GPS"));
        JComboBox<String> yes = new JComboBox<>(new String[]{"No", "Yes"});
        yes.setSelectedIndex(1);
        left.add(yes);
        line.add(left, BorderLayout.WEST);
        JEditorPane cell = new JEditorPane("text/html", "<span style='color:#ffaa00'>No GPS yet. <b>Connect the battery</b>, then <b>Read again</b>.</span>");
        line.add(cell, BorderLayout.CENTER);
        root.add(new JLabel("AVIONICS"));
        root.add(line);
        root.setSize(900, 300);
        layOut(root); // no window: validate() lays nothing out without one
        List<String> words = HwDroneSetup.words(root);
        assertEquals(2, words.size(), words.toString());
        assertEquals("AVIONICS", words.get(0));
        String gps = HwDroneSetup.row(words, "GPS");
        assertTrue(gps.startsWith("GPS [Yes]: No GPS yet.") && gps.contains("Connect the battery") && gps.endsWith("Read again."), gps);
        assertEquals("", HwDroneSetup.row(words, "COMPASS"));
        cell.setVisible(false); // nothing to say: the app hides a line's words
        assertEquals(List.of("AVIONICS", "GPS [Yes]:"), HwDroneSetup.words(root));
        line.setSize(900, 0); // folded away, as the app folds GPS RESCUE without a GPS
        assertEquals(List.of("AVIONICS"), HwDroneSetup.words(root));
    }

    private static void layOut(java.awt.Component c) {
        if (!(c instanceof java.awt.Container k)) return;
        k.doLayout();
        for (java.awt.Component child : k.getComponents()) layOut(child);
    }

    // ------------------------------------------------------------------ on the made-up board, behind a real keeper

    /**
     * The scenario's own pieces end to end, but for the app's window: the board read through the keeper's lease, Drone
     * setup's round (the app's own code, as its page makes it) through the tap, the board read again - each difference
     * one Drone setup said, the tap's writes the log's; then a second round on the board it set up writes nothing and
     * changes nothing; then a write of somebody else's is caught. The keeper has the board back, locked, after each.
     */
    @Test
    void onTheMadeUpBoardBehindAKeeperTheBoardChangesOnlyAsDroneSetupSays(@TempDir Path dir) throws Exception {
        FakeFlightBoard board = FakeFlightBoard.flown();
        try (KeeperBench bench = new KeeperBench(board)) {
            bench.config.readSettings = true; // as on the real board: its settings read in its CLI after every lease
            bench.start().beat();
            int setupPort = bench.keeper.setupPort();
            BoardSnapshot before = snapshot(setupPort, "before");
            waitForTheKeeper(bench);

            SetupTap tap = new SetupTap(KeeperWire.usb(setupPort));
            BetaflightUsb.use(tap);
            List<String> log = new ArrayList<>();
            List<BetaflightPlan.Change> plan = AppWrites.droneSetupChanges(KEEPER, T, log::add);
            log.add(saidLine(plan));
            waitForTheKeeper(bench);
            BoardSnapshot after = snapshot(setupPort, "after");
            waitForTheKeeper(bench);
            HwDroneSetup.Verdict v = HwDroneSetup.judge(before.differences(after), HwDroneSetup.written(log), HwDroneSetup.said(log), tap.cliWrites(), false,
                    !tap.msp(Msp.SET_FEATURE_CONFIG).isEmpty());
            assertEquals(List.of(), v.wrong(), v.differences().toString());
            assertTrue(v.differences().size() > 10, "a flown board has much to set: " + v.differences());
            assertTrue(v.differences().stream().anyMatch(d -> d.line().contains("feature RX_MSP")), v.differences().toString());
            assertEquals(1, tap.saves());
            assertEquals(List.of(), HwDroneSetup.forbidden(tap.events(), false));
            assertTrue(board.armingLocked(), "the keeper's lock is on again");

            // set up once: a second round writes nothing, and nothing changes
            SetupTap again = new SetupTap(KeeperWire.usb(setupPort));
            BetaflightUsb.use(again);
            List<String> log2 = new ArrayList<>();
            assertEquals(List.of(), AppWrites.droneSetupChanges(KEEPER, T, log2::add), "a board Drone setup set up has nothing left to set");
            waitForTheKeeper(bench);
            BoardSnapshot twice = snapshot(setupPort, "twice");
            waitForTheKeeper(bench);
            assertEquals(List.of(), after.differences(twice), "the second round changed nothing");
            assertEquals(List.of(), again.cliWrites());
            assertEquals(List.of(), HwDroneSetup.written(log2));

            // somebody else's write between the two reads: caught, and the line sent that no Drone setup log has
            SetupTap other = new SetupTap(KeeperWire.usb(setupPort));
            BetaflightUsb.use(other);
            AppWrites.cli(KEEPER, T, s -> { }, "set small_angle = 33");
            waitForTheKeeper(bench);
            BoardSnapshot changed = snapshot(setupPort, "changed");
            waitForTheKeeper(bench);
            HwDroneSetup.Verdict caught = HwDroneSetup.judge(twice.differences(changed), List.of(), List.of(), other.cliWrites(), false, false);
            assertEquals(3, caught.wrong().size(), caught.wrong().toString()); // the line out, the line in, the line sent
            assertTrue(caught.wrong().stream().allMatch(w -> w.contains("small_angle")), caught.wrong().toString());
            assertTrue(board.armingLocked());
        }
    }

    /** The board read through Drone setup's lease, as the scenario reads it before and after. */
    private BoardSnapshot snapshot(int setupPort, String label) throws Exception {
        BetaflightUsb.use(KeeperWire.usb(setupPort));
        return BoardSnapshot.take(KEEPER, new BoardSnapshot.Options(false, label, T), s -> { });
    }

    private static void waitForTheKeeper(KeeperBench bench) throws InterruptedException {
        Waits.waitFor(() -> {
            KeeperClient.State s = bench.state();
            return s.boardUp() && s.lease().equals("none") && Boolean.TRUE.equals(s.locked());
        }, 10_000, "the keeper to have the board again, locked", bench.said);
    }

    /** Drone setup's line in the app's log after a write (DroneSetup.BetaflightPage.work). */
    private static String saidLine(List<BetaflightPlan.Change> plan) {
        return "Betaflight: " + plan.stream().map(BetaflightPlan.Change::description).collect(Collectors.joining("; ")) + ". Its settings before: (a test's: none)";
    }
}
