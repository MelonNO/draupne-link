package de.droiddrone.pc.gui;

import java.util.List;

/** What the test bench needs from this package and can't reach from its own: names of settings, never behaviour. */
public final class BenchAccess {
    private BenchAccess() {
    }

    /** Every tour feature marked as seen: a pilot who has been through the tour. */
    public static String tourSeenAll() {
        return String.join(",", Tour.FEATURES);
    }

    public static List<String> tourFeatures() {
        return Tour.FEATURES;
    }

    /** The app made to believe its screen is this small (0: the real screen again). Its windows that ask fit themselves to it. */
    public static void pretendScreen(int w, int h) {
        ScreenFit.pretend = w > 0 && h > 0 ? new java.awt.Dimension(w, h) : null;
    }

    /** The screen the app's windows have to fit: the made-up one while there is one, else the real one's room. */
    public static java.awt.Rectangle screen() {
        return ScreenFit.usable(null);
    }

    /** Whether this map's last paint had an empty square where a tile is still to come (Swing thread). */
    public static boolean mapTilesMissing(java.awt.Component map) {
        return map instanceof MapView m && m.tilesMissing();
    }

    /** The OSD's elements as its window lists them (by title): {key in the settings file, title, the line that explains it}. */
    public static List<String[]> osdElements() {
        OsdElement[] sorted = OsdElement.values().clone();
        java.util.Arrays.sort(sorted, java.util.Comparator.comparing(e -> e.title));
        List<String[]> out = new java.util.ArrayList<>();
        for (OsdElement e : sorted) out.add(new String[]{e.key, e.title, e.help});
        return out;
    }

    /**
     * The middle of one segment of a MENU row of choices (VIDEO: AUTO | SHARP | ...), in the row's own coordinates -
     * laid out as MenuRows.ChoiceRow lays them out: at the right edge, each as wide as the longest word needs.
     */
    public static java.awt.Point choice(java.awt.Component row, String[] options, int index) {
        java.awt.FontMetrics fm = row.getFontMetrics(Theme.headingFont(10.5f));
        int widest = 0;
        for (String o : options) widest = Math.max(widest, fm.stringWidth(o));
        int segW = Math.max(58, widest + 16);
        int left = row.getWidth() - 14 - options.length * segW;
        return new java.awt.Point(left + index * segW + segW / 2, row.getHeight() / 2);
    }

    public static boolean isMainWindow(java.awt.Window w) {
        return w instanceof MainWindow;
    }

    /** A setup guide's check row's light (OK, WARN, BAD, PENDING, INFO, TODO); null for anything that is no check row. Read only. */
    public static String rowState(java.awt.Component c) {
        return c instanceof CheckRow r ? r.getState().name() : null;
    }

    /** Drone setup's two settings for the battery: the cell count, and that the pilot confirmed it (DroneSetup.BetaflightPage). */
    public static String[] droneCellKeys() {
        return new String[]{DroneSetup.BetaflightPage.CELLS_KEY, DroneSetup.BetaflightPage.CELLS_CONFIRMED_KEY};
    }
}
