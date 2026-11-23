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
 * DRAUPNE Flight (fleet PRD AP-1): is the flight controller on the port speaking MAVLink? With the protocol on AUTO the
 * phone first picks MSP unless the USB device calls itself ArduPilot or PX4 - and many ArduPilot boards carry their
 * maker's name instead (CubePilot, Holybro, Matek ...). ArduPilot sends a heartbeat once a second on its USB port
 * without being asked; this reads the bytes the MSP side gets and says when one has come: a whole MAVLink 1 or 2
 * frame with a good checksum, message HEARTBEAT, from an autopilot (ArduPilot or PX4), not from a ground station.
 * The phone then reads the port as MAVLink - no restart of the port, and the heartbeat itself carries the armed flag.
 * Java 11 (the phone), no allocation per byte; one reader thread.
 */
public final class MavlinkSniff {
    static final int STX_V1 = 0xFE, STX_V2 = 0xFD;
    static final int MSG_HEARTBEAT = 0;
    /** HEARTBEAT's CRC_EXTRA (MAVLink's common definition) and its payload length. */
    static final int HEARTBEAT_CRC_EXTRA = 50, HEARTBEAT_LEN = 9;
    public static final int AUTOPILOT_ARDUPILOT = 3, AUTOPILOT_PX4 = 12;
    static final int MAV_TYPE_GCS = 6;

    private final byte[] frame = new byte[12 + 255 + 13];
    private int have;
    private int need;
    private boolean found;
    private int autopilot = -1;

    /** Bytes as they came from the port. True once a heartbeat has been seen (and from then on). */
    public boolean feed(byte[] buf, int len) {
        int n = buf == null ? 0 : Math.min(len, buf.length); // a length that claims more than the array holds
        for (int i = 0; i < n && !found; i++) take(buf[i] & 0xff);
        return found;
    }

    public boolean found() {
        return found;
    }

    /** MAV_AUTOPILOT of the heartbeat seen (3 ArduPilot, 12 PX4), -1 before. */
    public int autopilot() {
        return autopilot;
    }

    /** Back to nothing seen (a new port). */
    public void reset() {
        have = 0;
        need = 0;
        found = false;
        autopilot = -1;
    }

    private void take(int b) {
        if (have == 0) {
            if (b == STX_V1 || b == STX_V2) frame[have++] = (byte) b;
            return;
        }
        frame[have++] = (byte) b;
        if (have == 2) {
            int payload = b;
            boolean v2 = (frame[0] & 0xff) == STX_V2;
            need = v2 ? 10 + payload + 2 : 6 + payload + 2;
            // a signed v2 frame has 13 bytes more: known only from the flags (the third byte)
        }
        if (have == 3 && (frame[0] & 0xff) == STX_V2 && (b & 0x01) != 0) need += 13;
        if (need > 0 && have >= need) {
            if (!check()) resync();
            else have = 0;
            need = 0;
        }
    }

    /** A frame that failed: look for the next start byte inside it. */
    private void resync() {
        int from = -1;
        for (int i = 1; i < have; i++) {
            int b = frame[i] & 0xff;
            if (b == STX_V1 || b == STX_V2) {
                from = i;
                break;
            }
        }
        int n = have;
        have = 0;
        need = 0;
        if (from < 0) return;
        byte[] rest = new byte[n - from];
        System.arraycopy(frame, from, rest, 0, rest.length);
        for (byte r : rest) {
            if (found) return;
            take(r & 0xff);
        }
    }

    private boolean check() {
        boolean v2 = (frame[0] & 0xff) == STX_V2;
        int len = frame[1] & 0xff;
        int header = v2 ? 10 : 6;
        int msgId = v2 ? (frame[7] & 0xff) | (frame[8] & 0xff) << 8 | (frame[9] & 0xff) << 16 : frame[5] & 0xff;
        if (msgId != MSG_HEARTBEAT) return true; // a whole frame of another kind: skipped (its checksum not known here)
        if (len < 1 || len > HEARTBEAT_LEN) return false;
        int crc = 0xffff;
        for (int i = 1; i < header + len; i++) crc = crcAccumulate(frame[i] & 0xff, crc);
        crc = crcAccumulate(HEARTBEAT_CRC_EXTRA, crc);
        int got = (frame[header + len] & 0xff) | (frame[header + len + 1] & 0xff) << 8;
        if (crc != got) return false;
        // MAVLink 2 cuts trailing zero bytes of a payload: what is missing reads as 0
        byte[] p = new byte[HEARTBEAT_LEN];
        System.arraycopy(frame, header, p, 0, len);
        int type = p[4] & 0xff, ap = p[5] & 0xff;
        if (type == MAV_TYPE_GCS) return true;
        if (ap != AUTOPILOT_ARDUPILOT && ap != AUTOPILOT_PX4) return true;
        autopilot = ap;
        found = true;
        return true;
    }

    /** MAVLink's X.25 checksum, one byte. */
    static int crcAccumulate(int b, int crc) {
        int tmp = b ^ (crc & 0xff);
        tmp = (tmp ^ (tmp << 4)) & 0xff;
        return ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xffff;
    }
}
