package de.droiddrone.bench.hw;

import de.droiddrone.common.MspFrames;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The flight controller keeper's tap (R3): every MSP frame that passes the keeper, either way, with the time it came
 * in and the time it went out ({@code System.nanoTime}), its direction, number and length - and from the stick frames
 * (MSP_SET_RAW_RC) the throttle and the ARM switch as the board takes them.
 * <p>
 * Two things come out of it, both in the keeper's folder:
 * <ul>
 * <li>{@code board-<start>.csv}, a row a second: the stick frames that reached the board in that second, the largest
 * and the 99th-percentile gap between two of them <b>at the board</b> (from one write to the board to the next), what
 * the keeper itself added (from a frame's last byte read to its write), armed, locked, the volts per cell. Its first
 * column is the clock as the app's {@code link-*.csv} has it (HH:mm:ss.SSS), so the two are joined by time.</li>
 * <li>{@code frames-<scenario>.log} while a scenario is named: every frame, one line each.</li>
 * </ul>
 * The frames are handed in from the threads that carry them ({@link #seen}: nothing but a queue's add) and counted and
 * written on the keeper's own tick ({@link #drain}, {@link #row}).
 * <p>
 * {@link Splitter} cuts a byte stream into frames as {@code common.MspFrames} does (v1 and v2, the same checksums;
 * {@code MspTapTest} holds the two to each other) and says two things more, which the keeper's strict mode needs: how
 * many bytes were no part of any frame, and each frame's own bytes as they came.
 */
public final class MspTap implements AutoCloseable {
    /** Who sent a frame to whom: the phone, the board, the keeper itself, the PC app's Drone setup. */
    public enum Way {
        PHONE_TO_BOARD("phone>board"), BOARD_TO_PHONE("board>phone"), KEEPER_TO_BOARD("keeper>board"), BOARD_TO_KEEPER("board>keeper"),
        SETUP_TO_BOARD("setup>board"), BOARD_TO_SETUP("board>setup");

        final String text;

        Way(String text) {
            this.text = text;
        }
    }

    /**
     * One MSP frame.
     * @param version MspFrames.V1 or V2
     * @param type    MspFrames.REQUEST, RESPONSE or ERROR
     * @param flag    v2's flag byte (0 in v1)
     * @param payload never null
     * @param raw     the frame's own bytes, as they came
     */
    public record Frame(byte version, byte type, int flag, int code, byte[] payload, byte[] raw) {
        public boolean v2() {
            return version == MspFrames.V2;
        }

        public boolean request() {
            return type == MspFrames.REQUEST;
        }

        public boolean answer() {
            return type == MspFrames.RESPONSE;
        }

        @Override
        public String toString() {
            return "MSP " + (v2() ? "v2 " : "v1 ") + (char) type + " " + code + " (" + payload.length + " bytes)";
        }
    }

    /** A byte stream cut into MSP frames; one per stream, on one thread. */
    public static final class Splitter {
        private static final byte[] NONE = new byte[0];
        private final int mostPayload;
        private byte[] carry = NONE;
        private long skipped;

        /** @param mostPayload a header that says more is no frame's start (MspFrames: 4096; the phone's requests are far shorter) */
        public Splitter(int mostPayload) {
            this.mostPayload = mostPayload;
        }

        /** The whole frames in the first length bytes, with what the last call left over in front. */
        public List<Frame> feed(byte[] data, int length) {
            byte[] s;
            if (carry.length == 0) {
                s = data;
            } else {
                s = Arrays.copyOf(carry, carry.length + length);
                System.arraycopy(data, 0, s, carry.length, length);
                length = s.length;
            }
            List<Frame> frames = new ArrayList<>(4);
            int i = 0;
            while (i < length) {
                if (s[i] != MspFrames.START) {
                    i++;
                    skipped++;
                    continue;
                }
                if (length - i < 3) break; // the rest of the header comes with the next read
                byte magic = s[i + 1], type = s[i + 2];
                boolean v2 = magic == MspFrames.V2;
                if (magic != MspFrames.V1 && !v2 || type != MspFrames.REQUEST && type != MspFrames.RESPONSE && type != MspFrames.ERROR) {
                    i++;
                    skipped++;
                    continue;
                }
                int header = v2 ? MspFrames.V2_HEADER_SIZE : MspFrames.V1_HEADER_SIZE;
                if (length - i < header) break;
                int code = v2 ? Msp.u16(s, i + 4) : s[i + 4] & 0xff, size = v2 ? Msp.u16(s, i + 6) : s[i + 3] & 0xff;
                if (size > mostPayload) { // not a frame's start
                    i++;
                    skipped++;
                    continue;
                }
                int whole = header + size + MspFrames.CRC_SIZE;
                if (length - i < whole) break;
                int crc = 0;
                if (v2) for (int k = i + 3; k < i + header + size; k++) crc = MspFrames.crc8DvbS2(crc, s[k]);
                else for (int k = i + 3; k < i + header + size; k++) crc ^= s[k] & 0xff;
                if ((s[i + whole - 1] & 0xff) != crc) {
                    i++;
                    skipped++;
                    continue;
                }
                frames.add(new Frame(magic, type, v2 ? s[i + 3] & 0xff : 0, code, Arrays.copyOfRange(s, i + header, i + header + size),
                        Arrays.copyOfRange(s, i, i + whole)));
                i += whole;
            }
            carry = i < length ? Arrays.copyOfRange(s, i, length) : NONE;
            return frames;
        }

        /** Bytes so far that were stepped over: part of no frame (text, noise, a frame with a wrong checksum). */
        public long skipped() {
            return skipped;
        }

        /** Bytes of a frame begun and not whole yet. */
        public int pending() {
            return carry.length;
        }

        /** Forgets a frame begun (the port opened again, another client). */
        public void reset() {
            carry = NONE;
        }
    }

    /** A frame of this kind, built: for the keeper's own requests and for tests. */
    public static byte[] bytes(byte version, byte type, int code, byte[] payload) {
        byte[] f = version == MspFrames.V2 ? MspFrames.v2Request(code, payload) : MspFrames.v1Request(code, payload);
        f[2] = type; // the checksum does not cover it
        return f;
    }

    /** The tap's clock: nanoseconds since it began, never 0 (0 stands for "not" in a {@link Seen}). */
    public long now() {
        return System.nanoTime() - startNs + 1;
    }

    /**
     * A frame that passed, or did not.
     * @param inNs     when its last byte was read ({@link MspTap#now()})
     * @param outNs    when its write returned; 0: it was not passed on
     * @param throttle a stick frame's throttle, else -1
     * @param arm      a stick frame's ARM switch value, else -1
     * @param note     why it was not passed on, or a word about it; null: nothing to say
     */
    public record Seen(long inNs, long outNs, Way way, Frame frame, int throttle, int arm, String note) {
    }

    /**
     * The stick frames at the board since {@link MspTap#mark()}.
     * @param frames     stick frames written to the board
     * @param seconds    from the first of them to the last
     * @param perSecond  frames a second over that time
     * @param maxGapMs   the longest time between two of them
     * @param p99GapMs   99 of 100 gaps were no longer than this
     * @param addedMaxMs the longest the keeper held one, from read to written
     * @param addedMeanMs the mean of that
     */
    public record Figures(long frames, double seconds, double perSecond, double maxGapMs, double p99GapMs, double addedMaxMs, double addedMeanMs) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d stick frames in %.1f s at the board: %.1f a second, largest gap %.1f ms, 99th percentile %.1f ms; "
                    + "the keeper held a frame %.2f ms at most, %.3f ms in the mean", frames, seconds, perSecond, maxGapMs, p99GapMs, addedMaxMs, addedMeanMs);
        }

        /** As the control channel says it: name=value, no spaces in a value. */
        public String line() {
            return String.format(Locale.ROOT, "frames=%d seconds=%.2f perSecond=%.1f maxGapMs=%.2f p99GapMs=%.2f addedMaxMs=%.3f addedMeanMs=%.4f",
                    frames, seconds, perSecond, maxGapMs, p99GapMs, addedMaxMs, addedMeanMs);
        }
    }

    /**
     * What the keeper knows in the second a row is written; the tap adds its own columns.
     * @param armed  "1", "0" or "" (not known)
     * @param locked the same, of the arming lock
     */
    public record Row(String board, String lease, String armed, String locked, long windowMs, double volts, double cellVolts, String faults, String scenario) {
    }

    public static final String HEADER = "time,board,lease,armed,locked,window_ms,volts,cell_volts,stick_frames,stick_gap_max_ms,stick_gap_p99_ms,"
            + "keeper_added_max_ms,keeper_added_mean_ms,throttle,arm_switch,to_board,from_board,dropped,faults,scenario,event";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS"), STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** The gaps since a mark, counted in steps of 0.05 ms up to 2 s (what is longer counts as that; the longest is kept exactly). */
    private static final int BUCKET_NS = 50_000, BUCKETS = 40_000;

    private final ConcurrentLinkedQueue<Seen> queue = new ConcurrentLinkedQueue<>();
    private final Path folder;
    private final long startNs = System.nanoTime();
    private BufferedWriter csv, log;
    private Path csvFile, logFile;
    private String problem;

    // ---- this second (drain's thread, under this)
    private long[] gaps = new long[512];
    private int gapCount, sticks, toBoard, fromBoard, dropped, addedCount;
    private long addedMax, addedSum, lastStickOutNs;
    private int throttle = -1, arm = -1;

    // ---- since the mark
    private final int[] histogram = new int[BUCKETS + 1];
    private long markFrames, markGaps, markGapMax, markFirstOut, markLastOut, markAddedMax, markAddedSum;

    /** @param folder where the files go; null: none are written (the figures are kept all the same) */
    public MspTap(Path folder) {
        this.folder = folder;
        if (folder == null) return;
        try {
            Files.createDirectories(folder);
            csvFile = folder.resolve("board-" + LocalDateTime.now().format(STAMP) + ".csv");
            for (int n = 2; Files.exists(csvFile); n++) csvFile = folder.resolve("board-" + LocalDateTime.now().format(STAMP) + "-" + n + ".csv");
            csv = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8);
            csv.write(HEADER);
            csv.newLine();
            csv.flush();
        } catch (IOException e) {
            problem = "the tap's file could not be made in " + folder + ": " + e.getMessage();
            csv = null;
        }
    }

    /** The row-a-second file; null when none is written. */
    public Path csvFile() {
        return csv == null ? null : csvFile;
    }

    /** What went wrong with the tap's files, once; null: nothing. */
    public synchronized String takeProblem() {
        String p = problem;
        problem = null;
        return p;
    }

    /** A frame, from the thread that carried it. */
    public void seen(Seen s) {
        queue.add(s);
    }

    /** Counts and writes down what was seen since the last call. */
    public synchronized void drain() {
        for (Seen s = queue.poll(); s != null; s = queue.poll()) count(s);
        if (log != null) {
            try {
                log.flush();
            } catch (IOException e) {
                failed(e);
            }
        }
    }

    private void count(Seen s) {
        boolean passed = s.outNs() != 0;
        if (!passed) dropped++;
        else if (s.way() == Way.BOARD_TO_PHONE || s.way() == Way.BOARD_TO_KEEPER || s.way() == Way.BOARD_TO_SETUP) fromBoard++;
        else toBoard++;
        if (s.way() == Way.PHONE_TO_BOARD && s.frame().code() == Msp.SET_RAW_RC && s.throttle() >= 0) {
            throttle = s.throttle();
            arm = s.arm();
            if (passed) stick(s.inNs(), s.outNs());
        }
        if (log != null) {
            try {
                log.write(line(s));
                log.newLine();
            } catch (IOException e) {
                failed(e);
            }
        }
    }

    /** A stick frame written to the board. */
    private void stick(long inNs, long outNs) {
        long added = Math.max(0, outNs - inNs);
        sticks++;
        addedCount++;
        addedSum += added;
        addedMax = Math.max(addedMax, added);
        if (markFirstOut == 0) markFirstOut = outNs;
        markLastOut = outNs;
        markFrames++;
        markAddedSum += added;
        markAddedMax = Math.max(markAddedMax, added);
        if (lastStickOutNs != 0) {
            long gap = Math.max(0, outNs - lastStickOutNs);
            if (gapCount == gaps.length) gaps = Arrays.copyOf(gaps, gaps.length * 2);
            gaps[gapCount++] = gap;
            if (markFrames > 1) { // a gap of this stretch: both its frames came after the mark
                histogram[(int) Math.min(BUCKETS, gap / BUCKET_NS)]++;
                markGaps++;
                markGapMax = Math.max(markGapMax, gap);
            }
        }
        lastStickOutNs = outNs;
    }

    /** From here on {@link #figures()} counts anew. */
    public synchronized void mark() {
        drain();
        Arrays.fill(histogram, 0);
        markFrames = markGaps = markGapMax = markFirstOut = markLastOut = markAddedMax = markAddedSum = 0;
    }

    /** The stick frames at the board since the last {@link #mark()} (or since the tap began). */
    public synchronized Figures figures() {
        drain();
        double seconds = markFrames < 2 ? 0 : (markLastOut - markFirstOut) / 1e9;
        long rank = (long) Math.ceil(0.99 * markGaps), seen = 0;
        double p99 = 0;
        for (int b = 0; b <= BUCKETS && markGaps > 0; b++) {
            seen += histogram[b];
            if (seen >= rank) {
                p99 = b == BUCKETS ? markGapMax / 1e6 : Math.min(markGapMax, (b + 1L) * BUCKET_NS) / 1e6; // the bucket's upper edge
                break;
            }
        }
        return new Figures(markFrames, seconds, seconds > 0 ? (markFrames - 1) / seconds : 0, markGapMax / 1e6, p99, markAddedMax / 1e6,
                markFrames == 0 ? 0 : markAddedSum / 1e6 / markFrames);
    }

    /** The 99th percentile by rank: of n values sorted, the one at ceil(0.99 n). */
    static long p99(long[] values, int count) {
        if (count == 0) return 0;
        long[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(0.99 * count) - 1];
    }

    /** This second's row, written; the second's counts begin anew. Returns the row as written. */
    public synchronized String row(Row r) {
        drain();
        long max = 0;
        for (int i = 0; i < gapCount; i++) max = Math.max(max, gaps[i]);
        String line = String.join(",", LocalDateTime.now().format(CLOCK), r.board(), r.lease(), r.armed(), r.locked(), Long.toString(r.windowMs()),
                number(r.volts(), "%.2f"), number(r.cellVolts(), "%.3f"), Integer.toString(sticks),
                gapCount == 0 ? "" : ms(max), gapCount == 0 ? "" : ms(p99(gaps, gapCount)),
                addedCount == 0 ? "" : ms(addedMax), addedCount == 0 ? "" : String.format(Locale.ROOT, "%.4f", addedSum / 1e6 / addedCount),
                throttle < 0 ? "" : Integer.toString(throttle), arm < 0 ? "" : Integer.toString(arm), Integer.toString(toBoard), Integer.toString(fromBoard),
                Integer.toString(dropped), plain(r.faults()), plain(r.scenario()), "");
        write(line);
        gapCount = sticks = toBoard = fromBoard = dropped = addedCount = 0;
        addedMax = addedSum = 0;
        throttle = arm = -1;
        return line;
    }

    /** A row of its own for something that happened: the time, every other column blank, the words last (as the app's link log does it). */
    public synchronized void event(String text) {
        StringBuilder line = new StringBuilder(LocalDateTime.now().format(CLOCK));
        for (int i = HEADER.split(",").length - 2; i > 0; i--) line.append(',');
        write(line.append(',').append(plain(text)).toString());
    }

    private void write(String line) {
        if (csv == null) return;
        try {
            csv.write(line);
            csv.newLine();
            csv.flush(); // on the disk at once: a keeper that is killed keeps what was said until then
        } catch (IOException e) {
            failed(e);
        }
    }

    private void failed(IOException e) {
        if (problem == null) problem = "the tap could not write: " + e.getMessage();
    }

    private static String ms(long ns) {
        return String.format(Locale.ROOT, "%.3f", ns / 1e6);
    }

    private static String number(double v, String format) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, format, v);
    }

    /** One cell: no comma, no line end. */
    private static String plain(String text) {
        return text == null ? "" : text.replace(',', ';').replace('\r', ' ').replace('\n', ' ');
    }

    // ------------------------------------------------------------------ the frame log

    /** Every frame from now on goes into frames-&lt;name&gt;.log in the tap's folder as well; the log before it is closed. */
    public synchronized Path openLog(String name) {
        closeLog();
        if (folder == null) return null;
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        try {
            logFile = folder.resolve("frames-" + safe + ".log");
            for (int n = 2; Files.exists(logFile); n++) logFile = folder.resolve("frames-" + safe + "-" + n + ".log");
            log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
            log.write("# " + name + " - every MSP frame at the keeper. Columns: microseconds since the keeper's tap began when the frame was whole; "
                    + "way; MSP version; < request, > answer, ! error; number; payload bytes; held = microseconds until its write returned "
                    + "(- : not passed on); for a stick frame its throttle and ARM switch; a note.");
            log.newLine();
            log.write("# clock " + LocalDateTime.now().format(CLOCK) + " = " + now() / 1000 + " us");
            log.newLine();
            return logFile;
        } catch (IOException e) {
            failed(e);
            log = null;
            return null;
        }
    }

    public synchronized void closeLog() {
        drain();
        if (log == null) return;
        try {
            log.close();
        } catch (IOException e) {
            failed(e);
        }
        log = null;
    }

    private String line(Seen s) {
        Frame f = s.frame();
        StringBuilder b = new StringBuilder(96);
        b.append(s.inNs() / 1000).append(' ').append(s.way().text).append(f.v2() ? " v2 " : " v1 ").append((char) f.type()).append(' ')
                .append(f.code()).append(" len=").append(f.payload().length).append(" held=");
        if (s.outNs() == 0) b.append('-');
        else b.append(Math.max(0, s.outNs() - s.inNs()) / 1000);
        if (s.throttle() >= 0) b.append(" thr=").append(s.throttle()).append(" arm=").append(s.arm());
        if (s.note() != null) b.append("  ").append(s.note());
        return b.toString();
    }

    @Override
    public synchronized void close() {
        closeLog();
        if (csv == null) return;
        try {
            csv.close();
        } catch (IOException ignored) {
        }
        csv = null;
    }
}
