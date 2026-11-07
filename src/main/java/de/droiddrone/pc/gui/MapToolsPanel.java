package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.RadioHorizon;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.geom.AffineTransform;
import java.util.EnumMap;
import java.util.Map;

/**
 * MAP TOOLS (1.0.35): a thin tab at the map's left edge; pressed, a strip of three flat tabs slides out beside it -
 * DRAW, TOOLS and DATA (1.0.39) - and each of those slides its list out the same way, full height, folding back on
 * the next press. DRAW: LINE, RECTANGLE, ARROW with the colour, width and fill chosen first, SYMBOL with the type,
 * affiliation (the colour) and echelon (the size), ERASER, PLACE AT. TOOLS: the RULER (and KEEP / CLEAR for the
 * measurement), RADIO HORIZON and GCS SEARCH with their heights and frequency (sliding out under the button pressed),
 * 4G COVERAGE (1.0.44: on / off, with the network, the drone's height and the OpenCelliD key), PINS, GO TO. DATA: EXPORT, IMPORT, DOWNLOAD AREA, CLEAR ALL. The
 * tool buttons light up while their tool is on the map.
 */
final class MapToolsPanel extends JPanel {
    static final int BODY_WIDTH = 196;
    static final int STRIP_WIDTH = 26;
    private static final int MS = 220;
    static final int[] COLOURS = {0xe31b23, 0xff9500, 0xfcd405, 0x2ecc40, 0x22d3ee, 0x3b82f6, 0xffffff};
    private static final int[] WIDTHS = {2, 4, 6};

    enum Group {
        DRAW("DRAW"), TOOLS("TOOLS"), DATA("DATA");

        final String title;

        Group(String title) {
            this.title = title;
        }
    }

    private final MapView map;
    private final JButton tab = new TabButton("MAP TOOLS", true);
    private final Map<Group, JButton> groupTabs = new EnumMap<>(Group.class);
    private final Map<Group, JPanel> bodies = new EnumMap<>(Group.class);
    private final javax.swing.JScrollPane scroll;
    private final Timer slide = new Timer(15, e -> tick());
    private long slideStart;
    private boolean open;
    private Group group; // the group whose list is out; null: none
    private float shownFrom, shownTo = 0, shown; // the strip: 0 closed .. 1 open
    private float bodyFrom, bodyTo = 0, bodyShown; // the list beside it

    final JButton ruler = Theme.secondaryButton("Ruler");
    final JButton keep = Theme.secondaryButton("Keep");
    final JButton clearMeasure = Theme.secondaryButton("Clear");
    final JButton horizon = Theme.secondaryButton("Radio horizon");
    final JButton gcsSearch = Theme.secondaryButton("GCS search");
    /** 4G COVERAGE (1.0.44): on / off, and its fields (the network, the drone's height, the OpenCelliD key, the country file). */
    final JButton coverage = Theme.secondaryButton("4G coverage");
    final JComboBox<String> coverageOperator = new JComboBox<>();
    final JComboBox<String> coverageHeight = new JComboBox<>();
    final JButton coverageKey = Theme.secondaryButton("Key...");
    final JButton coverageImport = Theme.secondaryButton("Import...");
    final JLabel coverageStatus = Theme.label(" ", Theme.font(Font.PLAIN, 13f), Theme.TEXT);
    final JButton line = Theme.secondaryButton("Line");
    final JButton rectangle = Theme.secondaryButton("Rectangle");
    final JButton arrow = Theme.secondaryButton("Arrow");
    final JButton symbol = Theme.secondaryButton("Symbol");
    final JButton eraser = Theme.secondaryButton("Eraser");
    final JButton placeAt = Theme.secondaryButton("Place at...");
    final JButton pins = Theme.secondaryButton("Pins");
    final JButton goTo = Theme.secondaryButton("Go to");
    /** 1.0.47: the overwatch clips, per drone (the map window's clip list). */
    final JButton clips = Theme.secondaryButton("Clips");
    final JButton download = Theme.secondaryButton("Download area");
    final JButton export = Theme.secondaryButton("Export...");
    final JButton importButton = Theme.secondaryButton("Import...");
    final JButton clearAll = Theme.secondaryButton("Clear all");
    /**
     * RADIO HORIZON and GCS SEARCH share these: the mast and the aircraft above the ground, the trees where the map
     * shows forest, and the link's frequency (how far it gets through the trees). Each tool has its own fields on them.
     */
    final SpinnerNumberModel mastM = new SpinnerNumberModel(10, 1, 200, 1);
    final SpinnerNumberModel aircraftM = new SpinnerNumberModel(120, 1, 3000, 1);
    final SpinnerNumberModel treeM = new SpinnerNumberModel(15, 0, 40, 1);
    final SpinnerNumberModel frequencyMHz = new SpinnerNumberModel(2400, 100, 6000, 1);
    final JLabel horizonStatus = Theme.label(" ", Theme.font(Font.PLAIN, 13f), Theme.TEXT);
    final JLabel searchStatus = Theme.label(" ", Theme.font(Font.PLAIN, 13f), Theme.TEXT);
    final SlideOut horizonFields, searchFields, coverageFields;
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
        horizonFields = new SlideOut(fields(horizonStatus, "the aircraft's altitude above the ground under it, wherever it flies"));
        searchFields = new SlideOut(fields(searchStatus, "the aircraft's altitude above the ground under it, over the area wanted"));
        searchStatus.setToolTipText("Green: a mast there sees all of the area; red: none of it. The best place is ringed.");
        coverageFields = new SlideOut(coverageFields());
        setOpaque(false);
        tab.setToolTipText("MAP TOOLS: drawing and symbols, the ruler and the radio horizon, export and import");
        tab.addActionListener(e -> setOpen(!open, true));
        add(tab, BorderLayout.WEST);
        JPanel clip = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(Math.round(STRIP_WIDTH * eased(shown) + BODY_WIDTH * eased(bodyShown)), 10);
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        clip.setOpaque(false);
        JPanel strip = new JPanel(new GridLayout(Group.values().length, 1, 0, 4)) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(STRIP_WIDTH, 10);
            }
        };
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        for (Group g : Group.values()) {
            JButton b = new TabButton(g.title, false);
            b.addActionListener(e -> showGroup(group == g ? null : g, true));
            groupTabs.put(g, b);
            strip.add(b);
            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBackground(Theme.PANEL_2);
            body.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            bodies.put(g, body);
        }
        groupTabs.get(Group.DRAW).setToolTipText("Lines, rectangles, arrows, NATO symbols, the eraser, pins");
        groupTabs.get(Group.TOOLS).setToolTipText("The ruler, the radio horizon, the GCS search, go to. Their results show on the map only while this is open");
        groupTabs.get(Group.DATA).setToolTipText("Export, import, offline maps, clear all");
        clip.add(strip, BorderLayout.WEST);
        scroll = new javax.swing.JScrollPane(bodies.get(Group.DRAW), javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
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
        buildDraw(bodies.get(Group.DRAW));
        buildTools(bodies.get(Group.TOOLS));
        buildData(bodies.get(Group.DATA));
        syncStyle();
        syncSymbol();
    }

    private void buildDraw(JPanel body) {
        section(body, "DRAW");
        line.setToolTipText("A line through the points you click; double-click or Enter ends it");
        rectangle.setToolTipText("Drag a box on the map");
        arrow.setToolTipText("A line through the points you click, with an arrowhead at the last");
        row(body, line);
        row(body, rectangle);
        row(body, arrow);
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
        row(body, swatchRow);
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
        row(body, widthRow);
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
        row(body, fillRow);

        section(body, "SYMBOLS");
        symbol.setToolTipText("A NATO unit symbol where you click: the type, the affiliation (its colour) and the echelon (its size) below");
        row(body, symbol);
        for (MapGraphics.UnitType t : MapGraphics.UnitType.values()) unitCombo.addItem(t.title);
        TacticalCombo.install(unitCombo);
        unitCombo.setToolTipText("The unit type: the icon inside the frame");
        unitCombo.addActionListener(e -> syncSymbol());
        row(body, unitCombo);
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
        row(body, affRow);
        for (MapGraphics.Echelon ec : MapGraphics.Echelon.values()) echelonCombo.addItem(ec.title + "  " + ec.mark);
        TacticalCombo.install(echelonCombo);
        echelonCombo.setSelectedIndex(MapGraphics.Echelon.PLATOON.ordinal());
        echelonCombo.setToolTipText("The echelon: the size mark above the frame");
        echelonCombo.addActionListener(e -> syncSymbol());
        row(body, echelonCombo);

        section(body, "EDIT");
        eraser.setToolTipText("Click a drawing or symbol to take it off the map; Esc ends. A double-click on a point of any drawing picks it up to move it");
        placeAt.setToolTipText("A position typed or pasted (MGRS, UTM or latitude, longitude): a point of the line being drawn, else a symbol there");
        row(body, eraser);
        row(body, placeAt);
        row(body, pins); // the pilot: pins belong with the drawings (1.0.39)
    }

    private void buildTools(JPanel body) {
        section(body, "MEASURE");
        ruler.setToolTipText("Click points on the map; double-click or Enter ends, Esc cancels, right-click takes the last point back. Click the first point again to close the loop (its area shows)");
        row(body, ruler);
        measureRow.setOpaque(false);
        keep.setToolTipText("Keep this measurement on the map as a graphic");
        clearMeasure.setToolTipText("Take the measurement off the map");
        measureRow.add(small(keep));
        measureRow.add(small(clearMeasure));
        measureRow.setVisible(false);
        row(body, measureRow);

        gap(body); // no heading: the button says it
        horizon.setToolTipText("Click the map to put a mast there: green is where the aircraft at the height set can see it, over the "
                + "terrain and through the forest on the map (by the frequency), 30 km at most. A new click moves the mast. It stays on the map when the tool ends: the cross on its label takes it off");
        row(body, horizon);
        slideRow(body, horizonFields);

        gap(body);
        gcsSearch.setToolTipText("Drag a box round the area you want good reception in: a heat map 30 km round it shows where a ground "
                + "station's antenna sees all of it (green) or none of it (red), the best place ringed. A new box replaces it. It stays on the map when the tool ends: the cross on its label takes it off");
        row(body, gcsSearch);
        slideRow(body, searchFields);

        gap(body);
        coverage.setToolTipText("<html>An ESTIMATE of the 4G signal the drone gets where the map is looked at: green good, yellow fair, orange weak, "
                + "red none.<br>From where OpenCelliD's open database puts the 4G and 5G cells, and the terrain between them and the drone. "
                + "Masts show as dots when zoomed in.<br>A guess, not a measurement: the operators' own maps are not open. Press again to turn it off</html>");
        row(body, coverage);
        slideRow(body, coverageFields);

        section(body, "MAP");
        row(body, goTo);

        section(body, "OVERWATCH");
        clips.setToolTipText("What the drones in overwatch saw: each event's clip, when, how long and where, to play here with the camera's view on the map");
        row(body, clips);
    }

    private void buildData(JPanel body) {
        section(body, "DATA");
        export.setToolTipText("Drag a box on the map: its picture (PNG) and everything drawn in it (KML) go to Documents\\DRAUPNE Link\\Exports");
        importButton.setToolTipText("A KML file's lines, areas, symbols and points onto the map (DRAUPNE's own, Google Earth's, ATAK's)");
        clearAll.setToolTipText("Every drawing, symbol, pin and measurement off the map");
        row(body, export);
        row(body, importButton);
        row(body, download);
        row(body, clearAll);
    }

    /** A tool's fields on the shared models, and its status line under them. */
    private JPanel fields(JLabel status, String aircraftTip) {
        JPanel grid = new JPanel(new GridLayout(4, 2, 6, 4));
        grid.setOpaque(false);
        addField(grid, "Mast m", mastM, "The mast: the antenna's height above the ground, in metres");
        addField(grid, "Aircraft m", aircraftM, "The aircraft: " + aircraftTip + ", in metres");
        addField(grid, "Trees m", treeM, "How tall the trees are where the map shows forest, in metres (0: the forest is ignored)");
        addField(grid, "Freq. MHz", frequencyMHz, "The link's frequency in MHz: the higher, the less forest it gets through (e.g. 800 or 1800 for 4G, 2400, 5800)");
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 0)); // indented under its button
        grid.setAlignmentX(LEFT_ALIGNMENT);
        status.setAlignmentX(LEFT_ALIGNMENT);
        box.add(grid);
        box.add(Box.createVerticalStrut(6));
        box.add(status);
        return box;
    }

    /** 4G COVERAGE's fields: network and height side by side, the key and the import, the status line. */
    private JPanel coverageFields() {
        for (de.droiddrone.pc.map.OpenCellId.Operator o : de.droiddrone.pc.map.OpenCellId.Operator.values()) coverageOperator.addItem(o.title);
        for (int m : de.droiddrone.pc.map.CellCoverage.HEIGHTS_M) coverageHeight.addItem(m + " m");
        TacticalCombo.install(coverageOperator);
        TacticalCombo.install(coverageHeight);
        coverageOperator.setToolTipText("The network your SIM is on: only its masts count");
        coverageHeight.setToolTipText("The drone's height above the ground: the higher, the more masts it sees (2 m: on the ground)");
        coverageKey.setToolTipText("Your OpenCelliD access token: register free at opencellid.org, then copy the Access Token");
        coverageImport.setToolTipText("A country file from OpenCelliD's downloads page (242.csv.gz is Norway): every cell at once, no requests");
        JPanel grid = new JPanel(new GridLayout(3, 2, 6, 4));
        grid.setOpaque(false);
        grid.add(Theme.label("Network", Theme.headingFont(11f), Theme.MUTED));
        grid.add(coverageOperator);
        grid.add(Theme.label("Drone", Theme.headingFont(11f), Theme.MUTED));
        grid.add(coverageHeight);
        grid.add(small(coverageKey));
        grid.add(small(coverageImport));
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 0)); // indented under its button
        grid.setAlignmentX(LEFT_ALIGNMENT);
        coverageStatus.setAlignmentX(LEFT_ALIGNMENT);
        box.add(grid);
        box.add(Box.createVerticalStrut(6));
        box.add(coverageStatus);
        return box;
    }

    /** The coverage button lit and its fields out while it is on. */
    void setCoverageOn(boolean on) {
        lit(coverage, on);
        coverageFields.setOpen(on);
    }

    private static void addField(JPanel grid, String name, SpinnerNumberModel model, String tip) {
        JSpinner s = new JSpinner(model);
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(4);
        s.setToolTipText(tip);
        grid.add(Theme.label(name, Theme.headingFont(11f), Theme.MUTED));
        grid.add(s);
    }

    private static void slideRow(JPanel body, SlideOut slide) {
        slide.setAlignmentX(LEFT_ALIGNMENT);
        body.add(slide);
    }

    /** The radio horizon's heights as set. */
    RadioHorizon.Settings horizonSettings() {
        return new RadioHorizon.Settings(mastM.getNumber().doubleValue(), aircraftM.getNumber().doubleValue(),
                treeM.getNumber().doubleValue(), frequencyMHz.getNumber().doubleValue());
    }

    void onHorizonSettings(Runnable listener) {
        for (SpinnerNumberModel m : new SpinnerNumberModel[]{mastM, aircraftM, treeM, frequencyMHz}) m.addChangeListener(e -> listener.run());
    }

    private static void section(JPanel body, String title) {
        if (body.getComponentCount() > 0) body.add(Box.createVerticalStrut(10));
        JComponent t = Theme.label(title, Theme.headingFont(11f), Theme.MUTED);
        t.setAlignmentX(LEFT_ALIGNMENT);
        body.add(t);
        body.add(Box.createVerticalStrut(5));
    }

    /** The space a section heading leaves, without the heading: for a tool whose button already names it. */
    private static void gap(JPanel body) {
        if (body.getComponentCount() > 0) body.add(Box.createVerticalStrut(10));
    }

    private static void row(JPanel body, JComponent c) {
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
        lit(horizon, t == MapView.Tool.HORIZON);
        lit(gcsSearch, t == MapView.Tool.SEARCH);
        horizonFields.setOpen(t == MapView.Tool.HORIZON);
        searchFields.setOpen(t == MapView.Tool.SEARCH);
        boolean measured = map.currentMeasurement() != null;
        if (measureRow.isVisible() != measured) {
            measureRow.setVisible(measured);
            bodies.get(Group.TOOLS).revalidate();
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

    Group group() {
        return group;
    }

    /** MAP TOOLS out or in: the strip of DRAW / TOOLS / DATA; in, the open list folds with it. */
    void setOpen(boolean open, boolean animate) {
        if (this.open == open) return;
        this.open = open;
        if (!open) group = null;
        animateTo(open ? 1 : 0, group == null ? 0 : 1, animate);
        groupChanged();
    }

    private Runnable onGroupChanged;

    /** Told when the open group changes (MAP TOOLS in or out, another tab). */
    void onGroupChanged(Runnable listener) {
        onGroupChanged = listener;
    }

    private void groupChanged() {
        if (onGroupChanged != null) onGroupChanged.run();
    }

    /** The TOOLS list is out: the radio horizon and the GCS search show on the map only then (the pilot). */
    boolean toolsShown() {
        return open && group == Group.TOOLS;
    }

    /** One group's list out beside the strip (null folds it back); MAP TOOLS comes out with it. */
    void showGroup(Group g, boolean animate) {
        if (g != null && !open) open = true;
        group = g;
        if (g != null && scroll.getViewport().getView() != bodies.get(g)) {
            scroll.setViewportView(bodies.get(g));
            scroll.getViewport().setBackground(Theme.PANEL_2);
        }
        animateTo(open ? 1 : 0, g == null ? 0 : 1, animate);
        groupChanged();
    }

    private void animateTo(float strip, float body, boolean animate) {
        for (Map.Entry<Group, JButton> e : groupTabs.entrySet()) e.getValue().repaint();
        tab.repaint();
        shownFrom = shown;
        bodyFrom = bodyShown;
        shownTo = strip;
        bodyTo = body;
        if (!animate) {
            shown = strip;
            bodyShown = body;
            slide.stop();
            revalidate();
            repaint();
            return;
        }
        slideStart = System.currentTimeMillis();
        slide.start();
    }

    private void tick() {
        float t = Math.min(1, (System.currentTimeMillis() - slideStart) / (float) MS);
        shown = shownFrom + (shownTo - shownFrom) * t;
        bodyShown = bodyFrom + (bodyTo - bodyFrom) * t;
        revalidate();
        repaint();
        if (t >= 1) slide.stop();
    }

    private static float eased(float v) {
        return 1 - (1 - v) * (1 - v);
    }

    /** The tab the tour points at. */
    JComponent tabButton() {
        return tab;
    }

    JComponent groupButton(Group g) {
        return groupTabs.get(g);
    }

    /** A flat tab: its title written upwards, a chevron showing which way it goes; lit while its part is out. */
    private final class TabButton extends JButton {
        private final boolean main;

        TabButton(String title, boolean main) {
            super(title);
            this.main = main;
            setPreferredSize(new Dimension(main ? 26 : STRIP_WIDTH - 2, 10));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }

        private boolean isOut() {
            if (main) return open;
            for (Map.Entry<Group, JButton> e : groupTabs.entrySet()) if (e.getValue() == this) return e.getKey() == group;
            return false;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight();
                boolean out = isOut();
                boolean hot = getModel().isRollover() || out;
                g.setColor(hot ? Theme.PANEL_2 : Theme.PANEL);
                g.fillRect(0, 0, w, h);
                g.setColor(out && !main ? Theme.ACCENT : hot ? Theme.ACCENT : Theme.LINE);
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
                if (out) { // < : it folds back
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

    /**
     * A tool's fields sliding out from under its button (1.0.39): the height grows from nothing over 220 ms with the
     * content's bottom edge leading, as if it came out from behind the button; the same way back in.
     */
    static final class SlideOut extends JPanel {
        private final JComponent content;
        private final Timer timer = new Timer(15, e -> tick());
        private float shown, from, to;
        private long start;

        SlideOut(JComponent content) {
            super(null);
            this.content = content;
            setOpaque(false);
            add(content);
            timer.setRepeats(true);
        }

        void setOpen(boolean open) {
            float target = open ? 1 : 0;
            if (to == target) return;
            from = shown;
            to = target;
            start = System.currentTimeMillis();
            timer.start();
        }

        boolean isOpen() {
            return to == 1;
        }

        /** Done sliding (for tests and pictures). */
        void finish() {
            timer.stop();
            shown = to;
            relayout();
        }

        private void tick() {
            float t = Math.min(1, (System.currentTimeMillis() - start) / (float) MS);
            shown = from + (to - from) * t;
            relayout();
            if (t >= 1) timer.stop();
        }

        private void relayout() {
            revalidate();
            java.awt.Container p = getParent();
            if (p != null) p.revalidate();
            repaint();
        }

        float shown() {
            return shown;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = content.getPreferredSize();
            return new Dimension(BODY_WIDTH - 20, Math.round(d.height * eased(shown)));
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public void doLayout() {
            Dimension d = content.getPreferredSize();
            content.setBounds(0, getHeight() - d.height, getWidth(), d.height); // the bottom edge leads out
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
