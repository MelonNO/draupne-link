package de.droiddrone.pc;

import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.video.Klv;

/**
 * Where the drone's camera looks (1.0.39): the latest position, attitude and altitude with the camera's settings, as
 * a {@link CameraFootprint.View} - drawn on the map, and sent as the STANAG 4609 stream's frame centre and corners.
 * The camera's angle across the picture and its tilt on the frame are settings (camera.hfovDeg, camera.tiltDeg):
 * the phone does not say them. The height: the ground at launch from the terrain plus the flight controller's
 * barometer height over launch (steadier than the GPS's), else the GPS's height above the sea.
 */
public final class CameraView {
    public static final String HFOV_KEY = "camera.hfovDeg", TILT_KEY = "camera.tiltDeg", SHOW_KEY = "camera.viewOnMap";
    /** A phone's main camera across a 16:9 picture: about 70 degrees. */
    public static final double DEFAULT_HFOV = 70, DEFAULT_TILT = 0;
    /** Older position or attitude than this: no view (it would point the wrong way). */
    static final long STALE_MS = 3000;

    private final CameraFootprint.Terrain terrain;
    private volatile double hfovDeg = DEFAULT_HFOV, tiltDeg = DEFAULT_TILT;
    private volatile boolean invertPitch;
    private volatile double aspect = 16.0 / 9;

    public CameraView(TileCache cache) {
        terrain = cache == null ? null : new CameraFootprint.Terrain(cache);
    }

    /** From the settings file; values out of range fall back to the defaults. */
    public void configure(PcConfig config) {
        hfovDeg = number(config.getString(HFOV_KEY), DEFAULT_HFOV, 5, 170);
        tiltDeg = number(config.getString(TILT_KEY), DEFAULT_TILT, -90, 90);
        invertPitch = "true".equalsIgnoreCase(config.getString("osdInvertPitch"));
    }

    public static double number(String s, double fallback, double min, double max) {
        try {
            double v = Double.parseDouble(s.trim().replace(',', '.'));
            return v >= min && v <= max ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public double hfovDeg() {
        return hfovDeg;
    }

    public double tiltDeg() {
        return tiltDeg;
    }

    /** The picture's angle up and down, from the angle across and the picture's shape. */
    public double vfovDeg() {
        return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(hfovDeg / 2)) / aspect));
    }

    /** The video's size, for the angle up and down. */
    public void setPictureSize(int width, int height) {
        if (width > 0 && height > 0) aspect = (double) width / height;
    }

    /** The camera's pose now, or null when the position, attitude or height is missing or stale. */
    public CameraFootprint.Pose pose(FcTelemetry t, FlightTrack track) {
        return pose(t, track, STALE_MS);
    }

    /**
     * The same with the position allowed to be positionStaleMs old: a drone sitting disarmed (the motion detection)
     * doesn't move, and its track may add no point for a while. The attitude must still be fresh (the heading).
     */
    public CameraFootprint.Pose pose(FcTelemetry t, FlightTrack track, long positionStaleMs) {
        if (t == null || track == null) return null;
        FlightTrack.Point last = track.last();
        FcTelemetry.Attitude att = t.getAttitude();
        long now = System.currentTimeMillis();
        if (last == null || att == null || now - last.timeMs() > positionStaleMs || MonoClock.ms() - att.receivedAt() > STALE_MS) return null;
        double heading = LiveHeading.of(t); // 1.0.62: without a compass the FC's yaw is drift - the phone's, or none
        if (Double.isNaN(heading)) return null;
        double ground = groundAtLaunch(track);
        FcTelemetry.Altitude baro = t.getAltitude();
        double alt;
        if (!Double.isNaN(ground) && baro != null) alt = ground + baro.meters();
        else alt = last.altM(); // the GPS's height above the sea
        double pitch = invertPitch ? att.pitchDeg() : -att.pitchDeg(); // Betaflight: nose down positive
        return new CameraFootprint.Pose(last.lat(), last.lon(), alt, heading, pitch, att.rollDeg(),
                tiltDeg, hfovDeg, vfovDeg());
    }

    /** The view now (see {@link #pose}), the terrain fetched round the drone as it flies; null when there is none. */
    public CameraFootprint.View compute(FcTelemetry t, FlightTrack track) {
        CameraFootprint.Pose p = pose(t, track);
        if (p == null) return null;
        if (terrain != null) terrain.around(p.lat(), p.lon());
        return CameraFootprint.compute(p, terrain, groundFallback(t, track, p));
    }

    /**
     * The view for the map, worked out PERIOD_MS apart on a thread of its own at the lowest priority (1.0.49): its rays
     * march some 12,000 steps over the terrain, and that ran on the Swing thread every 33 ms - the thread that also
     * shows the video. The maps take the latest from here when they refresh.
     */
    public static final class Live {
        public static final long PERIOD_MS = 200;
        private final CameraView view;
        private volatile FcTelemetry telemetry;
        private volatile FlightTrack track;
        private volatile CameraFootprint.View latest;
        private java.util.concurrent.ScheduledExecutorService worker; // made at the first want (Swing thread)

        public Live(CameraView view) {
            this.view = view;
        }

        /** What to work it out from from now on; null telemetry: no view (it goes at once). Swing thread. */
        public void want(FcTelemetry t, FlightTrack track) {
            this.track = track;
            telemetry = t;
            if (t == null) {
                latest = null;
                return;
            }
            if (worker == null) {
                worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread th = new Thread(r, "cameraView");
                    th.setDaemon(true);
                    th.setPriority(Thread.MIN_PRIORITY);
                    return th;
                });
                worker.scheduleWithFixedDelay(this::compute, 0, PERIOD_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }

        /** The view last worked out, or null. */
        public CameraFootprint.View latest() {
            return latest;
        }

        void compute() {
            try {
                FcTelemetry t = telemetry;
                FlightTrack tr = track;
                if (t == null) return;
                CameraFootprint.View v = view.compute(t, tr);
                if (telemetry == t) latest = v; // not one asked away meanwhile
            } catch (RuntimeException e) {
                latest = null; // never ends the thread: the next round tries again
            }
        }
    }

    /** Where the terrain isn't known: the ground at launch, else below the drone by its height over launch, else 50 m. */
    private double groundFallback(FcTelemetry t, FlightTrack track, CameraFootprint.Pose p) {
        double ground = groundAtLaunch(track);
        FcTelemetry.Altitude baro = t.getAltitude();
        return !Double.isNaN(ground) ? ground : baro != null ? p.altM() - baro.meters() : p.altM() - 50;
    }

    /** A position this old still places a drone sitting disarmed (DetectionMarks). */
    public static final long SITTING_STALE_MS = 120_000;

    /**
     * The ray through picture point (u, v), -1..1 across and down the picture as the screen shows it (1.0.46, the
     * motion detection's direction on the map), from the camera's pose now; null without one.
     */
    public CameraFootprint.Ray ray(FcTelemetry t, FlightTrack track, double u, double v) {
        CameraFootprint.Pose p = pose(t, track, SITTING_STALE_MS);
        if (p == null) return null;
        if (terrain != null) terrain.around(p.lat(), p.lon());
        return CameraFootprint.ray(p, u, v, terrain, groundFallback(t, track, p));
    }

    /** The camera's pose for a detection: the drone's position up to SITTING_STALE_MS old. */
    public CameraFootprint.Pose sittingPose(FcTelemetry t, FlightTrack track) {
        return pose(t, track, SITTING_STALE_MS);
    }

    /**
     * The STANAG 4609 metadata for a frame that arrived at arrivedMs (wall clock): the drone, the camera and where the
     * picture lands. Without a position or attitude only the time, names and security marking go out.
     */
    public Klv.Frame klv(long arrivedMs, FcTelemetry t, FlightTrack track, String missionId, String country) {
        CameraFootprint.View v = compute(t, track);
        double nan = Double.NaN;
        if (v == null) {
            return new Klv.Frame(arrivedMs * 1000, missionId, DESIGNATION, SENSOR, nan, nan, nan, nan, nan, nan, nan,
                    hfovDeg, vfovDeg(), 0, tiltDeg, 0, nan, nan, nan, nan, null, country);
        }
        CameraFootprint.Pose p = v.pose();
        FlightTrack.Point last = track.last();
        double[][] corners = null;
        if (v.corners()[0] != null && v.corners()[1] != null && v.corners()[2] != null && v.corners()[3] != null) {
            corners = new double[4][];
            for (int i = 0; i < 4; i++) corners[i] = new double[]{v.corners()[i][0], v.corners()[i][1]};
        }
        double[] c = v.centre();
        return new Klv.Frame(arrivedMs * 1000, missionId, DESIGNATION, SENSOR, p.headingDeg(), p.pitchDeg(), p.rollDeg(),
                last == null ? nan : last.speedKmh() / 3.6, p.lat(), p.lon(), p.altM(), p.hfovDeg(), p.vfovDeg(),
                0, p.tiltDeg(), 0, v.slantRangeM(), c == null ? nan : c[0], c == null ? nan : c[1], c == null ? nan : c[2],
                corners, country);
    }

    /** ST 0601's platform designation and image source sensor. */
    static final String DESIGNATION = "DRAUPNE", SENSOR = "EO FPV";

    /** The terrain's height at the launch point, NaN when either is not known yet; kept once known (launch stays put). */
    private double groundAtLaunch(FlightTrack track) {
        FlightTrack.Point home = track.home();
        if (home == null || terrain == null) return Double.NaN;
        double[] known = launchGround;
        if (known != null && known[0] == home.lat() && known[1] == home.lon()) return known[2];
        double h = terrain.heightM(home.lat(), home.lon()); // the terrain is fetched round the drone, 5 km each way
        if (!Double.isNaN(h)) launchGround = new double[]{home.lat(), home.lon(), h};
        return h;
    }

    private volatile double[] launchGround; // {lat, lon, ground m}
}
