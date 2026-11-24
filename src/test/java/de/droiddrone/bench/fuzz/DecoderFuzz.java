package de.droiddrone.bench.fuzz;

import de.droiddrone.bench.fuzz.FuzzInput.Target;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The video decoding fuzzed: made-up streams and clips ({@link Mutator}) fed through the app's own decoding
 * ({@link FuzzChild}: VideoDecoder as the live video, the overwatch preview, the OSD recording and STANAG use it, and
 * ClipPlayer for clips and recordings) for hours, in a child JVM, so that a native crash in FFmpeg ends the child and
 * not this. This one watches: a child that dies (an exit without its DONE, a JVM crash report) or hangs (an input
 * taking more than timeoutMs of CPU, or a dozen times that of wall time) leaves its input, its last lines and its crash
 * report in a folder of their own; the input is run once more in a fresh child to see whether it does it again; a new
 * child goes on with the next input. A Java exception out of the decoding is no crash: each kind's first inputs are
 * kept, and counted.
 * <p>
 * {@code gradlew decoderFuzz -Phours=4 -Pseed=1} (docs/TESTING.md). Results under
 * %LOCALAPPDATA%\DraupneBench\fuzz\&lt;time&gt;-seed&lt;seed&gt;: summary.txt, progress.txt, findings\, exceptions\, seeds\,
 * children.log. Nothing of the pilot's is read or written: the child's APPDATA, LOCALAPPDATA, home and temp are the
 * run's own.
 */
public final class DecoderFuzz {
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int RING = 200, MAX_FINDINGS = 200, MAX_EXCEPTION_SAMPLES = 100, SAMPLES_PER_KIND = 3, SEQUENCE_MAX = 2000;
    private static final long CHILDREN_LOG_MAX = 50L << 20, PROGRESS_EVERY_MS = 60_000;

    /** What a run is told. */
    public static final class Options {
        public double hours = 1;
        public long seed = System.currentTimeMillis() % 1_000_000;
        /** The CPU time one input may take (all of the child's threads); wall time: wallFactor times that. */
        public long timeoutMs = 10_000;
        public long wallFactor = 12;
        public Path root = defaultRoot();
        public int maxMemMb = 2048;
        public int cores = 2;
        public EnumSet<Target> targets = EnumSet.allOf(Target.class);
        public Path seedDir;
        /** An earlier run's seeds folder: its seeds taken as they are, none made (with its seed: the same inputs). */
        public Path seedsFrom;
        /** The first input's number (to run a finding's inputs again, from where its child started). */
        public long firstIndex;
        /** Whether the seeds may come from this PC's own H.265 encoders too (off: the same seeds on any PC). */
        public boolean pcEncoders = true;
        /** How many findings are run once more, in a fresh child. */
        public int replays = 20;
        /** The child at the lowest priority on its own few cores. */
        public boolean polite = true;
        // the self test's: nothing decoded, the child told to misbehave, a last input, the seeds given
        public boolean dry;
        public String misbehave = "";
        public long lastIndex = -1;
        public List<FuzzInput> seeds;
        public PrintStream out = System.out;

        static Path defaultRoot() {
            String local = System.getenv("LOCALAPPDATA");
            return Path.of(local != null ? local : System.getProperty("java.io.tmpdir"), "DraupneBench", "fuzz");
        }
    }

    /** A crash, a hang or memory over the cap, at one input. */
    public record Finding(String kind, long index, Path folder, String detail, String again) {
    }

    public record Summary(Path run, long inputs, double seconds, List<Finding> findings, long peakMb, int children, long exceptions,
                          Map<String, Integer> exceptionKinds, Map<String, Long> counts, String problem, String text) {
        public long count(String kind) {
            return findings.stream().filter(f -> f.kind.equals(kind)).count();
        }

        /** No JVM crash and no hang: the test's pass. */
        public boolean passed() {
            return problem == null && count("crash") == 0 && count("hang") == 0;
        }
    }

    private final Options o;
    private final Path run, findingsDir, exceptionsDir, hsDir, work, tmp, sealed;
    private BufferedWriter childrenLog;
    private long childrenLogBytes;
    private Mutator mutator;
    private final List<Finding> findings = new ArrayList<>();
    private int findingCount, replaysLeft, children;
    private final Map<String, Integer> exceptionKinds = new LinkedHashMap<>();
    private int exceptionSamples;
    private final Map<String, Long> totals = new HashMap<>();
    private long inputs, peakMb, exceptions;
    private long fuzzStart, fuzzEnd, nextProgress;
    private volatile Process currentChild;
    /** Inputs the mutator itself failed to make (the child says MUTATOR): a fault of this tool's, left out. */
    private final AtomicLong mutatorFaults = new AtomicLong();
    private String ffmpeg = "?";

    private DecoderFuzz(Options o, Path run) {
        this.o = o;
        this.run = run;
        findingsDir = run.resolve("findings");
        exceptionsDir = run.resolve("exceptions");
        hsDir = run.resolve("hs");
        work = run.resolve("work");
        tmp = run.resolve("tmp");
        sealed = run.resolve("sealed");
        replaysLeft = o.replays;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (String s : args) {
            int eq = s.indexOf('=');
            if (eq > 0 && !s.substring(eq + 1).isBlank()) a.put(s.substring(0, eq), s.substring(eq + 1).trim());
        }
        Options o = new Options();
        if (a.containsKey("hours")) o.hours = Double.parseDouble(a.get("hours"));
        if (a.containsKey("seed")) o.seed = Long.parseLong(a.get("seed"));
        if (a.containsKey("timeoutMs")) o.timeoutMs = Long.parseLong(a.get("timeoutMs"));
        if (a.containsKey("out")) o.root = Path.of(a.get("out"));
        if (a.containsKey("maxMemMb")) o.maxMemMb = Integer.parseInt(a.get("maxMemMb"));
        if (a.containsKey("cores")) o.cores = Integer.parseInt(a.get("cores"));
        if (a.containsKey("targets")) o.targets = FuzzChild.targets(a.get("targets"));
        if (a.containsKey("seedDir")) o.seedDir = Path.of(a.get("seedDir"));
        // to look into a finding: the same seeds (an earlier run's seeds folder), only some inputs, as the run had them
        if (a.containsKey("seedsFrom")) o.seedsFrom = Path.of(a.get("seedsFrom"));
        if (a.containsKey("first")) o.firstIndex = Long.parseLong(a.get("first"));
        if (a.containsKey("last")) o.lastIndex = Long.parseLong(a.get("last"));
        if (a.containsKey("pcEncoders")) o.pcEncoders = Boolean.parseBoolean(a.get("pcEncoders"));
        if (a.containsKey("polite")) o.polite = Boolean.parseBoolean(a.get("polite"));
        if (a.containsKey("replay")) {
            System.exit(replayOne(o, Path.of(a.get("replay"))) ? 0 : 1);
        }
        Summary s = run(o);
        System.exit(s.passed() ? 0 : 1);
    }

    /** A whole run: seeds, then children until the time is up (or the last input of a self test). */
    public static Summary run(Options o) throws IOException, InterruptedException {
        DecoderFuzz f = new DecoderFuzz(o, newRunFolder(o.root, "seed" + o.seed));
        Thread hook = new Thread(f::stopChild, "decoder-fuzz-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return f.go();
        } finally {
            f.stopChild();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // shutting down already
            }
        }
    }

    /** One input (a finding's input.dfz) run again in a child, said how it went. @return whether it ran through */
    static boolean replayOne(Options o, Path input) throws IOException, InterruptedException {
        FuzzInput in = FuzzInput.read(input);
        DecoderFuzz f = new DecoderFuzz(o, newRunFolder(o.root, "replay"));
        f.prepare();
        o.out.println("Running " + input + " (" + in.describe() + ") in a child, " + f.run);
        Outcome r = f.watch(f.start(f.oneCommand(input, 0)), 5 * 60_000L, false);
        o.out.println(r.what());
        for (String line : r.lastLines) if (!line.startsWith("AT ")) o.out.println("  | " + line);
        f.closeLog();
        return r.kind.equals("done");
    }

    private static Path newRunFolder(Path root, String tag) throws IOException {
        String name = LocalDateTime.now().format(FOLDER) + "-" + tag;
        Path p = root.resolve(name);
        for (int i = 2; Files.exists(p); i++) p = root.resolve(name + "-" + i);
        return Files.createDirectories(p);
    }

    private void prepare() throws IOException {
        for (Path p : List.of(findingsDir, hsDir, work, tmp, sealed.resolve("appdata"), sealed.resolve("localappdata"), sealed.resolve("home"))) {
            Files.createDirectories(p);
        }
        childrenLog = Files.newBufferedWriter(run.resolve("children.log"), StandardCharsets.UTF_8);
    }

    private Summary go() throws IOException, InterruptedException {
        prepare();
        Files.writeString(run.resolve("run.txt"), String.join(System.lineSeparator(),
                "Decoder fuzz, started " + LocalDateTime.now().withNano(0),
                "seed " + o.seed + ", " + o.hours + " h, targets " + o.targets + ", an input may take " + o.timeoutMs + " ms of CPU ("
                        + o.timeoutMs * o.wallFactor / 1000 + " s on the clock), the child " + o.maxMemMb + " MB at most, "
                        + (o.polite ? "lowest priority on " + o.cores + " cores" : "normal priority"),
                "more seeds: " + (o.seedDir == null ? "none" : o.seedDir),
                "java " + System.getProperty("java.version")) + System.lineSeparator(), StandardCharsets.UTF_8);
        o.out.println("Decoder fuzz: " + run);
        String problem = seeds();
        if (problem == null) problem = fuzz();
        String text = summaryText(problem);
        Files.writeString(run.resolve("summary.txt"), text, StandardCharsets.UTF_8);
        o.out.print(text);
        closeLog();
        return new Summary(run, inputs, seconds(), List.copyOf(findings), peakMb, children, exceptions, Map.copyOf(exceptionKinds), Map.copyOf(totals), problem, text);
    }

    // ------------------------------------------------------------------------------------------------- seeds

    private String seeds() throws IOException, InterruptedException {
        Path dir = Files.createDirectories(run.resolve("seeds"));
        if (o.seeds != null) {
            for (int i = 0; i < o.seeds.size(); i++) o.seeds.get(i).write(dir.resolve(String.format(Locale.ROOT, "%02d-given.dfz", i)));
        } else if (o.seedsFrom != null) {
            try (java.util.stream.Stream<Path> files = Files.list(o.seedsFrom)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".dfz")).toList()) Files.copy(f, dir.resolve(f.getFileName()));
            }
        } else {
            List<String> cmd = childCommand("seeds", run.toString(), o.seedDir == null ? "" : o.seedDir.toString(), o.pcEncoders ? "pc" : "same",
                    String.valueOf(o.cores));
            Outcome r = watch(start(cmd), 15 * 60_000L, false);
            for (String line : r.lastLines) if (line.startsWith("seed ") || line.startsWith("skipped") || line.startsWith("no ")) o.out.println("  " + line);
            if (!r.kind.equals("done")) return "the seeds could not be made: " + r.what() + tail(r);
        }
        List<FuzzInput> seeds = SeedMaker.load(dir);
        try {
            mutator = new Mutator(seeds, o.targets);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        o.out.println(seeds.size() + " seeds in " + dir + "; targets " + mutator.targets());
        return null;
    }

    // ------------------------------------------------------------------------------------------------- the run

    private String fuzz() throws IOException, InterruptedException {
        fuzzStart = System.currentTimeMillis();
        fuzzEnd = fuzzStart + Math.round(o.hours * 3_600_000);
        nextProgress = fuzzStart + PROGRESS_EVERY_MS;
        long next = o.firstIndex;
        int startFailures = 0;
        while (true) {
            if (System.currentTimeMillis() >= fuzzEnd || (o.lastIndex >= 0 && next > o.lastIndex)) return null;
            Outcome r = watch(start(childCommand("fuzz", run.toString(), String.valueOf(o.seed), String.valueOf(next), String.valueOf(fuzzEnd),
                    String.valueOf(o.lastIndex), String.valueOf(o.maxMemMb), String.valueOf(o.cores), targetList())), 5 * 60_000L, true);
            inputs += r.ats;
            if (r.kind.equals("done")) return null;
            if (r.atIndex < 0) {
                if (++startFailures >= 3) return "the child ended before its first input three times running: " + r.what() + tail(r);
                continue;
            }
            startFailures = 0;
            finding(r, next);
            next = r.atIndex + 1;
        }
    }

    /** A child's last lines (not its AT lines), for a problem said in the summary; the rest are in children.log. */
    private static String tail(Outcome r) {
        List<String> lines = r.lastLines.stream().filter(l -> !l.startsWith("AT ")).toList();
        lines = lines.subList(Math.max(0, lines.size() - 12), lines.size());
        return lines.isEmpty() ? " (children.log)" : " - its last lines (children.log has all):" + System.lineSeparator() + "    | "
                + String.join(System.lineSeparator() + "    | ", lines);
    }

    private String targetList() {
        StringBuilder s = new StringBuilder();
        for (Target t : o.targets) s.append(s.isEmpty() ? "" : ",").append(t.name().toLowerCase(Locale.ROOT));
        return s.toString();
    }

    private double seconds() {
        return fuzzStart == 0 ? 0 : (System.currentTimeMillis() - fuzzStart) / 1000.0;
    }

    /** What one child came to. kind: done, crash, hang, memory, startup. */
    private record Outcome(String kind, int exit, long atIndex, String atCrc, long ats, List<String> lastLines, long cpuMs, long wallMs,
                           String hsErr, String memLine) {
        String what() {
            return switch (kind) {
                case "done" -> "ran through";
                case "hang" -> "hung at input " + atIndex + " (" + cpuMs + " ms of CPU, " + wallMs + " ms on the clock)";
                case "memory" -> "over the memory cap at input " + atIndex + " (" + memLine + ")";
                case "startup" -> "ended before its first input (exit " + exit + ")";
                default -> "crashed at input " + atIndex + " (" + exitMeaning(exit) + (hsErr != null ? ", JVM crash report" : "") + ")";
            };
        }
    }

    static String exitMeaning(int exit) {
        return switch (exit) {
            case 1 -> "exit 1: a JVM crash report or an exception out of main";
            case 3 -> "exit 3: abort()";
            case FuzzChild.EXIT_MISBEHAVED -> "exit " + exit + ": ended on purpose";
            case 0xC0000005 -> "0xC0000005: access violation";
            case 0xC0000409 -> "0xC0000409: stack buffer overrun / fast fail (abort)";
            case 0xC00000FD -> "0xC00000FD: stack overflow";
            case 0xC0000374 -> "0xC0000374: heap corruption found by Windows (memory spoilt, maybe by an input before)";
            default -> "exit " + exit + (exit < -1 || exit > 255 ? String.format(Locale.ROOT, " (0x%08X)", exit) : "");
        };
    }

    /**
     * A child process and what it said so far: its output is the protocol (only the child's own lines are written
     * there), its error stream what FFmpeg and the JVM say besides - each read by a thread of its own, both into the
     * last lines kept.
     */
    private final class Child {
        final Process process;
        final ArrayDeque<String> ring = new ArrayDeque<>();
        final ConcurrentLinkedQueue<String> excLines = new ConcurrentLinkedQueue<>();
        final AtomicLong ats = new AtomicLong();
        volatile long atIndex = -1;
        volatile String atCrc = "";
        volatile boolean ready, done;
        volatile String memLine;
        /** The child's newest counts (its STAT line): its own, from its start. */
        volatile Map<String, Long> lastStat = Map.of();
        final Thread reader, errors;

        Child(Process p) {
            process = p;
            reader = new Thread(() -> read(p.getInputStream(), true), "decoder-fuzz-out-" + p.pid());
            errors = new Thread(() -> read(p.getErrorStream(), false), "decoder-fuzz-err-" + p.pid());
            for (Thread t : List.of(reader, errors)) {
                t.setDaemon(true);
                t.start();
            }
        }

        private void read(java.io.InputStream stream, boolean protocol) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (ring) {
                        ring.addLast(protocol ? line : "! " + line);
                        if (ring.size() > RING) ring.removeFirst();
                    }
                    try {
                        if (protocol) take(line);
                        else log(process.pid() + " ! " + line);
                    } catch (RuntimeException e) {
                        log(process.pid() + " (a line not understood: " + e + ") " + line);
                    }
                }
            } catch (IOException e) {
                log(process.pid() + " reader: " + e);
            }
        }

        private void take(String line) {
            if (line.startsWith("AT ")) {
                String[] p = line.split(" ");
                atCrc = p.length > 2 ? p[2] : "";
                atIndex = Long.parseLong(p[1]);
                ats.incrementAndGet();
                return;
            }
            log(process.pid() + " " + line);
            if (line.startsWith("READY")) {
                ready = true;
                if (line.startsWith("READY FFmpeg ")) ffmpeg = line.substring(13);
            } else if (line.startsWith("DONE")) {
                done = true;
            } else if (line.startsWith("MEM ")) {
                memLine = line;
            } else if (line.startsWith("MUTATOR ")) {
                mutatorFaults.incrementAndGet();
            } else if (line.startsWith("EXC ")) {
                excLines.add(line);
            } else if (line.startsWith("STAT ")) {
                lastStat = parseStat(line);
                peak(lastStat.getOrDefault("peakMb", 0L));
            }
        }

        List<String> lastLines() {
            synchronized (ring) {
                return List.copyOf(ring);
            }
        }
    }

    static Map<String, Long> parseStat(String line) {
        Map<String, Long> m = new HashMap<>();
        for (String part : line.substring(5).split(" ")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq), value = part.substring(eq + 1);
            if (value.contains("/")) { // inputs/pictures
                String[] rp = value.split("/");
                m.put(key, Long.parseLong(rp[0]));
                m.put(key + "Pictures", Long.parseLong(rp[1]));
            } else {
                m.put(key, Long.parseLong(value));
            }
        }
        return m;
    }

    private synchronized void peak(long mb) {
        peakMb = Math.max(peakMb, mb);
    }

    /** A fuzz child's last counts into the run's. */
    private synchronized void addStats(Map<String, Long> m) {
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (!e.getKey().endsWith("Mb")) totals.merge(e.getKey(), e.getValue(), Long::sum);
        }
    }

    private List<String> childCommand(String... args) {
        List<String> cmd = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        cmd.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
        String cache = System.getProperty("org.bytedeco.javacpp.cachedir", Path.of(System.getProperty("user.home"), ".javacpp", "cache").toString());
        cmd.addAll(List.of("-Xmx512m", "-XX:+UseSerialGC", "-XX:-CreateCoredumpOnCrash", "-XX:-UsePerfData",
                "-XX:ErrorFile=" + hsDir.resolve("hs_err_pid%p.log"),
                "-Dorg.bytedeco.javacpp.maxPhysicalBytes=0", "-Dorg.bytedeco.javacpp.cachedir=" + cache,
                "-Djava.awt.headless=true", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Duser.home=" + sealed.resolve("home"), "-Djava.io.tmpdir=" + tmp,
                "-D" + FuzzChild.POLITE + "=" + o.polite));
        if (o.dry) cmd.add("-D" + FuzzChild.DRY + "=true");
        if (!o.misbehave.isEmpty()) cmd.add("-D" + FuzzChild.MISBEHAVE + "=" + o.misbehave);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(FuzzChild.class.getName());
        cmd.addAll(List.of(args));
        return cmd;
    }

    private List<String> oneCommand(Path input, long index) {
        return childCommand("one", input.toString(), String.valueOf(index), work.toString(), String.valueOf(o.maxMemMb), String.valueOf(o.cores));
    }

    private Child start(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile());
        Map<String, String> env = pb.environment();
        env.put("APPDATA", sealed.resolve("appdata").toString());
        env.put("LOCALAPPDATA", sealed.resolve("localappdata").toString());
        env.put("TEMP", tmp.toString());
        env.put("TMP", tmp.toString());
        Process p = pb.start(); // its input stays open: the child ends when it closes (this JVM gone)
        currentChild = p;
        children++;
        return new Child(p);
    }

    /**
     * Watches a child until it ends, hangs at an input, or does not get ready in time.
     *
     * @param fuzzing a fuzz child: exceptions saved, progress said
     */
    private Outcome watch(Child c, long startupMs, boolean fuzzing) throws InterruptedException, IOException {
        long started = System.currentTimeMillis();
        long lastIndex = -2, indexCpu = 0, indexWall = started, cpu = 0, now = started;
        String hangKind = null;
        while (c.process.isAlive()) {
            now = System.currentTimeMillis();
            cpu = Math.max(cpu, cpuMs(c.process));
            long index = c.atIndex;
            if (index != lastIndex) {
                lastIndex = index;
                indexCpu = cpu;
                indexWall = now;
            }
            if (fuzzing) {
                exceptionSamples(c);
                progress(now, c);
            }
            if (index < 0 && !c.done && !c.ready && now - started > startupMs) {
                hangKind = "startup";
            } else if (index >= 0 && !c.done && (cpu - indexCpu > o.timeoutMs || now - indexWall > o.timeoutMs * o.wallFactor)) {
                hangKind = "hang";
            }
            if (hangKind != null) {
                kill(c.process);
                break;
            }
            c.process.waitFor(200, TimeUnit.MILLISECONDS);
        }
        if (hangKind == null) now = System.currentTimeMillis();
        c.process.waitFor(10, TimeUnit.SECONDS);
        c.reader.join(10_000);
        c.errors.join(2_000);
        if (fuzzing) {
            exceptionSamples(c);
            addStats(c.lastStat);
        }
        int exit = c.process.isAlive() ? -1 : c.process.exitValue();
        if (currentChild == c.process) currentChild = null;
        Path hs = hsDir.resolve("hs_err_pid" + c.process.pid() + ".log");
        String hsErr = Files.exists(hs) ? hs.toString() : null;
        String kind;
        if (hangKind != null) kind = c.atIndex < 0 ? "startup" : hangKind;
        else if (c.memLine != null) kind = "memory";
        else if (c.done && exit == 0) kind = "done";
        else kind = c.atIndex < 0 ? "startup" : "crash";
        return new Outcome(kind, exit, c.atIndex, c.atCrc, c.ats.get(), c.lastLines(), Math.max(0, cpu - indexCpu), now - indexWall, hsErr, c.memLine);
    }

    private static long cpuMs(Process p) {
        try {
            return p.toHandle().info().totalCpuDuration().map(Duration::toMillis).orElse(0L);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void kill(Process p) throws InterruptedException {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        p.waitFor(10, TimeUnit.SECONDS);
    }

    private void stopChild() {
        Process p = currentChild;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------------------------------------- findings

    /** @param first the first input of the child that had it */
    private void finding(Outcome r, long first) throws IOException, InterruptedException {
        int n = ++findingCount;
        if (n > MAX_FINDINGS) {
            findings.add(new Finding(r.kind, r.atIndex, null, r.what(), "not kept: more than " + MAX_FINDINGS + " findings"));
            return;
        }
        Path dir = Files.createDirectories(findingsDir.resolve(String.format(Locale.ROOT, "%03d-%s-input%d", n, r.kind, r.atIndex)));
        FuzzInput in = mutator.make(o.seed, r.atIndex);
        Path input = dir.resolve("input.dfz");
        in.write(input);
        if (in.file() != null) Files.write(dir.resolve("input." + FuzzInput.extension(in.file().kind())), in.file().data());
        Files.write(dir.resolve("log.txt"), r.lastLines, StandardCharsets.UTF_8);
        String frame = null;
        if (r.hsErr != null) {
            Path hs = Files.move(Path.of(r.hsErr), dir.resolve("hs_err.log"), StandardCopyOption.REPLACE_EXISTING);
            frame = problematicFrame(hs);
        }
        String crc = Long.toHexString(in.crc());
        String again = "not run again (more than " + o.replays + " findings)";
        if (replaysLeft-- > 0) {
            Outcome a = watch(start(oneCommand(input, r.atIndex)), 5 * 60_000L, false);
            again = a.kind.equals(r.kind) ? "yes, the same in a fresh child: " + a.what() : "not alone, in a fresh child: " + a.what();
            if (a.hsErr != null) Files.move(Path.of(a.hsErr), dir.resolve("hs_err-again.log"), StandardCopyOption.REPLACE_EXISTING);
            if (!a.kind.equals(r.kind) && first < r.atIndex) {
                // memory spoilt by an input before, found out at this one: the child's inputs up to it, once more in one child
                long from = Math.max(first, r.atIndex - SEQUENCE_MAX);
                Outcome s = watch(start(childCommand("fuzz", run.toString(), String.valueOf(o.seed), String.valueOf(from), String.valueOf(Long.MAX_VALUE),
                        String.valueOf(r.atIndex), String.valueOf(o.maxMemMb), String.valueOf(o.cores), targetList())), 5 * 60_000L, false);
                again += "; inputs " + from + " to " + r.atIndex + " once more in one child: "
                        + (s.kind.equals(r.kind) && s.atIndex == r.atIndex ? "yes, the same" : s.what());
                if (s.hsErr != null) Files.move(Path.of(s.hsErr), dir.resolve("hs_err-sequence.log"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String detail = r.what() + (frame != null ? "; " + frame : "");
        List<String> about = new ArrayList<>(List.of(
                r.kind.toUpperCase(Locale.ROOT) + " at input " + r.atIndex + " of the run with seed " + o.seed,
                detail,
                "the child's exit: " + exitMeaning(r.exit),
                "the input: " + in.describe(),
                crc.equals(r.atCrc) ? "the input here is the one the child had (crc " + crc + ")"
                        : "THE INPUT HERE DIFFERS from the child's (crc " + r.atCrc + " there, " + crc + " here): the mutator is not repeatable",
                "again: " + again,
                "",
                "To run it again: gradlew decoderFuzz -Preplay=" + input,
                "(input.dfz: the input; " + (in.file() != null ? "input." + FuzzInput.extension(in.file().kind()) + ": the clip file itself; " : "")
                        + "log.txt: the child's last lines" + (r.hsErr != null ? "; hs_err.log: the JVM's crash report" : "") + ")"));
        Files.write(dir.resolve("about.txt"), about, StandardCharsets.UTF_8);
        findings.add(new Finding(r.kind, r.atIndex, dir, detail, again));
        o.out.println("FOUND " + r.kind + ": " + detail + " -> " + dir);
        log("FOUND " + r.kind + " " + detail + " -> " + dir + "; again: " + again);
    }

    /** The JVM crash report's "Problematic frame" line, the library and place it crashed in. */
    static String problematicFrame(Path hsErr) {
        try {
            List<String> lines = Files.readAllLines(hsErr, StandardCharsets.ISO_8859_1);
            for (int i = 0; i + 1 < lines.size(); i++) {
                if (lines.get(i).contains("Problematic frame:")) return "problematic frame: " + lines.get(i + 1).replaceFirst("^#\\s*", "").trim();
            }
        } catch (IOException ignored) {
            // no frame then
        }
        return null;
    }

    /** Each kind of exception's first inputs, kept (the child says each kind's first three). */
    private void exceptionSamples(Child c) throws IOException {
        String line;
        while ((line = c.excLines.poll()) != null) {
            String[] p = line.split(" ", 3);
            if (p.length < 3) continue;
            long index = Long.parseLong(p[1]);
            String kind = p[2];
            int seen = exceptionKinds.merge(kind, 1, Integer::sum);
            if (seen > SAMPLES_PER_KIND || exceptionSamples >= MAX_EXCEPTION_SAMPLES) continue;
            exceptionSamples++;
            Path dir = Files.createDirectories(exceptionsDir);
            mutator.make(o.seed, index).write(dir.resolve(String.format(Locale.ROOT, "input%d.dfz", index)));
            Files.writeString(dir.resolve("list.txt"), "input" + index + ".dfz  " + kind + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private void progress(long now, Child c) throws IOException {
        if (now < nextProgress) return;
        nextProgress = now + PROGRESS_EVERY_MS;
        long ran = inputs + c.ats.get();
        double s = (now - fuzzStart) / 1000.0;
        String line = String.format(Locale.ROOT, "%s  %.2f h of %.2f h: %,d inputs (%.1f a second), crashes %d, hangs %d, over memory %d, exceptions of %d kinds, largest %d MB",
                LocalDateTime.now().withNano(0).toLocalTime(), s / 3600, o.hours, ran, s > 0 ? ran / s : 0, count("crash"), count("hang"), count("memory"),
                exceptionKinds.size(), peakMb);
        o.out.println(line);
        Files.writeString(run.resolve("progress.txt"), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (childrenLog != null) childrenLog.flush();
    }

    private long count(String kind) {
        return findings.stream().filter(f -> f.kind.equals(kind)).count();
    }

    private String summaryText(String problem) {
        double s = seconds();
        StringBuilder t = new StringBuilder();
        String nl = System.lineSeparator();
        t.append("Decoder fuzz ").append(run).append(nl);
        t.append(String.format(Locale.ROOT, "  %.2f h (%.0f s), %,d inputs, %.1f a second; seed %d; FFmpeg %s%n", s / 3600, s, inputs, s > 0 ? inputs / s : 0, o.seed, ffmpeg));
        t.append(String.format(Locale.ROOT, "  live %,d inputs (%,d pictures), second %,d (%,d), clips %,d (%,d pictures, %,d refused as no clip)%n",
                totals.getOrDefault("live", 0L), totals.getOrDefault("livePictures", 0L), totals.getOrDefault("second", 0L), totals.getOrDefault("secondPictures", 0L),
                totals.getOrDefault("clip", 0L), totals.getOrDefault("clipPictures", 0L), totals.getOrDefault("clipsRefused", 0L)));
        t.append(String.format(Locale.ROOT, "  JVM crashes and other deaths: %d, hangs: %d, over %d MB: %d; children started: %d%n",
                count("crash"), count("hang"), o.maxMemMb, count("memory"), children));
        exceptions = totals.getOrDefault("exceptions", 0L);
        t.append(String.format(Locale.ROOT, "  Java exceptions out of the decoding: %,d, of %d kinds%s%n", exceptions, exceptionKinds.size(),
                exceptionKinds.isEmpty() ? "" : " (first inputs of each in exceptions" + java.io.File.separator + ")"));
        for (Map.Entry<String, Integer> e : exceptionKinds.entrySet()) t.append("    ").append(e.getKey()).append(nl);
        t.append(String.format(Locale.ROOT, "  largest memory of the child: %d MB%n", peakMb));
        if (mutatorFaults.get() > 0) t.append("  inputs the mutator failed to make (this tool's fault, left out): ").append(mutatorFaults.get()).append(" - children.log").append(nl);
        Map<String, Integer> alike = new LinkedHashMap<>();
        for (Finding f : findings) alike.merge(f.detail.replaceAll("at input \\d+ ", ""), 1, Integer::sum);
        if (alike.size() > 0 && findings.size() > 1) {
            t.append("  the findings by what they were:").append(nl);
            for (Map.Entry<String, Integer> e : alike.entrySet()) t.append("    ").append(e.getValue()).append(" x ").append(e.getKey()).append(nl);
        }
        int listed = 0;
        for (Finding f : findings) {
            if (listed++ == 30) {
                t.append("  ... and ").append(findings.size() - 30).append(" more in findings").append(java.io.File.separator).append(nl);
                break;
            }
            t.append("  ").append(f.kind.toUpperCase(Locale.ROOT)).append(" input ").append(f.index).append(": ").append(f.detail)
                    .append(f.folder != null ? " -> " + f.folder : "").append("; again: ").append(f.again).append(nl);
        }
        if (problem != null) t.append("  STOPPED: ").append(problem).append(nl);
        boolean pass = problem == null && count("crash") == 0 && count("hang") == 0;
        t.append(pass ? "PASS: no JVM crash, no hang" : "FAIL: " + (problem != null ? problem : "a crash or hang - each in findings" + java.io.File.separator
                + " with its input; a crash means an FFmpeg update, or the decoding moved out of the app's process")).append(nl);
        return t.toString();
    }

    private synchronized void log(String line) {
        if (childrenLog == null || childrenLogBytes > CHILDREN_LOG_MAX) return;
        try {
            childrenLog.write(LocalDateTime.now().withNano(0).toLocalTime() + " " + line);
            childrenLog.newLine();
            childrenLogBytes += line.length() + 10;
            if (childrenLogBytes > CHILDREN_LOG_MAX) childrenLog.write("(children.log full: no more lines)" + System.lineSeparator());
        } catch (IOException ignored) {
            // the log is a help, not the result
        }
    }

    private synchronized void closeLog() {
        try {
            if (childrenLog != null) childrenLog.close();
        } catch (IOException ignored) {
            // nothing more to say
        }
        childrenLog = null;
    }
}
