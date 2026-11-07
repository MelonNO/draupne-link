package de.droiddrone.pc;

import de.droiddrone.common.MotionRecord;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's motion detection as the screen shows it (1.0.46, phone app pc.35): each box the DETECTION records
 * name, kept by its track id from one record to the next, solid while it keeps coming and fading out over
 * {@link #FADE_MS} once it stops (the phone sends a record five times a second while something is shown). Also what
 * the phone said of the detection itself: on, dark, no picture, paused for an armed drone.
 */
public final class MotionBoxes {
    /** A box is drawn solid this long after the last record that had it... */
    public static final long SOLID_MS = 250;
    /** ...and is gone this long after it. */
    public static final long FADE_MS = 1000;

    /** A box to draw: alpha 1 solid, falling to 0 at FADE_MS. */
    public record Shown(MotionRecord.Box box, float alpha, long seenAt) {}

    private record Seen(MotionRecord.Box box, long at) {}

    private final Map<Integer, Seen> boxes = new LinkedHashMap<>();
    private MotionRecord.Report last;
    private long lastAt;
    /** How many boxes with a new id came (the map's marks and the log count them). */
    private long newIds;

    /** A record from the phone, at now (MonoClock ms). */
    public synchronized void update(MotionRecord.Report r, long now) {
        last = r;
        lastAt = now;
        for (MotionRecord.Box b : r.boxes) {
            if (boxes.put(b.id, new Seen(b, now)) == null) newIds++;
        }
        forget(now);
    }

    /** The boxes to draw now, oldest id first. */
    public synchronized List<Shown> shown(long now) {
        forget(now);
        List<Shown> out = new ArrayList<>(boxes.size());
        for (Seen s : boxes.values()) out.add(new Shown(s.box(), alpha(now - s.at()), s.at()));
        return out;
    }

    static float alpha(long ageMs) {
        if (ageMs <= SOLID_MS) return 1;
        if (ageMs >= FADE_MS) return 0;
        return 1 - (float) (ageMs - SOLID_MS) / (FADE_MS - SOLID_MS);
    }

    private void forget(long now) {
        for (Iterator<Seen> it = boxes.values().iterator(); it.hasNext(); ) {
            if (now - it.next().at() >= FADE_MS) it.remove();
        }
    }

    /** The phone's last word on the detection, or null (none, or a phone app before pc.35, or the PC didn't ask). */
    public synchronized MotionRecord.Report last() {
        return last;
    }

    /** When it came (MonoClock ms), 0 = never. */
    public synchronized long lastAt() {
        return lastAt;
    }

    public synchronized long newIds() {
        return newIds;
    }
}
