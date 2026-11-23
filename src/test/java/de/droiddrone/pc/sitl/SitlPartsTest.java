package de.droiddrone.pc.sitl;

import de.droiddrone.common.FleetCmd;
import de.droiddrone.common.MissionBlock;
import de.droiddrone.common.MissionItem;
import de.droiddrone.common.MissionUpload;
import de.droiddrone.common.UdpCommon;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Long routes to a drone on real ArduPilot (Copter SITL) through the fleet path: a dispatch route of 111 items (3558
 * bytes in one datagram before - cut at the phone's 1100 and never there) and one of 300, in parts inside FleetCmds,
 * received in the phone's own buffer size by SitlFleetPhone, put together, uploaded and read back. The first rounds lose
 * parts and send some twice, out of order. Instance 30 (TCP 5760 + 300).
 */
@Tag("ardusitl")
class SitlPartsTest {
    static final double LAT = 59.93, LON = 10.73, ALT = 110;

    static List<MissionItem> route(int n) {
        List<MissionItem> r = new ArrayList<>();
        r.add(MissionItem.at(0, MissionItem.NAV_WAYPOINT, LAT, LON, ALT));
        for (int i = 1; i < n; i++) r.add(MissionItem.at(i, MissionItem.NAV_WAYPOINT, LAT + i * 2e-5, LON + (i % 7) * 1e-5, ALT + 40));
        return r;
    }

    @Test
    void longRoutesArriveInPartsAndReadBack() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        try (ArduSitl sitl = ArduSitl.start(30, LAT, LON, ALT, 1);
             SitlFleetPhone phone = new SitlFleetPhone(sitl, "DD");
             DatagramSocket pc = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread.sleep(3000);
            Random rnd = new Random(30);
            int blockId = 700;
            for (int n : new int[]{111, 300}) {
                blockId++;
                List<MissionItem> r = route(n);
                List<byte[]> parts = MissionBlock.packets(blockId, MissionUpload.TYPE_MISSION, r);
                long end = System.nanoTime() + 60_000_000_000L;
                int round = 0;
                while (System.nanoTime() < end) {
                    MissionUpload u = phone.uploadNow();
                    if (u != null && phone.routeBlockNow() == blockId && (u.done() || u.failed())) break;
                    // as FleetOps: every part again each second; the first two rounds lossy, doubled and shuffled
                    List<byte[]> wire = new ArrayList<>();
                    for (byte[] p : parts) {
                        if (round < 2 && rnd.nextInt(3) == 0) continue;
                        wire.add(p);
                        if (round < 2 && rnd.nextInt(4) == 0) wire.add(p);
                    }
                    if (round < 2) java.util.Collections.shuffle(wire, rnd);
                    for (byte[] p : wire) {
                        byte[] d = FleetCmd.wrap("DD", p);
                        assertTrue(d.length <= UdpCommon.packetLength);
                        pc.send(new DatagramPacket(d, d.length, phone.address()));
                    }
                    round++;
                    Thread.sleep(1000);
                }
                MissionUpload u = phone.uploadNow();
                assertEquals(blockId, phone.routeBlockNow(), n + " items: " + phone.uploadWhy());
                assertTrue(u.done(), n + " items uploaded and read back: " + phone.uploadWhy());
                assertEquals(n, u.items().size());
            }
        }
    }
}
