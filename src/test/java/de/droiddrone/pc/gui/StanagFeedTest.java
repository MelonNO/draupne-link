package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.video.StreamRecorder;
import de.droiddrone.pc.video.TestStreams;
import de.droiddrone.pc.video.TsReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.65: one drone's STANAG 4609 stream, written into a .ts file and read back with FFmpeg - the page, the video with
 * the OSD, an alert's pictures and a clip in ONE transport stream that never begins again, whatever it shows.
 */
class StanagFeedTest {
    private static final int W = StanagFeed.W, H = StanagFeed.H;
    private static final StanagShow.Page SLEEP = new StanagShow.Page("DEEP SLEEP", "Asleep. Checks in every 5 min.", StanagShow.Tone.PLAIN);

    @BeforeAll
    static void look() {
        Theme.install();
    }

    private static StanagFeed feed(Path file, AtomicLong ended, List<String> log) {
        StanagFeed f = new StanagFeed("MobileAlpha", false, file.toString(), 2_000_000, () -> false, ended::set, log::add);
        f.setPage(SLEEP);
        f.setIds("OP TEST", "NOR");
        f.setPlace(new StanagKlv.Place(59.95, 10.7, Double.NaN, 45, null));
        return f;
    }

    private static void await(BooleanSupplier what, long ms, String why) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!what.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("waited " + ms + " ms for: " + why);
            Thread.sleep(10);
        }
    }

    /** The test stream's frames to the feed at its own pace (30 a second), as the video's receive thread hands them over. */
    private static void play(StanagFeed f, TestStreams.Stream s, int w, int h) throws InterruptedException {
        f.onConfig(w, h, false, s.codecConfig());
        for (TestStreams.Packet p : s.packets()) {
            f.onFrame(p.data(), p.keyFrame(), MonoClock.ms());
            Thread.sleep(33);
        }
    }

    private static StanagFeed.Osd osd(boolean figures) {
        FcTelemetry telemetry = new FcTelemetry();
        return new StanagFeed.Osd() {
            public OsdRenderer.Data data() { return figures ? ObservationOsd.data(telemetry, null, MonoClock.ms(), false, MonoClock.ms()) : null; }
            public OsdLayout layout() { return OsdLayout.defaults(); }
            public boolean flipX() { return false; }
            public boolean flipY() { return false; }
            public double displayMs() { return 0; }
        };
    }

    private static boolean red(int rgb) {
        return ((rgb >> 16) & 0xff) > 150 && ((rgb >> 8) & 0xff) < 90 && (rgb & 0xff) < 90;
    }

    private static boolean dark(int rgb) {
        return ((rgb >> 16) & 0xff) < 40 && ((rgb >> 8) & 0xff) < 40 && (rgb & 0xff) < 40;
    }

    /** A second of the test video as a clip file (an MKV, as the preview's; the phone's MP4 plays the same way). */
    private static Path clip(Path dir, String name, int frames) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, frames, 15);
        Path file = dir.resolve(name);
        try (StreamRecorder r = new StreamRecorder(file, false, 320, 240, s.codecConfig())) {
            long ms = 1000;
            for (TestStreams.Packet p : s.packets()) {
                r.write(p.data(), p.keyFrame(), ms);
                ms += 33;
            }
        }
        return file;
    }

    @Test
    void thePageIsAStreamOfItsOwnWithTheDronesNameAndPlaceInTheMetadata(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("page.ts");
        List<String> log = new ArrayList<>();
        StanagFeed f = feed(file, new AtomicLong(), log);
        await(() -> f.sent(StanagShow.Kind.PAGE) >= 12, 6000, "pages");
        long took = 0;
        long n0 = f.sent(StanagShow.Kind.PAGE), t0 = System.currentTimeMillis();
        Thread.sleep(1000);
        long perSecond = f.sent(StanagShow.Kind.PAGE) - n0;
        f.close();
        assertTrue(perSecond >= 4 && perSecond <= 6, "about five pictures a second: " + perSecond);
        assertEquals(StanagShow.Kind.PAGE, f.showing());

        TsReader ts = TsReader.read(file, 5, W, H);
        assertEquals("mpegts", ts.format);
        assertTrue(ts.h264 && ts.klvStream, "H.264 and a KLV stream beside it");
        assertEquals(W, ts.width);
        assertEquals(H, ts.height);
        assertTrue(ts.pictures.size() >= 12);
        assertEquals(ts.pictures.size(), ts.decoded(), "every picture decodes");
        assertTrue(ts.pictures.get(0).key(), "it begins with a key frame");
        assertTrue(ts.timesRise());
        // the page: black, with the mode's name in the middle band
        TsReader.Picture p = ts.kept().get(ts.kept().size() - 1);
        assertTrue(dark(p.at(4, H - 4, W)) && dark(p.at(W - 4, H - 4, W)), "black");
        boolean ink = false;
        for (int x = 200; x < W - 200; x += 3) ink |= !dark(p.at(x, 440, W));
        assertTrue(ink, "the mode's name");
        // metadata: about once a second on a page, under the drone's name
        assertFalse(ts.meta.isEmpty());
        assertTrue(ts.meta.size() <= ts.pictures.size() / 3 + 2, "not with every picture of a still page: " + ts.meta.size() + " of " + ts.pictures.size());
        TsReader.Meta m = ts.meta.get(ts.meta.size() - 1);
        assertNotNull(m.items(), "the packet is whole");
        assertEquals("MobileAlpha", m.designation());
        assertEquals("OP TEST", m.mission());
        assertEquals(59.95, m.sensorLat(), 1e-6);
        assertEquals(10.7, m.sensorLon(), 1e-6);
        assertEquals(45, m.heading(), 0.01);
        assertTrue(log.isEmpty(), "a stream that simply goes says nothing (its owner says once that they are on): " + log);
    }

    @Test
    void theVideoWithItsOsdThenThePageAgainInOneStream(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("video.ts");
        StanagFeed f = feed(file, new AtomicLong(), new ArrayList<>());
        await(() -> f.sent(StanagShow.Kind.PAGE) >= 3, 6000, "the page first");
        f.setLive(new StanagFeed.Live(osd(true), false, null));
        play(f, TestStreams.h264(320, 240, 45, 15), 320, 240);
        assertEquals(StanagShow.Kind.VIDEO, f.showing());
        long video = f.sent(StanagShow.Kind.VIDEO);
        assertTrue(video >= 25, "the piloted drone's video, every picture: " + video + " of 45");
        f.setLive(null); // DISCONNECT
        long pages = f.sent(StanagShow.Kind.PAGE);
        await(() -> f.sent(StanagShow.Kind.PAGE) >= pages + 3, 3000, "the page at once after the video");
        assertEquals(StanagShow.Kind.PAGE, f.showing());
        f.close();

        TsReader ts = TsReader.read(file, 1, W, H);
        assertEquals(ts.pictures.size(), ts.decoded(), "every picture decodes, over both changes");
        assertTrue(ts.timesRise(), "one stream: its times never begin again");
        assertEquals(W, ts.width);
        // a 4:3 video on the 16:9 picture: its own shape in the middle, black beside it; the OSD drawn over it
        TsReader.Picture mid = null;
        for (TsReader.Picture p : ts.kept()) if (red(p.at(W / 2 + 200, 200, W))) mid = p;
        assertNotNull(mid, "a picture of the video");
        assertTrue(dark(mid.at(40, H / 2, W)) && dark(mid.at(W - 40, H / 2, W)), "black beside a 4:3 picture, not stretched");
        int osd = 0;
        for (int y = 40; y < H - 40; y += 2) for (int x = 200; x < W - 200; x += 2) if (!red(mid.at(x, y, W))) osd++;
        assertTrue(osd > 40, "the OSD is drawn into the video (the test picture is plain red): " + osd + " pixels of it");
        // the first and the last pictures are the page
        assertTrue(dark(ts.kept().get(0).at(W / 2 + 200, 200, W)));
        assertTrue(dark(ts.kept().get(ts.kept().size() - 1).at(W / 2 + 200, 200, W)));
    }

    @Test
    void aVideoThatStandsStillStaysWithTheOsdGoingOnThenGivesWayToThePage(@TempDir Path dir) throws Exception {
        StanagFeed f = feed(dir.resolve("gap.ts"), new AtomicLong(), new ArrayList<>());
        f.videoGoneMs = 1200;
        f.setLive(new StanagFeed.Live(osd(true), false, null));
        play(f, TestStreams.h264(320, 240, 20, 10), 320, 240);
        long video = f.sent(StanagShow.Kind.VIDEO);
        Thread.sleep(700); // the link stands still: the last picture goes on, five a second
        assertEquals(StanagShow.Kind.VIDEO, f.showing());
        long still = f.sent(StanagShow.Kind.VIDEO) - video;
        assertTrue(still >= 2 && still <= 5, "the last picture again while the link is silent: " + still);
        await(() -> f.showing() == StanagShow.Kind.PAGE, 3000, "the page once the video is gone for good");
        f.close();
    }

    @Test
    void aTilesStreamIsHeldToTenPicturesASecond(@TempDir Path dir) throws Exception {
        StanagFeed f = feed(dir.resolve("tile.ts"), new AtomicLong(), new ArrayList<>());
        f.setLive(new StanagFeed.Live(osd(true), true, null));
        long t0 = System.currentTimeMillis();
        play(f, TestStreams.h264(320, 240, 60, 15), 320, 240);
        double seconds = (System.currentTimeMillis() - t0) / 1000.0;
        long sent = f.sent(StanagShow.Kind.VIDEO);
        f.close();
        assertTrue(sent >= 5 * seconds && sent <= 11 * seconds + 2, sent + " pictures in " + seconds + " s");
    }

    @Test
    void anAlertsPicturesGoOutUnderTheirBarAndThePageReturnsAfter(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("alert.ts");
        StanagFeed f = feed(file, new AtomicLong(), new ArrayList<>());
        BufferedImage picture = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(new Color(0x2060c0));
        g.fillRect(0, 0, 640, 360);
        g.dispose();
        await(() -> f.sent(StanagShow.Kind.PAGE) >= 2, 6000, "the page first");
        f.setAlert(new StanagFeed.Alert(picture, new float[][]{{0.4f, 0.4f, 0.1f, 0.2f, 0}}, "MobileAlpha - OVERWATCH - ALERT - LIVE 12:00:00"));
        await(() -> f.sent(StanagShow.Kind.ALERT) >= 4, 6000, "the alert's picture");
        assertEquals(StanagShow.Kind.ALERT, f.showing());
        f.setAlert(null);
        await(() -> f.showing() == StanagShow.Kind.PAGE, 3000, "the page after the alert");
        long pages = f.sent(StanagShow.Kind.PAGE);
        await(() -> f.sent(StanagShow.Kind.PAGE) >= pages + 3, 3000, "pages after the alert");
        f.close();
        TsReader ts = TsReader.read(file, 1, W, H);
        assertEquals(ts.pictures.size(), ts.decoded());
        assertTrue(ts.timesRise());
        boolean seen = false;
        for (TsReader.Picture p : ts.kept()) {
            int bar = p.at(W - 20, 20, W), body = p.at(W / 2 + 250, 500, W);
            if (red(bar) && (body & 0xff) > 130 && ((body >> 16) & 0xff) < 90) seen = true; // the red bar over the blue picture
        }
        assertTrue(seen, "the picture under the red bar");
        // the pilot: "the stream freezes on the last frame for a while" after DISMISS - the page before and after the
        // alert was the same one, so it was not painted again over the alert's picture
        TsReader.Picture last = ts.kept().get(ts.kept().size() - 1);
        assertTrue(dark(last.at(W - 20, 20, W)) && dark(last.at(W / 2 + 250, 500, W)) && dark(last.at(40, H - 40, W)),
                "the same page as before the alert is the page again, not the alert's last picture");
    }

    @Test
    void aClipPlaysAtItsOwnPaceTellsItsEndAndIsNotPlayedAgain(@TempDir Path dir) throws Exception {
        Path part1 = clip(dir, "20261112-120000.mkv", 30), part2 = clip(dir, "20261112-120000-part2.mkv", 30);
        AtomicLong ended = new AtomicLong(-1);
        Path file = dir.resolve("clip.ts");
        StanagFeed f = feed(file, ended, new ArrayList<>());
        await(() -> f.sent(StanagShow.Kind.PAGE) >= 2, 6000, "the page first");
        long t0 = System.currentTimeMillis();
        f.setClip(new StanagFeed.Clip(77, () -> List.of(part1, part2), "MobileAlpha - OVERWATCH - CLIP 12:00:00 - RECORDED"));
        await(() -> ended.get() == 77, 8000, "the clip's end");
        long took = System.currentTimeMillis() - t0;
        long sent = f.sent(StanagShow.Kind.CLIP);
        assertTrue(took >= 1500 && took <= 4500, "two parts of a second each, at their own pace: " + took + " ms");
        assertTrue(sent >= 15 && sent <= 34, "up to fifteen pictures a second: " + sent);
        long pages = f.sent(StanagShow.Kind.PAGE);
        await(() -> f.sent(StanagShow.Kind.PAGE) >= pages + 3, 3000, "the page after the clip");
        assertEquals(sent, f.sent(StanagShow.Kind.CLIP), "the same clip still set: not played again");
        f.close();
        TsReader ts = TsReader.read(file, 1, W, H);
        assertEquals(ts.pictures.size(), ts.decoded());
        assertTrue(ts.timesRise());
        boolean seen = false;
        for (TsReader.Picture p : ts.kept()) {
            int bar = p.at(W - 20, 20, W);
            boolean amber = ((bar >> 16) & 0xff) > 200 && ((bar >> 8) & 0xff) > 170 && (bar & 0xff) < 90;
            if (amber && red(p.at(W / 2, 400, W))) seen = true; // the amber bar over the clip's picture
        }
        assertTrue(seen, "the clip under its amber bar");
        TsReader.Picture last = ts.kept().get(ts.kept().size() - 1);
        assertTrue(dark(last.at(W - 20, 20, W)) && dark(last.at(W / 2, 505, W)), "the same page again after the clip, not its last picture");
    }

    @Test
    void aClipTakenAwayStopsAtOnceAndAnAlertOrTheVideoGoesBeforeIt(@TempDir Path dir) throws Exception {
        Path part = clip(dir, "long.mkv", 150); // five seconds
        AtomicLong ended = new AtomicLong(-1);
        StanagFeed f = feed(dir.resolve("stop.ts"), ended, new ArrayList<>());
        StanagFeed.Clip c = new StanagFeed.Clip(5, () -> List.of(part), "clip");
        f.setClip(c);
        await(() -> f.sent(StanagShow.Kind.CLIP) >= 4, 6000, "the clip plays");
        // new motion: the alert's picture goes before the clip
        BufferedImage picture = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        f.setAlert(new StanagFeed.Alert(picture, null, "alert"));
        await(() -> f.showing() == StanagShow.Kind.ALERT, 2000, "the alert over the clip");
        long clipSent = f.sent(StanagShow.Kind.CLIP);
        Thread.sleep(400);
        assertEquals(clipSent, f.sent(StanagShow.Kind.CLIP), "nothing of the clip beside the alert");
        // the owner takes the clip away (it ended it): the page once the alert is over
        f.setClip(null);
        f.setAlert(null);
        await(() -> f.showing() == StanagShow.Kind.PAGE, 2000, "the page");
        // a clip again, then PILOT UAV: the video goes before it
        f.setClip(new StanagFeed.Clip(6, () -> List.of(part), "clip"));
        await(() -> f.showing() == StanagShow.Kind.CLIP, 3000, "the clip plays");
        f.setLive(new StanagFeed.Live(osd(false), false, null));
        play(f, TestStreams.h264(320, 240, 12, 12), 320, 240);
        assertEquals(StanagShow.Kind.VIDEO, f.showing());
        assertEquals(-1, ended.get(), "none of them played to its end");
        f.close();
    }

    @Test
    void aClipThatCannotBePlayedIsToldAsOverAndThePageStays(@TempDir Path dir) throws Exception {
        AtomicLong ended = new AtomicLong(-1);
        List<String> log = new ArrayList<>();
        StanagFeed f = feed(dir.resolve("none.ts"), ended, log);
        f.setClip(new StanagFeed.Clip(9, List::of, "clip")); // its file went meanwhile
        await(() -> ended.get() == 9, 3000, "told as over");
        assertEquals(StanagShow.Kind.PAGE, f.showing());
        f.close();
    }

    @Test
    void handingAFrameOverNeverWaitsForTheStream(@TempDir Path dir) throws Exception {
        StanagFeed f = feed(dir.resolve("busy.ts"), new AtomicLong(), new ArrayList<>());
        f.setLive(new StanagFeed.Live(osd(true), false, null));
        TestStreams.Stream s = TestStreams.h264(1280, 720, 240, 30, true);
        long t0 = System.nanoTime();
        f.onConfig(1280, 720, false, s.codecConfig());
        for (TestStreams.Packet p : s.packets()) f.onFrame(p.data(), p.keyFrame(), MonoClock.ms()); // eight seconds of video at once
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 200, "240 frames handed over in " + ms + " ms");
        await(() -> f.sent(StanagShow.Kind.VIDEO) >= 3, 8000, "it goes on with what it kept");
        assertTrue(f.leftOut() > 0, "the rest was left out of the stream, never queued without end");
        f.close();
    }
}
