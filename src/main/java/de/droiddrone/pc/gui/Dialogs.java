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
        fit(d, parent);
        return d;
    }

    /**
     * Packs until the size stops changing (a few times at most), then never smaller than that. Every size is asked
     * again each time: the cached ones are the HTML message's short first measure.
     */
    static void fit(JDialog d, Component near) {
        repack(d);
        d.setMinimumSize(d.getSize());
        d.setLocationRelativeTo(near != null ? near : d.getOwner());
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

    /** Shows it (modal) and lets it go. */
    private static void show(Component parent, JOptionPane pane, String title) {
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
