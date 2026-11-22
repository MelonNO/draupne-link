package de.droiddrone.bench;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words the phone scenarios wait for are the phone app's own ({@link PhoneWords}): each is read here in the app's
 * sources, where the app says it. An app that words a log line or a screen line anew fails this test - and not a
 * night's run on the phone, which would wait for words that no longer come and call it a fault of the phone.
 */
class PhoneRigWordsTest {
    private static Path root() {
        Path root = Path.of("").toAbsolutePath(); // the tests run in build/test-work
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        return root;
    }

    @Test
    void everyWordTheScenariosWaitForIsStillWhatTheAppSays() throws Exception {
        Path flightApp = root().resolve("flight-app");
        List<String> drifted = new ArrayList<>();
        Set<String> words = new HashSet<>();
        for (PhoneWords.Origin o : PhoneWords.ORIGINS) {
            words.add(o.words());
            Path file = flightApp.resolve(o.file());
            if (!Files.isRegularFile(file)) {
                drifted.add("\"" + o.words() + "\": " + o.file() + " is not there any more");
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (String piece : o.inSource()) {
                if (!source.contains(piece)) drifted.add("\"" + o.words() + "\": " + o.file() + " no longer has   " + piece);
            }
        }
        assertEquals(List.of(), drifted, "words of the phone app that the bench waits for and the app no longer says so (PhoneWords and the scenarios have to follow the app)");
        assertTrue(words.size() >= 20, "the list is the whole list: " + words.size());
        // each of the app's own is on the list (Android's own three have no place in the app's sources)
        for (String own : List.of(PhoneWords.SCREEN_TITLE, PhoneWords.SCREEN_CONNECTED, PhoneWords.SCREEN_NO_FC, PhoneWords.RECEIVER_STARTED, PhoneWords.LINK_PORT_TAKEN,
                PhoneWords.STANDBY, PhoneWords.DEEP_SLEEP, PhoneWords.SLEEP_LOCKED_SCREEN, PhoneWords.SLEEP_CHECK, PhoneWords.OPENED_OVER_LOCK, PhoneWords.FATAL,
                PhoneWords.OPENING_AGAIN, PhoneWords.NOT_OPENED_AGAIN, PhoneWords.LAST_ENDED_BY, PhoneWords.NEW_SESSION, PhoneWords.NEW_SESSION_SAME_PC, PhoneWords.LINK_SOCKET,
                PhoneWords.CAMERA_CLOSED_MEANWHILE, PhoneWords.CAMERA_CONFIGURED, PhoneWords.CAMERA_CLOSED, PhoneWords.ENCODER_STARTED, PhoneWords.STATS_PC_CONNECTED,
                PhoneWords.STATS_SERVICE_RUNNING, PhoneWords.STATS_SERIAL)) {
            assertTrue(words.contains(own), "\"" + own + "\" has its place in the app's sources written down");
        }
    }

    /** The other half of "one place": no phone scenario spells one of the app's lines out for itself any more. */
    @Test
    void theScenariosTakeTheAppsWordsFromTheOnePlace() throws Exception {
        Path bench = root().resolve("src/test/java/de/droiddrone/bench");
        List<String> own = new ArrayList<>();
        List<Path> scenarios;
        try (Stream<Path> files = Files.walk(bench)) {
            scenarios = files.filter(p -> p.getFileName().toString().matches("Phone\\w*Scenario\\.java")).sorted().toList();
        }
        assertTrue(scenarios.size() >= 7, scenarios.toString());
        Pattern literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        for (Path f : scenarios) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String code = lines.get(i).strip();
                if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")) continue;
                int remark = code.indexOf("; //"); // what is said after a statement, in words, is no code
                Matcher m = literal.matcher(remark < 0 ? code : code.substring(0, remark));
                while (m.find()) {
                    for (PhoneWords.Origin o : PhoneWords.ORIGINS) {
                        if (m.group(1).equals(o.words())) own.add(f.getFileName() + ":" + (i + 1) + ": \"" + o.words() + "\"  <- PhoneWords has it");
                    }
                }
            }
        }
        assertEquals(List.of(), own, "a phone scenario with a line of the app's of its own");
    }
}
