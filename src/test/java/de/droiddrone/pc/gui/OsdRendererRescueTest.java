package de.droiddrone.pc.gui;

import de.droiddrone.pc.FcTelemetry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I-002 (the bench's rescue-link-lost, 1.0.71): the link back during a failsafe's GPS rescue showed LINK READY, a big
 * FAILSAFE and RC OUTPUT: LIVE while the drone flew on by itself - nothing said how to take it back. Betaflight 4.4
 * ends a failsafe's rescue on roll or pitch moved past failsafe_stick_threshold (throttle never counts, yaw reads
 * centred while the rescue steers it); the virtual fleet's FlightModel ends it on a stick moved, the bench's
 * RescueScenario moves roll.
 */
class OsdRendererRescueTest {
    private static final FcTelemetry.Blocker FAILSAFE = new FcTelemetry.Blocker("FAILSAFE", "failsafe");

    private static FcTelemetry.Status status(boolean armed, List<String> modes, List<FcTelemetry.Blocker> blockers) {
        return new FcTelemetry.Status(armed, modes, blockers, 10, 40, 0);
    }

    @Test
    void aRescueWithTheLinkBackSaysWhatTakesItOver() {
        FcTelemetry.Status rescue = status(true, List.of("ARM", "FAILSAFE", "GPS RESCUE"), List.of(FAILSAFE));
        assertEquals("LINK BACK - MOVE ROLL OR PITCH TO FLY", OsdRenderer.rescueTakeOver(true, true, rescue));
        assertEquals(OsdRenderer.RESCUE_TAKE_OVER, OsdRenderer.rescueTakeOver(true, true,
                status(true, List.of("ARM", "FAILSAFE", "GPSRESCUE"), List.of(FAILSAFE))), "2025.12's box name, no space");
    }

    @Test
    void nothingIsSaidWhereAStickWouldNotTakeIt() {
        FcTelemetry.Status rescue = status(true, List.of("ARM", "FAILSAFE", "GPS RESCUE"), List.of(FAILSAFE));
        assertNull(OsdRenderer.rescueTakeOver(false, true, rescue), "the link still lost: the sticks don't reach it");
        assertNull(OsdRenderer.rescueTakeOver(true, false, rescue), "RC off here: no stick of this PC's goes out");
        assertNull(OsdRenderer.rescueTakeOver(true, true, null));
        assertNull(OsdRenderer.rescueTakeOver(true, true, status(true, List.of("ARM", "GPS RESCUE"), List.of())),
                "GPS RESCUE on its switch, no failsafe: the switch ends it");
        assertNull(OsdRenderer.rescueTakeOver(true, true, status(true, List.of("ARM", "FAILSAFE"), List.of(FAILSAFE))),
                "a failsafe's landing ends as the sticks return; no rescue to take over");
        assertNull(OsdRenderer.rescueTakeOver(true, true, status(false, List.of("FAILSAFE"), List.of(FAILSAFE))),
                "dropped: disarmed, nothing to fly");
    }
}
