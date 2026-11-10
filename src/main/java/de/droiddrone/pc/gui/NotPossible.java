package de.droiddrone.pc.gui;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Point;

/**
 * A press that can't do anything (the mode it is in already, an armed drone): the button shakes side to side, quickly
 * dying away (2026-11-10, the pilot: "when the user presses the button have a obvious not possible animation"). The
 * reason goes in a {@link LockedNotice} beside it.
 */
final class NotPossible {
    private NotPossible() {
    }

    static final long MS = 380;
    /** How far it goes each way at first (px), and how often it turns. */
    private static final double SWING_PX = 7, TURNS = 4.5;
    private static final String RUNNING = "draupne.notPossible";

    /** How far off its place it is at t ms into the shake: dies away to 0 by MS. */
    static int offset(long t) {
        if (t <= 0 || t >= MS) return 0;
        double p = t / (double) MS;
        return (int) Math.round(Math.sin(p * TURNS * 2 * Math.PI) * SWING_PX * (1 - p));
    }

    /** Shakes c (Swing thread); a shake already running goes on as it is. */
    static void shake(JComponent c) {
        if (c == null || Boolean.TRUE.equals(c.getClientProperty(RUNNING))) return;
        c.putClientProperty(RUNNING, Boolean.TRUE);
        Point home = c.getLocation();
        long start = System.currentTimeMillis();
        Timer t = new Timer(15, null);
        t.addActionListener(e -> {
            long at = System.currentTimeMillis() - start;
            if (at >= MS) {
                t.stop();
                c.setLocation(home);
                c.putClientProperty(RUNNING, null);
                if (c.getParent() != null) c.getParent().revalidate(); // its place, should the layout have changed meanwhile
                return;
            }
            c.setLocation(home.x + offset(at), home.y);
        });
        t.start();
    }

    /** Shakes it and says why under it. */
    static void say(JComponent c, String title, String text) {
        shake(c);
        new LockedNotice(c, c, title, text).pop();
    }
}
