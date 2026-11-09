package de.droiddrone.pc.fleet;

import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-09 (the pilot: the Overwatch demo "only works once", then ran with no drones, flights or units): the tour's
 * drones are refused while a virtual fleet announces itself. A closed fleet must never be announced again, and the
 * pilot's fleet program can be asked to end.
 */
class FleetAnnouncementTest {
    @TempDir
    Path dir;

    @Test
    void aClosedFleetIsNeverAnnouncedAgainEvenByAHeartbeatInFlight() throws Exception {
        Path file = dir.resolve(VirtualFleet.FILE);
        for (int round = 0; round < 8; round++) {
            Fleet fleet = new Fleet(PcConfig.defaults(), file, s -> { });
            fleet.add(fleet.freeName(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0);
            fleet.startHeartbeat();
            // the heartbeat and other writes racing the close
            Thread writer = new Thread(() -> {
                for (int i = 0; i < 200; i++) fleet.announce();
            });
            writer.start();
            Thread.sleep(round % 3);
            fleet.close();
            writer.join();
            assertFalse(Files.exists(file), "round " + round + ": the file came back after close");
            fleet.announce();
            assertFalse(Files.exists(file), "announce after close writes nothing");
            assertTrue(VirtualFleet.read(file, System.currentTimeMillis()).isEmpty());
        }
    }

    @Test
    void aStopRequestIsTakenOnceAndAnOldOneIsCleared() throws Exception {
        Path stop = dir.resolve(VirtualFleet.STOP_FILE);
        assertFalse(VirtualFleet.stopRequested(stop), "nothing asked");
        VirtualFleet.requestStop(stop);
        assertTrue(VirtualFleet.stopRequested(stop), "asked");
        assertFalse(VirtualFleet.stopRequested(stop), "taken: asked once");
        VirtualFleet.requestStop(stop); // left by an older fleet that never read it
        VirtualFleet.clearStop(stop); // a new fleet starts
        assertFalse(VirtualFleet.stopRequested(stop), "an old request isn't for the new fleet");
    }
}
