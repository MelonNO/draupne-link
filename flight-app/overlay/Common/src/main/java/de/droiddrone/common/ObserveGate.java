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

import java.util.function.BooleanSupplier;

/**
 * DRAUPNE Flight pc.37: who may observe this phone, when the observation ends for everyone, and what of the sound its
 * observers get (moved out of the phone's Udp, rules unchanged). Pure: Udp asks it with what it has just read - on the
 * receive thread for an OBSERVE, on the telemetry thread every 0.1 s, on the audio gate's thread - and does what the
 * answer says; the PC's tests check it. {@link Observe.Observers} is the table behind it, {@link ObservePace} what the
 * observation does when.
 * <ul>
 * <li>An OBSERVE ({@link #request}) is refused while a pilot is connected, or was heard within {@link #AFTER_PILOT_MS}
 * - unless it comes from that very PC, back at its grid; while the FC isn't known DISARMED (unknown counts as armed);
 * and outside normal power mode. pc.38: never an observer source while overwatching - its camera is the watch's (and
 * its event recorder's); overwatch can still be ending (the camera not yet let go) right after the mode went back to
 * normal. Past all that the table decides: streaming, or full.</li>
 * <li>pc.52 ({@link #wakes}; the pilot: "when trying to connect phone to the observing menu it does not work"):
 * DISCONNECT leaves a phone in standby, and standby refused every observer - a phone flown once could not be observed
 * again. Standby is awake with the camera off: an observer wakes it (as a pilot's Connect does); its next request, a
 * second on, gets the stream. Deep sleep and overwatch stay refused: those end at a check-in or by a pilot.</li>
 * <li>With observers in the table ({@link #ended}) the observation ends for all of them when a pilot connected, the FC
 * isn't known disarmed or the phone left normal power mode; they are told the first of these that holds.</li>
 * <li>The sound ({@link #soundWanted}, {@link #soundAllowed}) follows the pc.34 gate with the observers' wish instead
 * of the pilot's. pc.45 ({@link #listens}): observed, no observer wants the sound - listened to for their "sound
 * heard" ({@link #stateFlags}), never sent.</li>
 * </ul>
 */
public final class ObserveGate {
    /** No observation within this long of the pilot's last packet, but for that very PC (its "back to the grid"). */
    public static final long AFTER_PILOT_MS = Takeover.PILOT_ALIVE_MS;

    private ObserveGate() {
    }

    /**
     * An OBSERVE, before the table is asked.
     * @param pilotConnected a pilot's session is up
     * @param pilotSilentMs  how long since the pilot's last packet ({@link SessionGate#silentMs})
     * @param fromLastPc     it comes from the address of the PC this phone last flew with (or that set its mode)
     * @param disarmed       the flight controller is known DISARMED; asked only when no pilot is near
     * @param normalPower    the power mode is normal
     * @param overwatching   overwatch watches, or is still ending
     * @return {@link Observe#STREAMING}: on to the table; else REFUSED_PILOT, REFUSED_ARMED or REFUSED_POWER
     */
    public static int request(boolean pilotConnected, long pilotSilentMs, boolean fromLastPc, BooleanSupplier disarmed,
                              boolean normalPower, boolean overwatching) {
        boolean pilotNear = pilotConnected || pilotSilentMs < AFTER_PILOT_MS && !fromLastPc;
        if (pilotNear) return Observe.REFUSED_PILOT;
        if (!disarmed.getAsBoolean()) return Observe.REFUSED_ARMED;
        if (!normalPower || overwatching) return Observe.REFUSED_POWER;
        return Observe.STREAMING;
    }

    /**
     * pc.52: whether the OBSERVE that got this answer wakes the phone - one refused for the power mode, in standby,
     * and not while overwatch is still ending.
     * @param state what {@link #request} said
     */
    public static boolean wakes(int state, boolean standby, boolean overwatching) {
        return state == Observe.REFUSED_POWER && standby && !overwatching;
    }

    /**
     * Every tick while observers are in the table.
     * @param disarmed the flight controller is known DISARMED
     * @return {@link Observe#STREAMING} while the observation goes on; else why it ends for all of them: ENDED_PILOT,
     * REFUSED_ARMED or REFUSED_POWER
     */
    public static int ended(boolean pilotConnected, boolean disarmed, boolean normalPower, boolean overwatching) {
        boolean armed = !disarmed;
        boolean power = !normalPower || overwatching; // pc.38
        if (!(pilotConnected || armed || power)) return Observe.STREAMING;
        return pilotConnected ? Observe.ENDED_PILOT : armed ? Observe.REFUSED_ARMED : Observe.REFUSED_POWER;
    }

    /** pc.45: OBSERVE_STATE's flags - "sound heard" only in an answer that says STREAMING. */
    public static int stateFlags(int state, boolean soundHeard) {
        return state == Observe.STREAMING && soundHeard ? Observe.STATE_SOUND_HEARD : 0;
    }

    /**
     * pc.37: an observer wants the sound.
     * @param observing    the stream goes to observers
     * @param observeAudio one of them asks for the sound
     */
    public static boolean soundWanted(boolean observing, boolean observeAudio, boolean pilotConnected) {
        return observing && observeAudio && !pilotConnected;
    }

    /**
     * The observers' sound may stream (no pilot connected): wanted, the FC known DISARMED, normal power mode.
     * @param wanted {@link #soundWanted}
     */
    public static boolean soundAllowed(boolean wanted, boolean disarmed, boolean normalPower) {
        return wanted && disarmed && normalPower;
    }

    /**
     * pc.45: observed, no observer wants the sound: listened to for their "sound heard", never sent.
     * @param allowed  the sound itself may stream now (to a pilot, or to observers)
     * @param disarmed the flight controller is known DISARMED; asked last, only when all the rest holds
     */
    public static boolean listens(boolean allowed, boolean observing, boolean observeAudio, boolean pilotConnected,
                                  boolean normalPower, BooleanSupplier disarmed) {
        return !allowed && observing && !observeAudio && !pilotConnected && normalPower && disarmed.getAsBoolean();
    }
}
