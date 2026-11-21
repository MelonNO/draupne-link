package de.droiddrone.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pilot, 2026-11-19: the bench may use this PC "as long as no real drones are armed". The guard reads the real
 * app's link log; these are made-up logs in the shape LinkLog writes.
 */
class ArmedGuardTest {
    private static final String HEADER = "time,link,ping_ms,rc_frames,rc_max_gap_ms,telemetry_packets,telemetry_max_gap_ms,fc_data_age_ms,rxloss,failsafe,armed,blockers,radio";

    private static String row(String armed) {
        return "12:00:00,1,40,250,5,20,60,30,0,0," + armed + ",,1";
    }

    @Test
    void theLastWordOfTheFlightControllerDecides() {
        assertFalse(ArmedGuard.armedInTail(HEADER, List.of()), "no rows: nothing connected long enough to say");
        assertFalse(ArmedGuard.armedInTail(HEADER, List.of(row("0"), row("0"))));
        assertTrue(ArmedGuard.armedInTail(HEADER, List.of(row("0"), row("1"))));
        assertFalse(ArmedGuard.armedInTail(HEADER, List.of(row("1"), row("0"))), "disarmed again");
        assertTrue(ArmedGuard.armedInTail(HEADER, List.of(row("1"), row(""), row(""))), "armed, then not heard: still armed for the bench");
        assertFalse(ArmedGuard.armedInTail(HEADER, List.of(row(""), row(""))), "a phone with no flight controller: never armed");
        assertFalse(ArmedGuard.armedInTail(HEADER, List.of(row("0"), row(""), "12:00:01,1,4")), "a row being written is skipped");
        assertTrue(ArmedGuard.armedInTail("time,link,something_else", List.of("12:00:00,1,5")), "a log it can't read: the careful answer");
    }

    @Test
    void onlyALogBeingWrittenIsALink(@TempDir Path logs) throws Exception {
        long now = System.currentTimeMillis();
        assertEquals(ArmedGuard.State.NO_LINK, ArmedGuard.read(logs, now), "no logs at all");
        assertEquals(ArmedGuard.State.NO_LINK, ArmedGuard.read(logs.resolve("missing"), now), "no folder");

        Path old = logs.resolve("link-20261117-075700.csv");
        Files.writeString(old, HEADER + "\r\n" + row("1") + "\r\n");
        Files.setLastModifiedTime(old, FileTime.fromMillis(now - 60_000));
        assertEquals(ArmedGuard.State.NO_LINK, ArmedGuard.read(logs, now), "a flight that ended armed a minute ago is not a link now");

        Path live = logs.resolve("link-20261118-144508.csv");
        Files.writeString(live, HEADER + "\r\n" + row("0") + "\r\n" + row("0") + "\r\n");
        Files.setLastModifiedTime(live, FileTime.fromMillis(now - 500));
        assertEquals(ArmedGuard.State.LINK_DISARMED, ArmedGuard.read(logs, now));

        Files.writeString(live, HEADER + "\r\n" + row("0") + "\r\n" + row("1") + "\r\n" + row("") + "\r\n");
        Files.setLastModifiedTime(live, FileTime.fromMillis(now - 500));
        assertEquals(ArmedGuard.State.ARMED, ArmedGuard.read(logs, now));

        // other files in the folder are not link logs
        Files.writeString(logs.resolve("app-2026-11-19.log"), "armed");
        assertEquals(ArmedGuard.State.ARMED, ArmedGuard.read(logs, now));
        assertEquals(ArmedGuard.State.NO_LINK, ArmedGuard.read(logs, now + 2 * ArmedGuard.LIVE_MS), "and it ends when the log stops");
    }

    @Test
    void aLongLogIsReadFromItsEnd(@TempDir Path logs) throws Exception {
        StringBuilder s = new StringBuilder(HEADER).append("\r\n");
        for (int i = 0; i < 20_000; i++) s.append(row("1")).append("\r\n"); // armed for hours, long ago in this file
        for (int i = 0; i < 2000; i++) s.append(row("0")).append("\r\n");
        Path live = logs.resolve("link-20261119-080000.csv");
        Files.writeString(live, s);
        assertTrue(Files.size(live) > 4L * ArmedGuard.TAIL_BYTES);
        assertEquals(ArmedGuard.State.LINK_DISARMED, ArmedGuard.read(logs, System.currentTimeMillis()));
    }
}
