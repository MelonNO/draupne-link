package de.droiddrone.pc;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.pc.setup.Tailscale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.64, the name service's part in "other PCs' books go wrong": each phone's own answer told on with its mode, the
 * check-in's interval into the scan's pace, no "sleep on" behind a queued mode, and a rename that is sent until the
 * phone takes it - or put back.
 */
class NameServiceBooksTest {
    private static final String IP = "127.0.0.1";

    /** A phone on loopback: answers queries as pc.40 does (position block, power byte, status), takes a name - or loses the assign. */
    private static final class FakePhone {
        final DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        volatile String name;
        volatile int mode, intervalMin = 5;
        /** NAME_ASSIGNs still to lose before one is taken (Integer.MAX_VALUE: never taken). */
        final AtomicInteger assignsToLose = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger(), assigns = new AtomicInteger();

        FakePhone(String name, int mode) throws Exception {
            this.name = name;
            this.mode = mode;
            socket.setSoTimeout(100);
            Thread t = new Thread(this::run, "fake-phone");
            t.setDaemon(true);
            t.start();
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
                    if (d[0] == Names.NAME_ASSIGN) {
                        assigns.incrementAndGet();
                        if (assignsToLose.get() > 0) {
                            assignsToLose.decrementAndGet();
                            continue; // lost on its way
                        }
                        name = Names.parseAssign(d, len, "DD");
                    } else if (d[0] == Names.NAME_QUERY) {
                        queries.incrementAndGet();
                    } else {
                        continue;
                    }
                    byte[] r = reply();
                    socket.send(new DatagramPacket(r, r.length, packet.getAddress(), packet.getPort()));
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    return;
                }
            }
        }

        byte[] reply() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(Names.reply("DD", Names.Kind.PHONE, name, "Pixel 8", "2.3.0-pc.47"));
            out.writeByte(1);   // block version
            out.writeByte(2);   // the phone's own fix
            out.writeInt(599_100_000);
            out.writeInt(107_500_000);
            out.writeShort(3);
            out.writeByte(0);   // disarmed
            out.writeByte(0);   // nobody flies it
            out.writeByte(0);
            out.writeByte(mode);
            new FleetProtocol.Status(70, false, 3, 1, 9, 250, mode, intervalMin, 0).writeTo(out);
            return bytes.toByteArray();
        }

        void close() {
            socket.close();
        }
    }

    private FakePhone phone;
    private NameService service;
    private final PcConfig config;
    private final Map<String, String> saved = new HashMap<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private Names names;

    NameServiceBooksTest() throws Exception {
        config = PcConfig.defaults();
    }

    private void save(Map<String, String> m) {
        saved.putAll(m);
        m.forEach(config::set);
    }

    /** A service whose (fake) Tailscale network has the one phone, at the phone's own port; no virtual fleet of another test's. */
    private NameService service(FakePhone p) {
        names = new Names(config, this::save);
        Tailscale.Peer peer = new Tailscale.Peer("pixel-8", "android", true, IP, IP + ":1", "");
        Tailscale.Status status = new Tailscale.Status(true, true, "Running", "", "127.0.0.2", "this-pc", List.of(peer));
        NameService s = new NameService(config, names, () -> status, p.port(), false, this::save, log::add);
        s.setVirtualDrones(List::of);
        return s;
    }

    @AfterEach
    void stop() {
        if (service != null) service.stop();
        if (phone != null) phone.close();
    }

    /**
     * Item 1a/1b: the phone's own answer goes on as it comes in, with the mode it says - and only then: a resting phone
     * the scan merely holds online (not asked this round) tells nothing.
     */
    @Test
    void aPhonesOwnAnswerIsToldOnWithItsModeAndAHeldOneIsNot() throws Exception {
        phone = new FakePhone("MobileAlpha", 2);
        service = service(phone);
        List<String> told = new ArrayList<>();
        service.setPhoneReplies((ip, reply, mode) -> told.add(ip + " " + reply.name() + " " + mode + " every " + reply.status().intervalMin));
        service.scan();
        assertEquals(List.of(IP + " MobileAlpha 2 every 5"), told);
        assertEquals(2, service.saidMode(IP));
        assertTrue(service.saidFresh(IP));
        assertFalse(service.saidFresh("100.64.0.99"), "never heard");
        service.scan(); // asleep: not asked again so soon - held online, no answer of its own
        assertEquals(1, told.size(), "held, not heard: nothing told");
        assertEquals(1, phone.queries.get());
        assertTrue(names.get("MobileAlpha").online());
        // a throwing listener doesn't end the scan
        phone.mode = 0;
        NameService second = service(phone);
        try {
            second.setPhoneReplies((ip, reply, mode) -> {
                throw new IllegalStateException("listener");
            });
            second.scan();
            assertTrue(names.get("MobileAlpha").online());
            assertTrue(log.stream().anyMatch(l -> l.contains("listener")), log.toString());
        } finally {
            second.stop();
        }
    }

    /**
     * Item 1d: the check-in's own "next check in" reaches the scan's pace. It was dropped (0), so a timed sleeper known
     * only by its hellos counted as listening, asked every minute.
     */
    @Test
    void aCheckInsIntervalReachesTheScansPace() throws Exception {
        phone = new FakePhone("MobileAlpha", 2);
        service = service(phone);
        service.heardCheckIn(IP, 2, 5);
        service.scan();
        assertEquals(5, service.pacedEvery(IP));
        assertEquals(0, phone.queries.get(), "its hello said it all: not asked");
        assertEquals(2, service.saidMode(IP));
        // the where-block's part of the same hello (no interval in it) keeps it
        service.heardCheckIn(IP, 2, 10);
        service.heardAsleep(IP, new FleetProtocol.Where("MobileAlpha", 0, 0, 0, 0), 2);
        service.scan();
        assertEquals(10, service.pacedEvery(IP));
    }

    /**
     * Item 5: a rename was one NAME_ASSIGN. Lost, the phone went on answering with its old name and both names sat at
     * one address. The assign goes again with each scan until the phone answers with the new name; its old name isn't
     * filed again meanwhile.
     */
    @Test
    void aRenameIsSentAgainUntilThePhoneAnswersWithIt() throws Exception {
        phone = new FakePhone("MobileAlpha", 0);
        service = service(phone);
        service.scan();
        assertNotNull(names.get("MobileAlpha"));
        save(Map.of("videoUpsideDown.MobileAlpha", "true", Names.PHONE_NAME_KEY, "MobileAlpha"));
        List<String> renames = new ArrayList<>();
        names.onRenamed((from, to) -> renames.add(from + ">" + to));
        phone.assignsToLose.set(2);

        assertFalse(service.rename(IP, "MobileAlpha", "PCKilo"), "not a phone's name");
        assertTrue(service.rename(IP, "MobileAlpha", "MobileKilo"));
        assertEquals(List.of("MobileAlpha>MobileKilo"), renames);
        assertNull(names.get("MobileAlpha"));
        assertEquals("true", config.getString("videoUpsideDown.MobileKilo"), "its settings went with it");
        assertEquals("MobileKilo", config.getString(Names.PHONE_NAME_KEY), "and it stays the chosen phone");
        assertTrue(service.renaming(IP));

        service.scan(); // the first assign and this scan's were lost: it answers "MobileAlpha" still
        assertEquals("MobileAlpha", phone.name);
        assertNull(names.get("MobileAlpha"), "its old name is not filed again beside the new one");
        assertTrue(names.get("MobileKilo").online(), "the phone is there all the same");
        assertTrue(service.renaming(IP));
        service.scan(); // the third reaches it
        assertEquals("MobileKilo", phone.name);
        assertEquals(3, phone.assigns.get());
        assertFalse(service.renaming(IP));
        assertNull(names.get("MobileAlpha"));
        assertEquals(IP, names.ipOf("MobileKilo"));
        service.scan();
        assertEquals(3, phone.assigns.get(), "taken: not sent again");
        assertTrue(log.stream().noneMatch(l -> l.contains("didn't take")), log.toString());
    }

    /** Item 5: ... and when it never does, that is said and the old name comes back - with what went with it. */
    @Test
    void aRenameThePhoneNeverTakesIsPutBack() throws Exception {
        phone = new FakePhone("MobileAlpha", 0);
        service = service(phone);
        service.assignKeepMs = 2500; // two scans' worth (a scan waits 1.5 s for answers)
        service.scan();
        save(Map.of("videoUpsideDown.MobileAlpha", "true", Names.PHONE_NAME_KEY, "MobileAlpha"));
        List<String> renames = new ArrayList<>();
        names.onRenamed((from, to) -> renames.add(from + ">" + to));
        phone.assignsToLose.set(Integer.MAX_VALUE);

        assertTrue(service.rename(IP, "MobileAlpha", "MobileKilo"));
        service.scan();
        assertNotNull(names.get("MobileKilo"), "still waiting for it");
        assertTrue(phone.assigns.get() >= 2, "sent again with the scan");
        for (int i = 0; i < 4 && service.renaming(IP); i++) service.scan(); // until 2.5 s have passed since the rename
        assertFalse(service.renaming(IP));
        assertTrue(log.contains("MobileAlpha didn't take the name MobileKilo."), log.toString());
        assertNull(names.get("MobileKilo"));
        assertEquals(IP, names.ipOf("MobileAlpha"));
        assertEquals(List.of("MobileAlpha>MobileKilo", "MobileKilo>MobileAlpha"), renames, "what else is kept by name goes back too");
        assertEquals("true", config.getString("videoUpsideDown.MobileAlpha"));
        assertEquals("", config.getString("videoUpsideDown.MobileKilo"));
        assertEquals("MobileAlpha", config.getString(Names.PHONE_NAME_KEY));
        int sent = phone.assigns.get();
        service.scan();
        assertEquals(sent, phone.assigns.get(), "given up: not sent again");
        assertTrue(names.get("MobileAlpha").online(), "and its answers are filed under its name again");
    }

    /**
     * Item 1e: with a mode queued for the phone, its hello got the mode's packet and then "sleep on" (SLEEP_ANSWER 0) at
     * once. Was the mode lost and the answer not, the phone's window closed and the mode waited another interval. No
     * "sleep on" behind a mode; the hello after it is answered as ever.
     */
    @Test
    void noSleepOnBehindAQueuedMode() throws Exception {
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        Tailscale.Status status = new Tailscale.Status(true, true, "Running", "", "127.0.0.2", "this-pc", List.of());
        names = new Names(config, this::save);
        service = new NameService(config, names, () -> status, port, true, this::save, log::add);
        service.setVirtualDrones(List::of);
        service.setSleepHandler((ip, d) -> false);
        byte[] mode = FleetProtocol.request("DD", new FleetProtocol.Request(7, 1, 5, 0, 0, 0, port));
        byte[][] queued = {null};
        service.setFleet(null, (ip, hello) -> queued[0]);
        service.start();
        try (DatagramSocket phoneSocket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            byte[] hello = {ControlLink.SLEEP_HELLO, 80, 0, 5, 0x0F, (byte) 0xA0};
            DatagramPacket in = new DatagramPacket(new byte[64], 64);
            boolean up = false;
            phoneSocket.setSoTimeout(500);
            for (int i = 0; i < 12 && !up; i++) { // the service takes the port within a round
                phoneSocket.send(new DatagramPacket(hello, hello.length, InetAddress.getLoopbackAddress(), port));
                try {
                    phoneSocket.receive(in);
                    up = in.getData()[0] == ControlLink.SLEEP_ANSWER;
                } catch (SocketTimeoutException ignored) {
                }
            }
            assertTrue(up, "nothing queued: the check-in is answered");
            assertEquals(0, in.getData()[1], "sleep on");

            queued[0] = mode;
            phoneSocket.send(new DatagramPacket(hello, hello.length, InetAddress.getLoopbackAddress(), port));
            List<Byte> got = new ArrayList<>();
            long end = System.currentTimeMillis() + 800;
            phoneSocket.setSoTimeout(200);
            while (System.currentTimeMillis() < end) {
                try {
                    phoneSocket.receive(in);
                    got.add(in.getData()[0]);
                } catch (SocketTimeoutException ignored) {
                }
            }
            assertEquals(List.of(FleetProtocol.MODE), got, "the mode, and no 'sleep on' to close the window on it");

            queued[0] = null;
            phoneSocket.setSoTimeout(1000);
            phoneSocket.send(new DatagramPacket(hello, hello.length, InetAddress.getLoopbackAddress(), port));
            phoneSocket.receive(in);
            assertEquals(ControlLink.SLEEP_ANSWER, in.getData()[0], "taken (nothing queued any more): answered as ever");
        }
    }
}
