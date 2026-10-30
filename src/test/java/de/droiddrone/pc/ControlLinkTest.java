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

    @Test
    void hostRelayModeCarriesConfigAndRcThroughUpstreamRelay() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("linkMode", "host");
        config.set("port", Integer.toString(RELAY_PORT));
        config.set("key", "DD");
        connection = Connection.open(config);
        ControlLink relayed = connection.getLink();
        waitFor(() -> relayed.getState() == ControlLink.State.CONFIGURING, 5000, "relay answering Connect");

        // the phone shows up later, as it would over 4G
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode, RELAY_PORT);
        waitFor(() -> relayed.getState() == ControlLink.State.READY, 8000, "phone accepting config via relay");
        assertEquals(0, phone.config.trailingBytes);

        short[] sticks = {1500, 1500, 1500, 1000, 2000};
        assertTrue(relayed.sendRc(sticks));
        waitFor(() -> !phone.rcFrames.isEmpty(), 3000, "RC frame via relay");
        assertArrayEquals(ControlLink.padForRxMap(sticks), phone.rcFrames.get(phone.rcFrames.size() - 1));
    }

    /** Records what the video pipeline would put on screen. */
    private static final class CapturingSink implements VideoPipeline.FrameSink {
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

    @Test
    void linkLogRecordsTheTimeline(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        for (int i = 0; i < 25; i++) java.nio.file.Files.writeString(dir.resolve(String.format("link-20260101-0000%02d.csv", i)), "old");
        phone = new FakeFlightPhone("DD", SettingsCommon.versionCompatibleCode);
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
            log.sample(link, true, true, 0, sticks, "direct");
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

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    static class ParsedConfig {
        int cameraWidth, rcRefreshRate, fcProtocol, trailingBytes;
    }

    /** Minimal Flight app: accepts Connect, parses Config exactly like upstream Flight Config.processReceivedConfig, records RC. */
    static class FakeFlightPhone {
        final DatagramSocket socket;
        final UdpSender udpSender;
        final ReceiverBuffer receiverBuffer;
        final int versionCode;
        volatile boolean running = true;
        volatile ParsedConfig config;
        final List<short[]> rcFrames = new CopyOnWriteArrayList<>();

        FakeFlightPhone(String key, int versionCode) throws Exception {
            this(key, versionCode, -1);
        }

        /** @param relayPort >= 0: "Connect over server" mode, like upstream Flight with ConnectionMode.overServer */
        FakeFlightPhone(String key, int versionCode, int relayPort) throws Exception {
            this.versionCode = versionCode;
            boolean viaRelay = relayPort >= 0;
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
            Thread receiver = new Thread(() -> {
                byte[] buf = new byte[UdpCommon.packetLength];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                while (running) {
                    try {
                        socket.receive(packet);
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
                        receiverBuffer.processTimer();
                        Thread.sleep(1);
                    } catch (Exception ignored) {
                    }
                }
            });
            buffer.setDaemon(true);
            buffer.start();
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
                        if (i == 0) c.cameraWidth = w;
                        r.readShort(); r.readShort(); r.readShort();
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
                    udpSender.sendPacket(new UdpPacketData(UdpCommon.ConfigReceived).getData());
                }
                case UdpCommon.FcInfo -> {
                    UdpPacketData d = new UdpPacketData(UdpCommon.FcInfo);
                    d.daos.write(new byte[]{2, 4, 5, 1, 0, 1, 46, 0}); // Betaflight 4.5.1, MSP API 1.46
                    udpSender.sendPacket(d.getData());
                }
                case UdpCommon.RcFrame -> {
                    int count = r.readByte();
                    short[] ch = new short[count];
                    for (int i = 0; i < count; i++) ch[i] = r.readShort();
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
                        if (!lose) udpSender.sendPacket(d.getData());
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
