package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The PC's one "possibly flying" flag (1.0.49, H4 / H7): armed now, or within the lost-drone window after it went quiet. */
class PossiblyFlyingTest {
    private final long[] now = {1_000_000};
    private final PossiblyFlying flag = new PossiblyFlying(() -> now[0]);
    private final PossiblyFlying.Word[] word = {PossiblyFlying.Word.NOTHING};
    private final Object link = new Object();

    @Test
    void armedNowHoldsDisarmedDoesNot() {
        assertFalse(flag.holds(), "no link");
        flag.attach(link, () -> word[0]);
        assertFalse(flag.holds(), "nothing heard yet");
        word[0] = new PossiblyFlying.Word(true, now[0]);
        assertTrue(flag.holds());
        word[0] = new PossiblyFlying.Word(false, now[0]);
        assertFalse(flag.holds(), "a fresh DISARMED");
    }

    @Test
    void twoSecondsOfQuietIsNoProofOfLanding() {
        flag.attach(link, () -> word[0]);
        word[0] = new PossiblyFlying.Word(true, now[0]);
        now[0] += 2_500; // the old rule: "not flying" after 2 s of silent telemetry
        assertTrue(flag.holds(), "quiet while armed: it may be in a failsafe in the air");
        now[0] += PossiblyFlying.LOST_DRONE_WINDOW_MS - 3_000;
        assertTrue(flag.holds(), "still within the lost-drone window");
        now[0] += 1_000;
        assertFalse(flag.holds(), "the window is over");
    }

    @Test
    void anArmedStateTheLinkDoesntKnowCountsAsArmed() {
        flag.attach(link, () -> new PossiblyFlying.Word(null, now[0]));
        assertTrue(flag.holds());
        PossiblyFlying broken = new PossiblyFlying(() -> now[0]);
        broken.attach(link, () -> {
            throw new IllegalStateException("no telemetry");
        });
        assertTrue(broken.holds(), "a link that can't say may fly");
    }

    @Test
    void aLinkClosedWhileArmedKeepsTheFlagForTheWindowAndADisarmedOneDoesNot() {
        flag.attach(link, () -> word[0]);
        word[0] = new PossiblyFlying.Word(true, now[0]);
        flag.detach(link); // DISCONNECT (confirmed) or the window closed while armed
        now[0] += 60_000;
        assertTrue(flag.holds(), "the drone may still be up there");
        now[0] += PossiblyFlying.LOST_DRONE_WINDOW_MS;
        assertFalse(flag.holds());

        Object other = new Object();
        flag.attach(other, () -> new PossiblyFlying.Word(false, now[0]));
        flag.detach(other);
        assertFalse(flag.holds(), "disarmed at the end: nothing kept");
    }

    @Test
    void aReconnectToTheSameDroneThatSaysDisarmedEndsItAtOnce() {
        // 1.0.50, the pilot's test: MobileZulu lost armed (4G LOSS), DISCONNECT confirmed; later CONNECT to it again
        Object first = new Object();
        flag.attach(first, "MobileZulu", () -> word[0]);
        word[0] = new PossiblyFlying.Word(true, now[0]);
        now[0] += 60_000;
        flag.detach(first);
        assertTrue(flag.holds());
        PossiblyFlying.Hold h = flag.why();
        assertEquals("MobileZulu", h.drone());
        assertFalse(h.live());
        assertTrue(flag.reason().startsWith("MobileZulu was lost armed 1 min ago; a drone here may be flying (until "), flag.reason());

        now[0] += 120_000;
        PossiblyFlying.Word[] second = {PossiblyFlying.Word.NOTHING};
        Object again = new Object();
        flag.attach(again, "mobilezulu ", () -> second[0]); // the same drone, however it is written
        assertTrue(flag.holds(), "nothing heard yet from the new link: still held");
        second[0] = new PossiblyFlying.Word(false, now[0]);
        assertFalse(flag.holds(), "a fresh DISARMED from that drone: it is down");
        flag.detach(again);
        assertFalse(flag.holds());
    }

    @Test
    void aLinkToAnotherDroneSayingDisarmedDoesNotEndIt() {
        Object zulu = new Object(), xray = new Object();
        flag.attach(zulu, "MobileZulu", () -> new PossiblyFlying.Word(true, now[0]));
        flag.detach(zulu);
        flag.attach(xray, "MobileX-ray", () -> new PossiblyFlying.Word(false, now[0] + 1));
        now[0] += 5_000;
        assertTrue(flag.holds(), "X-ray is down; Zulu may still be up there");
        assertEquals("MobileZulu", flag.why().drone());
        flag.detach(xray);
        assertTrue(flag.holds(), "X-ray's link ending disarmed says nothing of Zulu");
    }

    @Test
    void aLaterLinkThatEndsDisarmedClearsItButAStaleDisarmedDoesNot() {
        Object a = new Object();
        flag.attach(a, "MobileZulu", () -> new PossiblyFlying.Word(true, 2_000_000));
        now[0] = 2_000_000;
        flag.detach(a);
        Object stale = new Object();
        flag.attach(stale, "MobileZulu", () -> new PossiblyFlying.Word(false, 1_999_000)); // older than the armed word
        assertTrue(flag.holds(), "a DISARMED older than the armed word proves nothing");
        flag.detach(stale);
        assertTrue(flag.holds());
        Object b = new Object();
        flag.attach(b, "MobileZulu", () -> new PossiblyFlying.Word(false, 2_000_500));
        flag.detach(b); // connected for a moment, heard DISARMED, closed: down
        assertFalse(flag.holds());
    }

    @Test
    void itsDownResumeForgetsLostDronesButNeverAnOpenArmedLink() {
        Object lost = new Object(), flying = new Object();
        flag.attach(lost, "MobileZulu", () -> new PossiblyFlying.Word(true, now[0]));
        flag.detach(lost);
        assertTrue(flag.resumable());
        boolean[] armed = {true};
        flag.attach(flying, "MobileYankee", () -> new PossiblyFlying.Word(armed[0], now[0]));
        assertFalse(flag.resumable(), "an open link says armed: nothing for the pilot to resume");
        assertEquals("MobileYankee", flag.why().drone());
        assertEquals("MobileYankee is armed: a drone here is flying", flag.reason());
        assertEquals(java.util.List.of("MobileZulu"), flag.resume());
        assertTrue(flag.holds(), "Yankee still says armed");
        armed[0] = false;
        assertFalse(flag.holds(), "Zulu forgotten, Yankee disarmed");
        assertEquals(java.util.List.of(), flag.resume());
        assertNull(flag.reason());
    }

    @Test
    void anyLinkThatMayFlyHoldsIt() {
        Object a = new Object(), b = new Object();
        flag.attach(a, () -> new PossiblyFlying.Word(false, now[0]));
        flag.attach(b, () -> new PossiblyFlying.Word(true, now[0]));
        assertTrue(flag.holds());
        flag.detach(b);
        assertTrue(flag.holds(), "b's last word was armed");
    }
}
