package de.droiddrone.pc.setup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Drone setup's ESCS row (1.0.71): what the ESCs run and whether their own beacon will sound on a resting drone
 * ({@link EscFirmware}), read through the flight controller on USB ({@link EscLink}) - and that beacon switched off.
 * One call opens the port, does it and gives the port back; the row shows {@link Result#state} and {@link Result#text}.
 * <p>
 * F-080 (the test drone: Betaflight 4.4.1, four AM32 2.17): after a restart of the flight controller with the battery
 * in, a read that did not reach ESC 1 left motor 1 still - while ESC 1 went on sending its telemetry, so the drone
 * looked fine and armed. Only unplugging the battery brought it back. So an ESC that does not answer with the battery
 * in makes the row red, says so, and holds Drone setup not ready for that board ({@link Hold}) until a read in which
 * all answer. "battery on?" is said only when the board reads no battery.
 */
public final class EscCheck {
    /** Opens the flight controller's USB port. */
    public interface Opener {
        BetaflightUsb open() throws IOException;
    }

    /**
     * escs: each ESC as it is now (null when the flight controller could not be asked: problem says why).
     * failures: what went wrong switching a beacon off, an ESC each - said in full, one may ask for the motor test.
     * noBattery: an ESC did not answer and the board then read no battery - they had no power (not F-080).
     * board: the board's id (SetupConfirm.boardId), null when it does not say it.
     */
    public record Result(List<EscFirmware.Info> escs, String problem, List<String> failures, boolean noBattery, String board) {
        Result(List<EscFirmware.Info> escs, String problem, List<String> failures) {
            this(escs, problem, failures, false, null);
        }

        /** The ESCs that did not answer, by motor number (1 = motor 1). */
        public List<Integer> silent() {
            if (escs == null) return List.of();
            return escs.stream().filter(EscFirmware.Info::silent).map(i -> i.index() + 1).toList();
        }

        /** F-080: an ESC did not answer with the battery in (or not known to be out) - it may not turn now. */
        public boolean holds() {
            return !noBattery && !silent().isEmpty();
        }

        /** An ESC's own beacon is on and this app can switch it off - not while an ESC did not answer. */
        public boolean canSilence() {
            return escs != null && !holds() && escs.stream().anyMatch(EscFirmware.Info::canSilence);
        }

        /** The row's light: "OK", "WARN", "BAD" or "INFO" (CheckRow's states). */
        public String state() {
            if (!failures.isEmpty() || holds()) return "BAD";
            if (escs == null) return "WARN";
            boolean beeps = false, unanswered = false, unread = false;
            for (EscFirmware.Info i : escs) {
                beeps |= i.beeps();
                unanswered |= i.problem() != null;
                unread |= i.kind() == EscFirmware.Kind.ARM_OTHER;
            }
            return beeps || unanswered ? "WARN" : unread ? "INFO" : "OK";
        }

        /** The row's words (HTML, short). */
        public String text() {
            if (escs == null) return problem;
            List<Integer> silent = silent();
            boolean none = !escs.isEmpty() && silent.size() == escs.size();
            if (none && noBattery) return "No ESC answered. <b>Battery in</b>, props off, then <b>Read ESCs</b>.";
            StringBuilder b = new StringBuilder(none ? "" : EscFirmware.summary(escs) + ".");
            for (String f : failures) b.append("<br>").append(f);
            if (holds()) return b.append(b.length() == 0 ? "" : "<br>").append(silentLine(silent)).toString();
            if (!failures.isEmpty()) return b.toString();
            if (canSilence()) b.append("<br>Beeps at rest. <b>Beacon off</b> ends it.");
            else if (escs.stream().anyMatch(EscFirmware.Info::beeps)) b.append("<br>Beeps at rest: set Beacon Delay to Infinite in the ESC's configurator.");
            if (escs.stream().anyMatch(i -> i.kind() == EscFirmware.Kind.ARM_OTHER)) {
                b.append("<br>BLHeli_32: set Beacon Delay to Infinite in BLHeliSuite32.");
            }
            return b.toString();
        }
    }

    private EscCheck() {
    }

    /**
     * F-080's words, the same wherever they are shown: "ESC 1 did not answer - it may not turn now. Unplug the battery,
     * plug it in, <b>Read ESCs</b> again." (HTML; {@link #plain} for the log and the status line.)
     * @param escs the ESCs by motor number, at least one
     */
    public static String silentLine(List<Integer> escs) {
        String which = escs.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return "ESC " + which + " did not answer - " + (escs.size() == 1 ? "it" : "they")
                + " may not turn now. Unplug the battery, plug it in, <b>Read ESCs</b> again.";
    }

    /** A row's words without their HTML. */
    public static String plain(String html) {
        return html.replace("<br>", " ").replaceAll("<[^>]+>", "");
    }

    /**
     * The row after a read, with what is held for the board after it ({@link Hold#on}): a read that did not reach
     * every ESC keeps an earlier "did not answer" red, its own words first. {state, html}.
     */
    public static String[] row(Result r, List<Integer> held) {
        if (r.holds() || held.isEmpty()) return new String[]{r.state(), r.text()};
        return new String[]{"BAD", r.text() + "<br>" + silentLine(held)};
    }

    /**
     * Reads every ESC; with silence, switches the beacon off on each that has it on and can be written
     * (the old settings kept in backupDir). The port is closed again whatever happens.
     */
    public static Result run(Opener opener, boolean silence, Path backupDir, Consumer<String> log) {
        List<String> failures = new ArrayList<>();
        try (BetaflightUsb fc = opener.open()) {
            if (!fc.firmware().startsWith("BTFL")) return new Result(null, "The flight controller doesn't run Betaflight.", failures);
            String board = SetupConfirm.boardId(fc); // what a "did not answer" is kept for (Hold)
            try { // said at once, instead of after every ESC was asked in vain
                if (!fc.batteryState().present()) return new Result(null, "No battery. <b>Battery in</b>, props off, then <b>Read ESCs</b>.", failures);
            } catch (IOException e) {
                // a Betaflight that doesn't say: the ESCs will
            }
            List<EscFirmware.Info> all;
            try (EscLink escs = fc.escs(log)) {
                all = escs.infoAll();
                log.accept("ESCs: " + EscFirmware.summary(all) + ".");
                if (silence) {
                    for (int i = 0; i < all.size(); i++) {
                        EscFirmware.Info before = all.get(i);
                        if (!before.canSilence()) continue;
                        try {
                            all.set(i, escs.silence(i, backupDir));
                            log.accept("ESC " + (i + 1) + ": beacon " + before.beaconText() + " -> " + all.get(i).beaconText() + " (saved in the ESC).");
                        } catch (IOException e) {
                            failures.add(e.getMessage());
                            log.accept("ESC " + (i + 1) + ": beacon " + before.beaconText() + " -> off FAILED: " + e.getMessage());
                        }
                    }
                }
            }
            // F-080: one that did not answer is red - unless the board, back with MSP now, reads no battery
            boolean noBattery = false;
            if (all.stream().anyMatch(EscFirmware.Info::silent)) {
                try {
                    noBattery = !fc.batteryState().present();
                } catch (IOException e) {
                    // not known: as with the battery in
                }
                if (noBattery) all.replaceAll(i -> i.silent() ? EscFirmware.Info.failed(i.index(), EscFirmware.NO_ANSWER_NO_BATTERY) : i);
            }
            Result r = new Result(all, null, failures, noBattery, board);
            if (r.holds()) log.accept(plain(silentLine(r.silent())));
            return r;
        } catch (IOException e) {
            log.accept("ESCs: " + e.getMessage());
            return new Result(null, e.getMessage(), failures);
        }
    }

    /**
     * F-080: the ESCs that did not answer a read, by board (SetupConfirm.boardId; boards that do not say their id
     * share one place), kept - saved as {@link #KEY}, so closing Drone setup does not end it - until a read of that
     * board in which every ESC answers. A read that did not reach the ESCs, or found the battery out, changes nothing.
     */
    public static final class Hold {
        public static final String KEY = "drone.escsSilent";
        private static final String NO_ID = "?";
        private final Map<String, List<Integer>> held = new LinkedHashMap<>();

        /** From the saved value, "board=1,3;other=2"; anything else holds nothing. */
        public void restore(String saved) {
            held.clear();
            if (saved == null) return;
            for (String one : saved.split(";")) {
                int eq = one.lastIndexOf('=');
                if (eq <= 0) continue;
                List<Integer> escs = new ArrayList<>();
                for (String n : one.substring(eq + 1).split(",")) {
                    try {
                        int e = Integer.parseInt(n.trim());
                        if (e >= 1 && e <= 255 && !escs.contains(e)) escs.add(e);
                    } catch (NumberFormatException ignored) {
                        // not a motor number: left out
                    }
                }
                if (!escs.isEmpty()) held.put(one.substring(0, eq).trim(), List.copyOf(escs));
            }
        }

        /** What {@link #KEY} holds; "" for nothing held. */
        public String saved() {
            return held.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().stream().map(String::valueOf)
                    .collect(Collectors.joining(","))).collect(Collectors.joining(";"));
        }

        /**
         * After a read of this board (its id; null when it does not say it): held when an ESC did not answer with the
         * battery in, let go when all answered.
         * @return true if that changed anything (then save {@link #saved()})
         */
        public boolean after(String board, Result r) {
            if (r.escs() == null) return false; // the ESCs were not reached
            String key = board == null ? NO_ID : board;
            if (r.holds()) return !r.silent().equals(held.put(key, r.silent()));
            if (r.silent().isEmpty()) return held.remove(key) != null;
            return false; // the battery out: they had no power, which says nothing either way
        }

        /** The ESCs held for this board (its id, or null), by motor number; empty: none. */
        public List<Integer> on(String board) {
            return held.getOrDefault(board == null ? NO_ID : board, List.of());
        }
    }
}
