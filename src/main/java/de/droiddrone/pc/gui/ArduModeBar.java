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
        if (!isVisible()) return; // GU-8: hidden (not this PC's own ArduPilot drone): a press sends nothing
        byte[] c = commandFor(a);
        if (!send.test(c)) return;
        paused = a == Action.START && modeNow == ArduModes.AUTO;
        lastSent = a;
        ackBeforePress = newestAck; // only an answer after this press is this press's
        pressedAt = lastNow;
    }

    /** The newest answer seen (its time), and the newest when the last button was pressed. */
    private long newestAck = Long.MIN_VALUE, ackBeforePress = Long.MIN_VALUE;

    /** MAVLink's command numbers the FC answers for: a mode (DO_SET_MODE), START (MISSION_START), PAUSE / CONTINUE. */
    static final int CMD_SET_MODE = 176, CMD_MISSION_START = 300, CMD_PAUSE_CONTINUE = 193;

    /** The answer is to what this button sent (not to the route window's GO, nor to a press before). */
    private boolean answers(Action a, FcTelemetry.CommandAck ack) {
        if (ack.receivedAt() > pressedAt + ANSWER_MS) return false; // the phone's own mode change much later (its link-loss rule)
        // GO is a mode (AUTO, or LOITER to pause) and MISSION_START on the phone
        return ack.command() == CMD_SET_MODE || a == Action.START && (ack.command() == CMD_MISSION_START || ack.command() == CMD_PAUSE_CONTINUE);
    }

    /** An answer comes within this of the press (the link's round trip and the FC's): later ones are another's. */
    static final long ANSWER_MS = 5000;
    /** The window's clock at the press (the last update's). */
    private long lastNow, pressedAt;

    /** The button shown red now (null: none). */
    Action refusedNow() {
        return refused;
    }

    /**
     * Each tick of the window: shown for an ArduPilot drone only; the lit button follows its mode; a refusal of the last
     * command shows red for {@link #REFUSED_SHOW_MS}.
     */
    void update(FcTelemetry.Status s, int arduMode, FcTelemetry.CommandAck ack, long now) {
        update(s, arduMode, ack, now, true);
    }

    /**
     * As above; ownDrone false (GU-8: a spectator's or a watcher's link): never shown. The OSD's flight mode still says
     * the mode; the buttons are the pilot's alone.
     */
    void update(FcTelemetry.Status s, int arduMode, FcTelemetry.CommandAck ack, long now, boolean ownDrone) {
        lastNow = now;
        boolean ardu = ownDrone && s != null && s.isArduPilot();
        if (ardu != isVisible()) setVisible(ardu);
        if (!ardu) return;
        int mode = arduMode;
        // a refusal from before the press (an hour old, or the route window's GO) never turns the pressed button red
        if (ack != null && !ack.accepted() && ack.receivedAt() != refusedAt && lastSent != null
                && ack.receivedAt() > ackBeforePress && answers(lastSent, ack)) {
            refused = lastSent;
            refusedAt = ack.receivedAt();
        }
        if (ack != null) newestAck = Math.max(newestAck, ack.receivedAt());
        if (refused != null && now - refusedAt > REFUSED_SHOW_MS) refused = null;
        // GU-4: the radio's mode switch took it out of the paused route's LOITER: the next GO starts the route anew
        if (paused && mode != ArduModes.LOITER && mode != ArduModes.AUTO) paused = false;
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
