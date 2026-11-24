package de.droiddrone.pc.launch;

import de.droiddrone.common.ArduLinkLossRule;
import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.FleetIntent;
import de.droiddrone.common.group.FollowerBrain;
import de.droiddrone.common.group.FollowerRule;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.group.GroupOrder;
import de.droiddrone.common.launch.LaunchIntent;
import de.droiddrone.common.launch.LaunchSequence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static de.droiddrone.pc.launch.LaunchSequenceTest.order;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A reserve the group has taken: the group's orders move its launch on (at the group, in transit, IN GROUP in its cell);
 * before, nothing on the phone did, it stayed ON THE WAY while armed and the launch's own link-loss rule (LOITER after
 * 12 s, RTL after 2 min) ran beside the follower's. Cut off, a joined reserve does exactly what any follower does.
 */
class ReserveJoinsTest {
    static final double LAT = 59.9, LON = 10.7;

    static GroupOrder groupOrder(int seq) {
        return new GroupOrder(0, seq, LAT, LON, 0, 0, 10, 10, 160, 140, 8, 0, 0, 0, 0, 0);
    }

    /** Up to ON THE WAY (LaunchSequenceTest's phone: climbs to its approach layer). */
    static LaunchSequenceTest.Phone onTheWay() {
        LaunchSequenceTest.Phone p = new LaunchSequenceTest.Phone(LaunchChecksTest.mark());
        p.take(p.seq.order(order(7, 11), p.t));
        p.seq.fcReady(true);
        p.run(40_000, false, null);
        assertEquals(LaunchSequence.ON_THE_WAY, p.seq.state());
        return p;
    }

    @Test
    void theGroupsOrdersMoveTheLaunchOnToInGroup() {
        LaunchSequenceTest.Phone p = onTheWay();
        GroupOrder o = groupOrder(1);
        double[] cell = FollowerRule.place(o, 0);
        List<LaunchIntent> in = p.seq.followerOrder(p.t, 120, 30);
        assertEquals(LaunchSequence.IN_TRANSIT, p.seq.state(), "at the group and in transit at the group's first order");
        long reports = in.stream().filter(i -> i.type == LaunchIntent.REPORT).count();
        assertEquals(2, reports, "WAITING FOR TRANSIT and IN TRANSIT told the PC");
        p.seq.followerOrder(p.t + 200, 10, 0);
        assertEquals(LaunchSequence.IN_TRANSIT, p.seq.state(), "10 m off its cell: still on its way in");
        p.seq.followerOrder(p.t + 400, 2.5, -1.5);
        assertEquals(LaunchSequence.IN_GROUP, p.seq.state(), "in its cell: IN GROUP");
        assertTrue(p.seq.finished());
        assertFalse(LaunchSequence.keepsGcsAlive(p.seq.state()), "a follower now: its brain keeps the ground station");
        p.seq.followerOrder(p.t + 600, Double.NaN, Double.NaN);
        assertEquals(LaunchSequence.IN_GROUP, p.seq.state());
        assertTrue(GeoLocal.distance(LAT, LON, cell[0], cell[1]) > 10, "the cell is the order's own place");
        // not on its way yet (still climbing): an order changes nothing
        LaunchSequenceTest.Phone c = new LaunchSequenceTest.Phone(LaunchChecksTest.mark());
        c.take(c.seq.order(order(7, 12), c.t));
        c.seq.fcReady(true);
        c.run(9000, false, null);
        assertEquals(LaunchSequence.CLIMBING, c.seq.state());
        assertTrue(c.seq.followerOrder(c.t, 0, 0).isEmpty());
        assertEquals(LaunchSequence.CLIMBING, c.seq.state());
    }

    @Test
    void theLaunchRuleStandsAsideForAFollower() {
        for (int s = LaunchSequence.IDLE; s <= LaunchSequence.LAST_STATE; s++) {
            assertFalse(LaunchSequence.launchRuleApplies(s, true), LaunchSequence.stateWord(s));
            assertEquals(LaunchSequence.linkLossApplies(s), LaunchSequence.launchRuleApplies(s, false));
        }
    }

    /**
     * Cut off after joining, as the phone's ArduLinkLoss and FleetRole run it ten times a second: only the follower's
     * brain acts - LOITER in its cell after 1 s, its exit plan after 2 min - and the launch rule never says a word.
     */
    @Test
    void aJoinedReserveCutOffDoesWhatAnyFollowerDoes() {
        LaunchSequenceTest.Phone p = onTheWay();
        FollowerBrain joined = new FollowerBrain(1000), plain = new FollowerBrain(1000);
        ExitPlan plan = new ExitPlan(0, 3, 100, 0, 90, LAT + 0.001, LON, 160);
        long t = p.t;
        for (FollowerBrain b : List.of(joined, plain)) {
            b.order(groupOrder(1), t);
            b.plan(plan);
        }
        p.seq.followerOrder(t, 1, 0); // the group's order: IN TRANSIT, then in its cell
        p.seq.followerOrder(t, 1, 0);
        assertEquals(LaunchSequence.IN_GROUP, p.seq.state());
        ArduLinkLossRule launchRule = new ArduLinkLossRule(LaunchSequence.LOST_BRAKE_AFTER_MS, LaunchSequence.LOST_HOME_AFTER_MS);
        long lastPc = t;
        List<Integer> joinedKinds = new ArrayList<>(), plainKinds = new ArrayList<>();
        double[] cell = FollowerRule.place(groupOrder(1), 0);
        for (long now = t + 100; now < t + 200_000; now += 100) {
            boolean follower = true; // the brain stays: it was given a group order
            boolean applies = LaunchSequence.launchRuleApplies(p.seq.state(), follower);
            int a = launchRule.tick(now, applies ? lastPc : now, true, 5, true, true);
            assertEquals(ArduLinkLossRule.NONE, a, "the launch rule never acts on a follower, at " + (now - t));
            FleetIntent j = joined.tick(now, lastPc, cell[0], cell[1], 80, true), q = plain.tick(now, lastPc, cell[0], cell[1], 80, true);
            assertEquals(q.kind, j.kind, "the same as a follower that never launched, at " + (now - t));
            if (joinedKinds.isEmpty() || joinedKinds.get(joinedKinds.size() - 1) != j.kind) joinedKinds.add(j.kind);
            if (plainKinds.isEmpty() || plainKinds.get(plainKinds.size() - 1) != q.kind) plainKinds.add(q.kind);
        }
        assertEquals(plainKinds, joinedKinds);
        assertTrue(joinedKinds.contains(FleetIntent.LOITER), "it holds: " + joinedKinds);
        assertTrue(joinedKinds.indexOf(FleetIntent.LOITER) < joinedKinds.size() - 1, "then its exit plan: " + joinedKinds);
    }
}
