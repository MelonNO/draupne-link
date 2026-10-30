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
