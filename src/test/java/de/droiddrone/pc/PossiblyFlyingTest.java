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
    void anyLinkThatMayFlyHoldsIt() {
        Object a = new Object(), b = new Object();
        flag.attach(a, () -> new PossiblyFlying.Word(false, now[0]));
        flag.attach(b, () -> new PossiblyFlying.Word(true, now[0]));
        assertTrue(flag.holds());
        flag.detach(b);
        assertTrue(flag.holds(), "b's last word was armed");
    }
}
