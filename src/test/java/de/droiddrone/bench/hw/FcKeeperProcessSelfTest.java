package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keeper as the process it is ({@link FcKeeper#main}), started as gradlew fcKeeper starts it - but on the made-up
 * board, which stays in this test's JVM behind a TCP port ({@link FakeBoardTcp}) so that it can be looked at when the
 * keeper's process is gone: a second keeper is refused, END's exit code is the keeper's word on the board, and a
 * keeper that is killed outright while an arm window is open leaves a board that {@code gradlew boardSafe} - what
 * {@code bench.ps1 hw} ends with - locks and stops.
 * <p>
 * What the made-up board does not model: Betaflight's own failsafe. A real board whose sticks stop (the keeper dead,
 * the cable out) disarms by itself after 1.5 s; the made-up one stays armed until it is told otherwise. So the test
 * of the kill proves the last of the three lines - boardSafe's lock - on a board that the first two never helped.
 */
@Tag("benchself")
class FcKeeperProcessSelfTest {
    private static final int[] AT_REST = {1000, 1000, 1000, 1000};
    @TempDir
    Path dir;
    private final FakeFlightBoard board = KeeperBench.armable();
    private final List<Process> started = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private FakeBoardTcp tcp;
    private StandInPhone phone;
    private volatile boolean beating;
    /** The control port of the keeper that was started last. */
    private int control;

    @AfterEach
    void end() {
        beating = false;
        if (phone != null) phone.close();
        for (Process p : started) if (p.isAlive()) p.destroyForcibly();
        if (tcp != null) tcp.close();
        BetaflightUsb.use(null);
    }

    /** A keeper in a JVM of its own, on the made-up board's TCP port, its lines into that file. */
    private Process keeper(Path out) throws IOException {
        if (tcp == null) tcp = new FakeBoardTcp(board);
        List<String> cmd = List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx256m", "-cp", System.getProperty("java.class.path"),
                FcKeeper.class.getName(), "board=tcp:127.0.0.1:" + tcp.port(), "lock=" + dir.resolve("keeper.lock"), "folder=" + dir.resolve("keeper"),
                "armedFile=" + dir.resolve("armed.txt"), "cells=4", "floor=3.5", "heartbeat=800", "back=4000");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        started.add(p);
        return p;
    }

    private String said(Path out) {
        try {
            return Files.readString(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** The keeper's control port, as its lock file says it to whoever wants to know who has the board. */
    private int controlPort(Path out) throws InterruptedException {
        Pattern control = Pattern.compile("control 127\\.0\\.0\\.1:(\\d+)");
        int[] port = new int[1];
        Waits.waitFor(() -> {
            try {
                Matcher m = control.matcher(Files.readString(dir.resolve("keeper.lock"), StandardCharsets.UTF_8));
                if (m.find()) port[0] = Integer.parseInt(m.group(1));
            } catch (IOException | RuntimeException notYet) {
                // not written yet
            }
            return port[0] != 0;
        }, 30_000, "the keeper's process to be up and its lock file to say where (its own lines: " + said(out) + ")");
        return port[0];
    }

    private KeeperClient upAndTaken(Path out) throws Exception {
        control = controlPort(out);
        KeeperClient rig = new KeeperClient(control, 20_000);
        Waits.waitFor(() -> {
            try {
                return rig.state().boardUp();
            } catch (IOException e) {
                return false;
            }
        }, 10_000, "the keeper to take the board over TCP: " + said(out));
        return rig;
    }

    /** A window opened and the board armed in it by the stand-in phone, with the rig's beat on a connection of its own. */
    private void armInAWindow(KeeperClient rig) throws Exception {
        int port = control;
        beating = true;
        Thread beat = new Thread(() -> {
            try (KeeperClient own = new KeeperClient(port, 2000)) {
                while (beating) {
                    own.beat();
                    Thread.sleep(40);
                }
            } catch (IOException | InterruptedException e) {
                // the keeper went
            }
        }, "selfTestBeat");
        beat.setDaemon(true);
        beat.start();
        rig.beat();
        phone = new StandInPhone(rig.state().number("phonePort")).sticks(1000, 1000).startSticks();
        assertEquals("OK window=30000", rig.ask("OPEN 30"));
        Thread.sleep(60);
        phone.sticks(1000, 2000);
        Waits.waitFor(board::armed, 5000, "the made-up board to arm in the window");
        assertFalse(board.armingLocked());
    }

    @Test
    void theKeepersProcessIsTheOnlyOneAndItsExitCodeIsItsWordOnTheBoard() throws Exception {
        Path out = dir.resolve("keeper-1.out");
        Process first = keeper(out);
        try (KeeperClient rig = upAndTaken(out)) {
            assertTrue(board.armingLocked());
            assertTrue(tcp.connected());

            // a second keeper for the same board: refused, and nothing of it started
            Path out2 = dir.resolve("keeper-2.out");
            Process second = keeper(out2);
            assertTrue(second.waitFor(60, TimeUnit.SECONDS), "the second keeper ends by itself");
            assertEquals(FcKeeper.EXIT_NO_START, second.exitValue(), said(out2));
            assertTrue(said(out2).contains("THE KEEPER DID NOT START: in use: a keeper has the board already (keeper pid " + first.pid()), said(out2));
            assertTrue(first.isAlive() && rig.state().boardUp(), "the first is not disturbed");

            armInAWindow(rig);
            // "make safe and end", as gradlew fcKeeperEnd says it
            ByteArrayOutputStream answer = new ByteArrayOutputStream();
            int code = FcKeeper.run(new String[]{"end", "control=" + control}, new PrintStream(answer, true, StandardCharsets.UTF_8));
            String word = answer.toString(StandardCharsets.UTF_8);
            assertEquals(FcKeeper.EXIT_SAFE, code, word);
            assertTrue(word.startsWith("OK safe=1 locked=1 armed=0 atRest=1 motors=[1000,1000,1000,1000] SAFE: arming locked, disarmed"), word);
        }
        assertTrue(first.waitFor(30, TimeUnit.SECONDS), "the keeper's process ends once it has answered");
        assertEquals(FcKeeper.EXIT_SAFE, first.exitValue(), said(out));
        assertTrue(said(out).contains("THE KEEPER HAS ENDED, the port tcp:127.0.0.1:" + tcp.port() + " is let go. SAFE: arming locked, disarmed"), said(out));
        assertFalse(board.armed());
        assertTrue(board.armingLocked());
        assertArrayEquals(AT_REST, board.motors());
        Waits.waitFor(() -> !tcp.connected(), 5000, "the board's port to be let go");
        assertEquals("", Files.readString(dir.resolve("keeper.lock")).trim(), "and the lock file given back");
        try (var files = Files.list(dir.resolve("keeper"))) {
            List<String> names = files.map(f -> f.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(n -> n.matches("board-\\d{8}-\\d{6}.*\\.csv")) && names.stream().anyMatch(n -> n.matches("keeper-.*\\.log")), names.toString());
        }
        // nobody there any more: said, with its own exit code
        ByteArrayOutputStream nobody = new ByteArrayOutputStream();
        assertEquals(FcKeeper.EXIT_NO_KEEPER, FcKeeper.run(new String[]{"end", "control=" + control}, new PrintStream(nobody, true, StandardCharsets.UTF_8)));
        assertTrue(nobody.toString(StandardCharsets.UTF_8).contains("NO KEEPER answers on 127.0.0.1:"), nobody.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aKeeperKilledOutrightInAnOpenWindowLeavesABoardThatBoardSafeLocksAndStops() throws Exception {
        Path out = dir.resolve("keeper-killed.out");
        Process keeper = keeper(out);
        try (KeeperClient rig = upAndTaken(out)) {
            armInAWindow(rig);
            keeper.destroyForcibly(); // Task Manager, a power cut: no END, no shutdown hook, nothing of the keeper's own runs
            assertTrue(keeper.waitFor(20, TimeUnit.SECONDS));
        }
        beating = false;
        Waits.waitFor(() -> phone.ended() && !tcp.connected(), 5000, "the phone's connection and the board's port to end with the process");
        // the made-up board has no failsafe of its own: it is as the dead keeper left it (a real board disarms 1.5 s after its sticks stop)
        assertTrue(board.armed(), "armed still");
        assertFalse(board.armingLocked(), "and not locked");

        // what bench.ps1 hw ends with, however the run went: gradlew boardSafe - the port is free for it now
        BetaflightUsb.use(board.usb());
        BoardSafe.Result r = BoardSafe.run(board.port(), FakeFlightBoard.NO_WAITING, BoardSafe.ARMING_LOCK, log::add);
        assertTrue(r.safe(), r.text());
        assertEquals("SAFE: motors read [1000, 1000, 1000, 1000] (at rest), disarmed, arming locked - it was ARMED: the lock disarmed it.", r.text());
        assertFalse(board.armed());
        assertTrue(board.armingLocked());
        assertArrayEquals(AT_REST, board.motors());
        BetaflightUsb.use(null);

        // and the next keeper starts: the dead one's hold on the lock file went with its process
        Path out2 = dir.resolve("keeper-next.out");
        Process next = keeper(out2);
        Waits.waitFor(() -> said(out2).contains("The board is taken: tcp:127.0.0.1:" + tcp.port()), 60_000, "the next keeper to take the board: " + said(out2));
        Matcher m = Pattern.compile("control 127\\.0\\.0\\.1:(\\d+)").matcher(said(out2));
        assertTrue(m.find(), said(out2));
        assertEquals(FcKeeper.EXIT_SAFE, FcKeeper.run(new String[]{"end", "control=" + m.group(1)}, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        assertTrue(next.waitFor(30, TimeUnit.SECONDS));
        assertEquals(FcKeeper.EXIT_SAFE, next.exitValue(), said(out2));
    }

    @Test
    void theExitCodeSaysWhenTheBoardWasNotLeftSafe() throws Exception {
        board.ignoreArmingDisable = true; // the lock is answered and does not show: nobody can call this board locked
        Path out = dir.resolve("keeper-unsafe.out");
        Process keeper = keeper(out);
        try (KeeperClient rig = upAndTaken(out)) {
            assertTrue(rig.ask("OPEN 5").startsWith("NO "), "no window on such a board");
            String word = rig.ask("END");
            assertTrue(word.startsWith("NO safe=0 locked=0"), word);
        }
        assertTrue(keeper.waitFor(30, TimeUnit.SECONDS));
        assertEquals(FcKeeper.EXIT_NOT_SAFE, keeper.exitValue(), said(out));
        assertTrue(said(out).contains("NOT SAFE: the arming lock was sent but does not show in this board's status"), said(out));
    }
}
