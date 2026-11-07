package de.droiddrone.pc.observe;

import java.awt.Rectangle;

/**
 * OBSERVATION's grid (1.0.48): one tile alone, two side by side, four as 2 x 2, five or six as 3 x 2 - by count; one
 * tile enlarged (a double-click) takes the whole area and the rest go out of sight until it is double-clicked again.
 */
public final class TileGrid {
    private TileGrid() {
    }

    /** {columns, rows} for n tiles: 1 1x1, 2 2x1, 3-4 2x2, 5-6 3x2; none for 0. */
    public static int[] shape(int n) {
        if (n <= 0) return new int[]{0, 0};
        if (n == 1) return new int[]{1, 1};
        if (n == 2) return new int[]{2, 1};
        if (n <= 4) return new int[]{2, 2};
        return new int[]{3, 2};
    }

    /**
     * Where each of n tiles goes in a width x height area, gap pixels between them; row by row, left to right. With
     * enlarged a tile's index, that one fills the area and the others get an empty rectangle.
     */
    public static Rectangle[] layout(int n, int width, int height, int gap, int enlarged) {
        Rectangle[] out = new Rectangle[Math.max(0, n)];
        if (n <= 0) return out;
        if (enlarged >= 0 && enlarged < n) {
            for (int i = 0; i < n; i++) out[i] = i == enlarged ? new Rectangle(0, 0, width, height) : new Rectangle();
            return out;
        }
        int[] s = shape(n);
        int cols = s[0], rows = s[1];
        int cw = Math.max(1, (width - gap * (cols - 1)) / cols);
        int rh = Math.max(1, (height - gap * (rows - 1)) / rows);
        for (int i = 0; i < n; i++) {
            int c = i % cols, r = i / cols;
            out[i] = new Rectangle(c * (cw + gap), r * (rh + gap), cw, rh);
        }
        return out;
    }

    /** The tile under (x, y), or -1. */
    public static int at(Rectangle[] tiles, int x, int y) {
        for (int i = 0; i < tiles.length; i++) if (tiles[i] != null && !tiles[i].isEmpty() && tiles[i].contains(x, y)) return i;
        return -1;
    }
}
