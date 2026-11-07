package de.droiddrone.pc;

import de.droiddrone.common.MotionRecord;
import de.droiddrone.pc.map.CameraFootprint;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The motion detection on the map (1.0.46, the pilot's choice): for each thing the phone saw travel, a line from the
 * drone along its direction - the drone's heading turned by where the box sits across the picture, through the
 * camera's angle (camera.hfovDeg) and tilt (camera.tiltDeg) - and, where that ray meets the terrain, an estimated
 * ground point. Kept {@link #KEEP_MS} after the box was last seen, fading. Pure bookkeeping: the geometry is
 * {@link CameraFootprint#ray}, the pose CameraView's.
 */
public final class DetectionMarks {
    /** A mark stays this long after its box was last seen. */
    public static final long KEEP_MS = 30_000;

    /**
     * One detection's mark: from the drone at (droneLat, droneLon) along bearingDeg; ground {lat, lon, height m} where
     * the ray meets the terrain (an estimate: null when it shows sky or ground past the camera's reach); light = found
     * in the dark; seenAt MonoClock ms.
     */
    public record Mark(int id, boolean light, double droneLat, double droneLon, double bearingDeg, double[] ground,
                       double rangeM, boolean flatGround, long seenAt) {
        /** 1 while fresh, falling to 0.25 at KEEP_MS (still to be seen until it goes). */
        public float alpha(long now) {
            long age = Math.max(0, now - seenAt);
            return age >= KEEP_MS ? 0 : 1 - 0.75f * age / KEEP_MS;
        }
    }

    private final Map<Integer, Mark> marks = new LinkedHashMap<>();

    /**
     * Where the screen shows a box's centre: (u, v), -1..1 left to right and top to bottom, with the picture mirrored
     * (front camera) and turned (upside down) as the video view draws it - the camera's own sense of left and right.
     */
    public static double[] pictureUv(MotionRecord.Box b, boolean flipX, boolean flipY) {
        double x = b.x + b.w / 2, y = b.y + b.h / 2;
        if (flipX) x = 1 - x;
        if (flipY) y = 1 - y;
        return new double[]{2 * x - 1, 2 * y - 1};
    }

    /** The box with this id was seen now: its mark follows it (the newest direction wins). */
    public synchronized void put(int id, boolean light, double droneLat, double droneLon, CameraFootprint.Ray ray, long now) {
        if (ray == null) return;
        marks.put(id, new Mark(id, light, droneLat, droneLon, ray.bearingDeg(), ray.ground(), ray.slantRangeM(), ray.flatGround(), now));
    }

    /** The marks younger than KEEP_MS, oldest first. */
    public synchronized List<Mark> marks(long now) {
        for (Iterator<Mark> it = marks.values().iterator(); it.hasNext(); ) {
            if (now - it.next().seenAt() >= KEEP_MS) it.remove();
        }
        return new ArrayList<>(marks.values());
    }

    public synchronized void clear() {
        marks.clear();
    }
}
