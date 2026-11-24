package de.droiddrone.pc.sitl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The SITL lab's settings and its phone relay, without SITL or an emulator. */
class SitlLabPartsTest {
    @TempDir
    Path dir;

    @Test
    void theRepoExampleReadsAsTheDefaults() throws Exception {
        SitlLabSettings s = SitlLabSettings.read(Path.of("sitl-lab.settings"), Map.of());
        assertEquals(SitlLabSettings.defaults(), s, "the example file says what the code assumes");
    }

    @Test
    void thePcsFileAndTheScriptsSwitches() throws Exception {
        Path f = dir.resolve("lab.settings");
        Files.writeString(f, "# a stronger PC\nphones = 6   # six emulators\nfleetOnly=10\nemulatorMemoryMb = 4096\nemulatorCores=4\n"
                + "emulatorWindows = true\nlat = 59,91\nlon = 10.72\nalt =\n");
        SitlLabSettings s = SitlLabSettings.read(f, Map.of());
        assertEquals("6", s.phones());
        assertEquals(10, s.fleetOnly());
        assertEquals(4096, s.emulatorMemoryMb());
        assertEquals(4, s.emulatorCores());
        assertTrue(s.emulatorWindows());
        assertEquals(59.91, s.lat(), 1e-9, "a decimal comma taken");
        assertTrue(Double.isNaN(s.alt()), "empty: looked up");
        assertEquals(6, s.phoneCount(1000, 4), "a number is a number, whatever the PC has");

        SitlLabSettings o = SitlLabSettings.read(f, Map.of("phones", "0", "fleetOnly", "3", "emulatorWindows", "false"));
        assertEquals(0, o.phoneCount(64_000, 32));
        assertEquals(3, o.fleetOnly());
        assertFalse(o.emulatorWindows());
        assertEquals(4096, o.emulatorMemoryMb(), "what the switches leave out stays the file's");

        assertEquals(SitlLabSettings.MAX_PHONES, SitlLabSettings.read(f, Map.of("phones", "99")).phoneCount(0, 1));
        assertThrows(IllegalArgumentException.class, () -> SitlLabSettings.read(f, Map.of("fleetOnly", "many")));
    }

    @Test
    void autoFitsTheMemoryFreeAndTheCores() {
        SitlLabSettings s = SitlLabSettings.defaults(); // 2048 MB each, 1024 MB more, 3072 kept free
        assertEquals(2, s.phoneCount(12_000, 16), "this laptop: 12 GB free (3 GB each, 3 GB kept), 16 cores");
        assertEquals(10, s.phoneCount(64_000, 64), "a big PC: the most there is");
        assertEquals(3, s.phoneCount(64_000, 16), "memory enough, cores not");
        assertEquals(1, s.phoneCount(2_000, 4), "at least one");
    }

    /** Each DRAUPNE Link socket gets the phone's answers on itself; the phone sees one socket of the relay for each. */
    @Test
    void theRelayKeepsThePcsSocketsApart() throws Exception {
        try (DatagramSocket phone = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             PhoneRelay relay = new PhoneRelay(InetAddress.getByName("127.0.0.103"), new InetSocketAddress(InetAddress.getLoopbackAddress(), phone.getLocalPort()));
             DatagramSocket link = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket names = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            phone.setSoTimeout(2000);
            link.setSoTimeout(2000);
            names.setSoTimeout(2000);
            assertEquals("127.0.0.103", relay.address().getAddress().getHostAddress());
            Set<Integer> seen = new HashSet<>();
            for (int round = 0; round < 2; round++) {
                for (DatagramSocket pc : new DatagramSocket[]{link, names}) {
                    byte[] ask = {(byte) pc.getLocalPort(), (byte) round};
                    pc.send(new DatagramPacket(ask, ask.length, relay.address()));
                    DatagramPacket got = new DatagramPacket(new byte[16], 16);
                    phone.receive(got);
                    seen.add(got.getPort());
                    phone.send(new DatagramPacket(new byte[]{got.getData()[0], got.getData()[1], 42}, 3, got.getSocketAddress())); // answered to its sender
                }
                for (DatagramSocket pc : new DatagramSocket[]{link, names}) {
                    DatagramPacket back = new DatagramPacket(new byte[16], 16);
                    pc.receive(back);
                    assertEquals((byte) pc.getLocalPort(), back.getData()[0], "the answer on the socket that asked");
                    assertEquals(relay.address(), back.getSocketAddress(), "from the phone's address as DRAUPNE Link knows it");
                }
            }
            assertEquals(2, seen.size(), "one relay socket per PC socket, the same each time");
        }
    }
}
