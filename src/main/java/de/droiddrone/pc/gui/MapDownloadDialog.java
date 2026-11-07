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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * DOWNLOAD AREA: the map saved for offline use, like Mission Planner's prefetch. Every tile of an area - the view, a
 * box drawn on the map with SELECT WITH CURSOR or Alt + drag - for every map type and every zoom level, one START (or
 * one type and a narrower range of levels). Says how many tiles that is, roughly how much and how long before START,
 * and warns when it is a lot; the bar and the figures while it runs, with the time left from the measured pace;
 * PAUSE holds it, CANCEL stops it, what is saved stays. HIDE puts the window away while the download goes on: the map
 * window shows a line with its progress, which brings it back. Closing the window doesn't stop a download. A new box
 * while one runs waits for it. An area outside Norway leaves Kartverket out ("All map types"): its servers have
 * nothing there.
 */
final class MapDownloadDialog extends JDialog {
    private static final int MIN_LEVEL = 2, MAX_LEVEL = 19;
    private static final int KB_PER_TILE = 20; // a rough average over the map types
    private static final double TILES_PER_SECOND = 6; // a rough average: two connections, far-away servers
    private static final String ALL = "All map types";
    /** The measured pace counts from this many tiles on; before, the guess above. */
    private static final int MEASURE_AFTER = 40;

    private final TileCache cache;
    private final Consumer<String> progress;
    private final JComboBox<String> picture = new JComboBox<>();
    private final SpinnerNumberModel fromModel = new SpinnerNumberModel(MIN_LEVEL, MIN_LEVEL, MAX_LEVEL, 1), toModel = new SpinnerNumberModel(MAX_LEVEL, MIN_LEVEL, MAX_LEVEL, 1);
    private final JLabel areaValue = value(), tilesValue = value(), status = Theme.label(" ", Theme.font(Font.PLAIN, 12.5f), Theme.MUTED);
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JButton start = Theme.primaryButton("Start"), pause = Theme.secondaryButton("Pause"), cancel = Theme.secondaryButton("Cancel"),
            hide = Theme.secondaryButton("Hide");
    private final Timer refresh = new Timer(250, e -> refresh());
    private double[] area; // minLat, maxLat, minLon, maxLon
    private TileCache.Download download;
    private boolean settingUp;
    /** The download's running time without the pauses, for the pace. */
    private long startedAt, pausedAt, pausedMs;

    /**
     * @param selectWithCursor puts the map into drawing a box with the next drag
     * @param progress         gets a short line while a download runs (for the map window), null when it is over
     */
    MapDownloadDialog(Window owner, TileCache cache, TileSource shown, double zoom, double[] area, Runnable selectWithCursor,
                      Consumer<String> progress) {
        super(owner, "Download area", ModalityType.MODELESS);
        this.cache = cache;
        this.progress = progress;
        setIconImages(owner.getIconImages());
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        JPanel card = Theme.card(new BorderLayout(0, 10));
        card.add(Theme.sectionTitle("Download area"), BorderLayout.NORTH);
        JPanel rows = new JPanel(new GridLayout(5, 2, 12, 8));
        rows.setOpaque(false);
        JPanel areaRow = new JPanel(new BorderLayout(10, 0));
        areaRow.setOpaque(false);
        areaRow.add(areaValue, BorderLayout.CENTER);
        JButton select = Theme.secondaryButton("Select with cursor");
        select.setToolTipText("Draw the area on the map: press on one corner and drag to the other (Alt + drag does the same any time); right-click on the map calls it off");
        select.addActionListener(e -> {
            selectWithCursor.run();
            areaValue.setText("DRAW A BOX ON THE MAP");
            setVisible(false); // out of the way of the map; back when the box is drawn (or the mode is called off)
        });
        areaRow.add(select, BorderLayout.EAST);
        row(rows, "AREA", areaRow);
        picture.addItem(ALL);
        for (TileSource s : TileSource.values()) picture.addItem(s.title);
        TacticalCombo.install(picture);
        picture.setToolTipText("Which map types to save: all of them, one after another, or one. OpenStreetMap and the world topo map are fetched one tile at a time: keep their areas small");
        picture.addActionListener(e -> {
            if (!settingUp) count();
        });
        row(rows, "MAP TYPE", picture);
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
        hide.setToolTipText("Put this window away; a download goes on, and the line in the map window brings it back");
        hide.addActionListener(e -> setVisible(false));
        start.setToolTipText("Fetch every tile of the area that isn't saved yet");
        start.addActionListener(e -> start());
        pause.setToolTipText("Hold the download; RESUME goes on where it was");
        cancel.setToolTipText("Stop; the tiles saved so far stay");
        // HIDE, PAUSE and CANCEL only mean something while a download runs: they appear with START
        hide.setVisible(false);
        pause.setVisible(false);
        cancel.setVisible(false);
        cancel.addActionListener(e -> {
            if (download != null) {
                download.resume(); // a paused download must run to notice the cancel
                download.cancel();
            }
        });
        buttons.add(hide);
        buttons.add(pause);
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

    /** A new area (a box or the view): every map type and every level, unless a download is running. */
    void setArea(double[] area, TileSource shown, double zoom) {
        this.area = area;
        settingUp = true;
        if (download == null || download.isFinished()) {
            picture.setSelectedIndex(0);
            fromModel.setValue(MIN_LEVEL);
            toModel.setValue(MAX_LEVEL);
        }
        settingUp = false;
        showArea();
        count();
    }

    private void showArea() {
        double midLat = (area[0] + area[1]) / 2;
        areaValue.setText(MapView.distanceText(Mercator.distanceM(midLat, area[2], midLat, area[3])) + " x "
                + MapView.distanceText(Mercator.distanceM(area[0], area[2], area[1], area[2])));
    }

    /**
     * SELECT WITH CURSOR ended on the map (a box drawn - setArea gets it right after - or called off with a click or
     * a right-click): the window comes back, its area line saying the area again.
     */
    void selectionEnded() {
        if (area != null) showArea();
        setVisible(true);
        toFront();
    }

    /** Any of the area in Norway (its corners or its middle): Kartverket has something there. */
    static boolean touchesNorway(double[] a) {
        return TileSource.inNorway(a[0], a[2]) || TileSource.inNorway(a[0], a[3]) || TileSource.inNorway(a[1], a[2]) || TileSource.inNorway(a[1], a[3])
                || TileSource.inNorway((a[0] + a[1]) / 2, (a[2] + a[3]) / 2);
    }

    /** The map types chosen: all (Kartverket in Norway, the world topo map outside it), or the one. */
    private List<TileSource> sources() {
        int i = picture.getSelectedIndex();
        if (i > 0) return List.of(TileSource.values()[i - 1]);
        List<TileSource> all = new ArrayList<>();
        boolean norway = touchesNorway(area);
        for (TileSource s : TileSource.values()) if (norway ? s != TileSource.WORLD_TOPO : !s.norwayOnly) all.add(s);
        return all;
    }

    /** How many tiles the choice means, roughly how much and how long; a warning when it is a lot. */
    private void count() {
        int from = (Integer) fromModel.getValue(), to = (Integer) toModel.getValue();
        if (to < from) {
            tilesValue.setText("TO LEVEL must not be lower than FROM LEVEL");
            tilesValue.setForeground(Theme.BAD);
            start.setEnabled(false);
            return;
        }
        List<TileSource> sources = sources();
        long n = TileCache.count(sources, from, to, area[0], area[1], area[2], area[3]);
        boolean kartverketOut = picture.getSelectedIndex() == 0 && !touchesNorway(area);
        tilesValue.setText(String.format(Locale.ROOT, "%,d  (ABOUT %s, %s)%s", n, sizeText(n * KB_PER_TILE), timeText(n), kartverketOut ? "   NO KARTVERKET" : ""));
        boolean aLot = n > TileCache.MAX_DOWNLOAD;
        tilesValue.setForeground(aLot ? Theme.WARN : Theme.TEXT);
        if (download == null || download.isFinished()) {
            String warn = aLot ? "A lot of tiles: this will take a long time. A smaller area or a lower TO LEVEL is much quicker." : "";
            String out = kartverketOut ? "Kartverket is left out: the area is outside Norway, where its maps end." : "";
            status.setText(warn.isEmpty() && out.isEmpty() ? " " : warn.isEmpty() || out.isEmpty() ? warn + out : "<html>" + warn + "<br>" + out + "</html>");
            status.setForeground(Theme.WARN);
        }
        start.setEnabled(download == null || download.isFinished());
        pack();
    }

    private static String sizeText(long kb) {
        if (kb >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", kb / 1024.0 / 1024.0);
        return kb >= 1024 ? String.format(Locale.ROOT, "%.0f MB", kb / 1024.0) : kb + " KB";
    }

    /** "ROUGHLY 2 H 10 MIN", "ROUGHLY 4 MIN", "UNDER A MINUTE", "ROUGHLY 3 DAYS", "MONTHS": at the guessed pace. */
    static String timeText(long tiles) {
        return timeText(tiles, TILES_PER_SECOND);
    }

    static String timeText(long tiles, double tilesPerSecond) {
        long seconds = Math.round(tiles / tilesPerSecond);
        if (seconds < 60) return "UNDER A MINUTE";
        long minutes = (seconds + 30) / 60;
        if (minutes < 60) return "ROUGHLY " + minutes + " MIN";
        long hours = minutes / 60;
        if (hours >= 24 * 60) return "MONTHS";
        if (hours >= 48) return "ROUGHLY " + (hours + 12) / 24 + " DAYS";
        return "ROUGHLY " + hours + " H" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " MIN");
    }

    private void start() {
        download = cache.download(sources(), (Integer) fromModel.getValue(), (Integer) toModel.getValue(), area[0], area[1], area[2], area[3]);
        startedAt = System.currentTimeMillis();
        pausedMs = 0;
        start.setEnabled(false);
        pause.setText("PAUSE");
        for (JButton b : new JButton[]{hide, pause, cancel}) b.setVisible(true);
        bar.setValue(0);
        status.setForeground(Theme.MUTED);
        refresh.start();
        refresh();
        pack();
    }

    private void togglePause() {
        TileCache.Download d = download;
        if (d == null || d.isFinished()) return;
        if (d.isPaused()) {
            d.resume();
            pausedMs += System.currentTimeMillis() - pausedAt;
            pause.setText("PAUSE");
        } else {
            d.pause();
            pausedAt = System.currentTimeMillis();
            pause.setText("RESUME");
        }
        refresh();
    }

    /** The time left from the pace so far (the guess until enough tiles have gone by), as "ABOUT 12 MIN LEFT". */
    private String timeLeft(TileCache.Download d) {
        long left = d.total - d.done();
        if (left <= 0) return "";
        long running = System.currentTimeMillis() - startedAt - pausedMs - (d.isPaused() ? System.currentTimeMillis() - pausedAt : 0);
        double pace = d.done() >= MEASURE_AFTER && running > 2000 ? d.done() * 1000.0 / running : TILES_PER_SECOND;
        return "   " + timeText(left, pace).replace("ROUGHLY", "ABOUT") + " LEFT";
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
            boolean flight = d.isHeldForFlight(); // 1.0.49: nothing fetched while a drone here may fly; on by itself after
            status.setText((d.isPaused() ? "PAUSED - " : flight ? "WAITING: A DRONE HERE MAY BE FLYING - " : now == null ? "" : now.title + " - ")
                    + figures + (d.isPaused() || flight ? "" : timeLeft(d)));
            if (progress != null) progress.accept(String.format(Locale.ROOT, d.isPaused() || flight ? "DOWNLOAD PAUSED %,d / %,d" : "DOWNLOADING %,d / %,d", done, d.total));
            return;
        }
        refresh.stop();
        pause.setText("PAUSE");
        for (JButton b : new JButton[]{hide, pause, cancel}) b.setVisible(false);
        start.setEnabled(true);
        pack();
        String refused = "";
        if (!d.refused().isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (TileSource s : d.refused()) names.append(names.length() == 0 ? "" : ", ").append(s.title);
            refused = " - " + names + " left out: the server kept refusing";
        }
        String how = d.isCancelled() ? "Cancelled" : d.gaveUp() ? "Stopped: no map server could be reached" : d.failed() > 0 ? "Done, with failures (try again for them)" : "Done";
        status.setText(how + refused + " - " + figures);
        status.setForeground(d.isCancelled() || d.gaveUp() ? Theme.WARN : d.failed() > 0 ? Theme.WARN : Theme.OK);
        if (progress != null) progress.accept(null);
    }
}
