package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.overwatch.ClipIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 1.0.56, the pilot: "when play is clicked have it open the video in a large window so it is possible to see it". */
class OverwatchClipWindowTest {
    @Test
    void playOpensTheClipInALargeResizableWindowAndClosingItStops(@TempDir Path dir) throws Exception {
        ClipIndex index = new ClipIndex(dir.resolve("Overwatch"));
        String id = index.newId("MobileAlpha", 1_762_500_000_000L);
        Properties m = new Properties();
        m.setProperty("startedAt", "1762500000000");
        index.save("MobileAlpha", id, m);
        ClipIndex.Clip clip = index.clips("MobileAlpha").get(0);
        MapView map = new MapView(new TileCache(dir, "test", false));
        AtomicReference<JFrame> frame = new AtomicReference<>();
        AtomicReference<OverwatchPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            OverwatchPanel p = new OverwatchPanel(map, index, null, () -> de.droiddrone.pc.map.Coordinates.Format.MGRS,
                    d -> { }, d -> { }, d -> { }, () -> { });
            JFrame f = new JFrame("map");
            f.add(p);
            f.setBounds(0, 0, 400, 300);
            frame.set(f);
            panel.set(p);
            p.play(clip);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                JDialog w = panel.get().playerWindow();
                assertNotNull(w, "PLAY opens the player window");
                assertTrue(w.isVisible());
                assertTrue(w.isResizable());
                assertTrue(w.getWidth() >= 2 * OverwatchPanel.PANEL_WIDTH, "large: " + w.getSize());
                assertTrue(w.getTitle().contains("MobileAlpha"), w.getTitle());
                w.dispatchEvent(new java.awt.event.WindowEvent(w, java.awt.event.WindowEvent.WINDOW_CLOSING));
                assertFalse(w.isVisible(), "closed");
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> frame.get().dispose());
        }
    }

    @Test
    void thePlayerTakesMostOfTheScreenAt16To9() {
        Rectangle r = OverwatchPanel.playerBounds(new Rectangle(0, 0, 1920, 1080));
        assertTrue(r.width >= 1200 && r.height <= 1080 * 3 / 4 + 1, r.toString());
        assertTrue(new Rectangle(0, 0, 1920, 1080).contains(r), "on the screen: " + r);
        Rectangle small = OverwatchPanel.playerBounds(new Rectangle(1920, 0, 1366, 768));
        assertTrue(new Rectangle(1920, 0, 1366, 768).contains(small), "on a small second screen: " + small);
    }
}
