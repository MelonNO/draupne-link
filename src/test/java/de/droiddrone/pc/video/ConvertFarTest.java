package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.LogCallback;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F-092: a picture far bigger than the view in one direction - as a damaged stream can decode to, 128 x 131072 - is
 * converted without ending the app. FFmpeg makes such a scale in two steps of its own, and that way, with the picture's
 * colour matrix set, ended the process. Both checks run in a JVM of their own, so that neither FFmpeg's log, which
 * they listen to, nor a crash reaches this one.
 */
class ConvertFarTest {
    /** The fuzz's picture (finding 001 of 2026-11-24, seed 1) to its 320 x 180, a live view's 1536 x 864, the STANAG feed's 2 x 720. */
    private static final int[][] FUZZ = {{128, 131072, 320, 180}, {128, 131072, 1536, 864}, {128, 131072, 2, 720}};

    @Test
    void aPictureFarBiggerThanTheViewIsConvertedWithoutEndingTheApp() throws Exception {
        Result r = child("far");
        assertEquals(0, r.exit, "the conversion ended the JVM:\n" + r.out);
        assertTrue(r.out.contains("converted " + FUZZ.length), r.out);
    }

    @Test
    void ffmpegScalesEveryPictureForTheAppInOneStep() throws Exception {
        Result r = child("grid");
        assertEquals(0, r.exit, r.out);
        assertTrue(r.out.contains("SELF-CHECK two steps seen"), "FFmpeg's word for two steps not seen where they are made - this check sees nothing:\n" + r.out);
        assertTrue(r.out.contains("GRID "), r.out);
        List<String> split = r.out.lines().filter(l -> l.startsWith("TWO STEPS")).toList();
        assertTrue(split.isEmpty(), split.size() + " conversions in two steps:\n" + String.join("\n", split));
    }

    private record Result(int exit, String out) {}

    private static Result child(String mode) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("convert-far");
        Path out = dir.resolve("out.txt");
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString(),
                "-Xmx256m", "-XX:-CreateCoredumpOnCrash", "-XX:-UsePerfData", "-XX:ErrorFile=" + dir.resolve("hs_err_pid%p.log"),
                "-Djava.awt.headless=true"));
        String cache = System.getProperty("org.bytedeco.javacpp.cachedir");
        if (cache != null) cmd.add("-Dorg.bytedeco.javacpp.cachedir=" + cache);
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Child.class.getName(), mode));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("the child did not finish in 120 s");
        }
        String text = Files.readString(out, StandardCharsets.UTF_8);
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                if (f.getFileName().toString().startsWith("hs_err")) text += "\n" + String.join("\n", Files.readString(f).lines().limit(40).toList());
                Files.delete(f);
            }
        }
        Files.delete(dir);
        return new Result(p.exitValue(), text);
    }

    /**
     * The child: "far" converts the fuzz's picture as the live view did; "grid" sets the scaler up as convert() does
     * for many picture kinds and sizes and listens to what FFmpeg says.
     */
    public static final class Child {
        private static final List<String> heard = Collections.synchronizedList(new ArrayList<>());
        private static LogCallback listener; // held: FFmpeg calls it

        public static void main(String[] args) throws Exception {
            if (args[0].equals("far")) far();
            else grid();
            System.out.flush();
            System.exit(0);
        }

        private static Field frameField() throws NoSuchFieldException {
            Field f = VideoDecoder.class.getDeclaredField("frame");
            f.setAccessible(true);
            return f;
        }

        /** A picture of this size and kind, grey, as FFmpeg makes its own; untagged, so BT.709 from 720 rows up. */
        private static AVFrame picture(int w, int h, int format) {
            AVFrame f = av_frame_alloc();
            f.format(format);
            f.width(w);
            f.height(h);
            if (av_frame_get_buffer(f, 0) < 0) throw new IllegalStateException("no picture " + w + "x" + h);
            for (int p = 0; p < 4 && f.data(p) != null && !f.data(p).isNull(); p++) {
                int rows = p == 0 || format == AV_PIX_FMT_YUV444P ? h : (h + 1) / 2;
                org.bytedeco.javacpp.Pointer.memset(f.data(p), 128, (long) f.linesize(p) * rows);
            }
            return f;
        }

        private static void far() throws Exception {
            int n = 0;
            for (int[] c : FUZZ) {
                try (VideoDecoder d = new VideoDecoder(false, null)) { // the live view's: slice threads
                    AVFrame held = (AVFrame) frameField().get(d);
                    AVFrame p = picture(c[0], c[1], AV_PIX_FMT_YUV420P);
                    av_frame_ref(held, p);
                    av_frame_free(p);
                    d.convert(new int[c[2] * c[3]], c[2], c[3]);
                    System.out.println("converted " + c[0] + "x" + c[1] + " to " + c[2] + "x" + c[3]);
                    n++;
                }
            }
            System.out.println("converted " + n);
        }

        private static boolean twoSteps() {
            synchronized (heard) {
                return heard.stream().anyMatch(l -> l.contains("initFilter failed") || l.contains("intermediate"));
            }
        }

        private static void grid() throws Exception {
            new VideoDecoder(false, null).close(); // its own logging set-up first
            listener = new LogCallback() {
                @Override
                public void call(int level, BytePointer msg) {
                    heard.add(msg.getString());
                }
            };
            setLogCallback(listener);
            av_log_set_level(AV_LOG_DEBUG);
            // the listener hears FFmpeg say so where it does take two steps
            SwsContext s = sws_getContext(128, 131072, AV_PIX_FMT_YUV420P, 320, 180, AV_PIX_FMT_BGRA, SWS_BICUBIC, null, null, (org.bytedeco.javacpp.DoublePointer) null);
            if (s != null && !s.isNull()) sws_freeContext(s);
            if (twoSteps()) System.out.println("SELF-CHECK two steps seen");
            heard.clear();
            int[][] pictures = {{128, 131072}, {131072, 128}, {1920, 1080}, {1080, 1920}, {4096, 16}, {16, 4096}, {16384, 64}, {2, 2}};
            int[][] views = {{320, 180}, {1536, 864}, {2, 720}, {720, 2}, {2, 2}, {16, 9}, {64, 36}, {1920, 1080}, {33, 1080}, {1920, 33}};
            int[] formats = {AV_PIX_FMT_YUV420P, AV_PIX_FMT_NV12, AV_PIX_FMT_YUV444P, AV_PIX_FMT_GRAY8, AV_PIX_FMT_YUV420P10LE};
            java.lang.reflect.Method converter = VideoDecoder.class.getDeclaredMethod("converter", AVFrame.class, int.class, int.class);
            converter.setAccessible(true);
            int n = 0;
            for (boolean live : new boolean[]{false, true}) {
                try (VideoDecoder d = live ? new VideoDecoder(false, null) : VideoDecoder.oneThread(false, null)) {
                    AVFrame held = (AVFrame) frameField().get(d);
                    for (int format : live ? new int[]{AV_PIX_FMT_YUV420P} : formats) {
                        for (int[] pic : pictures) {
                            AVFrame p = picture(pic[0], pic[1], format);
                            try {
                                av_frame_unref(held);
                                av_frame_ref(held, p);
                                for (int[] v : views) {
                                    heard.clear();
                                    String what = (live ? "live " : "one-thread ") + av_get_pix_fmt_name(format).getString() + " " + pic[0] + "x" + pic[1] + " to " + v[0] + "x" + v[1];
                                    try { // the scaler made and set up as for the conversion, nothing scaled: no crash here
                                        converter.invoke(d, held, v[0], v[1]);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        System.out.println("refused " + what + ": " + e.getCause());
                                    }
                                    n++;
                                    if (twoSteps()) System.out.println("TWO STEPS " + what);
                                }
                            } finally {
                                av_frame_unref(held);
                                av_frame_free(p);
                            }
                        }
                    }
                }
            }
            av_log_set_level(AV_LOG_FATAL);
            System.out.println("GRID " + n + " conversions");
        }
    }
}
