package de.droiddrone.pc.gui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JRootPane;
import javax.swing.MenuSelectionManager;
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
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The tour of the window, offered once the first-time setup is finished and under SETUP any time: one control at a
 * time is lit while the rest dims (and is dead to the mouse), with a notice saying what it does (NEXT, and a cross top
 * right that exits the tour). The pilot learns by doing: a step at a button or a choice keeps NEXT black ("Press it")
 * until the lit control was pressed; a press anywhere else rings the lit control.
 * <p>
 * The order (1.0.52, the pilot): a welcome, the main window top down (MENU, its four lists in one step, PREVIOUS
 * FLIGHTS, the controller card, the video's buttons, the LINK card: phone, PILOT UAV, sleep, CONNECT AS), POP OUT MAP,
 * the map window (the map itself first, then MAP TOOLS: DRAW, TOOLS, DATA - each list folding slowly into the next
 * before its step), FLEET MANAGEMENT with three drones of its own ({@link TourFinale}), and the finale: an OVERWATCH
 * alert of a BTR, CONNECT, fly into it - the crash ends the tour.
 * <p>
 * A step whose control is hidden (UPDATE with no update known) is skipped at once; one not on screen yet (a window
 * opening) is tried again for a second; a window closed under the tour ends it.
 */
final class Tour {
    /** One step: built with the setters, read by the tour. */
    static final class Step {
        /** The finish's may change before it shows (a hit). */
        String title;
        String text;
        /** What is lit; null = a centred box (the welcome, the finish) over the main window. */
        Supplier<JComponent> anchor;
        /** Done as the step comes up; and undone (after) when it is over, however it ends. */
        Runnable before, after;
        /** Done when the pilot moves on from it (NEXT, or on by itself) - never when the tour closes there. */
        Runnable onNext;
        /** The step needs the tour's drones: they are started (or the pilot's fleet closed first) as it comes up. */
        boolean finale;
        /** The pilot must press the lit control (or one of the targets) before NEXT works. */
        boolean press;
        /** Areas the mouse still works over (the header on the menu steps, the map on the map steps). */
        Supplier<JComponent> live;
        /** A second control lit with the first: the box covers both and everything between. */
        Supplier<JComponent> extra;
        /** Any of these pressed counts (the four menu buttons); null = the lit area. */
        Supplier<List<JComponent>> targets;
        /** Something to wait for instead of a press; NEXT shows waitText meanwhile. With auto, it moves on by itself. */
        BooleanSupplier waitFor;
        String waitText;
        boolean auto;
        /** Skip the step when this says so (the finale without its drones). */
        BooleanSupplier skip;
        /** Run first, with the step shown when it calls back (the slow switch between MAP TOOLS lists). */
        Consumer<Runnable> transition;
        String tip, feature;
        /** The welcome's legend (the black NEXT and the cross explained). */
        boolean legend;
        /** The finish: its burst. */
        boolean finish;
        /** Its window gone (closed, hidden): opened again instead of the tour ending - the map window's steps. */
        Runnable reopen;

        Step(String title, String text, Supplier<JComponent> anchor) {
            this.title = title;
            this.text = text;
            this.anchor = anchor;
        }

        Step before(Runnable r) {
            before = r;
            return this;
        }

        Step after(Runnable r) {
            after = r;
            return this;
        }

        Step onNext(Runnable r) {
            onNext = r;
            return this;
        }

        Step needsFinale() {
            finale = true;
            return this;
        }

        Step press() {
            press = true;
            return this;
        }

        Step live(Supplier<JComponent> s) {
            live = s;
            return this;
        }

        Step extra(Supplier<JComponent> s) {
            extra = s;
            return this;
        }

        Step targets(Supplier<List<JComponent>> s) {
            targets = s;
            press = true;
            return this;
        }

        Step waitFor(BooleanSupplier s, String text, boolean auto) {
            waitFor = s;
            waitText = text;
            this.auto = auto;
            return this;
        }

        Step skip(BooleanSupplier s) {
            skip = s;
            return this;
        }

        Step transition(Consumer<Runnable> t) {
            transition = t;
            return this;
        }

        Step tip(String t) {
            tip = t;
            return this;
        }

        Step of(String f) {
            feature = f;
            return this;
        }

        Step reopen(Runnable r) {
            reopen = r;
            return this;
        }
    }

    /** The features whose steps came after the first tour: a PC that had the tour before them is offered them alone. */
    static final String FEATURE_NAMES = "names", FEATURE_MAP_TOOLS = "maptools", FEATURE_RADIO_HORIZON = "radiohorizon",
            FEATURE_COVERAGE = "coverage", FEATURE_OVERWATCH = "overwatch", FEATURE_OBSERVATION = "observation",
            FEATURE_FLEET = "fleet", FEATURE_FLIGHTS = "flights", FEATURE_SPECTATOR = "spectator";
    static final List<String> FEATURES = List.of(FEATURE_NAMES, FEATURE_MAP_TOOLS, FEATURE_RADIO_HORIZON, FEATURE_COVERAGE,
            FEATURE_OVERWATCH, FEATURE_OBSERVATION, FEATURE_FLEET, FEATURE_FLIGHTS, FEATURE_SPECTATOR);
    /** The features the pilot has been offered a tour of, comma-separated. */
    static final String SEEN_KEY = "tour.seen";

    /** A control not on screen after this many tries (150 ms apart) is skipped: the step would wait forever. */
    private static final int MAX_TRIES = 7;

    /** The tour running now: a second one (SETUP > Tour during the tour) ends it first, never runs beside it. */
    private static Tour current;

    private final MainWindow main;
    /** The spectator steps' virtual pilot PC (1.0.56). */
    private final TourSpectator spectatorPart;
    private final List<Step> steps = new ArrayList<>();
    private int index = -1;
    private int tries;
    /** Counts the steps shown: a transition calling back after its step is over does nothing. */
    private int generation;
    private TourNotice notice;
    private final Timer retry = new Timer(150, e -> show());
    private final TourFinale finale;
    /** SETUP > Overwatch demo: without the tour's drones there is nothing to show, so it ends. */
    private boolean demo;
    /** The tour's drones asked for once: a refusal isn't asked again at every later fleet step. */
    private boolean finaleTried;
    /** Where the pilot moved the box and how wide it was made: kept for the rest of the tour. */
    private final NoticePlace noticePlace = new NoticePlace();

    /** @param only the features to show the steps of (the tour of what is new); null for the whole tour */
    private Tour(MainWindow main, java.util.Collection<String> only) {
        this.main = main;
        finale = new TourFinale(main);
        spectatorPart = new TourSpectator(main, finale::fleet);
        retry.setRepeats(false);
        buildSteps();
        if (only != null) steps.removeIf(s -> s.feature == null || !only.contains(s.feature));
    }

    /** The order, worked out from where things are (1.0.52): top to bottom of the main window, then the map window, then the fleet. */
    private void buildSteps() {
        Runnable openMenu = () -> {
            closeMenus();
            main.tourOpenMenu();
        };
        Supplier<JComponent> header = main::tourHeader; // the menu steps: the header stays clear, so the slide-out and the menus show in front
        Supplier<JComponent> centre = main::tourCentre;

        Step welcome = new Step("GUIDED TOUR", "A short walk through DRAUPNE, one part at a time. Animations show what each step "
                + "is about.", centre);
        welcome.legend = true;
        steps.add(welcome);

        // ---- the main window, top down
        steps.add(new Step("MENU", "Everything lives here. Press it.", main::tourMenuButton).before(openMenu).press().live(header));
        steps.add(new Step("THE MENUS", "SETUP: first-time setup, Set up a new drone, this tour. VIDEO: picture, OSD, "
                + "recording. LINK: sounds, spectators. TOOLS: find drone, tests, Reset setup. Press one.",
                main::tourSetupButton).extra(main::tourToolsButton).before(openMenu).live(header)
                .targets(() -> List.of(main.tourSetupButton(), main.tourVideoButton(), main.tourLinkButton(), main.tourToolsButton())));
        steps.add(new Step("UPDATE", "A newer version is ready: one press installs it. After the tour.",
                main::tourUpdateButton).before(openMenu).live(header));
        steps.add(new Step("MINIMIZE", "Folds the controller card for more video. Sticks and switches keep working. Press it.",
                main::tourMinimizeButton).before(this::closeMenus).press());
        // the video's buttons are explained, not pressed: pop-out and full screen would cover the tour
        steps.add(new Step("POP OUT VIDEO", "Video in its own window, for a second screen. Double-click the video to show it alone.",
                main::tourPopOutButton));
        steps.add(new Step("FULL SCREEN", "Video fills the screen (F11). Esc to exit.", main::tourFullScreenButton));
        steps.add(new Step("BEEPER", "Hold it: the drone beeps, to find it in the grass.", main::tourBeeperButton));
        steps.add(new Step("MUTE", "The phone's microphone. M mutes. Arming mutes it.", main::tourMuteButton));

        // ---- the LINK card: phone, PILOT UAV, sleep, CONNECT AS
        steps.add(new Step("PHONE", "Every phone running DRAUPNE Flight, by name. Pick one here. Press it.",
                main::tourPhoneCombo).press().of(FEATURE_NAMES));
        steps.add(new Step("PILOT UAV", "Connects and flies the chosen drone. Connected, it turns STANDBY MODE: rests it, back "
                + "in 2 s. A sleeping drone: REGAINING CONTROL IN x MIN, until its check-in.",
                main::tourConnectButton));
        steps.add(new Step("DEEP SLEEP / OVERWATCH", "DEEP SLEEP: the phone sleeps and checks in at the interval set in CHECK "
                + "EVERY. OVERWATCH: the same, with the camera watching; motion calls you. Press one.", main::tourDeepSleepButton)
                .extra(main::tourCheckEvery)
                .targets(() -> List.of(main.tourDeepSleepButton(), main.tourOverwatchButton()))
                .before(() -> main.tourPowerDemo(true)).after(() -> main.tourPowerDemo(false)).of(FEATURE_OVERWATCH));
        steps.add(new Step("CONNECT AS", "Pilot flies. Spectator watches another PC's flight. Observation shows up to six idle "
                + "drones with sound.", main::tourRoleCombo).of(FEATURE_OBSERVATION));
        steps.add(new Step("POP OUT MAP", "The map in its own window. Press it.", main::tourPopMapButton)
                .before(main::tourHideMapWindow).press());

        // ---- the map window: the map itself first
        Supplier<JComponent> map = () -> main.mapWindow().map();
        steps.add(new Step("MOVING AROUND", "Drag to pan, wheel to zoom. FOLLOW keeps the drone in view. Press it.",
                () -> main.mapWindow().tourFollow()).before(main::showMapWindow).press().live(map));
        steps.add(new Step("FIND DRONE", "The drone's last position, to go and get it.",
                () -> main.mapWindow().tourFindDrone()).live(map));
        steps.add(new Step("MAP TYPE", "Topo, paper map, satellite or OpenStreetMap. Viewed areas are kept offline. Press it.",
                () -> main.mapWindow().tourMapType()).press());
        steps.add(new Step("COORDINATES", "MGRS, UTM or degrees, for cursor, drone and pins. Press it.",
                () -> main.mapWindow().tourFormat()).press());
        steps.add(new Step("UNDER THE CURSOR", "Position and terrain height under the mouse. Right-click the map: Copy grid.",
                () -> main.mapWindow().tourCursor()).extra(() -> main.mapWindow().tourTerrain()).live(map));
        steps.add(new Step("THE DRONE", "The arrow is the drone, with its track. L = launch point.",
                () -> main.mapWindow().tourDrone()).live(map));

        // ---- MAP TOOLS: DRAW, TOOLS, DATA, each list folding into the next before its step
        steps.add(new Step("MAP TOOLS", "Drawing, tools and data. Press it.", () -> main.mapWindow().tourTools())
                .press().live(map).of(FEATURE_MAP_TOOLS));
        steps.add(new Step("DRAW", "LINE, RECTANGLE, ARROW: colour and width first. OUTLINE / FILL for rectangles. "
                + "Right-click a drawing to rename or remove.", () -> main.mapWindow().tourDraw())
                .extra(() -> main.mapWindow().tourDrawEnd(true)).transition(group(MapToolsPanel.Group.DRAW))
                .after(() -> main.mapWindow().tourDrawEnd(false)).live(map).of(FEATURE_MAP_TOOLS));
        steps.add(new Step("SYMBOLS", "A NATO symbol where you click: type, side (colour), unit size.",
                () -> main.mapWindow().tourSymbol()).extra(() -> main.mapWindow().tourSymbolEnd()).live(map).of(FEATURE_MAP_TOOLS));
        steps.add(new Step("PLACE AT", "A point or symbol at a typed grid.", () -> main.mapWindow().tourPlaceAt())
                .live(map).of(FEATURE_MAP_TOOLS));
        steps.add(new Step("PINS", "Right-click the map to drop one. PINS lists them; EDIT LIST adds, renames, removes. "
                + "These three are examples. Press it.", () -> main.mapWindow().tourPins()).before(() -> {
                    main.mapWindow().map().showExamplePins(true);
                    examplePins = true;
                }).after(() -> main.mapWindow().tourClosePins()) // the pins sheet goes with its step (the pilot, 2026-11-09)
                .press().live(map));
        steps.add(new Step("TOOLS", "RULER: lengths, bearings, height profile. RADIO HORIZON and GCS SEARCH are for traditional "
                + "radios, not 5G. GO TO: centre on a grid. CLIPS: overwatch events. PREVIOUS FLIGHTS: replay.",
                () -> main.mapWindow().tourRuler())
                .extra(() -> main.mapWindow().tourToolsEnd()).transition(group(MapToolsPanel.Group.TOOLS)).live(map)
                .of(FEATURE_MAP_TOOLS));
        steps.add(new Step("PREVIOUS FLIGHTS", "Each flight's video, track, height, speed, battery and link on one timeline. "
                + "EXPORT or DELETE.", PreviousFlightsDialog::tourList)
                .extra(PreviousFlightsDialog::tourTimeline).before(() -> main.mapWindow().tourOpenPreviousFlights())
                .after(PreviousFlightsDialog::tourClose).of(FEATURE_FLIGHTS));
        steps.add(new Step("DATA", "EXPORT a box: PNG and KML for Google Earth, ATAK / WinTAK and QGIS. IMPORT their KML. "
                + "DOWNLOAD AREA keeps maps offline. CLEAR ALL empties the map.", () -> main.mapWindow().tourExport())
                .extra(() -> main.mapWindow().tourDataEnd()).transition(group(MapToolsPanel.Group.DATA)).live(map)
                .of(FEATURE_MAP_TOOLS));

        // ---- FLEET MANAGEMENT, with three drones of the tour's own, and the finale
        BooleanSupplier noDrones = () -> !finale.started();
        steps.add(new Step("FLEET", "Every phone at a glance; modes changed without connecting. Press it.",
                () -> main.mapWindow().tourFleetTab()).transition(done -> main.mapWindow().tourSwitch(null, done))
                .before(main::showMapWindow).needsFinale().press().live(map).reopen(main::showMapWindow).of(FEATURE_FLEET));
        steps.add(new Step("THE ROWS", "A row a phone: mode sign, drone V/cell, phone %, last heard, ring to next check-in. "
                + "Click one: it shows on the map.", () -> main.mapWindow().tourFleetRows()).live(map).reopen(main::showMapWindow)
                .of(FEATURE_FLEET));
        steps.add(new Step("MODES", "STANDBY, DEEP SLEEP, OVERWATCH and EVERY reach the phone at its next "
                + "check-in, without connecting. PILOT UAV flies it.", () -> main.mapWindow().tourFleetStrip()).live(map)
                .reopen(main::showMapWindow).of(FEATURE_FLEET));
        steps.add(new Step("THE EYES", "Eyes are drones on OVERWATCH; the cones are what they see. Anything entering a "
                + "cone alerts you.", map).before(this::showTourArea).onNext(this::driveIn).live(map).skip(noDrones)
                .reopen(main::showMapWindow).of(FEATURE_FLEET));
        steps.add(new Step("INTEL", "Enemy movement reported near the drones. Attack may come soon. Stand by.",
                () -> main.mapWindow().tourFleetRows()).live(map).skip(noDrones).reopen(main::showMapWindow)
                .waitFor(finale::alerted, "Standing by...", true).of(FEATURE_FLEET));
        steps.add(new Step("ALERT", "It saw something. Press CONNECT on the alert.", () -> main.mapWindow().tourAlertCards())
                .live(map).skip(noDrones).reopen(main::showMapWindow).waitFor(finale::connected, "Press CONNECT", true)
                .of(FEATURE_FLEET));
        steps.add(new Step("FLY", "Hear it? It comes from where it is. Arm, fly to it and hit it.", main::tourVideo)
                .before(() -> {
                    main.toFront();
                    main.tourSoundOn();
                }).live(main::tourVideo).skip(noDrones).waitFor(finale::crashed, "Hit the BTR", true).of(FEATURE_FLEET));

        // ---- SPECTATOR (1.0.56): another pilot PC, mirrored here
        steps.addAll(spectatorPart.steps());

        Step finish = new Step("TOUR COMPLETE", "You've completed the tour. SETUP > Tour shows it again.", centre).before(() -> {
            main.toFront();
            if (finale.crashed()) {
                steps.get(index).title = "DIRECT HIT";
                steps.get(index).text = "You've completed the tour. You're ready to fly for real.";
            }
        });
        finish.finish = true;
        steps.add(finish);
    }

    /** The slow switch to a MAP TOOLS list, then the step. */
    private Consumer<Runnable> group(MapToolsPanel.Group g) {
        return done -> {
            main.showMapWindow();
            main.mapWindow().tourSwitch(g, done);
        };
    }

    /**
     * The tour's drones up (true), or why not said. 2026-11-09 (the pilot: "the overwatch demo is broken, the flight
     * paths are gone, the launch point is gone..."): with the pilot's own virtual fleet open the drones were refused
     * with a line in the log only, and the steps ran on an empty map. Now the pilot is asked to close the fleet, and a
     * refusal is said where it can be seen.
     */
    private boolean startFinale() {
        main.showMapWindow(); // the Overwatch demo starts here, the map window maybe closed
        if (finale.started()) return true;
        if (!finale.start() && finale.fleetOpen() && closePilotsFleet()) finale.start();
        if (!finale.started()) {
            main.logLine("Tour: no drones for the fleet steps - " + finale.refused());
            JOptionPane.showMessageDialog(main, (demo ? "Demo not started. " : "No tour drones: BTR part skipped. ")
                    + finale.refused(), demo ? "Overwatch demo" : "Tour", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        showTourArea();
        // 1.0.52 (the pilot: "only Zulu shows at once, Whiskey after a while"): asleep, a drone answers no name
        // query, so the three that check in every 1-5 min were only known at their first check-in. The name service
        // asks now, while all four are still awake; the modes follow once it has them (4 s at most), and each then
        // checks in at once (TourScenario.placeModes): rows, eyes and moons right away.
        main.tourScanNames();
        long until = System.currentTimeMillis() + 4000;
        Timer wait = new Timer(100, null);
        wait.addActionListener(e -> {
            if (!finale.started()) {
                wait.stop();
                return;
            }
            if (main.tourNamesKnown(finale.names()) || System.currentTimeMillis() > until) {
                wait.stop();
                finale.placeModes();
            }
        });
        wait.start();
        return true;
    }

    /** Asks to close the pilot's virtual fleet; closed: true (its drones and announcement gone, up to 8 s). */
    private boolean closePilotsFleet() {
        Object[] options = {"Close it", "Cancel"};
        int answer = JOptionPane.showOptionDialog(main, "The virtual fleet is open.\nClose it for the tour's drones?",
                "Virtual fleet", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer != JOptionPane.YES_OPTION) return false;
        try {
            de.droiddrone.pc.fleet.VirtualFleet.requestStop();
        } catch (java.io.IOException e) {
            main.logLine("Virtual fleet: can't ask it to close: " + e.getMessage());
            return false;
        }
        java.awt.Cursor was = main.getCursor();
        main.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        boolean gone;
        try {
            gone = waitUntil(() -> de.droiddrone.pc.fleet.VirtualFleet.announcedNow().isEmpty(), 8000);
        } finally {
            main.setCursor(was);
        }
        main.logLine(gone ? "Virtual fleet closed for the tour." : "Virtual fleet didn't close: close its window.");
        return gone;
    }

    /** Waits off the Swing thread (the window keeps painting) until ok, at most ms; whether it came. */
    static boolean waitUntil(BooleanSupplier ok, long ms) {
        java.awt.SecondaryLoop loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        java.util.concurrent.atomic.AtomicBoolean came = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            long end = System.currentTimeMillis() + ms;
            try {
                while (System.currentTimeMillis() < end) {
                    if (ok.getAsBoolean()) {
                        came.set(true);
                        return;
                    }
                    Thread.sleep(200);
                }
            } catch (InterruptedException ignored) {
                // over
            } finally {
                loop.exit();
            }
        }, "tourWait");
        t.setDaemon(true);
        t.start();
        loop.enter();
        return came.get();
    }

    /** The BTR sets off at the pilot's NEXT on the eyes. */
    private void driveIn() {
        if (!finale.started()) return;
        finale.driveIn();
        main.logLine("Tour: the BTR sets off.");
    }

    /** The map on the tour's drones, their flights and the units, before anything moves. */
    private void showTourArea() {
        if (!finale.started()) return;
        double[] c = finale.centre();
        main.mapWindow().map().setFollow(false);
        main.mapWindow().map().centreOn(c[0], c[1], 14.4);
    }

    /** An open SETUP / VIDEO / LINK / TOOLS list goes before the next step. */
    private void closeMenus() {
        MenuSelectionManager.defaultManager().clearSelectedPath();
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
        for (String f : features) what.add(switch (f) {
            case FEATURE_NAMES -> "phone by name";
            case FEATURE_MAP_TOOLS -> "map tools";
            case FEATURE_RADIO_HORIZON -> "radio horizon, GCS search";
            case FEATURE_COVERAGE -> "coverage map";
            case FEATURE_OVERWATCH -> "OVERWATCH";
            case FEATURE_OBSERVATION -> "observation";
            case FEATURE_FLEET -> "fleet management";
            case FEATURE_FLIGHTS -> "previous flights";
            case FEATURE_SPECTATOR -> "spectator mirror";
            default -> f;
        });
        Object[] options = {"Yes", "Not now"};
        int answer = JOptionPane.showOptionDialog(main, "New: " + String.join(", ", what)
                        + ".\nTour the new parts?", "What's new", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer == JOptionPane.YES_OPTION) run(new Tour(main, features), 0);
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
        run(new Tour(main, null), 0);
    }

    /**
     * SETUP > Overwatch demo (TOOLS > Advanced, 1.0.52, the pilot): the tour from its FLEET step - the four drones,
     * the eyes, the BTR, the alert, CONNECT, the flight and the hit - with the same clean-up at the end.
     */
    static void demo(MainWindow main) {
        Tour t = new Tour(main, null);
        t.demo = true;
        run(t, Math.max(0, t.fleetStepIndex()));
    }

    /** The FLEET step's index: where the Overwatch demo starts. */
    int fleetStepIndex() {
        return fleetStepIndex(steps);
    }

    static int fleetStepIndex(List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) if ("FLEET".equals(steps.get(i).title)) return i;
        return -1;
    }

    private static void run(Tour t, int from) {
        if (current != null) current.end(); // never two at once: each would dim and tick over the other
        current = t;
        if (from == 0) t.main.tourExpandController(); // open, so the pilot folds it when asked
        t.main.tourHideSleep(true); // the sleep row shows at its own step (the pilot)
        t.index = from - 1;
        t.next();
    }

    private void next() {
        if (notice != null) notice.dismiss();
        notice = null;
        movedOn();
        after();
        index++;
        tries = 0;
        generation++;
        for (; index < steps.size(); index++) {
            Step c = steps.get(index);
            if (c.finale && !finaleTried) {
                finaleTried = true;
                if (!startFinale() && demo) {
                    end("no tour drones");
                    return;
                }
            }
            if (c.skip == null || !c.skip.getAsBoolean()) break;
        }
        if (index >= steps.size()) {
            finished();
            return;
        }
        Step s = steps.get(index);
        if (s.before != null) {
            try {
                s.before.run();
            } catch (RuntimeException e) {
                main.logLine("Tour: step " + (index + 1) + " (" + s.title + ") failed to open: " + e);
            }
        }
        if (s.transition != null) {
            int g = generation;
            try {
                s.transition.accept(() -> {
                    if (g == generation) retry.restart();
                });
            } catch (RuntimeException e) {
                retry.restart();
            }
            return;
        }
        retry.restart(); // the window just opened: let it lay out first
    }

    /** EXIT TOUR, or a window gone: over, cleanly. */
    private void end() {
        end(notice != null && notice.endReason != null ? notice.endReason : "a new tour started");
    }

    private void end(String why) {
        if (index >= 0 && index < steps.size()) {
            // said in the log, so a tour that "skipped" its end can be told from one that was closed
            main.logLine("Tour: ended at step " + (index + 1) + " (" + steps.get(index).title + "): " + why + ".");
        }
        if (notice != null) notice.dismiss();
        notice = null;
        after();
        index = steps.size();
        generation++;
        retry.stop();
        finished();
    }

    /** Whatever way it ended: the example pins and the tour's drones go, the window is as before. */
    private void finished() {
        clearExamplePins();
        finale.close();
        spectatorPart.close();
        main.tourHideSleep(false);
        MapWindow m = main.mapWindowIfOpen();
        if (m != null) m.tourWindowedEnd();
        if (current == this) current = null;
    }

    /**
     * The pilot moved on from the step: what it starts on NEXT (the BTR at THE EYES). Not on the tour's end: closed
     * there (the X), it sent the BTR off as the drones were being removed (the log, 2026-11-09).
     */
    private void movedOn() {
        if (index < 0 || index >= steps.size() || steps.get(index).onNext == null) return;
        try {
            steps.get(index).onNext.run();
        } catch (RuntimeException e) {
            main.logLine("Tour: step " + (index + 1) + " could not go on: " + e);
        }
    }

    /** The step that is over undoes what it set up (the sleep buttons' demo, the open flights window). */
    private void after() {
        if (index < 0 || index >= steps.size() || steps.get(index).after == null) return;
        try {
            steps.get(index).after.run();
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
            anchor = s.anchor.get();
        } catch (RuntimeException e) {
            // the window isn't there (yet): as not showing
        }
        // hidden on purpose (UPDATE with no update known): skipped at once - waiting for it was the pause before MINIMIZE
        if (anchor != null && !anchor.isVisible()) {
            main.logLine("Tour: step " + (index + 1) + " (" + s.title + ") skipped: not shown.");
            next();
            return;
        }
        if (anchor == null || !anchor.isShowing()) {
            if (++tries >= MAX_TRIES) { // not on screen (a window that didn't open): skipped
                main.logLine("Tour: step " + (index + 1) + " (" + s.title + ") skipped: not on screen.");
                next();
                return;
            }
            retry.restart();
            return;
        }
        List<JComponent> targets = null;
        if (s.targets != null) {
            try {
                targets = s.targets.get();
            } catch (RuntimeException e) {
                // the lit area then
            }
        }
        boolean centred = s.legend || s.finish;
        notice = new TourNotice(anchor, s, index + 1, steps.size(), this::next, this::end, centred, targets, noticePlace);
        if (s.live != null) {
            try {
                notice.setLive(s.live.get());
            } catch (RuntimeException e) {
                // no live area then
            }
        }
        if (s.extra != null) {
            try {
                notice.setExtra(s.extra.get());
            } catch (RuntimeException e) {
                // the one control then
            }
        }
        if (anchor != null && SwingUtilities.getWindowAncestor(anchor) instanceof MapWindow m) m.tourWindowed();
        if (!notice.pop()) retry.restart();
    }

    /**
     * Where the pilot put the tour's box and how wide it was made (2026-11-09, the pilot: "make it possible to resize
     * and move the pop up window for the MAP during the tour" - the box sat over the map): kept for the rest of the
     * tour, the place per window. Double-click the box: back where the tour puts it.
     */
    static final class NoticePlace {
        int width = TourNotice.WIDTH;
        final java.util.Map<Component, java.awt.Point> at = new java.util.WeakHashMap<>();
    }

    /**
     * The notice: pointer at the control, the text, STEP i OF n, NEXT across the bottom and a cross top right that
     * exits the tour; the rest of the window dims. Centred (the welcome, the finish): a box in the middle, no pointer.
     * Dragged, it stays where the pilot put it (no pointer then); its bottom right corner makes it wider or narrower.
     */
    static final class TourNotice extends JComponent {
        static final int WIDTH = 460;
        /** The narrowest and widest the pilot can make it. */
        static final int MIN_WIDTH = 320, MAX_WIDTH = 900;
        /** The resize grip in the bottom right corner. */
        private static final int GRIP = 14;
        private static final int PAD = 12;
        private static final int POINTER = 8;
        private static final int GAP = 6;
        /** The exit cross's box, top right. */
        private static final int CLOSE = 24;
        private static final long IN_MS = 180;
        /** The lit area's ring closes in from this far over its first IN_FOCUS_MS: the eye goes to it. */
        private static final int FOCUS_FROM = 46;
        private static final long FOCUS_MS = 450;
        /** A press outside the lit area: rings run out from it this long. */
        private static final long NUDGE_MS = 750;
        /** The pulse's frame time: the ring alone is repainted, not the window (1.0.52: the tour's lag). */
        private static final int TICK_MS = 40;
        /** The legend's rows (the welcome). */
        private static final int LEGEND_ROW = 30;
        /** The control hidden this long (a card folded): the step moves on. */
        private static final long HIDDEN_MS = 2500;
        /** Its window gone this long (closed): the tour ends. */
        private static final long WINDOW_GONE_MS = 3000;

        private final JComponent anchor;
        private final String title;
        private final String text, tip;
        private List<String> lines;
        /** The tip under the text, wrapped; empty for none. */
        private List<String> tipLines;
        /** Where the pilot put it and how wide (shared by the tour's notices). */
        private final NoticePlace spot;
        /** Placed by the pilot (dragged): no pointer, not following the control. */
        private boolean moved;
        private final String counter;
        private final JButton next = Theme.primaryButton("Next");
        private final JButton exit = new CloseButton();
        private final Runnable onEnd;
        private final Dim dim = new Dim();
        private final Timer timer = new Timer(TICK_MS, e -> tick());
        private final boolean centred, legend, finish;
        private final List<JComponent> targets;
        private final BooleanSupplier waitFor;
        private final boolean auto;
        private long shownAt;
        private long nudgeAt = -1;
        private boolean above;
        private long notShowingSince;
        /** NEXT is locked until the control was pressed: a mouse button let go over it (or its button fired). */
        private boolean waitingForPress;
        /** NEXT is locked until waitFor says so. */
        private boolean waitingForEvent;
        private final java.awt.event.AWTEventListener pressWatch = this::watchPress;
        private final java.awt.event.ActionListener firedWatch = e -> pressed();
        /** Repaints asked of the window per tick (for the lag test). */
        int lastTickDirtyArea;

        private void watchPress(java.awt.AWTEvent event) {
            if (event.getID() != MouseEvent.MOUSE_RELEASED || !(event.getSource() instanceof Component c)) return;
            if (isTarget(c)) pressed();
        }

        private boolean isTarget(Component c) {
            if (targets != null) {
                for (JComponent t : targets) if (t != null && (c == t || SwingUtilities.isDescendingFrom(c, t))) return true;
                return false;
            }
            if (c == anchor || SwingUtilities.isDescendingFrom(c, anchor)) return true;
            JComponent e = extra;
            return e != null && (c == e || SwingUtilities.isDescendingFrom(c, e));
        }

        TourNotice(JComponent anchor, Step s, int step, int of, Runnable onNext, Runnable onEnd, boolean centred,
                   List<JComponent> targets) {
            this(anchor, s, step, of, onNext, onEnd, centred, targets, new NoticePlace());
        }

        TourNotice(JComponent anchor, Step s, int step, int of, Runnable onNext, Runnable onEnd, boolean centred,
                   List<JComponent> targets, NoticePlace spot) {
            this.anchor = anchor;
            this.spot = spot;
            this.text = s.text;
            this.tip = s.tip;
            this.title = s.title;
            this.onEnd = onEnd;
            this.centred = centred;
            this.legend = s.legend;
            this.finish = s.finish;
            this.targets = targets;
            this.waitFor = s.waitFor;
            this.auto = s.auto;
            this.reopen = s.reopen;
            counter = s.finish || s.legend ? "" : "STEP " + step + " OF " + of;
            setOpaque(false);
            setLayout(null);
            wrapText();
            next.setText(step == of ? "Done" : s.legend ? "Start" : "Next");
            next.addActionListener(e -> onNext.run());
            exit.addActionListener(e -> {
                endReason = "closed with the X";
                onEnd.run();
            });
            exit.setVisible(step != of);
            add(next);
            add(exit);
            if (!centred) {
                MouseAdapter grab = new Grab();
                addMouseListener(grab);
                addMouseMotionListener(grab);
            }
            waitingForPress = s.press;
            waitingForEvent = s.waitFor != null;
            if (waitingForEvent) {
                next.setEnabled(false);
                next.setText(s.waitText == null ? "Wait" : s.waitText);
            } else if (waitingForPress) {
                next.setEnabled(false);
                next.setText("Press it");
            }
        }

        /** An area the mouse still works over while this step shows (the map under the map steps); null = none. */
        private JComponent live;
        /** A second control lit with the first: the box covers both; null = just the one. */
        private JComponent extra;

        void setLive(JComponent live) {
            this.live = live;
        }

        void setExtra(JComponent extra) {
            this.extra = extra;
        }

        private int boxWidth() {
            return centred ? WIDTH : spot.width;
        }

        private void wrapText() {
            int w = boxWidth() - 2 * PAD;
            lines = wrap(text, getFontMetrics(textFont()), w);
            tipLines = tip == null || tip.isBlank() ? List.of() : wrap(tip, getFontMetrics(tipFont()), w);
        }

        /** The window it is in (the key of the pilot's place for it): its top component, or null. */
        private Component window() {
            Component top = anchor.getParent();
            if (top == null) return null;
            while (top.getParent() != null && !(top instanceof Window)) top = top.getParent();
            return top;
        }

        private boolean inGrip(java.awt.Point p) {
            return p.x >= getWidth() - GRIP && p.y >= getHeight() - GRIP;
        }

        /** The box dragged by the pilot: moved anywhere in the window, or wider/narrower by its corner. */
        private final class Grab extends MouseAdapter {
            private java.awt.Point from;
            private Rectangle start;
            private boolean resizing;

            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                from = e.getLocationOnScreen();
                start = getBounds();
                resizing = inGrip(e.getPoint());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (from == null) return;
                int dx = e.getXOnScreen() - from.x, dy = e.getYOnScreen() - from.y;
                if (resizing) resizeTo(start.width + dx);
                else moveTo(start.x + dx, start.y + dy);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                from = null;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setCursor(java.awt.Cursor.getPredefinedCursor(inGrip(e.getPoint()) ? java.awt.Cursor.SE_RESIZE_CURSOR
                        : java.awt.Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !inGrip(e.getPoint())) home();
            }
        }

        /** Moved by the pilot to x, y (the layer's): kept there for the rest of the tour in this window. */
        void moveTo(int x, int y) {
            Component w = window();
            if (w == null) return;
            spot.at.put(w, new java.awt.Point(x, y));
            place();
            repaint();
        }

        /** Made this wide by the pilot (within MIN_WIDTH..MAX_WIDTH and the window): kept for the rest of the tour. */
        void resizeTo(int width) {
            Component layer = getParent();
            int max = Math.min(MAX_WIDTH, layer == null ? MAX_WIDTH : Math.max(MIN_WIDTH, layer.getWidth() - 8));
            int w = Math.max(MIN_WIDTH, Math.min(max, width));
            if (w == spot.width) return;
            spot.width = w;
            wrapText();
            place();
            repaint();
        }

        /** Back where the tour puts it (a double-click). */
        void home() {
            Component w = window();
            if (w != null) spot.at.remove(w);
            place();
            repaint();
        }

        /** Tests: where the box is now, and whether it was placed by the pilot. */
        boolean movedForTest() {
            return moved;
        }

        /** The top of the window's content in the layer: under the title bar, which stays free for moving and resizing the window. */
        private int contentTop() {
            JRootPane root = SwingUtilities.getRootPane(this);
            Component layer = getParent();
            if (root == null || layer == null || root.getContentPane().getParent() == null) return 0;
            java.awt.Container cp = root.getContentPane();
            return Math.max(0, SwingUtilities.convertPoint(cp.getParent(), cp.getX(), cp.getY(), layer).y);
        }

        /** The control was pressed (a press step): when it then goes from the window, the step moves on at once. */
        private boolean pressDone;
        /** Why the tour ended from this notice (the X, a closed window), for the log; null = not from here. */
        String endReason;
        /** The step's window gone: opened again once instead of ending the tour (null: ends it). */
        private final Runnable reopen;
        private boolean reopened;

        private void pressed() {
            if (!waitingForPress) return;
            waitingForPress = false;
            pressDone = true;
            if (waitingForEvent) return;
            next.setEnabled(true);
            next.setText("Next");
            repaint();
        }

        boolean pop() {
            JRootPane root = SwingUtilities.getRootPane(anchor);
            if (root == null || !anchor.isShowing()) return false;
            if (waitingForPress) {
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(pressWatch, java.awt.AWTEvent.MOUSE_EVENT_MASK);
                if (targets != null) {
                    for (JComponent t : targets) if (t instanceof javax.swing.AbstractButton b) b.addActionListener(firedWatch);
                } else if (anchor instanceof javax.swing.AbstractButton b) b.addActionListener(firedWatch);
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
            if (targets != null) {
                for (JComponent t : targets) if (t instanceof javax.swing.AbstractButton b) b.removeActionListener(firedWatch);
            } else if (anchor instanceof javax.swing.AbstractButton b) b.removeActionListener(firedWatch);
            for (JComponent c : new JComponent[]{this, dim}) {
                Component parent = c.getParent();
                if (parent == null) continue;
                Rectangle r = c.getBounds();
                ((JComponent) parent).remove(c);
                parent.repaint(r.x, r.y, r.width, r.height);
            }
        }

        private void tick() {
            // what the step waits for came: on, even with its control gone (CONNECT closes the alert card - the step
            // kept saying "Press CONNECT", 1.0.52)
            if (waitingForEvent && auto && waitFor.getAsBoolean()) {
                waitingForEvent = false;
                next.setEnabled(true);
                next.doClick();
                return;
            }
            if (!anchor.isShowing()) {
                // pressed, and the press took the control away (POP OUT MAP hides its card): on at once, not after
                // HIDDEN_MS - the pause the pilot saw
                if (pressDone && next.isEnabled()) {
                    next.doClick();
                    return;
                }
                if (notShowingSince == 0) notShowingSince = System.currentTimeMillis();
                long hidden = System.currentTimeMillis() - notShowingSince;
                Window w = SwingUtilities.getWindowAncestor(anchor);
                boolean windowGone = w == null || !w.isDisplayable() || !w.isVisible();
                if (windowGone && hidden > WINDOW_GONE_MS && reopen != null && !reopened) {
                    // the map window's steps: its window opened again, never the fleet part lost to a closed map
                    reopened = true;
                    notShowingSince = 0;
                    reopen.run();
                } else if (windowGone && hidden > WINDOW_GONE_MS) {
                    endReason = "its window was closed";
                    onEnd.run(); // its window was closed: the tour is over
                }
                else if (!windowGone && hidden > HIDDEN_MS && next.isEnabled()) next.doClick(); // only the control went (folded away): move on
                return;
            }
            notShowingSince = 0;
            if (waitingForEvent && waitFor.getAsBoolean()) {
                waitingForEvent = false;
                if (auto) {
                    next.setEnabled(true);
                    next.doClick();
                    return;
                }
                if (!waitingForPress) {
                    next.setEnabled(true);
                    next.setText("Next");
                    repaint();
                }
            }
            Component layer = getParent();
            boolean full = false;
            if (layer != null && !dim.getBounds().equals(new Rectangle(0, 0, layer.getWidth(), layer.getHeight()))) {
                dim.setBounds(0, 0, layer.getWidth(), layer.getHeight());
                full = true;
            }
            Rectangle before = getBounds();
            place();
            if (!getBounds().equals(before)) full = true;
            long now = System.currentTimeMillis();
            if (now - shownAt < Math.max(IN_MS, FOCUS_MS) + TICK_MS || (nudgeAt >= 0 && now - nudgeAt < NUDGE_MS + TICK_MS)) {
                full = true; // fading in, closing in, rings running out: the whole dim
                repaint();
            }
            lastTickDirtyArea = repaintDim(full);
        }

        /**
         * 1.0.52 (the pilot: "if the tour is open for a while it starts to lag the program"): the dim covers the whole
         * window, so its repaint 30 times a second repainted everything under it - the video, the map with its tiles,
         * the fleet panel - for as long as the tour ran. Now only the pulsing ring's band is repainted once the dim is
         * in; the whole dim only while it fades in, a ring closes in or a nudge runs. Returns the area asked for.
         */
        private int repaintDim(boolean full) {
            if (full || centred) {
                if (centred && !full && !finish) return 0; // the welcome holds still once in
                dim.repaint();
                return dim.getWidth() * dim.getHeight();
            }
            Rectangle band = ringBand(anchorBounds());
            if (band == null) return 0;
            dim.repaint(band);
            return band.width * band.height;
        }

        /** Tests and pictures: on this layer as pop() puts it, without a window on screen; the step came up long ago. */
        void attachForTest(JLayeredPane layer, long shownAgoMs) {
            layer.add(dim, JLayeredPane.MODAL_LAYER);
            layer.add(this, NOTICE_LAYER);
            dim.setBounds(0, 0, layer.getWidth(), layer.getHeight());
            place();
            shownAt = System.currentTimeMillis() - shownAgoMs;
        }

        /** Tests: what one tick asks to repaint once the step is in (full: as every tick did before 1.0.52). */
        int tickRepaintArea(boolean full) {
            return repaintDim(full);
        }

        /** Tests: one tick, as the timer runs it. */
        void tickForTest() {
            tick();
        }

        /** Tests: its control has been gone this long. */
        void goneForTest(long ms) {
            notShowingSince = System.currentTimeMillis() - ms;
        }

        /** Tests: the lit control pressed. */
        void pressForTest() {
            pressed();
        }

        /** Pictures: the notice and its dim painted into g at the layer's origin. */
        void paintForTest(Graphics2D g) {
            dim.paint(g);
            Graphics2D n = (Graphics2D) g.create(getX(), getY(), getWidth(), getHeight());
            try {
                paint(n);
            } finally {
                n.dispose();
            }
        }

        /** Where the pulsing ring paints: the lit area's outline, a few pixels either side. */
        static Rectangle ringBand(Rectangle lit) {
            if (lit == null) return null;
            return new Rectangle(lit.x - 10, lit.y - 10, lit.width + 20, lit.height + 20);
        }

        private int legendHeight() {
            return legend ? 2 * LEGEND_ROW + 8 : 0;
        }

        private int height() {
            int pointer = centred || moved ? 0 : POINTER;
            return pointer + 2 * PAD + titleHeight() + 6 + lines.size() * lineHeight() + tipHeight() + legendHeight() + 12 + 34 + 4;
        }

        /** Under the control, or above it when there is no room below; inside the window. Centred: the middle. */
        private boolean place() {
            Component layer = getParent();
            if (layer == null) return false;
            Component win = centred ? null : window();
            java.awt.Point put = win == null ? null : spot.at.get(win);
            moved = put != null;
            int width = Math.min(boxWidth(), Math.max(MIN_WIDTH, layer.getWidth()));
            int h = height();
            int topLimit = contentTop(); // the title bar stays free: the window moves and resizes during the tour
            int x, y;
            if (centred) {
                x = Math.max(0, (layer.getWidth() - width) / 2);
                y = Math.max(0, (layer.getHeight() - h) / 2);
                above = false;
            } else if (moved) {
                x = put.x;
                y = put.y;
                above = false;
            } else {
                Rectangle a = anchorBounds();
                if (a == null) return false;
                x = a.x + a.width / 2 - width / 2;
                above = a.y + a.height + GAP + h > layer.getHeight() && a.y - GAP - h >= topLimit;
                y = above ? a.y - GAP - h : a.y + a.height + GAP;
            }
            x = Math.max(0, Math.min(x, layer.getWidth() - width));
            y = Math.max(centred ? 0 : topLimit, Math.min(y, layer.getHeight() - h));
            Rectangle r = new Rectangle(x, y, width, h);
            if (!r.equals(getBounds())) setBounds(r);
            int top = above || centred || moved ? 0 : POINTER;
            int by = top + 2 * PAD + titleHeight() + 6 + lines.size() * lineHeight() + tipHeight() + legendHeight() + 8 - PAD;
            next.setBounds(PAD, by, width - 2 * PAD, 30); // across the box: the exit is the cross top right
            exit.setBounds(width - PAD - CLOSE + 6, top + PAD - 6, CLOSE, CLOSE);
            return true;
        }

        /** The lit area: the control, and the second one with it when there is one (their union); null when centred. */
        private Rectangle anchorBounds() {
            if (centred) return null;
            Component layer = getParent();
            if (layer == null || anchor.getParent() == null) return null;
            Rectangle r = SwingUtilities.convertRectangle(anchor.getParent(), anchor.getBounds(), layer);
            JComponent e = extra;
            if (e != null && e.isVisible() && e.getParent() != null
                    && SwingUtilities.getRootPane(e) == SwingUtilities.getRootPane(anchor)) {
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
                if (centred) { // grows into place from the middle
                    double s = 0.92 + 0.08 * easeIn;
                    g.translate(w / 2.0, h / 2.0);
                    g.scale(s, s);
                    g.translate(-w / 2.0, -h / 2.0);
                } else {
                    g.translate(0, Math.round((above ? 6 : -6) * (1 - easeIn)));
                }
                boolean pointer = !centred && !moved;
                int top = above || !pointer ? 0 : POINTER, boxH = h - (pointer ? POINTER : 0) - 1;
                Rectangle a = anchorBounds();
                int tipX = a == null ? w / 2 : Math.min(Math.max(a.x + a.width / 2 - getX(), PAD + POINTER), w - PAD - POINTER);
                Path2D box = new Path2D.Float();
                box.moveTo(0, top);
                if (!above && pointer) {
                    box.lineTo(tipX - POINTER, top);
                    box.lineTo(tipX, 0);
                    box.lineTo(tipX + POINTER, top);
                }
                box.lineTo(w - 1, top);
                box.lineTo(w - 1, top + boxH);
                if (above && pointer) {
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
                if (legend) paintLegend(g, y + 8);
                if (!centred) { // the grip: drag the corner to make the box wider or narrower
                    g.setColor(Theme.MUTED);
                    g.setStroke(new BasicStroke(1.2f));
                    int gx = w - 3, gy = top + boxH - 2;
                    for (int i = 1; i <= 3; i++) g.drawLine(gx - 3 * i, gy, gx, gy - 3 * i);
                }
            } finally {
                g.dispose();
            }
        }

        /** The welcome's legend: a black PRESS IT (press the lit control) and the cross (ends the tour), drawn as they look. */
        private void paintLegend(Graphics2D g, int y) {
            int chipW = 96, chipH = 22;
            g.setFont(Theme.headingFont(11f));
            FontMetrics fm = g.getFontMetrics();
            // the locked NEXT: black
            g.setColor(Theme.BG);
            g.fillRect(PAD + 2, y, chipW, chipH);
            g.setColor(Theme.LINE);
            g.drawRect(PAD + 2, y, chipW, chipH);
            g.setColor(Theme.MUTED);
            String p = "PRESS IT";
            g.drawString(p, PAD + 2 + (chipW - fm.stringWidth(p)) / 2, y + (chipH + fm.getAscent()) / 2 - 2);
            g.setFont(textFont());
            g.setColor(Theme.TEXT);
            g.drawString("Black: press the lit control.", PAD + chipW + 14, y + (chipH + g.getFontMetrics().getAscent()) / 2 - 2);
            y += LEGEND_ROW;
            // the cross
            int cx = PAD + 2 + chipW / 2, cy = y + chipH / 2, r = 5;
            g.setColor(Theme.MUTED);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(cx - r, cy - r, cx + r, cy + r);
            g.drawLine(cx + r, cy - r, cx - r, cy + r);
            g.setColor(Theme.TEXT);
            g.drawString("The x top right ends the tour.", PAD + chipW + 14, y + (chipH + g.getFontMetrics().getAscent()) / 2 - 2);
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
         * Dims the window but the control, which gets a pulsing ring that closes in on it as the step comes up. The
         * mouse is dead everywhere but over the control and the live area (it owns every point outside them, and
         * listens, so clicks end here - and ring the lit control: "press here"); keyboard shortcuts still work.
         */
        private final class Dim extends JComponent {
            Dim() {
                setOpaque(false);
                MouseAdapter swallow = new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (centred) return;
                        nudgeAt = System.currentTimeMillis();
                        repaint();
                    }
                };
                addMouseListener(swallow);
                addMouseMotionListener(swallow);
                addMouseWheelListener(swallow);
            }

            /** The window's edges, left to the window (resizing) through the dim. */
            private static final int EDGE = 6;

            /**
             * Where the dim lies: the window's content, not the title bar above it (1.0.52, the pilot: "during the
             * entire tour make it possible to resize the window" - the dim over FlatLaf's title bar took its drag,
             * double-click and the resize band at the top).
             */
            private Rectangle content() {
                Rectangle all = new Rectangle(0, 0, getWidth(), getHeight());
                JRootPane root = SwingUtilities.getRootPane(this);
                Component layer = getParent();
                if (root == null || layer == null || root.getContentPane().getParent() == null) return all;
                java.awt.Container cp = root.getContentPane();
                return SwingUtilities.convertRectangle(cp.getParent(), cp.getBounds(), layer).intersection(all);
            }

            @Override
            public boolean contains(int x, int y) {
                if (x < EDGE || y < EDGE || x >= getWidth() - EDGE || y >= getHeight() - EDGE) return false;
                if (!content().contains(x, y)) return false; // the title bar: drag, maximize, resize as always
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
                Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
                try {
                    long now = System.currentTimeMillis();
                    float in = Math.min(1, (now - shownAt) / (float) IN_MS);
                    Rectangle a = anchorBounds();
                    Area shade = new Area(content()); // the title bar stays clear: it works during the tour
                    RoundRectangle2D hole = null;
                    if (a != null) {
                        hole = new RoundRectangle2D.Float(a.x - 6, a.y - 6, a.width + 12, a.height + 12, 10, 10);
                        shade.subtract(new Area(hole));
                        JComponent l = live; // the live area (the header on the menu steps, the map on the map steps) stays clear too
                        if (l != null && l.isShowing() && l.getParent() != null) {
                            Rectangle r = SwingUtilities.convertRectangle(l.getParent(), l.getBounds(), getParent());
                            shade.subtract(new Area(new Rectangle(r.x - 2, r.y - 2, r.width + 4, r.height + 4)));
                        }
                    }
                    g.setColor(Theme.alpha(java.awt.Color.BLACK, Math.round((centred ? 170 : 150) * in)));
                    g.fill(shade);
                    if (finish) paintBurst(g, now);
                    if (hole == null) return;
                    // the ring closes in on the lit area as the step comes up, then pulses on it
                    float focus = Math.min(1, (now - shownAt) / (float) FOCUS_MS);
                    float grow = (1 - focus) * (1 - focus) * FOCUS_FROM;
                    double pulse = 0.5 + 0.5 * Math.sin((now - shownAt) / 320.0);
                    g.setColor(Theme.alpha(Theme.ACCENT, (int) Math.round((120 + 135 * pulse) * in)));
                    g.setStroke(new BasicStroke(2.5f));
                    g.draw(grow < 0.5f ? hole : new RoundRectangle2D.Float(a.x - 6 - grow, a.y - 6 - grow,
                            a.width + 12 + 2 * grow, a.height + 12 + 2 * grow, 10 + grow, 10 + grow));
                    // a press elsewhere: three rings run out from the lit area - here
                    if (nudgeAt >= 0 && now - nudgeAt < NUDGE_MS) {
                        float t = (now - nudgeAt) / (float) NUDGE_MS;
                        for (int i = 0; i < 3; i++) {
                            float ti = t - i * 0.18f;
                            if (ti <= 0 || ti >= 1) continue;
                            float d = 6 + ti * 40;
                            g.setColor(Theme.alpha(Theme.ACCENT, Math.round(230 * (1 - ti))));
                            g.setStroke(new BasicStroke(3f * (1 - ti) + 1));
                            g.draw(new RoundRectangle2D.Float(a.x - d, a.y - d, a.width + 2 * d, a.height + 2 * d, 10 + d, 10 + d));
                        }
                    }
                } finally {
                    g.dispose();
                }
            }

            /**
             * The finish (1.0.52, the pilot: a new one): a hit. The window's four corner brackets close in on the box
             * and a reticle narrows on its middle (0.9 s); then the impact - a white flash ring and shards flying out -
             * and the brackets breathing on its corners (no radar sweep: the pilot).
             */
            private void paintBurst(Graphics2D g, long now) {
                Rectangle box = TourNotice.this.getBounds();
                double cx = box.getCenterX(), cy = box.getCenterY();
                double w = getWidth(), h = getHeight(), max = Math.hypot(w, h);
                long t = now - shownAt;
                double close = ease(Math.min(1, t / 900.0)); // 0 at the window's corners, 1 on the box
                // the brackets: from the window's corners to the box's, breathing once there
                double breathe = t > 900 ? 3 + 3 * Math.sin((t - 900) / 380.0) : 0;
                double[][] from = {{12, 12}, {w - 12, 12}, {12, h - 12}, {w - 12, h - 12}};
                double[][] to = {{box.x - 14 - breathe, box.y - 14 - breathe}, {box.getMaxX() + 14 + breathe, box.y - 14 - breathe},
                        {box.x - 14 - breathe, box.getMaxY() + 14 + breathe}, {box.getMaxX() + 14 + breathe, box.getMaxY() + 14 + breathe}};
                g.setColor(Theme.ACCENT);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
                for (int i = 0; i < 4; i++) {
                    double x = from[i][0] + (to[i][0] - from[i][0]) * close, y = from[i][1] + (to[i][1] - from[i][1]) * close;
                    double sx = i % 2 == 0 ? 1 : -1, sy = i < 2 ? 1 : -1, arm = 26;
                    java.awt.geom.Path2D b = new java.awt.geom.Path2D.Double();
                    b.moveTo(x, y + sy * arm);
                    b.lineTo(x, y);
                    b.lineTo(x + sx * arm, y);
                    g.draw(b);
                }
                if (t < 1100) { // the reticle narrows on the middle, then goes at the hit
                    double r = 30 + (1 - close) * max / 3;
                    g.setColor(Theme.alpha(Theme.ACCENT, (int) Math.round(200 * Math.min(1, t / 200.0))));
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
                    g.draw(new java.awt.geom.Line2D.Double(cx - r - 18, cy, cx - r * 0.55, cy));
                    g.draw(new java.awt.geom.Line2D.Double(cx + r * 0.55, cy, cx + r + 18, cy));
                    g.draw(new java.awt.geom.Line2D.Double(cx, cy - r - 18, cx, cy - r * 0.55));
                    g.draw(new java.awt.geom.Line2D.Double(cx, cy + r * 0.55, cx, cy + r + 18));
                }
                long hit = t - 900;
                if (hit >= 0 && hit < 1400) { // the impact: a flash ring and shards
                    double p = hit / 1400.0, q = ease(p);
                    double r = 20 + q * max * 0.55;
                    g.setColor(Theme.alpha(java.awt.Color.WHITE, (int) Math.round(230 * (1 - p))));
                    g.setStroke(new BasicStroke((float) (6 * (1 - p) + 1)));
                    g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
                    java.util.Random shards = new java.util.Random(4609);
                    g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    for (int i = 0; i < 28; i++) {
                        double a = shards.nextDouble() * 2 * Math.PI, v = 0.35 + shards.nextDouble() * 0.65, len = 10 + shards.nextDouble() * 22;
                        double d = 30 + q * v * max * 0.6;
                        g.setColor(Theme.alpha(i % 3 == 0 ? java.awt.Color.WHITE : Theme.ACCENT, (int) Math.round(220 * (1 - p))));
                        g.draw(new java.awt.geom.Line2D.Double(cx + Math.cos(a) * d, cy + Math.sin(a) * d,
                                cx + Math.cos(a) * (d + len), cy + Math.sin(a) * (d + len)));
                    }
                }
            }

            private double ease(double x) {
                return 1 - Math.pow(1 - x, 3);
            }
        }
    }
}
