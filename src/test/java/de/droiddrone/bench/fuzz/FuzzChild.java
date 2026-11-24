package de.droiddrone.bench.fuzz;

import de.droiddrone.bench.fuzz.FuzzInput.Target;
import de.droiddrone.bench.fuzz.FuzzInput.Unit;
import de.droiddrone.pc.overwatch.ClipPlayer;
import de.droiddrone.pc.video.VideoDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The decoder fuzz's child JVM: the one process that runs FFmpeg on made-up input, so that a native crash ends it and
 * not the {@link DecoderFuzz} watching it. It speaks in lines on its output:
 * <pre>
 *   READY &lt;FFmpeg version&gt;           the decoding loaded, inputs follow
 *   AT &lt;number&gt; &lt;crc&gt;                input number n is handed to the decoding now (written before it is)
 *   EXC &lt;number&gt; &lt;what&gt;              a Java exception out of the decoding (each kind's first three)
 *   STAT key=value ...                  counts, every few seconds
 *   MEM &lt;bytes&gt; &lt;number&gt;              over the memory cap: it ends itself (exit 77)
 *   DONE &lt;last number&gt;                 the time is up
 * </pre>
 * Modes: {@code seeds <run> <more seeds or ""> <pc|same> <cores>}, {@code fuzz <run> <seed> <first> <end ms> <last>
 * <max MB> <cores> <targets>}, {@code one <input> <number> <work> <max MB> <cores>}. It runs at the lowest priority on a few cores of
 * its own (fuzz.polite), and ends when its parent does (its input closes).
 * <p>
 * Each target is handed its input the way the app hands it on: see {@link #live}, {@link #second}, {@link #clip}.
 */
public final class FuzzChild {
    public static final int EXIT_MISBEHAVED = 134, EXIT_MEMORY = 77;
    /** For the self test: "exit@3,hang@6,crash@9,mem@12,after@14" - at those input numbers the child does that instead. */
    static final String MISBEHAVE = "fuzz.misbehave";
    /** For the self test: inputs are made and named but not decoded (no FFmpeg). */
    static final String DRY = "fuzz.dry";
    static final String POLITE = "fuzz.polite";

    private static final int CLIP_W = 320, CLIP_H = 180, CLIP_PICTURES = 200;
    private static final long STAT_EVERY_MS = 5000;

    private static volatile long current = -1;
    private static volatile long maxBytes = Long.MAX_VALUE;
    private static volatile long peakBytes;
    private static boolean dry;

    private final Path work;
    private final Map<String, Integer> excKinds = new HashMap<>();
    private long inputs, exceptions, clipsRefused, decodersRefused;
    private final long[] runs = new long[Target.values().length], pictures = new long[Target.values().length];

    private FuzzChild(Path work) {
        this.work = work;
    }

    public static void main(String[] args) throws Exception {
        dry = Boolean.getBoolean(DRY);
        endWithParent();
        switch (args[0]) {
            case "seeds" -> {
                polite(Integer.parseInt(args[4]));
                Path extra = args[2].isEmpty() ? null : Path.of(args[2]);
                for (String line : SeedMaker.make(Path.of(args[1]).resolve("seeds"), extra, args[3].equals("pc"))) say(line);
                say("DONE seeds");
            }
            case "fuzz" -> fuzz(Path.of(args[1]), Long.parseLong(args[2]), Long.parseLong(args[3]), Long.parseLong(args[4]), Long.parseLong(args[5]),
                    Integer.parseInt(args[6]), Integer.parseInt(args[7]), targets(args[8]));
            case "one" -> {
                polite(Integer.parseInt(args[5]));
                watchMemory(Integer.parseInt(args[4]));
                FuzzInput in = FuzzInput.read(Path.of(args[1]));
                long n = Long.parseLong(args[2]);
                FuzzChild child = new FuzzChild(Path.of(args[3]));
                child.ready();
                current = n;
                say("AT " + n + " " + Long.toHexString(in.crc()));
                misbehave(n, 0);
                if (!dry) child.run(in, n);
                child.inputs++;
                child.stat();
                say("DONE " + n);
            }
            default -> throw new IllegalArgumentException("mode " + args[0]);
        }
        System.out.flush();
        System.exit(0);
    }

    static EnumSet<Target> targets(String list) {
        EnumSet<Target> out = EnumSet.noneOf(Target.class);
        for (String t : list.split(",")) if (!t.isBlank()) out.add(Target.valueOf(t.trim().toUpperCase(Locale.ROOT)));
        return out.isEmpty() ? EnumSet.allOf(Target.class) : out;
    }

    private static void fuzz(Path run, long seed, long first, long endMs, long last, int maxMb, int cores, EnumSet<Target> targets) throws Exception {
        polite(cores);
        watchMemory(maxMb);
        Mutator mutator = new Mutator(SeedMaker.load(run.resolve("seeds")), targets);
        FuzzChild child = new FuzzChild(Files.createDirectories(run.resolve("work")));
        child.ready();
        long statAt = System.currentTimeMillis();
        for (long i = first; ; i++) {
            long now = System.currentTimeMillis();
            if (now >= endMs || (last >= 0 && i > last)) {
                child.stat();
                say("DONE " + (i - 1));
                return;
            }
            FuzzInput in;
            try {
                in = mutator.make(seed, i);
            } catch (RuntimeException e) { // the mutator's own fault, not the decoding's: said, and the input left out
                say("MUTATOR " + i + " " + e);
                continue;
            }
            current = i;
            say("AT " + i + " " + Long.toHexString(in.crc()));
            misbehave(i, child.inputs);
            if (!dry) child.run(in, i);
            child.inputs++;
            if (now - statAt >= STAT_EVERY_MS) {
                statAt = now;
                child.stat();
            }
        }
    }

    /** FFmpeg loaded and a decoder opened once, so the first input's time is its own. */
    private void ready() {
        if (dry) {
            say("READY dry (nothing decoded)");
            return;
        }
        try (VideoDecoder d = new VideoDecoder(false, new byte[0])) {
            d.flush();
        }
        say("READY FFmpeg " + org.bytedeco.ffmpeg.global.avutil.av_version_info().getString());
    }

    void run(FuzzInput in, long n) {
        runs[in.target().ordinal()]++;
        switch (in.target()) {
            case LIVE -> live(in, n);
            case SECOND -> second(in, n);
            case CLIP -> clip(in, n);
        }
    }

    // ------------------------------------------------------------------------------------------------- the targets

    /**
     * The live video (VideoPipeline's decode thread): a config closes the decoder and opens a new one (FFmpeg's own
     * threads, software); frames before a key frame are skipped; a picture is scaled to the screen (flags: 640x360,
     * 1280x720, the picture's own size up to 1920x1080, 320x180) and, with flag 4, kept for a still and converted
     * as the still saver does; an exception closes the decoder and opens a new one, from the next key frame.
     */
    private void live(FuzzInput in, long n) {
        VideoDecoder d = null;
        byte[] config = null;
        boolean hevc = false, waitingForKey = true, stillTaken = false;
        try {
            for (Unit u : in.units()) {
                if (u.isConfig()) {
                    close(d);
                    config = u.data();
                    hevc = u.kind() == FuzzInput.CONFIG_HEVC;
                    d = open(hevc, config, false, n, in.target());
                    waitingForKey = true;
                } else if (u.kind() == FuzzInput.FLUSH) {
                    if (d != null) d.flush();
                    waitingForKey = true;
                } else if (u.isFrame()) {
                    boolean key = u.kind() == FuzzInput.KEY_FRAME;
                    if (d == null && key && config != null) d = open(hevc, config, false, n, in.target());
                    if (d == null || (waitingForKey && !key)) continue;
                    waitingForKey = false;
                    try {
                        if (!d.decode(u.data(), u.data().length)) continue;
                        pictures[Target.LIVE.ordinal()]++;
                        int w, h;
                        switch (in.flags() & 3) {
                            case 0 -> { w = 640; h = 360; }
                            case 1 -> { w = 1280; h = 720; }
                            case 2 -> { w = Math.max(1, Math.min(1920, d.width())); h = Math.max(1, Math.min(1080, d.height())); }
                            default -> { w = 320; h = 180; }
                        }
                        d.convert(new int[w * h], w, h);
                        if ((in.flags() & 4) != 0 && !stillTaken) {
                            stillTaken = true;
                            try (VideoDecoder.Kept k = d.keep()) {
                                if (k != null && (long) k.width() * k.height() <= 4096L * 4096) k.rgb(new int[k.width() * k.height()]);
                            }
                        }
                    } catch (Throwable e) {
                        exception(n, in.target(), e);
                        close(d);
                        d = config == null ? null : open(hevc, config, false, n, in.target());
                        waitingForKey = true;
                    }
                }
            }
        } catch (Throwable e) {
            exception(n, in.target(), e);
        } finally {
            close(d);
        }
    }

    /**
     * The second decoders, in software on one thread: the overwatch preview (flag 1: always the H.264 decoder,
     * whatever the config says; made at the first key frame; its picture at most 640 wide, the height in proportion),
     * else the OSD recording (its canvas) and, with flag 2, STANAG's copy of the live video (1280x720), each in the
     * config's own codec.
     */
    private void second(FuzzInput in, long n) {
        boolean overwatch = (in.flags() & 1) != 0;
        VideoDecoder d = null;
        byte[] config = null;
        boolean hevc = false;
        try {
            for (Unit u : in.units()) {
                if (u.isConfig()) {
                    if (config == null || !java.util.Arrays.equals(config, u.data())) {
                        close(d);
                        d = null;
                    }
                    config = u.data();
                    hevc = !overwatch && u.kind() == FuzzInput.CONFIG_HEVC;
                } else if (u.isFrame()) {
                    if (config == null) continue;
                    if (d == null) {
                        if (u.kind() != FuzzInput.KEY_FRAME) continue;
                        d = open(hevc, config, true, n, in.target());
                        if (d == null) continue;
                    }
                    try {
                        boolean picture = d.decode(u.data(), u.data().length);
                        int w = d.width(), h = d.height();
                        if (!picture || w <= 0 || h <= 0) continue;
                        pictures[Target.SECOND.ordinal()]++;
                        int[] size = overwatch ? previewSize(w, h) : canvasFit(w, h, (in.flags() & 2) != 0);
                        if (size == null) continue;
                        d.convert(new int[size[0] * size[1]], size[0], size[1]);
                    } catch (Throwable e) {
                        exception(n, in.target(), e);
                    }
                }
            }
        } catch (Throwable e) {
            exception(n, in.target(), e);
        } finally {
            close(d);
        }
    }

    /** OverwatchHub's card picture: at most 640 wide, the height in proportion - a picture up to 640 wide at its own size. */
    static int[] previewSize(int w, int h) {
        int pw = Math.min(640, w);
        return new int[]{pw, Math.max(1, Math.round(h * (float) pw / w))};
    }

    /**
     * OsdFrame.fit on the OSD recording's canvas (OsdRecorder.outputSize: at least 720 high, as wide as the picture's
     * shape, both even) or STANAG's 1280x720: the size the picture is converted to. A canvas of more than 16 million
     * pixels (a picture of an absurd shape) is left out here: the app would fail at the canvas, before any decoding.
     */
    static int[] canvasFit(int w, int h, boolean stanag) {
        int cw = 1280, ch = 720;
        if (!stanag) {
            int oh = Math.max(h, 720);
            cw = (int) Math.round(w * (double) oh / h) & ~1;
            ch = oh & ~1;
        }
        if (cw <= 0 || ch <= 0 || (long) cw * ch > 16_000_000L) return null;
        double s = Math.min(cw / (double) w, ch / (double) h);
        int fw = Math.min(cw, (int) Math.round(w * s) & ~1), fh = Math.min(ch, (int) Math.round(h * s) & ~1);
        if (cw - fw <= 2) fw = cw;
        if (ch - fh <= 2) fh = ch;
        return new int[]{Math.max(2, fw), Math.max(2, fh)};
    }

    /**
     * A clip or recording: the file written where the player opens it, opened by ClipPlayer (FFmpeg probes the format
     * itself), played picture by picture at 320x180 (at most 200); flags 1-3: first sought to the middle, caught up
     * to the middle, or told to skip the first half second. A file it refuses (IOException) is counted, not reported.
     */
    private void clip(FuzzInput in, long n) {
        Unit f = in.file();
        if (f == null) return;
        Path file = work.resolve("input." + FuzzInput.extension(f.kind()));
        try {
            Files.write(file, f.data());
        } catch (IOException e) {
            exception(n, in.target(), e);
            return;
        }
        try (ClipPlayer p = new ClipPlayer(file)) {
            int[] px = new int[CLIP_W * CLIP_H];
            long half = Math.max(0, p.durationMs() / 2);
            switch (in.flags() & 3) {
                case 1 -> p.seek(half);
                case 2 -> p.catchUp(half);
                case 3 -> p.skipTo(500);
                default -> { }
            }
            for (int i = 0; i < CLIP_PICTURES; i++) {
                if (p.next(px, CLIP_W, CLIP_H) < 0) break;
                pictures[Target.CLIP.ordinal()]++;
            }
        } catch (IOException e) {
            clipsRefused++;
        } catch (Throwable e) {
            exception(n, in.target(), e);
        }
    }

    private VideoDecoder open(boolean hevc, byte[] config, boolean oneThread, long n, Target target) {
        try {
            return oneThread ? VideoDecoder.oneThread(hevc, config) : new VideoDecoder(hevc, config, false);
        } catch (IllegalStateException e) {
            decodersRefused++;
            return null;
        } catch (Throwable e) {
            exception(n, target, e);
            return null;
        }
    }

    private static void close(VideoDecoder d) {
        if (d != null) d.close();
    }

    /** A Java exception out of the decoding: counted by kind; each kind's first three said, with the input's number. */
    private void exception(long n, Target target, Throwable e) {
        exceptions++;
        String message = String.valueOf(e.getMessage()).replaceAll("\\d+", "#").replaceAll("\\s+", " ");
        String kind = target.name().toLowerCase(Locale.ROOT) + " " + e.getClass().getName() + ": " + (message.length() > 100 ? message.substring(0, 100) : message);
        int seen = excKinds.merge(kind, 1, Integer::sum);
        if (seen <= 3) say("EXC " + n + " " + kind);
    }

    private void stat() {
        Runtime rt = Runtime.getRuntime();
        long rss = memoryNow();
        say(String.format(Locale.ROOT, "STAT inputs=%d rssMb=%d peakMb=%d heapMb=%d live=%d/%d second=%d/%d clip=%d/%d clipsRefused=%d decodersRefused=%d exceptions=%d kinds=%d",
                inputs, rss >> 20, Math.max(peakBytes, rss) >> 20, (rt.totalMemory() - rt.freeMemory()) >> 20,
                runs[0], pictures[0], runs[1], pictures[1], runs[2], pictures[2], clipsRefused, decodersRefused, exceptions, excKinds.size()));
    }

    // ------------------------------------------------------------------------------------------------- the process

    static void say(String line) {
        System.out.println(line); // System.out flushes at each line: the parent has it before the next step
    }

    /** The parent gone (its end of our input closed): this child goes too, at once. */
    private static void endWithParent() {
        Thread t = new Thread(() -> {
            try {
                InputStream in = System.in;
                while (in.read() >= 0) {
                    // nothing is sent; the read returns at the end
                }
            } catch (IOException ignored) {
                // the same: the parent is gone
            }
            Runtime.getRuntime().halt(0);
        }, "parent-watch");
        t.setDaemon(true);
        t.start();
    }

    /** The memory of this process as Windows counts it (the working set), or the Java heap's in a dry run. */
    private static long memoryNow() {
        if (dry) {
            Runtime rt = Runtime.getRuntime();
            return rt.totalMemory() - rt.freeMemory();
        }
        return org.bytedeco.javacpp.Pointer.physicalBytes();
    }

    /** Over the cap this child ends itself, saying so: decoding that takes gigabytes is a finding, not a crash. */
    private static void watchMemory(int maxMb) {
        maxBytes = maxMb <= 0 ? Long.MAX_VALUE : (long) maxMb << 20;
        Thread t = new Thread(() -> {
            while (true) {
                long now = memoryNow();
                if (now > peakBytes) peakBytes = now;
                if (now > maxBytes) overMemory(now);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "memory-watch");
        t.setDaemon(true);
        t.start();
    }

    private static void overMemory(long bytes) {
        say("MEM " + bytes + " " + current);
        Runtime.getRuntime().halt(EXIT_MEMORY);
    }

    /**
     * For the self test (fuzz.misbehave): ends, hangs, crashes the JVM or goes over memory at the inputs named;
     * "after" ends only when this child had inputs before (memory spoilt earlier, found out at this one).
     *
     * @param before the inputs this child ran before this one
     */
    private static void misbehave(long n, long before) throws Exception {
        String spec = System.getProperty(MISBEHAVE, "");
        if (spec.isEmpty()) return;
        for (String s : spec.split(",")) {
            String[] p = s.split("@");
            if (p.length != 2 || Long.parseLong(p[1]) != n) continue;
            switch (p[0]) {
                case "exit" -> Runtime.getRuntime().halt(EXIT_MISBEHAVED);
                case "after" -> {
                    if (before > 0) Runtime.getRuntime().halt(EXIT_MISBEHAVED);
                }
                case "hang" -> Thread.sleep(Long.MAX_VALUE);
                case "mem" -> overMemory(maxBytes == Long.MAX_VALUE ? 1L << 40 : maxBytes + 1);
                case "crash" -> { // a real fault in native memory: the JVM's crash report, as a fault in FFmpeg would give
                    Class<?> unsafe = Class.forName("sun.misc.Unsafe");
                    java.lang.reflect.Field f = unsafe.getDeclaredField("theUnsafe");
                    f.setAccessible(true);
                    unsafe.getMethod("putAddress", long.class, long.class).invoke(f.get(null), 0L, 0L);
                }
                default -> throw new IllegalArgumentException(s);
            }
        }
    }

    private static final int IDLE_PRIORITY_CLASS = 0x40;
    private static final int SEM_FAILCRITICALERRORS = 0x1, SEM_NOGPFAULTERRORBOX = 0x2, SEM_NOOPENFILEERRORBOX = 0x8000;

    /**
     * Beside other work: the lowest priority, the last few cores only (FFmpeg then starts as many threads as it has
     * cores), and no Windows error box when it crashes. Best effort; said in one line.
     */
    static void polite(int cores) {
        if (!Boolean.parseBoolean(System.getProperty(POLITE, "true")) || !System.getProperty("os.name", "").startsWith("Windows")) return;
        List<String> done = new java.util.ArrayList<>();
        try {
            long process = org.lwjgl.system.windows.Kernel32.GetCurrentProcess();
            org.lwjgl.system.SharedLibrary k = org.lwjgl.system.windows.Kernel32.getLibrary();
            long fn = k.getFunctionAddress("SetErrorMode");
            if (fn != 0) {
                org.lwjgl.system.JNI.callI(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX, fn);
                done.add("no error box");
            }
            fn = k.getFunctionAddress("SetPriorityClass");
            if (fn != 0 && org.lwjgl.system.JNI.callPI(process, IDLE_PRIORITY_CLASS, fn) != 0) done.add("idle priority");
            int cpus = Runtime.getRuntime().availableProcessors();
            fn = k.getFunctionAddress("SetProcessAffinityMask");
            if (cores > 0 && cores < cpus && cpus <= 63 && fn != 0) {
                long mask = ((1L << cores) - 1) << (cpus - cores);
                if (org.lwjgl.system.JNI.callPPI(process, mask, fn) != 0) done.add("cores " + (cpus - cores) + "-" + (cpus - 1) + " of " + cpus);
            }
        } catch (Throwable t) {
            done.add("not lowered: " + t);
        }
        say("POLITE " + String.join(", ", done));
    }
}
