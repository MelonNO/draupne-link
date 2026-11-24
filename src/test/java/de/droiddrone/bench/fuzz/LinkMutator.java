package de.droiddrone.bench.fuzz;

import de.droiddrone.bench.fuzz.LinkInput.Packet;
import de.droiddrone.bench.fuzz.LinkInput.Target;
import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.Observe;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PacketIds;
import de.droiddrone.common.PcAudio;
import de.droiddrone.common.UdpCommon;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * Makes the protocol fuzz's inputs from its seeds: input number i of a run is a function of the seeds, the run's seed
 * and i alone ({@link #make}), as the decoder fuzz's {@link Mutator}, whose byte-level changes it uses too.
 * <p>
 * A stream is changed as a network and a damaged sender change it - datagrams lost, doubled, replayed later (one, or a
 * run of them), out of order, late, from another address - which leaves every datagram the sender's own
 * ({@link LinkInput#BYTES_INTACT} kept: the strict checks apply), and as damage does: bits flipped, the edge values
 * (0, 1, -1, 0x7F, 0x80, 0xFF, the most) written into the header's fields and into what reads as a length or a count,
 * sequence numbers moved (a numbered packet's, a stick frame's, a video frame's, a Connect's session id), the kind
 * byte changed to another kind, cut short, made longer, two datagrams spliced, and datagrams of another seed of the
 * same target put in.
 */
public final class LinkMutator {
    private static final int[] U8 = {0x00, 0x01, 0x02, 0x03, 0x04, 0x7f, 0x80, 0x81, 0xfe, 0xff, 0x10, 0x20, 0x40};
    private static final long[] U16 = {0, 1, 2, 0x7f, 0x80, 0xff, 0x100, 0x3ff, 0x400, 0x7fff, 0x8000, 0x8001, 0xfffe, 0xffff};
    private static final long[] U32 = {0, 1, 0x7f, 0x80, 0xff, 0x7fff, 0x8000, 0xffff, 0x10000, 0x7fffffffL, 0x80000000L, 0xffffffffL, 0xfffffffeL};
    /** Every kind byte the link knows: a damaged first byte becomes one of these more often than not. */
    static final byte[] KINDS = kinds();

    private final Map<Target, List<LinkInput>> seeds = new EnumMap<>(Target.class);
    private final List<Target> targets;

    public LinkMutator(List<LinkInput> seedList, Collection<Target> wanted) {
        for (LinkInput s : seedList) if (!s.packets().isEmpty()) seeds.computeIfAbsent(s.target(), t -> new ArrayList<>()).add(s);
        List<Target> t = new ArrayList<>();
        for (Target x : Target.values()) if (wanted.contains(x) && seeds.containsKey(x)) t.add(x);
        if (t.isEmpty()) throw new IllegalArgumentException("no seeds for " + wanted + " (" + seedList.size() + " seeds)");
        targets = List.copyOf(t);
    }

    public List<Target> targets() {
        return targets;
    }

    /** Input number index of the run with this seed. */
    public LinkInput make(long runSeed, long index) {
        Random r = new Random(Mutator.mix(runSeed ^ 0x5052_4f54L, index));
        Target target = targets.get(r.nextInt(targets.size()));
        List<LinkInput> pool = seeds.get(target);
        LinkInput seed = pool.get(r.nextInt(pool.size()));
        List<Packet> p = new ArrayList<>(seed.packets());
        boolean[] intact = {seed.intact()};
        int flags = seed.flags() & ~LinkInput.BYTES_INTACT;
        if (r.nextInt(6) == 0) flags ^= 2 << r.nextInt(3); // a target's own option the other way (disarmed, asleep, ...)
        int changes = 1 << r.nextInt(5); // 1 .. 16
        if (r.nextInt(8) == 0) { // only the network's doing: every datagram the sender's own
            for (int i = 0; i < changes; i++) stream(p, r);
        } else {
            for (int i = 0; i < changes; i++) {
                if (r.nextInt(3) == 0) stream(p, r);
                else damage(p, r, pool, intact);
            }
        }
        bound(p);
        return new LinkInput(target, flags | (intact[0] ? LinkInput.BYTES_INTACT : 0), p);
    }

    // ------------------------------------------------------------------------------------------------- the network

    /** Lost, doubled, replayed, reordered, late, from elsewhere: each datagram still the sender's own. */
    static void stream(List<Packet> p, Random r) {
        if (p.isEmpty()) return;
        int n = p.size(), i = r.nextInt(n);
        switch (r.nextInt(12)) {
            case 0 -> p.remove(i);                                                   // lost
            case 1 -> {                                                              // a run lost (a fade)
                int k = Math.min(n - i, 1 + r.nextInt(Math.max(1, n / 3)));
                p.subList(i, i + k).clear();
            }
            case 2 -> p.add(i + 1, new Packet(p.get(i).from(), 0, p.get(i).data())); // doubled
            case 3 -> {                                                              // replayed later, alone
                int at = i + 1 + r.nextInt(n - i);
                p.add(Math.min(at, p.size()), new Packet(p.get(i).from(), r.nextInt(3000), p.get(i).data()));
            }
            case 4 -> {                                                              // a run replayed (a queue let go)
                int k = Math.min(n - i, 1 + r.nextInt(r.nextBoolean() ? 8 : 120));
                List<Packet> run = new ArrayList<>();
                for (Packet q : p.subList(i, i + k)) run.add(new Packet(q.from(), r.nextInt(4), q.data()));
                p.addAll(r.nextBoolean() ? p.size() : Math.min(p.size(), i + k + r.nextInt(n - i)), run);
            }
            case 5 -> {                                                              // two swapped
                int j = Math.min(n - 1, i + 1 + r.nextInt(4));
                Packet t = p.get(i);
                p.set(i, p.get(j));
                p.set(j, t);
            }
            case 6 -> {                                                              // one overtaken by many
                Packet t = p.remove(i);
                p.add(Math.min(p.size(), i + 1 + r.nextInt(40)), t);
            }
            case 7 -> {                                                              // late: a gap before it
                long[] gaps = {300, 999, 1000, 1001, 1500, 2000, 4000, 5000, 10_001, 60_000, 120_001};
                p.set(i, new Packet(p.get(i).from(), (int) gaps[r.nextInt(gaps.length)] + r.nextInt(3), p.get(i).data()));
            }
            case 8 -> {                                                              // a burst: no time between
                for (int k = i; k < Math.min(n, i + 1 + r.nextInt(30)); k++) p.set(k, new Packet(p.get(k).from(), 0, p.get(k).data()));
            }
            case 9 -> {                                                              // from elsewhere
                int from = r.nextInt(3);
                p.set(i, new Packet(from, p.get(i).dtMs(), p.get(i).data()));
            }
            case 10 -> p.subList(1 + r.nextInt(n), n).clear();                       // the stream ends early
            default -> {                                                             // a run reversed
                int k = Math.min(n - i, 2 + r.nextInt(10));
                java.util.Collections.reverse(p.subList(i, i + k));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- damage

    private static void damage(List<Packet> p, Random r, List<LinkInput> pool, boolean[] intact) {
        if (p.isEmpty()) {
            p.add(new Packet(LinkInput.FROM_PEER, 0, randomBytes(r, 1 + r.nextInt(40))));
            intact[0] = false;
            return;
        }
        int i = pick(p, r);
        Packet x = p.get(i);
        byte[] d = x.data();
        byte[] out;
        int op = r.nextInt(16);
        switch (op) {
            case 0, 1, 2 -> out = Mutator.bytes(d, r, otherData(pool, r));         // the decoder fuzz's byte changes
            case 3, 4 -> out = field(d, r);                                         // an edge value in a header field
            case 5, 6 -> out = lengthField(d, r);                                   // a length or a count
            case 7, 8 -> out = sequence(d, r);                                      // a sequence number moved
            case 9 -> {                                                             // the kind byte
                out = d.clone();
                if (out.length > 0) out[0] = r.nextInt(4) == 0 ? (byte) r.nextInt(256) : KINDS[r.nextInt(KINDS.length)];
            }
            case 10 -> out = Arrays.copyOf(d, d.length == 0 ? 0 : r.nextInt(d.length)); // cut short
            case 11 -> {                                                            // longer: zeros, random, or itself again
                byte[] tail = switch (r.nextInt(3)) {
                    case 0 -> new byte[1 + r.nextInt(r.nextBoolean() ? 4 : 1200)];
                    case 1 -> randomBytes(r, 1 + r.nextInt(64));
                    default -> d;
                };
                out = Mutator.concat(d, tail);
            }
            case 12 -> {                                                            // spliced: this one's head, another's tail
                byte[] o = r.nextBoolean() ? p.get(r.nextInt(p.size())).data() : otherData(pool, r);
                out = Mutator.concat(Arrays.copyOf(d, r.nextInt(d.length + 1)), Arrays.copyOfRange(o, o.length == 0 ? 0 : r.nextInt(o.length), o.length));
            }
            case 13 -> {                                                            // datagrams of another seed put in
                LinkInput other = pool.get(r.nextInt(pool.size()));
                int from = r.nextInt(other.packets().size()), k = Math.min(other.packets().size() - from, 1 + r.nextInt(6));
                p.addAll(r.nextInt(p.size() + 1), other.packets().subList(from, from + k));
                intact[0] = false;
                return;
            }
            case 14 -> {                                                            // bits flipped, near the front
                out = d.clone();
                for (int k = 1 + r.nextInt(3); k > 0 && out.length > 0; k--) out[r.nextInt(Math.min(out.length, 16))] ^= (byte) (1 << r.nextInt(8));
            }
            default -> out = randomBytes(r, r.nextInt(r.nextBoolean() ? 16 : 300)); // made up, its kind kept
        }
        if (op == 15 && out.length > 0 && d.length > 0) out[0] = d[0];
        if (!Arrays.equals(out, d)) intact[0] = false;
        p.set(i, new Packet(x.from(), x.dtMs(), out));
    }

    /** A datagram, the rarer kinds as often as the many stick frames and video fragments. */
    private static int pick(List<Packet> p, Random r) {
        if (r.nextBoolean()) return r.nextInt(p.size());
        TreeSet<Integer> kinds = new TreeSet<>();
        for (Packet q : p) kinds.add(q.data().length > 0 ? q.data()[0] & 0xff : -1);
        Integer[] k = kinds.toArray(new Integer[0]);
        int want = k[r.nextInt(k.length)];
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) if ((p.get(i).data().length > 0 ? p.get(i).data()[0] & 0xff : -1) == want) at.add(i);
        return at.get(r.nextInt(at.size()));
    }

    private static byte[] otherData(List<LinkInput> pool, Random r) {
        List<Packet> p = pool.get(r.nextInt(pool.size())).packets();
        return p.isEmpty() ? new byte[0] : p.get(r.nextInt(p.size())).data();
    }

    /** An edge value, 1, 2 or 4 bytes big-endian (as both apps write), into the header: the first 16 bytes mostly. */
    static byte[] field(byte[] d, Random r) {
        if (d.length < 2) return d.length == 0 ? new byte[]{(byte) U8[r.nextInt(U8.length)]} : new byte[]{d[0], (byte) U8[r.nextInt(U8.length)]};
        byte[] out = d.clone();
        int at = 1 + (r.nextInt(4) == 0 ? r.nextInt(out.length - 1) : r.nextInt(Math.min(out.length - 1, 16)));
        switch (r.nextInt(3)) {
            case 0 -> out[at] = (byte) U8[r.nextInt(U8.length)];
            case 1 -> put(out, at, 2, r.nextInt(5) == 0 ? out.length - at + r.nextInt(3) - 1 : U16[r.nextInt(U16.length)]);
            default -> put(out, at, 4, r.nextInt(5) == 0 ? out.length + r.nextInt(3) - 1 : U32[r.nextInt(U32.length)]);
        }
        return out;
    }

    /**
     * What reads as a length (a UTF string's u16, so far as the bytes after it hold it) or a count (a small byte
     * that the bytes after it could be that many of) set to its neighbours and to the edge values.
     */
    static byte[] lengthField(byte[] d, Random r) {
        List<int[]> found = new ArrayList<>(); // {at, size: 1 or 2, value}
        for (int i = 1; i + 2 <= d.length && i < 80; i++) {
            int v = ((d[i] & 0xff) << 8) | (d[i + 1] & 0xff);
            if (v > 0 && i + 2 + v <= d.length) found.add(new int[]{i, 2, v});
        }
        for (int i = 1; i < Math.min(d.length, 24); i++) {
            int v = d[i] & 0xff;
            if (v > 0 && v <= 64 && i + 1 + v <= d.length) found.add(new int[]{i, 1, v});
        }
        if (found.isEmpty()) return field(d, r);
        int[] f = found.get(r.nextInt(found.size()));
        long room = d.length - f[0] - f[1];
        long[] choices = f[1] == 2 ? new long[]{0, 1, f[2] - 1L, f[2] + 1L, room, room + 1, 0x7fff, 0x8000, 0xffff, f[2] * 2L}
                : new long[]{0, 1, 3, 4, f[2] - 1L, f[2] + 1L, room, room + 1, 0x10, 0x11, 0x20, 0x7f, 0x80, 0xff};
        byte[] out = d.clone();
        put(out, f[0], f[1], choices[r.nextInt(choices.length)]);
        return out;
    }

    /** A sequence number moved, by the datagram's kind: back (old, a replay), a little forward, to its wrap. */
    static byte[] sequence(byte[] d, Random r) {
        if (d.length < 3) return field(d, r);
        byte[] out = d.clone();
        long[] by = {-1, -2, -100, -32768, 1, 2, 3, 1000, 32767, 32768, 65535};
        long step = by[r.nextInt(by.length)];
        byte k = d[0];
        if (k == UdpCommon.RcFrame && d.length >= 2) { // the stick frame's sequence, at its end by the layout
            int c = d[1] & 0xff, tail = d.length - 2 - 2 * c;
            int at = tail == 5 ? d.length - 5 : tail == 2 ? d.length - 2 : tail == 1 ? d.length - 1 : -1;
            if (at > 0) {
                if (tail == 1) out[at] = (byte) (out[at] + step);
                else put(out, at, 2, (((d[at] & 0xff) << 8 | (d[at + 1] & 0xff)) + step) & 0xffff);
                if (tail == 5 && r.nextBoolean()) put(out, d.length - 3, 2, (((d[d.length - 3] & 0xff) << 8 | (d[d.length - 2] & 0xff)) + step * 37) & 0xffff); // its send time too
                return out;
            }
        }
        if (k == UdpCommon.Connect && d.length >= 6) { // the session id behind the key and the version
            int at = 6 + (((d[4] & 0xff) << 8) | (d[5] & 0xff)) + 2;
            if (at + 4 <= d.length) {
                put(out, at, 4, ((long) ((d[at] & 0xff) << 24 | (d[at + 1] & 0xff) << 16 | (d[at + 2] & 0xff) << 8 | (d[at + 3] & 0xff)) + step) & 0xffffffffL);
                if (at + 4 < d.length && r.nextBoolean()) out[at + 4] ^= 1; // the answer's "by the id" mark
                return out;
            }
        }
        // a numbered packet's number, a video packet's frame number: the u16 behind the kind byte
        put(out, 1, 2, (((d[1] & 0xff) << 8 | (d[2] & 0xff)) + step) & 0xffff);
        return out;
    }

    private static void put(byte[] d, int at, int len, long v) {
        for (int i = 0; i < len && at + i < d.length; i++) d[at + i] = (byte) (v >>> (8 * (len - 1 - i)));
    }

    private static byte[] randomBytes(Random r, int n) {
        byte[] b = new byte[n];
        r.nextBytes(b);
        return b;
    }

    private static void bound(List<Packet> p) {
        if (p.size() > LinkInput.MAX_PACKETS) p.subList(LinkInput.MAX_PACKETS, p.size()).clear();
        for (int i = 0; i < p.size(); i++) {
            Packet q = p.get(i);
            if (q.data().length > LinkInput.MAX_PACKET_BYTES) p.set(i, new Packet(q.from(), q.dtMs(), Arrays.copyOf(q.data(), LinkInput.MAX_PACKET_BYTES)));
        }
    }

    /** The kind bytes of UdpCommon, PacketIds and the protocols with codes of their own. */
    private static byte[] kinds() {
        TreeSet<Byte> k = new TreeSet<>();
        for (Class<?> c : new Class<?>[]{UdpCommon.class, PacketIds.class}) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != byte.class || !Modifier.isStatic(f.getModifiers())) continue;
                try {
                    k.add(f.getByte(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        for (byte b : new byte[]{Observe.OBSERVE, Observe.STATE, Observe.STOP, FleetProtocol.MODE, PcAudio.PACKET, OverwatchProtocol.ALERT,
                OverwatchProtocol.COMMAND, OverwatchProtocol.PREVIEW, OverwatchProtocol.CLIP, OverwatchProtocol.STATUS}) k.add(b);
        byte[] out = new byte[k.size()];
        int i = 0;
        for (byte b : k) out[i++] = b;
        return out;
    }
}
