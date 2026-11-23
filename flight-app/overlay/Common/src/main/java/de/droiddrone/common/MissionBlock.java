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

/**
 * DRAUPNE Flight (fleet PRD RT-8): a whole route from the PC to the phone in one datagram, so the mission protocol's
 * item-by-item talk runs over the phone's USB (milliseconds) and not over 4G (100 items at 50 ms round trips miss the
 * 5 s gate). [0x33][block id u16][mission type u8 (MissionUpload's TYPE_: 0 route, 1 fence, 2 rally points)][MissionItem.pack]. Unnumbered: the PC sends it again every second until the phone's
 * status record names its block id. The phone answers with telemetry record {@link #STATUS_RECORD}:
 * [block id u16][state u8 (MissionUpload's 1 uploading, 2 reading back, 3 done, 4 failed)][percent u8][reason length u8]
 * [reason ASCII]. Java 11.
 */
public final class MissionBlock {
    public static final byte PACKET = 0x33;
    public static final short STATUS_RECORD = 0x40E1;
    /** The PC asks again this often until the phone answers, this many times at most. */
    public static final long RESEND_MS = 1000;
    public static final int MAX_SENDS = 15;

    public final int blockId;
    public final java.util.List<MissionItem> items;
    /** MissionUpload.TYPE_MISSION, TYPE_FENCE or TYPE_RALLY. */
    public final int missionType;

    private MissionBlock(int blockId, int missionType, java.util.List<MissionItem> items) {
        this.blockId = blockId;
        this.missionType = missionType;
        this.items = items;
    }

    public static byte[] packet(int blockId, java.util.List<MissionItem> items) {
        return packet(blockId, MissionUpload.TYPE_MISSION, items);
    }

    public static byte[] packet(int blockId, int missionType, java.util.List<MissionItem> items) {
        byte[] body = MissionItem.pack(items);
        byte[] p = new byte[4 + body.length];
        p[0] = PACKET;
        p[1] = (byte) (blockId >>> 8);
        p[2] = (byte) blockId;
        p[3] = (byte) missionType;
        System.arraycopy(body, 0, p, 4, body.length);
        return p;
    }

    /** The block in a payload after the packet byte, or null: cut short, empty, or too long. */
    public static MissionBlock read(byte[] d, int off, int len) {
        if (d == null || off < 0 || off >= d.length) return null;
        len = Math.min(len, d.length - off); // a length that claims more than the array holds
        if (len < 5) return null;
        int id = (d[off] & 0xff) << 8 | (d[off + 1] & 0xff);
        int type = d[off + 2] & 0xff;
        if (type > MissionUpload.TYPE_RALLY) return null;
        java.util.List<MissionItem> items = MissionItem.unpack(d, off + 3, len - 3);
        if (items == null || items.isEmpty() && type == MissionUpload.TYPE_MISSION) return null;
        return new MissionBlock(id, type, items);
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
