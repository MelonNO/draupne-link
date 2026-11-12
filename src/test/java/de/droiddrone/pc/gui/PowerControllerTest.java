package de.droiddrone.pc.gui;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PowerFloor;
import de.droiddrone.common.SleepCheck;
import de.droiddrone.pc.ControlLink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The power modes and the phones asleep, without the window: the check-ins a sleeping phone sends, and what is kept. */
class PowerControllerTest {
    private static final long T0 = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    /**
     * 1.0.64: the scan keeps a resting phone "online" between its check-ins - a timed sleeper was taken for woken on
     * the phone a minute after every check-in (online, no hello for a minute), its sleep state and countdown dropped.
     */
    @Test
    void aSleeperHeldOnlineBetweenCheckInsIsNotAwake() {
        PowerController.Asleep a = new PowerController.Asleep("MobileAlpha", "100.64.0.9", T0, 5);
        ControlLink.SleepHello hello = new ControlLink.SleepHello(80, false, 5, 3900, T0 + 5 * MIN);
        // its own answer came in the check window; 90 s later it is only held online
        de.droiddrone.pc.Names.Entry held = new de.droiddrone.pc.Names.Entry("MobileAlpha", de.droiddrone.pc.Names.Kind.PHONE, "100.64.0.9", "", "", "",
                T0 + 5 * MIN, true);
        long now = T0 + 5 * MIN + 90_000;
        assertFalse(PowerController.wokenOnThePhone(a, held, hello, de.droiddrone.pc.Names.POWER_UNKNOWN, now), "held online, not heard: still asleep");
        // answering right now and no check-in for a minute: woken on the phone
        de.droiddrone.pc.Names.Entry fresh = new de.droiddrone.pc.Names.Entry("MobileAlpha", de.droiddrone.pc.Names.Kind.PHONE, "100.64.0.9", "", "", "",
                now - 2000, true);
        assertTrue(PowerController.wokenOnThePhone(a, fresh, hello, de.droiddrone.pc.Names.POWER_UNKNOWN, now));
        assertTrue(PowerController.wokenOnThePhone(a, fresh, hello, 0, now), "and it says normal mode");
        // ...unless that answer itself says deep sleep or overwatch (a PC its hellos don't go to)
        assertFalse(PowerController.wokenOnThePhone(a, fresh, null, 2, now));
        assertFalse(PowerController.wokenOnThePhone(a, fresh, null, 3, now));
        // a check-in within the minute: asleep, as ever
        assertFalse(PowerController.wokenOnThePhone(a, fresh, new ControlLink.SleepHello(80, false, 5, 3900, now - 10_000), 0, now));
        assertFalse(PowerController.wokenOnThePhone(a, fresh.withOnline(false), hello, 0, now));
    }

    /** A host that keeps what it is told. */
    private static final class Host implements PowerController.Host {
        final List<String> logs = new ArrayList<>();
        final List<String> saves = new ArrayList<>();
        final List<String> woken = new ArrayList<>();
        final Set<String> observed = new HashSet<>();

        @Override
        public String nameAt(String ip) {
            return ip.equals("100.64.0.5") ? "MobileAlpha" : ip.equals("100.64.0.9") ? "PCBravo" : null;
        }

        @Override
        public boolean observed(String name) {
            return observed.contains(name);
        }

        @Override
        public void observedCheckIn(String ip, String name) {
            woken.add(name + "@" + ip);
        }

        @Override
        public void logLater(String line) {
            logs.add(line);
        }

        @Override
        public void saveAsleep(String saved) {
            saves.add(saved);
        }
    }

    private final Host host = new Host();
    private long wall = T0;
    private final PowerController power = new PowerController(host, () -> wall);

    /** A sleep check-in: [0x6F][battery][charging][nextCheckMin][mV u16][owFlags][floorReason]. */
    private static byte[] hello(int battery, int nextCheckMin, int owFlags, int floor) {
        return new byte[]{0x6F, (byte) battery, 0, (byte) nextCheckMin, 0x0F, 0x3C, (byte) owFlags, (byte) floor};
    }

    @Test
    void theSavedListReadsBackAndSkipsBrokenEntries() {
        List<PowerController.Asleep> read = PowerController.parseAsleep("MobileAlpha|100.64.0.5|1000|5;broken;MobileBravo|100.64.0.6|x|5;"
                + "MobileZulu|100.64.0.7|2000|255|ow;MobileYankee||3000|1");
        assertEquals(2, read.size());
        assertEquals(new PowerController.Asleep("MobileAlpha", "100.64.0.5", 1000, 5, false), read.get(0));
        assertTrue(read.get(1).overwatch());
        assertTrue(read.get(1).listening());
        assertTrue(PowerController.parseAsleep(null).isEmpty());
        assertTrue(PowerController.parseAsleep(" ").isEmpty());

        power.load("MobileZulu|100.64.0.7|2000|255|ow");
        assertEquals("MobileZulu|100.64.0.7|2000|255|ow", power.saved());
        assertTrue(host.saves.isEmpty(), "loading saves nothing");
    }

    @Test
    void aCheckInFromAPhoneOfOursIsRememberedAndAnsweredSleepOn() {
        // 1.0.64: "sleep on" not at the window's first hello - 2 s on (its third), so another PC's queued mode can land
        assertNull(power.checkIn("100.64.0.5", hello(80, 5, 0, 0)));
        PowerController.Asleep a = power.asleep("100.64.0.5");
        assertEquals(new PowerController.Asleep("MobileAlpha", "100.64.0.5", T0, 5, false), a);
        assertTrue(a.setHere(), "an older phone says hello only to the PC that set its sleep");
        assertEquals("MobileAlpha|100.64.0.5|" + T0 + "|5", host.saves.get(host.saves.size() - 1));
        assertEquals(List.of("MobileAlpha asleep: battery 80 %, next check in 5 min."), host.logs);
        assertEquals(T0, power.hello("100.64.0.5").receivedAt());
        wall += 1500;
        assertNull(power.checkIn("100.64.0.5", hello(80, 5, 0, 0)), "its second hello: not yet");
        wall += 1500;
        assertEquals(Boolean.FALSE, power.checkIn("100.64.0.5", hello(80, 5, 0, 0)), "3 s into its window: sleep on");

        wall += 7000; // the same check window: no second line
        power.checkIn("100.64.0.5", hello(79, 5, 0, 0));
        assertEquals(1, host.logs.size());
        wall += 5 * MIN; // the next check: a line, and its own wait
        assertNull(power.checkIn("100.64.0.5", hello(78, 5, 0, 0)));
        assertEquals(2, host.logs.size());
        wall += 3000;
        assertEquals(Boolean.FALSE, power.checkIn("100.64.0.5", hello(78, 5, 0, 0)));
    }

    /**
     * 1.0.64, phone pc.50 says hello to every PC that knows it: only the PC that set the sleep answers "sleep on" - the
     * first one from any PC shut the window in 0.3 s, before another PC's queued mode could land. And none behind a
     * queued mode just sent.
     */
    @Test
    void onlyThePcThatSetTheSleepAnswersSleepOn() {
        String ip = "100.64.0.5";
        // its sleep notice: a sleep this PC only heard of
        power.checkIn(ip, de.droiddrone.pc.SleepNoticeTest.notice(81, 2, 5, 0,
                new de.droiddrone.common.FleetProtocol.Notice(Double.NaN, 0, -1, -1, 303)));
        assertFalse(power.asleep(ip).setHere());
        power.setBuildOf(name -> PowerController.HELLO_TO_ALL_PC_BUILD);
        for (int i = 0; i < 6; i++) {
            wall += 5 * MIN + i * 1500L;
            assertNull(power.checkIn(ip, hello(80, 5, 0, 0)), "another PC's sleeper: no answer, hello " + i);
        }
        assertFalse(power.asleep(ip).setHere(), "pc.50's hello proves nothing");
        assertTrue(power.saved().endsWith("|other"), power.saved());
        assertFalse(PowerController.parseAsleep(power.saved()).get(0).setHere(), "kept across a restart");
        assertTrue(PowerController.parseAsleep("MobileAlpha|100.64.0.5|1000|5|ow|other").get(0).overwatch());

        // this PC sets a sleep (its link's command, its FLEET mode): its to answer, after 2 s
        power.remember(new PowerController.Asleep("MobileAlpha", ip, wall, 5));
        wall += 5 * MIN;
        assertNull(power.checkIn(ip, hello(80, 5, 0, 0)));
        wall += 3000;
        assertEquals(Boolean.FALSE, power.checkIn(ip, hello(80, 5, 0, 0)));
        // a queued FLEET mode went for this hello: no "sleep on" behind it, however late in the window
        wall += 1500;
        power.modeWent(ip);
        assertNull(power.checkIn(ip, hello(80, 5, 0, 0)));
        wall += 1500;
        assertEquals(Boolean.FALSE, power.checkIn(ip, hello(80, 5, 0, 0)), "nothing sent this time: sleep on again");
        // a listening phone's window never closes: answered as ever
        wall += MIN;
        assertEquals(Boolean.FALSE, power.checkIn(ip, hello(80, SleepCheck.ALWAYS, 0, 0)));
    }

    /** 1.0.64: an older phone's hello reaches only the PC that set its sleep - whatever the record said before. */
    @Test
    void anOlderPhonesHelloProvesThisPcSetItsSleep() {
        String ip = "100.64.0.5";
        power.setBuildOf(name -> 49);
        power.nameReply(ip, "MobileAlpha", 2, status(2, 5, 0));
        assertFalse(power.asleep(ip).setHere(), "filed by its answer to a name query: not set here");
        wall += 5 * MIN;
        power.checkIn(ip, hello(80, 5, 0, 0));
        assertTrue(power.asleep(ip).setHere());
        // a sleep notice of a mode this PC queued (FLEET's token) is set here from the start
        power.clear(ip);
        power.setOwnToken((name, token) -> token == 77);
        power.checkIn(ip, de.droiddrone.pc.SleepNoticeTest.notice(81, 2, 5, 0,
                new de.droiddrone.common.FleetProtocol.Notice(Double.NaN, 0, -1, -1, 303))); // its status says token 77
        assertTrue(power.asleep(ip).setHere());
        // the window copies a record (overwatch ended): the same sleep keeps whose it is
        power.clear(ip);
        power.setOwnToken(null);
        wall += MIN;
        power.checkIn(ip, de.droiddrone.pc.SleepNoticeTest.notice(81, 3, 5, OverwatchProtocol.HELLO_OVERWATCH,
                new de.droiddrone.common.FleetProtocol.Notice(Double.NaN, 0, -1, -1, 303)));
        PowerController.Asleep other = power.asleep(ip);
        assertFalse(other.setHere());
        power.remember(new PowerController.Asleep(other.name(), other.ip(), other.since(), other.intervalMin(), false));
        assertFalse(power.asleep(ip).setHere());
        assertFalse(power.asleep(ip).overwatch());
    }

    private static de.droiddrone.common.FleetProtocol.Status status(int mode, int intervalMin, int token) {
        return new de.droiddrone.common.FleetProtocol.Status(70, false, 3, 1, 9, 250, mode, intervalMin, token);
    }

    /**
     * 1.0.64, item 1a: a PC that only got the sleep notice (the phone's hellos go elsewhere) never heard a check-in -
     * two intervals and a minute on the drone read MISSED CHECK-IN / UNKNOWN, though it answered this PC's name query in
     * every window, saying "deep sleep". That answer is a check-in heard.
     */
    @Test
    void aNameReplyThatSaysAsleepIsACheckInHeard() {
        String ip = "100.64.0.5";
        power.checkIn(ip, de.droiddrone.pc.SleepNoticeTest.notice(81, 2, 5, 0,
                new de.droiddrone.common.FleetProtocol.Notice(Double.NaN, 0, -1, -1, 301)));
        for (int i = 0; i < 4; i++) { // its windows, each answered to this PC's scan
            wall += 5 * MIN + 2000;
            power.nameReply(ip, "MobileAlpha", 2, status(2, 5, 0));
            PowerController.Asleep a = power.asleep(ip);
            assertTrue(power.checkInOverdue(a, wall + 4 * MIN) <= 0, "window " + i + ": on time");
            assertEquals(wall + 5 * MIN, power.nextCheck(a, wall + MIN), "the countdown from its answer");
            assertEquals(wall, power.lastHeard(a));
        }
        assertEquals(70, power.hello(ip).batteryPercent(), "its battery as it says");
        assertEquals(1, host.logs.size(), "no line per answer: " + host.logs);
        // answers in one window (its hello came, or an earlier answer): one check-in, not a later anchor each
        long anchored = power.hello(ip).receivedAt();
        wall += 5000;
        power.nameReply(ip, "MobileAlpha", 2, status(2, 5, 0));
        assertEquals(anchored, power.hello(ip).receivedAt());
        // it says overwatch now, every 2 min: the record follows
        wall += 5 * MIN;
        power.nameReply(ip, "MobileAlpha", 3, status(3, 2, 0));
        assertTrue(power.asleep(ip).overwatch());
        assertEquals(2, power.asleep(ip).intervalMin());
        // silent from here: late as ever
        assertTrue(power.checkInOverdue(power.asleep(ip), wall + 5 * MIN + 1000) > 0);

        // a phone not in the book that says it sleeps is filed - not as set here; one that says no interval (pc.39) isn't
        power.nameReply("100.64.0.6", "MobileBravo", 2, status(2, 10, 0));
        PowerController.Asleep b = power.asleep("100.64.0.6");
        assertEquals(new PowerController.Asleep("MobileBravo", "100.64.0.6", wall, 10, false, false), b);
        assertEquals("MobileBravo: deep sleep, next check 10:00, battery 70 %.", host.logs.get(host.logs.size() - 1));
        power.nameReply("100.64.0.7", "MobileCharlie", 2, null);
        assertNull(power.asleep("100.64.0.7"));
        power.nameReply("100.64.0.8", "PCAlpha", 2, status(2, 5, 0));
        assertNull(power.asleep("100.64.0.8"), "not a phone's name");
    }

    /**
     * 1.0.64, item 1b: a phone woken by another PC, or on the phone, stayed ASLEEP then UNKNOWN on this PC while it
     * answered every 5 s saying "normal" - only the chosen phone's record was ever cleared. Any phone's goes at its own
     * fresh word; never at the word of an older phone that says no mode.
     */
    @Test
    void aSleeperThatAnswersAwakeIsNoLongerAsleepWhicheverItIs() {
        String ip = "100.64.0.5";
        power.remember(new PowerController.Asleep("MobileAlpha", ip, T0, 5));
        wall = T0 + 1000;
        power.nameReply(ip, "MobileAlpha", 0, status(0, 5, 0));
        assertNotNull(power.asleep(ip), "an answer from the second its sleep began: sent just before");
        wall = T0 + 90_000;
        power.nameReply(ip, "MobileAlpha", de.droiddrone.pc.Names.POWER_UNKNOWN, null);
        assertNotNull(power.asleep(ip), "an older phone says no mode: its answer in a check window proves nothing");
        power.nameReply(ip, "MobileAlpha", 0, status(0, 5, 0));
        assertNull(power.asleep(ip));
        assertNull(power.hello(ip));
        assertEquals("MobileAlpha is awake again.", host.logs.get(host.logs.size() - 1));
        assertEquals("", host.saves.get(host.saves.size() - 1));
        int lines = host.logs.size();
        power.nameReply(ip, "MobileAlpha", 0, status(0, 5, 0));
        assertEquals(lines, host.logs.size(), "said once");

        // just after a check-in: an answer from before it
        power.checkIn(ip, hello(80, 5, 0, 0));
        wall += 500;
        power.nameReply(ip, "MobileAlpha", 0, status(0, 5, 0));
        assertNotNull(power.asleep(ip));
        wall += 5000; // standby (another PC's, or on the phone): not asleep either
        power.nameReply(ip, "MobileAlpha", 1, status(1, 5, 0));
        assertNull(power.asleep(ip));
        assertEquals("MobileAlpha: standby.", host.logs.get(host.logs.size() - 1));

        // the phone this PC's link is waking: cleared, the window says "Phone awake" itself
        power.remember(new PowerController.Asleep("MobileAlpha", ip, wall, 5));
        power.phoneAsleep = true;
        power.awaitedIp = ip;
        wall += 10_000;
        lines = host.logs.size();
        power.nameReply(ip, "MobileAlpha", 0, status(0, 5, 0));
        assertNull(power.asleep(ip));
        assertEquals(lines, host.logs.size());
    }

    /** 1.0.64, item 1d: each check-in is told on with the phone's own "next check in" (the name scan paces its queries by it). */
    @Test
    void checkInsAreToldOnWithTheirInterval() {
        List<String> told = new ArrayList<>();
        power.setCheckIns((ip, mode, every) -> told.add(ip + " " + mode + " " + every));
        power.checkIn("100.64.0.5", hello(80, 5, 0, 0));
        wall += 5 * MIN;
        power.checkIn("100.64.0.5", hello(80, 2, OverwatchProtocol.HELLO_OVERWATCH, 0));
        wall += 5 * MIN;
        power.checkIn("100.64.0.5", de.droiddrone.pc.SleepNoticeTest.notice(81, 2, 10, 0,
                new de.droiddrone.common.FleetProtocol.Notice(Double.NaN, 0, -1, -1, 601)));
        power.linkHello("100.64.0.6", new ControlLink.SleepHello(69, false, 30, 3900, 5000), 5000, wall + MIN);
        wall += 5 * MIN;
        power.nameReply("100.64.0.5", "MobileAlpha", 2, status(2, 10, 0)); // an answer to a query is no hello
        assertEquals(List.of("100.64.0.5 2 5", "100.64.0.5 3 2", "100.64.0.5 2 10", "100.64.0.6 2 30"), told);
    }

    /**
     * 1.0.64, item 9: one word per sleeping phone on what its check-ins say of its overwatch - blind (pc.47), or left
     * for deep sleep by itself - for the lists.
     */
    @Test
    void theCheckInsSayWhenItsOverwatchIsBlindOrDropped() {
        String ip = "100.64.0.5";
        int watching = OverwatchProtocol.HELLO_OVERWATCH | OverwatchProtocol.HELLO_PICTURES;
        assertNull(power.watchWord(ip), "not asleep here");
        power.checkIn(ip, hello(80, 5, OverwatchProtocol.HELLO_OVERWATCH, 0));
        assertNull(power.watchWord(ip), "an older phone says nothing of its camera");
        wall += 5 * MIN;
        power.checkIn(ip, hello(80, 5, watching, 0));
        assertEquals(de.droiddrone.pc.overwatch.OverwatchHub.Trouble.BLIND, power.watchWord(ip).trouble());
        assertEquals(wall, power.watchWord(ip).at());
        assertTrue(host.logs.stream().anyMatch(l -> l.contains("OVERWATCH SEES NOTHING")), host.logs.toString());
        // an answer to a name query between its hellos says nothing of the camera: still blind, and not said again
        wall += 5 * MIN;
        power.nameReply(ip, "MobileAlpha", 3, status(3, 5, 0));
        assertEquals(de.droiddrone.pc.overwatch.OverwatchHub.Trouble.BLIND, power.watchWord(ip).trouble());
        assertEquals(wall, power.hello(ip).receivedAt(), "the answer counts as its check-in");
        assertTrue(power.hello(ip).watchesBlind(), "and what its last hello said of its camera stays said");
        wall += 5 * MIN;
        int lines = host.logs.size();
        power.checkIn(ip, hello(80, 5, watching, 0));
        assertFalse(host.logs.subList(lines, host.logs.size()).stream().anyMatch(l -> l.contains("SEES NOTHING")), host.logs.toString());
        wall += 5 * MIN;
        lines = host.logs.size();
        power.checkIn(ip, hello(80, 5, watching | OverwatchProtocol.HELLO_ANALYSING, 0));
        assertEquals(de.droiddrone.pc.overwatch.OverwatchHub.Trouble.NONE, power.watchWord(ip).trouble());
        assertTrue(host.logs.subList(lines, host.logs.size()).stream().anyMatch(l -> l.contains("pictures again")), host.logs.toString());
        // the floor: deep sleep by itself
        wall += 5 * MIN;
        power.checkIn(ip, hello(14, 5, 0, PowerFloor.REASON_BATTERY));
        de.droiddrone.pc.overwatch.OverwatchHub.Word w = power.watchWord(ip);
        assertEquals(de.droiddrone.pc.overwatch.OverwatchHub.Trouble.DROPPED, w.trouble());
        assertEquals(PowerFloor.REASON_BATTERY, w.reason());
        power.clear(ip);
        assertNull(power.watchWord(ip));
    }

    /** 1.0.64, item 4b: a renamed phone's sleep record goes with it. */
    @Test
    void aRenamedPhonesSleepRecordGoesWithIt() {
        power.remember(new PowerController.Asleep("MobileAlpha", "100.64.0.5", T0, 5, true));
        power.renamed("mobilealpha", "MobileKilo");
        PowerController.Asleep a = power.asleep("100.64.0.5");
        assertEquals("MobileKilo", a.name());
        assertTrue(a.overwatch());
        assertTrue(host.saves.get(host.saves.size() - 1).startsWith("MobileKilo|100.64.0.5|"));
    }

    @Test
    void aStrangerOrAPcIsNotAnswered() {
        assertNull(power.checkIn("100.64.0.1", hello(80, 5, 0, 0)), "not in the name map");
        assertNull(power.checkIn("100.64.0.9", hello(80, 5, 0, 0)), "a PC's name");
        assertTrue(power.noneAsleep());
        assertTrue(host.saves.isEmpty());
    }

    @Test
    void thePhonesOwnWordChangesItsIntervalAndOverwatch() {
        power.checkIn("100.64.0.5", hello(80, 5, OverwatchProtocol.HELLO_OVERWATCH, 0));
        assertTrue(power.asleep("100.64.0.5").overwatch());
        assertTrue(host.logs.get(0).startsWith("MobileAlpha overwatching"));

        wall += 5 * MIN; // the floor: out of overwatch by itself, and now listening all along
        power.checkIn("100.64.0.5", hello(14, SleepCheck.ALWAYS, 0, PowerFloor.REASON_BATTERY));
        PowerController.Asleep a = power.asleep("100.64.0.5");
        assertFalse(a.overwatch());
        assertTrue(a.listening());
        assertEquals(T0, a.since(), "still asleep since the first check-in");
        assertTrue(host.logs.contains("OVERWATCH ended on MobileAlpha: " + PowerFloor.describe(PowerFloor.REASON_BATTERY) + " - now in deep sleep."),
                host.logs.toString());
    }

    @Test
    void anAlwaysListeningPhoneIsLoggedOnceInTenMinutes() {
        power.checkIn("100.64.0.5", hello(80, SleepCheck.ALWAYS, 0, 0));
        assertEquals(List.of("MobileAlpha asleep: battery 80 %, listening."), host.logs);
        for (int i = 0; i < 9; i++) {
            wall += MIN;
            power.checkIn("100.64.0.5", hello(80, SleepCheck.ALWAYS, 0, 0));
        }
        assertEquals(1, host.logs.size(), "a hello a minute, no line each");
        wall += MIN + 1000;
        power.checkIn("100.64.0.5", hello(80, SleepCheck.ALWAYS, 0, 0));
        assertEquals(2, host.logs.size());
    }

    @Test
    void observationWakesItAtItsCheckIn() {
        host.observed.add("MobileAlpha");
        assertEquals(Boolean.TRUE, power.checkIn("100.64.0.5", hello(80, 5, 0, 0)));
        assertEquals(List.of("MobileAlpha@100.64.0.5"), host.woken);
        assertTrue(host.logs.isEmpty(), "the window says it");
    }

    @Test
    void clearingForgetsItsCheckInsToo() {
        power.checkIn("100.64.0.5", hello(80, 5, 0, 0));
        int saves = host.saves.size();
        power.clear("100.64.0.5");
        assertNull(power.asleep("100.64.0.5"));
        assertNull(power.hello("100.64.0.5"));
        assertEquals(saves + 1, host.saves.size());
        assertEquals("", host.saves.get(saves));
        power.clear("100.64.0.5");
        power.clear(null);
        assertEquals(saves + 1, host.saves.size(), "nothing to clear: nothing saved");

        power.remember(new PowerController.Asleep("MobileZulu", "100.64.0.7", T0, 1));
        power.forgetNamed("mobilezulu");
        assertTrue(power.noneAsleep());
    }

    @Test
    void theNextCheckCountsFromItsOwnWord() {
        PowerController.Asleep a = new PowerController.Asleep("MobileAlpha", "100.64.0.5", T0, 5);
        power.remember(a);
        assertEquals(T0 + 5 * MIN, power.nextCheck(a, T0));
        assertEquals(T0 + 10 * MIN, power.nextCheck(a, T0 + 6 * MIN), "a missed one: the one after");
        assertEquals("5:00", power.checkText(a, T0));
        wall = T0 + 2 * MIN;
        power.checkIn("100.64.0.5", hello(80, 2, 0, 0));
        a = power.asleep("100.64.0.5");
        assertEquals(2, a.intervalMin());
        assertEquals(wall + 2 * MIN, power.nextCheck(a, wall));
        assertEquals(wall, power.lastHeard(a));
        assertEquals("listening", power.checkText(new PowerController.Asleep("x", "y", T0, SleepCheck.ALWAYS), T0));
        assertEquals("0:00", PowerController.clock(-5));
        assertEquals("61:01", PowerController.clock(61 * MIN + 1000));
    }

    @Test
    void sticksPauseInAnyModeButNormalAndWhileAsleep() {
        assertFalse(power.sticksPaused());
        power.wanted = ControlLink.POWER_STANDBY;
        assertTrue(power.sticksPaused());
        power.wanted = ControlLink.POWER_NORMAL;
        power.phoneAsleep = true;
        assertTrue(power.sticksPaused());
    }

    /**
     * 2026-11-11, the pilot: "the time remaining in the map and in the main screen when trying to connect is
     * mismatched after the computer has been disconnected from the internet and the phone gave one status before
     * pressing PILOT". The main screen counted from the sleep's start; FLEET's card from that check-in.
     */
    @Test
    void theLinkCardAndFleetCountFromTheSameCheckIn() {
        String ip = "100.64.0.5";
        PowerController.Asleep a = new PowerController.Asleep("MobileAlpha", ip, T0, 5);
        power.remember(a);
        // offline a while; the phone's windows drifted: it checks in at 13.5 min, next in 5
        wall = T0 + 13 * MIN + 30_000;
        power.checkIn(ip, hello(70, 5, 0, 0));
        a = power.asleep(ip);
        long mono = 50_000_000; // MonoClock has its own zero
        // PILOT UAV half a minute later, as MainWindow sets it up
        wall += 30_000;
        mono += 30_000;
        power.phoneAsleep = true;
        power.sentIntervalMin = a.intervalMin();
        power.sleepStartAt = mono - (wall - a.since());
        power.awaitedIp = ip;
        long fleet = power.nextCheck(a, wall) - wall;
        assertEquals(4 * MIN + 30_000, fleet, "FLEET: 5 min from its check-in");
        assertEquals(fleet, power.wakeInMs(mono, wall, null), "the LINK card: the same, not the sleep's old windows");
        assertEquals(mono + fleet, power.nextCheckMono(mono, wall, null));

        // its next check-in comes on the link: both follow it
        mono += 4 * MIN + 30_000;
        wall += 4 * MIN + 30_000;
        ControlLink.SleepHello onLink = new ControlLink.SleepHello(69, false, 5, 3900, mono);
        power.linkHello(ip, onLink, mono, wall);
        long later = 20_000;
        assertEquals(5 * MIN - later, power.wakeInMs(mono + later, wall + later, onLink));
        assertEquals(5 * MIN - later, power.nextCheck(power.asleep(ip), wall + later) - (wall + later));
    }

    /** 1.0.64 / phone pc.50: the sleep notice - asleep from now, its next check counted from now, said once of three. */
    @Test
    void theSleepNoticeIsKnownAtOnce() {
        byte[] n = de.droiddrone.pc.SleepNoticeTest.notice(81, 2, 5, 0,
                new de.droiddrone.common.FleetProtocol.Notice(90, de.droiddrone.common.FleetProtocol.Notice.HEADING_PHONE, 3, 5, 301));
        assertEquals(Boolean.FALSE, power.checkIn("100.64.0.5", n));
        power.checkIn("100.64.0.5", n);
        power.checkIn("100.64.0.5", n);
        PowerController.Asleep a = power.asleep("100.64.0.5");
        assertEquals(T0, a.since(), "asleep from the notice, not from its first check-in");
        assertEquals(5, a.intervalMin());
        assertFalse(a.overwatch());
        assertEquals("5:00", power.checkText(a, T0), "its next check counts from now");
        assertEquals(List.of("MobileAlpha: deep sleep, next check 5:00, battery 81 %."), host.logs);
        assertTrue(host.saves.size() > 0, "kept across a restart");

        wall += 30_000; // an overwatch notice later
        power.checkIn("100.64.0.5", de.droiddrone.pc.SleepNoticeTest.notice(80, 3, 255, OverwatchProtocol.HELLO_OVERWATCH,
                new de.droiddrone.common.FleetProtocol.Notice(Double.NaN, 0, -1, -1, 0)));
        assertTrue(power.asleep("100.64.0.5").overwatch());
        assertEquals("MobileAlpha: overwatch, listening, battery 80 %.", host.logs.get(1));

        // standby: said, not asleep (it stays reachable)
        power.clear("100.64.0.5");
        wall += 30_000;
        assertNull(power.checkIn("100.64.0.5", de.droiddrone.pc.SleepNoticeTest.notice(79, 1, 0, 0,
                new de.droiddrone.common.FleetProtocol.Notice(Double.NaN, 0, -1, -1, 0))));
        assertNull(power.asleep("100.64.0.5"));
        assertEquals("MobileAlpha: standby, battery 79 %.", host.logs.get(2));
        assertNull(power.checkIn("100.64.0.1", n), "not one of ours");
    }

    @Test
    void theWakeCountdownFollowsTheLastHello() {
        assertEquals(-1, power.wakeInMs(0, null), "not asleep");
        power.phoneAsleep = true;
        power.sentIntervalMin = 5;
        power.sleepStartAt = 1000;
        assertEquals(5 * MIN, power.wakeInMs(1000, null));
        assertEquals(2 * MIN, power.wakeInMs(1000 + 8 * MIN, null), "a missed check: the one after");
        ControlLink.SleepHello h = new ControlLink.SleepHello(80, false, 2, 3900, 1000 + MIN);
        assertEquals(2 * MIN, power.wakeInMs(1000 + MIN, h), "its own word: 2 min from its hello");
        power.sentIntervalMin = SleepCheck.ALWAYS;
        assertEquals(-1, power.wakeInMs(1000, null), "always listening: no countdown");
    }

    /** 1.0.64: the countdown ran out and started over without a word; a check-in missed is counted, and said. */
    @Test
    void aMissedCheckInIsCounted() {
        assertEquals(0, power.missedCheckIns(0, T0, null), "not waited for");
        power.phoneAsleep = true;
        power.sentIntervalMin = 5;
        power.sleepStartAt = 1000;
        assertEquals(0, power.missedCheckIns(1000 + 4 * MIN, T0, null), "on time");
        assertEquals(0, power.missedCheckIns(1000 + 5 * MIN + 20_000, T0, null), "its window opens late and the hello takes a moment");
        assertEquals(1, power.missedCheckIns(1000 + 5 * MIN + 40_000, T0, null));
        assertEquals(1, power.missedCheckIns(1000 + 9 * MIN, T0, null));
        assertEquals(2, power.missedCheckIns(1000 + 11 * MIN, T0, null));
        ControlLink.SleepHello h = new ControlLink.SleepHello(80, false, 5, 3900, 1000 + 10 * MIN);
        assertEquals(0, power.missedCheckIns(1000 + 11 * MIN, T0, h), "heard again: counted from that check-in");
        power.sentIntervalMin = SleepCheck.ALWAYS;
        assertEquals(0, power.missedCheckIns(1000 + 60 * MIN, T0, null), "listening all along: nothing to miss");
        assertEquals("MISSED CHECK - NEXT 4:10", PowerController.connectText(true, false, false, false, false, true, 250_000, true));
        assertEquals("REGAINING CONTROL IN 4:10", PowerController.connectText(true, false, false, false, false, true, 250_000, false));
    }

    /**
     * 1.0.64, item 3: after a restart (or on a PC the phone doesn't check in with) the first check-in counted from lay
     * hours back - "MISSED CHECK" and "missed its check-in (12 now)" the moment PILOT UAV was pressed. Only check-ins
     * this PC waited for count.
     */
    @Test
    void checkInsFromBeforeThisPcWaitedAreNotMissed() {
        long mono = 9_000_000;
        power.phoneAsleep = true;
        power.sentIntervalMin = 5;
        power.awaitedIp = "100.64.0.5";
        power.sleepStartAt = mono - 60 * MIN; // asleep an hour, no check-in heard in this run
        assertEquals(0, power.missedCheckIns(mono, T0, null), "PILOT UAV just pressed: nothing missed yet");
        assertEquals(0, power.missedCheckIns(mono + 5 * MIN + 20_000, T0, null), "one whole interval from then");
        assertEquals(1, power.missedCheckIns(mono + 5 * MIN + 31_000, T0, null));
        assertEquals(2, power.missedCheckIns(mono + 10 * MIN + 31_000, T0, null));
        // a check-in heard on the link: counted from it again
        ControlLink.SleepHello h = new ControlLink.SleepHello(80, false, 5, 3900, mono + 11 * MIN);
        assertEquals(0, power.missedCheckIns(mono + 12 * MIN, T0, h));
        assertEquals(1, power.missedCheckIns(mono + 16 * MIN + 31_000, T0, h));

        // the wait ends (connected, or given up); the next wait counts from its own start
        power.phoneAsleep = false;
        assertEquals(-1, power.wakeInMs(mono + 20 * MIN, T0, null));
        power.phoneAsleep = true;
        assertEquals(0, power.missedCheckIns(mono + 60 * MIN, T0, null), "a new wait an hour later: not twelve missed");
        // a hello the name service heard long before this wait (wall clock) is no better
        power.phoneAsleep = false;
        power.missedCheckIns(mono + 61 * MIN, T0, null);
        power.phoneAsleep = true;
        power.remember(new PowerController.Asleep("MobileAlpha", "100.64.0.5", T0 - 60 * MIN, 5));
        wall = T0 - 40 * MIN;
        power.checkIn("100.64.0.5", hello(80, 5, 0, 0));
        assertEquals(0, power.missedCheckIns(mono + 62 * MIN, T0, null));
    }

    /**
     * 1.0.64, item 2: the Connects were held except round the expected check-in - the last hello rolled on by whole
     * intervals. Each unanswered window opens the next one a window's length later (the phone counts from its close),
     * and Doze holds alarms back: after a few missed check-ins no Connect met a window, and the phone couldn't be woken.
     * Once one was missed nothing is held.
     */
    @Test
    void connectsAreNotHeldOnceACheckInWasMissed() {
        long next = 1_000_000;
        assertTrue(PowerController.holdConnects(true, false, 5, next - 60_000, next, 0), "on time: held between check-ins");
        assertFalse(PowerController.holdConnects(true, false, 5, next - 60_000, next, 1), "one missed: Connects all along");
        assertFalse(PowerController.holdConnects(true, false, 5, next - 300_000 + 55_000, next), "the late side: one unanswered window more");
        assertTrue(PowerController.holdConnects(true, false, 5, next - 300_000 + 61_000, next));

        // the whole rule, as the window asks it each refresh
        long mono = 9_000_000;
        power.phoneAsleep = true;
        power.awaitingWake = true;
        power.sentIntervalMin = 5;
        power.awaitedIp = "100.64.0.5";
        power.sleepStartAt = mono;
        assertFalse(power.holdConnects(mono + MIN, T0, null), "no check-in heard on this link: the window isn't known");
        ControlLink.SleepHello h = new ControlLink.SleepHello(80, false, 5, 3900, mono + 5 * MIN);
        assertTrue(power.holdConnects(mono + 7 * MIN, T0, h), "between check-ins: held");
        assertFalse(power.holdConnects(mono + 10 * MIN - 4000, T0, h), "round the expected one: Connects");
        assertFalse(power.holdConnects(mono + 10 * MIN + 31_000, T0, h), "missed");
        for (long t = mono + 11 * MIN; t < mono + 30 * MIN; t += 7000) {
            assertFalse(power.holdConnects(t, T0, h), "missed: never held again until it is heard, at " + (t - mono) / 1000 + " s");
        }
        ControlLink.SleepHello again = new ControlLink.SleepHello(80, false, 5, 3900, mono + 30 * MIN);
        assertTrue(power.holdConnects(mono + 32 * MIN, T0, again), "heard again: its window is known again");
        // it answers this PC's name queries awake (woken elsewhere): nothing to wait for
        wall = T0 + 90_000;
        power.nameReply("100.64.0.5", "MobileAlpha", 0, status(0, 5, 0));
        assertFalse(power.holdConnects(mono + 32 * MIN, T0, again));
    }
}
