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
 * pc.50: whether the motion detection counts as running through a camera session change. Overwatch changes the
 * camera's session at every event's start and end (the recording's and the preview's surfaces in, then out) and at
 * the clip's cap; the camera reads "not started" for a moment each time. The detection's 0.1 s tick took that for a
 * stop and, on the start after it, learnt the picture anew: 3 s in which whoever stood in view became background,
 * then 20 s of stricter rules - someone working at the drone was alerted once and never again. A gap shorter than
 * {@link #GAP_MS} is the same run: nothing is reset, and the camera keeps its analysis picture switched on.
 * Pure: the phone's MotionWatch asks it, the PC's tests check it.
 */
public final class WatchRun {
    /** A session change takes 0.1-0.5 s; a camera reopened by the watchdog up to about 2.6 s. */
    public static final long GAP_MS = 4000;

    private WatchRun() {
    }

    /** The detector's pictures are about this far apart (8 a second). */
    public static final long FRAME_EVERY_MS = 125;

    /**
     * pc.50: how old the last picture taken must be for the next frame to be taken, at a camera of fps frames a second.
     * A fixed 125 ms against 15 frames a second (overwatch's own session: one every 66.7 ms) left 8 ms for the second
     * frame - measured after the copy, it mostly came "too early" and the third was taken: 5 pictures a second, not
     * 7.5. Now the gate lies half a frame before the frame meant - the one nearest {@link #FRAME_EVERY_MS} after the
     * last: 100 ms at 15 fps (every second frame), 117 ms at 30 fps (every fourth, as before), 104 ms at 24 fps.
     * @param fps the session's frame rate (its range's top); 0 or less: not known - as for 30
     */
    public static long frameGateMs(int fps) {
        int f = fps <= 0 ? 30 : fps;
        long frames = Math.max(1, Math.round(FRAME_EVERY_MS * f / 1000.0));
        return Math.round((frames - 0.5) * 1000.0 / f);
    }

    /**
     * @param ready       asked for, the FC disarmed, a camera there
     * @param started     that camera's session runs
     * @param wasRunning  the detection ran at the last tick
     * @param sameCamera  it is the camera the detection ran on
     * @param sinceStartedMs since that camera was last seen started
     */
    public static boolean runs(boolean ready, boolean started, boolean wasRunning, boolean sameCamera, long sinceStartedMs) {
        if (!ready) return false;
        if (started) return true;
        return wasRunning && sameCamera && sinceStartedMs < GAP_MS;
    }
}
