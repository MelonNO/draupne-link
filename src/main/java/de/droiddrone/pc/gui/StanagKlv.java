package de.droiddrone.pc.gui;

import de.droiddrone.pc.CameraView;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.video.Klv;

import java.util.List;

/**
 * The STANAG 4609 metadata of a drone this PC does not fly (1.0.65): where it is and which way it looks, from what the
 * network says - and, for a drone in overwatch, its sector (the pilot: "the sectors from when a drone is in overwatch
 * to get transferred with the data as well"): the camera's place and angles and the four corners of what it watches,
 * as the map draws it, so a player (ATAK, QGIS) draws the same sector.
 */
final class StanagKlv {
    private StanagKlv() {
    }

    /**
     * What is known of a drone's place: NaN where it isn't.
     * @param sector the camera's view on the ground (a drone in overwatch), or null
     */
    record Place(double lat, double lon, double altM, double headingDeg, CameraFootprint.View sector) {
        static final Place UNKNOWN = new Place(Double.NaN, Double.NaN, Double.NaN, Double.NaN, null);

        boolean known() {
            return !Double.isNaN(lat) && !Double.isNaN(lon);
        }
    }

    /** One metadata packet's content for a drone at this place; without a place: the time, names and marking alone. */
    static Klv.Frame frame(long ms, String name, Place p, String mission, String country) {
        double nan = Double.NaN;
        if (p == null) p = Place.UNKNOWN;
        CameraFootprint.View v = p.sector();
        if (v == null || v.pose() == null) {
            return new Klv.Frame(ms * 1000, mission, name, CameraView.SENSOR, p.headingDeg(), nan, nan, nan, p.lat(), p.lon(), p.altM(),
                    nan, nan, nan, nan, nan, nan, nan, nan, nan, null, country);
        }
        CameraFootprint.Pose c = v.pose();
        double[][] corners = corners(v);
        double[] centre = v.centre();
        if (centre == null && corners != null) { // a level camera's middle ray meets no ground: the middle of what it watches
            double lat = 0, lon = 0;
            for (double[] k : corners) {
                lat += k[0] / 4;
                lon += k[1] / 4;
            }
            centre = new double[]{lat, lon, nan};
        }
        return new Klv.Frame(ms * 1000, mission, name, CameraView.SENSOR, c.headingDeg(), c.pitchDeg(), c.rollDeg(), 0,
                c.lat(), c.lon(), c.altM(), c.hfovDeg(), c.vfovDeg(), 0, c.tiltDeg(), 0, v.slantRangeM(),
                centre == null ? nan : centre[0], centre == null ? nan : centre[1], centre == null || centre.length < 3 ? nan : centre[2],
                corners, country);
    }

    /**
     * The sector's four corners, upper left, upper right, lower right, lower left: where the picture's corner rays
     * meet the ground, or - a ray that meets none within the watch range - where the map's outline ends it.
     */
    static double[][] corners(CameraFootprint.View v) {
        List<double[]> outline = v.outline();
        if (outline == null || outline.size() < 4 || outline.size() % 4 != 0) return null;
        int per = outline.size() / 4;
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] hit = v.corners() == null ? null : v.corners()[i];
            double[] at = hit != null ? hit : outline.get(i * per);
            if (at == null || Double.isNaN(at[0]) || Double.isNaN(at[1])) return null;
            out[i] = new double[]{at[0], at[1]};
        }
        return out;
    }
}
