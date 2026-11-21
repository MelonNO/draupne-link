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
        assertTrue(PowerController.quietWhileWaking(true, ControlLink.State.CONNECTING), "the countdown to its check-in is no link loss");
        assertFalse(PowerController.quietWhileWaking(true, ControlLink.State.READY), "awake: warnings as usual");
        assertFalse(PowerController.quietWhileWaking(false, ControlLink.State.CONNECTING), "an ordinary link loss still beeps");
    }

    @Test
    void theChosenSleepingPhonesModeIsShown() {
        PowerController.Asleep overwatch = new PowerController.Asleep("MobileAlpha", "1.2.3.4", 0, 1, true);
        PowerController.Asleep sleeping = new PowerController.Asleep("MobileAlpha", "1.2.3.4", 0, 1, false);
        assertEquals(ControlLink.POWER_OVERWATCH, PowerController.shownPowerMode(null, overwatch, false, false));
        assertEquals(ControlLink.POWER_DEEP_SLEEP, PowerController.shownPowerMode(null, sleeping, false, false));
        assertEquals(-1, PowerController.shownPowerMode(null, null, false, false), "an awake phone lights nothing");
    }

    @Test
    void theTopButtonSaysWhatItDoes() { // 1.0.52, the pilot: PILOT UAV / STANDBY MODE / REGAINING CONTROL IN x MIN
        assertEquals("PILOT UAV", PowerController.connectText(false, false, false, false, false, false, -1));
        assertEquals("WATCH", PowerController.connectText(false, false, false, false, true, false, -1));
        assertEquals("OBSERVE", PowerController.connectText(false, false, false, true, false, false, -1));
        assertEquals("STOP OBSERVING", PowerController.connectText(false, false, true, true, false, false, -1));
        assertEquals("STANDBY MODE", PowerController.connectText(true, false, false, false, false, false, -1));
        assertEquals("STOP WATCHING", PowerController.connectText(true, true, false, false, true, false, -1));
        // in seconds (2026-11-10, the pilot: "Make the regaining control timer count down with seconds")
        assertEquals("REGAINING CONTROL IN 2:30", PowerController.connectText(true, false, false, false, false, true, 150_000));
        assertEquals("REGAINING CONTROL IN 0:05", PowerController.connectText(true, false, false, false, false, true, 5_000));
        assertEquals("REGAINING CONTROL IN 0:01", PowerController.connectText(true, false, false, false, false, true, 200));
        assertEquals("REGAINING CONTROL...", PowerController.connectText(true, false, false, false, false, true, -1), "always listening");
    }

    @Test
    void modeWords() {
        assertEquals("OVERWATCH", PowerController.modeWord(ControlLink.POWER_OVERWATCH));
        assertEquals("DEEP SLEEP", PowerController.modeWord(ControlLink.POWER_DEEP_SLEEP));
        assertEquals("STANDBY", PowerController.modeWord(ControlLink.POWER_STANDBY));
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

    /**
     * LOST DRONE marks it OUT OF ACTION (1.0.71; CRASHED from 1.0.52): kept in the settings as name@time, a broken entry
     * left out. The form is the old key's, so marks made before 1.0.71 read as they are.
     */
    @Test
    void outOfActionDronesSurviveARestart() {
        assertEquals("drone.outOfAction", MainWindow.OUT_OF_ACTION_KEY);
        assertEquals("drone.crashed", MainWindow.CRASHED_KEY_BEFORE, "1.0.52 - 1.0.70's marks are read from here once");
        java.util.Map<String, Long> m = MainWindow.parseOutOfAction("MobileAlpha@1700000000000;broken;MobileBravo@x;MobileZulu@5");
        assertEquals(java.util.Map.of("MobileAlpha", 1700000000000L, "MobileZulu", 5L), m);
        assertTrue(MainWindow.parseOutOfAction("").isEmpty());
        assertTrue(MainWindow.parseOutOfAction(null).isEmpty());
    }

    @Test
    void anOutOfActionDroneIsNotOfferedInThePhoneList() {
        java.util.function.Function<String, de.droiddrone.pc.Names.Entry> phone = n ->
                new de.droiddrone.pc.Names.Entry(n, de.droiddrone.pc.Names.Kind.PHONE, "100.64.0.1", "", "", "", 0, false);
        java.util.List<de.droiddrone.pc.Names.Entry> all = java.util.List.of(phone.apply("MobileAlpha"), phone.apply("MobileZulu"), phone.apply("MobileBravo"));
        assertEquals(java.util.List.of("MobileAlpha", "MobileBravo"),
                MainWindow.offeredPhones(all, java.util.Set.of("MobileZulu"), null).stream().map(de.droiddrone.pc.Names.Entry::name).toList());
        // the one still linked stays until the pilot ends it
        assertEquals(3, MainWindow.offeredPhones(all, java.util.Set.of("MobileZulu"), "mobilezulu").size());
    }

    /**
     * The test bench's F-037: the controller card said "LOWER THROTTLE TO ENABLE" whatever the sticks waited for -
     * also with the throttle down and the ARM switch left on. It names what they wait for.
     */
    @Test
    void theControllerCardNamesWhatTheSticksWaitFor() {
        assertEquals("RC OUTPUT: OFF - LOWER THROTTLE TO ENABLE", MainWindow.rcOffLine("THROTTLE DOWN"));
        assertEquals("RC OUTPUT: OFF - ARM SWITCH OFF TO ENABLE", MainWindow.rcOffLine("ARM SWITCH OFF"));
        assertEquals("RC OUTPUT: OFF - THROTTLE DOWN, ARM OFF", MainWindow.rcOffLine("THROTTLE DOWN, ARM OFF"));
        assertEquals("RC OUTPUT: OFF - READING FC", MainWindow.rcOffLine("READING FC"));
        assertEquals("RC OUTPUT: OFF - PHONE UPDATING", MainWindow.rcOffLine("PHONE UPDATING"));
        assertEquals("RC OUTPUT: OFF - LOWER THROTTLE TO ENABLE", MainWindow.rcOffLine(null), "as it was while nothing is known");
    }
}
