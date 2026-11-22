package de.droiddrone.bench;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PC's names for the bench build's receiver ({@link BenchControl}) are the build's own: read here from the phone
 * app's sources (flight-app/bench-overlay), so that a name changed on one side fails this test and not a night on
 * the phone. And the line the rig sends is one that receiver, as it is declared, takes.
 */
class PhoneRigBenchControlTest {
    private static Path rig() {
        Path root = Path.of("").toAbsolutePath(); // the tests run in build/test-work
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        return root.resolve("flight-app/bench-overlay/Flight");
    }

    private static String text(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void thePackageTheReceiverAndItsActionsAreTheBuildsOwn() throws Exception {
        String manifest = text(rig().resolve("src/bench/AndroidManifest.xml"));
        String receiver = text(rig().resolve("src/bench/java/de/droiddrone/flight/BenchControlReceiver.java"));
        String gradle = text(rig().resolve("bench.gradle"));
        String app = text(rig().resolve("../../overlay/Flight/build.gradle").normalize());

        // the package: the released app's with the build type's suffix
        Matcher id = Pattern.compile("applicationId \"([^\"]+)\"").matcher(app);
        Matcher suffix = Pattern.compile("applicationIdSuffix '([^']+)'").matcher(gradle);
        assertTrue(id.find() && suffix.find());
        assertEquals(PhoneRig.APP, id.group(1), "the released app's package");
        assertEquals(BenchControl.PACKAGE, id.group(1) + suffix.group(1), "the bench build's");

        // the receiver's class: the manifest's relative name in the app's namespace (which no build type changes)
        Matcher namespace = Pattern.compile("namespace '([^']+)'").matcher(app);
        Matcher declared = Pattern.compile("<receiver\\s+android:name=\"(\\.[^\"]+)\"").matcher(manifest);
        assertTrue(namespace.find() && declared.find());
        assertEquals(BenchControl.RECEIVER, namespace.group(1) + declared.group(1));
        assertTrue(receiver.contains("package " + namespace.group(1) + ";") && receiver.contains("class BenchControlReceiver"));
        assertEquals("de.droiddrone.flight.pc.bench/de.droiddrone.flight.BenchControlReceiver", BenchControl.component(BenchControl.PACKAGE));

        // its actions: exactly the ones the manifest lets in, each one the receiver's code knows
        List<String> declaredActions = new ArrayList<>();
        Matcher action = Pattern.compile("<action android:name=\"([^\"]+)\"").matcher(manifest);
        while (action.find()) declaredActions.add(action.group(1));
        assertEquals(List.copyOf(BenchControl.ACTIONS).stream().sorted().toList(), declaredActions.stream().sorted().toList());
        Matcher prefix = Pattern.compile("PREFIX = \"([^\"]+)\"").matcher(receiver);
        assertTrue(prefix.find());
        for (String a : BenchControl.ACTIONS) {
            assertTrue(a.startsWith(prefix.group(1)), a);
            String name = a.substring(prefix.group(1).length());
            assertTrue(Pattern.compile("static final String " + name + " = PREFIX \\+ \"" + name + "\"").matcher(receiver).find(), "the receiver's " + name);
            assertTrue(receiver.contains("case " + name + ":"), "and it acts on " + name);
        }
    }

    @Test
    void theLineTheRigSendsIsOneTheReceiverTakes() throws Exception {
        String manifest = text(rig().resolve("src/bench/AndroidManifest.xml"));
        String receiver = text(rig().resolve("src/bench/java/de/droiddrone/flight/BenchControlReceiver.java"));
        // reached from adb's shell: exported, and its sender must hold DUMP - which the shell does and no store app can
        assertTrue(Pattern.compile("android:name=\"\\.BenchControlReceiver\"\\s+android:exported=\"true\"\\s+android:permission=\"android\\.permission\\.DUMP\"").matcher(manifest).find(),
                "exported, behind DUMP");
        // it answers in the broadcast's result, which am broadcast prints: 1 when taken - what the rig waits for
        assertTrue(receiver.contains("setResultCode(1)") && receiver.contains("setResultData(said)"), "the receiver's answer");
        assertEquals("Broadcast completed: result=1", BenchControl.TAKEN);
        // the crash guard's store, which RESET_CRASH_GUARD empties, is the file the emulator's root removes
        assertTrue(receiver.contains("CRASH_GUARD_STORE = \"crashGuard\""));

        // and the rig's own lines, as they go to adb
        RecordingAdb adb = RecordingAdb.aPhone();
        PhoneRig rig = PhoneRigGuardTest.rig(PhoneRigGuardTest.phone(PhoneRigGuardTest.CABLE, BenchControl.PACKAGE, false), adb);
        rig.stopForgettingRestarts();
        rig.endProcess("1");
        rig.crash();
        rig.socketStats();
        String to = " -n de.droiddrone.flight.pc.bench/de.droiddrone.flight.BenchControlReceiver -a de.droiddrone.flight.bench.";
        assertEquals(List.of(
                // a closed app is opened for it: Android hands a stopped app no broadcast unless told to (the build's README says the same)
                "shell am broadcast --include-stopped-packages" + to + "RESET_CRASH_GUARD",
                "shell am force-stop de.droiddrone.flight.pc.bench",
                "shell am broadcast" + to + "KILL",
                "shell am broadcast" + to + "CRASH",
                "shell am broadcast" + to + "STATS"), adb.sent);
        // the emulator's way of the first, for the same file (as root: the emulator flies the bench build since Q-006)
        RecordingAdb emulator = new RecordingAdb();
        PhoneRigGuardTest.rig(PhoneRig.Target.emulator(), emulator).stopForgettingRestarts();
        assertEquals("shell rm -f /data/data/" + BenchControl.PACKAGE + "/shared_prefs/crashGuard.xml", emulator.sent.get(1));
        RecordingAdb released = new RecordingAdb();
        PhoneRigGuardTest.rig(PhoneRig.Target.emulator().flying(PhoneRig.APP), released).stopForgettingRestarts();
        assertEquals("shell rm -f /data/data/" + PhoneRig.APP + "/shared_prefs/crashGuard.xml", released.sent.get(1));
    }
}
