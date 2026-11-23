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
 * A board that runs Betaflight (AS-1, H1, H4): From Betaflight reads what carries over, To ArduPilot (DFU) writes
 * ArduPilot through the STM32's USB DFU ({@link de.droiddrone.pc.setup.ardu.ArduDfuFlow}) - the board the pilot picks,
 * disarmed, after a confirm naming board and firmware. The DFU device needs Windows' WinUSB driver (Betaflight's
 * driver tool or Zadig install it; this app doesn't). PORTS (AS-6) and FRAME AND TUNING (AS-7) follow SETTINGS.
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
        /** Fleet AS-7: the mode switch position that is AUTOTUNE (0: none) - the SETTINGS plan keeps it. */
        volatile int autotunePos;
        /** The cells the SETTINGS page chose (the tuning's battery compensation). */
        volatile int cells = 4;
        /**
         * GU-4: the pilot's radio switches as Drone setup knows them (SETUP's ARM and FLIGHT MODES switches): ARM and the
         * mode switch go on the same AUX channels as on the Betaflight drones, so one radio flies both. AUX1 / AUX2 when
         * not set (or set to "leave as it is").
         */
        volatile int radioArmAux = 1, radioModeAux = 2;

        /**
         * Which board the pages work on: one more each time a board is found (FIND, a restart). A page's "written" and
         * its plan belong to one board - another board found after Back starts them anew.
         */
        volatile int board;

        /** The radio's switches from this PC's settings. */
        void radioFrom(de.droiddrone.pc.PcConfig config) {
            int arm = de.droiddrone.pc.setup.ModeSwitch.parseArmAux(config.getString(de.droiddrone.pc.setup.ModeSwitch.ARM_KEY));
            de.droiddrone.pc.setup.ModeSwitch modes = de.droiddrone.pc.setup.ModeSwitch.parse(config.getString(de.droiddrone.pc.setup.ModeSwitch.KEY));
            radioArmAux = arm > 0 ? arm : 1;
            radioModeAux = ArduPlan.modeAuxBeside(radioArmAux, modes != null ? modes.aux() : 2);
        }

        MavSession fc() {
            ArduUsb.Found f = found;
            return f == null ? null : f.session();
        }

        /** A board found: the pages start anew on it. */
        void use(ArduUsb.Found f) {
            params = null;
            board++;
            found = f;
        }

        /** The board let go: its parameters with it (another board may come next). */
        void close() {
            MavSession s = fc();
            if (s != null) s.close();
            found = null;
            params = null;
        }
    }

    /** SETUP &rarr; ArduPilot drone setup. */
    static void open(MainWindow main) {
        State state = new State();
        state.radioFrom(main.config());
        WizardDialog w = create(main, state, () -> main.logLine("ArduPilot drone setup finished."));
        w.setVisible(true);
        state.close();
    }

    /** The guide's window, not shown yet; closing it lets the board go (the tests drive it as the pilot would). */
    static WizardDialog create(java.awt.Window owner, State state, Runnable finished) {
        return create(owner, state, pages(state), finished);
    }

    /** FIND, SETTINGS, PORTS, FRAME AND TUNING, CALIBRATE, MOTORS, BENCH on one board's state. */
    static List<WizardDialog.Page> pages(State state) {
        return List.of(new FindPage(state), new SettingsPage(state), new PortsPage(state),
                new TuningPage(state), new CalibratePage(state), new MotorsPage(state), new BenchPage(state));
    }

    static WizardDialog create(java.awt.Window owner, State state, List<WizardDialog.Page> pages, Runnable finished) {
        WizardDialog w = new WizardDialog(owner, "ArduPilot drone setup", pages, finished);
        w.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                state.close();
            }
        });
        return w;
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
        /** Fleet H1 / H4: ArduPilot onto a Betaflight board by USB DFU - the board the pilot picks, disarmed, confirmed. */
        private final JButton toArdu = Theme.secondaryButton("To ArduPilot (DFU)");

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
                    + "A Betaflight board is left alone until you click. Betaflight board: From Betaflight first, then "
                    + "To ArduPilot (USB DFU). Firmware: new ArduPilot onto a board that runs ArduPilot.";
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
            row.add(javax.swing.Box.createHorizontalStrut(8));
            row.add(toArdu);
            fromBf.addActionListener(e -> readBetaflight());
            toArdu.addActionListener(e -> toArduPilot());
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
            if (searching || flashing) return; // never while firmware is being written: the board is in its bootloader
            searching = true;
            state.close();
            fwFile.setEnabled(false);
            fwNewest.setEnabled(false);
            result.setText("Listening on USB ...");
            result.setForeground(Theme.MUTED);
            background(() -> {
                try {
                    ArduUsb.Found f = ArduUsb.find();
                    if (f == null) return "No ArduPilot board heard on USB.";
                    state.use(f);
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
            say("Looking for boards ...");
            String own = state.found == null ? null : state.found.port().systemName();
            background(() -> {
                try {
                    java.util.List<de.droiddrone.pc.setup.BetaflightUsb.Port> ports = de.droiddrone.pc.setup.BetaflightUsb.findFlightControllers();
                    // only the board the pilot picks is opened (as To ArduPilot); one alone is preselected, still confirmed
                    de.droiddrone.pc.setup.ardu.FromBetaflight.Carried c = de.droiddrone.pc.setup.ardu.FromBetaflight.readPicked(ports, own,
                            (boards, pre) -> {
                                de.droiddrone.pc.setup.BetaflightUsb.Port[] pick = {null};
                                try {
                                    SwingUtilities.invokeAndWait(() -> pick[0] = pickBoard(boards, pre));
                                } catch (Exception e) {
                                    return null;
                                }
                                if (pick[0] != null) say("Reading " + pick[0].systemName() + " ...");
                                return pick[0];
                            });
                    if (c == null) return ports.isEmpty() ? "No board on USB." : "Nothing read.";
                    state.carried = c;
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

        /**
         * The pilot's pick for From Betaflight, by port and name: one board is preselected but still confirmed; two
         * or more: none preselected, the pilot picks. null: cancelled.
         */
        private de.droiddrone.pc.setup.BetaflightUsb.Port pickBoard(List<de.droiddrone.pc.setup.BetaflightUsb.Port> boards,
                                                                  de.droiddrone.pc.setup.BetaflightUsb.Port pre) {
            javax.swing.JComboBox<de.droiddrone.pc.setup.BetaflightUsb.Port> board =
                    new javax.swing.JComboBox<>(boards.toArray(new de.droiddrone.pc.setup.BetaflightUsb.Port[0]));
            board.setSelectedItem(pre); // null with two or more: nothing chosen yet
            JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
            p.add(new JLabel(boards.size() == 1 ? "Read this board? Only it is opened." : "Which board? Only this one is opened."));
            p.add(board);
            while (true) {
                if (Dialogs.confirm(result, p, "From Betaflight", javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION) return null;
                Object chosen = board.getSelectedItem();
                if (chosen != null) return (de.droiddrone.pc.setup.BetaflightUsb.Port) chosen;
            }
        }

        /** Tests only: the file "picked" in place of the file chooser (null: the chooser). */
        static volatile java.util.function.Supplier<java.nio.file.Path> fileForTest;

        /** One board the pilot may pick for DFU: a Betaflight board's serial port, or a board already in DFU. */
        record DfuPick(de.droiddrone.pc.setup.BetaflightUsb.Port port, String dfuId) {
            @Override
            public String toString() {
                if (port != null) return port.systemName() + " - " + port.description();
                String id = dfuId.substring(dfuId.lastIndexOf('\\') + 1);
                return "In DFU now - " + id;
            }
        }

        /** The firmware, ready to write, and what it is. */
        record DfuFirmware(de.droiddrone.pc.setup.dfu.HexImage image, String what) {}

        /**
         * Fleet H1 / H4: Betaflight to ArduPilot. Lists the boards (serial ports and DFU devices - none opened), the
         * pilot picks one and the firmware; the board name is read (for the newest Copter), the firmware got, then a
         * last confirm names both, and only then the board is sent to DFU and written.
         */
        private void toArduPilot() {
            if (flashing) return;
            toArdu.setEnabled(false);
            say("Looking for boards ...");
            String own = state.found == null ? null : state.found.port().systemName();
            List<DfuPick> picks = new ArrayList<>();
            background(() -> {
                try {
                    for (de.droiddrone.pc.setup.BetaflightUsb.Port p : de.droiddrone.pc.setup.BetaflightUsb.findFlightControllers()) {
                        if (!p.systemName().equals(own)) picks.add(new DfuPick(p, null)); // an ArduPilot board found here is not offered
                    }
                    for (String id : de.droiddrone.pc.setup.ardu.ArduDfuFlow.bus().present()) picks.add(new DfuPick(null, id));
                    return null;
                } catch (Exception e) {
                    return e.getMessage();
                }
            }, failure -> {
                toArdu.setEnabled(true);
                if (failure != null || picks.isEmpty()) {
                    result.setText(failure != null ? "USB: " + failure : "No board on USB.");
                    result.setForeground(Theme.WARN);
                    return;
                }
                pickAndWrite(picks);
            });
        }

        private void pickAndWrite(List<DfuPick> picks) {
            javax.swing.JComboBox<DfuPick> board = new javax.swing.JComboBox<>(picks.toArray(new DfuPick[0]));
            if (picks.size() > 1) board.setSelectedItem(null); // two or more: the pilot picks, none preselected
            javax.swing.JComboBox<String> source = new javax.swing.JComboBox<>(new String[]{"Newest Copter for this board", "A file (_with_bl.hex)"});
            JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
            p.add(new JLabel("Which board? Only this one is touched."));
            p.add(board);
            p.add(new JLabel("Firmware"));
            p.add(source);
            if (state.carried == null) p.add(new JLabel("Tip: From Betaflight first keeps its battery, switches and motor order."));
            if (Dialogs.confirm(result, p, "To ArduPilot", javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION) return;
            DfuPick pick = (DfuPick) board.getSelectedItem();
            if (pick == null) {
                result.setText("No board picked: nothing touched.");
                result.setForeground(Theme.WARN);
                return;
            }
            boolean newest = source.getSelectedIndex() == 0;
            if (newest && pick.port() == null) {
                result.setText("A board already in DFU doesn't say its name: pick a file.");
                result.setForeground(Theme.WARN);
                return;
            }
            java.nio.file.Path file = null;
            java.util.function.Supplier<java.nio.file.Path> chosen = fileForTest;
            if (!newest && chosen != null) {
                file = chosen.get();
                if (file == null) return;
            } else if (!newest) {
                javax.swing.JFileChooser c = new javax.swing.JFileChooser();
                c.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ArduPilot with bootloader (.hex, .bin)", "hex", "bin"));
                if (c.showOpenDialog(result) != javax.swing.JFileChooser.APPROVE_OPTION) return;
                file = c.getSelectedFile().toPath();
            }
            java.nio.file.Path f = file;
            flashing = true;
            setButtons(false);
            background(() -> {
                try {
                    return firmwareFor(pick, f);
                } catch (Exception e) {
                    return new DfuFirmware(null, e.getMessage());
                }
            }, fw -> {
                if (fw.image() == null) {
                    dfuDone("To ArduPilot: " + fw.what());
                    return;
                }
                String q = "Write " + fw.what() + "\nonto " + pick + "?\n\nBetaflight and its settings are erased. Battery off, props off.\n"
                        + "Don't unplug until it says Done.";
                if (Dialogs.confirm(result, q, "To ArduPilot", javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION) {
                    dfuDone("Cancelled: nothing written.");
                    return;
                }
                writeDfu(pick, fw);
            });
        }

        /** The firmware: the file, or the newest stable Copter for the board's Betaflight name (asked disarmed). */
        private DfuFirmware firmwareFor(DfuPick pick, java.nio.file.Path file) throws Exception {
            if (file != null) {
                return new DfuFirmware(de.droiddrone.pc.setup.dfu.HexImage.read(java.nio.file.Files.readAllBytes(file)), file.getFileName().toString());
            }
            say("Reading the board's name ...");
            String name;
            try (de.droiddrone.pc.setup.BetaflightUsb bf = de.droiddrone.pc.setup.BetaflightUsb.open(pick.port())) {
                if (bf.armedNow()) throw new java.io.IOException("the drone is armed: only on the bench, disarmed");
                name = de.droiddrone.pc.setup.ardu.ArduDfuFlow.boardName(bf);
            }
            if (name == null) throw new java.io.IOException("Betaflight gives no board name: pick a file");
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
            say("Looking up Copter for " + name + " ...");
            String url = de.droiddrone.pc.setup.ardu.ArduDfuFlow.latestCopterHex(name, http);
            if (url == null) throw new java.io.IOException("no ArduPilot Copter for " + name + ": pick a file");
            say("Downloading ...");
            return new DfuFirmware(de.droiddrone.pc.setup.dfu.HexImage.read(de.droiddrone.pc.setup.ardu.ArduFirmwareFlow.download(url, http)),
                    "ArduCopter for " + name);
        }

        private void writeDfu(DfuPick pick, DfuFirmware fw) {
            background(() -> {
                try {
                    de.droiddrone.pc.setup.dfu.DfuDevice.Bus bus = de.droiddrone.pc.setup.ardu.ArduDfuFlow.bus();
                    if (pick.port() != null) {
                        waitForPort(pick.port().systemName()); // reading its name restarted it
                        de.droiddrone.pc.setup.ardu.ArduDfuFlow.fromBetaflight(de.droiddrone.pc.setup.ardu.ArduDfuFlow.betaflight(pick.port()),
                                bus, fw.image(), this::say, Thread::sleep, de.droiddrone.pc.setup.ardu.ArduDfuFlow.WAIT_DFU_MS);
                    } else {
                        de.droiddrone.pc.setup.ardu.ArduDfuFlow.inDfu(bus, pick.dfuId(), fw.image(), this::say, Thread::sleep);
                    }
                    log("ArduPilot setup: " + fw.what() + " written by DFU onto " + pick);
                    return null;
                } catch (Exception e) {
                    return "To ArduPilot: " + e.getMessage();
                }
            }, this::dfuDone);
        }

        /** The Betaflight board's port back after a restart (up to 8 s). */
        private static void waitForPort(String name) throws InterruptedException {
            long end = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < end) {
                for (de.droiddrone.pc.setup.BetaflightUsb.Port p : de.droiddrone.pc.setup.BetaflightUsb.findFlightControllers()) {
                    if (p.systemName().equals(name)) {
                        Thread.sleep(1500); // Betaflight up and answering
                        return;
                    }
                }
                Thread.sleep(250);
            }
        }

        private void dfuDone(String failure) {
            flashing = false;
            setButtons(true);
            if (failure == null) {
                result.setText("Done. Wait for ArduPilot to start, then Search again.");
                result.setForeground(Theme.OK);
            } else if (!failure.isEmpty()) {
                result.setText(failure);
                result.setForeground(Theme.WARN);
                log("ArduPilot setup: " + failure);
            }
        }

        private void setButtons(boolean on) {
            toArdu.setEnabled(on);
            fromBf.setEnabled(on);
            fwFile.setEnabled(on && state.found != null);
            fwNewest.setEnabled(on && state.found != null);
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
            confirm.setEnabled(false); // until there is a plan (a press before it did nothing)
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            Theme.transparent(row);
            row.add(confirm);
            p.add(row, BorderLayout.SOUTH);
            return p;
        }

        ArduPlan.Choices choices() {
            state.cells = (Integer) cells.getValue();
            de.droiddrone.pc.setup.ardu.FromBetaflight.Carried bf = state.carried;
            // the switches from Betaflight; cells and pack went into the boxes (and can be changed there)
            int arm = bf != null && bf.armAux() != null ? bf.armAux() : state.radioArmAux;
            int mode = bf != null && bf.modeAux() != null ? bf.modeAux() : state.radioModeAux;
            // ARM from Betaflight and the mode switch from this PC's radio may name one AUX: never both on one channel
            if (mode == arm) mode = ArduPlan.modeAuxBeside(arm, state.radioModeAux);
            return new ArduPlan.Choices((Integer) sysId.getValue(), (Integer) cells.getValue(), (Integer) capacity.getValue(), arm, mode,
                    de.droiddrone.pc.setup.ardu.ArduTuning.modesWith(ArduPlan.DEFAULT_MODES, state.autotunePos), (Integer) rtlAlt.getValue(), 10, 2.5, 1.5, true);
        }

        /** The board the plan and "written" belong to. */
        private int seenBoard = -1;

        @Override
        void onShow() {
            if (seenBoard != state.board) { // another board since: written on that one, not on this
                seenBoard = state.board;
                written = false;
                changes = List.of();
            }
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
            confirm.setEnabled(!writing && changes.stream().anyMatch(c -> !c.missing()));
            if (id != null && !written && id.intValue() != 1 && (Integer) sysId.getValue() == 2) sysId.setValue(id.intValue());
        }

        /** After a write: the plan against what the board holds now (the lines written stay shown); CONFIRM only for what is left. */
        private void replanAfterWrite() {
            Map<String, Float> now = state.params;
            if (now == null) return;
            de.droiddrone.pc.setup.ardu.FromBetaflight.Carried bf = state.carried;
            changes = ArduPlan.plan(choices(), bf == null ? Map.of() : bf.extra(), now);
            confirm.setEnabled(changes.stream().anyMatch(c -> !c.missing()));
        }

        /** A write runs: CONFIRM stays grey until it is over (a second press would write it all again). */
        private boolean writing;

        private void write() {
            MavSession fc = state.fc();
            if (fc == null || changes.isEmpty() || writing) return;
            writing = true;
            confirm.setEnabled(false);
            append(plan, "--- writing");
            List<ArduPlan.Change> todo = changes;
            int board = state.board;
            background(() -> {
                ArduSetupWriter.Result r;
                try {
                    r = new ArduSetupWriter(fc, l -> append(plan, l)).write(todo, AppPaths.arduPilotBackupsDir(), java.time.LocalDateTime.now());
                } catch (Exception e) {
                    return new ArduSetupWriter.Result(null, List.of(), e.getMessage());
                }
                if (r.ok() && board == state.board) {
                    try { // read again here, off Swing's thread (it was read on it: up to 30 s of a frozen window)
                        state.params = fc.readAllParams(30_000);
                    } catch (Exception ignored) {
                        // the next plan reads again
                    }
                }
                return r;
            }, r -> {
                writing = false;
                if (r == null) { // the worker failed outright (the footer says how)
                    confirm.setEnabled(true);
                    return;
                }
                if (r.ok()) {
                    if (board == state.board) written = true;
                    append(plan, "Done: " + r.written().size() + " written, each read back. Backup: " + r.backup().getFileName());
                    log("ArduPilot setup: " + r.written().size() + " parameters written, backup " + r.backup().getFileName());
                    if (board == state.board) replanAfterWrite();
                } else {
                    confirm.setEnabled(true);
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

    // ------------------------------------------------------------------ a plan, then CONFIRM (AS-2)

    /**
     * A page whose choices become a plan the pilot reads, then CONFIRM: backup, write, read back - the same path as
     * SETTINGS ({@link ArduSetupWriter}). Blocked lines (a port in use without REPLACE) keep CONFIRM grey.
     */
    abstract static class PlanPage extends WizardDialog.Page {
        final State state;
        final JTextArea plan = logArea();
        final JButton confirm = Theme.primaryButton("CONFIRM");
        List<ArduPlan.Change> changes = List.of();
        List<String> blocked = List.of(), notes = List.of();
        boolean written, replanning;

        PlanPage(State state) {
            this.state = state;
        }

        /** The page's own controls (above the plan). */
        abstract JComponent form();

        /** The wanted values for the parameters now; may set {@link #blocked} and {@link #notes}. */
        abstract Map<String, ArduPlan.Wanted> wanted(Map<String, Float> now);

        /** More buttons beside CONFIRM (none by default). */
        void moreButtons(JPanel row) {
        }

        @Override
        JComponent build() {
            JPanel p = new JPanel(new BorderLayout(0, 10));
            Theme.transparent(p);
            p.add(form(), BorderLayout.NORTH);
            p.add(new JScrollPane(plan), BorderLayout.CENTER);
            confirm.addActionListener(e -> write());
            confirm.setEnabled(false); // until there is a plan
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            Theme.transparent(row);
            row.add(confirm);
            moreButtons(row);
            p.add(row, BorderLayout.SOUTH);
            return p;
        }

        /** The board the page's plan belongs to. */
        int seenBoard = -1;

        /** Another board since the page was last shown: its choices seeded anew (none by default). */
        void newBoard() {
        }

        @Override
        void onShow() {
            if (seenBoard != state.board) {
                seenBoard = state.board;
                written = false;
                changes = List.of();
                newBoard();
            }
            if (state.params != null) {
                replan();
                return;
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

        void replan() {
            Map<String, Float> now = state.params;
            if (now == null || replanning || writing) return;
            replanning = true;
            try {
                replanNow(now);
            } finally {
                replanning = false;
            }
        }

        private void replanNow(Map<String, Float> now) {
            blocked = List.of();
            notes = List.of();
            changes = ArduPlan.changes(wanted(now), now);
            plan.setText("");
            for (String b : blocked) append(plan, "BLOCKED: " + b);
            List<String> lines = ArduPlan.lines(changes);
            if (lines.isEmpty() && blocked.isEmpty()) append(plan, "Nothing to change.");
            for (String l : lines) append(plan, l);
            for (String n : notes) append(plan, n);
            confirm.setEnabled(blocked.isEmpty() && !changes.isEmpty());
        }

        /** A write runs: CONFIRM stays grey and choices don't replan until it is over. */
        boolean writing;

        void write() {
            MavSession fc = state.fc();
            if (fc == null || changes.isEmpty() || !blocked.isEmpty() || writing) return;
            writing = true;
            confirm.setEnabled(false);
            append(plan, "--- writing");
            List<ArduPlan.Change> todo = changes;
            int board = state.board;
            background(() -> {
                try {
                    return new ArduSetupWriter(fc, l -> append(plan, l)).write(todo, AppPaths.arduPilotBackupsDir(), java.time.LocalDateTime.now());
                } catch (Exception e) {
                    return new ArduSetupWriter.Result(null, List.of(), e.getMessage());
                }
            }, r -> {
                writing = false;
                if (r == null) { // the worker failed outright (the footer says how)
                    confirm.setEnabled(true);
                    return;
                }
                Map<String, Float> now = state.params;
                if (r.ok() && board == state.board && now != null) {
                    written = true;
                    Map<String, Float> p = new java.util.HashMap<>(now);
                    for (ArduPlan.Change c : todo) if (r.written().contains(c.name())) p.put(c.name(), c.to());
                    state.params = p;
                    replan(); // what is left now (nothing, unless a choice changed meanwhile); CONFIRM only for that
                    append(plan, "Done: " + r.written().size() + " written, each read back. Backup: " + r.backup().getFileName());
                    log("ArduPilot setup, " + title() + ": " + r.written().size() + " written, backup " + r.backup().getFileName());
                } else if (r.ok()) {
                    append(plan, "Done on the board found before; this one is not written.");
                } else {
                    confirm.setEnabled(true);
                    append(plan, "STOPPED: " + r.failure());
                    log("ArduPilot setup stopped: " + r.failure());
                }
            });
        }

        /** Next: the plan shown is on the board - nothing left to write, nothing blocked (a changed choice after CONFIRM needs CONFIRM again). */
        @Override
        boolean canContinue() {
            return !writing && state.params != null && blocked.isEmpty() && changes.stream().allMatch(ArduPlan.Change::missing);
        }

        @Override
        String status() {
            return canContinue() ? null : blocked.isEmpty() ? "CONFIRM writes the plan." : "Blocked: see the plan.";
        }
    }

    // ------------------------------------------------------------------ PORTS (AS-6)

    static final class PortsPage extends PlanPage {
        private final javax.swing.JComboBox<String> gps = serials("Not set here");
        private final javax.swing.JComboBox<String> radio = serials("None fitted");
        private final javax.swing.JComboBox<Integer> radioBaud = new javax.swing.JComboBox<>(
                java.util.Arrays.stream(de.droiddrone.pc.setup.ardu.ArduPorts.RADIO_BAUDS).boxed().toArray(Integer[]::new));
        private final javax.swing.JCheckBox replace = new javax.swing.JCheckBox("REPLACE what a chosen port does now");
        private final JLabel now = Theme.label(" ", Theme.font(Font.PLAIN, 13f), Theme.MUTED);

        PortsPage(State state) {
            super(state);
        }

        private static javax.swing.JComboBox<String> serials(String none) {
            javax.swing.JComboBox<String> c = new javax.swing.JComboBox<>();
            c.addItem(none);
            for (int n = 1; n <= de.droiddrone.pc.setup.ardu.ArduPorts.MAX_SERIAL; n++) c.addItem("SERIAL" + n);
            return c;
        }

        @Override
        String title() {
            return "Ports";
        }

        @Override
        String hint() {
            return "USB stays MAVLink for the phone. Pick the GPS's port and the drone-to-drone radio's, if fitted "
                    + "(the board's ArduPilot page says which UART is which SERIAL). A port that does something else is kept unless you tick REPLACE.";
        }

        @Override
        JComponent form() {
            JPanel f = new JPanel(new GridLayout(0, 2, 10, 6));
            Theme.transparent(f);
            f.add(Theme.label("GPS PORT", Theme.headingFont(13f), Theme.MUTED));
            f.add(gps);
            f.add(Theme.label("RADIO PORT", Theme.headingFont(13f), Theme.MUTED));
            f.add(radio);
            f.add(Theme.label("RADIO SPEED (k)", Theme.headingFont(13f), Theme.MUTED));
            f.add(radioBaud);
            f.add(replace);
            f.add(now);
            replace.setOpaque(false);
            gps.addActionListener(e -> replan());
            radio.addActionListener(e -> replan());
            radioBaud.addActionListener(e -> replan());
            replace.addActionListener(e -> replan());
            return f;
        }

        /** The GPS's port was preselected once for this board: after that "Not set here" is the pilot's own choice. */
        private boolean gpsSeeded;

        @Override
        void newBoard() {
            gpsSeeded = false;
            gps.setSelectedIndex(0);
        }

        @Override
        void replan() {
            Map<String, Float> p = state.params;
            if (p != null && !replanning && !writing) {
                StringBuilder sb = new StringBuilder("<html>");
                int gpsNow = 0;
                for (de.droiddrone.pc.setup.ardu.ArduPorts.Port port : de.droiddrone.pc.setup.ardu.ArduPorts.ports(p)) {
                    sb.append("SERIAL").append(port.n()).append(": ").append(de.droiddrone.pc.setup.ardu.ArduPorts.name(port.protocol())).append("<br>");
                    if (port.n() > 0 && port.protocol() == de.droiddrone.pc.setup.ardu.ArduPorts.GPS && gpsNow == 0) gpsNow = port.n();
                }
                now.setText(sb.append("</html>").toString());
                if (!gpsSeeded) {
                    // once: the GPS where it is now (it was set back on every change: a pilot could never choose another
                    // port for the radio where the GPS was without a GPS port too)
                    gpsSeeded = true;
                    if (gpsNow > 0 && gps.getSelectedIndex() == 0) gps.setSelectedIndex(gpsNow);
                }
            }
            super.replan();
        }

        @Override
        Map<String, ArduPlan.Wanted> wanted(Map<String, Float> params) {
            de.droiddrone.pc.setup.ardu.ArduPorts.Plan p = de.droiddrone.pc.setup.ardu.ArduPorts.plan(
                    new de.droiddrone.pc.setup.ardu.ArduPorts.Choice(gps.getSelectedIndex(), radio.getSelectedIndex(),
                            (Integer) radioBaud.getSelectedItem(), replace.isSelected()), params);
            blocked = p.blocked();
            notes = p.notes();
            return p.wanted();
        }
    }

    // ------------------------------------------------------------------ TUNING (AS-7)

    static final class TuningPage extends PlanPage {
        private final javax.swing.JComboBox<de.droiddrone.pc.setup.ardu.ArduTuning.Frame> frame =
                new javax.swing.JComboBox<>(de.droiddrone.pc.setup.ardu.ArduTuning.Frame.values());
        private final javax.swing.JComboBox<Object> size = new javax.swing.JComboBox<>();
        private final javax.swing.JCheckBox autotune = new javax.swing.JCheckBox("AUTOTUNE on the mode switch");
        private final JSpinner position = new JSpinner(new SpinnerNumberModel(4, 1, 6, 1));
        private final JLabel howto = Theme.label(" ", Theme.font(Font.PLAIN, 13f), Theme.MUTED);
        private final JButton restart = Theme.secondaryButton("RESTART BOARD");
        private boolean seeded;
        static final String KEEP = "Keep its tune";

        TuningPage(State state) {
            super(state);
            size.addItem(KEEP);
            for (de.droiddrone.pc.setup.ardu.ArduTuning.Size s : de.droiddrone.pc.setup.ardu.ArduTuning.Size.values()) size.addItem(s);
        }

        @Override
        String title() {
            return "Frame and tuning";
        }

        @Override
        String hint() {
            return "Motor order and a size preset: starting points, not a finished tune. AUTOTUNE tunes it in the air. "
                    + "RESTART BOARD after ports or frame change.";
        }

        @Override
        JComponent form() {
            JPanel f = new JPanel(new GridLayout(0, 2, 10, 6));
            Theme.transparent(f);
            f.add(Theme.label("MOTOR ORDER", Theme.headingFont(13f), Theme.MUTED));
            f.add(frame);
            f.add(Theme.label("SIZE PRESET", Theme.headingFont(13f), Theme.MUTED));
            f.add(size);
            autotune.setOpaque(false);
            f.add(autotune);
            JPanel pos = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            Theme.transparent(pos);
            pos.add(Theme.label("POSITION", Theme.headingFont(13f), Theme.MUTED));
            pos.add(position);
            f.add(pos);
            frame.addActionListener(e -> replan());
            size.addActionListener(e -> replan());
            autotune.addActionListener(e -> replan());
            position.addChangeListener(e -> replan());
            JPanel all = new JPanel(new BorderLayout(0, 6));
            Theme.transparent(all);
            all.add(f, BorderLayout.NORTH);
            all.add(howto, BorderLayout.CENTER);
            return all;
        }

        @Override
        void moreButtons(JPanel row) {
            restart.addActionListener(e -> restart());
            row.add(restart);
        }

        @Override
        void onShow() {
            if (!seeded) { // fleet AS-11: Betaflight's motor order when it was read
                seeded = true;
                de.droiddrone.pc.setup.ardu.FromBetaflight.Carried bf = state.carried;
                frame.setSelectedItem(de.droiddrone.pc.setup.ardu.ArduTuning.Frame.ofType(bf == null ? null : bf.frameType()));
            }
            super.onShow();
        }

        @Override
        Map<String, ArduPlan.Wanted> wanted(Map<String, Float> params) {
            int pos = autotune.isSelected() ? (Integer) position.getValue() : 0;
            String no = de.droiddrone.pc.setup.ardu.ArduTuning.autotuneRefused(pos);
            if (no != null) {
                blocked = List.of(no);
                pos = 0;
            }
            state.autotunePos = pos;
            Object s = size.getSelectedItem();
            de.droiddrone.pc.setup.ardu.ArduTuning.Size sz = s instanceof de.droiddrone.pc.setup.ardu.ArduTuning.Size z ? z : null;
            String text = sz == null ? "Its tune is kept." : de.droiddrone.pc.setup.ardu.ArduTuning.summary(sz) + ".";
            if (pos > 0) text += " Position " + pos + ": " + de.droiddrone.pc.setup.ardu.ArduTuning.AUTOTUNE_HOWTO;
            howto.setText("<html><div style='width:560px'>" + text + "</div></html>");
            // AUTOTUNE taken off (or moved): the position it had gets its own mode back
            return de.droiddrone.pc.setup.ardu.ArduTuning.wanted(new de.droiddrone.pc.setup.ardu.ArduTuning.Choice(
                    (de.droiddrone.pc.setup.ardu.ArduTuning.Frame) frame.getSelectedItem(), sz, state.cells, pos), params);
        }

        /** Ports and frame take effect after a restart: the board restarts and is found again (it must speak first). */
        private void restart() {
            MavSession fc = state.fc();
            if (fc == null) return;
            if (fc.armed()) {
                append(plan, "Armed, or not known: no restart.");
                return;
            }
            restart.setEnabled(false);
            append(plan, "Restarting ...");
            background(() -> {
                try {
                    new ArduTools(fc).reboot();
                    state.close();
                    Thread.sleep(6000);
                    ArduUsb.Found f = ArduUsb.find();
                    if (f == null) return "Not back yet: FIND, Search again.";
                    state.use(f);
                    state.params = f.session().readAllParams(30_000);
                    return null;
                } catch (Exception e) {
                    return "Restart: " + e.getMessage();
                }
            }, failure -> {
                restart.setEnabled(true);
                append(plan, failure == null ? "Back: the board answers again." : failure);
                if (failure == null) replan();
            });
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
            level.setEnabled(false); // LEVEL or COMPASS in the middle of the six positions would start another calibration
            compass.setEnabled(false);
            background(() -> {
                try {
                    cal = new ArduTools(fc).startAccelCal(pos -> SwingUtilities.invokeLater(() -> show(pos)),
                            ok -> SwingUtilities.invokeLater(() -> {
                                accelDone = ok;
                                ask.setText(ok ? "Accelerometer: done" : "Accelerometer: failed, again");
                                placed.setEnabled(false);
                                accel.setEnabled(true);
                                level.setEnabled(true);
                                compass.setEnabled(true);
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
                    level.setEnabled(true);
                    compass.setEnabled(true);
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
        private final JButton arm = Theme.primaryButton("ARM TEST");

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
            arm.addActionListener(e -> armTest());
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            Theme.transparent(row);
            row.add(arm);
            p.add(row, BorderLayout.NORTH);
            p.add(new JScrollPane(out), BorderLayout.CENTER);
            p.setBorder(BorderFactory.createEmptyBorder());
            return p;
        }

        /** One test at a time (a second press sent the overrides twice over, arming while the first disarmed). */
        private boolean testing;
        /** ArduPilot's texts while a test runs (one listener for the page: one was added per press). */
        private final List<String> texts = new ArrayList<>();
        private MavSession listening;

        private void armTest() {
            MavSession fc = state.fc();
            if (fc == null || testing) return;
            testing = true;
            arm.setEnabled(false);
            synchronized (texts) {
                texts.clear();
            }
            if (listening != fc) {
                listening = fc;
                fc.onText(t -> {
                    synchronized (texts) {
                        texts.add(t);
                    }
                });
            }
            background(() -> {
                try {
                    // the ARM switch where the board has it now (SETTINGS put it on this PC's radio's AUX, not always AUX1)
                    Map<String, Float> p = state.params;
                    int aux = ArduBenchCheck.armAux(p != null ? p : fc.readAllParams(30_000));
                    if (aux < 1) return "No ARM switch on the board: SETTINGS first.";
                    return ArduBenchCheck.armTest(fc, aux, l -> append(out, l));
                } catch (Exception e) {
                    return "arm test: " + e.getMessage();
                }
            }, line -> {
                testing = false;
                arm.setEnabled(true);
                append(out, String.valueOf(line));
                synchronized (texts) {
                    for (String t : texts) if (t.startsWith("PreArm") || t.startsWith("Arm")) append(out, "  " + t);
                }
            });
        }
    }
}
