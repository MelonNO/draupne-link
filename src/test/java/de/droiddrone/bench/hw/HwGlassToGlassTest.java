package de.droiddrone.bench.hw;

import com.google.zxing.common.BitMatrix;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.LinkLog;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.BenchFleetAccess;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.video.LatencyMeter;
import de.droiddrone.testing.Ports;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picture delay glass to glass ({@link HwGlassToGlassScenario}) held before it meets the phone and its camera: the
 * cells and their time, the figures, the meter's readings told apart look by look - on the app's own LatencyMeter fed
 * pictures of its own code, and read through the video view as the scenario reads it - the app's link log as the
 * source of its own estimate (its real header), the camera check's words when no code is seen, where the code is found
 * and held to the main screen, the rows, the film's words, and the stand-in spectator watching through a real pilot
 * link to a made-up drone.
 */
class HwGlassToGlassTest {
    // ------------------------------------------------------------------ the cells

    @Test
    void theTableChangesOneThingACellAndFitsItsTimeQuickItsFewMinutes() {
        List<HwGlassToGlass.Cell> plan = HwGlassToGlass.plan(1, false);
        assertEquals(List.of("clean, NORMAL, H.265", "4G good, NORMAL, H.265", "one bar, NORMAL, H.265", "slow 300 ms, NORMAL, H.265", "one bar, AUTO", "clean, AUTO",
                "clean, SHARP, H.265", "clean, SHARP, H.264", "clean, NORMAL, H.264", "clean, ROBUST", "clean, SURVIVAL", "clean, NORMAL, H.265, recording off",
                "clean, NORMAL, H.265, STANAG on", "clean, NORMAL, H.265, a spectator watching", "clean, NORMAL, H.265, again"),
                plan.stream().map(HwGlassToGlass.Cell::name).toList());
        HwGlassToGlass.Cell base = plan.get(0);
        assertEquals("clean (nothing added)", base.net().name, "the network as it is: the bench adds nothing (its own clean adds 30 ms each way)");
        assertEquals(ControlLink.VideoMode.NORMAL, base.picture());
        assertEquals(Boolean.FALSE, base.h264());
        assertTrue(base.recording() && !base.stanag() && !base.spectator(), "the base as a pilot has the app: recording on, no STANAG, nobody watching");
        for (int i = 0; i < plan.size(); i++) {
            HwGlassToGlass.Cell c = plan.get(i);
            // one thing changed from the base - or from a cell before it (one bar AUTO beside one bar NORMAL, SHARP H.264 beside SHARP H.265)
            boolean one = changed(base, c) <= 1;
            for (HwGlassToGlass.Cell before : plan.subList(0, i)) one |= changed(before, c) == 1;
            assertTrue(one, c.name() + ": one thing changed from the base or from a cell before it");
            assertEquals(c.picture() == ControlLink.VideoMode.AUTO ? HwGlassToGlass.SETTLE_AUTO_MS : HwGlassToGlass.SETTLE_MS, c.settleMs(), c.name());
            assertEquals(HwGlassToGlass.MEASURE_MS, c.measureMs());
            assertTrue(c.picture() == ControlLink.VideoMode.ROBUST || c.picture() == ControlLink.VideoMode.SURVIVAL ? c.h264() == null : c.h264() != null,
                    c.name() + ": ROBUST and SURVIVAL send H.264 by the app's own rule; the rest ask a codec");
        }
        assertTrue(plan.get(1).net().name.startsWith("4G good") && plan.get(2).net().name.startsWith("one bar") && plan.get(3).net().name.startsWith("slow"));
        assertNotSame(plan.get(2).net(), plan.get(4).net(), "each cell its own network (a BadLink keeps its own random sequence)");
        assertEquals("the picture's own", plan.get(9).codecWords());
        long full = HwGlassToGlass.estimateMs(plan);
        assertTrue(full >= 15 * 60_000L && full <= 30 * 60_000L, "the whole table in about 25 minutes: " + full / 60_000 + " min");

        List<HwGlassToGlass.Cell> quick = HwGlassToGlass.plan(1, true);
        assertEquals(List.of("clean, NORMAL, H.265", "one bar, NORMAL, H.265"), quick.stream().map(HwGlassToGlass.Cell::name).toList(), "quick: the base and one preset");
        assertTrue(quick.stream().allMatch(c -> c.measureMs() == HwGlassToGlass.MEASURE_QUICK_MS));
        long quickMs = HwGlassToGlass.estimateMs(quick);
        assertTrue(quickMs <= 6 * 60_000L, "quick: about five minutes, " + quickMs / 1000 + " s");
        String words = HwGlassToGlass.planWords(quick, true);
        assertTrue(words.contains("QUICK") && words.contains("one bar, NORMAL, H.265") && words.contains(HwGlassToGlass.SET_UP), words);
    }

    /** What differs between two cells: the network (by its name), the picture, the codec asked (none asked: the picture's own), recording, STANAG, a spectator. */
    private static int changed(HwGlassToGlass.Cell a, HwGlassToGlass.Cell b) {
        boolean sameCodec = a.h264() == null || b.h264() == null || a.h264().equals(b.h264());
        return (a.net().name.equals(b.net().name) ? 0 : 1) + (a.picture() == b.picture() ? 0 : 1) + (sameCodec ? 0 : 1)
                + (a.recording() == b.recording() ? 0 : 1) + (a.stanag() == b.stanag() ? 0 : 1) + (a.spectator() == b.spectator() ? 0 : 1);
    }

    // ------------------------------------------------------------------ the figures

    @Test
    void theFiguresAreTheMiddleOneThe90thPercentileAndTheMost() {
        List<Integer> ten = List.of(7, 3, 10, 1, 9, 2, 8, 4, 6, 5);
        HwGlassToGlass.Spread s = HwGlassToGlass.Spread.of(ten);
        assertEquals(10, s.n());
        assertEquals(6, s.median(), "the upper of the middle two, as the app's own Latency test takes it");
        assertEquals(9, s.p90(), "9 in 10 readings at most this");
        assertEquals(10, s.max());
        assertEquals(1, s.min());
        HwGlassToGlass.Spread one = HwGlassToGlass.Spread.of(List.of(142.4));
        assertEquals(142.4, one.median());
        assertEquals(142.4, one.p90());
        HwGlassToGlass.Spread none = HwGlassToGlass.Spread.of(List.of());
        assertEquals(0, none.n());
        assertTrue(Double.isNaN(none.median()) && Double.isNaN(none.max()));
        assertEquals("-", HwGlassToGlass.ms(none.median()));
        assertEquals("143", HwGlassToGlass.ms(142.5));
        List<Double> hundred = new ArrayList<>();
        for (int i = 1; i <= 100; i++) hundred.add((double) i);
        assertEquals(90, HwGlassToGlass.Spread.of(hundred).p90());
        assertEquals(51, HwGlassToGlass.Spread.of(hundred).median());
    }

    // ------------------------------------------------------------------ the meter's readings, look by look

    private static List<Double> range(int from, int to) {
        List<Double> out = new ArrayList<>();
        for (int i = from; i < to; i++) out.add(100.0 + i);
        return out;
    }

    @Test
    void theMetersReadingsAreToldApartLookByLook() {
        assertEquals(3, HwGlassToGlass.fresh(List.of(), range(0, 3)), "the first look: all are new");
        assertEquals(2, HwGlassToGlass.fresh(range(0, 3), range(0, 5)), "two appended");
        assertEquals(0, HwGlassToGlass.fresh(range(0, 5), range(0, 5)), "nothing new");
        assertEquals(4, HwGlassToGlass.fresh(range(0, 60), range(4, 64)), "full: four appended, the four oldest dropped");
        assertEquals(60, HwGlassToGlass.fresh(range(0, 60), range(100, 160)), "nothing in common: all new, some missed");
        assertEquals(0, HwGlassToGlass.fresh(range(0, 5), List.of()), "a meter without readings");

        HwGlassToGlass.Readings r = new HwGlassToGlass.Readings();
        r.base(range(0, 10)); // the camera check's: not the cell's
        r.take(range(0, 12));
        r.take(range(0, 12));
        r.take(range(0, 15));
        assertEquals(range(10, 15), r.values);
        assertEquals(0, r.gaps);
        r.take(range(0, 60));
        r.take(range(10, 70));
        assertEquals(range(10, 70), r.values);
        assertEquals(0, r.gaps);
        r.take(range(200, 260));
        assertEquals(1, r.gaps, "more than the meter keeps between two looks: said");
        assertEquals(120, r.values.size());
    }

    /** A grey "camera picture" of the screen with the code painted at (x, y), modules of the given size (as VideoTest paints one). */
    static int[] frameWithCode(BitMatrix code, int w, int h, int x, int y, int scale) {
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0x404040);
        int size = (code.getWidth() + 8) * scale;
        for (int yy = 0; yy < size; yy++) {
            for (int xx = 0; xx < size; xx++) {
                int mx = xx / scale - 4, my = yy / scale - 4;
                boolean dark = mx >= 0 && my >= 0 && mx < code.getWidth() && my < code.getHeight() && code.get(mx, my);
                px[(y + yy) * w + x + xx] = dark ? 0x101010 : 0xf0f0f0;
            }
        }
        return px;
    }

    private static <T> T onSwing(java.util.concurrent.Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(c.call());
            } catch (Exception e) {
                error.set(e);
            }
        });
        if (error.get() != null) throw error.get();
        return out.get();
    }

    @Test
    void theTapReadsTheAppsOwnMeterReadingByReadingThroughTheVideoView() throws Exception {
        Class<?> type = Class.forName(MeterTap.VIEW);
        Constructor<?> make = type.getDeclaredConstructor();
        make.setAccessible(true);
        Component view = onSwing(() -> (Component) make.newInstance());
        Method set = type.getDeclaredMethod("setLatencyMeter", LatencyMeter.class);
        set.setAccessible(true);
        assertNull(MeterTap.meter(view), "no Latency test: no meter");
        assertFalse(Double.isNaN(MeterTap.displayMs(view)), "the view's screen time is read");
        try (LatencyMeter m = new LatencyMeter()) {
            onSwing(() -> set.invoke(view, m));
            assertSame(m, MeterTap.meter(view), "the Latency test's meter, as the view has it");
            assertEquals(List.of(), MeterTap.readings(m));
            HwGlassToGlass.Readings r = new HwGlassToGlass.Readings();
            r.base(MeterTap.readings(m));
            long[] delays = {100, 130, 115, 160};
            for (int i = 0; i < delays.length; i++) {
                long painted = System.nanoTime();
                int[] frame = frameWithCode(m.codeNow(), 640, 360, 100, 40, 9);
                int want = i + 1;
                boolean taken = false;
                // offered again only when it was not taken: the meter drops a picture while it reads the one before
                for (int tries = 0; tries < 5 && !taken; tries++) {
                    m.offer(frame, 640, 360, painted + delays[i] * 1_000_000L);
                    taken = Waits.until(() -> m.result().count() >= want, 2000);
                }
                assertTrue(taken, "reading " + want + " taken");
                r.take(MeterTap.readings(m));
            }
            assertEquals(delays.length, r.values.size(), "each reading once: " + r.values);
            for (int i = 0; i < delays.length; i++) {
                double v = r.values.get(i);
                assertTrue(v > delays[i] - 20 && v <= delays[i] + 0.1, "reading " + i + ": just under " + delays[i] + " ms, got " + v);
            }
            assertEquals(r.values, MeterTap.readings(m), "in the order the meter took them");
            onSwing(() -> set.invoke(view, (Object) null));
            assertNull(MeterTap.meter(view), "the test ended: no meter");
        }
    }

    // ------------------------------------------------------------------ the app's own estimate, from its link log

    @Test
    void theAppsOwnEstimateIsReadFromItsLinkLogUnderItsRealHeader(@TempDir Path logs) throws Exception {
        try (LinkLog log = LinkLog.open(logs)) {
            assertNotNull(log);
        }
        Path file;
        try (var files = Files.list(logs)) {
            file = files.filter(p -> p.getFileName().toString().startsWith("link-")).findFirst().orElseThrow();
        }
        String header = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
        List<String> head = List.of(header.split(",", -1));
        for (String column : List.of("time", "link", "g2g_ms", "g2g_max_ms", "capture_to_send_ms", "video_net_ms", "video_res", "video_rx_fps", "ping_ms")) {
            assertTrue(head.contains(column), "the app's link log has the column " + column + ": " + header);
        }
        List<String> lines = new ArrayList<>(List.of(header));
        lines.add(row(head, "10:00:00.000", "READY", Map.of("g2g_ms", "120", "g2g_max_ms", "150", "capture_to_send_ms", "40", "video_net_ms", "30",
                "video_res", "1280x720", "video_rx_fps", "30", "ping_ms", "40")));
        lines.add(row(head, "10:00:00.100", "READY", Map.of("g2g_ms", "130", "g2g_max_ms", "190", "capture_to_send_ms", "42", "video_net_ms", "32",
                "video_res", "1280x720", "video_rx_fps", "29", "ping_ms", "44")));
        lines.add(row(head, "10:00:00.150", "", Map.of("event", "\"Video: H.265 1920x1080, \"\"a quote\"\"\"")));
        lines.add(row(head, "10:00:00.200", "READY", Map.of("g2g_ms", "", "g2g_max_ms", "", "video_res", "1920x1080", "video_rx_fps", "0", "ping_ms", "")));
        lines.add(row(head, "10:00:00.300", "READY", Map.of("g2g_ms", "140", "g2g_max_ms", "170", "video_res", "1920x1080", "video_rx_fps", "31", "ping_ms", "48")));
        lines.add("10:00:00.4"); // the row being written
        Files.write(file, lines, StandardCharsets.UTF_8);

        List<HwGlassToGlass.Estimate> all = HwGlassToGlass.readEstimates(logs, System.currentTimeMillis() - 60_000);
        assertEquals(4, all.size(), "the sample rows; the event's and the half-written one left out: " + all);
        assertEquals(List.of(), HwGlassToGlass.readEstimates(logs, System.currentTimeMillis() + 60_000), "a log not written since the cell began is not read");
        assertEquals(-1, all.get(2).g2gMs(), "blank: no figure");
        List<HwGlassToGlass.Estimate> stretch = HwGlassToGlass.between(all, LocalTime.parse("10:00:00.050"), LocalTime.parse("10:00:00.300"));
        assertEquals(3, stretch.size());

        HwGlassToGlass.AppFigures f = HwGlassToGlass.appFigures(all, 12);
        assertEquals(4, f.rows());
        assertEquals(3, f.withFigure());
        assertTrue(f.any());
        assertEquals(142, f.g2g().median(), "130 + the view's 12 ms on the screen, as the OSD shows it");
        assertEquals(152, f.g2g().p90());
        assertEquals(202, f.maxMs(), "the highest single picture's, 190 + 12");
        assertEquals(42, f.captureMedian());
        assertEquals(32, f.netMedian());
        assertEquals("1280x720 50 %, 1920x1080 50 %", f.sizes());
        assertEquals(22.5, f.fpsMean(), 1e-9);
        assertEquals(44, f.pingMedian());
        HwGlassToGlass.AppFigures none = HwGlassToGlass.appFigures(List.of(all.get(2)), Double.NaN);
        assertFalse(none.any());
        assertEquals("1920x1080", none.sizes(), "one size alone: no share");
        assertTrue(Double.isNaN(none.maxMs()));
    }

    /** A link-log row under that header: every column blank but the time, the link and these. */
    private static String row(List<String> head, String time, String link, Map<String, String> values) {
        List<String> cells = new ArrayList<>();
        for (String c : head) cells.add(c.equals("time") ? time : c.equals("link") ? link : values.getOrDefault(c, ""));
        return String.join(",", cells);
    }

    @Test
    void theStreamsTheStillAndTheOwnTestAreReadFromTheAppsLog() {
        List<String> log = List.of("10:00:01 Link: ready", "10:00:02 Video: H.265 1280x720", "10:00:02 Video: H.265 1280x720", "10:00:20 Video: H.265 1920x1080",
                "10:00:40 Latency test: point the camera at the code on screen. Esc ends it.",
                "10:01:30 Latency test, camera to screen: median 142 ms (min 120, max 190, 60 readings)",
                "10:01:31 Still saved: C:\\bench\\Videos\\still-2026-11-23_10-01-31.png and still-2026-11-23_10-01-31-osd.png.");
        List<String> streams = HwGlassToGlass.streams(log);
        assertEquals(List.of("H.265 1280x720", "H.265 1920x1080"), streams, "a repeat left out, a size change kept");
        assertEquals("H.265", HwGlassToGlass.codecs(streams));
        assertEquals("H.264 then H.265", HwGlassToGlass.codecs(List.of("H.264 640x360", "H.265 1280x720")));
        assertEquals("-", HwGlassToGlass.codecs(List.of()));
        assertEquals("median 142 ms (min 120, max 190, 60 readings)", HwGlassToGlass.ownTestSaid(log));
        assertEquals("-", HwGlassToGlass.ownTestSaid(log.subList(0, 4)));
        assertEquals("C:\\bench\\Videos\\still-2026-11-23_10-01-31.png", HwGlassToGlass.stillSaved(log));
        assertEquals("C:\\v\\still-1.png", HwGlassToGlass.stillSaved(List.of("Still saved: C:\\v\\still-1.png.")), "without the OSD's copy");
        assertNull(HwGlassToGlass.stillSaved(List.of("Still: no picture.")));
    }

    // ------------------------------------------------------------------ the camera check, the windows

    @Test
    void whenTheCameraSeesNoCodeTheRunSaysSoPlainlyWithItsStillAndSkipsTheRest() {
        assertNull(HwGlassToGlass.cameraCheck(1, 400, true, null), "a reading: the camera sees the code");
        String skip = HwGlassToGlass.cameraCheck(0, 25_000, true, "C:\\run\\matrix\\hw-g2g-camera-check.png");
        assertTrue(skip.startsWith("THE CAMERA DOES NOT SEE THE LATENCY TEST'S CODE: no reading of it in 25 s."), skip);
        assertTrue(skip.contains("no failure of the app") && skip.contains("the rest of the table is skipped"), skip);
        assertTrue(skip.contains("What the camera saw: C:\\run\\matrix\\hw-g2g-camera-check.png"), skip);
        assertTrue(skip.contains(HwGlassToGlass.SET_UP), "the hands step said with it");
        String dark = HwGlassToGlass.cameraCheck(0, 25_000, false, null);
        assertTrue(dark.contains("no picture came from the phone at all") && dark.contains("no still could be saved"), dark);
    }

    @Test
    void theCodeIsFoundAsAReaderFindsItAndHeldToTheMainScreen() throws Exception {
        BufferedImage img = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 900, 600);
        BitMatrix code;
        try (LatencyMeter m = new LatencyMeter()) {
            code = m.codeNow(); // the app's own code, as its Latency test paints it
        }
        int scale = 12, x0 = 40, y0 = 70; // the white border of 4 modules at (x0, y0), the code 4 modules in
        g.setColor(Color.WHITE);
        g.fillRect(x0, y0, (code.getWidth() + 8) * scale, (code.getWidth() + 8) * scale);
        g.setColor(Color.BLACK);
        for (int y = 0; y < code.getHeight(); y++) {
            for (int x = 0; x < code.getWidth(); x++) if (code.get(x, y)) g.fillRect(x0 + (4 + x) * scale, y0 + (4 + y) * scale, scale, scale);
        }
        g.dispose();
        Rectangle found = HwGlassToGlass.findCode(img); // throws nothing: a picture without a code is null
        assertNotNull(found, "the code is read");
        Rectangle want = new Rectangle(x0 + 4 * scale, y0 + 4 * scale, code.getWidth() * scale, code.getWidth() * scale);
        assertTrue(Math.abs(found.x - want.x) <= 4 && Math.abs(found.y - want.y) <= 4 && Math.abs(found.width - want.width) <= 6, found + " against " + want);
        assertNull(HwGlassToGlass.findCode(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB)), "no code: none");

        Rectangle screen = new Rectangle(0, 0, 1920, 1080);
        Rectangle onIt = new Rectangle(56, 300, 588, 588);
        assertNull(HwGlassToGlass.placementProblem(screen, onIt, Map.of()));
        assertNull(HwGlassToGlass.placementProblem(screen, onIt, Map.of("DRAUPNE Link - map", new Rectangle(1200, 100, 600, 800))), "a window beside the code");
        String covered = HwGlassToGlass.placementProblem(screen, onIt, Map.of("Name map", new Rectangle(500, 500, 400, 300)));
        assertTrue(covered != null && covered.contains("\"Name map\" lies over the code"), covered);
        String off = HwGlassToGlass.placementProblem(screen, new Rectangle(-30000 + 56, 300, 588, 588), Map.of());
        assertTrue(off != null && off.contains("not wholly on the main screen") && off.contains("1920x1080 at 0,0"), off);
        assertTrue(HwGlassToGlass.placementProblem(screen, new Rectangle(1700, 300, 588, 588), Map.of()).contains("not wholly"), "half off the screen");
        assertTrue(HwGlassToGlass.placementProblem(screen, null, Map.of()).contains("could not be read"));
    }

    // ------------------------------------------------------------------ the rows and the words

    @Test
    void aRowHasEveryColumnTheCellsSettingsAndItsFigures(@TempDir Path dir) throws Exception {
        HwGlassToGlass.Cell c = HwGlassToGlass.plan(3, false).get(13);
        HwLinkMatrix.Row row = HwGlassToGlass.row(c);
        assertEquals(HwGlassToGlass.COLUMNS.size(), row.line().split("\t", -1).length, "a cell that stopped half way writes a whole row");
        assertEquals("clean, NORMAL, H.265, a spectator watching", row.get("cell"));
        assertEquals("NORMAL", row.get("picture"));
        assertEquals("H.265", row.get("codec asked"));
        assertEquals("on", row.get("recording"));
        assertEquals("off", row.get("STANAG"));
        assertEquals("one watching", row.get("spectator"));
        assertEquals("-", row.get("camera median ms"));

        HwGlassToGlass.Spread camera = HwGlassToGlass.Spread.of(List.of(150.2, 140.0, 160.4, 155.0));
        HwGlassToGlass.AppFigures app = new HwGlassToGlass.AppFigures(400, 380, HwGlassToGlass.Spread.of(List.of(120.0, 130.0, 125.0)), 180, 40, 30,
                "1280x720", 29.6, 41);
        HwGlassToGlass.put(row, camera, 45_200, app, 11.5);
        assertEquals("45", row.get("measured s"));
        assertEquals("4", row.get("readings"));
        assertEquals("0.1", row.get("readings a second"));
        assertEquals("155", row.get("camera median ms"));
        assertEquals("160", row.get("camera p90 ms"));
        assertEquals("160", row.get("camera max ms"));
        assertEquals("140", row.get("camera min ms"));
        assertEquals("125", row.get("app median ms"));
        assertEquals("180", row.get("app max ms"));
        assertEquals("30", row.get("camera minus app ms"));
        assertEquals("11.5", row.get("screen ms"));
        assertEquals("1280x720", row.get("size seen"));
        Path file = HwLinkMatrix.append(dir, HwGlassToGlass.TABLE, row);
        assertEquals("hw-g2g.tsv", file.getFileName().toString());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(String.join("\t", HwGlassToGlass.COLUMNS), lines.get(0));
        assertTrue(lines.get(1).startsWith("clean, NORMAL, H.265, a spectator watching\t"));

        String words = HwGlassToGlass.words("clean, NORMAL, H.265", camera, 45_200, app, "H.265 1280x720");
        assertTrue(words.startsWith("clean, NORMAL, H.265: camera to screen 155 ms in the middle, 9 in 10 readings at most 160 ms, the most 160 ms (4 readings in 45 s)"), words);
        assertTrue(words.contains("the app's own figure 125 ms in the middle") && words.contains("30 ms under what the camera measured"), words);
        assertTrue(words.contains("the phone's part 40 ms, the network's 30 ms") && words.contains("H.265 1280x720") && words.contains("29.6 frames a second"), words);
        String blind = HwGlassToGlass.words("clean, SURVIVAL", HwGlassToGlass.Spread.of(List.of()), 45_000, app, "H.264 480x270");
        assertTrue(blind.contains("NO READING of the code in 45 s") && !blind.contains("under what"), blind);
        String noOwn = HwGlassToGlass.words("x", camera, 45_000, HwGlassToGlass.appFigures(List.of(), 0), "");
        assertTrue(noOwn.contains("the app had no figure of its own"), noOwn);
    }

    // ------------------------------------------------------------------ the stand-in spectator

    @Test
    void theStandInSpectatorWatchesThroughARealPilotLinkAndIsRefusedWhereSpectatorsAreOff() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("cameraWidth", "640"); // a small picture: the stand-in counts packets, it decodes nothing
        config.set("cameraHeight", "360");
        List<String> said = new java.util.concurrent.CopyOnWriteArrayList<>();
        VirtualDrone drone = BenchFleetAccess.start(0, "MobileGolf", config.getKey(), said::add);
        ControlLink pilot = null;
        try {
            int pilotPort = Ports.free();
            pilot = new ControlLink(config, InetAddress.getLoopbackAddress(), drone.port(), pilotPort);
            pilot.enableSpectators(true, 600);
            pilot.setEvents(said::add);
            pilot.setVideoListener(new ControlLink.VideoListener() {
                @Override
                public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
                }

                @Override
                public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
                }
            }, false);
            pilot.start();
            ControlLink p = pilot;
            Waits.waitFor(() -> p.getState() == ControlLink.State.READY, 15_000, "the pilot's link READY", said);
            try (StandInSpectator s = new StandInSpectator(InetAddress.getLoopbackAddress(), pilotPort, config.getKey())) {
                assertTrue(s.join(5000), "let in: " + s.words() + " " + said);
                Waits.waitFor(() -> s.streamPackets() > 20 && s.snapshotCame(), 15_000, "the picture passed on, the snapshot", said);
                assertEquals(1, pilot.getSpectatorCount());
                assertTrue(s.words().contains("packets passed on") && s.words().contains("the snapshot came"), s.words());
            }
            Waits.waitFor(() -> p.getSpectatorCount() == 0, 5000, "the stand-in gone after its BYE", said);
            try (StandInSpectator wrongKey = new StandInSpectator(InetAddress.getLoopbackAddress(), pilotPort, "not the key")) {
                assertFalse(wrongKey.join(1200), "another key: no answer at all");
                assertTrue(wrongKey.words().startsWith("not let in"), wrongKey.words());
            }
            pilot.setSpectatorsAllowed(false);
            try (StandInSpectator off = new StandInSpectator(InetAddress.getLoopbackAddress(), pilotPort, config.getKey())) {
                assertFalse(off.join(3000));
                assertEquals(3, off.refusedFor(), "spectators not allowed");
                assertTrue(off.words().contains("spectators not allowed"), off.words());
            }
        } finally {
            if (pilot != null) pilot.close();
            BenchFleetAccess.stop(drone);
        }
        byte[] hello = StandInSpectator.hello("abc", StandInSpectator.MENDS_RS);
        assertEquals(List.of(0x63, 0, 3, 97, 98, 99, 1), toList(hello), "HELLO as a spectator of 1.0.71 says it: the key \"abc\", the flag that it mends RS");
        assertEquals(6, StandInSpectator.hello("abc", 0).length, "no flags: no flags byte, as 1.0.66 said it");
    }

    private static List<Integer> toList(byte[] b) {
        List<Integer> out = new ArrayList<>();
        for (byte x : b) out.add((int) x);
        return out;
    }
}
