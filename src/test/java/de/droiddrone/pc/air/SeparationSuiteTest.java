package de.droiddrone.pc.air;

import de.droiddrone.pc.air.virt.VirtualAirspace;
import de.droiddrone.pc.air.virt.VirtualCopter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRD AC-14 and AC-26, the separation suite: every case in the kinematic model, the phones' own rules and the PC's
 * group controller as they run, the closest approach measured every 0.1 s. Any pair inside the protection radius
 * sideways AND the layer spacing in height fails. The closest approach of each case is written to
 * build/air-separation.txt. Not tried on a drone; ArduPilot's own behaviour is modelled, not run (SITL comes later).
 */
class SeparationSuiteTest {
    private static final List<String> TABLE = new ArrayList<>();

    private static void record(String name, VirtualAirspace a) {
        TABLE.add(String.format(Locale.ROOT, "%-46s %s", name, Groups.describe(a)));
        assertTrue(a.separated(), name + ": " + Groups.describe(a) + "\nevents: " + a.events);
    }

    @AfterAll
    static void writeTable() throws Exception {
        Path out = Path.of("build", "air-separation.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, TABLE);
        TABLE.forEach(System.out::println);
    }

    static double leadAlt(VirtualAirspace a) {
        return a.copters.get("LEAD").u;
    }

    private static boolean has(VirtualAirspace a, String event) {
        return a.events.stream().anyMatch(e -> e.contains(event));
    }

    // ------------------------------------------------------------------ hand-overs

    @Test
    void discardLeadTheNewLeadLeavesByTheAbortLayerAndTheGapIsFilled() {
        VirtualAirspace a = Groups.group(20, 11);
        a.pilot = Groups.goTo(0, 100, leadAlt(a));
        double away = leadAlt(a);
        a.onNewLead = id -> a.pilot = Groups.goTo(-150, -300, away + 10);
        a.run(5);
        String next = a.gc.discardLead(a.now);
        assertNotNull(next);
        a.run(150);
        assertTrue(has(a, "NEW LEAD " + next), a.events.toString());
        assertEquals(next, a.leadId);
        assertTrue(a.outside.contains("LEAD"), "the old lead keeps its mode, outside the group (E3)");
        long bottom = a.gc.members().values().stream().filter(m -> m.place != null && m.place.layer() == 0).count();
        assertEquals(8, bottom, "the bottom row full again (E5)");
        record("hand-over (DISCARD LEAD)", a);
    }

    @Test
    void aLeadSilentThirtySecondsIsTakenOverByTheBottomRowsLowest() {
        VirtualAirspace a = Groups.group(20, 12);
        a.pilot = Groups.goTo(0, 100, leadAlt(a));
        a.onNewLead = id -> a.pilot = Groups.goTo(250, 0, leadAlt(a) + 30);
        a.run(5);
        a.setLink("LEAD", false);
        a.run(120);
        assertTrue(has(a, "LEAD TAKEN OVER"), a.events.toString());
        assertTrue(has(a, "NEW LEAD"));
        record("automatic takeover (lead silent 30 s)", a);
    }

    // ------------------------------------------------------------------ reserves

    @Test
    void oneToFiveReservesJoinFromOneSite() {
        for (int k = 1; k <= 5; k++) {
            VirtualAirspace a = Groups.group(12, 20 + k);
            a.pilot = Groups.goTo(0, 100, leadAlt(a));
            a.run(2);
            for (int i = 0; i < k; i++) a.launchReserve("R" + i, -400, i * 25.0, 95);
            a.run(260);
            for (int i = 0; i < k; i++) assertTrue(has(a, "IN GROUP R" + i), k + " reserves: " + a.events);
            record(k + " reserve(s) from one site", a);
        }
    }

    @Test
    void reservesJoinFromSeveralSitesAtOnce() {
        VirtualAirspace a = Groups.group(16, 30);
        a.pilot = Groups.goTo(0, 100, leadAlt(a));
        a.run(2);
        a.launchReserve("R0", -400, 0, 95);
        a.launchReserve("R1", 400, 0, 96);
        a.launchReserve("R2", 0, -500, 97);
        a.launchReserve("R3", 300, 300, 98);
        a.run(280);
        for (int i = 0; i < 4; i++) assertTrue(has(a, "IN GROUP R" + i), a.events.toString());
        record("4 reserves from 4 sites", a);
    }

    // ------------------------------------------------------------------ the lead and other drones

    @Test
    void theLeadFlownStraightThroughTheGroupMakesItGiveWay() {
        for (double speed : new double[]{8, 12}) {
            VirtualAirspace a = Groups.group(20, 40);
            double alt = a.gc.layerAmsl(1); // the middle layer's height: the worst one
            VirtualCopter lead = a.copters.get("LEAD");
            lead.e = 0;
            lead.n = 300;
            lead.u = alt;
            a.pilot = (t, l) -> new double[]{0, 300 - speed * t, alt};
            a.run(70);
            assertTrue(has(a, "TRAFFIC"));
            record("lead flown through the group at " + (int) speed + " m/s", a);
        }
    }

    @Test
    void convergingOnAnotherGroupTheGroupStopsShortOfIt() {
        VirtualAirspace a = Groups.group(12, 50);
        // another group's drones parked 400 m east in the same layers: the lead pulls this group towards them
        for (int i = 0; i < 8; i++) {
            V2 c = new HollowCube(a.params).cell(i);
            String id = "X" + i;
            a.add(id, 400 + c.e(), c.n(), a.gc.layerAmsl(i % 2));
            a.outside.add(id);
        }
        a.pilot = Groups.goTo(520, 0, a.gc.layerAmsl(4));
        a.run(120);
        double worstGap = Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            VirtualCopter x = a.copters.get("X" + i);
            worstGap = Math.min(worstGap, new HollowCube(a.params).outsideEdge(new V2(x.e, x.n).minus(a.gc.centre())));
        }
        assertTrue(worstGap >= a.params.radiusM(), "the group's edge stays clear of the other group: " + worstGap);
        record("groups converging", a);
    }

    // ------------------------------------------------------------------ links

    @Test
    void eachDronesLinkLostOnEachFaceTheGroupMovesAwayAndTheDroneLeavesAlone() {
        String[] who = new String[4];
        for (int face = 0; face < 4; face++) {
            VirtualAirspace a = Groups.group(20, 60 + face);
            a.pilot = Groups.goTo(0, 100, leadAlt(a));
            a.run(3);
            int cell = new int[]{1, 3, 5, 7}[face], layer = face % 2; // N, E, S, W middles, bottom row and the one over it
            String id = a.gc.members().values().stream().filter(m -> m.place != null && m.place.cell() == cell && m.place.layer() == layer)
                    .findFirst().orElseThrow().id;
            who[face] = id;
            a.setLink(id, false);
            a.run(760);
            assertTrue(has(a, "LINK LOST " + id) && has(a, "MOVE AWAY FROM " + id), a.events.toString());
            assertTrue(a.copters.get(id).landed, "it flew its plan home");
            record("link lost, " + "NESW".charAt(face) + " face (" + id + ")", a);
        }
    }

    @Test
    void aLinkBackWithinThirtySecondsTheDroneCarriesOn() {
        VirtualAirspace a = Groups.group(12, 65);
        a.pilot = Groups.goTo(0, 100, leadAlt(a));
        a.run(3);
        a.setLink("F03", false);
        a.run(20);
        a.setLink("F03", true);
        a.run(30);
        assertTrue(has(a, "LINK BACK F03"));
        assertEquals(GroupController.State.IN_CELL, a.gc.members().get("F03").state());
        record("link back within the wait", a);
    }

    @Test
    void thePcLostForOverTwoMinutesEveryFollowerLeavesAlongItsLaneWithPlansOfEveryAge() {
        for (double age : new double[]{0.2, 3, 6, 9.8}) {
            VirtualAirspace a = Groups.group(20, 70);
            a.pilot = Groups.goTo(0, 400, leadAlt(a)); // the group is following when the PC goes
            a.run(12);
            // wait for a refresh, then the plan's age
            String before = a.gc.plans().values().iterator().next().seq + "";
            int guard = 0;
            while ((a.gc.plans().values().iterator().next().seq + "").equals(before) && guard++ < 200) a.step();
            a.run(age);
            a.pcAlive = false;
            a.run(700);
            long down = a.copters.values().stream().filter(c -> c.landed).count();
            assertEquals(a.copters.size(), down, "everyone home and down");
            record(String.format(Locale.ROOT, "PC lost >2 min, plans %.1f s old", age), a);
        }
    }

    @Test
    void twoDronesAtThreePercentAtOnce() {
        VirtualAirspace a = Groups.group(20, 80);
        a.pilot = Groups.goTo(0, 100, leadAlt(a));
        a.run(3);
        List<String> upper = a.gc.members().values().stream().filter(m -> m.place != null && m.place.layer() == 2).map(m -> m.id).limit(2).toList();
        String bottom = a.gc.members().values().stream().filter(m -> m.place != null && m.place.layer() == 0).map(m -> m.id).findFirst().orElseThrow();
        for (String id : upper) a.copters.get(id).battery = 6.5;
        a.copters.get(bottom).battery = 3;
        a.run(300);
        for (String id : upper) assertTrue(a.copters.get(id).landed, id + " landed down a shaft");
        assertTrue(a.copters.get(bottom).landed);
        record("two above the bottom row (and one in it) at 3 %", a);
    }

    // ------------------------------------------------------------------ GNSS

    /**
     * AC-26 / AC-42: one drone loses GNSS in wind from each of eight directions, the group flying: it holds its height
     * and drifts; the group goes on clear of its estimate and its growing error circle - never downwind of where it
     * was, where the drone drifts to.
     */
    @Test
    void oneDroneLosesGnssInWindFromEachDirection() {
        String[] from = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int w = 0; w < 8; w++) {
            double b = Math.toRadians(45 * w); // the wind comes from this bearing: it blows the other way
            V2 wind = new V2(-Math.sin(b) * 6, -Math.cos(b) * 6);
            VirtualAirspace a = Groups.group(20, 90 + w);
            a.wind = wind;
            a.pilot = Groups.goTo(0, 100, leadAlt(a));
            a.run(3);
            String id = a.gc.members().values().stream().filter(m -> m.place != null && m.place.layer() == 1 && m.place.cell() == 3)
                    .findFirst().orElseThrow().id;
            VirtualCopter lost = a.copters.get(id);
            double alt0 = lost.u;
            lost.setGnss(false, a.t());
            a.run(90);
            assertTrue(has(a, "GNSS LOST " + id));
            assertEquals(alt0, lost.u, 0.5, "A5: it holds its height (wind from " + from[w] + ")");
            // AC-42: clear of its estimate plus its error circle by a protection radius outside the group's edge (the way
            // taken is the safest of 24 - not always upwind: see GroupController.driftEscape)
            GroupController.Member m = a.gc.members().get(id);
            double out = a.gc.cube().outsideEdge(lost.estimate().minus(a.gc.centre()));
            double err = Math.min(lost.errorM(a.t()), GroupController.GNSS_ERR_CAP_M);
            assertTrue(out >= a.gc.params().radiusM() + err, "AC-42: the group clear of it (wind from " + from[w] + "): "
                    + out + " m outside the edge, " + m.state());
            record("GNSS lost, one drone, wind from " + from[w], a);
        }
    }

    /** AC-42: its GNSS back, the drone rejoins through the transit areas and is in a cell again. */
    @Test
    void aDroneWhoseGnssComesBackRejoins() {
        VirtualAirspace a = Groups.group(12, 97);
        a.wind = new V2(-3, 2);
        a.pilot = Groups.goTo(0, 100, leadAlt(a));
        a.run(3);
        a.copters.get("F04").setGnss(false, a.t());
        a.run(60);
        a.copters.get("F04").setGnss(true, a.t());
        a.run(240);
        assertTrue(has(a, "GNSS BACK F04"), a.events.toString());
        assertTrue(has(a, "REJOIN F04"), a.events.toString());
        assertEquals(GroupController.State.IN_CELL, a.gc.members().get("F04").state(), "back in a cell");
        record("GNSS lost 60 s, then back: rejoins", a);
    }

    /**
     * AC-42: GNSS and link lost together - it can't fly its exit plan: it holds its height (centred sticks) until its
     * pack is at 3 %, then lands where it is; the group keeps clear of it all the way down.
     */
    @Test
    void gnssAndLinkLostTheDroneHoldsItsHeightAndLandsAtThreePercent() {
        VirtualAirspace a = Groups.group(12, 98);
        a.wind = new V2(2, 3);
        a.pilot = Groups.goTo(0, 100, leadAlt(a));
        a.run(3);
        VirtualCopter c = a.copters.get("F05");
        c.battery = 12;
        c.drainPerS = 0.2;
        double alt0 = c.u;
        c.setGnss(false, a.t());
        a.setLink("F05", false);
        double lowest = alt0;
        while (c.battery > 3.5) {
            a.run(1);
            lowest = Math.min(lowest, c.u);
        }
        assertEquals(alt0, lowest, 0.5, "it held its height till 3 %");
        a.run(200);
        assertTrue(c.landed, "down at 3 %");
        record("GNSS and link lost: holds, lands at 3 %", a);
    }

    /**
     * AC-12 / A5: drones with a layer of their own (no group: the lead, dispatched drones) lose GNSS together - each holds
     * its height on its barometer and drifts with the wind, and as they all drift alike they stay a layer apart; none is
     * moved sideways by the PC.
     */
    @Test
    void dronesOnLayersOfTheirOwnLoseGnssEachHoldsItsHeight() {
        AirParams p = AirParams.defaults();
        VirtualAirspace a = new VirtualAirspace(p, 0, 99);
        a.wind = new V2(4, -2);
        double[] alts = {60, 70, 80, 90};
        for (int i = 0; i < alts.length; i++) {
            String id = "S" + i;
            VirtualCopter c = a.add(id, i * 3, -i * 2, alts[i]); // nearly stacked: only their layers keep them apart
            c.homeAmsl = 0;
            a.outside.add(id);
        }
        a.run(2);
        for (int i = 0; i < alts.length; i++) a.copters.get("S" + i).setGnss(false, a.t());
        a.run(120);
        for (int i = 0; i < alts.length; i++) assertEquals(alts[i], a.copters.get("S" + i).u, 0.5, "S" + i + " held its height");
        record("drones on layers of their own lose GNSS (A5)", a);
    }

    /**
     * FL-8: cells 20 m apart in each layer, never under the protection radius + 5 m (AC-6); the lead flown slowly at the
     * group stays out of the keep-out zone round its edge (AC-34), and the group gives way to it as one: no follower
     * leaves its cell to dodge (AC-19).
     */
    @Test
    void theGroupGivesWayToTheLeadAsOneAndKeepsItOutOfTheKeepOutZone() {
        VirtualAirspace a = Groups.group(20, 41);
        AirParams p = a.gc.params();
        for (String x : a.gc.members().keySet()) {
            for (String y : a.gc.members().keySet()) {
                if (x.compareTo(y) >= 0 || a.gc.members().get(x).place.layer() != a.gc.members().get(y).place.layer()) continue;
                double[] cx = a.gc.cellPlace(x), cy = a.gc.cellPlace(y);
                double d = Math.hypot(cx[0] - cy[0], cx[1] - cy[1]);
                assertTrue(d >= 20 - 1e-6 && d >= p.radiusM() + 5, x + "-" + y + " cells " + d + " m apart");
            }
        }
        VirtualCopter lead = a.copters.get("LEAD");
        double alt = lead.u;
        lead.e = 0;
        lead.n = p.edgeM() + p.keepOutM() + 60;
        a.pilot = (t, l) -> new double[]{0, l.n - 3, alt}; // on at 3 m/s, well under the group's top speed
        double closest = Double.MAX_VALUE, worstCell = 0;
        for (int s = 0; s < 120; s++) {
            a.run(1);
            closest = Math.min(closest, a.gc.cube().outsideEdge(lead.pos().minus(a.gc.centre())));
            worstCell = Math.max(worstCell, Groups.worstCellError(a));
        }
        assertTrue(a.gc.centre().length() > 30, "the group gave way: " + a.gc.centre());
        assertTrue(closest >= p.keepOutM() - 5, "the lead kept out of the keep-out zone: " + closest + " m from the edge");
        assertTrue(worstCell < 6, "the group moved as one: a follower " + worstCell + " m off its cell");
        record("FL-8: the lead flown slowly at the group", a);
    }

    @Test
    void twoDronesLoseGnssTheGroupLandsColumnByColumnBottomFirst() {
        VirtualAirspace a = Groups.group(12, 95);
        a.wind = new V2(3, 1);
        a.pilot = Groups.goTo(0, 100, leadAlt(a));
        a.run(3);
        a.copters.get("F01").setGnss(false, a.t());
        a.copters.get("F02").setGnss(false, a.t());
        a.run(1200);
        assertTrue(has(a, "GNSS LOST FOR THE GROUP"));
        for (String id : a.gc.members().keySet()) assertTrue(a.copters.get(id).landed, id + " down");
        record("GNSS lost, two drones (group lands)", a);
    }

    // ------------------------------------------------------------------ moves

    @Test
    void everyTransitAreaBusyAndAFourthWaiting() {
        VirtualAirspace a = Groups.group(20, 100);
        a.pilot = Groups.goTo(0, 100, leadAlt(a));
        a.run(3);
        // turn the battery order upside down: the top layer must go to the bottom and the other way round
        for (GroupController.Member m : a.gc.members().values()) a.copters.get(m.id).battery = 30 + 10 * (2 - m.place.layer()) + m.place.cell() * 0.1;
        int most = 0;
        for (int s = 0; s < 4000; s++) {
            a.step();
            most = Math.max(most, a.gc.scheduler().shaftsInUse());
            if (s % 600 == 0 && s > 0) a.gc.members().values().forEach(m -> { }); // keep going
        }
        // the order check every 5 min is not waited for: the first check after the change is at the next enter/exit
        assertTrue(most <= 4, "never more than three from outside plus a hop: " + most);
        record("every transit area busy, a fourth waiting", a);
    }

    @Test
    void aDroneStoppingWhileTheGroupMovesStopsTheGroup() {
        VirtualAirspace a = Groups.group(20, 110);
        a.pilot = Groups.goTo(0, 600, leadAlt(a));
        a.run(15);
        double moving = a.gc.velocity().length();
        assertTrue(moving > 5);
        a.paused.add("F05");
        a.run(12);
        assertTrue(a.gc.velocity().length() < 0.2, "stopped for it (AC-40)");
        a.paused.remove("F05");
        a.run(60);
        assertTrue(a.gc.centre().n() > 300, "on again once it is normal");
        record("a drone stopping while the group moves", a);
    }

    @Test
    void hopsJoinsAndExitsWhileTheGroupFollowsTheLead() {
        VirtualAirspace a = Groups.group(16, 120);
        double alt = leadAlt(a) + 10;
        a.pilot = (t, l) -> new double[]{300 * Math.sin(t / 60), 250 + t * 2, alt}; // a slow wander, the group in tow
        a.onNewLead = id -> a.pilot = (t, l) -> new double[]{l.e + 200, l.n + 200, alt + 20};
        a.run(5);
        for (GroupController.Member m : a.gc.members().values()) a.copters.get(m.id).battery = 40 + 10 * (1 - m.place.layer()) + m.place.cell();
        a.launchReserve("R0", -300, 200, 99);
        a.run(30);
        a.gc.discardLead(a.now);
        a.run(240);
        assertTrue(has(a, "IN GROUP R0"), a.events.toString());
        assertTrue(has(a, "NEW LEAD"));
        record("hops, a join and an exit while following", a);
    }

    // ------------------------------------------------------------------ the phase-4 gate's pure parts

    @Test
    void gateACirclingLeadInsideTheLeashMovesNoFollower() {
        VirtualAirspace a = Groups.group(20, 130);
        double alt = leadAlt(a);
        a.pilot = (t, l) -> new double[]{118 * Math.sin(t / 9), 118 * Math.cos(t / 9), alt}; // flown about 113 m out: past the keep-out zone, inside the leash
        a.run(120);
        assertEquals(0, a.gc.centre().length(), 0.5, "the group stayed where it was");
        assertTrue(Groups.worstCellError(a) < 0.5);
        record("gate: circling lead inside the leash", a);
    }

    @Test
    void gateAHundredMetreSprintIsFollowedAndTheFloorIsHeld() {
        VirtualAirspace a = Groups.group(20, 140);
        a.pilot = Groups.goTo(0, 220, leadAlt(a) + 5); // 120 m past where it hovered: past the leash
        double floor = Groups.GROUND + a.params.floorM();
        double lowest = Double.MAX_VALUE;
        for (int s = 0; s < 600; s++) {
            a.step();
            for (String id : a.gc.members().keySet()) lowest = Math.min(lowest, a.copters.get(id).u);
        }
        assertEquals(100, a.copters.get("LEAD").pos().dist(a.gc.centre()), 2, "stopped the keeping distance from the lead");
        assertTrue(Groups.worstCellError(a) < 1.5);
        assertTrue(lowest >= floor + a.params.layerM() - 0.5, "followers held their layers over the floor: " + lowest);
        double raised = a.gc.setFloor(60);
        a.run(30);
        for (String id : a.gc.members().keySet()) assertTrue(a.copters.get(id).u >= Groups.GROUND + raised + a.params.layerM() - 0.5, "the floor raised live (FL-14)");
        assertEquals(10, a.gc.setFloor(3), "never under 10 m");
        record("gate: 100 m sprint, floor held and raised", a);
    }

    // ------------------------------------------------------------------ random cases

    /**
     * Random cases (AC-14 "random scenarios"): an event every 10 s for 5 min - the lead flown anywhere, links cut and back,
     * GNSS lost, packs draining fast, reserves called, DISCARD LEAD. Twelve seeds in the normal suite (about a second
     * each; seed 11 was the last of them to lose separation, fixed 2026-11-23); the long run (30 seeds, 10 min each)
     * under the night tag.
     */
    @Test
    void randomCasesSeeded() {
        for (long seed = 1; seed <= 12; seed++) randomCase(seed, 300);
    }

    /**
     * The nightly run (AC-14 "random scenarios run every night"): gradlew benchNight; AIR_SEEDS=N for how many (30),
     * seeds from 100, 10 min each; every seed is run and every one that lost separation is said.
     * <p>
     * 2026-11-23: seeds 100-189 all keep separation (AIR_SEEDS=90). The last five (112, 139, 147, 157, 160) and seed 11
     * were not near misses of the buffer but things the PC had wrong: a cut-off drone setting off on its plan a little
     * later than predicted; a drone landing with GNSS braking to a stop, not flying on; a GNSS-less drone's error circle
     * cut at 10 m; the lead's ADS-B hold let go the moment it stopped; one GNSS-less drone far off holding back the
     * group's landings; and whatever is under a drone coming down being taken as a layer clear of it.
     */
    @Tag("night")
    @Test
    void randomCasesNightly() {
        String env = System.getenv("AIR_SEEDS");
        int n = env != null && env.matches("[0-9]+") ? Integer.parseInt(env) : 30;
        List<String> lost = new ArrayList<>();
        for (long seed = 100; seed < 100 + n; seed++) {
            try {
                randomCase(seed, 600);
            } catch (AssertionError e) {
                lost.add(String.valueOf(e.getMessage()).split("\n")[0]);
            }
        }
        assertTrue(lost.isEmpty(), lost.size() + " of " + n + " lost separation:\n" + String.join("\n", lost));
    }

    /**
     * Two seeds the round of 2026-11-23 broke (they kept separation before it): 160 - the new lead, cut off on its way out
     * of the group, taken as left after 30 s and its phone's RTL climbing where it hung, through the follower over it
     * (now: the group steps aside from a silent new lead's column at once, and a silent lead stays in everyone's way);
     * 164 - a drone near 3 % chasing its cell at speed, its FC's LAND braking it across a neighbour (now: its
     * speed is held down, its braking way short); and 113, which a first try at 164 broke - a cut-off drone back after the
     * group had moved rose in place to its approach layer under the drone over its old cell (now: it holds its height
     * until its way out and up is granted).
     */
    @Test
    void seedsOnceBrokenKeepSeparation() {
        randomCase(160, 600);
        randomCase(164, 600);
        randomCase(113, 600);
    }

    /**
     * The random cases, then the PC gone for good - every follower out on its lane at once with whatever plans the
     * overlapping failures left (drones cut off earlier with older plans, GNSS lost for the group and landing column by
     * column, reserves on their way), nobody left to correct anything. Seeds 7, 9, 154 and 180 lost separation (7-9 m
     * sideways and in height) a few minutes after the PC went, until every set of plans was proven with the plans the
     * drones already cut off were flying (ExitPlanner.Hanging), and with the old leads' ways home (seed 124: an old lead
     * came down on its point as a follower crossed it). Half of these seeds end with the PC gone (by seed).
     */
    @Test
    void randomCasesThenThePcGoes() {
        List<String> lost = new ArrayList<>();
        for (long seed : new long[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 124, 154, 180}) {
            try {
                randomCase(seed, seed < 100 ? 300 : 600, true);
            } catch (AssertionError e) {
                lost.add(String.valueOf(e.getMessage()).split("\n")[0]);
            }
        }
        assertTrue(lost.isEmpty(), lost.size() + " lost separation:\n" + String.join("\n", lost));
    }

    /** As {@link #randomCasesNightly}, each case ending with the PC gone for good where its seed says so. */
    @Tag("night")
    @Test
    void randomCasesThenThePcGoesNightly() {
        String env = System.getenv("AIR_SEEDS");
        int n = env != null && env.matches("[0-9]+") ? Integer.parseInt(env) : 30;
        List<String> lost = new ArrayList<>();
        for (long seed = 100; seed < 100 + n; seed++) {
            try {
                randomCase(seed, 600, true);
            } catch (AssertionError e) {
                lost.add(String.valueOf(e.getMessage()).split("\n")[0]);
            }
        }
        assertTrue(lost.isEmpty(), lost.size() + " of " + n + " lost separation:\n" + String.join("\n", lost));
    }

    /**
     * The integrator's case on the real wire (GroupLinkLoopbackTest), here without UDP: ground at sea level, the lead
     * standing at (150, 0) 80 m in PILOT, four followers starting off their cells at 60 - 90 m, 8 s of following, then
     * F2's link cut for 4 s (it brakes and holds, its reports stop: the PC takes it as cut off), then back.
     */
    @Test
    void integratorsCaseALinkCutWhileTheGroupFormsUp() {
        AirParams p = AirParams.defaults();
        VirtualAirspace a = new VirtualAirspace(p, 0, 7);
        a.gc = new GroupController(p, 0, a.lat0, a.lon0, VirtualAirspace.prover(p, 0));
        java.util.Map<String, Double> batt = new java.util.LinkedHashMap<>();
        double[][] start = {{-30, -30, 60}, {30, -30, 70}, {-30, 30, 80}, {30, 30, 90}};
        for (int i = 1; i <= 4; i++) batt.put("F" + i, 90.0 - 5 * i);
        a.gc.form("LEAD", batt, V2.ZERO, new V2(-300, 0));
        for (int i = 1; i <= 4; i++) {
            VirtualCopter c = a.add("F" + i, start[i - 1][0], start[i - 1][1], start[i - 1][2]);
            c.battery = batt.get("F" + i);
            c.homeAmsl = 0;
            a.follower("F" + i);
        }
        VirtualCopter lead = a.add("LEAD", 150, 0, 80);
        lead.homeAmsl = 0;
        a.leadId = "LEAD";
        a.pilot = Groups.goTo(150, 0, 80);
        a.run(8);
        a.setLink("F2", false);
        a.run(4);
        a.setLink("F2", true);
        a.run(60);
        record("integrator's case: F2 cut 4 s while forming up", a);
    }

    static void randomCase(long seed, double seconds) {
        randomCase(seed, seconds, false);
    }

    /** @param thenPcGone end with the PC gone for good (the lead home over everyone, every follower out on its lane) */
    static void randomCase(long seed, double seconds, boolean thenPcGone) {
        Random r = new Random(seed);
        VirtualAirspace a = Groups.group(8 + r.nextInt(17), seed);
        a.wind = new V2(r.nextGaussian() * 3, r.nextGaussian() * 3);
        double alt = leadAlt(a) + 10;
        double[] goal = {0, 100};
        a.pilot = (t, l) -> new double[]{goal[0], goal[1], alt};
        a.onNewLead = id -> a.pilot = (t, l) -> new double[]{l.e + 150, l.n - 150, alt + 20};
        // AC-41: the lead's home (where its RTL comes down) beside the group, from any side; with thenPcGone, half the cases end with
        // the PC gone for good - every follower out along its lane, the lead home over and down past them
        Random h = new Random(seed * 31 + 7);
        double ang = h.nextDouble() * 2 * Math.PI, far = 80 + h.nextInt(80);
        a.copters.get("LEAD").rallyE = far * Math.sin(ang);
        a.copters.get("LEAD").rallyN = far * Math.cos(ang);
        boolean pcGoes = h.nextBoolean() && thenPcGone;
        List<String> ids = new ArrayList<>(a.gc.members().keySet());
        int reserves = 0;
        for (double t = 0; t < seconds; t += 10) {
            int what = r.nextInt(10);
            String id = ids.get(r.nextInt(ids.size()));
            switch (what) {
                case 0 -> { goal[0] = r.nextInt(600) - 300; goal[1] = r.nextInt(600) - 300; }
                case 1 -> a.setLink(id, false);
                case 2 -> a.setLink(id, true);
                case 3 -> { if (a.copters.get(id).gnssOk && a.gc.members().size() > 4) a.copters.get(id).setGnss(false, a.t()); }
                case 4 -> a.copters.get(id).drainPerS = 0.5; // a pack draining fast (a sudden cell failure is not modelled: see the class doc)
                case 5 -> { if (reserves < 3) a.launchReserve("R" + reserves++, r.nextInt(800) - 400, -500 - reserves * 30.0, 90); }
                case 6 -> { if (r.nextInt(3) == 0) a.gc.discardLead(a.now); }
                default -> { }
            }
            a.run(10);
        }
        if (pcGoes) {
            a.pcAlive = false;
            a.run(480);
        }
        record("random case, seed " + seed + (pcGoes ? ", then the PC gone" : ""), a);
    }
}
