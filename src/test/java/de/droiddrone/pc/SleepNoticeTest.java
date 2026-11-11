package de.droiddrone.pc;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.OverwatchProtocol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.64 / phone pc.50, the pilot: "Make sure that a device reports position, heading, battery percentages, Name, time
 * until next check and switched mode before starting to sleep" - the sleep notice in the phone's countdown.
 */
public class SleepNoticeTest {
    public static final String KEY = "k";

    /** A notice as the phone sends it: [0x6F][battery][charging][interval][mV u16][owFlags][floor] + status + where + notice. */
    public static byte[] notice(int battery, int mode, int interval, int owFlags, FleetProtocol.Notice n) {
        byte[] head = {0x6F, (byte) battery, 0, (byte) interval, 0x0F, 0x3C, (byte) (owFlags | OverwatchProtocol.HELLO_NOTICE), 0};
        FleetProtocol.Status status = new FleetProtocol.Status(battery, false, 3, 2, 9, 250, mode, interval, 77);
        FleetProtocol.Where where = new FleetProtocol.Where("MobileAlpha", 2, 59.91, 10.75, 4);
        return FleetProtocol.hello(head, status, where, n, KEY);
    }

    @Test
    void theNoticeCarriesHeadingAndNextCheckAndOldReadersStillRead() {
        byte[] d = notice(81, 2, 5, 0, new FleetProtocol.Notice(123.4, FleetProtocol.Notice.HEADING_PHONE, 2, 8, 301));
        FleetProtocol.Notice n = FleetProtocol.Notice.read(d, d.length, KEY);
        assertNotNull(n);
        assertEquals(123.4, n.headingDeg, 0.05);
        assertEquals(FleetProtocol.Notice.HEADING_PHONE, n.headingSource);
        assertEquals(2, n.accuracy);
        assertEquals(8, n.errorDeg);
        assertEquals(301, n.nextCheckInS);
        assertTrue(n.headingUsable());
        // what an older PC reads: the head, the status, the where - unchanged
        ControlLink.SleepHello h = ControlLink.SleepHello.read(d, d.length, 0);
        assertEquals(81, h.batteryPercent());
        assertEquals(2, h.status().mode);
        FleetProtocol.Where w = FleetProtocol.Where.read(d, 8 + FleetProtocol.STATUS_BYTES, d.length, KEY);
        assertEquals("MobileAlpha", w.name);
        assertEquals(59.91, w.lat, 1e-6);
    }

    @Test
    void noNoticeWithoutItsFlagOrBlockAndAnUnreliableHeadingIsNotShown() {
        byte[] plain = FleetProtocol.hello(new byte[]{0x6F, 80, 0, 5, 0, 0, 0, 0},
                new FleetProtocol.Status(80, false, 3, 2, 9, 250, 2, 5, 0), new FleetProtocol.Where("MobileAlpha", 0, 0, 0, 0), KEY);
        assertNull(FleetProtocol.Notice.read(plain, plain.length, KEY), "a check-in, not a notice");
        byte[] flaggedOnly = plain.clone();
        flaggedOnly[6] = (byte) OverwatchProtocol.HELLO_NOTICE;
        assertNull(FleetProtocol.Notice.read(flaggedOnly, flaggedOnly.length, KEY), "flag without block");
        byte[] other = notice(81, 2, 5, 0, new FleetProtocol.Notice(10, FleetProtocol.Notice.HEADING_PHONE, 0, -1, 0));
        assertNull(FleetProtocol.Notice.read(other, other.length, "another key"));
        FleetProtocol.Notice unreliable = FleetProtocol.Notice.read(other, other.length, KEY);
        assertFalse(unreliable.headingUsable(), "Android says unreliable: no heading shown");
        FleetProtocol.Notice none = new FleetProtocol.Notice(Double.NaN, FleetProtocol.Notice.HEADING_NONE, -1, -1, 0);
        assertFalse(none.headingUsable());
        FleetProtocol.Notice fc = new FleetProtocol.Notice(-10, FleetProtocol.Notice.HEADING_FC, 3, -1, 60);
        assertEquals(350, fc.headingDeg, 1e-9, "normalised");
    }

    /** The map keeps a sleeping drone's heading through later replies without one, while it stays put. */
    @Test
    void theHeadingStaysWhileTheDroneStaysPut() throws Exception {
        PcConfig config = PcConfig.defaults();
        List<String> log = new ArrayList<>();
        NameService svc = new NameService(config, new Names(config, null), () -> null, 1, false, m -> { }, log::add);
        FleetProtocol.Where w = new FleetProtocol.Where("MobileAlpha", 2, 59.91, 10.75, 4);
        svc.heardAsleep("100.64.0.5", w, 2, 123.0);
        assertEquals(123.0, svc.dronePositions().get(0).headingDeg(), 1e-9);
        svc.heardAsleep("100.64.0.5", w, 2); // its next check-in: no heading in it
        assertEquals(123.0, svc.dronePositions().get(0).headingDeg(), 1e-9, "kept: it hasn't moved");
        svc.heardAsleep("100.64.0.5", new FleetProtocol.Where("MobileAlpha", 2, 59.92, 10.75, 4), 2);
        assertTrue(Double.isNaN(svc.dronePositions().get(0).headingDeg()), "1 km on: that heading is gone");
    }
}
