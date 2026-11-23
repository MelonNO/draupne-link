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

/**
 * Fleet PRD area F: what {@link LaunchSequence} asks the phone to do, in order. The phone's wiring turns each into
 * MAVLink (or its own beeper / report): it decides nothing itself.
 */
public final class LaunchIntent {
    /** Sound the drone's beeper for {@link #ms} ms (0: stop it). FA11: 2 s before the motors start. */
    public static final int BEEP = 1;
    /** Flight mode GUIDED (ArduPilot Copter mode 4): take-off and the approach layer's targets need it. */
    public static final int SET_MODE_GUIDED = 2;
    /** Arm (MAV_CMD_COMPONENT_ARM_DISARM, param1 1). Only ever after the checks (AL-12). */
    public static final int ARM = 3;
    /** Climb straight up to {@link #heightM} m above the take-off point (MAV_CMD_NAV_TAKEOFF). */
    public static final int TAKEOFF = 4;
    /** Motors off at once, always (FA16): a forced disarm (MAV_CMD_COMPONENT_ARM_DISARM, param1 0, param2 21196). */
    public static final int MOTORS_OFF = 5;
    /** The mark is gone ({@link #why}: LaunchMark's CLEARED_ reason): say so to the PC and forget it. */
    public static final int CLEAR_MARK = 6;
    /** FA18 / AL-18: beeper silent until the drone is marked OUT OF ACTION. */
    public static final int BEEPER_SILENT = 7;
    /** The beeper's rules as before (OUT OF ACTION was set: its lost beeper may sound). */
    public static final int BEEPER_NORMAL = 8;
    /** Send {@link #status} to the PC. */
    public static final int REPORT = 9;

    public final int type;
    public final long ms;
    public final double heightM;
    public final int why;
    public final LaunchWire.Status status;

    private LaunchIntent(int type, long ms, double heightM, int why, LaunchWire.Status status) {
        this.type = type;
        this.ms = ms;
        this.heightM = heightM;
        this.why = why;
        this.status = status;
    }

    static LaunchIntent of(int type) {
        return new LaunchIntent(type, 0, 0, 0, null);
    }

    static LaunchIntent beep(long ms) {
        return new LaunchIntent(BEEP, ms, 0, 0, null);
    }

    static LaunchIntent takeoff(double heightM) {
        return new LaunchIntent(TAKEOFF, 0, heightM, 0, null);
    }

    static LaunchIntent clearMark(int why) {
        return new LaunchIntent(CLEAR_MARK, 0, 0, why, null);
    }

    static LaunchIntent report(LaunchWire.Status s) {
        return new LaunchIntent(REPORT, 0, 0, 0, s);
    }

    @Override
    public String toString() {
        switch (type) {
            case BEEP: return "BEEP " + ms;
            case SET_MODE_GUIDED: return "GUIDED";
            case ARM: return "ARM";
            case TAKEOFF: return "TAKEOFF " + heightM;
            case MOTORS_OFF: return "MOTORS OFF";
            case CLEAR_MARK: return "CLEAR MARK " + why;
            case BEEPER_SILENT: return "BEEPER SILENT";
            case BEEPER_NORMAL: return "BEEPER NORMAL";
            case REPORT: return "REPORT " + LaunchSequence.stateWord(status.state);
            default: return "?";
        }
    }
}
