package de.droiddrone.pc.gui;

import de.droiddrone.pc.video.VideoPipeline;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Shows the decoded camera picture. The decoder scales straight to this component's letterboxed size and writes into
 * a back buffer; presenting swaps buffers and repaints, so painting is a 1:1 copy with no scaling.
 * Not double-buffered by Swing (the picture covers everything it paints), which saves one more copy per frame.
 */
class VideoView extends JComponent implements VideoPipeline.FrameSink {
    private final Object lock = new Object();
    private BufferedImage front;
    private BufferedImage back;
    private volatile int videoWidth = 16;
    private volatile int videoHeight = 9;
    private volatile boolean mirror;
    private volatile int[] target = {0, 0};
    private Supplier<VideoPipeline.Stats> stats = () -> null;
    private String idleText = "Not connected";
    private final OsdRenderer osd = new OsdRenderer();
    private volatile boolean osdEnabled = true;
    private Supplier<OsdRenderer.Data> osdData = () -> null;

    VideoView() {
        setDoubleBuffered(false);
        setOpaque(true);
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(640, 360));
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                updateTarget();
            }
        });
    }

    void setStatsSource(Supplier<VideoPipeline.Stats> stats) {
        this.stats = stats == null ? () -> null : stats;
    }

    void setIdleText(String text) {
        if (!text.equals(idleText)) {
            idleText = text;
            repaint();
        }
    }

    /** Back to the idle screen (disconnected). */
    void clear() {
        synchronized (lock) {
            front = null;
            back = null;
        }
        repaint();
    }

    private void updateTarget() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            target = new int[]{0, 0};
            return;
        }
        double scale = Math.min(w / (double) videoWidth, h / (double) videoHeight);
        target = new int[]{Math.max(2, (int) (videoWidth * scale) & ~1), Math.max(2, (int) (videoHeight * scale) & ~1)};
    }

    // ------------------------------------------------------------------ FrameSink (decode thread)

    @Override
    public void videoStarted(int width, int height, boolean mirror) {
        if (width > 0 && height > 0) {
            videoWidth = width;
            videoHeight = height;
        }
        this.mirror = mirror;
        updateTarget();
    }

    @Override
    public int[] targetSize() {
        return target;
    }

    @Override
    public int[] beginFrame(int width, int height) {
        synchronized (lock) {
            if (back == null || back.getWidth() != width || back.getHeight() != height) {
                back = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            }
            return ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
        }
    }

    @Override
    public void presentFrame() {
        synchronized (lock) {
            BufferedImage t = front;
            front = back;
            back = t;
        }
        repaint();
    }

    // ------------------------------------------------------------------ painting (Swing thread)

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics;
        int w = getWidth();
        int h = getHeight();
        BufferedImage img;
        synchronized (lock) {
            img = front;
            if (img != null) {
                int x = (w - img.getWidth()) / 2;
                int y = (h - img.getHeight()) / 2;
                g.setColor(Color.BLACK);
                if (y > 0) {
                    g.fillRect(0, 0, w, y);
                    g.fillRect(0, y + img.getHeight(), w, h - y - img.getHeight());
                }
                if (x > 0) {
                    g.fillRect(0, 0, x, h);
                    g.fillRect(x + img.getWidth(), 0, w - x - img.getWidth(), h);
                }
                if (mirror) {
                    g.drawImage(img, x + img.getWidth(), y, x, y + img.getHeight(), 0, 0, img.getWidth(), img.getHeight(), null);
                } else {
                    g.drawImage(img, x, y, null);
                }
            }
        }
        if (img == null) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            Theme.smooth(g);
            g.setFont(Theme.headingFont(18f));
            g.setColor(Theme.LINE);
            drawCentered(g, "VIDEO", h / 2 - 6);
            g.setFont(Theme.font(Font.PLAIN, 13f));
            g.setColor(Theme.MUTED);
            drawCentered(g, idleText, h / 2 + 18);
        }
        OsdRenderer.Data data = osdEnabled ? osdData.get() : null;
        if (data != null && data.connected()) {
            // the HUD sits on the picture (letterboxed area), or the 16:9 area while there is no picture yet
            int[] t = target;
            int ow = img != null ? img.getWidth() : t[0] > 0 ? t[0] : w;
            int oh = img != null ? img.getHeight() : t[1] > 0 ? t[1] : h;
            osd.paint(g, (w - ow) / 2, (h - oh) / 2, ow, oh, data);
        } else {
            paintStats(g);
        }
    }

    /** O key / settings: show or hide the HUD. */
    void setOsdEnabled(boolean enabled) {
        osdEnabled = enabled;
        repaint();
    }

    boolean isOsdEnabled() {
        return osdEnabled;
    }

    void setOsdSource(Supplier<OsdRenderer.Data> source) {
        osdData = source == null ? () -> null : source;
    }

    private void paintStats(Graphics2D g) {
        VideoPipeline.Stats s = stats.get();
        if (s == null || s.width() == 0) return;
        String line = String.format(Locale.ROOT, "%s %dx%d   %d fps (shown %d)   %.1f Mbit/s   decode %.1f ms   draw %.1f ms   damaged %d  lost %d",
                s.hevc() ? "H.265" : "H.264", s.width(), s.height(), s.receivedFps(), s.shownFps(), s.megabitsPerSecond(),
                s.decodeMs(), s.convertMs(), s.damaged(), s.lost());
        if (s.error() != null) line = "Decoder: " + s.error();
        Theme.smooth(g);
        g.setFont(Theme.font(Font.BOLD, 11.5f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(8, 8, fm.stringWidth(line) + 16, fm.getHeight() + 8);
        g.setColor(s.error() != null ? Theme.BAD : Theme.TEXT);
        g.drawString(line, 16, 12 + fm.getAscent());
    }

    private void drawCentered(Graphics2D g, String text, int baseline) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, baseline);
    }
}
