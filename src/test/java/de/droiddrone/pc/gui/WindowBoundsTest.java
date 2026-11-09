package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.*;

/** Where the main window and the map window come back (MapWindow.Bounds.fit), with the screens as they are now. */
class WindowBoundsTest {
    private static final Rectangle LAPTOP = new Rectangle(0, 0, 1920, 1080);
    /** A monitor that sat above the laptop when the bounds were saved, unplugged since. */
    private static final Rectangle ABOVE = new Rectangle(0, -1440, 2560, 1440);

    @Test
    void boundsOnAScreenStillThereComeBackAsTheyWere() {
        Rectangle r = new Rectangle(100, 80, 1200, 800);
        assertEquals(r, MapWindow.Bounds.fit(r, new Rectangle[]{LAPTOP}));
        assertEquals(r, MapWindow.Bounds.fit(r, new Rectangle[]{ABOVE, LAPTOP}));
    }

    @Test
    void aTitleBarAboveTheScreenComesDownOntoIt() {
        // saved half on the monitor above: its title bar at y -500, its lower half on the laptop
        Rectangle back = MapWindow.Bounds.fit(new Rectangle(300, -500, 1200, 900), new Rectangle[]{LAPTOP});
        assertNotNull(back);
        assertEquals(0, back.y, "the title bar on the screen, where it can be dragged");
        assertEquals(new Rectangle(300, 0, 1200, 900), back);
    }

    @Test
    void boundsFromABiggerMonitorAreMadeToFitTheScreen() {
        Rectangle back = MapWindow.Bounds.fit(new Rectangle(-100, 50, 2500, 1400), new Rectangle[]{LAPTOP});
        assertEquals(LAPTOP, back);
    }

    @Test
    void nothingIsRestoredOffEveryScreenOrTooSmallToBeOurs() {
        assertNull(MapWindow.Bounds.fit(new Rectangle(0, -1400, 1200, 800), new Rectangle[]{LAPTOP}), "on the unplugged monitor only");
        assertNull(MapWindow.Bounds.fit(new Rectangle(1800, 100, 1200, 800), new Rectangle[]{LAPTOP}), "120 px showing: not enough");
        assertNull(MapWindow.Bounds.fit(new Rectangle(0, 0, 300, 200), new Rectangle[]{LAPTOP}));
    }
}
