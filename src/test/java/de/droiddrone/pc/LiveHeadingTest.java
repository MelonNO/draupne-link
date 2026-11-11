package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.Observe;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pilot (2026-11-11): "the orientation in map does not match the aircraft orientation, both the icon and sector,
 * when in Overwatch". The drones had no GPS module and so no compass: Betaflight's yaw was the gyro's drift since
 * power-up, and the map's arrow and the overwatch view took it as the heading.
 */
class LiveHeadingTest {
    private static final FcInfo BETAFLIGHT = new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 4, 1, 0, 1, 45, 0);
    private static final int MAG = 4, ACC = 1, GYRO = 32;

    private interface Writer {
        void write(DataOutputStream out) throws Exception;
    }

    private static void parse(FcTelemetry t, Writer w) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        w.write(new DataOutputStream(bytes));
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
    }

    private static void yaw(FcTelemetry t, int deg) throws Exception {
        parse(t, out -> {
            out.writeShort(FcCommon.MSP_ATTITUDE);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(deg);
        });
    }

    private static void sensors(FcTelemetry t, int bits) throws Exception {
        parse(t, out -> {
            out.writeShort(FcCommon.MSP_STATUS);
            out.writeShort(125);
            out.writeShort(0);
            out.writeShort(bits);
            out.writeByte(0);
            out.writeShort(10);
            out.writeByte(4);
            out.writeInt(0); // mode flags
            out.writeInt(0); // arming flags
            out.writeByte(0);
            out.writeShort(38);
        });
    }

    /** The phone's record (pc.48 sends it to the pilot too): flags, its own azimuth. */
    private static void record(FcTelemetry t, int flags, double fcHeading, double phoneAzimuth) throws Exception {
        parse(t, out -> Observe.writeRecord(out, new Observe.Position(flags | Observe.POS_VALID, 61.1, 11.4, 200, 0,
                fcHeading, phoneAzimuth, 3, 5, 70)));
    }

    @Test
    void aCompassMakesTheYawTheHeading() throws Exception {
        FcTelemetry t = new FcTelemetry();
        yaw(t, 234);
        sensors(t, ACC | GYRO | MAG);
        assertEquals(Boolean.TRUE, t.fcCompass());
        assertEquals(234, LiveHeading.of(t), 0.01);
        record(t, Observe.FC_COMPASS, 234, 80); // the phone disagrees: the compass on the FC wins
        assertEquals(234, LiveHeading.of(t), 0.01);
    }

    @Test
    void withoutACompassThePhonesSensorIsTheHeading() throws Exception {
        FcTelemetry t = new FcTelemetry();
        yaw(t, 234); // drift since power-up
        sensors(t, ACC | GYRO);
        assertEquals(Boolean.FALSE, t.fcCompass());
        assertTrue(Double.isNaN(LiveHeading.of(t)), "no compass, no phone's word: no heading, never the drift");
        record(t, 0, 234, 79);
        assertEquals(79, LiveHeading.of(t), 0.01);
    }

    @Test
    void anOlderPhoneAppWithoutAWordOnTheCompassKeepsTheYaw() throws Exception {
        FcTelemetry t = new FcTelemetry();
        yaw(t, 120);
        assertNull(t.fcCompass());
        assertEquals(120, LiveHeading.of(t), 0.01, "as before 1.0.62");
    }

    @Test
    void theRecordAloneSaysTheFcHasACompass() throws Exception {
        FcTelemetry t = new FcTelemetry();
        yaw(t, 300);
        record(t, Observe.FC_COMPASS, 300, 10);
        assertEquals(300, LiveHeading.of(t), 0.01);
    }

    @Test
    void aStaleRecordIsNoHeading() throws Exception {
        FcTelemetry t = new FcTelemetry();
        yaw(t, 234);
        sensors(t, ACC | GYRO);
        record(t, 0, 234, 79);
        long later = MonoClock.ms() + LiveHeading.RECORD_STALE_MS + 1;
        assertTrue(Double.isNaN(LiveHeading.of(t, later)));
    }
}
