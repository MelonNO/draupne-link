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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DRAUPNE Link 1.0.48 / DRAUPNE Flight pc.37: OBSERVATION - a PC watches up to six idle drones at once (their pictures
 * and sound, where they are and where they look), and flies none of them until it presses CONNECT on one, which opens
 * an ordinary pilot connection. An observer never sends sticks and the phone takes none from it: observation is a
 * stream out of the phone and a few requests in, nothing else.
 * <p>
 * On the link port, each starting with the link key (another key: dropped without a word), unnumbered:
 * <ul>
 * <li>{@code OBSERVE [0x50][key UTF][width u16][height u16][fps u8][maxKbps u16][flags u8][detection u8]} PC to phone,
 * once a second while observing (and at once when it needs a config): the picture it wants (the grid's 640x360 at 15
 * fps, or 720p for the selected tile), flags bit 0 = the sound wanted, bit 1 = send the video config again and a key
 * frame, bit 2 = send the sound's config again; detection = the motion detection's sensitivity (0 off .. 3 high).
 * An observer silent for {@link #OBSERVER_EXPIRES_MS} is dropped.</li>
 * <li>{@code OBSERVE_STATE [0x51][state u8][width u16][height u16][fps u8][observers u8]} phone to PC, the answer to
 * every OBSERVE (and once when observation ends): STREAMING, or why not.</li>
 * <li>{@code OBSERVE_STOP [0x52][key UTF]} PC to phone: this observer leaves (CONNECT on a tile stops every stream at
 * once, the pilot's own included). Answered with STOPPED.</li>
 * </ul>
 * The stream itself is upstream's packets unchanged (VideoInitialFrame - its number left 0 -, VideoFrame, KeyFrame,
 * VIDEO_PARITY_GROUP, AudioInitialFrame, AudioFrame) and a TelemetryData packet with record {@link #RECORD} (and the
 * motion detection's 0x40FA when asked for), sent from the phone's link port to each observer's address and port,
 * only while no pilot is connected. 0x53-0x57 and record 0x40FF are reserved for observation and unused.
 * <p>
 * Compiled into both apps (the PC's tests run it). Java 11: the phone app's language level.
 */
public final class Observe {
    public static final byte OBSERVE = 0x50, STATE = 0x51, STOP = 0x52;
    /** The observer record: where the phone is, which way its camera looks. */
    public static final short RECORD = 0x40FE;

    public static final int FLAG_AUDIO = 1, FLAG_VIDEO_CONFIG = 2, FLAG_AUDIO_CONFIG = 4;

    /** OBSERVE_STATE's states. */
    public static final int STREAMING = 0, REFUSED_PILOT = 1, ENDED_PILOT = 2, REFUSED_FULL = 3, REFUSED_POWER = 4, STOPPED = 5,
            REFUSED_ARMED = 6;

    /** The most observers one phone streams to at once (its uplink carries each copy). */
    public static final int MAX_OBSERVERS = 3;
    /** An observer's OBSERVE holds this long (it sends one a second): a PC gone without a word stops costing uplink. */
    public static final long OBSERVER_EXPIRES_MS = 3000;
    /** The grid's picture: small, few frames, little uplink - six of them are light on the PC too. */
    public static final int GRID_WIDTH = 640, GRID_HEIGHT = 360, GRID_FPS = 15, GRID_KBPS = 1000;

    private Observe() {
    }

    public static String stateName(int state) {
        switch (state) {
            case STREAMING: return "streaming";
            case REFUSED_PILOT: return "a pilot flies it";
            case ENDED_PILOT: return "a pilot connected";
            case REFUSED_FULL: return "it streams to " + MAX_OBSERVERS + " observers already";
            case REFUSED_POWER: return "it is in standby";
            case STOPPED: return "stopped";
            case REFUSED_ARMED: return "its flight controller isn't known to be disarmed";
            default: return "state " + state;
        }
    }

    // ------------------------------------------------------------------ OBSERVE

    /** One OBSERVE, read. */
    public static final class Request {
        public final String key;
        public final int width, height, fps, maxKbps, flags, detection;

        public Request(String key, int width, int height, int fps, int maxKbps, int flags, int detection) {
            this.key = key;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.maxKbps = maxKbps;
            this.flags = flags;
            this.detection = detection;
        }

        public boolean audio() {
            return (flags & FLAG_AUDIO) != 0;
        }

        public boolean videoConfig() {
            return (flags & FLAG_VIDEO_CONFIG) != 0;
        }

        public boolean audioConfig() {
            return (flags & FLAG_AUDIO_CONFIG) != 0;
        }
    }

    public static byte[] encodeRequest(String key, int width, int height, int fps, int maxKbps, int flags, int detection) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(OBSERVE);
            out.writeUTF(key == null ? "" : key);
            out.writeShort(clamp(width, 16, 4096));
            out.writeShort(clamp(height, 16, 4096));
            out.writeByte(clamp(fps, 1, 60));
            out.writeShort(clamp(maxKbps, 100, 20_000));
            out.writeByte(flags & 0xff);
            out.writeByte(clamp(detection, 0, 3));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the request, or null: not an OBSERVE, or cut short */
    public static Request decodeRequest(byte[] d, int len) {
        if (d == null || len < 1 || d[0] != OBSERVE) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(d, 1, len - 1));
            String key = in.readUTF();
            int w = in.readUnsignedShort(), h = in.readUnsignedShort(), fps = in.readUnsignedByte(), kbps = in.readUnsignedShort();
            int flags = in.readUnsignedByte(), detection = in.readUnsignedByte();
            return new Request(key, clamp(w, 16, 4096), clamp(h, 16, 4096), clamp(fps, 1, 60), clamp(kbps, 100, 20_000), flags,
                    clamp(detection, 0, 3));
        } catch (IOException e) {
            return null;
        }
    }

    public static byte[] encodeStop(String key) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(STOP);
            out.writeUTF(key == null ? "" : key);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The key of an OBSERVE or OBSERVE_STOP, or null. */
    public static String keyOf(byte[] d, int len) {
        if (d == null || len < 3 || (d[0] != OBSERVE && d[0] != STOP)) return null;
        int n = ((d[1] & 0xff) << 8) | (d[2] & 0xff);
        if (3 + n > len) return null;
        try {
            return new DataInputStream(new ByteArrayInputStream(d, 1, len - 1)).readUTF();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ OBSERVE_STATE

    public static final class State {
        public final int state, width, height, fps, observers;

        public State(int state, int width, int height, int fps, int observers) {
            this.state = state;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.observers = observers;
        }
    }

    public static byte[] encodeState(int state, int width, int height, int fps, int observers) {
        return new byte[]{STATE, (byte) state, (byte) (width >> 8), (byte) width, (byte) (height >> 8), (byte) height,
                (byte) clamp(fps, 0, 255), (byte) clamp(observers, 0, 255)};
    }

    public static State decodeState(byte[] d, int len) {
        if (d == null || len < 8 || d[0] != STATE) return null;
        return new State(d[1] & 0xff, ((d[2] & 0xff) << 8) | (d[3] & 0xff), ((d[4] & 0xff) << 8) | (d[5] & 0xff), d[6] & 0xff, d[7] & 0xff);
    }

    /** The phone's encoder steps, bit/s (StreamEncoder's table; BitrateControl.STEPS_BPS on the PC). */
    static final int[] STEPS_BPS = {150_000, 250_000, 500_000, 1_000_000, 2_000_000, 4_000_000, 6_000_000, 8_000_000,
            10_000_000, 15_000_000, 20_000_000};

    /** The highest encoder step at or under maxKbps (the lowest step for less). */
    public static int stepFor(int maxKbps) {
        int step = 0;
        for (int i = 0; i < STEPS_BPS.length; i++) if (STEPS_BPS[i] <= maxKbps * 1000L) step = i;
        return step;
    }

    // ------------------------------------------------------------------ the phone's table of observers

    /**
     * Who observes this phone, by address (any key the caller likes: "ip:port"). Not thread-safe by itself: the phone
     * calls it under its own lock.
     */
    public static final class Observers<K> {
        private static final class Entry {
            Request request;
            long heardAt;
        }

        private final Map<K, Entry> table = new LinkedHashMap<>();
        private final int max;

        public Observers(int max) {
            this.max = max;
        }

        /**
         * An OBSERVE from this observer.
         * @return STREAMING, or REFUSED_FULL when the table is full and it isn't in it
         */
        public int onRequest(K who, Request r, long now) {
            expire(now);
            Entry e = table.get(who);
            if (e == null) {
                if (table.size() >= max) return REFUSED_FULL;
                e = new Entry();
                table.put(who, e);
            }
            e.request = r;
            e.heardAt = now;
            return STREAMING;
        }

        /** OBSERVE_STOP: gone. @return it was in the table */
        public boolean remove(K who) {
            return table.remove(who) != null;
        }

        /** The observers silent for OBSERVER_EXPIRES_MS go. @return the ones that went */
        public List<K> expire(long now) {
            List<K> gone = new ArrayList<>();
            for (Iterator<Map.Entry<K, Entry>> it = table.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<K, Entry> e = it.next();
                if (now - e.getValue().heardAt >= OBSERVER_EXPIRES_MS) {
                    gone.add(e.getKey());
                    it.remove();
                }
            }
            return gone;
        }

        /** Everyone, emptied (a pilot connected). */
        public List<K> clear() {
            List<K> all = new ArrayList<>(table.keySet());
            table.clear();
            return all;
        }

        public List<K> who() {
            return new ArrayList<>(table.keySet());
        }

        public int size() {
            return table.size();
        }

        public boolean isEmpty() {
            return table.isEmpty();
        }

        /**
         * The one picture the phone encodes for all of them: the biggest any of them asks for (one encoder, every
         * observer gets the same stream), its frame rate and bitrate the highest asked; null with nobody.
         */
        public Request wanted() {
            Request best = null;
            int fps = 0, kbps = 0;
            boolean audio = false;
            int detection = 0;
            for (Entry e : table.values()) {
                Request r = e.request;
                if (best == null || (long) r.width * r.height > (long) best.width * best.height) best = r;
                fps = Math.max(fps, r.fps);
                kbps = Math.max(kbps, r.maxKbps);
                audio |= r.audio();
                detection = Math.max(detection, r.detection);
            }
            if (best == null) return null;
            return new Request(best.key, best.width, best.height, fps, kbps, audio ? FLAG_AUDIO : 0, detection);
        }
    }

    // ------------------------------------------------------------------ the record 0x40FE

    public static final int POS_VALID = 1, POS_FROM_FC = 2, FC_COMPASS = 4, ARMED = 8, ARMED_UNKNOWN = 16, FRONT_CAMERA = 32;
    /** No heading, no accuracy. */
    public static final int NONE_U16 = 65535, NONE_U8 = 255;

    /** One observer record, read (or to write). */
    public static final class Position {
        public final int flags;
        public final double lat, lon;
        public final int altM, ageS;
        /** The flight controller's heading (MSP attitude yaw), degrees, or NaN when it has none. */
        public final double fcHeadingDeg;
        /** The phone's own: where its camera looks by the rotation vector sensor, degrees, or NaN. */
        public final double phoneAzimuthDeg;
        /** Android's sensor accuracy 0 (unreliable) .. 3 (high), -1 unknown. */
        public final int phoneAccuracy;
        /** The sensor's own estimate of its heading error, degrees, -1 unknown. */
        public final int phoneErrorDeg;
        /** The camera's angle across the picture, degrees, 0 = unknown. */
        public final double hfovDeg;

        public Position(int flags, double lat, double lon, int altM, int ageS, double fcHeadingDeg, double phoneAzimuthDeg,
                        int phoneAccuracy, int phoneErrorDeg, double hfovDeg) {
            this.flags = flags;
            this.lat = lat;
            this.lon = lon;
            this.altM = altM;
            this.ageS = ageS;
            this.fcHeadingDeg = fcHeadingDeg;
            this.phoneAzimuthDeg = phoneAzimuthDeg;
            this.phoneAccuracy = phoneAccuracy;
            this.phoneErrorDeg = phoneErrorDeg;
            this.hfovDeg = hfovDeg;
        }

        public boolean hasPosition() {
            return (flags & POS_VALID) != 0;
        }

        public boolean fcCompass() {
            return (flags & FC_COMPASS) != 0;
        }
    }

    /** [code i16][flags u8][lat i32][lon i32][alt i16][age s u16][fc heading u16 0.1 deg][phone azimuth u16 0.1 deg][accuracy u8][error u8 deg][hfov u16 0.01 deg] */
    public static void writeRecord(DataOutputStream out, Position p) throws IOException {
        out.writeShort(RECORD);
        out.writeByte(p.flags);
        out.writeInt((int) Math.round(p.lat * 1e7));
        out.writeInt((int) Math.round(p.lon * 1e7));
        out.writeShort(clamp(p.altM, -32768, 32767));
        out.writeShort(clamp(p.ageS, 0, 65535));
        out.writeShort(Double.isNaN(p.fcHeadingDeg) ? NONE_U16 : (int) Math.round(norm(p.fcHeadingDeg) * 10) % 3600);
        out.writeShort(Double.isNaN(p.phoneAzimuthDeg) ? NONE_U16 : (int) Math.round(norm(p.phoneAzimuthDeg) * 10) % 3600);
        out.writeByte(p.phoneAccuracy < 0 ? NONE_U8 : clamp(p.phoneAccuracy, 0, 3));
        out.writeByte(p.phoneErrorDeg < 0 ? NONE_U8 : clamp(p.phoneErrorDeg, 0, 254));
        out.writeShort(clamp((int) Math.round(p.hfovDeg * 100), 0, 65535));
    }

    /** Reads the record after its code. */
    public static Position readRecord(DataReader in) {
        int flags = in.readUnsignedByteAsInt();
        double lat = in.readInt() / 1e7, lon = in.readInt() / 1e7;
        int alt = in.readShort();
        int age = in.readShort() & 0xffff;
        int fc = in.readShort() & 0xffff, az = in.readShort() & 0xffff;
        int acc = in.readUnsignedByteAsInt(), err = in.readUnsignedByteAsInt();
        int hfov = in.readShort() & 0xffff;
        return new Position(flags, lat, lon, alt, age, fc == NONE_U16 ? Double.NaN : fc / 10.0, az == NONE_U16 ? Double.NaN : az / 10.0,
                acc == NONE_U8 ? -1 : acc, err == NONE_U8 ? -1 : err, hfov / 100.0);
    }

    /** The record's bytes after its code: 21. */
    public static final int RECORD_BYTES = 21;

    // ------------------------------------------------------------------ the phone's camera direction

    /**
     * Where the phone's camera looks, degrees from north, from Android's rotation matrix (getRotationMatrixFromVector,
     * row-major 3 x 3: device to east / north / up): the back camera looks along the device's -Z, the front one along
     * +Z. A phone lying flat (the camera at the ground or the sky) has no such direction: the top edge's (+Y) then.
     * NaN when neither has one (a matrix of nothing).
     */
    public static double cameraAzimuthDeg(float[] r, boolean front) {
        if (r == null || r.length < 9) return Double.NaN;
        double s = front ? 1 : -1;
        double east = s * r[2], north = s * r[5];
        if (Math.hypot(east, north) < 0.35) { // within ~20 degrees of straight down or up
            east = r[1];
            north = r[4];
        }
        if (Math.hypot(east, north) < 1e-6) return Double.NaN;
        return norm(Math.toDegrees(Math.atan2(east, north)));
    }

    static double norm(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
