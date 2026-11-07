package de.droiddrone.pc;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.MotionRecord;
import de.droiddrone.pc.map.CameraFootprint;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The motion detection on the PC (1.0.46): the record read, the boxes kept and faded, the marks for the map. */
class MotionBoxesTest {
    private static final FcInfo BETAFLIGHT = FcTelemetryTest.BETAFLIGHT;

    private static MotionRecord.Box box(int id, float x) {
        return new MotionRecord.Box(id, x, 0.4f, 0.05f, 0.1f, 0, 0.9f, 0.1f, MotionRecord.KIND_MOVING);
    }

    @Test
    void theRecordIsReadAndWhatFollowsItToo() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bytes);
        MotionRecord.write(o, new MotionRecord.Report(MotionRecord.FLAG_ACTIVE, 70, List.of(box(3, 0.2f), box(4, 0.7f))));
        o.writeShort(FcTelemetry.PHONE_UPDATE); // a record after it is read on
        o.writeByte(1); o.writeByte(45); o.writeUTF("x");
        FcTelemetry t = new FcTelemetry();
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
        assertEquals(0, t.getUnknownCodes());
        assertNotNull(t.getPhoneUpdate(), "the record after DETECTION");
        MotionRecord.Report r = t.getMotion().last();
        assertTrue(r.active());
        assertEquals(2, t.getMotion().shown(MonoClock.ms()).size());
        assertEquals(2, t.getMotion().newIds());
    }

    @Test
    void aBoxStaysSolidThenFadesWithinASecond() {
        MotionBoxes m = new MotionBoxes();
        m.update(new MotionRecord.Report(MotionRecord.FLAG_ACTIVE, 0, List.of(box(1, 0.1f), box(2, 0.5f))), 1000);
        m.update(new MotionRecord.Report(MotionRecord.FLAG_ACTIVE, 0, List.of(box(1, 0.12f))), 1200); // 2 went
        List<MotionBoxes.Shown> s = m.shown(1300);
        assertEquals(2, s.size());
        assertEquals(1, s.get(0).alpha(), 1e-6, "box 1 seen 100 ms ago");
        assertEquals(0.12f, s.get(0).box().x, 1e-6, "where it was last");
        assertTrue(s.get(1).alpha() < 1 && s.get(1).alpha() > 0, "box 2 fading");
        assertEquals(1, m.shown(2100).size(), "box 2 gone a second after its last record");
        assertTrue(m.shown(2199).get(0).alpha() > 0);
        assertTrue(m.shown(2200).isEmpty(), "box 1 gone too");
        assertEquals(2, m.newIds(), "a box seen again is not new");
        assertEquals(1, MotionBoxes.alpha(MotionBoxes.SOLID_MS), 1e-6);
        assertEquals(0, MotionBoxes.alpha(MotionBoxes.FADE_MS), 1e-6);
    }

    @Test
    void theBoxCentreIsTurnedWithThePicture() {
        MotionRecord.Box b = new MotionRecord.Box(1, 0.7f, 0.1f, 0.1f, 0.2f, 0, 1, 0, 0); // centre 0.75, 0.2
        double[] plain = DetectionMarks.pictureUv(b, false, false);
        assertEquals(0.5, plain[0], 1e-6);
        assertEquals(-0.6, plain[1], 1e-6);
        double[] upsideDown = DetectionMarks.pictureUv(b, true, true); // turned 180: both ways
        assertEquals(-0.5, upsideDown[0], 1e-6);
        assertEquals(0.6, upsideDown[1], 1e-6);
    }

    @Test
    void aDetectionRayGoesAlongTheBoxesDirectionAndMeetsTheGround() {
        double lat = 60, lon = 10;
        // facing east, the camera tilted 30 degrees down, 100 m over flat ground, 70 degrees across
        CameraFootprint.Pose p = new CameraFootprint.Pose(lat, lon, 100, 90, 0, 0, -30, 70, 40);
        CameraFootprint.Ray centre = CameraFootprint.ray(p, 0, 0, (a, b) -> 0, Double.NaN);
        assertEquals(90, centre.bearingDeg(), 0.01);
        assertEquals(200, centre.slantRangeM(), 1);
        double eastM = (centre.ground()[1] - lon) * 111_195 * Math.cos(Math.toRadians(lat));
        assertEquals(100 / Math.tan(Math.toRadians(30)), eastM, 1.5);
        // at the right edge: the camera's right is the drone's right (south of east); tilted, the angle on the ground
        // is atan(tan 35 / cos 30)
        CameraFootprint.Ray right = CameraFootprint.ray(p, 1, 0, (a, b) -> 0, Double.NaN);
        assertEquals(90 + Math.toDegrees(Math.atan(Math.tan(Math.toRadians(35)) / Math.cos(Math.toRadians(30)))), right.bearingDeg(), 0.05);
        // level: the centre ray never meets the ground - a direction, no ground point
        CameraFootprint.Pose level = new CameraFootprint.Pose(lat, lon, 100, 270, 0, 0, 0, 70, 40);
        CameraFootprint.Ray far = CameraFootprint.ray(level, 0, 0, (a, b) -> 0, Double.NaN);
        assertEquals(270, far.bearingDeg(), 0.01);
        assertNull(far.ground());
    }

    @Test
    void marksFollowTheirBoxAndFadeAfterHalfAMinute() {
        DetectionMarks marks = new DetectionMarks();
        CameraFootprint.Ray r1 = new CameraFootprint.Ray(80, new double[]{60.001, 10.002, 0}, 150, false);
        CameraFootprint.Ray r2 = new CameraFootprint.Ray(85, null, Double.NaN, false);
        marks.put(7, false, 60, 10, r1, 1000);
        marks.put(7, false, 60, 10, r2, 2000); // the same thing, seen again
        marks.put(8, true, 60, 10, r1, 2000);
        marks.put(9, false, 60, 10, null, 2000); // no pose: no mark
        List<DetectionMarks.Mark> m = marks.marks(2000);
        assertEquals(2, m.size());
        assertEquals(85, m.get(0).bearingDeg(), 1e-9);
        assertNull(m.get(0).ground());
        assertTrue(m.get(1).light());
        assertEquals(1, m.get(0).alpha(2000), 1e-6);
        assertTrue(m.get(0).alpha(2000 + DetectionMarks.KEEP_MS - 1) > 0);
        assertTrue(marks.marks(2000 + DetectionMarks.KEEP_MS).isEmpty());
        assertFalse(marks.marks(0).size() > 0);
    }
}
