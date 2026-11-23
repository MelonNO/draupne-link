package de.droiddrone.pc.gui;

import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.setup.ardu.ArduBenchCheck;
import de.droiddrone.pc.setup.ardu.ArduPlan;
import de.droiddrone.pc.setup.ardu.ArduSetupWriter;
import de.droiddrone.pc.setup.ardu.ArduTools;
import de.droiddrone.pc.setup.ardu.ArduUsb;
import de.droiddrone.pc.setup.ardu.MavSession;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fleet PRD area H (AS-1 - AS-10): Drone setup's ArduPilot branch - FIND the board on USB (it must speak first), the
 * SETTINGS the pilot chooses as a plan read before CONFIRM (backup, write, read back: {@link ArduSetupWriter}), the
 * CALIBRATIONS with the 3D drone (six positions, level, compass), the MOTORS (props off), and a BENCH CHECK. Opened
 * from SETUP; a Betaflight board goes the way it went. Everything runs on the wizard's workers, never Swing's thread.
 * <p>
 * Not built (says so on the FIND page): flashing ArduPilot onto a board that runs Betaflight (AS-1, H1, H4) - that is
 * the STM32's DFU over USB, which needs a USB driver this app doesn't install. A board already on ArduPilot is
 * found and set up; its firmware updates are Mission Planner's for now.
 */
final class ArduSetupPages {
    private ArduSetupPages() {
    }

    /** What the pages share: the session once found. */
    static final class State {
        volatile ArduUsb.Found found;
        volatile Map<String, Float> params;
        /** Fleet AS-11: what was read from the board while it still ran Betaflight (null: nothing read). */
        volatile de.droiddrone.pc.setup.ardu.FromBetaflight.Carried carried;
        /**
         * GU-4: the pilot's radio switches as Drone setup knows them (SETUP's ARM and FLIGHT MODES switches): ARM and the
         * mode switch go on the same AUX channels as on the Betaflight drones, so one radio flies both. AUX1 / AUX2 when
         * not set (or set to "leave as it is").
         */
        volatile int radioArmAux = 1, radioModeAux = 2;

        /** The radio's switches from this PC's settings. */
        void radioFrom(de.droiddrone.pc.PcConfig config) {
            int arm = de.droiddrone.pc.setup.ModeSwitch.parseArmAux(config.getString(de.droiddrone.pc.setup.ModeSwitch.ARM_KEY));
            de.droiddrone.pc.setup.ModeSwitch modes = de.droiddrone.pc.setup.ModeSwitch.parse(config.getString(de.droiddrone.pc.setup.ModeSwitch.KEY));
            radioArmAux = arm > 0 ? arm : 1;
            radioModeAux = modes != null && modes.aux() != radioArmAux ? modes.aux() : radioArmAux == 2 ? 3 : 2;
        }

        MavSession fc() {
            ArduUsb.Found f = found;
            return f == null ? null : f.session();
        }

        void close() {
            MavSession s = fc();
            if (s != null) s.close();
            found = null;
        }
    }

    /** SETUP &rarr; ArduPilot drone setup. */
    static void open(MainWindow main) {
        State state = new State();
        state.radioFrom(main.config());
        List<WizardDialog.Page> pages = List.of(new FindPage(state), new SettingsPage(state), new CalibratePage(state),
                new MotorsPage(state), new BenchPage(state));
        WizardDialog w = new WizardDialog(main, "ArduPilot drone setup", pages, () -> main.logLine("ArduPilot drone setup finished."));
        w.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                state.close();
            }
        });
        w.setVisible(true);
        state.close();
    }

    static JTextArea logArea() {
        JTextArea a = new JTextArea(10, 60);
        a.setEditable(false);
        a.setFont(Theme.monoFont(Font.PLAIN, 12.5f));
        a.setBackground(Theme.PANEL_2);
        a.setForeground(Theme.TEXT);
        return a;
    }

    static void append(JTextArea a, String line) {
        SwingUtilities.invokeLater(() -> {
            a.append(line + "\n");
            a.setCaretPosition(a.getDocument().getLength());
        });
    }

    // ------------------------------------------------------------------ FIND

    static final class FindPage extends WizardDialog.Page {
        private final State state;
        private final JLabel result = Theme.label(" ", Theme.font(Font.BOLD, 15f), Theme.MUTED);
        private boolean searching;
        /** Fleet AS-1: ArduPilot firmware onto this board (its own bootloader), on the pilot's click only. */
        private final JButton fwFile = Theme.secondaryButton("Firmware file...");
        private final JButton fwNewest = Theme.secondaryButton("Newest Copter");
        private volatile boolean flashing;
        /** Fleet AS-11: battery, switches, board turn and motor order from a Betaflight board, before it is flashed. */
        private final JButton fromBf = Theme.secondaryButton("From Betaflight");

        FindPage(State state) {
            this.state = state;
        }

        @Override
        String title() {
            return "ArduPilot board";
        }

        @Override
        String hint() {
            return "Plug the drone's flight controller into this PC by USB, battery off. Mission Planner closed. "
                    + "A Betaflight board is left alone. Firmware: new ArduPilot onto a board that runs ArduPilot. "
                    + "Betaflight to ArduPilot (DFU) is not built: flash it once with ArduPilot's own tools.";
        }

        @Override
        JComponent build() {
            JPanel p = new JPanel(new BorderLayout(0, 12));
            Theme.transparent(p);
            JButton again = Theme.secondaryButton("Search again");
            again.addActionListener(e -> search());
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            Theme.transparent(row);
            row.add(again);
            row.add(javax.swing.Box.createHorizontalStrut(8));
            row.add(fwFile);
            row.add(javax.swing.Box.createHorizontalStrut(8));
            row.add(fwNewest);
            row.add(javax.swing.Box.createHorizontalStrut(8));
            row.add(fromBf);
            fromBf.addActionListener(e -> readBetaflight());
            fwFile.addActionListener(e -> firmwareFromFile());
            fwNewest.addActionListener(e -> firmwareNewest());
            fwFile.setEnabled(state.found != null);
            fwNewest.setEnabled(state.found != null);
            p.add(result, BorderLayout.NORTH);
            p.add(row, BorderLayout.CENTER);
            return p;
        }

        @Override
        void onShow() {
            if (state.found == null) search();
        }

        private void search() {
            if (searching) return;
            searching = true;
            state.close();
            result.setText("Listening on USB ...");
            result.setForeground(Theme.MUTED);
            background(() -> {
                try {
                    ArduUsb.Found f = ArduUsb.find();
                    if (f == null) return "No ArduPilot board heard on USB.";
                    state.found = f;
                    return null;
                } catch (Exception e) {
                    return e.getMessage();
                }
            }, failure -> {
                searching = false;
                ArduUsb.Found f = state.found;
                if (f != null) {
                    result.setText("ArduPilot on " + f.port().systemName() + ", system id " + f.session().fcSysId());
                    result.setForeground(Theme.OK);
                    fwFile.setEnabled(true);
                    fwNewest.setEnabled(true);
                    log("ArduPilot setup: board on " + f.port().systemName());
                } else {
                    result.setText(failure == null ? "Not found." : failure);
                    result.setForeground(Theme.WARN);
                }
            });
        }

        private void readBetaflight() {
            if (flashing) return;
            fromBf.setEnabled(false);
            say("Reading the Betaflight board ...");
            background(() -> {
                try {
                    java.util.List<de.droiddrone.pc.setup.BetaflightUsb.Port> ports = de.droiddrone.pc.setup.BetaflightUsb.findFlightControllers();
                    if (ports.isEmpty()) return "No board on USB.";
                    de.droiddrone.pc.setup.BetaflightUsb bf = de.droiddrone.pc.setup.BetaflightUsb.open(ports.get(0));
                    try {
                        state.carried = de.droiddrone.pc.setup.ardu.FromBetaflight.read(bf);
                    } finally {
                        bf.close();
                    }
                    return null;
                } catch (Exception e) {
                    return "Betaflight: " + e.getMessage();
                }
            }, failure -> {
                fromBf.setEnabled(true);
                de.droiddrone.pc.setup.ardu.FromBetaflight.Carried c = state.carried;
                if (failure != null || c == null) {
                    result.setText(failure == null ? "Nothing read." : failure);
                    result.setForeground(Theme.WARN);
                    return;
                }
                result.setText("<html>From Betaflight: " + c.summary() + (c.notes().isEmpty() ? "" : "<br>" + String.join("<br>", c.notes())) + "</html>");
                result.setForeground(Theme.OK);
                log("ArduPilot setup: from Betaflight " + c.summary());
            });
        }

        private void firmwareFromFile() {
            javax.swing.JFileChooser c = new javax.swing.JFileChooser();
            c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ArduPilot firmware (.apj)", "apj"));
            if (c.showOpenDialog(result) != javax.swing.JFileChooser.APPROVE_OPTION) return;
            java.nio.file.Path f = c.getSelectedFile().toPath();
            flash(() -> java.nio.file.Files.readAllBytes(f));
        }

        private void firmwareNewest() {
            flash(() -> {
                MavSession fc = state.fc();
                if (fc == null) throw new java.io.IOException("no board");
                int id = de.droiddrone.pc.setup.ardu.ArduFirmwareFlow.boardId(fc);
                if (id < 0) throw new java.io.IOException("the board doesn't say its id");
                java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
                say("Looking up Copter for board " + id + " ...");
                String url = de.droiddrone.pc.setup.ardu.ArduFirmwareFlow.latestCopter(id, http);
                if (url == null) throw new java.io.IOException("no Copter firmware for board " + id);
                say("Downloading ...");
                return de.droiddrone.pc.setup.ardu.ArduFirmwareFlow.download(url, http);
            });
        }

        interface Source {
            byte[] get() throws Exception;
        }

        /** Read, confirmed, board to its bootloader, written. Disarmed only (the session says). */
        private void flash(Source source) {
            if (flashing || state.found == null) return;
            MavSession fc0 = state.fc();
            if (fc0 == null || fc0.armed()) {
                say("Armed, or not known: firmware only on the bench, disarmed.");
                return;
            }
            if (Dialogs.confirm(result, "Write new ArduPilot firmware onto this board?\nBattery off. Don't unplug until it says Done.",
                    "Firmware", javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION) return;
            flashing = true;
            fwFile.setEnabled(false);
            fwNewest.setEnabled(false);
            background(() -> {
                try {
                    de.droiddrone.pc.setup.ardu.ArduFirmware.Apj fw = de.droiddrone.pc.setup.ardu.ArduFirmware.parseApj(source.get());
                    MavSession fc = state.fc();
                    if (fc == null) return "Firmware: the board went away.";
                    int id = de.droiddrone.pc.setup.ardu.ArduFirmwareFlow.boardId(fc);
                    if (id >= 0 && id != fw.boardId()) return "This firmware is for board " + fw.boardId() + ", this board is " + id + ".";
                    say("Restarting into the bootloader ...");
                    String own = state.found.port().systemName();
                    java.util.Set<String> before = de.droiddrone.pc.setup.ardu.ArduFirmwareFlow.portsNow();
                    de.droiddrone.pc.setup.ardu.ArduFirmwareFlow.rebootToBootloader(fc);
                    state.close();
                    state.found = null;
                    try (de.droiddrone.pc.setup.ardu.ArduFirmwareFlow.SerialWire w = de.droiddrone.pc.setup.ardu.ArduFirmwareFlow.findBootloader(15_000, own, before)) {
                        de.droiddrone.pc.setup.ardu.ArduFirmware.upload(w, fw, this::say);
                    }
                    log("ArduPilot setup: firmware written, " + fw.summary());
                    return null;
                } catch (Exception e) {
                    return "Firmware: " + e.getMessage();
                }
            }, failure -> {
                flashing = false;
                fwFile.setEnabled(state.found != null);
                fwNewest.setEnabled(state.found != null);
                if (failure != null) {
                    result.setText(failure);
                    result.setForeground(Theme.WARN);
                    log("ArduPilot setup: " + failure);
                } else {
                    result.setText("Done. Search again once the board has restarted.");
                    result.setForeground(Theme.OK);
                }
            });
        }

        private void say(String text) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                result.setText(text);
                result.setForeground(Theme.MUTED);
            });
        }

        @Override
        boolean canContinue() {
            return state.found != null && !flashing;
        }

        @Override
        String status() {
            return state.found == null ? "Needs an ArduPilot board on USB." : null;
        }
    }

    // ------------------------------------------------------------------ SETTINGS

    static final class SettingsPage extends WizardDialog.Page {
        private final State state;
        private final JSpinner sysId = new JSpinner(new SpinnerNumberModel(2, 1, 250, 1));
        private final JSpinner cells = new JSpinner(new SpinnerNumberModel(4, 1, 14, 1));
        private final JSpinner capacity = new JSpinner(new SpinnerNumberModel(3000, 300, 50_000, 100));
        private final JSpinner rtlAlt = new JSpinner(new SpinnerNumberModel(30, 10, 300, 5));
        private final JTextArea plan = logArea();
        private final JButton confirm = Theme.primaryButton("CONFIRM");
        private List<ArduPlan.Change> changes = List.of();
        private boolean written;
        private boolean seeded;

        SettingsPage(State state) {
            this.state = state;
        }

        @Override
        String title() {
            return "Settings";
        }

        @Override
        String hint() {
            return "Its own id in the fleet, the battery, how high it comes home. The plan shows every change; "
                    + "CONFIRM saves a backup first, then writes and reads back each one.";
        }

        @Override
        JComponent build() {
            JPanel p = new JPanel(new BorderLayout(0, 10));
            Theme.transparent(p);
            JPanel form = new JPanel(new GridLayout(0, 2, 10, 6));
            Theme.transparent(form);
            form.add(Theme.label("FLEET ID (1-250, one per drone)", Theme.headingFont(13f), Theme.MUTED));
            form.add(sysId);
            form.add(Theme.label("CELLS", Theme.headingFont(13f), Theme.MUTED));
            form.add(cells);
            form.add(Theme.label("PACK (mAh)", Theme.headingFont(13f), Theme.MUTED));
            form.add(capacity);
            form.add(Theme.label("HOME HEIGHT (m)", Theme.headingFont(13f), Theme.MUTED));
            form.add(rtlAlt);
            for (JSpinner s : new JSpinner[]{sysId, cells, capacity, rtlAlt}) s.addChangeListener(e -> replan());
            p.add(form, BorderLayout.NORTH);
            p.add(new JScrollPane(plan), BorderLayout.CENTER);
            confirm.addActionListener(e -> write());
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            Theme.transparent(row);
            row.add(confirm);
            p.add(row, BorderLayout.SOUTH);
            return p;
        }

        ArduPlan.Choices choices() {
            ArduPlan.Choices c = new ArduPlan.Choices((Integer) sysId.getValue(), (Integer) cells.getValue(), (Integer) capacity.getValue(),
                    state.radioArmAux, state.radioModeAux, ArduPlan.DEFAULT_MODES.clone(), (Integer) rtlAlt.getValue(), 10, 2.5, 1.5, true);
            de.droiddrone.pc.setup.ardu.FromBetaflight.Carried bf = state.carried;
            // the switches from Betaflight; cells and pack went into the boxes (and can be changed there)
            return bf == null ? c : new ArduPlan.Choices(c.sysId(), c.cells(), c.capacityMah(), bf.armAux() != null ? bf.armAux() : c.armAux(),
                    bf.modeAux() != null ? bf.modeAux() : c.modeAux(), c.flightModes(), c.rtlAltM(), c.cruiseMs(), c.climbMs(), c.descentMs(), c.avoidance());
        }

        @Override
        void onShow() {
            de.droiddrone.pc.setup.ardu.FromBetaflight.Carried bf = state.carried;
            if (bf != null && !seeded) { // fleet AS-11: once, then the pilot's
                seeded = true;
                if (bf.cells() != null) cells.setValue(Math.max(1, Math.min(14, bf.cells())));
                if (bf.capacityMah() != null) capacity.setValue(Math.max(300, Math.min(50_000, bf.capacityMah() / 100 * 100)));
            }
            MavSession fc = state.fc();
            if (fc == null) return;
            plan.setText("Reading its parameters ...\n");
            background(() -> {
                try {
                    state.params = fc.readAllParams(30_000);
                    return null;
                } catch (Exception e) {
                    return e.getMessage();
                }
            }, failure -> {
                if (failure != null) plan.setText("Couldn't read: " + failure + "\n");
                else replan();
            });
        }

        private void replan() {
            Map<String, Float> now = state.params;
            if (now == null) return;
            Float id = now.get("SYSID_THISMAV");
            de.droiddrone.pc.setup.ardu.FromBetaflight.Carried bf = state.carried;
            changes = ArduPlan.plan(choices(), bf == null ? Map.of() : bf.extra(), now);
            plan.setText("");
            List<String> lines = ArduPlan.lines(changes);
            if (lines.isEmpty()) append(plan, "Nothing to change.");
            for (String l : lines) append(plan, l);
            if (id != null && !written && id.intValue() != 1 && (Integer) sysId.getValue() == 2) sysId.setValue(id.intValue());
        }

        private void write() {
            MavSession fc = state.fc();
            if (fc == null || changes.isEmpty()) return;
            confirm.setEnabled(false);
            append(plan, "--- writing");
            background(() -> {
                try {
                    return new ArduSetupWriter(fc, l -> append(plan, l)).write(changes, AppPaths.arduPilotBackupsDir(), java.time.LocalDateTime.now());
                } catch (Exception e) {
                    return new ArduSetupWriter.Result(null, List.of(), e.getMessage());
                }
            }, r -> {
                confirm.setEnabled(true);
                if (r.ok()) {
                    written = true;
                    append(plan, "Done: " + r.written().size() + " written, each read back. Backup: " + r.backup().getFileName());
                    log("ArduPilot setup: " + r.written().size() + " parameters written, backup " + r.backup().getFileName());
                    try {
                        state.params = fc.readAllParams(30_000);
                    } catch (Exception ignored) {
                        // the next plan reads again
                    }
                } else {
                    append(plan, "STOPPED: " + r.failure());
                    log("ArduPilot setup stopped: " + r.failure());
                }
            });
        }

        @Override
        boolean canContinue() {
            return written || state.params != null && changes.stream().allMatch(ArduPlan.Change::missing);
        }

        @Override
        String status() {
            return canContinue() ? null : "CONFIRM writes the plan.";
        }
    }

    // ------------------------------------------------------------------ CALIBRATE

    static final class CalibratePage extends WizardDialog.Page {
        private final State state;
        private final DroneModelView drone = new DroneModelView();
        private final JLabel ask = Theme.label(" ", Theme.headingFont(22f), Theme.TEXT);
        private final JButton accel = Theme.primaryButton("ACCELEROMETER");
        private final JButton placed = Theme.primaryButton("DONE - IT IS SO");
        private final JButton level = Theme.secondaryButton("LEVEL");
        private final JButton compass = Theme.secondaryButton("COMPASS");
        private final JTextArea out = logArea();
        private volatile ArduTools.AccelCal cal;
        private boolean accelDone;

        CalibratePage(State state) {
            this.state = state;
        }

        @Override
        String title() {
            return "Calibrate";
        }

        @Override
        String hint() {
            return "ACCELEROMETER: put the drone as the picture shows, then DONE - six times. LEVEL: on a flat table. "
                    + "COMPASS: turn it round every axis until it says done.";
        }

        @Override
        JComponent build() {
            JPanel p = new JPanel(new BorderLayout(10, 10));
            Theme.transparent(p);
            drone.setPreferredSize(new java.awt.Dimension(320, 220));
            JPanel left = new JPanel(new BorderLayout(0, 6));
            Theme.transparent(left);
            left.add(ask, BorderLayout.NORTH);
            left.add(drone, BorderLayout.CENTER);
            JPanel buttons = new JPanel();
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
            Theme.transparent(buttons);
            for (JButton b : new JButton[]{accel, placed, level, compass}) {
                buttons.add(b);
                buttons.add(javax.swing.Box.createVerticalStrut(8));
            }
            placed.setEnabled(false);
            accel.addActionListener(e -> startAccel());
            placed.addActionListener(e -> placed());
            level.addActionListener(e -> run("LEVEL", t -> "level: " + (t.levelTrim() == 0 ? "done" : "refused")));
            compass.addActionListener(e -> run("COMPASS", t -> {
                int r = t.startCompassCal(rep -> append(out, "compass " + rep.compass_id + ": " + (rep.cal_status == 4 ? "GOOD" : "status " + rep.cal_status)
                        + ", fitness " + Math.round(rep.fitness) + " mGauss"));
                return r == 0 ? "compass: turn it round every axis" : "compass refused (" + r + ")";
            }));
            p.add(left, BorderLayout.CENTER);
            p.add(buttons, BorderLayout.EAST);
            p.add(new JScrollPane(out), BorderLayout.SOUTH);
            return p;
        }

        interface Tool {
            String run(ArduTools t) throws Exception;
        }

        private void run(String name, Tool tool) {
            MavSession fc = state.fc();
            if (fc == null) return;
            append(out, name + " ...");
            background(() -> {
                try {
                    return tool.run(new ArduTools(fc));
                } catch (Exception e) {
                    return name + ": " + e.getMessage();
                }
            }, line -> append(out, line));
        }

        private void startAccel() {
            MavSession fc = state.fc();
            if (fc == null) return;
            accel.setEnabled(false);
            background(() -> {
                try {
                    cal = new ArduTools(fc).startAccelCal(pos -> SwingUtilities.invokeLater(() -> show(pos)),
                            ok -> SwingUtilities.invokeLater(() -> {
                                accelDone = ok;
                                ask.setText(ok ? "Accelerometer: done" : "Accelerometer: failed, again");
                                placed.setEnabled(false);
                                accel.setEnabled(true);
                                append(out, ok ? "accelerometer: done" : "accelerometer: failed");
                            }));
                    return null;
                } catch (Exception e) {
                    return e.getMessage();
                }
            }, failure -> {
                if (failure != null) {
                    append(out, "accelerometer: " + failure);
                    accel.setEnabled(true);
                }
            });
        }

        private void show(ArduTools.Position p) {
            ask.setText(p.words);
            switch (p) {
                case LEVEL -> drone.setAttitude(0, 0, 30);
                case LEFT -> drone.setAttitude(-90, 0, 30);
                case RIGHT -> drone.setAttitude(90, 0, 30);
                case NOSE_DOWN -> drone.setAttitude(0, -90, 30);
                case NOSE_UP -> drone.setAttitude(0, 90, 30);
                case BACK -> drone.setAttitude(180, 0, 30);
            }
            placed.setEnabled(true);
        }

        private void placed() {
            ArduTools.AccelCal c = cal;
            if (c == null || c.asked() == null) return;
            ArduTools.Position p = c.asked();
            placed.setEnabled(false);
            background(() -> {
                try {
                    c.placed(p);
                    return null;
                } catch (Exception e) {
                    return e.getMessage();
                }
            }, failure -> {
                if (failure != null) append(out, failure);
            });
        }

        @Override
        String status() {
            return accelDone ? null : "The accelerometer is calibrated once; then NEXT.";
        }
    }

    // ------------------------------------------------------------------ MOTORS

    static final class MotorsPage extends WizardDialog.Page {
        private final State state;
        private final JTextArea out = logArea();

        MotorsPage(State state) {
            this.state = state;
        }

        @Override
        String title() {
            return "Motors - props OFF";
        }

        @Override
        String hint() {
            return "PROPS OFF. Each button spins one motor slowly for a second, in ArduPilot's order: A front right, "
                    + "then clockwise. Check it is the right one, turning the right way.";
        }

        @Override
        JComponent build() {
            JPanel p = new JPanel(new BorderLayout(0, 10));
            Theme.transparent(p);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            Theme.transparent(row);
            for (int m = 1; m <= 4; m++) {
                int motor = m;
                JButton b = Theme.secondaryButton(String.valueOf((char) ('A' + m - 1)));
                b.addActionListener(e -> spin(motor));
                row.add(b);
            }
            p.add(row, BorderLayout.NORTH);
            p.add(new JScrollPane(out), BorderLayout.CENTER);
            return p;
        }

        private void spin(int motor) {
            MavSession fc = state.fc();
            if (fc == null) return;
            background(() -> {
                try {
                    int r = new ArduTools(fc).motorTest(motor, 5, 1);
                    return "motor " + (char) ('A' + motor - 1) + (r == 0 ? ": spinning 1 s" : ": refused (" + r + ")");
                } catch (Exception e) {
                    return e.getMessage();
                }
            }, line -> append(out, line));
        }
    }

    // ------------------------------------------------------------------ BENCH CHECK

    /**
     * AS-10, as far as USB goes: the PC sends the sticks as RC overrides the way the phone will (ARM switch on, throttle
     * down) and watches the heartbeat say ARMED, then lets go (disarmed). The link-cut brake is checked over the phone
     * once connected (the LINK card's own check). Props off.
     */
    static final class BenchPage extends WizardDialog.Page {
        private final State state;
        private final JTextArea out = logArea();

        BenchPage(State state) {
            this.state = state;
        }

        @Override
        String title() {
            return "Bench check - props OFF";
        }

        @Override
        String hint() {
            return "PROPS OFF, battery ON. ARM TEST sends ARM the way the phone will and waits for ArduPilot to say ARMED, "
                    + "then disarms. A refusal shows its reason.";
        }

        @Override
        JComponent build() {
            JPanel p = new JPanel(new BorderLayout(0, 10));
            Theme.transparent(p);
            JButton arm = Theme.primaryButton("ARM TEST");
            arm.addActionListener(e -> armTest());
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            Theme.transparent(row);
            row.add(arm);
            p.add(row, BorderLayout.NORTH);
            p.add(new JScrollPane(out), BorderLayout.CENTER);
            p.setBorder(BorderFactory.createEmptyBorder());
            return p;
        }

        private void armTest() {
            MavSession fc = state.fc();
            if (fc == null) return;
            List<String> texts = new ArrayList<>();
            fc.onText(t -> {
                synchronized (texts) {
                    texts.add(t);
                }
            });
            background(() -> {
                try {
                    return ArduBenchCheck.armTest(fc, 1, l -> append(out, l));
                } catch (Exception e) {
                    return "arm test: " + e.getMessage();
                }
            }, line -> {
                append(out, line);
                synchronized (texts) {
                    for (String t : texts) if (t.startsWith("PreArm") || t.startsWith("Arm")) append(out, "  " + t);
                }
            });
        }
    }
}
