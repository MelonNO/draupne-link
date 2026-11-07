package de.droiddrone.pc.fleet;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * A virtual phone's video encoder: pictures (0xRRGGBB) in, H.264 out as the phone's MediaCodec gives it - the codec
 * config (SPS/PPS, Annex B) once, then a packet per picture, key frames every 2 s and on request. FFmpeg's libopenh264
 * on the caller's thread alone, one slice. Not thread-safe.
 */
final class H264Encoder implements AutoCloseable {
    /** One encoded picture. */
    record Packet(byte[] data, boolean keyFrame) {}

    private final AVCodecContext enc;
    private final AVFrame yuv;
    private final AVPacket pkt;
    private final SwsContext sws;
    private final BytePointer bgr;
    private final PointerPointer<BytePointer> srcData = new PointerPointer<>(4);
    private final IntPointer srcStride = new IntPointer(4);
    private final int width, height, fps, bitRate;
    private final byte[] config;
    private long pts;
    private boolean closed;

    static {
        av_log_set_level(AV_LOG_FATAL); // the encoders' chatter (OpenH264's profile notes) stays out of the console
    }

    H264Encoder(int width, int height, int fps, int bitRate) throws IOException {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) throw new IOException("bad picture size " + width + "x" + height);
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        this.bitRate = Math.max(100_000, bitRate);
        AVCodec codec = avcodec_find_encoder_by_name("libopenh264");
        if (codec == null || codec.isNull()) throw new IOException("this FFmpeg has no H.264 encoder (libopenh264)");
        enc = avcodec_alloc_context3(codec);
        enc.width(width);
        enc.height(height);
        enc.pix_fmt(AV_PIX_FMT_YUV420P);
        enc.time_base(av_make_q(1, this.fps));
        enc.framerate(av_make_q(this.fps, 1));
        enc.gop_size(this.fps * 2);
        enc.max_b_frames(0);
        enc.bit_rate(this.bitRate);
        enc.thread_count(1);
        enc.flags(enc.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
        // the bitrate held as asked (the PC's bitrate control judges what arrives), a picture skipped rather than overshoot
        AVDictionary opts = new AVDictionary(null);
        av_dict_set(opts, "rc_mode", "bitrate", 0);
        av_dict_set(opts, "allow_skip_frames", "1", 0);
        int r = avcodec_open2(enc, codec, opts);
        av_dict_free(opts);
        if (r < 0) {
            avcodec_free_context(enc);
            throw new IOException("could not open libopenh264 (" + r + ")");
        }
        config = new byte[enc.extradata_size()];
        if (config.length > 0) enc.extradata().get(config);
        yuv = av_frame_alloc();
        pkt = av_packet_alloc();
        long bytes = (long) width * height * 4;
        bgr = new BytePointer(av_malloc(bytes)).capacity(bytes);
        sws = sws_getContext(width, height, AV_PIX_FMT_BGRA, width, height, AV_PIX_FMT_YUV420P, SWS_BILINEAR, null, null, (DoublePointer) null);
        yuv.format(AV_PIX_FMT_YUV420P);
        yuv.width(width);
        yuv.height(height);
        if (sws == null || sws.isNull() || av_frame_get_buffer(yuv, 0) < 0) {
            free();
            throw new IOException("could not set up the picture conversion");
        }
    }

    /** The codec config (Annex B SPS/PPS): the VideoInitialFrame's payload. */
    byte[] config() {
        return config;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int fps() {
        return fps;
    }

    int bitRate() {
        return bitRate;
    }

    /** One picture (width x height pixels); a key frame when asked. Packets out (none when the encoder skipped it). */
    List<Packet> encode(int[] pixels, boolean keyFrame) throws IOException {
        if (closed) throw new IOException("closed");
        bgr.position(0).limit((long) width * height * 4).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels, 0, width * height);
        if (av_frame_make_writable(yuv) < 0) throw new IOException("could not reuse the picture");
        srcData.put(0, bgr);
        srcStride.put(0, width * 4);
        sws_scale(sws, srcData, srcStride, 0, height, yuv.data(), yuv.linesize());
        yuv.pts(pts++);
        yuv.pict_type(keyFrame ? AV_PICTURE_TYPE_I : AV_PICTURE_TYPE_NONE);
        if (avcodec_send_frame(enc, yuv) < 0) throw new IOException("could not encode a picture");
        List<Packet> out = new ArrayList<>(1);
        while (avcodec_receive_packet(enc, pkt) == 0) {
            if (pkt.size() > 0) {
                byte[] data = new byte[pkt.size()];
                pkt.data().get(data);
                out.add(new Packet(data, (pkt.flags() & AV_PKT_FLAG_KEY) != 0));
            }
            av_packet_unref(pkt);
        }
        return out;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        free();
    }

    private void free() {
        avcodec_free_context(enc);
        if (yuv != null) av_frame_free(yuv);
        if (pkt != null) av_packet_free(pkt);
        if (sws != null && !sws.isNull()) sws_freeContext(sws);
        if (bgr != null) av_free(bgr);
        srcData.close();
        srcStride.close();
    }
}
