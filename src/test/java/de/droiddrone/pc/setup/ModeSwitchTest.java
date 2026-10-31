package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModeSwitchTest {
    /** Betaflight's "aux" output: 20 slots, unused ones 900-900; these lines set, the rest empty. */
    private static String aux(String... used) {
        StringBuilder sb = new StringBuilder("# aux\n");
        for (int i = 0; i < 20; i++) {
            String line = "aux " + i + " 0 0 900 900 0 0";
            for (String u : used) if (u.startsWith("aux " + i + " ")) line = u;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static final Set<Integer> ALL = Set.of(0, 1, 2, 11, 46);

    @Test
    void defaultIsAux2AcroAngleAndPositionHold() {
        assertEquals(ModeSwitch.DEFAULT, ModeSwitch.parse(""));
        assertEquals(2, ModeSwitch.DEFAULT.aux());
        assertEquals("low Acro, middle Angle, high GPS position hold", ModeSwitch.DEFAULT.describe());
    }

    @Test
    void savedFormRoundTrips() {
        ModeSwitch m = new ModeSwitch(3, ModeSwitch.Mode.ANGLE, ModeSwitch.Mode.POS_HOLD, ModeSwitch.Mode.GPS_RESCUE);
        assertEquals(m, ModeSwitch.parse(m.format()));
        assertNull(ModeSwitch.parse("off"));
        assertEquals(ModeSwitch.DEFAULT, ModeSwitch.parse("garbage"));
    }

    @Test
    void writesTheDefaultIntoFreeSlotsKeepingArm() {
        ModeSwitch.Plan p = ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0"), ALL);
        assertNull(p.problem());
        assertEquals(List.of("aux 1 1 1 1300 1700 0 0", "aux 2 11 1 1700 2100 0 0"), p.commands());
    }

    @Test
    void alreadySetNeedsNothing() {
        ModeSwitch.Plan p = ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 4 11 1 1700 2100 0 0",
                "aux 7 1 1 1300 1700 0 0"), ALL);
        assertTrue(p.isSet());
    }

    @Test
    void replacesWhatElseIsOnThatSwitch() {
        // HORIZON and ANGLE on AUX2 from the pilot's earlier setup: cleared first, then the new ones in the lowest slots
        ModeSwitch.Plan p = ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 3 2 1 1700 2100 0 0",
                "aux 5 1 1 900 1300 0 0"), ALL);
        assertEquals(List.of("aux 3 0 0 900 900 0 0", "aux 5 0 0 900 900 0 0", "aux 1 1 1 1300 1700 0 0",
                "aux 2 11 1 1700 2100 0 0"), p.commands());
        // a cleared slot that is filled again is just overwritten
        ModeSwitch.Plan q = ModeSwitch.DEFAULT.plan(aux("aux 1 2 1 1700 2100 0 0", "aux 2 1 1 900 1300 0 0"), ALL);
        assertEquals(List.of("aux 2 0 0 900 900 0 0", "aux 0 1 1 1300 1700 0 0", "aux 1 11 1 1700 2100 0 0"), q.commands());
    }

    @Test
    void armOnTheChosenSwitchIsRefused() {
        ModeSwitch.Plan p = ModeSwitch.DEFAULT.plan(aux("aux 0 0 1 1700 2100 0 0"), ALL);
        assertTrue(p.problem().contains("ARM is on AUX2"));
        assertTrue(p.commands().isEmpty());
    }

    @Test
    void modesThisBetaflightLacksAreLeftOutAndReported() {
        // no GPS: neither POS HOLD (11) nor GPS RESCUE (46) is offered
        ModeSwitch.Plan p = ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0"), Set.of(0, 1, 2));
        assertEquals(List.of("aux 1 1 1 1300 1700 0 0"), p.commands());
        assertEquals(List.of(ModeSwitch.Mode.POS_HOLD), p.missing());
    }

    @Test
    void sameModesOnOtherSwitchesAreReportedNotTouched() {
        ModeSwitch.Plan p = ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 46 3 1700 2100 0 0"), ALL);
        assertEquals(List.of("GPS RESCUE on AUX4"), p.elsewhere());
        assertFalse(p.commands().stream().anyMatch(c -> c.startsWith("aux 1 ")));
    }

    @Test
    void olderBetaflightGetsItsOwnFieldCount() {
        String old = "aux 0 0 0 1700 2100 0\naux 1 0 0 900 900 0\naux 2 0 0 900 900 0\n";
        ModeSwitch.Plan p = ModeSwitch.DEFAULT.plan(old, null);
        assertEquals(List.of("aux 1 1 1 1300 1700 0", "aux 2 11 1 1700 2100 0"), p.commands());
    }

    @Test
    void fullModesTabIsReported() {
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < 20; i++) full.append("aux ").append(i).append(" 13 3 1700 2100 0 0\n");
        assertNotNull(ModeSwitch.DEFAULT.plan(full.toString(), ALL).problem());
    }

    @Test
    void gpsRescueCanGoOnAPosition() {
        ModeSwitch m = new ModeSwitch(2, ModeSwitch.Mode.ACRO, ModeSwitch.Mode.ANGLE, ModeSwitch.Mode.GPS_RESCUE);
        ModeSwitch.Plan p = m.plan(aux("aux 0 0 0 1700 2100 0 0"), ALL);
        assertEquals(List.of("aux 1 1 1 1300 1700 0 0", "aux 2 46 1 1700 2100 0 0"), p.commands());
    }

    @Test
    void gpsModesNeedANewEnoughBetaflight() {
        String bf45 = "Betaflight / STM32F7X2 (S7X2) 4.5.1 Oct 13 2024 / 12:00:00 (77d01ba3b) MSP API: 1.46";
        String bf43 = "Betaflight / STM32F405 (S405) 4.3.2 Jan  1 2023 / 12:00:00 (abc) MSP API: 1.44";
        String bf2025 = "Betaflight / STM32F7X2 (S7X2) 2025.12.0 Dec  1 2025 / 12:00:00 (abc) MSP API: 1.47";
        assertArrayEquals(new int[]{2025, 12}, BetaflightPlan.versionNumber(bf2025));
        assertNull(ModeSwitch.Mode.GPS_RESCUE.tooOld(bf45));
        assertTrue(ModeSwitch.Mode.GPS_RESCUE.tooOld(bf43).contains("needs Betaflight 4.4 or newer - this one is 4.3"));
        assertTrue(ModeSwitch.Mode.POS_HOLD.tooOld(bf45).contains("needs Betaflight 2025.12 or newer"));
        assertNull(ModeSwitch.Mode.POS_HOLD.tooOld(bf2025));
        assertNull(ModeSwitch.Mode.ANGLE.tooOld(bf43));
        assertNull(ModeSwitch.Mode.POS_HOLD.tooOld(""), "unknown version: not refused on a guess");
    }

    @Test
    void gpsModesOfTheSwitch() {
        assertEquals(List.of(ModeSwitch.Mode.POS_HOLD), ModeSwitch.DEFAULT.gpsModes());
        ModeSwitch m = new ModeSwitch(2, ModeSwitch.Mode.GPS_RESCUE, ModeSwitch.Mode.ANGLE, ModeSwitch.Mode.GPS_RESCUE);
        assertEquals(List.of(ModeSwitch.Mode.GPS_RESCUE), m.gpsModes());
    }

    @Test
    void blockedGpsModeIsLeftOutWithItsReason() {
        ModeSwitch.Plan p = ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0"), ALL,
                java.util.Map.of(ModeSwitch.Mode.POS_HOLD, "No GPS found"));
        assertEquals(List.of("aux 1 1 1 1300 1700 0 0"), p.commands());
        assertEquals("No GPS found", p.blocked().get(ModeSwitch.Mode.POS_HOLD));
        // once written, a blocked mode still on the switch from before is taken off: the switch flies acro there
        ModeSwitch.Plan q = ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 1 1 1300 1700 0 0",
                "aux 2 11 1 1700 2100 0 0"), ALL, java.util.Map.of(ModeSwitch.Mode.POS_HOLD, "No GPS found"));
        assertEquals(List.of("aux 2 0 0 900 900 0 0"), q.commands());
    }

    @Test
    void gpsPortAndFeatureFromTheCli() {
        String serial = "# serial\nserial 20 1 115200 57600 0 115200\nserial 0 64 115200 57600 0 115200\nserial 1 2 115200 57600 0 115200\n";
        assertTrue(BetaflightPlan.hasGpsPort(serial));
        assertFalse(BetaflightPlan.hasGpsPort("serial 20 1 115200 57600 0 115200\nserial 0 64 115200 57600 0 115200\n"));
        String features = "Enabled: RX_MSP OSD GPS AIRMODE\nDisabled: RX_SERIAL\n";
        assertTrue(BetaflightPlan.hasFeature(features, "GPS"));
        assertFalse(BetaflightPlan.hasFeature("Enabled: RX_MSP OSD\n", "GPS"));
    }

    @Test
    void failsafeChoiceAndBetaflightsValue() {
        assertEquals(ModeSwitch.Failsafe.KEEP, ModeSwitch.Failsafe.parse(""));
        assertEquals(ModeSwitch.Failsafe.GPS_RESCUE, ModeSwitch.Failsafe.parse("GPS_RESCUE"));
        assertEquals("GPS-RESCUE", ModeSwitch.Failsafe.GPS_RESCUE.value);
        assertEquals("DROP", ModeSwitch.Failsafe.current("failsafe_procedure = DROP\nAllowed values: AUTO-LAND, DROP, GPS-RESCUE"));
        assertEquals("GPS-RESCUE", ModeSwitch.Failsafe.current("# get failsafe_procedure\nfailsafe_procedure = GPS-RESCUE"));
        assertEquals("", ModeSwitch.Failsafe.current("Invalid name"));
    }

    @Test
    void rescueSwitchSettingDefaultsToAux4() {
        assertEquals(4, ModeSwitch.parseRescueAux(""));
        assertEquals(0, ModeSwitch.parseRescueAux("off"));
        assertEquals(5, ModeSwitch.parseRescueAux("5"));
        assertEquals(4, ModeSwitch.parseRescueAux("99"));
    }

    @Test
    void rescueGoesUpOnAux4BesidesTheModeSwitch() {
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0"), ALL, java.util.Map.of(), ModeSwitch.DEFAULT, 4);
        assertNull(p.problem());
        assertNull(p.rescueProblem());
        // the two switches share the free slots: no slot used twice
        assertEquals(List.of("aux 1 1 1 1300 1700 0 0", "aux 2 11 1 1700 2100 0 0", "aux 3 46 3 1700 2100 0 0"), p.commands());
        ModeSwitch.Plan done = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 1 1 1300 1700 0 0",
                "aux 2 11 1 1700 2100 0 0", "aux 3 46 3 1700 2100 0 0"), ALL, java.util.Map.of(), ModeSwitch.DEFAULT, 4);
        assertTrue(done.commands().isEmpty());
        assertTrue(done.elsewhere().isEmpty(), "rescue on its own switch isn't 'elsewhere'");
    }

    @Test
    void rescueAloneWithTheModeSwitchLeftAsItIs() {
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 13 3 1700 2100 0 0"), ALL,
                java.util.Map.of(), null, 4);
        assertEquals(List.of("aux 1 46 3 1700 2100 0 0"), p.commands(), "the beeper on AUX4 makes way");
        assertEquals(List.of("BEEPER on AUX4"), p.replaced());
    }

    @Test
    void rescueSwitchConflictsAreRefused() {
        ModeSwitch sameSwitch = new ModeSwitch(4, ModeSwitch.Mode.ACRO, ModeSwitch.Mode.ANGLE, ModeSwitch.Mode.POS_HOLD);
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0"), ALL, java.util.Map.of(), sameSwitch, 4);
        assertTrue(p.rescueProblem().contains("is the flight mode switch"));
        assertNull(p.problem(), "the mode switch still goes on");
        ModeSwitch.Plan arm = ModeSwitch.plan(aux("aux 0 0 3 1700 2100 0 0"), ALL, java.util.Map.of(), ModeSwitch.DEFAULT, 4);
        assertTrue(arm.rescueProblem().contains("ARM is on AUX4"));
        assertFalse(arm.commands().stream().anyMatch(c -> c.contains(" 46 ")));
    }

    @Test
    void blockedRescueLeavesItsSwitchAlone() {
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 13 3 1700 2100 0 0"), ALL,
                java.util.Map.of(ModeSwitch.Mode.GPS_RESCUE, "No GPS found"), null, 4);
        assertEquals("No GPS found", p.rescueProblem());
        assertTrue(p.commands().isEmpty(), "the beeper stays while rescue can't go on");
    }

    @Test
    void armSwitchDefaultsToAux1() {
        assertEquals(1, ModeSwitch.parseArmAux(""));
        assertEquals(0, ModeSwitch.parseArmAux("off"));
        assertEquals(3, ModeSwitch.parseArmAux("3"));
    }

    @Test
    void armIsPutOnItsSwitchAndTakenOffOthers() {
        // ARM on AUX3 before: moved to AUX1 (up), so there is one arm switch
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 2 1700 2100 0 0"), ALL, java.util.Map.of(), null, 0, 1);
        assertNull(p.armProblem());
        assertEquals(List.of("aux 0 0 0 1700 2100 0 0"), p.commands(), "the old slot reused for the new ARM");
        assertEquals(List.of("ARM on AUX3"), p.replaced());
    }

    @Test
    void armAlreadyOnTheSwitchKeepsItsOwnRange() {
        // armed low on AUX1: the pilot's switch, left as it is
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 900 1300 0 0"), ALL, java.util.Map.of(), null, 0, 1);
        assertTrue(p.commands().isEmpty());
    }

    @Test
    void armMovedOffTheModeSwitchFreesIt() {
        // ARM was on AUX2; choosing AUX1 for ARM lets the flight modes go on AUX2
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 1 1700 2100 0 0"), ALL, java.util.Map.of(), ModeSwitch.DEFAULT, 0, 1);
        assertNull(p.problem());
        assertNull(p.armProblem());
        assertEquals(List.of("aux 0 0 0 1700 2100 0 0", "aux 1 1 1 1300 1700 0 0", "aux 2 11 1 1700 2100 0 0"), p.commands());
    }

    @Test
    void armOnTheModeOrRescueSwitchIsRefused() {
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0"), ALL, java.util.Map.of(), ModeSwitch.DEFAULT, 4, 2);
        assertTrue(p.armProblem().contains("flight mode switch"));
        ModeSwitch.Plan q = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0"), ALL, java.util.Map.of(), null, 4, 4);
        assertTrue(q.armProblem().contains("GPS rescue switch"));
    }

    @Test
    void eachSwitchIsNamedForTheCalibration() throws Exception {
        de.droiddrone.pc.PcConfig config = de.droiddrone.pc.PcConfig.defaults();
        ModeSwitch.Purpose[] p = ModeSwitch.purposes(config);
        assertEquals("ARM", p[0].title());
        assertEquals("flight modes", p[1].title());
        assertTrue(p[1].hint().contains("top (GPS position hold)"));
        assertNull(p[3], "GPS rescue only once it is the failsafe");
        config.set(ModeSwitch.Failsafe.KEY, "GPS_RESCUE");
        assertEquals("GPS rescue", ModeSwitch.purposes(config)[3].title());
        config.set(ModeSwitch.Failsafe.KEY, "KEEP");
        config.set(ModeSwitch.RESCUE_FITTED_KEY, "true");
        assertEquals("GPS rescue", ModeSwitch.purposes(config)[3].title(), "fitted by Drone setup already");
    }
}
