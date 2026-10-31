package de.droiddrone.pc.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Windows settings the setup wizard checks and fixes: firewall rules, power plan, network adapter, shortcuts. */
public final class WindowsSetup {
    public static final String LINK_RULE = "DRAUPNE-Link-UDP";
    public static final String APK_RULE = "DRAUPNE-Link-PhoneDownload-TCP";
    /** The same rules' names up to 1.0.15 (DroidDrone PC): removed wherever the new ones are made or removed. */
    static final List<String> OLD_RULES = List.of("DroidDronePC-Link-UDP", "DroidDronePC-PhoneDownload-TCP");
    public static final String HIGH_PERFORMANCE = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c";
    public static final String ULTIMATE_PERFORMANCE = "e9a42b02-d5df-448d-aa00-03f14749eb61";
    private static final Pattern SCHEME = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F-]{27})\\s*\\((.*)\\)");
    private static final Pattern GUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public record PowerPlan(String guid, String name) {
        public boolean isHighPerformance() {
            String g = guid.toLowerCase(Locale.ROOT);
            String n = name.toLowerCase(Locale.ROOT);
            return g.equals(HIGH_PERFORMANCE) || g.equals(ULTIMATE_PERFORMANCE)
                    || n.contains("high performance") || n.contains("ultimate");
        }
    }

    /** @param wifi true for 802.11, false for wired; name is the adapter's name */
    public record InternetAdapter(boolean wifi, String name) {}

    private WindowsSetup() {
    }

    public static boolean firewallRuleExists(String name) {
        return Shell.run(Duration.ofSeconds(15), "netsh", "advfirewall", "firewall", "show", "rule", "name=" + name).ok();
    }

    /**
     * Adds (replaces) both inbound rules, as administrator:
     * UDP linkPort from anywhere (the 4G phone reaching the relay hosted on this PC), and TCP apkPort from the local
     * network and Tailscale only (the phone downloading the Flight app from this PC).
     */
    public static Shell.Result addFirewallRules(int linkPort, int apkPort) {
        return Shell.powershellElevated(Duration.ofMinutes(3), firewallScript(linkPort, apkPort));
    }

    /** The same rules, for a process that already runs as administrator (DRAUPNE Link's installer). */
    public static Shell.Result addFirewallRulesAsAdmin(int linkPort, int apkPort) {
        return Shell.powershell(Duration.ofMinutes(3), firewallScript(linkPort, apkPort));
    }

    /** Removes both rules, as administrator, when DRAUPNE Link is uninstalled. */
    public static Shell.Result removeFirewallRulesAsAdmin() {
        return Shell.powershell(Duration.ofMinutes(1), String.join("\n",
                deleteOldRules(),
                "netsh advfirewall firewall delete rule name=" + LINK_RULE + " | Out-Null",
                "netsh advfirewall firewall delete rule name=" + APK_RULE + " | Out-Null"));
    }

    private static String deleteOldRules() {
        StringBuilder s = new StringBuilder();
        for (String rule : OLD_RULES) s.append("netsh advfirewall firewall delete rule name=").append(rule).append(" | Out-Null\n");
        return s.toString().strip();
    }

    private static String firewallScript(int linkPort, int apkPort) {
        return String.join("\n",
                deleteOldRules(),
                "netsh advfirewall firewall delete rule name=" + LINK_RULE + " | Out-Null",
                "netsh advfirewall firewall add rule name=" + LINK_RULE
                        + " dir=in action=allow protocol=UDP localport=" + linkPort + " profile=any",
                "netsh advfirewall firewall delete rule name=" + APK_RULE + " | Out-Null",
                "netsh advfirewall firewall add rule name=" + APK_RULE
                        + " dir=in action=allow protocol=TCP localport=" + apkPort
                        + " remoteip=LocalSubnet,100.64.0.0/10 profile=any");
    }

    public static PowerPlan activePowerPlan() {
        Shell.Result r = Shell.run(Duration.ofSeconds(10), "powercfg", "/getactivescheme");
        Matcher m = SCHEME.matcher(r.output());
        return m.find() ? new PowerPlan(m.group(1), m.group(2).trim()) : null;
    }

    /** Switches to High performance; on PCs where that plan is hidden, creates it from the built-in template first. */
    public static Shell.Result useHighPerformance() {
        Shell.Result r = Shell.run(Duration.ofSeconds(10), "powercfg", "/setactive", HIGH_PERFORMANCE);
        if (r.ok()) return r;
        Shell.Result dup = Shell.run(Duration.ofSeconds(10), "powercfg", "-duplicatescheme", HIGH_PERFORMANCE);
        Matcher m = GUID.matcher(dup.output());
        if (!dup.ok() || !m.find()) return dup;
        return Shell.run(Duration.ofSeconds(10), "powercfg", "/setactive", m.group());
    }

    /** The adapter carrying the default route (what the PC uses to reach the internet), or null. */
    public static InternetAdapter internetAdapter() {
        String script = """
                $r = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
                     Sort-Object { $_.RouteMetric + $_.InterfaceMetric } | Select-Object -First 1
                if ($r) { $a = Get-NetAdapter -InterfaceIndex $r.ifIndex -ErrorAction SilentlyContinue
                          if ($a) { "$($a.PhysicalMediaType)|$($a.Name)" } }
                """;
        String out = Shell.powershell(Duration.ofSeconds(20), script).output().trim();
        int bar = out.lastIndexOf('|');
        if (bar < 0) return null;
        String media = out.substring(0, bar).toLowerCase(Locale.ROOT);
        return new InternetAdapter(media.contains("802.11") || media.contains("wireless"), out.substring(bar + 1).trim());
    }

    /** The installer's shortcut on the shared (all users) desktop if present, else one on this user's desktop. */
    public static Path desktopShortcut() {
        String[] dirs = Shell.powershell(Duration.ofSeconds(10),
                "[Environment]::GetFolderPath('CommonDesktopDirectory'); [Environment]::GetFolderPath('Desktop')")
                .output().strip().split("\\R");
        if (dirs.length < 2) return Path.of(System.getProperty("user.home"), "Desktop", "DRAUPNE Link.lnk");
        Path shared = Path.of(dirs[0].trim()).resolve("DRAUPNE Link.lnk");
        return Files.exists(shared) ? shared : Path.of(dirs[1].trim()).resolve("DRAUPNE Link.lnk");
    }

    /**
     * The installer's Start menu entry (in a "DRAUPNE Link" folder: for all users, or for this user from installers
     * before 0.5.3) if present, else where the wizard puts one.
     */
    public static Path startMenuShortcut() {
        String appData = System.getenv().getOrDefault("APPDATA", System.getProperty("user.home"));
        String programData = System.getenv().getOrDefault("ProgramData", "C:\\ProgramData");
        Path programs = Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs");
        for (Path installer : List.of(
                Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs", "DRAUPNE Link", "DRAUPNE Link.lnk"),
                programs.resolve("DRAUPNE Link").resolve("DRAUPNE Link.lnk"))) {
            if (Files.exists(installer)) return installer;
        }
        return programs.resolve("DRAUPNE Link.lnk");
    }

    /**
     * Shortcuts the setup wizard made for DroidDrone PC (up to 1.0.15) on this user's desktop and Start menu: once
     * the installer has replaced it, they point at nothing. Removed only when the old app really is gone.
     */
    public static void removeDeadOldShortcuts() {
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles == null || Files.exists(Path.of(programFiles, "DroidDrone PC", "DroidDrone PC.exe"))) return;
        String appData = System.getenv().getOrDefault("APPDATA", System.getProperty("user.home"));
        for (Path lnk : List.of(Path.of(System.getProperty("user.home"), "Desktop", "DroidDrone PC.lnk"),
                Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "DroidDrone PC.lnk"))) {
            try {
                Files.deleteIfExists(lnk);
            } catch (java.io.IOException ignored) {
            }
        }
    }

    public static boolean exists(Path path) {
        return path != null && Files.exists(path);
    }

    public static Shell.Result createShortcut(Path lnk, Path target, Path workingDir) {
        boolean bat = target.toString().toLowerCase(Locale.ROOT).endsWith(".bat");
        String script = String.join("\n",
                "$s = (New-Object -ComObject WScript.Shell).CreateShortcut(" + Shell.psQuote(lnk.toString()) + ")",
                "$s.TargetPath = " + Shell.psQuote(target.toString()),
                "$s.WorkingDirectory = " + Shell.psQuote(workingDir.toString()),
                "$s.Description = 'DRAUPNE Link ground station'",
                bat ? "$s.WindowStyle = 7" : "",
                "$s.Save()");
        return Shell.powershell(Duration.ofSeconds(20), script);
    }
}
