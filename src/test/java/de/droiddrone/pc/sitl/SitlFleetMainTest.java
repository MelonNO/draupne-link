package de.droiddrone.pc.sitl;

import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sitl-fleet.ps1's drones as DRAUPNE Link finds them: announced in the fleet file at loopback addresses of their own,
 * each answering the link key's name query with its name, where it stands (from ArduPilot) and disarmed.
 */
@Tag("ardusitl")
class SitlFleetMainTest {
    @TempDir
    Path dir;

    @Test
    void twoDronesAnnouncedAndAnsweringNameQueries() throws Exception {
        Assumptions.assumeTrue(ArduSitl.installed(), "ArduPilot SITL not installed");
        PcConfig config = PcConfig.load(null);
        Path file = dir.resolve("virtual-fleet.txt");
        double lat = 59.92, lon = 10.73;
        try (SitlFleetMain.Running r = SitlFleetMain.start(config, file, 2, lat, lon, 100, 50, s -> { })) {
            assertEquals(2, r.phones().size());
            String text = Files.readString(file);
            try (DatagramSocket ask = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                ask.setSoTimeout(2000);
                for (Map.Entry<String, SitlFleetPhone> e : r.phones().entrySet()) {
                    var at = e.getValue().address();
                    assertTrue(at.getAddress().getHostAddress().startsWith("127.0.0.1"), at.toString());
                    assertTrue(text.contains(at.getAddress().getHostAddress()) && text.contains(String.valueOf(at.getPort())),
                            "announced: " + text);
                    byte[] q = Names.query(config.getKey());
                    ask.send(new DatagramPacket(q, q.length, at));
                    DatagramPacket in = new DatagramPacket(new byte[1500], 1500);
                    ask.receive(in);
                    Names.Reply reply = Names.parseReply(in.getData(), in.getLength(), config.getKey());
                    assertNotNull(reply, e.getKey());
                    assertEquals(e.getKey(), reply.name());
                    assertNotNull(reply.position(), "where it stands");
                    assertEquals(lat, reply.position().lat(), 0.001);
                    assertEquals(Boolean.FALSE, reply.position().armed());
                }
            }
            List<String> names = List.copyOf(r.phones().keySet());
            assertNotEquals(names.get(0), names.get(1));
        }
    }
}
