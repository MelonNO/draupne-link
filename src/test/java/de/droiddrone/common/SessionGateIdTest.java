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

    /**
     * 1.0.71 / pc.55: the pilot PC's address changed under its session (no Tailscale): its Connects come from another
     * address with its own count's next ids. Let in once its old address is silent SAME_IP_SILENT_MS, armed or not -
     * before, refused while the drone may fly, the pilot a spectator of itself. Another PC's ids are not that count.
     */
    @Test
    void thePilotPcAtANewAddressIsKnownByItsIds() {
        SessionGate g = new SessionGate(KEY);
        assertEquals(NEW_SESSION, noSession(g, connect(KEY, 1000), 0));
        assertEquals(TAKE, fromPilot(g, PING, 5000)); // flying: heard to 5 s
        byte[] moved = connect(KEY, 1001), stranger = connect(KEY, 77), farOn = connect(KEY, 1000 + SessionGate.OLDER_TRIES + 1);
        long t = 5000 + Takeover.SAME_IP_SILENT_MS;
        assertEquals(REFUSED, g.fromOther(moved, moved.length, false, false, t - 1, 1001), "its old address heard moments ago: refused");
        assertEquals(REFUSED, g.fromOther(stranger, stranger.length, false, false, t, 77), "another PC, armed: refused as ever");
        assertEquals(REFUSED, g.fromOther(farOn, farOn.length, false, false, t, 1000 + SessionGate.OLDER_TRIES + 1), "too far on to be its count");
        assertEquals(NEW_SESSION, g.fromOther(moved, moved.length, false, false, t, 1001), "its next id, its old address silent: in, armed");
        assertEquals(SAME_SESSION, fromPilot(g, moved, t + 100), "its session now");
        byte[] wrong = connect("XX", 1002);
        assertEquals(WRONG_KEY, g.fromOther(wrong, wrong.length, false, false, t + 10_000, 1002), "never without the key");

        // the phone's session timed out meanwhile: the same rule after it
        SessionGate h = new SessionGate(KEY);
        assertEquals(NEW_SESSION, noSession(h, connect(KEY, 5000), 0));
        assertEquals(TAKE, fromPilot(h, PING, 1000));
        byte[] next = connect(KEY, 5002), other = connect(KEY, 9);
        assertEquals(SPECTATOR, h.noSession(other, other.length, true, false, false, () -> false, 8000, 9), "another PC, armed: a spectator");
        assertEquals(NEW_SESSION, h.noSession(next, next.length, true, false, false, () -> false, 8000, 5002), "the pilot PC moved: in");
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
    void aPhoneHeardNotAtAllKeepsItsIdFourSeconds() {
        // F-001, the pilot's rule (2026-11-20): a link dead one way - the phone hears the Connects, the PC hears nothing
        SessionGate.Ids ids = new SessionGate.Ids(100);
        long heardAt = 9_000; // the last the PC heard of the phone: before the cut
        long first = ids.next(10_000, 0, heardAt);
        assertEquals(101, first, "the link went: a new id at once, one session");
        assertEquals(first, ids.next(10_000 + SAME_SESSION_MS, 0, heardAt), "unanswered a second, nothing heard: kept");
        assertEquals(first, ids.next(10_000 + SessionGate.Ids.UNHEARD_KEEP_MS - 1, 0, heardAt));
        assertEquals(102, ids.next(10_000 + SessionGate.Ids.UNHEARD_KEEP_MS, 0, heardAt), "after 4 s: a new one");
    }

    @Test
    void aNewIdIsDueTheMomentThePhoneIsHeardAgain() {
        // F-001: a 3 s one-way cut - the id kept while unheard is given up as soon as the phone is heard, so the PC
        // sends that Connect at once (the bad-link table: LINK READY within a second of the phone heard again)
        SessionGate.Ids ids = new SessionGate.Ids(7);
        assertFalse(ids.renewDue(10_000, 0), "nothing tried yet");
        ids.next(10_000, 0, 9_000);
        assertFalse(ids.renewDue(10_500, 9_000), "half a second: kept");
        assertFalse(ids.renewDue(11_200, 9_000), "unheard: kept 4 s");
        assertTrue(ids.renewDue(11_200, 11_150), "heard at 11.15 s, the id 1.2 s old: due now");
        assertTrue(ids.renewDue(14_000, 9_000), "unheard 4 s: due");
        ids.sessionUp();
        assertFalse(ids.renewDue(20_000, 19_000), "a session up: nothing tried");
    }

    @Test
    void aPhoneHeardSinceTheIdWentOutGetsANewIdAfterASecondAsBefore() {
        // heard, but no answer: the answer is what was lost - mended by the next id after a second, as ever
        SessionGate.Ids ids = new SessionGate.Ids(100);
        long first = ids.next(10_000, 0, 9_000);
        assertEquals(first, ids.next(10_500, 0, 10_400));
        assertEquals(first + 1, ids.next(10_000 + SAME_SESSION_MS, 0, 10_400), "heard at 10.4 s: a new id at 11 s");
    }

    @Test
    void aOneWayCutOfSixSecondsMakesTwoSessionsNotFive() {
        // the bad-link table's cell "cut 6 s phone to PC only": Connects every 100 ms, the phone hears each
        SessionGate.Ids ids = new SessionGate.Ids(0);
        SessionGate phone = new SessionGate(KEY);
        long cutAt = 10_000, backAt = 16_000, heardAt = cutAt;
        int sessions = 0;
        // the PC counts the link lost 2 s into the cut and tries from then on
        for (long t = cutAt + 2000; t < backAt; t += 100) {
            if (fromPilot(phone, connect(KEY, ids.next(t, 0, heardAt)), t) == NEW_SESSION) sessions++;
        }
        assertTrue(sessions <= 2, "one when the link goes, about one when it is back: " + sessions);
        assertTrue(sessions >= 1);
        // back: the phone's answer is heard - the session is up on the id in use
        ids.sessionUp();
        assertEquals(NEW_SESSION, fromPilot(phone, connect(KEY, ids.next(backAt + 3000, 0, backAt + 2900)), backAt + 3000),
                "the next loss starts at once with a new id");
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

    /** A 1.0.71 PC's Connect: the id, then the byte the phone's answer says "told by the id" in. */
    private static byte[] withAnswerByte(byte[] connect) {
        return java.util.Arrays.copyOf(connect, connect.length + 1);
    }

    @Test
    void aLateConnectOfAnOlderTryStartsNothing() {
        // the PC gave up try 1000 unanswered and went on to 1001, which started the session; a Connect of 1000, held
        // up on the way, comes after it. As "another id" it was a session more - under a PC that is in 1001's and
        // lets an older try's answer start nothing: the two sides apart
        SessionGate g = new SessionGate(KEY);
        assertEquals(NEW_SESSION, noSession(g, connect(KEY, 1001), 10_000));
        assertEquals(SAME_SESSION, fromPilot(g, connect(KEY, 1000), 10_400), "the try before: late, not new");
        assertEquals(SAME_SESSION, fromPilot(g, connect(KEY, 1001 - SessionGate.OLDER_TRIES), 10_500), "as far back as a PC's tries go");
        assertEquals(SAME_SESSION, fromPilot(g, connect(KEY, 1001), 10_600), "the session's id is still 1001");
        assertEquals(0, g.silentMs(10_600), "and the pilot is heard");
        assertEquals(NEW_SESSION, fromPilot(g, connect(KEY, 1002), 10_700), "the next try is a new session at once");
        assertEquals(SAME_SESSION, fromPilot(g, connect(KEY, 1001), 10_800), "and 1001 the older one now");
        // further back than a PC's own tries reach: a PC counting from another seed - a new session, as any other id
        assertEquals(NEW_SESSION, fromPilot(g, connect(KEY, 1002 - SessionGate.OLDER_TRIES - 1), 10_900));
        // across the count's wrap
        SessionGate w = new SessionGate(KEY);
        assertEquals(NEW_SESSION, noSession(w, connect(KEY, 1), 0));
        assertEquals(SAME_SESSION, fromPilot(w, connect(KEY, 0xFFFFFFFFL), 100), "2 behind, over the wrap");
        assertEquals(NEW_SESSION, fromPilot(w, connect(KEY, 2), 200));
        // with no session up the rule is as it was: any keyed Connect of the pilot's starts one
        SessionGate n = new SessionGate(KEY);
        assertEquals(NEW_SESSION, noSession(n, connect(KEY, 1001), 0));
        assertEquals(NEW_SESSION, noSession(n, connect(KEY, 1000), 9000));
    }

    @Test
    void thePhoneMarksItsAnswerAndThePcReadsTheMark() {
        byte[] c = withAnswerByte(connect(KEY, 77));
        assertFalse(SessionGate.answeredById(c, c.length), "as the PC sends it: 0");
        byte[] expected = c.clone();
        expected[expected.length - 1] = SessionGate.ANSWER_BY_ID;
        SessionGate.markAnswer(c, c.length);
        assertArrayEquals(expected, c, "one bit of the last byte, nothing else");
        assertTrue(SessionGate.answeredById(c, c.length));
        assertEquals(77, SessionGate.sessionId(c, c.length), "the id as it was");
        assertTrue(new SessionGate(KEY).keyMatches(c, c.length, 4));
        // a Connect that ends with the id, or with the version (an older PC's), and what is no Connect: not a byte
        // changes, and no mark is read
        for (byte[] other : new byte[][]{connect(KEY, 77), connect(KEY, NO_ID), PING.clone()}) {
            byte[] was = other.clone();
            SessionGate.markAnswer(other, other.length);
            assertArrayEquals(was, other);
            assertFalse(SessionGate.answeredById(other, other.length));
        }
        assertFalse(SessionGate.answeredById(c, c.length - 1), "cut before the byte");
        byte[] more = java.util.Arrays.copyOf(c, c.length + 3); // a later PC's Connect goes on behind it
        assertTrue(SessionGate.answeredById(more, more.length));
    }

    @Test
    void anOlderTrysAnswerKeepsTheIdsLonger() {
        SessionGate.Ids ids = new SessionGate.Ids(99);
        long a = ids.next(0, 0);
        assertEquals(a, ids.next(900, 0));
        long b = ids.next(1000, 0);
        assertNotEquals(a, b);
        assertEquals(-1, ids.olderAnswer(b, 1500), "the id in use is no older try's");
        assertEquals(-1, ids.olderAnswer(12_345, 1500), "nor is one never given out");
        // the answer to the first try comes 1.6 s after that try began: the way is slower than a try is kept
        assertEquals(1600, ids.olderAnswer(a, 1600));
        assertEquals(b, ids.next(2300, 0), "b is kept past its second");
        assertEquals(b, ids.next(1000 + 2399, 0), "half as long again as the answer took, from its own first Connect");
        long c = ids.next(1000 + 2400, 0);
        assertNotEquals(b, c);
        assertEquals(c, ids.next(3400 + 2399, 0), "and so is the next, until a session is up");
        ids.sessionUp();
        long d = ids.next(10_000, 0);
        assertEquals(d, ids.next(10_999, 0));
        assertNotEquals(d, ids.next(11_000, 0), "after a session a try is a second again");
        // never longer than SLOW_KEEP_MAX_MS, whatever an answer took
        SessionGate.Ids slow = new SessionGate.Ids(5);
        long first = slow.next(0, 0);
        long second = slow.next(1000, 0);
        assertEquals(60_000, slow.olderAnswer(first, 60_000));
        assertEquals(second, slow.next(1000 + SessionGate.Ids.SLOW_KEEP_MAX_MS - 1, 0));
        assertNotEquals(second, slow.next(1000 + SessionGate.Ids.SLOW_KEEP_MAX_MS, 0));
        // with a session up an older try's answer teaches nothing: its own answer came in time
        SessionGate.Ids up = new SessionGate.Ids(5);
        long x = up.next(0, 0);
        up.next(1000, 0);
        up.sessionUp();
        assertEquals(3000, up.olderAnswer(x, 3000));
        long z = up.next(5000, 0);
        assertNotEquals(z, up.next(6000, 0), "a second, as ever");
    }

    @Test
    void anAnswerAfterACutIsCountedFromTheLastConnectSentIntoTheSilence() {
        // F-072, with the times of a run of SessionIdEndToEndTest (2026-11-21; ms into a cut both ways that ended at
        // 5.6 s). The PC counts the link lost after 2 s and begins a try; unheard, the try is kept (F-001)
        SessionGate.Ids ids = new SessionGate.Ids(1436);
        long heardBeforeTheCut = 0;
        long kept = ids.next(2062, 0, heardBeforeTheCut);
        for (long t = 2062 + 187; t < 5614; t += 187) assertEquals(kept, ids.next(t, 0, heardBeforeTheCut), "lost in the cut, the try kept");
        assertEquals(kept, ids.next(5614, 0, heardBeforeTheCut), "the Connect that gets through as the cut ends");
        // the phone's picture is heard a millisecond later: the try, 3.5 s old, is given up for the next (F-001)
        assertTrue(ids.renewDue(5677, 5615));
        long next = ids.next(5677, 0, 5615);
        assertEquals(kept + 1, next);
        // the phone's answer to the kept try: 79 ms after the Connect it answers - and 3.6 s after the try began
        assertEquals(79, ids.olderAnswer(kept, 5693), "from the last Connect sent into the silence: a cut's length is no way there and back");
        // so the next try is kept its second and no longer (5 s before F-072): its answer lost, the one after it mends
        assertFalse(ids.renewDue(5677 + SAME_SESSION_MS - 1, 5690));
        assertEquals(next, ids.next(5677 + SAME_SESSION_MS - 1, 0, 5690));
        assertTrue(ids.renewDue(5677 + SAME_SESSION_MS, 5690), "a new id a second after, the phone heard: F-001's rule");
        assertEquals(next + 1, ids.next(5677 + SAME_SESSION_MS, 0, 5690));
        // a try begun in a cut that ends 0.4 s later goes on for its second, the phone heard: its answer is counted
        // from the last Connect sent into the silence - the ones after it are not the first to reach the phone
        SessionGate.Ids young = new SessionGate.Ids(40);
        long p = young.next(2000, 0, 0);
        assertEquals(p, young.next(2190, 0, 0));
        assertEquals(p, young.next(2380, 0, 0));
        assertEquals(p, young.next(2570, 0, 2500), "heard at 2.5 s, the try half a second old: kept");
        assertEquals(p, young.next(2950, 0, 2940));
        assertNotEquals(p, young.next(3000, 0, 2990));
        assertEquals(900, young.olderAnswer(p, 3280), "from the Connect of 2.38 s");
    }

    @Test
    void anAnswerOnASlowWayIsCountedFromItsTrysFirstConnectAsBefore() {
        // F-072 changes nothing where the phone is heard: it answers the first Connect of a try to reach it, and with
        // the way alive that is the try's first. As ControlLink asks: the phone last heard a moment before each Connect
        SessionGate.Ids ids = new SessionGate.Ids(7);
        long a = ids.next(10_000, 0, 9_990);
        for (long t = 10_150; t < 11_000; t += 150) assertEquals(a, ids.next(t, 0, t - 10));
        long b = ids.next(11_000, 0, 10_990);
        assertEquals(1600, ids.olderAnswer(a, 11_600), "1.6 s there and back");
        assertEquals(b, ids.next(11_000 + 2399, 0, 13_390), "the next try kept half as long again");
        assertNotEquals(b, ids.next(11_000 + 2400, 0, 13_390));
        // nor where the phone is heard seldom (standby: once a second): not heard since the try began is no silence
        // yet - the PC itself counts a link lost after 2 s
        SessionGate.Ids seldom = new SessionGate.Ids(7);
        long c = seldom.next(20_000, 0, 19_900);
        for (long t = 20_150; t < 21_000; t += 150) assertEquals(c, seldom.next(t, 0, t < 20_900 ? 19_900 : 20_900));
        seldom.next(21_000, 0, 20_900);
        assertEquals(1600, seldom.olderAnswer(c, 21_600));
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
