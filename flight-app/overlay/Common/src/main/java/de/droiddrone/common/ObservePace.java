/*
 *  This file is part of DRAUPNE Flight, the phone app of DRAUPNE Link (based on DroidDrone).
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
 * DRAUPNE Flight pc.37: what the phone's observation does when (moved out of its Udp, the times unchanged) - the
 * picture applied when the observers want another, the camera started again when it stands still, the encoder's step,
 * the motion detection's request, the observer record, the FC's info, a newcomer's key frame. Each answer that says
 * "now" counts from that moment (not from when it was due: a late tick shifts the next ones, as before). Pure: Udp
 * passes its monotonic clock and does the thing; the PC's tests check the times.
 * <p>
 * Not thread-safe, as the fields it replaces: the telemetry thread's (the phone's observation tick), but for
 * {@link #keyFrame}, which is the receive thread's alone.
 */
public final class ObservePace {
    /** The encoder's step is set again this often: back up after the phone's own brake. */
    public static final long STEP_AGAIN_MS = 10_000;
    /** The motion detection is asked for this often while an observer wants it. */
    public static final long DETECTION_EVERY_MS = 1000;
    /** The observer record ({@link Observe#RECORD}): five times a second, to the observers - and (pc.48) to a pilot. */
    public static final long RECORD_EVERY_MS = 200;
    /** pc.43: the FcInfo packet to the observers - the PC reads the FC's records only knowing the FC (each tile's OSD). */
    public static final long FC_INFO_EVERY_MS = 1000;
    /** A new observer starts at a key frame; one a second at most. */
    public static final long KEY_FRAME_EVERY_MS = 1000;

    /** The picture applied for the observers; null before the first. */
    private Observe.Request applied;
    private long startTriedMs, stepAt, detectionAt, recordAt, fcInfoAt, pilotRecordAt;
    private int step = -1;
    /** The receive thread's. */
    private long keyFrameAt;

    /** The first observer is here: the encoder's step is set at this tick, whatever it was set to before. */
    public void begin() {
        step = -1;
    }

    /** The last observer went: the next one's picture is applied anew. */
    public void end() {
        applied = null;
    }

    /**
     * @return the picture wanted is not the one applied (its size, frame rate or bitrate; or none is yet): it is now,
     * and the caller sets the camera and the encoder to it
     */
    public boolean picture(Observe.Request want, long now) {
        if (applied == null || applied.width != want.width || applied.height != want.height
                || applied.fps != want.fps || applied.maxKbps != want.maxKbps) {
            applied = want;
            startTriedMs = now;
            return true;
        }
        return false;
    }

    /**
     * The picture is the one applied, but the camera stands still (taken away, a watchdog that gave up) and no start is
     * under way.
     * @param waitMs what a start gets for its first frame
     * @return it is started again now: more than waitMs since the picture was applied, or since the last such start
     */
    public boolean startAgain(long now, long waitMs) {
        if (now - startTriedMs > waitMs) {
            startTriedMs = now;
            return true;
        }
        return false;
    }

    /**
     * @param wanted the step for the observers' bitrate ({@link Observe#stepFor})
     * @return the encoder is set to it now: another step than the last set, or {@link #STEP_AGAIN_MS} since
     */
    public boolean step(int wanted, long now) {
        if (wanted != step || now - stepAt >= STEP_AGAIN_MS) {
            step = wanted;
            stepAt = now;
            return true;
        }
        return false;
    }

    /**
     * @param sensitivity the highest any observer asks for (0: none wants the detection)
     * @return the motion detection is asked for now
     */
    public boolean detection(int sensitivity, long now) {
        if (sensitivity > 0 && now - detectionAt >= DETECTION_EVERY_MS) {
            detectionAt = now;
            return true;
        }
        return false;
    }

    /** @return the observer record goes to the observers now */
    public boolean record(long now) {
        if (now - recordAt >= RECORD_EVERY_MS) {
            recordAt = now;
            return true;
        }
        return false;
    }

    /** pc.48: @return the observer record goes to the pilot now (asked only while one is connected) */
    public boolean pilotRecord(long now) {
        if (now - pilotRecordAt >= RECORD_EVERY_MS) {
            pilotRecordAt = now;
            return true;
        }
        return false;
    }

    /** pc.43: whether the FcInfo packet is due. Counted from {@link #fcInfoKnown}: no FC known yet, it stays due. */
    public boolean fcInfoDue(long now) {
        return now - fcInfoAt >= FC_INFO_EVERY_MS;
    }

    /** The FC's info was there when due (its packet goes now). */
    public void fcInfoKnown(long now) {
        fcInfoAt = now;
    }

    /** On the receive thread, for an OBSERVE that asks for the video's config: @return a key frame is asked for now */
    public boolean keyFrame(long now) {
        if (now - keyFrameAt >= KEY_FRAME_EVERY_MS) {
            keyFrameAt = now;
            return true;
        }
        return false;
    }
}
