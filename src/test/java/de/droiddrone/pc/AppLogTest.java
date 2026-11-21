package de.droiddrone.pc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppLogTest {
    @Test
    void printedLinesAndUncaughtExceptionsReachTheFile(@TempDir Path dir) throws Exception {
        AppLog.start(dir);
        System.out.println("hello from the link");
        Thread t = new Thread(() -> {
            throw new IllegalStateException("boom in a worker");
        }, "worker");
        t.start();
        t.join();
        Path file = AppLog.file();
        assertNotNull(file);
        long end = System.currentTimeMillis() + 3000;
        String text = "";
        while (System.currentTimeMillis() < end) {
            text = Files.exists(file) ? Files.readString(file) : "";
            if (text.contains("boom in a worker")) break;
            Thread.sleep(50);
        }
        assertTrue(text.contains("started"), text);
        assertTrue(text.contains("hello from the link"), text);
        assertTrue(text.contains("UNCAUGHT in thread worker: java.lang.IllegalStateException: boom in a worker"), text);
        assertTrue(text.contains("at de.droiddrone.pc.AppLogTest"), "with the stack trace: " + text);
    }

    /** F-025: a line repeated without end is written twenty times in five seconds, the rest counted. */
    @Test
    void aLineRepeatedWithoutEndIsCountedNotWritten() {
        AppLog.Flood flood = new AppLog.Flood();
        int written = 0;
        for (int i = 0; i < 100_000; i++) written += flood.take("Session thread error: X", 1000 + i / 100).size(); // one second of it
        assertEquals(AppLog.FLOOD_LINES, written);
        assertEquals(java.util.List.of("(the line above " + (100_000 - AppLog.FLOOD_LINES) + " more times)", "another line"),
                flood.take("another line", 2100), "the count, when another line comes");
        // a stretch later the same line is written afresh
        assertEquals(java.util.List.of("another line"), flood.take("another line", 2200));
        for (int i = 0; i < 30; i++) flood.take("again", 3000);
        assertEquals(java.util.List.of("(the line above 10 more times)", "again"), flood.take("again", 3000 + AppLog.FLOOD_MS));
        // a normal log: lines that differ, and one a few times, pass as they are
        AppLog.Flood calm = new AppLog.Flood();
        for (int i = 0; i < 50; i++) assertEquals(java.util.List.of("line " + i % 2), calm.take("line " + i % 2, 5000 + i));
        for (int i = 0; i < AppLog.FLOOD_LINES; i++) assertEquals(java.util.List.of("Still: no picture."), calm.take("Still: no picture.", 6000 + i));
    }

    @Test
    void allStacksNamesTheThreads() {
        String s = AppLog.allStacks();
        assertTrue(s.contains("\"" + Thread.currentThread().getName() + "\""));
    }
}
