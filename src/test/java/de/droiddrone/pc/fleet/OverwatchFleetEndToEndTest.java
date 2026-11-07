package de.droiddrone.pc.fleet;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.PossiblyFlying;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.OverwatchHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.50, what the pilot hit testing OVERWATCH with the virtual fleet: a drone lost armed kept the PC "possibly
 * flying" for 10 min with no word why, the alert card was a black bar, and walkers and vehicles never showed - they
 * crossed in front of the drone's START point, not of the drone. Against DRAUPNE Link's real link and overwatch hub,
 * on loopback.
 */
class OverwatchFleetEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final List<VirtualDrone> drones = new ArrayList<>();
    private final List<ControlLink> links = new ArrayList<>();
    private OverwatchHub hub;
    private volatile boolean sending;
    private volatile int armValue = 1000;
    private Thread sticks;
    /** The clips' folder: removed at the end as far as it goes (the hub's thread may still close a file in it). */
    private final Path dir;

    OverwatchFleetEndToEndTest() throws java.io.IOException {
        dir = Files.createTempDirectory("draupne-overwatch-test");
    }

    @AfterEach
    void tearDown() {
        sending = false;
        if (sticks != null) sticks.interrupt();
        if (hub != null) hub.close();
        for (ControlLink l : links) l.close();
        for (VirtualDrone d : drones) d.stop();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // still open: the temp folder's own clean-up takes it
                }
            }
        } catch (java.io.IOException ignored) {
        }
    }

    private VirtualDrone drone(PcConfig config, String name, double lat, double lon) throws Exception {
        VirtualDrone d = new VirtualDrone(drones.size(), name, config.getKey(), InetAddress.getLoopbackAddress(), lat, lon, 135, // facing south-east
                new FlightModel.Setup(), l -> log.add(name + ": " + l));
        d.start(0);
        drones.add(d);
        return d;
    }

    private ControlLink link(PcConfig config, VirtualDrone d) throws Exception {
        ControlLink l = new ControlLink(config, InetAddress.getLoopbackAddress(), d.port(), 0);
        l.start();
        links.add(l);
        waitFor(() -> l.getState() == ControlLink.State.READY, 5000, "the link READY");
        return l;
    }

    /** The drone off its start point: flown somewhere, or PLACE PHONE. */
    private static void moveAway(VirtualDrone d) {
        assertTrue(d.moveTo(d.fc.latDeg() + 0.0009, d.fc.lonDeg() + 0.0012), "moved while disarmed");
    }

    /** Puts the drone to OVERWATCH from this link, with the hub watching for its token. */
    private void overwatch(ControlLink l, VirtualDrone d, String name, int token) throws Exception {
        if (hub == null) {
            hub = new OverwatchHub(new ClipIndex(dir));
            hub.start();
        }
        l.setOverwatchSink(hub::offer);
        waitFor(() -> l.getTelemetry().getPhonePower() != null, 3000, "the power record");
        hub.watch(name, token, null);
        long until = System.currentTimeMillis() + 3000;
        while (d.mode() != VirtualDrone.OVERWATCH && System.currentTimeMillis() < until) {
            l.sendOverwatch(1, 2, 0, token);
            Thread.sleep(200);
        }
        waitFor(() -> d.asleep() && d.overwatch.isWatching(), 3000, "overwatching");
        l.setDormant(true);
    }


    private OverwatchHub.View view(String drone) {
        for (OverwatchHub.View v : hub.views()) if (v.drone().equals(drone)) return v;
        return null;
    }

    /** The pictures before the alert that came, newest last. */
    private List<BufferedImage> preRolls(String drone) throws Exception {
        List<BufferedImage> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(hub.index().folder(drone))) {
            for (Path p : s.filter(p -> p.getFileName().toString().contains("-pre-")).sorted().toList()) out.add(ImageIO.read(p.toFile()));
        }
        return out;
    }

    private static int count(BufferedImage img, IntPredicate rgb) {
        int n = 0;
        if (img == null) return 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if (rgb.test(img.getRGB(x, y) & 0xffffff)) n++;
        return n;
    }

    /** The vehicle's red (0xb02020) through JPEG. */
    private static boolean red(int c) {
        int r = c >> 16 & 0xff, g = c >> 8 & 0xff, b = c & 0xff;
        return r > 130 && g < 80 && b < 80;
    }

    /** The walker's blue (0x283a8a) - not the sky's (0x6fa6d8). */
    private static boolean walker(int c) {
        int r = c >> 16 & 0xff, g = c >> 8 & 0xff, b = c & 0xff;
        return r < 80 && g < 100 && b > 100;
    }

    @Test
    void lostArmedThenAReconnectThatSaysDisarmedEndsTheWindowAndTheHeldAlertSaysWhy() throws Exception {
        PcConfig config = PcConfig.defaults();
        PossiblyFlying flag = new PossiblyFlying(MonoClock::ms);

        // MobileZulu flown from this PC, then 4G gone while armed; the pilot ends that link
        VirtualDrone zulu = drone(config, "MobileZulu", 61.1429, 10.6898);
        ControlLink first = link(config, zulu);
        flag.attach(first, "MobileZulu", () -> PossiblyFlying.of(first.getTelemetry().getStatus()));
        FcTelemetry t = first.getTelemetry();
        waitFor(() -> t.getStatus() != null, 3000, "the FC's status");
        startSticks(first);
        waitFor(() -> zulu.fc.hadSticks(), 2000, "the sticks reach the virtual FC");
        Thread.sleep(FlightModel.BOOT_GRACE_MS + 700);
        armValue = 1900;
        waitFor(() -> t.isArmedNow(MonoClock.ms(), 1000), 3000, "armed");
        zulu.link().silence(System.currentTimeMillis(), 4000);
        waitFor(() -> first.getState() != ControlLink.State.READY, 3000, "the PC counts the link lost");
        sending = false;
        flag.detach(first); // DISCONNECT while possibly flying: confirmed
        first.close();
        assertTrue(flag.holds(), "lost armed: it may still be up there");
        assertTrue(flag.reason().startsWith("MobileZulu was lost armed "), flag.reason());

        // meanwhile MobileX-ray, elsewhere, overwatches for this PC: a vehicle crosses in front of it
        VirtualDrone xray = drone(config, "MobileX-ray", 61.1440, 10.6910);
        moveAway(xray);
        ControlLink watchLink = link(config, xray);
        overwatch(watchLink, xray, "MobileX-ray", 0x5eed0001);
        hub.setFlag(flag);
        Thread.sleep(3500); // the detector's warm-up, as on the phone
        long crossed = System.currentTimeMillis();
        xray.addTarget("vehicle");
        waitFor(() -> view("MobileX-ray") != null, 6000, "the alert while the vehicle still crosses");
        assertTrue(xray.scene.hasTargets(System.currentTimeMillis()), "it alerted while the vehicle was in the picture ("
                + (System.currentTimeMillis() - crossed) + " ms)");
        waitFor(() -> view("MobileX-ray").picture() != null && view("MobileX-ray").preRolls() > 0, 3000, "the pictures before the alert on the card");
        OverwatchHub.View held = view("MobileX-ray");
        assertNotNull(held.held(), "the card says why its live preview waits");
        assertTrue(held.held().startsWith("MobileZulu was lost armed "), held.held());
        assertFalse(held.live(), "no preview while a drone here may be flying");
        int redSeen = 0;
        for (BufferedImage img : preRolls("MobileX-ray")) redSeen = Math.max(redSeen, count(img, OverwatchFleetEndToEndTest::red));
        assertTrue(redSeen > 8, "the vehicle is in the pictures before the alert: " + redSeen + " red pixels at most");

        // CONNECT to MobileZulu again: it says DISARMED (the failsafe dropped it) - the window ends at once
        ControlLink again = link(config, zulu);
        flag.attach(again, "MobileZulu", () -> PossiblyFlying.of(again.getTelemetry().getStatus()));
        waitFor(() -> again.getTelemetry().getStatus() != null && !again.getTelemetry().getStatus().armed(), 3000, "a fresh DISARMED");
        assertFalse(flag.holds(), "reconnected and disarmed: nothing holds this PC back");
        waitFor(() -> view("MobileX-ray").held() == null, 2000, "the card no longer paused");
        waitFor(() -> view("MobileX-ray").live(), 8000, "the live preview comes");
        assertTrue(count(view("MobileX-ray").picture(), c -> (c & 0xffffff) != 0) > 1000, "a picture, not black");
    }

    @Test
    void alertNowGivesAFullAlertWithSomethingInEveryPicture() throws Exception {
        PcConfig config = PcConfig.defaults();
        VirtualDrone zulu = drone(config, "MobileZulu", 61.1429, 10.6898);
        moveAway(zulu);
        ControlLink l = link(config, zulu);
        overwatch(l, zulu, "MobileZulu", 0x5eed0002);
        hub.setFlag(new PossiblyFlying(MonoClock::ms)); // nothing here flies
        Thread.sleep(1200); // a few pictures before it
        zulu.alertNow();
        waitFor(() -> view("MobileZulu") != null, 3000, "the alert");
        waitFor(() -> view("MobileZulu").preRolls() > 0, 3000, "the pictures before it");
        List<BufferedImage> pre = preRolls("MobileZulu");
        assertTrue(count(pre.get(pre.size() - 1), OverwatchFleetEndToEndTest::walker) > 15,
                "the walker is in the last picture before the alert");
        assertNull(view("MobileZulu").held());
        waitFor(() -> view("MobileZulu").live(), 8000, "the live preview");
        assertTrue(count(view("MobileZulu").picture(), OverwatchFleetEndToEndTest::walker) > 30, "the walker in the preview");
    }

    @Test
    void connectedWithTheDefaultDetectionAWalkerGetsBoxes() throws Exception {
        PcConfig config = PcConfig.defaults();
        VirtualDrone zulu = drone(config, "MobileZulu", 61.1429, 10.6898);
        moveAway(zulu);
        ControlLink l = link(config, zulu);
        l.setDetection(2); // MainWindow's default for a drone never set: NORMAL
        waitFor(() -> zulu.readout().asked().contains("detection 2"), 3000, "the drone's card says what the PC asked");
        Thread.sleep(3500); // its warm-up
        zulu.addTarget("walker");
        waitFor(() -> !l.getTelemetry().getMotion().shown(MonoClock.ms()).isEmpty(), 8000, "boxes on the PC's video");
        assertTrue(zulu.scene.hasTargets(System.currentTimeMillis()), "while the walker still crosses");
        assertTrue(log.stream().anyMatch(s -> s.contains("a walker crosses the picture (motion detection 2)")), String.join("\n", log));
    }

    /** The PC's stick frames, 50 a second: throttle low, AUX1 at armValue. */
    private void startSticks(ControlLink l) {
        sending = true;
        sticks = new Thread(() -> {
            while (sending) {
                l.sendRc(new short[]{1500, 1500, 1500, 1000, (short) armValue, 1500, 1000, 1000});
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "testSticks");
        sticks.setDaemon(true);
        sticks.start();
    }

    private void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for " + what + "\n" + String.join("\n", log));
            Thread.sleep(20);
        }
    }
}
