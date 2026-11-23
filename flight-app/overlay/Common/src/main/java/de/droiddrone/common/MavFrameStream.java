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
 * DRAUPNE Flight (fleet): whole MAVLink 1 / 2 frames out of the flight controller's USB bytes as they come. Upstream's
 * parser read each USB read on its own: a frame split across two reads was lost, and a read that began inside a frame
 * was dropped whole (its first byte no start byte: "not MAVLink", the frames after it too). The fleet depends on every
 * frame - the mission protocol's answers, the commands' acks, the heartbeat's armed word.
 * <p>
 * The bytes of a frame not yet whole are kept for the next read; a candidate the caller refuses (its checksum wrong:
 * a start byte inside other bytes) is passed by one byte and the search goes on at the next start byte, without
 * dropping what follows. One reader thread. Java 11.
 */
public final class MavFrameStream {
    /** A frame candidate, whole by its header's length. @return true if it is a frame (taken), false to resync */
    public interface Frame {
        boolean take(byte[] d, int off, int len);
    }

    static final int STX_V1 = 0xFE, STX_V2 = 0xFD;
    /** The largest frame: MAVLink 2's header, 255 payload bytes, checksum and signature. */
    static final int MAX_FRAME = 10 + 255 + 2 + 13;

    private byte[] buf = new byte[4 * MAX_FRAME];
    private int have;

    /** Bytes as they came from the port; every whole frame found goes to frame, the rest waits for more. */
    public void feed(byte[] data, int len, Frame frame) {
        if (data == null || len <= 0) return;
        len = Math.min(len, data.length);
        int at = 0;
        while (at < len) {
            int n = Math.min(len - at, buf.length - have);
            System.arraycopy(data, at, buf, have, n);
            have += n;
            at += n;
            scan(frame);
            if (have == buf.length) dropTo(1); // can't happen with a scan that leaves at most one frame: never stuck
        }
    }

    /** Bytes kept for the next read (a frame not yet whole). */
    public int pending() {
        return have;
    }

    public void reset() {
        have = 0;
    }

    private void scan(Frame frame) {
        int i = 0;
        while (true) {
            while (i < have && (buf[i] & 0xff) != STX_V1 && (buf[i] & 0xff) != STX_V2) i++;
            if (i >= have) {
                have = 0;
                return;
            }
            int size = size(i);
            if (size < 0 || i + size > have) break; // not whole yet
            if (frame.take(buf, i, size)) i += size;
            else i++; // not a frame: the next start byte
        }
        dropTo(i);
    }

    /** A frame's length from its header at i, -1 while the header itself isn't here. */
    private int size(int i) {
        if (i + 2 > have) return -1;
        int payload = buf[i + 1] & 0xff;
        if ((buf[i] & 0xff) == STX_V1) return 6 + payload + 2;
        if (i + 3 > have) return -1;
        return 10 + payload + 2 + ((buf[i + 2] & 0x01) != 0 ? 13 : 0);
    }

    private void dropTo(int i) {
        if (i <= 0) return;
        System.arraycopy(buf, i, buf, 0, have - i);
        have -= i;
    }
}
