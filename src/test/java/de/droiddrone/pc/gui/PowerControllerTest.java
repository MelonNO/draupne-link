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
        assertEquals(Boolean.FALSE, power.checkIn("100.64.0.5", hello(80, 5, 0, 0)));
        PowerController.Asleep a = power.asleep("100.64.0.5");
        assertEquals(new PowerController.Asleep("MobileAlpha", "100.64.0.5", T0, 5, false), a);
        assertEquals("MobileAlpha|100.64.0.5|" + T0 + "|5", host.saves.get(host.saves.size() - 1));
        assertEquals(List.of("MobileAlpha asleep: battery 80 %, next check in 5 min."), host.logs);
        assertEquals(T0, power.hello("100.64.0.5").receivedAt());

        wall += 10_000; // the same check window: no second line
        power.checkIn("100.64.0.5", hello(79, 5, 0, 0));
        assertEquals(1, host.logs.size());
        wall += 5 * MIN; // the next check: a line
        power.checkIn("100.64.0.5", hello(78, 5, 0, 0));
        assertEquals(2, host.logs.size());
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
}
