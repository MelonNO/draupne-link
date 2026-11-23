package de.droiddrone.pc.gui;

import de.droiddrone.pc.route.RouteProfile;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/**
 * Fleet PRD RT-7 / RT-6: a route's profile before its upload - the ground (filled), the line the drone flies, the
 * lowest clearance marked, stretches without 4G shaded, the summary on top and the bare-ground note under it.
 * Shows what it is given ({@link #setProfile}); paints only, on the Swing thread.
 */
public final class RouteProfileView extends JComponent {
    private volatile RouteProfile profile;

    public RouteProfileView() {
        setPreferredSize(new Dimension(420, 170));
        setOpaque(true);
    }

    /** null: "NO ROUTE". */
    public void setProfile(RouteProfile p) {
        profile = p;
        repaint();
    }

    public RouteProfile profile() {
        return profile;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            paint(g, profile, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    static void paint(Graphics2D g, RouteProfile p, int w, int h) {
        g.setColor(Theme.PANEL);
        g.fillRect(0, 0, w, h);
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        if (p == null || p.along().length < 2) {
            g.setColor(Theme.MUTED);
            g.drawString("NO ROUTE", 8, fm.getAscent() + 6);
            return;
        }
        int top = fm.getHeight() + 8, bottom = h - 18, left = 36, right = w - 8;
        if (bottom - top < 20 || right - left < 40) return;
        g.setColor(Theme.TEXT);
        g.drawString(p.summary(), 8, fm.getAscent() + 4);

        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < p.along().length; i++) {
            minZ = Math.min(minZ, p.ground()[i]);
            maxZ = Math.max(maxZ, Math.max(p.line()[i], p.ground()[i]));
        }
        double pad = Math.max(5, (maxZ - minZ) * 0.08);
        minZ -= pad;
        maxZ += pad;
        double len = Math.max(1, p.along()[p.along().length - 1]);
        double zr = maxZ - minZ;

        // no 4G: shaded stretches
        for (double[] gap : p.noCoverage()) {
            int x0 = x(gap[0], len, left, right), x1 = x(gap[1], len, left, right);
            g.setColor(Theme.alpha(Theme.WARN, 40));
            g.fillRect(x0, top, Math.max(1, x1 - x0), bottom - top);
        }
        // the ground
        Path2D ground = new Path2D.Double();
        ground.moveTo(left, bottom);
        for (int i = 0; i < p.along().length; i++) ground.lineTo(x(p.along()[i], len, left, right), y(p.ground()[i], minZ, zr, top, bottom));
        ground.lineTo(right, bottom);
        ground.closePath();
        g.setColor(Theme.PANEL_2);
        g.fill(ground);
        g.setColor(Theme.MUTED);
        g.draw(ground);
        // the line flown
        Path2D line = new Path2D.Double();
        for (int i = 0; i < p.along().length; i++) {
            double px = x(p.along()[i], len, left, right), py = y(p.line()[i], minZ, zr, top, bottom);
            if (i == 0) line.moveTo(px, py);
            else line.lineTo(px, py);
        }
        g.setColor(Theme.OK);
        g.setStroke(new BasicStroke(2f));
        g.draw(line);
        g.setStroke(new BasicStroke(1f));
        // the lowest clearance
        int li = nearest(p.along(), p.lowestAtM());
        int lx = x(p.lowestAtM(), len, left, right);
        int ly1 = y(p.line()[li], minZ, zr, top, bottom), ly2 = y(p.ground()[li], minZ, zr, top, bottom);
        g.setColor(Theme.WARN);
        g.drawLine(lx, ly1, lx, ly2);
        String low = String.format(java.util.Locale.ROOT, "LOWEST %.0f M", p.lowestClearanceM());
        g.setFont(Theme.headingFont(10f));
        FontMetrics sf = g.getFontMetrics();
        int tx = Math.min(right - sf.stringWidth(low), Math.max(left, lx + 4));
        g.drawString(low, tx, Math.max(top + sf.getAscent(), ly1 - 4));
        // the height scale
        g.setColor(Theme.MUTED);
        g.drawString(String.format(java.util.Locale.ROOT, "%.0f", maxZ), 2, top + sf.getAscent());
        g.drawString(String.format(java.util.Locale.ROOT, "%.0f", minZ), 2, bottom);
        // RT-6
        g.setFont(Theme.font(java.awt.Font.PLAIN, 10f));
        g.drawString(RouteProfile.NOTE, 8, h - 4);
    }

    private static int nearest(double[] along, double at) {
        int best = 0;
        for (int i = 1; i < along.length; i++) if (Math.abs(along[i] - at) < Math.abs(along[best] - at)) best = i;
        return best;
    }

    private static int x(double along, double len, int left, int right) {
        return (int) Math.round(left + along / len * (right - left));
    }

    private static int y(double z, double minZ, double zr, int top, int bottom) {
        return (int) Math.round(bottom - (z - minZ) / zr * (bottom - top));
    }
}
