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

package de.droiddrone.common;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The motion detection's telemetry record (phone app pc.35, DRAUPNE Link 1.0.46), written by the phone and read by the
 * PC - one class, so the two can't disagree:
 * <pre>
 * [0x40FA][flags u8][hfov u16, 0.01 deg, 0 = unknown][n u8, at most 8]
 *   n x [id u16][x u16][y u16][w u16][h u16, of the picture, 0..65535][angle i16, 0.01 deg][confidence u8 %]
 *       [vegetation u8 %][kind u8: 0 moving, 1 light]
 * </pre>
 * The box is in the camera's own picture (as the encoder gets it: before the PC mirrors or turns it); angle is the
 * box centre's angle right of the camera's axis by the phone's own lens figures (0 when hfov is unknown). Sent in its
 * own TelemetryData packet (a PC that doesn't know it stops reading there and loses nothing), only to a PC that asked
 * for detection, a few times a second while something is shown and once a second otherwise.
 */
public final class MotionRecord {
    public static final short CODE = 0x40FA;
    /** Pictures are being analysed now (disarmed, the camera running, the PC asked). */
    public static final int FLAG_ACTIVE = 1;
    /** The picture is dark: only lights that travel are looked for. */
    public static final int FLAG_NIGHT = 2;
    /** The picture changed as a whole, or the background is still learning: nothing judged. */
    public static final int FLAG_SETTLING = 4;
    /** This camera gives no picture to analyse (the phone refused the extra stream, or no frames come). */
    public static final int FLAG_NO_PICTURE = 8;
    /** Stopped: the flight controller is not known to be disarmed. */
    public static final int FLAG_ARMED = 16;
    public static final int KIND_MOVING = 0, KIND_LIGHT = 1;
    public static final int MAX_BOXES = MotionDetector.MAX_DETECTIONS;

    public static final class Box {
        public final int id;
        /** 0..1 of the picture, the camera's way round. */
        public final float x, y, w, h;
        /** Degrees right of the camera's axis (the phone's lens figures), 0 when unknown. */
        public final float angleDeg;
        public final float confidence, vegetation;
        public final int kind;

        public Box(int id, float x, float y, float w, float h, float angleDeg, float confidence, float vegetation, int kind) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.angleDeg = angleDeg;
            this.confidence = confidence;
            this.vegetation = vegetation;
            this.kind = kind;
        }

        public boolean light() {
            return kind == KIND_LIGHT;
        }
    }

    public static final class Report {
        public final int flags;
        /** The phone's own figure for the picture's angle across, degrees; 0 = unknown. */
        public final float hfovDeg;
        public final List<Box> boxes;

        public Report(int flags, float hfovDeg, List<Box> boxes) {
            this.flags = flags;
            this.hfovDeg = hfovDeg;
            this.boxes = boxes == null ? Collections.<Box>emptyList() : Collections.unmodifiableList(new ArrayList<>(boxes));
        }

        public boolean active() {
            return (flags & FLAG_ACTIVE) != 0;
        }

        public boolean night() {
            return (flags & FLAG_NIGHT) != 0;
        }
    }

    private MotionRecord() {
    }

    /**
     * The angle right of the camera's axis of a point at x (0..1 across the picture) for a picture hfovDeg wide: a
     * pinhole camera, so the tangent - not the angle - goes evenly across.
     */
    public static float angleDeg(float x, float hfovDeg) {
        if (hfovDeg <= 0) return 0;
        double t = (2 * x - 1) * Math.tan(Math.toRadians(hfovDeg / 2));
        return (float) Math.toDegrees(Math.atan(t));
    }

    public static Report of(int flags, float hfovDeg, MotionDetector.Result r) {
        List<Box> boxes = new ArrayList<>();
        if (r != null) {
            for (MotionDetector.Detection d : r.detections) {
                if (boxes.size() >= MAX_BOXES) break;
                boxes.add(new Box(d.id, d.x, d.y, d.w, d.h, angleDeg(d.centreX(), hfovDeg), d.confidence, d.vegetation,
                        d.light ? KIND_LIGHT : KIND_MOVING));
            }
            if (r.night) flags |= FLAG_NIGHT;
            if (r.settling) flags |= FLAG_SETTLING;
        }
        return new Report(flags, hfovDeg, boxes);
    }

    public static void write(DataOutputStream out, Report r) throws IOException {
        out.writeShort(CODE);
        out.writeByte(r.flags & 0xff);
        out.writeShort(Math.max(0, Math.min(65535, Math.round(r.hfovDeg * 100))));
        int n = Math.min(MAX_BOXES, r.boxes.size());
        out.writeByte(n);
        for (int i = 0; i < n; i++) {
            Box b = r.boxes.get(i);
            out.writeShort(b.id & 0xffff);
            out.writeShort(unit(b.x));
            out.writeShort(unit(b.y));
            out.writeShort(unit(b.w));
            out.writeShort(unit(b.h));
            out.writeShort(Math.max(-18000, Math.min(18000, Math.round(b.angleDeg * 100))));
            out.writeByte(percent(b.confidence));
            out.writeByte(percent(b.vegetation));
            out.writeByte(b.kind);
        }
    }

    /** The record's body (after its code); throws on a short one, as every record read does. */
    public static Report read(DataReader in) {
        int flags = in.readUnsignedByteAsInt();
        float hfov = in.readUnsignedShortAsInt() / 100f;
        int n = in.readUnsignedByteAsInt();
        if (n > MAX_BOXES) throw new IllegalArgumentException("motion record: " + n + " boxes");
        List<Box> boxes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = in.readUnsignedShortAsInt();
            float x = in.readUnsignedShortAsInt() / 65535f, y = in.readUnsignedShortAsInt() / 65535f;
            float w = in.readUnsignedShortAsInt() / 65535f, h = in.readUnsignedShortAsInt() / 65535f;
            float angle = in.readShort() / 100f;
            float confidence = in.readUnsignedByteAsInt() / 100f, vegetation = in.readUnsignedByteAsInt() / 100f;
            int kind = in.readUnsignedByteAsInt();
            boxes.add(new Box(id, x, y, w, h, angle, confidence, vegetation, kind));
        }
        return new Report(flags, hfov, boxes);
    }

    private static int unit(float v) {
        return Math.max(0, Math.min(65535, Math.round(v * 65535)));
    }

    private static int percent(float v) {
        return Math.max(0, Math.min(100, Math.round(v * 100)));
    }
}
