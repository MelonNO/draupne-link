package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.Pins;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.function.LongSupplier;

/**
 * MARK on the video: armed (the MARK button, P), the cursor over the picture is a cross, and the next left click on
 * the picture drops a pin where the clicked point's ray meets the ground ({@link PinDrop}) - one click, one pin.
 * Esc, P or the button again ends it without one. It also draws the video's short lines ("PIN ALPHA 32V NM ...",
 * "STILL SAVED") for a few seconds, over the OSD: the screen's own, not the OSD's, so in no recording and no stream.
 * Armed, nothing but the cursor changes - no work per picture or per mouse move; the ray is cast once, at the click.
 * The Swing thread throughout.
 */
final class VideoMark extends MouseAdapter {
    /** A pin's line and its mark on the picture stay this long. */
    static final long PIN_LINE_MS = 3000;
    /** A second click this soon after the one that marked is the same hand's (a double-click): not the video's. */
    static final long SAME_CLICK_MS = 500;
    /** The line's baseline, down the picture: under the OSD's warnings, over its bottom rows. */
    static final float LINE_Y = 0.86f;
    private static final Color WHITE = new Color(0xf2f2ea), OUTLINE = new Color(0, 0, 0, 200);

    /** What marking needs of the window. */
    interface Host {
        /** Why marking can't start now ({@link VideoMark#off}), or null. */
        String off();

        /** The sight through picture point (u, v): {@link PinDrop#look} for the drone on the video. */
        PinDrop.Sight look(double u, double v);

        Pins pins();

        Coordinates.Format format();

        /** A pin was dropped: the maps repaint. @return false when they show other pins than this PC's (a spectator's: the pilot PC's) */
        boolean onMap(Pins.Pin pin);

        /** Armed or not: the button shows it. */
        void armed(boolean on);

        void log(String line);
    }

    /** A line on the video until `until`; (u, v) the clicked point's mark with it, NaN for none; hollow: a rough pin's. */
    private record Note(String text, Color color, long until, double u, double v, boolean hollow) {
    }

    private final VideoView view;
    private final Host host;
    private final LongSupplier clock;
    private boolean armed;
    private Note note;
    private long sameClickUntil;
    private Timer gone;

    /** @param clock ms, for how long a line stays (System::currentTimeMillis) */
    VideoMark(VideoView view, Host host, LongSupplier clock) {
        this.view = view;
        this.host = host;
        this.clock = clock;
        view.addMouseListener(this);
    }

    /**
     * Why MARK can't be armed now, or null. One cross-cursor mode at a time (the detection areas are drawn with the
     * same cursor and the same click), and only on a drone's own picture: the observation grid and the latency
     * test's code stand where it would be.
     */
    static String off(boolean observing, boolean latencyTest, boolean areasEditing) {
        if (observing) return "NO MARK - OBSERVING";
        if (latencyTest) return "NO MARK - LATENCY TEST";
        return areasEditing ? "NO MARK - END AREAS FIRST" : null;
    }

    boolean isArmed() {
        return armed;
    }

    /** The MARK button and P: arm, or end it without a pin. */
    void toggle() {
        if (armed) disarm();
        else arm();
    }

    /** @return false when refused: the video and the log say why */
    boolean arm() {
        if (armed) return true;
        String off = host.off();
        if (off != null) {
            say(off, true, PIN_LINE_MS);
            host.log(off + ".");
            return false;
        }
        armed = true;
        view.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        host.armed(true);
        host.look(0, 0); // the terrain round the drone is asked for now (CameraView.ray), while the pilot aims
        return true;
    }

    /** Esc, the button or P again, a pin dropped, the detection areas opened: over. */
    void disarm() {
        if (!armed) return;
        armed = false;
        view.setCursor(Cursor.getDefaultCursor());
        host.armed(false);
    }

    /** This click belongs to the one that just marked (its double-click): the video's own double-click leaves it alone. */
    boolean claims(MouseEvent e) {
        return e.getWhen() <= sameClickUntil;
    }

    // ------------------------------------------------------------------ the click

    @Override
    public void mousePressed(MouseEvent e) {
        if (!armed || !SwingUtilities.isLeftMouseButton(e)) return;
        if (host.off() != null) { // another mode took the picture meanwhile: the click and the cursor are its own
            armed = false;
            host.armed(false);
            return;
        }
        double[] uv = PinDrop.pictureUv(e.getPoint(), view.pictureRect());
        if (uv == null) return; // beside the picture: nothing, and still armed
        sameClickUntil = e.getWhen() + SAME_CLICK_MS;
        PinDrop.Verdict v = PinDrop.decide(host.look(uv[0], uv[1]));
        if (v.refusal() != null) { // no pin: still armed, for a click that can have one
            say(v.refusal(), true, PIN_LINE_MS);
            return;
        }
        disarm();
        Pins.Pin pin = host.pins().add(null, v.lat(), v.lon(), v.rough());
        show(new Note(PinDrop.line(pin, host.format()), WHITE, clock.getAsLong() + PIN_LINE_MS, uv[0], uv[1], pin.rough()), PIN_LINE_MS);
        boolean onMap = host.onMap(pin);
        host.log(PinDrop.logLine(pin, host.format(), v.why())
                + (onMap ? "" : " Kept on this PC; the map shows the pilot PC's pins while watching."));
    }

    // ------------------------------------------------------------------ the lines

    /** A short line on the video for ms. @param bad a refusal or a failure: in the warning colour */
    void say(String text, boolean bad, long ms) {
        show(new Note(text, bad ? Theme.WARN : WHITE, clock.getAsLong() + ms, Double.NaN, Double.NaN, false), ms);
    }

    /** The line showing now, or null (tests). */
    String line() {
        Note n = note;
        return n == null || clock.getAsLong() >= n.until() ? null : n.text();
    }

    private void show(Note n, long ms) {
        note = n;
        if (gone != null) gone.stop();
        // a picture that stands still (no link) is not painted again by itself: once, when the line is over
        gone = new Timer((int) ms + 40, e -> view.repaint());
        gone.setRepeats(false);
        gone.start();
        view.repaint();
    }

    /** Over the picture at r (VideoView, after the OSD). Without a line: one look at a field. */
    void paint(Graphics2D graphics, Rectangle r) {
        Note n = note;
        if (n == null) return;
        if (clock.getAsLong() >= n.until()) {
            note = null;
            return;
        }
        if (r.width <= 0 || r.height <= 0) return;
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        try {
            float unit = Math.max(0.65f, r.height / 720f);
            if (!Double.isNaN(n.u())) mark(g, r.x + (float) ((n.u() + 1) / 2 * r.width), r.y + (float) ((n.v() + 1) / 2 * r.height), unit, n.hollow());
            g.setFont(Theme.headingFont(20 * unit));
            FontMetrics fm = g.getFontMetrics();
            float x = r.x + (r.width - fm.stringWidth(n.text())) / 2f, y = r.y + r.height * LINE_Y;
            g.setColor(OUTLINE); // the OSD's text: a dark edge all round, readable on any picture
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) g.drawString(n.text(), x + dx * 1.5f, y + dy * 1.5f);
                }
            }
            g.setColor(n.color());
            g.drawString(n.text(), x, y);
        } finally {
            g.dispose();
        }
    }

    /** The clicked point: the map's pin, small - a diamond, filled for a pin, an outline for a rough one. */
    private static void mark(Graphics2D g, float x, float y, float unit, boolean hollow) {
        float a = 7 * unit, b = 9 * unit;
        Path2D d = new Path2D.Float();
        d.moveTo(x, y - b);
        d.lineTo(x + a, y);
        d.lineTo(x, y + b);
        d.lineTo(x - a, y);
        d.closePath();
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(4.5f * unit));
        g.draw(d);
        if (!hollow) {
            g.setColor(Theme.WARN);
            g.fill(d);
        }
        g.setColor(hollow ? Theme.WARN : Color.WHITE);
        g.setStroke(new BasicStroke(1.6f * unit));
        g.draw(d);
    }
}
