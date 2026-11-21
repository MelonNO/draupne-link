package de.droiddrone.pc.gui;

import de.droiddrone.bench.hw.FakeFlightBoard;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-064: a motor's spin direction is kept by its ESC and can't be read back, and it was saved on a pick in a list
 * alone - no props-off tick, no question; and a change still waiting when the board was lost was made on the next
 * board that opened. Here: only with the box ticked and after one question that names the motor and the direction,
 * and never on another board than the one it was asked of. Never shown; no real port.
 */
@Timeout(60)
class MotorTestDirectionTest {
    private static final int AS_IS = 0, NORMAL = 1, REVERSED = 2;
    private MotorTestRig rig;
    /** The questions the window asked, and what the pilot answers. */
    private final List<String> asked = new ArrayList<>();
    private volatile boolean yes;

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
            return yes;
        });
        rig.connect();
        return board;
    }

    private void pick(int motor, int choice) throws Exception {
        MotorTestRig.onSwing(() -> rig.directions().get(motor - 1).setSelectedIndex(choice));
    }

    private int picked(int motor) throws Exception {
        int[] index = new int[1];
        MotorTestRig.onSwing(() -> index[0] = rig.directions().get(motor - 1).getSelectedIndex());
        return index[0];
    }

    @Test
    void aPickInTheListAloneSavesNothing() throws Exception {
        FakeFlightBoard board = open();
        yes = true;
        pick(2, REVERSED); // the box "Propellers removed" is not ticked
        MotorTestRig.afterMoreMotorFrames(board);
        assertEquals(List.of(), board.dshotCommands(), "nothing is sent to an ESC without the box ticked");
        assertEquals(List.of(), asked, "and nothing is asked");
        assertEquals(AS_IS, picked(2), "the list is back at As is");
        assertTrue(rig.text().contains("Tick the box first."), rig.text());
    }

    @Test
    void theQuestionNamesTheMotorAndTheDirectionAndNoSavesNothing() throws Exception {
        FakeFlightBoard board = open();
        MotorTestRig.onSwing(() -> rig.box().doClick());
        yes = false;
        pick(2, REVERSED);
        assertEquals(List.of("Spin direction: Set motor 2 to reversed?\nSaved in its ESC."), asked);
        pick(3, NORMAL);
        assertEquals("Spin direction: Set motor 3 to normal?\nSaved in its ESC.", asked.get(1));
        MotorTestRig.afterMoreMotorFrames(board);
        assertEquals(List.of(), board.dshotCommands(), "No: nothing is sent to an ESC");
        assertEquals(AS_IS, picked(2));
        assertEquals(AS_IS, picked(3));
        assertTrue(rig.text().contains("Motor 3 direction not changed."), rig.text());
    }

    @Test
    void withTheBoxTickedAndYesTheDirectionIsSavedInThatMotorsEsc() throws Exception {
        FakeFlightBoard board = open();
        MotorTestRig.onSwing(() -> rig.box().doClick());
        yes = true;
        pick(2, REVERSED);
        Waits.await(() -> board.dshotCommands().equals(List.of("motor 2: 8 12")), 10_000, "motor 2 reversed and saved: " + board.dshotCommands());
        pick(3, NORMAL);
        Waits.await(() -> board.dshotCommands().equals(List.of("motor 2: 8 12", "motor 3: 7 12")), 10_000, "motor 3 normal and saved: " + board.dshotCommands());
        assertEquals(2, asked.size(), "one question each");
        assertEquals(REVERSED, picked(2));
        Waits.waitForOnSwing(() -> rig.text().contains("Motor 3 set to normal, saved. Spin it to check."), 5000, "the window to say it is saved");
        assertTrue(rig.log.stream().anyMatch(l -> l.contains("motor 2 spin direction") && l.contains("saved in its ESC")), rig.log.toString());
    }

    /**
     * The board goes while a direction asked of it still waits for the motor thread: that thread is held in a send
     * to board A (the test holds the made-up board, which then answers nothing), the direction is picked, A's cable
     * comes out and board B goes in. B must never get what was asked of A.
     */
    @Test
    void aDirectionAskedOfABoardThatIsLostNeverReachesTheNextBoard() throws Exception {
        FakeFlightBoard a = open();
        MotorTestRig.onSwing(() -> rig.box().doClick());
        yes = true;
        Thread motorThread = MotorTestRig.motorThread();
        FakeFlightBoard b;
        synchronized (a) {
            Waits.await(() -> motorThread.getState() == Thread.State.BLOCKED, 10_000, "the motor thread to wait for board A");
            pick(2, REVERSED); // for the window board A is still there
            a.unplug();
            rig.usb.unplug("MADEUP1");
            b = rig.plug("MADEUP2", 2);
        }
        FakeFlightBoard other = b;
        Waits.await(() -> MotorTestRig.motorFrames(other) > 0, 15_000, "the motor test to open board B");
        MotorTestRig.afterMoreMotorFrames(other);
        assertEquals(List.of(), other.dshotCommands(), "board B is sent nothing that was asked of board A");
        assertEquals(List.of(), a.dshotCommands());
        Waits.waitForOnSwing(() -> rig.text().contains("Flight controller lost: nothing saved."), 5000, "the window to say so");
    }
}
