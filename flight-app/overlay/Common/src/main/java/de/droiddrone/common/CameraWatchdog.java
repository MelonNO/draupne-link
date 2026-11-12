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
 * pc.49, overwatch's camera watchdog. Overwatch watched with no pictures until the pilot put it to overwatch again
 * (2026-11-11: a phone that boxed the pilot 5 m off while connected never alerted in overwatch; its check-in said it
 * "sees nothing"): a camera taken away while the screen was off, or a session refused, was never opened again.
 * <p>
 * Now {@link #BLIND_MS} without a picture opens it again, then after 15 s, 30 s, 60 s and every 120 s while none
 * come. The PC is told once that it went blind, and once that pictures came again. One thread (overwatch's worker);
 * times are monotonic ms.
 * <p>
 * pc.50: an event's session counts too (it was left out: a session that records without the picture, pc.47, or one
 * that never started, left the phone blind for the whole event and nobody was told). The PC is told the same way
 * ({@link Action#SAY_BLIND}) - but the camera is never opened again under a recording; the caller decides. Each
 * change between the watch's session and an event's starts the count over (a new session takes its moment).
 */
public final class CameraWatchdog {
    public static final long BLIND_MS = 5000, REOPEN_FIRST_MS = 15_000, REOPEN_MAX_MS = 120_000;

    /** SAY_BLIND (pc.50): blind in an event's session - the PC is told, the camera is not opened again by this. */
    public enum Action { NONE, REOPEN, REOPEN_AND_SAY_BLIND, SAY_BACK, SAY_BLIND }

    private long blindSince = -1, reopenAt;
    private int reopens;
    private boolean blindSaid, wasEvent;

    /** A new watch: nothing counted yet. */
    public void reset() {
        blindSince = -1;
        reopenAt = 0;
        reopens = 0;
        blindSaid = false;
        wasEvent = false;
    }

    /**
     * @param pictures the motion detection had a picture lately
     * @param eventVideo an event's session runs (it may record without the picture)
     */
    public Action tick(long now, boolean pictures, boolean eventVideo) {
        if (eventVideo != wasEvent) { // the session changes: counted from here
            wasEvent = eventVideo;
            blindSince = -1;
        }
        if (pictures) {
            blindSince = -1;
            if (!blindSaid) return Action.NONE;
            blindSaid = false;
            reopens = 0;
            reopenAt = 0;
            return Action.SAY_BACK;
        }
        if (blindSince < 0) blindSince = now;
        if (now - blindSince < BLIND_MS) return Action.NONE;
        if (eventVideo) { // pc.50: said once; its recording is not broken off from here
            if (blindSaid) return Action.NONE;
            blindSaid = true;
            return Action.SAY_BLIND;
        }
        if (now < reopenAt) return Action.NONE;
        reopens++;
        reopenAt = now + Math.min(REOPEN_MAX_MS, REOPEN_FIRST_MS << Math.min(3, reopens - 1));
        if (blindSaid) return Action.REOPEN;
        blindSaid = true;
        return Action.REOPEN_AND_SAY_BLIND;
    }

    /** How long without a picture (0 when it has them). */
    public long blindFor(long now) {
        return blindSince < 0 ? 0 : now - blindSince;
    }

    /** The camera opened again this many times since pictures last came. */
    public int reopens() {
        return reopens;
    }
}
