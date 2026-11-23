package de.droiddrone.bench.hw;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ReaderException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.DetectorResult;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.detector.Detector;
import de.droiddrone.bench.BadLink;
import de.droiddrone.pc.ControlLink;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The picture delay glass to glass ({@code HwGlassToGlassScenario}): the real phone films the app's own Latency test -
 * its code on this PC's main screen - and each reading is set beside the app's own estimate of the same delay (the
 * OSD's G2G, video.GlassToGlass), per network, picture, codec, with recording, STANAG and a spectator on and off.
 * Everything of it that needs no phone and no camera: the cells and their time, the figures (median, 90th percentile,
 * most), the readings told apart between two looks at the meter, the app's link log as the estimate's source, where
 * the code is on the screen, and the words for the film - so the bench's own tests hold it first.
 */
final class HwGlassToGlass {
    private HwGlassToGlass() {
    }

    // ------------------------------------------------------------------ what the pilot sets up (the hands step)

    /** The hands step, in plain words: for the film, the skip, and the page. */
    static final String SET_UP = "the phone on a stand, its back camera facing this PC's main screen 40 to 60 cm away, the whole screen in its picture "
            + "(the left half at least: the code is there); the screen bright, no glare or reflection on it; DRAUPNE Flight running on the phone as for "
            + "any hardware run; nothing on top of the screen (no other window, no notification over its left half); mouse and keyboard left alone "
            + "while it runs. The scenario puts the app's main window on the main screen itself, maximized, the video in it - the Latency test paints "
            + "its code on the left of the video area, the phone's own picture as a small thumbnail at the bottom right";

    // ------------------------------------------------------------------ the cells

    /**
     * One cell: a network on the bench's wire, the picture (MENU &gt; VIDEO), the codec (the hidden setting videoCodec:
     * null leaves it at H.265, as ROBUST and SURVIVAL send H.264 whatever it says), recording (MENU &gt; VIDEO &gt;
     * RECORD VIDEO; on is the app's default), STANAG 4609 (MENU &gt; LINK), a stand-in spectator watching through the
     * app; how long the picture settles after it has come, and how long the camera's readings are taken.
     */
    record Cell(String name, BadLink net, ControlLink.VideoMode picture, Boolean h264, boolean recording, boolean stanag, boolean spectator,
                long settleMs, long measureMs) {
        String codecWords() {
            return h264 == null ? "the picture's own" : h264 ? "H.264" : "H.265";
        }
    }

    /** After the picture has come: a few seconds for the bitrate to climb; AUTO 25 s - it goes SHARP after 20 s calm at the top bitrate. */
    static final long SETTLE_MS = 8000, SETTLE_AUTO_MS = 25_000;
    /** The camera's readings, a cell; in a quick run. */
    static final long MEASURE_MS = 45_000, MEASURE_QUICK_MS = 30_000;
    /** How long the first cell waits for the camera's first reading of the code before the run says the camera does not see it. */
    static final long CAMERA_CHECK_MS = 25_000;

    /** The network as it is: nothing added by the bench (its "clean" puts 30 ms each way on). */
    static BadLink clean(long seed) {
        return new BadLink("clean (nothing added)", seed);
    }

    /** One of the bad-link table's steady networks, by the start of its name: a fresh one (a BadLink keeps its own random sequence). */
    static BadLink preset(long seed, String start) {
        for (BadLink n : BadLink.steady(seed, false)) if (n.name.startsWith(start)) return n;
        throw new IllegalStateException("BadLink.steady has no network called \"" + start + " ...\"");
    }

    static Cell cell(String name, BadLink net, ControlLink.VideoMode picture, Boolean h264, boolean recording, boolean stanag, boolean spectator,
                     long measureMs) {
        return new Cell(name, net, picture, h264, recording, stanag, spectator, picture == ControlLink.VideoMode.AUTO ? SETTLE_AUTO_MS : SETTLE_MS, measureMs);
    }

    /**
     * The table. Against one base - the network as it is, NORMAL, H.265, recording on (the app's default), no STANAG, no
     * spectator - one thing changed a cell: three networks of the bad-link table (4G good, one bar, slow 300 ms), AUTO on
     * one bar and as it is, SHARP and NORMAL in both codecs, ROBUST and SURVIVAL (H.264 by the app's own rule),
     * recording off, STANAG on, a spectator; the base once more at the end (has anything drifted - the phone warm?).
     * Quick: the base and one bar.
     */
    static List<Cell> plan(long seed, boolean quick) {
        ControlLink.VideoMode normal = ControlLink.VideoMode.NORMAL;
        if (quick) {
            return List.of(cell("clean, NORMAL, H.265", clean(seed), normal, false, true, false, false, MEASURE_QUICK_MS),
                    cell("one bar, NORMAL, H.265", preset(seed, "one bar"), normal, false, true, false, false, MEASURE_QUICK_MS));
        }
        long m = MEASURE_MS;
        return List.of(
                cell("clean, NORMAL, H.265", clean(seed), normal, false, true, false, false, m),
                cell("4G good, NORMAL, H.265", preset(seed, "4G good"), normal, false, true, false, false, m),
                cell("one bar, NORMAL, H.265", preset(seed, "one bar"), normal, false, true, false, false, m),
                cell("slow 300 ms, NORMAL, H.265", preset(seed, "slow"), normal, false, true, false, false, m),
                cell("one bar, AUTO", preset(seed, "one bar"), ControlLink.VideoMode.AUTO, false, true, false, false, m),
                cell("clean, AUTO", clean(seed), ControlLink.VideoMode.AUTO, false, true, false, false, m),
                cell("clean, SHARP, H.265", clean(seed), ControlLink.VideoMode.SHARP, false, true, false, false, m),
                cell("clean, SHARP, H.264", clean(seed), ControlLink.VideoMode.SHARP, true, true, false, false, m),
                cell("clean, NORMAL, H.264", clean(seed), normal, true, true, false, false, m),
                cell("clean, ROBUST", clean(seed), ControlLink.VideoMode.ROBUST, null, true, false, false, m),
                cell("clean, SURVIVAL", clean(seed), ControlLink.VideoMode.SURVIVAL, null, true, false, false, m),
                cell("clean, NORMAL, H.265, recording off", clean(seed), normal, false, false, false, false, m),
                cell("clean, NORMAL, H.265, STANAG on", clean(seed), normal, false, true, true, false, m),
                cell("clean, NORMAL, H.265, a spectator watching", clean(seed), normal, false, true, false, true, m),
                cell("clean, NORMAL, H.265, again", clean(seed), normal, false, true, false, false, m));
    }

    /** The app started, the phone found, the windows placed; and a cell's own beside its settling and measuring: menus, PILOT UAV, STANDBY, the phone's log. */
    static final long START_MS = 120_000, CELL_OVERHEAD_MS = 20_000;

    /** About how long the table takes (for the film, and to hold it to its time). */
    static long estimateMs(List<Cell> plan) {
        long ms = START_MS + CAMERA_CHECK_MS;
        for (Cell c : plan) ms += CELL_OVERHEAD_MS + c.settleMs() + c.measureMs();
        return ms;
    }

    static String planWords(List<Cell> plan, boolean quick) {
        return "THE PICTURE DELAY, GLASS TO GLASS" + (quick ? " - QUICK: two cells" : "") + ": the real phone films the app's own Latency test - its code on this "
                + "PC's main screen - and each reading of it is set beside the app's own estimate (the OSD's G2G). " + plan.size() + " cell(s): "
                + String.join("; ", plan.stream().map(Cell::name).toList()) + "; " + (plan.isEmpty() ? 0 : plan.get(0).measureMs() / 1000)
                + " s of readings each once the picture has settled; about " + Math.round(estimateMs(plan) / 60_000.0) + " min. Set up by hand: " + SET_UP + ".";
    }

    // ------------------------------------------------------------------ the figures

    /** Readings in a cell: how many, the middle one (as the app's own test takes it: the upper of two), the 90th percentile, the most and the least. NaN without any. */
    record Spread(int n, double median, double p90, double max, double min) {
        static Spread of(Collection<? extends Number> values) {
            double[] s = values.stream().mapToDouble(Number::doubleValue).filter(v -> !Double.isNaN(v)).sorted().toArray();
            if (s.length == 0) return new Spread(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            int p90 = (int) Math.ceil(0.9 * s.length) - 1; // nearest rank: 9 of 10 readings are at most this
            return new Spread(s.length, s[s.length / 2], s[Math.max(0, p90)], s[s.length - 1], s[0]);
        }
    }

    /** A figure as the table writes it: whole ms, "-" for none. */
    static String ms(double v) {
        return Double.isNaN(v) ? "-" : Long.toString(Math.round(v));
    }

    // ------------------------------------------------------------------ the meter's readings, look by look

    /** How many readings the app's LatencyMeter keeps (its last 60): more than that between two looks, and some are missed. */
    static final int METER_KEEPS = 60;

    /**
     * How many of the meter's readings are new since the look before: the meter appends each reading and drops its
     * oldest past {@link #METER_KEEPS}, so the new ones are what follows the longest end of the last look that this look
     * begins with. Nothing in common: all of them are new (a new meter, or more than it keeps since the last look).
     */
    static int fresh(List<Double> before, List<Double> now) {
        for (int k = 0; k < now.size(); k++) {
            int overlap = now.size() - k;
            if (overlap > before.size()) continue;
            if (before.subList(before.size() - overlap, before.size()).equals(now.subList(0, overlap))) return k;
        }
        return now.size();
    }

    /** A cell's readings gathered look by look ({@link #fresh}). */
    static final class Readings {
        private List<Double> last = List.of();
        final List<Double> values = new ArrayList<>();
        /** Looks between which the meter may have taken more than it keeps: readings missed (said, never hidden). */
        int gaps;

        /** What the meter has now is not this cell's: taken as seen. */
        void base(List<Double> now) {
            last = List.copyOf(now);
        }

        void take(List<Double> now) {
            int k = fresh(last, now);
            if (k == now.size() && !last.isEmpty() && !now.isEmpty() && now.size() >= METER_KEEPS) gaps++;
            values.addAll(now.subList(now.size() - k, now.size()));
            last = List.copyOf(now);
        }
    }

    // ------------------------------------------------------------------ the app's own estimate, from its link log

    /**
     * One sample row of the app's link log with what the estimate needs: the picture's delay from the camera to this
     * screen as the app estimates it (g2g_ms, evened out; g2g_max_ms, the highest single picture's of the last second),
     * its parts (the phone's capture to send, the network), the picture's size and frames, the ping. -1 / "" for blank.
     */
    record Estimate(LocalTime at, String link, int g2gMs, int g2gMaxMs, int captureMs, int netMs, String size, double fps, int pingMs) {
    }

    static List<Estimate> estimates(List<String> lines) {
        List<Estimate> rows = new ArrayList<>();
        if (lines.isEmpty()) return rows;
        List<String> head = Arrays.asList(lines.get(0).trim().split(",", -1));
        int time = head.indexOf("time"), link = head.indexOf("link"), g2g = head.indexOf("g2g_ms"), g2gMax = head.indexOf("g2g_max_ms"),
                capture = head.indexOf("capture_to_send_ms"), net = head.indexOf("video_net_ms"), size = head.indexOf("video_res"),
                fps = head.indexOf("video_rx_fps"), ping = head.indexOf("ping_ms");
        if (time < 0 || link < 0) return rows;
        for (String line : lines.subList(1, lines.size())) {
            List<String> c = AppLinkLog.cells(line);
            if (c.size() < head.size() || c.get(link).isBlank()) continue; // the row being written; an event's row
            LocalTime at;
            try {
                at = LocalTime.parse(c.get(time).trim());
            } catch (RuntimeException e) {
                continue;
            }
            rows.add(new Estimate(at, c.get(link).trim(), number(c, g2g), number(c, g2gMax), number(c, capture), number(c, net),
                    size < 0 ? "" : c.get(size).trim(), real(c, fps), number(c, ping)));
        }
        return rows;
    }

    private static int number(List<String> c, int i) {
        if (i < 0 || c.get(i).isBlank()) return -1;
        try {
            return (int) Math.round(Double.parseDouble(c.get(i).trim()));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static double real(List<String> c, int i) {
        if (i < 0 || c.get(i).isBlank()) return 0;
        try {
            return Double.parseDouble(c.get(i).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Every sample row of the link logs in that folder written since that moment (epoch ms; a few seconds' slack), oldest file first. */
    static List<Estimate> readEstimates(Path logsDir, long sinceMs) {
        List<Estimate> rows = new ArrayList<>();
        if (!Files.isDirectory(logsDir)) return rows;
        List<Path> files;
        try (Stream<Path> list = Files.list(logsDir)) {
            files = list.filter(p -> {
                String n = p.getFileName().toString();
                try {
                    return n.startsWith("link-") && n.endsWith(".csv") && Files.getLastModifiedTime(p).toMillis() >= sinceMs - 5000;
                } catch (IOException e) {
                    return false;
                }
            }).sorted().toList();
        } catch (IOException | RuntimeException e) {
            return rows;
        }
        for (Path f : files) {
            try {
                rows.addAll(estimates(Files.readAllLines(f, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException ignored) {
                // being written, or gone: the next read has it
            }
        }
        return rows;
    }

    static List<Estimate> between(List<Estimate> rows, LocalTime from, LocalTime to) {
        return rows.stream().filter(r -> AppLinkLog.msBetween(from, r.at()) >= 0 && AppLinkLog.msBetween(r.at(), to) >= 0).toList();
    }

    /**
     * The app's own figures of a stretch: its estimate as the OSD shows it - the link log's figure and the view's own
     * time from a picture handed over to painted (screenMs; the OSD adds it, and so does the Latency test to its
     * readings) - median and 90th percentile of the rows, the most of their highest single pictures; its two parts; the
     * picture's sizes as they came (share of the rows), its frames a second, the ping.
     */
    record AppFigures(int rows, int withFigure, Spread g2g, double maxMs, double captureMedian, double netMedian, String sizes, double fpsMean,
                      double pingMedian) {
        boolean any() {
            return withFigure > 0;
        }
    }

    static AppFigures appFigures(List<Estimate> rows, double screenMs) {
        double screen = Double.isNaN(screenMs) || screenMs < 0 ? 0 : screenMs;
        List<Double> g2g = new ArrayList<>(), capture = new ArrayList<>(), net = new ArrayList<>(), ping = new ArrayList<>();
        double max = Double.NaN, fps = 0;
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (Estimate r : rows) {
            if (r.g2gMs() >= 0) g2g.add(r.g2gMs() + screen);
            if (r.g2gMaxMs() >= 0) max = Double.isNaN(max) ? r.g2gMaxMs() + screen : Math.max(max, r.g2gMaxMs() + screen);
            if (r.captureMs() >= 0) capture.add((double) r.captureMs());
            if (r.netMs() >= 0) net.add((double) r.netMs());
            if (r.pingMs() >= 0) ping.add((double) r.pingMs());
            if (!r.size().isEmpty()) sizes.merge(r.size(), 1, Integer::sum);
            fps += r.fps();
        }
        Spread s = Spread.of(g2g);
        if (Double.isNaN(max)) max = s.max();
        int all = sizes.values().stream().mapToInt(Integer::intValue).sum();
        List<String> said = new ArrayList<>();
        sizes.forEach((size, n) -> said.add(size + (sizes.size() > 1 ? " " + Math.round(100.0 * n / all) + " %" : "")));
        return new AppFigures(rows.size(), g2g.size(), s, max, Spread.of(capture).median(), Spread.of(net).median(), String.join(", ", said),
                rows.isEmpty() ? 0 : fps / rows.size(), Spread.of(ping).median());
    }

    // ------------------------------------------------------------------ what the app said of the stream and its own test

    private static final Pattern STREAM = Pattern.compile("Video: (H\\.26[45]) (\\d+x\\d+)");

    /** The streams the app's log says began ("Video: H.265 1280x720"), in order, a repeat of the one before left out. */
    static List<String> streams(List<String> appLog) {
        List<String> out = new ArrayList<>();
        for (String line : appLog) {
            Matcher m = STREAM.matcher(line);
            if (m.find()) {
                String s = m.group(1) + " " + m.group(2);
                if (out.isEmpty() || !out.get(out.size() - 1).equals(s)) out.add(s);
            }
        }
        return out;
    }

    /** The codecs of those streams ("H.265", "H.264 then H.265"); "-" for none. */
    static String codecs(List<String> streams) {
        List<String> c = new ArrayList<>();
        for (String s : streams) {
            String codec = s.substring(0, s.indexOf(' '));
            if (c.isEmpty() || !c.get(c.size() - 1).equals(codec)) c.add(codec);
        }
        return c.isEmpty() ? "-" : String.join(" then ", c);
    }

    /** The app's own Latency test's last word in its log ("median 142 ms (min 120, max 190, 60 readings)"), or "-". */
    static String ownTestSaid(List<String> appLog) {
        String said = "-";
        for (String line : appLog) {
            int at = line.indexOf("Latency test, camera to screen");
            if (at >= 0) {
                int colon = line.indexOf(": ", at);
                said = colon < 0 ? line.substring(at) : line.substring(colon + 2).trim();
            }
        }
        return said;
    }

    private static final Pattern STILL = Pattern.compile("Still saved: (.+?\\.png)(?: and |\\.$)");

    /** The still's file the app says it saved ("Still saved: C:\...\still-....png and still-...-osd.png."), the last one; null for none. */
    static String stillSaved(List<String> appLog) {
        String file = null;
        for (String line : appLog) {
            Matcher m = STILL.matcher(line.trim());
            if (m.find()) file = m.group(1);
        }
        return file;
    }

    // ------------------------------------------------------------------ the camera check, the windows

    /**
     * The first cell's look at whether the camera sees the code: null - it does (a reading came); else the plain words
     * that end the run here, skipped - not a failure of the app: nothing was measured.
     *
     * @param still where the still of what the camera saw went; null: none could be saved
     */
    static String cameraCheck(int readings, long waitedMs, boolean picture, String still) {
        if (readings > 0) return null;
        return "THE CAMERA DOES NOT SEE THE LATENCY TEST'S CODE: no reading of it in " + Math.round(waitedMs / 1000.0) + " s"
                + (picture ? "" : ", and no picture came from the phone at all") + ". Nothing was measured, and this is no failure of the app - the rest "
                + "of the table is skipped. What the camera saw: " + (still == null ? "no still could be saved" : still) + ". To set up: " + SET_UP + ".";
    }

    /**
     * Where the code is among the windows: null - on the main screen, whole, nothing of the app's over it; else what is
     * wrong. Other programs' windows are not known to the bench: the hands step keeps the screen clear of them.
     *
     * @param code   the code on the screen (screen points); null: it could not be read off the app's own video area
     * @param others the app's other showing windows by title
     */
    static String placementProblem(Rectangle screen, Rectangle code, Map<String, Rectangle> others) {
        if (code == null) return "the Latency test's code could not be read off the app's own video area (painted as it is)";
        if (!screen.contains(code)) {
            return "the code is not wholly on the main screen (" + where(code) + ", the screen " + where(screen) + ")";
        }
        for (Map.Entry<String, Rectangle> e : others.entrySet()) {
            if (e.getValue().intersects(code)) return "the app's window \"" + e.getKey() + "\" lies over the code (" + where(e.getValue()) + ")";
        }
        return null;
    }

    static String where(Rectangle r) {
        return r.width + "x" + r.height + " at " + r.x + "," + r.y;
    }

    /**
     * Where a QR code is in a picture - the code itself, without its white border - found as a reader finds it; null
     * when there is none it can read. The three finder patterns' centres lie 3.5 modules in from the code's edges.
     */
    static Rectangle findCode(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        RGBLuminanceSource source = new RGBLuminanceSource(w, h, px);
        try {
            new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)));
            BitMatrix black = new HybridBinarizer(source).getBlackMatrix();
            DetectorResult d = new Detector(black).detect();
            int n = d.getBits().getWidth();
            ResultPoint[] p = d.getPoints(); // bottom left, top left, top right (then an alignment pattern)
            if (p.length < 3 || n <= 7) return null;
            double module = ResultPoint.distance(p[1], p[2]) / (n - 7);
            int x = (int) Math.round(p[1].getX() - 3.5 * module), y = (int) Math.round(p[1].getY() - 3.5 * module), side = (int) Math.round(n * module);
            return new Rectangle(x, y, side, side);
        } catch (ReaderException | RuntimeException e) { // none, or none that reads
            return null;
        }
    }

    // ------------------------------------------------------------------ the table

    static final String TABLE = "hw-g2g";
    static final List<String> COLUMNS = List.of("cell", "network", "picture", "codec asked", "recording", "STANAG", "spectator", "codec seen", "size seen",
            "ready ms", "measured s", "readings", "readings a second", "camera median ms", "camera p90 ms", "camera max ms", "camera min ms",
            "app median ms", "app p90 ms", "app max ms", "camera minus app ms", "app capture to send ms", "app network ms", "screen ms",
            "picture fps", "ping median ms", "spectator packets", "app's own test said", "phone errors", "verdict", "what");

    /** A row with the cell's own settings in it; the rest "-" until measured. */
    static HwLinkMatrix.Row row(Cell c) {
        return new HwLinkMatrix.Row(COLUMNS).put("cell", c.name()).put("network", c.net().name).put("picture", c.picture().name())
                .put("codec asked", c.codecWords()).put("recording", c.recording() ? "on" : "off").put("STANAG", c.stanag() ? "on" : "off")
                .put("spectator", c.spectator() ? "one watching" : "none");
    }

    /** The measured and the estimated figures into a row. */
    static void put(HwLinkMatrix.Row row, Spread camera, long measuredMs, AppFigures app, double screenMs) {
        double seconds = measuredMs / 1000.0;
        row.put("measured s", Math.round(seconds)).put("readings", camera.n()).put("readings a second", seconds <= 0 ? "-" : HwLinkMatrix.one(camera.n() / seconds))
                .put("camera median ms", ms(camera.median())).put("camera p90 ms", ms(camera.p90())).put("camera max ms", ms(camera.max()))
                .put("camera min ms", ms(camera.min())).put("app median ms", ms(app.g2g().median())).put("app p90 ms", ms(app.g2g().p90()))
                .put("app max ms", ms(app.maxMs())).put("camera minus app ms", ms(camera.median() - app.g2g().median()))
                .put("app capture to send ms", ms(app.captureMedian())).put("app network ms", ms(app.netMedian())).put("screen ms", HwLinkMatrix.one(screenMs))
                .put("picture fps", HwLinkMatrix.one(app.fpsMean())).put("ping median ms", ms(app.pingMedian()));
        if (!app.sizes().isEmpty()) row.put("size seen", app.sizes());
    }

    /** A cell's figures in plain words, for the film. */
    static String words(String cell, Spread camera, long measuredMs, AppFigures app, String streams) {
        StringBuilder s = new StringBuilder(cell).append(": ");
        if (camera.n() == 0) {
            s.append("NO READING of the code in ").append(Math.round(measuredMs / 1000.0)).append(" s (the camera did not read it at this picture)");
        } else {
            s.append(String.format(Locale.ROOT, "camera to screen %s ms in the middle, 9 in 10 readings at most %s ms, the most %s ms (%d readings in %d s)",
                    ms(camera.median()), ms(camera.p90()), ms(camera.max()), camera.n(), Math.round(measuredMs / 1000.0)));
        }
        if (app.any()) {
            s.append(String.format(Locale.ROOT, "; the app's own figure %s ms in the middle, 9 in 10 at most %s ms, the most %s ms", ms(app.g2g().median()),
                    ms(app.g2g().p90()), ms(app.maxMs())));
            if (camera.n() > 0) {
                double d = camera.median() - app.g2g().median();
                s.append(" - ").append(ms(Math.abs(d))).append(" ms ").append(d >= 0 ? "under" : "over").append(" what the camera measured");
            }
            s.append(String.format(Locale.ROOT, " (the phone's part %s ms, the network's %s ms)", ms(app.captureMedian()), ms(app.netMedian())));
        } else {
            s.append("; the app had no figure of its own (no FRAME_TIME from the phone app, or no picture)");
        }
        s.append(". The picture: ").append(streams.isEmpty() ? "?" : streams);
        if (!app.sizes().isEmpty()) s.append(" (the link log: ").append(app.sizes()).append(")");
        s.append(String.format(Locale.ROOT, ", %.1f frames a second; ping %s ms.", app.fpsMean(), ms(app.pingMedian())));
        return s.toString();
    }
}
