package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet AP-10: log list entries lost on the way are asked for again one by one - the list comes back whole, soon. */
class ArduLogsListTest {
    @Test
    void lostEntriesAreAskedForAgain() throws Exception {
        FakeArduPilot fc = new FakeArduPilot();
        for (int i = 0; i < 5; i++) fc.logs.add(new byte[100 + i]);
        fc.dropEntriesOnce.addAll(List.of(2, 4));
        try (MavSession s = new MavSession(fc)) {
            assertTrue(s.awaitHeartbeat(3000));
            long t0 = System.currentTimeMillis();
            List<ArduLogs.Entry> l = ArduLogs.list(s, 8000);
            long took = System.currentTimeMillis() - t0;
            assertEquals(List.of(1, 2, 3, 4, 5), l.stream().map(ArduLogs.Entry::id).toList(), "whole, in id order");
            assertTrue(fc.listAsks.contains("2-2") && fc.listAsks.contains("4-4"), fc.listAsks.toString());
            assertTrue(took < 4000, "not the whole wait: " + took + " ms");
        } finally {
            fc.close();
        }
    }

    @Test
    void noLogsIsAnEmptyList() throws Exception {
        FakeArduPilot fc = new FakeArduPilot();
        try (MavSession s = new MavSession(fc)) {
            assertTrue(s.awaitHeartbeat(3000));
            assertTrue(ArduLogs.list(s, 3000).isEmpty());
        } finally {
            fc.close();
        }
    }
}
