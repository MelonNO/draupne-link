package de.droiddrone.pc.audio;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.ShortPointer;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * FFmpeg's own AAC decoder (the FFmpeg the video already uses): raw AAC-LC frames from the phone, the
 * AudioSpecificConfig as extradata, out come float samples, channels interleaved. One thread, its caller's (the live
 * audio's, at low priority): FFmpeg's AAC decoder starts no threads of its own. Not thread-safe.
 */
final class AacDecoder implements AutoCloseable {
    private final AVCodecContext ctx;
    private final AVPacket packet = av_packet_alloc();
    private final AVFrame frame = av_frame_alloc();
    private long errors;

    AacDecoder(AudioConfig config) {
        AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_AAC);
        if (codec == null || codec.isNull()) {
            free(null);
            throw new IllegalStateException("No AAC decoder");
        }
        AVCodecContext c = avcodec_alloc_context3(codec);
        c.sample_rate(config.sampleRate());
        av_channel_layout_default(c.ch_layout(), config.channels());
        c.thread_count(1);
        byte[] asc = config.asc();
        if (asc != null && asc.length > 0) {
            // freed by avcodec_free_context
            BytePointer extra = new BytePointer(av_mallocz(asc.length + AV_INPUT_BUFFER_PADDING_SIZE))
                    .capacity(asc.length + AV_INPUT_BUFFER_PADDING_SIZE);
            extra.put(asc, 0, asc.length);
            c.extradata(extra);
            c.extradata_size(asc.length);
        }
        int r = avcodec_open2(c, codec, (AVDictionary) null);
        if (r < 0) {
            free(c);
            throw new IllegalStateException("Could not open the AAC decoder (" + r + ")");
        }
        ctx = c;
    }

    private void free(AVCodecContext c) {
        if (c != null) avcodec_free_context(c);
        av_packet_free(packet);
        av_frame_free(frame);
    }

    /**
     * One AAC frame.
     * @return its samples, channels interleaved, -1..1; null when nothing came out (a broken frame: the gap stays a gap)
     */
    float[] decode(byte[] data) {
        if (av_new_packet(packet, data.length) < 0) return null;
        packet.data().put(data, 0, data.length);
        int r = avcodec_send_packet(ctx, packet);
        av_packet_unref(packet);
        if (r < 0 && r != AVERROR_EAGAIN()) errors++;
        float[] out = null;
        while (avcodec_receive_frame(ctx, frame) == 0) {
            float[] got = samples(frame);
            out = out == null ? got : concat(out, got);
            av_frame_unref(frame);
        }
        return out;
    }

    /** The channels in this decoder's output (the stream's, as FFmpeg read them). */
    int channels() {
        return Math.max(1, ctx.ch_layout().nb_channels());
    }

    long errors() {
        return errors;
    }

    /** The frame's samples interleaved as floats, whichever of FFmpeg's sample formats it came in. */
    private static float[] samples(AVFrame f) {
        int n = f.nb_samples(), ch = Math.max(1, f.ch_layout().nb_channels()), fmt = f.format();
        float[] out = new float[n * ch];
        if (fmt == AV_SAMPLE_FMT_FLTP) { // the AAC decoder's own
            float[] plane = new float[n];
            for (int c = 0; c < ch; c++) {
                new FloatPointer(f.data(c)).capacity(n).get(plane);
                for (int i = 0; i < n; i++) out[i * ch + c] = plane[i];
            }
        } else if (fmt == AV_SAMPLE_FMT_FLT) {
            new FloatPointer(f.data(0)).capacity((long) n * ch).get(out);
        } else if (fmt == AV_SAMPLE_FMT_S16P || fmt == AV_SAMPLE_FMT_S16) {
            boolean planar = fmt == AV_SAMPLE_FMT_S16P;
            short[] s = new short[planar ? n : n * ch];
            for (int c = 0; c < (planar ? ch : 1); c++) {
                new ShortPointer(f.data(c)).capacity(s.length).get(s);
                for (int i = 0; i < s.length; i++) {
                    out[planar ? i * ch + c : i] = s[i] / 32768f;
                }
            }
        } else {
            return new float[n * ch]; // a format FFmpeg's AAC decoder doesn't give: silence of the right length
        }
        return out;
    }

    private static float[] concat(float[] a, float[] b) {
        float[] c = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    @Override
    public void close() {
        free(ctx);
    }
}
