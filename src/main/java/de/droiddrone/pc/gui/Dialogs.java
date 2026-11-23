package de.droiddrone.pc.gui;

import javax.swing.Icon;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Dimension;

/**
 * JOptionPane's dialogs, always showing their buttons (1.0.56, the pilot's screenshot of RESET SETUP: the text, then
 * nothing). An HTML message measures shorter before its first layout than after it (102 px, then 136 px for the reset
 * question), so the dialog packed once was too short and the button row fell off its bottom. Here every dialog is
 * packed until its size stays put. Same arguments and answers as JOptionPane's show...Dialog methods, which the app
 * uses only through these.
 */
final class Dialogs {
    private Dialogs() {
    }

    /** The dialog for a pane, sized to show all of it; not shown yet. */
    static JDialog dialog(Component parent, JOptionPane pane, String title) {
        JDialog d = pane.createDialog(parent, title);
        // 1.0.64: modal to its own window only - a box left open on the map window (Go to, Place at, a NOTAM paste, an
        // export's "Saved") blocked the main window too: STANDBY MODE, LOST DRONE and IT'S DOWN took no click
        if (parent != null) d.setModalityType(java.awt.Dialog.ModalityType.DOCUMENT_MODAL);
        fit(d, parent);
        return d;
    }

    /**
     * Packs until the size stops changing (a few times at most), then never smaller than that. Every size is asked
     * again each time: the cached ones are the HTML message's short first measure.
     */
    static void fit(JDialog d, Component near) {
        repack(d);
        // never wider or higher than the screen it is on: "Report saved:" with a long folder's path was 1208 px wide,
        // its right end and half its OK off a 1093-wide screen (F-031)
        java.awt.Rectangle screen = ScreenFit.usable(near instanceof java.awt.Window w ? w : near != null ? javax.swing.SwingUtilities.getWindowAncestor(near) : d.getOwner());
        if (screen != null && (d.getWidth() > screen.width || d.getHeight() > screen.height)) {
            d.setSize(Math.min(d.getWidth(), screen.width), Math.min(d.getHeight(), screen.height));
        }
        d.setMinimumSize(d.getSize());
        d.setLocationRelativeTo(near != null ? near : d.getOwner());
        ScreenFit.clampToScreen(d);
    }

    /** pack() for a window whose HTML text changes: until its size stays put (a few times at most). */
    static void repack(java.awt.Window w) {
        Dimension last = null;
        for (int i = 0; i < 4; i++) {
            invalidateAll(w);
            w.pack();
            Dimension now = w.getSize();
            if (now.equals(last)) break;
            last = now;
        }
    }

    private static void invalidateAll(Component c) {
        c.invalidate();
        if (c instanceof java.awt.Container k) for (Component x : k.getComponents()) invalidateAll(x);
    }

    /** The tests' answers in place of the pilot's: (message, title) to the answer (a button's number or option, the input; null: shut). */
    interface Answers {
        Object answer(Object message, String title);
    }

    /** Tests only: every dialog is answered by this, none shown. null (always in the app): shown. */
    static volatile Answers testAnswers;

    /** Shows it (modal) and lets it go. */
    private static void show(Component parent, JOptionPane pane, String title) {
        Answers a = testAnswers;
        if (a != null) {
            Object v = a.answer(pane.getMessage(), title);
            if (pane.getWantsInput()) {
                if (v != null) pane.setInputValue(v);
                pane.setValue(v == null ? null : JOptionPane.OK_OPTION);
            } else {
                pane.setValue(v);
            }
            return;
        }
        JDialog d = dialog(parent, pane, title);
        pane.selectInitialValue();
        d.setVisible(true);
        d.dispose();
    }

    // ------------------------------------------------------------------ as JOptionPane.showMessageDialog

    static void message(Component parent, Object message) {
        message(parent, message, UIManagerText.MESSAGE, JOptionPane.INFORMATION_MESSAGE);
    }

    static void message(Component parent, Object message, String title, int messageType) {
        message(parent, message, title, messageType, null);
    }

    static void message(Component parent, Object message, String title, int messageType, Icon icon) {
        show(parent, new JOptionPane(message, messageType, JOptionPane.DEFAULT_OPTION, icon), title);
    }

    /** I-009: the choice every saved file ends with (REPORT, a flight's EXPORT, the map's EXPORT); OK the default. */
    static final Object[] SAVED_OPTIONS = {"Open folder", "OK"};

    /** What was saved and where, with SAVED_OPTIONS: "Open folder" opens the folder. */
    static void saved(Component parent, Object message, String title, java.nio.file.Path folder) {
        if (option(parent, message, title, JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                SAVED_OPTIONS, SAVED_OPTIONS[1]) != 0) return;
        try {
            de.droiddrone.pc.Outside.open(folder.toFile());
        } catch (Exception ex) {
            message(parent, "Can't open the folder: " + ex.getMessage(), title, JOptionPane.WARNING_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ as JOptionPane.showConfirmDialog

    static int confirm(Component parent, Object message) {
        return confirm(parent, message, UIManagerText.SELECT, JOptionPane.YES_NO_CANCEL_OPTION);
    }

    static int confirm(Component parent, Object message, String title, int optionType) {
        return confirm(parent, message, title, optionType, JOptionPane.QUESTION_MESSAGE);
    }

    static int confirm(Component parent, Object message, String title, int optionType, int messageType) {
        return confirm(parent, message, title, optionType, messageType, null);
    }

    static int confirm(Component parent, Object message, String title, int optionType, int messageType, Icon icon) {
        return option(parent, message, title, optionType, messageType, icon, null, null);
    }

    // ------------------------------------------------------------------ as JOptionPane.showOptionDialog

    static int option(Component parent, Object message, String title, int optionType, int messageType, Icon icon,
                      Object[] options, Object initialValue) {
        JOptionPane pane = new JOptionPane(message, messageType, optionType, icon, options, initialValue);
        pane.setInitialValue(initialValue);
        show(parent, pane, title);
        return answer(pane.getValue(), options);
    }

    /** JOptionPane's answer: the button's index among the options, its own number without them, CLOSED when shut. */
    static int answer(Object value, Object[] options) {
        if (value == null) return JOptionPane.CLOSED_OPTION;
        if (options == null) return value instanceof Integer i ? i : JOptionPane.CLOSED_OPTION;
        for (int i = 0; i < options.length; i++) if (options[i].equals(value)) return i;
        return JOptionPane.CLOSED_OPTION;
    }

    // ------------------------------------------------------------------ as JOptionPane.showInputDialog

    static String input(Component parent, Object message) {
        return input(parent, message, UIManagerText.INPUT, JOptionPane.QUESTION_MESSAGE);
    }

    static String input(Component parent, Object message, Object initialSelectionValue) {
        return (String) input(parent, message, UIManagerText.INPUT, JOptionPane.QUESTION_MESSAGE, null, null, initialSelectionValue);
    }

    static String input(Component parent, Object message, String title, int messageType) {
        return (String) input(parent, message, title, messageType, null, null, null);
    }

    static Object input(Component parent, Object message, String title, int messageType, Icon icon,
                        Object[] selectionValues, Object initialSelectionValue) {
        JOptionPane pane = new JOptionPane(message, messageType, JOptionPane.OK_CANCEL_OPTION, icon, null, null);
        pane.setWantsInput(true);
        pane.setSelectionValues(selectionValues);
        pane.setInitialSelectionValue(initialSelectionValue);
        show(parent, pane, title);
        Object value = pane.getInputValue();
        return value == JOptionPane.UNINITIALIZED_VALUE ? null : value;
    }

    /** JOptionPane's own default titles. */
    private static final class UIManagerText {
        static final String MESSAGE = javax.swing.UIManager.getString("OptionPane.messageDialogTitle");
        static final String SELECT = javax.swing.UIManager.getString("OptionPane.titleText");
        static final String INPUT = javax.swing.UIManager.getString("OptionPane.inputDialogTitle");
    }
}
