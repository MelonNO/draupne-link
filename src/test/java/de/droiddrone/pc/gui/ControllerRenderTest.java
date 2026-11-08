package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerRenderTest {
    private static int covered(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) > 0) n++;
        return n;
    }

    private static int different(BufferedImage a, BufferedImage b, int x0, int x1) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = x0; x < x1; x++) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }

    @Test
    void theRadioFillsTheViewAndIsSeenFromAbove() {
        BufferedImage img = ControllerRender.frame(ControllerRender.Show.REST, 0, 0, 460, 250);
        int n = covered(img);
        assertTrue(n > 460 * 250 / 4, "the radio covers a good part of the view: " + n);
        assertTrue((img.getRGB(0, 0) >>> 24) == 0 && (img.getRGB(459, 0) >>> 24) == 0, "the corners are see-through");
    }

    @Test
    void theStickAskedForMovesAndTheOtherStays() {
        // throttle up: at the start the left stick is down, a second in it is up and held; the right side stays put
        BufferedImage start = ControllerRender.frame(ControllerRender.Show.THROTTLE_UP, 0, 0, 460, 250);
        BufferedImage held = ControllerRender.frame(ControllerRender.Show.THROTTLE_UP, 0, 1200, 460, 250);
        assertTrue(different(start, held, 60, 200) > 400, "the left stick moved: " + different(start, held, 60, 200));
        // roll right moves the right stick, the left one is at rest throttle-down in both
        BufferedImage rollStart = ControllerRender.frame(ControllerRender.Show.ROLL_RIGHT, 0, 0, 460, 250);
        BufferedImage rollHeld = ControllerRender.frame(ControllerRender.Show.ROLL_RIGHT, 0, 1200, 460, 250);
        assertTrue(different(rollStart, rollHeld, 260, 400) > 400, "the right stick moved");
    }

    @Test
    void aSwitchFlips() {
        BufferedImage off = ControllerRender.frame(ControllerRender.Show.SWITCH, 0, 0, 460, 250);
        BufferedImage on = ControllerRender.frame(ControllerRender.Show.SWITCH, 0, 600, 460, 250);
        assertTrue(different(off, on, 0, 460) > 50, "the lever moved: " + different(off, on, 0, 460));
    }

    @Test
    void eachSwitchStepFlipsTheToggleItsPurposeLivesOn() {
        // the pilot's TX16S: SF arm, SH (momentary) prearm, SA the flight modes, SC AUX4 / GPS rescue
        org.junit.jupiter.api.Assertions.assertEquals(ControllerRender.SF, ControllerRender.slotFor("ARM", 0));
        org.junit.jupiter.api.Assertions.assertEquals(ControllerRender.SH, ControllerRender.slotFor("PREARM", 2));
        org.junit.jupiter.api.Assertions.assertEquals(ControllerRender.SA, ControllerRender.slotFor("flight modes", 1));
        org.junit.jupiter.api.Assertions.assertEquals(ControllerRender.SC, ControllerRender.slotFor("GPS rescue", 3));
        org.junit.jupiter.api.Assertions.assertEquals("SF", ControllerRender.switchName(ControllerRender.SF));
        int spare = ControllerRender.slotFor(null, 2);
        assertTrue(spare != ControllerRender.SF && spare != ControllerRender.SH && spare != ControllerRender.SA
                && spare != ControllerRender.SC, "a spare is none of those");
        // and they are different toggles on the picture: SF on the left, SH on the right
        BufferedImage arm = ControllerRender.frame(ControllerRender.Show.SWITCH, ControllerRender.SF, 600, 460, 250);
        BufferedImage prearm = ControllerRender.frame(ControllerRender.Show.SWITCH, ControllerRender.SH, 600, 460, 250);
        assertTrue(different(arm, prearm, 0, 460) > 2000, "the camera goes to the other side");
    }

    @Test
    void noLeverEverComesOutThroughItsBushing() {
        // the pilot: "it still clips outside its base" - every lever, at both ends of its throw and the snap's
        // overshoot past each: none of its points under the bushing's face is outside the bushing
        for (int slot = 0; slot < 8; slot++) {
            for (double angle : ControllerRender.leverAngles(slot)) {
                int outside = ControllerRender.leverPointsOutsideBushing(slot, angle);
                org.junit.jupiter.api.Assertions.assertEquals(0, outside, ControllerRender.switchName(slot) + " at "
                        + Math.round(Math.toDegrees(angle)) + " degrees: " + outside + " points through the bushing");
            }
        }
    }

    @Test
    void aLeverTiltsOverItsBushingAndSnaps() {
        // the pilot: the toggles "just moved" - they turn about where the lever leaves the bushing, a quick snap
        BufferedImage off = ControllerRender.frame(ControllerRender.Show.SWITCH, ControllerRender.SA, 0, 460, 250);
        BufferedImage halfway = ControllerRender.frame(ControllerRender.Show.SWITCH, ControllerRender.SA, 60, 460, 250);
        BufferedImage on = ControllerRender.frame(ControllerRender.Show.SWITCH, ControllerRender.SA, 600, 460, 250);
        assertTrue(different(off, on, 0, 460) > 50, "it flipped");
        assertTrue(different(off, halfway, 0, 460) > 20 && different(halfway, on, 0, 460) > 20, "60 ms in it is on its way: a snap, not a jump");
    }

    @Test
    void theScreenIsRedOpsWithTheMarkLarge() {
        // the pilot chose Red ops of ten, the DRAUPNE mark large in it: a lot of the accent red on a dark screen
        BufferedImage screen = ControllerRender.screenPicture();
        int red = 0, dark = 0;
        for (int y = 0; y < screen.getHeight(); y++) {
            for (int x = 0; x < screen.getWidth() / 2; x++) { // the left half: the mark
                int c = screen.getRGB(x, y), r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                if (r > 180 && g < 80 && b < 80) red++;
                if (r + g + b < 60) dark++;
            }
        }
        assertTrue(red > 2500, "the mark, large: " + red + " red pixels");
        assertTrue(dark > red, "on black");
    }

    @Test
    void aFrameIsQuickEnoughToAnimate() {
        ControllerRender.frame(ControllerRender.Show.SWEEP, 0, 0, 460, 250); // warm up
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) ControllerRender.frame(ControllerRender.Show.SWEEP, 0, i * 100L, 460, 250);
        long ms = (System.nanoTime() - t0) / 5_000_000;
        assertTrue(ms < 150, "a frame takes " + ms + " ms");
    }

    /** 1.0.52 (the pilot): the right toggles mirror the left ones - same rest, same throw, pivots mirrored. */
    @Test
    void theRightTogglesMirrorTheLeft() {
        int[][] pairs = {{ControllerRender.SB, ControllerRender.SC}, {ControllerRender.SA, ControllerRender.SD},
                {ControllerRender.SE, ControllerRender.SG}, {ControllerRender.SF, ControllerRender.SH}};
        for (int[] p : pairs) {
            double[] l = ControllerRender.leverAngles(p[0]), r = ControllerRender.leverAngles(p[1]);
            for (int i = 0; i < l.length; i++) assertEquals(l[i], r[i], 1e-9, ControllerRender.switchName(p[1]) + " as " + ControllerRender.switchName(p[0]));
            double[] lp = ControllerRender.switchPivot(p[0]), rp = ControllerRender.switchPivot(p[1]);
            assertTrue(lp[0] < 0 && rp[0] > 0, "left and right");
            assertEquals(lp[1], rp[1], 1e-6);
            assertEquals(lp[2], rp[2], 1e-6);
        }
    }
}
