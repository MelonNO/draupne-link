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
 * DRAUPNE Flight (Q-004): the phone's own screen while the drone is armed. The screen stays on in flight, and one
 * touch on the app's Stop ended its service and closed the flight controller's port - Betaflight's failsafe; the
 * touch lock was there, but switched on by hand, and gone when the app had opened itself again after an error. The
 * maintainer's rule (2026-11-22): "While armed the touch lock is on by itself; holding DISCONNECT for 3 s still
 * works."
 * <ul>
 * <li><b>Armed</b>, for the screen ({@link #armed}): the flight controller reads ARMED - or it is not known (a board
 * on USB that has not answered, a stale status: the phone's FcArmedState and {@link FcUnread}) while a PC flies it.
 * With no board on USB nothing here changes anything.</li>
 * <li><b>The lock</b> ({@link #look}, asked at every look at the screen's state): on by itself while armed - also in
 * an app that has just started anew, since it is decided by what the flight controller says now (read ARMED: at
 * once; not known with a PC flying: once that has lasted {@link #UNKNOWN_LOCK_AFTER_MS}). On by itself, it goes off
 * by itself {@link #UNLOCK_AFTER_MS} after the flight controller has read DISARMED without a break (not known is
 * not disarmed). A lock put on by hand stays as it was left, armed or not.</li>
 * <li><b>Unlocked by hand while armed</b> (the box held 2 s: somebody means it), the screen is the user's for
 * {@link #RELOCK_AFTER_MS}; still armed then, the lock is back by itself.</li>
 * <li><b>A press that ends the link or the service</b> (Stop) counts while armed only when held {@link #HOLD_MS}
 * ({@link #pressCounts}); anything shorter does nothing but say so.</li>
 * </ul>
 * Pure, with the lock's memory; times: any monotonic clock, ms. The activity asks it on its own thread - nothing of
 * it is on the stick path.
 */
public final class ArmedTouch {
    /** The flight controller's state as the phone's FcArmedState numbers it. */
    public static final int DISARMED = 0, ARMED = 1, UNKNOWN = 2;
    /** While armed, Stop must be held this long. */
    public static final long HOLD_MS = 3000;
    /** A lock put on by itself goes off this long after the flight controller reads DISARMED again. */
    public static final long UNLOCK_AFTER_MS = 5000;
    /** Unlocked by hand while armed: the lock is back by itself after this long, if still armed. */
    public static final long RELOCK_AFTER_MS = 15_000;
    /**
     * "Not known while a PC flies it" puts the lock on only when it has lasted this long: an app's start, and a wake
     * from a sleep, read the flight controller within a second or two with the PC already connected - no lock for that.
     * ARMED locks at the first look; Stop's hold never waits for this.
     */
    public static final long UNKNOWN_LOCK_AFTER_MS = 3000;
    /** What {@link #look} asks of the screen. */
    public static final int NOTHING = 0, LOCK = 1, UNLOCK = 2;

    private boolean locked, byItself;
    /** Since when the drone has counted as armed without being read ARMED (not known, a PC flying); -1: it does not. */
    private long unknownSince = -1;
    /** Since when DISARMED has been read without a break while the lock is on by itself; -1: not. */
    private long disarmedSince = -1;
    /** When the lock was taken off by hand while armed; -1: it was not. */
    private long unlockedByHandAt = -1;

    /**
     * Whether the drone counts as armed for the phone's screen.
     * @param fc         DISARMED, ARMED or UNKNOWN
     * @param boardOnUsb a flight controller is on the port (found, asked for, opened, failing) - answering or not
     * @param pcFlies    a PC's session is up
     */
    public static boolean armed(int fc, boolean boardOnUsb, boolean pcFlies) {
        return fc == ARMED || fc == UNKNOWN && boardOnUsb && pcFlies;
    }

    /**
     * A look at the drone (once a second, and when the screen comes up): LOCK - put the touch lock on, by itself;
     * UNLOCK - take it off; NOTHING.
     */
    public int look(int fc, boolean boardOnUsb, boolean pcFlies, long now) {
        boolean armed = armed(fc, boardOnUsb, pcFlies);
        if (!armed || fc == ARMED) unknownSince = -1;
        else if (unknownSince < 0) unknownSince = now;
        if (armed) {
            disarmedSince = -1;
            if (locked) return NOTHING;
            if (fc != ARMED && now - unknownSince < UNKNOWN_LOCK_AFTER_MS) return NOTHING;
            if (unlockedByHandAt >= 0 && now - unlockedByHandAt < RELOCK_AFTER_MS) return NOTHING;
            unlockedByHandAt = -1;
            locked = true;
            byItself = true;
            return LOCK;
        }
        unlockedByHandAt = -1;
        if (fc != DISARMED || !locked || !byItself) { // not known is not disarmed; a lock by hand is the user's
            disarmedSince = -1;
            return NOTHING;
        }
        if (disarmedSince < 0) disarmedSince = now;
        if (now - disarmedSince < UNLOCK_AFTER_MS) return NOTHING;
        locked = false;
        byItself = false;
        disarmedSince = -1;
        return UNLOCK;
    }

    /** LOCK SCREEN was pressed: the lock is the user's, and stays until the user takes it off. */
    public void lockedByHand() {
        locked = true;
        byItself = false;
        disarmedSince = -1;
        unlockedByHandAt = -1;
    }

    /** The unlock box was held: off, whoever put it on. While armed it comes back after {@link #RELOCK_AFTER_MS}. */
    public void unlockedByHand(long now) {
        locked = false;
        byItself = false;
        disarmedSince = -1;
        unlockedByHandAt = now;
    }

    /** The screen was built again with the lock on (a rotation, Android giving the app its window back): as it was. */
    public void restore(boolean wasByItself) {
        locked = true;
        byItself = wasByItself;
        disarmedSince = -1;
        unlockedByHandAt = -1;
    }

    public boolean locked() {
        return locked;
    }

    /** The lock is on and was put on by itself (it goes off by itself too). */
    public boolean byItself() {
        return locked && byItself;
    }

    /**
     * Whether a press of a control that ends the link or the service counts.
     * @param armed  {@link #armed} now
     * @param heldMs how long it has been held
     */
    public static boolean pressCounts(boolean armed, long heldMs) {
        return !armed || heldMs >= HOLD_MS;
    }

    /** The seconds still to hold, for the button's countdown: 3, 2, 1 - and 0 when the press counts. */
    public static int holdSecondsLeft(long heldMs) {
        if (heldMs >= HOLD_MS) return 0;
        return (int) ((HOLD_MS - Math.max(0, heldMs) + 999) / 1000);
    }
}
