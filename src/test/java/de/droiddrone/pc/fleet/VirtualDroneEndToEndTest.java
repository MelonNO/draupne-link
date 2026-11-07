package de.droiddrone.pc.fleet;

import de.droiddrone.common.Observe;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.video.VideoPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A virtual drone against DRAUPNE Link's real link code on loopback (ephemeral ports both sides): the session comes
 * up, the telemetry arrives, the sticks arm it, a 4G loss ends in Betaflight's failsafe; the generated video decodes
 * in the PC's pipeline; deep sleep's check-in reaches the PC and wakes it; an observer gets its stream.
 */
class VirtualDroneEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link;
    private volatile boolean sending;
    private volatile int armValue = 1000;
    private Thread sticks;

    @AfterEach
    void tearDown() {
        sending = false;
        if (sticks != null) sticks.interrupt();
        if (link != null) link.close();
        if (drone != null) drone.stop();
    }

    private void startDrone(PcConfig config) throws Exception {
        droneStartedAt = System.currentTimeMillis();
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.start(0);
    }

    private void startLink(PcConfig config, ControlLink.VideoListener video) throws Exception {
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), drone.port(), 0);
        if (video != null) link.setVideoListener(video, true);
        link.start();
    }

    /** The PC's stick frames, 50 a second: throttle low, AUX1 at armValue. */
    private void startSticks() {
        sending = true;
        sticks = new Thread(() -> {
            while (sending) {
                link.sendRc(new short[]{1500, 1500, 1500, 1000, (short) armValue, 1500, 1000, 1000});
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

    @Test
    void connectsSendsTelemetryArmsOnTheSticksAndFailsSafeOnA4gLoss() throws Exception {
        PcConfig config = PcConfig.defaults();
        startDrone(config);
        startLink(config, null);
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link READY");
        FcTelemetry t = link.getTelemetry();
        waitFor(() -> t.getStatus() != null && t.hasBoxNames() && t.getAttitude() != null, 5000, "the FC's status, modes and attitude");
        waitFor(() -> t.getPhonePcBuild() == 38, 3000, "the phone app's version");
        assertNotNull(link.getFcInfo(), "FcInfo answered");
        assertFalse(t.getStatus().armed());
        waitFor(() -> t.getGps() != null && t.getBattery() != null, 3000, "GPS and battery");
        assertEquals(Fleet.DEFAULT_LAT, t.getGps().latDeg(), 1e-3);

        startSticks();
        waitFor(() -> drone.fc.hadSticks(), 2000, "the sticks reach the virtual FC");
        Thread.sleep(Math.max(0, FlightModel.BOOT_GRACE_MS + 700 - (System.currentTimeMillis() - startedAt())));
        armValue = 1900;
        waitFor(() -> t.isArmedNow(de.droiddrone.pc.MonoClock.ms(), 1000), 3000, "armed, as the PC's telemetry says");
        assertTrue(drone.fc.isArmed());

        // 4G gone: nothing either way for 4 s - Betaflight's failsafe drops it
        drone.link().silence(System.currentTimeMillis(), 4000);
        waitFor(() -> !drone.fc.isArmed() && drone.fc.failsafe() == FlightModel.Failsafe.DROPPED, 3500, "failsafe, dropped");
        assertEquals("failsafe (drop)", drone.fc.lastDisarm());
        waitFor(() -> link.getState() != ControlLink.State.READY, 3000, "the PC counts the link lost");
        // back: the PC reconnects by itself and sees it disarmed; the switch still on doesn't re-arm it
        waitFor(() -> link.getState() == ControlLink.State.READY, 8000, "the link back");
        waitFor(() -> t.getStatus() != null && de.droiddrone.pc.MonoClock.ms() - t.getStatus().receivedAt() < 300 && !t.getStatus().armed(),
                3000, "a fresh status: disarmed");
        Thread.sleep(500);
        assertFalse(drone.fc.isArmed(), "the arm switch must go off and on after a failsafe");
    }

    private long droneStartedAt;

    private long startedAt() {
        return droneStartedAt;
    }

    @Test
    void theGeneratedPictureDecodesInThePcsPipeline() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("cameraWidth", "640");
        config.set("cameraHeight", "360");
        config.set(ControlLink.VIDEO_MODE_KEY, "NORMAL"); // the set size, not AUTO's sharp tier
        startDrone(config);
        Sink sink = new Sink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> 30)) {
            startLink(config, video);
            video.attach(link);
            waitFor(() -> sink.presented.get() >= 20, 15_000, "decoded pictures on the PC");
            assertTrue(link.isVideoConfigReceived());
            assertNull(video.stats().error(), "no decoder error");
            assertEquals(640, sink.width, "the size the PC asked for");
            int lit = 0;
            for (int px : sink.pixels) if ((px & 0xffffff) != 0) lit++;
            assertTrue(lit > sink.pixels.length / 2, "a picture, not black");
        }
    }

    @Test
    void deepSleepChecksInAndThePcWakesIt() throws Exception {
        PcConfig config = PcConfig.defaults();
        startDrone(config);
        startLink(config, null);
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link READY");
        FcTelemetry t = link.getTelemetry();
        waitFor(() -> t.getPhonePower() != null, 3000, "the power record");
        long until = System.currentTimeMillis() + 3000;
        while (drone.mode() != VirtualDrone.DEEP_SLEEP && System.currentTimeMillis() < until) {
            link.sendPowerMode(ControlLink.POWER_DEEP_SLEEP, 1);
            Thread.sleep(200);
        }
        waitFor(() -> drone.asleep(), 3000, "asleep after the countdown");
        link.setDormant(true);
        waitFor(() -> link.getState() != ControlLink.State.READY, 4000, "the link down: the phone sleeps");
        drone.checkNow(); // its check window: the hello, answered "sleep on" while the PC is dormant
        waitFor(() -> link.getSleepHello() != null, 3000, "the sleep check's hello at the PC");
        waitFor(() -> log.stream().anyMatch(l -> l.contains("the PC says sleep on")), 3000, "the PC's answer: sleep on");
        assertTrue(drone.asleep());
        link.setDormant(false); // WAKE: the next check-in wakes it
        drone.checkNow();
        waitFor(() -> drone.mode() == VirtualDrone.NORMAL && !drone.asleep(), 3000, "woken by the PC");
        waitFor(() -> link.getState() == ControlLink.State.READY, 8000, "the link back");
        assertTrue(log.stream().anyMatch(l -> l.contains("sleep check: listening")), String.join("\n", log));
    }

    @Test
    void overwatchCallsThePcsHubAndItsAlertIsTaken(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        PcConfig config = PcConfig.defaults();
        startDrone(config);
        startLink(config, null);
        de.droiddrone.pc.overwatch.OverwatchHub hub = new de.droiddrone.pc.overwatch.OverwatchHub(new de.droiddrone.pc.overwatch.ClipIndex(dir));
        try {
            hub.start();
            link.setOverwatchSink(hub::offer);
            waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link READY");
            waitFor(() -> link.getTelemetry().getPhonePower() != null, 3000, "the power record");
            int token = 0x5eed1234;
            hub.watch("MobileZulu", token, null);
            long until = System.currentTimeMillis() + 3000;
            while (drone.mode() != VirtualDrone.OVERWATCH && System.currentTimeMillis() < until) {
                link.sendOverwatch(1, 2, 0, token);
                Thread.sleep(200);
            }
            waitFor(() -> drone.asleep() && drone.overwatch.isWatching(), 3000, "overwatching");
            link.setDormant(true);
            Thread.sleep(3500); // the detector's warm-up
            drone.someoneNow(); // someone walks past: the detector finds it, and that calls the PC
            waitFor(() -> log.stream().anyMatch(l -> l.contains("the PC has the alert")), 8000, "the hub acknowledged the alert (its token)");
            waitFor(() -> !hub.views().isEmpty(), 2000, "the alert on the PC's map");
            assertEquals("MobileZulu", hub.views().get(0).drone());
        } finally {
            hub.close();
        }
    }

    /**
     * Every drawn model, by day and by night, seen by the real detector within a few seconds while it still crosses -
     * from a drone off its start point and turned (1.0.50: they crossed in front of the start point), on the wall clock
     * the virtual drone uses (1.0.50: the detector's float times failed on it).
     */
    @Test
    void theRealDetectorSeesEveryModelCrossTheGeneratedPicture() {
        Scene.View view = new Scene.View(130, -75, 0, 0, 0, 220);
        for (Scene.Kind kind : Scene.Kind.values()) {
            for (boolean night : new boolean[]{false, true}) {
                if (kind == Scene.Kind.LIGHT && !night) continue; // a lamp by day: the person carrying it is the WALKER's case
                Scene scene = new Scene("MobileZulu", 1001);
                scene.setNight(night);
                de.droiddrone.common.MotionDetector detector = new de.droiddrone.common.MotionDetector(de.droiddrone.common.MotionDetector.NORMAL);
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(320, 180, java.awt.image.BufferedImage.TYPE_INT_RGB);
                long t0 = System.currentTimeMillis(), added = t0 + 4000, first = -1, until = 0;
                for (long t = t0; t < t0 + 16_000; t += 200) { // five pictures a second, as the drone's loop
                    if (t == added) {
                        scene.addTarget(kind, view, t); // after the background has settled
                        until = scene.targets(t).get(0).untilMs;
                    }
                    scene.render(img, view, t, null, false);
                    int[] size = new int[2];
                    byte[] y = Scene.luma(Scene.pixels(img), 320, 180, 320, size);
                    de.droiddrone.common.MotionDetector.Result r = detector.process(y, size[0], size[1], null, null, t);
                    if (!r.detections.isEmpty() && first < 0) first = t;
                    if (t < added) assertTrue(r.detections.isEmpty(), kind + " night " + night + ": nothing before it");
                }
                assertTrue(first > 0 && first - added <= 6000 && first < until, kind + " night " + night + ": seen "
                        + (first - added) + " ms after it was sent, crossing until " + (until - added));
            }
        }
    }

    /** Nothing in the fleet makes up a detection: only the detector's verdict on a picture reaches an alert or a box. */
    @Test
    void noDetectionIsMadeUp() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/java/de/droiddrone/pc/fleet");
        if (!java.nio.file.Files.isDirectory(dir)) dir = java.nio.file.Path.of("../../src/main/java/de/droiddrone/pc/fleet"); // the tests' working folder
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path f : s.toList()) {
                String src = java.nio.file.Files.readString(f);
                assertFalse(src.contains("new MotionRecord.Box("), f + " makes a detection box of its own");
                assertFalse(src.contains("new MotionDetector.Detection(") || src.contains("new Detection("), f + " makes a detection of its own");
            }
        }
        String overwatch = java.nio.file.Files.readString(dir.resolve("VirtualOverwatch.java"));
        long calls = java.util.regex.Pattern.compile("(?<![.\\w])onDetection\\(").matcher(overwatch).results().count(); // not OverwatchEvent's
        assertEquals(2L, calls, "onDetection: its definition and the one call from onPicture (the detector's report)");
        String drone = java.nio.file.Files.readString(dir.resolve("VirtualDrone.java"));
        assertEquals(1, drone.split("overwatch\\.onPicture\\(", -1).length - 1, "the analysis picture's report is the only input");
        assertTrue(drone.contains("MotionRecord.of(flags, (float) Scene.HFOV_DEG, r)"), "the report is made from the detector's result");
    }

    @Test
    void anObserverGetsItsStream() throws Exception {
        PcConfig config = PcConfig.defaults();
        startDrone(config);
        try (DatagramSocket observer = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            observer.setSoTimeout(200);
            byte[] ask = Observe.encodeRequest(config.getKey(), 640, 360, 15, 1000, Observe.FLAG_VIDEO_CONFIG, 0);
            AtomicInteger state = new AtomicInteger(-1);
            boolean config0 = false, keyFrame = false;
            long end = System.currentTimeMillis() + 10_000, nextAsk = 0;
            byte[] buf = new byte[1500];
            while (System.currentTimeMillis() < end && !(config0 && keyFrame)) {
                if (System.currentTimeMillis() >= nextAsk) {
                    nextAsk = System.currentTimeMillis() + 1000;
                    observer.send(new DatagramPacket(ask, ask.length, InetAddress.getLoopbackAddress(), drone.port()));
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    observer.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                byte type = p.getData()[0];
                if (type == Observe.STATE) state.set(Observe.decodeState(p.getData(), p.getLength()).state);
                if (type == UdpCommon.VideoInitialFrame) config0 = true;
                if (type == UdpCommon.KeyFrame) keyFrame = true;
            }
            assertEquals(Observe.STREAMING, state.get(), "OBSERVE_STATE: streaming");
            assertTrue(config0, "the video config");
            assertTrue(keyFrame, "a key frame");
            observer.send(new DatagramPacket(Observe.encodeStop(config.getKey()), Observe.encodeStop(config.getKey()).length,
                    InetAddress.getLoopbackAddress(), drone.port()));
        }
    }

    /** What the PC's video pipeline would put on screen. */
    static final class Sink implements VideoPipeline.FrameSink {
        final int[] pixels = new int[320 * 180];
        final AtomicInteger presented = new AtomicInteger();
        volatile int width;

        @Override
        public void videoStarted(int w, int h, boolean mirror) {
            width = w;
        }

        @Override
        public int[] targetSize() {
            return new int[]{320, 180};
        }

        @Override
        public int[] beginFrame(int w, int h) {
            return pixels;
        }

        @Override
        public void presentFrame() {
            presented.incrementAndGet();
        }
    }

    private void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for " + what + "\n" + String.join("\n", log));
            Thread.sleep(20);
        }
    }
}
