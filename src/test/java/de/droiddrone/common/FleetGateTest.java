package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static de.droiddrone.common.FleetGate.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Whether the phone takes a PC's FLEET_MODE (moved out of the phone's Udp). Written from the code as it stood before
 * the move - Udp.onFleetMode in Udp.java of phone app pc.52 (commit 2047470), lines 1446-1457; each test names the
 * lines it pins.
 */
class FleetGateTest {
    private static final BooleanSupplier DISARMED = () -> true, NOT_DISARMED = () -> false;
    private static final BooleanSupplier NOT_ASKED = () -> fail("the FC is not asked here");

    /** Line 1447: r.token == fleetToken returns before anything else - no log line, no FC read, a pilot connected or not. */
    @Test
    void aTokenIsTakenOnce() {
        assertEquals(REPEAT, FleetGate.request(77, 77, false, NOT_ASKED));
        assertEquals(REPEAT, FleetGate.request(77, 77, true, NOT_ASKED), "the PC's repeats while a pilot flies: nothing said");
        assertEquals(REPEAT, FleetGate.request(-5, -5, false, NOT_ASKED), "a token is an i32: negative ones too");
    }

    /** Lines 1449-1452: isConnected() refuses ("a pilot is connected") before the FC is asked; the token is not taken. */
    @Test
    void refusedWhileAPilotIsConnected() {
        assertEquals(REFUSED_PILOT, FleetGate.request(78, 77, true, NOT_ASKED));
        assertEquals(REFUSED_PILOT, FleetGate.request(78, 0, true, NOT_ASKED), "the first one ever too");
    }

    /** Lines 1453-1456: FcArmedState != DISARMED (armed or unknown) refuses ("FC not known disarmed"). */
    @Test
    void refusedUnlessTheFcIsKnownDisarmed() {
        assertEquals(REFUSED_ARMED, FleetGate.request(78, 77, false, NOT_DISARMED));
    }

    /** Line 1457 on: a new token, no pilot, the FC disarmed - taken (the first: the last token is 0, none). */
    @Test
    void aNewTokenIsTakenFromAnIdleDisarmedPhone() {
        assertEquals(TAKE, FleetGate.request(78, 77, false, DISARMED));
        assertEquals(TAKE, FleetGate.request(78, 0, false, DISARMED));
    }

    /**
     * Lines 1447-1457 in a row: refused, the token is not kept (fleetToken is set only when taken), so the PC's next
     * repeat is looked at again - and taken once the pilot has gone and the FC reads disarmed.
     */
    @Test
    void aRefusedTokenIsLookedAtAgainAtEveryRepeatUntilTaken() {
        int fleetToken = 0;
        int asked = 0;
        boolean[] pilot = {true, true, false, false, false, false};
        boolean[] disarmed = {true, false, false, false, true, true};
        FleetGate.Verdict[] want = {REFUSED_PILOT, REFUSED_PILOT, REFUSED_ARMED, REFUSED_ARMED, TAKE, REPEAT};
        for (int i = 0; i < want.length; i++) {
            final boolean d = disarmed[i];
            int[] fcReads = {0};
            FleetGate.Verdict v = FleetGate.request(91, fleetToken, pilot[i], () -> {
                fcReads[0]++;
                return d;
            });
            assertEquals(want[i], v, "repeat " + i);
            assertEquals(want[i] == REFUSED_ARMED || want[i] == TAKE ? 1 : 0, fcReads[0], "the FC is read only past the pilot's check: repeat " + i);
            asked += fcReads[0];
            if (v == TAKE) fleetToken = 91;
        }
        assertEquals(3, asked);
        assertEquals(91, fleetToken);
    }
}
