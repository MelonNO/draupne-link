package de.droiddrone.pc.gui;

import de.droiddrone.pc.setup.ModeSwitch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-088: BY HAND named "set force_battery_cell_count = 6" while the page showed, and kept, the 4S Betaflight forces: its
 * words were made before the board was read. They are made from the page as it is when BY HAND is pressed, with the
 * count the page writes (the made-up board's whole round: bench/hw/HwDroneSetupSelfTest).
 */
class DroneSetupByHandTest {
    @Test
    void byHandNamesTheCountThePageWrites() {
        ModeSwitch angleTop = new ModeSwitch(2, ModeSwitch.Mode.ACRO, ModeSwitch.Mode.ANGLE, ModeSwitch.Mode.ANGLE);
        String four = DroneSetup.BetaflightPage.manual(4, true, 3, true, 1, 3, angleTop, ModeSwitch.Failsafe.GPS_RESCUE, 4);
        assertTrue(four.contains("<b>set force_battery_cell_count = 4</b>"), four);
        assertFalse(four.contains("force_battery_cell_count = 6"), four);
        assertTrue(four.contains("GPS</b> on UART3") && four.contains("<b>Magnetometer</b> on"), four);
        assertTrue(four.contains("on AUX2, <b>ANGLE</b> 1300-1700, <b>ANGLE</b> 1700-2100"), "the switch as the page writes it: " + four);
        assertFalse(four.contains("POS HOLD"), four);
        assertTrue(four.contains("<b>GPS RESCUE</b> on AUX4"), four);
        String noGps = DroneSetup.BetaflightPage.manual(6, false, 3, true, 1, 3, null, ModeSwitch.Failsafe.KEEP, 4);
        assertTrue(noGps.contains("force_battery_cell_count = 6") && !noGps.contains("Ports</b> tab") && !noGps.contains("GPS RESCUE"), noGps);
    }
}
