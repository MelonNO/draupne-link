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
    /**
     * Fleet RT-9 / FD-6 / AC-10: RETURN [0x40][RTL height above home dm u16] - a drone out on its own (dispatched, a
     * reserve on its way, the pilot's own on a route) gets its RTL_ALT; the phone sets it (PARAM_SET) and its reports
     * carry RTL_ALT as ArduPilot answered ({@link FleetStatus#rtlAltDm}). 0 changes nothing.
     */
    public static final byte RETURN = 0x40;

    /** RETURN: RTL_ALT relM above home (dm, rounded up: never under the height planned). */
    public static byte[] ret(double relM) {
        int dm = Double.isNaN(relM) ? 0 : (int) Math.max(0, Math.min(65534, Math.ceil(relM * 10 - 1e-6)));
        return new byte[]{RETURN, (byte) (dm >>> 8), (byte) dm};
    }

    /** RETURN's height (m above home): 0 changes nothing; -1 when cut short or not a RETURN. */
    public static double readReturnHeight(FleetCmd c) {
        if (c == null || c.inner() != RETURN || c.length < 3) return -1;
        return ((c.data[c.at + 1] & 0xff) << 8 | (c.data[c.at + 2] & 0xff)) / 10.0;
    }

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
        if (k.length == 0 || k.length > 64 || inner == null || inner.length == 0) throw new IllegalArgumentException("key or packet");
        byte[] out = new byte[2 + k.length + inner.length];
        out[0] = PACKET;
        out[1] = (byte) k.length;
        System.arraycopy(k, 0, out, 2, k.length);
        System.arraycopy(inner, 0, out, 2 + k.length, inner.length);
        return out;
    }

    /** The command in a datagram, or null: not one, cut short, or no inner packet. */
    public static FleetCmd read(byte[] d, int len) {
        if (d == null) return null;
        len = Math.min(len, d.length); // a length that claims more than the array holds
        if (len < 3 || d[0] != PACKET) return null;
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

    /**
     * @param rtlAltRelM the lead's RTL_ALT above home; 0 (or NaN, or less): the phone leaves RTL_ALT as it is - a hand-over
     *                   names the lead before its return height is worked out, and RTL_ALT 0 is ArduPilot's "come home at
     *                   the height it has", under the followers' return layers
     */
    public static byte[] lead(boolean lead, double rtlAltRelM) {
        int dm = (int) Math.max(0, Math.min(65535, Math.round(rtlAltRelM * 10)));
        return new byte[]{LEAD, (byte) (lead ? 1 : 0), (byte) (dm >>> 8), (byte) dm};
    }

    /** LEAD's RTL height (m above home) as read: 0 leaves RTL_ALT as it is; -1 when the command is cut short. */
    public static double readLeadHeight(FleetCmd c) {
        if (c == null || c.inner() != LEAD || c.length < 4) return -1;
        return ((c.data[c.at + 2] & 0xff) << 8 | (c.data[c.at + 3] & 0xff)) / 10.0;
    }

    /**
     * A place off the globe, NaN or a height that is not a number is sent as no place (0 / 0), which {@link #readClear}
     * refuses: the int casts wrapped a larger number round to another real place.
     */
    public static byte[] clear(double lat, double lon, double altAmslM) {
        boolean ok = Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && Math.abs(altAmslM) < 100_000; // false for NaN too
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(13);
        b.put(CLEAR).putInt(ok ? (int) Math.round(lat * 1e7) : 0).putInt(ok ? (int) Math.round(lon * 1e7) : 0)
                .putInt(ok ? (int) Math.round(altAmslM * 10) : 0);
        return b.array();
    }

    public static byte[] traffic(byte[] traffic) {
        return prefixed(TRAFFIC, traffic);
    }

    /**
     * CLEAR's place, {lat, lon, height above sea level m}, or null: cut short, off the globe, or 0 / 0 (no place - a
     * GUIDED target there would send the drone to the Gulf of Guinea).
     */
    public static double[] readClear(FleetCmd c) {
        if (c == null || c.inner() != CLEAR || c.length < 13) return null;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(c.data, c.at + 1, 12);
        int a = bb.getInt(), o = bb.getInt(), h = bb.getInt();
        if (a == 0 && o == 0) return null;
        double lat = a / 1e7, lon = o / 1e7;
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return new double[]{lat, lon, h / 10.0};
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
