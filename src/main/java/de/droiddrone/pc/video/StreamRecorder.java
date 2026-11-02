package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.javacpp.BytePointer;

import java.io.IOException;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * Writes the video exactly as it came from the phone into an MKV file: the frames are copied (remuxed), not encoded
 * again - no quality lost, next to no CPU. MKV rather than MP4 because a file cut short (a crash, the PC switched off)
 * still plays. Starts at the first key frame; timestamps are arrival times. Not thread-safe: one writer thread.
 */
public final class StreamRecorder implements AutoCloseable {
    private final AVFormatContext oc;
    private final AVPacket pkt = av_packet_alloc();
    private AVStream stream;
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
        this.file = file;
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
