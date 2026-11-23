package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-087: Drone setup said "Betaflight set up - unplug the drone." while its GPS rescue row said "No GPS found": once
 * CONFIRM had written everything, the rows were taken as still to be written, and a row not as chosen no longer kept
 * the page from done. Now only a page that nothing has been written for is "to write", and a done page names the rows
 * that are not set, in red (the made-up board's whole round: bench/hw/HwDroneSetupSelfTest).
 */
class DroneSetupDoneTest {
    @Test
    void onlyAPageNothingWasWrittenForIsStillToWrite() {
        assertTrue(DroneSetup.BetaflightPage.pendingWrite(false, false), "before CONFIRM: the rows say what it would set");
        assertFalse(DroneSetup.BetaflightPage.pendingWrite(true, false), "CONFIRM pressed, its rounds write");
        assertFalse(DroneSetup.BetaflightPage.pendingWrite(false, true), "CONFIRM wrote it all and is spent: a row not as chosen is not set");
    }

    @Test
    void theStatusNamesTheRowsThatAreNotSet() {
        assertEquals("Not set: GPS, GPS rescue.", DroneSetup.BetaflightPage.notSetStatus(List.of("GPS", "GPS rescue")));
        assertEquals("Not set: Flight modes.", DroneSetup.BetaflightPage.notSetStatus(List.of("Flight modes")));
        assertEquals("Not set - see the rows.", DroneSetup.BetaflightPage.notSetStatus(List.of()));
    }
}
