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

/**
 * DRAUPNE Flight pc.36: one overwatch event on the phone, from the first detection to the motion's end - the pilot's
 * rules. A detection while quiet starts an event (the alert goes, the clip records). It ends {@link #QUIET_MS} after
 * the last detection (MOTION ENDED; the clip stops, the phone watches on). The PC's DISMISS silences the event until
 * then - but never longer than {@link #DISMISS_CAP_MS}: a scene still moving after that alerts again. A clip stops
 * at {@link #MAX_CLIP_MS} even while the motion goes on (the event and its silence don't).
 * Pure and single-threaded (the phone's overwatch thread): the PC's tests drive it with made-up times.
 */
public final class OverwatchEvent {
    public static final long QUIET_MS = 30_000;
    public static final long DISMISS_CAP_MS = 10 * 60_000;
    public static final long MAX_CLIP_MS = 15 * 60_000;

    /** What the caller does now. */
    public enum Action { NONE, START, ALERT_AGAIN, STOP_CLIP, ENDED }

    private boolean active;
    private int event;
    private int alertNo;
    private long startedAt, lastDetectionAt, dismissedAt = -1;
    private boolean clipStopped;

    /**
     * A detection now. START: a new event (alert, record, preview); ALERT_AGAIN: still moving 10 min after a dismiss
     * (the alert once more, the same event); NONE: part of the running event.
     */
    public Action onDetection(long now) {
        if (!active) {
            active = true;
            event = (event + 1) & 0xffff;
            if (event == 0) event = 1;
            alertNo = 0;
            startedAt = now;
            lastDetectionAt = now;
            dismissedAt = -1;
            clipStopped = false;
            return Action.START;
        }
        lastDetectionAt = now;
        if (dismissedAt >= 0 && now - dismissedAt >= DISMISS_CAP_MS) {
            dismissedAt = -1;
            alertNo = (alertNo + 1) & 0xff;
            return Action.ALERT_AGAIN;
        }
        return Action.NONE;
    }

    /** The PC dismissed the alert: the event goes on quietly. */
    public void dismiss(long now) {
        if (active) dismissedAt = now;
    }

    /** Every tick: ENDED after QUIET_MS without a detection (once), STOP_CLIP at the clip's cap (once). */
    public Action tick(long now) {
        if (!active) return Action.NONE;
        if (now - lastDetectionAt >= QUIET_MS) {
            active = false;
            dismissedAt = -1;
            return Action.ENDED;
        }
        if (!clipStopped && now - startedAt >= MAX_CLIP_MS) {
            clipStopped = true;
            return Action.STOP_CLIP;
        }
        return Action.NONE;
    }

    /** The phone left overwatch (woken, the floor): the event ends with it. */
    public void abandon() {
        active = false;
        dismissedAt = -1;
    }

    public boolean isActive() {
        return active;
    }

    /** Silent: dismissed and the cap not reached (no preview, no alert). */
    public boolean isSilenced(long now) {
        return active && dismissedAt >= 0 && now - dismissedAt < DISMISS_CAP_MS;
    }

    public int event() {
        return event;
    }

    public int alertNo() {
        return alertNo;
    }

    public long startedAt() {
        return startedAt;
    }

    public long lastDetectionAt() {
        return lastDetectionAt;
    }

    /** Numbering goes on from here (the phone remembers the last event across a restart of the watch). */
    public void continueFrom(int lastEvent) {
        event = lastEvent & 0xffff;
    }
}
