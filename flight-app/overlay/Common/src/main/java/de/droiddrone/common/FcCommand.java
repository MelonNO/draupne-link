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
 * </ul>
 * The phone answers each with the FC's COMMAND_ACK as telemetry record {@link #ACK_RECORD}: [command u16][result u8]
 * (MAV_RESULT: 0 accepted, 1 temporarily rejected, 2 denied, 3 unsupported, 4 failed), or result {@link #NO_FC} when
 * the phone had no MAVLink flight controller to give it to. Java 11.
 */
public final class FcCommand {
    public static final byte PACKET = 0x30;
    public static final int MODE = 1, MISSION_START = 2, PAUSE = 3, CONTINUE = 4;
    /** Telemetry record: the flight controller's answer to a command (a DD_ code of DRAUPNE's fleet range). */
    public static final short ACK_RECORD = 0x40E0;
    public static final int NO_FC = 255;

    public final int kind;
    /** MODE: the custom mode; otherwise 0. */
    public final int mode;

    private FcCommand(int kind, int mode) {
        this.kind = kind;
        this.mode = mode;
    }

    public static byte[] mode(int customMode) {
        return new byte[]{PACKET, MODE, (byte) (customMode >>> 24), (byte) (customMode >>> 16), (byte) (customMode >>> 8), (byte) customMode};
    }

    public static byte[] simple(int kind) {
        return new byte[]{PACKET, (byte) kind};
    }

    /** The command in a datagram, or null: too short, or a kind this phone doesn't know. */
    public static FcCommand read(byte[] d, int len) {
        if (d == null || len < 2 || d[0] != PACKET) return null;
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
            default:
                return null;
        }
    }

    /** The answer record's payload (after the code). */
    public static byte[] ack(int mavCommand, int result) {
        return new byte[]{(byte) (mavCommand >>> 8), (byte) mavCommand, (byte) result};
    }
}
