package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.Names;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The hardware rig's first scenario, and its proof: the real app, sealed off, finds this bench's one real phone by
 * itself, opens its link to it, shows its camera's picture - and ends with the radio's hands off. Nothing is armed:
 * the radio's hands are off from the first moment to the last, and this scenario never puts them on.
 * <p>
 * Run by gradlew benchHw (.\bench.ps1 hw) only, on a PC that has a hardware file ({@link Hardware}); without one it
 * skips itself. It needs the phone awake with DRAUPNE Flight open, and no DRAUPNE Link running on this PC.
 */
@Tag("hw")
class PhoneAnswersScenario {
    @Test
    void theRealPhoneAnswersTheSealedAppWhichEndsHandsOff() throws Exception {
        Assumptions.assumeTrue(Hardware.present(), "This bench has no hardware set up: no " + Hardware.FILE
                + " in %LOCALAPPDATA%\\DraupneBench (start from " + Hardware.EXAMPLE + ")");
        Hardware bench = Hardware.load();
        AppRig.scenario("hw-phone-answers").hardware(bench).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            try {
                rig.check(rig.radio.isHandsOff() && rig.radio.ceiling() == Hardware.THROTTLE_CEILING,
                        "the radio starts hands off, its throttle ceiling at " + Hardware.THROTTLE_CEILING);
                ui.shot("started sealed off: the real phone is asked who it is");

                // the phone answers the app's "who are you?" - out and back through the fence's one way - and is in
                // the PHONE list under its own name
                ui.until("the real phone, online, in the PHONE list", 90_000, () -> chosenPhone(ui).contains("online"));
                String line = chosenPhone(ui);
                String name = line.trim().split("\\s+")[0];
                rig.check(Names.kindOf(name) == Names.Kind.PHONE, "the PHONE list has it under a phone's name: " + line.trim());
                rig.check(!line.contains("VIRTUAL"), "listed as a real phone, not a VIRTUAL one");
                rig.check(!rig.logged("Names: phone at " + bench.phoneIp() + " is "),
                        "the phone had its name already: this app, which knows no other phone, did not name it");
                ui.shot("the real phone answered: " + name);

                // PILOT UAV: the link, the camera's picture and the flight controller's word, all through the fence
                ui.click("PILOT UAV");
                ui.waitForControl("STANDBY MODE", 30_000);
                ui.until("the link up (LINK READY, or FC LOST when the phone has no flight controller)", 60_000, pilot::linkUp);
                boolean board = ui.has("LINK READY");
                rig.film.remark(board ? "The link is up and the flight controller answers." : "The link is up; the phone has no flight controller (FC LOST).");
                boolean picture = true;
                try {
                    rig.waitForLog("Video: H.26", 40_000); // H.264 or H.265, as the phone has it
                } catch (AssertionError none) {
                    picture = false; // said as a check that goes on: the link's end below still has to be tried
                }
                rig.check(picture, "the phone's camera picture arrives");
                Ui.pause(3000);
                ui.shot("connected to the real phone: its picture" + (board ? " and its flight controller's word" : ""));
                List<String> rc = ui.texts().stream().filter(t -> t.contains("RC OUTPUT") || t.contains("FC:")).toList();
                rig.film.remark("The controller card: " + rc);
                rig.check(!ui.sees("FC: ARMED"), "nothing is armed");

                // the link ended as a pilot ends it; the phone rests until the next PILOT UAV
                pilot.standby();
                ui.shot("STANDBY MODE: the link ended, the phone resting");
                rig.check(rig.radio.isHandsOff() && rig.radio.clamped() == 0 && rig.radio.refused() == 0,
                        "the radio was hands off throughout and was asked for nothing: " + rig.radio.limits());
            } finally {
                rig.radio.handsOff(); // the rig does it again, for good, before it closes the app
            }
        });
    }

    /** The PHONE list's chosen line: "MobileAlpha   online", or "No phone seen yet". */
    private static String chosenPhone(Ui ui) {
        return String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem()));
    }
}
