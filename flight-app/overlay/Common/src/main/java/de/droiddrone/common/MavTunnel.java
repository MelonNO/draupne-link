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
 * DRAUPNE Flight (fleet PRD RT-8, AC-10, AC-25, AC-41, FD-8, AP-11): the PC's way to an ArduPilot flight controller's
 * own protocols over the link - routes (the mission protocol), parameters (RTL_ALT, rally points), guided targets and
 * the other drones as traffic - as whole MAVLink frames in a numbered (resent) packet [0x32][frames]. Only what is
 * listed here passes, each way:
 * <ul>
 *   <li>PC to FC: parameters, missions, a guided position target, ADS-B traffic, and COMMAND_LONG / COMMAND_INT with
 *       one of {@link #COMMANDS} (modes, mission start / pause, reposition, region of interest, speed). Never stick
 *       overrides (the sticks go their own way), never arming, calibration, motor test or reboot.</li>
 *   <li>FC to PC: the answers to those, its heartbeat (armed, mode) - and only while the PC uses the tunnel (a frame
 *       from it in the last {@link #OPEN_MS}): a normal flight gets none of it.</li>
 * </ul>
 * The phone takes it in its pilot's session only. Java 11, no allocation per byte.
 */
public final class MavTunnel {
    public static final byte PACKET = 0x32;
    /** The phone sends the FC's answers while the PC used the tunnel this recently. */
    public static final long OPEN_MS = 30_000;

    static final int HEARTBEAT = 0, PARAM_REQUEST_READ = 20, PARAM_REQUEST_LIST = 21, PARAM_VALUE = 22, PARAM_SET = 23,
            MISSION_REQUEST = 40, MISSION_SET_CURRENT = 41, MISSION_CURRENT = 42, MISSION_REQUEST_LIST = 43, MISSION_COUNT = 44,
            MISSION_CLEAR_ALL = 45, MISSION_ITEM_REACHED = 46, MISSION_ACK = 47, MISSION_REQUEST_INT = 51, MISSION_ITEM_INT = 73,
            COMMAND_INT = 75, COMMAND_LONG = 76, COMMAND_ACK = 77, SET_POSITION_TARGET_GLOBAL_INT = 86,
            LOG_REQUEST_LIST = 117, LOG_ENTRY = 118, LOG_REQUEST_DATA = 119, LOG_DATA = 120, LOG_REQUEST_END = 122,
            POSITION_TARGET_GLOBAL_INT = 87, HOME_POSITION = 242, ADSB_VEHICLE = 246;

    /** The MAV_CMDs the PC may send: modes, mission control, pointing and speed - never arming or the bench's. */
    public static final int[] COMMANDS = {
            176, // DO_SET_MODE
            300, // MISSION_START
            193, // DO_PAUSE_CONTINUE
            192, // DO_REPOSITION
            195, // DO_SET_ROI_LOCATION
            197, // DO_SET_ROI_NONE
            178, // DO_CHANGE_SPEED
            115, // CONDITION_YAW
            511, // SET_MESSAGE_INTERVAL
            512, // REQUEST_MESSAGE
    };

    private MavTunnel() {
    }

    /** Whether a whole frame from the PC may be written to the flight controller. */
    public static boolean toFc(byte[] f, int off, int len) {
        int id = msgId(f, off, len);
        switch (id) {
            case PARAM_REQUEST_READ:
            case PARAM_REQUEST_LIST:
            case PARAM_SET:
            case MISSION_REQUEST_LIST:
            case MISSION_COUNT:
            case MISSION_CLEAR_ALL:
            case MISSION_ACK:
            case MISSION_SET_CURRENT:
            case MISSION_REQUEST_INT:
            case MISSION_REQUEST:
            case MISSION_ITEM_INT:
            case SET_POSITION_TARGET_GLOBAL_INT:
            case LOG_REQUEST_LIST: // AP-10: the flight log after landing (the phone passes these only while disarmed)
            case LOG_REQUEST_DATA:
            case LOG_REQUEST_END:
            case ADSB_VEHICLE:
                return true;
            case COMMAND_LONG:
            case COMMAND_INT: {
                int c = command(f, off, len);
                for (int allowed : COMMANDS) if (c == allowed) return true;
                return false;
            }
            default:
                return false;
        }
    }

    /** AP-10: a log request - the phone writes it to the FC only while it is disarmed (a log read slows the FC). */
    public static boolean isLogRequest(int msgId) {
        return msgId == LOG_REQUEST_LIST || msgId == LOG_REQUEST_DATA || msgId == LOG_REQUEST_END;
    }

    /** Whether a message from the flight controller goes to the PC (while the tunnel is open). */
    public static boolean toPc(int msgId) {
        switch (msgId) {
            case HEARTBEAT:
            case PARAM_VALUE:
            case MISSION_REQUEST:
            case MISSION_REQUEST_INT:
            case MISSION_COUNT:
            case MISSION_ITEM_INT:
            case MISSION_ACK:
            case MISSION_CURRENT:
            case MISSION_ITEM_REACHED:
            case COMMAND_ACK:
            case POSITION_TARGET_GLOBAL_INT:
            case LOG_ENTRY:
            case LOG_DATA:
            case HOME_POSITION:
                return true;
            default:
                return false;
        }
    }

    /** The frames in a packet's payload: each whole frame's start and length, or stop at the first broken one. */
    public interface FrameSink {
        void frame(byte[] data, int off, int len);
    }

    /** Splits a payload into whole MAVLink 1 / 2 frames (lengths by their headers); a broken tail is dropped. */
    public static int split(byte[] d, int off, int len, FrameSink sink) {
        int i = off, end = off + len, n = 0;
        while (i < end) {
            int stx = d[i] & 0xff;
            if (stx != 0xFE && stx != 0xFD) break;
            if (i + 2 > end) break;
            int payload = d[i + 1] & 0xff;
            int size;
            if (stx == 0xFE) {
                size = 6 + payload + 2;
            } else {
                if (i + 3 > end) break;
                size = 10 + payload + 2 + ((d[i + 2] & 0x01) != 0 ? 13 : 0);
            }
            if (i + size > end) break;
            sink.frame(d, i, size);
            n++;
            i += size;
        }
        return n;
    }

    /** A frame's message id, -1 if it isn't a whole frame. */
    public static int msgId(byte[] f, int off, int len) {
        if (len < 8) return -1;
        int stx = f[off] & 0xff;
        if (stx == 0xFE) return f[off + 5] & 0xff;
        if (stx == 0xFD && len >= 12) return (f[off + 7] & 0xff) | (f[off + 8] & 0xff) << 8 | (f[off + 9] & 0xff) << 16;
        return -1;
    }

    /** COMMAND_LONG / COMMAND_INT's command (bytes 28-29 of the payload, little-endian), -1 if cut short. */
    static int command(byte[] f, int off, int len) {
        int header = (f[off] & 0xff) == 0xFE ? 6 : 10;
        int payload = f[off + 1] & 0xff;
        if (payload < 30 || len < header + 30) return -1;
        int at = off + header + 28;
        return (f[at] & 0xff) | (f[at + 1] & 0xff) << 8;
    }
}
