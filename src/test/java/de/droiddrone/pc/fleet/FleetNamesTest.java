package de.droiddrone.pc.fleet;

import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FleetNamesTest {
    /**
     * The pilot: a second tour skipped its BTR part. The tour's clean-up removes its drones from the name map, which
     * leaves "name.MobileZulu=" (Names.remove saves ""); the fleet counted those as taken, so every new tour took the
     * next letters down (Victor, Uniform ...). A removed name is free again.
     */
    @Test
    void aNameTheMapRemovedIsFreeAgain() throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("name.MobileZulu", "");
        config.set("name.MobileYankee", "  ");
        config.set("name.MobileX-ray", "PHONE|127.0.0.13|virtual|||1");
        Fleet fleet = new Fleet(config, null, line -> { });
        assertEquals("MobileZulu", fleet.freeName(), "removed: free");
        assertFalse(fleet.nameTaken("MobileYankee"));
        assertTrue(fleet.nameTaken("MobileX-ray"), "a name the map still has stays taken");
        fleet.close();
    }
}
