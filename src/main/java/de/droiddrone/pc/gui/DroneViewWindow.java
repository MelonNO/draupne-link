package de.droiddrone.pc.gui;

import de.droiddrone.pc.observe.ObserverLink;
import de.droiddrone.pc.observe.TileFeed;
import de.droiddrone.pc.video.VideoPipeline;

import javax.swing.JComponent;
import javax.swing.JDialog;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;

/**
 * Fleet PRD C3: VIEW - a drone's picture on demand, small, in a window of its own (480x270 at 15 fps: the observation's
 * small stream, from that drone's own phone; FD-7: the piloted drone's uplink and video tier are not touched). Closing
 * the window ends the stream. The phone gives it to the PC that commands it (ObserveGate's fleet view).
 */
final class DroneViewWindow extends JDialog implements VideoPipeline.FrameSink {
    static final ObserverLink.Quality SMALL = LaunchViews.MINI;

    private final TileFeed feed;
    private volatile BufferedImage picture;
    private int[] buffer;
    private int w, h;

    private DroneViewWindow(Window owner, String phone, InetSocketAddress at, String key) throws java.io.IOException {
        super(owner, "VIEW " + phone, ModalityType.MODELESS);
        JComponent view = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.BG);
                g.fillRect(0, 0, getWidth(), getHeight());
                BufferedImage p = picture;
                if (p != null) g.drawImage(p, 0, 0, getWidth(), getHeight(), null);
            }
        };
        view.setPreferredSize(new Dimension(480, 270));
        setContentPane(view);
        pack();
        feed = new TileFeed(phone, at.getAddress(), at.getPort(), key, this, null, SMALL, msg -> { });
        feed.link().setQuality(SMALL);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                feed.stopStream();
                feed.release();
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    /** Opens the view of a drone; null and nothing opened when its phone can't be reached. */
    static DroneViewWindow open(Window owner, String phone, InetSocketAddress at, String key) {
        if (at == null) return null;
        try {
            DroneViewWindow v = new DroneViewWindow(owner, phone, at, key);
            v.setLocationRelativeTo(owner);
            v.setVisible(true);
            return v;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void videoStarted(int width, int height, boolean mirror) {
    }

    @Override
    public int[] targetSize() {
        return new int[]{SMALL.width(), SMALL.height()};
    }

    @Override
    public int[] beginFrame(int width, int height) {
        if (buffer == null || w != width || h != height) {
            buffer = new int[width * height];
            w = width;
            h = height;
        }
        return buffer;
    }

    @Override
    public void presentFrame() {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, buffer, 0, w);
        picture = img;
        getContentPane().repaint();
    }
}
