package de.droiddrone.pc.observe;

import de.droiddrone.common.Observe;
import de.droiddrone.pc.Names;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** OBSERVATION's grid, its set and selection, CONNECT, the idle-only rule and the heading (1.0.48). */
class ObservationTest {
    // ------------------------------------------------------------------ the grid

    @Test
    void layoutByCount() {
        assertArrayEquals(new int[]{0, 0}, TileGrid.shape(0));
        assertArrayEquals(new int[]{1, 1}, TileGrid.shape(1));
        assertArrayEquals(new int[]{2, 1}, TileGrid.shape(2));
        assertArrayEquals(new int[]{2, 2}, TileGrid.shape(3));
        assertArrayEquals(new int[]{2, 2}, TileGrid.shape(4));
        assertArrayEquals(new int[]{3, 2}, TileGrid.shape(5));
        assertArrayEquals(new int[]{3, 2}, TileGrid.shape(6));
    }

    @Test
    void tilesFillTheAreaRowByRow() {
        Rectangle[] r = TileGrid.layout(6, 1206, 606, 6, -1);
        assertEquals(new Rectangle(0, 0, 398, 300), r[0]);
        assertEquals(new Rectangle(404, 0, 398, 300), r[1]);
        assertEquals(new Rectangle(808, 0, 398, 300), r[2]);
        assertEquals(new Rectangle(0, 306, 398, 300), r[3]);
        assertEquals(5, TileGrid.at(r, 900, 400));
        assertEquals(-1, TileGrid.at(r, 401, 10), "the gap is no tile");
        Rectangle[] one = TileGrid.layout(1, 800, 450, 6, -1);
        assertEquals(new Rectangle(0, 0, 800, 450), one[0]);
    }

    @Test
    void anEnlargedTileTakesTheWholeArea() {
        Rectangle[] r = TileGrid.layout(4, 800, 450, 6, 2);
        assertEquals(new Rectangle(0, 0, 800, 450), r[2]);
        assertTrue(r[0].isEmpty());
        assertTrue(r[3].isEmpty());
        assertEquals(2, TileGrid.at(r, 10, 10));
    }

    // ------------------------------------------------------------------ the set and the selection

    @Test
    void theSetIsRememberedAndHoldsSix() {
        ObserveSet s = ObserveSet.parse("MobileAlpha, MobileBravo,,mobilealpha");
        assertEquals(List.of("MobileAlpha", "MobileBravo"), s.phones(), "blank and twice: once");
        for (String n : new String[]{"C", "D", "E", "F", "G"}) s.add(n);
        assertEquals(ObserveSet.MAX, s.size(), "six at most");
        assertFalse(s.contains("G"));
        assertEquals("MobileAlpha,MobileBravo,C,D,E,F", s.encode());
        assertEquals(s.phones(), ObserveSet.parse(s.encode()).phones());
    }

    @Test
    void theSelectedOrEnlargedTileAsksForTheSharpPicture() {
        ObserveSet s = ObserveSet.parse("A,B,C");
        assertFalse(s.wantsSharp(0), "the grid's picture until one is picked");
        s.select(1);
        assertTrue(s.wantsSharp(1));
        assertFalse(s.wantsSharp(0));
        s.select(1);
        assertFalse(s.wantsSharp(1), "a second click: none");
        s.toggleEnlarged(2);
        assertTrue(s.wantsSharp(2));
        assertEquals(2, s.selected(), "enlarged is selected too");
        s.toggleEnlarged(2);
        assertEquals(-1, s.enlarged());
        s.select(0);
        s.remove("A");
        assertEquals(-1, s.selected(), "the selected one left");
        s.toggleEnlarged(1);
        s.remove("B");
        assertEquals(0, s.enlarged(), "the tiles after it move up");
    }

    @Test
    void idlePhonesOnly() {
        Names.Position flown = new Names.Position(true, 59.9, 10.7, 1, false, true, "100.64.0.9");
        Names.Position idle = new Names.Position(true, 59.9, 10.7, 1, false, false, "");
        assertNotNull(ObserveSet.refusal(flown, "2.3.0-pc.37"), "another PC flies it");
        assertNull(ObserveSet.refusal(idle, "2.3.0-pc.37"));
        assertNull(ObserveSet.refusal(null, "2.3.0-pc.38"), "no word of where it is: it may try");
        assertNotNull(ObserveSet.refusal(idle, "2.3.0-pc.36"), "an app before observation");
        assertNull(ObserveSet.refusal(idle, ""), "version unknown (asleep): it may try");
    }

    // ------------------------------------------------------------------ the session

    /** A feed that writes what happens to it into a shared log. */
    private static final class FakeFeed implements ObservationSession.Feed {
        final String phone;
        final List<String> log;
        Boolean sharp;

        FakeFeed(String phone, List<String> log) {
            this.phone = phone;
            this.log = log;
        }

        @Override
        public String phone() {
            return phone;
        }

        @Override
        public void setSharp(boolean on) {
            sharp = on;
        }

        @Override
        public void stopStream() {
            log.add("stop " + phone);
        }

        @Override
        public void release() {
            synchronized (log) {
                log.add("release " + phone);
            }
        }
    }

    @Test
    void selectionAsksTheTilesPhoneForTheSharpPicture() {
        List<String> log = new ArrayList<>();
        ObserveSet set = ObserveSet.parse("A,B,C");
        List<FakeFeed> feeds = List.of(new FakeFeed("A", log), new FakeFeed("B", log), new FakeFeed("C", log));
        ObservationSession o = new ObservationSession(set, new ArrayList<>(feeds), null);
        for (FakeFeed f : feeds) assertEquals(Boolean.FALSE, f.sharp, "the grid's picture to begin with");
        o.select(1);
        assertTrue(feeds.get(1).sharp);
        assertFalse(feeds.get(0).sharp);
        o.toggleEnlarged(2);
        assertTrue(feeds.get(2).sharp);
        assertFalse(feeds.get(1).sharp, "one sharp picture at a time");
        o.toggleEnlarged(2);
        assertTrue(feeds.get(2).sharp, "back to the grid: still the selected one");
    }

    @Test
    void connectStopsEveryStreamFirst() throws Exception {
        List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());
        ObserveSet set = ObserveSet.parse("A,B,C");
        List<ObservationSession.Feed> feeds = List.of(new FakeFeed("A", log), new FakeFeed("B", log), new FakeFeed("C", log));
        ObservationSession o = new ObservationSession(set, feeds, () -> log.add("sound off"));
        assertEquals("B", o.connect(1));
        assertEquals(List.of("sound off", "stop A", "stop C", "stop B"), log.subList(0, 4),
                "the sound and the other streams first, the chosen phone's own last - all before connect returns");
        assertTrue(o.isStopped());
        assertNull(o.connect(0), "once");
        for (int i = 0; i < 100 && log.size() < 7; i++) Thread.sleep(10);
        assertTrue(log.containsAll(List.of("release A", "release B", "release C")), "the decoders let go afterwards");
    }

    @Test
    void aPhoneThatGetsAPilotLeavesTheGrid() {
        List<String> log = new ArrayList<>();
        ObserveSet set = ObserveSet.parse("A,B,C");
        List<ObservationSession.Feed> feeds = new ArrayList<>(List.of(new FakeFeed("A", log), new FakeFeed("B", log), new FakeFeed("C", log)));
        ObservationSession o = new ObservationSession(set, feeds, null);
        o.select(2);
        assertTrue(o.remove("B"));
        assertEquals(List.of("A", "C"), set.phones());
        assertEquals(1, set.selected(), "C moved up and stays selected");
        assertEquals(2, o.feeds().size());
        assertTrue(log.contains("stop B"));
        assertFalse(o.remove("B"));
    }

    // ------------------------------------------------------------------ the heading

    private static Observe.Position at(int flags, double fc, double phone, int accuracy, int error) {
        return new Observe.Position(flags | Observe.POS_VALID, 59.9, 10.7, 100, 0, fc, phone, accuracy, error, 70);
    }

    @Test
    void theFlightControllersCompassFirst() {
        HeadingChoice.Heading h = HeadingChoice.choose(at(Observe.FC_COMPASS, 90, 100, 3, 5), true);
        assertEquals(HeadingChoice.Source.FC_COMPASS, h.source());
        assertEquals(90, h.deg());
        assertFalse(h.approximate());
        assertTrue(HeadingChoice.choose(at(Observe.FC_COMPASS, 90, 100, 3, 5), false).approximate(),
                "approximate until the compass is known calibrated");
    }

    @Test
    void elseThePhonesSensor() {
        HeadingChoice.Heading h = HeadingChoice.choose(at(0, 90, 100, 3, 5), true);
        assertEquals(HeadingChoice.Source.PHONE, h.source(), "an FC yaw without a compass is no heading");
        assertEquals(100, h.deg());
        assertFalse(h.approximate());
        assertTrue(HeadingChoice.choose(at(0, Double.NaN, 100, 1, 5), true).approximate(), "low accuracy");
        assertTrue(HeadingChoice.choose(at(0, Double.NaN, 100, -1, -1), true).approximate(), "accuracy unknown");
        assertTrue(HeadingChoice.choose(at(0, Double.NaN, 100, 3, 40), true).approximate(), "a large error estimate");
        assertEquals(HeadingChoice.Source.PHONE, HeadingChoice.choose(at(Observe.FC_COMPASS, Double.NaN, 100, 2, -1), true).source(),
                "a compass whose reading is stale: the phone's");
    }

    @Test
    void noHeadingAtAll() {
        assertEquals(HeadingChoice.Source.NONE, HeadingChoice.choose(at(0, Double.NaN, Double.NaN, 3, 1), true).source());
        assertEquals(HeadingChoice.Heading.NONE, HeadingChoice.choose(null, true));
    }

    @Test
    void theConeFansOutAlongTheHeading() {
        List<double[]> edge = ObservedCone.edge(60, 10, 100, 90, 60, 0, 16 / 9.0);
        assertEquals(ObservedCone.RAYS, edge.size());
        double[] mid = edge.get(ObservedCone.RAYS / 2);
        assertEquals(60, mid[0], 1e-4, "straight east: the same latitude");
        assertTrue(mid[1] > 10, "east of the phone");
        double eastM = (mid[1] - 10) * 111_320 * Math.cos(Math.toRadians(60));
        assertEquals(ObservedCone.RANGE_M, eastM, 5, "a level camera sees no ground: the cone's reach");
        assertTrue(edge.get(0)[0] > 60 && edge.get(ObservedCone.RAYS - 1)[0] < 60, "left edge north of it, right edge south");
        List<double[]> down = ObservedCone.edge(60, 10, 100, 90, 60, -30, 16 / 9.0);
        double nearM = (down.get(ObservedCone.RAYS / 2)[1] - 10) * 111_320 * Math.cos(Math.toRadians(60));
        assertTrue(nearM < 10, "tilted down 30 degrees from 1.5 m: the ground a few metres off (" + nearM + ")");
        assertTrue(ObservedCone.edge(60, 10, 100, Double.NaN, 60, 0, 1.7).isEmpty(), "no heading: no cone");
    }
}
