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

    /**
     * Modern Standby (S0 Low Power Idle): on these PCs - most laptops since Windows 10 - the power plans do nothing;
     * Settings' Power mode slider decides. Read from "powercfg /a", whose first block lists the states available.
     */
    public static boolean modernStandby() {
        return modernStandby(Shell.run(Duration.ofSeconds(10), "powercfg", "/a").output());
    }

    static boolean modernStandby(String powercfgA) {
        // the available states come first; the unavailable ones (with the reason) after a blank line
        String available = powercfgA.strip().split("\\R\\s*\\R", 2)[0];
        return available.toLowerCase(Locale.ROOT).contains("(s0");
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

    /**
     * The desktop and Start menu shortcuts, made when missing (1.0.52: no setup step for them any more - the pilot:
     * "just have the software always do it"). Only for an installed copy (launcher known); slow (PowerShell), so off
     * the Swing thread.
     */
    public static void ensureShortcuts(Path launcher, Path workingDir) {
        ensureShortcuts(launcher, workingDir, SelfUpdate.runsInstalled(), SelfUpdate.installedApp() != null, null);
    }

    /**
     * 1.0.64: every desktop shortcut there is - the shared desktop's and this user's own (only the shared one was
     * looked at when it existed, so a broken one on the user's desktop stayed broken); none: this user's, to be made.
     */
    static List<Path> desktopShortcuts() {
        String[] dirs = Shell.powershell(Duration.ofSeconds(10),
                "[Environment]::GetFolderPath('CommonDesktopDirectory'); [Environment]::GetFolderPath('Desktop')")
                .output().strip().split("\\R");
        if (dirs.length < 2) return List.of(Path.of(System.getProperty("user.home"), "Desktop", "DRAUPNE Link.lnk"));
        return desktopLinks(Path.of(dirs[0].trim()).resolve("DRAUPNE Link.lnk"), Path.of(dirs[1].trim()).resolve("DRAUPNE Link.lnk"), Files::exists);
    }

    static List<Path> desktopLinks(Path shared, Path user, java.util.function.Predicate<Path> exists) {
        List<Path> out = new java.util.ArrayList<>();
        if (exists.test(shared)) out.add(shared);
        if (exists.test(user) || out.isEmpty()) out.add(user);
        return out;
    }

    /**
     * 1.0.64 (a pilot after an update: the desktop shortcut said "error opening ...\OneDrive\Desktop\app\DRAUPNE
     * Link.cfg", and the copy they found from it ran from Downloads): the installed copy also checks where its
     * shortcuts point, and points one here that leads to nothing, to a copy that can't start, or to another copy of
     * DRAUPNE Link. A copy that isn't the installed one never makes shortcuts to itself while an installed one exists.
     * @param installed this copy is the installed one (Program Files)
     * @param installedExists an installed copy exists (this one or another)
     * @param log where a repair is said, or null
     */
    static void ensureShortcuts(Path launcher, Path workingDir, boolean installed, boolean installedExists, java.util.function.Consumer<String> log) {
        if (launcher == null) return;
        List<Path> links = new java.util.ArrayList<>(desktopShortcuts());
        links.add(startMenuShortcut());
        for (Path lnk : links) {
            if (!exists(lnk)) {
                if (installed || !installedExists) createShortcut(lnk, launcher, workingDir);
                continue;
            }
            if (!installed) continue;
            String target = shortcutTarget(lnk);
            if (!needsRepair(target, launcher, Files::exists)) continue;
            boolean ok = createShortcut(lnk, launcher, workingDir).ok();
            String line = "Shortcut " + lnk.getFileName() + (ok ? " repaired" : " could not be repaired") + " (pointed at "
                    + (target == null || target.isBlank() ? "nothing" : target) + ").";
            if (log != null) log.accept(line);
            else de.droiddrone.pc.AppLog.line(line);
        }
    }

    /**
     * Whether a "DRAUPNE Link" shortcut with this target should point at the installed launcher instead: it leads to
     * nothing, or to another DRAUPNE Link.exe (a copy in Downloads, a lone launcher copied out of its folder). One the
     * pilot pointed at something else that is there is left alone; an unreadable one (null) too.
     */
    static boolean needsRepair(String target, Path launcher, java.util.function.Predicate<Path> exists) {
        if (target == null) return false;
        String t = target.trim();
        if (t.isEmpty()) return true;
        if (t.equalsIgnoreCase(launcher.toString())) return false;
        Path p;
        try {
            p = Path.of(t);
        } catch (RuntimeException e) {
            return true;
        }
        if (!exists.test(p)) return true;
        Path name = p.getFileName();
        return name != null && name.toString().equalsIgnoreCase("DRAUPNE Link.exe");
    }

    /** A shortcut's target, or null when it can't be read. */
    static String shortcutTarget(Path lnk) {
        Shell.Result r = Shell.powershell(Duration.ofSeconds(15),
                "(New-Object -ComObject WScript.Shell).CreateShortcut(" + Shell.psQuote(lnk.toString()) + ").TargetPath");
        if (!r.ok() || r.output() == null) return null;
        String out = r.output().strip();
        int nl = out.lastIndexOf('\n');
        return nl < 0 ? out : out.substring(nl + 1).strip();
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
