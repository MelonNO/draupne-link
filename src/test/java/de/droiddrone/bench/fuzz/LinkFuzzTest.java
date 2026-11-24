package de.droiddrone.bench.fuzz;

import de.droiddrone.bench.fuzz.LinkInput.Packet;
import de.droiddrone.bench.fuzz.LinkInput.Target;
import de.droiddrone.pc.LinkFuzzTargets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The protocol fuzz ({@link ProtocolFuzz}) in a few seconds: the mutator repeats itself and stays in bounds, the seeds
 * are valid streams that take each receive path's valid way (a session up, sticks taken, frames whole), a few thousand
 * damaged inputs with a fixed seed find nothing, and the child runs the inputs as the long run does. The hours are
 * gradlew protocolFuzz's.
 */
class LinkFuzzTest {
    private static PrintStream out;

    @BeforeAll
    static void quiet() {
        out = LinkFuzzTargets.captureLogs(); // the app's log (Log.log) into the targets' queue, read for caught exceptions
    }

    @AfterAll
    static void loud() {
        LinkFuzzTargets.restoreLogs(out);
    }

    /** What one or more inputs found and did. */
    static final class Found implements LinkFuzzTargets.Report {
        final List<String> problems = new ArrayList<>();
        final Map<String, Long> counts = new TreeMap<>();
        long index;

        @Override
        public void exception(String where, Throwable t) {
            problems.add("input " + index + ": EXC " + where + ": " + ProtocolFuzzChild.kind(t));
        }

        @Override
        public void caught(String where, String what) {
            counts.merge("caught", 1L, Long::sum);
        }

        @Override
        public void broken(String rule, String detail) {
            problems.add("input " + index + ": BAD " + rule + " | " + detail);
        }

        @Override
        public void count(String what) {
            counts.merge(what, 1L, Long::sum);
        }

        long n(String what) {
            return counts.getOrDefault(what, 0L);
        }
    }

    @Test
    void theSameNumberMakesTheSameInputAndEveryInputStaysInBounds() throws Exception {
        List<LinkInput> seeds = LinkFuzzTargets.seeds(1);
        List<LinkInput> again = LinkFuzzTargets.seeds(1);
        assertEquals(seeds.size(), again.size());
        for (int s = 0; s < seeds.size(); s++) {
            for (int k = 0; k < seeds.get(s).packets().size(); k++) {
                assertEquals(seeds.get(s).packets().get(k), again.get(s).packets().get(k),
                        "the seeds are the same for the same seed (no clock, no SecureRandom in them): seed " + s + ", datagram " + k);
            }
            assertEquals(seeds.get(s), again.get(s), "seed " + s);
        }
        LinkMutator m = new LinkMutator(seeds, EnumSet.allOf(Target.class));
        Set<Long> different = new HashSet<>();
        Map<Target, Integer> made = new EnumMap<>(Target.class);
        int intact = 0;
        for (long i = 0; i < 5000; i++) {
            LinkInput a = m.make(42, i);
            assertEquals(a, m.make(42, i), "input " + i + " made twice");
            assertEquals(a, LinkInput.fromBytes(a.toBytes()), "input " + i + " written and read back");
            assertTrue(a.packets().size() <= LinkInput.MAX_PACKETS, a.describe());
            for (Packet p : a.packets()) assertTrue(p.data().length <= LinkInput.MAX_PACKET_BYTES, a.describe());
            different.add(a.crc());
            made.merge(a.target(), 1, Integer::sum);
            if (a.intact()) intact++;
        }
        assertTrue(different.size() > 4900, "inputs made differ: " + different.size());
        assertEquals(EnumSet.allOf(Target.class), made.keySet(), "every target gets inputs: " + made);
        assertTrue(intact > 300 && intact < 2500, "some inputs only reordered, lost, doubled (the strict checks): " + intact);
        assertNotEquals(m.make(42, 7), m.make(43, 7), "another run's seed, other inputs");
    }

    /** Each seed unchanged: nothing found, and each target's valid way taken. */
    @Test
    void theSeedsAreValidStreamsThatTakeTheValidWay() {
        Map<Target, Found> by = new EnumMap<>(Target.class);
        for (LinkInput s : LinkFuzzTargets.seeds(1)) {
            Found f = by.computeIfAbsent(s.target(), t -> new Found());
            LinkFuzzTargets.run(s, f);
        }
        for (Map.Entry<Target, Found> e : by.entrySet()) assertEquals(List.of(), e.getValue().problems, e.getKey() + ": " + e.getValue().counts);
        Found phone = by.get(Target.PHONE_LINK);
        for (String c : List.of("phone session up", "phone sticks taken", "phone packet handed on", "phone config", "phone idle sticks",
                "phone update address taken", "phone power mode 3", "phone new session")) {
            assertTrue(phone.n(c) > 0, c + ": " + phone.counts);
        }
        assertEquals(0, phone.n("caught"), "a valid stream reads whole: " + phone.counts);
        Found outside = by.get(Target.PHONE_OUTSIDE);
        for (String c : List.of("phone name asked", "phone named", "phone fleet mode taken", "phone observer", "phone held awake")) {
            assertTrue(outside.n(c) > 0, c + ": " + outside.counts);
        }
        Found pc = by.get(Target.PC_LINK);
        for (String c : List.of("pc session up", "pc session started", "pc packet handed on", "pc video config", "pc frame whole")) {
            assertTrue(pc.n(c) > 0, c + ": " + pc.counts);
        }
        assertEquals(0, pc.n("caught"), pc.counts.toString());
        assertTrue(by.get(Target.PC_VIDEO).n("pc frame whole") > 40, by.get(Target.PC_VIDEO).counts.toString());
        assertTrue(by.get(Target.PC_TELEMETRY).n("pc telemetry read") > 40, by.get(Target.PC_TELEMETRY).counts.toString());
        Found other = by.get(Target.PC_OUTSIDE);
        for (String c : List.of("pc overwatch alert", "pc preview unit whole", "pc clip info", "pc sleep hello read", "pc name reply read",
                "pc pilot view read", "pc observed state read")) {
            assertTrue(other.n(c) > 0, c + ": " + other.counts);
        }
    }

    /** A few thousand damaged inputs, a fixed seed: nothing broken, no exception (the long runs are gradlew protocolFuzz's). */
    @Test
    void aFewThousandDamagedInputsFindNothing() {
        LinkMutator m = new LinkMutator(LinkFuzzTargets.seeds(7), EnumSet.allOf(Target.class));
        Found f = new Found();
        long until = System.currentTimeMillis() + 8000;
        int ran = 0;
        for (long i = 0; i < 4000 && System.currentTimeMillis() < until; i++, ran++) {
            f.index = i;
            LinkFuzzTargets.run(m.make(7, i), f);
            if (f.problems.size() > 10) break;
        }
        assertEquals(List.of(), f.problems, "after " + ran + " inputs; counts " + f.counts);
        assertTrue(ran >= 500, "inputs run: " + ran);
        assertTrue(f.n("phone sticks taken") > 0 && f.n("pc frame whole") > 0 && f.n("phone session up") > 0, f.counts.toString());
    }

    /** The checks' own pieces: an update's address read as a URI, not as text. */
    @Test
    void anUpdateAddressIsReadAsAnAddress() throws Exception {
        java.lang.reflect.Method m = LinkFuzzTargets.class.getDeclaredMethod("releaseAddress", String.class);
        m.setAccessible(true);
        String ok = de.droiddrone.common.UpdateSource.RELEASE_DOWNLOADS + "flight-2.3.0-pc.56/draupne-flight.apk";
        assertEquals(true, m.invoke(null, ok));
        for (String bad : List.of("https://github.com.example/MelonNO/draupne-link/releases/download/a/b.apk",
                "https://github.com/MelonNO/draupne-link/releases/download/a/b.apk?x=1",
                "https://github.com/MelonNO/draupne-link/releases/download/a/../../b.apk",
                "http://github.com/MelonNO/draupne-link/releases/download/a/b.apk",
                "https://user@github.com/MelonNO/draupne-link/releases/download/a/b.apk")) {
            assertEquals(false, m.invoke(null, bad), bad);
        }
    }

    /** The child as the long run starts it: two hundred inputs, counted, summed, nothing found. */
    @Test
    void theChildRunsTheInputsAsTheLongRunDoes(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        ProtocolFuzz.Options o = new ProtocolFuzz.Options();
        o.root = dir;
        o.seed = 3;
        o.lastIndex = 199;
        o.polite = false;
        o.timeoutMs = 20_000;
        o.out = new PrintStream(said, true, StandardCharsets.UTF_8);
        ProtocolFuzz.Summary s = ProtocolFuzz.run(o);
        String text = said.toString(StandardCharsets.UTF_8);
        assertNull(s.problem(), text);
        assertEquals(200, s.inputs(), text);
        assertTrue(s.passed(), text);
        long byTarget = 0;
        for (Target t : Target.values()) byTarget += s.counts().getOrDefault(t.name().toLowerCase(java.util.Locale.ROOT), 0L);
        assertEquals(200, byTarget, "the child's counts came home: " + s.counts());
        assertTrue(Files.readString(s.run().resolve("summary.txt")).contains("PASS"), text);
        try (var seeds = Files.list(s.run().resolve("seeds"))) {
            assertTrue(seeds.filter(p -> p.toString().endsWith(".pfz")).count() >= 10, "the seeds written down");
        }
    }
}
