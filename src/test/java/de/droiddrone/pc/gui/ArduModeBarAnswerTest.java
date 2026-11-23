package de.droiddrone.pc.gui;

import de.droiddrone.pc.ArduModes;
import de.droiddrone.pc.FcTelemetry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet AP-6: the mode button turns red only for the FC's answer to that press - not for a refusal from before it, not
 * for the route window's GO, not for the phone's own mode change long after.
 */
class ArduModeBarAnswerTest {
    private static final FcTelemetry.Status ARDU = new FcTelemetry.Status(true, List.of("ARM"), List.of(), 0, 0, 0, true, "LOITER");

    @Test
    void aRefusalFromBeforeThePressIsNotThePresssAnswer() {
        ArduModeBar bar = new ArduModeBar(c -> true);
        FcTelemetry.CommandAck old = new FcTelemetry.CommandAck(ArduModeBar.CMD_SET_MODE, 4, 1_000);
        bar.update(ARDU, ArduModes.LOITER, old, 60_000); // refused an hour ago, nothing pressed since
        bar.press(ArduModeBar.Action.RTL);
        bar.update(ARDU, ArduModes.LOITER, old, 60_100);
        assertNull(bar.refusedNow(), "the old refusal is not RTL's");
        bar.update(ARDU, ArduModes.LOITER, new FcTelemetry.CommandAck(ArduModeBar.CMD_SET_MODE, 4, 60_200), 60_200);
        assertEquals(ArduModeBar.Action.RTL, bar.refusedNow(), "its own refusal is");
    }

    @Test
    void theRouteWindowsGoRefusedIsNotTheLastButtons() {
        ArduModeBar bar = new ArduModeBar(c -> true);
        bar.update(ARDU, ArduModes.LOITER, null, 0);
        bar.press(ArduModeBar.Action.LAND);
        bar.update(ARDU, ArduModes.LOITER, new FcTelemetry.CommandAck(ArduModeBar.CMD_MISSION_START, 4, 100), 100);
        assertNull(bar.refusedNow(), "MISSION_START refused: not LAND's answer");
        bar.update(ARDU, ArduModes.LOITER, new FcTelemetry.CommandAck(ArduModeBar.CMD_SET_MODE, 4, 100 + ArduModeBar.ANSWER_MS + 1),
                100 + ArduModeBar.ANSWER_MS + 1);
        assertNull(bar.refusedNow(), "a mode refused long after the press: the phone's own, not LAND's");
    }
}
