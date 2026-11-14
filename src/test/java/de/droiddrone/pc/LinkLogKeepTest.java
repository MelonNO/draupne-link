package de.droiddrone.pc;

import de.droiddrone.pc.flights.FlightData;
import de.droiddrone.pc.flights.FlightFiles;
import de.droiddrone.pc.flights.FlightLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which link logs stay: a kept flight's as long as the flight, the others the newest {@link LinkLog#KEEP} - and when
 * that is sorted out: as a connection's log is closed, never as it is opened. The older logs here are made-up names
 * from 2001, so the one LinkLog opens now is the newest by its name.
 */
class LinkLogKeepTest {
    @TempDir
    Path root;

    private Path dir(String name) throws IOException {
        return Files.createDirectories(root.resolve(name));
    }

    /** The log of a connection that kept nothing: on day d of January 2001, at second n. */
    private Path plain(int day, int n) throws IOException {
        Path f = dir("logs").resolve(String.format("link-200101%02d-0000%02d.csv", day, n));
        Files.writeString(f, FlightFiles.LINK_HEADER + "\n");
        return f;
    }

    private Set<String> logs() throws IOException {
        try (var s = Files.list(dir("logs"))) {
            return s.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(java.util.TreeSet::new));
        }
    }

    /**
     * Opens a new log as CONNECT does and closes it as DISCONNECT does - the older ones are sorted out then, not
     * before - and waits until they are. Returns its name.
     */
    private String connect(Predicate<Path> kept) throws Exception {
        LinkLog log = LinkLog.open(dir("logs"), kept);
        log.close();
        log.awaitSorted();
        return log.file().getFileName().toString();
    }

    private Predicate<Path> keptFlights() throws IOException {
        Path tracks = dir("Tracks"), videos = dir("Videos");
        return FlightLibrary.keptLinkLog(() -> tracks, () -> videos);
    }

    @Test
    void aKeptFlightsLinkLogOutlivesTwentyNewerOnes() throws Exception {
        // a flight of 2 January 2001 with its track, and its link log from 5 s before the first position
        FlightFiles.gpx(dir("Tracks"), "2001-01-02T10:00:00", 30, s -> new FlightFiles.Pt(59.91 + s * 0.0001, 10.73, 100, s > 0));
        Path flown = FlightFiles.linkLog(dir("logs"), "2001-01-02T09:59:55", 40, "MobileAlpha");
        Path before = plain(1, 0); // a connection the day before that kept nothing
        for (int n = 0; n < 21; n++) plain(3, n); // and 21 the day after
        assertEquals(23, logs().size());

        String now = connect(keptFlights());
        Set<String> left = logs();
        assertTrue(left.contains(flown.getFileName().toString()), "the kept flight's link log is still there: " + left);
        assertFalse(left.contains(before.getFileName().toString()), "the older log of a connection that kept nothing went");
        assertFalse(left.contains("link-20010103-000000.csv") || left.contains("link-20010103-000001.csv"), "and the oldest two of the 21: " + left);
        assertTrue(left.contains("link-20010103-000002.csv") && left.contains(now));
        assertEquals(LinkLog.KEEP + 1, left.size(), "the newest " + LinkLog.KEEP + " of the others (the new one among them), and the flight's on top");

        // PREVIOUS FLIGHTS still has the flight's link
        List<FlightLibrary.Flight> flights = FlightLibrary.scan(dir("Tracks"), dir("Videos"), dir("logs"));
        assertEquals(1, flights.size());
        assertEquals(flown, flights.get(0).linkLog());
        assertEquals("MobileAlpha", flights.get(0).drone());
        assertFalse(FlightData.load(flights.get(0)).link.isEmpty());

        // DELETE takes the log with the flight; had it stayed behind, it would be a log like the others
        assertTrue(FlightData.delete(flights.get(0)).isEmpty());
        assertFalse(logs().contains(flown.getFileName().toString()));
    }

    @Test
    void aLogWhoseFlightIsGoneGoesByTheRuleOfTwenty() throws Exception {
        Path track = FlightFiles.gpx(dir("Tracks"), "2001-01-02T10:00:00", 30, s -> new FlightFiles.Pt(59.91, 10.73, 100, s > 0));
        Path flown = FlightFiles.linkLog(dir("logs"), "2001-01-02T09:59:55", 40, "MobileAlpha");
        for (int n = 0; n < 19; n++) plain(3, n);
        connect(keptFlights());
        assertEquals(21, logs().size(), "19 and the new one are 20; the flight's isn't counted");

        Files.delete(track); // the track deleted in Explorer: no flight any more
        forgetTheLogJustMade();
        connect(keptFlights());
        assertFalse(logs().contains(flown.getFileName().toString()), "the 21st of the logs that are nobody's: " + logs());
        assertEquals(LinkLog.KEEP, logs().size());
    }

    /** The log a connect() made goes, so the next one's count doesn't hang on whether both fell in the same second (one name) or not. */
    private void forgetTheLogJustMade() throws IOException {
        for (String name : logs()) if (!name.startsWith("link-2001")) Files.delete(dir("logs").resolve(name));
    }

    @Test
    void connectionsThatKeptNothingGoByTheRuleAsBefore() throws Exception {
        for (int n = 0; n < 25; n++) plain(1, n);
        String now = connect(keptFlights());
        Set<String> left = logs();
        assertEquals(LinkLog.KEEP, left.size(), "only the newest logs are kept");
        assertTrue(left.contains(now) && left.contains("link-20010101-000024.csv") && left.contains("link-20010101-000006.csv"));
        assertFalse(left.contains("link-20010101-000005.csv"));

        // and opened without anyone to ask
        forgetTheLogJustMade();
        for (int n = 0; n < 25; n++) plain(2, n);
        try (LinkLog log = LinkLog.open(dir("logs"))) {
            assertNotNull(log.file());
            assertEquals(LinkLog.KEEP, logs().size(), "sorted out at once, on the caller's thread, as it always was");
        }
        assertEquals(LinkLog.KEEP, logs().size());
    }

    @Test
    void theOlderLogsAreSortedOutWhenTheLogIsClosedNotWhenItIsOpened() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        Predicate<Path> counting = f -> {
            asked.incrementAndGet();
            return false;
        };
        for (int n = 0; n < 25; n++) plain(1, n);
        LinkLog log = LinkLog.open(dir("logs"), counting);
        log.awaitSorted(); // nothing to wait for: opening starts no thread
        log.event("Connecting to MobileAlpha (192.0.2.7:8888)");
        assertEquals(0, asked.get(), "nobody is asked while the connection is up");
        assertEquals(26, logs().size(), "and nothing has gone: " + logs());

        log.close();
        log.awaitSorted();
        assertEquals(25, asked.get(), "asked once the log is closed: each older log once");
        assertEquals(LinkLog.KEEP, logs().size(), "the newest logs are kept, the closed one among them");
        assertTrue(logs().contains(log.file().getFileName().toString()));
        assertTrue(Files.readString(log.file()).contains("Connecting to MobileAlpha"), "the log itself was closed whole first");

        for (int n = 0; n < 5; n++) plain(2, n); // 25 again, and a second close: nothing more happens
        log.close();
        log.awaitSorted();
        assertEquals(25, asked.get(), "sorted out once, however often it is closed");
        assertEquals(LinkLog.KEEP + 5, logs().size());
    }

    @Test
    void theFlightsAreNotReadWhileThereAreFewLogs() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        Predicate<Path> counting = f -> {
            asked.incrementAndGet();
            return false;
        };
        for (int n = 0; n < LinkLog.KEEP - 1; n++) plain(1, n);
        connect(counting);
        assertEquals(0, asked.get(), "19 and the new one: nothing can go, nobody is asked");
        assertEquals(LinkLog.KEEP, logs().size());
    }

    @Test
    void keptLogsTakeNoPlaceAmongTheTwenty() throws Exception {
        for (int n = 0; n < 30; n++) plain(1, n);
        Set<String> kept = Set.of("link-20010101-000000.csv", "link-20010101-000003.csv", "link-20010101-000028.csv");
        LinkLog.sortOut(dir("logs"), null, f -> kept.contains(f.getFileName().toString()));
        Set<String> left = logs();
        assertTrue(left.containsAll(kept));
        assertEquals(LinkLog.KEEP - 1 + 3, left.size(), "room for the new one, and the three kept");
        assertTrue(left.contains("link-20010101-000010.csv"), "29, 27 .. 10 are the 19 newest of the others: " + left);
        assertFalse(left.contains("link-20010101-000009.csv"));
    }

    @Test
    void whenTheFlightsCannotBeReadNothingIsDeleted() throws Exception {
        for (int n = 0; n < 25; n++) plain(1, n);
        String now = connect(f -> {
            throw new IllegalStateException("the flights can't be read");
        });
        assertEquals(26, logs().size(), "better a log too many than a kept flight's gone");
        assertTrue(logs().contains(now), "and the connection has its log");

        // the tracks' folder isn't there (Documents on a share that is away): its flights can't be seen, so no log goes
        forgetTheLogJustMade();
        Path videos = dir("Videos"), away = root.resolve("away").resolve("Tracks");
        connect(FlightLibrary.keptLinkLog(() -> away, () -> videos));
        assertEquals(26, logs().size());
        forgetTheLogJustMade();
        connect(FlightLibrary.keptLinkLog(() -> null, () -> videos));
        assertEquals(26, logs().size());
    }

    @Test
    void onlyALogWhoseConnectionKeptATrackOrARecordingIsAKeptFlights() throws Exception {
        FlightFiles.gpx(dir("Tracks"), "2001-01-02T10:00:00", 30, s -> new FlightFiles.Pt(59.91, 10.73, 100, s > 0));
        Path withTrack = FlightFiles.linkLog(dir("logs"), "2001-01-02T09:59:55", 40, "MobileAlpha");
        FlightFiles.recording(dir("Videos"), "2001-01-02T12:00:00", 20, i -> new FlightFiles.Row(24, 4.0, Double.NaN, Double.NaN, -1, 5, true, 40));
        Path withRecording = FlightFiles.linkLog(dir("logs"), "2001-01-02T11:59:50", 35, "MobileBravo");
        Path alone = FlightFiles.linkLog(dir("logs"), "2001-01-02T15:00:00", 30, "MobileCharlie");
        assertEquals(Set.of(withTrack.toAbsolutePath().normalize(), withRecording.toAbsolutePath().normalize()),
                FlightLibrary.keptLinkLogs(dir("Tracks"), dir("Videos"), dir("logs")));
        Predicate<Path> kept = keptFlights();
        assertTrue(kept.test(withTrack));
        assertTrue(kept.test(withRecording));
        assertFalse(kept.test(alone));
        assertFalse(kept.test(dir("logs").resolve("link-20010109-000000.csv")), "a log that isn't there");
    }
}
