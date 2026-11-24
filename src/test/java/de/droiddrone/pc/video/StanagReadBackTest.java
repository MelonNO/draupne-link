package de.droiddrone.pc.video;

import de.droiddrone.testing.Waits;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The STANAG 4609 stream read back the way a player reads it, none of it with the app's own KLV code: the transport
 * stream's tables parsed byte by byte (the KLV stream's type and its 'KLVA' registration, as MISB ST 1402 asks for
 * asynchronous metadata), the packets taken out by FFmpeg's own demuxer and the pictures decoded by FFmpeg's own H.264
 * decoder, and every KLV packet taken apart here by MISB ST 0601's own rules - the UAS Local Set key, the BER lengths,
 * each item's length as the standard gives it, the checksum, the time stamp first and rising, and the position back
 * within the standard's precision. Southern and western places, so the signs are tried too.
 */
class StanagReadBackTest {
    private static final byte[] UAS_LS_KEY = {0x06, 0x0E, 0x2B, 0x34, 0x02, 0x0B, 0x01, 0x01, 0x0E, 0x01, 0x03, 0x01, 0x01, 0x00, 0x00, 0x00};
    /** ST 0601's fixed item lengths for the tags a DRAUPNE packet may carry (strings and sets checked apart). */
    private static final Map<Integer, Integer> LENGTHS = Map.ofEntries(Map.entry(1, 2), Map.entry(2, 8), Map.entry(5, 2),
            Map.entry(6, 2), Map.entry(7, 2), Map.entry(13, 4), Map.entry(14, 4), Map.entry(15, 2), Map.entry(16, 2),
            Map.entry(17, 2), Map.entry(18, 4), Map.entry(19, 4), Map.entry(20, 4), Map.entry(21, 4), Map.entry(23, 4),
            Map.entry(24, 4), Map.entry(25, 2), Map.entry(26, 2), Map.entry(27, 2), Map.entry(28, 2), Map.entry(29, 2),
            Map.entry(30, 2), Map.entry(31, 2), Map.entry(32, 2), Map.entry(33, 2), Map.entry(56, 1), Map.entry(65, 1),
            Map.entry(82, 4), Map.entry(83, 4), Map.entry(84, 4), Map.entry(85, 4), Map.entry(86, 4), Map.entry(87, 4),
            Map.entry(88, 4), Map.entry(89, 4), Map.entry(90, 4), Map.entry(91, 4));
    private static final long T0_MS = 1_764_000_000_000L; // 24 Nov 2025: a real wall-clock time

    /** What the app was given for the frame that arrived at a time: a drone flying south-west over Chile. */
    private static Klv.Frame place(long ms) {
        double s = (ms - T0_MS) / 1000.0;
        double lat = -33.4489 - s * 1.1e-4, lon = -70.6693 - s * 1.3e-4, cLat = lat - 0.0012, cLon = lon + 0.0009;
        double[][] corners = {{cLat + 4e-4, cLon - 6e-4}, {cLat + 4e-4, cLon + 6e-4}, {cLat - 4e-4, cLon + 6e-4}, {cLat - 4e-4, cLon - 6e-4}};
        return new Klv.Frame(ms * 1000, "OP READBACK", "DRAUPNE", "EO FPV", (s * 37) % 360, -3.5, 4.25, 12,
                lat, lon, 512.3 + s * 3.1, 70, 42, 0, -20, 0, 230.4, cLat, cLon, 480.6, corners, "CHL");
    }

    /** One KLV packet taken apart by ST 0601's rules alone. */
    private record Packet(Map<Integer, byte[]> items) {}

    private static int[] ber(byte[] b, int at) {
        int first = b[at] & 0xff;
        if (first < 0x80) return new int[]{first, 1};
        int n = first & 0x7f, v = 0;
        assertTrue(n >= 1 && n <= 4, "a BER long form of 1 to 4 bytes");
        for (int i = 1; i <= n; i++) v = (v << 8) | (b[at + i] & 0xff);
        return new int[]{v, n + 1};
    }

    private static Packet takeApart(byte[] p) {
        assertArrayEquals(UAS_LS_KEY, Arrays.copyOf(p, 16), "the UAS Datalink Local Set key");
        int[] len = ber(p, 16);
        assertEquals(p.length, 16 + len[1] + len[0], "the BER length is the rest of the packet");
        if (len[0] < 128) assertEquals(1, len[1], "short form under 128");
        Map<Integer, byte[]> items = new LinkedHashMap<>();
        int at = 16 + len[1];
        while (at < p.length) {
            int tag = p[at] & 0xff;
            assertTrue(tag < 128, "every tag used fits one byte of BER-OID: " + tag);
            int[] n = ber(p, at + 1);
            int from = at + 1 + n[1];
            assertTrue(from + n[0] <= p.length, "item " + tag + " stays inside the packet");
            assertFalse(items.containsKey(tag), "tag " + tag + " once");
            items.put(tag, Arrays.copyOfRange(p, from, from + n[0]));
            at = from + n[0];
        }
        assertEquals(p.length, at, "the items fill the packet exactly");
        List<Integer> tags = new ArrayList<>(items.keySet());
        assertEquals(2, tags.get(0), "the Precision Time Stamp first");
        assertEquals(1, tags.get(tags.size() - 1), "the checksum last");
        // ST 0601's checksum: a 16-bit running sum of the packet as big-endian words, from the key's first byte up to
        // and including the checksum item's tag and length
        int sum = 0;
        for (int i = 0; i < p.length - 2; i++) sum += (i % 2 == 0 ? (p[i] & 0xff) << 8 : p[i] & 0xff);
        assertEquals(sum & 0xffff, ((p[p.length - 2] & 0xff) << 8) | (p[p.length - 1] & 0xff), "the checksum");
        for (Map.Entry<Integer, byte[]> e : items.entrySet()) {
            Integer want = LENGTHS.get(e.getKey());
            if (want != null) assertEquals(want, e.getValue().length, "tag " + e.getKey() + "'s length");
        }
        byte[] security = items.get(48);
        if (security != null) { // ST 0102's local set: its own items fill it, a classification of one byte first
            Map<Integer, byte[]> sec = new LinkedHashMap<>();
            for (int i = 0; i < security.length; ) {
                int[] n = ber(security, i + 1);
                sec.put(security[i] & 0xff, Arrays.copyOfRange(security, i + 1 + n[1], i + 1 + n[1] + n[0]));
                i += 1 + n[1] + n[0];
                assertTrue(i <= security.length, "the security set's items stay inside it");
            }
            assertEquals(1, sec.get(1).length);
            assertTrue(sec.get(1)[0] >= 1 && sec.get(1)[0] <= 5, "a classification ST 0102 knows");
            assertEquals(2, sec.get(22).length, "its version, two bytes");
        }
        for (int s : new int[]{3, 10, 11, 12}) {
            byte[] v = items.get(s);
            if (v == null) continue;
            assertTrue(v.length >= 1 && v.length <= 127, "string tag " + s);
            for (byte c : v) assertTrue(c >= 0x20 && c < 0x7f, "ISO 646 in tag " + s);
        }
        return new Packet(items);
    }

    private static long unsignedBe(byte[] v) {
        long x = 0;
        for (byte b : v) x = (x << 8) | (b & 0xff);
        return x;
    }

    private static long signedBe(byte[] v) {
        long x = unsignedBe(v);
        return (v[0] & 0x80) != 0 ? x - (1L << (8 * v.length)) : x;
    }

    /** The raw transport stream: its tables and each PES, in the order they start in the file, read here byte by byte. */
    private static final class Ts {
        int pmtPid = -1, pcrPid = -1;
        final Map<Integer, Integer> streamType = new LinkedHashMap<>();
        final Map<Integer, List<byte[]>> descriptors = new LinkedHashMap<>();
        /** Every PES in file order: its PID and its bytes from the start code on. */
        final List<Map.Entry<Integer, ByteArrayOutputStream>> pes = new ArrayList<>();
        boolean crcOk = true;

        static Ts read(byte[] b) {
            assertEquals(0, b.length % 188, "whole 188-byte packets");
            Ts ts = new Ts();
            Map<Integer, ByteArrayOutputStream> open = new LinkedHashMap<>();
            for (int at = 0; at < b.length; at += 188) {
                assertEquals(0x47, b[at] & 0xff, "the sync byte");
                boolean start = (b[at + 1] & 0x40) != 0;
                int pid = ((b[at + 1] & 0x1f) << 8) | (b[at + 2] & 0xff);
                int afc = (b[at + 3] >> 4) & 3;
                int p = at + 4;
                if (afc == 2 || afc == 3) p += 1 + (b[p] & 0xff);
                if (afc == 2 || p >= at + 188) continue;
                byte[] payload = Arrays.copyOfRange(b, p, at + 188);
                if (pid == 0 && start) ts.pat(payload);
                else if (pid == ts.pmtPid && start) ts.pmt(payload);
                else if (ts.streamType.containsKey(pid)) {
                    if (start) {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        open.put(pid, o);
                        ts.pes.add(Map.entry(pid, o));
                    }
                    ByteArrayOutputStream o = open.get(pid);
                    if (o != null) o.writeBytes(payload);
                }
            }
            return ts;
        }

        List<byte[]> of(int pid) {
            List<byte[]> out = new ArrayList<>();
            for (Map.Entry<Integer, ByteArrayOutputStream> e : pes) if (e.getKey() == pid) out.add(e.getValue().toByteArray());
            return out;
        }

        /** A PES header's PTS in 90 kHz ticks, or -1 when it has none. */
        static long pts(byte[] p) {
            if ((p[7] & 0x80) == 0) return -1;
            return ((long) ((p[9] >> 1) & 7) << 30) | ((p[10] & 0xffL) << 22) | (((p[11] & 0xff) >> 1) << 15)
                    | ((p[12] & 0xffL) << 7) | ((p[13] & 0xff) >> 1);
        }

        /** A PES's payload (after its header). */
        static byte[] body(byte[] p) {
            return Arrays.copyOfRange(p, 9 + (p[8] & 0xff), p.length);
        }

        private byte[] section(byte[] payload) {
            int p = 1 + (payload[0] & 0xff); // pointer field
            int length = ((payload[p + 1] & 0x0f) << 8) | (payload[p + 2] & 0xff);
            byte[] s = Arrays.copyOfRange(payload, p, p + 3 + length);
            crcOk &= crc32(s) == 0; // MPEG-2's CRC over the section with its own CRC is 0
            return s;
        }

        void pat(byte[] payload) {
            byte[] s = section(payload);
            for (int i = 8; i + 4 <= s.length - 4; i += 4) {
                int program = ((s[i] & 0xff) << 8) | (s[i + 1] & 0xff);
                if (program != 0) pmtPid = ((s[i + 2] & 0x1f) << 8) | (s[i + 3] & 0xff);
            }
        }

        void pmt(byte[] payload) {
            byte[] s = section(payload);
            assertEquals(0x02, s[0] & 0xff, "a program map table");
            pcrPid = ((s[8] & 0x1f) << 8) | (s[9] & 0xff);
            int programInfo = ((s[10] & 0x0f) << 8) | (s[11] & 0xff);
            int i = 12 + programInfo;
            while (i < s.length - 4) {
                int type = s[i] & 0xff, pid = ((s[i + 1] & 0x1f) << 8) | (s[i + 2] & 0xff);
                int esInfo = ((s[i + 3] & 0x0f) << 8) | (s[i + 4] & 0xff);
                streamType.put(pid, type);
                List<byte[]> d = new ArrayList<>();
                for (int k = i + 5; k < i + 5 + esInfo; ) {
                    int n = s[k + 1] & 0xff;
                    d.add(Arrays.copyOfRange(s, k, k + 2 + n));
                    k += 2 + n;
                }
                descriptors.put(pid, d);
                i += 5 + esInfo;
            }
        }

        static int crc32(byte[] s) {
            int crc = 0xffffffff;
            for (byte x : s) {
                crc ^= (x & 0xff) << 24;
                for (int k = 0; k < 8; k++) crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04c11db7 : crc << 1;
            }
            return crc;
        }
    }

    private static Path write(Path dir, String name, Map<Long, Klv.Frame> sent, int frames) throws InterruptedException {
        TestStreams.Stream s = TestStreams.h264(320, 240, frames, 30);
        Path file = dir.resolve(name);
        StanagStream stream = new StanagStream(file.toString(), ms -> {
            Klv.Frame f = place(ms);
            sent.put(ms, f);
            return f;
        }, m -> {});
        stream.onConfig(320, 240, false, s.codecConfig());
        long ms = T0_MS;
        int handed = 0;
        for (TestStreams.Packet p : s.packets()) {
            stream.onFrame(p.data(), p.keyFrame(), ms);
            ms += 33;
            int n = ++handed;
            // the frames come far faster than a camera's here: never more than its queue's worth waiting
            Waits.await(() -> stream.sentFrames() >= n - 30, 10_000, "the stream's writer");
        }
        Waits.await(() -> stream.sentFrames() >= frames, 10_000, "every frame written");
        stream.close();
        assertEquals(frames, stream.sentFrames());
        assertEquals(0, stream.droppedFrames());
        return file;
    }

    /** What FFmpeg's demuxer and decoder make of a file: the pictures' times, the KLV packets and where each sits. */
    private record Demuxed(List<Long> videoPtsMs, List<byte[]> klv, List<Long> klvPts, List<Integer> klvBeforePicture,
                           int decoded, int width, int height, boolean klvCodec) {}

    private static Demuxed demux(Path file) {
        AVFormatContext in = new AVFormatContext(null);
        assertEquals(0, avformat_open_input(in, file.toString(), null, (AVDictionary) null));
        assertTrue(avformat_find_stream_info(in, (AVDictionary) null) >= 0);
        int video = -1, data = -1;
        boolean klvCodec = false;
        for (int i = 0; i < in.nb_streams(); i++) {
            var par = in.streams(i).codecpar();
            if (par.codec_type() == AVMEDIA_TYPE_VIDEO) video = i;
            if (par.codec_type() == AVMEDIA_TYPE_DATA) {
                data = i;
                klvCodec = par.codec_id() == AV_CODEC_ID_SMPTE_KLV;
            }
        }
        assertTrue(video >= 0 && data >= 0, "a video stream and a data stream");
        AVCodec codec = avcodec_find_decoder(in.streams(video).codecpar().codec_id());
        assertNotNull(codec);
        AVCodecContext dec = avcodec_alloc_context3(codec);
        assertTrue(avcodec_parameters_to_context(dec, in.streams(video).codecpar()) >= 0);
        assertEquals(0, avcodec_open2(dec, codec, (AVDictionary) null));
        AVPacket pkt = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        List<Long> videoPts = new ArrayList<>(), klvPts = new ArrayList<>();
        List<byte[]> klv = new ArrayList<>();
        List<Integer> before = new ArrayList<>();
        int decoded = 0, w = 0, h = 0;
        try {
            while (av_read_frame(in, pkt) >= 0) {
                int idx = pkt.stream_index();
                long ms = pkt.pts() == AV_NOPTS_VALUE ? Long.MIN_VALUE : av_rescale_q(pkt.pts(), in.streams(idx).time_base(), av_make_q(1, 1000));
                if (idx == video) {
                    videoPts.add(ms);
                    assertTrue(avcodec_send_packet(dec, pkt) >= 0);
                    while (avcodec_receive_frame(dec, frame) == 0) {
                        decoded++;
                        w = frame.width();
                        h = frame.height();
                    }
                } else if (idx == data) {
                    byte[] b = new byte[pkt.size()];
                    pkt.data().get(b);
                    klv.add(b);
                    klvPts.add(ms);
                    before.add(videoPts.size()); // the index of the next picture
                }
                av_packet_unref(pkt);
            }
            avcodec_send_packet(dec, null);
            while (avcodec_receive_frame(dec, frame) == 0) decoded++;
            return new Demuxed(videoPts, klv, klvPts, before, decoded, w, h, klvCodec);
        } finally {
            av_frame_free(frame);
            av_packet_free(pkt);
            avcodec_free_context(dec);
            avformat_close_input(in);
        }
    }

    @Test
    void aPlayerFindsTheKlvStreamByItsTypeAndRegistration(@TempDir Path dir) throws Exception {
        Map<Long, Klv.Frame> sent = new ConcurrentHashMap<>();
        Ts ts = Ts.read(Files.readAllBytes(write(dir, "tables.ts", sent, 60)));
        assertTrue(ts.crcOk, "the PAT's and PMT's CRC-32s");
        assertEquals(2, ts.streamType.size(), "two elementary streams: " + ts.streamType);
        int videoPid = -1, klvPid = -1;
        for (Map.Entry<Integer, Integer> e : ts.streamType.entrySet()) {
            if (e.getValue() == 0x1b) videoPid = e.getKey();
            if (e.getValue() == 0x06) klvPid = e.getKey();
        }
        assertTrue(videoPid > 0, "H.264 is stream type 0x1B: " + ts.streamType);
        assertTrue(klvPid > 0, "asynchronous KLV is stream type 0x06 (private PES): " + ts.streamType);
        assertEquals(videoPid, ts.pcrPid, "the clock rides on the video");
        boolean klva = false;
        for (byte[] d : ts.descriptors.get(klvPid)) {
            klva |= (d[0] & 0xff) == 0x05 && d.length >= 6 && new String(d, 2, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("KLVA");
        }
        assertTrue(klva, "the registration descriptor says KLVA");
        List<byte[]> pes = ts.of(klvPid);
        assertFalse(pes.isEmpty());
        for (byte[] p : pes) {
            assertEquals(0x000001, ((p[0] & 0xff) << 16) | ((p[1] & 0xff) << 8) | (p[2] & 0xff), "a PES start code");
            // FFmpeg 7.1's muxer marks it metadata_stream (0xFC); ST 1402 names private_stream_1 (0xBD) for
            // asynchronous KLV. Readers find the stream by the PMT's type and KLVA, as FFmpeg's demuxer does here
            int id = p[3] & 0xff;
            assertTrue(id == 0xfc || id == 0xbd, "a metadata or private stream: " + Integer.toHexString(id));
            assertTrue((p[6] & 0x04) != 0, "data_alignment_indicator: a KLV packet begins the PES");
            assertEquals(Ts.body(p).length, ((p[4] & 0xff) << 8 | (p[5] & 0xff)) - 3 - (p[8] & 0xff), "the PES length is the packet's");
            // the PES packet holds exactly one whole ST 0601 packet (the TS stuffing is in adaptation fields, not here)
            takeApart(Ts.body(p));
        }
        assertEquals(sent.size(), pes.size(), "one PES per KLV packet the app made");

        // each KLV packet sits right before the picture of its own instant, its PTS (when it has one) that picture's
        long firstVideo = -1, klvPts = -1;
        Long klvMs = null;
        int checked = 0;
        for (Map.Entry<Integer, ByteArrayOutputStream> e : ts.pes) {
            byte[] p = e.getValue().toByteArray();
            if (e.getKey() == klvPid) {
                klvMs = unsignedBe(takeApart(Ts.body(p)).items().get(2)) / 1000 - T0_MS;
                klvPts = Ts.pts(p);
            } else if (e.getKey() == videoPid) {
                long pts = Ts.pts(p);
                assertTrue(pts >= 0, "every picture has its PTS");
                if (firstVideo < 0) firstVideo = pts;
                if (klvMs != null) {
                    assertEquals(klvMs, (pts - firstVideo) / 90, "the KLV's time is the next picture's");
                    if (klvPts >= 0) assertEquals(pts, klvPts, "the KLV's PTS is its picture's");
                    checked++;
                }
                klvMs = null;
            }
        }
        assertEquals(pes.size(), checked);
    }

    @Test
    void ffmpegsOwnDemuxerAndDecoderGetEveryPictureAndEveryPacketBack(@TempDir Path dir) throws Exception {
        Map<Long, Klv.Frame> sent = new ConcurrentHashMap<>();
        int frames = 90;
        Demuxed d = demux(write(dir, "readback.ts", sent, frames));
        assertTrue(d.klvCodec(), "FFmpeg names the data stream SMPTE KLV");
        assertEquals(frames, d.videoPtsMs().size());
        assertEquals(frames, d.decoded(), "FFmpeg's own H.264 decoder gives every picture");
        assertEquals(320, d.width());
        assertEquals(240, d.height());
        assertEquals(sent.size(), d.klv().size(), "every KLV packet made is in the stream");
        assertTrue(d.klv().size() >= frames / 4, "about ten a second: " + d.klv().size());

        long lastTime = Long.MIN_VALUE;
        long firstVideo = d.videoPtsMs().get(0);
        for (int i = 0; i < d.klv().size(); i++) {
            Packet p = takeApart(d.klv().get(i));
            long micros = unsignedBe(p.items().get(2));
            assertTrue(micros > lastTime, "the time stamps rise");
            lastTime = micros;
            assertTrue(micros > 1_700_000_000_000_000L && micros < 4_102_444_800_000_000L, "microseconds of UTC since 1970: " + micros);
            Klv.Frame f = sent.get(micros / 1000);
            assertNotNull(f, "a time stamp the app was asked for: " + micros);

            double lat = signedBe(p.items().get(13)) * 180.0 / 4294967294.0;
            double lon = signedBe(p.items().get(14)) * 360.0 / 4294967294.0;
            double alt = unsignedBe(p.items().get(15)) * 19900.0 / 65535.0 - 900;
            double heading = unsignedBe(p.items().get(5)) * 360.0 / 65535.0;
            assertTrue(lat < 0 && lon < 0, "south and west keep their signs");
            assertEquals(f.sensorLat(), lat, 180.0 / 4294967294.0, "latitude within one step of ST 0601's mapping");
            assertEquals(f.sensorLon(), lon, 360.0 / 4294967294.0, "longitude within one step");
            assertEquals(f.sensorAltM(), alt, 19900.0 / 65535.0 / 2 + 1e-9, "altitude within half a step (0.15 m)");
            assertEquals(f.headingDeg(), heading, 360.0 / 65535.0 / 2 + 1e-9, "heading within half a step");
            assertEquals(f.pitchDeg(), signedBe(p.items().get(6)) * 40.0 / 65534.0, 40.0 / 65534.0, "pitch");
            assertEquals(f.rollDeg(), signedBe(p.items().get(7)) * 100.0 / 65534.0, 100.0 / 65534.0, "roll");
            assertEquals(f.relElevationDeg(), signedBe(p.items().get(19)) * 360.0 / 4294967294.0, 1e-6, "the camera's tilt");
            assertEquals(17, p.items().get(65)[0], "ST 0601.17");
            assertEquals(f.slantRangeM(), unsignedBe(p.items().get(21)) * 5_000_000.0 / 4294967295.0, 5e6 / 4294967295.0, "slant range");
            assertEquals(f.centreLat(), signedBe(p.items().get(23)) * 180.0 / 4294967294.0, 180.0 / 4294967294.0, "frame centre latitude");
            assertEquals(f.centreLon(), signedBe(p.items().get(24)) * 360.0 / 4294967294.0, 360.0 / 4294967294.0, "frame centre longitude");
            assertEquals(f.centreElevM(), unsignedBe(p.items().get(25)) * 19900.0 / 65535.0 - 900, 0.16, "frame centre elevation");
            for (int c = 0; c < 4; c++) { // corner 1 upper left, then clockwise
                assertEquals(f.corners()[c][0], signedBe(p.items().get(82 + 2 * c)) * 180.0 / 4294967294.0, 180.0 / 4294967294.0, "corner " + (c + 1) + " latitude");
                assertEquals(f.corners()[c][1], signedBe(p.items().get(83 + 2 * c)) * 360.0 / 4294967294.0, 360.0 / 4294967294.0, "corner " + (c + 1) + " longitude");
                assertEquals(f.corners()[c][0] - f.centreLat(), signedBe(p.items().get(26 + 2 * c)) * 0.15 / 65534.0, 0.15 / 65534.0, "offset corner " + (c + 1) + " latitude");
                assertEquals(f.corners()[c][1] - f.centreLon(), signedBe(p.items().get(27 + 2 * c)) * 0.15 / 65534.0, 0.15 / 65534.0, "offset corner " + (c + 1) + " longitude");
            }

            // the KLV's time is a picture's time in the stream, to the millisecond; FFmpeg hands it over within a
            // couple of pictures of that one (its H.264 parser holds a picture back until the next one starts)
            int picture = d.videoPtsMs().indexOf(firstVideo + micros / 1000 - T0_MS);
            assertTrue(picture >= 0, "a picture of the KLV's own instant: " + (micros / 1000 - T0_MS) + " ms");
            assertTrue(Math.abs(d.klvBeforePicture().get(i) - picture) <= 2, "read out beside its picture");
            Long pts = d.klvPts().get(i);
            if (pts != Long.MIN_VALUE) assertEquals(d.videoPtsMs().get(picture), pts, "a KLV time stamp in the PES is its picture's");
        }
    }

    /** A player that joins late (a receiver switched on mid-flight) starts at the next key frame, KLV and all. */
    @Test
    void aPlayerThatJoinsMidStreamFindsTheTablesAndStartsAtAKeyFrame(@TempDir Path dir) throws Exception {
        av_log_set_level(AV_LOG_FATAL); // as the app's decoder: the pictures before the first key frame are refused, not printed
        Map<Long, Klv.Frame> sent = new ConcurrentHashMap<>();
        byte[] all = Files.readAllBytes(write(dir, "whole.ts", sent, 120));
        int cut = (all.length / 188 / 3) * 188 + 188 * 5; // a third in, not at any table
        Path late = Files.write(dir.resolve("late.ts"), Arrays.copyOfRange(all, cut, all.length));
        Demuxed d = demux(late);
        assertTrue(d.klvCodec(), "the tables come again: the late player knows the KLV stream");
        assertTrue(d.decoded() >= 30, "pictures from the next key frame on: " + d.decoded());
        assertTrue(d.klv().size() >= 8, "and the metadata: " + d.klv().size());
        for (byte[] k : d.klv()) {
            if (k.length >= 16 && Arrays.equals(UAS_LS_KEY, Arrays.copyOf(k, 16))) {
                try {
                    takeApart(k);
                } catch (AssertionError e) {
                    // the first may be a packet cut in two by the join; any other must be whole
                    assertSame(d.klv().get(0), k, e.getMessage());
                }
            }
        }
    }
}
