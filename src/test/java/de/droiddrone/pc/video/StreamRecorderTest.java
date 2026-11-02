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
        Thread.sleep(300);
        Path file = pipeline.recordingFile();
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
        for (TestStreams.Packet p : s.packets()) {
            byte[] d = p.data();
            pipeline.onVideoFragment(p.keyFrame(), ++n, 0, d.length, d, 0, d.length);
            Thread.sleep(15); // paced, so every frame is drawn
        }
        Thread.sleep(300);
        Path file = pipeline.recordingFile();
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
