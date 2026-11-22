package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The folder the installer's tasks keep their log in (ProgramData\DraupneLink, written as administrator or SYSTEM):
 * made so that only administrators and SYSTEM can write in it, an older install's folder brought to the same rights,
 * and a folder or log file that is a link - or a folder somebody else owns - not written into at all.
 * <p>
 * Run here without administrator rights, in a folder of the test's own: the script as the installer's tasks run it
 * but for the names in it - "whoever owns what this test makes" where the app says Administrators and SYSTEM. What
 * the real names give in ProgramData, as SYSTEM, is not run here.
 */
class PcPrepLogTest {
    @TempDir
    Path dir;

    private final List<Path> links = new java.util.ArrayList<>();

    /** The links go first, each itself and not what it leads to: the test's folder is cleared away after. */
    @org.junit.jupiter.api.AfterEach
    void removeLinks() throws IOException {
        for (Path link : links) Files.deleteIfExists(link);
    }

    /** A junction, made as any user can make one. */
    private void junction(Path link, Path target) {
        Shell.Result made = Shell.run(Duration.ofSeconds(30), "cmd", "/c", "mklink", "/J", link.toString(), target.toString());
        assertTrue(made.ok(), made.output());
        links.add(link);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private static String ps(String script) {
        Shell.Result r = Shell.powershell(Duration.ofSeconds(60), script);
        assertTrue(r.ok(), r.output());
        return r.output().strip();
    }

    /** The owner (its SID) of what this test makes: the user, or the Administrators group when the tests run as administrator. */
    private String ownerOfMine() throws IOException {
        Path probe = Files.createDirectory(dir.resolve("probe"));
        return ps("(Get-Item -LiteralPath " + Shell.psQuote(probe.toString())
                + ").GetAccessControl('Owner').GetOwner([System.Security.Principal.SecurityIdentifier]).Value");
    }

    private static String me() {
        return ps("[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value");
    }

    /** The folder's rights as Windows has them: "protected|owner|sid=rights;sid=rights;..." (inherited rules marked), the rules in order. */
    private static String rights(Path folder) {
        String[] p = ps("$a = (Get-Item -LiteralPath " + Shell.psQuote(folder.toString()) + ").GetAccessControl('Access, Owner')\n"
                + "$rules = $a.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]) | ForEach-Object {"
                + " $_.IdentityReference.Value + '=' + $_.FileSystemRights + $(if ($_.IsInherited) { ' (inherited)' } else { '' }) }\n"
                + "[string]$a.AreAccessRulesProtected + '|' + $a.GetOwner([System.Security.Principal.SecurityIdentifier]).Value + '|' + ($rules -join ';')")
                .split("\\|", 3);
        return p[0] + "|" + p[1] + "|" + String.join(";", java.util.Arrays.stream(p.length > 2 ? p[2].split(";") : new String[0]).sorted().toList());
    }

    private String run(Path folder, String owner, List<String> full) {
        Shell.Result r = Shell.powershell(Duration.ofSeconds(60), PcPrep.logFolderScript(folder, owner, full, PcPrep.SID_USERS));
        String[] lines = r.output().strip().split("\\R");
        return (PcPrep.logFolderReady(r) ? "ready: " : "not ready: ") + lines[lines.length - 1];
    }

    // ------------------------------------------------------------------ the script as the app builds it

    @Test
    void theScriptGivesTheFolderToAdministratorsAndSystemOnly() {
        String s = PcPrep.logFolderScript(Path.of("C:\\ProgramData\\Draupne's Link"), PcPrep.SID_ADMINISTRATORS,
                List.of(PcPrep.SID_SYSTEM, PcPrep.SID_ADMINISTRATORS), PcPrep.SID_USERS);
        assertTrue(s.contains("$d = 'C:\\ProgramData\\Draupne''s Link'"), "the folder quoted: " + s);
        // by their numbers, which every Windows knows them by whatever its language
        assertEquals("S-1-5-18", PcPrep.SID_SYSTEM);
        assertEquals("S-1-5-32-544", PcPrep.SID_ADMINISTRATORS);
        assertEquals("S-1-5-32-545", PcPrep.SID_USERS);
        assertTrue(s.contains("'S-1-5-18' 'FullControl'") && s.contains("'S-1-5-32-544' 'FullControl'"), s);
        assertTrue(s.contains("'S-1-5-32-545' 'ReadAndExecute'"), "everybody else reads: " + s);
        assertEquals(2, s.split("'FullControl'", -1).length - 1, "full control for those two and nobody else: " + s);
        assertFalse(s.contains("Modify") || s.contains("Write"), "no right to write for anybody else: " + s);
        assertTrue(s.contains("$acl.SetAccessRuleProtection($true, $false)"), "nothing taken over from ProgramData's own rights: " + s);
        assertTrue(s.contains("$acl.SetOwner((& $sid 'S-1-5-32-544'))"), s);
        assertTrue(s.contains("[System.IO.Directory]::CreateDirectory($d, $acl)"), "made with its rights in one step: " + s);
        assertTrue(s.indexOf("ReparsePoint") > 0 && s.indexOf("ReparsePoint") < s.indexOf("SetAccessControl"), "a link is refused before anything is set: " + s);
        assertTrue(s.contains("@('S-1-5-18', 'S-1-5-32-544') -notcontains"), "an existing folder must be one of theirs: " + s);
        assertThrows(IllegalArgumentException.class, () -> PcPrep.logFolderScript(dir, "Administrators", List.of(PcPrep.SID_SYSTEM), PcPrep.SID_USERS),
                "names are not taken: they differ with Windows' language");
        assertThrows(IllegalArgumentException.class, () -> PcPrep.logFolderScript(dir, PcPrep.SID_SYSTEM, List.of("S-1-5-18'; x"), PcPrep.SID_USERS));
        // what the script says is what counts, not that it ran
        assertTrue(PcPrep.logFolderReady(new Shell.Result(0, "OK\r\n")));
        assertFalse(PcPrep.logFolderReady(new Shell.Result(0, "REFUSED: a link\r\n")));
        assertFalse(PcPrep.logFolderReady(new Shell.Result(0, "")), "a stand-in's empty answer is no yes");
        assertFalse(PcPrep.logFolderReady(new Shell.Result(1, "OK")));
    }

    // ------------------------------------------------------------------ the script run, on folders of the test's own

    @Test
    void aNewFolderIsMadeWithItsRightsAndAnOlderOneBroughtToThem() throws Exception {
        assumeTrue(windows());
        String owner = ownerOfMine(), me = me();
        List<String> full = owner.equals(me) ? List.of(me) : List.of(owner, me);
        String expected = "True|" + owner + "|" + String.join(";", java.util.stream.Stream.concat(full.stream().map(s -> s + "=FullControl"),
                java.util.stream.Stream.of(PcPrep.SID_USERS + "=ReadAndExecute, Synchronize")).sorted().toList());

        Path fresh = dir.resolve("fresh");
        assertEquals("ready: OK", run(fresh, owner, full));
        assertEquals(expected, rights(fresh), "made for those named and nobody else, nothing inherited");

        // an install from before: the folder as Windows makes one, with its parent's rights
        Path older = Files.createDirectory(dir.resolve("older"));
        Files.writeString(older.resolve("setup.log"), "2026-11-01T10:00:00  Preparing this PC\n");
        assertTrue(rights(older).contains("(inherited)"), "it took its parent's rights: " + rights(older));
        assertEquals("ready: OK", run(older, owner, full));
        assertEquals(expected, rights(older), "brought to the same rights");
        assertEquals("2026-11-01T10:00:00  Preparing this PC\n", Files.readString(older.resolve("setup.log")), "its log stays");
        assertEquals("ready: OK", run(older, owner, full), "and again: as it is");
    }

    @Test
    void aFolderThatIsALinkOrSomebodyElsesOrNoFolderIsRefused() throws Exception {
        assumeTrue(windows());
        String owner = ownerOfMine(), me = me();
        List<String> full = List.of(owner, me);

        // a junction to another folder (made as any user can make one)
        Path target = Files.createDirectory(dir.resolve("elsewhere"));
        Path link = dir.resolve("linked");
        junction(link, target);
        String before = rights(target);
        assertEquals("not ready: REFUSED: a link", run(link, owner, full));
        assertEquals(before, rights(target), "the folder it leads to is left as it was");
        assertThrows(IOException.class, () -> PcPrep.append(link.resolve("setup.log"), "line\n"), "and nothing is written through it");
        assertFalse(Files.exists(target.resolve("setup.log")));

        // a folder somebody else owns: here, one the test made while the script is told only SYSTEM's count
        Path theirs = Files.createDirectory(dir.resolve("theirs"));
        String theirRights = rights(theirs);
        assertEquals("not ready: REFUSED: owner " + owner, run(theirs, PcPrep.SID_SYSTEM, List.of(PcPrep.SID_SYSTEM)));
        assertEquals(theirRights, rights(theirs), "left as it was");

        // a file where the folder should be
        Path file = Files.writeString(dir.resolve("a-file"), "x");
        assertEquals("not ready: REFUSED: not a folder", run(file, owner, full));
    }

    // ------------------------------------------------------------------ the log's lines

    @Test
    void linesAreAddedToALogThatIsAFileAndToNothingElse() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("log"));
        Path log = folder.resolve("setup.log");
        PcPrep.append(log, "one\n");
        PcPrep.append(log, "two\n");
        assertEquals("one\ntwo\n", Files.readString(log, StandardCharsets.UTF_8));
        assertFalse(PcPrep.isLink(folder));
        assertFalse(PcPrep.isLink(log));

        assumeTrue(windows());
        // the log's name taken by a junction: not written through, not replaced
        Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        Path folder2 = Files.createDirectory(dir.resolve("log2"));
        Path taken = folder2.resolve("setup.log");
        junction(taken, elsewhere);
        assertTrue(PcPrep.isLink(taken));
        assertThrows(IOException.class, () -> PcPrep.append(taken, "three\n"));
        try (java.util.stream.Stream<Path> in = Files.list(elsewhere)) {
            assertEquals(0, in.count(), "nothing landed where the link leads");
        }
        // a symbolic link to a file, where this user may make one (Windows asks a right for it)
        Path real = Files.writeString(dir.resolve("real.txt"), "kept\n");
        Path folder3 = Files.createDirectory(dir.resolve("log3"));
        Path symlink = folder3.resolve("setup.log");
        try {
            Files.createSymbolicLink(symlink, real);
        } catch (IOException | UnsupportedOperationException e) {
            return; // not allowed to make one here
        }
        links.add(symlink);
        assertTrue(PcPrep.isLink(symlink));
        assertThrows(IOException.class, () -> PcPrep.append(symlink, "four\n"));
        assertEquals("kept\n", Files.readString(real), "the file it leads to is untouched");
    }
}
