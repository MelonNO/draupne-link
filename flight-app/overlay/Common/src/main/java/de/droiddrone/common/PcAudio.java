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
 * DRAUPNE Link 1.0.45 / DRAUPNE Flight pc.34: the phone's microphone to the PC, while the drone is disarmed. The PC
 * asks with AUDIO {@code [0x79][on u8][kbps u8][flags u8]} (unnumbered, again every second while connected; an older
 * phone app drops it); flags bit 0 = send the stream's config (upstream's AudioInitialFrame) again. The phone streams
 * upstream's AudioInitialFrame / AudioFrame (AAC-LC) while the PC's last "on" is under {@link #WANT_EXPIRES_MS} old,
 * the flight controller is known DISARMED (unknown counts as armed) and the phone is in normal power mode - so never
 * in the air: the uplink in flight carries the sticks' answers and the picture alone.
 * <p>
 * DRAUPNE Link 1.0.50 / DRAUPNE Flight pc.39: flags bit 1 ({@link #FLAG_IN_FLIGHT}) = the pilot unmuted while armed and
 * wants the sound in the air too: then the phone streams armed as well (still only while wanted, in normal power). The
 * PC mutes by itself at every arming, so the sound in the air is always the pilot's own choice. An older phone ignores
 * the bit and stays silent armed.
 * <p>
 * Compiled into both apps (the PC's tests check the gate). Java 11: the phone app's language level.
 */
public final class PcAudio {
    public static final byte PACKET = 0x79;
    /** Flags bit 0: the PC has frames but no config (it missed the AudioInitialFrame): the phone sends it again. */
    public static final int FLAG_RESEND_CONFIG = 1;
    /** Flags bit 1 (pc.39): the pilot wants the sound while armed too (unmuted in the air). */
    public static final int FLAG_IN_FLIGHT = 2;
    /** AAC-LC, mono: speech and engine noise come through, about 60 kbit/s on the link with the packet headers. */
    public static final int DEFAULT_KBPS = 48;
    public static final int MIN_KBPS = 16, MAX_KBPS = 64;
    /**
     * The PC's "on" holds this long: a PC gone without a word (closed, its link lost) doesn't keep the microphone
     * running. It sends it every second.
     */
    public static final long WANT_EXPIRES_MS = 3000;

    /** One AUDIO packet, read. */
    public static final class Request {
        public final boolean on;
        public final int kbps;
        public final boolean resendConfig;
        public final boolean inFlight;

        public Request(boolean on, int kbps, boolean resendConfig) {
            this(on, kbps, resendConfig, false);
        }

        public Request(boolean on, int kbps, boolean resendConfig, boolean inFlight) {
            this.on = on;
            this.kbps = kbps;
            this.resendConfig = resendConfig;
            this.inFlight = inFlight;
        }
    }

    public static byte[] encode(boolean on, int kbps, boolean resendConfig) {
        return encode(on, kbps, resendConfig, false);
    }

    public static byte[] encode(boolean on, int kbps, boolean resendConfig, boolean inFlight) {
        int flags = (resendConfig ? FLAG_RESEND_CONFIG : 0) | (inFlight ? FLAG_IN_FLIGHT : 0);
        return new byte[]{PACKET, (byte) (on ? 1 : 0), (byte) clampKbps(kbps), (byte) flags};
    }

    /** @return the request, or null: not an AUDIO packet, or too short. A missing flags byte reads as none. */
    public static Request decode(byte[] d, int len) {
        if (d == null || len < 3 || d[0] != PACKET) return null;
        int flags = len >= 4 ? d[3] & 0xff : 0;
        return new Request(d[1] != 0, clampKbps(d[2] & 0xff), (flags & FLAG_RESEND_CONFIG) != 0, (flags & FLAG_IN_FLIGHT) != 0);
    }

    static int clampKbps(int kbps) {
        return Math.max(MIN_KBPS, Math.min(MAX_KBPS, kbps));
    }

    // ------------------------------------------------------------------ the phone's gate

    private volatile long wantedUntil;
    /** Until when the pilot's "in the air too" holds (its own request's lifetime, as "on"). */
    private volatile long inFlightUntil;
    private volatile int kbps = DEFAULT_KBPS;

    /** A request from the pilot PC. */
    public void onRequest(Request r, long nowMs) {
        if (r == null) return;
        if (r.on) {
            kbps = r.kbps;
            wantedUntil = nowMs + WANT_EXPIRES_MS;
            inFlightUntil = r.inFlight ? nowMs + WANT_EXPIRES_MS : 0;
        } else {
            wantedUntil = 0;
            inFlightUntil = 0;
        }
    }

    /** The session ended (disconnect, a new pilot): nothing wanted until the next request. */
    public void forget() {
        wantedUntil = 0;
        inFlightUntil = 0;
    }

    /** The PC wants the sound now. */
    public boolean wanted(long nowMs) {
        return nowMs < wantedUntil;
    }

    /** The bitrate the PC last asked for, bit/s. */
    public int bitRate() {
        return kbps * 1000;
    }

    /**
     * The microphone may stream now: the PC wants it, the flight controller reads DISARMED (a fresh status, not
     * unknown) - or the pilot asked for the sound in the air too (pc.39) - and the phone isn't in standby or deep sleep.
     */
    public boolean allowed(long nowMs, boolean fcDisarmed, boolean normalPower) {
        return normalPower && wanted(nowMs) && (fcDisarmed || nowMs < inFlightUntil);
    }
}
