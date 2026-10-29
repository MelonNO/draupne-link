package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class VideoViewTest {
    private static final int RED = 0xff0000;
    private static final int BLUE = 0x0000ff;

    /** A 16x8 picture shown in a 16x8 view: red in the top-left quarter, blue elsewhere. Returns the screen. */
    private static BufferedImage show(boolean mirror, boolean upsideDown) {
        VideoView view = new VideoView();
        view.setSize(16, 8);
        view.videoStarted(16, 8, mirror);
        view.setUpsideDown(upsideDown);
        int[] px = view.beginFrame(16, 8);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) px[y * 16 + x] = x < 8 && y < 4 ? RED : BLUE;
        }
        view.presentFrame();
        BufferedImage screen = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        view.paintComponent(g);
        g.dispose();
        return screen;
    }

    private static int at(BufferedImage screen, int x, int y) {
        return screen.getRGB(x, y) & 0xffffff;
    }

    @Test
    void upsideDownTurnsThePicture180Degrees() {
        BufferedImage normal = show(false, false);
        assertEquals(RED, at(normal, 1, 1), "top-left");
        assertEquals(BLUE, at(normal, 14, 6));

        BufferedImage turned = show(false, true);
        assertEquals(RED, at(turned, 14, 6), "top-left ends up bottom-right");
        assertEquals(BLUE, at(turned, 1, 1));
    }

    @Test
    void mirroredFrontCameraTurnsToo() {
        assertEquals(RED, at(show(true, false), 14, 1), "mirrored: top-right");
        assertEquals(RED, at(show(true, true), 1, 6), "mirrored and turned: bottom-left");
    }
}
