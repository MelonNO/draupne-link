package de.droiddrone.bench.hw;

import de.droiddrone.bench.BadLink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The bad-network box on the real phone (R8, {@code HwLinkMatrixScenario}): its cells, its two tables' rows, and the
 * rules its armed part keeps - everything of it that needs no phone and no board, so the bench's own tests hold it
 * before the scenario is trusted with the real ones.
 */
final class HwLinkMatrix {
    private HwLinkMatrix() {
    }

    // ------------------------------------------------------------------ the cells

    /** A steady cell: the network bad in one way from before the connect, measured this long once the sticks reach the board. */
    record Steady(BadLink net, long measureMs) {
        String name() {
            return "steady " + net.name;
        }
    }

    /** A cut cell on a good network: nothing that way for so long. */
    record Cut(long ms, BadLink.Way way) {
        String name() {
            return "cut " + ms / 1000 + " s " + wayWords(way);
        }
    }

    static String wayWords(BadLink.Way way) {
        return switch (way) {
            case BOTH -> "both ways";
            case TO_PHONE -> "PC to phone only";
            case TO_PC -> "phone to PC only";
        };
    }

    /** How long a steady cell is measured once its sticks reach the board; in a quick run. */
    static final long STEADY_MS = 40_000, STEADY_QUICK_MS = 20_000;
    /** The cuts' lengths: under the board's RXLOSS-to-failsafe time, around it, over it, and a minute. */
    static final long[] CUT_MS = {1000, 3000, 10_000, 60_000};
    /** The armed part's cut is the cell's, but no longer than this: its window is 10 s. */
    static final long ARMED_CUT_MAX_MS = 12_000;
    /** With the board's own failsafe let run (board.letFailsafeRun) a longer cut would outlast the window with the board armed. */
    static final long LET_RUN_CUT_MAX_MS = 3000;
    /** The arm window each armed part asks the keeper for (the keeper's rules: 10 s at most here). */
    static final int WINDOW_S = 10;

    /**
     * The LinkMatrix's steady networks that make sense over a real phone - delay, jitter, loss, bursts, a narrow band
     * (a real network seldom doubles packets: that one is left out). Quick: the clean one and one bar.
     */
    static List<Steady> steady(long seed, boolean quick) {
        List<BadLink> all = BadLink.steady(seed, false).stream().filter(n -> !n.name.startsWith("doubled")).toList();
        if (quick) return List.of(new Steady(named(all, "clean"), STEADY_QUICK_MS), new Steady(named(all, "one bar"), STEADY_QUICK_MS));
        List<Steady> out = new ArrayList<>();
        for (BadLink n : all) out.add(new Steady(n, STEADY_MS));
        return out;
    }

    private static BadLink named(List<BadLink> all, String start) {
        for (BadLink n : all) if (n.name.startsWith(start)) return n;
        throw new IllegalStateException("BadLink.steady has no network called \"" + start + " ...\"");
    }

    /** 1, 3, 10 and 60 s, each both ways, PC to phone only, phone to PC only. Quick: 1 s and 10 s both ways (a short cut and a long one). */
    static List<Cut> cuts(boolean quick) {
        if (quick) return List.of(new Cut(1000, BadLink.Way.BOTH), new Cut(10_000, BadLink.Way.BOTH));
        List<Cut> out = new ArrayList<>();
        for (long ms : CUT_MS) for (BadLink.Way way : List.of(BadLink.Way.BOTH, BadLink.Way.TO_PHONE, BadLink.Way.TO_PC)) out.add(new Cut(ms, way));
        return out;
    }

    static long armedCutMs(Cut cut) {
        return Math.min(cut.ms(), ARMED_CUT_MAX_MS);
    }

    /** What a cell costs beside its own measuring and cut: PILOT UAV to the sticks at the board, the way back, STANDBY; an armed part's window. */
    static final long START_MS = 90_000, STEADY_OVERHEAD_MS = 25_000, CUT_OVERHEAD_MS = 40_000, ARMED_OVERHEAD_MS = 20_000;

    /** About how long the table takes (for the film, and to hold it to its hour). */
    static long estimateMs(List<Steady> steady, List<Cut> cuts, boolean armed) {
        long ms = START_MS;
        for (Steady s : steady) ms += s.measureMs() + STEADY_OVERHEAD_MS;
        for (Cut c : cuts) ms += c.ms() + CUT_OVERHEAD_MS + (armed ? armedCutMs(c) + ARMED_OVERHEAD_MS : 0);
        return ms;
    }

    /**
     * New sessions the phone may begin for one cut (F-001, the pilot's rule): one when the link goes, one when it is
     * back. A phone that hears the PC while the PC hears nothing gets a new id every {@code UNHEARD_KEEP_MS} (4 s) as well.
     */
    static long sessionsAtMost(Cut cut) {
        return 2 + (cut.way() == BadLink.Way.TO_PC ? cut.ms() / de.droiddrone.common.SessionGate.Ids.UNHEARD_KEEP_MS : 0);
    }

    /**
     * Stick frames a second at the board from which they are the pilot's: the PC sends 250 a second
     * ({@code ControlLink.DEFAULT_RC_RATE}; 188 measured at the board, 2026-11-23), while the phone's own idle sticks
     * - fed to a disarmed board 300 ms after the PC's last frame, until the phone counts the link lost (pc.33, pc.54) -
     * come 20 a second.
     */
    static final long PILOT_STICKS_PER_S = 80;

    /** So many stick frames in so many ms at the board are the pilot's sticks (not the phone's idle ones). */
    static boolean pilotsSticks(long frames, long ms) {
        return frames > 0 && ms > 0 && frames * 1000 >= PILOT_STICKS_PER_S * ms;
    }

    // ------------------------------------------------------------------ the tables

    static final List<String> STEADY_COLUMNS = List.of("cell", "ready ms", "ready %", "left READY", "ping ms median", "ping ms max",
            "telemetry gap ms max", "picture fps mean", "picture %", "sticks/s at board", "stick gap ms max", "stick gap ms p99", "board RXLOSS %",
            "board armed", "ready again ms", "phone new sessions", "phone socket anew", "phone errors", "network counts", "verdict", "what");
    static final List<String> CUT_COLUMNS = List.of("cell", "cut ms", "way", "ready ms", "left READY", "ready again ms", "picture stopped",
            "picture again ms", "sticks again ms", "stick gap ms max", "board RXLOSS at ms", "board RXLOSS for ms", "board FAILSAFE", "board armed",
            "phone new sessions", "phone socket anew", "phone errors", "armed part", "armed cut ms", "armed: stick gap ms", "armed: board said",
            "armed: ended by", "armed: re-arm", "network counts", "verdict", "what");

    /** One row of a table: every column there from the start ("-": not measured), so a cell that stopped half way writes a whole row. */
    static final class Row {
        private final LinkedHashMap<String, String> values = new LinkedHashMap<>();

        Row(List<String> columns) {
            for (String c : columns) values.put(c, "-");
        }

        Row put(String column, Object value) {
            if (!values.containsKey(column)) throw new IllegalArgumentException("No column \"" + column + "\" in this table: " + values.keySet());
            values.put(column, clean(value));
            return this;
        }

        String get(String column) {
            return values.get(column);
        }

        String header() {
            return String.join("\t", values.keySet());
        }

        String line() {
            return String.join("\t", values.values());
        }
    }

    /** A value as one cell of a tab-separated line: no tab, no line break; nothing at all is "-". */
    static String clean(Object value) {
        String s = String.valueOf(value).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        return s.isEmpty() ? "-" : s;
    }

    /**
     * The row appended to {@code <table>.tsv} in that folder, the header first when the file is new. A file whose
     * header is another table's is never written into (IllegalStateException): two tables in one would be read wrong.
     */
    static Path append(Path folder, String table, Row row) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve(table + ".tsv");
        if (!Files.exists(file) || Files.size(file) == 0) {
            Files.writeString(file, row.header() + "\n", StandardCharsets.UTF_8);
        } else {
            String first;
            try (java.io.BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                first = in.readLine();
            }
            if (!row.header().equals(first)) throw new IllegalStateException(file + " has other columns than this row: " + first);
        }
        Files.writeString(file, row.line() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return file;
    }

    /** A time as the tables write it: -1 is "none" (it did not come within the time given). */
    static String ms(long ms) {
        return ms < 0 ? "none" : Long.toString(ms);
    }

    static String one(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }

    // ------------------------------------------------------------------ the armed part

    /**
     * Whether a cut cell's armed part runs: null - it does; else why not, for the film and the row. It runs only on the
     * hardware file's word (board.mayArm=true), never after the rig's guard has stopped the run's flying, and - where
     * the keeper lets the board's own failsafe run - only for a cut that ends well inside the window.
     *
     * @param stickLimitMs the keeper's limit on a window without sticks (its state's stickLimitMs): 0 - it lets the
     *                     board's failsafe run; -1 - not known (the keeper then opens no window, and says why)
     */
    static String armedSkip(boolean mayArm, String guardStopped, int stickLimitMs, Cut cut) {
        if (!mayArm) return "the hardware file does not say board.mayArm=true";
        if (guardStopped != null) return "the hardware guard has stopped the run's flying (" + guardStopped + ")";
        if (stickLimitMs == 0 && armedCutMs(cut) > LET_RUN_CUT_MAX_MS) {
            return "the keeper lets the board's own failsafe run (board.letFailsafeRun): a cut of " + cut.ms() / 1000 + " s would outlast the "
                    + WINDOW_S + " s window with the board armed, and the keeper trips on that";
        }
        return null;
    }

    /** Under this the board kept its sticks: Betaflight says RXLOSS a tenth of a second after the last frame. */
    static final long KEPT_GAP_MS = 250;
    /** What a stick gap must be under, or over, a limit by before the judge holds the board to it. */
    static final long MARGIN_MS = 700;

    /**
     * What the board did in the armed part of a cut cell, as the keeper read it.
     *
     * @param stickGapMs   the longest time no stick frame reached the board, from the arm to after the cut (the keeper's figures)
     * @param failsafeAtMs the board's failsafe_delay in ms: how long Betaflight holds before its failsafe's stage 2
     * @param stickLimitMs the keeper's limit (its state's stickLimitMs): over 0 it ends a window on RXLOSS, or this long
     *                     after the last stick frame; 0 it lets the board's failsafe run
     * @param failsafeSeen the board said FAILSAFE (its arming block: stage 2) after the cut began
     * @param rxlossSeen   the board said RXLOSS after the cut began
     * @param keeperEnded  why the keeper ended the window itself; null: it did not
     * @param disarmedMs   ms after the cut began that the board said disarmed, before the scenario's own ARM off; -1: it did not
     * @param rearmed      it said armed again after that, before the scenario's own ARM off
     * @param switchOn     the radio's ARM switch was still on after the disarm (the rig's guard takes it off when the keeper ends a window)
     */
    record ArmedFacts(double stickGapMs, long failsafeAtMs, long stickLimitMs, boolean failsafeSeen, boolean rxlossSeen, String keeperEnded,
                      long disarmedMs, boolean rearmed, boolean switchOn) {
    }

    /** The judge's word: who ended the armed time, what is wrong (each one a failed check), what is only said. */
    record ArmedVerdict(String endedBy, List<String> wrong, List<String> notes) {
    }

    /**
     * The armed part's rules. The board kept its sticks: it stays armed and says no FAILSAFE. They stopped for a short
     * time (under its failsafe_delay): no FAILSAFE - and where the keeper lets the failsafe run, still armed. They
     * stopped for longer than the keeper's limit (or, with the failsafe let run, than failsafe_delay): disarmed, or
     * FAILSAFE - a failsafe or the keeper ending the window. Either way: no re-arm once it was disarmed.
     */
    static ArmedVerdict judge(ArmedFacts f) {
        List<String> wrong = new ArrayList<>(), notes = new ArrayList<>();
        boolean disarmed = f.disarmedMs() >= 0;
        long gap = Math.round(f.stickGapMs());
        String endedBy = !disarmed ? (f.failsafeSeen() ? "the board's own failsafe (armed still, in its stage 2)" : "nothing: armed until the ARM switch went off")
                : f.keeperEnded() != null ? "the keeper: " + f.keeperEnded()
                : f.failsafeSeen() ? "the board's own failsafe" : "disarmed - by what, the keeper's word does not say";
        if (f.stickGapMs() < KEPT_GAP_MS) {
            notes.add("the board kept its sticks through the cut (the largest gap " + gap + " ms)");
            if (disarmed) wrong.add("the board kept its sticks (the largest gap " + gap + " ms) and was disarmed " + f.disarmedMs() + " ms into the cut - " + endedBy);
            if (f.failsafeSeen()) wrong.add("the board said FAILSAFE though no stick gap was longer than " + gap + " ms");
        } else {
            boolean shortCut = f.stickGapMs() < f.failsafeAtMs() - MARGIN_MS;
            long endsAt = f.stickLimitMs() > 0 ? Math.min(f.stickLimitMs(), f.failsafeAtMs()) : f.failsafeAtMs();
            boolean longCut = f.stickGapMs() >= endsAt + MARGIN_MS;
            if (shortCut && f.failsafeSeen()) {
                wrong.add("no failsafe for a short cut: the board said FAILSAFE after " + gap + " ms without sticks, its failsafe_delay is " + f.failsafeAtMs() + " ms");
            }
            if (shortCut && f.stickLimitMs() == 0 && disarmed) {
                wrong.add("a short cut (" + gap + " ms without sticks, failsafe_delay " + f.failsafeAtMs() + " ms) disarmed the board - " + endedBy);
            }
            if (longCut && !disarmed && !f.failsafeSeen()) {
                wrong.add("no sticks at the board for " + gap + " ms and the board still armed: no failsafe, and no end of the keeper's (it ends a window "
                        + (f.stickLimitMs() > 0 ? f.stickLimitMs() + " ms after the last stick frame at the latest" : "only by its time") + ")");
            }
            if (f.keeperEnded() != null) {
                notes.add("the keeper ended the window itself (" + f.keeperEnded() + ")" + (f.stickLimitMs() > 0
                        ? " - its rule on a real board: the board's own failsafe is not let run" : ""));
            }
            if (f.failsafeSeen()) notes.add("the board said FAILSAFE");
            if (!f.rxlossSeen()) notes.add("the board was not seen to say RXLOSS though its sticks stopped for " + gap + " ms");
        }
        if (f.rearmed()) {
            wrong.add("RE-ARMED by itself after it was disarmed in the cut" + (f.switchOn() ? ", the ARM switch never off" : ""));
        } else if (disarmed) {
            notes.add("no re-arm: disarmed, and it stayed so" + (f.switchOn() ? " with the ARM switch still on"
                    : " - the switch went off with the keeper's end of the window (the rig's guard), and the keeper's lock is on the board"));
        }
        return new ArmedVerdict(endedBy, List.copyOf(wrong), List.copyOf(notes));
    }
}
