package de.droiddrone.pc.gui;

import de.droiddrone.bench.FakeUsb;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.FakeCompassBoard;
import de.droiddrone.pc.setup.GpsSetup;
import de.droiddrone.pc.setup.SetupBoard;
import de.droiddrone.testing.Waits;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orientation check's window for a test: built as when open and never shown, on a made-up USB with a made-up
 * board (FakeCompassBoard) that the test "holds" as the window asks, its waits shortened. No serial port of this PC
 * is looked at while one of these is open - and none after it: this PC's ports are put back only once the window's
 * thread has ended, which is after it has put the compass's old value back when it owed that.
 */
final class OrientationCheckRig implements AutoCloseable {
    static final int USB = 0, FLAT = 1, NOSE_UP = 2, RIGHT_DOWN = 3, COMPASS = 4, RESULT = 5;
    private static final String[] HEADINGS = {"NORTH", "EAST", "SOUTH", "WEST"};
    final FakeUsb usb = new FakeUsb();
    /** What the window wrote to the app's log. */
    final List<String> log = new CopyOnWriteArrayList<>();
    /** GPS and compass: Yes, as chosen in Drone setup - only then does the check look at the compass. */
    final Map<String, String> settings = new ConcurrentHashMap<>(Map.of(GpsSetup.GPS_KEY, "yes", GpsSetup.COMPASS_KEY, "yes"));
    /** What the window told whoever opened it when it was closed: the line about the compass put back, "" for none. */
    final List<String> closed = new CopyOnWriteArrayList<>();
    volatile boolean passed;
    private final Path backups;
    OrientationCheckDialog window;

    /** @param backups where the window keeps Betaflight's settings before a write (a test's own folder) */
    OrientationCheckRig(Path backups) {
        this.backups = backups;
        BetaflightUsb.use(usb);
    }

    /** The window as {@code OrientationCheckDialog.open} builds it, not put on the screen. */
    void open(SetupBoard board) throws Exception {
        OrientationCheckDialog.Host host = new OrientationCheckDialog.Host() {
            @Override
            public String setting(String key) {
                return settings.get(key);
            }

            @Override
            public void logLine(String text) {
                log.add(text);
            }

            @Override
            public Path backupsDir() {
                return backups;
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            window = new OrientationCheckDialog(null, host, board, () -> passed = true, new OrientationCheckDialog.Timing(100, 100, 2));
            window.whenClosed(note -> closed.add(note == null ? "" : note));
            window.addNotify(); // displayable as when open
        });
    }

    static void onSwing(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    // ------------------------------------------------------------------ what the window shows

    /** The big line over the 3D drone: what to do now ("NOSE NORTH"), or the verdict. Not from the Swing thread. */
    boolean says(String instruction) {
        boolean[] found = new boolean[1];
        swing(() -> {
            for (JLabel l : all(window.getContentPane(), JLabel.class)) found[0] |= instruction.equals(l.getText());
        });
        return found[0];
    }

    /** A row's state and text: "WARN: CW90FLIP found (now CW0). Save?". Not from the Swing thread. */
    String row(int index) {
        String[] out = new String[1];
        swing(() -> {
            CheckRow row = all(window.getContentPane(), CheckRow.class).get(index);
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

    /** A button of this name can be pressed now: shown and enabled. Not from the Swing thread. */
    boolean offers(String name) {
        boolean[] there = new boolean[1];
        swing(() -> {
            JButton b = button(name);
            there[0] = b != null && b.isVisible() && b.isEnabled();
        });
        return there[0];
    }

    /** Presses the button of this name (DONE, SAVE, KEEP OLD, SKIP COMPASS, CHECK AGAIN), once it can be pressed. */
    void press(String name) throws Exception {
        Waits.waitFor(() -> offers(name), 15_000, "the button " + name + " to be there: compass row " + row(COMPASS));
        onSwing(() -> button(name).doClick(0));
    }

    private JButton button(String name) {
        for (JButton b : all(window.getContentPane(), JButton.class)) if (name.equals(b.getText())) return b;
        return null;
    }

    // ------------------------------------------------------------------ the pilot's hands

    /** The first part of the check, with a board that sits right on its frame: flat, nose up, flat, right side down. */
    void boardCheck(FakeCompassBoard board) throws Exception {
        board.hold(0, 0);
        Waits.waitFor(() -> says("NOSE STRAIGHT UP"), 15_000, "the check to see the drone flat");
        board.hold(0, -90);
        Waits.waitFor(() -> says("FLAT AGAIN"), 15_000, "the check to see the nose up");
        board.hold(0, 0);
        Waits.waitFor(() -> says("RIGHT SIDE DOWN"), 15_000, "the check to see it flat again");
        board.hold(90, 0);
        Waits.waitFor(() -> says("NOSE NORTH"), 15_000, "the check to find the board right and go on to the compass: " + row(RESULT));
        board.hold(0, 0);
    }

    /**
     * One round of the compass: for north, east, south and west the nose is turned to the quarter given
     * (0 north ... 3 west - as asked: 0, 1, 2, 3) and DONE pressed.
     */
    void round(FakeCompassBoard board, int... noses) throws Exception {
        for (int i = 0; i < 4; i++) {
            String asked = "NOSE " + HEADINGS[i];
            Waits.waitFor(() -> says(asked), 15_000, asked + " to be asked: compass row " + row(COMPASS) + ", USB row " + row(USB));
            board.nose(noses[i]);
            press("DONE");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void swing(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException | java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
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
            String n = t.getName();
            if (t.isAlive() && (n.equals("orientation-check") || n.equals("orientation-live") || n.equals("motor-test"))) return true;
        }
        return false;
    }

    /** Closes the window as the pilot does (CLOSE), without waiting for what it still does with the board. */
    void closeWindow() throws Exception {
        onSwing(() -> window.dispose());
    }

    /**
     * The window closed, and the made-up USB ended safely: first the window's thread (it may still be putting the
     * compass's old value back), and only when it is gone this PC's own ports are put back. Until then the stand-in
     * stays - a thread that outlives its test must never find a real board.
     */
    @Override
    public void close() throws Exception {
        if (window != null) closeWindow();
        boolean ended = Waits.until(() -> !usbThreadsAlive(), 40_000);
        for (BetaflightUsb.Port p : usb.ports()) usb.unplug(p.systemName());
        assertTrue(ended, "the orientation check's thread is still running: the made-up USB stays in place");
        BetaflightUsb.use(null);
    }
}
