package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BetaflightTest {
    // shapes of Betaflight 4.5 CLI output
    static final String FEATURES_SERIAL_RX = """
            # feature
            Enabled: RX_SERIAL SOFTSERIAL TELEMETRY OSD AIRMODE ANTI_GRAVITY DYNAMIC_FILTER
            """;
    static final String FEATURES_MSP_RX = "Enabled: RX_MSP OSD AIRMODE\n";
    static final String RATE_DEFAULT = "serial_update_rate_hz = 100\r\nAllowed range: 100 - 2000\r\nDefault value: 100\r\n";
    static final String RATE_SET = "serial_update_rate_hz = 2000";
    static final String F7 = "Betaflight / STM32F7X2 (S7X2) 4.5.1 Oct 13 2024 / 12:00:00 (77d01ba3b) MSP API: 1.46";
    static final String F411 = "Betaflight / STM32F411 (S411) 4.4.3 Nov 14 2023 / 12:00:00 (738127e7e) MSP API: 1.45";

    @Test
    void serialReceiverIsSwitchedToMsp() {
        List<BetaflightPlan.Change> plan = BetaflightPlan.plan(FEATURES_SERIAL_RX, RATE_SET, F7);
        assertEquals(1, plan.size());
        assertEquals(List.of("feature -RX_SERIAL", "feature RX_MSP"), plan.get(0).commands());
    }

    @Test
    void alreadyRightMeansNoChanges() {
        assertTrue(BetaflightPlan.plan(FEATURES_MSP_RX, RATE_SET, F7).isEmpty());
        assertTrue(BetaflightPlan.isMspReceiver(FEATURES_MSP_RX));
    }

    @Test
    void onlyTheReceiverAndTheStickDelayAreEverChanged() {
        List<String> all = BetaflightPlan.plan(FEATURES_SERIAL_RX, RATE_DEFAULT, F7).stream()
                .flatMap(c -> c.commands().stream()).toList();
        assertEquals(List.of("feature -RX_SERIAL", "feature RX_MSP", "set serial_update_rate_hz = 2000"), all);
    }

    @Test
    void unreadableFeatureOutputDisablesEveryOtherReceiver() {
        List<String> cmds = BetaflightPlan.plan("garbage", RATE_SET, F7).get(0).commands();
        assertEquals(List.of("feature -RX_SERIAL", "feature -RX_PPM", "feature -RX_SPI", "feature -RX_PARALLEL_PWM", "feature RX_MSP"), cmds);
    }

    @Test
    void cellCountIsForcedToThePilotsChoice() {
        String unset = "force_battery_cell_count = 0\r\nAllowed range: 0 - 24\r\n";
        assertEquals(0, BetaflightPlan.forcedCells(unset));
        List<BetaflightPlan.Change> plan = BetaflightPlan.plan(FEATURES_MSP_RX, RATE_SET, F7, unset, 6);
        assertEquals(1, plan.size());
        assertEquals(List.of("set force_battery_cell_count = 6"), plan.get(0).commands());
        assertTrue(BetaflightPlan.plan(FEATURES_MSP_RX, RATE_SET, F7, "force_battery_cell_count = 6", 6).isEmpty(), "already right");
        assertEquals(List.of("set force_battery_cell_count = 4"),
                BetaflightPlan.plan(FEATURES_MSP_RX, RATE_SET, F7, "force_battery_cell_count = 6", 4).get(0).commands(), "changed");
        assertTrue(BetaflightPlan.plan(FEATURES_MSP_RX, RATE_SET, F7, "Invalid name", 6).isEmpty(), "a Betaflight without it");
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
        List<BetaflightPlan.Change> plan = BetaflightPlan.plan(FEATURES_MSP_RX, RATE_DEFAULT, F7);
        assertEquals(1, plan.size(), "only the serial rate is missing");
        assertEquals(List.of("set serial_update_rate_hz = 2000"), plan.get(0).commands());
        assertEquals(List.of("set serial_update_rate_hz = 1000"),
                BetaflightPlan.plan(FEATURES_MSP_RX, RATE_DEFAULT, F411).get(0).commands(), "slower F411: 1000");
        assertTrue(BetaflightPlan.plan(FEATURES_MSP_RX, "Invalid name", F7).isEmpty(),
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
