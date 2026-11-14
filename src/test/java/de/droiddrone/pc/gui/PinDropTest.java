package de.droiddrone.pc.gui;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.MotionRecord;
import de.droiddrone.pc.CameraView;
import de.droiddrone.pc.DetectionMarks;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.Coordinates;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.RadioHorizon;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MARK's decision: a drone 100 m over flat ground at (60, 10), its camera 90 degrees wide and high, tilted 45 degrees
 * down - the picture's middle meets the ground 100 m ahead, 141 m away; half way to the right edge, 70.7 m to the
 * right of that.
 */
class PinDropTest {
    private static final double LAT = 60, LON = 10;
    private static final RadioHorizon.Terrain FLAT = (lat, lon) -> 0;

    private static double northM(double lat) {
        return (lat - LAT) * 111_195;
    }

    private static double eastM(double lon) {
        return (lon - LON) * 111_195 * Math.cos(Math.toRadians(LAT));
    }

    private static CameraFootprint.Pose pose(double altM, double headingDeg, double tiltDeg) {
        return new CameraFootprint.Pose(LAT, LON, altM, headingDeg, 0, 0, tiltDeg, 90, 90);
    }

    /** A sight with a good position: the drone's GPS, 3D fix, 10 satellites, a point just in. */
    private static PinDrop.Sight sight(CameraFootprint.Pose p, double u, double v, RadioHorizon.Terrain terrain) {
        return new PinDrop.Sight(true, true, p, CameraFootprint.ray(p, u, v, terrain, 0), false, 2, 10, 0, 200);
    }

    private static PinDrop.Sight with(PinDrop.Sight s, boolean phoneGps, int fix, int sats, int accuracyM, long ageMs) {
        return new PinDrop.Sight(s.position(), s.heading(), s.pose(), s.ray(), phoneGps, fix, sats, accuracyM, ageMs);
    }

    // ------------------------------------------------------------------ the pin's place

    @Test
    void theMiddleOfThePictureIsAPin100mAhead() {
        PinDrop.Verdict v = PinDrop.decide(sight(pose(100, 0, -45), 0, 0, FLAT));
        assertNull(v.refusal());
        assertEquals(100, northM(v.lat()), 1);
        assertEquals(0, eastM(v.lon()), 1);
        assertFalse(v.rough(), "known terrain, 3D fix, 141 m, 45 degrees: a point - " + v.why());
    }

    @Test
    void rightOfTheMiddleIsRightOfTheDroneAndTheHeadingTurnsIt() {
        PinDrop.Verdict north = PinDrop.decide(sight(pose(100, 0, -45), 0.5, 0, FLAT));
        assertEquals(100, northM(north.lat()), 1);
        assertEquals(70.7, eastM(north.lon()), 1, "half way to the right edge of a 90 degree picture, 141 m out");
        PinDrop.Verdict east = PinDrop.decide(sight(pose(100, 90, -45), 0.5, 0, FLAT));
        assertEquals(-70.7, northM(east.lat()), 1, "looking east, right is south");
        assertEquals(100, eastM(east.lon()), 1);
        assertFalse(north.rough() || east.rough());
    }

    @Test
    void aClickIsAPointOfThePictureAsShownAndNothingBesideIt() {
        Rectangle picture = new Rectangle(100, 50, 800, 400); // black bars left and right of it
        assertArrayEquals(new double[]{0.00125, 0.0025}, PinDrop.pictureUv(new Point(500, 250), picture), 1e-9, "the middle pixel");
        assertArrayEquals(new double[]{-0.99875, -0.9975}, PinDrop.pictureUv(new Point(100, 50), picture), 1e-9, "the first pixel");
        assertArrayEquals(new double[]{0.99875, 0.9975}, PinDrop.pictureUv(new Point(899, 449), picture), 1e-9, "the last one");
        assertNull(PinDrop.pictureUv(new Point(99, 250), picture), "the bar beside it");
        assertNull(PinDrop.pictureUv(new Point(900, 250), picture));
        assertNull(PinDrop.pictureUv(new Point(500, 450), picture));
        assertNull(PinDrop.pictureUv(new Point(5, 5), new Rectangle()), "no picture placed yet");
    }

    /**
     * A camera mounted upside down (or a front camera's mirror): the view turns the picture, so the screen shows the
     * drone's own left and right whatever the mounting - the click is not turned back. The same as a motion box drawn
     * at that place of the screen gets (DetectionMarks.pictureUv, from the camera's own coordinates), so MARK and the
     * detections' marks agree on the map.
     */
    @Test
    void anUpsideDownOrMirroredViewDropsThePinWhereTheScreenShowsIt() {
        Rectangle picture = new Rectangle(100, 50, 800, 400);
        Point click = new Point(700, 250); // three quarters across, half way down
        double[] uv = PinDrop.pictureUv(click, picture);
        for (boolean[] flip : new boolean[][]{{false, false}, {true, true}, {true, false}, {false, true}}) {
            float[] own = DetectionAreaLayer.toCamera(click, picture, flip[0], flip[1]); // where the camera itself has it
            double[] shown = DetectionMarks.pictureUv(new MotionRecord.Box(1, own[0], own[1], 0, 0, 0, 1, 0, 0), flip[0], flip[1]);
            assertEquals(shown[0], uv[0], 2.0 / 800 + 1e-6, "flip " + flip[0] + " " + flip[1]);
            assertEquals(shown[1], uv[1], 2.0 / 400 + 1e-6);
        }
        PinDrop.Verdict v = PinDrop.decide(sight(pose(100, 0, -45), uv[0], uv[1], FLAT));
        assertEquals(100, northM(v.lat()), 1.5);
        assertEquals(70.7, eastM(v.lon()), 1.5, "right of the middle on the screen: right of the drone, turned camera or not");
    }

    // ------------------------------------------------------------------ no pin

    @Test
    void noPositionNoHeadingNoAttitudeNoPin() {
        CameraFootprint.Pose p = pose(100, 0, -45);
        CameraFootprint.Ray ray = CameraFootprint.ray(p, 0, 0, FLAT, 0);
        assertEquals("NO PIN - NO POSITION", PinDrop.decide(new PinDrop.Sight(false, false, null, null, false, 0, 0, 0, Long.MAX_VALUE)).refusal());
        assertEquals("NO PIN - NO POSITION", PinDrop.decide(null).refusal());
        assertEquals("NO PIN - NO HEADING", PinDrop.decide(new PinDrop.Sight(true, false, null, null, false, 2, 10, 0, 200)).refusal());
        assertEquals("NO PIN - NO ATTITUDE", PinDrop.decide(new PinDrop.Sight(true, true, null, null, false, 2, 10, 0, 200)).refusal(),
                "a heading from the phone, nothing from the flight controller");
        PinDrop.Verdict v = PinDrop.decide(new PinDrop.Sight(false, true, p, ray, false, 2, 10, 0, 200));
        assertEquals(PinDrop.NO_POSITION, v.refusal(), "the position first");
        assertTrue(Double.isNaN(v.lat()) && !v.rough());
    }

    @Test
    void skyOrGroundPastThreeKilometresIsNoPin() {
        assertEquals("NO PIN - SKY OR OVER 3 KM", PinDrop.decide(sight(pose(100, 0, 30), 0, 0, FLAT)).refusal(), "30 degrees up: sky");
        // 1 degree down from 100 m: the ground 5.7 km off
        assertEquals(PinDrop.NO_GROUND, PinDrop.decide(sight(pose(100, 0, -1), 0, 0, FLAT)).refusal());
        // 2.5 degrees down: 2290 m off - a pin, far and flat
        PinDrop.Verdict near = PinDrop.decide(sight(pose(100, 0, -2.5), 0, 0, FLAT));
        assertNull(near.refusal());
        assertEquals(100 / Math.tan(Math.toRadians(2.5)), northM(near.lat()), 15);
        assertEquals(EnumSet.of(PinDrop.Rough.FAR, PinDrop.Rough.GRAZING), near.why());
    }

    // ------------------------------------------------------------------ rough, each reason alone

    @Test
    void unknownTerrainTakenAsFlatIsRough() {
        PinDrop.Verdict v = PinDrop.decide(sight(pose(100, 0, -45), 0, 0, null)); // no terrain: the fallback height
        assertEquals(Set.of(PinDrop.Rough.FLAT_GROUND), v.why());
        assertEquals(100, northM(v.lat()), 1, "the pin all the same");
    }

    @Test
    void aWeakGpsIsRough() {
        PinDrop.Sight good = sight(pose(100, 0, -45), 0, 0, FLAT);
        assertEquals(Set.of(PinDrop.Rough.GPS), PinDrop.decide(with(good, false, 1, 10, 0, 200)).why(), "a 2D fix");
        assertEquals(Set.of(PinDrop.Rough.GPS), PinDrop.decide(with(good, false, 0, 0, 0, 200)).why(), "no fix now: the track's last point");
        assertEquals(Set.of(PinDrop.Rough.GPS), PinDrop.decide(with(good, false, 2, PinDrop.MIN_SATS - 1, 0, 200)).why(), "5 satellites");
        assertFalse(PinDrop.decide(with(good, false, 2, PinDrop.MIN_SATS, 0, 200)).rough(), "6: enough");
        // the phone's GPS has no fix and no satellites to tell: its accuracy
        assertFalse(PinDrop.decide(with(good, true, 0, 0, 8, 200)).rough(), "the phone's GPS, 8 m");
        assertEquals(Set.of(PinDrop.Rough.GPS), PinDrop.decide(with(good, true, 0, 0, PinDrop.PHONE_ACCURACY_M + 1, 200)).why());
        assertEquals(Set.of(PinDrop.Rough.GPS), PinDrop.decide(with(good, true, 2, 10, 0, 200)).why(), "the phone's, accuracy unknown");
    }

    @Test
    void overAKilometreIsRough() {
        PinDrop.Verdict v = PinDrop.decide(sight(pose(1000, 0, -45), 0, 0, FLAT)); // 1414 m away, at 45 degrees
        assertEquals(Set.of(PinDrop.Rough.FAR), v.why());
        assertEquals(1000, northM(v.lat()), 5);
        assertFalse(PinDrop.decide(sight(pose(700, 0, -45), 0, 0, FLAT)).rough(), "990 m");
    }

    @Test
    void aFlatAngleToTheGroundIsRough() {
        CameraFootprint.Pose low = pose(20, 0, -4); // 4 degrees down from 20 m: 286 m off
        PinDrop.Sight s = sight(low, 0, 0, FLAT);
        assertEquals(4, PinDrop.grazingDeg(low, s.ray()), 0.05);
        assertEquals(Set.of(PinDrop.Rough.GRAZING), PinDrop.decide(s).why());
        CameraFootprint.Pose steeper = pose(20, 0, -6);
        assertFalse(PinDrop.decide(sight(steeper, 0, 0, FLAT)).rough(), "6 degrees");
        assertEquals(45, PinDrop.grazingDeg(pose(100, 0, -45), sight(pose(100, 0, -45), 0, 0, FLAT).ray()), 0.05);
    }

    @Test
    void anOldPositionIsRough() {
        PinDrop.Sight good = sight(pose(100, 0, -45), 0, 0, FLAT);
        assertEquals(Set.of(PinDrop.Rough.OLD_POSITION), PinDrop.decide(with(good, false, 2, 10, 0, PinDrop.FRESH_POSITION_MS + 1)).why());
        assertFalse(PinDrop.decide(with(good, false, 2, 10, 0, PinDrop.FRESH_POSITION_MS)).rough());
    }

    // ------------------------------------------------------------------ the lines

    @Test
    void theVideoLineNamesThePinAndItsGridAndARoughOneWithATilde() {
        Pins.Pin pin = new Pins.Pin("Alpha", 61.149, 11.394, 1, false), rough = new Pins.Pin("Bravo", 61.149, 11.394, 1, true);
        String grid = Coordinates.format(Coordinates.Format.MGRS, 61.149, 11.394);
        assertEquals("PIN ALPHA " + grid, PinDrop.line(pin, Coordinates.Format.MGRS));
        assertEquals("PIN ~BRAVO " + grid, PinDrop.line(rough, null), "no format said: MGRS");
        assertTrue(PinDrop.line(pin, Coordinates.Format.DECIMAL).endsWith("61.149000 11.394000"), "the pilot's own format");
        assertEquals("Pin Alpha from the video: " + grid + ".", PinDrop.logLine(pin, null, Set.of()));
        assertEquals("Pin Bravo from the video: " + grid + " - ROUGH (terrain unknown, flat ground assumed; over 1 km).",
                PinDrop.logLine(rough, null, EnumSet.of(PinDrop.Rough.FLAT_GROUND, PinDrop.Rough.FAR)));
    }

    // ------------------------------------------------------------------ from the telemetry and the track

    private static final FcInfo BETAFLIGHT = new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 4, 1, 0, 1, 45, 0);
    private static final int MAG = 4, ACC = 1, GYRO = 32;

    private interface Writer {
        void write(DataOutputStream out) throws Exception;
    }

    private static void parse(FcTelemetry t, Writer w) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        w.write(new DataOutputStream(bytes));
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
    }

    private static void gps(FcTelemetry t, int fix, int sats) throws Exception {
        parse(t, out -> {
            out.writeShort(FcCommon.MSP_RAW_GPS);
            out.writeByte(fix);
            out.writeByte(sats);
            out.writeInt((int) Math.round(LAT * 1e7));
            out.writeInt((int) Math.round(LON * 1e7));
            out.writeShort(250); // m above the sea
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(120);
            out.writeInt(0);
        });
    }

    /** Betaflight's attitude: decidegrees, nose down positive; yaw in degrees. */
    private static void attitude(FcTelemetry t, int pitchDownDeg, int yawDeg) throws Exception {
        parse(t, out -> {
            out.writeShort(FcCommon.MSP_ATTITUDE);
            out.writeShort(0);
            out.writeShort(pitchDownDeg * 10);
            out.writeShort(yawDeg);
        });
    }

    private static void compass(FcTelemetry t) throws Exception {
        parse(t, out -> {
            out.writeShort(FcCommon.MSP_STATUS);
            out.writeShort(125);
            out.writeShort(0);
            out.writeShort(ACC | GYRO | MAG);
            out.writeByte(0);
            out.writeShort(10);
            out.writeByte(4);
            out.writeInt(0); // mode flags
            out.writeInt(0); // arming flags
            out.writeByte(0);
            out.writeShort(38);
        });
    }

    /** Betaflight's status with its mode names known: ARM on or off, a compass among the sensors. */
    private static void status(FcTelemetry t, boolean armed) throws Exception {
        parse(t, out -> {
            byte[] names = "ARM;ANGLE;".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.writeShort(FcCommon.MSP_BOXNAMES);
            out.writeShort(names.length);
            out.write(names);
            out.writeShort(FcCommon.MSP_STATUS);
            out.writeShort(125);
            out.writeShort(0);
            out.writeShort(ACC | GYRO | MAG);
            out.writeByte(0);
            out.writeShort(10);
            out.writeByte(4);
            out.writeByte(armed ? 1 : 0);
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
            out.writeInt(0); // arming flags
            out.writeByte(0);
            out.writeShort(38);
        });
    }

    /**
     * A position that stopped coming (the GPS lost its fix): a drone sitting disarmed is still there - a rough pin; one
     * in the air has flown on - no pin.
     */
    @Test
    void anOldPositionPlacesASittingDroneButNotAFlyingOne() throws Exception {
        CameraView view = new CameraView(null);
        FcTelemetry t = new FcTelemetry();
        FlightTrack track = new FlightTrack(null);
        long halfAMinuteAgo = System.currentTimeMillis() - 30_000;
        track.sample(new FcTelemetry.Gps(2, 12, 0, 250, -1, 0, LAT, LON, -1, 0, 1.2, halfAMinuteAgo), false);
        attitude(t, 45, 0);
        status(t, false);
        PinDrop.Sight sitting = PinDrop.look(view, t, track, 0, 0);
        assertTrue(sitting.position());
        assertTrue(sitting.positionAgeMs() >= 30_000);
        PinDrop.Verdict v = PinDrop.decide(sitting);
        assertNull(v.refusal());
        assertTrue(v.why().contains(PinDrop.Rough.OLD_POSITION), v.why().toString());
        assertEquals(50, northM(v.lat()), 1);

        status(t, true);
        status(t, true); // armed, said twice (one report alone does not count)
        attitude(t, 45, 0);
        assertEquals(PinDrop.NO_POSITION, PinDrop.decide(PinDrop.look(view, t, track, 0, 0)).refusal());
        track.sample(new FcTelemetry.Gps(2, 12, 40, 250, -1, 0, LAT, LON, -1, 0, 1.2, System.currentTimeMillis()), true);
        assertNull(PinDrop.decide(PinDrop.look(view, t, track, 0, 0)).refusal(), "a fresh point: a pin again");
    }

    @Test
    void theSightSaysWhatIsMissingAndThenCastsTheRay() throws Exception {
        CameraView view = new CameraView(null); // no map tiles here: the ground is taken as flat, 50 m under the drone
        assertEquals(PinDrop.NO_POSITION, PinDrop.decide(PinDrop.look(view, null, new FlightTrack(null), 0, 0)).refusal(), "not connected");
        FcTelemetry t = new FcTelemetry();
        FlightTrack track = new FlightTrack(null);
        attitude(t, 45, 90);
        compass(t);
        assertEquals(PinDrop.NO_POSITION, PinDrop.decide(PinDrop.look(view, t, track, 0, 0)).refusal(), "no point in the track yet");

        gps(t, 2, 12);
        track.sample(t.getGps(), false);
        FcTelemetry mute = new FcTelemetry(); // a position, but nothing says which way the drone looks
        assertEquals(PinDrop.NO_HEADING, PinDrop.decide(PinDrop.look(view, mute, track, 0, 0)).refusal());

        PinDrop.Sight s = PinDrop.look(view, t, track, 0, 0);
        assertTrue(s.position() && s.heading());
        assertFalse(s.phoneGps());
        assertEquals(2, s.fix());
        assertEquals(12, s.sats());
        assertTrue(s.positionAgeMs() < 2000, "just sampled: " + s.positionAgeMs());
        PinDrop.Verdict v = PinDrop.decide(s);
        assertNull(v.refusal());
        // nose 45 degrees down, looking east, the ground 50 m under it: 50 m east
        assertEquals(0, northM(v.lat()), 1);
        assertEquals(50, eastM(v.lon()), 1);
        assertEquals(Set.of(PinDrop.Rough.FLAT_GROUND), v.why(), "the terrain isn't known: rough, and only for that");

        gps(t, 1, 4); // the fix goes: the track's last point stays, the pin is rough for the GPS too
        assertEquals(EnumSet.of(PinDrop.Rough.FLAT_GROUND, PinDrop.Rough.GPS), PinDrop.decide(PinDrop.look(view, t, track, 0, 0)).why());
    }
}
