package de.droiddrone.pc.setup;

import de.droiddrone.pc.Outside;
import de.droiddrone.testing.TroubledServer;
import de.droiddrone.testing.TroubledServer.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UPDATE's download on a bad internet (2026-11-19). The installer is tens of megabytes read piece by piece; a transfer
 * that stood still half way - nothing more came, no error either - left the window on "Downloading" for good. Now it
 * fails with words after a minute of nothing (here: a second and a half), and nothing half-downloaded is left behind
 * to be run.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SelfUpdateDownloadTest {
    private TroubledServer server;
    private final byte[] installer = new byte[300_000];
    private long stallBefore;

    @BeforeEach
    void up() throws Exception {
        for (int i = 0; i < installer.length; i++) installer[i] = (byte) (i * 31);
        server = new TroubledServer(installer, "application/octet-stream");
        Outside.use(new Outside.Doors() {
            @Override
            public String url(String url) {
                return server.address() + URI.create(url).getPath();
            }
        });
        stallBefore = SelfUpdate.stallMs;
        SelfUpdate.stallMs = 1500;
    }

    @AfterEach
    void down() throws Exception {
        SelfUpdate.stallMs = stallBefore;
        Outside.use(null);
        server.close();
    }

    private UpdateCheck.Release release(String name) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(installer));
        return new UpdateCheck.Release("9.9.9", "https://github.com/x/y/releases/tag/v9.9.9",
                "https://github.com/x/y/releases/download/v9.9.9/" + name, installer.length, sha);
    }

    private static Path target(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), "DraupneLink-update", name);
    }

    @Test
    void aDownloadThatStandsStillFailsWithWordsAndLeavesNoHalfInstaller() throws Exception {
        server.always(Mode.HALF);
        AtomicLong got = new AtomicLong();
        long t0 = System.currentTimeMillis();
        IOException e = assertThrows(IOException.class, () -> SelfUpdate.download(release("stalled-test-setup.exe"), (done, total) -> got.set(done)));
        long took = System.currentTimeMillis() - t0;
        assertTrue(e.getMessage().contains("stalled"), "the failure says what happened: " + e.getMessage());
        assertTrue(took < 10_000, "given up after " + took + " ms, not waited for without end");
        assertTrue(got.get() > 0 && got.get() <= installer.length / 2, "half of it had come: " + got.get() + " bytes");
        assertFalse(Files.exists(target("stalled-test-setup.exe")), "no half installer is left behind");
    }

    @Test
    void aServerThatHangsUpFailsAndLeavesNothing() throws Exception {
        server.always(Mode.HANG_UP);
        assertThrows(IOException.class, () -> SelfUpdate.download(release("hangup-test-setup.exe"), (done, total) -> { }));
        assertFalse(Files.exists(target("hangup-test-setup.exe")));
    }

    @Test
    void anAnswerThatIsNotTheInstallerIsNeverKept() throws Exception {
        server.close();
        server = new TroubledServer("<html>Sign in to continue</html>".getBytes(), "text/html");
        IOException e = assertThrows(IOException.class, () -> SelfUpdate.download(release("signin-test-setup.exe"), (done, total) -> { }));
        assertTrue(e.getMessage().contains("damaged"), e.getMessage());
        assertFalse(Files.exists(target("signin-test-setup.exe")), "what came is not kept: it would be run");
    }
}
