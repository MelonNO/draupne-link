package de.droiddrone.pc.gui;

import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.setup.Tailscale;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static de.droiddrone.pc.setup.Tailscale.PhoneState.NOT_INSTALLED;
import static de.droiddrone.pc.setup.Tailscale.PhoneState.OFFLINE;
import static de.droiddrone.pc.setup.Tailscale.PhoneState.ONLINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Q-009 (the pilot's answer, PC only): PILOT UAV pressed and not one packet back. Tailscale has the phone online and
 * its Tailscale answers a ping: the connecting screen says "Phone online, no answer: DRAUPNE Flight closed, or another
 * key? (MENU > LINK)" after 8 s. Offline: the offline words as before. Never for a phone that answered anything, and
 * never for a link that simply comes up late over a relay.
 */
class PhoneSilenceTest {
    private static final String HINT = "PHONE ONLINE, NO ANSWER: DRAUPNE FLIGHT CLOSED, OR ANOTHER KEY? (MENU > LINK)";
    private static final Tailscale.PingResult PONG = new Tailscale.PingResult(true, false, "DERP(fra)", 120, 120, "pong");
    private static final Tailscale.PingResult NO_PONG = new Tailscale.PingResult(false, false, "", -1, -1, "timeout");

    /** A pilot's wait for its first link: never up, no sleeper waited for, CONNECTING. */
    private static PhoneSilence.Wait waited(long ms, boolean heard, long pongAgoMs) {
        return new PhoneSilence.Wait(false, ControlLink.State.CONNECTING, false, ms, heard, pongAgoMs);
    }

    @Test
    void onlineItsTailscaleAnswersAndSilentFor8sSaysClosedOrAnotherKey() {
        assertEquals(HINT, OsdDataBuilder.linkHint(ONLINE, waited(8_001, false, 6_000), false));
        assertEquals(HINT, OsdDataBuilder.linkHint(ONLINE, waited(60_000, false, 55_000), true), "as long as it is silent");
        assertNull(OsdDataBuilder.linkHint(ONLINE, waited(7_999, false, 6_000), false), "the first 8 s: CONNECTING alone");
        assertEquals(OsdDataBuilder.UNANSWERED, OsdDataBuilder.linkHint(ONLINE, waited(30_000, false, -1), false),
                "its Tailscale never answered the ping: I-004's questions");
        assertEquals(OsdDataBuilder.UNANSWERED, OsdDataBuilder.linkHint(ONLINE, waited(8_500, false, 900), false),
                "Tailscale answered just now: the link's Connects get their time first");
        assertEquals(OsdDataBuilder.UNANSWERED, OsdDataBuilder.linkHint(ONLINE, waited(30_000, true, 20_000), false),
                "the phone answered something (a session of its own, another PC's): not closed, not another key");
        assertNull(OsdDataBuilder.linkHint(ONLINE, new PhoneSilence.Wait(false, ControlLink.State.CONNECTING, true, 30_000, false, 20_000), false),
                "a sleeper waited for: WAKING");
        assertNull(OsdDataBuilder.linkHint(ONLINE, null, false), "watching: never");
    }

    @Test
    void offlineKeepsTheOfflineWords() {
        assertEquals("PHONE NOT ON TAILSCALE", OsdDataBuilder.linkHint(OFFLINE, waited(30_000, false, 20_000), false));
        assertEquals("PHONE NOT ON TAILSCALE", OsdDataBuilder.linkHint(OFFLINE, waited(1_000, false, -1), false), "from the start, as before");
        assertFalse(PhoneSilence.pingDue(OFFLINE, waited(3_000, false, -1), Long.MAX_VALUE), "no ping for an offline phone");
    }

    @Test
    void tailscaleMissingOnThisPcGivesNoHint() {
        assertEquals("TAILSCALE NOT INSTALLED ON THIS PC", OsdDataBuilder.linkHint(NOT_INSTALLED, waited(30_000, false, 20_000), false));
        assertFalse(PhoneSilence.pingDue(NOT_INSTALLED, waited(3_000, false, -1), Long.MAX_VALUE));
        // not asked at all (a virtual phone, no address): I-004's questions, no ping
        assertEquals(OsdDataBuilder.UNANSWERED, OsdDataBuilder.linkHint(null, waited(30_000, false, -1), false));
        assertFalse(PhoneSilence.pingDue(null, waited(3_000, false, -1), Long.MAX_VALUE));

        Clocked t = new Clocked();
        for (long ms = 0; ms <= 30_000; ms += 100) {
            t.now = ms;
            t.silence.follow(t.connection, "100.64.0.20", NOT_INSTALLED, t.wait(ms, false, ControlLink.State.CONNECTING));
        }
        t.runAll(PONG);
        assertEquals(0, t.pings, "Tailscale missing: never asked");
    }

    @Test
    void anAnswerAt7sMeansNoHint() {
        Clocked t = new Clocked();
        List<String> lines = new ArrayList<>();
        for (long ms = 0; ms <= 30_000; ms += 100) {
            t.now = ms;
            t.runAll(PONG); // the phone's Tailscale answers at once
            boolean heard = ms >= 7_000;
            ControlLink.State state = ms < 7_000 ? ControlLink.State.CONNECTING : ms < 7_500 ? ControlLink.State.CONFIGURING
                    : ControlLink.State.READY;
            PhoneSilence.Wait w = t.wait(ms, heard, state);
            assertFalse(t.silence.follow(t.connection, "100.64.0.20", ONLINE, w), "no log line at " + ms);
            lines.add(OsdDataBuilder.linkHint(ONLINE, w, false));
        }
        assertFalse(lines.contains(HINT), lines.toString());
        assertEquals(1, t.pings, "one ping, at 2 s, answered");

        // still CONNECTING after the answer (the phone named a session of this PC's): the PC's own words, not the hint
        assertEquals(OsdDataBuilder.UNANSWERED, OsdDataBuilder.linkHint(ONLINE, waited(9_000, true, 7_000), false));
    }

    @Test
    void aSilentPhoneOnlineIsToldAt8sAndInTheLogOnce() {
        Clocked t = new Clocked();
        int told = 0;
        String first = null;
        for (long ms = 0; ms <= 30_000; ms += 100) {
            t.now = ms;
            t.runAll(PONG);
            PhoneSilence.Wait w = t.wait(ms, false, ControlLink.State.CONNECTING);
            if (t.silence.follow(t.connection, "100.64.0.20", ONLINE, w)) told++;
            String line = OsdDataBuilder.linkHint(ONLINE, w, false);
            if (first == null && HINT.equals(line)) first = ms + "";
        }
        assertEquals("8100", first, "at the first look after 8 s");
        assertEquals(1, told, "the log's line once");
        assertEquals(1, t.pings, "one ping, at 2 s; none once answered");
        assertEquals("100.64.0.20", t.asked.get(0));
        assertEquals("Phone online (Tailscale ping 120 ms), no answer: DRAUPNE Flight closed, or another key? (MENU > LINK)",
                t.silence.logLine(t.connection));

        // PILOT UAV again: a new connection, a new wait
        Object next = new Object();
        t.now = 40_000;
        assertEquals(-1, t.silence.pongAgo(next), "the last connection's pong is not this one's");
        assertFalse(t.silence.follow(next, "100.64.0.20", ONLINE, new PhoneSilence.Wait(false, ControlLink.State.CONNECTING, false, 0, false, -1)));
    }

    /**
     * The timing the pilot asked for: a link over a relay that simply comes up late - its first answer at 9.5 s, READY
     * half a second later - never gets the hint. The way to the phone opens at 7.5 s: the ping at 2 s gets no answer,
     * the one at 6 s its answer at 7.6 s, and the phone's first answer follows within the grace.
     */
    @Test
    void aLinkThatComesUpIn8To10sOverARelayGetsNoHint() {
        Clocked t = new Clocked();
        List<String> hints = new ArrayList<>();
        for (long ms = 0; ms <= 12_000; ms += 100) {
            t.now = ms;
            if (ms == 5_000) t.runAll(NO_PONG); // the ping of 2 s times out (3 s)
            if (ms == 7_600) t.runAll(PONG);    // the one of 6 s is answered once the way is open
            boolean heard = ms >= 9_500;
            ControlLink.State state = ms < 9_500 ? ControlLink.State.CONNECTING : ms < 10_000 ? ControlLink.State.CONFIGURING
                    : ControlLink.State.READY;
            PhoneSilence.Wait w = t.wait(ms, heard, state);
            assertFalse(t.silence.follow(t.connection, "100.64.0.20", ONLINE, w), "no log line at " + ms);
            String h = OsdDataBuilder.linkHint(ONLINE, w, false);
            if (h != null) hints.add(ms + " " + h);
        }
        assertTrue(hints.stream().noneMatch(h -> h.endsWith(HINT)), hints.toString());
        assertEquals(2, t.pings, "at 2 s and again at 6 s; none once answered");
    }

    @Test
    void thePingGoesOnlyWhileThePhoneIsSilentAndNotTwiceAtOnce() {
        assertFalse(PhoneSilence.pingDue(ONLINE, waited(1_999, false, -1), Long.MAX_VALUE), "a phone answers within a second or two");
        assertTrue(PhoneSilence.pingDue(ONLINE, waited(2_000, false, -1), Long.MAX_VALUE));
        assertFalse(PhoneSilence.pingDue(ONLINE, waited(5_000, false, -1), 3_999), "the last one asked just now");
        assertTrue(PhoneSilence.pingDue(ONLINE, waited(6_000, false, -1), 4_000), "again when it got no answer");
        assertFalse(PhoneSilence.pingDue(ONLINE, waited(6_000, false, 1_000), Long.MAX_VALUE), "answered: no more");
        assertFalse(PhoneSilence.pingDue(ONLINE, waited(6_000, true, -1), Long.MAX_VALUE), "the phone answered");
        assertFalse(PhoneSilence.pingDue(ONLINE, null, Long.MAX_VALUE), "watching");
        assertFalse(PhoneSilence.pingDue(ONLINE, new PhoneSilence.Wait(true, ControlLink.State.CONNECTING, false, 6_000, true, -1),
                Long.MAX_VALUE), "a link that was up and is lost");
        assertFalse(PhoneSilence.pingDue(ONLINE, new PhoneSilence.Wait(false, ControlLink.State.CONNECTING, true, 6_000, false, -1),
                Long.MAX_VALUE), "a sleeper waited for");

        // one at a time: a ping still out is not asked again
        Clocked t = new Clocked();
        for (long ms = 0; ms <= 20_000; ms += 100) {
            t.now = ms;
            t.silence.follow(t.connection, "100.64.0.20", ONLINE, t.wait(ms, false, ControlLink.State.CONNECTING));
        }
        assertEquals(1, t.pings, "the first never came back: no second beside it");
    }

    /** The line is long for the OSD: it fits the picture at 1280 x 720, in the OSD's font. */
    @Test
    void theHintFitsThePicture() {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setFont(Theme.headingFont(18));
        int width = g.getFontMetrics().stringWidth(PhoneSilence.HINT);
        g.dispose();
        assertTrue(width < 1280 - 2 * 40, "width " + width);
    }

    /** A PhoneSilence on a made-up clock; its pings wait in a queue until the test answers them. */
    private static final class Clocked {
        final Object connection = new Object();
        long now;
        int pings;
        final List<String> asked = new ArrayList<>();
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private Tailscale.PingResult answer;
        final PhoneSilence silence = new PhoneSilence(ip -> {
            asked.add(ip);
            return answer;
        }, r -> {
            pings++;
            queue.add(r);
        }, () -> now);

        /** The pings asked so far come back now, each with this answer. */
        void runAll(Tailscale.PingResult result) {
            answer = result;
            while (!queue.isEmpty()) queue.poll().run();
        }

        PhoneSilence.Wait wait(long ms, boolean heard, ControlLink.State state) {
            return new PhoneSilence.Wait(false, state, false, ms, heard, silence.pongAgo(connection));
        }
    }
}
