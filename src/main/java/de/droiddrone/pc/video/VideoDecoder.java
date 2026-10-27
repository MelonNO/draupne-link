package de.droiddrone.pc.video;

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

import java.nio.ByteOrder;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * FFmpeg H.265/H.264 decoder tuned for latency: no frame threading (each frame thread holds back one frame), slice
 * threading only, LOW_DELAY so no frame is held for reordering (the phone's encoder sends no B-frames), and colour
 * conversion that scales straight to the on-screen size so drawing is a plain copy.
 * Not thread-safe: use from one decode thread.
 */
public final class VideoDecoder implements AutoCloseable {
    static {
        av_log_set_level(AV_LOG_FATAL); // concealment of lost packets would otherwise flood the console
    }

    private final AVCodecContext ctx;
    private final AVPacket packet;
    private final AVFrame frame;
    private final AVFrame spare;
    private SwsContext sws;
    private BytePointer rgb;
    private long rgbCapacity;
    private final PointerPointer<BytePointer> dstData = new PointerPointer<>(4);
    private final IntPointer dstStride = new IntPointer(4);
    private long errors;

    /** @param codecConfig Annex-B parameter sets from the phone's VideoInitialFrame (may be empty) */
    public VideoDecoder(boolean hevc, byte[] codecConfig) {
        AVCodec codec = avcodec_find_decoder(hevc ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264);
        if (codec == null || codec.isNull()) throw new IllegalStateException("No " + (hevc ? "H.265" : "H.264") + " decoder");
        ctx = avcodec_alloc_context3(codec);
        // OUTPUT_CORRUPT + SHOW_ALL: after a lost fragment keep showing (concealed) pictures instead of freezing until
        // the next key frame - in the air a smeared live picture beats a frozen one (upstream's Android decoder does too)
        ctx.flags(ctx.flags() | AV_CODEC_FLAG_LOW_DELAY | AV_CODEC_FLAG_OUTPUT_CORRUPT);
        ctx.flags2(ctx.flags2() | AV_CODEC_FLAG2_FAST | AV_CODEC_FLAG2_SHOW_ALL);
        ctx.thread_type(AVCodecContext.FF_THREAD_SLICE);
        ctx.thread_count(0);
        if (codecConfig != null && codecConfig.length > 0) {
            // freed by avcodec_free_context
            BytePointer extra = new BytePointer(av_mallocz(codecConfig.length + AV_INPUT_BUFFER_PADDING_SIZE))
                    .capacity(codecConfig.length + AV_INPUT_BUFFER_PADDING_SIZE);
            extra.put(codecConfig, 0, codecConfig.length);
            ctx.extradata(extra);
            ctx.extradata_size(codecConfig.length);
        }
        int r = avcodec_open2(ctx, codec, (AVDictionary) null);
        if (r < 0) {
            avcodec_free_context(ctx);
            throw new IllegalStateException("Could not open the decoder (" + r + ")");
        }
        packet = av_packet_alloc();
        frame = av_frame_alloc();
        spare = av_frame_alloc();
    }

    /**
     * Decodes one frame (Annex-B access unit).
     * @return true if a picture came out; it stays available for {@link #convert} until the next call
     */
    public boolean decode(byte[] data, int length) {
        if (av_new_packet(packet, length) < 0) return false;
        packet.data().put(data, 0, length);
        int r = avcodec_send_packet(ctx, packet);
        av_packet_unref(packet);
        if (r < 0 && r != AVERROR_EAGAIN()) errors++;
        // avcodec_receive_frame clears its frame even when it has nothing to return, so receive into a spare and
        // only move a real picture into `frame` (keeping the newest if several come out)
        boolean got = false;
        while (avcodec_receive_frame(ctx, spare) == 0) {
            av_frame_unref(frame);
            av_frame_move_ref(frame, spare);
            got = true;
        }
        return got;
    }

    public int width() {
        return frame.width();
    }

    public int height() {
        return frame.height();
    }

    /** Converts the last picture to 0xRRGGBB pixels of the given size into dst (length >= width * height). */
    public void convert(int[] dst, int width, int height) {
        // FFmpeg asserts (and kills the process) on an empty frame, so never hand it one
        if (frame.width() <= 0 || frame.height() <= 0 || frame.format() < 0) throw new IllegalStateException("No picture to convert");
        sws = sws_getCachedContext(sws, frame.width(), frame.height(), frame.format(),
                width, height, AV_PIX_FMT_BGRA, SWS_FAST_BILINEAR, null, null, (DoublePointer) null);
        if (sws == null || sws.isNull()) throw new IllegalStateException("No colour converter for pixel format " + frame.format());
        long needed = (long) width * height * 4;
        if (rgb == null || rgbCapacity < needed) {
            if (rgb != null) av_free(rgb);
            rgb = new BytePointer(av_malloc(needed)).capacity(needed);
            rgbCapacity = needed;
        }
        dstData.put(0, rgb);
        dstStride.put(0, width * 4);
        sws_scale(sws, frame.data(), frame.linesize(), 0, frame.height(), dstData, dstStride);
        // BGRA bytes read as little-endian ints are 0xAARRGGBB
        rgb.position(0).limit(needed).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(dst, 0, width * height);
    }

    public long getErrors() {
        return errors;
    }

    @Override
    public void close() {
        if (sws != null) sws_freeContext(sws);
        if (rgb != null) av_free(rgb);
        av_frame_free(frame);
        av_frame_free(spare);
        av_packet_free(packet);
        avcodec_free_context(ctx);
        dstData.close();
        dstStride.close();
    }
}
