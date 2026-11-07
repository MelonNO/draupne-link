package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.javacpp.BytePointer;
import de.droiddrone.pc.audio.AudioConfig;
import de.droiddrone.pc.audio.AudioPts;

import java.io.IOException;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * Writes the video exactly as it came from the phone into an MKV file: the frames are copied (remuxed), not encoded
 * again - no quality lost, next to no CPU. MKV rather than MP4 because a file cut short (a crash, the PC switched off)
 * still plays. Starts at the first key frame; timestamps are arrival times. Not thread-safe: one writer thread.
 * 1.0.45: the phone's live audio as a second track (AAC, copied as it came), when the stream may have sound.
 */
public final class StreamRecorder implements AutoCloseable {
    private final AVFormatContext oc;
    private final AVPacket pkt = av_packet_alloc();
    private AVStream stream;
    /** The sound's track and the config it was declared with; null = a file without sound. */
    private AVStream audioStream;
    private AudioConfig audio;
    private AudioPts audioPts;
    private long audioFrames;
    private boolean started; // the first key frame is in
    private long firstMs;
    private long lastMs = -1;
    private long frames;
    private final Path file;
    /** The OSD sidecar (see {@link #writeOsd}), opened with the first row. */
    private java.io.BufferedWriter osd;
    private long osdRows;

    /** @param codecConfig the stream's VPS/SPS/PPS as the phone sends them (Annex B) */
    public StreamRecorder(Path file, boolean hevc, int width, int height, byte[] codecConfig) throws IOException {
        this(file, hevc, width, height, codecConfig, null);
    }

    /** @param audio the live audio's track (1.0.45), or null for a file without sound */
    public StreamRecorder(Path file, boolean hevc, int width, int height, byte[] codecConfig, AudioConfig audio) throws IOException {
        this.file = file;
        this.audio = audio;
        AVFormatContext ctx = new AVFormatContext(null);
        check(avformat_alloc_output_context2(ctx, null, "matroska", file.toString()), "create " + file);
        oc = ctx;
        try {
            open(file, hevc, width, height, codecConfig);
        } catch (IOException | RuntimeException e) {
            if (oc.pb() != null && !oc.pb().isNull()) avio_closep(oc.pb());
            avformat_free_context(oc);
            av_packet_free(pkt);
            throw e;
        }
    }

    private void open(Path file, boolean hevc, int width, int height, byte[] codecConfig) throws IOException {
        stream = avformat_new_stream(oc, null);
        if (stream == null) throw new IOException("Recording: no stream");
        AVCodecParameters par = stream.codecpar();
        par.codec_type(AVMEDIA_TYPE_VIDEO);
        par.codec_id(hevc ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264);
        par.width(width);
        par.height(height);
        if (codecConfig != null && codecConfig.length > 0) {
            BytePointer extra = new BytePointer(av_mallocz(codecConfig.length + AV_INPUT_BUFFER_PADDING_SIZE));
            extra.put(codecConfig);
            par.extradata(extra); // freed with the context
            par.extradata_size(codecConfig.length);
        }
        stream.time_base(av_make_q(1, 1000));
        if (audio != null) {
            audioStream = addAacStream(oc, audio);
            if (audioStream == null) throw new IOException("Recording: no audio track");
            audioPts = new AudioPts(audio.frameMs());
        }
        AVIOContext pb = new AVIOContext(null);
        check(avio_open(pb, file.toString(), AVIO_FLAG_WRITE), "open " + file);
        oc.pb(pb);
        check(avformat_write_header(oc, (AVDictionary) null), "write the header");
    }

    /** One whole frame (Annex B); frames before the first key frame are skipped (they can't be decoded). */
    public void write(byte[] data, boolean keyFrame, long arrivedMs) throws IOException {
        if (!started) {
            if (!keyFrame) return;
            started = true;
            firstMs = arrivedMs;
        }
        long ms = Math.max(arrivedMs - firstMs, lastMs + 1); // strictly rising, as muxers want
        lastMs = ms;
        check(av_new_packet(pkt, data.length), "packet");
        pkt.data().put(data);
        pkt.pts(ms);
        pkt.dts(ms);
        pkt.flags(keyFrame ? AV_PKT_FLAG_KEY : 0);
        pkt.stream_index(0);
        av_packet_rescale_ts(pkt, av_make_q(1, 1000), stream.time_base());
        int r = av_interleaved_write_frame(oc, pkt); // takes the packet's data
        av_packet_unref(pkt);
        check(r, "write a frame");
        frames++;
    }

    public long frames() {
        return frames;
    }

    /**
     * An AAC track (1.0.45) on oc, before its header is written: the phone's frames go in as they came. Its packets'
     * timestamps are ms. The muxer holds packets back to interleave the tracks, for as long as one track has none -
     * the sound stops while the drone is armed - so it is told to wait at most 1 s: the file on disk at most that much
     * behind, not up to 10 s (FFmpeg's default).
     */
    static AVStream addAacStream(AVFormatContext oc, AudioConfig audio) {
        AVStream st = avformat_new_stream(oc, null);
        if (st == null) return null;
        AVCodecParameters par = st.codecpar();
        par.codec_type(AVMEDIA_TYPE_AUDIO);
        par.codec_id(AV_CODEC_ID_AAC);
        par.sample_rate(audio.sampleRate());
        av_channel_layout_default(par.ch_layout(), audio.channels());
        par.frame_size(AudioConfig.SAMPLES_PER_FRAME);
        byte[] asc = audio.asc();
        if (asc != null && asc.length > 0) {
            BytePointer extra = new BytePointer(av_mallocz(asc.length + AV_INPUT_BUFFER_PADDING_SIZE));
            extra.put(asc);
            par.extradata(extra); // freed with the context
            par.extradata_size(asc.length);
        }
        st.time_base(av_make_q(1, 1000));
        oc.max_interleave_delta(1_000_000);
        return st;
    }

    /** One AAC frame into an audio track at ptsMs (the caller keeps it strictly rising). */
    static int writeAac(AVFormatContext oc, AVPacket pkt, AVStream st, byte[] data, long ptsMs) {
        int r = av_new_packet(pkt, data.length);
        if (r < 0) return r;
        pkt.data().put(data);
        pkt.pts(ptsMs);
        pkt.dts(ptsMs);
        pkt.flags(AV_PKT_FLAG_KEY);
        pkt.stream_index(st.index());
        av_packet_rescale_ts(pkt, av_make_q(1, 1000), st.time_base());
        r = av_interleaved_write_frame(oc, pkt); // takes the packet's data
        av_packet_unref(pkt);
        return r;
    }

    /**
     * One frame of the live audio (1.0.45), stamped by counting frames from its arrival (AudioPts). Left out before
     * the first key frame (the file's start), and when it belongs to a stream other than the one the track was
     * declared with (the phone's encoder changed: the next file has it).
     * @return its timestamp in this file, ms; -1 when left out
     */
    public long writeAudio(byte[] data, AudioConfig config, long arrivedMs) throws IOException {
        if (!started || audioStream == null || !audio.sameAs(config)) return -1;
        long ms = audioPts.next(Math.max(0, arrivedMs - firstMs));
        if (ms < 0) return -1; // the sound ran ahead of the video: this frame left out (AudioPts)
        check(writeAac(oc, pkt, audioStream, data, ms), "write the sound");
        audioFrames++;
        return ms;
    }

    public long audioFrames() {
        return audioFrames;
    }

    /** The sound's track and its config, or null: a file without sound. */
    public AudioConfig audio() {
        return audioStream == null ? null : audio;
    }

    /**
     * The HUD's figures when a frame was shown: one row of the CSV next to the MKV, so a replay can draw the OSD.
     * Numbers unknown at the time are NaN / -1, the mode "".
     */
    public record OsdRow(long arrivedMs, int pingMs, double volts, double cellVolts, double amps, int mahDrawn, int sats, int fix,
                         double latDeg, double lonDeg, double speedKmh, int homeDistanceM, double altitudeM, double varioMs,
                         String mode, boolean armed, double rollDeg, double pitchDeg, int headingDeg) {}

    static final String OSD_HEADER = "pts_ms,ping_ms,volts,cell_volts,amps,mah,sats,fix,lat,lon,speed_kmh,home_m,alt_m,vario_ms,mode,armed,roll,pitch,heading";

    /**
     * One row for a frame shown; its pts is the same clock as the MKV's (ms from the first key frame). Rows before the
     * file started are left out, like the frames. Called on the writer thread.
     */
    public void writeOsd(OsdRow r) throws IOException {
        if (!started) return;
        if (osd == null) {
            String name = file.getFileName().toString().replaceFirst("\\.mkv$", "") + ".csv";
            osd = java.nio.file.Files.newBufferedWriter(file.resolveSibling(name), java.nio.charset.StandardCharsets.UTF_8);
            osd.write(OSD_HEADER);
            osd.newLine();
        }
        osd.write(String.format(java.util.Locale.ROOT, "%d,%d,%s,%s,%s,%d,%d,%d,%s,%s,%s,%d,%s,%s,%s,%d,%s,%s,%d",
                Math.max(0, r.arrivedMs() - firstMs), r.pingMs(), num(r.volts(), 2), num(r.cellVolts(), 2), num(r.amps(), 1), r.mahDrawn(),
                r.sats(), r.fix(), num(r.latDeg(), 6), num(r.lonDeg(), 6), num(r.speedKmh(), 1), r.homeDistanceM(), num(r.altitudeM(), 1),
                num(r.varioMs(), 1), r.mode() == null ? "" : r.mode().replace(',', ' '), r.armed() ? 1 : 0, num(r.rollDeg(), 1), num(r.pitchDeg(), 1), r.headingDeg()));
        osd.newLine();
        osdRows++;
    }

    private static String num(double v, int decimals) {
        return Double.isNaN(v) ? "" : String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
    }

    public long osdRows() {
        return osdRows;
    }

    @Override
    public void close() {
        if (osd != null) {
            try {
                osd.close();
            } catch (IOException ignored) {
            }
            osd = null;
        }
        av_write_trailer(oc);
        avio_closep(oc.pb());
        avformat_free_context(oc);
        av_packet_free(pkt);
    }

    private static void check(int result, String what) throws IOException {
        if (result < 0) {
            BytePointer msg = new BytePointer(256);
            av_strerror(result, msg, 256);
            throw new IOException("Recording: could not " + what + " (" + msg.getString().trim() + ")");
        }
    }
}
