package de.droiddrone.pc;

import de.droiddrone.pc.observe.ObserverLink;
import de.droiddrone.testing.Ports;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static de.droiddrone.pc.LinkPlaceTest.held;
import static org.junit.jupiter.api.Assertions.*;

/**
 * S-LINK-2 on the PC's link: it listens for phones and other PCs on this PC's Tailscale address only - loopback for this
 * PC's own (the virtual fleet, the tests) - so what is sent to another address of this PC reaches none of its
 * sockets. No Tailscale address: the link waits ("No Tailscale") and starts by itself when it comes, and follows it
 * when it changes or goes. A peer at another address (an old LAN setup) is refused in short words.
 * <p>
 * As in {@link LinkPlaceTest}, a second loopback address stands in for this PC's Tailscale address.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TailscaleLinkTest {
    private static final String KEY = "DD";
    private static final InetAddress LOCAL = ip("127.0.0.1"), STAND_IN = ip("127.0.0.2"), STAND_IN_2 = ip("127.0.0.3");
    /** A made-up phone on Tailscale: never reached - a socket on a loopback stand-in sends nothing off this PC. */
    private static final InetAddress PHONE = ip("100.64.0.9");
    private final List<AutoCloseable> open = new CopyOnWriteArrayList<>();

    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s); // numbers only: nothing is looked up
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable c : open) c.close();
    }

    private static PcConfig config(String ip, int port) throws IOException {
        PcConfig c = PcConfig.defaults();
        c.set("key", KEY);
        c.set("ip", ip);
        c.set("port", Integer.toString(port));
        return c;
    }

    // ------------------------------------------------------------------ another address of this PC is not served

    /** The link to a phone on loopback answers a name query sent to 127.0.0.1, and nothing sent to 127.0.0.2 - its stand-in for a LAN address. */
    @Test
    void theLinkTakesNothingSentToAnotherAddressOfThisPc() throws Exception {
        int port = Ports.free(), phonePort = Ports.free();
        ControlLink link = new ControlLink(config("127.0.0.1", port), LOCAL, phonePort, port);
        open.add(link::close);
        link.setNameReply(() -> Names.reply(KEY, Names.Kind.PC, "PCAlpha", "test", "1.0"));
        link.start();
        try (DatagramSocket other = new DatagramSocket(new InetSocketAddress(LOCAL, 0))) {
            other.setSoTimeout(600);
            byte[] query = Names.query(KEY);
            DatagramPacket in = new DatagramPacket(new byte[512], 512);
            other.send(new DatagramPacket(query, query.length, STAND_IN, port));
            assertThrows(SocketTimeoutException.class, () -> other.receive(in), "sent to another address of this PC: not taken");
            other.send(new DatagramPacket(query, query.length, LOCAL, port));
            other.receive(in);
            assertNotNull(Names.parseReply(in.getData(), in.getLength(), KEY), "sent to the link's own address: answered");
        }
        assertFalse(held(STAND_IN, port), "the link holds 127.0.0.1 alone");
    }

    // ------------------------------------------------------------------ an old LAN setup

    @Test
    void aPhoneAtALanAddressIsRefusedInShortWords() throws Exception {
        int port = Ports.free();
        IOException e = assertThrows(IOException.class, () -> Connection.open(config("192.0.2.5", port)));
        assertEquals("Not a Tailscale address: 192.0.2.5", e.getMessage(), "what the pilot reads");
        try (DatagramSocket every = new DatagramSocket(port)) { // nothing was opened for it, on any address
            assertTrue(every.isBound());
        }
        assertThrows(LinkPlace.NotTailscale.class, () -> new MirrorClient(ip("198.51.100.7"), port, KEY, new MirrorClient.Listener() {
            @Override
            public void state(Map<String, byte[]> sections) {
            }

            @Override
            public void tile(int tile, byte[] d, int len) {
            }
        }), "watching a PC at a LAN address: the same words");
        ObserverLink observer = new ObserverLink("MobileAlpha", ip("203.0.113.9"), port, KEY, null, null, -1, s -> { });
        assertThrows(LinkPlace.NotTailscale.class, observer::start, "observing a phone at a LAN address: the same");
    }

    // ------------------------------------------------------------------ no Tailscale, then Tailscale

    @Test
    void noTailscaleTheLinkWaitsAndStartsWhenTheAddressComesThenFollowsIt() throws Exception {
        int port = Ports.free();
        OwnTailscale.Found[] now = {OwnTailscale.Found.NONE};
        OwnTailscale own = new OwnTailscale(() -> now[0], 20);
        open.add(own);
        ControlLink link = new ControlLink(config(PHONE.getHostAddress(), port), PHONE, port, port);
        open.add(link::close);
        List<String> events = new CopyOnWriteArrayList<>();
        link.setOwnTailscale(own);
        link.setEvents(events::add);
        link.start();

        assertTrue(link.waitsForTailscale(), "no Tailscale address: the link waits");
        assertEquals(ControlLink.State.CONNECTING, link.getState());
        assertEquals("No Tailscale on this PC: the link waits for it.", Connection.describe(link, link.getState()));
        assertFalse(held(LOCAL, port), "nothing at the link port meanwhile");

        now[0] = new OwnTailscale.Found(STAND_IN, null); // Tailscale comes up
        Waits.waitFor(() -> !link.waitsForTailscale(), 3000, "the link starting by itself");
        assertTrue(held(STAND_IN, port), "on this PC's Tailscale address at the link port");
        assertTrue(Connection.describe(link, link.getState()).startsWith("Waiting for the phone"), "and it calls the phone as ever");
        Waits.waitFor(() -> events.contains("Tailscale is up: the link goes on."), 3000, "said in the log", events);

        now[0] = new OwnTailscale.Found(STAND_IN_2, null); // its address changes
        Waits.waitFor(() -> held(STAND_IN_2, port) && !held(STAND_IN, port), 3000, "opened anew on the new address");

        now[0] = OwnTailscale.Found.NONE; // it goes in flight
        Waits.waitFor(link::waitsForTailscale, 3000, "waiting again");
        Waits.waitFor(() -> events.contains("No Tailscale on this PC: the link waits for it."), 3000, "said in the log", events);
        assertFalse(held(STAND_IN_2, port));
        link.close();
        assertEquals(0, own.following(), "a closed link follows nothing");
    }

    // ------------------------------------------------------------------ the name service's two listeners

    @Test
    void theLinkPortIsListenedOnAtTheTailscaleAddressAndLoopbackAndTheLinkTakesOneSide() throws Exception {
        int port = Ports.free();
        OwnTailscale own = new OwnTailscale(() -> new OwnTailscale.Found(STAND_IN, null), 20);
        open.add(own);
        PcConfig config = config("", port);
        NameService svc = new NameService(config, new Names(config, m -> m.forEach(config::set)), () -> null, port, true,
                m -> { }, s -> { }, own);
        open.add(svc::stop);
        svc.setVirtualDrones(List::of);
        List<String> asked = new CopyOnWriteArrayList<>();
        svc.setSleepHandler((ip, d) -> {
            asked.add(ip);
            return false;
        });
        svc.start();
        Waits.waitFor(() -> held(LOCAL, port) && held(STAND_IN, port), 3000, "both sides listened on");
        assertFalse(held(STAND_IN_2, port), "and no other address");

        svc.pause(PHONE.getHostAddress()); // a link to a phone on Tailscale takes that side
        Waits.waitFor(() -> !held(STAND_IN, port), 3000, "the Tailscale side let go for the link");
        assertTrue(checkInAnswered(port), "a check-in on loopback (the virtual fleet) still answered meanwhile");
        assertTrue(held(LOCAL, port));
        svc.resume();
        Waits.waitFor(() -> held(STAND_IN, port), 3000, "taken back when the link closed");

        svc.pause("127.0.0.11"); // a link to a virtual drone takes loopback
        Waits.waitFor(() -> !held(LOCAL, port), 3000, "loopback let go for the link");
        assertTrue(held(STAND_IN, port), "Tailscale still listened on");
        svc.resume();
        svc.pause("192.0.2.5"); // refused by the link: nothing let go
        Waits.waitFor(() -> held(LOCAL, port) && held(STAND_IN, port), 3000, "both again");
        svc.stop();
        Waits.waitFor(() -> !held(LOCAL, port) && !held(STAND_IN, port), 3000, "both let go at the end");
    }

    private boolean checkInAnswered(int port) throws Exception {
        try (DatagramSocket drone = new DatagramSocket(new InetSocketAddress(ip("127.0.0.11"), 0))) {
            drone.setSoTimeout(500);
            byte[] hello = {ControlLink.SLEEP_HELLO, 80, 0, 5, 0x0F, (byte) 0xA0};
            DatagramPacket in = new DatagramPacket(new byte[16], 16);
            for (int i = 0; i < 6; i++) {
                drone.send(new DatagramPacket(hello, hello.length, LOCAL, port));
                try {
                    drone.receive(in);
                    if (in.getData()[0] == ControlLink.SLEEP_ANSWER) return true;
                } catch (SocketTimeoutException ignored) {
                }
            }
            return false;
        }
    }
}
