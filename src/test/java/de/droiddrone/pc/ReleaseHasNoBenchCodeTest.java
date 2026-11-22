package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phone app has a build for the hardware test rig that reaches a flight controller over TCP instead of USB
 * (flight-app/bench-overlay). That code must never be in an app that is released, on a phone in the field.
 * <p>
 * The released app is built from upstream and flight-app/overlay and nothing else, so nothing of the rig may be in
 * flight-app/overlay: not its marker, not its files, not a word of where they are. What overlay has is the BenchFc
 * that says "not built in" and does nothing, and Serial asking that constant first. This reads the sources as text;
 * the built APK is scanned as well (flight-app/scan-apk.ps1: by build.ps1 on every build, by the release workflow on
 * the phone app it publishes) - and that those two still do is held here too.
 */
class ReleaseHasNoBenchCodeTest {
    /** In the rig's code and manifest; an APK is scanned for it. */
    private static final String MARKER = "DRAUPNE-BENCH-FC";
    /**
     * What only the rig's build has, and the update test's builds (bench-overlay/update-test, build.ps1 -UpdateTest: a
     * remote update taken from the bench's own server too): none of it may be written anywhere under flight-app/overlay.
     */
    private static final List<String> RIG_ONLY = List.of(MARKER, "bench-overlay", "bench.gradle", "BenchControlReceiver",
            "debug.draupne.fc", "android:testOnly", "assembleBench", "flight-bench",
            "DRAUPNE-UPDATE-TEST", "UpdateTestSource", "update_test_network", "flight-update-test");
    private static final String STUB = "Flight/src/release/java/de/droiddrone/flight/BenchFc.java";

    private static Path root() {
        // the project's root: the test runs in build/test-work
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        return root;
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1); // pictures too: every byte a letter
    }

    private static String slashes(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    @Test
    void nothingOfTheRigIsInTheSourcesOfTheReleasedApp() throws Exception {
        Path overlay = root().resolve("flight-app/overlay");
        List<String> found = new ArrayList<>();
        int read = 0;
        try (Stream<Path> files = Files.walk(overlay)) {
            for (Path f : files.sorted().toList()) {
                String name = slashes(overlay.relativize(f));
                if (name.contains("bench-overlay") || name.endsWith("bench.gradle") || ("/" + name + "/").contains("/src/bench/")
                        || name.contains("BenchControlReceiver") || name.contains("UpdateTestSource") || name.contains("update_test_network")) {
                    found.add(name + "  <- a file of the rig's build");
                }
                if (!Files.isRegularFile(f)) continue;
                read++;
                String text = text(f);
                for (String word : RIG_ONLY) if (text.contains(word)) found.add(name + ": " + word);
                if (name.endsWith(".gradle") && Pattern.compile("\\bbench\\s*\\{").matcher(text).find()) found.add(name + ": a build type bench");
                if (name.endsWith(".gradle") && Pattern.compile("(applicationId|versionName)Suffix").matcher(text).find()) found.add(name + ": a suffix");
            }
        }
        assertTrue(read > 50, "the phone app's sources were found: " + read + " files under " + overlay);
        assertEquals(List.of(), found, "the rig's build in flight-app/overlay, from which the released app is built");
    }

    @Test
    void theBenchFcOfTheReleasedAppSaysNotBuiltInAndDoesNothing() throws Exception {
        Path overlay = root().resolve("flight-app/overlay");
        List<String> stubs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(overlay)) {
            for (Path f : files.filter(p -> p.getFileName().toString().equals("BenchFc.java")).sorted().toList()) stubs.add(slashes(overlay.relativize(f)));
        }
        // one, in the release build type's sources: in main it would be compiled into the rig's build beside the real one
        assertEquals(List.of(STUB), stubs, "BenchFc under flight-app/overlay");
        // its code: without the comments, which say in words what it is
        StringBuilder code = new StringBuilder();
        for (String line : Files.readAllLines(overlay.resolve(STUB), StandardCharsets.ISO_8859_1)) {
            String l = line.strip();
            if (!l.startsWith("/*") && !l.startsWith("*") && !l.startsWith("//")) code.append(l).append('\n');
        }
        String stub = code.toString();
        Matcher value = Pattern.compile("\\bBUILT_IN\\s*=\\s*(\\w+)").matcher(stub);
        List<String> values = new ArrayList<>();
        while (value.find()) values.add(value.group(1));
        assertEquals(List.of("false"), values, "BUILT_IN in " + STUB);
        assertTrue(Pattern.compile("static\\s+final\\s+boolean\\s+BUILT_IN\\s*=\\s*false\\s*;").matcher(stub).find(),
                "BUILT_IN is a constant the compiler knows (static final boolean ... = false)");
        for (String doing : List.of("java.net", "Socket", "new ", "Thread", "SystemProperties", "getprop", "import ")) {
            assertFalse(stub.contains(doing), STUB + " does nothing, but has: " + doing);
        }
        // a debug build takes the same one
        assertTrue(text(overlay.resolve("Flight/build.gradle")).contains("debug.java.srcDirs += 'src/release/java'"), "a debug build compiles the same BenchFc");
    }

    /**
     * Q-006 (Tailscale only): the link's rule is widened in the Android emulator for the rig's build and the update
     * test's alone (their own BenchNet). The released app's says "not built in" and does nothing, and the one place
     * that asks it (PhoneTailscale) asks the constant first - so the widened rule is not in the compiled app.
     */
    @Test
    void theBenchNetOfTheReleasedAppSaysNotBuiltInAndTheRuleIsNeverWidenedThere() throws Exception {
        Path root = root();
        Path overlay = root.resolve("flight-app/overlay");
        String stubPath = "Flight/src/release/java/de/droiddrone/flight/BenchNet.java";
        List<String> stubs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(overlay)) {
            for (Path f : files.filter(p -> p.getFileName().toString().equals("BenchNet.java")).sorted().toList()) stubs.add(slashes(overlay.relativize(f)));
        }
        assertEquals(List.of(stubPath), stubs, "BenchNet under flight-app/overlay");
        StringBuilder code = new StringBuilder();
        for (String line : Files.readAllLines(overlay.resolve(stubPath), StandardCharsets.ISO_8859_1)) {
            String l = line.strip();
            if (!l.startsWith("/*") && !l.startsWith("*") && !l.startsWith("//")) code.append(l).append('\n');
        }
        String stub = code.toString();
        assertTrue(Pattern.compile("static\\s+final\\s+boolean\\s+BUILT_IN\\s*=\\s*false\\s*;").matcher(stub).find(), "BUILT_IN = false, a constant the compiler knows");
        assertFalse(stub.contains("true"), stubPath + " says yes nowhere");
        for (String doing : List.of("java.net", "Socket", "new ", "Thread", "SystemProperties", "getprop", "import ", "Build.")) {
            assertFalse(stub.contains(doing), stubPath + " does nothing, but has: " + doing);
        }
        // asked in one place, the constant first on the same line
        List<String> wrong = new ArrayList<>();
        int asked = 0;
        try (Stream<Path> files = Files.walk(overlay.resolve("Flight/src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.ISO_8859_1);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i).strip();
                    if (l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") || !l.contains("BenchNet.")) continue;
                    String at = f.getFileName() + ":" + (i + 1) + ": " + l;
                    if (!f.getFileName().toString().equals("PhoneTailscale.java")) wrong.add(at + "  <- only PhoneTailscale asks it");
                    if (l.contains("BenchNet.wide()") && !l.contains("BenchNet.BUILT_IN && BenchNet.wide()")) wrong.add(at + "  <- BenchNet.BUILT_IN is not asked first");
                    if (l.contains("BenchNet.wide()")) asked++;
                }
            }
        }
        assertEquals(List.of(), wrong);
        assertEquals(1, asked, "PhoneTailscale asks it once, for the place of the link's sockets");
        // the rig's and the update test's own: widened in the emulator alone, each with its own marker in code that runs
        String rig = text(root.resolve("flight-app/bench-overlay/Flight/src/bench/java/de/droiddrone/flight/BenchNet.java"));
        assertTrue(rig.contains("BUILT_IN = true") && rig.contains("\"ranchu\"") && rig.contains("log(BenchFc.MARKER"), "the rig's: the emulator alone, said with its marker");
        String updateTest = text(root.resolve("flight-app/bench-overlay/update-test/Flight/src/release/java/de/droiddrone/flight/BenchNet.java"));
        assertTrue(updateTest.contains("BUILT_IN = true") && updateTest.contains("\"ranchu\"") && updateTest.contains("log(UpdateTestSource.MARKER"),
                "the update test's: the emulator alone, said with its marker");
        assertFalse(updateTest.contains("BenchFc") || updateTest.contains(MARKER), "nothing of the rig's in the update test's builds (their scan refuses it)");
    }

    @Test
    void serialAsksBuiltInBeforeEveryStepOfTheRig() throws Exception {
        Path sources = root().resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight");
        List<String> wrong = new ArrayList<>();
        int asked = 0;
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String name = f.getFileName().toString();
                String before = "";
                List<String> lines = Files.readAllLines(f, StandardCharsets.ISO_8859_1);
                for (int i = 0; i < lines.size(); i++) {
                    String code = lines.get(i).strip();
                    if (code.isEmpty() || code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) continue;
                    String at = name + ":" + (i + 1) + ": " + code;
                    boolean guard = code.contains("BenchFc.BUILT_IN");
                    if (guard) asked++;
                    if (code.contains("BenchFc.") && !name.equals("Serial.java")) wrong.add(at + "  <- only Serial has the rig's steps");
                    // a call of the rig's: asked on its own line or the line before (if (BenchFc.BUILT_IN && ...) {). The
                    // one exception is inside openBenchFc, which is itself called only behind the question
                    if (Pattern.compile("BenchFc\\.(?!BUILT_IN)\\w+\\(").matcher(code).find() && !guard && !before.contains("BenchFc.BUILT_IN")
                            && !code.equals("if (!BenchFc.open(this, id)) {")) {
                        wrong.add(at + "  <- BenchFc.BUILT_IN is not asked first");
                    }
                    if (code.contains("openBenchFc(id)") && !code.startsWith("private") && !before.contains("BenchFc.BUILT_IN")) {
                        wrong.add(at + "  <- BenchFc.BUILT_IN is not asked first");
                    }
                    before = code;
                }
            }
        }
        assertEquals(List.of(), wrong);
        assertTrue(asked >= 4, "Serial asks BenchFc.BUILT_IN where it opens, writes, closes and asks for the port: " + asked + " times");
    }

    @Test
    void theRigsBuildIsMarkedAndBothScansOfAnApkStillRun() throws Exception {
        Path root = root();
        Path rig = root.resolve("flight-app/bench-overlay/Flight");
        String benchFc = text(rig.resolve("src/bench/java/de/droiddrone/flight/BenchFc.java"));
        assertTrue(benchFc.contains("MARKER = \"" + MARKER + "\"") && benchFc.contains("log(MARKER"), "the rig's BenchFc carries the marker in code that runs");
        assertTrue(benchFc.contains("{127, 0, 0, 1}") && !benchFc.contains("getByName"), "its address is this phone's own, in the code");
        String manifest = text(rig.resolve("src/bench/AndroidManifest.xml"));
        for (String must : List.of(MARKER, "android:testOnly=\"true\"", "android:permission=\"android.permission.DUMP\"")) {
            assertTrue(manifest.contains(must), "the rig's manifest has " + must);
        }
        String gradle = text(rig.resolve("bench.gradle"));
        for (String must : List.of("applicationIdSuffix '.bench'", "versionNameSuffix '-bench'", "signingConfigs.bench")) {
            assertTrue(gradle.contains(must), "bench.gradle has " + must);
        }
        for (String never : List.of("signingConfigs.release", "DD_FLIGHT_KEYSTORE", "flight-release")) {
            assertFalse(gradle.contains(never), "bench.gradle never names the release key: " + never);
        }
        // CrashGuard's store, which the rig's receiver empties, under the name CrashGuard gives it
        assertTrue(text(root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/CrashGuard.java")).contains("STORE = \"crashGuard\""), "CrashGuard's store");
        assertTrue(text(rig.resolve("src/bench/java/de/droiddrone/flight/BenchControlReceiver.java")).contains("CRASH_GUARD_STORE = \"crashGuard\""), "the receiver's name for it");

        // the scans of the built APK
        String scan = text(root.resolve("flight-app/scan-apk.ps1"));
        assertTrue(scan.contains("$marker = '" + MARKER + "'"), "scan-apk.ps1 looks for the marker");
        String build = text(root.resolve("flight-app/build.ps1"));
        assertTrue(Pattern.compile("\\} else \\{\\s*& \\$scan -Apk \\$apk\\.FullName -Sdk \\$sdk -Jdk \\$jdk\\s*\\}\\s*New-Item -ItemType Directory -Force \\$out").matcher(build).find(),
                "build.ps1 scans a release before it reaches build/flight");
        assertTrue(build.contains("$env:DD_FLIGHT_KEYSTORE = $null"), "build.ps1 -Bench gives Gradle no release key");
        String release = text(root.resolve(".github/workflows/release.yml"));
        assertTrue(Pattern.compile("scan-apk\\.ps1 -Apk \"build/release/\\$name\"").matcher(release).find(), "the release workflow scans the phone app it publishes");
        assertTrue(release.indexOf("scan-apk.ps1") < release.indexOf("name: Publish release"), "and before it publishes");

        // the Windows app compiles a part of flight-app/overlay; never the rig's folder
        assertFalse(text(root.resolve("build.gradle")).contains("bench-overlay"), "the PC app's build never reads flight-app/bench-overlay");
    }
}
