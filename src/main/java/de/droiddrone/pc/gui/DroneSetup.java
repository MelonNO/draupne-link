package de.droiddrone.pc.gui;

import de.droiddrone.pc.ArmSwitch;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.gui.WizardDialog.Page;
import de.droiddrone.pc.setup.Beepers;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.CliWrite;
import de.droiddrone.pc.setup.GpsSetup;
import de.droiddrone.pc.setup.ModeSwitch;
import de.droiddrone.pc.setup.SetupConfirm;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drone setup: the Betaflight settings DRAUPNE Link needs - receiver mode MSP, serial_update_rate_hz and the battery's
 * cell count (6S unless the pilot changes it) - set over USB with nothing else to click; the GPS (its UART and the
 * compass, when the drone has one); the flight mode switch the pilot picks (AUX2 by default: acro, angle, GPS
 * position hold; or left as it is); ARM, PREARM, GPS rescue and the failsafe. The rows say only what the choices
 * next to them don't: what will be written, what is set, what is wrong. The motors and props are assumed checked;
 * the pilot can say so, or check them there and then with the motor test.
 */
final class DroneSetup {
    /**
     * Set when Drone setup wrote or changed a switch (ARM, flight modes, GPS rescue): an older radio calibration may
     * have any switch on those AUX channels, so the radio step is shown again until a calibration is saved.
     */
    static final String RADIO_REDO_KEY = "setup.radioNeedsRedo";

    private DroneSetup() {
    }

    /** Drone setup's steps. The first-time setup is made of these too (SetupWizard): a step added here is in both. */
    static List<Page> pages(MainWindow main) {
        return List.of(new BetaflightPage(main));
    }

    /** SETUP &rarr; Drone setup. */
    static void open(MainWindow main) {
        new WizardDialog(main, "Drone setup", pages(main), () -> {
            main.save(Map.of("setup.droneDone", "true"));
            main.logLine("Drone setup finished.");
        }).setVisible(true);
        main.reloadSettings();
    }

    /** The radio must be calibrated again: Drone setup changed the switches since the last calibration. */
    static boolean radioNeedsRedo(PcConfig config) {
        return "true".equalsIgnoreCase(config.getString(RADIO_REDO_KEY));
    }

    /** "Calibrate the radio again so AUX1 (ARM) and AUX2 (flight modes) are the right switches." */
    static String radioRedoText(PcConfig config) {
        ModeSwitch.Purpose[] purposes = ModeSwitch.purposes(config);
        List<String> named = new java.util.ArrayList<>();
        for (int i = 0; i < purposes.length; i++) {
            if (purposes[i] != null) named.add("AUX" + (i + 1) + " (" + purposes[i].title() + ")");
        }
        if (named.isEmpty()) return "Calibrate the radio again so each AUX is the right switch.";
        if (named.size() == 1) return "Calibrate the radio again so " + named.get(0) + " is the right switch.";
        return "Calibrate the radio again so " + String.join(", ", named.subList(0, named.size() - 1)) + " and "
                + named.get(named.size() - 1) + " are the right switches.";
    }

    /** Finds the flight controller on USB, reads the settings, fixes them, and checks they stuck. */
    static final class BetaflightPage extends Page {
        /**
         * OBSERVATION (1.0.48): whether the chosen phone's drone has a calibrated compass, as read here - its view cone
         * on the observation map says "heading approximate" until it has (the phone can't tell).
         */
        private void rememberCompass(boolean calibrated) {
            String phone = main.config().getString(de.droiddrone.pc.Names.PHONE_NAME_KEY);
            if (phone == null || phone.isBlank()) return;
            String key = de.droiddrone.pc.observe.HeadingChoice.calibratedKey(phone);
            if (Boolean.toString(calibrated).equalsIgnoreCase(main.config().getString(key))) return;
            javax.swing.SwingUtilities.invokeLater(() -> main.save(Map.of(key, Boolean.toString(calibrated))));
        }

        /** Automatic attempts at changing the settings before the page says it couldn't. */
        private static final int MAX_APPLIES = 3; // the GPS: turned on, then its modes once it answers
        /** How long to wait for the GPS to talk to Betaflight after a (re)start: it tries its baud rates first. */
        private static final long GPS_WAIT_MS = 12_000;
        /** After the CLI closes (exit or save) Betaflight restarts and its USB port is gone for a few seconds. */
        private static final long RESTART_MS = 6000;
        /** The same error this many times running: the USB link itself is stuck. */
        private static final int SAME_ERROR_LIMIT = 3;
        private static final String WITH_WRITE = "Set on <b>Confirm</b>.";

        /**
         * The GPS question's side of a round.
         * @param target     the pilot's answer (null = not asked yet: nothing GPS is written)
         * @param uart       the UART chosen (1 = UART1)
         * @param compass    the compass wanted
         * @param onFc       Betaflight's GPS feature is on
         * @param portNow    the port Betaflight has the GPS function on ("" = none)
         * @param uarts      the UARTs the board has, as Betaflight lists them (empty = not read)
         * @param magFound   Betaflight sees a compass
         * @param calibrated the compass has calibration offsets
         * @param gpsPending the plan changes the GPS (feature or port); portNote says what the port loses
         * @param compassPending the plan changes mag_hardware
         */
        private record Gps(Boolean target, int uart, boolean compass, boolean onFc, String portNow, List<Integer> uarts,
                           boolean magFound, boolean calibrated, boolean gpsPending, String portNote, boolean compassPending) {
            static Gps none(Boolean target, int uart, boolean compass) {
                return new Gps(target, uart, compass, false, "", List.of(), false, false, false, "", false);
            }

            boolean wanted() {
                return Boolean.TRUE.equals(target);
            }
        }

        /**
         * @param arm        Betaflight's arm switch, or null if ARM isn't on one switch
         * @param armAuxes   every AUX ARM is on (more than one isn't covered by {@link ArmSwitch})
         * @param backup     where Betaflight's settings were saved before writing, or null
         * @param ports      every port that looked like a flight controller this round
         * @param applied    the plan was written and saved (all of it: a write that stops is never saved)
         * @param refused    why a write stopped without saving (Betaflight refused a command, its echo differed, or no
         *                   backup could be made): the board is as it was; null if none
         * @param plan       what needs changing (empty: all set); written when applied
         * @param replaced   what the plan takes off the switches
         * @param board      the board's name and the craft's, for the USB row ("" if none)
         * @param battery    the battery as Betaflight sees it, or null if it couldn't be asked
         * @param receiverNow the receiver(s) on before any change, for the log and the list of changes
         * @param prearmTarget the PREARM switch chosen (AUX number, 0 = leave as it is)
         * @param prearmAuxes every AUX PREARM is on in Betaflight
         * @param boardId    the flight controller's id (SetupConfirm.boardId), or null if it couldn't be read
         */
        private record Read(String port, String version, boolean msp, int rate, int target, int cells, int cellTarget,
                            boolean applied, String error, ArmSwitch arm, java.nio.file.Path backup, List<String> ports,
                            ModeSwitch modeTarget, ModeSwitch.Plan modes, Boolean gpsFound, String rescueNote,
                            int rescueTarget, String failsafe, ModeSwitch.Failsafe failsafeTarget, boolean rescueFitted,
                            String failsafeProblem, String failsafeNote, int armTarget, String refused,
                            List<BetaflightPlan.Change> plan, List<String> replaced, String board,
                            BetaflightUsb.BatteryState battery, Set<Integer> armAuxes, String receiverNow, boolean cellsConfirmed,
                            int prearmTarget, Set<Integer> prearmAuxes, Gps gps, String boardId) {
            Read(String port, String error, int cellTarget, ModeSwitch modeTarget, int rescueTarget,
                 ModeSwitch.Failsafe failsafeTarget, int armTarget, int prearmTarget, List<String> ports, Gps gps) {
                this(port, "", false, -1, 0, -1, cellTarget, false, error, null, null, ports, modeTarget, null, null, null,
                        rescueTarget, null, failsafeTarget, false, null, null, armTarget, null, List.of(), List.of(), "", null,
                        Set.of(), "", false, prearmTarget, Set.of(), gps, null);
            }

            /** A switch (ARM, flight modes, GPS rescue) was written this round: the radio calibration may be off. */
            boolean switchesWritten() {
                return applied && modes != null && !modes.commands().isEmpty();
            }
        }
        /** Why the last write stopped without saving, shown with the port until a write goes through cleanly. */
        private String lastRefused;
        static final String CELLS_KEY = "drone.cellCount";
        /** The pilot has confirmed (or changed) the cell count: only then is it forced in Betaflight. */
        static final String CELLS_CONFIRMED_KEY = "drone.cellCountConfirmed";
        static final String MOTORS_CHECKED_KEY = "setup.motorsChecked";

        private final MainWindow main;
        private final CheckRow usb = new CheckRow("Flight controller on USB");
        /** The settings, and CONFIRM: nothing is written before the press. */
        private final Combined settings = new Combined("Betaflight settings");
        private javax.swing.JButton confirmButton;
        /**
         * Everything on the page was confirmed and is in Betaflight: the rows may be green. Off on any change. Saved as
         * the board's id (1.0.49): another flight controller isn't confirmed by it.
         */
        static final String CONFIRMED_KEY = "drone.confirmed";
        /** CONFIRM, and the confirmed state, of one board (SetupConfirm): a drone plugged in afterwards needs its own. */
        private final SetupConfirm confirm = new SetupConfirm();
        /** The board the last round read (SetupConfirm.boardId), what CONFIRM is pressed for; null = none read. */
        private String lastBoardId;
        private final Part receiver = settings.part("Receiver");
        private final Part stickDelay = settings.part("Stick delay");
        private final Part battery = settings.part("Battery");
        /** GPS [No / Yes]; with Yes the compass, the UART and CALIBRATE COMPASS slide out under it. */
        private final Section gpsSection = settings.section("GPS");
        private final Part gps = gpsSection.part("GPS");
        private javax.swing.JComboBox<String> gpsChoice;
        private javax.swing.JComboBox<String> compassChoice;
        private javax.swing.JComboBox<String> uartChoice;
        private javax.swing.JButton calibrateCompass;
        private Fold gpsFold;
        /** The pilot's answer; shown from the evidence (a GPS rescue failsafe) until answered or read from Betaflight. */
        private volatile boolean gpsYes;
        private boolean gpsAnswered;
        private volatile boolean compassYes;
        private volatile int gpsUart = GpsSetup.DEFAULT_UART;
        /** The compass calibration has the flight controller's USB port: this page leaves it alone meanwhile. */
        private boolean compassCalOpen;
        private final Section switches = settings.section("SWITCHES");
        private final Part armRow = switches.part("Arm");
        private final Part prearmRow = switches.part("Prearm");
        private final Part modeRow = switches.part("Flight modes");
        private final Part rescueRow = switches.part("GPS rescue");
        private Fold rescueFold;
        private final CheckRow motors = new CheckRow("Motors and props");
        /** 1.0.42: Betaflight sees the moves the right way round (a turned board flips the drone on takeoff). */
        private final CheckRow orientation = new CheckRow("Orientation");
        /** The orientation check has the flight controller's USB port: this page leaves it alone meanwhile. */
        private boolean orientationOpen;
        /** The pilot's choice; null = leave Betaflight's modes as they are. Read by the background round. */
        private volatile ModeSwitch modeSwitch;
        /** GPS rescue's own switch (AUX number, 0 = none), fitted when Betaflight's failsafe is GPS rescue. */
        private volatile int rescueAux;
        /** The ARM switch (AUX number, 0 = leave ARM as it is in Betaflight); AUX1 by default. */
        private volatile int armAux;
        private javax.swing.JComboBox<String> armChoice;
        /** The PREARM switch (AUX number, 0 = leave PREARM as it is in Betaflight); AUX3 by default. */
        private volatile int prearmAux;
        private javax.swing.JComboBox<String> prearmChoice;
        private final Section failsafeSection = settings.section("FAILSAFE");
        private final Part failsafeRow = failsafeSection.part("Failsafe");
        /** The pilot's failsafe choice: left as it is by default. */
        private volatile ModeSwitch.Failsafe failsafeChoice = ModeSwitch.Failsafe.KEEP;
        private javax.swing.JComboBox<ModeSwitch.Failsafe> failsafeCombo;
        private javax.swing.JComboBox<String> rescueChoice;
        private static final String MODES_OFF = "Leave as it is";
        private javax.swing.JComboBox<String> auxChoice;
        private final List<javax.swing.JComboBox<ModeSwitch.Mode>> positionChoices = new java.util.ArrayList<>();
        /** The flight controller's version line once read: modes it is too old for can't be chosen. */
        private volatile String fcVersion = "";
        private boolean revertingChoice;
        /** Which port to use; "Automatic" = the first that answers as Betaflight. */
        private final javax.swing.JComboBox<String> portChoice = new javax.swing.JComboBox<>(new String[]{AUTOMATIC});
        private static final String AUTOMATIC = "Automatic";
        private volatile String chosenPort; // null = automatic
        /** The motor test has the flight controller's USB port: this page leaves it alone meanwhile. */
        private boolean motorTestOpen;
        private JSpinner cellSpinner;
        /** The pilot's choice; read by the background round, so a change made meanwhile is noticed after it. */
        private volatile int cells;
        /** The count is the pilot's (confirmed or changed): before that Betaflight keeps what it has. */
        private volatile boolean cellsConfirmed;
        /** "Use measured: 4S": Betaflight's own count while a battery is in. */
        private javax.swing.JButton measuredCells;
        private int measured;
        /** The spinner follows a count Betaflight already forces: not the pilot's doing. */
        private boolean syncingSpinner;
        /** The same settings by hand, in Betaflight Configurator: behind the USB row's "By hand" button. */
        private String manualHtml = "";
        private javax.swing.JButton byHand;
        /** RETRY while nothing answers, READ AGAIN once the flight controller is there. */
        private javax.swing.JButton readAgain;
        /** DISCONNECT lets go of the USB port (for Betaflight Configurator); RECONNECT carries on. */
        private javax.swing.JButton disconnect;
        private boolean paused;
        private boolean busy;
        private boolean found;
        private boolean failed;
        private boolean done;
        /** Done, and everything is as it should be (the unplug line); otherwise something needs doing by hand. */
        private boolean cleanDone;
        /** The page was left (Next, Back, Close, Disconnect) while a round ran: it reads, but writes nothing. */
        private volatile boolean abandoned;
        /** What the last write took off the switches, for the rows after the check that followed. */
        private List<String> lastReplaced = List.of();
        /** When the CLI last closed: Betaflight restarts, so the port is gone for a while (not "not found"). */
        private long restartedAt;
        private String lastError;
        private int sameErrorCount;
        private int applies;
        private long nextTry;

        BetaflightPage(MainWindow main) {
            this.main = main;
        }

        @Override
        String title() {
            return "Betaflight";
        }

        @Override
        String hint() {
            return "Plug a USB data cable into the drone and this computer. <b>Props off.</b>";
        }

        /** Rank for a combined row's light: the worst part shows; INFO (nothing to do) only when all are. */
        private static int rank(State s) {
            return switch (s) {
                case BAD -> 5;
                case WARN -> 4;
                case PENDING -> 3;
                case TODO -> 2;
                case OK -> 1;
                case INFO -> 0;
            };
        }

        /**
         * Every Betaflight setting on one row: one light (the worst of them), a line per setting beside its choice, the
         * lines in sections (GPS, SWITCHES, FAILSAFE) with a small light each, and CONFIRM at the bottom.
         */
        private static final class Combined {
            final CheckRow row;
            final List<Part> parts = new java.util.ArrayList<>();
            final List<Section> sections = new java.util.ArrayList<>();
            /** At the bottom, beside CONFIRM: what it does; what is still not set after a failed write. */
            final WrappingText footerText = WrappingText.fitting("", Theme.font(Font.PLAIN, 13f), Theme.MUTED);

            Combined(String title) {
                row = new CheckRow(title);
                footerText.setVisible(false);
            }

            Part part(String label) {
                Part p = new Part(this, label);
                parts.add(p);
                return p;
            }

            Section section(String title) {
                Section s = new Section(this, title);
                sections.add(s);
                return s;
            }

            void footer(String html) {
                footerText.setHtml(html);
                footerText.setVisible(!html.isEmpty());
            }

            /**
             * Shown instead of the worst part, on the row's light and every section's: red while CONFIRM hasn't been
             * pressed (the pilot: not confirmed is red, not orange), busy while its write runs; null = the parts' own.
             */
            State override = State.BAD;

            void refresh() {
                State worst = State.INFO;
                for (Part p : parts) {
                    if (!p.hidden && rank(p.state) > rank(worst)) worst = p.state;
                }
                for (Section s : sections) {
                    s.refresh();
                    if (!s.note.isEmpty() && rank(State.WARN) > rank(worst)) worst = State.WARN;
                }
                row.set(override != null ? override : worst, "");
            }
        }

        /** A group of lines under a small heading with its own light: GPS, SWITCHES, FAILSAFE. */
        private static final class Section {
            final Combined owner;
            final String title;
            final List<Part> parts = new java.util.ArrayList<>();
            final Dot dot = new Dot();
            /** A warning under the lines (the radio calibration to redo); "" for none. */
            String note = "";
            final WrappingText noteText = WrappingText.fitting("", Theme.font(Font.PLAIN, 13f), Theme.WARN);

            Section(Combined owner, String title) {
                this.owner = owner;
                this.title = title;
                noteText.setVisible(false);
            }

            Part part(String label) {
                Part p = owner.part(label);
                parts.add(p);
                return p;
            }

            void note(String html) {
                note = html;
                noteText.setHtml(html.isEmpty() ? "" : "<b>" + html + "</b>");
                noteText.setVisible(!html.isEmpty());
                owner.refresh();
            }

            void refresh() {
                State worst = State.INFO;
                for (Part p : parts) {
                    if (!p.hidden && rank(p.state) > rank(worst)) worst = p.state;
                }
                if (!note.isEmpty() && rank(State.WARN) > rank(worst)) worst = State.WARN;
                dot.state = owner.override != null ? owner.override : worst;
                dot.repaint();
            }

            /** The heading with its light, the lines under it set in from the left, the note last. */
            JComponent build(JComponent... lines) {
                JPanel panel = new JPanel();
                panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
                panel.setOpaque(false);
                JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
                head.setOpaque(false);
                head.add(dot);
                head.add(Theme.label(title, Theme.headingFont(13f), Theme.TEXT));
                panel.add(head);
                JPanel body = new JPanel();
                body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
                body.setOpaque(false);
                body.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, SECTION_INSET, 0, 0));
                for (JComponent l : lines) body.add(l);
                body.add(noteText);
                for (java.awt.Component c : body.getComponents()) {
                    if (c instanceof JComponent j) j.setAlignmentX(javax.swing.JComponent.LEFT_ALIGNMENT);
                }
                panel.add(body);
                for (java.awt.Component c : panel.getComponents()) {
                    if (c instanceof JComponent j) j.setAlignmentX(javax.swing.JComponent.LEFT_ALIGNMENT);
                }
                return panel;
            }
        }

        /** The lines of a section sit this far in from its heading's light. */
        private static final int SECTION_INSET = 18;

        /** A section's light: a small disc (a ring while still to confirm) in the state's colour. */
        private static final class Dot extends JComponent {
            State state = State.PENDING;

            Dot() {
                setPreferredSize(new java.awt.Dimension(12, 12));
            }

            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                java.awt.Graphics2D g = Theme.smooth((java.awt.Graphics2D) graphics.create());
                java.awt.Color c = switch (state) {
                    case OK -> Theme.OK;
                    case WARN -> Theme.WARN;
                    case BAD -> Theme.BAD;
                    case INFO, TODO -> Theme.MUTED;
                    case PENDING -> Theme.LINE;
                };
                g.setColor(c);
                if (state == State.TODO) {
                    g.setStroke(new java.awt.BasicStroke(1.8f));
                    g.drawOval(1, 1, getWidth() - 3, getHeight() - 3);
                } else {
                    g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                }
                g.dispose();
            }
        }

        /**
         * One setting's line in a combined row; set like a row of its own. With a cell, its text shows there (beside
         * its choice, coloured by its state) instead of in the row's text.
         */
        private static final class Part {
            final Combined owner;
            final String label;
            State state = State.PENDING;
            String text = "";
            /** Not shown, and not counted (GPS rescue without a GPS). */
            boolean hidden;
            WrappingText cell;

            Part(Combined owner, String label) {
                this.owner = owner;
                this.label = label;
            }

            Part set(State state, String text) {
                this.state = state;
                this.text = text;
                if (cell != null) {
                    String color = switch (state) {
                        case BAD -> Theme.BAD_HEX;
                        case WARN -> Theme.WARN_HEX;
                        case OK -> Theme.OK_HEX;
                        case TODO -> Theme.TEXT_HEX;
                        case PENDING, INFO -> Theme.MUTED_HEX;
                    };
                    cell.setHtml(text.isEmpty() ? "" : "<span style='color:" + color + "'>" + text + "</span>");
                    if (cell.isVisible() == text.isEmpty()) { // nothing to say: no empty line (under the flight modes)
                        cell.setVisible(!text.isEmpty());
                        if (cell.getParent() instanceof JComponent parent) parent.revalidate();
                    }
                }
                owner.refresh();
                return this;
            }
        }

        /** Label width of a switch line, so the choices line up in columns. */
        private static final int LINE_LABEL_W = 104;

        /**
         * A switch's line: its label and choices on the left, its state beside them in the rest of the width (wrapping
         * when it is a long one).
         */
        private static JComponent line(Part part, String label, JComponent... choices) {
            return line(part, label, false, choices);
        }

        /** @param below the state under the choices, across the width, for a line whose choices fill it (the flight modes) */
        private static JComponent line(Part part, String label, boolean below, JComponent... choices) {
            JPanel line = new JPanel(new java.awt.BorderLayout(10, 0));
            line.setOpaque(false);
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
            left.setOpaque(false);
            javax.swing.JLabel l = Theme.label(label, Theme.headingFont(12f), Theme.MUTED);
            l.setPreferredSize(new java.awt.Dimension(LINE_LABEL_W, l.getPreferredSize().height));
            left.add(l);
            for (JComponent c : choices) left.add(c);
            part.cell = WrappingText.fitting("", Theme.font(Font.PLAIN, 13f), Theme.MUTED);
            if (below) {
                line.add(left, java.awt.BorderLayout.NORTH);
                part.cell.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, LINE_LABEL_W + 16, 4, 0)); // under the lists
                line.add(part.cell, java.awt.BorderLayout.SOUTH);
            } else {
                line.add(left, java.awt.BorderLayout.WEST);
                part.cell.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 0, 0, 0));
                line.add(part.cell, java.awt.BorderLayout.CENTER);
            }
            return line;
        }

        /** An AUX choice: "Leave as it is" / "None", AUX1 ... AUX8, all the same width. */
        private static javax.swing.JComboBox<String> auxCombo(String none) {
            String[] items = new String[ModeSwitch.MAX_AUX + 1];
            items[0] = none;
            for (int i = 1; i <= ModeSwitch.MAX_AUX; i++) items[i] = "AUX" + i;
            javax.swing.JComboBox<String> c = new javax.swing.JComboBox<>(items);
            c.setPrototypeDisplayValue(MODES_OFF);
            return c;
        }

        @Override
        JComponent build() {
            JPanel col = WizardDialog.column();
            cells = savedCells();
            cellsConfirmed = "true".equalsIgnoreCase(main.config().getString(CELLS_CONFIRMED_KEY));
            cellSpinner = new JSpinner(new SpinnerNumberModel(cells, BetaflightPlan.MIN_CELLS, BetaflightPlan.MAX_CELLS, 1));
            cellSpinner.setFont(Theme.headingFont(16f));
            ((JSpinner.DefaultEditor) cellSpinner.getEditor()).getTextField().setColumns(2);
            cellSpinner.addChangeListener(e -> cellsChanged((Integer) cellSpinner.getValue()));
            cellSpinner.setToolTipText("The packs this drone flies on: Betaflight then always uses this count");
            measuredCells = Theme.secondaryButton("Use measured");
            measuredCells.addActionListener(e -> cellSpinner.setValue(measured));
            measuredCells.setToolTipText("The cell count Betaflight sees on the battery plugged in now");
            measuredCells.setVisible(false);
            confirm.restore(main.config().getString(CONFIRMED_KEY));
            confirmButton = Theme.primaryButton("Confirm");
            confirmButton.setFont(Theme.headingFont(12.5f));
            confirmButton.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 16, 8, 16));
            confirmButton.addActionListener(e -> confirmNow());
            confirmButton.setToolTipText("Every setting on this page is right: write them to the flight controller (its settings are backed up first)");
            confirmButton.setVisible(!confirm.confirmed());
            confirmButton.setEnabled(false);
            // the card: the link's settings, then GPS, SWITCHES and FAILSAFE as sections, CONFIRM at the bottom
            JPanel card = new JPanel();
            card.setLayout(new javax.swing.BoxLayout(card, javax.swing.BoxLayout.Y_AXIS));
            card.setOpaque(false);
            JPanel link = new JPanel(); // the link's own settings, set in like the sections' lines so every label lines up
            link.setLayout(new javax.swing.BoxLayout(link, javax.swing.BoxLayout.Y_AXIS));
            link.setOpaque(false);
            link.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, SECTION_INSET, 0, 0));
            link.add(line(receiver, "RECEIVER"));
            link.add(line(stickDelay, "STICK DELAY"));
            link.add(line(battery, "BATTERY", cellSpinner, Theme.label("S", Theme.headingFont(14f), Theme.TEXT), measuredCells));
            for (java.awt.Component c : link.getComponents()) {
                if (c instanceof JComponent j) j.setAlignmentX(javax.swing.JComponent.LEFT_ALIGNMENT);
            }
            card.add(link);
            card.add(javax.swing.Box.createVerticalStrut(8));
            JComponent gpsLine = line(gps, "FITTED", gpsChoices());
            JComponent gpsDetails = gpsDetails();
            card.add(gpsSection.build(gpsLine, gpsDetails));
            card.add(javax.swing.Box.createVerticalStrut(8));
            rescueFold = new Fold(line(rescueRow, "GPS RESCUE", rescueChoices()), gpsYes);
            rescueRow.hidden = !gpsYes;
            card.add(switches.build(line(modeRow, "FLIGHT MODES", true, modeChoices()), line(armRow, "ARM", armChoices()),
                    line(prearmRow, "PREARM", prearmChoices()), rescueFold));
            card.add(javax.swing.Box.createVerticalStrut(8));
            card.add(failsafeSection.build(line(failsafeRow, "SIGNAL LOST", failsafeChoices())));
            card.add(javax.swing.Box.createVerticalStrut(10));
            JPanel bottom = new JPanel(new java.awt.BorderLayout(14, 0));
            bottom.setOpaque(false);
            settings.footerText.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 0, 0, 0));
            bottom.add(settings.footerText, java.awt.BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            buttons.setOpaque(false);
            buttons.add(confirmButton);
            bottom.add(buttons, java.awt.BorderLayout.EAST);
            card.add(bottom);
            for (java.awt.Component c : card.getComponents()) { // all at the left edge
                if (c instanceof JComponent j) j.setAlignmentX(javax.swing.JComponent.LEFT_ALIGNMENT);
            }
            settings.row.addBelow(card);
            portChoice.setToolTipText("Which USB port the flight controller is on, if more than one is found");
            portChoice.addActionListener(e -> {
                Object c = portChoice.getSelectedItem();
                String chosen = c == null || AUTOMATIC.equals(c) ? null : c.toString();
                if (java.util.Objects.equals(chosen, chosenPort)) return;
                chosenPort = chosen;
                done = false;
                nextTry = 0;
            });
            TacticalCombo.install(portChoice);
            usb.addComponent(portChoice);
            portChoice.setVisible(false); // shown once more than one port is found
            byHand = usb.addAction("By hand", this::showByHand);
            byHand.setToolTipText("The same settings in Betaflight Configurator, for a drone without USB to this PC");
            readAgain = usb.addAction("Retry", this::checkModesAgain);
            readAgain.setToolTipText("Look for the flight controller again, or read it again after plugging the battery in or changing something in Betaflight");
            disconnect = usb.addAction("Disconnect", this::togglePaused);
            disconnect.setToolTipText("Let go of the USB port, e.g. for Betaflight Configurator; RECONNECT carries on");
            WizardDialog.addRow(col, usb);
            WizardDialog.addRow(col, settings.row);
            motors.addAction("Already checked", this::motorsAlreadyChecked);
            motors.addPrimaryAction("Check now", this::checkMotorsNow);
            motors.setToolTipText("Each motor in the right place, turning the right way, the props on the right way round");
            WizardDialog.addRow(col, motors);
            orientation.addPrimaryAction("Check now", this::checkOrientationNow);
            orientation.setToolTipText("A drone on screen that moves as Betaflight sees the real one: lift the nose, lower the right side, and see it does the same");
            WizardDialog.addRow(col, orientation);
            showManual();
            usb.set(State.PENDING, "Looking for it...");
            receiver.set(State.PENDING, "");
            stickDelay.set(State.PENDING, "");
            battery.set(cellsConfirmed ? State.PENDING : State.INFO, cellsConfirmed ? "" : "Correct? Change it if not.");
            ArmSwitch known = ArmSwitch.parse(main.config().getString(ArmSwitch.KEY));
            armRow.set(State.PENDING, armAux > 0 || known == null ? "" : describe(known));
            prearmRow.set(State.PENDING, "");
            showGpsChoice();
            showModeChoice();
            showRescueChoice();
            showFailsafeChoice();
            boolean checkedBefore = "true".equalsIgnoreCase(main.config().getString(MOTORS_CHECKED_KEY));
            motors.set(checkedBefore ? State.OK : State.INFO, checkedBefore ? "Checked."
                    : "Not sure? <b>Check now</b> opens the motor test (props off).");
            orientation.set(State.INFO, "Check each drone once: <b>Check now</b> (USB, props off).");
            showConfirmState();
            return col;
        }

        /** GPS [No / Yes]: in the GPS row. Not asked yet: Yes when the saved choices want a GPS (a GPS rescue failsafe). */
        private JComponent gpsChoices() {
            Boolean saved = GpsSetup.parseYesNo(main.config().getString(GpsSetup.GPS_KEY));
            gpsAnswered = saved != null;
            boolean evidence = ModeSwitch.Failsafe.parse(main.config().getString(ModeSwitch.Failsafe.KEY)) == ModeSwitch.Failsafe.GPS_RESCUE
                    || "true".equalsIgnoreCase(main.config().getString(ModeSwitch.RESCUE_FITTED_KEY));
            gpsYes = saved != null ? saved : evidence;
            compassYes = Boolean.TRUE.equals(GpsSetup.parseYesNo(main.config().getString(GpsSetup.COMPASS_KEY)));
            gpsUart = GpsSetup.parseUart(main.config().getString(GpsSetup.UART_KEY));
            gpsChoice = new javax.swing.JComboBox<>(new String[]{"No", "Yes"});
            gpsChoice.setToolTipText("The drone has a GPS: Betaflight's GPS goes on, on the UART chosen, and GPS rescue and position hold can be used");
            gpsChoice.setSelectedIndex(gpsYes ? 1 : 0);
            TacticalCombo.install(gpsChoice);
            gpsChoice.addActionListener(e -> {
                if (revertingChoice) return;
                boolean yes = gpsChoice.getSelectedIndex() == 1;
                if (yes != gpsYes || !gpsAnswered) {
                    choiceChanged();
                    setGps(yes, true, true);
                }
            });
            return gpsChoice;
        }

        /** COMPASS [No / Yes]  UART [UART3]  [Calibrate compass]: slides out under the GPS row with Yes. */
        private JComponent gpsDetails() {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            row.setOpaque(false);
            row.add(Theme.label("COMPASS", Theme.headingFont(12f), Theme.MUTED));
            compassChoice = new javax.swing.JComboBox<>(new String[]{"No", "Yes"});
            compassChoice.setToolTipText("A compass (magnetometer) on the GPS module or the board: Betaflight uses it for the heading and GPS rescue; "
                    + "it must be calibrated once, on the drone, away from metal");
            compassChoice.setSelectedIndex(compassYes ? 1 : 0);
            TacticalCombo.install(compassChoice);
            compassChoice.addActionListener(e -> compassChoiceChanged());
            row.add(compassChoice);
            row.add(javax.swing.Box.createHorizontalStrut(10));
            row.add(Theme.label("UART", Theme.headingFont(12f), Theme.MUTED));
            uartChoice = new javax.swing.JComboBox<>(uartItems(List.of()));
            uartChoice.setToolTipText("The flight controller's UART the GPS is wired to (RX to TX, TX to RX); UART3 on most boards");
            uartChoice.setSelectedItem("UART" + gpsUart);
            TacticalCombo.install(uartChoice);
            uartChoice.addActionListener(e -> uartChoiceChanged());
            row.add(uartChoice);
            row.add(javax.swing.Box.createHorizontalStrut(10));
            calibrateCompass = Theme.secondaryButton("Calibrate compass");
            calibrateCompass.setToolTipText("Turn the drone round every axis for 30 s while Betaflight learns the compass's offsets (over USB, props off)");
            calibrateCompass.addActionListener(e -> calibrateCompassNow());
            calibrateCompass.setVisible(compassYes);
            calibrateCompass.setEnabled(false);
            row.add(calibrateCompass);
            row.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, LINE_LABEL_W + 8, 0, 0)); // under the FITTED choice
            gpsFold = new Fold(row, gpsYes);
            return gpsFold;
        }

        /** "UART1" ... as the board has them (UART1-6 until Betaflight has said), the chosen one always among them. */
        private String[] uartItems(List<Integer> fromFc) {
            List<Integer> uarts = new java.util.ArrayList<>(fromFc);
            if (uarts.isEmpty()) for (int i = 1; i <= GpsSetup.UARTS_OFFERED; i++) uarts.add(i);
            if (!uarts.contains(gpsUart)) uarts.add(gpsUart);
            uarts.sort(null);
            return uarts.stream().map(u -> "UART" + u).toArray(String[]::new);
        }

        /** The UART list follows the board once read, keeping the choice. */
        private void showUarts(List<Integer> fromFc) {
            String[] items = uartItems(fromFc);
            DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) uartChoice.getModel();
            boolean same = model.getSize() == items.length;
            for (int i = 0; same && i < items.length; i++) same = items[i].equals(model.getElementAt(i));
            if (same) return;
            revertingChoice = true;
            uartChoice.setModel(new DefaultComboBoxModel<>(items));
            uartChoice.setSelectedItem("UART" + gpsUart);
            revertingChoice = false;
        }

        /**
         * The GPS answer: the details slide out (or away) under the row, GPS RESCUE under the switches, the GPS modes
         * and the GPS rescue failsafe come and go from their lists (a GPS choice without a GPS becomes Angle / Leave as it is).
         * @param save the pilot's own answer (not the evidence, nor Betaflight's)
         */
        private void setGps(boolean yes, boolean save, boolean animate) {
            gpsYes = yes;
            if (save) {
                gpsAnswered = true;
                main.save(Map.of(GpsSetup.GPS_KEY, yes ? "yes" : "no"));
            }
            if (gpsChoice.getSelectedIndex() != (yes ? 1 : 0)) {
                revertingChoice = true;
                gpsChoice.setSelectedIndex(yes ? 1 : 0);
                revertingChoice = false;
            }
            gpsFold.setShown(yes, animate);
            rescueFold.setShown(yes, animate);
            rescueRow.hidden = !yes;
            settings.refresh();
            calibrateCompass.setVisible(yes && compassYes);
            offerGpsModes(yes);
            offerGpsFailsafe(yes);
            showGpsChoice();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        /** The flight mode switch as it was with a GPS, kept while the answer is No so Yes brings it back. */
        static final String MODES_WITH_GPS_KEY = "drone.modeSwitchWithGps";

        /**
         * The GPS modes are on the position lists only with a GPS; a GPS mode chosen without one becomes Angle (and the
         * switch as it was is remembered: Yes again puts it back). The same switch stays, so the radio needs no redo.
         */
        private void offerGpsModes(boolean gps) {
            ModeSwitch.Mode[] items = gps ? ModeSwitch.Mode.values()
                    : java.util.Arrays.stream(ModeSwitch.Mode.values()).filter(m -> !m.needsGps).toArray(ModeSwitch.Mode[]::new);
            ModeSwitch current = modeSwitch == null ? ModeSwitch.DEFAULT : modeSwitch;
            ModeSwitch wanted = gps ? current : withoutGps(current);
            if (gps) { // the switch from before the No, if nothing was changed since
                ModeSwitch kept = ModeSwitch.parse(main.config().getString(MODES_WITH_GPS_KEY));
                if (kept != null && !main.config().getString(MODES_WITH_GPS_KEY).isEmpty() && withoutGps(kept).equals(current)) wanted = kept;
            } else if (!wanted.equals(current)) {
                main.save(Map.of(MODES_WITH_GPS_KEY, current.format()));
            }
            revertingChoice = true;
            for (int i = 0; i < 3; i++) {
                var c = positionChoices.get(i);
                if (c.getModel().getSize() != items.length) c.setModel(new DefaultComboBoxModel<>(items));
                c.setSelectedItem(wanted.positions()[i]);
            }
            revertingChoice = false;
            if (modeSwitch == null || wanted.equals(modeSwitch)) return;
            modeSwitch = wanted;
            main.save(Map.of(ModeSwitch.KEY, wanted.format()));
            choiceChanged();
            showModeChoice();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        /** The switch with every GPS mode replaced by Angle. */
        private static ModeSwitch withoutGps(ModeSwitch m) {
            ModeSwitch.Mode[] p = m.positions();
            for (int i = 0; i < 3; i++) if (p[i].needsGps) p[i] = ModeSwitch.Mode.ANGLE;
            return new ModeSwitch(m.aux(), p[0], p[1], p[2]);
        }

        /** GPS rescue is on the failsafe list only with a GPS; chosen without one it becomes Leave as it is. */
        private void offerGpsFailsafe(boolean gps) {
            ModeSwitch.Failsafe[] items = gps ? ModeSwitch.Failsafe.values()
                    : new ModeSwitch.Failsafe[]{ModeSwitch.Failsafe.KEEP, ModeSwitch.Failsafe.DROP};
            revertingChoice = true;
            if (failsafeCombo.getModel().getSize() != items.length) failsafeCombo.setModel(new DefaultComboBoxModel<>(items));
            failsafeCombo.setSelectedItem(gps || failsafeChoice != ModeSwitch.Failsafe.GPS_RESCUE ? failsafeChoice : ModeSwitch.Failsafe.KEEP);
            revertingChoice = false;
            failsafeChoiceChanged();
        }

        private void compassChoiceChanged() {
            if (revertingChoice) return;
            boolean yes = compassChoice.getSelectedIndex() == 1;
            if (yes == compassYes) return;
            compassYes = yes;
            main.save(Map.of(GpsSetup.COMPASS_KEY, yes ? "yes" : "no"));
            choiceChanged();
            calibrateCompass.setVisible(gpsYes && yes);
            showGpsChoice();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        private void uartChoiceChanged() {
            if (revertingChoice) return;
            Object item = uartChoice.getSelectedItem();
            int uart = item == null ? gpsUart : GpsSetup.parseUart(item.toString());
            if (uart == gpsUart) return;
            gpsUart = uart;
            main.save(Map.of(GpsSetup.UART_KEY, Integer.toString(uart)));
            choiceChanged();
            showGpsChoice();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        private void showGpsChoice() {
            gps.set(gpsYes ? State.PENDING : State.OK, "");
        }

        /** CALIBRATE COMPASS: the calibration window takes the USB port; the page reads again once it is closed. */
        private void calibrateCompassNow() {
            if (main.isLinkActive()) {
                gps.set(State.WARN, "Disconnect from the drone first: the calibration runs over this PC's USB, with the drone on the bench.");
                return;
            }
            compassCalOpen = true;
            gps.set(State.PENDING, "The compass calibration is open. Close it when you're done.");
            CompassCalibrationDialog.open(javax.swing.SwingUtilities.getWindowAncestor(usb), main, chosenPort, () -> {
                compassCalOpen = false;
                nextTry = System.currentTimeMillis() + 1500; // the calibration lets go of the port first
                done = false;
                applies = 0;
            });
        }

        /** A small heading over a choice, the two left-aligned: for the flight mode positions side by side. */
        private static JComponent stacked(String heading, JComponent choice) {
            JPanel stack = new JPanel();
            stack.setLayout(new javax.swing.BoxLayout(stack, javax.swing.BoxLayout.Y_AXIS));
            stack.setOpaque(false);
            javax.swing.JLabel l = Theme.label(heading, Theme.headingFont(11f), Theme.MUTED);
            l.setAlignmentX(javax.swing.JComponent.LEFT_ALIGNMENT);
            choice.setAlignmentX(javax.swing.JComponent.LEFT_ALIGNMENT);
            stack.add(l);
            stack.add(javax.swing.Box.createVerticalStrut(2));
            stack.add(choice);
            return stack;
        }

        /** SWITCH [AUX2] LOW [Acro] MIDDLE [Angle] HIGH [GPS position hold], each name over its list: saved, written like the rest. */
        private JComponent modeChoices() {
            modeSwitch = ModeSwitch.parse(main.config().getString(ModeSwitch.KEY));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            row.setOpaque(false);
            auxChoice = auxCombo(MODES_OFF);
            auxChoice.setToolTipText("The 3-position switch on your radio for the flight modes (AUX2 = the radio's channel 6 by default). "
                    + "Which switch is which AUX is chosen in the radio step.");
            auxChoice.setSelectedIndex(modeSwitch == null ? 0 : modeSwitch.aux());
            TacticalCombo.install(auxChoice);
            row.add(stacked("SWITCH", auxChoice));
            ModeSwitch shown = modeSwitch == null ? ModeSwitch.DEFAULT : modeSwitch;
            String[] names = {"LOW", "MIDDLE", "HIGH"};
            for (int i = 0; i < 3; i++) { // the position's name over its list: LOW, MIDDLE, HIGH side by side
                javax.swing.JComboBox<ModeSwitch.Mode> c = new javax.swing.JComboBox<>(ModeSwitch.Mode.values());
                c.setPrototypeDisplayValue(ModeSwitch.Mode.POS_HOLD);
                c.setSelectedItem(shown.positions()[i]);
                c.setToolTipText("What the switch in this position does");
                TacticalCombo.install(c);
                c.addActionListener(e -> modeChoiceChanged());
                positionChoices.add(c);
                row.add(stacked(names[i], c));
            }
            auxChoice.addActionListener(e -> modeChoiceChanged());
            enablePositions();
            if (!gpsYes) offerGpsModes(false);
            return row;
        }

        /** FAILSAFE [Leave as it is]: in the failsafe row. */
        private JComponent failsafeChoices() {
            failsafeChoice = ModeSwitch.Failsafe.parse(main.config().getString(ModeSwitch.Failsafe.KEY));
            failsafeCombo = new javax.swing.JComboBox<>(ModeSwitch.Failsafe.values());
            failsafeCombo.setToolTipText("What Betaflight does when the signal is lost for good (failsafe stage 2)");
            failsafeCombo.setSelectedItem(failsafeChoice);
            TacticalCombo.install(failsafeCombo);
            failsafeCombo.addActionListener(e -> failsafeChoiceChanged());
            if (!gpsYes) offerGpsFailsafe(false);
            return failsafeCombo;
        }

        /** [AUX1]: the arm switch. */
        private JComponent armChoices() {
            armAux = ModeSwitch.parseArmAux(main.config().getString(ModeSwitch.ARM_KEY));
            armChoice = auxCombo(MODES_OFF);
            armChoice.setToolTipText("The switch that arms the drone (up = armed; an ARM already on that switch keeps its own range). "
                    + "ARM on any other switch is taken off.");
            armChoice.setSelectedIndex(armAux);
            TacticalCombo.install(armChoice);
            armChoice.addActionListener(e -> armChoiceChanged());
            return armChoice;
        }

        /** [AUX3]: the prearm switch. */
        private JComponent prearmChoices() {
            prearmAux = ModeSwitch.parsePrearmAux(main.config().getString(ModeSwitch.PREARM_KEY));
            prearmChoice = auxCombo(MODES_OFF);
            prearmChoice.setToolTipText("A second switch that must be on (up) before ARM arms: a knocked ARM switch alone does nothing. "
                    + "PREARM on any other switch is taken off.");
            prearmChoice.setSelectedIndex(prearmAux);
            TacticalCombo.install(prearmChoice);
            prearmChoice.addActionListener(e -> prearmChoiceChanged());
            return prearmChoice;
        }

        /** [AUX4]: up = GPS rescue, as the failsafe does - only when Betaflight's failsafe is GPS rescue. */
        private JComponent rescueChoices() {
            rescueAux = ModeSwitch.parseRescueAux(main.config().getString(ModeSwitch.RESCUE_KEY));
            rescueChoice = auxCombo("None");
            rescueChoice.setToolTipText("A switch to call GPS rescue yourself (up = rescue); fitted when Betaflight's failsafe is GPS rescue");
            rescueChoice.setSelectedIndex(rescueAux);
            TacticalCombo.install(rescueChoice);
            rescueChoice.addActionListener(e -> rescueChoiceChanged());
            return rescueChoice;
        }

        /**
         * A switch choice changed: an older radio calibration may have any switch on that AUX (see RADIO_REDO_KEY), so
         * the first-time setup shows the radio step again. No warning here: the pilot asked for none.
         */
        private void switchChanged() {
            main.save(Map.of(RADIO_REDO_KEY, "true"));
        }

        private void armChoiceChanged() {
            int aux = armChoice.getSelectedIndex();
            if (aux == armAux) return;
            armAux = aux;
            main.save(Map.of(ModeSwitch.ARM_KEY, aux == 0 ? ModeSwitch.OFF : Integer.toString(aux)));
            choiceChanged();
            armRow.set(State.PENDING, "");
            switchChanged();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        private void prearmChoiceChanged() {
            int aux = prearmChoice.getSelectedIndex();
            if (aux == prearmAux) return;
            prearmAux = aux;
            main.save(Map.of(ModeSwitch.PREARM_KEY, aux == 0 ? ModeSwitch.OFF : Integer.toString(aux)));
            choiceChanged();
            prearmRow.set(State.PENDING, "");
            switchChanged();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        private void rescueChoiceChanged() {
            if (revertingChoice) return;
            int aux = rescueChoice.getSelectedIndex();
            if (aux == rescueAux) return;
            String tooOld = ModeSwitch.Mode.GPS_RESCUE.tooOld(fcVersion);
            if (aux > 0 && tooOld != null) { // this flight controller's Betaflight is too old: not allowed
                revertingChoice = true;
                rescueChoice.setSelectedIndex(rescueAux);
                revertingChoice = false;
                rescueRow.set(State.WARN, tooOld);
                return;
            }
            rescueAux = aux;
            main.save(Map.of(ModeSwitch.RESCUE_KEY, aux == 0 ? ModeSwitch.OFF : Integer.toString(aux)));
            choiceChanged();
            showRescueChoice();
            switchChanged();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        private void failsafeChoiceChanged() {
            if (revertingChoice) return;
            ModeSwitch.Failsafe f = (ModeSwitch.Failsafe) failsafeCombo.getSelectedItem();
            if (f == null || f == failsafeChoice) return;
            String tooOld = ModeSwitch.Mode.GPS_RESCUE.tooOld(fcVersion);
            if (f == ModeSwitch.Failsafe.GPS_RESCUE && tooOld != null) { // too old a Betaflight: not allowed
                revertingChoice = true;
                failsafeCombo.setSelectedItem(failsafeChoice);
                revertingChoice = false;
                failsafeRow.set(State.WARN, tooOld);
                return;
            }
            failsafeChoice = f;
            main.save(Map.of(ModeSwitch.Failsafe.KEY, f.name()));
            choiceChanged();
            showFailsafeChoice();
            showManual();
            if (!found || failed) return;
            done = false;
            applies = 0;
            nextTry = 0;
        }

        private void showFailsafeChoice() {
            failsafeRow.set(State.PENDING, "");
        }

        private void showRescueChoice() {
            rescueRow.set(rescueAux == 0 ? State.INFO : State.PENDING, rescueAux == 0 ? "None." : "");
        }

        private void enablePositions() {
            for (var c : positionChoices) c.setEnabled(auxChoice.getSelectedIndex() > 0);
        }

        private void modeChoiceChanged() {
            if (revertingChoice) return;
            enablePositions();
            int aux = auxChoice.getSelectedIndex();
            ModeSwitch m = aux == 0 ? null : new ModeSwitch(aux, (ModeSwitch.Mode) positionChoices.get(0).getSelectedItem(),
                    (ModeSwitch.Mode) positionChoices.get(1).getSelectedItem(), (ModeSwitch.Mode) positionChoices.get(2).getSelectedItem());
            if (java.util.Objects.equals(m, modeSwitch)) return;
            // a mode this flight controller's Betaflight is too old for can't be picked
            for (int i = 0; m != null && i < 3; i++) {
                ModeSwitch.Mode chosen = m.positions()[i];
                ModeSwitch old = modeSwitch == null ? ModeSwitch.DEFAULT : modeSwitch;
                String tooOld = chosen.tooOld(fcVersion);
                if (tooOld != null && chosen != old.positions()[i]) {
                    revertingChoice = true;
                    positionChoices.get(i).setSelectedItem(old.positions()[i]);
                    revertingChoice = false;
                    modeRow.set(State.WARN, tooOld);
                    return;
                }
            }
            modeSwitch = m;
            main.save(Map.of(ModeSwitch.KEY, m == null ? ModeSwitch.OFF : m.format()));
            choiceChanged();
            showModeChoice();
            switchChanged();
            showManual();
            if (!found || failed) return; // written as soon as the flight controller is there
            done = false;
            applies = 0;
            nextTry = 0;
        }

        /** After plugging the battery in (some boards only power the GPS from it), or fixing its wiring. */
        private void checkModesAgain() {
            if (!found || busy) return;
            done = false;
            applies = 0;
            nextTry = 0;
            usb.set(State.PENDING, "Reading Betaflight again" + (gpsYes ? " and looking for the GPS (up to " + GPS_WAIT_MS / 1000 + " s)..." : "..."));
        }

        /** CONFIRM: every setting on the page (the cell count too) goes to Betaflight now; the rows go green once it is all there. */
        private void confirmNow() {
            if (lastBoardId == null) return; // no board read (or its id unknown): nothing to confirm for
            confirm.press(lastBoardId); // this board only: one plugged in afterwards needs its own CONFIRM
            confirmButton.setVisible(false);
            if (!cellsConfirmed) {
                cellsConfirmed = true;
                main.save(Map.of(CELLS_KEY, Integer.toString(cells), CELLS_CONFIRMED_KEY, "true"));
            }
            settings.footer("Writing...");
            showConfirmState();
            done = false;
            applies = 0;
            nextTry = 0;
            wizard.update();
        }

        private void showModeChoice() {
            modeRow.set(modeSwitch == null ? State.INFO : State.PENDING, modeSwitch == null ? "Left as it is." : "");
        }

        private void motorsAlreadyChecked() {
            main.save(Map.of(MOTORS_CHECKED_KEY, "true"));
            motors.set(State.OK, "Checked.");
        }

        private void checkMotorsNow() {
            if (main.isLinkActive()) {
                motors.set(State.WARN, "Disconnect from the drone first: the motor test runs over this PC's USB, with the drone on the bench.");
                return;
            }
            motorTestOpen = true; // the page's own rounds would fight the motor test for the USB port
            motors.set(State.PENDING, "The motor test is open. Close it when you're done.");
            MotorTestDialog.open(javax.swing.SwingUtilities.getWindowAncestor(motors), main, () -> {
                motorTestOpen = false;
                nextTry = System.currentTimeMillis() + 1500; // the motor test lets go of the port first
                main.save(Map.of(MOTORS_CHECKED_KEY, "true"));
                motors.set(State.OK, "Checked.");
            });
        }

        /** CHECK NOW: the orientation check takes the USB port; the page reads again once it is closed. */
        private void checkOrientationNow() {
            if (main.isLinkActive()) {
                orientation.set(State.WARN, "Disconnect from the drone first: the check runs over this PC's USB, with the drone on the bench.");
                return;
            }
            orientationOpen = true;
            orientation.set(State.PENDING, "The orientation check is open. Close it when you're done.");
            boolean[] passed = {false};
            OrientationCheckDialog.open(javax.swing.SwingUtilities.getWindowAncestor(orientation), main, chosenPort, () -> {
                passed[0] = true;
                orientation.set(State.OK, "Checked: Betaflight sees it the right way round.");
            }, () -> {
                orientationOpen = false;
                nextTry = System.currentTimeMillis() + 1500; // the check lets go of the port first
                done = false; // it may have changed the board alignment: read again
                applies = 0;
                if (!passed[0]) orientation.set(State.WARN, "Not checked to the end. <b>Check now</b> again before flying.");
            });
        }

        private int savedCells() {
            try {
                int n = Integer.parseInt(main.config().getString(CELLS_KEY));
                if (n >= BetaflightPlan.MIN_CELLS && n <= BetaflightPlan.MAX_CELLS) return n;
            } catch (NumberFormatException ignored) {
                // not chosen yet
            }
            return BetaflightPlan.DEFAULT_CELLS;
        }

        /** A new count: remembered, and written to the flight controller again (it may already have the old one). */
        private void cellsChanged(int n) {
            if (n == cells) return;
            cells = n;
            if (syncingSpinner) return; // Betaflight's own forced count shown, not the pilot's choice
            cellsConfirmed = true; // touched: the pilot's count
            measuredCells.setVisible(measured > 0 && measured != cells);
            main.save(Map.of(CELLS_KEY, Integer.toString(n), CELLS_CONFIRMED_KEY, "true"));
            choiceChanged();
            showManual();
            if (!found || failed) return; // written as soon as the flight controller is there
            done = false;
            applies = 0;
            nextTry = 0;
            battery.set(State.PENDING, "");
        }

        /** A choice changed: the page is no longer confirmed - CONFIRM comes back, the rows leave green. */
        private void choiceChanged() {
            if (!confirm.confirmed() && !confirm.armed()) return;
            confirm.clear();
            main.save(Map.of(CONFIRMED_KEY, confirm.saved()));
            if (confirmButton != null) confirmButton.setVisible(true);
            showConfirmState();
        }

        /** The settings' lights: red until CONFIRM, busy while its write runs, then the settings' own (green when all is set). */
        private void showConfirmState() {
            settings.override = confirm.confirmed() ? null : confirm.armed() ? State.PENDING : State.BAD;
            settings.refresh();
        }

        /** OK once the page is confirmed; until then a setting that is right still shows as to do. */
        private State okState() {
            return confirm.confirmed() ? State.OK : State.TODO;
        }

        /** The manual way, for a drone without USB to this PC (and for whatever couldn't be set by itself). */
        private void showByHand() {
            javax.swing.JLabel text = new javax.swing.JLabel("<html><div style='width:520px'>" + manualHtml + "</div></html>");
            text.setFont(Theme.font(java.awt.Font.PLAIN, 14f));
            javax.swing.JOptionPane.showMessageDialog(javax.swing.SwingUtilities.getWindowAncestor(usb), text,
                    "Betaflight settings by hand", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }

        /** The "By hand" button stands out while something needs doing that way. */
        private void markByHand(boolean needed) {
            if (byHand != null) byHand.setText(needed ? "By hand (needed)" : "By hand");
        }

        private void showManual() {
            ModeSwitch m = modeSwitch;
            int rescue = gpsYes ? rescueAux : 0;
            int arm = armAux;
            int prearm = prearmAux;
            manualHtml = ("Can't plug the drone into this PC? Make the same settings in Betaflight Configurator: "
                    + "<b>Receiver</b> tab > Receiver Mode <b>MSP RX input</b> > Save. "
                    + "<b>CLI</b> tab > <b>set serial_update_rate_hz = 2000</b> (1000 on F411 boards), "
                    + "<b>set force_battery_cell_count = " + cells + "</b> > <b>save</b>."
                    + (!gpsYes ? "" : " <b>Ports</b> tab: Sensor input <b>GPS</b> on UART" + gpsUart + " > Save. <b>Configuration</b> tab: "
                    + "<b>GPS</b> on" + (compassYes ? ", <b>Magnetometer</b> on" : "") + " > Save."
                    + (compassYes ? " <b>Setup</b> tab: <b>Calibrate Magnetometer</b>." : ""))
                    + (arm == 0 ? "" : " <b>Modes</b> tab: <b>ARM</b> on AUX" + arm + " 1700-2100.")
                    + (prearm == 0 ? "" : " <b>Modes</b> tab: <b>PREARM</b> on AUX" + prearm + " 1700-2100.")
                    + (m == null ? "" : " <b>Modes</b> tab: on AUX" + m.aux() + ", " + modeRanges(m) + " > Save.")
                    + (failsafeChoice == ModeSwitch.Failsafe.KEEP ? "" : " <b>Failsafe</b> tab: Stage 2 procedure <b>"
                    + failsafeChoice.title + "</b>.")
                    + (rescue == 0 ? "" : " With the failsafe on GPS rescue: <b>GPS RESCUE</b> on AUX" + rescue + " 1700-2100.")
                    + " <b>Modes</b> tab: <b>BEEPER</b> on AUX" + ModeSwitch.BEEPER_AUX + " 1700-2100 alone (the BEEPER button). <b>CLI</b> tab: "
                    + "<b>beeper -ALL</b>, then <b>beeper " + String.join("</b>, <b>beeper ", Beepers.KEEP) + "</b>, <b>beacon RX_SET</b> > <b>save</b>.");
        }

        /** "ANGLE 1300-1700, POS HOLD 1700-2100" for the manual way, named as Betaflight Configurator's Modes tab does. */
        private static String modeRanges(ModeSwitch m) {
            String[] ranges = {"900-1300", "1300-1700", "1700-2100"};
            List<String> parts = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (m.positions()[i] != ModeSwitch.Mode.ACRO) parts.add("<b>" + m.positions()[i].configuratorName + "</b> " + ranges[i]);
            }
            return parts.isEmpty() ? "nothing (acro throughout)" : String.join(", ", parts);
        }

        private static final String PAUSED_TEXT = "Disconnected: the USB port is free, e.g. for Betaflight Configurator. Press <b>Reconnect</b> to carry on.";

        /** DISCONNECT / RECONNECT: the rounds stop, so the USB port is free; a round in progress finishes its read first. */
        private void togglePaused() {
            paused = !paused;
            disconnect.setText(paused ? "Reconnect" : "Disconnect");
            if (paused) {
                abandoned = true; // a round still running reads, but writes nothing
                found = false;
                failed = false;
                done = false;
                markByHand(false);
                calibrateCompass.setEnabled(false);
                usb.set(busy ? State.PENDING : State.INFO, busy ? "Finishing the current read, then the port is free..." : PAUSED_TEXT);
                for (Part p : new Part[]{receiver, stickDelay, battery}) p.set(State.PENDING, "");
            } else {
                abandoned = false;
                applies = 0;
                nextTry = 0;
                usb.set(State.PENDING, "Looking for it...");
            }
            wizard.update();
        }

        @Override
        void onShow() {
            abandoned = paused;
        }

        @Override
        void onHide() {
            abandoned = true; // a round still running may read, but must not write
        }

        @Override
        void refresh() {
            // the motor test or the compass calibration may have the port, however it was opened (Check now or SETTINGS)
            if (paused || motorTestOpen || MotorTestDialog.isOpen() || compassCalOpen || CompassCalibrationDialog.isOpen()
                    || orientationOpen || OrientationCheckDialog.isOpen()
                    || busy || done || System.currentTimeMillis() < nextTry) return;
            busy = true;
            String writeFor = applies < MAX_APPLIES ? confirm.armedFor() : null; // the board CONFIRM was pressed for
            int cellTarget = cells;
            boolean cellsOk = cellsConfirmed;
            ModeSwitch modeTarget = modeSwitch;
            Boolean gpsTarget = gpsAnswered ? gpsYes : null;
            int rescueTarget = gpsYes ? rescueAux : 0;
            ModeSwitch.Failsafe failsafeTarget = gpsYes || failsafeChoice != ModeSwitch.Failsafe.GPS_RESCUE ? failsafeChoice : ModeSwitch.Failsafe.KEEP;
            int armTarget = armAux;
            int prearmTarget = prearmAux;
            Gps gpsWanted = Gps.none(gpsTarget, gpsUart, compassYes);
            background(() -> work(writeFor, cellTarget, cellsOk, modeTarget, rescueTarget, failsafeTarget, armTarget, prearmTarget, gpsWanted), this::show);
        }

        /**
         * One round: find the FC, read the settings, and change them in the same CLI session if needed.
         * @param writeFor the board CONFIRM was pressed for: only that board is written to; null = read only
         */
        private Read work(String writeFor, int cellTarget, boolean cellsOk, ModeSwitch modeTarget, int rescueTarget,
                          ModeSwitch.Failsafe failsafeTarget, int armTarget, int prearmTarget, Gps gpsWanted) {
            List<BetaflightUsb.Port> all = BetaflightUsb.findFlightControllers();
            if (all.isEmpty()) return null;
            List<String> names = all.stream().map(BetaflightUsb.Port::systemName).toList();
            String chosen = chosenPort;
            List<BetaflightUsb.Port> ports = chosen == null ? all : all.stream().filter(p -> p.systemName().equals(chosen)).toList();
            if (ports.isEmpty()) {
                return new Read(chosen, chosen + " isn't plugged in - pick another port, or Automatic.", cellTarget, modeTarget,
                        rescueTarget, failsafeTarget, armTarget, prearmTarget, names, gpsWanted);
            }
            // each port that looks like one, not just the first: a GPS or ESC adapter may come first. Asked over MSP
            // first (no restart, nothing written); only Betaflight gets the CLI
            String why = null;
            for (BetaflightUsb.Port port : ports) {
                try (BetaflightUsb fc = BetaflightUsb.open(port)) {
                    String firmware = fc.firmware();
                    if (!firmware.startsWith("BTFL")) {
                        why = port.systemName() + " runs <b>" + firmwareName(firmware) + "</b>, not Betaflight. DRAUPNE Link needs "
                                + "Betaflight: flash it with Betaflight Configurator. Nothing was changed.";
                        continue;
                    }
                    return work(fc, port, writeFor, cellTarget, cellsOk, modeTarget, rescueTarget, failsafeTarget, armTarget, prearmTarget, names, gpsWanted);
                } catch (IOException e) {
                    why = port.systemName() + ": " + e.getMessage();
                }
            }
            return new Read(ports.get(0).systemName(), why, cellTarget, modeTarget, rescueTarget, failsafeTarget, armTarget, prearmTarget, names, gpsWanted);
        }

        private Read work(BetaflightUsb fc, BetaflightUsb.Port port, String writeFor, int cellTarget, boolean cellsOk, ModeSwitch modeTarget,
                          int rescueTarget, ModeSwitch.Failsafe failsafeTarget, int armTarget, int prearmTarget, List<String> ports, Gps gpsWanted) {
            try {
                // which board this is, over MSP before anything else: CONFIRM pressed for another one writes nothing here
                String boardId = boardId(fc);
                boolean mayApply = writeFor != null && writeFor.equals(boardId);
                if (writeFor != null && !mayApply) main.logLine("Betaflight: another flight controller than the one confirmed - nothing written.");
                java.util.Set<Integer> modesOffered = null; // over MSP, before the CLI: which modes this board has
                try {
                    modesOffered = fc.boxIds();
                } catch (IOException e) {
                    // unknown: every chosen mode is written
                }
                BetaflightUsb.BatteryState batteryNow = null;
                try {
                    batteryNow = fc.batteryState();
                } catch (IOException e) {
                    // an old Betaflight without it
                }
                int featureMask = fc.featureMask();
                boolean gpsOnFc = BetaflightPlan.hasGps(featureMask);
                boolean magFound = false;
                try {
                    magFound = GpsSetup.magInStatus(fc.msp(101 /* MSP_STATUS */, null));
                } catch (IOException e) {
                    // not known
                }
                // a GPS wanted: is one there? Only asked while Betaflight's GPS is on (else it is turned on first)
                Boolean gpsFound = null;
                if (gpsWanted.wanted() && gpsOnFc) {
                    try {
                        gpsFound = fc.waitForGps(GPS_WAIT_MS);
                    } catch (IOException e) {
                        // not known: GPS modes wait
                    }
                }
                // the receiver, over MSP and before the CLI (see BetaflightPlan): the CLI's save below stores it
                boolean mspRx = BetaflightPlan.isMspReceiver(featureMask);
                String receiverNow = BetaflightPlan.receiverNames(featureMask);
                boolean switchRx = !mspRx && mayApply && !abandoned;
                if (switchRx) fc.setFeatureMask(BetaflightPlan.mspReceiverMask(featureMask));
                fc.enterCli();
                String version = BetaflightPlan.version(fc.command("version"));
                String board = boardName(fc);
                // where the arm switch is: the sticks then also wait for it to be off (RcSession). Saved below - after a
                // write, from Betaflight's modes as written, not as they were before ARM moved
                String aux = fc.command("aux");
                ArmSwitch arm = ArmSwitch.fromCli(aux);
                String rate = fc.command("get serial_update_rate_hz");
                String forced = fc.command("get force_battery_cell_count");
                List<BetaflightPlan.Change> plan = new java.util.ArrayList<>(BetaflightPlan.plan(rate, version, forced, cellTarget, cellsOk));
                if (!mspRx) {
                    plan.add(0, new BetaflightPlan.Change("Receiver: MSP RX input (the phone sends the sticks over MSP), now "
                            + receiverNow, List.of()));
                }
                // the GPS as answered: its feature, its port, the compass
                String serial = gpsWanted.wanted() ? fc.command("serial") : "";
                Gps gpsRead = gpsWanted;
                if (gpsWanted.wanted()) {
                    List<BetaflightPlan.Change> gpsPlan = GpsSetup.gpsPlan(featureMask, serial, fc.command("get gps_auto_baud"), gpsWanted.uart());
                    plan.addAll(gpsPlan);
                    String portNote = "";
                    for (BetaflightPlan.Change c : gpsPlan) {
                        int at = c.description().indexOf(" (");
                        if (c.description().startsWith("GPS on UART") && at > 0) portNote = c.description().substring(at + 2, c.description().length() - 1);
                    }
                    String magHardware = GpsSetup.magHardware(fc.command("get mag_hardware"));
                    BetaflightPlan.Change compass = GpsSetup.compassPlan(gpsWanted.compass(), magHardware);
                    if (compass != null) plan.add(compass);
                    boolean calibrated = gpsWanted.compass() && magFound && GpsSetup.calibrated(fc.command("get mag_calibration"));
                    if (gpsWanted.compass() && magFound) rememberCompass(calibrated);
                    gpsRead = new Gps(gpsWanted.target(), gpsWanted.uart(), gpsWanted.compass(), gpsOnFc, GpsSetup.gpsPortName(serial),
                            GpsSetup.uarts(serial), magFound, calibrated, !gpsPlan.isEmpty(), portNote, compass != null);
                } else {
                    gpsRead = new Gps(gpsWanted.target(), gpsWanted.uart(), gpsWanted.compass(), gpsOnFc, "", List.of(), magFound, false, false, "", false);
                }
                // silent unless BEEPER is pressed or the phone is unplugged (Beepers); the button's mode on its AUX (BEEPER_AUX) below
                BetaflightPlan.Change beepers = Beepers.plan(fc.command("beeper list"), fc.command("beeper"), fc.command("beacon"));
                if (beepers != null) plan.add(beepers);
                int beeperTarget = modesOffered == null || modesOffered.contains(ModeSwitch.BEEPER_MODE) ? ModeSwitch.BEEPER_AUX : 0;
                java.util.Map<ModeSwitch.Mode, String> blocked = new java.util.EnumMap<>(ModeSwitch.Mode.class);
                String rescueNote = null;
                String failsafeNote = null;
                String failsafeProblem = null;
                // the failsafe, as chosen; the rescue switch only goes on while the failsafe is (or becomes) GPS rescue
                boolean readFailsafe = rescueTarget > 0 || failsafeTarget != ModeSwitch.Failsafe.KEEP;
                String failsafe = readFailsafe ? ModeSwitch.Failsafe.current(fc.command("get failsafe_procedure")) : null;
                boolean rescueNow = "GPS-RESCUE".equals(failsafe);
                boolean rescueWanted = failsafeTarget == ModeSwitch.Failsafe.GPS_RESCUE
                        || (rescueNow && failsafeTarget == ModeSwitch.Failsafe.KEEP);
                List<ModeSwitch.Mode> gpsModes = new java.util.ArrayList<>(modeTarget == null ? List.of() : modeTarget.gpsModes());
                if ((rescueWanted && rescueTarget > 0 || failsafeTarget == ModeSwitch.Failsafe.GPS_RESCUE)
                        && !gpsModes.contains(ModeSwitch.Mode.GPS_RESCUE)) {
                    gpsModes.add(ModeSwitch.Mode.GPS_RESCUE);
                }
                if (!gpsModes.isEmpty()) {
                    for (ModeSwitch.Mode m : gpsModes) {
                        String tooOld = m.tooOld(version);
                        if (tooOld != null) {
                            blocked.put(m, tooOld);
                        } else if (!gpsWanted.wanted()) {
                            blocked.put(m, "Needs a GPS: answer <b>Yes</b> under GPS above.");
                        } else if (!gpsOnFc) {
                            blocked.put(m, "Betaflight's GPS is being turned on; the GPS is looked for once it has restarted.");
                        } else if (!Boolean.TRUE.equals(gpsFound)) {
                            blocked.put(m, "No GPS found - see the GPS row.");
                        }
                    }
                }
                boolean rescueFailsafe = rescueNow && failsafeTarget != ModeSwitch.Failsafe.DROP;
                if (failsafeTarget == ModeSwitch.Failsafe.GPS_RESCUE && !rescueNow) {
                    if (blocked.containsKey(ModeSwitch.Mode.GPS_RESCUE)) {
                        failsafeProblem = blocked.get(ModeSwitch.Mode.GPS_RESCUE);
                    } else {
                        plan.add(new BetaflightPlan.Change("Failsafe: GPS rescue", List.of("set failsafe_procedure = GPS-RESCUE")));
                        rescueFailsafe = true;
                    }
                } else if (failsafeTarget == ModeSwitch.Failsafe.DROP && failsafe != null && !"DROP".equals(failsafe)) {
                    plan.add(new BetaflightPlan.Change("Failsafe: drop", List.of("set failsafe_procedure = DROP")));
                }
                int rescueOn = rescueFailsafe ? rescueTarget : 0;
                // with a GPS, GPS rescue (failsafe or switch) would block arming until the GPS has a fix - indoors too:
                // always allowed (the pilot's rule), written with the rest
                if (gpsWanted.wanted() && !fc.command("get gps_rescue_allow_arming_without_fix").replace(" ", "").contains("=ON")) {
                    plan.add(new BetaflightPlan.Change("Arming without a GPS fix allowed (gps_rescue_allow_arming_without_fix)",
                            List.of("set gps_rescue_allow_arming_without_fix = ON")));
                }
                ModeSwitch.Plan modes = modeTarget == null && rescueOn == 0 && armTarget == 0 && prearmTarget == 0 && beeperTarget == 0 ? null
                        : ModeSwitch.plan(aux, modesOffered, blocked, modeTarget, rescueOn, armTarget, prearmTarget, beeperTarget);
                List<String> replaced = modes == null ? List.of() : modes.replaced();
                if (modes != null && !modes.commands().isEmpty()) {
                    List<String> what = new java.util.ArrayList<>();
                    if (modeTarget != null && modes.problem() == null) what.add("flight modes on AUX" + modeTarget.aux() + ": " + modeTarget.describe());
                    if (rescueOn > 0 && modes.rescueProblem() == null) what.add("GPS rescue up on AUX" + rescueOn);
                    if (armTarget > 0 && modes.armProblem() == null) what.add("ARM on AUX" + armTarget);
                    if (prearmTarget > 0 && modes.prearmProblem() == null) what.add("PREARM on AUX" + prearmTarget);
                    if (beeperTarget > 0 && modes.commands().stream().anyMatch(c -> c.matches("aux \\d+ " + ModeSwitch.BEEPER_MODE + " .*"))) {
                        what.add("BEEPER on AUX" + beeperTarget + " (the app's BEEPER button)");
                    }
                    plan.add(new BetaflightPlan.Change("Modes: " + String.join("; ", what) + "." + takenOff(replaced), modes.commands()));
                }
                boolean apply = mayApply && !plan.isEmpty() && version.startsWith("Betaflight"); // never INAV & co.
                if (apply && abandoned) { // the page was left meanwhile: nothing is written behind the pilot's back
                    fc.exitCli();
                    saveArm(arm);
                    main.logLine("Betaflight: the page was left while reading - nothing written.");
                } else {
                    java.nio.file.Path backup = null;
                    String refused = null;
                    boolean saved = false;
                    if (apply) {
                        // all or nothing: no backup, no write; a command not taken as sent leaves the CLI without saving
                        String diff = fc.command("diff all", 20_000); // what it was, before anything changes
                        if (switchRx) diff = BetaflightPlan.restoreReceiver(diff, featureMask); // but for the receiver, already switched
                        if (!CliWrite.usableBackup(diff)) main.logLine("Betaflight's \"diff all\" didn't come back whole: " + brief(diff));
                        backup = CliWrite.usableBackup(diff) ? backup(diff, version) : null;
                        if (backup == null) {
                            refused = "No backup of Betaflight's settings could be made (see the log), so nothing was written.";
                        } else {
                            List<String> commands = new java.util.ArrayList<>();
                            for (BetaflightPlan.Change c : plan) commands.addAll(c.commands());
                            CliWrite.Result written = CliWrite.run(fc::command, commands, main::logLine);
                            if (written.complete()) {
                                fc.saveAndReboot();
                                saved = true;
                                // the ARM switch as written now, for the sticks (RcSession) - not as read before it moved
                                saveArm(written.auxAfter().isEmpty() ? arm : ArmSwitch.fromCli(written.auxAfter()));
                                main.logLine("Betaflight: " + plan.stream().map(BetaflightPlan.Change::description).collect(Collectors.joining("; "))
                                        + ". Its settings before: " + backup);
                            } else {
                                refused = written.stopped() + ". Nothing was saved: the flight controller is as it was.";
                            }
                        }
                        if (!saved) {
                            fc.exitCli(); // "exit": Betaflight drops every change since the CLI opened (the receiver's too) and restarts
                            saveArm(arm);
                            main.logLine("Betaflight: write stopped, nothing saved - " + refused);
                        }
                    } else {
                        saveArm(arm);
                        fc.exitCli(); // Betaflight restarts (nothing changed, or the receiver only in RAM) - which also clears a stale "MSP" arming block
                    }
                    return new Read(port.systemName(), version, mspRx, BetaflightPlan.serialRate(rate),
                            BetaflightPlan.targetSerialRate(version), BetaflightPlan.forcedCells(forced), cellTarget, saved, null, arm, backup, ports,
                            modeTarget, modes, gpsFound, rescueNote, rescueTarget, failsafe, failsafeTarget, rescueFailsafe,
                            failsafeProblem, failsafeNote, armTarget, refused, List.copyOf(plan), replaced, board, batteryNow,
                            ModeSwitch.armAuxes(aux), receiverNow, cellsOk, prearmTarget, ModeSwitch.prearmAuxes(aux), gpsRead, boardId);
                }
                return new Read(port.systemName(), version, mspRx, BetaflightPlan.serialRate(rate),
                        BetaflightPlan.targetSerialRate(version), BetaflightPlan.forcedCells(forced), cellTarget, false, null, arm, null, ports,
                        modeTarget, modes, gpsFound, rescueNote, rescueTarget, failsafe, failsafeTarget, rescueFailsafe,
                        failsafeProblem, failsafeNote, armTarget, null, List.copyOf(plan), replaced, board, batteryNow,
                        ModeSwitch.armAuxes(aux), receiverNow, cellsOk, prearmTarget, ModeSwitch.prearmAuxes(aux), gpsRead, boardId);
            } catch (IOException e) {
                return new Read(port.systemName(), e.getMessage(), cellTarget, modeTarget, rescueTarget, failsafeTarget, armTarget, prearmTarget, ports, gpsWanted);
            }
        }

        /** The board's name and the craft's ("IFLIGHT_BLITZ_F722 - Vanguard"), from the CLI; "" when neither is set. */
        private static String boardName(BetaflightUsb fc) throws IOException {
            String board = BetaflightPlan.boardName(fc.command("board_name"));
            String craft = BetaflightPlan.craftName(fc.command("get craft_name")); // Betaflight 4.4 on
            if (craft.isEmpty()) craft = BetaflightPlan.craftName(fc.command("name")); // older
            if (board.isEmpty()) return craft;
            return craft.isEmpty() ? board : board + " - " + craft;
        }

        /** A CLI reply's first line, short, for the log; "(ok)" when Betaflight printed nothing. */
        static String brief(String reply) {
            return CliWrite.brief(reply);
        }

        /** Where ARM is, saved for the sticks (they wait for it to be off: RcSession); not covered (null) leaves the last. */
        private void saveArm(ArmSwitch arm) {
            if (arm != null) main.save(Map.of(ArmSwitch.KEY, arm.format()));
        }

        /** The board's id over MSP (MSP_UID, else MSP_BOARD_INFO): what CONFIRM belongs to; null if neither answers. */
        private static String boardId(BetaflightUsb fc) {
            byte[] uid = null, info = null;
            try {
                uid = fc.msp(160 /* MSP_UID */, null);
            } catch (IOException e) {
                // the board info below
            }
            if (uid == null || uid.length < 12) {
                try {
                    info = fc.msp(4 /* MSP_BOARD_INFO */, null);
                } catch (IOException e) {
                    // unknown: nothing is written
                }
            }
            return SetupConfirm.boardId(uid, info);
        }

        /** " Taken off: ARM on AUX3, BEEPER on AUX2." or "". */
        private static String takenOff(List<String> replaced) {
            return replaced.isEmpty() ? "" : " Taken off: " + String.join(", ", replaced) + ".";
        }

        /** MSP's four-letter firmware id as a name: INAV, ArduPilot, Cleanflight ... */
        static String firmwareName(String variant) {
            return switch (variant) {
                case "INAV" -> "INAV";
                case "ARDU" -> "ArduPilot";
                case "CLFL" -> "Cleanflight";
                case "BAFL" -> "Baseflight";
                case "EMUF" -> "EmuFlight";
                case "" -> "something that doesn't answer MSP";
                default -> variant;
            };
        }

        /** Betaflight's "diff all" into Documents\DRAUPNE Link\Betaflight backups; null (and logged) if it can't be saved. */
        private java.nio.file.Path backup(String diff, String version) {
            try {
                String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                java.nio.file.Path file = de.droiddrone.pc.AppPaths.betaflightBackupsDir().resolve("betaflight-" + stamp + ".txt");
                java.nio.file.Files.writeString(file, "# " + version + "\n# saved by DRAUPNE Link before Drone setup changed it. "
                        + "To go back: paste this into Betaflight Configurator's CLI tab, then type save.\n\n" + diff + "\n");
                return file;
            } catch (IOException e) {
                main.logLine("Could not save a backup of Betaflight's settings: " + e.getMessage());
                return null;
            }
        }

        /** "Set." when the switch arms up, as the plan writes it; else where Betaflight really arms. */
        private String describe(ArmSwitch a) {
            String position = ModeSwitch.armedPosition(a);
            return position.equals("up") ? setText() : "Arms " + position + ".";
        }

        /** "Set." once confirmed; before that the ring alone says it is still to confirm. */
        private String setText() {
            return confirm.confirmed() ? "Set." : "";
        }

        /** The port list: Automatic plus every port found, keeping the choice. */
        private void showPorts(List<String> ports) {
            if (ports == null) return;
            java.util.List<String> items = new java.util.ArrayList<>();
            items.add(AUTOMATIC);
            items.addAll(ports);
            if (chosenPort != null && !items.contains(chosenPort)) items.add(chosenPort);
            javax.swing.DefaultComboBoxModel<String> model = (javax.swing.DefaultComboBoxModel<String>) portChoice.getModel();
            boolean same = model.getSize() == items.size();
            for (int i = 0; same && i < items.size(); i++) same = items.get(i).equals(model.getElementAt(i));
            if (same) return;
            model.removeAllElements();
            items.forEach(model::addElement);
            model.setSelectedItem(chosenPort == null ? AUTOMATIC : chosenPort);
        }

        private void show(Read r) {
            busy = false;
            long now = System.currentTimeMillis();
            if (paused) { // DISCONNECT came while this round ran: it is over, the port is free
                usb.set(State.INFO, PAUSED_TEXT);
                wizard.update();
                return;
            }
            found = r != null;
            failed = r != null && r.error() != null;
            markByHand(r == null);
            readAgain.setText(r == null || r.error() != null ? "Retry" : "Read again");
            calibrateCompass.setEnabled(found && !failed);
            confirmButton.setEnabled(found && !failed && r.boardId() != null); // CONFIRM is for a board known by its id
            if (r == null) {
                nextTry = now + 1500;
                if (now - restartedAt < RESTART_MS) {
                    usb.set(State.PENDING, "Betaflight restarts...");
                } else {
                    usb.set(State.WARN, "Not found yet. Use a USB <b>data</b> cable (some only charge). If Windows doesn't see the drone, "
                            + "install the STM32 VCP driver (ImpulseRC Driver Fixer) once.");
                }
                receiver.set(State.PENDING, "");
                stickDelay.set(State.PENDING, "");
                return;
            }
            showPorts(r.ports());
            portChoice.setVisible(r.ports() != null && r.ports().size() > 1 || chosenPort != null);
            if (r.error() != null) {
                nextTry = now + 2000;
                sameErrorCount = r.error().equals(lastError) ? sameErrorCount + 1 : 1;
                lastError = r.error();
                usb.set(State.BAD, r.error() + (sameErrorCount >= SAME_ERROR_LIMIT
                        ? "<br><b>Unplug and plug the drone's USB in again.</b>"
                        : "<br>Trying again by itself every few seconds, or press <b>Retry</b>."));
                return;
            }
            lastError = null;
            sameErrorCount = 0;
            restartedAt = now; // the CLI was open and is closed now: Betaflight restarts
            if (!r.version().isEmpty()) fcVersion = r.version();
            lastBoardId = r.boardId();
            // CONFIRM, and the green rows, belong to the board they were given for: another one plugged in needs its own
            if (r.boardId() != null && confirm.seen(r.boardId())) main.save(Map.of(CONFIRMED_KEY, confirm.saved()));
            if (r.applied() || r.refused() != null) lastRefused = r.refused();
            if (r.refused() != null) { // the write stopped and nothing was saved: the page says why, CONFIRM again
                confirm.clear();
                main.save(Map.of(CONFIRMED_KEY, confirm.saved()));
            }
            if (r.applied() && r.modes() != null) lastReplaced = r.modes().replaced();
            if (r.switchesWritten()) switchChanged();
            String usbText = "<b>" + r.port() + "</b>" + (r.version().isEmpty() ? "" : " - " + BetaflightPlan.shortVersion(r.version()))
                    + (r.board().isEmpty() ? "" : " - " + r.board())
                    + (r.backup() == null ? "" : "<br>Its settings before the change: " + r.backup())
                    + (lastRefused == null ? "" : "<br><b>Nothing written:</b> " + lastRefused);
            usb.set(lastRefused == null ? State.OK : State.WARN, usbText);
            showMeasured(r.battery());
            if (!r.gps().uarts().isEmpty()) showUarts(r.gps().uarts());
            if (r.gps().target() == null) { // not asked yet: Betaflight's answer, and the lists follow it
                setGps(r.gps().onFc(), true, true);
                return;
            }
            if (r.applied()) {
                applies++;
                nextTry = now + 4000; // then read again, to check the settings stuck
                settings.footer("Saved - Betaflight restarts, then the settings are checked...");
                receiver.set(State.PENDING, "");
                if (r.modes() != null && !r.modes().blocked().isEmpty() && r.modeTarget() != null) {
                    modeRow.set(State.PENDING, "Betaflight restarts, then the GPS is looked for (up to " + GPS_WAIT_MS / 1000 + " s)...");
                }
                if (r.gps().wanted()) gps.set(State.PENDING, "Betaflight restarts, then the GPS is looked for...");
                stickDelay.set(State.PENDING, "");
                battery.set(State.PENDING, "");
                return;
            }
            Boolean gpsNow = gpsAnswered ? gpsYes : null;
            ModeSwitch.Failsafe failsafeNow = gpsYes || failsafeChoice != ModeSwitch.Failsafe.GPS_RESCUE ? failsafeChoice : ModeSwitch.Failsafe.KEEP;
            if (r.cellTarget() != cells || r.cellsConfirmed() != cellsConfirmed || !java.util.Objects.equals(r.modeTarget(), modeSwitch)
                    || r.rescueTarget() != (gpsYes ? rescueAux : 0) || r.failsafeTarget() != failsafeNow || r.armTarget() != armAux
                    || r.prearmTarget() != prearmAux || !java.util.Objects.equals(r.gps().target(), gpsNow)
                    || r.gps().uart() != gpsUart || r.gps().compass() != compassYes) {
                // changed meanwhile: write it
                applies = 0;
                nextTry = 0;
                return;
            }
            done = true;
            // nothing is written before CONFIRM: the rows say what it would set. Everything there after it: confirmed
            if (confirm.armed() && r.plan().isEmpty() && !confirm.confirmed()) {
                confirm.done(confirm.armedFor());
                main.save(Map.of(CONFIRMED_KEY, confirm.saved()));
            } else if (!confirm.armed() && confirm.confirmed() && !r.plan().isEmpty()) {
                confirm.clear(); // changed since (Configurator, a reflash): not as confirmed any more - CONFIRM again
                main.save(Map.of(CONFIRMED_KEY, confirm.saved()));
            }
            showConfirmState();
            boolean pendingWrite = !confirm.armed();
            showChanges(r, pendingWrite);
            ModeSwitch.Plan armPlan = r.modes();
            if (r.armTarget() > 0 && armPlan != null && armPlan.armProblem() != null) {
                armRow.set(State.WARN, armPlan.armProblem());
            } else if (r.armTarget() > 0 && (r.arm() == null || r.arm().channel() - 3 != r.armTarget())) {
                armRow.set(pendingWrite ? State.TODO : State.BAD, pendingWrite ? WITH_WRITE + takenOff(r.replaced())
                        : "Could not set it - the Modes tab (see <b>By hand</b>).");
            } else if (r.arm() == null) {
                armRow.set(State.WARN, r.armAuxes().size() > 1
                        ? "On more than one switch in Betaflight - set it on one."
                        : "On no switch in Betaflight - the drone can't be armed. Choose a switch (AUX1 is usual).");
            } else {
                armRow.set(okState(), describe(r.arm()) + (r.armTarget() > 0 ? takenOff(lastReplaced) : ""));
            }
            boolean prearmOnIt = r.prearmAuxes().contains(r.prearmTarget());
            if (r.prearmTarget() > 0 && armPlan != null && armPlan.prearmProblem() != null) {
                prearmRow.set(State.WARN, armPlan.prearmProblem());
            } else if (r.prearmTarget() > 0 && !prearmOnIt) {
                prearmRow.set(pendingWrite ? State.TODO : State.BAD, pendingWrite ? WITH_WRITE
                        : "Could not set it - the Modes tab (see <b>By hand</b>).");
            } else if (r.prearmTarget() > 0) {
                prearmRow.set(r.prearmAuxes().size() > 1 ? State.WARN : okState(), r.prearmAuxes().size() > 1
                        ? "Set, but PREARM is on more than one switch in Betaflight - set it on one." : setText());
            } else if (r.prearmAuxes().isEmpty()) {
                prearmRow.set(State.INFO, "None: ARM alone arms.");
            } else {
                List<String> on = new java.util.ArrayList<>();
                for (int a : r.prearmAuxes()) on.add("AUX" + a);
                prearmRow.set(okState(), "On " + String.join(", ", on) + ", left as it is.");
            }
            // BEEPER never goes where ARM or PREARM is: the pilot moves that switch first, BEEPER isn't written till then
            String beeperProblem = armPlan == null ? null : armPlan.beeperProblem();
            if (beeperProblem != null) (beeperProblem.startsWith("PREARM") ? prearmRow : armRow).set(State.WARN, beeperProblem);
            receiver.set(r.msp() ? okState() : pendingWrite ? State.TODO : State.BAD, r.msp() ? "MSP RX input."
                    : pendingWrite ? "MSP RX input - now " + r.receiverNow() + ". " + WITH_WRITE
                    : "Could not set it - see <b>By hand</b>.");
            if (r.rate() < 0) {
                stickDelay.set(State.WARN, "Not in this Betaflight (it came in 4.2) - update Betaflight for the least stick delay.");
            } else if (r.rate() >= r.target()) {
                stickDelay.set(okState(), "serial_update_rate_hz " + r.rate() + ".");
            } else if (pendingWrite) {
                stickDelay.set(State.TODO, "serial_update_rate_hz " + r.rate() + " -> " + r.target() + ". " + WITH_WRITE);
            } else {
                stickDelay.set(State.BAD, "Could not set it (" + r.rate() + ") - see <b>By hand</b>.");
            }
            boolean cellsWrong = showCells(r, pendingWrite);
            boolean gpsWrong = showGps(r, pendingWrite);
            boolean modesWrong = showModes(r, pendingWrite);
            modesWrong |= showRescue(r, pendingWrite);
            modesWrong |= showFailsafe(r, pendingWrite);
            boolean byHandNeeded = !pendingWrite && (!r.msp() || (r.rate() >= 0 && r.rate() < r.target()) || cellsWrong || gpsWrong || modesWrong);
            markByHand(byHandNeeded);
            cleanDone = confirm.confirmed() && r.plan().isEmpty() && !byHandNeeded && lastRefused == null && beeperProblem == null;
            if (cleanDone) usb.set(State.OK, usbText + "<br><b>Done - you can unplug the drone from the PC.</b>");
        }

        /** CONFIRM and its line under the settings: before the press; what is left after the last try. */
        private void showChanges(Read r, boolean pendingWrite) {
            confirmButton.setVisible(pendingWrite && !confirm.confirmed());
            if (confirm.confirmed()) {
                settings.footer("");
            } else if (pendingWrite && lastRefused != null) {
                settings.footer("<b>Nothing was written</b> - " + lastRefused + "<br>Fix it (or change the choice), then <b>Confirm</b> again.");
            } else if (pendingWrite) {
                settings.footer("Set everything on this page, then <b>Confirm</b> writes it to the flight controller (backed up first).");
            } else if (r.plan().isEmpty()) {
                settings.footer("");
            } else {
                String list = r.plan().stream().map(c -> "&bull; " + c.description()).collect(Collectors.joining("<br>"));
                settings.footer("Still not set:<br>" + list + "<br>See <b>By hand</b>.");
            }
        }

        /** The battery line. @return true if the count is not as the pilot's (confirmed) choice */
        private boolean showCells(Read r, boolean pendingWrite) {
            if (r.cells() < 0) {
                battery.set(State.WARN, "Not in this Betaflight - it counts the cells itself; the OSD corrects a wrong count.");
                return false;
            }
            if (!r.cellsConfirmed()) {
                if (r.cells() > 0 && main.config().getString(CELLS_KEY).isEmpty() && r.cells() != cells) {
                    syncingSpinner = true; // show the count Betaflight already forces, as the one to confirm
                    cellSpinner.setValue(r.cells());
                    syncingSpinner = false;
                }
                battery.set(State.INFO, (r.cells() > 0 ? "Betaflight forces <b>" + r.cells() + "S</b> now. " : "Betaflight counts the cells itself. ")
                        + "Correct? Change it if not.");
                return false;
            }
            if (r.cells() == r.cellTarget()) {
                battery.set(okState(), setText());
                return false;
            }
            if (pendingWrite) {
                battery.set(State.TODO, "Betaflight " + (r.cells() == 0 ? "counts itself" : "forces " + r.cells() + "S") + " now. " + WITH_WRITE);
                return false;
            }
            battery.set(State.BAD, "Could not set it (" + r.cells() + ") - see <b>By hand</b>.");
            return true;
        }

        /** "Use measured: 4S" while a battery is in and Betaflight's own count differs from the spinner. */
        private void showMeasured(BetaflightUsb.BatteryState b) {
            measured = b != null && b.present() ? b.cells() : 0;
            measuredCells.setText("Use measured: " + measured + "S");
            measuredCells.setVisible(measured > 0 && measured != cells);
        }

        /** The GPS row after a round that changed nothing. @return true if the GPS or the compass is not as chosen */
        private boolean showGps(Read r, boolean pendingWrite) {
            Gps g = r.gps();
            if (!g.wanted()) {
                gps.set(okState(), "");
                return false;
            }
            List<String> lines = new java.util.ArrayList<>();
            State worst = okState();
            boolean wrong = false;
            if (g.gpsPending()) {
                if (pendingWrite) {
                    lines.add(WITH_WRITE + (g.portNote().isEmpty() ? "" : " " + Character.toUpperCase(g.portNote().charAt(0)) + g.portNote().substring(1) + "."));
                    worst = State.TODO;
                } else {
                    lines.add("Could not set it - the Ports tab (see <b>By hand</b>).");
                    worst = State.BAD;
                    wrong = true;
                }
            } else if (Boolean.TRUE.equals(r.gpsFound())) {
                lines.add("GPS found.");
            } else {
                lines.add("No GPS answers on " + g.portNow() + ": check its wiring (RX to TX, TX to RX) and power - some boards power it "
                        + "from the battery only: plug it in (props off), then <b>Read again</b>. No fix is needed.");
                worst = State.WARN;
            }
            if (g.compass()) {
                if (g.compassPending()) {
                    if (pendingWrite) {
                        lines.add("Compass: " + WITH_WRITE);
                        if (rank(State.TODO) > rank(worst)) worst = State.TODO;
                    } else {
                        lines.add("Compass: could not set it - see <b>By hand</b>.");
                        worst = State.BAD;
                        wrong = true;
                    }
                } else if (!g.magFound()) {
                    lines.add("No compass found: none on the GPS module, or not wired to the board's I2C.");
                    if (rank(State.WARN) > rank(worst)) worst = State.WARN;
                } else if (g.calibrated()) {
                    lines.add("Compass found and calibrated.");
                } else {
                    lines.add("Compass found. <b>Calibrate compass</b> once, before flying with GPS rescue.");
                }
            }
            gps.set(worst, String.join("<br>", lines));
            return wrong;
        }

        /** The failsafe row after a round that changed nothing. @return true if it is not as chosen */
        private boolean showFailsafe(Read r, boolean pendingWrite) {
            ModeSwitch.Failsafe f = r.failsafeTarget();
            String now = r.failsafe() == null || r.failsafe().isEmpty() ? null : r.failsafe();
            if (f == ModeSwitch.Failsafe.KEEP) {
                failsafeRow.set(State.INFO, (now == null ? "" : "Now <b>" + now + "</b>.") + (r.failsafeNote() == null ? "" : "<br>" + r.failsafeNote()));
                return false;
            }
            if (r.failsafeProblem() != null) {
                failsafeRow.set(State.WARN, "Not changed (" + (now == null ? "?" : now) + "): " + r.failsafeProblem());
                return true;
            }
            if (now == null || !now.equals(f.value)) {
                failsafeRow.set(pendingWrite ? State.TODO : State.BAD, pendingWrite ? "Now " + (now == null ? "?" : now) + ". " + WITH_WRITE
                        : "Could not set it - Betaflight Configurator's Failsafe tab.");
                return !pendingWrite;
            }
            String text = setText() + (Boolean.TRUE.equals(r.gpsFound()) && f == ModeSwitch.Failsafe.GPS_RESCUE ? " GPS found." : "");
            failsafeRow.set(r.failsafeNote() == null ? okState() : State.WARN, r.failsafeNote() == null ? text : text + "<br>" + r.failsafeNote());
            return false;
        }

        /** The rescue switch row after a round that changed nothing. @return true if it is not as chosen */
        private boolean showRescue(Read r, boolean pendingWrite) {
            // remembered for the radio calibration: it names AUX4 (or whichever) GPS rescue only when it is fitted
            main.save(Map.of(ModeSwitch.RESCUE_FITTED_KEY, Boolean.toString(r.rescueTarget() > 0 && r.rescueFitted())));
            int aux = r.rescueTarget();
            if (aux == 0) {
                showRescueChoice();
                return false;
            }
            if (!r.rescueFitted()) {
                String now = r.failsafe() == null ? "" : r.failsafe().replaceAll("(?s).*=\\s*", "").trim();
                rescueRow.set(State.INFO, "Not fitted: the failsafe is " + (now.isEmpty() ? "not GPS rescue" : now)
                        + (r.failsafeTarget() == ModeSwitch.Failsafe.GPS_RESCUE ? " (see the failsafe row)." : " - choose <b>GPS rescue</b> under FAILSAFE."));
                return false;
            }
            ModeSwitch.Plan p = r.modes();
            if (p == null) return false;
            if (p.rescueProblem() != null) {
                rescueRow.set(State.WARN, p.rescueProblem());
                return true;
            }
            if (!p.commands().isEmpty()) {
                rescueRow.set(pendingWrite ? State.TODO : State.BAD, pendingWrite ? WITH_WRITE
                        : "Could not set it - GPS RESCUE on AUX" + aux + " 1700-2100 in the Modes tab.");
                return !pendingWrite;
            }
            StringBuilder text = new StringBuilder(setText());
            List<String> replaced = lastReplaced.stream().filter(x -> x.endsWith("AUX" + aux)).toList();
            if (!replaced.isEmpty()) text.append(" Taken off that switch: ").append(String.join(", ", replaced)).append('.');
            rescueRow.set(okState(), text.toString());
            return false;
        }

        /** The flight mode row after a round that changed nothing. @return true if they are not as chosen */
        private boolean showModes(Read r, boolean pendingWrite) {
            ModeSwitch m = r.modeTarget();
            ModeSwitch.Plan p = r.modes();
            if (m == null || p == null) {
                showModeChoice();
                return false;
            }
            if (p.problem() != null) {
                modeRow.set(State.WARN, p.problem());
                return true;
            }
            if (!p.isSet()) {
                modeRow.set(pendingWrite ? State.TODO : State.BAD, pendingWrite ? WITH_WRITE + takenOff(r.replaced())
                        : "Could not set it - the Modes tab (see <b>By hand</b>).");
                return !pendingWrite;
            }
            boolean partly = !p.blocked().isEmpty() || !p.missing().isEmpty();
            StringBuilder text = new StringBuilder(partly ? "Partly set." : setText());
            text.append(takenOff(lastReplaced));
            for (var b : p.blocked().entrySet()) { // the reason names the mode itself when it is "too old": not twice
                text.append("<br>");
                if (!b.getValue().startsWith(b.getKey().title)) text.append("<b>").append(b.getKey().title).append(":</b> ");
                text.append(b.getValue());
            }
            if (r.rescueNote() != null) text.append("<br>").append(r.rescueNote());
            for (ModeSwitch.Mode missing : p.missing()) {
                text.append("<br><b>").append(missing.title).append("</b> isn't offered by this Betaflight (")
                        .append(missing.detail).append("): that position flies acro.");
            }
            if (!p.elsewhere().isEmpty()) {
                text.append("<br>Also in Betaflight's Modes, left as they are: ").append(String.join(", ", p.elsewhere())).append('.');
            }
            // a mode on two switches that nothing here takes off (the switch left as it is, or the write refused)
            boolean doubled = !p.doubled().isEmpty() && p.commands().isEmpty();
            if (doubled) {
                text.append("<br>On more than one switch in Betaflight: ").append(String.join("; ", p.doubled()))
                        .append(" - set each on one (choose a switch above, or the Modes tab).");
            }
            boolean warn = !p.missing().isEmpty() || !p.blocked().isEmpty() || doubled;
            modeRow.set(warn ? State.WARN : okState(), text.toString());
            return false;
        }

        @Override
        String status() {
            if (paused) return busy ? "Finishing the current read, then the port is free..." : "Disconnected from the USB port - press Reconnect to carry on.";
            if (busy && found) return "Reading Betaflight...";
            if (done) {
                if (cleanDone) return "Betaflight is set up - you can unplug the drone from the PC.";
                if (!confirm.armed() && !confirm.confirmed()) return "Set everything above, then press Confirm to write it to the flight controller (or Next to leave Betaflight as it is).";
                wizard.setStatusColor(Theme.WARN);
                return "Some settings could not be set - see By hand";
            }
            if (!found) return "Waiting for the flight controller (or press Next and use the settings by hand).";
            if (failed) return "Waiting for the flight controller - close or Disconnect Betaflight Configurator, then Retry.";
            return "Setting up Betaflight...";
        }

        @Override
        boolean canContinue() {
            return !(busy && found); // a round reads (and may write) for up to half a minute: not left halfway
        }
    }
}
