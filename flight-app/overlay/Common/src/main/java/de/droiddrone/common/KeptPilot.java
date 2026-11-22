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
 * DRAUPNE Flight: the pilot the phone keeps in its own storage, so that an app started anew - after an error, or ended
 * by Android, in the air perhaps - still knows who flew it: the pilot PC's address and port and its session's id
 * ({@link SessionGate}). Before, the pilot lived in the app's memory only, and after a restart the first keyed Connect
 * from anywhere started a session, armed or not. With it the pilot's own PC gets its session back at once, and any other
 * PC only by the takeover rule ({@link Takeover}: the flight controller known disarmed, the pilot silent 10 s - counted
 * from the app's start at the latest, {@link SessionGate#remembered}).
 * <p>
 * Kept while a pilot's session is up in normal power mode (the phone writes it once a session, off the stick path);
 * forgotten at a clean DISCONNECT from the PC and when the phone goes to STANDBY (and kept again should that session fly
 * on). One line: {@code <address bytes, hex>|<port>|<session id, -1: none>}.
 * <p>
 * Pure: the phone's Udp and the virtual fleet's phone keep it, the PC's tests check it.
 */
public final class KeptPilot {
    /** The pilot PC's address (4 or 16 bytes), its port and its session id ({@link SessionGate#NO_ID}: none). */
    public final byte[] address;
    public final int port;
    public final long sessionId;

    private KeptPilot(byte[] address, int port, long sessionId) {
        this.address = address;
        this.port = port;
        this.sessionId = sessionId;
    }

    /** The line kept; null for an address or port that is none. */
    public static String encode(byte[] address, int port, long sessionId) {
        if (address == null || address.length != 4 && address.length != 16 || port < 1 || port > 65535) return null;
        StringBuilder sb = new StringBuilder(address.length * 2 + 20);
        for (byte b : address) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        long id = sessionId >= 0 && sessionId <= 0xFFFFFFFFL ? sessionId : SessionGate.NO_ID;
        return sb.append('|').append(port).append('|').append(id).toString();
    }

    /** The pilot a kept line says; null for none, or a line that is not whole. */
    public static KeptPilot decode(String line) {
        if (line == null) return null;
        String[] p = line.split("\\|", -1);
        if (p.length != 3 || p[0].length() != 8 && p[0].length() != 32) return null;
        byte[] a = new byte[p[0].length() / 2];
        for (int i = 0; i < a.length; i++) {
            int hi = Character.digit(p[0].charAt(2 * i), 16), lo = Character.digit(p[0].charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            a[i] = (byte) (hi << 4 | lo);
        }
        try {
            int port = Integer.parseInt(p[1]);
            long id = Long.parseLong(p[2]);
            if (port < 1 || port > 65535 || id != SessionGate.NO_ID && (id < 0 || id > 0xFFFFFFFFL)) return null;
            return new KeptPilot(a, port, id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
