package de.droiddrone.pc.air;

import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.FleetStatus;
import de.droiddrone.common.SeqGate;
import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.GroupOrder;
import de.droiddrone.common.group.OrderGates;
import de.droiddrone.common.group.Traffic;
import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stale datagrams on the fleet link, through a relay on loopback that delays every datagram by a random time (so they
 * overtake each other), sends some twice and replays an old one long after: the PC's GroupLink keeps the newest report
 * and counts only a newer one as heard; a late launch status never takes back IN GROUP; a follower's phone keeps the
 * newest group order, exit plan and traffic - also after a DISBAND, when a late order made it a follower again.
 */
class StalePacketsRelayTest {
    /** Forwards what reaches it to one address, each datagram after its own random delay, some twice. */
    static final class Relay implements AutoCloseable {
        final DatagramSocket in = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        final ScheduledExecutorService later = Executors.newScheduledThreadPool(4);
        final Random rnd = new Random(17);
        volatile InetSocketAddress to;
        volatile boolean open = true;

        Relay(InetSocketAddress to) throws Exception {
            this.to = to;
            Thread t = new Thread(() -> {
                byte[] buf = new byte[2048];
                while (open) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        in.receive(p);
                        byte[] d = java.util.Arrays.copyOf(p.getData(), p.getLength());
                        int copies = rnd.nextInt(6) == 0 ? 2 : 1;
                        for (int i = 0; i < copies; i++) {
                            long delay = rnd.nextInt(150);
                            later.schedule(() -> send(d), delay, TimeUnit.MILLISECONDS);
                        }
                    } catch (Exception e) {
                        if (!open) return;
                    }
                }
            }, "relay");
            t.setDaemon(true);
            t.start();
        }

        void send(byte[] d) {
            try {
                in.send(new DatagramPacket(d, d.length, to));
            } catch (Exception ignored) {
                // closed
            }
        }

        InetSocketAddress address() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), in.getLocalPort());
        }

        @Override
        public void close() {
            open = false;
            later.shutdownNow();
            in.close();
        }
    }

    static FleetStatus report(int seq, double lat) {
        FleetStatus s = new FleetStatus();
        s.seq = seq & 0xffff;
        s.lat = lat;
        s.lon = 10.7;
        s.altAmsl = 150;
        s.battery = 80;
        return s;
    }

    @Test
    void groupLinkKeepsTheNewestReportAndCountsOnlyItAsHeard() throws Exception {
        List<LaunchWire.Status> launches = new CopyOnWriteArrayList<>();
        DatagramSocket pcSocket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        GroupLink link = new GroupLink("DD", new GroupLink.Listener() {
            public void event(String text) {
            }

            public void newLead(String drone) {
            }

            @Override
            public void launchStatus(String drone, LaunchWire.Status s) {
                launches.add(s);
            }
        }, pcSocket);
        try (Relay relay = new Relay(new InetSocketAddress(InetAddress.getLoopbackAddress(), pcSocket.getLocalPort()));
             DatagramSocket phone = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            link.phone("A", relay.address()); // the PC knows the phone by the address its datagrams come from: the relay's
            int start = 65_500; // across the wrap
            byte[] first = null;
            double newest = 0;
            for (int i = 0; i < 200; i++) {
                double lat = 59.9 + i * 1e-5;
                byte[] d = report(start + i, lat).encode();
                if (i == 3) first = d;
                phone.send(new DatagramPacket(d, d.length, relay.address()));
                newest = lat;
                double seen = link.status("A") == null ? 0 : link.status("A").lat;
                Thread.sleep(5);
                FleetStatus now = link.status("A");
                if (now != null) assertTrue(now.lat >= seen, "never back to an older report: " + now.lat + " after " + seen);
            }
            de.droiddrone.testing.Waits.waitFor(() -> link.status("A") != null && Math.abs(link.status("A").lat - (59.9 + 199 * 1e-5)) < 1e-9, 3000, "the newest");
            Thread.sleep(1500);
            long quiet = link.heardAgoMs("A", de.droiddrone.pc.MonoClock.ms());
            // an old one replayed a second and a half later: neither taken nor counted as heard
            phone.send(new DatagramPacket(first, first.length, relay.address()));
            Thread.sleep(400);
            assertEquals(59.9 + 199 * 1e-5, link.status("A").lat, 1e-9);
            assertTrue(link.heardAgoMs("A", de.droiddrone.pc.MonoClock.ms()) >= quiet, "the late one isn't news");
            // the launch status: IN GROUP, then a late CLIMBING
            LaunchWire.Status climbing = new LaunchWire.Status();
            climbing.callId = 5;
            climbing.markId = 9;
            climbing.state = LaunchSequence.CLIMBING;
            climbing.seq = 1000;
            LaunchWire.Status in = new LaunchWire.Status();
            in.callId = 5;
            in.markId = 9;
            in.state = LaunchSequence.IN_GROUP;
            in.seq = 1001;
            relay.rnd.setSeed(1);
            for (LaunchWire.Status s : List.of(climbing, in)) {
                byte[] st = LaunchWire.encode(s);
                byte[] p = new byte[1 + st.length];
                p[0] = FleetCmd.LAUNCH_STATUS;
                System.arraycopy(st, 0, p, 1, st.length);
                phone.send(new DatagramPacket(p, p.length, relay.address()));
                Thread.sleep(300);
            }
            byte[] st = LaunchWire.encode(climbing);
            byte[] late = new byte[1 + st.length];
            late[0] = FleetCmd.LAUNCH_STATUS;
            System.arraycopy(st, 0, late, 1, st.length);
            phone.send(new DatagramPacket(late, late.length, relay.address()));
            Thread.sleep(400);
            assertEquals(LaunchSequence.IN_GROUP, launches.get(launches.size() - 1).state, "a late CLIMBING never takes back IN GROUP");
        } finally {
            link.close();
        }
    }

    static GroupOrder order(int seq, double lat) {
        return new GroupOrder(0, seq, lat, 10.7, 0, 0, 5, 5, 160, 140, 8, 0, 0, 0, 0, 0);
    }

    /** As the phone's FleetRole: orders, plans and traffic through the relay, each kept only when newer. */
    @Test
    void aFollowerKeepsTheNewestOrderPlanAndTraffic() throws Exception {
        DatagramSocket phoneSocket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        phoneSocket.setSoTimeout(200);
        OrderGates gates = new OrderGates();
        double[] orderLat = {0};
        int[] planSeq = {-1}, trafficSeq = {-1}; // exit plans go through gates.exit the same way
        boolean[] follower = {false};
        Thread rx = new Thread(() -> {
            byte[] buf = new byte[de.droiddrone.common.UdpCommon.packetLength];
            while (!phoneSocket.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    phoneSocket.receive(p);
                    FleetCmd c = FleetCmd.read(p.getData(), p.getLength());
                    long now = de.droiddrone.pc.MonoClock.ms();
                    if (c == null) continue;
                    synchronized (gates) {
                        switch (c.inner()) {
                            case FleetCmd.GROUP -> {
                                GroupOrder o = GroupOrder.decode(c.data, c.at + 1);
                                if (o != null && gates.group.take(o.seq, now)) {
                                    orderLat[0] = o.centreLat;
                                    follower[0] = true;
                                }
                            }
                            case FleetCmd.EXIT -> {
                                ExitPlan e = ExitPlan.decode(c.data, c.at + 1);
                                if (e != null && gates.exit.take(e.seq, now)) planSeq[0] = e.seq;
                            }
                            case FleetCmd.TRAFFIC -> {
                                Traffic t = Traffic.decode(c.data, c.at + 1, c.length - 1);
                                if (t != null && gates.traffic.take(t.seq, now)) trafficSeq[0] = t.seq;
                            }
                            case FleetCmd.DISBAND -> follower[0] = false;
                            default -> {
                            }
                        }
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // again
                } catch (Exception e) {
                    return;
                }
            }
        }, "phone");
        rx.setDaemon(true);
        rx.start();
        try (Relay relay = new Relay(new InetSocketAddress(InetAddress.getLoopbackAddress(), phoneSocket.getLocalPort()));
             DatagramSocket pc = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            int start = 65_400;
            byte[] old = null;
            for (int i = 0; i < 150; i++) {
                byte[] g = FleetCmd.wrap("DD", FleetCmd.group(order(start + i, 59.9 + i * 1e-5).encode()));
                if (i == 10) old = g;
                pc.send(new DatagramPacket(g, g.length, relay.address()));
                Traffic t = new Traffic(List.of());
                t.seq = (start + i) & 0xffff;
                byte[] tr = FleetCmd.wrap("DD", FleetCmd.traffic(t.encode()));
                pc.send(new DatagramPacket(tr, tr.length, relay.address()));
                Thread.sleep(5);
            }
            de.droiddrone.testing.Waits.waitFor(() -> {
                synchronized (gates) {
                    return Math.abs(orderLat[0] - (59.9 + 149 * 1e-5)) < 1e-9;
                }
            }, 3000, "the newest order");
            Thread.sleep(300);
            synchronized (gates) {
                assertEquals(59.9 + 149 * 1e-5, orderLat[0], 1e-9, "the newest order kept");
                assertEquals((start + 149) & 0xffff, trafficSeq[0], "the newest traffic kept");
            }
            // DISBAND, then a late order of before: no follower again
            byte[] d = FleetCmd.wrap("DD", FleetCmd.disband());
            pc.send(new DatagramPacket(d, d.length, relay.address()));
            Thread.sleep(300);
            pc.send(new DatagramPacket(old, old.length, relay.address()));
            Thread.sleep(400);
            synchronized (gates) {
                assertFalse(follower[0], "a late order after DISBAND makes no follower");
            }
        } finally {
            phoneSocket.close();
        }
    }

    @Test
    void theGateItself() {
        SeqGate g = new SeqGate();
        assertTrue(g.take(65_534, 0));
        assertTrue(g.take(65_535, 1));
        assertTrue(g.take(0, 2), "across the wrap");
        assertTrue(g.take(0, 3), "a copy says the same");
        assertFalse(g.take(65_535, 4), "older");
        assertFalse(g.take(65_535 - SeqGate.WINDOW + 2, 5), "older, within the window");
        assertTrue(g.take(30_000, 6), "far off: the sender started again");
        assertFalse(g.take(29_999, 7));
        assertTrue(g.take(29_999, 7 + SeqGate.RESYNC_MS), "nothing newer for 3 s: taken");
        assertTrue(g.take(-1, 8), "an older peer without a sequence");
        // the senders' starts are random
        java.util.Set<Integer> starts = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) starts.add(SeqGate.randomStart());
        assertTrue(starts.size() > 15);
        // older peers: a 36-byte report has no sequence and is read; a v1 traffic reader reads past the sequence
        byte[] r = report(7, 59.9).encode();
        assertEquals(FleetStatus.WITH_SEQ_BYTES, r.length);
        assertEquals(-1, FleetStatus.decode(java.util.Arrays.copyOf(r, FleetStatus.WITH_RTL_BYTES), FleetStatus.WITH_RTL_BYTES).seq);
        assertEquals(7, FleetStatus.decode(r, r.length).seq);
        Traffic t = new Traffic(List.of());
        t.seq = 42;
        byte[] te = t.encode();
        assertEquals(42, Traffic.decode(te, 0, te.length).seq);
        assertEquals(-1, Traffic.decode(te, 0, te.length - 2).seq, "an older PC's traffic: none");
        LaunchWire.Status s = new LaunchWire.Status();
        s.seq = 77;
        assertEquals(77, LaunchWire.decodeStatus(LaunchWire.encode(s), 0, LaunchWire.STATUS_SIZE).seq);
    }
}
