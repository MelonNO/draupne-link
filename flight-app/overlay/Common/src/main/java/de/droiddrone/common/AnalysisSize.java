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

    /**
     * A plane of a camera picture made step times smaller each way into a packed ow x oh array: each output pixel the
     * mean of its step x step block (one in a step x step block would alias fine texture into flicker as the lens's
     * stabiliser moves it - foreground to the detector). Step 1 copies. Bytes past the buffer's limit count as 0.
     */
    public static void shrink(java.nio.ByteBuffer src, int rowStride, int pixelStride, int step, byte[] dst, int ow, int oh) {
        int limit = src.limit();
        if (step <= 1) {
            for (int yy = 0; yy < oh; yy++) {
                int row = yy * rowStride, o = yy * ow;
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
                    int row = (yy * step + j) * rowStride + xx * step * pixelStride;
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
