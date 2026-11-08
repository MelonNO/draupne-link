package de.droiddrone.pc.gui;

import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.ControlLink;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FLEET MANAGEMENT (1.0.52, the pilot): a thin FLEET tab at the map window's right edge; pressed, a column of cards
 * slides out - one per phone: its sign and mode, the drone's battery per cell and the phone's as gauges, a ring running
 * down to its next check-in, signal, GPS, temperatures, overwatch events - and buttons that queue STANDBY, DEEP SLEEP
 * or OVERWATCH without connecting (FleetManager: sent when the phone next listens). The cards come once a second from
 * the main window ({@link #setCards}); painting them is all this does on the Swing thread.
 */
final class FleetManagementPanel extends JPanel {
    /** What the buttons do (the main window). intervalMin as SleepCheck has it (255: always listening). */
    interface Actions {
        void mode(String phone, int mode, int intervalMin);

        void clips(String phone);

        void cancel(String phone);
    }

    static final int BODY_WIDTH = 300;
    /** The painted top of a card. */
    static final int FACE_HEIGHT = 156;
    private static final int TAB_WIDTH = 26;
    private static final int MS = 220;
    /** CHECK EVERY's choices, as the LINK card has them: 0 (always listening), 1, 2, 5 min. */
    private static final int[] INTERVALS = {SleepCheck.ALWAYS, 1, 2, 5};

    private final Actions actions;
    private final JButton tab = new Tab();
    private final JPanel list = new JPanel();
    private final JPanel empty = new JPanel(new BorderLayout());
    private final Map<String, CardView> views = new LinkedHashMap<>();
    private final Timer slide = new Timer(15, e -> tick());
    private boolean open;
    private float shown, from, to;
    private long slideStart;
    private int count;

    FleetManagementPanel(Actions actions) {
        super(new BorderLayout());
        this.actions = actions;
        setOpaque(false);
        tab.setToolTipText("Every phone: battery, mode, check-ins. Change modes without connecting");
        tab.addActionListener(e -> setOpen(!open, true));
        add(tab, BorderLayout.EAST);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(Theme.PANEL_2);
        list.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // no blit scrolling inside a clipped parent (see MapToolsPanel)
        scroll.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
        scroll.getViewport().setBackground(Theme.PANEL_2);
        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(Theme.PANEL_2);
        head.setBorder(BorderFactory.createEmptyBorder(10, 10, 2, 10));
        head.add(new JComponent() {
            {
                setPreferredSize(new Dimension(10, 20));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
                try {
                    g.setFont(Theme.headingFont(13f));
                    g.setColor(Theme.ACCENT);
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString("FLEET MANAGEMENT", 0, fm.getAscent());
                    String n = count + (count == 1 ? " PHONE" : " PHONES");
                    g.setFont(Theme.headingFont(11f));
                    g.setColor(Theme.MUTED);
                    g.drawString(n, getWidth() - g.getFontMetrics().stringWidth(n), fm.getAscent());
                } finally {
                    g.dispose();
                }
            }
        }, BorderLayout.CENTER);
        empty.setOpaque(false);
        empty.add(Theme.label("No phones yet.", Theme.font(java.awt.Font.PLAIN, 12f), Theme.MUTED), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(Math.round(BODY_WIDTH * eased(shown)), 10);
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        body.setOpaque(false);
        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(Theme.PANEL_2);
        inner.add(head, BorderLayout.NORTH);
        inner.add(scroll, BorderLayout.CENTER);
        inner.setPreferredSize(new Dimension(BODY_WIDTH, 10));
        body.add(inner, BorderLayout.WEST);
        add(body, BorderLayout.CENTER);
        slide.setRepeats(true);
        list.add(empty);
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean open, boolean animate) {
        this.open = open;
        tab.repaint();
        from = shown;
        to = open ? 1 : 0;
        if (!animate) {
            shown = to;
            slide.stop();
            revalidate();
            repaint();
            return;
        }
        slideStart = System.currentTimeMillis();
        slide.start();
    }

    private void tick() {
        float t = Math.min(1, (System.currentTimeMillis() - slideStart) / (float) MS);
        shown = from + (to - from) * t;
        revalidate();
        repaint();
        if (t >= 1) slide.stop();
    }

    private static float eased(float v) {
        return 1 - (1 - v) * (1 - v);
    }

    /** The cards now (Swing thread): kept by name, new ones added, gone ones taken out. */
    void setCards(List<FleetCards.Card> cards) {
        count = cards.size();
        boolean changed = cards.size() != views.size();
        List<String> names = new ArrayList<>();
        for (FleetCards.Card c : cards) {
            names.add(c.name());
            CardView v = views.get(c.name());
            if (v == null) {
                v = new CardView(c.name());
                views.put(c.name(), v);
                changed = true;
            }
            v.set(c);
        }
        if (views.keySet().retainAll(names)) changed = true;
        if (changed || !new ArrayList<>(views.keySet()).equals(names)) {
            list.removeAll();
            if (cards.isEmpty()) list.add(empty);
            for (String n : names) {
                list.add(views.get(n));
                list.add(javax.swing.Box.createVerticalStrut(8));
            }
            list.revalidate();
        }
        repaint();
    }

    /** For tests and pictures: the card shown for this phone, or null. */
    JComponent cardOf(String phone) {
        return views.get(phone);
    }

    static Color kindColor(MapView.Kind k) {
        return switch (k) {
            case ACTIVE -> new Color(0x4fc3f7);
            case ASLEEP -> new Color(0x9fa8da);
            case OVERWATCH -> new Color(0x4db6ac);
            case LOST -> new Color(0xef5350);
            case UNKNOWN -> Theme.MUTED;
        };
    }

    // ------------------------------------------------------------------ one phone

    private final class CardView extends JPanel {
        private final String phone;
        private FleetCards.Card card;
        private int interval = 5;
        private boolean intervalChosen;
        private final JButton standby = button("STANDBY", "Camera off, back in about 2 s at CONNECT");
        private final JButton sleep = button("DEEP SLEEP", "Sleeps; checks in at CHECK EVERY");
        private final JButton watch = button("OVERWATCH", "Sleeps with the camera watching for motion");
        private final JButton every = button("", "How often it checks in. Changes a sleeping phone at its next check-in");
        private final JButton clips = button("CLIPS", "Its overwatch clips");
        private final JButton cancel = button("CANCEL", "Drop the queued mode");

        CardView(String phone) {
            super(new BorderLayout(0, 6));
            this.phone = phone;
            setBackground(Theme.PANEL);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.LINE),
                    BorderFactory.createEmptyBorder(0, 0, 8, 0)));
            setAlignmentX(LEFT_ALIGNMENT);
            add(new Face(), BorderLayout.CENTER);
            JPanel buttons = new JPanel(new GridLayout(2, 3, 4, 4));
            buttons.setOpaque(false);
            buttons.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 8));
            buttons.add(standby);
            buttons.add(sleep);
            buttons.add(watch);
            buttons.add(every);
            buttons.add(clips);
            buttons.add(cancel);
            add(buttons, BorderLayout.SOUTH);
            standby.addActionListener(e -> actions.mode(phone, ControlLink.POWER_STANDBY, interval));
            sleep.addActionListener(e -> actions.mode(phone, ControlLink.POWER_DEEP_SLEEP, interval));
            watch.addActionListener(e -> actions.mode(phone, ControlLink.POWER_OVERWATCH, interval));
            clips.addActionListener(e -> actions.clips(phone));
            cancel.addActionListener(e -> actions.cancel(phone));
            every.addActionListener(e -> nextInterval());
            setEveryText();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(BODY_WIDTH - 16, getPreferredSize().height);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(BODY_WIDTH - 16, FACE_HEIGHT + 6 + 2 * 26 + 4 + 9);
        }

        private JButton button(String text, String tip) {
            JButton b = Theme.secondaryButton(text);
            b.setFont(Theme.headingFont(10f));
            b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.ACCENT, 1),
                    BorderFactory.createEmptyBorder(2, 1, 2, 1)));
            b.setPreferredSize(new Dimension(80, 26));
            b.setToolTipText(tip);
            b.setFocusable(false);
            return b;
        }

        private void nextInterval() {
            int i = 0;
            while (i < INTERVALS.length && INTERVALS[i] != interval) i++;
            interval = INTERVALS[(i + 1) % INTERVALS.length];
            intervalChosen = true;
            setEveryText();
            FleetCards.Card c = card;
            // a sleeping phone gets the new interval at its next check-in; an awake one keeps it for the next press
            if (c != null && c.note() == null && ("ASLEEP".equals(c.badge()) || "OVERWATCH".equals(c.badge()))) {
                actions.mode(phone, "OVERWATCH".equals(c.badge()) ? ControlLink.POWER_OVERWATCH : ControlLink.POWER_DEEP_SLEEP, interval);
            }
        }

        private void setEveryText() {
            every.setText("EVERY " + (SleepCheck.isAlways(interval) ? "0" : interval) + "M");
        }

        void set(FleetCards.Card c) {
            card = c;
            if (!intervalChosen && c.intervalMin() > 0) {
                for (int v : INTERVALS) if (v == c.intervalMin()) interval = v;
                setEveryText();
            }
            boolean usable = c.note() == null;
            String now = c.queued() != null ? c.queued() : c.badge();
            mark(standby, usable, "STANDBY".equals(now));
            mark(sleep, usable, "DEEP SLEEP".equals(now) || "ASLEEP".equals(now));
            mark(watch, usable, "OVERWATCH".equals(now));
            every.setEnabled(usable);
            clips.setEnabled(c.events() > 0);
            cancel.setEnabled(c.queued() != null);
            repaint();
        }

        /** The mode it is in (or is queued for) stands out: lit red, the others plain. */
        private void mark(JButton b, boolean enabled, boolean current) {
            b.setEnabled(enabled);
            b.setBackground(current ? Theme.ACCENT : Theme.BG);
            b.setForeground(current ? Theme.BG : Theme.ACCENT);
        }

        /** The painted top of the card. */
        private final class Face extends JComponent {
            Face() {
                setPreferredSize(new Dimension(BODY_WIDTH - 16, FACE_HEIGHT));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                FleetCards.Card c = card;
                if (c == null) return;
                Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
                try {
                    paintFace(g, c, getWidth());
                } finally {
                    g.dispose();
                }
            }
        }
    }

    // ------------------------------------------------------------------ the painting

    static void paintFace(Graphics2D g, FleetCards.Card c, int w) {
        Color kc = kindColor(c.kind());
        g.setColor(kc);
        g.fillRect(0, 0, 4, FACE_HEIGHT);
        // the sign, the name, the badge
        MapView.paintNetSign(g, c.kind(), kc, 22, 20);
        g.setFont(Theme.headingFont(14f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Theme.TEXT);
        g.drawString(c.name(), 38, 20 + fm.getAscent() / 2 - 2);
        int nameEnd = 38 + fm.stringWidth(c.name());
        if (c.virtual()) {
            g.setFont(Theme.headingFont(9f));
            g.setColor(Theme.WARN);
            g.drawString("VIRTUAL", nameEnd + 6, 20 + g.getFontMetrics().getAscent() / 2 - 1);
        }
        g.setFont(Theme.headingFont(10f));
        FontMetrics bf = g.getFontMetrics();
        String badge = c.badge();
        int bw = bf.stringWidth(badge) + 14, bx = w - bw - 10;
        g.setColor(Theme.alpha(kc, 50));
        g.fillRoundRect(bx, 11, bw, 18, 9, 9);
        g.setColor(kc);
        g.drawRoundRect(bx, 11, bw, 18, 9, 9);
        g.drawString(badge, bx + 7, 20 + bf.getAscent() / 2 - 1);

        // the two gauges, the ring at the right
        int gx = 14, gw = w - 14 - 78;
        double cell = FleetCards.cellFill(c.cellVolts());
        String cellText = Double.isNaN(c.cellVolts()) ? "-" : FleetCards.cell(c.cellVolts()) + "/cell"
                + (c.cellAgeMs() > 60_000 ? " · " + FleetCards.ago(c.cellAgeMs()).replace(" ago", "") : "");
        gauge(g, gx, 44, gw, "DRONE", cell, cellText);
        double phone = c.phonePercent() < 0 ? Double.NaN : c.phonePercent() / 100.0;
        gauge(g, gx, 76, gw, "PHONE", phone, c.phonePercent() < 0 ? "-" : c.phonePercent() + " %" + (c.charging() ? " CHARGING" : ""));
        ring(g, w - 40, 58, 23, c);

        // signal, GPS, heard
        int y = 114;
        bars(g, gx, y, c.signal());
        g.setFont(Theme.font(java.awt.Font.PLAIN, 11f));
        g.setColor(Theme.MUTED);
        FontMetrics sf = g.getFontMetrics();
        g.drawString("GPS " + FleetCards.gps(c.gpsFix(), c.sats()), gx + 26, y);
        String heard = "HEARD " + FleetCards.ago(c.heardAgoMs()).toUpperCase(Locale.ROOT);
        g.drawString(heard, w - 10 - sf.stringWidth(heard), y);
        // temperatures, events
        y += 17;
        g.drawString("PHONE " + FleetCards.tempDeci(c.phoneTempDeci()) + "   FC " + FleetCards.tempC(c.fcTempC()), gx, y);
        String ev = c.events() == 0 ? "NO EVENTS" : c.events() + (c.events() == 1 ? " EVENT " : " EVENTS ")
                + new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(c.lastEventWall()));
        if (c.events() > 0) g.setColor(MapView.ALERT);
        g.drawString(ev, w - 10 - sf.stringWidth(ev), y);
        // queued, or why not
        y += 17;
        g.setFont(Theme.headingFont(10f));
        if (c.queued() != null) {
            g.setColor(Theme.WARN);
            g.drawString("QUEUED: " + c.queued() + " (AT CHECK-IN)", gx, y);
        } else if (c.note() != null) {
            g.setColor(Theme.MUTED);
            g.drawString(c.note().toUpperCase(Locale.ROOT), gx, y);
        }
    }

    /** A segmented gauge: its label above, ten blocks green to red by how full, its text on the right. */
    private static void gauge(Graphics2D g, int x, int y, int w, String label, double fill, String text) {
        g.setFont(Theme.headingFont(9f));
        g.setColor(Theme.MUTED);
        g.drawString(label, x, y - 4);
        g.setFont(Theme.headingFont(11f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Double.isNaN(fill) ? Theme.MUTED : Theme.TEXT);
        g.drawString(text, x + w - fm.stringWidth(text), y - 4);
        int n = 10, gap = 2, bw = (w - gap * (n - 1)) / n;
        Color on = Double.isNaN(fill) ? Theme.LINE : fill < 0.2 ? Theme.BAD : fill < 0.45 ? Theme.WARN : Theme.OK;
        int lit = Double.isNaN(fill) ? 0 : (int) Math.ceil(fill * n);
        for (int i = 0; i < n; i++) {
            g.setColor(i < lit ? on : Theme.PANEL_2);
            g.fillRect(x + i * (bw + gap), y, bw, 9);
            g.setColor(i < lit ? on.darker() : Theme.LINE);
            g.drawRect(x + i * (bw + gap), y, bw - 1, 8);
        }
    }

    /** The ring running down to the next check-in, the countdown in it, NEXT under it. */
    private static void ring(Graphics2D g, int cx, int cy, int r, FleetCards.Card c) {
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.PANEL_2);
        g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        double fill = FleetCards.ringFill(c.nextCheckMs(), c.intervalMin());
        Color kc = kindColor(c.kind());
        if (fill > 0) {
            g.setColor(kc);
            g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 90, -360 * fill, Arc2D.OPEN));
        }
        g.setStroke(new BasicStroke(1f));
        String t = FleetCards.countdown(c.nextCheckMs());
        g.setFont(Theme.headingFont(t.length() > 4 ? 10f : 12f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c.nextCheckMs() < 0 ? Theme.MUTED : Theme.TEXT);
        g.drawString(t, cx - fm.stringWidth(t) / 2, cy + fm.getAscent() / 2 - 2);
        g.setFont(Theme.headingFont(8f));
        fm = g.getFontMetrics();
        g.setColor(Theme.MUTED);
        String next = "NEXT CHECK";
        g.drawString(next, cx - fm.stringWidth(next) / 2, cy + r + 14);
    }

    /** Four signal bars, lit up to the phone's level; all dim when unknown. */
    private static void bars(Graphics2D g, int x, int baseline, int level) {
        for (int i = 0; i < 4; i++) {
            int h = 4 + i * 3;
            g.setColor(level < 0 ? Theme.LINE : i < level ? Theme.TEXT : Theme.LINE);
            g.fillRect(x + i * 5, baseline - h, 3, h);
        }
    }

    // ------------------------------------------------------------------ the tab

    /** The FLEET tab: its title written upwards, a chevron pointing the way it goes; lit while out. */
    private final class Tab extends JButton {
        Tab() {
            super("FLEET");
            setPreferredSize(new Dimension(TAB_WIDTH, 10));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight();
                boolean hot = getModel().isRollover() || open;
                g.setColor(hot ? Theme.PANEL_2 : Theme.PANEL);
                g.fillRect(0, 0, w, h);
                g.setColor(hot ? Theme.ACCENT : Theme.LINE);
                g.drawRect(0, 0, w - 1, h - 1);
                g.setFont(Theme.headingFont(12f));
                FontMetrics fm = g.getFontMetrics();
                String text = getText();
                int tw = fm.stringWidth(text);
                AffineTransform saved = g.getTransform();
                g.rotate(Math.PI / 2, w / 2.0, h / 2.0); // read top to bottom, as on the right edge
                g.setColor(hot ? Theme.TEXT : Theme.MUTED);
                g.drawString(text, w / 2 - tw / 2, h / 2 + fm.getAscent() / 2 - 1);
                g.setTransform(saved);
                g.setColor(Theme.ACCENT);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = w / 2, cy = 14;
                if (open) { // > : it folds back to the right
                    g.drawLine(cx - 3, cy - 4, cx + 2, cy);
                    g.drawLine(cx + 2, cy, cx - 3, cy + 4);
                } else { // < : it slides out to the left
                    g.drawLine(cx + 3, cy - 4, cx - 2, cy);
                    g.drawLine(cx - 2, cy, cx + 3, cy + 4);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
