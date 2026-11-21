package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The line at the map's bottom right (1.0.71, the test bench's F-005): on the mini-map the long one began outside
 * the card and lay over the scale bar. As much of it as fits; the map's attribution always.
 */
class MapAttributionTest {
    private static final ToIntFunction<String> SIX_A_LETTER = s -> s.length() * 6;

    @Test
    void theLineIsShortenedToWhatFitsBesideTheScaleBar() {
        String why = "MobileX-ray is armed: a drone here is flying";
        String full = "© Kartverket   SAVED TILES ONLY - " + why;
        assertEquals(full, MapView.attributionThatFits("© Kartverket", "SAVED TILES ONLY", why, SIX_A_LETTER, full.length() * 6), "the map window: all of it");
        assertEquals("© Kartverket   SAVED TILES ONLY", MapView.attributionThatFits("© Kartverket", "SAVED TILES ONLY", why, SIX_A_LETTER, 200),
                "the mini-map: without the reason");
        assertEquals("© Kartverket", MapView.attributionThatFits("© Kartverket", "SAVED TILES ONLY", why, SIX_A_LETTER, 100), "a very small map: the attribution");
        assertEquals("© Kartverket", MapView.attributionThatFits("© Kartverket", null, null, SIX_A_LETTER, 10), "the attribution is always shown");
        assertEquals("© Kartverket   SAVED TILES ONLY", MapView.attributionThatFits("© Kartverket", "SAVED TILES ONLY", null, SIX_A_LETTER, 400));
    }

    /** The bench's picture of the 1093 x 584 window: the mini-map 70 high, "SAVED TILES ONLY" under the minus button. */
    @Test
    void onALowMapTheLineEndsLeftOfTheZoomButtons() {
        assertEquals(300 - 8, MapView.attributionRight(300, 400, 13, true), "a map with room: at its right edge");
        assertEquals(300 - 46, MapView.attributionRight(300, 70, 13, true), "the mini-map in a small window: left of + and -");
        assertEquals(300 - 8, MapView.attributionRight(300, 70, 13, false), "a picture for EXPORT has no zoom buttons");
    }
}
