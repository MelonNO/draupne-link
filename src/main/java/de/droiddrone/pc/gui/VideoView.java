package de.droiddrone.pc.gui;

import com.google.zxing.common.BitMatrix;
import de.droiddrone.pc.video.LatencyMeter;
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
 * The picture can be turned upside down for a camera mounted that way; the OSD is drawn on top, the right way up.
 * Turning it happens in the same copy (measured: no slower than a plain one).
 */
class VideoView extends JComponent implements VideoPipeline.FrameSink {
    private final Object lock = new Object();
    private BufferedImage front;
    private BufferedImage back;
    private volatile int videoWidth = 16;
    private volatile int videoHeight = 9;
    private volatile boolean mirror;
    private volatile boolean upsideDown;
    private volatile int[] target = {0, 0};
    private Supplier<VideoPipeline.Stats> stats = () -> null;
    private String idleText = "Not connected";
    private final OsdRenderer osd = new OsdRenderer();
    private volatile boolean osdEnabled = true;
    private volatile OsdLayout osdLayout = OsdLayout.defaults();
    /** When the newest picture was handed over, and the smoothed wait until Swing painted it (shown in the stats). */
    private volatile long presentedNs;
    private volatile double displayMs;
    private volatile LatencyMeter meter;
    private Supplier<OsdRenderer.Data> osdData = () -> null;
    /** Animates the skeleton while there is no picture; stops by itself once video comes (or the view is hidden). */
    private final javax.swing.Timer skeletonTimer = new javax.swing.Timer(40, null);
    /** The no-signal static: drawn small and blown up, so it is cheap. */
    private final BufferedImage staticImage = new BufferedImage(192, 108, BufferedImage.TYPE_INT_RGB);
    private long noiseSeed = 0x9E3779B97F4A7C15L;

    VideoView() {
        setDoubleBuffered(false);
        setOpaque(true);
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(640, 360));
        skeletonTimer.addActionListener(e -> {
            boolean noPicture;
            synchronized (lock) {
                noPicture = front == null;
            }
            if (noPicture && isShowing() && meter == null) repaint();
            else skeletonTimer.stop();
        });
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
        presentedNs = System.nanoTime();
        LatencyMeter m = meter;
        if (m != null) {
            BufferedImage shown;
            synchronized (lock) {
                shown = front;
            }
            if (shown != null) {
                m.offer(((DataBufferInt) shown.getRaster().getDataBuffer()).getData(), shown.getWidth(), shown.getHeight(),
                        presentedNs + (long) (displayMs * 1e6));
            }
        }
        repaint();
    }

    // ------------------------------------------------------------------ painting (Swing thread)

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics;
        int w = getWidth();
        int h = getHeight();
        LatencyMeter m = meter;
        if (m != null) {
            paintLatencyTest(g, w, h, m);
            notePainted();
            return;
        }
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
                drawPicture(g, img, x, y, img.getWidth(), img.getHeight());
            }
        }
        OsdRenderer.Data data = osdEnabled ? osdData.get() : null;
        if (img == null) {
            paintSkeleton(g, w, h, data != null && data.connected());
            if (!skeletonTimer.isRunning()) skeletonTimer.start();
        }
        if (data != null && data.connected()) {
            // the HUD sits on the picture (letterboxed area), or the 16:9 area while there is no picture yet
            int[] t = target;
            int ow = img != null ? img.getWidth() : t[0] > 0 ? t[0] : w;
            int oh = img != null ? img.getHeight() : t[1] > 0 ? t[1] : h;
            osd.paint(g, (w - ow) / 2, (h - oh) / 2, ow, oh, data, osdLayout);
        } else {
            paintStats(g);
        }
        notePainted();
    }

    /** The camera picture into this rectangle: as it comes, mirrored (front camera) and/or turned 180 degrees. */
    private void drawPicture(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        boolean flipX = mirror != upsideDown; // turning 180 = flipping both ways; a mirrored picture is already flipped
        boolean flipY = upsideDown;
        if (!flipX && !flipY && w == img.getWidth() && h == img.getHeight()) {
            g.drawImage(img, x, y, null);
            return;
        }
        g.drawImage(img, flipX ? x + w : x, flipY ? y + h : y, flipX ? x : x + w, flipY ? y : y + h,
                0, 0, img.getWidth(), img.getHeight(), null);
    }

    /** For a camera mounted upside down. Only the picture turns; the OSD stays the right way up. */
    void setUpsideDown(boolean upsideDown) {
        this.upsideDown = upsideDown;
        repaint();
    }

    boolean isUpsideDown() {
        return upsideDown;
    }

    /**
     * No picture yet: what an analog FPV goggle shows without signal - dim, grainy static, with the OSD's crosshair and
     * an artificial horizon that rocks gently, and the status under it. With the OSD live on top (connected), the OSD
     * draws the crosshair and the real horizon, and the status moves up, clear of its warnings. Only runs while there
     * is no picture.
     */
    private void paintSkeleton(Graphics2D g, int w, int h, boolean osdLive) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        int[] t = target;
        int vw = t[0] > 0 ? t[0] : w, vh = t[1] > 0 ? t[1] : h;
        int x0 = (w - vw) / 2, y0 = (h - vh) / 2;
        long now = System.currentTimeMillis();
        // static: a small noise picture, blown up without smoothing so the grain stays coarse
        int[] px = ((DataBufferInt) staticImage.getRaster().getDataBuffer()).getData();
        int sw = staticImage.getWidth();
        for (int i = 0; i < px.length; i++) {
            noiseSeed ^= noiseSeed << 13;
            noiseSeed ^= noiseSeed >>> 7;
            noiseSeed ^= noiseSeed << 17;
            int row = i / sw;
            int level = (int) ((noiseSeed >>> 40) & 31) + ((row + (int) (now / 30)) % 9 == 0 ? 6 : 0);
            px[i] = level << 16 | level << 8 | level;
        }
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(staticImage, x0, y0, vw, vh, null);
        Theme.smooth(g);
        Graphics2D v = (Graphics2D) g.create(x0, y0, vw, vh);
        try {
            float u = vh / 720f;
            int cx = vw / 2, cy = vh / 2;
            if (!osdLive) skeletonHorizon(v, cx, cy, u, now);
            // status
            String dots = ".".repeat((int) (now / 400 % 4));
            int base = osdLive ? cy - Math.round(150 * u) : cy + Math.round(110 * u);
            v.setFont(Theme.headingFont(Math.max(14f, 20f * u)));
            v.setColor(Theme.TEXT);
            centered(v, "NO VIDEO FEED" + dots, vw, base);
            v.setFont(Theme.font(Font.PLAIN, Math.max(12f, 14f * u)));
            v.setColor(Theme.MUTED);
            centered(v, idleText, vw, base + Math.round(24 * u));
        } finally {
            v.dispose();
        }
    }

    /** The rocking horizon and the crosshair, while there is no OSD to draw the real ones. */
    private static void skeletonHorizon(Graphics2D v, int cx, int cy, float u, long now) {
        // artificial horizon, rocking a few degrees as if the quad were hovering
        double roll = Math.toRadians(6 * Math.sin(now / 1300.0));
        float pitch = (float) (14 * u * Math.sin(now / 2100.0));
        Graphics2D hz = (Graphics2D) v.create();
        hz.translate(cx, cy + pitch);
        hz.rotate(roll);
        int dash = Math.round(18 * u), gap = Math.round(14 * u), reach = Math.round(230 * u);
        hz.setStroke(new java.awt.BasicStroke(Math.max(1.5f, 2.5f * u)));
        for (int x = Math.round(70 * u); x < reach; x += dash + gap) {
            outlined(hz, x, 0, Math.min(x + dash, reach), 0);
            outlined(hz, -x, 0, -Math.min(x + dash, reach), 0);
        }
        hz.dispose();
        OsdRenderer.crosshair(v, cx, cy, u);
    }

    /** An OSD line: light, with a dark edge so it reads on any picture. */
    private static void outlined(Graphics2D g, int x1, int y1, int x2, int y2) {
        java.awt.Stroke s = g.getStroke();
        float wide = ((java.awt.BasicStroke) s).getLineWidth();
        g.setStroke(new java.awt.BasicStroke(wide + 2, java.awt.BasicStroke.CAP_SQUARE, java.awt.BasicStroke.JOIN_MITER));
        g.setColor(Theme.alpha(Color.BLACK, 200));
        g.drawLine(x1, y1, x2, y2);
        g.setStroke(s);
        g.setColor(Theme.alpha(Theme.TEXT, 220));
        g.drawLine(x1, y1, x2, y2);
    }

    private static void centered(java.awt.Graphics2D g, String text, int width, int baseline) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (width - fm.stringWidth(text)) / 2, baseline);
    }

    /** Picture and OSD drawn: the rest is Windows putting it on the screen. */
    private void notePainted() {
        long presented = presentedNs;
        if (presented != 0) {
            presentedNs = 0;
            double ms = (System.nanoTime() - presented) / 1e6;
            displayMs = displayMs == 0 ? ms : displayMs * 0.9 + ms * 0.1;
        }
    }

    /** Starts (meter) or stops (null) the latency test screen. */
    void setLatencyMeter(LatencyMeter meter) {
        this.meter = meter;
        repaint();
    }

    /**
     * Latency test: a big QR code with the paint time on the left, the readings next to it, and the live video as a
     * small thumbnail for aiming the camera (small, so its own copy of the code is too small to be read).
     */
    private void paintLatencyTest(Graphics2D g, int w, int h, LatencyMeter m) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        synchronized (lock) {
            if (front != null) {
                int tw = Math.max(80, w / 6);
                int th = tw * front.getHeight() / front.getWidth();
                drawPicture(g, front, w - tw - 12, h - th - 12, tw, th);
            }
        }
        BitMatrix code = m.codeNow(); // as late as possible: the time in it is "now"
        int modules = code.getWidth() + 8; // 4-module white border
        int scale = Math.max(1, Math.min(w * 11 / 20, h - 32) / modules);
        int size = modules * scale;
        int x0 = 16;
        int y0 = (h - size) / 2;
        g.setColor(Color.WHITE);
        g.fillRect(x0, y0, size, size);
        g.setColor(Color.BLACK);
        for (int y = 0; y < code.getHeight(); y++) {
            for (int x = 0; x < code.getWidth(); x++) {
                if (code.get(x, y)) g.fillRect(x0 + (4 + x) * scale, y0 + (4 + y) * scale, scale, scale);
            }
        }
        Theme.smooth(g);
        int tx = x0 + size + 28;
        int ty = Math.max(40, y0 + 30);
        g.setFont(Theme.headingFont(20f));
        g.setColor(Theme.ACCENT);
        g.drawString("// LATENCY TEST", tx, ty);
        g.setFont(Theme.font(Font.PLAIN, 14f));
        g.setColor(Theme.TEXT);
        ty += 34;
        for (String line : new String[]{"Point the drone's camera at this code,", "close enough that it fills most of the picture.",
                "Hold still for a few seconds.", "", "F3 or Esc: stop    F11: full screen"}) {
            g.drawString(line, tx, ty);
            ty += 21;
        }
        LatencyMeter.Result r = m.result();
        ty += 24;
        g.setFont(Theme.headingFont(13f));
        g.setColor(Theme.MUTED);
        g.drawString("CAMERA TO SCREEN", tx, ty);
        ty += 44;
        g.setFont(Theme.monoFont(Font.BOLD, 40f));
        g.setColor(r.count() == 0 ? Theme.MUTED : Theme.OK);
        g.drawString(r.count() == 0 ? "-- ms" : String.format(Locale.ROOT, "%.0f ms", r.medianMs()), tx, ty);
        ty += 26;
        g.setFont(Theme.font(Font.PLAIN, 13f));
        g.setColor(Theme.MUTED);
        g.drawString(r.count() == 0 ? "No code seen in the video yet"
                : String.format(Locale.ROOT, "median of %d readings, min %.0f, max %.0f", r.count(), r.minMs(), r.maxMs()), tx, ty);
    }

    /** O key / settings: show or hide the HUD. */
    void setOsdEnabled(boolean enabled) {
        osdEnabled = enabled;
        repaint();
    }

    boolean isOsdEnabled() {
        return osdEnabled;
    }

    /** Which OSD elements show and where (SETTINGS > OSD). */
    void setOsdLayout(OsdLayout layout) {
        osdLayout = layout == null ? OsdLayout.defaults() : layout;
        repaint();
    }

    OsdLayout getOsdLayout() {
        return osdLayout;
    }

    void setOsdSource(Supplier<OsdRenderer.Data> source) {
        osdData = source == null ? () -> null : source;
    }

    private void paintStats(Graphics2D g) {
        VideoPipeline.Stats s = stats.get();
        if (s == null || s.width() == 0) return;
        String line = String.format(Locale.ROOT, "%s %dx%d   %d fps (shown %d)   %.1f Mbit/s   decode %.1f ms   draw %.1f ms   to screen %.1f ms   damaged %d  lost %d",
                s.hevc() ? "H.265" : "H.264", s.width(), s.height(), s.receivedFps(), s.shownFps(), s.megabitsPerSecond(),
                s.decodeMs(), s.convertMs(), displayMs, s.damaged(), s.lost());
        if (s.error() != null) line = "Decoder: " + s.error();
        Theme.smooth(g);
        g.setFont(Theme.font(Font.BOLD, 11.5f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(8, 8, fm.stringWidth(line) + 16, fm.getHeight() + 8);
        g.setColor(s.error() != null ? Theme.BAD : Theme.TEXT);
        g.drawString(line, 16, 12 + fm.getAscent());
    }

}
