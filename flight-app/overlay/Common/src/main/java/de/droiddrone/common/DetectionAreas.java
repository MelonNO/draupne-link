/*
 *  This file is part of DRAUPNE Link's build of DRAUPNE Flight.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Areas of the picture with a motion detection sensitivity of their own (DRAUPNE Link 1.0.58, phone app pc.46; the
 * pilot: "the user can box a certain area to be a different sensitivity in cases where there is a constant movement
 * that is not interesting"). Each a box in the camera's own picture (0..1, before the PC mirrors or turns it, as
 * MotionRecord's boxes) and a level: 0 off, 1 low, 2 normal, 3 high. A later area lies over an earlier one.
 *
 * <p>On the wire, after DETECTION_SET's sensitivity byte (an older phone reads that byte only):
 * [n u8, at most {@link #MAX}] n x [x u16][y u16][w u16][h u16, of the picture, 0..65535][level u8].
 * No block: the phone keeps the areas it has; n = 0 clears them.
 */
public final class DetectionAreas {
    public static final int MAX = 8;
    /** Bytes an area takes on the wire. */
    static final int AREA_BYTES = 9;
    public static final DetectionAreas NONE = new DetectionAreas(Collections.<Area>emptyList());

    /** A box (0..1 of the picture) and its sensitivity. */
    public static final class Area {
        public final float x, y, w, h;
        public final int level;

        public Area(float x, float y, float w, float h, int level) {
            // on the wire's grid (1/65535), so what comes back is the same
            int x0 = u16(Math.min(x, x + w)), y0 = u16(Math.min(y, y + h));
            int x1 = u16(Math.max(x, x + w)), y1 = u16(Math.max(y, y + h));
            this.x = x0 / 65535f;
            this.y = y0 / 65535f;
            this.w = (x1 - x0) / 65535f;
            this.h = (y1 - y0) / 65535f;
            this.level = Math.max(0, Math.min(3, level));
        }

        public boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        public Area withLevel(int level) {
            return new Area(x, y, w, h, level);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Area)) return false;
            Area a = (Area) o;
            return a.level == level && u16(a.x) == u16(x) && u16(a.y) == u16(y) && u16(a.w) == u16(w) && u16(a.h) == u16(h);
        }

        @Override
        public int hashCode() {
            return ((u16(x) * 31 + u16(y)) * 31 + u16(w)) * 31 + u16(h) * 4 + level;
        }
    }

    private final List<Area> list;

    private DetectionAreas(List<Area> list) {
        this.list = list;
    }

    /** At most MAX: the first ones; areas with nothing inside them are left out. */
    public static DetectionAreas of(List<Area> areas) {
        List<Area> l = new ArrayList<>();
        if (areas != null) for (Area a : areas) if (a != null && a.w > 0 && a.h > 0 && l.size() < MAX) l.add(a);
        return l.isEmpty() ? NONE : new DetectionAreas(Collections.unmodifiableList(l));
    }

    public List<Area> list() {
        return list;
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    /** These areas and one more on top (the oldest dropped past MAX). */
    public DetectionAreas plus(Area a) {
        List<Area> l = new ArrayList<>(list);
        l.add(a);
        while (l.size() > MAX) l.remove(0);
        return of(l);
    }

    /** These areas without the i-th, or with it replaced by a (a null: removed). */
    public DetectionAreas with(int i, Area a) {
        List<Area> l = new ArrayList<>(list);
        if (i < 0 || i >= l.size()) return this;
        if (a == null) l.remove(i);
        else l.set(i, a);
        return of(l);
    }

    /** The topmost area at a point of the picture, -1 for none. */
    public int at(float px, float py) {
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).contains(px, py)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ the wire

    /** The block after DETECTION_SET's sensitivity byte: [n] n x [x][y][w][h][level]. */
    public byte[] encode() {
        byte[] b = new byte[1 + list.size() * AREA_BYTES];
        b[0] = (byte) list.size();
        int o = 1;
        for (Area a : list) {
            o = put16(b, o, u16(a.x));
            o = put16(b, o, u16(a.y));
            o = put16(b, o, u16(a.w));
            o = put16(b, o, u16(a.h));
            b[o++] = (byte) a.level;
        }
        return b;
    }

    /** The block from where in is; null when there is none or it is cut short (the areas then stay as they were). */
    public static DetectionAreas read(DataReader in) {
        if (in.getRemaining() < 1) return null;
        int n = in.readUnsignedByteAsInt();
        if (n > MAX || in.getRemaining() < n * AREA_BYTES) return null;
        List<Area> l = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            float x = in.readUnsignedShortAsInt() / 65535f, y = in.readUnsignedShortAsInt() / 65535f;
            float w = in.readUnsignedShortAsInt() / 65535f, h = in.readUnsignedShortAsInt() / 65535f;
            l.add(new Area(x, y, w, h, in.readUnsignedByteAsInt()));
        }
        return of(l);
    }

    // ------------------------------------------------------------------ the settings (DRAUPNE Link)

    private static final String[] WORDS = {"off", "low", "normal", "high"};

    /** "x,y,w,h,level;..." (level as a word), for a settings line. */
    public String toText() {
        StringBuilder s = new StringBuilder();
        for (Area a : list) {
            if (s.length() > 0) s.append(';');
            s.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%s", a.x, a.y, a.w, a.h, WORDS[a.level]));
        }
        return s.toString();
    }

    /** From toText's form; what can't be read is left out. */
    public static DetectionAreas parse(String text) {
        if (text == null || text.trim().isEmpty()) return NONE;
        List<Area> l = new ArrayList<>();
        for (String part : text.split(";")) {
            String[] f = part.trim().split(",");
            if (f.length != 5) continue;
            try {
                int level = -1;
                for (int k = 0; k < WORDS.length; k++) if (WORDS[k].equalsIgnoreCase(f[4].trim())) level = k;
                if (level < 0) continue;
                l.add(new Area(Float.parseFloat(f[0].trim()), Float.parseFloat(f[1].trim()), Float.parseFloat(f[2].trim()),
                        Float.parseFloat(f[3].trim()), level));
            } catch (NumberFormatException e) {
                // left out
            }
        }
        return of(l);
    }

    public static String word(int level) {
        return WORDS[Math.max(0, Math.min(3, level))].toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DetectionAreas && ((DetectionAreas) o).list.equals(list);
    }

    @Override
    public int hashCode() {
        return list.hashCode();
    }

    @Override
    public String toString() {
        return list.isEmpty() ? "no areas" : list.size() + (list.size() == 1 ? " area" : " areas");
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    private static int u16(float v) {
        return Math.round(clamp01(v) * 65535);
    }

    private static int put16(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 8);
        b[o + 1] = (byte) v;
        return o + 2;
    }
}
