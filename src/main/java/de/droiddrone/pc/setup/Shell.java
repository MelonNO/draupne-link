package de.droiddrone.pc.setup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
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
            ProcessBuilder builder = new ProcessBuilder(command);
            if (mergeErrors) {
                builder.redirectErrorStream(true);
            } else {
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            }
            Process process = builder.start();
            process.getOutputStream().close();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, output.getNow("") + "\n(timed out)");
            }
            return new Result(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            return new Result(-1, e.toString());
        }
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
