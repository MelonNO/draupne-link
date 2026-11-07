package de.droiddrone.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The overwatch clips on the phone (pc.38, the review's M20 and L3) and the update download's guard (H9): pure, run
 * on the PC in a temporary folder.
 */
class ClipStoreTest {
    private static final long T = 1_762_500_000_000L;
    private static final int TOKEN = 0x1234ABCD;

    @TempDir
    Path tmp;

    /** A playable-looking MP4: ftyp, mdat, moov - the top-level boxes run exactly to the end. */
    private static byte[] wholeMp4(int mdatBytes) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeInt(16);
        out.writeBytes("ftypisom");
        out.writeInt(0);
        out.writeInt(8 + mdatBytes);
        out.writeBytes("mdat");
        out.write(new byte[mdatBytes]);
        out.writeInt(16);
        out.writeBytes("moov");
        out.writeLong(0);
        return b.toByteArray();
    }

    /** As MediaMuxer leaves it when the app dies: an mdat whose size was never written, no moov. */
    private static byte[] cutOffMp4() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeInt(16);
        out.writeBytes("ftypisom");
        out.writeInt(0);
        out.writeInt(1_000_000);
        out.writeBytes("mdat");
        out.write(new byte[5000]);
        return b.toByteArray();
    }

    private ClipStore.Clip recorded(ClipStore s, long at, int event, int part, int bytes) throws IOException {
        File f = s.newFile(at, event, part);
        ClipStore.Clip c = s.begin(f, event, part, at, T, true, 1280, 720);
        Files.write(f.toPath(), wholeMp4(bytes));
        s.finish(c, 60_000, ClipStore.sha256(f));
        return c;
    }

    @Test
    void aSegmentRecordedAndDeliveredLeavesNothingBehind() throws IOException {
        ClipStore s = new ClipStore(tmp.toFile());
        ClipStore.Clip c = recorded(s, T, 7, 1, 1000);
        assertTrue(ClipStore.metaOf(c.file).isFile(), "its facts beside it");
        assertSame(c, s.head());
        assertEquals(1, s.waitingCount());
        assertSame(c, s.find(7, T));
        assertNull(s.find(7, T + 1), "another segment's start is another clip");
        assertTrue(s.delivered(c));
        assertFalse(c.file.exists());
        assertFalse(ClipStore.metaOf(c.file).exists());
        assertNull(s.head());
        assertEquals(0, tmp.toFile().listFiles().length);
    }

    @Test
    void aRestartTakesBackFinishedClipsAsTheyWere() throws IOException {
        ClipStore s = new ClipStore(tmp.toFile());
        ClipStore.Clip a = recorded(s, T, 3, 1, 2000);
        ClipStore.Clip b = recorded(s, T + 60_000, 3, 2, 3000);
        s.served(a, 0, a.size(), T); // a went whole, no DONE: one attempt (and b first meanwhile)
        assertSame(a, s.tick(T + ClipStore.DONE_WAIT_MS));
        assertSame(b, s.head());
        List<String> log = new ArrayList<>();
        ClipStore again = new ClipStore(tmp.toFile());
        again.scan(log::add);
        assertEquals(2, again.waitingCount(), String.join("\n", log));
        ClipStore.Clip head = again.head();
        assertEquals(a.file, head.file, "oldest first");
        assertEquals(3, head.event);
        assertEquals(1, head.part);
        assertEquals(T, head.eventStartedAt);
        assertTrue(head.sound);
        assertEquals(1280, head.width);
        assertArrayEquals(a.sha256(), head.sha256());
        assertEquals(a.size(), head.size());
        assertEquals(60_000, head.durationMs());
        assertEquals(1, head.attempts(), "the attempts count on after a restart");
        assertEquals(0, again.find(3, T + 60_000).attempts());
    }

    @Test
    void aSegmentCutOffMidRecordingIsDeletedButAWholeOneIsHashedAndKept() throws IOException {
        ClipStore s = new ClipStore(tmp.toFile());
        File cut = s.newFile(T, 5, 1);
        s.begin(cut, 5, 1, T, T, false, 640, 360);
        Files.write(cut.toPath(), cutOffMp4());
        File whole = s.newFile(T + 60_000, 5, 2);
        s.begin(whole, 5, 2, T + 60_000, T, false, 640, 360);
        Files.write(whole.toPath(), wholeMp4(4000)); // closed, but the app died before it was hashed
        ClipStore again = new ClipStore(tmp.toFile());
        List<String> log = new ArrayList<>();
        again.scan(log::add);
        assertFalse(cut.exists(), "an MP4 without its moov can't be played: deleted");
        assertFalse(ClipStore.metaOf(cut).exists());
        ClipStore.Clip c = again.head();
        assertNotNull(c, String.join("\n", log));
        assertEquals(whole, c.file);
        assertEquals(2, c.part);
        assertArrayEquals(ClipStore.sha256(whole), c.sha256());
        assertEquals(1, again.waitingCount());
    }

    @Test
    void aSegmentStillRecordingIsNeverScannedAway() throws IOException {
        ClipStore s = new ClipStore(tmp.toFile());
        File rec = s.newFile(T, 9, 1);
        s.begin(rec, 9, 1, T, T, false, 640, 360);
        Files.write(rec.toPath(), cutOffMp4()); // being written: no moov yet
        s.scan(line -> { });
        assertTrue(rec.exists(), "the recording in this process is left alone");
        assertNull(s.head());
    }

    @Test
    void aClipFromBeforeTheFactsFilesIsReadFromItsName() throws IOException {
        long at = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).parse("20261105_101530", new java.text.ParsePosition(0)).getTime();
        File old = tmp.resolve("20261105_101530_12.mp4").toFile();
        Files.write(old.toPath(), wholeMp4(500));
        ClipStore s = new ClipStore(tmp.toFile());
        s.scan(line -> { });
        ClipStore.Clip c = s.head();
        assertNotNull(c);
        assertEquals(12, c.event);
        assertEquals(at, c.startedAt);
        assertTrue(ClipStore.metaOf(old).isFile(), "its facts written now");
    }

    @Test
    void oneClipThatNeverFinishesDoesNotBlockTheRestAndIsGivenUp() throws IOException {
        ClipStore s = new ClipStore(tmp.toFile());
        ClipStore.Clip stuck = recorded(s, T, 1, 1, 1000);
        ClipStore.Clip next = recorded(s, T + 60_000, 1, 2, 1000);
        long now = T;
        s.served(stuck, 0, stuck.size(), now);
        assertNull(s.tick(now + ClipStore.DONE_WAIT_MS - 1), "the PC has its time for the DONE");
        s.served(stuck, 512, stuck.size(), now + 15_000); // still asking (filling a gap): not stuck
        assertNull(s.tick(now + ClipStore.DONE_WAIT_MS + 1));
        assertSame(stuck, s.tick(now + 15_000 + ClipStore.DONE_WAIT_MS), "whole, then silence: an attempt");
        assertSame(next, s.head(), "the others first");
        assertTrue(s.delivered(next));
        for (int i = 1; i < ClipStore.MAX_ATTEMPTS; i++) {
            assertSame(stuck, s.head());
            now += 100_000;
            s.served(stuck, 0, stuck.size(), now);
            assertSame(stuck, s.tick(now + ClipStore.DONE_WAIT_MS));
        }
        assertTrue(stuck.givenUp());
        assertNull(s.head(), "given up: no longer offered");
        assertTrue(stuck.file.exists(), "but kept on the phone");
        assertEquals(0, s.waitingCount());
    }

    @Test
    void thePcAskingFromTheStartAfterTheWholeClipCountsAnAttempt() throws IOException {
        ClipStore s = new ClipStore(tmp.toFile());
        ClipStore.Clip c = recorded(s, T, 1, 1, 200_000);
        s.served(c, 0, 65536, T);
        s.served(c, 65536, c.size(), T + 1000);
        s.served(c, 0, 65536, T + 2000); // its SHA-256 differed at the PC: from nothing again
        assertEquals(1, c.attempts());
        assertSame(c, s.head(), "it goes on being fetched");
    }

    @Test
    void theQuotaTakesGivenUpClipsFirstThenTheOldest() throws IOException {
        ClipStore s = new ClipStore(tmp.toFile());
        ClipStore.Clip oldest = recorded(s, T, 1, 1, 10_000);
        ClipStore.Clip givenUp = recorded(s, T + 60_000, 1, 2, 10_000);
        ClipStore.Clip newest = recorded(s, T + 120_000, 1, 3, 10_000);
        s.giveUp(givenUp);
        long each = oldest.file.length();
        // the phone nearly full: room for two clips above the 1 GB it keeps free
        long usable = ClipStore.KEEP_FREE_BYTES - each;
        List<ClipStore.Clip> gone = s.enforceQuota(usable);
        assertEquals(List.of(givenUp), gone);
        assertFalse(givenUp.file.exists());
        gone = s.enforceQuota(usable); // room for one now
        assertEquals(List.of(oldest), gone, "then the oldest waiting");
        assertTrue(newest.file.exists());
        assertTrue(s.enforceQuota(0).isEmpty(), "unknown room: the quota alone");
        assertEquals(ClipStore.QUOTA_BYTES, ClipStore.limit(0, 100L << 30));
        assertEquals(500, ClipStore.limit(1000, ClipStore.KEEP_FREE_BYTES - 500));
    }

    @Test
    void aWholeMp4IsToldFromOneCutOff() throws IOException {
        File f = tmp.resolve("x.mp4").toFile();
        Files.write(f.toPath(), wholeMp4(100));
        assertTrue(ClipStore.isWholeMp4(f));
        Files.write(f.toPath(), cutOffMp4());
        assertFalse(ClipStore.isWholeMp4(f));
        byte[] noMoov = Arrays.copyOf(wholeMp4(100), 16 + 8 + 100);
        Files.write(f.toPath(), noMoov);
        assertFalse(ClipStore.isWholeMp4(f), "boxes to the end, but no movie box");
        Files.write(f.toPath(), new byte[0]);
        assertFalse(ClipStore.isWholeMp4(f));
    }

    // ------------------------------------------------------------------ the wire (pc.38 / 1.0.49)

    @Test
    void aSegmentsInfoCarriesItsPartAndItsEventsStart() {
        byte[] sha = new byte[32];
        sha[0] = 9;
        byte[] p = OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 4, 5_000_000, sha, 60_000, T + 120_000, true, 1920, 1080, 3, T));
        OverwatchProtocol.ClipInfo i = OverwatchProtocol.readClipInfo(p, p.length);
        assertEquals(3, i.part);
        assertEquals(T, i.eventStartedAt);
        assertEquals(T + 120_000, i.startedAt);
        assertEquals(1080, i.height);
        // a phone before pc.38: no tail - part 0, the event's start its own
        byte[] old = Arrays.copyOf(p, p.length - 10);
        OverwatchProtocol.ClipInfo o = OverwatchProtocol.readClipInfo(old, old.length);
        assertEquals(0, o.part);
        assertEquals(T + 120_000, o.eventStartedAt);
        assertEquals(1920, o.width);
    }

    @Test
    void theClipDoneNamesTheSegmentAndAnOldOneStillReads() {
        byte[] done = OverwatchProtocol.clipDone(TOKEN, 4, true, T + 60_000);
        OverwatchProtocol.Command c = OverwatchProtocol.readCommand(done, done.length);
        assertEquals(OverwatchProtocol.CMD_CLIP_DONE, c.cmd);
        assertEquals(4, c.event);
        assertEquals(1, c.offset);
        assertEquals(T + 60_000, c.clipStartedAt);
        byte[] old = OverwatchProtocol.command(TOKEN, 4, OverwatchProtocol.CMD_CLIP_DONE, 1, 0);
        OverwatchProtocol.Command o = OverwatchProtocol.readCommand(old, old.length);
        assertEquals(1, o.offset);
        assertEquals(OverwatchProtocol.NO_CLIP_START, o.clipStartedAt);
    }

    // ------------------------------------------------------------------ H9: the update download's guard

    @Test
    void anUpdateDownloadStopsAtTheFirstChunkAfterArming() throws Exception {
        byte[] apk = new byte[50 * 64 * 1024];
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean disarmed = new AtomicBoolean(true);
        ByteArrayInputStream in = new ByteArrayInputStream(apk) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                if (reads.incrementAndGet() == 3) disarmed.set(false); // armed while the third chunk came
                return super.read(b, off, len);
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GuardedCopy.Stopped e = assertThrows(GuardedCopy.Stopped.class, () -> GuardedCopy.copy(in, out,
                MessageDigest.getInstance("SHA-256"), apk.length, null, disarmed::get, "armed"));
        assertEquals("armed", e.getMessage());
        assertEquals(3, reads.get(), "not one read after the guard said no");
        assertEquals(3 * 64 * 1024, out.size());
    }

    @Test
    void anUpdateDownloadNotStoppedCopiesAllAndCountsInFivePercentSteps() throws Exception {
        byte[] apk = new byte[100 * 64 * 1024];
        apk[12345] = 7;
        List<Integer> percents = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long n = GuardedCopy.copy(new ByteArrayInputStream(apk), out, sha, apk.length, percents::add, () -> true, "x");
        assertEquals(apk.length, n);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(apk), sha.digest());
        assertEquals(20, percents.size(), "5, 10 ... 100: " + percents);
        assertEquals(100, (int) percents.get(percents.size() - 1));
    }
}
