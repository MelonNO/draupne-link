package de.droiddrone.bench;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * The count of what can be pressed: every button, menu row, tick box, list and field the bench has seen on a showing
 * window, and whether a scenario used it. "Everything was tested" then is a number, and the report lists what was
 * never touched. One file per JVM (census-PID.tsv in the run's folder); the report adds them up.
 */
public final class Census {
    /** key -> used. The key: the window it is in, the kind of control, its words. */
    private final Map<String, Boolean> seen = new TreeMap<>();

    static boolean isControl(Component c) {
        return c instanceof AbstractButton || c instanceof JComboBox || c instanceof JTextComponent t && t.isEditable()
                || c instanceof JSlider || c instanceof JSpinner;
    }

    static String key(Component c) {
        Window w = c instanceof Window x ? x : javax.swing.SwingUtilities.getWindowAncestor(c);
        // a menu's rows live in a pop-up window of their own: named for the window that owns it
        while (w != null && !(w instanceof Frame) && !(w instanceof Dialog) && w.getOwner() != null) w = w.getOwner();
        String where = w == null ? "?" : w.getClass().getSimpleName().isEmpty() ? w.getClass().getName() : w.getClass().getSimpleName();
        if (w instanceof Dialog d && d.getTitle() != null && !d.getTitle().isBlank() && where.equals("JDialog")) where = "JDialog \"" + d.getTitle() + "\"";
        String kind = c instanceof javax.swing.JMenuItem ? "menu row" : c instanceof javax.swing.JCheckBox ? "tick box"
                : c instanceof javax.swing.JToggleButton ? "toggle" : c instanceof AbstractButton ? "button"
                : c instanceof JComboBox ? "list" : c instanceof JSlider ? "slider" : c instanceof JSpinner ? "spinner" : "field";
        String words = Ui.textOf(c);
        if (words == null || words.isBlank()) words = c instanceof javax.swing.JComponent j && j.getToolTipText() != null ? "(" + Ui.norm(j.getToolTipText()) + ")" : "";
        if (words.isBlank() && c.getName() != null) words = "[" + c.getName() + "]";
        if (words.length() > 70) words = words.substring(0, 70) + "...";
        return where + "\t" + kind + "\t" + words;
    }

    public synchronized void see(Component c) {
        if (isControl(c)) seen.putIfAbsent(key(c), false);
    }

    public synchronized void used(Component c) {
        if (isControl(c)) seen.put(key(c), true);
    }

    public synchronized int seenCount() {
        return seen.size();
    }

    public synchronized long usedCount() {
        return seen.values().stream().filter(v -> v).count();
    }

    public synchronized void write(Path runRoot) {
        StringBuilder s = new StringBuilder();
        seen.forEach((k, used) -> s.append(used ? "used" : "seen").append('\t').append(k).append('\n'));
        try {
            Path dir = Files.createDirectories(runRoot.resolve("census"));
            Files.writeString(dir.resolve("census-" + ProcessHandle.current().pid() + ".tsv"), s.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
