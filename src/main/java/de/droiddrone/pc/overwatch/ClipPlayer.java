package de.droiddrone.pc.overwatch;

import de.droiddrone.pc.video.VideoDecoder;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIndexEntry;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.javacpp.PointerPointer;

import java.io.IOException;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * Plays an overwatch clip in the map window (1.0.47): the phone's MP4 or the preview's MKV, read with the FFmpeg the
 * app ships and decoded in software on the caller's thread ({@link VideoDecoder#oneThread}) - the map window's player
 * thread, at the lowest priority, never the live video's. The picture only: the clip's sound stays in the file (VLC,
 * the Films app). Not thread-safe: one thread.
 */
public final class ClipPlayer implements AutoCloseable {
    private final AVFormatContext fmt;
    private final AVPacket pkt = av_packet_alloc();
    private final int stream;
    private final AVRational timeBase;
    private final VideoDecoder decoder;
    private final long durationMs;
    private final int width, height;
    private boolean ended;

    public ClipPlayer(Path file) throws IOException {
        AVFormatContext ctx = new AVFormatContext(null);
        if (avformat_open_input(ctx, file.toString(), null, (AVDictionary) null) < 0) {
            av_packet_free(pkt);
            throw new IOException("Can't open " + file.getFileName());
        }
        fmt = ctx;
        try {
            if (avformat_find_stream_info(fmt, (PointerPointer<?>) null) < 0) throw new IOException("Can't read " + file.getFileName());
            int found = -1;
            for (int i = 0; i < fmt.nb_streams(); i++) {
                if (fmt.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                    found = i;
                    break;
                }
            }
            if (found < 0) throw new IOException(file.getFileName() + " has no picture");
            stream = found;
            AVStream st = fmt.streams(stream);
            AVCodecParameters par = st.codecpar();
            boolean hevc = par.codec_id() == AV_CODEC_ID_HEVC;
            if (!hevc && par.codec_id() != AV_CODEC_ID_H264) throw new IOException(file.getFileName() + ": not H.264 or H.265");
            byte[] extra = new byte[Math.max(0, par.extradata_size())];
            if (extra.length > 0) par.extradata().get(extra);
            decoder = VideoDecoder.oneThread(hevc, extra);
            timeBase = st.time_base();
            width = par.width();
            height = par.height();
            long d = fmt.duration() > 0 ? fmt.duration() / 1000 : st.duration() > 0 ? toMs(st.duration()) : 0;
            durationMs = Math.max(0, d);
        } catch (IOException | RuntimeException e) {
            close();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    public long durationMs() {
        return durationMs;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * The next picture into dst (0xRRGGBB, w x h).
     * @return its time from the clip's start in ms, or -1 at the end
     */
    public long next(int[] dst, int w, int h) {
        while (!ended) {
            if (av_read_frame(fmt, pkt) < 0) {
                ended = true;
                break;
            }
            try {
                if (pkt.stream_index() != stream || pkt.size() <= 0) continue;
                byte[] data = new byte[pkt.size()];
                pkt.data().get(data);
                long ts = pkt.pts() != AV_NOPTS_VALUE ? pkt.pts() : pkt.dts();
                long ms = ts == AV_NOPTS_VALUE ? 0 : toMs(ts) - startMs();
                readMs = ms;
                if (decoder.decode(data, data.length) && skipUntil <= ms) {
                    decoder.convert(dst, w, h);
                    return Math.max(0, ms);
                }
            } finally {
                av_packet_unref(pkt);
            }
        }
        return -1;
    }

    private long skipUntil = Long.MIN_VALUE;
    /** The time of the picture read last, ms from the clip's first moment; before the first, none. */
    private long readMs = Long.MIN_VALUE;

    /**
     * For a player that shows fewer pictures than the clip has - playing faster than real time, or behind its clock:
     * the pictures before ms are decoded (the ones after them are built on them) but not turned into a picture to
     * show, which is the part that can be spared; the next one {@link #next} gives is the first at or after ms.
     * Reading goes on from where it is.
     */
    public void skipTo(long ms) {
        skipUntil = ms;
    }

    /**
     * For a player behind its clock: as {@link #skipTo}, and when a key frame lies between the picture read last and
     * ms the reading goes on from that key frame - the pictures before it are not even decoded. Without one, or in a
     * file that doesn't list its key frames, it reads on from where it is: a seek would only decode the same pictures
     * once more.
     * @return whether it went on from a later key frame
     */
    public boolean catchUp(long ms) {
        skipUntil = ms;
        AVStream st = fmt.streams(stream);
        long ts = av_rescale_q(Math.max(0, ms) + startMs(), av_make_q(1, 1000), timeBase);
        int i = av_index_search_timestamp(st, ts, AVSEEK_FLAG_BACKWARD);
        AVIndexEntry key = i < 0 ? null : avformat_index_get_entry(st, i);
        if (key == null || key.isNull() || toMs(key.timestamp()) - startMs() <= readMs) return false;
        if (av_seek_frame(fmt, stream, ts, AVSEEK_FLAG_BACKWARD) < 0) return false;
        decoder.flush();
        ended = false;
        return true;
    }

    /** To ms (from the clip's first moment): back to the key frame before it; the pictures up to ms are decoded unseen. */
    public void seek(long ms) {
        long target = Math.max(0, ms) + startMs();
        long ts = av_rescale_q(target, av_make_q(1, 1000), timeBase);
        av_seek_frame(fmt, stream, ts, AVSEEK_FLAG_BACKWARD);
        decoder.flush();
        ended = false;
        skipUntil = Math.max(0, ms) - 40; // the picture at (or just before) the place asked for is the first shown
    }

    private long startMs() {
        long st = fmt.streams(stream).start_time();
        return st == AV_NOPTS_VALUE ? 0 : toMs(st);
    }

    private long toMs(long ts) {
        return av_rescale_q(ts, timeBase, av_make_q(1, 1000));
    }

    @Override
    public void close() {
        if (decoder != null) decoder.close();
        av_packet_free(pkt);
        AVFormatContext f = fmt;
        if (f != null) avformat_close_input(f);
    }
}
