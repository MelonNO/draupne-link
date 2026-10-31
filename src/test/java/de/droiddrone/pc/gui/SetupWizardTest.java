package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The first-time setup covers every step of PC, drone and phone setup. */
class SetupWizardTest {
    private static final class Stub extends WizardDialog.Page {
        private final String name;

        Stub(String name) {
            this.name = name;
        }

        @Override
        String title() {
            return name;
        }

        @Override
        String hint() {
            return "";
        }

        @Override
        JComponent build() {
            return new JPanel();
        }
    }

    @Test
    void everyStepOfTheSeparateSetupsIsInTheFirstTimeSetup() {
        WizardDialog.Page radio = new PcSetupWizard.RadioPage(null);
        WizardDialog.Page shortcuts = new PcSetupWizard.ShortcutsPage(null);
        WizardDialog.Page connect = new PhoneGuideWizard.ConnectPage(null);
        List<WizardDialog.Page> pc = List.of(new Stub("connection"), new Stub("tailscale"), radio, new Stub("latency"), shortcuts);
        List<WizardDialog.Page> drone = List.of(new Stub("betaflight"));
        List<WizardDialog.Page> phone = List.of(new Stub("overview"), new Stub("flight app"), connect);
        WizardDialog.Page welcome = new Stub("welcome"), delay = new Stub("delay"), done = new Stub("done");

        List<WizardDialog.Page> all = SetupWizard.firstTimePages(pc, drone, phone, welcome, delay, done);

        for (List<WizardDialog.Page> part : List.of(pc, drone, phone)) {
            for (WizardDialog.Page p : part) assertTrue(all.contains(p), p.title() + " is missing from the first-time setup");
        }
        assertEquals(pc.size() + drone.size() + phone.size() + 3, all.size(), "nothing twice");
        assertSame(welcome, all.get(0));
        assertSame(done, all.get(all.size() - 1));
        assertEquals("THIS PC", shortcuts.section, "the shortcuts with the other PC steps");
        assertTrue(all.indexOf(shortcuts) < all.indexOf(drone.get(0)), "this PC's steps together, then the drone");
        assertTrue(all.indexOf(drone.get(0)) < all.indexOf(radio), "the radio after the drone: it names the switches");
        assertEquals("RADIO", radio.section);
        assertEquals("CONNECT", connect.section);
        assertEquals("DRONE", drone.get(0).section);
        assertTrue(all.indexOf(drone.get(0)) < all.indexOf(phone.get(0)), "the drone before the phone");
        assertTrue(all.indexOf(connect) < all.indexOf(delay), "the first connection, then its delay");
    }
}
