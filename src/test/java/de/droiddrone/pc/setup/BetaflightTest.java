package de.droiddrone.pc.setup;

import de.droiddrone.pc.FcTelemetry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BetaflightTest {
    // shapes of Betaflight 4.5 CLI output
    static final String FEATURES_SERIAL_RX = """
            # feature
            Enabled: RX_SERIAL SOFTSERIAL TELEMETRY OSD AIRMODE ANTI_GRAVITY DYNAMIC_FILTER
            """;
    static final String FEATURES_MSP_RX = "Enabled: RX_MSP OSD AIRMODE\n";
    static final String AUX_EMPTY = """
            # aux
            aux 0 0 0 900 900 0 0
            aux 1 0 0 900 900 0 0
            aux 2 0 0 900 900 0 0
            """;
    static final String AUX_WITH_ARM = """
            aux 0 1 1 1300 2100 0 0
            aux 1 0 0 1800 2100 0 0
            aux 2 0 0 900 900 0 0
            """;

    @Test
    void serialReceiverIsSwitchedToMspAndArmIsAdded() {
        List<BetaflightPlan.Change> plan = BetaflightPlan.plan(FEATURES_SERIAL_RX, AUX_EMPTY);
        assertEquals(2, plan.size());
        assertEquals(List.of("feature -RX_SERIAL", "feature RX_MSP"), plan.get(0).commands());
        assertEquals(List.of("aux 0 0 0 1700 2100 0 0"), plan.get(1).commands(), "ARM (mode 0) on AUX1 (channel 0), high");
    }

    @Test
    void alreadyRightMeansNoChanges() {
        assertTrue(BetaflightPlan.plan(FEATURES_MSP_RX, AUX_WITH_ARM).isEmpty());
        assertTrue(BetaflightPlan.hasArmSwitch(AUX_WITH_ARM));
        assertTrue(BetaflightPlan.isMspReceiver(FEATURES_MSP_RX));
    }

    @Test
    void existingModesAreKeptAndArmGoesIntoAFreeSlot() {
        String aux = "aux 0 1 1 1300 2100 0 0\naux 1 2 2 1300 2100 0 0\naux 2 0 0 900 900 0 0\n";
        List<BetaflightPlan.Change> plan = BetaflightPlan.plan(FEATURES_MSP_RX, aux);
        assertEquals(List.of("aux 2 0 0 1700 2100 0 0"), plan.get(0).commands());
    }

    @Test
    void unreadableFeatureOutputDisablesEveryOtherReceiver() {
        List<String> cmds = BetaflightPlan.plan("garbage", AUX_WITH_ARM).get(0).commands();
        assertEquals(List.of("feature -RX_SERIAL", "feature -RX_PPM", "feature -RX_SPI", "feature -RX_PARALLEL_PWM", "feature RX_MSP"), cmds);
    }

    @Test
    void versionLineIsFound() {
        assertEquals("Betaflight / STM32F7X2 (S7X2) 4.5.1 Oct 13 2024 / 12:00:00 (77d01ba3b) MSP API: 1.46",
                BetaflightPlan.version("# version\n# Betaflight / STM32F7X2 (S7X2) 4.5.1 Oct 13 2024 / 12:00:00 (77d01ba3b) MSP API: 1.46\n# config rev: 1"));
    }

    @Test
    void mspFramesRoundTrip() throws IOException {
        byte[] req = BetaflightUsb.Msp.request(205, null);
        assertArrayEquals(new byte[]{'$', 'M', '<', 0, (byte) 205, (byte) 205}, req);
        byte[] reply = {'x', '$', 'M', '>', 2, 101, 7, 9, (byte) (2 ^ 101 ^ 7 ^ 9)};
        assertArrayEquals(new byte[]{7, 9}, BetaflightUsb.Msp.parseReply(reply, 101));
        assertNull(BetaflightUsb.Msp.parseReply(new byte[]{'$', 'M', '>', 2, 101, 7}, 101), "incomplete");
        byte[] corrupt = {'$', 'M', '>', 1, 101, 7, 0};
        assertThrows(IOException.class, () -> BetaflightUsb.Msp.parseReply(corrupt, 101));
        assertThrows(IOException.class, () -> BetaflightUsb.Msp.parseReply(new byte[]{'$', 'M', '!', 0, (byte) 205, (byte) 205}, 205));
    }

    @Test
    void armingFlagsFromRawStatus() {
        // Betaflight MSP_STATUS: cycle, i2c, sensors, modes(4), profile, load, unused, extraModeBytes=1 [1 byte],
        // armingFlagCount, armingFlags (little-endian)
        int flags = 1 << 8 | 1 << 16; // ANGLE + MSP, what this project's drone showed
        byte[] s = {125, 0, 0, 0, 0x23, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 1, 0, 26,
                (byte) flags, (byte) (flags >> 8), (byte) (flags >> 16), (byte) (flags >> 24), 0};
        assertEquals(flags, BetaflightUsb.armingFlags(s));
        assertEquals(List.of("ANGLE", "MSP"), FcTelemetry.blockersFor(flags).stream().map(FcTelemetry.Blocker::osdName).toList());
    }
}
