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
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The tour of the window, offered once the first-time setup is finished and under SETUP any time: one control at a
 * time is lit while the rest dims, with a notice in the style of the locked-button notices saying what it does
 * (NEXT / SKIP TOUR). It walks the main window (MENU, the controller card's fold, pop-out video, the map's place),
 * then opens the map window for the map itself: pins, what the cursor shows, the drone, DOWNLOAD AREA.
 */
final class Tour {
    /** @param before done as the step comes up (opens the map window); null for nothing */
    private record Step(String title, String text, Supplier<JComponent> anchor, Runnable before) {}

    private final MainWindow main;
    private final List<Step> steps = new ArrayList<>();
    private int index = -1;
    private TourNotice notice;
    private final Timer retry = new Timer(150, e -> show());

    private Tour(MainWindow main) {
        this.main = main;
        retry.setRepeats(false);
        steps.add(new Step("MENU", "Everything lives here. SETUP holds the guides, VIDEO the picture and the OSD, LINK the sounds "
                + "and spectators, TOOLS the map tools and tests. Press it and the four slide out.", main::tourMenuButton, null));
        steps.add(new Step("MINIMIZE / EXPAND", "Folds the controller card away for more video, and brings it back. The sticks "
                + "and switches keep working either way.", main::tourMinimizeButton, null));
        steps.add(new Step("POP OUT VIDEO", "The picture in a window of its own, for a second screen. A double-click on the video "
                + "shows it alone; F11 is full screen, Esc goes back.", main::tourPopOutButton, null));
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
        steps.add(new Step("OFFLINE MAPS", "DOWNLOAD AREA saves the map for flying without internet: this view, or a box you draw "
                + "with SELECT WITH CURSOR (or Alt + drag). Every map type and zoom level, one START.", () -> main.mapWindow().tourDownload(), null));
        steps.add(new Step("THAT'S THE TOUR", "SETUP > Tour shows it again. Press CONNECT in the main window when the drone and "
                + "the phone are ready.", main::tourMenuButton, null));
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
        if (index >= steps.size()) return;
        Step s = steps.get(index);
        if (s.before() != null) s.before().run();
        retry.restart(); // the window just opened: let it lay out first
    }

    private void skip() {
        if (notice != null) notice.dismiss();
        notice = null;
        index = steps.size();
    }

    private void show() {
        if (index < 0 || index >= steps.size()) return;
        Step s = steps.get(index);
        JComponent anchor = s.anchor().get();
        if (anchor == null || !anchor.isShowing()) {
            retry.restart();
            return;
        }
        notice = new TourNotice(anchor, s.title(), s.text(), index + 1, steps.size(), this::next, this::skip);
        if (!notice.pop()) retry.restart();
    }

    /** The notice: pointer at the control, the text, STEP i OF n, NEXT and SKIP TOUR; the rest of the window dims. */
    private static final class TourNotice extends JComponent {
        private static final int WIDTH = 400;
        private static final int PAD = 12;
        private static final int POINTER = 8;
        private static final int GAP = 6;
        private static final long IN_MS = 180;

        private final JComponent anchor;
        private final String title;
        private final List<String> lines;
        private final String counter;
        private final JButton next = Theme.primaryButton("Next");
        private final JButton skip = Theme.secondaryButton("Skip tour");
        private final Dim dim = new Dim();
        private final Timer timer = new Timer(33, e -> tick());
        private long shownAt;
        private boolean above;
        private long notShowingSince;

        TourNotice(JComponent anchor, String title, String text, int step, int of, Runnable onNext, Runnable onSkip) {
            this.anchor = anchor;
            this.title = title;
            counter = "STEP " + step + " OF " + of;
            setOpaque(false);
            setLayout(null);
            lines = wrap(text, getFontMetrics(textFont()), WIDTH - 2 * PAD);
            next.setText(step == of ? "Done" : "Next");
            next.addActionListener(e -> onNext.run());
            skip.addActionListener(e -> onSkip.run());
            skip.setVisible(step != of);
            add(next);
            add(skip);
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
                else if (System.currentTimeMillis() - notShowingSince > 2500) next.doClick(); // its window went: move on
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
            int by = h - 4 - 34 + (above ? 0 : 0) - (above ? 0 : 0);
            int top = above ? 0 : POINTER;
            by = top + 2 * PAD + titleHeight() + 6 + lines.size() * lineHeight() + 8 - PAD;
            next.setBounds(WIDTH - PAD - 86, by, 86, 30);
            skip.setBounds(PAD, by, 110, 30);
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

        /** Dims the window but the control, which gets a pulsing ring; mouse events pass through it. */
        private final class Dim extends JComponent {
            Dim() {
                setOpaque(false);
            }

            @Override
            public boolean contains(int x, int y) {
                return false;
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
