package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD AS-6 / AS-7 on the pages: a blocked port keeps CONFIRM grey; AUTOTUNE's position reaches SETTINGS. */
class ArduSetupPagesTest {
    private static Map<String, Float> board() {
        Map<String, Float> p = new HashMap<>();
        p.put("SERIAL0_PROTOCOL", 2f);
        p.put("SERIAL2_PROTOCOL", 23f);
        p.put("SERIAL3_PROTOCOL", -1f);
        p.put("FRAME_CLASS", 1f);
        p.put("FRAME_TYPE", 12f);
        return p;
    }

    @Test
    void aBlockedPortKeepsConfirmGreyUntilReplace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ArduSetupPages.State s = new ArduSetupPages.State();
            s.params = board();
            ArduSetupPages.PortsPage p = new ArduSetupPages.PortsPage(s);
            p.view();
            p.onShow();
            assertTrue(p.changes.isEmpty() && p.canContinue(), "nothing to change, nothing blocked");
            javax.swing.JComboBox<?> gps = find(p.view(), javax.swing.JComboBox.class, 0);
            gps.setSelectedIndex(2); // SERIAL2: RC input
            assertFalse(p.blocked.isEmpty());
            assertFalse(p.confirm.isEnabled(), "blocked: no CONFIRM");
            assertFalse(p.canContinue());
            find(p.view(), javax.swing.JCheckBox.class, 0).doClick(); // REPLACE
            assertTrue(p.blocked.isEmpty());
            assertTrue(p.confirm.isEnabled());
        });
    }

    @Test
    void autotunesPositionReachesTheSettingsPlan() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ArduSetupPages.State s = new ArduSetupPages.State();
            s.params = board();
            ArduSetupPages.TuningPage t = new ArduSetupPages.TuningPage(s);
            t.view();
            t.onShow();
            assertTrue(t.changes.isEmpty(), "Betaflight X kept, its tune kept: " + t.changes);
            find(t.view(), javax.swing.JCheckBox.class, 0).doClick(); // AUTOTUNE, position 4
            assertEquals(4, s.autotunePos);
            assertTrue(t.changes.stream().anyMatch(c -> c.name().equals("FLTMODE4") && c.to() == 15f), t.changes.toString());
            ArduSetupPages.SettingsPage settings = new ArduSetupPages.SettingsPage(s);
            settings.view();
            assertEquals(15, settings.choices().flightModes()[3], "SETTINGS keeps AUTOTUNE on its position");
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends java.awt.Component> T find(java.awt.Component root, Class<T> type, int index) {
        java.util.List<T> all = new java.util.ArrayList<>();
        collect(root, type, all);
        return all.get(index);
    }

    private static <T extends java.awt.Component> void collect(java.awt.Component c, Class<T> type, java.util.List<T> out) {
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof java.awt.Container k) for (java.awt.Component x : k.getComponents()) collect(x, type, out);
    }
}
