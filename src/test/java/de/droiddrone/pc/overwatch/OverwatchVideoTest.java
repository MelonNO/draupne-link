package de.droiddrone.pc.overwatch;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.video.StreamRecorder;
import de.droiddrone.pc.video.TestStreams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The overwatch preview as the phone sends it (H.264 cut into fragments): decoded for the card, recorded, played back. */
class OverwatchVideoTest {
    private static final int TOKEN = 77;

    @Test
    void thePreviewIsShownRecordedAndPlaysBack(@TempDir Path dir) throws Exception {
        TestStreams.Stream s = TestStreams.h264(320, 180, 40, 10);
        OverwatchHub hub = new OverwatchHub(new ClipIndex(dir), p -> { });
        hub.watch("MobileAlpha", TOKEN, new CameraFootprint.Pose(59.9, 10.7, 100, 90, 0, 0, 0, 70, 42));
        InetAddress lo = InetAddress.getLoopbackAddress();
        hub.nowOverride = 1000;
        hub.handleNow(OverwatchProtocol.alert(new OverwatchProtocol.Alert(TOKEN, 1, 0, 0, 1_762_500_000_000L, 0.5f, 0.5f, 0.1f, 0.1f,
                0, 70, 0, 0, 0xffff, 255, "MobileAlpha")), lo, 5000);
        int unit = 0;
        for (byte[] p : OverwatchProtocol.fragments(TOKEN, 1, unit++, OverwatchProtocol.UNIT_CONFIG, s.codecConfig())) hub.handleNow(p, lo, 5000);
        long t = 1000;
        for (TestStreams.Packet f : s.packets()) {
            hub.nowOverride = t += 66;
            int type = f.keyFrame() ? OverwatchProtocol.UNIT_KEY_FRAME : OverwatchProtocol.UNIT_FRAME;
            for (byte[] p : OverwatchProtocol.fragments(TOKEN, 1, unit++, type, f.data())) hub.handleNow(p, lo, 5000);
        }
        OverwatchHub.View v = hub.views().get(0);
        assertNotNull(v.picture(), "the card's live picture");
        assertEquals(320, v.picture().getWidth());
        assertTrue(v.live());
        hub.handleNow(OverwatchProtocol.clipEnded(TOKEN, 1, 1_762_500_030_000L, false), lo, 5000); // closes the recording
        ClipIndex.Clip clip = hub.index().clips("MobileAlpha").get(0);
        Path mkv = clip.preview();
        assertNotNull(mkv, "the preview recorded as the event's clip");
        assertTrue(Files.size(mkv) > 1000);
        assertEquals("none", clip.meta().getProperty("clip"), "the phone said no full clip follows");
        assertTrue(clip.durationMs() > 2000, "its length from the preview: " + clip.durationMs());
        assertEquals(mkv, clip.video());
        hub.close();

        try (ClipPlayer player = new ClipPlayer(mkv)) {
            assertEquals(320, player.width());
            int[] px = new int[160 * 90];
            int frames = 0;
            long last = -1;
            long at;
            while ((at = player.next(px, 160, 90)) >= 0) {
                assertTrue(at >= last, "in order");
                last = at;
                frames++;
            }
            assertEquals(s.packets().size(), frames);
            assertTrue(last > 2000, "the frames' times kept: " + last);
            player.seek(1500);
            long after = player.next(px, 160, 90);
            assertTrue(after >= 1400 && after <= 1700, "seek lands near 1.5 s: " + after);
        }
    }

    @Test
    void anMp4WithAvccFramesPlaysToo(@TempDir Path dir) throws Exception {
        // the recorder writes Annex B into MKV; the phone's MediaMuxer writes length-prefixed frames into MP4 - both must play
        TestStreams.Stream s = TestStreams.h264(320, 240, 20, 10);
        Path mkv = dir.resolve("a.mkv");
        try (StreamRecorder r = new StreamRecorder(mkv, false, 320, 240, s.codecConfig())) {
            long t = 0;
            for (TestStreams.Packet p : s.packets()) r.write(p.data(), p.keyFrame(), t += 40);
        }
        Path mp4 = dir.resolve("a.mp4");
        remux(mkv, mp4);
        try (ClipPlayer player = new ClipPlayer(mp4)) {
            int[] px = new int[320 * 240];
            int n = 0;
            while (player.next(px, 320, 240) >= 0) n++;
            assertEquals(20, n);
            assertTrue(player.durationMs() > 500);
        }
    }

    /** MKV to MP4 with FFmpeg's own muxer (it turns the frames into length-prefixed ones, as MediaMuxer writes them). */
    private static void remux(Path in, Path out) throws Exception {
        org.bytedeco.ffmpeg.avformat.AVFormatContext ic = new org.bytedeco.ffmpeg.avformat.AVFormatContext(null);
        assertEquals(0, org.bytedeco.ffmpeg.global.avformat.avformat_open_input(ic, in.toString(), null, (org.bytedeco.ffmpeg.avutil.AVDictionary) null));
        org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info(ic, (org.bytedeco.ffmpeg.avutil.AVDictionary) null);
        org.bytedeco.ffmpeg.avformat.AVFormatContext oc = new org.bytedeco.ffmpeg.avformat.AVFormatContext(null);
        org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2(oc, null, "mp4", out.toString());
        org.bytedeco.ffmpeg.avformat.AVStream os = org.bytedeco.ffmpeg.global.avformat.avformat_new_stream(oc, null);
        org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy(os.codecpar(), ic.streams(0).codecpar());
        os.codecpar().codec_tag(0);
        os.time_base(ic.streams(0).time_base());
        org.bytedeco.ffmpeg.avformat.AVIOContext pb = new org.bytedeco.ffmpeg.avformat.AVIOContext(null);
        org.bytedeco.ffmpeg.global.avformat.avio_open(pb, out.toString(), org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE);
        oc.pb(pb);
        org.bytedeco.ffmpeg.global.avformat.avformat_write_header(oc, (org.bytedeco.ffmpeg.avutil.AVDictionary) null);
        org.bytedeco.ffmpeg.avcodec.AVPacket pkt = org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc();
        while (org.bytedeco.ffmpeg.global.avformat.av_read_frame(ic, pkt) >= 0) {
            org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts(pkt, ic.streams(0).time_base(), os.time_base());
            pkt.stream_index(0);
            org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame(oc, pkt);
            org.bytedeco.ffmpeg.global.avcodec.av_packet_unref(pkt);
        }
        org.bytedeco.ffmpeg.global.avformat.av_write_trailer(oc);
        org.bytedeco.ffmpeg.global.avformat.avio_closep(oc.pb());
        org.bytedeco.ffmpeg.global.avformat.avformat_free_context(oc);
        org.bytedeco.ffmpeg.global.avcodec.av_packet_free(pkt);
        org.bytedeco.ffmpeg.global.avformat.avformat_close_input(ic);
    }
}
