package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.FakeRadio;
import de.droiddrone.bench.Ui;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * CALIBRATE walked through with the made-up radio (2026-11-19 afternoon), as a pilot does it the first time: the
 * sticks circled, centred, each stick held to its side when asked, a switch flipped for each AUX asked for, the
 * summary, FINISH - and the four sticks are then four different axes in the settings file. On the way: a switch
 * flipped in the first step is not one of the "4 stick axes" (F-034: it got a tick as if it were).
 */
@Tag("ui")
class CalibrationScenario {
    /** The wizard's "STEP 3 OF 11" label, looked for once: asking every part of the window for its words takes a second. */
    private static javax.swing.JLabel counter;

    private static String title(Ui ui) {
        if (counter == null || !Ui.edt(counter::isShowing)) {
            counter = null;
            for (javax.swing.JLabel l : ui.all(javax.swing.JLabel.class)) {
                String t = Ui.edt(l::getText);
                if (t != null && t.startsWith("STEP ") && t.contains(" OF ")) counter = l;
            }
        }
        return counter == null ? "" : Ui.edt(counter::getText);
    }

    /** Holds the sticks as given until the wizard's step counter changes; false when it does not in time. */
    private static boolean holdUntilNextStep(Ui ui, long withinMs) {
        String at = title(ui);
        long end = System.currentTimeMillis() + withinMs;
        while (System.currentTimeMillis() < end) {
            if (!title(ui).equals(at)) return true;
            Ui.pause(100);
        }
        return false;
    }

    @Test
    void theRadioIsCalibratedThroughAllItsSteps() throws Exception {
        AppRig.scenario("calibration").drones(1).run(rig -> {
            Ui ui = rig.ui;
            FakeRadio radio = rig.radio;
            ui.click("CALIBRATE");
            ui.until("the calibration window", 10_000, () -> ui.window("Controller calibration") != null);
            Ui.pause(800);

            // step 1. A switch first: two flips from end to end are a full range, and no stick
            for (int i = 0; i < 2; i++) {
                radio.aux(2, 1);
                Ui.pause(350);
                radio.aux(2, -1);
                Ui.pause(350);
            }
            ui.shot("step 1, a switch flipped twice");
            rig.check(ui.sees("Full range: 0 of 4 stick axes"), "a flipped switch is none of the four stick axes");
            long end = System.currentTimeMillis() + 30_000;
            for (int i = 0; System.currentTimeMillis() < end; i++) {
                double a = i / 40.0 * 2 * Math.PI;
                radio.sticks(Math.sin(a), Math.cos(a), Math.cos(a), Math.sin(a)); // both sticks in circles
                Ui.pause(15);
                if (i % 20 == 19 && !ui.sees("MOVE ALL STICKS TO THEIR LIMITS")) break;
            }
            rig.check(ui.sees("CENTRE THE STICKS"), "after circles the wizard goes on to centring by itself: " + title(ui));
            ui.shot("step 2 asks for the sticks centred");

            // step 2: thumbs off, all sticks centred, the throttle too
            radio.sticks(0, 0, 0, 0);
            Ui.pause(1500);
            ui.click("NEXT");
            Ui.pause(700);

            // steps 3 to 6: each stick held to the side asked for
            String[] asked = {"THROTTLE UP", "YAW RIGHT", "PITCH UP", "ROLL RIGHT"};
            double[][] held = {{0, 0, 0, 1}, {0, 0, 1, 0}, {0, 1, 0, 0}, {1, 0, 0, 0}};
            for (int s = 0; s < asked.length; s++) {
                rig.check(ui.sees(asked[s]), "the wizard asks for " + asked[s] + ": " + title(ui));
                radio.sticks(held[s][0], held[s][1], held[s][2], held[s][3]);
                boolean on = holdUntilNextStep(ui, 8000);
                if (s == 0) ui.shot("THROTTLE UP held: taken");
                rig.check(on, asked[s] + " held is taken and the wizard goes on");
                radio.sticks(0, 0, 0, 0);
                Ui.pause(900);
            }

            // the AUX steps: a switch flipped ON for each, until the summary
            int aux = 1;
            for (int guard = 0; guard < 8 && !ui.sees("CHECK AND SAVE"); guard++) {
                String at = title(ui);
                radio.aux(aux, 1);
                boolean on = holdUntilNextStep(ui, 6000);
                if (!on) {
                    ui.shot("the switch for " + at + " was not taken: skipped");
                    ui.click("SKIP");
                    Ui.pause(600);
                }
                rig.film.remark(at + ": the switch on AUX" + aux + (on ? " taken" : " not taken, skipped"));
                aux++;
            }
            rig.check(ui.sees("CHECK AND SAVE"), "the summary is reached: " + title(ui));
            ui.shot("the summary");
            for (String channel : new String[]{"roll", "pitch", "yaw", "throttle", "aux1", "aux2", "aux3", "aux4"}) {
                java.util.List<java.awt.Component> box = ui.all(channel.toUpperCase(java.util.Locale.ROOT)); // F-043: in capitals
                String why = box.isEmpty() ? "it is not there" : ui.whyNotPressable(box.get(0), null);
                rig.check(why == null, "INVERT's box for " + channel + " can be reached (F-036: aux4's was off the window's edge)" + (why == null ? "" : ": " + why));
            }
            ui.click("FINISH");
            ui.until("the calibration window gone", 10_000, () -> ui.window("Controller calibration") == null);
            Ui.pause(1200);
            ui.shot("after FINISH");

            Map<String, String> saved = rig.settingsOnDisk();
            Set<String> axes = new LinkedHashSet<>();
            StringBuilder said = new StringBuilder();
            for (String channel : new String[]{"roll", "pitch", "yaw", "throttle"}) {
                String spec = saved.getOrDefault(channel, "");
                said.append(channel).append('=').append(spec).append(' ');
                if (!spec.isBlank()) axes.add(spec.replace("-", ""));
            }
            rig.check(axes.size() == 4, "the four sticks are four different axes in the settings file: " + said.toString().strip());
            rig.check(ui.sees("CALIBRATED"), "the controller card says CALIBRATED");
        });
    }
}
