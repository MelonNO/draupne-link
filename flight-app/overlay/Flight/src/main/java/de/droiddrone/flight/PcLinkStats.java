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

    static void writeVersionTo(DataOutputStream out, String versionName) throws IOException {
        byte[] name = versionName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeShort(APP_VERSION_CODE);
        out.writeShort(name.length);
        out.write(name);
    }

    private static long lastRcIn;
    private static long lastRcOut;
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
    }

    /** A stick write reached the flight controller. */
    static synchronized void rcOut() {
        long now = System.currentTimeMillis();
        if (lastRcOut != 0) rcOutMaxGap = Math.max(rcOutMaxGap, now - lastRcOut);
        lastRcOut = now;
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
