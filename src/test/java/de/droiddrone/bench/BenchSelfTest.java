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

    // ------------------------------------------------------------------ the hardware rig (no hardware is touched here)

    /** An address kept for examples (TEST-NET-1): nobody's. Nothing is ever sent to it here - the fence is only asked. */
    private static final String PHONE = "192.0.2.10";

    /** As gradlew benchHw starts its JVM; put back to what it was by the returned step. */
    private static AutoCloseable asAHardwareRun() {
        String was = System.getProperty(Fence.Reach.PROPERTY);
        System.setProperty(Fence.Reach.PROPERTY, "true");
        return () -> {
            if (was == null) System.clearProperty(Fence.Reach.PROPERTY);
            else System.setProperty(Fence.Reach.PROPERTY, was);
        };
    }

    @Test
    @SuppressWarnings("removal") // the fence is asked as the JDK asks it
    void aHardwareRunsFenceLetsItsOnePairThroughAndNothingElse() throws Exception {
        Sandbox.fresh("self-reach");
        int link = de.droiddrone.common.UdpCommon.defaultPort;
        try (AutoCloseable back = asAHardwareRun()) {
            Fence.Reach reach = Fence.Reach.of(PHONE + ":" + link);
            List<Path> roots = List.of(Sandbox.runRoot(), Path.of(System.getProperty("bench.build")));
            Fence fence = Fence.up(roots, Set.of("java.exe"), reach);
            assertEquals(reach, Fence.reach());
            SecurityManager asked = System.getSecurityManager(); // the checks the JDK makes before a packet goes or is handed over
            assertEquals(fence, asked);

            // the listed pair, out and in - also as the JDK writes an IPv4 address on an IPv6 socket
            asked.checkConnect(PHONE, link);
            asked.checkAccept(PHONE, link);
            asked.checkConnect("::ffff:" + PHONE, link);
            assertEquals(List.of(), Fence.refusals(), "the pair passes and nothing is written down");

            // everything else is as closed as without a reach, and written down: asked with real sockets, where the
            // JDK asks the fence before anything leaves
            byte[] hello = {1, 2, 3};
            InetAddress phone = InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 10});
            InetAddress other = InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 11});
            InetAddress group = InetAddress.getByAddress(new byte[]{(byte) 224, 0, 0, (byte) 251});
            try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                socket.send(new DatagramPacket(hello, 3, InetAddress.getLoopbackAddress(), socket.getLocalPort())); // loopback as ever
                assertThrows(Fence.Refused.class, () -> socket.send(new DatagramPacket(hello, 3, phone, link + 1)), "another port of the phone");
                assertThrows(Fence.Refused.class, () -> socket.send(new DatagramPacket(hello, 3, other, link)), "another address, the same port");
                assertThrows(Fence.Refused.class, () -> socket.send(new DatagramPacket(hello, 3, group, link)), "multicast");
                assertThrows(Fence.Refused.class, () -> socket.connect(phone, link + 1));
            }
            assertThrows(Fence.Refused.class, () -> InetAddress.getByName("bench-phone.invalid"), "a name looked up");
            assertThrows(Fence.Refused.class, () -> asked.checkConnect("bench-phone.invalid", link), "a name, even at the port");
            assertThrows(Fence.Refused.class, () -> asked.checkConnect(PHONE, -1), "the phone's address without its port");
            assertThrows(Fence.Refused.class, () -> asked.checkMulticast(group));
            assertThrows(Fence.Refused.class, () -> asked.checkMulticast(group, (byte) 1), "the older way of sending to a group");
            // a packet from anywhere else is dropped by the JDK when this throws (not written down: anybody may send one)
            assertThrows(Fence.Refused.class, () -> asked.checkAccept(PHONE, link + 1));
            assertThrows(Fence.Refused.class, () -> asked.checkAccept("192.0.2.11", link));
            // and a reach is a way through for packets only: programs and files are as fenced as ever
            assertThrows(Fence.Refused.class, () -> new ProcessBuilder("notepad.exe").start());
            Path theirs = Sandbox.realAppData().resolve("DraupneLink").resolve("bench-reach-test-does-not-exist.txt");
            assertThrows(Fence.Refused.class, () -> Files.writeString(theirs, "x"));

            List<String> refused = Fence.refusals().stream().map(r -> r.kind() + " " + r.what()).toList();
            for (String what : List.of("network " + PHONE + ":" + (link + 1), "network 192.0.2.11:" + link, "network bench-phone.invalid (lookup)",
                    "network bench-phone.invalid:" + link, "network " + PHONE + " (lookup)", "network multicast /224.0.0.251", "program notepad.exe")) {
                assertTrue(refused.contains(what), what + " is written down: " + refused);
            }
            assertTrue(refused.stream().anyMatch(r -> r.startsWith("write ") && r.contains("bench-reach-test-does-not-exist")), refused.toString());
            assertTrue(refused.stream().noneMatch(r -> r.equals("network " + PHONE + ":" + link)), refused.toString());

            // a fence that is up is never taken for one with another reach - not for one without a hole either
            assertEquals(fence, Fence.up(roots, Set.of("java.exe"), Fence.Reach.of(PHONE + ":" + link)));
            assertThrows(IllegalStateException.class, () -> Fence.up(roots, Set.of("java.exe"), Fence.Reach.of("192.0.2.11:" + link)));
            assertThrows(IllegalStateException.class, () -> Fence.up(roots, Set.of("java.exe")));
            Fence.down();
            assertTrue(Fence.reach().isEmpty());
        }
    }

    @Test
    void aReachIsMadeOnlyInAHardwareRunAndOnlyOfAddressesWrittenOut() throws Exception {
        Sandbox.fresh("self-reach-closed");
        List<Path> roots = List.of(Sandbox.runRoot());
        // this JVM is benchSelf's: no -Dbench.hardware
        assertFalse(Fence.Reach.hardwareRun(), "the self tests are no hardware run");
        IllegalStateException closed = assertThrows(IllegalStateException.class, () -> Fence.Reach.of(PHONE + ":6286"));
        assertTrue(closed.getMessage().contains("benchHw"), closed.getMessage());
        assertTrue(Fence.Reach.none().isEmpty() && Fence.Reach.of().isEmpty(), "nothing to reach needs no hardware run");

        Fence.Reach made;
        try (AutoCloseable back = asAHardwareRun()) {
            made = Fence.Reach.of(PHONE + ":6286", " " + PHONE + ":6286 ", "192.0.2.11:1");
            assertEquals(List.of(PHONE + ":6286", "192.0.2.11:1"), made.pairs());
            assertThrows(UnsupportedOperationException.class, () -> made.pairs().add("192.0.2.12:6286"));
            for (String no : List.of("phone.example:6286", "localhost:6286", PHONE, PHONE + ":", PHONE + ":0", PHONE + ":65536", PHONE + ":6286-6290",
                    PHONE + ":*", "192.0.2.0/24:6286", "192.0.2.*:6286", "192.0.2:6286", "192.0.2.256:6286", "192.0.02.10:6286", "224.0.0.251:5353",
                    "239.255.255.250:1900", "255.255.255.255:6286", "0.0.0.0:6286", "[::1]:6286", "::1:6286", "fe80::1:6286", PHONE + ":6286,192.0.2.11:6286", "")) {
                assertThrows(IllegalArgumentException.class, () -> Fence.Reach.of(no), no);
            }
            assertThrows(IllegalArgumentException.class, () -> Fence.Reach.of("192.0.2.1:1", "192.0.2.2:1", "192.0.2.3:1", "192.0.2.4:1", "192.0.2.5:1"),
                    "a handful of addresses is a range by another name");
            System.setProperty(Fence.Reach.PROPERTY, "yes");
            assertThrows(IllegalStateException.class, () -> Fence.Reach.of(PHONE + ":6286"), "only the word true opens it");
        }
        // a reach made in a hardware run is no use outside one: the fence asks again as it goes up
        assertThrows(IllegalStateException.class, () -> Fence.up(roots, Set.of("java.exe"), made));
        assertFalse(Fence.isUp(), "and no fence with a hole in it is left up");
        assertThrows(IllegalArgumentException.class, () -> Fence.up(roots, Set.of("java.exe"), null));
        assertFalse(Fence.isUp());
    }

    @Test
    void theRadiosThrottleCeilingHoldsWhateverIsAsked() {
        FakeRadio radio = new FakeRadio();
        Joystick.use(radio);
        // the app's own way from the radio's axes to the sticks it sends, with the rig's stick settings (AppRig)
        java.util.Map<Integer, de.droiddrone.pc.AxisCalibration> measured = new java.util.HashMap<>();
        for (int axis = 0; axis < 8; axis++) measured.put(axis, new de.droiddrone.pc.AxisCalibration(-1f, 0f, 1f));
        de.droiddrone.pc.RcMapper mapper = new de.droiddrone.pc.RcMapper(new String[]{"axis0", "axis1", "axis3", "axis2", "axis4", "axis5", "axis6", "axis7"}, measured);
        int throttle = de.droiddrone.pc.PcConfig.THROTTLE_INDEX;

        // no ceiling unless asked for: the radio of every scenario so far (frozen: a reading without its noise)
        radio.frozen(true).sticks(0, 0, 0, 1);
        assertEquals(2000, mapper.map(Joystick.readSame(radio.info()).axes(), new boolean[16])[throttle]);
        assertEquals(0, radio.ceiling());
        assertEquals("", radio.limits());
        radio.frozen(false);

        radio.ceiling(Hardware.THROTTLE_CEILING);
        assertEquals(1100, radio.ceiling());
        assertTrue(mapper.map(Joystick.readSame(radio.info()).axes(), new boolean[16])[throttle] <= 1100, "what was held comes under it at once");
        assertEquals(0, radio.clamped(), "that was no request");

        java.util.Random random = new java.util.Random(20261121);
        float top = (1100 - 1500) / 500f;
        int above = 0, most = 0;
        for (int i = 0; i < 10_000; i++) {
            double asked = random.nextInt(8) == 0 ? (random.nextBoolean() ? 1 : 5) : random.nextDouble() * 2.4 - 1.2;
            switch (random.nextInt(4)) {
                case 0 -> radio.axis(FakeRadio.THROTTLE, asked);
                case 1 -> radio.sticks(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, asked);
                case 2 -> radio.frozen(random.nextBoolean()).sticks(0, 0, 0, asked);
                default -> radio.aux(1 + random.nextInt(4), random.nextDouble() * 2 - 1).axis(FakeRadio.THROTTLE, asked);
            }
            if ((float) asked > top) above++;
            for (int reading = 0; reading < 3; reading++) { // each reading has its own noise
                float[] axes = Joystick.readSame(radio.info()).axes();
                assertTrue(axes[FakeRadio.THROTTLE] <= top, "request " + i + " (" + asked + "): the radio gives " + axes[FakeRadio.THROTTLE]);
                int sent = mapper.map(axes, new boolean[16])[throttle];
                assertTrue(sent <= 1100, "request " + i + " (" + asked + "): the app would send " + sent);
                most = Math.max(most, sent);
            }
        }
        assertEquals(1100, most, "up to the ceiling it is still a throttle");
        assertTrue(above > 3000, "the requests did ask for more: " + above);
        assertEquals(above, radio.clamped(), "every request above the ceiling is counted");
        assertTrue(radio.limits().contains("ceiling 1100") && radio.limits().contains(above + " request"), radio.limits());

        // it only goes down: no scenario lifts or drops it
        assertThrows(IllegalStateException.class, () -> radio.ceiling(1500));
        assertThrows(IllegalArgumentException.class, () -> radio.ceiling(0));
        assertThrows(IllegalArgumentException.class, () -> radio.ceiling(2001));
        radio.frozen(true).ceiling(1050).sticks(0, 0, 0, 1);
        assertEquals(1050, mapper.map(Joystick.readSame(radio.info()).axes(), new boolean[16])[throttle]);
        // every ceiling there is holds to the unit, whatever the float nearest to it
        for (int ceiling = 2000; ceiling >= 1000; ceiling--) {
            FakeRadio r = new FakeRadio().ceiling(ceiling).sticks(0, 0, 0, 1);
            for (int reading = 0; reading < 5; reading++) {
                float axis = r.readSame(r.info()).axes()[FakeRadio.THROTTLE];
                assertTrue(1500.0 + 500.0 * axis <= ceiling, ceiling + ": " + axis);
                assertTrue(mapper.map(r.readSame(r.info()).axes(), new boolean[16])[throttle] <= ceiling, "ceiling " + ceiling);
            }
        }
    }

    @Test
    void handsOffWinsAgainstAThrottleRequestOfTheSameMoment() throws Exception {
        // ARM off and throttle down in one call, and held
        FakeRadio radio = new FakeRadio();
        radio.sticks(0.3, -0.2, 0.1, 0.4).aux(1, 1).aux(2, 1);
        radio.handsOff();
        float[] rest = radio.readSame(radio.info()).axes();
        assertEquals(-1f, rest[FakeRadio.THROTTLE], 0f, "throttle at its minimum, exactly");
        assertEquals(-1f, rest[FakeRadio.AUX1], 0f, "ARM off");
        assertEquals(0f, rest[FakeRadio.ROLL], 0.01f, "the sprung sticks let go");
        assertEquals(1f, rest[FakeRadio.AUX2], 0f, "the other switches stay");
        assertTrue(radio.isHandsOff());
        radio.sticks(0, 0, 0, 0.5);
        radio.axis(FakeRadio.THROTTLE, 1);
        radio.aux(1, 1);
        radio.sticks(0.5, 0, 0, -1); // a stick that neither arms nor throttles is no business of the stop
        float[] held = radio.readSame(radio.info()).axes();
        assertEquals(-1f, held[FakeRadio.THROTTLE], 0f);
        assertEquals(-1f, held[FakeRadio.AUX1], 0f);
        assertEquals(0.5f, held[FakeRadio.ROLL], 0.01f);
        assertEquals(3, radio.refused(), "each request for throttle or ARM while off is counted");
        radio.frozen(true).handsOn(); // frozen: what is held, without a reading's noise
        float[] on = radio.readSame(radio.info()).axes();
        assertEquals(-1f, on[FakeRadio.THROTTLE], 0f, "hands on moves nothing: what was refused is not kept for later");
        assertEquals(-1f, on[FakeRadio.AUX1], 0f);
        assertFalse(radio.isHandsOff());
        radio.frozen(false).aux(1, 1).sticks(0, 0, 0, 0.2);
        assertEquals(1f, radio.readSame(radio.info()).axes()[FakeRadio.AUX1], 0f, "and then the radio is a radio again");

        // the same moment, two threads, 2,000 times: one asks for full throttle and ARM, one says hands off - whichever
        // is first, the radio ends hands off, and no reading after the call's return says otherwise
        java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (int round = 0; round < 2000; round++) {
                FakeRadio r = new FakeRadio().sticks(0, 0, 0, 0.3).aux(1, 1);
                java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<?> asks = other.submit(() -> {
                    together.await();
                    for (int i = 0; i < 20; i++) {
                        r.sticks(0, 0, 0, 1);
                        r.aux(1, 1);
                        r.axis(FakeRadio.THROTTLE, 0.9);
                    }
                    return null;
                });
                together.await();
                r.handsOff();
                for (int reading = 0; reading < 20; reading++) { // while the other thread is still asking
                    float[] a = r.readSame(r.info()).axes();
                    assertEquals(-1f, a[FakeRadio.THROTTLE], 0f, "round " + round + ": throttle after hands off");
                    assertEquals(-1f, a[FakeRadio.AUX1], 0f, "round " + round + ": ARM after hands off");
                }
                asks.get(10, java.util.concurrent.TimeUnit.SECONDS);
                r.frozen(true); // no noise: what is held, exactly
                float[] end = r.readSame(r.info()).axes();
                assertEquals(-1f, end[FakeRadio.THROTTLE], 0f, "round " + round + ": throttle held at the end");
                assertEquals(-1f, end[FakeRadio.AUX1], 0f, "round " + round + ": ARM held at the end");
                r.handsOn(); // and nothing of the race was kept for the moment the hands come back
                float[] back = r.readSame(r.info()).axes();
                assertEquals(-1f, back[FakeRadio.THROTTLE], 0f, "round " + round + ": throttle with the hands back on");
                assertEquals(-1f, back[FakeRadio.AUX1], 0f, "round " + round + ": ARM with the hands back on");
            }
        } finally {
            other.shutdownNow();
        }

        // the worst moment there is, held still: a request for full throttle and ARM is caught between working out the
        // sticks and putting them back, and hands off is called just then. The stop waits its turn and has the last word
        FakeRadio caught = new FakeRadio().aux(1, 1);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1), goOn = new java.util.concurrent.CountDownLatch(1);
        caught.midMove = () -> {
            inside.countDown();
            try {
                goOn.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread asking = new Thread(() -> caught.sticks(0, 0, 0, 1), "selfTestAsks");
        asking.start();
        assertTrue(inside.await(10, java.util.concurrent.TimeUnit.SECONDS), "the request is under way");
        caught.midMove = null;
        Thread stopping = new Thread(caught::handsOff, "selfTestStops");
        stopping.start();
        assertTrue(de.droiddrone.testing.Waits.until(caught::isHandsOff, 10_000), "hands off is called");
        float[] meanwhile = caught.readSame(caught.info()).axes();
        assertEquals(-1f, meanwhile[FakeRadio.THROTTLE], 0f, "what the app reads is hands off at once, the request still under way");
        assertEquals(-1f, meanwhile[FakeRadio.AUX1], 0f);
        de.droiddrone.testing.Waits.until(() -> stopping.getState() != Thread.State.RUNNABLE, 5000); // waiting its turn (or, were it wrong, done already)
        goOn.countDown();
        asking.join(10_000);
        stopping.join(10_000);
        assertFalse(asking.isAlive() || stopping.isAlive());
        caught.frozen(true).handsOn();
        float[] kept = caught.readSame(caught.info()).axes();
        assertEquals(-1f, kept[FakeRadio.THROTTLE], 0f, "the request that was under way did not outlast the stop");
        assertEquals(-1f, kept[FakeRadio.AUX1], 0f, "nor did the ARM switch it found on");

        // for good: a watch's stop that no scenario opens again; the ARM switch may be another one
        FakeRadio last = new FakeRadio().armSwitch(3);
        last.aux(3, 1).aux(1, 1).sticks(0, 0, 0, 1);
        last.handsOffForGood("the battery is under its floor");
        float[] a = last.readSame(last.info()).axes();
        assertEquals(-1f, a[FakeRadio.AUX3], 0f, "the switch that arms on this board");
        assertEquals(1f, a[FakeRadio.AUX1], 0f);
        assertEquals(-1f, a[FakeRadio.THROTTLE], 0f);
        IllegalStateException no = assertThrows(IllegalStateException.class, last::handsOn);
        assertTrue(no.getMessage().contains("the battery is under its floor"), no.getMessage());
        assertTrue(last.isHandsOff() && last.limits().contains("for good"), last.limits());
    }

    @Test
    void theHardwareFileIsThisPcsOwnAndItsExampleIsMadeUp() throws Exception {
        Sandbox s = Sandbox.fresh("self-hardware");
        // where it belongs: the real user's LOCALAPPDATA, outside the repo (this JVM's own LOCALAPPDATA is a sealed one)
        Path place = Hardware.file();
        assertTrue(place.endsWith(Path.of("DraupneBench", "hardware.properties")), place.toString());
        assertTrue(place.startsWith(Path.of(System.getProperty("bench.realLocalAppData"))), place.toString());
        Path repo = Path.of(System.getProperty("bench.project")).toAbsolutePath().normalize();
        assertFalse(place.toAbsolutePath().normalize().startsWith(repo), "never in the repo");

        // not there: said so, with where it belongs and what to start from
        Path none = s.root().resolve("no-such").resolve(Hardware.FILE);
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> Hardware.read(none));
        assertTrue(missing.getMessage().contains(none.toString()) && missing.getMessage().contains(Hardware.EXAMPLE), missing.getMessage());

        // the example in the repo reads, and has nobody's values
        Path example = repo.resolve(Hardware.EXAMPLE);
        Hardware hw = Hardware.read(example);
        assertTrue(hw.phoneIp().startsWith("192.0.2."), "an address kept for examples: " + hw.phoneIp());
        assertEquals(de.droiddrone.common.UdpCommon.defaultPort, hw.phonePort());
        assertEquals("", hw.phoneKey());
        assertEquals("EXAMPLE0SERIAL", hw.phoneAdb());
        assertEquals("COM99", hw.boardPort());
        assertEquals(1, hw.armAux());
        assertEquals(4, hw.cells());
        assertEquals(14.0, hw.packFloor(), 1e-9);
        assertTrue(hw.keeperPort() > 1024);
        assertFalse(hw.relayed());
        assertThrows(IllegalStateException.class, hw::reach, "no reach from it in a JVM that is no hardware run");
        try (AutoCloseable back = asAHardwareRun()) {
            assertEquals(List.of(hw.phoneIp() + ":" + hw.phonePort()), hw.reach().pairs());
        }

        // what the sealed app is told of its network: the phone is there, online, a real phone to it, reached directly
        de.droiddrone.pc.setup.Tailscale.Status told = de.droiddrone.pc.setup.BenchSetupAccess.tailscaleStatus(hw.tailscaleStatus());
        assertTrue(told.installed() && told.signedIn(), told.toString());
        assertEquals(1, told.androidPeers().size());
        assertEquals(hw.phoneIp(), told.androidPeers().get(0).ip());
        assertEquals(de.droiddrone.pc.setup.Tailscale.PhoneState.ONLINE, de.droiddrone.pc.setup.Tailscale.phoneState(told, hw.phoneIp()));
        assertEquals("direct", de.droiddrone.pc.setup.Tailscale.phonePath(told, hw.phoneIp()));
        assertFalse(de.droiddrone.pc.fleet.VirtualFleet.isVirtual(hw.phoneIp()), "not a VIRTUAL drone's address");
        // and the app gets it through the bench's door, under a test's own answers
        try (FakeWeb web = new FakeWeb()) {
            BenchDoors doors = new BenchDoors(web);
            Outside.use(doors);
            String before = Shell.run(Duration.ofSeconds(2), "C:\\Program Files\\Tailscale\\tailscale.exe", "status", "--json").output();
            assertTrue(before.contains("\"Peer\":{}"), "no phone on the network unless a hardware run says so: " + before);
            doors.tailscaleStatus(hw.tailscaleStatus());
            String after = Shell.run(Duration.ofSeconds(2), "C:\\Program Files\\Tailscale\\tailscale.exe", "status", "--json").output();
            assertEquals(hw.tailscaleStatus(), after);
        }

        // a file of this bench's own: everything said, the key kept out of what is printed
        Path own = s.root().resolve(Hardware.FILE);
        Files.writeString(own, "phone.ip=192.0.2.44\nphone.port=7000\nphone.key=Secret16\nphone.path=relay\nboard.armAux=2\n");
        Hardware mine = Hardware.read(own);
        assertEquals("192.0.2.44", mine.phoneIp());
        assertEquals(7000, mine.phonePort());
        assertEquals("Secret16", mine.phoneKey());
        assertEquals(2, mine.armAux());
        assertFalse(mine.toString().contains("Secret16"), mine.toString());
        de.droiddrone.pc.setup.Tailscale.Status relayed = de.droiddrone.pc.setup.BenchSetupAccess.tailscaleStatus(mine.tailscaleStatus());
        assertTrue(de.droiddrone.pc.setup.Tailscale.phonePath(relayed, "192.0.2.44").startsWith("relay"), "a relayed way is said as one");
        // what the file does not say is asked for by name when it is needed, not guessed
        for (org.junit.jupiter.api.function.Executable needed : List.<org.junit.jupiter.api.function.Executable>of(mine::phoneAdb, mine::boardPort, mine::cells,
                mine::cellFloor, mine::packFloor, mine::keeperPort)) {
            assertTrue(assertThrows(IllegalStateException.class, needed).getMessage().contains(own.toString()));
        }
        // and a slip of the hand is said, not read as something else
        for (String wrong : List.of("phone.port=6286\n", "phone.ip=bench-phone.example\n", "phone.ip=192.0.2.0/24\n", "phone.ip=224.0.0.251\n",
                "phone.ip=192.0.2.10\nphone.prot=7000\n", "phone.ip=192.0.2.10\nphone.port=0\n", "phone.ip=192.0.2.10\nbattery.cells=40\n",
                "phone.ip=192.0.2.10\nbattery.cellFloor=35\n", "phone.ip=192.0.2.10\nphone.path=fast\n", "phone.ip=192.0.2.10\nboard.armAux=9\n",
                "phone.ip=192.0.2.10\nphone.key=more-than-sixteen-characters\n", "phone.ip=192.0.2.10\nkeeper.port=80\n")) {
            Files.writeString(own, wrong);
            IllegalStateException said = assertThrows(IllegalStateException.class, () -> Hardware.read(own), wrong);
            assertTrue(said.getMessage().contains(own.toString()), said.getMessage());
        }
    }

    @Test
    void aHardwareRunStartsOnlyWithNothingInItsWay() throws Exception {
        Sandbox s = Sandbox.fresh("self-hardware-start");
        // another DRAUPNE Link on this PC: found by its program's name (the virtual fleet is the same program)
        assertEquals("C:\\Program Files\\DRAUPNE Link\\DRAUPNE Link.exe", HardwareRun.draupneLinkAmong(java.util.stream.Stream.of("",
                "C:\\Windows\\explorer.exe", "C:\\Program Files\\Java\\bin\\java.exe", "C:\\Program Files\\DRAUPNE Link\\DRAUPNE Link.exe")));
        assertEquals("D:/apps/draupne link.EXE", HardwareRun.draupneLinkAmong(java.util.stream.Stream.of("D:/apps/draupne link.EXE")));
        assertNull(HardwareRun.draupneLinkAmong(java.util.stream.Stream.of("", "C:\\Program Files\\Java\\bin\\java.exe", "C:\\DRAUPNE Link\\notepad.exe")));

        // the link port on this PC: taken is taken, whoever has it
        try (DatagramSocket held = new DatagramSocket(0)) {
            assertFalse(HardwareRun.udpPortFree(held.getLocalPort()));
        }
        int free = de.droiddrone.testing.Ports.free();
        assertTrue(HardwareRun.udpPortFree(free));

        // what a scenario does not get to set, and what a hardware plan does not take
        assertEquals(List.of("ip", "port", "key", "throttle", "aux1", "axis2.max"), HardwareRun.notForAScenario(
                List.of("ip", "port", "key", "throttle", "aux1", "axis2.max", "mapSource", de.droiddrone.pc.ArmSwitch.KEY, "videoCodec")));
        List<String> no = new java.util.ArrayList<>();
        HardwareRun.plan(no, 0, false, false, false, List.of("videoCodec"));
        assertEquals(List.of(), no);
        HardwareRun.plan(no, 2, true, true, true, List.of("axis2.center"));
        assertEquals(5, no.size(), no.toString());

        // the throttle the app really sent, from its link logs
        Path logs = s.logsDir();
        assertEquals(-1, HardwareRun.mostThrottleSent(logs), "no logs: no sticks sent");
        Files.createDirectories(logs);
        String header = "time,link,armed,blockers,radio,rc_output,video_fps,throttle,channels,event";
        Files.writeString(logs.resolve("link-20261121-100000.csv"), header + "\r\n10:00:00.000,READY,0,,1,0,30,,,\r\n10:00:00.100,READY,0,,1,1,30,1000,1500 1500 1500 1000 1000,\r\n"
                + "10:00:00.200,READY,1,,1,1,30,1100,1500 1500 1500 1100 2000,\r\n10:00:00.300,,,,,,,,,link lost\r\n");
        Files.writeString(logs.resolve("link-20261121-100500.csv"), header + "\r\n10:05:00.000,READY,0,,1,1,30,1042,1500 1500 1500 1042 1000,\r\n10:05:00.1");
        Files.writeString(logs.resolve("app-2026-11-21.log"), "not a link log,throttle\n9999,9999\n");
        assertEquals(1100, HardwareRun.mostThrottleSent(logs));
        Files.writeString(logs.resolve("link-20261121-101000.csv"), "time,link,armed\r\n10:10:00.000,READY,0\r\n");
        assertThrows(java.io.IOException.class, () -> HardwareRun.mostThrottleSent(logs), "a log that cannot say is not taken for a good one");

        // and the rig itself: in a JVM that is no hardware run nothing is made, sealed or started - it says why and stops
        Path own = s.root().resolve(Hardware.FILE);
        Files.writeString(own, "phone.ip=" + PHONE + "\nphone.port=" + free + "\n");
        Hardware hw = Hardware.read(own);
        String home = System.getProperty("user.home");
        int windows = java.awt.Window.getWindows().length;
        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> AppRig.scenario("self-hardware-refused").hardware(hw).drones(1).setting("throttle", "-axis2").start());
        assertTrue(stopped.getMessage().contains("does not start"), stopped.getMessage());
        assertTrue(stopped.getMessage().contains("no hardware task's JVM"), stopped.getMessage());
        assertTrue(stopped.getMessage().contains("made-up drone") && stopped.getMessage().contains("[throttle]"), stopped.getMessage());
        assertFalse(Fence.isUp(), "no fence went up");
        assertEquals(home, System.getProperty("user.home"), "no user was made for it");
        assertEquals(windows, java.awt.Window.getWindows().length, "and no window");
        assertThrows(IllegalArgumentException.class, () -> AppRig.scenario("self-hardware-none").hardware(null));
    }

    /**
     * R5, the real phone's rig: it opens nothing in the fence itself. There is one way through, the fence's own
     * ({@link Fence.Reach}, handed to a scenario's plan), and the rig asks whether the fence that is up has it for its
     * phone - before a first packet would be refused, with what to do. No phone is touched: the fence is only asked.
     */
    @Test
    void thePhoneRigGoesThroughTheFenceTheFencesWayAndOpensNothingItself() throws Exception {
        Sandbox s = Sandbox.fresh("self-phone-reach");
        List<Path> roots = List.of(Sandbox.runRoot(), Path.of(System.getProperty("bench.build")));
        Path own = s.root().resolve(Hardware.FILE);
        Files.writeString(own, "phone.adb=BENCHPHONE0001\nphone.ip=" + PHONE + "\n");
        PhoneRig.Target target = PhoneRig.Target.device(Hardware.read(own), key -> null, key -> null, null);
        RecordingAdb adb = new RecordingAdb();
        PhoneRig rig = new PhoneRig(null, target, adb, ms -> { }, System::currentTimeMillis, null);
        int link = de.droiddrone.common.UdpCommon.defaultPort;

        // a scenario that put its fence up without the phone's reach: said at once, not found out from a refusal
        Fence.up(roots, Set.of("java.exe", "adb.exe"));
        IllegalStateException forgotten = assertThrows(IllegalStateException.class, rig::linkAddress);
        assertTrue(forgotten.getMessage().contains("reach(target.reach())"), forgotten.getMessage());
        Fence.down();
        // no hardware run: the phone's reach cannot even be made, so no plan can carry it
        assertThrows(IllegalStateException.class, target::reach);
        assertThrows(IllegalArgumentException.class, () -> AppRig.scenario("self-phone-none").reach(null));

        try (AutoCloseable back = asAHardwareRun()) {
            assertEquals(List.of(PHONE + ":" + link), target.reach().pairs());
            Fence.up(roots, Set.of("java.exe", "adb.exe"), target.reach()); // as AppRig does with a plan's reach
            assertEquals(target.link(), rig.linkAddress());
            Fence.down();
            Fence.up(roots, Set.of("java.exe", "adb.exe"), Fence.Reach.of("192.0.2.11:" + link)); // another phone's fence is not this one's
            assertThrows(IllegalStateException.class, rig::linkAddress);
            Fence.down();
            Fence.up(roots, Set.of("java.exe", "adb.exe"), Fence.Reach.of(PHONE + ":" + (link + 1))); // nor another port's
            assertThrows(IllegalStateException.class, rig::linkAddress);
            Fence.down();
        }
        assertEquals(List.of(), Fence.refusals(), "asked, never tried: nothing was refused and nothing sent");
        assertEquals(List.of(), adb.sent, "and nothing went to adb for it");
        assertTrue(PhoneRig.Target.emulator().reach().isEmpty(), "the emulator's reach is none: its scenarios' fence is what it always was");
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
