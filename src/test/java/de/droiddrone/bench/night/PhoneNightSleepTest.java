package de.droiddrone.bench.night;

import de.droiddrone.bench.PhoneWords;
import de.droiddrone.common.PowerFloor;
import de.droiddrone.pc.DiskSpace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phone's night with OVERWATCH ({@link PhoneNightSleep}) held before it meets the phone: the run's word for the
 * sleep, which round takes which, what keeps a round from OVERWATCH, its row in soak/phone-night.tsv - and the words
 * of the PC and the wiring from bench.ps1's -Sleep to the scenario, read in their files.
 */
class PhoneNightSleepTest {
    private static Path root() {
        Path root = Path.of("").toAbsolutePath(); // the tests run in build/test-work
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        return root;
    }

    // ------------------------------------------------------------------ the run's word

    @Test
    void leftOutTheNightIsDeepSleepAsItAlwaysWasAndAnUnknownWordStopsIt() {
        assertEquals(PhoneNightSleep.Mode.DEEP, PhoneNightSleep.Mode.parse(null));
        assertEquals(PhoneNightSleep.Mode.DEEP, PhoneNightSleep.Mode.parse(""));
        assertEquals(PhoneNightSleep.Mode.DEEP, PhoneNightSleep.Mode.parse("  "));
        assertEquals(PhoneNightSleep.Mode.DEEP, PhoneNightSleep.Mode.parse("deep"));
        assertEquals(PhoneNightSleep.Mode.OVERWATCH, PhoneNightSleep.Mode.parse("OVERWATCH"));
        assertEquals(PhoneNightSleep.Mode.BOTH, PhoneNightSleep.Mode.parse(" Both "));
        for (String wrong : List.of("deep sleep", "ow", "all", "1")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PhoneNightSleep.Mode.parse(wrong), wrong);
            assertTrue(e.getMessage().contains("deep, overwatch or both") && e.getMessage().contains(wrong), e.getMessage());
        }
        assertEquals(List.of("deep", "overwatch", "both"), Arrays.stream(PhoneNightSleep.Mode.values()).map(PhoneNightSleep.Mode::word).toList());
    }

    @Test
    void deepIsNeverOverwatchOverwatchAlwaysAndBothTakesTurnsOverwatchFirst() {
        List<Boolean> both = new ArrayList<>();
        for (int round = 0; round < 12; round++) {
            assertFalse(PhoneNightSleep.overwatch(PhoneNightSleep.Mode.DEEP, round), "deep, round " + round);
            assertTrue(PhoneNightSleep.overwatch(PhoneNightSleep.Mode.OVERWATCH, round), "overwatch, round " + round);
            both.add(PhoneNightSleep.overwatch(PhoneNightSleep.Mode.BOTH, round));
        }
        assertTrue(both.get(0), "a short try of both has OVERWATCH first");
        for (int i = 1; i < both.size(); i++) assertTrue(both.get(i) != both.get(i - 1), "both: the two by turns, never twice the same: " + both);
        assertEquals(6, both.stream().filter(b -> b).count());
    }

    @Test
    void aDeepRoundPressesAndWaitsForTheWordsItAlwaysDid() {
        assertEquals("DEEP SLEEP", PhoneNightSleep.button(false));
        assertEquals("DEEP SLEEP asked", PhoneNightSleep.asked(false));
        assertEquals("OVERWATCH", PhoneNightSleep.button(true));
        assertEquals("OVERWATCH asked", PhoneNightSleep.asked(true));
        assertEquals("deep", PhoneNightSleep.word(false));
        assertEquals("overwatch", PhoneNightSleep.word(true));
    }

    // ------------------------------------------------------------------ what OVERWATCH needs

    @Test
    void underTheAppsBatteryFloorWithRoomToSpareTheRoundTakesDeepSleep() {
        assertTrue(PhoneNightSleep.OVERWATCH_BATTERY_MIN > PowerFloor.BATTERY_MIN, "above the app's own floor");
        assertNull(PhoneNightSleep.lowForOverwatch(-1), "not known: it watches");
        assertNull(PhoneNightSleep.lowForOverwatch(100));
        assertNull(PhoneNightSleep.lowForOverwatch(PhoneNightSleep.OVERWATCH_BATTERY_MIN));
        String low = PhoneNightSleep.lowForOverwatch(PhoneNightSleep.OVERWATCH_BATTERY_MIN - 1);
        assertNotNull(low);
        assertTrue(low.contains((PhoneNightSleep.OVERWATCH_BATTERY_MIN - 1) + " %") && low.contains(PowerFloor.BATTERY_MIN + " %"), low);
        assertNotNull(PhoneNightSleep.lowForOverwatch(0));
        // the app's own floor, as the phone runs it "off the cable" (not charging), two readings in a row
        PowerFloor floor = new PowerFloor();
        for (int i = 0; i < 4; i++) {
            assertEquals(PowerFloor.REASON_NONE, floor.check(PhoneNightSleep.OVERWATCH_BATTERY_MIN, false, 0, 300), "the bench's least battery for OVERWATCH keeps it");
        }
        floor.reset();
        floor.check(PowerFloor.BATTERY_MIN - 1, false, 0, 300);
        assertEquals(PowerFloor.REASON_BATTERY, floor.check(PowerFloor.BATTERY_MIN - 1, false, 0, 300), "under it the phone leaves OVERWATCH by itself");
    }

    @Test
    void thePcsRefusalIsFoundAmongItsNoticesAndAWaitIsNone() {
        assertNull(PhoneNightSleep.refusal(List.of()));
        assertNull(PhoneNightSleep.refusal(List.of("WAIT")));
        assertNull(PhoneNightSleep.refusal(Arrays.asList(null, "ALREADY OVERWATCH")));
        assertEquals("NO PICTURE TO WATCH", PhoneNightSleep.refusal(List.of("WAIT", "NO PICTURE TO WATCH")));
        assertEquals("UPDATE THE PHONE APP", PhoneNightSleep.refusal(List.of("Update the phone app")));
    }

    @Test
    void underThePcsDiskFloorTheFilmSaysSoOnce() {
        assertNull(PhoneNightSleep.diskNote(-1), "not known");
        assertNull(PhoneNightSleep.diskNote(DiskSpace.LOW_BYTES));
        assertNull(PhoneNightSleep.diskNote(500_000_000_000L));
        String note = PhoneNightSleep.diskNote(1_200_000_000L);
        assertNotNull(note);
        assertTrue(note.startsWith("LOW DISK") && note.contains("1.2 GB free") && note.contains("2 GB"), note);
    }

    // ------------------------------------------------------------------ the table

    @Test
    void theTableHasAModeColumnAndAnOverwatchRoundsOwnFiguresBlankInADeepRound() {
        List<String> c = PhoneNightSleep.COLUMNS;
        assertEquals("minutes", c.get(0), "the report draws every column against the first: minutes");
        assertEquals(List.of("minutes", "round", "mode"), c.subList(0, 3));
        assertEquals(List.of("phone app MB", "PC heap MB", "PC threads", "woke after s", "phone's own checks", "new sessions on the phone", "socket opened anew"),
                c.subList(3, PhoneNightSleep.EVERY_ROUND), "the night's figures as before, in their order");
        assertEquals(List.of("alerts", "clips", "to deep sleep by itself", "battery C", "phone app MB watching"), c.subList(PhoneNightSleep.EVERY_ROUND, c.size()));
        assertEquals(c.size(), c.stream().distinct().count(), "no column twice");

        Object[] every = {12.5, 2, "deep", 140L, 300L, 90, 61L, 2L, 1L, 0L};
        Object[] deep = PhoneNightSleep.row(every, null);
        assertEquals(c.size(), deep.length);
        assertArrayEquals(every, Arrays.copyOf(deep, every.length));
        for (int i = every.length; i < deep.length; i++) assertEquals("", deep[i], c.get(i) + " is blank in a DEEP SLEEP round");

        String nl = "\n", at = "11-24 01:02:03.456 I/DDLog(12345): ";
        String phoneLog = at + PhoneWords.SLEEP_CHECK + nl
                + at + PhoneWords.OVERWATCH_WATCHING + " 2)" + nl
                + at + PhoneWords.OVERWATCH_MOTION + " 1, calling 1 PC" + nl
                + at + "Overwatch: clip ow-1-1.mp4 (812" + PhoneWords.OVERWATCH_CLIP_WAITS + nl
                + at + PhoneWords.OVERWATCH_MOTION + " 2, calling 1 PC" + nl
                + at + "Overwatch: clip ow-2-1.mp4 (90" + PhoneWords.OVERWATCH_CLIP_WAITS + nl
                + at + "Overwatch: clip ow-2-2.mp4 (64" + PhoneWords.OVERWATCH_CLIP_WAITS + nl
                + at + "Overwatch: phone too hot (battery 61 %, 45.5" + PhoneWords.OVERWATCH_TO_DEEP_SLEEP + nl
                + at + "Overwatch: stopped (deep sleep instead)" + nl;
        PhoneNightSleep.Overwatched w = PhoneNightSleep.Overwatched.of(phoneLog, 38.25, 212);
        assertEquals(new PhoneNightSleep.Overwatched(2, 3, 1, 38.25, 212), w);
        Object[] watched = PhoneNightSleep.row(new Object[]{20.0, 3, "overwatch", 150L, 310L, 92, 58L, 2L, 0L, 0L}, w);
        assertEquals(List.of(2L, 3L, 1L, 38.25, 212L), Arrays.asList(watched).subList(PhoneNightSleep.EVERY_ROUND, c.size()));
        Object[] unread = PhoneNightSleep.row(new Object[]{20.0, 3, "overwatch", 150L, 310L, 92, 58L, 2L, 0L, 0L},
                PhoneNightSleep.Overwatched.of("", Double.NaN, -1));
        assertEquals(List.of(0L, 0L, 0L, "", ""), Arrays.asList(unread).subList(PhoneNightSleep.EVERY_ROUND, c.size()), "a temperature or memory not read is blank, never NaN");
        assertThrows(IllegalArgumentException.class, () -> PhoneNightSleep.row(new Object[]{1.0, 1}, null), "a row short of figures is the scenario's mistake");
    }

    // ------------------------------------------------------------------ held to their files

    /** The PC's words the scenario waits for and reads are still the PC's, and the notice it reads still has its title. */
    @Test
    void thePcsWordsAreStillWhatThePcSays() throws Exception {
        String window = Files.readString(root().resolve("src/main/java/de/droiddrone/pc/gui/MainWindow.java"), StandardCharsets.UTF_8);
        for (boolean ow : List.of(false, true)) {
            assertTrue(window.contains("\"" + PhoneNightSleep.asked(ow) + ": "), PhoneNightSleep.asked(ow));
        }
        for (String r : PhoneNightSleep.PC_REFUSALS) {
            assertTrue(window.contains("new LockedNotice(pressed, videoView, \"" + r + "\""), r + ": one of the PC's notices beside OVERWATCH");
        }
        Field title = Class.forName("de.droiddrone.pc.gui.LockedNotice", false, getClass().getClassLoader()).getDeclaredField("title");
        assertEquals(String.class, title.getType(), "the scenario reads a notice's title by this field");
    }

    /** bench.ps1 -Sleep deep|overwatch|both -> BENCH_SLEEP -> build.gradle -> bench.sleep -> the scenario. */
    @Test
    void benchPs1sSleepReachesTheScenario() throws Exception {
        String bench = Files.readString(root().resolve("bench.ps1"), StandardCharsets.UTF_8);
        Matcher set = Pattern.compile("\\[ValidateSet\\(([^)]*)\\)\\]\\s*\\[string\\]\\$Sleep\\s*=\\s*'(\\w+)'").matcher(bench);
        assertTrue(set.find(), "bench.ps1 has a -Sleep parameter with its words");
        List<String> words = Arrays.stream(set.group(1).split(",")).map(s -> s.trim().replace("'", "")).toList();
        assertEquals(Arrays.stream(PhoneNightSleep.Mode.values()).map(PhoneNightSleep.Mode::word).toList(), words, "-Sleep takes the scenario's words");
        assertEquals("deep", set.group(2), "left out: the night as it always was");
        assertTrue(bench.contains("$env:BENCH_SLEEP = $Sleep"), "bench.ps1 always hands it on");
        assertTrue(bench.contains("-Sleep deep | overwatch | both"), "and its help says it");
        String gradle = Files.readString(root().resolve("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(gradle.contains("systemProperty '" + PhoneNightSleep.PROPERTY + "', System.getenv('BENCH_SLEEP') ?: 'deep'"),
                "build.gradle hands BENCH_SLEEP to the scenarios' JVM");
        assertTrue(bench.indexOf("$env:BENCH_SLEEP") < bench.indexOf("function Invoke-Suite"), "set before any suite runs");
    }
}
