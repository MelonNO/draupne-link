package de.droiddrone.pc.gui;

import de.droiddrone.pc.audio.FileAudio;
import de.droiddrone.pc.flights.FlightData;
import de.droiddrone.pc.flights.FlightLibrary;
import de.droiddrone.pc.flights.FlightMarks;
import de.droiddrone.pc.flights.FlightNotes;
import de.droiddrone.pc.flights.FlightReport;
import de.droiddrone.pc.flights.FlightSummary;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.ClipPlayer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * PREVIOUS FLIGHTS (TOOLS, and the map window's button that was TRACKS; the pilot, 2026-11-08): the flights on the
 * left, newest first - date, drone, length, distance. The chosen one plays: its recording (the copy with the OSD drawn
 * in when there is one) with its sound, its track on a map with the drone where it was, the figures at that moment
 * (altitude, speed, battery, distance from launch, flight mode), and under them ONE bar for all of it - altitude and
 * battery over the flight, the link's ping, losses and failsafes from its link log - that a click or a drag moves to.
 * ON MAP draws it on the map window as before; EXPORT copies its files to a folder; DELETE removes them.
 * Decoding runs on a thread of its own at the lowest priority, the sound on another: never the live video's.
 * <p>
 * The debrief: over the video and the map the flight's figures as a whole ({@link SummaryStrip}), there without
 * playing; on the bar its marks - the pilot's bookmarks, the pins dropped while it flew, the overwatch clips of its
 * time - each a sign of its own to point at (when and what) and to click (the bar goes there). BOOKMARK (or B) marks
 * the moment playing now with a few words; a right-click on a bookmark's sign renames or removes it. NOTE under the
 * flights is the pilot's free text on the flight. Both are kept in the flight's notes file as they are made. The
 * speed button plays 1X, 2X, 4X or 0.5X. REPORT writes the flight as one HTML file ({@link FlightReport}).
 */
final class PreviousFlightsDialog extends JDialog {
    private static PreviousFlightsDialog open;

    private final Path tracksDir, videosDir, logsDir;
    private final Consumer<Path> onMap;
    private final TileCache cache;
    /** The app's pins and overwatch clips: those of a flight's time are marks on its bar. Null: none. */
    private final Pins pins;
    private final ClipIndex clips;
    private final DefaultListModel<FlightLibrary.Flight> model = new DefaultListModel<>();
    final JList<FlightLibrary.Flight> list = new JList<>(model);
    private final JLabel listNote = Theme.label("Reading flights...", Theme.font(Font.PLAIN, 12.5f), Theme.MUTED);
    /** 1.0.71: narrows the list to a drone (or a date). */
    final javax.swing.JTextField search = new javax.swing.JTextField(12);
    /** Every flight read, searched or not. */
    private List<FlightLibrary.Flight> allFlights = List.of();
    final VideoPane video = new VideoPane();
    final MapView map;
    final Timeline timeline = new Timeline();
    final JLabel[] figures = new JLabel[5];
    final JButton play = Theme.primaryButton("PLAY");
    private final JButton onMapButton = Theme.secondaryButton("On map");
    private final JButton export = Theme.secondaryButton("Export");
    final JButton delete = Theme.secondaryButton("Delete");
    final JButton report = Theme.secondaryButton("Report");
    final JButton bookmark = Theme.secondaryButton("Bookmark");
    final JButton speedButton = Theme.secondaryButton(speedText(1));
    final JLabel time = Theme.label("0:00 / 0:00", Theme.monoFont(Font.BOLD, 12.5f), Theme.TEXT);
    private final Timer tick = new Timer(66, e -> tick());
    final SummaryStrip summaryStrip = new SummaryStrip();
    /** NOTE: the pilot's free text on the chosen flight. */
    final JTextArea note = new JTextArea();
    /** A moment after the last key in the note it is kept, so little of it is lost when the app ends with this window open. */
    private final Timer noteIdle = new Timer(1500, e -> keepNote());
    private boolean settingNote, reporting;

    FlightData data;
    /** The chosen flight's figures, the pilot's notes on it and what its bar marks; null and empty while none is loaded. */
    FlightSummary summary;
    FlightNotes notes;
    List<FlightMarks.Mark> marks = List.of();
    /** The overwatch clips of the chosen flight's time, read with it. */
    private List<ClipIndex.Clip> flightClips = List.of();
    // the clock: epoch ms of the flight; while playing it runs from run.from at run.wall, run.speed times as fast
    private volatile long position;
    private volatile boolean playing;
    private volatile Run run = new Run(0, 0, 1);
    /** How fast the clock runs while playing; kept over pause, seek and the next flight. */
    private volatile double speed = 1;
    /** This PC's time in ms: what the clock runs by (a test's own, to step it). */
    volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    private Player player;
    private FileAudio audio;
    /** Where the sound file's 0 is on the flight's clock: the start of the recording's part being played. */
    private long audioStartMs;
    /** The thread reading the folders or the chosen flight now (or the one that did last). */
    private volatile Thread reading;

    /** The speeds the speed button goes round. */
    static final double[] SPEEDS = {1, 2, 4, 0.5};

    /**
     * The clock while it runs: the flight's time `from` at this PC's time `wall`, going `speed` times as fast. One
     * object, so the player's thread never reads half of a change.
     */
    private record Run(long from, long wall, double speed) {}

    /**
     * A flight read for this screen (off the Swing thread): its files, its figures, the pilot's notes and the
     * overwatch clips that started while it flew.
     */
    record Debrief(FlightData data, FlightSummary summary, FlightNotes notes, List<ClipIndex.Clip> clips) {
        /** @param index the app's clips, or null */
        static Debrief read(FlightLibrary.Flight f, ClipIndex index) {
            FlightData d = FlightData.load(f);
            return new Debrief(d, FlightSummary.of(d), FlightNotes.load(f), FlightMarks.clipsIn(index, d.startMs(), d.endMs()));
        }
    }

    /** The tour: the window, while it is open; null otherwise. */
    static java.awt.Window tourWindow() {
        PreviousFlightsDialog d = open;
        return d != null && d.isDisplayable() ? d : null;
    }

    /** The tour moves on: the window closes (its playback stops with it). */
    static void tourClose() {
        PreviousFlightsDialog d = open;
        if (d != null && d.isDisplayable()) d.dispose();
    }

    /** Opens it (in front if open already), its bars without pins or clips. */
    static void show(Window owner, TileCache cache, TileSource source, Consumer<Path> onMap) {
        show(owner, cache, source, onMap, null, null);
    }

    /**
     * Opens it (in front if open already).
     * @param pins  the app's pins: those dropped during a flight are marks on its bar; null: none
     * @param clips the app's overwatch clips: those that started during a flight are marks on its bar; null: none
     */
    static void show(Window owner, TileCache cache, TileSource source, Consumer<Path> onMap, Pins pins, ClipIndex clips) {
        if (open != null && open.isDisplayable()) {
            open.toFront();
            return;
        }
        Path tracks = null, videos = null, logs = null;
        try {
            tracks = de.droiddrone.pc.map.FlightTrack.defaultDir();
        } catch (java.io.IOException ignored) {
        }
        try {
            videos = de.droiddrone.pc.AppPaths.videosDir();
        } catch (java.io.IOException ignored) {
        }
        logs = de.droiddrone.pc.AppPaths.dataDir().resolve("logs");
        open = new PreviousFlightsDialog(owner, cache, source, onMap, tracks, videos, logs, pins, clips);
        open.setCameraTurn(turnOf(owner));
        open.setVisible(true);
    }

    /** The main window's CameraTurn, found up the owners (the map window's owner is the main window). */
    static CameraTurn turnOf(Window w) {
        for (Window o = w; o != null; o = o.getOwner()) if (o instanceof MainWindow m) return m.cameraTurn();
        return null;
    }

    /**
     * Each phone's way up (1.0.55): the recording itself is the camera's picture as it came, so it is turned as its
     * drone's is set now; the copy with the OSD was turned when it was made, so it plays as it is.
     */
    void setCameraTurn(CameraTurn t) {
        video.turn = t;
        if (t != null) {
            java.util.function.BiConsumer<String, Boolean> l = (phone, on) -> video.repaint();
            t.addListener(l);
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    t.removeListener(l);
                }
            });
        }
    }

    PreviousFlightsDialog(Window owner, TileCache cache, TileSource source, Consumer<Path> onMap, Path tracksDir, Path videosDir, Path logsDir) {
        this(owner, cache, source, onMap, tracksDir, videosDir, logsDir, null, null);
    }

    PreviousFlightsDialog(Window owner, TileCache cache, TileSource source, Consumer<Path> onMap, Path tracksDir, Path videosDir, Path logsDir,
                          Pins pins, ClipIndex clips) {
        super(owner, "Previous flights", ModalityType.MODELESS);
        this.tracksDir = tracksDir;
        this.videosDir = videosDir;
        this.logsDir = logsDir;
        this.onMap = onMap;
        this.cache = cache;
        this.pins = pins;
        this.clips = clips;
        if (owner != null) setIconImages(owner.getIconImages());
        map = new MapView(cache);
        if (source != null) map.setSource(source);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        // left: the flights
        JPanel left = Theme.card(new BorderLayout(0, 8));
        // 1.0.71, the pilot: "search / filter by name" - the list follows every key (as the pins sheet's SEARCH)
        JPanel leftTop = new JPanel(new BorderLayout(0, 6));
        leftTop.setOpaque(false);
        leftTop.add(Theme.sectionTitle("Previous flights"), BorderLayout.NORTH);
        JPanel find = new JPanel(new BorderLayout(8, 0));
        find.setOpaque(false);
        find.add(Theme.label("SEARCH", Theme.headingFont(11f), Theme.MUTED), BorderLayout.WEST);
        search.setFont(Theme.font(Font.PLAIN, 12.5f));
        search.setToolTipText("Drone or date");
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                showFlights(allFlights, list.getSelectedValue());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                showFlights(allFlights, list.getSelectedValue());
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        find.add(search, BorderLayout.CENTER);
        leftTop.add(find, BorderLayout.SOUTH);
        left.add(leftTop, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.PANEL);
        list.setCellRenderer(new FlightCell());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) choose(list.getSelectedValue());
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.setPreferredSize(new Dimension(250, 400));
        left.add(scroll, BorderLayout.CENTER);
        JPanel leftFoot = new JPanel(new BorderLayout(0, 8));
        leftFoot.setOpaque(false);
        leftFoot.add(listNote, BorderLayout.NORTH);
        // NOTE: the pilot's own words on the chosen flight, kept as they are typed
        JPanel notePanel = new JPanel(new BorderLayout(0, 4));
        notePanel.setOpaque(false);
        notePanel.add(Theme.label("NOTE", Theme.headingFont(10.5f), Theme.MUTED), BorderLayout.NORTH);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setFont(Theme.font(Font.PLAIN, 12.5f));
        note.setBackground(Theme.PANEL_2);
        note.setForeground(Theme.TEXT);
        note.setCaretColor(Theme.TEXT);
        note.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        note.setToolTipText("Kept with the flight");
        note.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                keepNote();
            }
        });
        note.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                if (!settingNote) noteIdle.restart();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (!settingNote) noteIdle.restart();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        noteIdle.setRepeats(false);
        JScrollPane noteScroll = new JScrollPane(note, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        noteScroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        noteScroll.setPreferredSize(new Dimension(250, 74));
        notePanel.add(noteScroll, BorderLayout.CENTER);
        leftFoot.add(notePanel, BorderLayout.CENTER);
        JButton folder = Theme.secondaryButton("Folder");
        folder.setToolTipText("Open the tracks folder");
        folder.addActionListener(e -> {
            try {
                if (this.tracksDir != null) de.droiddrone.pc.Outside.open(this.tracksDir.toFile());
            } catch (Exception ex) {
                Dialogs.message(this, "Can't open the folder: " + ex.getMessage(), "Previous flights", JOptionPane.WARNING_MESSAGE);
            }
        });
        leftFoot.add(folder, BorderLayout.SOUTH);
        left.add(leftFoot, BorderLayout.SOUTH);
        root.add(left, BorderLayout.WEST);

        // right: the flight's figures as a whole, video and map, the figures at the moment, the bar, the buttons
        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);
        right.add(summaryStrip, BorderLayout.NORTH);
        JPanel views = new JPanel(new GridLayout(1, 2, 10, 0));
        views.setOpaque(false);
        views.add(framed(video));
        views.add(framed(map));
        right.add(views, BorderLayout.CENTER);

        JPanel bottom = Theme.card(new BorderLayout(0, 8));
        JPanel figs = new JPanel(new GridLayout(1, 5, 10, 0));
        figs.setOpaque(false);
        String[] names = {"ALT", "SPEED", "BATT", "DIST", "MODE"};
        for (int i = 0; i < figures.length; i++) {
            JPanel cell = new JPanel(new BorderLayout());
            cell.setOpaque(false);
            cell.add(Theme.label(names[i], Theme.headingFont(10.5f), Theme.MUTED), BorderLayout.NORTH);
            figures[i] = Theme.label("-", Theme.monoFont(Font.BOLD, 15f), Theme.TEXT);
            cell.add(figures[i], BorderLayout.CENTER);
            figs.add(cell);
        }
        bottom.add(figs, BorderLayout.NORTH);
        timeline.setPreferredSize(new Dimension(600, 92 + Timeline.MARKS_H));
        bottom.add(timeline, BorderLayout.CENTER);
        JPanel playRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        playRow.setOpaque(false);
        play.addActionListener(e -> togglePlay());
        playRow.add(play);
        speedButton.setToolTipText("Playing speed (sound at 1X only)");
        speedButton.addActionListener(e -> nextSpeed());
        int widest = 0; // one width for every speed: the row doesn't shift as it changes
        for (double k : SPEEDS) {
            speedButton.setText(speedText(k));
            widest = Math.max(widest, speedButton.getPreferredSize().width);
        }
        speedButton.setText(speedText(speed));
        speedButton.setPreferredSize(new Dimension(widest, speedButton.getPreferredSize().height));
        playRow.add(speedButton);
        playRow.add(time);
        bookmark.setToolTipText("Mark this moment (B)");
        bookmark.addActionListener(e -> bookmarkNow());
        playRow.add(bookmark);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        onMapButton.setToolTipText("Draw it on the map window");
        onMapButton.addActionListener(e -> {
            if (data != null && data.flight.track() != null && this.onMap != null) this.onMap.accept(data.flight.track());
        });
        report.setToolTipText("The flight as one HTML file: figures, track, graphs, marks, note");
        report.addActionListener(e -> reportFlight());
        export.setToolTipText("Copy its track, video and log to a folder");
        export.addActionListener(e -> exportFlight());
        delete.setToolTipText("Delete its track, video and log");
        delete.addActionListener(e -> deleteFlight());
        actions.add(onMapButton);
        actions.add(report);
        actions.add(export);
        actions.add(delete);
        JPanel buttons = new JPanel(new TwoEnds(playRow, actions, 10, 8)) {
            @Override
            public Dimension getPreferredSize() { // asked anew each time: one line or two goes by the card's width now
                return getLayout().preferredLayoutSize(this);
            }
        };
        buttons.setOpaque(false);
        buttons.add(playRow);
        buttons.add(actions);
        bottom.add(buttons, BorderLayout.SOUTH);
        right.add(bottom, BorderLayout.SOUTH);
        root.add(right, BorderLayout.CENTER);

        timeline.onSeek = this::seek;
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("SPACE"), "play");
        root.getActionMap().put("play", new WindowKey(this::togglePlay));
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("B"), "bookmark");
        root.getActionMap().put("bookmark", new WindowKey(this::bookmarkNow));
        showDebrief();
        Theme.greyWhenOff(onMapButton, report, export, delete); // I-006: no flight chosen, they look off (PLAY greys by itself)
        setButtons();
        pack();
        ScreenFit.apply(this, 900, 600, 1280, 760);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                keepNote(); // the note as it stands when the window goes
                tick.stop(); // 1.0.64: it ran on (15 a second) and kept this window, its map and its flight in memory
                stopAll();
            }
        });
        tick.start();
        reload(null);
    }

    /**
     * A key for the whole window (Space, B) - but not while a text is being typed: in the note the key is a letter of
     * it, and goes there.
     */
    private static final class WindowKey extends javax.swing.AbstractAction {
        private final Runnable press;

        WindowKey(Runnable press) {
            this.press = press;
        }

        @Override
        public boolean isEnabled() {
            return !(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof javax.swing.text.JTextComponent);
        }

        @Override
        public void actionPerformed(java.awt.event.ActionEvent e) {
            press.run();
        }
    }

    /**
     * The row under the bar: the playing controls at its left end, the flight's buttons at its right end - and on a
     * window too narrow for both side by side, the flight's buttons on a line of their own under the controls. The row
     * lies across what holds it (the card): its height is asked before it has its new width, so it goes by the card's.
     */
    static final class TwoEnds implements LayoutManager {
        private final Component left, right;
        private final int gap, lineGap;
        private boolean wrapped;

        TwoEnds(Component left, Component right, int gap, int lineGap) {
            this.left = left;
            this.right = right;
            this.gap = gap;
            this.lineGap = lineGap;
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        boolean fitsOneLine(int width) {
            return left.getPreferredSize().width + gap + right.getPreferredSize().width <= width;
        }

        /** The width the row is about to have: the inner width of what holds it, once that has one; else its own. */
        private static int widthToFill(Container row) {
            Container holder = row.getParent();
            if (holder == null || holder.getWidth() <= 0) return row.getWidth();
            Insets in = holder.getInsets();
            return holder.getWidth() - in.left - in.right;
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            Dimension l = left.getPreferredSize(), r = right.getPreferredSize();
            int width = widthToFill(parent);
            boolean one = width <= 0 || fitsOneLine(width - in.left - in.right);
            return new Dimension(l.width + gap + r.width + in.left + in.right,
                    (one ? Math.max(l.height, r.height) : l.height + lineGap + r.height) + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            Dimension l = left.getPreferredSize(), r = right.getPreferredSize();
            return new Dimension(Math.max(l.width, r.width) + in.left + in.right, l.height + lineGap + r.height + in.top + in.bottom);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int x = in.left, y = in.top, w = parent.getWidth() - in.left - in.right;
            Dimension l = left.getPreferredSize(), r = right.getPreferredSize();
            boolean one = fitsOneLine(w);
            if (one) {
                int line = Math.max(l.height, r.height);
                left.setBounds(x, y + (line - l.height) / 2, l.width, l.height);
                right.setBounds(x + w - r.width, y + (line - r.height) / 2, r.width, r.height);
            } else {
                left.setBounds(x, y, Math.min(l.width, w), l.height);
                right.setBounds(x + Math.max(0, w - r.width), y + l.height + lineGap, Math.min(r.width, w), r.height);
            }
            if (one == wrapped) {
                wrapped = !one;
                SwingUtilities.invokeLater(parent::revalidate); // a line more or less: the card's height follows
            }
        }
    }

    private static JComponent framed(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.PANEL);
        p.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ the list

    /** Reads the folders off the Swing thread; then selects `keep` (or the newest). */
    void reload(FlightLibrary.Flight keep) {
        listNote.setText("Reading flights...");
        listNote.setVisible(true);
        Thread t = new Thread(() -> {
            List<FlightLibrary.Flight> flights = FlightLibrary.scan(tracksDir, videosDir, logsDir);
            SwingUtilities.invokeLater(() -> showFlights(flights, keep));
        }, "flightsScan");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        reading = t;
        t.start();
    }

    /** For tests: waits until the list is read and the flight it chose is loaded and shown. Not on the Swing thread. */
    void awaitLoaded() throws InterruptedException, java.lang.reflect.InvocationTargetException {
        Thread t;
        do {
            t = reading;
            if (t != null) t.join();
            SwingUtilities.invokeAndWait(() -> { }); // what it handed to the Swing thread is done - which may have started the next read
        } while (reading != t);
    }

    /** I-006: the list's note with no flight kept - only armed flights are kept (1.0.71), so "No flights yet." misled. */
    static final String NO_FLIGHTS = "No armed flights yet.";

    void showFlights(List<FlightLibrary.Flight> read, FlightLibrary.Flight keep) {
        allFlights = read;
        List<FlightLibrary.Flight> flights = new java.util.ArrayList<>();
        for (FlightLibrary.Flight f : read) if (matches(f, search.getText())) flights.add(f);
        if (keep != null && flights.contains(keep) && model.size() == flights.size() && list.getSelectedValue() == keep) {
            boolean same = true;
            for (int i = 0; i < flights.size() && same; i++) same = model.get(i) == flights.get(i);
            if (same) return; // a key that changed nothing: the flight chosen plays on
        }
        model.clear();
        for (FlightLibrary.Flight f : flights) model.addElement(f);
        listNote.setText(read.isEmpty() ? NO_FLIGHTS : "No flight found.");
        listNote.setVisible(flights.isEmpty());
        if (flights.isEmpty()) {
            choose(null);
            return;
        }
        int at = keep == null ? 0 : Math.max(0, flights.indexOf(keep));
        list.setSelectedIndex(at);
    }

    /**
     * SEARCH: a flight whose drone (its name, or the address it was flown by) or date and time has what is typed,
     * capitals or not; nothing typed finds every flight.
     */
    static boolean matches(FlightLibrary.Flight f, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        if (f.drone() != null && f.drone().toLowerCase(Locale.ROOT).contains(q)) return true;
        return f.title().toLowerCase(Locale.ROOT).contains(q);
    }

    /** The chosen flight: read (off the Swing thread), then ready to play from its start. */
    private void choose(FlightLibrary.Flight f) {
        keepNote(); // the note on the flight before, as it stands
        stopAll();
        data = null;
        summary = null;
        notes = null;
        flightClips = List.of();
        showDebrief();
        video.clear(f == null ? "" : "Loading...");
        map.setReplay(null);
        timeline.repaint();
        setButtons();
        if (f == null) return;
        Thread t = new Thread(() -> {
            Debrief read = Debrief.read(f, clips);
            SwingUtilities.invokeLater(() -> {
                if (list.getSelectedValue() != f) return; // another chosen meanwhile
                loaded(read);
            });
        }, "flightLoad");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        reading = t;
        t.start();
    }

    void loaded(Debrief read) {
        FlightData d = read.data();
        data = d;
        summary = read.summary();
        notes = read.notes();
        flightClips = read.clips();
        position = d.startMs();
        map.setReplay(d.path());
        if (!d.flight.parts().isEmpty()) { // 1.0.71: every part of the recording, one after the other
            player = new Player(d, d.flight.parts());
            player.start();
        } else {
            video.clear("No video");
        }
        showDebrief();
        setButtons();
        tick();
    }

    private void setButtons() {
        boolean have = data != null;
        play.setEnabled(have);
        bookmark.setEnabled(have && notes != null);
        report.setEnabled(have && !reporting);
        export.setEnabled(have);
        // not the flight being flown now (it is the newest, so the one chosen when the window opens): its track,
        // record and link log went while they were being written, and the rest of the flight was saved nowhere
        boolean flownNow = have && !FlightData.deletable(data.flight);
        delete.setEnabled(have && !flownNow);
        delete.setToolTipText(flownNow ? "Flying now" : null);
        onMapButton.setEnabled(have && data.flight.track() != null && onMap != null);
    }

    // ------------------------------------------------------------------ the debrief: figures, note, marks

    /** The summary strip, the note and the bar's marks follow the chosen flight (none: empty). */
    private void showDebrief() {
        summaryStrip.show(summary);
        settingNote = true;
        note.setText(notes == null ? "" : notes.note());
        note.setCaretPosition(0);
        settingNote = false;
        note.setEnabled(notes != null);
        showMarks();
    }

    /** The bar's marks worked out again: the flight's bookmarks as they are now, the pins and the clips of its time. */
    private void showMarks() {
        FlightData d = data;
        marks = d == null || notes == null ? List.of()
                : FlightMarks.of(d.flight, notes, pins == null ? List.of() : pins.list(), flightClips);
        timeline.repaint();
    }

    /** "1:23  Car at the gate", "4:05  PIN Alpha": a mark as the bar's tooltip says it. */
    static String markText(FlightMarks.Mark m) {
        return m.timeText() + "  " + (m.kind() == FlightMarks.Kind.BOOKMARK ? m.label() : m.kind().title + " " + m.label());
    }

    /** The notes' file is written by one thread, in the order the changes were made (as the link log's rows are). */
    private static final java.util.concurrent.ExecutorService NOTES_WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "flightNotes");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** The chosen flight's notes to their file, off the Swing thread. */
    private void saveNotes() {
        FlightNotes n = notes;
        if (n == null) return;
        NOTES_WRITER.execute(() -> {
            try {
                n.save();
            } catch (java.io.IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    if (isDisplayable()) Dialogs.message(this, "Can't save the notes: " + e.getMessage(), "Previous flights", JOptionPane.WARNING_MESSAGE);
                });
            }
        });
    }

    /** Waits until the notes changed so far are in their file (EXPORT and DELETE take the file along; tests look at it). */
    static void awaitSaved() {
        try {
            NOTES_WRITER.submit(() -> { }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ignored) {
        }
    }

    /** The note as typed goes into the flight's notes and their file - when it has changed. */
    void keepNote() {
        noteIdle.stop();
        FlightNotes n = notes;
        if (n == null || settingNote) return;
        String before = n.note();
        n.setNote(note.getText());
        if (!n.note().equals(before)) saveNotes();
    }

    /** BOOKMARK, or the B key: the moment showing now gets a mark and a few words - none will do - kept at once. */
    void bookmarkNow() {
        FlightData d = data;
        if (d == null || notes == null) return;
        long at = Math.max(0, position() - d.startMs());
        Object text = Dialogs.input(this, "Text (may be empty)", "Bookmark at " + FlightLibrary.durationText(at), JOptionPane.PLAIN_MESSAGE,
                null, null, "");
        if (text == null || data != d) return; // called off
        addBookmark(at, text.toString());
    }

    /** @param offsetMs from the flight's start */
    FlightNotes.Bookmark addBookmark(long offsetMs, String text) {
        FlightNotes.Bookmark b = notes.addBookmark(offsetMs, text);
        saveNotes();
        showMarks();
        return b;
    }

    /** Other words for the same moment. */
    FlightNotes.Bookmark renameBookmark(FlightNotes.Bookmark b, String text) {
        if (notes == null || !notes.removeBookmark(b)) return b;
        return addBookmark(b.offsetMs(), text);
    }

    void removeBookmark(FlightNotes.Bookmark b) {
        if (notes == null || !notes.removeBookmark(b)) return;
        saveNotes();
        showMarks();
    }

    /** A right-click on a bookmark's sign: RENAME, REMOVE. Pins and clips are changed where they live, not here. */
    private void bookmarkMenu(FlightNotes.Bookmark b, int x, int y) {
        javax.swing.JPopupMenu menu = TacticalCombo.menu();
        menu.setBackground(Theme.PANEL_2);
        javax.swing.JMenuItem rename = new javax.swing.JMenuItem("Rename...");
        rename.addActionListener(e -> {
            Object text = Dialogs.input(this, "Text (may be empty)", "Bookmark at " + FlightLibrary.durationText(b.offsetMs()),
                    JOptionPane.PLAIN_MESSAGE, null, null, b.text());
            if (text != null) renameBookmark(b, text.toString());
        });
        menu.add(rename);
        javax.swing.JMenuItem remove = new javax.swing.JMenuItem("Remove");
        remove.addActionListener(e -> removeBookmark(b));
        menu.add(remove);
        TacticalCombo.show(menu, timeline, x, y);
    }

    // ------------------------------------------------------------------ the clock

    long position() {
        if (!playing) return position;
        Run r = run;
        return r.from + Math.round((clock.getAsLong() - r.wall) * r.speed);
    }

    static String speedText(double k) {
        return (k == Math.rint(k) ? String.valueOf((int) k) : String.valueOf(k)) + "X";
    }

    double speed() {
        return speed;
    }

    /** The speed button: 1X, 2X, 4X, 0.5X and round again. */
    void nextSpeed() {
        int i = 0;
        while (i < SPEEDS.length && SPEEDS[i] != speed) i++;
        setSpeed(SPEEDS[(i + 1) % SPEEDS.length]);
    }

    /**
     * How fast the clock runs while playing. Everything shown goes by the clock - the picture, the drone on the map,
     * the figures, the bar's playhead - so all of it stays in step at any speed; the clock goes on from where it is.
     * The sound plays at 1X only.
     */
    void setSpeed(double k) {
        if (playing) run = new Run(position(), clock.getAsLong(), k);
        speed = k;
        speedButton.setText(speedText(k));
        if (playing) startAudio(position()); // on from here at 1X, off at any other speed
    }

    void togglePlay() {
        if (data == null) return;
        if (playing) {
            position = position();
            playing = false;
            if (audio != null) audio.stop();
        } else {
            if (position >= data.endMs() - 200) {
                position = data.startMs();
                if (player != null) player.seekTo(position); // the recording too: at its end it would wait there for a seek
            }
            run = new Run(position, clock.getAsLong(), speed);
            playing = true;
            startAudio(position);
        }
        play.setText(playing ? "PAUSE" : "PLAY");
    }

    void seek(long t) {
        if (data == null) return;
        t = Math.max(data.startMs(), Math.min(data.endMs(), t));
        position = t;
        if (playing) {
            run = new Run(t, clock.getAsLong(), speed);
            startAudio(t);
        }
        if (player != null) player.seekTo(t);
        tick();
    }

    /**
     * The player opened a part of the recording: its sound is that part's file's (the recording carries the sound),
     * on its own clock.
     */
    private void soundFor(Player from, FlightLibrary.Part part) {
        if (player != from) return; // another flight chosen meanwhile
        if (audio != null) audio.close();
        audio = part.video() == null ? null : new FileAudio(part.video());
        audioStartMs = part.startMs();
        if (playing) startAudio(position());
    }

    private void startAudio(long t) {
        if (audio == null || data == null) return;
        long ms = t - audioStartMs;
        if (ms >= 0 && speed == 1) audio.play(ms); // at the speed it was recorded at, or not at all
        else audio.stop();
    }

    private void stopAll() {
        playing = false;
        play.setText("PLAY");
        if (audio != null) audio.close();
        audio = null;
        Player old = player;
        player = null;
        if (old != null) {
            old.stopPlayer();
            try { // its file closed before another opens or the window goes (Windows keeps an open file)
                old.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 15 a second: the figures, the bar's playhead, the map's marker, the end. */
    void tick() {
        FlightData d = data;
        if (d == null) {
            for (JLabel l : figures) l.setText("-");
            time.setText("0:00 / 0:00");
            return;
        }
        long t = position();
        if (playing && t >= d.endMs()) {
            playing = false;
            position = d.endMs();
            t = position;
            play.setText("PLAY");
            if (audio != null) audio.stop();
        }
        FlightData.Sample s = d.at(t);
        figures[0].setText(s == null || Double.isNaN(s.altM()) ? "-" : Math.round(s.altM()) + " m");
        figures[1].setText(s == null || Double.isNaN(s.speedKmh()) ? "-" : Math.round(s.speedKmh()) + " km/h");
        figures[2].setText(s == null || Double.isNaN(s.cellVolts()) ? "-" : String.format(Locale.ROOT, "%.2f V", s.cellVolts()));
        figures[3].setText(s == null || s.homeM() < 0 ? "-" : MapView.distanceText(s.homeM()));
        figures[4].setText(s == null || s.mode() == null || s.mode().isEmpty() ? "-" : s.mode().toUpperCase(Locale.ROOT));
        double[] at = d.positionAt(t);
        if (at == null) map.setReplayMarker(Double.NaN, Double.NaN, Double.NaN);
        else map.setReplayMarker(at[0], at[1], at[2]);
        time.setText(FlightLibrary.durationText(t - d.startMs()) + " / " + FlightLibrary.durationText(d.flight.durationMs()));
        timeline.repaint();
    }

    // ------------------------------------------------------------------ EXPORT, DELETE

    private void exportFlight() {
        if (data == null) return;
        keepNote(); // the notes' file goes along: with the note as it stands
        FlightLibrary.Flight f = data.flight;
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(de.droiddrone.pc.AppPaths.exportsDir().toFile());
        chooser.setDialogTitle("Export to folder");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        Path parent = chooser.getSelectedFile().toPath();
        Thread t = new Thread(() -> { // the disk (videos can be big), off the Swing thread
            String said;
            try {
                awaitSaved();
                said = "Exported to " + FlightData.export(f, parent) + ".";
            } catch (java.io.IOException e) {
                said = "Export failed: " + e.getMessage();
            }
            String msg = said;
            SwingUtilities.invokeLater(() -> Dialogs.message(this, msg, "Export", JOptionPane.INFORMATION_MESSAGE));
        }, "flightExport");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void deleteFlight() {
        if (data == null || !FlightData.deletable(data.flight)) return;
        FlightLibrary.Flight f = data.flight;
        if (Dialogs.confirm(this, "Delete " + f.title() + "? Track, video and log go. Can't be undone.", "Delete flight",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        stopAll(); // the files open for playback first
        data = null;
        notes = null; // nothing more is written about it
        noteIdle.stop();
        awaitSaved(); // a note on its way to the file gets there first, and goes with the others
        List<Path> left = FlightData.delete(f);
        // 1.0.71: said in the app's log - a flight missing from the list later is then known to have been deleted
        de.droiddrone.pc.AppLog.line("Previous flights: " + f.title() + (f.drone() == null ? "" : " " + f.drone()) + " deleted"
                + (left.isEmpty() ? "." : " (" + left.size() + " file(s) could not go)."));
        if (!left.isEmpty()) Dialogs.message(this, "Couldn't delete " + left.get(0).getFileName() + ".", "Delete flight", JOptionPane.WARNING_MESSAGE);
        int i = list.getSelectedIndex();
        FlightLibrary.Flight next = i + 1 < model.size() ? model.get(i + 1) : i > 0 ? model.get(i - 1) : null;
        reload(next);
    }

    // ------------------------------------------------------------------ REPORT

    /** The report's picture of the track. */
    private static final int PICTURE_W = 840, PICTURE_H = 472;

    /** REPORT: asks for a file (Documents' Exports, as EXPORT; the flight's date in its name), writes it, offers its folder. */
    private void reportFlight() {
        if (data == null) return;
        Path dir = de.droiddrone.pc.AppPaths.exportsDir();
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(dir.toFile());
        chooser.setDialogTitle("Save report");
        chooser.setSelectedFile(dir.resolve(FlightReport.fileName(data.flight)).toFile());
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("HTML", "html", "htm"));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        Path chosen = chooser.getSelectedFile().toPath();
        String name = chosen.getFileName().toString().toLowerCase(Locale.ROOT);
        Path target = name.endsWith(".html") || name.endsWith(".htm") ? chosen : chosen.resolveSibling(chosen.getFileName() + ".html");
        writeReport(target, failed -> {
            if (!isDisplayable()) return;
            if (failed != null) {
                Dialogs.message(this, "Report failed: " + failed.getMessage(), "Report", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Object[] options = {"Open folder", "OK"};
            if (Dialogs.option(this, "<html>Report saved:<br>" + target.getFileName() + "<br>in " + target.toAbsolutePath().getParent() + "</html>", "Report", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE, null, options, options[1]) != 0) return;
            try {
                de.droiddrone.pc.Outside.open(target.toAbsolutePath().getParent().toFile());
            } catch (Exception ex) {
                Dialogs.message(this, "Can't open the folder: " + ex.getMessage(), "Report", JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    /**
     * The chosen flight's report to this file, off the Swing thread (REPORT is locked meanwhile): its figures, its
     * marks and the note as they stand, and the track on a map of its own.
     * @param then on the Swing thread when it is done: null, or why it failed
     */
    void writeReport(Path target, Consumer<Exception> then) {
        FlightData d = data;
        if (d == null) return;
        keepNote();
        FlightSummary figures = summary;
        FlightNotes written = notes;
        List<FlightMarks.Mark> marked = marks;
        MapView trackMap = trackMap(d);
        reporting = true;
        setButtons();
        Thread t = new Thread(() -> {
            Exception failed = null;
            try {
                FlightReport.write(target, d, figures, marked, written, trackPicture(trackMap));
            } catch (java.io.IOException | RuntimeException e) {
                failed = e;
            }
            Exception why = failed;
            SwingUtilities.invokeLater(() -> {
                reporting = false;
                setButtons();
                then.accept(why);
            });
        }, "flightReport");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * A map of its own for the report's picture, never on the screen: the whole track in view, as this window's map
     * shows it at first, whatever the pilot has since moved that one to. Null for a flight without positions.
     */
    private MapView trackMap(FlightData d) {
        List<de.droiddrone.pc.map.FlightTrack.Point> path = d.path();
        if (path.isEmpty()) return null;
        MapView shot = new MapView(cache);
        shot.setSource(map.source());
        shot.setFollow(false);
        shot.setSize(PICTURE_W, PICTURE_H);
        shot.setReplay(path);
        return shot;
    }

    /**
     * The track's picture, painted on the Swing thread and waited for on the report's: map tiles not in memory get a
     * few seconds to come from the disk or the server. Null without a map.
     */
    private BufferedImage trackPicture(MapView shot) {
        if (shot == null) return null;
        BufferedImage[] picture = new BufferedImage[1];
        boolean[] missing = new boolean[1];
        try {
            for (int i = 0; i < 20; i++) {
                SwingUtilities.invokeAndWait(() -> {
                    picture[0] = shot.picture(PICTURE_W, PICTURE_H);
                    missing[0] = shot.tilesMissing();
                });
                if (!missing[0] || (i >= 2 && !cache.loading())) break;
                Thread.sleep(150);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            return null; // the map couldn't be painted: the report goes without its picture
        }
        return picture[0];
    }

    // ------------------------------------------------------------------ the video

    /** A picture this late (ms of this PC's time) is not shown: the player goes on to the clock's place instead. */
    static final long LATE_MS = 120;
    /** Played faster than real time, about this many ms of this PC's time lie between two pictures shown (30 a second). */
    static final long SHOWN_EVERY_MS = 33;

    /** What the player does with the picture it has decoded. */
    enum Pace { WAIT, SHOW, LEAVE_OUT, FIND_AGAIN }

    /**
     * The player's pace: the clock never waits for the picture, the picture follows the clock. A picture ahead of the
     * clock waits for it; one on time is shown; one that is late (a slow PC, 4X, the clock moved on) is left out and
     * the player goes on to the clock's place - except the first picture after such a step, which is shown however
     * late, so that a PC too slow for the speed still shows pictures: fewer, not older and older ones.
     * @param aheadMs  the picture's time less the clock's, in the flight's ms (negative: late)
     * @param speed    how fast the clock runs
     * @param caughtUp the picture is the first after others were left out
     */
    static Pace pace(long aheadMs, double speed, boolean caughtUp) {
        if (aheadMs > 1500) return Pace.FIND_AGAIN; // the clock went back (a seek we missed)
        if (aheadMs > 0) return Pace.WAIT;
        return !caughtUp && -aheadMs / speed > LATE_MS ? Pace.LEAVE_OUT : Pace.SHOW;
    }

    /**
     * Decodes the recording on its own thread at the lowest priority and shows each picture when the clock reaches
     * it; a seek (or the clock going back) goes to the key frame before the place and on from there. It follows the
     * clock at any speed ({@link #pace}): faster than real time about 30 pictures a second are shown and the others
     * only decoded, and a picture that comes late is left out with those up to the clock's place - from a key frame
     * on the way when there is one, so they needn't even be decoded.
     */
    final class Player extends Thread {
        private final FlightData d;
        /** 1.0.71: the recording's parts in order (a new file whenever the picture's size changed); never empty. */
        private final List<FlightLibrary.Part> parts;
        private volatile long seekTo = Long.MIN_VALUE;
        private volatile boolean running = true;

        Player(FlightData d, List<FlightLibrary.Part> parts) {
            super("flightVideo");
            setDaemon(true);
            setPriority(Thread.MIN_PRIORITY);
            this.d = d;
            this.parts = parts;
            seekTo = d.startMs();
        }

        void seekTo(long t) {
            seekTo = t;
            interrupt();
        }

        void stopPlayer() {
            running = false;
            interrupt();
        }

        @Override
        public void run() {
            long first = seekTo;
            int index = partAt(parts, first == Long.MIN_VALUE ? d.startMs() : first);
            while (running && index >= 0) index = play(index);
        }

        /** Plays one part until the clock or a seek is in another one; that part's index, or -1 at the end. */
        private int play(int index) {
            FlightLibrary.Part part = parts.get(index);
            long partStart = part.startMs();
            Path file = part.playable(), fallback = part.video();
            ClipPlayer p;
            Path played = file;
            try {
                p = new ClipPlayer(file);
            } catch (java.io.IOException e) {
                try { // the OSD copy may be MPEG-4 (no OpenH264 on that PC): then the recording itself
                    if (fallback == null || fallback.equals(file)) throw e;
                    p = new ClipPlayer(fallback);
                    played = fallback;
                } catch (java.io.IOException e2) {
                    video.clearLater("Can't play the video");
                    return waitForAnother(index);
                }
            }
            video.setSource(rawPart(played, part) ? d.flight.drone() : null);
            javax.swing.SwingUtilities.invokeLater(() -> soundFor(this, part));
            if (seekTo == Long.MIN_VALUE) seekTo = Math.max(position(), partStart); // came here by the clock: from its place
            try {
                int w = Math.max(16, Math.min(960, p.width())), h = Math.max(16, (int) Math.round(w * (double) p.height() / Math.max(1, p.width())));
                int[] buf = new int[w * h];
                long frameAt = Long.MIN_VALUE; // the clock time of the picture decoded last
                boolean pending = false;
                boolean caughtUp = false; // the picture decoded last is the first after others were left out
                while (running) {
                    long s = seekTo;
                    if (s != Long.MIN_VALUE) {
                        int there = partAt(parts, s);
                        if (there != index) return there; // the place is in another part: that one seeks to it
                        seekTo = Long.MIN_VALUE;
                        p.seek(Math.max(0, s - partStart));
                        long ms = p.next(buf, w, h);
                        if (ms >= 0) {
                            frameAt = partStart + ms;
                            video.show(buf, w, h);
                        } else {
                            video.clearLater("");
                        }
                        pending = false;
                        caughtUp = false;
                        continue;
                    }
                    long now = position();
                    int there = partAt(parts, now);
                    if (there != index) return there; // the clock ran on into the next part (or back)
                    if (!playing || now < partStart) {
                        sleepQuietly(30);
                        continue;
                    }
                    double k = speed;
                    if (!pending) {
                        long ms = p.next(buf, w, h);
                        if (ms < 0) { // the end of this part
                            sleepQuietly(50);
                            continue;
                        }
                        frameAt = partStart + ms;
                        pending = true;
                    }
                    long wait = frameAt - position();
                    switch (pace(wait, k, caughtUp)) {
                        case FIND_AGAIN -> seekTo = position(); // the clock went back (a seek we missed): find the place again
                        case WAIT -> sleepQuietly(Math.min((long) Math.ceil(wait / k), 40));
                        case LEAVE_OUT -> { // behind the clock: to its place, not every picture on the way
                            p.catchUp(position() - partStart);
                            pending = false;
                            caughtUp = true;
                        }
                        case SHOW -> {
                            video.show(buf, w, h);
                            pending = false;
                            caughtUp = false;
                            // faster than real time: the pictures until the next one to show are decoded only
                            if (k > 1) p.skipTo(frameAt - partStart + Math.round(SHOWN_EVERY_MS * k));
                        }
                    }
                }
            } catch (RuntimeException e) {
                // a picture that can't be converted (an unknown pixel format, a decoder that gave none): said, and this
                // part is let be - the thread ended quietly before, the picture standing while sound and clock went on
                // (a reader of this code, 2026-11-20)
                de.droiddrone.pc.AppLog.line("Previous flights: the video can't be played on: " + e);
                video.clearLater("Can't play the video");
                p.close();
                return waitForAnother(index);
            } finally {
                p.close();
            }
            return -1;
        }

        /** A part that can't be played: nothing to show until the clock or a seek is in another. */
        private int waitForAnother(int index) {
            while (running) {
                long s = seekTo;
                int there = partAt(parts, s != Long.MIN_VALUE ? s : position());
                if (there != index) return there;
                if (s != Long.MIN_VALUE) seekTo = Long.MIN_VALUE;
                sleepQuietly(100);
            }
            return -1;
        }

        private void sleepQuietly(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
                // a seek or the end: the loop looks again
            }
        }
    }

    /** Which part of a recording a moment is in: the last one that had begun by then (the first, before any). */
    static int partAt(List<FlightLibrary.Part> parts, long t) {
        int at = 0;
        for (int i = 1; i < parts.size(); i++) if (parts.get(i).startMs() <= t) at = i;
        return at;
    }

    /** The file played is this part's own recording (not its copy with the OSD drawn in, already turned). */
    static boolean rawPart(Path played, FlightLibrary.Part part) {
        return played != null && part != null && part.video() != null && played.equals(part.video())
                && (part.osdVideo() == null || !played.equals(part.osdVideo()));
    }

    /** The file played is the camera's own recording (not the copy with the OSD drawn in, already turned). */
    static boolean rawRecording(Path played, FlightLibrary.Flight f) {
        return played != null && f != null && f.video() != null && played.equals(f.video())
                && (f.osdVideo() == null || !played.equals(f.osdVideo()));
    }

    /** The picture, letterboxed; a word when there is none; the raw recording turned as its drone's picture is set. */
    static final class VideoPane extends JComponent {
        private BufferedImage image;
        private String note = "";
        /** The pictures shown so far. */
        private int shown;
        volatile CameraTurn turn;
        /** The drone whose camera recording plays, or null (the OSD copy, already turned). */
        private volatile String source;

        void setSource(String drone) {
            source = drone;
            repaint();
        }

        boolean upsideDown() {
            CameraTurn t = turn;
            String s = source;
            return t != null && s != null && t.upsideDown(s);
        }

        VideoPane() {
            setOpaque(true);
            setBackground(Color.BLACK);
            setPreferredSize(new Dimension(480, 300));
        }

        /** From the player's thread: copies the picture and repaints. */
        void show(int[] rgb, int w, int h) {
            synchronized (this) {
                if (image == null || image.getWidth() != w || image.getHeight() != h) image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                int[] dst = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                System.arraycopy(rgb, 0, dst, 0, Math.min(dst.length, rgb.length));
                note = "";
                shown++;
                notifyAll();
            }
            repaint();
        }

        synchronized int shown() {
            return shown;
        }

        /** For tests: waits until this many pictures have been shown (the player's thread shows them). False: not within ms. */
        synchronized boolean awaitShown(int pictures, long ms) throws InterruptedException {
            long until = System.currentTimeMillis() + ms;
            for (long left; shown < pictures && (left = until - System.currentTimeMillis()) > 0; ) wait(left);
            return shown >= pictures;
        }

        void clear(String text) {
            synchronized (this) {
                image = null;
                note = text;
            }
            repaint();
        }

        void clearLater(String text) {
            SwingUtilities.invokeLater(() -> clear(text));
        }

        synchronized boolean hasPicture() {
            return image != null;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());
                synchronized (this) {
                    if (image != null) {
                        double k = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
                        int w = (int) Math.round(image.getWidth() * k), h = (int) Math.round(image.getHeight() * k);
                        boolean turned = upsideDown();
                        CameraTurn.draw(g, image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, turned, turned);
                        return;
                    }
                }
                if (!note.isEmpty()) {
                    Theme.smooth(g);
                    g.setFont(Theme.headingFont(13f));
                    g.setColor(Theme.MUTED);
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(note.toUpperCase(Locale.ROOT), (getWidth() - fm.stringWidth(note.toUpperCase(Locale.ROOT))) / 2, getHeight() / 2);
                }
            } finally {
                g.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ the bar

    /**
     * One bar for the whole flight: altitude (white) and battery per cell (green) as lines, the link under them - ping
     * green / yellow / red, a red mark where the link was down or Betaflight failed safe, yellow for RXLOSS - and the
     * playhead. Click or drag to move there. Over the lines a lane of signs for the flight's marks: a white ribbon for
     * a bookmark, a yellow diamond (the map's pin) for a pin, a teal dot (overwatch's colour) for an overwatch clip.
     * Pointing at a sign says when and what; a click on it moves there; a right-click on a bookmark's renames or
     * removes it. Only the sign itself takes the click - beside it the bar is for moving, as ever.
     */
    final class Timeline extends JComponent {
        Consumer<Long> onSeek;
        static final int LINK_H = 16;
        /** The marks' lane: under the bar's legend, over the lines. */
        static final int MARKS_TOP = 14, MARKS_H = 12;
        /** How far from a sign's middle the mouse still is on it. */
        static final int MARK_REACH = 6;

        Timeline() {
            setOpaque(true);
            setBackground(Theme.PANEL);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            javax.swing.ToolTipManager.sharedInstance().registerComponent(this); // the marks' tooltips
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    FlightMarks.Mark mark = markAt(e.getX(), e.getY());
                    FlightData d = data;
                    if (mark == null || d == null) {
                        seekAt(e.getX());
                    } else if (!SwingUtilities.isRightMouseButton(e)) {
                        if (onSeek != null) onSeek.accept(d.startMs() + mark.offsetMs());
                    } else if (mark.bookmark() != null) {
                        bookmarkMenu(mark.bookmark(), e.getX(), e.getY());
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    seekAt(e.getX());
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private void seekAt(int x) {
            FlightData d = data;
            if (d == null || onSeek == null) return;
            double k = Math.max(0, Math.min(1, (x - 8) / (double) Math.max(1, getWidth() - 16)));
            onSeek.accept(d.startMs() + Math.round(k * (d.endMs() - d.startMs())));
        }

        private double xOf(long t, FlightData d, int w) {
            long span = Math.max(1, d.endMs() - d.startMs());
            return 8 + (w - 16) * (t - d.startMs()) / (double) span;
        }

        /** The middle of a mark's sign. */
        java.awt.Point markCentre(FlightMarks.Mark m) {
            FlightData d = data;
            if (d == null) return null;
            long t = Math.max(d.startMs(), Math.min(d.endMs(), d.startMs() + m.offsetMs()));
            return new java.awt.Point((int) Math.round(xOf(t, d, getWidth())), MARKS_TOP + MARKS_H / 2);
        }

        /** The mark whose sign is under the mouse - the nearest of them - or null. */
        FlightMarks.Mark markAt(int x, int y) {
            if (y < MARKS_TOP - 1 || y > MARKS_TOP + MARKS_H) return null;
            FlightMarks.Mark best = null;
            int bestD = MARK_REACH + 1;
            for (FlightMarks.Mark m : marks) {
                java.awt.Point c = markCentre(m);
                if (c != null && Math.abs(x - c.x) < bestD) {
                    bestD = Math.abs(x - c.x);
                    best = m;
                }
            }
            return best;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            FlightMarks.Mark m = markAt(e.getX(), e.getY());
            return m == null ? null : markText(m);
        }

        static Color markColor(FlightMarks.Kind kind) {
            return switch (kind) {
                case BOOKMARK -> Theme.TEXT;
                case PIN -> Theme.WARN;
                case CLIP -> MapView.OVERWATCH_COLOR;
            };
        }

        /** A mark's sign round (x, y): a ribbon, a diamond, a dot. */
        private static Shape markShape(FlightMarks.Kind kind, double x, double y) {
            Path2D p = new Path2D.Double();
            switch (kind) {
                case BOOKMARK -> {
                    p.moveTo(x - 4, y - 5);
                    p.lineTo(x + 4, y - 5);
                    p.lineTo(x + 4, y + 5);
                    p.lineTo(x, y + 2);
                    p.lineTo(x - 4, y + 5);
                }
                case PIN -> {
                    p.moveTo(x, y - 5.5);
                    p.lineTo(x + 5, y);
                    p.lineTo(x, y + 5.5);
                    p.lineTo(x - 5, y);
                }
                case CLIP -> {
                    return new Ellipse2D.Double(x - 4.5, y - 4.5, 9, 9);
                }
            }
            p.closePath();
            return p;
        }

        private void paintMarks(Graphics2D g, int bottom) {
            for (FlightMarks.Mark m : marks) {
                java.awt.Point c = markCentre(m);
                if (c == null) continue;
                Color color = markColor(m.kind());
                g.setStroke(new BasicStroke(1f));
                g.setColor(Theme.alpha(color, 70)); // down through the lines: where on them it is
                g.drawLine(c.x, c.y + MARKS_H / 2, c.x, bottom);
                Shape sign = markShape(m.kind(), c.x, c.y);
                g.setColor(color);
                g.fill(sign);
                g.setColor(Theme.BG);
                g.draw(sign);
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight();
                g.setColor(Theme.PANEL);
                g.fillRect(0, 0, w, h);
                FlightData d = data;
                int graphTop = MARKS_TOP + MARKS_H, graphBottom = h - LINK_H - 8, linkTop = h - LINK_H - 2;
                g.setColor(new Color(0x101010));
                g.fillRect(8, graphTop, w - 16, graphBottom - graphTop);
                g.fillRect(8, linkTop, w - 16, LINK_H);
                g.setFont(Theme.headingFont(9.5f));
                g.setColor(new Color(0xd8d8d0));
                g.drawString("ALT", 8, 10);
                g.setColor(Theme.OK);
                g.drawString("BATT", 38, 10);
                g.setColor(Theme.MUTED);
                g.drawString("LINK", 76, 10);
                if (d == null) return;
                // altitude and battery
                List<FlightData.Sample> tel = d.telemetry;
                double maxAlt = 10;
                for (FlightData.Sample s : tel) if (!Double.isNaN(s.altM())) maxAlt = Math.max(maxAlt, s.altM());
                if (tel.isEmpty()) for (de.droiddrone.pc.map.FlightTrack.Point p : d.track) maxAlt = Math.max(maxAlt, p.altM());
                Path2D alt = new Path2D.Double(), batt = new Path2D.Double();
                boolean altStarted = false, battStarted = false;
                int gh = graphBottom - graphTop - 4;
                if (!tel.isEmpty()) {
                    for (FlightData.Sample s : tel) {
                        double x = xOf(s.timeMs(), d, w);
                        if (!Double.isNaN(s.altM())) {
                            double y = graphBottom - 2 - gh * Math.max(0, s.altM()) / maxAlt;
                            if (altStarted) alt.lineTo(x, y);
                            else alt.moveTo(x, y);
                            altStarted = true;
                        }
                        if (!Double.isNaN(s.cellVolts()) && s.cellVolts() > 2) {
                            double v = Math.max(0, Math.min(1, (s.cellVolts() - 2.8) / (4.35 - 2.8))); // from the pilot's "empty"
                            double y = graphBottom - 2 - gh * v;
                            if (battStarted) batt.lineTo(x, y);
                            else batt.moveTo(x, y);
                            battStarted = true;
                        }
                    }
                } else {
                    for (de.droiddrone.pc.map.FlightTrack.Point p : d.track) {
                        double x = xOf(p.timeMs(), d, w), y = graphBottom - 2 - gh * Math.max(0, p.altM()) / maxAlt;
                        if (altStarted) alt.lineTo(x, y);
                        else alt.moveTo(x, y);
                        altStarted = true;
                    }
                }
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (battStarted) {
                    g.setColor(Theme.OK);
                    g.draw(batt);
                }
                if (altStarted) {
                    g.setColor(new Color(0xd8d8d0));
                    g.draw(alt);
                }
                g.setFont(Theme.monoFont(Font.PLAIN, 10f));
                g.setColor(Theme.MUTED);
                String top = Math.round(maxAlt) + " m";
                g.drawString(top, w - 10 - g.getFontMetrics().stringWidth(top), graphTop + 11);
                // the link
                if (d.link.isEmpty()) {
                    g.setColor(Theme.MUTED);
                    g.setFont(Theme.headingFont(9.5f));
                    g.drawString("NO LINK LOG", 14, linkTop + LINK_H - 4);
                } else {
                    for (int i = 0; i < d.link.size(); i++) {
                        FlightData.LinkSample s = d.link.get(i);
                        if (s.timeMs() < d.startMs() || s.timeMs() > d.endMs()) continue;
                        double x0 = xOf(s.timeMs(), d, w);
                        double x1 = i + 1 < d.link.size() ? xOf(Math.min(d.endMs(), d.link.get(i + 1).timeMs()), d, w) : x0 + 1;
                        int bw = Math.max(1, (int) Math.ceil(x1 - x0));
                        Color c;
                        if (s.down() || s.failsafe()) c = Theme.ACCENT;
                        else if (s.rxloss()) c = Theme.WARN;
                        else if (s.pingMs() < 0) c = Theme.LINE;
                        else c = s.pingMs() < 120 ? Theme.OK : s.pingMs() < 300 ? Theme.WARN : Theme.BAD;
                        g.setColor(c);
                        int bh = s.down() || s.failsafe() ? LINK_H : Math.max(3, Math.min(LINK_H, s.pingMs() < 0 ? 3 : 3 + s.pingMs() / 40));
                        g.fillRect((int) x0, linkTop + LINK_H - bh, bw, bh);
                    }
                }
                paintMarks(g, linkTop + LINK_H);
                // the playhead
                double x = xOf(position(), d, w);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawLine((int) x, MARKS_TOP - 2, (int) x, linkTop + LINK_H);
                Path2D head = new Path2D.Double();
                head.moveTo(x - 5, MARKS_TOP - 7);
                head.lineTo(x + 5, MARKS_TOP - 7);
                head.lineTo(x, MARKS_TOP - 1);
                head.closePath();
                g.setColor(Theme.ACCENT);
                g.fill(head);
            } finally {
                g.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ a row in the list

    private static final class FlightCell extends JComponent implements ListCellRenderer<FlightLibrary.Flight> {
        private FlightLibrary.Flight f;
        private boolean selected;

        @Override
        public java.awt.Component getListCellRendererComponent(JList<? extends FlightLibrary.Flight> list, FlightLibrary.Flight value, int index,
                                                               boolean isSelected, boolean cellHasFocus) {
            f = value;
            selected = isSelected;
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(220, 50);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight();
                g.setColor(selected ? new Color(0x221012) : Theme.PANEL);
                g.fillRect(0, 0, w, h);
                if (selected) {
                    g.setColor(Theme.ACCENT);
                    g.fillRect(0, 0, 3, h);
                }
                g.setColor(new Color(0x1d1d1a));
                g.drawLine(0, h - 1, w, h - 1);
                if (f == null) return;
                g.setFont(Theme.font(Font.BOLD, 13f));
                g.setColor(Theme.TEXT);
                g.drawString(f.title(), 12, 20);
                g.setFont(Theme.headingFont(10.5f));
                g.setColor(selected ? Theme.ACCENT : Theme.MUTED);
                String drone = f.drone() == null ? "-" : f.drone();
                g.drawString(drone, 12, 38);
                String ended = f.endMark(); // 1.0.71: a flight that did not end with a DISCONNECT says how
                if (ended != null) {
                    int x = 12 + g.getFontMetrics().stringWidth(drone) + 8;
                    g.setColor(Theme.WARN);
                    g.drawString(ended, x, 38);
                }
                g.setFont(Theme.monoFont(Font.PLAIN, 11.5f));
                g.setColor(Theme.MUTED);
                String right = FlightLibrary.durationText(f.durationMs()) + "  " + (Double.isNaN(f.distanceM()) ? "-" : MapView.distanceText(f.distanceM()));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(right, w - 10 - fm.stringWidth(right), 38);
                // a mark for what it has: video
                if (f.video() != null) {
                    int x = w - 22, y = 9;
                    g.setColor(selected ? Theme.ACCENT : Theme.MUTED);
                    g.drawRect(x, y, 11, 8);
                    g.fillPolygon(new int[]{x + 12, x + 15, x + 15}, new int[]{y + 4, y + 1, y + 7}, 3);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
