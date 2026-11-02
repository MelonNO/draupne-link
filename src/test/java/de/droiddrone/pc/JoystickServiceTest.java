package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JoystickServiceTest {
    private static final Joystick.Info RADIO = new Joystick.Info(0, "Radiomaster TX16S Joystick", "0300radio");
    private static final Joystick.Info GAMEPAD = new Joystick.Info(1, "Xbox Controller", "0300xbox");

    @Test
    void aRadioThatDisappearsIsNeverReplacedByAnotherDevice() {
        JoystickService s = new JoystickService("", 250);
        assertEquals(RADIO, s.resolve(List.of(RADIO, GAMEPAD)));
        assertNull(s.resolve(List.of(GAMEPAD)), "radio unplugged: no input, not the gamepad");
        Joystick.Info back = new Joystick.Info(3, RADIO.name(), RADIO.guid());
        assertEquals(back, s.resolve(List.of(GAMEPAD, back)), "the same radio back, under another id");
    }

    /** The slowest GLFW event pump is kept per 100 ms window, and the window before stays until the next rolls. */
    @Test
    void theSlowestPollOfTheLastWindowsIsKept() {
        JoystickService s = new JoystickService("", 250);
        assertEquals(0, s.getPollMaxMs());
        s.notePoll(200_000, 1000);        // 0.2 ms
        s.notePoll(12_400_000, 1004);     // a USB plug event: 12.4 ms
        s.notePoll(300_000, 1008);
        assertEquals(13, s.getPollMaxMs(), "rounded up");
        s.notePoll(100_000, 1100);        // the next window: the slow one is still in the last
        assertEquals(13, s.getPollMaxMs());
        s.notePoll(100_000, 1200);        // two windows on: gone
        assertEquals(1, s.getPollMaxMs());
    }

    @Test
    void anotherDeviceOnlyByChoosingIt() {
        JoystickService s = new JoystickService("tx16s", 250);
        assertEquals(RADIO, s.resolve(List.of(GAMEPAD, RADIO)), "found by the name filter");
        s.select(GAMEPAD.id());
        assertEquals(GAMEPAD, s.resolve(List.of(GAMEPAD, RADIO)));
        assertNull(s.resolve(List.of(RADIO)), "the chosen one gone: nothing");
    }
}
