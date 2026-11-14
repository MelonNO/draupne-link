package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Pins;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MARK and STILL as they look, painted off screen: the video's bar with its two new buttons, and a picture with a
 * pin's line and mark on it. Set MARK_SHOTS to a folder (build/shots) and the pictures are written there as PNG to be
 * looked at; without it only the sizes are checked.
 */
class MarkShotsTest {
    @BeforeAll
    static void theme() {
        Theme.install();
    }

    private static void save(BufferedImage img, String name) throws IOException {
        String dir = System.getenv("MARK_SHOTS");
        if (dir == null) return;
        Files.createDirectories(Path.of(dir));
        ImageIO.write(img, "png", Path.of(dir, name).toFile());
    }

    /** The bar at the picture's right edge, built as the window builds it: the buttons under each other, 4 apart. */
    private static JPanel bar(GlyphButton... buttons) {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBackground(Color.BLACK);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) bar.add(Box.createVerticalStrut(4));
            buttons[i].setAlignmentX(Component.CENTER_ALIGNMENT);
            bar.add(buttons[i]);
        }
        bar.add(Box.createVerticalGlue());
        return bar;
    }

    private static GlyphButton[] buttons(boolean markArmed) {
        GlyphButton mark = new GlyphButton(GlyphButton.Glyph.MARK, "Mark");
        mark.setSelected(markArmed);
        return new GlyphButton[]{new GlyphButton(GlyphButton.Glyph.POP_OUT, "Pop out video"), new GlyphButton(GlyphButton.Glyph.FULL_SCREEN, "Full screen"),
                new GlyphButton(GlyphButton.Glyph.BEEPER, "Beeper"), new GlyphButton(GlyphButton.Glyph.SOUND, "Mute"),
                new GlyphButton(GlyphButton.Glyph.AREAS, "Detection areas"), mark, new GlyphButton(GlyphButton.Glyph.STILL, "Still")};
    }

    private static BufferedImage paint(JPanel bar, int height, double scale) {
        bar.setSize(bar.getPreferredSize().width, height);
        bar.doLayout();
        BufferedImage img = new BufferedImage((int) Math.ceil(bar.getWidth() * scale), (int) Math.ceil(height * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(scale, scale);
        bar.paint(g);
        g.dispose();
        return img;
    }

    /**
     * The room the picture's bar has in the window, worked out (the window itself is not made in a test): the
     * screen's height less a title bar, the window's margins, the header and the controller card under the video -
     * with the sizes those parts ask for, made here as the window makes them; the card's top row on two lines, as it
     * is on a narrow window (on one line the picture has some 50 more).
     */
    private static int videoHeight(int screenHeight, boolean controllerMinimized) {
        int title = 31, margins = 2 * 14, gap = 14;
        int header = Math.max(Theme.primaryButton("Menu").getPreferredSize().height, Theme.label("DRAUPNE", Theme.headingFont(22f), Theme.TEXT).getPreferredSize().height);
        JPanel states = new JPanel(new GridLayout(3, 1, 0, 0));
        states.add(new CopyableText(" ", Theme.font(Font.BOLD, 11f), Theme.MUTED, Theme.PANEL));
        states.add(new CopyableText(" ", Theme.headingFont(11f), Theme.MUTED, Theme.PANEL));
        states.add(new CopyableText(" ", Theme.headingFont(11f), Theme.MUTED, Theme.PANEL));
        int line = Math.max(32, Theme.secondaryButton("Calibrate").getPreferredSize().height);
        int topRow = line + 8 + states.getPreferredSize().height; // WrapRowLayout, wrapped
        java.awt.Insets card = Theme.card(null).getInsets();
        int controller = card.top + card.bottom + topRow + (controllerMinimized ? 0 : 12 + 262);
        return screenHeight - title - margins - header - gap - gap - controller;
    }

    @Test
    void theBarWithSevenButtonsAndTheRoomItHas() throws IOException {
        JPanel bar = bar(buttons(true));
        int needs = bar.getPreferredSize().height;
        assertEquals(7 * 30 + 6 * 4 + 8, needs, "seven buttons of 30, 4 apart, 4 round them");
        assertEquals(38, bar.getPreferredSize().width, "no wider than before");
        int five = bar(java.util.Arrays.copyOf(buttons(false), 5)).getPreferredSize().height;
        assertEquals(174, five, "the five before MARK and STILL");
        save(paint(bar, needs, 1), "video-bar-100.png");
        save(paint(bar, needs, 1.25), "video-bar-125.png");
        save(paint(bar, needs, 1.5), "video-bar-150.png");
        save(paint(bar, needs, 4), "video-bar-400.png");
        BufferedImage armed = paint(bar, needs, 1), idle = paint(bar(buttons(false)), needs, 1);
        int differ = 0;
        for (int y = 0; y < needs; y++) {
            for (int x = 0; x < 38; x++) {
                if (armed.getRGB(x, y) != idle.getRGB(x, y)) {
                    differ++;
                    assertTrue(y >= 4 + 5 * 34 && y < 4 + 5 * 34 + 30, "only the MARK button changes when armed: row " + y);
                }
            }
        }
        assertTrue(differ > 100, "armed shows on the button: " + differ);

        // the small screens (logical sizes: 1366 x 768 at 125 % and 1920 x 1080 at 150 %, the taskbar off)
        int small = videoHeight(584, true), smallOpen = videoHeight(584, false), mid = videoHeight(672, true), midOpen = videoHeight(672, false);
        System.out.println("video bar: needs " + needs + " (was " + five + "); room at 1093x584: " + small + " controller minimized, " + smallOpen
                + " expanded; at 1280x672: " + mid + " minimized, " + midOpen + " expanded");
        assertTrue(needs <= small, "fits beside the picture on the smallest screen with the controller card minimized: " + needs + " in " + small);
        assertTrue(needs <= mid);
        // with the controller card expanded those screens leave the picture about the height the five buttons before
        // took, or less: the bar's bottom is cut there - MARK and STILL now, AREAS already before on the smaller one.
        // Not squeezed in: a second column when the bar is taller than the picture is proposed
        save(paint(bar, Math.max(60, midOpen), 1), "video-bar-cut-1280x672-controller-expanded.png");
    }

    /** A sky over ground, lighter towards the horizon: something for the OSD and the line to stand on. */
    private static void picture(int[] px, int w, int h) {
        for (int y = 0; y < h; y++) {
            float t = y / (float) h;
            int c = t < 0.45f ? new Color(120 + (int) (200 * t), 150 + (int) (180 * t), 190 + (int) (120 * t)).getRGB()
                    : new Color(70 + (int) (60 * (1 - t)), 95 + (int) (50 * (1 - t)), 60).getRGB();
            for (int x = 0; x < w; x++) px[y * w + x] = c & 0xffffff;
        }
    }

    /** A still's two files of that picture: into the shots' folder when there is one, else the test's own. */
    @Test
    void aStillWithAndWithoutTheOsd(@TempDir Path temp) throws Exception {
        String shots = System.getenv("MARK_SHOTS");
        Path dir = shots == null ? temp : Files.createDirectories(Path.of(shots));
        OsdPreview osd = new OsdPreview();
        CountDownLatch said = new CountDownLatch(1);
        String[] line = {null};
        StillSaver saver = new StillSaver(new StillSaver.Source() {
            public boolean ask(java.util.function.Consumer<StillSaver.Shot> to) {
                to.accept(new StillSaver.Shot() {
                    public int width() { return 1280; }
                    public int height() { return 720; }
                    public void pixels(int[] dst) { picture(dst, 1280, 720); }
                    public void close() {}
                });
                return true;
            }

            public OsdRenderer.Data osdData() { return osd.data(); }
            public OsdLayout osdLayout() { return OsdLayout.defaults(); }
            public boolean flipX() { return false; }
            public boolean flipY() { return false; }
            public double displayMs() { return 5; }
        }, () -> dir, (text, bad, ms) -> {
            line[0] = text;
            said.countDown();
        }, l -> System.out.println("log: " + l), Runnable::run);
        saver.save();
        assertTrue(said.await(30, TimeUnit.SECONDS));
        assertEquals(StillSaver.SAVED, line[0]);
    }

    private static final class Host implements VideoMark.Host {
        final Pins pins = new Pins(null);
        boolean rough, position = true;

        public String off() { return null; }

        public PinDrop.Sight look(double u, double v) {
            if (!position) return new PinDrop.Sight(false, false, null, null, false, 0, 0, 0, Long.MAX_VALUE);
            CameraFootprint.Pose p = new CameraFootprint.Pose(61.149, 11.394, 320, 75, -4, 8, -25, 70, 43);
            return new PinDrop.Sight(true, true, p, CameraFootprint.ray(p, u, v, rough ? null : (lat, lon) -> 200, 200), false, 2, 14, 0, 300);
        }

        public Pins pins() { return pins; }
        public Coordinates.Format format() { return Coordinates.Format.MGRS; }
        public boolean onMap(Pins.Pin pin) { return true; }
        public void armed(boolean on) {}
        public void log(String line) { System.out.println("log: " + line); }
    }

    private static BufferedImage paint(VideoView view) {
        BufferedImage screen = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        view.paintComponent(g);
        g.dispose();
        return screen;
    }

    private static void click(VideoView view, int x, int y) {
        view.dispatchEvent(new MouseEvent(view, MouseEvent.MOUSE_PRESSED, 1000, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
    }

    @Test
    void aMarkedPictureWithItsLine() throws IOException {
        for (int[] size : new int[][]{{1280, 720}, {640, 360}}) {
            VideoView view = new VideoView();
            view.setSize(size[0], size[1]);
            view.videoStarted(1280, 720, false);
            picture(view.beginFrame(size[0], size[1]), size[0], size[1]);
            view.presentFrame();
            OsdPreview osd = new OsdPreview();
            view.setOsdSource(osd::data);
            AtomicLong clock = new AtomicLong(1000);
            Host host = new Host();
            VideoMark mark = new VideoMark(view, host, clock::get);
            view.setMarkLayer(mark);
            paint(view);
            String tag = size[0] + "x" + size[1];

            mark.arm();
            click(view, size[0] * 62 / 100, size[1] * 70 / 100);
            assertEquals(1, host.pins.list().size());
            assertTrue(mark.line().startsWith("PIN ALPHA "), mark.line());
            save(paint(view), "marked-" + tag + ".png");

            host.rough = true;
            mark.arm();
            click(view, size[0] * 30 / 100, size[1] * 60 / 100);
            assertTrue(mark.line().startsWith("PIN ~BRAVO "), mark.line());
            save(paint(view), "marked-rough-" + tag + ".png");

            host.position = false;
            mark.arm();
            click(view, size[0] / 2, size[1] / 2);
            assertEquals(List.of("Alpha", "Bravo"), host.pins.list().stream().map(Pins.Pin::name).toList());
            save(paint(view), "marked-refused-" + tag + ".png");

            mark.say(StillSaver.SAVED, false, StillSaver.LINE_MS);
            save(paint(view), "still-saved-" + tag + ".png");
            clock.addAndGet(StillSaver.LINE_MS);
            assertNull(mark.line());
        }
    }
}
