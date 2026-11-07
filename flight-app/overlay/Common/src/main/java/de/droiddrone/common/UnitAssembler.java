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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DRAUPNE Link 1.0.47: puts the overwatch preview's units back together from their fragments
 * ({@link OverwatchProtocol#fragments}): each fragment says the unit's size and where it sits, so they may come in any
 * order. A unit not complete within {@link #MAX_OPEN} newer ones is given up (the preview is a glance, never a
 * recording that waits). Single-threaded (the PC's overwatch thread).
 */
public final class UnitAssembler {
    public static final int MAX_OPEN = 8;
    /** A unit bigger than this is not taken (a key frame at 1 Mbit/s is ~60 KB; a pre-roll picture ~10 KB). */
    public static final int MAX_UNIT = 2 * 1024 * 1024;

    /** A complete unit. */
    public static final class Unit {
        public final int event, unit, type;
        public final byte[] data;

        Unit(int event, int unit, int type, byte[] data) {
            this.event = event;
            this.unit = unit;
            this.type = type;
            this.data = data;
        }
    }

    private static final class Open {
        final int type;
        final byte[] data;
        final boolean[] have;
        int missing;

        Open(int type, int size) {
            this.type = type;
            this.data = new byte[size];
            int pieces = Math.max(1, (size + OverwatchProtocol.FRAGMENT - 1) / OverwatchProtocol.FRAGMENT);
            this.have = new boolean[pieces];
            this.missing = pieces;
        }
    }

    private final Map<Long, Open> open = new LinkedHashMap<>();
    private long given;

    /** @return the unit when this fragment completed it, else null */
    public Unit add(OverwatchProtocol.Fragment f) {
        if (f.total > MAX_UNIT || f.offset % OverwatchProtocol.FRAGMENT != 0) return null;
        long key = ((long) f.event << 16) | f.unit;
        Open o = open.get(key);
        if (o == null) {
            if (f.total == 0) return new Unit(f.event, f.unit, f.type, new byte[0]);
            o = new Open(f.type, (int) f.total);
            open.put(key, o);
            while (open.size() > MAX_OPEN) {
                Iterator<Long> it = open.keySet().iterator();
                it.next();
                it.remove();
                given++;
            }
        }
        if (o.data.length != f.total) return null; // another unit under the same number (a new event, a restart)
        int piece = (int) (f.offset / OverwatchProtocol.FRAGMENT);
        if (piece >= o.have.length || o.have[piece]) return null;
        System.arraycopy(f.data, f.dataOffset, o.data, (int) f.offset, f.dataLength);
        o.have[piece] = true;
        if (--o.missing > 0) return null;
        open.remove(key);
        return new Unit(f.event, f.unit, o.type, o.data);
    }

    /** Units given up incomplete so far. */
    public long givenUp() {
        return given;
    }

    public void clear() {
        open.clear();
    }

    /** For tests: whether the unit is still being put together. */
    boolean isOpen(int event, int unit) {
        return open.containsKey(((long) event << 16) | unit);
    }
}
