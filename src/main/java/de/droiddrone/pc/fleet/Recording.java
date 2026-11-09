package de.droiddrone.pc.fleet;

import de.droiddrone.pc.overwatch.ClipPlayer;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.PointerPointer;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * An old flight looped as a virtual drone's camera (the panel's PICK FILE): one of DRAUPNE Link's own recordings
 * (flight-....mkv, or the -osd.mkv with the OSD burned in). The picture is decoded (H.264 or H.265, ClipPlayer's
 * decoder) and encoded again at the size, frame rate and bitrate the PC asks for - a remux could not follow the PC's
 * tiers. The sound track (the phone's AAC frames, copied as they came) is sent on as it is, when it is 48 kHz mono AAC.
 * The flight CSV beside the recording (pts_ms, lat, lon, alt_m, roll, pitch, heading, armed ...), when there is one and
 * the pilot asks, plays as the drone's telemetry. Everything loops; the times follow the wall clock.
 */
final class Recording implements AutoCloseable {
    /** Decoded pictures are kept at most this big; the encoder's size is scaled from them. */
    static final int MAX_WIDTH = 1280, MAX_HEIGHT = 720;

    private final Path file;
    private final ClipPlayer player;
    private final int decodeW, decodeH;
    private final BufferedImage frame;
    private long base = -1;
    private long shownMs = -1;
    private final AudioTrack audio;
    private final List<double[]> track;
    private volatile boolean playTrack;
    private long trackBase = -1;

    Recording(Path file) throws IOException {
        this.file = file;
        player = new ClipPlayer(file);
        double s = Math.min(1, Math.min(MAX_WIDTH / (double) Math.max(1, player.width()), MAX_HEIGHT / (double) Math.max(1, player.height())));
        decodeW = Math.max(2, (int) Math.round(player.width() * s) / 2 * 2);
        decodeH = Math.max(2, (int) Math.round(player.height() * s) / 2 * 2);
        frame = new BufferedImage(decodeW, decodeH, BufferedImage.TYPE_INT_RGB);
        AudioTrack a = null;
        try {
            a = AudioTrack.open(file);
        } catch (IOException | RuntimeException ignored) {
            // the picture alone
        }
        audio = a;
        track = readTrack(csvOf(file));
    }

    Path file() {
        return file;
    }

    /** The flight CSV beside a recording: flight-X.mkv and flight-X-osd.mkv have flight-X.csv. */
    static Path csvOf(Path mkv) {
        String n = mkv.getFileName().toString().replaceFirst("(-osd)?\\.mkv$", "");
        return mkv.resolveSibling(n + ".csv");
    }

    boolean hasTrack() {
        return !track.isEmpty();
    }

    /** The CSV's track as the drone's telemetry (when there is one). */
    void setPlayTrack(boolean on) {
        playTrack = on && !track.isEmpty();
        trackBase = -1;
    }

    boolean playsTrack() {
        return playTrack;
    }

    /**
     * The picture at nowMs, scaled into dst (all of it). Decodes on up to now; at the end it starts again. 1.0.53:
     * straight into dst - a new picture of dst's size each frame and a copy cost 8 MB and 1 - 2.5 ms a 1080p frame.
     * @return false when nothing could be decoded (the caller draws its own picture)
     */
    synchronized boolean picture(BufferedImage dst, long nowMs) {
        if (base < 0) base = nowMs;
        long t = nowMs - base;
        int[] px = Scene.pixels(frame);
        int guard = 0;
        while (shownMs < t && guard++ < 120) {
            long ms = player.next(px, decodeW, decodeH);
            if (ms < 0) { // the end: again from the start
                player.seek(0);
                base = nowMs;
                t = 0;
                shownMs = -1;
                if (guard > 2) break;
                continue;
            }
            shownMs = ms;
        }
        if (shownMs < 0) return false;
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(frame, 0, 0, dst.getWidth(), dst.getHeight(), null);
        } finally {
            g.dispose();
        }
        return true;
    }

    boolean hasAudio() {
        return audio != null;
    }

    byte[] audioConfig() {
        return audio == null ? new byte[0] : audio.config;
    }

    /** The sound's AAC frames due by nowMs (none when it has no sound). */
    List<byte[]> audioFrames(long nowMs) {
        return audio == null ? List.of() : audio.until(nowMs);
    }

    /**
     * The CSV's state at nowMs laid over the flight model (when the track plays): position, height, attitude, armed.
     * @return true when the track set it (the model is not stepped then)
     */
    synchronized boolean track(long nowMs, FlightModel fc) {
        if (!playTrack || track.isEmpty()) return false;
        if (trackBase < 0) trackBase = nowMs;
        double end = track.get(track.size() - 1)[0];
        double t = end <= 0 ? 0 : (nowMs - trackBase) % (long) Math.max(1, end);
        int lo = 0, hi = track.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (track.get(mid)[0] <= t) lo = mid;
            else hi = mid - 1;
        }
        double[] r = track.get(lo);
        if (Double.isNaN(r[1]) || Double.isNaN(r[2])) return false;
        fc.overlay(r[1], r[2], nz(r[3]), nz(r[4]), nz(r[5]), nz(r[6]), r[7] > 0.5);
        return true;
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    /** Rows of {pts_ms, lat, lon, alt_m, roll, pitch, heading, armed}; empty when there is no CSV or it is unreadable. */
    static List<double[]> readTrack(Path csv) {
        List<double[]> rows = new ArrayList<>();
        if (!Files.isRegularFile(csv)) return rows;
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = in.readLine();
            if (header == null) return rows;
            List<String> cols = List.of(header.toLowerCase(Locale.ROOT).split(","));
            int[] at = {cols.indexOf("pts_ms"), cols.indexOf("lat"), cols.indexOf("lon"), cols.indexOf("alt_m"), cols.indexOf("roll"),
                    cols.indexOf("pitch"), cols.indexOf("heading"), cols.indexOf("armed")};
            if (at[0] < 0 || at[1] < 0 || at[2] < 0) return rows;
            String line;
            while ((line = in.readLine()) != null && rows.size() < 500_000) {
                String[] p = line.split(",", -1);
                double[] r = new double[8];
                for (int i = 0; i < 8; i++) r[i] = at[i] >= 0 && at[i] < p.length ? num(p[at[i]]) : Double.NaN;
                if (!Double.isNaN(r[0])) rows.add(r);
            }
        } catch (IOException | RuntimeException e) {
            rows.clear();
        }
        return rows;
    }

    private static double num(String s) {
        try {
            return s.isBlank() ? Double.NaN : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @Override
    public synchronized void close() {
        player.close();
        if (audio != null) audio.close();
    }

    /** The recording's AAC track, read packet by packet and handed out by its timestamps. */
    private static final class AudioTrack implements AutoCloseable {
        private final AVFormatContext fmt;
        private final AVPacket pkt = av_packet_alloc();
        private final int stream;
        private final AVRational timeBase;
        final byte[] config;
        private long base = -1;
        private long firstPts = Long.MIN_VALUE;
        private byte[] pending;
        private long pendingMs;

        private AudioTrack(AVFormatContext fmt, int stream, byte[] config) {
            this.fmt = fmt;
            this.stream = stream;
            this.timeBase = fmt.streams(stream).time_base();
            this.config = config;
        }

        /** The file's sound, when it is AAC at 48 kHz mono (what the phone sends); else an IOException. */
        static AudioTrack open(Path file) throws IOException {
            AVFormatContext ctx = new AVFormatContext(null);
            if (avformat_open_input(ctx, file.toString(), null, (AVDictionary) null) < 0) throw new IOException("can't open");
            try {
                if (avformat_find_stream_info(ctx, (PointerPointer<?>) null) < 0) throw new IOException("can't read");
                for (int i = 0; i < ctx.nb_streams(); i++) {
                    AVCodecParameters par = ctx.streams(i).codecpar();
                    if (par.codec_type() != AVMEDIA_TYPE_AUDIO || par.codec_id() != AV_CODEC_ID_AAC) continue;
                    if (par.sample_rate() != AacEncoder.SAMPLE_RATE || par.ch_layout().nb_channels() != 1) continue;
                    byte[] asc = new byte[Math.max(0, par.extradata_size())];
                    if (asc.length == 0) continue;
                    par.extradata().get(asc);
                    return new AudioTrack(ctx, i, asc);
                }
                throw new IOException("no 48 kHz mono AAC track");
            } catch (IOException | RuntimeException e) {
                avformat_close_input(ctx);
                throw e;
            }
        }

        synchronized List<byte[]> until(long nowMs) {
            if (base < 0) base = nowMs;
            long t = nowMs - base;
            List<byte[]> out = new ArrayList<>();
            int guard = 0;
            while (guard++ < 64) {
                if (pending == null && !readOne()) {
                    av_seek_frame(fmt, stream, 0, AVSEEK_FLAG_BACKWARD);
                    base = nowMs;
                    firstPts = Long.MIN_VALUE;
                    break;
                }
                if (pendingMs > t) break;
                out.add(pending);
                pending = null;
            }
            return out;
        }

        private boolean readOne() {
            while (av_read_frame(fmt, pkt) >= 0) {
                try {
                    if (pkt.stream_index() != stream || pkt.size() <= 0) continue;
                    byte[] data = new byte[pkt.size()];
                    pkt.data().get(data);
                    long ts = pkt.pts() != AV_NOPTS_VALUE ? pkt.pts() : pkt.dts();
                    long ms = ts == AV_NOPTS_VALUE ? 0 : av_rescale_q(ts, timeBase, av_make_q(1, 1000));
                    if (firstPts == Long.MIN_VALUE) firstPts = ms;
                    pending = data;
                    pendingMs = ms - firstPts;
                    return true;
                } finally {
                    av_packet_unref(pkt);
                }
            }
            return false;
        }

        @Override
        public synchronized void close() {
            av_packet_free(pkt);
            avformat_close_input(fmt);
        }
    }
}
