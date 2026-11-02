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
    void cellCountIsOnlyForcedOnceThePilotConfirmedIt() {
        String unset = "force_battery_cell_count = 0\r\nAllowed range: 0 - 24\r\n";
        // 6S is only a default: nothing is written until the pilot has touched or confirmed the spinner
        assertTrue(BetaflightPlan.plan(RATE_SET, F7, unset, 6, false).isEmpty(), "Betaflight keeps counting itself");
        assertTrue(BetaflightPlan.plan(RATE_SET, F7, "force_battery_cell_count = 4", 6, false).isEmpty(), "a count it forces is kept");
        assertEquals(List.of("set force_battery_cell_count = 6"), BetaflightPlan.plan(RATE_SET, F7, unset, 6, true).get(0).commands());
        assertEquals(List.of("set force_battery_cell_count = 6"),
                BetaflightPlan.plan(RATE_SET, F7, "force_battery_cell_count = 4", 6, true).get(0).commands(), "confirmed: changed");
        // the stick delay is written regardless
        assertEquals(1, BetaflightPlan.plan(RATE_DEFAULT, F7, unset, 6, false).size());
    }

    @Test
    void boardAndCraftNamesAreRead() {
        assertEquals("IFLIGHT_BLITZ_F722", BetaflightPlan.boardName("# board_name\r\nboard_name IFLIGHT_BLITZ_F722\r\n"));
        assertEquals("", BetaflightPlan.boardName("board_name -"), "none set");
        assertEquals("", BetaflightPlan.boardName("###ERROR: UNKNOWN COMMAND###"));
        assertEquals("Vanguard", BetaflightPlan.craftName("craft_name = Vanguard\r\nAllowed length: 16"), "4.4 on");
        assertEquals("Vanguard", BetaflightPlan.craftName("name: Vanguard"), "older");
        assertEquals("", BetaflightPlan.craftName("name = -"));
        assertEquals("", BetaflightPlan.craftName("###ERROR IN get: INVALID NAME: craft_name###"));
        assertEquals("", BetaflightPlan.craftName("Unknown command, try 'help'"));
    }

    @Test
    void batteryStateIsParsed() throws Exception {
        // 4S, 1500 mAh, 16.8 V legacy, 0 mAh drawn, 0 A, state 0, 16.82 V
        byte[] p = {4, (byte) 0xDC, 5, (byte) 168, 0, 0, 0, 0, 0, (byte) 0x92, 0x06};
        BetaflightUsb.BatteryState b = BetaflightUsb.parseBatteryState(p);
        assertEquals(4, b.cells());
        assertEquals(16.82, b.volts(), 1e-9);
        assertTrue(b.present());
        assertFalse(BetaflightUsb.parseBatteryState(new byte[]{0, 0, 0, 1, 0, 0, 0, 0, 0, 10, 0}).present(), "USB power: 0.1 V, no cells");
        assertTrue(BetaflightUsb.parseBatteryState(new byte[]{0, 0, 0, 38, 0, 0, 0, 0, 0}).present(), "an old Betaflight: 3.8 V, a 1S pack");
    }

    /** A flight controller on a fake port: what it prints, in chunks, each after a pause. */
    static final class FakeWire implements BetaflightUsb.Wire {
        private final java.util.ArrayDeque<Object[]> chunks = new java.util.ArrayDeque<>(); // {delay ms, bytes}
        /** What each write (in order) is answered with: released into chunks as the writes come. */
        private final java.util.ArrayDeque<java.util.List<Object[]>> replies = new java.util.ArrayDeque<>();
        private long readyAt;
        final StringBuilder written = new StringBuilder();
        boolean open = true;

        /** The answer to the next write, in chunks: a delay in ms before each chunk's text. */
        void reply(Object... delayThenText) {
            java.util.List<Object[]> group = new java.util.ArrayList<>();
            for (int i = 0; i < delayThenText.length; i += 2) {
                group.add(new Object[]{((Number) delayThenText[i]).longValue(),
                        ((String) delayThenText[i + 1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII)});
            }
            replies.add(group);
        }

        @Override
        public int read(byte[] buf) {
            long end = System.currentTimeMillis() + 100; // the port's read timeout
            while (System.currentTimeMillis() < end) {
                if (available() > 0) {
                    byte[] b = (byte[]) chunks.poll()[1];
                    System.arraycopy(b, 0, buf, 0, b.length);
                    readyAt = 0;
                    return b.length;
                }
                sleep(2);
            }
            return 0;
        }

        @Override
        public int available() {
            Object[] next = chunks.peek();
            if (next == null) return 0;
            if (readyAt == 0) readyAt = System.currentTimeMillis() + (long) next[0];
            return System.currentTimeMillis() >= readyAt ? ((byte[]) next[1]).length : 0;
        }

        @Override
        public void write(byte[] data) {
            written.append(new String(data, java.nio.charset.StandardCharsets.US_ASCII));
            java.util.List<Object[]> group = replies.poll();
            if (group != null) chunks.addAll(group);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Test
    void aPromptInsideDiffAllIsNotTheEnd() throws Exception {
        FakeWire wire = new FakeWire();
        wire.reply(0, "\r\nEntering CLI Mode, type 'exit' to return, or 'help'\r\n\r\n# "); // to '#'
        wire.reply(0, "# diff all\r\n# ",                      // a chunk ending in "# ": the "# feature" line's start
                25, "feature\r\nfeature GPS\r\n\r\n# ");       // the rest, a moment later
        try (BetaflightUsb fc = BetaflightUsb.onWire(wire)) {
            fc.enterCli();
            assertEquals("# feature\r\nfeature GPS", fc.command("diff all"));
            assertTrue(fc.inCli());
        }
        assertTrue(wire.written.toString().endsWith("exit\n"), "closed with the CLI open: exit sent - " + wire.written);
        assertFalse(wire.open);
    }

    @Test
    void thePromptAloneIsAcceptedQuickly() throws Exception {
        FakeWire wire = new FakeWire();
        wire.reply(0, "# version\r\n# " + F7 + "\r\n# ");
        try (BetaflightUsb fc = BetaflightUsb.onWire(wire)) {
            long t = System.currentTimeMillis();
            assertEquals("# " + F7, fc.command("version"));
            assertTrue(System.currentTimeMillis() - t < 1000, "the prompt settles within a fraction of a second");
            assertFalse(fc.inCli(), "command() alone doesn't open the CLI");
        }
        assertFalse(wire.written.toString().contains("exit"), "no CLI open: nothing to leave");
    }

    @Test
    void aCutOffAnswerIsAnError() throws Exception {
        FakeWire wire = new FakeWire();
        wire.reply(0, "\r\nEntering CLI Mode, type 'exit' to return, or 'he");
        try (BetaflightUsb fc = BetaflightUsb.onWire(wire)) {
            fc.enterCli(); // the banner never comes
            fail("should have thrown");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage().contains("cut off"), e.getMessage());
        }
        assertTrue(wire.written.toString().endsWith("exit\n"), "the board may be in the CLI anyway: exit sent");
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
