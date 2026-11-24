package de.droiddrone.bench.fuzz;

import de.droiddrone.bench.fuzz.FuzzInput.Target;
import de.droiddrone.bench.fuzz.FuzzInput.Unit;
import de.droiddrone.pc.video.StreamRecorder;
import de.droiddrone.pc.video.TestStreams;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.PointerPointer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * The decoder fuzz's seeds, made on this PC (nothing downloaded): H.264 from libopenh264 the way the tests make it
 * ({@link TestStreams}) and with its other tools (CABAC, slices, sizes up to 720p); H.265 from an encoder of the PC's
 * own when FFmpeg finds one (Media Foundation, Quick Sync, NVENC - none on a PC without); clips of them written by the
 * app's own recorder ({@link StreamRecorder}, MKV) and remuxed by FFmpeg into MP4 and MPEG-TS. A folder of more
 * (-PseedDir=): earlier inputs (.dfz) as they are, recordings (.mkv .mp4 .ts .mov) cut to their first frames.
 * Runs in the child only: it is native code.
 */
final class SeedMaker {
    private static final int EXTRA_PACKETS = 40, EXTRA_FILES = 24;

    private SeedMaker() {
    }

    private record Encoded(String name, boolean hevc, int width, int height, TestStreams.Stream stream) {
    }

    /**
     * Writes the seeds into dir as NN-name.dfz (a clip also as its file, to look at).
     *
     * @param extra a folder of more seeds, or null
     * @param pcEncoders whether to try the PC's own H.265 encoders (off: the same seeds on every PC)
     * @return a line for each seed made or skipped
     */
    static List<String> make(Path dir, Path extra, boolean pcEncoders) throws IOException {
        Files.createDirectories(dir);
        List<String> said = new ArrayList<>();
        List<Encoded> made = new ArrayList<>();
        made.add(new Encoded("h264-176x144-solid", false, 176, 144, TestStreams.h264(176, 144, 8, 4)));
        made.add(new Encoded("h264-320x240-textured", false, 320, 240, TestStreams.h264(320, 240, 12, 6, true)));
        try {
            made.add(new Encoded("h264-1280x720-textured", false, 1280, 720, TestStreams.h264(1280, 720, 2, 2, true)));
        } catch (RuntimeException e) {
            said.add("skipped h264-1280x720: " + e.getMessage());
        }
        tryEncode(made, said, "h264-640x360-slices", false, "libopenh264", 640, 360, 6, 3, AV_PIX_FMT_YUV420P, c -> c.slices(4), Map.of());
        tryEncode(made, said, "h264-320x180-cabac", false, "libopenh264", 320, 180, 8, 4, AV_PIX_FMT_YUV420P,
                c -> c.profile(100 /* High */), Map.of("coder", "cabac"));
        if (pcEncoders) {
            // the phone sends H.265 unless told otherwise: an encoder of this PC's, the first that works
            boolean hevc = false;
            for (String[] e : new String[][]{{"hevc_mf", ""}, {"hevc_mf", "hw_encoding"}, {"hevc_qsv", ""}, {"hevc_nvenc", ""}}) {
                Map<String, String> opts = e[1].isEmpty() ? Map.of() : Map.of(e[1], "1");
                String tag = e[0] + (e[1].isEmpty() ? "" : "-hw");
                if (tryEncode(made, said, "hevc-320x240-" + tag, true, e[0], 320, 240, 10, 5, AV_PIX_FMT_NV12, c -> { }, opts)) {
                    tryEncode(made, said, "hevc-640x360-" + tag, true, e[0], 640, 360, 6, 3, AV_PIX_FMT_NV12, c -> { }, opts);
                    hevc = true;
                    break;
                }
            }
            if (!hevc) said.add("no H.265 encoder on this PC: H.265 is reached by mutation only (or -PseedDir= with H.265 recordings)");
        }
        int n = 0;
        for (Encoded e : made) {
            List<Unit> units = new ArrayList<>();
            units.add(new Unit(e.hevc ? FuzzInput.CONFIG_HEVC : FuzzInput.CONFIG_H264, e.stream.codecConfig()));
            for (TestStreams.Packet p : e.stream.packets()) units.add(new Unit(p.keyFrame() ? FuzzInput.KEY_FRAME : FuzzInput.FRAME, p.data()));
            said.add(save(dir, n++, e.name, new FuzzInput(Target.LIVE, 0, units)));
        }
        // clips: the small ones, written by the app's recorder and remuxed
        for (Encoded e : made) {
            if ((long) e.width * e.height > 640 * 360) continue;
            Path mkv = dir.resolve(String.format(Locale.ROOT, "clip-%s.mkv", e.name));
            try (StreamRecorder r = new StreamRecorder(mkv, e.hevc, e.width, e.height, e.stream.codecConfig())) {
                long t = 0;
                for (TestStreams.Packet p : e.stream.packets()) r.write(p.data(), p.keyFrame(), t += 33);
            }
            said.add(save(dir, n++, e.name + "-mkv", clip(FuzzInput.FILE_MKV, mkv)));
            for (String[] f : new String[][]{{"mp4", "mp4"}, {"mpegts", "ts"}}) {
                Path out = dir.resolve(String.format(Locale.ROOT, "clip-%s.%s", e.name, f[1]));
                try {
                    remux(mkv, out, f[0], Integer.MAX_VALUE);
                    said.add(save(dir, n++, e.name + "-" + f[1], clip(f[1].equals("mp4") ? FuzzInput.FILE_MP4 : FuzzInput.FILE_TS, out)));
                } catch (IOException | RuntimeException x) {
                    said.add("skipped " + e.name + " as " + f[1] + ": " + x.getMessage());
                }
            }
        }
        if (extra != null) n = extra(dir, extra, n, said);
        return said;
    }

    private static FuzzInput clip(int kind, Path file) throws IOException {
        return new FuzzInput(Target.CLIP, 0, List.of(new Unit(kind, Files.readAllBytes(file))));
    }

    private static String save(Path dir, int n, String name, FuzzInput in) throws IOException {
        Path f = dir.resolve(String.format(Locale.ROOT, "%02d-%s.dfz", n, name.replaceAll("[^A-Za-z0-9_.-]", "_")));
        in.write(f);
        return "seed " + f.getFileName() + ": " + in.describe();
    }

    /** The seeds made before (and a folder's): every .dfz of the folder, by name. */
    static List<FuzzInput> load(Path dir) throws IOException {
        List<FuzzInput> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".dfz")).sorted().toList()) out.add(FuzzInput.read(f));
        }
        return out;
    }

    private static int extra(Path dir, Path extra, int n, List<String> said) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(extra)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        int taken = 0;
        for (Path f : files) {
            if (taken >= EXTRA_FILES) {
                said.add("more than " + EXTRA_FILES + " files in " + extra + ": the rest left out");
                break;
            }
            String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
            try {
                if (name.endsWith(".dfz")) {
                    said.add(save(dir, n++, "extra-" + name.replace(".dfz", ""), FuzzInput.read(f)));
                    taken++;
                } else if (name.matches(".*\\.(mkv|mp4|ts|mov|m4v)")) {
                    Path cut = dir.resolve("clip-extra-" + taken + ".mkv");
                    List<Unit> stream = remux(f, cut, "matroska", EXTRA_PACKETS);
                    said.add(save(dir, n++, "extra-" + taken + "-mkv", clip(FuzzInput.FILE_MKV, cut)));
                    if (!stream.isEmpty()) said.add(save(dir, n++, "extra-" + taken + "-stream", new FuzzInput(Target.LIVE, 0, stream)));
                    else said.add("  (" + f.getFileName() + ": its frames are not Annex B - a clip seed only)");
                    taken++;
                }
            } catch (IOException | RuntimeException e) {
                said.add("skipped " + f.getFileName() + ": " + e.getMessage());
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------- encoding

    private static boolean tryEncode(List<Encoded> made, List<String> said, String name, boolean hevc, String encoder, int w, int h, int frames, int gop,
                                     int pixFmt, Consumer<AVCodecContext> setup, Map<String, String> options) {
        try {
            made.add(new Encoded(name, hevc, w, h, encode(hevc, encoder, w, h, frames, gop, pixFmt, setup, options)));
            return true;
        } catch (RuntimeException e) {
            said.add("skipped " + name + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Moving stripes and noise through an encoder; the result as the phone sends it: the parameter sets as the config
     * (from the encoder's header, else taken out of the first key frame), the frames without them.
     */
    static TestStreams.Stream encode(boolean hevc, String encoder, int w, int h, int frames, int gop, int pixFmt,
                                     Consumer<AVCodecContext> setup, Map<String, String> options) {
        AVCodec codec = avcodec_find_encoder_by_name(encoder);
        if (codec == null || codec.isNull()) throw new IllegalStateException(encoder + " is not in this FFmpeg");
        AVCodecContext c = avcodec_alloc_context3(codec);
        AVFrame f = av_frame_alloc();
        AVPacket pkt = av_packet_alloc();
        try {
            c.width(w);
            c.height(h);
            c.pix_fmt(pixFmt);
            c.time_base(av_make_q(1, 30));
            c.framerate(av_make_q(30, 1));
            c.bit_rate(2_000_000);
            c.gop_size(gop);
            c.max_b_frames(0);
            c.flags(c.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);
            setup.accept(c);
            AVDictionary opts = new AVDictionary(null);
            for (Map.Entry<String, String> e : options.entrySet()) av_dict_set(opts, e.getKey(), e.getValue(), 0);
            int r = avcodec_open2(c, codec, opts);
            av_dict_free(opts);
            if (r < 0) throw new IllegalStateException(encoder + " can't open here (" + r + ")");
            byte[] config = new byte[Math.max(0, c.extradata_size())];
            if (config.length > 0) c.extradata().get(config);
            f.format(pixFmt);
            f.width(w);
            f.height(h);
            if (av_frame_get_buffer(f, 32) < 0) throw new IllegalStateException("no frame buffer");
            List<TestStreams.Packet> packets = new ArrayList<>();
            Random random = new Random(7);
            for (int i = 0; i <= frames; i++) {
                if (i < frames) {
                    av_frame_make_writable(f);
                    draw(f, pixFmt, w, h, i, random);
                    f.pts(i);
                }
                int s = avcodec_send_frame(c, i < frames ? f : null);
                if (s < 0 && s != AVERROR_EOF()) throw new IllegalStateException(encoder + ": a frame refused (" + s + ")");
                while (avcodec_receive_packet(c, pkt) == 0) {
                    byte[] data = new byte[pkt.size()];
                    pkt.data().get(data);
                    packets.add(new TestStreams.Packet(data, (pkt.flags() & AV_PKT_FLAG_KEY) != 0));
                    av_packet_unref(pkt);
                }
            }
            if (packets.isEmpty()) throw new IllegalStateException(encoder + " gave no frames");
            return phoneStyle(hevc, config, packets);
        } finally {
            av_packet_free(pkt);
            av_frame_free(f);
            avcodec_free_context(c);
        }
    }

    private static void draw(AVFrame f, int pixFmt, int w, int h, int frame, Random random) {
        int stride = f.linesize(0);
        byte[] y = new byte[stride * h];
        for (int row = 0; row < h; row++) {
            for (int x = 0; x < w; x++) y[row * stride + x] = (byte) Math.max(16, Math.min(235, ((x + row + frame * 6) * 3 & 0xff) / 2 + 60 + random.nextInt(24)));
        }
        f.data(0).put(y);
        if (pixFmt == AV_PIX_FMT_NV12) {
            int cs = f.linesize(1);
            byte[] uv = new byte[cs * (h / 2)];
            for (int row = 0; row < h / 2; row++) {
                for (int x = 0; x < w; x += 2) {
                    uv[row * cs + x] = (byte) (110 + (row + frame) % 40);
                    uv[row * cs + x + 1] = (byte) (150 - (x / 2) % 40);
                }
            }
            f.data(1).put(uv);
        } else {
            for (int p = 1; p <= 2; p++) {
                int cs = f.linesize(p);
                byte[] c = new byte[cs * (h / 2)];
                for (int row = 0; row < h / 2; row++) for (int x = 0; x < w / 2; x++) c[row * cs + x] = (byte) (p == 1 ? 100 + (row + frame) % 50 : 160 - x % 50);
                f.data(p).put(c);
            }
        }
    }

    private static boolean isParameterSet(boolean hevc, byte[] d, int at) {
        if (at >= d.length) return false;
        if (!hevc) {
            int type = d[at] & 0x1f;
            return type == 7 || type == 8;
        }
        int type = (d[at] >> 1) & 0x3f;
        return type == 32 || type == 33 || type == 34;
    }

    private static TestStreams.Stream phoneStyle(boolean hevc, byte[] config, List<TestStreams.Packet> packets) {
        boolean annexB = config.length >= 4 && config[0] == 0 && config[1] == 0 && (config[2] == 1 || config[2] == 0 && config[3] == 1);
        if (!annexB) { // no usable header: the first key frame's parameter sets
            List<byte[]> sets = new ArrayList<>();
            byte[] first = packets.get(0).data();
            for (int[] n : Mutator.nals(first)) if (isParameterSet(hevc, first, n[0])) sets.add(Arrays.copyOfRange(first, n[0], n[1]));
            config = Mutator.join(sets);
        }
        List<TestStreams.Packet> out = new ArrayList<>();
        for (TestStreams.Packet p : packets) {
            List<byte[]> keep = new ArrayList<>();
            for (int[] n : Mutator.nals(p.data())) if (!isParameterSet(hevc, p.data(), n[0])) keep.add(Arrays.copyOfRange(p.data(), n[0], n[1]));
            if (!keep.isEmpty()) out.add(new TestStreams.Packet(Mutator.join(keep), p.keyFrame()));
        }
        if (config.length == 0) throw new IllegalStateException("no parameter sets");
        return new TestStreams.Stream(config, out);
    }

    // ------------------------------------------------------------------------------------------------- files

    /**
     * The first video stream of in, at most maxPackets of its frames, into out as the given container.
     *
     * @return the frames read as a stream (config, frames) when they are Annex B, else empty
     */
    static List<Unit> remux(Path in, Path out, String format, int maxPackets) throws IOException {
        AVFormatContext ic = new AVFormatContext(null);
        if (avformat_open_input(ic, in.toString(), null, (AVDictionary) null) < 0) throw new IOException("can't open " + in.getFileName());
        AVFormatContext oc = null;
        AVPacket pkt = av_packet_alloc();
        List<Unit> stream = new ArrayList<>();
        try {
            if (avformat_find_stream_info(ic, (PointerPointer<?>) null) < 0) throw new IOException("can't read " + in.getFileName());
            int video = -1;
            for (int i = 0; i < ic.nb_streams() && video < 0; i++) if (ic.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) video = i;
            if (video < 0) throw new IOException(in.getFileName() + " has no video");
            AVStream is = ic.streams(video);
            AVCodecParameters par = is.codecpar();
            boolean hevc = par.codec_id() == AV_CODEC_ID_HEVC;
            byte[] extra = new byte[Math.max(0, par.extradata_size())];
            if (extra.length > 0) par.extradata().get(extra);
            boolean annexB = (hevc || par.codec_id() == AV_CODEC_ID_H264) && extra.length >= 4 && extra[0] == 0 && extra[1] == 0;
            if (annexB) stream.add(new Unit(hevc ? FuzzInput.CONFIG_HEVC : FuzzInput.CONFIG_H264, extra));
            oc = new AVFormatContext(null);
            if (avformat_alloc_output_context2(oc, null, format, out.toString()) < 0) throw new IOException("no " + format + " muxer");
            AVStream os = avformat_new_stream(oc, null);
            avcodec_parameters_copy(os.codecpar(), par);
            os.codecpar().codec_tag(0);
            os.time_base(is.time_base());
            AVIOContext pb = new AVIOContext(null);
            if (avio_open(pb, out.toString(), AVIO_FLAG_WRITE) < 0) throw new IOException("can't write " + out.getFileName());
            oc.pb(pb);
            if (avformat_write_header(oc, (AVDictionary) null) < 0) throw new IOException(format + ": no header");
            int n = 0;
            while (n < maxPackets && av_read_frame(ic, pkt) >= 0) {
                try {
                    if (pkt.stream_index() != video) continue;
                    n++;
                    if (annexB) {
                        byte[] d = new byte[pkt.size()];
                        pkt.data().get(d);
                        stream.add(new Unit((pkt.flags() & AV_PKT_FLAG_KEY) != 0 ? FuzzInput.KEY_FRAME : FuzzInput.FRAME, d));
                    }
                    av_packet_rescale_ts(pkt, is.time_base(), os.time_base());
                    pkt.stream_index(0);
                    pkt.pos(-1);
                    if (av_interleaved_write_frame(oc, pkt) < 0) throw new IOException(format + ": a frame refused");
                } finally {
                    av_packet_unref(pkt);
                }
            }
            av_write_trailer(oc);
            return stream;
        } finally {
            av_packet_free(pkt);
            if (oc != null) {
                if (oc.pb() != null && !oc.pb().isNull()) avio_closep(oc.pb());
                avformat_free_context(oc);
            }
            avformat_close_input(ic);
        }
    }
}
