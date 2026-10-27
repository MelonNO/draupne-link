package de.droiddrone.pc.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Military look: black base, signal red accents, yellow for warnings, green for "OK", square corners, red corner
 * brackets on panels, condensed DIN-style lettering (Bahnschrift, part of Windows 10/11) and monospace readouts.
 */
final class Theme {
    static final Color BG = new Color(0x000000);
    static final Color PANEL = new Color(0x0a0a0a);
    static final Color PANEL_2 = new Color(0x161616);
    static final Color LINE = new Color(0x2e2e2a);
    static final Color TEXT = new Color(0xe6e6e0);
    static final Color MUTED = new Color(0x8c8c7e);
    static final Color ACCENT = new Color(0xe31b23); // signal red
    static final Color OK = new Color(0x009639);
    static final Color WARN = new Color(0xfcd405);
    static final Color BAD = new Color(0xff5a4e);  // lighter than the accent, so errors stand out
    /** For HTML text. */
    static final String MUTED_HEX = "#8c8c7e";
    static final String BAD_HEX = "#ff5a4e";

    private static final List<String> FONTS = Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    private static final String TEXT_FONT = first("Bahnschrift", "Segoe UI");
    private static final String HEADING_FONT = first("Bahnschrift SemiBold Condensed", "Bahnschrift Condensed", "Bahnschrift", "Segoe UI");
    private static final String MONO_FONT = first("Consolas", "Cascadia Mono", Font.MONOSPACED);

    private Theme() {
    }

    private static String first(String... names) {
        for (String n : names) if (FONTS.contains(n)) return n;
        return names[names.length - 1];
    }

    static void install() {
        FlatLaf.setGlobalExtraDefaults(Map.of(
                "@accentColor", "#e31b23",
                "@background", "#000000",
                "@foreground", "#e6e6e0",
                "@selectionBackground", "#e31b23",
                "@selectionForeground", "#ffffff"));
        FlatDarkLaf.setup();
        for (String key : new String[]{"Button.arc", "Component.arc", "TextComponent.arc", "CheckBox.arc", "ProgressBar.arc"}) {
            UIManager.put(key, 0);
        }
        UIManager.put("ScrollBar.width", 8);
        UIManager.put("ScrollBar.thumb", LINE);
        UIManager.put("Component.focusColor", ACCENT);
        UIManager.put("Component.focusedBorderColor", ACCENT);
        UIManager.put("TextField.background", PANEL_2);
        UIManager.put("ComboBox.background", PANEL_2);
        UIManager.put("ToolTip.background", PANEL_2);
        UIManager.put("defaultFont", font(Font.PLAIN, 13f));
    }

    static Font font(int style, float size) {
        return new Font(TEXT_FONT, style, 12).deriveFont(size);
    }

    /** Uppercase, condensed, widely spaced - stencil-like headings. */
    static Font headingFont(float size) {
        return new Font(HEADING_FONT, Font.BOLD, 12).deriveFont(size).deriveFont(Map.of(TextAttribute.TRACKING, 0.08f));
    }

    static Font monoFont(int style, float size) {
        return new Font(MONO_FONT, style, 12).deriveFont(size);
    }

    static JLabel label(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    /** "// LINK" style section marker. */
    static JLabel sectionTitle(String text) {
        return label("// " + text.toUpperCase(), headingFont(12.5f), ACCENT);
    }

    static JPanel card(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(new BracketBorder(), BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        return panel;
    }

    static JButton primaryButton(String text) {
        JButton button = new JButton(text.toUpperCase());
        button.setFont(headingFont(14.5f));
        button.setBackground(ACCENT);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(9, 22, 9, 22));
        return button;
    }

    static JButton secondaryButton(String text) {
        JButton button = new JButton(text.toUpperCase());
        button.setFont(headingFont(13.5f));
        button.setBackground(BG);
        button.setForeground(ACCENT);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ACCENT, 1),
                BorderFactory.createEmptyBorder(7, 16, 7, 16)));
        return button;
    }

    static void transparent(JComponent... components) {
        for (JComponent c : components) c.setOpaque(false);
    }

    static Graphics2D smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static Color alpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** Thin frame with red L-shaped corner brackets, like a targeting reticle. */
    static final class BracketBorder extends AbstractBorder {
        private static final int ARM = 12;

        @Override
        public void paintBorder(Component c, Graphics graphics, int x, int y, int w, int h) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(LINE);
            g.drawRect(x, y, w - 1, h - 1);
            g.setColor(ACCENT);
            g.setStroke(new BasicStroke(2f));
            int r = x + w - 1;
            int b = y + h - 1;
            g.drawLine(x, y, x + ARM, y);
            g.drawLine(x, y, x, y + ARM);
            g.drawLine(r, y, r - ARM, y);
            g.drawLine(r, y, r, y + ARM);
            g.drawLine(x, b, x + ARM, b);
            g.drawLine(x, b, x, b - ARM);
            g.drawLine(r, b, r - ARM, b);
            g.drawLine(r, b, r, b - ARM);
            g.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(2, 2, 2, 2);
        }
    }
}
