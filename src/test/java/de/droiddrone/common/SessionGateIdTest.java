package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static de.droiddrone.common.SessionGate.NO_ID;
import static de.droiddrone.common.SessionGate.SAME_SESSION_MS;
import static de.droiddrone.common.SessionGate.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The session id a newer PC ends its Connect with: the phone tells one session's Connects from the next session's by
 * it, not by the clock. A Connect without one is judged as before (SessionGateTest has those rules).
 */
class SessionGateIdTest {
    private static final String KEY = "DD";

    /** [Connect][num i16][client type][key UTF][version i16], and [sessionId u32] behind it unless id is NO_ID. */
    private static byte[] connect(String key, long id) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[8 + k.length + (id == NO_ID ? 0 : 4)];
        d[0] = UdpCommon.Connect;
        d[1] = -1;
        d[2] = -1;
        d[3] = 1;
        d[4] = (byte) (k.length >> 8);
        d[5] = (byte) k.length;
        System.arraycopy(k, 0, d, 6, k.length);
        d[7 + k.length] = 9; // the version
        if (id != NO_ID) Bytes.putInt(d, 8 + k.length, (int) id);
        return d;
    }

    private static final byte[] PING = {UdpCommon.Ping, 1, 0, 0, 0, 0, 0, 0, 0, 0};

    /** As the phone's receive thread would: the id read from the packet, whatever the packet is. */
    private static SessionGate.Verdict fromPilot(SessionGate g, byte[] d, long now) {
        return g.fromPilot(d, d.length, now, SessionGate.sessionId(d, d.length));
    }

    private static SessionGate.Verdict noSession(SessionGate g, byte[] d, long now) {
        return g.noSession(d, d.length, false, false, false, () -> true, now, SessionGate.sessionId(d, d.length));
    }

    @Test
    void theIdIsReadBehindTheKeyAndTheVersion() {
        byte[] c = connect(KEY, 0x89ABCDEFL);
        assertEquals(0x89ABCDEFL, SessionGate.sessionId(c, c.length), "all 32 bits, none of them a sign");
        assertEquals(0, SessionGate.sessionId(connect(KEY, 0), c.length), "0 is an id like any other");
        assertEquals(0xFFFFFFFFL, SessionGate.sessionId(connect(KEY, 0xFFFFFFFFL), c.length));
        byte[] longKey = connect("a longer key", 77);
        assertEquals(77, SessionGate.sessionId(longKey, longKey.length), "wherever the key ends");
        byte[] old = connect(KEY, NO_ID);
        assertEquals(NO_ID, SessionGate.sessionId(old, old.length), "an older PC: the Connect ends with the version");
        assertEquals(NO_ID, SessionGate.sessionId(c, c.length - 1), "cut");
        assertEquals(NO_ID, SessionGate.sessionId(c, 3));
        assertEquals(NO_ID, SessionGate.sessionId(PING, PING.length), "no Connect");
        byte[] more = java.util.Arrays.copyOf(c, c.length + 5); // a later PC's Connect goes on behind it
        assertEquals(0x89ABCDEFL, SessionGate.sessionId(more, more.length));
        assertTrue(new SessionGate(KEY).keyMatches(c, c.length, 4), "the key is where it was");
    }

    @Test
    void theSameIdIsTheSameSessionAtAnyAge() {
        SessionGate g = new SessionGate(KEY);
        byte[] c = connect(KEY, 1000);
        assertEquals(NEW_SESSION, noSession(g, c, 10_000));
        assertEquals(SAME_SESSION, fromPilot(g, c, 10_150), "the next in flight");
        assertEquals(SAME_SESSION, fromPilot(g, c, 10_000 + SAME_SESSION_MS), "a second on: by the clock a new session");
        assertEquals(SAME_SESSION, fromPilot(g, c, 12_500), "a slow path's late Connects");
        assertEquals(SAME_SESSION, fromPilot(g, c, 10_000 + 3_600_000), "an hour on");
        // by the clock alone the second of those was a session more
        SessionGate byClock = new SessionGate(KEY);
        byte[] old = connect(KEY, NO_ID);
        assertEquals(NEW_SESSION, noSession(byClock, old, 10_000));
        assertEquals(NEW_SESSION, fromPilot(byClock, old, 10_000 + SAME_SESSION_MS));
    }

    @Test
    void anotherIdIsANewSessionAtOnce() {
        SessionGate g = new SessionGate(KEY);
        assertEquals(NEW_SESSION, noSession(g, connect(KEY, 1000), 10_000));
        assertEquals(NEW_SESSION, fromPilot(g, connect(KEY, 1001), 10_010), "10 ms on: the PC has let the first go");
        assertEquals(SAME_SESSION, fromPilot(g, connect(KEY, 1001), 10_020));
        assertEquals(SAME_SESSION, fromPilot(g, connect(KEY, 1001), 20_000));
        assertEquals(NEW_SESSION, fromPilot(g, connect(KEY, 5), 20_001), "any other id, not only a higher one");
        assertEquals(NEW_SESSION, fromPilot(g, connect(KEY, 1001), 20_002), "the id before the last is another too");
    }

    @Test
    void otherPacketsAreTakenAndCountAsHeard() {
        SessionGate g = new SessionGate(KEY);
        noSession(g, connect(KEY, 1000), 0);
        assertEquals(TAKE, fromPilot(g, PING, 5000));
        assertEquals(1000, g.silentMs(6000));
        assertEquals(TAKE, fromPilot(g, connect("XX", 1001), 7000), "not a keyed Connect: the buffer drops it");
        assertEquals(0, g.silentMs(7000));
        assertEquals(SAME_SESSION, fromPilot(g, connect(KEY, 1000), 8000), "and the session is the one it was");
        assertEquals(0, g.silentMs(8000), "a Connect of the session is the pilot heard");
    }

    @Test
    void withoutAnIdEveryVerdictIsTheOldMethods() {
        // the same packets at the same times to two gates, one through the old calls and one through the new with no
        // id: verdict for verdict, and the pilot's silence, the same
        Random r = new Random(7);
        SessionGate old = new SessionGate(KEY), id = new SessionGate(KEY);
        byte[][] packets = {connect(KEY, NO_ID), connect(KEY, NO_ID), PING, connect("XX", NO_ID)};
        long now = 1000;
        for (int i = 0; i < 20_000; i++) {
            now += r.nextInt(4) == 0 ? r.nextInt(12_000) : r.nextInt(400);
            byte[] d = packets[r.nextInt(packets.length)];
            boolean sameAddress = r.nextBoolean(), disarmed = r.nextBoolean(), pilotKnown = r.nextBoolean(), samePc = sameAddress && r.nextBoolean();
            switch (r.nextInt(3)) {
                case 0 -> assertEquals(old.fromPilot(d, d.length, now), id.fromPilot(d, d.length, now, SessionGate.sessionId(d, d.length)), "step " + i);
                case 1 -> assertEquals(old.fromOther(d, d.length, sameAddress, disarmed, now),
                        id.fromOther(d, d.length, sameAddress, disarmed, now, NO_ID), "step " + i);
                default -> assertEquals(old.noSession(d, d.length, pilotKnown, samePc, sameAddress, () -> disarmed, now),
                        id.noSession(d, d.length, pilotKnown, samePc, sameAddress, () -> disarmed, now, NO_ID), "step " + i);
            }
            assertEquals(old.silentMs(now), id.silentMs(now), "step " + i);
            assertEquals(old.pilotAlive(disarmed, now), id.pilotAlive(disarmed, now));
        }
    }

    @Test
    void anOlderPcsConnectIsJudgedByTheClockAfterASessionWithAnId() {
        SessionGate g = new SessionGate(KEY);
        assertEquals(NEW_SESSION, noSession(g, connect(KEY, 1000), 10_000));
        byte[] old = connect(KEY, NO_ID);
        assertEquals(SAME_SESSION, fromPilot(g, old, 10_500), "within the second");
        assertEquals(NEW_SESSION, fromPilot(g, old, 11_000), "after it: a session, and one without an id");
        assertEquals(SAME_SESSION, fromPilot(g, old, 11_900));
        assertEquals(NEW_SESSION, fromPilot(g, old, 12_000));
        // the id of the session before it is not the live one any more
        assertEquals(NEW_SESSION, fromPilot(g, connect(KEY, 1000), 14_000));
    }

    @Test
    void aSessionStartedWithoutAnIdTakesTheIdOfItsOwnConnects() {
        // the first Connect went through a call that takes no id (the wiring swapped in one place only): the next
        // Connect of the burst is the same session, as by the clock, and names it
        SessionGate g = new SessionGate(KEY);
        byte[] c = connect(KEY, 1000);
        assertEquals(NEW_SESSION, g.noSession(c, c.length, false, false, false, () -> true, 10_000));
        assertEquals(SAME_SESSION, fromPilot(g, c, 10_150));
        assertEquals(SAME_SESSION, fromPilot(g, c, 15_000), "named: at any age now");
        assertEquals(NEW_SESSION, fromPilot(g, connect(KEY, 1001), 15_001));
        // not named within the second: by the clock a new session, with the id
        SessionGate late = new SessionGate(KEY);
        assertEquals(NEW_SESSION, late.noSession(c, c.length, false, false, false, () -> true, 10_000));
        assertEquals(NEW_SESSION, fromPilot(late, c, 10_000 + SAME_SESSION_MS));
        assertEquals(SAME_SESSION, fromPilot(late, c, 20_000));
        // and a session the old calls start later has no id from the one before
        assertEquals(NEW_SESSION, late.fromOther(c, c.length, true, true, 30_000), "the pilot's address on a new port, disarmed");
        assertEquals(NEW_SESSION, fromPilot(late, c, 30_000 + SAME_SESSION_MS), "the old id is not this session's");
    }

    @Test
    void aSessionAnotherPcStartsHasThatPcsId() {
        SessionGate g = new SessionGate(KEY);
        assertEquals(NEW_SESSION, noSession(g, connect(KEY, 1000), 0));
        byte[] other = connect(KEY, 50);
        long later = Takeover.PILOT_ALIVE_MS;
        assertEquals(REFUSED, g.fromOther(other, other.length, false, true, later - 1, 50), "the takeover rule as ever");
        assertEquals(SAME_SESSION, fromPilot(g, connect(KEY, 1000), later - 1), "a Connect refused starts nothing");
        assertEquals(NEW_SESSION, g.fromOther(other, other.length, false, true, later + 20_000, 50));
        assertEquals(SAME_SESSION, fromPilot(g, other, later + 25_000), "it is the pilot now, and this its session");
        assertEquals(NEW_SESSION, fromPilot(g, connect(KEY, 1000), later + 25_001), "the first pilot's id is no longer live");
        assertEquals(SPECTATOR, g.fromOther(PING, PING.length, false, true, later + 26_000, NO_ID));
        byte[] wrong = connect("XX", 51);
        assertEquals(WRONG_KEY, g.fromOther(wrong, wrong.length, false, true, later + 60_000, 51));
    }

    @Test
    void withNoSessionUpTheSameIdStartsOneAgain() {
        // the phone's session timed out (4 s without a packet) while the PC's Connects with that id still come
        SessionGate g = new SessionGate(KEY);
        byte[] c = connect(KEY, 1000);
        assertEquals(NEW_SESSION, noSession(g, c, 0));
        assertEquals(SAME_SESSION, fromPilot(g, c, 3000));
        assertEquals(NEW_SESSION, noSession(g, c, 8000), "no session: any keyed Connect of the pilot's starts one");
        assertEquals(SAME_SESSION, fromPilot(g, c, 8100));
        assertEquals(DROP, g.noSession(PING, PING.length, true, true, true, () -> true, 20_000, NO_ID));
    }

    @Test
    void thePcGivesOneIdToATryAndANewOneToTheNext() {
        SessionGate.Ids ids = new SessionGate.Ids(0xFFFFFFFE);
        long first = ids.next(10_000, 0);
        assertEquals(0xFFFFFFFFL, first, "counted up from the seed, 32 bits unsigned");
        assertEquals(first, ids.next(10_150, 0), "the Connects of one try");
        assertEquals(first, ids.next(10_000 + SAME_SESSION_MS - 1, 0));
        assertEquals(first, ids.current());
        long second = ids.next(10_000 + SAME_SESSION_MS, 0);
        assertEquals(0, second, "unanswered for a second: a new one (and the count wraps)");
        assertEquals(second, ids.next(11_500, 0));
        // a slow path's round: one id for all of it
        long third = ids.next(12_000, 1800);
        assertEquals(1, third);
        assertEquals(third, ids.next(13_700, 1800), "1.7 s on, still the round");
        assertEquals(2, ids.next(13_800, 1800));
        // answered: the id is the session's, and the Connect after the next loss has another - at once
        ids.sessionUp();
        assertEquals(2, ids.current());
        assertEquals(3, ids.next(13_801, 0));
        assertEquals(3, ids.next(13_900, 0));
    }

    @Test
    void aLostAnswerIsMendedByTheNextId() {
        // the phone took the first Connect and answered; the answer never reached the PC, which goes on asking
        SessionGate phone = new SessionGate(KEY);
        SessionGate.Ids pc = new SessionGate.Ids(41);
        int sessions = 0;
        long answeredAt = -1;
        for (long now = 0; now < 3000 && answeredAt < 0; now += 150) {
            byte[] c = connect(KEY, pc.next(now, 0));
            SessionGate.Verdict v = now == 0 ? noSession(phone, c, now) : fromPilot(phone, c, now);
            if (v == NEW_SESSION) {
                sessions++;
                if (sessions == 2) answeredAt = now; // the second answer arrives
            } else {
                assertEquals(SAME_SESSION, v, "dropped unanswered: the PC hears nothing");
            }
        }
        assertEquals(2, sessions);
        assertTrue(answeredAt >= SAME_SESSION_MS && answeredAt < SAME_SESSION_MS + 150, "answered again with the first new id, at " + answeredAt);
        // up: the Connects of that try still in flight are its own
        pc.sessionUp();
        assertEquals(SAME_SESSION, fromPilot(phone, connect(KEY, pc.current()), answeredAt + 400));
        // the PC loses the link 5 s later and asks again: answered at once, with the session still held on the phone
        assertEquals(NEW_SESSION, fromPilot(phone, connect(KEY, pc.next(answeredAt + 5000, 0)), answeredAt + 5040));
    }

    @Test
    void aSlowPathsRoundIsOneSession() {
        // 1.6 s there and back: the round's Connects (0.6 s of them) reach the phone 0.8 s late, and the answer is
        // back before the next round - one session, where the clock's rule made the Connects after a second another
        SessionGate phone = new SessionGate(KEY);
        SessionGate.Ids pc = new SessionGate.Ids(new Random(3).nextInt());
        int sessions = 0;
        for (long sent = 0; sent <= 600; sent += 150) {
            byte[] c = connect(KEY, pc.next(sent, 2000));
            SessionGate.Verdict v = sent == 0 ? noSession(phone, c, sent + 800) : fromPilot(phone, c, sent + 800);
            if (v == NEW_SESSION) sessions++;
        }
        // a Connect of that round delayed a second more on its way
        assertEquals(SAME_SESSION, fromPilot(phone, connect(KEY, pc.current()), 2900));
        assertEquals(1, sessions);
    }
}
