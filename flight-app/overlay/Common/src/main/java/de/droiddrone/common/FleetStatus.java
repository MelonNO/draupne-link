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
 * DRAUPNE Flight (fleet PRD FD-1, FD-2): what a drone the PC doesn't pilot tells its commander, twice a second while it
 * is commanded (FleetCmd) - [0x38][version 1] then: lat, lon (1e7 i32), height above sea level (dm i32), velocity east,
 * north, up (cm/s i16 each), battery % (u8, 255 not known), flags (u8: armed, GNSS 3D, landed, pilot session up, PC
 * heard), ArduPilot mode (u8, 255 not known), the follower's phase (u8, 255 none), error radius (dm u16), home height
 * above sea level (dm i32), the route upload's block (u16), state (u8) and percent (u8). 34 bytes. Java 11.
 */
public final class FleetStatus {
    public static final byte PACKET = 0x38;
    public static final int VERSION = 1, BYTES = 34;
    public static final int ARMED = 1, GNSS = 2, LANDED = 4, PILOTED = 8, PC_HEARD = 16;

    public double lat, lon, altAmsl, velE, velN, velU, errM, homeAmsl;
    public int battery = 255, flags, mode = 255, phase = 255, routeBlock, routeState, routePercent;

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public byte[] encode() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(BYTES);
        b.put(PACKET).put((byte) VERSION)
                .putInt((int) Math.round(lat * 1e7)).putInt((int) Math.round(lon * 1e7)).putInt((int) Math.round(altAmsl * 10))
                .putShort(s(velE * 100)).putShort(s(velN * 100)).putShort(s(velU * 100))
                .put((byte) battery).put((byte) flags).put((byte) mode).put((byte) phase)
                .putShort((short) Math.max(0, Math.min(65535, Math.round(errM * 10))))
                .putInt((int) Math.round(homeAmsl * 10))
                .putShort((short) routeBlock).put((byte) routeState).put((byte) routePercent);
        return b.array();
    }

    /** A report, or null: not one, cut short, another version. */
    public static FleetStatus decode(byte[] d, int len) {
        if (d == null || len < BYTES || d[0] != PACKET || d[1] != VERSION) return null;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d, 2, BYTES - 2);
        FleetStatus s = new FleetStatus();
        s.lat = b.getInt() / 1e7;
        s.lon = b.getInt() / 1e7;
        s.altAmsl = b.getInt() / 10.0;
        s.velE = b.getShort() / 100.0;
        s.velN = b.getShort() / 100.0;
        s.velU = b.getShort() / 100.0;
        s.battery = b.get() & 0xff;
        s.flags = b.get() & 0xff;
        s.mode = b.get() & 0xff;
        s.phase = b.get() & 0xff;
        s.errM = (b.getShort() & 0xffff) / 10.0;
        s.homeAmsl = b.getInt() / 10.0;
        s.routeBlock = b.getShort() & 0xffff;
        s.routeState = b.get() & 0xff;
        s.routePercent = b.get() & 0xff;
        if (Math.abs(s.lat) > 90 || Math.abs(s.lon) > 180) return null;
        return s;
    }

    private static short s(double v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
    }
}
