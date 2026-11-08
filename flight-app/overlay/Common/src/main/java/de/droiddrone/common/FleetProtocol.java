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

/**
 * DRAUPNE Link 1.0.52 / DRAUPNE Flight pc.40: FLEET MANAGEMENT. A PC changes a phone's power mode without a link
 * session, and every phone says how it is doing wherever it already answers.
 * <ul>
 * <li>{@link #MODE} PC to phone, on the phone's link port, keyed like the names: {@code [0x58][key UTF][token i32]
 * [mode u8: 1 standby, 2 deep sleep, 3 overwatch][checkIntervalMin u8 (SleepCheck)][sensitivity u8][flags u8]
 * [owToken i32][pcPort u16]}: pcPort is the PC's link port - the phone's sleep hellos and overwatch alerts go to that PC
 * from then on, as to the PC it last flew with. Sent to an awake idle phone at each name scan, and to a sleeping one right after its sleep hello
 * (before the PC's answer), until the phone's {@link Status} says that token was taken. The phone takes it in any
 * state except a pilot session, once per token, and refuses (as POWER_MODE does) unless its FC is known disarmed.
 * Sensitivity, flags and owToken are POWER_MODE mode 3's (OverwatchProtocol), read for mode 3 only.</li>
 * <li>{@link Status}: a block after the NAME_REPLY's power byte and after the sleep hello's floorReason - {@code [ver u8
 * = 1][battery % u8, 255 unknown][charging u8][signal u8 0-4, 255 unknown][gpsFix u8 0 none, 1 fix, 255 unknown][sats u8,
 * 255 unknown][battery temp i16 0.1 C, -32768 unknown][mode u8, 255 unknown][checkIntervalMin u8, 0 unknown][token i32:
 * the last FLEET_MODE taken, 0 none]}. An older PC stops reading before it; from an older phone it is absent.</li>
 * </ul>
 * Pure: both apps use it, the PC's tests the cases.
 */
public final class FleetProtocol {
    public static final byte MODE = 0x58;
    /** The first phone app that takes {@link #MODE} and sends a {@link Status}. */
    public static final int MIN_PC_BUILD = 40;
    public static final String MIN_PHONE_VERSION = "2.3.0-pc.40";
    static final int STATUS_VERSION = 1;
    public static final int UNKNOWN = -1;
    /** The temperature's "unknown" on the wire. */
    static final int TEMP_UNKNOWN = Short.MIN_VALUE;

    private FleetProtocol() {
    }

    /** A FLEET_MODE request as it goes. */
    public static final class Request {
        public final int token, mode, intervalMin, sensitivity, flags, owToken, pcPort;

        public Request(int token, int mode, int intervalMin, int sensitivity, int flags, int owToken, int pcPort) {
            this.token = token;
            this.mode = mode;
            this.intervalMin = intervalMin;
            this.sensitivity = sensitivity;
            this.flags = flags;
            this.owToken = owToken;
            this.pcPort = pcPort;
        }
    }

    public static byte[] request(String key, Request r) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(MODE);
            out.writeUTF(key);
            out.writeInt(r.token);
            out.writeByte(r.mode);
            out.writeByte(r.intervalMin);
            out.writeByte(r.sensitivity);
            out.writeByte(r.flags);
            out.writeInt(r.owToken);
            out.writeShort(r.pcPort);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The request in a FLEET_MODE with this key, or null (another key, too short, not one). */
    public static Request parseRequest(byte[] d, int len, String key) {
        if (len < 1 || d[0] != MODE) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(d, 1, len - 1))) {
            if (!in.readUTF().equals(key)) return null;
            int token = in.readInt();
            int mode = in.readUnsignedByte(), interval = in.readUnsignedByte(), sensitivity = in.readUnsignedByte(), flags = in.readUnsignedByte();
            int owToken = in.readInt();
            int pcPort = in.readUnsignedShort();
            if (token == 0 || mode < 1 || mode > 3 || pcPort == 0) return null;
            return new Request(token, mode, interval, sensitivity, flags, owToken, pcPort);
        } catch (IOException e) {
            return null;
        }
    }

    /** How a phone is doing, as it says at its check-in and in its name reply. UNKNOWN where it doesn't know. */
    public static final class Status {
        public final int batteryPercent;
        public final boolean charging;
        public final int signal, gpsFix, sats;
        /** Tenths of a degree C, or {@link #TEMP_UNKNOWN}. */
        public final int batteryTempDeci;
        public final int mode, intervalMin, token;

        public Status(int batteryPercent, boolean charging, int signal, int gpsFix, int sats, int batteryTempDeci, int mode,
                      int intervalMin, int token) {
            this.batteryPercent = batteryPercent;
            this.charging = charging;
            this.signal = signal;
            this.gpsFix = gpsFix;
            this.sats = sats;
            this.batteryTempDeci = batteryTempDeci;
            this.mode = mode;
            this.intervalMin = intervalMin;
            this.token = token;
        }

        public boolean tempKnown() {
            return batteryTempDeci != TEMP_UNKNOWN;
        }

        public void writeTo(DataOutputStream out) throws IOException {
            out.writeByte(STATUS_VERSION);
            out.writeByte(u8(batteryPercent));
            out.writeByte(charging ? 1 : 0);
            out.writeByte(u8(signal));
            out.writeByte(u8(gpsFix));
            out.writeByte(u8(sats));
            out.writeShort(batteryTempDeci == TEMP_UNKNOWN ? TEMP_UNKNOWN : Math.max(-32767, Math.min(32767, batteryTempDeci)));
            out.writeByte(u8(mode));
            out.writeByte(Math.max(0, Math.min(255, intervalMin)));
            out.writeInt(token);
        }

        public byte[] bytes() {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeTo(new DataOutputStream(b));
                return b.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The block where it starts, or null: none, or a version unknown here. */
        public static Status readFrom(DataInputStream in) {
            try {
                if (in.available() < 14 || in.readUnsignedByte() != STATUS_VERSION) return null;
                int battery = in.readUnsignedByte();
                boolean charging = in.readUnsignedByte() != 0;
                int signal = in.readUnsignedByte(), fix = in.readUnsignedByte(), sats = in.readUnsignedByte();
                int temp = in.readShort();
                int mode = in.readUnsignedByte(), interval = in.readUnsignedByte();
                int token = in.readInt();
                return new Status(s8(battery), charging, s8(signal), s8(fix), s8(sats), temp, s8(mode), interval, token);
            } catch (IOException e) {
                return null;
            }
        }

        public static Status read(byte[] d, int offset, int len) {
            if (offset >= len) return null;
            return readFrom(new DataInputStream(new ByteArrayInputStream(d, offset, len - offset)));
        }

        private static int u8(int v) {
            return v < 0 ? 255 : Math.min(254, v);
        }

        private static int s8(int v) {
            return v == 255 ? UNKNOWN : v;
        }
    }

    /** The pcBuild of a version like "2.3.0-pc.40" (-1 when it has none). */
    public static int pcBuild(String version) {
        if (version == null) return -1;
        int i = version.lastIndexOf("-pc.");
        if (i < 0) return -1;
        int end = i + 4;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        try {
            return Integer.parseInt(version.substring(i + 4, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
