package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BetaflightTest {
    // shapes of Betaflight 4.5 CLI output
    static final String RATE_DEFAULT = "serial_update_rate_hz = 100\r\nAllowed range: 100 - 2000\r\nDefault value: 100\r\n";
    static final String RATE_SET = "serial_update_rate_hz = 2000";
    static final String F7 = "Betaflight / STM32F7X2 (S7X2) 4.5.1 Oct 13 2024 / 12:00:00 (77d01ba3b) MSP API: 1.46";
    static final String F411 = "Betaflight / STM32F411 (S411) 4.4.3 Nov 14 2023 / 12:00:00 (738127e7e) MSP API: 1.45";
    // feature masks (MSP_FEATURE_CONFIG): a stock board with a serial receiver, and one set up for the phone
    static final int MASK_SERIAL_RX = BetaflightPlan.FEATURE_RX_SERIAL | BetaflightPlan.FEATURE_GPS | 1 << 18 /* OSD */ | 1 << 22 /* AIRMODE */;
    static final int MASK_MSP_RX = BetaflightPlan.FEATURE_RX_MSP | BetaflightPlan.FEATURE_GPS | 1 << 18 | 1 << 22;

    @Test
    void receiverIsReadAndSwitchedByTheFeatureMask() {
        // over MSP, as Betaflight Configurator does: Betaflight 2025.12's CLI has no name for RX_MSP
        assertFalse(BetaflightPlan.isMspReceiver(MASK_SERIAL_RX));
        assertTrue(BetaflightPlan.isMspReceiver(MASK_MSP_RX));
        assertFalse(BetaflightPlan.isMspReceiver(MASK_MSP_RX | BetaflightPlan.FEATURE_RX_SERIAL), "Betaflight takes serial before MSP");
        assertTrue(BetaflightPlan.isMspReceiver(MASK_MSP_RX | BetaflightPlan.FEATURE_RX_SPI), "SPI comes after MSP");
        assertEquals(MASK_MSP_RX, BetaflightPlan.mspReceiverMask(MASK_SERIAL_RX), "RX_SERIAL off, RX_MSP on, the rest kept");
        assertEquals(MASK_MSP_RX, BetaflightPlan.mspReceiverMask(MASK_SERIAL_RX | BetaflightPlan.FEATURE_RX_PPM
                | BetaflightPlan.FEATURE_RX_SPI | BetaflightPlan.FEATURE_RX_PARALLEL_PWM), "every other receiver off");
        assertEquals(MASK_MSP_RX, BetaflightPlan.mspReceiverMask(MASK_MSP_RX), "already right: unchanged");
        assertEquals("RX_SERIAL", BetaflightPlan.receiverNames(MASK_SERIAL_RX));
        assertEquals("RX_SERIAL + RX_MSP", BetaflightPlan.receiverNames(MASK_SERIAL_RX | BetaflightPlan.FEATURE_RX_MSP));
        assertEquals("no receiver", BetaflightPlan.receiverNames(1 << 18));
        assertTrue(BetaflightPlan.hasGps(MASK_MSP_RX));
        assertFalse(BetaflightPlan.hasGps(1 << 18));
    }

    @Test
    void backupGetsTheReceiverLinesFromBeforeTheSwitch() {
        // "diff all" after the switch over MSP, on 4.5 (names both): RX_SERIAL is default-on, RX_MSP never
        String after = "# feature\r\nfeature -RX_SERIAL\r\nfeature GPS\r\nfeature RX_MSP\r\n\r\n# serial\r\nserial 0 64 115200 57600 0 115200\r\n";
        assertEquals("# feature\nfeature GPS\n\n# serial\nserial 0 64 115200 57600 0 115200\n",
                BetaflightPlan.restoreReceiver(after, MASK_SERIAL_RX));
        // on 2025.12 the CLI can't name RX_MSP, so only the other receiver's line shows
        assertEquals("# feature\nfeature GPS\n",
                BetaflightPlan.restoreReceiver("# feature\nfeature -RX_SERIAL\nfeature GPS\n", MASK_SERIAL_RX));
        // a default-off receiver that was on (SPI on a board whose default is serial) gets its line back
        assertEquals("# feature\nfeature -RX_SERIAL\nfeature RX_SPI\n",
                BetaflightPlan.restoreReceiver("# feature\nfeature -RX_SERIAL\nfeature RX_MSP\n", BetaflightPlan.FEATURE_RX_SPI));
        // no feature block at all: one is added
        assertEquals("# serial\n\n# feature\nfeature RX_PPM", BetaflightPlan.restoreReceiver("# serial\n", BetaflightPlan.FEATURE_RX_PPM));
        // a board that had no receiver (this project's F722 after an earlier failed switch): nothing to put back
        assertEquals("# feature\nfeature -RX_SERIAL\n",
                BetaflightPlan.restoreReceiver("# feature\nfeature -RX_SERIAL\nfeature RX_MSP\n", 1 << 18));
    }

    @Test
    void cliRefusalsAreRecognised() {
        assertTrue(BetaflightPlan.refused("###ERROR IN feature: INVALID NAME: RX_MSP###"));
        assertTrue(BetaflightPlan.refused("###ERROR: INVALID VALUE###"));
        assertTrue(BetaflightPlan.refused("Unavailable RX_MSP"));
        assertFalse(BetaflightPlan.refused("Enabled GPS"));
        assertFalse(BetaflightPlan.refused("serial_update_rate_hz set to 2000"));
        assertFalse(BetaflightPlan.refused(""));
    }

    @Test
    void alreadyRightMeansNoChanges() {
        assertTrue(BetaflightPlan.plan(RATE_SET, F7).isEmpty());
    }

    @Test
    void onlyTheStickDelayIsEverChangedInTheCli() {
        List<String> all = BetaflightPlan.plan(RATE_DEFAULT, F7).stream().flatMap(c -> c.commands().stream()).toList();
        assertEquals(List.of("set serial_update_rate_hz = 2000"), all);
    }

    @Test
    void cellCountIsForcedToThePilotsChoice() {
        String unset = "force_battery_cell_count = 0\r\nAllowed range: 0 - 24\r\n";
        assertEquals(0, BetaflightPlan.forcedCells(unset));
        List<BetaflightPlan.Change> plan = BetaflightPlan.plan(RATE_SET, F7, unset, 6);
        assertEquals(1, plan.size());
        assertEquals(List.of("set force_battery_cell_count = 6"), plan.get(0).commands());
        assertTrue(BetaflightPlan.plan(RATE_SET, F7, "force_battery_cell_count = 6", 6).isEmpty(), "already right");
        assertEquals(List.of("set force_battery_cell_count = 4"),
                BetaflightPlan.plan(RATE_SET, F7, "force_battery_cell_count = 6", 4).get(0).commands(), "changed");
        assertTrue(BetaflightPlan.plan(RATE_SET, F7, "Invalid name", 6).isEmpty(), "a Betaflight without it");
    }

    @Test
    void versionLineIsFound() {
        String line = BetaflightPlan.version("# version\n# " + F7 + "\n# config rev: 1");
        assertEquals(F7, line);
        assertEquals("Betaflight 4.5.1 (STM32F7X2)", BetaflightPlan.shortVersion(line));
        assertEquals("something else", BetaflightPlan.shortVersion("something else"));
    }

    @Test
    void stickDelaySettingIsRaisedOnce() {
        assertEquals(100, BetaflightPlan.serialRate(RATE_DEFAULT));
        List<BetaflightPlan.Change> plan = BetaflightPlan.plan(RATE_DEFAULT, F7);
        assertEquals(1, plan.size(), "only the serial rate is missing");
        assertEquals(List.of("set serial_update_rate_hz = 2000"), plan.get(0).commands());
        assertEquals(List.of("set serial_update_rate_hz = 1000"),
                BetaflightPlan.plan(RATE_DEFAULT, F411).get(0).commands(), "slower F411: 1000");
        assertTrue(BetaflightPlan.plan("Invalid name", F7).isEmpty(),
                "a Betaflight without the setting is left alone");
    }

    @Test
    void flightControllersOfEveryKindAreFound() {
        // the localized name Windows gives any virtual COM port, as on this project's AT32 board
        String generic = "Seriell USB-enhet (COM10)";
        for (int vid : new int[]{0x0483, 0x2E3C, 0x314B, 0x28E9, 0x2E8A, 0x1209, 0x26AC, 0x2DAE, 0x3162,
                0x1A86, 0x10C4, 0x0403, 0x067B}) {
            assertTrue(BetaflightUsb.isFlightController(vid, generic), Integer.toHexString(vid));
        }
        assertTrue(BetaflightUsb.isFlightController(-1, "STM32 Virtual ComPort"), "by name when the vendor is unknown");
        assertFalse(BetaflightUsb.isFlightController(0x046D, generic), "some other USB serial device");
        assertFalse(BetaflightUsb.isFlightController(-1, "Communications Port (COM1)"), "the PC's own serial port");
    }
}
