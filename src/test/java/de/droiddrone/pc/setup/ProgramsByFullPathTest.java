package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every program the app starts is started by its full path: Windows' own tools from Windows' own folders
 * ({@link Shell#withFullPath}), this app and Tailscale from where they are installed - so the program that runs is
 * the one meant, whatever lies beside the app or in the folder it was started from. The app's sources are read as
 * text (as TestHygieneTest reads the tests): this finds the usual ways of writing a start, not every way.
 */
class ProgramsByFullPathTest {
    /** A process made from a name written into the code: new ProcessBuilder("x.exe", ...), or a list that begins so. */
    private static final Pattern NAMED_IN_CODE = Pattern.compile(
            "new\\s+ProcessBuilder\\s*\\(\\s*(?:(?:java\\.util\\.)?(?:List\\.of|Arrays\\.asList)\\s*\\(\\s*|new\\s+String\\s*\\[\\s*]\\s*\\{\\s*)?\"");
    /** The other way to start one, which takes a whole command line: not used, Shell and ProcessBuilder are. */
    private static final Pattern RUNTIME_EXEC = Pattern.compile("Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\s*\\(");
    /** In one of the app's PowerShell scripts: Start-Process with a program's bare name, not a path in a variable or in quotes. */
    private static final Pattern SCRIPT_START = Pattern.compile("Start-Process\\s+(?:-FilePath\\s+)?[A-Za-z][\\w.-]*(?:\\s|\"|$)");
    /** A script line that begins with one of Windows' tools by its bare name. */
    private static final Pattern SCRIPT_TOOL = Pattern.compile(
            "\"(?:netsh|powercfg|schtasks|msiexec|icacls|takeown|powershell|explorer|wmic|certutil|bcdedit|tasklist|taskkill|whoami|robocopy|cmd\\.exe)(?:\\.exe)? ");

    @Test
    void noProgramIsStartedByABareName() throws Exception {
        Path root = Path.of("").toAbsolutePath(); // the tests run in build/test-work
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root, "the project's root");
        List<String> found = new ArrayList<>();
        int read = 0;
        // everything the app is compiled from (build.gradle's source folders)
        for (String sources : new String[]{"src/main/java", "flight-app/overlay/Common/src/main/java", "upstream/Common/src/main/java", "upstream/Server/src"}) {
            Path dir = root.resolve(sources);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    read++;
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i), code = line.strip();
                        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) continue;
                        String at = root.relativize(f).toString().replace('\\', '/') + ":" + (i + 1) + ": " + code;
                        if (NAMED_IN_CODE.matcher(line).find()) found.add(at + "  <- Shell.withFullPath");
                        if (RUNTIME_EXEC.matcher(line).find()) found.add(at + "  <- Shell.run or a ProcessBuilder with a full path");
                        if (SCRIPT_START.matcher(line).find()) found.add(at + "  <- the program's full path");
                        if (SCRIPT_TOOL.matcher(line).find()) found.add(at + "  <- the tool's full path (Join-Path $env:SystemRoot ...)");
                    }
                }
            }
        }
        assertTrue(read > 300, "the app's sources were found: " + read + " files under " + root);
        assertEquals(List.of(), found, "programs started by a bare name");
    }

    @Test
    void thePatternsFindWhatTheyAreFor() {
        for (String bare : List.of("        de.droiddrone.pc.Outside.start(new ProcessBuilder(\"powershell.exe\", \"-NoProfile\",",
                "                Outside.start(new ProcessBuilder(\"explorer.exe\", dir.toString()));",
                "            new ProcessBuilder(List.of(\"cmd\", \"/c\", x)).start();",
                "            new ProcessBuilder(new String[]{\"tool\"}).start();")) {
            assertTrue(NAMED_IN_CODE.matcher(bare).find(), bare);
        }
        for (String fine : List.of("            ProcessBuilder builder = new ProcessBuilder(withFullPath(command));",
                "            de.droiddrone.pc.Outside.start(new ProcessBuilder(app.toString()));",
                "            p = new ProcessBuilder(cmd).directory(folder.toFile()).redirectErrorStream(true)",
                "                Outside.start(new ProcessBuilder(Shell.withFullPath(List.of(\"explorer.exe\", dir.toString()))));")) {
            assertFalse(NAMED_IN_CODE.matcher(fine).find(), fine);
        }
        assertTrue(RUNTIME_EXEC.matcher("        Runtime.getRuntime().exec(\"netsh advfirewall\");").find());
        for (String bare : List.of("        String launcher = \"Start-Process powershell.exe -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList \"",
                "                \"Start-Process -FilePath msiexec.exe -Wait\",",
                "                \"Start-Process notepad\",")) {
            assertTrue(SCRIPT_START.matcher(bare).find(), bare);
        }
        for (String fine : List.of("                \"$p = Start-Process -FilePath \" + quote(installer) + \" -ArgumentList '/passive','/norestart' -Wait -PassThru\",",
                "                \"  if (Test-Path -LiteralPath $app) { Note ('starting ' + $app); Start-Process -FilePath $app } else { Note 'x' }\",",
                "                \"    $p = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\\\\msiexec.exe') \"",
                "        String launcher = \"Start-Process -FilePath \" + psQuote(powershell.toString()) + \" -Verb RunAs -Wait\"")) {
            assertFalse(SCRIPT_START.matcher(fine).find(), fine);
        }
        assertTrue(SCRIPT_TOOL.matcher("                \"netsh advfirewall firewall delete rule name=\" + LINK_RULE + \" | Out-Null\",").find());
        for (String fine : List.of("        return Shell.run(Duration.ofSeconds(15), \"netsh\", \"advfirewall\", \"firewall\", \"show\", \"rule\", \"name=\" + name).ok();",
                "                \"& $netsh advfirewall firewall delete rule name=\" + LINK_RULE + \" | Out-Null\",",
                "            logLine(\"Explorer didn't open: \" + e.getMessage());")) {
            assertFalse(SCRIPT_TOOL.matcher(fine).find(), fine);
        }
    }

    /** Windows' own tools are found in Windows' own folders; a name that is in none of them is not started at all. */
    @Test
    void windowsToolsAreFoundInWindowsOwnFolders() throws Exception {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        String windows = System.getenv().getOrDefault("SystemRoot", "C:\\Windows").toLowerCase();
        for (String tool : new String[]{"netsh", "powercfg", "powershell.exe", "explorer.exe"}) {
            String full = Shell.withFullPath(List.of(tool, "x")).get(0);
            assertTrue(Path.of(full).isAbsolute() && full.toLowerCase().startsWith(windows + "\\") && Files.isRegularFile(Path.of(full)), tool + " -> " + full);
        }
        assertTrue(Shell.withFullPath(List.of("explorer.exe")).get(0).equalsIgnoreCase(windows + "\\explorer.exe"), "Explorer is in the Windows folder itself");
        assertEquals(List.of("C:\\x\\tool.exe", "a"), Shell.withFullPath(List.of("C:\\x\\tool.exe", "a")), "a full path is kept");
        for (String notOne : new String[]{"no-such-tool", "no-such-tool.exe", "bin\\tool.exe", "..\\tool.exe", ".\\powershell.exe", "sub/tool"}) {
            assertThrows(IOException.class, () -> Shell.withFullPath(List.of(notOne)), notOne + " is not started by that name");
            Shell.Result r = Shell.run(java.time.Duration.ofSeconds(5), notOne);
            assertFalse(r.ok(), notOne);
        }
    }
}
