package de.droiddrone.pc.gui;

import de.droiddrone.common.DetectionAreas;
import de.droiddrone.common.MotionRecord;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Each drone's own motion detection level: kept per phone, the old single setting as the fallback, NORMAL when never set. */
class DetectionControllerTest {
    private final List<Map<String, String>> saved = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    private DetectionController controller(PcConfig config) {
        return new DetectionController(config, null, m -> {
            saved.add(m);
            m.forEach(config::set);
            return true;
        }, log::add);
    }

    @Test
    void eachDroneHasItsOwnLevel() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set(DetectionController.DETECTION_KEY, "");
        DetectionController d = controller(config);
        assertEquals(2, d.levelFor("MobileAlpha"), "never set: NORMAL");
        assertEquals(2, d.levelFor(null));
        config.set(DetectionController.DETECTION_KEY, " high ");
        assertEquals(3, d.levelFor("MobileAlpha"), "the setting for all");
        config.set(DetectionController.DETECTION_KEY + ".MobileAlpha", "off");
        assertEquals(0, d.levelFor("MobileAlpha"));
        assertEquals(3, d.levelFor("MobileBravo"));
        config.set(DetectionController.DETECTION_KEY + ".MobileBravo", "loud");
        assertEquals(2, d.levelFor("MobileBravo"), "a word it doesn't know: NORMAL");
    }

    @Test
    void settingItSavesForTheChosenPhone() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set(Names.PHONE_NAME_KEY, "MobileAlpha");
        DetectionController d = controller(config);
        d.set(1, null);
        assertEquals(List.of(Map.of("detection.MobileAlpha", "low")), saved);
        assertEquals(List.of("Motion detection LOW (while disarmed)."), log);
        assertEquals(1, d.levelFor("MobileAlpha"));

        config.set(Names.PHONE_NAME_KEY, "");
        d.set(0, null);
        assertEquals(Map.of("detection", "off"), saved.get(1), "no phone chosen: the setting for all");
        assertEquals("Motion detection off.", log.get(1));
    }

    /**
     * 1.0.63: the pilot's log said "no picture from this camera. Update the phone app." at every SHARP step - the
     * camera's session rebuilt for the new size. Said only when it lasts 3 s; again only after a picture came.
     */
    @Test
    void aMomentWithoutAPictureIsNotSaid() throws Exception {
        DetectionController d = controller(PcConfig.defaults());
        MotionRecord.Report none = new MotionRecord.Report(MotionRecord.FLAG_ACTIVE | MotionRecord.FLAG_NO_PICTURE, 70, List.of());
        MotionRecord.Report seen = new MotionRecord.Report(MotionRecord.FLAG_ACTIVE, 70, List.of());
        d.sayNoPicture(none, 1000);
        d.sayNoPicture(none, 2000);
        d.sayNoPicture(seen, 2500);
        d.sayNoPicture(none, 3500);
        d.sayNoPicture(none, 6000);
        assertTrue(log.isEmpty(), log.toString());
        d.sayNoPicture(none, 6500);
        assertEquals(List.of("Motion detection: no picture from this camera. Update the phone app."), log);
        d.sayNoPicture(none, 20_000);
        assertEquals(1, log.size(), "once");
        d.sayNoPicture(seen, 21_000);
        d.sayNoPicture(none, 22_000);
        d.sayNoPicture(none, 25_000);
        assertEquals(2, log.size(), "again after a picture came");
        d.sayNoPicture(null, 30_000);
        d.sayNoPicture(new MotionRecord.Report(0, 70, List.of()), 30_000);
    }

    /** 1.0.58: each drone's detection areas, saved as text in its camera's own picture and read back the same. */
    @Test
    void eachDroneHasItsOwnAreas() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set(Names.PHONE_NAME_KEY, "MobileAlpha");
        DetectionController d = controller(config);
        assertTrue(d.areasFor("MobileAlpha").isEmpty(), "none set: none");
        DetectionAreas a = DetectionAreas.of(List.of(new DetectionAreas.Area(0.5f, 0.1f, 0.25f, 0.3f, 1)));
        d.setAreas(a, null);
        assertEquals(List.of(Map.of("detectionAreas.MobileAlpha", "0.5000,0.1000,0.2500,0.3000,low")), saved);
        assertEquals(a, d.areasFor("MobileAlpha"));
        assertTrue(d.areasFor("MobileBravo").isEmpty(), "another drone's are its own");
        assertEquals(2, d.levelFor("MobileAlpha"), "the level is not touched");
    }

    /** 1.0.58: a box drawn on a mirrored, upside-down picture is kept where the camera sees it, and drawn back there. */
    @Test
    void anAreaDrawnOnATurnedPictureIsTheCamerasOwn() {
        java.awt.Rectangle r = new java.awt.Rectangle(100, 50, 400, 200);
        java.awt.Point p = new java.awt.Point(100, 50), q = new java.awt.Point(200, 100); // the screen's top left quarter
        DetectionAreas.Area plain = DetectionAreaLayer.toCamera(p, q, r, false, false, 0);
        assertEquals(0, plain.x, 1e-4);
        assertEquals(0, plain.y, 1e-4);
        DetectionAreas.Area turned = DetectionAreaLayer.toCamera(q, p, r, true, true, 0);
        assertEquals(0.75f, turned.x, 1e-4, "mirrored: the camera's right");
        assertEquals(0.75f, turned.y, 1e-4, "upside down: the camera's bottom");
        assertEquals(0.25f, turned.w, 1e-4);
        assertEquals(new java.awt.Rectangle(100, 50, 100, 50), DetectionAreaLayer.toScreen(turned, r, true, true), "drawn back where it was drawn");
    }

    @Test
    void noMarksBeforeAnyDetection() throws Exception {
        assertTrue(controller(PcConfig.defaults()).marks(System.currentTimeMillis()).isEmpty());
    }
}
