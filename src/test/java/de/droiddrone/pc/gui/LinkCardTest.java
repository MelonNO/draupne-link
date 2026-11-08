package de.droiddrone.pc.gui;

import de.droiddrone.pc.ControlLink;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;

import static org.junit.jupiter.api.Assertions.*;

/** 1.0.51, the pilot's LINK card round: quiet while a phone wakes, the phone's mode lit, copyable figures. */
class LinkCardTest {
    @Test
    void noBeepWhileWaitingForASleepingPhone() {
        assertTrue(MainWindow.quietWhileWaking(true, ControlLink.State.CONNECTING), "the countdown to its check-in is no link loss");
        assertFalse(MainWindow.quietWhileWaking(true, ControlLink.State.READY), "awake: warnings as usual");
        assertFalse(MainWindow.quietWhileWaking(false, ControlLink.State.CONNECTING), "an ordinary link loss still beeps");
    }

    @Test
    void theChosenSleepingPhonesModeIsShown() {
        MainWindow.Asleep overwatch = new MainWindow.Asleep("MobileAlpha", "1.2.3.4", 0, 1, true);
        MainWindow.Asleep sleeping = new MainWindow.Asleep("MobileAlpha", "1.2.3.4", 0, 1, false);
        assertEquals(ControlLink.POWER_OVERWATCH, MainWindow.shownPowerMode(null, overwatch, false, false));
        assertEquals(ControlLink.POWER_DEEP_SLEEP, MainWindow.shownPowerMode(null, sleeping, false, false));
        assertEquals(-1, MainWindow.shownPowerMode(null, null, false, false), "an awake phone lights nothing");
    }

    @Test
    void aLitButtonKeepsItsLookWhenLocked() {
        TacticalButton b = (TacticalButton) Theme.secondaryButton("Overwatch");
        b.setCurrent(true);
        b.setEnabled(false);
        assertTrue(b.isCurrent());
        b.setCurrent(false);
        assertFalse(b.isCurrent());
    }

    @Test
    void copyTakesTheSelectionElseAll() {
        CopyableText t = new CopyableText("-", new Font(Font.MONOSPACED, Font.BOLD, 12), Color.WHITE, Color.BLACK);
        t.show("32V PN 49174 50857", Color.WHITE);
        assertEquals("32V PN 49174 50857", t.copyText());
        t.select(0, 3);
        assertEquals("32V", t.copyText());
        assertFalse(t.isEditable());
    }
}
