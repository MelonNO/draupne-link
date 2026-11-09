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

/** A 32-bit big-endian int in a packet, written and read in place (the link's byte order). */
public final class Bytes {
    public static void putInt(byte[] d, int at, int v) {
        d[at] = (byte) (v >> 24);
        d[at + 1] = (byte) (v >> 16);
        d[at + 2] = (byte) (v >> 8);
        d[at + 3] = (byte) v;
    }

    public static int readInt(byte[] d, int at) {
        return ((d[at] & 0xff) << 24) | ((d[at + 1] & 0xff) << 16) | ((d[at + 2] & 0xff) << 8) | (d[at + 3] & 0xff);
    }

    private Bytes() {
    }
}
