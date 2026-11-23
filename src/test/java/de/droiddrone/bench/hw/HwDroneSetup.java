package de.droiddrone.bench.hw;

import de.droiddrone.bench.Ui;
import de.droiddrone.pc.gui.BenchAccess;
import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.pc.setup.GpsSetup;
import de.droiddrone.pc.setup.MotorTest;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of {@link HwDroneSetupScenario} that need neither the board nor the app: which CLI lines change a board,
 * what a line of "dump all" is the setting of, which differences between two snapshots Drone setup said it made (the
 * judge), the choices a pilot answers for the drone on the bench as its board has them, the live view's attitude,
 * and a setup page's words row by row.
 */
final class HwDroneSetup {
    private HwDroneSetup() {
    }

    // ------------------------------------------------------------------ the CLI's lines

    /** Commands that only list or leave when they come alone ("aux", "serial", "beeper" ...). */
    private static final Set<String> LISTS_ALONE = Set.of("version", "board_name", "manufacturer_id", "mcu_id", "signature", "aux", "serial", "feature",
            "beeper", "beacon", "map", "name", "status", "tasks", "exit", "save", "rxfail", "profile", "rateprofile", "mixer", "help", "resource", "timer",
            "dma", "adjrange", "rxrange", "vtxtable", "led", "color", "mode_color", "servo", "smix", "mmix", "batch");

    /**
     * A CLI line that changes nothing on the board: a question ("get x", "diff all", "beeper list", "aux" alone ...),
     * the CLI's way out ("exit"), its entry, Enter alone - and "save", which keeps what was changed and is no change of
     * its own. Every other line is a write.
     */
    static boolean reads(String line) {
        String l = line == null ? "" : line.strip();
        if (l.isEmpty() || l.equals("#")) return true;
        String[] w = l.split("\\s+");
        String first = w[0].toLowerCase(Locale.ROOT);
        if (w.length == 1) return LISTS_ALONE.contains(first);
        return switch (first) {
            case "get", "diff", "dump", "help" -> true;
            case "beeper" -> w.length == 2 && w[1].equalsIgnoreCase("list");
            default -> false;
        };
    }

    /**
     * The setting a CLI line (as written, or as "dump all" prints it) is about: "set x = 1" and "set x=1" are "set x",
     * "aux 3 ..." is "aux 3", "feature -GPS" and "feature GPS" are "feature GPS", "beeper -X" is "beeper X", "serial 2
     * ..." is "serial 2", "resource MOTOR 1 B00" is "resource MOTOR 1", "map AETR1234" is "map".
     */
    static String key(String line) {
        String l = line == null ? "" : line.strip();
        if (l.isEmpty()) return "";
        String[] w = l.split("\\s+");
        String first = w[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "set": {
                String rest = l.substring(3).strip();
                int eq = rest.indexOf('=');
                return "set " + (eq >= 0 ? rest.substring(0, eq) : rest).strip().toLowerCase(Locale.ROOT);
            }
            case "feature", "beeper", "beacon":
                return w.length > 1 ? first + " " + w[1].replaceFirst("^-", "").toUpperCase(Locale.ROOT) : first;
            case "resource", "dma":
                return w.length > 2 ? first + " " + w[1].toUpperCase(Locale.ROOT) + " " + w[2] : first + (w.length > 1 ? " " + w[1] : "");
            case "map", "mixer", "name", "board_name", "manufacturer_id", "mcu_id", "signature":
                return first;
            default:
                return w.length > 1 ? first + " " + w[1] : first;
        }
    }

    /**
     * The words the app's own description of a change has for this command (its "Betaflight: ..." line in the log,
     * made of the changes' descriptions: BetaflightPlan, BatterySetup, GpsSetup, Beepers, SetupPlanner): a written
     * command whose words are not in that line was written without being said. HwDroneSetupTest holds this to the
     * app's planners.
     */
    static String named(String command) {
        String k = key(command);
        if (k.startsWith("set ")) {
            String name = k.substring(4);
            if (name.equals("failsafe_procedure")) return "Failsafe:";
            if (name.equals(BATTERY_WARNING) || name.equals(BATTERY_MIN)) return "Battery: low";
            return name;
        }
        if (k.startsWith("feature ")) return k;           // "GPS on (feature GPS)"
        if (k.startsWith("serial ")) return "UART";       // "GPS on UART3", "off UART2"
        if (k.startsWith("beeper ") || k.startsWith("beacon ")) return "Beepers:";
        if (k.startsWith("aux ")) return "Modes:";
        return k;
    }

    private static final String BATTERY_WARNING = "vbat_warning_cell_voltage", BATTERY_MIN = "vbat_min_cell_voltage";

    // ------------------------------------------------------------------ the app's log

    /** The CLI commands the app's log says Drone setup wrote ("Betaflight CLI: set x = 1 -> (ok)"), in order. */
    static List<String> written(List<String> appLog) {
        List<String> out = new ArrayList<>();
        for (String line : appLog) {
            int at = line.indexOf(WRITTEN);
            if (at < 0) continue;
            String rest = line.substring(at + WRITTEN.length());
            int arrow = rest.indexOf(" -> "); // the command has none; Betaflight's answer after it may
            out.add((arrow >= 0 ? rest.substring(0, arrow) : rest).strip());
        }
        return out;
    }

    private static final String WRITTEN = "Betaflight CLI: ";

    /**
     * What Drone setup said it changed: each of its "Betaflight: &lt;the changes&gt;. Its settings before: &lt;the
     * backup&gt;" lines, the backup cut off; empty when it wrote nothing.
     */
    static List<String> said(List<String> appLog) {
        List<String> out = new ArrayList<>();
        for (String line : appLog) {
            int at = line.indexOf("Betaflight: "), before = line.indexOf(". Its settings before:");
            if (at >= 0 && before > at) out.add(line.substring(at + "Betaflight: ".length(), before).strip());
        }
        return out;
    }

    // ------------------------------------------------------------------ the judge

    /** One difference between the snapshot before Drone setup and the board after it, and whether Drone setup said it. */
    record Difference(String line, boolean said, String why) {
        String words() {
            return (said ? "as Drone setup said - " : "NOT SAID BY DRONE SETUP - ") + line + (why.isEmpty() ? "" : " (" + why + ")");
        }
    }

    /**
     * What the board after Drone setup is, against the snapshot before it.
     * @param differences each difference ({@link BoardSnapshot#differences})
     * @param wrong       what Drone setup did not say: a difference no word of its covers, a command it wrote and did
     *                    not name, a CLI line it sent that its log does not have
     * @param notes       what is so and no fault: a command that changed nothing that shows, the defaults' note
     */
    record Verdict(List<Difference> differences, List<String> wrong, List<String> notes) {
    }

    /**
     * Holds the board after Drone setup to what Drone setup said.
     * @param differences      the snapshot's differences from the board now ({@link BoardSnapshot#differences})
     * @param written          the CLI commands the app's log says it wrote ({@link #written})
     * @param said             the app's own lines saying what it changed ({@link #said})
     * @param sent             the CLI lines that changed something as the tap saw them go to the board, or null when
     *                         nothing stood between the app and the board ({@link SetupTap#cliWrites})
     * @param calibrated       CALIBRATE was pressed and the board was sent MSP_ACC_CALIBRATION
     * @param receiverSwitched the receiver was switched over MSP (MSP_SET_FEATURE_CONFIG sent)
     */
    static Verdict judge(List<String> differences, List<String> written, List<String> said, List<String> sent, boolean calibrated, boolean receiverSwitched) {
        String words = String.join(" | ", said);
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (String c : written) byKey.computeIfAbsent(key(c), k -> new ArrayList<>()).add(c);
        boolean receiverSaid = receiverSwitched && words.contains("Receiver: MSP RX input");
        List<Difference> out = new ArrayList<>();
        List<String> wrong = new ArrayList<>(), notes = new ArrayList<>();
        Set<String> changedKeys = new LinkedHashSet<>();
        for (String d : differences) {
            String text = dumpLine(d);
            if (text != null) {
                String k = key(text);
                changedKeys.add(k);
                if (byKey.containsKey(k)) {
                    out.add(new Difference(d, true, "written: " + String.join("; ", byKey.get(k))));
                } else if (k.equals("set acc_calibration") && calibrated) {
                    out.add(new Difference(d, true, "CALIBRATE: Betaflight levels its accelerometer where the drone sits and saves it itself (MSP_ACC_CALIBRATION)"));
                } else if (k.startsWith("feature RX_") && receiverSaid) {
                    out.add(new Difference(d, true, "\"Receiver: MSP RX input\": switched over MSP before the CLI, kept by its save"));
                } else {
                    out.add(new Difference(d, false, "no command of Drone setup's is about " + k));
                }
                continue;
            }
            String fact = mspFact(d);
            String why = fact == null ? null : factSaid(fact, byKey.keySet(), receiverSaid);
            out.add(why == null ? new Difference(d, false, fact == null ? "not understood" : "nothing Drone setup said changes " + fact)
                    : new Difference(d, true, why));
        }
        for (Difference d : out) if (!d.said()) wrong.add("a difference Drone setup did not say it made: " + d.line());
        for (String c : written) {
            String name = named(c);
            if (!words.contains(name)) wrong.add("Drone setup wrote \"" + c + "\" and its words do not name it (no \"" + name + "\" in: " + (words.isEmpty() ? "nothing said" : words) + ")");
            else if (!changedKeys.contains(key(c))) notes.add("\"" + c + "\" written, and no line of the dump shows it changed (it was so already, or it is not in a dump)");
        }
        if (sent != null) {
            List<String> left = new ArrayList<>(written);
            for (String s : sent) {
                if (!left.remove(s)) wrong.add("the board was sent \"" + s + "\", and the app's log has no such write");
            }
            for (String l : left) wrong.add("the app's log says \"" + l + "\" was written, and the board was not sent it");
        }
        return new Verdict(out, wrong, notes);
    }

    /** "dump all: only in the snapshot: [profile 0] set x = 1" -> "set x = 1"; null for a line that is no dump line. */
    static String dumpLine(String difference) {
        for (String head : new String[]{"dump all: only in the snapshot: ", "dump all: only on the board now: "}) {
            if (difference.startsWith(head)) return difference.substring(head.length()).replaceFirst("^\\[[^]]*]\\s*", "");
        }
        return null;
    }

    /** "MSP features: 00 in the snapshot, 01 now" -> "features"; null for another line. */
    static String mspFact(String difference) {
        if (!difference.startsWith("MSP ")) return null;
        int colon = difference.indexOf(':');
        return colon > 4 ? difference.substring(4, colon) : null;
    }

    /** Why an MSP fact that changed is what Drone setup said; null when nothing it wrote changes it. */
    private static String factSaid(String fact, Set<String> keys, boolean receiverSaid) {
        boolean any;
        switch (fact) {
            case "features":
                any = keys.stream().anyMatch(k -> k.startsWith("feature "));
                return any || receiverSaid ? "the feature mask: " + (any ? "a feature written" : "the receiver switched to MSP") : null;
            case "mode_ranges":
                return keys.stream().anyMatch(k -> k.startsWith("aux ")) ? "the modes' ranges: aux lines written" : null;
            case "beeper":
                return keys.stream().anyMatch(k -> k.startsWith("beeper ") || k.startsWith("beacon ")) ? "the beepers: beeper and beacon lines written" : null;
            case "rx_map":
                return keys.contains("map") ? "the channel map: map written" : null;
            case "mixer_and_props_direction":
                return keys.contains("mixer") || keys.contains("set yaw_motors_reversed") ? "the mixer or props direction written" : null;
            case "motor_order":
                return keys.contains("set motor_output_reordering") ? "the motor order written" : null;
            default:
                return null;
        }
    }

    /**
     * What the app sent the board that Drone setup never may in this scenario: the ESCs' passthrough (Read ESCs, F-080's
     * own probes), a DShot command (spin direction, beeps), a new motor order, props in / out, an EEPROM write or MSP's
     * reboot (neither is any row's way of writing), a motor value above STOP (the motor test's box is never ticked) -
     * and MSP_ACC_CALIBRATION unless CALIBRATE was pressed. Empty: none of it.
     */
    static List<String> forbidden(List<SetupTap.Event> events, boolean calibratePressed) {
        List<String> out = new ArrayList<>();
        for (SetupTap.Event e : events) {
            if (e.kind().equals("msp")) {
                switch (e.code()) {
                    case Msp.SET_PASSTHROUGH -> out.add("MSP_SET_PASSTHROUGH (the ESCs' passthrough, as Read ESCs)");
                    case Msp.SET_MIXER_CONFIG -> out.add("MSP_SET_MIXER_CONFIG (props in / out)");
                    case Msp.EEPROM_WRITE -> out.add("MSP_EEPROM_WRITE");
                    case Msp.REBOOT -> out.add("MSP_REBOOT");
                    case Msp.SET_MOTOR -> {
                        if (!stopOnly(e.text())) out.add("MSP_SET_MOTOR above STOP (" + e.text() + ")");
                    }
                    case ACC_CALIBRATION -> {
                        if (!calibratePressed) out.add("MSP_ACC_CALIBRATION without CALIBRATE pressed");
                    }
                    default -> {
                    }
                }
            } else if (e.kind().equals("msp2")) {
                if (e.code() == Msp.SEND_DSHOT_COMMAND) out.add("MSP2_SEND_DSHOT_COMMAND (" + e.text() + ")");
                if (e.code() == Msp.SET_MOTOR_OUTPUT_REORDERING) out.add("MSP2_SET_MOTOR_OUTPUT_REORDERING (" + e.text() + ")");
            }
        }
        return out;
    }

    /** Betaflight's MSP_ACC_CALIBRATION: what CALIBRATE sends (DroneSetup's MotorsPage). */
    static final int ACC_CALIBRATION = 205;

    /** An MSP_SET_MOTOR payload (as hex) with every output at STOP. */
    static boolean stopOnly(String payloadHex) {
        byte[] p;
        try {
            p = Msp.unhex(payloadHex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (int i = 0; i + 1 < p.length; i += 2) if (Msp.u16(p, i) != MotorTest.STOP) return false;
        return true;
    }

    // ------------------------------------------------------------------ the choices a pilot answers, as the board has them

    /**
     * The settings the app is started with so that Drone setup's questions are answered as the board on the bench
     * has them - GPS and its UART, the compass, the cells - and what is said of each; {@code refuse} why CONFIRM must
     * not be pressed (null: it may). The rest is the page's own default.
     */
    record Choices(Map<String, String> settings, List<String> words, String refuse) {
    }

    /**
     * From the snapshot's "dump all": GPS Yes with feature GPS on (and the UART whose function it is), No without;
     * the compass Yes unless mag_hardware is NONE; the cells as Betaflight forces them - the page takes those itself -
     * or, when it counts them itself, the hardware file's (none there: CONFIRM is not pressed, as it would force the
     * page's 6S on the drone).
     * @param cellKeys Drone setup's two settings for the cells: the count, and that the pilot confirmed it
     */
    static Choices choices(String dumpAll, int fileCells, String[] cellKeys) {
        Map<String, String> settings = new LinkedHashMap<>();
        List<String> words = new ArrayList<>();
        String refuse = null;
        List<String> lines = dumpAll.lines().map(String::strip).toList();
        boolean gps = lines.contains("feature GPS");
        String serial = String.join("\n", lines.stream().filter(l -> l.startsWith("serial ")).toList());
        int uart = GpsSetup.gpsUart(serial);
        String mag = GpsSetup.magHardware(lines.stream().filter(l -> l.startsWith("set mag_hardware")).findFirst().orElse(""));
        int forced = BetaflightPlan.forcedCells(lines.stream().filter(l -> l.startsWith("set force_battery_cell_count")).findFirst().orElse(""));
        settings.put(GpsSetup.GPS_KEY, gps ? "yes" : "no");
        words.add("GPS " + (gps ? "Yes (feature GPS on)" : "No (feature GPS off)"));
        if (gps && uart > 0) {
            settings.put(GpsSetup.UART_KEY, Integer.toString(uart));
            words.add("its port UART" + uart + " (the GPS function is on it)");
        } else if (gps) {
            words.add("no UART has the GPS function: the page's own UART" + GpsSetup.DEFAULT_UART);
        }
        boolean compass = !mag.isEmpty() && !mag.equals("NONE");
        settings.put(GpsSetup.COMPASS_KEY, compass ? "yes" : "no");
        words.add("compass " + (compass ? "Yes" : "No") + " (mag_hardware " + (mag.isEmpty() ? "not in the dump" : mag) + ")");
        if (forced > 0) {
            words.add("the cells as Betaflight forces them, " + forced + "S - the page takes that count itself");
        } else if (forced == 0 && fileCells > 0) {
            settings.put(cellKeys[0], Integer.toString(fileCells));
            settings.put(cellKeys[1], "true");
            words.add("Betaflight counts the cells itself: the hardware file's " + fileCells + "S chosen (CONFIRM forces it)");
        } else if (forced == 0) {
            refuse = "Betaflight counts the cells itself and the hardware file names no battery.cells: CONFIRM would force the page's "
                    + BetaflightPlan.DEFAULT_CELLS + "S on this drone";
            words.add(refuse);
        } else {
            words.add("no force_battery_cell_count in the dump: the page's own count");
        }
        return new Choices(settings, words, refuse);
    }

    // ------------------------------------------------------------------ the live view

    private static final Pattern PITCH = Pattern.compile("nose (up|down) (\\d+)"), ROLL = Pattern.compile("(left|right) side down (\\d+)");

    /**
     * The live view's line ("Betaflight: nose up 2°, left side down 1°", as Orientation.describe writes it) as
     * {roll, pitch} in degrees, right side down and nose down positive; null when the line is no such reading.
     */
    static double[] attitude(String reading) {
        if (reading == null || !reading.contains("Betaflight:")) return null;
        String r = reading.substring(reading.indexOf("Betaflight:"));
        boolean noseLevel = r.contains("nose level"), sidesLevel = r.contains("sides level");
        Matcher p = PITCH.matcher(r), q = ROLL.matcher(r);
        boolean pitchRead = p.find(), rollRead = q.find();
        if (!(noseLevel || pitchRead) || !(sidesLevel || rollRead)) return null;
        double pitch = noseLevel ? 0 : Integer.parseInt(p.group(2)) * (p.group(1).equals("down") ? 1 : -1);
        double roll = sidesLevel ? 0 : Integer.parseInt(q.group(2)) * (q.group(1).equals("right") ? 1 : -1);
        return new double[]{roll, pitch};
    }

    /** Within the app's own "reads flat" (3 degrees each way, DroneSetup's MotorsPage). */
    static boolean flat(double[] rollPitch) {
        return rollPitch != null && Math.abs(rollPitch[0]) <= 3 && Math.abs(rollPitch[1]) <= 3;
    }

    // ------------------------------------------------------------------ a page's words

    /**
     * Everything a page shows, as a pilot reads it, in order, one line a thing: a check row as "TITLE [STATE]: its words
     * (ITS BUTTONS)", a setting's line as "LABEL [choice] ...: its words", other words as they are; greyed buttons in
     * brackets. Swing thread.
     */
    static List<String> words(Container root) {
        List<String> out = new ArrayList<>();
        walk(root, "", out);
        return out;
    }

    private static void walk(Component c, String indent, List<String> out) {
        if (!c.isVisible()) return;
        if (!(c instanceof Window) && (c.getWidth() <= 0 || c.getHeight() <= 0)) return; // folded away
        if (BenchAccess.rowState(c) != null) {
            checkRow((Container) c, indent, out);
            return;
        }
        if (isLine(c)) {
            out.add(indent + line((JPanel) c));
            return;
        }
        String t = text(c);
        if (c instanceof AbstractButton b) {
            String s = button(b);
            if (!s.isEmpty()) out.add(indent + s);
            return;
        }
        if (c instanceof JComboBox<?> box) {
            out.add(indent + "[" + box.getSelectedItem() + "]" + (box.isEnabled() ? "" : " greyed"));
            return;
        }
        if (c instanceof JSpinner s) {
            out.add(indent + "[" + s.getValue() + "]");
            return;
        }
        if (c instanceof JLabel || c instanceof JTextComponent) {
            if (t != null && !t.isBlank()) out.add(indent + t);
            return;
        }
        if (c instanceof Container k) for (Component x : k.getComponents()) walk(x, indent, out);
    }

    /** A check row: its title, its light, its words, its buttons; then what lies under its words (a combined row's lines). */
    private static void checkRow(Container row, String indent, List<String> out) {
        String title = "", detail = "";
        List<String> buttons = new ArrayList<>();
        Component below = null;
        for (Component child : row.getComponents()) {
            if (child instanceof JPanel p && p.getLayout() instanceof BorderLayout b && b.getLayoutComponent(BorderLayout.NORTH) instanceof JLabel l) {
                title = text(l);
                Component d = b.getLayoutComponent(BorderLayout.CENTER);
                if (d != null && d.isVisible()) detail = text(d);
                below = b.getLayoutComponent(BorderLayout.SOUTH);
            } else if (child instanceof JPanel p && p.getLayout() instanceof FlowLayout) {
                for (Component a : p.getComponents()) {
                    if (!a.isVisible()) continue;
                    if (a instanceof AbstractButton x) buttons.add(button(x));
                    else if (a instanceof JComboBox<?> box) buttons.add("[" + box.getSelectedItem() + "]");
                }
            }
        }
        out.add(indent + title + " [" + BenchAccess.rowState(row) + "]: " + (detail == null ? "" : detail)
                + (buttons.isEmpty() ? "" : "  " + String.join(" ", buttons)));
        if (below != null) walk(below, indent + "  ", out);
    }

    /** A setting's line: its label and choices on the left (or on top), its words beside (or under) them. */
    private static boolean isLine(Component c) {
        if (!(c instanceof JPanel p) || !(p.getLayout() instanceof BorderLayout b)) return false;
        Component west = b.getLayoutComponent(BorderLayout.WEST), north = b.getLayoutComponent(BorderLayout.NORTH);
        Component head = west != null ? west : north, cell = west != null ? b.getLayoutComponent(BorderLayout.CENTER) : b.getLayoutComponent(BorderLayout.SOUTH);
        return head instanceof JPanel h && h.getComponentCount() > 0 && h.getComponent(0) instanceof JLabel && cell instanceof JEditorPane;
    }

    private static String line(JPanel line) {
        BorderLayout b = (BorderLayout) line.getLayout();
        Component west = b.getLayoutComponent(BorderLayout.WEST);
        Container head = (Container) (west != null ? west : b.getLayoutComponent(BorderLayout.NORTH));
        Component cell = west != null ? b.getLayoutComponent(BorderLayout.CENTER) : b.getLayoutComponent(BorderLayout.SOUTH);
        List<String> parts = new ArrayList<>();
        choices(head, parts);
        String words = cell.isVisible() ? text(cell) : "";
        return String.join(" ", parts) + ":" + (words == null || words.isEmpty() ? "" : " " + words);
    }

    private static void choices(Component c, List<String> parts) {
        if (!c.isVisible()) return;
        if (c instanceof JComboBox<?> box) {
            parts.add("[" + box.getSelectedItem() + "]" + (box.isEnabled() ? "" : " greyed"));
        } else if (c instanceof JSpinner s) {
            parts.add("[" + s.getValue() + "]");
        } else if (c instanceof AbstractButton x) {
            String s = button(x);
            if (!s.isEmpty()) parts.add(s);
        } else if (c instanceof JLabel l) {
            String t = text(l);
            if (t != null && !t.isBlank()) parts.add(t);
        } else if (c instanceof Container k) {
            for (Component x : k.getComponents()) choices(x, parts);
        }
    }

    /** "(READ ESCS)", "[(BEACON OFF)]" greyed, "[x] the box's words" for a tick box. */
    private static String button(AbstractButton b) {
        String t = text(b);
        if (t == null || t.isBlank()) return "";
        if (b instanceof JCheckBox box) return (box.isSelected() ? "[x] " : "[ ] ") + t + (b.isEnabled() ? "" : " (greyed)");
        return b.isEnabled() ? "(" + t + ")" : "[(" + t + ")]";
    }

    /**
     * The first row or setting's line that begins with this - a check row's title, a setting's label - and has its
     * words after a colon (a section's heading alone, "ESCS", is not its line); "" when none does.
     */
    static String row(List<String> words, String begins) {
        for (String w : words) if (w.strip().startsWith(begins) && w.contains(":")) return w.strip();
        return "";
    }

    /** A control's words as {@link Ui#textOf} reads them, and the no-break space an empty HTML line leaves gone. */
    private static String text(Component c) {
        String t = Ui.textOf(c);
        return t == null ? null : t.replace("&#160;", " ").replace((char) 160, ' ').strip();
    }
}
