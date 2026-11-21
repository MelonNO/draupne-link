package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MENU and its four menus, row by row: every switch pressed on and off again with the settings file watched, every
 * row that opens a window pressed and the window looked at and closed, and what was set still set after the app is
 * closed and started again.
 */
@Tag("ui")
class MenusScenario {
    static final List<String> MENUS = List.of("SETUP", "VIDEO", "LINK", "TOOLS");
    /** Rows that are a switch: a press changes a setting, a second press puts it back. */
    static final Map<String, List<String>> SWITCHES = Map.of(
            "VIDEO", List.of("CAMERA UPSIDE DOWN", "CAMERA VIEW ON THE MAP", "RECORD VIDEO", "HARDWARE VIDEO DECODING", "OSD"),
            "LINK", List.of("WARNING SOUNDS", "OVERWATCH RING", "ALLOW SPECTATORS", "STANAG 4609 STREAMS"));
    /** Rows that open a window of their own. */
    static final Map<String, List<String>> WINDOWS = Map.of(
            "VIDEO", List.of("OSD LAYOUT"),
            "LINK", List.of("NAME MAP", "STANAG 4609 SETTINGS"),
            "TOOLS", List.of("RC CHECK"));

    /** The menu open and its rows showing. */
    static void open(Ui ui, String menu) {
        List<java.awt.Component> top = ui.all(menu);
        if (top.isEmpty() || ui.whyNotPressable(top.get(0), null) != null) ui.click("MENU");
        ui.click(menu);
        Ui.pause(350);
    }

    static boolean rowShowing(Ui ui, String row) {
        return !ui.all(row).isEmpty();
    }

    private static Map<String, String> settings(AppRig rig) throws Exception {
        return new LinkedHashMap<>(rig.settingsOnDisk());
    }

    /** What differs between two readings of the settings file: key=old -> new. */
    private static List<String> changed(Map<String, String> before, Map<String, String> after) {
        List<String> out = new ArrayList<>();
        for (String k : after.keySet()) if (!after.get(k).equals(before.get(k))) out.add(k + "=" + before.get(k) + " -> " + after.get(k));
        for (String k : before.keySet()) if (!after.containsKey(k)) out.add(k + "=" + before.get(k) + " -> (gone)");
        out.removeIf(s -> s.startsWith("mainWindow=") || s.startsWith("mapWindow="));
        return out;
    }

    private static List<String> pressAndSeeWhatChanges(AppRig rig, String row) throws Exception {
        Map<String, String> before = settings(rig);
        rig.ui.click(row);
        long end = System.currentTimeMillis() + 3000;
        List<String> diff = List.of();
        while (System.currentTimeMillis() < end && (diff = changed(before, settings(rig))).isEmpty()) Ui.pause(100);
        return diff;
    }

    /**
     * 1.0.71 (was the bench's finding F-003): MENU > LINK > STANAG 4609 STREAMS switched off held the whole window
     * for 1.3 to 2.6 s - the switch waited on the window's thread for each stream's thread to end. The freeze watch
     * of every scenario fails this one if it does again.
     */
    @Test
    void switchingTheStanagStreamsOffDoesNotFreezeTheWindow() throws Exception {
        AppRig.scenario("menus-stanag-off").drones(1).setting("advanced", "true").run(rig -> {
            Ui ui = rig.ui;
            open(ui, "LINK");
            ui.click("STANAG 4609 STREAMS"); // on
            Ui.pause(1500);
            if (!rowShowing(ui, "STANAG 4609 STREAMS")) open(ui, "LINK");
            ui.click("STANAG 4609 STREAMS"); // off
            Ui.pause(3500);
            rig.film.remark("The longest answer of the window: " + rig.watch.maxLagMs() + " ms (a freeze is over " + de.droiddrone.bench.Watch.FREEZE_MS + " ms).");
        });
    }

    @Test
    void everyMenuOpensAndEverySwitchSwitches() throws Exception {
        AppRig.scenario("menus-switches").drones(1).run(rig -> {
            Ui ui = rig.ui;
            // ADVANCED first: it shows the rows few pilots need, and they are to be pressed too
            open(ui, "TOOLS");
            List<String> adv = pressAndSeeWhatChanges(rig, "ADVANCED");
            rig.check(adv.stream().anyMatch(s -> s.startsWith("advanced=")), "ADVANCED is kept in the settings: " + adv);
            for (String menu : MENUS) {
                open(ui, menu);
                ui.shot("the " + menu + " menu");
                rig.dump("menu-" + menu);
                List<String> rows = new ArrayList<>();
                for (String b : ui.buttons()) {
                    if (b.equals("MENU")) break; // the rows come first: they are in the pop-up, in front
                    rows.add(b);
                }
                rig.film.remark(menu + " has the rows: " + rows);
                rig.check(!rows.isEmpty(), "the " + menu + " menu shows rows");
                for (String row : SWITCHES.getOrDefault(menu, List.of())) {
                    if (!rowShowing(ui, row)) open(ui, menu);
                    rig.check(rowShowing(ui, row), "the row " + row + " is in the " + menu + " menu");
                    if (!rowShowing(ui, row)) continue;
                    List<String> on = pressAndSeeWhatChanges(rig, row);
                    if (!rowShowing(ui, row)) open(ui, menu);
                    List<String> off = pressAndSeeWhatChanges(rig, row);
                    rig.film.remark(row + ": first press " + on + ", second press " + off);
                    rig.check(on.size() == 1, row + ": a press changes one setting: " + on);
                    rig.check(off.size() == 1 && on.size() == 1 && off.get(0).split("=")[0].equals(on.get(0).split("=")[0]),
                            row + ": a second press changes the same setting back: " + off);
                }
            }
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
        });
    }

    @Test
    void everyRowThatOpensAWindowOpensItAndItCloses() throws Exception {
        AppRig.scenario("menus-windows").drones(1).setting("advanced", "true").run(rig -> {
            Ui ui = rig.ui;
            for (String menu : List.of("VIDEO", "LINK", "TOOLS")) {
                for (String row : WINDOWS.getOrDefault(menu, List.of())) {
                    List<Window> before = ui.windows();
                    open(ui, menu);
                    rig.check(rowShowing(ui, row), "the row " + row + " is in the " + menu + " menu");
                    if (!rowShowing(ui, row)) continue;
                    ui.click(row);
                    Window opened = null;
                    long end = System.currentTimeMillis() + 5000;
                    while (opened == null && System.currentTimeMillis() < end) {
                        for (Window w : ui.windows()) if (!before.contains(w) && (w instanceof Frame || w instanceof Dialog)) opened = w;
                        Ui.pause(100);
                    }
                    rig.check(opened != null, row + " opens a window");
                    if (opened == null) continue;
                    Window w = opened;
                    String title = w instanceof Frame f ? f.getTitle() : ((Dialog) w).getTitle();
                    rig.film.remark(row + " opens \"" + title + "\"");
                    rig.dump("window-" + row);
                    ui.shot("the window of " + row + ": \"" + title + "\"");
                    ui.closeWindow(w);
                    ui.until("\"" + title + "\" closed", 5000, () -> !w.isShowing());
                }
            }
        });
    }

    @Test
    void whatWasSetIsStillSetAfterARestart() throws Exception {
        Sandbox[] user = new Sandbox[1];
        Map<String, String> atClose = new LinkedHashMap<>();
        AppRig.scenario("menus-restart-1").drones(1).run(rig -> {
            Ui ui = rig.ui;
            user[0] = rig.sandbox;
            open(ui, "TOOLS");
            pressAndSeeWhatChanges(rig, "ADVANCED");
            open(ui, "VIDEO");
            pressAndSeeWhatChanges(rig, "RECORD VIDEO");
            pressAndSeeWhatChanges(rig, "OSD");
            open(ui, "LINK");
            pressAndSeeWhatChanges(rig, "WARNING SOUNDS");
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
            ui.shot("four switches changed");
            rig.check(rig.closeApp(), "the app closes by its X");
            atClose.putAll(rig.settingsOnDisk());
            rig.film.remark("Settings at the close: " + atClose);
            rig.check("true".equals(atClose.get("advanced")), "advanced=true is on disk");
            rig.check("false".equals(atClose.get("recordVideo")), "recordVideo=false is on disk");
        });
        AppRig.scenario("menus-restart-2").drones(1).sameUserAs(user[0]).run(rig -> {
            Ui ui = rig.ui;
            Map<String, String> now = rig.settingsOnDisk();
            for (String key : List.of("advanced", "recordVideo", "osd")) {
                rig.check(String.valueOf(atClose.get(key)).equals(String.valueOf(now.get(key))), key + " is as it was at the close: " + now.get(key));
            }
            open(ui, "TOOLS");
            ui.shot("TOOLS after the restart");
            rig.check(ui.buttons().stream().filter(b -> !b.equals("MENU")).findFirst().isPresent(), "the menu opens after the restart");
            // ADVANCED was on: the rows it shows are there without pressing it again
            open(ui, "VIDEO");
            rig.check(rowShowing(ui, "OSD LAYOUT"), "the advanced row OSD LAYOUT shows after the restart");
            // a switch pressed now changes the setting back: the menu knew its state
            List<String> back = pressAndSeeWhatChanges(rig, "RECORD VIDEO");
            rig.check(back.stream().anyMatch(s -> s.equals("recordVideo=false -> true")), "RECORD VIDEO was off after the restart and a press puts it on: " + back);
            ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
        });
    }
}
