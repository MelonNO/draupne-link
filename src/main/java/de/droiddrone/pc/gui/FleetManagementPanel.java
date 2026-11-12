package de.droiddrone.pc.gui;

import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.ControlLink;

import javax.swing.BorderFactory;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FLEET MANAGEMENT (1.0.52, the pilot; made compact the same day: "it must be possible to view 10 phones without
 * scrolling"): a thin FLEET tab at the map window's right edge; pressed, a list slides out - one slim row per phone:
 * its sign (the mode, as confirmed), its name, a queued mode as a yellow arrow to its sign with how long ago it was
 * queued, the drone's volts per cell and the phone's battery with thin gauges, when it was heard, and a ring running
 * down to its next check-in. A click selects a row; one strip at the bottom shows that phone's details (signal, GPS,
 * temperatures, events, why not) and its modes - PILOTED, STANDBY, OVERWATCH, DEEP SLEEP, the one it is in with grey
 * text (pressed: it shakes, not possible), a queued one yellow (pressed again: dropped) - then CLIPS and how often it
 * checks in, a list (2026-11-10, the pilot's layout). Not connected the modes are queued (FleetManager: sent when the
 * phone next listens); the phone this PC flies takes them as the LINK card's buttons do. Rows shrink to
 * {@link #MIN_ROW} before the list scrolls. The cards come once a second from the main window ({@link #setCards});
 * painting them is all this does on the Swing thread.
 */
final class FleetManagementPanel extends JPanel {
    /** What the buttons do (the main window). intervalMin as SleepCheck has it (255: always listening). */
    interface Actions {
        void mode(String phone, int mode, int intervalMin);

        void clips(String phone);

        void cancel(String phone);

        /** A row picked by a click: its drone stands out on the map. */
        default void picked(String phone) {
        }

        /** PILOT UAV: fly the selected drone, as the map's right-click does. */
        default void connect(String phone) {
        }

        /** Right-click on a drone not heard from (LOST, UNKNOWN): it is down and gone (1.0.52). */
        default void markCrashed(String phone) {
        }

        /** Right-click on a CRASHED drone: not crashed after all. */
        default void clearCrashed(String phone) {
        }
    }

    /** What a row's right-click offers for this state: "Mark CRASHED", "Clear CRASHED", or null (nothing). */
    static String crashChoice(MapView.Kind kind) {
        if (kind == MapView.Kind.LOST || kind == MapView.Kind.UNKNOWN) return "Mark CRASHED";
        if (kind == MapView.Kind.CRASHED) return "Clear CRASHED";
        return null;
    }

    static final int BODY_WIDTH = 340;
    /** A row's height when there is room, and the least it shrinks to before the list scrolls. */
    static final int ROW = 30, MIN_ROW = 21;
    private static final int TAB_WIDTH = 26;
    private static final int MS = 220;
    /** CHECK EVERY's choices, as the LINK card has them: 0 (always listening), 1, 2, 5 min. */
    private static final int[] INTERVALS = {SleepCheck.ALWAYS, 1, 2, 5};
    // a row's columns (x from its left edge); the header names them at the same places
    private static final int NAME_X = 28, QUEUED_X = 124, DRONE_X = 166, PHONE_X = 216, HEARD_X = 254, NEXT_CX = 302;

    private final Actions actions;
    private final JButton tab = new Tab();
    private final Rows list = new Rows();
    private final JScrollPane scroll;
    private final JComponent empty;
    private final Map<String, RowView> views = new LinkedHashMap<>();
    /** CHECK EVERY per phone, chosen or as it slept: DEEP SLEEP's and OVERWATCH's apart (1.0.56: their defaults differ). */
    private final Map<String, Integer> intervals = new HashMap<>();
    private final Map<String, Integer> overwatchIntervals = new HashMap<>();
    private final Detail detail = new Detail();
    private final JButton fly = button("PILOTED", "Connect and fly it (a sleeping one at its next check)");
    private final JButton standby = button("STANDBY", "Camera off, back in about 2 s at PILOT UAV");
    private final JButton watch = button("OVERWATCH", "Sleeps with the camera watching for motion");
    private final JButton sleep = button("DEEP SLEEP", "Sleeps; checks in at CHECK EVERY");
    /** How often it checks in: a list, not a button (2026-11-10, the pilot). */
    private final javax.swing.JComboBox<String> every = new javax.swing.JComboBox<>();
    private final JButton clips = button("CLIPS", "Its overwatch clips");
    private boolean fillingEvery;
    private final Timer slide = new Timer(15, e -> tick());
    private String selected;
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
        list.setBackground(Theme.PANEL_2);
        scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(MIN_ROW);
        // no blit scrolling inside a clipped parent (see MapToolsPanel)
        scroll.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
        scroll.getViewport().setBackground(Theme.PANEL_2);
        JPanel head = new JPanel(new BorderLayout());
        this.head = head;
        head.setBackground(Theme.PANEL_2);
        head.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        head.add(new JComponent() {
            {
                setPreferredSize(new Dimension(10, 30));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
                try {
                    g.setFont(Theme.headingFont(13f));
                    g.setColor(Theme.ACCENT);
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString("FLEET MANAGEMENT", 2, fm.getAscent());
                    String n = count + (count == 1 ? " PHONE" : " PHONES");
                    g.setFont(Theme.headingFont(11f));
                    g.setColor(Theme.MUTED);
                    g.drawString(n, getWidth() - 2 - g.getFontMetrics().stringWidth(n), fm.getAscent());
                    // the columns' names, over the rows
                    g.setFont(Theme.headingFont(8f));
                    int y = getHeight() - 3;
                    g.drawString("PHONE", NAME_X, y);
                    g.drawString("DRONE", DRONE_X, y);
                    g.drawString("BATT", PHONE_X, y);
                    g.drawString("HEARD", HEARD_X, y);
                    String next = "NEXT";
                    g.drawString(next, NEXT_CX - g.getFontMetrics().stringWidth(next) / 2, y);
                } finally {
                    g.dispose();
                }
            }
        }, BorderLayout.CENTER);
        empty = Theme.label("No phones yet.", Theme.font(java.awt.Font.PLAIN, 12f), Theme.MUTED);
        empty.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
        // 2026-11-10 (the pilot): four modes at the top - PILOTED, STANDBY, OVERWATCH, DEEP SLEEP - then CLIPS and the
        // check-in list
        JPanel modes = new JPanel(new GridLayout(1, 4, 4, 0));
        modes.setOpaque(false);
        modes.add(fly);
        modes.add(standby);
        modes.add(watch);
        modes.add(sleep);
        JPanel more = new JPanel(new GridLayout(1, 2, 4, 0));
        more.setOpaque(false);
        more.add(clips);
        TacticalCombo.install(every);
        for (int i : INTERVALS) every.addItem(everyText(i));
        every.setFont(Theme.headingFont(10f));
        every.setToolTipText("How often it checks in. Changes a sleeping phone at its next check-in");
        every.setFocusable(false);
        more.add(every);
        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 3));
        buttons.setOpaque(false);
        buttons.add(modes);
        buttons.add(more);
        fly.addActionListener(e -> pressed(fly, -1));
        standby.addActionListener(e -> pressed(standby, ControlLink.POWER_STANDBY));
        sleep.addActionListener(e -> pressed(sleep, ControlLink.POWER_DEEP_SLEEP));
        watch.addActionListener(e -> pressed(watch, ControlLink.POWER_OVERWATCH));
        clips.addActionListener(e -> {
            if (selected == null) return;
            FleetCards.Card c = selectedCard();
            if (c != null && c.events() == 0) NotPossible.say(clips, "NO CLIPS", "No overwatch clips of " + selected + " yet.");
            else actions.clips(selected);
        });
        every.addActionListener(e -> {
            if (!fillingEvery) chooseInterval(every.getSelectedIndex());
        });
        JPanel strip = new JPanel(new BorderLayout(0, 4));
        strip.setBackground(Theme.PANEL);
        strip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE),
                BorderFactory.createEmptyBorder(5, 8, 6, 8)));
        strip.add(detail, BorderLayout.CENTER);
        strip.add(buttons, BorderLayout.SOUTH);
        actionStrip = strip;
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
        inner.add(strip, BorderLayout.SOUTH);
        inner.setPreferredSize(new Dimension(BODY_WIDTH, 10));
        body.add(inner, BorderLayout.WEST);
        add(body, BorderLayout.CENTER);
        slide.setRepeats(true);
        list.add(empty);
        refreshStrip();
    }

    boolean isOpen() {
        return open;
    }

    private JPanel head;
    private JComponent demoStrip;

    /** DEMO mode's strip at the top of the list (its scenario's button), or null to take it away. */
    void setDemoStrip(JComponent strip) {
        if (strip == demoStrip) return;
        if (demoStrip != null) head.remove(demoStrip);
        demoStrip = strip;
        if (strip != null) head.add(strip, BorderLayout.NORTH);
        head.revalidate();
        head.repaint();
    }

    JComponent demoStrip() {
        return demoStrip;
    }

    /** The selected phone's details and buttons (the tour points at it). */
    private JPanel actionStrip;

    JComponent tourTab() {
        return tab;
    }

    JComponent tourRows() {
        return scroll;
    }

    JComponent tourStrip() {
        return actionStrip;
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

    /** The cards now (Swing thread): rows kept by name, new ones added, gone ones taken out. */
    void setCards(List<FleetCards.Card> cards) {
        count = cards.size();
        boolean changed = cards.size() != views.size();
        List<String> names = new ArrayList<>();
        for (FleetCards.Card c : cards) {
            names.add(c.name());
            RowView v = views.get(c.name());
            if (v == null) {
                v = new RowView(c.name());
                views.put(c.name(), v);
                changed = true;
            }
            v.card = c;
            // as it sleeps now (an awake phone's own number is only its last one: the mode's default goes instead)
            Map<String, Integer> asItSleeps = "OVERWATCH".equals(c.badge()) && c.queued() == null ? overwatchIntervals
                    : "ASLEEP".equals(c.badge()) && c.queued() == null ? intervals : null;
            if (asItSleeps != null && !asItSleeps.containsKey(c.name()) && c.intervalMin() > 0) {
                for (int i : INTERVALS) if (i == c.intervalMin()) asItSleeps.put(c.name(), i);
            }
        }
        if (views.keySet().retainAll(names)) changed = true;
        if (selected == null || !views.containsKey(selected)) selected = names.isEmpty() ? null : names.get(0);
        if (changed || !new ArrayList<>(views.keySet()).equals(names)) {
            list.removeAll();
            if (cards.isEmpty()) list.add(empty);
            for (String n : names) list.add(views.get(n));
            list.revalidate();
        }
        refreshStrip();
        repaint();
    }

    /** For tests and pictures: the row shown for this phone, or null. */
    JComponent cardOf(String phone) {
        return views.get(phone);
    }

    /** For tests: the list's scroll pane. */
    JScrollPane listScroll() {
        return scroll;
    }

    String selected() {
        return selected;
    }

    /** The row's own name for a phone, any case; null when it has none. */
    String rowName(String phone) {
        for (String n : views.keySet()) if (n.equalsIgnoreCase(phone)) return n;
        return null;
    }

    void select(String phone) {
        if (!views.containsKey(phone)) return;
        selected = phone;
        refreshStrip();
        list.repaint();
    }

    private FleetCards.Card selectedCard() {
        RowView v = selected == null ? null : views.get(selected);
        return v == null ? null : v.card;
    }

    /** The selected phone's CHECK EVERY for this mode: chosen, as it sleeps, else the mode's default. */
    private int interval(int mode) {
        boolean ow = mode == ControlLink.POWER_OVERWATCH;
        int def = PowerController.defaultInterval(mode);
        return selected == null ? def : (ow ? overwatchIntervals : intervals).getOrDefault(selected, def);
    }

    /** The mode EVERY shows and sets: OVERWATCH while the phone overwatches (or is queued to), else DEEP SLEEP. */
    private int everyMode() {
        FleetCards.Card c = selectedCard();
        String now = c == null ? null : c.queued() != null ? c.queued() : c.badge();
        return "OVERWATCH".equals(now) ? ControlLink.POWER_OVERWATCH : ControlLink.POWER_DEEP_SLEEP;
    }

    /** EVERY's words: "CONSTANT" (1.0.56: was "EVERY 0M"), else "EVERY 5M". */
    static String everyText(int intervalMin) {
        return SleepCheck.isAlways(intervalMin) ? "CONSTANT" : "EVERY " + intervalMin + "M";
    }

    /**
     * A mode button pressed (mode -1: PILOTED). The one it is in: not possible, it shakes and says so. The queued one:
     * dropped. Else asked for (queued, or at once for the phone this PC flies).
     */
    private void pressed(JButton b, int mode) {
        if (selected == null) return;
        FleetCards.Card c = selectedCard();
        if (c == null) return;
        if (c.note() != null) {
            NotPossible.say(b, "NOT NOW", c.note());
            return;
        }
        if (isCurrent(c, mode)) {
            String word = mode < 0 ? "PILOTED" : PowerController.modeWord(mode);
            NotPossible.say(b, "ALREADY " + word, selected + " is " + (mode < 0 ? "flown from this PC" : "in " + word) + " already.");
            return;
        }
        if (mode >= 0 && isQueued(c, mode)) {
            actions.cancel(selected);
            return;
        }
        if (mode < 0) actions.connect(selected);
        else actions.mode(selected, mode, interval(mode));
    }

    /** The mode it is in, as confirmed: PILOTED (-1) while this PC flies it, else its badge's. */
    static boolean isCurrent(FleetCards.Card c, int mode) {
        if (c == null) return false;
        return switch (mode) {
            case -1 -> "CONNECTED".equals(c.badge());
            case ControlLink.POWER_STANDBY -> "STANDBY".equals(c.badge());
            case ControlLink.POWER_DEEP_SLEEP -> "ASLEEP".equals(c.badge()) || "DEEP SLEEP".equals(c.badge());
            case ControlLink.POWER_OVERWATCH -> "OVERWATCH".equals(c.badge());
            default -> false;
        };
    }

    static boolean isQueued(FleetCards.Card c, int mode) {
        return c != null && c.queued() != null && c.queued().equals(de.droiddrone.pc.FleetManager.modeName(mode));
    }

    /** The check-in list's choice (the index into INTERVALS). */
    private void chooseInterval(int index) {
        if (selected == null || index < 0 || index >= INTERVALS.length) return;
        int mode = everyMode();
        int next = INTERVALS[index];
        if (next == interval(mode)) return;
        (mode == ControlLink.POWER_OVERWATCH ? overwatchIntervals : intervals).put(selected, next);
        refreshStrip();
        FleetCards.Card c = selectedCard();
        // a sleeping phone gets the new interval at its next check-in; an awake one keeps it for the next press
        if (c != null && c.note() == null && ("ASLEEP".equals(c.badge()) || "OVERWATCH".equals(c.badge()))) {
            actions.mode(selected, "OVERWATCH".equals(c.badge()) ? ControlLink.POWER_OVERWATCH : ControlLink.POWER_DEEP_SLEEP, next);
        }
    }

    /**
     * The strip follows the selected phone: the mode it is in with grey text, a queued one yellow, the others red; every
     * button stays pressable (a press that can't do anything shakes, 2026-11-10).
     */
    private void refreshStrip() {
        FleetCards.Card c = selectedCard();
        int at = 0, now = interval(everyMode());
        while (at < INTERVALS.length - 1 && INTERVALS[at] != now) at++;
        if (every.getSelectedIndex() != at) {
            fillingEvery = true;
            try {
                every.setSelectedIndex(at);
            } finally {
                fillingEvery = false;
            }
        }
        every.setEnabled(c != null && c.note() == null);
        mark(fly, c, -1);
        mark(standby, c, ControlLink.POWER_STANDBY);
        mark(watch, c, ControlLink.POWER_OVERWATCH);
        mark(sleep, c, ControlLink.POWER_DEEP_SLEEP);
        clips.setEnabled(c != null);
        clips.setForeground(c != null && c.events() > 0 ? Theme.ACCENT : Theme.MUTED);
        detail.repaint();
    }

    /** A mode button's look: grey text for the mode it is in, yellow for a queued one, red for the rest. */
    private static void mark(JButton b, FleetCards.Card c, int mode) {
        b.setEnabled(c != null);
        b.setBackground(Theme.BG);
        boolean current = isCurrent(c, mode), queued = mode >= 0 && isQueued(c, mode);
        b.setForeground(current ? Theme.MUTED : queued ? Theme.WARN : Theme.ACCENT);
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(current ? Theme.LINE : queued ? Theme.WARN : Theme.ACCENT, 1),
                BorderFactory.createEmptyBorder(2, 1, 2, 1)));
        String tip = current ? "It is in this mode" : queued ? "Queued: press again to drop it" : null;
        if (tip != null) b.setToolTipText(tip);
        else b.setToolTipText(mode < 0 ? "Connect and fly it (a sleeping one at its next check)"
                : mode == ControlLink.POWER_STANDBY ? "Camera off, back in about 2 s at PILOT UAV"
                : mode == ControlLink.POWER_OVERWATCH ? "Sleeps with the camera watching for motion" : "Sleeps; checks in at CHECK EVERY");
    }

    private static JButton button(String text, String tip) {
        JButton b = Theme.secondaryButton(text);
        b.setFont(Theme.headingFont(10f));
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.ACCENT, 1),
                BorderFactory.createEmptyBorder(2, 1, 2, 1)));
        b.setPreferredSize(new Dimension(80, 22));
        b.setToolTipText(tip);
        b.setFocusable(false);
        return b;
    }

    static Color kindColor(MapView.Kind k) {
        return MapView.kindColor(k); // the map's (2026-11-10: one set of signs and colours)
    }

    // ------------------------------------------------------------------ the list

    /**
     * The rows, one under the other: {@link #ROW} high each when there is room, all shrunk alike down to
     * {@link #MIN_ROW} when not; only past that does the list scroll.
     */
    private final class Rows extends JPanel implements javax.swing.Scrollable {
        Rows() {
            super(null);
            setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        }

        private int rows() {
            return getComponentCount() == 1 && getComponent(0) == empty ? 0 : getComponentCount();
        }

        private int rowHeight() {
            int n = rows();
            if (n == 0) return ROW;
            java.awt.Insets in = getInsets();
            int room = (getParent() instanceof javax.swing.JViewport vp ? vp.getHeight() : getHeight()) - in.top - in.bottom;
            return Math.max(MIN_ROW, Math.min(ROW, room / n));
        }

        @Override
        public void doLayout() {
            java.awt.Insets in = getInsets();
            int w = getWidth() - in.left - in.right;
            if (rows() == 0) {
                empty.setBounds(in.left, in.top, w, empty.getPreferredSize().height);
                return;
            }
            int h = rowHeight(), y = in.top;
            for (java.awt.Component c : getComponents()) {
                c.setBounds(in.left, y, w, h);
                y += h;
            }
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.Insets in = getInsets();
            int n = rows();
            int h = n == 0 ? empty.getPreferredSize().height : n * MIN_ROW;
            return new Dimension(BODY_WIDTH, h + in.top + in.bottom);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return MIN_ROW;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(MIN_ROW, visible.height - MIN_ROW);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        /** Fills the viewport while every row fits at its least height; taller than it (and scrolling) when not. */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof javax.swing.JViewport vp && getPreferredSize().height <= vp.getHeight();
        }
    }

    /** One phone's row; a click selects it. */
    private final class RowView extends JComponent {
        private final String phone;
        private FleetCards.Card card;

        RowView(String phone) {
            this.phone = phone;
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    select(RowView.this.phone);
                    actions.picked(RowView.this.phone);
                    if (e.isPopupTrigger()) crashMenu(e);
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (e.isPopupTrigger()) crashMenu(e); // Windows: the menu comes on release
                }
            });
        }

        /** Right-click: Mark CRASHED on a drone not heard from, Clear CRASHED on a crashed one. */
        private void crashMenu(java.awt.event.MouseEvent e) {
            FleetCards.Card c = card;
            String choice = c == null ? null : crashChoice(c.kind());
            if (choice == null) return;
            javax.swing.JPopupMenu menu = TacticalCombo.menu();
            menu.setBackground(Theme.PANEL_2);
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(choice + " on " + phone);
            item.addActionListener(a -> {
                if (c.kind() == MapView.Kind.CRASHED) actions.clearCrashed(phone);
                else actions.markCrashed(phone);
            });
            menu.add(item);
            menu.show(this, e.getX(), e.getY());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            FleetCards.Card c = card;
            if (c == null) return;
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                paintRow(g, c, getWidth(), getHeight(), phone.equals(selected));
            } finally {
                g.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ the painting

    /** One row: sign, name, queued mode, drone volts, phone battery, heard, the check-in ring. */
    static void paintRow(Graphics2D g, FleetCards.Card c, int w, int h, boolean selected) {
        Color kc = kindColor(c.kind());
        g.setColor(selected ? Theme.PANEL : Theme.PANEL_2);
        g.fillRect(0, 0, w, h);
        g.setColor(Theme.LINE);
        g.drawLine(0, h - 1, w, h - 1);
        g.setColor(selected ? Theme.ACCENT : kc);
        g.fillRect(0, 1, 3, h - 2);
        int cy = h / 2;
        // the sign, a little smaller than on the map
        AffineTransform saved = g.getTransform();
        double s = Math.min(0.8, (h - 6) / 18.0);
        g.translate(15, cy);
        g.scale(s, s);
        MapView.paintNetSign(g, c.kind(), c.blind() ? Theme.WARN : kc, 0, 0);
        if (c.blind()) MapView.paintBlind(g, 0, 0); // 1.0.64: overwatching, seeing nothing - the eye crossed out
        g.setTransform(saved);
        // the name; VIRTUAL as a small yellow V
        g.setFont(Theme.headingFont(h < 26 ? 11f : 12f));
        FontMetrics fm = g.getFontMetrics();
        int base = cy + fm.getAscent() / 2 - 2;
        String name = fit(c.name(), fm, QUEUED_X - NAME_X - (c.virtual() ? 12 : 2));
        g.setColor(Theme.TEXT);
        g.drawString(name, NAME_X, base);
        if (c.virtual()) {
            g.setFont(Theme.headingFont(8f));
            g.setColor(Theme.WARN);
            g.drawString("V", NAME_X + fm.stringWidth(name) + 3, base);
        }
        // the queued mode: a yellow arrow to its sign, and how long ago it was queued (2026-11-10, the pilot)
        MapView.Kind q = c.queuedKind();
        if (q != null) {
            g.setColor(Theme.WARN);
            java.awt.geom.Path2D arrow = new java.awt.geom.Path2D.Double();
            arrow.moveTo(QUEUED_X, cy - 4);
            arrow.lineTo(QUEUED_X + 6, cy);
            arrow.lineTo(QUEUED_X, cy + 4);
            arrow.closePath();
            g.fill(arrow);
            AffineTransform before = g.getTransform();
            g.translate(QUEUED_X + 13, cy);
            g.scale(s * 0.75, s * 0.75);
            MapView.paintNetSign(g, q, kindColor(q), 0, 0);
            g.setTransform(before);
            g.setFont(Theme.headingFont(8f));
            g.setColor(Theme.WARN);
            g.drawString(FleetCards.queuedAgo(c.queuedAgoMs()), QUEUED_X + 20, cy + g.getFontMetrics().getAscent() / 2 - 1);
        }
        // the drone's volts per cell and the phone's battery: a number over a thin gauge
        g.setFont(Theme.headingFont(10f));
        String cell = Double.isNaN(c.cellVolts()) ? "-" : String.format(Locale.ROOT, "%.2fV", c.cellVolts());
        mini(g, DRONE_X, cy, 42, cell, FleetCards.cellFill(c.cellVolts()));
        String batt = c.phonePercent() < 0 ? "-" : c.phonePercent() + "%" + (c.charging() ? "+" : "");
        mini(g, PHONE_X, cy, 34, batt, c.phonePercent() < 0 ? Double.NaN : c.phonePercent() / 100.0);
        g.setFont(Theme.headingFont(10f));
        g.setColor(Theme.MUTED);
        g.drawString(heard(c.heardAgoMs()), HEARD_X, cy + g.getFontMetrics().getAscent() / 2 - 1);
        ring(g, NEXT_CX, cy, Math.max(8, Math.min(11, h / 2 - 2)), c);
    }

    /** A text cut to its room, with "." when cut. */
    private static String fit(String s, FontMetrics fm, int room) {
        if (fm.stringWidth(s) <= room) return s;
        while (s.length() > 1 && fm.stringWidth(s + ".") > room) s = s.substring(0, s.length() - 1);
        return s + ".";
    }

    /** A queued mode in a word that fits a row. */
    static String shortMode(String mode) {
        return switch (mode) {
            case "STANDBY" -> "STBY";
            case "DEEP SLEEP" -> "SLEEP";
            case "OVERWATCH" -> "WATCH";
            default -> mode;
        };
    }

    /** "20s", "12m", "3h", "2d", or "-". */
    static String heard(long ms) {
        if (ms < 0) return "-";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        if (s < 86_400) return s / 3600 + "h";
        return s / 86_400 + "d";
    }

    /** A number with a thin gauge under it, green to red by how full; grey when unknown. */
    private static void mini(Graphics2D g, int x, int cy, int w, String text, double fill) {
        FontMetrics fm = g.getFontMetrics();
        Color on = Double.isNaN(fill) ? Theme.LINE : fill < 0.2 ? Theme.BAD : fill < 0.45 ? Theme.WARN : Theme.OK;
        g.setColor(Double.isNaN(fill) ? Theme.MUTED : Theme.TEXT);
        g.drawString(text, x, cy + fm.getAscent() / 2 - 3);
        int y = cy + fm.getAscent() / 2;
        g.setColor(Theme.LINE);
        g.fillRect(x, y, w, 3);
        if (!Double.isNaN(fill)) {
            g.setColor(on);
            g.fillRect(x, y, (int) Math.round(w * fill), 3);
        }
    }

    /** The ring running down to the next check-in, the countdown in it; full with a dot while always listening. */
    private static void ring(Graphics2D g, int cx, int cy, int r, FleetCards.Card c) {
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.LINE);
        g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        double fill = FleetCards.ringFill(c.nextCheckMs(), c.intervalMin());
        Color kc = kindColor(c.kind());
        if (fill > 0) {
            g.setColor(kc);
            g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 90, -360 * fill, Arc2D.OPEN));
        }
        g.setStroke(new BasicStroke(1f));
        if (c.nextCheckMs() == FleetCards.LISTENING) {
            g.setColor(kc);
            g.fill(new Ellipse2D.Double(cx - 2.5, cy - 2.5, 5, 5));
            return;
        }
        String t = FleetCards.countdown(c.nextCheckMs());
        if (t.startsWith("0:")) t = t.substring(1); // ":40": room in the ring
        g.setFont(Theme.headingFont(t.length() > 3 ? 7f : 8f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c.nextCheckMs() < 0 ? Theme.MUTED : Theme.TEXT);
        g.drawString(t, cx - fm.stringWidth(t) / 2, cy + fm.getAscent() / 2 - 1);
    }

    /** Four signal bars, lit up to the phone's level; all dim when unknown. */
    private static void bars(Graphics2D g, int x, int baseline, int level) {
        for (int i = 0; i < 4; i++) {
            int h = 3 + i * 3;
            g.setColor(level < 0 ? Theme.LINE : i < level ? Theme.TEXT : Theme.LINE);
            g.fillRect(x + i * 4, baseline - h, 3, h);
        }
    }

    /** The selected phone's details over the buttons: name and mode (queued, or why not), then its figures. */
    private final class Detail extends JComponent {
        Detail() {
            setPreferredSize(new Dimension(10, 32));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                paintDetail(g, selectedCard(), getWidth());
            } finally {
                g.dispose();
            }
        }
    }

    static void paintDetail(Graphics2D g, FleetCards.Card c, int w) {
        if (c == null) {
            g.setFont(Theme.font(java.awt.Font.PLAIN, 11f));
            g.setColor(Theme.MUTED);
            g.drawString("Click a phone.", 0, 14);
            return;
        }
        g.setFont(Theme.headingFont(12f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Theme.TEXT);
        g.drawString(c.name(), 0, fm.getAscent());
        int x = fm.stringWidth(c.name()) + 8;
        g.setFont(Theme.headingFont(10f));
        FontMetrics bf = g.getFontMetrics();
        // 1.0.64: "OVERWATCH - BLIND" in the warning colour while its camera gives the motion detection no pictures
        String badge = c.badgeText();
        g.setColor(c.blind() ? Theme.WARN : kindColor(c.kind()));
        g.drawString(badge, x, fm.getAscent());
        x += bf.stringWidth(badge) + 10;
        String right = c.note() != null ? c.note().toUpperCase(Locale.ROOT)
                : c.queued() != null ? "QUEUED: " + c.queued() + (c.queuedAgoMs() >= 0 ? " " + FleetCards.ago(c.queuedAgoMs()).toUpperCase(Locale.ROOT) : "")
                : c.nextCheckMs() == FleetCards.LISTENING ? "LISTENING"
                : c.nextCheckMs() >= 0 ? "NEXT CHECK " + FleetCards.countdown(c.nextCheckMs()) : "";
        right = fit(right, bf, w - x);
        g.setColor(c.note() == null && c.queued() != null ? Theme.WARN : Theme.MUTED);
        g.drawString(right, w - bf.stringWidth(right), fm.getAscent());
        // signal, GPS, temperatures, events
        int y = fm.getAscent() + 16;
        bars(g, 0, y, c.signal());
        g.setFont(Theme.font(java.awt.Font.PLAIN, 11f));
        FontMetrics sf = g.getFontMetrics();
        g.setColor(Theme.MUTED);
        g.drawString("GPS " + FleetCards.gps(c.gpsFix(), c.sats()) + "   " + FleetCards.tempDeci(c.phoneTempDeci())
                + " / FC " + FleetCards.tempC(c.fcTempC()), 22, y);
        String ev = c.events() == 0 ? "NO EVENTS" : c.events() + (c.events() == 1 ? " EVENT " : " EVENTS ")
                + new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(c.lastEventWall()));
        if (c.events() > 0) g.setColor(MapView.ALERT);
        g.drawString(ev, w - sf.stringWidth(ev), y);
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
