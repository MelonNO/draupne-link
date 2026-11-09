package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static de.droiddrone.common.SessionGate.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

/** Who gets a session on the phone (moved out of its Udp): the key, the pilot's Connects, another PC's. */
class SessionGateTest {
    private static final String KEY = "DD";

    /** [Connect][num i16][client type][key UTF]: the key at 4. */
    private static byte[] connect(String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[6 + k.length];
        d[0] = UdpCommon.Connect;
        d[1] = -1;
        d[2] = -1;
        d[3] = 0;
        d[4] = (byte) (k.length >> 8);
        d[5] = (byte) k.length;
        System.arraycopy(k, 0, d, 6, k.length);
        return d;
    }

    private static final byte[] PING = {UdpCommon.Ping, 0, 0};

    private static SessionGate.Verdict fromPilot(SessionGate g, byte[] d, long now) {
        return g.fromPilot(d, d.length, now);
    }

    @Test
    void theKeyIsReadWhereTheConnectOrTheQueryHasIt() {
        SessionGate g = new SessionGate(KEY);
        byte[] c = connect(KEY);
        assertTrue(g.keyMatches(c, c.length, 4));
        assertFalse(g.keyMatches(c, c.length - 1, 4), "cut");
        byte[] other = connect("XX");
        assertFalse(g.keyMatches(other, other.length, 4));
        assertFalse(g.keyMatches(new byte[]{UdpCommon.Connect}, 1, 4));
        byte[] query = {0x60, 0, 2, 'D', 'D'};
        assertTrue(g.keyMatches(query, query.length, 1), "PILOT_QUERY: the key at 1");
    }

    @Test
    void thePilotsRepeatedConnectsAfterALossAreOneSession() {
        SessionGate g = new SessionGate(KEY);
        byte[] c = connect(KEY);
        assertEquals(NEW_SESSION, fromPilot(g, c, 10_000));
        assertEquals(SAME_SESSION, fromPilot(g, c, 10_150), "the next in flight");
        assertEquals(SAME_SESSION, fromPilot(g, c, 10_000 + SessionGate.SAME_SESSION_MS - 1));
        assertEquals(NEW_SESSION, fromPilot(g, c, 10_000 + SessionGate.SAME_SESSION_MS), "its answer lost: the next one is answered");
        assertEquals(TAKE, fromPilot(g, PING, 12_000));
        byte[] other = connect("XX");
        assertEquals(TAKE, fromPilot(g, other, 13_000), "not a keyed Connect: the buffer drops it");
    }

    @Test
    void everyPacketFromThePilotCountsAsHeard() {
        SessionGate g = new SessionGate(KEY);
        fromPilot(g, PING, 5000);
        assertEquals(1000, g.silentMs(6000));
    }

    @Test
    void anotherPcTakesOverOnlyAsTakeoverAllows() {
        SessionGate g = new SessionGate(KEY);
        fromPilot(g, PING, 0);
        byte[] c = connect(KEY);
        assertEquals(REFUSED, g.fromOther(c, c.length, false, true, Takeover.PILOT_ALIVE_MS - 1), "the pilot heard");
        assertEquals(REFUSED, g.fromOther(c, c.length, false, false, 60_000), "never while not known disarmed");
        assertEquals(NEW_SESSION, g.fromOther(c, c.length, false, true, Takeover.PILOT_ALIVE_MS));
        assertEquals(SAME_SESSION, fromPilot(g, c, Takeover.PILOT_ALIVE_MS + 10), "that session started now");
    }

    @Test
    void thePilotsAddressOnANewPortWaitsUnlessDisarmed() {
        SessionGate g = new SessionGate(KEY);
        fromPilot(g, PING, 0);
        byte[] c = connect(KEY);
        assertEquals(REFUSED, g.fromOther(c, c.length, true, false, 100));
        assertEquals(NEW_SESSION, g.fromOther(c, c.length, true, true, 100));
    }

    @Test
    void anotherPcsOtherPacketsAreASpectatorsAndAWrongKeyIsDropped() {
        SessionGate g = new SessionGate(KEY);
        assertEquals(SPECTATOR, g.fromOther(PING, PING.length, false, true, 0));
        byte[] other = connect("XX");
        assertEquals(WRONG_KEY, g.fromOther(other, other.length, false, true, 60_000));
    }

    @Test
    void theSpectatorIsToldThePilotIsThereWhileHeardOrNotDisarmed() {
        SessionGate g = new SessionGate(KEY);
        fromPilot(g, PING, 0);
        assertTrue(g.pilotAlive(true, Takeover.PILOT_ALIVE_MS - 1));
        assertFalse(g.pilotAlive(true, Takeover.PILOT_ALIVE_MS));
        assertTrue(g.pilotAlive(false, 60_000), "armed: a fade, not an absence");
    }

    @Test
    void withoutASessionOnlyAKeyedConnectGetsIn() {
        SessionGate g = new SessionGate(KEY);
        assertEquals(DROP, g.noSession(PING, PING.length, false, false, false, () -> true, 0), "a stray packet revives nothing");
        byte[] other = connect("XX");
        assertEquals(WRONG_KEY, g.noSession(other, other.length, false, false, false, () -> true, 0));
        byte[] c = connect(KEY);
        assertEquals(NEW_SESSION, g.noSession(c, c.length, false, false, false, () -> fail("no pilot yet: not asked"), 0));
        assertEquals(0, g.silentMs(0), "heard from: no takeover in its first moments");
    }

    @Test
    void afterATimeoutAnotherPcStillNeedsTheTakeoverRule() {
        SessionGate g = new SessionGate(KEY);
        byte[] c = connect(KEY);
        g.noSession(c, c.length, false, false, false, () -> true, 0);
        long timedOut = 4000;
        assertEquals(SPECTATOR, g.noSession(c, c.length, true, false, false, () -> true, timedOut), "a 4 s fade hands nothing over");
        assertEquals(SPECTATOR, g.noSession(c, c.length, true, false, false, () -> false, 60_000), "armed: never");
        assertEquals(NEW_SESSION, g.noSession(c, c.length, true, false, false, () -> true, Takeover.PILOT_ALIVE_MS));
        assertEquals(NEW_SESSION, g.noSession(c, c.length, true, true, true, () -> fail("the pilot itself: not asked"), Takeover.PILOT_ALIVE_MS + 1),
                "the pilot's own PC: always");
    }
}
