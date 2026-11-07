package de.droiddrone.pc.gui;

import de.droiddrone.pc.gui.CheckRow.State;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.GpsSetup;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.List;

/**
 * Drone setup &gt; Calibrate compass, like Betaflight Configurator's Calibrate Magnetometer: START tells Betaflight to
 * learn the compass's offsets (MSP_MAG_CALIBRATION), and for the next 30 s the pilot turns the drone round every
 * axis while the window shows where the compass has pointed so far - a dot per reading on two discs, from above and
 * from the side; the aim is dots all round both. At the end Betaflight keeps the offsets itself; the window reads
 * them back over the CLI (and saves if Betaflight hasn't) and says whether they are there. Over this PC's USB, with
 * the drone on the bench, props off, away from metal, magnets, motors running and this PC.
 */
final class CompassCalibrationDialog extends JFrame {
    /** Betaflight's calibration runs this long from the request. */
    static final long CALIBRATION_MS = 30_000;
    /** Then Betaflight writes the offsets (saveConfigAndNotify) before they can be read. */
    private static final long SETTLE_MS = 2000;
    /** After the CLI closes Betaflight restarts and its USB port is gone for a few seconds. */
    private static final long RESTART_MS = 6000;
    private static final int MSP_STATUS = 101, MSP_RAW_IMU = 102, MSP_MAG_CALIBRATION = 206;

    private final MainWindow main;
    private final String chosenPort;
    private final CheckRow usb = new CheckRow("Flight controller on USB");
    private final CheckRow compass = new CheckRow("Compass");
    private final JButton start = Theme.primaryButton("Start");
    private final Discs discs = new Discs();
    private final WrappingText note = new WrappingText("", Theme.font(Font.PLAIN, 13f), Theme.MUTED);
    private volatile boolean running = true;
    private volatile boolean startWanted;
    private volatile boolean magFound;
    private volatile boolean calibrating;
    private volatile long startedAt;
    /**
     * Betaflight's compass offsets before Start (1.0.49): a calibration counts only when it changed them - old offsets
     * left as they were aren't one. Read once over the CLI on the first connection (Betaflight restarts after it), then
     * taken from each calibration's result. Null = no offsets (no compass in this Betaflight).
     */
    private volatile int[] offsetsBefore;
    private volatile boolean offsetsRead;
    /** Unit directions the compass has reported during this calibration, for the discs. */
    private final List<float[]> points = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Thread worker;

    private CompassCalibrationDialog(Window owner, MainWindow main, String chosenPort) {
        super("Compass calibration - DRAUPNE Link");
        setIconImages(AppIcon.images());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // opened from a setup guide (Drone setup > Calibrate compass), which blocks the app's other windows: not this one
        setModalExclusionType(java.awt.Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        this.main = main;
        this.chosenPort = chosenPort;
        JPanel root = new JPanel(new BorderLayout(14, 14));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        setContentPane(root);

        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(Theme.label("COMPASS", Theme.headingFont(26f), Theme.TEXT), BorderLayout.NORTH);
        head.add(Theme.label("Press START, then turn the drone slowly round every axis for 30 s: nose round the horizon, "
                + "roll it over, pitch it over. Away from metal, magnets and this PC.", Theme.font(Font.PLAIN, 13f), Theme.MUTED), BorderLayout.SOUTH);
        root.add(head, BorderLayout.NORTH);

        JPanel side = Theme.card(null);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(330, 10));
        for (CheckRow r : new CheckRow[]{usb, compass}) {
            r.setAlignmentX(LEFT_ALIGNMENT);
            side.add(r);
            side.add(javax.swing.Box.createVerticalStrut(6));
        }
        root.add(side, BorderLayout.WEST);

        JPanel card = Theme.card(new BorderLayout(0, 8));
        card.add(Theme.sectionTitle("Where the compass has pointed"), BorderLayout.NORTH);
        card.add(discs, BorderLayout.CENTER);
        note.setAlignmentX(LEFT_ALIGNMENT);
        card.add(note, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);

        start.addActionListener(e -> {
            startWanted = true;
            start.setEnabled(false);
        });
        start.setEnabled(false);
        JButton close = Theme.secondaryButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(start);
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        usb.set(State.PENDING, "Looking for it...");
        compass.set(State.PENDING, "");
        setMinimumSize(new Dimension(900, 560));
        setSize(960, 620);
        setLocationRelativeTo(owner);
        WindowReveal.onOpen(this);
        worker = new Thread(this::run, "compass-calibration");
        worker.setDaemon(true);
        worker.start();
    }

    /** The one open, if any: opening it again brings that one forward. */
    private static CompassCalibrationDialog open;

    /** Open now (it may have the flight controller's USB port). */
    static boolean isOpen() {
        return open != null && open.isDisplayable();
    }

    /**
     * @param chosenPort the USB port Drone setup uses, or null for the first that answers as Betaflight
     * @param onClosed   run once the window is closed (and so stops using the USB port), or null
     */
    static void open(Window owner, MainWindow main, String chosenPort, Runnable onClosed) {
        if (open == null || !open.isDisplayable()) {
            open = new CompassCalibrationDialog(owner, main, chosenPort);
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

    /** The worker: finds the flight controller, starts the calibration on START, reads the compass meanwhile, checks the result. */
    private void run() {
        BetaflightUsb fc = null;
        long retryAt = 0;
        long lastStatus = 0;
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
                        if (!offsetsRead) { // the offsets before Start, over the CLI: its exit restarts Betaflight
                            fc.enterCli();
                            String cal = fc.command("get mag_calibration");
                            offsetsBefore = GpsSetup.magOffsets(cal);
                            offsetsRead = true;
                            main.logLine("Compass calibration: before Start " + DroneSetup.BetaflightPage.brief(cal));
                            fc.exitCli();
                            fc = null;
                            retryAt = System.currentTimeMillis() + RESTART_MS;
                            ui(() -> usb.set(State.PENDING, "Read the compass's offsets - Betaflight restarts..."));
                            continue;
                        }
                        magFound = GpsSetup.magInStatus(fc.msp(MSP_STATUS, null));
                        lastStatus = now;
                        boolean mag = magFound;
                        ui(() -> {
                            usb.set(State.OK, "Found.");
                            compass.set(mag ? State.OK : State.BAD, mag ? "Found. Press <b>Start</b>."
                                    : "Not found: Betaflight sees no compass. Is one fitted and on in Drone setup (COMPASS: Yes, written)?");
                            start.setEnabled(mag && !calibrating);
                        });
                    }
                    if (startWanted && !calibrating) {
                        startWanted = false;
                        fc.msp(MSP_MAG_CALIBRATION, null);
                        points.clear();
                        startedAt = System.currentTimeMillis();
                        calibrating = true;
                        main.logLine("Compass calibration started.");
                        ui(() -> compass.set(State.PENDING, "Calibrating - turn the drone round every axis..."));
                    }
                    if (calibrating) {
                        int[] m = GpsSetup.magFromRawImu(fc.msp(MSP_RAW_IMU, null));
                        if (m != null) {
                            double len = Math.sqrt((double) m[0] * m[0] + (double) m[1] * m[1] + (double) m[2] * m[2]);
                            if (len > 0) points.add(new float[]{(float) (m[0] / len), (float) (m[1] / len), (float) (m[2] / len)});
                        }
                        ui(discs::repaint);
                        if (System.currentTimeMillis() - startedAt >= CALIBRATION_MS + SETTLE_MS) {
                            calibrating = false;
                            boolean ok = checkAndKeep(fc);
                            fc.close(); // the CLI's exit or save: Betaflight restarts
                            fc = null;
                            retryAt = System.currentTimeMillis() + RESTART_MS;
                            ui(() -> {
                                compass.set(ok ? State.OK : State.WARN, ok ? "Calibrated. You can close this window."
                                        : "Betaflight's compass offsets didn't change: it didn't take. <b>Start</b> again and turn the drone further round every axis, away from metal.");
                                usb.set(State.PENDING, "Betaflight restarts...");
                                start.setEnabled(false);
                            });
                            continue;
                        }
                        Thread.sleep(80);
                    } else {
                        if (now - lastStatus >= 1000) { // the compass may come (Drone setup writing it on) or go
                            boolean mag = GpsSetup.magInStatus(fc.msp(MSP_STATUS, null));
                            lastStatus = now;
                            if (mag != magFound) {
                                magFound = mag;
                                ui(() -> {
                                    compass.set(mag ? State.OK : State.BAD, mag ? "Found. Press <b>Start</b>." : "Not found: Betaflight sees no compass.");
                                    start.setEnabled(mag);
                                });
                            }
                        }
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException | RuntimeException e) {
                    if (fc != null) fc.close();
                    fc = null;
                    calibrating = false;
                    retryAt = System.currentTimeMillis() + 1500;
                    String msg = e.getMessage();
                    ui(() -> {
                        usb.set(State.BAD, "Lost: " + msg);
                        start.setEnabled(false);
                    });
                    main.logLine("Compass calibration: lost the flight controller (" + msg + ").");
                }
            }
        } finally {
            if (fc != null) fc.close();
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

    /** Over the CLI: the offsets Betaflight learnt, changed from before Start; saved if it has them in RAM only, else just exit. */
    private boolean checkAndKeep(BetaflightUsb fc) throws IOException {
        fc.enterCli();
        String cal = fc.command("get mag_calibration");
        boolean ok = GpsSetup.calibratedSince(offsetsBefore, cal);
        main.logLine("Compass calibration: " + DroneSetup.BetaflightPage.brief(cal) + (ok ? "" : " - not changed from before Start: not calibrated"));
        offsetsBefore = GpsSetup.magOffsets(cal); // the next Start is compared with these
        if (ok) fc.saveAndReboot(); // a save when Betaflight already saved changes nothing
        else fc.exitCli();
        return ok;
    }

    /** Two discs: the compass's direction seen from above (x, y) and from the side (x, z), a dot per reading. */
    private final class Discs extends JComponent {
        Discs() {
            setPreferredSize(new Dimension(560, 320));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            int w = getWidth(), h = getHeight();
            int r = Math.max(40, Math.min(w / 4 - 30, h / 2 - 30));
            int cy = h / 2;
            int[] cx = {w / 4, w * 3 / 4};
            String[] names = {"FROM ABOVE", "FROM THE SIDE"};
            for (int d = 0; d < 2; d++) {
                g.setColor(Theme.PANEL_2);
                g.fillOval(cx[d] - r, cy - r, 2 * r, 2 * r);
                g.setColor(Theme.LINE);
                g.setStroke(new BasicStroke(1.5f));
                g.drawOval(cx[d] - r, cy - r, 2 * r, 2 * r);
                g.drawLine(cx[d] - r, cy, cx[d] + r, cy);
                g.drawLine(cx[d], cy - r, cx[d], cy + r);
                g.setFont(Theme.headingFont(12f));
                g.setColor(Theme.MUTED);
                g.drawString(names[d], cx[d] - g.getFontMetrics().stringWidth(names[d]) / 2, cy + r + 18);
            }
            g.setColor(Theme.OK);
            for (float[] p : points) {
                // above: x right, y up; side: x right, z up. Readings on the far half of the sphere drawn dimmer
                drawDot(g, cx[0] + p[0] * r, cy - p[1] * r, p[2] >= 0);
                drawDot(g, cx[1] + p[0] * r, cy - p[2] * r, p[1] >= 0);
            }
            if (calibrating) {
                long left = Math.max(0, CALIBRATION_MS - (System.currentTimeMillis() - startedAt));
                String s = (left + 999) / 1000 + " s";
                g.setFont(Theme.headingFont(28f));
                g.setColor(Theme.TEXT);
                g.drawString(s, w / 2 - g.getFontMetrics().stringWidth(s) / 2, 34);
                repaint(100);
            }
            g.dispose();
        }

        private void drawDot(Graphics2D g, float x, float y, boolean near) {
            g.setColor(near ? Theme.OK : Theme.alpha(Theme.OK, 90));
            g.fillOval(Math.round(x) - 3, Math.round(y) - 3, 6, 6);
        }
    }
}
