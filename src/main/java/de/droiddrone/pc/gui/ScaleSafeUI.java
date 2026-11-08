package de.droiddrone.pc.gui;

import com.formdev.flatlaf.ui.FlatButtonUI;
import com.formdev.flatlaf.ui.FlatLabelUI;
import com.formdev.flatlaf.ui.FlatToggleButtonUI;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Labels and buttons that never cut their text short at Windows' display scaling (1.0.52, the pilot on a smaller
 * screen: "CHECK EVER.", "CO..."). Swing sizes a text at 100 %, but at 125-200 % it paints each letter on whole
 * device pixels - with the heading font's letter spacing a word comes out a few pixels wider than it was given, and
 * Swing cuts it to "...". These UIs add the widest the text paints at any of those scales to the preferred size.
 */
public final class ScaleSafeUI {
    private ScaleSafeUI() {
    }

    /** Windows' usual display scales. */
    private static final double[] SCALES = {1.0, 1.25, 1.5, 1.75, 2.0};
    private static final int CACHE = 512;
    private static final Map<String, Integer> EXTRA = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > CACHE;
        }
    };

    static void install() {
        for (Class<?> ui : new Class<?>[]{Label.class, Button.class, ToggleButton.class}) UIManager.put(ui.getName(), ui);
        UIManager.put("LabelUI", Label.class.getName());
        UIManager.put("ButtonUI", Button.class.getName());
        UIManager.put("ToggleButtonUI", ToggleButton.class.getName());
    }

    /** The pixels a text paints wider at some display scale than Swing's 100 % measure gives it; 0 for none or HTML. */
    static int extraWidth(Font font, FontMetrics fm, String text) {
        if (font == null || text == null || text.isEmpty() || text.regionMatches(true, 0, "<html>", 0, 6)) return 0;
        String key = font.getFamily() + '|' + font.getStyle() + '|' + font.getSize2D() + '|' + font.getAttributes().hashCode() + '|' + text;
        synchronized (EXTRA) {
            Integer known = EXTRA.get(key);
            if (known != null) return known;
        }
        double widest = 0;
        for (double s : SCALES) {
            FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(s, s), true, false);
            widest = Math.max(widest, font.getStringBounds(text, frc).getWidth());
        }
        int extra = Math.max(0, (int) Math.ceil(widest) - fm.stringWidth(text));
        synchronized (EXTRA) {
            EXTRA.put(key, extra);
        }
        return extra;
    }

    private static Dimension widen(JComponent c, Dimension d, String text) {
        if (d == null) return null;
        int extra = extraWidth(c.getFont(), c.getFontMetrics(c.getFont()), text);
        return extra == 0 ? d : new Dimension(d.width + extra, d.height);
    }

    public static final class Label extends FlatLabelUI {
        private static final Label SHARED = new Label();

        private Label() {
            super(true);
        }

        public static ComponentUI createUI(JComponent c) {
            return SHARED;
        }

        @Override
        public void installUI(JComponent c) {
            super.installUI(c);
            c.removeComponentListener(RELAYOUT);
            c.addComponentListener(RELAYOUT);
        }

        @Override
        public void uninstallUI(JComponent c) {
            c.removeComponentListener(RELAYOUT);
            super.uninstallUI(c);
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return wrapped(c, widen(c, super.getPreferredSize(c), ((JLabel) c).getText()));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            return widen(c, super.getMinimumSize(c), ((JLabel) c).getText());
        }
    }

    /**
     * An HTML label narrower than its one long line wraps when painted, but Swing kept its height at one line and the
     * rest was cut off (the OSD window's help, RC CHECK's lines on a small screen). Laid out at a width, it asks the
     * height for that width; a new width lays it out again.
     */
    private static Dimension wrapped(JComponent c, Dimension d) {
        Object view = c.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
        int width = c.getWidth();
        if (!(view instanceof javax.swing.text.View v) || width <= 0 || d == null || width >= d.width * 1.06) return d; // room to spare at any scale: one line
        java.awt.Insets in = c.getInsets();
        // a little under its width: at 125-200 % the words paint a few per cent wider and may take one more line
        int inner = Math.max(1, (int) ((width - in.left - in.right) * 0.94));
        float oldW = v.getPreferredSpan(javax.swing.text.View.X_AXIS);
        v.setSize(inner, 0);
        int h = (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + in.top + in.bottom;
        v.setSize(oldW, 0);
        return new Dimension(d.width, Math.max(d.height, h));
    }

    /** A label that changed width: its wrapped height may have changed too. */
    private static final java.awt.event.ComponentListener RELAYOUT = new java.awt.event.ComponentAdapter() {
        @Override
        public void componentResized(java.awt.event.ComponentEvent e) {
            if (!(e.getComponent() instanceof JLabel l) || l.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null) return;
            Object last = l.getClientProperty(LAST_WIDTH);
            if (last instanceof Integer w && w == l.getWidth()) return;
            l.putClientProperty(LAST_WIDTH, l.getWidth());
            javax.swing.SwingUtilities.invokeLater(l::revalidate);
        }
    };
    private static final String LAST_WIDTH = "draupne.wrapWidth";

    public static final class Button extends FlatButtonUI {
        private Button() {
            super(false);
        }

        public static ComponentUI createUI(JComponent c) {
            return new Button();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return widen(c, super.getPreferredSize(c), ((AbstractButton) c).getText());
        }
    }

    public static final class ToggleButton extends FlatToggleButtonUI {
        private ToggleButton() {
            super(false);
        }

        public static ComponentUI createUI(JComponent c) {
            return new ToggleButton();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return widen(c, super.getPreferredSize(c), ((AbstractButton) c).getText());
        }
    }
}
