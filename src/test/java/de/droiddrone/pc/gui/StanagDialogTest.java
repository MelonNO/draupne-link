package de.droiddrone.pc.gui;

import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.65 (the pilot: "I am not able to set any values in the settings"): the five fields were 15 px wide - the hints
 * beside them didn't fit the window, and GridBagLayout then gives every component its minimum size. Never shown.
 */
class StanagDialogTest {
    @Test
    void everyFieldHasItsFullWidthAndTheHintsFit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Theme.install();
                StanagDialog d = new StanagDialog(null, PcConfig.defaults(), m -> { });
                try {
                    d.addNotify(); // displayable as when open
                    d.validate();
                    List<Component> all = new ArrayList<>();
                    collect(d.getContentPane(), all);
                    int fields = 0;
                    for (Component c : all) {
                        if (c instanceof JTextField f) {
                            fields++;
                            assertTrue(f.getWidth() >= f.getPreferredSize().width,
                                    "a field " + f.getWidth() + " px wide, wants " + f.getPreferredSize().width);
                            assertTrue(f.getX() + f.getWidth() <= f.getParent().getWidth(), "a field runs out of the window");
                        } else if (c instanceof JLabel l && l.getToolTipText() != null) {
                            assertTrue(l.getWidth() >= l.getPreferredSize().width, "cut short: " + l.getText());
                        }
                    }
                    assertEquals(5, fields);
                } finally {
                    d.dispose();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * The test bench's F-032: the window was 700 high whatever the screen - on a 584-high one (a 1366 x 768 laptop at
     * 125 %) SAVE and CLOSE were below its edge. It fits the screen it opens on, its buttons on it; the list gives way.
     */
    @Test
    void onASmallScreenTheWindowFitsWithItsButtons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Theme.install();
                ScreenFit.pretend = new java.awt.Dimension(1093, 584);
                StanagDialog d = new StanagDialog(null, PcConfig.defaults(), m -> { });
                try {
                    d.addNotify();
                    d.validate();
                    java.awt.Rectangle screen = ScreenFit.usable(null);
                    assertTrue(d.getHeight() <= 584 && d.getWidth() <= 1093, "the window " + d.getSize() + " on a screen of 1093 x 584");
                    assertTrue(screen.contains(d.getBounds()), d.getBounds() + " lies on " + screen);
                    List<Component> all = new ArrayList<>();
                    collect(d.getContentPane(), all);
                    int buttons = 0;
                    java.awt.Rectangle inside = new java.awt.Rectangle(d.getContentPane().getSize());
                    for (Component c : all) {
                        if (!(c instanceof javax.swing.JButton b) || b.getText() == null) continue;
                        if (!b.getText().equalsIgnoreCase("Save") && !b.getText().equalsIgnoreCase("Close")) continue;
                        buttons++;
                        java.awt.Rectangle r = SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), d.getContentPane());
                        assertTrue(inside.contains(r), b.getText() + " at " + r + " is inside the window's " + inside);
                    }
                    assertEquals(2, buttons, "SAVE and CLOSE");
                } finally {
                    ScreenFit.pretend = null;
                    d.dispose();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 1.0.65: every drone's port and what its stream shows, and the line when another PC streams to the same address. */
    @Test
    void theStreamsAreListedAndAClashIsSaidWithTheFieldsStillWhole() throws Exception {
        List<StanagController.Row> rows = List.of(new StanagController.Row("MobileAlpha", "100.64.0.9:15000", "VIDEO"),
                new StanagController.Row("MobileBravo", "100.64.0.9:15001", "DEEP SLEEP"), new StanagController.Row("Hawk", "100.64.0.9:15026", "OVERWATCH - ALERT"));
        SwingUtilities.invokeAndWait(() -> {
            try {
                Theme.install();
                StanagDialog d = new StanagDialog(null, PcConfig.defaults(), m -> { }, () -> rows,
                        () -> "PCBravo also streams to 100.64.0.9. Switch one off.");
                try {
                    d.addNotify();
                    d.validate();
                    List<Component> all = new ArrayList<>();
                    collect(d.getContentPane(), all);
                    boolean warning = false, showing = false;
                    List<String> addresses = new ArrayList<>();
                    for (Component c : all) {
                        if (c instanceof JTextField f) {
                            assertTrue(f.getWidth() >= f.getPreferredSize().width, "a field " + f.getWidth() + " px wide");
                            assertTrue(f.getY() + f.getHeight() <= f.getParent().getHeight(), "a field cut off below");
                            assertTrue(f.isEditable() && f.isEnabled(), "every box can be typed in");
                            if (f.getText().startsWith("100.64.0.9:")) addresses.add(f.getText());
                        } else if (c instanceof JLabel l && l.getText().startsWith("PCBravo also streams")) {
                            warning = true;
                            assertTrue(l.getWidth() >= l.getPreferredSize().width, "the warning is whole");
                        } else if (c instanceof JLabel l && l.getText().equals("OVERWATCH - ALERT")) {
                            showing = l.getWidth() >= l.getPreferredSize().width;
                        }
                    }
                    // the pilot: "make it possible for the pilot to change the ip and port" - a box per drone
                    assertEquals(List.of("100.64.0.9:15000", "100.64.0.9:15001", "100.64.0.9:15026"), addresses, "each drone's address in a box of its own");
                    assertTrue(showing, "and what its stream shows, whole");
                    assertTrue(warning, "the other PC's line");
                } finally {
                    d.dispose();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void collect(Container from, List<Component> into) {
        for (Component c : from.getComponents()) {
            into.add(c);
            if (c instanceof Container k) collect(k, into);
        }
    }
}
