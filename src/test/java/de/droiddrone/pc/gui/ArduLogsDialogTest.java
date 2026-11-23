package de.droiddrone.pc.gui;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.ardu.ArduUsb;
import de.droiddrone.pc.setup.ardu.FakeArduPilot;
import de.droiddrone.pc.setup.ardu.MavSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static de.droiddrone.testing.Waits.waitForOnSwing;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet AP-10 through SETUP > ArduPilot flight logs: a made-up ArduPilot on made-up USB that drops every third piece
 * of its log - downloaded whole all the same and kept where PREVIOUS FLIGHTS finds it; the window closed while the
 * board is still looked for lets it go. Never shown on screen.
 */
class ArduLogsDialogTest {
    @TempDir
    Path home;
    private String oldHome;
    private FakeArduPilot fc;
    private ArduLogsDialog dialog;

    @BeforeEach
    void setUp() throws Exception {
        oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        fc = new FakeArduPilot();
        BetaflightUsb.use(new BetaflightUsb.Usb() {
            public List<BetaflightUsb.Port> ports() {
                return List.of(new BetaflightUsb.Port("COMFAKE7", "ArduPilot (made up)"));
            }

            public BetaflightUsb.Wire open(BetaflightUsb.Port port) throws java.io.IOException {
                throw new java.io.IOException("no Betaflight here");
            }
        });
        SwingUtilities.invokeAndWait(Theme::install);
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (dialog != null) dialog.dispose();
        });
        ArduUsb.use(null);
        BetaflightUsb.use(null);
        fc.close();
        System.setProperty("user.home", oldHome);
    }

    @Test
    void aLogWithPiecesDroppedIsDownloadedWholeAndKept() throws Exception {
        byte[] log = new byte[9_000];
        new Random(7).nextBytes(log);
        fc.logs.add(new byte[400]);
        fc.logs.add(log);
        fc.dropLogEvery = 3;
        ArduUsb.use(p -> fc);
        SwingUtilities.invokeAndWait(() -> {
            dialog = new ArduLogsDialog(null, null, null);
            dialog.pack(); // displayable as when open (never shown): its close is a real close
        });
        waitForOnSwing(() -> dialog.statusText().startsWith("2 logs"), 15_000, dialog.statusText());
        SwingUtilities.invokeAndWait(() -> dialog.downloadButton().doClick()); // the newest is chosen
        waitForOnSwing(() -> dialog.statusText().startsWith("Saved") || dialog.statusText().startsWith("Not"), 30_000, dialog.statusText());
        assertTrue(dialog.statusText().startsWith("Saved"), dialog.statusText());
        try (var files = Files.list(home.resolve("Videos").resolve("DRAUPNE Link").resolve("ArduPilot logs"))) {
            Path f = files.findFirst().orElseThrow();
            assertArrayEquals(log, Files.readAllBytes(f), "whole, the dropped pieces asked for again");
            assertTrue(f.getFileName().toString().startsWith("USB COMFAKE7 "), f.toString());
        }
        assertTrue(dialog.downloadButton().isEnabled());
    }

    @Test
    void closedWhileTheBoardIsLookedForTheBoardIsLetGo() throws Exception {
        CountDownLatch listening = new CountDownLatch(1), closedNow = new CountDownLatch(1);
        ArduUsb.use(p -> new MavSession.Wire() { // the board answers only once the window is closed
            public void write(byte[] data) throws java.io.IOException {
                fc.write(data);
            }

            public int read(byte[] buf, int timeoutMs) throws java.io.IOException {
                listening.countDown();
                try {
                    closedNow.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return 0;
                }
                return fc.read(buf, timeoutMs);
            }

            public void close() {
                fc.close();
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            dialog = new ArduLogsDialog(null, null, null);
            dialog.pack(); // displayable as when open (never shown): its close is a real close
        });
        assertTrue(listening.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> dialog.dispose());
        closedNow.countDown();
        // the session the search finds after the close is closed at once: its wire with it
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end && fc.read(new byte[64], 10) >= 0) Thread.sleep(20);
        assertEquals(-1, fc.read(new byte[64], 10), "the board's port is let go");
    }
}
