package de.droiddrone.bench.fuzz;

import de.droiddrone.bench.fuzz.FuzzInput.Target;
import de.droiddrone.bench.fuzz.FuzzInput.Unit;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import static de.droiddrone.bench.fuzz.FuzzInput.*;

/**
 * Makes the decoder fuzz's inputs from its seeds: input number i of a run is a function of the seeds, the run's seed
 * and i alone ({@link #make}), so the parent rebuilds the input a child died on without the child writing anything
 * down. Pure Java, nothing native: it runs in the parent too.
 * <p>
 * A stream is changed at three levels: its bytes (bits flipped, bytes set to edge values, runs written, put in, cut
 * out, cut short, spliced with another seed's), its NAL units (headers edited, units dropped, repeated, moved between
 * frames, emulation prevention bytes taken out or added, start codes put in) and its order (frames dropped, repeated,
 * swapped, a config of the other codec, a flush, frames of another seed spliced in). A clip file at its bytes and its
 * structure: MP4 box sizes and counts, Matroska element sizes and block flags, MPEG-TS packet headers and lengths.
 */
public final class Mutator {
    static final int MAX_UNIT_BYTES = 1 << 20;
    static final int MAX_FILE_BYTES = 4 << 20;
    static final int MAX_UNITS = 400;
    private static final long MAX_STREAM_BYTES = 8L << 20;
    private static final int[] BYTES = {0x00, 0x01, 0x03, 0x7f, 0x80, 0xfe, 0xff, 0x40, 0x20, 0x10};
    private static final long[] WORDS = {0, 1, 2, 0x7f, 0x80, 0xff, 0x100, 0x7fff, 0x8000, 0xffff, 0x10000, 0x7fffffffL, 0x80000000L, 0xffffffffL, 0xfffffffeL};

    private final List<FuzzInput> streams = new ArrayList<>(), clips = new ArrayList<>();
    private final List<Target> targets;

    public Mutator(List<FuzzInput> seeds, Collection<Target> wanted) {
        for (FuzzInput s : seeds) {
            if (s.target() == Target.CLIP ? s.file() == null : s.units().isEmpty()) continue; // nothing to start from
            (s.target() == Target.CLIP ? clips : streams).add(s);
        }
        List<Target> t = new ArrayList<>();
        for (Target x : Target.values()) {
            if (wanted.contains(x) && !(x == Target.CLIP ? clips : streams).isEmpty()) t.add(x);
        }
        if (t.isEmpty()) throw new IllegalArgumentException("no seeds for " + wanted + " (" + seeds.size() + " seeds)");
        targets = List.copyOf(t);
    }

    /** The targets this mutator makes inputs for (those wanted that have seeds). */
    public List<Target> targets() {
        return targets;
    }

    /** Input number index of the run with this seed. */
    public FuzzInput make(long runSeed, long index) {
        Random r = new Random(mix(runSeed, index));
        Target target = targets.get(r.nextInt(targets.size()));
        return target == Target.CLIP ? clip(r) : stream(target, r);
    }

    /** SplitMix64 of the run's seed and the input's number: neighbouring numbers give unrelated generators. */
    static long mix(long seed, long index) {
        long z = seed + 0x9E3779B97F4A7C15L * (index + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ------------------------------------------------------------------------------------------------- streams

    private FuzzInput stream(Target target, Random r) {
        FuzzInput seed = streams.get(r.nextInt(streams.size()));
        List<Unit> units = new ArrayList<>(seed.units());
        int flags = r.nextInt(16);
        int changes = 1 << r.nextInt(4);
        for (int i = 0; i < changes; i++) mutateStream(units, r);
        bound(units);
        return new FuzzInput(target, flags, units);
    }

    private void mutateStream(List<Unit> units, Random r) {
        if (units.isEmpty()) {
            units.addAll(otherStream(r).units().subList(0, 1));
            return;
        }
        int op = r.nextInt(22);
        if (op < 8) { // bytes of a config or frame
            int u = pickData(units, r);
            Unit x = units.get(u);
            units.set(u, new Unit(x.kind(), bytes(x.data(), r, otherData(r))));
            return;
        }
        switch (op) {
            case 8, 9 -> { // a NAL unit's header
                int u = pickData(units, r);
                byte[] d = units.get(u).data().clone();
                List<int[]> nals = nals(d);
                if (nals.isEmpty()) {
                    if (d.length > 0) d[0] = (byte) r.nextInt(256);
                } else {
                    editNalHeader(d, nals.get(r.nextInt(nals.size())), r);
                }
                units.set(u, new Unit(units.get(u).kind(), d));
            }
            case 10 -> { // a NAL unit dropped, or repeated
                int u = pickData(units, r);
                List<byte[]> nals = nalBytes(units.get(u).data());
                if (nals.isEmpty()) return;
                int k = r.nextInt(nals.size());
                if (r.nextBoolean()) nals.remove(k);
                else nals.add(r.nextInt(nals.size() + 1), nals.get(k));
                units.set(u, new Unit(units.get(u).kind(), join(nals)));
            }
            case 11 -> { // a NAL unit of another unit (this stream's or another seed's: a config's into a frame) put in
                int u = pickData(units, r);
                byte[] from = r.nextBoolean() ? units.get(r.nextInt(units.size())).data() : otherData(r);
                List<byte[]> source = nalBytes(from);
                if (source.isEmpty()) return;
                List<byte[]> nals = nalBytes(units.get(u).data());
                nals.add(r.nextInt(nals.size() + 1), source.get(r.nextInt(source.size())));
                units.set(u, new Unit(units.get(u).kind(), join(nals)));
            }
            case 12 -> { // two NAL units of a unit swapped
                int u = pickData(units, r);
                List<byte[]> nals = nalBytes(units.get(u).data());
                if (nals.size() < 2) return;
                int a = r.nextInt(nals.size()), b = r.nextInt(nals.size());
                byte[] t = nals.get(a);
                nals.set(a, nals.get(b));
                nals.set(b, t);
                units.set(u, new Unit(units.get(u).kind(), join(nals)));
            }
            case 13 -> units.remove(r.nextInt(units.size()));
            case 14 -> units.add(r.nextInt(units.size() + 1), units.get(r.nextInt(units.size())));
            case 15 -> {
                int a = r.nextInt(units.size()), b = r.nextInt(units.size());
                Unit t = units.get(a);
                units.set(a, units.get(b));
                units.set(b, t);
            }
            case 16 -> { // cut short
                int keep = 1 + r.nextInt(units.size());
                units.subList(keep, units.size()).clear();
            }
            case 17 -> units.add(r.nextInt(units.size() + 1), new Unit(FLUSH, new byte[0]));
            case 18 -> { // a unit's kind: key or not, a config, the other codec's config
                int u = r.nextInt(units.size());
                Unit x = units.get(u);
                int kind = switch (r.nextInt(4)) {
                    case 0 -> x.kind() == FRAME ? KEY_FRAME : x.kind() == KEY_FRAME ? FRAME : x.kind();
                    case 1 -> x.isConfig() ? x.kind() ^ 1 : CONFIG_H264;
                    case 2 -> x.isConfig() ? KEY_FRAME : CONFIG_HEVC;
                    default -> x.isConfig() ? x.kind() ^ 1 : KEY_FRAME;
                };
                units.set(u, new Unit(kind, x.data()));
            }
            case 19 -> { // a run of another seed's units spliced in (the other codec too)
                List<Unit> other = otherStream(r).units();
                int from = r.nextInt(other.size()), n = 1 + r.nextInt(Math.min(4, other.size() - from));
                units.addAll(r.nextInt(units.size() + 1), other.subList(from, from + n));
            }
            case 20 -> { // a frame split in two, or two merged
                int u = pickData(units, r);
                Unit x = units.get(u);
                if (r.nextBoolean() && x.data().length > 1) {
                    int at = 1 + r.nextInt(x.data().length - 1);
                    units.set(u, new Unit(x.kind(), Arrays.copyOfRange(x.data(), 0, at)));
                    units.add(u + 1, new Unit(x.kind() == KEY_FRAME ? FRAME : x.kind(), Arrays.copyOfRange(x.data(), at, x.data().length)));
                } else if (u + 1 < units.size()) {
                    units.set(u, new Unit(x.kind(), concat(x.data(), units.get(u + 1).data())));
                    units.remove(u + 1);
                }
            }
            default -> { // emulation prevention: a 00 00 03 undone, or 00 00 03 / a start code / zeros put in
                int u = pickData(units, r);
                units.set(u, new Unit(units.get(u).kind(), emulation(units.get(u).data(), r)));
            }
        }
    }

    /** A unit with data: a config about a third of the time (the parameter sets), else frames nearer the start. */
    private static int pickData(List<Unit> units, Random r) {
        List<Integer> configs = new ArrayList<>(), data = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            if (units.get(i).isConfig()) configs.add(i);
            if (units.get(i).kind() != FLUSH) data.add(i);
        }
        if (!configs.isEmpty() && r.nextInt(3) == 0) return configs.get(r.nextInt(configs.size()));
        if (data.isEmpty()) return r.nextInt(units.size());
        return data.get(r.nextInt(r.nextInt(data.size()) + 1));
    }

    private FuzzInput otherStream(Random r) {
        return streams.get(r.nextInt(streams.size()));
    }

    private byte[] otherData(Random r) {
        List<Unit> u = otherStream(r).units();
        return u.isEmpty() ? new byte[0] : u.get(r.nextInt(u.size())).data();
    }

    private static void bound(List<Unit> units) {
        if (units.size() > MAX_UNITS) units.subList(MAX_UNITS, units.size()).clear();
        long total = 0;
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (u.data().length > MAX_UNIT_BYTES) units.set(i, u = new Unit(u.kind(), Arrays.copyOf(u.data(), MAX_UNIT_BYTES)));
            total += u.data().length;
            if (total > MAX_STREAM_BYTES) {
                units.subList(i, units.size()).clear();
                return;
            }
        }
    }

    /** The NAL units of an Annex B buffer: {where the unit's header is, where it ends} each, start codes left out. */
    static List<int[]> nals(byte[] d) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        int i = 0;
        while (i + 2 < d.length) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                if (start >= 0) {
                    int end = i;
                    while (end > start && d[end - 1] == 0) end--; // the leading zero of a 4-byte start code
                    out.add(new int[]{start, end});
                }
                start = i + 3;
                i += 3;
            } else {
                i++;
            }
        }
        if (start >= 0) out.add(new int[]{start, d.length});
        return out;
    }

    private static List<byte[]> nalBytes(byte[] d) {
        List<byte[]> out = new ArrayList<>();
        for (int[] n : nals(d)) out.add(Arrays.copyOfRange(d, n[0], n[1]));
        return out;
    }

    /** NAL units with 4-byte start codes, as the phone's encoder writes them. */
    static byte[] join(List<byte[]> nals) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] n : nals) {
            b.write(0);
            b.write(0);
            b.write(0);
            b.write(1);
            b.writeBytes(n);
        }
        return b.toByteArray();
    }

    /** The header of one NAL unit edited, read as H.264's one byte or H.265's two. */
    private static void editNalHeader(byte[] d, int[] nal, Random r) {
        int s = nal[0];
        if (s >= nal[1]) return;
        boolean second = s + 1 < nal[1];
        switch (r.nextInt(7)) {
            case 0 -> d[s] = (byte) ((d[s] & 0xe0) | r.nextInt(32));          // H.264 nal_unit_type
            case 1 -> d[s] ^= (byte) 0x80;                                     // forbidden_zero_bit
            case 2 -> d[s] = (byte) ((d[s] & 0x9f) | (r.nextInt(4) << 5));     // H.264 nal_ref_idc
            case 3 -> d[s] = (byte) ((d[s] & 0x81) | (r.nextInt(64) << 1));    // H.265 nal_unit_type
            case 4 -> {                                                        // H.265 nuh_layer_id
                d[s] = (byte) ((d[s] & 0xfe) | r.nextInt(2));
                if (second) d[s + 1] = (byte) ((d[s + 1] & 0x07) | (r.nextInt(32) << 3));
            }
            case 5 -> {                                                        // H.265 nuh_temporal_id_plus1 (0 is forbidden)
                if (second) d[s + 1] = (byte) ((d[s + 1] & 0xf8) | r.nextInt(8));
            }
            default -> d[s] = (byte) r.nextInt(256);
        }
    }

    private static byte[] emulation(byte[] d, Random r) {
        if (r.nextBoolean()) {
            List<Integer> at = new ArrayList<>();
            for (int i = 0; i + 2 < d.length; i++) if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 3) at.add(i + 2);
            if (!at.isEmpty()) {
                int k = at.get(r.nextInt(at.size()));
                return concat(Arrays.copyOfRange(d, 0, k), Arrays.copyOfRange(d, k + 1, d.length));
            }
        }
        byte[][] put = {{0, 0, 3}, {0, 0, 1}, {0, 0, 0, 1}, {0, 0, 0}, {0, 0, 2}};
        int at = d.length == 0 ? 0 : position(d.length, r);
        return concat(concat(Arrays.copyOfRange(d, 0, at), put[r.nextInt(put.length)]), Arrays.copyOfRange(d, at, d.length));
    }

    // ------------------------------------------------------------------------------------------------- clips

    private FuzzInput clip(Random r) {
        FuzzInput seed = clips.get(r.nextInt(clips.size()));
        Unit f = seed.file();
        byte[] d = f.data();
        int kind = f.kind();
        int flags = r.nextInt(16);
        int changes = 1 << r.nextInt(4);
        for (int i = 0; i < changes; i++) {
            switch (r.nextInt(12)) {
                case 0, 1, 2, 3, 4 -> d = bytes(d, r, otherClip(r));
                case 5, 6, 7 -> d = lengthField(d, r);
                case 8, 9 -> d = inStructure(d, r);
                case 10 -> kind = FILE_MP4 + r.nextInt(3);
                default -> {
                    byte[] o = otherClip(r);
                    if (d.length > 0 && o.length > 0) d = concat(Arrays.copyOfRange(d, 0, r.nextInt(d.length)), Arrays.copyOfRange(o, r.nextInt(o.length), o.length));
                }
            }
        }
        if (d.length > MAX_FILE_BYTES) d = Arrays.copyOf(d, MAX_FILE_BYTES);
        return new FuzzInput(Target.CLIP, flags, List.of(new Unit(kind, d)));
    }

    private byte[] otherClip(Random r) {
        Unit f = clips.get(r.nextInt(clips.size())).file();
        return f == null ? new byte[0] : f.data();
    }

    /** A region of a file's structure: {where its size field is, its length in bytes, where its payload starts, ends}. */
    record Region(int sizeAt, int sizeLen, int start, int end, long id) {
    }

    static boolean isMp4(byte[] d) {
        return d.length >= 8 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p';
    }

    static boolean isMkv(byte[] d) {
        return d.length >= 4 && (d[0] & 0xff) == 0x1a && (d[1] & 0xff) == 0x45 && (d[2] & 0xff) == 0xdf && (d[3] & 0xff) == 0xa3;
    }

    static boolean isTs(byte[] d) {
        return d.length >= 188 && d[0] == 0x47 && (d.length < 376 || d[188] == 0x47);
    }

    /** The file's regions: MP4 boxes, Matroska elements or TS packets; empty for anything else. */
    static List<Region> regions(byte[] d) {
        List<Region> out = new ArrayList<>();
        if (isMp4(d)) boxes(d, 0, d.length, out, 0);
        else if (isMkv(d)) elements(d, 0, d.length, out, 0);
        else if (isTs(d)) for (int p = 0; p + 188 <= d.length; p += 188) out.add(new Region(p, 0, p + 4, p + 188, 0x47));
        return out;
    }

    private static final List<String> MP4_CONTAINERS = List.of("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex", "moof", "traf", "meta");

    private static void boxes(byte[] d, int from, int to, List<Region> out, int depth) {
        int p = from;
        while (p + 8 <= to && out.size() < 4000 && depth < 8) {
            long size = u32(d, p);
            String type = new String(d, p + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int head = 8;
            if (size == 1 && p + 16 <= to) {
                size = u32(d, p + 8) << 32 | u32(d, p + 12);
                head = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < head || p + size > to) {
                out.add(new Region(p, 4, p + head, to, type.hashCode()));
                return;
            }
            int end = (int) (p + size);
            out.add(new Region(p, 4, p + head, end, type.hashCode()));
            if (MP4_CONTAINERS.contains(type)) boxes(d, p + head, end, out, depth + 1);
            else if (type.equals("stsd") && p + head + 8 <= end) boxes(d, p + head + 8, end, out, depth + 1);
            else if ((type.equals("avc1") || type.equals("hvc1") || type.equals("hev1")) && p + head + 78 <= end) boxes(d, p + head + 78, end, out, depth + 1);
            p = end;
        }
    }

    private static final long[] MKV_MASTERS = {0x1A45DFA3L, 0x18538067L, 0x114D9B74L, 0x4DBBL, 0x1549A966L, 0x1654AE6BL, 0xAEL, 0xE0L, 0x1F43B675L,
            0xA0L, 0x1C53BB6BL, 0xBBL, 0xB7L, 0x1254C367L, 0x7373L, 0x67C8L, 0x63C0L};

    private static void elements(byte[] d, int from, int to, List<Region> out, int depth) {
        int p = from;
        while (p < to && out.size() < 4000 && depth < 8) {
            int idLen = vintLength(d[p] & 0xff);
            if (idLen == 0 || idLen > 4 || p + idLen >= to) return;
            long id = 0;
            for (int i = 0; i < idLen; i++) id = id << 8 | (d[p + i] & 0xff);
            int sizeAt = p + idLen, sizeLen = vintLength(d[sizeAt] & 0xff);
            if (sizeLen == 0 || sizeAt + sizeLen > to) return;
            long size = d[sizeAt] & (0xff >> sizeLen);
            boolean unknown = size == (0xff >> sizeLen);
            for (int i = 1; i < sizeLen; i++) {
                size = size << 8 | (d[sizeAt + i] & 0xff);
                unknown &= (d[sizeAt + i] & 0xff) == 0xff;
            }
            int start = sizeAt + sizeLen;
            int end = unknown || start + size > to ? to : (int) (start + size);
            out.add(new Region(sizeAt, sizeLen, start, end, id));
            boolean master = false;
            for (long m : MKV_MASTERS) master |= m == id;
            if (master) elements(d, start, end, out, depth + 1);
            p = end;
        }
    }

    private static int vintLength(int first) {
        for (int i = 0; i < 8; i++) if ((first & (0x80 >> i)) != 0) return i + 1;
        return 0;
    }

    /** A size or count field of the file's structure set to an edge value. */
    static byte[] lengthField(byte[] d, Random r) {
        List<Region> regions = regions(d);
        if (regions.isEmpty()) return bytes(d, r, d);
        Region g = regions.get(r.nextInt(regions.size()));
        byte[] out = d.clone();
        if (isTs(d)) {
            int p = g.sizeAt();
            switch (r.nextInt(6)) {
                case 0 -> { // the PID
                    int pid = r.nextBoolean() ? r.nextInt(0x2000) : new int[]{0, 0x11, 0x100, 0x1000, 0x1fff}[r.nextInt(5)];
                    out[p + 1] = (byte) ((out[p + 1] & 0xe0) | pid >> 8);
                    out[p + 2] = (byte) pid;
                }
                case 1 -> out[p + 1] ^= 0x40;                                           // payload_unit_start_indicator
                case 2 -> out[p + 3] = (byte) ((out[p + 3] & 0xcf) | r.nextInt(4) << 4); // adaptation_field_control
                case 3 -> out[p + 3] = (byte) ((out[p + 3] & 0xf0) | r.nextInt(16));     // continuity_counter
                case 4 -> out[p + 4] = (byte) BYTES[r.nextInt(BYTES.length)];             // adaptation length / pointer field
                default -> { // a PES or section length
                    int at = p + 4 + r.nextInt(8);
                    if (at + 1 < p + 188) put(out, at, 2, WORDS[r.nextInt(WORDS.length)], true);
                }
            }
            return out;
        }
        if (r.nextInt(3) > 0 && g.sizeLen() > 0) { // the region's own size
            long now = isMp4(d) ? u32(d, g.sizeAt()) : g.end() - g.start();
            long[] choices = {0, 1, 7, 8, now - 1, now + 1, now * 2, now / 2, 0x7fffffffL, 0xffffffffL, r.nextInt(1 << 16)};
            long v = choices[r.nextInt(choices.length)];
            if (isMp4(d)) {
                put(out, g.sizeAt(), 4, v, true);
            } else { // a Matroska size, in the length it has (all ones: "unknown")
                int len = g.sizeLen();
                long max = (1L << (7 * len)) - 1;
                v = r.nextInt(4) == 0 ? max : Math.min(Math.max(v, 0), max);
                for (int i = len - 1; i >= 0; i--) {
                    out[g.sizeAt() + i] = (byte) v;
                    v >>>= 8;
                }
                out[g.sizeAt()] = (byte) ((out[g.sizeAt()] & (0xff >> len)) | (0x80 >> (len - 1)));
            }
            return out;
        }
        // a field inside: MP4 counts (version/flags, then the entry count); a Matroska block's track, time and flags (lacing)
        int len = g.end() - g.start();
        if (len <= 0) return out;
        if (isMkv(d) && (g.id() == 0xA3 || g.id() == 0xA1) && len >= 4) {
            int at = g.start() + (r.nextBoolean() ? 3 : r.nextInt(4));
            out[at] = r.nextBoolean() ? (byte) (out[at] ^ (1 << r.nextInt(8))) : (byte) BYTES[r.nextInt(BYTES.length)];
            return out;
        }
        int at = g.start() + (r.nextBoolean() && len >= 8 ? 4 : r.nextInt(len) & ~3);
        put(out, at, Math.min(4, g.end() - at), WORDS[r.nextInt(WORDS.length)], true);
        return out;
    }

    /** Bytes changed inside one region of the file's structure, the file's length kept. */
    static byte[] inStructure(byte[] d, Random r) {
        List<Region> regions = regions(d);
        if (regions.isEmpty()) return bytes(d, r, d);
        Region g = regions.get(r.nextInt(regions.size()));
        int from = Math.min(g.start(), d.length), to = Math.min(g.end(), d.length);
        if (to <= from) return d;
        byte[] out = d.clone();
        int n = 1 + r.nextInt(Math.min(8, to - from));
        for (int i = 0; i < n; i++) {
            int at = from + (r.nextBoolean() ? r.nextInt(Math.min(16, to - from)) : r.nextInt(to - from));
            out[at] = switch (r.nextInt(3)) {
                case 0 -> (byte) (out[at] ^ (1 << r.nextInt(8)));
                case 1 -> (byte) BYTES[r.nextInt(BYTES.length)];
                default -> (byte) r.nextInt(256);
            };
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- bytes

    /** One change to a buffer; it may grow or shrink. other: another seed's bytes, for a splice. */
    static byte[] bytes(byte[] d, Random r, byte[] other) {
        if (d.length == 0) return random(r, 1 + r.nextInt(16));
        byte[] out;
        switch (r.nextInt(13)) {
            case 0, 1 -> { // bits flipped
                out = d.clone();
                int n = 1 + r.nextInt(r.nextBoolean() ? 2 : 8);
                for (int i = 0; i < n; i++) {
                    int at = position(out.length, r);
                    out[at] ^= (byte) (1 << r.nextInt(8));
                }
            }
            case 2 -> {
                out = d.clone();
                out[position(out.length, r)] = (byte) BYTES[r.nextInt(BYTES.length)];
            }
            case 3 -> {
                out = d.clone();
                int at = position(out.length, r);
                out[at] = (byte) (out[at] + (r.nextBoolean() ? 1 : -1) * (1 + r.nextInt(35)));
            }
            case 4 -> { // a 16- or 32-bit edge value, either byte order
                out = d.clone();
                int len = r.nextBoolean() ? 2 : 4;
                int at = position(out.length, r);
                put(out, at, Math.min(len, out.length - at), WORDS[r.nextInt(WORDS.length)], r.nextBoolean());
            }
            case 5 -> { // a run written over
                out = d.clone();
                int at = position(out.length, r), n = Math.min(out.length - at, 1 + r.nextInt(64));
                if (r.nextBoolean()) Arrays.fill(out, at, at + n, (byte) BYTES[r.nextInt(BYTES.length)]);
                else for (int i = 0; i < n; i++) out[at + i] = (byte) r.nextInt(256);
            }
            case 6 -> { // a run put in
                int at = r.nextInt(d.length + 1);
                byte[] run = r.nextBoolean() ? random(r, 1 + r.nextInt(64)) : filled(1 + r.nextInt(64), BYTES[r.nextInt(BYTES.length)]);
                out = concat(concat(Arrays.copyOfRange(d, 0, at), run), Arrays.copyOfRange(d, at, d.length));
            }
            case 7 -> { // a run cut out
                int at = position(d.length, r), n = Math.min(d.length - at, 1 + r.nextInt(Math.max(1, d.length / 4)));
                out = concat(Arrays.copyOfRange(d, 0, at), Arrays.copyOfRange(d, at + n, d.length));
            }
            case 8 -> { // a piece repeated elsewhere
                int from = r.nextInt(d.length), n = Math.min(d.length - from, 1 + r.nextInt(256)), at = r.nextInt(d.length + 1);
                out = concat(concat(Arrays.copyOfRange(d, 0, at), Arrays.copyOfRange(d, from, from + n)), Arrays.copyOfRange(d, at, d.length));
            }
            case 9 -> out = Arrays.copyOf(d, r.nextInt(d.length)); // cut short
            case 10 -> { // spliced: the start of this, the end of another
                if (other.length == 0) return Arrays.copyOf(d, r.nextInt(d.length));
                out = concat(Arrays.copyOfRange(d, 0, r.nextInt(d.length + 1)), Arrays.copyOfRange(other, r.nextInt(other.length), other.length));
            }
            case 11 -> { // a few bytes shuffled
                out = d.clone();
                int at = position(out.length, r), n = Math.min(out.length - at, 2 + r.nextInt(7));
                for (int i = n - 1; i > 0; i--) {
                    int j = r.nextInt(i + 1);
                    byte t = out[at + i];
                    out[at + i] = out[at + j];
                    out[at + j] = t;
                }
            }
            default -> { // another seed's bytes written over these
                out = d.clone();
                if (other.length == 0) return out;
                int from = r.nextInt(other.length), at = position(out.length, r);
                int n = Math.min(Math.min(other.length - from, out.length - at), 1 + r.nextInt(128));
                System.arraycopy(other, from, out, at, n);
            }
        }
        return out;
    }

    /** Half the time in the first 64 bytes (headers, parameter sets, slice headers), else anywhere. */
    private static int position(int length, Random r) {
        return r.nextBoolean() ? r.nextInt(Math.min(length, 64)) : r.nextInt(length);
    }

    private static void put(byte[] d, int at, int len, long v, boolean bigEndian) {
        for (int i = 0; i < len && at + i < d.length; i++) {
            int shift = bigEndian ? 8 * (len - 1 - i) : 8 * i;
            d[at + i] = (byte) (v >>> shift);
        }
    }

    private static long u32(byte[] d, int at) {
        return (d[at] & 0xffL) << 24 | (d[at + 1] & 0xffL) << 16 | (d[at + 2] & 0xffL) << 8 | (d[at + 3] & 0xffL);
    }

    private static byte[] random(Random r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    private static byte[] filled(int n, int v) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) v);
        return b;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
