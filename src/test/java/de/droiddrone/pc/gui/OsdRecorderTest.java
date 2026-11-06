package de.droiddrone.pc.gui;

import de.droiddrone.pc.video.TestStreams;
import de.droiddrone.pc.video.VideoPipeline;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.junit.jupiter.api.Assertions.*;

class OsdRecorderTest {
    @Test
    void theCopyIsNamedAfterTheRawFileAndBigEnoughForTheText() {
        assertEquals(Path.of("v", "flight-2026-11-06_10-50-02-osd.mkv"), OsdRecorder.osdFile(Path.of("v", "flight-2026-11-06_10-50-02.mkv")));
        assertArrayEquals(new int[]{960, 720}, OsdRecorder.outputSize(640, 480));
        assertArrayEquals(new int[]{1280, 720}, OsdRecorder.outputSize(1280, 720));
        assertArrayEquals(new int[]{1920, 1080}, OsdRecorder.outputSize(1920, 1080));
        assertArrayEquals(new int[]{426, 720}, OsdRecorder.outputSize(240, 406)); // a phone held upright: even sides
    }

    private static final class NoScreen implements VideoPipeline.FrameSink {
        public void videoStarted(int width, int height, boolean mirror) {}
        public int[] targetSize() { return new int[]{0, 0}; }
        public int[] beginFrame(int width, int height) { return new int[width * height]; }
        public void presentFrame() {}
    }

    @Test
    void aRecordingAlsoWritesACopyWithTheOsdDrawnIn(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 30, 10);
        OsdPreview preview = new OsdPreview();
        CountDownLatch closed = new CountDownLatch(1);
        boolean[] recording = {false};
        OsdRecorder osd = new OsdRecorder(new OsdRecorder.Source() {
            public OsdRenderer.Data osdData() { return preview.data(); }
            public OsdLayout osdLayout() { return OsdLayout.defaults(); }
            public boolean mirror() { return false; }
            public boolean upsideDown() { return false; }
            public double displayMs() { return 5; }
            public void recording(boolean on) { recording[0] = on; }
        }, line -> {
            if (line.contains("closed")) closed.countDown();
        });
        VideoPipeline pipeline = new VideoPipeline(new NoScreen(), () -> 40);
        pipeline.setRecordingTap(osd);
        pipeline.startRecording(dir);
        pipeline.onVideoConfig(320, 240, false, false, s.codecConfig());
        short n = 0;
        for (TestStreams.Packet p : s.packets()) {
            byte[] d = p.data();
            pipeline.onVideoFragment(p.keyFrame(), ++n, 0, d.length, d, 0, d.length);
            Thread.sleep(25); // the phone's pace, about
        }
        Thread.sleep(300);
        Path raw = pipeline.recordingFile();
        assertNotNull(raw);
        assertTrue(recording[0], "the screen is told to keep the OSD's figures");
        pipeline.stopRecording();
        pipeline.close();
        assertTrue(closed.await(20, TimeUnit.SECONDS), "the copy was closed");
        assertFalse(recording[0]);
        Path copy = OsdRecorder.osdFile(raw);
        assertTrue(Files.size(copy) > 0);

        AVFormatContext in = new AVFormatContext(null);
        assertEquals(0, avformat_open_input(in, copy.toString(), null, (AVDictionary) null));
        assertTrue(avformat_find_stream_info(in, (AVDictionary) null) >= 0);
        assertEquals(960, in.streams(0).codecpar().width());
        assertEquals(720, in.streams(0).codecpar().height());
        AVCodecContext dec = avcodec_alloc_context3(avcodec_find_decoder(in.streams(0).codecpar().codec_id()));
        avcodec_parameters_to_context(dec, in.streams(0).codecpar());
        assertEquals(0, avcodec_open2(dec, (org.bytedeco.ffmpeg.avcodec.AVCodec) null, (AVDictionary) null));
        AVPacket pkt = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        int frames = 0, pictures = 0, bright = 0;
        while (av_read_frame(in, pkt) >= 0) {
            frames++;
            avcodec_send_packet(dec, pkt);
            av_packet_unref(pkt);
            while (avcodec_receive_frame(dec, frame) == 0) {
                if (++pictures != 10) continue;
                // the stream is solid red (luma about 81); the OSD's white and green text is far brighter
                int stride = frame.linesize(0);
                for (int y = 0; y < 720; y += 2) {
                    for (int x = 0; x < 960; x += 2) {
                        if ((frame.data(0).get((long) y * stride + x) & 0xff) > 150) bright++;
                    }
                }
            }
        }
        av_frame_free(frame);
        av_packet_free(pkt);
        avcodec_free_context(dec);
        avformat_close_input(in);
        assertTrue(frames >= 25, "pictures in the copy: " + frames); // a few may be left out to keep up
        assertTrue(bright > 500, "the OSD is in the picture: " + bright);
    }
}
