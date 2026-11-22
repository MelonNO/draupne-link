package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.MotorTest;
import de.droiddrone.pc.setup.SetupBoard;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * SETTINGS > Motor test, like Betaflight Configurator's Motors tab: the drone from above with each motor's number and
 * the way it must turn, and below it the motor test - a risk checkbox, a slider per motor and a master slider. For
 * checking, props off, that each motor sits where its number says and turns the right way, and then that the props go
 * on the right way round. Runs over this PC's USB (the drone on the bench), never over the 4G link.
 * The motors stop when the box is unticked, on SPACE or STOP ALL, when this window loses focus or closes, and when
 * the USB link fails; sliders go to 10 % at most (MotorTest.MAX). The thread that sends the values has limits of its
 * own, which need no Swing event (F-061, {@link MotorLimits}): a click's 3 s, 20 s of any motor running, and a
 * Swing thread that has not answered for a second. A cable pulled while a motor turns is the one thing nothing here
 * can stop - Betaflight keeps the last value - and the warning over the sliders says so.
 */
final class MotorTestDialog extends JFrame {
    private static final int SLIDER_H = 150;
    /** The pilot's choice of motor direction for the picture; props out unless they pick props in. */
    static final String PROPS_OUT_KEY = "motorTest.propsOut";

    /** What the motor test asks of the app: the main window in the app; a stand-in in tests, which have none. */
    interface Host {
        /** A saved setting, or null. */
        String setting(String key);

        void save(java.util.Map<String, String> settings);

        /** A line for the log; from any thread. */
        void logLine(String text);
    }

    private final Host main;
    /**
     * The flight controller this window is for, and no other (F-060): the board Drone setup's first step read, as
     * every USB tool of Drone setup has it (SetupBoard); null: opened for none, then only a board that is alone on USB.
     */
    private final SetupBoard target;
    private final Diagram diagram = new Diagram();
    private final JCheckBox understood = new JCheckBox("<html><b>Propellers removed, I understand the risk</b> - enable motors.</html>");
    private final JButton stopAll = Theme.primaryButton("Stop all  (space)");
    private final JPanel sliderRow = new JPanel(new GridLayout(1, 0, 10, 0));
    final List<Slider> sliders = new java.util.ArrayList<>();
    private final Slider master = new Slider(0);
    private final CheckRow usb = new CheckRow("Flight controller on USB");
    private final CheckRow mixer = new CheckRow("Motor layout");
    private final CheckRow battery = new CheckRow("Battery");

    /**
     * What the sliders ask for, all STOP unless the box is ticked, and what the motor thread may send of it by its
     * own clock (F-061): the 20 s auto-stop, a click's 3 s, and all STOP when the Swing thread no longer answers.
     */
    private final MotorLimits limits;
    /** The battery's voltage as the flight controller reports it; -1 before the first reading. */
    private double volts = -1;
    /** A battery is in (Betaflight counts its cells, or the voltage is above USB power's): else nothing can spin. */
    private boolean batteryIn;
    /** Ten times a second the Swing thread tells the motor thread it is there, and shows what that thread stopped. */
    private final Timer beat = new Timer(MotorLimits.BEAT_MS, e -> beat());
    /** Under the sliders: why the motors stopped by themselves. */
    private final WrappingText note = new WrappingText("", Theme.font(Font.PLAIN, 13f), Theme.WARN);
    /** A click on a motor in the picture: it runs at PULSE for the limits' time, then stops; one timer per motor. */
    private static final int PULSE = MotorTest.STOP + (MotorTest.MAX - MotorTest.STOP) * 2 / 10; // 2 % (the pilot's, was 8 %): turns, slowly
    private final java.util.Map<Integer, Timer> pulses = new java.util.HashMap<>();
    /** The risk line shakes when the motors are asked for without the box ticked. */
    private Timer shake;
    private volatile boolean running = true;
    private volatile MotorTest.Info info;
    /** The board said armed at its last reading (Swing thread): props, reorder and directions are grey then (F-077). */
    private boolean armedNow;
    private boolean propsOut;
    /** PROPS IN / OUT changed: the switch's highlight slides over and the arrows sweep round the new way. */
    private static final long FLIP_MS = 340;
    private long flippedAt;
    private final DirectionSwitch directionSwitch = new DirectionSwitch();
    private final Timer flip = new Timer(15, e -> {
        directionSwitch.repaint();
        diagram.repaint();
        if (flipProgress() >= 1) ((Timer) e.getSource()).stop();
    });
    private Thread worker;
    /** Settings to change on the flight controller, run by the motor thread (it owns the USB port) between its sends. */
    private interface Job {
        void run(MotorTest test) throws IOException;
    }
    /** A change and the flight controller it was asked of (as it was open then): it is never made on another (F-064). */
    private record Asked(MotorTest.Info board, Job job) {
    }
    private final java.util.concurrent.LinkedBlockingQueue<Asked> jobs = new java.util.concurrent.LinkedBlockingQueue<>();
    /** Betaflight saves and restarts after a settings change: its port is gone for a few seconds, not lost. */
    private static final long RESTART_MS = 6000;
    private volatile long restartUntil;
    /** REORDER MOTORS: 0 = off; k = motor k spins and the pilot clicks the place where it is. */
    private int reorderStep;
    private MotorTest.Info reorderBoard;
    private int[] reorderCurrent;
    private int[] reorderPlaces;
    private final JButton reorderButton = Theme.secondaryButton("Reorder motors");
    /** SPIN DIRECTION: a choice per motor (As is / Normal / Reversed), sent to its ESC as DShot commands. */
    private final JPanel directionRow = new JPanel();
    /** The tool columns beside the picture, the same width each side so the drone stays in the middle. */
    private static final int TOOL_W = 210;
    private final List<javax.swing.JComboBox<String>> directionChoices = new java.util.ArrayList<>();
    private boolean revertingDirection;
    /** PROPS IN / OUT, for a test. */
    JComponent propsSwitch() {
        return directionSwitch;
    }

    /** The question before a direction is saved in an ESC (F-064) or props in / out in Betaflight: yes or no, in this window. Tests answer it themselves. */
    java.util.function.BiPredicate<String, String> ask = (question, title) ->
            Dialogs.confirm(this, question, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;

    /**
     * Built, not shown: {@link #open} shows it; tests don't.
     * @param board the flight controller it is for, or null (then only a board that is alone on USB)
     */
    MotorTestDialog(Window owner, Host main, MotorLimits limits, SetupBoard board) {
        super("Motor test - DRAUPNE Link"); // a window of its own: Windows' minimise, maximise and close
        this.limits = limits;
        this.target = board;
        setIconImages(AppIcon.images());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // opened from a setup guide (Drone setup > Check now), which blocks the app's other windows: not this one
        setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        this.main = main;
        propsOut = !"false".equalsIgnoreCase(main.setting(PROPS_OUT_KEY));
        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        setContentPane(root);

        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(Theme.label("MOTORS", Theme.headingFont(26f), Theme.TEXT), BorderLayout.NORTH);
        head.add(Theme.label("Check each motor's place and direction, then the props. "
                + "Over USB, on the bench.", Theme.font(Font.PLAIN, 13f), Theme.MUTED), BorderLayout.SOUTH);
        root.add(head, BorderLayout.NORTH);
        root.add(sideColumn(), BorderLayout.WEST);

        JPanel centre = new JPanel(new BorderLayout(0, 14));
        centre.setOpaque(false);
        JPanel diagramCard = Theme.card(new BorderLayout(14, 0));
        // left of the drone: what it is and how it is built (props in / out, the motor order); right: each motor's direction
        JPanel leftTools = toolColumn();
        leftTools.add(left(Theme.sectionTitle("Seen from above")));
        leftTools.add(left(gap(14)));
        leftTools.add(left(Theme.label("PROPS", Theme.headingFont(12f), Theme.MUTED)));
        leftTools.add(left(gap(4)));
        JPanel switchHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)) { // its own height, not the column's
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        switchHolder.setOpaque(false);
        switchHolder.add(directionSwitch);
        switchHolder.setAlignmentX(LEFT_ALIGNMENT);
        leftTools.add(switchHolder);
        leftTools.add(left(gap(18)));
        leftTools.add(left(Theme.label("MOTOR ORDER", Theme.headingFont(12f), Theme.MUTED)));
        leftTools.add(left(gap(4)));
        reorderButton.setToolTipText("Spin each motor, click where it is (Quad X, box ticked, battery in, props off)");
        reorderButton.setFocusable(false); // SPACE stops the motors
        reorderButton.setEnabled(false);
        reorderButton.addActionListener(e -> reorderPressed());
        leftTools.add(left(reorderButton));
        diagramCard.add(leftTools, BorderLayout.WEST);
        diagramCard.add(diagram, BorderLayout.CENTER);
        directionRow.setOpaque(false);
        directionRow.setLayout(new BoxLayout(directionRow, BoxLayout.Y_AXIS));
        directionRow.setPreferredSize(new Dimension(TOOL_W, 10));
        diagramCard.add(directionRow, BorderLayout.EAST);
        centre.add(diagramCard, BorderLayout.CENTER);
        centre.add(testCard(), BorderLayout.SOUTH);
        root.add(centre, BorderLayout.CENTER);

        JButton close = Theme.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);

        getRootPane().registerKeyboardAction(e -> stopAll(), KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                stopAll(); // looking elsewhere: nothing keeps spinning unseen
            }
        });
        usb.set(State.PENDING, "Searching...");
        mixer.set(State.PENDING, "");
        battery.set(State.PENDING, "");
        showMotors(4, false);
        ScreenFit.apply(this, 900, 600, 1440, 1000); // the drone big, within the screen

        setLocationRelativeTo(owner);
        WindowReveal.onOpen(this);
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(pressWhileWaiting, java.awt.AWTEvent.MOUSE_EVENT_MASK);
        worker = new Thread(this::run, "motor-test");
        worker.setDaemon(true);
        worker.start();
        beat.start();
    }

    /**
     * The Swing thread's word to the motor thread that it is there (without it that thread stops every motor), and
     * what the motor thread stopped by its own clock, shown: the sliders back at 0 and why. After twenty seconds of
     * any motor running a slider goes on; after a window that did not answer the box is unticked too, so nothing
     * clicked while it hung starts a motor now.
     */
    private void beat() {
        MotorLimits.Cut cut = limits.takeCut();
        limits.alive(MotorLimits.now());
        if (cut == null) return;
        if (cut == MotorLimits.Cut.WINDOW_SILENT) understood.setSelected(false);
        stopAll();
        enableSliders();
        if (cut == MotorLimits.Cut.AUTO_STOP) {
            note.setHtml("Auto-stopped after " + limits.autoStopMs / 1000 + " s. Raise a slider to continue.");
            main.logLine("Motor test: auto-stopped after " + limits.autoStopMs / 1000 + " s.");
        } else {
            note.setHtml("Window froze: motors stopped. Tick the box to go on.");
            main.logLine("Motor test: stopped, the window did not answer for " + limits.silentMs / 1000 + " s.");
        }
    }

    /** The one open, if any: opening it again brings that one forward. */
    private static MotorTestDialog open;

    static void open(Window owner, MainWindow main) {
        open(owner, main, null, null);
    }

    /** Open now (it may have the flight controller's USB port). */
    static boolean isOpen() {
        return open != null && open.isDisplayable();
    }

    /**
     * @param board    the flight controller it is for (Drone setup's), or null: then only a board that is alone on USB
     * @param onClosed run once the window is closed (and so stops using the USB port), with whether a motor was
     *                 turned in it; or null
     */
    static void open(Window owner, MainWindow main, SetupBoard board, java.util.function.Consumer<Boolean> onClosed) {
        // open for another board: closed, never brought forward as if it were this one's
        if (open != null && open.isDisplayable() && !java.util.Objects.equals(open.target, board)) open.dispose();
        if (open == null || !open.isDisplayable()) {
            open = new MotorTestDialog(owner, host(main), new MotorLimits(MotorLimits.now()), board);
            open.setVisible(true);
        } else {
            if ((open.getExtendedState() & ICONIFIED) != 0) open.setExtendedState(open.getExtendedState() & ~ICONIFIED);
            open.toFront();
        }
        if (onClosed != null) open.whenClosed(onClosed);
    }

    /** Runs this once the window is closed (and so has let go of the USB port), with whether a motor was turned in it. */
    void whenClosed(java.util.function.Consumer<Boolean> closed) {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                closed.accept(turned);
            }
        });
    }

    /** A motor was commanded to turn in this window: a value above STOP went to the flight controller. Set by the motor thread. */
    private volatile boolean turned;

    /** The main window as the motor test's host. */
    private static Host host(MainWindow main) {
        return new Host() {
            @Override
            public String setting(String key) {
                return main.config().getString(key);
            }

            @Override
            public void save(java.util.Map<String, String> settings) {
                main.save(settings);
            }

            @Override
            public void logLine(String text) {
                main.logLine(text);
            }
        };
    }

    /**
     * A press anywhere in this window while the flight controller isn't there (1.0.52, the pilot): the waiting line
     * under the drone pulses and shakes, in the warning colour, so it is read.
     */
    private final java.awt.event.AWTEventListener pressWhileWaiting = event -> {
        if (event.getID() != MouseEvent.MOUSE_PRESSED || info != null) return;
        if (event.getSource() instanceof java.awt.Component c && SwingUtilities.getWindowAncestor(c) == this) diagram.nudge();
    };

    @Override
    public void dispose() {
        if (open == this) open = null;
        java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(pressWhileWaiting);
        diagram.nudge.stop();
        flip.stop();
        beat.stop();
        stopAll();
        running = false;
        if (worker != null) worker.interrupt();
        diagram.animation.stop();
        super.dispose();
    }

    // ------------------------------------------------------------------ layout

    private JComponent sideColumn() {
        JPanel column = new JPanel(new BorderLayout(0, 14));
        column.setOpaque(false);
        column.setPreferredSize(new Dimension(330, 10));
        JPanel status = Theme.card(null);
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        status.add(left(Theme.sectionTitle("Flight controller")));
        status.add(left(gap(10)));
        for (CheckRow r : new CheckRow[]{usb, mixer, battery}) {
            status.add(left(r));
            status.add(left(gap(6)));
        }
        column.add(status, BorderLayout.NORTH);

        JPanel steps = Theme.card(new BorderLayout(0, 8));
        steps.add(Theme.sectionTitle("How to check"), BorderLayout.NORTH);
        // scrolls when the status rows above need more room (a long warning)
        javax.swing.JScrollPane stepsScroll = new javax.swing.JScrollPane(new WrappingText("<ol style='margin-left:16px'>"
                + "<li><b>Props off.</b> Battery in, then USB.</li>"
                + "<li>Set <b>props in</b> or <b>props out</b> as built. Tick the box, raise <b>one</b> motor a little.</li>"
                + "<li>It must spin at its number in the picture, the way its arrow points.</li>"
                + "<li><b>Wrong place:</b> <b>Reorder motors</b>. <b>Wrong way:</b> "
                + "<b>Spin direction</b> (DShot), or swap two motor wires.</li>"
                + "<li><b>Props on, battery out:</b> leading edge first in the arrow's direction, "
                + "or it flips on take-off.</li></ol>",
                Theme.font(Font.PLAIN, 13f), Theme.TEXT));
        stepsScroll.setBorder(BorderFactory.createEmptyBorder());
        stepsScroll.setOpaque(false);
        stepsScroll.getViewport().setOpaque(false);
        stepsScroll.setHorizontalScrollBarPolicy(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        steps.add(stepsScroll, BorderLayout.CENTER);
        column.add(steps, BorderLayout.CENTER);
        return column;
    }

    private JComponent testCard() {
        JPanel card = Theme.card(new BorderLayout(0, 10));
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(Theme.sectionTitle("Motor test mode"), BorderLayout.NORTH);
        WrappingText warning = new WrappingText("<b>Motors will spin. Props off.</b> "
                + "If USB comes out while a motor spins, unplug the battery. "
                + "Untick, SPACE, STOP ALL or leaving the window stops them. Max 10 %.", Theme.font(Font.PLAIN, 13f), Theme.WARN);
        warning.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.alpha(Theme.WARN, 140)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        warning.setOpaque(true);
        warning.setBackground(Theme.alpha(Theme.WARN, 18));
        top.add(warning, BorderLayout.CENTER);
        understood.setFont(Theme.font(Font.PLAIN, 14f));
        understood.setForeground(Theme.TEXT);
        understood.setOpaque(false);
        understood.setFocusable(false); // SPACE stops the motors; it must never tick the box
        understood.setEnabled(false);
        understood.addActionListener(e -> {
            if (!understood.isSelected()) stopAll();
            enableSliders();
        });
        stopAll.setBackground(Theme.BAD);
        stopAll.setFocusable(false);
        stopAll.addActionListener(e -> stopAll());
        JPanel tickRow = new JPanel(new BorderLayout(12, 0));
        tickRow.setOpaque(false);
        tickRow.add(understood, BorderLayout.CENTER);
        tickRow.add(stopAll, BorderLayout.EAST);
        top.add(tickRow, BorderLayout.SOUTH);
        card.add(top, BorderLayout.NORTH);

        sliderRow.setOpaque(false);
        JPanel sliders = new JPanel(new BorderLayout(18, 0));
        sliders.setOpaque(false);
        sliders.add(sliderRow, BorderLayout.CENTER);
        sliders.add(master, BorderLayout.EAST);
        card.add(sliders, BorderLayout.CENTER);
        card.add(note, BorderLayout.SOUTH);
        return card;
    }

    private void showMotors(int count, boolean connected) {
        sliderRow.removeAll();
        sliders.clear();
        for (int i = 1; i <= Math.max(1, count); i++) {
            Slider s = new Slider(i);
            sliders.add(s);
            sliderRow.add(s);
        }
        for (int i = count; i < MotorTest.MAX_MOTORS && count <= 4; i++) sliderRow.add(placeholder(i + 1)); // keep the width steady
        understood.setEnabled(connected);
        if (!connected) understood.setSelected(false);
        enableSliders();
        MotorTest.Info i = info;
        reorderButton.setEnabled(connected && reorderAllowed(i));
        showDirections(connected ? i : null);
        sliderRow.revalidate();
        sliderRow.repaint();
    }

    private static JComponent placeholder(int n) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(56, SLIDER_H));
        return p;
    }

    private void enableSliders() {
        boolean on = understood.isSelected() && understood.isEnabled();
        for (Slider s : sliders) s.setEnabled(on);
        master.setEnabled(on);
    }

    // ------------------------------------------------------------------ motors

    /** Everything to STOP, at once; the motor thread sends it on its next round (within 20 ms). */
    private void stopAll() {
        if (reorderStep > 0) {
            reorderStep = 0;
            reorderButton.setText("Reorder motors");
            note.setHtml("Reorder cancelled. Nothing changed.");
        }
        limits.stop();
        for (Slider s : sliders) s.value = MotorTest.STOP;
        master.value = MotorTest.STOP;
        for (Slider s : sliders) s.repaint();
        master.repaint();
        diagram.repaint();
    }

    /**
     * May the motors be asked for? Not without the flight controller, not without the box ticked (the risk line
     * shakes to say so), not without a battery (a pop-up says so: on USB power alone nothing spins).
     */
    private boolean readyToSpin() {
        if (!understood.isEnabled()) return false;
        if (!understood.isSelected()) {
            shakeUnderstood();
            return false;
        }
        if (volts >= 0 && !batteryIn) {
            stopAll();
            Dialogs.message(this, "Plug in a battery: motors can't spin on USB power.\n\nProps off.",
                    "No battery", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        return true;
    }

    /** The risk line wags from side to side for half a second and turns yellow, so the box gets noticed. */
    private void shakeUnderstood() {
        if (shake != null && shake.isRunning()) return;
        java.awt.Point base = understood.getLocation();
        java.awt.Color colour = understood.getForeground();
        long start = System.currentTimeMillis();
        understood.setForeground(Theme.WARN);
        shake = new Timer(16, e -> {
            long t = System.currentTimeMillis() - start;
            if (t >= 520) {
                ((Timer) e.getSource()).stop();
                understood.setLocation(base);
                understood.setForeground(colour);
                return;
            }
            double fade = 1 - t / 520.0;
            understood.setLocation(base.x + (int) Math.round(9 * Math.sin(t / 22.0) * fade), base.y);
        });
        shake.start();
    }

    /**
     * A click on a motor in the picture: it runs for a few seconds at a low value, then stops by itself - on the
     * wire by the motor thread's clock (the limits), on the slider by this timer.
     */
    void pulse(int motor) {
        if (motor < 1 || motor > sliders.size() || !readyToSpin()) return;
        Slider s = sliders.get(motor - 1);
        s.set(PULSE);
        limits.pulse(motor, MotorLimits.now());
        Timer old = pulses.remove(motor);
        if (old != null) old.stop();
        Timer t = new Timer((int) limits.pulseMs, e -> {
            pulses.remove(motor);
            if (s.value == PULSE) s.set(MotorTest.STOP); // unless the pilot moved it meanwhile
        });
        t.setRepeats(false);
        pulses.put(motor, t);
        t.start();
    }

    // ------------------------------------------------------------------ reorder, spin direction

    /** REORDER MOTORS, or CANCEL while it runs. */
    private void reorderPressed() {
        if (reorderStep > 0) {
            stopAll(); // cancels it
            return;
        }
        MotorTest.Info i = info;
        if (i == null || !i.quadX() || i.motorCount() != 4 || !readyToSpin()) return;
        stopAll();
        reorderButton.setEnabled(false);
        note.setHtml("Reading Betaflight's motor order...");
        queue(i, t -> {
            int[] order;
            try {
                order = t.outputOrder();
            } catch (IOException e) {
                ui(() -> {
                    reorderButton.setEnabled(!armedNow);
                    note.setHtml("Reorder needs Betaflight 4.3+: " + e.getMessage());
                });
                return;
            }
            ui(() -> beginReorder(i, order));
        });
    }

    private void beginReorder(MotorTest.Info board, int[] order) {
        reorderButton.setEnabled(!armedNow);
        if (armedNow) {
            note.setHtml(BetaflightUsb.ARMED);
            return;
        }
        if (order.length < 4 || !understood.isSelected()) {
            note.setHtml(order.length < 4 ? "No motor order for four motors." : "Tick the box first.");
            return;
        }
        reorderBoard = board;
        reorderCurrent = order;
        reorderPlaces = new int[4];
        reorderStep = 1;
        reorderButton.setText("Cancel reorder");
        spinReorderMotor();
    }

    /** The motor the wizard is at runs slowly, the others are stopped; the picture asks where it is. */
    private void spinReorderMotor() {
        for (Slider s : sliders) s.value = MotorTest.STOP;
        sliders.get(reorderStep - 1).set(PULSE);
        note.setHtml("<b>Motor " + reorderStep + " of 4 spinning.</b> Click where it is.");
        diagram.repaint();
    }

    /** A click on a place while reordering: that is where the spinning motor sits. */
    private void placeReorder(int place) {
        for (int k = 0; k < reorderStep - 1; k++) {
            if (reorderPlaces[k] == place) {
                note.setHtml("Taken - click where motor " + reorderStep + " is.");
                return;
            }
        }
        reorderPlaces[reorderStep - 1] = place;
        if (reorderStep < 4) {
            reorderStep++;
            spinReorderMotor();
            return;
        }
        int[] current = reorderCurrent;
        int[] places = reorderPlaces.clone();
        reorderStep = 0;
        reorderButton.setText("Reorder motors");
        stopAll();
        int[] next = MotorTest.reordered(current, places);
        if (java.util.Arrays.equals(next, current)) {
            note.setHtml("All motors in place. Nothing to change.");
            return;
        }
        int answer = Dialogs.confirm(this, "Save the new motor order?\nBetaflight restarts; "
                + "then check again.", "Reorder motors", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            note.setHtml("Motor order not changed.");
            return;
        }
        String was = java.util.Arrays.toString(current), now = java.util.Arrays.toString(next);
        queue(reorderBoard, t -> { // the board whose order was read and whose motors were spun
            main.logLine(MotorTest.writeLog("motor order", was, now, "writing"));
            t.setOutputOrderAndRestart(next);
            main.logLine(MotorTest.writeLog("motor order", was, now, "saved, Betaflight restarts"));
        });
    }

    /** SPIN DIRECTION under the picture: a choice per motor while connected with DShot; a line saying why not otherwise. */
    private void showDirections(MotorTest.Info i) {
        directionRow.removeAll();
        directionChoices.clear();
        directionRow.add(left(Theme.label("SPIN DIRECTION", Theme.headingFont(12f), Theme.MUTED)));
        directionRow.add(left(gap(6)));
        if (i == null) {
            directionRow.add(left(new WrappingText("Motor directions, once connected.", Theme.font(Font.PLAIN, 13f), Theme.MUTED)));
        } else if (!i.dshot()) {
            directionRow.add(left(new WrappingText("Needs DShot. Otherwise swap two motor wires.", Theme.font(Font.PLAIN, 13f), Theme.MUTED)));
        } else {
            for (int m = 1; m <= i.motorCount(); m++) {
                int motor = m;
                JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
                line.setOpaque(false);
                javax.swing.JLabel n = Theme.label("MOTOR " + m, Theme.headingFont(13f), Theme.TEXT);
                n.setPreferredSize(new Dimension(70, n.getPreferredSize().height));
                line.add(n);
                javax.swing.JComboBox<String> c = new javax.swing.JComboBox<>(new String[]{"As is", "Normal", "Reversed"});
                c.setPrototypeDisplayValue("Reversed  ");
                c.setToolTipText("Motor " + m + " direction, saved in the ESC (box ticked, battery in, props off)");
                c.setFocusable(false); // SPACE stops the motors
                TacticalCombo.install(c);
                c.addActionListener(e -> directionChosen(motor, c));
                c.setEnabled(!armedNow);
                directionChoices.add(c);
                line.add(c);
                directionRow.add(left(line));
            }
        }
        directionRow.revalidate();
        directionRow.repaint();
    }

    /** A column of tools beside the picture: stacked from the top, TOOL_W wide. */
    private static JPanel toolColumn() {
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setOpaque(false);
        c.setPreferredSize(new Dimension(TOOL_W, 10));
        return c;
    }

    /**
     * A pick in a motor's SPIN DIRECTION list. The ESC keeps it and it can't be read back, so it is saved only with
     * the box "Propellers removed" ticked and after one question that names the motor and the direction (F-064:
     * the pick alone saved it), and only in the flight controller that was open at the pick.
     */
    private void directionChosen(int motor, javax.swing.JComboBox<String> choice) {
        if (revertingDirection || choice.getSelectedIndex() <= 0) return;
        boolean reversed = choice.getSelectedIndex() == 2;
        if (armedNow) {
            asIs(choice);
            note.setHtml(BetaflightUsb.ARMED);
            return;
        }
        MotorTest.Info board = info;
        if (board == null || volts >= 0 && !batteryIn) {
            asIs(choice);
            note.setHtml("Plug in the battery first (props off).");
            return;
        }
        if (!understood.isSelected()) {
            asIs(choice);
            shakeUnderstood();
            note.setHtml("Tick the box first.");
            return;
        }
        stopAll();
        String now = reversed ? "reversed" : "normal";
        if (!ask.test("Set motor " + motor + " to " + now + "?\nSaved in its ESC.", "Spin direction")) {
            asIs(choice);
            note.setHtml("Motor " + motor + " direction not changed.");
            return;
        }
        note.setHtml("Motor " + motor + ": sending " + now + " to its ESC...");
        // an ESC's direction can't be read back over DShot: the one before is what this window last set, if it did
        String was = directionSet.getOrDefault(motor, "as the ESC had it (not readable)");
        queue(board, t -> {
            main.logLine(MotorTest.writeLog("motor " + motor + " spin direction", was, now, "writing"));
            t.setDirection(motor, reversed);
            directionSet.put(motor, now);
            main.logLine(MotorTest.writeLog("motor " + motor + " spin direction", was, now, "saved in its ESC"));
            ui(() -> note.setHtml("Motor " + motor + " set to <b>" + now + "</b>, saved. Spin it to check."));
        });
    }

    /** The list back at "As is": nothing was sent. */
    private void asIs(javax.swing.JComboBox<String> choice) {
        revertingDirection = true;
        choice.setSelectedIndex(0);
        revertingDirection = false;
    }

    /**
     * A change for the motor thread to make between its sends - in this flight controller only. One that is lost or
     * another by the time its turn comes is not changed: the queue is emptied ({@link #dropJobs}), and the motor
     * thread makes no change that was asked of another board than the one it has open.
     */
    private void queue(MotorTest.Info board, Job job) {
        jobs.add(new Asked(board, job));
    }

    /** The motor thread: the flight controller is lost or another one - what still waited is not done (F-064). */
    private void dropJobs() {
        if (jobs.isEmpty()) return;
        jobs.clear();
        notDone();
    }

    private void notDone() {
        ui(() -> note.setHtml("Flight controller lost: nothing saved."));
        main.logLine("Motor test: flight controller lost - what was asked of it was not saved.");
    }

    /** Each motor's spin direction as this window last set it in the ESC (motor number -> "normal" / "reversed"). */
    private final java.util.Map<Integer, String> directionSet = new java.util.concurrent.ConcurrentHashMap<>();

    private void slidersChanged() {
        note.setHtml("");
        int[] v = new int[sliders.size()];
        for (int i = 0; i < v.length; i++) v[i] = sliders.get(i).value;
        limits.ask(understood.isSelected() ? v : new int[0], MotorLimits.now());
        diagram.repaint();
        diagram.animate();
    }

    /** The motor thread: finds the flight controller, sends what the sliders ask for, reads what it does. */
    private void run() {
        MotorTest test = null;
        int[] sent = null;
        long lastSend = 0, lastPoll = 0;
        try {
            while (running) {
                try {
                    if (test == null) {
                        if (System.currentTimeMillis() < restartUntil) { // saved: its port comes back in a few seconds
                            Thread.sleep(300);
                            continue;
                        }
                        try { // the board this window is for - not the first that answers (F-060)
                            test = SetupBoard.open(target, MotorTest::open);
                        } catch (SetupBoard.NotThere e) {
                            String line = e.html(SetupBoard.BACK_AND_READ_AGAIN)
                                    + (e.why() == SetupBoard.NotThere.Why.FAILED ? "<br>Trying again by itself." : "");
                            ui(() -> usb.set(e.waiting() ? State.WARN : State.BAD, line));
                            Thread.sleep(e.why() == SetupBoard.NotThere.Why.NOT_FOUND ? 1500 : 2500);
                            continue;
                        }
                        MotorTest.Info i = test.info();
                        dropJobs(); // whatever still waits was asked of a board before this one
                        info = i;
                        sent = null;
                        ui(() -> connected(i));
                        main.logLine("Motor test: " + i.port() + ", Betaflight " + i.version() + ", " + i.motorCount() + " motors.");
                    }
                    long now = System.currentTimeMillis();
                    Asked asked = jobs.poll();
                    if (asked != null && asked.board() != test.info()) { // asked of another board than the one open now
                        asked = null;
                        notDone();
                    }
                    if (asked != null) {
                        try {
                            asked.job().run(test);
                        } catch (IOException e) {
                            String msg = e.getMessage();
                            ui(() -> note.setHtml(msg));
                            main.logLine("Motor test: " + msg);
                        }
                        if (test.isClosed()) { // saved and restarting
                            test = null;
                            info = null;
                            jobs.clear();
                            restartUntil = System.currentTimeMillis() + RESTART_MS;
                            ui(() -> {
                                showMotors(4, false);
                                usb.set(State.PENDING, "Saved, restarting...");
                            });
                            continue;
                        }
                        sent = null;
                    }
                    // what the sliders ask for, within this thread's own limits: no stop here waits for a Swing event
                    int[] want = limits.allowed(MotorLimits.now());
                    if (!Arrays.equals(want, sent) || now - lastSend >= 250) {
                        test.set(want);
                        sent = want;
                        lastSend = now;
                        for (int v : want) turned |= v > MotorTest.STOP; // for whoever opened this: a motor was turned
                    }
                    if (now - lastPoll >= 300) {
                        MotorTest.Live live = test.poll();
                        lastPoll = now;
                        if (live.armed()) {
                            limits.stop();
                            test.set(new int[0]);
                        }
                        ui(() -> showLive(live));
                    }
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    break;
                } catch (IOException | RuntimeException e) {
                    if (test != null) test.close();
                    test = null;
                    info = null;
                    limits.stop(); // what was asked of it is over with it: not sent again when a board is back
                    String msg = e.getMessage();
                    ui(() -> {
                        stopAll();
                        showMotors(4, false);
                        usb.set(State.BAD, "Lost: " + msg + "<br>If a motor still spins, unplug the battery.");
                    });
                    main.logLine("Motor test: lost the flight controller (" + msg + ").");
                    dropJobs(); // nothing asked of this board is made on the next one that opens
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        } finally {
            if (test != null) test.close(); // sends STOP first, whatever ended the loop
        }
    }

    private void connected(MotorTest.Info i) {
        showArmed(false); // a motor test opens only on a board that is not armed
        if (i.quadX() && i.reversed() != propsOut) { // the picture shows what Betaflight has
            propsOut = i.reversed();
            main.save(java.util.Map.of(PROPS_OUT_KEY, Boolean.toString(propsOut)));
            directionSwitch.repaint();
        }
        usb.set(State.OK, "<b>" + i.port() + "</b> - Betaflight " + i.version());
        showMixer();
        showMotors(i.motorCount(), true);
        diagram.repaint();
    }

    /** The layout Betaflight has, and whether its motor direction matches the one picked here. */
    private void showMixer() {
        MotorTest.Info i = info;
        if (i == null) return;
        if (!i.quadX()) {
            mixer.set(State.WARN, "Not Quad X (mixer " + i.mixer() + "): picture doesn't apply. Use "
                    + "Betaflight Configurator's Motors tab.");
        } else {
            mixer.set(State.OK, "Quad X, <b>" + (i.reversed() ? "props out" : "props in") + "</b>.");
        }
    }

    /**
     * PROPS IN / OUT: with the flight controller there, Betaflight is set to it (saved, it restarts) and the switch
     * turns once that is done - not at the press (F-077); else the picture only. Not while the board is armed.
     */
    private void setPropsOut(boolean out) {
        if (out == propsOut) return;
        MotorTest.Info i = info;
        if (i != null && i.quadX() && i.reversed() != out) {
            if (armedNow) {
                note.setHtml(BetaflightUsb.ARMED);
                return;
            }
            stopAll();
            if (!ask.test("Set Betaflight to " + (out ? "props out" : "props in") + "?\nBetaflight restarts.", "Props in / out")) return;
            String was = i.reversed() ? "props out" : "props in", now = out ? "props out" : "props in";
            queue(i, t -> {
                main.logLine(MotorTest.writeLog("Betaflight's motor direction (yaw_motors_reversed)", was, now, "writing"));
                t.setPropsOutAndRestart(out);
                main.logLine(MotorTest.writeLog("Betaflight's motor direction (yaw_motors_reversed)", was, now, "saved, Betaflight restarts"));
                ui(() -> showPropsOut(out));
            });
            return;
        }
        showPropsOut(out);
    }

    /** The switch and the picture turn to this props direction, remembered for the next time. */
    private void showPropsOut(boolean out) {
        if (out == propsOut) return;
        propsOut = out;
        main.save(java.util.Map.of(PROPS_OUT_KEY, Boolean.toString(out)));
        showMixer();
        flippedAt = System.currentTimeMillis();
        flip.restart();
    }

    /** 0..1 eased, 1 when no change is animating. */
    private float flipProgress() {
        if (flippedAt == 0) return 1;
        float t = Math.min(1f, (System.currentTimeMillis() - flippedAt) / (float) FLIP_MS);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private void showLive(MotorTest.Live live) {
        if (!isDisplayable()) return;
        showArmed(live.armed());
        if (live.armed()) {
            understood.setSelected(false);
            stopAll();
            enableSliders();
            battery.set(State.BAD, "ARMED - motor test stopped. Disarm.");
            return;
        }
        volts = live.volts();
        batteryIn = live.batteryIn();
        if (!batteryIn) {
            battery.set(State.WARN, "No battery. Plug it in (props off).");
        } else {
            battery.set(State.OK, String.format(java.util.Locale.ROOT, "%.2f V", live.volts())
                    + (live.cells() > 0 ? " - " + live.cells() + "S" : ""));
        }
        for (int i = 0; i < sliders.size() && i < live.motors().length; i++) sliders.get(i).reported = live.motors()[i];
        for (Slider s : sliders) s.repaint();
    }

    /**
     * Armed or not, as the board last said: while armed, nothing that writes to it can be pressed (F-077) - props in /
     * out, reorder, the spin directions. Changed only when the state changes, so it doesn't undo a reorder's own.
     */
    private void showArmed(boolean armed) {
        if (armed == armedNow) return;
        armedNow = armed;
        directionSwitch.setEnabled(!armed);
        directionSwitch.setCursor(Cursor.getPredefinedCursor(armed ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
        MotorTest.Info i = info;
        reorderButton.setEnabled(reorderAllowed(i));
        for (javax.swing.JComboBox<String> c : directionChoices) c.setEnabled(!armed);
    }

    /** REORDER MOTORS can be pressed: a Quad X with four motors, connected and not armed. */
    private boolean reorderAllowed(MotorTest.Info i) {
        return i != null && i.quadX() && i.motorCount() == 4 && !armedNow;
    }

    private static void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    private static JComponent gap(int h) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(1, h));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        return p;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getMaximumSize().height));
        return c;
    }

    // ------------------------------------------------------------------ slider

    /** A vertical slider like Betaflight's, from STOP to MAX: % on top, the motor number under it. 0 = master. */
    final class Slider extends JComponent {
        private final int motor;
        int value = MotorTest.STOP;
        int reported; // what the flight controller says it outputs; 0 = unknown

        Slider(int motor) {
            this.motor = motor;
            setPreferredSize(new Dimension(motor == 0 ? 72 : 56, SLIDER_H));
            setEnabled(false);
            setToolTipText(motor == 0 ? "All motors" : "Motor " + motor);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!isEnabled() && MotorTestDialog.this.isActive()) readyToSpin(); // says why not: the box, or no battery
                    else drag(e.getY());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (isEnabled()) drag(e.getY());
                }

                @Override
                public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    // only in the window in front: Windows scrolls windows under the mouse even when inactive
                    if (isEnabled() && MotorTestDialog.this.isActive()) set(value - e.getWheelRotation() * 10);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    diagram.hover = motor;
                    diagram.repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    diagram.hover = -1;
                    diagram.repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        private int trackTop() {
            return 26;
        }

        private int trackBottom() {
            return getHeight() - 26;
        }

        private void drag(int y) {
            if (!MotorTestDialog.this.isActive()) return;
            if (!isEnabled() || !readyToSpin()) return;
            double f = 1 - (y - trackTop()) / (double) (trackBottom() - trackTop());
            set((int) Math.round(MotorTest.STOP + Math.max(0, Math.min(1, f)) * (MotorTest.MAX - MotorTest.STOP)));
        }

        void set(int v) {
            v = Math.max(MotorTest.STOP, Math.min(MotorTest.MAX, v));
            if (motor == 0) {
                value = v;
                for (Slider s : sliders) s.value = v;
                for (Slider s : sliders) s.repaint();
            } else {
                value = v;
            }
            repaint();
            slidersChanged();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), top = trackTop(), bottom = trackBottom();
                int tw = 14, tx = (w - tw) / 2;
                boolean on = isEnabled();
                double f = (value - MotorTest.STOP) / (double) (MotorTest.MAX - MotorTest.STOP);
                int fillTop = (int) Math.round(bottom - f * (bottom - top));
                g.setColor(Theme.PANEL_2);
                g.fillRect(tx, top, tw, bottom - top);
                g.setColor(on ? (motor == 0 ? Theme.TEXT : Theme.ACCENT) : Theme.LINE);
                g.fillRect(tx, fillTop, tw, bottom - fillTop);
                g.setColor(on ? Theme.MUTED : Theme.LINE);
                g.drawRect(tx, top, tw, bottom - top);
                // handle
                g.setColor(on ? Color.WHITE : Theme.MUTED);
                g.fillRect(tx - 6, fillTop - 3, tw + 12, 6);
                // % on top, motor number under
                g.setFont(Theme.monoFont(Font.BOLD, 13f));
                String pct = Math.round((value - MotorTest.STOP) / 10.0) + "%";
                g.setColor(value > MotorTest.STOP ? Theme.TEXT : Theme.MUTED);
                g.drawString(pct, (w - g.getFontMetrics().stringWidth(pct)) / 2, 16);
                g.setFont(Theme.headingFont(14f));
                String label = motor == 0 ? "MASTER" : Integer.toString(motor);
                g.setColor(motor == 0 ? Theme.MUTED : on ? Theme.TEXT : Theme.MUTED);
                g.drawString(label, (w - g.getFontMetrics().stringWidth(label)) / 2, getHeight() - 6);
            } finally {
                g.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ props in / out

    /** PROPS IN | PROPS OUT: which way the motors are meant to turn; the chosen half is lit. */
    private final class DirectionSwitch extends JComponent {
        private static final String[] LABELS = {"PROPS IN", "PROPS OUT"};

        DirectionSwitch() {
            setPreferredSize(new Dimension(210, 26));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Props in (Betaflight default) or props out (reversed)");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!isEnabled()) return; // grey while the board is armed (F-077)
                    setPropsOut(e.getX() >= getWidth() / 2);
                    repaint();
                }
            });
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight(), half = w / 2;
                float p = flipProgress();
                float at = propsOut ? p : 1 - p; // the highlight: 0 over PROPS IN .. 1 over PROPS OUT
                if (!isEnabled()) g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.4f)); // grey: armed
                g.setColor(Theme.PANEL_2);
                g.fillRect(0, 0, w, h);
                int hx = Math.round(at * (w - half));
                g.setColor(Theme.ACCENT);
                g.fillRect(hx, 0, half, h);
                g.setFont(Theme.headingFont(12f));
                for (int i = 0; i < 2; i++) {
                    int x = i * half, bw = i == 0 ? half : w - half;
                    boolean lit = Math.abs(hx - x) < half / 2;
                    g.setColor(lit ? Color.WHITE : Theme.MUTED);
                    int tw = g.getFontMetrics().stringWidth(LABELS[i]);
                    g.drawString(LABELS[i], x + (bw - tw) / 2, h / 2 + g.getFontMetrics().getAscent() / 2 - 2);
                }
                g.setColor(Theme.ACCENT);
                g.drawRect(0, 0, w - 1, h - 1);
            } finally {
                g.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ diagram

    /**
     * Betaflight's Quad X from above, front up: each motor's number and the arrow it must turn by. A spinning motor
     * lights up and its prop turns the way it should, to compare with the real one.
     */
    private final class Diagram extends JComponent {
        private static final int DW = 410, DH = 348; // the drone, its arrows and CW / CCW, a line under it for what to do
        private static final float DRONE_CY = 171; // its middle: the FRONT mark above it, the line under it clear
        int hover = -1;
        private double angle;
        final Timer animation = new Timer(30, e -> {
            angle += 0.25;
            repaint();
            if (activeCount() == 0) ((Timer) e.getSource()).stop();
        });
        /** The waiting line's pulse after a press while the flight controller isn't there. */
        private static final long NUDGE_MS = 900;
        private long nudgedAt;
        final Timer nudge = new Timer(30, e -> {
            repaint();
            if (System.currentTimeMillis() - nudgedAt > NUDGE_MS) ((Timer) e.getSource()).stop();
        });

        void nudge() {
            nudgedAt = System.currentTimeMillis();
            nudge.restart();
        }

        Diagram() {
            setPreferredSize(new Dimension(DW / 2, DH / 2));
            setToolTipText("Click a motor: it runs for " + MotorLimits.PULSE_MS / 1000 + " s (box ticked, battery in, props off)");
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int m = motorAt(e);
                    if (m != hover) {
                        hover = m;
                        setCursor(Cursor.getPredefinedCursor(m > 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    int m = motorAt(e);
                    if (m <= 0 || !MotorTestDialog.this.isActive()) return;
                    if (reorderStep > 0) placeReorder(m);
                    else pulse(m);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        /** Which motor's disc is under the mouse (1-4), or -1; the same transform as the painting. */
        private int motorAt(MouseEvent e) {
            MotorTest.Info i = info;
            if (i == null || !i.quadX()) return -1;
            int w = getWidth(), h = getHeight();
            double k = Math.min(w / (double) DW, h / (double) DH);
            double x = (e.getX() - (w - DW * k) / 2) / k, y = (e.getY() - (h - DH * k) / 2) / k;
            float cx = DW / 2f, cy = DRONE_CY, span = 92, prop = 58;
            for (int m = 1; m <= 4; m++) {
                int[] pos = MotorTest.quadXPosition(m);
                if (Math.hypot(x - (cx + pos[0] * span), y - (cy + pos[1] * span)) <= prop + 9) return m;
            }
            return -1;
        }

        void animate() {
            if (activeCount() > 0 && !animation.isRunning()) animation.start();
        }

        private int activeCount() {
            int n = 0;
            for (Slider s : sliders) if (s.value > MotorTest.STOP) n++;
            return n;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight();
                MotorTest.Info i = info;
                if (i != null && !i.quadX()) {
                    g.setFont(Theme.headingFont(16f));
                    g.setColor(Theme.MUTED);
                    String s = "NO PICTURE FOR THIS MOTOR LAYOUT - SEE BETAFLIGHT'S MOTORS TAB";
                    g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, h / 2);
                    return;
                }
                boolean reversed = propsOut;
                // drawn at 460 x 330, scaled to fit
                double k = Math.min(w / (double) DW, h / (double) DH);
                g.translate((w - DW * k) / 2, (h - DH * k) / 2);
                g.scale(k, k);
                float cx = DW / 2f, cy = DRONE_CY, span = 92, prop = 58;
                // arms and body
                g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(0x2a2a26));
                g.drawLine(Math.round(cx - span), Math.round(cy - span), Math.round(cx + span), Math.round(cy + span));
                g.drawLine(Math.round(cx + span), Math.round(cy - span), Math.round(cx - span), Math.round(cy + span));
                g.setColor(new Color(0x34342f));
                g.fillRoundRect(Math.round(cx - 30), Math.round(cy - 46), 60, 92, 14, 14);
                g.setColor(Theme.LINE);
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(Math.round(cx - 30), Math.round(cy - 46), 60, 92, 14, 14);
                // front: camera, and the word above the drone
                Path2D cam = new Path2D.Float();
                cam.moveTo(cx, cy - 40);
                cam.lineTo(cx - 12, cy - 22);
                cam.lineTo(cx + 12, cy - 22);
                cam.closePath();
                g.setColor(Theme.ACCENT);
                g.fill(cam);
                g.setFont(Theme.headingFont(14f));
                String front = "FRONT";
                g.drawString(front, cx - g.getFontMetrics().stringWidth(front) / 2f, 14);
                Path2D up = new Path2D.Float();
                up.moveTo(cx, 18);
                up.lineTo(cx - 6, 27);
                up.lineTo(cx + 6, 27);
                up.closePath();
                g.fill(up);
                for (int m = 1; m <= 4; m++) motor(g, m, cx, cy, span, prop, reversed, i != null);
                if (reorderStep > 0) {
                    g.setFont(Theme.headingFont(14f));
                    g.setColor(Theme.WARN);
                    String s = "MOTOR " + reorderStep + " SPINS - CLICK WHERE IT IS";
                    g.drawString(s, (DW - g.getFontMetrics().stringWidth(s)) / 2f, DH - 2);
                }
                if (i == null) { // in the warning colour; a press anywhere makes it pulse and shake (nudge)
                    double t = Math.min(1, (System.currentTimeMillis() - nudgedAt) / (double) NUDGE_MS);
                    double swell = t < 1 ? Math.sin(t * Math.PI) : 0;
                    float shake = t < 1 ? (float) (Math.sin(t * Math.PI * 8) * 6 * (1 - t)) : 0;
                    g.setFont(Theme.headingFont((float) (12 + 3 * swell)));
                    g.setColor(swell > 0 ? Theme.ACCENT : Theme.WARN);
                    String s = "WAITING FOR THE FLIGHT CONTROLLER";
                    g.drawString(s, (DW - g.getFontMetrics().stringWidth(s)) / 2f + shake, DH - 2);
                }
            } finally {
                g.dispose();
            }
        }

        private void motor(Graphics2D g, int m, float cx, float cy, float span, float prop, boolean reversed, boolean known) {
            int[] pos = MotorTest.quadXPosition(m);
            float mx = cx + pos[0] * span, my = cy + pos[1] * span;
            boolean cw = MotorTest.clockwise(m, reversed);
            // while reordering a running slider says nothing about where its motor is: no place turns, the picked ones go green
            boolean placed = false;
            for (int k = 0; reorderStep > 0 && k < reorderStep - 1; k++) placed |= reorderPlaces[k] == m;
            boolean spinning = reorderStep == 0 && m - 1 < sliders.size() && sliders.get(m - 1).value > MotorTest.STOP;
            boolean lit = spinning || hover == m || hover == 0 && reorderStep == 0;
            Color tone = placed ? Theme.OK : lit ? Theme.ACCENT : known ? Theme.TEXT : Theme.MUTED;
            // prop disc
            g.setColor(Theme.alpha(tone, spinning ? 60 : 22));
            g.fillOval(Math.round(mx - prop), Math.round(my - prop), Math.round(2 * prop), Math.round(2 * prop));
            g.setColor(Theme.alpha(tone, 120));
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5, 4}, 0));
            g.drawOval(Math.round(mx - prop), Math.round(my - prop), Math.round(2 * prop), Math.round(2 * prop));
            // the blades, turning the way they must while the motor runs
            double a = (spinning ? angle * (cw ? 1 : -1) : 0) + Math.PI / 4;
            g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Theme.alpha(tone, spinning ? 220 : 90));
            float bl = prop * 0.86f;
            g.drawLine(Math.round(mx - (float) Math.cos(a) * bl), Math.round(my - (float) Math.sin(a) * bl),
                    Math.round(mx + (float) Math.cos(a) * bl), Math.round(my + (float) Math.sin(a) * bl));
            // direction arrow round the disc (screen y points down: clockwise = increasing angle)
            float r = prop + 9;
            // just switched props in / out: the arrow is drawn again from its start, sweeping round the new way
            double start = cw ? 200 : -20, extent = (cw ? -220 : 220) * Math.max(0.04, flipProgress()); // Arc2D: counter-clockwise positive
            g.setColor(tone);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Float(mx - r, my - r, 2 * r, 2 * r, (float) start, (float) extent, Arc2D.OPEN));
            double end = Math.toRadians(start + extent);
            float ex = mx + (float) Math.cos(end) * r, ey = my - (float) Math.sin(end) * r;
            // tangent at the end, in the turning direction
            double tx = cw ? Math.sin(end) : -Math.sin(end), ty = cw ? Math.cos(end) : -Math.cos(end);
            double nx = -ty, ny = tx;
            Path2D head = new Path2D.Double();
            head.moveTo(ex + tx * 11, ey + ty * 11);
            head.lineTo(ex + nx * 7, ey + ny * 7);
            head.lineTo(ex - nx * 7, ey - ny * 7);
            head.closePath();
            g.fill(head);
            // motor bell with its number
            g.setColor(new Color(0x1d1d1a));
            g.fillOval(Math.round(mx - 17), Math.round(my - 17), 34, 34);
            g.setColor(tone);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(Math.round(mx - 17), Math.round(my - 17), 34, 34);
            g.setFont(Theme.headingFont(20f));
            String n = Integer.toString(m);
            g.drawString(n, mx - g.getFontMetrics().stringWidth(n) / 2f, my + 7);
            // CW / CCW beside it, away from the body
            g.setFont(Theme.headingFont(13f));
            String dir = cw ? "CW" : "CCW";
            float lx = pos[0] > 0 ? mx + r + 10 : mx - r - 10 - g.getFontMetrics().stringWidth(dir);
            g.drawString(dir, lx, my + 5);
        }
    }
}
