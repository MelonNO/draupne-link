package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;

import static org.junit.jupiter.api.Assertions.*;

class SlideMenuTest {
    @Test
    void shutItIsJustTheMenuButtonAndOpenTheItemsSitToItsLeft() {
        JButton main = new JButton("MENU");
        SlideMenu menu = new SlideMenu(main);
        JButton a = new JButton("SETUP"), b = new JButton("VIDEO"), hidden = new JButton("UPDATE");
        hidden.setVisible(false);
        menu.addItem(hidden);
        menu.addItem(b);
        menu.addItem(a);
        menu.finishNow();
        int shut = menu.getPreferredSize().width;
        assertEquals(main.getPreferredSize().width, shut, "shut: the MENU button alone");
        menu.setSize(menu.getPreferredSize());
        menu.doLayout();
        assertTrue(menu.itemX(a) < 0, "items wait behind the edge: " + menu.itemX(a));

        menu.setOpen(true);
        menu.finishNow();
        int open = menu.getPreferredSize().width;
        assertEquals(shut + a.getPreferredSize().width + b.getPreferredSize().width + 2 * 8, open, "open: both visible items and their gaps, not the hidden one");
        menu.setSize(menu.getPreferredSize());
        menu.doLayout();
        assertEquals(0, menu.itemX(a), "the last added sits at the far left");
        assertTrue(menu.itemX(b) > menu.itemX(a) && menu.itemX(b) + b.getWidth() <= main.getX(), "in order, left of MENU");
        assertTrue(menu.isOpen());
    }

    @Test
    void shutTheItemsTakeNoKeyboardFocus() {
        // the pilot (2026-11-10): Space in the main window opened SETUP's menu with MENU shut
        JButton main = new JButton("MENU"), setup = new JButton("SETUP");
        SlideMenu menu = new SlideMenu(main);
        menu.addItem(setup);
        assertFalse(setup.isFocusable(), "shut: SETUP behind the edge can't have the focus, so Space can't press it");
        menu.setOpen(true);
        assertTrue(setup.isFocusable(), "open: the keyboard reaches it");
        menu.setOpen(false);
        assertFalse(setup.isFocusable());
        assertTrue(main.isFocusable(), "MENU itself always");
    }

    @Test
    void aMenusWipeIsInFrontOfTheToursNoticeLikeTheMenuItself() {
        // the pilot (1.0.39): the wipe went behind the tour's notice and jumped in front when the menu took over
        assertTrue(TacticalCombo.GHOST_LAYER > Tour.NOTICE_LAYER, "the wipe over the notice");
        assertFalse(TacticalCombo.menu().isLightWeightPopupEnabled(), "the menu a window of its own: over the notice too");
    }
}
