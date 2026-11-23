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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * DRAUPNE fleet (PRD RT-8): one MAVLink mission item as MISSION_ITEM_INT carries it - the same fields, the same
 * meaning - so the PC plans with it, the phone hands it to the flight controller, and the read-back compares it.
 * Altitudes are above sea level (frame {@link #FRAME_GLOBAL}, PRD B4 / RT-5); nothing uses terrain frames (RT-10,
 * TERRAIN_ENABLE = 0). Immutable. Java 11 (the phone's Common).
 */
public final class MissionItem {
    /** MAV_FRAME_GLOBAL: altitude above mean sea level (with MISSION_ITEM_INT, x / y are degrees * 1e7). */
    public static final int FRAME_GLOBAL = 0;
    /** MAV_FRAME_MISSION: no position (DO_ commands). */
    public static final int FRAME_MISSION = 2;

    public static final int NAV_WAYPOINT = 16, NAV_LOITER_UNLIM = 17, NAV_RETURN_TO_LAUNCH = 20, NAV_LAND = 21,
            NAV_TAKEOFF = 22, DO_CHANGE_SPEED = 178;

    public final int seq, frame, command;
    public final boolean autocontinue;
    public final float param1, param2, param3, param4;
    /** Latitude / longitude in degrees * 1e7. */
    public final int x, y;
    /** Altitude in metres (above sea level for {@link #FRAME_GLOBAL}). */
    public final float z;

    public MissionItem(int seq, int frame, int command, boolean autocontinue, float param1, float param2, float param3,
                       float param4, int x, int y, float z) {
        this.seq = seq;
        this.frame = frame;
        this.command = command;
        this.autocontinue = autocontinue;
        this.param1 = param1;
        this.param2 = param2;
        this.param3 = param3;
        this.param4 = param4;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** A positioned item (waypoint, takeoff, loiter, land) at lat / lon degrees and altitude above sea level. */
    public static MissionItem at(int seq, int command, double latDeg, double lonDeg, double altAmslM) {
        return new MissionItem(seq, FRAME_GLOBAL, command, true, 0, 0, 0, Float.NaN,
                (int) Math.round(latDeg * 1e7), (int) Math.round(lonDeg * 1e7), (float) altAmslM);
    }

    public MissionItem withSeq(int newSeq) {
        return new MissionItem(newSeq, frame, command, autocontinue, param1, param2, param3, param4, x, y, z);
    }

    public double latDeg() {
        return x / 1e7;
    }

    public double lonDeg() {
        return y / 1e7;
    }

    /**
     * The read-back's comparison (RT-8): the same command, frame and place to the last stored unit, the altitude to a
     * centimetre (the FC stores a float), the parameters as floats (NaN equals NaN: "unchanged" in MAVLink).
     */
    public boolean sameAs(MissionItem o) {
        return o != null && seq == o.seq && sameFrame(frame, o.frame) && command == o.command && x == o.x && y == o.y
                && Math.abs(z - o.z) < 0.01f && same(param1, o.param1) && same(param2, o.param2)
                && same(param3, o.param3) && same(param4, o.param4);
    }

    /** What differs between this item (sent) and another (read back), field by field: "frame 0 / 3, param2 0.0 / 2.0". */
    public String diff(MissionItem o) {
        if (o == null) return "nothing read back";
        StringBuilder s = new StringBuilder();
        if (seq != o.seq) s.append("seq ").append(seq).append(" / ").append(o.seq).append(", ");
        if (!sameFrame(frame, o.frame)) s.append("frame ").append(frame).append(" / ").append(o.frame).append(", ");
        if (command != o.command) s.append("command ").append(command).append(" / ").append(o.command).append(", ");
        if (x != o.x || y != o.y) s.append("place ").append(latDeg()).append(",").append(lonDeg()).append(" / ").append(o.latDeg()).append(",").append(o.lonDeg()).append(", ");
        if (Math.abs(z - o.z) >= 0.01f) s.append("height ").append(z).append(" / ").append(o.z).append(", ");
        float[] a = {param1, param2, param3, param4}, b = {o.param1, o.param2, o.param3, o.param4};
        for (int i = 0; i < 4; i++) if (!same(a[i], b[i])) s.append("param").append(i + 1).append(" ").append(a[i]).append(" / ").append(b[i]).append(", ");
        return s.length() == 0 ? "the same" : s.substring(0, s.length() - 2);
    }

    /**
     * A frame as sent against as read back. ArduPilot 4.7.3 (SITL, 2026-11-23) answers in the frames it stores: a rally
     * point sent as GLOBAL_RELATIVE_ALT_INT (6) reads back as GLOBAL_RELATIVE_ALT (3) - every follower's own rally
     * upload failed its read-back - and an item without a place sent as MISSION (2) reads back as GLOBAL (0). The
     * _INT frames and their plain twins mean the same place with MISSION_ITEM_INT; MISSION and GLOBAL the same for an
     * item without one.
     */
    static boolean sameFrame(int a, int b) {
        return plain(a) == plain(b);
    }

    private static int plain(int frame) {
        switch (frame) {
            case 2: // MAV_FRAME_MISSION
            case 5: // GLOBAL_INT
                return 0;
            case 6: // GLOBAL_RELATIVE_ALT_INT
                return 3;
            case 11: // GLOBAL_TERRAIN_ALT_INT
                return 10;
            default:
                return frame;
        }
    }

    /**
     * A parameter as sent against as read back. NaN sent means "as it is" (its default) in MAVLink: ArduPilot stores its
     * own value and reads that back - 0 for a waypoint's yaw, 1 for NAV_LAND's param4 (both seen in ArduPilot SITL
     * 4.7.3, 2026-11-23: every route MissionBuilder ends with a LAND failed its read-back). Anything matches it.
     */
    private static boolean same(float a, float b) {
        if (Float.isNaN(a)) return true;
        return !Float.isNaN(b) && Math.abs(a - b) < 1e-4f;
    }

    @Override
    public String toString() {
        return "#" + seq + " cmd " + command + " " + latDeg() + "," + lonDeg() + " " + z + " m";
    }

    // --- the PC's one packet to the phone: the whole mission (so the item-by-item talk runs on the phone's USB) ---

    /** The mission as one block: [count u16] then per item [frame u8][command u16][flags u8][p1..p4 f32][x i32][y i32][z f32]. */
    public static byte[] pack(List<MissionItem> items) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(2 + items.size() * 32);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeShort(items.size());
            for (MissionItem m : items) {
                out.writeByte(m.frame);
                out.writeShort(m.command);
                out.writeByte(m.autocontinue ? 1 : 0);
                out.writeFloat(m.param1);
                out.writeFloat(m.param2);
                out.writeFloat(m.param3);
                out.writeFloat(m.param4);
                out.writeInt(m.x);
                out.writeInt(m.y);
                out.writeFloat(m.z);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // a byte array never fails
        }
    }

    /** {@link #pack}'s block read back, items numbered from 0 in order; null when it is cut short, has bytes past its last item (the
     * count and the length disagree), or claims more than {@link #MAX_ITEMS}. */
    public static List<MissionItem> unpack(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 2 || offset > data.length) return null;
        length = Math.min(length, data.length - offset); // a length that claims more than the array holds
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, offset, length));
            int n = in.readUnsignedShort();
            if (n > MAX_ITEMS || length != 2 + n * PACKED_SIZE) return null; // exactly: a count lower than the items sent would fly a cut route
            List<MissionItem> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int frame = in.readUnsignedByte();
                int command = in.readUnsignedShort();
                boolean auto = in.readUnsignedByte() != 0;
                float p1 = in.readFloat(), p2 = in.readFloat(), p3 = in.readFloat(), p4 = in.readFloat();
                int x = in.readInt(), y = in.readInt();
                float z = in.readFloat();
                out.add(new MissionItem(i, frame, command, auto, p1, p2, p3, p4, x, y, z));
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    /** Bytes per item in {@link #pack}'s block. */
    public static final int PACKED_SIZE = 1 + 2 + 1 + 16 + 4 + 4 + 4;
    /**
     * The most items a block may carry: under one UDP datagram (64 KiB). Over 4G a block over ~1.2 kB is split by IP
     * into fragments, any one lost loses it all - the link's sender resends the block as a whole until the phone says
     * it has it (a route of 100 items is 3.2 kB: three fragments).
     */
    public static final int MAX_ITEMS = 1500;
}
