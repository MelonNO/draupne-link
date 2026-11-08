package de.droiddrone.pc.gui;

import javax.swing.JOptionPane;

/**
 * TOOLS &gt; Advanced &gt; Reset setup (1.0.52, the pilot: "so Draupne is like a fresh install"): asks, then this PC's
 * settings go and the app starts again - the setup guide opens by itself, then the tour. Settings only (the pilot's
 * choice): flights, videos, overwatch clips, pins, drawings and saved maps stay.
 */
final class ResetSetup {
    private ResetSetup() {
    }

    static void ask(MainWindow main) {
        if (main.isLinkActive()) {
            JOptionPane.showMessageDialog(main, "DISCONNECT first.", "Reset setup", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(main, "<html><b>Reset this PC's settings?</b><br><br>"
                        + "Gone: settings, phone names, calibration, tour.<br>"
                        + "Kept: flights, videos, clips, pins, drawings, maps.<br><br>"
                        + "DRAUPNE Link restarts with the setup guide.</html>",
                "Reset setup", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        main.logLine("Reset setup: settings removed, restarting.");
        main.resetSetupAndRestart();
    }
}
