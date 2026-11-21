package de.droiddrone.bench;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real phone a phone run drives is this bench's own, read by the one reader there is ({@link Hardware}: the
 * hardware file, and before it the run's own word and the environment's for the phone's four facts). What is missing
 * is asked for by name, never guessed; the two words that put the released app at risk are a run's own, never a
 * file's; and the phone's link port is reached only the fence's way ({@link Fence.Reach}), in a hardware run's JVM.
 */
class PhoneRigTargetTest {
    private static final String LOCAL = "C:\\Users\\bench\\AppData\\Local";
    private final Map<String, String> property = new HashMap<>(), environment = new HashMap<>();
    private Path file;

    @BeforeEach
    void aFile(@TempDir Path dir) {
        file = dir.resolve(Hardware.FILE);
    }

    private Hardware bench(String text) throws Exception {
        Files.writeString(file, text);
        return Hardware.read(file, property::get, environment::get);
    }

    private PhoneRig.Target device(String text) throws Exception {
        return PhoneRig.Target.device(bench(text), property::get, environment::get, LOCAL);
    }

    /** As gradlew benchHwPhone starts its JVM; put back by the returned step. */
    static AutoCloseable asAHardwareRun() {
        String was = System.getProperty(Fence.Reach.PROPERTY);
        System.setProperty(Fence.Reach.PROPERTY, "true");
        return () -> {
            if (was == null) System.clearProperty(Fence.Reach.PROPERTY);
            else System.setProperty(Fence.Reach.PROPERTY, was);
        };
    }

    @Test
    void theHardwareFileAloneIsEnough() throws Exception {
        PhoneRig.Target t = device("# the bench's phone\nphone.adb = BENCHPHONE0001\nphone.ip=192.0.2.7\nboard.port=COM99\n");
        assertEquals("BENCHPHONE0001", t.serial());
        assertEquals(PhoneRig.Kind.DEVICE, t.kind());
        assertEquals(BenchControl.PACKAGE, t.app(), "left out: the bench build, not the released app");
        assertTrue(t.benchBuild());
        assertEquals("192.0.2.7", t.link().getAddress().getHostAddress());
        assertEquals(PhoneRig.linkOnThePhone(), t.link().getPort(), "left out: the app's own link port");
        assertEquals(EnumSet.noneOf(PhoneRig.Capability.class), t.can(), "a real phone: no root, no console");
        assertFalse(t.releasedAppMayBeChanged());
        assertNull(t.apk());
        assertNull(t.avdHome());
        assertEquals(Path.of(LOCAL, "Android", "Sdk"), t.sdk());
        assertFalse(t.overTcp());
        assertEquals("http://127.0.0.1:8080", t.thisPc(8080));
        assertEquals(Set.of(), bench("phone.adb=BENCHPHONE0001\nphone.ip=192.0.2.7\n").saidOutsideTheFile());
    }

    @Test
    void theRunsOwnWordGoesBeforeTheEnvironmentAndThatBeforeTheFile() throws Exception {
        String text = "phone.adb=FROMFILE\nphone.ip=192.0.2.7\nphone.port=7001\nphone.package=" + PhoneRig.APP + "\n";
        assertEquals("FROMFILE", device(text).serial());
        assertEquals(7001, device(text).link().getPort());
        assertEquals(PhoneRig.APP, device(text).app());

        environment.put("BENCH_PHONE_ADB", "FROMENV");
        environment.put("BENCH_PHONE_IP", "192.0.2.8");
        environment.put("BENCH_PHONE_PORT", "7002");
        environment.put("BENCH_PHONE_PACKAGE", BenchControl.PACKAGE);
        PhoneRig.Target env = device(text);
        assertEquals("FROMENV", env.serial());
        assertEquals("192.0.2.8", env.link().getAddress().getHostAddress());
        assertEquals(7002, env.link().getPort());
        assertEquals(BenchControl.PACKAGE, env.app());

        property.put("bench.phone.adb", " FROMRUN ");
        property.put("bench.phone.ip", "192.0.2.9");
        property.put("bench.phone.port", "7003");
        property.put("bench.phone.package", PhoneRig.APP);
        PhoneRig.Target run = device(text);
        assertEquals("FROMRUN", run.serial());
        assertEquals("192.0.2.9", run.link().getAddress().getHostAddress());
        assertEquals(7003, run.link().getPort());
        assertEquals(PhoneRig.APP, run.app());
        assertFalse(run.benchBuild());
        // what did not come from the file is said wherever the hardware is printed (a film's first lines)
        Hardware said = bench(text);
        assertEquals(Set.of("phone.adb", "phone.ip", "phone.port", "phone.package"), said.saidOutsideTheFile());
        assertTrue(said.toString().contains("not from the hardware file") && said.toString().contains("phone.ip"), said.toString());

        property.put("bench.phone.adb", "  "); // an empty word is no word
        assertEquals("FROMENV", device(text).serial());

        // only the phone's four: the link key, the board, the battery's floor and the keeper are the file's alone
        property.clear();
        environment.clear();
        property.put("bench.phone.key", "FromRun");
        property.put("bench.board.port", "COM7");
        property.put("bench.battery.cellFloor", "2.6");
        environment.put("BENCH_PHONE_KEY", "FromEnv");
        environment.put("BENCH_KEEPER_PORT", "5000");
        Hardware own = bench("phone.adb=BENCHPHONE0001\nphone.ip=192.0.2.7\nphone.key=FromFile\nboard.port=COM99\nbattery.cells=4\nbattery.cellFloor=3.5\nkeeper.port=47800\n");
        assertEquals("FromFile", own.phoneKey());
        assertEquals("COM99", own.boardPort());
        assertEquals(3.5, own.cellFloor());
        assertEquals(47800, own.keeperPort());
        assertEquals(Set.of(), own.saidOutsideTheFile());
        assertEquals(Hardware.read(file).toString(), own.toString(), "and the file read alone is the same bench");

        // the SDK: the run's, else the environment's, else this user's
        property.clear();
        environment.clear();
        environment.put("ANDROID_HOME", "D:\\sdk-home");
        assertEquals(Path.of("D:\\sdk-home"), device(text).sdk());
        environment.put("ANDROID_SDK_ROOT", "D:\\sdk-root");
        assertEquals(Path.of("D:\\sdk-root"), device(text).sdk());
        property.put("bench.android.sdk", "D:\\sdk-run");
        assertEquals(Path.of("D:\\sdk-run"), device(text).sdk());
    }

    @Test
    void theReleasedAppMayBeChangedOnlyOnTheRunsOwnWord() throws Exception {
        String text = "phone.adb=BENCHPHONE0001\nphone.ip=192.0.2.7\n";
        // a file that lies about for a week cannot say it: the reader does not even know such words
        for (String word : List.of("phone.releasedAppMayBeChanged=true", "releasedAppMayBeChanged=true", "phone.apk=some.apk")) {
            assertTrue(assertThrows(IllegalStateException.class, () -> bench(text + word + "\n"), word).getMessage().contains("does not know"));
        }
        assertFalse(device(text).releasedAppMayBeChanged());
        assertNull(device(text).apk());
        for (String no : new String[]{"false", "0", "", "no", "maybe"}) {
            property.put("bench.phone.releasedAppMayBeChanged", no);
            assertFalse(device(text).releasedAppMayBeChanged(), no);
        }
        for (String yes : new String[]{"true", "TRUE", "1", "yes"}) {
            property.put("bench.phone.releasedAppMayBeChanged", yes);
            assertTrue(device(text).releasedAppMayBeChanged(), yes);
        }
        property.clear();
        environment.put("BENCH_PHONE_RELEASED_APP_MAY_BE_CHANGED", "1");
        assertTrue(device(text).releasedAppMayBeChanged());
        property.put("bench.phone.apk", "C:\\builds\\flight-bench.apk");
        assertEquals(Path.of("C:\\builds\\flight-bench.apk"), device(text).apk());
    }

    @Test
    void whatIsMissingIsAskedForByNameAndNothingIsGuessed() throws Exception {
        // no serial: a phone is never picked by itself
        String noSerial = assertThrows(IllegalStateException.class, () -> device("phone.ip=192.0.2.7\n")).getMessage();
        assertTrue(noSerial.contains("phone.adb") && noSerial.contains(file.toString()), noSerial);
        // no address: adb's cable carries no UDP, the link needs one
        assertTrue(assertThrows(IllegalStateException.class, () -> device("phone.adb=BENCHPHONE0001\n")).getMessage().contains("phone.ip"));
        // a name would have to be looked up, and nothing is from inside the bench's fence - also when the run says it
        for (String name : new String[]{"bench-phone", "bench-phone.example.net", "192.0.2", "192.0.2.300", "localhost", "2001:db8::7"}) {
            assertTrue(assertThrows(IllegalStateException.class, () -> device("phone.adb=BENCHPHONE0001\nphone.ip=" + name + "\n"), name).getMessage().contains("phone.ip"));
            property.put("bench.phone.ip", name);
            String why = assertThrows(IllegalStateException.class, () -> device("phone.adb=BENCHPHONE0001\nphone.ip=192.0.2.7\n"), name).getMessage();
            assertTrue(why.contains("phone.ip") && why.contains("as the run or the environment says"), why);
            property.clear();
        }
        for (String port : new String[]{"0", "70000", "-1", "link"}) {
            assertTrue(assertThrows(IllegalStateException.class, () -> device("phone.adb=BENCHPHONE0001\nphone.ip=192.0.2.7\nphone.port=" + port + "\n"), port)
                    .getMessage().contains("phone.port"));
        }
        // the bench flies DRAUPNE Flight, in one of its two packages, and nothing else
        String other = assertThrows(IllegalStateException.class, () -> device("phone.adb=BENCHPHONE0001\nphone.ip=192.0.2.7\nphone.package=com.android.settings\n")).getMessage();
        assertTrue(other.contains("com.android.settings") && other.contains(BenchControl.PACKAGE) && other.contains(PhoneRig.APP), other);
        property.put("bench.phone.package", "com.android.settings");
        assertThrows(IllegalStateException.class, () -> device("phone.adb=BENCHPHONE0001\nphone.ip=192.0.2.7\n"));
    }

    /** The one way through the fence is the fence's own - and it is there in a hardware run's JVM only. */
    @Test
    void thePhonesLinkPortIsReachedTheFencesWayOrNotAtAll() throws Exception {
        PhoneRig.Target phone = device("phone.adb=BENCHPHONE0001\nphone.ip=192.0.2.7\nphone.port=7001\n");
        assertTrue(PhoneRig.Target.emulator().reach().isEmpty(), "the emulator: nothing outside this PC, in any run");

        // this JVM is the fast tests': no hardware run
        assertFalse(Fence.Reach.hardwareRun());
        assertTrue(assertThrows(IllegalStateException.class, phone::reach).getMessage().contains("benchHwPhone"));
        RecordingAdb adb = RecordingAdb.aPhone();
        PhoneRig rig = PhoneRigGuardTest.rig(phone, adb);
        IllegalStateException closed = assertThrows(IllegalStateException.class, rig::linkAddress);
        assertTrue(closed.getMessage().contains("no hardware run"), closed.getMessage());

        try (AutoCloseable back = asAHardwareRun()) {
            assertEquals(List.of("192.0.2.7:7001"), phone.reach().pairs());
            assertFalse(Fence.isUp(), "no fence up here: a scenario of the PC's link alone");
            assertEquals(phone.link(), rig.linkAddress());
            assertEquals(phone.link(), rig.linkAddress());
        }
        assertEquals(List.of(), adb.sent, "nothing is forwarded and nothing sent for it: adb's cable carries no UDP");
        rig.close();
        assertThrows(IllegalStateException.class, rig::linkAddress, "and outside a hardware run it is closed again");
    }

    /** For trying the real phone's rules out before the phone is on the cable: the emulator, held to them. */
    @Test
    void theEmulatorCanBeHeldToARealPhonesRules() throws Exception {
        property.put("bench.phone.adb", "emulator-5584");
        PhoneRig.Target t = device("phone.adb=BENCHPHONE0001\nphone.ip=192.0.2.7\n");
        assertEquals(PhoneRig.Kind.DEVICE, t.kind());
        assertEquals(EnumSet.of(PhoneRig.Capability.UDP_REDIR), t.can(), "its link port is still reached as an emulator's is - and that is all it keeps");
        assertNull(t.link(), "the file's address is not the emulator's");
        assertTrue(t.reach().isEmpty(), "on this PC's loopback: no way through the fence is needed, or asked for");
        assertEquals(BenchControl.PACKAGE, t.app());

        RecordingAdb adb = RecordingAdb.aPhone();
        PhoneRig rig = PhoneRigGuardTest.rig(t, adb);
        assertTrue(rig.device());
        java.net.InetSocketAddress at = rig.linkAddress();
        assertEquals("emu redir add udp:" + at.getPort() + ":" + PhoneRig.linkOnThePhone(), adb.sent.get(0));
        assertTrue(at.getAddress().isLoopbackAddress());
        assertThrows(PhoneRig.Refused.class, () -> rig.adb("root"));
        assertThrows(PhoneRig.Refused.class, () -> rig.gps(60, 10));
        assertThrows(PhoneRig.Refused.class, () -> rig.shell("pm clear " + PhoneRig.APP));
        assertThrows(PhoneRig.Refused.class, () -> rig.shell("locksettings set-disabled false"));
        rig.close();
        assertTrue(adb.sent.contains("shell dumpsys battery reset") && adb.sent.contains("emu redir del udp:" + at.getPort()), adb.sent.toString());
    }

    @Test
    void aRunIsForTheEmulatorUnlessItIsAHardwareRun() {
        assertFalse(Fence.Reach.hardwareRun());
        assertEquals(PhoneRig.Target.emulator(), PhoneRig.Target.ofRun());
        String was = System.getProperty(Fence.Reach.PROPERTY);
        try {
            System.setProperty(Fence.Reach.PROPERTY, "yes"); // only the word true is a hardware run
            assertEquals(PhoneRig.Target.emulator(), PhoneRig.Target.ofRun());
        } finally {
            if (was == null) System.clearProperty(Fence.Reach.PROPERTY);
            else System.setProperty(Fence.Reach.PROPERTY, was);
        }
    }
}
