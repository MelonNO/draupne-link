package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-070c (a reader of the code, 2026-11-22): errors when writing to the flight controller's USB were only logged -
 * only a failed read opened the port anew. A port whose reads stand still while every write fails stayed as it was,
 * and no stick reached the flight controller again.
 */
class WriteRunTest {
    /** A stick write gives up after 50 ms, a request after 20 ms (Serial). */
    private static final long RC_TIMEOUT_MS = 50, REQUEST_TIMEOUT_MS = 20;

    /** Failures every gapMs from t0 while the device is still on USB; when the port counts as lost, or -1. */
    private static long lostAt(WriteRun run, long t0, long gapMs, long forMs) {
        for (long t = t0; t <= t0 + forMs; t += gapMs) if (run.failed(t, () -> true)) return t;
        return -1;
    }

    @Test
    void everyStickWriteTimingOutLosesThePortAfter300msNotBefore() {
        WriteRun run = new WriteRun();
        // the first one gives up at 1000, then one every 50 ms: 1300 is the seventh in a row
        assertEquals(1300, lostAt(run, 1000, RC_TIMEOUT_MS, 5000));
        assertEquals(300, WriteRun.RUN_MS, "Betaflight holds the last sticks that long: until then a stall may still end by itself");
        assertTrue(WriteRun.RUN_MS + 1000 < 1500, "and a second is left to open the port anew before Betaflight's stage 2 (1.5 s)");
    }

    @Test
    void requestsTimingOutWithNoSticksGoingLoseItTheSame() {
        assertEquals(5300, lostAt(new WriteRun(), 5000, REQUEST_TIMEOUT_MS, 5000));
    }

    @Test
    void errorsThatComeBackAtOnceNeedThe300msToo() {
        // a dead endpoint answers a write at once: 250 stick writes a second, each an error
        WriteRun run = new WriteRun();
        assertEquals(-1, lostAt(run, 1000, 4, 296), "four in a row are 16 ms: a burst of failed transfers is not a lost port");
        assertTrue(run.failed(1300, () -> true), "300 ms of nothing but errors is");
    }

    @Test
    void oneWriteThatGoesOutStartsTheCountOver() {
        WriteRun run = new WriteRun();
        AtomicInteger asked = new AtomicInteger();
        // a busy flight controller: every other stick write late, for a minute
        for (long t = 0; t < 60_000; t += 100) {
            assertFalse(run.failed(t, () -> {
                asked.incrementAndGet();
                return true;
            }), "at " + t);
            run.wentOut();
        }
        assertEquals(0, asked.get(), "a single late write asks Android nothing");
        // 250 ms of timeouts, one write out, 250 ms more: never 300 ms in a row
        assertEquals(-1, lostAt(run, 100_000, RC_TIMEOUT_MS, 250));
        run.wentOut();
        assertEquals(-1, lostAt(run, 100_300, RC_TIMEOUT_MS, 250));
    }

    @Test
    void aDeviceGoneFromUsbIsLostAtTheSecondFailureInARow() {
        WriteRun run = new WriteRun();
        AtomicInteger asked = new AtomicInteger();
        assertFalse(run.failed(1000, () -> {
            asked.incrementAndGet();
            return false;
        }), "one failure: not asked yet");
        assertEquals(0, asked.get());
        assertTrue(run.failed(1004, () -> false), "the next stick frame's write fails too, and the device is gone: lost now, as a failed read finds it");
    }

    @Test
    void androidIsAskedForTheDeviceAtMostEvery100ms() {
        WriteRun run = new WriteRun();
        AtomicInteger asked = new AtomicInteger();
        for (long t = 1000; t < 1296; t += 4) {
            assertFalse(run.failed(t, () -> {
                asked.incrementAndGet();
                return true;
            }));
        }
        assertEquals(3, asked.get(), "at the second failure (1004), then at 1104 and 1204");
    }

    @Test
    void fourFailuresAreNeededHoweverLongTheyTake() {
        // a quiet port: a request a second, each failing
        WriteRun run = new WriteRun();
        assertFalse(run.failed(0, () -> true));
        assertFalse(run.failed(1000, () -> true));
        assertFalse(run.failed(2000, () -> true), "three: not yet");
        assertTrue(run.failed(3000, () -> true));
        assertEquals(4, WriteRun.FAILURES, "as many as failed reads (the reader's: more than 3)");
    }

    /** The phone app cannot be run here: its sources are read, as PacketIdsTest and ReleaseHasNoBenchCodeTest do. */
    @Test
    void theWriterCountsItsWritesAndLosesTheDeviceAsAFailedReadDoes() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        String serial = new String(Files.readAllBytes(root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight/Serial.java")), StandardCharsets.UTF_8);
        int at = serial.indexOf("private void write(byte[] data, int timeoutMs, boolean isRc, WriteRun run, int id){");
        assertTrue(at >= 0, "Serial.write knows its writer's run");
        String write = serial.substring(at, serial.indexOf("public void writeDataMavlink("));
        assertTrue(write.contains("if (written) run.wentOut();"), "a write that went out starts the count over");
        assertTrue(write.contains("run.failed(") && write.contains("lostDevice(id, "), "a run of failures is a lost device");
        // each writer its own count: one of a port closed since must not add to the new port's
        String loop = serial.substring(serial.indexOf("private void writerLoop(final int id) {"), at);
        assertTrue(loop.contains("final WriteRun run = new WriteRun();"));
        assertEquals(4, loop.split(", run, id\\);", -1).length - 1, "every write of the loop");
    }
}
