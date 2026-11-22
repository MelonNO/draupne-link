package de.droiddrone.pc.gui;

import de.droiddrone.bench.hw.FakeFlightBoard;
import de.droiddrone.pc.setup.SetupBoard;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-060: the motor test opened the first Betaflight board that answered, whatever board Drone setup showed. With two
 * flight controllers on USB the pilot took the props off A and ticked the box - and B's motors turned, or B's motor
 * order, props direction or ESC direction was saved. Here the window is for one board, by USB port and by id (the
 * one Drone setup's CONFIRM is for): another board is asked what it is and sent nothing else; opened for no board it
 * does not choose between two. And it says, when closed, whether a motor was turned in it. Never shown; no real port.
 */
@Timeout(60)
class MotorTestBoardTest {
    private static final int RAISED = 1050;
    /** MSP_FC_VARIANT and MSP_UID: what a board is asked to say what it is. */
    private static final Set<Integer> WHAT_IT_IS = Set.of(2, 160);
    private MotorTestRig rig = new MotorTestRig();

    @AfterEach
    void close() throws Exception {
        rig.close();
    }

    private static MotorLimits limits() {
        return new MotorLimits(MotorLimits.now());
    }

    private void raiseMotor1() throws Exception {
        MotorTestRig.onSwing(() -> rig.window.sliders.get(0).set(RAISED));
    }

    @Test
    void withTwoBoardsOnUsbOnlyTheOneDroneSetupReadIsOpenedAndTurned() throws Exception {
        FakeFlightBoard a = rig.plug("MADEUP1", 1), b = rig.plug("MADEUP2", 2); // A comes first in the port list
        rig.open(limits(), new SetupBoard("MADEUP2", MotorTestRig.id(2)));
        rig.connectAndTick();
        raiseMotor1();
        Waits.await(() -> b.motors()[0] == RAISED, 5000, "board B's motor 1 to turn");
        assertEquals(0, a.opens, "board A's port is not opened");
        assertEquals(List.of(), a.mspRequests(), "and board A is asked nothing");
        assertTrue(rig.usbRow().startsWith("OK: MADEUP2"), rig.usbRow());
    }

    /** The pilot swapped drones on the cable after Drone setup read the first: the same port, another board. */
    @Test
    void anotherBoardOnDroneSetupsPortIsRefusedAndItsMotorsAreSentNothing() throws Exception {
        FakeFlightBoard swapped = rig.plug("MADEUP1", 2);
        rig.open(limits(), new SetupBoard("MADEUP1", MotorTestRig.id(1)));
        Waits.await(() -> swapped.opens >= 2, 15_000, "the motor test to look at the port again by itself");
        assertTrue(WHAT_IT_IS.containsAll(swapped.mspRequests()),
                "the other board is asked what it is and nothing else - no motor value, not even STOP: " + swapped.mspRequests());
        assertEquals("BAD: MADEUP1: not the board Drone setup read. Nothing sent. Plug that one in, or Back and Read again.", rig.usbRow());
        assertFalse(rig.box().isEnabled(), "the box can't be ticked");
        assertTrue(MotorTestRig.allStopped(swapped));
    }

    @Test
    void whileDroneSetupsBoardIsNotPluggedInNoOtherBoardIsOpened() throws Exception {
        FakeFlightBoard other = rig.plug("MADEUP1", 1);
        rig.open(limits(), new SetupBoard("MADEUP2", MotorTestRig.id(2)));
        Waits.waitFor(() -> rig.usbRow().equals("WARN: Drone setup's board is not on MADEUP2. Plug it in, or Back and Read again."),
                10_000, "the window to say Drone setup's board is not there");
        assertEquals(0, other.opens, "the board that is there is not opened");
        FakeFlightBoard read = rig.plug("MADEUP2", 2); // plugged in: the motor test goes on by itself
        rig.connectAndTick();
        raiseMotor1();
        Waits.await(() -> read.motors()[0] == RAISED, 5000, "Drone setup's board's motor 1 to turn");
        assertEquals(0, other.opens);
    }

    @Test
    void openedForNoBoardItDoesNotChooseBetweenTwo() throws Exception {
        FakeFlightBoard a = rig.plug("MADEUP1", 1), b = rig.plug("MADEUP2", 2);
        rig.open(limits());
        Waits.waitFor(() -> rig.usbRow().equals("BAD: 2 flight controllers on USB. Unplug all but one."), 10_000,
                "the window to refuse to choose");
        assertFalse(rig.box().isEnabled(), "the box can't be ticked");
        assertFalse(a.mspRequests().contains(MotorTestRig.SET_MOTOR), "board A's motors are sent nothing: " + a.mspRequests());
        assertFalse(b.mspRequests().contains(MotorTestRig.SET_MOTOR), "board B's motors are sent nothing: " + b.mspRequests());
        rig.usb.unplug("MADEUP2"); // one left: no choice to make
        b.unplug();
        rig.connectAndTick();
        raiseMotor1();
        Waits.await(() -> a.motors()[0] == RAISED, 5000, "the one board's motor 1 to turn");
        assertFalse(b.mspRequests().contains(MotorTestRig.SET_MOTOR));
    }

    @Test
    void closedWithNoMotorTurnedItSaysSo() throws Exception {
        FakeFlightBoard board = rig.plug("MADEUP1", 1);
        rig.open(limits(), new SetupBoard("MADEUP1", MotorTestRig.id(1)));
        List<Boolean> turned = new CopyOnWriteArrayList<>();
        MotorTestRig.onSwing(() -> rig.window.whenClosed(turned::add));
        rig.connectAndTick(); // the box ticked, no slider moved
        MotorTestRig.afterMoreMotorFrames(board); // STOP was sent, again and again
        MotorTestRig.onSwing(() -> rig.window.dispose());
        Waits.await(() -> !turned.isEmpty(), 5000, "the window to say it is closed");
        assertEquals(List.of(false), turned, "opened, the box ticked, closed: no motor was turned");
    }

    @Test
    void closedAfterAMotorTurnedItSaysSo() throws Exception {
        FakeFlightBoard board = rig.plug("MADEUP1", 1);
        rig.open(limits(), new SetupBoard("MADEUP1", MotorTestRig.id(1)));
        List<Boolean> turned = new CopyOnWriteArrayList<>();
        MotorTestRig.onSwing(() -> rig.window.whenClosed(turned::add));
        rig.connectAndTick();
        raiseMotor1();
        Waits.await(() -> board.motors()[0] == RAISED, 5000, "motor 1 to turn");
        MotorTestRig.onSwing(() -> rig.window.dispose());
        Waits.await(() -> !turned.isEmpty(), 5000, "the window to say it is closed");
        assertEquals(List.of(true), turned);
        assertTrue(MotorTestRig.allStopped(board), "and closing stopped it");
    }
}
