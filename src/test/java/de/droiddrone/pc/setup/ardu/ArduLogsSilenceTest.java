package de.droiddrone.pc.setup.ardu;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet AP-10: a drone that stops answering mid-log is given up after a silence, not after the download's 30 minutes. */
class ArduLogsSilenceTest {
    @Test
    void aSilentDroneIsGivenUp() throws Exception {
        FakeArduPilot fc = new FakeArduPilot();
        fc.logs.add(new byte[5000]);
        fc.silentLogData = true;
        try (MavSession s = new MavSession(fc)) {
            assertTrue(s.awaitHeartbeat(3000));
            ArduLogs.Entry e = ArduLogs.list(s, 3000).get(0);
            long t0 = System.currentTimeMillis();
            IOException x = assertThrows(IOException.class, () -> ArduLogs.download(s, e, null, 30 * 60_000L, 2000));
            long took = System.currentTimeMillis() - t0;
            assertTrue(took < 6000, "given up after the silence, not the half hour: " + took + " ms");
            assertTrue(x.getMessage().startsWith("no answer from the drone"), x.getMessage());
        } finally {
            fc.close();
        }
    }
}
