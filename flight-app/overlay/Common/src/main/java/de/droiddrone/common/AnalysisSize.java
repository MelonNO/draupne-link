/*
 *  This file is part of DRAUPNE Link / DRAUPNE Flight (based on DroidDrone).
 *
 *  It is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 *  version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details. You should have received a copy of the GNU General Public License along with it. If not, see
 *  <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

/**
 * The size of the motion detection's small YUV picture beside the video (the phone's InternalCamera; Java 11, both
 * apps so the PC tests it). It must have the video's shape, so a box on it is a box on the video.
 * <p>
 * pc.43: up to pc.42 only sizes 240 - 720 wide counted. A phone that lists 16:9 only at 1280x720 and 1920x1080 (the
 * pilot's: the same list that made pc.27 open 640x480 for the small video tiers) got none for any 16:9 video - no
 * picture to analyse, so no motion detection live and nothing to watch with in overwatch, on every 16:9 session. Now
 * the smallest of the shape from {@link #MIN_WIDTH} up is taken, however wide (MotionWatch samples every n-th pixel
 * down to 320 across, so a bigger picture costs the camera's bandwidth, not the analysis).
 */
public final class AnalysisSize {
    /** Narrower pictures say too little. */
    public static final int MIN_WIDTH = 240;
    /** How close the shape must be (width / height). */
    static final double SHAPE_TOLERANCE = 0.03;

    private AnalysisSize() {
    }

    /**
     * @param sizes the camera's YUV sizes, each {width, height} (null: none)
     * @return {width, height}, or null when the camera lists no size of the video's shape at least MIN_WIDTH wide
     */
    public static int[] pick(int[][] sizes, int videoWidth, int videoHeight) {
        if (sizes == null || videoWidth <= 0 || videoHeight <= 0) return null;
        double aspect = (double) videoWidth / videoHeight;
        int[] best = null;
        for (int[] s : sizes) {
            if (s == null || s.length < 2 || s[0] < MIN_WIDTH || s[1] <= 0) continue;
            if (Math.abs((double) s[0] / s[1] - aspect) > SHAPE_TOLERANCE) continue;
            if (best == null || s[0] < best[0]) best = s;
        }
        return best == null ? null : new int[]{best[0], best[1]};
    }

    /** pc.50: overwatch's own picture is at least this wide where it shows the video's view. */
    public static final int WATCH_WIDTH = 320;

    /**
     * pc.50, overwatch's picture alone (the watch-only session): the YUV size with the fewest pixels whose centred cut
     * to the video's shape is at least {@link #WATCH_WIDTH} wide - any shape, since every stream of a session is a
     * centred crop of the same region (Camera2), so the cut is the video's view. The pilot's phone lists 16:9 only from
     * 1280x720: overwatch asked the camera for 1280x720 at 30 fps (its HAL at 1.6 cores in the dark) to use 320x180 of
     * it; now 320x240, cut to 320x180. Today's {@link #pick} when it has fewer pixels, or nothing else fits.
     * @return {width, height}, or null as pick
     */
    public static int[] pickWatch(int[][] sizes, int videoWidth, int videoHeight) {
        int[] same = pick(sizes, videoWidth, videoHeight);
        if (sizes == null || videoWidth <= 0 || videoHeight <= 0) return same;
        int[] best = same;
        long bestPixels = same == null ? Long.MAX_VALUE : (long) same[0] * same[1];
        for (int[] s : sizes) {
            if (s == null || s.length < 2 || s[0] <= 0 || s[1] <= 0) continue;
            int[] cut = centreCrop(s[0], s[1], videoWidth, videoHeight);
            if (cut[2] < WATCH_WIDTH) continue;
            long pixels = (long) s[0] * s[1];
            if (pixels < bestPixels) {
                bestPixels = pixels;
                best = new int[]{s[0], s[1]};
            }
        }
        return best;
    }

    /**
     * The centred part of a w x h picture with the video's shape: {x, y, width, height}, even numbers (the chroma
     * planes are half size). A picture of the video's shape is whole.
     */
    public static int[] centreCrop(int w, int h, int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0 || w <= 0 || h <= 0) return new int[]{0, 0, w, h};
        double aspect = (double) videoWidth / videoHeight, own = (double) w / h;
        if (Math.abs(own - aspect) <= SHAPE_TOLERANCE) return new int[]{0, 0, w, h};
        int cw = w, ch = h;
        if (own > aspect) cw = (int) Math.round(h * aspect) & ~1;
        else ch = (int) Math.round(w / aspect) & ~1;
        return new int[]{(w - cw) / 2 & ~1, (h - ch) / 2 & ~1, cw, ch};
    }

    /**
     * pc.50: the frame-rate range for overwatch's picture alone - the lowest top the camera lists from 10 up (the
     * detector takes 8 pictures a second), the steadiest of those; null: none, keep the video's.
     * @param ranges each {lower, upper}
     */
    public static int[] watchFps(int[][] ranges) {
        if (ranges == null) return null;
        int[] best = null;
        for (int[] r : ranges) {
            if (r == null || r.length < 2 || r[1] < 10 || r[0] <= 0 || r[0] > r[1]) continue;
            if (best == null || r[1] < best[1] || r[1] == best[1] && r[0] > best[0]) best = r;
        }
        return best == null ? null : new int[]{best[0], best[1]};
    }

    /**
     * A plane of a camera picture made step times smaller each way into a packed ow x oh array: each output pixel the
     * mean of its step x step block (one in a step x step block would alias fine texture into flicker as the lens's
     * stabiliser moves it - foreground to the detector). Step 1 copies. Bytes past the buffer's limit count as 0.
     */
    public static void shrink(java.nio.ByteBuffer src, int rowStride, int pixelStride, int step, byte[] dst, int ow, int oh) {
        shrink(src, 0, rowStride, pixelStride, step, dst, ow, oh);
    }

    /** pc.50: from offset on (the first byte of a centred cut). */
    public static void shrink(java.nio.ByteBuffer src, int offset, int rowStride, int pixelStride, int step, byte[] dst, int ow, int oh) {
        int limit = src.limit();
        if (step <= 1) {
            for (int yy = 0; yy < oh; yy++) {
                int row = offset + yy * rowStride, o = yy * ow;
                for (int xx = 0; xx < ow; xx++) {
                    int at = row + xx * pixelStride;
                    dst[o + xx] = at < limit ? src.get(at) : 0;
                }
            }
            return;
        }
        int n = step * step;
        for (int yy = 0; yy < oh; yy++) {
            int o = yy * ow;
            for (int xx = 0; xx < ow; xx++) {
                int sum = 0;
                for (int j = 0; j < step; j++) {
                    int row = offset + (yy * step + j) * rowStride + xx * step * pixelStride;
                    for (int i = 0; i < step; i++) {
                        int at = row + i * pixelStride;
                        if (at < limit) sum += src.get(at) & 0xff;
                    }
                }
                dst[o + xx] = (byte) (sum / n);
            }
        }
    }
}
