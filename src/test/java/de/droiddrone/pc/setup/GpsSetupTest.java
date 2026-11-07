package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GpsSetupTest {
    // the CLI's "serial" on an F722: USB VCP (MSP), UART1 the serial receiver, UART2 free, UART3 the GPS
    static final String SERIAL = "# serial\nserial 20 1 115200 57600 0 115200\nserial 0 64 115200 57600 0 115200\n"
            + "serial 1 0 115200 57600 0 115200\nserial 2 2 115200 57600 0 115200\nserial 3 0 115200 57600 0 115200\n";
    static final int GPS_ON = BetaflightPlan.FEATURE_RX_MSP | BetaflightPlan.FEATURE_GPS;
    static final int GPS_OFF = BetaflightPlan.FEATURE_RX_MSP;
    static final String AUTO_BAUD_OFF = "gps_auto_baud = OFF\nAllowed values: OFF, ON\n";
    static final String AUTO_BAUD_ON = "gps_auto_baud = ON\n";

    @Test
    void portsAreReadWithTheirNames() {
        List<GpsSetup.Port> ports = GpsSetup.ports(SERIAL);
        assertEquals(5, ports.size());
        assertEquals("USB VCP", ports.get(0).name());
        assertEquals("UART1", ports.get(1).name());
        assertEquals(0, ports.get(0).uart(), "the USB port is no UART");
        assertEquals(3, ports.get(3).uart());
        assertTrue(ports.get(3).hasGps());
        assertEquals("115200 57600 0 115200", ports.get(3).rest());
        assertEquals(List.of(1, 2, 3, 4), GpsSetup.uarts(SERIAL));
        assertEquals(3, GpsSetup.gpsUart(SERIAL));
        assertEquals("UART3", GpsSetup.gpsPortName(SERIAL));
        assertEquals(0, GpsSetup.gpsUart("serial 20 1 115200 57600 0 115200"));
        assertEquals("", GpsSetup.gpsPortName(""));
        assertEquals("SOFTSERIAL1", GpsSetup.portName(30));
        assertEquals("LPUART1", GpsSetup.portName(40));
    }

    @Test
    void savedAnswersAreParsed() {
        assertNull(GpsSetup.parseYesNo(""), "not asked yet");
        assertEquals(Boolean.TRUE, GpsSetup.parseYesNo("yes"));
        assertEquals(Boolean.FALSE, GpsSetup.parseYesNo("No"));
        assertEquals(3, GpsSetup.parseUart(""));
        assertEquals(2, GpsSetup.parseUart("UART2"));
        assertEquals(6, GpsSetup.parseUart("6"));
        assertEquals(3, GpsSetup.parseUart("UART42"));
    }

    @Test
    void allSetMeansNoChanges() {
        assertTrue(GpsSetup.gpsPlan(GPS_ON, SERIAL, AUTO_BAUD_ON, 3).isEmpty());
    }

    @Test
    void theGpsGoesOnAndOntoItsUart() {
        List<BetaflightPlan.Change> plan = GpsSetup.gpsPlan(GPS_OFF, SERIAL.replace("serial 2 2 ", "serial 2 0 "), AUTO_BAUD_OFF, 3);
        assertEquals(3, plan.size());
        assertEquals(List.of("feature GPS"), plan.get(0).commands());
        assertEquals(List.of("serial 2 2 115200 57600 0 115200"), plan.get(1).commands(), "the baud fields kept");
        assertEquals("GPS on UART3", plan.get(1).description());
        assertEquals(List.of("set gps_auto_baud = ON"), plan.get(2).commands());
    }

    @Test
    void anotherUartLosesTheGpsAndTheChosenOneLosesWhatItHad() {
        // the GPS moves from UART3 to UART1, which has the serial receiver: GPS alone on it, said so
        List<BetaflightPlan.Change> plan = GpsSetup.gpsPlan(GPS_ON, SERIAL, AUTO_BAUD_ON, 1);
        assertEquals(1, plan.size());
        assertEquals(List.of("serial 2 0 115200 57600 0 115200", "serial 0 2 115200 57600 0 115200"), plan.get(0).commands());
        assertEquals("GPS on UART1 (serial receiver taken off it), off UART3", plan.get(0).description());
    }

    @Test
    void aUartBetaflightDidNotListIsAskedForAllTheSame() {
        List<BetaflightPlan.Change> plan = GpsSetup.gpsPlan(GPS_ON, "serial 20 1 115200 57600 0 115200\n", "", 6);
        assertEquals(1, plan.size());
        assertEquals(List.of("serial 5 2 115200 57600 0 115200"), plan.get(0).commands());
        // a Betaflight without gps_auto_baud never gets the command
        assertFalse(plan.stream().anyMatch(c -> c.commands().contains("set gps_auto_baud = ON")));
    }

    @Test
    void theCompassFollowsTheAnswer() {
        assertEquals("AUTO", GpsSetup.magHardware("mag_hardware = AUTO\nAllowed values: AUTO, NONE, HMC5883, AK8975"));
        assertEquals("", GpsSetup.magHardware("Invalid name"));
        assertNull(GpsSetup.compassPlan(true, "AUTO"), "wanted and on: nothing");
        assertNull(GpsSetup.compassPlan(true, "QMC5883"), "a named chip is kept");
        assertEquals(List.of("set mag_hardware = AUTO"), GpsSetup.compassPlan(true, "NONE").commands());
        assertEquals(List.of("set mag_hardware = NONE"), GpsSetup.compassPlan(false, "AUTO").commands());
        assertNull(GpsSetup.compassPlan(false, "NONE"));
        assertNull(GpsSetup.compassPlan(true, ""), "not understood: left alone");
    }

    @Test
    void sensorsAndReadingsFromMsp() {
        // MSP_STATUS: cycle time u16, i2c errors u16, sensors u16 (acc 1, baro 2, mag 4, gps 8), ...
        byte[] withMagAndGps = {0, 0, 0, 0, (byte) (1 | 4 | 8), 0, 0, 0, 0, 0, 0};
        byte[] accOnly = {0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0};
        assertTrue(GpsSetup.magInStatus(withMagAndGps));
        assertTrue(GpsSetup.gpsInStatus(withMagAndGps));
        assertFalse(GpsSetup.magInStatus(accOnly));
        assertFalse(GpsSetup.gpsInStatus(accOnly));
        assertFalse(GpsSetup.magInStatus(new byte[3]), "a short answer");
        // MSP_RAW_IMU: acc, gyro, mag as int16 each
        byte[] imu = new byte[18];
        imu[12] = (byte) 0x10; imu[13] = 0x00; // mag x = 16
        imu[14] = (byte) 0xff; imu[15] = (byte) 0xff; // mag y = -1
        imu[16] = 0x00; imu[17] = (byte) 0x80; // mag z = -32768
        assertArrayEquals(new int[]{16, -1, -32768}, GpsSetup.magFromRawImu(imu));
        assertNull(GpsSetup.magFromRawImu(new byte[12]), "an answer without the compass");
    }

    @Test
    void calibrationIsSeenInTheOffsets() {
        assertFalse(GpsSetup.calibrated("mag_calibration = 0,0,0\nAllowed range: -32768 - 32767"));
        assertTrue(GpsSetup.calibrated("mag_calibration = -123, 45, 6"));
        assertFalse(GpsSetup.calibrated("Invalid name"));
    }

    // Betaflight 2025.12 prints the ports by name: VCP (MSP), UART1 Tramp, UART2 the serial receiver, UART3 the GPS,
    // UART6 the ESC sensor - and a second GPS left on UART4 from an earlier try
    static final String SERIAL_2025 = "# serial\nserial VCP 1 115200 57600 0 115200\nserial UART1 8192 115200 57600 0 115200\n"
            + "serial UART2 64 115200 57600 0 115200\nserial UART3 2 115200 57600 0 115200\n"
            + "serial UART4 2 115200 57600 0 115200\nserial UART6 1024 115200 57600 0 115200\n";

    @Test
    void betaflight2025PortNamesAreUnderstood() {
        List<GpsSetup.Port> ports = GpsSetup.ports(SERIAL_2025);
        assertEquals(6, ports.size());
        assertEquals("USB VCP", ports.get(0).name());
        assertEquals("UART3", ports.get(3).name());
        assertEquals(3, ports.get(3).uart());
        assertEquals(List.of(1, 2, 3, 4, 6), GpsSetup.uarts(SERIAL_2025));
        assertEquals(3, GpsSetup.gpsUart(SERIAL_2025));
        // 2025.12's own numbers (MSP's) and the other names
        assertEquals(0, GpsSetup.legacyId("51"));
        assertEquals(9, GpsSetup.legacyId("60"));
        assertEquals(2, GpsSetup.legacyId("2"));
        assertEquals(30, GpsSetup.legacyId("SOFT1"));
        assertEquals(40, GpsSetup.legacyId("lpuart1"));
        assertEquals(-1, GpsSetup.legacyId("INVALID"));
    }

    @Test
    void theGpsIsOnOneUartOnlyAndOtherPortsKeepWhatTheyHave() {
        // the pilot picks UART4: both UART3 and UART4 had the GPS - UART3 loses it, nothing else changes
        List<BetaflightPlan.Change> plan = GpsSetup.gpsPlan(GPS_ON, SERIAL_2025, AUTO_BAUD_ON, 4);
        assertEquals(1, plan.size());
        assertEquals(List.of("serial UART3 0 115200 57600 0 115200"), plan.get(0).commands(), "named as the CLI names it");
        // UART6: the GPS goes on UART6 (the ESC sensor said to go), off UART3 and UART4; UART1's Tramp is never touched
        plan = GpsSetup.gpsPlan(GPS_ON, SERIAL_2025, AUTO_BAUD_ON, 6);
        assertEquals(List.of("serial UART3 0 115200 57600 0 115200", "serial UART4 0 115200 57600 0 115200",
                "serial UART6 2 115200 57600 0 115200"), plan.get(0).commands());
        assertEquals("GPS on UART6 (ESC sensor taken off it), off UART3, off UART4", plan.get(0).description());
        assertFalse(plan.get(0).commands().stream().anyMatch(c -> c.startsWith("serial UART1 ") || c.startsWith("serial 0 ")));
    }

    @Test
    void aGpsOnAPortThatIsNoUartIsTakenOffToo() {
        String serial = "serial VCP 1 115200 57600 0 115200\nserial SOFT1 2 115200 57600 0 115200\nserial UART3 0 115200 57600 0 115200\n";
        List<BetaflightPlan.Change> plan = GpsSetup.gpsPlan(GPS_ON, serial, AUTO_BAUD_ON, 3);
        assertEquals(List.of("serial SOFT1 0 115200 57600 0 115200", "serial UART3 2 115200 57600 0 115200"), plan.get(0).commands());
    }
}
