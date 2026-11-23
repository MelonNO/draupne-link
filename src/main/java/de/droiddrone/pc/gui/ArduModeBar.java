package de.droiddrone.pc.gui;

import de.droiddrone.common.FcCommand;
import de.droiddrone.pc.ArduModes;
import de.droiddrone.pc.FcTelemetry;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Predicate;

/**
 * Fleet PRD AP-6 / G2: the OSD's mode buttons for an ArduPilot drone, on the video's edge under the other buttons -
 * the backup to the radio's own mode switch. LOITER, RTL, LAND, and START / PAUSE for an uploaded route. Shown only
 * while the connected drone is ArduPilot; the mode it is in now is lit. One press sends the command (a mode set
 * twice is the same mode); the flight controller's answer comes back through the telemetry and a refusal turns the
 * button red for a moment. Nothing here runs on the stick or video path.
 */
final class ArduModeBar extends JPanel {
    static final int WIDTH = 30, HEIGHT = 22;
    /** A refusal shows this long. */
    static final long REFUSED_SHOW_MS = 3000;

    /** What each button sends and the mode it lights for. */
    enum Action {
        LOITER("LOI", "LOITER: hold here", ArduModes.LOITER),
        RTL("RTL", "RTL: come home", ArduModes.RTL),
        LAND("LND", "LAND here", ArduModes.LAND),
        START("GO", "Route: START, or go on after PAUSE", ArduModes.AUTO);

        final String label, tip;
        final int mode;

        Action(String label, String tip, int mode) {
            this.label = label;
            this.tip = tip;
            this.mode = mode;
        }
    }

    private final Predicate<byte[]> send;
    private final Button[] buttons = new Button[Action.values().length];
    private int modeNow = -1;
    private Action refused;
    private long refusedAt;
    private Action lastSent;
    /** GO paused a running route (the drone holds in LOITER): the next GO goes on from where it was. */
    private boolean paused;

    /** @param send sends a command (ControlLink.sendFcCommand); false: not sent */
    ArduModeBar(Predicate<byte[]> send) {
        this.send = send;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        for (Action a : Action.values()) {
            Button b = new Button(a);
            b.addActionListener(e -> press(a));
            buttons[a.ordinal()] = b;
            add(b);
            add(javax.swing.Box.createVerticalStrut(3));
        }
        setVisible(false);
    }

    /** What a press sends. GO: PAUSE while the route runs (AUTO), CONTINUE after a PAUSE, else START from the first point. */
    byte[] commandFor(Action a) {
        return switch (a) {
            case LOITER -> FcCommand.mode(ArduModes.LOITER);
            case RTL -> FcCommand.mode(ArduModes.RTL);
            case LAND -> FcCommand.mode(ArduModes.LAND);
            case START -> modeNow == ArduModes.AUTO ? FcCommand.simple(FcCommand.PAUSE)
                    : paused && modeNow == ArduModes.LOITER ? FcCommand.simple(FcCommand.CONTINUE) : FcCommand.simple(FcCommand.MISSION_START);
        };
    }

    void press(Action a) {
        byte[] c = commandFor(a);
        if (!send.test(c)) return;
        paused = a == Action.START && modeNow == ArduModes.AUTO;
        lastSent = a;
    }

    /**
     * Each tick of the window: shown for an ArduPilot drone only; the lit button follows its mode; a refusal of the last
     * command shows red for {@link #REFUSED_SHOW_MS}.
     */
    void update(FcTelemetry.Status s, int arduMode, FcTelemetry.CommandAck ack, long now) {
        boolean ardu = s != null && s.isArduPilot();
        if (ardu != isVisible()) setVisible(ardu);
        if (!ardu) return;
        int mode = arduMode;
        if (ack != null && !ack.accepted() && ack.receivedAt() != refusedAt && lastSent != null) {
            refused = lastSent;
            refusedAt = ack.receivedAt();
        }
        if (refused != null && now - refusedAt > REFUSED_SHOW_MS) refused = null;
        if (mode != modeNow || refused != null) {
            modeNow = mode;
            for (Button b : buttons) b.repaint();
        }
    }

    boolean lit(Action a) {
        return a == Action.START ? modeNow == ArduModes.AUTO : modeNow == a.mode;
    }

    private final class Button extends JButton {
        private final Action action;

        Button(Action a) {
            super(a.label);
            this.action = a;
            setToolTipText(a.tip);
            Dimension d = new Dimension(WIDTH, HEIGHT);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setAlignmentX(CENTER_ALIGNMENT);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                boolean on = lit(action);
                boolean bad = refused == action;
                Color fill = bad ? Theme.BAD : on ? Theme.ACCENT : new Color(0, 0, 0, getModel().isRollover() ? 170 : 120);
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                g.setColor(on || bad ? Theme.BG : Color.WHITE);
                g.setFont(Theme.headingFont(10f).deriveFont(Font.BOLD));
                String t = action == Action.START && modeNow == ArduModes.AUTO ? "II" : action.label;
                int w = g.getFontMetrics().stringWidth(t);
                g.drawString(t, (getWidth() - w) / 2, (getHeight() + g.getFontMetrics().getAscent()) / 2 - 2);
            } finally {
                g.dispose();
            }
        }
    }
}
