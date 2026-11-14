package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import static de.droiddrone.common.CaptureClock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone's capture-to-send time from a frame's presentation time, whatever clock the camera stamped it on. The
 * phone here has been up for UP, with elapsedRealtime ahead of the uptime clock by what it slept.
 */
class CaptureClockTest {
    static final long MS = 1_000_000, S = 1000 * MS, HOUR = 3600 * S;
    /** The uptime clock now, ns. */
    static final long UP = 50 * HOUR;

    /** A stamp (us) for a picture taken ageMs ago on a clock that reads clockNs now. */
    static long stampUs(long clockNs, long ageMs) {
        return (clockNs - ageMs * MS) / 1000;
    }

    @Test
    void aStampOnThePhonesOwnClockIsItsAge() {
        long realtime = UP + 3 * HOUR; // it has slept 3 hours in all
        CaptureClock c = new CaptureClock();
        assertEquals(80, c.captureToSendMs(stampUs(realtime, 80), realtime, UP));
        assertEquals(SEND_CLOCK, c.source(), "on the uptime clock the picture would be from the future");
    }

    @Test
    void aStampOnTheUptimeClockIsItsAgeToo() {
        long realtime = UP + 3 * HOUR;
        CaptureClock c = new CaptureClock();
        assertEquals(80, c.captureToSendMs(stampUs(UP, 80), realtime, UP));
        assertEquals(OTHER_CLOCK, c.source(), "on elapsedRealtime it would be three hours old");
    }

    @Test
    void whereBothClocksFitTheSmallerAgeIsTaken() {
        long realtime = UP + 2 * S; // slept two seconds in all: both ages could be
        CaptureClock c = new CaptureClock();
        assertEquals(80, c.captureToSendMs(stampUs(UP, 80), realtime, UP));
        assertEquals(OTHER_CLOCK, c.source());
        // a phone that never slept: the two clocks are one
        assertEquals(80, c.captureToSendMs(stampUs(UP, 80), UP, UP));
        assertEquals(SEND_CLOCK, c.source());
    }

    @Test
    void theFigureIsRoundedAndNeverOverItsSixteenBits() {
        CaptureClock c = new CaptureClock();
        assertEquals(0, c.captureToSendMs(UP / 1000, UP, UP), "sent the instant it was taken");
        assertEquals(0, c.captureToSendMs((UP - 400_000) / 1000, UP, UP));
        assertEquals(1, c.captureToSendMs((UP - 600_000) / 1000, UP, UP));
        assertEquals(10_000, c.captureToSendMs(stampUs(UP, 10_000), UP, UP), "the oldest picture that can be");
        assertEquals(UNKNOWN, c.captureToSendMs(stampUs(UP, 10_001), UP, UP));
        assertEquals(NONE, c.source());
        assertTrue(PLAUSIBLE_NS / MS <= MAX_MS && MAX_MS < UNKNOWN, "every figure fits under the code for none");
    }

    @Test
    void aPictureFromTheFutureOrFromLongAgoHasNoFigure() {
        long realtime = UP + 3 * HOUR;
        CaptureClock c = new CaptureClock();
        assertEquals(UNKNOWN, c.captureToSendMs(stampUs(realtime, -5), realtime, UP), "5 ms ahead of both clocks");
        assertEquals(UNKNOWN, c.captureToSendMs(stampUs(UP, 60_000), realtime, UP), "a minute old");
        assertEquals(UNKNOWN, c.captureToSendMs(0, realtime, UP), "counted from the stream's start, say");
        assertEquals(UNKNOWN, c.captureToSendMs(Long.MIN_VALUE / 2000, realtime, UP));
        assertEquals(NONE, c.source());
    }

    /** A camera whose stamps count from a base of its own: this far from elapsedRealtime. */
    static final long OWN_BASE = 7 * HOUR + 123_456_789;

    /** A picture taken ageMs before nowNs on that camera, its callback delayMs after the exposure began. */
    static long ownStamped(CaptureClock c, long nowNs, long ageMs, long delayMs) {
        long takenNs = nowNs - ageMs * MS;
        long stampUs = (takenNs - OWN_BASE) / 1000;
        c.stamped(stampUs, takenNs + delayMs * MS);
        return stampUs;
    }

    @Test
    void aBaseOfTheCamerasOwnIsLearnedFromTheStampedPairs() {
        long realtime = UP + 3 * HOUR;
        CaptureClock c = new CaptureClock();
        assertEquals(UNKNOWN, c.captureToSendMs((realtime - OWN_BASE) / 1000, realtime, UP), "nothing stamped yet");
        // 30 pictures a second, the callback 4 to 23 ms after the exposure began, each sent 70 ms after it
        long[] delays = {9, 23, 4, 15, 12, 4, 19, 7};
        for (int i = 0; i < 90; i++) {
            long sendNs = realtime + i * 33 * MS;
            long stamp = ownStamped(c, sendNs, 70, delays[i % delays.length]);
            int ms = c.captureToSendMs(stamp, sendNs, sendNs - 3 * HOUR);
            assertEquals(STAMPS, c.source());
            if (i >= 2) assertEquals(66, ms, "70 ms, short by the quickest callback (4 ms), whatever this picture's own was");
            else assertTrue(ms >= 47 && ms <= 66, "before the quickest has come: shorter still, " + ms);
        }
    }

    @Test
    void aFrameWhoseOwnCallbackWasMissedStillHasItsFigure() {
        long realtime = UP + 3 * HOUR;
        CaptureClock c = new CaptureClock();
        for (int i = 0; i < 10; i++) ownStamped(c, realtime + i * 33 * MS, 0, 5);
        long sendNs = realtime + 400 * MS;
        long stamp = (sendNs - 90 * MS - OWN_BASE) / 1000; // taken 90 ms ago, never stamped
        assertEquals(85, c.captureToSendMs(stamp, sendNs, sendNs - 3 * HOUR));
        // and one sent sooner after its exposure than the quickest callback ever came: 0, not less
        assertEquals(0, c.captureToSendMs((sendNs - 2 * MS - OWN_BASE) / 1000, sendNs, sendNs - 3 * HOUR));
    }

    @Test
    void theClocksAreTriedBeforeTheStamps() {
        // the capture callback's stamps on elapsedRealtime, the encoder's moved to the uptime clock: the pairs say
        // nothing about that frame, the uptime clock does - and exactly, not short by the callback
        long realtime = UP + 3 * HOUR;
        CaptureClock c = new CaptureClock();
        for (int i = 0; i < 30; i++) {
            long takenNs = realtime + i * 33 * MS;
            c.stamped(takenNs / 1000, takenNs + 6 * MS);
        }
        long sendRealtime = realtime + 1100 * MS, sendUp = sendRealtime - 3 * HOUR;
        assertEquals(70, c.captureToSendMs(stampUs(sendUp, 70), sendRealtime, sendUp));
        assertEquals(OTHER_CLOCK, c.source());
        assertEquals(70, c.captureToSendMs(stampUs(sendRealtime, 70), sendRealtime, sendUp));
        assertEquals(SEND_CLOCK, c.source());
    }

    @Test
    void theStreamStartedAgainForgetsTheOldBase() {
        long realtime = UP + 3 * HOUR;
        CaptureClock c = new CaptureClock();
        long stamp = 0;
        for (int i = 0; i < 30; i++) stamp = ownStamped(c, realtime + i * 33 * MS, 0, 5);
        assertEquals(STAMPS, source(c, stamp, realtime + S));
        c.restart();
        assertEquals(NONE, source(c, stamp, realtime + S), "another camera may count from elsewhere");
        assertEquals(NONE, c.source());
    }

    private static int source(CaptureClock c, long stampUs, long sendNs) {
        c.captureToSendMs(stampUs, sendNs, sendNs - 3 * HOUR);
        return c.source();
    }

    @Test
    void aBaseThatMovedIsLearnedAgainWithinTwoBuckets() {
        long now = UP + 3 * HOUR;
        CaptureClock c = new CaptureClock();
        for (int i = 0; i < 30; i++) ownStamped(c, now += 33 * MS, 0, 5);
        // the camera opened again without a word: its stamps now count from an hour later (the differences grow)
        long moved = OWN_BASE + HOUR;
        long firstMoved = now;
        int unknown = 0, last = -1;
        for (int i = 0; i < 700; i++) {
            now += 33 * MS;
            long takenNs = now - 60 * MS;
            long stamp = (takenNs - moved) / 1000;
            c.stamped(stamp, takenNs + 5 * MS);
            last = c.captureToSendMs(stamp, now, now - 3 * HOUR);
            if (last == UNKNOWN) unknown++;
            else assertEquals(55, last, "never a figure by the old base");
        }
        assertEquals(55, last);
        assertTrue(unknown > 0 && unknown * 33 * MS <= 2 * BUCKET_NS + S, "none for " + unknown + " frames, no longer than two buckets");
        assertTrue(now - firstMoved > 2 * BUCKET_NS);
        // a base that moved the other way (the differences shrink) is the new smallest at once
        long back = OWN_BASE - HOUR;
        long takenNs = now + 33 * MS - 60 * MS;
        c.stamped((takenNs - back) / 1000, takenNs + 5 * MS);
        assertEquals(55, c.captureToSendMs((takenNs - back) / 1000, now + 33 * MS, now + 33 * MS - 3 * HOUR));
    }

    @Test
    void pairsNoLongerStampedAreForgotten() {
        long realtime = UP + 3 * HOUR;
        CaptureClock c = new CaptureClock();
        long stamp = 0;
        for (int i = 0; i < 30; i++) stamp = ownStamped(c, realtime + i * 33 * MS, 0, 5);
        long later = realtime + S + 2 * BUCKET_NS;
        long stampLater = stamp + (later - realtime - 29 * 33 * MS) / 1000 - 60_000; // a picture 60 ms old then, by the same base
        assertEquals(UNKNOWN, c.captureToSendMs(stampLater, later, later - 3 * HOUR), "nothing stamped for two buckets");
        // within one bucket more they would still count
        CaptureClock d = new CaptureClock();
        for (int i = 0; i < 30; i++) stamp = ownStamped(d, realtime + i * 33 * MS, 0, 5);
        long soon = realtime + S + BUCKET_NS;
        long stampSoon = stamp + (soon - realtime - 29 * 33 * MS) / 1000 - 60_000;
        assertEquals(55, d.captureToSendMs(stampSoon, soon, soon - 3 * HOUR));
    }

    @Test
    void theClocksMayWrap() {
        // a clock about to pass the end of its 64 bits (none does in a phone's life): only differences are taken
        long send = Long.MIN_VALUE + 30 * MS; // 80 ms after a picture stamped just before the wrap
        long stampNs = send - 80 * MS; // wraps back to just under Long.MAX_VALUE
        assertTrue(stampNs > 0 && send < 0);
        CaptureClock c = new CaptureClock();
        assertEquals(80, c.captureToSendMs(stampNs / 1000, send, send - 3 * HOUR));
        // and the pairs across it
        CaptureClock own = new CaptureClock();
        long base = 5 * HOUR;
        own.stamped((stampNs - base) / 1000, stampNs + 5 * MS);
        assertEquals(75, own.captureToSendMs((stampNs - base) / 1000, send, send - 3 * HOUR));
        assertEquals(STAMPS, own.source());
    }

    @Test
    void thePingAFrameAnswersAndHowLongItWasHeld() {
        PingEcho e = new PingEcho();
        long now = UP;
        assertEquals(UNKNOWN, PingEcho.holdMs(e.at(now)), "no Ping yet");
        assertEquals(0, PingEcho.echo(e.at(now)));
        // the PC's stamp is 64 bits: its low 32 go back
        e.received(0x1234_FFFF_FFC0L, now);
        long at = e.at(now + 12 * MS + 400_000);
        assertEquals(0xFFFF_FFC0L, PingEcho.echo(at));
        assertEquals(12, PingEcho.holdMs(at));
        assertEquals(13, PingEcho.holdMs(e.at(now + 12 * MS + 600_000)), "to the nearest millisecond");
        assertEquals(0, PingEcho.holdMs(e.at(now)));
        e.received(0x1234_FFFF_FFF0L, now + 50 * MS);
        at = e.at(now + 53 * MS);
        assertEquals(0xFFFF_FFF0L, PingEcho.echo(at), "the next Ping takes its place");
        assertEquals(3, PingEcho.holdMs(at));
        // and the stamps count on across the 32 bits' wrap
        e.received(0x1235_0000_0022L, now + 100 * MS);
        assertEquals(0x22, PingEcho.echo(e.at(now + 100 * MS)));
        // held too long to say in 16 bits: not known
        assertEquals(MAX_MS, PingEcho.holdMs(e.at(now + 100 * MS + MAX_MS * MS)));
        assertEquals(UNKNOWN, PingEcho.holdMs(e.at(now + 100 * MS + (MAX_MS + 1L) * MS)));
        e.restart();
        assertEquals(UNKNOWN, PingEcho.holdMs(e.at(now + 200 * MS)), "a new session: none of its Pings yet");
    }

    @Test
    void aPingOvertakenOnTheWayIsLeftOut() {
        PingEcho e = new PingEcho();
        long now = UP;
        e.received(5050, now);
        e.received(5000, now + 4 * MS); // stamped 50 ms earlier, here 4 ms later
        long at = e.at(now + 10 * MS);
        assertEquals(5050, PingEcho.echo(at), "the late one's lateness is not the frame's");
        assertEquals(10, PingEcho.holdMs(at));
        e.received(5100, now + 50 * MS);
        assertEquals(5100, PingEcho.echo(e.at(now + 50 * MS)));
        // nothing newer for a second: an "earlier" stamp is another clock's (a PC started again), taken
        e.received(77, now + 50 * MS + PingEcho.REORDER_NS);
        assertEquals(77, PingEcho.echo(e.at(now + 2 * S)));
    }
}
