package de.droiddrone.bench;

import de.droiddrone.pc.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The test bench's own place on the disk: every scenario gets an empty "user" of its own - its settings folder, its
 * Documents and Videos - under the run's folder ({@code -Dbench.root}, outside the repo, kept after the run). The app
 * under test is pointed there before it starts, and nothing of the bench runs unless it is: the pilot's real
 * %APPDATA%\DraupneLink and Documents\DRAUPNE Link are never read or written by a bench instance of the app.
 */
public final class Sandbox {
    private static final AtomicInteger COUNT = new AtomicInteger();
    /**
     * The folders off the run's disk the user in use may have its home or settings in (a small disk of a scenario's
     * own: {@link #fresh(String, Path, Path)}); none for every other scenario.
     */
    private static volatile List<Path> elsewhereNow = List.of();
    private final String name;
    private final Path root;
    private final Path home, appData;
    private final List<Path> elsewhere;

    private Sandbox(String name, Path root) {
        this(name, root, root.resolve("home"), root.resolve("appdata"), List.of());
    }

    private Sandbox(String name, Path root, Path home, Path appData, List<Path> elsewhere) {
        this.name = name;
        this.root = root;
        this.home = home;
        this.appData = appData;
        this.elsewhere = List.copyOf(elsewhere);
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
        return fresh(scenario, null, null);
    }

    /**
     * An empty user whose home (Documents, Videos) or settings folder (%APPDATA%) is on another disk: a small one of the
     * scenario's own, which it fills ({@code ui/DiskFullScenario}). Each goes into a folder of this user's own there
     * ({@code home().getParent()}); null keeps it in the run's folder as for any scenario. The film and everything else
     * of the bench stay in the run's.
     */
    public static Sandbox fresh(String scenario, Path homeOn, Path appDataOn) throws IOException {
        String safe = scenario.replaceAll("[^A-Za-z0-9._-]", "_");
        String own = safe + "-" + ProcessHandle.current().pid() + "-" + COUNT.incrementAndGet();
        Path root = runRoot().resolve("scenarios").resolve(own);
        // the fence lets the app write on all of that disk, as on all of the run's folder: what is left running of the
        // scenario before (in the same JVM) still writes into its own user's folder there for a moment
        List<Path> elsewhere = new ArrayList<>();
        Path home = root.resolve("home"), appData = root.resolve("appdata");
        if (homeOn != null) {
            Path disk = homeOn.toAbsolutePath().normalize();
            elsewhere.add(disk);
            home = disk.resolve(own).resolve("home");
        }
        if (appDataOn != null) {
            Path disk = appDataOn.toAbsolutePath().normalize();
            if (!elsewhere.contains(disk)) elsewhere.add(disk);
            appData = disk.resolve(own).resolve("appdata");
        }
        Sandbox s = new Sandbox(safe, root, home, appData, elsewhere);
        Files.createDirectories(root);
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

    /** The same user again, its folders where they were - on another disk too ({@link #fresh(String, Path, Path)}). */
    public static Sandbox reopen(Sandbox before, String scenario) {
        Sandbox s = new Sandbox(scenario, before.root, before.home, before.appData, before.elsewhere);
        s.enter();
        return s;
    }

    /** Points the app at this user and proves it; throws before anything of the app has run otherwise. */
    public void enter() {
        elsewhereNow = elsewhere;
        System.setProperty("user.home", home().toString());
        System.setProperty("draupne.appData", appData().toString());
        require();
    }

    /** The disks off the run's this user's home or settings are on (the fence lets the app write there); mostly none. */
    public List<Path> elsewhere() {
        return elsewhere;
    }

    /**
     * The app's folders resolve inside the run's folder - or on a scenario's own small disk ({@link #fresh(String, Path,
     * Path)}) - or nothing goes on.
     */
    public static void require() {
        Path run = runRoot();
        Path data = AppPaths.dataDir().toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (!sealed(data, run) || !sealed(home, run)) {
            throw new IllegalStateException("The bench is not sealed: the app's data folder is " + data + ", its home " + home
                    + " - both must be inside " + run + (elsewhereNow.isEmpty() ? "" : " or " + elsewhereNow));
        }
    }

    private static boolean sealed(Path p, Path run) {
        if (p.startsWith(run)) return true;
        for (Path e : elsewhereNow) if (p.startsWith(e)) return true;
        return false;
    }

    public String name() {
        return name;
    }

    public Path root() {
        return root;
    }

    public Path home() {
        return home;
    }

    public Path appData() {
        return appData;
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
