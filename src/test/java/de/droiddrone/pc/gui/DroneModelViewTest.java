package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class DroneModelViewTest {
    private static BufferedImage paint(DroneModelView v) {
        BufferedImage img = new BufferedImage(520, 380, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        v.paint(g);
        g.dispose();
        return img;
    }

    /** The front props' red: opaque, red well over green and blue. */
    private static boolean red(int argb) {
        int r = argb >> 16 & 0xff, g = argb >> 8 & 0xff, b = argb & 0xff;
        return (argb >>> 24) == 255 && r > 90 && r > 2 * g && r > 2 * b;
    }

    /** {mean y of the red pixels, mean y of the drone's other opaque pixels, red count}. */
    private static double[] where(BufferedImage img) {
        double ry = 0, oy = 0;
        int rn = 0, on = 0;
        for (int y = 0; y < img.getHeight() - 30; y++) { // the label at the bottom left aside
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                if (red(p)) {
                    ry += y;
                    rn++;
                } else if ((p >>> 24) == 255) {
                    oy += y;
                    on++;
                }
            }
        }
        return new double[]{ry / Math.max(1, rn), oy / Math.max(1, on), rn};
    }

    @Test
    void theMeshLoadsWithTheRedPropsAtTheFrontAwayFromTheEye() {
        DroneModelView v = new DroneModelView();
        v.setSize(520, 380);
        v.setAttitude(0, 0, 0);
        double[] flat = where(paint(v));
        assertTrue(flat[2] > 500, "the front props are drawn red: " + flat[2]);
        assertTrue(flat[0] < flat[1], "seen from behind and above, the front is higher on screen: red " + flat[0] + ", rest " + flat[1]);
        v.setAttitude(0, 40, 0); // Betaflight's nose down
        double[] noseDown = where(paint(v));
        assertTrue(noseDown[0] > flat[0] + 8, "nose down brings the front props down: " + flat[0] + " -> " + noseDown[0]);
    }
}
