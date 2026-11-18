package de.droiddrone.pc.fleet;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.CameraView;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.FlightTrack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.70: the PC's sums for where a ground point shows in the picture (the pins on the video; MARK and the map's view
 * are the same sums the other way) against the virtual fleet's own camera - a DEMO drone's pins stand on what its
 * picture shows, and turn and tilt with it.
 */
class VirtualCameraMatchTest {
    private static final double HOME_LAT = 60, HOME_LON = 10;
    private static final int W = 1280, H = 720;

    /** north, east, alt, roll, pitch (nose up +, the model's), heading */
    private static final double[][] VIEWS = {{0, 0, 40, 0, 0, 30}, {120, -80, 75, 12, -20, 250}, {-50, 30, 20, -8, 10, 355},
            {10, 10, 120, 3, -45, 90}};

    @Test
    void theVirtualCamerasAngleIsTheOneThePcTakesForIt() {
        assertEquals(Scene.HFOV_DEG, CameraView.VIRTUAL_HFOV);
        CameraView view = new CameraView(null);
        view.setPictureSize(W, H);
        view.setVirtual(true);
        assertEquals(Scene.HFOV_DEG, view.hfovDeg());
        assertEquals(0, view.tiltDeg(), "the virtual camera looks along the nose");
        view.setVirtual(false);
        assertEquals(CameraView.DEFAULT_HFOV, view.hfovDeg(), "a real drone: the settings again");
    }

    @Test
    void aGroundPointShowsWhereTheVirtualCameraDrawsIt() {
        double vfov = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(Scene.HFOV_DEG / 2)) * H / W));
        int compared = 0;
        for (double[] v : VIEWS) {
            Scene.Camera cam = new Scene.Camera(new Scene.View(v[0], v[1], v[2], v[3], v[4], v[5]), W, H);
            CameraFootprint.Pose pose = new CameraFootprint.Pose(lat(v[0]), lon(v[1]), v[2] + Scene.Camera.EYE_M, v[5], v[4], v[3],
                    0, Scene.HFOV_DEG, vfov);
            for (int n = -300; n <= 300; n += 50) {
                for (int e = -300; e <= 300; e += 50) {
                    double[] c = cam.cam(n, e, 0);
                    if (c[2] < 5) continue; // behind or right under the lens
                    double px = cam.cx + cam.f * c[0] / c[2], py = cam.cy + cam.f * c[1] / c[2];
                    if (px < 0 || px > W || py < 0 || py > H) continue;
                    double[] s = CameraFootprint.toPicture(pose, lat(n), lon(e), 0);
                    String at = "view " + java.util.Arrays.toString(v) + ", ground " + n + " N " + e + " E";
                    assertTrue(s[2] > 0, at);
                    assertEquals(px, (s[0] + 1) / 2 * W, 1.0, at);
                    assertEquals(py, (s[1] + 1) / 2 * H, 1.0, at);
                    compared++;
                }
            }
        }
        assertTrue(compared > 40, "points in sight compared: " + compared);
    }

    private static double lat(double northM) {
        return HOME_LAT + northM / 111_320.0;
    }

    private static double lon(double eastM) {
        return HOME_LON + eastM / (111_320.0 * Math.cos(Math.toRadians(HOME_LAT)));
    }

    /** As VirtualDrone sends it: the model's nose up turned into Betaflight's nose down +. */
    @Test
    void aVirtualDroneNoseDownIsNoseDownOnThePc() throws Exception {
        FcTelemetry t = new FcTelemetry();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        FlightModel fc = new FlightModel(new FlightModel.Setup(), HOME_LAT, HOME_LON, 0, 0);
        fc.overlay(HOME_LAT, HOME_LON, 50, 0, 20, 0, true); // a flight's CSV: Betaflight's 20 degrees nose down
        assertEquals(-20, fc.pitchDeg(), 1e-9, "the model's own: nose up +");
        TelemetryRecords.attitude(out, fc.rollDeg(), -fc.pitchDeg(), fc.headingDeg());
        TelemetryRecords.status(out, TelemetryRecords.SENSOR_ACC | TelemetryRecords.SENSOR_GYRO | TelemetryRecords.SENSOR_MAG, 0, 0, 10, 38);
        t.parse(new DataReader(bytes.toByteArray(), true), new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 4, 1, 0, 1, 45, 0));
        assertEquals(20, t.getAttitude().pitchDeg(), 0.05, "sent on as the flight had it");
        FlightTrack track = new FlightTrack(null);
        track.sample(new FcTelemetry.Gps(2, 12, 0, 50, -1, 0, HOME_LAT, HOME_LON, -1, 0, 1.2, System.currentTimeMillis()), false);
        CameraFootprint.Pose p = new CameraView(null).pose(t, track);
        assertNotNull(p);
        assertEquals(-20, p.pitchDeg(), 0.05, "the PC's pose: looking 20 degrees down, as the picture does");
    }
}
