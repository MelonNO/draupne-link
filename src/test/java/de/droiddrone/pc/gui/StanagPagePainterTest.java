package de.droiddrone.pc.gui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.65, the pilot's page: "the logo then under it in big text the mode name, then an explanation of the mode", on
 * black. STANAG_SHOT=dir writes each page as a PNG there (looked at, never a screen capture).
 */
class StanagPagePainterTest {
    private static final int W = StanagFeed.W, H = StanagFeed.H;

    @BeforeAll
    static void look() {
        Theme.install();
    }

    private static BufferedImage image() {
        return new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    }

    /** The rows and columns that have anything but black on them: {top, bottom, left, right}, or null for a black picture. */
    private static int[] ink(BufferedImage img, int y0, int y1) {
        int top = -1, bottom = -1, left = Integer.MAX_VALUE, right = -1;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xffffff) == 0) continue;
                if (top < 0) top = y;
                bottom = y;
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        return top < 0 ? null : new int[]{top, bottom, left, right};
    }

    private static boolean has(BufferedImage img, int y0, int y1, Color c) {
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color p = new Color(img.getRGB(x, y));
                if (Math.abs(p.getRed() - c.getRed()) < 24 && Math.abs(p.getGreen() - c.getGreen()) < 24 && Math.abs(p.getBlue() - c.getBlue()) < 24) return true;
            }
        }
        return false;
    }

    private static void shot(BufferedImage img, String name) throws Exception {
        String dir = System.getenv("STANAG_SHOT");
        if (dir == null || dir.isBlank()) return;
        Path d = Files.createDirectories(Path.of(dir));
        ImageIO.write(img, "png", new File(d.toFile(), name + ".png"));
    }

    private static final StanagShow.Page[] PAGES = {
            new StanagShow.Page("CRASHED", "Marked lost. Last place in the metadata.", StanagShow.Tone.ALARM),
            new StanagShow.Page("LOST", "Link lost while flown.", StanagShow.Tone.ALARM),
            new StanagShow.Page("PILOTED", "Flown from PCBravo. Video on that PC.", StanagShow.Tone.PLAIN),
            new StanagShow.Page("OVERWATCH - ALERT", "Motion seen. See the map window.", StanagShow.Tone.ALARM),
            new StanagShow.Page("OVERWATCH - BLIND", "Camera stopped. The phone restarts it.", StanagShow.Tone.WARN),
            new StanagShow.Page("OVERWATCH", "Asleep, camera watching for motion.", StanagShow.Tone.PLAIN),
            new StanagShow.Page("DEEP SLEEP", "Asleep. Checks in every 5 min.", StanagShow.Tone.PLAIN),
            new StanagShow.Page("STANDBY", "Camera off. Ready for PILOT UAV.", StanagShow.Tone.PLAIN),
            new StanagShow.Page("IDLE", "Awake, no pilot. PILOT UAV flies it.", StanagShow.Tone.PLAIN),
            new StanagShow.Page("UNKNOWN", "Missed its check-in.", StanagShow.Tone.WARN),
            new StanagShow.Page("OFFLINE", "Not heard since this PC started.", StanagShow.Tone.PLAIN)};

    @Test
    void theLogoThenTheModeBigThenWhatItMeansAllInsideThePicture() throws Exception {
        for (StanagShow.Page p : PAGES) {
            BufferedImage img = image();
            StanagPagePainter.page(img, "MobileAlpha", false, p);
            shot(img, "page-" + p.title().replace(' ', '_'));
            // black in the corners and along the edges: nothing runs out of the picture
            int[] all = ink(img, 0, H);
            assertNotNull(all, p.title());
            assertTrue(all[0] >= 16 && all[1] <= H - 16 && all[2] >= 16 && all[3] <= W - 16, p.title() + " stays inside: " + java.util.Arrays.toString(all));
            int[] logo = ink(img, 90, 350), title = ink(img, 370, 510), text = ink(img, 520, 625);
            assertNotNull(logo, "the logo on top");
            assertNotNull(title, "the mode's name under it");
            assertNotNull(text, "the explanation under that");
            assertTrue(has(img, 120, 330, Logo.RED), "the logo's red");
            // the pilot: "increase the logo size" - it takes the picture's width, and stays clear of the mode's name
            assertTrue(logo[3] - logo[2] >= W * 0.85, "the logo across the picture: " + (logo[3] - logo[2]) + " px");
            assertTrue(logo[1] - logo[0] >= 140, "its mark " + (logo[1] - logo[0]) + " px high (it was 130)");
            assertTrue(title[0] - logo[1] >= 40, "room between the logo and the mode's name");
            assertEquals("DRAUPNE FPV", StanagPagePainter.LOGO_FIRST + StanagPagePainter.LOGO_SECOND, "the pilot: \"change the word Link for FPV\"");
            assertTrue(title[1] - title[0] >= 55, "the mode's name is the big text: " + (title[1] - title[0]) + " px high");
            assertTrue(text[1] - text[0] < title[1] - title[0], "the explanation is smaller");
            // centred: as much room left as right, within a letter
            assertEquals(W - title[3], title[2], 12, p.title() + " in the middle");
            Color tone = p.tone() == StanagShow.Tone.ALARM ? Theme.ACCENT : p.tone() == StanagShow.Tone.WARN ? Theme.WARN : Theme.TEXT;
            assertTrue(has(img, 370, 510, tone), p.title() + " in its colour");
        }
    }

    /** The pilot: "include some stats in the screen shown when no video is streamed, specifically battery level". */
    @Test
    void theFiguresGoInOneLineUnderTheTextAndALowBatteryIsYellow() throws Exception {
        java.util.List<StanagShow.Stat> stats = java.util.List.of(new StanagShow.Stat("PHONE", "87 %", false), new StanagShow.Stat("DRONE", "69 % AT 20:54", false),
                new StanagShow.Stat("HEARD", "21:03", false), new StanagShow.Stat("NEXT CHECK", "21:08", false));
        BufferedImage img = image();
        StanagPagePainter.page(img, "MobileAlpha", false, PAGES[6].with(stats));
        shot(img, "page-stats");
        int[] line = ink(img, 630, 700);
        assertNotNull(line, "the figures under the explanation");
        assertTrue(line[2] >= 30 && line[3] <= W - 30 && line[1] <= H - 16, "inside the picture: " + java.util.Arrays.toString(line));
        assertEquals(W - line[3], line[2], 14, "in the middle");
        assertFalse(has(img, 630, 700, Theme.WARN), "nothing low");
        BufferedImage low = image();
        StanagPagePainter.page(low, "MobileAlpha", false, PAGES[7].with(java.util.List.of(new StanagShow.Stat("PHONE", "12 %", true),
                new StanagShow.Stat("DRONE", "12 %", true))));
        shot(low, "page-stats-low");
        assertTrue(has(low, 630, 700, Theme.WARN), "a low battery in yellow");
        // without figures the page is as before: nothing down there
        BufferedImage none = image();
        StanagPagePainter.page(none, "MobileAlpha", false, PAGES[7]);
        assertNull(ink(none, 630, 700));
        // many long figures are made to fit
        java.util.List<StanagShow.Stat> many = new java.util.ArrayList<>(stats);
        many.add(new StanagShow.Stat("SOMETHING ELSE", "A LONG VALUE INDEED 123456", false));
        many.add(new StanagShow.Stat("AND MORE", "ANOTHER LONG VALUE 7890", false));
        BufferedImage wide = image();
        StanagPagePainter.page(wide, "MobileAlpha", false, PAGES[6].with(many));
        int[] fit = ink(wide, 630, 700);
        assertTrue(fit[2] >= 20 && fit[3] <= W - 20, "inside: " + fit[2] + ".." + fit[3]);
    }

    @Test
    void theDronesNameIsInTheCornerAndAVirtualOneIsMarked() throws Exception {
        BufferedImage img = image();
        StanagPagePainter.page(img, "MobileZulu", true, PAGES[8]);
        shot(img, "page-virtual");
        int[] corner = ink(img, 0, 75);
        assertNotNull(corner, "the name on top");
        assertTrue(corner[2] < 60 && corner[3] < W / 2, "at the left");
        assertTrue(has(img, 0, 75, Theme.WARN), "VIRTUAL in yellow");
        BufferedImage real = image();
        StanagPagePainter.page(real, "MobileZulu", false, PAGES[8]);
        assertFalse(has(real, 0, 75, Theme.WARN));
    }

    @Test
    void aLongModeNameIsMadeToFit() {
        BufferedImage img = image();
        StanagPagePainter.page(img, "MobileAlpha", false, new StanagShow.Page("OVERWATCH - ALERT - SOMETHING VERY LONG INDEED HERE", "Text.", StanagShow.Tone.PLAIN));
        int[] title = ink(img, 370, 510);
        assertNotNull(title);
        assertTrue(title[2] >= 40 && title[3] <= W - 40, "inside: " + title[2] + ".." + title[3]);
    }

    @Test
    void anAlertsPictureGoesUnderARedBarWithItsBoxesAndAClipUnderAnAmberOne() throws Exception {
        BufferedImage picture = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(new Color(0x204060));
        g.fillRect(0, 0, 640, 360);
        g.dispose();
        BufferedImage img = image();
        StanagPagePainter.picture(img, picture, new float[][]{{0.4f, 0.4f, 0.1f, 0.2f, 0}}, false,
                StanagShow.alertBar("MobileGolf", true, 0), StanagShow.Tone.ALARM);
        shot(img, "alert");
        int bar = StanagPagePainter.barHeight(H);
        assertEquals(Theme.ACCENT.getRGB() & 0xffffff, img.getRGB(W - 5, bar / 2) & 0xffffff, "the red bar across the top");
        assertEquals(0x204060, img.getRGB(W / 2, bar + 20) & 0xffffff, "the picture under it, not behind it");
        assertTrue(has(img, bar + 100, H - 50, Theme.WARN), "the detector's box");
        assertTrue(has(img, 0, bar, Color.WHITE), "the bar's text");

        StanagPagePainter.bar(img, StanagShow.clipBar("MobileGolf", 0), StanagShow.Tone.WARN);
        shot(img, "clip");
        assertEquals(Theme.WARN.getRGB() & 0xffffff, img.getRGB(W - 5, bar / 2) & 0xffffff, "a clip's bar is amber: it is a recording");
    }

    @Test
    void aPictureOfAnotherShapeKeepsItAndAnUpsideDownPhonesIsTurned() {
        // 4:3 under the bar: bars at the sides, never stretched
        int[] at = StanagPagePainter.underBar(640, 480, W, H);
        assertEquals(4.0 / 3, at[2] / (double) at[3], 0.01);
        assertTrue(at[1] >= StanagPagePainter.barHeight(H) && at[1] + at[3] <= H && at[0] > 0);
        BufferedImage picture = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, 640, 180); // green on top
        g.dispose();
        BufferedImage img = image();
        StanagPagePainter.picture(img, picture, null, true, "x", StanagShow.Tone.ALARM);
        int[] p = StanagPagePainter.underBar(640, 360, W, H);
        assertEquals(0x00ff00, img.getRGB(W / 2, p[1] + p[3] - 20) & 0xffffff, "turned: the top is at the bottom");
        assertEquals(0, img.getRGB(W / 2, p[1] + 20) & 0xffffff);
    }
}
