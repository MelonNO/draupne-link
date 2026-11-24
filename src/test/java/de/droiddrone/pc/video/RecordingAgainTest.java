package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.junit.jupiter.api.Assertions.*;

/**
 * I-017 (the pilot, 2026-11-24): "Recording starts again by itself when the disk has room." Until then a full disk
 * stopped the recording for the rest of the connection; VIDEO > RECORD VIDEO off and on started it again.
 * <p>
 * The full disk is played by Windows' lock on each new part's file, taken as the part is named: the file is there and
 * every byte of it is refused, as on the test bench's full disk. A try for room is named only once its first picture
 * is on the disk, so a try that finds none is played by the folder gone (a disk unplugged): it can't be begun. Each
 * try asks the phone for a key frame once, when its wait is over (the one it begins on): that is how the tries are
 * counted here. The clock is the test's: the waits are checked to the tenth of a second without waiting them.
 */
class RecordingAgainTest {
    private static final long S = 1_000_000_000L;

    @Test
    void theWaitsForRoomGrowFromFiveToThirtySeconds() {
        assertEquals(5_000, VideoRecording.roomWaitMs(0));
        assertEquals(10_000, VideoRecording.roomWaitMs(1));
        assertEquals(20_000, VideoRecording.roomWaitMs(2));
        assertEquals(30_000, VideoRecording.roomWaitMs(3));
        assertEquals(30_000, VideoRecording.roomWaitMs(4));
        assertEquals(30_000, VideoRecording.roomWaitMs(1000), "never longer, never a busy loop");
    }

    /**
     * Five parts on the full disk - each refused every byte - stop the recording, said once with that it starts again;
     * each was deleted and taken out of the flight, and no copy with the OSD began on any. Then a new part is tried
     * after 5, 10, 20, 30, 30 s: not a tenth of a second before, and no key frame is asked for meanwhile; a try that
     * finds no room is not said. With room the next try records: RECORDING AGAIN, once; that part is the file being
     * written, gets its copy with the OSD, is named in the flight once its first picture is on the disk, and plays.
     * Nothing else is left in the folder.
     */
    @Test
    void aRecordingStoppedByAFullDiskStartsAgainByItselfWhenThereIsRoom(@TempDir Path tmp) throws Exception {
        Path videos = Files.createDirectory(tmp.resolve("Videos"));
        Rig rig = new Rig();
        try {
            rig.recording.start(videos);
            rig.feedUntil("the stop", () -> rig.said.size() == 1);
            assertEquals(VideoRecording.MAX_WRITE_FAILURES, rig.parts.size(), "five tries before the stop: " + rig.parts);
            assertEquals(rig.parts, rig.empty, "each deleted, and taken out of the flight");
            assertTrue(rig.said.get(0).startsWith("RECORDING STOPPED: ") && rig.said.get(0).endsWith(" - starts again when there is room."), rig.said.toString());
            assertTrue(rig.recording.isRecording(), "waiting for room, not given up");
            assertNull(rig.recording.file(), "no file being written: no REC mark");
            assertTrue(rig.copies.isEmpty(), "no copy with the OSD began on a part that got nothing on the disk: " + rig.copies);
            rig.waitFor("the folder empty", () -> files(videos).isEmpty());
            Files.delete(videos); // the tries find no room

            long last = rig.now.get(); // the stop
            long[] waits = {5, 10, 20, 30, 30};
            for (int i = 0; i < waits.length; i++) {
                long wait = waits[i];
                int asked = rig.keyAsked.get();
                rig.now.set(last + wait * S - S / 10);
                rig.feedAndSettle();
                assertEquals(asked, rig.keyAsked.get(), "no try, no key frame asked for, a tenth of a second before the wait of " + wait + " s is over");
                if (i == waits.length - 1) { // room again, for the last try
                    Files.createDirectory(videos);
                    rig.full.set(false);
                }
                rig.now.set(last + wait * S);
                rig.feedUntil("the try after " + wait + " s", () -> rig.keyAsked.get() == asked + 1);
                rig.feedAndSettle(); // its key frame: the try
                last = rig.now.get();
                if (i < waits.length - 1) assertEquals(1, rig.said.size(), "a try that found no room is not said: " + rig.said);
            }
            rig.waitFor("RECORDING AGAIN", () -> rig.said.size() == 2);
            assertEquals("RECORDING AGAIN.", rig.said.get(1));
            assertEquals(VideoRecording.MAX_WRITE_FAILURES + 1, rig.parts.size(), "no try that failed is a part of the flight: " + rig.parts);
            Path back = rig.parts.get(rig.parts.size() - 1);
            assertTrue(rig.sizeWhenNamed.get(back) > 0, "named once its first picture is on the disk: " + rig.sizeWhenNamed);
            assertEquals(back, rig.recording.file(), "the part is the file being written: the REC mark is back");
            assertEquals(List.of(back), rig.copies, "its copy with the OSD begins on it");
            rig.feed(20);
            rig.recording.stop();
            assertFalse(rig.recording.isRecording());

            assertEquals(List.of(back), files(videos), "nothing else is left in the folder");
            assertTrue(frames(back) >= 20, "the part after the room came plays: " + frames(back) + " frames");
            assertEquals(2, rig.said.size(), "said: the stop once, the start once - " + rig.said);
        } finally {
            rig.close();
        }
    }

    /**
     * RECORD VIDEO off while the recording waits for room: no more tries. On again, it records at once - the pilot's
     * start, not said as RECORDING AGAIN.
     */
    @Test
    void recordVideoOffWhileWaitingForRoomEndsTheTries(@TempDir Path tmp) throws Exception {
        Path videos = Files.createDirectory(tmp.resolve("Videos"));
        Rig rig = new Rig();
        try {
            rig.recording.start(videos);
            rig.feedUntil("the stop", () -> rig.said.size() == 1);
            assertTrue(rig.recording.isRecording(), "waiting for room");
            rig.recording.stop();
            assertFalse(rig.recording.isRecording(), "RECORD VIDEO off: the waiting ends");
            rig.now.addAndGet(120 * S);
            int asked = rig.keyAsked.get();
            rig.feedAndSettle();
            assertEquals(asked, rig.keyAsked.get(), "no try after RECORD VIDEO off");

            rig.full.set(false);
            rig.recording.start(videos);
            rig.feedUntil("the pilot's new recording", () -> rig.recording.file() != null);
            rig.recording.stop();
            assertEquals(1, rig.said.size(), "a start by the pilot is not RECORDING AGAIN: " + rig.said);
            List<Path> left = files(videos);
            assertEquals(1, left.size(), left.toString());
            assertTrue(Files.size(left.get(0)) > 0, "no file of no byte: " + left);
        } finally {
            rig.close();
        }
    }

    /**
     * A disk that came back and was full again within a minute: stopped again (said again: the pilot was told it
     * records), and tried with the longer wait it had reached, not from 5 s again - a disk that comes and goes is not
     * said every few seconds.
     */
    @Test
    void aDiskFullAgainWithinAMinuteIsTriedWithTheLongerWaits(@TempDir Path tmp) throws Exception {
        Path videos = Files.createDirectory(tmp.resolve("Videos"));
        Rig rig = new Rig();
        try {
            rig.recording.start(videos);
            rig.feedUntil("the stop", () -> rig.said.size() == 1);
            rig.waitFor("the folder empty", () -> files(videos).isEmpty());
            Files.delete(videos);
            long t = rig.now.get();
            for (long wait : new long[]{5, 10}) { // two tries without room: the next wait is 20 s
                int asked = rig.keyAsked.get();
                rig.now.set(t += wait * S);
                rig.feedUntil("the try after " + wait + " s", () -> rig.keyAsked.get() == asked + 1);
                rig.feedAndSettle();
            }
            Files.createDirectory(videos);
            rig.full.set(false);
            rig.now.set(t += 20 * S);
            rig.feedUntil("RECORDING AGAIN", () -> rig.said.size() == 2);
            rig.full.set(true);
            rig.lock(rig.recording.file()); // full again half a minute later: the part in hand fails at its next flush
            rig.now.set(t += 30 * S);
            rig.feedUntil("the second stop", () -> rig.said.size() == 3);
            assertTrue(rig.said.get(2).startsWith("RECORDING STOPPED: "), rig.said.toString());
            long stoppedAt = rig.now.get();
            int asked = rig.keyAsked.get();
            rig.now.set(stoppedAt + 20 * S - S / 10);
            rig.feedAndSettle();
            assertEquals(asked, rig.keyAsked.get(), "not tried from 5 s again");
            rig.now.set(stoppedAt + 20 * S);
            rig.feedUntil("the try after 20 s", () -> rig.keyAsked.get() == asked + 1);
            rig.recording.stop();
        } finally {
            rig.close();
        }
    }

    // ------------------------------------------------------------------ the rig

    /** A recording on the test's clock; each part named while the disk is "full" is locked. */
    private static final class Rig implements AutoCloseable {
        final AtomicLong now = new AtomicLong(1000 * S);
        final AtomicBoolean full = new AtomicBoolean(true);
        final AtomicInteger keyAsked = new AtomicInteger();
        final List<Path> parts = new CopyOnWriteArrayList<>(), empty = new CopyOnWriteArrayList<>(), copies = new CopyOnWriteArrayList<>();
        final Map<Path, Long> sizeWhenNamed = new ConcurrentHashMap<>();
        final List<String> said = new CopyOnWriteArrayList<>();
        final Map<Path, FileChannel> locks = new ConcurrentHashMap<>();
        final TestStreams.Stream stream = TestStreams.h264(320, 240, 20, 2); // a key frame every other frame
        final VideoRecording recording;
        private int next;
        private long arrivedMs = 10_000;

        Rig() {
            Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "a lock that stops a write is Windows' way");
            VideoPipeline.Config config = new VideoPipeline.Config(320, 240, false, false, stream.codecConfig());
            recording = new VideoRecording(() -> config, keyAsked::incrementAndGet, () -> null, now::get);
            recording.setFileListener(f -> {
                parts.add(f);
                try {
                    sizeWhenNamed.put(f, Files.size(f));
                } catch (IOException e) {
                    sizeWhenNamed.put(f, -1L);
                }
                if (full.get()) lock(f); // the disk has no room: not one byte of this part reaches it
            });
            recording.setEmptyListener(f -> {
                empty.add(f);
                release(locks.remove(f));
            });
            recording.setProblemListener(said::add);
            recording.setTap(new VideoPipeline.RecordingTap() {
                public void fileStarted(Path rawFile, boolean hevc, int width, int height, byte[] codecConfig) {
                    copies.add(rawFile);
                }

                public void frame(byte[] data, boolean keyFrame, long arrivedMs) {}

                public void fileEnded() {}
            });
        }

        /** n frames of the stream, at the phone's pace, its frames over and over (a key frame every other one). */
        void feed(int n) throws InterruptedException {
            for (int i = 0; i < n; i++) {
                TestStreams.Packet p = stream.packets().get(next++ % stream.packets().size());
                recording.frame(new FrameAssembler.Frame(p.data(), p.keyFrame(), (short) next, false, false), arrivedMs += 33);
                Thread.sleep(2);
            }
        }

        /** The next frame fed is not a key frame: a part waits for one and asks the phone for it. */
        private void skipToNonKey() {
            if (stream.packets().get(next % stream.packets().size()).keyFrame()) next++;
        }

        void feedUntil(String what, BooleanSupplier done) throws InterruptedException {
            skipToNonKey();
            long end = System.currentTimeMillis() + 5000;
            while (!done.getAsBoolean() && System.currentTimeMillis() < end) feed(4);
            assertTrue(done.getAsBoolean(), "within 5 s: " + what + " (parts " + parts + ", said " + said + ", key frames asked " + keyAsked + ")");
        }

        /** Frames, a non-key one first, and time for the record thread to take them. */
        void feedAndSettle() throws InterruptedException {
            skipToNonKey();
            feed(12);
            Thread.sleep(300);
        }

        void waitFor(String what, BooleanSupplier done) throws InterruptedException {
            long end = System.currentTimeMillis() + 5000;
            while (!done.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
            assertTrue(done.getAsBoolean(), "within 5 s: " + what + " (parts " + parts + ", said " + said + ")");
        }

        /** Every byte written into f from now on is refused. */
        void lock(Path f) {
            try {
                FileChannel c = FileChannel.open(f, StandardOpenOption.WRITE);
                locks.put(f, c);
                c.lock(0, Long.MAX_VALUE, false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static void release(FileChannel c) {
            if (c == null) return;
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            recording.stop();
            for (FileChannel c : locks.values()) release(c);
            locks.clear();
        }
    }

    private static List<Path> files(Path dir) {
        try (Stream<Path> all = Files.list(dir)) {
            return all.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The frames FFmpeg reads from a recording; -1 when it does not open. */
    private static int frames(Path file) {
        AVFormatContext in = new AVFormatContext(null);
        if (avformat_open_input(in, file.toString(), null, (AVDictionary) null) != 0) return -1;
        AVPacket pkt = av_packet_alloc();
        int n = 0;
        while (av_read_frame(in, pkt) >= 0) {
            n++;
            av_packet_unref(pkt);
        }
        av_packet_free(pkt);
        avformat_close_input(in);
        return n;
    }
}
