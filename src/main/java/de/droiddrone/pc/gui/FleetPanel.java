package de.droiddrone.pc.gui;

import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.FlightModel;
import de.droiddrone.pc.fleet.LinkImpairment;
import de.droiddrone.pc.fleet.VirtualDrone;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The virtual fleet's window (FleetMain): one card per virtual drone with its live readout and the buttons that play
 * the situations the pilot wants to try - LINK PROBLEMS (4G loss, a weak link, a handover, the flight controller lost,
 * the phone app restarting), OVERWATCH + DETECTION (something crossing the picture, night, an alert now, the sleep
 * check now, the phone's own power buttons) and OBSERVATION (a voice now) - plus ADD DRONE, the start place, the arm
 * switch's channel and the failsafe. DRAUPNE Link's look (Theme). The drones keep running when it is minimised;
 * closing it ends the fleet program.
 */
public final class FleetPanel {
    private final Fleet fleet;
    /** Null until open (a panel painted off screen has none). */
    private JFrame frame;
    private final JPanel cards = new JPanel();
    private final JTextArea logArea = new JTextArea();
    private final List<Card> cardList = new ArrayList<>();
    private final JLabel summary = Theme.label("", Theme.font(Font.PLAIN, 13f), Theme.MUTED);
    private double startLat = Fleet.DEFAULT_LAT, startLon = Fleet.DEFAULT_LON;

    FleetPanel(Fleet fleet) {
        this.fleet = fleet;
    }

    /** On the Swing thread. */
    public static void open(Fleet fleet, Path configFile) {
        Theme.install();
        FleetPanel p = new FleetPanel(fleet);
        JFrame frame = new JFrame("DRAUPNE Link - virtual fleet");
        p.frame = frame;
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE); // the shutdown hook closes the fleet
        frame.getContentPane().setBackground(Theme.BG);
        frame.setContentPane(p.content());
        frame.setSize(1180, 860);
        frame.setLocationByPlatform(true);
        frame.setIconImages(AppIcon.images());
        frame.setVisible(true);
        new Timer(250, e -> p.refresh()).start();
    }

    /** The window's content: the fleet's bar, a card per drone, the log. */
    JPanel content() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JPanel top = Theme.card(new BorderLayout(10, 6));
        JPanel titles = new JPanel(new GridLayout(0, 1, 0, 2));
        Theme.transparent(titles);
        titles.add(Theme.sectionTitle("Virtual fleet"));
        titles.add(Theme.label("Virtual drones on this PC's loopback, speaking the real phone protocol.",
                Theme.font(Font.PLAIN, 12.5f), Theme.TEXT));
        titles.add(Theme.label("Start DRAUPNE Link as usual: they show up marked VIRTUAL. No serial port, no Tailscale.",
                Theme.font(Font.PLAIN, 12.5f), Theme.TEXT));
        titles.add(summary);
        top.add(titles, BorderLayout.CENTER);
        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        Theme.transparent(topButtons);
        JButton add = Theme.primaryButton("Add drone");
        add.addActionListener(e -> addDrone());
        JButton place = Theme.secondaryButton("Start place");
        place.setToolTipText("Where new drones stand (lat, lon) - and moves the disarmed ones there");
        place.addActionListener(e -> pickPlace());
        JComboBox<String> arm = new JComboBox<>(new String[]{"ARM on AUX1", "ARM on AUX2", "ARM on AUX3", "ARM on AUX4", "ARM on AUX5", "ARM on AUX6"});
        arm.setToolTipText("The virtual Betaflight's ARM switch (1700-2100 us) for drones added from now on; MSP_MODE_RANGES says so to the phone");
        arm.setSelectedIndex(fleet.setup().armAux);
        arm.addActionListener(e -> fleet.setup().armAux = arm.getSelectedIndex());
        JComboBox<String> fs = new JComboBox<>(new String[]{"Failsafe: DROP", "Failsafe: LAND"});
        fs.setToolTipText("Betaflight's failsafe stage 2 for drones added from now on");
        fs.addActionListener(e -> fleet.setup().procedure = fs.getSelectedIndex() == 0 ? FlightModel.Procedure.DROP : FlightModel.Procedure.LAND);
        topButtons.add(arm);
        topButtons.add(fs);
        topButtons.add(place);
        topButtons.add(add);
        top.add(topButtons, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        cards.setBackground(Theme.BG);
        JScrollPane scroll = new JScrollPane(new WidthTrackingPanel(cards)); // as wide as the window: no sideways scrolling
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        root.add(scroll, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setFont(Theme.monoFont(Font.PLAIN, 11.5f));
        logArea.setBackground(Theme.PANEL);
        logArea.setForeground(Theme.TEXT);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(100, 150));
        logScroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        root.add(logScroll, BorderLayout.SOUTH);
        fleet.onLog(line -> SwingUtilities.invokeLater(() -> appendLog(line)));

        for (VirtualDrone d : fleet.drones()) addCard(d);
        refresh();
        return root;
    }


    private void appendLog(String line) {
        String time = java.time.LocalTime.now().withNano(0).toString();
        logArea.append(time + "  " + line + "\n");
        String text = logArea.getText();
        if (text.length() > 60_000) logArea.setText(text.substring(text.length() - 40_000));
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void addDrone() {
        try {
            double[] at = fleet.nextPlace(startLat, startLon);
            addCard(fleet.add(fleet.freeName(), at[0], at[1], 0));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Add drone", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void pickPlace() {
        String now = String.format(Locale.ROOT, "%.6f, %.6f", startLat, startLon);
        String s = (String) JOptionPane.showInputDialog(frame, "Where the drones stand, latitude, longitude (decimal degrees):",
                "Start place", JOptionPane.QUESTION_MESSAGE, null, null, now);
        if (s == null) return;
        String[] p = s.split("[,;\\s]+");
        try {
            double lat = Double.parseDouble(p[0].trim()), lon = Double.parseDouble(p[1].trim());
            if (Math.abs(lat) > 85 || Math.abs(lon) > 180) throw new NumberFormatException();
            startLat = lat;
            startLon = lon;
            int i = 0;
            for (VirtualDrone d : fleet.drones()) {
                double east = i++ * 30 / (111_320.0 * Math.cos(Math.toRadians(lat)));
                if (!d.moveTo(lat, lon + east)) appendLog(d.name() + ": armed - stays where it is");
            }
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(frame, "Two numbers please, like 52.4732, 13.4012", "Start place", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void addCard(VirtualDrone d) {
        Card c = new Card(d);
        cardList.add(c);
        cards.add(c.panel);
        cards.add(javax.swing.Box.createVerticalStrut(10));
        cards.revalidate();
    }

    void refresh() {
        for (Card c : cardList) c.refresh();
        summary.setText(fleet.drones().size() + " virtual drones up" + (fleet.key().isEmpty() ? ", no link key set" : "")
                + ". Names from Zulu down, never one in DRAUPNE Link's map.");
    }

    // ------------------------------------------------------------------ one drone

    private final class Card {
        final VirtualDrone drone;
        final JPanel panel = Theme.card(new BorderLayout(10, 8));
        final JLabel title = Theme.label("", Theme.headingFont(16f), Theme.TEXT);
        final JLabel state = Theme.label("", Theme.headingFont(13f), Theme.MUTED);
        final JLabel[] lines = new JLabel[5];
        final JLabel videoLabel = Theme.label("generated picture", Theme.font(Font.PLAIN, 12f), Theme.MUTED);
        final JCheckBox fcLost = new JCheckBox("FC LOST");
        final JCheckBox night = new JCheckBox("NIGHT");
        final JCheckBox track = new JCheckBox("play its track");
        final JCheckBox newPort = new JCheckBox("new port");
        final JCheckBox voices = new JCheckBox("voices", true);

        Card(VirtualDrone d) {
            drone = d;
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 460));
            JPanel head = new JPanel(new BorderLayout(10, 0));
            Theme.transparent(head);
            head.add(title, BorderLayout.WEST);
            head.add(state, BorderLayout.CENTER);
            JPanel headButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            Theme.transparent(headButtons);
            headButtons.add(button("Place phone", "Put this drone somewhere else: click the spot on a map (disarmed only)",
                    () -> PlacePhoneDialog.open(frame, fleet, drone, FleetPanel.this::appendLog)));
            headButtons.add(button("Rename", "Another DRAUPNE name (MobileXxx) - DRAUPNE Link files it at its next scan", this::rename));
            headButtons.add(button("Remove", "This drone stops and leaves the fleet", () -> {
                fleet.remove(drone);
                cards.remove(panel);
                cardList.remove(this);
                cards.revalidate();
                cards.repaint();
            }));
            head.add(headButtons, BorderLayout.EAST);
            panel.add(head, BorderLayout.NORTH);

            JPanel body = new JPanel(new BorderLayout(14, 0));
            Theme.transparent(body);
            JPanel read = new JPanel();
            read.setLayout(new BoxLayout(read, BoxLayout.Y_AXIS));
            Theme.transparent(read);
            for (int i = 0; i < lines.length; i++) {
                lines[i] = Theme.label(" ", Theme.monoFont(Font.PLAIN, 12f), Theme.TEXT);
                read.add(lines[i]);
                read.add(javax.swing.Box.createVerticalStrut(5));
            }
            JPanel readTop = new JPanel(new BorderLayout());
            Theme.transparent(readTop);
            readTop.add(read, BorderLayout.NORTH);
            readTop.setPreferredSize(new Dimension(390, 10));
            body.add(readTop, BorderLayout.WEST);

            JPanel actions = new JPanel(new GridLayout(0, 1, 0, 4));
            Theme.transparent(actions);
            actions.add(section("LINK PROBLEMS"));
            actions.add(row(button("4G loss", "Nothing either way for the seconds set here", this::fourGLoss), lossSeconds,
                    Theme.label("s", Theme.font(Font.PLAIN, 12f), Theme.MUTED), fcLost,
                    button("Phone restart", "The app restarts: no link for 3 s, then a new session", drone::restartApp)));
            actions.add(row(button("Handover", "A cell change: the phone's packets held 1.5 s then let go at once, 80 ms slower for 15 s"
                            + " (with \"new port\": its address changes too, as without Tailscale)", this::handover), newPort));
            actions.add(row(Theme.label("weak link:  loss %", Theme.font(Font.PLAIN, 12f), Theme.MUTED), loss,
                    Theme.label("jitter ms", Theme.font(Font.PLAIN, 12f), Theme.MUTED), jitter,
                    Theme.label("bunch ms", Theme.font(Font.PLAIN, 12f), Theme.MUTED), bunch,
                    button("Apply", "The weak link's loss, jitter and bunching, both ways", this::applyWeak),
                    button("Clear", "A perfect link again", () -> {
                        drone.link().clear();
                        loss.setValue(0);
                        jitter.setValue(0);
                        bunch.setValue(0);
                    })));
            actions.add(section("OVERWATCH + DETECTION"));
            actions.add(row(button("Walker", "Someone walks across the picture (12 m out)", () -> drone.addTarget("walker")),
                    button("Vehicle", "A vehicle drives across (40 m out)", () -> drone.addTarget("vehicle")),
                    button("Light", "A torch moves across (shows at NIGHT)", () -> drone.addTarget("light")), night,
                    button("Alert now", "In OVERWATCH: a detection now (the alert, pre-roll, preview, then the clip)", drone::alertNow)));
            actions.add(row(button("Standby", "The phone's own STANDBY button", () -> drone.requestMode(VirtualDrone.STANDBY, 0, "the phone's button")),
                    button("Deep sleep", "The phone's own DEEP SLEEP button", () -> drone.requestMode(VirtualDrone.DEEP_SLEEP, 0, "the phone's button")),
                    button("Check now", "Asleep: the next sleep check window opens now (hello to the PC)", drone::checkNow),
                    button("Wake", "The phone's own hold-to-wake", () -> drone.requestMode(VirtualDrone.NORMAL, 0, "the phone's button"))));
            actions.add(section("OBSERVATION + VIDEO"));
            actions.add(row(button("Speak", "A voice on its microphone now (the observation grid's speaking highlight)", drone::speakNow), voices,
                    button("New battery", "A full pack (on the ground)", drone::newBattery)));
            actions.add(row(button("Flight file...", "Loop an old flight (a DRAUPNE Link recording) as its camera", this::pickFile), track,
                    button("Generated", "The generated picture again", this::generated), videoLabel));
            body.add(actions, BorderLayout.CENTER);
            panel.add(body, BorderLayout.CENTER);

            fcLost.addActionListener(e -> drone.setFcLost(fcLost.isSelected()));
            night.addActionListener(e -> drone.setNight(night.isSelected()));
            voices.addActionListener(e -> drone.setVoices(voices.isSelected()));
            for (JCheckBox b : new JCheckBox[]{fcLost, night, track, newPort, voices}) {
                b.setOpaque(false);
                b.setForeground(Theme.TEXT);
                b.setFont(Theme.font(Font.PLAIN, 12f));
            }
            refresh();
        }

        final JSpinner lossSeconds = spinner(6, 1, 120, 1);
        final JSpinner loss = spinner(10, 0, 100, 5);
        final JSpinner jitter = spinner(80, 0, 2000, 20);
        final JSpinner bunch = spinner(0, 0, 2000, 50);

        private void fourGLoss() {
            int s = (Integer) lossSeconds.getValue();
            drone.link().silence(System.currentTimeMillis(), s * 1000L);
            appendLog(drone.name() + ": 4G LOSS for " + s + " s");
        }

        private void handover() {
            drone.link().handover(System.currentTimeMillis(), 1500);
            appendLog(drone.name() + ": HANDOVER" + (newPort.isSelected() ? " to a new port" : ""));
            if (newPort.isSelected()) {
                drone.moveToNewPort();
                fleet.announce();
            }
        }

        private void applyWeak() {
            LinkImpairment l = drone.link();
            l.set((Integer) loss.getValue(), (Integer) jitter.getValue(), 0, (Integer) bunch.getValue());
            appendLog(drone.name() + ": WEAK LINK loss " + loss.getValue() + " %, jitter " + jitter.getValue() + " ms, bunch " + bunch.getValue() + " ms");
        }

        private void rename() {
            String s = (String) JOptionPane.showInputDialog(frame, "The DRAUPNE name (MobileAlpha ... MobileZulu):", "Rename",
                    JOptionPane.QUESTION_MESSAGE, null, null, drone.name());
            if (s == null) return;
            s = s.trim();
            if (!de.droiddrone.pc.Names.isValidName(s, de.droiddrone.pc.Names.Kind.PHONE)) {
                JOptionPane.showMessageDialog(frame, "Not a DRAUPNE phone name: Mobile and a letter word, like MobileYankee.", "Rename", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (fleet.nameTaken(s)) {
                JOptionPane.showMessageDialog(frame, s + " is in DRAUPNE Link's name map already (one of your phones, or a drone seen before).\n"
                        + "A virtual drone never takes such a name.", "Rename", JOptionPane.WARNING_MESSAGE);
                return;
            }
            for (VirtualDrone o : fleet.drones()) {
                if (o != drone && o.name().equalsIgnoreCase(s)) {
                    JOptionPane.showMessageDialog(frame, "Another virtual drone has that name.", "Rename", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
            drone.setName(s);
        }

        private void pickFile() {
            JFileChooser chooser;
            try {
                chooser = new JFileChooser(de.droiddrone.pc.AppPaths.videosDir().toFile());
            } catch (IOException e) {
                chooser = new JFileChooser();
            }
            chooser.setFileFilter(new FileNameExtensionFilter("DRAUPNE Link recordings (*.mkv)", "mkv"));
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            Path file = chooser.getSelectedFile().toPath();
            try {
                videoLabel.setText("plays " + drone.playRecording(file, track.isSelected()));
            } catch (IOException | RuntimeException e) {
                JOptionPane.showMessageDialog(frame, "Can't play " + file.getFileName() + ": " + e.getMessage(), "Flight file", JOptionPane.WARNING_MESSAGE);
            }
        }

        private void generated() {
            try {
                videoLabel.setText(drone.playRecording(null, false));
            } catch (IOException ignored) {
            }
        }

        void refresh() {
            VirtualDrone.Readout r = drone.readout();
            title.setText(r.name() + "  VIRTUAL");
            state.setText("   " + r.address() + "   " + (r.armed() ? "ARMED" : "DISARMED") + "   " + r.flightMode());
            state.setForeground(r.armed() ? Theme.ACCENT : r.failsafe().equals("NONE") ? Theme.MUTED : Theme.WARN);
            lines[0].setText(String.format(Locale.ROOT, "battery %.2f V   height %.1f m   speed %.0f km/h", r.volts(), r.altM(), r.speedKmh()));
            lines[1].setText("link    " + r.link());
            lines[2].setText("pilot   " + r.pilot() + "    power " + r.power());
            lines[3].setText("asked   " + r.asked());
            lines[4].setText(r.extra().isEmpty() ? " " : r.extra());
            lines[4].setForeground(r.extra().contains("FC LOST") || r.extra().contains("BEEPING") ? Theme.WARN : Theme.MUTED);
        }
    }

    private static JLabel section(String text) {
        return Theme.label("// " + text, Theme.headingFont(12f), Theme.ACCENT);
    }

    private static JPanel row(JComponent... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        Theme.transparent(p);
        for (JComponent c : parts) p.add(c);
        return p;
    }

    private static JButton button(String text, String tip, Runnable action) {
        JButton b = Theme.secondaryButton(text);
        b.setFont(Theme.headingFont(11.5f));
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.ACCENT, 1),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        b.setToolTipText(tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JSpinner spinner(int value, int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        s.setPreferredSize(new Dimension(64, 24));
        return s;
    }

}
