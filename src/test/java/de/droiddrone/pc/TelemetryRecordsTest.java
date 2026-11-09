package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.TelemetryData;
import de.droiddrone.common.TelemetryRecords;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Both halves of the flight controller's telemetry: the phone's TelemetryRecords turns Betaflight's MSP replies
 * (little-endian, as the FC sends them) into records, and the PC's FcTelemetry reads those back.
 */
class TelemetryRecordsTest {
    private static final FcInfo BETAFLIGHT = FcTelemetryTest.BETAFLIGHT;

    /** An MSP payload, little-endian: each value written in the given number of bytes. */
    private static byte[] msp(int... sizeThenValue) {
        ByteBuffer b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sizeThenValue.length; i += 2) {
            int v = sizeThenValue[i + 1];
            switch (sizeThenValue[i]) {
                case 1 -> b.put((byte) v);
                case 2 -> b.putShort((short) v);
                default -> b.putInt(v);
            }
        }
        byte[] out = new byte[b.position()];
        b.flip().get(out);
        return out;
    }

    private static FcTelemetry readBack(TelemetryData... replies) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (TelemetryData r : replies) TelemetryRecords.write(r, BETAFLIGHT, out);
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        return t;
    }

    @Test
    void attitudeAnalogAndBatteryComeThrough() throws IOException {
        FcTelemetry t = readBack(
                new TelemetryData(FcCommon.MSP_ATTITUDE, msp(2, 125, 2, -40, 2, 271)),
                // legacy voltage, mAh, rssi 0..1023, amps cA, volts cV
                new TelemetryData(FcCommon.MSP_ANALOG, msp(1, 160, 2, 321, 2, 1023, 2, 1250, 2, 1610)),
                // cells, capacity, legacy voltage, mAh, amps cA, state, volts cV
                new TelemetryData(FcCommon.MSP_BATTERY_STATE, msp(1, 4, 2, 1500, 1, 160, 2, 330, 2, 1260, 1, 0, 2, 1612)));
        assertEquals(12.5, t.getAttitude().rollDeg(), 1e-9);
        assertEquals(-4.0, t.getAttitude().pitchDeg(), 1e-9);
        assertEquals(271, t.getAttitude().headingDeg());
        FcTelemetry.Battery b = t.getBattery();
        assertEquals(16.12, b.volts(), 1e-9);
        assertEquals(4, b.fcCells());
        assertEquals(330, b.mahDrawn());
        assertEquals(12.6, b.amps(), 1e-9);
    }

    @Test
    void betaflightsStatusGivesTheModesAndTheArmingFlags() throws IOException {
        byte[] names = "ARM;ANGLE;HORIZON;BEEPER;".getBytes(StandardCharsets.US_ASCII);
        // cycle time, i2c errors, sensors, the first 32 mode bits (ARM and HORIZON), pid profile, load, unused,
        // 1 more byte of mode bits, its byte, arming flags count, arming flags, config state, core temperature
        byte[] status = msp(2, 125, 2, 0, 2, 0x23, 4, 0b101, 1, 0, 2, 37, 2, 0, 1, 1, 1, 0, 1, 26, 4, 0, 1, 0, 2, 41);
        FcTelemetry t = readBack(new TelemetryData(FcCommon.MSP_BOXNAMES, names), new TelemetryData(FcCommon.MSP_STATUS, status));
        FcTelemetry.Status s = t.getStatus();
        assertNotNull(s);
        assertTrue(s.armed());
        assertEquals(List.of("ARM", "HORIZON"), s.activeModes());
        assertEquals(37, s.cpuLoadPercent());
        assertEquals(41, s.coreTempC());
        assertTrue(s.blockers().isEmpty());
    }

    @Test
    void nothingOfTheFlightControllerBeforeItsInfoButThePhonesOwn() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        TelemetryRecords.write(new TelemetryData(FcCommon.MSP_ATTITUDE, msp(2, 1, 2, 2, 2, 3)), null, out);
        assertEquals(0, bytes.size(), "no FC info yet");
        TelemetryRecords.write(new TelemetryData(FcCommon.DD_VIDEO_RECORDER_START_STOP, null), null, out);
        assertEquals(2, bytes.size(), "the code alone");
    }

    @Test
    void aReplyCutShortThrowsWithWhatWasWritten() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        assertThrows(RuntimeException.class,
                () -> TelemetryRecords.write(new TelemetryData(FcCommon.MSP_ATTITUDE, msp(2, 1)), BETAFLIGHT, out));
        assertTrue(bytes.size() >= 2, "the code (and the roll) stay, as before");
    }
}
