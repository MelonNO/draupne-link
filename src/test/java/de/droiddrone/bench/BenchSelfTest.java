package de.droiddrone.bench;

import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.Joystick;
import de.droiddrone.pc.Outside;
import de.droiddrone.pc.setup.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bench's own parts, proved before anything is trusted to them: the fence refuses what it must (and only that),
 * a scenario's sandbox is where the app then keeps its files, the stand-ins answer as the real things would.
 */
@Tag("benchself")
class BenchSelfTest {
    @AfterEach
    void down() {
        Fence.down();
        Fence.forget();
        Outside.use(null);
        Joystick.use(null);
    }

    @Test
    void aScenarioGetsAUserOfItsOwnAndTheAppKeepsItsFilesThere() throws Exception {
        Sandbox s = Sandbox.fresh("self-sandbox");
        Path run = Sandbox.runRoot();
        assertTrue(s.root().startsWith(run));
        assertEquals(s.dataDir(), AppPaths.dataDir().toAbsolutePath().normalize());
        assertTrue(AppPaths.videosDir().startsWith(s.home()), "Videos\\DRAUPNE Link is this user's");
        assertTrue(de.droiddrone.pc.map.FlightTrack.defaultDir().startsWith(s.home()), "Documents\\DRAUPNE Link\\Tracks too");
        assertEquals(s.settingsFile(), AppPaths.configFile());
        Path real = Sandbox.realAppData();
        assertNotNull(real, "the bench task says where the real settings are (for the armed guard)");
        assertFalse(AppPaths.dataDir().startsWith(real), "and the app is not pointed there");
        Sandbox other = Sandbox.fresh("self-sandbox");
        assertFalse(other.root().equals(s.root()), "the next scenario starts empty");
        assertEquals(other.dataDir(), AppPaths.dataDir().toAbsolutePath().normalize());

        // pointed outside the run's folder: the bench refuses to go on
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", real.getParent().toString());
            assertThrows(IllegalStateException.class, Sandbox::require);
        } finally {
            System.setProperty("user.home", home);
        }
    }

    @Test
    void theFenceRefusesTheWorldOutsideAndNothingElse() throws Exception {
        Sandbox s = Sandbox.fresh("self-fence");
        Path real = Sandbox.realAppData();
        Fence.up(List.of(Sandbox.runRoot(), Path.of(System.getProperty("bench.build"))), Set.of("java.exe"));

        // the network: loopback yes, anything else no - not even a name looked up
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            byte[] hello = {1, 2, 3};
            socket.send(new DatagramPacket(hello, 3, InetAddress.getLoopbackAddress(), socket.getLocalPort()));
            socket.send(new DatagramPacket(hello, 3, InetAddress.getByAddress(new byte[]{127, 0, 0, 11}), 9));
            InetAddress outside = InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1}); // TEST-NET: nobody's
            assertThrows(Fence.Refused.class, () -> socket.send(new DatagramPacket(hello, 3, outside, 6286)));
        }
        assertThrows(Fence.Refused.class, () -> InetAddress.getByName("api.github.com"));
        assertThrows(Exception.class, () -> HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://192.0.2.1/")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding()));

        // files: inside the bench yes; the pilot's folders no - asked with names that do not exist, so nothing real is at stake
        Path mine = s.dataDir().resolve("x.txt");
        Files.createDirectories(mine.getParent());
        Files.writeString(mine, "x");
        Files.delete(mine);
        Path theirs = real.resolve("DraupneLink").resolve("logs").resolve("bench-fence-test-does-not-exist.csv");
        assertThrows(Fence.Refused.class, () -> Files.deleteIfExists(theirs));
        assertThrows(Fence.Refused.class, () -> Files.writeString(theirs, "x"));
        assertThrows(Fence.Refused.class, () -> new FileOutputStream(theirs.toFile()).close());
        assertThrows(Fence.Refused.class, () -> Files.move(theirs, theirs.resolveSibling("y.csv")));
        assertFalse(Files.exists(theirs));
        assertTrue(Files.isReadable(real) || !Files.exists(real), "reading stays allowed (the armed guard reads the link log)");

        // programs and the JVM's own end
        assertThrows(Fence.Refused.class, () -> new ProcessBuilder("notepad.exe").start());
        assertThrows(Fence.Refused.class, () -> new ProcessBuilder("C:\\Program Files\\Tailscale\\tailscale.exe", "status").start());
        assertThrows(Fence.Refused.class, () -> System.exit(3));

        List<Fence.Refusal> refused = Fence.refusals();
        assertTrue(refused.stream().anyMatch(r -> r.kind().equals("network") && r.what().contains("192.0.2.1")), refused.toString());
        assertTrue(refused.stream().anyMatch(r -> r.kind().equals("delete")), refused.toString());
        assertTrue(refused.stream().anyMatch(r -> r.kind().equals("write")), refused.toString());
        assertTrue(refused.stream().anyMatch(r -> r.kind().equals("program") && r.what().contains("notepad")), refused.toString());
        assertTrue(refused.stream().anyMatch(r -> r.kind().equals("exit")), refused.toString());
        assertTrue(refused.stream().allMatch(r -> r.where().startsWith("BenchSelfTest.")), "where it came from is named: " + refused);

        Fence.down();
        assertFalse(Fence.isUp());
    }

    @Test
    void loopbackIsToldFromEverythingElse() {
        for (String yes : List.of("127.0.0.1", "127.0.0.11", "127.255.255.254", "localhost", "LOCALHOST", "::1", "[::1]", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) {
            assertTrue(Fence.loopback(yes), yes);
        }
        for (String no : List.of("100.64.0.1", "192.168.1.1", "10.0.2.2", "0.0.0.0", "127.example.com", "127.0.0", "1270.0.0.1", "example.com", "", "::", "fe80::1")) {
            assertFalse(Fence.loopback(no), no);
        }
        assertFalse(Fence.loopback(null));
    }

    @Test
    void theRadioIsTheOnlyDeviceTheAppSees() {
        FakeRadio radio = new FakeRadio();
        Joystick.use(radio);
        assertTrue(Joystick.init());
        assertEquals(List.of(radio.info()), Joystick.list());
        Joystick.State rest = Joystick.readSame(radio.info());
        assertEquals(8, rest.axes().length);
        assertEquals(-1f, rest.axes()[FakeRadio.THROTTLE], 0.01f, "throttle down");
        assertEquals(-1f, rest.axes()[FakeRadio.AUX1], 0f, "the switches carry no noise");
        radio.sticks(0.5, -0.25, 0, 0.2).aux(1, 1);
        Joystick.State s = Joystick.readSame(radio.info());
        assertEquals(0.5f, s.axes()[FakeRadio.ROLL], 0.01f);
        assertEquals(1f, s.axes()[FakeRadio.AUX1], 0f);
        assertFalse(java.util.Arrays.equals(s.axes(), Joystick.readSame(radio.info()).axes()), "a live radio's sticks are never exactly still");
        radio.frozen(true);
        assertArrayEquals(Joystick.readSame(radio.info()).axes(), Joystick.readSame(radio.info()).axes(), "frozen: exactly the same");
        radio.plugged(false);
        assertEquals(List.of(), Joystick.list());
        assertNull(Joystick.readSame(radio.info()));
        assertNull(Joystick.readSame(new Joystick.Info(0, "Some gamepad", "other")), "never another device's state");
    }

    @Test
    void theWebAnswersForEverySiteAndCanBeDown() throws Exception {
        try (FakeWeb web = new FakeWeb()) {
            BenchDoors doors = new BenchDoors(web);
            Outside.use(doors);
            HttpClient http = HttpClient.newHttpClient();
            String tile = Outside.url("https://cache.kartverket.no/v1/wmts/1.0.0/topo/default/webmercator/12/1190/2150.png");
            assertTrue(tile.startsWith("http://127.0.0.1:"), tile);
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(tile)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, r.statusCode());
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.body()));
            assertEquals(256, img.getWidth());

            web.release("9.9.9", new byte[]{1, 2, 3, 4});
            de.droiddrone.pc.setup.UpdateCheck.Release latest = de.droiddrone.pc.setup.UpdateCheck.latest();
            assertEquals("9.9.9", latest.version());
            assertTrue(latest.canInstall(), latest.toString());
            assertEquals(4, latest.installerSize());

            HttpResponse<String> height = http.send(HttpRequest.newBuilder(URI.create(Outside.url("https://api.open-meteo.com/v1/elevation?latitude=1&longitude=2"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(height.body().contains("\"elevation\":[120"), height.body());
            assertEquals(1, web.requestsTo("kartverket"));
            assertTrue(doors.asked("url").contains("api.github.com"), doors.asked().toString());

            web.mood(FakeWeb.Mood.ERRORS);
            assertThrows(java.io.IOException.class, de.droiddrone.pc.setup.UpdateCheck::latest, "GitHub answering 500");
            web.mood(FakeWeb.Mood.DOWN);
            assertThrows(java.io.IOException.class, de.droiddrone.pc.setup.UpdateCheck::latest, "no internet");
            web.mood(FakeWeb.Mood.OK);
            assertEquals("9.9.9", de.droiddrone.pc.setup.UpdateCheck.latest().version(), "and back");

            // other programs are not started; what the app wanted is written down
            Shell.Result status = Shell.run(Duration.ofSeconds(2), "C:\\Program Files\\Tailscale\\tailscale.exe", "status", "--json");
            assertTrue(status.ok() && status.output().contains("\"BackendState\":\"Running\""), status.toString());
            assertNull(Outside.start(new ProcessBuilder("explorer.exe", "C:\\")));
            Outside.browse(URI.create("https://www.openstreetmap.org/"));
            Outside.open(Path.of("C:\\").toFile());
            assertEquals(List.of("explorer.exe C:\\"), doors.asked("start"));
            assertEquals(1, doors.asked("browse").size());
            assertEquals(1, doors.asked("open").size());
        }
    }

    @Test
    void soundIsPlayedToNobodyInRealTimeAndCounted() throws Exception {
        SilentSound.forget();
        AudioFormat format = new AudioFormat(48_000, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            assertTrue(line.getClass().getName().contains("SilentSound"), "the bench's line, not the sound card's: " + line.getClass());
            line.open(format, 4800); // a 50 ms buffer
            line.start();
            byte[] tone = new byte[48_000]; // half a second
            for (int i = 0; i < tone.length / 2; i++) {
                short v = (short) (Math.sin(i * 2 * Math.PI * 440 / 48_000) * 12_000);
                tone[2 * i] = (byte) v;
                tone[2 * i + 1] = (byte) (v >> 8);
            }
            long t0 = System.nanoTime();
            assertEquals(tone.length, line.write(tone, 0, tone.length));
            line.drain();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 400 && ms < 1500, "half a second of sound takes half a second: " + ms + " ms");
        }
        List<SilentSound.Played> played = SilentSound.played();
        assertEquals(1, played.size());
        assertTrue(played.get(0).audible(), played.toString());
        assertEquals(0.5, played.get(0).seconds(), 0.01);
    }

    /**
     * The driver sees what a pilot can: a button inside a part of a card folded to nothing (the app's Fold: height 0,
     * the button itself still visible to Swing) is not there - the update scenario took a folded-away UPDATE PHONE
     * APP as still offered (2026-11-21). Unfolded, it is.
     */
    @Test
    void aButtonFoldedAwayIsNotSeen() {
        javax.swing.JPanel card = new javax.swing.JPanel(null);
        javax.swing.JPanel fold = new javax.swing.JPanel(null);
        javax.swing.JButton update = new javax.swing.JButton("UPDATE PHONE APP");
        card.setBounds(0, 0, 300, 200);
        card.add(fold);
        fold.add(update);
        update.setBounds(0, 0, 280, 30); // the content keeps its height, clipped at the fold
        fold.setBounds(10, 100, 280, 0);  // folded
        List<java.awt.Component> seen = new java.util.ArrayList<>();
        Ui.collect(card, seen);
        assertFalse(seen.contains(update), "folded away: not seen");
        assertTrue(seen.contains(card));
        fold.setBounds(10, 100, 280, 30); // unfolded
        seen.clear();
        Ui.collect(card, seen);
        assertTrue(seen.contains(update), "unfolded: seen");
        update.setVisible(false);
        seen.clear();
        Ui.collect(card, seen);
        assertFalse(seen.contains(update), "hidden: not seen, as before");
    }
}
