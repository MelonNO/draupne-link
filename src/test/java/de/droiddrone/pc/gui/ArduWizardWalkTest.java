package de.droiddrone.pc.gui;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.ardu.ArduUsb;
import de.droiddrone.pc.setup.ardu.FakeArduPilot;
import de.droiddrone.pc.setup.ardu.FromBetaflight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static de.droiddrone.testing.Waits.waitForOnSwing;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet area H through the real guide: SETUP > ArduPilot drone setup, page by page against a made-up ArduPilot on
 * made-up USB - FIND, SETTINGS, PORTS, FRAME AND TUNING, CALIBRATE, MOTORS, BENCH - with its buttons pressed as the
 * pilot does: a board pulled out mid-write, a parameter this firmware lacks, a read-back that differs, CONFIRM twice,
 * Back and another board, a port that needs REPLACE, AUTOTUNE on the mode switch, ARM and the modes on one AUX, a close
 * mid-write, two boards, an armed board. The window is never shown; no real port is touched.
 */
class ArduWizardWalkTest {
    @TempDir
    Path home;
    private String oldHome;
    private final List<FakeArduPilot> boards = new ArrayList<>();
    private volatile FakeArduPilot onUsb;
    private WizardDialog wizard;
    private ArduSetupPages.State state;
    private List<WizardDialog.Page> pages;
    private final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); // the backups go here, not into the Documents of whoever runs this
        // the made-up USB: one port, the board on it now
        BetaflightUsb.use(new BetaflightUsb.Usb() {
            public List<BetaflightUsb.Port> ports() {
                return List.of(new BetaflightUsb.Port("COMFAKE1", "ArduPilot (made up)"));
            }

            public BetaflightUsb.Wire open(BetaflightUsb.Port port) throws java.io.IOException {
                throw new java.io.IOException("no Betaflight here");
            }
        });
        ArduUsb.use(p -> onUsb);
        Dialogs.testAnswers = (message, title) -> {
            asked.add(title);
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
        ArduUsb.use(null);
        BetaflightUsb.use(null);
        for (FakeArduPilot b : boards) b.close();
        System.setProperty("user.home", oldHome);
    }

    /** A made-up Copter as an earlier setup left it: ARM on AUX1 (RC5), GPS on SERIAL3, RC input on SERIAL4. */
    private FakeArduPilot board() {
        FakeArduPilot b = new FakeArduPilot();
        b.params.put("RC5_OPTION", 153f);
        for (int n = 6; n <= 8; n++) b.params.put("RC" + n + "_OPTION", 0f);
        b.params.put("RCMAP_ROLL", 1f);
        b.params.put("RCMAP_PITCH", 2f);
        b.params.put("RCMAP_THROTTLE", 3f);
        b.params.put("RCMAP_YAW", 4f);
        b.params.put("SERIAL0_PROTOCOL", 2f);
        b.params.put("SERIAL1_PROTOCOL", 2f);
        b.params.put("SERIAL1_BAUD", 57f);
        b.params.put("SERIAL3_PROTOCOL", 5f);
        b.params.put("SERIAL4_PROTOCOL", 23f);
        b.params.put("SERIAL4_BAUD", 115f);
        b.params.put("FRAME_CLASS", 1f);
        b.params.put("FRAME_TYPE", 1f);
        b.params.put("AUTOTUNE_AXES", 7f);
        boards.add(b);
        return b;
    }

    /** The guide opened as SETUP opens it, never shown; the radio's ARM on AUX3, its modes on AUX4. */
    private void open(int armAux, int modeAux) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            state = new ArduSetupPages.State();
            state.radioArmAux = armAux;
            state.radioModeAux = modeAux;
            pages = ArduSetupPages.pages(state);
            wizard = ArduSetupPages.create(null, state, pages, () -> asked.add("finished"));
            wizard.pack();
        });
    }

    private static <T extends Component> List<T> all(Component root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Component> void collect(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, type, out);
    }

    private static AbstractButton button(Component root, String text) {
        for (AbstractButton b : all(root, AbstractButton.class)) if (text.equalsIgnoreCase(b.getText())) return b;
        throw new AssertionError("no button " + text);
    }

    private AbstractButton footer(String text) {
        for (AbstractButton b : all(wizard.getContentPane(), AbstractButton.class)) {
            if (text.equalsIgnoreCase(b.getText()) && !isInPage(b)) return b;
        }
        throw new AssertionError("no footer button " + text);
    }

    private boolean isInPage(Component c) {
        for (WizardDialog.Page p : pages) if (SwingUtilities.isDescendingFrom(c, p.view())) return true;
        return false;
    }

    private WizardDialog.Page page(int i) {
        return pages.get(i);
    }

    private static String text(WizardDialog.Page p) {
        StringBuilder sb = new StringBuilder();
        for (JTextArea a : all(p.view(), JTextArea.class)) sb.append(a.getText());
        for (javax.swing.JLabel l : all(p.view(), javax.swing.JLabel.class)) sb.append(l.getText()).append('\n');
        return sb.toString();
    }

    private void click(AbstractButton b) throws Exception {
        SwingUtilities.invokeAndWait(b::doClick);
    }

    private boolean nextOn() {
        return footer("NEXT").isEnabled();
    }

    private void found() throws Exception {
        waitForOnSwing(() -> state.found != null && nextOn(), 10_000, "the board found");
    }

    private void next() throws Exception {
        waitForOnSwing(this::nextOn, 5000, "NEXT on");
        click(footer("NEXT"));
    }

    private static int ends(WizardDialog.Page p) {
        String t = text(p);
        return t.split("Done:", -1).length + t.split("STOPPED", -1).length - 2;
    }

    private void confirmAndWait(WizardDialog.Page p) throws Exception {
        AbstractButton confirm = button(p.view(), "CONFIRM");
        waitForOnSwing(confirm::isEnabled, 10_000, "CONFIRM on: " + text(p));
        int before = ends(p);
        click(confirm);
        waitForOnSwing(() -> ends(p) > before, 15_000, "the write over: " + text(p));
    }

    // ------------------------------------------------------------------ the whole walk

    @Test
    void everyPageAsThePilotWalksIt() throws Exception {
        FakeArduPilot fc = board();
        onUsb = fc;
        open(3, 4);
        found();

        // SETTINGS: the plan, the parameter this firmware lacks, ARM moved to AUX3 takes the old ARM switch off
        next();
        WizardDialog.Page settings = page(1);
        AbstractButton confirm = button(settings.view(), "CONFIRM");
        waitForOnSwing(() -> text(settings).contains("RC7_OPTION 0 -> 153"), 10_000, "the plan: " + text(settings));
        String plan = text(settings);
        assertTrue(plan.contains("NOT SET: ADSB_TYPE - not on this board"), plan); // never silent: said first
        assertTrue(plan.contains("RC5_OPTION 153 -> 0"), "the old ARM switch (AUX1) taken off: " + plan);
        assertTrue(plan.contains("FLTMODE_CH 5 -> 8"), plan);
        assertFalse(settings.canContinue(), "NEXT waits for CONFIRM");
        // CONFIRM pressed twice: one write
        SwingUtilities.invokeAndWait(() -> {
            confirm.doClick();
            confirm.doClick();
        });
        waitForOnSwing(() -> text(settings).contains("Done:"), 15_000, "written: " + text(settings));
        waitForOnSwing(() -> !confirm.isEnabled() && nextOn(), 15_000, "read again: nothing left, CONFIRM grey");
        int once = fc.sets;
        click(confirm);
        Thread.sleep(300);
        assertEquals(once, fc.sets, "a CONFIRM after the write writes nothing again");
        assertFalse(fc.listAskedOnSwing, "the parameters are read again off Swing's thread");
        assertEquals(153f, fc.params.get("RC7_OPTION"), "ARM on AUX3");
        assertEquals(0f, fc.params.get("RC5_OPTION"), "the old ARM switch off");
        assertEquals(8f, fc.params.get("FLTMODE_CH"), "the modes on AUX4");
        assertEquals(2f, fc.params.get("FLTMODE4"), "a 3-position switch's middle: ALT HOLD");
        assertFalse(fc.params.containsKey("ADSB_TYPE"));

        // PORTS: the GPS where it is; the radio on SERIAL4 (RC input) is blocked until REPLACE
        next();
        ArduSetupPages.PortsPage ports = (ArduSetupPages.PortsPage) page(2);
        waitForOnSwing(() -> ports.canContinue(), 10_000, "nothing to change at first: " + text(ports));
        @SuppressWarnings("unchecked") JComboBox<String> gps = all(ports.view(), JComboBox.class).get(0);
        @SuppressWarnings("unchecked") JComboBox<String> radio = all(ports.view(), JComboBox.class).get(1);
        assertEquals(3, gps.getSelectedIndex(), "the GPS where it is now");
        // "Not set here" for the GPS is the pilot's own choice: it isn't set back on the next change
        SwingUtilities.invokeAndWait(() -> gps.setSelectedIndex(0));
        SwingUtilities.invokeAndWait(() -> radio.setSelectedIndex(3)); // the radio where the GPS was
        assertEquals(0, gps.getSelectedIndex(), "the GPS choice stays as the pilot set it");
        assertTrue(ports.blocked.stream().noneMatch(b -> b.contains("one port")), ports.blocked.toString());
        SwingUtilities.invokeAndWait(() -> gps.setSelectedIndex(3));
        SwingUtilities.invokeAndWait(() -> radio.setSelectedIndex(4));
        assertFalse(ports.blocked.isEmpty(), text(ports));
        assertFalse(button(ports.view(), "CONFIRM").isEnabled());
        assertFalse(ports.canContinue(), "blocked: no NEXT");
        click(button(ports.view(), "REPLACE what a chosen port does now"));
        assertTrue(ports.blocked.isEmpty());
        confirmAndWait(ports);
        assertTrue(text(ports).contains("Done:"), text(ports));
        assertEquals(2f, fc.params.get("SERIAL4_PROTOCOL"), "the radio on SERIAL4");
        assertEquals(57f, fc.params.get("SERIAL4_BAUD"));
        waitForOnSwing(this::nextOn, 3000, "written: NEXT");

        // FRAME AND TUNING: AUTOTUNE on position 4, then moved, then off - the switch gets its modes back
        next();
        ArduSetupPages.TuningPage tuning = (ArduSetupPages.TuningPage) page(3);
        waitForOnSwing(() -> tuning.changes.stream().anyMatch(c -> c.name().equals("FRAME_TYPE")), 10_000, "Betaflight X: " + text(tuning));
        JCheckBox autotune = all(tuning.view(), JCheckBox.class).get(0);
        JSpinner position = all(tuning.view(), JSpinner.class).get(0);
        click(autotune);
        assertTrue(tuning.changes.stream().anyMatch(c -> c.name().equals("FLTMODE4") && c.to() == 15f), tuning.changes.toString());
        confirmAndWait(tuning);
        assertEquals(15f, fc.params.get("FLTMODE4"));
        SwingUtilities.invokeAndWait(() -> position.setValue(1));
        assertFalse(tuning.blocked.isEmpty(), "position 1: it can't arm in AUTOTUNE");
        assertFalse(button(tuning.view(), "CONFIRM").isEnabled());
        SwingUtilities.invokeAndWait(() -> position.setValue(6));
        assertFalse(tuning.blocked.isEmpty(), "position 6 is LOITER");
        SwingUtilities.invokeAndWait(() -> position.setValue(2));
        assertTrue(tuning.blocked.isEmpty());
        assertTrue(tuning.changes.stream().anyMatch(c -> c.name().equals("FLTMODE2") && c.to() == 15f), tuning.changes.toString());
        assertTrue(tuning.changes.stream().anyMatch(c -> c.name().equals("FLTMODE4") && c.to() == 2f),
                "AUTOTUNE moved: position 4 gets ALT HOLD back " + tuning.changes);
        assertFalse(tuning.canContinue(), "a changed choice after CONFIRM needs CONFIRM again");
        click(autotune);
        assertTrue(tuning.changes.stream().anyMatch(c -> c.name().equals("FLTMODE4") && c.to() == 2f),
                "AUTOTUNE off: position 4 gets ALT HOLD back " + tuning.changes);
        assertTrue(tuning.changes.stream().noneMatch(c -> c.name().equals("FLTMODE2")));
        confirmAndWait(tuning);
        assertEquals(2f, fc.params.get("FLTMODE4"), "no AUTOTUNE left on the switch");
        assertEquals(12f, fc.params.get("FRAME_TYPE"));

        // CALIBRATE: six positions, LEVEL, COMPASS
        next();
        WizardDialog.Page cal = page(4);
        AbstractButton placed = button(cal.view(), "DONE - IT IS SO");
        click(button(cal.view(), "ACCELEROMETER"));
        assertFalse(button(cal.view(), "LEVEL").isEnabled(), "no LEVEL in the middle of the six positions");
        for (int i = 0; i < 6; i++) {
            waitForOnSwing(placed::isEnabled, 5000, "position " + (i + 1) + " asked");
            click(placed);
        }
        waitForOnSwing(() -> text(cal).contains("accelerometer: done"), 5000, text(cal));
        click(button(cal.view(), "LEVEL"));
        click(button(cal.view(), "COMPASS"));
        waitForOnSwing(() -> text(cal).contains("level: done") && text(cal).contains("compass: turn it round"), 5000, text(cal));

        // MOTORS: A to D, one each
        next();
        WizardDialog.Page motors = page(5);
        for (String m : new String[]{"A", "B", "C", "D"}) click(button(motors.view(), m));
        waitForOnSwing(() -> text(motors).split("spinning 1 s", -1).length == 5, 8000, text(motors));
        assertEquals(4, fc.commands.stream().filter(c -> c == 209).count(), "four motor tests");

        // BENCH: ARM where SETTINGS put it (AUX3, channel 7), not AUX1
        next();
        WizardDialog.Page bench = page(6);
        assertEquals("FINISH", footer("FINISH").getText());
        AbstractButton armTest = button(bench.view(), "ARM TEST");
        SwingUtilities.invokeAndWait(() -> {
            armTest.doClick();
            armTest.doClick(); // a second press while it runs: nothing
        });
        waitForOnSwing(() -> text(bench).contains("OK.") || text(bench).contains("NOT"), 15_000, text(bench));
        assertTrue(text(bench).contains("channel 7"), text(bench));
        assertTrue(text(bench).contains("Armed on the overrides, and disarmed again: OK."), text(bench));
        assertEquals(1, text(bench).split("ARM switch up", -1).length - 1, "one test for two presses");
        click(footer("FINISH"));
        assertTrue(asked.contains("finished"));
        waitForOnSwing(() -> state.found == null, 3000, "the board let go");
    }

    // ------------------------------------------------------------------ what goes wrong

    @Test
    void anotherBoardAfterBackStartsAfresh() throws Exception {
        FakeArduPilot first = board();
        onUsb = first;
        open(1, 2);
        found();
        next();
        confirmAndWait(page(1));
        waitForOnSwing(this::nextOn, 15_000, "first board written");
        // Back, another board on USB, Search again
        FakeArduPilot second = board();
        onUsb = second;
        click(footer("Back"));
        first.close();
        click(button(page(0).view(), "Search again"));
        waitForOnSwing(() -> state.found != null && state.fc().fcSysId() >= 0 && nextOn(), 10_000, "the second board");
        int board = state.board;
        next();
        waitForOnSwing(() -> button(page(1).view(), "CONFIRM").isEnabled(), 10_000, "its plan");
        assertFalse(page(1).canContinue(), "written on the first board, not on this one: CONFIRM first");
        assertEquals(board, state.board);
        confirmAndWait(page(1));
        assertEquals(153f, second.params.get("RC5_OPTION"));
        assertEquals(0f, second.params.get("FS_THR_ENABLE"));
    }

    @Test
    void aBoardPulledOutMidWriteStops() throws Exception {
        FakeArduPilot fc = board();
        fc.unplugAfterSets = 3;
        onUsb = fc;
        open(1, 2);
        found();
        next();
        confirmAndWait(page(1));
        assertTrue(text(page(1)).contains("STOPPED"), text(page(1)));
        assertFalse(page(1).canContinue());
        assertTrue(button(page(1).view(), "CONFIRM").isEnabled(), "CONFIRM again once it is back");
    }

    @Test
    void aReadBackThatDiffersStopsThere() throws Exception {
        FakeArduPilot fc = board();
        fc.stuck = "RTL_ALT";
        onUsb = fc;
        open(1, 2);
        found();
        next();
        confirmAndWait(page(1));
        assertTrue(text(page(1)).contains("RTL_ALT reads back 1500, not 3000: stopped."), text(page(1)));
        assertFalse(page(1).canContinue());
        assertEquals(0f, fc.params.get("BATT_ARM_MAH"), "before it: written");
        assertEquals(1000f, fc.params.get("WPNAV_SPEED"), "after it: nothing");
    }

    @Test
    void anArmedBoardIsNotWritten() throws Exception {
        FakeArduPilot fc = board();
        fc.armed = true;
        onUsb = fc;
        open(1, 2);
        found();
        next();
        confirmAndWait(page(1));
        assertTrue(text(page(1)).contains("armed"), text(page(1)));
        assertEquals(0, fc.sets);
        fc.armed = false;
        fc.armAfterSets = 2;
        Thread.sleep(400); // a disarmed heartbeat
        confirmAndWait(page(1));
        assertTrue(text(page(1)).contains("The drone armed during setup: stopped"), text(page(1)));
        assertEquals(2, fc.sets, "nothing after it armed");
    }

    @Test
    void twoBoardsOnUsbAreRefused() throws Exception {
        FakeArduPilot a = board(), b = board();
        BetaflightUsb.use(new BetaflightUsb.Usb() {
            public List<BetaflightUsb.Port> ports() {
                return List.of(new BetaflightUsb.Port("COMFAKE1", "ArduPilot A"), new BetaflightUsb.Port("COMFAKE2", "ArduPilot B"));
            }

            public BetaflightUsb.Wire open(BetaflightUsb.Port port) throws java.io.IOException {
                throw new java.io.IOException("no Betaflight here");
            }
        });
        ArduUsb.use(p -> p.systemName().equals("COMFAKE1") ? a : b);
        open(1, 2);
        waitForOnSwing(() -> text(page(0)).contains("Several ArduPilot boards"), 10_000, text(page(0)));
        assertNull(state.found);
        assertFalse(page(0).canContinue());
    }

    @Test
    void closedMidWriteTheBoardIsLetGo() throws Exception {
        FakeArduPilot fc = board();
        onUsb = fc;
        open(1, 2);
        found();
        next();
        AbstractButton confirm = button(page(1).view(), "CONFIRM");
        waitForOnSwing(confirm::isEnabled, 10_000, "the plan");
        click(confirm);
        waitForOnSwing(() -> fc.sets > 0, 10_000, "writing");
        SwingUtilities.invokeAndWait(() -> footer("Close").doClick());
        wizard = null;
        int at = fc.sets;
        Thread.sleep(800);
        assertTrue(fc.sets <= at + 1, "no writes after the close: " + at + " then " + fc.sets);
        assertNull(state.found);
    }

    @Test
    void armFromBetaflightOnTheRadiosModeAuxGetsTheModesBesideIt() throws Exception {
        AtomicReference<de.droiddrone.pc.setup.ardu.ArduPlan.Choices> c = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ArduSetupPages.State s = new ArduSetupPages.State();
            s.radioArmAux = 1;
            s.radioModeAux = 2;
            s.carried = new FromBetaflight.Carried(null, null, 2, null, null, null, List.of());
            ArduSetupPages.SettingsPage p = new ArduSetupPages.SettingsPage(s);
            p.view();
            c.set(p.choices());
        });
        assertEquals(2, c.get().armAux(), "ARM where Betaflight had it");
        assertNotEquals(c.get().armAux(), c.get().modeAux(), "never ARM and the modes on one AUX");
        var w = de.droiddrone.pc.setup.ardu.ArduPlan.wanted(new de.droiddrone.pc.setup.ardu.ArduPlan.Choices(2, 4, 3000, 3, 3,
                de.droiddrone.pc.setup.ardu.ArduPlan.DEFAULT_MODES, 30, 10, 2.5, 1.5, true));
        assertNotEquals(w.get("RC7_OPTION").value(), 0f);
        assertNotEquals(7f, w.get("FLTMODE_CH").value(), "the plan itself never puts both on one channel");
    }
}
