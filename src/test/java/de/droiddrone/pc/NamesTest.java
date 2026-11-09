package de.droiddrone.pc;

import de.droiddrone.pc.setup.Tailscale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** DRAUPNE names (1.0.35): the letters, the map, the packets, and the service that asks the network. */
class NamesTest {
    private FakeDevice phone, otherPc;
    private NameService service;

    @AfterEach
    void stop() {
        if (service != null) service.stop();
        if (phone != null) phone.close();
        if (otherPc != null) otherPc.close();
    }

    /**
     * The tour's virtual pilot PC (2026-11-09): its name was "PilotPC", never filed, so the spectator step waited
     * forever. Now a free PC name; and though it may have the highest letter, it never takes the naming over.
     */
    @Test
    void theTourPilotPcIsFiledAndNeverTheMaster() throws Exception {
        Names names = new Names(PcConfig.defaults(), m -> { });
        String free = names.firstFree(Names.Kind.PC, List.of("PCAlpha"));
        assertEquals("PCBravo", free);
        assertTrue(Names.isValidName(free, Names.Kind.PC));
        names.seen(new Names.Reply(Names.Kind.PC, free, de.droiddrone.pc.fleet.VirtualPilotPc.DEVICE, "1.0.57"), "127.0.0.60", "");
        assertNotNull(names.get(free), "filed: it shows in the PILOT PC list");
        assertTrue(names.isMaster("PCAlpha"), "the virtual PC's higher letter doesn't count");
        names.seen(new Names.Reply(Names.Kind.PC, "PCCharlie", "Desktop", "1.0.57"), "100.64.0.7", "");
        assertFalse(names.isMaster("PCAlpha"), "a real PC's does");
    }

    @Test
    void lettersNamesAndTheMap() throws Exception {
        assertEquals("MobileAlpha", Names.Kind.PHONE.name(0));
        assertEquals("PCZulu", Names.Kind.PC.name(25));
        assertEquals(Names.Kind.PHONE, Names.kindOf("MobileCharlie"));
        assertEquals(Names.Kind.PC, Names.kindOf("PCBravo"));
        assertNull(Names.kindOf("Pixel"));
        assertEquals(2, Names.letterOf("MobileCharlie"));
        assertEquals(-1, Names.letterOf("Mobile"));

        Map<String, String> saved = new HashMap<>();
        Names names = new Names(PcConfig.defaults(), saved::putAll);
        assertEquals("MobileAlpha", names.firstFree(Names.Kind.PHONE, List.of()));
        assertEquals("MobileBravo", names.firstFree(Names.Kind.PHONE, List.of("MobileAlpha")));
        assertNull(names.seen(new Names.Reply(Names.Kind.PHONE, "MobileAlpha", "Pixel 8", "2.3.0-pc.28"), "100.64.0.5", "pixel-8"));
        assertEquals("MobileBravo", names.firstFree(Names.Kind.PHONE, List.of()));
        assertEquals("100.64.0.5", names.ipOf("MobileAlpha"));
        assertEquals("MobileAlpha", names.nameAt("100.64.0.5"));
        assertTrue(names.get("mobilealpha").online(), "names are not case-sensitive");
        assertEquals("100.64.0.5", names.seen(new Names.Reply(Names.Kind.PHONE, "MobileAlpha", "Pixel 8", "2.3.0-pc.28"), "100.64.0.9", "pixel-8"),
                "a name that moved: the old address is reported");
        assertEquals("100.64.0.9", names.ipOf("MobileAlpha"));
        names.onlineOnly(List.of());
        assertFalse(names.get("MobileAlpha").online());
        assertTrue(saved.containsKey("name.MobileAlpha"), "kept in the settings: " + saved);

        // the map is read back from the settings
        PcConfig config = PcConfig.defaults();
        saved.forEach(config::set);
        Names again = new Names(config, null);
        Names.Entry e = again.get("MobileAlpha");
        assertNotNull(e);
        assertEquals("100.64.0.9", e.ip());
        assertEquals("Pixel 8", e.device());
        assertEquals("pixel-8", e.host());
        assertFalse(e.online(), "offline until seen again");
        assertEquals("Pixel 8  (pixel-8) - 2.3.0-pc.28", e.detail());

        // the master: the highest PC letter online
        again.seen(new Names.Reply(Names.Kind.PC, "PCCharlie", "pc3", "1.0.35"), "100.64.0.30", "pc3");
        assertFalse(again.isMaster("PCAlpha"), "PCCharlie is online and higher");
        assertTrue(again.isMaster("PCDelta"));
        again.onlineOnly(List.of());
        assertTrue(again.isMaster("PCAlpha"), "alone online: the master");
        assertFalse(again.isMaster(""), "a PC without a name is never the master");
    }

    @Test
    void thePacketsRoundTrip() {
        byte[] q = Names.query("DD");
        assertEquals(Names.NAME_QUERY, q[0]);
        assertEquals("DD", Names.keyOf(q, q.length));
        byte[] r = Names.reply("DD", Names.Kind.PHONE, "", "Pixel 8", "2.3.0-pc.28");
        Names.Reply reply = Names.parseReply(r, r.length, "DD");
        assertNotNull(reply);
        assertEquals(Names.Kind.PHONE, reply.kind());
        assertEquals("", reply.name());
        assertEquals("Pixel 8", reply.device());
        assertNull(Names.parseReply(r, r.length, "other"), "another key: not ours");
        byte[] a = Names.assign("DD", "MobileBravo");
        assertEquals("MobileBravo", Names.parseAssign(a, a.length, "DD"));
        assertNull(Names.parseAssign(a, a.length, "xx"));
        assertNull(Names.parseAssign(q, q.length, "DD"), "a query is no assign");
        assertEquals("2 min ago", Names.agoText(1000, 1000 + 150_000));
        assertEquals("just now", Names.agoText(1000, 2000));
    }

    /** H5 (1.0.49): only DRAUPNE names come in from the network; device and version texts are made printable. */
    @Test
    void onlyValidNamesAreTakenFromTheNetwork() {
        byte[] bad = Names.reply("DD", Names.Kind.PHONE, "MobileAlpha\nrcRate=1000", "x", "y");
        assertNull(Names.parseReply(bad, bad.length, "DD"), "a name with a line in it");
        byte[] other = Names.reply("DD", Names.Kind.PHONE, "Pixel", "x", "y");
        assertNull(Names.parseReply(other, other.length, "DD"), "not one of the 26");
        byte[] wrongKind = Names.reply("DD", Names.Kind.PHONE, "PCAlpha", "x", "y");
        assertNull(Names.parseReply(wrongKind, wrongKind.length, "DD"), "a phone with a PC's name");
        byte[] ok = Names.reply("DD", Names.Kind.PHONE, "MobileX-ray", "Pixel\n8|evil\u0000" + "x".repeat(200), "2.3.0-pc.38\r\nkey=1");
        Names.Reply r = Names.parseReply(ok, ok.length, "DD");
        assertNotNull(r);
        assertEquals("MobileX-ray", r.name());
        assertTrue(r.device().startsWith("Pixel 8 evil x"), r.device());
        assertEquals(Names.MAX_TEXT, r.device().length(), "cut to its length");
        assertEquals("2.3.0-pc.38  key=1", r.version());
        byte[] assign = Names.assign("DD", "MobileAlpha=1\nip=6.6.6.6");
        assertNull(Names.parseAssign(assign, assign.length, "DD"));
        assertTrue(Names.isValidName("MobileNovember", Names.Kind.PHONE));
        assertFalse(Names.isValidName("Mobile November", Names.Kind.PHONE));
    }

    /** M3 (1.0.49): two devices with one name in a scan are found, kept a while, and cleared when one is renamed. */
    @Test
    void aDuplicateNameIsFoundAndClearedByARename() throws Exception {
        PcConfig config = PcConfig.defaults();
        List<String> log = new CopyOnWriteArrayList<>();
        NameService svc = new NameService(config, new Names(config, null), () -> null, 1, false, m -> { }, log::add);
        Map<String, List<String>> claims = new java.util.LinkedHashMap<>();
        claims.put("mobilealpha", List.of("100.64.0.5", "100.64.0.9"));
        claims.put("mobilebravo", List.of("100.64.0.7"));
        svc.noteDuplicates(claims);
        assertEquals(List.of("100.64.0.5", "100.64.0.9"), svc.duplicateOf("MobileAlpha"));
        assertNull(svc.duplicateOf("MobileBravo"));
        assertEquals(1, log.size(), "said once: " + log);
        svc.noteDuplicates(claims);
        assertEquals(1, log.size(), "not again while it lasts");
        // one reply lost: still a duplicate
        svc.noteDuplicates(Map.of("mobilealpha", List.of("100.64.0.5")));
        assertNotNull(svc.duplicateOf("MobileAlpha"), "one scan without the other phone's answer proves nothing");
        // the other phone was renamed: it answers as MobileCharlie now
        svc.noteDuplicates(Map.of("mobilealpha", List.of("100.64.0.5"), "mobilecharlie", List.of("100.64.0.9")));
        assertNull(svc.duplicateOf("MobileAlpha"));
    }

    /**
     * The service asks the online devices of a (fake) Tailscale network, files their answers, names this PC with the
     * first free PC letter, is the master when its letter is the highest online, and names the nameless phone.
     */
    @Test
    void theServiceNamesThePcAndTheNamelessPhone() throws Exception {
        phone = new FakeDevice(Names.Kind.PHONE, "", "Pixel 8");
        otherPc = new FakeDevice(Names.Kind.PC, "PCAlpha", "other-pc");
        // both fake devices listen on their own ports, so the service is told the port of the one it asks:
        // the "network" has one peer per service port - two services, one per fake device
        PcConfig config = PcConfig.defaults();
        Map<String, String> saved = new HashMap<>();
        Names names = new Names(config, m -> {
            saved.putAll(m);
            m.forEach(config::set);
        });
        List<String> log = new CopyOnWriteArrayList<>();
        Tailscale.Peer phonePeer = new Tailscale.Peer("pixel-8", "android", true, "127.0.0.1", "127.0.0.1:1", "");
        Tailscale.Status status = new Tailscale.Status(true, true, "Running", "", "127.0.0.2", "this-pc", List.of(phonePeer));
        service = new NameService(config, names, () -> status, phone.port(), false, m -> {
            saved.putAll(m);
            m.forEach(config::set);
        }, log::add);
        service.scan();
        assertEquals("PCAlpha", config.getString(Names.PC_NAME_KEY), "the first PC letter: nobody else online");
        assertTrue(service.isMaster());
        waitFor(() -> "MobileAlpha".equals(phone.name), 2000, "the nameless phone was given MobileAlpha");
        service.scan();
        Names.Entry e = names.get("MobileAlpha");
        assertNotNull(e, "the phone answers with its name now: " + names.all());
        assertEquals("127.0.0.1", e.ip());
        assertEquals("Pixel 8", e.device());
        assertEquals("pixel-8", e.host(), "the Tailscale machine name from the peer list");
        assertTrue(e.online());
        assertTrue(saved.containsKey("name.MobileAlpha"));

        // another PC with a higher letter online: this PC is not the master, and a nameless phone is left alone
        phone.name = "";
        NameService second = new NameService(config, names, () -> status, otherPc.port(), false, m -> m.forEach(config::set), log::add);
        try {
            second.scan(); // asks the other PC (it answers PCAlpha): this PC's own name PCAlpha is taken
            assertEquals("PCBravo", config.getString(Names.PC_NAME_KEY), "PCAlpha was taken: the next letter");
            assertTrue(names.get("PCAlpha").online());
            assertTrue(second.isMaster(), "PCBravo is higher than PCAlpha");
            otherPc.name = "PCCharlie";
            second.scan();
            assertFalse(second.isMaster(), "PCCharlie online is higher than PCBravo");
        } finally {
            second.stop();
        }
    }

    /**
     * The virtual fleet: its announced drones are asked like Tailscale peers (at their own ports), filed online and marked
     * virtual, never saved; with nothing announced the scan is what it was (no Tailscale: nothing asked, nobody named).
     */
    @Test
    void theVirtualFleetsDronesAreFoundOnlyWhileAnnounced() throws Exception {
        PcConfig config = PcConfig.defaults();
        Map<String, String> saved = new HashMap<>();
        Names names = new Names(config, saved::putAll);
        try (de.droiddrone.pc.fleet.Fleet fleet = new de.droiddrone.pc.fleet.Fleet(config, null, s -> { })) {
            de.droiddrone.pc.fleet.VirtualDrone d = fleet.add("MobileYankee", de.droiddrone.pc.fleet.Fleet.DEFAULT_LAT, de.droiddrone.pc.fleet.Fleet.DEFAULT_LON, 0);
            List<de.droiddrone.pc.fleet.VirtualFleet.Drone> announced = new CopyOnWriteArrayList<>();
            service = new NameService(config, names, () -> null, 6286, false, saved::putAll, s -> { });
            service.setVirtualDrones(() -> announced);
            service.scan();
            assertTrue(names.all().isEmpty(), "nothing announced, no Tailscale: nothing asked");
            assertEquals("", config.getString(Names.PC_NAME_KEY), "and this PC not named either, as before");
            announced.add(new de.droiddrone.pc.fleet.VirtualFleet.Drone(d.address().getHostAddress(), d.port()));
            service.scan();
            Names.Entry e = names.get("MobileYankee");
            assertNotNull(e, "found at its own port: " + names.all());
            assertTrue(e.online());
            assertTrue(e.isVirtual());
            assertEquals("127.0.0.11", e.ip());
            assertEquals(de.droiddrone.pc.fleet.VirtualDrone.DEVICE, e.device());
            assertFalse(saved.keySet().stream().anyMatch(k -> k.startsWith("name.")), "never saved: " + saved);
            assertTrue(service.dronePositions().stream().anyMatch(p -> p.name().equals("MobileYankee")), "on the map too");
            announced.clear();
            service.scan();
            assertFalse(names.get("MobileYankee").online(), "the fleet gone: offline");
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier ok, int ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    /** A device on loopback that answers queries and takes a name. */
    static final class FakeDevice {
        private final DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        private final Names.Kind kind;
        private final String device;
        volatile String name;
        private final Thread thread;

        FakeDevice(Names.Kind kind, String name, String device) throws Exception {
            this.kind = kind;
            this.name = name;
            this.device = device;
            socket.setSoTimeout(100);
            thread = new Thread(this::run, "fake-" + kind);
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void run() {
            byte[] buf = new byte[512];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (!socket.isClosed()) {
                try {
                    socket.receive(packet);
                    byte[] d = packet.getData();
                    int len = packet.getLength();
                    if (len < 1 || !"DD".equals(Names.keyOf(d, len))) continue;
                    if (d[0] == Names.NAME_QUERY) {
                        byte[] r = Names.reply("DD", kind, name, device, "test");
                        socket.send(new DatagramPacket(r, r.length, packet.getAddress(), packet.getPort()));
                    } else if (d[0] == Names.NAME_ASSIGN && kind == Names.Kind.PHONE) {
                        name = Names.parseAssign(d, len, "DD");
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (Exception e) {
                    return;
                }
            }
        }

        void close() {
            socket.close();
        }
    }

    @Test
    void renameMovesAPhoneToAnotherName() {
        java.util.Map<String, String> saved = new java.util.HashMap<>();
        Names names = new Names(null, saved::putAll);
        names.seen(new Names.Reply(Names.Kind.PHONE, "MobileAlpha", "Pixel 8", "2.3.0-pc.29"), "100.64.0.9", "pixel-8");
        names.seen(new Names.Reply(Names.Kind.PHONE, "MobileBravo", "Pixel 7", "2.3.0-pc.29"), "100.64.0.10", "pixel-7");
        org.junit.jupiter.api.Assertions.assertFalse(names.rename("MobileAlpha", "MobileBravo"), "taken");
        org.junit.jupiter.api.Assertions.assertFalse(names.rename("MobileAlpha", "PCCharlie"), "not a phone name");
        org.junit.jupiter.api.Assertions.assertFalse(names.rename("MobileZulu", "MobileCharlie"), "unknown");
        org.junit.jupiter.api.Assertions.assertTrue(names.rename("MobileAlpha", "MobileCharlie"));
        org.junit.jupiter.api.Assertions.assertNull(names.get("MobileAlpha"));
        org.junit.jupiter.api.Assertions.assertEquals("100.64.0.9", names.ipOf("MobileCharlie"));
        org.junit.jupiter.api.Assertions.assertEquals("", saved.get("name.MobileAlpha"), "the old key cleared");
        org.junit.jupiter.api.Assertions.assertTrue(saved.get("name.MobileCharlie").startsWith("PHONE|100.64.0.9|Pixel 8|"), saved.toString());
    }

    /**
     * A phone put to deep sleep checks in every few minutes at this PC's link port; with no link open the name
     * service holds the port, and before 1.0.41 nothing answered there: a DISCONNECT left no way to wake it.
     */
    @Test
    void aSleepingPhonesCheckInIsAnsweredWhileNoLinkIsOpen() throws Exception {
        int port;
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        PcConfig config = PcConfig.defaults();
        Names names = new Names(config, m -> m.forEach(config::set));
        Tailscale.Status status = new Tailscale.Status(true, true, "Running", "", "127.0.0.2", "this-pc", List.of());
        NameService svc = new NameService(config, names, () -> status, port, true, m -> m.forEach(config::set), s -> { });
        List<String> asked = new CopyOnWriteArrayList<>();
        svc.setSleepHandler((ip, d) -> {
            asked.add(ip + " " + (d[1] & 0xff) + " %");
            return false;
        });
        svc.start();
        try (java.net.DatagramSocket phoneSocket = new java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())) {
            byte[] hello = {ControlLink.SLEEP_HELLO, 80, 0, 5, 0x0F, (byte) 0xA0}; // 80 %, not charging, next check in 5 min
            java.net.DatagramPacket in = new java.net.DatagramPacket(new byte[16], 16);
            boolean answered = false;
            for (int i = 0; i < 12 && !answered; i++) { // the service takes the port within a round
                phoneSocket.send(new java.net.DatagramPacket(hello, hello.length, java.net.InetAddress.getLoopbackAddress(), port));
                phoneSocket.setSoTimeout(500);
                try {
                    phoneSocket.receive(in);
                    answered = in.getData()[0] == ControlLink.SLEEP_ANSWER;
                } catch (java.net.SocketTimeoutException ignored) {
                }
            }
            assertTrue(answered, "the check-in is answered");
            assertEquals(0, in.getData()[1], "sleep on (WAKE connects, and the link answers the next one 'wake')");
            assertEquals("127.0.0.1 80 %", asked.get(0));
        } finally {
            svc.stop();
        }
    }

    /** A phone's name reply says where its drone is (phone app pc.32), and an older reply still reads (no position). */
    @Test
    void aPhonesReplyCarriesWhereItsDroneIs() throws Exception {
        byte[] old = Names.reply("DD", Names.Kind.PHONE, "MobileAlpha", "Pixel 8", "2.3.0-pc.31");
        Names.Reply r = Names.parseReply(old, old.length, "DD");
        assertEquals("MobileAlpha", r.name());
        assertNull(r.position(), "pc.31 says nothing of it");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bytes.write(Names.reply("DD", Names.Kind.PHONE, "MobileBravo", "XR", "2.3.0-pc.32"));
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeByte(1);       // block version
        out.writeByte(1);       // the FC's GPS
        out.writeInt(599_123_456);
        out.writeInt(107_654_321);
        out.writeShort(3);      // 3 s old
        out.writeByte(1);       // armed
        out.writeByte(1);       // flown
        out.writeByte(4);
        out.write(new byte[]{127, 0, 0, 7});
        byte[] p = bytes.toByteArray();
        Names.Reply n = Names.parseReply(p, p.length, "DD");
        assertEquals("2.3.0-pc.32", n.version());
        Names.Position pos = n.position();
        assertNotNull(pos);
        assertTrue(pos.fromFc());
        assertEquals(59.9123456, pos.lat(), 1e-7);
        assertEquals(10.7654321, pos.lon(), 1e-7);
        assertEquals(3, pos.ageS());
        assertEquals(Boolean.TRUE, pos.armed());
        assertTrue(pos.flown());
        assertEquals("127.0.0.7", pos.pilotIp());
        // no fix: nothing to put on the map
        byte[] none = java.util.Arrays.copyOf(old, old.length + 18);
        none[old.length] = 1; // version 1, source 0, the rest zero
        assertNull(Names.parseReply(none, none.length, "DD").position());
    }

    /** pc.39: a phone's reply says its power mode after its position; an older one's says nothing (unknown). */
    @Test
    void aReplySaysThePhonesPowerMode() throws Exception {
        for (int mode : new int[]{-1, 0, 1, 2, 3}) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            out.write(Names.reply("DD", Names.Kind.PHONE, "MobileBravo", "XR", "2.3.0-pc.39"));
            out.writeByte(1);
            out.writeByte(1);
            out.writeInt(599_139_000);
            out.writeInt(107_522_000);
            out.writeShort(2);
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
            if (mode >= 0) out.writeByte(mode);
            byte[] d = bytes.toByteArray();
            Names.Reply r = Names.parseReply(d, d.length, "DD");
            assertNotNull(r.position());
            assertEquals(mode, r.position().powerMode());
        }
    }
}
