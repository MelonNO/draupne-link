package de.droiddrone.pc;

import de.droiddrone.common.UdpCommon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The mirror (1.0.56) on loopback, ephemeral ports both sides: a spectator joins through the pilot's link port, gets the
 * pilot PC's state (mode, sections, a big one in parts) and its observed tiles' packets - each tile's stream config
 * first, so its decoder can start mid-stream - and is told when the pilot PC refuses or closes.
 */
class MirrorTest {
    private static final String KEY = "mirror-test-key";
    private final List<AutoCloseable> open = new ArrayList<>();
    private volatile boolean running = true;

    @AfterEach
    void tearDown() throws Exception {
        running = false;
        for (AutoCloseable c : open) c.close();
    }

    /** The pilot's link port: MIRROR_HELLO handed to the host with this socket, as ControlLink and NameService do. */
    private int linkPort(MirrorHost host) throws Exception {
        DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        s.setSoTimeout(100);
        open.add(s::close);
        Thread t = new Thread(() -> {
            byte[] buf = new byte[1500];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running) {
                try {
                    p.setLength(buf.length);
                    s.receive(p);
                    if (buf[0] == Mirror.HELLO) host.onHello(p, s);
                } catch (java.net.SocketTimeoutException e) {
                    // again
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return s.getLocalPort();
    }

    private static final class Seen implements MirrorClient.Listener {
        final List<Map<String, byte[]>> states = new CopyOnWriteArrayList<>();
        final List<byte[]> tiles = new CopyOnWriteArrayList<>();
        final List<String> joins = new CopyOnWriteArrayList<>();

        @Override
        public void state(Map<String, byte[]> sections) {
            states.add(sections);
        }

        @Override
        public void tile(int tile, byte[] d, int len) {
            byte[] t = new byte[len + 1];
            t[0] = (byte) tile;
            System.arraycopy(d, 0, t, 1, len);
            tiles.add(t);
        }

        @Override
        public void joined(boolean on, String why) {
            joins.add(on ? "joined" : "left: " + why);
        }

        Map<String, byte[]> last() {
            return states.isEmpty() ? null : states.get(states.size() - 1);
        }
    }

    private MirrorHost host(BooleanSupplier allowed) throws Exception {
        MirrorHost h = new MirrorHost(KEY, allowed, line -> {
        }, InetAddress.getLoopbackAddress());
        open.add(h);
        return h;
    }

    private MirrorClient client(int port, Seen seen) throws Exception {
        MirrorClient c = new MirrorClient(InetAddress.getLoopbackAddress(), port, KEY, seen);
        open.add(c);
        return c;
    }

    private static void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > until) fail("waited " + ms + " ms for " + what);
            Thread.sleep(10);
        }
    }

    @Test
    void theSpectatorGetsThePilotPcsModeAndSectionsAndFollowsAChangeAtOnce() throws Exception {
        MirrorHost h = host(() -> true);
        h.setSection(Mirror.MODE, new Mirror.Mode(Mirror.PILOT, "MobileZulu", List.of(), -1, -1).encode());
        h.setSection("fleet", new byte[]{1, 2, 3});
        // a big section (pins and drawings): goes in parts and comes back whole
        byte[] big = new byte[40_000];
        new java.util.Random(7).nextBytes(big);
        h.setSection("text.map", big);
        Seen seen = new Seen();
        MirrorClient c = client(linkPort(h), seen);
        waitFor(() -> seen.last() != null, 3000, "the first state");
        assertTrue(c.isJoined());
        assertEquals(1, h.count());
        Mirror.Mode m = Mirror.Mode.decode(seen.last().get(Mirror.MODE));
        assertNotNull(m);
        assertEquals(Mirror.PILOT, m.mode());
        assertEquals("MobileZulu", m.phone());
        assertArrayEquals(new byte[]{1, 2, 3}, seen.last().get("fleet"));
        assertArrayEquals(big, seen.last().get("text.map"));

        // the pilot starts observing: the spectator hears it well within the once-a-second state
        long at = System.currentTimeMillis();
        h.setSection(Mirror.MODE, new Mirror.Mode(Mirror.OBSERVER, "", List.of("MobileYankee", "MobileX-ray"), 1, -1).encode());
        waitFor(() -> {
            Mirror.Mode x = Mirror.Mode.decode(seen.last().get(Mirror.MODE));
            return x != null && x.mode() == Mirror.OBSERVER;
        }, 2000, "the OBSERVER mode");
        assertTrue(System.currentTimeMillis() - at < 600, "a change goes at once, not with the next second's state");
        Mirror.Mode o = Mirror.Mode.decode(seen.last().get(Mirror.MODE));
        assertEquals(List.of("MobileYankee", "MobileX-ray"), o.tiles());
        assertEquals(1, o.selected());
    }

    @Test
    void eachTileStartsWithItsStreamsConfigAndThenItsPackets() throws Exception {
        MirrorHost h = host(() -> true);
        AtomicInteger keyAsks = new AtomicInteger();
        h.setTiles(i -> {
            if (i == 0) keyAsks.incrementAndGet();
        });
        // before anyone watches: tile 0's config passes by and is kept; a frame is dropped (no one to send it to)
        byte[] config = {UdpCommon.VideoInitialFrame, 0, 1, 2, (byte) 128, 1, 104, 0, 0, 0, 0, 0, 1, 103};
        h.tile(0, config, config.length);
        byte[] frame = {UdpCommon.KeyFrame, 0, 7, 0, 0, 0, 0, 9, 9, 9};
        h.tile(0, frame, frame.length);
        Seen seen = new Seen();
        client(linkPort(h), seen);
        // joined: the config comes for the tile's decoder, and its phone is asked for a key frame
        waitFor(() -> seen.tiles.stream().anyMatch(t -> t[0] == 0 && t[1] == UdpCommon.VideoInitialFrame), 3000, "tile 0's config");
        waitFor(() -> keyAsks.get() > 0, 2000, "a key frame asked of tile 0's phone");
        // a packet from tile 1's phone now reaches the spectator as it was
        byte[] later = {UdpCommon.VideoFrame, 0, 8, 0, 0, 0, 0, 5};
        h.tile(1, later, later.length);
        waitFor(() -> seen.tiles.stream().anyMatch(t -> t[0] == 1 && t[1] == UdpCommon.VideoFrame && t[8] == 5), 2000, "tile 1's frame");
    }

    @Test
    void aPilotPcThatAllowsNoSpectatorsSaysSoAndNothingIsMirrored() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(false);
        MirrorHost h = host(allowed::get);
        h.setSection(Mirror.MODE, new Mirror.Mode(Mirror.PILOT, "MobileZulu", List.of(), -1, -1).encode());
        Seen seen = new Seen();
        MirrorClient c = client(linkPort(h), seen);
        waitFor(() -> !seen.joins.isEmpty(), 3000, "the refusal");
        assertTrue(seen.joins.get(0).startsWith("left"), seen.joins.toString());
        Thread.sleep(300);
        assertNull(seen.last());
        assertEquals(0, h.count());
        // switched on: the next ask gets in
        allowed.set(true);
        waitFor(c::isJoined, 3000, "joined once allowed");
        waitFor(() -> seen.last() != null, 2000, "the state");
    }

    @Test
    void thePilotPcClosingIsHeardAtOnce() throws Exception {
        MirrorHost h = host(() -> true);
        h.setSection(Mirror.MODE, new Mirror.Mode(Mirror.IDLE, "", List.of(), -1, -1).encode());
        Seen seen = new Seen();
        MirrorClient c = client(linkPort(h), seen);
        waitFor(c::isJoined, 3000, "joined");
        h.close();
        waitFor(() -> seen.joins.stream().anyMatch(j -> j.contains("closed")), 2000, "the BYE");
    }

    @Test
    void theStateSurvivesTheWayThere() {
        Map<String, byte[]> s = new java.util.LinkedHashMap<>();
        s.put(Mirror.MODE, new Mirror.Mode(Mirror.OBSERVER, "", List.of("A", "B"), 0, 1).encode());
        s.put("empty", new byte[0]);
        Map<String, byte[]> back = Mirror.decodeState(Mirror.encodeState(s));
        assertNotNull(back);
        assertEquals(s.keySet(), back.keySet());
        assertArrayEquals(s.get(Mirror.MODE), back.get(Mirror.MODE));
        assertNull(Mirror.decodeState(new byte[]{1, 2, 3}), "broken bytes read as nothing");
        assertNull(Mirror.Mode.decode(new byte[]{1}));
    }
}
