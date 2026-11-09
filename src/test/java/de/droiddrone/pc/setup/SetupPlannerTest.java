package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drone setup's plan from a made-up flight controller's CLI: what is asked, in which order, and what would be written -
 * the part of a round that was inside the GUI's USB worker until the review of 1.0.53.
 */
class SetupPlannerTest {
    static final String F7 = "# Betaflight / STM32F7X2 (S7X2) 4.5.1 Oct 13 2024 / 12:00:00 (77d01ba3b) MSP API: 1.46\r\n";
    static final String OLD = "# Betaflight / STM32F411 (S411) 4.3.2 Feb 1 2023 / 12:00:00 (abc) MSP API: 1.44\r\n";
    static final String NEW = "# Betaflight / STM32F7X2 (S7X2) 2025.12.2 Dec 1 2025 / 12:00:00 (def) MSP API: 1.47\r\n";
    static final int SERIAL_RX = BetaflightPlan.FEATURE_RX_SERIAL | 1 << 18;
    static final int MSP_RX_GPS = BetaflightPlan.FEATURE_RX_MSP | BetaflightPlan.FEATURE_GPS | 1 << 18;
    static final Set<Integer> BOXES = Set.of(0, 1, 2, 11, 13, 36, 46);
    static final String SILENT = "Disabled:  GYRO_CALIBRATED DISARMING ARMING ARMING_GPS_FIX BAT_CRIT_LOW BAT_LOW"
            + " GPS_STATUS ACC_CALIBRATION ACC_CALIBRATION_FAIL READY_BEEP MULTI_BEEPS DISARM_REPEAT ARMED SYSTEM_INIT"
            + " BLACKBOX_ERASE CRASHFLIP CAM_CONNECTION_OPEN CAM_CONNECTION_CLOSE ARMING_GPS_NO_FIX\r\n";

    /** A CLI answering from a table ("" for anything else), keeping what was asked. */
    static final class FakeCli implements SetupPlanner.Cli {
        final Map<String, String> replies = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        FakeCli() {
            replies.put("version", F7);
            replies.put("board_name", "# board_name\r\nboard_name IFLIGHT_BLITZ_F722\r\n");
            replies.put("get craft_name", "craft_name = Vanguard\r\n");
            replies.put("aux", aux());
            replies.put("get serial_update_rate_hz", "serial_update_rate_hz = 100\r\nAllowed range: 100 - 2000\r\n");
            replies.put("get force_battery_cell_count", "force_battery_cell_count = 0\r\nAllowed range: 0 - 24\r\n");
            replies.put("beeper list", BeepersTest.LIST);
            replies.put("beeper", "Disabled:  none\r\n");
            replies.put("beacon", "Disabled:  RX_LOST  RX_SET\r\n");
            replies.put("serial", GpsSetupTest.SERIAL);
            replies.put("get gps_auto_baud", GpsSetupTest.AUTO_BAUD_OFF);
            replies.put("get mag_hardware", "mag_hardware = NONE\r\n");
            replies.put("get mag_calibration", "mag_calibration = -123,45,6\r\n");
            replies.put("get failsafe_procedure", "failsafe_procedure = DROP\r\n");
            replies.put("get gps_rescue_allow_arming_without_fix", "gps_rescue_allow_arming_without_fix = OFF\r\n");
        }

        FakeCli with(String command, String reply) {
            replies.put(command, reply);
            return this;
        }

        @Override
        public String command(String line) throws IOException {
            asked.add(line);
            return replies.getOrDefault(line, "");
        }
    }

    /** Betaflight's "aux": 20 slots, these set. */
    static String aux(String... used) {
        StringBuilder sb = new StringBuilder("# aux\r\n");
        for (int i = 0; i < 20; i++) {
            String line = "aux " + i + " 0 0 900 900 0 0";
            for (String u : used) if (u.startsWith("aux " + i + " ")) line = u;
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    /** What the page asks for by default: AUX2 modes, ARM AUX1, PREARM AUX3; GPS as given. */
    static SetupPlanner.Wanted wanted(Boolean gps, int rescue, ModeSwitch.Failsafe failsafe) {
        return new SetupPlanner.Wanted(6, true, ModeSwitch.DEFAULT, rescue, failsafe, 1, 3,
                SetupPlanner.Gps.none(gps, GpsSetup.DEFAULT_UART, true));
    }

    static List<String> described(SetupPlanner.Result r) {
        return r.plan().stream().map(BetaflightPlan.Change::description).toList();
    }

    @Test
    void aStockBoardWithoutAGpsAnswerGetsTheReceiverRateCellsBeepersAndSwitches() throws IOException {
        FakeCli cli = new FakeCli();
        SetupPlanner.Result r = SetupPlanner.plan(cli, new SetupPlanner.Fc(BOXES, SERIAL_RX, false, null),
                wanted(null, 0, ModeSwitch.Failsafe.KEEP));
        assertEquals(List.of("version", "board_name", "get craft_name", "aux", "get serial_update_rate_hz",
                "get force_battery_cell_count", "beeper list", "beeper", "beacon"), cli.asked, "no GPS, no failsafe asked");
        assertEquals("IFLIGHT_BLITZ_F722 - Vanguard", r.board());
        List<String> d = described(r);
        assertTrue(d.get(0).startsWith("Receiver: MSP RX input, now RX_SERIAL"), d.toString());
        assertTrue(d.get(d.size() - 1).startsWith("Modes: flight modes on AUX2"), d.toString());
        assertTrue(d.get(d.size() - 1).contains("ARM on AUX1") && d.get(d.size() - 1).contains("PREARM on AUX3")
                && d.get(d.size() - 1).contains("BEEPER on AUX14"), d.toString());
        // POS HOLD needs a GPS (and 2025.12): it isn't written, that position flies acro
        assertTrue(r.modes().blocked().containsKey(ModeSwitch.Mode.POS_HOLD));
        assertNull(r.failsafe());
        assertNull(r.compassRead());
        assertFalse(r.gps().onFc());
    }

    @Test
    void aGpsWithACompassIsAskedForAndRescueBecomesTheFailsafe() throws IOException {
        FakeCli cli = new FakeCli().with("version", NEW);
        SetupPlanner.Result r = SetupPlanner.plan(cli, new SetupPlanner.Fc(BOXES, MSP_RX_GPS, true, true),
                wanted(true, 4, ModeSwitch.Failsafe.GPS_RESCUE));
        assertEquals(List.of("version", "board_name", "get craft_name", "aux", "get serial_update_rate_hz",
                "get force_battery_cell_count", "serial", "get gps_auto_baud", "get mag_hardware", "get mag_calibration",
                "beeper list", "beeper", "beacon", "get failsafe_procedure", "get gps_rescue_allow_arming_without_fix"), cli.asked);
        List<String> d = described(r);
        assertFalse(d.get(0).startsWith("Receiver"), "MSP already");
        assertTrue(d.contains("Failsafe: GPS rescue"), d.toString());
        assertTrue(r.rescueFailsafe());
        assertTrue(d.stream().anyMatch(s -> s.startsWith("Arming without a GPS fix")), d.toString());
        assertTrue(d.get(d.size() - 1).contains("GPS rescue up on AUX4"), d.toString());
        assertEquals(Boolean.TRUE, r.compassRead(), "calibrated, for the observation map");
        assertTrue(r.gps().compassPending(), "mag_hardware NONE -> AUTO");
        assertEquals("UART3", r.gps().portNow());
        assertTrue(r.modes().blocked().isEmpty(), r.modes().blocked().toString());
        // every command of the plan is written in this order: the changes as listed
        List<String> written = new ArrayList<>();
        for (BetaflightPlan.Change c : r.plan()) written.addAll(c.commands());
        assertTrue(written.indexOf("set failsafe_procedure = GPS-RESCUE") < written.indexOf("set gps_rescue_allow_arming_without_fix = ON"));
    }

    @Test
    void noGpsFoundBlocksItsModesAndTheRescueFailsafe() throws IOException {
        SetupPlanner.Result r = SetupPlanner.plan(new FakeCli().with("version", NEW), new SetupPlanner.Fc(BOXES, MSP_RX_GPS, false, false),
                wanted(true, 4, ModeSwitch.Failsafe.GPS_RESCUE));
        assertEquals("No GPS found - see the GPS row.", r.failsafeProblem());
        assertEquals("No GPS found - see the GPS row.", r.modes().blocked().get(ModeSwitch.Mode.POS_HOLD));
        assertFalse(described(r).contains("Failsafe: GPS rescue"));
        assertFalse(r.rescueFailsafe());
        assertNull(r.compassRead(), "no compass seen: not remembered");
    }

    @Test
    void gpsTurnedOnFirstThenItsModesAfterTheRestart() throws IOException {
        SetupPlanner.Result r = SetupPlanner.plan(new FakeCli().with("version", NEW),
                new SetupPlanner.Fc(BOXES, BetaflightPlan.FEATURE_RX_MSP, false, null), wanted(true, 4, ModeSwitch.Failsafe.GPS_RESCUE));
        assertEquals("Turning GPS on; checked after restart.", r.failsafeProblem());
        assertTrue(r.gps().gpsPending());
    }

    @Test
    void anOldBetaflightIsToldWhatItLacks() throws IOException {
        SetupPlanner.Result r = SetupPlanner.plan(new FakeCli().with("version", OLD), new SetupPlanner.Fc(BOXES, MSP_RX_GPS, true, true),
                wanted(true, 4, ModeSwitch.Failsafe.GPS_RESCUE));
        assertTrue(r.failsafeProblem().contains("needs Betaflight 4.4"), r.failsafeProblem());
        assertTrue(r.modes().blocked().get(ModeSwitch.Mode.POS_HOLD).contains("needs Betaflight 2025.12"));
    }

    @Test
    void dropChosenOverARescueFailsafe() throws IOException {
        SetupPlanner.Result r = SetupPlanner.plan(new FakeCli().with("get failsafe_procedure", "failsafe_procedure = GPS-RESCUE\r\n"),
                new SetupPlanner.Fc(BOXES, MSP_RX_GPS, false, true), wanted(false, 0, ModeSwitch.Failsafe.DROP));
        assertTrue(described(r).contains("Failsafe: drop"));
        assertFalse(r.rescueFailsafe());
    }

    @Test
    void aBoardWithoutBeeperModeGetsNoButtonAndAnOlderOneItsOwnName() throws IOException {
        FakeCli cli = new FakeCli().with("get craft_name", "###ERROR IN get: INVALID NAME: craft_name###\r\n")
                .with("name", "# name\r\nname Old\r\n");
        SetupPlanner.Result r = SetupPlanner.plan(cli, new SetupPlanner.Fc(Set.of(0, 1, 2, 36), SERIAL_RX, false, null),
                wanted(false, 0, ModeSwitch.Failsafe.KEEP));
        assertTrue(cli.asked.contains("name"), "craft_name refused: the old command");
        assertFalse(described(r).get(described(r).size() - 1).contains("BEEPER"), described(r).toString());
    }

    @Test
    void aBoardSetUpAlreadyNeedsNothing() throws IOException {
        FakeCli cli = new FakeCli()
                .with("get serial_update_rate_hz", "serial_update_rate_hz = 2000\r\n")
                .with("get force_battery_cell_count", "force_battery_cell_count = 6\r\n")
                .with("beeper", SILENT).with("beacon", "Disabled:  RX_LOST\r\n")
                .with("aux", aux("aux 0 0 0 1700 2100 0 0", "aux 1 1 1 1300 1700 0 0", "aux 2 36 2 1700 2100 0 0",
                        "aux 3 13 13 1700 2100 0 0"));
        ModeSwitch angleOnly = new ModeSwitch(2, ModeSwitch.Mode.ACRO, ModeSwitch.Mode.ANGLE, ModeSwitch.Mode.ACRO);
        SetupPlanner.Result r = SetupPlanner.plan(cli, new SetupPlanner.Fc(BOXES, BetaflightPlan.FEATURE_RX_MSP, false, null),
                new SetupPlanner.Wanted(6, true, angleOnly, 0, ModeSwitch.Failsafe.KEEP, 1, 3, SetupPlanner.Gps.none(false, 3, false)));
        assertEquals(List.of(), described(r));
        assertTrue(r.modes().isSet());
        assertEquals(Set.of(1), ModeSwitch.armAuxes(r.aux()));
        assertNotNull(r.arm());
        assertEquals(List.of(), r.replaced());
    }

    @Test
    void takenOffReadsAsASentence() {
        assertEquals("", SetupPlanner.takenOff(List.of()));
        assertEquals(" Taken off: ARM on AUX3, BEEPER on AUX2.", SetupPlanner.takenOff(List.of("ARM on AUX3", "BEEPER on AUX2")));
    }

    // ---- recorded: every round of a grid as 1.0.53 planned it (moved out of the GUI unchanged) ----

    private static final String RECORDED = "49d15c23db75d43348ec37e8141382dec3b6fb38a33d2458ac6eb641f4ed0901";

    static String grid() throws IOException {
        StringBuilder sb = new StringBuilder();
        String[] versions = {F7, OLD, NEW};
        int[] masks = {SERIAL_RX, MSP_RX_GPS, BetaflightPlan.FEATURE_RX_MSP};
        String[] failsafes = {"failsafe_procedure = DROP\r\n", "failsafe_procedure = GPS-RESCUE\r\n"};
        String[] auxes = {aux(), aux("aux 0 0 2 1700 2100 0 0", "aux 1 2 1 1700 2100 0 0", "aux 2 46 3 1700 2100 0 0")};
        List<ModeSwitch> modes = new ArrayList<>();
        modes.add(null);
        modes.add(ModeSwitch.DEFAULT);
        Boolean[] gpsAnswers = {null, false, true};
        Boolean[] gpsFound = {null, false, true};
        for (String version : versions) for (int mask : masks) for (String failsafe : failsafes) for (String aux : auxes)
            for (ModeSwitch m : modes) for (Boolean gps : gpsAnswers) for (Boolean found : gpsFound)
                for (ModeSwitch.Failsafe f : ModeSwitch.Failsafe.values()) for (int rescue : new int[]{0, 4}) {
                    FakeCli cli = new FakeCli().with("version", version).with("get failsafe_procedure", failsafe).with("aux", aux);
                    SetupPlanner.Result r = SetupPlanner.plan(cli, new SetupPlanner.Fc(BOXES, mask, true, found),
                            new SetupPlanner.Wanted(6, true, m, rescue, f, 1, 3, SetupPlanner.Gps.none(gps, 3, true)));
                    sb.append("# ").append(version.trim()).append(' ').append(mask).append(' ').append(failsafe.trim()).append(' ')
                            .append(aux.hashCode()).append(' ').append(m).append(' ').append(gps).append(' ').append(found).append(' ')
                            .append(f).append(' ').append(rescue).append('\n')
                            .append("asked=").append(cli.asked).append('\n');
                    for (BetaflightPlan.Change c : r.plan()) sb.append(c.description()).append(" | ").append(c.commands()).append('\n');
                    sb.append("failsafe=").append(r.failsafe()).append(" rescue=").append(r.rescueFailsafe()).append(" problem=")
                            .append(r.failsafeProblem()).append(" gps=").append(r.gps()).append(" compass=").append(r.compassRead())
                            .append(" blocked=").append(r.modes() == null ? null : new java.util.TreeMap<>(r.modes().blocked()))
                            .append(" replaced=").append(r.replaced()).append('\n');
                }
        return sb.toString();
    }

    @Test
    void everyRoundIsAsRecorded() throws Exception {
        String text = grid();
        String dump = System.getenv("GOLDEN_DUMP");
        if (dump != null) Files.writeString(Path.of(dump), text);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(RECORDED, hash, "the rounds differ from the recorded ones (GOLDEN_DUMP=<file> writes them for a diff)");
    }
}
