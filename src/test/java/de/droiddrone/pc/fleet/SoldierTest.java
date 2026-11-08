package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class SoldierTest {
    /** Pixels darker than anything else in the day scene (the soldier's camouflage, goggles and rifle). */
    private static int dark(BufferedImage img) {
        int n = 0;
        for (int c : Scene.pixels(img)) {
            int r = c >> 16 & 0xff, g = c >> 8 & 0xff, b = c & 0xff;
            if ((66 * r + 129 * g + 25 * b + 128 >> 8) + 16 < 50) n++;
        }
        return n;
    }

    @Test
    void theModelIsAWalkingPersonOfAPersonsSize() {
        Soldier.Model m = Soldier.model();
        assertTrue(m.frames() >= 12 && m.tri().length / 3 > 2000, m.frames() + " frames, " + m.tri().length / 3 + " triangles");
        int top = 0;
        short[] p = m.pos()[0];
        for (int i = 2; i < p.length; i += 3) top = Math.max(top, p[i]);
        assertTrue(top > 1700 && top < 1900, "about 1.8 m tall: " + top + " mm");
        short[] half = m.pos()[m.frames() / 2];
        int moved = 0;
        for (int i = 0; i < p.length; i++) moved = Math.max(moved, Math.abs(p[i] - half[i]));
        assertTrue(moved > 150, "half a step later the limbs have moved: " + moved + " mm");
        assertTrue(m.strideM() > 1 && m.strideM() < 2.2, "metres a walk cycle: " + m.strideM());
    }

    @Test
    void aWalkerIsDrawnInThePictureAndNothingWithoutOne() {
        Scene.View v = new Scene.View(100, 120, 0, 0, 0, 135);
        Scene with = new Scene("Z", 7), without = new Scene("Z", 7);
        with.addTarget(Scene.Kind.WALKER, v, 0, 10);
        BufferedImage a = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), b = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        with.render(a, v, 6000, null, false);
        without.render(b, v, 6000, null, false);
        assertEquals(0, dark(b), "nothing that dark without the walker");
        assertTrue(dark(a) > 40, "the soldier in the picture: " + dark(a) + " dark pixels");
    }
}
