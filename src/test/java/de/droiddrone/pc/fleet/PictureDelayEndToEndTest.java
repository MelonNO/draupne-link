package de.droiddrone.pc.fleet;

import de.droiddrone.common.PacketIds;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.LinkLog;
import de.droiddrone.pc.OlderPcLink;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.flights.FlightData;
import de.droiddrone.pc.flights.FlightLibrary;
import de.droiddrone.pc.video.VideoPipeline;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.67, the picture's delay shown in flight (G2G), end to end on loopback: a virtual drone sends FRAME_TIME when the
 * PC's profile asks (bit 3), the PC's pipeline makes a figure of it for every picture shown, and the link log gets its
 * columns. With an older phone app (the profile's bits 0-2 only) there is no figure and the video is as before; a PC
 * that doesn't ask is sent no FRAME_TIME.
 */
class PictureDelayEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link;
    private Wire wire;

    @AfterEach
    void tearDown() {
        if (link != null) link.close();
        if (drone != null) drone.stop();
        if (wire != null) wire.close();
    }

    private PcConfig config() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("cameraWidth", "640");
        config.set("cameraHeight", "360");
        config.set(ControlLink.VIDEO_MODE_KEY, "NORMAL"); // frame times are asked for in every tier: the plain one here
        return config;
    }

    private void startDrone(PcConfig config) throws Exception {
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.start(0);
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
    }

    @Test
    void aPictureDelayFigureAppearsAndIsSane(@TempDir Path dir) throws Exception {
        PcConfig config = config();
        startDrone(config);
        VirtualDroneEndToEndTest.Sink sink = new VirtualDroneEndToEndTest.Sink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> link == null ? -1 : link.getPing())) {
            link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
            link.setVideoListener(video, true);
            link.start();
            video.attach(link);
            waitFor(() -> video.stats().g2gMs() >= 0, 20_000, "a picture delay figure", log);
            Thread.sleep(1500); // a whole second's highest figure too
            VideoPipeline.Stats s = video.stats();
            assertNull(s.error(), "no decoder error");
            assertTrue(wire.toPc(PacketIds.FRAME_TIME) > 10, "FRAME_TIME came with the frames: " + wire.toPc(PacketIds.FRAME_TIME));
            // the virtual phone draws a picture a frame ahead and encodes it: tens of ms from the capture to the send
            assertTrue(s.captureToSendMs() >= 1 && s.captureToSendMs() < 1000, "capture to send: " + s.captureToSendMs());
            // a virtual drone's network is 30 ms each way (VirtualDrone.NET_BASE_ONE_WAY_MS); a loaded PC adds to it
            assertTrue(s.videoNetMs() >= 15 && s.videoNetMs() < 500, "the network's part: " + s.videoNetMs());
            assertTrue(s.g2gMs() >= s.videoNetMs() && s.g2gMs() < 2000, "camera to screen: " + s.g2gMs());
            assertTrue(s.g2gMaxMs() >= 15 && s.g2gMaxMs() < 5000, "the second's highest: " + s.g2gMaxMs());
            System.out.printf("picture delay on loopback: %d ms (capture to send %d, network %d, highest of the second %d)%n",
                    s.g2gMs(), s.captureToSendMs(), s.videoNetMs(), s.g2gMaxMs());

            // the link log's columns carry it, before the event; the flights' reader takes the new file as an old one
            Path file;
            try (LinkLog linkLog = LinkLog.open(dir)) {
                file = linkLog.file();
                linkLog.sample(link, false, false, s.shownFps(), null, "direct", video.stats(), -1);
                linkLog.event("a line of the app's log");
            }
            List<String> lines = Files.readAllLines(file);
            List<String> columns = List.of(lines.get(0).split(","));
            String[] row = lines.get(1).split(",", -1);
            assertEquals(columns.size(), row.length);
            assertEquals(List.of("capture_to_send_ms", "video_net_ms", "g2g_ms", "g2g_max_ms", "event"), columns.subList(columns.size() - 5, columns.size()));
            for (String c : List.of("capture_to_send_ms", "video_net_ms", "g2g_ms", "g2g_max_ms")) {
                assertTrue(Integer.parseInt(row[columns.indexOf(c)]) >= 0, c + ": " + row[columns.indexOf(c)]);
            }
            FlightLibrary.Flight flight = new FlightLibrary.Flight(0, 0, "MobileZulu", null, null, null, null, file, Double.NaN);
            FlightData data = FlightData.load(flight);
            assertEquals(1, data.link.size(), "the row is read (the event's row is none)");
            assertFalse(data.link.get(0).down());
            assertTrue(data.link.get(0).pingMs() >= 0, "its ping: " + data.link.get(0).pingMs());
        }
    }

    /** A phone app before pc.53 reads the profile's bits 0-2 and no more: no FRAME_TIME, no figure, the video as it was. */
    @Test
    void anOlderPhoneShowsNoFigure(@TempDir Path dir) throws Exception {
        PcConfig config = config();
        startDrone(config);
        drone.setOlderApp(true);
        VirtualDroneEndToEndTest.Sink sink = new VirtualDroneEndToEndTest.Sink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> link == null ? -1 : link.getPing())) {
            link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
            link.setVideoListener(video, true);
            link.start();
            video.attach(link);
            waitFor(() -> sink.presented.get() >= 60, 30_000, "two seconds of pictures on the PC", log);
            VideoPipeline.Stats s = video.stats();
            assertNull(s.error(), "no decoder error");
            assertEquals(0, wire.toPc(PacketIds.FRAME_TIME), "an older phone sends none");
            assertEquals(-1, s.g2gMs(), "no figure");
            assertEquals(-1, s.captureToSendMs());
            assertEquals(-1, s.videoNetMs());
            assertEquals(-1, s.g2gMaxMs());
            assertEquals(640, sink.width, "the picture as asked for");
            assertTrue(wire.toPc(UdpCommon.VideoFrame) > 30, "plain frames as ever");
            // the profile it was sent: bit 3 on top of what 1.0.66 sent in this tier (nothing), again while no times come
            assertTrue(wire.toPhone(PacketIds.VIDEO_PROFILE) >= 1);
            // its link log: the four columns blank
            Path file;
            try (LinkLog linkLog = LinkLog.open(dir)) {
                file = linkLog.file();
                linkLog.sample(link, false, false, s.shownFps(), null, "direct", video.stats(), -1);
            }
            List<String> lines = Files.readAllLines(file);
            List<String> columns = List.of(lines.get(0).split(","));
            String[] row = lines.get(1).split(",", -1);
            for (String c : List.of("capture_to_send_ms", "video_net_ms", "g2g_ms", "g2g_max_ms")) assertEquals("", row[columns.indexOf(c)], c);
        }
    }

    /** A PC before 1.0.67 never asks (its profile is bits 0-2): the phone app of now sends it no packet it doesn't know. */
    @Test
    void aPcThatDoesNotAskIsSentNoFrameTimes() throws Exception {
        PcConfig config = config();
        startDrone(config);
        VirtualDroneEndToEndTest.Sink sink = new VirtualDroneEndToEndTest.Sink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> link == null ? -1 : link.getPing())) {
            link = new OlderPcLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
            link.setVideoListener(video, true);
            link.start();
            video.attach(link);
            waitFor(() -> sink.presented.get() >= 60, 30_000, "two seconds of pictures on the PC", log);
            assertEquals(0, wire.toPc(PacketIds.FRAME_TIME), "not asked for, not sent");
            assertEquals(0, wire.toPc(PacketIds.VIDEO_PARITY_RS));
            assertEquals(-1, video.stats().g2gMs());
        }
    }

    /**
     * The phone forgets the wish with every new session (the profile's bits 0-2 it keeps): after a PC that asked, a PC
     * that doesn't ask gets none - not even before its own profile has come.
     */
    @Test
    void theWishEndsWithTheSession() throws Exception {
        PcConfig config = config();
        startDrone(config);
        VirtualDroneEndToEndTest.Sink sink = new VirtualDroneEndToEndTest.Sink();
        try (VideoPipeline video = new VideoPipeline(sink, () -> link == null ? -1 : link.getPing())) {
            link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
            link.setVideoListener(video, true);
            link.start();
            video.attach(link);
            waitFor(() -> wire.toPc(PacketIds.FRAME_TIME) > 5, 20_000, "frame times for the PC that asked", log);
            // the same PC (address and port) connects again, and from now on every profile packet is lost on the way:
            // to the phone it is a PC that never asks. Nothing comes back for a while, so the PC counts the link lost
            long sessions = drone.sessions(), connects = wire.toPhone(UdpCommon.Connect);
            wire.rule((toPhone, d, len) -> !toPhone || len > 0 && d[0] == PacketIds.VIDEO_PROFILE ? Wire.Rule.LOST : 0);
            waitFor(() -> wire.toPhone(UdpCommon.Connect) > connects, 8000, "the PC counts the link lost and connects again", log);
            wire.rule((toPhone, d, len) -> toPhone && len > 0 && d[0] == PacketIds.VIDEO_PROFILE ? Wire.Rule.LOST : 0);
            waitFor(() -> drone.sessions() > sessions && link.getState() == ControlLink.State.READY, 10_000, "a new session", log);
            long before = wire.toPc(PacketIds.FRAME_TIME);
            long frames = wire.toPc(UdpCommon.VideoFrame);
            waitFor(() -> wire.toPc(UdpCommon.VideoFrame) > frames + 30, 10_000, "the video goes on", log);
            assertEquals(before, wire.toPc(PacketIds.FRAME_TIME), "no profile in this session: no frame times");
        }
    }
}
