package de.droiddrone.pc.flights;

import de.droiddrone.pc.map.FlightTrack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * A flight as one HTML file to keep or hand on (the debrief): when and which drone, the figures
 * ({@link FlightSummary}), the track's picture, altitude, battery and ping over the flight, the marks
 * ({@link FlightMarks}) and the pilot's note. The file stands alone - the picture is inside it, the graphs are drawn
 * in it, and it has no script, no link and nothing fetched from anywhere, so it opens the same on a PC without a
 * network and years later.
 * <p>
 * What goes in is what a reader may see: of the link log only the ping, and where the link was down, as a graph. Its
 * addresses, its events and the files' paths (they carry the Windows user's name) stay out; a drone "name" that is an
 * address (a log from before phones had names) is left out too.
 */
public final class FlightReport {
    private static final int W = 800, H = 170, LEFT = 62, RIGHT = 790, TOP = 12, BOTTOM = 140;
    /** A graph's line is cut where its figures stop for this long. */
    private static final long GAP_MS = 5000;

    private FlightReport() {
    }

    /** "flight-2026-11-08_1405-report.html": a name for the file, from the flight's start. */
    public static String fileName(FlightLibrary.Flight f) {
        return "flight-" + f.title().replace(":", "").replace(" ", "_") + "-report.html";
    }

    /**
     * Writes the report whole, then moves it into place (an existing file is replaced). Not on the Swing thread.
     * @param summary      the flight's figures; null: worked out here
     * @param marks        the flight's marks ({@link FlightData#marks}); empty or null: none
     * @param notes        the flight's notes, or null
     * @param trackPicture the map with the track drawn, as the caller painted it; null: no picture
     */
    public static Path write(Path target, FlightData d, FlightSummary summary, List<FlightMarks.Mark> marks, FlightNotes notes,
                             BufferedImage trackPicture) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(tmp, html(d, summary, marks, notes, trackPicture), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    /** The report's text ({@link #write} for its parameters). */
    public static String html(FlightData d, FlightSummary summary, List<FlightMarks.Mark> marks, FlightNotes notes, BufferedImage trackPicture)
            throws IOException {
        FlightLibrary.Flight f = d.flight;
        if (summary == null) summary = FlightSummary.of(d);
        String drone = isName(f.drone()) ? f.drone().trim() : null;
        String title = "Flight " + f.title() + (drone == null ? "" : " - " + drone);
        StringBuilder sb = new StringBuilder(64_000);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(esc(title)).append("</title>\n<style>\n").append(CSS).append("</style>\n</head>\n<body>\n");
        sb.append("<header>\n<h1>FLIGHT ").append(esc(f.title())).append("</h1>\n");
        if (drone != null) sb.append("<p class=\"drone\">").append(esc(drone)).append("</p>\n");
        sb.append("</header>\n");

        sb.append("<section>\n<h2>SUMMARY</h2>\n<dl class=\"figures\">\n");
        for (FlightSummary.Figure fig : summary.figures()) {
            sb.append("<div").append(fig.known() ? "" : " class=\"unknown\"").append("><dt>").append(esc(fig.label())).append("</dt><dd>")
                    .append(esc(fig.value())).append("</dd></div>\n");
        }
        sb.append("</dl>\n");
        if (summary.maxAltKind != null) {
            sb.append("<p class=\"small\">").append(esc(summary.maxAltLabel())).append(": ").append(esc(summary.maxAltKind.meaning)).append(".</p>\n");
        }
        sb.append("</section>\n");

        if (trackPicture != null) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(trackPicture, "png", png)) throw new IOException("The track's picture can't be written as PNG");
            sb.append("<section>\n<h2>TRACK</h2>\n<img alt=\"The track on the map\" width=\"").append(trackPicture.getWidth()).append("\" height=\"")
                    .append(trackPicture.getHeight()).append("\" src=\"data:image/png;base64,")
                    .append(Base64.getEncoder().encodeToString(png.toByteArray())).append("\">\n</section>\n");
        }

        long start = d.startMs(), length = Math.max(1, d.endMs() - d.startMs());
        graphs(sb, d, summary, start, length);

        sb.append("<section>\n<h2>MARKS</h2>\n");
        if (marks == null || marks.isEmpty()) {
            sb.append("<p class=\"none\">NO MARKS</p>\n");
        } else {
            sb.append("<table>\n");
            for (FlightMarks.Mark m : marks) {
                sb.append("<tr><td class=\"time\">").append(esc(m.timeText())).append("</td><td class=\"kind\">").append(esc(m.kind().title))
                        .append("</td><td>").append(esc(m.label())).append("</td></tr>\n");
            }
            sb.append("</table>\n");
        }
        sb.append("</section>\n");

        String note = notes == null ? "" : notes.note();
        if (!note.isEmpty()) sb.append("<section>\n<h2>NOTE</h2>\n<p class=\"note\">").append(esc(note)).append("</p>\n</section>\n");

        sb.append("<footer>DRAUPNE Link ").append(esc(de.droiddrone.pc.AppInfo.version())).append("</footer>\n</body>\n</html>\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ the graphs

    /** One figure at one moment (ms from the flight's start); NaN: none here, and the line doesn't go on through. */
    private record At(long ms, double value) {}

    private static void graphs(StringBuilder sb, FlightData d, FlightSummary summary, long start, long length) {
        // altitude: the recording's (the flight controller's) where there is one, else the track's GPS altitude - as the summary's
        List<At> alt = new ArrayList<>();
        if (summary.maxAltKind == FlightSummary.AltitudeKind.ABOVE_TAKE_OFF) {
            for (FlightData.Sample s : d.telemetry) if (!Double.isNaN(s.altM())) alt.add(new At(s.timeMs() - start, s.altM()));
        } else {
            for (FlightTrack.Point p : d.track) alt.add(new At(p.timeMs() - start, p.altM()));
        }
        String altTitle = summary.maxAltKind == null ? "ALTITUDE" : summary.maxAltKind == FlightSummary.AltitudeKind.ABOVE_TAKE_OFF
                ? "HEIGHT ABOVE TAKE-OFF" : summary.maxAltKind == FlightSummary.AltitudeKind.PHONE_GPS ? "PHONE GPS ALTITUDE" : "GPS ALTITUDE";
        graph(sb, altTitle, "alt", alt, List.of(), length, "%.0f M", summary.maxAltKind == FlightSummary.AltitudeKind.ABOVE_TAKE_OFF);

        // the battery: a cell's voltage; the pack's when the cells weren't known
        List<At> cell = new ArrayList<>(), pack = new ArrayList<>();
        for (FlightData.Sample s : d.telemetry) {
            if (s.cellVolts() > 0) cell.add(new At(s.timeMs() - start, s.cellVolts()));
            if (s.volts() > 0) pack.add(new At(s.timeMs() - start, s.volts()));
        }
        if (cell.isEmpty() && !pack.isEmpty()) graph(sb, "PACK VOLTAGE", "batt", pack, List.of(), length, "%.1f V", false);
        else graph(sb, "CELL VOLTAGE", "batt", cell, List.of(), length, "%.2f V", false);

        // ping, and where the link was down: the link log's, else the recording's ping
        List<At> ping = new ArrayList<>();
        List<long[]> down = new ArrayList<>();
        long downFrom = -1;
        for (FlightData.LinkSample s : d.link) {
            long ms = s.timeMs() - start;
            if (ms < 0 || ms > length) continue;
            if (s.down()) {
                if (downFrom < 0) downFrom = ms;
                continue;
            }
            if (downFrom >= 0) {
                down.add(new long[]{downFrom, ms});
                ping.add(new At(ms, Double.NaN)); // no ping while it was down: the line isn't drawn across
            }
            downFrom = -1;
            if (s.pingMs() >= 0) ping.add(new At(ms, s.pingMs()));
        }
        if (downFrom >= 0) down.add(new long[]{downFrom, length});
        if (ping.isEmpty() && down.isEmpty()) {
            for (FlightData.Sample s : d.telemetry) if (s.pingMs() >= 0) ping.add(new At(s.timeMs() - start, s.pingMs()));
        }
        graph(sb, "PING", "ping", ping, down, length, "%.0f MS", true);
    }

    /**
     * One graph: the figure over the flight's length as a line (its highest and lowest of each step across, so a
     * short dip or peak stays), the scale's ends at the left, the time under it.
     * @param down     spans (ms from, to) where the link was down, drawn behind the line
     * @param fromZero the scale starts at 0 (or lower); else at the lowest figure
     */
    private static void graph(StringBuilder sb, String title, String css, List<At> points, List<long[]> down, long length, String unit, boolean fromZero) {
        sb.append("<section>\n<h2>").append(esc(title)).append("</h2>\n");
        double lo = Double.NaN, hi = Double.NaN;
        for (At p : points) {
            if (Double.isNaN(p.value())) continue;
            if (!(p.value() >= lo)) lo = p.value();
            if (!(p.value() <= hi)) hi = p.value();
        }
        if (Double.isNaN(lo) && down.isEmpty()) {
            sb.append("<p class=\"none\">NO DATA</p>\n</section>\n");
            return;
        }
        if (Double.isNaN(lo)) lo = hi = 0;
        if (fromZero) lo = Math.min(0, lo);
        if (hi - lo < 1e-9) hi = lo + 1; // a flat line: drawn at the bottom of a scale of one
        sb.append("<svg viewBox=\"0 0 ").append(W).append(' ').append(H).append("\" role=\"img\" aria-label=\"").append(esc(title)).append(" over the flight\">\n");
        sb.append("<rect class=\"plot\" x=\"").append(LEFT).append("\" y=\"").append(TOP).append("\" width=\"").append(RIGHT - LEFT)
                .append("\" height=\"").append(BOTTOM - TOP).append("\"/>\n");
        for (long[] span : down) {
            double x0 = x(span[0], length), x1 = Math.max(x0 + 1, x(span[1], length));
            sb.append("<rect class=\"down\" x=\"").append(num(x0)).append("\" y=\"").append(TOP).append("\" width=\"").append(num(x1 - x0))
                    .append("\" height=\"").append(BOTTOM - TOP).append("\"/>\n");
        }
        for (int i = 1; i < 4; i++) {
            int y = TOP + (BOTTOM - TOP) * i / 4;
            sb.append("<line class=\"grid\" x1=\"").append(LEFT).append("\" y1=\"").append(y).append("\" x2=\"").append(RIGHT).append("\" y2=\"").append(y).append("\"/>\n");
        }
        String path = path(points, length, lo, hi);
        if (!path.isEmpty()) {
            sb.append("<path class=\"line ").append(css).append("\" d=\"").append(path).append("\"/>\n");
            sb.append("<text class=\"axis\" text-anchor=\"end\" x=\"").append(LEFT - 6).append("\" y=\"").append(TOP + 9).append("\">")
                    .append(esc(String.format(Locale.ROOT, unit, hi))).append("</text>\n");
            sb.append("<text class=\"axis\" text-anchor=\"end\" x=\"").append(LEFT - 6).append("\" y=\"").append(BOTTOM).append("\">")
                    .append(esc(String.format(Locale.ROOT, unit, lo))).append("</text>\n");
        }
        sb.append("<text class=\"axis\" x=\"").append(LEFT).append("\" y=\"").append(BOTTOM + 18).append("\">0:00</text>\n");
        sb.append("<text class=\"axis\" text-anchor=\"end\" x=\"").append(RIGHT).append("\" y=\"").append(BOTTOM + 18).append("\">")
                .append(esc(FlightLibrary.durationText(length))).append("</text>\n");
        if (!down.isEmpty()) {
            sb.append("<text class=\"axis downText\" text-anchor=\"middle\" x=\"").append((LEFT + RIGHT) / 2).append("\" y=\"").append(BOTTOM + 18)
                    .append("\">RED: LINK DOWN</text>\n");
        }
        sb.append("</svg>\n</section>\n");
    }

    /**
     * The line as an SVG path. Each pixel across holds the lowest and the highest figure that fell on it, in the order
     * they came; the line is cut where the figures stop for {@link #GAP_MS}, and at a NaN.
     */
    private static String path(List<At> points, long length, double lo, double hi) {
        StringBuilder sb = new StringBuilder();
        int column = -1;
        At min = null, max = null, last = null;
        boolean pen = false;
        for (At p : points) {
            if (p.ms() < 0 || p.ms() > length) continue; // outside the flight (a link log runs from CONNECT)
            if (Double.isNaN(p.value())) { // the line ends here
                if (min != null) column(sb, pen, min, max, length, lo, hi);
                min = max = null;
                pen = false;
                continue;
            }
            int c = (int) Math.floor(x(p.ms(), length));
            boolean gap = last != null && p.ms() - last.ms() > GAP_MS;
            if (min != null && (c != column || gap)) {
                pen = column(sb, pen, min, max, length, lo, hi);
                min = max = null;
            }
            if (gap) pen = false;
            column = c;
            if (min == null || p.value() < min.value()) min = p;
            if (max == null || p.value() > max.value()) max = p;
            last = p;
        }
        if (min != null) column(sb, pen, min, max, length, lo, hi);
        return sb.toString().trim();
    }

    /** A pixel's lowest and highest, the earlier first. */
    private static boolean column(StringBuilder sb, boolean pen, At min, At max, long length, double lo, double hi) {
        At first = min.ms() <= max.ms() ? min : max, second = first == min ? max : min;
        point(sb, pen, first, length, lo, hi);
        if (second != first) point(sb, true, second, length, lo, hi);
        return true;
    }

    private static void point(StringBuilder sb, boolean pen, At p, long length, double lo, double hi) {
        double y = BOTTOM - (BOTTOM - TOP) * (p.value() - lo) / (hi - lo);
        sb.append(pen ? "L" : "M").append(num(x(p.ms(), length))).append(',').append(num(y)).append(pen ? " " : " h0 "); // h0: a figure alone is a dot
    }

    private static double x(long ms, long length) {
        return LEFT + (RIGHT - LEFT) * Math.max(0, Math.min(1, ms / (double) length));
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ------------------------------------------------------------------ text

    /** Text for inside an element or an attribute: nothing in it is markup any more. */
    static String esc(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                case '\n', '\t' -> sb.append(c);
                default -> {
                    if (c >= 0x20 && c != 0x7f) sb.append(c); // other control characters: left out
                }
            }
        }
        return sb.toString();
    }

    /** A drone's name to print: there, and not an address (four numbers with dots, or something ending in a port). */
    static boolean isName(String drone) {
        if (drone == null || drone.isBlank()) return false;
        String s = drone.trim();
        return !s.matches(".*\\d{1,3}(\\.\\d{1,3}){3}.*") && !s.matches(".*:\\d+\\)?");
    }

    private static final String CSS = """
            :root { color-scheme: dark; }
            * { box-sizing: border-box; }
            body { margin: 0 auto; padding: 24px 20px 40px; max-width: 880px; background: #000; color: #e6e6e0;
              font: 15px/1.45 Bahnschrift, "Segoe UI", system-ui, sans-serif; }
            h1 { margin: 0; font-size: 26px; letter-spacing: .06em; }
            h2 { margin: 0 0 10px; font-size: 12px; letter-spacing: .12em; color: #8c8c7e; }
            header { border-left: 4px solid #e31b23; padding-left: 12px; margin-bottom: 18px; }
            .drone { margin: 2px 0 0; color: #8c8c7e; letter-spacing: .08em; }
            section { background: #0a0a0a; border: 1px solid #2e2e2a; padding: 14px 16px; margin-bottom: 12px; }
            .figures { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px 16px; margin: 0; }
            dt { font-size: 11px; letter-spacing: .1em; color: #8c8c7e; }
            dd { margin: 0; font: bold 19px Consolas, "Cascadia Mono", monospace; }
            .unknown dd { color: #8c8c7e; }
            .small { margin: 12px 0 0; font-size: 12px; color: #8c8c7e; }
            img { display: block; max-width: 100%; height: auto; border: 1px solid #2e2e2a; }
            svg { display: block; width: 100%; height: auto; }
            .plot { fill: #101010; }
            .grid { stroke: #2e2e2a; stroke-width: 1; }
            .down { fill: #e31b23; opacity: .55; }
            .line { fill: none; stroke-width: 1.6; stroke-linejoin: round; stroke-linecap: round; }
            .alt { stroke: #d8d8d0; }
            .batt { stroke: #009639; }
            .ping { stroke: #fcd405; }
            .axis { fill: #8c8c7e; font: 11px Consolas, "Cascadia Mono", monospace; }
            .downText { fill: #ff5a4e; }
            table { border-collapse: collapse; width: 100%; }
            td { padding: 5px 12px 5px 0; border-top: 1px solid #1d1d1a; vertical-align: top; }
            tr:first-child td { border-top: 0; }
            .time { width: 70px; font-family: Consolas, "Cascadia Mono", monospace; }
            .kind { width: 110px; font-size: 11px; letter-spacing: .1em; color: #8c8c7e; }
            .note { margin: 0; white-space: pre-wrap; }
            .none { margin: 0; font-size: 12px; letter-spacing: .1em; color: #8c8c7e; }
            footer { margin-top: 18px; font-size: 11px; letter-spacing: .1em; color: #8c8c7e; }
            @media print { body { background: #fff; color: #000; } section { background: #fff; } }
            """;
}
