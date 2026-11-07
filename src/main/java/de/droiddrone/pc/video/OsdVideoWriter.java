package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import de.droiddrone.pc.audio.AudioConfig;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Encodes pictures (0xRRGGBB pixels, the OSD already drawn in) into an H.264 MKV - the second file of a recording
 * (1.0.42), next to the stream as it came. OpenH264 in software on one thread (the caller's, at low priority), MPEG-4
 * if this FFmpeg has no H.264 encoder. Timestamps in ms, the same clock as the raw file's. Not thread-safe.
 * 1.0.45: the live audio as the raw file has it (the same AAC frames and timestamps), when that one has a sound track.
 */
public final class OsdVideoWriter implements AutoCloseable {
    private final AVFormatContext oc;
    private final AVCodecContext enc;
    private final AVStream stream;
    /** The sound's track, or null. */
    private AVStream audioStream;
    private AVPacket audioPkt;
    private long lastAudioPts = -1;
    private final AVFrame yuv;
    private final AVPacket pkt;
    private final SwsContext sws;
    private final BytePointer bgr;
    private final PointerPointer<BytePointer> srcData = new PointerPointer<>(4);
    private final IntPointer srcStride = new IntPointer(4);
    private final int width, height;
    private final String codecName;
    private long lastPts = -1;
    private long frames;
    private boolean closed;

    /** Bits per second for this picture size: plenty for the OSD's thin lines and text. */
    static int bitrate(int width, int height) {
        return (int) Math.min(8_000_000, Math.max(1_500_000, (long) width * height * 5));
    }

    public OsdVideoWriter(Path file, int width, int height, int fps) throws IOException {
        this(file, width, height, fps, null);
    }

    /** @param audio the raw file's sound track (1.0.45), or null for a file without sound */
    public OsdVideoWriter(Path file, int width, int height, int fps, AudioConfig audio) throws IOException {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) throw new IOException("Recording with OSD: bad size " + width + "x" + height);
        this.width = width;
        this.height = height;
        AVFormatContext ctx = new AVFormatContext(null);
        check(avformat_alloc_output_context2(ctx, null, "matroska", file.toString()), "create " + file);
        oc = ctx;
        AVCodec codec = avcodec_find_encoder_by_name("libopenh264");
        if (codec == null || codec.isNull()) codec = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
        if (codec == null || codec.isNull()) {
            avformat_free_context(oc);
            throw new IOException("Recording with OSD: no video encoder");
        }
        codecName = codec.name().getString();
        enc = avcodec_alloc_context3(codec);
        enc.width(width);
        enc.height(height);
        enc.pix_fmt(AV_PIX_FMT_YUV420P);
        enc.time_base(av_make_q(1, 1000));
        enc.framerate(av_make_q(Math.max(1, fps), 1));
        enc.gop_size(Math.max(1, fps) * 2);
        enc.max_b_frames(0);
        enc.bit_rate(bitrate(width, height));
        enc.thread_count(1); // on the caller's thread only: never beside the live video's threads
        if ((oc.oformat().flags() & AVFMT_GLOBALHEADER) != 0) enc.flags(enc.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
        int r = avcodec_open2(enc, codec, (AVDictionary) null);
        if (r < 0) {
            avcodec_free_context(enc);
            avformat_free_context(oc);
            throw new IOException("Recording with OSD: could not open the " + codecName + " encoder (" + r + ")");
        }
        stream = avformat_new_stream(oc, null);
        yuv = av_frame_alloc();
        pkt = av_packet_alloc();
        long bytes = (long) width * height * 4;
        bgr = new BytePointer(av_malloc(bytes)).capacity(bytes);
        sws = sws_getContext(width, height, AV_PIX_FMT_BGRA, width, height, AV_PIX_FMT_YUV420P, SWS_BILINEAR, null, null, (DoublePointer) null);
        try {
            if (stream == null || sws == null || sws.isNull()) throw new IOException("Recording with OSD: could not set up the file");
            check(avcodec_parameters_from_context(stream.codecpar(), enc), "set the stream up");
            stream.time_base(av_make_q(1, 1000));
            if (audio != null) {
                audioStream = StreamRecorder.addAacStream(oc, audio);
                if (audioStream == null) throw new IOException("Recording with OSD: no audio track");
                audioPkt = av_packet_alloc();
            }
            yuv.format(AV_PIX_FMT_YUV420P);
            yuv.width(width);
            yuv.height(height);
            check(av_frame_get_buffer(yuv, 0), "make a picture");
            AVIOContext pb = new AVIOContext(null);
            check(avio_open(pb, file.toString(), AVIO_FLAG_WRITE), "open " + file);
            oc.pb(pb);
            check(avformat_write_header(oc, (AVDictionary) null), "write the header");
        } catch (IOException | RuntimeException e) {
            free();
            throw e;
        }
    }

    /** The encoder in use: "libopenh264", or "mpeg4" where there is no H.264 one. */
    public String codecName() {
        return codecName;
    }

    /** One picture, width x height 0xRRGGBB pixels, at ptsMs (made strictly rising). */
    public void write(int[] pixels, long ptsMs) throws IOException {
        if (closed) throw new IOException("Recording with OSD: closed");
        if (pixels.length < width * height) throw new IllegalArgumentException("Too few pixels");
        // 0xRRGGBB ints as little-endian bytes are B, G, R, 0: BGRA with alpha unused
        bgr.position(0).limit((long) width * height * 4).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels, 0, width * height);
        check(av_frame_make_writable(yuv), "reuse the picture");
        srcData.put(0, bgr);
        srcStride.put(0, width * 4);
        sws_scale(sws, srcData, srcStride, 0, height, yuv.data(), yuv.linesize());
        long pts = Math.max(ptsMs, lastPts + 1);
        lastPts = pts;
        yuv.pts(pts);
        check(avcodec_send_frame(enc, yuv), "encode a picture");
        drain();
        frames++;
    }

    public long frames() {
        return frames;
    }

    /** One AAC frame of the sound at the raw file's timestamp (1.0.45); nothing without a sound track. */
    public void writeAudio(byte[] data, long ptsMs) throws IOException {
        if (closed || audioStream == null || ptsMs <= lastAudioPts) return;
        lastAudioPts = ptsMs;
        check(StreamRecorder.writeAac(oc, audioPkt, audioStream, data, ptsMs), "write the sound");
    }

    private void drain() throws IOException {
        while (true) {
            int r = avcodec_receive_packet(enc, pkt);
            if (r == AVERROR_EAGAIN() || r == AVERROR_EOF) return;
            check(r, "encode");
            av_packet_rescale_ts(pkt, enc.time_base(), stream.time_base());
            pkt.stream_index(stream.index());
            r = av_interleaved_write_frame(oc, pkt); // takes the packet's data
            av_packet_unref(pkt);
            check(r, "write a picture");
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            avcodec_send_frame(enc, null); // the encoder's last pictures out
            drain();
        } catch (IOException ignored) {
            // a file cut short still plays (MKV)
        }
        av_write_trailer(oc);
        free();
    }

    private void free() {
        if (oc.pb() != null && !oc.pb().isNull()) avio_closep(oc.pb());
        avformat_free_context(oc);
        avcodec_free_context(enc);
        av_frame_free(yuv);
        av_packet_free(pkt);
        if (audioPkt != null) av_packet_free(audioPkt);
        if (sws != null && !sws.isNull()) sws_freeContext(sws);
        av_free(bgr);
        srcData.close();
        srcStride.close();
    }

    private static void check(int result, String what) throws IOException {
        if (result < 0) {
            BytePointer msg = new BytePointer(256);
            av_strerror(result, msg, 256);
            throw new IOException("Recording with OSD: could not " + what + " (" + msg.getString().trim() + ")");
        }
    }
}
