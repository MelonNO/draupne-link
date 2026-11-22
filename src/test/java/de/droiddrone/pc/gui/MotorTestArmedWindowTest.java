package de.droiddrone.pc.gui;

import de.droiddrone.bench.hw.FakeFlightBoard;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.swing.JButton;
import javax.swing.JComponent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-077 in the motor test's window: PROPS IN / OUT, REORDER and the SPIN DIRECTION lists are grey while the board
 * says it is armed, and the switch shows a new props direction only once Betaflight has taken and saved it - it
 * turned at the press before, whatever the answer. Never shown; made-up board, no real port.
 */
@Timeout(60)
class MotorTestArmedWindowTest {
    private static final String ARMED = "The drone is armed. Disarm it first.";
    private MotorTestRig rig;
    private final List<String> asked = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        if (rig != null) rig.close();
    }

    private FakeFlightBoard open() throws Exception {
        rig = new MotorTestRig();
        FakeFlightBoard board = rig.plug("MADEUP1", 1);
        rig.open(new MotorLimits(MotorLimits.now()));
        MotorTestRig.onSwing(() -> rig.window.ask = (question, title) -> {
            asked.add(title + ": " + question);
            return true;
        });
        rig.connect();
        Waits.waitForOnSwing(() -> "false".equals(rig.saved.get(MotorTestDialog.PROPS_OUT_KEY)), 5000, "the switch to show the board's props in");
        return board;
    }

    private JButton reorder() {
        for (JButton b : MotorTestRig.all(rig.window.getContentPane(), JButton.class)) if (b.getText().equalsIgnoreCase("Reorder motors")) return b;
        throw new IllegalStateException("no reorder button");
    }

    /** A press on the switch's PROPS OUT half. */
    private void pressPropsOut() throws Exception {
        MotorTestRig.onSwing(() -> {
            JComponent s = rig.window.propsSwitch();
            s.dispatchEvent(new MouseEvent(s, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                    s.getPreferredSize().width * 3 / 4, 5, 1, false, MouseEvent.BUTTON1));
        });
    }

    private boolean[] enabled() throws Exception {
        boolean[] on = new boolean[3];
        MotorTestRig.onSwing(() -> {
            on[0] = rig.window.propsSwitch().isEnabled();
            on[1] = reorder().isEnabled();
            on[2] = rig.directions().stream().anyMatch(JComponent::isEnabled);
        });
        return on;
    }

    @Test
    void whileArmedTheWritesAreGreyAndAPressChangesNothing() throws Exception {
        FakeFlightBoard board = open();
        assertArrayEquals(new boolean[]{true, true, true}, enabled(), "disarmed: props, reorder and directions to hand");
        board.arm();
        Waits.waitForOnSwing(() -> rig.text().contains("ARMED"), 10_000, "the window to see the board armed");
        assertArrayEquals(new boolean[]{false, false, false}, enabled(), "armed: props switch, reorder and directions grey");
        pressPropsOut();
        MotorTestRig.afterMoreMotorFrames(board);
        assertEquals(List.of(), asked, "nothing asked");
        assertEquals("false", rig.saved.get(MotorTestDialog.PROPS_OUT_KEY), "the switch still shows props in");
        assertFalse(board.inMemory().contains("set yaw_motors_reversed = ON"), "the armed board's yaw is not reversed");
        board.disarm();
        Waits.waitForOnSwing(() -> rig.window.propsSwitch().isEnabled(), 10_000, "the switch back once disarmed");
    }

    /** Armed between the press and the write: the motor thread is held in a request (the test holds the board). */
    @Test
    void theSwitchTurnsOnlyOnceBetaflightTookIt() throws Exception {
        FakeFlightBoard board = open();
        Thread motorThread = MotorTestRig.motorThread();
        synchronized (board) {
            Waits.await(() -> motorThread.getState() == Thread.State.BLOCKED, 10_000, "the motor thread to wait for the board");
            pressPropsOut();
            assertEquals(1, asked.size(), "asked once: " + asked);
            assertEquals("false", rig.saved.get(MotorTestDialog.PROPS_OUT_KEY), "no answer yet: the switch still shows props in");
            board.arm();
        }
        Waits.waitForOnSwing(() -> rig.text().contains(ARMED), 10_000, "the window to say the board is armed");
        assertEquals("false", rig.saved.get(MotorTestDialog.PROPS_OUT_KEY), "refused: the switch still shows props in");
        assertFalse(board.inMemory().contains("set yaw_motors_reversed = ON"));
        board.disarm();
        Waits.waitForOnSwing(() -> rig.window.propsSwitch().isEnabled(), 10_000, "the switch back once disarmed");
        pressPropsOut();
        Waits.await(() -> board.saved().contains("set yaw_motors_reversed = ON"), 10_000, "props out saved once disarmed");
        Waits.waitForOnSwing(() -> "true".equals(rig.saved.get(MotorTestDialog.PROPS_OUT_KEY)), 10_000, "the switch to show props out once saved");
    }
}
