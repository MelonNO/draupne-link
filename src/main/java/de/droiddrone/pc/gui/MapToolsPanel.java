package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.MapGraphics;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.geom.AffineTransform;

/**
 * MAP TOOLS (1.0.35): a thin tab at the map's left edge; pressed, the tools slide out beside it and fold back on
 * the next press. RULER (and KEEP / CLEAR for the measurement), LINE, RECTANGLE, ARROW with the colour, width and
 * fill chosen first, SYMBOL with the type, affiliation (the colour) and echelon (the size), then PINS, GO TO,
 * DOWNLOAD AREA, EXPORT, IMPORT and CLEAR ALL. The tool buttons light up while their tool is on the map.
 */
final class MapToolsPanel extends JPanel {
    static final int BODY_WIDTH = 196;
    private static final int MS = 220;
    static final int[] COLOURS = {0xe31b23, 0xff9500, 0xfcd405, 0x2ecc40, 0x22d3ee, 0x3b82f6, 0xffffff};
    private static final int[] WIDTHS = {2, 4, 6};

    private final MapView map;
    private final JButton tab = new TabButton();
    private final JPanel body = new JPanel();
    private final Timer slide = new Timer(15, e -> tick());
    private long slideStart;
    private boolean open;
    private float shown; // 0 closed .. 1 open

    final JButton ruler = Theme.secondaryButton("Ruler");
    final JButton keep = Theme.secondaryButton("Keep");
    final JButton clearMeasure = Theme.secondaryButton("Clear");
    final JButton line = Theme.secondaryButton("Line");
    final JButton rectangle = Theme.secondaryButton("Rectangle");
    final JButton arrow = Theme.secondaryButton("Arrow");
    final JButton symbol = Theme.secondaryButton("Symbol");
    final JButton eraser = Theme.secondaryButton("Eraser");
    final JButton placeAt = Theme.secondaryButton("Place at...");
    final JButton pins = Theme.secondaryButton("Pins");
    final JButton goTo = Theme.secondaryButton("Go to");
    final JButton download = Theme.secondaryButton("Download area");
    final JButton export = Theme.secondaryButton("Export...");
    final JButton importButton = Theme.secondaryButton("Import...");
    final JButton clearAll = Theme.secondaryButton("Clear all");
    private final JButton[] swatches = new JButton[COLOURS.length];
    private final JButton[] widthButtons = new JButton[WIDTHS.length];
    private final JButton outline = Theme.secondaryButton("Outline");
    private final JButton fill = Theme.secondaryButton("Fill");
    private final JComboBox<String> unitCombo = new JComboBox<>();
    private final JButton[] affiliationButtons = new JButton[MapGraphics.Affiliation.values().length];
    private final JComboBox<String> echelonCombo = new JComboBox<>();
    private final JPanel measureRow = new JPanel(new GridLayout(1, 2, 6, 0));
    private int colour = COLOURS[0], width = WIDTHS[1];
    private boolean filled;
    private MapGraphics.Affiliation affiliation = MapGraphics.Affiliation.FRIEND;

    MapToolsPanel(MapView map) {
        super(new BorderLayout());
        this.map = map;
        setOpaque(false);
        tab.setToolTipText("MAP TOOLS: ruler, lines, areas, arrows, NATO symbols, pins, export and import");
        tab.addActionListener(e -> setOpen(!open, true));
        add(tab, BorderLayout.WEST);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Theme.PANEL_2);
        body.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel clip = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(Math.round(BODY_WIDTH * eased()), 10);
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        clip.setOpaque(false);
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(body, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(BODY_WIDTH, 10));
        // no blit scrolling: a viewport wider than its clipped parent copied pixels across the whole window (the map
        // under the toolbar, the figures row doubled, white where nothing was painted); and the panel's own colour
        // where the body ends on a tall screen
        scroll.getViewport().setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
        scroll.getViewport().setBackground(Theme.PANEL_2);
        scroll.setBackground(Theme.PANEL_2);
        clip.add(scroll, BorderLayout.CENTER);
        add(clip, BorderLayout.CENTER);
        slide.setRepeats(true);
        build();
    }

    private void build() {
        section("MEASURE");
        ruler.setToolTipText("Click points on the map; double-click or Enter ends, Esc cancels, right-click takes the last point back. Click the first point again to close the loop (its area shows)");
        row(ruler);
        measureRow.setOpaque(false);
        keep.setToolTipText("Keep this measurement on the map as a graphic");
        clearMeasure.setToolTipText("Take the measurement off the map");
        measureRow.add(small(keep));
        measureRow.add(small(clearMeasure));
        measureRow.setVisible(false);
        row(measureRow);

        section("DRAW");
        line.setToolTipText("A line through the points you click; double-click or Enter ends it");
        rectangle.setToolTipText("Drag a box on the map");
        arrow.setToolTipText("A line through the points you click, with an arrowhead at the last");
        row(line);
        row(rectangle);
        row(arrow);
        JPanel swatchRow = new JPanel(new GridLayout(1, COLOURS.length, 3, 0));
        swatchRow.setOpaque(false);
        for (int i = 0; i < COLOURS.length; i++) {
            int rgb = COLOURS[i];
            JButton b = new Swatch(rgb);
            b.addActionListener(e -> {
                colour = rgb;
                syncStyle();
            });
            swatches[i] = b;
            swatchRow.add(b);
        }
        row(swatchRow);
        JPanel widthRow = new JPanel(new GridLayout(1, WIDTHS.length, 6, 0));
        widthRow.setOpaque(false);
        String[] widthNames = {"Thin", "Mid", "Thick"};
        for (int i = 0; i < WIDTHS.length; i++) {
            int w = WIDTHS[i];
            JButton b = small(Theme.secondaryButton(widthNames[i]));
            b.addActionListener(e -> {
                width = w;
                syncStyle();
            });
            widthButtons[i] = b;
            widthRow.add(b);
        }
        row(widthRow);
        JPanel fillRow = new JPanel(new GridLayout(1, 2, 6, 0));
        fillRow.setOpaque(false);
        small(outline);
        small(fill);
        outline.setToolTipText("A rectangle as its frame only");
        fill.setToolTipText("A rectangle with a see-through fill");
        outline.addActionListener(e -> {
            filled = false;
            syncStyle();
        });
        fill.addActionListener(e -> {
            filled = true;
            syncStyle();
        });
        fillRow.add(outline);
        fillRow.add(fill);
        row(fillRow);

        section("SYMBOLS");
        symbol.setToolTipText("A NATO unit symbol where you click: the type, the affiliation (its colour) and the echelon (its size) below");
        row(symbol);
        for (MapGraphics.UnitType t : MapGraphics.UnitType.values()) unitCombo.addItem(t.title);
        TacticalCombo.install(unitCombo);
        unitCombo.setToolTipText("The unit type: the icon inside the frame");
        unitCombo.addActionListener(e -> syncSymbol());
        row(unitCombo);
        JPanel affRow = new JPanel(new GridLayout(1, affiliationButtons.length, 3, 0));
        affRow.setOpaque(false);
        for (MapGraphics.Affiliation a : MapGraphics.Affiliation.values()) {
            JButton b = new Swatch(a.fillRgb);
            b.setToolTipText(a.title + ": the frame's shape and colour");
            b.addActionListener(e -> {
                affiliation = a;
                syncSymbol();
            });
            affiliationButtons[a.ordinal()] = b;
            affRow.add(b);
        }
        row(affRow);
        for (MapGraphics.Echelon ec : MapGraphics.Echelon.values()) echelonCombo.addItem(ec.title + "  " + ec.mark);
        TacticalCombo.install(echelonCombo);
        echelonCombo.setSelectedIndex(MapGraphics.Echelon.PLATOON.ordinal());
        echelonCombo.setToolTipText("The echelon: the size mark above the frame");
        echelonCombo.addActionListener(e -> syncSymbol());
        row(echelonCombo);

        section("EDIT");
        eraser.setToolTipText("Click a drawing or symbol to take it off the map; Esc ends. A double-click on a point of any drawing picks it up to move it");
        placeAt.setToolTipText("A position typed or pasted (MGRS, UTM or latitude, longitude): a point of the line being drawn, else a symbol there");
        row(eraser);
        row(placeAt);

        section("MAP");
        row(pins);
        row(goTo);
        row(download);
        export.setToolTipText("Drag a box on the map: its picture (PNG) and everything drawn in it (KML) go to Documents\\DRAUPNE Link\\Exports");
        importButton.setToolTipText("A KML file's lines, areas, symbols and points onto the map (DRAUPNE's own, Google Earth's, ATAK's)");
        clearAll.setToolTipText("Every drawing and symbol off the map (the pins stay)");
        row(export);
        row(importButton);
        row(clearAll);
        syncStyle();
        syncSymbol();
    }

    private void section(String title) {
        if (body.getComponentCount() > 0) body.add(Box.createVerticalStrut(10));
        JComponent t = Theme.label(title, Theme.headingFont(11f), Theme.MUTED);
        t.setAlignmentX(LEFT_ALIGNMENT);
        body.add(t);
        body.add(Box.createVerticalStrut(5));
    }

    private void row(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        if (c instanceof JButton b) {
            b.setHorizontalAlignment(JButton.LEFT);
            b.setPreferredSize(new Dimension(BODY_WIDTH - 20, b.getPreferredSize().height));
        }
        c.setMaximumSize(new Dimension(BODY_WIDTH - 20, c.getPreferredSize().height));
        body.add(c);
        body.add(Box.createVerticalStrut(5));
    }

    private static JButton small(JButton b) {
        b.setFont(Theme.headingFont(11f));
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.ACCENT, 1), BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        return b;
    }

    /** The chosen colour, width and fill to the map; the chosen buttons lit. */
    private void syncStyle() {
        for (int i = 0; i < COLOURS.length; i++) ((Swatch) swatches[i]).setChosen(COLOURS[i] == colour);
        for (int i = 0; i < WIDTHS.length; i++) lit(widthButtons[i], WIDTHS[i] == width);
        lit(outline, !filled);
        lit(fill, filled);
        map.setDrawStyle(colour, width, filled);
    }

    private void syncSymbol() {
        for (MapGraphics.Affiliation a : MapGraphics.Affiliation.values()) ((Swatch) affiliationButtons[a.ordinal()]).setChosen(a == affiliation);
        MapGraphics.UnitType unit = MapGraphics.UnitType.values()[Math.max(0, unitCombo.getSelectedIndex())];
        MapGraphics.Echelon echelon = MapGraphics.Echelon.values()[Math.max(0, echelonCombo.getSelectedIndex())];
        map.setSymbolStyle(unit, affiliation, echelon);
    }

    /** The tool buttons: the one whose tool is on lit; the measurement's KEEP / CLEAR while there is one. */
    void syncTools() {
        MapView.Tool t = map.tool();
        lit(ruler, t == MapView.Tool.RULER);
        lit(line, t == MapView.Tool.LINE);
        lit(rectangle, t == MapView.Tool.AREA);
        lit(arrow, t == MapView.Tool.ARROW);
        lit(symbol, t == MapView.Tool.SYMBOL);
        lit(export, t == MapView.Tool.EXPORT_BOX);
        lit(eraser, t == MapView.Tool.ERASER);
        boolean measured = map.currentMeasurement() != null;
        if (measureRow.isVisible() != measured) {
            measureRow.setVisible(measured);
            body.revalidate();
        }
    }

    static void lit(JButton b, boolean on) {
        b.setBackground(on ? Theme.ACCENT : Theme.BG);
        b.setForeground(on ? Theme.BG : Theme.ACCENT);
        b.repaint();
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean open, boolean animate) {
        if (this.open == open) return;
        this.open = open;
        if (!animate) {
            shown = open ? 1 : 0;
            revalidate();
            repaint();
            return;
        }
        slideStart = System.currentTimeMillis();
        slide.start();
    }

    private void tick() {
        float t = Math.min(1, (System.currentTimeMillis() - slideStart) / (float) MS);
        shown = open ? t : 1 - t;
        revalidate();
        repaint();
        if (t >= 1) slide.stop();
    }

    private float eased() {
        return 1 - (1 - shown) * (1 - shown);
    }

    /** The tab the tour points at. */
    JComponent tabButton() {
        return tab;
    }

    /** The tab: MAP TOOLS written upwards, a chevron showing which way it goes. */
    private final class TabButton extends JButton {
        TabButton() {
            super("MAP TOOLS");
            setPreferredSize(new Dimension(26, 10));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight();
                boolean hot = getModel().isRollover() || open;
                g.setColor(hot ? Theme.PANEL_2 : Theme.PANEL);
                g.fillRect(0, 0, w, h);
                g.setColor(hot ? Theme.ACCENT : Theme.LINE);
                g.drawRect(0, 0, w - 1, h - 1);
                g.setFont(Theme.headingFont(12f));
                FontMetrics fm = g.getFontMetrics();
                String text = getText();
                int tw = fm.stringWidth(text);
                AffineTransform saved = g.getTransform();
                g.rotate(-Math.PI / 2, w / 2.0, h / 2.0);
                g.setColor(hot ? Theme.TEXT : Theme.MUTED);
                g.drawString(text, w / 2 - tw / 2, h / 2 + fm.getAscent() / 2 - 1);
                g.setTransform(saved);
                g.setColor(Theme.ACCENT);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = w / 2, cy = 14;
                if (open) { // < : it folds back
                    g.drawLine(cx + 3, cy - 4, cx - 2, cy);
                    g.drawLine(cx - 2, cy, cx + 3, cy + 4);
                } else { // > : it slides out
                    g.drawLine(cx - 3, cy - 4, cx + 2, cy);
                    g.drawLine(cx + 2, cy, cx - 3, cy + 4);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** A colour to pick: a square of it, framed white when chosen. */
    private static final class Swatch extends JButton {
        private final Color colour;
        private boolean chosen;

        Swatch(int rgb) {
            colour = new Color(rgb);
            setPreferredSize(new Dimension(22, 22));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setToolTipText(String.format("#%06x", rgb));
        }

        void setChosen(boolean chosen) {
            this.chosen = chosen;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight();
                g.setColor(colour);
                g.fillRect(3, 3, w - 6, h - 6);
                g.setColor(chosen ? Color.WHITE : Theme.LINE);
                g.setStroke(new BasicStroke(chosen ? 2f : 1f));
                g.drawRect(2, 2, w - 5, h - 5);
            } finally {
                g.dispose();
            }
        }
    }
}
