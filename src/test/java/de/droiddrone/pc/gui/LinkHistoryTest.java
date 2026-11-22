package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** I-011: the ring behind the OSD's LINK HISTORY - 30 s of the link log's tick, its marks, and the ring going round. */
class LinkHistoryTest {
    private static final long T0 = 1_000_000;

    /** A link log tick every 100 ms: the strip shows the samples of the last 30 s, the older ones are left out. */
    @Test
    void theWindowIsThirtySeconds() {
        LinkHistory h = new LinkHistory();
        for (int i = 0; i < 400; i++) h.add(T0 + i * 100L, 40 + i % 7, 38, 0, 0, false);
        long now = T0 + 399 * 100L;
        int w = h.written();
        assertEquals(301, h.within(w, now), "ages 0 to 30 s in steps of 100 ms");
        assertEquals(now, h.at(w, 0), "k = 0 is the newest");
        assertEquals(now - LinkHistory.WINDOW_MS, h.at(w, 300), "the oldest shown is 30 s old");
        assertEquals(0, h.within(w, now + LinkHistory.WINDOW_MS + 1), "nothing new for over 30 s: an empty strip");
        assertEquals(1, h.within(w, now + LinkHistory.WINDOW_MS), "the newest, 30 s old");
    }

    /** More samples than the ring holds: the newest are kept, in order, and the slot written next is never read. */
    @Test
    void theRingGoesRound() {
        LinkHistory h = new LinkHistory();
        int n = 3 * LinkHistory.SIZE + 17;
        for (int i = 0; i < n; i++) h.add(T0 + i * 100L, i, 38, 0, 0, false);
        int w = h.written();
        assertEquals(n, w);
        assertEquals(LinkHistory.SIZE - 1, LinkHistory.kept(w));
        for (int k = 0; k < LinkHistory.kept(w); k++) {
            assertEquals(n - 1 - k, h.ping(w, k), "the " + k + ". newest");
            assertEquals(T0 + (n - 1 - k) * 100L, h.at(w, k));
        }
        assertEquals(3, LinkHistory.kept(3), "fewer than it holds: all of them");
        // a paint that read the count before the next sample sees the same samples after it
        h.add(T0 + n * 100L, 9999, 38, 0, 0, false);
        for (int k = 0; k < LinkHistory.kept(w); k++) assertEquals(n - 1 - k, h.ping(w, k), "read by the count it had");
    }

    /** The marks: from the video's counts as the link log reads them, and the link down. */
    @Test
    void lostAndDamagedFramesAndALostLinkAreMarked() {
        LinkHistory h = new LinkHistory();
        h.add(T0, 40, 38, 5, 2, false); // the first sample: counts from before say nothing
        h.add(T0 + 100, 41, 38, 5, 2, false);
        h.add(T0 + 200, 42, 38, 6, 2, false); // a frame damaged
        h.add(T0 + 300, 43, 38, 7, 3, false); // damaged and lost: lost
        h.add(T0 + 400, 44, 38, 7, 4, false);
        h.add(T0 + 500, 45, 38, 0, 0, false); // a new video pipeline counts from 0: no mark
        h.add(T0 + 600, 46, 38, -1, -1, false); // no video
        h.add(T0 + 700, 47, 38, 0, 1, false); // video again: its first counts say nothing
        h.add(T0 + 800, 120, 38, 0, 1, true); // the link down
        int w = h.written();
        byte[] want = {LinkHistory.NONE, LinkHistory.NONE, LinkHistory.DAMAGED, LinkHistory.LOST, LinkHistory.LOST,
                LinkHistory.NONE, LinkHistory.NONE, LinkHistory.NONE, LinkHistory.DOWN};
        for (int i = 0; i < want.length; i++) assertEquals(want[i], h.mark(w, want.length - 1 - i), "sample " + i);
        assertEquals(-1, h.ping(w, 0), "the link down: no ping in the line");
        assertEquals(47, h.ping(w, 1));
        assertEquals(38, h.baseline(w, 1));
    }

    @Test
    void aNewConnectionStartsEmpty() {
        LinkHistory h = new LinkHistory();
        for (int i = 0; i < 50; i++) h.add(T0 + i * 100L, 40, 38, i, 0, false);
        h.clear();
        assertEquals(0, h.written());
        assertEquals(0, h.within(h.written(), T0 + 5000));
        h.add(T0 + 6000, 40, 38, 60, 0, false);
        assertEquals(LinkHistory.NONE, h.mark(h.written(), 0), "the counts of the connection before say nothing");
    }
}
