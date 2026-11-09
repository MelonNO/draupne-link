package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.CellCoverage;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.OpenCellId;
import de.droiddrone.pc.map.RadioHorizon;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * The map tools' results over the map: COVERAGE (the estimate, the masts, the legend), GCS SEARCH (the heat map, the
 * area, the best place) and RADIO HORIZON (the ground seen from the mast, the rings). The pictures and shapes are made
 * once per result; MapView keeps them and the labels' crosses.
 */
final class MapToolLayer {
    private MapToolLayer() {
    }

    /** The levels as see-through colours, the GCS SEARCH's red - yellow - green with orange between; unknown left clear. */
    static final Color[] COVERAGE_COLOURS = {new Color(227, 27, 35, 110), new Color(255, 140, 0, 110), new Color(252, 212, 5, 100),
            new Color(46, 204, 64, 95)};
    static final String[] COVERAGE_NAMES = {"NONE", "WEAK", "FAIR", "GOOD"};

    static BufferedImage coverageImage(CellCoverage.Grid g) {
        BufferedImage img = new BufferedImage(g.cols(), g.rows(), BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < g.rows(); r++) {
            for (int c = 0; c < g.cols(); c++) {
                int v = g.at(c, r);
                if (v >= 0) img.setRGB(c, r, COVERAGE_COLOURS[v].getRGB());
            }
        }
        return img;
    }

    /** A mast's dot: Telenor blue, Telia purple, Ice orange, others grey. */
    static Color operatorColour(OpenCellId.Operator op) {
        if (op == null) return new Color(0x9e9e9e);
        return switch (op) {
            case TELENOR -> new Color(0x2196f3);
            case TELIA -> new Color(0xb455f0);
            case ICE -> new Color(0xff8c1a);
            default -> new Color(0x9e9e9e);
        };
    }

    static final int SEARCH_BUCKETS = 11; // 0 %, 10 % ... 100 %

    /** The heat map as a picture, a pixel per cell (row 0 north), see-through outside the 30 km circle. */
    static BufferedImage searchImage(RadioHorizon.Search r) {
        BufferedImage img = new BufferedImage(r.size(), r.size(), BufferedImage.TYPE_INT_ARGB);
        int[] colours = new int[SEARCH_BUCKETS];
        for (int i = 0; i < SEARCH_BUCKETS; i++) colours[i] = searchColour(i / (double) (SEARCH_BUCKETS - 1)).getRGB();
        for (int cy = 0; cy < r.size(); cy++) {
            for (int cx = 0; cx < r.size(); cx++) {
                float v = r.at(cx, cy);
                if (!Float.isNaN(v)) img.setRGB(cx, cy, colours[Math.round(v * (SEARCH_BUCKETS - 1))]);
            }
        }
        return img;
    }

    /** Red (no point of the area sees a mast there) through yellow to green (every point does). */
    static Color searchColour(double v) {
        Color bad = new Color(227, 27, 35), mid = new Color(252, 212, 5), good = new Color(46, 204, 64);
        Color a = v < 0.5 ? bad : mid, b = v < 0.5 ? mid : good;
        double t = v < 0.5 ? v * 2 : (v - 0.5) * 2;
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t), 110);
    }

    /** One quad per seen stretch, a degree wide, in zoom-0 world pixels (painted through a transform at any zoom). */
    static Path2D horizonShape(RadioHorizon.Result r) {
        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (int b = 0; b < r.spans().length; b++) {
            float[] s = r.spans()[b];
            for (int i = 0; i + 1 < s.length; i += 2) {
                double[] a = worldAt(r, b - 0.5, s[i]), c = worldAt(r, b + 0.5, s[i]);
                double[] d = worldAt(r, b + 0.5, s[i + 1]), e = worldAt(r, b - 0.5, s[i + 1]);
                path.moveTo(a[0], a[1]);
                path.lineTo(c[0], c[1]);
                path.lineTo(d[0], d[1]);
                path.lineTo(e[0], e[1]);
                path.closePath();
            }
        }
        return path;
    }

    /** The zoom-0 world pixel distM from the mast on a bearing. */
    private static double[] worldAt(RadioHorizon.Result r, double bearingDeg, double distM) {
        double[] ll = RadioHorizon.destination(r.lat(), r.lon(), bearingDeg, distM);
        return new double[]{Mercator.x(ll[1], 0), Mercator.y(ll[0], 0)};
    }

    /** Blue, not green: it reads over the GCS SEARCH's red - yellow - green heat map (the pilot: both at the same time). */
    private static final Color HORIZON_SEEN = new Color(30, 144, 255, 120);

    /** GCS SEARCH: the heat map, the 30 km circle round the area's centre, the area's box, the best place. */
    static Rectangle paintSearch(Graphics2D g0, double[] searchArea, RadioHorizon.Search search, BufferedImage searchImage, String searchStatus,
                                 MapProjection proj) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            if (searchImage != null) { // the grid's outer corners on screen; cells stay sharp (nearest neighbour)
                double half = search.size() / 2.0 * RadioHorizon.SEARCH_CELL_M;
                double dLat = half / 111_320.0, dLon = half / (111_320.0 * Math.cos(Math.toRadians(search.lat())));
                Point2D tl = proj.toScreen(search.lat() + dLat, search.lon() - dLon);
                Point2D br = proj.toScreen(search.lat() - dLat, search.lon() + dLon);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(searchImage, (int) Math.round(tl.getX()), (int) Math.round(tl.getY()), (int) Math.round(br.getX()),
                        (int) Math.round(br.getY()), 0, 0, searchImage.getWidth(), searchImage.getHeight(), null);
            }
            double cLat = (searchArea[0] + searchArea[1]) / 2, cLon = (searchArea[2] + searchArea[3]) / 2;
            Point2D c = proj.toScreen(cLat, cLon);
            double pxPerM = proj.scale() / Mercator.metersPerPixel(cLat, proj.zi());
            double r = RadioHorizon.MAX_RANGE_M * pxPerM;
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 6f}, 0f));
            g.setColor(Theme.ACCENT);
            g.draw(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
            Point2D nw = proj.toScreen(searchArea[1], searchArea[2]);
            Point2D se = proj.toScreen(searchArea[0], searchArea[3]);
            g.setStroke(new BasicStroke(2f));
            g.setColor(Color.WHITE);
            g.draw(new Rectangle((int) Math.round(nw.getX()), (int) Math.round(nw.getY()),
                    (int) Math.round(se.getX() - nw.getX()), (int) Math.round(se.getY() - nw.getY())));
            int labelY = (int) Math.round(se.getY()) + 4;
            if (search != null) { // the best place: a ring and its share
                Point2D b = proj.toScreen(search.bestLat(), search.bestLon());
                int bx = (int) Math.round(b.getX()), by = (int) Math.round(b.getY());
                g.setStroke(new BasicStroke(3f));
                g.setColor(Color.BLACK);
                g.drawOval(bx - 9, by - 9, 18, 18);
                g.setStroke(new BasicStroke(2f));
                g.setColor(Color.WHITE);
                g.drawOval(bx - 9, by - 9, 18, 18);
                g.drawLine(bx - 13, by, bx - 5, by);
                g.drawLine(bx + 5, by, bx + 13, by);
                g.drawLine(bx, by - 13, bx, by - 5);
                g.drawLine(bx, by + 5, bx, by + 13);
                label(g, "BEST " + Math.round(search.bestScore() * 100) + " %", bx, by + 16);
            }
            return labelWithCross(g, searchStatus != null ? searchStatus : "GCS SEARCH", (int) Math.round((nw.getX() + se.getX()) / 2), labelY);
        } finally {
            g.dispose();
        }
    }

    /** Masts are drawn from this zoom in (further out they would cover the estimate). */
    static final double COVERAGE_MASTS_ZOOM = 11;

    /**
     * 4G COVERAGE: the estimate's colours over the map, the masts as small dots in their network's colour, and the
     * legend bottom left - that it is an estimate, the colours, what it is for, the status, and OpenCelliD's attribution.
     */
    static void paintCoverage(Graphics2D g0, CellCoverage.Grid grid, BufferedImage coverageImage, String coverageStatus, MapProjection proj) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            if (grid != null && coverageImage != null) {
                double k = proj.level() * proj.scale(); // zoom-0 world pixels to screen
                int sx0 = (int) Math.round(proj.screenX(grid.x0() * proj.level()));
                int sy0 = (int) Math.round(proj.screenY(grid.y0() * proj.level()));
                int sx1 = (int) Math.round(sx0 + (grid.x1() - grid.x0()) * k), sy1 = (int) Math.round(sy0 + (grid.y1() - grid.y0()) * k);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(coverageImage, sx0, sy0, sx1, sy1, 0, 0, coverageImage.getWidth(), coverageImage.getHeight(), null);
                if (proj.zoom() >= COVERAGE_MASTS_ZOOM) {
                    g.setStroke(new BasicStroke(1.5f));
                    for (CellCoverage.Site s : grid.sites()) {
                        Point2D p = proj.toScreen(s.lat(), s.lon());
                        if (p.getX() < -8 || p.getY() < -8 || p.getX() > proj.w() + 8 || p.getY() > proj.h() + 8) continue;
                        int x = (int) Math.round(p.getX()), y = (int) Math.round(p.getY());
                        g.setColor(operatorColour(s.operator()));
                        g.fillOval(x - 4, y - 4, 9, 9);
                        g.setColor(Color.BLACK);
                        g.drawOval(x - 4, y - 4, 9, 9);
                        if (s.nr()) { // 5G there too: a white ring round it
                            g.setColor(Color.WHITE);
                            g.drawOval(x - 7, y - 7, 15, 15);
                        }
                    }
                }
            }
            paintCoverageLegend(g, grid, coverageStatus, proj.h());
        } finally {
            g.dispose();
        }
    }

    /** Bottom left over the scale bar: the top middle has NO DRONE POSITION, the top right the zoom buttons. */
    private static void paintCoverageLegend(Graphics2D g, CellCoverage.Grid grid, String coverageStatus, int h) {
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        Font small = Theme.font(Font.PLAIN, 10.5f);
        FontMetrics sm = g.getFontMetrics(small);
        String title = "COVERAGE (ESTIMATE)" + (grid != null ? String.format(Locale.ROOT, "   DRONE %.0f M   %s", grid.droneM(),
                grid.operator().title.toUpperCase(Locale.ROOT)) : "");
        String status = coverageStatus == null ? "" : coverageStatus;
        int lh = fm.getHeight(), sh = sm.getHeight();
        int swatchW = 0;
        for (String n : COVERAGE_NAMES) swatchW += 14 + fm.stringWidth(n) + 12;
        int bw = Math.max(Math.max(fm.stringWidth(title), swatchW), Math.max(sm.stringWidth(status), sm.stringWidth(OpenCellId.ATTRIBUTION))) + 16;
        int bh = lh + lh + 4 + (status.isEmpty() ? 0 : sh) + sh + 10;
        int x = 4, y = h - 50 - bh;
        g.setColor(MapView.BOX);
        g.fillRect(x, y, bw, bh);
        g.setColor(Theme.ACCENT);
        g.fillRect(x, y, 3, bh);
        int ty = y + 4 + fm.getAscent();
        g.setColor(Theme.TEXT);
        g.drawString(title, x + 8, ty);
        ty += lh + 2;
        int sx = x + 8;
        for (int i = COVERAGE_NAMES.length - 1; i >= 0; i--) { // GOOD first
            Color c = COVERAGE_COLOURS[i];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue()));
            g.fillRect(sx, ty - fm.getAscent() + 2, 10, 10);
            g.setColor(Theme.TEXT);
            g.drawString(COVERAGE_NAMES[i], sx + 14, ty);
            sx += 14 + fm.stringWidth(COVERAGE_NAMES[i]) + 12;
        }
        g.setFont(small);
        ty += sh + 2;
        if (!status.isEmpty()) {
            g.setColor(Theme.TEXT);
            g.drawString(status, x + 8, ty);
            ty += sh;
        }
        g.setColor(Theme.MUTED);
        g.drawString(OpenCellId.ATTRIBUTION, x + 8, ty);
    }

    /** A line of text on a dark box, centred on x, from y down. */
    private static void label(Graphics2D g, String text, int x, int y) {
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text) + 10;
        g.setColor(new Color(10, 10, 10, 215));
        g.fillRect(x - tw / 2, y, tw, fm.getHeight() + 2);
        g.setColor(Theme.TEXT);
        g.drawString(text, x - tw / 2 + 5, y + 1 + fm.getAscent());
    }

    /** The label with a cross at its right end (in the accent colour, like the tour's EXIT); the cross's box. */
    private static Rectangle labelWithCross(Graphics2D g, String text, int x, int y) {
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        int bh = fm.getHeight() + 2, tw = fm.stringWidth(text) + 10, all = tw + bh;
        int x0 = x - all / 2;
        g.setColor(new Color(10, 10, 10, 215));
        g.fillRect(x0, y, all, bh);
        g.setColor(Theme.TEXT);
        g.drawString(text, x0 + 5, y + 1 + fm.getAscent());
        Rectangle cross = new Rectangle(x0 + tw, y, bh, bh);
        g.setColor(Theme.ACCENT);
        g.fillRect(cross.x, cross.y, cross.width, cross.height);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int m = 5;
        g.drawLine(cross.x + m, cross.y + m, cross.x + cross.width - m, cross.y + cross.height - m);
        g.drawLine(cross.x + cross.width - m, cross.y + m, cross.x + m, cross.y + cross.height - m);
        return cross;
    }

    /** RADIO HORIZON: the seen ground blue, the 30 km limit and the smooth-earth horizon as dashed rings, the mast. */
    static Rectangle paintHorizon(Graphics2D g0, MapGraphics.LatLon horizonMast, RadioHorizon.Result horizon, Path2D horizonShape,
                                  String horizonStatus, MapProjection proj) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            if (horizonShape != null) {
                double k = proj.level() * proj.scale();
                AffineTransform t = new AffineTransform();
                t.translate(proj.w() / 2.0 - proj.wcx() * proj.scale(), proj.h() / 2.0 - proj.wcy() * proj.scale());
                t.scale(k, k);
                // no antialiasing: the quads' shared edges would show as seams
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                g.setColor(HORIZON_SEEN);
                g.fill(t.createTransformedShape(horizonShape));
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            }
            Point2D c = proj.toScreen(horizonMast.lat(), horizonMast.lon());
            double pxPerM = proj.scale() / Mercator.metersPerPixel(horizonMast.lat(), proj.zi());
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{8f, 6f}, 0f));
            g.setColor(Theme.ACCENT);
            double r = RadioHorizon.MAX_RANGE_M * pxPerM;
            g.draw(new Ellipse2D.Double(c.getX() - r, c.getY() - r, 2 * r, 2 * r));
            if (horizon != null && horizon.smoothEarthM() < RadioHorizon.MAX_RANGE_M) {
                double rs = horizon.smoothEarthM() * pxPerM;
                g.setColor(Theme.TEXT);
                g.draw(new Ellipse2D.Double(c.getX() - rs, c.getY() - rs, 2 * rs, 2 * rs));
            }
            // the mast: a tower with its waves
            int x = (int) Math.round(c.getX()), y = (int) Math.round(c.getY());
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.BLACK);
            g.fillOval(x - 4, y - 4, 8, 8);
            g.setColor(Theme.ACCENT);
            g.drawLine(x, y, x - 6, y + 12);
            g.drawLine(x, y, x + 6, y + 12);
            g.drawLine(x, y - 14, x, y);
            g.drawArc(x - 7, y - 21, 14, 14, -30, 240);
            return labelWithCross(g, horizonStatus != null ? horizonStatus : "RADIO HORIZON", x, y + 16);
        } finally {
            g.dispose();
        }
    }
}
