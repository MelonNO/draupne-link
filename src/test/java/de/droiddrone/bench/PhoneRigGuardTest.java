package de.droiddrone.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules for a real phone ({@link PhoneGuard}), proved without one: a recorder stands where adb is. What must
 * never reach a phone that flies is refused before adb is asked - and said, not passed over; the same command for
 * the bench build, or on the emulator, goes through.
 */
class PhoneRigGuardTest {
    static final String RELEASED = PhoneRig.APP, BENCH = BenchControl.PACKAGE;
    /** A made-up serial of a phone on its cable, and one of a phone reached over the network (an address nobody has). */
    static final String CABLE = "BENCHPHONE0001", NETWORK = "192.0.2.7:5555";

    static PhoneRig.Target phone(String serial, String app, boolean releasedAppMayBeChanged) {
        try {
            InetSocketAddress link = new InetSocketAddress(InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 7}), PhoneRig.linkOnThePhone());
            return new PhoneRig.Target(serial, PhoneRig.Kind.DEVICE, app, link, EnumSet.noneOf(PhoneRig.Capability.class), releasedAppMayBeChanged, null, null, null);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    static PhoneRig rig(PhoneRig.Target target, RecordingAdb adb) {
        long[] now = {1_000_000};
        return new PhoneRig(null, target, adb, ms -> now[0] += ms, () -> now[0], null);
    }

    /** The command is refused on that phone: nothing reaches adb, and the refusal says why. */
    private static String refused(PhoneRig.Target target, String... command) {
        RecordingAdb adb = RecordingAdb.aPhone();
        PhoneRig rig = rig(target, adb);
        PhoneRig.Refused r = assertThrows(PhoneRig.Refused.class, () -> rig.adbBytes(60, command), String.join(" ", command));
        assertEquals(List.of(), adb.sent, "nothing was sent: " + String.join(" ", command));
        assertEquals(List.of(r.getMessage()), rig.refusals(), "and the refusal is kept");
        assertTrue(r.getMessage().startsWith("Not sent to the real phone: adb " + String.join(" ", command) + " - "), r.getMessage());
        return r.getMessage();
    }

    /** The command goes to adb as it is. */
    private static void sent(PhoneRig.Target target, String... command) {
        RecordingAdb adb = RecordingAdb.aPhone();
        PhoneRig rig = rig(target, adb);
        rig.adbBytes(60, command);
        assertEquals(List.of(String.join(" ", command)), adb.sent);
        assertEquals(List.of(), rig.refusals());
    }

    private static final List<String[]> NEVER = List.of(
            new String[]{"shell", "locksettings set-disabled false"},
            new String[]{"shell", "locksettings clear --old 1234"},
            new String[]{"root"},
            new String[]{"unroot"},
            new String[]{"shell", "su -c id"},
            new String[]{"shell", "su 0 rm -f /data/data/" + BENCH + "/shared_prefs/crashGuard.xml"},
            new String[]{"shell", "getprop ro.build.type; su"},
            new String[]{"shell", "dumpsys window | su -c cat"},
            new String[]{"install", "-r", "-d", "some.apk"},
            new String[]{"install", "-d", "some.apk"},
            new String[]{"shell", "pm install -d /data/local/tmp/some.apk"},
            new String[]{"shell", "pm install -r /data/local/tmp/some.apk"},
            new String[]{"shell", "cmd package install -d /data/local/tmp/some.apk"},
            new String[]{"shell", "kill -9 1234"},
            new String[]{"shell", "rm -f /data/data/" + BENCH + "/shared_prefs/crashGuard.xml"},
            new String[]{"shell", "rm -rf /sdcard/DCIM"},
            new String[]{"shell", "echo 1 > /sdcard/x.txt"},
            new String[]{"shell", "dumpsys battery >> /sdcard/b.txt"},
            new String[]{"shell", "screencap -p /sdcard/s.png"},
            new String[]{"shell", "logcat -d -f /sdcard/log.txt"},
            new String[]{"shell", "logcat -c"},
            new String[]{"shell", "logcat -v threadtime"},
            new String[]{"shell", "date 112112002026"},
            new String[]{"shell", "wm size 720x1280"},
            new String[]{"shell", "wm density 320"},
            new String[]{"shell", "settings put global airplane_mode_on 1"},
            new String[]{"shell", "setprop persist.sys.usb.config none"},
            new String[]{"shell", "reboot"},
            new String[]{"reboot"},
            new String[]{"shell", "svc power shutdown"},
            new String[]{"shell", "svc usb setFunctions"},
            new String[]{"shell", "dumpsys battery set level 5"},
            new String[]{"shell", "cmd battery set status 3"},
            new String[]{"shell", "dumpsys deviceidle whitelist +" + BENCH},
            new String[]{"shell", "dumpsys package write"},
            new String[]{"shell", "dumpsys batterystats --reset"},
            new String[]{"shell", "am start -n com.android.settings/.Settings"},
            new String[]{"shell", "am start -a android.intent.action.FACTORY_RESET"},
            new String[]{"shell", "am broadcast -a android.intent.action.BOOT_COMPLETED"},
            new String[]{"shell", "am broadcast -n " + RELEASED + "/de.droiddrone.flight.AppUpdateReceiver -a x"},
            new String[]{"shell", "am broadcast -n " + BenchControl.component(BENCH) + " -a android.intent.action.MASTER_CLEAR"},
            new String[]{"shell", "am crash 1234"},
            new String[]{"shell", "dpm set-device-owner " + BENCH + "/de.droiddrone.flight.PowerAdminReceiver"},
            new String[]{"shell", "pm clear com.android.providers.settings"},
            new String[]{"shell", "pm uninstall com.android.chrome"},
            new String[]{"shell", "am force-stop com.example.vpn"},
            new String[]{"uninstall", "com.example.vpn"},
            new String[]{"shell", "cmd wifi start-scan"},
            new String[]{"shell", "content delete --uri content://settings/system"},
            new String[]{"shell", "cat /data/data/" + RELEASED + "/shared_prefs/settings.xml"},
            new String[]{"shell", "getprop $(su)"},
            new String[]{"shell", "getprop `reboot`"},
            new String[]{"shell", "echo \"$(reboot)\""},
            new String[]{"shell", "wc -l < /sdcard/x"},
            new String[]{"shell"},
            new String[]{"emu", "geo", "fix", "10", "60"},
            new String[]{"emu", "power", "capacity", "5"},
            new String[]{"emu", "redir", "add", "udp:5000:6000"},
            new String[]{"emu", "kill"},
            new String[]{"push", "x", "/sdcard/x"},
            new String[]{"tcpip", "5555"},
            new String[]{"forward", "tcp:1", "tcp:2"},
            new String[]{"reverse", "tcp:8000", "localabstract:x"});

    @Test
    void whatIsNeverSentToARealPhone() {
        for (String[] command : NEVER) {
            refused(phone(CABLE, BENCH, false), command);
            refused(phone(CABLE, RELEASED, true), command); // nor with "the released app may be changed": that word is about one app, not the phone
        }
        // each of the named ones says what it is about
        assertTrue(refused(phone(CABLE, BENCH, false), "shell", "locksettings set-disabled false").contains("lock screen"));
        assertTrue(refused(phone(CABLE, BENCH, false), "root").contains("never sent to a real phone"));
        assertTrue(refused(phone(CABLE, BENCH, false), "shell", "su -c id").contains("su is never"));
        assertTrue(refused(phone(CABLE, BENCH, false), "install", "-r", "-d", "some.apk").contains("a version down"));
        assertTrue(refused(phone(CABLE, BENCH, false), "shell", "kill -9 1234").contains("bench build's receiver"));
        assertTrue(refused(phone(CABLE, BENCH, false), "shell", "frobnicate now").contains("not on the list"));
    }

    @Test
    void theSameCommandsAllGoThroughOnTheEmulator() {
        for (String[] command : NEVER) sent(PhoneRig.Target.emulator(), command);
        for (String[] command : NETWORK_OFF) sent(PhoneRig.Target.emulator(), command);
        for (String app : List.of(RELEASED, BENCH, "com.android.chrome")) for (String[] command : changes(app)) sent(PhoneRig.Target.emulator(), command);
    }

    /** What changes an app: its data, its permissions, whether it runs. */
    private static List<String[]> changes(String app) {
        return List.of(
                new String[]{"shell", "pm clear " + app},
                new String[]{"shell", "pm clear --user 0 " + app},
                new String[]{"shell", "pm grant " + app + " android.permission.CAMERA"},
                new String[]{"shell", "pm revoke " + app + " android.permission.CAMERA"},
                new String[]{"shell", "pm uninstall " + app},
                new String[]{"shell", "cmd package clear " + app},
                new String[]{"uninstall", app},
                new String[]{"shell", "appops set " + app + " REQUEST_INSTALL_PACKAGES allow"},
                new String[]{"shell", "cmd appops set " + app + " REQUEST_INSTALL_PACKAGES default"},
                new String[]{"shell", "dpm set-active-admin --user 0 " + app + "/de.droiddrone.flight.PowerAdminReceiver"},
                new String[]{"shell", "dpm remove-active-admin " + app + "/de.droiddrone.flight.PowerAdminReceiver"},
                new String[]{"shell", "am force-stop " + app},
                new String[]{"shell", "am kill " + app},
                new String[]{"shell", "am crash " + app},
                new String[]{"shell", "am crash --user 0 " + app},
                new String[]{"shell", "getprop sys.boot_completed && am force-stop " + app});
    }

    @Test
    void theReleasedAppIsChangedOnlyWithTheRunsOwnWord() {
        for (String[] command : changes(RELEASED)) {
            String why = refused(phone(CABLE, BENCH, false), command);
            assertTrue(why.contains("would change the released app") && why.contains("releasedAppMayBeChanged"), why);
            refused(phone(CABLE, RELEASED, false), command); // nor when the released app is the one the run flies
            sent(phone(CABLE, RELEASED, true), command);     // with the run's word: yes
            sent(phone(CABLE, BENCH, true), command);
        }
    }

    @Test
    void theBenchBuildIsTheBenchsToChange() {
        for (String[] command : changes(BENCH)) {
            sent(phone(CABLE, BENCH, false), command);
            sent(phone(NETWORK, BENCH, false), command);
        }
        for (String action : BenchControl.ACTIONS) {
            sent(phone(CABLE, BENCH, false), "shell", "am broadcast -n " + BenchControl.component(BENCH) + " -a " + action);
            sent(phone(CABLE, BENCH, false), "shell", "am broadcast --include-stopped-packages -n " + BenchControl.component(BENCH) + " -a " + action);
        }
        sent(phone(CABLE, BENCH, false), "shell", "am broadcast -n " + BenchControl.component(BENCH) + " -a " + BenchControl.CRASH + " --es thread main");
    }

    @Test
    void noOtherAppIsEverChanged() {
        for (String[] command : changes("com.android.chrome")) {
            assertTrue(refused(phone(CABLE, BENCH, true), command).contains("no other app"));
        }
    }

    @Test
    void anApkIsInstalledOnlyWhenItsPackageMayBeChanged(@TempDir Path dir) throws Exception {
        Path bench = PhoneRigApkPackageTest.apk(dir.resolve("bench.apk"), BENCH, true);
        Path released = PhoneRigApkPackageTest.apk(dir.resolve("released.apk"), RELEASED, false);
        Path other = PhoneRigApkPackageTest.apk(dir.resolve("other.apk"), "com.example.other", true);
        Path none = dir.resolve("not-there.apk");

        sent(phone(CABLE, BENCH, false), "install", "-r", "-t", bench.toString());
        sent(phone(CABLE, BENCH, false), "install", "-r", bench.toString());
        assertTrue(refused(phone(CABLE, BENCH, false), "install", "-r", "-t", released.toString()).contains("would change the released app"),
                "the file's name says nothing: its manifest does");
        sent(phone(CABLE, RELEASED, true), "install", "-r", released.toString());
        assertTrue(refused(phone(CABLE, BENCH, true), "install", "-r", other.toString()).contains("no other app"));
        assertTrue(refused(phone(CABLE, BENCH, true), "install", "-r", none.toString()).contains("could not be read"));
        refused(phone(CABLE, BENCH, true), "install", "-r", "-d", bench.toString());
        refused(phone(CABLE, BENCH, true), "install", "-r", "-g", bench.toString());
        refused(phone(CABLE, BENCH, true), "install", "-r");

        // through the rig: a real phone gets -r -t, never -d - also where the emulator is taken a version down
        RecordingAdb adb = RecordingAdb.aPhone();
        PhoneRig rig = rig(phone(CABLE, BENCH, false), adb);
        rig.install(bench);
        assertEquals("Success", rig.installAnyVersion(bench).lines().reduce((a, b) -> b).orElse(""));
        assertEquals(List.of("install -r -t " + bench, "install -r -t " + bench), adb.sent);
        assertThrows(PhoneRig.Refused.class, () -> rig.install(released));
        assertThrows(PhoneRig.Refused.class, () -> rig.installAnyVersion(released));
        assertEquals(2, adb.sent.size());
    }

    private static final List<String[]> NETWORK_OFF = List.of(
            new String[]{"shell", "cmd connectivity airplane-mode enable"},
            new String[]{"shell", "svc wifi disable"},
            new String[]{"shell", "svc data disable"});

    @Test
    void theNetworkIsNotSwitchedOffWhileAdbComesOverIt() {
        for (String serial : List.of(NETWORK, "[2001:db8::7]:5555", "adb-BENCHPHONE0001-AbCdEf._adb-tls-connect._tcp")) {
            assertTrue(phone(serial, BENCH, false).overTcp(), serial);
            for (String[] command : NETWORK_OFF) {
                String why = refused(phone(serial, BENCH, true), command);
                assertTrue(why.contains("adb itself comes over the network") && why.contains("USB cable"), why);
            }
            // switching it on again is always right, and asking is
            sent(phone(serial, BENCH, false), "shell", "cmd connectivity airplane-mode disable");
            sent(phone(serial, BENCH, false), "shell", "cmd connectivity airplane-mode");
            sent(phone(serial, BENCH, false), "shell", "svc wifi enable");
            sent(phone(serial, BENCH, false), "shell", "svc data enable");
        }
        for (String serial : List.of(CABLE, "0123456789ABCDEF", "emulator-5584")) assertFalse(phone(serial, BENCH, false).overTcp(), serial);
        for (String[] command : NETWORK_OFF) sent(phone(CABLE, BENCH, false), command); // over the cable: allowed
    }

    @Test
    void theNetworkSwitchedOffIsSwitchedOnAgainWhenTheRigCloses() {
        RecordingAdb adb = RecordingAdb.aPhone();
        PhoneRig rig = rig(phone(CABLE, BENCH, false), adb);
        rig.aeroplane(true);
        rig.wifi(false);
        rig.shell("svc data disable");
        rig.shell("svc data enable"); // put back by the scenario itself: not again
        int before = adb.count();
        rig.close();
        assertEquals(List.of("shell dumpsys deviceidle unforce", "shell dumpsys battery reset", "shell cmd connectivity airplane-mode disable", "shell svc wifi enable"),
                adb.since(before));
    }

    /** A scenario's "try again" must not swallow a refusal: several of them catch RuntimeException around a phone command. */
    @Test
    void aRefusalIsNoExceptionAScenarioCatchesAndGoesOn() {
        assertFalse(RuntimeException.class.isAssignableFrom(PhoneRig.Refused.class));
        assertFalse(RuntimeException.class.isAssignableFrom(PhoneRig.Stop.class));
        RecordingAdb adb = RecordingAdb.aPhone();
        PhoneRig rig = rig(phone(CABLE, BENCH, false), adb);
        assertThrows(PhoneRig.Refused.class, () -> {
            try {
                rig.shell("kill -9 1");
            } catch (RuntimeException swallowed) {
                // as PhoneRig.pid and the scenarios' own helpers do
            }
        });
    }

    @Test
    void whatOnlyAnEmulatorCanDoIsRefusedByNameOnARealPhone() {
        RecordingAdb adb = RecordingAdb.aPhone();
        PhoneRig rig = rig(phone(CABLE, BENCH, false), adb);
        assertTrue(assertThrows(PhoneRig.Refused.class, () -> rig.gps(60, 10)).getMessage().contains("EMU_GPS"));
        assertTrue(assertThrows(PhoneRig.Refused.class, () -> rig.battery(50, false)).getMessage().contains("EMU_BATTERY"));
        assertTrue(assertThrows(PhoneRig.Refused.class, () -> rig.forwardUdp(PhoneRig.linkOnThePhone())).getMessage().contains("UDP_REDIR"));
        assertEquals(List.of(), adb.sent);
        assertEquals(3, rig.refusals().size());
        rig.root(); // said, nothing sent
        assertEquals(List.of(), adb.sent);
        // the released app has no bench receiver: root's work cannot be done for it, and that is said
        PhoneRig released = rig(phone(CABLE, RELEASED, true), adb);
        assertTrue(assertThrows(PhoneRig.Refused.class, released::stopForgettingRestarts).getMessage().contains("bench build's receiver"));
        assertThrows(PhoneRig.Refused.class, () -> released.endProcess("1234"));
        assertEquals(List.of(), adb.sent);
        released.crash(); // Android's own am crash needs no root
        assertEquals(List.of("shell am crash " + RELEASED), adb.sent);
        assertTrue(released.socketStats().contains("not readable"));
    }

    /**
     * Every command the rig and the scenarios tagged hwphone send to a real phone, as they are written there: each is
     * on the list. (A scenario that needs a new one fails here first, not in the middle of a night on the phone.)
     */
    @Test
    void whatTheHardwareScenariosSendIsOnTheList() {
        String app = BENCH, stamp = "11-21 23:10:05.000";
        List<String[]> commands = new ArrayList<>(List.of(
                new String[]{"get-state"},
                new String[]{"shell", "getprop sys.boot_completed"},
                new String[]{"logcat", "-v", "threadtime", "-s", "DDLog:I", "DD:I", "AndroidRuntime:E", "ActivityManager:I"},
                new String[]{"shell", "dumpsys battery"},
                new String[]{"shell", "cat /sys/class/power_supply/battery/capacity /sys/class/power_supply/battery/temp 2>/dev/null"},
                new String[]{"shell", "pm path " + app},
                new String[]{"shell", "pm clear " + app},
                new String[]{"shell", "pm grant " + app + " android.permission.POST_NOTIFICATIONS"},
                new String[]{"shell", "dpm set-active-admin --user 0 " + app + "/de.droiddrone.flight.PowerAdminReceiver"},
                new String[]{"shell", "input keyevent KEYCODE_WAKEUP"},
                new String[]{"shell", "input keyevent KEYCODE_BACK"},
                new String[]{"shell", "wm dismiss-keyguard"},
                new String[]{"shell", "am start -n " + app + "/de.droiddrone.flight.MainActivity"},
                new String[]{"shell", "pidof " + app},
                new String[]{"shell", "am force-stop " + app},
                new String[]{"shell", "am crash " + app},
                new String[]{"shell", "dumpsys package " + app},
                new String[]{"shell", "dumpsys activity services " + app},
                new String[]{"shell", "dumpsys meminfo " + app},
                new String[]{"logcat", "-d", "--pid=4711", "-v", "time"},
                new String[]{"shell", "date '+%m-%d %H:%M:%S.000'"},
                new String[]{"shell", "logcat -d --pid=4711 -v time -T '" + stamp + "'"},
                new String[]{"shell", "logcat -d -v time -T '" + stamp + "' -s DDLog:I AndroidRuntime:E"},
                new String[]{"shell", "logcat -d -v time -T '" + stamp + "' -s DDLog:I"},
                new String[]{"shell", "logcat -G 16M"},
                new String[]{"exec-out", "uiautomator", "dump", "/dev/tty"},
                new String[]{"exec-out", "screencap", "-p"},
                new String[]{"shell", "input tap 540 1200"},
                new String[]{"shell", "input swipe 540 1700 540 700 300"},
                new String[]{"shell", "wm size"},
                new String[]{"shell", "dumpsys window displays"},
                new String[]{"shell", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"},
                new String[]{"shell", "ps -T -p 4711 -o TID,S,NAME 2>&1"},
                new String[]{"shell", "ss -u -a -m 2>&1"},
                new String[]{"shell", "appops get " + app + " REQUEST_INSTALL_PACKAGES"},
                new String[]{"shell", "appops set " + app + " REQUEST_INSTALL_PACKAGES default"},
                new String[]{"shell", "dumpsys battery unplug"},
                new String[]{"shell", "dumpsys deviceidle force-idle"},
                new String[]{"shell", "dumpsys deviceidle unforce"},
                new String[]{"shell", "dumpsys battery reset"},
                new String[]{"shell", "dumpsys deviceidle get deep"},
                new String[]{"shell", "cmd connectivity airplane-mode"},
                new String[]{"shell", "cmd connectivity airplane-mode enable"},
                new String[]{"shell", "cmd connectivity airplane-mode disable"},
                new String[]{"shell", "svc wifi disable"},
                new String[]{"shell", "svc wifi enable"},
                new String[]{"reverse", "tcp:8123", "tcp:8123"},
                new String[]{"reverse", "--remove", "tcp:8123"},
                new String[]{"reverse", "--list"}));
        for (String action : BenchControl.ACTIONS) {
            commands.add(new String[]{"shell", "am broadcast -n " + BenchControl.component(app) + " -a " + action});
        }
        commands.add(new String[]{"shell", "am broadcast --include-stopped-packages -n " + BenchControl.component(app) + " -a " + BenchControl.RESET_CRASH_GUARD});
        for (String[] command : commands) {
            PhoneGuard.Verdict v = PhoneGuard.judge(phone(CABLE, BENCH, false), List.of(command), p -> null);
            assertNull(v.refusal(), String.join(" ", command));
        }
    }

    @Test
    void whatACommandDoesToThePhoneIsKnown() {
        PhoneRig.Target t = phone(CABLE, BENCH, false);
        assertEquals(PhoneGuard.Kind.READ, PhoneGuard.judge(t, List.of("shell", "dumpsys battery"), p -> null).kind());
        assertEquals(PhoneGuard.Kind.READ, PhoneGuard.judge(t, List.of("shell", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"), p -> null).kind());
        assertEquals(PhoneGuard.Kind.HAND, PhoneGuard.judge(t, List.of("shell", "input tap 1 2"), p -> null).kind());
        assertEquals(PhoneGuard.Kind.APP, PhoneGuard.judge(t, List.of("shell", "getprop x; pm clear " + BENCH), p -> null).kind());
        assertEquals(PhoneGuard.Kind.PHONE, PhoneGuard.judge(t, List.of("shell", "logcat -G 16M"), p -> null).kind());
        PhoneGuard.Verdict unplug = PhoneGuard.judge(t, List.of("shell", "dumpsys battery unplug"), p -> null);
        assertTrue(unplug.pretends() && unplug.kind() == PhoneGuard.Kind.PHONE);
        assertTrue(PhoneGuard.judge(t, List.of("shell", "dumpsys deviceidle force-idle"), p -> null).pretends());
        assertFalse(PhoneGuard.judge(t, List.of("shell", "dumpsys battery reset"), p -> null).pretends());
        assertEquals(EnumSet.of(PhoneGuard.Undo.WIFI), PhoneGuard.judge(t, List.of("shell", "svc wifi disable"), p -> null).sets());
        assertEquals(EnumSet.of(PhoneGuard.Undo.WIFI), PhoneGuard.judge(t, List.of("shell", "svc wifi enable"), p -> null).clears());
    }

    @Test
    void aShellLineIsCutAtItsSeamsAndNotInsideItsQuotes() {
        assertEquals(List.of(List.of("dumpsys", "window"), List.of("grep", "-E", "mCurrentFocus|mFocusedApp")), cut("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"));
        assertEquals(List.of(List.of("date", "+%m-%d %H:%M:%S.000")), cut("date '+%m-%d %H:%M:%S.000'"));
        assertEquals(List.of(List.of("ss", "-u", "-a", "-n")), cut("ss -u -a -n 2>&1"));
        assertEquals(List.of(List.of("cat", "/sys/a", "/sys/b")), cut("cat /sys/a /sys/b 2>/dev/null"));
        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c"), List.of("d"), List.of("e")), cut("a; b && c || d\ne"));
        assertEquals(List.of(List.of("echo", "a;b", "c d")), cut("echo \"a;b\" c\\ d"));
        assertEquals(List.of(List.of("ps"), List.of("true")), cut("ps > /dev/null & true"));
        for (String no : List.of("echo $HOME", "echo `id`", "echo \"$(id)\"", "cat < /sdcard/x", "echo 'open", "ps > /sdcard/x", "ps 2>err.txt", "ps >>/sdcard/x")) {
            assertTrue(PhoneGuard.split(no, new ArrayList<>()) != null, no);
        }
        assertNull(PhoneGuard.split("echo '$HOME `id`'", new ArrayList<>()), "inside single quotes nothing is run");
    }

    private static List<List<String>> cut(String line) {
        List<List<String>> out = new ArrayList<>();
        assertNull(PhoneGuard.split(line, out), line);
        return out;
    }
}
