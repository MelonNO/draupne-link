package de.droiddrone.pc.gui;

import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.RepaintManager;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TourTest {
    private final RepaintManager before = RepaintManager.currentManager((JComponent) null);

    @AfterEach
    void restore() {
        RepaintManager.setCurrentManager(before);
    }

    /** Counts the area every repaint asks for, whether on screen or not. */
    private static final class Counting extends RepaintManager {
        long area;

        @Override
        public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
            area += (long) Math.max(0, w) * Math.max(0, h);
        }
    }

    private static Tour.TourNotice notice(JLayeredPane layer, JComponent anchor, Tour.Step step, boolean centred) {
        Tour.TourNotice n = new Tour.TourNotice(anchor, step, 3, 30, () -> { }, () -> { }, centred, null);
        n.attachForTest(layer, 10_000);
        return n;
    }

    private static JLayeredPane window(int w, int h) {
        JLayeredPane layer = new JLayeredPane();
        layer.setSize(w, h);
        return layer;
    }

    /**
     * The lag (the pilot: "if the tour is open for a while it starts to lag the program"): every tick repainted the
     * whole dim, so everything under it - video, map, fleet panel - 30 times a second. Now the ring's band alone.
     */
    @Test
    void aStepThatIsInRepaintsOnlyItsRingNotTheWindow() {
        JLayeredPane layer = window(1366, 768);
        JButton anchor = new JButton("Minimize");
        anchor.setBounds(1200, 600, 120, 30);
        layer.add(anchor, JLayeredPane.DEFAULT_LAYER);
        Tour.TourNotice n = notice(layer, anchor, new Tour.Step("MINIMIZE", "Folds the controller card.", () -> anchor), false);
        Counting counting = new Counting();
        RepaintManager.setCurrentManager(counting);
        long fullBefore = n.tickRepaintArea(true);
        counting.area = 0;
        long steady = n.tickRepaintArea(false);
        assertEquals(1366L * 768, fullBefore, "before: the whole window every tick");
        assertEquals(steady, counting.area, "what it says it asked for");
        assertTrue(steady < fullBefore / 50, "the ring's band only: " + steady + " of " + fullBefore);
        Rectangle band = Tour.TourNotice.ringBand(new Rectangle(1200, 600, 120, 30));
        assertTrue(band.contains(new Rectangle(1194, 594, 132, 42)), "the band holds the ring round the lit area");
    }

    @Test
    void theWelcomeSitsInTheMiddleAndHoldsStillOnceIn() {
        JLayeredPane layer = window(1366, 768);
        JButton anything = new JButton();
        anything.setBounds(0, 0, 10, 10);
        layer.add(anything);
        Tour.Step welcome = new Tour.Step("GUIDED TOUR", "A short walk through DRAUPNE.", () -> anything);
        welcome.legend = true;
        Tour.TourNotice n = notice(layer, anything, welcome, true);
        Rectangle r = n.getBounds();
        assertEquals(1366 / 2, r.x + r.width / 2, 1, "centred across");
        assertEquals(768 / 2, r.y + r.height / 2, 1, "centred down");
        Counting counting = new Counting();
        RepaintManager.setCurrentManager(counting);
        assertEquals(0, n.tickRepaintArea(false), "nothing moves once it is in");
    }

    @Test
    void anUpdateBringsTheFleetAndPreviousFlightsSteps() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set(Tour.SEEN_KEY, "names,maptools,radiohorizon,coverage,overwatch,observation");
        assertEquals(List.of(Tour.FEATURE_FLEET, Tour.FEATURE_FLIGHTS), Tour.unseen(config));
        config.set(Tour.SEEN_KEY, String.join(",", Tour.FEATURES));
        assertTrue(Tour.unseen(config).isEmpty());
    }
}
