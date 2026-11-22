package de.droiddrone.pc.gui;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.SetupBoard;
import de.droiddrone.testing.Waits;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drone setup's Motors and orientation step for a test: in a setup guide that is built as when open and never shown,
 * on a made-up USB (BetaflightUsb.use), with a stand-in for the main window. No serial port of this PC is looked at
 * while one of these is open - and none after it: this PC's ports are put back only once the step's live thread,
 * which looks for a flight controller by itself, has ended.
 */
final class MotorsPageRig implements AutoCloseable {
    static final int LEVEL = 0, MOTORS = 1, ORIENTATION = 2;
    final FakeUsb usb = new FakeUsb();
    /** The settings the step saved. */
    final Map<String, String> saved = new ConcurrentHashMap<>();
    /** The flight controller Drone setup's first step has read (its port and id), as the step is told; null: none. */
    volatile SetupBoard board;
    /**
     * How often the step opened the motor test, for which flight controller, and what to tell it when that window
     * closes: whether a motor was turned.
     */
    volatile int motorTests;
    volatile SetupBoard motorTestFor;
    volatile java.util.function.Consumer<Boolean> motorTestClosed;
    final DroneSetup.MotorsPage.Host host = new DroneSetup.MotorsPage.Host() {
        @Override
        public boolean isLinkActive() {
            return false;
        }

        @Override
        public String setting(String key) {
            return saved.get(key);
        }

        @Override
        public void save(Map<String, String> settings) {
            saved.putAll(settings);
        }

        @Override
        public void motorTest(java.awt.Window owner, SetupBoard board, java.util.function.Consumer<Boolean> onClosed) {
            motorTests++;
            motorTestFor = board;
            motorTestClosed = onClosed;
        }

        @Override
        public void orientationCheck(java.awt.Window owner, SetupBoard board, Runnable onPassed, java.util.function.Consumer<String> onClosed) {
            orientationChecks++;
            orientationCheckFor = board;
            orientationCheckClosed = onClosed;
        }
    };
    /**
     * How often the step opened the orientation check, for which flight controller, and what to tell it when that
     * window closes: the line about the compass's old alignment put back, or null.
     */
    volatile int orientationChecks;
    volatile SetupBoard orientationCheckFor;
    volatile java.util.function.Consumer<String> orientationCheckClosed;
    DroneSetup.MotorsPage page;
    WizardDialog wizard;

    MotorsPageRig() {
        BetaflightUsb.use(usb);
    }

    /**
     * The step as a guide's second page, as in Drone setup, and NEXT pressed: its live thread looks for a flight
     * controller from here on. (It ends when the guide it saw open is closed - so it must start in an open guide.)
     */
    void open() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            page = new DroneSetup.MotorsPage(host, () -> board);
            wizard = new WizardDialog(null, "Drone setup", List.of(new FirstPage(), page), null);
            wizard.pack(); // displayable as when open
            for (JButton b : all(wizard.getContentPane(), JButton.class)) {
                if ("NEXT".equals(b.getText())) b.doClick(0);
            }
        });
        liveThread(); // it runs
    }

    /** Stands for Drone setup's first step. */
    private static final class FirstPage extends WizardDialog.Page {
        @Override
        String title() {
            return "Betaflight";
        }

        @Override
        String hint() {
            return "";
        }

        @Override
        javax.swing.JComponent build() {
            return new javax.swing.JPanel();
        }
    }

    static void onSwing(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    /** The step's rows from the top: {@link #LEVEL}, {@link #MOTORS}, {@link #ORIENTATION}. */
    CheckRow row(int index) {
        return all(page.view(), CheckRow.class).get(index);
    }

    /** What a row says under its title, plain. */
    String says(int index) {
        StringBuilder sb = new StringBuilder();
        for (javax.swing.text.JTextComponent t : all(row(index), javax.swing.text.JTextComponent.class)) {
            if (!t.isVisible()) continue;
            try {
                sb.append(t.getDocument().getText(0, t.getDocument().getLength()).strip());
            } catch (javax.swing.text.BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }
        return sb.toString();
    }

    /** A row's state and text, read on the Swing thread: "WARN: No flight controller ...". */
    String shows(int index) throws Exception {
        String[] out = new String[1];
        onSwing(() -> out[0] = row(index).getState() + ": " + says(index));
        return out[0];
    }

    /** The line under the 3D model: what the live view reads, or why it has no flight controller. Not from the Swing thread. */
    String underTheModel() {
        String[] out = {""};
        try {
            onSwing(() -> {
                for (javax.swing.JLabel l : all(row(ORIENTATION), javax.swing.JLabel.class)) { // the row's title, then that line
                    if (l.getText() != null && !l.getText().isBlank() && !l.getText().equals("Orientation")) out[0] = l.getText();
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out[0];
    }

    /** Presses the button of this name (CALIBRATE, CHECK NOW, ALREADY CHECKED) as the pilot would. */
    void press(String name) throws Exception {
        onSwing(() -> {
            for (JButton b : all(page.view(), JButton.class)) {
                if (name.equalsIgnoreCase(b.getText())) {
                    b.doClick(0);
                    return;
                }
            }
            throw new IllegalStateException("no button " + name);
        });
    }

    /** The step's live thread: it follows the flight controller's attitude and does the level calibration. */
    static Thread liveThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().equals("orientation-live")) return t;
        throw new IllegalStateException("no live thread");
    }

    static <T> List<T> all(Component root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T> void collect(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, type, out);
    }

    private static boolean usbThreadsAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && (t.getName().equals("orientation-live") || t.getName().equals("motor-test"))) return true;
        }
        return false;
    }

    /**
     * The guide closed, and the made-up USB ended safely: first the live thread, and only when it is gone this PC's
     * own ports are put back. Until then the stand-in stays, now with nothing on it - a thread that outlives its
     * test must never find a real board.
     */
    @Override
    public void close() throws Exception {
        if (wizard != null) onSwing(() -> wizard.closeAs("CLOSE"));
        for (BetaflightUsb.Port p : usb.ports()) usb.unplug(p.systemName());
        boolean ended = Waits.until(() -> !usbThreadsAlive(), 20_000);
        assertTrue(ended, "the step's live thread is still running: the made-up USB stays in place");
        BetaflightUsb.use(null);
    }
}
