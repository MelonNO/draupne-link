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

    @Test
    void anotherDeviceOnlyByChoosingIt() {
        JoystickService s = new JoystickService("tx16s", 250);
        assertEquals(RADIO, s.resolve(List.of(GAMEPAD, RADIO)), "found by the name filter");
        s.select(GAMEPAD.id());
        assertEquals(GAMEPAD, s.resolve(List.of(GAMEPAD, RADIO)));
        assertNull(s.resolve(List.of(RADIO)), "the chosen one gone: nothing");
    }
}
