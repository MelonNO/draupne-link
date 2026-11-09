package de.droiddrone.pc.gui;

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

    @Test
    void noMarksBeforeAnyDetection() throws Exception {
        assertTrue(controller(PcConfig.defaults()).marks(System.currentTimeMillis()).isEmpty());
    }
}
