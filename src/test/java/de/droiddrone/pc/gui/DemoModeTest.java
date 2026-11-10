package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEMO mode's strip in FLEET MANAGEMENT (2026-11-10, the pilot: the scenario "should be a surprise for the viewers"):
 * it never names what comes - no BTR on it, nor in its tips.
 */
class DemoModeTest {
    private static void texts(Component c, List<String> out) {
        if (c instanceof AbstractButton b) {
            out.add(b.getText());
            if (b.getToolTipText() != null) out.add(b.getToolTipText());
        }
        if (c instanceof JLabel l) out.add(l.getText());
        if (c instanceof Container k) for (Component x : k.getComponents()) texts(x, out);
    }

    @Test
    void theScenarioStripNeverNamesWhatComes() {
        Theme.install();
        DemoMode d = new DemoMode(null);
        List<String> shown = new ArrayList<>();
        texts(d.strip(), shown);
        assertTrue(shown.contains("SET UP SCENARIO"), "its first button: " + shown);
        for (String t : shown) assertFalse(t.toUpperCase().contains("BTR"), "the surprise kept: " + t);
        assertEquals(DemoMode.Stage.NONE, d.stage());
    }
}
