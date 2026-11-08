package de.droiddrone.pc.fleet;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.FloatPointer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * A virtual phone's sound encoder, as DRAUPNE Flight pc.34 streams it: AAC-LC, 48 kHz mono, raw frames of 1024
 * samples (no ADTS header) and the AudioSpecificConfig once. FFmpeg's own AAC encoder. Not thread-safe. The tour's
 * scenario drone streams two channels (the PC plays what the config declares), so its BTR is heard from its side.
 */
final class AacEncoder implements AutoCloseable {
    static final int SAMPLE_RATE = 48_000, FRAME = 1024;

    private final AVCodecContext enc;
    private final AVFrame frame;
    private final AVPacket pkt;
    private final byte[] config;
    private final int bitRate, channels;
    private long pts;
    private boolean closed;

    static {
        av_log_set_level(AV_LOG_FATAL);
    }

    AacEncoder(int bitRate) throws IOException {
        this(bitRate, 1);
    }

    /** @param channels 1 (a phone's microphone) or 2 (the tour's stereo scene) */
    AacEncoder(int bitRate, int channels) throws IOException {
        this.bitRate = bitRate;
        this.channels = channels;
        AVCodec codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (codec == null || codec.isNull()) throw new IOException("this FFmpeg has no AAC encoder");
        enc = avcodec_alloc_context3(codec);
        enc.sample_fmt(AV_SAMPLE_FMT_FLTP);
        enc.sample_rate(SAMPLE_RATE);
        av_channel_layout_default(enc.ch_layout(), channels);
        enc.bit_rate(bitRate);
        enc.time_base(av_make_q(1, SAMPLE_RATE));
        enc.flags(enc.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
        int r = avcodec_open2(enc, codec, (AVDictionary) null);
        if (r < 0) {
            avcodec_free_context(enc);
            throw new IOException("could not open the AAC encoder (" + r + ")");
        }
        config = new byte[enc.extradata_size()];
        if (config.length > 0) enc.extradata().get(config);
        frame = av_frame_alloc();
        frame.format(AV_SAMPLE_FMT_FLTP);
        frame.sample_rate(SAMPLE_RATE);
        frame.nb_samples(FRAME);
        av_channel_layout_default(frame.ch_layout(), channels);
        pkt = av_packet_alloc();
        if (av_frame_get_buffer(frame, 0) < 0) {
            close();
            throw new IOException("could not make an audio frame");
        }
    }

    /** The AudioSpecificConfig (0x11 0x88 for 48 kHz mono). */
    byte[] config() {
        return config;
    }

    int bitRate() {
        return bitRate;
    }

    int channels() {
        return channels;
    }

    /** FRAME samples (-1..1) in; the raw AAC frames out (the encoder runs a frame or two behind). */
    List<byte[]> encode(float[] samples) throws IOException {
        return channels == 1 ? encode(samples, null) : encode(samples, samples);
    }

    /** As encode, two channels (right ignored by a mono encoder). */
    List<byte[]> encode(float[] left, float[] right) throws IOException {
        if (closed) throw new IOException("closed");
        if (av_frame_make_writable(frame) < 0) throw new IOException("could not reuse the frame");
        new FloatPointer(frame.data(0)).put(left, 0, FRAME);
        if (channels == 2) new FloatPointer(frame.data(1)).put(right, 0, FRAME);
        frame.pts(pts);
        pts += FRAME;
        if (avcodec_send_frame(enc, frame) < 0) throw new IOException("could not encode the sound");
        List<byte[]> out = new ArrayList<>(2);
        while (avcodec_receive_packet(enc, pkt) == 0) {
            byte[] data = new byte[pkt.size()];
            pkt.data().get(data);
            out.add(data);
            av_packet_unref(pkt);
        }
        return out;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        avcodec_free_context(enc);
        if (frame != null) av_frame_free(frame);
        if (pkt != null) av_packet_free(pkt);
    }
}
