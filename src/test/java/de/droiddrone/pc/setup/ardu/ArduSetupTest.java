package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AP-7 / area H: Drone setup for an ArduPilot drone against a made-up ArduPilot ({@link FakeArduPilot}) -
 * the whole list read (missing ones asked again), the backup before any write, the plan, the read-back, the refusals
 * while armed, RESTORE, and the bench tools. Not tried on a real flight controller.
 */
class ArduSetupTest {
    private FakeArduPilot fake;
    private MavSession fc;

    private MavSession open() throws Exception {
        fake = new FakeArduPilot();
        fc = new MavSession(fake);
        assertTrue(fc.awaitHeartbeat(3000), "the FC's heartbeat");
        Thread.sleep(150); // a second heartbeat: armed is known
        return fc;
    }

    @AfterEach
    void close() {
        if (fc != null) fc.close();
    }

    @Test
    void theWholeListIsReadAlsoWhenAnswersAreLost() throws Exception {
        open();
        fake.dropEvery = 7;
        Map<String, Float> all = fc.readAllParams(10_000);
        assertEquals(fake.params.size(), all.size(), "every parameter, the lost ones asked again by index");
        assertEquals(1500f, all.get("RTL_ALT"));
        assertEquals(List.copyOf(fake.params.keySet()), List.copyOf(all.keySet()), "in the FC's own order");
    }

    @Test
    void thePlanHasOnlyWhatChangesWithItsReasonAndKeepsMissingOnesApart() throws Exception {
        open();
        ArduPlan.Choices c = ArduPlan.Choices.defaults(3);
        List<ArduPlan.Change> plan = ArduPlan.plan(c, fc.readAllParams(10_000));
        assertTrue(plan.stream().anyMatch(x -> x.name().equals("SYSID_THISMAV") && x.to() == 3f));
        assertTrue(plan.stream().anyMatch(x -> x.name().equals("TERRAIN_ENABLE") && x.to() == 0f), "RT-10");
        assertTrue(plan.stream().anyMatch(x -> x.name().equals("BATT_FS_CRT_ACT") && x.to() == 1f), "LAND at 3 %");
        assertTrue(plan.stream().anyMatch(x -> x.name().equals("BATT_CRT_VOLT") && Math.abs(x.to() - 12.0f) < 1e-3), "3.0 V x 4 cells");
        assertTrue(plan.stream().anyMatch(x -> x.name().equals("BATT_FS_LOW_ACT") && x.to() == 0f), "never home for battery");
        assertTrue(plan.stream().anyMatch(x -> x.name().equals("RC5_OPTION") && x.to() == ArduPlan.RC_OPTION_ARM_DISARM), "ARM on AUX1 = ch5");
        assertTrue(plan.stream().anyMatch(x -> x.name().equals("FLTMODE_CH") && x.to() == 6f), "mode switch on AUX2 = ch6");
        assertTrue(plan.stream().noneMatch(x -> x.name().equals("BATT_ARM_VOLT")), "already 0: not in the plan");
        ArduPlan.Change adsb = plan.stream().filter(x -> x.name().equals("ADSB_TYPE")).findFirst().orElseThrow();
        assertTrue(adsb.missing());
        List<String> lines = ArduPlan.lines(plan);
        assertTrue(lines.get(0).startsWith("NOT SET: ") && lines.get(1).startsWith("NOT SET: ADSB_TYPE"), "what nothing stands for: said first " + lines);
        assertTrue(lines.get(lines.size() - 1).contains("this firmware has"), "the other version's names last " + lines);
        assertTrue(lines.contains("RTL_ALT_M - this firmware has RTL_ALT, left out"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("RTL_ALT 1500 -> 3000")), lines.toString());
    }

    @Test
    void writingBacksUpFirstWritesAndReadsBackEachAndStopsAtAMismatch(@TempDir Path dir) throws Exception {
        open();
        List<String> log = new CopyOnWriteArrayList<>();
        ArduSetupWriter w = new ArduSetupWriter(fc, log::add);
        List<ArduPlan.Change> plan = ArduPlan.plan(ArduPlan.Choices.defaults(3), fc.readAllParams(10_000));
        ArduSetupWriter.Result r = w.write(plan, dir, LocalDateTime.of(2026, 11, 23, 14, 5, 31));
        assertTrue(r.ok(), r.failure());
        assertTrue(Files.isRegularFile(r.backup()));
        Map<String, Float> backup = ParamFile.read(r.backup());
        assertEquals(1500f, backup.get("RTL_ALT"), "the backup holds the values from BEFORE");
        assertEquals(1f, backup.get("SYSID_THISMAV"));
        assertEquals(3f, fake.params.get("SYSID_THISMAV"));
        assertEquals(0f, fake.params.get("TERRAIN_ENABLE"));
        assertFalse(r.written().contains("ADSB_TYPE"), "a parameter the firmware lacks is never written");
        assertTrue(ArduPlan.plan(ArduPlan.Choices.defaults(3), fc.readAllParams(10_000)).stream().allMatch(ArduPlan.Change::missing),
                "written as planned: nothing left to change");

        // a value the FC doesn't take: the write stops there
        fake.stuck = "RTL_ALT";
        ArduPlan.Choices higher = new ArduPlan.Choices(3, 4, 3000, 1, 2, ArduPlan.DEFAULT_MODES.clone(), 50, 10, 2.5, 1.5, true);
        ArduSetupWriter.Result r2 = w.write(ArduPlan.plan(higher, fc.readAllParams(10_000)), dir, LocalDateTime.of(2026, 11, 23, 14, 6, 0));
        assertFalse(r2.ok());
        assertTrue(r2.failure().startsWith("RTL_ALT reads back 3000"), r2.failure());

        // RESTORE the first backup: the values from before come back
        fake.stuck = null;
        ArduSetupWriter.Result back = w.restore(r.backup());
        assertTrue(back.ok(), back.failure());
        assertEquals(1f, fake.params.get("SYSID_THISMAV"));
        assertEquals(1f, fake.params.get("TERRAIN_ENABLE"));
        assertEquals(1500f, fake.params.get("RTL_ALT"));
    }

    @Test
    void nothingIsWrittenOrTestedWhileArmed(@TempDir Path dir) throws Exception {
        open();
        fake.armed = true;
        Thread.sleep(300);
        assertTrue(fc.armed());
        ArduSetupWriter.Result r = new ArduSetupWriter(fc, null).write(
                List.of(new ArduPlan.Change("RTL_ALT", 1500, 3000, "x")), dir, LocalDateTime.now());
        assertFalse(r.ok());
        assertNull(r.backup());
        assertEquals(1500f, fake.params.get("RTL_ALT"));
        ArduTools tools = new ArduTools(fc);
        assertThrows(java.io.IOException.class, () -> tools.motorTest(1, 2, 1));
        assertThrows(java.io.IOException.class, tools::levelTrim);
        assertFalse(fake.commands.contains(ArduTools.MAV_CMD_DO_MOTOR_TEST));
    }

    @Test
    void aSessionWithoutAHeartbeatCountsAsArmed() {
        MavSession silent = new MavSession(new MavSession.Wire() {
            public void write(byte[] d) {
            }

            public int read(byte[] b, int t) throws java.io.IOException {
                try {
                    Thread.sleep(t);
                } catch (InterruptedException e) {
                    return 0;
                }
                return 0;
            }

            public void close() {
            }
        });
        try {
            assertTrue(silent.armed(), "nothing known: treated as armed, nothing written");
        } finally {
            silent.close();
        }
    }

    @Test
    void theAccelerometerCalibrationGoesThroughItsSixPositions() throws Exception {
        open();
        ArduTools tools = new ArduTools(fc);
        List<ArduTools.Position> asked = new CopyOnWriteArrayList<>();
        CountDownLatch over = new CountDownLatch(1);
        boolean[] good = {false};
        ArduTools.AccelCal[] cal = new ArduTools.AccelCal[1];
        cal[0] = tools.startAccelCal(p -> {
            asked.add(p);
            new Thread(() -> { // the pilot turns the drone, then presses DONE
                try {
                    Thread.sleep(20);
                    while (cal[0] == null) Thread.sleep(5); // the first question may beat startAccelCal's return
                    cal[0].placed(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).start();
        }, ok -> {
            good[0] = ok;
            over.countDown();
        });
        assertTrue(over.await(5, TimeUnit.SECONDS), "finished");
        assertTrue(good[0]);
        assertEquals(List.of(ArduTools.Position.values()), asked, "level, left, right, nose down, nose up, back");
        assertThrows(java.io.IOException.class, () -> cal[0].placed(ArduTools.Position.BACK.equals(cal[0].asked())
                ? ArduTools.Position.LEVEL : ArduTools.Position.BACK), "only the position asked for");
    }

    @Test
    void theMotorTestIsCappedAndTimed() throws Exception {
        open();
        ArduTools tools = new ArduTools(fc);
        assertEquals(0, tools.motorTest(3, 40, 10));
        float[] p = fake.commandParams.get(fake.commands.lastIndexOf(ArduTools.MAV_CMD_DO_MOTOR_TEST));
        assertEquals(3f, p[0], "motor 3");
        assertEquals(15f, p[2], "never more than 15 % on the bench");
        assertEquals(3f, p[3], "never longer than 3 s");
        assertThrows(java.io.IOException.class, () -> tools.motorTest(0, 2, 1));
        fake.results.put(ArduTools.MAV_CMD_DO_MOTOR_TEST, 4);
        assertEquals(4, tools.motorTest(1, 2, 1), "the FC's refusal comes back");
    }

    @Test
    void paramFilesReadWhatMissionPlannerWrites(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("x.param");
        Files.write(f, List.of("# a comment", "RTL_ALT,3000", "BATT_CRT_VOLT 12.6", "AHRS_TRIM_X\t0.0123", "bad line", "lower_case,1", ""));
        Map<String, Float> m = ParamFile.read(f);
        assertEquals(Map.of("RTL_ALT", 3000f, "BATT_CRT_VOLT", 12.6f, "AHRS_TRIM_X", 0.0123f), m);
        Path g = dir.resolve("y.param");
        ParamFile.write(g, new java.util.LinkedHashMap<>(m), "header");
        assertEquals(m, ParamFile.read(g));
        assertTrue(Files.readAllLines(g).get(0).startsWith("# header"));
        assertEquals("ArduPilot sysid 3 2026-11-23 14-05-31.param",
                ParamFile.backupName(dir, 3, LocalDateTime.of(2026, 11, 23, 14, 5, 31)).getFileName().toString());
    }
}
