package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Not a test of anything: the app started in the bench and driven by a small script - for whoever writes the next
 * scenario, to see what a press leads to before writing it down as a test. {@code gradlew benchExplore}; the script
 * is {@code explore.txt} in the run's folder (BENCH_ROOT), one command a line:
 * <pre>
 * start first | start pilot [drones]     (first line; default: pilot 2)
 * set KEY VALUE                          (right after it: lines of the settings file)
 * click WORDS | click2 WORDS (the second of two) | rclick CLASS x y | clickat CLASS x y | dclick CLASS x y
 * choose N ENTRY (the N-th drop-down, 0 = first) | key F3 | type TEXT | wheel CLASS x y notches
 * drag CLASS x1 y1 x2 y2 | fill N TEXT (the N-th text field)
 * aux N VALUE | sticks ROLL PITCH YAW THROTTLE | unplug | plug | freeze | thaw
 * wait MS | waitfor WORDS | shot NAME | tree | buttons | texts | log | asked | problems
 * </pre>
 * tree / buttons / texts / log go to out-NN.txt beside the film's pictures.
 */
@Tag("explore")
class ExploreScenario {
    @Test
    void lookAtTheApp() throws Exception {
        Path script = Sandbox.runRoot().resolve("explore.txt");
        List<String> lines = Files.exists(script) ? Files.readAllLines(script, StandardCharsets.UTF_8) : List.of("wait 6000", "tree", "log");
        AppRig.Plan plan = AppRig.scenario("explore");
        int from = 0;
        if (!lines.isEmpty() && lines.get(0).startsWith("start ")) {
            String[] w = lines.get(0).split("\\s+");
            if (w[1].equals("first")) plan.firstStart();
            plan.drones(w.length > 2 ? Integer.parseInt(w[2]) : w[1].equals("first") ? 0 : 2);
            from = 1;
        } else {
            plan.drones(2);
        }
        while (from < lines.size() && lines.get(from).startsWith("set ")) { // a line of the settings file, before the start
            String[] w = lines.get(from++).split("\\s+", 3);
            plan.setting(w[1], w.length > 2 ? w[2] : "");
        }
        try (AppRig rig = plan.start()) {
            Ui ui = rig.ui;
            int out = 0;
            for (String raw : lines.subList(from, lines.size())) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] w = line.split("\\s+", 2);
                String rest = w.length > 1 ? w[1] : "";
                String[] a = rest.split("\\s+");
                try {
                    switch (w[0]) {
                        case "click" -> ui.click(rest);
                        case "click2" -> ui.click(rest, 1);
                        case "clickat" -> ui.clickAt(byClass(ui, a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                        case "dclick" -> ui.doubleClickAt(byClass(ui, a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                        case "rclick" -> ui.rightClickAt(byClass(ui, a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                        case "drag" -> ui.drag(byClass(ui, a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]));
                        case "fill" -> ui.fill(ui.all(javax.swing.text.JTextComponent.class).get(Integer.parseInt(a[0])), rest.substring(a[0].length()).strip());
                        case "wheel" -> ui.wheel(byClass(ui, a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                        case "choose" -> ui.choose(ui.all(javax.swing.JComboBox.class).get(Integer.parseInt(a[0])), rest.substring(a[0].length()).strip());
                        case "key" -> ui.key(KeyEvent.class.getField("VK_" + rest.toUpperCase()).getInt(null));
                        case "type" -> ui.type(rest);
                        case "aux" -> rig.radio.aux(Integer.parseInt(a[0]), Double.parseDouble(a[1]));
                        case "sticks" -> rig.radio.sticks(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
                        case "unplug" -> rig.radio.plugged(false);
                        case "plug" -> rig.radio.plugged(true);
                        case "freeze" -> rig.radio.frozen(true);
                        case "thaw" -> rig.radio.frozen(false);
                        case "chooseitem" -> {
                            @SuppressWarnings("rawtypes")
                            javax.swing.JComboBox combo = ui.all(javax.swing.JComboBox.class).get(Integer.parseInt(a[0]));
                            ui.choose(combo, String.valueOf(Ui.edt(() -> combo.getItemAt(Integer.parseInt(a[1])))));
                        }
                        case "front" -> ui.front(ui.window(rest));
                        case "close" -> {
                            java.awt.Window win = ui.window(rest);
                            javax.swing.SwingUtilities.invokeLater(() -> win.dispatchEvent(new java.awt.event.WindowEvent(win, java.awt.event.WindowEvent.WINDOW_CLOSING)));
                        }
                        case "wait" -> Ui.pause(Long.parseLong(rest));
                        case "waitfor" -> ui.waitForControl(rest, 20_000);
                        case "shot" -> ui.shot(rest);
                        case "tree" -> write(rig, ++out, "tree", ui.tree());
                        case "buttons" -> write(rig, ++out, "buttons", String.join("\n", ui.buttons()));
                        case "texts" -> write(rig, ++out, "texts", String.join("\n", ui.texts()));
                        case "log" -> write(rig, ++out, "log", String.join("\n", rig.log()));
                        case "asked" -> write(rig, ++out, "asked", String.valueOf(rig.doors.asked()).replace(", ", "\n"));
                        case "problems" -> write(rig, ++out, "problems", String.join("\n\n", rig.problems()));
                        default -> rig.film.remark("explore: unknown command: " + line);
                    }
                } catch (Throwable t) {
                    rig.film.remark("explore: \"" + line + "\" -> " + t);
                    write(rig, ++out, "failed", line + "\n" + t + "\n\nbuttons:\n" + String.join("\n", ui.buttons()));
                }
            }
            write(rig, ++out, "problems-at-end", String.join("\n\n", rig.problems()));
        }
    }

    private static Component byClass(Ui ui, String simpleName) {
        List<Component> found = new ArrayList<>();
        for (Component c : ui.components()) if (c.getClass().getSimpleName().equals(simpleName)) found.add(c);
        if (found.isEmpty()) throw new AssertionError("No showing " + simpleName);
        return found.get(0);
    }

    private static void write(AppRig rig, int n, String what, String text) throws Exception {
        Files.writeString(rig.film.dir().resolve(String.format("out-%02d-%s.txt", n, what)), text, StandardCharsets.UTF_8);
    }
}
