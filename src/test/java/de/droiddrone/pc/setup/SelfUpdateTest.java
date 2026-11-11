package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The self-update's signature check (1.0.49, the review's L8): the rule, and the helper that runs it again. */
class SelfUpdateTest {

    @Test
    void whileReleasesAreUnsignedAnUnsignedInstallerPassesButABrokenSignatureNever() {
        assertNull(SelfUpdate.signatureVerdict("NotSigned", null, null));
        assertNull(SelfUpdate.signatureVerdict("Valid", "CN=Someone", null));
        assertNotNull(SelfUpdate.signatureVerdict("HashMismatch", "CN=Someone", null), "signed, then changed");
        assertNotNull(SelfUpdate.signatureVerdict("NotTrusted", "CN=Someone", null));
        assertNotNull(SelfUpdate.signatureVerdict("UnknownError", null, null));
        assertNotNull(SelfUpdate.signatureVerdict("", null, null), "no answer from the check: not run");
        assertNotNull(SelfUpdate.signatureVerdict(null, null, null));
    }

    @Test
    void onceASignerIsSetOnlyItsValidSignatureRuns() {
        String signer = "O=Draupne AS";
        assertNull(SelfUpdate.signatureVerdict("Valid", "CN=DRAUPNE Link, O=Draupne AS, C=NO", signer));
        assertNotNull(SelfUpdate.signatureVerdict("Valid", "CN=Someone Else, O=Other", signer));
        assertNotNull(SelfUpdate.signatureVerdict("NotSigned", null, signer));
        assertNotNull(SelfUpdate.signatureVerdict("HashMismatch", "CN=DRAUPNE Link, O=Draupne AS", signer));
    }

    @Test
    void theHelperChecksTheSignatureAfterTheChecksumAndBeforeItRunsTheInstaller() {
        Path installer = Path.of("C:\\Temp\\DraupneLink-update\\DRAUPNE-Link-Setup-1.0.49.exe");
        String script = SelfUpdate.script(installer, 1234, Path.of("C:\\Program Files\\DRAUPNE Link\\DRAUPNE Link.exe"), "ab".repeat(32));
        int hash = script.indexOf("Get-FileHash");
        int sig = script.indexOf("Get-AuthenticodeSignature");
        int run = script.indexOf("$p = Start-Process -FilePath");
        assertTrue(hash > 0 && sig > hash && run > sig, "checksum, signature, then the installer");
        assertTrue(script.contains("$st -ne 'NotSigned'"), "unsigned releases still update while SIGNER is null");
        String strict = SelfUpdate.signatureCheck(installer, "O=It's Us");
        assertTrue(strict.contains("-notmatch 'O=It''s Us'"), "the signer quoted for PowerShell: " + strict);
        assertTrue(strict.contains("$lock.Close(); Finish"), "a refusal lets go of the file and starts the old app again");
    }

    /**
     * 1.0.64, a pilot after an update: "if they try to manually open the program from inside its installed folder they
     * get ... this copy is not the installed one (runs from the user's download folder)". A copy elsewhere updates
     * the installed one; the release page only when none is installed.
     */
    @Test
    void aCopyElsewhereUpdatesTheInstalledOne() {
        String pf = "C:\\Program Files";
        Path installed = Path.of(pf, "DRAUPNE Link", "DRAUPNE Link.exe");
        assertEquals(installed, SelfUpdate.installedApp(installed.toString(), pf, p -> false), "the installed one itself");
        String downloads = "C:\\Users\\pilot\\Downloads\\DRAUPNE-Link-1.0.62-windows\\DRAUPNE Link\\DRAUPNE Link.exe";
        assertEquals(installed, SelfUpdate.installedApp(downloads, pf, installed::equals), "from Downloads: the installed one");
        assertNull(SelfUpdate.installedApp(downloads, pf, p -> false), "none installed: the release page");
        assertNull(SelfUpdate.installedApp(null, pf, p -> true), "a development run: the release page");
    }

    /** 1.0.64: which "DRAUPNE Link" shortcuts the installed copy points at itself. */
    @Test
    void aShortcutToNothingOrAnotherCopyIsRepaired() {
        Path launcher = Path.of("C:\\Program Files\\DRAUPNE Link\\DRAUPNE Link.exe");
        java.util.function.Predicate<Path> there = p -> !p.toString().contains("Gone");
        assertFalse(WindowsSetup.needsRepair("c:\\program files\\draupne link\\draupne link.exe", launcher, there), "here already");
        assertTrue(WindowsSetup.needsRepair("C:\\Users\\pilot\\OneDrive\\Desktop\\DRAUPNE Link.exe", launcher, there),
                "a lone launcher copied to the desktop: 'error opening ...\\app\\DRAUPNE Link.cfg'");
        assertTrue(WindowsSetup.needsRepair("C:\\Users\\pilot\\Downloads\\x\\DRAUPNE Link.exe", launcher, there), "a copy in Downloads");
        assertTrue(WindowsSetup.needsRepair("C:\\Gone\\DRAUPNE Link.exe", launcher, there), "leads to nothing");
        assertTrue(WindowsSetup.needsRepair("", launcher, there), "no target");
        assertFalse(WindowsSetup.needsRepair("C:\\Tools\\my-draupne.bat", launcher, there), "the pilot's own: left");
        assertFalse(WindowsSetup.needsRepair(null, launcher, there), "unreadable: left");
    }

    /** 1.0.56, the pilot: "when updating ... check if the user already has a desktop shortcut, and if they don't add one". */
    @Test
    void anUpdateMakesADesktopShortcutOnlyWhenThereIsNone() throws Exception {
        Path installer = Path.of("C:\\Temp\\DraupneLink-update\\DRAUPNE-Link-Setup-1.0.56.exe");
        String script = SelfUpdate.script(installer, 1234, Path.of("C:\\Program Files\\DRAUPNE Link\\DRAUPNE Link.exe"), "ab".repeat(32));
        int ok = script.indexOf("{ EnsureShortcut; Finish ('OK installed");
        assertTrue(ok > script.indexOf("$p = Start-Process -FilePath"), "after a good install, before the app starts again");
        String fn = script.substring(script.indexOf("function EnsureShortcut"), script.indexOf("function Finish"));
        int check = fn.indexOf("foreach ($l in @($shared, $own))");
        assertTrue(check > 0 && check < fn.indexOf("$s = $wsh.CreateShortcut($own)"), "both desktops looked at before one is made: " + fn);
        // 1.0.64: one that is there is checked and pointed at the installed app when it leads to another DRAUPNE Link.exe or nothing
        assertTrue(fn.contains("$t -ieq $a") && fn.contains("-ine 'DRAUPNE Link.exe'") && fn.contains("desktop shortcut repaired"), fn);
        assertTrue(fn.indexOf("if ((Test-Path -LiteralPath $shared) -or (Test-Path -LiteralPath $own)) { return }") < fn.indexOf("$s = $wsh.CreateShortcut($own)"),
                "a new one only when there is none: " + fn);
        assertTrue(fn.contains("'DRAUPNE Link.lnk'"), fn);
        // the whole helper is valid PowerShell (parsed only, nothing run)
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            Path ps1 = java.nio.file.Files.createTempFile("update-helper", ".ps1");
            try {
                java.nio.file.Files.writeString(ps1, script);
                Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "$e = $null; [void][System.Management.Automation.Language.Parser]::ParseFile('" + ps1 + "', [ref]$null, [ref]$e); "
                                + "if ($e.Count) { $e | ForEach-Object { $_.Message }; exit 1 }")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                assertTrue(p.waitFor() == 0, "PowerShell parse errors: " + out);
            } finally {
                java.nio.file.Files.deleteIfExists(ps1);
            }
        }
    }
}
