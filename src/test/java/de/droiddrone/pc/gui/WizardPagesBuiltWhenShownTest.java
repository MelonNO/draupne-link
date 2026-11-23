package de.droiddrone.pc.gui;

import de.droiddrone.pc.setup.SetupBoard;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Map;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F-084 (the bench, 2026-11-23): SETUP > Set up a new drone held the window 0.46 to 0.57 s as it opened - every one of
 * its seven pages was built at once, and the second step's 3D drone read its model and two textures (0.12 s) on the
 * window's thread. A page is now built when it is first shown, the model read on a thread of its own as the guide
 * opens; the checks that decide the skipped steps still start for every page as it opens. Never shown on screen.
 */
class WizardPagesBuiltWhenShownTest {
    /** A page that counts its builds and its prepare() calls. */
    private static class Counted extends WizardDialog.Page {
        int builds;
        int prepared;
        boolean applicable = true;

        @Override
        String title() {
            return "Test";
        }

        @Override
        String hint() {
            return "";
        }

        @Override
        JComponent build() {
            builds++;
            return new JPanel();
        }

        @Override
        boolean isApplicable() {
            return applicable;
        }

        @Override
        void prepare() {
            prepared++;
        }
    }

    private static WizardDialog open(List<WizardDialog.Page> pages) throws Exception {
        WizardDialog[] w = new WizardDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new WizardDialog(null, "New drone", pages, null);
            w[0].pack(); // displayable as when open, never shown
        });
        return w[0];
    }

    private static void press(WizardDialog w, String text) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AbstractButton b = button(w.getContentPane(), text);
            assertNotNull(b, text + " is on the wizard");
            b.doClick(0);
        });
    }

    private static AbstractButton button(Container c, String text) {
        for (Component k : c.getComponents()) {
            if (k instanceof AbstractButton b && text.equalsIgnoreCase(b.getText())) return b;
            if (k instanceof Container kk) {
                AbstractButton found = button(kk, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int[] builds(Counted... pages) throws Exception {
        int[] n = new int[pages.length];
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < pages.length; i++) n[i] = pages[i].builds;
        });
        return n;
    }

    private static void close(WizardDialog w) throws Exception {
        SwingUtilities.invokeAndWait(() -> w.closeAs("test"));
    }

    @Test
    void onlyThePageShownIsBuiltAndEachOnlyOnce() throws Exception {
        Counted a = new Counted(), b = new Counted(), c = new Counted();
        WizardDialog w = open(List.of(a, b, c));
        try {
            assertArrayEquals(new int[]{1, 0, 0}, builds(a, b, c), "the wizard opens with its first page built, no other");
            press(w, "NEXT");
            assertArrayEquals(new int[]{1, 1, 0}, builds(a, b, c), "NEXT builds the second");
            press(w, "BACK");
            press(w, "NEXT");
            assertArrayEquals(new int[]{1, 1, 0}, builds(a, b, c), "a page shown before is not built again");
            press(w, "NEXT");
            assertArrayEquals(new int[]{1, 1, 1}, builds(a, b, c));
            String[] counter = new String[1];
            SwingUtilities.invokeAndWait(() -> counter[0] = w.stepCounterText());
            assertEquals("STEP 3 OF 3", counter[0], "the steps are counted as before");
        } finally {
            close(w);
        }
    }

    @Test
    void everyPagesCheckStillStartsAsTheWizardOpensAndASkippedPageIsNeverBuilt() throws Exception {
        Counted a = new Counted(), skipped = new Counted(), c = new Counted();
        skipped.applicable = false;
        WizardDialog w = open(List.of(a, skipped, c));
        try {
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, a.prepared);
                assertEquals(1, skipped.prepared, "prepare() runs for a page not built yet: its check decides whether it is skipped");
                assertEquals(1, c.prepared);
            });
            press(w, "NEXT");
            assertArrayEquals(new int[]{1, 0, 1}, builds(a, skipped, c), "the skipped page is never built");
        } finally {
            close(w);
        }
    }

    /** New drone's and Drone setup's second step: not built as the guide opens, its 3D model read in the background. */
    @Test
    void theMotorsStepIsNotBuiltAsTheGuideOpensAndItsModelIsReadMeanwhile() throws Exception {
        DroneSetup.MotorsPage.Host host = new DroneSetup.MotorsPage.Host() {
            @Override
            public boolean isLinkActive() {
                return false;
            }

            @Override
            public String setting(String key) {
                return null;
            }

            @Override
            public void save(Map<String, String> settings) {
            }

            @Override
            public void motorTest(java.awt.Window owner, SetupBoard board, java.util.function.Consumer<Boolean> onClosed) {
            }

            @Override
            public void orientationCheck(java.awt.Window owner, SetupBoard board, Runnable onPassed, java.util.function.Consumer<String> onClosed) {
            }
        };
        DroneSetup.MotorsPage motors = new DroneSetup.MotorsPage(host, () -> null);
        Counted first = new Counted();
        WizardDialog w = open(List.of(first, motors));
        try {
            boolean[] built = new boolean[1];
            SwingUtilities.invokeAndWait(() -> built[0] = motors.isBuilt());
            assertFalse(built[0], "the motors step (and its 3D drone) is not made as the guide opens");
            waitFor(DroneModelView::loaded, 20_000, "the 3D model read in the background");
        } finally {
            close(w);
        }
    }
}
