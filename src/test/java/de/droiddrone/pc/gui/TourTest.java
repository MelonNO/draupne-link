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

    /**
     * The pause after POP OUT MAP (the pilot): the press hides the MAP card, and the step waited HIDDEN_MS (2.5 s) for
     * its gone control before moving on. Pressed and gone: on at once. Gone without a press: no.
     */
    @Test
    void aPressedControlThatGoesAwayMovesTheTourOnAtOnce() {
        JLayeredPane layer = window(1366, 768);
        JButton pop = new JButton("POP OUT MAP");
        pop.setBounds(40, 600, 300, 40);
        layer.add(pop);
        int[] nexts = {0};
        Tour.Step step = new Tour.Step("POP OUT MAP", "The map in its own window. Press it.", () -> pop).press();
        Tour.TourNotice n = new Tour.TourNotice(pop, step, 15, 33, () -> nexts[0]++, () -> { }, false, null);
        n.attachForTest(layer, 10_000);
        n.tickForTest(); // off screen: the control counts as gone
        assertEquals(0, nexts[0], "not pressed: the step waits");
        n.pressForTest();
        n.tickForTest();
        assertEquals(1, nexts[0], "pressed and gone: the next step at once");
    }

    /**
     * The alert step (the pilot: "the tour alert is still saying to press connect"): CONNECT closes the alert card it
     * points at, and a step whose control was gone never looked at what it waited for. Now the wait comes first.
     */
    @Test
    void whatTheStepWaitsForMovesItOnEvenWithItsControlGone() {
        JLayeredPane layer = window(1366, 768);
        JButton card = new JButton("CONNECT");
        card.setBounds(1000, 100, 300, 200);
        layer.add(card);
        boolean[] connected = {false};
        int[] nexts = {0};
        Tour.Step step = new Tour.Step("ALERT", "Press CONNECT on the alert.", () -> card)
                .waitFor(() -> connected[0], "Press CONNECT", true);
        Tour.TourNotice n = new Tour.TourNotice(card, step, 32, 33, () -> nexts[0]++, () -> { }, false, null);
        n.attachForTest(layer, 10_000);
        n.tickForTest();
        assertEquals(0, nexts[0], "nothing yet");
        connected[0] = true;
        n.tickForTest();
        assertEquals(1, nexts[0], "connected: on, though the card is gone");
    }

    /**
     * The pilot: a second tour "skips" the BTR part. A fleet step whose window is gone (the map window closed or
     * hidden) ended the whole tour after 3 s, silently; now it opens its window again, and an end says why in the log.
     */
    @Test
    void aFleetStepWithItsWindowGoneOpensItAgainInsteadOfEndingTheTour() {
        JLayeredPane layer = window(1366, 768);
        JButton rows = new JButton("rows");
        rows.setBounds(1000, 100, 300, 200);
        layer.add(rows);
        int[] reopened = {0}, ended = {0};
        Tour.Step step = new Tour.Step("INTEL", "Stand by.", () -> rows).reopen(() -> reopened[0]++)
                .waitFor(() -> false, "Standing by...", true);
        Tour.TourNotice n = new Tour.TourNotice(rows, step, 31, 35, () -> { }, () -> ended[0]++, false, null);
        n.attachForTest(layer, 10_000);
        n.tickForTest(); // no window: gone
        n.goneForTest(3500);
        n.tickForTest();
        assertEquals(1, reopened[0], "its window opened again");
        assertEquals(0, ended[0], "the tour goes on");

        Tour.Step plain = new Tour.Step("MENU", "Press it.", () -> rows);
        Tour.TourNotice m = new Tour.TourNotice(rows, plain, 2, 35, () -> { }, () -> ended[0]++, false, null);
        m.attachForTest(layer, 10_000);
        m.tickForTest();
        m.goneForTest(3500);
        m.tickForTest();
        assertEquals(1, ended[0], "a step without a window to reopen ends the tour, as before");
        assertEquals("its window was closed", m.endReason, "and says why");
    }

    private static java.awt.event.MouseEvent mouse(JComponent c, int id, int x, int y, int clicks) {
        Rectangle b = c.getBounds();
        return new java.awt.event.MouseEvent(c, id, System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
                x, y, b.x + x, b.y + y, clicks, false, java.awt.event.MouseEvent.BUTTON1);
    }

    /**
     * The pilot (2026-11-09): "the message pop up should follow the point they are pointing to" - dragged by its body
     * the box stays at its control; when the control moves (its window resized) the box goes with it; its corner makes
     * it wider (the text wraps to fewer lines), kept for the next steps.
     */
    @Test
    void theBoxStaysAtItsControlFollowsItAndWidensByItsCorner() {
        JLayeredPane layer = window(1366, 768);
        JButton anchor = new JButton("MAP TYPE");
        anchor.setBounds(100, 100, 120, 30);
        layer.add(anchor, JLayeredPane.DEFAULT_LAYER);
        Tour.NoticePlace place = new Tour.NoticePlace();
        Tour.Step step = new Tour.Step("MAP TYPE", "Topo, paper map, satellite or OpenStreetMap. Viewed areas are kept "
                + "offline. Press it and pick one of the four pictures for the map under the drone. The topo map is the "
                + "default in Norway; satellite pictures show the ground as it looks from the air, OpenStreetMap the rest "
                + "of the world.", () -> anchor);
        Tour.TourNotice n = new Tour.TourNotice(anchor, step, 3, 30, () -> { }, () -> { }, false, null, place);
        n.attachForTest(layer, 10_000);
        Rectangle under = n.getBounds();
        assertEquals(136, under.y, "first under its control: 100 + 30 + gap");

        // dragged by its body: it stays
        n.dispatchEvent(mouse(n, java.awt.event.MouseEvent.MOUSE_PRESSED, 40, 30, 1));
        n.dispatchEvent(mouse(n, java.awt.event.MouseEvent.MOUSE_DRAGGED, 540, 330, 1));
        n.dispatchEvent(mouse(n, java.awt.event.MouseEvent.MOUSE_RELEASED, 540, 330, 1));
        n.placeForTest();
        assertEquals(under.getLocation(), n.getLocation(), "never dragged away from its control");

        // the control moves (the window resized): the box follows at the next tick
        anchor.setLocation(400, 300);
        n.placeForTest(); // what each tick does while the control shows
        assertEquals(336, n.getY(), "under the control again: 300 + 30 + gap");

        // wider by its corner: fewer lines, so lower
        int h = n.getHeight();
        Rectangle b = n.getBounds();
        n.dispatchEvent(mouse(n, java.awt.event.MouseEvent.MOUSE_PRESSED, b.width - 4, b.height - 4, 1));
        n.dispatchEvent(mouse(n, java.awt.event.MouseEvent.MOUSE_DRAGGED, b.width - 4 + 300, b.height - 4, 1));
        n.dispatchEvent(mouse(n, java.awt.event.MouseEvent.MOUSE_RELEASED, b.width - 4 + 300, b.height - 4, 1));
        assertEquals(b.width + 300, n.getWidth(), "wider by the drag");
        assertTrue(n.getHeight() < h, "the text on fewer lines: " + n.getHeight() + " < " + h);
        n.resizeTo(10);
        assertEquals(Tour.TourNotice.MIN_WIDTH, n.getWidth(), "never narrower than readable");
        n.resizeTo(b.width + 300);

        // the next step's box: as wide, at its own control
        n.dismiss();
        JButton other = new JButton("COORDINATES");
        other.setBounds(400, 60, 120, 30);
        layer.add(other, JLayeredPane.DEFAULT_LAYER);
        Tour.TourNotice m = new Tour.TourNotice(other, new Tour.Step("COORDINATES", "MGRS, UTM or degrees.", () -> other),
                4, 30, () -> { }, () -> { }, false, null, place);
        m.attachForTest(layer, 10_000);
        assertEquals(b.width + 300, m.getWidth(), "as wide as the pilot made it");
        assertEquals(96, m.getY(), "under COORDINATES: 60 + 30 + gap");
    }

    /**
     * The window's title bar stays free during the tour (moving and resizing the map window): the box is never put over
     * it, and the dim tells FlatLaf's title bar the points over it are not its own (2026-11-09, the pilot: "it is still
     * not possible to move the map window" - FlatLaf asks every component by its bounds, and the dim covers the window).
     */
    @Test
    void theTitleBarStaysTheWindowsDuringTheTour() {
        javax.swing.JRootPane root = new javax.swing.JRootPane();
        javax.swing.JMenuBar bar = new javax.swing.JMenuBar(); // stands in for the title bar above the content
        bar.setPreferredSize(new java.awt.Dimension(10, 40));
        root.setJMenuBar(bar);
        root.setSize(1366, 768);
        root.doLayout();
        root.getLayeredPane().doLayout();
        JButton anchor = new JButton("FOLLOW");
        anchor.setBounds(20, 690, 120, 30); // near the bottom: no room under it, so the box goes above
        root.getContentPane().setLayout(null);
        root.getContentPane().add(anchor);
        Tour.Step step = new Tour.Step("MOVING AROUND", "Drag to pan, wheel to zoom. " + "Words to fill the box. ".repeat(60),
                () -> anchor);
        Tour.TourNotice n = new Tour.TourNotice(anchor, step, 15, 30, () -> { }, () -> { }, false, null);
        n.attachForTest(root.getLayeredPane(), 10_000);
        assertTrue(n.getY() >= 40, "a box taller than the room above stops under the title bar: " + n.getY());
        assertNull(n.captionForTest(300, 20), "over the title bar: the dim says not mine (the window drags)");
        assertEquals(Boolean.FALSE, n.captionForTest(300, 300), "over the content: the dim's");
    }

    /** SETUP > Overwatch demo starts at the FLEET step, which every whole tour has, in front of the finale's steps. */
    @Test
    void theOverwatchDemoStartsAtTheFleetStep() {
        List<Tour.Step> steps = List.of(new Tour.Step("GUIDED TOUR", "", () -> null), new Tour.Step("DATA", "", () -> null),
                new Tour.Step("FLEET", "", () -> null), new Tour.Step("THE ROWS", "", () -> null));
        assertEquals(2, Tour.fleetStepIndex(steps));
        assertEquals(-1, Tour.fleetStepIndex(List.of(new Tour.Step("MENU", "", () -> null))));
    }

    @Test
    void anUpdateBringsTheFleetAndPreviousFlightsSteps() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set(Tour.SEEN_KEY, "names,maptools,radiohorizon,coverage,overwatch,observation");
        assertEquals(List.of(Tour.FEATURE_FLEET, Tour.FEATURE_FLIGHTS, Tour.FEATURE_MODES, Tour.FEATURE_SPECTATOR), Tour.unseen(config));
        // 1.0.56: a PC that had every tour before the modes and the spectator mirror is offered those alone
        config.set(Tour.SEEN_KEY, "names,maptools,radiohorizon,coverage,overwatch,observation,fleet,flights");
        assertEquals(List.of(Tour.FEATURE_MODES, Tour.FEATURE_SPECTATOR), Tour.unseen(config));
        config.set(Tour.SEEN_KEY, String.join(",", Tour.FEATURES));
        assertTrue(Tour.unseen(config).isEmpty());
    }

    @Test
    void theTourShowsAMaximizedMapWindowAtASizeThatCanBeResized() {
        Rectangle screen = new Rectangle(0, 0, 1920, 1040);
        assertEquals(new Rectangle(370, 126, 1180, 780), MapWindow.tourNormalBounds(new Rectangle(370, 126, 1180, 780), screen),
                "its own normal size when it has one on this screen");
        Rectangle fallback = MapWindow.tourNormalBounds(null, screen);
        assertEquals(new Rectangle(240, 130, 1440, 780), fallback, "else three quarters of the screen, centred");
        assertEquals(fallback, MapWindow.tourNormalBounds(new Rectangle(0, 0, 1920, 1040), screen),
                "a normal size as big as the screen would be no better than maximized");
        assertEquals(fallback, MapWindow.tourNormalBounds(new Rectangle(3000, 100, 800, 600), screen), "off this screen");
    }
}
