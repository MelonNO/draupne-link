package de.droiddrone.common;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.Names;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** FLEET MANAGEMENT's wire (1.0.52, phone pc.40): the request, the status block, and where the PC reads it. */
class FleetProtocolTest {
    private static final String KEY = "k3y";

    @Test
    void aRequestGoesAndComesBackWithItsKeyOnly() {
        byte[] p = FleetProtocol.request(KEY, new FleetProtocol.Request(0x12345678, 3, SleepCheck.ALWAYS, 2, 1, 0x0badcafe, 8888));
        FleetProtocol.Request r = FleetProtocol.parseRequest(p, p.length, KEY);
        assertNotNull(r);
        assertEquals(0x12345678, r.token);
        assertEquals(3, r.mode);
        assertEquals(SleepCheck.ALWAYS, r.intervalMin);
        assertEquals(2, r.sensitivity);
        assertEquals(1, r.flags);
        assertEquals(0x0badcafe, r.owToken);
        assertEquals(8888, r.pcPort);
        assertNull(FleetProtocol.parseRequest(p, p.length, "other"), "another key: dropped");
        assertNull(FleetProtocol.parseRequest(p, p.length - 3, KEY), "cut short: dropped");
        byte[] normal = FleetProtocol.request(KEY, new FleetProtocol.Request(1, 0, 5, 0, 0, 0, 8888));
        assertNull(FleetProtocol.parseRequest(normal, normal.length, KEY), "mode 0 isn't a fleet mode (WAKE connects)");
    }

    @Test
    void theStatusKeepsWhatIsKnownAndSaysWhatIsNot() {
        FleetProtocol.Status s = new FleetProtocol.Status(88, true, 3, 1, 14, 312, 2, 5, 77);
        byte[] b = s.bytes();
        assertEquals(14, b.length);
        FleetProtocol.Status r = FleetProtocol.Status.read(b, 0, b.length);
        assertEquals(88, r.batteryPercent);
        assertTrue(r.charging);
        assertEquals(3, r.signal);
        assertEquals(1, r.gpsFix);
        assertEquals(14, r.sats);
        assertEquals(312, r.batteryTempDeci);
        assertEquals(2, r.mode);
        assertEquals(5, r.intervalMin);
        assertEquals(77, r.token);

        FleetProtocol.Status none = new FleetProtocol.Status(-1, false, -1, -1, -1, FleetProtocol.TEMP_UNKNOWN, -1, 0, 0);
        FleetProtocol.Status n = FleetProtocol.Status.read(none.bytes(), 0, 14);
        assertEquals(FleetProtocol.UNKNOWN, n.batteryPercent);
        assertEquals(FleetProtocol.UNKNOWN, n.signal);
        assertEquals(FleetProtocol.UNKNOWN, n.sats);
        assertFalse(n.tempKnown());
        assertNull(FleetProtocol.Status.read(none.bytes(), 0, 10), "cut short: none");
    }

    @Test
    void pcBuildIsReadFromTheVersion() {
        assertEquals(40, FleetProtocol.pcBuild("2.3.0-pc.40"));
        assertEquals(39, FleetProtocol.pcBuild("2.3.0-pc.39"));
        assertEquals(-1, FleetProtocol.pcBuild("2.3.0"));
        assertEquals(-1, FleetProtocol.pcBuild(null));
    }

    /** The name reply as a pc.40 phone builds it: name, position block, power byte, then the status. */
    private static byte[] reply(boolean withFix, boolean withStatus) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(Names.NAME_REPLY);
        out.writeUTF(KEY);
        out.writeByte(0);
        out.writeUTF("MobileAlpha");
        out.writeUTF("phone");
        out.writeUTF("2.3.0-pc.40");
        out.writeByte(1);
        out.writeByte(withFix ? 1 : 0);
        out.writeInt(599_000_000);
        out.writeInt(105_000_000);
        out.writeShort(3);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(3); // overwatch
        if (withStatus) new FleetProtocol.Status(51, false, 2, 0, 5, 280, 3, 2, 99).writeTo(out);
        return bytes.toByteArray();
    }

    @Test
    void theNameReplyCarriesTheStatusAfterThePowerByteFixOrNot() throws Exception {
        byte[] d = reply(true, true);
        Names.Reply r = Names.parseReply(d, d.length, KEY);
        assertNotNull(r.position());
        assertEquals(3, r.powerMode());
        assertNotNull(r.status());
        assertEquals(51, r.status().batteryPercent);
        assertEquals(99, r.status().token);

        d = reply(false, true);
        r = Names.parseReply(d, d.length, KEY);
        assertNull(r.position(), "no fix: no position");
        assertEquals(3, r.powerMode(), "the power byte all the same");
        assertNotNull(r.status(), "and the status");

        d = reply(true, false);
        r = Names.parseReply(d, d.length, KEY);
        assertNull(r.status(), "a pc.39 phone: no status");
        assertEquals(3, r.powerMode());
    }

    @Test
    void theSleepHelloCarriesItAfterTheFloorReason() {
        byte[] status = new FleetProtocol.Status(40, false, 1, -1, -1, 250, 2, 1, 5).bytes();
        byte[] d = new byte[8 + status.length];
        d[0] = ControlLink.SLEEP_HELLO;
        d[1] = 40;
        d[3] = 1;
        System.arraycopy(status, 0, d, 8, status.length);
        ControlLink.SleepHello h = ControlLink.SleepHello.read(d, d.length, 1000);
        assertEquals(40, h.batteryPercent());
        assertNotNull(h.status());
        assertEquals(5, h.status().token);
        assertEquals(250, h.status().batteryTempDeci);
        assertNull(ControlLink.SleepHello.read(d, 8, 1000).status(), "a pc.39 hello: none");
    }
}
