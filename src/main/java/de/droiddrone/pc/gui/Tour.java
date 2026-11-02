package de.droiddrone.pc.gui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The tour of the window, offered once the first-time setup is finished and under SETUP any time: one control at a
 * time is lit while the rest dims (and is dead to the mouse), with a notice in the style of the locked-button notices
 * saying what it does (NEXT / EXIT TOUR). It walks the main window (MENU and each of its slide-out buttons, the
 * controller card's fold, pop-out video, full screen, the map's place), then opens the map window for the map itself:
 * pins, what the cursor shows, the drone, the map picture and coordinate format, DOWNLOAD AREA. A step whose control
 * isn't on screen (UPDATE with no update known) is skipped; a window closed under the tour ends it.
 */
final class Tour {
    /** @param before done as the step comes up (opens the menu, the map window); null for nothing */
    private record Step(String title, String text, Supplier<JComponent> anchor, Runnable before) {}

    /** A control not on screen after this many tries (150 ms apart) is skipped: the step would wait forever. */
    private static final int MAX_TRIES = 7;

    private final MainWindow main;
    private final List<Step> steps = new ArrayList<>();
    private int index = -1;
    private int tries;
    private TourNotice notice;
    private final Timer retry = new Timer(150, e -> show());

    private Tour(MainWindow main) {
        this.main = main;
        retry.setRepeats(false);
        Runnable openMenu = main::tourOpenMenu;
        steps.add(new Step("MENU", "Everything lives here. Press it and SETUP, VIDEO, LINK and TOOLS slide out - as now.",
                main::tourMenuButton, openMenu));
        steps.add(new Step("SETUP", "The guides: first-time setup, PC, drone and phone setup, and this tour.", main::tourSetupButton, openMenu));
        steps.add(new Step("VIDEO", "The picture, the OSD and recording.", main::tourVideoButton, openMenu));
        steps.add(new Step("LINK", "The link's sounds, and who may watch as a spectator.", main::tourLinkButton, openMenu));
        steps.add(new Step("TOOLS", "Find the drone, the tracks folder and the tests.", main::tourToolsButton, openMenu));
        steps.add(new Step("UPDATE", "A newer DRAUPNE Link is ready: one press installs it.", main::tourUpdateButton, openMenu));
        steps.add(new Step("MINIMIZE / EXPAND", "Folds the controller card away for more video, and brings it back. The sticks "
                + "and switches keep working either way.", main::tourMinimizeButton, null));
        steps.add(new Step("POP OUT VIDEO", "The picture in a window of its own, for a second screen. A double-click on the video "
                + "shows it alone.", main::tourPopOutButton, null));
        steps.add(new Step("FULL SCREEN", "The picture fills the whole screen (F11 too). Esc brings the window back.",
                main::tourFullScreenButton, null));
        steps.add(new Step("THE MAP", "In flight the minimap takes this place, with POP OUT MAP under it. F3 opens the map window "
                + "any time - as now.", main::tourLinkCard, null));
        steps.add(new Step("MOVING AROUND", "Drag to pan, the wheel zooms, a double-click fits the whole flight. FOLLOW keeps the "
                + "drone in the middle; WHOLE FLIGHT and LAUNCH jump there.", () -> main.mapWindow().tourFollow(), main::showMapWindow));
        steps.add(new Step("PINS", "Right-click the map to drop a pin (Alpha, Bravo ...), or to rename or remove one. PINS lists "
                + "them all. Each pin shows its distance and bearing from the drone.", () -> main.mapWindow().tourPins(), null));
        steps.add(new Step("UNDER THE CURSOR", "Move the mouse over the map: the position, in the format chosen top right, and the "
                + "terrain height there show here. DRONE, ALTITUDE and LAUNCH are live.", () -> main.mapWindow().tourCursor(), null));
        steps.add(new Step("THE DRONE", "The arrow is the drone, pointing where it heads, with its track behind it. The ring marked "
                + "L is the launch point. LAST KNOWN on the arrow means its position is old.", () -> main.mapWindow().tourDrone(), null));
        steps.add(new Step("MAP TYPE", "The map picture: topo, paper map, satellite or OpenStreetMap. Change it here any time; "
                + "what you have looked at is kept for flying without internet.", () -> main.mapWindow().tourMapType(), null));
        steps.add(new Step("COORDINATES", "The coordinate system for the cursor, the drone and the pins: degrees, UTM or MGRS. "
                + "Change it here.", () -> main.mapWindow().tourFormat(), null));
        steps.add(new Step("OFFLINE MAPS", "DOWNLOAD AREA saves the map for flying without internet: this view, or a box you draw "
                + "with SELECT WITH CURSOR (or Alt + drag). Every map type and zoom level, one START.", () -> main.mapWindow().tourDownload(), null));
        steps.add(new Step("THAT'S THE TOUR", "SETUP > Tour shows it again. Press CONNECT in the main window when the drone and "
                + "the phone are ready.", main::tourMenuButton, openMenu));
    }

    /** Asks first; the tour runs on yes. */
    static void offer(MainWindow main) {
        Object[] options = {"Yes", "Not now"};
        int answer = JOptionPane.showOptionDialog(main, "Take a short tour of the window?\nMENU, the video, the map and the offline maps, one by one.",
                "Tour", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer == JOptionPane.YES_OPTION) start(main);
    }

    static void start(MainWindow main) {
        new Tour(main).next();
    }

    private void next() {
        if (notice != null) notice.dismiss();
        notice = null;
        index++;
        tries = 0;
        if (index >= steps.size()) return;
        Step s = steps.get(index);
        if (s.before() != null) {
            try {
                s.before().run();
            } catch (RuntimeException e) {
                main.logLine("Tour: step " + (index + 1) + " (" + s.title() + ") could not open its window: " + e);
            }
        }
        retry.restart(); // the window just opened: let it lay out first
    }

    /** EXIT TOUR, or a window gone: over, cleanly. */
    private void end() {
        if (notice != null) notice.dismiss();
        notice = null;
        index = steps.size();
    }

    private void show() {
        if (index < 0 || index >= steps.size()) return;
        Step s = steps.get(index);
        JComponent anchor = null;
        try {
            anchor = s.anchor().get();
        } catch (RuntimeException e) {
            // the window isn't there (yet): as not showing
        }
        if (anchor == null || !anchor.isShowing()) {
            if (++tries >= MAX_TRIES) { // not on screen (UPDATE with no update known, a layout without the control): skipped
                main.logLine("Tour: step " + (index + 1) + " (" + s.title() + ") skipped - its control isn't shown.");
                next();
                return;
            }
            retry.restart();
            return;
        }
        notice = new TourNotice(anchor, s.title(), s.text(), index + 1, steps.size(), this::next, this::end);
        if (!notice.pop()) retry.restart();
    }

    /** The notice: pointer at the control, the text, STEP i OF n, NEXT and EXIT TOUR; the rest of the window dims. */
    private static final class TourNotice extends JComponent {
        private static final int WIDTH = 460;
        private static final int PAD = 12;
        private static final int POINTER = 8;
        private static final int GAP = 6;
        private static final long IN_MS = 180;
        /** The control hidden this long (a card folded): the step moves on. */
        private static final long HIDDEN_MS = 2500;
        /** Its window gone this long (closed): the tour ends. */
        private static final long WINDOW_GONE_MS = 3000;

        private final JComponent anchor;
        private final String title;
        private final List<String> lines;
        private final String counter;
        private final JButton next = Theme.primaryButton("Next");
        private final JButton exit = Theme.secondaryButton("Exit tour");
        private final Runnable onEnd;
        private final Dim dim = new Dim();
        private final Timer timer = new Timer(33, e -> tick());
        private long shownAt;
        private boolean above;
        private long notShowingSince;

        TourNotice(JComponent anchor, String title, String text, int step, int of, Runnable onNext, Runnable onEnd) {
            this.anchor = anchor;
            this.title = title;
            this.onEnd = onEnd;
            counter = "STEP " + step + " OF " + of;
            setOpaque(false);
            setLayout(null);
            lines = wrap(text, getFontMetrics(textFont()), WIDTH - 2 * PAD);
            next.setText(step == of ? "Done" : "Next");
            next.addActionListener(e -> onNext.run());
            exit.addActionListener(e -> onEnd.run());
            exit.setVisible(step != of);
            add(next);
            add(exit);
        }

        boolean pop() {
            JRootPane root = SwingUtilities.getRootPane(anchor);
            if (root == null || !anchor.isShowing()) return false;
            JLayeredPane layer = root.getLayeredPane();
            layer.add(dim, JLayeredPane.POPUP_LAYER);
            layer.add(this, JLayeredPane.DRAG_LAYER);
            dim.setBounds(0, 0, layer.getWidth(), layer.getHeight());
            if (!place()) {
                dismiss();
                return false;
            }
            shownAt = System.currentTimeMillis();
            setVisible(true);
            timer.start();
            repaint();
            dim.repaint();
            return true;
        }

        void dismiss() {
            timer.stop();
            for (JComponent c : new JComponent[]{this, dim}) {
                Component parent = c.getParent();
                if (parent == null) continue;
                Rectangle r = c.getBounds();
                ((JComponent) parent).remove(c);
                parent.repaint(r.x, r.y, r.width, r.height);
            }
        }

        private void tick() {
            if (!anchor.isShowing()) {
                if (notShowingSince == 0) notShowingSince = System.currentTimeMillis();
                long hidden = System.currentTimeMillis() - notShowingSince;
                Window w = SwingUtilities.getWindowAncestor(anchor);
                boolean windowGone = w == null || !w.isDisplayable() || !w.isVisible();
                if (windowGone && hidden > WINDOW_GONE_MS) onEnd.run(); // its window was closed: the tour is over
                else if (!windowGone && hidden > HIDDEN_MS) next.doClick(); // only the control went (folded away): move on
                return;
            }
            notShowingSince = 0;
            Component layer = getParent();
            if (layer != null && !dim.getBounds().equals(new Rectangle(0, 0, layer.getWidth(), layer.getHeight()))) {
                dim.setBounds(0, 0, layer.getWidth(), layer.getHeight());
            }
            place();
            repaint();
            dim.repaint();
        }

        private int height() {
            return POINTER + 2 * PAD + titleHeight() + 6 + lines.size() * lineHeight() + 12 + 34 + 4;
        }

        /** Under the control, or above it when there is no room below; inside the window. */
        private boolean place() {
            Component layer = getParent();
            if (layer == null) return false;
            Rectangle a = anchorBounds();
            if (a == null) return false;
            int h = height();
            int x = Math.max(0, Math.min(a.x + a.width / 2 - WIDTH / 2, layer.getWidth() - WIDTH));
            above = a.y + a.height + GAP + h > layer.getHeight() && a.y - GAP - h >= 0;
            int y = above ? a.y - GAP - h : a.y + a.height + GAP;
            Rectangle r = new Rectangle(x, Math.max(0, Math.min(y, layer.getHeight() - h)), WIDTH, h);
            if (!r.equals(getBounds())) setBounds(r);
            int top = above ? 0 : POINTER;
            int by = top + 2 * PAD + titleHeight() + 6 + lines.size() * lineHeight() + 8 - PAD;
            next.setBounds(WIDTH - PAD - 86, by, 86, 30);
            exit.setBounds(PAD, by, 130, 30);
            return true;
        }

        private Rectangle anchorBounds() {
            Component layer = getParent();
            if (layer == null || anchor.getParent() == null) return null;
            return SwingUtilities.convertRectangle(anchor.getParent(), anchor.getBounds(), layer);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            long now = System.currentTimeMillis();
            float in = Math.min(1, (now - shownAt) / (float) IN_MS);
            float easeIn = 1 - (1 - in) * (1 - in);
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0, easeIn)));
                int w = getWidth(), h = getHeight();
                g.translate(0, Math.round((above ? 6 : -6) * (1 - easeIn)));
                int top = above ? 0 : POINTER, boxH = h - POINTER - 1;
                Rectangle a = anchorBounds();
                int tipX = a == null ? w / 2 : Math.min(Math.max(a.x + a.width / 2 - getX(), PAD + POINTER), w - PAD - POINTER);
                Path2D box = new Path2D.Float();
                box.moveTo(0, top);
                if (!above) {
                    box.lineTo(tipX - POINTER, top);
                    box.lineTo(tipX, 0);
                    box.lineTo(tipX + POINTER, top);
                }
                box.lineTo(w - 1, top);
                box.lineTo(w - 1, top + boxH);
                if (above) {
                    box.lineTo(tipX + POINTER, top + boxH);
                    box.lineTo(tipX, top + boxH + POINTER);
                    box.lineTo(tipX - POINTER, top + boxH);
                }
                box.lineTo(0, top + boxH);
                box.closePath();
                g.setColor(Theme.PANEL_2);
                g.fill(box);
                g.setColor(Theme.ACCENT);
                g.setStroke(new BasicStroke(1f));
                g.draw(box);
                g.fillRect(0, top, 3, boxH);

                int y = top + PAD;
                g.setFont(Theme.headingFont(11f));
                FontMetrics fm = g.getFontMetrics();
                g.setColor(Theme.MUTED);
                g.drawString(counter, w - PAD - fm.stringWidth(counter), y + fm.getAscent());
                g.setFont(Theme.headingFont(14f));
                fm = g.getFontMetrics();
                g.setColor(Theme.ACCENT);
                g.drawString(title, PAD + 2, y + fm.getAscent());
                y += titleHeight() + 6;
                g.setFont(textFont());
                fm = g.getFontMetrics();
                g.setColor(Theme.TEXT);
                for (String line : lines) {
                    g.drawString(line, PAD + 2, y + fm.getAscent());
                    y += lineHeight();
                }
            } finally {
                g.dispose();
            }
        }

        private static Font textFont() {
            return Theme.font(Font.PLAIN, 13f);
        }

        private int titleHeight() {
            return getFontMetrics(Theme.headingFont(14f)).getHeight();
        }

        private int lineHeight() {
            return getFontMetrics(textFont()).getHeight();
        }

        private static List<String> wrap(String text, FontMetrics fm, int width) {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && fm.stringWidth(next) > width) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (!line.isEmpty()) lines.add(line.toString());
            return lines;
        }

        /**
         * Dims the window but the control, which gets a pulsing ring. The mouse is dead everywhere but over the control
         * (it owns every point outside it, and listens, so clicks end here); keyboard shortcuts still work.
         */
        private final class Dim extends JComponent {
            Dim() {
                setOpaque(false);
                MouseAdapter swallow = new MouseAdapter() {
                };
                addMouseListener(swallow);
                addMouseMotionListener(swallow);
                addMouseWheelListener(swallow);
            }

            @Override
            public boolean contains(int x, int y) {
                Rectangle a = anchorBounds();
                return a == null || !a.contains(x, y);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Rectangle a = anchorBounds();
                if (a == null) return;
                Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
                try {
                    float in = Math.min(1, (System.currentTimeMillis() - shownAt) / (float) IN_MS);
                    Area shade = new Area(new Rectangle(0, 0, getWidth(), getHeight()));
                    RoundRectangle2D hole = new RoundRectangle2D.Float(a.x - 6, a.y - 6, a.width + 12, a.height + 12, 10, 10);
                    shade.subtract(new Area(hole));
                    g.setColor(Theme.alpha(java.awt.Color.BLACK, Math.round(150 * in)));
                    g.fill(shade);
                    double pulse = 0.5 + 0.5 * Math.sin((System.currentTimeMillis() - shownAt) / 320.0);
                    g.setColor(Theme.alpha(Theme.ACCENT, (int) Math.round((120 + 135 * pulse) * in)));
                    g.setStroke(new BasicStroke(2.5f));
                    g.draw(hole);
                } finally {
                    g.dispose();
                }
            }
        }
    }
}
