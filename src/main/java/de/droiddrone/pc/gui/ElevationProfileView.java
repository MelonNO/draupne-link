package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.MapGraphics;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.Locale;

/**
 * The terrain along a measured line (1.0.35): height against distance, filled under the curve, the lowest and
 * highest heights on the left, 0, the middle and the whole length along the bottom. Samples still being looked up
 * (NaN) leave a gap that fills in as the answers come.
 */
final class ElevationProfileView extends JComponent {
    private double[] dist = new double[0], height = new double[0];

    ElevationProfileView() {
        setOpaque(false);
        setPreferredSize(new Dimension(300, 132));
    }

    /** @param distances metres along the line; heights metres, NaN where not known (yet) */
    void set(double[] distances, double[] heights) {
        dist = distances == null ? new double[0] : distances;
        height = heights == null ? new double[0] : heights;
        repaint();
    }

    /** How many samples have their height. */
    int known() {
        int n = 0;
        for (double h : height) if (!Double.isNaN(h)) n++;
        return n;
    }

    int samples() {
        return height.length;
    }

    /** The lowest and highest known heights, or null without any. */
    double[] range() {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : height) {
            if (Double.isNaN(v)) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return lo == Double.MAX_VALUE ? null : new double[]{lo, hi};
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            int w = getWidth(), h = getHeight();
            g.setFont(Theme.monoFont(java.awt.Font.PLAIN, 11f));
            FontMetrics fm = g.getFontMetrics();
            int left = 64, right = 12, top = 10, bottom = fm.getHeight() + 8;
            int pw = w - left - right, ph = h - top - bottom;
            if (pw < 20 || ph < 20) return;
            double[] range = range();
            double total = dist.length == 0 ? 0 : dist[dist.length - 1];
            g.setColor(Theme.LINE);
            g.setStroke(new BasicStroke(1f));
            g.drawLine(left, top, left, top + ph);
            g.drawLine(left, top + ph, left + pw, top + ph);
            if (range == null || total <= 0) {
                g.setColor(Theme.MUTED);
                g.setFont(Theme.headingFont(11f));
                g.drawString(height.length == 0 ? "NO LINE MEASURED" : "LOOKING UP TERRAIN...", left + 10, top + ph / 2);
                return;
            }
            double lo = range[0], hi = range[1];
            if (hi - lo < 2) { // a flat line: some room so the curve isn't glued to an edge
                hi += 1;
                lo -= 1;
            }
            double pad = (hi - lo) * 0.08;
            double yLo = lo - pad, yHi = hi + pad;
            Path2D line = new Path2D.Double(), fill = new Path2D.Double();
            boolean open = false;
            double lastX = left;
            for (int i = 0; i < height.length; i++) {
                if (Double.isNaN(height[i])) {
                    if (open) {
                        fill.lineTo(lastX, top + ph);
                        fill.closePath();
                        open = false;
                    }
                    continue;
                }
                double x = left + dist[i] / total * pw, y = top + (yHi - height[i]) / (yHi - yLo) * ph;
                if (!open) {
                    line.moveTo(x, y);
                    fill.moveTo(x, top + ph);
                    fill.lineTo(x, y);
                    open = true;
                } else {
                    line.lineTo(x, y);
                    fill.lineTo(x, y);
                }
                lastX = x;
            }
            if (open) {
                fill.lineTo(lastX, top + ph);
                fill.closePath();
            }
            g.setPaint(new GradientPaint(0, top, Theme.alpha(Theme.ACCENT, 110), 0, top + ph, Theme.alpha(Theme.ACCENT, 15)));
            g.fill(fill);
            g.setColor(Theme.ACCENT);
            g.setStroke(new BasicStroke(1.8f));
            g.draw(line);
            int yHiPx = top + (int) ((yHi - hi) / (yHi - yLo) * ph), yLoPx = top + (int) ((yHi - lo) / (yHi - yLo) * ph);
            g.setColor(Theme.LINE);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3f, 4f}, 0f));
            g.drawLine(left, yHiPx, left + pw, yHiPx);
            g.drawLine(left, yLoPx, left + pw, yLoPx);
            g.setFont(Theme.monoFont(java.awt.Font.PLAIN, 11f));
            g.setColor(Theme.TEXT);
            label(g, fm, metres(hi), left - 6, yHiPx + fm.getAscent() / 2);
            label(g, fm, metres(lo), left - 6, yLoPx + fm.getAscent() / 2);
            g.setColor(Theme.MUTED);
            int by = top + ph + fm.getAscent() + 4;
            g.drawString("0 M", left, by);
            String mid = MapGraphics.distanceText(total / 2), end = MapGraphics.distanceText(total);
            g.drawString(mid, left + pw / 2 - fm.stringWidth(mid) / 2, by);
            g.drawString(end, left + pw - fm.stringWidth(end), by);
            if (known() < samples()) {
                String wait = known() + " / " + samples();
                g.drawString(wait, left + pw - fm.stringWidth(wait), top + fm.getAscent());
            }
        } finally {
            g.dispose();
        }
    }

    private static void label(Graphics2D g, FontMetrics fm, String text, int rightX, int baseline) {
        g.drawString(text, rightX - fm.stringWidth(text), baseline);
    }

    static String metres(double m) {
        return String.format(Locale.ROOT, "%.0f M", m);
    }
}
