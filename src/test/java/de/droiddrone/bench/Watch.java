package de.droiddrone.bench;

import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What is watched while a scenario runs, whatever the scenario itself checks: an error no code caught (on the Swing
 * thread or any other), the Swing thread held up (the window frozen for the pilot), and how many threads and how much
 * memory the app has at the start and at the end.
 */
public final class Watch implements AutoCloseable {
    public record Problem(long atMs, String kind, String text) {
        @Override
        public String toString() {
            return kind + ": " + text;
        }
    }

    /** The window not answering for longer than this is a freeze the pilot sees. */
    public static final long FREEZE_MS = 500;

    private final List<Problem> problems = new CopyOnWriteArrayList<>();
    private final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
    private final Thread probe;
    private volatile boolean running = true;
    private volatile long maxLagMs;
    private final int threadsAtStart = Thread.activeCount();

    public Watch() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (error instanceof Fence.Refused && error.getMessage() != null && error.getMessage().contains(" exit ")) return; // the fence's own list has it
            StringWriter s = new StringWriter();
            error.printStackTrace(new PrintWriter(s));
            String trace = s.toString();
            problems.add(new Problem(System.currentTimeMillis(), "uncaught error on " + thread.getName(), trace.length() > 3000 ? trace.substring(0, 3000) + " ..." : trace));
            if (before != null) before.uncaughtException(thread, error); // the app's own log gets its line as always
        });
        probe = new Thread(this::probeSwing, "benchSwingProbe");
        probe.setDaemon(true);
        probe.start();
    }

    private void probeSwing() {
        while (running) {
            CountDownLatch ran = new CountDownLatch(1);
            long t0 = System.nanoTime();
            SwingUtilities.invokeLater(ran::countDown);
            try {
                String stack = null;
                if (!ran.await(FREEZE_MS, TimeUnit.MILLISECONDS)) {
                    stack = swingStack(); // what it is doing while it does not answer
                    ran.await(60, TimeUnit.SECONDS);
                }
                long lag = (System.nanoTime() - t0) / 1_000_000;
                if (lag > maxLagMs) maxLagMs = lag;
                // Windows' own file dialog takes 0.3 to 0.6 s to come up (its folders, its pictures, the window itself): not
                // the app's to shorten, and no freeze of a flight - up to 1.5 s of it is let pass (F-030)
                boolean fileDialog = stack != null && (stack.contains("javax.swing.JFileChooser.") || stack.contains("sun.awt.shell.")) && lag < 1500;
                if (stack != null && running && freezesCount && !fileDialog) problems.add(new Problem(System.currentTimeMillis(), "window frozen " + lag + " ms", stack));
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String swingStack() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (!e.getKey().getName().startsWith("AWT-EventQueue")) continue;
            // the top of the stack (what it waits in), then the app's own frames (who asked for it)
            // - and the first frame of Windows' file dialog wherever it stands: the rule above looks for it, and on
            // 2026-11-20 it stood deeper than the sixteen (fonts being drawn for the dialog's buttons), so a file
            // dialog's half second was called a frozen window twice
            StringBuilder s = new StringBuilder();
            int n = 0, own = 0;
            boolean dialogSaid = false;
            for (StackTraceElement f : e.getValue()) {
                boolean app = f.getClassName().startsWith("de.droiddrone.");
                boolean dialog = !dialogSaid && (f.getClassName().startsWith("javax.swing.JFileChooser") || f.getClassName().startsWith("sun.awt.shell."));
                if (n < 16 || app && own < 12 || dialog) s.append("\n    at ").append(f);
                else if (n == 16) s.append("\n    ...");
                if (dialog) dialogSaid = true;
                if (app) own++;
                n++;
            }
            return s.toString();
        }
        return "(no Swing thread)";
    }

    /** Freezes count from here on: before the window shows there is nothing for a pilot to find frozen. */
    public void windowIsUp() {
        maxLagMs = 0;
        freezesCount = true;
    }

    private volatile boolean freezesCount;

    public List<Problem> problems() {
        return List.copyOf(problems);
    }

    /** A problem the scenario itself found, to stand with the others in the film and the report. */
    public void add(String kind, String text) {
        problems.add(new Problem(System.currentTimeMillis(), kind, text));
    }

    /** The longest the Swing thread took to answer, ms. */
    public long maxLagMs() {
        return maxLagMs;
    }

    public int threads() {
        return Thread.activeCount();
    }

    public int threadsAtStart() {
        return threadsAtStart;
    }

    /** Heap in use after asking for a collection, MB. */
    public static long usedMb() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    @Override
    public void close() {
        running = false;
        probe.interrupt();
        Thread.setDefaultUncaughtExceptionHandler(before);
    }
}
