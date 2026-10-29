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
 * The PC tasks DroidDrone PC's installer does, so nobody has to click through them in the setup wizard. The
 * installer runs them as administrator: {@link #prepare} after installing, {@link #cleanup} when uninstalling.
 * <p>
 * Tailscale can't be installed from inside the installer, because Windows runs one install at a time. So prepare
 * registers a one-off scheduled task that installs it as soon as the installer has finished
 * ({@link #installTailscale}) and then deletes itself.
 * <p>
 * Everything is best effort: a failure is written to setup.log and the setup wizard checks and fixes it later.
 */
public final class PcPrep {
    static final String TAILSCALE_TASK = "DroidDrone PC - install Tailscale";
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
     * The scheduled task: downloads Tailscale and installs it silently, waiting while the DroidDrone PC installer
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
        return tailscaleInstallPending(logFile());
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

    /** %ProgramData%\DroidDronePC\setup.log: the installer's tasks and how they went. */
    public static Path logFile() {
        return Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "DroidDronePC", "setup.log");
    }

    private static void log(String line) {
        try {
            Path file = logFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 19)
                            + "  " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static String outcome(Shell.Result r) {
        if (r.ok()) return "done";
        String[] lines = r.output().strip().split("\\R");
        return "failed (" + r.exitCode() + (lines[lines.length - 1].isEmpty() ? "" : ": " + lines[lines.length - 1]) + ")";
    }
}
