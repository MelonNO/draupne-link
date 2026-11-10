package de.droiddrone.pc.gui;

import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.ControlLink;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.56, the pilot's LINK and fleet round (a queued mode at once on the sign: undone 2026-11-10, ModesRoundTest), OVERWATCH checks in
 * Constant by default and DEEP SLEEP every 5 min, "0 min" reads Constant, PILOT UAV on an alerting drone, the LINK
 * card titled by its mode, the modes in the pilot's order.
 */
class LinkFleetRoundTest {
    @Test
    void overwatchChecksInConstantlyByDefaultDeepSleepEveryFiveMinutes() {
        assertEquals(SleepCheck.ALWAYS, PowerController.defaultInterval(ControlLink.POWER_OVERWATCH));
        assertEquals(5, PowerController.defaultInterval(ControlLink.POWER_DEEP_SLEEP));
        PowerController p = new PowerController(null, System::currentTimeMillis);
        assertEquals(SleepCheck.ALWAYS, p.intervalFor(ControlLink.POWER_OVERWATCH));
        assertEquals(5, p.intervalFor(ControlLink.POWER_DEEP_SLEEP));
        p.overwatchIntervalMin = 2;
        assertEquals(2, p.intervalFor(ControlLink.POWER_OVERWATCH), "each keeps its own");
        assertEquals(5, p.intervalFor(ControlLink.POWER_DEEP_SLEEP));
    }

    @Test
    void zeroMinutesReadsConstant() {
        assertEquals("Constant", SleepCheck.label(SleepCheck.ALWAYS));
        assertEquals("CONSTANT", FleetManagementPanel.everyText(SleepCheck.ALWAYS));
        assertEquals("EVERY 5M", FleetManagementPanel.everyText(5));
        for (int i : MainWindow.CHECK_INTERVALS) assertFalse(SleepCheck.label(i).startsWith("0"), SleepCheck.label(i));
    }

    @Test
    void theFleetsButtonsSendEachModeWithItsOwnInterval() throws Exception {
        List<int[]> sent = new ArrayList<>();
        FleetManagementPanel[] panel = new FleetManagementPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new FleetManagementPanel(new FleetManagementPanel.Actions() {
                @Override
                public void mode(String phone, int mode, int intervalMin) {
                    sent.add(new int[]{mode, intervalMin});
                }

                @Override
                public void clips(String phone) {
                }

                @Override
                public void cancel(String phone) {
                }
            });
            panel[0].setCards(List.of(card("MobileAlpha", "ACTIVE", 5, null)));
            press(panel[0], "OVERWATCH");
            press(panel[0], "DEEP SLEEP");
        });
        assertEquals(2, sent.size());
        assertArrayEquals(new int[]{ControlLink.POWER_OVERWATCH, SleepCheck.ALWAYS}, sent.get(0),
                "an awake phone's own 5 min is not OVERWATCH's choice: Constant");
        assertArrayEquals(new int[]{ControlLink.POWER_DEEP_SLEEP, 5}, sent.get(1));
        SwingUtilities.invokeAndWait(() -> panel[0].setCards(List.of(card("MobileAlpha", "OVERWATCH", SleepCheck.ALWAYS, null))));
        SwingUtilities.invokeAndWait(() -> assertEquals("CONSTANT", everyShown(panel[0]), "EVERY shows OVERWATCH's interval"));
    }

    /** The check-in list's choice (a list since 2026-11-10, was a button). */
    private static String everyShown(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof javax.swing.JComboBox<?> box) return String.valueOf(box.getSelectedItem());
            if (k instanceof Container inner) {
                String s = everyShown(inner);
                if (s != null) return s;
            }
        }
        return null;
    }

    @Test
    void pilotUavOnAnAlertingDroneIsTheAlertsConnect() {
        assertEquals("MobileAlpha", MainWindow.alertingDrone("mobilealpha", List.of("MobileZulu", "MobileAlpha")));
        assertNull(MainWindow.alertingDrone("MobileBravo", List.of("MobileAlpha")), "no alert: an ordinary PILOT UAV");
        assertNull(MainWindow.alertingDrone("", List.of("MobileAlpha")));
    }

    @Test
    void theLinkCardIsTitledByItsMode() {
        assertEquals("PILOT MODE", MainWindow.linkTitleFor("Pilot - fly the drone"));
        assertEquals("OBSERVER MODE", MainWindow.linkTitleFor("Observation - watch idle drones"));
        assertEquals("SPECTATOR MODE", MainWindow.linkTitleFor("Spectator - watch only"));
        assertEquals("PILOT MODE", MainWindow.linkTitleFor(null));
    }

    private static FleetCards.Card card(String name, String badge, int intervalMin, String queued) {
        MapView.Kind kind = "OVERWATCH".equals(badge) ? MapView.Kind.OVERWATCH : MapView.Kind.ACTIVE;
        return new FleetCards.Card(name, false, kind, badge, Double.NaN, -1, 80, false, -1, intervalMin, 1000, -1, -1, -1,
                0, 0, FleetCards.NO_TEMP, FleetCards.NO_TEMP, queued, null);
    }

    private static void press(Container c, String text) {
        AbstractButton b = find(c, text);
        assertNotNull(b, text);
        assertTrue(b.isEnabled(), text);
        b.doClick();
    }

    private static AbstractButton find(Container c, String text) {
        for (Component k : c.getComponents()) {
            if (k instanceof AbstractButton b && text.equals(b.getText())) return b;
            if (k instanceof Container inner) {
                AbstractButton f = find(inner, text);
                if (f != null) return f;
            }
        }
        return null;
    }
}
