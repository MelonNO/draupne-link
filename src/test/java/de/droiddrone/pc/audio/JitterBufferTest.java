package de.droiddrone.pc.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JitterBufferTest {
    private static final double FRAME = 1024 * 1000.0 / 48_000; // 21.3 ms, an AAC frame at 48 kHz

    @Test
    void playsOnlyOnceTheTargetIsInHand() {
        JitterBuffer<Integer> b = new JitterBuffer<>(200, 450);
        for (int i = 0; i < 9; i++) { // 192 ms
            b.offer(i, FRAME);
            assertNull(b.poll(), "still filling after " + (i + 1) + " frames");
        }
        b.offer(9, FRAME); // 213 ms
        assertEquals(0, b.poll(), "the oldest first");
        assertTrue(b.isPlaying());
        for (int i = 1; i < 10; i++) assertEquals(i, b.poll(), "then in order");
    }

    @Test
    void runningDryFillsToTheTargetAgain() {
        JitterBuffer<Integer> b = new JitterBuffer<>(100, 300);
        for (int i = 0; i < 5; i++) b.offer(i, FRAME);
        for (int i = 0; i < 5; i++) assertEquals(i, b.poll());
        assertNull(b.poll(), "dry");
        assertEquals(1, b.underruns());
        assertFalse(b.isPlaying());
        b.offer(5, FRAME); // one late frame alone doesn't restart it: a gap, then steady sound, not a stutter
        assertNull(b.poll());
        for (int i = 6; i < 10; i++) b.offer(i, FRAME);
        assertEquals(5, b.poll(), "playing again from the frame that waited");
    }

    @Test
    void aBurstPastTheMaximumIsCutBackToTheTarget() {
        JitterBuffer<Integer> b = new JitterBuffer<>(200, 450);
        for (int i = 0; i < 30; i++) b.offer(i, FRAME); // 640 ms at once, after a stall
        assertTrue(b.bufferedMs() <= 450, "never more than the maximum: " + b.bufferedMs());
        assertTrue(b.dropped() > 0);
        int first = b.poll();
        assertTrue(first > 0, "the oldest went");
        assertEquals(29, first + (int) Math.round(b.bufferedMs() / FRAME), "the newest kept: " + first);
    }

    @Test
    void clearStartsOver() {
        JitterBuffer<Integer> b = new JitterBuffer<>(50, 100);
        for (int i = 0; i < 4; i++) b.offer(i, FRAME);
        assertEquals(0, b.poll());
        b.clear();
        assertEquals(0, b.bufferedMs());
        assertNull(b.poll(), "muted and back: fills first");
        assertEquals(0, b.underruns(), "a clear is no underrun");
    }

    @Test
    void aTargetOverTheMaximumIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new JitterBuffer<>(300, 200));
    }
}
