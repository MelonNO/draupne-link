package de.droiddrone.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emulator's path through {@link PhoneRig} is what it was before the rig learned of real phones: the same serial
 * and device, and for every step the same adb commands in the same order - written down here from the rig as it was
 * on 2026-11-21 (commit ea36694), where each method sent them itself. The emulator scenarios run every night; a
 * change that alters what they send has to change this test first.
 */
class PhoneRigEmulatorTest {
    private static final String APP = "de.droiddrone.flight.pc";
    private final RecordingAdb adb = new RecordingAdb();
    private final List<Long> pauses = new ArrayList<>();
    private final long[] now = {1_000_000};
    private final PhoneRig rig = new PhoneRig(null, PhoneRig.Target.emulator(), adb, ms -> {
        pauses.add(ms);
        now[0] += ms;
    }, () -> now[0], null);

    /** What was sent since the last call. */
    private int seen;

    private List<String> sent() {
        List<String> s = adb.since(seen);
        seen = adb.count();
        return s;
    }

    @Test
    void theEmulatorIsTheSamePhoneAsEver() {
        PhoneRig.Target t = PhoneRig.Target.emulator();
        assertEquals("emulator-5584", t.serial());
        assertEquals("emulator-5584", PhoneRig.SERIAL);
        assertEquals("draupne-bench", PhoneRig.AVD);
        assertEquals(APP, PhoneRig.APP);
        assertEquals(APP, t.app());
        assertEquals(PhoneRig.Kind.EMULATOR, t.kind());
        assertEquals(EnumSet.allOf(PhoneRig.Capability.class), t.can());
        assertNull(t.link(), "its link port is reached through a forwarded port");
        assertNull(t.apk());
        assertTrue(!t.overTcp() && !rig.device());
        assertEquals("http://10.0.2.2:8080", t.thisPc(8080));
        assertEquals(PhoneRig.Target.emulator(), new PhoneRig(null).target(), "PhoneRig(film) is the emulator's");
    }

    @Test
    void everyStepSendsWhatItAlwaysSent() {
        adb.says("shell getprop sys.boot_completed", "1").says("shell pidof", "4711\n").says("shell dumpsys package", "    versionName=2.3.0-pc.55\n")
                .says("shell dumpsys activity services", "  * ServiceRecord{1 u0 " + APP + "/de.droiddrone.flight.DDService}\n    app=ProcessRecord{2 4711:" + APP + "/u0a1}")
                .says("shell date", "11-21 23:10:05.000\n").says("install", "Performing Streamed Install\nSuccess\n")
                .says("exec-out uiautomator", "<?xml version='1.0'?><hierarchy rotation=\"0\"><node index=\"0\" text=\"START\" resource-id=\"" + APP
                        + ":id/start\" content-desc=\"\" clickable=\"true\" enabled=\"true\" bounds=\"[100,200][300,260]\" /></hierarchy>");

        assertTrue(rig.running());
        assertEquals(List.of("shell getprop sys.boot_completed"), sent());

        rig.install(Path.of("build", "flight", "DRAUPNE-Flight.apk"));
        assertEquals(List.of("install -r " + Path.of("build", "flight", "DRAUPNE-Flight.apk")), sent());
        assertEquals(180L, adb.timeouts.get(adb.count() - 1));

        rig.clearData();
        assertEquals(List.of("shell pm clear " + APP), sent());

        rig.grantPermissions();
        assertEquals(List.of(
                "shell pm grant " + APP + " android.permission.CAMERA",
                "shell pm grant " + APP + " android.permission.RECORD_AUDIO",
                "shell pm grant " + APP + " android.permission.ACCESS_FINE_LOCATION",
                "shell pm grant " + APP + " android.permission.ACCESS_COARSE_LOCATION",
                "shell pm grant " + APP + " android.permission.POST_NOTIFICATIONS",
                "shell dpm set-active-admin --user 0 " + APP + "/de.droiddrone.flight.PowerAdminReceiver",
                "shell locksettings set-disabled false"), sent());

        pauses.clear();
        rig.unlock();
        assertEquals(List.of("shell input keyevent KEYCODE_WAKEUP", "shell wm dismiss-keyguard"), sent());
        assertEquals(List.of(500L), pauses);

        pauses.clear();
        rig.launch();
        assertEquals(List.of("shell input keyevent KEYCODE_WAKEUP", "shell wm dismiss-keyguard", "shell am start -n " + APP + "/de.droiddrone.flight.MainActivity"), sent());
        assertEquals(List.of(500L, 2500L), pauses);

        assertEquals("4711", rig.pid());
        assertEquals(List.of("shell pidof " + APP), sent());

        rig.forceStop();
        assertEquals(List.of("shell am force-stop " + APP), sent());

        assertEquals("2.3.0-pc.55", rig.version());
        assertEquals(List.of("shell dumpsys package " + APP), sent());

        assertTrue(rig.serviceRunning());
        assertEquals(List.of("shell pidof " + APP, "shell dumpsys activity services " + APP), sent());

        rig.log();
        assertEquals(List.of("shell pidof " + APP, "logcat -d --pid=4711 -v time"), sent());

        assertEquals("11-21 23:10:05.000", rig.now());
        assertEquals(List.of("shell date '+%m-%d %H:%M:%S.000'"), sent());

        rig.logSince("11-21 23:10:05.000");
        assertEquals(List.of("shell pidof " + APP, "shell logcat -d --pid=4711 -v time -T '11-21 23:10:05.000'"), sent());

        assertEquals(List.of("START"), rig.texts());
        assertEquals(List.of("exec-out uiautomator dump /dev/tty"), sent());
        assertEquals(40L, adb.timeouts.get(adb.count() - 1));

        pauses.clear();
        rig.tap("START"); // in the middle of [100,200][300,260]
        assertEquals(List.of("exec-out uiautomator dump /dev/tty", "shell input tap 200 230", "exec-out screencap -p"), sent());
        assertEquals(List.of(900L), pauses);

        rig.hold("start", 1200); // by its id
        assertEquals(List.of("exec-out uiautomator dump /dev/tty", "shell input swipe 200 230 200 230 1200", "exec-out screencap -p"), sent());

        rig.back();
        assertEquals(List.of("shell input keyevent KEYCODE_BACK"), sent());

        rig.scrollDown(); // the fixed places of the emulator's own screen, as they are: nothing asked, nothing scaled
        assertEquals(List.of("shell input swipe 540 1700 540 700 300"), sent());

        rig.shot("a picture");
        assertEquals(List.of("exec-out screencap -p"), sent());

        rig.gps(59.5, 10.25);
        assertEquals(List.of("emu geo fix 10.25 59.5"), sent());

        rig.battery(42, false);
        assertEquals(List.of("emu power capacity 42", "emu power ac off", "emu power status discharging"), sent());
        rig.battery(90, true);
        assertEquals(List.of("emu power capacity 90", "emu power ac on", "emu power status charging"), sent());

        rig.aeroplane(true);
        rig.aeroplane(false);
        assertEquals(List.of("shell cmd connectivity airplane-mode enable", "shell cmd connectivity airplane-mode disable"), sent());

        rig.doze(true);
        assertEquals(List.of("shell dumpsys battery unplug", "shell dumpsys deviceidle force-idle"), sent());
        rig.doze(false);
        assertEquals(List.of("shell dumpsys deviceidle unforce", "shell dumpsys battery reset", "shell input keyevent KEYCODE_WAKEUP"), sent());

        int phonePort = PhoneRig.linkOnThePhone();
        int host = rig.forwardUdp(phonePort);
        assertEquals(List.of("emu redir add udp:" + host + ":" + phonePort), sent());
        int second = rig.forwardUdp(phonePort);
        sent();

        rig.close(); // the forwarded ports given back, and nothing else
        assertEquals(List.of("emu redir del udp:" + host, "emu redir del udp:" + second), sent());
        rig.close();
        assertEquals(List.of(), sent());
        assertEquals(List.of(), adb.followed, "no log tap on the emulator");
        assertEquals(List.of(), rig.refusals());
    }

    /** What the scenarios did in their own lines before - adb root, the crash guard's file, kill -9, am crash, ss, install -d - is sent as they sent it. */
    @Test
    void whatTheScenariosSentThemselvesIsSentTheSameThroughTheRig(@TempDir Path build) throws Exception {
        adb.says("shell getprop sys.boot_completed", "1").says("install", "Success").says("shell ss", "State Recv-Q Send-Q");
        Path apk = Files.createDirectories(build.resolve("flight")).resolve("DRAUPNE-Flight-2.3.0-pc.55.apk");
        Files.write(apk, new byte[]{1});
        String before = System.getProperty("bench.build");
        System.setProperty("bench.build", build.toString());
        try {
            // PhoneCrashScenario's and PhoneCameraRaceScenario's opening, line for line
            rig.ensureRunning();
            pauses.clear();
            rig.root();
            assertEquals(List.of(3000L), pauses, "adb root, then 3 s");
            rig.installApp();
            rig.grantPermissions();
            rig.stopForgettingRestarts();
            rig.launch();
            InetSocketAddress link = rig.linkAddress();
            List<String> opening = sent();
            assertEquals("shell getprop sys.boot_completed", opening.get(0));
            assertEquals("root", opening.get(1));
            assertEquals("install -r " + apk, opening.get(2));
            assertEquals("shell pm grant " + APP + " android.permission.CAMERA", opening.get(3));
            assertEquals("shell locksettings set-disabled false", opening.get(9));
            assertEquals(List.of("shell am force-stop " + APP, "shell rm -f /data/data/" + APP + "/shared_prefs/crashGuard.xml",
                    "shell input keyevent KEYCODE_WAKEUP", "shell wm dismiss-keyguard", "shell am start -n " + APP + "/de.droiddrone.flight.MainActivity",
                    "emu redir add udp:" + link.getPort() + ":" + PhoneRig.linkOnThePhone()), opening.subList(10, opening.size()));
            assertTrue(link.getAddress().isLoopbackAddress());
            assertTrue(rig.target().reach().isEmpty(), "the fence needs no way through for the emulator: its plan's reach is none, as ever");
        } finally {
            if (before == null) System.clearProperty("bench.build");
            else System.setProperty("bench.build", before);
        }

        rig.crash();
        assertEquals(List.of("shell am crash " + APP), sent());
        rig.endProcess("4711");
        assertEquals(List.of("shell kill -9 4711"), sent());
        assertEquals("State Recv-Q Send-Q", rig.socketStats());
        assertEquals(List.of("shell ss -u -a -m 2>&1"), sent());
        rig.installAnyVersion(Path.of("receiver.apk"));
        assertEquals(List.of("install -r -d receiver.apk"), sent());
        assertEquals(180L, adb.timeouts.get(adb.count() - 1));
        rig.reachesThisPc(8080); // 10.0.2.2 needs nothing
        rig.checkBattery();      // a real phone's only
        assertTrue(!rig.sleepsOnBattery());
        assertEquals(List.of(), sent());

        // what the scenarios read off the screen, since a real phone may have a lock screen over it (2026-11-22): on the
        // emulator each is the same look at the screen it always was, with the same words - and nothing else is asked
        adb.says("exec-out uiautomator", RecordingAdb.screen(APP, "START", "2.3.0-pc.55 · for DRAUPNE Link", "Connected to PC"));
        rig.untilAppIsUp(15_000); // was: phone.untilSees("for DRAUPNE Link", 15_000)
        assertEquals(List.of("exec-out uiautomator dump /dev/tty"), sent());
        pauses.clear();
        rig.startIfAsked();       // was: if (phone.texts().contains("START")) phone.tap("START")
        assertEquals(List.of("exec-out uiautomator dump /dev/tty", "exec-out uiautomator dump /dev/tty", "shell input tap 360 240", "exec-out screencap -p"), sent());
        assertEquals(List.of(900L), pauses);
        List<String> checks = new ArrayList<>();
        rig.checkConnectedToPc((ok, what) -> checks.add(ok + " " + what), "the phone's screen says: Connected to PC"); // was: rig.check(phone.sees("Connected to PC"), ...)
        assertEquals(List.of("exec-out uiautomator dump /dev/tty"), sent());
        assertEquals(List.of("true the phone's screen says: Connected to PC"), checks);
        adb.says("exec-out uiautomator", RecordingAdb.screen(APP, "2.3.0-pc.55 · for DRAUPNE Link", "Waiting for a PC"));
        rig.startIfAsked();       // no START showing: looked, nothing pressed
        rig.checkConnectedToPc((ok, what) -> checks.add(ok + " " + what), "again");
        assertEquals(List.of("exec-out uiautomator dump /dev/tty", "exec-out uiautomator dump /dev/tty"), sent());
        assertEquals("false again", checks.get(1));
        // and what is a real phone's alone asks nothing of the emulator
        rig.lookOverTheLock("never on the emulator");
        assertTrue(rig.screenIsTheApps());
        assertEquals(PhoneRig.Lock.UNLOCKED, rig.lock());
        assertEquals(List.of(), sent());
        assertEquals(List.of(), rig.skipped());
        assertEquals(List.of(), rig.otherWays());
        assertEquals("for DRAUPNE Link", PhoneWords.SCREEN_TITLE);
        assertEquals("START", PhoneWords.SCREEN_START);
        assertEquals("Connected to PC", PhoneWords.SCREEN_CONNECTED);
    }

    @Test
    void theEmulatorIsStartedAsBeforeWhenItIsNotRunning(@TempDir Path run) throws Exception {
        adb.says("shell getprop sys.boot_completed", "", "", "1");
        String before = System.getProperty("bench.root");
        System.setProperty("bench.root", run.toString());
        try {
            rig.ensureRunning();
        } finally {
            if (before == null) System.clearProperty("bench.root");
            else System.setProperty("bench.root", before);
        }
        assertEquals(List.of("-avd draupne-bench -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -camera-back emulated -camera-front emulated -no-snapshot -port 5584"),
                adb.emulatorStarts);
        assertEquals(List.of("shell getprop sys.boot_completed", "shell getprop sys.boot_completed", "shell getprop sys.boot_completed"), sent());
        assertEquals(List.of(3000L, 4000L), pauses, "asked every 3 s, then Android's own first 4 s");
        rig.ensureRunning(); // running: nothing started again
        assertEquals(1, adb.emulatorStarts.size());
    }

    /** No guard, no battery watch, no putting back that was not there before - however long a scenario takes. */
    @Test
    void nothingOfARealPhonesRulesTouchesTheEmulator() {
        now[0] += 10 * 3_600_000L;
        rig.shell("kill -9 1");
        rig.adb("root");
        rig.shell("locksettings set-disabled false");
        now[0] += 10 * 3_600_000L;
        rig.shell("svc wifi disable");
        assertEquals(List.of("shell kill -9 1", "root", "shell locksettings set-disabled false", "shell svc wifi disable"), sent());
        rig.close();
        assertEquals(List.of(), sent(), "close sends nothing when no port was forwarded");
        // a doze the scenario did not end is the one thing close now takes back on the emulator too
        rig.doze(true);
        sent();
        rig.close();
        assertEquals(List.of("shell dumpsys deviceidle unforce", "shell dumpsys battery reset"), sent());
    }
}
