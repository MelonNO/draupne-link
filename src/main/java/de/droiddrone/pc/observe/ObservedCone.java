package de.droiddrone.pc.observe;

import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.RadioHorizon;

import java.util.ArrayList;
import java.util.List;

/**
 * An observed drone's view cone on the map (1.0.48): a sitting drone's camera looks at the horizon, so its
 * CameraFootprint is mostly sky - the cone is drawn instead as the picture's width fanned out from the phone, each
 * edge ray (CameraFootprint.ray across the picture's middle row) ending where it meets the ground or at
 * {@link #RANGE_M}.
 */
public final class ObservedCone {
    /** How far a cone reaches on the map where its rays meet no ground. */
    public static final double RANGE_M = 400;
    /** The camera above the ground it sits on, m. */
    static final double CAMERA_HEIGHT_M = 1.5;
    static final int RAYS = 9;

    private ObservedCone() {
    }

    /**
     * The cone's far edge, {lat, lon} points left to right (the phone itself is its apex, not in the list); empty
     * without a heading.
     * @param groundM the ground's height under the phone (above the sea), the terrain taken as flat there
     * @param hfovDeg the picture's width in degrees; tiltDeg the camera's tilt up
     */
    public static List<double[]> edge(double lat, double lon, double groundM, double headingDeg, double hfovDeg, double tiltDeg,
                                      double aspect) {
        List<double[]> out = new ArrayList<>();
        if (Double.isNaN(headingDeg) || Double.isNaN(lat) || Double.isNaN(lon)) return out;
        double hfov = hfovDeg > 1 && hfovDeg < 179 ? hfovDeg : 70;
        double vfov = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(hfov / 2)) / (aspect > 0 ? aspect : 16 / 9.0)));
        CameraFootprint.Pose pose = new CameraFootprint.Pose(lat, lon, groundM + CAMERA_HEIGHT_M, headingDeg, 0, 0, tiltDeg, hfov, vfov);
        for (int i = 0; i < RAYS; i++) {
            double u = -1 + 2.0 * i / (RAYS - 1);
            CameraFootprint.Ray r = CameraFootprint.ray(pose, u, 0, null, groundM);
            double reach = r.ground() != null && !Double.isNaN(r.slantRangeM()) ? Math.min(RANGE_M, r.slantRangeM()) : RANGE_M;
            double b = Math.toRadians(r.bearingDeg());
            out.add(RadioHorizon.offset(lat, lon, Math.sin(b) * reach, Math.cos(b) * reach));
        }
        return out;
    }
}
