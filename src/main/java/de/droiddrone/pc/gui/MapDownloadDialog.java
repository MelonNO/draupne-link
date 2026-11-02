package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.Mercator;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.List;
import java.util.Locale;

/**
 * DOWNLOAD AREA: the map saved for offline use, like Mission Planner's prefetch. Every tile of an area - the view, or
 * a box drawn on the map with Alt + drag - for every picture and every zoom level, one START (or one picture and a
 * narrower range of levels). Says how many tiles that is and roughly how much before START; the bar and the figures
 * while it runs; CANCEL stops it, what is saved stays. Closing the window doesn't stop a download. A new box while
 * one runs waits for it.
 */
final class MapDownloadDialog extends JDialog {
    private static final int MIN_LEVEL = 2, MAX_LEVEL = 19;
    private static final int KB_PER_TILE = 20; // a rough average over the pictures
    private static final String ALL = "All pictures";

    private final TileCache cache;
    private final JComboBox<String> picture = new JComboBox<>();
    private final SpinnerNumberModel fromModel = new SpinnerNumberModel(MIN_LEVEL, MIN_LEVEL, MAX_LEVEL, 1), toModel = new SpinnerNumberModel(MAX_LEVEL, MIN_LEVEL, MAX_LEVEL, 1);
    private final JLabel areaValue = value(), tilesValue = value(), status = Theme.label(" ", Theme.font(Font.PLAIN, 12.5f), Theme.MUTED);
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JButton start = Theme.primaryButton("Start"), cancel = Theme.secondaryButton("Cancel");
    private final Timer refresh = new Timer(250, e -> refresh());
    private double[] area; // minLat, maxLat, minLon, maxLon
    private TileCache.Download download;
    private boolean settingUp;

    MapDownloadDialog(Window owner, TileCache cache, TileSource shown, double zoom, double[] area) {
        super(owner, "Download area", ModalityType.MODELESS);
        this.cache = cache;
        setIconImages(owner.getIconImages());
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        JPanel card = Theme.card(new BorderLayout(0, 10));
        card.add(Theme.sectionTitle("Download area"), BorderLayout.NORTH);
        JPanel rows = new JPanel(new GridLayout(5, 2, 12, 8));
        rows.setOpaque(false);
        row(rows, "AREA", areaValue);
        picture.addItem(ALL);
        for (TileSource s : TileSource.values()) picture.addItem(s.title);
        TacticalCombo.install(picture);
        picture.setToolTipText("Which map pictures to save: all four, one after another, or one. OpenStreetMap is fetched one tile at a time: keep its areas small");
        picture.addActionListener(e -> {
            if (!settingUp) count();
        });
        row(rows, "PICTURE", picture);
        JSpinner from = spinner(fromModel), to = spinner(toModel);
        from.setToolTipText("The coarsest zoom level to save: 2 is the whole world in a few tiles, so every level costs little until about 12");
        to.setToolTipText("The finest zoom level to save: 19 is the deepest there is (18 for Kartverket), and each level down has four times the tiles");
        row(rows, "FROM LEVEL", from);
        row(rows, "TO LEVEL", to);
        row(rows, "TILES", tilesValue);
        card.add(rows, BorderLayout.CENTER);
        JPanel south = new JPanel(new GridLayout(2, 1, 0, 6));
        south.setOpaque(false);
        south.add(status);
        bar.setBackground(Theme.PANEL_2);
        bar.setForeground(Theme.ACCENT);
        bar.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        bar.setPreferredSize(new Dimension(360, 10));
        south.add(bar);
        card.add(south, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        start.setToolTipText("Fetch every tile of the area that isn't saved yet");
        start.addActionListener(e -> start());
        cancel.setToolTipText("Stop; the tiles saved so far stay");
        cancel.setEnabled(false);
        cancel.addActionListener(e -> {
            if (download != null) download.cancel();
        });
        buttons.add(cancel);
        buttons.add(start);
        root.add(buttons, BorderLayout.SOUTH);

        refresh.setRepeats(true);
        setArea(area, shown, zoom);
        pack();
        setLocationRelativeTo(owner);
    }

    private static JLabel value() {
        return Theme.label("-", Theme.monoFont(Font.BOLD, 12.5f), Theme.TEXT);
    }

    private static void row(JPanel rows, String name, java.awt.Component field) {
        rows.add(Theme.label(name, Theme.headingFont(11f), Theme.MUTED));
        rows.add(field);
    }

    private JSpinner spinner(SpinnerNumberModel model) {
        JSpinner s = new JSpinner(model);
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(3);
        s.addChangeListener(e -> {
            if (!settingUp) count();
        });
        return s;
    }

    /** A new area (a box or the view): every picture and every level, unless a download is running. */
    void setArea(double[] area, TileSource shown, double zoom) {
        this.area = area;
        settingUp = true;
        if (download == null || download.isFinished()) {
            picture.setSelectedIndex(0);
            fromModel.setValue(MIN_LEVEL);
            toModel.setValue(MAX_LEVEL);
        }
        settingUp = false;
        double midLat = (area[0] + area[1]) / 2;
        areaValue.setText(MapView.distanceText(Mercator.distanceM(midLat, area[2], midLat, area[3])) + " x "
                + MapView.distanceText(Mercator.distanceM(area[0], area[2], area[1], area[2])));
        count();
    }

    /** The pictures chosen: all four, or the one. */
    private List<TileSource> sources() {
        int i = picture.getSelectedIndex();
        return i == 0 ? List.of(TileSource.values()) : List.of(TileSource.values()[i - 1]);
    }

    /** How many tiles the choice means, and whether it is allowed. */
    private void count() {
        int from = (Integer) fromModel.getValue(), to = (Integer) toModel.getValue();
        if (to < from) {
            tilesValue.setText("TO LEVEL is above FROM LEVEL");
            tilesValue.setForeground(Theme.BAD);
            start.setEnabled(false);
            return;
        }
        long n = TileCache.count(sources(), from, to, area[0], area[1], area[2], area[3]);
        if (n > TileCache.MAX_DOWNLOAD) {
            tilesValue.setText(String.format(Locale.ROOT, "%,d - TOO MANY (AT MOST %,d): A SMALLER AREA OR A LOWER TO LEVEL", n, TileCache.MAX_DOWNLOAD));
            tilesValue.setForeground(Theme.BAD);
            start.setEnabled(false);
            return;
        }
        tilesValue.setText(String.format(Locale.ROOT, "%,d  (ABOUT %s)", n, sizeText(n * KB_PER_TILE)));
        tilesValue.setForeground(Theme.TEXT);
        start.setEnabled(download == null || download.isFinished());
        pack();
    }

    private static String sizeText(long kb) {
        if (kb >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", kb / 1024.0 / 1024.0);
        return kb >= 1024 ? String.format(Locale.ROOT, "%.0f MB", kb / 1024.0) : kb + " KB";
    }

    private void start() {
        download = cache.download(sources(), (Integer) fromModel.getValue(), (Integer) toModel.getValue(), area[0], area[1], area[2], area[3]);
        start.setEnabled(false);
        cancel.setEnabled(true);
        bar.setValue(0);
        status.setForeground(Theme.MUTED);
        refresh.start();
        refresh();
    }

    private void refresh() {
        TileCache.Download d = download;
        if (d == null) return;
        int done = d.done();
        bar.setValue(d.total == 0 ? 1000 : (int) (1000L * done / d.total));
        String figures = String.format(Locale.ROOT, "%,d of %,d tiles: %,d fetched, %,d saved already, %,d failed",
                done, d.total, d.fetched(), d.onDisk(), d.failed());
        if (!d.isFinished()) {
            TileSource now = d.current();
            status.setText((now == null ? "" : now.title + " - ") + figures);
            return;
        }
        refresh.stop();
        cancel.setEnabled(false);
        start.setEnabled(true);
        String how = d.isCancelled() ? "Cancelled" : d.gaveUp() ? "Stopped: no map server could be reached" : d.failed() > 0 ? "Done, with failures (try again for them)" : "Done";
        status.setText(how + " - " + figures);
        status.setForeground(d.isCancelled() || d.gaveUp() ? Theme.WARN : d.failed() > 0 ? Theme.WARN : Theme.OK);
    }
}
