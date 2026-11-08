package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.GpsSetup;
import de.droiddrone.pc.setup.Orientation;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Drone setup &gt; Motors and orientation &gt; CHECK IT (1.0.42, guided since 1.0.52): the pilot is told how to hold
 * the drone, the 3D drone shows it, and Betaflight's readings decide. First the board - flat, nose up, flat, right side
 * down: a board turned on the frame and not set in Betaflight flips the drone on takeoff. Then, with a compass, the
 * nose north, east, south and west: a compass the wrong way round sends GPS rescue the wrong way. What is wrong is
 * set by the program itself (board alignment, align_mag; a backup first, as Drone setup does), and checked again
 * after the restart, as many rounds as it takes. Over this PC's USB, on the bench, props off.
 */
final class OrientationCheckDialog extends JFrame {
    /** After "save" Betaflight restarts and its USB port is gone for a few seconds. */
    private static final long RESTART_MS = 6000;
    /** Readings this still for this long: lying flat. */
    private static final long STILL_MS = 1200;
    private static final double STILL_DEG = 1.5;
    /** Compass readings averaged per heading. */
    private static final int MAG_SAMPLES = 10;
    /** Compass writes at most before the check gives up (DEFAULT -> CW0, then a board alignment guess each). */
    private static final int MAX_COMPASS_WRITES = 4;

    private enum Step { FLAT, NOSE_UP, FLAT_AGAIN, RIGHT_DOWN, NORTH, EAST, SOUTH, WEST, DONE }

    private final MainWindow main;
    private final String chosenPort;
    private final Runnable onPassed;
    private final CheckRow usb = new CheckRow("Flight controller on USB");
    private final CheckRow flat = new CheckRow("Flat");
    private final CheckRow noseRow = new CheckRow("Nose up");
    private final CheckRow rightRow = new CheckRow("Right side down");
    private final CheckRow compassRow = new CheckRow("Compass");
    private final CheckRow result = new CheckRow("Betaflight sees it");
    private final DroneModelView model = new DroneModelView();
    private final javax.swing.JLabel instruction = Theme.label(" ", Theme.headingFont(22f), Theme.TEXT);
    private final javax.swing.JLabel reading = Theme.label(" ", Theme.font(Font.PLAIN, 13f), Theme.MUTED);
    private final JButton done = Theme.primaryButton("Done");
    private final JButton skip = Theme.secondaryButton("Skip compass");
    private final JButton fix = Theme.primaryButton("Fix it");
    private final JButton again = Theme.secondaryButton("Check again");
    /** The pose the drone is shown in (what to do): eased towards the target. */
    private final double[] shown = new double[3], target = new double[3];
    private final Timer ease = new Timer(30, e -> easeModel());
    private volatile boolean running = true;
    private volatile boolean restartWanted;
    /** FIX IT pressed: the turn to add to the board alignment (yaw, or 180 on roll for upside down). */
    private volatile int fixYaw = -1;
    private volatile boolean fixRoll;
    private volatile boolean fixWanted;
    private volatile boolean sampleWanted;
    private volatile boolean skipCompass;
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
        head.add(Theme.label("ORIENTATION CHECK", Theme.headingFont(26f), Theme.TEXT), BorderLayout.NORTH);
        head.add(Theme.label("Stand behind the drone. Hold it as shown. Props off.", Theme.font(Font.PLAIN, 13f), Theme.MUTED),
                BorderLayout.SOUTH);
        root.add(head, BorderLayout.NORTH);

        JPanel side = Theme.card(null);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(320, 10));
        for (CheckRow r : new CheckRow[]{usb, flat, noseRow, rightRow, compassRow, result}) {
            r.setAlignmentX(LEFT_ALIGNMENT);
            side.add(r);
            side.add(javax.swing.Box.createVerticalStrut(6));
        }
        side.add(javax.swing.Box.createVerticalGlue());
        root.add(side, BorderLayout.WEST);

        JPanel card = Theme.card(new BorderLayout(0, 8));
        card.add(instruction, BorderLayout.NORTH);
        card.add(model, BorderLayout.CENTER);
        card.add(reading, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);

        done.setVisible(false);
        done.addActionListener(e -> {
            sampleWanted = true;
            done.setEnabled(false);
        });
        skip.setVisible(false);
        skip.addActionListener(e -> skipCompass = true);
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
        for (JButton b : new JButton[]{skip, done, fix, again, close}) buttons.add(b);
        root.add(buttons, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        usb.set(State.PENDING, "Searching...");
        resetRows();
        ScreenFit.apply(this, 760, 520, 1000, 660);
        setLocationRelativeTo(owner);
        WindowReveal.onOpen(this);
        model.setAttitude(0, 0, 0);
        ease.start();
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
        ease.stop();
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
        compassRow.set(State.PENDING, "");
        result.set(State.PENDING, "");
        fix.setVisible(false);
        fix.setEnabled(true);
    }

    /** What the drone should look like now: the model eases there (roll, pitch, yaw; Betaflight's signs). */
    private void pose(String what, double roll, double pitch, double yaw, boolean north) {
        ui(() -> {
            instruction.setText(what);
            target[0] = roll;
            target[1] = pitch;
            target[2] = yaw;
            model.setNorthMark(north);
        });
    }

    private void easeModel() {
        boolean moved = false;
        for (int i = 0; i < 3; i++) {
            double d = target[i] - shown[i];
            if (Math.abs(d) < 0.2) {
                if (shown[i] != target[i]) moved = true;
                shown[i] = target[i];
            } else {
                shown[i] += d * 0.12;
                moved = true;
            }
        }
        if (moved) model.setAttitude(shown[0], shown[1], shown[2]);
    }

    // ------------------------------------------------------------------ the worker

    private Step step;
    private Orientation.Attitude level;
    private double[] noseUp, rightDown;
    private Orientation.Attitude stillFrom;
    private long stillSince;
    /** The board passed: after a compass write's restart the check goes straight to the compass. */
    private boolean boardOk;
    private boolean magPresent;
    private final double[][] mag = new double[4][];
    private int compassWrites;
    /** Which board-alignment guess the next compass fix uses (see compassBoards). */
    private int boardGuess;
    /** The last compass write was a fix (not DEFAULT -> CW0): a wrong result after it moves on to the next guess. */
    private boolean lastWasFix;
    /** Passed, or the compass given up on: a reconnect leaves the verdict as it is. */
    private boolean finished;

    private void restartSteps() {
        finished = false;
        step = Step.FLAT;
        level = null;
        noseUp = rightDown = null;
        stillFrom = null;
        fixYaw = -1;
        fixRoll = false;
        boardOk = false;
        compassWrites = 0;
        boardGuess = 0;
        lastWasFix = false;
        ui(() -> {
            resetRows();
            done.setVisible(false);
            skip.setVisible(false);
            flat.set(State.PENDING, "Put it flat, front away.");
        });
        pose("PUT IT FLAT", 0, 0, 0, false);
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
                        fc = openBetaflight(chosenPort, (state, text) -> ui(() -> usb.set(state, text)));
                        if (fc == null) {
                            retryAt = now + 1500;
                            continue;
                        }
                        try {
                            magPresent = GpsSetup.magInStatus(fc.msp(101 /* MSP_STATUS */, null));
                        } catch (IOException e) {
                            magPresent = false;
                        }
                        ui(() -> usb.set(State.OK, "Found."));
                        if (finished) {
                            // the verdict stands (a replug after it): CHECK AGAIN starts over
                        } else if (boardOk && magPresent) {
                            startCompass();
                        } else {
                            restartSteps();
                        }
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
                        ui(() -> usb.set(State.PENDING, "Betaflight restarts..."));
                        continue;
                    }
                    if (skipCompass) {
                        skipCompass = false;
                        step = Step.DONE;
                        ui(() -> {
                            compassRow.set(State.INFO, "Skipped.");
                            done.setVisible(false);
                            skip.setVisible(false);
                        });
                        passed("BOARD RIGHT");
                    }
                    Orientation.Attitude a = Orientation.parse(fc.msp(Orientation.MSP_ATTITUDE, null));
                    if (a != null) {
                        ui(() -> reading.setText("Betaflight: " + Orientation.describe(a.rollDeg(), a.pitchDeg())
                                + (magPresent ? ", heading " + a.headingDeg() + "°" : "")));
                        if (sampleWanted) {
                            sampleWanted = false;
                            sample(fc);
                            if (step == Step.DONE && !compassRightNow()) {
                                if (writeCompass(fc)) {
                                    fc = null; // Betaflight restarts
                                    retryAt = System.currentTimeMillis() + RESTART_MS;
                                    ui(() -> usb.set(State.PENDING, "Betaflight restarts..."));
                                    continue;
                                }
                            }
                        } else {
                            advance(a, System.currentTimeMillis());
                        }
                    }
                    Thread.sleep(33);
                } catch (InterruptedException e) {
                    break;
                } catch (IOException | RuntimeException e) {
                    if (fc != null) fc.close();
                    fc = null;
                    retryAt = System.currentTimeMillis() + 1500;
                    String msg = e.getMessage();
                    ui(() -> usb.set(State.BAD, "Lost: " + msg));
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
                        flat.set(State.BAD, "Seen on its back.");
                        result.set(State.BAD, "Board upside down. <b>Fix it</b> sets roll +180°.");
                        fix.setVisible(true);
                    });
                    pose("UPSIDE DOWN TO BETAFLIGHT", 0, 0, 0, false);
                    return;
                }
                step = Step.NOSE_UP;
                ui(() -> {
                    flat.set(State.OK, "Flat.");
                    noseRow.set(State.PENDING, "Lift the nose about 45°.");
                });
                pose("LIFT THE NOSE", 0, -45, 0, false);
            }
            case NOSE_UP -> {
                double[] d = Orientation.delta(level, a);
                if (Orientation.size(d) < Orientation.MOVE_DEG) return;
                noseUp = d;
                step = Step.FLAT_AGAIN;
                ui(() -> {
                    noseRow.set(State.OK, "Done.");
                    rightRow.set(State.PENDING, "Flat again first.");
                });
                pose("FLAT AGAIN", 0, 0, 0, false);
            }
            case FLAT_AGAIN -> {
                if (Orientation.size(Orientation.delta(level, a)) > Orientation.FLAT_DEG) return;
                step = Step.RIGHT_DOWN;
                ui(() -> rightRow.set(State.PENDING, "Lower the right side about 45°."));
                pose("LOWER THE RIGHT SIDE", 45, 0, 0, false);
            }
            case RIGHT_DOWN -> {
                double[] d = Orientation.delta(level, a);
                if (Orientation.size(d) < Orientation.MOVE_DEG) return;
                rightDown = d;
                step = Step.DONE;
                judge();
            }
            default -> { }
        }
    }

    private void judge() {
        int turn = Orientation.boardTurn(noseUp, rightDown);
        String seen = String.format(java.util.Locale.ROOT, "nose up seen as roll %+.0f°, pitch %+.0f°; right down as roll %+.0f°, pitch %+.0f°",
                noseUp[0], noseUp[1], rightDown[0], rightDown[1]);
        main.logLine("Orientation check: " + seen + (turn == 0 ? " - right." : turn > 0 ? " - the board is turned " + turn + "°." : " - no turn explains it."));
        fixYaw = turn > 0 ? turn : -1;
        ui(() -> rightRow.set(State.OK, "Done."));
        if (turn == 0) {
            boardOk = true;
            if (magPresent) {
                startCompass();
            } else {
                ui(() -> compassRow.set(State.INFO, "None found."));
                passed("ALL RIGHT");
            }
        } else if (turn > 0) {
            ui(() -> {
                result.set(State.BAD, "Board turned " + turn + "°: it would flip. <b>Fix it</b> sets yaw +" + turn + "°.");
                fix.setVisible(true);
            });
            pose("WRONG WAY ROUND", 0, 0, 0, false);
        } else {
            ui(() -> result.set(State.BAD, "Not clear. Repeat slowly; if it stays, set board alignment in Betaflight Configurator."));
            pose("NOT CLEAR", 0, 0, 0, false);
        }
    }

    private void passed(String what) {
        finished = true;
        ui(() -> {
            result.set(State.OK, "Correct.");
            if (onPassed != null) onPassed.run();
        });
        pose(what, 0, 0, 0, false);
    }

    // ------------------------------------------------------------------ the compass

    private static final String[] HEADINGS = {"NORTH", "EAST", "SOUTH", "WEST"};

    private void startCompass() {
        step = Step.NORTH;
        java.util.Arrays.fill(mag, null);
        ui(() -> {
            flat.set(State.OK, "Flat.");
            noseRow.set(State.OK, "Done.");
            rightRow.set(State.OK, "Done.");
            compassRow.set(State.PENDING, "Nose north, then <b>Done</b>. A phone compass helps.");
            done.setEnabled(true);
            done.setVisible(true);
            skip.setVisible(true);
        });
        showHeading(0);
    }

    /** The heading asked for: the drone turned that way, north ahead of the pilot. */
    private void showHeading(int i) {
        pose("NOSE " + HEADINGS[i], 0, 0, i * 90, true);
    }

    /** DONE pressed: the compass averaged over a moment, then the next heading (or the verdict after west). */
    private void sample(BetaflightUsb fc) throws IOException, InterruptedException {
        int i = step.ordinal() - Step.NORTH.ordinal();
        if (i < 0 || i > 3) return;
        double[] sum = new double[3];
        int n = 0;
        for (int k = 0; k < MAG_SAMPLES; k++) {
            double[] m = Orientation.parseMag(fc.msp(Orientation.MSP_RAW_IMU, null));
            if (m != null) {
                for (int j = 0; j < 3; j++) sum[j] += m[j];
                n++;
            }
            Thread.sleep(30);
        }
        if (n == 0) throw new IOException("no compass reading");
        for (int j = 0; j < 3; j++) sum[j] /= n;
        mag[i] = sum;
        if (i < 3) {
            step = Step.values()[step.ordinal() + 1];
            int next = i + 1;
            ui(() -> {
                compassRow.set(State.PENDING, "Nose " + HEADINGS[next].toLowerCase(java.util.Locale.ROOT) + ", then <b>Done</b>.");
                done.setEnabled(true);
            });
            showHeading(next);
            return;
        }
        step = Step.DONE;
        ui(() -> {
            done.setVisible(false);
            skip.setVisible(false);
        });
        if (compassRightNow()) {
            main.logLine("Orientation check: compass right.");
            ui(() -> compassRow.set(State.OK, "Right way round."));
            passed("ALL RIGHT");
        } else if (lastWasFix) {
            boardGuess++; // the last fix guessed the board alignment's effect wrong: the next guess
        }
    }

    private boolean compassRightNow() {
        return mag[3] != null && Orientation.compassRight(mag[0], mag[1], mag[2], mag[3]);
    }

    /**
     * The compass is the wrong way round: align_mag set so it is (a backup first), saved; Betaflight restarts and the
     * compass is checked again. @return true when something was written (the port is gone: Betaflight restarts)
     */
    private boolean writeCompass(BetaflightUsb fc) throws IOException {
        if (compassWrites >= MAX_COMPASS_WRITES) {
            giveUp("Still wrong after " + compassWrites + " tries.");
            return false;
        }
        fc.enterCli();
        String version = fc.command("version");
        String current = Orientation.parseAlign(fc.command("get align_mag"));
        if (current == null) {
            fc.exitCli();
            giveUp("Betaflight has no align_mag.");
            return true;
        }
        String next;
        if ("DEFAULT".equals(current)) {
            next = "CW0"; // what DEFAULT is depends on the board: a known one first, then one more round
            lastWasFix = false;
        } else {
            List<int[][]> boards = compassBoards(fc);
            if (boardGuess >= boards.size() || Orientation.alignIndex(current) < 0) {
                fc.exitCli();
                giveUp(Orientation.alignIndex(current) < 0 ? "align_mag is " + current + "." : "No setting fits.");
                return true;
            }
            int fit = Orientation.compassFix(Orientation.alignIndex(current), mag[0], mag[1], mag[2], mag[3], boards.get(boardGuess));
            if (fit < 0) {
                fc.exitCli();
                giveUp("No setting fits: turn the drone slowly, nose straight each way.");
                return true;
            }
            next = Orientation.MAG_ALIGNS[fit];
            lastWasFix = true;
        }
        String diff = fc.command("diff all", 20_000);
        java.nio.file.Path backup = de.droiddrone.pc.setup.CliWrite.usableBackup(diff) ? backup(diff, version) : null;
        if (backup == null) {
            fc.exitCli();
            throw new IOException("Backup failed, nothing written");
        }
        de.droiddrone.pc.setup.CliWrite.Result written = de.droiddrone.pc.setup.CliWrite.run(fc::command,
                List.of("set align_mag = " + next), main::logLine);
        if (!written.complete()) {
            fc.exitCli();
            throw new IOException(written.stopped() + ". Nothing was saved");
        }
        fc.saveAndReboot();
        compassWrites++;
        main.logLine("Betaflight: align_mag " + current + " -> " + next + " (orientation check). Its settings before: " + backup);
        String c = current;
        ui(() -> compassRow.set(State.PENDING, "Set " + c + " -> " + next + ". Again after the restart."));
        return true;
    }

    /**
     * Where the board alignment may come in: Betaflight turns the compass's readings by it too, in a sense not known
     * for sure here - each guess is tried in turn (a wrong one shows in the check after its restart).
     */
    private static List<int[][]> compassBoards(BetaflightUsb fc) throws IOException {
        Integer roll = Orientation.parseSetting(fc.command("get align_board_roll"), "align_board_roll");
        Integer pitch = Orientation.parseSetting(fc.command("get align_board_pitch"), "align_board_pitch");
        Integer yaw = Orientation.parseSetting(fc.command("get align_board_yaw"), "align_board_yaw");
        int r = roll == null ? 0 : roll, p = pitch == null ? 0 : pitch, y = yaw == null ? 0 : yaw;
        List<int[][]> out = new java.util.ArrayList<>();
        if (Math.floorMod(r, 360) == 0 && Math.floorMod(p, 360) == 0 && Math.floorMod(y, 360) == 0) {
            out.add(null); // no board alignment: nothing to guess
            return out;
        }
        int[][] cw = Orientation.boardMatrix(r, p, y, true), ccw = Orientation.boardMatrix(r, p, y, false);
        if (cw != null) out.add(cw);
        if (ccw != null && !java.util.Arrays.deepEquals(cw, ccw)) out.add(ccw);
        out.add(null); // or not applied to the compass at all
        return out;
    }

    private void giveUp(String why) {
        main.logLine("Orientation check: compass not fixed - " + why);
        step = Step.DONE;
        finished = true;
        ui(() -> {
            compassRow.set(State.BAD, why + " Set align_mag in Betaflight Configurator.");
            result.set(State.WARN, "Board right. Compass: by hand.");
            done.setVisible(false);
            skip.setVisible(false);
        });
        pose("COMPASS: BY HAND", 0, 0, 0, false);
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes the alignment found (a backup of the settings first) and saves; Betaflight restarts. All or nothing, as
     * Drone setup (1.0.49): no backup, or the setting refused, leaves the CLI without saving - the board as it was.
     */
    private void writeFix(BetaflightUsb fc) throws IOException {
        boolean roll = fixRoll;
        int turn = fixYaw;
        if (!roll && turn <= 0) return;
        String name = roll ? "align_board_roll" : "align_board_yaw";
        int more = roll ? 180 : turn;
        fc.enterCli();
        String version = fc.command("version");
        String diff = fc.command("diff all", 20_000);
        java.nio.file.Path backup = de.droiddrone.pc.setup.CliWrite.usableBackup(diff) ? backup(diff, version) : null;
        if (backup == null) {
            fc.exitCli();
            throw new IOException("Backup failed, nothing written");
        }
        Integer current = Orientation.parseSetting(fc.command("get " + name), name);
        if (current == null) {
            fc.exitCli();
            throw new IOException("Betaflight doesn't know " + name);
        }
        int next = Orientation.turned(current, more);
        String cmd = "set " + name + " = " + next;
        de.droiddrone.pc.setup.CliWrite.Result written = de.droiddrone.pc.setup.CliWrite.run(fc::command, List.of(cmd), main::logLine);
        if (!written.complete()) {
            fc.exitCli();
            throw new IOException(written.stopped() + ". Nothing was saved");
        }
        fc.saveAndReboot();
        main.logLine("Betaflight: board alignment " + (roll ? "roll" : "yaw") + " " + current + "° -> " + next + "° (orientation check)."
                + " Its settings before: " + backup);
        ui(() -> result.set(State.PENDING, "Set " + (roll ? "roll" : "yaw") + " " + current + "° -> " + next + "°. Again after the restart."));
    }

    private java.nio.file.Path backup(String diff, String version) {
        try {
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            java.nio.file.Path file = de.droiddrone.pc.AppPaths.betaflightBackupsDir().resolve("betaflight-" + stamp + ".txt");
            java.nio.file.Files.writeString(file, "# " + version.lines().findFirst().orElse("").trim() + "\n# saved by DRAUPNE Link before the orientation check changed it. "
                    + "To go back: paste this into Betaflight Configurator's CLI tab, then type save.\n\n" + diff + "\n");
            return file;
        } catch (IOException e) {
            main.logLine("Betaflight backup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * The chosen port, or the first port that answers as Betaflight; null if none, and why (to the state consumer).
     * Shared with the Motors and orientation step's live view.
     */
    static BetaflightUsb openBetaflight(String chosenPort, BiConsumer<State, String> problem) {
        List<BetaflightUsb.Port> ports = BetaflightUsb.findFlightControllers();
        if (chosenPort != null) ports = ports.stream().filter(p -> p.systemName().equals(chosenPort)).toList();
        if (ports.isEmpty()) {
            problem.accept(State.WARN, "Not found. Plug in a USB data cable.");
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
        problem.accept(State.BAD, why);
        return null;
    }
}
