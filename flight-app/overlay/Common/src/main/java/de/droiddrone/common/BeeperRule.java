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
 * DRAUPNE Flight pc.54: when a drone on the phone beeps (the pilot, 2026-11-18). Silent while it rests on purpose -
 * after the PC's DISCONNECT, in standby, deep sleep and overwatch. It beeps - "It should beep if link is lost, usb
 * connection to FC is lost, or out of action is pressed, or beeper is pressed" - and stops "when link and FC
 * connection is back".
 * The sound is Betaflight's own, for a receiver it has lost (RX_LOST: the buzzer, and the motors where the DShot
 * beacon is on for it - Drone setup sees to both). So the rule is about what the phone gives the flight controller:
 * <ul>
 * <li>disarmed and nobody flying, the phone feeds it idle sticks - the receiver is there, the drone silent - while
 * {@link #feedIdle} says so: not after a link that went without the PC's Disconnect (lost, not ended), until a PC
 * connects again; not while the pilot's BEEPER asks for a beep ({@link #beep});</li>
 * <li>a sleep closes the flight controller's port: {@link #muteForSleep} - the lost-receiver sounds are switched
 * off first ({@link BeeperQuiet}), except for a drone marked OUT OF ACTION, which is to be found;</li>
 * <li>the phone's USB out of the flight controller: nothing the phone can do or needs to - Betaflight beeps.</li>
 * </ul>
 * Never anything while armed: idle sticks go to a disarmed flight controller only (the idle guards before this rule).
 * Pure, with the session's memory: one for the app (it outlives a restarted link). Times: any monotonic clock, ms.
 */
public final class BeeperRule {
    /** The longest one BEEPER press beeps, as the PC's own limit for the held button. */
    public static final long BEEP_MAX_MS = 10_000;

    private boolean connected, disconnectSaid, lost;
    private long beepUntil;

    /** A PC's Connect was taken: whatever went before, a pilot is here again. */
    public synchronized void sessionTaken() {
        connected = true;
        disconnectSaid = false;
        lost = false;
    }

    /** The PC said Disconnect: the session ends on purpose. */
    public synchronized void disconnectSaid() {
        disconnectSaid = true;
        lost = false;
    }

    /**
     * Whether the session is up now, asked again and again: the moment it is no longer up without a Disconnect having
     * been said, the link is lost.
     */
    public synchronized void session(boolean up) {
        if (connected && !up && !disconnectSaid) lost = true;
        connected = up;
    }

    /** BEEPER pressed on the PC (ms 0: let go): the drone beeps for that long, {@link #BEEP_MAX_MS} at most. */
    public synchronized void beep(long now, long ms) {
        beepUntil = ms <= 0 ? 0 : now + Math.min(ms, BEEP_MAX_MS);
    }

    /** The idle sticks may be fed now (the other conditions - disarmed, can't arm - are the caller's). */
    public synchronized boolean feedIdle(long now) {
        return !lost && now >= beepUntil;
    }

    /** The link went without a Disconnect and no PC has connected since. */
    public synchronized boolean linkLost() {
        return lost;
    }

    /** The pilot's BEEPER holds the idle sticks back now. */
    public synchronized boolean beeping(long now) {
        return now < beepUntil;
    }

    /** Before a sleep closes the flight controller's port: switch its lost-receiver sounds off? Not for OUT OF ACTION. */
    public static boolean muteForSleep(boolean outOfAction) {
        return !outOfAction;
    }

    /**
     * F-051 (the pilot, 2026-11-20): OUT OF ACTION asked of a drone already asleep with its beeper switched off - it
     * wakes for the beeper (then sleeps again, {@link #backToSleep}). Not when its beeper was left on, or it is awake
     * (the sleep it falls into next leaves the beeper on by {@link #muteForSleep}).
     */
    public static boolean wakeForBeeper(boolean outOfAction, boolean asleep, boolean beeperQuieted) {
        return outOfAction && asleep && beeperQuieted;
    }

    /**
     * F-051: awake for its beeper, the drone sleeps again once the beeper is back and its flight controller is read as
     * disarmed (unknown counts as armed: the sleep would be refused) - or when the time for it is up.
     */
    public static boolean backToSleep(boolean beeperQuieted, boolean armedOrUnknown, boolean timeUp) {
        return timeUp || !beeperQuieted && !armedOrUnknown;
    }

    /**
     * pc.54: idle sticks of the phone's own, for when no PC has sent any (a phone put to standby from FLEET without a
     * session, a PC without a radio): roll, pitch and yaw centred, the throttle low, every AUX low - {@link #CHANNELS}
     * channels in the PC's order (roll, pitch, yaw, throttle, AUX1 ...). Null when that could arm by the flight
     * controller's own ARM ranges (mayArm; also while they aren't read): then there are none, as before.
     */
    public static short[] ownIdle(java.util.function.Predicate<short[]> mayArm) {
        short[] frame = new short[CHANNELS];
        java.util.Arrays.fill(frame, (short) 1000);
        frame[0] = frame[1] = frame[2] = 1500;
        return mayArm.test(frame) ? null : frame;
    }

    public static final int CHANNELS = 18;
}
