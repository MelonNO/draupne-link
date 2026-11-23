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

package de.droiddrone.common.launch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fleet PRD area F (AL-7 - AL-18, FA11 - FA18): a marked reserve's launch, as its phone drives it.
 * <pre>
 * IDLE -order-> CALLED -> WAKING -FC ready-> CHECKING -OK-> BEEPING (2 s) -still OK-> ARMING -armed-> CLIMBING
 *   -approach layer-> ON THE WAY -at the group-> WAITING FOR TRANSIT -granted-> IN TRANSIT -in its cell-> IN GROUP
 * any check failure before the motors run: NOT LAUNCHED (the PC calls the next)
 * the climb watchdog: LAUNCH FAILED - motors off at once, beeper silent until OUT OF ACTION
 * cancel (until armed) / OUT OF ACTION before arming: CANCELLED
 * </pre>
 * <b>The rule this class is for (AL-12): only a marked drone, and only on the pilot's call, ever arms by itself.</b> The
 * one place {@link LaunchIntent#ARM} is made is the step from BEEPING to ARMING, and it is taken only when, at that
 * moment: an order was decoded and accepted for this sequence (the pilot's call - the PC sends one per CALL RESERVE
 * press), the order named the id of the mark the phone holds, that mark is valid, the 2 s beep has run its time, and
 * {@link PreArmCheck} answers OK. A launch is one-shot: after it the mark is cleared (LAUNCHED), so the same order
 * can't launch it twice. A cancel after arming changes nothing: the drone joins whoever is the lead now (FA10).
 * Not thread-safe: the phone's service timer drives it.
 */
public final class LaunchSequence {
    public static final int IDLE = 0, CALLED = 1, WAKING = 2, CHECKING = 3, BEEPING = 4, ARMING = 5, CLIMBING = 6,
            ON_THE_WAY = 7, WAITING_FOR_TRANSIT = 8, IN_TRANSIT = 9, IN_GROUP = 10, NOT_LAUNCHED = 11,
            LAUNCH_FAILED = 12, CANCELLED = 13;
    public static final int LAST_STATE = CANCELLED;

    /** FA11: the warning before the motors start. */
    public static final long BEEP_MS = 2000;
    /** The FC must answer this soon after waking, else NOT LAUNCHED (FC NOT READY). */
    public static final long WAKE_MS = 30_000;
    /** The check may wait this long for its 5 s of still samples, else NOT LAUNCHED (CARRIED / no samples). */
    public static final long CHECK_MS = 15_000;
    /** ArduPilot must report ARMED this soon, else NOT LAUNCHED (ARM REFUSED) and a forced disarm to be sure. */
    public static final long ARM_MS = 5000;
    /**
     * The same call (call id and mark id) again this soon after it came is a copy - the PC repeats an order until the
     * status names it, and a late or replayed copy may come after the launch ended: answered with the status, nothing
     * new. Later it is a new call (the PC's call ids start at 1 each time it starts).
     */
    public static final long REPLAY_MS = 60_000;
    /** The approach layer counts as reached this close under it. */
    public static final double REACHED_M = 1.0;

    /** Reasons: PreArmCheck's results as they are (2..8), the climb watchdog's + 20, and these. */
    public static final int CLIMB_REASON = 20, ARM_REFUSED = 40, DISARMED_IN_CLIMB = 41, FC_NOT_READY = 42,
            WRONG_MARK = 43, LANDED = 44;

    public static String stateWord(int s) {
        switch (s) {
            case IDLE: return "IDLE";
            case CALLED: return "CALLED";
            case WAKING: return "WAKING";
            case CHECKING: return "CHECKING";
            case BEEPING: return "BEEPING";
            case ARMING: return "ARMING";
            case CLIMBING: return "CLIMBING";
            case ON_THE_WAY: return "ON THE WAY";
            case WAITING_FOR_TRANSIT: return "WAITING FOR TRANSIT";
            case IN_TRANSIT: return "IN TRANSIT";
            case IN_GROUP: return "IN GROUP";
            case NOT_LAUNCHED: return "NOT LAUNCHED";
            case LAUNCH_FAILED: return "LAUNCH FAILED";
            case CANCELLED: return "CANCELLED";
            default: return "?";
        }
    }

    public static String reasonWord(int reason) {
        if (reason == 0) return "";
        if (reason > CLIMB_REASON && reason < ARM_REFUSED) return ClimbWatchdog.word(reason - CLIMB_REASON);
        switch (reason) {
            case ARM_REFUSED: return "ARM REFUSED";
            case DISARMED_IN_CLIMB: return "DISARMED";
            case FC_NOT_READY: return "FC NOT READY";
            case WRONG_MARK: return "MARK GONE";
            case LANDED: return "LANDED";
            default: return PreArmCheck.word(reason);
        }
    }

    /**
     * Link loss on a launch (PRD AL-14, AC-37, AC-38). From the call to the top of the climb the phone is in charge
     * with no 4G round trip (AL-14: the climb watchdog decides, the climb goes on to its approach layer): the
     * commander's silence changes nothing, and the phone keeps the FC's ground station heartbeat going - before, it
     * stopped 3 s into the silence and ArduPilot's GCS failsafe broke off the climb. On the way to the group (ON THE
     * WAY to IN TRANSIT) the commander's silence is a follower's link loss (AC-37): LOITER after {@link #LOST_BRAKE_AFTER_MS}
     * (a reserve on its way hears its commander's TRACK only every 5 s), home by RTL after {@link #LOST_HOME_AFTER_MS};
     * heard again, it rejoins like a reserve (AC-38). IN GROUP: the follower's own rules.
     */
    public static final long LOST_BRAKE_AFTER_MS = 12_000, LOST_HOME_AFTER_MS = 120_000;

    /** The phone keeps the FC's ground station heartbeat going in this state, the commander heard or not. */
    public static boolean keepsGcsAlive(int s) {
        return s >= CALLED && s <= IN_TRANSIT;
    }

    /** The commander's silence is link loss in this state (the climb ended, not yet in the group). */
    public static boolean linkLossApplies(int s) {
        return s == ON_THE_WAY || s == WAITING_FOR_TRANSIT || s == IN_TRANSIT;
    }

    /** The motors may be running: armed, or told to arm. */
    public static boolean motorsMayRun(int s) {
        return s == ARMING || s == CLIMBING || s == ON_THE_WAY || s == WAITING_FOR_TRANSIT || s == IN_TRANSIT || s == IN_GROUP;
    }

    private final PreArmCheck check;
    private LaunchMark mark;
    private int state = IDLE, reason;
    private LaunchWire.Order order;
    /** The mark id the accepted order named (0: none accepted). */
    private int acceptedMarkId;
    private long stateAt;
    /** When the last order (taken or refused) came. */
    private long orderAt;
    private ClimbWatchdog watchdog;
    private double heightM, climbMps;
    private boolean passedFloor, fcReady, armed;

    public LaunchSequence(PreArmCheck check) {
        this.check = check;
    }

    public int state() {
        return state;
    }

    public int reason() {
        return reason;
    }

    public LaunchMark mark() {
        return mark;
    }

    /** The order being carried out, or null. */
    public LaunchWire.Order order() {
        return order;
    }

    /** A sequence that has ended (or never began): a new order may start one. */
    public boolean finished() {
        return state == IDLE || state == NOT_LAUNCHED || state == LAUNCH_FAILED || state == CANCELLED || state == IN_GROUP;
    }

    /**
     * The pilot marked (or unmarked: null) this drone. Taken only while no launch runs; a running one keeps the mark
     * it started with.
     */
    public void setMark(LaunchMark m) {
        if (finished() || state == IDLE) mark = m;
    }

    /**
     * The PC's order (already decoded: a null is ignored). LAUNCH starts a launch only from a finished sequence and only
     * for the mark held now (its id, still valid); otherwise NOT LAUNCHED (MARK GONE) is reported for that call so the
     * PC calls the next. CANCEL stops a launch whose motors have not been told to start.
     */
    public List<LaunchIntent> order(LaunchWire.Order o, long nowMs) {
        if (o == null) return Collections.emptyList();
        List<LaunchIntent> out = new ArrayList<>();
        if (o.kind == LaunchWire.KIND_LAUNCH && order != null && order.kind == LaunchWire.KIND_LAUNCH && o.callId == order.callId
                && o.markId == order.markId && nowMs - orderAt >= 0 && nowMs - orderAt < REPLAY_MS) {
            // a copy of the call being (or last) carried out: its status again - before, a copy after the launch ended
            // was taken as a new call, refused (the mark gone) and IN GROUP or LAUNCH FAILED reported as NOT LAUNCHED
            report(out);
            return out;
        }
        if (o.kind == LaunchWire.KIND_CANCEL) {
            if (order != null && o.callId == order.callId && !motorsMayRun(state) && !finished()) {
                to(CANCELLED, 0, nowMs, out);
                out.add(LaunchIntent.beep(0));
                report(out);
            }
            return out; // after arming: carries on and joins whoever is the lead now (FA10)
        }
        if (!finished()) return out; // one launch at a time; the running one answers
        if (mark == null || !mark.valid() || o.markId != mark.id) {
            LaunchWire.Order refused = o;
            order = refused;
            orderAt = nowMs;
            acceptedMarkId = 0;
            to(NOT_LAUNCHED, WRONG_MARK, nowMs, out);
            report(out);
            return out;
        }
        order = o;
        acceptedMarkId = o.markId;
        orderAt = nowMs;
        reason = 0;
        heightM = 0;
        climbMps = 0;
        passedFloor = false;
        armed = false;
        watchdog = null;
        check.restart();
        to(CALLED, 0, nowMs, out);
        report(out);
        to(WAKING, 0, nowMs, out);
        report(out);
        return out;
    }

    /** The phone is awake and its flight controller answers (FC info and attitude read). */
    public void fcReady(boolean ready) {
        fcReady = ready;
    }

    /** The pilot marked the drone OUT OF ACTION: the mark goes; a launch not yet under way is cancelled. */
    public List<LaunchIntent> outOfAction(long nowMs) {
        List<LaunchIntent> out = new ArrayList<>();
        if (mark != null && mark.valid()) {
            mark.clear(LaunchMark.CLEARED_OUT_OF_ACTION);
            out.add(LaunchIntent.clearMark(LaunchMark.CLEARED_OUT_OF_ACTION));
        }
        if (!finished() && !motorsMayRun(state)) {
            to(CANCELLED, 0, nowMs, out);
            out.add(LaunchIntent.beep(0));
            report(out);
        }
        out.add(LaunchIntent.of(LaunchIntent.BEEPER_NORMAL)); // FA18: silent only until OUT OF ACTION
        return out;
    }

    /**
     * The service's tick (several a second while a launch runs).
     * @param r what the FC and the phone read now (the check's input)
     * @param fcArmed the FC reports ARMED now
     * @param climb the climb's sample now (null between samples)
     */
    public List<LaunchIntent> tick(long nowMs, PreArmCheck.Reading r, boolean fcArmed, ClimbWatchdog.Sample climb) {
        return tick(nowMs, r, fcArmed, true, climb);
    }

    /**
     * @param fcArmedKnown the FC's armed word is fresh (its heartbeat within 3 s): a gap in the heartbeats is no
     *                     disarm - before, it read as DISARMED in the climb and the motors were forced off in the air
     */
    public List<LaunchIntent> tick(long nowMs, PreArmCheck.Reading r, boolean fcArmed, boolean fcArmedKnown, ClimbWatchdog.Sample climb) {
        List<LaunchIntent> out = new ArrayList<>();
        if (fcArmedKnown) armed = fcArmed;
        fcArmed &= fcArmedKnown;
        switch (state) {
            case WAKING:
                if (fcReady) {
                    to(CHECKING, 0, nowMs, out);
                    report(out);
                } else if (nowMs - stateAt > WAKE_MS) {
                    notLaunched(FC_NOT_READY, nowMs, out);
                }
                break;
            case CHECKING: {
                int res = check.check(nowMs, r, mark);
                if (res == PreArmCheck.OK) {
                    to(BEEPING, 0, nowMs, out);
                    out.add(LaunchIntent.beep(BEEP_MS));
                    report(out);
                } else if (res == PreArmCheck.WAIT) {
                    if (nowMs - stateAt > CHECK_MS) notLaunched(PreArmCheck.CARRIED, nowMs, out);
                } else {
                    notLaunched(res, nowMs, out);
                }
                break;
            }
            case BEEPING: {
                int res = check.check(nowMs, r, mark);
                if (res != PreArmCheck.OK) {
                    out.add(LaunchIntent.beep(0));
                    notLaunched(res == PreArmCheck.WAIT ? PreArmCheck.CARRIED : res, nowMs, out);
                } else if (nowMs - stateAt >= BEEP_MS && armAllowed(nowMs, res)) {
                    to(ARMING, 0, nowMs, out);
                    out.add(LaunchIntent.of(LaunchIntent.SET_MODE_GUIDED));
                    out.add(LaunchIntent.of(LaunchIntent.ARM));
                    report(out);
                }
                break;
            }
            case ARMING:
                if (fcArmed) {
                    mark.clear(LaunchMark.CLEARED_LAUNCHED); // one-shot: this mark never launches again
                    out.add(LaunchIntent.clearMark(LaunchMark.CLEARED_LAUNCHED));
                    watchdog = new ClimbWatchdog(order.floorM);
                    watchdog.start(nowMs);
                    to(CLIMBING, 0, nowMs, out);
                    out.add(LaunchIntent.takeoff(order.approachHeightM));
                    report(out);
                } else if (nowMs - stateAt > ARM_MS) {
                    out.add(LaunchIntent.of(LaunchIntent.MOTORS_OFF)); // should it arm late, it must not stay so
                    notLaunched(ARM_REFUSED, nowMs, out);
                }
                break;
            case CLIMBING: {
                if (climb != null) {
                    if (!Double.isNaN(climb.heightM)) heightM = climb.heightM;
                    if (!Double.isNaN(climb.climbMps)) climbMps = climb.climbMps;
                }
                if (fcArmedKnown && !fcArmed) {
                    failed(DISARMED_IN_CLIMB, nowMs, out);
                    break;
                }
                int v = climb != null ? watchdog.feed(climb) : watchdog.tick(nowMs);
                if (ClimbWatchdog.isAbort(v)) {
                    failed(CLIMB_REASON + v, nowMs, out);
                    break;
                }
                if (v == ClimbWatchdog.DONE && !passedFloor) {
                    passedFloor = true; // the mini-view leaves here (AL-20)
                    report(out);
                }
                if (heightM >= order.approachHeightM - REACHED_M) {
                    to(ON_THE_WAY, 0, nowMs, out);
                    report(out);
                }
                break;
            }
            case ON_THE_WAY:
            case WAITING_FOR_TRANSIT:
            case IN_TRANSIT:
                // disarmed after its climb (landed - in the group's end, at 3 %, by the pilot): the launch is over.
                // Before, nothing ended it (nothing on the phone knows the group's cells): the sequence stayed ON THE
                // WAY for good, and the drone never took a new mark or call again until the app restarted.
                if (fcArmedKnown && !fcArmed) {
                    to(LAUNCH_FAILED, LANDED, nowMs, out);
                    report(out);
                }
                break;
            default:
                break;
        }
        return out;
    }

    /** It flew to the group in its approach layer and waits for a transit area (AC-27). */
    public List<LaunchIntent> atGroup(long nowMs) {
        return step(ON_THE_WAY, WAITING_FOR_TRANSIT, nowMs);
    }

    /** The PC granted it a transit area: it comes down to its new layer there. */
    public List<LaunchIntent> transitGranted(long nowMs) {
        return step(WAITING_FOR_TRANSIT, IN_TRANSIT, nowMs);
    }

    /** It is in its cell: a follower now (AL-9's IN GROUP). */
    public List<LaunchIntent> inCell(long nowMs) {
        return step(IN_TRANSIT, IN_GROUP, nowMs);
    }

    /** The status the phone sends now (also every second while a launch runs). */
    public LaunchWire.Status status() {
        LaunchWire.Status s = new LaunchWire.Status();
        s.callId = order == null ? 0 : order.callId;
        s.markId = order == null ? 0 : order.markId;
        s.state = state;
        s.reason = reason;
        s.heightM = heightM;
        s.climbMps = climbMps;
        s.passedFloor = passedFloor;
        s.markValid = mark != null && mark.valid();
        s.connected = fcReady;
        s.armed = armed;
        return s;
    }

    /**
     * AL-12, asked at the one place ARM is made: every condition of the rule at this moment. Separate from the state
     * machine's flow on purpose, so that no later change to the flow can arm past it.
     */
    private boolean armAllowed(long nowMs, int checkResult) {
        return state == BEEPING
                && order != null && order.kind == LaunchWire.KIND_LAUNCH
                && acceptedMarkId != 0 && mark != null && mark.id == acceptedMarkId && mark.valid()
                && nowMs - stateAt >= BEEP_MS
                && checkResult == PreArmCheck.OK;
    }

    private List<LaunchIntent> step(int from, int to, long nowMs) {
        List<LaunchIntent> out = new ArrayList<>();
        if (state == from) {
            to(to, 0, nowMs, out);
            report(out);
        }
        return out;
    }

    private void notLaunched(int why, long nowMs, List<LaunchIntent> out) {
        if (PreArmCheck.clearsMark(why) && mark != null && mark.valid()) {
            mark.clear(LaunchMark.CLEARED_CHECK_FAILED);
            out.add(LaunchIntent.clearMark(LaunchMark.CLEARED_CHECK_FAILED));
        }
        to(NOT_LAUNCHED, why, nowMs, out);
        report(out);
    }

    private void failed(int why, long nowMs, List<LaunchIntent> out) {
        out.add(0, LaunchIntent.of(LaunchIntent.MOTORS_OFF)); // first, before anything else is said
        out.add(LaunchIntent.of(LaunchIntent.BEEPER_SILENT));
        to(LAUNCH_FAILED, why, nowMs, out);
        report(out);
    }

    private void to(int s, int why, long nowMs, List<LaunchIntent> out) {
        state = s;
        reason = why;
        stateAt = nowMs;
    }

    private void report(List<LaunchIntent> out) {
        out.add(LaunchIntent.report(status()));
    }
}
