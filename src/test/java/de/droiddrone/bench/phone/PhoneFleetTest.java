package de.droiddrone.bench.phone;

import de.droiddrone.bench.FleetRows;
import de.droiddrone.bench.hw.AppLinkLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PhoneFleet}: what the real phone's FLEET scenario works out without the phone. */
class PhoneFleetTest {
    @Test
    void theRealPhonesNameIsTheStartOfItsPhoneListEntry() {
        assertEquals("MobileAlpha", PhoneFleet.nameOf("MobileAlpha  online"));
        assertEquals("MobileZulu", PhoneFleet.nameOf("  MobileZulu V online"));
        assertEquals("", PhoneFleet.nameOf(""));
        assertEquals("", PhoneFleet.nameOf(null));
    }

    @Test
    void theMadeUpDronesAreNamedFromZuluDownAndNeverAsTheRealPhone() {
        assertEquals(List.of("MobileZulu", "MobileYankee", "MobileX-ray"), PhoneFleet.madeUpNames("MobileAlpha", 3));
        assertEquals(List.of("MobileZulu", "MobileX-ray", "MobileWhiskey"), PhoneFleet.madeUpNames("MobileYankee", 3));
        assertEquals(List.of("MobileYankee", "MobileX-ray", "MobileWhiskey"), PhoneFleet.madeUpNames("mobilezulu", 3), "any case");
        assertEquals(List.of("MobileZulu", "MobileYankee", "MobileX-ray"), PhoneFleet.madeUpNames("", 3), "a phone with no name yet");
    }

    private static FleetRows.Row row(String name, String badge, long heardAgoMs) {
        return new FleetRows.Row(name, badge, badge, null, heardAgoMs, 50, true);
    }

    @Test
    void aMadeUpDronesRowThatSaysSomethingElseOrIsGoneIsNamed() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("MobileZulu", "ACTIVE");
        first.put("MobileYankee", "ACTIVE");
        first.put("MobileX-ray", "ACTIVE");
        List<FleetRows.Row> now = List.of(row("MobileAlpha", "ASLEEP", 40_000), row("MobileZulu", "ACTIVE", 1000), row("MobileYankee", "UNKNOWN", 70_000));
        assertEquals(List.of("MobileYankee: ACTIVE -> UNKNOWN", "MobileX-ray: no row"), PhoneFleet.changed(first, now));
        assertEquals(List.of(), PhoneFleet.changed(Map.of("MobileZulu", "ACTIVE"), now), "the real phone's own row changing is none of the made-up drones'");
    }

    @Test
    void thePcHeardThePhoneEachTimeItsRowsHeardFalls() {
        assertEquals(2, PhoneFleet.heardAgain(List.of(5000L, 7000L, 9000L, 500L, 2500L, 4500L, -1L, 300L)));
        assertEquals(0, PhoneFleet.heardAgain(List.of(1000L, 3000L, 5000L, 7000L)), "only growing: not heard");
        assertEquals(0, PhoneFleet.heardAgain(List.of(3000L, 2500L)), "a card's rounding is not a hearing");
        assertEquals(0, PhoneFleet.heardAgain(List.of(-1L, -1L)));
        assertTrue(PhoneFleet.WHILE_ASLEEP.contains(PhoneFleet.ASLEEP) && PhoneFleet.WHILE_ASLEEP.contains(PhoneFleet.UNKNOWN));
        assertFalse(PhoneFleet.WHILE_ASLEEP.contains(PhoneFleet.ACTIVE) || PhoneFleet.WHILE_ASLEEP.contains(PhoneFleet.CONNECTED)
                || PhoneFleet.WHILE_ASLEEP.contains(PhoneFleet.STANDBY), "never an awake word while it sleeps");
    }

    @Test
    void onlyARowThatSaysArmedCounts() {
        List<AppLinkLog.Row> rows = List.of("", "0", "1", "false", "true", " 0 ").stream()
                .map(a -> new AppLinkLog.Row(LocalTime.NOON, "READY", 40, 0, 0, "", "", a, 30)).toList();
        assertEquals(2, PhoneFleet.armed(rows));
        assertEquals(0, PhoneFleet.armed(List.of()));
        String check = de.droiddrone.bench.PhoneWords.SLEEP_CHECK;
        assertEquals(3, PhoneFleet.count(check + "\nx\n" + check + " " + check, check));
    }

    /** Only the app's "not known yet" question is one the scenario answers; armed, or armed moments ago, never. */
    @Test
    void onlyTheNotKnownQuestionIsOneToAnswer() {
        String action = "DEEP SLEEP";
        assertEquals(PhoneFleet.Question.NOT_KNOWN, PhoneFleet.question(de.droiddrone.pc.ArmedCheck.NOT_KNOWN.title(action), action));
        assertEquals("DEEP SLEEP - armed?", de.droiddrone.pc.ArmedCheck.NOT_KNOWN.title(action), "the title the first runs met");
        assertEquals(PhoneFleet.Question.ARMED, PhoneFleet.question(de.droiddrone.pc.ArmedCheck.ARMED.title(action), action));
        assertEquals(PhoneFleet.Question.ARMED, PhoneFleet.question(de.droiddrone.pc.ArmedCheck.MAY_FLY.title(action), action));
        assertEquals(PhoneFleet.Question.NONE, PhoneFleet.question(de.droiddrone.pc.ArmedCheck.NOT_KNOWN.title("Standby mode"), action), "another action's");
        assertEquals(PhoneFleet.Question.NONE, PhoneFleet.question("Out of action", action));
        assertEquals(PhoneFleet.Question.NONE, PhoneFleet.question(null, action));
        assertTrue(de.droiddrone.pc.ArmedCheck.NOT_KNOWN.question(action).startsWith("Not known yet if the drone is armed"), "the words the scenario looks for first");
    }

    /** The words the scenario waits for in a FLEET row are the app's (FleetCards.badge). */
    @Test
    void theRowsWordsAreStillTheApps() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        String cards = Files.readString(root.resolve("src/main/java/de/droiddrone/pc/gui/FleetCards.java"), StandardCharsets.UTF_8);
        for (String word : List.of(PhoneFleet.ACTIVE, PhoneFleet.CONNECTED, PhoneFleet.ASLEEP, PhoneFleet.STANDBY, PhoneFleet.UNKNOWN)) {
            assertTrue(cards.contains("new Badge(MapView.Kind.") && cards.contains(", \"" + word + "\")"), word + ": one of FleetCards' badges");
        }
    }
}
