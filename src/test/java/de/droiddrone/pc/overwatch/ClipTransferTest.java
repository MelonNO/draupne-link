package de.droiddrone.pc.overwatch;

import de.droiddrone.common.OverwatchProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.64: the clip's windows on a slow uplink. A window was asked for again a fixed 1.5 s after the ask, whole from
 * the first gap - a phone that needs longer than that for 64 chunks sent every window twice or more. Asked again only
 * after 1.5 s without a chunk, and then only from its first gap to its last.
 */
class ClipTransferTest {
    private static final int CHUNK = OverwatchProtocol.FRAGMENT;

    @TempDir
    Path dir;

    private static byte[] clip(int chunks, int tail) {
        byte[] b = new byte[chunks * CHUNK + tail];
        new Random(chunks).nextBytes(b);
        return b;
    }

    private static void chunk(ClipTransfer t, byte[] clip, int index, long now) throws IOException {
        long off = (long) index * CHUNK;
        int n = (int) Math.min(CHUNK, clip.length - off);
        t.onChunk(off, clip, (int) off, n, now);
    }

    @Test
    void aSlowUplinkIsNotAskedTwiceForTheSameWindow() throws Exception {
        byte[] c = clip(150, 77); // 151 chunks: three windows
        List<long[]> asks = new ArrayList<>();
        int sentByPhone = 0;
        try (ClipTransfer t = new ClipTransfer(dir.resolve("slow.part"), dir.resolve("slow.mp4"), c.length, MessageDigest.getInstance("SHA-256").digest(c))) {
            List<Integer> toSend = new ArrayList<>(); // what the phone still has to send, in order
            long now = 0;
            while (!t.complete()) {
                long[] ask = t.next(now, false); // the hub's tick, every 100 ms
                if (ask != null) {
                    asks.add(ask);
                    for (int i = 0; i < ask[1]; i++) toSend.add((int) (ask[0] / CHUNK) + i);
                }
                for (int k = 0; k < 2 && !toSend.isEmpty(); k++) { // 20 chunks a second: a window takes 3.2 s, not 1.5
                    chunk(t, c, toSend.remove(0), now + 50 * k);
                    sentByPhone++;
                }
                now += 100;
                assertTrue(now < 60_000, "stuck");
            }
            assertEquals(ClipTransfer.Result.OK, t.finish());
        }
        assertEquals(3, asks.size(), "one ask a window: " + asks.size() + " asks");
        assertEquals(151, sentByPhone, "every chunk sent once");
        assertArrayEquals(c, Files.readAllBytes(dir.resolve("slow.mp4")));
    }

    @Test
    void aStalledWindowIsAskedForAgainFromItsFirstGapToItsLast() throws Exception {
        byte[] c = clip(100, 0);
        try (ClipTransfer t = new ClipTransfer(dir.resolve("gap.part"), dir.resolve("gap.mp4"), c.length, MessageDigest.getInstance("SHA-256").digest(c))) {
            long[] first = t.next(0, false);
            assertArrayEquals(new long[]{0, ClipTransfer.WINDOW}, first);
            for (int i = 0; i < ClipTransfer.WINDOW; i++) if (i != 10 && i != 50) chunk(t, c, i, 10 + i * 10L);
            long last = 10 + 63 * 10L; // the last chunk came at 640 ms
            assertEquals(10L * CHUNK, t.received(), "written up to the first gap");
            assertNull(t.next(1600, false), "1.5 s from the ask, but a chunk came 1 s ago: not yet");
            assertNull(t.next(last + ClipTransfer.RETRY_MS - 1, false));
            long[] again = t.next(last + ClipTransfer.RETRY_MS, false);
            assertArrayEquals(new long[]{10L * CHUNK, 41}, again, "from chunk 10 to chunk 50 - not the 54 to the window's end, nor 64 more");
            assertNull(t.next(last + ClipTransfer.RETRY_MS + 100, false), "asked: it comes");
            // only the first gap is filled this time
            chunk(t, c, 10, 2400);
            assertEquals(50L * CHUNK, t.received());
            assertNull(t.next(2500, false));
            long[] third = t.next(2400 + ClipTransfer.RETRY_MS, false);
            assertArrayEquals(new long[]{50L * CHUNK, 1}, third, "the one chunk still missing");
            chunk(t, c, 50, 4000);
            assertEquals(64L * CHUNK, t.received());
            // the window is in: the next at once, a whole one (36 chunks are left)
            assertArrayEquals(new long[]{64L * CHUNK, 36}, t.next(4000, false));
            // its last chunk lost: one chunk asked again
            for (int i = 64; i < 99; i++) chunk(t, c, i, 4100);
            assertArrayEquals(new long[]{99L * CHUNK, 1}, t.next(4100 + ClipTransfer.RETRY_MS, false));
            chunk(t, c, 99, 6000);
            assertTrue(t.complete());
            assertEquals(ClipTransfer.Result.OK, t.finish());
        }
        assertArrayEquals(c, Files.readAllBytes(dir.resolve("gap.mp4")));
    }

    /** Nothing at all comes (the ask was lost): the whole window again, every 1.5 s. */
    @Test
    void aLostAskIsAskedAgainWhole() throws Exception {
        byte[] c = clip(10, 5);
        try (ClipTransfer t = new ClipTransfer(dir.resolve("lost.part"), dir.resolve("lost.mp4"), c.length, MessageDigest.getInstance("SHA-256").digest(c))) {
            assertArrayEquals(new long[]{0, 11}, t.next(0, false));
            assertNull(t.next(ClipTransfer.RETRY_MS - 1, false));
            assertArrayEquals(new long[]{0, 11}, t.next(ClipTransfer.RETRY_MS, false));
            assertNull(t.next(ClipTransfer.RETRY_MS + 1000, false));
            assertArrayEquals(new long[]{0, 11}, t.next(2 * ClipTransfer.RETRY_MS, false));
            // paused meanwhile (a drone here may fly): nothing; then on from where it stood
            for (int i = 0; i < 4; i++) chunk(t, c, i, 3100);
            assertNull(t.next(10_000, true));
            assertArrayEquals(new long[]{4L * CHUNK, 7}, t.next(10_100, false));
        }
    }
}
