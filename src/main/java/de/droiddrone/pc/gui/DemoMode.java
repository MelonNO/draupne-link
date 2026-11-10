package de.droiddrone.pc.gui;

import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.TourScenario;
import de.droiddrone.pc.fleet.VirtualFleet;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Point;

/**
 * DEMO mode (2026-11-10, the pilot: "a DEMO mode where the main purpose is to use it to demo what capabilities this SW
 * has ... reuse as much of the TOUR as possible"): every virtual drone with its scene up at once, nothing dimmed and
 * nothing in the way - the pilot shows PILOT, OBSERVER and SPECTATOR mode as they like, a real phone's feed too.
 * <ul>
 * <li>At the start, in one virtual fleet: the tour's six with their scenes ({@link TourFinale#startShowcase}), and a
 * virtual pilot PC flying ({@link TourSpectator}) with the three drones it observes when asked. Watching it as
 * SPECTATOR brings a card ({@link Card}) that switches it to observing and back - the card takes no other click.</li>
 * <li>FLEET MANAGEMENT gets a strip ({@link #strip}): SET UP SCENARIO puts out the tour's four - three overwatching,
 * one asleep - with their camera views, flights and units on the map; once the overwatch has settled, START SCENARIO
 * sets off what comes (the pilot: not named - a surprise for the viewers); the alert, CONNECT and the flight as any.
 * A crash leaves everything as it is. SCENARIO AGAIN: the four anew, the rest stays.</li>
 * <li>SETUP > End demo ({@link #close}): every demo drone, flight, unit, mark and setting it left goes, as the tour's
 * clean-up does ({@link TourHost#cleanUp}).</li>
 * </ul>
 * Not with the pilot's own virtual fleet running (both would write the one announcement file), nor with the tour.
 */
final class DemoMode implements AutoCloseable {
    static final String PREFIX = "Demo: ";
    /** After SET UP: the four take their modes this much later (the PHONE list and the map know them by then). */
    static final long PLACE_AFTER_MS = 3000;

    /** Where the scenario is: nothing out, the four out, overwatch settling, ready, running. */
    enum Stage { NONE, SETTING_UP, SETTLING, READY, RUNNING }

    private final MainWindow main;
    private Fleet fleet;
    private final TourFinale parts;
    private final TourSpectator otherPilot;
    private final Timer tick = new Timer(500, e -> tick());
    private Stage stage = Stage.NONE;
    private long setUpAt, placedAt;
    private final JPanel strip = new JPanel(new BorderLayout(8, 0));
    private final JButton scenarioButton = Theme.primaryButton("Set up scenario");
    private final Card card = new Card();

    DemoMode(MainWindow main) {
        this.main = main;
        parts = new TourFinale(main, () -> fleet, PREFIX);
        otherPilot = new TourSpectator(main, () -> fleet, PREFIX);
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE),
                BorderFactory.createEmptyBorder(0, 0, 8, 0)));
        JLabel tag = Theme.label("DEMO", Theme.headingFont(12f), Theme.WARN);
        strip.add(tag, BorderLayout.WEST);
        scenarioButton.setFont(Theme.headingFont(11f));
        scenarioButton.setFocusable(false);
        scenarioButton.addActionListener(e -> pressed());
        strip.add(scenarioButton, BorderLayout.CENTER);
        refreshStrip();
    }

    /** Everything up (Swing thread); false (said why) when it can't. */
    boolean start() {
        if (!VirtualFleet.announcedNow().isEmpty()) {
            Dialogs.message(main, "Close the virtual fleet first.", "Demo", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        fleet = new Fleet(main.config(), VirtualFleet.file(), line -> SwingUtilities.invokeLater(() -> main.logLine(PREFIX + line)));
        fleet.startHeartbeat();
        if (!parts.startShowcase()) {
            String why = parts.refused();
            fleet.close();
            fleet = null;
            Dialogs.message(main, "Demo not started. " + (why == null ? "" : why), "Demo", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        otherPilot.start();
        main.tourScanNames();
        tick.start();
        main.logLine("DEMO: six drones with scenes and a virtual pilot PC are up. FLEET in the map window: the scenario.");
        return true;
    }

    /** FLEET MANAGEMENT's strip. */
    JComponent strip() {
        return strip;
    }

    Stage stage() {
        return stage;
    }

    /** The virtual pilot PC's name (null until chosen). */
    String otherPilot() {
        return otherPilot.pilotName();
    }

    /** The scenario's button: SET UP, START, AGAIN. */
    void pressed() {
        switch (stage) {
            case NONE -> setUp();
            case READY -> {
                parts.driveIn();
                stage = Stage.RUNNING;
                main.logLine(PREFIX + "scenario started.");
            }
            case RUNNING -> { // the four anew; the six, the pilot PC and a crash elsewhere stay
                parts.closeScenario();
                stage = Stage.NONE;
                setUp();
            }
            default -> {
            }
        }
        refreshStrip();
    }

    private void setUp() {
        if (!parts.start()) {
            Dialogs.message(main, "Scenario not set up. " + (parts.refused() == null ? "" : parts.refused()), "Demo",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        stage = Stage.SETTING_UP;
        setUpAt = System.currentTimeMillis();
        main.tourScanNames();
        MapWindow w = main.mapWindow();
        if (w != null) {
            double[] c = parts.centre();
            w.map().panTo(c[0], c[1]);
        }
        main.logLine(PREFIX + "scenario set up: three drones overwatching, one asleep.");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (stage == Stage.SETTING_UP && now - setUpAt >= PLACE_AFTER_MS) {
            parts.placeModes();
            placedAt = now;
            stage = Stage.SETTLING;
        } else if (stage == Stage.SETTLING && now - placedAt >= TourScenario.DRIVE_IN_AFTER_MS) {
            stage = Stage.READY; // the detection's strict first seconds over: what comes is found
        }
        refreshStrip();
        card.refresh();
    }

    /** The button as the stage says. */
    private void refreshStrip() {
        String text;
        String tip;
        boolean on = true;
        switch (stage) {
            case SETTING_UP -> {
                text = "Setting up...";
                tip = "The four drones go to their modes";
                on = false;
            }
            case SETTLING -> {
                long left = Math.max(0, TourScenario.DRIVE_IN_AFTER_MS - (System.currentTimeMillis() - placedAt));
                text = "Ready in " + (left + 999) / 1000 + " s";
                tip = "Overwatch settles first";
                on = false;
            }
            case READY -> {
                text = "Start scenario";
                tip = "It begins in the first overwatch drone's view";
            }
            case RUNNING -> {
                text = "Scenario again";
                tip = "The four drones anew; everything else stays";
            }
            default -> {
                text = "Set up scenario";
                tip = "Four drones out: three overwatching, one asleep, with their views and flights";
            }
        }
        String t = text.toUpperCase(java.util.Locale.ROOT);
        if (!t.equals(scenarioButton.getText())) scenarioButton.setText(t);
        scenarioButton.setToolTipText(tip);
        scenarioButton.setEnabled(on);
    }

    /** Everything it brought gone (SETUP > End demo; the window closing). */
    @Override
    public void close() {
        tick.stop();
        card.hideCard();
        otherPilot.close();
        parts.close(); // the six, the four, their traces and settings (TourHost.cleanUp), flights and units
        Fleet f = fleet;
        fleet = null;
        stage = Stage.NONE;
        if (f != null) {
            Thread t = new Thread(f::close, "demoFleetClose"); // after the pilot PC's own close thread has begun
            t.setDaemon(true);
            t.start();
        }
        main.logLine("DEMO ended: its drones and what they left are gone.");
    }

    // ------------------------------------------------------------------ the spectator's card

    /**
     * While this PC watches the virtual pilot PC: a small card over the video's corner - who flies or observes, and the
     * switch between them. It takes only its own clicks (the pilot: "a pop up ... that does not limit any button
     * presses").
     */
    private final class Card extends JPanel {
        private final JLabel line = Theme.label(" ", Theme.headingFont(12f), Theme.TEXT);
        private final JButton toggle = Theme.secondaryButton("Switch to observation");

        Card() {
            super(new BorderLayout(10, 6));
            setBackground(Theme.PANEL_2);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.WARN, 1),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            add(Theme.label("DEMO - ANOTHER PILOT", Theme.headingFont(10f), Theme.WARN), BorderLayout.NORTH);
            add(line, BorderLayout.CENTER);
            toggle.setFont(Theme.headingFont(11f));
            toggle.setFocusable(false);
            toggle.addActionListener(e -> {
                if (otherPilot.observing()) otherPilot.flyAgain();
                else otherPilot.observe();
                refresh();
            });
            add(toggle, BorderLayout.SOUTH);
        }

        void refresh() {
            String pilot = otherPilot.pilotName();
            if (pilot == null || !main.demoWatching(pilot)) {
                hideCard();
                return;
            }
            JRootPane root = main.getRootPane();
            JLayeredPane layer = root.getLayeredPane();
            if (getParent() != layer) layer.add(this, JLayeredPane.PALETTE_LAYER);
            boolean observing = otherPilot.observing();
            line.setText(pilot + (observing ? " OBSERVES" : " FLIES"));
            String t = (observing ? "Back to flying" : "Switch to observation").toUpperCase(java.util.Locale.ROOT);
            if (!t.equals(toggle.getText())) toggle.setText(t);
            Dimension d = getPreferredSize();
            JComponent video = main.tourVideoArea();
            Point at = SwingUtilities.convertPoint(video, 12, 12, layer);
            setBounds(at.x, at.y, Math.max(260, d.width), d.height);
            revalidate();
            repaint();
        }

        void hideCard() {
            if (getParent() == null) return;
            java.awt.Container p = getParent();
            p.remove(this);
            p.repaint();
        }
    }

    /** For tests: the card on the window now. */
    boolean cardShown() {
        return card.getParent() != null;
    }
}
