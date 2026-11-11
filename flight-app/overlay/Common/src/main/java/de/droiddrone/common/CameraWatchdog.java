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
 * come. The PC is told once that it went blind, and once that pictures came again. While an event records, its own
 * session may lack the picture (pc.47): not counted then. One thread (overwatch's worker); times are monotonic ms.
 */
public final class CameraWatchdog {
    public static final long BLIND_MS = 5000, REOPEN_FIRST_MS = 15_000, REOPEN_MAX_MS = 120_000;

    public enum Action { NONE, REOPEN, REOPEN_AND_SAY_BLIND, SAY_BACK }

    private long blindSince = -1, reopenAt;
    private int reopens;
    private boolean blindSaid;

    /** A new watch: nothing counted yet. */
    public void reset() {
        blindSince = -1;
        reopenAt = 0;
        reopens = 0;
        blindSaid = false;
    }

    /**
     * @param pictures the motion detection had a picture lately
     * @param eventVideo an event's session runs (it may record without the picture)
     */
    public Action tick(long now, boolean pictures, boolean eventVideo) {
        if (eventVideo) {
            blindSince = -1;
            return Action.NONE;
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
        if (now - blindSince < BLIND_MS || now < reopenAt) return Action.NONE;
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
