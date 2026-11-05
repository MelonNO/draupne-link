package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The stick frame's send time and lateness limit (phone app pc.31), the phone's report on it, and their settings. */
class StickLagTest {
    @Test
    void aFrameForPc31CarriesItsSendTimeAndTheLimitInTenMsSteps() throws Exception {
        short[] frame = {1500, 1600, 1400, 1000, 2000, 1000, 1000, 1000};
        byte[] data = ControlLink.rcFrame(frame, 0x1234, 0x5_ABCD, 455);
        assertEquals(2 + 8 * 2 + 2 + 2 + 1, data.length);
        int end = 2 + 8 * 2;
        assertEquals(0x1234, ((data[end] & 0xff) << 8) | (data[end + 1] & 0xff), "the 16-bit sequence as before");
        assertEquals(0xABCD, ((data[end + 2] & 0xff) << 8) | (data[end + 3] & 0xff), "the send time's low 16 bits");
        assertEquals(46, data[end + 4] & 0xff, "455 ms rounds up to 46 x 10 ms");
        assertEquals(0, ControlLink.rcFrame(frame, 1, 0, 0)[end + 4], "0 = never drop");
        assertEquals(255, ControlLink.rcFrame(frame, 1, 0, 99_999)[end + 4] & 0xff, "clamped to the byte");
    }

    @Test
    void readsThePhonesStickLagReport() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        o.writeShort(FcTelemetry.PHONE_RC_LAG);
        o.writeShort(120); o.writeShort(850); o.writeShort(7);
        FcTelemetry t = new FcTelemetry();
        assertNull(t.getPhoneRcLag());
        t.parse(new DataReader(bytes.toByteArray(), true), null);
        FcTelemetry.PhoneRcLag lag = t.getPhoneRcLag();
        assertNotNull(lag);
        assertEquals(120, lag.lagMs());
        assertEquals(850, lag.maxLagMs());
        assertEquals(7, lag.dropped());
        bytes.reset();
        o.writeShort(FcTelemetry.PHONE_RC_LAG);
        o.writeShort(65535); o.writeShort(65535); o.writeShort(0);
        t.parse(new DataReader(bytes.toByteArray(), true), null);
        assertEquals(-1, t.getPhoneRcLag().lagMs(), "unknown");
        assertEquals(-1, t.getPhoneRcLag().maxLagMs());
    }

    @Test
    void theNewSettingsAreOffByDefaultAndClamped() throws Exception {
        PcConfig c = PcConfig.defaults();
        assertEquals(0, c.getRadioFrozenStopS());
        assertEquals(0, c.getMaxStickLagMs());
        c.set("radio.frozenStopS", "8");
        c.set("rc.maxLagMs", "99999");
        assertEquals(8, c.getRadioFrozenStopS());
        assertEquals(2550, c.getMaxStickLagMs());
        c.set("rc.maxLagMs", "soon");
        assertEquals(0, c.getMaxStickLagMs(), "not a number: off, never a crash at connect");
    }

    @Test
    void theMonotonicClockNeverGoesBack() {
        long a = MonoClock.ms();
        long b = MonoClock.ms();
        assertTrue(b >= a);
        assertTrue(Math.abs(a - System.currentTimeMillis()) < 5000, "close to the wall clock while nobody changed it");
    }
}
