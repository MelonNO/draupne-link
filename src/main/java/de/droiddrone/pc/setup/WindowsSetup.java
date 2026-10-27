package de.droiddrone.pc.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Windows settings the setup wizard checks and fixes: firewall rules, power plan, network adapter, shortcuts. */
public final class WindowsSetup {
    public static final String LINK_RULE = "DroidDronePC-Link-UDP";
    public static final String APK_RULE = "DroidDronePC-PhoneDownload-TCP";
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
        String script = String.join("\n",
                "netsh advfirewall firewall delete rule name=" + LINK_RULE + " | Out-Null",
                "netsh advfirewall firewall add rule name=" + LINK_RULE
                        + " dir=in action=allow protocol=UDP localport=" + linkPort + " profile=any",
                "netsh advfirewall firewall delete rule name=" + APK_RULE + " | Out-Null",
                "netsh advfirewall firewall add rule name=" + APK_RULE
                        + " dir=in action=allow protocol=TCP localport=" + apkPort
                        + " remoteip=LocalSubnet,100.64.0.0/10 profile=any");
        return Shell.powershellElevated(Duration.ofMinutes(3), script);
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

    public static Path desktopShortcut() {
        String out = Shell.powershell(Duration.ofSeconds(10), "[Environment]::GetFolderPath('Desktop')").output().trim();
        Path desktop = out.isEmpty() ? Path.of(System.getProperty("user.home"), "Desktop") : Path.of(out);
        return desktop.resolve("DroidDrone PC.lnk");
    }

    /** The installer's Start menu entry (in a "DroidDrone PC" folder) if present, else where the wizard puts one. */
    public static Path startMenuShortcut() {
        String appData = System.getenv().getOrDefault("APPDATA", System.getProperty("user.home"));
        Path programs = Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs");
        Path installer = programs.resolve("DroidDrone PC").resolve("DroidDrone PC.lnk");
        return Files.exists(installer) ? installer : programs.resolve("DroidDrone PC.lnk");
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
                "$s.Description = 'DroidDrone PC ground station'",
                bat ? "$s.WindowStyle = 7" : "",
                "$s.Save()");
        return Shell.powershell(Duration.ofSeconds(20), script);
    }
}
