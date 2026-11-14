package de.droiddrone.pc.gui;

import de.droiddrone.pc.CameraView;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.LiveHeading;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Pins;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * MARK: a click on the video as a pin on the map - the decision. The click is a point of the picture, the
 * point a ray from the camera ({@link CameraView#ray}), and where the ray meets the ground the pin goes. No pin
 * where that says nothing (no position, no heading, sky); a pin on weak geometry is a ROUGH one - a place to look
 * around, not a point. Pure: no state, nothing kept.
 */
final class PinDrop {
    /** The camera's reach (the ray ends there): no pin past it. */
    static final double MAX_RANGE_M = CameraFootprint.MAX_RANGE_M;
    /** Rough from here on: a degree of heading error is 17 m across at 1 km. */
    static final double ROUGH_RANGE_M = 1000;
    /**
     * Rough below this angle between the ray and the ground (taken as level there): at 5 degrees a degree of pitch
     * error moves the point by a fifth of its range.
     */
    static final double ROUGH_GRAZING_DEG = 5;
    /** The drone's GPS (FcTelemetry.Gps): rough below a 3D fix (Betaflight's fix 2) or with fewer satellites than this. */
    static final int FIX_3D = 2, MIN_SATS = 6;
    /** A position from the phone's GPS has no fix and no satellites: rough when its accuracy is unknown or worse than this. */
    static final int PHONE_ACCURACY_M = 15;
    /** The track's last point older than this: the drone may have moved since - rough. */
    static final long FRESH_POSITION_MS = 3000;
    /**
     * Armed, the track's last point may be no older than this: a drone in the air is no longer where it was, and a pin
     * from there would be anywhere. Disarmed it sits, and its place holds as long as CameraView takes it for a ray.
     */
    static final long ARMED_POSITION_MS = 10_000;

    static final String NO_POSITION = "NO PIN - NO POSITION", NO_HEADING = "NO PIN - NO HEADING",
            NO_ATTITUDE = "NO PIN - NO ATTITUDE", NO_GROUND = "NO PIN - SKY OR OVER 3 KM";

    /** Why a pin is rough, as the log says it. */
    enum Rough {
        FLAT_GROUND("terrain unknown, flat ground assumed"), GPS("weak GPS"), FAR("over 1 km"),
        GRAZING("flat angle to the ground"), OLD_POSITION("old position");

        final String text;

        Rough(String text) {
            this.text = text;
        }
    }

    /**
     * What a click was cast from. position: the track's last point, no older than CameraView takes for a ray - and
     * no older than ARMED_POSITION_MS while the drone is armed; heading: {@link LiveHeading} knows one; pose and ray: CameraView's, null without (a pose needs the flight
     * controller's attitude too). The position's quality: phoneGps - it is the phone's, with accuracyM (0 unknown);
     * else the drone's, with the fix and satellites of FcTelemetry.Gps now.
     */
    record Sight(boolean position, boolean heading, CameraFootprint.Pose pose, CameraFootprint.Ray ray, boolean phoneGps,
                 int fix, int sats, int accuracyM, long positionAgeMs) {
    }

    /** refusal: the line for the video and no pin; else the pin's place, and why it is rough (empty: it is not). */
    record Verdict(String refusal, double lat, double lon, Set<Rough> why) {
        boolean rough() {
            return !why.isEmpty();
        }
    }

    private PinDrop() {
    }

    /**
     * A point of the view as a point of the picture, (u, v) -1..1 left to right and top to bottom as the screen shows
     * it - what {@link CameraView#ray} takes: the view turns a camera mounted upside down and mirrors a front camera,
     * so what it shows is the drone's own left and right, and nothing is turned back here. Null beside the picture
     * (the black bars) and before a picture has a place.
     */
    static double[] pictureUv(Point p, Rectangle picture) {
        if (p == null || picture == null || picture.width <= 0 || picture.height <= 0 || !picture.contains(p)) return null;
        // a pixel's middle: the picture's left column is not quite -1, its right one not quite 1
        return new double[]{2 * (p.x - picture.x + 0.5) / picture.width - 1, 2 * (p.y - picture.y + 0.5) / picture.height - 1};
    }

    /** The sight through picture point (u, v) now: the live telemetry and track of the drone on the video. */
    static Sight look(CameraView view, FcTelemetry t, FlightTrack track, double u, double v) {
        FlightTrack.Point last = t == null || track == null ? null : track.last();
        long age = last == null ? Long.MAX_VALUE : Math.max(0, System.currentTimeMillis() - last.timeMs());
        boolean armed = t != null && t.isArmedNow(MonoClock.ms(), 2000);
        boolean position = last != null && age <= (armed ? ARMED_POSITION_MS : CameraView.SITTING_STALE_MS);
        boolean heading = position && !Double.isNaN(LiveHeading.of(t));
        CameraFootprint.Pose pose = heading ? view.sittingPose(t, track) : null;
        CameraFootprint.Ray ray = pose == null ? null : view.ray(t, track, u, v);
        FcTelemetry.Gps gps = t == null ? null : t.getGps();
        boolean phone = last != null && last.phone();
        return new Sight(position, heading, pose, ray, phone, gps == null ? 0 : gps.fix(), gps == null ? 0 : gps.sats(),
                last == null ? 0 : last.accuracyM(), age);
    }

    /** Pin or no pin, and how good. */
    static Verdict decide(Sight s) {
        if (s == null || !s.position()) return refuse(NO_POSITION);
        if (!s.heading()) return refuse(NO_HEADING);
        if (s.pose() == null || s.ray() == null) return refuse(NO_ATTITUDE);
        CameraFootprint.Ray ray = s.ray();
        double[] ground = ray.ground();
        if (ground == null || Double.isNaN(ray.slantRangeM()) || ray.slantRangeM() > MAX_RANGE_M) return refuse(NO_GROUND);
        Set<Rough> why = EnumSet.noneOf(Rough.class);
        if (ray.flatGround()) why.add(Rough.FLAT_GROUND);
        if (s.phoneGps() ? s.accuracyM() <= 0 || s.accuracyM() > PHONE_ACCURACY_M : s.fix() < FIX_3D || s.sats() < MIN_SATS) why.add(Rough.GPS);
        if (ray.slantRangeM() > ROUGH_RANGE_M) why.add(Rough.FAR);
        if (grazingDeg(s.pose(), ray) < ROUGH_GRAZING_DEG) why.add(Rough.GRAZING);
        if (s.positionAgeMs() > FRESH_POSITION_MS) why.add(Rough.OLD_POSITION);
        return new Verdict(null, ground[0], ground[1], why);
    }

    private static Verdict refuse(String line) {
        return new Verdict(line, Double.NaN, Double.NaN, EnumSet.noneOf(Rough.class));
    }

    /**
     * The angle between the ray and the ground where they meet, the ground taken as level there: from the camera's
     * height over that ground and the way to it. (A slope facing the camera is met steeper than this says, one
     * falling away flatter; the terrain's slope is not asked.)
     */
    static double grazingDeg(CameraFootprint.Pose pose, CameraFootprint.Ray ray) {
        double[] ground = ray.ground();
        if (ground == null || !(ray.slantRangeM() > 0)) return 0;
        double sin = Math.abs(pose.altM() - ground[2]) / ray.slantRangeM();
        return Math.toDegrees(Math.asin(Math.min(1, sin)));
    }

    /** The video's line for a pin: "PIN ALPHA 32V NM 97254 42617", a rough one "PIN ~ALPHA ...". */
    static String line(Pins.Pin pin, Coordinates.Format format) {
        return "PIN " + (pin.rough() ? "~" : "") + pin.name().toUpperCase(Locale.ROOT) + " "
                + Coordinates.format(format == null ? Coordinates.Format.MGRS : format, pin.lat(), pin.lon());
    }

    /** The log's line for it: the name as kept, the place, and why it is rough. */
    static String logLine(Pins.Pin pin, Coordinates.Format format, Set<Rough> why) {
        StringBuilder sb = new StringBuilder("Pin ").append(pin.name()).append(" from the video: ")
                .append(Coordinates.format(format == null ? Coordinates.Format.MGRS : format, pin.lat(), pin.lon()));
        if (!why.isEmpty()) {
            sb.append(" - ROUGH (");
            int n = 0;
            for (Rough r : why) sb.append(n++ == 0 ? "" : "; ").append(r.text);
            sb.append(')');
        }
        return sb.append('.').toString();
    }
}
