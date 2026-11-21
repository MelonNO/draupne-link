package de.droiddrone.bench;

import de.droiddrone.bench.hw.KeeperClient;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The hardware rig's guard in the test's own JVM (R3), beside the flight controller keeper in its process
 * ({@code bench/hw/FcKeeper}): twenty times a second it beats to the keeper - the heartbeat without which no arm window
 * stays open - and looks whether the run may go on flying. It may not when
 * <ul>
 * <li>the keeper is silent (no answer for a second), or has tripped one of its own interlocks;</li>
 * <li>the app's own word on "armed" (its link log's newest row) and the keeper's (the board's own answer) differ for
 * five seconds - one of the two is wrong about a board that may be turning its motors;</li>
 * <li>an error in the app that nobody caught;</li>
 * <li>the radio had to cut a stick request to its throttle ceiling - a scenario asked for more than the rig allows.</li>
 * </ul>
 * Then: the radio's hands off for good ({@link FakeRadio#handsOffForGood}), the keeper told to close its window and
 * open none, and a stop file in the run's folder ({@link #STOP_FILE}) - no hardware scenario after this one starts
 * until somebody has looked and deleted it. The scenario that was running fails with the reason.
 * <p>
 * The keeper holds every rule without this guard (a test JVM that dies stops beating, and that alone closes the
 * window); the guard is what the test's side knows and the keeper cannot see.
 */
public final class HwGuard implements AutoCloseable {
    /** In the run's folder: why the hardware suite was stopped. While it is there no hardware scenario starts. */
    public static final String STOP_FILE = "hardware-stop.txt";

    /** What the guard looks at beside the keeper: the app under test and its radio. */
    public interface Eyes {
        /** The app's own word on armed, from its link log's newest row; null: it has no fresh word (no link, no flight controller heard). */
        Boolean appArmed();

        /** An error of the app's that nobody caught; null: none. */
        String appError();

        /** How many stick requests the radio cut to its throttle ceiling. */
        int clamped();
    }

    private final int keeperPort;
    private final FakeRadio radio;
    private final Eyes eyes;
    private final Path stopFile;
    private final Consumer<String> say;
    /** The guard's pace and patience (a test shortens them): twenty looks a second, a keeper silent for a second, two words on "armed" that differ for five. */
    public long stepMs = 50, silentMs = 1000, disagreeMs = 5000;
    private volatile KeeperClient.State state;
    private volatile String tripped;
    private volatile boolean running;
    private Thread thread;

    /**
     * @param keeperPort the keeper's control port on this PC's loopback
     * @param stopFile   where the reason goes when the run is stopped
     * @param say        the scenario's film
     */
    public HwGuard(int keeperPort, FakeRadio radio, Eyes eyes, Path stopFile, Consumer<String> say) {
        this.keeperPort = keeperPort;
        this.radio = radio;
        this.eyes = eyes;
        this.stopFile = stopFile;
        this.say = say;
    }

    /** Why the hardware suite of this run was stopped (the stop file's first line); null: it was not. */
    public static String stopped(Path runRoot) {
        Path file = runRoot.resolve(STOP_FILE);
        if (!Files.isRegularFile(file)) return null;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? "no reason written" : text.lines().findFirst().orElse("");
        } catch (IOException e) {
            return "its reason could not be read (" + e.getMessage() + ")";
        }
    }

    /** The guard from now on, twenty times a second. A keeper that does not answer at all stops the run within the second. */
    public HwGuard start() {
        running = true;
        thread = new Thread(this::watch, "benchHwGuard");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        return this;
    }

    private void watch() {
        KeeperClient beat = null;
        long lastAnswer = System.nanoTime(), differSince = 0;
        boolean differ = false;
        Boolean app = null;
        String lastFailure = "it does not answer";
        for (long round = 0; running; round++) {
            long now = System.nanoTime();
            KeeperClient.State s = null;
            try {
                if (beat == null) beat = new KeeperClient(keeperPort, (int) Math.max(200, silentMs / 2));
                s = beat.beat();
                state = s;
                lastAnswer = System.nanoTime();
            } catch (IOException e) {
                lastFailure = String.valueOf(e.getMessage());
                if (beat != null) beat.close();
                beat = null;
                if (now - lastAnswer > silentMs * 1_000_000L) {
                    trip("the keeper is silent: no answer on 127.0.0.1:" + keeperPort + " for " + (now - lastAnswer) / 1_000_000 + " ms (" + lastFailure + ")");
                }
            }
            if (s != null) {
                if (s.tripped() != null) trip("the keeper stopped it: " + s.tripped());
                if (s.get("ending").equals("1")) trip("the keeper is ending");
                if (round % 5 == 0) app = eyes.appArmed(); // the link log's newest row: read four times a second
                Boolean board = s.fresh() ? s.armed() : null;
                if (app != null && board != null && !app.equals(board)) {
                    if (!differ) {
                        differ = true;
                        differSince = now;
                    } else if (now - differSince > disagreeMs * 1_000_000L) {
                        trip("the app says the board is " + (app ? "ARMED" : "disarmed") + " and the keeper, from the board's own answer, " + (board ? "ARMED" : "disarmed")
                                + " - for " + (now - differSince) / 1_000_000 + " ms");
                    }
                } else {
                    differ = false;
                }
            }
            int cut = eyes.clamped();
            if (cut > 0) trip("a scenario asked the radio for more throttle than its ceiling of " + radio.ceiling() + ": " + cut + " request(s) cut");
            String error = eyes.appError();
            if (error != null) trip("an error in the app that nobody caught: " + error.lines().findFirst().orElse(error));
            try {
                Thread.sleep(stepMs);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (beat != null) beat.close();
    }

    /** Once: hands off for good, the keeper told, the stop file written. */
    private synchronized void trip(String why) {
        if (tripped != null) return;
        tripped = why;
        radio.handsOffForGood("the hardware guard: " + why);
        String keeper;
        try (KeeperClient k = new KeeperClient(keeperPort, 3000)) {
            keeper = k.ask("TRIP the rig's guard: " + why).startsWith("OK") ? "The keeper has closed its window and opens none." : "The keeper did not take the stop.";
        } catch (IOException e) {
            keeper = "The keeper could not be told (" + e.getMessage() + "): without the rig's beat it closes its window by itself.";
        }
        try {
            Files.createDirectories(stopFile.getParent());
            Files.writeString(stopFile, why + "\n" + LocalDateTime.now().withNano(0) + "\nNo hardware scenario of this run starts while this file is here. "
                    + "Look at the board and the keeper's log; delete the file to go on.\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            keeper += " THE STOP FILE COULD NOT BE WRITTEN (" + e + ").";
        }
        say.accept("HARDWARE GUARD - THE RUN STOPS FLYING: " + why + ". The radio's hands are off for good. " + keeper + " Stop file: " + stopFile);
    }

    /** Why the guard stopped the run; null: it did not. */
    public String tripped() {
        return tripped;
    }

    /** The keeper's state as of the last beat; null before the first answer. */
    public KeeperClient.State keeperState() {
        return state;
    }

    /**
     * The keeper's port for the PC app's Drone setup (its lease on the board; -1 before the keeper has answered). A
     * scenario that opens Drone setup, the motor test or the compass pages plugs it in as the app's USB:
     * {@code rig.usb.plug(KeeperWire.PORT_NAME, KeeperWire.DESCRIPTION, () -> KeeperWire.open(rig.guard.setupPort()))}.
     * Not plugged in by itself: while that lease is held the phone has no flight controller.
     */
    public int setupPort() {
        KeeperClient.State s = state;
        return s == null ? -1 : s.number("setupPort");
    }

    /** A connection of the scenario's own to the keeper's control channel (FAULT, MARK, FIGURES, SCENARIO ...). */
    public KeeperClient keeper() throws IOException {
        return new KeeperClient(keeperPort, 30_000);
    }

    /**
     * An arm window: asked of the keeper first (it says no and why when the board, the battery or the budget does not
     * allow it), and only then the radio's hands on. The scenario moves the switches.
     * @throws IllegalStateException when the keeper opens none, or the guard has stopped the run
     */
    public void openWindow(int seconds) {
        if (tripped != null) throw new IllegalStateException("No arm window: the hardware guard has stopped the run (" + tripped + ")");
        String answer;
        try (KeeperClient k = keeper()) {
            answer = k.ask("OPEN " + seconds);
        } catch (IOException e) {
            throw new IllegalStateException("No arm window: the keeper does not answer (" + e.getMessage() + ")");
        }
        if (!answer.startsWith("OK")) throw new IllegalStateException("No arm window: the keeper says " + answer);
        radio.handsOn();
        say.accept("ARM WINDOW open for " + seconds + " s (the keeper: " + answer + "); the radio's hands are on.");
    }

    /** The radio's hands off first, then the keeper's window closed and its lock set. Returns the keeper's word ("OK safe=1 locked=1 armed=0 ..."). */
    public String closeWindow() {
        radio.handsOff();
        String answer;
        try (KeeperClient k = keeper()) {
            answer = k.ask("CLOSE");
        } catch (IOException e) {
            answer = "NO the keeper does not answer (" + e.getMessage() + ")";
        }
        say.accept("ARM WINDOW closed; the radio's hands are off. The keeper: " + answer);
        return answer;
    }

    /**
     * A hardware scenario's end: the board made safe through the keeper - window closed, locked, STOP, the motors read
     * back - and its own word on it ("OK safe=1 locked=1 armed=0 atRest=1 ..." or "NO ..."). The guard watches no more.
     */
    public String finish() {
        String word;
        try (KeeperClient k = keeper()) {
            word = k.ask("SAFE");
        } catch (IOException e) {
            word = "NO the keeper does not answer (" + e.getMessage() + ")";
        }
        close();
        return word;
    }

    @Override
    public void close() {
        running = false;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ the app's own word

    /**
     * The app's word on "armed" as its newest link log has it now (logs\link-*.csv, column "armed", ten rows a second
     * while a link is up, on the disk within a second): TRUE or FALSE from the last whole row - and null when that row
     * says nothing (the flight controller not heard for 1.5 s), or is older than three seconds by its own clock (no
     * link any more). Read only.
     *
     * @param now this PC's time of day, as the log's first column has it
     */
    public static Boolean linkWord(Path logsDir, LocalTime now) {
        Path newest;
        try (Stream<Path> files = Files.list(logsDir)) {
            newest = files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("link-") && n.endsWith(".csv");
            }).max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null); // link-<date>-<time>.csv: the last by name is the newest
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (newest == null) return null;
        try (RandomAccessFile f = new RandomAccessFile(newest.toFile(), "r")) {
            long length = f.length();
            byte[] head = new byte[(int) Math.min(length, 8192)];
            f.readFully(head);
            String first = new String(head, StandardCharsets.UTF_8);
            int end = first.indexOf('\n');
            if (end < 0) return null;
            int armed = Arrays.asList(first.substring(0, end).trim().split(",", -1)).indexOf("armed"), link = 1;
            if (armed < 0) return null;
            long from = Math.max(end + 1, length - 16 * 1024);
            f.seek(from);
            byte[] tail = new byte[(int) (length - from)];
            f.readFully(tail);
            String text = new String(tail, StandardCharsets.UTF_8);
            int last = text.lastIndexOf('\n'); // what follows is a row being written
            if (last < 0) return null;
            String[] rows = text.substring(0, last).split("\r?\n");
            for (int i = rows.length - 1; i >= Math.max(from == end + 1 ? 0 : 1, rows.length - 40); i--) { // (the tail's first row may be cut in half)
                String[] cells = rows[i].split(",", -1);
                if (cells.length <= armed || cells[link].isBlank()) continue; // an event's row
                long age = Math.abs(Duration.between(LocalTime.parse(cells[0].trim()), now).toMillis());
                if (Math.min(age, 86_400_000L - age) > 3000) return null; // (the smaller way round the clock: midnight)
                String word = cells[armed].trim();
                return word.isEmpty() ? null : !word.equals("0") && !word.equalsIgnoreCase("false");
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
