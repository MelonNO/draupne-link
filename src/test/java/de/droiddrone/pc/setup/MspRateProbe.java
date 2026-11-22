package de.droiddrone.pc.setup;

import com.fazecast.jSerialComm.SerialPort;
import de.droiddrone.common.ArmRanges;
import de.droiddrone.common.BeeperQuiet;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.MspFrames;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * By hand, on ONE real Betaflight board on this PC's USB (props off): how long the board takes to answer MSP while
 * stick frames come at the phone app's rate.
 * <pre>
 * gradlew mspRateProbe [-Pport=COM9] [-Prates=250,100,50] [-Pseconds=10] [-Prequests=42] [-Pmix=phone|attitude]
 * </pre>
 * Why: a code read says Betaflight 4.4.1 takes ONE MSP frame from a port each time its serial task runs
 * (msp_serial.c), 100 times a second by default (serial_update_rate_hz), and an F4's USB receive buffer holds 2048
 * bytes and drops a USB packet that does not fit whole - while the phone writes about 250 stick frames a second and
 * about 42 requests. If so, sticks queue up at the board for most of a second. This measures it.
 * <p>
 * The PC stands in for the phone (its USB to the board's virtual COM port is what the phone's is) and sends what the
 * phone sends (flight-app overlay Msp.setRawRc, Serial.writeRcMsp): MSP_SET_RAW_RC in MSP v2, 8 channels (the PC's
 * default mapping: the sticks and AUX1-4; more when ARM is on a higher AUX), in the board's own channel order (its
 * MSP_RX_MAP), each frame its own write, fire and forget, a late frame replaced by the newest; between them the
 * requests, MSP v2 too: the phone's mix (attitude 20/s, status and altitude 4/s, seven more 2/s each: 42/s) or
 * attitude alone. Betaflight answers every frame (a stick frame with an empty answer), one at a time, in the order
 * they came: each answer is paired with what it answers, and its delay is answer time - send time - a range where a
 * frame lost among alike ones leaves open which one an answer is for ({@link #earliest}, {@link #latest}). The mix's
 * ten kinds pin it down to a few ms; attitude alone, with frames lost, leaves it wide. The PC spins one core while
 * sticks go out (Windows' sleep may be 15.6 ms coarse).
 * <p>
 * Each rate in turn (0: no sticks, the requests alone, as a baseline): the board read (armed or 3D: nothing more is
 * sent), Betaflight's arming block "MSP" set and read back, then for {@code seconds} sticks at the rate and the
 * requests, then nothing until no answer has come for a second (the board's queue is empty). Printed for each kind
 * (sticks, requests): sent, answered within 3 s, lost (the rest), and the delays (median, 90 %, longest) of the frames
 * sent in the window's second half (steady state). At the end: STOP to every motor (MSP_SET_MOTOR 1000, as escProbe
 * -Pesc=stop), the arming block as it was found, and one line "Board left: ...".
 * <p>
 * Safe by construction: arming is blocked before the first stick frame, and the sticks are idle - roll, pitch, yaw
 * 1500, throttle 1000, every AUX 1000 - and checked against the board's own ARM ranges first (a frame that could arm
 * is never sent). Enter stops early, cleanly. Ctrl+C: Gradle may end the program hard - the arming block then stays
 * on until the board restarts (USB out and in). Sound: with an MSP receiver Betaflight may beep for its lost receiver
 * when the sticks stop. No test opens a COM port: MspRateProbeTest runs this on a made-up board.
 */
public final class MspRateProbe {
    static final int MSP_SET_RAW_RC = FcCommon.MSP_SET_RAW_RC, MSP_ATTITUDE = FcCommon.MSP_ATTITUDE, MSP_STATUS = FcCommon.MSP_STATUS;
    /** The phone's stick frame with the PC's default mapping (draupne-link.example.properties): roll, pitch, yaw, throttle, AUX1-4. */
    static final int CHANNELS = 8;
    static final int MID = 1500, LOW = 1000;
    /** The phone's requests at its telemetry rate 20 (Msp's MSP thread): {command, a second}. */
    static final int[][] PHONE_MIX = {{MSP_ATTITUDE, 20}, {MSP_STATUS, 4}, {FcCommon.MSP_ALTITUDE, 4},
            {ArmRanges.MSP_MODE_RANGES, 2}, {BeeperQuiet.MSP_BEEPER_CONFIG, 2}, {FcCommon.MSP_BATTERY_STATE, 2},
            {FcCommon.MSP_RAW_GPS, 2}, {FcCommon.MSP_COMP_GPS, 2}, {FcCommon.MSP_VTX_CONFIG, 2}, {FcCommon.MSP_ANALOG, 2}};
    /** A setup read or write before or after the windows: this long for its answer, so many tries. */
    private static final long CALL_TIMEOUT_MS = 1000;
    private static final int CALL_TRIES = 3;
    /** A queue that never empties (it can't, with nothing sent): the wait for it ends after this. */
    private static final long DRAIN_MAX_MS = 15_000;

    /** What to run: the stick rates in turn, each window's length, the requests a second and their kinds. */
    record Settings(int[] rates, long windowMs, int requests, boolean phoneMix, long quietMs, long lostMs) {
        /** From the Gradle properties; empty: the default. */
        static Settings parse(String rates, String seconds, String requests, String mix) {
            int[] r;
            try {
                r = Arrays.stream((rates.isBlank() ? "250,100,50" : rates).split("[,; ]+")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("-Prates: stick frames a second, e.g. 250,100,50 (0: none, the requests alone)");
            }
            double sec;
            int req;
            try {
                sec = seconds.isBlank() ? 10 : Double.parseDouble(seconds);
                req = requests.isBlank() ? 42 : Integer.parseInt(requests.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("-Pseconds and -Prequests are numbers");
            }
            String m = mix.isBlank() ? "phone" : mix.trim().toLowerCase(Locale.ROOT);
            if (r.length == 0 || Arrays.stream(r).anyMatch(x -> x < 0 || x > 1000)) throw new IllegalArgumentException("-Prates: 0 .. 1000 each");
            if (sec < 2 || sec > 600) throw new IllegalArgumentException("-Pseconds: 2 .. 600");
            if (req < 0 || req > 500) throw new IllegalArgumentException("-Prequests: 0 .. 500");
            if (req == 0 && Arrays.stream(r).anyMatch(x -> x == 0)) throw new IllegalArgumentException("-Prates=0 is the requests alone: -Prequests must not be 0");
            if (!m.equals("phone") && !m.equals("attitude")) throw new IllegalArgumentException("-Pmix: phone or attitude");
            return new Settings(r, Math.round(sec * 1000), req, m.equals("phone"), 1000, 3000);
        }
    }

    /** The order the requests go in: the phone's mix spread evenly (smooth weighted round robin), or attitude alone. */
    static int[] requestCycle(boolean phoneMix) {
        if (!phoneMix) return new int[]{MSP_ATTITUDE};
        int total = Arrays.stream(PHONE_MIX).mapToInt(k -> k[1]).sum();
        int[] cycle = new int[total], current = new int[PHONE_MIX.length];
        for (int n = 0; n < total; n++) {
            int best = 0;
            for (int i = 0; i < PHONE_MIX.length; i++) {
                current[i] += PHONE_MIX[i][1];
                if (current[i] > current[best]) best = i;
            }
            current[best] -= total;
            cycle[n] = PHONE_MIX[best][0];
        }
        return cycle;
    }

    // ------------------------------------------------------------------ the sticks

    /** The idle sticks in Betaflight's own channel order (roll, pitch, yaw, throttle, AUX1 ...). */
    static short[] idle(int count) {
        short[] v = new short[count];
        for (int i = 0; i < count; i++) v[i] = (short) (i < 3 ? MID : LOW);
        return v;
    }

    /** The channels to send: the PC's default 8, more when an ARM slot is on a higher AUX (it must read off too). */
    static int channelCount(int[][] armSlots) {
        int n = CHANNELS;
        for (int[] slot : armSlots) n = Math.max(n, 4 + slot[0] + 1);
        return Math.min(n, FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT);
    }

    /**
     * The phone's stick frame (Msp.setRawRc): MSP v2 MSP_SET_RAW_RC, the idle sticks each put where the board's channel
     * map says (channel i at map[i]). Null when the map does not fit the frame: then no sticks, as on the phone.
     */
    static byte[] stickFrame(int[] map, int count) {
        short[] v = idle(count);
        byte[] payload = new byte[count * 2];
        boolean[] taken = new boolean[count];
        for (int i = 0; i < count; i++) {
            int at = i;
            if (i < map.length) {
                if (map[i] < 0 || map[i] >= count) return null;
                at = map[i];
            }
            if (taken[at]) return null; // two channels on one place: one would be left at 0
            taken[at] = true;
            payload[at * 2] = (byte) (v[i] & 0xff);
            payload[at * 2 + 1] = (byte) (v[i] >> 8 & 0xff);
        }
        return MspFrames.v2Request(MSP_SET_RAW_RC, payload);
    }

    // ------------------------------------------------------------------ pairing and statistics

    /** One frame sent: its command and when it went (nanoTime, just before its write). */
    record Sent(int code, long ns) {
        boolean stick() {
            return code == MSP_SET_RAW_RC;
        }
    }

    /** One answer as it came: its command, whether it was Betaflight's error answer, when it was read (nanoTime). */
    record Answer(int code, boolean error, long ns) {
    }

    /** What one window sent and got back, each in order: the writer adds frames, the reader thread answers. */
    static final class Pairing {
        private final List<Sent> sent = new ArrayList<>();
        private final List<Answer> answers = new ArrayList<>();

        synchronized void sent(int code, long ns) {
            sent.add(new Sent(code, ns));
        }

        synchronized void answer(int code, boolean error, long ns) {
            answers.add(new Answer(code, error, ns));
        }

        /** When the last answer came, or {@code orElse} when none has. */
        synchronized long lastAnswerNs(long orElse) {
            return answers.isEmpty() ? orElse : answers.get(answers.size() - 1).ns();
        }

        synchronized List<Sent> sent() {
            return List.copyOf(sent);
        }

        synchronized List<Answer> answers() {
            return List.copyOf(answers);
        }
    }

    /*
     * Which frame an answer is for. Betaflight answers one frame at a time, in the order they came, so the answers are
     * the frames that got through, in order - but a stick frame's answer is like every other's (empty), and a frame lost
     * in the board's buffer leaves open which of two alike ones got through. So each answer is given its earliest
     * possible frame (the first of its command after the answer before's, sent before it came) and its latest (the same
     * from the last answer backwards): the true one lies between, and so does its delay. Requests of other kinds between
     * the sticks pin both down (the phone's mix has ten); stick frames and attitude alone, with frames lost, leave a
     * wide range - printed as one.
     */

    /** Each answer's earliest possible frame (-1: none - an answer to something sent before the window). */
    static int[] earliest(List<Sent> sent, List<Answer> answers) {
        int[] f = new int[answers.size()];
        int last = -1;
        for (int i = 0; i < f.length; i++) {
            Answer a = answers.get(i);
            f[i] = -1;
            for (int j = last + 1; j < sent.size() && sent.get(j).ns() <= a.ns(); j++) {
                if (sent.get(j).code() == a.code()) {
                    f[i] = last = j;
                    break;
                }
            }
        }
        return f;
    }

    /** Each answer's latest possible frame: the last of its command before the next answer's, sent before it came. */
    static int[] latest(List<Sent> sent, List<Answer> answers, int[] earliest) {
        int[] g = new int[answers.size()];
        int next = sent.size();
        for (int i = g.length - 1; i >= 0; i--) {
            g[i] = -1;
            if (earliest[i] < 0) continue;
            Answer a = answers.get(i);
            int j = next - 1;
            while (j > earliest[i] && (sent.get(j).code() != a.code() || sent.get(j).ns() > a.ns())) j--;
            g[i] = next = j; // never before its earliest: both are ways the frames can have got through
        }
        return g;
    }

    /** Delays in ms: how many, the median, the 90th percentile and the longest (nearest rank). */
    record Delays(int count, double median, double p90, double max) {
        static Delays of(List<Long> ns) {
            if (ns.isEmpty()) return new Delays(0, Double.NaN, Double.NaN, Double.NaN);
            List<Long> s = new ArrayList<>(ns);
            Collections.sort(s);
            return new Delays(s.size(), rank(s, 0.5), rank(s, 0.9), s.get(s.size() - 1) / 1e6);
        }

        private static double rank(List<Long> sorted, double p) {
            return sorted.get(Math.max(0, (int) Math.ceil(p * sorted.size()) - 1)) / 1e6;
        }
    }

    /**
     * One kind of frame (sticks or requests) in one window: sent, answered (within the lost time), error answers, and
     * the delays of the frames sent in the window's second half - as short and as long as the answers allow.
     */
    record Kind(int sent, int answered, int errors, Delays shortest, Delays longest) {
        int lost() {
            return sent - answered;
        }

        /** "median 846 ms, 90 % 902-933 ms, max 960 ms": a range where lost frames leave it open. */
        String delays() {
            if (longest.count() == 0) return "no answers";
            return "median " + range(shortest.median(), longest.median()) + " ms, 90 % " + range(shortest.p90(), longest.p90())
                    + " ms, max " + range(shortest.max(), longest.max()) + " ms";
        }
    }

    static String range(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return String.format(Locale.ROOT, "%.0f", Double.isNaN(a) ? b : a);
        long lo = Math.round(Math.min(a, b)), hi = Math.round(Math.max(a, b));
        return lo == hi ? Long.toString(lo) : lo + "-" + hi;
    }

    /** One window: its stick rate and length, both kinds, how long the board's queue took to empty after it. */
    record Window(int rate, long lengthNs, Kind sticks, Kind requests, long drainNs, int strays) {
    }

    static Window summary(int rate, List<Sent> sent, List<Answer> answers, long startNs, long endNs, long lostMs, long drainNs) {
        int[] early = earliest(sent, answers), late = latest(sent, answers, early);
        long half = startNs + (endNs - startNs) / 2, lostNs = lostMs * 1_000_000L;
        int strays = (int) Arrays.stream(early).filter(j -> j < 0).count();
        return new Window(rate, endNs - startNs, kind(true, sent, answers, early, late, half, lostNs),
                kind(false, sent, answers, early, late, half, lostNs), drainNs, strays);
    }

    private static Kind kind(boolean sticks, List<Sent> sent, List<Answer> answers, int[] early, int[] late, long half, long lostNs) {
        int count = (int) sent.stream().filter(s -> s.stick() == sticks).count(), answered = 0, errors = 0;
        List<Long> shortest = new ArrayList<>(), longest = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            if (early[i] < 0) continue;
            Answer a = answers.get(i);
            Sent first = sent.get(early[i]), last = sent.get(late[i]);
            if (first.stick() != sticks) continue;
            if (a.ns() - last.ns() > lostNs) continue; // later than the lost time however it is read: lost
            answered++;
            if (a.error()) errors++;
            if (last.ns() >= half) shortest.add(a.ns() - last.ns());
            if (first.ns() >= half) longest.add(a.ns() - first.ns());
        }
        return new Kind(count, answered, errors, Delays.of(shortest), Delays.of(longest));
    }

    // ------------------------------------------------------------------ the run

    /** What a run did: each window, why it stopped short (null: it didn't), and how it left the board. */
    record Outcome(List<Window> windows, String stopped, String boardLeft) {
    }

    private final BetaflightUsb.Wire wire;
    private final Settings settings;
    private final PrintStream out;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final Thread reader;
    private final LinkedBlockingQueue<MspFrames.Frame> answers = new LinkedBlockingQueue<>();
    private final Object writing = new Object();
    private final List<Window> windows = new ArrayList<>();
    /** Measuring: every answer goes here; else to {@link #answers} for a setup call. */
    private volatile Pairing pairing;
    private volatile IOException readerGone;
    private volatile boolean armedSeen;
    /** Something was written that changes the board (the block, sticks): the end puts it back. */
    private boolean touched;
    private boolean blockedBefore;
    private byte[] stick;
    private volatile String boardLeft;

    MspRateProbe(BetaflightUsb.Wire wire, Settings settings, PrintStream out) {
        this.wire = wire;
        this.settings = settings;
        this.out = out;
        reader = new Thread(this::readLoop, "mspRateProbe-reader");
        reader.setDaemon(true);
    }

    /** Ends the run soon and cleanly (Enter): the window under way stops, its queue empties, the board is put back. */
    void stop() {
        stop.set(true);
    }

    Outcome run() {
        reader.start();
        String stopped = null;
        try {
            stopped = prepare();
            if (stopped == null) {
                for (int rate : settings.rates) {
                    if (stop.get()) {
                        stopped = "Stopped early.";
                        break;
                    }
                    stopped = safety();
                    if (stopped != null) break;
                    Window w = window(rate);
                    windows.add(w);
                    print(w);
                    if (armedSeen) {
                        stopped = "The board said ARMED: stopped.";
                        break;
                    }
                }
                if (windows.size() > 1) printSummary();
            }
            if (stopped != null) out.println(stopped);
        } catch (IOException e) {
            stopped = "Stopped: " + e.getMessage();
            out.println(stopped);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = "Stopped.";
        } finally {
            finish();
        }
        return new Outcome(List.copyOf(windows), stopped, boardLeft);
    }

    /** Reads the board and builds the stick frame; nothing is written. The reason not to go on, or null. */
    private synchronized String prepare() throws IOException, InterruptedException {
        String variant = new String(call(FcCommon.MSP_FC_VARIANT, null), java.nio.charset.StandardCharsets.US_ASCII).trim();
        if (!variant.startsWith("BTFL")) return "Not Betaflight (" + variant + "): nothing sent.";
        byte[] v = call(FcCommon.MSP_FC_VERSION, null);
        byte[] status = call(MSP_STATUS, null);
        if (MotorTest.armed(status)) return "ARMED: nothing sent. Disarm it first.";
        int flags = BetaflightUsb.armingDisableFlags(status);
        if (flags == -1) return "Its status has no arming flags (an old Betaflight?): nothing sent.";
        blockedBefore = (flags & BetaflightUsb.ARMING_DISABLED_MSP) != 0;
        if (MotorTest.is3d(call(MotorTest.MSP_FEATURE_CONFIG, null))) return "3D mode is on (throttle 1000 would be full reverse): nothing sent.";
        byte[] m = call(FcCommon.MSP_RX_MAP, null);
        int[] map = new int[m.length];
        for (int i = 0; i < m.length; i++) map[i] = m[i] & 0xff;
        int[][] arm = ArmRanges.parse(call(ArmRanges.MSP_MODE_RANGES, null));
        if (arm == null) return "Its mode ranges are not understood: nothing sent.";
        int count = channelCount(arm);
        stick = stickFrame(map, count);
        if (stick == null) return "Its channel map " + Arrays.toString(map) + " does not fit " + count + " channels: nothing sent.";
        if (ArmRanges.mayArm(idle(count), count, arm)) return "ARM could be on with every AUX at 1000 (its ARM ranges): nothing sent.";
        StringBuilder armText = new StringBuilder();
        for (int[] slot : arm) armText.append(armText.length() == 0 ? "" : ", ").append("AUX").append(slot[0] + 1).append(' ').append(slot[1]).append('-').append(slot[2]);
        out.println("Betaflight " + (v.length >= 3 ? (v[0] & 0xff) + "." + (v[1] & 0xff) + "." + (v[2] & 0xff) : "?") + ", disarmed, arming block "
                + (blockedBefore ? "on" : "off") + ". Channel map " + Arrays.toString(map) + "; ARM " + (arm.length == 0 ? "on no switch" : "on " + armText) + ".");
        out.println("Sticks: MSP v2 MSP_SET_RAW_RC, " + count + " channels, " + stick.length + " bytes a frame: roll, pitch, yaw " + MID
                + ", throttle " + LOW + ", every AUX " + LOW + ". Each frame its own write.");
        if (settings.requests > 0) out.println("Requests: " + settings.requests + "/s, " + (settings.phoneMix
                ? "the phone's mix (attitude 20, status 4, altitude 4, seven more 2 each, in 42)" : "MSP_ATTITUDE alone") + ", MSP v2, 9 bytes each.");
        out.println(String.format(Locale.ROOT, "Each rate %.0f s. Delays: of the frames sent in its last %.0f s; a range (a-b) where lost frames"
                        + " leave open which frame an answer is for. Lost: no answer within %.0f s.",
                settings.windowMs / 1000.0, settings.windowMs / 2000.0, settings.lostMs / 1000.0));
        out.println("Enter stops early. Ctrl+C may end it hard: the arming block then stays on until the board restarts (USB out and in).");
        return null;
    }

    /** Before each window: not armed, not 3D, arming blocked and read back so. The reason not to go on, or null. */
    private synchronized String safety() throws IOException, InterruptedException {
        if (MotorTest.armed(call(MSP_STATUS, null))) return "ARMED: stopped, nothing more sent.";
        if (MotorTest.is3d(call(MotorTest.MSP_FEATURE_CONFIG, null))) return "3D mode is on: stopped, nothing more sent.";
        touched = true;
        call(BetaflightUsb.MSP_SET_ARMING_DISABLED, new byte[]{1});
        byte[] status = call(MSP_STATUS, null);
        int flags = BetaflightUsb.armingDisableFlags(status);
        if (MotorTest.armed(status) || flags == -1 || (flags & BetaflightUsb.ARMING_DISABLED_MSP) == 0) return "The arming block did not take: no sticks sent.";
        return null;
    }

    /** One window: sticks at the rate and the requests, then nothing until the board's queue is empty. */
    private synchronized Window window(int rate) throws IOException, InterruptedException {
        Pairing p = new Pairing();
        int[] cycle = requestCycle(settings.phoneMix);
        byte[][] requests = new byte[cycle.length][];
        for (int i = 0; i < cycle.length; i++) requests[i] = MspFrames.v2Request(cycle[i], null);
        long rcPeriod = rate > 0 ? 1_000_000_000L / rate : 0, reqPeriod = settings.requests > 0 ? 1_000_000_000L / settings.requests : 0;
        pairing = p;
        long start = System.nanoTime(), end = start + settings.windowMs * 1_000_000L;
        long nextRc = start, nextReq = start + reqPeriod / 2;
        int r = 0;
        try {
            while (!stop.get() && !armedSeen) {
                long now = System.nanoTime();
                if (now >= end) break;
                if (rate > 0 && now >= nextRc) { // sticks first, as the phone's writer
                    if (now - nextRc >= rcPeriod) nextRc = now; // a whole frame late: the phone writes the newest only
                    p.sent(MSP_SET_RAW_RC, now);
                    send(stick);
                    nextRc += rcPeriod;
                    continue;
                }
                if (reqPeriod > 0 && now >= nextReq) {
                    int at = r++ % cycle.length;
                    p.sent(cycle[at], now);
                    send(requests[at]);
                    nextReq += reqPeriod;
                    continue;
                }
                long due = end;
                if (rate > 0) due = Math.min(due, nextRc);
                if (reqPeriod > 0) due = Math.min(due, nextReq);
                pause(due - now);
            }
        } finally {
            end = Math.min(end, System.nanoTime());
            // the queue empties: no answer for the quiet time (also when stopped - the end's own calls must get through)
            long quiet = settings.quietMs * 1_000_000L, giveUp = System.nanoTime() + DRAIN_MAX_MS * 1_000_000L;
            while (System.nanoTime() - p.lastAnswerNs(end) < quiet && System.nanoTime() < giveUp && readerGone == null) Thread.sleep(5);
            pairing = null;
        }
        if (readerGone != null) throw readerGone;
        return summary(rate, p.sent(), p.answers(), start, end, settings.lostMs, Math.max(0, p.lastAnswerNs(end) - end));
    }

    /**
     * The end, once, also from the shutdown hook (Ctrl+C) and after an error: STOP to every motor, the arming block as
     * it was found (taken off only when it was off then and the board reads disarmed), what the board says now.
     */
    String finish() {
        stop.set(true);
        synchronized (this) { // a window under way ends first (it sees the stop), its queue empties
            if (boardLeft != null) return boardLeft;
            String left;
            try {
                if (!touched) {
                    left = "Board left: as found (nothing written to it).";
                } else {
                    call(MotorTest.MSP_SET_MOTOR, MotorTest.setMotorPayload(new int[0]));
                    if (!blockedBefore && !MotorTest.armed(call(MSP_STATUS, null))) call(BetaflightUsb.MSP_SET_ARMING_DISABLED, new byte[]{0});
                    byte[] status = call(MSP_STATUS, null);
                    int flags = BetaflightUsb.armingDisableFlags(status);
                    boolean blocked = flags != -1 && (flags & BetaflightUsb.ARMING_DISABLED_MSP) != 0;
                    left = "Board left: " + (MotorTest.armed(status) ? "ARMED" : "disarmed") + ", arming block " + (blocked ? "on" : "off")
                            + (blocked == blockedBefore ? " (as before)." : " (NOT as before: unplug its USB, a restart takes the block off).");
                }
            } catch (IOException e) {
                left = "Board left: not known (" + e.getMessage() + "). Unplug its USB: a restart takes the arming block off.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                left = "Board left: not known (interrupted). Unplug its USB: a restart takes the arming block off.";
            }
            boardLeft = left;
            out.println(left);
            reader.interrupt();
            wire.close();
            return left;
        }
    }

    // ------------------------------------------------------------------ the wire

    private void readLoop() {
        MspFrames frames = new MspFrames();
        byte[] buf = new byte[1024];
        while (boardLeft == null) {
            int n;
            try {
                n = wire.read(buf);
            } catch (IOException e) {
                readerGone = e;
                return;
            }
            if (n < 0) {
                readerGone = new IOException("The flight controller disconnected");
                return;
            }
            if (n == 0) continue;
            long now = System.nanoTime();
            for (MspFrames.Frame f : frames.feed(buf, n)) {
                if (f.type == MspFrames.REQUEST) continue;
                if (f.code == MSP_STATUS && f.payload != null && MotorTest.armed(f.payload)) armedSeen = true;
                Pairing p = pairing;
                if (p != null) {
                    p.answer(f.code, f.isError(), now);
                } else {
                    answers.offer(f);
                }
            }
        }
    }

    private void send(byte[] frame) throws IOException {
        synchronized (writing) {
            wire.write(frame);
        }
    }

    /** A setup read or write outside the windows, MSP v2 as the phone's: the answer's payload. Tried three times. */
    private synchronized byte[] call(int code, byte[] payload) throws IOException, InterruptedException {
        if (boardLeft != null) throw new IOException("finished");
        for (int attempt = 1; ; attempt++) {
            answers.clear();
            send(MspFrames.v2Request(code, payload));
            long end = System.nanoTime() + CALL_TIMEOUT_MS * 1_000_000L;
            for (long left = end - System.nanoTime(); left > 0; left = end - System.nanoTime()) {
                MspFrames.Frame f = answers.poll(left, TimeUnit.NANOSECONDS);
                if (f == null) break;
                if (f.version != MspFrames.V2 || f.code != code) continue;
                if (f.isError()) throw new IOException("Betaflight refused MSP " + code);
                return f.payload == null ? new byte[0] : f.payload;
            }
            if (readerGone != null) throw readerGone;
            if (attempt == CALL_TRIES) throw new IOException("No answer to MSP " + code + " (" + CALL_TRIES + " tries)");
        }
    }

    /** The last part of a wait is spun: Windows may wake a sleeping thread only every 15.6 ms. */
    private static final long SPIN_NS = 20_000_000L;

    /**
     * A step of a wait of ns, the caller looking at the clock again after it. Windows' timer may wake a sleeping
     * thread only every 15.6 ms (a program in the background or at low priority gets no finer one: the first try of
     * this sent 98 stick frames a second when asked for 250), so only what is beyond the last 20 ms is slept and the
     * rest spun - a core busy while sticks go out.
     */
    static void pause(long ns) throws InterruptedException {
        if (ns > SPIN_NS) {
            Thread.sleep((ns - SPIN_NS) / 1_000_000L + 1);
        } else {
            Thread.onSpinWait();
        }
    }

    // ------------------------------------------------------------------ what it prints

    private void print(Window w) {
        out.println();
        out.println(String.format(Locale.ROOT, "%s, %.1f s:", w.rate() == 0 ? "No sticks (the requests alone)" : w.rate() + " sticks/s", w.lengthNs() / 1e9));
        if (w.sticks().sent() > 0) out.println(line("sticks", w.sticks(), w.lengthNs()));
        if (w.requests().sent() > 0) out.println(line("requests", w.requests(), w.lengthNs()));
        double rate = w.sticks().sent() / (w.lengthNs() / 1e9);
        if (w.rate() > 0 && rate < 0.9 * w.rate()) {
            out.println(String.format(Locale.ROOT, "  NOTE: this PC sent only %.0f sticks/s, not %d: not the rate asked for", rate, w.rate()));
        }
        out.println(String.format(Locale.ROOT, "  queue empty %.2f s after the last frame went%s", w.drainNs() / 1e9,
                w.strays() > 0 ? "; " + w.strays() + " answers matched nothing sent" : ""));
    }

    static String line(String what, Kind k, long lengthNs) {
        return String.format(Locale.ROOT, "  %-8s %5d sent (%.1f/s), %5d answered, %5d lost%s - answer after: %s", what, k.sent(),
                k.sent() / (lengthNs / 1e9), k.answered(), k.lost(), k.errors() > 0 ? " (" + k.errors() + " error answers)" : "", k.delays());
    }

    private void printSummary() {
        out.println();
        out.println("Summary (delays in ms, of each window's second half):");
        out.println(String.format(Locale.ROOT, "  %-9s %-16s %-14s %-14s %-14s %s", "sticks/s", "sticks answered", "stick median",
                "request median", "request 90 %", "requests lost"));
        for (Window w : windows) {
            Kind s = w.sticks(), r = w.requests();
            out.println(String.format(Locale.ROOT, "  %-9d %-16s %-14s %-14s %-14s %s", w.rate(),
                    s.sent() == 0 ? "-" : s.answered() + " of " + s.sent(), median(s), median(r),
                    r.longest().count() == 0 ? "-" : range(r.shortest().p90(), r.longest().p90()), r.sent() == 0 ? "-" : r.lost() + " of " + r.sent()));
        }
    }

    private static String median(Kind k) {
        return k.longest().count() == 0 ? "-" : range(k.shortest().median(), k.longest().median());
    }

    // ------------------------------------------------------------------ by hand

    public static void main(String[] args) throws Exception {
        String wanted = arg(args, 0);
        Settings s;
        try {
            s = Settings.parse(arg(args, 1), arg(args, 2), arg(args, 3), arg(args, 4));
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            return;
        }
        List<BetaflightUsb.Port> ports = BetaflightUsb.findFlightControllers();
        System.out.println("Flight controllers: " + ports);
        if (wanted.isEmpty() && ports.size() > 1) {
            System.out.println("More than one: say which with -Pport=COM9.");
            return;
        }
        BetaflightUsb.Port port = ports.stream().filter(p -> wanted.isEmpty() || p.systemName().equalsIgnoreCase(wanted)).findFirst().orElse(null);
        if (port == null) {
            System.out.println("None" + (wanted.isEmpty() ? "" : " on " + wanted) + ": USB in?");
            return;
        }
        SerialPort sp = SerialPort.getCommPort(port.systemName()); // as BetaflightUsb.open
        sp.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        sp.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 1000);
        if (!sp.openPort()) {
            System.out.println(port.systemName() + " is in use - close Betaflight Configurator and DRAUPNE Link's Drone setup, and retry.");
            return;
        }
        sp.flushIOBuffers();
        System.out.println(port.systemName() + ":");
        MspRateProbe probe = new MspRateProbe(new PortWire(sp), s, System.out);
        Runtime.getRuntime().addShutdownHook(new Thread(probe::finish, "mspRateProbe-end"));
        Thread enter = new Thread(() -> {
            try {
                if (System.in.read() >= 0) {
                    System.out.println("Stopping...");
                    probe.stop();
                }
            } catch (IOException ignored) {
                // no console: no Enter
            }
        }, "mspRateProbe-enter");
        enter.setDaemon(true);
        enter.start();
        probe.run();
    }

    private static String arg(String[] args, int i) {
        return args.length > i && args[i] != null ? args[i].trim() : "";
    }

    /** The real port, opened as BetaflightUsb opens it; read and written from two threads. */
    private static final class PortWire implements BetaflightUsb.Wire {
        private final SerialPort port;

        PortWire(SerialPort port) {
            this.port = port;
        }

        @Override
        public int read(byte[] buf) {
            return port.readBytes(buf, buf.length);
        }

        @Override
        public int available() {
            return port.bytesAvailable();
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (port.writeBytes(data, data.length) != data.length) throw new IOException("Could not write to the flight controller");
        }

        @Override
        public boolean isOpen() {
            return port.isOpen();
        }

        @Override
        public void close() {
            if (port.isOpen()) port.closePort();
        }
    }
}
