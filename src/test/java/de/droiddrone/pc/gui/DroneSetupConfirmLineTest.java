package de.droiddrone.pc.gui;

import de.droiddrone.pc.setup.BetaflightPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Q-003, the page's side: CONFIRM in Drone setup also writes settings no row shows (the battery warning voltages,
 * the beepers, arming without a GPS fix). One line above CONFIRM says so - while CONFIRM is there to be pressed, and
 * only for what the plan just read would write (SetupPlannerTest has the plans; here the plan is given by its
 * commands, as the page gets it).
 */
class DroneSetupConfirmLineTest {
    private static final List<BetaflightPlan.Change> PLAN = List.of(
            new BetaflightPlan.Change("Serial rate", List.of("set serial_update_rate_hz = 1000")),
            new BetaflightPlan.Change("Battery", List.of("set vbat_min_cell_voltage = 280", "set vbat_warning_cell_voltage = 320")),
            new BetaflightPlan.Change("Beepers", List.of("beeper -ARMING", "beacon RX_LOST")),
            new BetaflightPlan.Change("Arming", List.of("set gps_rescue_allow_arming_without_fix = ON")));

    @Test
    void theLineIsThereWhileConfirmIsAndGoneWithIt() {
        assertEquals("Also sets: battery warnings, beepers, arm without GPS fix", DroneSetup.BetaflightPage.alsoSets(PLAN, true));
        assertEquals("", DroneSetup.BetaflightPage.alsoSets(PLAN, false), "CONFIRM pressed, or the page confirmed: no line");
    }

    @Test
    void itNamesOnlyWhatThePlanWrites() {
        assertEquals("Also sets: beepers", DroneSetup.BetaflightPage.alsoSets(PLAN.subList(2, 3), true));
        assertEquals("Also sets: battery warnings, arm without GPS fix", DroneSetup.BetaflightPage.alsoSets(List.of(PLAN.get(1), PLAN.get(3)), true));
        // one of the two voltages is enough for the battery warnings; a beacon alone is the beepers
        assertEquals("Also sets: battery warnings, beepers", DroneSetup.BetaflightPage.alsoSets(List.of(
                new BetaflightPlan.Change("Battery", List.of("set vbat_warning_cell_voltage = 32")),
                new BetaflightPlan.Change("Beepers", List.of("beacon RX_SET"))), true));
        assertEquals("", DroneSetup.BetaflightPage.alsoSets(PLAN.subList(0, 1), true), "none of them in the plan: no line");
        assertEquals("", DroneSetup.BetaflightPage.alsoSets(List.of(), true));
    }
}
