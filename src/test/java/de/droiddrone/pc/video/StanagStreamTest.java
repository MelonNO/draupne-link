package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.junit.jupiter.api.Assertions.*;

class StanagStreamTest {
    /** A sink that shows nothing: only the tap is looked at. */
    private static final class NoScreen implements VideoPipeline.FrameSink {
        public void videoStarted(int width, int height, boolean mirror) {}
        public int[] targetSize() { return new int[]{0, 0}; }
        public int[] beginFrame(int width, int height) { return new int[width * height]; }
        public void presentFrame() {}
    }

    private static Klv.Frame meta(long ms) {
        return new Klv.Frame(ms * 1000, "T", "DRAUPNE", "EO FPV", 45, -5, 2, 10, 59.95, 10.7, 400, 70, 42, 0, 0, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, "NOR");
    }

    private static void feed(VideoPipeline.FrameTap tap, TestStreams.Stream s, long startMs) {
        tap.onConfig(320, 240, false, s.codecConfig());
        long ms = startMs;
        for (TestStreams.Packet p : s.packets()) {
            tap.onFrame(p.data(), p.keyFrame(), ms);
            ms += 33;
        }
    }

    @Test
    void aTransportStreamWithTheVideoAndKlvBesideIt(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 60, 30);
        Path file = dir.resolve("out.ts");
        StanagStream stream = new StanagStream(file.toString(), StanagStreamTest::meta, m -> {});
        feed(stream, s, 1_000_000);
        stream.close();
        assertEquals(60, stream.sentFrames());

        AVFormatContext in = new AVFormatContext(null);
        assertEquals(0, avformat_open_input(in, file.toString(), null, (AVDictionary) null));
        assertTrue(avformat_find_stream_info(in, (AVDictionary) null) >= 0);
        assertEquals(2, in.nb_streams());
        assertEquals("mpegts", in.iformat().name().getString());
        int videoIndex = -1, klvIndex = -1;
        for (int i = 0; i < in.nb_streams(); i++) {
            var par = in.streams(i).codecpar();
            if (par.codec_type() == AVMEDIA_TYPE_VIDEO && par.codec_id() == AV_CODEC_ID_H264) videoIndex = i;
            if (par.codec_type() == AVMEDIA_TYPE_DATA && par.codec_id() == AV_CODEC_ID_SMPTE_KLV) klvIndex = i;
        }
        assertTrue(videoIndex >= 0, "an H.264 stream");
        assertTrue(klvIndex >= 0, "a KLV data stream FFmpeg itself recognises");
        AVPacket pkt = av_packet_alloc();
        int frames = 0;
        List<Map<Integer, byte[]>> klv = new ArrayList<>();
        while (av_read_frame(in, pkt) >= 0) {
            if (pkt.stream_index() == videoIndex) frames++;
            if (pkt.stream_index() == klvIndex) {
                byte[] b = new byte[pkt.size()];
                pkt.data().get(b);
                klv.add(Klv.decode(b));
            }
            av_packet_unref(pkt);
        }
        av_packet_free(pkt);
        avformat_close_input(in);
        assertEquals(60, frames);
        // 60 frames 33 ms apart: a KLV packet with the first frame at least 100 ms after the last one - every 4th
        assertEquals(15, klv.size(), "KLV packets");
        for (Map<Integer, byte[]> t : klv) {
            assertNotNull(t, "every KLV packet intact, its checksum right");
            assertEquals(59.95, Klv.decodeSigned(t.get(Klv.TAG_SENSOR_LAT), 90), 1e-7);
        }
    }

    @Test
    void hostAndPortBecomeAUdpUrlOfWholeTransportPackets() {
        assertEquals("udp://127.0.0.1:15000?pkt_size=1316", StanagStream.url("127.0.0.1:15000"));
        assertEquals("udp://239.1.2.3:5000?pkt_size=1316&ttl=8", StanagStream.url(" 239.1.2.3:5000 "));
        assertEquals("rtp://10.0.0.1:5004", StanagStream.url("rtp://10.0.0.1:5004"));
    }

    @Test
    void itGoesOutOverUdpInTransportStreamPackets() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            socket.setSoTimeout(3000);
            TestStreams.Stream s = TestStreams.h264(320, 240, 30, 30);
            StanagStream stream = new StanagStream("127.0.0.1:" + socket.getLocalPort(), StanagStreamTest::meta, m -> {});
            feed(stream, s, 5_000);
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            socket.receive(p);
            assertEquals(0, p.getLength() % 188, "whole 188-byte packets");
            for (int i = 0; i < p.getLength(); i += 188) assertEquals(0x47, p.getData()[i] & 0xff, "the sync byte");
            stream.close();
        }
    }

    @Test
    void aStreamThatCannotKeepUpLeavesFramesOutAndNeverHoldsTheCaller(@TempDir Path dir) {
        TestStreams.Stream s = TestStreams.h264(320, 240, 200, 50);
        StanagStream stream = new StanagStream(dir.resolve("slow.ts").toString(), ms -> {
            try {
                Thread.sleep(50); // a stuck metadata source: far slower than the frames come
            } catch (InterruptedException ignored) {
            }
            return meta(ms);
        }, m -> {});
        long t0 = System.nanoTime();
        stream.onConfig(320, 240, false, s.codecConfig());
        long ms = 0;
        for (TestStreams.Packet p : s.packets()) {
            stream.onFrame(p.data(), p.keyFrame(), ms);
            ms += 5; // a KLV packet every 20 frames, each 50 ms
        }
        long spentMs = (System.nanoTime() - t0) / 1_000_000;
        stream.close();
        assertTrue(spentMs < 200, "handing over 200 frames took " + spentMs + " ms");
        assertTrue(stream.droppedFrames() > 0, "frames were left out instead of queued");
    }

    @Test
    void theParameterSetsGoInFrontOfAKeyFrameThatHasNone() {
        byte[] config = {0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3}; // SPS, PPS
        byte[] idr = {0, 0, 0, 1, 0x65, 9, 9};
        byte[] out = StanagStream.withConfig(idr, true, false, config);
        assertEquals(config.length + idr.length, out.length);
        assertTrue(StanagStream.hasParameterSets(out, false));
        assertSame(idr, StanagStream.withConfig(idr, false, false, config), "not a key frame: as it is");
        byte[] both = StanagStream.withConfig(out, true, false, config);
        assertSame(out, both, "already has them: as it is");
        byte[] hevcVps = {0, 0, 0, 1, 0x42, 1}; // NAL type 33 = SPS in H.265
        assertTrue(StanagStream.hasParameterSets(hevcVps, true));
    }

    @Test
    void thePipelineHandsTheTapItsConfigAndFrames() {
        TestStreams.Stream s = TestStreams.h264(320, 240, 10, 10);
        VideoPipeline pipeline = new VideoPipeline(new NoScreen(), () -> 40);
        pipeline.onVideoConfig(320, 240, false, false, s.codecConfig());
        int[] configs = {0}, frames = {0};
        pipeline.setFrameTap(new VideoPipeline.FrameTap() {
            public void onConfig(int width, int height, boolean hevc, byte[] codecConfig) {
                configs[0]++;
            }

            public void onFrame(byte[] data, boolean keyFrame, long arrivedMs) {
                frames[0]++;
            }
        });
        assertEquals(1, configs[0], "a tap set mid-stream gets the config in hand");
        short n = 0;
        for (TestStreams.Packet p : s.packets()) {
            byte[] d = p.data();
            pipeline.onVideoFragment(p.keyFrame(), ++n, 0, d.length, d, 0, d.length);
        }
        assertEquals(10, frames[0]);
        pipeline.setFrameTap(null);
        pipeline.close();
    }

    /** The same parameter sets with an access unit delimiter behind them: other bytes, the same picture size and codec. */
    private static byte[] otherBytes(byte[] config) {
        byte[] out = java.util.Arrays.copyOf(config, config.length + 6);
        System.arraycopy(new byte[]{0, 0, 0, 1, 0x09, (byte) 0xf0}, 0, out, config.length, 6);
        return out;
    }

    /**
     * 1.0.65 (one stream per drone, each made on the PC: a page, an alert's pictures, a clip or the video by turns, each
     * from an encoder of its own): new parameter sets at the same size went as "a new stream" - the transport stream
     * ended and began again, its times from zero, and a player stalled at every change of what was shown.
     */
    @Test
    void newParameterSetsAtTheSameSizeGoOnInTheSameStream(@TempDir Path dir) {
        TestStreams.Stream a = TestStreams.h264(320, 240, 20, 10), b = TestStreams.h264(320, 240, 20, 10);
        Path file = dir.resolve("same.ts");
        StanagStream stream = new StanagStream(file.toString(), StanagStreamTest::meta, m -> {});
        long ms = 10_000;
        stream.onConfig(320, 240, false, a.codecConfig());
        for (TestStreams.Packet p : a.packets()) stream.onFrame(p.data(), p.keyFrame(), ms += 33);
        stream.onConfig(320, 240, false, otherBytes(b.codecConfig())); // another encoder's
        stream.onFrame(b.packets().get(1).data(), false, ms += 33); // not a key frame: nothing of the new one before its key frame
        for (TestStreams.Packet p : b.packets()) stream.onFrame(p.data(), p.keyFrame(), ms += 33);
        stream.close();
        assertEquals(40, stream.sentFrames(), "both, and not the picture that came before its key frame");
        TsReader ts = TsReader.read(file, 0, 0, 0);
        assertEquals(40, ts.pictures.size(), "one file with all of it: the stream never began again (a new one replaces the file)");
        assertEquals(40, ts.decoded(), "every picture decodes across the change");
        assertTrue(ts.timesRise());
        assertTrue(ts.pictures.get(39).ptsMs() - ts.pictures.get(0).ptsMs() > 1200, "its times run on over the change");
    }

    @Test
    void aNewPictureSizeStillBeginsAStreamOfItsOwn(@TempDir Path dir) {
        TestStreams.Stream a = TestStreams.h264(320, 240, 10, 10), b = TestStreams.h264(640, 480, 10, 10);
        Path file = dir.resolve("size.ts");
        StanagStream stream = new StanagStream(file.toString(), StanagStreamTest::meta, m -> {});
        long ms = 10_000;
        stream.onConfig(320, 240, false, a.codecConfig());
        for (TestStreams.Packet p : a.packets()) stream.onFrame(p.data(), p.keyFrame(), ms += 33);
        stream.onConfig(640, 480, false, b.codecConfig());
        for (TestStreams.Packet p : b.packets()) stream.onFrame(p.data(), p.keyFrame(), ms += 33);
        stream.close();
        TsReader ts = TsReader.read(file, 0, 0, 0);
        assertEquals(640, ts.width, "the new stream (it took the file's place)");
        assertEquals(10, ts.pictures.size());
    }

    /** 1.0.65: with a stream per drone a receiver that is away was said by every one of them every 5 s. */
    @Test
    void aStreamThatCannotBeSentIsSaidOnceAndAgainWhenItGoes(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 240, 4, 1); // every picture a key frame: each is a new try
        Path folder = dir.resolve("not-there-yet");
        List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        StanagStream stream = new StanagStream("MobileAlpha", folder.resolve("out.ts").toString(), StanagStreamTest::meta, log::add,
                StanagStream.KLV_EVERY_MS);
        stream.retryMs = 50;
        stream.onConfig(320, 240, false, s.codecConfig());
        for (int i = 0; i < 4; i++) {
            stream.onFrame(s.packets().get(i).data(), true, 1000 + i * 100);
            Thread.sleep(120);
        }
        assertEquals(1, log.stream().filter(l -> l.startsWith("STANAG 4609 MobileAlpha: ") && l.endsWith("trying every 5 s.")).count(), log.toString());
        java.nio.file.Files.createDirectories(folder);
        stream.onFrame(s.packets().get(0).data(), true, 2000);
        stream.close();
        assertTrue(log.get(log.size() - 1).startsWith("STANAG 4609 MobileAlpha: sending to"), log.toString());
        assertEquals(2, log.size(), "the failure once, then that it goes: " + log);
    }
}
