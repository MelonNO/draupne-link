package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MotorTestTest {
    @Test
    void mspRequestIsFramedLikeBetaflightConfiguratorDoes() {
        // MSP_SET_MOTOR with all eight at 1000, as Configurator sends it
        byte[] f = BetaflightUsb.mspFrame(214, MotorTest.setMotorPayload(new int[0]));
        assertEquals('$', f[0]);
        assertEquals('M', f[1]);
        assertEquals('<', f[2]);
        assertEquals(16, f[3]);
        assertEquals((byte) 214, f[4]);
        assertEquals((byte) 0xe8, f[5], "1000 little-endian");
        assertEquals(0x03, f[6]);
        int checksum = 16 ^ 214;
        for (int i = 5; i < 21; i++) checksum ^= f[i] & 0xff;
        assertEquals((byte) checksum, f[21]);
        assertArrayEquals(new byte[]{'$', 'M', '<', 0, 101, 101}, BetaflightUsb.mspFrame(101, null), "MSP_STATUS, no payload");
    }

    @Test
    void answersAreFoundInTheByteStreamAndBadOnesDropped() {
        BetaflightUsb.MspParser parser = new BetaflightUsb.MspParser();
        byte[] noise = {'#', ' ', '$', 'X'};
        for (byte b : noise) assertNull(parser.feed(b));
        byte[] good = {'$', 'M', '>', 2, 104, (byte) 0xe8, 0x03, (byte) (2 ^ 104 ^ 0xe8 ^ 0x03)};
        BetaflightUsb.MspParser.Frame frame = null;
        for (byte b : good) frame = parser.feed(b);
        assertNotNull(frame);
        assertEquals(104, frame.command());
        assertArrayEquals(new byte[]{(byte) 0xe8, 0x03}, frame.payload());
        byte[] bad = {'$', 'M', '>', 1, 104, 7, 0};
        frame = null;
        for (byte b : bad) frame = parser.feed(b);
        assertNull(frame, "checksum wrong");
        byte[] refused = {'$', 'M', '!', 0, (byte) 214, (byte) 214};
        for (byte b : refused) frame = parser.feed(b);
        assertTrue(frame.error());
    }

    @Test
    void motorValuesNeverGoBelowStopOrAboveTheCap() {
        int[] sent = MotorTest.motors(MotorTest.setMotorPayload(new int[]{1050, 2000, 0, -5, 1300}));
        assertArrayEquals(new int[]{1050, MotorTest.MAX, 1000, 1000, MotorTest.MAX, 1000, 1000, 1000}, sent);
        assertEquals(1100, MotorTest.MAX, "10 %: enough to see the direction, far from a 5\" quad's hover (1.0.48 allowed 1300)");
    }

    @Test
    void everyWriteIsLoggedWithTheValueBefore() {
        assertEquals("Motor test: motor order [1, 2, 3, 4] -> [2, 1, 3, 4] (saved, Betaflight restarts)",
                MotorTest.writeLog("motor order", java.util.Arrays.toString(new int[]{1, 2, 3, 4}),
                        java.util.Arrays.toString(new int[]{2, 1, 3, 4}), "saved, Betaflight restarts"));
    }

    @Test
    void readsWhatTheFlightControllerReports() {
        byte[] motors = MotorTest.setMotorPayload(new int[]{1000, 1000, 1000, 1000});
        motors[8] = 0; motors[9] = 0; motors[10] = 0; motors[11] = 0; motors[12] = 0; motors[13] = 0; motors[14] = 0; motors[15] = 0;
        assertEquals(4, MotorTest.motorCount(motors), "a quad: four outputs, the rest 0");
        byte[] status = new byte[11];
        assertFalse(MotorTest.armed(status));
        status[6] = 1;
        assertTrue(MotorTest.armed(status));
        byte[] analog = {(byte) 234, 0, 0, 0, 0, 0, 0, (byte) (2345 & 0xff), (byte) (2345 >> 8)};
        assertEquals(23.45, MotorTest.volts(analog), 1e-9);
        assertEquals(23.4, MotorTest.volts(new byte[]{(byte) 234, 0, 0, 0, 0, 0, 0}), 1e-9, "older Betaflight: 0.1 V");
        // a battery is in: Betaflight counts cells, or the voltage is above USB power's (a 1S pack is 3-4 V)
        assertTrue(new MotorTest.Live(new int[0], 3.9, 0, false).batteryIn(), "a 1S pack");
        assertTrue(new MotorTest.Live(new int[0], 0.2, 1, false).batteryIn(), "Betaflight's count wins");
        assertFalse(new MotorTest.Live(new int[0], 0.4, 0, false).batteryIn(), "USB power only");
        assertTrue(MotorTest.is3d(new byte[]{0, 0x10, 0, 0}));
        assertFalse(MotorTest.is3d(new byte[]{(byte) 0xff, 0x0f, 0, 0}));
    }

    @Test
    void quadXIsBetaflightsLayout() {
        // props in: rear right and front left clockwise
        assertTrue(MotorTest.clockwise(1, false));
        assertFalse(MotorTest.clockwise(2, false));
        assertFalse(MotorTest.clockwise(3, false));
        assertTrue(MotorTest.clockwise(4, false));
        for (int m = 1; m <= 4; m++) assertNotEquals(MotorTest.clockwise(m, false), MotorTest.clockwise(m, true), "props out: reversed");
        assertArrayEquals(new int[]{1, 1}, MotorTest.quadXPosition(1), "rear right");
        assertArrayEquals(new int[]{1, -1}, MotorTest.quadXPosition(2), "front right");
        assertArrayEquals(new int[]{-1, 1}, MotorTest.quadXPosition(3), "rear left");
        assertArrayEquals(new int[]{-1, -1}, MotorTest.quadXPosition(4), "front left");
    }

    @Test
    void msp2ChecksumIsCrc8DvbS2() {
        // the published check value of CRC-8/DVB-S2 for "123456789"
        int crc = 0;
        for (byte b : "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) crc = BetaflightUsb.crc8DvbS2(crc, b);
        assertEquals(0xBC, crc);
    }

    @Test
    void msp2RequestsAreFramedAndAnswersFound() {
        byte[] f = BetaflightUsb.msp2Frame(0x3002, new byte[]{4, 1, 0, 3, 2});
        assertArrayEquals(new byte[]{'$', 'X', '<', 0, 0x02, 0x30, 5, 0, 4, 1, 0, 3, 2}, java.util.Arrays.copyOf(f, 13));
        int crc = 0;
        for (int i = 3; i < 13; i++) crc = BetaflightUsb.crc8DvbS2(crc, f[i]);
        assertEquals((byte) crc, f[13]);
        // an answer: the same frame shape with '>' - found among noise, a bad checksum dropped
        byte[] answer = BetaflightUsb.msp2Frame(0x3001, new byte[]{4, 0, 1, 2, 3});
        answer[2] = '>';
        BetaflightUsb.Msp2Parser parser = new BetaflightUsb.Msp2Parser();
        for (byte b : new byte[]{'#', '$', 'M', '>'}) assertNull(parser.feed(b));
        BetaflightUsb.MspParser.Frame frame = null;
        for (byte b : answer) frame = parser.feed(b);
        assertNotNull(frame);
        assertEquals(0x3001, frame.command());
        assertFalse(frame.error());
        assertArrayEquals(new byte[]{4, 0, 1, 2, 3}, frame.payload());
        answer[answer.length - 1] ^= 1;
        frame = null;
        for (byte b : answer) frame = parser.feed(b);
        assertNull(frame, "checksum wrong");
    }

    @Test
    void motorOrderIsReadAndWritten() throws Exception {
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7}, MotorTest.parseOrder(new byte[]{8, 0, 1, 2, 3, 4, 5, 6, 7}));
        assertThrows(java.io.IOException.class, () -> MotorTest.parseOrder(new byte[]{8, 0, 1}));
        assertArrayEquals(new byte[]{4, 1, 0, 3, 2}, MotorTest.orderPayload(new int[]{1, 0, 3, 2}));
    }

    @Test
    void reorderPutsEachPlacesNumberOnTheOutputThatSpunThere() {
        int[] identity = {0, 1, 2, 3, 4, 5, 6, 7};
        // all where they belong: nothing changes
        assertArrayEquals(identity, MotorTest.reordered(identity, new int[]{1, 2, 3, 4}));
        // motor 1 spun at motor 2's place and motor 2 at motor 1's: 1 and 2 swap; outputs 5-8 keep theirs
        assertArrayEquals(new int[]{1, 0, 2, 3, 4, 5, 6, 7}, MotorTest.reordered(identity, new int[]{2, 1, 3, 4}));
        // from an order that was already changed: the outputs follow, not the numbers
        int[] swapped = {1, 0, 2, 3};
        assertArrayEquals(new int[]{2, 0, 1, 3}, MotorTest.reordered(swapped, new int[]{3, 2, 1, 4}));
        assertThrows(IllegalArgumentException.class, () -> MotorTest.reordered(identity, new int[]{1, 1, 3, 4}), "a place twice");
        assertThrows(IllegalArgumentException.class, () -> MotorTest.reordered(identity, new int[]{1, 2, 3, 5}));
    }

    @Test
    void dshotCommandsAndProtocol() {
        // blocking, motor 3 (index 2), two commands: reversed, then save
        assertArrayEquals(new byte[]{1, 2, 2, 21, 12}, MotorTest.dshotPayload(2, MotorTest.DSHOT_SPIN_REVERSED, MotorTest.DSHOT_SAVE_SETTINGS));
        // MSP_ADVANCED_CONFIG's fourth byte is the motor protocol
        assertTrue(MotorTest.isDshot(new byte[]{1, 1, 0, 7}), "DSHOT600");
        assertTrue(MotorTest.isDshot(new byte[]{1, 1, 0, 5}), "DSHOT150");
        assertTrue(MotorTest.isDshot(new byte[]{1, 1, 0, 8}), "PROSHOT1000");
        assertFalse(MotorTest.isDshot(new byte[]{1, 1, 0, 3}), "MULTISHOT");
        assertFalse(MotorTest.isDshot(new byte[]{1, 1, 0}), "short");
    }
}
