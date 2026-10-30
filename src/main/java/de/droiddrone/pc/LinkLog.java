package de.droiddrone.pc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A timeline of one connection, 10 rows a second, to find what is behind a failsafe: was the PC sending sticks
 * (longest gap between RC frames), did the phone answer (ping, longest gap between telemetry packets), did the
 * flight controller talk (age of its last data) and what Betaflight said (RXLOSS / FAILSAFE). One CSV file per
 * connection in %APPDATA%\DroidDronePC\logs, newest {@value #KEEP} kept.
 */
public final class LinkLog implements AutoCloseable {
    static final int KEEP = 20;
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    static final String HEADER = "time,link,ping_ms,rc_frames,rc_max_gap_ms,telemetry_packets,telemetry_max_gap_ms,"
            + "fc_data_age_ms,rxloss,failsafe,armed,blockers,radio,rc_output,video_fps,throttle,channels,"
            + "ping_max_ms,pong_age_ms,path,event";

    private final Path file;
    private final BufferedWriter out;
    private long lastRcFrames;
    private long lastTelemetry;
    private long lastFlush;

    private LinkLog(Path file) throws IOException {
        this.file = file;
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write(HEADER);
        out.newLine();
    }

    /** A new log in dir (created if needed); older logs beyond {@value #KEEP} are deleted. */
    public static LinkLog open(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> old = files.filter(f -> f.getFileName().toString().matches("link-.*\\.csv"))
                    .sorted(Comparator.comparing(Path::toString).reversed()).skip(KEEP - 1).toList();
            for (Path f : old) Files.deleteIfExists(f);
        }
        return new LinkLog(dir.resolve("link-" + LocalDateTime.now().format(FILE) + ".csv"));
    }

    public Path file() {
        return file;
    }

    /** One row: the link as it is now, and what happened since the previous row. */
    /**
     * @param channels the last RC frame the PC built (AETR + AUX, 1000-2000), or null without a radio
     * @param path     Tailscale's way to the phone ("direct", "relay fra", ...), or "" when unknown
     */
    public synchronized void sample(ControlLink link, boolean radio, boolean rcOutput, int videoFps, short[] channels,
                                    String path) throws IOException {
        long now = System.currentTimeMillis();
        long rc = link.getRcFramesSent();
        long telemetry = link.getTelemetryPacketsReceived();
        FcTelemetry t = link.getTelemetry();
        FcTelemetry.Status s = t.getStatus();
        boolean fresh = s != null && now - s.receivedAt() < 1500;
        long fcAge = t.msSinceFcData(now);
        String blockers = fresh ? String.join(" ", s.blockers().stream().map(FcTelemetry.Blocker::osdName).toList()) : "";
        row(link.getState().name(), link.getPing(), rc - lastRcFrames, link.takeMaxRcGapMs(), telemetry - lastTelemetry,
                link.takeMaxTelemetryGapMs(), fcAge == Long.MAX_VALUE ? -1 : fcAge,
                fresh ? flag(blockers.contains("RXLOSS")) : "", fresh ? flag(blockers.contains("FAILSAFE")) : "",
                fresh ? flag(s.armed()) : "", blockers, flag(radio), flag(rcOutput), videoFps,
                channels == null || channels.length <= PcConfig.THROTTLE_INDEX ? "" : channels[PcConfig.THROTTLE_INDEX],
                channels == null ? "" : java.util.Arrays.toString(channels).replace(",", "").replace("[", "").replace("]", ""),
                link.takeMaxPingMs(), link.msSinceLastPong(), path == null ? "" : path, "");
        lastRcFrames = rc;
        lastTelemetry = telemetry;
        if (now - lastFlush >= 1000) {
            out.flush();
            lastFlush = now;
        }
    }

    /** A message from the app's log, in the timeline. */
    public synchronized void event(String text) throws IOException {
        row("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", text);
    }

    private void row(Object... values) throws IOException {
        StringBuilder sb = new StringBuilder(LocalTime.now().format(TIME));
        for (Object v : values) {
            String s = String.valueOf(v);
            sb.append(',').append(s.contains(",") || s.contains("\"") ? '"' + s.replace("\"", "\"\"") + '"' : s);
        }
        out.write(sb.toString());
        out.newLine();
    }

    private static String flag(boolean b) {
        return b ? "1" : "0";
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
