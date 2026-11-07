package de.droiddrone.pc.setup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drone setup's write to Betaflight's CLI, all or nothing (1.0.49). The commands go one by one; the first that
 * Betaflight refuses (###ERROR), or whose echo isn't what was sent (an "aux" line: see {@link ModeSwitch#echoProblem}),
 * stops the write there. After the last one the modes are read back ("aux") and every slot written is compared. Only a
 * write that got through all of it may be saved: otherwise the caller leaves the CLI with "exit", Betaflight drops
 * every change since the CLI opened, and the board is exactly as it was.
 */
public final class CliWrite {
    /** One CLI command and what Betaflight printed (BetaflightUsb.command, or a test's Betaflight). */
    @FunctionalInterface
    public interface Cli {
        String command(String command) throws IOException;
    }

    /**
     * @param stopped  why the write stopped and must not be saved; null = every command taken as sent
     * @param auxAfter Betaflight's "aux" read back after the write ("" when no aux command was sent)
     */
    public record Result(String stopped, String auxAfter) {
        public boolean complete() {
            return stopped == null;
        }
    }

    private CliWrite() {
    }

    /** Sends the commands in order, each logged with Betaflight's answer; stops at the first that isn't taken as sent. */
    public static Result run(Cli cli, List<String> commands, Consumer<String> log) throws IOException {
        List<String> auxSent = new ArrayList<>();
        for (String cmd : commands) {
            String reply = cli.command(cmd);
            log.accept("Betaflight CLI: " + cmd + " -> " + brief(reply));
            if (BetaflightPlan.refused(reply)) {
                return new Result("Betaflight refused \"" + cmd + "\": " + firstLine(reply), "");
            }
            String echo = ModeSwitch.echoProblem(cmd, reply);
            if (echo != null) return new Result("Betaflight didn't take \"" + cmd + "\" as sent (" + echo + ")", "");
            if (cmd.startsWith("aux ")) auxSent.add(cmd);
        }
        if (auxSent.isEmpty()) return new Result(null, "");
        String after = cli.command("aux");
        String problem = ModeSwitch.readBackProblem(auxSent, after);
        return new Result(problem == null ? null : "Betaflight's modes after the write aren't as sent (" + problem + ")", after);
    }

    /**
     * A "diff all" that can be gone back to: Betaflight's "# version" header (4.4, 4.5 and 2025.12 all start with it)
     * and no error in it. Anything else is no backup - and with no backup nothing is written.
     */
    public static boolean usableBackup(String diff) {
        return diff != null && diff.contains("# version") && !BetaflightPlan.refused(diff);
    }

    /** A CLI reply's first line, short, for the log; "(ok)" when Betaflight printed nothing. */
    public static String brief(String reply) {
        String line = firstLine(reply);
        if (line.isEmpty()) return "(ok)";
        return line.length() > 80 ? line.substring(0, 77) + "..." : line;
    }

    private static String firstLine(String reply) {
        return reply.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse("");
    }
}
