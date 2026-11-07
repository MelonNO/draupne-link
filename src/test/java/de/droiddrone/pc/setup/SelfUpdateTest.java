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
}
