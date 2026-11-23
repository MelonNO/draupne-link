package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AS-6 (ports) and AS-7 (frame and size presets, AUTOTUNE on a switch): the plans, and the write through
 * Drone setup's one path against a made-up ArduPilot ({@link FakeArduPilot}).
 */
class ArduPortsTuningTest {
    private MavSession fc;

    @AfterEach
    void close() {
        if (fc != null) fc.close();
    }

    private static Map<String, Float> board() {
        Map<String, Float> p = new HashMap<>();
        p.put("SERIAL0_PROTOCOL", 2f);
        p.put("SERIAL0_BAUD", 115f);
        p.put("SERIAL1_PROTOCOL", 2f);
        p.put("SERIAL1_BAUD", 57f);
        p.put("SERIAL2_PROTOCOL", 23f); // RC input
        p.put("SERIAL2_BAUD", 115f);
        p.put("SERIAL3_PROTOCOL", -1f);
        p.put("SERIAL3_BAUD", 38f);
        p.put("SERIAL4_PROTOCOL", 5f);
        p.put("SERIAL4_BAUD", 230f);
        return p;
    }

    private static ArduPorts.Choice choice(int gps, int radio, boolean replace) {
        return new ArduPorts.Choice(gps, radio, 57, replace);
    }

    @Test
    void aPortThatAlreadyDoesTheJobIsLeftAsItIs() {
        ArduPorts.Plan p = ArduPorts.plan(choice(4, 1, false), board());
        assertTrue(p.ok(), p.blocked().toString());
        assertTrue(p.wanted().isEmpty(), "USB MAVLink, GPS on 4, radio on 1 at 57: nothing to write " + p.wanted());
    }

    @Test
    void aFreePortIsTakenAndAnotherGpsPortKeepsItsGps() {
        ArduPorts.Plan p = ArduPorts.plan(choice(3, 0, false), board());
        assertTrue(p.ok());
        assertEquals(5f, p.wanted().get("SERIAL3_PROTOCOL").value());
        assertFalse(p.wanted().containsKey("SERIAL4_PROTOCOL"), "SERIAL4's GPS is kept");
        assertTrue(p.notes().stream().anyMatch(n -> n.contains("SERIAL4 keeps its GPS")), p.notes().toString());
        assertTrue(p.notes().stream().anyMatch(n -> n.contains("restart")));
    }

    @Test
    void aPortInUseIsNeverTakenWithoutReplace() {
        ArduPorts.Plan p = ArduPorts.plan(choice(2, 0, false), board());
        assertFalse(p.ok());
        assertEquals(List.of("SERIAL2 has RC input now: tick REPLACE to use it."), p.blocked());
        assertTrue(p.wanted().isEmpty(), "nothing written while blocked");
        ArduPorts.Plan r = ArduPorts.plan(choice(2, 0, true), board());
        assertTrue(r.ok());
        assertEquals(5f, r.wanted().get("SERIAL2_PROTOCOL").value());
        assertTrue(r.wanted().get("SERIAL2_PROTOCOL").why().contains("was RC input"));
    }

    @Test
    void theRadioGetsMavlinkAndItsSpeedAndNeverSharesTheGpsPort() {
        ArduPorts.Plan p = ArduPorts.plan(new ArduPorts.Choice(4, 3, 115, false), board());
        assertTrue(p.ok());
        assertEquals(2f, p.wanted().get("SERIAL3_PROTOCOL").value());
        assertEquals(115f, p.wanted().get("SERIAL3_BAUD").value());
        ArduPorts.Plan speed = ArduPorts.plan(new ArduPorts.Choice(0, 1, 115, false), board());
        assertEquals(Map.of("SERIAL1_BAUD", 115f), Map.of("SERIAL1_BAUD", speed.wanted().get("SERIAL1_BAUD").value()));
        assertEquals(1, speed.wanted().size(), "a MAVLink port only changes its speed");
        assertFalse(ArduPorts.plan(choice(3, 3, true), board()).ok(), "one port each");
        assertFalse(ArduPorts.plan(choice(7, 0, true), board()).ok(), "a port the board lacks");
    }

    @Test
    void usbThatIsNotMavlinkIsBlockedUntilReplace() {
        Map<String, Float> b = board();
        b.put("SERIAL0_PROTOCOL", 32f);
        assertFalse(ArduPorts.plan(ArduPorts.Choice.none(), b).ok());
        assertEquals(2f, ArduPorts.plan(new ArduPorts.Choice(0, 0, 57, true), b).wanted().get("SERIAL0_PROTOCOL").value());
    }

    @Test
    void sizePresetsAreStartingPointsAndKeepLeavesTheTune() {
        Map<String, ArduPlan.Wanted> five = ArduTuning.wanted(new ArduTuning.Choice(ArduTuning.Frame.BF_X, ArduTuning.Size.FIVE, 4, 0));
        assertEquals(1f, five.get("FRAME_CLASS").value());
        assertEquals(12f, five.get("FRAME_TYPE").value(), "Betaflight's motor order");
        assertEquals(75f, five.get("INS_GYRO_FILTER").value());
        assertEquals(168_600f, five.get("ATC_ACCEL_R_MAX").value());
        assertEquals(0.5f, five.get("MOT_THST_EXPO").value(), 1e-6);
        assertEquals(16.8f, five.get("MOT_BAT_VOLT_MAX").value(), 1e-4);
        assertTrue(five.get("ATC_RAT_RLL_P").why().contains("5\" start"));
        Map<String, ArduPlan.Wanted> ten = ArduTuning.wanted(new ArduTuning.Choice(ArduTuning.Frame.AP_X, ArduTuning.Size.TEN, 6, 0));
        assertEquals(42f, ten.get("INS_GYRO_FILTER").value());
        assertEquals(0.135f, ten.get("ATC_RAT_RLL_P").value(), 1e-6, "ArduPilot's own default is its 10\" start");
        assertEquals(0.6f, ten.get("MOT_THST_EXPO").value(), 1e-6);
        assertTrue(ArduTuning.gyroFilterHz(7) < 75 && ArduTuning.gyroFilterHz(7) > 42);
        Map<String, ArduPlan.Wanted> keep = ArduTuning.wanted(new ArduTuning.Choice(ArduTuning.Frame.BF_X_OUT, null, 4, 0));
        assertEquals(List.of("FRAME_CLASS", "FRAME_TYPE"), List.copyOf(keep.keySet()), "its tune is kept");
        assertEquals(18f, keep.get("FRAME_TYPE").value());
    }

    @Test
    void autotuneGoesOnTheChosenSwitchPositionAndSettingsKeepIt() {
        Map<String, ArduPlan.Wanted> w = ArduTuning.wanted(new ArduTuning.Choice(ArduTuning.Frame.BF_X, null, 4, 5));
        assertEquals((float) ArduTuning.MODE_AUTOTUNE, w.get("FLTMODE5").value());
        assertEquals(7f, w.get("AUTOTUNE_AXES").value());
        int[] modes = ArduTuning.modesWith(ArduPlan.DEFAULT_MODES, 5);
        assertEquals(ArduTuning.MODE_AUTOTUNE, modes[4]);
        assertEquals(ArduPlan.DEFAULT_MODES[0], modes[0]);
        assertNotEquals(ArduTuning.MODE_AUTOTUNE, ArduPlan.DEFAULT_MODES[4], "the defaults are not changed");
        ArduPlan.Choices c = ArduPlan.Choices.defaults(3);
        ArduPlan.Choices withTune = new ArduPlan.Choices(c.sysId(), c.cells(), c.capacityMah(), c.armAux(), c.modeAux(), modes,
                c.rtlAltM(), c.cruiseMs(), c.climbMs(), c.descentMs(), c.avoidance());
        assertEquals((float) ArduTuning.MODE_AUTOTUNE, ArduPlan.wanted(withTune).get("FLTMODE5").value(), "SETTINGS keeps it");
        assertTrue(ArduTuning.AUTOTUNE_HOWTO.length() < 300, "short");
    }

    @Test
    void portsAndTuningAreWrittenThroughTheOnePathWithBackupAndReadBack(@TempDir Path dir) throws Exception {
        FakeArduPilot fake = new FakeArduPilot();
        fake.params.putAll(board());
        fake.params.put("FRAME_CLASS", 0f);
        fake.params.put("FRAME_TYPE", 1f);
        fake.params.put("INS_GYRO_FILTER", 20f);
        fc = new MavSession(fake);
        assertTrue(fc.awaitHeartbeat(3000));
        Thread.sleep(150);
        Map<String, Float> now = fc.readAllParams(10_000);
        ArduPorts.Plan ports = ArduPorts.plan(choice(3, 0, false), now);
        Map<String, ArduPlan.Wanted> all = new java.util.LinkedHashMap<>(ports.wanted());
        all.putAll(ArduTuning.wanted(new ArduTuning.Choice(ArduTuning.Frame.BF_X, ArduTuning.Size.SEVEN, 4, 4)));
        List<ArduPlan.Change> plan = ArduPlan.changes(all, now);
        assertTrue(plan.stream().anyMatch(ArduPlan.Change::missing), "the fake lacks most tuning parameters: listed apart");
        ArduSetupWriter.Result r = new ArduSetupWriter(fc, s -> { }).write(plan, dir, LocalDateTime.of(2026, 11, 23, 18, 0, 0));
        assertTrue(r.ok(), r.failure());
        assertEquals(5f, fake.params.get("SERIAL3_PROTOCOL"));
        assertEquals(23f, fake.params.get("SERIAL2_PROTOCOL"), "the RC input port is untouched");
        assertEquals(1f, fake.params.get("FRAME_CLASS"));
        assertEquals(12f, fake.params.get("FRAME_TYPE"));
        assertEquals((float) ArduTuning.gyroFilterHz(7), fake.params.get("INS_GYRO_FILTER"));
        assertEquals((float) ArduTuning.MODE_AUTOTUNE, fake.params.get("FLTMODE4"));
        assertEquals(-1f, ParamFile.read(r.backup()).get("SERIAL3_PROTOCOL"), "the backup holds the port from before");
    }
}
