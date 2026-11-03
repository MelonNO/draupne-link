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
        List<WizardDialog.Page> phone = List.of(new Stub("tailscale on the phone"), new Stub("flight app"), connect);
        WizardDialog.Page welcome = new Stub("welcome");

        List<WizardDialog.Page> all = SetupWizard.firstTimePages(pc, drone, phone, welcome);

        for (List<WizardDialog.Page> part : List.of(pc, drone, phone)) {
            for (WizardDialog.Page p : part) assertTrue(all.contains(p), p.title() + " is missing from the first-time setup");
        }
        assertEquals(pc.size() + drone.size() + phone.size() + 1, all.size(), "nothing twice");
        assertSame(welcome, all.get(0));
        assertSame(connect, all.get(all.size() - 1), "the first connection is the last step: no delay, ready or sleep page after it");
        assertEquals("THIS PC", shortcuts.section, "the shortcuts with the other PC steps");
        assertTrue(all.indexOf(shortcuts) < all.indexOf(drone.get(0)), "this PC's steps together, then the drone");
        assertTrue(all.indexOf(drone.get(0)) < all.indexOf(radio), "the radio after the drone: it names the switches");
        assertEquals("RADIO", radio.section);
        assertEquals("CONNECT", connect.section);
        assertEquals("DRONE", drone.get(0).section);
        assertTrue(all.indexOf(drone.get(0)) < all.indexOf(phone.get(0)), "the drone before the phone");
    }

    @Test
    void radioStepComesBackWhenDroneSetupChangedTheSwitches() throws Exception {
        de.droiddrone.pc.PcConfig config = de.droiddrone.pc.PcConfig.defaults();
        assertFalse(DroneSetup.radioNeedsRedo(config));
        assertTrue(PcSetupWizard.RadioPage.shown(false, config), "not calibrated: shown");
        assertFalse(PcSetupWizard.RadioPage.shown(true, config), "calibrated, nothing changed: skipped");
        // Drone setup wrote ARM to AUX1 (or a choice changed): an old calibration may have any switch there
        config.set(DroneSetup.RADIO_REDO_KEY, "true");
        assertTrue(DroneSetup.radioNeedsRedo(config));
        assertTrue(PcSetupWizard.RadioPage.shown(true, config), "shown again until a calibration is saved");
        String text = DroneSetup.radioRedoText(config);
        assertTrue(text.startsWith("Calibrate the radio again so AUX1 (ARM)"), text);
        assertTrue(text.contains("AUX2 (flight modes)"), text);
        // a calibration saved: cleared
        config.set(DroneSetup.RADIO_REDO_KEY, "false");
        assertFalse(PcSetupWizard.RadioPage.shown(true, config));
        // one switch only
        config.set(de.droiddrone.pc.setup.ModeSwitch.KEY, "off");
        config.set(de.droiddrone.pc.setup.ModeSwitch.RESCUE_KEY, "off");
        assertEquals("Calibrate the radio again so AUX1 (ARM) is the right switch.", DroneSetup.radioRedoText(config));
    }

    @Test
    void cliRepliesAreLoggedShort() {
        assertEquals("(ok)", DroneSetup.BetaflightPage.brief("\r\n"));
        assertEquals("serial_update_rate_hz set to 2000", DroneSetup.BetaflightPage.brief("serial_update_rate_hz set to 2000\r\n\r\n"));
        assertEquals(80, DroneSetup.BetaflightPage.brief("x".repeat(200)).length());
    }
}
