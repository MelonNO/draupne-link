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

    private static double leadAlt(VirtualAirspace a) {
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

    @Test
    void oneDroneLosesGnssInWindFromEachDirection() {
        V2[] winds = {new V2(0, -6), new V2(-6, 0), new V2(0, 6), new V2(6, 0)};
        for (int w = 0; w < 4; w++) {
            VirtualAirspace a = Groups.group(20, 90 + w);
            a.wind = winds[w];
            a.pilot = Groups.goTo(0, 100, leadAlt(a));
            a.run(3);
            String id = a.gc.members().values().stream().filter(m -> m.place != null && m.place.layer() == 1 && m.place.cell() == 3)
                    .findFirst().orElseThrow().id;
            a.copters.get(id).setGnss(false, a.t());
            a.run(90);
            assertTrue(has(a, "GNSS LOST " + id));
            record("GNSS lost, one drone, wind from " + "NESW".charAt(w), a);
        }
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
     * GNSS lost, packs draining fast, reserves called, DISCARD LEAD. Five seeds in the normal suite; the long run (30
     * seeds, 10 min each) under the night tag.
     */
    @Test
    void randomCasesSeeded() {
        for (long seed = 1; seed <= 5; seed++) randomCase(seed, 300);
    }

    /**
     * The nightly run (AC-14 "random scenarios run every night"): gradlew benchNight; AIR_SEEDS=N for how many (30),
     * seeds from 100, 10 min each; every seed is run and every one that lost separation is said.
     * <p>
     * OPEN (tagged finding too, until it holds for all): 2026-11-23, seeds 100-189: 85 of 90 keep separation; 112
     * (6.1 m at the same height), 139 (9.2 m), 147 (9.5 m sideways, 9.0 m in height), 157 (9.0 m, 8.6 m), 160
     * (10.0 m, 5.9 m) do not. Near misses mostly: what the safe velocities' buffer (3 m) can buy against the drones'
     * response; a wider buffer (4 m) made it worse (82 of 90).
     */
    @Tag("night")
    @Tag("finding")
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
        Random r = new Random(seed);
        VirtualAirspace a = Groups.group(8 + r.nextInt(17), seed);
        a.wind = new V2(r.nextGaussian() * 3, r.nextGaussian() * 3);
        double alt = leadAlt(a) + 10;
        double[] goal = {0, 100};
        a.pilot = (t, l) -> new double[]{goal[0], goal[1], alt};
        a.onNewLead = id -> a.pilot = (t, l) -> new double[]{l.e + 150, l.n - 150, alt + 20};
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
        record("random case, seed " + seed, a);
    }
}
