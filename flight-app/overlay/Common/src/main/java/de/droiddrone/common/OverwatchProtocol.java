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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * DRAUPNE Link 1.0.47 / DRAUPNE Flight pc.36: OVERWATCH SLEEP's packets. The phone sleeps as in deep sleep but its
 * camera and the motion detection go on; on a detection it calls the PC that put it to sleep. These packets never
 * travel in a link session: the phone sends them from a socket of its own (not the link port) to the PC's link port,
 * and the PC answers from a socket of its own to wherever they came from - so a PC that flies another drone at the
 * time takes them beside its link, and its sticks and picture never wait for them.
 * <p>
 * Every packet starts {@code [code u8][token u32]}: the token is the one the PC gave with its OVERWATCH request
 * (POWER_MODE mode 3); the phone takes commands with its token only, and the PC knows by it which drone speaks.
 * <ul>
 * <li>{@link #ALERT} phone to PC: {@code [event u16][alertNo u8][flags u8][startedAt i64 wall ms][box x, y, w, h u16 of
 * 65535][angle i16 0.01 deg right of the camera's axis][hfov u16 0.01 deg][lat i32][lon i32 1e-7 deg][posAge u16 s,
 * 65535 none][battery u8 %, 255 unknown][name UTF]} - again every {@link #ALERT_RESEND_MS} until the PC's ACK.</li>
 * <li>{@link #COMMAND} PC to phone: {@code [event u16][cmd u8][args]} - {@link #CMD_ACK}, {@link #CMD_PREVIEW} (keep
 * the live preview going; every second), {@link #CMD_DISMISS}, {@link #CMD_CONNECT} (wake fully: the PC connects),
 * {@link #CMD_ENDED_ACK}, {@link #CMD_CLIP_GET} {@code [offset u32][count u16]}, {@link #CMD_CLIP_DONE} {@code [ok u8]},
 * {@link #CMD_STATUS_ACK}, {@link #CMD_PREROLL} (the pre-roll again).</li>
 * <li>{@link #PREVIEW} phone to PC: {@code [event u16][unit u16][type u8][total u32][offset u32][bytes]} - one
 * fragment of a unit: a pre-roll JPEG ({@code [capturedAt i64][jpeg]}), the H.264 config, a key frame or a frame.</li>
 * <li>{@link #CLIP} phone to PC: {@code [event u16][type u8]...} - INFO {@code [size u32][sha256 32 bytes][durationMs
 * u32][startedAt i64][flags u8: bit 0 sound][width u16][height u16]}, CHUNK {@code [offset u32][bytes]}, ENDED
 * {@code [endedAt i64][clip u8: 1 a clip follows]}, NONE {@code [why UTF]}.</li>
 * <li>{@link #STATUS} phone to PC: {@code [state u8][reason u8][battery u8][tempDeci i16]} - the phone left overwatch
 * by itself for deep sleep (battery or heat, {@link PowerFloor}); five times, or until acknowledged.</li>
 * </ul>
 * Compiled into both apps (the PC's tests read and write every packet). Java 11: the phone app's language level.
 */
public final class OverwatchProtocol {
    public static final byte ALERT = 0x7A, COMMAND = 0x7B, PREVIEW = 0x7D, CLIP = 0x7E, STATUS = 0x7F;
    /** The power mode (POWER_MODE 0x6E) of overwatch sleep. */
    public static final int MODE = 3;
    /** The first phone app that knows it (the PC asks no older one). */
    public static final String MIN_PHONE_VERSION = "2.3.0-pc.36";

    public static final int CMD_ACK = 1, CMD_PREVIEW = 2, CMD_DISMISS = 3, CMD_CONNECT = 4, CMD_ENDED_ACK = 5,
            CMD_CLIP_GET = 6, CMD_CLIP_DONE = 7, CMD_STATUS_ACK = 8, CMD_PREROLL = 9;
    public static final int UNIT_PREROLL = 0, UNIT_CONFIG = 1, UNIT_KEY_FRAME = 2, UNIT_FRAME = 3;
    public static final int CLIP_INFO = 0, CLIP_CHUNK = 1, CLIP_ENDED = 2, CLIP_NONE = 3;
    /** STATUS states: still overwatching; left for deep sleep by itself. */
    public static final int STATE_WATCHING = 1, STATE_DROPPED = 2;
    /** ALERT flags. */
    public static final int ALERT_LIGHT = 1, ALERT_NIGHT = 2, ALERT_AGAIN = 4;
    /** POWER_MODE flags for mode 3: bit 0 = the clip records the phone's microphone. */
    public static final int MODE_FLAG_SOUND = 1;
    /** The sleep hello's added byte (pc.36): bit 0 overwatching, bit 1 an event runs. Then the floor's reason. */
    public static final int HELLO_OVERWATCH = 1, HELLO_EVENT = 2;

    /** The bytes of a preview fragment or a clip chunk: a datagram stays under the link's 1100. */
    public static final int FRAGMENT = 1024;
    public static final long ALERT_RESEND_MS = 1000;
    /** The phone streams the preview while the PC's last CMD_PREVIEW is younger than this. */
    public static final long PREVIEW_FRESH_MS = 3000;
    /** A header the PC's receive threads look at: an overwatch packet from any phone. */
    private static final int HEADER = 5;

    private OverwatchProtocol() {
    }

    /** Whether this datagram is one of the phone's overwatch packets (the PC's link threads hand it on as it is). */
    public static boolean isFromPhone(byte[] d, int len) {
        return len >= HEADER && (d[0] == ALERT || d[0] == PREVIEW || d[0] == CLIP || d[0] == STATUS);
    }

    public static int token(byte[] d) {
        return ((d[1] & 0xff) << 24) | ((d[2] & 0xff) << 16) | ((d[3] & 0xff) << 8) | (d[4] & 0xff);
    }

    // ------------------------------------------------------------------ the alert

    /** An ALERT, as sent and as read. Box and angle are those of the detection that started (or renewed) it. */
    public static final class Alert {
        public final int token, event, alertNo, flags;
        public final long startedAt;
        public final float x, y, w, h, angleDeg, hfovDeg;
        /** 1e-7 degrees; posAgeS 65535 = no position. */
        public final int latE7, lonE7, posAgeS, battery;
        public final String name;

        public Alert(int token, int event, int alertNo, int flags, long startedAt, float x, float y, float w, float h,
                     float angleDeg, float hfovDeg, int latE7, int lonE7, int posAgeS, int battery, String name) {
            this.token = token;
            this.event = event & 0xffff;
            this.alertNo = alertNo & 0xff;
            this.flags = flags & 0xff;
            this.startedAt = startedAt;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.angleDeg = angleDeg;
            this.hfovDeg = hfovDeg;
            this.latE7 = latE7;
            this.lonE7 = lonE7;
            this.posAgeS = posAgeS;
            this.battery = battery;
            this.name = name == null ? "" : name;
        }

        public boolean hasPosition() {
            return posAgeS != 0xffff && (latE7 != 0 || lonE7 != 0);
        }

        public boolean light() {
            return (flags & ALERT_LIGHT) != 0;
        }
    }

    public static byte[] alert(Alert a) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            DataOutputStream out = header(bytes, ALERT, a.token);
            out.writeShort(a.event);
            out.writeByte(a.alertNo);
            out.writeByte(a.flags);
            out.writeLong(a.startedAt);
            out.writeShort(unit(a.x));
            out.writeShort(unit(a.y));
            out.writeShort(unit(a.w));
            out.writeShort(unit(a.h));
            out.writeShort(clamp(Math.round(a.angleDeg * 100), -32768, 32767));
            out.writeShort(clamp(Math.round(a.hfovDeg * 100), 0, 65535));
            out.writeInt(a.latE7);
            out.writeInt(a.lonE7);
            out.writeShort(clamp(a.posAgeS, 0, 65535));
            out.writeByte(clamp(a.battery, 0, 255));
            out.writeUTF(a.name);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the alert, or null: not one, or cut short */
    public static Alert readAlert(byte[] d, int len) {
        if (len < HEADER || d[0] != ALERT) return null;
        try {
            DataInputStream in = body(d, len);
            int event = in.readUnsignedShort(), no = in.readUnsignedByte(), flags = in.readUnsignedByte();
            long started = in.readLong();
            float x = in.readUnsignedShort() / 65535f, y = in.readUnsignedShort() / 65535f;
            float w = in.readUnsignedShort() / 65535f, h = in.readUnsignedShort() / 65535f;
            float angle = in.readShort() / 100f, hfov = in.readUnsignedShort() / 100f;
            int lat = in.readInt(), lon = in.readInt(), age = in.readUnsignedShort(), battery = in.readUnsignedByte();
            String name = in.readUTF();
            return new Alert(token(d), event, no, flags, started, x, y, w, h, angle, hfov, lat, lon, age, battery, name);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the PC's commands

    public static final class Command {
        public final int token, event, cmd;
        /** CMD_CLIP_GET: the first byte and how many FRAGMENT-sized chunks; CMD_CLIP_DONE: ok in offset (1/0). */
        public final long offset;
        public final int count;

        public Command(int token, int event, int cmd, long offset, int count) {
            this.token = token;
            this.event = event & 0xffff;
            this.cmd = cmd;
            this.offset = offset;
            this.count = count;
        }
    }

    public static byte[] command(int token, int event, int cmd) {
        return command(token, event, cmd, 0, 0);
    }

    public static byte[] command(int token, int event, int cmd, long offset, int count) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
            DataOutputStream out = header(bytes, COMMAND, token);
            out.writeShort(event);
            out.writeByte(cmd);
            if (cmd == CMD_CLIP_GET) {
                out.writeInt((int) offset);
                out.writeShort(count);
            } else if (cmd == CMD_CLIP_DONE) {
                out.writeByte(offset != 0 ? 1 : 0);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Command readCommand(byte[] d, int len) {
        if (len < HEADER || d[0] != COMMAND) return null;
        try {
            DataInputStream in = body(d, len);
            int event = in.readUnsignedShort(), cmd = in.readUnsignedByte();
            long offset = 0;
            int count = 0;
            if (cmd == CMD_CLIP_GET) {
                offset = in.readInt() & 0xffffffffL;
                count = in.readUnsignedShort();
            } else if (cmd == CMD_CLIP_DONE) {
                offset = in.readUnsignedByte();
            }
            return new Command(token(d), event, cmd, offset, count);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the preview's fragments

    public static final class Fragment {
        public final int token, event, unit, type;
        public final long total, offset;
        public final byte[] data;
        public final int dataOffset, dataLength;

        public Fragment(int token, int event, int unit, int type, long total, long offset, byte[] data, int dataOffset, int dataLength) {
            this.token = token;
            this.event = event & 0xffff;
            this.unit = unit & 0xffff;
            this.type = type;
            this.total = total;
            this.offset = offset;
            this.data = data;
            this.dataOffset = dataOffset;
            this.dataLength = dataLength;
        }
    }

    /** A unit (a frame, the config, a pre-roll picture) cut into datagrams of FRAGMENT bytes at most. */
    public static List<byte[]> fragments(int token, int event, int unit, int type, byte[] data) {
        List<byte[]> out = new ArrayList<>(data.length / FRAGMENT + 1);
        int offset = 0;
        do {
            int n = Math.min(FRAGMENT, data.length - offset);
            byte[] p = new byte[HEADER + 13 + n];
            p[0] = PREVIEW;
            putInt(p, 1, token);
            p[5] = (byte) (event >> 8);
            p[6] = (byte) event;
            p[7] = (byte) (unit >> 8);
            p[8] = (byte) unit;
            p[9] = (byte) type;
            putInt(p, 10, data.length);
            putInt(p, 14, offset);
            System.arraycopy(data, offset, p, 18, n);
            out.add(p);
            offset += n;
        } while (offset < data.length);
        return out;
    }

    /** @return the fragment (its bytes not copied: data is d), or null */
    public static Fragment readFragment(byte[] d, int len) {
        if (len < HEADER + 13 || d[0] != PREVIEW) return null;
        int event = ((d[5] & 0xff) << 8) | (d[6] & 0xff);
        int unit = ((d[7] & 0xff) << 8) | (d[8] & 0xff);
        int type = d[9] & 0xff;
        long total = getInt(d, 10) & 0xffffffffL, offset = getInt(d, 14) & 0xffffffffL;
        int n = len - 18;
        if (offset + n > total) return null;
        return new Fragment(token(d), event, unit, type, total, offset, d, 18, n);
    }

    // ------------------------------------------------------------------ the clip

    public static final class ClipInfo {
        public final int token, event;
        public final long size, durationMs, startedAt;
        public final byte[] sha256;
        public final boolean sound;
        public final int width, height;

        public ClipInfo(int token, int event, long size, byte[] sha256, long durationMs, long startedAt, boolean sound, int width, int height) {
            this.token = token;
            this.event = event & 0xffff;
            this.size = size;
            this.sha256 = sha256;
            this.durationMs = durationMs;
            this.startedAt = startedAt;
            this.sound = sound;
            this.width = width;
            this.height = height;
        }
    }

    public static byte[] clipInfo(ClipInfo c) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(80);
            DataOutputStream out = header(bytes, CLIP, c.token);
            out.writeShort(c.event);
            out.writeByte(CLIP_INFO);
            out.writeInt((int) c.size);
            out.write(c.sha256, 0, 32);
            out.writeInt((int) Math.min(0xffffffffL, c.durationMs));
            out.writeLong(c.startedAt);
            out.writeByte(c.sound ? 1 : 0);
            out.writeShort(c.width);
            out.writeShort(c.height);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] clipChunk(int token, int event, long offset, byte[] data, int from, int n) {
        byte[] p = new byte[HEADER + 3 + 4 + n];
        p[0] = CLIP;
        putInt(p, 1, token);
        p[5] = (byte) (event >> 8);
        p[6] = (byte) event;
        p[7] = CLIP_CHUNK;
        putInt(p, 8, (int) offset);
        System.arraycopy(data, from, p, 12, n);
        return p;
    }

    public static byte[] clipEnded(int token, int event, long endedAt, boolean clipFollows) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(24);
            DataOutputStream out = header(bytes, CLIP, token);
            out.writeShort(event);
            out.writeByte(CLIP_ENDED);
            out.writeLong(endedAt);
            out.writeByte(clipFollows ? 1 : 0);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] clipNone(int token, int event, String why) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            DataOutputStream out = header(bytes, CLIP, token);
            out.writeShort(event);
            out.writeByte(CLIP_NONE);
            out.writeUTF(why == null ? "" : why);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The type of a CLIP packet (CLIP_*), or -1. */
    public static int clipType(byte[] d, int len) {
        return len >= HEADER + 3 && d[0] == CLIP ? d[7] & 0xff : -1;
    }

    public static int clipEvent(byte[] d) {
        return ((d[5] & 0xff) << 8) | (d[6] & 0xff);
    }

    public static ClipInfo readClipInfo(byte[] d, int len) {
        if (clipType(d, len) != CLIP_INFO) return null;
        try {
            DataInputStream in = body(d, len);
            int event = in.readUnsignedShort();
            in.readUnsignedByte();
            long size = in.readInt() & 0xffffffffL;
            byte[] sha = new byte[32];
            in.readFully(sha);
            long duration = in.readInt() & 0xffffffffL;
            long started = in.readLong();
            boolean sound = in.readUnsignedByte() != 0;
            int w = in.readUnsignedShort(), h = in.readUnsignedShort();
            return new ClipInfo(token(d), event, size, sha, duration, started, sound, w, h);
        } catch (IOException e) {
            return null;
        }
    }

    /** A CHUNK's first byte in the clip; its bytes are d[12 .. len). */
    public static long chunkOffset(byte[] d) {
        return getInt(d, 8) & 0xffffffffL;
    }

    public static final int CHUNK_DATA_AT = 12;

    /** ENDED: {endedAt, clipFollows 1/0}, or null. */
    public static long[] readClipEnded(byte[] d, int len) {
        if (clipType(d, len) != CLIP_ENDED || len < 17) return null;
        long ended = 0;
        for (int i = 0; i < 8; i++) ended = (ended << 8) | (d[8 + i] & 0xff);
        return new long[]{ended, len > 16 ? d[16] & 0xff : 0};
    }

    public static String readClipNone(byte[] d, int len) {
        if (clipType(d, len) != CLIP_NONE) return null;
        try {
            DataInputStream in = body(d, len);
            in.readUnsignedShort();
            in.readUnsignedByte();
            return in.readUTF();
        } catch (IOException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ the phone's own word

    public static byte[] status(int token, int state, int reason, int battery, int tempDeci) {
        byte[] p = new byte[HEADER + 5];
        p[0] = STATUS;
        putInt(p, 1, token);
        p[5] = (byte) state;
        p[6] = (byte) reason;
        p[7] = (byte) clamp(battery, 0, 255);
        int t = clamp(tempDeci, -32768, 32767);
        p[8] = (byte) (t >> 8);
        p[9] = (byte) t;
        return p;
    }

    /** {state, reason, battery, tempDeci}, or null. */
    public static int[] readStatus(byte[] d, int len) {
        if (len < HEADER + 5 || d[0] != STATUS) return null;
        return new int[]{d[5] & 0xff, d[6] & 0xff, d[7] & 0xff, (short) (((d[8] & 0xff) << 8) | (d[9] & 0xff))};
    }

    // ------------------------------------------------------------------ the request (POWER_MODE mode 3)

    /** The OVERWATCH request: {@code [0x6E][3][checkIntervalMin u8][sensitivity u8][flags u8][token u32]}. */
    public static byte[] request(byte powerModeCode, int checkIntervalMin, int sensitivity, int flags, int token) {
        byte[] p = new byte[10];
        p[0] = powerModeCode;
        p[1] = MODE;
        p[2] = (byte) clamp(checkIntervalMin, 0, 255);
        p[3] = (byte) clamp(sensitivity, 1, 3);
        p[4] = (byte) flags;
        putInt(p, 5, token);
        return p;
    }

    /** {sensitivity, flags, token} of a mode 3 request's tail (bytes 3..9), or null when it has none. */
    public static int[] readRequestTail(byte[] d, int offset, int len) {
        if (len - offset < 7) return null;
        return new int[]{clamp(d[offset] & 0xff, 1, 3), d[offset + 1] & 0xff, getInt(d, offset + 2)};
    }

    // ------------------------------------------------------------------ helpers

    private static DataOutputStream header(ByteArrayOutputStream bytes, byte code, int token) throws IOException {
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(code);
        out.writeInt(token);
        return out;
    }

    private static DataInputStream body(byte[] d, int len) {
        return new DataInputStream(new ByteArrayInputStream(d, HEADER, len - HEADER));
    }

    private static int unit(float f) {
        return clamp(Math.round(f * 65535), 0, 65535);
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void putInt(byte[] p, int at, int v) {
        p[at] = (byte) (v >> 24);
        p[at + 1] = (byte) (v >> 16);
        p[at + 2] = (byte) (v >> 8);
        p[at + 3] = (byte) v;
    }

    private static int getInt(byte[] d, int at) {
        return ((d[at] & 0xff) << 24) | ((d[at + 1] & 0xff) << 16) | ((d[at + 2] & 0xff) << 8) | (d[at + 3] & 0xff);
    }
}
