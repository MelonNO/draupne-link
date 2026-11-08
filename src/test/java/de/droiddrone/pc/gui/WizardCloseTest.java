package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.52 (the pilot: "I plugged in the drone and the setup window closed by itself", and the log said nothing): every
 * close of a setup guide says why, and one nobody asked for says from where. Never shown on screen.
 */
class WizardCloseTest {
    private static final class OnePage extends WizardDialog.Page {
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

    private static String closeAndRead(java.util.function.Consumer<WizardDialog> how) throws Exception {
        PrintStream before = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            WizardDialog w = new WizardDialog(null, "Drone setup", List.of(new OnePage()), null);
            w.pack(); // displayable as when open, never shown
            System.setOut(new PrintStream(out, true));
            try {
                how.accept(w);
            } finally {
                System.setOut(before);
            }
            text[0] = out.toString();
        });
        return text[0];
    }

    @Test
    void aCloseNobodyAskedForIsLoggedWithWhereItCameFrom() throws Exception {
        String line = closeAndRead(WizardDialog::dispose);
        assertTrue(line.contains("Drone setup closed - not by the pilot"), line);
        assertTrue(line.contains("WizardCloseTest"), "the caller is named: " + line);
    }

    @Test
    void thePilotsCloseSaysHow() throws Exception {
        assertTrue(closeAndRead(w -> w.closeAs("CLOSE")).contains("Drone setup closed (CLOSE)."));
        assertFalse(closeAndRead(w -> w.closeAs("finished")).contains("closed"), "a finish logs its own line");
    }
}
