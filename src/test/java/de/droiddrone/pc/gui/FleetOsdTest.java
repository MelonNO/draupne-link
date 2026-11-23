package de.droiddrone.pc.gui;

import de.droiddrone.common.Observe;
import de.droiddrone.common.ObserveGate;
import de.droiddrone.pc.FleetSwitches;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.route.Dispatch;
import de.droiddrone.pc.setup.ModeSwitch;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD GU-5 (the OSD's fleet elements), AL-20 / AL-21 (the launch views), FD-2 / FD-5 (battery home, HOME NOW),
 * FD-9 (the dispatch preview), C3 / AL-20 on the phone (the commander's view through ObserveGate), GU-7 (the radio's
 * fleet switches). Names and places made up.
 */
class FleetOsdTest {
    private static Map<OsdElement, Rectangle2D> paint(OsdRenderer.Data d, OsdLayout layout) {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Map<OsdElement, Rectangle2D> bounds = new EnumMap<>(OsdElement.class);
        new OsdRenderer().paint(g, 0, 0, 1280, 720, d, layout, bounds);
        g.dispose();
        return bounds;
    }

    private static OsdLayout allOn() {
        OsdLayout all = OsdLayout.defaults();
        for (OsdElement e : OsdElement.values()) all = all.with(e, new OsdLayout.Placement(true, all.get(e).x(), all.get(e).y()));
        return all;
    }

    @Test
    void theEditorsSampleDrawsEveryFleetElementAndNothingWithoutAFleet() {
        OsdRenderer.Data sample = new OsdPreview().data();
        Map<OsdElement, Rectangle2D> b = paint(sample, allOn());
        for (OsdElement e : List.of(OsdElement.TRAFFIC, OsdElement.HOME_NOW, OsdElement.RESERVES, OsdElement.GROUP, OsdElement.FLOOR,
                OsdElement.GNSS)) {
            assertNotNull(b.get(e), e + " drawn in the editor's sample");
            assertTrue(new Rectangle2D.Double(0, 0, 1280, 720).contains(b.get(e)), e + " inside the picture: " + b.get(e));
        }
        assertNotNull(b.get(OsdElement.LEAD_DOWN), "LEAD DOWN in the sample, steady");
        assertNotNull(b.get(OsdElement.MINI_VIEWS), "a launch view in the sample (a dark box until its picture)");
        Map<OsdElement, Rectangle2D> none = paint(sample.withFleet(null), allOn());
        for (OsdElement e : List.of(OsdElement.TRAFFIC, OsdElement.HOME_NOW, OsdElement.RESERVES, OsdElement.GROUP, OsdElement.GNSS)) {
            assertNull(none.get(e), e + ": no fleet, nothing drawn (a Betaflight drone's OSD is as before)");
        }
    }

    @Test
    void theFleetElementsKeepOutOfEachOthersWay() {
        Map<OsdElement, Rectangle2D> b = paint(new OsdPreview().data(), OsdLayout.defaults());
        List<OsdElement> fleet = List.of(OsdElement.TRAFFIC, OsdElement.HOME_NOW, OsdElement.LEAD_DOWN, OsdElement.MINI_VIEWS);
        for (OsdElement f : fleet) {
            Rectangle2D r = b.get(f);
            if (r == null) continue;
            for (Map.Entry<OsdElement, Rectangle2D> e : b.entrySet()) {
                if (e.getKey() == f || !e.getKey().movable || e.getKey() == OsdElement.WARNINGS || e.getKey() == OsdElement.BANNER) continue;
                assertFalse(e.getValue().intersects(r), e.getKey() + " under " + f + ": " + e.getValue() + " / " + r);
            }
        }
    }

    @Test
    void launchViewsStackFailedOnesShowRedAndTheRecordingLeavesThemOut() {
        BufferedImage pic = new BufferedImage(480, 270, BufferedImage.TYPE_INT_RGB);
        OsdFleet f = new OsdFleet(null, null, Double.NaN, Double.NaN, null, null, null, false, false,
                List.of(new OsdFleet.Mini("R1", pic, "CLIMBING", 12, 40, false), new OsdFleet.Mini("R2", null, "LAUNCH FAILED", 2, 40, true)));
        OsdRenderer.Data d = new OsdPreview().data().withFleet(f);
        Rectangle2D one = paint(d.withFleet(new OsdFleet(null, null, Double.NaN, Double.NaN, null, null, null, false, false,
                List.of(f.minis().get(0)))), OsdLayout.defaults()).get(OsdElement.MINI_VIEWS);
        Rectangle2D two = paint(d, OsdLayout.defaults()).get(OsdElement.MINI_VIEWS);
        assertNotNull(one);
        assertTrue(two.getHeight() > one.getHeight() * 1.8, "two stacked: " + two + " vs " + one);
        assertTrue(one.getWidth() > 200 && one.getWidth() < 300, "about a fifth of 1280: " + one);
        assertTrue(f.withoutMinis().minis().isEmpty(), "the lead's recording leaves them out (AL-21)");
        assertNull(paint(d.withFleet(f.withoutMinis()), OsdLayout.defaults()).get(OsdElement.MINI_VIEWS));
    }

    @Test
    void homeNowAndTheBatteryHome() {
        assertEquals("LEFT 5:00", FleetOps.timeLeft(33, 0.1), "30 % at 0.1 % a second");
        assertNull(FleetOps.timeLeft(50, Double.NaN), "not measured yet");
        assertFalse(FleetOps.homeNow(60, 0.05, 1000), "1140 s left, 100 s home");
        assertTrue(FleetOps.homeNow(10, 0.05, 1000), "140 s left, 100 s home x 1.25 + 30");
        assertEquals(55, FleetOps.batteryAfterHome(60, 0.05, 1000), 1e-9, "100 s home at 0.05 %/s");
        FleetOps.Drain d = new FleetOps.Drain();
        d.add(0, 80);
        d.add(10_000, 79);
        assertTrue(Double.isNaN(d.ratePerS()), "under 30 s: not yet");
        d.add(40_000, 76);
        assertEquals(0.1, d.ratePerS(), 1e-9);
        assertEquals("NE", FleetOps.compass(44));
        assertEquals("N", FleetOps.compass(359));
    }

    @Test
    void theDispatchPreviewCountsTheWaitForThePilotAndWhoRunsLow() {
        List<Dispatch.Leg> legs = List.of(
                new Dispatch.Leg("D1", 60.01, 10, 0, null, 60, 0),
                new Dispatch.Leg("D2", 60.01, 10.001, 10, null, 70, 10),
                new Dispatch.Leg("D3", 60.05, 10, 20, null, 80, 20));
        List<FleetOps.Preview> p = FleetOps.preview(legs, n -> new double[]{60.0, 10.0});
        assertEquals(0, p.get(0).waitS(), 1e-9, "the first: no wait");
        assertTrue(p.get(1).waitS() > 200, "the second waits for the pilot's turn with the first: " + p.get(1).waitS());
        assertFalse(p.get(0).runsLow());
        assertTrue(p.get(2).runsLow(), "the third: far, and waits two turns");
        String text = FleetOps.previewText(p);
        assertTrue(text.contains("RUNS LOW") && text.contains("TRANSIT"), text);
    }

    @Test
    void theCommanderMayViewItsArmedDroneAndNobodyDuringAPilotSession() {
        assertEquals(Observe.REFUSED_ARMED, ObserveGate.request(false, 60_000, false, () -> false, true, false, false));
        assertEquals(Observe.STREAMING, ObserveGate.request(false, 60_000, false, () -> false, true, false, true), "launch / fleet view");
        assertEquals(Observe.REFUSED_PILOT, ObserveGate.request(true, 0, false, () -> false, true, false, true), "a pilot session: never");
        assertEquals(Observe.STREAMING, ObserveGate.ended(false, false, true, false, true));
        assertEquals(Observe.REFUSED_ARMED, ObserveGate.ended(false, false, true, false, false));
    }

    @Test
    void theRadiosFleetSwitchesOnlyWhenPutOnIt() throws Exception {
        PcConfig c = PcConfig.defaults();
        assertTrue(java.util.Arrays.stream(ModeSwitch.purposes(c)).noneMatch(p -> p != null && p.title().equals("DISCARD LEAD")),
                "a Betaflight pilot's calibration is as before");
        short[] f = new short[12];
        java.util.Arrays.fill(f, (short) 1000);
        FleetSwitches off = FleetSwitches.from(c);
        off.onFrame(f, 12, 0);
        f[3 + 5] = 2000;
        assertEquals(FleetSwitches.NONE, off.onFrame(f, 12, 1000), "not on the radio: AUX5 is something else, never DISCARD LEAD");
        for (Map.Entry<String, String> e : FleetSwitches.settings(true).entrySet()) c.set(e.getKey(), e.getValue());
        ModeSwitch.Purpose[] p = ModeSwitch.purposes(c);
        assertEquals("DISCARD LEAD", p[4].title(), "AUX5");
        assertEquals("CALL RESERVE", p[5].title(), "AUX6");
        FleetSwitches on = FleetSwitches.from(c);
        f[3 + 5] = 1000;
        on.onFrame(f, 12, 0);
        f[3 + 5] = 2000;
        assertEquals(FleetSwitches.DISCARD_LEAD, on.onFrame(f, 12, 1000));
    }
}
