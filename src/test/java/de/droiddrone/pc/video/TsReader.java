package de.droiddrone.pc.video;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * Reads a transport stream a test wrote (a STANAG 4609 stream into a .ts file) back with FFmpeg: its video packets -
 * each decoded - and its KLV packets, for the tests of other packages too.
 */
public final class TsReader {
    /** One video packet: its time in the stream (ms), whether it is a key frame, whether a picture came out of it. */
    public record Picture(long ptsMs, boolean key, boolean decoded, int[] pixels) {
        /** 0xRRGGBB at x, y of the w x h picture asked for; -1 when this one was not kept. */
        public int at(int x, int y, int w) {
            return pixels == null ? -1 : pixels[y * w + x] & 0xffffff;
        }
    }

    /** One metadata packet. */
    public record Meta(long ptsMs, Map<Integer, byte[]> items) {
        public String designation() {
            byte[] b = items == null ? null : items.get(Klv.TAG_DESIGNATION);
            return b == null ? null : new String(b, StandardCharsets.US_ASCII);
        }

        public String mission() {
            byte[] b = items == null ? null : items.get(Klv.TAG_MISSION);
            return b == null ? null : new String(b, StandardCharsets.US_ASCII);
        }

        public double sensorLat() {
            byte[] b = items == null ? null : items.get(Klv.TAG_SENSOR_LAT);
            return b == null ? Double.NaN : Klv.decodeSigned(b, 90);
        }

        public double sensorLon() {
            byte[] b = items == null ? null : items.get(Klv.TAG_SENSOR_LON);
            return b == null ? Double.NaN : Klv.decodeSigned(b, 180);
        }

        public double heading() {
            byte[] b = items == null ? null : items.get(Klv.TAG_HEADING);
            return b == null ? Double.NaN : Klv.decodeUnsigned(b, 360);
        }

        public double hfov() {
            byte[] b = items == null ? null : items.get(Klv.TAG_HFOV);
            return b == null ? Double.NaN : Klv.decodeUnsigned(b, 180);
        }

        /** Corner i (0 upper left, clockwise) as {lat, lon}, or null when the packet has none. */
        public double[] corner(int i) {
            byte[] lat = items == null ? null : items.get(Klv.TAG_CORNER + 2 * i), lon = items == null ? null : items.get(Klv.TAG_CORNER + 2 * i + 1);
            return lat == null || lon == null ? null : new double[]{Klv.decodeSigned(lat, 90), Klv.decodeSigned(lon, 180)};
        }
    }

    public final List<Picture> pictures = new ArrayList<>();
    public final List<Meta> meta = new ArrayList<>();
    public int width, height;
    public boolean h264, klvStream;
    public String format;

    /**
     * @param keepEvery every n-th decoded picture's pixels are kept (at w x h), 0 for none
     */
    public static TsReader read(Path file, int keepEvery, int w, int h) {
        TsReader out = new TsReader();
        AVFormatContext in = new AVFormatContext(null);
        if (avformat_open_input(in, file.toString(), null, (AVDictionary) null) != 0) throw new IllegalStateException("can't open " + file);
        if (avformat_find_stream_info(in, (AVDictionary) null) < 0) throw new IllegalStateException("no streams in " + file);
        out.format = in.iformat().name().getString();
        int video = -1, klv = -1;
        for (int i = 0; i < in.nb_streams(); i++) {
            var par = in.streams(i).codecpar();
            if (par.codec_type() == AVMEDIA_TYPE_VIDEO && video < 0) {
                video = i;
                out.h264 = par.codec_id() == AV_CODEC_ID_H264;
                out.width = par.width();
                out.height = par.height();
            }
            if (par.codec_type() == AVMEDIA_TYPE_DATA && par.codec_id() == AV_CODEC_ID_SMPTE_KLV) {
                klv = i;
                out.klvStream = true;
            }
        }
        VideoDecoder decoder = VideoDecoder.oneThread(false, new byte[0]);
        AVPacket pkt = av_packet_alloc();
        int n = 0;
        try {
            while (av_read_frame(in, pkt) >= 0) {
                int index = pkt.stream_index();
                byte[] b = new byte[pkt.size()];
                pkt.data().get(b);
                long ms = pkt.pts() == AV_NOPTS_VALUE ? -1 : av_rescale_q(pkt.pts(), in.streams(index).time_base(), av_make_q(1, 1000));
                if (index == video) {
                    boolean got = decoder.decode(b, b.length);
                    int[] px = null;
                    if (got && keepEvery > 0 && n++ % keepEvery == 0) {
                        px = new int[w * h];
                        decoder.convert(px, w, h);
                    }
                    out.pictures.add(new Picture(ms, (pkt.flags() & AV_PKT_FLAG_KEY) != 0, got, px));
                } else if (index == klv) {
                    out.meta.add(new Meta(ms, Klv.decode(b)));
                }
                av_packet_unref(pkt);
            }
        } finally {
            av_packet_free(pkt);
            decoder.close();
            avformat_close_input(in);
        }
        return out;
    }

    /** The kept pictures, in order. */
    public List<Picture> kept() {
        List<Picture> k = new ArrayList<>();
        for (Picture p : pictures) if (p.pixels() != null) k.add(p);
        return k;
    }

    public long decoded() {
        return pictures.stream().filter(Picture::decoded).count();
    }

    /** The pictures' times never go back: one stream, never begun again. */
    public boolean timesRise() {
        long last = Long.MIN_VALUE;
        for (Picture p : pictures) {
            if (p.ptsMs() < last) return false;
            last = p.ptsMs();
        }
        return true;
    }
}
