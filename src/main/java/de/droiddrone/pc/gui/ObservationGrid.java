package de.droiddrone.pc.gui;

import de.droiddrone.pc.MotionBoxes;
import de.droiddrone.pc.observe.TileGrid;
import de.droiddrone.pc.video.VideoPipeline;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * OBSERVATION's grid in the main window's video area (1.0.48): one tile per observed phone - 1, 2x1, 2x2 or 3x2 by
 * count (TileGrid) - each with its number and colour (the same on the map), the drone's name, its live picture (the
 * motion detection's boxes on it), a green frame while its phone hears something, MUTE and volume for its sound, and
 * CONNECT. A click selects a tile (its phone sends the sharp picture), a double-click enlarges it and back.
 * <p>
 * 1.0.55 (the pilot): each tile shows its phone's picture the way up set for that phone (CameraTurn - the same as the
 * main video, the overwatch cards and the clips; right-click a tile to turn it) and the OSD with that drone's own
 * figures (ObservationOsd), drawn by the pilot's OSD layout.
 */
final class ObservationGrid extends JPanel {
    /** The tiles' colours, by number: the map draws each phone's cone in its tile's. */
    static final Color[] COLOURS = {new Color(0x4fc3f7), new Color(0xffb74d), new Color(0xba68c8), new Color(0x4dd0e1),
            new Color(0xf06292), new Color(0xdce775)};
    static final Color SPEAKING = new Color(0x2ee66b);
    static final int GAP = 6;

    interface Listener {
        void select(int tile);

        void enlarge(int tile);

        void connect(int tile);

        void mute(int tile, boolean muted);

        /** 0..100; done: the slider let go (worth saving) */
        void volume(int tile, int volume, boolean done);

        /** The mouse over a tile (-1: none): its cone stands out on the map. */
        void hover(int tile);
    }

    /**
     * What a tile shows besides its picture, refreshed by the main window.
     * @param osd its OSD now (ObservationOsd.data), or null for none
     */
    record TileState(String status, boolean speaking, boolean muted, int volume, String headingNote,
                     List<MotionBoxes.Shown> boxes, OsdRenderer.Data osd) {
        TileState(String status, boolean speaking, boolean muted, int volume, String headingNote, List<MotionBoxes.Shown> boxes) {
            this(status, speaking, muted, volume, headingNote, boxes, null);
        }
    }

    private final Listener listener;
    private final CameraTurn turn;
    /** The pilot's OSD layout, and whether the OSD is on at all (F2 / VIDEO > OSD): read at every paint. */
    private final Supplier<OsdLayout> osdLayout;
    private final BooleanSupplier osdOn;
    private final BiConsumer<String, Boolean> turned = (phone, on) -> repaintPhone(phone);
    private final List<Tile> tiles = new ArrayList<>();
    private int enlarged = -1, selected = -1;

    ObservationGrid(Listener listener) {
        this(listener, null, OsdLayout::defaults, () -> false);
    }

    /** @param turn each phone's way up (null: as it comes) */
    ObservationGrid(Listener listener, CameraTurn turn, Supplier<OsdLayout> osdLayout, BooleanSupplier osdOn) {
        super(null);
        this.listener = listener;
        this.turn = turn;
        this.osdLayout = osdLayout;
        this.osdOn = osdOn;
        setBackground(Color.BLACK);
        setOpaque(true);
        if (turn != null) turn.addListener(turned);
    }

    /** The grid is gone: no more news of turned pictures. */
    void dispose() {
        if (turn != null) turn.removeListener(turned);
    }

    private void repaintPhone(String phone) {
        for (Tile t : tiles) if (t.phone.equalsIgnoreCase(phone)) t.video.repaint();
    }

    /** The tile's phone's picture is shown upside down. */
    boolean upsideDown(int tile) {
        return turn != null && tile >= 0 && tile < tiles.size() && turn.upsideDown(tiles.get(tile).phone);
    }

    static Color colour(int tile) {
        return COLOURS[Math.floorMod(tile, COLOURS.length)];
    }

    /** New tiles for these phones (the old ones go). */
    void setPhones(List<String> phones) {
        for (Tile t : tiles) remove(t);
        tiles.clear();
        for (int i = 0; i < phones.size(); i++) {
            Tile t = new Tile(i, phones.get(i));
            tiles.add(t);
            add(t);
        }
        enlarged = selected = -1;
        revalidate();
        repaint();
    }

    VideoPipeline.FrameSink sink(int tile) {
        return tiles.get(tile).video;
    }

    int count() {
        return tiles.size();
    }

    void setSelection(int selected, int enlarged) {
        if (this.selected == selected && this.enlarged == enlarged) return;
        this.selected = selected;
        this.enlarged = enlarged;
        for (Tile t : tiles) t.updateFrame();
        revalidate();
        repaint();
    }

    /** A phone left the grid: its tile goes, the ones after it move up (their numbers and colours with them). */
    void removeTile(int tile) {
        if (tile < 0 || tile >= tiles.size()) return;
        remove(tiles.remove(tile));
        for (int i = 0; i < tiles.size(); i++) tiles.get(i).setIndex(i);
        selected = enlarged = -1;
        revalidate();
        repaint();
    }

    void update(int tile, TileState s) {
        if (tile >= 0 && tile < tiles.size()) tiles.get(tile).show(s);
    }

    @Override
    public void doLayout() {
        Rectangle[] r = TileGrid.layout(tiles.size(), getWidth(), getHeight(), GAP, enlarged);
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            t.setBounds(r[i]);
            t.setVisible(!r[i].isEmpty());
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, getWidth(), getHeight());
        if (tiles.isEmpty()) {
            g.setColor(Theme.MUTED);
            g.setFont(Theme.headingFont(14f));
            String text = "OBSERVATION: no phone in the grid";
            g.drawString(text, (getWidth() - g.getFontMetrics().stringWidth(text)) / 2, getHeight() / 2);
        }
    }

    /** One phone's tile. */
    private final class Tile extends JPanel {
        int index;
        final String phone;
        final TileVideo video;
        final JLabel name;
        final JLabel status = Theme.label(" ", Theme.font(Font.PLAIN, 11.5f), Theme.MUTED);
        final GlyphButton mute = new GlyphButton(GlyphButton.Glyph.SOUND, "Mute");
        final JSlider volume = new JSlider(0, 100, 100);
        final JButton connect = Theme.primaryButton("Connect");
        boolean speaking, muted;
        boolean updating;

        Tile(int index, String phone) {
            super(new BorderLayout());
            this.index = index;
            this.phone = phone;
            video = new TileVideo(phone, turn, osdLayout, osdOn);
            setBackground(Color.BLACK);
            name = Theme.label((index + 1) + "  " + phone, Theme.headingFont(12.5f), colour(index));
            add(video, BorderLayout.CENTER);
            // the controls keep their place on the right; the name and the status line give way, cut with "..." (1.0.50:
            // in one row the status pushed MUTE, the volume and CONNECT off a narrow tile)
            JPanel bar = new JPanel(new BorderLayout(6, 0));
            bar.setBackground(Theme.PANEL);
            bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 4));
            JPanel texts = new JPanel(new java.awt.GridLayout(2, 1));
            texts.setOpaque(false);
            texts.setMinimumSize(new Dimension(0, 0));
            texts.add(name);
            texts.add(status);
            bar.add(texts, BorderLayout.CENTER);
            JPanel controls = new JPanel();
            controls.setOpaque(false);
            controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
            mute.setToolTipText("Mute this drone (others play on)");
            mute.addActionListener(e -> listener.mute(index, !muted));
            controls.add(mute);
            volume.setOpaque(false);
            volume.setFocusable(false);
            volume.setPreferredSize(new Dimension(64, 22));
            volume.setMaximumSize(new Dimension(64, 22));
            volume.setToolTipText("This drone's volume");
            volume.addChangeListener(e -> {
                if (!updating) listener.volume(index, volume.getValue(), !volume.getValueIsAdjusting());
            });
            controls.add(volume);
            controls.add(Box.createHorizontalStrut(6));
            connect.setFocusable(false);
            connect.setToolTipText("Fly this drone: streams stop, pilot link opens (throttle low, arm off)");
            connect.addActionListener(e -> listener.connect(index));
            controls.add(connect);
            bar.add(controls, BorderLayout.EAST);
            add(bar, BorderLayout.SOUTH);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) turnMenu(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) turnMenu(e);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) return;
                    if (e.getClickCount() == 2) listener.enlarge(index);
                    else if (e.getClickCount() == 1) listener.select(index);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    listener.hover(index);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    listener.hover(-1);
                }
            };
            video.addMouseListener(mouse);
            updateFrame();
        }

        /** Right-click: this phone's picture upside down or not - everywhere it shows. */
        private void turnMenu(MouseEvent e) {
            if (turn == null) return;
            JPopupMenu menu = new JPopupMenu();
            JCheckBoxMenuItem item = new JCheckBoxMenuItem("Camera upside down", turn.upsideDown(phone));
            item.setToolTipText("Turns " + phone + "'s picture 180°, everywhere");
            item.addActionListener(a -> turn.set(phone, item.isSelected()));
            menu.add(item);
            menu.show(e.getComponent(), e.getX(), e.getY());
        }

        void setIndex(int i) {
            index = i;
            name.setText((i + 1) + "  " + phone);
            name.setForeground(colour(i));
            updateFrame();
        }

        void show(TileState s) {
            String text = s.status() == null || s.status().isEmpty() ? " " : s.status();
            if (s.headingNote() != null) text = text.isBlank() ? s.headingNote() : text + "  |  " + s.headingNote();
            if (!text.equals(status.getText())) status.setText(text);
            if (s.muted() != muted) {
                muted = s.muted();
                mute.setGlyph(muted ? GlyphButton.Glyph.MUTED : GlyphButton.Glyph.SOUND);
                mute.setToolTipText(muted ? "Muted - press to hear this drone" : "Mute this drone (others play on)");
            }
            if (!volume.getValueIsAdjusting() && volume.getValue() != s.volume()) {
                updating = true;
                volume.setValue(s.volume());
                updating = false;
            }
            video.boxes = s.boxes() == null ? List.of() : s.boxes();
            video.osd = s.osd();
            if (s.speaking() != speaking) {
                speaking = s.speaking();
                updateFrame();
            }
        }

        void updateFrame() {
            boolean sel = index == selected || index == enlarged;
            Color c = speaking ? SPEAKING : sel ? colour(index) : Theme.LINE;
            int w = speaking ? 3 : sel ? 2 : 1;
            setBorder(BorderFactory.createLineBorder(c, w));
        }
    }

    /** A tile's picture: the newest decoded one drawn, nothing queued; turned as set for its phone, its OSD over it. */
    private static final class TileVideo extends JComponent implements VideoPipeline.FrameSink {
        private final String phone;
        private final CameraTurn turn;
        private final Supplier<OsdLayout> osdLayout;
        private final BooleanSupplier osdOn;
        /** Its own: the renderer keeps per-paint state. */
        private final OsdRenderer osdRenderer = new OsdRenderer();
        volatile OsdRenderer.Data osd;
        private final Object lock = new Object();
        private BufferedImage back, ready, front;
        private boolean fresh;
        private volatile boolean mirror;
        private volatile int[] target = {0, 0};
        private volatile int videoWidth = 16, videoHeight = 9;
        volatile List<MotionBoxes.Shown> boxes = List.of();

        TileVideo(String phone, CameraTurn turn, Supplier<OsdLayout> osdLayout, BooleanSupplier osdOn) {
            this.phone = phone;
            this.turn = turn;
            this.osdLayout = osdLayout;
            this.osdOn = osdOn;
            osdRenderer.setObserver(true);
            osdRenderer.setMinUnit(ObservationOsd.MIN_UNIT);
            setOpaque(true);
            setBackground(Color.BLACK);
            setDoubleBuffered(false);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    updateTarget();
                }
            });
        }

        private void updateTarget() {
            int[] f = fitIn(videoWidth, videoHeight, getWidth(), getHeight());
            target = new int[]{f[0] / 2 * 2, f[1] / 2 * 2};
        }

        static int[] fitIn(int iw, int ih, int w, int h) {
            if (w <= 0 || h <= 0 || iw <= 0 || ih <= 0) return new int[]{0, 0};
            double s = Math.min(w / (double) iw, h / (double) ih);
            return new int[]{Math.max(2, (int) Math.round(iw * s)), Math.max(2, (int) Math.round(ih * s))};
        }

        @Override
        public void videoStarted(int width, int height, boolean mirror) {
            if (width > 0 && height > 0) {
                videoWidth = width;
                videoHeight = height;
            }
            this.mirror = mirror;
            updateTarget();
        }

        @Override
        public int[] targetSize() {
            return target;
        }

        @Override
        public int[] beginFrame(int width, int height) {
            synchronized (lock) {
                if (back == null || back.getWidth() != width || back.getHeight() != height) {
                    back = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                }
                return ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
            }
        }

        @Override
        public void presentFrame() {
            synchronized (lock) {
                BufferedImage t = ready;
                ready = back;
                back = t;
                fresh = true;
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics;
            int w = getWidth(), h = getHeight();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            BufferedImage img;
            synchronized (lock) {
                if (fresh) {
                    BufferedImage t = front;
                    front = ready;
                    ready = t;
                    fresh = false;
                }
                img = front;
            }
            if (img == null) {
                g.setColor(Theme.MUTED);
                g.setFont(Theme.headingFont(12f));
                String text = "WAITING FOR THE PICTURE";
                g.drawString(text, (w - g.getFontMetrics().stringWidth(text)) / 2, h / 2);
                return;
            }
            int[] f = fitIn(img.getWidth(), img.getHeight(), w, h);
            int x = (w - f[0]) / 2, y = (h - f[1]) / 2;
            boolean upsideDown = turn != null && turn.upsideDown(phone);
            boolean flipX = CameraTurn.flipX(mirror, upsideDown), flipY = upsideDown;
            CameraTurn.draw(g, img, x, y, f[0], f[1], flipX, flipY);
            OsdRenderer.Data d = osd;
            if (d != null && osdOn.getAsBoolean() && f[0] >= 200 && f[1] >= 120) {
                // the OSD draws the motion boxes too, turned with the picture
                osdRenderer.setPictureFlip(flipX, flipY);
                osdRenderer.paint(g, x, y, f[0], f[1], d, osdLayout.get());
                return;
            }
            paintBoxes(g, boxes, x, y, f[0], f[1], flipX, flipY);
        }

        /** The motion boxes where they show on the picture drawn flipped so (no OSD over it). */
        static void paintBoxes(Graphics2D g, List<MotionBoxes.Shown> shown, int x, int y, int fw, int fh, boolean flipX, boolean flipY) {
            if (shown.isEmpty()) return;
            Graphics2D b = (Graphics2D) g.create();
            try {
                b.setStroke(new BasicStroke(1.5f));
                for (MotionBoxes.Shown s : shown) {
                    float[] r = CameraTurn.box(new float[]{s.box().x, s.box().y, s.box().w, s.box().h}, flipX, flipY);
                    b.setColor(Theme.alpha(Theme.WARN, Math.round(230 * s.alpha())));
                    b.drawRect(x + Math.round(r[0] * fw), y + Math.round(r[1] * fh),
                            Math.max(2, Math.round(r[2] * fw)), Math.max(2, Math.round(r[3] * fh)));
                }
            } finally {
                b.dispose();
            }
        }
    }
}
