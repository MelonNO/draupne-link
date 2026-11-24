package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** DOWNLOAD AREA's buttons: PAUSE holds the download and RESUME lets it go on (PAUSE had no action: it did nothing). */
class MapDownloadDialogTest {
    private static void collect(Container c, List<Component> out) {
        for (Component k : c.getComponents()) {
            out.add(k);
            if (k instanceof Container cc) collect(cc, out);
        }
    }

    private static AbstractButton button(Container root, String text) {
        List<Component> all = new ArrayList<>();
        collect(root, all);
        for (Component c : all) if (c instanceof AbstractButton b && b.getText().equalsIgnoreCase(text)) return b;
        return fail("no button " + text);
    }

    private static String status(Container root) {
        List<Component> all = new ArrayList<>();
        collect(root, all);
        StringBuilder sb = new StringBuilder();
        for (Component c : all) if (c instanceof JLabel l) sb.append(l.getText()).append(" | ");
        return sb.toString();
    }

    @Test
    void pauseHoldsTheDownloadAndResumeLetsItGoOn(@TempDir Path dir) throws Exception {
        AtomicBoolean flying = new AtomicBoolean(true); // nothing is fetched meanwhile: the download waits, as while a drone may fly
        TileCache cache = TileCache.heldWhile(dir, flying::get);
        JFrame owner = new JFrame();
        MapDownloadDialog[] dialog = new MapDownloadDialog[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                Theme.install();
                dialog[0] = new MapDownloadDialog(owner, cache, TileSource.KARTVERKET_TOPO, 12, new double[]{59.90, 59.91, 10.70, 10.72},
                        () -> { }, line -> { });
                button(dialog[0].getContentPane(), "Start").doClick();
                AbstractButton pause = button(dialog[0].getContentPane(), "PAUSE");
                assertTrue(pause.isVisible());
                pause.doClick();
                assertEquals("RESUME", pause.getText(), "PAUSE holds it");
                assertTrue(status(dialog[0].getContentPane()).contains("PAUSED"), status(dialog[0].getContentPane()));
                pause.doClick();
                assertEquals("PAUSE", pause.getText(), "RESUME lets it go on");
                assertFalse(status(dialog[0].getContentPane()).contains("PAUSED"), status(dialog[0].getContentPane()));
                button(dialog[0].getContentPane(), "Cancel").doClick();
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialog[0] != null) dialog[0].dispose();
                owner.dispose();
            });
        }
    }
}
