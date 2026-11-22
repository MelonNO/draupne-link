package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.CliWrite;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Betaflight's motor_output_limit, written to a flight controller on USB (the hardware rig, R3):
 * <pre>
 * gradlew boardCap -Pport=COM9 -Plimit=15       15 % in every PID profile, saved, read back
 * </pre>
 * The hardware rig's third throttle layer, and the only one that holds whatever makes the throttle: the made-up radio
 * gives no more than 1100 and the flight controller keeper passes no stick frame over 1100 - but a throttle the flight
 * controller makes itself (its failsafe: a landing, a GPS rescue) is in no stick frame. With this limit Betaflight
 * gives its motors no more than so many per cent of their range, whoever asks. The keeper opens no arm window on a
 * real board whose limit is over the hardware file's board.outputLimitMax (15).
 * <p>
 * motor_output_limit is a PID profile's own setting, and Betaflight changes profiles by itself (a pack of other cells,
 * a stick command): so it is written in every profile the board has, and the profile that was in use is in use
 * afterwards. Written through the app's own all-or-nothing CLI write ({@link CliWrite}) - one line refused, and the
 * CLI is left with "exit": nothing is saved - with "save" last (Betaflight restarts), then read back in a second
 * visit. An armed board is not written to. Nothing else of the board is changed: {@code gradlew boardRestore} with a
 * snapshot taken before puts the old values back, and so does this tool with -Plimit=100, Betaflight's own.
 */
public final class BoardCap {
    private static final Pattern VALUE = Pattern.compile("(?m)^motor_output_limit = (\\d+)\\s*$"), PROFILE = Pattern.compile("(?m)^profile (\\d+)\\s*$");
    /** No Betaflight has more PID profiles than this. */
    private static final int MOST_PROFILES = 8;

    /**
     * @param refused why nothing was sent to the board, or null
     * @param saved   the limit was written in every profile and "save" was said
     * @param stopped why the write ended without a save (the board is then as it was), or why it could not be read back; or null
     * @param before  the limit in each PID profile before (0 first)
     * @param after   the same read back after the save; null: not read back
     */
    public record Result(String refused, boolean saved, String stopped, List<Integer> before, List<Integer> after, int limit) {
        /** Every PID profile of the board has the limit, by the board's own word read back. */
        public boolean capped() {
            return refused == null && stopped == null && after != null && !after.isEmpty() && after.stream().allMatch(v -> v == limit);
        }

        /** 0: capped. 2: refused. 1: anything else. */
        public int exitCode() {
            return refused != null ? 2 : capped() ? 0 : 1;
        }

        /** One line for the log. */
        public String text() {
            if (refused != null) return "REFUSED, nothing written: " + refused;
            if (stopped != null && !saved) return "NOT CAPPED, and nothing saved: " + stopped;
            if (!capped()) return "NOT CAPPED: saved, but " + (stopped != null ? stopped : "read back as " + after) + ".";
            String every = "motor_output_limit = " + limit + " in each of the board's " + after.size() + " PID profiles";
            if (!saved) return "AS IT IS ALREADY: " + every + "; nothing written.";
            return "CAPPED: " + every + ", saved and read back (it was " + numbers(before) + "). Betaflight gives its motors no more than " + limit
                    + " % now, whoever asks. gradlew boardRestore puts a snapshot's own values back.";
        }

        private static String numbers(List<Integer> values) {
            return String.join(", ", values.stream().map(String::valueOf).toList());
        }
    }

    private BoardCap() {
    }

    /** Writes the limit to the board on this port and says how that went. Never throws: what went wrong is in the result. */
    public static Result run(BetaflightUsb.Port port, int limit, BoardPort.Timing timing, Consumer<String> log) {
        if (limit < 1 || limit > 100) return new Result("motor_output_limit is per cent, 1 to 100 - not " + limit, false, null, List.of(), null, limit);
        List<Integer> before;
        try (BetaflightUsb fc = BoardPort.open(port, timing, log)) {
            if (Msp.armed(fc.msp(Msp.STATUS, null))) return new Result("The board is armed. Disarm it first.", false, null, List.of(), null, limit);
            fc.enterCli();
            int inUse = profileInUse(fc);
            before = limits(fc, inUse);
            if (before.stream().allMatch(v -> v == limit)) {
                log.accept("motor_output_limit is " + limit + " in every PID profile already: nothing to write.");
                return new Result(null, false, null, before, before, limit); // closing says exit: nothing was typed that could be saved
            }
            List<String> commands = new ArrayList<>();
            for (int profile = 0; profile < before.size(); profile++) {
                commands.add("profile " + profile);
                commands.add("set motor_output_limit = " + limit);
            }
            commands.add("profile " + inUse); // the profile in use is saved with the rest: it has to be the one it was
            CliWrite.Result written = CliWrite.run(fc::command, commands, log);
            if (!written.complete()) return new Result(null, false, written.stopped(), before, null, limit); // closing says exit: Betaflight drops it all
            List<Integer> typed = limits(fc, inUse);
            if (!typed.stream().allMatch(v -> v == limit)) {
                return new Result(null, false, "Betaflight took every line and has " + typed + " all the same", before, null, limit);
            }
            fc.saveAndReboot();
        } catch (IOException | RuntimeException e) {
            return new Result(null, false, String.valueOf(e.getMessage()), List.of(), null, limit);
        }
        // read back, from what Betaflight kept: a second visit, after its restart
        try (BetaflightUsb fc = BoardPort.open(port, timing, log)) {
            fc.enterCli();
            return new Result(null, true, null, before, limits(fc, profileInUse(fc)), limit);
        } catch (IOException | RuntimeException e) {
            return new Result(null, true, "it could not be read back (" + e.getMessage() + ")", before, null, limit);
        }
    }

    /** The PID profile in use, as the CLI's "profile" says it. */
    private static int profileInUse(BetaflightUsb fc) throws IOException {
        String said = fc.command("profile");
        Matcher m = PROFILE.matcher(said.replace("\r", ""));
        if (!m.find()) throw new IOException("The CLI did not say which PID profile is in use: " + CliWrite.brief(said));
        return Integer.parseInt(m.group(1));
    }

    /** motor_output_limit in every PID profile the board has (0 first); the profile in use is selected again after. */
    private static List<Integer> limits(BetaflightUsb fc, int inUse) throws IOException {
        List<Integer> limits = new ArrayList<>();
        for (int profile = 0; profile < MOST_PROFILES; profile++) {
            if (BetaflightPlan.refused(fc.command("profile " + profile))) break; // one more than the board has
            String said = fc.command("get motor_output_limit");
            Matcher m = VALUE.matcher(said.replace("\r", ""));
            if (!m.find()) throw new IOException("The CLI did not say PID profile " + profile + "'s motor_output_limit: " + CliWrite.brief(said));
            limits.add(Integer.parseInt(m.group(1)));
        }
        if (limits.isEmpty()) throw new IOException("The CLI named no PID profile.");
        fc.command("profile " + inUse);
        return limits;
    }

    /** args: the port ("" = the one flight controller there is), the limit. Exit code 0 only when the limit is saved and read back. */
    public static void main(String[] args) {
        System.exit(byHand(args, System.out, BoardPort.Timing.REAL));
    }

    /** What main does, said to out. */
    static int byHand(String[] args, PrintStream out, BoardPort.Timing timing) {
        String wanted = args.length > 0 ? args[0].strip() : "", number = args.length > 1 ? args[1].strip() : "";
        if (number.isEmpty()) {
            out.println("Which limit? gradlew boardCap -Pport=" + (wanted.isEmpty() ? "COM9" : wanted) + " -Plimit=15   (per cent, 1 to 100; 100 is Betaflight's own)");
            return 2;
        }
        int limit;
        try {
            limit = Integer.parseInt(number);
        } catch (NumberFormatException e) {
            out.println("-Plimit=" + number + " is no number (per cent, 1 to 100).");
            return 2;
        }
        BetaflightUsb.Port port;
        try {
            port = BoardPort.find(wanted, timing);
        } catch (IOException e) {
            out.println("NOT CAPPED: " + e.getMessage());
            return 1;
        }
        out.println("motor_output_limit = " + limit + " for the flight controller on " + port + ": written in its CLI in every PID profile, saved (Betaflight restarts), read back.");
        out.println("Sound to expect: the board's own start-up tones at each restart; no motor turns.");
        Result r = run(port, limit, timing, s -> out.println("  " + s));
        out.println(r.text());
        if (r.refused() == null) BoardPort.isBack(port, timing, out); // the visits to the CLI restart Betaflight
        return r.exitCode();
    }
}
