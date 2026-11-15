package de.droiddrone.pc.gui;

import de.droiddrone.common.DataReader;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.CameraView;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.map.FlightTrack;
import de.droiddrone.pc.map.Pins;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pins on the video: a drone 100 m over flat ground at (60, 10) looking north, its camera 70 degrees wide and 40
 * high, tilted 30 degrees down - the picture's middle meets the ground 173 m ahead.
 */
class VideoPinsTest {
    private static final double LAT = 60, LON = 10;
    private static final CameraFootprint.Pose NORTH = new CameraFootprint.Pose(LAT, LON, 100, 0, 0, 0, -30, 70, 40);

    private static Pins.Pin pin(String name, double northM, double eastM, boolean rough) {
        return new Pins.Pin(name, LAT + northM / 111_320.0, LON + eastM / (111_320.0 * Math.cos(Math.toRadians(LAT))), 0, rough);
    }

    private static VideoPins.Placed flat(Pins.Pin p) {
        return new VideoPins.Placed(p, 0, false);
    }

    @Test
    void aPinAheadStandsInThePictureWithItsNameAndDistance() {
        List<VideoPins.Mark> marks = VideoPins.marks(NORTH, List.of(flat(pin("Alpha", 173.2, 0, false))));
        assertEquals(1, marks.size());
        VideoPins.Mark m = marks.get(0);
        assertFalse(m.arrow());
        assertEquals(0, m.u(), 1e-3);
        assertEquals(0, m.v(), 1e-3, "the picture's middle");
        assertEquals("ALPHA 170M", m.label());
        assertFalse(m.rough() || m.hidden());

        VideoPins.Mark right = VideoPins.marks(NORTH, List.of(flat(pin("b", 173.2, 60, false)))).get(0);
        assertTrue(right.u() > 0.2 && right.u() < 1, "60 m to the right of it: on the right - " + right.u());
    }

    @Test
    void aRoughPinSaysSoAndOneTheTerrainHidesIsGrey() {
        List<VideoPins.Mark> marks = VideoPins.marks(NORTH, List.of(flat(pin("Bravo", 400, 0, true)),
                new VideoPins.Placed(pin("Charlie", 300, 0, false), 0, true)));
        assertEquals(2, marks.size());
        assertEquals("~BRAVO 400M", marks.get(0).label());
        assertTrue(marks.get(0).rough());
        assertFalse(marks.get(0).hidden());
        assertEquals("CHARLIE 300M", marks.get(1).label());
        assertTrue(marks.get(1).hidden());
    }

    @Test
    void theFurtherPinIsDrawnFirstAndTheNearerOverIt() {
        List<VideoPins.Mark> marks = VideoPins.marks(NORTH, List.of(flat(pin("near", 200, 0, false)), flat(pin("far", 500, 0, false))));
        assertEquals(List.of("FAR 500M", "NEAR 200M"), marks.stream().map(VideoPins.Mark::label).toList());
    }

    @Test
    void pinsBesideBehindAndUnderThePictureGetAnArrowOnItsEdge() {
        VideoPins.Mark east = VideoPins.marks(NORTH, List.of(flat(pin("east", 300, 2000, false)))).get(0);
        assertTrue(east.arrow());
        assertEquals(VideoPins.EDGE, east.u(), 1e-9, "on the right edge");
        assertTrue(east.du() > 0);

        VideoPins.Mark behind = VideoPins.marks(NORTH, List.of(flat(pin("behind", -500, -100, false)))).get(0);
        assertTrue(behind.arrow());
        assertEquals(-VideoPins.EDGE, behind.u(), 1e-9, "behind on the left: turn left");
        assertEquals(-1, behind.du());
        assertEquals(0, behind.dv());

        VideoPins.Mark under = VideoPins.marks(NORTH, List.of(flat(pin("under", 5, 0, false)))).get(0);
        assertTrue(under.arrow(), "straight under the drone, the camera looking 10 to 50 degrees down");
        assertEquals(VideoPins.EDGE, under.v(), 1e-9, "on the bottom edge");
        assertTrue(under.dv() > 0);
        assertEquals("UNDER 5M", under.label());
    }

    @Test
    void onlyTheNearestPinsOutsideGetArrowsAndNoneStandsOnAnother() {
        List<VideoPins.Placed> pins = new ArrayList<>();
        for (int i = 0; i < 8; i++) pins.add(flat(pin("p" + i, -500 - 100 * i, 50, false))); // behind on the right
        List<VideoPins.Mark> marks = VideoPins.marks(NORTH, pins);
        assertEquals(VideoPins.MAX_ARROWS, marks.size());
        assertEquals(List.of("P0 500M", "P1 600M", "P2 700M", "P3 800M", "P4 900M"),
                marks.stream().map(VideoPins.Mark::label).toList(), "the nearest five");
        for (int i = 0; i < marks.size(); i++) {
            VideoPins.Mark a = marks.get(i);
            assertEquals(VideoPins.EDGE, a.u(), 1e-9);
            assertTrue(Math.abs(a.v()) <= VideoPins.EDGE + 1e-9);
            for (int j = 0; j < i; j++) {
                assertTrue(Math.abs(a.v() - marks.get(j).v()) >= VideoPins.GAP_SIDE - 1e-9, a + " stands on " + marks.get(j));
            }
        }
    }

    @Test
    void pinsOutOfRangeAreLeftOut() {
        assertTrue(VideoPins.marks(NORTH, List.of(flat(pin("far", VideoPins.MAX_RANGE_M + 500, 0, false)))).isEmpty());
        assertTrue(VideoPins.marks(NORTH, List.of(flat(pin("far", 0, -VideoPins.MAX_RANGE_M - 500, false)))).isEmpty(), "no arrow either");
    }

    @Test
    void distancesAreShortAndSteady() {
        assertEquals("42M", VideoPins.distance(42.3));
        assertEquals("860M", VideoPins.distance(856));
        assertEquals("2.4KM", VideoPins.distance(2449));
        assertEquals("12KM", VideoPins.distance(12_400));
    }

    // ------------------------------------------------------------------ the picture's delay

    @Test
    void thePictureIsOlderThanTheAttitudeByItsDelayLessTheAttitudesWayHere() {
        assertEquals(0, VideoPins.shiftMs(-1, 5, 40), "no G2G (an older phone app): the newest pose");
        assertEquals(160, VideoPins.shiftMs(180, 10, 60), "180 ms to the PC, 10 to paint, the attitude 30 ms on its way");
        assertEquals(0, VideoPins.shiftMs(20, 0, 80), "never newer than the newest");
        assertEquals(VideoPins.MAX_SHIFT_MS, VideoPins.shiftMs(5000, 0, 0), "a frozen picture: as far back as is kept for");
    }

    private static CameraFootprint.Pose heading(double deg, double pitch) {
        return new CameraFootprint.Pose(LAT, LON, 100, deg, pitch, 0, -30, 70, 40);
    }

    @Test
    void thePoseOfAMomentIsTakenBetweenTheTwoKeptRoundIt() {
        List<VideoPins.Sample> h = List.of(new VideoPins.Sample(1000, heading(350, -4)), new VideoPins.Sample(1100, heading(10, -2)),
                new VideoPins.Sample(1200, heading(30, 0)));
        assertEquals(0, VideoPins.at(h, 1050).headingDeg(), 1e-9, "350 to 10 the short way: through north");
        assertEquals(-3, VideoPins.at(h, 1050).pitchDeg(), 1e-9);
        assertEquals(20, VideoPins.at(h, 1150).headingDeg(), 1e-9);
        assertEquals(350, VideoPins.at(h, 500).headingDeg(), 1e-9, "older than kept: the oldest");
        assertEquals(30, VideoPins.at(h, 1300).headingDeg(), 1e-9, "newer than any: the newest");
        assertNull(VideoPins.at(List.of(), 1000));
    }

    @Test
    void aTurnLeavesThePinWhereThePictureShowsIt() {
        // the picture taken while the drone looked north, shown now it looks 20 degrees right: the pin stays in the middle
        List<VideoPins.Sample> h = List.of(new VideoPins.Sample(1000, heading(0, 0)), new VideoPins.Sample(1200, heading(20, 0)));
        List<VideoPins.Placed> alpha = List.of(flat(pin("alpha", 173.2, 0, false)));
        assertEquals(0, VideoPins.marks(VideoPins.at(h, 1000), alpha).get(0).u(), 1e-3);
        assertTrue(VideoPins.marks(VideoPins.at(h, 1200), alpha).get(0).u() < -0.3, "the newest pose would put it well left");
    }

    // ------------------------------------------------------------------ live: from the telemetry and the track

    private static final FcInfo BETAFLIGHT = new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 4, 1, 0, 1, 45, 0);

    private interface Writer {
        void write(DataOutputStream out) throws Exception;
    }

    private static void parse(FcTelemetry t, Writer w) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        w.write(new DataOutputStream(bytes));
        t.parse(new DataReader(bytes.toByteArray(), true), BETAFLIGHT);
    }

    /** Nose 30 degrees down, looking north, a compass among the sensors; the drone's GPS at (60, 10), 250 m. */
    private static FcTelemetry drone() throws Exception {
        FcTelemetry t = new FcTelemetry();
        parse(t, out -> {
            out.writeShort(FcCommon.MSP_STATUS);
            out.writeShort(125);
            out.writeShort(0);
            out.writeShort(1 | 32 | 4); // ACC, GYRO, MAG
            out.writeByte(0);
            out.writeShort(10);
            out.writeByte(4);
            out.writeInt(0);
            out.writeInt(0);
            out.writeByte(0);
            out.writeShort(38);
            out.writeShort(FcCommon.MSP_RAW_GPS);
            out.writeByte(2);
            out.writeByte(12);
            out.writeInt((int) Math.round(LAT * 1e7));
            out.writeInt((int) Math.round(LON * 1e7));
            out.writeShort(250);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(120);
            out.writeInt(0);
            out.writeShort(FcCommon.MSP_ATTITUDE);
            out.writeShort(0);
            out.writeShort(300);
            out.writeShort(0);
        });
        return t;
    }

    @Test
    void theLivePinsComeFromTheDronesPoseAndNoneWithoutOne() throws Exception {
        CameraView view = new CameraView(null); // no map tiles: the ground taken as flat, 50 m under the drone
        VideoPins pins = new VideoPins(view);
        FcTelemetry t = drone();
        FlightTrack track = new FlightTrack(null);
        // the ground 50 m under, the nose 30 degrees down: the picture's middle 86.6 m ahead
        List<Pins.Pin> one = List.of(pin("Alpha", 86.6, 0, false));
        assertTrue(pins.marks(t, track, one, false, 0, MonoClock.ms()).isEmpty(), "no position yet");
        track.sample(t.getGps(), false);
        List<VideoPins.Mark> marks = pins.marks(t, track, one, false, 0, MonoClock.ms());
        assertEquals(1, marks.size());
        assertEquals(0, marks.get(0).u(), 0.01);
        assertEquals(0, marks.get(0).v(), 0.01);
        assertFalse(marks.get(0).hidden(), "no terrain: nothing hidden");
        assertTrue(pins.marks(t, track, List.of(), false, 0, MonoClock.ms()).isEmpty(), "no pins");
        assertTrue(pins.marks(new FcTelemetry(), track, one, false, 0, MonoClock.ms()).isEmpty(), "nothing says which way it looks");
        assertEquals(1, pins.marks(t, track, one, false, 120, MonoClock.ms()).size(), "a delay with one pose kept: that one");
    }

    // ------------------------------------------------------------------ drawn

    /** Half a pixel right of 320: the stem, 1.6 px wide, covers pixel 320 whole. */
    private static final double X = 320.5 / 640 - 1;

    private static int[] paint(VideoPins.Mark m, OsdLayout layout) {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            new OsdRenderer().paint(g, 0, 0, 1280, 720, new OsdPreview().data().withPins(List.of(m)), layout);
        } finally {
            g.dispose();
        }
        // the diamond's middle: its stem 18 and half its height 9 over the foot at (320.5, 576) - pixel 320 whole
        return new int[]{img.getRGB(320, 549) & 0xffffff, img.getRGB(320, 570) & 0xffffff};
    }

    private static boolean near(int rgb, Color c) {
        return Math.abs((rgb >> 16 & 0xff) - c.getRed()) < 12 && Math.abs((rgb >> 8 & 0xff) - c.getGreen()) < 12
                && Math.abs((rgb & 0xff) - c.getBlue()) < 12;
    }

    @Test
    void aPinIsTheMapsDiamondOnAStemGreyWhereHiddenAndNoneSwitchedOff() {
        OsdLayout layout = OsdLayout.defaults().with(OsdElement.HORIZON, new OsdLayout.Placement(false, 0.5, 0.5));
        VideoPins.Mark seen = new VideoPins.Mark("ALPHA 850M", X, 0.6, false, 0, 0, false, false, 850);
        int[] shown = paint(seen, layout);
        assertTrue(near(shown[0], Theme.WARN), "the map's pin colour: " + Integer.toHexString(shown[0]));
        assertTrue(near(shown[1], Theme.WARN), "its stem: " + Integer.toHexString(shown[1]));
        int[] hidden = paint(new VideoPins.Mark("ALPHA 850M", X, 0.6, false, 0, 0, false, true, 850), layout);
        assertTrue(near(hidden[0], Theme.MUTED), "behind a hill: grey - " + Integer.toHexString(hidden[0]));
        int[] off = paint(seen, layout.with(OsdElement.PINS, new OsdLayout.Placement(false, 0.5, 0.5)));
        assertEquals(0, off[0], "switched off in SETTINGS > OSD");
    }
}
