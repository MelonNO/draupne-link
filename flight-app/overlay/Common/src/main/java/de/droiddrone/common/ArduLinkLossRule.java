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
 * DRAUPNE Flight (fleet PRD AP-5, A3-A5, AC-41): what the phone of an ArduPilot drone does by itself when its pilot's
 * PC goes silent - a drone on its own, or the lead of a follower group (followers have their own rule). Asked ten
 * times a second; it answers with the one action to take now, once per step:
 * <ol>
 *   <li>The PC silent {@link #BRAKE_AFTER_MS}: {@link #BRAKE} into LOITER where it is, or {@link #HOLD_HEIGHT}
 *       (ALT_HOLD) when its GNSS is no good - it can't hold a place without it (A5: hold height and wait).</li>
 *   <li>Silent {@link #HOME_AFTER_MS} (A4): {@link #SMART_RTL} alone; {@link #RTL} as a lead, whose RTL_ALT the PC set
 *       one layer above every follower's return height (AC-41: the group trails on the lead's path, so Smart RTL
 *       would fly back through it). Without GNSS nothing more: it holds height and waits for the pilot.</li>
 *   <li>The PC heard again: the rule lets go ({@link #running()} false). The mode it set stays - the pilot flies on
 *       with the mode switch (the PC says so); a drone on its way home keeps going.</li>
 * </ol>
 * Nothing while disarmed, nothing before a PC was ever heard (a drone flown with its own radio), and nothing when
 * the flight controller is already bringing it home or down (RTL, Smart RTL, Auto RTL, LAND: ArduPilot's own
 * failsafe may have chosen them) - neither when the rule begins nor later: a LAND or RTL ArduPilot chose by itself
 * after the brake (its battery failsafe) is never overridden by the rule's next step. GNSS lost later in the plan
 * turns a brake into holding height; a return already started is left to ArduPilot.
 * <p>
 * A step whose mode the flight controller doesn't show {@link #RETRY_MS} after it was asked (the command lost on the
 * USB, or refused) is asked again; refused {@link #MAX_TRIES} times, LOITER gives way to ALT_HOLD (no position
 * estimate) and Smart RTL to RTL (no Smart RTL path: armed without a position, or its path full) - before, a refused
 * step left the drone in the pilot's mode with centred sticks for good.
 * <p>
 * Not as the PRD says: "brakes within about 100 ms". On 4G, gaps of 100-500 ms with nothing from the PC are routine
 * (the link logs); a brake at each would leave the pilot in LOITER all the time. The brake starts after a full
 * second of silence (the PC sends 20 pings a second besides the sticks), its command within 100 ms of that.
 * Java 11, no allocation.
 */
public final class ArduLinkLossRule {
    public static final long BRAKE_AFTER_MS = 1000;
    public static final long HOME_AFTER_MS = 20_000;
    /** A step's mode not shown by the FC's heartbeat (once a second) this long after it was asked: asked again. */
    public static final long RETRY_MS = 1500;
    /** Asked this many times without the FC taking it: the step's fallback (LOITER to ALT_HOLD, Smart RTL to RTL). */
    public static final int MAX_TRIES = 3;

    public static final int NONE = 0, BRAKE = 1, HOLD_HEIGHT = 2, SMART_RTL = 3, RTL = 4;

    /** ArduPilot Copter's mode numbers this rule sets or respects (ArduModes on the PC has them all). */
    public static final int MODE_ALT_HOLD = 2, MODE_LOITER = 5, MODE_RTL = 6, MODE_LAND = 9, MODE_BRAKE = 17,
            MODE_SMART_RTL = 21, MODE_AUTO_RTL = 27;

    /** Silent this long: home. HOME_AFTER_MS for a drone the pilot flies; a reserve on its way waits 2 min (AC-37). */
    private final long brakeAfterMs, homeAfterMs;

    public ArduLinkLossRule() {
        this(BRAKE_AFTER_MS, HOME_AFTER_MS);
    }

    /**
     * @param brakeAfterMs how long the PC is silent before the brake (1 s for a pilot's 20 pings a second; longer for
     *                     a commander that is heard only every few seconds)
     * @param homeAfterMs  how long before the return
     */
    public ArduLinkLossRule(long brakeAfterMs, long homeAfterMs) {
        this.brakeAfterMs = brakeAfterMs;
        this.homeAfterMs = homeAfterMs;
    }

    private boolean running;
    /** ArduPilot took over by itself (a LAND or RTL of its own after the rule began): nothing more from the rule. */
    private boolean handedOver;
    /** LOITER / Smart RTL refused MAX_TRIES times: ALT_HOLD / RTL in their place until the rule lets go. */
    private boolean loiterRefused, smartRefused;
    private int last = NONE;
    private int tries;
    private long askedAt;

    /**
     * @param pcHeardAtMs when a packet of the pilot's session last came (the same clock as now), 0: never
     * @param armed       the FC's own word (its heartbeat)
     * @param customMode  the FC's mode now (its heartbeat), -1 not known
     * @param gnssOk      a 3D fix or better
     * @param lead        this drone leads a follower group
     * @return the action to take now, or NONE (also when the same step was taken already and the FC shows it)
     */
    public int tick(long nowMs, long pcHeardAtMs, boolean armed, int customMode, boolean gnssOk, boolean lead) {
        long silent = pcHeardAtMs == 0 ? -1 : nowMs - pcHeardAtMs;
        if (silent < brakeAfterMs || !armed) {
            running = false;
            handedOver = loiterRefused = smartRefused = false;
            last = NONE;
            tries = 0;
            return NONE;
        }
        if (!running && goingHomeOrDown(customMode)) return NONE; // ArduPilot already does it: never overridden
        running = true;
        if (handedOver) return NONE;
        if (goingHomeOrDown(customMode) && customMode != modeFor(last)) {
            handedOver = true; // ArduPilot chose its own way home or down after the rule began: never overridden
            return NONE;
        }
        int want;
        if (!gnssOk) {
            want = HOLD_HEIGHT;
        } else if (silent >= homeAfterMs) {
            want = lead ? RTL : SMART_RTL;
        } else {
            want = BRAKE;
        }
        // a return under way is left alone: lost GNSS during RTL is ArduPilot's own (it lands or holds by its failsafe)
        if (last == SMART_RTL || last == RTL) want = last;
        if (want == BRAKE && loiterRefused) want = HOLD_HEIGHT;
        if (want == SMART_RTL && smartRefused) want = RTL;
        if (want != last) return ask(want, nowMs);
        // the same step: asked again while the FC doesn't show its mode (not known: nothing to compare)
        if (customMode < 0 || customMode == modeFor(last) || nowMs - askedAt < RETRY_MS) return NONE;
        if (tries >= MAX_TRIES) {
            if (last == BRAKE) {
                loiterRefused = true;
                return ask(HOLD_HEIGHT, nowMs);
            }
            if (last == SMART_RTL) {
                smartRefused = true;
                return ask(RTL, nowMs);
            }
        }
        tries++;
        askedAt = nowMs;
        return last;
    }

    private int ask(int step, long nowMs) {
        last = step;
        tries = 1;
        askedAt = nowMs;
        return step;
    }

    /** The rule is in charge: the PC silent past the brake, the drone armed. */
    public boolean running() {
        return running;
    }

    /** The mode number an action sets. */
    public static int modeFor(int action) {
        switch (action) {
            case BRAKE:
                return MODE_LOITER;
            case HOLD_HEIGHT:
                return MODE_ALT_HOLD;
            case SMART_RTL:
                return MODE_SMART_RTL;
            case RTL:
                return MODE_RTL;
            default:
                return -1;
        }
    }

    public static String name(int action) {
        switch (action) {
            case BRAKE:
                return "LOITER";
            case HOLD_HEIGHT:
                return "ALT HOLD";
            case SMART_RTL:
                return "SMART RTL";
            case RTL:
                return "RTL";
            default:
                return "none";
        }
    }

    static boolean goingHomeOrDown(int mode) {
        return mode == MODE_RTL || mode == MODE_SMART_RTL || mode == MODE_AUTO_RTL || mode == MODE_LAND;
    }
}
