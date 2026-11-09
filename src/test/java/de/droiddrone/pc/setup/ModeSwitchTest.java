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
        // a slot that is filled again is still cleared first (1.0.49): all the clears, then the fills
        ModeSwitch.Plan q = ModeSwitch.DEFAULT.plan(aux("aux 1 2 1 1700 2100 0 0", "aux 2 1 1 900 1300 0 0"), ALL);
        assertEquals(List.of("aux 1 0 0 900 900 0 0", "aux 2 0 0 900 900 0 0", "aux 0 1 1 1300 1700 0 0", "aux 1 11 1 1700 2100 0 0"),
                q.commands());
    }

    @Test
    void aRefilledSlotIsAlwaysClearedFirst() {
        // ARM was on AUX2 in slot 0; ARM goes to AUX1 and the flight modes to AUX2. 1.0.48 sent only the fill for a slot
        // it refilled - a fill Betaflight didn't take left ARM there with the new channel (ARM on the mode switch)
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 1 1700 2100 0 0", "aux 1 0 1 900 1300 0 0"), ALL, java.util.Map.of(),
                ModeSwitch.DEFAULT, 0, 1);
        List<String> c = p.commands();
        for (String fill : c) {
            if (fill.contains(" 900 900")) continue;
            String slot = fill.split(" ")[1];
            int clear = c.indexOf("aux " + slot + " 0 0 900 900 0 0");
            if (clear < 0) continue; // a free slot: nothing to clear
            assertTrue(clear < c.indexOf(fill), "the clear before the fill: " + c);
        }
        assertTrue(c.contains("aux 0 0 0 900 900 0 0") && c.contains("aux 1 0 0 900 900 0 0"), c.toString());
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
    void sameModesOnOtherSwitchesAreTakenOff() {
        // the pilot's own GPS RESCUE switch on AUX4 stays while GPS rescue isn't chosen here (1.0.48 took it off)
        ModeSwitch.Plan p = ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 46 3 1700 2100 0 0"), ALL);
        assertEquals(List.of("GPS RESCUE on AUX4"), p.elsewhere());
        assertTrue(p.replaced().isEmpty());
        assertEquals(List.of("aux 2 1 1 1300 1700 0 0", "aux 3 11 1 1700 2100 0 0"), p.commands(), "slot 1 isn't touched");
        // GPS rescue chosen on the switch's top: then it is this plan's, and the one on AUX4 goes
        ModeSwitch rescueTop = new ModeSwitch(2, ModeSwitch.Mode.ACRO, ModeSwitch.Mode.ANGLE, ModeSwitch.Mode.GPS_RESCUE);
        assertEquals(List.of("GPS RESCUE on AUX4"), rescueTop.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 46 3 1700 2100 0 0"), ALL).replaced());
        // HORIZON is never placed here: left where the pilot has it
        assertEquals(List.of("HORIZON on AUX5"), ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 2 4 1700 2100 0 0"), ALL).elsewhere());
        // the pilot's case: ANGLE on AUX5 as well as on the chosen AUX2 - AUX5 is cleared
        ModeSwitch.Plan q = ModeSwitch.DEFAULT.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 1 1 1300 1700 0 0",
                "aux 2 11 1 1700 2100 0 0", "aux 3 1 4 1300 1700 0 0"), ALL);
        assertEquals(List.of("aux 3 0 0 900 900 0 0"), q.commands());
        assertEquals(List.of("ANGLE on AUX5"), q.replaced());
        assertEquals(List.of("ANGLE on AUX2, AUX5"), q.doubled(), "as read, before the write");
    }

    @Test
    void aSwitchLeftAsItIsKeepsThemAndDoublesAreReported() {
        String aux = aux("aux 0 0 0 1700 2100 0 0", "aux 1 1 1 1300 1700 0 0", "aux 3 1 4 1300 1700 0 0");
        ModeSwitch.Plan p = ModeSwitch.plan(aux, ALL, java.util.Map.of(), null, 0, 1, 0);
        assertTrue(p.commands().isEmpty(), "ARM is where it should be, the modes are the pilot's own");
        assertEquals(List.of("ANGLE on AUX2", "ANGLE on AUX5"), p.elsewhere());
        assertEquals(List.of("ANGLE on AUX2, AUX5"), p.doubled());
        assertTrue(ModeSwitch.doubled("aux 0 0 0 1700 2100 0 0\naux 1 13 1 1700 2100 0 0\naux 2 13 4 1700 2100 0 0").isEmpty(),
                "BEEPER on two switches is the pilot's business");
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
        assertTrue(BetaflightPlan.hasGps(BetaflightPlan.FEATURE_RX_MSP | BetaflightPlan.FEATURE_GPS));
        assertFalse(BetaflightPlan.hasGps(BetaflightPlan.FEATURE_RX_MSP | 1 << 18));
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
        assertEquals(List.of("aux 1 0 0 900 900 0 0", "aux 1 46 3 1700 2100 0 0"), p.commands(), "the beeper on AUX4 makes way");
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
        assertEquals(List.of("aux 0 0 0 900 900 0 0", "aux 0 0 0 1700 2100 0 0"), p.commands(), "the old slot cleared, then reused for the new ARM");
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
        assertEquals(List.of("aux 0 0 0 900 900 0 0", "aux 0 0 0 1700 2100 0 0", "aux 1 1 1 1300 1700 0 0", "aux 2 11 1 1700 2100 0 0"),
                p.commands());
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

    @Test
    void armHintShowsTheRealRangeWhenItIsntUp() throws Exception {
        de.droiddrone.pc.PcConfig config = de.droiddrone.pc.PcConfig.defaults();
        assertTrue(ModeSwitch.purposes(config)[0].hint().endsWith("(up)."), "nothing read yet: up");
        config.set(de.droiddrone.pc.ArmSwitch.KEY, "4:1700-2100"); // read from the board: AUX1, up
        assertTrue(ModeSwitch.purposes(config)[0].hint().endsWith("(up)."));
        config.set(de.droiddrone.pc.ArmSwitch.KEY, "4:900-1300"); // the plan keeps an ARM already on the switch: it arms low
        assertTrue(ModeSwitch.purposes(config)[0].hint().endsWith("(down - Betaflight arms at 900-1300)."), ModeSwitch.purposes(config)[0].hint());
        config.set(de.droiddrone.pc.ArmSwitch.KEY, "5:900-1300"); // on another switch than the one chosen: the chosen one is written up
        assertTrue(ModeSwitch.purposes(config)[0].hint().endsWith("(up)."));
        assertEquals("the middle - Betaflight arms at 1300-1700", ModeSwitch.armedPosition(new de.droiddrone.pc.ArmSwitch(4, List.of(new int[]{1300, 1700}))));
    }

    @Test
    void armOnSeveralSwitchesIsSeen() {
        assertEquals(Set.of(), ModeSwitch.armAuxes(aux()));
        assertEquals(Set.of(1), ModeSwitch.armAuxes(aux("aux 0 0 0 1700 2100 0 0")));
        assertEquals(Set.of(1, 3), ModeSwitch.armAuxes(aux("aux 0 0 0 1700 2100 0 0", "aux 5 0 2 1700 2100 0 0")));
        assertNull(de.droiddrone.pc.ArmSwitch.fromCli(aux("aux 0 0 0 1700 2100 0 0", "aux 5 0 2 1700 2100 0 0")), "not covered");
    }

    @Test
    void modesAreNamedAsConfiguratorDoesForTheManualWay() {
        assertEquals("POS HOLD", ModeSwitch.Mode.POS_HOLD.configuratorName);
        assertEquals("GPS RESCUE", ModeSwitch.Mode.GPS_RESCUE.configuratorName);
        assertEquals("ANGLE", ModeSwitch.Mode.ANGLE.configuratorName);
    }

    @Test
    void prearmSwitchDefaultsToAux3() throws Exception {
        assertEquals(3, ModeSwitch.parsePrearmAux(""));
        assertEquals(0, ModeSwitch.parsePrearmAux("off"));
        assertEquals(5, ModeSwitch.parsePrearmAux("5"));
        assertEquals("PREARM", ModeSwitch.purposes(de.droiddrone.pc.PcConfig.defaults())[2].title(), "the calibration names AUX3 after it");
    }

    @Test
    void prearmIsPutOnItsSwitchUpAndTakenOffOthers() {
        // ARM on AUX1 already; PREARM (36) on AUX5 before: moved to AUX3, up, so there is one prearm switch
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 4 36 4 1700 2100 0 0"), ALL, java.util.Map.of(), null, 0, 1, 3);
        assertNull(p.armProblem());
        assertNull(p.prearmProblem());
        assertEquals(List.of("aux 4 0 0 900 900 0 0", "aux 1 36 2 1700 2100 0 0"), p.commands(), "the old one cleared, the new PREARM in the lowest free slot");
        assertEquals(List.of("PREARM on AUX5"), p.replaced());
        // none before: written into a free slot; already there: nothing to do
        assertEquals(List.of("aux 1 36 2 1700 2100 0 0"), ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0"), ALL, java.util.Map.of(), null, 0, 1, 3).commands());
        assertTrue(ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 36 2 1700 2100 0 0"), ALL, java.util.Map.of(), null, 0, 1, 3).commands().isEmpty());
        // left as it is (0): PREARM elsewhere stays
        assertTrue(ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 4 36 4 1700 2100 0 0"), ALL, java.util.Map.of(), null, 0, 1, 0).commands().isEmpty());
        assertEquals(Set.of(5), ModeSwitch.prearmAuxes(aux("aux 4 36 4 1700 2100 0 0")));
    }

    @Test
    void theArmAndPrearmSwitchesCarryTheirModeAlone() {
        // the pilot: HORIZON (2) already on AUX3, PREARM put on AUX3 -> HORIZON comes off AUX3; AIRMODE (28) beside ARM on
        // AUX1 comes off too; ANGLE on AUX5 (not placed here) stays
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 28 0 1700 2100 0 0", "aux 2 2 2 1300 1700 0 0",
                "aux 3 1 4 1700 2100 0 0"), ALL, java.util.Map.of(), null, 0, 1, 3);
        assertNull(p.prearmProblem());
        assertEquals(List.of("aux 1 0 0 900 900 0 0", "aux 2 0 0 900 900 0 0", "aux 1 36 2 1700 2100 0 0"), p.commands());
        assertTrue(p.replaced().contains("HORIZON on AUX3"), p.replaced().toString());
        assertEquals(2, p.replaced().size(), "AIR MODE off AUX1, HORIZON off AUX3: " + p.replaced());
        // ARM and PREARM left as they are (0): nothing on their switches is touched
        assertTrue(ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 28 0 1700 2100 0 0", "aux 2 36 2 1700 2100 0 0",
                "aux 3 2 2 1300 1700 0 0"), ALL, java.util.Map.of(), null, 0, 0, 0).commands().isEmpty());
    }

    @Test
    void prearmOnTheArmModeOrRescueSwitchIsRefused() {
        assertTrue(ModeSwitch.plan(aux(), ALL, java.util.Map.of(), null, 0, 1, 1).prearmProblem().contains("ARM is on AUX1"));
        assertTrue(ModeSwitch.plan(aux(), ALL, java.util.Map.of(), ModeSwitch.DEFAULT, 0, 1, 2).prearmProblem().contains("flight mode switch"));
        assertTrue(ModeSwitch.plan(aux(), ALL, java.util.Map.of(), null, 4, 1, 4).prearmProblem().contains("GPS rescue switch"));
        // the flight modes and ARM are written all the same
        ModeSwitch.Plan p = ModeSwitch.plan(aux(), ALL, java.util.Map.of(), ModeSwitch.DEFAULT, 0, 1, 2);
        assertEquals(List.of("aux 0 0 0 1700 2100 0 0", "aux 1 1 1 1300 1700 0 0", "aux 2 11 1 1700 2100 0 0"), p.commands());
    }

    @Test
    void beeperGoesAloneOnAux14AndOffEveryOtherSwitch() {
        assertEquals(14, ModeSwitch.BEEPER_AUX, "the last channel MSP carries, almost never a radio switch (AUX12 before 1.0.49)");
        // BEEPER (13) on the radio's AUX4 and ANGLE on AUX14 before: both taken off, BEEPER up on AUX14 (channel 13)
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 13 3 1700 2100 0 0", "aux 2 1 13 900 1300 0 0"),
                ALL, java.util.Map.of(), null, 0, 1, 0, ModeSwitch.BEEPER_AUX);
        assertEquals(List.of("aux 1 0 0 900 900 0 0", "aux 2 0 0 900 900 0 0", "aux 1 13 13 1700 2100 0 0"), p.commands(),
                "both cleared, the old BEEPER slot filled again with the new one");
        assertEquals(List.of("BEEPER on AUX4", "ANGLE on AUX14"), p.replaced());
        assertNull(p.beeperProblem());
        // already so: nothing; with another range on AUX14 it is set to the button's
        assertTrue(ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 13 13 1700 2100 0 0"), ALL, java.util.Map.of(), null, 0, 1, 0,
                ModeSwitch.BEEPER_AUX).commands().isEmpty());
        assertEquals(List.of("aux 1 0 0 900 900 0 0", "aux 1 13 13 1700 2100 0 0"), ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0",
                "aux 1 13 13 900 1300 0 0"), ALL, java.util.Map.of(), null, 0, 1, 0, ModeSwitch.BEEPER_AUX).commands());
    }

    @Test
    void armLeftOnTheBeeperChannelBlocksBeeperAndAsksToMoveArm() {
        // ARM left as it is (0) on AUX14: BEEPER isn't put there, and the page says why
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 13 1700 2100 0 0"), ALL, java.util.Map.of(), null, 0, 0, 0, ModeSwitch.BEEPER_AUX);
        assertTrue(p.commands().isEmpty());
        assertTrue(p.beeperProblem().startsWith("ARM is on AUX14"), p.beeperProblem());
        assertTrue(p.beeperProblem().contains("Choose an ARM switch"));
        // an ARM switch chosen: ARM moves off AUX14 (cleared first), then BEEPER goes there
        ModeSwitch.Plan moved = ModeSwitch.plan(aux("aux 0 0 13 1700 2100 0 0"), ALL, java.util.Map.of(), null, 0, 1, 0, ModeSwitch.BEEPER_AUX);
        assertNull(moved.beeperProblem());
        assertEquals(List.of("aux 0 0 0 900 900 0 0", "aux 0 0 0 1700 2100 0 0", "aux 1 13 13 1700 2100 0 0"), moved.commands());
        // PREARM left on AUX14: the same
        ModeSwitch.Plan prearm = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 36 13 1700 2100 0 0"), ALL, java.util.Map.of(),
                null, 0, 1, 0, ModeSwitch.BEEPER_AUX);
        assertTrue(prearm.beeperProblem().startsWith("PREARM is on AUX14"), prearm.beeperProblem());
        assertTrue(prearm.commands().isEmpty(), "PREARM stays where the pilot has it");
    }

    @Test
    void noPlanEverPutsBeeperOrAModeBesideArm() {
        // every ARM position as read (none, AUX1-AUX14, low or high), every ARM / PREARM choice, with and without the
        // flight modes: once the commands are taken, BEEPER and every mode written is on a channel without ARM
        for (int armRead = -1; armRead < 14; armRead++) {
            for (int[] range : new int[][]{{1700, 2100}, {900, 1300}}) {
                String read = armRead < 0 ? aux() : aux("aux 0 0 " + armRead + " " + range[0] + " " + range[1] + " 0 0",
                        "aux 1 13 3 1700 2100 0 0", "aux 2 1 13 1300 1700 0 0");
                for (int armAux = 0; armAux <= ModeSwitch.MAX_AUX; armAux++) {
                    for (int prearmAux : new int[]{0, 3}) {
                        for (ModeSwitch modes : new ModeSwitch[]{null, ModeSwitch.DEFAULT}) {
                            ModeSwitch.Plan p = ModeSwitch.plan(read, ALL, java.util.Map.of(), modes, 4, armAux, prearmAux, ModeSwitch.BEEPER_AUX);
                            List<ModeSwitch.Slot> after = ModeSwitch.after(ModeSwitch.slots(read), p.commands());
                            String where = "ARM read on " + armRead + ", ARM " + armAux + ", PREARM " + prearmAux + ", modes " + modes + ": " + p.commands();
                            assertNull(ModeSwitch.sharedWithArm(after, p.commands()), where);
                            for (ModeSwitch.Slot s : after) {
                                if (!s.used() || s.mode() != ModeSwitch.BEEPER_MODE) continue;
                                for (ModeSwitch.Slot a : after) {
                                    assertFalse(a.used() && a.mode() == 0 && a.channel() == s.channel(), "BEEPER beside ARM - " + where);
                                }
                            }
                            if (armAux == 0 && armRead == 13) assertNotNull(p.beeperProblem(), where);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theLastCheckFindsAModePlacedBesideArm() {
        List<ModeSwitch.Slot> read = ModeSwitch.slots(aux("aux 0 0 13 1700 2100 0 0"));
        List<String> beeperThere = List.of("aux 1 13 13 1700 2100 0 0");
        assertEquals("ARM and BEEPER would share AUX14", ModeSwitch.sharedWithArm(ModeSwitch.after(read, beeperThere), beeperThere));
        List<String> modeThere = List.of("aux 1 1 13 1300 1700 0 0");
        assertNotNull(ModeSwitch.sharedWithArm(ModeSwitch.after(read, modeThere), modeThere));
        List<String> armCleared = List.of("aux 0 0 0 900 900 0 0", "aux 1 13 13 1700 2100 0 0");
        assertNull(ModeSwitch.sharedWithArm(ModeSwitch.after(read, armCleared), armCleared));
        // the pilot's own mode beside ARM, untouched, is theirs
        List<ModeSwitch.Slot> own = ModeSwitch.slots(aux("aux 0 0 0 1700 2100 0 0", "aux 1 35 0 1700 2100 0 0"));
        assertNull(ModeSwitch.sharedWithArm(own, List.of("aux 2 1 1 1300 1700 0 0")));
    }

    @Test
    void prearmLeftAsItIsKeepsItsSwitch() {
        // PREARM on the flight mode switch, PREARM left as it is: 1.0.48 took it off; now the mode switch is refused
        ModeSwitch.Plan p = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 36 1 1700 2100 0 0"), ALL, java.util.Map.of(),
                ModeSwitch.DEFAULT, 0, 1, 0);
        assertTrue(p.problem().startsWith("PREARM is on AUX2"), p.problem());
        assertFalse(p.commands().stream().anyMatch(c -> c.startsWith("aux 1 ")), "PREARM's slot isn't touched: " + p.commands());
        // on the rescue switch: the same for GPS rescue
        ModeSwitch.Plan r = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 36 3 1700 2100 0 0"), ALL, java.util.Map.of(), null, 4, 1, 0);
        assertTrue(r.rescueProblem().startsWith("PREARM is on AUX4"), r.rescueProblem());
        assertTrue(r.commands().isEmpty());
        // a PREARM switch chosen elsewhere: then PREARM moves, and the mode switch is free
        ModeSwitch.Plan moved = ModeSwitch.plan(aux("aux 0 0 0 1700 2100 0 0", "aux 1 36 1 1700 2100 0 0"), ALL, java.util.Map.of(),
                ModeSwitch.DEFAULT, 0, 1, 3);
        assertNull(moved.problem());
        assertEquals(List.of("PREARM on AUX2"), moved.replaced());
    }

    // ------------------------------------------------------------------ Betaflight's echo and read-back

    /** Betaflight's answer to an aux command in 4.4, 4.5 and 2025.12 (cliAux: the slot as it is now, seven numbers). */
    private static final String[] VERSIONS = {"4.4.3", "4.5.2", "2025.12.5"};

    @Test
    void anEchoAsSentIsFineInEveryVersion() {
        for (String v : VERSIONS) {
            assertNull(ModeSwitch.echoProblem("aux 3 1 1 1300 1700 0 0", "aux 3 1 1 1300 1700 0 0"), v);
            assertNull(ModeSwitch.echoProblem("aux 3 0 0 900 900 0 0", "aux 3 0 0 900 900 0 0"), v + ": a clear");
            // the typed line left in front of Betaflight's own: its own (the last) counts
            assertNull(ModeSwitch.echoProblem("aux 3 1 1 1300 1700 0 0", "aux 3 1 1 1300 1700 0 0\r\naux 3 1 1 1300 1700 0 0"), v);
            // a six-field command (before 4.1) against the seven-field echo
            assertNull(ModeSwitch.echoProblem("aux 3 1 1 1300 1700 0", "aux 3 1 1 1300 1700 0 0"), v);
            assertNull(ModeSwitch.echoProblem("set failsafe_procedure = DROP", "failsafe_procedure set to DROP"), v + ": not an aux command");
            assertNull(ModeSwitch.echoProblem("aux 3 1 1 1300 1700 0 0", ""), v + ": no echo - the read-back checks it");
        }
    }

    @Test
    void anEchoThatKeptTheOldModeIsSeenInEveryVersion() {
        // a mode id Betaflight doesn't take: it keeps the slot's old mode (ARM) with the new channel and range, no ###ERROR
        for (String v : VERSIONS) {
            String problem = ModeSwitch.echoProblem("aux 0 11 1 1700 2100 0 0", "aux 0 0 1 1700 2100 0 0");
            assertEquals("aux 0: sent GPS POSITION HOLD on AUX2 1700-2100, Betaflight has ARM on AUX2 1700-2100", problem, v);
            assertNotNull(ModeSwitch.echoProblem("aux 0 1 1 1300 1700 0 0", "aux 0 1 1 1300 1675 0 0"), v + ": another range");
            assertNotNull(ModeSwitch.echoProblem("aux 0 1 1 1300 1700 0 0", "aux 0 1 2 1300 1700 0 0"), v + ": another channel");
        }
    }

    @Test
    void theReadBackIsComparedSlotBySlot() {
        List<String> sent = List.of("aux 0 0 0 900 900 0 0", "aux 0 0 0 1700 2100 0 0", "aux 1 13 13 1700 2100 0 0");
        assertNull(ModeSwitch.readBackProblem(sent, aux("aux 0 0 0 1700 2100 0 0", "aux 1 13 13 1700 2100 0 0")),
                "the last command for a slot counts");
        assertNotNull(ModeSwitch.readBackProblem(sent, aux("aux 0 0 13 1700 2100 0 0", "aux 1 13 13 1700 2100 0 0")));
        assertNotNull(ModeSwitch.readBackProblem(sent, "# aux\n"), "a slot missing from the list");
    }

    /** The CLI's "aux" text for slots (what Betaflight would list after a write). */
    private static String listed(List<ModeSwitch.Slot> slots) {
        StringBuilder sb = new StringBuilder();
        for (ModeSwitch.Slot s : slots) sb.append(ModeSwitch.auxCommand(s.index(), s.mode(), s.channel(), s.start(), s.end(), 7)).append('\n');
        return sb.toString();
    }

    /**
     * Review 1.0.53: ARM moved off AUX3 to AUX1 while PREARM is put on AUX3 - the old ARM slot was cleared twice (as
     * "something else on the PREARM switch" and as "ARM on another switch") and counted free twice, so ARM and PREARM
     * were both written into that one slot: PREARM over ARM, and no ARM switch at all.
     */
    @Test
    void armMovedOffThePrearmSwitchKeepsBothInSlotsOfTheirOwn() {
        String before = aux("aux 0 0 2 1700 2100 0 0");
        ModeSwitch.Plan p = ModeSwitch.plan(before, ALL, java.util.Map.of(), null, 0, 1, 3);
        assertNull(p.armProblem());
        assertNull(p.prearmProblem());
        String after = listed(ModeSwitch.after(ModeSwitch.slots(before), p.commands()));
        assertEquals(Set.of(1), ModeSwitch.armAuxes(after), "ARM on AUX1 after the write: " + p.commands());
        assertEquals(Set.of(3), ModeSwitch.prearmAuxes(after), "PREARM on AUX3: " + p.commands());
        assertEquals(1, p.commands().stream().filter(c -> c.equals("aux 0 0 0 900 900 0 0")).count(), "cleared once: " + p.commands());
    }

    /** Every plan, whatever was read and chosen: each slot is written at most once after its clear, and ARM ends up where chosen. */
    @Test
    void noPlanWritesTwoModesIntoOneSlot() {
        for (int armRead = -1; armRead < 8; armRead++) {
            for (int prearmRead = -1; prearmRead < 8; prearmRead++) {
                List<String> lines = new java.util.ArrayList<>();
                if (armRead >= 0) lines.add("aux 0 0 " + armRead + " 1700 2100 0 0");
                if (prearmRead >= 0) lines.add("aux 1 36 " + prearmRead + " 1700 2100 0 0");
                lines.add("aux 2 2 2 1300 1700 0 0");
                String read = aux(lines.toArray(new String[0]));
                for (int armAux = 0; armAux <= ModeSwitch.MAX_AUX; armAux++) {
                    for (int prearmAux = 0; prearmAux <= ModeSwitch.MAX_AUX; prearmAux++) {
                        for (ModeSwitch modes : new ModeSwitch[]{null, ModeSwitch.DEFAULT}) {
                            ModeSwitch.Plan p = ModeSwitch.plan(read, ALL, java.util.Map.of(), modes, 4, armAux, prearmAux, ModeSwitch.BEEPER_AUX);
                            String where = "ARM read " + armRead + ", PREARM read " + prearmRead + ", ARM " + armAux + ", PREARM "
                                    + prearmAux + ", modes " + modes + ": " + p.commands();
                            java.util.Map<Integer, Integer> fills = new java.util.HashMap<>();
                            for (String c : p.commands()) {
                                ModeSwitch.Slot s = ModeSwitch.slots(c).get(0);
                                if (s.used()) assertNull(fills.put(s.index(), s.mode()), "slot " + s.index() + " filled twice - " + where);
                            }
                            String after = listed(ModeSwitch.after(ModeSwitch.slots(read), p.commands()));
                            if (armAux > 0 && p.armProblem() == null && p.problem() == null && !p.commands().isEmpty()) {
                                assertTrue(ModeSwitch.armAuxes(after).contains(armAux), "ARM on AUX" + armAux + " - " + where);
                            }
                        }
                    }
                }
            }
        }
    }
}
