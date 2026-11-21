package de.droiddrone.bench;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollBar;
import javax.swing.JViewport;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What can be seen to be wrong on a window without a person looking: a part that sticks out of what holds it, words
 * that do not fit their label or button (cut off, or ending in "..."), two controls lying over each other, words too
 * close in colour to what they stand on, a dialog that opened partly off the screen. Run on every picture the bench
 * takes. What it finds is a list for a person to look through - a layout can be meant that way - so it never fails a
 * scenario by itself; each kind of remark is said once per scenario.
 */
public final class Look {
    private final Set<String> said = new LinkedHashSet<>();

    /** Everything new that looks wrong on these windows now. Swing thread. */
    public List<String> inspect(List<Window> windows, boolean onScreen) {
        List<String> found = new ArrayList<>();
        // the screen the app believes it has: a scenario at a window size is a screen of that size (AppRig)
        Rectangle screen = GraphicsEnvironment.isHeadless() ? null : de.droiddrone.pc.gui.BenchAccess.screen();
        for (Window w : windows) {
            String where = w instanceof Frame f ? f.getTitle() : w instanceof Dialog d ? d.getTitle() : w.getClass().getSimpleName();
            if (onScreen && screen != null && (w instanceof Dialog || w instanceof Frame) && !isMaximized(w)) {
                Rectangle b = w.getBounds();
                Rectangle seen = b.intersection(screen);
                if (seen.isEmpty() || (long) seen.width * seen.height < (long) b.width * b.height * 95 / 100) {
                    add(found, "off the screen", "\"" + where + "\" " + b.width + "x" + b.height + " at " + b.x + "," + b.y + " does not fit the screen's " + screen.width + "x" + screen.height);
                }
            }
            walk(w, where, found);
        }
        return found;
    }

    private static boolean isMaximized(Window w) {
        return w instanceof Frame f && (f.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
    }

    private void walk(Container root, String where, List<String> found) {
        Component[] kids = root.getComponents();
        boolean scrolls = root instanceof JViewport;
        for (Component c : kids) {
            if (!c.isVisible() || c.getWidth() <= 0 || c.getHeight() <= 0) continue;
            Rectangle b = c.getBounds();
            if (!scrolls && !(c instanceof JScrollBar) && isWorded(c) && !root.getClass().getSimpleName().equals("SlideMenu") // its buttons slide out of it on purpose
                    && !root.getClass().getSimpleName().equals("Fold") // what it holds slides open under its edge, caught half-way
                    && (b.x < -1 || b.y < -1 || b.x + b.width > root.getWidth() + 1 || b.y + b.height > root.getHeight() + 1)) {
                add(found, "sticks out", describe(c) + " in \"" + where + "\": " + b.width + "x" + b.height + " at " + b.x + "," + b.y + " in a "
                        + root.getClass().getSimpleName() + " of " + root.getWidth() + "x" + root.getHeight());
            }
            String cut = wordsCut(c);
            if (cut != null) add(found, "words cut off", describe(c) + " in \"" + where + "\": " + cut);
            String faint = faint(c);
            if (faint != null) add(found, "hard to read", describe(c) + " in \"" + where + "\": " + faint);
            if (c instanceof Container k && !(c instanceof JScrollBar) && !(c instanceof JComboBox)) walk(k, where, found);
        }
        // two controls over each other in the same holder
        for (int i = 0; i < kids.length; i++) {
            if (!isControl(kids[i])) continue;
            for (int j = i + 1; j < kids.length; j++) {
                if (!isControl(kids[j])) continue;
                Rectangle both = kids[i].getBounds().intersection(kids[j].getBounds());
                if (both.width > 3 && both.height > 3) {
                    add(found, "overlap", describe(kids[i]) + " and " + describe(kids[j]) + " in \"" + where + "\" lie over each other by " + both.width + "x" + both.height);
                }
            }
        }
    }

    private static boolean isWorded(Component c) {
        return c instanceof JLabel || c instanceof AbstractButton || c instanceof JTextComponent || c instanceof JComboBox;
    }

    private static boolean isControl(Component c) {
        return c.isVisible() && c.getWidth() > 0 && c.getHeight() > 0 && isWorded(c);
    }

    /** The words need more room than the label or button has: null when they fit. */
    private static String wordsCut(Component c) {
        if (c.getClass().getSimpleName().equals("GlyphButton")) return null; // a sign, not words: its text is its name for a screen reader
        if (!(c instanceof JLabel) && !(c instanceof AbstractButton)) return null;
        String raw = c instanceof JLabel l ? l.getText() : ((AbstractButton) c).getText();
        if (raw == null || raw.isBlank()) return null;
        JComponent j = (JComponent) c;
        Insets in = j.getInsets();
        if (raw.regionMatches(true, 0, "<html", 0, 5)) {
            // wrapped text: its needed height at this width is what counts
            int need = j.getPreferredSize().height;
            return need > c.getHeight() + 2 ? "needs " + need + " px of height, has " + c.getHeight() : null;
        }
        FontMetrics fm = c.getFontMetrics(c.getFont());
        int icon = 0;
        if (c instanceof JLabel l && l.getIcon() != null) icon = l.getIcon().getIconWidth() + l.getIconTextGap();
        if (c instanceof AbstractButton b && b.getIcon() != null) icon = b.getIcon().getIconWidth() + b.getIconTextGap();
        int need = fm.stringWidth(raw) + icon;
        int room = c.getWidth() - in.left - in.right;
        // a button that draws a sign instead of its words (they are its name for a screen reader) asks for no more room than it has
        boolean drawsWords = c instanceof JLabel || j.getPreferredSize().width >= need;
        if (drawsWords && need > room + 1) return "\"" + Ui.norm(raw) + "\" needs " + need + " px, has " + room;
        if (fm.getHeight() > c.getHeight() + 3) return "\"" + Ui.norm(raw) + "\" is " + fm.getHeight() + " px high in " + c.getHeight();
        return null;
    }

    /** Words whose colour is close to what they stand on (contrast under 2.5 to 1): null when readable or not knowable. */
    private static String faint(Component c) {
        if (!(c instanceof JLabel l)) return null; // a button paints its own ground: its colours can't be read from outside
        String raw = l.getText();
        if (raw == null || raw.isBlank() || raw.contains("<html") || !c.isEnabled()) return null;
        Color fg = c.getForeground();
        Color bg = null;
        for (Component p = c; p != null && bg == null; p = p.getParent()) if (p.isOpaque() && p.getBackground() != null) bg = p.getBackground();
        if (fg == null || bg == null || fg.getAlpha() < 255) return null;
        double ratio = contrast(fg, bg);
        return ratio < 2.5 ? String.format("\"%s\" #%06x on #%06x, contrast %.1f to 1", Ui.norm(raw), fg.getRGB() & 0xFFFFFF, bg.getRGB() & 0xFFFFFF, ratio) : null;
    }

    static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    private static String describe(Component c) {
        String t = Ui.textOf(c);
        String name = c.getClass().getSimpleName().isEmpty() ? c.getClass().getName() : c.getClass().getSimpleName();
        if (t == null || t.isBlank() || c instanceof JTextComponent) {
            String tip = c instanceof JComponent j ? j.getToolTipText() : null;
            return tip == null ? name : name + " (" + (tip.length() > 40 ? Ui.norm(tip).substring(0, 40) + "..." : Ui.norm(tip)) + ")";
        }
        return name + " \"" + (t.length() > 50 ? t.substring(0, 50) + "..." : t) + "\"";
    }

    private void add(List<String> found, String kind, String text) {
        // said once: the same label looked at on fifty pictures is one remark (numbers in it may differ by a pixel)
        String key = kind + ": " + text.replaceAll("\\d+", "#");
        if (said.add(key)) found.add(kind + ": " + text);
    }
}
