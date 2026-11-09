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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * DRAUPNE Flight pc.36, overwatch sleep: the last {@link #KEEP_MS} of the motion detection's small picture as JPEGs,
 * {@link #EVERY_MS} apart - what led up to a detection, sent with the alert (the full-quality clip only starts once
 * the motion is seen). Kept cheap: two small JPEGs a second, never more than {@link #MAX_BYTES} in all.
 * Thread-safe (the analysis thread adds, the overwatch thread takes a copy).
 */
public final class PreRoll {
    public static final long KEEP_MS = 5000;
    public static final long EVERY_MS = 500;
    /** A picture of 320 x 180 at the quality used is 5-12 KB; this is room for twice the 10 of 5 s. */
    public static final int MAX_BYTES = 256 * 1024;

    public static final class Frame {
        public final long atMs;
        public final byte[] jpeg;

        public Frame(long atMs, byte[] jpeg) {
            this.atMs = atMs;
            this.jpeg = jpeg;
        }
    }

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private long lastAddedAt = Long.MIN_VALUE / 2;
    private int bytes;
    private boolean detectedBefore;
    /** A detection's first picture comes this soon after the last at most (one flickering on and off adds no flood). */
    static final long FIRST_MIN_MS = 100;

    /** A picture is wanted now: the last was EVERY_MS ago or more (the caller then makes the JPEG). */
    public synchronized boolean due(long now) {
        return now - lastAddedAt >= EVERY_MS;
    }

    /**
     * {@link #due} or the first picture of a detection (1.0.54): the picture that raises an alert is the last one
     * before it. Up to EVERY_MS older otherwise - something found as it came in wasn't in any of them.
     */
    public synchronized boolean due(long now, boolean detected) {
        boolean first = detected && !detectedBefore;
        detectedBefore = detected;
        return due(now) || first && now - lastAddedAt >= FIRST_MIN_MS;
    }

    public synchronized void add(long now, byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        lastAddedAt = now;
        frames.addLast(new Frame(now, jpeg));
        bytes += jpeg.length;
        trim(now);
    }

    /** The pictures of the last KEEP_MS before now, oldest first. */
    public synchronized List<Frame> snapshot(long now) {
        trim(now);
        return new ArrayList<>(frames);
    }

    public synchronized int size() {
        return frames.size();
    }

    public synchronized void clear() {
        frames.clear();
        bytes = 0;
        lastAddedAt = Long.MIN_VALUE / 2;
        detectedBefore = false;
    }

    private void trim(long now) {
        while (!frames.isEmpty() && (now - frames.peekFirst().atMs > KEEP_MS || bytes > MAX_BYTES)) {
            bytes -= frames.removeFirst().jpeg.length;
        }
    }
}
