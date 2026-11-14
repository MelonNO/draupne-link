package de.droiddrone.pc.video;

import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static de.droiddrone.pc.video.GlassToGlass.UNKNOWN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The glass-to-glass estimate against a made-up link whose every delay is known: the PC pings every 50 ms, the phone
 * takes a picture every 33 ms, sends its first packet 45 ms later with the last Ping's echo, and the PC shows it 25 ms
 * after that packet came. The phone's clock is never handed over - only the hold, as on the wire.
 */
class GlassToGlassTest {
    static final int CAPTURE_TO_SEND = 45, PC = 25, FRAME_EVERY = 33, PING_EVERY = 50;

    /** The link: its best time each way, and what a Ping (by its count) and a frame (by its count) wait over that. */
    static final class Link {
        final GlassToGlass g = new GlassToGlass();
        int there, back;
        IntUnaryOperator pingLate = i -> 0, frameLate = i -> 0;
        /** The PC's clock when the first Ping leaves (Pings from then on), and the phone's first picture a little after. */
        long start = 5_000_000;
        int frames;
        short number = 1;
        /** The last frame's figure and what it truly was, and when it was shown. */
        int figure, truth;
        long now;

        Link(int there, int back) {
            this.there = there;
            this.back = back;
        }

        private long pingSent(long n) {
            return start + n * PING_EVERY;
        }

        private long pingArrives(long n) {
            return pingSent(n) + there + pingLate.applyAsInt((int) n);
        }

        /** One picture more, from the camera to the screen; false if it got no figure. */
        boolean frame() {
            long captured = start + 200 + (long) frames * FRAME_EVERY;
            long sent = captured + CAPTURE_TO_SEND;
            long ping = (sent - start - there) / PING_EVERY + 1; // the last Ping the phone had taken by then
            while (ping >= 0 && pingArrives(ping) > sent) ping--;
            long arrives = sent + back + frameLate.applyAsInt(frames);
            long shown = arrives + PC;
            if (ping >= 0) g.onFrameTime(number, CAPTURE_TO_SEND, pingSent(ping) & 0xFFFFFFFFL, (int) (sent - pingArrives(ping)), arrives);
            else g.onFrameTime(number, CAPTURE_TO_SEND, 0, 65535, arrives);
            figure = g.onShown(number, shown);
            truth = (int) (shown - captured);
            now = shown;
            frames++;
            number = number == Short.MAX_VALUE ? 2 : (short) (number + 1);
            return figure != UNKNOWN;
        }

        /** n pictures; the largest error of those that got a figure (-1: none did). */
        int run(int n) {
            int worst = -1;
            for (int i = 0; i < n; i++) if (frame()) worst = Math.max(worst, Math.abs(figure - truth));
            return worst;
        }
    }

    @Test
    void aSymmetricLinkGivesTheTrueFigure() {
        Link l = new Link(40, 40);
        l.frameLate = i -> i * 7 % 31; // each frame waits 0 to 30 ms on its way; the first few show the link's best
        l.run(30);
        int worst = l.run(600);
        assertTrue(worst >= 0 && worst <= 1, "off by " + worst + " ms at most");
        assertEquals(l.truth, l.figure, 1);
        assertEquals(80, l.g.bestRoundTripMs());
        assertEquals(CAPTURE_TO_SEND, l.g.lastCaptureToSendMs(l.now));
        assertEquals(PC, l.g.lastPcMs(l.now));
        assertEquals(l.truth - CAPTURE_TO_SEND - PC, l.g.lastNetworkMs(l.now), 1, "the frame's own wait is all in it");
        // glass to glass on this link: 45 + 40..70 + 25
        int smoothed = l.g.smoothedMs(l.now);
        assertTrue(smoothed >= 110 && smoothed <= 140, "smoothed " + smoothed);
    }

    @Test
    void anAsymmetricLinkErrsByHalfTheDifferenceOfItsTwoWays() {
        // the way back (the phone's upload) slower: 20 ms there, 80 back - the figure is short by 30
        Link slowBack = new Link(20, 80);
        slowBack.frameLate = i -> i * 7 % 31;
        slowBack.run(30);
        for (int i = 0; i < 300; i++) {
            assertTrue(slowBack.frame());
            assertEquals(-30, slowBack.figure - slowBack.truth, 1);
        }
        // the way there slower: long by 30
        Link slowThere = new Link(80, 20);
        slowThere.frameLate = i -> i * 7 % 31;
        slowThere.run(30);
        for (int i = 0; i < 300; i++) {
            assertTrue(slowThere.frame());
            assertEquals(30, slowThere.figure - slowThere.truth, 1);
        }
        // the bound as the class states it: half the difference, and what the echoed Ping itself waited on its way
        Link jittery = new Link(20, 80);
        jittery.frameLate = i -> i % 3 == 0 ? 0 : i * 7 % 31;
        jittery.pingLate = i -> i * 3 % 11; // 0 to 10 ms; now and then neither waits, and the link shows its best
        jittery.run(60);
        for (int i = 0; i < 600; i++) {
            assertTrue(jittery.frame());
            int error = jittery.figure - jittery.truth;
            assertTrue(error >= -30 - 1 && error <= -30 + 10 + 1, "error " + error);
            assertTrue(Math.abs(error) <= Math.abs(20 - 80) / 2 + 1, "never over half the difference here: " + error);
        }
    }

    @Test
    void thereIsNoFigureUntilEnoughRoundTripsHaveCome() {
        Link l = new Link(40, 40);
        for (int i = 0; i < GlassToGlass.MIN_SAMPLES - 1; i++) {
            assertFalse(l.frame(), "frame " + i + ": the link's best isn't known yet");
            assertEquals(UNKNOWN, l.g.smoothedMs(l.now));
        }
        assertTrue(l.frame());
        assertEquals(l.truth, l.figure, 1);
        assertEquals(l.figure, l.g.smoothedMs(l.now));
        assertEquals(l.figure, l.g.lastMs(l.now));
    }

    @Test
    void anOlderPhoneShowsNoFigure() {
        // no FRAME_TIME ever: frames are shown, Pongs come - nothing to say
        GlassToGlass g = new GlassToGlass();
        long now = 5_000_000;
        for (short n = 1; n < 200; n++) {
            now += FRAME_EVERY;
            if (n % 2 == 0) g.onRoundTrip(80, now);
            assertEquals(UNKNOWN, g.onShown(n, now));
        }
        assertEquals(UNKNOWN, g.smoothedMs(now));
        assertEquals(UNKNOWN, g.lastMs(now));
        assertEquals(UNKNOWN, g.lastNetworkMs(now));
        assertEquals(UNKNOWN, g.takeMaxMs());
    }

    @Test
    void aPhoneThatHasTakenNoPingOrKnowsNoCaptureTimeGivesNone() {
        GlassToGlass g = new GlassToGlass();
        long now = 5_000_000;
        for (short n = 1; n < 100; n++) { // hold 65535: no Ping taken
            now += FRAME_EVERY;
            g.onFrameTime(n, CAPTURE_TO_SEND, 0, 65535, now);
            assertEquals(UNKNOWN, g.onShown(n, now + PC));
        }
        assertEquals(UNKNOWN, g.bestRoundTripMs(), "nothing counted by it either");
        for (short n = 100; n < 200; n++) { // capture 65535: the round trips count, the figure has no first part
            now += FRAME_EVERY;
            g.onFrameTime(n, 65535, (now - 90) & 0xFFFFFFFFL, 10, now);
            assertEquals(UNKNOWN, g.onShown(n, now + PC));
        }
        assertEquals(80, g.bestRoundTripMs());
        now += FRAME_EVERY;
        g.onFrameTime((short) 200, CAPTURE_TO_SEND, (now - 90) & 0xFFFFFFFFL, 10, now);
        assertEquals(CAPTURE_TO_SEND + 40 + PC, g.onShown((short) 200, now + PC), "the moment it knows one");
        // a Ping held for seconds: the Pings no longer reach the phone - not counted by
        now += FRAME_EVERY;
        g.onFrameTime((short) 201, CAPTURE_TO_SEND, (now - 6090) & 0xFFFFFFFFL, GlassToGlass.MAX_HOLD_MS + 1, now);
        assertEquals(UNKNOWN, g.onShown((short) 201, now + PC));
    }

    @Test
    void aReconnectForgetsEverything() {
        Link l = new Link(40, 40);
        l.run(100);
        assertNotEquals(UNKNOWN, l.g.smoothedMs(l.now));
        l.g.reset();
        assertEquals(UNKNOWN, l.g.smoothedMs(l.now));
        assertEquals(UNKNOWN, l.g.lastMs(l.now));
        assertEquals(UNKNOWN, l.g.takeMaxMs());
        assertEquals(UNKNOWN, l.g.bestRoundTripMs());
        // the new session's link is another: learned from its own round trips, none of the old best in it
        l.there = 100;
        l.back = 100;
        for (int i = 0; i < GlassToGlass.MIN_SAMPLES - 1; i++) assertFalse(l.frame());
        assertTrue(l.frame());
        assertEquals(l.truth, l.figure, 1);
        assertEquals(200, l.g.bestRoundTripMs());
    }

    @Test
    void aClockJumpForgetsEverything() {
        Link l = new Link(40, 40);
        l.run(100);
        assertNotEquals(UNKNOWN, l.g.smoothedMs(l.now));
        // the echo is another PC's stamp all at once (a takeover, a session this PC didn't start): a frame here
        // an hour before its Ping left
        l.g.onFrameTime((short) 9000, CAPTURE_TO_SEND, (l.now + 3_600_000) & 0xFFFFFFFFL, 10, l.now);
        assertEquals(UNKNOWN, l.g.onShown((short) 9000, l.now + PC));
        assertEquals(UNKNOWN, l.g.smoothedMs(l.now + PC), "no old figure stays up");
        assertEquals(UNKNOWN, l.g.bestRoundTripMs());
        // and one that would have been an hour on the way
        Link m = new Link(40, 40);
        m.run(100);
        m.g.onFrameTime((short) 9000, CAPTURE_TO_SEND, (m.now - 3_600_000) & 0xFFFFFFFFL, 10, m.now);
        assertEquals(UNKNOWN, m.g.smoothedMs(m.now));
        // back on its own clock: a figure again after the usual round trips, and the right one
        for (int i = 0; i < GlassToGlass.MIN_SAMPLES - 1; i++) assertFalse(m.frame());
        assertTrue(m.frame());
        assertEquals(m.truth, m.figure, 1);
    }

    @Test
    void theStampsMayWrapTheir32Bits() {
        Link l = new Link(40, 40);
        l.start = 0x1_0000_0000L - 2000; // the PC's clock passes 2^32 ms two seconds in
        l.frameLate = i -> i * 7 % 31;
        l.run(30);
        int worst = l.run(200);
        assertTrue(l.now > 0x1_0000_0000L + 1000, "past the wrap");
        assertTrue(worst >= 0 && worst <= 1, "off by " + worst + " ms at most");
        // and frame numbers theirs (32767 -> 2)
        Link n = new Link(40, 40);
        n.number = Short.MAX_VALUE - 50;
        n.run(30);
        worst = n.run(200);
        assertTrue(n.number > 2 && n.number < 300 && worst >= 0 && worst <= 1, "off by " + worst);
    }

    @Test
    void theLinksBestIsTheLowestOfTwoWindows() {
        Link l = new Link(40, 40);
        l.run(60);
        assertEquals(80, l.g.bestRoundTripMs());
        // the path gets slower for good (a relay): for up to two windows the old best makes the figure long by half
        // the difference, then the new best is learned
        l.there = 70;
        l.back = 70;
        l.frame();
        assertEquals(l.truth + 30, l.figure, 1, "140 ms round, the best still 80: 100 for the network, truly 70");
        int frames = (int) (2 * GlassToGlass.WINDOW_MS / FRAME_EVERY) + 5;
        l.run(frames);
        assertEquals(140, l.g.bestRoundTripMs());
        assertEquals(l.truth, l.figure, 1);
        // quicker again: known with the first frame
        l.there = 40;
        l.back = 40;
        l.frame();
        assertEquals(80, l.g.bestRoundTripMs());
        assertEquals(l.truth, l.figure, 1);
    }

    @Test
    void aPongMayShowTheLinksBest() {
        // every frame waits at least 20 ms behind the one before; a Pong for once does not
        Link l = new Link(40, 40);
        l.frameLate = i -> 20 + i % 5;
        l.run(60);
        assertEquals(100, l.g.bestRoundTripMs());
        assertEquals(l.truth - 10, l.figure, 1, "short by half the queue that always stood");
        l.g.onRoundTrip(80, l.now);
        l.frame();
        assertEquals(l.truth, l.figure, 1);
        l.g.onRoundTrip(-1, l.now); // no pong lately: nothing
        l.g.onRoundTrip(GlassToGlass.MAX_ROUND_MS + 1, l.now);
        assertEquals(80, l.g.bestRoundTripMs());
    }

    @Test
    void theScreensFigureIsEvenedOutAndGoesWhenThePicturesStop() {
        Link l = new Link(40, 40);
        l.run(100);
        int steady = l.g.smoothedMs(l.now);
        assertEquals(CAPTURE_TO_SEND + 40 + PC, steady);
        l.frameLate = i -> 200; // the picture is 200 ms later all at once
        l.frame();
        assertEquals(steady + 200, l.figure, 1);
        assertEquals(steady + 20, l.g.smoothedMs(l.now), 1, "a tenth of the step with the first frame");
        l.run(60);
        assertEquals(steady + 200, l.g.smoothedMs(l.now), 2, "and all of it two seconds on");
        assertEquals(steady + 200, l.g.smoothedMs(l.now + GlassToGlass.STALE_MS));
        assertEquals(UNKNOWN, l.g.smoothedMs(l.now + GlassToGlass.STALE_MS + 1), "no picture for two seconds: no figure");
        assertEquals(UNKNOWN, l.g.lastMs(l.now + GlassToGlass.STALE_MS + 1));
        // the next picture after such a gap starts the average afresh
        long gap = 5000;
        l.start += gap;
        l.frameLate = i -> 0;
        assertTrue(l.frame());
        assertEquals(l.figure, l.g.smoothedMs(l.now));
    }

    @Test
    void theLogsFigureIsTheHighestSinceItLastAsked() {
        Link l = new Link(40, 40);
        l.run(60);
        l.g.takeMaxMs();
        l.frameLate = i -> i % 30 == 7 ? 150 : 0; // one frame in 30 held up
        int highest = 0;
        for (int i = 0; i < 30; i++) {
            l.frame();
            highest = Math.max(highest, l.figure);
        }
        assertEquals(CAPTURE_TO_SEND + 40 + 150 + PC, highest, 1);
        assertEquals(highest, l.g.takeMaxMs());
        assertEquals(UNKNOWN, l.g.takeMaxMs(), "asked again at once: no frame since");
    }

    @Test
    void aFrameNeverShownOrShownFarTooLateGivesNoFigure() {
        Link l = new Link(40, 40);
        l.run(60);
        long now = l.now + FRAME_EVERY;
        l.g.onFrameTime((short) 5000, CAPTURE_TO_SEND, (now - 90) & 0xFFFFFFFFL, 10, now);
        assertEquals(UNKNOWN, l.g.onShown((short) 5001, now + PC), "another frame");
        assertEquals(UNKNOWN, l.g.onShown((short) (5000 + 64), now + PC), "nor one that shares its place in the list");
        assertEquals(UNKNOWN, l.g.onShown((short) 5000, now + GlassToGlass.MAX_SHOW_MS + 1), "the same number a wrap later");
        l.g.onFrameTime((short) 5002, CAPTURE_TO_SEND, (now - 90) & 0xFFFFFFFFL, 10, now);
        assertEquals(CAPTURE_TO_SEND + 40 + PC, l.g.onShown((short) 5002, now + PC));
        assertEquals(UNKNOWN, l.g.onShown((short) 5002, now + PC + 1), "shown once");
    }
}
