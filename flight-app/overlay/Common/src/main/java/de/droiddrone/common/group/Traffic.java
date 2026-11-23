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

package de.droiddrone.common.group;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fleet PRD FD-8 / AC-11: the other DRAUPNE drones round one drone, for its flight controller as ADS-B traffic. The PC
 * sends it once a second (FleetCmd TRAFFIC, 0x3F) to the lead and each follower; the phone writes one ADSB_VEHICLE per
 * entry to its FC. Big endian:
 * <pre>
 * [version u8 = 1][count u8, 0..8] then per drone 24 bytes:
 *   [address u32]     the drone's ADS-B address: 24 bits, {@link #address(String)} of its name
 *   [lat i32]         degrees * 1e7
 *   [lon i32]         degrees * 1e7
 *   [alt i32]         height above sea level, dm
 *   [heading u16]     course over ground, centidegrees 0..35999 (from its velocity)
 *   [hor speed u16]   cm/s
 *   [ver speed i16]   cm/s, up positive
 *   [flags u8]        {@link #PREDICTED}: cut off, the place its exit plan has it; {@link #NO_GNSS}: its own fix lost
 *   [age u8]          seconds since the PC last heard it, 0..255
 * </pre>
 * 2 + 8 * 24 = 194 bytes at most. Java 11.
 */
public final class Traffic {
    public static final int VERSION = 1, MAX = 8, ENTRY_BYTES = 24;
    public static final int PREDICTED = 1, NO_GNSS = 2;

    /** One drone. */
    public static final class Entry {
        public final long address;
        public final double lat, lon, altAmsl, headingDeg, horSpeedMs, verSpeedMs;
        public final int flags, ageS;

        public Entry(long address, double lat, double lon, double altAmsl, double headingDeg, double horSpeedMs, double verSpeedMs,
                     int flags, int ageS) {
            this.address = address & 0xffffffffL;
            this.lat = lat;
            this.lon = lon;
            this.altAmsl = altAmsl;
            this.headingDeg = headingDeg;
            this.horSpeedMs = horSpeedMs;
            this.verSpeedMs = verSpeedMs;
            this.flags = flags;
            this.ageS = ageS;
        }

        /** From a velocity east, north, up (m/s): the course and speeds ADS-B carries. */
        public static Entry of(long address, double lat, double lon, double altAmsl, double ve, double vn, double vu, int flags, int ageS) {
            double course = Math.toDegrees(Math.atan2(ve, vn));
            if (course < 0) course += 360;
            return new Entry(address, lat, lon, altAmsl, course, Math.hypot(ve, vn), vu, flags, ageS);
        }
    }

    public final List<Entry> entries;

    public Traffic(List<Entry> entries) {
        if (entries.size() > MAX) throw new IllegalArgumentException("at most " + MAX);
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * A drone's ADS-B address from its name: 24 bits (as ICAO's), never 0 and never all ones, the same on every PC.
     * FNV-1a over the name's UTF-8 bytes, folded to 24 bits.
     */
    public static long address(String drone) {
        int h = 0x811c9dc5;
        for (byte b : drone.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x01000193;
        }
        long a = ((h >>> 24) ^ h) & 0xffffffL;
        if (a == 0 || a == 0xffffffL) a = 0x5a5a5aL;
        return a;
    }

    public byte[] encode() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(2 + entries.size() * ENTRY_BYTES);
        b.put((byte) VERSION).put((byte) entries.size());
        for (Entry e : entries) {
            double heading = ((e.headingDeg % 360) + 360) % 360;
            b.putInt((int) e.address)
                    .putInt((int) Math.round(e.lat * 1e7)).putInt((int) Math.round(e.lon * 1e7))
                    .putInt((int) Math.round(e.altAmsl * 10))
                    .putShort((short) (Math.round(heading * 100) % 36000))
                    .putShort((short) Math.max(0, Math.min(65535, Math.round(e.horSpeedMs * 100))))
                    .putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(e.verSpeedMs * 100))))
                    .put((byte) e.flags)
                    .put((byte) Math.max(0, Math.min(255, e.ageS)));
        }
        return b.array();
    }

    /** The traffic at d[at..at+len), or null: cut short, another version, too many, a place off the globe. */
    public static Traffic decode(byte[] d, int at, int len) {
        if (d == null || len < 2 || at < 0 || at + len > d.length || (d[at] & 0xff) != VERSION) return null;
        int n = d[at + 1] & 0xff;
        if (n > MAX || len < 2 + n * ENTRY_BYTES) return null;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d, at + 2, n * ENTRY_BYTES);
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long address = b.getInt() & 0xffffffffL;
            double lat = b.getInt() / 1e7, lon = b.getInt() / 1e7, alt = b.getInt() / 10.0;
            double heading = (b.getShort() & 0xffff) / 100.0, hor = (b.getShort() & 0xffff) / 100.0, ver = b.getShort() / 100.0;
            int flags = b.get() & 0xff, age = b.get() & 0xff;
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || heading >= 360) return null;
            out.add(new Entry(address, lat, lon, alt, heading, hor, ver, flags, age));
        }
        return new Traffic(out);
    }
}
