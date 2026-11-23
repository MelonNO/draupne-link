package de.droiddrone.pc.air;

import de.droiddrone.common.group.ExitPlan;
import de.droiddrone.common.group.FleetIntent;
import de.droiddrone.common.group.FollowerBrain;
import de.droiddrone.common.group.FollowerRule;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.common.group.GroupOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The follower's own rules on its phone (PRD FL-2, FL-3, FL-13, AC-25, AC-33, AC-37, AC-42): the order's wire form, the
 * GUIDED target, and what it does with the PC silent or GNSS gone. Places are made up.
 */
class FollowerRulesTest {
    static final double LAT = 60, LON = 10;

    static GroupOrder order(int flags, double velN, double offE, double offN, double alt, double floor) {
        double[] lead = GeoLocal.offset(LAT, LON, 0, 150);
        return new GroupOrder(flags | GroupOrder.FLAG_LEAD_KNOWN, 7, LAT, LON, 0, velN, offE, offN, alt, floor, 10, lead[0], lead[1], 190, 0, 0);
    }

    static ExitPlan plan(boolean bottom) {
        double[] rally = GeoLocal.offset(LAT, LON, -500, 0);
        return new ExitPlan(bottom ? ExitPlan.FLAG_BOTTOM_ROW : 0, 3, 100, 0, 90, rally[0], rally[1], 160);
    }

    @Test
    void theOrderAndThePlanGoOverTheWireAndBack() {
        GroupOrder o = new GroupOrder(GroupOrder.FLAG_BOTTOM_ROW | GroupOrder.FLAG_LEAD_KNOWN, 70000, 59.1234567, 10.7654321, -3.21, 4.56,
                -20, 20, 160.25, 140.5, 15, 59.125, 10.766, 185.5, 1.5, -2.25);
        GroupOrder b = GroupOrder.decode(o.encode(), 0);
        assertEquals(GroupOrder.BYTES, o.encode().length);
        assertEquals(70000 & 0xffff, b.seq);
        assertTrue(b.bottomRow() && b.leadKnown() && !b.stopped() && !b.land());
        assertEquals(59.1234567, b.centreLat, 1e-7);
        assertEquals(-3.21, b.velE, 0.01);
        assertEquals(-20, b.offE, 0.1);
        assertEquals(160.25, b.altAmsl, 0.01);
        assertEquals(140.5, b.floorAmsl, 0.01);
        assertEquals(-2.25, b.leadVelN, 0.01);
        assertNull(GroupOrder.decode(new byte[10], 0), "too short");
        byte[] other = o.encode();
        other[0] = 9;
        assertNull(GroupOrder.decode(other, 0), "another version");

        ExitPlan p = new ExitPlan(ExitPlan.FLAG_BOTTOM_ROW, 5, 137.5, 30, 92.5, 59.2, 10.8, 151.25);
        ExitPlan q = ExitPlan.decode(p.encode(), 0);
        assertEquals(ExitPlan.BYTES, p.encode().length);
        assertTrue(q.bottomRow());
        assertEquals(137.5, q.laneEndRadiusM, 0.05);
        assertEquals(30, q.startDelayS, 0.05);
        assertEquals(92.5, q.returnAltRelM, 0.01);
        assertEquals(59.2, q.rallyLat, 1e-7);
        assertEquals(151.25, q.layerAltAmsl, 0.01);
    }

    @Test
    void theTargetIsTheCellCarriedOnWithTheGroupNeverUnderTheFloorNoseAtTheLead() {
        GroupOrder o = order(0, 10, 0, 20, 150, 140);
        FleetIntent i = FollowerRule.target(o, 1000);
        assertEquals(FleetIntent.GUIDED, i.kind);
        assertEquals(30, GeoLocal.north(LAT, LON, i.lat, i.lon), 0.01, "cell 20 m north plus 1 s at 10 m/s");
        assertEquals(10, i.velN, 1e-9, "the group's velocity as a feed-forward");
        assertEquals(0, i.yawDeg, 0.5, "the lead is due north");
        assertEquals(20 + 15, GeoLocal.north(LAT, LON, FollowerRule.place(o, 5000)[0], LON), 0.01, "carried 1.5 s at most");
        assertEquals(0, FollowerRule.target(o, 5000).velN, 1e-9, "no feed-forward past it: it stops where the group would be");
        FleetIntent low = FollowerRule.target(order(0, 0, 0, 20, 120, 140), 0);
        assertEquals(140, low.altAmsl, 1e-9, "never under the floor (FL-3)");
        assertEquals(20, GeoLocal.north(LAT, LON, FollowerRule.target(order(GroupOrder.FLAG_STOPPED, 10, 0, 20, 150, 140), 1000).lat, LON), 0.01,
                "a stopped group is not carried");
    }

    @Test
    void cutOffItHoldsTwoMinutesThenFliesItsLaneOutAndGoesHomeAtItsReturnHeight() {
        FollowerBrain b = new FollowerBrain(1000);
        b.order(order(0, 0, 0, 20, 160, 140), 0);
        b.plan(plan(false));
        double[] cell = GeoLocal.offset(LAT, LON, 0, 20);
        assertEquals(FleetIntent.GUIDED, b.tick(500, 400, cell[0], cell[1], 80, true).kind);
        assertEquals(FleetIntent.LOITER, b.tick(1500, 400, cell[0], cell[1], 80, true).kind, "the PC silent 1 s: LOITER in its cell");
        assertEquals(FleetIntent.LOITER, b.tick(100_000, 400, cell[0], cell[1], 80, true).kind);
        FleetIntent out = b.tick(400 + 120_000, 400, cell[0], cell[1], 80, true);
        assertEquals(FleetIntent.GUIDED, out.kind, "after the 2 min: its lane");
        assertEquals(100, GeoLocal.north(LAT, LON, out.lat, out.lon), 0.01, "straight out from the centre through its cell");
        assertEquals(0, GeoLocal.east(LAT, LON, out.lat, out.lon), 0.01);
        assertEquals(160, out.altAmsl, 1e-9, "in its own layer");
        assertEquals(FleetIntent.GUIDED, b.tick(130_000, 125_000, cell[0], cell[1], 80, true).kind, "the PC back: it keeps going");
        double[] end = GeoLocal.offset(LAT, LON, 0, 100);
        FleetIntent home = b.tick(140_000, 125_000, end[0], end[1], 80, true);
        assertEquals(FleetIntent.RTL, home.kind);
        assertEquals(90, home.rtlAltRelM, 1e-9);
    }

    @Test
    void backWithinTheHoldItStaysAndFollows() {
        FollowerBrain b = new FollowerBrain(1000);
        b.order(order(0, 0, 0, 20, 160, 140), 0);
        b.plan(plan(false));
        double[] cell = GeoLocal.offset(LAT, LON, 0, 20);
        assertEquals(FleetIntent.LOITER, b.tick(5_000, 0, cell[0], cell[1], 80, true).kind);
        b.order(order(0, 0, 0, 20, 160, 140), 60_000);
        assertEquals(FleetIntent.GUIDED, b.tick(60_100, 60_000, cell[0], cell[1], 80, true).kind);
        assertEquals(FollowerBrain.NORMAL, b.phase());
    }

    @Test
    void atThreePercentTheBottomRowLandsStraightDownTheOthersFlyTheirLaneFirst() {
        double[] cell = GeoLocal.offset(LAT, LON, 0, 20);
        FollowerBrain bottom = new FollowerBrain(1000);
        bottom.order(order(GroupOrder.FLAG_BOTTOM_ROW, 0, 0, 20, 150, 140), 0);
        bottom.plan(plan(true));
        assertEquals(FleetIntent.LOITER, bottom.tick(2000, 0, cell[0], cell[1], 50, true).kind);
        assertEquals(FleetIntent.LAND, bottom.tick(3000, 0, cell[0], cell[1], 3, true).kind);

        FollowerBrain upper = new FollowerBrain(1000);
        upper.order(order(0, 0, 0, 20, 170, 140), 0);
        upper.plan(plan(false));
        assertEquals(FleetIntent.LOITER, upper.tick(2000, 0, cell[0], cell[1], 50, true).kind);
        assertEquals(FleetIntent.GUIDED, upper.tick(3000, 0, cell[0], cell[1], 3, true).kind, "out along its lane at once");
        double[] end = GeoLocal.offset(LAT, LON, 0, 100);
        assertEquals(FleetIntent.LAND, upper.tick(9000, 0, end[0], end[1], 2.5, true).kind, "and lands at its end");

        FollowerBrain following = new FollowerBrain(1000);
        following.order(order(GroupOrder.FLAG_BOTTOM_ROW, 0, 0, 20, 150, 140), 0);
        assertEquals(FleetIntent.LAND, following.tick(100, 0, cell[0], cell[1], 3, true).kind, "a bottom-row drone at 3 % while following (AC-33)");
    }

    @Test
    void withoutGnssItHoldsItsHeightAndOnlyThePcsLandOrderOrThreePercentBringItDown() {
        double[] cell = GeoLocal.offset(LAT, LON, 0, 20);
        FollowerBrain b = new FollowerBrain(1000);
        b.order(order(0, 0, 0, 20, 160, 140), 0);
        b.plan(plan(false));
        assertEquals(FleetIntent.ALT_HOLD, b.tick(100, 0, cell[0], cell[1], 80, false).kind);
        assertEquals(FleetIntent.ALT_HOLD, b.tick(200_000, 0, cell[0], cell[1], 80, false).kind, "link gone too: still holds, no plan to fly");
        assertEquals(FleetIntent.LAND, b.tick(200_100, 0, cell[0], cell[1], 3, false).kind);

        FollowerBrain c = new FollowerBrain(1000);
        c.order(order(GroupOrder.FLAG_LAND, 0, 0, 20, 160, 140), 0);
        assertEquals(FleetIntent.LAND, c.tick(100, 0, cell[0], cell[1], 80, false).kind, "AC-20: the PC lands it column by column");
        FollowerBrain d = new FollowerBrain(1000);
        d.order(order(0, 0, 0, 20, 160, 140), 0);
        assertEquals(FleetIntent.ALT_HOLD, d.tick(100, 0, cell[0], cell[1], 80, false).kind);
        assertEquals(FleetIntent.GUIDED, d.tick(200, 150, cell[0], cell[1], 80, true).kind, "GNSS back: it follows the PC again");
    }

    @Test
    void noPlanEverMeansItHoldsRatherThanGuess() {
        double[] cell = GeoLocal.offset(LAT, LON, 0, 20);
        FollowerBrain b = new FollowerBrain(1000);
        b.order(order(0, 0, 0, 20, 160, 140), 0);
        assertEquals(FleetIntent.LOITER, b.tick(500_000, 0, cell[0], cell[1], 80, true).kind);
        b.recall();
        assertEquals(FollowerBrain.NORMAL, b.phase());
    }

    @Test
    void aPlanUpToARefreshOldStillPointsAwayFromWhereTheGroupIs() {
        // the group moved 50 m north since the plan was made; the drone left from its cell: its lane is from the centre
        // its last order says, through where it is - not a fixed point of the old place
        FollowerBrain b = new FollowerBrain(1000);
        b.order(order(0, 10, 20, 0, 160, 140), 0); // east cell, group moving north at 10 m/s
        b.plan(plan(false));
        double[] at = GeoLocal.offset(LAT, LON, 20, 10); // braked 10 m on
        b.tick(2500, 1000, at[0], at[1], 80, true); // silent 1.5 s: holds
        FleetIntent out = b.tick(1000 + 120_000, 1000, at[0], at[1], 80, true);
        double e = GeoLocal.east(LAT, LON, out.lat, out.lon), n = GeoLocal.north(LAT, LON, out.lat, out.lon);
        assertEquals(100, Math.hypot(e, n - 10), 0.5, "100 m from where the centre was when it went silent");
        assertTrue(e > 95, "east, away from the group");
    }
}
