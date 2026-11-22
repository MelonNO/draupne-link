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
        Result standIn = de.droiddrone.pc.Outside.run(command); // the test bench's answer; null always in the app itself
        if (standIn != null) return standIn;
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
     * Windows' own tools (netsh, powercfg, powershell, explorer, ...) by their full path - System32, PowerShell's
     * folder in it, the Windows folder - so they run even when the app was started with an unusual PATH, e.g. by an
     * installer or a scheduled task, and the program that runs is Windows' own, whatever lies beside the app or in
     * the folder it was started from. A command that begins with a full path is kept as it is. Nothing is started by
     * a bare name: a name in none of Windows' folders, or a path that is not a full one, is no command.
     * @throws IOException for a program that is neither a full path nor one of Windows' own
     */
    public static List<String> withFullPath(List<String> command) throws IOException {
        String name = command.get(0);
        if (isFullPath(name)) return command;
        Path tool = windowsTool(name);
        if (tool == null) throw new IOException(name + ": not a full path and not one of Windows' own programs - not started");
        List<String> full = new ArrayList<>(command);
        full.set(0, tool.toString());
        return full;
    }

    /** C:\..., \\server\..., and outside Windows /... */
    private static boolean isFullPath(String name) {
        return name.matches("[A-Za-z]:[\\\\/].*") || name.startsWith("\\\\")
                || name.startsWith("/") && !System.getProperty("os.name", "").startsWith("Windows");
    }

    /** One of Windows' own programs by its name ("netsh", "explorer.exe"): its full path, or null when Windows' folders have none. */
    static Path windowsTool(String name) {
        if (name == null || name.isBlank() || name.contains("\\") || name.contains("/") || name.contains(":")) return null;
        String exe = name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name : name + ".exe";
        Path windows;
        try {
            windows = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"));
            if (!windows.isAbsolute()) windows = Path.of("C:\\Windows");
            Path system32 = windows.resolve("System32");
            for (Path candidate : List.of(system32.resolve(exe), system32.resolve("WindowsPowerShell").resolve("v1.0").resolve(exe),
                    windows.resolve(exe))) {
                if (Files.isRegularFile(candidate)) return candidate;
            }
        } catch (java.nio.file.InvalidPathException e) {
            // a name no file can have
        }
        return null;
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
        return powershell(timeout, elevatedLauncher(script));
    }

    /** The script that starts the elevated one: PowerShell by its full path there too (Windows' own, {@link #POWERSHELL_IN_SCRIPT}). */
    static String elevatedLauncher(String script) {
        return "Start-Process -FilePath " + POWERSHELL_IN_SCRIPT + " -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList "
                + "'-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','" + encode(script) + "'";
    }

    /** In a PowerShell script: Windows' own PowerShell by its full path. */
    static final String POWERSHELL_IN_SCRIPT = "(Join-Path $env:SystemRoot 'System32\\WindowsPowerShell\\v1.0\\powershell.exe')";
    /** In a PowerShell script: a line that sets $netsh to Windows' own netsh by its full path; then "& $netsh ...". */
    static final String NETSH_IN_SCRIPT = "$netsh = Join-Path $env:SystemRoot 'System32\\netsh.exe'";

    static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    /**
     * Quotes a value for a single-quoted PowerShell string. PowerShell takes the typographic single quotes (U+2018 -
     * U+201B) as quotes too, so each is doubled like ' (review 1.0.53: a folder such as "Kari’s PC" ended the string,
     * and the script - the update helper, the Tailscale install - failed).
     */
    public static String psQuote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' || (c >= '‘' && c <= '‛')) out.append(c);
            out.append(c);
        }
        return out.append('\'').toString();
    }
}
