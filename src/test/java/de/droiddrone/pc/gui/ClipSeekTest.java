package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.ClipTimeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.64: the clip player's bar while paused. Dragging it (also back from the clip's end) moved the bar and the time
 * and showed no picture: one picture of the new place shows, and it stays paused. A clip of its pictures from before
 * the alert only (no video to decode), played without its window.
 */
class ClipSeekTest {
    private static final Color[] COLOURS = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE};

    @Test
    void aSeekWhilePausedShowsThePictureOfThatPlace(@TempDir Path dir) throws Exception {
        ClipIndex index = new ClipIndex(dir.resolve("Overwatch"));
        String id = index.newId("MobileAlpha", 1_762_500_000_000L);
        Properties m = new Properties();
        m.setProperty("startedAt", "1762500000000");
        index.save("MobileAlpha", id, m);
        for (int i = 0; i < COLOURS.length; i++) {
            BufferedImage img = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(COLOURS[i]);
            g.fillRect(0, 0, 64, 36);
            g.dispose();
            Path f = index.folder("MobileAlpha").resolve(String.format(Locale.ROOT, "%s-pre-%013d.jpg", id, 1_762_499_995_000L + i * 1000L));
            assertTrue(ImageIO.write(img, "jpg", f.toFile()));
        }
        ClipIndex.Clip clip = index.clips("MobileAlpha").get(0);
        assertEquals(COLOURS.length, clip.preRoll().size());
        long total = COLOURS.length * ClipTimeline.PRE_MS;

        MapView map = new MapView(new TileCache(dir.resolve("tiles"), "test", false));
        AtomicReference<OverwatchPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            OverwatchPanel p = new OverwatchPanel(map, index, null, () -> Coordinates.Format.MGRS, d -> { }, d -> { }, d -> { }, () -> { });
            ref.set(p);
            p.startPlayback(clip); // PLAY without its window
        });
        OverwatchPanel p = ref.get();
        try {
            await(() -> p.playerPicture() != null, "the clip's first picture");
            SwingUtilities.invokeAndWait(p::pauseOrPlay);
            assertTrue(p.isPaused());
            Thread.sleep(150); // a picture on its way when PAUSE came
            Color before = shown(p);

            p.seekToMs(3 * ClipTimeline.PRE_MS + 200); // the bar let go in the last picture
            await(() -> near(shown(p), Color.WHITE), "paused, the bar moved: the picture of that place (it stayed " + before + ")");
            assertTrue(p.isPaused(), "and it stays paused");

            p.seekToMs(ClipTimeline.PRE_MS + 200); // back
            await(() -> near(shown(p), Color.GREEN), "and back: the second picture");

            p.seekToMs(total); // the bar at its very end: the last picture there is
            await(() -> near(shown(p), Color.WHITE), "the bar at the end: the last picture");
            Thread.sleep(300);
            assertTrue(p.isPaused());
            assertTrue(near(shown(p), Color.WHITE), "it waits there");

            SwingUtilities.invokeAndWait(p::pauseOrPlay); // PLAY at the end: from the start again
            await(() -> near(shown(p), Color.RED), "PLAY at the end starts over");
            assertFalse(p.isPaused());
        } finally {
            SwingUtilities.invokeAndWait(p::dispose);
        }
        assertEquals(0, OverwatchPanel.stillPlace(-5, total));
        assertEquals(700, OverwatchPanel.stillPlace(700, total));
        assertEquals(total - 1, OverwatchPanel.stillPlace(total, total), "the end has no picture of its own: just before it");
    }

    private static Color shown(OverwatchPanel p) {
        BufferedImage img = p.playerPicture();
        return img == null ? null : new Color(img.getRGB(img.getWidth() / 2, img.getHeight() / 2));
    }

    /** JPEG's colours are near, not exact. */
    private static boolean near(Color c, Color want) {
        return c != null && Math.abs(c.getRed() - want.getRed()) < 40 && Math.abs(c.getGreen() - want.getGreen()) < 40
                && Math.abs(c.getBlue() - want.getBlue()) < 40;
    }

    private static void await(BooleanSupplier done, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 4000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("never came: " + what);
            Thread.sleep(20);
        }
    }
}
