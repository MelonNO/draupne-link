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
    void theTopButtonSaysWhatItDoes() { // 1.0.52, the pilot: PILOT UAV / STANDBY MODE / REGAINING CONTROL IN x MIN
        assertEquals("PILOT UAV", MainWindow.connectText(false, false, false, false, false, false, -1));
        assertEquals("WATCH", MainWindow.connectText(false, false, false, false, true, false, -1));
        assertEquals("OBSERVE", MainWindow.connectText(false, false, false, true, false, false, -1));
        assertEquals("STOP OBSERVING", MainWindow.connectText(false, false, true, true, false, false, -1));
        assertEquals("STANDBY MODE", MainWindow.connectText(true, false, false, false, false, false, -1));
        assertEquals("STOP WATCHING", MainWindow.connectText(true, true, false, false, true, false, -1));
        assertEquals("REGAINING CONTROL IN 3 MIN", MainWindow.connectText(true, false, false, false, false, true, 150_000));
        assertEquals("REGAINING CONTROL IN 1 MIN", MainWindow.connectText(true, false, false, false, false, true, 5_000));
        assertEquals("REGAINING CONTROL...", MainWindow.connectText(true, false, false, false, false, true, -1), "always listening");
    }

    @Test
    void modeWords() {
        assertEquals("OVERWATCH", MainWindow.modeWord(ControlLink.POWER_OVERWATCH));
        assertEquals("DEEP SLEEP", MainWindow.modeWord(ControlLink.POWER_DEEP_SLEEP));
        assertEquals("STANDBY", MainWindow.modeWord(ControlLink.POWER_STANDBY));
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

    /** LOST DRONE marks it CRASHED (1.0.52): kept in the settings as name@time, a broken entry left out. */
    @Test
    void crashedDronesSurviveARestart() {
        java.util.Map<String, Long> m = MainWindow.parseCrashed("MobileAlpha@1700000000000;broken;MobileBravo@x;MobileZulu@5");
        assertEquals(java.util.Map.of("MobileAlpha", 1700000000000L, "MobileZulu", 5L), m);
        assertTrue(MainWindow.parseCrashed("").isEmpty());
        assertTrue(MainWindow.parseCrashed(null).isEmpty());
    }
}
