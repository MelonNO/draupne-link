package de.droiddrone.testing;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the tests fit to run side by side (the build's forks, a DRAUPNE Link running on the same PC): no waiting
 * helper of a test's own ({@link Waits} has the one copy), no port written into a test and none of the app's own
 * fixed ports ({@link Ports} hands out free ones, UDP and TCP). The test sources are read as text, so this finds the
 * usual ways of writing either, not every way.
 */
class TestHygieneTest {
    /** A method named waitFor, or an await / waitUntil over a condition: Waits' job. */
    private static final Pattern OWN_WAIT = Pattern.compile(
            "\\b(?:void|boolean)\\s+(?:waitFor\\s*\\(|(?:await|waitUntil)\\s*\\(\\s*(?:java\\.util\\.function\\.)?BooleanSupplier\\b)");
    /** The ways a test names the port it binds; group 1 is the number. */
    private static final List<Pattern> BOUND_PORTS = List.of(
            // a socket on a number of its own
            Pattern.compile("new\\s+(?:java\\.net\\.)?(?:DatagramSocket|MulticastSocket|ServerSocket|InetSocketAddress)\\(\\s*(\\d[\\d_]*)\\s*[,)]"),
            // a link's own port (the last argument; a link made as a subclass too), a name service that answers on its port
            Pattern.compile("(?:new\\s+ControlLink|super)\\((?=[^;]*InetAddress)[^;]*,\\s*(\\d[\\d_]*)\\s*\\)\\s*;"),
            Pattern.compile("new\\s+NameService\\([^;]*,\\s*(\\d[\\d_]*)\\s*,\\s*true\\b"),
            // a constant or variable named for a port (PC_PORT, pcPort, port - not "report")
            Pattern.compile("\\b(?:\\w*_)?(?:PORT(?:_\\w*)?|port|[a-z]\\w*Port\\w*)\\s*=\\s*(\\d[\\d_]*)\\s*[;,]"));
    /**
     * A fixed port of the app's own named in a test (ApkShare.PORT): a DRAUPNE Link on this PC may hold it, and a test
     * holding it keeps that app from serving.
     */
    private static final Pattern APP_PORT = Pattern.compile("\\b[A-Z]\\w*\\.(?:\\w+_)?PORT\\b");

    @Test
    void noTestHasAWaitingHelperOrAPortNumberOfItsOwn() throws Exception {
        // the project's root: the tests run in build/test-work
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        Path tests = root.resolve("src/test/java");
        List<String> found = new ArrayList<>();
        int read = 0;
        try (Stream<Path> files = Files.walk(tests)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (f.getParent().endsWith("testing")) continue; // the helpers themselves, and this test's patterns
                read++;
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i), code = line.strip();
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) continue;
                    String at = tests.relativize(f).toString().replace('\\', '/') + ":" + (i + 1) + ": " + code;
                    if (OWN_WAIT.matcher(line).find()) found.add(at + "  <- use Waits");
                    for (Pattern p : BOUND_PORTS) {
                        Matcher m = p.matcher(line);
                        if (m.find() && Integer.parseInt(m.group(1).replace("_", "")) >= 1024) found.add(at + "  <- use Ports.free() / Ports.freeTcp()");
                    }
                    if (APP_PORT.matcher(line).find()) found.add(at + "  <- the app's own port: use Ports.free() / Ports.freeTcp()");
                }
            }
        }
        assertTrue(read > 100, "the test sources were found: " + read + " files under " + tests);
        assertEquals(List.of(), found, "tests with a waiting helper or a port number of their own");
    }

    @Test
    void thePatternsFindWhatTheyAreFor() {
        for (String own : List.of("    private static void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {",
                "    static boolean waitFor(java.util.function.BooleanSupplier c, long ms) {",
                "    private static void await(BooleanSupplier what, long ms, String why) throws InterruptedException {")) {
            assertTrue(OWN_WAIT.matcher(own).find(), own);
        }
        for (String fine : List.of("        waitFor(() -> link.isConnected(), 5000, \"connected\");",
                "        assertTrue(closed.await(20, TimeUnit.SECONDS), \"the copy was closed\");",
                "    private static boolean await(TileCache.Download d) throws InterruptedException {",
                "                .waitFor(() -> connected[0], \"Press CONNECT\", true);")) {
            assertTrue(!OWN_WAIT.matcher(fine).find(), fine);
        }
        for (String bound : List.of("    private static final int PHONE_PORT = 46286;",
                "        new DatagramSocket(46287).close();",
                "            socket = new DatagramSocket(46310, InetAddress.getLoopbackAddress());",
                "        try (ServerSocket taken = new ServerSocket(8765)) {",
                "        link = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), phone.port(), 46391);",
                "        svc = new NameService(config, names, () -> status, 6286, true, m -> { }, log::add);")) {
            assertTrue(BOUND_PORTS.stream().anyMatch(p -> p.matcher(bound).find()), bound);
        }
        for (String fine : List.of("        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {",
                "        private final int phonePort = Ports.free(), pcPort = Ports.free();",
                "        assertEquals(8888, r.pcPort);",
                "        service = new NameService(config, names, () -> null, 6286, false, saved::putAll, s -> { });")) {
            for (Pattern p : BOUND_PORTS) {
                Matcher m = p.matcher(fine);
                assertTrue(!m.find() || Integer.parseInt(m.group(1).replace("_", "")) < 1024, fine);
            }
        }
        for (String app : List.of("            String base = \"http://127.0.0.1:\" + ApkShare.PORT + \"/\";",
                "        assertTrue(rules.contains(de.droiddrone.pc.setup.ApkShare.PORT));",
                "        server.bind(new InetSocketAddress(Relay.UDP_PORT));")) {
            assertTrue(APP_PORT.matcher(app).find(), app);
        }
        for (String fine : List.of("            share.start(apk, port);",
                "        assertEquals(MotionRecord.KIND_REPORT, b.kind);",
                "        int n = Limits.PORT_COUNT;",
                "        String s = report.SUPPORT;")) {
            assertTrue(!APP_PORT.matcher(fine).find(), fine);
        }
    }
}
