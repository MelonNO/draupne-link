package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static de.droiddrone.testing.Waits.waitForOnSwing;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F-073 (the bench's guide-first-start, 1.0.71): the setup guide said "STEP 1 OF 9" on its first page, then "STEP 2 OF
 * 8". The last of the checks that decide the skipped steps was counted as done before its result was in, so its page
 * still counted as shown. The count is now right from the first page, or has no total until it is known. Never shown
 * on screen.
 */
class WizardStepCountTest {
    private static class Plain extends WizardDialog.Page {
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
            return new JPanel();
        }
    }

    /** A step whose check (in the background, as the PC's steps do) finds it done: then it is skipped. */
    private static final class Checked extends Plain {
        final CountDownLatch answer = new CountDownLatch(1);
        private boolean done;

        @Override
        boolean isApplicable() {
            return !done;
        }

        @Override
        void prepare() {
            background(() -> {
                try {
                    return answer.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return false;
                }
            }, found -> done = Boolean.TRUE.equals(found));
        }
    }

    private static WizardDialog open(Checked checked) throws Exception {
        WizardDialog[] w = new WizardDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new WizardDialog(null, "Drone setup", List.of(new Plain(), checked, new Plain()), null);
            w[0].pack(); // displayable as when open, never shown
        });
        return w[0];
    }

    private static String counter(WizardDialog w) throws Exception {
        String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> text[0] = w.stepCounterText());
        return text[0];
    }

    private static void close(WizardDialog w) throws Exception {
        SwingUtilities.invokeAndWait(() -> w.closeAs("test"));
    }

    @Test
    void theFirstPagesCountLeavesOutTheStepItsCheckSkips() throws Exception {
        Checked checked = new Checked();
        WizardDialog w = open(checked);
        try {
            assertFalse(counter(w).contains("OF"), "no total while the check is out: " + counter(w));
            checked.answer.countDown();
            waitForOnSwing(() -> w.stepCounterText().contains("OF"), 5000, "the step count");
            assertEquals("STEP 1 OF 2", counter(w), "the skipped step is not counted");
        } finally {
            close(w);
        }
    }

    @Test
    void aCheckSlowerThanTheSecondsWaitShowsNoTotalThenTheRightOne() throws Exception {
        Checked checked = new Checked();
        WizardDialog w = open(checked);
        try {
            waitForOnSwing(() -> w.stepCounterText().startsWith("STEP"), 5000, "the step shown after the second's wait");
            assertEquals("STEP 1", counter(w), "no total until it is known");
            checked.answer.countDown();
            waitForOnSwing(() -> w.stepCounterText().contains("OF"), 5000, "the step count");
            assertEquals("STEP 1 OF 2", counter(w));
        } finally {
            close(w);
        }
    }
}
