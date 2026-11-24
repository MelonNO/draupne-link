package de.droiddrone.bench.hw;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The app's own figures of its link, read from its link log ({@code logs\link-*.csv}, ten rows a second while a link is
 * up, on the disk within a second or two): the link's state, the ping, the gaps, the picture's frames a second, and
 * what it heard of the flight controller. Read only; for the bad-network box (R8) - a cell's rows by the time of day
 * of its first column, which is this PC's clock as the scenario's own.
 */
public final class AppLinkLog {
    private AppLinkLog() {
    }

    /** One sample row (the rows that carry an event only are left out). videoFps: the picture's frames received a second, 0 for none. */
    public record Row(LocalTime at, String link, int pingMs, long rcMaxGapMs, long telemetryMaxGapMs, String rxloss, String failsafe, String armed,
               double videoFps) {
        boolean ready() {
            return "READY".equals(link);
        }

        boolean picture() {
            return videoFps > 0;
        }
    }

    /** Every sample row of the link logs in that folder written since that moment (epoch ms; a few seconds' slack), oldest file first. */
    public static List<Row> read(Path logsDir, long sinceMs) {
        List<Row> rows = new ArrayList<>();
        if (!Files.isDirectory(logsDir)) return rows;
        List<Path> files;
        try (Stream<Path> list = Files.list(logsDir)) {
            files = list.filter(p -> {
                String n = p.getFileName().toString();
                if (!n.startsWith("link-") || !n.endsWith(".csv")) return false;
                try {
                    return Files.getLastModifiedTime(p).toMillis() >= sinceMs - 5000;
                } catch (IOException e) {
                    return false;
                }
            }).sorted().toList();
        } catch (IOException | RuntimeException e) {
            return rows;
        }
        for (Path f : files) {
            try {
                rows.addAll(parse(Files.readAllLines(f, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException ignored) {
                // being written, or gone (the app keeps its newest 20): the next read has it
            }
        }
        return rows;
    }

    /** A link log's lines, the header first: its sample rows. A row cut short (the one being written) is left out. */
    static List<Row> parse(List<String> lines) {
        List<Row> rows = new ArrayList<>();
        if (lines.isEmpty()) return rows;
        List<String> head = Arrays.asList(lines.get(0).trim().split(",", -1));
        int time = head.indexOf("time"), link = head.indexOf("link"), ping = head.indexOf("ping_ms"), rcGap = head.indexOf("rc_max_gap_ms"),
                telemetryGap = head.indexOf("telemetry_max_gap_ms"), rxloss = head.indexOf("rxloss"), failsafe = head.indexOf("failsafe"),
                armed = head.indexOf("armed"), fps = head.indexOf("video_rx_fps"), shown = head.indexOf("video_fps");
        if (time < 0 || link < 0) return rows;
        for (String line : lines.subList(1, lines.size())) {
            List<String> c = cells(line);
            if (c.size() < head.size()) continue;
            String state = c.get(link).trim();
            if (state.isEmpty()) continue; // an event's row
            LocalTime at;
            try {
                at = LocalTime.parse(c.get(time).trim());
            } catch (RuntimeException e) {
                continue;
            }
            String video = fps >= 0 && !c.get(fps).isBlank() ? c.get(fps) : shown >= 0 ? c.get(shown) : "";
            rows.add(new Row(at, state, (int) number(c, ping, -1), number(c, rcGap, -1), number(c, telemetryGap, -1), text(c, rxloss), text(c, failsafe),
                    text(c, armed), real(video)));
        }
        return rows;
    }

    /** A CSV line's cells, a quoted one with its commas and doubled quotes as the link log writes them. */
    static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        out.add(cell.toString());
        return out;
    }

    private static long number(List<String> c, int i, long none) {
        if (i < 0) return none;
        try {
            return Math.round(Double.parseDouble(c.get(i).trim()));
        } catch (RuntimeException e) {
            return none;
        }
    }

    private static String text(List<String> c, int i) {
        return i < 0 ? "" : c.get(i).trim();
    }

    private static double real(String s) {
        try {
            return s.isBlank() ? 0 : Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** From one time of day to another in ms, the short way round the clock (a run across midnight). */
    static long msBetween(LocalTime from, LocalTime to) {
        long d = (to.toNanoOfDay() - from.toNanoOfDay()) / 1_000_000;
        if (d < -43_200_000L) d += 86_400_000L;
        else if (d > 43_200_000L) d -= 86_400_000L;
        return d;
    }

    /** The rows from one moment to another, both included. */
    static List<Row> between(List<Row> rows, LocalTime from, LocalTime to) {
        return rows.stream().filter(r -> msBetween(from, r.at()) >= 0 && msBetween(r.at(), to) >= 0).toList();
    }

    /** ms after that moment of the first row at or after it that is so; -1: none (yet). */
    static long first(List<Row> rows, LocalTime from, Predicate<Row> ok) {
        for (Row r : rows) {
            long d = msBetween(from, r.at());
            if (d >= 0 && ok.test(r)) return d;
        }
        return -1;
    }

    /**
     * The app's own figures of a stretch of its link.
     *
     * @param readyPercent the share of rows with the link READY
     * @param leftReady    how often it went from READY to anything else
     * @param pictureRows  rows with a picture coming (frames received that second)
     * @param appRxloss    rows in which the app had the flight controller's word RXLOSS (as the phone passed it on)
     * @param appArmed     rows in which the app had the flight controller's word ARMED
     */
    record Figures(int rows, int readyPercent, int leftReady, int pingMedian, int pingMax, long telemetryGapMax, double videoFpsMean,
                   int pictureRows, int appRxloss, int appFailsafe, int appArmed) {
        int picturePercent() {
            return rows == 0 ? 0 : 100 * pictureRows / rows;
        }

        String words() {
            return rows == 0 ? "no rows in the app's link log"
                    : String.format(java.util.Locale.ROOT, "LINK READY in %d %% of %d rows, left %d time(s); ping %d ms median, %d ms at most; the longest gap "
                            + "in the phone's telemetry %d ms; the picture in %d %% of the rows, %.1f frames a second in the mean; the app had the FC's "
                            + "RXLOSS in %d row(s), FAILSAFE in %d, ARMED in %d", readyPercent, rows, leftReady, pingMedian, pingMax, telemetryGapMax,
                    picturePercent(), videoFpsMean, appRxloss, appFailsafe, appArmed);
        }
    }

    static Figures figures(List<Row> rows) {
        int n = rows.size(), ready = 0, left = 0, picture = 0, rxloss = 0, failsafe = 0, armed = 0;
        long telemetry = -1;
        double fps = 0;
        List<Integer> pings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            if (r.ready()) ready++;
            if (i > 0 && rows.get(i - 1).ready() && !r.ready()) left++;
            if (r.pingMs() >= 0) pings.add(r.pingMs());
            telemetry = Math.max(telemetry, r.telemetryMaxGapMs());
            fps += r.videoFps();
            if (r.picture()) picture++;
            if (r.rxloss().equals("1")) rxloss++;
            if (r.failsafe().equals("1")) failsafe++;
            if (r.armed().equals("1")) armed++;
        }
        pings.sort(null);
        int median = pings.isEmpty() ? -1 : pings.get(pings.size() / 2), most = pings.isEmpty() ? -1 : pings.get(pings.size() - 1);
        return new Figures(n, n == 0 ? 0 : 100 * ready / n, left, median, most, telemetry, n == 0 ? 0 : fps / n, picture, rxloss, failsafe, armed);
    }
}
