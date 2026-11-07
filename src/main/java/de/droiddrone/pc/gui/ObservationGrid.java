package de.droiddrone.pc.gui;

import de.droiddrone.pc.MotionBoxes;
import de.droiddrone.pc.observe.TileGrid;
import de.droiddrone.pc.video.VideoPipeline;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
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

/**
 * OBSERVATION's grid in the main window's video area (1.0.48): one tile per observed phone - 1, 2x1, 2x2 or 3x2 by
 * count (TileGrid) - each with its number and colour (the same on the map), the drone's name, its live picture (the
 * motion detection's boxes on it), a green frame while its phone hears something, MUTE and volume for its sound, and
 * CONNECT. A click selects a tile (its phone sends the sharp picture), a double-click enlarges it and back.
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

    /** What a tile shows besides its picture, refreshed by the main window. */
    record TileState(String status, boolean speaking, boolean muted, int volume, String headingNote,
                     List<MotionBoxes.Shown> boxes) {}

    private final Listener listener;
    private final List<Tile> tiles = new ArrayList<>();
    private int enlarged = -1, selected = -1;

    ObservationGrid(Listener listener) {
        super(null);
        this.listener = listener;
        setBackground(Color.BLACK);
        setOpaque(true);
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
        final TileVideo video = new TileVideo();
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
            setBackground(Color.BLACK);
            name = Theme.label((index + 1) + "  " + phone, Theme.headingFont(12.5f), colour(index));
            add(video, BorderLayout.CENTER);
            JPanel bar = new JPanel();
            bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
            bar.setBackground(Theme.PANEL);
            bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 4));
            JPanel texts = new JPanel();
            texts.setOpaque(false);
            texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
            texts.add(name);
            texts.add(status);
            bar.add(texts);
            bar.add(Box.createHorizontalGlue());
            mute.setToolTipText("Mute this drone's sound (the others play on)");
            mute.addActionListener(e -> listener.mute(index, !muted));
            bar.add(mute);
            volume.setOpaque(false);
            volume.setFocusable(false);
            volume.setPreferredSize(new Dimension(80, 22));
            volume.setMaximumSize(new Dimension(80, 22));
            volume.setToolTipText("This drone's volume");
            volume.addChangeListener(e -> {
                if (!updating) listener.volume(index, volume.getValue(), !volume.getValueIsAdjusting());
            });
            bar.add(volume);
            bar.add(Box.createHorizontalStrut(6));
            connect.setFocusable(false);
            connect.setToolTipText("Fly this drone: every stream stops, then the usual pilot connection opens (throttle low, arm switch off)");
            connect.addActionListener(e -> listener.connect(index));
            bar.add(connect);
            add(bar, BorderLayout.SOUTH);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
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
                mute.setToolTipText(muted ? "Muted - press to hear this drone" : "Mute this drone's sound (the others play on)");
            }
            if (!volume.getValueIsAdjusting() && volume.getValue() != s.volume()) {
                updating = true;
                volume.setValue(s.volume());
                updating = false;
            }
            video.boxes = s.boxes() == null ? List.of() : s.boxes();
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

    /** A tile's picture: the newest decoded one drawn, nothing queued. */
    private static final class TileVideo extends JComponent implements VideoPipeline.FrameSink {
        private final Object lock = new Object();
        private BufferedImage back, ready, front;
        private boolean fresh;
        private volatile boolean mirror;
        private volatile int[] target = {0, 0};
        private volatile int videoWidth = 16, videoHeight = 9;
        volatile List<MotionBoxes.Shown> boxes = List.of();

        TileVideo() {
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
            if (mirror) g.drawImage(img, x + f[0], y, -f[0], f[1], null);
            else g.drawImage(img, x, y, f[0], f[1], null);
            List<MotionBoxes.Shown> shown = boxes;
            if (!shown.isEmpty()) {
                Graphics2D b = (Graphics2D) g.create();
                try {
                    b.setStroke(new BasicStroke(1.5f));
                    for (MotionBoxes.Shown s : shown) {
                        float bx = mirror ? 1 - s.box().x - s.box().w : s.box().x;
                        b.setColor(Theme.alpha(Theme.WARN, Math.round(230 * s.alpha())));
                        b.drawRect(x + Math.round(bx * f[0]), y + Math.round(s.box().y * f[1]),
                                Math.max(2, Math.round(s.box().w * f[0])), Math.max(2, Math.round(s.box().h * f[1])));
                    }
                } finally {
                    b.dispose();
                }
            }
        }
    }
}
