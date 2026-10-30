package de.droiddrone.pc.setup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs Windows commands with a timeout and captures their output. Never call from the Swing thread. */
public final class Shell {
    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    private static final Charset CONSOLE = consoleCharset();

    private Shell() {
    }

    private static Charset consoleCharset() {
        try {
            return Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    public static Result run(Duration timeout, String... command) {
        return run(timeout, List.of(command));
    }

    public static Result run(Duration timeout, List<String> command) {
        return run(timeout, command, true);
    }

    private static Result run(Duration timeout, List<String> command, boolean mergeErrors) {
        try {
            ProcessBuilder builder = new ProcessBuilder(withFullPath(command));
            if (mergeErrors) {
                builder.redirectErrorStream(true);
            } else {
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            }
            Process process = builder.start();
            process.getOutputStream().close();
            // its own thread: on the shared pool, a long install's output would hold up quick checks (tailscale status)
            CompletableFuture<String> output = new CompletableFuture<>();
            Thread reader = new Thread(() -> output.complete(read(process.getInputStream())), "shellOutput");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, output.getNow("") + "\n(timed out)");
            }
            return new Result(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            return new Result(-1, e.toString());
        }
    }

    /**
     * Windows' own tools (netsh, powercfg, powershell, schtasks, ...) by their full path in System32, so they run
     * even when the app was started with an unusual PATH, e.g. by an installer or a scheduled task.
     */
    static List<String> withFullPath(List<String> command) {
        String name = command.get(0);
        if (name.contains("\\") || name.contains("/")) return command;
        String exe = name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name : name + ".exe";
        Path system32 = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32");
        for (Path candidate : List.of(system32.resolve(exe), system32.resolve("WindowsPowerShell").resolve("v1.0").resolve(exe))) {
            if (Files.isRegularFile(candidate)) {
                List<String> full = new ArrayList<>(command);
                full.set(0, candidate.toString());
                return full;
            }
        }
        return command;
    }

    private static String read(InputStream in) {
        try (in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toString(CONSOLE);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Runs a PowerShell script passed as -EncodedCommand, so no quoting problems. With redirected output PowerShell
     * writes progress records to stderr as CLIXML, so progress is switched off and stderr is not mixed into the output.
     */
    public static Result powershell(Duration timeout, String script) {
        return run(timeout, List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-OutputFormat", "Text", "-EncodedCommand", encode("$ProgressPreference = 'SilentlyContinue'\n" + script)), false);
    }

    /**
     * Runs a PowerShell script as administrator: Windows shows its UAC prompt. The elevated script's own exit code is
     * not available, so callers re-check the state afterwards. Exit code != 0 here usually means the prompt was declined.
     */
    public static Result powershellElevated(Duration timeout, String script) {
        String launcher = "Start-Process powershell.exe -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList "
                + "'-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','" + encode(script) + "'";
        return powershell(timeout, launcher);
    }

    static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    /** Quotes a value for a single-quoted PowerShell string. */
    public static String psQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
