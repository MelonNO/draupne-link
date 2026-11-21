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
    void deepSleepAndOverwatchShareOneCheckInterval() {
        // F-046, the pilot (2026-11-20): one CHECK EVERY for both - 1.0.56 to 1.0.71 kept one each (OVERWATCH Constant),
        // and a value chosen while the box showed the other mode's went to that mode
        assertEquals(5, PowerController.defaultInterval(ControlLink.POWER_OVERWATCH));
        assertEquals(5, PowerController.defaultInterval(ControlLink.POWER_DEEP_SLEEP));
        PowerController p = new PowerController(null, System::currentTimeMillis);
        assertEquals(5, p.intervalFor(ControlLink.POWER_OVERWATCH));
        assertEquals(5, p.intervalFor(ControlLink.POWER_DEEP_SLEEP));
        p.checkIntervalMin = 1;
        assertEquals(1, p.intervalFor(ControlLink.POWER_OVERWATCH), "the value just chosen goes to whichever is pressed");
        assertEquals(1, p.intervalFor(ControlLink.POWER_DEEP_SLEEP));
        p.checkIntervalMin = SleepCheck.ALWAYS;
        assertEquals(SleepCheck.ALWAYS, p.intervalFor(ControlLink.POWER_OVERWATCH));
        assertEquals(SleepCheck.ALWAYS, p.intervalFor(ControlLink.POWER_DEEP_SLEEP));
    }

    @Test
    void zeroMinutesReadsConstant() {
        assertEquals("Constant", SleepCheck.label(SleepCheck.ALWAYS));
        assertEquals("CONSTANT", FleetManagementPanel.everyText(SleepCheck.ALWAYS));
        assertEquals("EVERY 5M", FleetManagementPanel.everyText(5));
        for (int i : MainWindow.CHECK_INTERVALS) assertFalse(SleepCheck.label(i).startsWith("0"), SleepCheck.label(i));
    }

    @Test
    void theFleetsButtonsSendOneIntervalForEitherSleep() throws Exception {
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
        // F-046, the pilot (2026-11-20): one CHECK EVERY for both sleeps (1.0.56 - 1.0.71: OVERWATCH its own, Constant)
        assertArrayEquals(new int[]{ControlLink.POWER_OVERWATCH, 5}, sent.get(0), "the one interval, 5 min unless chosen");
        assertArrayEquals(new int[]{ControlLink.POWER_DEEP_SLEEP, 5}, sent.get(1));
        SwingUtilities.invokeAndWait(() -> panel[0].setCards(List.of(card("MobileAlpha", "OVERWATCH", SleepCheck.ALWAYS, null))));
        SwingUtilities.invokeAndWait(() -> assertEquals("CONSTANT", everyShown(panel[0]), "EVERY shows the interval it sleeps by"));
        sent.clear();
        SwingUtilities.invokeAndWait(() -> {
            panel[0].setCards(List.of(card("MobileAlpha", "ACTIVE", 5, null))); // woken: the value it slept by is kept
            press(panel[0], "DEEP SLEEP");
        });
        assertArrayEquals(new int[]{ControlLink.POWER_DEEP_SLEEP, SleepCheck.ALWAYS}, sent.get(0),
                "the value OVERWATCH slept by goes to DEEP SLEEP too: one interval");
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
