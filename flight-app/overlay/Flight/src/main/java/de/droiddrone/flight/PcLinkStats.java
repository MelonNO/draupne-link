/*
 *  This file is part of DRAUPNE Link's build of DRAUPNE Flight.
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

package de.droiddrone.flight;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * What the phone sees of the sticks on their way through it, for DRAUPNE Link's link log: the longest gap between
 * stick packets arriving from the PC, the longest gap between stick writes reaching the flight controller, the
 * slowest USB write, and how many writes timed out or requests were skipped. Sent every 0.1 s as its own telemetry
 * packet (record {@link #CODE}); a PC that doesn't know the record just skips that packet.
 */
final class PcLinkStats {
    /** Telemetry record code, outside upstream's ranges. */
    static final short CODE = 0x40F0;
    /** This app's version (versionName), once a second after the stats, so DRAUPNE Link can say when it is old. */
    static final short APP_VERSION_CODE = 0x40F1;

    /** The encoder's bitrate setting (bit/s) and whether it uses intra refresh: the PC decides the bitrate from it. */
    static final short ENCODER_CODE = 0x40F2;

    /**
     * The phone's own GPS position, once a second when the pilot allowed location: lat and lon (1e-7 degrees),
     * altitude (m), accuracy (m), speed (km/h x 10), course (degrees), age of the fix (s). The PC's map shows it
     * instead of, or when there is no longer, the drone's.
     */
    static final short PHONE_POSITION_CODE = 0x40F3;

    /**
     * The phone's state (pc.19), once a second: u8 flags ({@link #STATE_RX_MAP_KNOWN}, {@link #STATE_USB_PERMISSION_PENDING},
     * {@link #STATE_USB_PERMISSION_DENIED}, {@link #STATE_CAMERA_OPEN}, {@link #STATE_VIDEO_RESTARTING}), u8 the last stick frame's sequence (255 =
     * none), u16 ms since the last stick frame was written to the flight controller (65535 = unknown), u16 ms from
     * the last frame's arrival over UDP to its USB write (65535 = unknown). The PC shows why no sticks reach the
     * drone (no channel map yet, USB permission) without a look at the phone.
     */
    static final short PHONE_STATE_CODE = 0x40F4;
    static final int STATE_RX_MAP_KNOWN = 1;
    static final int STATE_USB_PERMISSION_PENDING = 2;
    static final int STATE_USB_PERMISSION_DENIED = 4;
    static final int STATE_CAMERA_OPEN = 8;
    /** pc.26: the video watchdog is restarting the camera and encoder (no encoder output for 2 s). */
    static final int STATE_VIDEO_RESTARTING = 16;

    /**
     * The phone's power mode (pc.21, {@link PowerMode}), once a second and three times 300 ms apart when it changes:
     * u8 the mode in force (0 normal, 1 standby, 2 deep sleep), u8 the deep sleep check interval in minutes, u16
     * seconds until the phone goes to deep sleep (0 when it isn't about to), u8 what the phone may do
     * ({@link #CAP_DEVICE_ADMIN}: it can lock its own screen, {@link #CAP_EXACT_ALARMS}: its sleep checks come on
     * time, {@link #CAP_NO_BATTERY_OPTIMISATION}: Android lets it use the network during a check). Last in its
     * packet: a PC that doesn't know it stops reading there.
     */
    static final short PHONE_POWER_CODE = 0x40F5;
    static final int CAP_DEVICE_ADMIN = 1;
    static final int CAP_EXACT_ALARMS = 2;
    static final int CAP_NO_BATTERY_OPTIMISATION = 4;

    static void writePhonePowerTo(DataOutputStream out, int mode, int checkIntervalMin, int sleepInS, int caps) throws IOException {
        out.writeShort(PHONE_POWER_CODE);
        out.writeByte(mode);
        out.writeByte(Math.max(0, Math.min(255, checkIntervalMin)));
        out.writeShort(Math.max(0, Math.min(65535, sleepInS)));
        out.writeByte(caps);
    }

    /**
     * pc.31, once a second to a PC whose stick frames carry their send time: u16 how late the last stick frame came
     * against the best the link did lately (ms, 65535 unknown), u16 the most over the last second (65535 unknown),
     * u16 frames dropped as too late this session. Last in its packet.
     */
    static final short PHONE_RC_LAG_CODE = 0x40F6;

    static void writeRcLagTo(DataOutputStream out, int lagMs, int maxLagMs, int dropped) throws IOException {
        out.writeShort(PHONE_RC_LAG_CODE);
        out.writeShort(lagMs < 0 ? 65535 : Math.min(65534, lagMs));
        out.writeShort(maxLagMs < 0 ? 65535 : Math.min(65534, maxLagMs));
        out.writeShort(Math.max(0, Math.min(65535, dropped)));
    }

    static void writePhonePositionTo(DataOutputStream out, android.location.Location l) throws IOException {
        out.writeShort(PHONE_POSITION_CODE);
        out.writeInt((int) Math.round(l.getLatitude() * 1e7));
        out.writeInt((int) Math.round(l.getLongitude() * 1e7));
        out.writeShort((int) Math.max(-32768, Math.min(32767, Math.round(l.hasAltitude() ? l.getAltitude() : 0))));
        out.writeShort((int) Math.min(65535, l.hasAccuracy() ? Math.round(l.getAccuracy()) : 65535));
        out.writeShort((int) Math.min(65535, l.hasSpeed() ? Math.round(l.getSpeed() * 36) : 0));
        out.writeShort(l.hasBearing() ? Math.round(l.getBearing()) % 360 : 0);
        out.writeShort((int) Math.min(65535, Math.max(0, PhoneLocation.ageMs(l) / 1000)));
    }

    static void writeEncoderTo(DataOutputStream out, int targetBitRate, boolean intraRefresh) throws IOException {
        out.writeShort(ENCODER_CODE);
        out.writeInt(targetBitRate);
        out.writeByte(intraRefresh ? 1 : 0);
    }

    static void writeVersionTo(DataOutputStream out, String versionName) throws IOException {
        byte[] name = versionName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeShort(APP_VERSION_CODE);
        out.writeShort(name.length);
        out.write(name);
    }

    /** The PHONE_STATE record; lastRcSeq is -1 for none. */
    static synchronized void writePhoneStateTo(DataOutputStream out, int flags, int lastRcSeq) throws IOException {
        long now = System.currentTimeMillis();
        out.writeShort(PHONE_STATE_CODE);
        out.writeByte(flags);
        out.writeByte(lastRcSeq < 0 ? 255 : lastRcSeq & 0xff); // the low byte of a 16-bit sequence (pc.26)
        out.writeShort(lastRcOut == 0 ? 65535 : (int) Math.min(65535, now - lastRcOut));
        out.writeShort(rcInToOutMs < 0 ? 65535 : (int) Math.min(65535, rcInToOutMs));
    }

    private static long lastRcIn;
    private static long lastRcInNs;
    private static long lastRcOut;
    /** Phone receive to USB write of the last stick frame written; -1 = none yet. */
    private static long rcInToOutMs = -1;
    private static long rcInMaxGap;
    private static long rcOutMaxGap;
    private static long writeMaxMs;
    private static int writeTimeouts;
    private static int skipped;

    private PcLinkStats() {
    }

    /** A stick packet arrived from the PC. */
    static synchronized void rcIn() {
        long now = System.currentTimeMillis();
        if (lastRcIn != 0) rcInMaxGap = Math.max(rcInMaxGap, now - lastRcIn);
        lastRcIn = now;
        lastRcInNs = System.nanoTime();
    }

    /**
     * A stick write reached the flight controller. The newest frame is always the one written, so the time since
     * the last arrival is (near enough) that frame's way through the phone.
     */
    static synchronized void rcOut() {
        long now = System.currentTimeMillis();
        if (lastRcOut != 0) rcOutMaxGap = Math.max(rcOutMaxGap, now - lastRcOut);
        lastRcOut = now;
        if (lastRcInNs != 0) rcInToOutMs = (System.nanoTime() - lastRcInNs) / 1_000_000L;
    }

    /** The flight controller's port closed: the next state record says the write age is unknown, not growing. */
    static synchronized void rcPortClosed() {
        lastRcOut = 0;
        rcInToOutMs = -1;
    }

    /** One USB write to the flight controller and how long it took. */
    static synchronized void write(long ms, boolean timedOut) {
        writeMaxMs = Math.max(writeMaxMs, ms);
        if (timedOut) writeTimeouts++;
    }

    /** A non-stick request skipped (queue full, or the USB link slow). */
    static synchronized void skipped() {
        skipped++;
    }

    /** The record body since the last call, then starts over: five shorts. */
    static synchronized void writeTo(DataOutputStream out) throws IOException {
        long now = System.currentTimeMillis();
        // a gap still open counts too: no stick packet for 0.3 s is a 0.3 s gap even before the next one comes
        long inGap = Math.max(rcInMaxGap, lastRcIn == 0 ? 0 : now - lastRcIn);
        long outGap = Math.max(rcOutMaxGap, lastRcOut == 0 ? 0 : now - lastRcOut);
        out.writeShort(clamp(inGap));
        out.writeShort(clamp(outGap));
        out.writeShort(clamp(writeMaxMs));
        out.writeShort(clamp(writeTimeouts));
        out.writeShort(clamp(skipped));
        rcInMaxGap = 0;
        rcOutMaxGap = 0;
        writeMaxMs = 0;
        writeTimeouts = 0;
        skipped = 0;
    }

    private static int clamp(long v) {
        return (int) Math.min(Short.MAX_VALUE, Math.max(0, v));
    }
}
