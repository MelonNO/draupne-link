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
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A timeline of one connection, 10 rows a second, to find what is behind a failsafe: was the PC sending sticks
 * (longest gap between RC frames), did the phone answer (ping, longest gap between telemetry packets), did the
 * flight controller talk (age of its last data) and what Betaflight said (RXLOSS / FAILSAFE). One CSV file per
 * connection in %APPDATA%\DraupneLink\logs: the newest {@value #KEEP} kept, and those of kept flights as long as the
 * flight is - sorted out when a connection's log is closed, not when it is opened.
 */
public final class LinkLog implements AutoCloseable {
    static final int KEEP = 20;
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    static final String HEADER = "time,link,ping_ms,rc_frames,rc_max_gap_ms,telemetry_packets,telemetry_max_gap_ms,"
            + "fc_data_age_ms,rxloss,failsafe,armed,blockers,radio,rc_output,video_fps,throttle,channels,"
            + "ping_max_ms,pong_age_ms,path,phone_rc_in_gap_ms,phone_rc_out_gap_ms,phone_write_max_ms,phone_write_timeouts,"
            + "phone_skipped,video_rx_fps,video_mbps,video_damaged,video_lost,camera_fps,phone_video_mbps,bitrate_step,"
            + "decode_ms,convert_ms,draw_skipped,bunched,video_res,key_requests,intra_refresh,robust,recovered,"
            + "lat,lon,gps_alt_m,gps_sats,phone_lat,phone_lon,phone_acc_m,phone_age_s,"
            // the phone's own state (record 0x40F4, phone app pc.19 on): age of its last stick write to the FC, how
            // long that frame took from arriving to the USB write, its sequence number, the flags as sent (bit 0
            // channel map known, 1 USB permission pending, 2 denied, 3 camera open); pc_to_fc_ms = half the ping +
            // phone_rc_in_to_out_ms, the stick's way from this PC to the flight controller; glfw_poll_max_ms = the
            // slowest GLFW event pump on the RC thread (a USB plug event re-reads the devices and can hold a tick up)
            + "phone_rc_write_age_ms,phone_rc_in_to_out_ms,phone_rc_seq,phone_flags,pc_to_fc_ms,glfw_poll_max_ms,"
            // record 0x40F6 (phone app pc.31): the worst lateness of the sticks over the link's usual in the last
            // second, and how many frames the phone dropped as too late (rc.maxLagMs) this session
            + "stick_lag_max_ms,sticks_dropped_late,"
            // 1.0.67 (phone app pc.53, FRAME_TIME): the picture's delay from the camera to this screen as estimated for
            // the last picture shown (video.GlassToGlass, evened out) with two of its parts - the phone's, capture to
            // first packet, and the network's - and the highest single picture's figure of the last second; blank
            // without a figure (an older phone app, no picture, watching)
            + "capture_to_send_ms,video_net_ms,g2g_ms,g2g_max_ms,event";
    /** A phone state older than this is not shown: the phone has stopped reporting. */
    static final long PHONE_STATE_FRESH_MS = 2500;

    private final Path file;
    private final BufferedWriter out;
    private long lastRcFrames;
    private long lastTelemetry;
    private long lastFlush;
    private long lastDamaged;
    private long lastLost;
    private long lastDrawSkipped;
    private long lastBunched;
    private long lastKeyRequests;
    private long lastRecovered;

    private LinkLog(Path file) throws IOException {
        this.file = file;
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write(HEADER);
        out.newLine();
    }

    /** A new log in dir (created if needed); older logs beyond {@value #KEEP} are deleted. */
    public static LinkLog open(Path dir) throws IOException {
        Files.createDirectories(dir);
        sortOut(dir, null, f -> false);
        return new LinkLog(dir.resolve("link-" + LocalDateTime.now().format(FILE) + ".csv"));
    }

    /**
     * A new log in dir (created if needed). A log that belongs to a kept flight - PREVIOUS FLIGHTS plays its link with
     * the video and the track - stays as long as the flight does: it is neither deleted nor counted among the newest
     * {@value #KEEP}; the logs of connections that kept nothing go by that rule as before. Nothing is sorted out here
     * (CONNECT is where every millisecond counts): the older logs are when this one is closed, see {@link #close}.
     * @param kept whether an older log is a kept flight's. It may read the flights' files (FlightLibrary.keptLinkLog):
     *             it is asked on a thread of its own after this log is closed, never on the caller's (CONNECT and
     *             DISCONNECT are on the Swing thread), and only when there are more logs than the rule keeps anyway
     */
    public static LinkLog open(Path dir, Predicate<Path> kept) throws IOException {
        Files.createDirectories(dir);
        LinkLog log = new LinkLog(dir.resolve("link-" + LocalDateTime.now().format(FILE) + ".csv"));
        log.kept = kept;
        return log;
    }

    /**
     * The older logs sorted out: newest first, the kept flights' passed over, the rest counted with the new one -
     * those beyond {@value #KEEP} deleted (one that can't go now, open in another program, is tried at the next
     * connection).
     * @param current the new log when it is in the folder already, else null
     */
    static void sortOut(Path dir, Path current, Predicate<Path> kept) throws IOException {
        List<Path> older;
        try (Stream<Path> files = Files.list(dir)) {
            older = files.filter(f -> f.getFileName().toString().matches("link-.*\\.csv") && !f.equals(current))
                    .sorted(Comparator.comparing(Path::toString).reversed()).toList();
        }
        if (older.size() < KEEP) return; // not more than the rule keeps: nobody is asked
        int counted = 1; // the new one
        for (Path f : older) {
            if (kept.test(f)) continue;
            if (++counted <= KEEP) continue;
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignored) {
            }
        }
    }

    /** Whether an older log is a kept flight's ({@link #open(Path, Predicate)}); null: opened without anyone to ask. */
    private Predicate<Path> kept;
    /** The thread sorting out the older logs, started by {@link #close}; null before, and without a predicate. */
    private volatile Thread sorting;

    /**
     * The connection is over: the older logs are sorted out on a thread of their own at the lowest priority (it may
     * read every kept flight's files), this log counted as the new one. Once: from the close() that closed the file.
     */
    private void sortSoon() {
        Predicate<Path> asked = kept;
        if (asked == null) return;
        Path dir = file.toAbsolutePath().getParent();
        Thread t = new Thread(() -> {
            try {
                sortOut(dir, file, asked);
            } catch (IOException | RuntimeException e) {
                // the folder or the flights can't be read: nothing deleted, the next connection tries again
            }
        }, "linkLogKeep");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        sorting = t;
        t.start();
    }

    /**
     * Waits until the older logs are sorted out, after {@link #close} (at once before it): tests look at the folder
     * then, and the probe's process ends right after its log.
     */
    void awaitSorted() throws InterruptedException {
        Thread t = sorting;
        if (t != null) t.join();
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
        sample(link, radio, rcOutput, videoFps, channels, path, null);
    }

    /**
     * @param video the video's figures, or null without video: frames received a second, Mbit/s received (what the
     *              phone's encoder sends, after the bitrate control), and frames damaged / lost since the last row
     */
    public synchronized void sample(ControlLink link, boolean radio, boolean rcOutput, int videoFps, short[] channels,
                                    String path, de.droiddrone.pc.video.VideoPipeline.Stats video) throws IOException {
        sample(link, radio, rcOutput, videoFps, channels, path, video, -1);
    }

    /** @param glfwPollMaxMs the slowest GLFW event pump lately ({@link JoystickService#getPollMaxMs}), -1 = not measured */
    public synchronized void sample(ControlLink link, boolean radio, boolean rcOutput, int videoFps, short[] channels,
                                    String path, de.droiddrone.pc.video.VideoPipeline.Stats video, long glfwPollMaxMs) throws IOException {
        long now = MonoClock.ms();
        FcTelemetry.PhoneState ps = link.getTelemetry().getPhoneState();
        if (ps != null && now - ps.receivedAt() > PHONE_STATE_FRESH_MS) ps = null;
        FcTelemetry.PhoneRcLag lag = link.getTelemetry().getPhoneRcLag();
        if (lag != null && now - lag.receivedAt() > PHONE_STATE_FRESH_MS) lag = null;
        int ping = link.getPing();
        long rc = link.getRcFramesSent();
        long telemetry = link.getTelemetryPacketsReceived();
        FcTelemetry t = link.getTelemetry();
        FcTelemetry.Status s = t.getStatus();
        boolean fresh = s != null && now - s.receivedAt() < 1500;
        long fcAge = t.msSinceFcData(now);
        String blockers = fresh ? String.join(" ", s.blockers().stream().map(FcTelemetry.Blocker::osdName).toList()) : "";
        FcTelemetry.PhoneLink p = t.takePhoneLink();
        FcTelemetry.Gps gps = t.getGps();
        boolean fix = gps != null && gps.fix() >= 1 && gps.receivedAt() > 0 && !(gps.latDeg() == 0 && gps.lonDeg() == 0);
        // the phone's own position (record 0x40F3, once a second): blank when the phone sends none - no permission,
        // no fix yet, or an app before pc.16
        FcTelemetry.PhonePosition pp = t.getPhonePosition();
        boolean phoneFix = pp != null && pp.receivedAt() > 0 && now - pp.receivedAt() < 2500 && !(pp.latDeg() == 0 && pp.lonDeg() == 0);
        row(link.getState().name(), ping, rc - lastRcFrames, link.takeMaxRcGapMs(), telemetry - lastTelemetry,
                link.takeMaxTelemetryGapMs(), fcAge == Long.MAX_VALUE ? -1 : fcAge,
                fresh ? flag(blockers.contains("RXLOSS")) : "", fresh ? flag(blockers.contains("FAILSAFE")) : "",
                fresh ? flag(s.armed()) : "", blockers, flag(radio), flag(rcOutput), videoFps,
                // only sticks that went out: gated off (no throttle-low yet, link down) they weren't sent
                !rcOutput || channels == null || channels.length <= PcConfig.THROTTLE_INDEX ? "" : channels[PcConfig.THROTTLE_INDEX],
                !rcOutput || channels == null ? "" : java.util.Arrays.toString(channels).replace(",", "").replace("[", "").replace("]", ""),
                link.takeMaxPingMs(), link.msSinceLastPong(), path == null ? "" : path,
                p == null ? "" : p.rcInGapMs(), p == null ? "" : p.rcOutGapMs(), p == null ? "" : p.writeMaxMs(),
                p == null ? "" : p.writeTimeouts(), p == null ? "" : p.skipped(),
                video == null ? "" : video.receivedFps(), video == null ? "" : String.format(java.util.Locale.ROOT, "%.2f", video.megabitsPerSecond()),
                video == null ? "" : video.damaged() - lastDamaged, video == null ? "" : video.lost() - lastLost,
                t.getCameraFps() < 0 ? "" : t.getCameraFps(),
                t.getPhoneVideoMbps() < 0 ? "" : String.format(java.util.Locale.ROOT, "%.2f", t.getPhoneVideoMbps()),
                video == null ? "" : video.bitrateStep(),
                // decoding and scaling a frame, ms (smoothed); frames not drawn as a newer one waited; frames that came
                // within 4 ms of the one before. Over ~16 ms a frame at 60 fps: too slow; low with many bunched: 4G
                video == null ? "" : String.format(java.util.Locale.ROOT, "%.1f", video.decodeMs()),
                video == null ? "" : String.format(java.util.Locale.ROOT, "%.1f", video.convertMs()),
                video == null ? "" : video.drawSkipped() - lastDrawSkipped, video == null ? "" : video.bunched() - lastBunched,
                video == null || video.width() == 0 ? "" : video.width() + "x" + video.height(),
                link.getKeyFrameRequests() - lastKeyRequests,
                t.getPhoneTargetBitRate() < 0 ? "" : flag(t.isPhoneIntraRefresh()), flag(link.isRobust()),
                video == null ? "" : video.recovered() - lastRecovered,
                fix ? String.format(java.util.Locale.ROOT, "%.6f", gps.latDeg()) : "", fix ? String.format(java.util.Locale.ROOT, "%.6f", gps.lonDeg()) : "",
                fix ? gps.altM() : "", fix ? gps.sats() : "",
                phoneFix ? String.format(java.util.Locale.ROOT, "%.6f", pp.latDeg()) : "", phoneFix ? String.format(java.util.Locale.ROOT, "%.6f", pp.lonDeg()) : "",
                phoneFix ? pp.accuracyM() : "", phoneFix ? pp.ageS() : "",
                ps == null || ps.rcWriteAgeMs() < 0 ? "" : ps.rcWriteAgeMs(), ps == null || ps.rcInToOutMs() < 0 ? "" : ps.rcInToOutMs(),
                ps == null || ps.lastRcSeq() < 0 ? "" : ps.lastRcSeq(), ps == null ? "" : ps.flags(),
                ps == null || ps.rcInToOutMs() < 0 || ping < 0 ? "" : ping / 2 + ps.rcInToOutMs(),
                glfwPollMaxMs < 0 ? "" : glfwPollMaxMs,
                lag == null || lag.maxLagMs() < 0 ? "" : lag.maxLagMs(), lag == null ? "" : lag.dropped(),
                video == null || video.captureToSendMs() < 0 ? "" : video.captureToSendMs(),
                video == null || video.videoNetMs() < 0 ? "" : video.videoNetMs(),
                video == null || video.g2gMs() < 0 ? "" : video.g2gMs(), video == null || video.g2gMaxMs() < 0 ? "" : video.g2gMaxMs(), "");
        if (video != null) lastRecovered = video.recovered();
        lastKeyRequests = link.getKeyFrameRequests();
        if (video != null) {
            lastDamaged = video.damaged();
            lastLost = video.lost();
            lastDrawSkipped = video.drawSkipped();
            lastBunched = video.bunched();
        }
        lastRcFrames = rc;
        lastTelemetry = telemetry;
        if (now - lastFlush >= 1000) {
            lastFlush = now;
            writeSoon();
        }
    }

    /**
     * The rows go to the file on a thread of their own (1.0.49, L11): sample() and event() run on the Swing thread,
     * and a slow disk (a virus scanner, a sleeping USB drive) held it up at every flush. Rows wait in memory until
     * then; an error there stops the log at the next sample.
     */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "linkLog");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final StringBuilder pending = new StringBuilder();
    private boolean writeQueued, closed;
    private volatile IOException writeError;
    private final Object fileLock = new Object();

    private void writeSoon() throws IOException {
        IOException e = writeError;
        if (e != null) throw e;
        if (writeQueued || pending.length() == 0) return;
        writeQueued = true;
        WRITER.execute(this::drain);
    }

    /** On the writer thread: what waits, written and flushed. */
    private void drain() {
        synchronized (fileLock) { // fileLock, then this: as close() takes them, so the rows stay in order
            String text;
            synchronized (this) {
                writeQueued = false;
                if (closed) return;
                text = pending.toString();
                pending.setLength(0);
            }
            try {
                out.write(text);
                out.flush();
            } catch (IOException e) {
                writeError = e;
            }
        }
    }

    /** A message from the app's log, in the timeline. */
    public synchronized void event(String text) throws IOException {
        Object[] values = new Object[HEADER.split(",").length - 1]; // every column but time blank, the event last
        java.util.Arrays.fill(values, "");
        values[values.length - 1] = text;
        row(values);
    }

    private void row(Object... values) throws IOException {
        StringBuilder sb = new StringBuilder(LocalTime.now().format(TIME));
        for (Object v : values) {
            String s = String.valueOf(v);
            sb.append(',').append(s.contains(",") || s.contains("\"") ? '"' + s.replace("\"", "\"\"") + '"' : s);
        }
        IOException e = writeError;
        if (e != null) throw e;
        pending.append(sb).append(System.lineSeparator());
    }

    private static String flag(boolean b) {
        return b ? "1" : "0";
    }

    /**
     * The last rows written and the file closed; then, for a log opened with {@link #open(Path, Predicate)}, the older
     * logs are sorted out ({@link #sortSoon}). A second close does nothing.
     */
    @Override
    public void close() throws IOException {
        synchronized (fileLock) { // after a write already under way: the last rows, then closed
            String text;
            synchronized (this) {
                if (closed) return;
                closed = true;
                text = pending.toString();
                pending.setLength(0);
            }
            try {
                out.write(text);
            } finally {
                try {
                    out.close();
                } finally {
                    sortSoon(); // a disk that failed at the end is no reason to leave the older logs
                }
            }
        }
    }
}
