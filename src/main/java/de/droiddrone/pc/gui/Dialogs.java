package de.droiddrone.pc.gui;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Dimension;

/**
 * JOptionPane questions that always show their buttons (1.0.56, the pilot's screenshot of RESET SETUP: the text, then
 * nothing). An HTML message measures shorter before its first layout than after it (102 px, then 136 px for the reset
 * question), so the dialog packed once was too short and the button row fell off its bottom. Packed until its size
 * stays put.
 */
final class Dialogs {
    private Dialogs() {
    }

    /** The dialog for a question, sized to show all of it; not shown yet. */
    static JDialog dialog(Component parent, JOptionPane pane, String title) {
        JDialog d = pane.createDialog(parent, title);
        fit(d);
        return d;
    }

    /**
     * Packs until the size stops changing (a few times at most), then never smaller than that. Every size is asked
     * again each time: the cached ones are the HTML message's short first measure.
     */
    static void fit(JDialog d) {
        Dimension last = null;
        for (int i = 0; i < 4; i++) {
            invalidateAll(d);
            d.pack();
            Dimension now = d.getSize();
            if (now.equals(last)) break;
            last = now;
        }
        d.setMinimumSize(d.getSize());
        if (d.getOwner() != null) d.setLocationRelativeTo(d.getOwner());
    }

    private static void invalidateAll(Component c) {
        c.invalidate();
        if (c instanceof java.awt.Container k) for (Component x : k.getComponents()) invalidateAll(x);
    }

    /** OK / Cancel, as JOptionPane.showConfirmDialog. */
    static int confirm(Component parent, Object message, String title, int optionType, int messageType) {
        JOptionPane pane = new JOptionPane(message, messageType, optionType);
        JDialog d = dialog(parent, pane, title);
        d.setVisible(true); // modal
        d.dispose();
        Object v = pane.getValue();
        return v instanceof Integer i ? i : JOptionPane.CLOSED_OPTION;
    }
}
