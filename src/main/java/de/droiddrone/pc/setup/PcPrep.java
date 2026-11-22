package de.droiddrone.pc.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The PC tasks DRAUPNE Link's installer does, so nobody has to click through them in the setup wizard. The
 * installer runs them as administrator: {@link #prepare} after installing, {@link #cleanup} when uninstalling.
 * <p>
 * Tailscale can't be installed from inside the installer, because Windows runs one install at a time. So prepare
 * registers a one-off scheduled task that installs it as soon as the installer has finished
 * ({@link #installTailscale}) and then deletes itself.
 * <p>
 * Everything is best effort: a failure is written to setup.log and the setup wizard checks and fixes it later.
 */
public final class PcPrep {
    static final String TAILSCALE_TASK = "DRAUPNE Link - install Tailscale";
    /** The installer's upgrade code (build.gradle); installers before 0.5.3 installed per user with the same code. */
    static final String UPGRADE_CODE = "{5D2F1D3E-6A0B-4C9E-9F53-2B7C1A8E4D61}";
    private static final String SCHEDULED = "Tailscale: install scheduled";
    private static final Duration TAILSCALE_WAIT = Duration.ofMinutes(20);

    private PcPrep() {
    }

    /** As administrator, from the installer: firewall rules, power plan, and Tailscale (scheduled). */
    public static void prepare(Path launcher, int linkPort, int apkPort) {
        log("Preparing this PC");
        log("Firewall rules: " + outcome(WindowsSetup.addFirewallRulesAsAdmin(linkPort, apkPort)));
        WindowsSetup.PowerPlan plan = WindowsSetup.activePowerPlan();
        if (plan != null && plan.isHighPerformance()) {
            log("Power plan: already " + plan.name());
        } else {
            log("Power plan: High performance - " + outcome(WindowsSetup.useHighPerformance()));
        }
        if (Tailscale.exe() != null) {
            log("Tailscale: already installed");
        } else if (launcher == null) {
            log("Tailscale: not scheduled (unknown program path)");
        } else {
            Shell.Result r = scheduleTailscale(launcher);
            log(r.ok() ? SCHEDULED : "Tailscale: could not schedule the install - " + outcome(r));
        }
    }

    private static Shell.Result scheduleTailscale(Path launcher) {
        String task = Shell.psQuote(TAILSCALE_TASK);
        return Shell.powershell(Duration.ofMinutes(1), String.join("\n",
                "$a = New-ScheduledTaskAction -Execute " + Shell.psQuote(launcher.toString()) + " -Argument 'install-tailscale'",
                "$p = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest",
                "$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries "
                        + "-ExecutionTimeLimit (New-TimeSpan -Minutes 40)",
                "Register-ScheduledTask -TaskName " + task + " -Action $a -Principal $p -Settings $s -Force -ErrorAction Stop | Out-Null",
                "Start-ScheduledTask -TaskName " + task + " -ErrorAction Stop"));
    }

    /**
     * The scheduled task: downloads Tailscale and installs it silently, waiting while the DRAUPNE Link installer
     * is still finishing, then removes the task.
     */
    public static void installTailscale() {
        try {
            if (Tailscale.exe() != null) {
                log("Tailscale install: already installed");
                return;
            }
            Path msi;
            try {
                msi = Tailscale.download();
            } catch (Exception e) {
                log("Tailscale install: download failed - " + e.getMessage());
                return;
            }
            try {
                Instant deadline = Instant.now().plus(TAILSCALE_WAIT);
                Shell.Result r = Tailscale.installMsi(msi, false);
                while (r.exitCode() == Tailscale.ERROR_INSTALL_ALREADY_RUNNING && Instant.now().isBefore(deadline)) {
                    Thread.sleep(5000);
                    r = Tailscale.installMsi(msi, false);
                }
                log("Tailscale install: " + outcome(r));
            } finally {
                Files.deleteIfExists(msi);
                Files.deleteIfExists(msi.getParent());
            }
        } catch (Exception e) {
            log("Tailscale install: " + e);
        } finally {
            Shell.powershell(Duration.ofSeconds(30), "Unregister-ScheduledTask -TaskName " + Shell.psQuote(TAILSCALE_TASK)
                    + " -Confirm:$false -ErrorAction SilentlyContinue");
        }
    }

    /** As administrator, from the uninstaller: removes the firewall rules and a leftover task. Tailscale stays. */
    public static void cleanup() {
        log("Uninstalling: firewall rules removed - " + outcome(WindowsSetup.removeFirewallRulesAsAdmin()));
        Shell.powershell(Duration.ofSeconds(30), "Unregister-ScheduledTask -TaskName " + Shell.psQuote(TAILSCALE_TASK)
                + " -Confirm:$false -ErrorAction SilentlyContinue");
    }

    /**
     * True while the installer's Tailscale task is still due to finish, so the wizard waits for it instead of
     * starting a second install.
     */
    public static boolean tailscaleInstallPending() {
        return tailscaleInstallPending(logFile()) && taskRunning(tailscaleTaskState());
    }

    /**
     * The scheduled task's state ("Running", "Ready", "Queued", "Disabled"), or "" when there is no such task: it
     * deletes itself when done. A log that says "scheduled" with no task running means the task never ran (or was
     * never registered): nothing to wait for.
     */
    static String tailscaleTaskState() {
        Shell.Result r = Shell.powershell(Duration.ofSeconds(20), "$t = Get-ScheduledTask -TaskName " + Shell.psQuote(TAILSCALE_TASK)
                + " -ErrorAction SilentlyContinue\nif ($t) { [string]$t.State }");
        return r.ok() ? r.output().strip() : "";
    }

    static boolean taskRunning(String state) {
        return state.equalsIgnoreCase("Running") || state.equalsIgnoreCase("Queued");
    }

    static boolean tailscaleInstallPending(Path file) {
        try {
            if (!Files.exists(file)) return false;
            if (Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(TAILSCALE_WAIT.plusMinutes(5)))) return false;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).contains("Tailscale install:")) return false;
                if (lines.get(i).contains(SCHEDULED)) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    /**
     * Installers before 0.5.3 installed for one user, under %LOCALAPPDATA%. Run as that user after an all-users
     * install, this removes the old copy silently (the settings in %APPDATA% stay), so there aren't two of each
     * shortcut. @return what was removed, for the log; empty if nothing was found
     */
    public static String removePerUserInstall() {
        String script = String.join("\n",
                "$i = New-Object -ComObject WindowsInstaller.Installer",
                "$t = $i.GetType()",
                "$codes = $t.InvokeMember('RelatedProducts', 'GetProperty', $null, $i, @(" + Shell.psQuote(UPGRADE_CODE) + "))",
                "$count = $t.InvokeMember('Count', 'GetProperty', $null, $codes, $null)",
                "for ($n = 0; $n -lt $count; $n++) {",
                "  $c = $t.InvokeMember('Item', 'GetProperty', $null, $codes, @([int]$n))",
                "  try { $loc = $t.InvokeMember('ProductInfo', 'GetProperty', $null, $i, @($c, 'InstallLocation')) } catch { continue }",
                "  if ($loc -and $loc.StartsWith($env:LOCALAPPDATA, [StringComparison]::OrdinalIgnoreCase)) {",
                "    $p = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\\msiexec.exe') "
                        + "-ArgumentList '/x', $c, '/qn', '/norestart' -Wait -PassThru",
                "    \"$loc (exit $($p.ExitCode))\"",
                "  }",
                "}");
        return Shell.powershell(Duration.ofMinutes(5), script).output().strip();
    }

    /** %ProgramData%\DraupneLink\setup.log: the installer's tasks and how they went. */
    public static Path logFile() {
        return Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "DraupneLink", "setup.log");
    }

    /** The log's folder was looked at this run, and is this app's own to write in. */
    private static Boolean logFolderReady;

    /**
     * A line into the log. These tasks run as administrator or SYSTEM, and any user may make a folder in ProgramData:
     * so the log's folder is made for administrators and SYSTEM alone, or checked to be theirs ({@link #logFolderScript},
     * once a run), and the line is added only to a log that is a file ({@link #append}). Otherwise nothing is logged.
     */
    private static void log(String line) {
        try {
            Path file = logFile();
            synchronized (PcPrep.class) {
                if (logFolderReady == null) {
                    Shell.Result r = Shell.powershell(Duration.ofSeconds(60), logFolderScript(file.getParent(), SID_ADMINISTRATORS,
                            List.of(SID_SYSTEM, SID_ADMINISTRATORS), SID_USERS));
                    logFolderReady = logFolderReady(r);
                    if (!logFolderReady) System.err.println("setup.log not written: " + r.output().strip());
                }
            }
            if (!logFolderReady) return;
            append(file, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 19)
                    + "  " + line + System.lineSeparator());
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** Windows' own names for SYSTEM, the Administrators group and the Users group: the same in every language. */
    static final String SID_SYSTEM = "S-1-5-18", SID_ADMINISTRATORS = "S-1-5-32-544", SID_USERS = "S-1-5-32-545";

    /**
     * The script that makes the log's folder, or looks at the one there, and says "OK" or why not ("REFUSED: ...").
     * <ul>
     * <li>None there: made with its rights in one step - full control for {@code full}, reading for {@code read},
     * nothing taken over from ProgramData's own rights (those let every user add files to a folder in it), its owner
     * {@code owner}.</li>
     * <li>One there: refused when it is a link (a junction, a symbolic link: the log would be written where it leads),
     * no folder, or owned by somebody not in {@code full}; else brought to the same rights - an install from before
     * made it with ProgramData's.</li>
     * </ul>
     * By SID, never by name: the names differ with Windows' language.
     */
    static String logFolderScript(Path dir, String owner, List<String> full, String read) {
        List<String> all = new java.util.ArrayList<>(full);
        all.add(owner);
        all.add(read);
        for (String sid : all) if (!sid.matches("S-1-[0-9]+(-[0-9]+)+")) throw new IllegalArgumentException("not a SID: " + sid);
        StringBuilder s = new StringBuilder("$ErrorActionPreference = 'Stop'\n");
        s.append("$d = ").append(Shell.psQuote(dir.toString())).append('\n');
        s.append("$sid = { param($v) New-Object System.Security.Principal.SecurityIdentifier($v) }\n");
        s.append("$rule = { param($v, $rights) New-Object System.Security.AccessControl.FileSystemAccessRule((& $sid $v), $rights, "
                + "'ContainerInherit, ObjectInherit', 'None', 'Allow') }\n");
        s.append("$acl = New-Object System.Security.AccessControl.DirectorySecurity\n");
        s.append("$acl.SetAccessRuleProtection($true, $false)\n");
        s.append("$acl.SetOwner((& $sid '").append(owner).append("'))\n");
        for (String sid : full) s.append("$acl.AddAccessRule((& $rule '").append(sid).append("' 'FullControl'))\n");
        s.append("$acl.AddAccessRule((& $rule '").append(read).append("' 'ReadAndExecute'))\n");
        s.append("$item = Get-Item -LiteralPath $d -Force -ErrorAction SilentlyContinue\n");
        s.append("if (-not $item) { [System.IO.Directory]::CreateDirectory($d, $acl) | Out-Null; 'OK'; exit 0 }\n");
        s.append("if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { 'REFUSED: a link'; exit 0 }\n");
        s.append("if (-not $item.PSIsContainer) { 'REFUSED: not a folder'; exit 0 }\n");
        s.append("$is = $item.GetAccessControl('Owner').GetOwner([System.Security.Principal.SecurityIdentifier]).Value\n");
        s.append("if (@('").append(String.join("', '", full)).append("') -notcontains $is) { 'REFUSED: owner ' + $is; exit 0 }\n");
        s.append("[System.IO.Directory]::SetAccessControl($d, $acl)\n");
        s.append("'OK'\n");
        return s.toString();
    }

    /** The script ran and its last word is OK. */
    static boolean logFolderReady(Shell.Result r) {
        if (!r.ok() || r.output() == null) return false;
        String[] lines = r.output().strip().split("\\R");
        return lines[lines.length - 1].strip().equals("OK");
    }

    /**
     * Adds text to the log: only when its folder is no link and the log is a file or not there yet - never through a
     * link in its place, and never following one when it is opened.
     */
    static void append(Path file, String text) throws IOException {
        java.nio.file.LinkOption itself = java.nio.file.LinkOption.NOFOLLOW_LINKS;
        if (isLink(file.getParent())) throw new IOException(file.getParent() + " is a link: nothing written");
        if (Files.exists(file, itself) && (isLink(file) || !Files.isRegularFile(file, itself))) {
            throw new IOException(file + " is a link or no file: nothing written");
        }
        try (java.nio.channels.SeekableByteChannel out = Files.newByteChannel(file,
                java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND, itself))) {
            out.write(java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** A junction, a symbolic link, a mount point: what Windows marks as a reparse point (FILE_ATTRIBUTE_REPARSE_POINT), itself looked at. */
    static boolean isLink(Path p) throws IOException {
        try {
            Object attributes = Files.getAttribute(p, "dos:attributes", java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return attributes instanceof Integer a && (a & 0x400) != 0;
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return Files.isSymbolicLink(p); // not Windows
        }
    }

    private static String outcome(Shell.Result r) {
        if (r.ok()) return "done";
        String[] lines = r.output().strip().split("\\R");
        return "failed (" + r.exitCode() + (lines[lines.length - 1].isEmpty() ? "" : ": " + lines[lines.length - 1]) + ")";
    }
}
