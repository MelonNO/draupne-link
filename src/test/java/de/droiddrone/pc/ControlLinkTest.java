package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.ReceiverBuffer;
import de.droiddrone.common.SavedPacket;
import de.droiddrone.common.SettingsCommon;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;
import de.droiddrone.common.UdpSender;
import de.droiddrone.pc.video.TestStreams;
import de.droiddrone.pc.video.VideoPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs ControlLink against a fake Flight phone built from upstream's own ReceiverBuffer/UdpSender in
 * "direct mode" (isServer = true), the same way upstream Flight/Udp.java sets them up.
 */
class ControlLinkTest {
    private static final int PHONE_PORT = 46286;
    private static final int PC_PORT = 46287;
    private static final int RELAY_PORT = 46300; // Connection uses RELAY_PORT + 1 locally in host mode
    private static final int RELAY_PHONE_PORT = 46310;

    private FakeFlightPhone phone;
    private ControlLink link;
    private Connection connection;

    @AfterEach
    void tearDown() {
        if (link != null) link.close();
        if (connection != null) connection.close();
        if (phone != null) phone.close();
    }

    /** Records what the video pipeline would put on screen. */
    static final class CapturingSink implements VideoPipeline.FrameSink {
        final int[] pixels = new int[160 * 120];
        final AtomicInteger presented = new AtomicInteger();
        volatile int startedWidth;

        @Override
        public void videoStarted(int width, int height, boolean mirror) {
            startedWidth = width;
        }

        @Override
        public int[] targetSize() {
            return new int[]{160, 120};
        }

        @Override
        public int[] beginFrame(int width, int height) {
            return pixels;
        }

        @Override
        public void presentFrame() {
            presented.incrementAndGet();
        }
    }

    @Test
    void videoStreamsFromPhoneToScreen() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.videoStream = TestStreams.h264(320, 240, 40, 10);
        CapturingSink sink = new CapturingSink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> 30)) {
            link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
            link.setVideoListener(video, false);
            video.attach(link);
            link.start();
            waitFor(() -> sink.presented.get() >= 30, 10_000, "decoded frames on screen");
            assertFalse(phone.requestedHevc, "asked for H.264 as configured");
            assertEquals(320, sink.startedWidth);
            int px = sink.pixels[60 * 160 + 80];
            assertTrue(((px >> 16) & 0xff) > 200 && ((px >> 8) & 0xff) < 60, "red picture, got " + Integer.toHexString(px));
            assertTrue(link.isVideoConfigReceived());
            assertNull(video.stats().error());
        }
    }

    @Test
    void lostFragmentDoesNotStopTheVideo() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.videoStream = TestStreams.h264(320, 240, 40, 20, true); // textured: frames span several packets
        phone.dropFrame = 5; // a P-frame (key frames are 1 and 21)
        CapturingSink sink = new CapturingSink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> 30)) {
            link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
            link.setVideoListener(video, true);
            video.attach(link);
            link.start();
            long end = System.currentTimeMillis() + 10_000;
            while (sink.presented.get() < 30 && System.currentTimeMillis() < end) Thread.sleep(50);
            assertTrue(sink.presented.get() >= 30, "presented " + sink.presented.get() + ", " + video.stats());
            VideoPipeline.Stats s = video.stats();
            assertEquals(1, s.damaged(), "the frame with the hole is shown concealed, not waited for");
            assertEquals(0, s.lost());
        }
    }

    @Test
    void noVideoRequestedWithoutListener() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        Thread.sleep(700);
        assertEquals(0, phone.startVideoRequests.get(), "RC-only links (and the setup's link test) don't start the camera");
    }

    @Test
    void handshakeConfigAndRcReachThePhone() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();

        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        ParsedConfig cfg = phone.config;
        assertNotNull(cfg);
        assertEquals(ControlLink.ANNOUNCED_RC_RATE, cfg.rcRefreshRate, "keeps the Flight app's frame-drop window at 1 ms");
        assertEquals(1280, cfg.cameraWidth);
        assertEquals(FcCommon.FC_PROTOCOL_MSP, cfg.fcProtocol);
        assertEquals(0, cfg.trailingBytes, "Config packet must be fully consumed by the Flight parser layout");

        waitFor(() -> link.getFcInfo() != null, 3000, "FC info");
        assertEquals("Betaflight", link.getFcInfo().getFcName());

        short[] sticks = {1500, 1510, 1490, 1000, 1000, 2000};
        assertTrue(link.sendRc(sticks));
        waitFor(() -> !phone.rcFrames.isEmpty(), 2000, "RC frame");
        short[] received = phone.rcFrames.get(phone.rcFrames.size() - 1);
        assertEquals(8, received.length, "padded so the Flight app always applies Betaflight's channel map");
        assertArrayEquals(sticks, java.util.Arrays.copyOf(received, sticks.length));
    }

    /** The stick frame's bytes: upstream's layout, and with the sequence byte for phone apps that read it. */
    @Test
    void rcFrameCarriesASequenceByteOnlyWhenAsked() throws Exception {
        short[] frame = {1500, 1600, 1400, 1000, 2000, 1000, 1000, 1000};
        byte[] old = ControlLink.rcFrame(frame, -1);
        assertEquals(2 + 8 * 2, old.length, "[24][count][8 x int16]");
        assertEquals(UdpCommon.RcFrame, old[0]);
        assertEquals(8, old[1]);
        assertEquals(1600, ((old[4] & 0xff) << 8) | (old[5] & 0xff), "big-endian pitch");
        byte[] withSeq = ControlLink.rcFrame(frame, 200);
        assertEquals(old.length + 1, withSeq.length);
        assertArrayEquals(old, java.util.Arrays.copyOf(withSeq, old.length), "the same frame in front");
        assertEquals(200, withSeq[withSeq.length - 1] & 0xff);
        assertEquals(255, ControlLink.rcFrame(frame, 255)[withSeq.length - 1] & 0xff, "the whole byte");
    }

    /** A phone app from pc.19 on gets numbered frames; an older one (which refuses extra bytes) the old frames. */
    @Test
    void sequenceByteGoesOutOnlyToAPhoneAppThatReadsIt() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.appVersion = "2.3.0-pc.18";
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        waitFor(() -> "2.3.0-pc.18".equals(link.getTelemetry().getPhoneAppVersion()), 5000, "version known");
        short[] sticks = {1500, 1500, 1500, 1000};
        assertTrue(link.sendRc(sticks));
        waitFor(() -> phone.rcFrames.size() >= 1, 2000, "RC frame");
        assertTrue(phone.rcSeqs.isEmpty(), "pc.18: no sequence byte");
        phone.appVersion = "2.3.0-pc.19";
        waitFor(() -> link.getTelemetry().getPhonePcBuild() == 19, 5000, "newer version known");
        for (int i = 0; i < 3; i++) assertTrue(link.sendRc(sticks));
        waitFor(() -> phone.rcSeqs.size() >= 3, 2000, "numbered frames");
        int first = phone.rcSeqs.get(0);
        assertEquals(List.of(first, (first + 1) & 0xff, (first + 2) & 0xff), phone.rcSeqs.subList(0, 3), "counted up per frame");
        assertEquals(4, phone.rcFrames.size(), "every frame reached the phone whole");
    }

    /**
     * Live audio (1.0.45): a pc.34 phone is asked for the sound (AUDIO on, again each second), its config and frames
     * reach the listener; muted, the request stays on for STOP_AFTER_MUTED_MS (here: checked by AudioRequestTest) and
     * the speakers are the caller's.
     */
    @Test
    void liveAudioIsAskedForAndArrives() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.appVersion = "2.3.0-pc.34";
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        List<de.droiddrone.pc.audio.AudioConfig> configs = new CopyOnWriteArrayList<>();
        List<byte[]> frames = new CopyOnWriteArrayList<>();
        link.setAudioListener(new ControlLink.AudioListener() {
            @Override
            public void onAudioConfig(de.droiddrone.pc.audio.AudioConfig config) {
                configs.add(config);
            }

            @Override
            public void onAudioFrame(de.droiddrone.pc.audio.AudioConfig config, byte[] frame, long arrivedMs) {
                frames.add(frame);
            }
        });
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        waitFor(() -> phone.audioRequests.size() >= 2, 4000, "two audio requests");
        assertTrue(phone.audioRequests.get(0).on, "not muted: on");
        assertEquals(de.droiddrone.common.PcAudio.DEFAULT_KBPS, phone.audioRequests.get(0).kbps);
        assertEquals(ControlLink.AudioState.WAITING, link.audioState());
        phone.sendAudioFrame(new byte[]{1, 2, 3}); // before the config: kept from the listener (it can't be decoded)
        phone.sendAudioConfig();
        waitFor(() -> configs.size() == 1, 2000, "audio config");
        assertTrue(configs.get(0).sameAs(de.droiddrone.pc.audio.AudioConfig.DEFAULT), "48 kHz mono, its AudioSpecificConfig");
        phone.sendAudioFrame(new byte[]{4, 5, 6, 7});
        waitFor(() -> frames.size() == 1, 2000, "audio frame");
        assertArrayEquals(new byte[]{4, 5, 6, 7}, frames.get(0), "the raw frame, without its type byte");
        assertEquals(ControlLink.AudioState.ON, link.audioState());
    }

    /** A phone app older than pc.34 is never sent AUDIO; the window says why there is no sound. */
    @Test
    void anOlderPhoneGetsNoAudioRequest() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.appVersion = "2.3.0-pc.33";
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        waitFor(() -> link.getTelemetry().getPhonePcBuild() == 33, 5000, "version known");
        Thread.sleep(1500);
        assertTrue(phone.audioRequests.isEmpty(), "no AUDIO to pc.33");
        assertEquals(ControlLink.AudioState.OLD_PHONE, link.audioState());
    }

    /** Nothing from the phone for 2 s: the link counts as lost, well before upstream's 4000 ticks. */
    @Test
    void silenceOfTwoSecondsIsALinkLoss() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        Thread.sleep(300);
        assertTrue(link.isConnected());
        phone.silent = true;
        long quietFrom = System.currentTimeMillis();
        waitFor(() -> !link.isConnected(), ControlLink.LINK_LOST_MS + 1500, "link lost");
        long took = System.currentTimeMillis() - quietFrom;
        assertTrue(took >= ControlLink.LINK_LOST_MS - 200, "not before the 2 s: " + took + " ms");
        assertEquals(ControlLink.State.CONNECTING, link.getState());
        // the phone back: the handshake again
        phone.silent = false;
        waitFor(() -> link.getState() == ControlLink.State.READY, 6000, "READY again");
    }

    @Test
    void linkLogRecordsTheTimeline(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        for (int i = 0; i < 25; i++) java.nio.file.Files.writeString(dir.resolve(String.format("link-20260101-0000%02d.csv", i)), "old");
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.phoneStateFlags = 0b1001; // channel map known, camera open
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        waitFor(() -> link.getTelemetry().getStatus() != null, 5000, "FC status");
        java.nio.file.Path file;
        try (LinkLog log = LinkLog.open(dir)) {
            file = log.file();
            short[] sticks = {1500, 1500, 1500, 1000, 1000};
            for (int i = 0; i < 5; i++) {
                link.sendRc(sticks);
                Thread.sleep(20);
            }
            waitFor(() -> {
                FcTelemetry.PhoneState s = link.getTelemetry().getPhoneState();
                return s != null && s.rcWriteAgeMs() == 4 && link.getPing() >= 0;
            }, 3000, "the phone's state after the sticks, and a ping");
            log.sample(link, true, true, 0, sticks, "direct", null, 7);
            log.event("Joystick lost, test");
        }
        try (var files = java.nio.file.Files.list(dir)) {
            assertEquals(LinkLog.KEEP, files.count(), "only the newest logs are kept");
        }
        List<String> lines = java.nio.file.Files.readAllLines(file);
        assertEquals(LinkLog.HEADER, lines.get(0));
        String[] row = lines.get(1).split(",", -1);
        assertEquals("READY", row[1]);
        assertEquals("5", row[3], "RC frames in the interval");
        assertTrue(Integer.parseInt(row[4]) >= 15, "longest gap between them, ms");
        assertEquals("0", row[8], "no RXLOSS");
        assertEquals("ANGLE MSP", row[11], "the fake FC's arming blocks");
        assertEquals("1000", row[15], "throttle as sent");
        assertEquals("1500 1500 1500 1000 1000", row[16], "every channel as sent");
        assertTrue(Long.parseLong(row[18]) >= -1, "age of the last pong");
        assertEquals("direct", row[19], "Tailscale path");
        List<String> columns = List.of(LinkLog.HEADER.split(","));
        assertEquals("4", row[columns.indexOf("phone_rc_write_age_ms")]);
        assertEquals("3", row[columns.indexOf("phone_rc_in_to_out_ms")]);
        assertEquals("", row[columns.indexOf("phone_rc_seq")], "an old-layout frame: the phone saw no sequence");
        assertEquals("9", row[columns.indexOf("phone_flags")]);
        assertEquals(Integer.toString(Integer.parseInt(row[2]) / 2 + 3), row[columns.indexOf("pc_to_fc_ms")], "half the ping + the phone's in-to-out");
        assertEquals("7", row[columns.indexOf("glfw_poll_max_ms")]);
        assertEquals("event", columns.get(columns.size() - 1), "the event stays the last column");
        assertEquals(LinkLog.HEADER.split(",").length, row.length, "every column in every row");
        assertEquals(LinkLog.HEADER.split(",").length, lines.get(2).replace("\"Joystick lost, test\"", "e").split(",", -1).length,
                "event rows too (the event's own comma is inside quotes)");
        assertTrue(lines.get(2).endsWith(",Joystick lost, test") || lines.get(2).endsWith(",\"Joystick lost, test\""));
    }

    @Test
    void phoneThatStopsForwardingFcDataCountsAsFcLost() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        assertFalse(link.isFcLost(), "no verdict during the grace period");
        // the fake phone forwards FC data once (with the box names), then goes quiet - like an unplugged OTG cable
        waitFor(() -> link.getTelemetry().getStatus() != null, 5000, "FC data");
        assertFalse(link.isFcLost());
        waitFor(link::isFcLost, ControlLink.FC_GRACE_MS + ControlLink.FC_SILENCE_MS + 2000, "FC lost after the silence");
        assertEquals(ControlLink.State.READY, link.getState(), "the link to the phone itself is still up");
    }

    @Test
    void flightControllerStatusArrivesThroughTheLink() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getTelemetry().getStatus() != null, 8000, "FC status via telemetry");
        FcTelemetry.Status s = link.getTelemetry().getStatus();
        assertEquals(List.of("ANGLE", "MSP"), s.blockers().stream().map(FcTelemetry.Blocker::osdName).toList());
        assertEquals(List.of("ANGLE"), s.activeModes(), "mode names were requested and applied");
    }

    @Test
    void rcGoesOutOnlyWhenTheInputLoopSendsIt() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        short[] sticks = {1500, 1500, 1500, 1000};
        link.start();
        assertFalse(link.sendRc(sticks), "nothing is sent before the phone accepted the config");
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");

        assertTrue(link.sendRc(sticks));
        waitFor(() -> !phone.rcFrames.isEmpty(), 2000, "RC frame");
        Thread.sleep(500);
        assertEquals(1, phone.rcFrames.size(), "no background resends: a stalled input loop means no RC (failsafe)");
    }

    @Test
    void pacerHoldsItsRateOnWindows() throws Exception {
        Pacer pacer = new Pacer(100);
        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) pacer.await();
        long ms = (System.nanoTime() - start) / 1_000_000;
        // a plain sleep(10) loop takes ~780 ms here (15.6 ms Windows timer ticks)
        assertTrue(ms >= 470 && ms <= 700, "50 ticks at 100 Hz took " + ms + " ms"); // wide enough for CI machines
    }

    /** The camera always gets a steady 30 fps, whatever the phone app or the settings file says. */
    @Test
    void cameraAlwaysRunsAtASteady30() throws Exception {
        for (String version : new String[]{"2.3.0-pc.12", "2.3.0-pc.11", null}) {
            phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
            phone.appVersion = version;
            PcConfig config = PcConfig.defaults();
            config.set("cameraFpsMin", "60"); // an old settings file: ignored
            config.set("cameraFpsMax", "60");
            link = new ControlLink(config, InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
            link.start();
            waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
            assertEquals(30, phone.config.cameraFpsMin, "phone app " + version);
            assertEquals(30, phone.config.cameraFpsMax);
            link.close();
            phone.close();
        }
        link = null;
        phone = null;
    }

    @Test
    void keyFrameRequestsReachThePhoneAtMostEvery1Point5Seconds() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        for (int i = 0; i < 40; i++) {
            link.requestKeyFrame();
            Thread.sleep(50);
        }
        waitFor(() -> phone.keyFrameRequests.get() >= 2, 2000, "requests at the phone");
        assertTrue(phone.keyFrameRequests.get() <= 2, "2 s of asking: " + phone.keyFrameRequests.get());
        assertEquals(phone.keyFrameRequests.get(), link.getKeyFrameRequests());
    }

    /** DEEP SLEEP: the mode reaches the phone; while the PC is dormant no Connect goes out; WAKE brings the link back. */
    @Test
    void powerModeReachesThePhoneAndADormantLinkSendsNoConnect() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        link.sendPowerMode(ControlLink.POWER_DEEP_SLEEP, 2);
        // the waits are long: the release runner is slow and a thread may get no turn for a second or more
        waitFor(() -> !phone.powerModes.isEmpty(), 8000, "the power mode at the phone");
        assertArrayEquals(new int[]{ControlLink.POWER_DEEP_SLEEP, 2}, phone.powerModes.get(0));
        // the phone sleeps (silent, its socket closed); this PC, told so, keeps quiet: a Connect in a listening
        // window would wake it
        link.setDormant(true);
        phone.sleep();
        waitFor(() -> !link.isConnected(), 4000, "link lost");
        int before = phone.connects.get();
        Thread.sleep(1500);
        assertEquals(before, phone.connects.get(), "no Connect while dormant");
        // the phone's check window: "still here" with its battery; the dormant PC says sleep on
        phone.sendSleepHello(67, true, 5);
        waitFor(() -> !phone.sleepAnswers.isEmpty(), 8000, "the PC's answer to the hello");
        assertEquals(0, phone.sleepAnswers.get(0), "dormant: sleep on");
        ControlLink.SleepHello hello = link.getSleepHello();
        assertNotNull(hello);
        assertEquals(67, hello.batteryPercent());
        assertTrue(hello.charging());
        assertEquals(5, hello.nextCheckMin());
        assertEquals(0x0FA0, hello.milliVolts());
        assertEquals(before, phone.connects.get(), "a hello wakes nothing while dormant");
        // WAKE: the next hello is answered with 1, the Connects resume, and the phone, listening again, takes one
        link.setDormant(false);
        phone.sendSleepHello(66, false, 5);
        waitFor(() -> phone.sleepAnswers.size() >= 2, 8000, "the second answer");
        assertEquals(1, phone.sleepAnswers.get(1), "after WAKE: wake");
        phone.silent = false;
        waitFor(() -> phone.connects.get() > before, 8000, "a Connect after WAKE");
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "the link back");
    }

    /** A weak link (the phone's bitrate setting low for 5 s): bad coverage mode, 640x360; the sticks keep going. */
    @Test
    void aWeakLinkSwitchesToBadCoverageMode() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.encoderBitRate = 250_000;
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        assertEquals(1280, phone.config.cameraWidth);
        waitFor(() -> phone.config.cameraWidth == 640, (int) ControlLink.LOW_RES_AFTER_MS + 3000, "640x360");
        assertTrue(link.isRobust());
        waitFor(() -> phone.videoProfile > 0 && (phone.videoProfile & 1) == 1, 2000, "key frames twice");
        assertEquals(ControlLink.ROBUST_MAX_STEP, link.maxBitrateStep(), "up to 1 Mbit/s");
        assertEquals(1_000_000, de.droiddrone.pc.video.BitrateControl.STEPS_BPS[ControlLink.ROBUST_MAX_STEP]);
        assertEquals(2 | 4, phone.videoProfile & 6, "a parity per group, H.264 (the setting is H.265)");
        assertEquals(ControlLink.State.READY, link.getState(), "still flying");
        int rcBefore = phone.rcFrames.size();
        assertTrue(link.sendRc(new short[]{1500, 1500, 1500, 1000}), "sticks go out during the switch");
        waitFor(() -> phone.rcFrames.size() > rcBefore, 2000, "sticks at the phone");
    }

    /** The phone ignores the switch (its config arrives, the picture stays): the PC sends the size again. */
    @Test
    void aSwitchThePhoneDidntMakeIsSentAgain() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.encoderBitRate = 250_000;
        phone.videoStream = TestStreams.h264(320, 240, 600, 10); // the stream stays 320 wide: the switch is never made
        ControlLinkTest.CapturingSink sink = new ControlLinkTest.CapturingSink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> 30)) {
            PcConfig config = PcConfig.defaults();
            config.set("cameraWidth", "320");
            config.set("cameraHeight", "240");
            link = new ControlLink(config, InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
            link.setVideoListener(video, false);
            video.attach(link);
            link.start();
            waitFor(() -> link.isRobust(), (int) ControlLink.LOW_RES_AFTER_MS + 4000, "switched down");
            int after = phone.configs.size();
            waitFor(() -> phone.configs.size() >= after + 2, (int) ControlLink.VERIFY_AFTER_MS + 3000, "sent again");
            assertEquals(240, phone.configs.get(phone.configs.size() - 1).cameraWidth, "the small size again");
        }
    }

    @Test
    void videoRobustAndSharpAreObeyedAtOnce() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        PcConfig config = PcConfig.defaults();
        config.set(ControlLink.VIDEO_MODE_KEY, "robust");
        link = new ControlLink(config, InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        waitFor(() -> phone.config.cameraWidth == 640 && phone.videoProfile > 0 && (phone.videoProfile & 1) == 1, 3000, "bad coverage mode from the start");
        assertEquals(640, phone.configs.get(0).cameraWidth, "the very first config already carries the small size (1.0.33)");
        assertEquals(4, PcConfig.defaults().getBitrateLimitMbps());
        link.setVideoMode(ControlLink.VideoMode.SHARP);
        waitFor(() -> phone.config.cameraWidth == 1920 && phone.videoProfile == 0, 3000, "the sharp picture (1.0.34)");
        assertEquals(ControlLink.Tier.SHARP, link.getTier());
        assertEquals(7, link.maxBitrateStep(), "the sharp 8 Mbit/s");
        assertEquals(8_000_000, de.droiddrone.pc.video.BitrateControl.STEPS_BPS[link.maxBitrateStep()]);
        assertFalse(link.isRobust());
        // the pilot (1.0.39): ROBUST did nothing while the sharp tier was on - every choice takes at once, from any tier
        link.setVideoMode(ControlLink.VideoMode.ROBUST);
        waitFor(() -> phone.config.cameraWidth == 640 && (phone.videoProfile & 1) == 1, 3000, "ROBUST straight from the sharp tier");
        assertEquals(ControlLink.Tier.ROBUST, link.getTier());
        link.setVideoMode(ControlLink.VideoMode.SURVIVAL);
        waitFor(() -> phone.config.cameraWidth == 480 && phone.config.cameraFpsMax == 15, 3000, "SURVIVAL: 480x270 at 15 fps");
        assertEquals(ControlLink.SURVIVAL_MAX_STEP, link.maxBitrateStep());
        link.setVideoMode(ControlLink.VideoMode.NORMAL);
        waitFor(() -> phone.config.cameraWidth == 1280 && phone.config.cameraFpsMax == 30 && phone.videoProfile == 0, 3000, "NORMAL: 1280x720");
        assertEquals(ControlLink.Tier.NORMAL, link.getTier());
        assertEquals(5, link.maxBitrateStep(), "the set 4 Mbit/s");
    }

    @Test
    void everyVideoChoiceHoldsItsTier() {
        assertNull(ControlLink.VideoMode.AUTO.forced());
        for (ControlLink.VideoMode m : ControlLink.VideoMode.values()) {
            if (m != ControlLink.VideoMode.AUTO) assertEquals(m.name(), m.forced().name(), "a button for every tier AUTO can pick");
        }
        assertEquals(ControlLink.Tier.values().length + 1, ControlLink.VideoMode.values().length);
        assertEquals(ControlLink.VideoMode.ROBUST, ControlLink.VideoMode.parse("robust"), "the saved settings still read");
        assertEquals(ControlLink.VideoMode.AUTO, ControlLink.VideoMode.parse(null));
    }

    /**
     * AUTO earns the sharp tier (1.0.34): calm at the normal top bitrate, no stall, for SHARP_AFTER_MS - then the
     * sharp size and limit go out. A stall ends it at once: back to the normal picture, from where bad coverage mode
     * is judged as before.
     */
    @Test
    void autoGoesSharpWhenTheLinkIsCalmAndBackAtAStall() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.encoderBitRate = 4_000_000; // the phone sits at the normal top
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.setSharpAfterMsForTest(1500);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        assertEquals(1280, phone.configs.get(0).cameraWidth, "a session starts on the normal picture");
        waitFor(() -> link.getTier() == ControlLink.Tier.SHARP && phone.config.cameraWidth == 1920, 5000, "sharp after the calm");
        assertEquals(7, link.maxBitrateStep(), "up to 8 Mbit/s");
        assertEquals(0, phone.videoProfile, "no bad coverage flags in the sharp tier");
        phone.silent = true; // a stall: no pong for STALL_EVENT_MS, well under a link loss
        Thread.sleep(ControlLink.STALL_EVENT_MS + 400);
        phone.silent = false;
        waitFor(() -> link.getTier() == ControlLink.Tier.NORMAL && phone.config.cameraWidth == 1280, 3000, "the normal picture after the stall");
        assertEquals(5, link.maxBitrateStep(), "the set 4 Mbit/s");
    }

    /**
     * With a spectator watching, AUTO stays on the normal picture and leaves the sharp one: the relay sends the picture
     * out again through this PC's upload, and at 8 Mbit/s the ping to the drone went from 50 to 300 - 700 ms (2026-11-05).
     */
    @Test
    void autoStaysOffTheSharpTierWhileSomeoneWatches() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.encoderBitRate = 4_000_000;
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.setSharpAfterMsForTest(1500);
        link.setWatchersForTest(1);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        Thread.sleep(3500); // well past the calm that earns the sharp tier
        assertEquals(ControlLink.Tier.NORMAL, link.getTier(), "no sharp tier while watched");
        link.setWatchersForTest(0);
        waitFor(() -> link.getTier() == ControlLink.Tier.SHARP && phone.config.cameraWidth == 1920, 5000, "sharp once nobody watches");
        link.setWatchersForTest(1);
        waitFor(() -> link.getTier() == ControlLink.Tier.NORMAL && phone.config.cameraWidth == 1280, 3000, "back to the normal picture when someone watches");
    }

    /** A phone that reports no bitrate setting (older than pc.13) is never judged calm at the top: no sharp tier. */
    @Test
    void aPhoneThatReportsNoBitrateNeverGoesSharp() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.setSharpAfterMsForTest(500);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        Thread.sleep(2500);
        assertEquals(ControlLink.Tier.NORMAL, link.getTier());
        assertTrue(phone.configs.stream().allMatch(c -> c.cameraWidth == 1280));
    }

    /**
     * The phone keeps its video profile across sessions (pc.26 never clears it): a phone left in bad coverage mode by
     * the session before would send key frames twice, parity and H.264 to a PC that takes it for normal. So the profile
     * goes out with the first config of every session, the normal one too (1.0.34).
     */
    @Test
    void theVideoProfileGoesOutWithTheFirstConfigOfEverySession() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.videoProfile = 1 | 2 | 4; // left in bad coverage mode by the session before
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        waitFor(() -> phone.videoProfile == 0, 2000, "the normal profile, with the first config");
        assertFalse(phone.configs.isEmpty());
    }

    /** Bad coverage mode not enough (the bitrate at the bottom for 5 s more): the survival tier, 480x270 at 15 fps. */
    @Test
    void aLinkThatCantCarryBadCoverageModeGetsTheSurvivalTier() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.encoderBitRate = 250_000;
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getTier() == ControlLink.Tier.ROBUST, (int) ControlLink.LOW_RES_AFTER_MS + 3000, "bad coverage mode");
        waitFor(() -> link.getTier() == ControlLink.Tier.SURVIVAL, (int) ControlLink.LOW_RES_AFTER_MS + 3000, "the survival tier");
        waitFor(() -> phone.config.cameraWidth == 480 && phone.config.cameraFpsMax == 15, 3000, "480x270 at 15 fps");
        assertEquals(ControlLink.SURVIVAL_MAX_STEP, link.maxBitrateStep());
        assertEquals(250_000, de.droiddrone.pc.video.BitrateControl.STEPS_BPS[link.maxBitrateStep()], "up to 250 kbit/s");
        assertTrue(link.isRobust());
        link.setVideoMode(ControlLink.VideoMode.SHARP);
        waitFor(() -> link.getTier() == ControlLink.Tier.SHARP && phone.config.cameraWidth == 1920 && phone.config.cameraFpsMax == 30, 3000, "SHARP: the sharp picture at 30 fps");
    }

    /**
     * The sticks keep going through a link loss (1.0.33): the phone's uplink is the weak direction on 4G, and a
     * drone whose sticks arrive is under control while its video and telemetry are gone. The flight controller's
     * state is kept too, so the armed-resume rule has it; the handshake comes at once when the phone is heard.
     */
    @Test
    void sticksAndFcStateSurviveALinkLoss() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        waitFor(() -> link.getTelemetry().hasBoxNames() && link.getFcInfo() != null, 5000, "FC state");
        short[] sticks = {1500, 1500, 1500, 1400, 2000};
        assertTrue(link.sendRc(sticks));
        phone.silent = true;
        waitFor(() -> !link.isConnected(), ControlLink.LINK_LOST_MS + 1500, "link lost");
        assertEquals(ControlLink.State.CONNECTING, link.getState());
        assertTrue(link.sticksWanted(), "the sticks are still wanted");
        assertTrue(link.sendRc(sticks), "and still go out, blind");
        assertNotNull(link.getFcInfo(), "the FC's state is kept through a short loss");
        assertTrue(link.getTelemetry().hasBoxNames());
        int connects = phone.connects.get();
        Thread.sleep(700);
        assertTrue(phone.connects.get() - connects >= 3, "Connect every " + ControlLink.CONNECT_FAST_MS + " ms after a loss: " + (phone.connects.get() - connects));
        int frames = phone.rcFrames.size();
        phone.silent = false;
        long back = System.currentTimeMillis();
        assertTrue(link.sendRc(sticks));
        waitFor(() -> phone.rcFrames.size() > frames, 2000, "the frame reaches the phone the moment it listens");
        waitFor(() -> link.getState() == ControlLink.State.READY, 3000, "READY again");
        assertTrue(System.currentTimeMillis() - back < 1500, "the handshake within a few round trips");
        assertNotNull(link.getFcInfo());
    }

    /** The link comes back and the video never stopped: the stream is not asked for again (no encoder restart). */
    @Test
    void aReconnectLeavesARunningStreamAlone() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.videoStream = TestStreams.h264(320, 240, 900, 10); // 30 s of frames
        CapturingSink sink = new CapturingSink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> 30)) {
            link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
            link.setVideoListener(video, false);
            video.attach(link);
            link.start();
            waitFor(() -> sink.presented.get() >= 10, 10_000, "pictures");
            assertEquals(1, phone.startVideoRequests.get());
            phone.silent = true;
            waitFor(() -> !link.isConnected(), ControlLink.LINK_LOST_MS + 1500, "link lost");
            int shown = sink.presented.get();
            phone.silent = false;
            waitFor(() -> sink.presented.get() > shown + 5, 3000, "pictures again, with the config in hand");
            waitFor(() -> link.getState() == ControlLink.State.READY, 3000, "READY again");
            Thread.sleep(ControlLink.VIDEO_CHECK_MS + 700);
            assertEquals(1, phone.startVideoRequests.get(), "the stream was never asked for again");
            assertTrue(link.isVideoConfigReceived());
        }
    }

    /** The link comes back but the stream really stopped (the phone app restarted): it is asked for again. */
    @Test
    void aStreamThatStoppedIsAskedForAgainAfterAReconnect() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.videoStream = TestStreams.h264(320, 240, 60, 10); // 2 s of frames, then nothing
        phone.appVersion = "2.3.0-pc.26";
        CapturingSink sink = new CapturingSink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> 30)) {
            link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
            link.setVideoListener(video, false);
            video.attach(link);
            link.start();
            waitFor(() -> sink.presented.get() >= 10, 10_000, "pictures");
            waitFor(() -> phone.startVideoRequests.get() >= 2, ControlLink.VIDEO_DEAD_MS + 6000,
                    "the stream ended while telemetry goes on: StartVideo again (the watchdog)");
        }
    }

    /** Phone app pc.26 reads a 16-bit stick sequence; pc.19-25 one byte; older none. */
    @Test
    void theStickSequenceIsSixteenBitsForAPhoneAppThatReadsIt() throws Exception {
        short[] frame = {1500, 1600, 1400, 1000, 2000, 1000, 1000, 1000};
        byte[] wide = ControlLink.rcFrame(frame, 0x1234, true);
        assertEquals(2 + 8 * 2 + 2, wide.length);
        assertEquals(0x12, wide[wide.length - 2] & 0xff);
        assertEquals(0x34, wide[wide.length - 1] & 0xff);
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.appVersion = "2.3.0-pc.26";
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getTelemetry().getPhonePcBuild() == 26, 5000, "version known");
        short[] sticks = {1500, 1500, 1500, 1000};
        for (int i = 0; i < 3; i++) assertTrue(link.sendRc(sticks));
        waitFor(() -> phone.rcSeqs.size() >= 3, 2000, "numbered frames");
        int first = phone.rcSeqs.get(0);
        assertEquals(List.of(first, first + 1, first + 2), phone.rcSeqs.subList(0, 3));
        assertEquals(3, phone.rcSeqWidth, "two bytes");
    }

    /** SET_BITRATE (pc.26): the step outright; an older phone app gets one relative step. */
    @Test
    void theBitrateStepGoesOutrightToAPhoneAppThatTakesIt() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
        phone.appVersion = "2.3.0-pc.25";
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY && link.getTelemetry().getPhonePcBuild() == 25, 5000, "READY, version known");
        link.sendBitrateStep(3, 5);
        waitFor(() -> phone.bitRateChanges.size() == 1, 2000, "one relative step");
        assertEquals(false, phone.bitRateChanges.get(0), "down");
        assertTrue(phone.bitRateSteps.isEmpty());
        phone.appVersion = "2.3.0-pc.26";
        waitFor(() -> link.getTelemetry().getPhonePcBuild() == 26, 5000, "newer version known");
        link.sendBitrateStep(3, 5);
        waitFor(() -> phone.bitRateSteps.size() == 1, 2000, "the step itself");
        assertEquals(3, phone.bitRateSteps.get(0));
        assertEquals(1, phone.bitRateChanges.size(), "no relative step for it");
    }

    @Test
    void anOlderPhoneAppNeverSwitchesResolution() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode); // reports no encoder setting
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.READY, 5000, "link READY");
        Thread.sleep(ControlLink.LOW_RES_AFTER_MS + 1000);
        assertFalse(link.isRobust());
        assertTrue(phone.configs.stream().allMatch(c -> c.cameraWidth == 1280));
    }

    @Test
    void wrongKeyNeverConnects() throws Exception {
        phone = new FakeFlightPhone("SECRET", SettingsCommon.versionCompatibleCode);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        Thread.sleep(1500);
        assertEquals(ControlLink.State.CONNECTING, link.getState());
        assertNull(phone.config);
    }

    @Test
    void versionMismatchIsReported() throws Exception {
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode + 1);
        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), PHONE_PORT, PC_PORT);
        link.start();
        waitFor(() -> link.getState() == ControlLink.State.VERSION_MISMATCH, 5000, "version mismatch");
    }

    static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    static class ParsedConfig {
        int cameraWidth, cameraFpsMin, cameraFpsMax, rcRefreshRate, fcProtocol, trailingBytes;
    }

    /** Minimal Flight app: accepts Connect, parses Config exactly like upstream Flight Config.processReceivedConfig, records RC. */
    static class FakeFlightPhone {
        final DatagramSocket socket;
        final UdpSender udpSender;
        volatile ReceiverBuffer receiverBuffer;
        final int versionCode;
        final String key;
        final boolean viaRelay;
        volatile boolean running = true;
        /** The app version it reports (DRAUPNE Flight's PcLinkStats record, every 0.1 s while connected); null = none. */
        volatile String appVersion;
        /** The encoder bitrate setting it reports (pc.13's record); -1 = none, like older apps. */
        volatile int encoderBitRate = -1;
        /** The flags of the PHONE_STATE record it reports (pc.19 on); -1 = none, like older apps. */
        volatile int phoneStateFlags = -1;
        /** Gone quiet: drops everything that comes in and sends nothing, like a phone whose 4G is gone. */
        volatile boolean silent;
        /** The sequence byte of every RC frame that carried one (pc.19 layout), in order. */
        final List<Integer> rcSeqs = new CopyOnWriteArrayList<>();
        /** Key frame requests received (pc.13 on). */
        final AtomicInteger keyFrameRequests = new AtomicInteger();
        /** The PC's live audio requests (AUDIO, pc.34 on), in order. */
        final List<de.droiddrone.common.PcAudio.Request> audioRequests = new CopyOnWriteArrayList<>();

        /** The stream's config as the Flight app sends it (numbered, acknowledged): 48 kHz mono AAC-LC. */
        void sendAudioConfig() throws java.io.IOException {
            UdpPacketData d = new UdpPacketData(UdpCommon.AudioInitialFrame);
            d.daos.writeInt(48_000);
            d.daos.writeByte(1);
            d.daos.writeByte(2); // Android's ENCODING_PCM_16BIT
            d.daos.write(new byte[]{0x11, (byte) 0x88});
            udpSender.sendPacket(d.getData());
        }

        /** One raw AAC frame (unnumbered, fire and forget). */
        void sendAudioFrame(byte[] aac) throws java.io.IOException {
            UdpPacketData d = new UdpPacketData(UdpCommon.AudioFrame);
            d.daos.write(aac);
            udpSender.sendPacket(d.getData());
        }
        /** PowerMode packets as {mode, checkIntervalMin}, and every Connect that reached the socket. */
        final List<int[]> powerModes = new CopyOnWriteArrayList<>();
        final AtomicInteger connects = new AtomicInteger();
        /** The PC's answers to this phone's sleep hellos: 0 sleep on, 1 wake. */
        final List<Integer> sleepAnswers = new CopyOnWriteArrayList<>();

        /**
         * Gone to sleep: silent, and its session dropped (the real phone closes its socket), so only a Connect
         * brings it back - the PC's pings, which go on once the link has been up (1.0.33), are not answered.
         */
        void sleep() {
            silent = true;
            ReceiverBuffer old = receiverBuffer;
            receiverBuffer = new ReceiverBuffer(udpSender, !viaRelay, key, null);
            old.close();
        }

        /** The sleeping phone's "still here" from a check window (pc.22), to the PC it last flew with. */
        void sendSleepHello(int batteryPercent, boolean charging, int nextCheckMin) {
            udpSender.sendPacket(new byte[]{ControlLink.SLEEP_HELLO, (byte) batteryPercent, (byte) (charging ? 1 : 0), (byte) nextCheckMin, 0x0F, (byte) 0xA0});
        }
        /** The last video profile received (pc.15 on): -1 = none yet, else its flags (bit 0: key frames twice). */
        volatile int videoProfile = -1;
        /** ChangeBitRate packets (true = up) and SET_BITRATE steps (pc.26), in order. */
        final List<Boolean> bitRateChanges = new CopyOnWriteArrayList<>();
        final List<Integer> bitRateSteps = new CopyOnWriteArrayList<>();
        /** How many bytes the last stick frame's sequence took: 0, 1 (pc.19) or 2 (pc.26) - stored as width + 1. */
        volatile int rcSeqWidth;
        /** Every config received, in order (a resolution switch sends a new one). */
        final List<ParsedConfig> configs = new CopyOnWriteArrayList<>();
        /** Spectator mode answers sent (DRAUPNE Flight pc.11 and on); 0 with answerSpectators off, like older apps. */
        final AtomicInteger spectatorAnswers = new AtomicInteger();
        volatile boolean answerSpectators = true;
        /**
         * A phone still holding a session that is gone: every Connect is answered with PILOT_AT naming this address
         * instead of being accepted (seen on 2026-11-02 after a link loss, naming the PC's own previous session).
         */
        volatile Spectate.Address staleSession;
        volatile ParsedConfig config;
        final List<short[]> rcFrames = new CopyOnWriteArrayList<>();

        FakeFlightPhone(String key, int versionCode) throws Exception {
            this(key, versionCode, -1);
        }

        /** @param relayPort >= 0: "Connect over server" mode, like upstream Flight with ConnectionMode.overServer */
        FakeFlightPhone(String key, int versionCode, int relayPort) throws Exception {
            this.versionCode = versionCode;
            this.key = key;
            viaRelay = relayPort >= 0;
            socket = new DatagramSocket(viaRelay ? RELAY_PHONE_PORT : PHONE_PORT, InetAddress.getLoopbackAddress());
            udpSender = new UdpSender(socket);
            receiverBuffer = new ReceiverBuffer(udpSender, !viaRelay, key, null);
            if (viaRelay) {
                udpSender.connect(InetAddress.getLoopbackAddress(), relayPort);
                Thread connector = new Thread(() -> {
                    while (running) {
                        try {
                            if (!receiverBuffer.isConnected()) udpSender.sendConnect(0, key, versionCode);
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                });
                connector.setDaemon(true);
                connector.start();
            }
            Thread stats = new Thread(() -> {
                while (running) {
                    try {
                        String v = appVersion;
                        int bps = encoderBitRate;
                        int flags = phoneStateFlags;
                        if ((v != null || bps > 0 || flags >= 0) && receiverBuffer.isConnected() && !silent) {
                            UdpPacketData d = new UdpPacketData(UdpCommon.TelemetryData);
                            if (v != null) {
                                byte[] name = v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                d.daos.writeShort(0x40F1); // PcLinkStats.APP_VERSION_CODE
                                d.daos.writeShort(name.length);
                                d.daos.write(name);
                            }
                            if (bps > 0) {
                                d.daos.writeShort(0x40F2); // PcLinkStats.ENCODER_CODE
                                d.daos.writeInt(bps);
                                d.daos.writeByte(0);
                            }
                            if (flags >= 0) {
                                d.daos.writeShort(0x40F4); // PcLinkStats.PHONE_STATE_CODE
                                d.daos.writeByte(flags);
                                d.daos.writeByte(rcSeqs.isEmpty() ? 255 : rcSeqs.get(rcSeqs.size() - 1));
                                d.daos.writeShort(rcFrames.isEmpty() ? 65535 : 4);
                                d.daos.writeShort(rcFrames.isEmpty() ? 65535 : 3);
                            }
                            udpSender.sendPacket(d.getData());
                        }
                        Thread.sleep(100);
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            stats.setDaemon(true);
            stats.start();
            Thread receiver = new Thread(() -> {
                byte[] buf = new byte[UdpCommon.packetLength];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                while (running) {
                    try {
                        socket.receive(packet);
                        if (packet.getLength() > 0 && packet.getData()[0] == UdpCommon.Connect) connects.incrementAndGet();
                        if (packet.getLength() > 1 && packet.getData()[0] == ControlLink.SLEEP_ANSWER) {
                            sleepAnswers.add((int) packet.getData()[1]);
                            continue;
                        }
                        if (silent) continue;
                        // like DRAUPNE Flight's Udp.receiverRun in direct mode: once a PC is connected, nothing from
                        // another one gets in; a second PC asking is told where the first one is
                        if (!viaRelay && receiverBuffer.isConnected()) {
                            if (!packet.getAddress().equals(udpSender.getIp()) || packet.getPort() != udpSender.getPort()) {
                                answerSpectator(packet, true);
                                continue;
                            }
                        } else if (!viaRelay && packet.getLength() > 0 && packet.getData()[0] == Spectate.PILOT_QUERY) {
                            answerSpectator(packet, false);
                            continue;
                        } else if (!viaRelay && staleSession != null && packet.getLength() > 0 && packet.getData()[0] == UdpCommon.Connect) {
                            answerSpectator(packet, true);
                            continue;
                        }
                        receiverBuffer.addPacket(packet);
                    } catch (Exception ignored) {
                    }
                }
            });
            receiver.setDaemon(true);
            receiver.start();
            Thread buffer = new Thread(() -> {
                while (running) {
                    try {
                        SavedPacket p;
                        while ((p = receiverBuffer.getNextPacket()) != null) process(p);
                        if (!silent) receiverBuffer.processTimer(); // its own pings would still go out
                        Thread.sleep(1);
                    } catch (Exception ignored) {
                    }
                }
            });
            buffer.setDaemon(true);
            buffer.start();
        }

        /** Same checks as DRAUPNE Flight's Udp.answerSpectator. */
        private void answerSpectator(DatagramPacket packet, boolean flying) throws java.io.IOException {
            if (!answerSpectators) return;
            byte[] d = packet.getData();
            int keyAt = d[0] == Spectate.PILOT_QUERY ? 1 : flying && d[0] == UdpCommon.Connect ? 4 : -1;
            if (keyAt < 0 || packet.getLength() < keyAt + 2) return;
            int n = ((d[keyAt] & 0xff) << 8) | (d[keyAt + 1] & 0xff);
            if (!key.equals(new String(d, keyAt + 2, n, java.nio.charset.StandardCharsets.UTF_8))) return;
            Spectate.Address stale = staleSession;
            byte[] answer = !flying ? new byte[]{Spectate.NO_PILOT}
                    : stale != null ? Spectate.pilotAt(stale.ip(), stale.port()) : Spectate.pilotAt(udpSender.getIp(), udpSender.getPort());
            spectatorAnswers.incrementAndGet();
            socket.send(new DatagramPacket(answer, answer.length, packet.getAddress(), packet.getPort()));
        }

        void process(SavedPacket packet) throws Exception {
            DataReader r = new DataReader(packet.data, true);
            byte name = r.readByte();
            if (UdpCommon.isPacketNumbered(name)) r.readShort();
            switch (name) {
                case UdpCommon.Config -> {
                    if (r.readShort() != versionCode) {
                        udpSender.sendPacket(new UdpPacketData(UdpCommon.VersionMismatch).getData());
                        return;
                    }
                    ParsedConfig c = new ParsedConfig();
                    for (int i = 0; i < SettingsCommon.maxCamerasCount; i++) {
                        if (i > 0) r.readBoolean();
                        r.readUTF();
                        int w = r.readShort();
                        r.readShort();
                        int fpsMin = r.readShort(), fpsMax = r.readShort();
                        if (i == 0) {
                            c.cameraWidth = w;
                            c.cameraFpsMin = fpsMin;
                            c.cameraFpsMax = fpsMax;
                        }
                        r.readBoolean(); r.readByte(); r.readBoolean();
                    }
                    r.readUnsignedByteAsInt(); r.readBoolean(); r.readByte(); r.readUnsignedByteAsInt();
                    r.readBoolean(); r.readShort(); r.readBoolean(); r.readShort();
                    r.readUnsignedByteAsInt();
                    c.rcRefreshRate = r.readUnsignedByteAsInt();
                    r.readInt(); r.readByte(); r.readBoolean(); r.readUTF();
                    c.fcProtocol = r.readByte();
                    r.readUnsignedByteAsInt(); r.readUnsignedByteAsInt(); r.readUnsignedByteAsInt();
                    r.readUTF(); r.readUnsignedShortAsInt();
                    c.trailingBytes = r.getRemaining();
                    config = c;
                    configs.add(c);
                    udpSender.sendPacket(new UdpPacketData(UdpCommon.ConfigReceived).getData());
                }
                case UdpCommon.FcInfo -> {
                    UdpPacketData d = new UdpPacketData(UdpCommon.FcInfo);
                    d.daos.write(new byte[]{2, 4, 5, 1, 0, 1, 46, 0}); // Betaflight 4.5.1, MSP API 1.46
                    udpSender.sendPacket(d.getData());
                }
                case UdpCommon.Ping -> { // like Flight Udp.processData: the PC's ping comes back as a pong at once
                    boolean toEndPoint = r.readBoolean();
                    long time = r.readLong();
                    udpSender.sendPong(toEndPoint, time, (byte) -1);
                }
                case ControlLink.KEY_FRAME_REQUEST -> keyFrameRequests.incrementAndGet();
                case de.droiddrone.common.PcAudio.PACKET -> audioRequests.add(de.droiddrone.common.PcAudio.decode(packet.data, packet.data.length));
                case ControlLink.POWER_MODE -> powerModes.add(new int[]{r.readByte(), r.readByte()});
                case ControlLink.VIDEO_PROFILE -> videoProfile = r.readByte();
                case ControlLink.SET_BITRATE -> bitRateSteps.add(r.readUnsignedByteAsInt());
                case UdpCommon.ChangeBitRate -> bitRateChanges.add(r.readBoolean());
                case UdpCommon.GetVideoConfig -> videoConfigRequests.incrementAndGet();
                case UdpCommon.RcFrame -> {
                    int count = r.readByte();
                    short[] ch = new short[count];
                    for (int i = 0; i < count; i++) ch[i] = r.readShort();
                    // pc.19 on: a trailing sequence byte is read; pc.26 two; an older app refuses any length but count * 2
                    rcSeqWidth = r.getRemaining() + 1;
                    if (r.getRemaining() == 1) rcSeqs.add(r.readUnsignedByteAsInt());
                    else if (r.getRemaining() == 2) rcSeqs.add(r.readUnsignedShortAsInt());
                    else if (r.getRemaining() != 0) return;
                    rcFrames.add(ch);
                }
                case UdpCommon.BoxNames -> {
                    // like Flight: Betaflight's answers go out as telemetry records
                    UdpPacketData names = new UdpPacketData(UdpCommon.TelemetryData);
                    names.daos.write(FcTelemetryTest.boxNamesPacket("ARM;ANGLE;HORIZON;"));
                    udpSender.sendPacket(names.getData());
                    UdpPacketData status = new UdpPacketData(UdpCommon.TelemetryData);
                    status.daos.write(FcTelemetryTest.betaflightPacket(1 << 8 | 1 << 16, 0b10));
                    udpSender.sendPacket(status.getData());
                }
                case UdpCommon.StartVideo -> {
                    startVideoRequests.incrementAndGet();
                    requestedHevc = r.readBoolean();
                    TestStreams.Stream stream = videoStream;
                    if (stream != null && !videoStarted) {
                        videoStarted = true;
                        Thread sender = new Thread(() -> sendVideo(stream), "fakeVideo");
                        sender.setDaemon(true);
                        sender.start();
                    }
                }
                default -> {
                }
            }
        }

        // ---------------------------------------------------------------- video, like upstream Flight Udp

        volatile TestStreams.Stream videoStream;
        volatile int dropFrame = -1; // "lose" the second fragment of this frame on the network
        volatile boolean requestedHevc = true;
        volatile boolean videoStarted;
        final java.util.concurrent.atomic.AtomicInteger startVideoRequests = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger videoConfigRequests = new java.util.concurrent.atomic.AtomicInteger();

        private void sendVideo(TestStreams.Stream stream) {
            try {
                // sendVideoInitialFrame: numbered, acknowledged
                UdpPacketData init = new UdpPacketData(UdpCommon.VideoInitialFrame);
                init.daos.writeShort(320);
                init.daos.writeShort(240);
                init.daos.writeBoolean(false);
                init.daos.writeBoolean(false);
                init.daos.write(stream.codecConfig());
                udpSender.sendPacket(init.getData());
                short frameNum = 0;
                for (TestStreams.Packet p : stream.packets()) {
                    if (!running) return;
                    frameNum++;
                    byte[] buf = p.data();
                    int offset = 0;
                    int fragmentInFrame = 0;
                    while (offset < buf.length) { // sendKeyFrame / sendVideoFrame, header-size quirk included
                        UdpPacketData d = new UdpPacketData(p.keyFrame() ? UdpCommon.KeyFrame : UdpCommon.VideoFrame);
                        d.daos.writeShort(frameNum);
                        d.daos.writeInt(offset);
                        int headerSize = 9;
                        if (offset == 0) {
                            d.daos.writeInt(buf.length);
                            headerSize += 4;
                        }
                        int dataSize = UdpCommon.packetLength - headerSize;
                        int n = Math.min(dataSize, buf.length - offset);
                        d.daos.write(buf, offset, n);
                        offset += n;
                        boolean lose = frameNum == dropFrame && fragmentInFrame++ == 1;
                        if (!lose && !silent) udpSender.sendPacket(d.getData()); // silent: the frames go lost too
                    }
                    Thread.sleep(33);
                }
            } catch (Exception ignored) {
            }
        }

        void close() {
            running = false;
            udpSender.close();
            receiverBuffer.close();
            socket.close();
        }
    }
}
