package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.javacpp.BytePointer;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * The STANAG 4609 stream (1.0.39): the video exactly as it came from the phone (copied, never encoded again) in an
 * MPEG-2 transport stream with a KLV data stream beside it (MISB ST 0601 with ST 0102 security, asynchronous KLV,
 * stream type 0x06 with the 'KLVA' registration FFmpeg writes), sent over UDP to one address - what ATAK / WinTAK,
 * QGIS's FMV plugin, Esri's FMV, VLC and the military ground stations play. Off the flight path: frames are handed
 * over on a queue and written on a thread of its own at the lowest priority, so the picture on screen never waits
 * for it; when it can't keep up, frames are left out of the stream (until the next key frame), never queued.
 * <p>
 * 1.0.65 (one stream per drone, each encoded on this PC - a page, an alert's pictures, a clip or the video by turns):
 * new parameter sets at the same picture size and codec no longer end the transport stream - they go in front of the
 * next key frame and the time stamps run on, so a player sees no restart when what is shown changes. A failure is
 * said once, not every 5 s.
 */
public final class StanagStream implements VideoPipeline.FrameTap, AutoCloseable {
    /** Metadata at most this often (ST 0601 asks for none faster; 10 Hz is what most UAS send). */
    public static final long KLV_EVERY_MS = 100;
    private static final int QUEUE = 90; // 3 s at 30 fps
    private static final long RETRY_MS = 5000;
    /** Tests: a shorter wait between two tries. */
    volatile long retryMs = RETRY_MS;

    private record Config(int width, int height, boolean hevc, byte[] codecConfig) {
        boolean sameAs(Config o) {
            return sameShape(o) && Arrays.equals(codecConfig, o.codecConfig);
        }

        /** The same picture size and codec: the transport stream can go on, whatever the parameter sets. */
        boolean sameShape(Config o) {
            return o != null && width == o.width && height == o.height && hevc == o.hevc;
        }
    }

    private record Frame(byte[] data, boolean keyFrame, long arrivedMs) {}

    private static final Object STOP = new Object();

    private final String url;
    /** "STANAG 4609 stream" or "STANAG 4609 MobileAlpha": how the log names it. */
    private final String label;
    private final long klvEveryMs;
    private final LongFunction<Klv.Frame> metadata;
    private final Consumer<String> log;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>(QUEUE);
    private final Thread thread;
    private volatile boolean dropping; // the queue overflowed: frames are left out until the next key frame
    private volatile long sentFrames, sentKlv, droppedFrames;

    /**
     * @param target "host:port" (UDP, unicast or multicast) or a full FFmpeg URL (udp://, rtp://, srt://, or a file
     *               path: the tests write a .ts file)
     * @param metadata the KLV for a frame that arrived at the given wall-clock time (never null)
     */
    public StanagStream(String target, LongFunction<Klv.Frame> metadata, Consumer<String> log) {
        this(null, target, metadata, log, KLV_EVERY_MS);
    }

    /**
     * @param name the drone this stream is of (the log names it), or null
     * @param klvEveryMs metadata at most this often (a still page: once a second is plenty)
     */
    public StanagStream(String name, String target, LongFunction<Klv.Frame> metadata, Consumer<String> log, long klvEveryMs) {
        this.url = url(target);
        this.label = name == null || name.isEmpty() ? "STANAG 4609 stream" : "STANAG 4609 " + name;
        this.klvEveryMs = Math.max(KLV_EVERY_MS, klvEveryMs);
        this.metadata = metadata;
        this.log = log;
        thread = new Thread(this::run, name == null || name.isEmpty() ? "stanagStream" : "stanagStream-" + name);
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** "host:port" -> udp://host:port with whole TS packets per datagram (7 x 188 bytes); multicast with a few hops. */
    static String url(String target) {
        String t = target.trim();
        if (t.contains("://") || t.endsWith(".ts")) return t;
        int colon = t.lastIndexOf(':');
        String host = colon > 0 ? t.substring(0, colon) : t;
        boolean multicast = host.matches("2(2[4-9]|3[0-9])\\..*");
        return "udp://" + t + "?pkt_size=1316" + (multicast ? "&ttl=8" : "");
    }

    public String url() {
        return url;
    }

    public long sentFrames() {
        return sentFrames;
    }

    public long sentKlv() {
        return sentKlv;
    }

    public long droppedFrames() {
        return droppedFrames;
    }

    @Override
    public void onConfig(int width, int height, boolean hevc, byte[] codecConfig) {
        offer(new Config(width, height, hevc, codecConfig));
    }

    @Override
    public void onFrame(byte[] data, boolean keyFrame, long arrivedMs) {
        if (dropping && !keyFrame) {
            droppedFrames++;
            return;
        }
        dropping = false;
        if (!offer(new Frame(data, keyFrame, arrivedMs))) {
            dropping = true;
            droppedFrames++;
        }
    }

    private boolean offer(Object item) {
        return queue.offer(item);
    }

    private void run() {
        Muxer mux = null;
        Config config = null;
        long failedAt = -RETRY_MS;
        boolean told = false, failSaid = false;
        boolean needKey = false; // new parameter sets in a running stream: from their key frame on
        try {
            while (true) {
                Object item = queue.take();
                if (item == STOP) break;
                if (item instanceof Config c) {
                    if (mux != null && !c.sameShape(config)) {
                        mux.close(); // a new picture size or codec: a new stream from its key frame
                        mux = null;
                    } else if (mux != null && !c.sameAs(config)) {
                        needKey = true; // 1.0.65: the same shape - the stream goes on, the new sets ahead of the next key frame
                    }
                    config = c;
                    continue;
                }
                Frame f = (Frame) item;
                if (needKey) {
                    if (!f.keyFrame()) continue;
                    needKey = false;
                }
                if (mux == null) {
                    if (config == null || !f.keyFrame()) continue;
                    long now = System.currentTimeMillis();
                    if (now - failedAt < retryMs) continue;
                    try {
                        mux = new Muxer(url, config);
                        log.accept(label + ": sending to " + url + (told ? " again." : "."));
                        told = true;
                        failSaid = false;
                    } catch (IOException | RuntimeException e) {
                        failedAt = now;
                        if (!failSaid) log.accept(label + ": " + e.getMessage() + " - trying every 5 s.");
                        failSaid = true;
                        continue;
                    }
                }
                try {
                    if (f.arrivedMs() - mux.lastKlvMs >= klvEveryMs) {
                        Klv.Frame k = metadata.apply(f.arrivedMs());
                        if (k != null) {
                            mux.writeKlv(Klv.encode(k), f.arrivedMs());
                            sentKlv++;
                        }
                    }
                    mux.writeVideo(withConfig(f.data(), f.keyFrame(), config.hevc(), config.codecConfig()), f.keyFrame(), f.arrivedMs());
                    sentFrames++;
                } catch (IOException | RuntimeException e) {
                    if (!failSaid) log.accept(label + " stopped: " + e.getMessage() + " - trying every 5 s.");
                    failSaid = true;
                    mux.close();
                    mux = null;
                    failedAt = System.currentTimeMillis();
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (mux != null) mux.close();
        }
    }

    /**
     * A key frame with the parameter sets in front, when it comes without them: a player that joins the stream late
     * can start at any key frame (the phone sends them once, apart).
     */
    static byte[] withConfig(byte[] frame, boolean keyFrame, boolean hevc, byte[] config) {
        if (!keyFrame || config == null || config.length == 0 || hasParameterSets(frame, hevc)) return frame;
        byte[] out = new byte[config.length + frame.length];
        System.arraycopy(config, 0, out, 0, config.length);
        System.arraycopy(frame, 0, out, config.length, frame.length);
        return out;
    }

    static boolean hasParameterSets(byte[] d, boolean hevc) {
        for (int i = 0; i + 3 < d.length && i < 512; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                int type = hevc ? (d[i + 3] >> 1) & 0x3f : d[i + 3] & 0x1f;
                if (hevc ? type == 33 : type == 7) return true; // SPS
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (!queue.offer(STOP)) { // full (it can't keep up): what is in it is not worth the wait
            queue.clear();
            queue.offer(STOP);
        }
        try {
            thread.join(2000);
        } catch (InterruptedException ignored) {
        }
    }

    /** One transport stream: the video (stream 0) and the KLV (stream 1), every packet sent as it is written. */
    private static final class Muxer {
        private final AVFormatContext oc;
        private final AVPacket pkt = av_packet_alloc();
        private final AVStream video, data;
        private long firstMs = -1, lastVideo = -1, lastData = -1;
        long lastKlvMs = Long.MIN_VALUE / 2;

        Muxer(String url, Config c) throws IOException {
            AVFormatContext ctx = new AVFormatContext(null);
            check(avformat_alloc_output_context2(ctx, null, "mpegts", url), "make the stream");
            oc = ctx;
            try {
                video = avformat_new_stream(oc, null);
                data = avformat_new_stream(oc, null);
                if (video == null || data == null) throw new IOException("no stream");
                AVCodecParameters vp = video.codecpar();
                vp.codec_type(AVMEDIA_TYPE_VIDEO);
                vp.codec_id(c.hevc() ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264);
                vp.width(c.width());
                vp.height(c.height());
                if (c.codecConfig() != null && c.codecConfig().length > 0) {
                    BytePointer extra = new BytePointer(av_mallocz(c.codecConfig().length + AV_INPUT_BUFFER_PADDING_SIZE));
                    extra.put(c.codecConfig());
                    vp.extradata(extra); // freed with the context
                    vp.extradata_size(c.codecConfig().length);
                }
                AVCodecParameters dp = data.codecpar();
                dp.codec_type(AVMEDIA_TYPE_DATA);
                dp.codec_id(AV_CODEC_ID_SMPTE_KLV);
                video.time_base(av_make_q(1, 90_000));
                data.time_base(av_make_q(1, 90_000));
                AVDictionary md = new AVDictionary(null);
                av_dict_set(md, "service_provider", "DRAUPNE Link", 0);
                av_dict_set(md, "service_name", "DRAUPNE Link UAS", 0);
                oc.metadata(md);
                AVIOContext pb = new AVIOContext(null);
                check(avio_open(pb, url, AVIO_FLAG_WRITE), "open " + url);
                oc.pb(pb);
                AVDictionary opts = new AVDictionary(null);
                av_dict_set(opts, "mpegts_flags", "resend_headers", 0); // PAT / PMT before every key frame: late joiners start there
                av_dict_set(opts, "flush_packets", "1", 0); // out at once, not when a buffer fills
                try {
                    check(avformat_write_header(oc, opts), "start the stream");
                } finally {
                    av_dict_free(opts);
                }
            } catch (IOException | RuntimeException e) {
                if (oc.pb() != null && !oc.pb().isNull()) avio_closep(oc.pb());
                avformat_free_context(oc);
                av_packet_free(pkt);
                throw e;
            }
        }

        private long ticks(long arrivedMs) {
            if (firstMs < 0) firstMs = arrivedMs;
            return (arrivedMs - firstMs) * 90;
        }

        void writeVideo(byte[] frame, boolean keyFrame, long arrivedMs) throws IOException {
            long ts = Math.max(ticks(arrivedMs), lastVideo + 1);
            lastVideo = ts;
            write(frame, ts, 0, keyFrame ? AV_PKT_FLAG_KEY : 0);
        }

        void writeKlv(byte[] klv, long arrivedMs) throws IOException {
            long ts = Math.max(ticks(arrivedMs), lastData + 1);
            lastData = ts;
            lastKlvMs = arrivedMs;
            write(klv, ts, 1, AV_PKT_FLAG_KEY);
        }

        private void write(byte[] bytes, long ts, int stream, int flags) throws IOException {
            check(av_new_packet(pkt, bytes.length), "make a packet");
            pkt.data().put(bytes);
            pkt.pts(ts);
            pkt.dts(ts);
            pkt.flags(flags);
            pkt.stream_index(stream);
            int r = av_write_frame(oc, pkt); // not interleaved: the KLV is sparse, waiting for it would hold the video back
            av_packet_unref(pkt);
            check(r, "send");
        }

        void close() {
            try {
                av_write_trailer(oc);
            } catch (RuntimeException ignored) {
            }
            avio_closep(oc.pb());
            avformat_free_context(oc);
            av_packet_free(pkt);
        }

        private static void check(int result, String what) throws IOException {
            if (result < 0) {
                BytePointer msg = new BytePointer(256);
                av_strerror(result, msg, 256);
                throw new IOException("could not " + what + " (" + msg.getString().trim() + ")");
            }
        }
    }
}
