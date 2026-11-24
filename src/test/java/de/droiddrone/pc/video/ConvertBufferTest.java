package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.Pointer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F-091: the colour conversion writes only into what the app allocated for it, and writes every pixel of the picture.
 * FFmpeg 7.1 converts YUV to BGRA at a picture's own size (no scaling) 16 pixels at a time: into rows packed tight
 * (width * 4 bytes) it wrote up to 32 bytes past the end at widths like 168, 360 or 1080 - memory that was not the
 * app's, the app gone with a heap corruption - and left the last pixels of each row unwritten at widths like 854.
 * Each check runs the app's own convert() into what it would allocate plus a guard zone, all filled with a pattern.
 */
class ConvertBufferTest {
    private static final int GUARD = 4096;
    /** A converted BGRA pixel has alpha 255: a pixel still all FILL was never written. */
    private static final int FILL = 0xA5, FILL_PIXEL = 0xA5A5A5A5;

    private static Field field(String name) throws NoSuchFieldException {
        Field f = VideoDecoder.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** The decoder's convert() gets a buffer of the size it would allocate for w x h, with GUARD bytes more after it. */
    private static long guard(VideoDecoder d, int w, int h) throws ReflectiveOperationException {
        long bytes = VideoDecoder.rgbBytes(w, h);
        BytePointer old = (BytePointer) field("rgb").get(d);
        if (old != null) av_free(old.position(0));
        BytePointer buf = new BytePointer(av_malloc(bytes + GUARD)).capacity(bytes + GUARD);
        assertFalse(buf.isNull());
        Pointer.memset(buf, FILL, bytes + GUARD);
        field("rgb").set(d, buf);
        field("rgbCapacity").setLong(d, bytes); // the app's own: it is used as it is, not made again
        return bytes;
    }

    /**
     * convert() at w x h into a guarded buffer.
     *
     * @return what went wrong, or null: bytes written past the app's buffer, pixels never written
     */
    private static String check(VideoDecoder d, int w, int h, String what) throws ReflectiveOperationException {
        long bytes = guard(d, w, h);
        int[] dst = new int[w * h];
        d.convert(dst, w, h);
        BytePointer buf = (BytePointer) field("rgb").get(d);
        long past = 0;
        for (long i = bytes + GUARD - 1; i >= bytes; i--) {
            if ((buf.get(i) & 0xff) != FILL) {
                past = i + 1 - bytes;
                break;
            }
        }
        int unwritten = 0;
        for (int p : dst) if (p == FILL_PIXEL) unwritten++;
        if (past == 0 && unwritten == 0) return null;
        return String.format("%s to %dx%d: %d bytes written past the app's buffer, %d pixels never written", what, w, h, past, unwritten);
    }

    private static boolean feed(VideoDecoder d, TestStreams.Stream s) {
        boolean any = false;
        for (TestStreams.Packet p : s.packets()) any |= d.decode(p.data(), p.data().length);
        return any;
    }

    @Test
    void realPicturesAtTheirOwnSizeAndScaledStayInsideTheAppsBuffer() throws Exception {
        // the widths the fuzz found (168, 316, 360, 1080: past the end; 854: edge pixels unwritten) and ones it did not
        int[][] sizes = {{168, 96}, {316, 96}, {360, 640}, {854, 480}, {1080, 1920}, {1280, 720}, {1920, 1080}, {176, 144},
                {640, 360}, {200, 64}, {24, 16}, {40, 32}};
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (int[] s : sizes) {
            TestStreams.Stream stream = TestStreams.h264(s[0], s[1], 2, 2, true);
            for (boolean live : new boolean[]{true, false}) {
                String kind = live ? "live decoder" : "one-thread decoder";
                try (VideoDecoder d = live ? new VideoDecoder(false, stream.codecConfig()) : VideoDecoder.oneThread(false, stream.codecConfig())) {
                    assertTrue(feed(d, stream), "a picture at " + s[0] + "x" + s[1]);
                    assertEquals(s[0], d.width());
                    assertEquals(s[1], d.height());
                    String what = kind + " " + s[0] + "x" + s[1];
                    // its own size twice (the buffer kept the second time), then scaled: down, and up (bicubic)
                    for (int[] to : new int[][]{{s[0], s[1]}, {s[0], s[1]}, {Math.max(2, s[0] / 2 + 1), Math.max(2, s[1] / 2 + 1)}, {s[0] + 12, s[1] + 6}}) {
                        String bad = check(d, to[0], to[1], what);
                        checked++;
                        if (bad != null) wrong.add(bad);
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " of " + checked + " conversions:\n" + String.join("\n", wrong));
    }

    /**
     * The picture kinds a damaged or unusual stream can decode into (4:2:2, 4:4:4, 10 bits, grey; NV12 from the GPU),
     * at widths and heights a phone never sends - odd ones, tiny ones - each converted at its own size.
     */
    @Test
    void everyKindOfPictureAtItsOwnSizeStaysInsideTheAppsBuffer() throws Exception {
        TestStreams.Stream stream = TestStreams.h264(320, 240, 2, 2, true);
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        try (VideoDecoder source = VideoDecoder.oneThread(false, stream.codecConfig())) {
            assertTrue(feed(source, stream));
            AVFrame base = (AVFrame) field("frame").get(source);
            int[] formats = {AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUVJ420P, AV_PIX_FMT_NV12, AV_PIX_FMT_YUV422P, AV_PIX_FMT_YUV444P,
                    AV_PIX_FMT_YUV420P10LE, AV_PIX_FMT_GRAY8};
            int[] widths = {1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 33, 63, 65, 167, 168, 169, 315, 316, 317, 359, 360, 361, 853, 854, 855,
                    1079, 1080, 1081, 1279, 1280, 1919, 1920};
            for (boolean live : new boolean[]{true, false}) {
                String kind = live ? "live decoder " : "one-thread decoder ";
                try (VideoDecoder d = live ? new VideoDecoder(false, null) : VideoDecoder.oneThread(false, null)) {
                    AVFrame held = (AVFrame) field("frame").get(d);
                    for (int format : formats) {
                        for (int h : new int[]{16, 15}) {
                            for (int w : widths) {
                                AVFrame picture = picture(base, w, h, format);
                                try {
                                    av_frame_unref(held);
                                    assertEquals(0, av_frame_ref(held, picture));
                                    String bad = check(d, w, h, kind + av_get_pix_fmt_name(format).getString());
                                    checked++;
                                    if (bad != null) wrong.add(bad);
                                } finally {
                                    av_frame_free(picture);
                                }
                            }
                        }
                    }
                    av_frame_unref(held);
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " of " + checked + " conversions:\n" + String.join("\n", wrong));
    }

    /** A decoded picture scaled into a new one of this size and kind, the way FFmpeg makes its own (padded). */
    private static AVFrame picture(AVFrame base, int w, int h, int format) {
        AVFrame f = av_frame_alloc();
        f.format(format);
        f.width(w);
        f.height(h);
        assertEquals(0, av_frame_get_buffer(f, 0));
        SwsContext s = sws_getContext(base.width(), base.height(), base.format(), w, h, format, SWS_BICUBIC, null, null, (DoublePointer) null);
        assertNotNull(s);
        try {
            sws_scale(s, base.data(), base.linesize(), 0, base.height(), f.data(), f.linesize());
        } finally {
            sws_freeContext(s);
        }
        return f;
    }
}
