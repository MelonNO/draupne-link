package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD G1 / GU-6: FOLLOW GROUP's right-click items and which group events are critical. Names made up. */
class FleetOpsTest {
    static FleetOps ops(String flown, List<String> log) {
        return new FleetOps(new FleetOps.Host() {
            public String flownPhone() {
                return flown;
            }

            public java.net.InetSocketAddress addressOf(String phone) {
                return null;
            }

            public String key() {
                return "DD";
            }

            public void log(String line) {
                log.add(line);
            }

            public void alert(String text) {
                log.add("! " + text);
            }

            public void flyNow(String phone) {
            }
        });
    }

    private static List<String> texts(List<JMenuItem> items) {
        List<String> t = new ArrayList<>();
        for (JMenuItem i : items) t.add(i.getText());
        return t;
    }

    @Test
    void followersAreAddedThenTheGroupStarts() {
        List<String> log = new ArrayList<>();
        FleetOps f = ops("MobileAlpha", log);
        assertEquals(List.of(), texts(f.items("MobileAlpha")), "the lead itself: nothing to add");
        List<JMenuItem> items = f.items("MobileBravo");
        assertEquals(List.of("FOLLOW GROUP: add MobileBravo"), texts(items));
        items.get(0).doClick();
        assertEquals(List.of("FOLLOW GROUP: leave out MobileBravo", "FOLLOW GROUP: start (1 follow MobileAlpha)"), texts(f.items("MobileBravo")));
        assertEquals(List.of(), texts(ops(null, log).items("MobileBravo")), "no ArduPilot drone flown: no group");
    }

    @Test
    void startingWithoutAddressesSaysWhy() {
        List<String> log = new ArrayList<>();
        FleetOps f = ops("MobileAlpha", log);
        f.items("MobileBravo").get(0).doClick();
        f.items("MobileBravo").get(1).doClick();
        assertTrue(log.stream().anyMatch(l -> l.contains("isn't reachable")), log.toString());
        f.close();
    }

    @Test
    void criticalEvents() {
        assertTrue(FleetOps.critical("LINK LOST F2 (30 s to the group's move)"));
        assertTrue(FleetOps.critical("GNSS LOST F3"));
        assertTrue(FleetOps.critical("TRAFFIC 40 m"));
        assertFalse(FleetOps.critical("IN GROUP F1"));
    }
}
