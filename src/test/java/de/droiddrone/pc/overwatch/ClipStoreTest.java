package de.droiddrone.pc.overwatch;

import de.droiddrone.common.OverwatchProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The clip's way to the PC (chunks, the SHA check, the pause while armed, going on after a stop) and the clip list. */
class ClipStoreTest {
    @TempDir
    Path dir;

    private static byte[] clip(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static byte[] sha(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    /** The phone's answer to one request: every chunk asked for, minus the ones lost. */
    private static void serve(ClipTransfer t, byte[] clip, long[] ask, java.util.function.IntPredicate lost) throws IOException {
        for (int i = 0; i < ask[1]; i++) {
            long off = ask[0] + (long) i * OverwatchProtocol.FRAGMENT;
            int n = (int) Math.min(OverwatchProtocol.FRAGMENT, clip.length - off);
            if (lost.test((int) (off / OverwatchProtocol.FRAGMENT))) continue;
            byte[] p = OverwatchProtocol.clipChunk(1, 1, off, clip, (int) off, n);
            t.onChunk(OverwatchProtocol.chunkOffset(p), p, OverwatchProtocol.CHUNK_DATA_AT, p.length - OverwatchProtocol.CHUNK_DATA_AT);
        }
    }

    @Test
    void aClipComesInWindowsAndChecks() throws Exception {
        byte[] c = clip(OverwatchProtocol.FRAGMENT * 150 + 333, 1);
        Path done = dir.resolve("a.mp4");
        try (ClipTransfer t = new ClipTransfer(dir.resolve("a.mp4.part"), done, c.length, sha(c))) {
            long now = 0;
            int asks = 0;
            long[] ask;
            while ((ask = t.next(now, false)) != null) {
                asks++;
                assertTrue(ask[1] <= ClipTransfer.WINDOW);
                serve(t, c, ask, i -> false);
                now += 10;
            }
            assertTrue(t.complete());
            assertEquals(3, asks, "151 chunks in windows of 64");
            assertEquals(ClipTransfer.Result.OK, t.finish());
        }
        assertArrayEquals(c, Files.readAllBytes(done));
        assertFalse(Files.exists(dir.resolve("a.mp4.part")));
    }

    @Test
    void nothingIsAskedWhileADroneHereIsArmedAndItGoesOnAfter() throws Exception {
        byte[] c = clip(OverwatchProtocol.FRAGMENT * 100, 2);
        try (ClipTransfer t = new ClipTransfer(dir.resolve("b.part"), dir.resolve("b.mp4"), c.length, sha(c))) {
            serve(t, c, t.next(0, false), i -> false);
            long have = t.received();
            assertEquals((long) ClipTransfer.WINDOW * OverwatchProtocol.FRAGMENT, have);
            for (long now = 10; now < 60_000; now += 100) assertNull(t.next(now, true), "armed: not one request");
            long[] ask = t.next(60_000, false);
            assertEquals(have, ask[0], "on from where it stood");
            serve(t, c, ask, i -> false);
            assertTrue(t.complete());
            assertEquals(ClipTransfer.Result.OK, t.finish());
        }
    }

    @Test
    void aLostChunkIsAskedForAgainAndOnlyTheGaplessPartIsWritten() throws Exception {
        byte[] c = clip(OverwatchProtocol.FRAGMENT * 40, 3);
        try (ClipTransfer t = new ClipTransfer(dir.resolve("c.part"), dir.resolve("c.mp4"), c.length, sha(c))) {
            serve(t, c, t.next(0, false), i -> i == 10);
            assertEquals(10L * OverwatchProtocol.FRAGMENT, t.received(), "up to the gap");
            assertEquals(10L * OverwatchProtocol.FRAGMENT, Files.size(dir.resolve("c.part")));
            assertNull(t.next(ClipTransfer.RETRY_MS - 1, false), "the window still coming");
            long[] again = t.next(ClipTransfer.RETRY_MS, false);
            assertEquals(10L * OverwatchProtocol.FRAGMENT, again[0], "asked again from the gap");
            serve(t, c, again, i -> false);
            assertTrue(t.complete());
            assertEquals(ClipTransfer.Result.OK, t.finish());
        }
        assertArrayEquals(c, Files.readAllBytes(dir.resolve("c.mp4")));
    }

    @Test
    void aStoppedTransferGoesOnFromItsPartFile() throws Exception {
        byte[] c = clip(OverwatchProtocol.FRAGMENT * 90 + 5, 4);
        Path part = dir.resolve("d.part"), done = dir.resolve("d.mp4");
        try (ClipTransfer t = new ClipTransfer(part, done, c.length, sha(c))) {
            serve(t, c, t.next(0, false), i -> false);
        }
        Files.write(part, new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND); // a torn write at the end
        try (ClipTransfer t = new ClipTransfer(part, done, c.length, sha(c))) {
            assertEquals(64L * OverwatchProtocol.FRAGMENT, t.received(), "cut back to whole chunks");
            long[] ask = t.next(0, false);
            assertEquals(64L * OverwatchProtocol.FRAGMENT, ask[0]);
            serve(t, c, ask, i -> false);
            assertEquals(ClipTransfer.Result.OK, t.finish());
        }
        assertArrayEquals(c, Files.readAllBytes(done));
    }

    @Test
    void aClipThatDoesNotCheckStartsOver() throws Exception {
        byte[] c = clip(OverwatchProtocol.FRAGMENT * 3, 5);
        byte[] wrong = c.clone();
        wrong[100] ^= 1;
        try (ClipTransfer t = new ClipTransfer(dir.resolve("e.part"), dir.resolve("e.mp4"), c.length, sha(c))) {
            serve(t, wrong, t.next(0, false), i -> false);
            assertTrue(t.complete());
            assertEquals(ClipTransfer.Result.MISMATCH, t.finish());
            assertEquals(0, t.received());
            assertEquals(1, t.mismatches());
            assertFalse(Files.exists(dir.resolve("e.mp4")));
            serve(t, c, t.next(0, false), i -> false);
            assertEquals(ClipTransfer.Result.OK, t.finish());
        }
    }

    @Test
    void chunksOfTheWrongLengthOrPlaceAreDropped() throws Exception {
        byte[] c = clip(OverwatchProtocol.FRAGMENT * 2, 6);
        try (ClipTransfer t = new ClipTransfer(dir.resolve("f.part"), dir.resolve("f.mp4"), c.length, sha(c))) {
            t.onChunk(0, c, 0, 10);
            t.onChunk(7, c, 0, OverwatchProtocol.FRAGMENT);
            t.onChunk((long) OverwatchProtocol.FRAGMENT * ClipTransfer.WINDOW, c, 0, OverwatchProtocol.FRAGMENT);
            assertEquals(0, t.received());
        }
    }

    // ------------------------------------------------------------------ the list

    private static Properties meta(long startedAt) {
        Properties m = new Properties();
        m.setProperty("startedAt", Long.toString(startedAt));
        m.setProperty("lat", "59.9");
        m.setProperty("lon", "10.7");
        m.setProperty("clipDurationMs", "42000");
        return m;
    }

    @Test
    void removeTakesAClipOffTheListAndLeavesItsFiles() throws Exception {
        ClipIndex index = new ClipIndex(dir.resolve("Overwatch"));
        String a = index.newId("MobileAlpha", 1_762_500_000_000L);
        index.save("MobileAlpha", a, meta(1_762_500_000_000L));
        String b = index.newId("MobileAlpha", 1_762_500_000_000L);
        assertNotEquals(a, b, "the same second twice: two ids");
        index.save("MobileAlpha", b, meta(1_762_500_000_000L));
        String c = index.newId("MobileAlpha", 1_762_600_000_000L);
        index.save("MobileAlpha", c, meta(1_762_600_000_000L));
        Path video = index.folder("MobileAlpha").resolve(c + ".mp4");
        Files.write(video, new byte[5000]);

        List<ClipIndex.Clip> clips = index.clips("MobileAlpha");
        assertEquals(3, clips.size());
        assertEquals(c, clips.get(0).id(), "newest first");
        assertEquals(video, clips.get(0).video());
        assertEquals(42_000, clips.get(0).durationMs());
        assertTrue(clips.get(0).hasPosition());
        long bytes = index.folderBytes("MobileAlpha");
        assertTrue(bytes >= 5000);

        index.hide("MobileAlpha", c);
        index.hide("MobileAlpha", c); // twice: no harm
        List<ClipIndex.Clip> after = index.clips("MobileAlpha");
        assertEquals(2, after.size());
        assertTrue(after.stream().noneMatch(x -> x.id().equals(c)));
        assertTrue(Files.exists(video), "REMOVE never deletes the file");
        assertTrue(Files.exists(index.folder("MobileAlpha").resolve(c + ClipIndex.META)));
        assertTrue(index.folderBytes("MobileAlpha") >= bytes, "the folder's size still counts it: it is on the disk");
        assertEquals(List.of("MobileAlpha"), index.drones());
    }

    @Test
    void theFullClipIsPlayedWhenHereElseThePreviewAndTheThumbnailIsTheLastPictureBefore() throws Exception {
        ClipIndex index = new ClipIndex(dir.resolve("Overwatch"));
        String id = index.newId("Mobile/Bravo:1", 1_762_500_000_000L);
        index.save("Mobile/Bravo:1", id, meta(1_762_500_000_000L));
        Path folder = index.folder("Mobile/Bravo:1");
        assertEquals("Mobile_Bravo_1", folder.getFileName().toString(), "no path signs in a folder name");
        Files.write(folder.resolve(id + "-preview.mkv"), new byte[10]);
        Files.write(folder.resolve(id + "-pre-0000000002000.jpg"), new byte[1]);
        Files.write(folder.resolve(id + "-pre-0000000001000.jpg"), new byte[1]);
        ClipIndex.Clip clip = index.clip("Mobile/Bravo:1", id);
        assertEquals(folder.resolve(id + "-preview.mkv"), clip.video());
        assertEquals(2, clip.preRoll().size());
        assertTrue(clip.preRoll().get(0).toString().endsWith("0001000.jpg"), "oldest first");
        assertEquals(clip.preRoll().get(1), clip.thumbnail());
        Files.write(folder.resolve(id + ".mp4"), new byte[10]);
        assertEquals(folder.resolve(id + ".mp4"), index.clip("Mobile/Bravo:1", id).video());
    }

    @Test
    void sizesReadAsPeopleWriteThem() {
        assertEquals("512 B", ClipIndex.sizeText(512));
        assertEquals("2 KB", ClipIndex.sizeText(2048));
        assertEquals("1.5 MB", ClipIndex.sizeText(1_572_864));
        assertEquals("2.00 GB", ClipIndex.sizeText(2L * 1024 * 1024 * 1024));
    }
}
