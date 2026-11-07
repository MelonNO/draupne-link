package de.droiddrone.pc.gui;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.overwatch.AlertCard;
import de.droiddrone.pc.overwatch.ClipIndex;
import de.droiddrone.pc.overwatch.ClipPlayer;
import de.droiddrone.pc.overwatch.OverwatchHub;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * OVERWATCH in the map window (1.0.47), beside the map: an alert card per drone that saw something move - the live
 * preview (the pictures before the alert until it comes), the time, CONNECT and DISMISS - that keeps its last picture
 * 1.5 min after the motion ended and then shrinks to a badge; and the clip list (MAP TOOLS > CLIPS, or right-click a
 * drone): per drone, each event's picture, time, length and place, played here (FFmpeg, its own thread at the lowest
 * priority) with the camera's view as it was on the map. REMOVE only takes a clip off the list; the files stay in
 * Documents\DRAUPNE Link\Overwatch.
 */
final class OverwatchPanel extends JPanel {
    static final int WIDTH = 340;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss", Locale.ROOT);

    private final MapView map;
    private final ClipIndex index;
    private final CameraFootprint.Terrain terrain;
    private final Consumer<String> onConnect, onDismiss, onCloseBadge;
    private final Supplier<Coordinates.Format> format;
    private final JPanel cards = new JPanel();
    private final Map<String, Card> cardByDrone = new LinkedHashMap<>();
    private List<OverwatchHub.View> views = List.of();
    /** The camera's view per drone and pose: worked out once (the drone doesn't move), again every 10 s for the terrain. */
    private final Map<String, Object[]> viewCache = new HashMap<>();

    // ---- the clip list and the player
    private final JPanel library = Theme.card(new BorderLayout(0, 8));
    private final JComboBox<String> droneCombo = new JComboBox<>();
    private final JLabel folderSize = Theme.label(" ", Theme.font(Font.PLAIN, 11.5f), Theme.MUTED);
    private final DefaultListModel<ClipIndex.Clip> clipModel = new DefaultListModel<>();
    private final JList<ClipIndex.Clip> clipList = new JList<>(clipModel);
    private final Picture player = new Picture();
    private final JButton playButton = Theme.primaryButton("Play");
    private final JSlider seek = new JSlider(0, 1000, 0);
    private final JLabel playTime = Theme.label("-", Theme.monoFont(Font.PLAIN, 11.5f), Theme.MUTED);
    private final Map<Path, ImageIcon> thumbs = new HashMap<>();
    private final ExecutorService playerThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clipPlayer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY); // never before the live video
        return t;
    });
    private volatile int playGen;
    private volatile boolean paused;
    private volatile long seekTo = -1;
    private volatile long durationMs;
    private boolean settingSeek, fillingCombo;
    private ClipIndex.Clip playing;
    private MapView.OverwatchMark replay;

    OverwatchPanel(MapView map, ClipIndex index, CameraFootprint.Terrain terrain, Supplier<Coordinates.Format> format,
                   Consumer<String> onConnect, Consumer<String> onDismiss, Consumer<String> onCloseBadge) {
        super(new BorderLayout(0, 10));
        this.map = map;
        this.index = index;
        this.terrain = terrain;
        this.format = format;
        this.onConnect = onConnect;
        this.onDismiss = onDismiss;
        this.onCloseBadge = onCloseBadge;
        setOpaque(false);
        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        cards.setOpaque(false);
        add(cards, BorderLayout.NORTH);
        buildLibrary();
        library.setVisible(false);
        add(library, BorderLayout.CENTER);
        setVisible(false);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WIDTH, super.getPreferredSize().height);
    }

    // ------------------------------------------------------------------ the alerts

    /** The hub's latest (every refresh, Swing thread): cards in, changed or out; the map's marks. */
    void setViews(List<OverwatchHub.View> list) {
        views = list;
        boolean changed = false;
        List<String> seen = new ArrayList<>();
        for (OverwatchHub.View v : list) {
            if (v.shown() == AlertCard.Shown.HIDDEN) continue;
            seen.add(v.drone());
            Card c = cardByDrone.get(v.drone());
            if (c == null) {
                c = new Card(v.drone());
                cardByDrone.put(v.drone(), c);
                cards.add(c, 0); // the newest on top
                changed = true;
            }
            changed |= c.show(v);
        }
        for (String d : new ArrayList<>(cardByDrone.keySet())) {
            if (seen.contains(d)) continue;
            cards.remove(cardByDrone.remove(d));
            changed = true;
        }
        if (changed) {
            syncVisible();
            revalidate();
            repaint();
        }
        map.setOverwatchMarks(marks());
        if (panTo != null && pan(panTo)) panTo = null;
    }

    /** A new alert's drone, until its view is here to pan to. */
    private String panTo;

    /** Whether an alert is on (the map's pulse wants repaints). */
    boolean pulsing() {
        for (OverwatchHub.View v : views) if (v.shown() == AlertCard.Shown.CARD && !v.ended()) return true;
        return false;
    }

    /** A new alert: the map pans to the drone when it is off the screen (at the next refresh, if it isn't known yet). */
    void news(String drone) {
        if (!pan(drone)) panTo = drone;
    }

    private boolean pan(String drone) {
        for (OverwatchHub.View v : views) {
            if (!v.drone().equals(drone)) continue;
            double[] at = position(v);
            if (at != null && !map.shows(at[0], at[1], 40)) map.panTo(at[0], at[1]);
            return true;
        }
        return false;
    }

    private static double[] position(OverwatchHub.View v) {
        if (v.pose() != null) return new double[]{v.pose().lat(), v.pose().lon()};
        OverwatchProtocol.Alert a = v.alert();
        return a != null && a.hasPosition() ? new double[]{a.latE7 / 1e7, a.lonE7 / 1e7} : null;
    }

    private List<MapView.OverwatchMark> marks() {
        List<MapView.OverwatchMark> out = new ArrayList<>();
        for (OverwatchHub.View v : views) {
            double[] at = position(v);
            if (at == null || v.shown() == AlertCard.Shown.HIDDEN) continue;
            boolean alert = v.shown() == AlertCard.Shown.CARD && !v.ended();
            OverwatchProtocol.Alert a = v.alert();
            double bearing = v.pose() != null && a != null ? (v.pose().headingDeg() + a.angleDeg + 360) % 360 : Double.NaN;
            out.add(new MapView.OverwatchMark(v.drone(), at[0], at[1], cone(v.drone(), v.pose()), bearing, alert, false,
                    "OVERWATCH " + v.drone() + (alert ? " - MOTION" : "")));
        }
        if (replay != null) out.add(replay);
        return out;
    }

    /** The camera's view from a drone sitting on the ground (its height 2 m over the terrain where that isn't known). */
    private CameraFootprint.View cone(String key, CameraFootprint.Pose p) {
        if (p == null) return null;
        Object[] cached = viewCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached[0].equals(p) && now - (long) cached[2] < 10_000) return (CameraFootprint.View) cached[1];
        if (terrain != null) terrain.around(p.lat(), p.lon());
        CameraFootprint.View v = CameraFootprint.compute(p, terrain, p.altM() - 2);
        viewCache.put(key, new Object[]{p, v, now});
        return v;
    }

    private void syncVisible() {
        boolean any = !cardByDrone.isEmpty() || library.isVisible();
        if (any != isVisible()) setVisible(any);
    }

    /** One drone's alert: the full card, or its badge. */
    private final class Card extends JPanel {
        private final String drone;
        private final JLabel title = Theme.label(" ", Theme.headingFont(13f), MapView.ALERT);
        private final JLabel when = Theme.label(" ", Theme.monoFont(Font.PLAIN, 11.5f), Theme.TEXT);
        private final JLabel info = Theme.label(" ", Theme.font(Font.PLAIN, 11.5f), Theme.MUTED);
        private final Picture picture = new Picture();
        private final JPanel full = new JPanel(new BorderLayout(0, 6));
        private final JPanel badge = new JPanel(new BorderLayout(6, 0));
        private final JLabel badgeText = Theme.label(" ", Theme.headingFont(11.5f), Theme.WARN);
        private AlertCard.Shown state;

        Card(String drone) {
            super(new BorderLayout());
            this.drone = drone;
            setBackground(Theme.PANEL_2);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(MapView.ALERT, 2),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)));
            setAlignmentX(LEFT_ALIGNMENT);
            full.setOpaque(false);
            JPanel head = new JPanel(new GridLayout(2, 1, 0, 2));
            head.setOpaque(false);
            head.add(title);
            head.add(when);
            full.add(head, BorderLayout.NORTH);
            picture.setPreferredSize(new Dimension(WIDTH - 20, (WIDTH - 20) * 9 / 16));
            full.add(picture, BorderLayout.CENTER);
            JPanel foot = new JPanel(new BorderLayout(0, 6));
            foot.setOpaque(false);
            foot.add(info, BorderLayout.NORTH);
            JPanel buttons = new JPanel(new GridLayout(1, 3, 6, 0));
            buttons.setOpaque(false);
            JButton connect = Theme.primaryButton("Connect");
            connect.setToolTipText("Wake the drone and connect to it as its pilot");
            connect.addActionListener(e -> onConnect.accept(drone));
            JButton dismiss = Theme.secondaryButton("Dismiss");
            dismiss.setToolTipText("Close this: the preview stops, and the drone stays silent until the motion ends (10 min at most)");
            dismiss.addActionListener(e -> onDismiss.accept(drone));
            JButton clips = Theme.secondaryButton("Clips");
            clips.setToolTipText("This drone's clips");
            clips.addActionListener(e -> openClips(drone));
            buttons.add(connect);
            buttons.add(dismiss);
            buttons.add(clips);
            foot.add(buttons, BorderLayout.SOUTH);
            full.add(foot, BorderLayout.SOUTH);
            badge.setOpaque(false);
            badge.add(badgeText, BorderLayout.CENTER);
            JPanel badgeButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            badgeButtons.setOpaque(false);
            JButton open = Theme.secondaryButton("Clips");
            open.addActionListener(e -> openClips(drone));
            JButton close = Theme.secondaryButton("X");
            close.setToolTipText("Off the map window; the clip stays in the list");
            close.addActionListener(e -> onCloseBadge.accept(drone));
            badgeButtons.add(open);
            badgeButtons.add(close);
            badge.add(badgeButtons, BorderLayout.EAST);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(WIDTH, getPreferredSize().height);
        }

        /** @return true when the layout changed (card and badge swapped) */
        boolean show(OverwatchHub.View v) {
            boolean layout = v.shown() != state;
            if (layout) {
                state = v.shown();
                removeAll();
                add(state == AlertCard.Shown.BADGE ? badge : full, BorderLayout.CENTER);
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(state == AlertCard.Shown.BADGE ? Theme.WARN : MapView.ALERT, state == AlertCard.Shown.BADGE ? 1 : 2),
                        BorderFactory.createEmptyBorder(state == AlertCard.Shown.BADGE ? 4 : 8, 8, state == AlertCard.Shown.BADGE ? 4 : 8, 8)));
            }
            OverwatchProtocol.Alert a = v.alert();
            String start = clock(v.alertAtWall());
            long now = System.currentTimeMillis();
            if (state == AlertCard.Shown.BADGE) {
                badgeText.setText("OVERWATCH " + drone + " - motion " + start + (v.endedAtWall() > 0 ? ", " + length(v.endedAtWall() - v.alertAtWall()) : ""));
                return layout;
            }
            title.setText((v.ended() ? "MOTION ENDED - " : a != null && a.light() ? "LIGHT MOVING - " : "MOTION - ") + drone);
            when.setText(v.ended() && v.endedAtWall() > 0 ? start + " - " + clock(v.endedAtWall()) + "  (" + length(v.endedAtWall() - v.alertAtWall()) + ")"
                    : start + "  -  " + length(now - v.alertAtWall()) + " ago" + (v.live() ? "  LIVE" : ""));
            StringBuilder line = new StringBuilder();
            if (v.pose() != null && a != null) line.append(String.format(Locale.ROOT, "Bearing %03.0f deg", (v.pose().headingDeg() + a.angleDeg + 360) % 360));
            if (a != null && a.battery <= 100) line.append(line.length() > 0 ? "  -  " : "").append("battery ").append(a.battery).append(" %");
            if (v.clipLine() != null) line.append(line.length() > 0 ? "  -  " : "").append(v.clipLine());
            info.setText(line.length() == 0 ? " " : line.toString());
            picture.setImage(v.picture(), v.ended() ? null : a == null ? null : new float[]{a.x, a.y, a.w, a.h});
            return layout;
        }
    }

    private static String clock(long wall) {
        return wall <= 0 ? "-" : TIME.format(Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()));
    }

    static String length(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s < 60 ? s + " s" : s < 3600 ? String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** A picture, scaled to fit (letterboxed), with the detection's box when one is given. */
    private static final class Picture extends JComponent {
        private volatile BufferedImage image;
        private volatile float[] box;

        Picture() {
            setOpaque(true);
            setPreferredSize(new Dimension(WIDTH - 20, (WIDTH - 20) * 9 / 16));
        }

        void setImage(BufferedImage img, float[] box) {
            if (img == image && java.util.Arrays.equals(box, this.box)) return;
            image = img;
            this.box = box;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());
                BufferedImage img = image;
                if (img == null) {
                    g.setColor(Theme.MUTED);
                    g.setFont(Theme.headingFont(12f));
                    g.drawString("WAITING FOR THE PICTURE", 10, getHeight() / 2);
                    return;
                }
                double s = Math.min((double) getWidth() / img.getWidth(), (double) getHeight() / img.getHeight());
                int w = (int) Math.round(img.getWidth() * s), h = (int) Math.round(img.getHeight() * s);
                int x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, x, y, w, h, null);
                float[] b = box;
                if (b != null && b[2] > 0 && b[3] > 0) {
                    g.setColor(MapView.ALERT);
                    g.drawRect(x + Math.round(b[0] * w), y + Math.round(b[1] * h), Math.max(4, Math.round(b[2] * w)), Math.max(4, Math.round(b[3] * h)));
                }
            } finally {
                g.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ the clip list

    private void buildLibrary() {
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.add(Theme.label("CLIPS", Theme.headingFont(13f), Theme.TEXT), BorderLayout.WEST);
        TacticalCombo.install(droneCombo);
        droneCombo.setToolTipText("Whose clips: every drone that has a folder in Documents\\DRAUPNE Link\\Overwatch");
        droneCombo.addActionListener(e -> {
            if (!fillingCombo) fillClips();
        });
        head.add(droneCombo, BorderLayout.CENTER);
        JButton close = Theme.secondaryButton("X");
        close.setToolTipText("Close the clip list");
        close.addActionListener(e -> closeLibrary());
        head.add(close, BorderLayout.EAST);
        library.add(head, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        clipList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clipList.setBackground(Theme.PANEL);
        clipList.setCellRenderer(new ClipRenderer());
        clipList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) playSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(clipList);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        scroll.setPreferredSize(new Dimension(WIDTH - 20, 220));
        body.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1, 3, 6, 0));
        actions.setOpaque(false);
        JButton play = Theme.primaryButton("Play");
        play.addActionListener(e -> playSelected());
        JButton remove = Theme.secondaryButton("Remove");
        remove.setToolTipText("Off this list; the files stay in the folder");
        remove.addActionListener(e -> removeSelected());
        JButton folder = Theme.secondaryButton("Folder");
        folder.setToolTipText("Show in folder: Explorer opens on the clip's files");
        folder.addActionListener(e -> showInFolder());
        actions.add(play);
        actions.add(remove);
        actions.add(folder);
        JPanel under = new JPanel(new BorderLayout(0, 6));
        under.setOpaque(false);
        under.add(actions, BorderLayout.NORTH);
        under.add(folderSize, BorderLayout.SOUTH);
        body.add(under, BorderLayout.SOUTH);

        JPanel playerBox = new JPanel(new BorderLayout(0, 6));
        playerBox.setOpaque(false);
        playerBox.add(player, BorderLayout.CENTER);
        JPanel controls = new JPanel(new BorderLayout(6, 0));
        controls.setOpaque(false);
        playButton.setText("Pause");
        playButton.addActionListener(e -> togglePause());
        controls.add(playButton, BorderLayout.WEST);
        seek.setOpaque(false);
        seek.addChangeListener(e -> {
            if (settingSeek || seek.getValueIsAdjusting() || durationMs <= 0) return;
            seekTo = seek.getValue() * durationMs / 1000; // where the pilot let go of it
        });
        controls.add(seek, BorderLayout.CENTER);
        controls.add(playTime, BorderLayout.EAST);
        playerBox.add(controls, BorderLayout.SOUTH);
        JPanel stack = new JPanel(new BorderLayout(0, 10));
        stack.setOpaque(false);
        stack.add(body, BorderLayout.NORTH);
        stack.add(playerBox, BorderLayout.CENTER);
        library.add(stack, BorderLayout.CENTER);
    }

    /** The clip list for this drone (MAP TOOLS > CLIPS: the first drone with clips, or the one shown). */
    void openClips(String drone) {
        fillingCombo = true;
        try {
            droneCombo.removeAllItems();
            List<String> drones = new ArrayList<>(index.drones());
            if (drone != null && drones.stream().noneMatch(d -> d.equalsIgnoreCase(drone))) drones.add(0, drone);
            for (String d : drones) droneCombo.addItem(d);
            if (drone != null) droneCombo.setSelectedItem(drones.stream().filter(d -> d.equalsIgnoreCase(drone)).findFirst().orElse(drone));
        } finally {
            fillingCombo = false;
        }
        library.setVisible(true);
        syncVisible();
        fillClips();
        revalidate();
        repaint();
    }

    void closeLibrary() {
        stopPlaying();
        library.setVisible(false);
        syncVisible();
        revalidate();
    }

    boolean libraryOpen() {
        return library.isVisible();
    }

    /** A clip came or an event's facts changed: the list again, if it shows that drone. */
    void clipsChanged(String drone) {
        if (library.isVisible() && drone.equalsIgnoreCase(String.valueOf(droneCombo.getSelectedItem()))) fillClips();
    }

    private void fillClips() {
        String drone = (String) droneCombo.getSelectedItem();
        ClipIndex.Clip selected = clipList.getSelectedValue();
        clipModel.clear();
        if (drone == null) {
            folderSize.setText("No clips yet: a drone in OVERWATCH records what it sees move.");
            return;
        }
        List<ClipIndex.Clip> clips = index.clips(drone);
        for (ClipIndex.Clip c : clips) clipModel.addElement(c);
        if (selected != null) {
            for (int i = 0; i < clipModel.size(); i++) if (clipModel.get(i).id().equals(selected.id())) clipList.setSelectedIndex(i);
        }
        folderSize.setText(clips.size() + " clip" + (clips.size() == 1 ? "" : "s") + "  -  the folder holds " + ClipIndex.sizeText(index.folderBytes(drone))
                + " (removed ones too; nothing is deleted by the app)");
    }

    private void removeSelected() {
        ClipIndex.Clip c = clipList.getSelectedValue();
        if (c == null) return;
        if (playing != null && playing.id().equals(c.id())) stopPlaying();
        try {
            index.hide(c.drone(), c.id());
        } catch (IOException e) {
            folderSize.setText("Not removed: " + e.getMessage());
            return;
        }
        fillClips();
    }

    private void showInFolder() {
        ClipIndex.Clip c = clipList.getSelectedValue();
        try {
            Path target = c == null ? null : c.video() != null ? c.video() : c.thumbnail();
            if (target != null) {
                new ProcessBuilder("explorer.exe", "/select," + target.toAbsolutePath()).start();
            } else {
                Object d = droneCombo.getSelectedItem();
                Path dir = d == null ? index.root() : index.folder(d.toString());
                Files.createDirectories(dir);
                new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
            }
        } catch (IOException e) {
            folderSize.setText("Explorer didn't open: " + e.getMessage());
        }
    }

    /** One line of the list: the picture before the alert, the time, the length and where. */
    private final class ClipRenderer implements ListCellRenderer<ClipIndex.Clip> {
        private final JPanel row = new JPanel(new BorderLayout(8, 0));
        private final JLabel thumb = new JLabel();
        private final JLabel text = new JLabel();

        ClipRenderer() {
            row.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            thumb.setPreferredSize(new Dimension(96, 54));
            thumb.setOpaque(true);
            thumb.setBackground(Color.BLACK);
            text.setFont(Theme.font(Font.PLAIN, 11.5f));
            row.add(thumb, BorderLayout.WEST);
            row.add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ClipIndex.Clip> list, ClipIndex.Clip c, int i, boolean selected, boolean focus) {
            row.setBackground(selected ? Theme.alpha(MapView.ALERT, 70) : Theme.PANEL);
            thumb.setIcon(thumb(c.thumbnail()));
            String when = c.startedAt() > 0 ? DAY_TIME.format(Instant.ofEpochMilli(c.startedAt()).atZone(ZoneId.systemDefault())) : c.id();
            String kind = c.fullClip() != null ? "full quality" : c.preview() != null ? "preview (the full clip is on its way)" : "pictures only";
            if ("none".equals(c.meta().getProperty("clip")) && c.fullClip() == null) kind = c.preview() != null ? "preview (no full clip)" : "pictures only";
            String where = c.hasPosition() ? Coordinates.format(format.get(), c.lat(), c.lon()) : "position unknown";
            text.setText("<html><b>" + when + "</b><br>" + length(c.durationMs()) + "  -  " + kind + "<br><span style='color:#8c8c7e'>" + where + "</span></html>");
            text.setForeground(Theme.TEXT);
            return row;
        }
    }

    private ImageIcon thumb(Path p) {
        if (p == null) return null;
        return thumbs.computeIfAbsent(p, k -> {
            try {
                BufferedImage img = ImageIO.read(k.toFile());
                if (img == null) return null;
                BufferedImage small = new BufferedImage(96, 54, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = small.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double s = Math.min(96.0 / img.getWidth(), 54.0 / img.getHeight());
                int w = (int) (img.getWidth() * s), h = (int) (img.getHeight() * s);
                g.drawImage(img, (96 - w) / 2, (54 - h) / 2, w, h, null);
                g.dispose();
                return new ImageIcon(small);
            } catch (IOException e) {
                return null;
            }
        });
    }

    // ------------------------------------------------------------------ the player

    private void playSelected() {
        ClipIndex.Clip c = clipList.getSelectedValue();
        if (c == null && !clipModel.isEmpty()) {
            clipList.setSelectedIndex(0);
            c = clipList.getSelectedValue();
        }
        if (c == null) return;
        play(c);
    }

    private void play(ClipIndex.Clip c) {
        stopPlaying();
        playing = c;
        paused = false;
        seekTo = -1;
        playButton.setText("Pause");
        replay = replayMark(c);
        map.setOverwatchMarks(marks());
        if (replay != null && !map.shows(replay.lat(), replay.lon(), 40)) map.panTo(replay.lat(), replay.lon());
        int gen = ++playGen;
        playerThread.execute(() -> runPlayback(gen, c));
    }

    /** The camera's view as it was when the clip was made (the event's facts), calm yellow on the map. */
    private MapView.OverwatchMark replayMark(ClipIndex.Clip c) {
        if (!c.hasPosition()) return null;
        double heading = c.doubleOf("headingDeg");
        CameraFootprint.Pose p = Double.isNaN(heading) ? null : new CameraFootprint.Pose(c.lat(), c.lon(), nz(c.doubleOf("altM")), heading,
                nz(c.doubleOf("pitchDeg")), nz(c.doubleOf("rollDeg")), nz(c.doubleOf("tiltDeg")), def(c.doubleOf("hfovDeg"), 70), def(c.doubleOf("vfovDeg"), 42));
        double angle = c.doubleOf("angleDeg");
        double bearing = p == null || Double.isNaN(angle) ? Double.NaN : (heading + angle + 360) % 360;
        return new MapView.OverwatchMark(c.drone(), c.lat(), c.lon(), cone("replay:" + c.id(), p), bearing, false, true,
                "CLIP " + c.drone() + " " + clock(c.startedAt()));
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static double def(double v, double fallback) {
        return Double.isNaN(v) || v <= 0 ? fallback : v;
    }

    private void stopPlaying() {
        playGen++;
        playing = null;
        if (replay != null) {
            replay = null;
            map.setOverwatchMarks(marks());
        }
    }

    private void togglePause() {
        if (playing == null) {
            playSelected();
            return;
        }
        paused = !paused;
        playButton.setText(paused ? "Play" : "Pause");
    }

    /** On the player's thread: the pictures before the alert (half a second each), then the clip at its own pace. */
    private void runPlayback(int gen, ClipIndex.Clip c) {
        try {
            List<Path> pre = c.preRoll();
            Path video = c.video();
            for (Path p : pre) {
                if (gen != playGen || seekTo >= 0) break;
                while (paused && gen == playGen) sleep(50);
                BufferedImage img = ImageIO.read(p.toFile());
                if (img != null) post(gen, img, "BEFORE THE ALERT", -1);
                sleep(500);
            }
            if (video == null) {
                post(gen, null, "No video of this event: only the pictures before the alert", -1);
                return;
            }
            try (ClipPlayer player = new ClipPlayer(video)) {
                durationMs = player.durationMs();
                int w = Math.min(640, Math.max(16, player.width())), h = Math.max(16, Math.round(player.height() * (float) w / Math.max(1, player.width())));
                int[] pixels = new int[w * h];
                long wallStart = System.currentTimeMillis();
                long pos = 0;
                while (gen == playGen) {
                    long to = seekTo;
                    if (to >= 0) {
                        seekTo = -1;
                        player.seek(to);
                        pos = to;
                        wallStart = System.currentTimeMillis() - to;
                    }
                    if (paused) {
                        sleep(50);
                        wallStart = System.currentTimeMillis() - pos; // the clock stands still while paused
                        continue;
                    }
                    long t = player.next(pixels, w, h);
                    if (t < 0) {
                        post(gen, null, "END", durationMs);
                        while (gen == playGen && seekTo < 0) sleep(100); // the slider can start it again
                        continue;
                    }
                    pos = t;
                    long wait = wallStart + t - System.currentTimeMillis();
                    if (wait > 0) sleep(Math.min(wait, 1000));
                    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    System.arraycopy(pixels, 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0, pixels.length);
                    post(gen, img, null, t);
                }
            }
        } catch (IOException | RuntimeException e) {
            post(gen, null, "Can't play: " + e.getMessage(), -1);
        }
    }

    private void post(int gen, BufferedImage img, String note, long posMs) {
        SwingUtilities.invokeLater(() -> {
            if (gen != playGen) return;
            if (img != null) player.setImage(img, null);
            if (posMs >= 0 && durationMs > 0) {
                settingSeek = true;
                seek.setValue((int) Math.min(1000, posMs * 1000 / durationMs));
                settingSeek = false;
                playTime.setText(length(posMs) + " / " + length(durationMs));
            }
            if (note != null) playTime.setText(note.length() > 40 ? note.substring(0, 40) : note);
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The tour's anchor. */
    JComponent tourCards() {
        return cards;
    }

    void dispose() {
        playGen++;
        playerThread.shutdownNow();
    }
}
