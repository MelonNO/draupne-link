package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.GpsSetup;
import de.droiddrone.pc.setup.Orientation;
import de.droiddrone.pc.setup.SetupBoard;

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
 * set by the program (board alignment, align_mag; a backup first, as Drone setup does) once the pilot has said so -
 * APPLY for the board, SAVE for the compass - and checked again after the restart, as many rounds as it takes. A
 * compass search that ends without a compass shown right puts align_mag back as it was before the search (Q-001).
 * Over this PC's USB, on the bench, props off.
 */
final class OrientationCheckDialog extends JFrame {
    private static final double STILL_DEG = 1.5;
    /** Compass writes at most before the check gives up (DEFAULT -> CW0, then a board alignment guess each). */
    private static final int MAX_COMPASS_WRITES = 4;
    /** A window closed with a compass value still to put back: that long for the board to be there (it may be restarting). */
    private static final long CLOSE_WAIT_MS = 20_000;

    private enum Step { FLAT, NOSE_UP, FLAT_AGAIN, RIGHT_DOWN, NORTH, EAST, SOUTH, WEST, DONE }

    /** What the check asks of the app: the main window in the app; a stand-in in tests, which have none. */
    interface Host {
        /** A saved setting, or null. */
        String setting(String key);

        /** A line for the log; from any thread. */
        void logLine(String text);

        /** Where Betaflight's settings are kept before a write (Documents\DRAUPNE Link\Betaflight backups). */
        java.nio.file.Path backupsDir() throws IOException;
    }

    /**
     * The check's waits; tests shorten them.
     * @param restartMs  after "save" or the CLI's "exit" Betaflight restarts and its USB port is gone for a few seconds
     * @param stillMs    readings this still for this long: lying flat
     * @param magSamples compass readings averaged per heading
     */
    record Timing(long restartMs, long stillMs, int magSamples) {
        static final Timing REAL = new Timing(6000, 1200, 10);
    }

    private final Host main;
    private final Timing timing;
    /** The flight controller this check reads and writes, and no other (F-071): the board Drone setup's first step read. */
    private final SetupBoard setupBoard;
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
    private final JButton fix = Theme.primaryButton("Apply");
    /** The compass search's question (Q-001): the alignment found is saved on SAVE only; KEEP OLD ends the search. */
    private final JButton save = Theme.primaryButton("Save");
    private final JButton keepOld = Theme.secondaryButton("Keep old");
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
    private volatile boolean saveWanted, keepWanted;
    private Thread worker;
    /** COMPASS: Yes chosen in Drone setup (with a GPS): only then is the compass checked (1.0.52, the pilot). */
    private final boolean compassWanted;
    /** Betaflight's board alignment (roll, pitch, yaw degrees) as read when it answered; null unknown. */
    private volatile int[] boardAlign;
    /** What APPLY wrote in this window ("yaw 0° -> 90°"), for the verdict after the restart; null none. */
    private volatile String applied;

    /** Built, not shown: {@link #open} shows it; tests don't. */
    OrientationCheckDialog(Window owner, Host main, SetupBoard board, Runnable onPassed, Timing timing) {
        super("Orientation check - DRAUPNE Link");
        setIconImages(AppIcon.images());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE); // beside Drone setup's guide
        this.main = main;
        this.timing = timing;
        this.setupBoard = board;
        this.onPassed = onPassed;
        this.compassWanted = Boolean.TRUE.equals(GpsSetup.parseYesNo(main.setting(GpsSetup.GPS_KEY)))
                && Boolean.TRUE.equals(GpsSetup.parseYesNo(main.setting(GpsSetup.COMPASS_KEY)));
        compassRow.setVisible(compassWanted);
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
        save.setVisible(false);
        save.setToolTipText("Write this compass alignment to Betaflight (backed up first) and check again");
        save.addActionListener(e -> {
            saveWanted = true;
            save.setEnabled(false);
            keepOld.setEnabled(false);
        });
        keepOld.setVisible(false);
        keepOld.setToolTipText("Write nothing more: the compass alignment as it was before this check");
        keepOld.addActionListener(e -> {
            keepWanted = true;
            save.setEnabled(false);
            keepOld.setEnabled(false);
        });
        again.addActionListener(e -> restartWanted = true);
        JButton close = Theme.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        for (JButton b : new JButton[]{skip, done, keepOld, save, fix, again, close}) buttons.add(b);
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
        atWork.incrementAndGet(); // until that thread has let go of the USB port
        worker.start();
    }

    private static OrientationCheckDialog open;
    /** Windows whose thread still has the USB port: open ones, and one just closed that puts the compass's old value back. */
    private static final java.util.concurrent.atomic.AtomicInteger atWork = new java.util.concurrent.atomic.AtomicInteger();

    /** Open now, or closed and still putting the compass's old value back: it has (or may have) the flight controller's USB port. */
    static boolean isOpen() {
        return open != null && open.isDisplayable() || atWork.get() > 0;
    }

    /**
     * @param board    the flight controller Drone setup's first step read (SetupBoard: it alone is read and written),
     *                 or null: then only a board that is alone on USB
     * @param onPassed run (Swing thread) when Betaflight saw every move the right way, or null
     * @param onClosed run once the window is closed and has let go of the USB port, with a line to show when
     *                 closing put the compass's old value back (or could not), else null; or null
     */
    static void open(Window owner, MainWindow main, SetupBoard board, Runnable onPassed, java.util.function.Consumer<String> onClosed) {
        // open for another board: closed, never brought forward as if it were this one's
        if (open != null && open.isDisplayable() && !java.util.Objects.equals(open.setupBoard, board)) open.dispose();
        if (open == null || !open.isDisplayable()) {
            open = new OrientationCheckDialog(owner, host(main), board, onPassed, Timing.REAL);
            open.setVisible(true);
        } else {
            if ((open.getExtendedState() & ICONIFIED) != 0) open.setExtendedState(open.getExtendedState() & ~ICONIFIED);
            open.toFront();
        }
        if (onClosed != null) open.whenClosed(onClosed);
    }

    /** The main window as the check's host. */
    private static Host host(MainWindow main) {
        return new Host() {
            @Override
            public String setting(String key) {
                return main.config().getString(key);
            }

            @Override
            public void logLine(String text) {
                main.logLine(text);
            }

            @Override
            public java.nio.file.Path backupsDir() throws IOException {
                return de.droiddrone.pc.AppPaths.betaflightBackupsDir();
            }
        };
    }

    /** Swing thread: who is told once the window is closed and its thread has let go of the USB port. */
    private final List<java.util.function.Consumer<String>> closedListeners = new java.util.ArrayList<>();

    /**
     * Runs this (Swing thread) once the window is closed and has let go of the USB port - after the compass's old
     * value was put back, when a search was closed half way: then with the line that says so, else with null.
     */
    void whenClosed(java.util.function.Consumer<String> closed) {
        closedListeners.add(closed);
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

    // the compass search (Q-001): nothing is written before SAVE, and a search that ends without a compass shown
    // right puts align_mag back as it was before the search wrote anything
    /** align_mag as Betaflight has it, as far as this window knows: read after the first wrong round, then what SAVE wrote; null not read. */
    private String magNow;
    /** align_mag before this search's first write: what a search that fails, or is left half way, puts back. */
    private String magBefore;
    /** A value of this search is saved in Betaflight and no round has shown it right yet: the old one is owed. */
    private boolean magUnproven;
    /** Where the board alignment may come into the compass's readings, read with magNow (see compassBoards). */
    private List<int[][]> magBoards;
    /** The alignment found and asked about (SAVE / KEEP OLD); null: no question open. */
    private volatile String magFound;
    /** The alignment asked about is a fix (not DEFAULT -> CW0, the known one to measure from). */
    private boolean foundIsFix;
    /** How a search ends without a compass shown right. */
    private enum End { FAILED, KEPT, SKIPPED, AGAIN }
    /** The search is ending: the old value goes back first (when one is owed), then the verdict. */
    private End ending;
    private String endingWhy;
    /** "Put back: CW0." once the old value is back; why it could not be put back. For the verdict's line. */
    private String putBackSaid;
    private int putBackTries;

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
        if (!magUnproven) { // read anew by the next search: the old value is back, or nothing was written
            magNow = magBefore = null;
            magBoards = null;
        }
        magFound = null;
        putBackSaid = null;
        putBackTries = 0;
        ui(() -> {
            resetRows();
            done.setVisible(false);
            skip.setVisible(false);
            save.setVisible(false);
            keepOld.setVisible(false);
            flat.set(State.PENDING, "Put it flat, front away.");
        });
        pose("PUT IT FLAT", 0, 0, 0, false);
    }

    private void run() {
        BetaflightUsb fc = null;
        long retryAt = 0;
        String closedNote = null;
        try {
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    if (fc == null) {
                        if (now < retryAt) {
                            Thread.sleep(200);
                            continue;
                        }
                        fc = openBetaflight(setupBoard, SetupBoard.BACK_AND_READ_AGAIN, (state, text) -> ui(() -> usb.set(state, text)));
                        if (fc == null) {
                            retryAt = now + 1500;
                            continue;
                        }
                        try {
                            magPresent = compassWanted && GpsSetup.magInStatus(fc.msp(101 /* MSP_STATUS */, null));
                        } catch (IOException e) {
                            magPresent = false;
                        }
                        try {
                            boardAlign = Orientation.parseBoardAlignment(fc.msp(Orientation.MSP_BOARD_ALIGNMENT_CONFIG, null));
                        } catch (IOException e) {
                            boardAlign = null;
                        }
                        ui(() -> usb.set(State.OK, "Found."));
                        if (finished || ending != null || magFound != null) {
                            // the verdict stands (a replug after it): CHECK AGAIN starts over. And the compass
                            // search's question, or its end, waits for this board: nothing starts anew
                        } else if (boardOk && magPresent) {
                            startCompass();
                        } else if (magUnproven) {
                            end(End.AGAIN, null); // no compass seen now: the search can't go on - the old value back, then from the start
                        } else {
                            restartSteps();
                        }
                    }
                    if (restartWanted) {
                        restartWanted = false;
                        end(End.AGAIN, null);
                    }
                    if (fixWanted) {
                        fixWanted = false;
                        writeFix(fc);
                        fc = null; // Betaflight restarts
                        retryAt = System.currentTimeMillis() + timing.restartMs();
                        ui(() -> usb.set(State.PENDING, "Betaflight restarts..."));
                        continue;
                    }
                    if (skipCompass) {
                        skipCompass = false;
                        end(End.SKIPPED, null);
                    }
                    if (keepWanted) {
                        keepWanted = false;
                        end(End.KEPT, null);
                    }
                    if (ending != null) {
                        // a value of this search is saved and no round has shown it right: the old one back first
                        if (magUnproven && putBack(fc)) {
                            fc = null; // Betaflight restarts
                            retryAt = System.currentTimeMillis() + timing.restartMs();
                            ui(() -> usb.set(State.PENDING, "Betaflight restarts..."));
                            continue;
                        }
                        ended();
                    }
                    String found = magFound;
                    if (saveWanted && found != null) {
                        saveWanted = false;
                        try {
                            writeCompass(fc, found);
                        } catch (IOException | RuntimeException e) { // nothing saved: the question stands
                            ui(() -> {
                                save.setEnabled(true);
                                keepOld.setEnabled(true);
                            });
                            throw e;
                        }
                        fc = null; // Betaflight restarts
                        retryAt = System.currentTimeMillis() + timing.restartMs();
                        ui(() -> usb.set(State.PENDING, "Betaflight restarts..."));
                        continue;
                    }
                    saveWanted = false;
                    Orientation.Attitude a = Orientation.parse(fc.msp(Orientation.MSP_ATTITUDE, null));
                    if (a != null) {
                        ui(() -> reading.setText("Betaflight: " + Orientation.describe(a.rollDeg(), a.pitchDeg())
                                + (magPresent ? ", heading " + a.headingDeg() + "°" : "")));
                        if (sampleWanted) {
                            sampleWanted = false;
                            sample(fc);
                            if (step == Step.DONE && !compassRightNow() && findCompass(fc)) {
                                fc = null; // the CLI was read and left: Betaflight restarts
                                retryAt = System.currentTimeMillis() + timing.restartMs();
                                ui(() -> usb.set(State.PENDING, "Betaflight restarts..."));
                                continue;
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
            // closed half way through a compass search: the old value back before the port is let go of
            if (magUnproven) closedNote = putBackAtClose(fc);
        } finally {
            if (fc != null) fc.close();
            atWork.decrementAndGet();
            String note = closedNote;
            ui(() -> {
                for (java.util.function.Consumer<String> closed : closedListeners) closed.accept(note);
            });
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
                if (now - stillSince < timing.stillMs()) return;
                level = a;
                if (Orientation.upsideDown(a)) {
                    step = Step.DONE;
                    fixRoll = true;
                    main.logLine("Orientation check: Betaflight sees the drone on its back while it lies flat.");
                    ui(() -> {
                        flat.set(State.BAD, "Seen on its back.");
                        result.set(State.BAD, "Difference: seen on its back. " + suggestion("roll", 0, 180) + " <b>Apply</b>?");
                        fix.setVisible(true);
                    });
                    pose("UPSIDE DOWN TO BETAFLIGHT", 0, 0, 0, false);
                    return;
                }
                step = Step.NOSE_UP;
                ui(() -> {
                    flat.set(State.OK, "Flat.");
                    noseRow.set(State.PENDING, "Nose straight up (90°).");
                });
                pose("NOSE STRAIGHT UP", 0, -90, 0, false);
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
                ui(() -> rightRow.set(State.PENDING, "Right side straight down (90°)."));
                pose("RIGHT SIDE DOWN", 90, 0, 0, false);
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
                if (compassWanted) ui(() -> compassRow.set(State.INFO, "None found."));
                passed("ALL RIGHT");
            }
        } else if (turn > 0) {
            ui(() -> {
                result.set(State.BAD, "Difference: board turned " + turn + "° on the frame, it would flip. "
                        + suggestion("yaw", 2, turn) + " <b>Apply</b>?");
                fix.setVisible(true);
            });
            pose("WRONG WAY ROUND", 0, 0, 0, false);
        } else {
            ui(() -> result.set(State.BAD, "Not clear. Repeat slowly; if it stays, set board alignment in Betaflight Configurator."));
            pose("NOT CLEAR", 0, 0, 0, false);
        }
    }

    /** "Suggested: board alignment yaw 0° -> 90°." from the alignment read (index 0 roll, 2 yaw); the turn alone when unknown. */
    private String suggestion(String axis, int index, int more) {
        int[] a = boardAlign;
        if (a == null) return "Suggested: board alignment " + axis + " +" + more + "°.";
        return "Suggested: board alignment " + axis + " " + a[index] + "° -> " + Orientation.turned(a[index], more) + "°.";
    }

    private void passed(String what) {
        finished = true;
        String fixed = applied;
        ui(() -> {
            // the window stays open with the verdict (1.0.52, the pilot): CLOSE when read
            result.set(State.OK, fixed != null ? "Fixed: " + fixed + ". No difference now." : "No difference. Correct.");
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
        for (int k = 0; k < timing.magSamples(); k++) {
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
            String saved = magUnproven ? " Saved: " + magNow + " (was " + magBefore + ")." : "";
            magUnproven = false; // what SAVE wrote is shown right: it stays
            main.logLine("Orientation check: compass right." + saved);
            ui(() -> compassRow.set(State.OK, "Right way round." + saved));
            passed("ALL RIGHT");
        } else if (lastWasFix) {
            boardGuess++; // the last fix guessed the board alignment's effect wrong: the next guess
        }
    }

    private boolean compassRightNow() {
        return mag[3] != null && Orientation.compassRight(mag[0], mag[1], mag[2], mag[3]);
    }

    /**
     * A round read the compass the wrong way round: the alignment that would make it right is shown and asked about -
     * SAVE writes it; nothing is written before that (Q-001: it was written and saved here, unasked, try after try).
     * The first time, what Betaflight has is read over the CLI: that value is what a search that fails, or is left
     * half way, puts back. @return true when the CLI was used (it is left without a change: Betaflight restarts)
     */
    private boolean findCompass(BetaflightUsb fc) throws IOException {
        if (compassWrites >= MAX_COMPASS_WRITES) {
            end(End.FAILED, "Still wrong after " + compassWrites + " tries.");
            return false;
        }
        boolean read = false;
        if (magNow == null) {
            fc.enterCli();
            String there = Orientation.parseAlign(fc.command("get align_mag"));
            List<int[][]> boards = there == null ? null : compassBoards(fc);
            fc.exitCli();
            read = true;
            if (there == null) {
                end(End.FAILED, "Betaflight has no align_mag.");
                return true;
            }
            magNow = magBefore = there;
            magBoards = boards;
            main.logLine("Orientation check: the compass reads the wrong way round; align_mag is " + there + ".");
        }
        String current = magNow, next = null;
        boolean isFix = !"DEFAULT".equals(current);
        if (!isFix) {
            next = "CW0"; // what DEFAULT is depends on the board: a known one first, then one more round
        } else {
            int index = Orientation.alignIndex(current);
            if (index < 0) {
                end(End.FAILED, "align_mag is " + current + ".");
                return read;
            }
            while (next == null) {
                if (boardGuess >= magBoards.size()) {
                    end(End.FAILED, "No setting fits.");
                    return read;
                }
                int fit = Orientation.compassFix(index, mag[0], mag[1], mag[2], mag[3], magBoards.get(boardGuess));
                if (fit < 0) {
                    end(End.FAILED, "No setting fits: turn the drone slowly, nose straight each way.");
                    return read;
                }
                if (fit == index) boardGuess++; // this guess of the board alignment's part says "right as it is": not it
                else next = Orientation.MAG_ALIGNS[fit];
            }
        }
        magFound = next;
        foundIsFix = isFix;
        String n = next;
        main.logLine("Orientation check: align_mag " + n + (isFix ? " found" : " first, to measure from") + " (now " + current + ") - asked, nothing written.");
        ui(() -> {
            compassRow.set(State.WARN, isFix ? "<b>" + n + "</b> found (now " + current + "). <b>Save</b>?"
                    : "Now DEFAULT: <b>CW0</b> first, to measure from. <b>Save</b>?");
            save.setEnabled(true);
            keepOld.setEnabled(true);
            save.setVisible(true);
            keepOld.setVisible(true);
        });
        pose(isFix ? "COMPASS: " + n + " FOUND" : "COMPASS: CW0 FIRST", 0, 0, 0, false);
        return read;
    }

    /**
     * SAVE: the alignment found is written (a backup first) and saved; Betaflight restarts and the compass is checked
     * again. All or nothing, as Drone setup: no backup, or the setting refused, leaves the CLI without saving.
     */
    private void writeCompass(BetaflightUsb fc, String next) throws IOException {
        String current = magNow;
        fc.enterCli();
        String version = fc.command("version");
        String there = Orientation.parseAlign(fc.command("get align_mag"));
        if (!current.equals(there)) { // not what was read: changed elsewhere meanwhile - nothing is written over it
            fc.exitCli();
            end(End.FAILED, "align_mag is " + there + " now, not " + current + ": nothing saved.");
            return;
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
        lastWasFix = foundIsFix;
        magNow = next;
        magUnproven = true;
        magFound = null;
        main.logLine("Betaflight: align_mag " + current + " -> " + next + " (orientation check). Its settings before: " + backup);
        ui(() -> {
            save.setVisible(false);
            keepOld.setVisible(false);
            compassRow.set(State.PENDING, "Set " + current + " -> " + next + ". Again after the restart.");
        });
    }

    /**
     * align_mag back as it was before this search's first write, saved: Betaflight restarts. The value alone - the
     * backup made before the first write has everything else. A set that Betaflight refuses is said, not tried again.
     * @return true when the CLI was used (Betaflight restarts)
     */
    private boolean putBack(BetaflightUsb fc) throws IOException {
        String now = magNow, before = magBefore;
        if (++putBackTries > 3) { // the board went away in the middle of it every time
            notPutBack(before, now, "the flight controller was lost each time");
            return false;
        }
        if (!fc.inCli()) fc.enterCli();
        de.droiddrone.pc.setup.CliWrite.Result written = de.droiddrone.pc.setup.CliWrite.run(fc::command,
                List.of("set align_mag = " + before), main::logLine);
        if (!written.complete()) {
            fc.exitCli(); // nothing saved
            notPutBack(before, now, written.stopped());
            return true;
        }
        fc.saveAndReboot();
        magNow = before;
        magUnproven = false;
        putBackSaid = "Put back: " + before + ".";
        main.logLine("Betaflight: align_mag " + now + " -> " + before + " put back (orientation check: its compass search ended without a compass shown right).");
        return true;
    }

    private void notPutBack(String before, String now, String why) {
        magUnproven = false; // not tried again
        putBackSaid = "Could not put back " + before + ": align_mag is " + now + ".";
        main.logLine("Orientation check: align_mag could NOT be put back to " + before + " (" + why + "). It is " + now + ".");
    }

    /**
     * The window was closed with a value of the search saved and not shown right: the old one goes back before the
     * port is let go of. The board may be restarting just now, so it is waited for.
     * @return the line for whoever opened the check
     */
    private String putBackAtClose(BetaflightUsb open) {
        Thread.interrupted(); // closing interrupted this thread: it still has to wait, and to write
        String before = magBefore, now = magNow;
        long until = System.currentTimeMillis() + CLOSE_WAIT_MS;
        BetaflightUsb fc = open, own = null;
        putBackTries = 0;
        try {
            while (magUnproven && System.currentTimeMillis() < until) {
                try {
                    if (fc == null) fc = own = SetupBoard.open(setupBoard);
                    putBack(fc);
                } catch (IOException e) {
                    if (fc != null) fc.close();
                    fc = own = null;
                    Thread.sleep(500);
                }
            }
        } catch (InterruptedException e) {
            // the app is closing
        } finally {
            if (own != null) own.close();
        }
        if (magUnproven) notPutBack(before, now, "the flight controller was not there");
        return before.equals(magNow) ? "Compass put back: " + before + "." : "Compass NOT put back: align_mag is " + now + ", was " + before + ".";
    }

    /** The compass search ends without a compass shown right (or the whole check starts over); the loop does the rest. */
    private void end(End how, String why) {
        ending = how;
        endingWhy = why;
        magFound = null; // the question is over
        ui(() -> {
            save.setVisible(false);
            keepOld.setVisible(false);
        });
    }

    /** The verdict of a search that ended without a compass shown right; the old value is back by now, when one was owed. */
    private void ended() {
        End how = ending;
        String why = endingWhy, back = putBackSaid;
        boolean backFailed = back != null && !back.startsWith("Put back");
        ending = null;
        endingWhy = null;
        putBackSaid = null;
        switch (how) {
            case AGAIN -> restartSteps();
            case SKIPPED -> {
                step = Step.DONE;
                ui(() -> {
                    compassRow.set(backFailed ? State.BAD : State.INFO, "Skipped." + (back == null ? "" : " " + back));
                    done.setVisible(false);
                    skip.setVisible(false);
                });
                passed("BOARD RIGHT");
            }
            case KEPT -> {
                String kept = back != null ? back : "Kept: " + magNow + ".";
                main.logLine("Orientation check: compass left as it was - " + kept);
                byHand(backFailed ? State.BAD : State.WARN, kept + (backFailed ? " Set it in Betaflight Configurator." : " Still the wrong way round."));
            }
            default -> {
                main.logLine("Orientation check: compass not fixed - " + why + (back == null ? "" : " " + back));
                byHand(State.BAD, why + (back == null ? "" : " " + back) + " Set align_mag in Betaflight Configurator.");
            }
        }
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

    /** The check ends with the board right and the compass left to the pilot. */
    private void byHand(State state, String compassLine) {
        step = Step.DONE;
        finished = true;
        ui(() -> {
            compassRow.set(state, compassLine);
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
        applied = (roll ? "roll " : "yaw ") + current + "° -> " + next + "°";
        ui(() -> result.set(State.PENDING, "Set " + (roll ? "roll" : "yaw") + " " + current + "° -> " + next + "°. Again after the restart."));
    }

    private java.nio.file.Path backup(String diff, String version) {
        try {
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            java.nio.file.Path file = main.backupsDir().resolve("betaflight-" + stamp + ".txt");
            java.nio.file.Files.writeString(file, "# " + version.lines().findFirst().orElse("").trim() + "\n# saved by DRAUPNE Link before the orientation check changed it. "
                    + "To go back: paste this into Betaflight Configurator's CLI tab, then type save.\n\n" + diff + "\n");
            return file;
        } catch (IOException e) {
            main.logLine("Betaflight backup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * The flight controller Drone setup's first step read, opened - that board and no other (SetupBoard.open, as the
     * motor test has it; F-071: on Automatic it was the first port that answered as Betaflight, whatever board the
     * page showed); null if it is not there, and why (to the state consumer). Shared with the Motors and
     * orientation step's live view and with the compass calibration.
     * @param readAgain how the pilot has another board read in from where the line is shown
     *                  (SetupBoard.READ_AGAIN or BACK_AND_READ_AGAIN)
     */
    static BetaflightUsb openBetaflight(SetupBoard target, String readAgain, BiConsumer<State, String> problem) {
        try {
            return SetupBoard.open(target);
        } catch (SetupBoard.NotThere e) {
            problem.accept(e.waiting() ? State.WARN : State.BAD, e.html(readAgain));
            return null;
        }
    }
}
