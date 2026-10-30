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
        int[] sent = MotorTest.motors(MotorTest.setMotorPayload(new int[]{1150, 2000, 0, -5}));
        assertArrayEquals(new int[]{1150, MotorTest.MAX, 1000, 1000, 1000, 1000, 1000, 1000}, sent);
        assertEquals(1300, MotorTest.MAX, "30 %: enough to see the direction");
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
}
