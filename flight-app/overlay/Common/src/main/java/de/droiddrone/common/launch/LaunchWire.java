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

package de.droiddrone.common.launch;

/**
 * Fleet PRD area F on the wire: the PC's LAUNCH order to a marked reserve (or its cancel) and the reserve phone's
 * launch status back. Payloads only, big-endian, each ending in a CRC-8 over what comes before: the wiring puts them
 * in its own keyed packets. A decoder never throws: anything short, of another version, or with a wrong CRC is null -
 * and a null order never launches anything.
 */
public final class LaunchWire {
    private LaunchWire() {
    }

    public static final int VERSION = 1;
    public static final int KIND_LAUNCH = 1, KIND_CANCEL = 2;
    public static final int NONE = 255;
    public static final int ORDER_SIZE = 25, STATUS_SIZE = 17;

    /** The PC's order. Heights in metres (sent in decimetres), the column's centre in degrees. */
    public static final class Order {
        public int kind = KIND_LAUNCH;
        /** The mark it is for: an order for another mark id launches nothing ({@link LaunchSequence}). */
        public int markId;
        /** The call's number, 0..65535: the status names it back. */
        public int callId;
        /** Its own approach layer, above the take-off point (AC-5). */
        public double approachHeightM;
        /** The follower floor as set at this moment (FL-14: reserves climb to the floor as it is then). */
        public double floorM;
        /** The cleared launch column's centre (AC-4): its own spot. */
        public double columnLat, columnLon;
        /** Who to join (FOLL_SYSID = the lead, AL-9); NONE: told later. */
        public int leadSysId = NONE;
        /** The cell it is to take (a new cell at the top, FA8); NONE: told when it reaches the group. */
        public int joinLayer = NONE, joinCell = NONE;
        /** Battery needed for this launch (the check's LOW BATTERY); 0: the phone's default. */
        public int minBatteryPercent;
    }

    /** The phone's word on its launch. */
    public static final class Status {
        public int callId, markId;
        public int state = LaunchSequence.IDLE;
        /** Why it failed (PreArmCheck's or ClimbWatchdog's word, see {@link LaunchSequence#reasonWord}); 0 none. */
        public int reason;
        public double heightM, climbMps;
        public boolean passedFloor, markValid, connected, armed;
    }

    public static byte[] encode(Order o) {
        byte[] b = new byte[ORDER_SIZE];
        int at = 0;
        b[at++] = (byte) VERSION;
        b[at++] = (byte) o.kind;
        at = putInt(b, at, o.markId);
        at = putShort(b, at, o.callId);
        at = putShort(b, at, dm(o.approachHeightM));
        at = putShort(b, at, dm(o.floorM));
        at = putInt(b, at, (int) Math.round(o.columnLat * 1e7));
        at = putInt(b, at, (int) Math.round(o.columnLon * 1e7));
        b[at++] = (byte) o.leadSysId;
        b[at++] = (byte) o.joinLayer;
        b[at++] = (byte) o.joinCell;
        b[at++] = (byte) Math.max(0, Math.min(100, o.minBatteryPercent));
        b[at] = crc8(b, at);
        return b;
    }

    /** The order, or null: not one (short, another version or kind, a wrong CRC, heights out of reason). */
    public static Order decodeOrder(byte[] b, int off, int len) {
        if (b == null || off < 0 || len < ORDER_SIZE || off + ORDER_SIZE > b.length) return null;
        if ((b[off] & 0xff) != VERSION) return null;
        if (crc8(b, off, ORDER_SIZE - 1) != b[off + ORDER_SIZE - 1]) return null;
        Order o = new Order();
        int at = off + 1;
        o.kind = b[at++] & 0xff;
        if (o.kind != KIND_LAUNCH && o.kind != KIND_CANCEL) return null;
        o.markId = getInt(b, at);
        at += 4;
        o.callId = getShort(b, at);
        at += 2;
        o.approachHeightM = getShort(b, at) / 10.0;
        at += 2;
        o.floorM = getShort(b, at) / 10.0;
        at += 2;
        o.columnLat = getInt(b, at) / 1e7;
        at += 4;
        o.columnLon = getInt(b, at) / 1e7;
        at += 4;
        o.leadSysId = b[at++] & 0xff;
        o.joinLayer = b[at++] & 0xff;
        o.joinCell = b[at++] & 0xff;
        o.minBatteryPercent = b[at] & 0xff;
        if (o.kind == KIND_LAUNCH) {
            if (o.markId == 0) return null;
            if (o.floorM <= 0 || o.approachHeightM < o.floorM) return null; // never a climb that ends under the floor
            if (Math.abs(o.columnLat) > 90 || Math.abs(o.columnLon) > 180) return null;
            if (o.minBatteryPercent > 100) return null;
        }
        return o;
    }

    public static byte[] encode(Status s) {
        byte[] b = new byte[STATUS_SIZE];
        int at = 0;
        b[at++] = (byte) VERSION;
        at = putShort(b, at, s.callId);
        at = putInt(b, at, s.markId);
        b[at++] = (byte) s.state;
        b[at++] = (byte) s.reason;
        at = putShort(b, at, clampShort(Math.round(s.heightM * 10)));
        at = putShort(b, at, clampShort(Math.round(s.climbMps * 100)));
        int flags = (s.passedFloor ? 1 : 0) | (s.markValid ? 2 : 0) | (s.connected ? 4 : 0) | (s.armed ? 8 : 0);
        b[at++] = (byte) flags;
        b[at++] = 0; // spare
        b[at++] = 0;
        b[at] = crc8(b, at);
        return b;
    }

    public static Status decodeStatus(byte[] b, int off, int len) {
        if (b == null || off < 0 || len < STATUS_SIZE || off + STATUS_SIZE > b.length) return null;
        if ((b[off] & 0xff) != VERSION) return null;
        if (crc8(b, off, STATUS_SIZE - 1) != b[off + STATUS_SIZE - 1]) return null;
        Status s = new Status();
        int at = off + 1;
        s.callId = getShort(b, at);
        at += 2;
        s.markId = getInt(b, at);
        at += 4;
        s.state = b[at++] & 0xff;
        if (s.state > LaunchSequence.LAST_STATE) return null;
        s.reason = b[at++] & 0xff;
        s.heightM = (short) getShort(b, at) / 10.0;
        at += 2;
        s.climbMps = (short) getShort(b, at) / 100.0;
        at += 2;
        int flags = b[at] & 0xff;
        s.passedFloor = (flags & 1) != 0;
        s.markValid = (flags & 2) != 0;
        s.connected = (flags & 4) != 0;
        s.armed = (flags & 8) != 0;
        return s;
    }

    private static int dm(double m) {
        return (int) Math.max(0, Math.min(65535, Math.round(m * 10)));
    }

    private static int clampShort(long v) {
        return (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private static int putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
        return at + 4;
    }

    private static int putShort(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 8);
        b[at + 1] = (byte) v;
        return at + 2;
    }

    private static int getInt(byte[] b, int at) {
        return (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | (b[at + 3] & 0xff);
    }

    private static int getShort(byte[] b, int at) {
        return (b[at] & 0xff) << 8 | (b[at + 1] & 0xff);
    }

    private static byte crc8(byte[] b, int len) {
        return crc8(b, 0, len);
    }

    /** CRC-8, polynomial 0x07. */
    static byte crc8(byte[] b, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= b[i] & 0xff;
            for (int k = 0; k < 8; k++) crc = (crc & 0x80) != 0 ? (crc << 1 ^ 0x07) & 0xff : crc << 1 & 0xff;
        }
        return (byte) crc;
    }
}
