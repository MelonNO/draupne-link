package de.droiddrone.bench.fuzz;

import de.droiddrone.bench.fuzz.LinkInput.Target;
import de.droiddrone.pc.LinkFuzzTargets;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The protocol fuzz's child JVM: runs the inputs ({@link LinkFuzzTargets}) with a capped heap, so that a reader that
 * grows without bound or hangs ends here and not in the {@link ProtocolFuzz} watching it. It speaks in lines on its
 * output, as the decoder fuzz's {@link FuzzChild}:
 * <pre>
 *   READY &lt;seeds&gt;                       inputs follow
 *   AT &lt;number&gt; &lt;crc&gt;                   input number n is run now (written before it is)
 *   BAD &lt;number&gt; &lt;rule&gt; | &lt;detail&gt;      a promise of the code broken (each rule's first three)
 *   EXC &lt;number&gt; &lt;what&gt;                 an exception out of a reader or a receive path (each kind's first three)
 *   CAUGHT &lt;number&gt; &lt;what&gt;              one a receive thread's own catch takes, losing only that packet (first three)
 *   STAT key=value ...                    counts, every few seconds
 *   MEM &lt;number&gt;                         out of heap at input n: it ends itself (exit 77)
 *   DONE &lt;last number&gt;
 * </pre>
 * Modes: {@code fuzz <seed> <first> <end ms> <last> <targets>}, {@code one <input file> <number>} (every event said
 * whole, with its stack). The app's own log (Common's Log.log, on System.out) is taken off the output: the receive
 * paths' catches are read from it ({@link LinkFuzzTargets#captureLogs}).
 */
public final class ProtocolFuzzChild {
    public static final int EXIT_MEMORY = 77;
    private static final long STAT_EVERY_MS = 5000;
    private static PrintStream out;

    private final boolean verbose;
    private final Map<String, Integer> seen = new HashMap<>();
    private final Map<String, Long> counts = new TreeMap<>();
    private final long[] perTarget = new long[Target.values().length];
    private long inputs, broken, exceptions, caught;
    private static volatile long current;

    private ProtocolFuzzChild(boolean verbose) {
        this.verbose = verbose;
    }

    public static void main(String[] args) throws Exception {
        out = System.out;
        FuzzChild.endWithParent();
        FuzzChild.polite(1);
        LinkFuzzTargets.captureLogs();
        try {
            switch (args[0]) {
                case "fuzz" -> fuzz(Long.parseLong(args[1]), Long.parseLong(args[2]), Long.parseLong(args[3]), Long.parseLong(args[4]), targets(args[5]));
                case "one" -> {
                    LinkInput in = LinkInput.read(Path.of(args[1]));
                    long n = Long.parseLong(args[2]);
                    ProtocolFuzzChild c = new ProtocolFuzzChild(true);
                    say("READY one");
                    say("AT " + n + " " + Long.toHexString(in.crc()));
                    for (String line : in.dump().split("\\R")) say("  " + line);
                    c.run(in, n);
                    c.stat();
                    say("DONE " + n);
                }
                default -> throw new IllegalArgumentException("mode " + args[0]);
            }
        } catch (OutOfMemoryError e) {
            say("MEM " + current);
            Runtime.getRuntime().halt(EXIT_MEMORY);
        }
        out.flush();
        System.exit(0);
    }

    static EnumSet<Target> targets(String list) {
        EnumSet<Target> t = EnumSet.noneOf(Target.class);
        for (String s : list.split(",")) if (!s.isBlank()) t.add(Target.valueOf(s.trim().toUpperCase(Locale.ROOT)));
        return t.isEmpty() ? EnumSet.allOf(Target.class) : t;
    }

    private static void fuzz(long seed, long first, long endMs, long last, EnumSet<Target> targets) {
        LinkMutator mutator = new LinkMutator(LinkFuzzTargets.seeds(seed), targets);
        ProtocolFuzzChild c = new ProtocolFuzzChild(false);
        say("READY " + mutator.targets());
        long statAt = System.currentTimeMillis();
        for (long i = first; ; i++) {
            long now = System.currentTimeMillis();
            if (now >= endMs || (last >= 0 && i > last)) {
                c.stat();
                say("DONE " + (i - 1));
                return;
            }
            LinkInput in;
            try {
                in = mutator.make(seed, i);
            } catch (RuntimeException e) {
                say("MUTATOR " + i + " " + e);
                continue;
            }
            say("AT " + i + " " + Long.toHexString(in.crc()));
            c.run(in, i);
            if (now - statAt >= STAT_EVERY_MS) {
                statAt = now;
                c.stat();
            }
        }
    }

    void run(LinkInput in, long n) {
        current = n;
        inputs++;
        perTarget[in.target().ordinal()]++;
        try {
            LinkFuzzTargets.run(in, new LinkFuzzTargets.Report() {
                @Override
                public void exception(String where, Throwable t) {
                    exceptions++;
                    event("EXC", n, where + ": " + kind(t), t);
                }

                @Override
                public void caught(String where, String what) {
                    caught++;
                    event("CAUGHT", n, where + ": " + what, null);
                }

                @Override
                public void broken(String rule, String detail) {
                    broken++;
                    event("BAD", n, rule + " | " + detail, null);
                }

                @Override
                public void count(String what) {
                    counts.merge(what, 1L, Long::sum);
                }
            });
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) { // the harness itself, or a target's path outside the checks
            exceptions++;
            event("EXC", n, "the run: " + kind(t), t);
        }
    }

    /** Each kind's first three said (all of them, whole, in a replay); the kind is the text without its numbers. */
    private void event(String tag, long n, String what, Throwable t) {
        String kind = tag.equals("BAD") ? what.substring(0, Math.max(0, what.indexOf(" | "))) : what.replaceAll("\\d+", "#");
        int k = seen.merge(tag + " " + kind, 1, Integer::sum);
        if (!verbose && k > 3) return;
        say(tag + " " + n + " " + (tag.equals("BAD") ? what : kind).replaceAll("\\R", " "));
        if (verbose && t != null) {
            for (StackTraceElement e : t.getStackTrace()) say("    at " + e);
        }
    }

    /** An exception's kind: its class, its message without numbers, where in the app's code it came from. */
    static String kind(Throwable t) {
        String message = String.valueOf(t.getMessage()).replaceAll("\\s+", " ");
        if (message.length() > 140) message = message.substring(0, 140);
        String at = "";
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().startsWith("de.droiddrone") && !e.getClassName().startsWith("de.droiddrone.bench")
                    && !e.getClassName().endsWith("LinkFuzzTargets") && !e.getClassName().contains("LinkFuzzTargets$")) {
                at = " at " + e.getClassName().replace("de.droiddrone.", "") + "." + e.getMethodName() + ":" + e.getLineNumber();
                break;
            }
        }
        String name = t instanceof LinkFuzzTargets.Logged ? "logged" : t.getClass().getName();
        return name + ": " + message + at;
    }

    private void stat() {
        Runtime rt = Runtime.getRuntime();
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "STAT inputs=%d heapMb=%d broken=%d exceptions=%d caught=%d", inputs,
                (rt.totalMemory() - rt.freeMemory()) >> 20, broken, exceptions, caught));
        for (Target t : Target.values()) s.append(' ').append(t.name().toLowerCase(Locale.ROOT)).append('=').append(perTarget[t.ordinal()]);
        for (Map.Entry<String, Long> e : counts.entrySet()) s.append(" c.").append(e.getKey().replace(' ', '_')).append('=').append(e.getValue());
        say(s.toString());
    }

    static void say(String line) {
        out.println(line);
    }
}
