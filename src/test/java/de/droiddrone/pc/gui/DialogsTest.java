package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.0.56, the pilot's screenshot: RESET SETUP showed its text and no buttons. */
class DialogsTest {
    @Test
    void theResetQuestionShowsItsButtonsInsideTheDialog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            JOptionPane pane = new JOptionPane(ResetSetup.QUESTION, JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            JDialog d = Dialogs.dialog(null, pane, "Reset setup");
            try {
                // as on screen: everything laid out again once shown (the HTML message by then at its real height)
                invalidateAll(d);
                d.validate();
                List<AbstractButton> buttons = new ArrayList<>();
                collect(pane, buttons);
                assertEquals(2, buttons.size(), "OK and Cancel");
                Rectangle area = new Rectangle(d.getContentPane().getSize());
                for (AbstractButton b : buttons) {
                    Rectangle r = SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), d.getContentPane());
                    assertTrue(area.contains(r), b.getText() + " at " + r + " outside " + area);
                }
                // and the message isn't squeezed: it has the height it asks for
                assertTrue(pane.getHeight() >= pane.getPreferredSize().height, pane.getSize() + " < " + pane.getPreferredSize());
            } finally {
                d.dispose();
            }
        });
    }

    private static void invalidateAll(Component c) {
        c.invalidate();
        if (c instanceof Container k) for (Component x : k.getComponents()) invalidateAll(x);
    }

    private static void collect(Component c, List<AbstractButton> out) {
        if (c instanceof AbstractButton b) out.add(b);
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, out);
    }
}
