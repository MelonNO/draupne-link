package de.droiddrone.bench;

import de.droiddrone.pc.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The test bench's own place on the disk: every scenario gets an empty "user" of its own - its settings folder, its
 * Documents and Videos - under the run's folder ({@code -Dbench.root}, outside the repo, kept after the run). The app
 * under test is pointed there before it starts, and nothing of the bench runs unless it is: the pilot's real
 * %APPDATA%\DraupneLink and Documents\DRAUPNE Link are never read or written by a bench instance of the app.
 */
public final class Sandbox {
    private static final AtomicInteger COUNT = new AtomicInteger();
    private final String name;
    private final Path root;

    private Sandbox(String name, Path root) {
        this.name = name;
        this.root = root;
    }

    /** The run's folder: the bench tasks set it; a test started any other way has none and must not go on. */
    public static Path runRoot() {
        String r = System.getProperty("bench.root");
        if (r == null || r.isBlank()) throw new IllegalStateException("No -Dbench.root: start bench tests with the bench tasks (gradlew benchUi ...), never with 'test'");
        return Path.of(r).toAbsolutePath().normalize();
    }

    /** True when this JVM was started by a bench task. */
    public static boolean inBench() {
        String r = System.getProperty("bench.root");
        return r != null && !r.isBlank();
    }

    /** An empty user for this scenario; the app's folders point into it from now on. */
    public static Sandbox fresh(String scenario) throws IOException {
        String safe = scenario.replaceAll("[^A-Za-z0-9._-]", "_");
        Path root = runRoot().resolve("scenarios").resolve(safe + "-" + ProcessHandle.current().pid() + "-" + COUNT.incrementAndGet());
        Sandbox s = new Sandbox(safe, root);
        Files.createDirectories(s.home());
        Files.createDirectories(s.appData());
        s.enter();
        return s;
    }

    /** The same user again (after the app was closed or killed): its folders as they were left. */
    public static Sandbox reopen(Path root, String scenario) {
        Sandbox s = new Sandbox(scenario, root.toAbsolutePath().normalize());
        s.enter();
        return s;
    }

    /** Points the app at this user and proves it; throws before anything of the app has run otherwise. */
    public void enter() {
        System.setProperty("user.home", home().toString());
        System.setProperty("draupne.appData", appData().toString());
        require();
    }

    /** The app's folders resolve inside the run's folder - or nothing goes on. */
    public static void require() {
        Path run = runRoot();
        Path data = AppPaths.dataDir().toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (!data.startsWith(run) || !home.startsWith(run)) {
            throw new IllegalStateException("The bench is not sealed: the app's data folder is " + data + ", its home " + home
                    + " - both must be inside " + run);
        }
    }

    public String name() {
        return name;
    }

    public Path root() {
        return root;
    }

    public Path home() {
        return root.resolve("home");
    }

    public Path appData() {
        return root.resolve("appdata");
    }

    /** %APPDATA%\DraupneLink of this user. */
    public Path dataDir() {
        return appData().resolve("DraupneLink");
    }

    public Path settingsFile() {
        return dataDir().resolve("draupne-link.properties");
    }

    public Path logsDir() {
        return dataDir().resolve("logs");
    }

    /** Documents\DRAUPNE Link of this user. */
    public Path documents() {
        return home().resolve("Documents").resolve("DRAUPNE Link");
    }

    public Path tracks() {
        return documents().resolve("Tracks");
    }

    public Path videos() {
        return home().resolve("Videos").resolve("DRAUPNE Link");
    }

    /** Where this scenario's pictures and step log go: the run's report reads them from here. */
    public Path film() throws IOException {
        return Files.createDirectories(runRoot().resolve("film").resolve(root.getFileName().toString()));
    }

    /** The real user's settings folder (only ever read: the armed guard). Null when the bench task did not say. */
    public static Path realAppData() {
        String r = System.getProperty("bench.realAppData");
        return r == null || r.isBlank() ? null : Path.of(r);
    }
}
