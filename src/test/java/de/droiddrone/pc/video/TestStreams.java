package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;

import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/** Encodes solid-colour test video with FFmpeg's libopenh264, standing in for the phone's MediaCodec output. */
public final class TestStreams {
    public record Packet(byte[] data, boolean keyFrame) {}

    public record Stream(byte[] codecConfig, List<Packet> packets) {}

    /** BT.601 limited-range red, as Y/U/V. */
    static final int[] RED = {81, 90, 240};

    private TestStreams() {
    }

    public static Stream h264(int width, int height, int frames, int gop) {
        return h264(width, height, frames, gop, false);
    }

    /** @param textured moving detail + noise (like a camera) instead of solid red - realistic decode cost */
    public static Stream h264(int width, int height, int frames, int gop, boolean textured) {
        AVCodec codec = avcodec_find_encoder_by_name("libopenh264");
        if (codec == null || codec.isNull()) throw new IllegalStateException("libopenh264 encoder missing");
        AVCodecContext c = avcodec_alloc_context3(codec);
        c.width(width);
        c.height(height);
        c.pix_fmt(AV_PIX_FMT_YUV420P);
        c.time_base(av_make_q(1, 30));
        c.framerate(av_make_q(30, 1));
        c.bit_rate(textured ? 6_000_000 : 2_000_000);
        c.gop_size(gop);
        c.max_b_frames(0);
        c.flags(c.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
        if (avcodec_open2(c, codec, (AVDictionary) null) < 0) throw new IllegalStateException("cannot open libopenh264");
        byte[] config = new byte[c.extradata_size()];
        if (config.length > 0) c.extradata().get(config);

        AVFrame f = av_frame_alloc();
        f.format(AV_PIX_FMT_YUV420P);
        f.width(width);
        f.height(height);
        av_frame_get_buffer(f, 32);
        List<Packet> packets = new ArrayList<>();
        AVPacket pkt = av_packet_alloc();
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i <= frames; i++) {
            AVFrame send = null;
            if (i < frames) {
                av_frame_make_writable(f);
                if (textured) {
                    texture(f, 0, width, height, i, random);
                    texture(f, 1, width / 2, height / 2, i, random);
                    texture(f, 2, width / 2, height / 2, i, random);
                } else {
                    fill(f, 0, RED[0], height);
                    fill(f, 1, RED[1], height / 2);
                    fill(f, 2, RED[2], height / 2);
                }
                f.pts(i);
                send = f;
            }
            avcodec_send_frame(c, send); // null flushes
            while (avcodec_receive_packet(c, pkt) == 0) {
                byte[] data = new byte[pkt.size()];
                pkt.data().get(data);
                packets.add(new Packet(data, (pkt.flags() & AV_PKT_FLAG_KEY) != 0));
                av_packet_unref(pkt);
            }
        }
        av_packet_free(pkt);
        av_frame_free(f);
        avcodec_free_context(c);
        return new Stream(config, packets);
    }

    /** Diagonal stripes that move each frame, plus sensor-like noise. */
    private static void texture(AVFrame f, int plane, int w, int h, int frame, java.util.Random random) {
        int stride = f.linesize(plane);
        byte[] data = new byte[stride * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = ((x + y + frame * 6) * 3) & 0xff;
                data[y * stride + x] = (byte) Math.max(16, Math.min(235, v / 2 + 60 + random.nextInt(24)));
            }
        }
        f.data(plane).put(data);
    }

    private static void fill(AVFrame f, int plane, int value, int rows) {
        byte[] data = new byte[f.linesize(plane) * rows];
        java.util.Arrays.fill(data, (byte) value);
        f.data(plane).put(data);
    }
}
