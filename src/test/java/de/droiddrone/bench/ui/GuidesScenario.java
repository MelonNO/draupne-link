package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Ui;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The app's two guides walked through as a new pilot meets them, every page photographed and looked at: the setup
 * guide that opens by itself at the very first start, and the tour. The bench presses on as far as a guide lets it
 * without the things it has no stand-in for yet (a flight controller on USB that answers Betaflight's every word);
 * where a page waits for one, the film says so and the walk ends there.
 */
@Tag("ui")
class GuidesScenario {
    /** The showing buttons inside the first showing part of this class. */
    static List<AbstractButton> buttonsIn(Ui ui, String holderClass) {
        List<AbstractButton> out = new ArrayList<>();
        for (Component c : ui.components()) {
            if (!c.getClass().getSimpleName().equals(holderClass)) continue;
            List<Component> inside = new ArrayList<>();
            Ui.edt(() -> {
                collect((Container) c, inside);
                return null;
            });
            for (Component x : inside) if (x instanceof AbstractButton b && x.isShowing()) out.add(b);
            break;
        }
        return out;
    }

    private static void collect(Container k, List<Component> into) {
        for (Component c : k.getComponents()) {
            if (!c.isVisible()) continue;
            into.add(c);
            if (c instanceof Container kk) collect(kk, into);
        }
    }

    private static String pageOf(Ui ui, Window wizard) {
        List<String> words = new ArrayList<>();
        for (Component c : ui.components()) {
            if (SwingUtilities.getWindowAncestor(c) != wizard) continue;
            String t = Ui.edt(() -> Ui.textOf(c));
            if (t != null && !t.isBlank() && !(c instanceof AbstractButton)) words.add(t);
        }
        return words.size() > 3 ? words.get(2) + " - " + words.get(3) : String.join(" - ", words);
    }

    @Test
    void theVeryFirstStartOpensTheSetupGuideAndItsPagesFollow() throws Exception {
        AppRig.scenario("guide-first-start").firstStart().run(rig -> {
            Ui ui = rig.ui;
            ui.until("the setup guide opens by itself", 15_000, () -> ui.window("DRAUPNE Link setup") != null);
            Window wizard = ui.window("DRAUPNE Link setup");
            rig.check(ui.sees("STEP 1 OF"), "it starts at step 1");
            Set<String> pages = new LinkedHashSet<>();
            for (int i = 0; i < 20; i++) {
                String page = pageOf(ui, wizard);
                if (!pages.add(page)) break;
                ui.shot("setup guide: " + page);
                rig.dump("setup-page-" + pages.size());
                Component next = ui.all("NEXT").stream().findFirst().orElse(null);
                if (next == null || !Ui.edt(next::isEnabled)) {
                    rig.film.remark("The setup guide's page \"" + page + "\" does not let NEXT be pressed: " + ui.buttons().subList(0, Math.min(8, ui.buttons().size())));
                    break;
                }
                ui.click("NEXT");
                Ui.pause(700);
            }
            rig.film.remark("Setup guide pages walked: " + pages.size() + " - " + pages);
            rig.check(pages.size() >= 2, "NEXT leads on from the first page");
            // BACK leads back, CLOSE closes it; the app is there and offers the guide again from the menu
            if (Ui.edt(() -> ui.find("BACK").isEnabled())) {
                ui.click("BACK");
                Ui.pause(500);
            }
            ui.click("CLOSE");
            Ui.pause(1000);
            if (ui.window("DRAUPNE Link setup") != null) { // "really close?" on some pages
                rig.film.remark("After CLOSE: " + ui.buttons().subList(0, Math.min(6, ui.buttons().size())));
                for (String yes : List.of("Yes", "YES", "OK", "CLOSE SETUP", "Close")) {
                    if (ui.has(yes)) {
                        ui.click(yes);
                        break;
                    }
                }
            }
            ui.until("the setup guide closed", 8000, () -> ui.window("DRAUPNE Link setup") == null);
            rig.check("true".equals(rig.settingsOnDisk().get("setup.offered")), "the guide is not opened by itself again (setup.offered)");
            ui.shot("the main window after the guide");
            MenusScenario.open(ui, "SETUP");
            rig.check(MenusScenario.rowShowing(ui, "FIRST-TIME SETUP"), "SETUP > FIRST-TIME SETUP offers it again");
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
        });
    }

    @Test
    void theTourStartsAndWalksOn() throws Exception {
        AppRig.scenario("guide-tour").drones(0).run(rig -> {
            Ui ui = rig.ui;
            MenusScenario.open(ui, "SETUP");
            ui.click("TOUR");
            ui.until("the tour's first note", 10_000, () -> !buttonsIn(ui, "TourNotice").isEmpty());
            int steps = 0;
            String last = "";
            for (int i = 0; i < 80; i++) {
                List<AbstractButton> buttons = buttonsIn(ui, "TourNotice");
                AbstractButton go = null;
                for (AbstractButton b : buttons) {
                    String t = Ui.edt(() -> Ui.textOf(b));
                    if (t != null && !t.isBlank() && Ui.edt(b::isEnabled)) go = b;
                }
                List<String> said = new ArrayList<>();
                for (Component c : ui.components()) {
                    if (SwingUtilities.getAncestorNamed("x", c) == null && inClass(c, "TourNotice")) {
                        String t = Ui.edt(() -> Ui.textOf(c));
                        if (t != null && !t.isBlank() && !(c instanceof AbstractButton)) said.add(t);
                    }
                }
                String now = String.join(" | ", said);
                if (go == null) {
                    rig.film.remark("The tour waits for a press of the app itself at: " + now);
                    ui.shot("tour: waits for the pilot - " + (now.length() > 80 ? now.substring(0, 80) + "..." : now));
                    break;
                }
                if (now.equals(last) && i > 0) {
                    rig.film.remark("The tour did not move on from: " + now);
                    break;
                }
                last = now;
                steps++;
                AbstractButton press = go;
                ui.click(press);
                Ui.pause(1200);
                if (buttonsIn(ui, "TourNotice").isEmpty()) {
                    Ui.pause(2500); // between two notes
                    if (buttonsIn(ui, "TourNotice").isEmpty()) break;
                }
            }
            rig.film.remark("Tour notes walked by their own button: " + steps);
            rig.check(steps >= 1, "the tour's first note has a button that leads on");
            // Exit tour: its X, wherever the tour is
            for (Component c : ui.components()) {
                if (c.getClass().getSimpleName().equals("CloseButton") && inClass(c, "TourNotice")) {
                    ui.click(c);
                    break;
                }
            }
            Ui.pause(2500);
            for (String yes : List.of("END TOUR", "End tour", "Yes", "OK")) {
                if (ui.has(yes)) {
                    ui.click(yes);
                    break;
                }
            }
            Ui.pause(2500);
            ui.shot("after the tour was left");
            rig.check(ui.has("PILOT UAV") || ui.has("MENU"), "the main window is there after leaving the tour");
        });
    }

    private static boolean inClass(Component c, String simpleName) {
        for (Component p = c; p != null; p = p.getParent()) if (p.getClass().getSimpleName().equals(simpleName)) return true;
        return false;
    }
}
