package de.droiddrone.pc.gui;

import de.droiddrone.pc.video.VideoDecoder;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * One picture as the screen shows it, for something that encodes it again: a decoder's last picture, turned the way
 * the screen turns it, with the OSD painted over. The recording with the OSD (1.0.42, {@link OsdRecorder}) and the
 * STANAG 4609 streams (1.0.65, {@link StanagFeed}) both draw through this. One thread: the caller's.
 */
final class OsdFrame {
    private final OsdRenderer osd = new OsdRenderer();
    private BufferedImage picture, canvas;

    /** Its own renderer (it keeps per-paint state): set up once by the owner (an observation tile's: setObserver). */
    OsdRenderer renderer() {
        return osd;
    }

    /** A canvas of this size from now on. */
    void size(int w, int h) {
        if (canvas != null && canvas.getWidth() == w && canvas.getHeight() == h) return;
        canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        picture = null;
    }

    int width() {
        return canvas == null ? 0 : canvas.getWidth();
    }

    int height() {
        return canvas == null ? 0 : canvas.getHeight();
    }

    BufferedImage canvas() {
        return canvas;
    }

    /** The canvas' pixels, 0xRRGGBB, row by row: what an encoder takes. */
    int[] pixels() {
        return ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
    }

    void clear() {
        canvas = null;
        picture = null;
    }

    /** The picture's place on a w x h canvas: as big as fits, its own shape, even sides, centred. {x, y, w, h}. */
    static int[] fit(int pictureW, int pictureH, int w, int h) {
        if (pictureW <= 0 || pictureH <= 0 || w <= 0 || h <= 0) return new int[]{0, 0, w, h};
        double s = Math.min(w / (double) pictureW, h / (double) pictureH);
        int fw = Math.min(w, (int) Math.round(pictureW * s) & ~1), fh = Math.min(h, (int) Math.round(pictureH * s) & ~1);
        // a picture a pixel or two off the canvas' shape (1280x718) fills it: no hairline bars
        if (w - fw <= 2) fw = w;
        if (h - fh <= 2) fh = h;
        return new int[]{(w - fw) / 2, (h - fh) / 2, Math.max(2, fw), Math.max(2, fh)};
    }

    /**
     * The decoder's last picture onto the canvas - scaled to fit (black beside a picture of another shape), turned -
     * and the OSD over the picture when there are figures to show.
     * @param data the OSD's figures, or null for none
     */
    void draw(VideoDecoder decoder, boolean flipX, boolean flipY, OsdRenderer.Data data, OsdLayout layout, double displayMs) {
        int w = canvas.getWidth(), h = canvas.getHeight();
        int[] at = fit(decoder.width(), decoder.height(), w, h);
        boolean whole = at[2] == w && at[3] == h;
        Graphics2D g = canvas.createGraphics();
        try {
            if (whole && !flipX && !flipY) {
                decoder.convert(pixels(), w, h);
            } else {
                if (picture == null || picture.getWidth() != at[2] || picture.getHeight() != at[3]) {
                    picture = new BufferedImage(at[2], at[3], BufferedImage.TYPE_INT_RGB);
                }
                decoder.convert(((DataBufferInt) picture.getRaster().getDataBuffer()).getData(), at[2], at[3]);
                if (!whole) {
                    g.setColor(Color.BLACK);
                    g.fillRect(0, 0, w, h);
                }
                g.drawImage(picture, at[0] + (flipX ? at[2] : 0), at[1] + (flipY ? at[3] : 0), at[0] + (flipX ? 0 : at[2]),
                        at[1] + (flipY ? 0 : at[3]), 0, 0, at[2], at[3], null);
            }
            if (data != null && data.connected()) {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                osd.setDisplayMs(displayMs);
                osd.setPictureFlip(flipX, flipY); // the motion boxes (1.0.46) as the screen draws them
                osd.paint(g, at[0], at[1], at[2], at[3], data, layout);
            }
        } finally {
            g.dispose();
        }
    }
}
