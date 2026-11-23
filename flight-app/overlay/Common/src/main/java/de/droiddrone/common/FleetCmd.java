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
 * DRAUPNE Flight (fleet PRD FD-1): the PC's light command link to a drone it doesn't pilot - [0x34][key length u8][key]
 * [one inner packet]. The inner packet is one of the pilot session's own (FcCommand 0x30, MissionBlock 0x33) or a
 * fleet one: {@link #GROUP} [0x35][GroupOrder], {@link #EXIT} [0x36][ExitPlan], {@link #LAUNCH} [0x37][LaunchWire order],
 * {@link #TRACK} [0x39][seconds u8] (report for that long), {@link #LEAD} [0x3A][lead u8][RTL height above home dm u16],
 * {@link #CLEAR} [0x3B][lat i32][lon i32][height above sea level dm i32] (step aside there in GUIDED), {@link #DISBAND}
 * [0x3C] (no longer in a group), {@link #TRAFFIC} [0x3F][group.Traffic] (the other drones, for the FC as ADS-B). Who may send it: {@link FleetCommandGate}. The phone answers every accepted one by
 * keeping its {@link FleetStatus} reports going to the sender. Unnumbered: the PC repeats what must arrive (orders at
 * 5 Hz, plans every 10 s). Java 11.
 */
public final class FleetCmd {
    public static final byte PACKET = 0x34;
    public static final byte GROUP = 0x35, EXIT = 0x36, LAUNCH = 0x37, TRACK = 0x39, LEAD = 0x3A, CLEAR = 0x3B, DISBAND = 0x3C;
    /**
     * Fleet AL-1: SAFE TO AUTOLAUNCH [0x3D][mark id i32][the floor dm u16] - the phone marks where it is and which way it
     * points; mark id 0 clears it.
     */
    public static final byte MARK = 0x3D;
    /** The launch's status, phone to commander: [0x3E][LaunchWire status]. Not a FleetCmd: a datagram of its own. */
    public static final byte LAUNCH_STATUS = 0x3E;
    /**
     * Fleet FD-8 / AC-11: the other DRAUPNE drones round this one, once a second, [0x3F][group.Traffic] - its FC gets
     * each as an ADS-B target (ADSB_VEHICLE).
     */
    public static final byte TRAFFIC = 0x3F;

    public static byte[] mark(int id, double floorM) {
        int dm = (int) Math.max(0, Math.min(65535, Math.round(floorM * 10)));
        return new byte[]{MARK, (byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id, (byte) (dm >>> 8), (byte) dm};
    }

    public final String key;
    /** The inner packet: its bytes from {@link #at}, {@link #length} long; data[at] is its id. */
    public final byte[] data;
    public final int at, length;

    private FleetCmd(String key, byte[] data, int at, int length) {
        this.key = key;
        this.data = data;
        this.at = at;
        this.length = length;
    }

    public byte inner() {
        return data[at];
    }

    public static byte[] wrap(String key, byte[] inner) {
        byte[] k = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (k.length > 64 || inner == null || inner.length == 0) throw new IllegalArgumentException("key or packet");
        byte[] out = new byte[2 + k.length + inner.length];
        out[0] = PACKET;
        out[1] = (byte) k.length;
        System.arraycopy(k, 0, out, 2, k.length);
        System.arraycopy(inner, 0, out, 2 + k.length, inner.length);
        return out;
    }

    /** The command in a datagram, or null: not one, cut short, or no inner packet. */
    public static FleetCmd read(byte[] d, int len) {
        if (d == null || len < 3 || d[0] != PACKET) return null;
        int k = d[1] & 0xff;
        if (k == 0 || k > 64 || len < 2 + k + 1) return null;
        String key = new String(d, 2, k, java.nio.charset.StandardCharsets.UTF_8);
        return new FleetCmd(key, d, 2 + k, len - 2 - k);
    }

    public static byte[] group(byte[] order) {
        return prefixed(GROUP, order);
    }

    public static byte[] exit(byte[] plan) {
        return prefixed(EXIT, plan);
    }

    public static byte[] launch(byte[] order) {
        return prefixed(LAUNCH, order);
    }

    public static byte[] track(int seconds) {
        return new byte[]{TRACK, (byte) Math.max(0, Math.min(255, seconds))};
    }

    public static byte[] lead(boolean lead, double rtlAltRelM) {
        int dm = (int) Math.max(0, Math.min(65535, Math.round(rtlAltRelM * 10)));
        return new byte[]{LEAD, (byte) (lead ? 1 : 0), (byte) (dm >>> 8), (byte) dm};
    }

    public static byte[] clear(double lat, double lon, double altAmslM) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(13);
        b.put(CLEAR).putInt((int) Math.round(lat * 1e7)).putInt((int) Math.round(lon * 1e7)).putInt((int) Math.round(altAmslM * 10));
        return b.array();
    }

    public static byte[] traffic(byte[] traffic) {
        return prefixed(TRAFFIC, traffic);
    }

    public static byte[] disband() {
        return new byte[]{DISBAND};
    }

    private static byte[] prefixed(byte id, byte[] body) {
        byte[] out = new byte[1 + body.length];
        out[0] = id;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }
}
