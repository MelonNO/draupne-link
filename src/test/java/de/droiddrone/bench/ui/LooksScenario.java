package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualDrone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The app looked at rather than tested: its main screens brought up at several window sizes, a picture of each, the
 * pictures' own checks run on every one (words cut off, parts sticking out, controls over each other) - and a random
 * presser that goes at every button it finds for a while, looking for an error nobody caught and for a window that
 * hangs. What these find is for a person to look through: the report's "found on the pictures" list and the films.
 * Windows' text scaling is a setting of the whole run: {@code .\bench.ps1 ui -Tests *Looks* -Scale 1.5}.
 */
@Tag("ui")
class LooksScenario {
    private static void screens(AppRig rig, String at) {
        Ui ui = rig.ui;
        Pilot pilot = new Pilot(rig);
        ui.shot(at + ": the main window, nothing connected");
        for (String menu : MenusScenario.MENUS) {
            MenusScenario.open(ui, menu);
            ui.shot(at + ": the " + menu + " menu");
        }
        ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
        if (ui.has("EXPAND")) {
            ui.click("EXPAND");
            ui.shot(at + ": the controller card with sticks and channels");
            if (ui.has("MINIMIZE")) ui.click("MINIMIZE");
        }
        String name = String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem())).trim().split("\\s+")[0];
        VirtualDrone drone = rig.drone(name);
        pilot.connect();
        pilot.waitForVideo();
        ui.until("RC OUTPUT: LIVE", 10_000, () -> ui.sees("RC OUTPUT: LIVE"));
        ui.shot(at + ": connected, disarmed");
        pilot.arm(drone);
        pilot.throttle(0.15);
        pilot.until("the drone to climb", 15_000, () -> drone.readout().altM() > 3);
        pilot.throttle(-0.2);
        ui.shot(at + ": armed and flying");
        pilot.throttle(-1);
        pilot.until("the drone to come down", 30_000, () -> drone.readout().altM() < 0.5);
        pilot.disarm(drone);
        pilot.standby();
        FleetScenario.openFleet(ui);
        ui.shot(at + ": the map window with FLEET");
        ui.click("MAP TOOLS");
        ui.shot(at + ": MAP TOOLS, DRAW");
        ui.click("TOOLS");
        ui.shot(at + ": MAP TOOLS, TOOLS");
        ui.click("DATA");
        ui.shot(at + ": MAP TOOLS, DATA");
        ui.click("PREVIOUS FLIGHTS");
        ui.until("the Previous flights window", 10_000, () -> ui.window("Previous flights") != null);
        Window flights = ui.window("Previous flights");
        Ui.pause(1500);
        ui.shot(at + ": PREVIOUS FLIGHTS with the flight just flown");
        fits(rig, flights, "PREVIOUS FLIGHTS");
        ui.closeWindow(flights);
        ui.click("LAST KNOWN");
        ui.until("the Last known window", 5000, () -> ui.window("Last known") != null);
        ui.shot(at + ": LAST KNOWN");
        fits(rig, ui.window("Last known"), "LAST KNOWN");
        ui.click("CLOSE");
        ui.click("FIND DRONE");
        Ui.pause(1200);
        ui.shot(at + ": FIND DRONE");
        Window find = ui.windows().stream().filter(w -> w instanceof java.awt.Dialog && w != flights).findFirst().orElse(null);
        fits(rig, find, "FIND DRONE");
        if (find != null) ui.closeWindow(find);
    }

    /**
     * A window fits the screen it is on - the made-up small one in the small tour (F-018: FIND DRONE's buttons were
     * below the edge of a 584-high screen).
     */
    private static void fits(AppRig rig, Window w, String what) {
        if (w == null) return;
        java.awt.Rectangle screen = de.droiddrone.pc.gui.BenchAccess.screen(), b = Ui.edt(w::getBounds);
        rig.check(b.width <= screen.width && b.height <= screen.height, what + " fits its screen: " + b.width + " x " + b.height + " on " + screen.width + " x " + screen.height);
    }

    @Test
    void theMainScreensAtTheScreensOwnSize() throws Exception {
        AppRig.scenario("looks-full-size").drones(3).run(rig -> screens(rig, "full size"));
    }

    @Test
    void theMainScreensInASmallWindow() throws Exception {
        // 1093 x 584 is what a 1366 x 768 laptop at Windows' 125 % leaves a window: the smallest the app is made for
        AppRig.scenario("looks-1093x584").drones(3).size(1093, 584).run(rig -> screens(rig, "1093 x 584"));
    }

    /** Buttons the presser leaves alone: they end the app, or start what the bench has no stand-in for. */
    private static final List<String> NOT_FOR_THE_MONKEY = List.of("RESET SETUP", "VIRTUAL FLEET", "FIRST-TIME SETUP", "SET UP A NEW DRONE", "PC SETUP",
            "DEMO MODE", "TOUR", "CALIBRATE", "UPDATE", "UPDATE PHONE APP");

    @Test
    void aRandomPresserFindsNoErrorAndNoFreeze() throws Exception {
        long seed = Long.getLong("bench.seed", 20261119L);
        long ms = Boolean.getBoolean("bench.quick") ? 45_000 : 150_000;
        AppRig.scenario("looks-random-presser").drones(3).setting("advanced", "true").run(rig -> {
            Ui ui = rig.ui;
            ui.autoShot(false);
            Random random = new Random(seed);
            long end = System.currentTimeMillis() + ms;
            int presses = 0, refused = 0;
            List<String> trail = new ArrayList<>();
            while (System.currentTimeMillis() < end) {
                List<Component> can = new ArrayList<>();
                for (Component c : ui.components()) {
                    if (!(c instanceof AbstractButton b)) continue;
                    String words = Ui.edt(() -> Ui.textOf(b));
                    String label = words == null || words.isBlank() ? String.valueOf(b.getToolTipText()) : words;
                    boolean title = false;
                    for (Component p = c.getParent(); p != null; p = p.getParent()) if (p.getClass().getName().contains("FlatTitlePane")) title = true;
                    if (title || NOT_FOR_THE_MONKEY.stream().anyMatch(n -> label.toUpperCase().startsWith(n))) continue;
                    if (ui.whyNotPressable(c, null) == null) can.add(c);
                }
                if (can.isEmpty()) {
                    ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
                    Ui.pause(300);
                    continue;
                }
                Component c = can.get(random.nextInt(can.size()));
                String what = Ui.edt(() -> {
                    String t = Ui.textOf(c);
                    return t == null || t.isBlank() ? String.valueOf(((AbstractButton) c).getToolTipText()) : t;
                });
                try {
                    ui.click(c);
                    presses++;
                    trail.add(what.length() > 40 ? what.substring(0, 40) : what);
                } catch (Ui.NotPressable e) {
                    refused++;
                }
                // a dialog that asks: answered at random; a file chooser: cancelled
                if (random.nextInt(5) == 0) ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
                Ui.pause(150 + random.nextInt(400));
                if (presses % 25 == 0) ui.shot("after " + presses + " random presses (the last: " + what + ")");
                if (!rig.watch.problems().isEmpty()) break; // the first error or freeze: the trail to it is the finding
            }
            rig.film.remark("Random presser, seed " + seed + ": " + presses + " presses, " + refused + " not pressable at the moment. The last 30: "
                    + trail.subList(Math.max(0, trail.size() - 30), trail.size()));
            ui.shot("the random presser's last picture");
            rig.radio.handsOff();
            rig.radio.aux(1, -1);
            rig.check(presses > 20, "the presser found buttons to press: " + presses);
        });
    }
}
