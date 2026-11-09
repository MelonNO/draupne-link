package de.droiddrone.pc.gui;

import de.droiddrone.pc.audio.FileAudio;
import de.droiddrone.pc.flights.FlightData;
import de.droiddrone.pc.flights.FlightLibrary;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.map.TileSource;
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
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
 */
final class PreviousFlightsDialog extends JDialog {
    private static PreviousFlightsDialog open;

    private final Path tracksDir, videosDir, logsDir;
    private final Consumer<Path> onMap;
    private final DefaultListModel<FlightLibrary.Flight> model = new DefaultListModel<>();
    final JList<FlightLibrary.Flight> list = new JList<>(model);
    private final JLabel listNote = Theme.label("Reading flights...", Theme.font(Font.PLAIN, 12.5f), Theme.MUTED);
    final VideoPane video = new VideoPane();
    final MapView map;
    final Timeline timeline = new Timeline();
    private final JLabel[] figures = new JLabel[5];
    final JButton play = Theme.primaryButton("Play");
    private final JButton onMapButton = Theme.secondaryButton("On map");
    private final JButton export = Theme.secondaryButton("Export");
    private final JButton delete = Theme.secondaryButton("Delete");
    private final JLabel time = Theme.label("0:00 / 0:00", Theme.monoFont(Font.BOLD, 12.5f), Theme.TEXT);
    private final Timer tick = new Timer(66, e -> tick());

    FlightData data;
    // the clock: epoch ms of the flight; while playing it runs from playFrom at playWall
    private volatile long position;
    private volatile boolean playing;
    private long playFrom, playWall;
    private Player player;
    private FileAudio audio;

    /** The tour's quick look: the flight list, while the window is open; null otherwise. */
    static javax.swing.JComponent tourList() {
        PreviousFlightsDialog d = open;
        return d != null && d.isDisplayable() ? d.list : null;
    }

    /** The timeline, lit with the list. */
    static javax.swing.JComponent tourTimeline() {
        PreviousFlightsDialog d = open;
        return d != null && d.isDisplayable() ? d.timeline : null;
    }

    /** The tour moves on: the window closes (its playback stops with it). */
    static void tourClose() {
        PreviousFlightsDialog d = open;
        if (d != null && d.isDisplayable()) d.dispose();
    }

    /** Opens it (in front if open already). */
    static void show(Window owner, TileCache cache, TileSource source, Consumer<Path> onMap) {
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
        open = new PreviousFlightsDialog(owner, cache, source, onMap, tracks, videos, logs);
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
        super(owner, "Previous flights", ModalityType.MODELESS);
        this.tracksDir = tracksDir;
        this.videosDir = videosDir;
        this.logsDir = logsDir;
        this.onMap = onMap;
        if (owner != null) setIconImages(owner.getIconImages());
        map = new MapView(cache);
        if (source != null) map.setSource(source);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        setContentPane(root);

        // left: the flights
        JPanel left = Theme.card(new BorderLayout(0, 8));
        left.add(Theme.sectionTitle("Previous flights"), BorderLayout.NORTH);
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
        JPanel leftFoot = new JPanel(new BorderLayout());
        leftFoot.setOpaque(false);
        leftFoot.add(listNote, BorderLayout.NORTH);
        JButton folder = Theme.secondaryButton("Folder");
        folder.setToolTipText("Open the tracks folder");
        folder.addActionListener(e -> {
            try {
                if (this.tracksDir != null) java.awt.Desktop.getDesktop().open(this.tracksDir.toFile());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Can't open the folder: " + ex.getMessage(), "Previous flights", JOptionPane.WARNING_MESSAGE);
            }
        });
        leftFoot.add(folder, BorderLayout.SOUTH);
        left.add(leftFoot, BorderLayout.SOUTH);
        root.add(left, BorderLayout.WEST);

        // right: video and map, the figures, the bar, the buttons
        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);
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
        timeline.setPreferredSize(new Dimension(600, 92));
        bottom.add(timeline, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        JPanel playRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        playRow.setOpaque(false);
        play.addActionListener(e -> togglePlay());
        playRow.add(play);
        playRow.add(time);
        buttons.add(playRow, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        onMapButton.setToolTipText("Draw it on the map window");
        onMapButton.addActionListener(e -> {
            if (data != null && data.flight.track() != null && this.onMap != null) this.onMap.accept(data.flight.track());
        });
        export.setToolTipText("Copy its track, video and log to a folder");
        export.addActionListener(e -> exportFlight());
        delete.setToolTipText("Delete its track, video and log");
        delete.addActionListener(e -> deleteFlight());
        actions.add(onMapButton);
        actions.add(export);
        actions.add(delete);
        buttons.add(actions, BorderLayout.EAST);
        bottom.add(buttons, BorderLayout.SOUTH);
        right.add(bottom, BorderLayout.SOUTH);
        root.add(right, BorderLayout.CENTER);

        timeline.onSeek = this::seek;
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("SPACE"), "play");
        root.getActionMap().put("play", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                togglePlay();
            }
        });
        setButtons();
        pack();
        ScreenFit.apply(this, 900, 600, 1280, 760);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                stopAll();
            }
        });
        tick.start();
        reload(null);
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
        t.start();
    }

    void showFlights(List<FlightLibrary.Flight> flights, FlightLibrary.Flight keep) {
        model.clear();
        for (FlightLibrary.Flight f : flights) model.addElement(f);
        listNote.setText("No flights yet.");
        listNote.setVisible(flights.isEmpty());
        if (flights.isEmpty()) {
            choose(null);
            return;
        }
        int at = keep == null ? 0 : Math.max(0, flights.indexOf(keep));
        list.setSelectedIndex(at);
    }

    /** The chosen flight: read (off the Swing thread), then ready to play from its start. */
    private void choose(FlightLibrary.Flight f) {
        stopAll();
        data = null;
        video.clear(f == null ? "" : "Loading...");
        map.setReplay(null);
        timeline.repaint();
        setButtons();
        if (f == null) return;
        Thread t = new Thread(() -> {
            FlightData d = FlightData.load(f);
            SwingUtilities.invokeLater(() -> {
                if (list.getSelectedValue() != f) return; // another chosen meanwhile
                loaded(d);
            });
        }, "flightLoad");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    void loaded(FlightData d) {
        data = d;
        position = d.startMs();
        map.setReplay(d.path());
        Path file = d.flight.playable();
        if (file != null) {
            player = new Player(d, file, d.flight.video());
            player.start();
            if (d.flight.video() != null) audio = new FileAudio(d.flight.video()); // the recording carries the sound
        } else {
            video.clear("No video");
        }
        setButtons();
        tick();
    }

    private void setButtons() {
        boolean have = data != null;
        play.setEnabled(have);
        export.setEnabled(have);
        delete.setEnabled(have);
        onMapButton.setEnabled(have && data.flight.track() != null && onMap != null);
    }

    // ------------------------------------------------------------------ the clock

    long position() {
        if (!playing) return position;
        return playFrom + (System.currentTimeMillis() - playWall);
    }

    void togglePlay() {
        if (data == null) return;
        if (playing) {
            position = position();
            playing = false;
            if (audio != null) audio.stop();
        } else {
            if (position >= data.endMs() - 200) position = data.startMs();
            playFrom = position;
            playWall = System.currentTimeMillis();
            playing = true;
            startAudio(position);
        }
        play.setText(playing ? "Pause" : "Play");
    }

    void seek(long t) {
        if (data == null) return;
        t = Math.max(data.startMs(), Math.min(data.endMs(), t));
        position = t;
        if (playing) {
            playFrom = t;
            playWall = System.currentTimeMillis();
            startAudio(t);
        }
        if (player != null) player.seekTo(t);
        tick();
    }

    private void startAudio(long t) {
        if (audio == null || data == null) return;
        long ms = t - data.videoStartMs;
        if (ms >= 0) audio.play(ms);
        else audio.stop();
    }

    private void stopAll() {
        playing = false;
        play.setText("Play");
        if (audio != null) audio.close();
        audio = null;
        if (player != null) player.stopPlayer();
        player = null;
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
            play.setText("Play");
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
        FlightLibrary.Flight f = data.flight;
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(Path.of(System.getProperty("user.home"), "Documents", "DRAUPNE Link", "Exports").toFile());
        chooser.setDialogTitle("Export to folder");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        Path parent = chooser.getSelectedFile().toPath();
        Thread t = new Thread(() -> { // the disk (videos can be big), off the Swing thread
            String said;
            try {
                said = "Exported to " + FlightData.export(f, parent) + ".";
            } catch (java.io.IOException e) {
                said = "Export failed: " + e.getMessage();
            }
            String msg = said;
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, msg, "Export", JOptionPane.INFORMATION_MESSAGE));
        }, "flightExport");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void deleteFlight() {
        if (data == null) return;
        FlightLibrary.Flight f = data.flight;
        if (JOptionPane.showConfirmDialog(this, "Delete " + f.title() + "? Track, video and log go. Can't be undone.", "Delete flight",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        stopAll(); // the files open for playback first
        data = null;
        List<Path> left = FlightData.delete(f);
        if (!left.isEmpty()) JOptionPane.showMessageDialog(this, "Couldn't delete " + left.get(0).getFileName() + ".", "Delete flight", JOptionPane.WARNING_MESSAGE);
        int i = list.getSelectedIndex();
        FlightLibrary.Flight next = i + 1 < model.size() ? model.get(i + 1) : i > 0 ? model.get(i - 1) : null;
        reload(next);
    }

    // ------------------------------------------------------------------ the video

    /**
     * Decodes the recording on its own thread at the lowest priority and shows each picture when the clock reaches
     * it; a seek (or the clock jumping) goes to the key frame before the place and on from there.
     */
    final class Player extends Thread {
        private final Path file;
        private final FlightData d;
        private final Path fallback;
        private volatile long seekTo = Long.MIN_VALUE;
        private volatile boolean running = true;

        Player(FlightData d, Path file, Path fallback) {
            super("flightVideo");
            setDaemon(true);
            setPriority(Thread.MIN_PRIORITY);
            this.d = d;
            this.file = file;
            this.fallback = fallback;
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
                    return;
                }
            }
            video.setSource(rawRecording(played, d.flight) ? d.flight.drone() : null);
            try {
                int w = Math.max(16, Math.min(960, p.width())), h = Math.max(16, (int) Math.round(w * (double) p.height() / Math.max(1, p.width())));
                int[] buf = new int[w * h];
                long frameAt = Long.MIN_VALUE; // the clock time of the picture decoded last
                boolean pending = false;
                while (running) {
                    long s = seekTo;
                    if (s != Long.MIN_VALUE) {
                        seekTo = Long.MIN_VALUE;
                        p.seek(Math.max(0, s - d.videoStartMs));
                        long ms = p.next(buf, w, h);
                        if (ms >= 0) {
                            frameAt = d.videoStartMs + ms;
                            video.show(buf, w, h);
                        } else {
                            video.clearLater("");
                        }
                        pending = false;
                        continue;
                    }
                    long now = position();
                    if (!playing || now < d.videoStartMs) {
                        sleepQuietly(30);
                        continue;
                    }
                    if (!pending) {
                        long ms = p.next(buf, w, h);
                        if (ms < 0) { // the end of the recording
                            sleepQuietly(50);
                            continue;
                        }
                        frameAt = d.videoStartMs + ms;
                        pending = true;
                    }
                    long wait = frameAt - position();
                    if (wait > 1500) { // the clock went back (a seek we missed): find the place again
                        seekTo = position();
                        continue;
                    }
                    if (wait > 0) {
                        sleepQuietly(Math.min(wait, 40));
                        continue;
                    }
                    if (wait < -1500) { // far behind (a jump forward): to the place, not every picture on the way
                        seekTo = position();
                        continue;
                    }
                    video.show(buf, w, h);
                    pending = false;
                }
            } finally {
                p.close();
            }
        }

        private void sleepQuietly(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
                // a seek or the end: the loop looks again
            }
        }
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
            }
            repaint();
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
     * playhead. Click or drag to move there.
     */
    final class Timeline extends JComponent {
        Consumer<Long> onSeek;
        static final int LINK_H = 16;

        Timeline() {
            setOpaque(true);
            setBackground(Theme.PANEL);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    seekAt(e.getX());
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

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
            try {
                int w = getWidth(), h = getHeight();
                g.setColor(Theme.PANEL);
                g.fillRect(0, 0, w, h);
                FlightData d = data;
                int graphTop = 14, graphBottom = h - LINK_H - 8, linkTop = h - LINK_H - 2;
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
                            double v = Math.max(0, Math.min(1, (s.cellVolts() - 3.3) / (4.35 - 3.3)));
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
                // the playhead
                double x = xOf(position(), d, w);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawLine((int) x, graphTop - 2, (int) x, linkTop + LINK_H);
                Path2D head = new Path2D.Double();
                head.moveTo(x - 5, graphTop - 7);
                head.lineTo(x + 5, graphTop - 7);
                head.lineTo(x, graphTop - 1);
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
