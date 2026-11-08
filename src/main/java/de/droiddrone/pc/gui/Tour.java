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
 * saying what it does (NEXT, and a cross top right that exits the tour). It walks the main window (MENU and each of its slide-out buttons, the
 * controller card's fold, pop-out video, full screen, the map's place), then opens the map window for the map itself: pins, what the cursor
 * shows, the drone, the map picture and coordinate format, DOWNLOAD AREA. The pilot learns by doing: a step at a
 * button or a choice keeps NEXT locked ("Press it") until that control was pressed. A step whose control isn't on
 * screen (UPDATE with no update known) is skipped; a window closed under the tour ends it.
 */
final class Tour {
    /**
     * @param before done as the step comes up (opens the menu, the map window); null for nothing
     * @param press  the pilot must press the control before NEXT works (a button or a choice); false for a plain
     *               area, and for UPDATE, whose press would start the update
     * @param tip    a muted line under the text: good to know, not the step itself; null for none
     * @param feature the feature the step came with (FEATURE_*), for the tour of what is new after an update; null
     *                for the steps every tour has had
     */
    private record Step(String title, String text, Supplier<JComponent> anchor, Runnable before, boolean press,
                        Supplier<JComponent> live, Supplier<JComponent> extra, Runnable after, String tip, String feature) {
        Step(String title, String text, Supplier<JComponent> anchor, Runnable before) {
            this(title, text, anchor, before, true, null, null, null, null, null);
        }

        Step(String title, String text, Supplier<JComponent> anchor, Runnable before, boolean press) {
            this(title, text, anchor, before, press, null, null, null, null, null);
        }

        Step(String title, String text, Supplier<JComponent> anchor, Runnable before, boolean press, Supplier<JComponent> live) {
            this(title, text, anchor, before, press, live, null, null, null, null);
        }

        Step(String title, String text, String tip, Supplier<JComponent> anchor, Runnable before, boolean press, Supplier<JComponent> live) {
            this(title, text, anchor, before, press, live, null, null, tip, null);
        }

        Step(String title, String text, Supplier<JComponent> anchor, Runnable before, boolean press, Supplier<JComponent> live, Supplier<JComponent> extra) {
            this(title, text, anchor, before, press, live, extra, null, null, null);
        }

        /** The same step, marked as part of a feature. */
        Step of(String feature) {
            return new Step(title, text, anchor, before, press, live, extra, after, tip, feature);
        }
    }

    /** The features whose steps came after the first tour: a PC that had the tour before them is offered them alone. */
    static final String FEATURE_NAMES = "names", FEATURE_MAP_TOOLS = "maptools", FEATURE_RADIO_HORIZON = "radiohorizon",
            FEATURE_COVERAGE = "coverage", FEATURE_OVERWATCH = "overwatch", FEATURE_OBSERVATION = "observation";
    static final List<String> FEATURES = List.of(FEATURE_NAMES, FEATURE_MAP_TOOLS, FEATURE_RADIO_HORIZON, FEATURE_COVERAGE,
            FEATURE_OVERWATCH, FEATURE_OBSERVATION);
    /** The features the pilot has been offered a tour of, comma-separated. */
    static final String SEEN_KEY = "tour.seen";

    /** A control not on screen after this many tries (150 ms apart) is skipped: the step would wait forever. */
    private static final int MAX_TRIES = 7;

    private final MainWindow main;
    private final List<Step> steps = new ArrayList<>();
    private int index = -1;
    private int tries;
    private TourNotice notice;
    private final Timer retry = new Timer(150, e -> show());

    private Tour(MainWindow main) {
        this(main, null);
    }

    /** @param only the features to show the steps of (the tour of what is new); null for the whole tour */
    private Tour(MainWindow main, java.util.Collection<String> only) {
        this.main = main;
        retry.setRepeats(false);
        buildSteps();
        if (only != null) steps.removeIf(s -> s.feature() == null || !only.contains(s.feature()));
    }

    private void buildSteps() {
        Runnable openMenu = main::tourOpenMenu;
        Supplier<JComponent> header = main::tourHeader; // the menu steps: the header stays clear, so the slide-out and the menus show in front
        steps.add(new Step("MENU", "Everything lives here. Press it: SETUP, VIDEO, LINK and TOOLS slide out.",
                "Tip: a second press closes or undoes the first.", main::tourMenuButton, openMenu, true, header));
        steps.add(new Step("SETUP", "First-time setup and this tour. PC, drone and phone setup with TOOLS > Advanced on.", main::tourSetupButton, openMenu, true, header));
        steps.add(new Step("VIDEO", "Picture, OSD, recording.", main::tourVideoButton, openMenu, true, header));
        steps.add(new Step("LINK", "Link sounds and spectators.", main::tourLinkButton, openMenu, true, header));
        steps.add(new Step("TOOLS", "Find the drone, previous flights, tests.", main::tourToolsButton, openMenu, true, header));
        steps.add(new Step("UPDATE", "A newer version is ready: one press installs it. After the tour.",
                main::tourUpdateButton, openMenu, false, header));
        steps.add(new Step("MINIMIZE / EXPAND", "Folds the controller card for more video. Sticks and switches keep working. "
                + "Press it.", main::tourMinimizeButton, null));
        // the two video buttons are explained, not pressed: pop-out moves the picture to a window of its own and
        // full screen covers the window, both over the tour
        steps.add(new Step("POP OUT VIDEO", "Video in its own window, for a second screen. VIDEO BACK returns it. "
                + "Double-click the video to show it alone.", main::tourPopOutButton, null, false));
        steps.add(new Step("FULL SCREEN", "Video fills the screen (F11). Esc or double-click to exit.",
                main::tourFullScreenButton, null, false));
        steps.add(new Step("CONNECT", "Pick the phone by name (MobileAlpha, MobileBravo ...): every phone running DRAUPNE Flight "
                + "on your Tailscale network. CONNECT flies; WATCH joins as a spectator. The minimap shows the drone; "
                + "POP OUT MAP or F3 opens the map window.",
                main::tourLinkCard, null, false).of(FEATURE_NAMES));
        // OBSERVATION (1.0.48): explained, not pressed - it opens streams to the drones
        steps.add(new Step("OBSERVATION", "CONNECT AS > Observation: watch up to six idle drones. CHOOSE DRONES picks them, "
                + "OBSERVE shows them all with sound. Each tile: MUTE, volume, green frame on sound. Click for a sharp picture, "
                + "double-click to enlarge. CONNECT on a tile flies that drone.",
                "Tip: on the map each tile's drone has a numbered view cone in its colour. Dashed = approximate heading.",
                main::tourLinkCard, null, false, null).of(FEATURE_OBSERVATION));
        // the sleep buttons only show while connected: the step puts them on the card as a demo that answers presses
        steps.add(new Step("SLEEP BUTTONS", "Shown while connected. DEEP SLEEP: phone sleeps, checks in every CHECK EVERY "
                + "minutes; WAKE brings it back. DISCONNECT: standby, camera off. Hidden while armed. Demo: press one.",
                main::tourPowerRow, () -> main.tourPowerDemo(true), true, null, null, () -> main.tourPowerDemo(false), null, null));
        // OVERWATCH (1.0.47): the third sleep button, in the same demo
        steps.add(new Step("OVERWATCH", "Sleep with camera and motion detection on. On motion the phone calls in: the drone "
                + "pulses red on the map, a card shows live video, CONNECT and DISMISS. The clip follows in full quality. "
                + "Disarmed only; under 20 % battery or too hot it goes to deep sleep. About 15-25 h on 5000 mAh. Press it.",
                main::tourOverwatchButton, () -> main.tourPowerDemo(true), true, null, null, () -> main.tourPowerDemo(false), null, null).of(FEATURE_OVERWATCH));
        // on the map steps the map itself stays live under the dimming: panning, the wheel, pins and the cursor figures work
        Supplier<JComponent> map = () -> main.mapWindow().map();
        steps.add(new Step("MOVING AROUND", "Drag to pan, wheel to zoom, double-click fits the flight. FOLLOW centres the "
                + "drone. Home: launch point. End: drone.", () -> main.mapWindow().tourFollow(), main::showMapWindow, true, map));
        // MAP TOOLS (1.0.35): the slide-out at the map's left edge; PINS and DOWNLOAD AREA live in it now
        Runnable mapAndTools = () -> {
            main.showMapWindow();
            main.mapWindow().openTools(true);
        };
        steps.add(new Step("MAP TOOLS", "Three groups: DRAW (lines, boxes, arrows, NATO symbols), TOOLS (ruler, radio horizon, "
                + "GCS search - shown while TOOLS is open), DATA (export, import, offline maps). Press it.",
                () -> main.mapWindow().tourTools(), main::showMapWindow, true, map).of(FEATURE_MAP_TOOLS));
        steps.add(new Step("DRAW, TOOLS, DATA", "Each tab opens its list; press again to fold. "
                + "Press TOOLS.", () -> main.mapWindow().tourGroups(), mapAndTools, true, map).of(FEATURE_RADIO_HORIZON));
        steps.add(new Step("RULER", "Click points: length and bearing per leg, total at the end. Click the first point to close "
                + "and get the area. Elevation profile below. KEEP saves it, Esc clears.", () -> main.mapWindow().tourRuler(), mapAndTools, false, map).of(FEATURE_MAP_TOOLS));
        steps.add(new Step("RADIO HORIZON", "Set mast height, aircraft altitude (each above its ground), tree height and frequency. "
                + "Press RADIO HORIZON, click the map: blue = the aircraft sees the mast over terrain and through forest. "
                + "Up to 30 km. Click again to move the mast; the cross on its label removes it.",
                () -> main.mapWindow().tourHorizon(), mapAndTools, false, map).of(FEATURE_RADIO_HORIZON));
        steps.add(new Step("GCS SEARCH", "Where to put the ground station: drag a box round the area to cover. The heat map "
                + "(30 km) shows green = sees all of it, red = none. Best spot ringed; the cross removes it.", () -> main.mapWindow().tourSearch(), mapAndTools, false, map).of(FEATURE_RADIO_HORIZON));
        steps.add(new Step("COVERAGE", "Estimated good, weak or no mobile signal (5G and older), from OpenCelliD masts and terrain. Pick your SIM's network "
                + "and the drone's height. Needs a free OpenCelliD key (KEY...). A guess, not a measurement.",
                () -> main.mapWindow().tourCoverage(), mapAndTools, false, map).of(FEATURE_COVERAGE));
        steps.add(new Step("CLIPS", "Overwatch events per drone: picture, time, length, place. Play one with its camera view on "
                + "the map. REMOVE hides it; files stay in Documents\\DRAUPNE Link\\Overwatch (FOLDER). Right-click a drone for its clips.",
                () -> main.mapWindow().tourClips(), mapAndTools, false, map).of(FEATURE_OVERWATCH));
        steps.add(new Step("DRAW", "LINE: click points, double-click ends. RECTANGLE: drag. ARROW: head at the last point. "
                + "Pick colour, width, outline or fill first. Right-click a drawing to rename or remove.",
                () -> main.mapWindow().tourDraw(), mapAndTools, false, map).of(FEATURE_MAP_TOOLS));
        steps.add(new Step("SYMBOLS", "NATO unit symbol where you click: type, then friend/hostile/neutral/unknown, then echelon. "
                + "Drawings are kept.",
                () -> main.mapWindow().tourSymbol(), mapAndTools, false, map).of(FEATURE_MAP_TOOLS));
        steps.add(new Step("PINS", "Right-click the map to drop, rename or remove a pin. Each shows distance and bearing from the drone. "
                + "PINS lists them: press it. These three are examples.",
                () -> main.mapWindow().tourPins(), () -> {
                    mapAndTools.run();
                    main.mapWindow().map().showExamplePins(true);
                    examplePins = true;
                }, true, map));
        steps.add(new Step("UNDER THE CURSOR", "Position and terrain height under the mouse. DRONE, ALTITUDE and LAUNCH are live.",
                () -> main.mapWindow().tourCursor(), null, false, map,
                () -> main.mapWindow().tourTerrain()));
        steps.add(new Step("THE DRONE", "The arrow is the drone, with its track. Ring L = launch point. "
                + "LAST KNOWN = old position.", () -> main.mapWindow().tourDrone(), null, false, map));
        steps.add(new Step("MAP TYPE", "Topo, paper map, satellite or OpenStreetMap. "
                + "Viewed areas are kept for offline use.", () -> main.mapWindow().tourMapType(), null));
        steps.add(new Step("COORDINATES", "Degrees, UTM or MGRS, for cursor, drone and pins.",
                () -> main.mapWindow().tourFormat(), null));
        steps.add(new Step("EXPORT", "Drag a box: PNG picture and KML drawings to Documents\\DRAUPNE Link\\Exports. "
                + "IMPORT reads KML (DRAUPNE, Google Earth, ATAK).",
                () -> main.mapWindow().tourExport(), mapAndTools, false, map).of(FEATURE_MAP_TOOLS));
        steps.add(new Step("OFFLINE MAPS", "DOWNLOAD AREA saves this view, or a box (SELECT WITH CURSOR or Alt + drag), "
                + "all map types and zooms, for flying without internet.",
                () -> main.mapWindow().tourDownload(), mapAndTools, false)); // explained, not pressed: a download is for later
        // the last step is back in the main window: it comes to the front over the map window by itself
        Runnable backToMain = () -> {
            main.toFront();
            main.requestFocus();
            main.tourOpenMenu();
        };
        steps.add(new Step("THAT'S THE TOUR", "SETUP > Tour shows it again. Press CONNECT when drone and phone are ready.", main::tourMenuButton, backToMain, false));
    }

    /** Set once the tour has been offered on this PC: after the first-time setup, or on the first start after an update brought it. */
    static final String OFFERED_KEY = "tour.offered";
    /** The notice's layer: over the window's animations, under a menu's wipe (TacticalCombo.GHOST_LAYER). */
    static final Integer NOTICE_LAYER = JLayeredPane.POPUP_LAYER + 10;

    /** The features whose steps the pilot has not been offered yet (the tour of what is new after an update). */
    static List<String> unseen(de.droiddrone.pc.PcConfig config) {
        List<String> seen = java.util.Arrays.asList(config.getString(SEEN_KEY).split(","));
        List<String> out = new ArrayList<>();
        for (String f : FEATURES) if (!seen.contains(f)) out.add(f);
        return out;
    }

    /**
     * An update brought steps the pilot's tour didn't have: offered once, as a short tour of just those. Declining
     * counts as seen too (SETUP > Tour has everything any time).
     */
    static void offerNew(MainWindow main, List<String> features) {
        main.save(java.util.Map.of(SEEN_KEY, String.join(",", FEATURES)));
        List<String> what = new ArrayList<>();
        for (String f : features) what.add(f.equals(FEATURE_NAMES) ? "phone by name" : f.equals(FEATURE_MAP_TOOLS) ? "map tools" : f.equals(FEATURE_RADIO_HORIZON) ? "radio horizon, GCS search"
                : f.equals(FEATURE_COVERAGE) ? "coverage map"
                : f.equals(FEATURE_OVERWATCH) ? "OVERWATCH and its clips"
                : f.equals(FEATURE_OBSERVATION) ? "observation of six drones" : f);
        Object[] options = {"Yes", "Not now"};
        int answer = JOptionPane.showOptionDialog(main, "New: " + String.join(", ", what)
                        + ".\nTour the new parts?", "What's new", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer == JOptionPane.YES_OPTION) new Tour(main, features).next();
    }

    /** Asks first; the tour runs on yes. The whole tour counts every feature as seen. */
    static void offer(MainWindow main) {
        main.save(java.util.Map.of(OFFERED_KEY, "true", SEEN_KEY, String.join(",", FEATURES)));
        Object[] options = {"Yes", "Not now"};
        int answer = JOptionPane.showOptionDialog(main, "Take a short tour of the window?",
                "Tour", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer == JOptionPane.YES_OPTION) start(main);
    }

    static void start(MainWindow main) {
        main.save(java.util.Map.of(SEEN_KEY, String.join(",", FEATURES)));
        new Tour(main).next();
    }

    private void next() {
        if (notice != null) notice.dismiss();
        notice = null;
        after();
        index++;
        tries = 0;
        if (index >= steps.size()) {
            clearExamplePins();
            return;
        }
        Step s = steps.get(index);
        if (s.before() != null) {
            try {
                s.before().run();
            } catch (RuntimeException e) {
                main.logLine("Tour: step " + (index + 1) + " (" + s.title() + ") failed to open: " + e);
            }
        }
        retry.restart(); // the window just opened: let it lay out first
    }

    /** EXIT TOUR, or a window gone: over, cleanly. */
    private void end() {
        if (notice != null) notice.dismiss();
        notice = null;
        after();
        index = steps.size();
        clearExamplePins();
    }

    /** The step that is over undoes what it set up (the sleep buttons' demo). */
    private void after() {
        if (index < 0 || index >= steps.size() || steps.get(index).after() == null) return;
        try {
            steps.get(index).after().run();
        } catch (RuntimeException e) {
            main.logLine("Tour: step " + (index + 1) + " could not clean up: " + e);
        }
    }

    /** The tour's made-up pins on the map: shown by the PINS step, gone when the tour is over. */
    private boolean examplePins;

    private void clearExamplePins() {
        if (!examplePins) return;
        examplePins = false;
        try {
            main.mapWindow().map().showExamplePins(false);
        } catch (RuntimeException e) {
            // the map window is gone with them
        }
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
                main.logLine("Tour: step " + (index + 1) + " (" + s.title() + ") skipped: not on screen.");
                next();
                return;
            }
            retry.restart();
            return;
        }
        notice = new TourNotice(anchor, s.title(), s.text(), s.tip(), index + 1, steps.size(), this::next, this::end);
        notice.requirePress(s.press());
        if (s.live() != null) {
            try {
                notice.setLive(s.live().get());
            } catch (RuntimeException e) {
                // no live area then
            }
        }
        if (s.extra() != null) {
            try {
                notice.setExtra(s.extra().get());
            } catch (RuntimeException e) {
                // the one control then
            }
        }
        if (!notice.pop()) retry.restart();
    }

    /** The notice: pointer at the control, the text, STEP i OF n, NEXT across the bottom and a cross top right that exits the tour; the rest of the window dims. */
    private static final class TourNotice extends JComponent {
        private static final int WIDTH = 460;
        private static final int PAD = 12;
        private static final int POINTER = 8;
        private static final int GAP = 6;
        /** The exit cross's box, top right. */
        private static final int CLOSE = 24;
        private static final long IN_MS = 180;
        /** The control hidden this long (a card folded): the step moves on. */
        private static final long HIDDEN_MS = 2500;
        /** Its window gone this long (closed): the tour ends. */
        private static final long WINDOW_GONE_MS = 3000;

        private final JComponent anchor;
        private final String title;
        private final List<String> lines;
        /** The tip under the text, wrapped; empty for none. */
        private final List<String> tipLines;
        private final String counter;
        private final JButton next = Theme.primaryButton("Next");
        private final JButton exit = new CloseButton();
        private final Runnable onEnd;
        private final Dim dim = new Dim();
        private final Timer timer = new Timer(33, e -> tick());
        private long shownAt;
        private boolean above;
        private long notShowingSince;
        /** NEXT is locked until the control was pressed: a mouse button let go over it (or its button fired). */
        private boolean waitingForPress;
        private final java.awt.event.AWTEventListener pressWatch = this::watchPress;
        private final java.awt.event.ActionListener firedWatch = e -> pressed();

        private void watchPress(java.awt.AWTEvent event) {
            if (event.getID() != java.awt.event.MouseEvent.MOUSE_RELEASED || !(event.getSource() instanceof Component c)) return;
            if (c == anchor || SwingUtilities.isDescendingFrom(c, anchor)) pressed();
        }

        TourNotice(JComponent anchor, String title, String text, String tip, int step, int of, Runnable onNext, Runnable onEnd) {
            this.anchor = anchor;
            this.title = title;
            this.onEnd = onEnd;
            counter = "STEP " + step + " OF " + of;
            setOpaque(false);
            setLayout(null);
            lines = wrap(text, getFontMetrics(textFont()), WIDTH - 2 * PAD);
            tipLines = tip == null || tip.isBlank() ? List.of() : wrap(tip, getFontMetrics(tipFont()), WIDTH - 2 * PAD);
            next.setText(step == of ? "Done" : "Next");
            next.addActionListener(e -> onNext.run());
            exit.addActionListener(e -> onEnd.run());
            exit.setVisible(step != of);
            add(next);
            add(exit);
        }

        /** An area the mouse still works over while this step shows (the map under the map steps); null = none. */
        private JComponent live;
        /** A second control lit with the first (CURSOR and TERRAIN HEIGHT together); null = just the one. */
        private JComponent extra;

        void setLive(JComponent live) {
            this.live = live;
        }

        void setExtra(JComponent extra) {
            this.extra = extra;
        }

        /** The pilot learns by doing: NEXT says "Press it" and waits until the control was pressed. */
        void requirePress(boolean required) {
            waitingForPress = required;
            next.setEnabled(!required);
            next.setText(required ? "Press it" : next.getText());
        }

        private void pressed() {
            if (!waitingForPress) return;
            waitingForPress = false;
            next.setEnabled(true);
            next.setText("Next");
            repaint();
        }

        boolean pop() {
            JRootPane root = SwingUtilities.getRootPane(anchor);
            if (root == null || !anchor.isShowing()) return false;
            if (waitingForPress) {
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(pressWatch, java.awt.AWTEvent.MOUSE_EVENT_MASK);
                if (anchor instanceof javax.swing.AbstractButton b) b.addActionListener(firedWatch);
            }
            JLayeredPane layer = root.getLayeredPane();
            // the dim sits under the popup layer: a menu that opens, and the rows that fly as it closes, show over it
            layer.add(dim, JLayeredPane.MODAL_LAYER);
            // 1.0.38: above the window's own animations, so the notice never goes behind one and pops out when it ends
            // (the pilot); but under a menu's wipe (TacticalCombo.GHOST_LAYER), since the menu itself ends in front
            layer.add(this, NOTICE_LAYER);
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
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(pressWatch);
            if (anchor instanceof javax.swing.AbstractButton b) b.removeActionListener(firedWatch);
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
            return POINTER + 2 * PAD + titleHeight() + 6 + lines.size() * lineHeight() + tipHeight() + 12 + 34 + 4;
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
            int by = top + 2 * PAD + titleHeight() + 6 + lines.size() * lineHeight() + tipHeight() + 8 - PAD;
            next.setBounds(PAD, by, WIDTH - 2 * PAD, 30); // across the box: the exit is the cross top right
            exit.setBounds(WIDTH - PAD - CLOSE + 6, top + PAD - 6, CLOSE, CLOSE);
            return true;
        }

        /** The lit area: the control, and the second one with it when there is one (their union). */
        private Rectangle anchorBounds() {
            Component layer = getParent();
            if (layer == null || anchor.getParent() == null) return null;
            Rectangle r = SwingUtilities.convertRectangle(anchor.getParent(), anchor.getBounds(), layer);
            JComponent e = extra;
            if (e != null && e.isShowing() && e.getParent() != null) {
                r = r.union(SwingUtilities.convertRectangle(e.getParent(), e.getBounds(), layer));
            }
            return r;
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
                g.drawString(counter, w - PAD - CLOSE - 2 - fm.stringWidth(counter), y + fm.getAscent()); // left of the cross
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
                if (!tipLines.isEmpty()) {
                    y += 4;
                    g.setFont(tipFont());
                    fm = g.getFontMetrics();
                    g.setColor(Theme.MUTED);
                    for (String line : tipLines) {
                        g.drawString(line, PAD + 2, y + fm.getAscent());
                        y += tipLineHeight();
                    }
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

        private static Font tipFont() {
            return Theme.font(Font.ITALIC, 12f);
        }

        private int tipLineHeight() {
            return getFontMetrics(tipFont()).getHeight();
        }

        /** The tip's lines and the gap above them; 0 without a tip. */
        private int tipHeight() {
            return tipLines.isEmpty() ? 0 : 4 + tipLines.size() * tipLineHeight();
        }

        /** EXIT TOUR as a small cross in the notice's top right corner: muted, red under the mouse. */
        private static final class CloseButton extends JButton {
            CloseButton() {
                setToolTipText("Exit tour");
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setOpaque(false);
                setRolloverEnabled(true);
                setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
                try {
                    boolean hot = getModel().isRollover() || getModel().isPressed();
                    g.setColor(hot ? Theme.ACCENT : Theme.MUTED);
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int cx = getWidth() / 2, cy = getHeight() / 2, r = 5;
                    g.drawLine(cx - r, cy - r, cx + r, cy + r);
                    g.drawLine(cx + r, cy - r, cx - r, cy + r);
                } finally {
                    g.dispose();
                }
            }
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
                if (a != null && a.contains(x, y)) return false;
                JComponent l = live;
                if (l != null && l.isShowing() && getParent() != null && l.getParent() != null) {
                    Rectangle r = SwingUtilities.convertRectangle(l.getParent(), l.getBounds(), getParent());
                    if (r.contains(x, y)) return false; // the map stays live: the cursor figures, panning, pins
                }
                return true;
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
                    JComponent l = live; // the live area (the header on the menu steps, the map on the map steps) stays clear too
                    if (l != null && l.isShowing() && l.getParent() != null) {
                        Rectangle r = SwingUtilities.convertRectangle(l.getParent(), l.getBounds(), getParent());
                        shade.subtract(new Area(new Rectangle(r.x - 2, r.y - 2, r.width + 4, r.height + 4)));
                    }
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
