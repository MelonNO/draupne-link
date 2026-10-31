package de.droiddrone.pc;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The app's own log, for finding out afterwards why it crashed or froze: %APPDATA%\DraupneLink\logs\app-DATE.log.
 * Everything printed to System.out/err (the link's errors included), the main window's log lines, uncaught exceptions
 * with their stack traces, and - when the window stops responding - the stacks of all threads.
 * Lines go to the file from a thread of their own through a queue: a caller never waits for the disk, and when the
 * queue is full a line is dropped rather than held up (the link's receive and RC threads print errors too).
 */
public final class AppLog {
    /** The window counts as frozen when it hasn't answered for this long. */
    static final long HANG_MS = 5000;
    private static final int KEEP_FILES = 14;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final BlockingQueue<String> queue = new ArrayBlockingQueue<>(4096);
    private static volatile Path file;

    private AppLog() {
    }

    /** Starts the log (GUI only): System.out/err are copied into it, and the freeze watch begins. */
    public static void start(Path dir) {
        try {
            Files.createDirectories(dir);
            prune(dir);
            file = dir.resolve("app-" + LocalDate.now() + ".log");
        } catch (IOException e) {
            return; // no log, the app runs as before
        }
        Thread writer = new Thread(AppLog::writeLoop, "appLog");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
        System.setOut(new PrintStream(new Tee(System.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(System.err), true, StandardCharsets.UTF_8));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> line("UNCAUGHT in thread " + t.getName() + ": " + stack(e)));
        line("=== DRAUPNE Link " + AppInfo.version() + " started, Java " + System.getProperty("java.version")
                + ", " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        Thread watch = new Thread(AppLog::watchWindow, "appLogHangWatch");
        watch.setDaemon(true);
        watch.start();
    }

    /** The log file, or null when there is none. */
    public static Path file() {
        return file;
    }

    /** One line, time-stamped. Never blocks. */
    public static void line(String text) {
        if (file == null) return;
        queue.offer(LocalDateTime.now().format(TIME) + "  " + text);
    }

    static String stack(Throwable e) {
        java.io.StringWriter s = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(s));
        return s.toString().stripTrailing();
    }

    private static void writeLoop() {
        while (true) {
            try {
                String first = queue.take();
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(first);
                    w.write(System.lineSeparator());
                    String next;
                    while ((next = queue.poll(200, TimeUnit.MILLISECONDS)) != null) {
                        w.write(next);
                        w.write(System.lineSeparator());
                    }
                } catch (IOException ignored) {
                    // a full or locked disk: lose these lines, keep going
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Asks the Swing thread for an answer every second. No answer for HANG_MS: the window is frozen - writes every
     * thread's stack once (the Swing thread's shows where it is stuck) and again only after it has recovered.
     */
    private static void watchWindow() {
        long[] answered = {System.currentTimeMillis()};
        boolean reported = false;
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            SwingUtilities.invokeLater(() -> answered[0] = System.currentTimeMillis());
            long silent = System.currentTimeMillis() - answered[0];
            if (silent >= HANG_MS && !reported) {
                reported = true;
                line("WINDOW NOT RESPONDING for " + silent + " ms - all threads:\n" + allStacks());
            } else if (silent < HANG_MS && reported) {
                reported = false;
                line("Window responding again.");
            }
        }
    }

    static String allStacks() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            sb.append('"').append(t.getName()).append("\" ").append(t.getState()).append(t.isDaemon() ? " daemon" : "").append('\n');
            for (StackTraceElement el : e.getValue()) sb.append("    at ").append(el).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static void prune(Path dir) throws IOException {
        List<Path> logs;
        try (Stream<Path> s = Files.list(dir)) {
            logs = s.filter(p -> p.getFileName().toString().startsWith("app-")).sorted(Comparator.reverseOrder()).toList();
        }
        for (int i = KEEP_FILES; i < logs.size(); i++) Files.deleteIfExists(logs.get(i));
    }

    /** Passes everything on to the original stream and sends whole lines to the log. */
    private static final class Tee extends OutputStream {
        private final PrintStream original;
        private final StringBuilder pending = new StringBuilder();

        Tee(PrintStream original) {
            this.original = original;
        }

        @Override
        public synchronized void write(int b) {
            original.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                pending.append((char) b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            original.write(b, off, len);
            String s = new String(b, off, len, StandardCharsets.UTF_8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\n') {
                    flushLine();
                } else if (c != '\r') {
                    pending.append(c);
                }
            }
        }

        private void flushLine() {
            line(pending.toString());
            pending.setLength(0);
        }
    }
}
