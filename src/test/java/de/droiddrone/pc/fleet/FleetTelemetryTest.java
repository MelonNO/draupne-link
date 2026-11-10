package de.droiddrone.pc.fleet;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The virtual phone's records read back by DRAUPNE Link's own parser (FcTelemetry), so the fleet's wire format can't
 * drift from the PC's; and how DRAUPNE Link finds the fleet (the announcement file) and files its drones.
 */
class FleetTelemetryTest {
    private static final FcInfo BETAFLIGHT = new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 4, 1, 0, 1, 45, 0);

    private interface Writer {
        void write(DataOutputStream out) throws Exception;
    }

    private static void parse(FcTelemetry t, Writer w) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        w.write(new DataOutputStream(bytes));
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
    }

    @Test
    void everyRecordReadsBackThroughThePcsParser() throws Exception {
        FcTelemetry t = new FcTelemetry();
        parse(t, out -> {
            TelemetryRecords.boxNames(out, FlightModel.BOX_NAMES);
            TelemetryRecords.boxIds(out, FlightModel.BOX_IDS);
        });
        assertTrue(t.hasBoxNames());
        assertTrue(t.hasBoxIds());
        int modes = (1 << FlightModel.BOX_ARM) | (1 << FlightModel.BOX_ANGLE);
        parse(t, out -> {
            TelemetryRecords.attitude(out, -12.3, 4.5, 271);
            TelemetryRecords.status(out, TelemetryRecords.SENSOR_ACC | TelemetryRecords.SENSOR_GPS, modes, FlightModel.FLAG_RXLOSS, 12, 38);
            TelemetryRecords.altitude(out, 23.45, -1.5);
            TelemetryRecords.analog(out, 321, 1023, 14.2, 15.87);
            TelemetryRecords.rawGps(out, 2, 13, 52.4732, 13.4012, 25, 10, 90, 0.9, 1234);
            TelemetryRecords.batteryState(out, 4, 1500, 321, 14.2, 15.87);
            TelemetryRecords.compGps(out, 120, 200);
            TelemetryRecords.timers(out, 100, 42, 42);
            TelemetryRecords.phoneBattery(out, 88, false);
            TelemetryRecords.cameraFps(out, 30);
            TelemetryRecords.videoBitRate(out, 3.5f);
            TelemetryRecords.network(out, 4, 70);
            TelemetryRecords.batteryConfig(out, 3.3, 4.3, 3.5);
        });
        FcTelemetry.Attitude a = t.getAttitude();
        assertEquals(-12.3, a.rollDeg(), 0.05);
        assertEquals(4.5, a.pitchDeg(), 0.05);
        assertEquals(271, a.headingDeg());
        FcTelemetry.Status s = t.getStatus();
        assertTrue(s.armed(), "ARM among the modes, found by the box names");
        assertEquals("ANGLE", FcTelemetry.flightMode(s.activeModes()));
        assertEquals("RXLOSS", s.blockers().get(0).osdName());
        assertEquals(38, s.coreTempC());
        assertEquals(23.45, t.getAltitude().meters(), 0.01);
        assertEquals(-1.5, t.getAltitude().varioMetersPerSecond(), 0.01);
        assertEquals(15.87, t.getBattery().volts(), 0.01);
        assertEquals(4, t.getBattery().cells());
        assertEquals(321, t.getBattery().mahDrawn());
        assertEquals(100, t.getRssiPercent());
        FcTelemetry.Gps g = t.getGps();
        assertEquals(2, g.fix());
        assertEquals(13, g.sats());
        assertEquals(52.4732, g.latDeg(), 1e-6);
        assertEquals(13.4012, g.lonDeg(), 1e-6);
        assertEquals(36, g.speedKmh(), 0.1);
        assertEquals(90, g.courseDeg());
        assertEquals(120, g.distanceHomeM());
        assertEquals(200, g.directionHomeDeg());
        assertEquals(1234, g.travelledM());
        assertEquals(88, t.getPhoneBatteryPercent());
        assertEquals(30, t.getCameraFps());
        assertEquals(3.5f, t.getPhoneVideoMbps(), 0.01);
        assertEquals("LTE", t.getNetwork().name());
        assertEquals(3.5, t.getBatteryLimits().warningCell(), 0.01);
        assertEquals(0, t.getUnknownCodes(), "every record known");

        parse(t, out -> {
            TelemetryRecords.linkStats(out, 40, 40, 1);
            TelemetryRecords.version(out, VirtualDrone.VERSION);
            TelemetryRecords.encoder(out, 4_000_000);
            TelemetryRecords.phonePosition(out, 52.4732, 13.4012, 30, 5, 12.3, 90, 0);
            TelemetryRecords.phoneState(out, TelemetryRecords.STATE_RX_MAP_KNOWN | TelemetryRecords.STATE_AUDIO_ON, 300, 20, 1);
            TelemetryRecords.phonePower(out, 2, 5, 3, 15);
        });
        assertEquals(VirtualDrone.VERSION, t.getPhoneAppVersion());
        assertTrue(t.getPhonePcBuild() >= 40, "the newest layouts: the PC uses them all (pc.40: fleet management)");
        assertEquals(FcTelemetry.pcBuild(de.droiddrone.pc.setup.ApkShare.VERSION), t.getPhonePcBuild(), "the phone app this PC hands out: no \"old phone app\" warning");
        assertEquals(4_000_000, t.getPhoneTargetBitRate());
        assertEquals(52.4732, t.getPhonePosition().latDeg(), 1e-6);
        FcTelemetry.PhoneState ps = t.getPhoneState();
        assertTrue(ps.channelMapKnown());
        assertTrue(ps.audioOn());
        assertEquals(300 & 0xff, ps.lastRcSeq());
        assertEquals(20, ps.rcWriteAgeMs());
        assertEquals(2, t.getPhonePower().mode());
        assertEquals(3, t.getPhonePower().sleepInS());
        parse(t, out -> TelemetryRecords.rcLag(out, 12, 80, 3));
        assertEquals(80, t.getPhoneRcLag().maxLagMs());
        assertEquals(0, t.getUnknownCodes());
    }

    @Test
    void theAnnouncementIsReadOnlyWhileFreshAndOnlyForLoopback(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(VirtualFleet.FILE);
        assertTrue(VirtualFleet.read(f, 1000).isEmpty(), "no file: no fleet");
        VirtualFleet.write(f, List.of(new VirtualFleet.Drone("127.0.0.11", 50001), new VirtualFleet.Drone("127.0.0.12", 50002)), 100_000);
        List<VirtualFleet.Drone> d = VirtualFleet.read(f, 101_000);
        assertEquals(2, d.size());
        assertEquals(new VirtualFleet.Drone("127.0.0.12", 50002), d.get(1));
        assertTrue(VirtualFleet.read(f, 100_000 + VirtualFleet.STALE_MS + 1).isEmpty(), "a stale heartbeat: the fleet ended");
        Files.writeString(f, "heartbeat=100000\ndrone=100.64.0.5:6286\ndrone=127.0.0.13:7000\ndrone=127.0.0.999:1\ndrone=bad\n");
        d = VirtualFleet.read(f, 100_000);
        assertEquals(List.of(new VirtualFleet.Drone("127.0.0.13", 7000)), d, "only loopback addresses are ever taken");
        VirtualFleet.withdraw(f);
        assertFalse(Files.exists(f));
        assertTrue(VirtualFleet.isVirtual("127.0.0.11"));
        assertFalse(VirtualFleet.isVirtual("100.64.0.5"));
        assertFalse(VirtualFleet.isVirtual(null));
        assertEquals(6286, VirtualFleet.portOf("100.64.0.5", 6286), "a real phone: always the link port");
    }

    @Test
    void aVirtualDroneIsNeverSavedAndNeverTakesARealPhonesName() throws Exception {
        Map<String, String> saved = new HashMap<>();
        Names names = new Names(PcConfig.defaults(), saved::putAll);
        names.seen(new Names.Reply(Names.Kind.PHONE, "MobileAlpha", "a phone", "2.3.0-pc.38"), "100.64.0.5", "phone");
        assertTrue(saved.containsKey("name.MobileAlpha"));
        saved.clear();
        names.seen(new Names.Reply(Names.Kind.PHONE, "MobileZulu", VirtualDrone.DEVICE, VirtualDrone.VERSION), "127.0.0.11", "virtual fleet");
        assertTrue(saved.isEmpty(), "known while the fleet runs, never in the settings: " + saved);
        Names.Entry z = names.get("MobileZulu");
        assertNotNull(z);
        assertTrue(z.isVirtual());
        assertNull(names.seen(new Names.Reply(Names.Kind.PHONE, "MobileAlpha", VirtualDrone.DEVICE, VirtualDrone.VERSION), "127.0.0.12", "virtual fleet"));
        assertEquals("100.64.0.5", names.ipOf("MobileAlpha"), "the real phone keeps its name");
        assertFalse(names.get("MobileAlpha").isVirtual());
    }

    @Test
    void theFleetPicksNamesTheMapDoesntHave() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("name.MobileZulu", "PHONE|100.64.0.5|a phone||2.3.0-pc.38|0");
        try (Fleet fleet = new Fleet(config, null, line -> { })) {
            assertEquals("MobileYankee", fleet.freeName(), "Zulu is one of the pilot's phones");
            assertTrue(fleet.nameTaken("mobilezulu"));
            assertFalse(fleet.nameTaken("MobileYankee"));
        }
    }
}
