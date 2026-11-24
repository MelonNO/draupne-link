package de.droiddrone.bench.fuzz;

import de.droiddrone.bench.fuzz.FuzzInput.Target;
import de.droiddrone.bench.fuzz.FuzzInput.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The decoder fuzz's own parts ({@link DecoderFuzz}): the mutator repeats itself and stays in bounds; a child that
 * dies, hangs, crashes the JVM or goes over its memory leaves the input it had, run once more, and the run goes on with
 * the next; and the child really runs the app's decoding (a minute's fuzz is gradlew decoderFuzz's work, not this).
 */
class DecoderFuzzTest {
    @Test
    void theSameNumberMakesTheSameInputAndEveryInputStaysInBounds() throws Exception {
        List<FuzzInput> seeds = seeds();
        Mutator m = new Mutator(seeds, EnumSet.allOf(Target.class));
        Set<Long> different = new HashSet<>();
        Map<Target, Integer> made = new EnumMap<>(Target.class);
        int asSeeded = 0;
        for (long i = 0; i < 20_000; i++) {
            FuzzInput a = m.make(42, i);
            assertEquals(a, m.make(42, i), "input " + i + " made twice");
            assertEquals(a, FuzzInput.fromBytes(a.toBytes()), "input " + i + " written and read back");
            assertTrue(a.units().size() <= Mutator.MAX_UNITS, a.describe());
            for (Unit u : a.units()) {
                assertTrue(u.data().length <= (u.isFile() ? Mutator.MAX_FILE_BYTES : Mutator.MAX_UNIT_BYTES), a.describe());
                assertEquals(a.target() == Target.CLIP, u.isFile(), "a clip is one file, a stream has none: " + a.describe());
            }
            if (a.target() == Target.CLIP) assertEquals(1, a.units().size());
            different.add(a.crc());
            made.merge(a.target(), 1, Integer::sum);
            for (FuzzInput s : seeds) if (s.units().equals(a.units())) asSeeded++;
        }
        assertTrue(different.size() > 18_000, "inputs made differ: " + different.size());
        assertEquals(EnumSet.allOf(Target.class), made.keySet(), "every target gets inputs: " + made);
        assertTrue(asSeeded < 1000, "a seed is mostly changed: " + asSeeded + " of 20000 unchanged");
        assertNotEquals(m.make(42, 7), m.make(43, 7), "another run's seed, other inputs");
        // a mutator of the targets asked for only
        Mutator clips = new Mutator(seeds, EnumSet.of(Target.CLIP));
        for (long i = 0; i < 50; i++) assertEquals(Target.CLIP, clips.make(1, i).target());
        assertThrows(IllegalArgumentException.class, () -> new Mutator(seeds.subList(0, 2), EnumSet.of(Target.CLIP)), "no clip seeds, no clip inputs");
    }

    @Test
    void theNalUnitsAndTheFilesStructuresAreFound() {
        byte[] annexB = {0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5, 6};
        List<int[]> nals = Mutator.nals(annexB);
        assertEquals(3, nals.size());
        assertArrayEquals(new int[]{4, 7}, nals.get(0));
        assertArrayEquals(new int[]{10, 12}, nals.get(1), "the zero of the 4-byte start code after it is no part of it");
        assertArrayEquals(new int[]{16, 20}, nals.get(2));
        List<byte[]> parts = new ArrayList<>();
        for (int[] n : nals) parts.add(java.util.Arrays.copyOfRange(annexB, n[0], n[1]));
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5, 6}, Mutator.join(parts));

        assertEquals(List.of("ftyp", "moov", "trak", "mdia", "mdhd"), Mutator.regions(mp4()).stream().map(r -> type(mp4(), r)).toList());
        assertEquals(List.of(0x1A45DFA3L, 0x4286L, 0x18538067L, 0x1F43B675L, 0xA3L), Mutator.regions(mkv()).stream().map(Mutator.Region::id).toList(),
                "EBML and its version, the segment (its size unknown), a cluster, a block");
        Mutator.Region block = Mutator.regions(mkv()).get(4);
        assertEquals(5, block.end() - block.start(), "the block's size read from its one-byte size");
        assertEquals(3, Mutator.regions(ts()).size(), "a region a packet");
        assertEquals(List.of(), Mutator.regions(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}));
    }

    /** The sizes the second decoders' pictures are converted to: the child's copies, held to the app's own methods. */
    @Test
    void theSecondDecodersConvertAtTheAppsSizes() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        java.lang.reflect.Method outputSize = Class.forName("de.droiddrone.pc.gui.OsdRecorder", false, loader).getDeclaredMethod("outputSize", int.class, int.class);
        java.lang.reflect.Method fit = Class.forName("de.droiddrone.pc.gui.OsdFrame", false, loader).getDeclaredMethod("fit", int.class, int.class, int.class, int.class);
        java.lang.reflect.Field previewMax = Class.forName("de.droiddrone.pc.overwatch.OverwatchHub", false, loader).getDeclaredField("PICTURE_MAX_W");
        outputSize.setAccessible(true);
        fit.setAccessible(true);
        previewMax.setAccessible(true);
        assertEquals(640, previewMax.getInt(null), "the overwatch card's picture width");
        for (int[] p : new int[][]{{1280, 720}, {1920, 1080}, {1080, 1920}, {360, 640}, {168, 108}, {316, 624}, {854, 480}, {16, 4096}}) {
            int[] canvas = (int[]) outputSize.invoke(null, p[0], p[1]);
            int[] at = (int[]) fit.invoke(null, p[0], p[1], canvas[0], canvas[1]);
            assertArrayEquals(new int[]{at[2], at[3]}, FuzzChild.canvasFit(p[0], p[1], false), "the OSD recording of " + p[0] + "x" + p[1]);
            at = (int[]) fit.invoke(null, p[0], p[1], 1280, 720);
            assertArrayEquals(new int[]{at[2], at[3]}, FuzzChild.canvasFit(p[0], p[1], true), "STANAG's of " + p[0] + "x" + p[1]);
        }
        assertArrayEquals(new int[]{360, 640}, FuzzChild.previewSize(360, 640), "a preview up to 640 wide: at its own size");
        assertArrayEquals(new int[]{640, 360}, FuzzChild.previewSize(1280, 720));
        assertArrayEquals(new int[]{1080, 1920}, FuzzChild.canvasFit(1080, 1920, false), "a picture 720 high or more: the OSD canvas is its own size");
    }

    @Test
    void aChildThatDiesHangsCrashesOrGrowsLeavesItsInputAndTheRunGoesOn(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        DecoderFuzz.Options o = new DecoderFuzz.Options();
        o.root = dir;
        o.seed = 7;
        o.lastIndex = 16;
        o.timeoutMs = 600;
        o.wallFactor = 3;
        o.polite = false;
        o.dry = true; // nothing decoded: the parent's part only
        o.misbehave = "exit@3,hang@6,crash@9,mem@12,after@14"; // after: only after other inputs in the same child
        o.seeds = seeds();
        o.out = new PrintStream(said, true, StandardCharsets.UTF_8);
        DecoderFuzz.Summary s = DecoderFuzz.run(o);
        String out = said.toString(StandardCharsets.UTF_8);

        assertNull(s.problem(), out);
        assertEquals(17, s.inputs(), "inputs 0 to 16, each handed over once: " + out);
        assertEquals(List.of("crash", "hang", "crash", "memory", "crash"), s.findings().stream().map(DecoderFuzz.Finding::kind).toList(), out);
        assertEquals(List.of(3L, 6L, 9L, 12L, 14L), s.findings().stream().map(DecoderFuzz.Finding::index).toList(), out);
        assertEquals(12, s.children(), "a child at the start and after each of the five, each input run again alone, the last one's "
                + "child's inputs once more: " + out);
        assertFalse(s.passed());
        DecoderFuzz.Finding after = s.findings().get(4);
        assertTrue(after.again().startsWith("not alone") && after.again().endsWith("inputs 13 to 14 once more in one child: yes, the same"), after.again());
        Mutator m = new Mutator(o.seeds, EnumSet.allOf(Target.class));
        for (DecoderFuzz.Finding f : s.findings()) {
            assertEquals(m.make(7, f.index()), FuzzInput.read(f.folder().resolve("input.dfz")), "the input the child had: " + f);
            String about = Files.readString(f.folder().resolve("about.txt"));
            assertTrue(about.contains("the input here is the one the child had"), about);
            if (f != after) assertTrue(f.again().startsWith("yes"), "run again, the same again: " + f);
            assertTrue(Files.readAllLines(f.folder().resolve("log.txt")).contains("AT " + f.index() + " " + Long.toHexString(m.make(7, f.index()).crc())),
                    "the child's last lines: " + f);
            assertTrue(about.contains("gradlew decoderFuzz -Preplay="), about);
        }
        assertTrue(s.findings().get(0).detail().contains("exit 134"), s.findings().get(0).detail());
        Path crash = s.findings().get(2).folder();
        assertTrue(Files.exists(crash.resolve("hs_err.log")), "the JVM's crash report moved to the finding: " + crash);
        assertTrue(s.findings().get(2).detail().contains("JVM crash report") && s.findings().get(2).detail().contains("problematic frame"), s.findings().get(2).detail());
        assertTrue(s.findings().get(1).detail().contains("hung at input 6"), s.findings().get(1).detail());
        String summary = Files.readString(s.run().resolve("summary.txt"));
        assertTrue(summary.contains("17 inputs") && summary.contains("FAIL") && summary.contains("2 x crashed (exit 134: ended on purpose)"), summary);
        try (Stream<Path> hs = Files.list(s.run().resolve("hs"))) {
            assertEquals(List.of(), hs.toList(), "every crash report went to its finding");
        }
    }

    /** The real thing, a few dozen inputs: seeds made by FFmpeg in a child, each target decoding pictures. */
    @Test
    void theChildRunsTheAppsDecoding(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        DecoderFuzz.Options o = new DecoderFuzz.Options();
        o.root = dir;
        o.seed = 1;
        o.lastIndex = 59;
        o.timeoutMs = 30_000;
        o.polite = false;
        o.pcEncoders = false; // the same seeds on every PC
        o.out = new PrintStream(said, true, StandardCharsets.UTF_8);
        DecoderFuzz.Summary s = DecoderFuzz.run(o);
        String out = said.toString(StandardCharsets.UTF_8);
        assertNull(s.problem(), out);
        assertEquals(60, s.inputs(), out);
        try (Stream<Path> seeds = Files.list(s.run().resolve("seeds"))) {
            assertTrue(seeds.filter(p -> p.toString().endsWith(".dfz")).count() >= 10, "streams and clips made: " + out);
        }
        for (String target : List.of("live", "second", "clip")) {
            assertTrue(s.counts().getOrDefault(target, 0L) > 0, target + " inputs: " + s.counts() + "\n" + out);
            assertTrue(s.counts().getOrDefault(target + "Pictures", 0L) > 0, target + " pictures decoded: " + s.counts() + "\n" + out);
        }
        // a crash found here is a finding of FFmpeg's, not this test's: the run went on; it is said, and kept beside the
        // tests' working folder (with the run's seeds: -PseedsFrom= and -Pseed=1 -Plast= run the same inputs again)
        if (!s.findings().isEmpty()) {
            Path kept = Path.of("decoder-fuzz-found").toAbsolutePath().resolve(s.run().getFileName());
            for (String part : List.of("findings", "seeds")) copyTree(s.run().resolve(part), kept.resolve(part));
            Files.writeString(kept.resolve("summary.txt"), s.text());
            System.out.println("decoder fuzz smoke run found: " + s.findings() + " - kept in " + kept);
        }
    }

    private static void copyTree(Path from, Path to) throws java.io.IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path t = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(t);
                else Files.copy(f, t);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- made-up seeds

    /** Streams shaped like H.264 and H.265 (start codes, NAL headers, random payload) and MP4, MKV, TS skeletons. */
    static List<FuzzInput> seeds() {
        Random r = new Random(3);
        List<FuzzInput> out = new ArrayList<>();
        out.add(new FuzzInput(Target.LIVE, 0, List.of(
                new Unit(FuzzInput.CONFIG_H264, nal(r, false, 0x67, 12, 0x68, 4)), // SPS, PPS
                new Unit(FuzzInput.KEY_FRAME, nal(r, false, 0x65, 300)),           // IDR
                new Unit(FuzzInput.FRAME, nal(r, false, 0x41, 120)),
                new Unit(FuzzInput.FRAME, nal(r, false, 0x41, 90)))));
        out.add(new FuzzInput(Target.LIVE, 0, List.of(
                new Unit(FuzzInput.CONFIG_HEVC, nal(r, true, 32, 20, 33, 30, 34, 6)), // VPS, SPS, PPS
                new Unit(FuzzInput.KEY_FRAME, nal(r, true, 19, 400)),                 // IDR_W_RADL
                new Unit(FuzzInput.FRAME, nal(r, true, 1, 150)))));                   // TRAIL_R
        out.add(new FuzzInput(Target.CLIP, 0, List.of(new Unit(FuzzInput.FILE_MP4, mp4()))));
        out.add(new FuzzInput(Target.CLIP, 0, List.of(new Unit(FuzzInput.FILE_MKV, mkv()))));
        out.add(new FuzzInput(Target.CLIP, 0, List.of(new Unit(FuzzInput.FILE_TS, ts()))));
        return out;
    }

    /** NAL units, each a pair: H.264's header byte or H.265's type, and so many random bytes after the header. */
    private static byte[] nal(Random r, boolean hevc, int... typeAndLength) {
        List<byte[]> nals = new ArrayList<>();
        for (int i = 0; i < typeAndLength.length; i += 2) {
            byte[] n = new byte[typeAndLength[i + 1] + (hevc ? 2 : 1)];
            r.nextBytes(n);
            for (int j = 0; j < n.length; j++) if (n[j] == 0) n[j] = 1; // no start code inside
            if (hevc) {
                n[0] = (byte) (typeAndLength[i] << 1);
                n[1] = 1;
            } else {
                n[0] = (byte) typeAndLength[i];
            }
            nals.add(n);
        }
        return Mutator.join(nals);
    }

    static byte[] mp4() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        box(b, "ftyp", "isom\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream mdhd = new ByteArrayOutputStream();
        box(mdhd, "mdhd", new byte[]{0, 0, 0, 1, 0, 0, 0, 2});
        ByteArrayOutputStream mdia = new ByteArrayOutputStream();
        box(mdia, "mdia", mdhd.toByteArray());
        ByteArrayOutputStream trak = new ByteArrayOutputStream();
        box(trak, "trak", mdia.toByteArray());
        box(b, "moov", trak.toByteArray());
        return b.toByteArray();
    }

    private static void box(ByteArrayOutputStream b, String type, byte[] payload) {
        int size = 8 + payload.length;
        b.writeBytes(new byte[]{(byte) (size >> 24), (byte) (size >> 16), (byte) (size >> 8), (byte) size});
        b.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        b.writeBytes(payload);
    }

    private static String type(byte[] d, Mutator.Region r) {
        return new String(d, r.sizeAt() + 4, 4, StandardCharsets.ISO_8859_1);
    }

    static byte[] mkv() {
        return new byte[]{
                0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x84, 0x42, (byte) 0x86, (byte) 0x81, 0x01,           // EBML, 4 bytes
                0x18, 0x53, (byte) 0x80, 0x67, 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // Segment, unknown size
                0x1F, 0x43, (byte) 0xB6, 0x75, (byte) 0x87,                                                    // Cluster, 7 bytes
                (byte) 0xA3, (byte) 0x85, (byte) 0x81, 0x00, 0x00, (byte) 0x80, 0x65};                       // SimpleBlock: track 1, time 0, key
    }

    static byte[] ts() {
        byte[] d = new byte[188 * 3];
        for (int p = 0; p < 3; p++) {
            d[p * 188] = 0x47;
            d[p * 188 + 1] = (byte) (p == 0 ? 0x40 : 0x01);
            d[p * 188 + 2] = (byte) (p == 0 ? 0x00 : 0x00);
            d[p * 188 + 3] = (byte) (0x10 | p);
            for (int i = 4; i < 188; i++) d[p * 188 + i] = (byte) 0xff;
        }
        return d;
    }
}
