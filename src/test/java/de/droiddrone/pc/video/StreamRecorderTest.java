package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamRecorderTest {
    /** A sink that shows nothing: only the recording is looked at. */
    private static final class NoScreen implements VideoPipeline.FrameSink {
        public void videoStarted(int width, int height, boolean mirror) {}
        public int[] targetSize() { return new int[]{0, 0}; }
        public int[] beginFrame(int width, int height) { return new int[width * height]; }
        public void presentFrame() {}
    }

    @Test
    void thePipelineRecordsWhatArrivesOnItsOwnThread(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 20, 10);
        VideoPipeline pipeline = new VideoPipeline(new NoScreen(), () -> 40);
        pipeline.startRecording(dir);
        pipeline.onVideoConfig(320, 240, false, false, s.codecConfig());
        short n = 0;
        for (TestStreams.Packet p : s.packets()) {
            byte[] d = p.data();
            pipeline.onVideoFragment(p.keyFrame(), ++n, 0, d.length, d, 0, d.length); // each frame in one packet
        }
        Path file = fileOf(pipeline);
        assertNotNull(file, "a file was started at the first key frame");
        pipeline.stopRecording();
        pipeline.close();
        AVFormatContext in = new AVFormatContext(null);
        assertEquals(0, avformat_open_input(in, file.toString(), null, (AVDictionary) null));
        AVPacket pkt = av_packet_alloc();
        int frames = 0;
        while (av_read_frame(in, pkt) >= 0) {
            frames++;
            av_packet_unref(pkt);
        }
        av_packet_free(pkt);
        avformat_close_input(in);
        assertEquals(s.packets().size(), frames);
    }

    /**
     * A file whose start fails after it was opened (here the file listener throws; on a PC, a full disk at its first
     * frame) is closed, not left open: before 1.0.53 the recorder was lost with the exception and the MKV stayed open -
     * locked, not even deletable - until the app closed.
     */
    @Test
    void aFileThatFailsAtItsStartIsClosed(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 10, 10);
        VideoPipeline pipeline = new VideoPipeline(new NoScreen(), () -> 40);
        java.util.concurrent.atomic.AtomicReference<Path> started = new java.util.concurrent.atomic.AtomicReference<>();
        pipeline.setRecordingFileListener(f -> {
            started.set(f);
            throw new IllegalStateException("the listener fails");
        });
        pipeline.startRecording(dir);
        pipeline.onVideoConfig(320, 240, false, false, s.codecConfig());
        feed(pipeline, s, 0, 3, 1);
        long end = System.currentTimeMillis() + 3000;
        while (pipeline.isRecording() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertFalse(pipeline.isRecording(), "the recording stopped on the error");
        assertNotNull(started.get(), "a file was opened");
        Files.delete(started.get()); // an open file can't be deleted on Windows
        pipeline.close();
    }

    /**
     * A write that fails in the middle of a flight (a disk full for a moment, a file held by a virus scanner - here the
     * file listener says so at the first file): the recording goes on in a new file from the next key frame. Until
     * 2026-11-20 it ended for the rest of the connection.
     */
    @Test
    void aFailedWriteBeginsANewFileAtTheNextKeyFrame(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 30, 10); // key frames at 0, 10, 20
        VideoPipeline pipeline = new VideoPipeline(new NoScreen(), () -> 40);
        java.util.List<Path> files = new java.util.concurrent.CopyOnWriteArrayList<>();
        pipeline.setRecordingFileListener(f -> {
            files.add(f);
            if (files.size() == 1) throw new java.io.UncheckedIOException(new java.io.IOException("the disk refused"));
        });
        pipeline.startRecording(dir);
        pipeline.onVideoConfig(320, 240, false, false, s.codecConfig());
        feed(pipeline, s, 0, 30, 1);
        long end = System.currentTimeMillis() + 3000;
        while (files.size() < 2 && System.currentTimeMillis() < end) Thread.sleep(10);
        assertTrue(pipeline.isRecording(), "still recording after the failed write");
        assertEquals(2, files.size(), "a second file, begun at the next key frame: " + files);
        pipeline.stopRecording();
        pipeline.close();
        assertEquals(20, framesAndWidth(files.get(1))[0], "the second file has the frames from the next key frame on");
        Files.delete(files.get(0)); // the first was closed (an open file can't be deleted on Windows)
    }

    /** A screen the pictures go to, so frames are presented (each one writes a row of the OSD sidecar). */
    private static final class SmallScreen implements VideoPipeline.FrameSink {
        final java.util.concurrent.atomic.AtomicInteger presented = new java.util.concurrent.atomic.AtomicInteger();
        private final int[] buffer = new int[320 * 240];
        public void videoStarted(int width, int height, boolean mirror) {}
        public int[] targetSize() { return new int[]{320, 240}; }
        public int[] beginFrame(int width, int height) { return buffer; }
        public void presentFrame() { presented.incrementAndGet(); }
    }

    @Test
    void theOsdSidecarHasARowPerPresentedFrameOnTheMkvsClock(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 20, 10);
        SmallScreen screen = new SmallScreen();
        VideoPipeline pipeline = new VideoPipeline(screen, () -> 40);
        pipeline.startRecording(dir);
        pipeline.onVideoConfig(320, 240, false, false, s.codecConfig());
        short n = 0;
        long end = System.currentTimeMillis() + 5000;
        for (TestStreams.Packet p : s.packets()) {
            byte[] d = p.data();
            pipeline.onVideoFragment(p.keyFrame(), ++n, 0, d.length, d, 0, d.length);
            while (pipeline.framesDone() < n && System.currentTimeMillis() < end) Thread.sleep(2); // one at a time: every frame drawn
        }
        Path file = fileOf(pipeline);
        assertNotNull(file);
        pipeline.stopRecording();
        pipeline.close();
        Path csv = file.resolveSibling(file.getFileName().toString().replace(".mkv", ".csv"));
        assertTrue(Files.exists(csv), "next to the MKV: " + csv);
        java.util.List<String> lines = Files.readAllLines(csv);
        assertEquals(StreamRecorder.OSD_HEADER, lines.get(0));
        assertEquals(screen.presented.get(), lines.size() - 1, "a row per presented frame");
        assertTrue(lines.size() >= 10, "most frames were drawn: " + lines.size());
        long last = -1;
        for (String line : lines.subList(1, lines.size())) {
            String[] cols = line.split(",", -1);
            assertEquals(19, cols.length, line);
            long pts = Long.parseLong(cols[0]);
            assertTrue(pts >= last, "pts never go back: " + line);
            last = pts;
            assertEquals("40", cols[1], "the ping");
            assertEquals("", cols[2], "no telemetry: empty, not NaN");
        }
        assertTrue(last < 2000, "the MKV's clock: ms from the first key frame, " + last);
    }

    /** The file being written, once the record thread has opened it (up to 3 s). */
    private static Path fileOf(VideoPipeline pipeline) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (pipeline.recordingFile() == null && System.currentTimeMillis() < end) Thread.sleep(5);
        return pipeline.recordingFile();
    }

    /** The frames in an MKV file and its picture width. */
    private static int[] framesAndWidth(Path file) {
        AVFormatContext in = new AVFormatContext(null);
        assertEquals(0, avformat_open_input(in, file.toString(), null, (AVDictionary) null), "opens: " + file);
        assertTrue(avformat_find_stream_info(in, (AVDictionary) null) >= 0);
        int width = in.streams(0).codecpar().width();
        AVPacket pkt = av_packet_alloc();
        int n = 0;
        while (av_read_frame(in, pkt) >= 0) {
            n++;
            av_packet_unref(pkt);
        }
        av_packet_free(pkt);
        avformat_close_input(in);
        return new int[]{n, width};
    }

    private static java.util.List<Path> mkvFiles(Path dir) throws java.io.IOException {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            // in the order they were started: a second file in the same second is named "-2", which sorts first
            return files.filter(f -> f.toString().endsWith(".mkv"))
                    .sorted(java.util.Comparator.comparing((Path f) -> f.getFileName().toString().substring(0, "flight-yyyy-MM-dd_HH-mm-ss".length()))
                            .thenComparing(f -> f.getFileName().toString().length())
                            .thenComparing(f -> f.getFileName().toString()))
                    .toList();
        }
    }

    private static void feed(VideoPipeline p, TestStreams.Stream s, int from, int to, int firstNumber) throws InterruptedException {
        for (int i = from; i < to; i++) {
            byte[] d = s.packets().get(i).data();
            p.onVideoFragment(s.packets().get(i).keyFrame(), (short) (firstNumber + i - from), 0, d.length, d, 0, d.length);
            Thread.sleep(5);
        }
    }

    /**
     * M6: the phone restarts its stream at another size, its key frame 1 before its config (the usual order). The new
     * stream gets a file of its own from that key frame on; before 1.0.49 the key frame and the frames up to the config
     * went into the old file (of the old size), and the new file waited for the next key frame (here none comes).
     */
    @Test
    void aRestartedStreamGetsItsOwnFileFromItsFirstKeyFrame(@TempDir Path dir) throws Exception {
        TestStreams.Stream a = TestStreams.h264(320, 240, 20, 10);
        TestStreams.Stream b = TestStreams.h264(160, 120, 15, 30); // one key frame, its first
        VideoPipeline pipeline = new VideoPipeline(new NoScreen(), () -> 40);
        pipeline.setKeyFrameSender(() -> { });
        pipeline.setConfigRequester(() -> { });
        pipeline.startRecording(dir);
        pipeline.onVideoConfig(320, 240, false, false, a.codecConfig());
        feed(pipeline, a, 0, 20, 1);
        feed(pipeline, b, 0, 5, 1); // the restart: key frame 1 and four more before the config
        pipeline.onVideoConfig(160, 120, false, false, b.codecConfig());
        feed(pipeline, b, 5, 15, 6);
        Thread.sleep(300);
        pipeline.stopRecording();
        pipeline.close();
        java.util.List<Path> files = mkvFiles(dir);
        assertEquals(2, files.size(), "a file per stream: " + files);
        assertArrayEquals(new int[]{20, 320}, framesAndWidth(files.get(0)), "the first stream, nothing of the second");
        assertArrayEquals(new int[]{15, 160}, framesAndWidth(files.get(1)), "the second, from its key frame 1");
    }

    /** A recording tap whose first frame() holds the record thread up to 3 s: a slow disk. */
    private static final class SlowTap implements VideoPipeline.RecordingTap {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);

        public void fileStarted(Path rawFile, boolean hevc, int width, int height, byte[] codecConfig) {}

        public void frame(byte[] data, boolean keyFrame, long arrivedMs) {
            if (!first.getAndSet(false)) return;
            inside.countDown();
            try {
                release.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        public void fileEnded() {}
    }

    /**
     * M7: the recording stopped and started again while the old one still writes (its thread held past the stop's
     * 2 s wait). Before 1.0.49 the new one cleared the shared queue (the old one's stop with it) and the old thread
     * wrote the new recording's frames into its file: both broke. Now each has its own; the new one waits.
     */
    @Test
    void aRecordingRestartedWhileTheLastStillWritesBreaksNeither(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 20, 10); // key frames 1 and 11
        VideoPipeline pipeline = new VideoPipeline(new NoScreen(), () -> 40);
        pipeline.setKeyFrameSender(() -> { });
        SlowTap tap = new SlowTap();
        pipeline.setRecordingTap(tap);
        pipeline.startRecording(dir);
        pipeline.onVideoConfig(320, 240, false, false, s.codecConfig());
        feed(pipeline, s, 0, 10, 1);
        assertTrue(tap.inside.await(2, java.util.concurrent.TimeUnit.SECONDS));
        pipeline.stopRecording(); // waits 2 s: the thread is still held
        pipeline.startRecording(dir);
        feed(pipeline, s, 10, 20, 11);
        tap.release.countDown();
        Thread.sleep(500);
        pipeline.stopRecording();
        pipeline.close();
        java.util.List<Path> files = mkvFiles(dir);
        assertEquals(2, files.size(), "two files, not one over the other: " + files);
        assertEquals(10, framesAndWidth(files.get(0))[0], "the first recording's frames, all of them, only them");
        assertEquals(10, framesAndWidth(files.get(1))[0], "the second's from its key frame");
    }

    /** The frames in a file as it stands now, not closed (as an app that was killed leaves it): -1 when it does not open. */
    private static int framesAsKilled(Path file, Path copy) throws java.io.IOException {
        Files.copy(file, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        AVFormatContext in = new AVFormatContext(null);
        if (avformat_open_input(in, copy.toString(), null, (AVDictionary) null) != 0) return -1;
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

    /**
     * The app killed, or the laptop's battery gone, in flight: what the file holds then. Three seconds of picture
     * after one key frame - the usual stretch between key frames is 2 to 10 s - written, then the file copied as it
     * stands. Until 2026-11-20 the once-a-second flush left the cluster in hand in memory: none of it was in the file.
     */
    @Test
    void whatIsRecordedIsInTheFileAtTheNextFlushNotWhenItsClusterEnds(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 90, 300);
        Path file = dir.resolve("flight.mkv");
        try (StreamRecorder r = new StreamRecorder(file, false, 320, 240, s.codecConfig())) {
            long t = 1000;
            for (TestStreams.Packet p : s.packets()) r.write(p.data(), p.keyFrame(), t += 33);
            int before = framesAsKilled(file, dir.resolve("killed-before.mkv"));
            r.flush();
            int after = framesAsKilled(file, dir.resolve("killed-after.mkv"));
            assertEquals(s.packets().size(), after, "every frame written is in the file after the flush (before it: " + before + ")");
            assertTrue(before < after, "(without the flush the file had " + before + " of " + after + ": the cluster in hand was in memory)");
            // and the recording goes on into the same file, whole when closed
            for (TestStreams.Packet p : s.packets().subList(1, 31)) r.write(p.data(), false, t += 33);
        }
        assertEquals(s.packets().size() + 30, framesAndWidth(file)[0], "the file closed after a flush plays to its end");
    }

    /** The link lost: no frame comes any more, the recording stays open. What came before is in the file a second later. */
    @Test
    void whenThePictureStopsComingWhatWasRecordedGoesToTheDisk(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 45, 300);
        VideoPipeline pipeline = new VideoPipeline(new NoScreen(), () -> 40);
        pipeline.startRecording(dir);
        pipeline.onVideoConfig(320, 240, false, false, s.codecConfig());
        feed(pipeline, s, 0, s.packets().size(), 1);
        Path file = fileOf(pipeline);
        assertNotNull(file);
        try {
            long end = System.currentTimeMillis() + 4000;
            int inFile = -1;
            while (System.currentTimeMillis() < end && inFile < s.packets().size()) {
                Thread.sleep(200);
                inFile = framesAsKilled(file, dir.resolve("killed.mkv"));
            }
            assertEquals(s.packets().size(), inFile, "all " + s.packets().size() + " frames are in the file within a few seconds of the last one, the recording still open");
        } finally {
            pipeline.stopRecording();
            pipeline.close();
        }
    }

    @Test
    void theStreamIsWrittenAsItCameAndPlaysBack(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 30, 10);
        Path file = dir.resolve("flight.mkv");
        try (StreamRecorder r = new StreamRecorder(file, false, 320, 240, s.codecConfig())) {
            r.write(s.packets().get(1).data(), false, 0); // before the first key frame: skipped
            long t = 1000;
            for (TestStreams.Packet p : s.packets()) r.write(p.data(), p.keyFrame(), t += 33);
            assertEquals(s.packets().size(), r.frames());
        }
        assertTrue(Files.size(file) > 1000);
        // read back: every frame there, H.264, the right size
        AVFormatContext in = new AVFormatContext(null);
        assertEquals(0, avformat_open_input(in, file.toString(), null, (AVDictionary) null));
        assertTrue(avformat_find_stream_info(in, (AVDictionary) null) >= 0);
        assertEquals(AV_CODEC_ID_H264, in.streams(0).codecpar().codec_id());
        assertEquals(320, in.streams(0).codecpar().width());
        AVPacket pkt = av_packet_alloc();
        int n = 0;
        while (av_read_frame(in, pkt) >= 0) {
            n++;
            av_packet_unref(pkt);
        }
        av_packet_free(pkt);
        avformat_close_input(in);
        assertEquals(s.packets().size(), n);
    }
}
