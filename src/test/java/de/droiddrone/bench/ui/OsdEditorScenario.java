package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.gui.BenchAccess;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OSD window (MENU > VIDEO > OSD LAYOUT) used as a pilot uses it: every element chosen in the list and its card
 * read, one switched on and off, one dragged in the preview, RESET ELEMENT and RESET ALL - with the settings file
 * watched at each press. Until 2026-11-20 the window was only opened, photographed a fifth of a second later and
 * closed; a reader of those pictures saw the chosen element's description cut in half, and nothing could say whether
 * it stays that way.
 */
@Tag("ui")
class OsdEditorScenario {
    private static final String TITLE = "OSD - DRAUPNE Link";
    private static final Pattern COUNT = Pattern.compile("(\\d+) / (\\d+) ON");

    /** The showing parts of this window only. */
    private static List<Component> in(Ui ui, Window w) {
        List<Component> out = new ArrayList<>();
        for (Component c : ui.components()) if (Ui.edt(() -> SwingUtilities.getWindowAncestor(c)) == w) out.add(c);
        return out;
    }

    private static List<Component> rows(Ui ui, Window w) {
        List<Component> out = in(ui, w);
        out.removeIf(c -> !c.getClass().getSimpleName().equals("Row"));
        return out;
    }

    /**
     * Every wrapped (HTML) label of the window whose words need more height at the width it has than it has: what a
     * pilot sees as a last line cut in half. Measured on the words themselves, not asked of the label.
     */
    private static List<String> cut(Ui ui, Window w) {
        List<Component> parts = in(ui, w);
        return Ui.edt(() -> {
            List<String> out = new ArrayList<>();
            for (Component c : parts) {
                if (!(c instanceof JLabel l) || l.getWidth() <= 0) continue;
                Object view = l.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
                if (!(view instanceof javax.swing.text.View v)) continue;
                Insets in = l.getInsets();
                float before = v.getPreferredSpan(javax.swing.text.View.X_AXIS);
                v.setSize(Math.max(1, l.getWidth() - in.left - in.right), 0);
                int need = (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + in.top + in.bottom;
                v.setSize(before, 0);
                if (need > l.getHeight() + 2) out.add("\"" + Ui.norm(l.getText()) + "\" needs " + need + " px of height at its " + l.getWidth() + " px of width, has " + l.getHeight());
            }
            return out;
        });
    }

    /** "23 / 41 ON" over the list: {on, all}; null when it is not there. */
    private static int[] count(Ui ui) {
        for (String t : ui.texts()) {
            Matcher m = COUNT.matcher(t);
            if (m.matches()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return null;
    }

    private static Map<String, String> osd(AppRig rig) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        rig.settingsOnDisk().forEach((k, v) -> {
            if (k.startsWith("osd.")) out.put(k, v);
        });
        return out;
    }

    /** The row brought under the mouse with the wheel, as a pilot does, when the list has scrolled it away. */
    private static boolean reach(Ui ui, Component row) {
        Component list = Ui.edt(() -> row.getParent().getParent()); // what shows the rows: they scroll under it
        for (int i = 0; i < 40 && ui.whyNotPressable(row, new Point(120, 15)) != null; i++) {
            boolean below = Ui.edt(() -> row.getLocationOnScreen().y > list.getLocationOnScreen().y);
            ui.wheel(list, 120, Ui.edt(list::getHeight) / 2, below ? 1 : -1);
            Ui.pause(120);
        }
        return ui.whyNotPressable(row, new Point(120, 15)) == null;
    }

    /** The card's line of where the chosen element stands ("Left-aligned, 1% across, 6% down"); "" when none shows. */
    private static String where(Ui ui) {
        for (String t : ui.texts()) if (t.contains("% across")) return t;
        return "";
    }

    @Test
    void everyElementIsChosenSwitchedMovedAndReset() throws Exception {
        AppRig.scenario("osd-editor").drones(1).setting("advanced", "true").run(rig -> {
            Ui ui = rig.ui;
            Map<String, String> atStart = osd(rig);
            MenusScenario.open(ui, "VIDEO");
            ui.click("OSD LAYOUT");
            ui.until("the OSD window", 5000, () -> ui.window(TITLE) != null);
            Window w = ui.window(TITLE);
            Ui.pause(1500); // opened, grown to the whole screen, laid out again: what a pilot looks at
            ui.shot("the OSD window a second and a half after it opened");
            List<String> cutAtOpen = cut(ui, w);
            rig.check(cutAtOpen.isEmpty(), "no wrapped text of the OSD window is cut off as it stands open: " + cutAtOpen);

            // ---- every element chosen: the card on the right says which, in whole lines
            List<String[]> elements = BenchAccess.osdElements(); // by title, as the list has them
            List<Component> rows = rows(ui, w);
            rig.check(rows.size() == elements.size(), "the list has a row for each of the " + elements.size() + " elements: " + rows.size());
            int[] count = count(ui);
            rig.check(count != null && count[1] == elements.size(), "the list's head counts them: " + (count == null ? "no count" : count[0] + " / " + count[1] + " ON"));
            ui.autoShot(false);
            int chosen = 0;
            List<String> wrongCard = new ArrayList<>(), cutCards = new ArrayList<>();
            for (int i = 0; i < Math.min(rows.size(), elements.size()); i++) {
                Component row = rows.get(i);
                String[] e = elements.get(i);
                if (!reach(ui, row)) {
                    wrongCard.add(e[1] + ": its row can't be reached with the wheel");
                    continue;
                }
                ui.clickAt(row, 120, 15); // on its name: chosen, not switched
                Ui.pause(120);
                chosen++;
                if (!ui.sees(e[1].toUpperCase()) || !ui.sees(e[2])) wrongCard.add(e[1] + ": the card does not show its name and its line \"" + e[2] + "\"");
                for (String c : cut(ui, w)) cutCards.add(e[1] + ": " + c);
                if (i % 8 == 0) ui.shot(e[1] + " chosen");
            }
            ui.autoShot(true);
            rig.check(chosen == elements.size(), "every element's row was pressed: " + chosen + " of " + elements.size());
            rig.check(wrongCard.isEmpty(), "the card shows each chosen element's name and line: " + wrongCard);
            rig.check(cutCards.isEmpty(), "no element's card has its words cut off: " + cutCards);
            rig.check(osd(rig).equals(atStart), "choosing changes no setting: " + osd(rig));

            // ---- one switched on and off: Clock is off until a pilot turns it on
            int clockAt = -1;
            for (int i = 0; i < elements.size(); i++) if (elements.get(i)[0].equals("clock")) clockAt = i;
            Component clock = rows.get(clockAt);
            rig.check(reach(ui, clock), "the Clock row can be reached");
            int on0 = count(ui)[0];
            ui.clickAt(clock, 26, 15); // its switch
            Ui.pause(300);
            Map<String, String> switched = osd(rig);
            rig.check(String.valueOf(switched.get("osd.clock")).startsWith("on,"), "Clock switched on is in the settings file: osd.clock=" + switched.get("osd.clock"));
            rig.check(count(ui)[0] == on0 + 1, "and counted: " + count(ui)[0] + " ON, was " + on0);
            rig.check(ui.first(javax.swing.JButton.class, b -> "RESET ELEMENT".equalsIgnoreCase(Ui.textOf(b))).isEnabled(), "RESET ELEMENT can be pressed now");
            ui.click("Reset element");
            Ui.pause(300);
            rig.check(!String.valueOf(osd(rig).get("osd.clock")).startsWith("on,") && count(ui)[0] == on0, "RESET ELEMENT puts Clock back to off: osd.clock=" + osd(rig).get("osd.clock") + ", " + count(ui)[0] + " ON");

            // ---- one dragged in the preview: the battery block, a tenth of the picture to the right and a fifth down
            Component preview = null;
            for (Component c : in(ui, w)) if (c.getClass().getSimpleName().equals("Preview")) preview = c;
            rig.check(preview != null, "the window has its preview");
            if (preview != null) {
                Component p = preview;
                int pw = Ui.edt(() -> Math.min(p.getWidth(), p.getHeight() * 16 / 9)), ph = pw * 9 / 16;
                int px = Ui.edt(() -> (p.getWidth() - pw) / 2), py = Ui.edt(() -> (p.getHeight() - ph) / 2);
                Map<String, String> before = osd(rig);
                int fromX = px + pw * 5 / 100, fromY = py + ph * 8 / 100;
                ui.clickAt(p, fromX, fromY); // chosen by a press on it in the picture
                Ui.pause(200);
                String whereAtStart = where(ui);
                rig.check(ui.sees("BATTERY") && !whereAtStart.isEmpty(), "a press on the battery block in the preview chooses it: \"" + whereAtStart + "\"");
                ui.drag(p, fromX, fromY, fromX + pw / 10, fromY + ph / 5);
                Ui.pause(400);
                Map<String, String> moved = osd(rig);
                List<String> changed = new ArrayList<>();
                for (String k : moved.keySet()) if (!moved.get(k).equals(before.get(k))) changed.add(k + "=" + before.get(k) + " -> " + moved.get(k));
                rig.check(changed.size() == 1 && changed.get(0).startsWith("osd.battery="), "dragging the battery block moves it and nothing else, saved at once: " + changed);
                String whereMoved = where(ui);
                rig.check(ui.sees("BATTERY") && !whereMoved.equals(whereAtStart), "and the card says where it stands now: \"" + whereMoved + "\"");
                List<String> cutMoved = cut(ui, w);
                rig.check(cutMoved.isEmpty(), "no words cut off with the block moved: " + cutMoved);

                // ---- RESET ALL: asked first, then everything as at the start
                ui.click("Reset all");
                ui.until("the question before RESET ALL", 3000, () -> ui.sees("Reset every OSD element to the standard layout?"));
                ui.click("OK");
                Ui.pause(400);
                Map<String, String> reset = osd(rig);
                rig.check(!String.valueOf(reset.get("osd.battery")).equals(moved.get("osd.battery")) && where(ui).equals(whereAtStart),
                        "RESET ALL puts the battery block back where it stood: \"" + where(ui) + "\", osd.battery=" + reset.get("osd.battery"));
                rig.check(count(ui)[0] == on0, "and as many are on as at the start: " + count(ui)[0]);
            }
            ui.shot("after RESET ALL");
            ui.click("Close");
            ui.until("the OSD window closed", 5000, () -> !w.isShowing());
        });
    }
}
