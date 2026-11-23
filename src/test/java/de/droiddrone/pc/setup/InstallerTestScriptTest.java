package de.droiddrone.pc.setup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * tools/installer-test.ps1, the installer test run by hand on a PC with a released DRAUPNE Link installed
 * (docs/TESTING.md, "The installer test"), as far as it can be proved without installing, closing or starting
 * anything: the script parses; it names what the app's code names (the folders, the firewall rules, the installer's
 * codes, the log lines it waits for, UPDATE's arguments); nothing in it moves, deletes, ends or writes the registry;
 * the folder list; the copy aside and its hash list on folders of this test's own, the folders left as they were; the
 * compare of two hash lists with what the app is known to change; the refusal of an installed version it does not
 * expect, the whole script included.
 * <p>
 * The script runs in a PowerShell of its own whose APPDATA, LOCALAPPDATA, USERPROFILE, TEMP, ProgramData and
 * ProgramFiles are this test's folders, sealed (DRAUPNE_INSTALLER_TEST_SEALED: it stops before any step that would
 * change anything). Its functions are loaded with -LoadOnly and given made-up folders, entries and states; the whole
 * script is run only where it must refuse at its first step.
 */
class InstallerTestScriptTest {
    @TempDir
    Path dir;

    private final List<Path> links = new ArrayList<>();
    private int scripts;

    /** The links go first, each itself and not what it leads to: the test's folder is cleared away after. */
    @AfterEach
    void removeLinks() throws IOException {
        for (Path link : links) Files.deleteIfExists(link);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private static Path project() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root, "the project's folder");
        return root;
    }

    private static Path script() {
        return project().resolve("tools").resolve("installer-test.ps1");
    }

    private static String text(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String q(Path p) {
        return "'" + p.toString().replace("'", "''") + "'";
    }

    // ------------------------------------------------------------------ running PowerShell, sealed

    private record Ran(int exit, String out) {
        JsonElement json() {
            for (String line : out.split("\\R")) if (line.startsWith("JSON:")) return JsonParser.parseString(line.substring(5));
            throw new AssertionError("no JSON line in: " + out);
        }
    }

    /** This test's own folders, given to the script's PowerShell as the PC's. */
    private Map<String, Path> sealed() throws IOException {
        Map<String, Path> env = new LinkedHashMap<>();
        env.put("APPDATA", dir.resolve("pc/home/AppData/Roaming"));
        env.put("LOCALAPPDATA", dir.resolve("pc/home/AppData/Local"));
        env.put("USERPROFILE", dir.resolve("pc/home"));
        env.put("TEMP", dir.resolve("pc/home/AppData/Local/Temp"));
        env.put("TMP", dir.resolve("pc/home/AppData/Local/Temp"));
        env.put("ProgramData", dir.resolve("pc/ProgramData"));
        env.put("ProgramFiles", dir.resolve("pc/Program Files"));
        for (Path p : env.values()) Files.createDirectories(p);
        return env;
    }

    private Ran run(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        for (Map.Entry<String, Path> e : sealed().entrySet()) pb.environment().put(e.getKey(), e.getValue().toString());
        pb.environment().put("DRAUPNE_INSTALLER_TEST_SEALED", "1");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Ran(p.waitFor(), out);
    }

    /** PowerShell code run after the script's functions are loaded (-LoadOnly). */
    private Ran code(String ps) throws Exception {
        Path file = dir.resolve("t" + (++scripts) + ".ps1");
        String all = "[Console]::OutputEncoding = [Text.Encoding]::UTF8\n$ErrorActionPreference = 'Stop'\n. " + q(script()) + " -LoadOnly\n" + ps;
        byte[] body = all.getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(bom);
            out.write(body);
        }
        Ran r = run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file.toString()));
        assertEquals(0, r.exit(), r.out());
        return r;
    }

    /** The script itself, sealed, with these arguments. */
    private Ran whole(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script().toString()));
        command.addAll(List.of(args));
        return run(command);
    }

    /** The made-up PC's folders as the script's Get-Places takes them. */
    private String folders() throws IOException {
        Map<String, Path> env = sealed();
        Path desktopAll = Files.createDirectories(dir.resolve("pc/Users/Public/Desktop"));
        Path desktop = Files.createDirectories(env.get("USERPROFILE").resolve("Desktop"));
        return "$F = @{ AppData = " + q(env.get("APPDATA")) + "; LocalAppData = " + q(env.get("LOCALAPPDATA")) + "; Home = " + q(env.get("USERPROFILE"))
                + "; Temp = " + q(env.get("TEMP")) + "; ProgramData = " + q(env.get("ProgramData")) + "; ProgramFiles = " + q(env.get("ProgramFiles"))
                + "; CommonDesktop = " + q(desktopAll) + "; Desktop = " + q(desktop) + " }\n";
    }

    private static Map<String, String> checks(JsonElement list) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonElement e : list.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            out.merge(o.get("Check").getAsString(), o.get("Verdict").getAsString() + ": " + o.get("Detail").getAsString(), (a, b) -> a + " || " + b);
        }
        return out;
    }

    // ------------------------------------------------------------------ the script as text

    @Test
    void theScriptParsesAndNamesNoPersonalPath() throws Exception {
        String s = text(script());
        for (int i = 0; i < s.length(); i++) assertTrue(s.charAt(i) < 128, "plain ASCII (Windows PowerShell reads a file without a BOM in the PC's code page): at " + i);
        assertFalse(s.contains("C:\\Users\\"), "no one's own folder written into it");
        assumeTrue(windows());
        Shell.Result r = Shell.powershell(Duration.ofSeconds(60), "$e = $null; [void][System.Management.Automation.Language.Parser]::ParseFile("
                + Shell.psQuote(script().toString()) + ", [ref]$null, [ref]$e); if ($e.Count) { $e | ForEach-Object { $_.Message }; exit 1 }");
        assertTrue(r.ok(), "PowerShell parse errors: " + r.output());
    }

    @Test
    void itNamesWhatTheAppsCodeNames() throws Exception {
        String s = text(script());
        assertTrue(s.contains("$script:LinkRule = '" + WindowsSetup.LINK_RULE + "'"), "the link port's firewall rule");
        assertTrue(s.contains("$script:ApkRule = '" + WindowsSetup.APK_RULE + "'"), "the phone download's");
        for (String old : WindowsSetup.OLD_RULES) assertTrue(s.contains("'" + old + "'"), old);
        assertTrue(s.contains("$script:RuleRemote = @('" + String.join("', '", WindowsSetup.RULE_REMOTE.split(",")) + "')"), "where both rules let packets in from");
        assertTrue(s.contains("$script:UpgradeCode = '" + PcPrep.UPGRADE_CODE + "'"));
        assertTrue(s.contains("$script:TailscaleTask = '" + PcPrep.TAILSCALE_TASK + "'"));
        assertTrue(s.contains("$script:SidSystem = '" + PcPrep.SID_SYSTEM + "'") && s.contains("$script:SidAdmins = '" + PcPrep.SID_ADMINISTRATORS + "'")
                && s.contains("$script:SidUsers = '" + PcPrep.SID_USERS + "'"), "PcPrepLogTest's rule, by the same SIDs");
        assertTrue(s.contains("$script:SettingsFile = '" + de.droiddrone.pc.PcConfig.FILE_NAME + "'"));
        assertTrue(s.contains("$script:Downloads = '" + UpdateCheck.DOWNLOADS + "'"), "-Back downloads where UPDATE does, nowhere else");
        java.lang.reflect.Field keep = de.droiddrone.pc.AppLog.class.getDeclaredField("KEEP_FILES");
        keep.setAccessible(true);
        assertTrue(s.contains("$script:AppLogsKept = " + keep.getInt(null) + " "), "the app logs AppLog keeps at every start");

        // the installer run as UPDATE's helper runs it
        String helper = SelfUpdate.script(Path.of("C:\\Temp\\DraupneLink-update\\DRAUPNE-Link-Setup-1.0.72.exe"), 1, Path.of("C:\\Program Files\\DRAUPNE Link\\DRAUPNE Link.exe"), "ab".repeat(32));
        Matcher m = Pattern.compile("-ArgumentList '([^']+)','([^']+)'").matcher(helper);
        assertTrue(m.find(), helper);
        assertTrue(s.contains("$script:UpdateArgs = @('" + m.group(1) + "', '" + m.group(2) + "')"), "the same arguments as UPDATE: " + m.group());

        // what jpackage's product code is made of, as build.gradle gives it
        String gradle = text(project().resolve("build.gradle"));
        assertTrue(gradle.contains("'--vendor', 'draupne-link'") && s.contains("$script:Vendor = 'draupne-link'"));
        assertTrue(gradle.contains("'--name', 'DRAUPNE Link'") && s.contains("$script:AppName = 'DRAUPNE Link'"));
        assertTrue(gradle.contains("'--win-upgrade-uuid', '" + PcPrep.UPGRADE_CODE.replaceAll("[{}]", "").toLowerCase() + "'"));
        String wxs = text(project().resolve("src/installer/main.wxs"));
        assertTrue(wxs.contains("Value=\"$(var.JpAppName).exe;DroidDrone PC.exe\"") && s.contains("$script:OldExe = 'DroidDrone PC.exe'"), "the programs the installer waits for");

        // the folders, where the app's code puts them
        assertEquals("DraupneLink", de.droiddrone.pc.AppPaths.dataDir().getFileName().toString());
        assertTrue(s.contains("(Join-Path $F.AppData 'DraupneLink')"));
        assertEquals("DraupneLink", PcPrep.logFile().getParent().getFileName().toString());
        assertTrue(s.contains("(Join-Path $F.ProgramData 'DraupneLink')") && s.contains("'DraupneLink\\setup.log'"));
        assertEquals("DraupneLink-update", SelfUpdate.helperLog().getParent().getFileName().toString());
        assertTrue(s.contains("(Join-Path $F.Temp 'DraupneLink-update')"));
        Path docs = de.droiddrone.pc.overwatch.ClipIndex.defaultRoot().getParent();
        assertEquals("DRAUPNE Link", docs.getFileName().toString());
        assertEquals("Documents", docs.getParent().getFileName().toString());
        assertTrue(s.contains("(Join-Path $F.Home 'Documents\\DRAUPNE Link')"));
        String appPaths = text(project().resolve("src/main/java/de/droiddrone/pc/AppPaths.java"));
        assertTrue(appPaths.contains("\"Videos\", \"DRAUPNE Link\"") && s.contains("(Join-Path $F.Home 'Videos\\DRAUPNE Link')"), "recordings");
        assertTrue(appPaths.contains("resolve(\"DroidDronePC\")") && s.contains("(Join-Path $F.AppData 'DroidDronePC')"), "DroidDrone PC's settings");
        assertEquals(Path.of("X:\\PF\\DRAUPNE Link\\DRAUPNE Link.exe"), SelfUpdate.installedLauncher("X:\\PF"));
        assertTrue(s.contains("$script:AppExe = 'DRAUPNE Link.exe'"));
        String windowsSetup = text(project().resolve("src/main/java/de/droiddrone/pc/setup/WindowsSetup.java"));
        assertTrue(windowsSetup.contains("Path.of(programData, \"Microsoft\", \"Windows\", \"Start Menu\", \"Programs\", \"DRAUPNE Link\", \"DRAUPNE Link.lnk\")")
                && s.contains("'Microsoft\\Windows\\Start Menu\\Programs'"), "the installer's Start menu entry");

        // the lines it waits for, as the app writes them
        String pcPrep = text(project().resolve("src/main/java/de/droiddrone/pc/setup/PcPrep.java"));
        for (String line : List.of("Preparing this PC", "Firewall rules: ", "Uninstalling: firewall rules removed")) {
            assertTrue(pcPrep.contains("\"" + line), "PcPrep logs " + line);
            assertTrue(s.contains(line.strip()), "the script looks for " + line);
        }
        assertTrue(text(project().resolve("src/main/java/de/droiddrone/pc/AppLog.java")).contains("\"=== DRAUPNE Link \" + AppInfo.version() + \" started"));
        assertTrue(s.contains("=== DRAUPNE Link (\\S+) started"));
        String window = text(project().resolve("src/main/java/de/droiddrone/pc/gui/MainWindow.java"));
        assertTrue(window.contains("logLine(\"DRAUPNE Link \" + AppInfo.version() + \". Phone app: DRAUPNE Flight \"") && window.contains("\"). Settings: \""));
        assertTrue(window.contains("super(\"DRAUPNE Link \" + AppInfo.version())") && s.contains("\"DRAUPNE Link $Version\""), "the window's title");
        assertTrue(s.contains("$script:BackTo = '1.0.69'") && s.contains("'1.0.69' = @{ Name = 'DRAUPNE-Link-Setup-1.0.69.exe'"), "the released installer -Back puts back");
    }

    /** What the script may never do, by what it would have to say to do it. */
    @Test
    void nothingInItMovesDeletesEndsOrWritesTheRegistry() throws Exception {
        String s = text(script());
        StringBuilder code = new StringBuilder(); // the code without its comments
        boolean block = false;
        for (String line : s.split("\\R")) {
            String t = line.strip();
            if (block) { if (t.contains("#>")) block = false; continue; }
            if (t.startsWith("<#")) { block = !t.contains("#>"); continue; }
            if (t.startsWith("#")) continue;
            int hash = line.indexOf(" # ");
            code.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        String c = code.toString();
        for (String never : List.of("Remove-Item", "Move-Item", "Rename-Item", "Stop-Process", "taskkill", ".Kill(", "Clear-Content", "Remove-ItemProperty",
                "Set-ItemProperty", "New-ItemProperty", "Remove-NetFirewallRule", "Set-NetFirewallRule", "New-NetFirewallRule", "netsh", "Unregister-ScheduledTask",
                "Register-ScheduledTask", "]::Delete(", "]::Move(", "/MOV", "/MIR", "/PURGE", "Set-Acl", "SetAccessControl", "powercfg /s", "Set-Service")) {
            assertFalse(c.toLowerCase().contains(never.toLowerCase()), "the script says " + never);
        }
        assertFalse(Pattern.compile("(?i)\\breg(\\.exe)?\\s+(add|delete|import|copy)\\b").matcher(c).find(), "reg.exe writes");
        assertFalse(Pattern.compile("(?im)^\\s*(del|rd|rmdir|erase)\\s").matcher(c).find(), "cmd's deletes");
        // Windows' uninstall only in -Uninstall's own step
        assertEquals(1, c.split("'/x'", -1).length - 1, "msiexec /x once");
        assertTrue(c.indexOf("'/x'") > c.indexOf("function Invoke-Uninstall") && c.indexOf("'/x'") < c.indexOf("if ($LoadOnly) { return }"));
        assertTrue(c.contains("elseif ($Uninstall) { Invoke-Uninstall $F $Root }"));
        // it starts the installer it is given, the installed app (as this user) and nothing else
        List<String> starts = new ArrayList<>();
        for (String line : c.split("\\R")) if (line.contains("Start-Process")) starts.add(line.strip());
        assertEquals(List.of(
                "$p = Start-Process -FilePath $Exe -ArgumentList $all -PassThru",
                "if (Test-Elevated) { Start-Process -FilePath (Join-Path $env:SystemRoot 'explorer.exe') -ArgumentList ('\"' + $exe + '\"') }",
                "else { Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe -Parent) }"), starts);
        // a copy is made by robocopy and Copy-Item only, into its own folder; robocopy's options copy
        assertTrue(c.contains("$a += @('/COPY:DAT', '/DCOPY:T', '/XJ', '/R:2', '/W:1', '/NP', '/NFL', '/NDL', \"/UNILOG+:$Log\")"));
        // every step after the first one asks the seal first
        for (String fn : List.of("function Invoke-Upgrade", "function Invoke-Back", "function Invoke-Uninstall")) {
            int at = c.indexOf(fn), seal = c.indexOf("Assert-NotSealed", at), run = c.indexOf("New-RunFolder", at);
            assertTrue(at >= 0 && seal > at && seal < run, fn + " asks the seal before its run folder");
        }
    }

    // ------------------------------------------------------------------ the functions

    @Test
    void theProductCodeIsJpackagesAndVersionsCompareAsTheAppCompares() throws Exception {
        assumeTrue(windows());
        JsonObject r = code("'JSON:' + (ConvertTo-Json -Compress -InputObject ([ordered]@{ c69 = (Get-ProductCode '1.0.69'); c72 = (Get-ProductCode '1.0.72')\n"
                + " a = (Compare-Version '1.0.72' '1.0.69'); b = (Compare-Version '1.0.9' '1.0.10'); c = (Compare-Version '1.0.69' '1.0.69'); d = (Compare-Version '2.3.0-pc.55' '2.3.0-pc.9') }))").json().getAsJsonObject();
        for (String v : List.of("1.0.69", "1.0.72")) {
            // jpackage (JDK 21, WinMsiBundler.getProductCode): a name-based UUID of "ProductCode/<vendor>/<name>/<version>"
            String java = "{" + UUID.nameUUIDFromBytes(("ProductCode/draupne-link/DRAUPNE Link/" + v).getBytes(StandardCharsets.UTF_8)).toString().toUpperCase() + "}";
            assertEquals(java, r.get("c" + v.substring(4)).getAsString(), v);
        }
        assertEquals(UpdateCheck.isNewer("1.0.72", "1.0.69") ? 1 : 0, r.get("a").getAsInt());
        assertEquals(-1, r.get("b").getAsInt());
        assertFalse(UpdateCheck.isNewer("1.0.9", "1.0.10"));
        assertEquals(0, r.get("c").getAsInt());
        assertEquals(1, r.get("d").getAsInt());
    }

    @Test
    void theFolderListIsWhereTheAppKeepsItsFiles() throws Exception {
        assumeTrue(windows());
        Map<String, Path> env = sealed();
        JsonArray places = code(folders() + "'JSON:' + (ConvertTo-Json -Compress -InputObject @(Get-Places $F | Select-Object Group, Kind, Path, Only))").json().getAsJsonArray();
        Map<String, String> got = new TreeMap<>();
        for (JsonElement e : places) {
            JsonObject o = e.getAsJsonObject();
            got.put(o.get("Group").getAsString(), o.get("Kind").getAsString() + " " + o.get("Path").getAsString() + (o.get("Only").getAsString().isEmpty() ? "" : " | " + o.get("Only").getAsString()));
        }
        Path home = env.get("USERPROFILE"), programData = env.get("ProgramData");
        Map<String, String> want = new TreeMap<>(Map.ofEntries(
                Map.entry("appdata", "user " + env.get("APPDATA").resolve("DraupneLink")),
                Map.entry("appdata-old", "user " + env.get("APPDATA").resolve("DroidDronePC")),
                Map.entry("documents", "user " + home.resolve("Documents\\DRAUPNE Link")),
                Map.entry("videos", "user " + home.resolve("Videos\\DRAUPNE Link")),
                Map.entry("update", "user " + env.get("TEMP").resolve("DraupneLink-update")),
                Map.entry("programdata", "installer " + programData.resolve("DraupneLink")),
                Map.entry("startmenu", "installer " + programData.resolve("Microsoft\\Windows\\Start Menu\\Programs\\DRAUPNE Link")),
                Map.entry("desktop-all", "installer " + dir.resolve("pc/Users/Public/Desktop") + " | DRAUPNE Link.lnk"),
                Map.entry("desktop-own", "installer " + home.resolve("Desktop") + " | DRAUPNE Link.lnk"),
                Map.entry("startmenu-own", "installer " + env.get("APPDATA").resolve("Microsoft\\Windows\\Start Menu\\Programs") + " | DRAUPNE Link.lnk"),
                Map.entry("program", "program " + env.get("ProgramFiles").resolve("DRAUPNE Link"))));
        assertEquals(want, got);
    }

    private static String sha256(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    /** Every file under a folder, its path inside it to its SHA-256 (links not followed). */
    private static Map<String, String> hashes(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                out.put(root.relativize(f).toString(), sha256(f));
            }
        }
        return out;
    }

    /**
     * The made-up PC's files where the app and its installer keep theirs (PowerShell itself writes in LOCALAPPDATA and
     * TEMP as it starts: those are left out).
     */
    private Map<String, String> pcFiles() throws Exception {
        Map<String, Path> env = sealed();
        Map<String, String> out = new TreeMap<>();
        for (Path root : List.of(env.get("APPDATA"), env.get("USERPROFILE").resolve("Documents"), env.get("USERPROFILE").resolve("Videos"),
                env.get("USERPROFILE").resolve("Desktop"), env.get("TEMP").resolve("DraupneLink-update"), env.get("ProgramData"), env.get("ProgramFiles"),
                dir.resolve("pc/Users"))) {
            if (!Files.exists(root)) continue;
            for (Map.Entry<String, String> e : hashes(root).entrySet()) out.put(dir.relativize(root.resolve(e.getKey())).toString(), e.getValue());
        }
        return out;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void theCopyAsideCopiesEveryFileAndChangesNone() throws Exception {
        assumeTrue(windows());
        Map<String, Path> env = sealed();
        Path appData = env.get("APPDATA").resolve("DraupneLink"), docs = env.get("USERPROFILE").resolve("Documents/DRAUPNE Link");
        write(appData.resolve("draupne-link.properties"), "rcRate=250\n");
        write(appData.resolve("logs/app-2026-11-01.log"), "=== DRAUPNE Link 1.0.69 started\n");
        write(appData.resolve("tiles/osm/12/34/56.png"), "tile");
        write(docs.resolve("Tracks/flight-1.gpx"), "<gpx/>");
        write(env.get("USERPROFILE").resolve("Videos/DRAUPNE Link/flight-1.mp4"), "video");
        write(env.get("TEMP").resolve("DraupneLink-update/update-last.log"), "RESULT OK");
        write(env.get("ProgramData").resolve("DraupneLink/setup.log"), "2026-11-21T13:23:06  Preparing this PC\n");
        write(env.get("ProgramFiles").resolve("DRAUPNE Link/app/DRAUPNE Link.cfg"), "[Application]\n");
        write(dir.resolve("pc/Users/Public/Desktop/DRAUPNE Link.lnk"), "lnk");
        write(dir.resolve("pc/Users/Public/Desktop/someone else's.txt"), "not ours");
        // a link in the pilot's folder, to a folder elsewhere: listed, never followed, never copied
        Path elsewhere = dir.resolve("elsewhere");
        write(elsewhere.resolve("not-the-apps.txt"), "elsewhere");
        Path link = docs.resolve("a link");
        Shell.Result made = Shell.run(Duration.ofSeconds(30), "cmd", "/c", "mklink", "/J", link.toString(), elsewhere.toString());
        assertTrue(made.ok(), made.output());
        links.add(link);
        Map<String, String> before = pcFiles();
        assertTrue(before.size() >= 10, "9 files and another program's on the desktop (and the one the link leads to, where Java follows a junction): " + before.keySet());

        Path run = dir.resolve("run");
        Ran r = code(folders() + "$places = Get-Places $F\nNew-Item -ItemType Directory -Path " + q(run) + " | Out-Null\n"
                + "Copy-Aside $places " + q(run) + "\n$list = Confirm-Copy $places " + q(run) + " 'before'\n"
                + "'JSON:' + (ConvertTo-Json -Compress -InputObject @($script:Checks))");
        assertEquals(before, pcFiles(), "every file of the PC's as it was");
        assertTrue(checks(r.json()).get("copy aside").startsWith("PASS: 9 files"), r.out());
        assertTrue(checks(r.json()).get("copy aside").contains("1 links listed, not followed"), r.out());

        Map<String, String> copied = hashes(run.resolve("copy"));
        assertEquals(sha256(appData.resolve("draupne-link.properties")), copied.get("appdata\\draupne-link.properties"));
        assertEquals(sha256(docs.resolve("Tracks/flight-1.gpx")), copied.get("documents\\Tracks\\flight-1.gpx"));
        assertTrue(copied.containsKey("program\\app\\DRAUPNE Link.cfg") && copied.containsKey("desktop-all\\DRAUPNE Link.lnk") && copied.containsKey("programdata\\setup.log"));
        assertEquals(9, copied.size(), "nothing else: not the link's folder, not another program's desktop file: " + copied.keySet());

        List<String> rows = Files.readAllLines(run.resolve("hashes-before.tsv"));
        assertEquals("group\tpath\tbytes\tsha256\tmodifiedUtc", rows.get(0));
        assertTrue(rows.contains("documents\ta link\\\t0\tLINK\t"), rows.toString());
        String settings = rows.stream().filter(l -> l.startsWith("appdata\tdraupne-link.properties\t")).findFirst().orElseThrow();
        assertEquals(sha256(appData.resolve("draupne-link.properties")), settings.split("\t")[3]);
        assertEquals("11", settings.split("\t")[2]);
        assertEquals(1 + 9 + 1, rows.size(), "a header, 9 files, the link: " + rows);
    }

    @Test
    void theCompareFailsAUserFileLostOrChangedButNotWhatTheAppIsKnownToChange() throws Exception {
        assumeTrue(windows());
        JsonObject r = code("""
                function Row($g, $p, $h) { [pscustomobject]@{ Group = $g; Path = $p; Bytes = 1; Sha256 = $h; Modified = '' } }
                $before = [System.Collections.Generic.List[object]]::new()
                $before.Add((Row 'appdata' 'draupne-link.properties' 'a'))
                1..16 | ForEach-Object { $before.Add((Row 'appdata' ('logs\\app-2026-10-{0:D2}.log' -f $_) 'l')) }
                $before.Add((Row 'appdata' 'logs\\link-20261001-120000.csv' 'k'))
                $before.Add((Row 'appdata' 'tiles\\osm\\1\\2\\3.png' 't'))
                $before.Add((Row 'documents' 'Tracks\\flight-1.gpx' 'g'))
                $before.Add((Row 'videos' 'flight-1.mp4' 'v'))
                $before.Add((Row 'update' 'update-last.log' 'u'))
                $gone = @('logs\\app-2026-10-01.log', 'logs\\app-2026-10-02.log', 'logs\\app-2026-10-03.log', 'flight-1.mp4')
                $after = [System.Collections.Generic.List[object]]::new()
                foreach ($x in $before) {
                    if ($gone -contains $x.Path) { continue }
                    $h = $x.Sha256
                    if (@('draupne-link.properties', 'logs\\app-2026-10-16.log', 'Tracks\\flight-1.gpx') -contains $x.Path) { $h = $h + '2' }
                    $after.Add((Row $x.Group $x.Path $h))
                }
                $after.Add((Row 'appdata' 'tiles\\osm\\1\\2\\4.png' 't'))
                $after.Add((Row 'appdata' 'logs\\app-2026-11-23.log' 'n'))
                function Verdicts($c) { $o = [ordered]@{}; foreach ($x in $c) { $o[$x.Group + '\\' + $x.Path] = $x.Change + ' ' + $x.Verdict }; $o }
                'JSON:' + (ConvertTo-Json -Compress -InputObject ([ordered]@{
                    install = (Verdicts (Compare-HashList $before $after (Get-ExpectedChanges 'install' $before)))
                    app = (Verdicts (Compare-HashList $before $after (Get-ExpectedChanges 'app' $before))) }))
                """).json().getAsJsonObject();
        Map<String, String> install = new TreeMap<>(), app = new TreeMap<>();
        r.getAsJsonObject("install").entrySet().forEach(e -> install.put(e.getKey(), e.getValue().getAsString()));
        r.getAsJsonObject("app").entrySet().forEach(e -> app.put(e.getKey(), e.getValue().getAsString()));
        // the installer writes no file of the pilot's: every one lost or changed fails, new ones are said
        assertEquals(new TreeMap<>(Map.of(
                "appdata\\draupne-link.properties", "changed FAIL",
                "appdata\\logs\\app-2026-10-01.log", "missing FAIL",
                "appdata\\logs\\app-2026-10-02.log", "missing FAIL",
                "appdata\\logs\\app-2026-10-03.log", "missing FAIL",
                "appdata\\logs\\app-2026-10-16.log", "changed FAIL",
                "appdata\\logs\\app-2026-11-23.log", "new NEW",
                "appdata\\tiles\\osm\\1\\2\\4.png", "new NEW",
                "documents\\Tracks\\flight-1.gpx", "changed FAIL",
                "videos\\flight-1.mp4", "missing FAIL")), install);
        // the app's start: its settings and today's log written, the app logs beyond its 14 newest gone (AppLog) - not
        // the 14th newest, not a flight, not a recording
        assertEquals(new TreeMap<>(Map.of(
                "appdata\\draupne-link.properties", "changed EXPECTED",
                "appdata\\logs\\app-2026-10-01.log", "missing EXPECTED",
                "appdata\\logs\\app-2026-10-02.log", "missing EXPECTED",
                "appdata\\logs\\app-2026-10-03.log", "missing FAIL",
                "appdata\\logs\\app-2026-10-16.log", "changed EXPECTED",
                "appdata\\logs\\app-2026-11-23.log", "new NEW",
                "appdata\\tiles\\osm\\1\\2\\4.png", "new NEW",
                "documents\\Tracks\\flight-1.gpx", "changed FAIL",
                "videos\\flight-1.mp4", "missing FAIL")), app);
    }

    @Test
    void theSettingsKeysAreComparedByName() throws Exception {
        assumeTrue(windows());
        Path before = dir.resolve("before.properties"), kept = dir.resolve("kept.properties"), lost = dir.resolve("lost.properties");
        Files.writeString(before, "# DRAUPNE Link\nrcRate=250\nip=192.0.2.1\ndrone.crashed=X\nwindow.x=10\nlong=one \\\n    two\n");
        Files.writeString(kept, "rcRate=250\nip=192.0.2.1\nwindow.x=20\nlong=one two\nmap.keptFlights=\n");
        Files.writeString(lost, "rcRate=250\nwindow.x=10\nlong=one two\n");
        Map<String, String> c = checks(code("$s = Read-Settings " + q(before) + "\nif ($s['long'] -ne 'one two') { throw \"long: $($s['long'])\" }\n"
                + "Add-SettingsCheck 'kept' " + q(before) + " " + q(kept) + "\nAdd-SettingsCheck 'lost' " + q(before) + " " + q(lost) + "\n"
                + "'JSON:' + (ConvertTo-Json -Compress -InputObject @($script:Checks))").json());
        // OUT OF ACTION's marks of 1.0.70 and before (drone.crashed) move to drone.outOfAction: that key may go
        assertTrue(c.get("kept").startsWith("PASS: every key still there; 5 keys before, 5 after; changed: window.x; added: map.keptFlights"), c.toString());
        assertTrue(c.get("lost").startsWith("FAIL: keys gone: ip; "), c.toString());
        assertFalse(c.toString().contains("192.0.2.1"), "values are never said");
    }

    private static void jar(Path file, String version) throws IOException {
        Files.createDirectories(file.getParent());
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), m)) {
            out.flush();
        }
    }

    private static void programFolder(Path folder, String named, String says) throws IOException {
        write(folder.resolve("DRAUPNE Link.exe"), "");
        write(folder.resolve("app/DRAUPNE Link.cfg"), "[Application]\napp.classpath=$APPDIR\\draupne-link-" + named + ".jar\napp.mainclass=de.droiddrone.pc.Main\n");
        jar(folder.resolve("app/draupne-link-" + named + ".jar"), says);
    }

    @Test
    void itRefusesAnInstalledVersionItDoesNotExpect() throws Exception {
        assumeTrue(windows());
        Path pf = dir.resolve("pf");
        programFolder(pf.resolve("DRAUPNE Link"), "1.0.69", "1.0.69");
        programFolder(dir.resolve("liar/DRAUPNE Link"), "1.0.69", "1.0.70");
        programFolder(dir.resolve("two/DRAUPNE Link"), "1.0.69", "1.0.69");
        jar(dir.resolve("two/DRAUPNE Link/app/draupne-link-1.0.72.jar"), "1.0.72");
        JsonObject r = code("$pf = " + q(pf) + "\n" + """
                function Entry($v, $code, $loc, $key, $name) { [pscustomobject]@{ Code = $code; Name = $name; Version = $v; Location = $loc; Key = $key } }
                $hklm = 'HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\x'
                $c69 = Get-ProductCode '1.0.69'; $c71 = Get-ProductCode '1.0.71'
                $loc = (Join-Path $pf 'DRAUPNE Link') + '\\'
                $r = [ordered]@{
                    none = (Test-Installed @() '1.0.69' $pf)
                    other = (Test-Installed @(Entry '1.0.71' $c71 $loc $hklm 'DRAUPNE Link') '1.0.69' $pf)
                    twice = (Test-Installed @((Entry '1.0.69' $c69 $loc $hklm 'DRAUPNE Link'), (Entry '1.0.71' $c71 $loc $hklm 'DRAUPNE Link')) '1.0.69' $pf)
                    code = (Test-Installed @(Entry '1.0.69' $c71 $loc $hklm 'DRAUPNE Link') '1.0.69' $pf)
                    where = (Test-Installed @(Entry '1.0.69' $c69 'C:\\elsewhere\\' $hklm 'DRAUPNE Link') '1.0.69' $pf)
                    user = (Test-Installed @(Entry '1.0.69' $c69 $loc 'HKEY_CURRENT_USER\\Software\\x' 'DRAUPNE Link') '1.0.69' $pf)
                    old = (Test-Installed @((Entry '1.0.69' $c69 $loc $hklm 'DRAUPNE Link'), (Entry '1.0.15' 'x' 'y' $hklm 'DroidDrone PC')) '1.0.69' $pf)
                    right = (Test-Installed @(Entry '1.0.69' $c69 $loc $hklm 'DRAUPNE Link') '1.0.69' $pf)
                    folder = (Test-ProgramFolder (Join-Path $pf 'DRAUPNE Link') '1.0.69')
                    folderNewer = (Test-ProgramFolder (Join-Path $pf 'DRAUPNE Link') '1.0.72')
                }
                """ + "$r.liar = (Test-ProgramFolder " + q(dir.resolve("liar/DRAUPNE Link")) + " '1.0.69')\n"
                + "$r.two = (Test-ProgramFolder " + q(dir.resolve("two/DRAUPNE Link")) + " '1.0.69')\n"
                + "'JSON:' + (ConvertTo-Json -Compress -InputObject $r)").json().getAsJsonObject();
        assertEquals("DRAUPNE Link is not installed (expected 1.0.69)", r.get("none").getAsString());
        assertEquals("DRAUPNE Link 1.0.71 is installed, not 1.0.69", r.get("other").getAsString());
        assertTrue(r.get("twice").getAsString().startsWith("DRAUPNE Link is installed 2 times (1.0.69, 1.0.71)"), r.toString());
        assertTrue(r.get("code").getAsString().contains("product code"), r.toString());
        assertTrue(r.get("where").getAsString().startsWith("DRAUPNE Link is installed in C:\\elsewhere\\"), r.toString());
        assertTrue(r.get("user").getAsString().contains("for one user only"), r.toString());
        assertEquals("DroidDrone PC 1.0.15 is installed too", r.get("old").getAsString());
        assertTrue(r.get("right").isJsonNull(), r.toString());
        assertTrue(r.get("folder").isJsonNull(), r.toString());
        assertTrue(r.get("folderNewer").getAsString().endsWith("does not name draupne-link-1.0.72.jar"), r.toString());
        assertEquals("draupne-link-1.0.69.jar says it is 1.0.70", r.get("liar").getAsString());
        assertTrue(r.get("two").getAsString().endsWith("also holds draupne-link-1.0.72.jar"), r.toString());
    }

    /** The whole script, sealed: an installed version other than the one asked for is refused at its first step. */
    @Test
    void theWholeScriptRefusesBeforeItCopiesClosesOrInstallsAnything() throws Exception {
        assumeTrue(windows());
        Path setup = dir.resolve("DRAUPNE-Link-Setup-9.9.10.exe");
        Files.writeString(setup, "not an installer");
        Path root = dir.resolve("root");
        Map<String, Path> env = sealed();
        write(env.get("APPDATA").resolve("DraupneLink/draupne-link.properties"), "rcRate=250\n");
        Map<String, String> pcBefore = pcFiles();
        // -AllowElevated: a CI runner is an administrator; the seal and these folders keep it from everything
        Ran upgrade = whole("-Installer", setup.toString(), "-Expect", "9.9.9", "-AllowElevated", "-Root", root.toString());
        assertEquals(2, upgrade.exit(), upgrade.out());
        assertTrue(Pattern.compile("REFUSED: DRAUPNE Link (is not installed \\(expected 9\\.9\\.9\\)|\\S+ is installed, not 9\\.9\\.9|is installed \\d+ times)").matcher(upgrade.out()).find(), upgrade.out());
        assertTrue(upgrade.out().contains("Nothing was copied, closed or installed."), upgrade.out());

        Ran back = whole("-Back", "-AllowElevated", "-Root", root.toString());
        assertEquals(2, back.exit(), back.out());
        assertTrue(back.out().contains("REFUSED: no upgrade run folder"), back.out());

        Ran none = whole();
        assertEquals(2, none.exit(), none.out());
        assertTrue(none.out().startsWith("One of: -Check"), none.out());

        assertFalse(Files.exists(root), "no run folder");
        assertFalse(Files.exists(env.get("LOCALAPPDATA").resolve("DraupneBench")), "nothing in the bench's folder");
        assertEquals(pcBefore, pcFiles(), "nothing written into the PC's folders");
    }

    @Test
    void theFirewallTheLogFoldersRightsAndTheInstallersLogAreChecked() throws Exception {
        assumeTrue(windows());
        JsonObject r = code("""
                function Rule($remote, $port) { [pscustomobject]@{ Name = 'x'; Enabled = 'True'; Direction = 'Inbound'; Action = 'Allow'; Profile = 'Any'; Protocol = 'UDP'; LocalPort = $port; Remote = $remote } }
                function State($link, $apk, $old) { [pscustomobject]@{ LinkRule = @($link); ApkRule = @($apk); OldRules = @($old) } }
                $tailscale = 'LocalSubnet,' + (ConvertTo-Prefix '100.64.0.0/255.192.0.0')
                $before = State (Rule 'Any' '4100') (Rule $tailscale '4200') @()
                $out = [ordered]@{}
                $cases = [ordered]@{
                    narrowed = (State (Rule '100.64.0.0/10,LocalSubnet' '4100') (Rule $tailscale '4200') @())
                    open = (State (Rule 'Any' '4100') (Rule $tailscale '4200') @())
                    twice = (State @((Rule $tailscale '4100'), (Rule $tailscale '4100')) (Rule $tailscale '4200') @())
                    moved = (State (Rule $tailscale '4101') (Rule $tailscale '4200') @())
                    old = (State (Rule $tailscale '4100') (Rule $tailscale '4200') (Rule 'Any' '4100'))
                }
                foreach ($k in $cases.Keys) { $script:Checks.Clear(); Test-FirewallRules $before $cases[$k]; $out[$k] = @($script:Checks | ForEach-Object { $_.Check + ' ' + $_.Verdict + ' ' + $_.Detail }) }

                function Ace($sid, $rights, $inh) { [pscustomobject]@{ Sid = $sid; Rights = $rights; Type = 'Allow'; Inherited = $inh } }
                function Rights($owner, $protected, $rules, $link) { [pscustomobject]@{ Path = 'p'; Link = $link; Protected = $protected; Owner = $owner; Rules = @($rules) } }
                $ok = @((Ace 'S-1-5-18' 'FullControl' $false), (Ace 'S-1-5-32-544' 'FullControl' $false), (Ace 'S-1-5-32-545' 'ReadAndExecute, Synchronize' $false))
                $log = Rights 'S-1-5-18' $false @((Ace 'S-1-5-18' 'FullControl' $true), (Ace 'S-1-5-32-544' 'FullControl' $true), (Ace 'S-1-5-32-545' 'ReadAndExecute, Synchronize' $true)) $false
                $asBefore = Rights 'S-1-5-18' $false @((Ace 'S-1-5-18' 'FullControl' $true), (Ace 'S-1-5-32-544' 'FullControl' $true), (Ace 'S-1-3-0' '268435456' $true), (Ace 'S-1-5-32-545' 'ReadAndExecute, Synchronize' $true), (Ace 'S-1-5-32-545' 'Write' $true)) $false
                $rights = [ordered]@{
                    fine = @((Rights 'S-1-5-32-544' $true $ok $false), $log)
                    ofSystem = @((Rights 'S-1-5-18' $true $ok $false), $log)
                    asBefore = @($asBefore, $log)
                    someoneElses = @((Rights 'S-1-5-21-1-2-3-1001' $true $ok $false), $log)
                    aLink = @((Rights 'S-1-5-32-544' $true $ok $true), $log)
                    logWritable = @((Rights 'S-1-5-32-544' $true $ok $false), (Rights 'S-1-5-18' $false @((Ace 'S-1-5-18' 'FullControl' $true), (Ace 'S-1-5-32-545' 'Write' $false)) $false))
                }
                foreach ($k in $rights.Keys) { $script:Checks.Clear(); Test-LogRights $rights[$k][0] $rights[$k][1]; $out[$k] = $script:Checks[0].Verdict + ' ' + $script:Checks[0].Detail }

                $b = @('2026-11-21T13:23:06  Preparing this PC', '2026-11-21T13:23:07  Firewall rules: done')
                $since = [datetime]'2026-11-23T21:00:00'
                $out.logOk = Test-SetupLog $b ($b + '2026-11-23T21:00:30  Preparing this PC' + '2026-11-23T21:00:31  Firewall rules: done' + '2026-11-23T21:00:31  Power plan: already High performance') $since 'Preparing this PC'
                $out.logNoRules = Test-SetupLog $b ($b + '2026-11-23T21:00:30  Preparing this PC' + '2026-11-23T21:00:31  Firewall rules: failed (1: no)') $since 'Preparing this PC'
                $out.logNothing = Test-SetupLog $b $b $since 'Preparing this PC'
                $out.logOld = Test-SetupLog $b ($b + '2026-11-22T10:00:00  Preparing this PC' + '2026-11-22T10:00:00  Firewall rules: done') $since 'Preparing this PC'
                $out.logChanged = Test-SetupLog $b (@('2026-11-21T13:23:06  Preparing') + $b[1] + '2026-11-23T21:00:30  Preparing this PC' + '2026-11-23T21:00:31  Firewall rules: done') $since 'Preparing this PC'
                $out.logUninstall = Test-SetupLog $b ($b + '2026-11-23T21:05:00  Uninstalling: firewall rules removed - done') $since 'Uninstalling: firewall rules removed'
                'JSON:' + (ConvertTo-Json -Compress -Depth 4 -InputObject $out)
                """).json().getAsJsonObject();
        String narrowed = r.get("narrowed").toString();
        assertTrue(narrowed.contains("firewall DRAUPNE-Link-UDP PASS UDP 4100 from 100.64.0.0/10,LocalSubnet (before: from Any) - narrowed"), narrowed);
        assertTrue(narrowed.contains("firewall DRAUPNE-Link-PhoneDownload-TCP PASS"), narrowed);
        assertTrue(r.get("open").toString().contains("firewall DRAUPNE-Link-UDP FAIL from Any, not LocalSubnet,100.64.0.0/10"), r.get("open").toString());
        assertTrue(r.get("twice").toString().contains("FAIL 2 rules of that name"), r.get("twice").toString());
        assertTrue(r.get("moved").toString().contains("FAIL port 4101, was 4100"), r.get("moved").toString());
        assertTrue(r.get("old").toString().contains("firewall old rules FAIL"), r.get("old").toString());

        assertTrue(r.get("fine").getAsString().startsWith("PASS owner S-1-5-32-544, protected True"), r.get("fine").getAsString());
        assertTrue(r.get("ofSystem").getAsString().startsWith("PASS"), "SYSTEM may own it (PcPrep's script takes either)");
        String asBefore = r.get("asBefore").getAsString();
        assertTrue(asBefore.startsWith("FAIL it takes ProgramData's rights; rules: ") && asBefore.contains("S-1-5-32-545=Write (inherited)"), asBefore);
        assertTrue(r.get("someoneElses").getAsString().startsWith("FAIL owner S-1-5-21-1-2-3-1001"), r.get("someoneElses").getAsString());
        assertTrue(r.get("aLink").getAsString().startsWith("FAIL it is a link"), r.get("aLink").getAsString());
        assertTrue(r.get("logWritable").getAsString().startsWith("FAIL setup.log writable by S-1-5-32-545=Write"), r.get("logWritable").getAsString());

        assertTrue(r.get("logOk").isJsonNull(), r.toString());
        assertTrue(r.get("logNoRules").getAsString().startsWith("the firewall rules were not done"), r.toString());
        assertEquals("no new line \"Preparing this PC\" (0 new lines)", r.get("logNothing").getAsString());
        assertTrue(r.get("logOld").getAsString().startsWith("no new line"), "a line from before this run is not this run's: " + r);
        assertEquals("line 1 is not as it was", r.get("logChanged").getAsString());
        assertTrue(r.get("logUninstall").isJsonNull(), r.toString());
    }
}
