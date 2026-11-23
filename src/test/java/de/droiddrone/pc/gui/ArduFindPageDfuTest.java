package de.droiddrone.pc.gui;

import de.droiddrone.bench.hw.FakeFlightBoard;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.ardu.ArduDfuFlow;
import de.droiddrone.pc.setup.ardu.ArduUsb;
import de.droiddrone.pc.setup.ardu.DfuRig;
import de.droiddrone.pc.setup.ardu.MavSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static de.droiddrone.testing.Waits.waitForOnSwing;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet H1 / H4 / AS-11 through FIND's buttons: "To ArduPilot (DFU)" and "From Betaflight" against made-up Betaflight
 * boards and made-up STM32s in DFU - the board the pilot picks is the only one touched, an armed board is refused, a
 * board already in DFU is written from a file. No real USB device is opened.
 */
class ArduFindPageDfuTest {
    @TempDir
    Path dir;
    private final DfuRig rig = new DfuRig();
    private final List<FakeFlightBoard> bf = new ArrayList<>();
    private final List<String> opened = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();
    private WizardDialog wizard;
    private List<WizardDialog.Page> pages;
    /** Which board the pilot picks in the board list (by its words), and the firmware's source (1: a file). */
    private volatile String pick;

    @BeforeEach
    void setUp() throws Exception {
        BetaflightUsb.use(new BetaflightUsb.Usb() {
            public List<BetaflightUsb.Port> ports() {
                List<BetaflightUsb.Port> out = new ArrayList<>();
                for (FakeFlightBoard b : bf) out.addAll(b.usb().ports());
                return out;
            }

            public BetaflightUsb.Wire open(BetaflightUsb.Port port) throws java.io.IOException {
                for (FakeFlightBoard b : bf) {
                    if (b.port().systemName().equals(port.systemName())) {
                        opened.add(port.systemName());
                        return b.usb().open(port);
                    }
                }
                throw new java.io.IOException("gone");
            }
        });
        // no ArduPilot on these ports: each listened to, nothing heard
        ArduUsb.use(p -> new MavSession.Wire() {
            public void write(byte[] data) {
            }

            public int read(byte[] buf, int timeoutMs) {
                return -1;
            }

            public void close() {
            }
        });
        ArduDfuFlow.use(rig.bus());
        Path hex = dir.resolve("arducopter_with_bl.hex");
        Files.write(hex, DfuRig.withBlHex());
        ArduSetupPages.FindPage.fileForTest = () -> hex;
        Dialogs.testAnswers = (message, title) -> {
            titles.add(title);
            if (message instanceof Container panel) {
                List<JComboBox<?>> combos = new ArrayList<>();
                collect(panel, combos);
                for (int i = 0; i < combos.get(0).getItemCount(); i++) {
                    if (String.valueOf(combos.get(0).getItemAt(i)).contains(pick)) combos.get(0).setSelectedIndex(i);
                }
                if (combos.size() > 1) combos.get(1).setSelectedIndex(1); // a file
            }
            return javax.swing.JOptionPane.OK_OPTION;
        };
        SwingUtilities.invokeAndWait(Theme::install);
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (wizard != null) wizard.closeAs("test over");
        });
        Dialogs.testAnswers = null;
        ArduSetupPages.FindPage.fileForTest = null;
        ArduDfuFlow.use(null);
        ArduUsb.use(null);
        BetaflightUsb.use(null);
    }

    private static void collect(Component c, List<JComboBox<?>> out) {
        if (c instanceof JComboBox<?> b) out.add(b);
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, out);
    }

    private void open() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ArduSetupPages.State state = new ArduSetupPages.State();
            pages = ArduSetupPages.pages(state);
            wizard = ArduSetupPages.create(null, state, pages, null);
            wizard.pack();
        });
        waitForOnSwing(() -> result().contains("No ArduPilot") || result().contains("Not found"), 10_000, "FIND's search: " + result());
    }

    private String result() {
        StringBuilder sb = new StringBuilder();
        List<Component> all = new ArrayList<>();
        walk(pages.get(0).view(), all);
        for (Component c : all) if (c instanceof JLabel l) sb.append(l.getText());
        return sb.toString();
    }

    private static void walk(Component c, List<Component> out) {
        out.add(c);
        if (c instanceof Container k) for (Component x : k.getComponents()) walk(x, out);
    }

    private AbstractButton button(String text) {
        List<Component> all = new ArrayList<>();
        walk(pages.get(0).view(), all);
        for (Component c : all) if (c instanceof AbstractButton b && text.equalsIgnoreCase(b.getText())) return b;
        throw new AssertionError(text);
    }

    @Test
    void aBoardAlreadyInDfuIsWrittenFromAFile() throws Exception {
        rig.add("USB\\VID_0483&PID_DF11\\INDFU");
        pick = "INDFU";
        open();
        SwingUtilities.invokeAndWait(() -> button("To ArduPilot (DFU)").doClick());
        waitForOnSwing(() -> result().startsWith("Done.") || result().startsWith("To ArduPilot"), 60_000, "written: " + result());
        assertTrue(result().startsWith("Done."), result());
        assertTrue(rig.running("USB\\VID_0483&PID_DF11\\INDFU"), "written and restarted");
        assertEquals(List.of("To ArduPilot", "To ArduPilot"), titles, "the pick, then the last word naming board and firmware");
        assertTrue(button("To ArduPilot (DFU)").isEnabled());
    }

    @Test
    void anArmedBetaflightBoardIsRefusedAndTheOtherInDfuIsLeftAlone() throws Exception {
        FakeFlightBoard armed = new FakeFlightBoard("COMBF1");
        armed.arm();
        bf.add(armed);
        rig.add("USB\\VID_0483&PID_DF11\\OTHER");
        pick = "COMBF1";
        open();
        SwingUtilities.invokeAndWait(() -> button("To ArduPilot (DFU)").doClick());
        waitForOnSwing(() -> result().startsWith("To ArduPilot") || result().startsWith("Done."), 30_000, "refused: " + result());
        assertTrue(result().contains("armed"), result());
        assertTrue(rig.opened().isEmpty(), "the board in DFU that wasn't picked is never opened");
        assertFalse(armed.inBootloader());
        assertFalse(armed.cli().contains("bl"), "never sent to its bootloader");
    }

    @Test
    void thePickedBetaflightBoardGoesToDfuAndIsWritten() throws Exception {
        FakeFlightBoard board = new FakeFlightBoard("COMBF2");
        bf.add(board);
        rig.add("USB\\VID_0483&PID_DF11\\OTHER");
        pick = "COMBF2";
        // its "bl": off the serial list, up in DFU
        Thread restart = new Thread(() -> {
            try {
                while (!board.cli().contains("bl")) Thread.sleep(20);
                bf.remove(board);
                rig.add("USB\\VID_0483&PID_DF11\\PICKED");
            } catch (Exception ignored) {
            }
        });
        restart.setDaemon(true);
        restart.start();
        open();
        SwingUtilities.invokeAndWait(() -> button("To ArduPilot (DFU)").doClick());
        waitForOnSwing(() -> result().startsWith("Done.") || result().startsWith("To ArduPilot"), 60_000, "written: " + result());
        assertTrue(result().startsWith("Done."), result());
        assertTrue(rig.running("USB\\VID_0483&PID_DF11\\PICKED"));
        assertEquals(List.of("USB\\VID_0483&PID_DF11\\PICKED"), rig.opened(), "OTHER was never opened");
    }

    @Test
    void fromBetaflightWithTwoBoardsReadsOnlyThePickedOne() throws Exception {
        bf.add(new FakeFlightBoard("COMBFA"));
        bf.add(new FakeFlightBoard("COMBFB"));
        pick = "COMBFB";
        open();
        SwingUtilities.invokeAndWait(() -> button("From Betaflight").doClick());
        waitForOnSwing(() -> result().contains("From Betaflight") || result().contains("Nothing") || result().contains("Betaflight:"), 30_000, result());
        assertTrue(result().contains("From Betaflight:"), result());
        assertFalse(opened.contains("COMBFA"), "the board not picked is never opened: " + opened);
        assertTrue(opened.contains("COMBFB"));
        assertTrue(button("From Betaflight").isEnabled());
    }
}
