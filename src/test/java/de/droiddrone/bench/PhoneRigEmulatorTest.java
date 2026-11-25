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
 * <p>
 * Q-006 (2.3.0-pc.58, the phone answers over Tailscale only; the emulator has none): the package flown there is the
 * bench build's, whose link takes every address in the emulator alone. Its APK comes from build/flight-bench and is
 * installed with -t (it is test-only); before it, the other package (the released app's) is stopped, as both want the
 * link port. The update test flies the released app's package there ({@link PhoneRig.Target#flying}).
 * <p>
 * 2026-11-25: one command more when a rig finds the emulator running - its uptime ({@code cat /proc/uptime}), once a
 * rig; one that has run too long is ended and started anew as a cold start starts it.
 */
class PhoneRigEmulatorTest {
    /** The package flown on the emulator since Q-006: the bench build's. */
    private static final String APP = "de.droiddrone.flight.pc.bench";
    private static final String RELEASED = "de.droiddrone.flight.pc";
    /** The emulator's arguments when the rig starts it, as ever. */
    private static final String COLD = "-avd draupne-bench -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -camera-back emulated -camera-front emulated -no-snapshot -port 5584";
    private static final String BOOTED = "shell getprop sys.boot_completed", UPTIME = "shell cat /proc/uptime";
    private final RecordingAdb adb = new RecordingAdb();
    private final List<Long> pauses = new ArrayList<>();
    private final long[] now = {1_000_000};
    /** The rig the scenarios get, with the default limit whatever this PC's environment says. */
    private final PhoneRig rig = rig(PhoneRig.EMULATOR_MAX_HOURS, null);

    /** A rig on the emulator that may have run that many hours, its lines into that film (null: none). */
    private PhoneRig rig(double maxHours, Film film) {
        return new PhoneRig(film, PhoneRig.Target.emulator(), adb, ms -> {
            pauses.add(ms);
            now[0] += ms;
        }, () -> now[0], null, maxHours);
    }

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
        assertEquals(RELEASED, PhoneRig.APP);
        assertEquals(APP, t.app(), "the bench build (Q-006)");
        assertTrue(t.benchBuild());
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
        assertEquals(List.of("install -r -t " + Path.of("build", "flight", "DRAUPNE-Flight.apk")), sent());
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
                "shell pm grant " + APP + " android.permission.WRITE_EXTERNAL_STORAGE", // Android 8 and 9 (2026-11-22)
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
        assertEquals(List.of("emu redir add udp:" + host + ":" + phonePort, "root", "shell ip netns list", "shell ip -4 -o addr show"), sent()); // no router of its own, nothing to give its card: nothing more
        int second = rig.forwardUdp(phonePort);
        assertEquals(List.of("emu redir add udp:" + second + ":" + phonePort), sent(), "looked once");

        rig.close(); // the forwarded ports given back, and nothing else
        assertEquals(List.of("emu redir del udp:" + host, "emu redir del udp:" + second), sent());
        rig.close();
        assertEquals(List.of(), sent());
        assertEquals(List.of(), adb.followed, "no log tap on the emulator");
        assertEquals(List.of(), rig.refusals());
    }

    /**
     * Android 8 to 10's emulator (2026-11-22, draupne-a26): Android behind the emulator's own router - the forwarded
     * port is led on through it to the phone's Wi-Fi address, once; a rule already there is not added again.
     */
    @Test
    void anOlderEmulatorsOwnRouterLetsTheForwardedPortThrough() {
        adb.says("shell ip netns list", "router\n").says("shell ip -4 -o addr show wlan0", "3: wlan0    inet 192.168.232.2/21 brd 192.168.239.255 scope global wlan0\n")
                .says("shell ip netns exec router iptables -t nat -C", "iptables: Bad rule (does a matching rule exist in that chain?).\nrc=1\n");
        int phonePort = PhoneRig.linkOnThePhone();
        int host = rig.forwardUdp(phonePort);
        String rule = "PREROUTING -i eth0 -p udp --dport " + phonePort + " -j DNAT --to-destination 192.168.232.2:" + phonePort;
        assertEquals(List.of("emu redir add udp:" + host + ":" + phonePort, "root", "shell ip netns list", "shell ip -4 -o addr show wlan0",
                "shell ip netns exec router iptables -t nat -C " + rule + " 2>&1; echo rc=$?", "shell ip netns exec router iptables -t nat -A " + rule), sent());
        int second = rig.forwardUdp(phonePort);
        assertEquals(List.of("emu redir add udp:" + second + ":" + phonePort), sent(), "led through once");

        // a rig of its own on the same emulator (the next scenario): the rule the router has is not added again
        PhoneRig next = new PhoneRig(null, PhoneRig.Target.emulator(), adb, pauses::add, () -> now[0], null);
        adb.says("shell ip netns exec router iptables -t nat -C", "rc=0\n");
        int third = next.forwardUdp(phonePort);
        assertEquals(List.of("emu redir add udp:" + third + ":" + phonePort, "root", "shell ip netns list", "shell ip -4 -o addr show wlan0",
                "shell ip netns exec router iptables -t nat -C " + rule + " 2>&1; echo rc=$?"), sent());
    }

    /** What {@code ip -4 -o addr show} said on the Android 15 emulator (2026-11-22, draupne-a35): its Wi-Fi's address only. */
    private static final String A35_ADDRESSES = "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever\n"
            + "16: wlan0    inet 10.0.2.16/24 brd 10.0.2.255 scope global wlan0\\       valid_lft forever preferred_lft forever\n";

    /**
     * Android 15's emulator (2026-11-22, draupne-a35): no card has the address the console forwards to - its mobile
     * data, on the emulator's own card, never comes up - so that card is given it, once. Where a card has it (the
     * other images; the next scenario's rig on the same emulator) nothing more is sent.
     */
    @Test
    void anAndroid15EmulatorsOwnCardIsGivenTheAddressTheForwardEndsAt() {
        adb.says("shell ip -4 -o addr show", A35_ADDRESSES).says("shell ip link set eth0 up", "rc=0\n");
        int phonePort = PhoneRig.linkOnThePhone();
        int host = rig.forwardUdp(phonePort);
        assertEquals(List.of("emu redir add udp:" + host + ":" + phonePort, "root", "shell ip netns list", "shell ip -4 -o addr show",
                "shell ip link set eth0 up 2>&1 && ip addr add 10.0.2.15/24 dev eth0 2>&1; echo rc=$?"), sent());
        int second = rig.forwardUdp(phonePort);
        assertEquals(List.of("emu redir add udp:" + second + ":" + phonePort), sent(), "given once");

        PhoneRig next = new PhoneRig(null, PhoneRig.Target.emulator(), adb, pauses::add, () -> now[0], null);
        adb.says("shell ip -4 -o addr show", "15: eth0    inet 10.0.2.15/24 scope global eth0\\       valid_lft forever preferred_lft forever\n" + A35_ADDRESSES);
        int third = next.forwardUdp(phonePort);
        assertEquals(List.of("emu redir add udp:" + third + ":" + phonePort, "root", "shell ip netns list", "shell ip -4 -o addr show"), sent(), "the card has it now");

        // a card that cannot be given it (no eth0): said in the film, the scenario goes on
        PhoneRig without = new PhoneRig(null, PhoneRig.Target.emulator(), adb, pauses::add, () -> now[0], null);
        adb.says("shell ip -4 -o addr show", A35_ADDRESSES).says("shell ip link set eth0 up", "Cannot find device \"eth0\"\nrc=1\n");
        int fourth = without.forwardUdp(phonePort);
        assertEquals(5, sent().size(), "looked, tried once");
        assertTrue(fourth > 0);
    }

    /** What the scenarios did in their own lines before - adb root, the crash guard's file, kill -9, am crash, ss, install -d - is sent as they sent it. */
    @Test
    void whatTheScenariosSentThemselvesIsSentTheSameThroughTheRig(@TempDir Path build) throws Exception {
        adb.says("shell getprop sys.boot_completed", "1").says("install", "Success").says("shell ss", "State Recv-Q Send-Q")
                .says(UPTIME, "3600.12 14000.50\n"); // up an hour: used as it is
        Path apk = Files.createDirectories(build.resolve("flight-bench")).resolve("DRAUPNE-Flight-2.3.0-pc.58-beta-bench.apk");
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
            assertEquals(UPTIME, opening.get(1), "its uptime, once (2026-11-25)");
            assertEquals("root", opening.get(2));
            assertEquals("shell am force-stop " + RELEASED, opening.get(3), "the other package gives up the link port (Q-006)");
            assertEquals("install -r -t " + apk, opening.get(4));
            assertEquals("shell pm grant " + APP + " android.permission.CAMERA", opening.get(5));
            assertEquals("shell locksettings set-disabled false", opening.get(12)); // the grants: one more since Android 8's storage (2026-11-22)
            assertEquals(List.of("shell am force-stop " + APP, "shell rm -f /data/data/" + APP + "/shared_prefs/crashGuard.xml",
                    "shell input keyevent KEYCODE_WAKEUP", "shell wm dismiss-keyguard", "shell am start -n " + APP + "/de.droiddrone.flight.MainActivity",
                    "emu redir add udp:" + link.getPort() + ":" + PhoneRig.linkOnThePhone(),
                    "root", "shell ip netns list", "shell ip -4 -o addr show"), opening.subList(13, opening.size())); // no router of its own (Android 11 on): the card's address looked for, nothing more
            assertEquals(List.of(), adb.emulatorStarts, "up an hour: used as it is");
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

    /**
     * Q-006: the update test flies the released app's package on the emulator (its two test builds take every address
     * there, as the bench build does): the normal build from build/flight, the bench build stopped first.
     */
    @Test
    void theUpdateTestFliesTheReleasedAppsPackageAndTheBenchBuildGivesUpThePort(@TempDir Path build) throws Exception {
        PhoneRig.Target released = PhoneRig.Target.emulator().flying(PhoneRig.APP);
        assertEquals(RELEASED, released.app());
        assertTrue(!released.benchBuild());
        assertEquals(PhoneRig.Target.emulator().can(), released.can(), "the same emulator, every capability as ever");
        PhoneRig update = new PhoneRig(null, released, adb, pauses::add, () -> now[0], null);
        adb.says("install", "Success");
        Path normal = Files.createDirectories(build.resolve("flight")).resolve("DRAUPNE-Flight-2.3.0-pc.58-beta.apk");
        Files.write(normal, new byte[]{1});
        String before = System.getProperty("bench.build");
        System.setProperty("bench.build", build.toString());
        try {
            assertEquals(normal, update.appApk());
            update.installApp();
            assertEquals(List.of("shell am force-stop " + APP, "install -r -t " + normal), sent());
            update.stopTheOther();
            assertEquals(List.of("shell am force-stop " + APP), sent());
            rig.stopTheOther();
            assertEquals(List.of("shell am force-stop " + RELEASED), sent());
            // no bench build yet: said how to make one
            String said = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, PhoneRig::benchApk).getMessage();
            assertTrue(said.contains("flight-app\\build.ps1 -Bench"), said);
        } finally {
            if (before == null) System.clearProperty("bench.build");
            else System.setProperty("bench.build", before);
        }
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
        assertEquals(List.of(COLD), adb.emulatorStarts);
        assertEquals(List.of("shell getprop sys.boot_completed", "shell getprop sys.boot_completed", "shell getprop sys.boot_completed"), sent());
        assertEquals(List.of(3000L, 4000L), pauses, "asked every 3 s, then Android's own first 4 s");
        rig.ensureRunning(); // running: nothing started again
        assertEquals(1, adb.emulatorStarts.size());
        assertEquals(List.of(BOOTED), sent(), "started by this rig: its uptime not asked");
    }

    // ------------------------------------------------------------------ an emulator that has run too long (2026-11-25)

    private interface Step {
        void run() throws Exception;
    }

    /** As a bench task's JVM: the run's folder set, where the emulator's own output goes. */
    private static void inARun(Path run, Step step) throws Exception {
        String before = System.getProperty("bench.root");
        System.setProperty("bench.root", run.toString());
        try {
            step.run();
        } finally {
            if (before == null) System.clearProperty("bench.root");
            else System.setProperty("bench.root", before);
        }
    }

    private static String remarks(Film film) throws Exception {
        Path file = film.dir().resolve("remarks.txt");
        return Files.exists(file) ? Files.readString(file) : "";
    }

    /** (1) Up 34.2 hours, over 6: ended as bench.ps1 ends one, until adb no longer has it, then started as a cold start starts it - once a rig. */
    @Test
    void anEmulatorThatHasRunTooLongIsEndedAndStartedAnewAsEver(@TempDir Path run) throws Exception {
        Film film = new Film(run.resolve("film"), "aged");
        adb.says(BOOTED, "1", "", "1").says(UPTIME, "123120.55 480000.10\n").says("emu avd name", "draupne-bench\nOK\n")
                .says("emu kill", "OK: killing emulator, bye bye\n").says("get-state", "device", "device", "error: device 'emulator-5584' not found");
        PhoneRig aged = rig(6, film);
        inARun(run, aged::ensureRunning);
        assertEquals(List.of(BOOTED, UPTIME, "emu avd name", "emu kill", "get-state", "get-state", "get-state", BOOTED, BOOTED), sent());
        assertEquals(List.of(COLD), adb.emulatorStarts, "the same arguments as a cold start");
        assertEquals(List.of(2000L, 2000L, 3000L, 3000L, 4000L), pauses, "gone after 4 s, 3 s for its ports, then started as ever");
        assertEquals("The emulator had run 34.2 h: started anew (over 6 h).\n", remarks(film));

        inARun(run, aged::ensureRunning); // once a rig
        assertEquals(List.of(BOOTED), sent());
        assertEquals(1, adb.emulatorStarts.size());
    }

    /** One that does not end after emu kill stops the scenario with the reason: nothing is started over it. */
    @Test
    void anEmulatorThatDoesNotEndIsNotStartedOver(@TempDir Path run) throws Exception {
        adb.says(BOOTED, "1").says(UPTIME, "123120.55 480000.10").says("emu avd name", "draupne-bench\nOK").says("get-state", "device");
        PhoneRig aged = rig(6, new Film(run.resolve("film"), "stuck"));
        String said = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> inARun(run, aged::ensureRunning)).getMessage();
        assertTrue(said.contains("did not end within 90 s"), said);
        assertEquals(List.of(), adb.emulatorStarts);
    }

    /** Another Android device on the port (a run with -Avd) is started anew as that device, not as draupne-bench. */
    @Test
    void anotherAndroidDeviceIsStartedAnewAsItself(@TempDir Path run) throws Exception {
        Film film = new Film(run.resolve("film"), "a26");
        adb.says(BOOTED, "1", "1").says(UPTIME, "25200.00 90000.00").says("emu avd name", "draupne-a26\r\nOK");
        inARun(run, rig(6, film)::ensureRunning);
        assertEquals(List.of(COLD.replace("draupne-bench", "draupne-a26")), adb.emulatorStarts);
        assertEquals("The emulator (draupne-a26) had run 7.0 h: started anew (over 6 h).\n", remarks(film));
    }

    /** (2) Up under the limit: used as it is - nothing ended, nothing started, nothing said. */
    @Test
    void anEmulatorUnderTheLimitIsUsedAsItIs(@TempDir Path run) throws Exception {
        Film film = new Film(run.resolve("film"), "young");
        adb.says(BOOTED, "1").says(UPTIME, "21540.00 80000.00\n"); // 5.98 h
        PhoneRig young = rig(6, film);
        young.ensureRunning();
        young.ensureRunning();
        assertEquals(List.of(BOOTED, UPTIME, BOOTED), sent(), "its uptime asked once");
        assertEquals(List.of(), adb.emulatorStarts);
        assertEquals(List.of(), pauses);
        assertEquals("", remarks(film));
    }

    /** (3) A limit of 0: never started anew, its uptime not even asked. The run's word read as a number of 0 or more, else the default. */
    @Test
    void aLimitOfNoneNeverStartsItAnew(@TempDir Path run) throws Exception {
        Film film = new Film(run.resolve("film"), "never");
        adb.says(BOOTED, "1").says(UPTIME, "360000.00 1400000.00\n"); // 100 h
        rig(0, film).ensureRunning();
        assertEquals(List.of(BOOTED), sent());
        assertEquals(List.of(), adb.emulatorStarts);
        assertEquals("", remarks(film));

        assertEquals(0, PhoneRig.emulatorMaxHours("0"));
        assertEquals(12, PhoneRig.emulatorMaxHours(" 12 "));
        assertEquals(0.5, PhoneRig.emulatorMaxHours("0.5"));
        for (String word : java.util.Arrays.asList(null, "", " ", "x", "-1", "NaN", "Infinity")) {
            assertEquals(6, PhoneRig.emulatorMaxHours(word), "\"" + word + "\"");
        }
        assertEquals(6, PhoneRig.EMULATOR_MAX_HOURS);
    }

    /** (4) A real phone - or the emulator held to a real phone's rules (BENCH_PHONE_ADB=emulator-5584) - is never asked its uptime, never ended or started. */
    @Test
    void aRealPhoneIsNeverStartedAnewWhateverItsUptime(@TempDir Path run) throws Exception {
        PhoneRig.Target cable = PhoneRigGuardTest.phone(PhoneRigGuardTest.CABLE, APP, false);
        PhoneRig.Target rehearsal = new PhoneRig.Target(PhoneRig.SERIAL, PhoneRig.Kind.DEVICE, APP, null, EnumSet.of(PhoneRig.Capability.UDP_REDIR), false, null, null, null);
        for (PhoneRig.Target phone : List.of(cable, rehearsal)) {
            RecordingAdb real = RecordingAdb.aPhone().says(UPTIME, "360000.00 1400000.00\n"); // 100 h
            PhoneRig r = new PhoneRig(null, phone, real, ms -> now[0] += ms, () -> now[0], run.resolve(phone.serial()), 6);
            try {
                inARun(run, r::ensureRunning);
            } finally {
                r.close();
            }
            assertTrue(real.sent.stream().noneMatch(c -> c.contains("uptime") || c.startsWith("emu")), phone.serial() + ": " + real.sent);
            assertEquals(List.of(), real.emulatorStarts, phone.serial());
            assertTrue(real.sent.contains("get-state"), "found as a real phone is found");
        }
    }

    /** (5) An uptime that cannot be read - adb's error, or adb not answering - leaves the emulator as it is, said in one line. */
    @Test
    void anUptimeThatCannotBeReadLeavesTheEmulatorAsItIs(@TempDir Path run) throws Exception {
        Film film = new Film(run.resolve("film"), "unread");
        adb.says(BOOTED, "1").says(UPTIME, "error: closed\n");
        rig(6, film).ensureRunning();
        assertEquals(List.of(BOOTED, UPTIME), sent());
        assertEquals(List.of(), adb.emulatorStarts);
        assertEquals(List.of(), pauses);
        assertEquals("The emulator's uptime could not be read (adb said: error: closed): used as it is.\n", remarks(film));

        Film gone = new Film(run.resolve("gone"), "gone");
        RecordingAdb silent = new RecordingAdb().says(BOOTED, "1").fails(UPTIME);
        new PhoneRig(gone, PhoneRig.Target.emulator(), silent, pauses::add, () -> now[0], null, 6).ensureRunning();
        assertEquals(List.of(BOOTED, UPTIME), silent.sent);
        assertEquals(List.of(), silent.emulatorStarts);
        String said = remarks(gone);
        assertEquals(1, said.lines().count(), said);
        assertTrue(said.startsWith("The emulator's uptime could not be read (adb said: adb shell cat /proc/uptime did not end within 60 s): used as it is."), said);
    }

    /** What /proc/uptime and the console say, as the rig reads them. */
    @Test
    void theUptimeAndTheDevicesNameAreReadAsAndroidAndTheConsoleWriteThem() {
        assertEquals(34.2, PhoneRig.uptimeHours("123120.55 480000.10"), 0.001);
        assertEquals(1.0, PhoneRig.uptimeHours("* daemon started successfully\n3600 7000\n"), 0.001, "adb's own first lines passed over");
        for (String odd : java.util.Arrays.asList(null, "", "error: device offline", "12.5", "uptime: 12 34")) assertNull(PhoneRig.uptimeHours(odd), odd);
        assertEquals("draupne-bench", PhoneRig.avdName("draupne-bench\nOK"));
        assertEquals("draupne-a35", PhoneRig.avdName("draupne-a35\r\nOK\r\n"));
        for (String odd : java.util.Arrays.asList(null, "", "OK", "KO: unknown command", "error: device 'emulator-5584' not found", "Android Console: Authentication required")) {
            assertNull(PhoneRig.avdName(odd), odd);
        }
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
