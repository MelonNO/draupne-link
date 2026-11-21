package de.droiddrone.bench;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The bench's hands and eyes on the app's windows. A control is found by the words on it, checked to be showing,
 * enabled and not covered by anything, and then pressed where a pilot would press it:
 * <ul>
 * <li><b>desktop</b> - the real mouse on the real screen ({@link Robot}); the bench's windows are kept above all
 * others, and a press only goes out when the screen shows at that spot what the control paints (so it can never land
 * on another program's window). Pictures are taken from the screen, everything that is not a bench window blacked out.</li>
 * <li><b>quiet</b> - while a real drone is armed ({@link ArmedGuard}), or when asked: the windows are parked off the
 * screen and the same presses are put into the app's own event queue at the same spots; pictures are painted by the
 * windows.</li>
 * </ul>
 * Keys are always put into the app's event queue, never typed on the real keyboard: a key press goes to whatever
 * program has the focus, and that could be the pilot's.
 */
public final class Ui {
    public enum Mode { DESKTOP, QUIET }

    /** A control that could not be pressed the way a pilot would: hidden, greyed, off the window, or covered. */
    public static final class NotPressable extends AssertionError {
        public NotPressable(String message) {
            super(message);
        }
    }

    /** How far off the screen quiet windows are parked. */
    static final int PARK = -30000;
    private static final long SETTLE_MS = 60;

    private final Film film;
    private final Census census;
    private final Robot robot;
    private final boolean fixed;
    private volatile Mode mode;
    private volatile long modeCheckedMs;
    private volatile boolean autoShot = true;
    private final java.awt.event.AWTEventListener placer = this::windowEvent;

    public Ui(Film film, Census census) {
        this.film = film;
        this.census = census;
        Robot r = null;
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                r = new Robot();
                r.setAutoDelay(0);
            } catch (Exception e) {
                r = null;
            }
        }
        robot = r;
        String asked = System.getProperty("bench.mode", "auto");
        fixed = asked.equals("desktop") || asked.equals("quiet") || r == null;
        mode = r == null || asked.equals("quiet") ? Mode.QUIET : asked.equals("desktop") ? Mode.DESKTOP : ArmedGuard.handsOff() ? Mode.QUIET : Mode.DESKTOP;
        Toolkit.getDefaultToolkit().addAWTEventListener(placer, AWTEvent.WINDOW_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK);
    }

    public Mode mode() {
        return mode;
    }

    public void autoShot(boolean on) {
        autoShot = on;
    }

    public void close() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(placer);
    }

    // ------------------------------------------------------------------ where the windows are

    /** The app's frames and dialogs, the one in front first: each goes to the front when it opens or is activated. */
    private final List<Window> zOrder = new ArrayList<>();

    private void windowEvent(AWTEvent e) {
        if (!(e.getSource() instanceof Window w)) return;
        int id = e.getID();
        if (id == WindowEvent.WINDOW_OPENED || id == java.awt.event.ComponentEvent.COMPONENT_SHOWN) place(w);
        if (id == WindowEvent.WINDOW_OPENED || id == java.awt.event.ComponentEvent.COMPONENT_SHOWN || id == WindowEvent.WINDOW_ACTIVATED
                || id == WindowEvent.WINDOW_GAINED_FOCUS) {
            synchronized (zOrder) {
                zOrder.remove(w);
                zOrder.add(0, w);
            }
        } else if (id == WindowEvent.WINDOW_CLOSED || id == java.awt.event.ComponentEvent.COMPONENT_HIDDEN) {
            synchronized (zOrder) {
                zOrder.remove(w);
            }
        }
    }

    /** How far in front a window is: 0 = the front one. Pop-ups (menus, drop-downs, tooltips) are in front of every frame and dialog. */
    private int depth(Window w) {
        if (!(w instanceof Frame) && !(w instanceof Dialog)) return -1;
        synchronized (zOrder) {
            int i = zOrder.indexOf(w);
            return i < 0 ? Integer.MAX_VALUE : i;
        }
    }

    /** Brings a window of the app in front of its others, as a pilot does by clicking it or its taskbar button. */
    public void front(Window w) {
        edt(() -> {
            w.toFront();
            w.requestFocus();
            synchronized (zOrder) {
                zOrder.remove(w);
                zOrder.add(0, w);
            }
            return null;
        });
        settle();
        if (autoShot) shot("bring to the front: " + (w instanceof Frame f ? f.getTitle() : w instanceof Dialog d ? d.getTitle() : w.getClass().getSimpleName()), null);
    }

    private void place(Window w) {
        try {
            if (mode == Mode.DESKTOP) {
                if (w.getX() < PARK / 2) w.setLocation(w.getX() - PARK, w.getY());
                if (!w.isAlwaysOnTop() && w.isAlwaysOnTopSupported()) w.setAlwaysOnTop(true);
            } else {
                if (w.isAlwaysOnTop()) w.setAlwaysOnTop(false);
                if (w.getX() > PARK / 2) w.setLocation(w.getX() + PARK, w.getY());
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** A real drone armed means hands off the mouse and the screen - looked at before every step, once a second at most. */
    private void checkMode() {
        if (fixed) return;
        long now = System.currentTimeMillis();
        if (now - modeCheckedMs < 1000) return;
        modeCheckedMs = now;
        Mode want = ArmedGuard.handsOff() ? Mode.QUIET : Mode.DESKTOP;
        if (want == mode) return;
        mode = want;
        edt(() -> {
            for (Window w : Window.getWindows()) if (w.isShowing()) place(w);
            return null;
        });
        film.remark(want == Mode.QUIET ? "A real drone is armed: the bench let go of the mouse and the screen (quiet mode)."
                : "No real drone armed any more: back on the screen (desktop mode).");
        settle();
    }

    // ------------------------------------------------------------------ Swing thread helpers

    public static <T> T edt(Callable<T> c) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return c.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(c.call());
                } catch (Throwable t) {
                    error.set(t);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (error.get() instanceof RuntimeException r) throw r;
        if (error.get() instanceof Error r) throw r;
        if (error.get() != null) throw new IllegalStateException(error.get());
        return result.get();
    }

    /** Waits until what was just done has been taken in: the event queue run through twice, and a moment for repaints. */
    public void settle() {
        if (SwingUtilities.isEventDispatchThread()) return;
        for (int i = 0; i < 2; i++) edt(() -> null);
        pause(SETTLE_MS);
        edt(() -> null);
    }

    public static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ finding

    /** The showing windows of the app, the ones in front first: pop-ups, then dialogs, then frames; newer before older. */
    public List<Window> windows() {
        return edt(() -> {
            List<Window> all = new ArrayList<>();
            for (Window w : Window.getWindows()) if (w.isShowing() && w.getWidth() > 0 && w.getHeight() > 0) all.add(w);
            Collections.reverse(all); // getWindows is oldest first
            List<Window> ordered = new ArrayList<>();
            for (Window w : all) if (!(w instanceof Frame) && !(w instanceof Dialog)) ordered.add(w);
            List<Window> rest = new ArrayList<>();
            for (Window w : all) if (w instanceof Frame || w instanceof Dialog) rest.add(w);
            rest.sort(java.util.Comparator.comparingInt(this::depth)); // stable: never-activated ones keep newest first
            ordered.addAll(rest);
            return ordered;
        });
    }

    /** The showing window whose title holds these words; null if none. */
    public Window window(String titlePart) {
        for (Window w : windows()) {
            String title = w instanceof Frame f ? f.getTitle() : w instanceof Dialog d ? d.getTitle() : null;
            if (title != null && title.toLowerCase().contains(titlePart.toLowerCase())) return w;
        }
        return null;
    }

    /** A modal dialog is up: only it (and its pop-ups) can be pressed. */
    private Dialog modal() {
        for (Window w : windows()) if (w instanceof Dialog d && d.isModal()) return d;
        return null;
    }

    /**
     * What a pilot could see: visible, and not inside something folded to nothing. The app folds parts of its cards
     * away (Fold: height 0, its content still "visible" and "showing" to Swing) - the bench's update scenario took a
     * folded-away UPDATE PHONE APP and an old "Old phone app" line as still there (2026-11-21).
     */
    static void collect(Component c, List<Component> into) {
        if (!c.isVisible()) return;
        if (!(c instanceof Window) && (c.getWidth() <= 0 || c.getHeight() <= 0)) return;
        into.add(c);
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, into);
    }

    /** Every showing part of every showing window, front windows first. */
    public List<Component> components() {
        List<Window> ws = windows();
        return edt(() -> {
            List<Component> all = new ArrayList<>();
            for (Window w : ws) collect(w, all);
            all.removeIf(c -> !c.isShowing());
            for (Component c : all) census.see(c);
            return all;
        });
    }

    /** The words on a control, as a pilot reads them: tags and line breaks gone. Null for what carries no words. */
    public static String textOf(Component c) {
        String t = c instanceof AbstractButton b ? b.getText() : c instanceof JLabel l ? l.getText()
                : c instanceof JTextComponent x ? x.getText() : null;
        return t == null ? null : norm(t);
    }

    public static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]*>", "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
    }

    private static boolean says(Component c, String words) {
        String t = textOf(c);
        if (t != null && t.equalsIgnoreCase(words)) return true;
        return (t == null || t.isEmpty()) && c instanceof JComponent j && j.getToolTipText() != null && norm(j.getToolTipText()).equalsIgnoreCase(words);
    }

    /** All showing controls with these words (or, wordless ones, this tooltip): buttons and menu rows first, then anything. */
    public List<Component> all(String words) {
        String want = norm(words);
        List<Component> buttons = new ArrayList<>(), others = new ArrayList<>();
        for (Component c : components()) {
            if (c instanceof JTextComponent) continue; // a field is found by what is beside it, not by what is typed in it
            if (!says(c, want)) continue;
            (c instanceof AbstractButton ? buttons : others).add(c);
        }
        buttons.addAll(others);
        if (buttons.isEmpty()) {
            // a menu row carries its title and a line of explanation: found by its title alone
            String start = want.toLowerCase() + " ";
            for (Component c : components()) {
                if (!(c instanceof AbstractButton)) continue;
                String t = edt(() -> textOf(c));
                if (t != null && t.toLowerCase().startsWith(start)) buttons.add(c);
            }
        }
        return buttons;
    }

    /** The control with these words; a failure that lists what is there when there is none. */
    public Component find(String words) {
        List<Component> found = all(words);
        if (found.isEmpty()) throw new NotPressable("Nothing showing says \"" + words + "\". Showing: " + buttons());
        return found.get(0);
    }

    public boolean has(String words) {
        return !all(words).isEmpty();
    }

    /** True when a showing label, button or field holds these words somewhere in its text. */
    public boolean sees(String part) {
        String p = part.toLowerCase();
        for (String t : texts()) if (t.toLowerCase().contains(p)) return true;
        return false;
    }

    /** Everything written on the showing windows, front first. */
    public List<String> texts() {
        List<String> out = new ArrayList<>();
        for (Component c : components()) {
            String t = edt(() -> textOf(c));
            if (t != null && !t.isBlank()) out.add(t);
        }
        return out;
    }

    /** The words on every showing button and menu row (greyed ones in brackets). */
    public List<String> buttons() {
        List<String> out = new ArrayList<>();
        for (Component c : components()) {
            if (!(c instanceof AbstractButton b)) continue;
            String t = edt(() -> textOf(b));
            if (t == null || t.isBlank()) t = edt(() -> b.getToolTipText() == null ? "" : "(" + norm(b.getToolTipText()) + ")");
            if (!t.isBlank()) out.add(b.isEnabled() ? t : "[" + t + "]");
        }
        return out;
    }

    public <T extends Component> List<T> all(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Component c : components()) if (type.isInstance(c)) out.add(type.cast(c));
        return out;
    }

    public <T extends Component> T first(Class<T> type) {
        List<T> found = all(type);
        if (found.isEmpty()) throw new NotPressable("No showing " + type.getSimpleName());
        return found.get(0);
    }

    public <T extends Component> T first(Class<T> type, Predicate<T> which) {
        for (T c : all(type)) if (edt(() -> which.test(c))) return c;
        throw new NotPressable("No showing " + type.getSimpleName() + " of that kind");
    }

    /** The windows and their parts as text: for the bench's author, and kept beside a failure. */
    public String tree() {
        StringBuilder s = new StringBuilder();
        for (Window w : windows()) {
            String title = w instanceof Frame f ? f.getTitle() : w instanceof Dialog d ? d.getTitle() : "";
            s.append(w.getClass().getSimpleName()).append(" \"").append(title).append("\" ").append(w.getBounds()).append('\n');
            edt(() -> {
                dump(w, 1, s);
                return null;
            });
        }
        return s.toString();
    }

    private static void dump(Component c, int depth, StringBuilder s) {
        if (!c.isVisible()) return;
        String t = textOf(c);
        String tip = c instanceof JComponent j ? j.getToolTipText() : null;
        boolean worth = c instanceof AbstractButton || c instanceof JTextComponent || c instanceof javax.swing.JComboBox || (t != null && !t.isBlank())
                || !c.getClass().getName().startsWith("javax.swing.");
        if (worth) {
            s.append("  ".repeat(depth)).append(c.getClass().getSimpleName().isEmpty() ? c.getClass().getName() : c.getClass().getSimpleName());
            if (t != null && !t.isBlank()) s.append(" \"").append(t.length() > 80 ? t.substring(0, 80) + "..." : t).append('"');
            if (tip != null) s.append(" tip=\"").append(norm(tip)).append('"');
            if (!c.isEnabled()) s.append(" GREYED");
            s.append(" ").append(c.getWidth()).append('x').append(c.getHeight()).append('\n');
        }
        if (c instanceof Container k) for (Component x : k.getComponents()) dump(x, worth ? depth + 1 : depth, s);
    }

    // ------------------------------------------------------------------ pressing

    /** Presses the control with these words. */
    public void click(String words) {
        Component c = find(words);
        press(c, null, MouseEvent.BUTTON1, 1, "click \"" + words + "\"");
    }

    /** Presses the n-th (0 = first) of several controls with the same words. */
    public void click(String words, int nth) {
        List<Component> found = all(words);
        if (found.size() <= nth) throw new NotPressable("Only " + found.size() + " controls say \"" + words + "\"");
        press(found.get(nth), null, MouseEvent.BUTTON1, 1, "click \"" + words + "\" #" + (nth + 1));
    }

    public void click(Component c) {
        press(c, null, MouseEvent.BUTTON1, 1, "click " + describe(c));
    }

    /** Presses at a spot of a component (its own coordinates): a map, a picture, a row painted by the app. */
    public void clickAt(Component c, int x, int y) {
        press(c, new Point(x, y), MouseEvent.BUTTON1, 1, "click " + describe(c) + " at " + x + "," + y);
    }

    public void doubleClickAt(Component c, int x, int y) {
        press(c, new Point(x, y), MouseEvent.BUTTON1, 2, "double-click " + describe(c) + " at " + x + "," + y);
    }

    public void rightClickAt(Component c, int x, int y) {
        press(c, new Point(x, y), MouseEvent.BUTTON3, 1, "right-click " + describe(c) + " at " + x + "," + y);
    }

    public void rightClick(Component c) {
        press(c, null, MouseEvent.BUTTON3, 1, "right-click " + describe(c));
    }

    static String describe(Component c) {
        String t = edt(() -> textOf(c));
        String name = c.getClass().getSimpleName().isEmpty() ? c.getClass().getName() : c.getClass().getSimpleName();
        return t == null || t.isBlank() || c instanceof JTextComponent ? name : name + " \"" + (t.length() > 40 ? t.substring(0, 40) + "..." : t) + "\"";
    }

    /** Why this control can't be pressed by a pilot right now; null when it can. at: a spot in it, or null for its middle. */
    public String whyNotPressable(Component c, Point at) {
        return edt(() -> {
            if (!c.isShowing()) return "it is not showing";
            if (!c.isEnabled()) return "it is greyed";
            Window w = c instanceof Window x ? x : SwingUtilities.getWindowAncestor(c);
            if (w == null) return "it is in no window";
            Dialog blocking = modalNoEdt();
            if (blocking != null && w != blocking && !ownedBy(w, blocking)) return "the dialog \"" + blocking.getTitle() + "\" is in front";
            Point p = spot(c, at);
            if (p == null) return "it is outside what its window shows (scrolled or cut off)";
            Point onScreen = new Point(p);
            SwingUtilities.convertPointToScreen(onScreen, c);
            int mine = depth(w);
            for (Window other : Window.getWindows()) { // another window of the app lies over that spot
                if (other == w || !other.isShowing() || ownedBy(w, other) && mine < 0) continue;
                if (depth(other) < mine && other.getBounds().contains(onScreen)) {
                    return "the window \"" + (other instanceof Frame f ? f.getTitle() : other instanceof Dialog d ? d.getTitle() : other.getClass().getSimpleName())
                            + "\" is in front of it";
                }
            }
            Point inWindow = SwingUtilities.convertPoint(c, p, w);
            Component top = SwingUtilities.getDeepestComponentAt(w, inWindow.x, inWindow.y);
            if (top == null) return "its spot is outside its window";
            // Swing gives a press to the deepest part at that spot that listens to the mouse; what only paints is seen through
            Component target = top;
            while (target != null && !takesMouse(target)) target = target.getParent();
            if (target == null || target == c || SwingUtilities.isDescendingFrom(target, c)) return null;
            if (SwingUtilities.isDescendingFrom(c, target) && !takesMouse(c)) return null; // c is a passive part of what takes the press
            return "it is covered by " + describeNoEdt(top);
        });
    }

    private static boolean takesMouse(Component c) {
        return c.getMouseListeners().length > 0 || c.getMouseMotionListeners().length > 0 || c.getMouseWheelListeners().length > 0;
    }

    private static String describeNoEdt(Component c) {
        String t = textOf(c);
        String name = c.getClass().getSimpleName().isEmpty() ? c.getClass().getName() : c.getClass().getSimpleName();
        return t == null || t.isBlank() ? name : name + " \"" + t + "\"";
    }

    private static Dialog modalNoEdt() {
        Dialog found = null;
        for (Window w : Window.getWindows()) if (w.isShowing() && w instanceof Dialog d && d.isModal()) found = d; // the newest
        return found;
    }

    private static boolean ownedBy(Window w, Window owner) {
        for (Window o = w.getOwner(); o != null; o = o.getOwner()) if (o == owner) return true;
        return false;
    }

    /** The spot to press, in the component's coordinates: the given one, or the middle of what shows of it. Null if nothing shows. */
    private static Point spot(Component c, Point at) {
        Rectangle visible = c instanceof JComponent j ? j.getVisibleRect() : new Rectangle(0, 0, c.getWidth(), c.getHeight());
        if (visible.isEmpty()) return null;
        if (at != null) return visible.contains(at) ? at : null;
        return new Point(visible.x + visible.width / 2, visible.y + visible.height / 2);
    }

    private void press(Component c, Point at, int button, int clicks, String action) {
        checkMode();
        settle();
        if (c instanceof JComponent j && at == null) { // a control scrolled out of sight is scrolled to, as a pilot would
            edt(() -> {
                if (j.getVisibleRect().isEmpty() && j.getWidth() > 0) j.scrollRectToVisible(new Rectangle(0, 0, j.getWidth(), j.getHeight()));
                return null;
            });
            settle();
        }
        String why = whyNotPressable(c, at);
        for (int i = 0; i < 20 && why != null && !why.equals("it is greyed"); i++) { // a menu sliding out, a card unfolding: a pilot waits too
            pause(100);
            why = whyNotPressable(c, at);
        }
        if (why != null) {
            shot("NOT PRESSABLE: " + action + " - " + why);
            throw new NotPressable("Can't " + action + ": " + why);
        }
        Point local = edt(() -> spot(c, at));
        Point screen = edt(() -> {
            Point p = new Point(local);
            SwingUtilities.convertPointToScreen(p, c);
            return p;
        });
        census.used(c);
        String note = null;
        boolean real = mode == Mode.DESKTOP && robot != null;
        if (real && !screenShows(c, screen)) {
            real = false;
            note = "the screen did not show the control at that spot: pressed through the app's event queue";
        }
        if (real && !robotPress(screen, button, clicks)) {
            real = false;
            note = "the pointer was moved by someone else: pressed through the app's event queue";
        }
        if (!real) postPress(c, local, button, clicks);
        settle();
        if (autoShot) shot(action, note);
    }

    /** Before the real mouse goes down: the screen at that spot shows what the app paints there - not another program's window. */
    private boolean screenShows(Component c, Point screen) {
        Window w = c instanceof Window x ? x : SwingUtilities.getWindowAncestor(c);
        // a patch around the spot, compared by its average colours: letters are drawn a little differently on a
        // scaled screen than into a picture, but a patch of the same control has the same mix of colours
        Rectangle whole = new Rectangle(edt(w::getLocationOnScreen), w.getSize());
        Rectangle r = new Rectangle(screen.x - 16, screen.y - 8, 32, 16).intersection(whole);
        if (r.isEmpty()) return false;
        for (int attempt = 0; attempt < 4; attempt++) {
            BufferedImage painted = edt(() -> {
                BufferedImage img = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                Point origin = w.getLocationOnScreen();
                g.translate(origin.x - r.x, origin.y - r.y);
                w.paint(g);
                g.dispose();
                return img;
            });
            BufferedImage shown = robot.createScreenCapture(r);
            boolean same = true;
            int half = Math.max(1, r.width / 2);
            for (int side = 0; side < 2 && same; side++) { // the left and the right half each: not only the patch as a whole
                long[] a = new long[3], b = new long[3];
                int from = side * half, to = side == 0 ? half : r.width;
                for (int y = 0; y < r.height; y++) {
                    for (int x = from; x < to; x++) {
                        int p = painted.getRGB(x, y), s = shown.getRGB(x, y);
                        a[0] += p >> 16 & 255;
                        a[1] += p >> 8 & 255;
                        a[2] += p & 255;
                        b[0] += s >> 16 & 255;
                        b[1] += s >> 8 & 255;
                        b[2] += s & 255;
                    }
                }
                long n = (long) Math.max(1, to - from) * r.height;
                for (int ch = 0; ch < 3; ch++) if (Math.abs(a[ch] - b[ch]) / n > 30) same = false;
            }
            if (same) return true;
            pause(200);
        }
        return false;
    }

    private boolean robotPress(Point screen, int button, int clicks) {
        int mask = button == MouseEvent.BUTTON3 ? InputEvent.BUTTON3_DOWN_MASK : button == MouseEvent.BUTTON2 ? InputEvent.BUTTON2_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
        for (int attempt = 0; attempt < 3; attempt++) {
            robot.mouseMove(screen.x, screen.y);
            robot.delay(60);
            PointerInfo pointer = MouseInfo.getPointerInfo();
            if (pointer == null) return false;
            Point is = pointer.getLocation();
            if (Math.abs(is.x - screen.x) > 2 || Math.abs(is.y - screen.y) > 2) {
                pause(300); // someone's hand is on the mouse: once more in a moment
                continue;
            }
            for (int i = 0; i < clicks; i++) {
                robot.mousePress(mask);
                robot.delay(35);
                robot.mouseRelease(mask);
                robot.delay(35);
            }
            return true;
        }
        return false;
    }

    /** The same press as events in the app's own queue, entering at the window as the operating system's do. */
    private void postPress(Component c, Point local, int button, int clicks) {
        Window w = edt(() -> c instanceof Window x ? x : SwingUtilities.getWindowAncestor(c));
        Point p = edt(() -> SwingUtilities.convertPoint(c, local, w));
        int down = button == MouseEvent.BUTTON3 ? InputEvent.BUTTON3_DOWN_MASK : button == MouseEvent.BUTTON2 ? InputEvent.BUTTON2_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
        post(w, MouseEvent.MOUSE_MOVED, p, 0, 0, MouseEvent.NOBUTTON, false);
        for (int i = 1; i <= clicks; i++) {
            post(w, MouseEvent.MOUSE_PRESSED, p, down, i, button, false);
            post(w, MouseEvent.MOUSE_RELEASED, p, 0, i, button, button == MouseEvent.BUTTON3);
            post(w, MouseEvent.MOUSE_CLICKED, p, 0, i, button, false);
        }
    }

    private static void post(Window w, int id, Point p, int modifiers, int clicks, int button, boolean popup) {
        Point abs = new Point(p);
        try {
            Point origin = w.getLocationOnScreen();
            abs.translate(origin.x, origin.y);
        } catch (RuntimeException ignored) {
        }
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                new MouseEvent(w, id, System.currentTimeMillis(), modifiers, p.x, p.y, abs.x, abs.y, clicks, popup, button));
    }

    /** Presses, moves and lets go: from one spot of a component to another, in steps. */
    public void drag(Component c, int x1, int y1, int x2, int y2) {
        checkMode();
        settle();
        String action = "drag " + describe(c) + " " + x1 + "," + y1 + " -> " + x2 + "," + y2;
        String why = whyNotPressable(c, new Point(x1, y1));
        if (why != null) {
            shot("NOT PRESSABLE: " + action + " - " + why);
            throw new NotPressable("Can't " + action + ": " + why);
        }
        Point from = edt(() -> {
            Point p = new Point(x1, y1);
            SwingUtilities.convertPointToScreen(p, c);
            return p;
        });
        int steps = Math.max(4, (int) (Math.hypot(x2 - x1, y2 - y1) / 12));
        String note = null;
        if (mode == Mode.DESKTOP && robot != null && screenShows(c, from)) {
            robot.mouseMove(from.x, from.y);
            robot.delay(60);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int i = 1; i <= steps; i++) {
                robot.mouseMove(from.x + (x2 - x1) * i / steps, from.y + (y2 - y1) * i / steps);
                robot.delay(12);
            }
            robot.delay(40);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } else {
            if (mode == Mode.DESKTOP) note = "the screen did not show the spot: dragged through the app's event queue";
            Window w = edt(() -> SwingUtilities.getWindowAncestor(c));
            Point a = edt(() -> SwingUtilities.convertPoint(c, new Point(x1, y1), w));
            post(w, MouseEvent.MOUSE_MOVED, a, 0, 0, MouseEvent.NOBUTTON, false);
            post(w, MouseEvent.MOUSE_PRESSED, a, InputEvent.BUTTON1_DOWN_MASK, 1, MouseEvent.BUTTON1, false);
            for (int i = 1; i <= steps; i++) {
                post(w, MouseEvent.MOUSE_DRAGGED, new Point(a.x + (x2 - x1) * i / steps, a.y + (y2 - y1) * i / steps), InputEvent.BUTTON1_DOWN_MASK, 0, MouseEvent.NOBUTTON, false);
            }
            post(w, MouseEvent.MOUSE_RELEASED, new Point(a.x + x2 - x1, a.y + y2 - y1), 0, 1, MouseEvent.BUTTON1, false);
        }
        settle();
        if (autoShot) shot(action, note);
    }

    /** Turns the mouse wheel over a spot of a component; negative = away from the pilot (zoom in on a map). */
    public void wheel(Component c, int x, int y, int notches) {
        checkMode();
        settle();
        Point screen = edt(() -> {
            Point p = new Point(x, y);
            SwingUtilities.convertPointToScreen(p, c);
            return p;
        });
        if (mode == Mode.DESKTOP && robot != null && screenShows(c, screen)) {
            robot.mouseMove(screen.x, screen.y);
            robot.delay(60);
            int one = notches < 0 ? -1 : 1;
            for (int i = 0; i < Math.abs(notches); i++) {
                robot.mouseWheel(one);
                robot.delay(40);
            }
        } else {
            Window w = edt(() -> SwingUtilities.getWindowAncestor(c));
            Point p = edt(() -> SwingUtilities.convertPoint(c, new Point(x, y), w));
            int one = notches < 0 ? -1 : 1;
            for (int i = 0; i < Math.abs(notches); i++) {
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new MouseWheelEvent(w, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                        p.x, p.y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, one));
            }
        }
        settle();
        if (autoShot) shot("wheel " + notches + " over " + describe(c), null);
    }

    /** Moves the pointer over a control without pressing (tooltips, hover states). */
    public void hover(Component c) {
        checkMode();
        Point local = edt(() -> spot(c, null));
        if (local == null) return;
        if (mode == Mode.DESKTOP && robot != null) {
            Point screen = edt(() -> {
                Point p = new Point(local);
                SwingUtilities.convertPointToScreen(p, c);
                return p;
            });
            robot.mouseMove(screen.x, screen.y);
        } else {
            Window w = edt(() -> SwingUtilities.getWindowAncestor(c));
            post(w, MouseEvent.MOUSE_MOVED, edt(() -> SwingUtilities.convertPoint(c, local, w)), 0, 0, MouseEvent.NOBUTTON, false);
        }
        settle();
    }

    /** A row of a list (a combo's drop-down, a table of names): pressed in the row's middle. */
    public void clickRow(JList<?> list, int index) {
        Rectangle cell = edt(() -> {
            list.ensureIndexIsVisible(index);
            return list.getCellBounds(index, index);
        });
        if (cell == null) throw new NotPressable("The list has no row " + index);
        settle();
        press(list, new Point(cell.x + cell.width / 2, cell.y + cell.height / 2), MouseEvent.BUTTON1, 1, "click row " + index + " of a list");
    }

    /** Opens a drop-down and picks the entry that reads like this. */
    public void choose(javax.swing.JComboBox<?> combo, String entry) {
        int index = edt(() -> {
            for (int i = 0; i < combo.getItemCount(); i++) if (norm(String.valueOf(combo.getItemAt(i))).equalsIgnoreCase(norm(entry))) return i;
            return -1;
        });
        if (index < 0) {
            throw new NotPressable("The list has no \"" + entry + "\": " + edt(() -> {
                List<String> items = new ArrayList<>();
                for (int i = 0; i < combo.getItemCount(); i++) items.add(String.valueOf(combo.getItemAt(i)));
                return items;
            }));
        }
        press(combo, null, MouseEvent.BUTTON1, 1, "open the list " + describe(combo));
        until("the drop-down of " + describe(combo), 3000, combo::isPopupVisible);
        @SuppressWarnings("rawtypes")
        JList list = null;
        for (int i = 0; i < 40 && list == null; i++) { // the drop-down's window is up a moment after the list says it is
            for (JList l : all(JList.class)) if (edt(() -> SwingUtilities.getAncestorOfClass(javax.swing.JPopupMenu.class, l) != null)) list = l;
            if (list == null) pause(50);
        }
        if (list == null) throw new NotPressable("The drop-down of " + describe(combo) + " did not show its list");
        clickRow(list, index);
    }

    /** Closes a window the way a pilot does: its X in the title bar (the app's own title bar has one; a plain dialog gets Windows' close). */
    public void closeWindow(Window w) {
        String title = w instanceof Frame f ? f.getTitle() : w instanceof Dialog d ? d.getTitle() : w.getClass().getSimpleName();
        Component x = edt(() -> {
            List<Component> parts = new ArrayList<>();
            collect(w, parts);
            for (Component c : parts) {
                if (!(c instanceof AbstractButton) || !c.isShowing()) continue;
                boolean inTitle = false;
                for (Component p = c.getParent(); p != null; p = p.getParent()) if (p.getClass().getName().contains("FlatTitlePane")) inTitle = true;
                javax.accessibility.AccessibleContext a = c.getAccessibleContext();
                if (inTitle && a != null && "Close".equalsIgnoreCase(a.getAccessibleName())) return c;
            }
            return null;
        });
        if (x != null) {
            press(x, null, MouseEvent.BUTTON1, 1, "close the window \"" + title + "\" by its X");
        } else {
            checkMode();
            SwingUtilities.invokeLater(() -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)));
            settle();
            if (autoShot) shot("close the window \"" + title + "\"", "no X of the app's own: Windows' close sent");
        }
    }

    // ------------------------------------------------------------------ keys

    /** A key pressed and let go, as the app's focused control gets it (modifiers: InputEvent.CTRL_DOWN_MASK ...). */
    public void key(int keyCode, int modifiers) {
        checkMode();
        settle();
        edt(() -> {
            // Esc with a menu open closes the menu: Swing's menus take that key through a helper of their own that a
            // key put into the queue does not reach, so the bench does what the helper does
            if (keyCode == KeyEvent.VK_ESCAPE && modifiers == 0 && javax.swing.MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return null;
            }
            Component target = keyTarget();
            char ch = KeyEvent.CHAR_UNDEFINED;
            if (modifiers == 0 || modifiers == InputEvent.SHIFT_DOWN_MASK) {
                if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) ch = (char) ((modifiers == 0 ? 'a' : 'A') + keyCode - KeyEvent.VK_A);
                else if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9 && modifiers == 0) ch = (char) ('0' + keyCode - KeyEvent.VK_0);
                else if (keyCode == KeyEvent.VK_SPACE) ch = ' ';
                else if (keyCode == KeyEvent.VK_ENTER) ch = '\n';
                else if (keyCode == KeyEvent.VK_ESCAPE) ch = 27;
            }
            long now = System.currentTimeMillis();
            send(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiers, keyCode, ch));
            if (ch != KeyEvent.CHAR_UNDEFINED) send(new KeyEvent(target, KeyEvent.KEY_TYPED, now, modifiers, KeyEvent.VK_UNDEFINED, ch));
            send(new KeyEvent(target, KeyEvent.KEY_RELEASED, now, modifiers, keyCode, ch));
            return null;
        });
        settle();
        if (autoShot) shot("key " + InputEvent.getModifiersExText(modifiers) + (modifiers == 0 ? "" : "+") + KeyEvent.getKeyText(keyCode), null);
    }

    public void key(int keyCode) {
        key(keyCode, 0);
    }

    /** Text typed into the control that has the focus (press it first). */
    public void type(String text) {
        checkMode();
        settle();
        edt(() -> {
            Component target = keyTarget();
            for (char ch : text.toCharArray()) send(new KeyEvent(target, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch));
            return null;
        });
        settle();
        if (autoShot) shot("type \"" + text + "\"", null);
    }

    /** Replaces what a field holds: pressed, everything selected, typed over. */
    public void fill(JTextComponent field, String text) {
        click(field);
        until("the field has the focus", 2000, field::isFocusOwner, false);
        if (!edt(field::isFocusOwner)) edt(() -> { // off the screen no window is active: the field is given the keys directly
            field.requestFocusInWindow();
            return null;
        });
        keyTo = field;
        try {
            boolean was = autoShot;
            autoShot = false;
            key(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK);
            autoShot = was;
            type(text);
        } finally {
            keyTo = null;
        }
    }

    private volatile Component keyTo;

    private Component keyTarget() {
        Component to = keyTo;
        if (to != null && to.isShowing()) return to;
        // an open menu takes the keys (Esc closes it, the arrows walk it)
        javax.swing.MenuElement[] path = javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
        if (path.length > 0 && path[path.length - 1].getComponent() != null && path[path.length - 1].getComponent().isShowing()) {
            return path[path.length - 1].getComponent();
        }
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null) return owner;
        // no window of the app is active (parked off the screen, or the pilot is working in another program): the
        // front window's content takes the keys, as it would with the focus
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w instanceof Dialog d && d.isModal()) return most(w);
        }
        Window front = null;
        for (Window w : Window.getWindows()) if (w.isShowing() && (w instanceof Frame || w instanceof Dialog)) front = w;
        return front == null ? null : most(front);
    }

    private static Component most(Window w) {
        Component recent = w.getMostRecentFocusOwner();
        if (recent != null && recent.isShowing()) return recent;
        return w instanceof javax.swing.RootPaneContainer r ? r.getRootPane() : w;
    }

    private static void send(KeyEvent e) {
        Component target = (Component) e.getSource();
        if (target == null) return;
        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        if (kfm.getFocusOwner() == target) target.dispatchEvent(e); // the usual way in: dispatchers, the focus owner, key bindings
        else kfm.redispatchEvent(target, e);                         // no focus: straight to the control and its key bindings
    }

    // ------------------------------------------------------------------ waiting

    /** Waits until it is so (looked at on the Swing thread); fails with a picture of how it was instead. */
    public void until(String what, long ms, BooleanSupplier ok) {
        until(what, ms, ok, true);
    }

    private boolean until(String what, long ms, BooleanSupplier ok, boolean fail) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            boolean is;
            try {
                is = edt(ok::getAsBoolean);
            } catch (RuntimeException e) {
                is = false;
            }
            if (is) return true;
            if (System.currentTimeMillis() > end) {
                if (!fail) return false;
                shot("TIMED OUT after " + ms + " ms waiting for " + what);
                throw new AssertionError("Timed out after " + ms + " ms waiting for " + what + ". Showing: " + texts());
            }
            pause(40);
        }
    }

    /** Waits until something showing says exactly this (a button, a label). */
    public Component waitForControl(String words, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            List<Component> found = all(words);
            if (!found.isEmpty()) return found.get(0);
            if (System.currentTimeMillis() > end) {
                shot("TIMED OUT after " + ms + " ms waiting for \"" + words + "\"");
                throw new AssertionError("Timed out after " + ms + " ms waiting for \"" + words + "\". Showing: " + buttons());
            }
            pause(60);
        }
    }

    /** Waits until these words show somewhere (part of a label, a log line on screen). */
    public void waitForText(String part, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!sees(part)) {
            if (System.currentTimeMillis() > end) {
                shot("TIMED OUT after " + ms + " ms waiting for the words \"" + part + "\"");
                throw new AssertionError("Timed out after " + ms + " ms waiting for the words \"" + part + "\". Showing: " + texts());
            }
            pause(80);
        }
    }

    // ------------------------------------------------------------------ pictures

    /** A step in the film with a picture of the app as it is now. */
    public Film.Step shot(String action) {
        return shot(action, null);
    }

    Film.Step shot(String action, String note) {
        BufferedImage picture;
        try {
            picture = picture();
        } catch (RuntimeException e) {
            picture = null;
            note = (note == null ? "" : note + "; ") + "no picture: " + e;
        }
        Film.Step step = film.add(action, picture, mode.name().toLowerCase(), note);
        try {
            List<Window> ws = windows();
            boolean onScreen = mode == Mode.DESKTOP;
            for (String seen : edt(() -> look.inspect(ws, onScreen))) film.look(step.number(), seen);
        } catch (RuntimeException ignored) {
        }
        return step;
    }

    private final Look look = new Look();

    /**
     * The app's showing windows as one picture, placed as they are on the screen. Desktop: from the screen itself -
     * what a pilot's eyes get - with everything that is not a bench window blacked out. Quiet: painted by the windows.
     */
    public BufferedImage picture() {
        List<Window> ws = windows();
        if (ws.isEmpty()) return null;
        List<Rectangle> rects = edt(() -> {
            List<Rectangle> r = new ArrayList<>();
            for (Window w : ws) r.add(new Rectangle(w.getLocationOnScreen(), w.getSize()));
            return r;
        });
        Rectangle box = new Rectangle(rects.get(0));
        for (Rectangle r : rects) box = box.union(r);
        if (mode == Mode.DESKTOP && robot != null) {
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            Rectangle visible = box.intersection(screen);
            if (!visible.isEmpty()) {
                BufferedImage shot = robot.createScreenCapture(visible);
                BufferedImage out = new BufferedImage(visible.width, visible.height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = out.createGraphics();
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, out.getWidth(), out.getHeight());
                for (Rectangle r : rects) { // only the bench's windows are kept: nothing of the pilot's desktop goes into a picture
                    Rectangle part = r.intersection(visible);
                    if (part.isEmpty()) continue;
                    g.drawImage(shot.getSubimage(part.x - visible.x, part.y - visible.y, part.width, part.height), part.x - visible.x, part.y - visible.y, null);
                }
                g.dispose();
                return out;
            }
        }
        return painted(ws, rects, box);
    }

    /** The windows painted by themselves into one picture (the same in both modes: what the comparison with approved pictures uses). */
    public BufferedImage painted() {
        List<Window> ws = windows();
        if (ws.isEmpty()) return null;
        List<Rectangle> rects = edt(() -> {
            List<Rectangle> r = new ArrayList<>();
            for (Window w : ws) r.add(new Rectangle(w.getLocationOnScreen(), w.getSize()));
            return r;
        });
        Rectangle box = new Rectangle(rects.get(0));
        for (Rectangle r : rects) box = box.union(r);
        return painted(ws, rects, box);
    }

    private static BufferedImage painted(List<Window> ws, List<Rectangle> rects, Rectangle box) {
        BufferedImage out = new BufferedImage(Math.max(1, box.width), Math.max(1, box.height), BufferedImage.TYPE_INT_RGB);
        return edt(() -> {
            Graphics2D g = out.createGraphics();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            for (int i = ws.size() - 1; i >= 0; i--) { // the windows behind first
                Rectangle r = rects.get(i);
                Graphics2D wg = (Graphics2D) g.create(r.x - box.x, r.y - box.y, r.width, r.height);
                try {
                    ws.get(i).paint(wg);
                } catch (RuntimeException ignored) {
                } finally {
                    wg.dispose();
                }
            }
            g.dispose();
            return out;
        });
    }

    /** One component painted by itself. */
    public static BufferedImage paint(Component c) {
        return edt(() -> {
            BufferedImage img = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            c.paint(g);
            g.dispose();
            return img;
        });
    }
}
