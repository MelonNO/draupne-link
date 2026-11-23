package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F-086 on Drone setup's page: the flight mode lists' hint says why a mode is not offered on this board, and the
 * row says in short words what a position keeps, or that it has no mode (red: never "set").
 */
class DroneSetupModesTest {
    private static final String BF44 = "Betaflight / STM32F405 (S405) 4.4.1 Apr 11 2023 / 01:20:48 (abc) MSP API: 1.45";
    private static final String BF2025 = "Betaflight / STM32F7X2 (S7X2) 2025.12.0 Dec  1 2025 / 12:00:00 (abc) MSP API: 1.47";

    @Test
    void theListsHintSaysWhyPositionHoldIsNotOffered() {
        assertEquals("Mode in this position. GPS position hold needs Betaflight 2025.12 or newer - this one is 4.4.",
                DroneSetup.BetaflightPage.positionTip(BF44));
        assertEquals("Mode in this position", DroneSetup.BetaflightPage.positionTip(BF2025));
        assertEquals("Mode in this position", DroneSetup.BetaflightPage.positionTip(""), "not read yet: everything offered");
    }

    @Test
    void theRowSaysWhatAPositionKeepsAndWhichHasNoMode() {
        assertEquals("<br>High keeps ANGLE: GPS position hold needs Betaflight 2025.12 or newer - this one is 4.4.",
                DroneSetup.BetaflightPage.keptLines(List.of("high keeps ANGLE: GPS position hold needs Betaflight 2025.12 or newer - this one is 4.4.")));
        assertEquals("", DroneSetup.BetaflightPage.keptLines(List.of()));
        assertEquals("<b>High has no mode</b>: No GPS found - see the GPS row.<br>Choose another mode there.",
                DroneSetup.BetaflightPage.positionsWithoutMode(List.of("high has no mode: No GPS found - see the GPS row.")));
    }
}
