package de.droiddrone.bench.fuzz;

import de.droiddrone.bench.fuzz.LinkInput.Target;
import de.droiddrone.pc.LinkFuzzTargets;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The link's packet reading fuzzed: valid streams of every kind, both directions ({@link LinkFuzzTargets#seeds}),
 * damaged and reordered ({@link LinkMutator}) and fed for hours to the receive paths ({@link LinkFuzzTargets#run}) in a
 * child JVM with a capped heap ({@link ProtocolFuzzChild}) - the decoder fuzz's way ({@link DecoderFuzz}): a child
 * that dies, hangs (an input taking more than timeoutMs of CPU, or a dozen times that on the clock) or runs out of
 * heap leaves its input in findings\ and a new child goes on with the next. A promise of the code broken (the child's
 * BAD lines) is a finding too, each rule's first inputs kept and run once more alone; an exception out of a reader or
 * a receive path goes to exceptions\ with its input. What a receive thread's own catch takes by design (a packet cut
 * short, read past its end in a buffer thread: only that packet lost) is listed, not failed.
 * <p>
 * {@code gradlew protocolFuzz -Phours=4 -Pseed=1} (docs/TESTING.md). Results under
 * %LOCALAPPDATA%\DraupneBench\fuzz\&lt;time&gt;-protocol-seed&lt;seed&gt;: summary.txt, progress.txt, findings\, exceptions\,
 * seeds\, children.log. {@code -Preplay=<input.pfz>} runs one input again, every event said whole.
 */
public final class ProtocolFuzz {
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int RING = 200, SAMPLES_PER_KIND = 3, MAX_SAMPLES = 300;
    private static final long CHILDREN_LOG_MAX = 50L << 20, PROGRESS_EVERY_MS = 60_000;

    public static final class Options {
        public double hours = 1;
        public long seed = System.currentTimeMillis() % 1_000_000;
        /** The CPU time one input may take; wall time: wallFactor times that. */
        public long timeoutMs = 3000;
        public long wallFactor = 12;
        public Path root = DecoderFuzz.Options.defaultRoot();
        /** The child's heap. */
        public int maxMemMb = 768;
        public EnumSet<Target> targets = EnumSet.allOf(Target.class);
        public long firstIndex;
        public long lastIndex = -1;
        /** How many findings' inputs are run once more alone, in a fresh child. */
        public int replays = 30;
        public boolean polite = true;
        public PrintStream out = System.out;
    }

    /** Something found at one input: kind broken, exception, crash, hang, memory. */
    public record Finding(String kind, long index, Path folder, String detail, String again) {
    }

    public record Summary(Path run, long inputs, double seconds, List<Finding> findings, Map<String, Integer> rules, Map<String, Integer> exceptionKinds,
                          Map<String, Integer> caughtKinds, Map<String, Long> counts, int children, String problem, String text) {
        /** Nothing broken, no exception, no hang, death or heap run out. */
        public boolean passed() {
            return problem == null && findings.isEmpty() && exceptionKinds.isEmpty();
        }
    }

    private final Options o;
    private final Path run, findingsDir, exceptionsDir, work;
    private BufferedWriter childrenLog;
    private long childrenLogBytes;
    private LinkMutator mutator;
    private final List<Finding> findings = new ArrayList<>();
    private final Map<String, Integer> rules = new LinkedHashMap<>(), exceptionKinds = new LinkedHashMap<>(), caughtKinds = new LinkedHashMap<>();
    private final Map<String, Long> totals = new TreeMap<>();
    private int samples, replaysLeft, children, findingNo;
    private long inputs, fuzzStart, fuzzEnd, nextProgress;
    private volatile Process currentChild;

    private ProtocolFuzz(Options o, Path run) {
        this.o = o;
        this.run = run;
        findingsDir = run.resolve("findings");
        exceptionsDir = run.resolve("exceptions");
        work = run.resolve("work");
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
        if (a.containsKey("targets")) o.targets = ProtocolFuzzChild.targets(a.get("targets"));
        if (a.containsKey("first")) o.firstIndex = Long.parseLong(a.get("first"));
        if (a.containsKey("last")) o.lastIndex = Long.parseLong(a.get("last"));
        if (a.containsKey("polite")) o.polite = Boolean.parseBoolean(a.get("polite"));
        if (a.containsKey("replay")) System.exit(replayOne(o, Path.of(a.get("replay"))) ? 0 : 1);
        System.exit(run(o).passed() ? 0 : 1);
    }

    public static Summary run(Options o) throws IOException, InterruptedException {
        ProtocolFuzz f = new ProtocolFuzz(o, newRunFolder(o.root, "protocol-seed" + o.seed));
        Thread hook = new Thread(f::stopChild, "protocol-fuzz-stop");
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

    /** One input run again in a child, everything it found said whole. @return whether it ran through with nothing found */
    static boolean replayOne(Options o, Path input) throws IOException, InterruptedException {
        LinkInput in = LinkInput.read(input);
        ProtocolFuzz f = new ProtocolFuzz(o, newRunFolder(o.root, "protocol-replay"));
        f.prepare();
        o.out.println("Running " + input + " (" + in.describe() + ") in a child, " + f.run);
        Outcome r = f.watch(f.start(f.childCommand("one", input.toString(), "0")), 60_000, false);
        o.out.println(r.what());
        boolean clean = r.kind.equals("done");
        for (String line : r.lastLines) {
            if (line.startsWith("BAD ") || line.startsWith("EXC ")) clean = false;
            if (!line.startsWith("AT ")) o.out.println("  | " + line);
        }
        f.closeLog();
        return clean;
    }

    private static Path newRunFolder(Path root, String tag) throws IOException {
        String name = LocalDateTime.now().format(FOLDER) + "-" + tag;
        Path p = root.resolve(name);
        for (int i = 2; Files.exists(p); i++) p = root.resolve(name + "-" + i);
        return Files.createDirectories(p);
    }

    private void prepare() throws IOException {
        Files.createDirectories(work);
        childrenLog = Files.newBufferedWriter(run.resolve("children.log"), StandardCharsets.UTF_8);
    }

    private Summary go() throws IOException, InterruptedException {
        prepare();
        Files.writeString(run.resolve("run.txt"), String.join(System.lineSeparator(),
                "Protocol fuzz, started " + LocalDateTime.now().withNano(0),
                "seed " + o.seed + ", " + o.hours + " h, targets " + o.targets + ", an input may take " + o.timeoutMs + " ms of CPU ("
                        + o.timeoutMs * o.wallFactor / 1000 + " s on the clock), the child's heap " + o.maxMemMb + " MB, "
                        + (o.polite ? "lowest priority on one core" : "normal priority"),
                "java " + System.getProperty("java.version")) + System.lineSeparator(), StandardCharsets.UTF_8);
        o.out.println("Protocol fuzz: " + run);
        String problem = seeds();
        if (problem == null) problem = fuzz();
        String text = summaryText(problem);
        Files.writeString(run.resolve("summary.txt"), text, StandardCharsets.UTF_8);
        o.out.print(text);
        closeLog();
        return new Summary(run, inputs, seconds(), List.copyOf(findings), Map.copyOf(rules), Map.copyOf(exceptionKinds), Map.copyOf(caughtKinds),
                Map.copyOf(totals), children, problem, text);
    }

    /** The seeds, made here as in the child (pure Java, the same for the same seed), and written down to be looked at. */
    private String seeds() throws IOException {
        Path dir = Files.createDirectories(run.resolve("seeds"));
        List<LinkInput> seeds;
        java.io.PrintStream was = LinkFuzzTargets.captureLogs();
        try {
            seeds = LinkFuzzTargets.seeds(o.seed);
        } finally {
            LinkFuzzTargets.restoreLogs(was);
        }
        for (int i = 0; i < seeds.size(); i++) {
            LinkInput s = seeds.get(i);
            s.write(dir.resolve(String.format(Locale.ROOT, "%02d-%s.pfz", i, s.target().name().toLowerCase(Locale.ROOT))));
            Files.writeString(dir.resolve(String.format(Locale.ROOT, "%02d-%s.txt", i, s.target().name().toLowerCase(Locale.ROOT))), s.dump());
        }
        try {
            mutator = new LinkMutator(seeds, o.targets);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        o.out.println(seeds.size() + " seeds in " + dir + "; targets " + mutator.targets());
        return null;
    }

    private String fuzz() throws IOException, InterruptedException {
        fuzzStart = System.currentTimeMillis();
        fuzzEnd = fuzzStart + Math.round(o.hours * 3_600_000);
        nextProgress = fuzzStart + PROGRESS_EVERY_MS;
        long next = o.firstIndex;
        int startFailures = 0;
        while (true) {
            if (System.currentTimeMillis() >= fuzzEnd || (o.lastIndex >= 0 && next > o.lastIndex)) return null;
            Outcome r = watch(start(childCommand("fuzz", String.valueOf(o.seed), String.valueOf(next), String.valueOf(fuzzEnd),
                    String.valueOf(o.lastIndex), targetList())), 120_000, true);
            inputs += r.ats;
            if (r.kind.equals("done")) return null;
            if (r.atIndex < 0) {
                if (++startFailures >= 3) return "the child ended before its first input three times running: " + r.what() + tail(r);
                continue;
            }
            startFailures = 0;
            finding(r.kind, r.atIndex, r.what(), r.lastLines);
            next = r.atIndex + 1;
        }
    }

    private String targetList() {
        StringBuilder s = new StringBuilder();
        for (Target t : o.targets) s.append(s.isEmpty() ? "" : ",").append(t.name().toLowerCase(Locale.ROOT));
        return s.toString();
    }

    private double seconds() {
        return fuzzStart == 0 ? 0 : (System.currentTimeMillis() - fuzzStart) / 1000.0;
    }

    private static String tail(Outcome r) {
        List<String> lines = r.lastLines.stream().filter(l -> !l.startsWith("AT ")).toList();
        lines = lines.subList(Math.max(0, lines.size() - 12), lines.size());
        return lines.isEmpty() ? " (children.log)" : " - its last lines:" + System.lineSeparator() + "    | " + String.join(System.lineSeparator() + "    | ", lines);
    }

    /** What one child came to: done, crash, hang, memory, startup. */
    private record Outcome(String kind, int exit, long atIndex, long ats, List<String> lastLines, long cpuMs, long wallMs) {
        String what() {
            return switch (kind) {
                case "done" -> "ran through";
                case "hang" -> "hung at input " + atIndex + " (" + cpuMs + " ms of CPU, " + wallMs + " ms on the clock)";
                case "memory" -> "out of its " + "heap at input " + atIndex;
                case "startup" -> "ended before its first input (exit " + exit + ")";
                default -> "died at input " + atIndex + " (" + DecoderFuzz.exitMeaning(exit) + ")";
            };
        }
    }

    private final class Child {
        final Process process;
        final ArrayDeque<String> ring = new ArrayDeque<>();
        final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        final AtomicLong ats = new AtomicLong();
        volatile long atIndex = -1;
        volatile boolean ready, done, memory;
        volatile Map<String, Long> lastStat = Map.of();
        final Thread reader, errors;

        Child(Process p) {
            process = p;
            reader = new Thread(() -> read(p.getInputStream(), true), "protocol-fuzz-out-" + p.pid());
            errors = new Thread(() -> read(p.getErrorStream(), false), "protocol-fuzz-err-" + p.pid());
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
                atIndex = Long.parseLong(line.split(" ")[1]);
                ats.incrementAndGet();
                return;
            }
            log(process.pid() + " " + line);
            if (line.startsWith("READY")) ready = true;
            else if (line.startsWith("DONE")) done = true;
            else if (line.startsWith("MEM ")) memory = true;
            else if (line.startsWith("BAD ") || line.startsWith("EXC ") || line.startsWith("CAUGHT ")) events.add(line);
            else if (line.startsWith("STAT ")) lastStat = DecoderFuzz.parseStat(line);
        }

        List<String> lastLines() {
            synchronized (ring) {
                return List.copyOf(ring);
            }
        }
    }

    private synchronized void addStats(Map<String, Long> m) {
        for (Map.Entry<String, Long> e : m.entrySet()) if (!e.getKey().endsWith("Mb")) totals.merge(e.getKey(), e.getValue(), Long::sum);
    }

    private List<String> childCommand(String... args) {
        List<String> cmd = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        cmd.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
        cmd.addAll(List.of("-Xmx" + o.maxMemMb + "m", "-XX:+UseSerialGC", "-XX:-CreateCoredumpOnCrash", "-XX:-UsePerfData", "-XX:+ExitOnOutOfMemoryError", "-XX:-OmitStackTraceInFastThrow",
                "-Djava.awt.headless=true", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Duser.home=" + work, "-Djava.io.tmpdir=" + work, "-D" + FuzzChild.POLITE + "=" + o.polite));
        // ExitOnOutOfMemoryError: a heap run out where the child can't say so itself still ends it (exit 3), never a hang
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(ProtocolFuzzChild.class.getName());
        cmd.addAll(List.of(args));
        return cmd;
    }

    private Child start(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile());
        Map<String, String> env = pb.environment();
        env.put("APPDATA", work.toString());
        env.put("LOCALAPPDATA", work.toString());
        env.put("TEMP", work.toString());
        env.put("TMP", work.toString());
        Process p = pb.start();
        currentChild = p;
        children++;
        return new Child(p);
    }

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
                events(c);
                progress(now, c);
            }
            if (index < 0 && !c.done && !c.ready && now - started > startupMs) hangKind = "startup";
            else if (index >= 0 && !c.done && (cpu - indexCpu > o.timeoutMs || now - indexWall > o.timeoutMs * o.wallFactor)) hangKind = "hang";
            if (hangKind != null) {
                c.process.descendants().forEach(ProcessHandle::destroyForcibly);
                c.process.destroyForcibly();
                c.process.waitFor(10, TimeUnit.SECONDS);
                break;
            }
            c.process.waitFor(200, TimeUnit.MILLISECONDS);
        }
        if (hangKind == null) now = System.currentTimeMillis();
        c.process.waitFor(10, TimeUnit.SECONDS);
        c.reader.join(10_000);
        c.errors.join(2_000);
        if (fuzzing) {
            events(c);
            addStats(c.lastStat);
        }
        int exit = c.process.isAlive() ? -1 : c.process.exitValue();
        if (currentChild == c.process) currentChild = null;
        String kind;
        if (hangKind != null) kind = c.atIndex < 0 ? "startup" : hangKind;
        else if (c.memory || exit == ProtocolFuzzChild.EXIT_MEMORY || exit == 3) kind = "memory";
        else if (c.done && exit == 0) kind = "done";
        else kind = c.atIndex < 0 ? "startup" : "crash";
        return new Outcome(kind, exit, c.atIndex, c.ats.get(), c.lastLines(), Math.max(0, cpu - indexCpu), now - indexWall);
    }

    private static long cpuMs(Process p) {
        try {
            return p.toHandle().info().totalCpuDuration().map(Duration::toMillis).orElse(0L);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void stopChild() {
        Process p = currentChild;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------------------------------------- findings

    /** The child's BAD, EXC and CAUGHT lines: counted by kind; the first inputs of each kept. */
    private void events(Child c) throws IOException, InterruptedException {
        String line;
        while ((line = c.events.poll()) != null) {
            String[] p = line.split(" ", 3);
            if (p.length < 3) continue;
            long index = Long.parseLong(p[1]);
            switch (p[0]) {
                case "BAD" -> {
                    String rule = p[2].contains(" | ") ? p[2].substring(0, p[2].indexOf(" | ")) : p[2];
                    int seen = rules.merge(rule, 1, Integer::sum);
                    if (seen <= SAMPLES_PER_KIND) finding("broken", index, p[2], c.lastLines());
                }
                case "EXC" -> {
                    int seen = exceptionKinds.merge(p[2], 1, Integer::sum);
                    if (seen <= SAMPLES_PER_KIND) sample(index, p[2], false);
                }
                default -> {
                    int seen = caughtKinds.merge(p[2], 1, Integer::sum);
                    if (seen <= 1) sample(index, p[2], true);
                }
            }
        }
    }

    private void sample(long index, String kind, boolean caught) throws IOException {
        if (samples >= MAX_SAMPLES) return;
        samples++;
        Files.createDirectories(exceptionsDir);
        LinkInput in = mutator.make(o.seed, index);
        in.write(exceptionsDir.resolve("input" + index + ".pfz"));
        Files.writeString(exceptionsDir.resolve("input" + index + ".txt"), in.dump(), StandardCharsets.UTF_8);
        Files.writeString(exceptionsDir.resolve("list.txt"), "input" + index + ".pfz  " + (caught ? "(caught by its thread, by design) " : "") + kind
                + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void finding(String kind, long index, String detail, List<String> lastLines) throws IOException, InterruptedException {
        int n = ++findingNo;
        if (n > 200) {
            findings.add(new Finding(kind, index, null, detail, "not kept: more than 200 findings"));
            return;
        }
        Path dir = Files.createDirectories(findingsDir.resolve(String.format(Locale.ROOT, "%03d-%s-input%d", n, kind, index)));
        LinkInput in = mutator.make(o.seed, index);
        Path input = dir.resolve("input.pfz");
        in.write(input);
        Files.writeString(dir.resolve("input.txt"), in.dump(), StandardCharsets.UTF_8);
        Files.write(dir.resolve("log.txt"), lastLines, StandardCharsets.UTF_8);
        String again = "not run again (more than " + o.replays + " findings)";
        if (replaysLeft-- > 0) {
            Outcome a = watch(start(childCommand("one", input.toString(), String.valueOf(index))), 60_000, false);
            Files.write(dir.resolve("again.txt"), a.lastLines, StandardCharsets.UTF_8);
            boolean same = kind.equals("broken") ? a.lastLines.stream().anyMatch(l -> l.startsWith("BAD ") && detail.startsWith(l.split(" ", 3)[2].split(" \\| ")[0]))
                    : a.kind.equals(kind);
            again = same ? "yes, the same alone in a fresh child (again.txt has every event, whole)" : "not alone, in a fresh child: " + a.what() + " (again.txt)";
        }
        Files.write(dir.resolve("about.txt"), List.of(
                kind.toUpperCase(Locale.ROOT) + " at input " + index + " of the run with seed " + o.seed,
                detail,
                "the input: " + in.describe(),
                "again: " + again,
                "",
                "To run it again: gradlew protocolFuzz -Preplay=" + input,
                "(input.pfz: the input; input.txt: its datagrams in hex; log.txt: the child's last lines)"), StandardCharsets.UTF_8);
        findings.add(new Finding(kind, index, dir, detail, again));
        o.out.println("FOUND " + kind + ": " + detail + " -> " + dir);
        log("FOUND " + kind + " " + detail + " -> " + dir + "; again: " + again);
    }

    private void progress(long now, Child c) throws IOException {
        if (now < nextProgress) return;
        nextProgress = now + PROGRESS_EVERY_MS;
        long ran = inputs + c.ats.get();
        double s = (now - fuzzStart) / 1000.0;
        String line = String.format(Locale.ROOT, "%s  %.2f h of %.2f h: %,d inputs (%.0f a second), findings %d (rules broken %d), exceptions of %d kinds, caught of %d kinds",
                LocalDateTime.now().withNano(0).toLocalTime(), s / 3600, o.hours, ran, s > 0 ? ran / s : 0, findings.size(), rules.size(),
                exceptionKinds.size(), caughtKinds.size());
        o.out.println(line);
        Files.writeString(run.resolve("progress.txt"), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (childrenLog != null) childrenLog.flush();
    }

    private String summaryText(String problem) {
        double s = seconds();
        StringBuilder t = new StringBuilder();
        String nl = System.lineSeparator();
        t.append("Protocol fuzz ").append(run).append(nl);
        t.append(String.format(Locale.ROOT, "  %.2f h (%.0f s), %,d inputs, %.0f a second; seed %d; children started: %d%n", s / 3600, s, inputs, s > 0 ? inputs / s : 0, o.seed, children));
        t.append("  inputs by target:");
        for (Target x : Target.values()) t.append(' ').append(x.name().toLowerCase(Locale.ROOT)).append(' ').append(String.format(Locale.ROOT, "%,d", totals.getOrDefault(x.name().toLowerCase(Locale.ROOT), 0L)));
        t.append(nl).append("  what the valid paths did:").append(nl);
        for (Map.Entry<String, Long> e : totals.entrySet()) {
            if (e.getKey().startsWith("c.")) t.append(String.format(Locale.ROOT, "    %-40s %,d%n", e.getKey().substring(2).replace('_', ' '), e.getValue()));
        }
        t.append(String.format(Locale.ROOT, "  promises broken: %d rules, %,d times%n", rules.size(), totals.getOrDefault("broken", 0L)));
        for (Map.Entry<String, Integer> e : rules.entrySet()) t.append("    ").append(e.getValue()).append(" x ").append(e.getKey()).append(nl);
        t.append(String.format(Locale.ROOT, "  exceptions out of a reader or receive path: %,d, of %d kinds%n", totals.getOrDefault("exceptions", 0L), exceptionKinds.size()));
        for (Map.Entry<String, Integer> e : exceptionKinds.entrySet()) t.append("    ").append(e.getValue()).append(" x ").append(e.getKey()).append(nl);
        t.append(String.format(Locale.ROOT, "  caught by their thread by design (a packet cut short, only it lost): %,d, of %d kinds%n", totals.getOrDefault("caught", 0L), caughtKinds.size()));
        for (Map.Entry<String, Integer> e : caughtKinds.entrySet()) t.append("    ").append(e.getKey()).append(nl);
        long deaths = findings.stream().filter(f -> !f.kind.equals("broken")).count();
        t.append("  children that died, hung or ran out of heap: ").append(deaths).append(nl);
        int listed = 0;
        for (Finding f : findings) {
            if (listed++ == 40) {
                t.append("  ... and ").append(findings.size() - 40).append(" more in findings").append(java.io.File.separator).append(nl);
                break;
            }
            t.append("  ").append(f.kind.toUpperCase(Locale.ROOT)).append(" input ").append(f.index).append(": ").append(f.detail)
                    .append(f.folder != null ? " -> " + f.folder : "").append("; again: ").append(f.again).append(nl);
        }
        if (problem != null) t.append("  STOPPED: ").append(problem).append(nl);
        boolean pass = problem == null && findings.isEmpty() && exceptionKinds.isEmpty();
        t.append(pass ? "PASS: nothing broken, no exception, no hang" : "FAIL: " + (problem != null ? problem : "see above - each with its input in findings"
                + java.io.File.separator + " or exceptions" + java.io.File.separator)).append(nl);
        return t.toString();
    }

    private synchronized void log(String line) {
        if (childrenLog == null || childrenLogBytes > CHILDREN_LOG_MAX) return;
        try {
            childrenLog.write(LocalDateTime.now().withNano(0).toLocalTime() + " " + line);
            childrenLog.newLine();
            childrenLogBytes += line.length() + 10;
        } catch (IOException ignored) {
            // the log is a help, not the result
        }
    }

    private synchronized void closeLog() {
        try {
            if (childrenLog != null) childrenLog.close();
        } catch (IOException ignored) {
        }
        childrenLog = null;
    }
}
