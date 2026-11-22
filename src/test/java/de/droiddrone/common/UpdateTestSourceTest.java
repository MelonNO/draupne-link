package de.droiddrone.common;

import de.droiddrone.pc.setup.ApkShare;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bench's update test (PhoneUpdateScenario) serves its newer phone app from this PC over plain http -
 * http://10.0.2.2:port/... in the emulator, http://127.0.0.1:port/... on a real phone through adb reverse - which a
 * released app refuses: a remote update is taken only from the project's release downloads (UpdateSource). The update
 * test's two builds take that address too: {@code flight-app\build.ps1 -UpdateTest} lays bench-overlay/update-test over
 * a source copy of its own and has UpdateSource ask UpdateTestSource first, in that copy alone. Held here: a release
 * takes none of the bench's addresses; the update test's rule - compiled here from the same sources, patched as
 * build.ps1 patches them, at the phone app's Java 11 - takes them and nothing more besides the release downloads; and
 * build.ps1, the scan and flight-app/overlay are as that needs them.
 */
class UpdateTestSourceTest {
    /** What build.ps1 -UpdateTest finds in UpdateSource.java, and the line it adds after it. */
    private static final String ANCHOR = "    public static boolean fromReleases(String url) {";
    private static final String ADDED = "        if (UpdateTestSource.takes(url)) return true;";
    private static final String RULE = "flight-app/overlay/Common/src/main/java/de/droiddrone/common/UpdateSource.java";
    private static final String UPDATE_TEST = "flight-app/bench-overlay/update-test";
    private static final String VARIANT = UPDATE_TEST + "/Common/src/main/java/de/droiddrone/common/UpdateTestSource.java";

    /** As the scenario names them: target.thisPc(port) + "/DRAUPNE-Flight-" + newVersion + ".apk". */
    private static final List<String> BENCH = List.of(
            "http://10.0.2.2:51234/DRAUPNE-Flight-2.3.0-pc.57-test.apk",
            "http://127.0.0.1:51234/DRAUPNE-Flight-2.3.0-pc.57-test.apk",
            "http://10.0.2.2:1/x.apk", "http://127.0.0.1:65535/x.apk");
    /** Near the bench's addresses, and none of them. */
    private static final List<String> NEVER = List.of("", "http://10.0.2.3:51234/x.apk", "http://127.0.0.2:51234/x.apk",
            "http://localhost:51234/x.apk", "http://192.168.1.2:51234/x.apk", "http://10.0.2.2/x.apk", "http://10.0.2.2:0/x.apk",
            "http://10.0.2.2:65536/x.apk", "http://10.0.2.2:099/x.apk", "http://10.0.2.2:51234/", "http://10.0.2.2:51234/x.zip",
            "http://10.0.2.2:51234/a/x.apk", "http://10.0.2.2:51234/x.apk?y=1", "http://10.0.2.2:51234/x.apk#y",
            "http://10.0.2.2:51234/../x.apk", "http://10.0.2.2:51234/x..apk", "http://10.0.2.2:51234/.apk",
            "http://user@10.0.2.2:51234/x.apk", "http://10.0.2.2.example.net:51234/x.apk", "http://10.0.2.2:51234@example.net/x.apk",
            "https://10.0.2.2:51234/x.apk", "HTTP://10.0.2.2:51234/x.apk", "ftp://10.0.2.2:51234/x.apk", "http://[::1]:51234/x.apk",
            "http://0x7f000001:51234/x.apk", "http://2130706433:51234/x.apk", "http://127.1:51234/x.apk", "http://10.0.2.2:8765",
            "http://10.0.2.2:51234/x.apk\n", " http://10.0.2.2:51234/x.apk", "http://10.0.2.2:51234\\x.apk",
            "http://10.0.2.2:51234/x%2e.apk", "http://10.0.2.2:51234/" + "x".repeat(300) + ".apk",
            "http://github.com/MelonNO/draupne-link/releases/download/v1/x.apk",
            "https://github.com/MelonNO/other/releases/download/v1/x.apk",
            "https://github.com.example.net/MelonNO/draupne-link/releases/download/v1/x.apk",
            UpdateSource.RELEASE_DOWNLOADS + "v1/../x.apk", UpdateSource.RELEASE_DOWNLOADS + "v1/x.apk?y=1");

    @TempDir
    Path tmp;

    private static Path root() {
        // the project's root: the tests run in build/test-work
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        return root;
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    void aReleaseTakesNoneOfTheBenchsAddresses() throws Exception {
        for (String url : BENCH) assertFalse(UpdateSource.fromReleases(url), url);
        for (String url : NEVER) assertFalse(UpdateSource.fromReleases(url), url);
        // and its rule has nothing of the update test's: no word of it, no plain http, neither address
        String release = text(root().resolve(RULE));
        for (String never : List.of("UpdateTestSource", "http://", "10.0.2.2", "127.0.0.1")) {
            assertFalse(release.contains(never), RULE + " has " + never);
        }
    }

    @Test
    void theUpdateTestsBuildTakesTheBenchsServerAndNothingMore() throws Exception {
        ClassLoader build = updateTestsBuild();
        Predicate<String> updateTests = rule(build, "UpdateSource", "fromReleases"), variant = rule(build, "UpdateTestSource", "takes");
        for (String url : BENCH) {
            assertTrue(updateTests.test(url), url);
            assertTrue(variant.test(url), url);
        }
        // the release downloads as before
        for (String url : List.of(ApkShare.APK_URL, UpdateSource.RELEASE_DOWNLOADS + "v1.0.71/DRAUPNE-Flight-2.3.0-pc.55.apk")) {
            assertTrue(updateTests.test(url), url);
            assertFalse(variant.test(url), "not the bench's: " + url);
        }
        List<String> taken = new ArrayList<>();
        for (String url : NEVER) if (updateTests.test(url) || variant.test(url)) taken.add(url);
        if (updateTests.test(null) || variant.test(null)) taken.add(null);
        assertEquals(List.of(), taken, "taken by the update test's build, though not the bench's server");
    }

    @Test
    void buildPs1PatchesItsOwnCopyAsThisTestDoesAndTheScansKnowTheMarker() throws Exception {
        Path root = root();
        String rule = text(root.resolve(RULE));
        assertEquals(1, rule.split(Pattern.quote(ANCHOR), -1).length - 1, "UpdateSource.java has the line build.ps1 -UpdateTest adds after, once");
        String build = text(root.resolve("flight-app/build.ps1"));
        assertTrue(build.contains("$anchor = '" + ANCHOR + "'"), "build.ps1 looks for the same line");
        assertTrue(build.contains("$added = '" + ADDED + " //"), "and adds the same");
        Matcher part = Pattern.compile("(?m)^    if \\(\\$UpdateTest\\) \\{").matcher(build);
        assertTrue(part.find(), "build.ps1 lays the update test over its source copy in a part of its own");
        String laying = build.substring(part.start(), build.indexOf("Set-Content -Path (Join-Path $src 'local.properties')"));
        assertTrue(laying.contains("Copy-Item (Join-Path $PSScriptRoot 'bench-overlay\\update-test\\*') $src -Recurse -Force"), "bench-overlay/update-test, over the copy");
        assertTrue(laying.contains("Join-Path $src 'Common\\src\\main\\java\\de\\droiddrone\\common\\UpdateSource.java'"), "the copy's UpdateSource, never overlay's");
        assertTrue(build.contains("elseif ($UpdateTest) { 'build\\flight-update-test-src' }"), "a source copy of its own");
        assertTrue(Pattern.compile("\\} elseif \\(\\$UpdateTest\\) \\{\\s*& \\$scan -Apk \\$apk\\.FullName -Sdk \\$sdk -Jdk \\$jdk -UpdateTest\\s*\\}").matcher(build).find(),
                "its build is scanned for the update test's marker");
        // the scan: the marker looked for, and a release's verdict refuses it (the release workflow's scan is that one)
        String scan = text(root.resolve("flight-app/scan-apk.ps1"));
        assertTrue(scan.contains("$updateTestMarker = 'DRAUPNE-UPDATE-TEST'"), "scan-apk.ps1 looks for the update test's marker");
        assertTrue(Pattern.compile("\\} else \\{\\s*if \\(\\$markerIn\\) \\{[^\\n]*\\s*if \\(\\$updateTestIn\\) \\{ \\$wrong \\+=").matcher(scan).find(),
                "a release's scan refuses an APK with the update test's marker");
        // the marker in code that runs (a constant alone the code shrinker drops) and in the manifest
        String variant = text(root.resolve(VARIANT));
        assertTrue(variant.contains("MARKER = \"DRAUPNE-UPDATE-TEST\"") && variant.contains("Log.log(MARKER"), "UpdateTestSource carries the marker in code that runs");
        String manifest = text(root.resolve(UPDATE_TEST + "/Flight/src/release/AndroidManifest.xml"));
        assertTrue(manifest.contains("android:name=\"DRAUPNE-UPDATE-TEST\"") && manifest.contains("android:networkSecurityConfig=\"@xml/update_test_network\""),
                "the update test's manifest: its marker, and plain http to the bench's server");
        assertFalse(manifest.contains("testOnly"), "not test-only: the app's own updater must be able to install the newer build");
        // plain http to the two addresses alone: no other host, no base config
        String network = text(root.resolve(UPDATE_TEST + "/Flight/src/release/res/xml/update_test_network.xml"));
        List<String> domains = new ArrayList<>();
        Matcher d = Pattern.compile("<domain[^>]*>([^<]*)</domain>").matcher(network);
        while (d.find()) domains.add(d.group(1).trim());
        assertEquals(List.of("10.0.2.2", "127.0.0.1"), domains);
        assertFalse(network.contains("base-config") || network.contains("includeSubdomains=\"true\""), "nothing else in plain");
        // never in what the released app is built from (ReleaseHasNoBenchCodeTest holds its words there too)
        try (Stream<Path> files = Files.walk(root.resolve("flight-app/overlay"))) {
            assertEquals(List.of(), files.map(p -> p.getFileName().toString())
                    .filter(n -> n.equals("UpdateTestSource.java") || n.equals("update_test_network.xml")).toList());
        }
    }

    /**
     * The update test's build's rule: overlay's UpdateSource patched as build.ps1 -UpdateTest patches its own source
     * copy, UpdateTestSource beside it and upstream's Log that it writes with, compiled at the phone app's Java 11 and
     * loaded apart from the PC's own UpdateSource.
     */
    private ClassLoader updateTestsBuild() throws Exception {
        Path root = root();
        Path src = Files.createDirectories(tmp.resolve("src/de/droiddrone/common"));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        String rule = text(root.resolve(RULE));
        assertTrue(rule.contains(ANCHOR), RULE + " has " + ANCHOR.trim());
        Files.writeString(src.resolve("UpdateSource.java"), rule.replace(ANCHOR, ANCHOR + "\n" + ADDED));
        Files.copy(root.resolve(VARIANT), src.resolve("UpdateTestSource.java"));
        Files.copy(root.resolve("upstream/Common/src/main/java/de/droiddrone/common/Log.java"), src.resolve("Log.java"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "the tests run on a JDK");
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        int exit = javac.run(null, said, said, "--release", "11", "-encoding", "UTF-8", "-proc:none", "-d", classes.toString(),
                src.resolve("UpdateSource.java").toString(), src.resolve("UpdateTestSource.java").toString(), src.resolve("Log.java").toString());
        assertEquals(0, exit, "the update test's rule compiles at Java 11: " + said);
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static Predicate<String> rule(ClassLoader build, String simpleName, String method) throws Exception {
        Class<?> c = build.loadClass("de.droiddrone.common." + simpleName);
        assertNotSame(UpdateSource.class, c, "the update test's own, not the PC's");
        Method m = c.getMethod(method, String.class);
        return url -> {
            try {
                return (Boolean) m.invoke(null, url);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(url, e);
            }
        };
    }
}
