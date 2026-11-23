package de.droiddrone.pc.launch;

import de.droiddrone.common.launch.LaunchSequence;
import de.droiddrone.common.launch.LaunchWire;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AL-5 / AL-6 / AL-17 / AL-20 / AL-21 (FA9, FA10, FA17, FA20) on the PC: which reserve a press sends, the
 * queue, its cancel rules, the next call after a failure, the OSD list and the mini-views. Names and places made up.
 */
class ReserveQueueTest {
    static final double GLAT = 60.0, GLON = 10.0;

    static double north(double m) {
        return GLAT + m / 111_195.0;
    }

    static ReserveChoice.Reserve reserve(String name, double metresAway, long waitMs) {
        return new ReserveChoice.Reserve(name, true, name.hashCode() | 1, north(metresAway), GLON, waitMs, 60, true);
    }

    /** Every drone its own approach layer (60, 70, 80 ...); columns free unless blocked. */
    static class Air implements LaunchAirspace {
        final Map<String, Double> layers = new HashMap<>();
        final Set<String> columns = new HashSet<>();
        final Set<String> blocked = new HashSet<>();
        double next = 60;

        @Override
        public double approachLayerFor(String drone, double lat, double lon) {
            return layers.computeIfAbsent(drone, d -> (next += 10) - 10);
        }

        @Override
        public boolean reserveColumn(String drone, double lat, double lon, double radiusM, double topM) {
            if (blocked.contains(drone)) return false;
            columns.add(drone);
            return true;
        }

        @Override
        public void releaseColumn(String drone) {
            columns.remove(drone);
        }

        @Override
        public void releaseLayer(String drone) {
            layers.remove(drone);
        }
    }

    final ReserveChoice choice = new ReserveChoice(ReserveChoice.Pace.DEFAULT);

    @Test
    void theFirstToArriveWinsNotTheNearest() {
        // near: 1 km but its check-in is 5 min away; far: 4 km and listening now
        ReserveChoice.Reserve near = reserve("Sentry-1", 1000, 300_000);
        ReserveChoice.Reserve far = reserve("Sentry-2", 4000, 0);
        ReserveChoice.Arrival a = choice.pick(List.of(near, far), Set.of(), GLAT, GLON).orElseThrow();
        assertEquals("Sentry-2", a.reserve().name());
        // 0 s wait + 20 s wake and arm + 60 m / 2.5 = 24 s climb + 4000 / 12 = 333 s
        assertEquals(377.3, a.seconds(), 0.5);
        // with the check-in due now the near one wins
        ReserveChoice.Reserve nearNow = reserve("Sentry-1", 1000, 10_000);
        assertEquals("Sentry-1", choice.pick(List.of(nearNow, far), Set.of(), GLAT, GLON).orElseThrow().reserve().name());
    }

    @Test
    void whoCountsAtAll() {
        assertTrue(choice.arrival(reserve("A", 9_900, 0), GLAT, GLON).isPresent());
        assertTrue(choice.arrival(reserve("B", 10_100, 0), GLAT, GLON).isEmpty(), "FA9: 10 km");
        assertTrue(choice.arrival(reserve("C", 100, -1), GLAT, GLON).isEmpty(), "check-in not known: not promised");
        assertTrue(choice.arrival(new ReserveChoice.Reserve("D", false, 1, GLAT, GLON, 0, 60, true), GLAT, GLON).isEmpty(), "unmarked");
        assertTrue(choice.arrival(new ReserveChoice.Reserve("E", true, 1, GLAT, GLON, 0, 60, false), GLAT, GLON).isEmpty(), "not ready");
        assertTrue(choice.arrival(new ReserveChoice.Reserve("F", true, 1, GLAT, GLON, 0, Double.NaN, true), GLAT, GLON).isEmpty(), "no layer");
        assertEquals(List.of("A"), choice.ranked(List.of(reserve("A", 9_900, 0), reserve("B", 10_100, 0)), GLAT, GLON)
                .stream().map(r -> r.reserve().name()).toList());
    }

    @Test
    void eachPressCallsOneMoreAndEachDroneAnswersOnce() {
        Air air = new Air();
        ReserveQueue q = new ReserveQueue(choice, air);
        q.setLead(4);
        List<ReserveChoice.Reserve> rs = List.of(reserve("Sentry-1", 800, 0), reserve("Sentry-2", 1500, 0), reserve("Sentry-3", 3000, 0));
        ReserveQueue.Out a = q.press(0, rs, GLAT, GLON);
        ReserveQueue.Out b = q.press(100, rs, GLAT, GLON);
        ReserveQueue.Out c = q.press(200, rs, GLAT, GLON);
        ReserveQueue.Out d = q.press(300, rs, GLAT, GLON);
        assertEquals(1, a.orders().size());
        assertEquals(List.of("CALLED Sentry-1"), a.events());
        assertEquals(List.of("CALLED Sentry-2"), b.events());
        assertEquals(List.of("CALLED Sentry-3"), c.events());
        assertEquals(List.of("NO RESERVE"), d.events());
        assertTrue(d.orders().isEmpty());
        assertEquals(60, a.orders().get(0).approachHeightM, 1e-9);
        assertEquals(70, b.orders().get(0).approachHeightM, 1e-9, "AC-5: each its own approach layer");
        assertEquals(4, a.orders().get(0).leadSysId);
        assertEquals(40, a.orders().get(0).floorM, 1e-9);
        assertNotEquals(a.orders().get(0).callId, b.orders().get(0).callId);
        String line = q.osdLine(0);
        assertTrue(line.startsWith("+3 RESERVES:  Sentry-1 "), line);
        assertTrue(line.indexOf("Sentry-1") < line.indexOf("Sentry-2") && line.indexOf("Sentry-2") < line.indexOf("Sentry-3"), "arrival order");
        assertEquals("NO RESERVE", new ReserveQueue(choice, air).press(0, List.of(), GLAT, GLON).events().get(0));
    }

    @Test
    void theOsdLineCountsDown() {
        ReserveQueue q = new ReserveQueue(choice, new Air());
        q.press(0, List.of(reserve("Sentry-3", 0, 0)), GLAT, GLON); // 20 s + 24 s = 44 s
        assertEquals("+1 RESERVE:  Sentry-3 0:44", q.osdLine(0));
        assertEquals("+1 RESERVE:  Sentry-3 0:14", q.osdLine(30_000));
        assertEquals("1:20", ReserveQueue.mmss(80_000));
        ReserveQueue none = new ReserveQueue(choice, new Air());
        none.press(1000, List.of(), GLAT, GLON);
        assertEquals("NO RESERVE", none.osdLine(2000));
        assertEquals("", none.osdLine(1000 + ReserveQueue.NO_RESERVE_MS + 1));
    }

    /** Every call over (in the group): the OSD's reserve line is empty. Before, it said NO RESERVE for good (an overflow). */
    @Test
    void noReserveLineOnceEveryCallIsOver() {
        ReserveQueue q = new ReserveQueue(choice, new Air());
        long now = 1_800_000_000_000L; // the PC's clock
        int id = q.press(now, List.of(reserve("Sentry-3", 0, 0)), GLAT, GLON).orders().get(0).callId;
        q.onStatus(now + 60_000, "Sentry-3", status(id, LaunchSequence.IN_GROUP), List.of(), GLAT, GLON);
        assertEquals("", q.osdLine(now + 61_000));
    }

    /** AL-5: a call cancelled before its drone armed leaves the drone marked - the next press may call it again. */
    @Test
    void aCancelledReserveCanAnswerALaterCall() {
        ReserveQueue q = new ReserveQueue(choice, new Air());
        List<ReserveChoice.Reserve> rs = List.of(reserve("Sentry-1", 500, 0));
        q.press(0, rs, GLAT, GLON);
        assertEquals(List.of("CANCELLED Sentry-1"), q.cancelLast(10).events());
        assertEquals(List.of("CALLED Sentry-1"), q.press(20, rs, GLAT, GLON).events());
    }

    @Test
    void theLastCallCanBeCancelledUntilItArms() {
        Air air = new Air();
        ReserveQueue q = new ReserveQueue(choice, air);
        List<ReserveChoice.Reserve> rs = List.of(reserve("Sentry-1", 500, 0), reserve("Sentry-2", 900, 0));
        int first = q.press(0, rs, GLAT, GLON).orders().get(0).callId; // call ids start at random
        LaunchWire.Order second = q.press(0, rs, GLAT, GLON).orders().get(0);
        assertTrue(q.canCancel());
        ReserveQueue.Out c = q.cancelLast(10);
        assertEquals(LaunchWire.KIND_CANCEL, c.orders().get(0).kind);
        assertEquals(second.callId, c.orders().get(0).callId);
        assertEquals(1, q.active().size());
        assertFalse(air.layers.containsKey("Sentry-2"), "its layer is free again");
        // the first one arms: no cancel now
        LaunchWire.Status s = status(first, LaunchSequence.ARMING);
        q.onStatus(20, "Sentry-1", s, rs, GLAT, GLON);
        assertFalse(q.canCancel());
        assertTrue(q.cancelLast(30).orders().isEmpty());
        assertEquals(1, q.active().size());
    }

    static LaunchWire.Status status(int callId, int state) {
        LaunchWire.Status s = new LaunchWire.Status();
        s.callId = callId;
        s.state = state;
        s.connected = true;
        return s;
    }

    /** A PC started again counts its calls from a new random point: an exact repeat of a call the phone still knows (it
     * answers one of the last minute with its status, never launching) was likely when every start began at 1. */
    @Test
    void callIdsStartAnewAtRandomOnEveryStart() {
        java.util.Set<Integer> firsts = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            ReserveQueue q = new ReserveQueue(choice, new Air());
            int id = q.press(0, List.of(reserve("Sentry-1", 500, 0)), GLAT, GLON).orders().get(0).callId;
            assertTrue(id >= 1 && id <= 65535);
            firsts.add(id);
        }
        assertTrue(firsts.size() > 15, "20 starts, " + firsts.size() + " first ids");
        java.util.Set<Integer> blocks = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) blocks.add(de.droiddrone.pc.route.RouteSender.randomId());
        assertTrue(blocks.size() > 15);
    }

    @Test
    void aFailedLaunchCallsTheNextAtOnce() {
        Air air = new Air();
        ReserveQueue q = new ReserveQueue(choice, air);
        List<ReserveChoice.Reserve> rs = List.of(reserve("Sentry-1", 500, 0), reserve("Sentry-2", 900, 0));
        int call = q.press(0, rs, GLAT, GLON).orders().get(0).callId;
        LaunchWire.Status s = status(call, LaunchSequence.LAUNCH_FAILED);
        s.reason = LaunchSequence.CLIMB_REASON + 3; // impact
        ReserveQueue.Out out = q.onStatus(5000, "Sentry-1", s, rs, GLAT, GLON);
        assertEquals("LAUNCH FAILED Sentry-1 - IMPACT", out.events().get(0));
        assertEquals("CALLED Sentry-2", out.events().get(1), "FA17: the next reserve");
        assertEquals(1, out.orders().size());
        assertFalse(air.columns.contains("Sentry-1"));
        // and Sentry-1 never answers again under that mark
        assertEquals(List.of("NO RESERVE"), q.press(6000, rs, GLAT, GLON).events());
        // marked again (a new id): callable again
        List<ReserveChoice.Reserve> again = List.of(new ReserveChoice.Reserve("Sentry-1", true, 99, north(500), GLON, 0, 60, true));
        assertEquals(List.of("CALLED Sentry-1"), q.press(7000, again, GLAT, GLON).events());
    }

    @Test
    void aBlockedColumnHoldsTheOrderBack() {
        Air air = new Air();
        air.blocked.add("Sentry-1");
        ReserveQueue q = new ReserveQueue(choice, air);
        List<ReserveChoice.Reserve> rs = List.of(reserve("Sentry-1", 500, 0));
        ReserveQueue.Out out = q.press(0, rs, GLAT, GLON);
        assertTrue(out.orders().isEmpty(), "AC-4: not before its column is clear");
        assertEquals("WAITING FOR COLUMN", q.active().get(0).word());
        assertTrue(q.tick(100).orders().isEmpty());
        air.blocked.clear();
        assertEquals(1, q.tick(200).orders().size());
        assertTrue(q.tick(300).orders().isEmpty(), "once");
        assertEquals("CALLED", q.active().get(0).word());
    }

    @Test
    void theColumnIsFreedAtTheFloorAndTheLayerInTheGroup() {
        Air air = new Air();
        ReserveQueue q = new ReserveQueue(choice, air);
        List<ReserveChoice.Reserve> rs = List.of(reserve("Sentry-1", 500, 0));
        int call = q.press(0, rs, GLAT, GLON).orders().get(0).callId;
        LaunchWire.Status s = status(call, LaunchSequence.CLIMBING);
        s.armed = true;
        q.onStatus(10, "Sentry-1", s, rs, GLAT, GLON);
        assertTrue(air.columns.contains("Sentry-1"));
        s.passedFloor = true;
        q.onStatus(20, "Sentry-1", s, rs, GLAT, GLON);
        assertFalse(air.columns.contains("Sentry-1"));
        assertTrue(air.layers.containsKey("Sentry-1"));
        assertEquals(List.of("RESERVE ARRIVED Sentry-1"), q.onStatus(30, "Sentry-1", status(call, LaunchSequence.IN_GROUP), rs, GLAT, GLON).events());
        assertFalse(air.layers.containsKey("Sentry-1"));
        assertTrue(q.active().isEmpty());
        assertTrue(q.onStatus(40, "Sentry-1", status(call + 1, LaunchSequence.LAUNCH_FAILED), rs, GLAT, GLON).events().isEmpty(),
                "a status for no call of ours is ignored");
    }

    @Test
    void callsOnTheirWayJoinWhoeverIsTheLeadNow() {
        ReserveQueue q = new ReserveQueue(choice, new Air());
        q.setLead(3);
        q.press(0, List.of(reserve("Sentry-1", 500, 0)), GLAT, GLON);
        q.setLead(9); // DISCARD LEAD while it climbs
        assertEquals(9, q.joinWhom());
    }

    @Test
    void reservesNeverShareAnApproachLayer() {
        LaunchAirspace same = new Air() {
            @Override
            public double approachLayerFor(String drone, double lat, double lon) {
                return 60;
            }
        };
        ReserveQueue q = new ReserveQueue(choice, same);
        List<ReserveChoice.Reserve> rs = List.of(reserve("Sentry-1", 500, 0), reserve("Sentry-2", 900, 0));
        assertEquals(1, q.press(0, rs, GLAT, GLON).orders().size());
        assertEquals(List.of("NO LAYER FREE"), q.press(0, rs, GLAT, GLON).events());
        LaunchAirspace low = new Air() {
            @Override
            public double approachLayerFor(String drone, double lat, double lon) {
                return 30;
            }
        };
        ReserveQueue q2 = new ReserveQueue(choice, low);
        assertEquals(List.of("NO LAYER FREE"), q2.press(0, rs, GLAT, GLON).events(), "never a layer under the floor");
    }

    @Test
    void miniViewsAppearOnlyOnceConnectedLeaveAtTheFloorAndFailuresStayTenSeconds() {
        ReserveQueue q = new ReserveQueue(choice, new Air());
        List<ReserveChoice.Reserve> rs = List.of(reserve("Sentry-1", 500, 0), reserve("Sentry-2", 900, 0));
        int c1 = q.press(0, rs, GLAT, GLON).orders().get(0).callId;
        int c2 = q.press(0, rs, GLAT, GLON).orders().get(0).callId;
        MiniViews mv = new MiniViews();
        assertTrue(mv.views(0, q.all(), 40).isEmpty(), "CALLED: only in the reserve list");
        LaunchWire.Status waking = status(c1, LaunchSequence.WAKING);
        waking.connected = false;
        q.onStatus(1000, "Sentry-1", waking, rs, GLAT, GLON);
        assertTrue(mv.views(1000, q.all(), 40).isEmpty(), "not before it woke and connected");
        LaunchWire.Status climbing = status(c1, LaunchSequence.CLIMBING);
        climbing.heightM = 10;
        q.onStatus(2000, "Sentry-1", climbing, rs, GLAT, GLON);
        LaunchWire.Status beeping = status(c2, LaunchSequence.BEEPING);
        q.onStatus(2000, "Sentry-2", beeping, rs, GLAT, GLON);
        List<MiniViews.View> v = mv.views(2000, q.all(), 40);
        assertEquals(2, v.size(), "stacked");
        assertEquals(0.25, v.get(0).barFill(), 1e-9);
        assertTrue(v.get(0).livePicture());
        climbing.passedFloor = true;
        climbing.heightM = 41;
        q.onStatus(3000, "Sentry-1", climbing, rs, GLAT, GLON);
        assertEquals(List.of("Sentry-2"), mv.views(3000, q.all(), 40).stream().map(MiniViews.View::name).toList(), "left at the floor");
        LaunchWire.Status failed = status(c2, LaunchSequence.NOT_LAUNCHED);
        failed.reason = 3; // MOVED
        q.onStatus(4000, "Sentry-2", failed, rs, GLAT, GLON);
        v = mv.views(4000, q.all(), 40);
        assertEquals(1, v.size());
        assertTrue(v.get(0).failed());
        assertEquals("NOT LAUNCHED - MOVED", v.get(0).word());
        assertFalse(v.get(0).livePicture(), "the last picture, frozen");
        assertTrue(mv.takeAlerts().isEmpty());
        assertEquals(1, mv.views(13_900, q.all(), 40).size());
        assertTrue(mv.views(14_000, q.all(), 40).isEmpty(), "10 s");
        assertEquals(List.of("Sentry-2: NOT LAUNCHED - MOVED"), mv.takeAlerts());
        assertTrue(mv.takeAlerts().isEmpty(), "given once");
        assertEquals(480, MiniViews.WIDTH);
    }

    @Test
    void whoMayBeMarked() {
        assertNull(MarkRules.refusal(true, true, false));
        assertNotNull(MarkRules.refusal(false, true, false), "not known landed");
        assertNotNull(MarkRules.refusal(true, false, false), "Betaflight");
        assertNotNull(MarkRules.refusal(true, true, true), "OUT OF ACTION");
        assertEquals("Can Sentry-1 climb straight up to 40 m with nothing above it?", MarkRules.question("Sentry-1", 40));
    }

    @Test
    void aWholeQueueAgainstPhones() {
        // three marked phones driven by LaunchSequence; the middle one is tipped over; FA17 brings the third
        Air air = new Air();
        ReserveQueue q = new ReserveQueue(choice, air);
        q.setLead(2);
        Map<String, LaunchSequenceTest.Phone> phones = new HashMap<>();
        List<ReserveChoice.Reserve> rs = new ArrayList<>();
        int i = 0;
        for (String n : List.of("Sentry-1", "Sentry-2", "Sentry-3")) {
            de.droiddrone.common.launch.LaunchMark m = new de.droiddrone.common.launch.LaunchMark(100 + i, LaunchChecksTest.LAT,
                    LaunchChecksTest.LON, 90, 0, 40);
            phones.put(n, new LaunchSequenceTest.Phone(m));
            rs.add(new ReserveChoice.Reserve(n, true, 100 + i, north(500 + 400 * i), GLON, 0, 60, true));
            i++;
        }
        List<LaunchWire.Order> orders = new ArrayList<>();
        orders.addAll(q.press(0, rs, GLAT, GLON).orders());
        orders.addAll(q.press(0, rs, GLAT, GLON).orders());
        assertEquals(2, orders.size());
        Map<Integer, String> byCall = new HashMap<>();
        for (ReserveQueue.Call c : q.all()) byCall.put(c.callId, c.name);
        for (long t = 0; t < 40_000; t += 50) {
            for (LaunchWire.Order o : orders) {
                String n = byCall.get(o.callId);
                LaunchSequenceTest.Phone p = phones.get(n);
                LaunchWire.Order wire = LaunchWire.decodeOrder(LaunchWire.encode(o), 0, LaunchWire.ORDER_SIZE);
                p.take(p.seq.order(wire, t));
                p.seq.fcReady(true);
            }
            orders.clear();
            for (Map.Entry<String, LaunchSequenceTest.Phone> e : phones.entrySet()) {
                LaunchSequenceTest.Phone p = e.getValue();
                int before = p.said.size();
                if (e.getKey().equals("Sentry-2")) {
                    p.t = t;
                    p.check.motion(t, 1, 0.1);
                    PreArmCheck_tipped(p, t);
                } else {
                    p.t = t - 50;
                    p.run(50, false, null);
                }
                for (int k = before; k < p.said.size(); k++) {
                    var in = p.said.get(k);
                    if (in.type != de.droiddrone.common.launch.LaunchIntent.REPORT) continue;
                    LaunchWire.Status s = LaunchWire.decodeStatus(LaunchWire.encode(in.status), 0, LaunchWire.STATUS_SIZE);
                    ReserveQueue.Out out = q.onStatus(t, e.getKey(), s, rs, GLAT, GLON);
                    orders.addAll(out.orders());
                    for (ReserveQueue.Call c : q.all()) byCall.put(c.callId, c.name);
                }
            }
        }
        assertTrue(phones.get("Sentry-1").armedEver());
        assertFalse(phones.get("Sentry-2").armedEver(), "tipped: never armed");
        assertTrue(phones.get("Sentry-3").armedEver(), "FA17 called it in Sentry-2's place");
        assertEquals(LaunchSequence.NOT_LAUNCHED, phones.get("Sentry-2").seq.state());
    }

    private static void PreArmCheck_tipped(LaunchSequenceTest.Phone p, long t) {
        de.droiddrone.common.launch.PreArmCheck.Reading r = LaunchChecksTest.level();
        r.rollDeg = 170;
        p.take(p.seq.tick(t, r, false, null));
    }
}
