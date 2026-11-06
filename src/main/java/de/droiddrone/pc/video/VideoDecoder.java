package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.nio.ByteOrder;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * FFmpeg H.265/H.264 decoder tuned for latency: no frame threading (each frame thread holds back one frame), slice
 * threading only, LOW_DELAY so no frame is held for reordering (the phone's encoder sends no B-frames), and colour
 * conversion that scales straight to the on-screen size so drawing is a plain copy. Optionally on the GPU (D3D11VA,
 * SETTINGS > Hardware video decoding): the picture is then copied back only when it is drawn; if the GPU can't
 * decode it, FFmpeg decodes in software as usual.
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
    /** The GPU device when decoding in hardware, else null. */
    private org.bytedeco.ffmpeg.avutil.AVBufferRef hwDevice;
    /** A hardware picture copied back to memory for the colour conversion. */
    private final AVFrame copied = av_frame_alloc();

    /** @param codecConfig Annex-B parameter sets from the phone's VideoInitialFrame (may be empty) */
    public VideoDecoder(boolean hevc, byte[] codecConfig) {
        this(hevc, codecConfig, false);
    }

    /** @param hardware decode on the GPU (D3D11VA) if this PC can; software otherwise */
    public VideoDecoder(boolean hevc, byte[] codecConfig, boolean hardware) {
        this(hevc, codecConfig, hardware, false);
    }

    /**
     * A second decoder that must never compete with the live one (1.0.42: the recording with the OSD drawn in): in
     * software, one thread for decoding and scaling, so all of its work runs on the caller's (low priority) thread.
     */
    public static VideoDecoder oneThread(boolean hevc, byte[] codecConfig) {
        return new VideoDecoder(hevc, codecConfig, false, true);
    }

    /** One thread only: FFmpeg's own threads would run at normal priority, beside the live video's. */
    private final boolean oneThread;

    private VideoDecoder(boolean hevc, byte[] codecConfig, boolean hardware, boolean oneThread) {
        this.oneThread = oneThread;
        AVCodec codec = avcodec_find_decoder(hevc ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264);
        if (codec == null || codec.isNull()) {
            freeBeforeOpen();
            throw new IllegalStateException("No " + (hevc ? "H.265" : "H.264") + " decoder");
        }
        ctx = avcodec_alloc_context3(codec);
        // OUTPUT_CORRUPT + SHOW_ALL: after a lost fragment keep showing (concealed) pictures instead of freezing until
        // the next key frame - in the air a smeared live picture beats a frozen one (upstream's Android decoder does too)
        ctx.flags(ctx.flags() | AV_CODEC_FLAG_LOW_DELAY | AV_CODEC_FLAG_OUTPUT_CORRUPT);
        ctx.flags2(ctx.flags2() | AV_CODEC_FLAG2_FAST | AV_CODEC_FLAG2_SHOW_ALL);
        ctx.thread_type(AVCodecContext.FF_THREAD_SLICE);
        ctx.thread_count(oneThread ? 1 : 0);
        if (codecConfig != null && codecConfig.length > 0) {
            // freed by avcodec_free_context
            BytePointer extra = new BytePointer(av_mallocz(codecConfig.length + AV_INPUT_BUFFER_PADDING_SIZE))
                    .capacity(codecConfig.length + AV_INPUT_BUFFER_PADDING_SIZE);
            extra.put(codecConfig, 0, codecConfig.length);
            ctx.extradata(extra);
            ctx.extradata_size(codecConfig.length);
        }
        if (hardware) {
            // with a device attached, FFmpeg's own format choice takes the GPU format when the codec supports it
            org.bytedeco.ffmpeg.avutil.AVBufferRef dev = new org.bytedeco.ffmpeg.avutil.AVBufferRef(null);
            if (av_hwdevice_ctx_create(dev, AV_HWDEVICE_TYPE_D3D11VA, (String) null, null, 0) >= 0) {
                hwDevice = dev;
                ctx.hw_device_ctx(av_buffer_ref(dev));
            }
        }
        int r = avcodec_open2(ctx, codec, (AVDictionary) null);
        if (r < 0) {
            if (hwDevice != null) av_buffer_unref(hwDevice);
            avcodec_free_context(ctx);
            freeBeforeOpen();
            throw new IllegalStateException("Could not open the decoder (" + r + ")");
        }
        packet = av_packet_alloc();
        frame = av_frame_alloc();
        spare = av_frame_alloc();
    }

    /**
     * A decoder that could not be made: what the field initialisers allocated goes (it leaked at every failed try -
     * and the pipeline now tries again at every key frame).
     */
    private void freeBeforeOpen() {
        av_frame_free(copied);
        dstData.close();
        dstStride.close();
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

    /** Decoding on the GPU (the last picture came from it). */
    public boolean isHardware() {
        return hwDevice != null && frame.format() == AV_PIX_FMT_D3D11;
    }

    public int width() {
        return frame.width();
    }

    public int height() {
        return frame.height();
    }

    /**
     * Forgets the pictures in hand (a dropped backlog): the next frame decodes on a clean slate, from the next key
     * frame. The decoder itself stays - a new one costs a black gap.
     */
    public void flush() {
        avcodec_flush_buffers(ctx);
    }

    /** Converts the last picture to 0xRRGGBB pixels of the given size into dst (length >= width * height). */
    public void convert(int[] dst, int width, int height) {
        // FFmpeg asserts (and kills the process) on an empty frame, so never hand it one
        if (frame.width() <= 0 || frame.height() <= 0 || frame.format() < 0) throw new IllegalStateException("No picture to convert");
        AVFrame src = frame;
        if (frame.format() == AV_PIX_FMT_D3D11) { // on the GPU: copy it back (NV12), only for pictures that are drawn
            av_frame_unref(copied);
            if (av_hwframe_transfer_data(copied, frame, 0) < 0) throw new IllegalStateException("Could not copy the picture from the GPU");
            src = copied;
        }
        converter(src, width, height);
        long needed = (long) width * height * 4;
        if (rgb == null || rgbCapacity < needed) {
            if (rgb != null) av_free(rgb);
            rgb = new BytePointer(av_malloc(needed)).capacity(needed);
            rgbCapacity = needed;
        }
        dstData.put(0, rgb);
        dstStride.put(0, width * 4);
        sws_scale(sws, src.data(), src.linesize(), 0, src.height(), dstData, dstStride);
        // BGRA bytes read as little-endian ints are 0xAARRGGBB
        rgb.position(0).limit(needed).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(dst, 0, width * height);
    }

    /**
     * The scaler for this picture and screen size, kept between frames like sws_getCachedContext would - made by hand
     * so its options can be set before it is initialised: slice threads (FFmpeg 5.1 on; the bundled one is 7.1) share
     * the scaling of a big picture between cores. The colour matrix follows the picture: the phone's H.265 is BT.709
     * (HD) and the scaler's default is BT.601 (SD), which shifts reds and greens. Unspecified: BT.709 for HD sizes.
     */
    private void converter(AVFrame src, int width, int height) {
        int colorspace = frame.colorspace(), range = frame.color_range();
        if (sws != null && src.width() == swsSrcW && src.height() == swsSrcH && src.format() == swsSrcFmt
                && width == swsDstW && height == swsDstH && colorspace == swsColorspace && range == swsRange) return;
        if (sws != null) sws_freeContext(sws);
        sws = sws_alloc_context();
        if (sws == null || sws.isNull()) throw new IllegalStateException("No colour converter");
        av_opt_set_int(sws, "srcw", src.width(), 0);
        av_opt_set_int(sws, "srch", src.height(), 0);
        av_opt_set_int(sws, "src_format", src.format(), 0);
        av_opt_set_int(sws, "dstw", width, 0);
        av_opt_set_int(sws, "dsth", height, 0);
        av_opt_set_int(sws, "dst_format", AV_PIX_FMT_BGRA, 0);
        // up-scaling (a 720p picture on a 1080p screen) bicubic: visibly sharper edges for 0.4 ms more a frame (720p to
        // 1080p: 2.6 ms fast bilinear, 3.0 ms bicubic on an i7-14700); 1:1 and down-scaling fast bilinear as before
        av_opt_set_int(sws, "sws_flags", width > src.width() ? SWS_BICUBIC : SWS_FAST_BILINEAR, 0);
        av_opt_set_int(sws, "threads", oneThread ? 1 : SCALE_THREADS, 0);
        if (sws_init_context(sws, null, null) < 0) {
            sws_freeContext(sws);
            sws = null;
            throw new IllegalStateException("No colour converter for pixel format " + frame.format());
        }
        int cs = coefficients(colorspace, src.height());
        // source range as flagged (the phone's encoder: limited), BGRA out is always full
        sws_setColorspaceDetails(sws, sws_getCoefficients(cs), range == AVCOL_RANGE_JPEG ? 1 : 0,
                sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16, 1 << 16);
        swsSrcW = src.width();
        swsSrcH = src.height();
        swsSrcFmt = src.format();
        swsDstW = width;
        swsDstH = height;
        swsColorspace = colorspace;
        swsRange = range;
    }

    /** The scaler's YUV matrix for a picture's colourspace tag (AVCOL_SPC_*); untagged pictures by their size. */
    static int coefficients(int colorspace, int height) {
        return switch (colorspace) {
            case AVCOL_SPC_BT709 -> SWS_CS_ITU709;
            case AVCOL_SPC_BT470BG -> SWS_CS_ITU601;
            case AVCOL_SPC_SMPTE170M -> SWS_CS_SMPTE170M;
            case AVCOL_SPC_SMPTE240M -> SWS_CS_SMPTE240M;
            case AVCOL_SPC_FCC -> SWS_CS_FCC;
            case AVCOL_SPC_BT2020_NCL, AVCOL_SPC_BT2020_CL -> SWS_CS_BT2020;
            default -> height >= 720 ? SWS_CS_ITU709 : SWS_CS_ITU601;
        };
    }

    /** The scaler's slice threads: 0 = one per core. Measured on 720p -> 1080p BGRA, see VideoTest.decodeAndDrawTimeAt720p. */
    static final int SCALE_THREADS = 0;

    private int swsSrcW, swsSrcH, swsSrcFmt = -1, swsDstW, swsDstH, swsColorspace = -1, swsRange = -1;

    public long getErrors() {
        return errors;
    }

    @Override
    public void close() {
        if (sws != null) sws_freeContext(sws);
        if (rgb != null) av_free(rgb);
        av_frame_free(frame);
        av_frame_free(spare);
        av_frame_free(copied);
        if (hwDevice != null) av_buffer_unref(hwDevice);
        av_packet_free(packet);
        avcodec_free_context(ctx);
        dstData.close();
        dstStride.close();
    }
}
