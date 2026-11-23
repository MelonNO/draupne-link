/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

import java.util.ArrayList;
import java.util.List;

/**
 * DRAUPNE Flight (fleet PRD RT-8): a whole route (fence, rally points) from the PC to the phone, so the mission
 * protocol's item-by-item talk runs over the phone's USB (milliseconds) and not over 4G (100 items at 50 ms round trips
 * miss the 5 s gate).
 * <p>
 * In parts: the phone reads at most {@link UdpCommon#packetLength} (1100) bytes of a datagram, and a Tailscale path
 * carries about 1250 - a block in one datagram over about 33 items was cut and dropped, nothing said (a 111-item
 * dispatch route never reached its drone). Each part [0x33][block id u16][mission type u8 (MissionUpload's TYPE_: 0
 * route, 1 fence, 2 rally points)][part u8][parts u8][block CRC-32 u32][items in the block u16][first item u16][items
 * here u8][items: MissionItem.PACKED_SIZE each] carries at most {@link #ITEMS_PER_PART} items: 855 bytes, 921 inside a
 * FleetCmd with the longest key. The CRC-32 is over the whole block (its MissionItem.pack and its type): the phone
 * uploads a block only when every part has come and they make that block. Unnumbered: the PC sends every part again
 * each second until the phone's status names the block beyond {@link #RECEIVING} ({@link Assembler}). The phone
 * answers with telemetry record {@link #STATUS_RECORD}: [block id u16][state u8 (5 receiving its parts, then
 * MissionUpload's 1 uploading, 2 reading back, 3 done, 4 failed)][percent u8][reason length u8][reason ASCII]. Java 11.
 */
public final class MissionBlock {
    public static final byte PACKET = 0x33;
    public static final short STATUS_RECORD = 0x40E1;
    /** The PC asks again this often until the phone answers, this many times at most. */
    public static final long RESEND_MS = 1000;
    public static final int MAX_SENDS = 15;
    /** Items in one part: 1 + 14 + 30 x 28 = 855 bytes, under the phone's 1100 also inside a FleetCmd (+66). */
    public static final int ITEMS_PER_PART = 30;
    /** The part header after the packet byte. */
    public static final int PART_HEADER = 14;
    /** Status state: the phone has some of the block's parts (percent: of the parts); the PC sends them all again. */
    public static final int RECEIVING = 5;

    public final int blockId;
    public final List<MissionItem> items;
    /** MissionUpload.TYPE_MISSION, TYPE_FENCE or TYPE_RALLY. */
    public final int missionType;

    private MissionBlock(int blockId, int missionType, List<MissionItem> items) {
        this.blockId = blockId;
        this.missionType = missionType;
        this.items = items;
    }

    /** The block's check: CRC-32 over its packed items and its type. */
    public static int crc(int missionType, List<MissionItem> items) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(MissionItem.pack(items));
        c.update(missionType);
        return (int) c.getValue();
    }

    /** The block as its parts, each its own datagram (at least one: an empty fence or rally list clears it). */
    public static List<byte[]> packets(int blockId, int missionType, List<MissionItem> items) {
        if (items.size() > MissionItem.MAX_ITEMS) throw new IllegalArgumentException(items.size() + " items: over " + MissionItem.MAX_ITEMS);
        int crc = crc(missionType, items);
        int parts = Math.max(1, (items.size() + ITEMS_PER_PART - 1) / ITEMS_PER_PART);
        List<byte[]> out = new ArrayList<>(parts);
        for (int p = 0; p < parts; p++) {
            int first = p * ITEMS_PER_PART, n = Math.min(ITEMS_PER_PART, items.size() - first);
            byte[] body = MissionItem.pack(items.subList(first, first + n)); // [count u16][items]
            byte[] d = new byte[1 + PART_HEADER + n * MissionItem.PACKED_SIZE];
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d);
            b.put(PACKET).putShort((short) blockId).put((byte) missionType).put((byte) p).put((byte) parts).putInt(crc)
                    .putShort((short) items.size()).putShort((short) first).put((byte) n);
            b.put(body, 2, body.length - 2);
            out.add(d);
        }
        return out;
    }

    /** A block that fits one part (at most {@link #ITEMS_PER_PART} items) as its one datagram. */
    public static byte[] packet(int blockId, List<MissionItem> items) {
        return packet(blockId, MissionUpload.TYPE_MISSION, items);
    }

    /** As {@link #packet(int, List)}; a longer block throws - it goes as {@link #packets}. */
    public static byte[] packet(int blockId, int missionType, List<MissionItem> items) {
        List<byte[]> p = packets(blockId, missionType, items);
        if (p.size() != 1) throw new IllegalArgumentException(items.size() + " items: " + p.size() + " parts - use packets()");
        return p.get(0);
    }

    /** One part as read. */
    public static final class Part {
        public final int blockId, missionType, index, count, crc, total, first;
        public final List<MissionItem> items;

        Part(int blockId, int missionType, int index, int count, int crc, int total, int first, List<MissionItem> items) {
            this.blockId = blockId;
            this.missionType = missionType;
            this.index = index;
            this.count = count;
            this.crc = crc;
            this.total = total;
            this.first = first;
            this.items = items;
        }
    }

    /** A part in a payload after the packet byte, or null: cut short, too long, or its numbers don't add up. */
    public static Part readPart(byte[] d, int off, int len) {
        if (d == null || off < 0 || off >= d.length) return null;
        len = Math.min(len, d.length - off); // a length that claims more than the array holds
        if (len < PART_HEADER) return null;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d, off, len);
        int id = b.getShort() & 0xffff, type = b.get() & 0xff, index = b.get() & 0xff, count = b.get() & 0xff, crc = b.getInt();
        int total = b.getShort() & 0xffff, first = b.getShort() & 0xffff, n = b.get() & 0xff;
        if (type > MissionUpload.TYPE_RALLY || count == 0 || index >= count || total > MissionItem.MAX_ITEMS || n > ITEMS_PER_PART) return null;
        if (count != Math.max(1, (total + ITEMS_PER_PART - 1) / ITEMS_PER_PART) || first != index * ITEMS_PER_PART) return null;
        if (n != Math.min(ITEMS_PER_PART, total - first)) return null;
        if (len != PART_HEADER + n * MissionItem.PACKED_SIZE) return null; // exactly: no item cut, none extra
        byte[] packed = new byte[2 + n * MissionItem.PACKED_SIZE];
        packed[0] = (byte) (n >>> 8);
        packed[1] = (byte) n;
        System.arraycopy(d, off + PART_HEADER, packed, 2, n * MissionItem.PACKED_SIZE);
        List<MissionItem> items = MissionItem.unpack(packed, 0, packed.length);
        if (items == null) return null;
        return new Part(id, type, index, count, crc, total, first, items);
    }

    /**
     * The block in a payload after the packet byte when that one part is the whole block, or null: cut short, a part
     * of a longer block, a check that doesn't match, an empty route.
     */
    public static MissionBlock read(byte[] d, int off, int len) {
        Part p = readPart(d, off, len);
        if (p == null || p.count != 1) return null;
        return new Assembler().offer(p);
    }

    /**
     * The phone's side: parts in, whole blocks out - each block once, only when all its parts came and their CRC-32
     * matches. Parts may come lost, twice or out of order; a part whose numbers disagree with the others of its block
     * starts that block anew (a new block under the same id). A few blocks are gathered at once (a route and a fence
     * sent together; a new one mid-way). Thread-safe.
     */
    public static final class Assembler {
        static final int GATHERING = 4, DONE_KEPT = 16;

        private static final class Gathering {
            final Part head;
            final MissionItem[] items;
            final boolean[] have;
            int got;

            Gathering(Part p) {
                head = p;
                items = new MissionItem[p.total];
                have = new boolean[p.count];
            }

            boolean sameBlock(Part p) {
                return p.missionType == head.missionType && p.count == head.count && p.crc == head.crc && p.total == head.total;
            }
        }

        private final java.util.LinkedHashMap<Integer, Gathering> gathering = new java.util.LinkedHashMap<>();
        /** Blocks already put together, {id, crc}: their late or repeated parts are not gathered again. */
        private final java.util.ArrayDeque<long[]> done = new java.util.ArrayDeque<>();

        /** @return the whole block when this part made it whole, else null */
        public synchronized MissionBlock offer(Part p) {
            if (p == null) return null;
            for (long[] d : done) if (d[0] == p.blockId && (int) d[1] == p.crc) return null; // put together already
            Gathering g = gathering.get(p.blockId);
            if (g != null && !g.sameBlock(p)) g = null; // other numbers under this id: a new block
            if (g == null) {
                gathering.remove(p.blockId);
                g = new Gathering(p);
                gathering.put(p.blockId, g);
                if (gathering.size() > GATHERING) gathering.remove(gathering.keySet().iterator().next());
            }
            if (!g.have[p.index]) {
                g.have[p.index] = true;
                g.got++;
                for (int i = 0; i < p.items.size(); i++) g.items[p.first + i] = p.items.get(i);
            }
            if (g.got < g.head.count) return null;
            gathering.remove(p.blockId);
            List<MissionItem> all = new ArrayList<>(g.items.length);
            for (int i = 0; i < g.items.length; i++) all.add(g.items[i].withSeq(i));
            if (crc(g.head.missionType, all) != g.head.crc) return null; // the parts don't make the block they claim
            if (all.isEmpty() && g.head.missionType == MissionUpload.TYPE_MISSION) return null; // an empty route is none
            done.addLast(new long[]{p.blockId, p.crc});
            if (done.size() > DONE_KEPT) done.removeFirst();
            return new MissionBlock(p.blockId, g.head.missionType, all);
        }

        /** A block being gathered: percent of its parts here, -1 when none is. */
        public synchronized int percent(int blockId) {
            Gathering g = gathering.get(blockId);
            return g == null ? -1 : g.got * 100 / g.head.count;
        }
    }

    /** The status record's payload (after its code). */
    public static byte[] status(int blockId, int state, int percent, String reason) {
        byte[] r = reason == null ? new byte[0] : reason.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int n = Math.min(r.length, 120);
        byte[] out = new byte[5 + n];
        out[0] = (byte) (blockId >>> 8);
        out[1] = (byte) blockId;
        out[2] = (byte) state;
        out[3] = (byte) Math.max(0, Math.min(100, percent));
        out[4] = (byte) n;
        System.arraycopy(r, 0, out, 5, n);
        return out;
    }
}
