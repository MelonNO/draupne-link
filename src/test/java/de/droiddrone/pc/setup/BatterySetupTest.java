package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The field round of 2026-11-18, Li-ion packs: "lower the low battery warning to be empty at 2,8 and low at 3,2". */
class BatterySetupTest {
    private static String get(String name, int value) {
        return name + " = " + value + "\r\nAllowed range: 100 - 500\r\nDefault value: " + value + "\r\n";
    }

    @Test
    void betaflightsDefaultsAreBroughtDownToThePilotsLimits() {
        BetaflightPlan.Change c = BatterySetup.plan(get("vbat_warning_cell_voltage", 350), get("vbat_min_cell_voltage", 330));
        assertNotNull(c);
        assertEquals(List.of("set vbat_min_cell_voltage = 280", "set vbat_warning_cell_voltage = 320"), c.commands());
        assertTrue(c.description().contains("3.2 V") && c.description().contains("2.8 V"), c.description());
    }

    @Test
    void aDroneAlreadySetUpNeedsNothing() {
        assertNull(BatterySetup.plan(get("vbat_warning_cell_voltage", 320), get("vbat_min_cell_voltage", 280)));
        BetaflightPlan.Change onlyOne = BatterySetup.plan(get("vbat_warning_cell_voltage", 320), get("vbat_min_cell_voltage", 330));
        assertEquals(List.of("set vbat_min_cell_voltage = 280"), onlyOne.commands());
    }

    @Test
    void anOlderBetaflightsTenthsOfAVoltAreWrittenInItsUnit() {
        BetaflightPlan.Change c = BatterySetup.plan("vbat_warning_cell_voltage = 35\r\n", "vbat_min_cell_voltage = 33\r\n");
        assertEquals(List.of("set vbat_min_cell_voltage = 28", "set vbat_warning_cell_voltage = 32"), c.commands());
    }

    @Test
    void aReplyNotUnderstoodChangesNothing() {
        assertNull(BatterySetup.plan("Invalid name\r\n", get("vbat_min_cell_voltage", 330)));
        assertNull(BatterySetup.plan(null, null));
        assertNull(BatterySetup.plan("", ""));
        assertEquals(-1, BatterySetup.value("vbat_min_cell_voltage = x", "vbat_min_cell_voltage"));
    }
}
