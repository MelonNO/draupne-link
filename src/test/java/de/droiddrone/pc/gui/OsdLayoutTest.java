package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OsdLayoutTest {
    /** The OSD as it was before it could be changed (plus 1.0.27's picture age, sticks age and status line): these on, the extras off. */
    private static final Set<OsdElement> STANDARD = Set.of(OsdElement.CROSSHAIR, OsdElement.HORIZON, OsdElement.HEADING,
            OsdElement.BATTERY, OsdElement.PING, OsdElement.NETWORK, OsdElement.VIDEO, OsdElement.PHONE_BATTERY,
            OsdElement.PICTURE_AGE, OsdElement.STICKS_AGE, OsdElement.BANNER,
            OsdElement.ARMED, OsdElement.FLIGHT_MODE, OsdElement.FLY_TIME, OsdElement.CORE_TEMP, OsdElement.GPS_SATS,
            OsdElement.SPEED, OsdElement.ALTITUDE, OsdElement.HOME, OsdElement.WARNINGS, OsdElement.DEBUG);

    @Test
    void defaultsAreTheStandardOsdWithTheExtrasOff() {
        OsdLayout d = OsdLayout.defaults();
        for (OsdElement e : OsdElement.values()) assertEquals(STANDARD.contains(e), d.isOn(e), e.name());
    }

    @Test
    void alignmentFollowsTheThirdOfThePicture() {
        assertEquals(OsdLayout.Align.LEFT, OsdLayout.defaults().get(OsdElement.BATTERY).align());
        assertEquals(OsdLayout.Align.RIGHT, OsdLayout.defaults().get(OsdElement.PING).align());
        assertEquals(OsdLayout.Align.CENTER, OsdLayout.defaults().get(OsdElement.WARNINGS).align());
        assertEquals(OsdLayout.Align.CENTER, OsdLayout.defaults().get(OsdElement.STICKS_LEFT).align());
        assertEquals(OsdLayout.Align.LEFT, OsdLayout.defaults().get(OsdElement.CORE_TEMP).align());
    }

    @Test
    void savedAndLoadedAgain() {
        OsdLayout l = OsdLayout.defaults()
                .with(OsdElement.THROTTLE, new OsdLayout.Placement(true, 0.25, 0.5))
                .with(OsdElement.DEBUG, new OsdLayout.Placement(false, 0.5, 0.98));
        Map<String, String> file = new HashMap<>(l.toSettings());
        OsdLayout back = OsdLayout.load(file::get);
        assertTrue(back.isOn(OsdElement.THROTTLE));
        assertEquals(0.25, back.get(OsdElement.THROTTLE).x(), 1e-5);
        assertEquals(0.5, back.get(OsdElement.THROTTLE).y(), 1e-5);
        assertFalse(back.isOn(OsdElement.DEBUG));
        assertEquals("on,0.25000,0.50000", file.get("osd.throttle"));
    }

    @Test
    void missingOrBrokenEntriesMeanTheDefault() {
        Map<String, String> file = Map.of("osd.battery", "maybe,0.1,0.1", "osd.ping", "on,2.0,0.1", "osd.network", "off,abc,0.1",
                "osd.video", "");
        OsdLayout l = OsdLayout.load(k -> file.getOrDefault(k, ""));
        assertEquals(OsdLayout.defaults(), l);
    }

    @Test
    void warningsCantBeTurnedOffAndTheCrosshairCantMove() {
        OsdLayout l = OsdLayout.defaults()
                .with(OsdElement.WARNINGS, new OsdLayout.Placement(false, 0.5, 0.3))
                .with(OsdElement.CROSSHAIR, new OsdLayout.Placement(true, 0.1, 0.1));
        assertTrue(l.isOn(OsdElement.WARNINGS), "warnings stay on");
        assertEquals(0.3, l.get(OsdElement.WARNINGS).y(), 1e-9, "but move");
        assertEquals(0.5, l.get(OsdElement.CROSSHAIR).x(), 1e-9);
        OsdLayout loaded = OsdLayout.load(Map.of("osd.warnings", "off,0.5,0.66")::get);
        assertTrue(loaded.isOn(OsdElement.WARNINGS), "not from a hand-edited settings file either");
    }

    @Test
    void warningsPlacedLowStayInThePicture() {
        OsdLayout low = OsdLayout.defaults().with(OsdElement.WARNINGS, new OsdLayout.Placement(true, 0.5, 0.97));
        OsdRenderer.Data sample = new OsdPreview().data();
        // the longest block: two warnings and the two lines about the lost flight controller
        OsdRenderer.Data d = new OsdRenderer.Data(true, true, 42, false, true, sample.telemetry(), sample.video(), false, null,
                sample.channels(), false, false, false, false, null, false, null, false);
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Map<OsdElement, Rectangle2D> bounds = new EnumMap<>(OsdElement.class);
        new OsdRenderer().paint(g, 0, 0, 1280, 720, d, low, bounds);
        g.dispose();
        Rectangle2D w = bounds.get(OsdElement.WARNINGS);
        assertNotNull(w);
        assertTrue(w.getMaxY() <= 720, "bottom of the warnings: " + w.getMaxY());
    }

    /** The HUD's new lines at once, placed low: the whole block, arm switch line included, stays in the picture. */
    @Test
    void theWarningBlockWithEveryLineStaysInThePicture() {
        OsdLayout low = OsdLayout.defaults().with(OsdElement.WARNINGS, new OsdLayout.Placement(true, 0.5, 0.97));
        OsdRenderer.Data sample = new OsdPreview().data();
        OsdRenderer.Data d = new OsdRenderer.Data(true, true, 42, false, true, sample.telemetry(), sample.video(), false, null,
                sample.channels(), false, false, true, false, null, false, null, false, 140, true, "LINK BACK - ARMED - STICKS LIVE", true);
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Map<OsdElement, Rectangle2D> bounds = new EnumMap<>(OsdElement.class);
        new OsdRenderer().paint(g, 0, 0, 1280, 720, d, low, bounds);
        g.dispose();
        Rectangle2D w = bounds.get(OsdElement.WARNINGS);
        assertNotNull(w);
        assertTrue(w.getMaxY() <= 720, "bottom of the warnings: " + w.getMaxY());
        assertTrue(w.getHeight() > 38 * 4, "several lines: " + w.getHeight());
        assertTrue(bounds.containsKey(OsdElement.STICKS_AGE) && bounds.containsKey(OsdElement.BANNER), "the new elements drew");
    }

    @Test
    void theOldDataConstructorStillWorks() {
        OsdRenderer.Data sample = new OsdPreview().data();
        OsdRenderer.Data d = new OsdRenderer.Data(true, true, 42, true, false, sample.telemetry(), sample.video(), false, null,
                sample.channels(), false, false, false, false, null, false, null, false);
        assertEquals(-1, d.sticksAgeMs());
        assertNull(d.banner());
        assertFalse(d.armSwitchUnknown());
        assertFalse(d.spectatorPaused());
    }

    /** A far 4G link sits at 150 ms: green there; what turns it yellow and red is the ping over its own usual. */
    @Test
    void theLinkColourIsByTheExcessOverTheLinksOwnBaseline() {
        assertEquals(de.droiddrone.pc.gui.Theme.OK, OsdRenderer.pingColor(150, 140));
        assertEquals(de.droiddrone.pc.gui.Theme.WARN, OsdRenderer.pingColor(150 + OsdRenderer.PING_EXCESS_WARN_MS + 1, 150));
        assertEquals(de.droiddrone.pc.gui.Theme.BAD, OsdRenderer.pingColor(150 + OsdRenderer.PING_EXCESS_ALARM_MS + 1, 150));
        assertEquals(de.droiddrone.pc.gui.Theme.BAD, OsdRenderer.pingColor(151, -1), "no baseline yet: the fixed thresholds");
        assertEquals(de.droiddrone.pc.gui.Theme.OK, OsdRenderer.pingColor(60, -1));
        assertEquals(de.droiddrone.pc.gui.Theme.BAD, OsdRenderer.pingColor(-1, 100), "no pong");
    }

    @Test
    void aWholeHudPaintsInAFewMilliseconds() {
        OsdLayout all = OsdLayout.defaults();
        for (OsdElement e : OsdElement.values()) all = all.with(e, new OsdLayout.Placement(true, all.get(e).x(), all.get(e).y()));
        OsdPreview sample = new OsdPreview();
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        OsdRenderer r = new OsdRenderer();
        for (int i = 0; i < 20; i++) r.paint(g, 0, 0, 1920, 1080, sample.data(), all); // warm up
        long t0 = System.nanoTime();
        int n = 50;
        for (int i = 0; i < n; i++) r.paint(g, 0, 0, 1920, 1080, sample.data(), all);
        double ms = (System.nanoTime() - t0) / 1e6 / n;
        g.dispose();
        System.out.printf(java.util.Locale.ROOT, "OSD with every element at 1080p: %.2f ms per paint%n", ms);
        assertTrue(ms < 25, "a frame at 60 fps is 16 ms, the HUD must be a fraction of it: " + ms);
    }

    @Test
    void everyElementDrawsInsideThePictureWithTheSampleData() {
        OsdLayout all = OsdLayout.defaults();
        for (OsdElement e : OsdElement.values()) all = all.with(e, new OsdLayout.Placement(true, all.get(e).x(), all.get(e).y()));
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Map<OsdElement, Rectangle2D> bounds = new EnumMap<>(OsdElement.class);
        new OsdRenderer().paint(g, 0, 0, 1280, 720, new OsdPreview().data(), all, bounds);
        g.dispose();
        Rectangle2D picture = new Rectangle2D.Double(0, 0, 1280, 720);
        for (OsdElement e : OsdElement.values()) {
            if (!e.movable) continue;
            assertTrue(bounds.containsKey(e), e + " drawn");
            assertTrue(picture.contains(bounds.get(e)), e + " inside the picture: " + bounds.get(e));
        }
    }
}
