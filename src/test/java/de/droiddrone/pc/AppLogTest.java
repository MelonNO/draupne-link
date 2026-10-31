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

    @Test
    void allStacksNamesTheThreads() {
        String s = AppLog.allStacks();
        assertTrue(s.contains("\"" + Thread.currentThread().getName() + "\""));
    }
}
