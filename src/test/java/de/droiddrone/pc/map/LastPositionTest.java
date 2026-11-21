package de.droiddrone.pc.map;

import de.droiddrone.pc.FcTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the lost drone was last (a reader of the code, 2026-11-20): what FIND DRONE, the copied MGRS and
 * last-position.txt give the pilot to search by.
 */
class LastPositionTest {
    private static FcTelemetry.Gps fix(double lat, double lon, long at) {
        return new FcTelemetry.Gps(3, 12, 40, 100, 0, 0, lat, lon, 0, 0, 1.0, at);
    }

    /** The phone's own fix, ageS old when it was sent at receivedAt. */
    private static FcTelemetry.PhonePosition phone(double lat, double lon, int accuracy, int ageS, long receivedAt) {
        return new FcTelemetry.PhonePosition(lat, lon, 100, accuracy, 0, 0, ageS, receivedAt);
    }

    /**
     * The drone's GPS gives its last fix and the drone comes down; five seconds later the phone's own fix fills in -
     * one 25 s old. It was taken as the last position: half a minute back along the way.
     */
    @Test
    void anOlderPhoneFixDoesNotBecomeTheLastPosition() throws Exception {
        FlightTrack track = new FlightTrack(null);
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) track.sample(fix(59.90 + i * 0.001, 10.70, t + i * 1000L), null, true, false);
        FlightTrack.Point lastDrone = track.last();
        Thread.sleep(FlightTrack.FALLBACK_MS + 200); // the drone's GPS silent long enough for the phone's to fill in (the track's own clock)
        long later = t + 9000 + FlightTrack.FALLBACK_MS + 1000;
        track.sample(null, phone(59.85, 10.70, 8, 25, later), true, false);
        assertEquals(lastDrone, track.last(), "a phone fix older than the drone's last one is no new last position");
        track.sample(null, phone(59.912, 10.70, 8, 1, later + 1000), true, false);
        assertTrue(track.last().phone(), "a newer one is, as before (the drone's GPS gone, the phone's fills in)");
        assertEquals(59.912, track.last().lat(), 1e-9);
    }

    /**
     * Drone A lost in the air; drone B connected on the bench afterwards and never armed. B's first fix and its close
     * wrote last-position.txt over A's - the lost drone's place gone from the file meant for finding it.
     */
    @Test
    void aConnectionThatNeverArmedDoesNotWriteOverTheLostDronesPosition(@TempDir Path dir) throws Exception {
        FlightTrack a = new FlightTrack(dir);
        long t = System.currentTimeMillis() - 60_000;
        for (int i = 0; i < 5; i++) a.sample(fix(59.95, 10.80 + i * 0.001, t + i * 1000L), true);
        a.close();
        Path file = dir.resolve("last-position.txt");
        String lost = Files.readString(file);
        assertTrue(lost.contains("59.95"), lost);

        FlightTrack b = new FlightTrack(dir);
        for (int i = 0; i < 5; i++) b.sample(fix(60.40, 11.20, t + 30_000 + i * 1000L), false);
        b.close();
        assertEquals(lost, Files.readString(file), "the lost drone's position is still the one in the file");
    }
}
