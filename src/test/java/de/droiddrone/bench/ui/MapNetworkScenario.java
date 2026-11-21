package de.droiddrone.bench.ui;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.FakeWeb;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.gui.BenchAccess;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Component;

/**
 * The map in the real window on a bad internet (2026-11-19, the pilot: "the map is stuck loading even though the
 * computer has internet"). Three times the map is sent to a place it has no tiles of while the internet is bad in
 * one way - not there, stalling half way through every tile, answering errors - and then the internet is made good.
 * Nobody touches the window after that: no mouse over the map, no drone that moves on it. The map has to have its
 * tiles by itself, within the time a pilot would wait.
 * <p>
 * Before 1.0.71's fix the second case never ended: two tiles stalled half way held both loader threads for good.
 */
@Tag("ui")
class MapNetworkScenario {
    /** Waits until the map's last paint had every tile, and it stays so for a second; false when it does not in time. */
    static boolean whole(Component map, long withinMs) {
        long end = System.currentTimeMillis() + withinMs;
        long since = 0;
        while (System.currentTimeMillis() < end) {
            boolean missing = Ui.edt(() -> BenchAccess.mapTilesMissing(map));
            if (missing) since = 0;
            else if (since == 0) since = System.currentTimeMillis();
            else if (System.currentTimeMillis() - since >= 1000) return true;
            Ui.pause(100);
        }
        return false;
    }

    private static void badThenGood(AppRig rig, Component map, FakeWeb.Mood bad, String badInWords, String place, long badForMs, long withinMs) throws Exception {
        Ui ui = rig.ui;
        rig.web.mood(bad);
        MapToolsScenario.goTo(ui, place, "Go");
        Ui.pause(badForMs);
        boolean missingWhileBad = Ui.edt(() -> BenchAccess.mapTilesMissing(map));
        ui.shot("the map at " + place + " while the internet is " + badInWords);
        rig.check(missingWhileBad, "while the internet is " + badInWords + " the map at a new place has empty squares (the bench's own check: it can tell)");
        long t0 = System.currentTimeMillis();
        rig.web.mood(FakeWeb.Mood.OK);
        boolean came = whole(map, withinMs);
        long took = System.currentTimeMillis() - t0;
        ui.shot("the map " + took / 1000 + " s after the internet is good again, untouched");
        rig.check(came, "the internet " + badInWords + ", then good: the map has all its tiles by itself within " + withinMs / 1000 + " s (" + took / 1000 + " s)");
        rig.film.remark("Internet " + badInWords + " for " + badForMs / 1000 + " s, then good: the map whole after " + took / 1000 + " s, nobody touching it.");
    }

    @Test
    void theMapComesBackByItselfWhenTheInternetDoes() throws Exception {
        AppRig.scenario("map-bad-internet").drones(0).run(rig -> {
            Ui ui = rig.ui;
            MapToolsScenario.openTools(ui, "TOOLS");
            Component map = MapToolsScenario.map(ui);
            MapToolsScenario.goTo(ui, "60.8669, 11.7381", "Go");
            rig.check(whole(map, 30_000), "on a good internet the map has its tiles");
            ui.shot("the map on a good internet");

            // the PC's network is not up yet, a cable is out, the 4G stick is still dialling
            badThenGood(rig, map, FakeWeb.Mood.DOWN, "not there", "61.5000, 10.5000", 7000, 40_000);
            // every tile's transfer stops half way: a weak link, a PC that moved from Wi-Fi to 4G, a VPN that came up.
            // The app gives a stalled tile up after 20 s and asks again
            badThenGood(rig, map, FakeWeb.Mood.STALL, "stalling half way through every tile", "62.2000, 9.7000", 8000, 75_000);
            // the map's server is there and says 500
            badThenGood(rig, map, FakeWeb.Mood.ERRORS, "answering errors", "63.1000, 10.9000", 7000, 40_000);
        });
    }
}
