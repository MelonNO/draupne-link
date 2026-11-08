package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.*;

/** Drone setup's flight mode lists: they wrap on a narrow window and ask for the lines' height (1.0.52). */
class FlowWrapTest {
    private static JPanel row(int width) {
        JPanel row = new JPanel(new FlowWrap(8, 4));
        for (int i = 0; i < 4; i++) {
            JPanel box = new JPanel();
            box.setPreferredSize(new Dimension(100, 30));
            row.add(box);
        }
        row.setSize(width, 10);
        row.doLayout();
        return row;
    }

    @Test
    void oneLineWhenWideAndNoGapBeforeTheFirst() {
        JPanel row = row(600);
        assertEquals(30, row.getPreferredSize().height);
        assertEquals(4 * 100 + 3 * 8, row.getPreferredSize().width);
        assertEquals(0, row.getComponent(0).getX(), "lines up with the lists under it");
        assertEquals(108, row.getComponent(1).getX());
    }

    @Test
    void wrapsAndAsksForEveryLine() {
        JPanel row = row(250); // two to a line
        assertEquals(30 + 4 + 30, row.getPreferredSize().height, "two lines, not one line's height");
        assertEquals(0, row.getComponent(2).getX());
        assertEquals(34, row.getComponent(2).getY());
        assertEquals(4 * 30 + 3 * 4, row(150).getPreferredSize().height, "one to a line: four lines");
    }
}
