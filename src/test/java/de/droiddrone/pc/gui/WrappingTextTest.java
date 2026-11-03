package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A text given new words must not scroll the page it is on: the connect step's check rows change four times a
 * second, and each change scrolled the page to that row (the caret followed the new text to its end and asked the
 * scroll pane for it), so the top of the step could never be seen.
 */
class WrappingTextTest {
    @Test
    void newWordsDontScrollThePage() throws Exception {
        JScrollPane[] scroll = new JScrollPane[1];
        CheckRow[] row = new CheckRow[1];
        SwingUtilities.invokeAndWait(() -> {
            JPanel col = WizardDialog.column();
            JPanel filler = new JPanel();
            filler.setPreferredSize(new Dimension(500, 1200)); // the row is far below the visible part
            WizardDialog.addRow(col, filler);
            row[0] = new CheckRow("Round-trip time");
            row[0].set(CheckRow.State.PENDING, "");
            WizardDialog.addRow(col, row[0]);
            scroll[0] = new JScrollPane(new WidthTrackingPanel(col));
            scroll[0].setSize(600, 300);
            layOut(scroll[0]);
            scroll[0].getViewport().setViewPosition(new Point(0, 0));
            int rowY = SwingUtilities.convertPoint(row[0], 0, 0, scroll[0].getViewport().getView()).y;
            assertTrue(rowY > 300, "the row is below the visible part: " + rowY);
            row[0].set(CheckRow.State.OK, "Median 47 ms, worst 65 ms.");
        });
        for (int i = 0; i < 3; i++) SwingUtilities.invokeAndWait(() -> { }); // the caret's scroll request, had there been one, runs first
        assertEquals(0, scroll[0].getViewport().getViewPosition().y, "the page stayed at the top");
    }

    /** Lays the tree out without a window (headless): each container its own layout, top down. */
    static void layOut(Component c) {
        c.doLayout();
        if (c instanceof Container container) for (Component child : container.getComponents()) layOut(child);
    }
}
