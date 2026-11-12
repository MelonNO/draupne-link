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
 * then - but never longer than {@link #DISMISS_CAP_MS}: a scene still moving after that alerts again.
 * <p>
 * pc.50: an event is never longer than its clip. At {@link #MAX_CLIP_MS} it ENDS (it only stopped its clip and went
 * on: a scene that kept moving - wind, an animal, for hours - rang once at its start and recorded 15 min, and a person
 * arriving later got no ring and no clip). The next detection starts a new event: its own alert, pictures before it
 * and clip - so a scene that moves is recorded all along and rings at least every 15 min. A dismissed event waits for
 * its silence to end first (at most {@link #DISMISS_CAP_MS} more), so a DISMISS is never answered with a ring at once.
 * And motion that is new alerts again in an event nobody dismissed too ({@link #AGAIN_EVERY_MS} apart at least).
 * <p>
 * Pure and single-threaded (the phone's overwatch thread): the PC's tests drive it with made-up times.
 */
public final class OverwatchEvent {
    public static final long QUIET_MS = 30_000;
    public static final long DISMISS_CAP_MS = 10 * 60_000;
    public static final long MAX_CLIP_MS = 15 * 60_000;

    /** What the caller does now. */
    public enum Action { NONE, START, ALERT_AGAIN, ENDED }

    private boolean active;
    private int event;
    private int alertNo;
    private long startedAt, lastDetectionAt, dismissedAt = -1;
    /** pc.50: when this event last alerted (at its start, or again), and new motion seen since that hasn't rung yet. */
    private long alertedAt;
    private boolean newWaiting;

    /**
     * A detection now. START: a new event (alert, record, preview); ALERT_AGAIN: new motion, or still moving 10 min
     * after a dismiss (the alert once more, the same event); NONE: part of the running event.
     */
    public Action onDetection(long now) {
        return onDetection(now, Float.NaN, Float.NaN);
    }

    /**
     * pc.50 (the pilot: "new motion can alert again"): a dismissed event was silent for up to 10 min whatever moved -
     * a second person arriving while the dismissed dog still wandered raised nothing. Now motion that is new alerts
     * again: it starts after {@link #NEW_AFTER_QUIET_MS} without any, or far ({@link #NEW_FAR} of the picture) from
     * where the last was - never within {@link #NEW_MIN_AFTER_DISMISS_MS} of the dismiss itself.
     * @param cx,cy the middle of the detection's box, 0..1 of the picture (NaN: not known)
     */
    public static final long NEW_AFTER_QUIET_MS = 5000, NEW_MIN_AFTER_DISMISS_MS = 10_000;
    public static final float NEW_FAR = 0.3f;
    /**
     * pc.50: the same in an event nobody dismissed (an acknowledged alert rang once, then never again while anything
     * kept the event alive) - but not a ring for every flicker: an event rings once in {@link #AGAIN_EVERY_MS} at most,
     * its start counted. New motion in between is remembered and rings when that time is over, if the scene still
     * moves then. (After a dismiss the dismiss's own rule holds: the pilot has looked.)
     */
    public static final long AGAIN_EVERY_MS = 60_000;
    private float lastX = Float.NaN, lastY = Float.NaN;

    public Action onDetection(long now, float cx, float cy) {
        long quiet = now - lastDetectionAt;
        boolean far = !Float.isNaN(cx) && !Float.isNaN(lastX) && Math.hypot(cx - lastX, cy - lastY) > NEW_FAR;
        lastX = cx;
        lastY = cy;
        if (!active) {
            active = true;
            event = (event + 1) & 0xffff;
            if (event == 0) event = 1;
            alertNo = 0;
            startedAt = now;
            lastDetectionAt = now;
            dismissedAt = -1;
            alertedAt = now;
            newWaiting = false;
            return Action.START;
        }
        lastDetectionAt = now;
        boolean newMotion = quiet >= NEW_AFTER_QUIET_MS || far;
        // at the clip's cap the tick ends the event now (a dismissed one: once its silence is over) - no ring from this
        // one just before; the next detection is the new event's first
        boolean capped = now - startedAt >= MAX_CLIP_MS;
        if (dismissedAt >= 0) {
            if (now - dismissedAt < DISMISS_CAP_MS && !(newMotion && now - dismissedAt >= NEW_MIN_AFTER_DISMISS_MS)) return Action.NONE;
            dismissedAt = -1;
            return capped ? Action.NONE : again(now);
        }
        if (capped) return Action.NONE;
        if (newMotion) newWaiting = true;
        return newWaiting && now - alertedAt >= AGAIN_EVERY_MS ? again(now) : Action.NONE;
    }

    private Action again(long now) {
        alertNo = (alertNo + 1) & 0xff;
        alertedAt = now;
        newWaiting = false;
        return Action.ALERT_AGAIN;
    }

    /** The PC dismissed the alert: the event goes on quietly. */
    public void dismiss(long now) {
        if (!active) return;
        dismissedAt = now;
        newWaiting = false; // the pilot saw what there is
    }

    /**
     * Every tick: ENDED (once) after QUIET_MS without a detection - or, pc.50, at the clip's cap: a dismissed event
     * when its silence is over, but never more than DISMISS_CAP_MS past the cap.
     */
    public Action tick(long now) {
        if (!active) return Action.NONE;
        long ran = now - startedAt;
        if (now - lastDetectionAt >= QUIET_MS || ran >= MAX_CLIP_MS && (!isSilenced(now) || ran >= MAX_CLIP_MS + DISMISS_CAP_MS)) {
            abandon();
            return Action.ENDED;
        }
        return Action.NONE;
    }

    /** The phone left overwatch (woken, the floor): the event ends with it. */
    public void abandon() {
        active = false;
        dismissedAt = -1;
        newWaiting = false;
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

    /**
     * pc.50: how long a PC that doesn't answer is still told once another has it. The alert and the motion's end went
     * to every PC until ALL had them: one PC switched off (of up to ten that asked this phone's name in a day) kept the
     * phone sending through every event and for 30 min after it - its radio never idle.
     */
    public static final long STRAGGLER_MS = 60_000;

    /**
     * pc.50: whether an alert (or a motion's end) said sinceSaidMs ago is still to be sent again: someone lacks it -
     * and nobody has it yet, or it is younger than {@link #STRAGGLER_MS}.
     * @param allHave every PC told has acknowledged it
     * @param anyHas one at least has
     */
    public static boolean resendOwed(boolean allHave, boolean anyHas, long sinceSaidMs) {
        return !allHave && (!anyHas || sinceSaidMs < STRAGGLER_MS);
    }
}
