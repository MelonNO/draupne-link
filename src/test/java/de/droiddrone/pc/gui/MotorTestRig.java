package de.droiddrone.pc.gui;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.bench.hw.FakeFlightBoard;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.MotorTest;
import de.droiddrone.pc.setup.SetupBoard;
import de.droiddrone.testing.Waits;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The motor test's window for a test: built as when open and never shown, on a made-up USB with made-up boards
 * (FakeFlightBoard behind BetaflightUsb.use). No serial port of this PC is looked at while one of these is open - and
 * none after it: this PC's ports are put back only once the threads that look for a flight controller have ended.
 */
final class MotorTestRig implements AutoCloseable {
    /** MSP_SET_MOTOR, as the boards list what they were asked. */
    static final int SET_MOTOR = 214;
    final FakeUsb usb = new FakeUsb();
    /** What the window wrote to the app's log, and the settings it saved. */
    final List<String> log = new CopyOnWriteArrayList<>();
    final Map<String, String> saved = new ConcurrentHashMap<>();
    final MotorTestDialog.Host host = new MotorTestDialog.Host() {
        @Override
        public String setting(String key) {
            return saved.get(key);
        }

        @Override
        public void save(Map<String, String> settings) {
            saved.putAll(settings);
        }

        @Override
        public void logLine(String text) {
            log.add(text);
        }
    };
    MotorTestDialog window;
    /** Lets a Swing thread held by {@link #hangSwing} go on. */
    private final CountDownLatch swingMayGoOn = new CountDownLatch(1);

    MotorTestRig() {
        BetaflightUsb.use(usb);
    }

    /** A Betaflight board with a battery in on a port of this name; its id's last byte is {@code id}. */
    FakeFlightBoard plug(String port, int id) {
        FakeFlightBoard board = new FakeFlightBoard(port);
        board.uid = uid(id);
        board.battery(16.0, 4);
        usb.plug(port, "Made-up Betaflight board", board::connect);
        return board;
    }

    /** MSP_UID's twelve bytes of the made-up board number {@code id}. */
    static byte[] uid(int id) {
        return new byte[]{0x4d, 0x41, 0x44, 0x45, 0x2d, 0x55, 0x50, 0, 0, 0, 0, (byte) id};
    }

    /** The id of the made-up board number {@code id}, as Drone setup reads it for CONFIRM. */
    static String id(int id) {
        return de.droiddrone.pc.setup.SetupConfirm.boardId(uid(id), null);
    }

    /** The window as {@code MotorTestDialog.open} builds it, not put on the screen; opened for no board. */
    MotorTestDialog open(MotorLimits limits) throws Exception {
        return open(limits, null);
    }

    /** The same, opened for this flight controller (Drone setup's). */
    MotorTestDialog open(MotorLimits limits, SetupBoard board) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            window = new MotorTestDialog(null, host, limits, board);
            window.addNotify(); // displayable as when open
        });
        return window;
    }

    /** What the row "Flight controller on USB" says, with its state: "BAD: ..." (a line break reads as a space). Not from the Swing thread. */
    String usbRow() {
        String[] out = new String[1];
        swing(() -> {
            CheckRow row = all(window.getContentPane(), CheckRow.class).get(0);
            StringBuilder said = new StringBuilder();
            for (javax.swing.text.JTextComponent t : all(row, javax.swing.text.JTextComponent.class)) {
                try {
                    said.append(t.getDocument().getText(0, t.getDocument().getLength()).strip());
                } catch (javax.swing.text.BadLocationException e) {
                    throw new IllegalStateException(e);
                }
            }
            out[0] = row.getState() + ": " + said;
        });
        return out[0];
    }

    static void onSwing(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    /** As {@link #onSwing}, for a condition that is looked at again and again: no checked exception. */
    private static void swing(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException | java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits for the flight controller to be read (the risk box can be ticked), and ticks it. */
    void connectAndTick() throws Exception {
        connect();
        onSwing(() -> box().doClick());
        assertTrue(box().isSelected(), "the box is ticked");
    }

    /** Waits for the flight controller to be read: the risk box can be ticked, and is not. */
    void connect() throws Exception {
        Waits.waitForOnSwing(() -> box().isEnabled(), 10_000, "the motor test to find the flight controller");
    }

    /** The SPIN DIRECTION lists, motor 1 first (there while a DShot board is connected). */
    @SuppressWarnings("unchecked")
    List<javax.swing.JComboBox<String>> directions() {
        List<javax.swing.JComboBox<String>> out = new ArrayList<>();
        for (javax.swing.JComboBox<?> c : all(window.getContentPane(), javax.swing.JComboBox.class)) out.add((javax.swing.JComboBox<String>) c);
        return out;
    }

    /** The window's motor thread. */
    static Thread motorThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().equals("motor-test")) return t;
        throw new IllegalStateException("no motor thread");
    }

    /** "Propellers removed, I understand the risk". */
    JCheckBox box() {
        return all(window.getContentPane(), JCheckBox.class).get(0);
    }

    /** Every text the window shows, plain. */
    String text() {
        return text(window.getContentPane());
    }

    /**
     * Runs this on the Swing thread and keeps that thread from doing anything after it - a window that hangs - until
     * {@link #swingGoesOn} (or close). Returns once it has run.
     */
    void hangSwing(Runnable first) throws InterruptedException {
        CountDownLatch hung = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                first.run();
            } finally {
                hung.countDown();
                try {
                    swingMayGoOn.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        hung.await();
    }

    void swingGoesOn() {
        swingMayGoOn.countDown();
    }

    /** How often this board was sent MSP_SET_MOTOR. */
    static int motorFrames(FakeFlightBoard board) {
        return (int) board.mspRequests().stream().filter(c -> c == SET_MOTOR).count();
    }

    /** Waits until the board was sent its motors' values a few more times: what it holds now is what the window keeps sending. */
    static void afterMoreMotorFrames(FakeFlightBoard board) throws InterruptedException {
        int before = motorFrames(board);
        Waits.await(() -> motorFrames(board) >= before + 3, 10_000, "the motor thread to go on sending");
    }

    static boolean allStopped(FakeFlightBoard board) {
        for (int m : board.motors()) if (m != MotorTest.STOP) return false;
        return true;
    }

    static <T> List<T> all(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T> void collect(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, type, out);
    }

    /** The texts under this component, plain (labels, and the HTML lines without their tags). */
    static String text(Component root) {
        StringBuilder sb = new StringBuilder();
        for (javax.swing.text.JTextComponent t : all(asContainer(root), javax.swing.text.JTextComponent.class)) {
            try {
                sb.append(t.getDocument().getText(0, t.getDocument().getLength()).strip()).append('\n');
            } catch (javax.swing.text.BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }
        for (javax.swing.JLabel l : all(asContainer(root), javax.swing.JLabel.class)) sb.append(l.getText()).append('\n');
        return sb.toString();
    }

    private static Container asContainer(Component c) {
        return (Container) c;
    }

    /** The threads of the app that look for a flight controller on USB: the motor test's, and Drone setup's live view. */
    static boolean usbThreadsAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && (t.getName().equals("motor-test") || t.getName().equals("orientation-live"))) return true;
        }
        return false;
    }

    /**
     * Ends the made-up USB safely: first every thread that would look for a flight controller, and only when they
     * are gone this PC's own ports are put back. Until then the stand-in stays, now with nothing on it - a thread
     * that outlives its test must never find a real board.
     */
    static void putRealUsbBack(FakeUsb usb) throws InterruptedException {
        for (BetaflightUsb.Port p : usb.ports()) usb.unplug(p.systemName());
        boolean ended = Waits.until(() -> !usbThreadsAlive(), 15_000);
        assertTrue(ended, "a thread that looks for a flight controller is still running: the made-up USB stays in place");
        BetaflightUsb.use(null);
    }

    @Override
    public void close() throws Exception {
        swingGoesOn();
        if (window != null) onSwing(() -> window.dispose());
        putRealUsbBack(usb);
    }
}
