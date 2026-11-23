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
 * DRAUPNE Flight (fleet PRD AP-6, G2): a command from the pilot's PC to an ArduPilot flight controller, taken only in
 * the pilot's own session (never from an observer, a spectator or a fleet mode packet), on the receive thread.
 * [0x30][kind u8][arguments]:
 * <ul>
 *   <li>MODE: [1][custom mode i32] - LOITER, RTL, LAND ... (ArduPilot Copter's numbers); MAV_CMD_DO_SET_MODE</li>
 *   <li>MISSION_START: [2] - AUTO, then MAV_CMD_MISSION_START (the uploaded route from its first item)</li>
 *   <li>PAUSE: [3] - LOITER where it is (a mission is held; START or CONTINUE goes on)</li>
 *   <li>CONTINUE: [4] - AUTO again, the mission from the item it was on</li>
 *   <li>GO_LOOK: [5][lat i32 1e7][lon i32 1e7] (fleet AP-12) - fly there at the height it has (DO_REPOSITION, GUIDED) and
 *       point the camera at that ground point (DO_SET_ROI_LOCATION)</li>
 * </ul>
 * The phone answers each with the FC's COMMAND_ACK as telemetry record {@link #ACK_RECORD}: [command u16][result u8]
 * (MAV_RESULT: 0 accepted, 1 temporarily rejected, 2 denied, 3 unsupported, 4 failed), or result {@link #NO_FC} when
 * the phone had no MAVLink flight controller to give it to. Java 11.
 */
public final class FcCommand {
    public static final byte PACKET = 0x30;
    public static final int MODE = 1, MISSION_START = 2, PAUSE = 3, CONTINUE = 4, GO_LOOK = 5;
    /** Telemetry record: the flight controller's answer to a command (a DD_ code of DRAUPNE's fleet range). */
    public static final short ACK_RECORD = 0x40E0;
    public static final int NO_FC = 255;

    public final int kind;
    /** MODE: the custom mode; otherwise 0. */
    public final int mode;
    /** GO_LOOK: the ground point (degrees); otherwise NaN. */
    public final double lat, lon;

    private FcCommand(int kind, int mode) {
        this(kind, mode, Double.NaN, Double.NaN);
    }

    private FcCommand(int kind, int mode, double lat, double lon) {
        this.kind = kind;
        this.mode = mode;
        this.lat = lat;
        this.lon = lon;
    }

    /**
     * A place off the globe (or NaN) is sent as 0 / 0, which every phone refuses: the int cast of a larger number would
     * wrap round to another real place (lon 400 became -29.5).
     */
    public static byte[] goLook(double lat, double lon) {
        boolean ok = Math.abs(lat) <= 90 && Math.abs(lon) <= 180; // false for NaN too
        int a = ok ? (int) Math.round(lat * 1e7) : 0, o = ok ? (int) Math.round(lon * 1e7) : 0;
        return new byte[]{PACKET, GO_LOOK, (byte) (a >>> 24), (byte) (a >>> 16), (byte) (a >>> 8), (byte) a,
                (byte) (o >>> 24), (byte) (o >>> 16), (byte) (o >>> 8), (byte) o};
    }

    public static byte[] mode(int customMode) {
        return new byte[]{PACKET, MODE, (byte) (customMode >>> 24), (byte) (customMode >>> 16), (byte) (customMode >>> 8), (byte) customMode};
    }

    public static byte[] simple(int kind) {
        return new byte[]{PACKET, (byte) kind};
    }

    /** The command in a datagram, or null: too short, or a kind this phone doesn't know. */
    public static FcCommand read(byte[] d, int len) {
        if (d == null) return null;
        len = Math.min(len, d.length); // a length that claims more than the array holds
        if (len < 2 || d[0] != PACKET) return null;
        int kind = d[1] & 0xff;
        switch (kind) {
            case MODE:
                if (len < 6) return null;
                int m = (d[2] & 0xff) << 24 | (d[3] & 0xff) << 16 | (d[4] & 0xff) << 8 | (d[5] & 0xff);
                if (m < 0 || m > 255) return null; // Copter's modes are small numbers: anything else is garbage
                return new FcCommand(MODE, m);
            case MISSION_START:
            case PAUSE:
            case CONTINUE:
                return new FcCommand(kind, 0);
            case GO_LOOK: {
                if (len < 10) return null;
                int a = (d[2] & 0xff) << 24 | (d[3] & 0xff) << 16 | (d[4] & 0xff) << 8 | (d[5] & 0xff);
                int o = (d[6] & 0xff) << 24 | (d[7] & 0xff) << 16 | (d[8] & 0xff) << 8 | (d[9] & 0xff);
                double lat = a / 1e7, lon = o / 1e7;
                if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || a == 0 && o == 0) return null;
                return new FcCommand(GO_LOOK, 0, lat, lon);
            }
            default:
                return null;
        }
    }

    /**
     * GO_LOOK's DO_REPOSITION height: the height above home the drone has now (frame GLOBAL_RELATIVE_ALT_INT), NaN when
     * it isn't known - then nothing is sent and the PC is told FAILED. ArduPilot Copter 4.7.3 DENIES a reposition whose
     * height is NaN (SITL, 2026-11-23): "the height it has" must be given as a number - GO LOOK never moved a drone.
     */
    public static float goLookHeight(double relAltM) {
        return Double.isNaN(relAltM) || Double.isInfinite(relAltM) || Math.abs(relAltM) > 10_000 ? Float.NaN : (float) relAltM;
    }

    /** The answer record's payload (after the code). */
    public static byte[] ack(int mavCommand, int result) {
        return new byte[]{(byte) (mavCommand >>> 8), (byte) mavCommand, (byte) result};
    }
}
