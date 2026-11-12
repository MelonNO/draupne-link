package de.droiddrone.pc.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Paints what a drone's STANAG 4609 stream shows when it is not the video (1.0.65). The page, as the pilot laid it out:
 * "the logo, then under it in big text the mode name, then an explanation of the mode" on black - with the drone's
 * name small in the corner, so one stream is told from the next in a player. And an overwatch alert's picture or clip
 * under a bar that says which it is. Pure painting, on the caller's thread.
 */
final class StanagPagePainter {
    private StanagPagePainter() {
    }

    /** The page's logo words: the second in the mark's red. */
    static final String LOGO_FIRST = "DRAUPNE ", LOGO_SECOND = "FPV";

    /** The bar over an alert's picture or a clip, pixels on a 720-high picture. */
    static final int BAR_H = 48;

    private static Color colour(StanagShow.Tone tone) {
        return tone == StanagShow.Tone.ALARM ? Theme.ACCENT : tone == StanagShow.Tone.WARN ? Theme.WARN : Theme.TEXT;
    }

    /** The whole page onto img. */
    static void page(BufferedImage img, String drone, boolean virtual, StanagShow.Page page) {
        int w = img.getWidth(), h = img.getHeight();
        float u = h / 720f; // the layout's unit: a 720-high picture
        Graphics2D g = Theme.smooth(img.createGraphics());
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            name(g, drone, virtual, u);

            // the logo: the mark and the words beside it, as wide as the picture takes (the pilot: "increase the logo
            // size ... and change the word Link for FPV" - a stream's viewer sees the drones, not the PC app's name)
            float scale = 1.3f;
            Font word;
            FontMetrics wm;
            float mark, gap, total;
            while (true) {
                mark = 170 * u * scale;
                gap = 34 * u * scale;
                word = Theme.headingFont(104 * u * scale);
                wm = g.getFontMetrics(word);
                total = mark + gap + wm.stringWidth(LOGO_FIRST + LOGO_SECOND);
                if (total <= w - 90 * u || scale <= 0.6f) break;
                scale -= 0.02f;
            }
            float x = (w - total) / 2f, top = 228 * u - mark / 2f; // its middle where it was: the mode's name keeps its room
            Logo.paint(g, x, top, mark, 1f);
            g.setFont(word);
            float base = top + mark / 2f + (wm.getAscent() - wm.getDescent()) / 2f;
            g.setColor(Theme.TEXT);
            g.drawString(LOGO_FIRST, x + mark + gap, base);
            g.setColor(Logo.RED);
            g.drawString(LOGO_SECOND, x + mark + gap + wm.stringWidth(LOGO_FIRST), base);

            // the mode's name, big: narrower letters when it would not fit
            float size = 100 * u;
            Font title = Theme.headingFont(size);
            String t = page.title() == null ? "" : page.title();
            while (size > 20 * u && g.getFontMetrics(title).stringWidth(t) > w - 120 * u) {
                size -= 4 * u;
                title = Theme.headingFont(size);
            }
            g.setFont(title);
            g.setColor(colour(page.tone()));
            FontMetrics tm = g.getFontMetrics();
            float titleBase = 480 * u;
            g.drawString(t, (w - tm.stringWidth(t)) / 2f, titleBase);

            // what the mode means
            g.setFont(Theme.font(Font.PLAIN, 34 * u));
            g.setColor(Theme.MUTED);
            FontMetrics em = g.getFontMetrics();
            float y = titleBase + 78 * u;
            for (String line : wrap(page.text(), em, Math.round(w - 240 * u))) {
                g.drawString(line, (w - em.stringWidth(line)) / 2f, y);
                y += em.getHeight() + 4 * u;
            }
            stats(g, page.stats(), w, 664 * u, u);
        } finally {
            g.dispose();
        }
    }

    /** The drone's figures in one line across the bottom: each label muted, its value beside it (yellow when low). */
    private static void stats(Graphics2D g, List<StanagShow.Stat> stats, int w, float base, float u) {
        if (stats == null || stats.isEmpty()) return;
        float size = 28 * u;
        while (true) {
            Font label = Theme.headingFont(size * 0.8f), value = Theme.headingFont(size);
            FontMetrics lm = g.getFontMetrics(label), vm = g.getFontMetrics(value);
            float gap = size * 1.5f, inner = size * 0.4f, total = -gap;
            for (StanagShow.Stat s : stats) total += gap + lm.stringWidth(s.label()) + inner + vm.stringWidth(s.value());
            if (total > w - 80 * u && size > 12 * u) { // many figures: smaller, never out of the picture
                size -= 2 * u;
                continue;
            }
            float x = (w - total) / 2f;
            for (StanagShow.Stat s : stats) {
                g.setFont(label);
                g.setColor(Theme.MUTED);
                g.drawString(s.label(), x, base);
                x += lm.stringWidth(s.label()) + inner;
                g.setFont(value);
                g.setColor(s.low() ? Theme.WARN : Theme.TEXT);
                g.drawString(s.value(), x, base);
                x += vm.stringWidth(s.value()) + gap;
            }
            return;
        }
    }

    private static void name(Graphics2D g, String drone, boolean virtual, float u) {
        g.setFont(Theme.headingFont(26 * u));
        FontMetrics m = g.getFontMetrics();
        float x = 36 * u, y = 30 * u + m.getAscent();
        g.setColor(Theme.TEXT);
        String n = drone == null ? "" : drone;
        g.drawString(n, x, y);
        if (virtual) {
            g.setColor(Theme.WARN);
            g.drawString("VIRTUAL", x + m.stringWidth(n + "  "), y);
        }
    }

    /** Words onto lines no wider than max. */
    static List<String> wrap(String text, FontMetrics m, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String tried = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && m.stringWidth(tried) > max) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** Where a picture of this shape goes on a canvas under the bar: {x, y, w, h}. */
    static int[] underBar(int pictureW, int pictureH, int w, int h) {
        int bar = barHeight(h);
        int[] at = OsdFrame.fit(pictureW, pictureH, w, h - bar);
        return new int[]{at[0], at[1] + bar, at[2], at[3]};
    }

    static int barHeight(int h) {
        return Math.round(BAR_H * h / 720f) & ~1;
    }

    /**
     * An alert's picture or a clip's onto img: black, the bar on top, the picture under it (its own shape, turned as
     * the PC shows this phone's pictures), the detector's boxes over it.
     * @param picture the picture, any size (scaled here), or null for none yet
     * @param boxes {x, y, w, h, ...} as fractions of the picture, or null
     */
    static void picture(BufferedImage img, BufferedImage picture, float[][] boxes, boolean upsideDown, String bar, StanagShow.Tone tone) {
        int w = img.getWidth(), h = img.getHeight();
        Graphics2D g = Theme.smooth(img.createGraphics());
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            if (picture != null) {
                int[] at = underBar(picture.getWidth(), picture.getHeight(), w, h);
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                boolean flip = upsideDown; // a phone mounted upside down: turned half round, as everywhere on this PC
                CameraTurn.draw(g, picture, at[0], at[1], at[2], at[3], flip, flip);
                boxes(g, boxes, at, flip);
            }
            bar(g, bar, tone, w, h);
        } finally {
            g.dispose();
        }
    }

    /** The bar alone, over a picture already on img (a clip's frame put there by its decoder). */
    static void bar(BufferedImage img, String bar, StanagShow.Tone tone) {
        Graphics2D g = Theme.smooth(img.createGraphics());
        try {
            bar(g, bar, tone, img.getWidth(), img.getHeight());
        } finally {
            g.dispose();
        }
    }

    private static void bar(Graphics2D g, String text, StanagShow.Tone tone, int w, int h) {
        int bar = barHeight(h);
        float u = h / 720f;
        g.setColor(tone == StanagShow.Tone.ALARM ? Theme.ACCENT : tone == StanagShow.Tone.WARN ? Theme.WARN : Theme.PANEL_2);
        g.fillRect(0, 0, w, bar);
        g.setColor(tone == StanagShow.Tone.WARN ? Color.BLACK : Color.WHITE);
        float size = 26 * u;
        Font f = Theme.headingFont(size);
        String t = text == null ? "" : text;
        while (size > 12 * u && g.getFontMetrics(f).stringWidth(t) > w - 40 * u) {
            size -= 2 * u;
            f = Theme.headingFont(size);
        }
        g.setFont(f);
        FontMetrics m = g.getFontMetrics();
        g.drawString(t, 20 * u, (bar + m.getAscent() - m.getDescent()) / 2f);
    }

    private static void boxes(Graphics2D g, float[][] boxes, int[] at, boolean flip) {
        if (boxes == null) return;
        g.setColor(Theme.WARN);
        g.setStroke(new BasicStroke(2f));
        for (float[] b : boxes) {
            if (b == null || b.length < 4) continue;
            float[] r = CameraTurn.box(new float[]{b[0], b[1], b[2], b[3]}, flip, flip);
            g.drawRect(at[0] + Math.round(r[0] * at[2]), at[1] + Math.round(r[1] * at[3]), Math.round(r[2] * at[2]), Math.round(r[3] * at[3]));
        }
    }
}
