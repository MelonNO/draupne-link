package de.droiddrone.bench;

import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PhoneRig} on a real phone, without one (a recorder where adb is): what it always puts back, how it stands in
 * for root, where a finger lands on another screen, how it reads the battery and what it does when that is hot or
 * low, the log it follows, the one address it reaches.
 */
class PhoneRigDeviceTest {
    private static final String BENCH = BenchControl.PACKAGE, CABLE = PhoneRigGuardTest.CABLE;
    private final RecordingAdb adb = RecordingAdb.aPhone();
    private final List<Long> pauses = new ArrayList<>();
    private final long[] now = {1_000_000};
    private final List<PhoneRig> open = new ArrayList<>();

    private PhoneRig rig(PhoneRig.Target target, Path folder) {
        PhoneRig r = new PhoneRig(null, target, adb, ms -> {
            pauses.add(ms);
            now[0] += ms;
        }, () -> now[0], folder);
        open.add(r);
        return r;
    }

    private PhoneRig rig() {
        return rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), null);
    }

    @AfterEach
    void closed() {
        for (PhoneRig r : open) r.close(); // the tap's thread
        assertFalse(Fence.isUp(), "no fence is put up or opened by a rig: the fence's way through is its own (Fence.Reach)");
    }

    private int seen;

    private List<String> sent() {
        List<String> s = adb.since(seen);
        seen = adb.count();
        return s;
    }

    private static final List<String> RESETS = List.of("shell dumpsys deviceidle unforce", "shell dumpsys battery reset");

    // ------------------------------------------------------------------ what is always put back

    @Test
    void closeAlwaysTakesBackWhatMayHaveBeenPretendedAboutTheBattery() {
        // a rig that pretended nothing: a run killed before this one may have
        PhoneRig untouched = rig();
        untouched.close();
        assertEquals(RESETS, sent());

        // one that pretended and never took it back
        PhoneRig pretended = rig();
        assertTrue(pretended.onBattery());
        assertEquals(List.of("shell dumpsys battery unplug", "shell dumpsys deviceidle force-idle"), sent());
        pretended.close();
        assertEquals(RESETS, sent());

        // one whose scenario took it back itself: again all the same - it costs nothing
        PhoneRig tidy = rig();
        tidy.onBattery();
        tidy.plugged();
        assertEquals(List.of("shell dumpsys battery unplug", "shell dumpsys deviceidle force-idle", "shell dumpsys deviceidle unforce", "shell dumpsys battery reset"), sent());
        tidy.close();
        assertEquals(RESETS, sent());

        // one whose scenario pretended in its own words
        PhoneRig own = rig();
        own.shell("dumpsys battery unplug");
        sent();
        own.close();
        assertEquals(RESETS, sent());
    }

    @Test
    void closeTakesItBackAlsoAfterARefusalAStopOrALostPhone() {
        PhoneRig refused = rig();
        assertThrows(PhoneRig.Refused.class, () -> refused.shell("locksettings clear"));
        refused.close();
        assertEquals(RESETS, sent());

        // the battery too low: the scenario is stopped, every further command too - but not the putting back
        PhoneRig low = rig();
        adb.says("shell dumpsys battery", RecordingAdb.battery(9, 300, 3, false));
        assertThrows(PhoneRig.Stop.class, low::checkBattery);
        assertThrows(PhoneRig.Stop.class, () -> low.shell("getprop sys.boot_completed"));
        assertThrows(PhoneRig.Stop.class, low::onBattery);
        sent();
        low.close();
        assertEquals(RESETS, sent());
        adb.says("shell dumpsys battery", RecordingAdb.battery(80, 300, 2, true));

        // adb fails on the first of the two (the cable out): the second is still tried, and close does not throw
        PhoneRig lost = rig();
        adb.fails("shell dumpsys deviceidle unforce");
        lost.close();
        assertEquals(RESETS, sent());
    }

    @Test
    void whatWasLedBackToThisPcIsRemovedAndCanBeSentAgain() {
        PhoneRig rig = rig();
        rig.reverseTcp(8123);
        rig.reverseTcp(8124);
        rig.reverseTcp(8123); // asked again: sent again, kept once
        assertEquals(List.of("reverse tcp:8123 tcp:8123", "reverse tcp:8124 tcp:8124", "reverse tcp:8123 tcp:8123"), sent());
        rig.reverseAgain();   // after the cable was out
        assertEquals(List.of("reverse tcp:8123 tcp:8123", "reverse tcp:8124 tcp:8124"), sent());
        rig.close();
        assertEquals(List.of("shell dumpsys deviceidle unforce", "shell dumpsys battery reset", "reverse --remove tcp:8123", "reverse --remove tcp:8124"), sent());
        rig.close();
        assertEquals(RESETS, sent(), "closed twice: the two again, the ports only once");

        // how a real phone fetches from a server a scenario made up on this PC, against the emulator's 10.0.2.2
        PhoneRig.Target phone = PhoneRigGuardTest.phone(CABLE, BENCH, false);
        assertEquals("http://127.0.0.1:8125", phone.thisPc(8125));
        PhoneRig update = rig();
        update.reachesThisPc(8125);
        assertEquals(List.of("reverse tcp:8125 tcp:8125"), sent());
        adb.says("reverse", "adb.exe: error: closed");
        assertThrows(IllegalStateException.class, () -> update.reverseTcp(8126));
    }

    // ------------------------------------------------------------------ root's work without root

    @Test
    void rootsWorkGoesThroughTheBenchBuildsReceiver() {
        PhoneRig rig = rig();
        // as flight-app/bench-overlay/README.md writes them
        String to = "-n de.droiddrone.flight.pc.bench/de.droiddrone.flight.BenchControlReceiver -a de.droiddrone.flight.bench.";
        rig.root();
        assertEquals(List.of(), sent(), "no adb root");

        pauses.clear();
        rig.stopForgettingRestarts(); // asked first - a closed app is opened for the question - then closed
        assertEquals(List.of("shell am broadcast --include-stopped-packages " + to + "RESET_CRASH_GUARD", "shell am force-stop " + BENCH), sent());
        assertEquals(List.of(), pauses, "its answer is there when am broadcast returns: nothing to wait for");

        rig.endProcess("4711");
        assertEquals(List.of("shell am broadcast " + to + "KILL"), sent());

        rig.crash();
        assertEquals(List.of("shell am broadcast " + to + "CRASH"), sent());

        adb.says("shell am broadcast", "Broadcasting: Intent { act=de.droiddrone.flight.bench.STATS }\nBroadcast completed: result=1, "
                + "data=\"DRAUPNE-BENCH-FC stats: chosen=usb link=down serialStatus=1 service=running pc=connected pid=4711\"");
        String stats = rig.socketStats();
        assertTrue(stats.contains("pc=connected pid=4711") && stats.contains("cannot be read"), stats);
        assertEquals(List.of("shell am broadcast " + to + "STATS"), sent());

        // nobody took it (result 0: the released app installed under the bench's name, a build without the receiver),
        // or am itself refuses: said, never passed over
        adb.says("shell am broadcast", "Broadcasting: Intent { }\nBroadcast completed: result=0", "Error: Activity class does not exist");
        assertTrue(assertThrows(IllegalStateException.class, () -> rig.endProcess("4711")).getMessage().contains("did not take"));
        assertTrue(assertThrows(IllegalStateException.class, rig::crash).getMessage().contains("did not take"));
    }

    @Test
    void aRealPhoneIsSetUpWithoutItsLockScreenBeingTouched() {
        PhoneRig rig = rig();
        rig.grantPermissions();
        assertEquals(List.of(
                "shell pm grant " + BENCH + " android.permission.CAMERA",
                "shell pm grant " + BENCH + " android.permission.RECORD_AUDIO",
                "shell pm grant " + BENCH + " android.permission.ACCESS_FINE_LOCATION",
                "shell pm grant " + BENCH + " android.permission.ACCESS_COARSE_LOCATION",
                "shell pm grant " + BENCH + " android.permission.POST_NOTIFICATIONS",
                "shell dpm set-active-admin --user 0 " + BENCH + "/de.droiddrone.flight.PowerAdminReceiver"), sent());
        rig.launch();
        assertEquals(List.of("shell input keyevent KEYCODE_WAKEUP", "shell wm dismiss-keyguard", "shell am start -n " + BENCH + "/de.droiddrone.flight.MainActivity"), sent());
        assertEquals(BENCH, rig.app());
        rig.pid();
        rig.forceStop();
        rig.clearData();
        assertEquals(List.of("shell pidof " + BENCH, "shell am force-stop " + BENCH, "shell pm clear " + BENCH), sent());

        // the released app, without the run's word: the first change is refused, nothing sent
        PhoneRig released = rig(PhoneRigGuardTest.phone(CABLE, PhoneRig.APP, false), null);
        assertThrows(PhoneRig.Refused.class, released::grantPermissions);
        assertThrows(PhoneRig.Refused.class, released::forceStop);
        assertThrows(PhoneRig.Refused.class, released::clearData);
        assertEquals(List.of(), sent());
        released.launch(); // opening it is a hand's work, and reading it is reading
        released.version();
        assertEquals(List.of("shell input keyevent KEYCODE_WAKEUP", "shell wm dismiss-keyguard", "shell am start -n " + PhoneRig.APP + "/de.droiddrone.flight.MainActivity",
                "shell dumpsys package " + PhoneRig.APP), sent());
    }

    @Test
    void theAppOnARealPhoneIsLeftAsItIsUnlessTheRunNamesAnApk(@TempDir Path dir) throws Exception {
        adb.says("shell dumpsys package", "    versionName=2.3.0-pc.56-bench\n");
        PhoneRig rig = rig();
        rig.installApp();
        assertEquals(List.of("shell pm path " + BENCH, "shell dumpsys package " + BENCH), sent(), "asked whether it is there, and its version: nothing installed");

        Path apk = PhoneRigApkPackageTest.apk(dir.resolve("flight-bench.apk"), BENCH, true);
        PhoneRig.Target named = new PhoneRig.Target(CABLE, PhoneRig.Kind.DEVICE, BENCH, PhoneRigGuardTest.phone(CABLE, BENCH, false).link(),
                java.util.EnumSet.noneOf(PhoneRig.Capability.class), false, null, null, apk);
        rig(named, null).installApp();
        assertEquals(List.of("install -r -t " + apk, "shell dumpsys package " + BENCH), sent());

        adb.says("shell pm path", "");
        assertTrue(assertThrows(IllegalStateException.class, () -> rig.installApp()).getMessage().contains("is not on the phone"));
    }

    // ------------------------------------------------------------------ another screen

    @Test
    void aPlaceOnTheReferenceScreenIsTheSameShareOfAnotherScreen() {
        assertEquals(new PhoneRig.Screen(1080, 2400), PhoneRig.Screen.parse("Physical size: 1080x2400", null));
        assertEquals(new PhoneRig.Screen(720, 1600), PhoneRig.Screen.parse("Physical size: 1080x2400\nOverride size: 720x1600", ""), "a size the phone was set to wins");
        assertEquals(new PhoneRig.Screen(2400, 1080), PhoneRig.Screen.parse("Physical size: 1080x2400",
                "  Display: mDisplayId=0\n    init=1080x2400 420dpi cur=2400x1080 app=2400x1017 rng=1080x1017-2400x2337"), "the screen as it is turned now");
        assertNull(PhoneRig.Screen.parse("error: no devices", null));
        assertNull(PhoneRig.Screen.parse(null, null));

        assertEquals(new Point(540, 1700), new PhoneRig.Screen(1080, 2400).scaled(540, 1700), "the reference screen itself");
        assertEquals(new Point(360, 1133), new PhoneRig.Screen(720, 1600).scaled(540, 1700));
        assertEquals(new Point(720, 2267), new PhoneRig.Screen(1440, 3200).scaled(540, 1700));
        assertEquals(new Point(540, 1658), new PhoneRig.Screen(1080, 2340).scaled(540, 1700), "another shape: the same share of the way down");
        assertEquals(new Point(1200, 765), new PhoneRig.Screen(2400, 1080).scaled(540, 1700), "lying on its side");
        assertEquals(new Point(0, 0), new PhoneRig.Screen(720, 1600).scaled(0, 0));
        assertEquals(new Point(719, 1599), new PhoneRig.Screen(720, 1600).scaled(1080, 2400), "the far corner stays on the screen");
    }

    @Test
    void fixedPlacesAreScaledToARealPhonesScreenAndWordsAreFoundWhereTheyAre() {
        adb.says("shell wm size", "Physical size: 720x1600");
        PhoneRig rig = rig();
        rig.scrollDown();
        assertEquals(List.of("shell wm size", "shell dumpsys window displays", "shell input swipe 360 1133 360 467 300"), sent());
        rig.tapAt(540, 1200);
        assertEquals(List.of("shell wm size", "shell dumpsys window displays", "shell input tap 360 800"), sent());
        assertEquals(new Point(360, 800), rig.place(540, 1200));
        sent();

        // turned on its side between two presses: asked each time
        adb.says("shell dumpsys window displays", "init=720x1600 320dpi cur=1600x720 app=1600x660");
        rig.tapAt(540, 1200);
        assertEquals(List.of("shell wm size", "shell dumpsys window displays", "shell input tap 800 360"), sent());

        // a control found by its words is pressed where the phone itself says it is: no scaling, no size asked
        adb.says("exec-out uiautomator", "<hierarchy rotation=\"0\"><node text=\"START\" resource-id=\"x:id/start\" content-desc=\"\" clickable=\"true\" enabled=\"true\" "
                + "bounds=\"[60,1400][660,1500]\" /></hierarchy>");
        rig.tap("START");
        assertEquals(List.of("exec-out uiautomator dump /dev/tty", "shell input tap 360 1450", "exec-out screencap -p"), sent());

        adb.says("shell wm size", "").says("shell dumpsys window displays", "");
        assertThrows(IllegalStateException.class, rig::scrollDown);
    }

    // ------------------------------------------------------------------ the battery

    @Test
    void theBatteryIsReadFromWhatAndroidPrints() {
        PhoneRig.Battery b = PhoneRig.Battery.parse(RecordingAdb.battery(87, 305, 2, true));
        assertEquals(new PhoneRig.Battery(87, 30.5, "CHARGING", true, false, true), b);
        assertTrue(b.charging());
        assertEquals("87 %, 30.5 C, charging, on a charger", b.toString());

        PhoneRig.Battery away = PhoneRig.Battery.parse(RecordingAdb.battery(41, 268, 3, false));
        assertEquals(new PhoneRig.Battery(41, 26.8, "DISCHARGING", false, false, true), away);
        assertFalse(away.charging());

        assertEquals("FULL", PhoneRig.Battery.parse(RecordingAdb.battery(100, 290, 5, true)).status());
        assertTrue(PhoneRig.Battery.parse(RecordingAdb.battery(100, 290, 5, true)).charging());
        assertEquals("NOT_CHARGING", PhoneRig.Battery.parse(RecordingAdb.battery(80, 451, 4, true)).status());
        assertEquals("UNKNOWN", PhoneRig.Battery.parse(RecordingAdb.battery(80, 290, 1, false)).status());

        // a phone maker's own lines among them, Windows line ends, a scale that is not 100, below zero
        PhoneRig.Battery odd = PhoneRig.Battery.parse("Current Battery Service state:\r\n  AC powered: true\r\n  USB powered: false\r\n  Wireless powered: false\r\n"
                + "  Max charging current: 0\r\n  mMakersOwnLine: 1\r\n  makersDate: 20240101\r\n  status: 2\r\n  health: 2\r\n  present: true\r\n  level: 150\r\n  scale: 200\r\n"
                + "  voltage: 4100\r\n  temperature: -35\r\n  technology: Li-ion\r\n  makersOwnFlag: false\r\n");
        assertEquals(new PhoneRig.Battery(75, -3.5, "CHARGING", true, false, true), odd);

        // wireless and dock count as a charger
        assertTrue(PhoneRig.Battery.parse("  AC powered: false\n  USB powered: false\n  Wireless powered: true\n  status: 2\n  level: 50\n  scale: 100\n  temperature: 300\n").plugged());
        assertTrue(PhoneRig.Battery.parse("  AC powered: false\n  Dock powered: true\n  status: 2\n  level: 50\n  scale: 100\n  temperature: 300\n").plugged());

        // pretended off the cable: Android's figures stand still, and are marked so
        String unplugged = "Current Battery Service state:\n  (UPDATES STOPPED -- use 'reset' to restart)\n  AC powered: false\n  USB powered: false\n  Wireless powered: false\n"
                + "  status: 3\n  level: 64\n  scale: 100\n  temperature: 331\n";
        PhoneRig.Battery still = PhoneRig.Battery.parse(unplugged);
        assertEquals(new PhoneRig.Battery(64, 33.1, "DISCHARGING", false, true, false), still);
        assertEquals("64 %, 33.1 C, discharging (pretended off the cable; these figures stand still)", still.toString());
        // ... unless the battery's own files say how it really is (per cent, tenths of a degree)
        assertEquals(new PhoneRig.Battery(71, 38.4, "DISCHARGING", false, true, true), still.withReal("71\n384"));
        for (String no : new String[]{"", "71", "cat: /sys/class/power_supply/battery/temp: Permission denied", "101\n300", "50\n9999", "a\nb"}) {
            assertEquals(still, still.withReal(no), no);
        }
        assertEquals(still, still.withReal(null));

        assertNull(PhoneRig.Battery.parse("error: device offline"));
        assertNull(PhoneRig.Battery.parse(""));
        assertNull(PhoneRig.Battery.parse(null));
        assertTrue(Double.isNaN(PhoneRig.Battery.parse("  level: 50\n  scale: 100\n").celsius()), "no temperature said: not a made-up one");
    }

    @Test
    void theRigReadsTheRealFiguresWhileItPretends() {
        String unplugged = "Current Battery Service state:\n  (UPDATES STOPPED -- use 'reset' to restart)\n  USB powered: false\n  status: 3\n  level: 64\n  scale: 100\n  temperature: 331\n";
        PhoneRig rig = rig();
        assertEquals(new PhoneRig.Battery(80, 30.1, "CHARGING", true, false, true), rig.readBattery());
        assertEquals(List.of("shell dumpsys battery"), sent());

        adb.says("shell dumpsys battery", unplugged).says("shell cat /sys/class/power_supply", "66\n352\n");
        assertEquals(new PhoneRig.Battery(66, 35.2, "DISCHARGING", false, true, true), rig.readBattery());
        assertEquals(List.of("shell dumpsys battery", "shell cat /sys/class/power_supply/battery/capacity /sys/class/power_supply/battery/temp 2>/dev/null"), sent());

        // a phone that does not let adb read them: the figures that stand still, marked so - and neither rule is applied to them
        adb.says("shell cat /sys/class/power_supply", "");
        PhoneRig.Battery still = rig.checkBattery();
        assertTrue(still.pretended() && !still.live());
        adb.says("shell dumpsys battery", unplugged.replace("level: 64", "level: 3").replace("temperature: 331", "temperature: 600"));
        assertFalse(rig.checkBattery().live(), "nothing is concluded from figures that stand still");
        assertEquals(List.of(), pauses);
    }

    @Test
    void hotterThanTheLimitTheScenarioWaitsUntilItIsCooler() {
        PhoneRig rig = rig();
        adb.says("shell dumpsys battery", RecordingAdb.battery(70, 420, 2, true));
        rig.checkBattery();
        assertEquals(List.of(), pauses, "42.0 is not hotter than 42");

        adb.says("shell dumpsys battery", RecordingAdb.battery(70, 431, 2, true), RecordingAdb.battery(70, 425, 2, true), RecordingAdb.battery(70, 408, 2, true),
                RecordingAdb.battery(70, 400, 2, true));
        long t0 = now[0];
        PhoneRig.Battery after = rig.checkBattery();
        assertEquals(40.0, after.celsius(), "it goes on at 40, not at the first reading under 42");
        assertEquals(List.of(30_000L, 30_000L, 30_000L), pauses);
        assertEquals(90_000, now[0] - t0);

        // it does not cool (the app goes on filming): half an hour, then the run stops - and stays stopped
        adb.says("shell dumpsys battery", RecordingAdb.battery(70, 455, 2, true));
        pauses.clear();
        PhoneRig.Stop stop = assertThrows(PhoneRig.Stop.class, rig::checkBattery);
        assertTrue(stop.getMessage().contains("45.5") && stop.getMessage().contains("30 minutes"), stop.getMessage());
        assertEquals(61, pauses.size());
        assertThrows(PhoneRig.Stop.class, () -> rig.shell("getprop sys.boot_completed"));
    }

    @Test
    void belowTheLowLimitTheRunStops() {
        PhoneRig rig = rig();
        adb.says("shell dumpsys battery", RecordingAdb.battery(15, 300, 3, false));
        assertEquals(15, rig.checkBattery().percent(), "15 % is not below 15");
        adb.says("shell dumpsys battery", RecordingAdb.battery(14, 300, 2, true));
        PhoneRig.Stop stop = assertThrows(PhoneRig.Stop.class, rig::checkBattery);
        assertTrue(stop.getMessage().contains("14 %") && stop.getMessage().contains("below 15"), stop.getMessage());
        sent();
        assertThrows(PhoneRig.Stop.class, () -> rig.tapAt(1, 1));
        assertThrows(PhoneRig.Stop.class, rig::launch);
        assertEquals(List.of(), sent(), "nothing more goes to the phone");

        // while it waits for a hot battery to cool it must not run empty unseen
        PhoneRig hot = rig();
        adb.says("shell dumpsys battery", RecordingAdb.battery(40, 450, 3, false), RecordingAdb.battery(20, 440, 3, false), RecordingAdb.battery(12, 430, 3, false));
        assertTrue(assertThrows(PhoneRig.Stop.class, hot::checkBattery).getMessage().contains("12 %"));
    }

    @Test
    void theBatteryIsLookedAtOnceAMinuteAsCommandsGo() {
        PhoneRig rig = rig();
        rig.shell("getprop sys.boot_completed");
        now[0] += 59_000;
        rig.shell("getprop sys.boot_completed");
        assertEquals(List.of("shell getprop sys.boot_completed", "shell getprop sys.boot_completed"), sent(), "not within the first minute");
        now[0] += 1_000;
        rig.shell("getprop sys.boot_completed");
        assertEquals(List.of("shell dumpsys battery", "shell getprop sys.boot_completed"), sent(), "after a minute: first the battery, then the command");
        rig.shell("getprop sys.boot_completed");
        assertEquals(List.of("shell getprop sys.boot_completed"), sent());

        // found too low on the way: the command that was on its way is not sent
        now[0] += 60_000;
        adb.says("shell dumpsys battery", RecordingAdb.battery(5, 300, 3, false));
        assertThrows(PhoneRig.Stop.class, () -> rig.shell("am force-stop " + BENCH));
        assertEquals(List.of("shell dumpsys battery"), sent());
    }

    // ------------------------------------------------------------------ the phone there, its log followed

    @Test
    void aRealPhoneIsNotStartedButFoundAndItsLogFollowedFromThen(@TempDir Path run) throws Exception {
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), run);
        rig.ensureRunning();
        assertEquals(List.of("get-state", "shell getprop sys.boot_completed", "shell dumpsys battery"), sent());
        assertEquals(List.of(), adb.emulatorStarts, "no emulator is started for a real phone");
        assertEquals(List.of("logcat -v threadtime -s DDLog:I DD:I AndroidRuntime:E ActivityManager:I"), adb.followed);
        assertEquals(List.of(run.resolve("phone-logcat.txt")), adb.followedInto, "into the run's folder");
        assertTrue(Files.readString(run.resolve("phone-logcat.txt")).contains("the phone's log"), "its first line says what it is");
        rig.ensureRunning();
        assertEquals(1, adb.followedCount(), "followed once");
        assertEquals(List.of(false), adb.followClosed);
        rig.close();
        assertEquals(List.of(true), adb.followClosed, "ended when the rig closes");
    }

    @Test
    void theTapIsStartedAgainWhenItEnds(@TempDir Path run) throws Exception {
        adb.followDies = true; // the cable pulled: adb's logcat ends by itself
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), run);
        rig.ensureRunning();
        Waits.waitFor(() -> adb.followedCount() >= 2, 15_000, "the log tap started again after it had ended");
        adb.followDies = false;
        assertTrue(Files.readString(run.resolve("phone-logcat.txt")).contains("followed again"), "and the gap is marked in the file");
        rig.close();
        int after = adb.followedCount();
        Thread.sleep(200);
        assertEquals(after, adb.followedCount(), "not after the rig closed");
    }

    @Test
    void aPhoneThatIsNotThereStopsTheScenarioWithTheReason(@TempDir Path run) {
        adb.says("get-state", "error: device 'BENCHPHONE0001' not found");
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), run);
        IllegalStateException e = assertThrows(IllegalStateException.class, rig::ensureRunning);
        assertTrue(e.getMessage().contains("No phone on adb") && e.getMessage().contains("not found"), e.getMessage());
        adb.says("get-state", "unauthorized");
        assertTrue(assertThrows(IllegalStateException.class, rig::ensureRunning).getMessage().contains("unauthorized"));
        adb.says("get-state", "device").says("shell getprop sys.boot_completed", "");
        assertTrue(assertThrows(IllegalStateException.class, rig::ensureRunning).getMessage().contains("not started through"));
        assertEquals(0, adb.followedCount(), "nothing followed of a phone that is not there");
    }

    // ------------------------------------------------------------------ the one address

    @Test
    void thePhonesLinkPortIsReachedAtItsAddressInAHardwareRunOnly() throws Exception {
        PhoneRig.Target phone = PhoneRigGuardTest.phone(CABLE, BENCH, false);
        PhoneRig rig = rig(phone, null);
        // the fast tests' JVM is no hardware run: the fence's own rule, with no fence up
        assertTrue(assertThrows(IllegalStateException.class, rig::linkAddress).getMessage().contains("no hardware run"));
        assertThrows(IllegalStateException.class, phone::reach);
        try (AutoCloseable back = PhoneRigTargetTest.asAHardwareRun()) {
            InetSocketAddress at = rig.linkAddress();
            assertEquals(phone.link(), at);
            assertEquals(List.of("192.0.2.7:" + PhoneRig.linkOnThePhone()), phone.reach().pairs(), "what a scenario with the Windows app hands its plan");
        }
        assertEquals(List.of(), sent(), "nothing is forwarded: adb's cable carries no UDP");
    }

    @Test
    void sleepOnACableIsPretendedOnlyOnARealPhoneAndCanBeSwitchedOff() {
        PhoneRig rig = rig();
        assertTrue(rig.sleepsOnBattery());
        String before = System.getProperty("bench.phone.onBattery");
        System.setProperty("bench.phone.onBattery", "false");
        try {
            assertFalse(rig.sleepsOnBattery());
        } finally {
            if (before == null) System.clearProperty("bench.phone.onBattery");
            else System.setProperty("bench.phone.onBattery", before);
        }
        adb.says("shell dumpsys deviceidle force-idle", "Unable to go deep idle; stopped at IDLE_PENDING");
        assertFalse(rig.onBattery(), "Android's own word when it could not");
        adb.says("shell dumpsys deviceidle force-idle", "Now forced in to deep idle mode");
        assertTrue(rig.onBattery());
        assertNotNull(rig.target());
    }
}
