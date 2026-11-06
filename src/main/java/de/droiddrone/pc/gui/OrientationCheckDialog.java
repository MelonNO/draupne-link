package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.Orientation;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.List;

/**
 * Drone setup &gt; Orientation (1.0.42): a drone in 3D that moves as Betaflight sees the real one, and a check that
 * Betaflight sees the moves the right way round - flat, nose up, flat, right side down. A board turned on the frame
 * and not set in Betaflight's board alignment corrects the wrong way and flips the drone on takeoff; FIX IT turns the
 * alignment by what the check found (backup first, as Drone setup does), and the check runs again after the restart.
 * Over this PC's USB, on the bench, no battery needed.
 */
final class OrientationCheckDialog extends JFrame {
    /** After "save" Betaflight restarts and its USB port is gone for a few seconds. */
    private static final long RESTART_MS = 6000;
    /** Readings this still for this long: lying flat. */
    private static final long STILL_MS = 1200;
    private static final double STILL_DEG = 1.5;

    private enum Step { FLAT, NOSE_UP, FLAT_AGAIN, RIGHT_DOWN, DONE }

    private final MainWindow main;
    private final String chosenPort;
    private final Runnable onPassed;
    private final CheckRow usb = new CheckRow("Flight controller on USB");
    private final CheckRow flat = new CheckRow("Flat");
    private final CheckRow noseRow = new CheckRow("Nose up");
    private final CheckRow rightRow = new CheckRow("Right side down");
    private final CheckRow result = new CheckRow("Betaflight sees it");
    private final DroneModelView model = new DroneModelView();
    private final javax.swing.JLabel instruction = Theme.label(" ", Theme.headingFont(22f), Theme.TEXT);
    private final javax.swing.JLabel reading = Theme.label(" ", Theme.font(Font.PLAIN, 13f), Theme.MUTED);
    private final JButton fix = Theme.primaryButton("Fix it");
    private final JButton again = Theme.secondaryButton("Check again");
    private volatile boolean running = true;
    private volatile boolean restartWanted;
    /** FIX IT pressed: the turn to add to the board alignment (yaw, or 180 on roll for upside down). */
    private volatile int fixYaw = -1;
    private volatile boolean fixRoll;
    private volatile boolean fixWanted;
    private Thread worker;

    private OrientationCheckDialog(Window owner, MainWindow main, String chosenPort, Runnable onPassed) {
        super("Orientation check - DRAUPNE Link");
        setIconImages(AppIcon.images());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE); // beside Drone setup's guide
        this.main = main;
        this.chosenPort = chosenPort;
        this.onPassed = onPassed;
        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        setContentPane(root);

        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(Theme.label("ORIENTATION", Theme.headingFont(26f), Theme.TEXT), BorderLayout.NORTH);
        head.add(Theme.label("The drone on screen moves as Betaflight sees the real one. Stand behind the drone, its front away from you, "
                + "and do what it says. Props off.", Theme.font(Font.PLAIN, 13f), Theme.MUTED), BorderLayout.SOUTH);
        root.add(head, BorderLayout.NORTH);

        JPanel side = Theme.card(null);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(340, 10));
        for (CheckRow r : new CheckRow[]{usb, flat, noseRow, rightRow, result}) {
            r.setAlignmentX(LEFT_ALIGNMENT);
            side.add(r);
            side.add(javax.swing.Box.createVerticalStrut(6));
        }
        root.add(side, BorderLayout.WEST);

        JPanel card = Theme.card(new BorderLayout(0, 8));
        card.add(instruction, BorderLayout.NORTH);
        card.add(model, BorderLayout.CENTER);
        card.add(reading, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);

        fix.setVisible(false);
        fix.addActionListener(e -> {
            fixWanted = true;
            fix.setEnabled(false);
        });
        again.addActionListener(e -> restartWanted = true);
        JButton close = Theme.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(fix);
        buttons.add(again);
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        usb.set(State.PENDING, "Looking for it...");
        resetRows();
        setMinimumSize(new Dimension(940, 600));
        setSize(1000, 660);
        setLocationRelativeTo(owner);
        WindowReveal.onOpen(this);
        worker = new Thread(this::run, "orientation-check");
        worker.setDaemon(true);
        worker.start();
    }

    private static OrientationCheckDialog open;

    /** Open now (it may have the flight controller's USB port). */
    static boolean isOpen() {
        return open != null && open.isDisplayable();
    }

    /**
     * @param chosenPort the USB port Drone setup uses, or null for the first that answers as Betaflight
     * @param onPassed   run (Swing thread) when Betaflight saw every move the right way, or null
     * @param onClosed   run once the window is closed (and so stops using the USB port), or null
     */
    static void open(Window owner, MainWindow main, String chosenPort, Runnable onPassed, Runnable onClosed) {
        if (open == null || !open.isDisplayable()) {
            open = new OrientationCheckDialog(owner, main, chosenPort, onPassed);
            open.setVisible(true);
        } else {
            if ((open.getExtendedState() & ICONIFIED) != 0) open.setExtendedState(open.getExtendedState() & ~ICONIFIED);
            open.toFront();
        }
        if (onClosed != null) {
            open.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    onClosed.run();
                }
            });
        }
    }

    @Override
    public void dispose() {
        if (open == this) open = null;
        running = false;
        if (worker != null) worker.interrupt();
        super.dispose();
    }

    private void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    private void resetRows() {
        flat.set(State.PENDING, "");
        noseRow.set(State.PENDING, "");
        rightRow.set(State.PENDING, "");
        result.set(State.PENDING, "");
        fix.setVisible(false);
        fix.setEnabled(true);
    }

    // ------------------------------------------------------------------ the worker

    private Step step;
    private Orientation.Attitude level;
    private double[] noseUp, rightDown;
    private Orientation.Attitude stillFrom;
    private long stillSince;
    private int startHeading = -1;

    private void restartSteps() {
        step = Step.FLAT;
        level = null;
        noseUp = rightDown = null;
        stillFrom = null;
        fixYaw = -1;
        fixRoll = false;
        ui(() -> {
            resetRows();
            flat.set(State.PENDING, "Put it flat on the table, front away from you.");
            instruction.setText("PUT THE DRONE FLAT");
        });
    }

    private void run() {
        BetaflightUsb fc = null;
        long retryAt = 0;
        try {
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    if (fc == null) {
                        if (now < retryAt) {
                            Thread.sleep(200);
                            continue;
                        }
                        fc = openBetaflight();
                        if (fc == null) {
                            retryAt = now + 1500;
                            continue;
                        }
                        startHeading = -1;
                        ui(() -> usb.set(State.OK, "Found."));
                        restartSteps();
                    }
                    if (restartWanted) {
                        restartWanted = false;
                        restartSteps();
                    }
                    if (fixWanted) {
                        fixWanted = false;
                        writeFix(fc);
                        fc = null; // Betaflight restarts
                        retryAt = System.currentTimeMillis() + RESTART_MS;
                        ui(() -> {
                            usb.set(State.PENDING, "Betaflight restarts...");
                            model.clear();
                        });
                        continue;
                    }
                    Orientation.Attitude a = Orientation.parse(fc.msp(Orientation.MSP_ATTITUDE, null));
                    if (a != null) {
                        if (startHeading < 0) startHeading = a.headingDeg();
                        double yaw = a.headingDeg() - startHeading;
                        ui(() -> {
                            model.setAttitude(a.rollDeg(), a.pitchDeg(), yaw);
                            reading.setText("Betaflight: " + Orientation.describe(a.rollDeg(), a.pitchDeg()));
                        });
                        advance(a, System.currentTimeMillis());
                    }
                    Thread.sleep(33);
                } catch (InterruptedException e) {
                    break;
                } catch (IOException | RuntimeException e) {
                    if (fc != null) fc.close();
                    fc = null;
                    retryAt = System.currentTimeMillis() + 1500;
                    String msg = e.getMessage();
                    ui(() -> {
                        usb.set(State.BAD, "Lost: " + msg);
                        model.clear();
                    });
                }
            }
        } finally {
            if (fc != null) fc.close();
        }
    }

    /** One reading: on to the next step when the move asked for is done. */
    private void advance(Orientation.Attitude a, long now) {
        switch (step) {
            case FLAT -> {
                if (stillFrom == null || Orientation.size(Orientation.delta(stillFrom, a)) > STILL_DEG) {
                    stillFrom = a;
                    stillSince = now;
                    return;
                }
                if (now - stillSince < STILL_MS) return;
                level = a;
                if (Orientation.upsideDown(a)) {
                    step = Step.DONE;
                    fixRoll = true;
                    main.logLine("Orientation check: Betaflight sees the drone on its back while it lies flat.");
                    ui(() -> {
                        flat.set(State.BAD, "Betaflight sees it on its back.");
                        result.set(State.BAD, "Upside down: the board is mounted upside down and Betaflight isn't told. "
                                + "<b>Fix it</b> turns Betaflight's board alignment roll by 180°.");
                        instruction.setText("UPSIDE DOWN TO BETAFLIGHT");
                        fix.setVisible(true);
                    });
                    return;
                }
                step = Step.NOSE_UP;
                ui(() -> {
                    flat.set(State.OK, "Flat.");
                    noseRow.set(State.PENDING, "Lift the nose, about 45°, the tail on the table.");
                    instruction.setText("LIFT THE NOSE");
                });
            }
            case NOSE_UP -> {
                double[] d = Orientation.delta(level, a);
                if (Orientation.size(d) < Orientation.MOVE_DEG) return;
                noseUp = d;
                step = Step.FLAT_AGAIN;
                ui(() -> {
                    noseRow.set(State.OK, "Done.");
                    rightRow.set(State.PENDING, "Put it flat again first.");
                    instruction.setText("FLAT AGAIN");
                });
            }
            case FLAT_AGAIN -> {
                if (Orientation.size(Orientation.delta(level, a)) > Orientation.FLAT_DEG) return;
                step = Step.RIGHT_DOWN;
                ui(() -> {
                    rightRow.set(State.PENDING, "Lower the right side, about 45°, the left on the table.");
                    instruction.setText("LOWER THE RIGHT SIDE");
                });
            }
            case RIGHT_DOWN -> {
                double[] d = Orientation.delta(level, a);
                if (Orientation.size(d) < Orientation.MOVE_DEG) return;
                rightDown = d;
                step = Step.DONE;
                judge();
            }
            case DONE -> { }
        }
    }

    private void judge() {
        int turn = Orientation.boardTurn(noseUp, rightDown);
        String seen = String.format(java.util.Locale.ROOT, "nose up seen as roll %+.0f°, pitch %+.0f°; right down as roll %+.0f°, pitch %+.0f°",
                noseUp[0], noseUp[1], rightDown[0], rightDown[1]);
        main.logLine("Orientation check: " + seen + (turn == 0 ? " - right." : turn > 0 ? " - the board is turned " + turn + "°." : " - no turn explains it."));
        fixYaw = turn > 0 ? turn : -1;
        ui(() -> {
            rightRow.set(State.OK, "Done.");
            if (turn == 0) {
                result.set(State.OK, "The right way round. Safe to fly as far as this goes.");
                instruction.setText("ALL RIGHT");
                if (onPassed != null) onPassed.run();
            } else if (turn > 0) {
                String how = turn == 180 ? "turned round: nose up looks like nose down to it, and it flips on takeoff"
                        : "turned " + turn + "° on the frame: it mixes up pitch and roll, and it flips on takeoff";
                result.set(State.BAD, "Betaflight sees the drone " + how + ". <b>Fix it</b> turns Betaflight's board alignment (yaw) by " + turn + "°, then check again.");
                instruction.setText("WRONG WAY ROUND");
                fix.setVisible(true);
            } else {
                result.set(State.BAD, "Betaflight's tilts don't match a turned board (" + seen + "). Do the moves again, slowly; if it stays so, "
                        + "set the board alignment in Betaflight Configurator.");
                instruction.setText("NOT CLEAR");
            }
        });
    }

    /** Writes the alignment found (a backup of the settings first) and saves; Betaflight restarts. */
    private void writeFix(BetaflightUsb fc) throws IOException {
        boolean roll = fixRoll;
        int turn = fixYaw;
        if (!roll && turn <= 0) return;
        String name = roll ? "align_board_roll" : "align_board_yaw";
        int more = roll ? 180 : turn;
        fc.enterCli();
        String version = fc.command("version");
        java.nio.file.Path backup = backup(fc.command("diff all", 20_000), version);
        Integer current = Orientation.parseSetting(fc.command("get " + name), name);
        if (current == null) {
            fc.exitCli();
            throw new IOException("Betaflight doesn't know " + name);
        }
        int next = Orientation.turned(current, more);
        String cmd = "set " + name + " = " + next;
        String reply = fc.command(cmd);
        main.logLine("Betaflight CLI: " + cmd + " -> " + DroneSetup.BetaflightPage.brief(reply));
        fc.saveAndReboot();
        main.logLine("Betaflight: board alignment " + (roll ? "roll" : "yaw") + " " + current + "° -> " + next + "° (orientation check)."
                + (backup == null ? "" : " Its settings before: " + backup));
        ui(() -> result.set(State.PENDING, "Written: " + (roll ? "roll" : "yaw") + " " + current + "° -> " + next + "°. Checking again once Betaflight is back..."));
    }

    private java.nio.file.Path backup(String diff, String version) {
        try {
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            java.nio.file.Path file = de.droiddrone.pc.AppPaths.betaflightBackupsDir().resolve("betaflight-" + stamp + ".txt");
            java.nio.file.Files.writeString(file, "# " + version.lines().findFirst().orElse("").trim() + "\n# saved by DRAUPNE Link before the orientation check changed it. "
                    + "To go back: paste this into Betaflight Configurator's CLI tab, then type save.\n\n" + diff + "\n");
            return file;
        } catch (IOException e) {
            main.logLine("Could not save a backup of Betaflight's settings: " + e.getMessage());
            return null;
        }
    }

    /** The chosen port, or the first port that answers as Betaflight; null (and why, shown) if none. */
    private BetaflightUsb openBetaflight() {
        List<BetaflightUsb.Port> ports = BetaflightUsb.findFlightControllers();
        if (chosenPort != null) ports = ports.stream().filter(p -> p.systemName().equals(chosenPort)).toList();
        if (ports.isEmpty()) {
            ui(() -> usb.set(State.WARN, "Not found yet. Plug it into this PC with a data USB cable."));
            return null;
        }
        String why = null;
        for (BetaflightUsb.Port port : ports) {
            BetaflightUsb fc = null;
            try {
                fc = BetaflightUsb.open(port);
                String firmware = fc.firmware();
                if (firmware.startsWith("BTFL")) return fc;
                why = port.systemName() + " runs " + DroneSetup.BetaflightPage.firmwareName(firmware) + ", not Betaflight.";
                fc.close();
            } catch (IOException e) {
                why = port.systemName() + ": " + e.getMessage();
                if (fc != null) fc.close();
            }
        }
        String w = why;
        ui(() -> usb.set(State.BAD, w));
        return null;
    }
}
