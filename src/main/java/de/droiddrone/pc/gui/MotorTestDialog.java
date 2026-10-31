package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.MotorTest;

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
 * the USB link fails; sliders go to 30 % at most.
 */
final class MotorTestDialog extends JFrame {
    private static final int SLIDER_H = 150;
    /** The pilot's choice of motor direction for the picture; props out unless they pick props in. */
    static final String PROPS_OUT_KEY = "motorTest.propsOut";
    private final MainWindow main;
    private final Diagram diagram = new Diagram();
    private final JCheckBox understood = new JCheckBox("<html><b>I understand the risks, the propellers are removed</b> - enable motor control.</html>");
    private final JButton stopAll = Theme.primaryButton("Stop all  (space)");
    private final JPanel sliderRow = new JPanel(new GridLayout(1, 0, 10, 0));
    private final List<Slider> sliders = new java.util.ArrayList<>();
    private final Slider master = new Slider(0);
    private final CheckRow usb = new CheckRow("Flight controller on USB");
    private final CheckRow mixer = new CheckRow("Motor layout");
    private final CheckRow battery = new CheckRow("Battery");

    /** What the sliders ask for; the motor thread sends it. All STOP unless the box is ticked. */
    private volatile int[] wanted = new int[0];
    private volatile boolean running = true;
    private volatile MotorTest.Info info;
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

    private MotorTestDialog(Window owner, MainWindow main) {
        super("Motor test - DRAUPNE Link"); // a window of its own: Windows' minimise, maximise and close
        setIconImages(AppIcon.images());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // opened from a setup guide (Drone setup > Check now), which blocks the app's other windows: not this one
        setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        this.main = main;
        propsOut = !"false".equalsIgnoreCase(main.config().getString(PROPS_OUT_KEY));
        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        setContentPane(root);

        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(Theme.label("MOTORS", Theme.headingFont(26f), Theme.TEXT), BorderLayout.NORTH);
        head.add(Theme.label("Check that each motor is in the right place and turns the right way, then that the props go on the right way round. "
                + "Over USB, with the drone on the bench.", Theme.font(Font.PLAIN, 13f), Theme.MUTED), BorderLayout.SOUTH);
        root.add(head, BorderLayout.NORTH);
        root.add(sideColumn(), BorderLayout.WEST);

        JPanel centre = new JPanel(new BorderLayout(0, 14));
        centre.setOpaque(false);
        JPanel diagramCard = Theme.card(new BorderLayout(0, 8));
        JPanel diagramHead = new JPanel(new BorderLayout());
        diagramHead.setOpaque(false);
        diagramHead.add(Theme.sectionTitle("Seen from above"), BorderLayout.WEST);
        diagramHead.add(directionSwitch, BorderLayout.EAST);
        diagramCard.add(diagramHead, BorderLayout.NORTH);
        diagramCard.add(diagram, BorderLayout.CENTER);
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
        usb.set(State.PENDING, "Looking for it...");
        mixer.set(State.PENDING, "");
        battery.set(State.PENDING, "");
        showMotors(4, false);
        setMinimumSize(new Dimension(1100, 720));
        setSize(1180, 800);
        setLocationRelativeTo(owner);
        WindowReveal.onOpen(this);
        worker = new Thread(this::run, "motor-test");
        worker.setDaemon(true);
        worker.start();
    }

    /** The one open, if any: opening it again brings that one forward. */
    private static MotorTestDialog open;

    static void open(Window owner, MainWindow main) {
        open(owner, main, null);
    }

    /** Open now (it may have the flight controller's USB port). */
    static boolean isOpen() {
        return open != null && open.isDisplayable();
    }

    /** @param onClosed run once the window is closed (and so stops using the USB port), or null */
    static void open(Window owner, MainWindow main, Runnable onClosed) {
        if (open == null || !open.isDisplayable()) {
            open = new MotorTestDialog(owner, main);
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
        flip.stop();
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
                + "<li><b>Props off.</b> Battery in, then the flight controller's USB into this PC.</li>"
                + "<li>Pick <b>props in</b> or <b>props out</b> above the picture, as the drone is built. Tick the box, "
                + "raise <b>one</b> motor a little.</li>"
                + "<li>The motor that spins must sit where the picture shows its number and turn the way its arrow points "
                + "(tape on the bell helps).</li>"
                + "<li><b>Wrong motor:</b> motor wiring or Betaflight's motor order. <b>Wrong way:</b> reverse it in ESC "
                + "Configurator / BLHeli, or swap two of its wires.</li>"
                + "<li><b>Props on, battery out:</b> each prop's raised leading edge must go first in its arrow's direction, "
                + "or the drone flips on take-off.</li></ol>",
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
        WrappingText warning = new WrappingText("<b>The motors will spin.</b> Remove all propellers first. Betaflight keeps a "
                + "motor running at the last value it got: if the USB cable comes out while a motor spins, unplug the battery. "
                + "The motors stop when you untick the box, press SPACE or STOP ALL, or leave this window. At most 30 %: "
                + "enough to see which way they turn.", Theme.font(Font.PLAIN, 13f), Theme.WARN);
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
        wanted = new int[0];
        for (Slider s : sliders) s.value = MotorTest.STOP;
        master.value = MotorTest.STOP;
        for (Slider s : sliders) s.repaint();
        master.repaint();
        diagram.repaint();
    }

    private void slidersChanged() {
        int[] v = new int[sliders.size()];
        for (int i = 0; i < v.length; i++) v[i] = sliders.get(i).value;
        wanted = understood.isSelected() ? v : new int[0];
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
                        List<BetaflightUsb.Port> ports = BetaflightUsb.findFlightControllers();
                        if (ports.isEmpty()) {
                            ui(() -> usb.set(State.WARN, "Not found yet. Plug it into this PC with a data USB cable."));
                            Thread.sleep(1500);
                            continue;
                        }
                        test = openFirstBetaflight(ports); // not just the first port: a GPS or ESC adapter may come before it
                        if (test == null) {
                            Thread.sleep(2500);
                            continue;
                        }
                        MotorTest.Info i = test.info();
                        info = i;
                        sent = null;
                        ui(() -> connected(i));
                        main.logLine("Motor test: " + i.port() + ", Betaflight " + i.version() + ", " + i.motorCount() + " motors.");
                    }
                    long now = System.currentTimeMillis();
                    int[] want = wanted;
                    if (!Arrays.equals(want, sent) || now - lastSend >= 250) {
                        test.set(want);
                        sent = want;
                        lastSend = now;
                    }
                    if (now - lastPoll >= 300) {
                        MotorTest.Live live = test.poll();
                        lastPoll = now;
                        if (live.armed()) {
                            wanted = new int[0];
                            test.set(wanted);
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
                    String msg = e.getMessage();
                    ui(() -> {
                        stopAll();
                        showMotors(4, false);
                        usb.set(State.BAD, "Lost: " + msg + "<br>If a motor still spins, unplug the battery.");
                    });
                    main.logLine("Motor test: lost the flight controller (" + msg + ").");
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

    /** The first of these ports that answers as Betaflight and passes MotorTest's checks; null (and why, shown) if none. */
    private MotorTest openFirstBetaflight(List<BetaflightUsb.Port> ports) {
        String why = null;
        for (BetaflightUsb.Port port : ports) {
            try {
                return MotorTest.open(port);
            } catch (IOException e) {
                why = port.systemName() + ": " + e.getMessage();
            }
        }
        String msg = why;
        ui(() -> usb.set(State.BAD, msg + "<br>Trying again by itself."));
        return null;
    }

    private void connected(MotorTest.Info i) {
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
            mixer.set(State.WARN, "Not Quad X (mixer " + i.mixer() + "): the picture doesn't apply. Compare with the mixer picture in "
                    + "Betaflight Configurator's Motors tab.");
        } else if (i.reversed() == propsOut) {
            mixer.set(State.OK, "Quad X, <b>" + (propsOut ? "props out" : "props in") + "</b> - Betaflight is set the same way.");
        } else {
            mixer.set(State.WARN, "Betaflight is set to <b>" + (i.reversed() ? "props out" : "props in") + "</b>, the picture to <b>"
                    + (propsOut ? "props out" : "props in") + "</b>. Make them match: Betaflight Configurator, Motors tab, "
                    + "<i>Motor direction is reversed</i> " + (propsOut ? "on" : "off") + ". Otherwise yaw turns the wrong way.");
        }
    }

    private void setPropsOut(boolean out) {
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
        if (live.armed()) {
            understood.setSelected(false);
            stopAll();
            enableSliders();
            battery.set(State.BAD, "The drone is armed - motor test stopped. Disarm it.");
            return;
        }
        if (live.volts() < 5) {
            battery.set(State.WARN, "No battery: on USB power the motors can't spin. Plug in the battery (props off).");
        } else {
            battery.set(State.OK, String.format(java.util.Locale.ROOT, "%.2f V", live.volts()));
        }
        for (int i = 0; i < sliders.size() && i < live.motors().length; i++) sliders.get(i).reported = live.motors()[i];
        for (Slider s : sliders) s.repaint();
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
    private final class Slider extends JComponent {
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
                    drag(e.getY());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    drag(e.getY());
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
            if (!isEnabled() || !MotorTestDialog.this.isActive()) return;
            double f = 1 - (y - trackTop()) / (double) (trackBottom() - trackTop());
            set((int) Math.round(MotorTest.STOP + Math.max(0, Math.min(1, f)) * (MotorTest.MAX - MotorTest.STOP)));
        }

        private void set(int v) {
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
            setToolTipText("Which way the motors turn: props in (Betaflight's standard) or props out (motor direction reversed)");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    setPropsOut(e.getX() >= getWidth() / 2);
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight(), half = w / 2;
                float p = flipProgress();
                float at = propsOut ? p : 1 - p; // the highlight: 0 over PROPS IN .. 1 over PROPS OUT
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
        private static final int DW = 460, DH = 330;
        int hover = -1;
        private double angle;
        final Timer animation = new Timer(30, e -> {
            angle += 0.25;
            repaint();
            if (activeCount() == 0) ((Timer) e.getSource()).stop();
        });

        Diagram() {
            setPreferredSize(new Dimension(DW / 2, DH / 2));
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
                float cx = DW / 2f, cy = DH / 2f + 6, span = 92, prop = 58;
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
                if (i == null) {
                    g.setFont(Theme.headingFont(12f));
                    g.setColor(Theme.MUTED);
                    String s = "WAITING FOR THE FLIGHT CONTROLLER";
                    g.drawString(s, (DW - g.getFontMetrics().stringWidth(s)) / 2f, DH - 2);
                }
            } finally {
                g.dispose();
            }
        }

        private void motor(Graphics2D g, int m, float cx, float cy, float span, float prop, boolean reversed, boolean known) {
            int[] pos = MotorTest.quadXPosition(m);
            float mx = cx + pos[0] * span, my = cy + pos[1] * span;
            boolean cw = MotorTest.clockwise(m, reversed);
            boolean spinning = m - 1 < sliders.size() && sliders.get(m - 1).value > MotorTest.STOP;
            boolean lit = spinning || hover == m || hover == 0;
            Color tone = lit ? Theme.ACCENT : known ? Theme.TEXT : Theme.MUTED;
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
