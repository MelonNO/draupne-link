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

/**
 * 1.0.56, the pilot's screenshot: RESET SETUP showed its text and no buttons. Every dialog of the app goes through
 * Dialogs, which packs until an HTML message has its real height.
 */
class DialogsTest {
    @Test
    void theResetQuestionShowsItsButtonsInsideTheDialog() throws Exception {
        assertButtonsInside(ResetSetup.QUESTION, JOptionPane.OK_CANCEL_OPTION, 2);
    }

    @Test
    void theUpdateQuestionShowsItsButtonsInsideTheDialog() throws Exception {
        assertButtonsInside(MainWindow.updateQuestion("1.0.56"), JOptionPane.OK_CANCEL_OPTION, 2);
    }

    @Test
    void aLongHtmlMessageWithLinesOfItsOwnToo() throws Exception {
        assertButtonsInside("<html><b>Delete MobileAlpha 2026-11-09 17:41?</b><br><br>Track, video and log go.<br>"
                + "Can't be undone.<br><br>" + "A long line that wraps on a narrow screen, ".repeat(3) + "</html>",
                JOptionPane.YES_NO_OPTION, 2);
    }

    @Test
    void theAnswerIsTheButtonsIndexOrItsNumberOrClosed() {
        Object[] options = {"Close fleet", "Cancel"};
        assertEquals(1, Dialogs.answer("Cancel", options));
        assertEquals(JOptionPane.CLOSED_OPTION, Dialogs.answer(null, options), "window shut");
        assertEquals(JOptionPane.OK_OPTION, Dialogs.answer(JOptionPane.OK_OPTION, null));
        assertEquals(JOptionPane.CLOSED_OPTION, Dialogs.answer(JOptionPane.UNINITIALIZED_VALUE, null));
    }

    /** Every dialog of the window goes through Dialogs: none left on JOptionPane's own, which packs once. */
    @Test
    void noDialogIsShownByJOptionPaneDirectly() throws Exception {
        // the project from where the test classes are (build/classes/java/test): tests may run elsewhere
        java.nio.file.Path p = java.nio.file.Path.of(DialogsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        while (p != null && !java.nio.file.Files.isDirectory(p.resolve("src/main/java"))) p = p.getParent();
        assertTrue(p != null, "the project folder");
        java.nio.file.Path dir = p.resolve("src/main/java/de/droiddrone/pc/gui");
        List<String> found = new ArrayList<>();
        try (var files = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path f : files.toList()) {
                if (f.getFileName().toString().equals("Dialogs.java")) continue;
                String text = java.nio.file.Files.readString(f);
                if (text.matches("(?s).*JOptionPane\\.show\\w*Dialog\\(.*")) found.add(f.getFileName().toString());
            }
        }
        assertTrue(found.isEmpty(), "use Dialogs.message/confirm/option/input in " + found);
    }

    private static void assertButtonsInside(String message, int optionType, int buttonCount) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE, optionType);
            JDialog d = Dialogs.dialog(null, pane, "Test");
            try {
                // as on screen: everything laid out again once shown (the HTML message by then at its real height)
                invalidateAll(d);
                d.validate();
                List<AbstractButton> buttons = new ArrayList<>();
                collect(pane, buttons);
                assertEquals(buttonCount, buttons.size());
                Rectangle area = new Rectangle(d.getContentPane().getSize());
                for (AbstractButton b : buttons) {
                    Rectangle r = SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), d.getContentPane());
                    assertTrue(area.contains(r), b.getText() + " at " + r + " outside " + area);
                }
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
