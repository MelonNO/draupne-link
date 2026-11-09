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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DRAUPNE Flight / DRAUPNE Link: MSP v1 ("$M") and v2 ("$X") frames, both ways - the one copy the phone's Msp (the
 * flight controller on the phone's USB) and the PC's BetaflightUsb (Drone setup) both use; tested on the PC.
 * <p>
 * Reading: the frames in a byte stream. A USB read ends wherever it ends, so a frame that isn't complete yet is kept
 * for the next read (upstream dropped the rest of a read at the first frame cut short: long replies such as BOXNAMES
 * and OSD_CONFIG, split across two reads, were lost, asked for again, and lost again); a byte that is no frame start,
 * or a frame with a bad checksum, is stepped over one byte at a time until the next frame start. One reader per
 * stream, on one thread.
 */
public final class MspFrames {
    public static final byte START = '$';
    public static final byte V1 = 'M';
    public static final byte V2 = 'X';
    public static final byte REQUEST = '<';
    public static final byte RESPONSE = '>';
    public static final byte ERROR = '!';
    public static final int V1_HEADER_SIZE = 5;
    public static final int V2_HEADER_SIZE = 8;
    public static final int CRC_SIZE = 1;
    /** No MSP reply is longer: a v2 header saying more is no frame start, and a longer rest isn't kept. */
    public static final int MAX_CARRY = 4096;

    /** One frame read: its version (V1 or V2), type (REQUEST, RESPONSE or ERROR), v2 flag, code and payload. */
    public static final class Frame {
        public final byte version;
        public final byte type;
        public final byte flag;
        public final int code;
        public final int payloadSize;
        /** null when empty. */
        public final byte[] payload;

        Frame(byte version, byte type, byte flag, int code, int payloadSize, byte[] payload) {
            this.version = version;
            this.type = type;
            this.flag = flag;
            this.code = code;
            this.payloadSize = payloadSize;
            this.payload = payload;
        }

        public boolean isError() {
            return type == ERROR;
        }
    }

    /** V1 or V2 to read only those (a "$M" in a v2 stream is no frame start), 0 for both. */
    private final byte only;
    /** A frame cut by the end of a read, waiting for the rest. */
    private byte[] carry;

    /** A reader of v1 and v2 frames. */
    public MspFrames() {
        this((byte) 0);
    }

    /** A reader of one version's frames: V1 or V2. */
    public MspFrames(byte version) {
        only = version;
    }

    /** The whole frames in the first length bytes of data, with what the last read left over in front. */
    public List<Frame> feed(byte[] data, int length) {
        List<Frame> frames = new ArrayList<>();
        byte[] s;
        if (carry != null) {
            s = new byte[carry.length + length];
            System.arraycopy(carry, 0, s, 0, carry.length);
            System.arraycopy(data, 0, s, carry.length, length);
            carry = null;
        } else {
            s = length == data.length ? data : Arrays.copyOf(data, length);
        }
        int len = s.length;
        int i = 0;
        while (i < len) {
            if (s[i] != START) {
                i++;
                continue;
            }
            if (len - i < 3) break; // the rest of the header comes with the next read
            byte magic = s[i + 1];
            byte type = s[i + 2];
            boolean isV2 = magic == V2;
            if (magic != V1 && !isV2 || only != 0 && magic != only || type != REQUEST && type != RESPONSE && type != ERROR) {
                i++;
                continue;
            }
            int frameSize;
            if (isV2) {
                if (len - i < V2_HEADER_SIZE) break;
                byte flag = s[i + 3];
                int code = (s[i + 4] & 0xFF) | (s[i + 5] & 0xFF) << 8;
                int payloadSize = (s[i + 6] & 0xFF) | (s[i + 7] & 0xFF) << 8;
                if (payloadSize > MAX_CARRY) { // not a frame start
                    i++;
                    continue;
                }
                frameSize = V2_HEADER_SIZE + payloadSize + CRC_SIZE;
                if (len - i < frameSize) break;
                byte[] payload = payloadSize > 0 ? Arrays.copyOfRange(s, i + V2_HEADER_SIZE, i + V2_HEADER_SIZE + payloadSize) : null;
                if ((s[i + frameSize - 1] & 0xFF) == crcV2(flag, code, payloadSize, payload)) {
                    frames.add(new Frame(V2, type, flag, code, payloadSize, payload));
                    i += frameSize;
                } else {
                    i++;
                }
            } else {
                if (len - i < V1_HEADER_SIZE) break;
                int payloadSize = s[i + 3] & 0xFF;
                int code = s[i + 4] & 0xFF;
                frameSize = V1_HEADER_SIZE + payloadSize + CRC_SIZE;
                if (len - i < frameSize) break;
                byte[] payload = payloadSize > 0 ? Arrays.copyOfRange(s, i + V1_HEADER_SIZE, i + V1_HEADER_SIZE + payloadSize) : null;
                if ((s[i + frameSize - 1] & 0xFF) == crcV1(code, payloadSize, payload)) {
                    frames.add(new Frame(V1, type, (byte) 0, code, payloadSize, payload));
                    i += frameSize;
                } else {
                    i++;
                }
            }
        }
        if (i < len && len - i <= MAX_CARRY) carry = Arrays.copyOfRange(s, i, len);
        return frames;
    }

    /** Forgets a frame begun in the last read (another flight controller, or the port opened again). */
    public void reset() {
        carry = null;
    }

    /** "$M<", size, code, payload, checksum (XOR of size, code and payload); payload may be null. */
    public static byte[] v1Request(int code, byte[] payload) {
        int size = payload == null ? 0 : payload.length;
        if (size > 255 || code < 0 || code > 255) throw new IllegalArgumentException("Not an MSP v1 request");
        byte[] f = new byte[V1_HEADER_SIZE + size + CRC_SIZE];
        f[0] = START;
        f[1] = V1;
        f[2] = REQUEST;
        f[3] = (byte) size;
        f[4] = (byte) code;
        if (size > 0) System.arraycopy(payload, 0, f, V1_HEADER_SIZE, size);
        f[V1_HEADER_SIZE + size] = (byte) crcV1(code, size, payload);
        return f;
    }

    /** "$X<", flag 0, code u16, size u16, payload, CRC-8/DVB-S2 over flag to payload; payload may be null. */
    public static byte[] v2Request(int code, byte[] payload) {
        int size = payload == null ? 0 : payload.length;
        if (code < 0 || code > 0xffff || size > 0xffff) throw new IllegalArgumentException("Not an MSP v2 request");
        byte[] f = new byte[V2_HEADER_SIZE + size + CRC_SIZE];
        f[0] = START;
        f[1] = V2;
        f[2] = REQUEST;
        f[3] = 0;
        f[4] = (byte) code;
        f[5] = (byte) (code >> 8);
        f[6] = (byte) size;
        f[7] = (byte) (size >> 8);
        if (size > 0) System.arraycopy(payload, 0, f, V2_HEADER_SIZE, size);
        f[V2_HEADER_SIZE + size] = (byte) crcV2((byte) 0, code, size, payload);
        return f;
    }

    /** MSP v2's checksum: CRC-8 with polynomial 0xD5 (DVB-S2), from 0, no reflection; b is taken as unsigned. */
    public static int crc8DvbS2(int crc, int b) {
        crc ^= b & 0xff;
        for (int i = 0; i < 8; i++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0xd5) & 0xff : (crc << 1) & 0xff;
        return crc;
    }

    private static int crcV2(byte flag, int code, int payloadSize, byte[] payload) {
        int crc = crc8DvbS2(0, flag);
        crc = crc8DvbS2(crc, code);
        crc = crc8DvbS2(crc, code >> 8);
        crc = crc8DvbS2(crc, payloadSize);
        crc = crc8DvbS2(crc, payloadSize >> 8);
        for (int i = 0; i < payloadSize; i++) crc = crc8DvbS2(crc, payload[i]);
        return crc;
    }

    /**
     * XOR of size, code and payload, the bytes unsigned (the phone's copy XORed signed bytes: a v1 reply with an odd
     * count of bytes over 127 never matched - unused there, as the phone asks in v2).
     */
    private static int crcV1(int code, int payloadSize, byte[] payload) {
        int crc = (code ^ payloadSize) & 0xff;
        for (int i = 0; i < payloadSize; i++) crc ^= payload[i] & 0xff;
        return crc;
    }
}
