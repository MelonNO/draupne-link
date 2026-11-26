package de.droiddrone.bench.hw;

import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.PhoneAdb;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keeper's sides ({@link FcKeeper}): a board over plain TCP (Betaflight as a program later; the made-up board
 * behind a port here), the phone's port led down the cable through adb - a recorder stands where adb is - a second
 * keeper refused by the lock file, and what the keeper takes from this bench's hardware file. No serial port is
 * opened anywhere in the tests: the real board's side is {@link FcKeeper#serial}, and only gradlew fcKeeper uses it.
 */
class FcKeeperBoardSideTest {
    @TempDir
    Path dir;
    private final FakeFlightBoard board = KeeperBench.armable();
    private final KeeperBench bench = new KeeperBench(board);
    private FakeBoardTcp tcp;
    private StandInPhone phone;

    @AfterEach
    void end() {
        if (phone != null) phone.close();
        bench.close();
        if (tcp != null) tcp.close();
        BetaflightUsb.use(null);
    }

    @Test
    void aBoardOverTcpIsTakenLostFoundAgainAndLetGoAtTheEnd() throws Exception {
        tcp = new FakeBoardTcp(board);
        bench.start(FcKeeper.tcp("127.0.0.1", tcp.port())).beat();
        assertTrue(board.armingLocked() && tcp.connected());
        assertTrue(bench.saidOf("The board is taken: tcp:127.0.0.1:" + tcp.port() + ", BTFL 4.4.1, disarmed; the arming lock is set and shows as arming-disable flag 16"), bench.said.toString());
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=20000", bench.ask("OPEN 20"));
        bench.arm(phone);
        assertTrue(bench.ask("CLOSE").startsWith("OK safe=1 locked=1 armed=0"));

        board.unplug(); // Betaflight ends (or restarts): the connection goes, as a USB port does
        Waits.waitFor(() -> bench.state().get("board").equals("away") && phone.ended(), 5000, "the keeper to see the board gone, and the phone with it", bench.said);
        board.plugIn();
        Waits.waitFor(() -> bench.state().boardUp() && board.armingLocked(), 8000, "the keeper to take it again, locked", bench.said);

        assertTrue(bench.ask("END").startsWith("OK safe=1 locked=1 armed=0 atRest=1"));
        Waits.waitFor(() -> !tcp.connected(), 5000, "the connection to the board to be let go", bench.said);
        BetaflightUsb.use(board.usb());
        try (BetaflightUsb fc = BetaflightUsb.open(board.port())) {
            assertEquals("BTFL", fc.firmware(), "the board is free for whoever comes next");
        }
    }

    /** Where adb is: every command written down, and answered as a test says. */
    private static final class Recorder implements PhoneAdb {
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile String answer = "";
        volatile boolean gone;

        @Override
        public byte[] run(long timeoutS, List<String> args) {
            sent.add(String.join(" ", args));
            if (gone) throw new IllegalStateException("adb " + String.join(" ", args) + " did not end within " + timeoutS + " s");
            return answer.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Running follow(List<String> args, Path into) {
            throw new AssertionError("the keeper follows nothing on the phone");
        }

        @Override
        public Running startEmulator(List<String> args, Path log) {
            throw new AssertionError("the keeper starts no emulator");
        }
    }

    @Test
    void thePhonesPortIsLedDownTheCableByTheKeeperItselfAgainAndAgain() throws Exception {
        Recorder adb = new Recorder();
        bench.adb = adb;
        bench.config.reverseEveryMs = 60;
        bench.start();
        String command = "reverse tcp:" + bench.keeper.phonePort() + " tcp:" + bench.keeper.phonePort();
        Waits.waitFor(() -> adb.sent.size() >= 4, 5000, "adb reverse, and again", bench.said);
        assertEquals("ok", bench.state().get("reverse"));
        assertTrue(bench.saidOf("adb " + command + ": the phone's 127.0.0.1:" + bench.keeper.phonePort() + " comes down the cable to the keeper"), bench.said.toString());

        adb.answer = "adb: error: device 'EXAMPLE0SERIAL' not found"; // the cable out
        Waits.waitFor(() -> bench.state().get("reverse").equals("failed"), 5000, "the keeper to say the cable is out", bench.said);
        assertTrue(bench.saidOf("FINDING: adb " + command + ": FAILED: adb: error: device 'EXAMPLE0SERIAL' not found"), bench.said.toString());
        adb.gone = true; // adb itself hangs
        Waits.waitFor(() -> bench.saidOf("did not end within 15 s"), 5000, "the keeper to say so", bench.said);
        adb.gone = false;
        adb.answer = String.valueOf(bench.keeper.phonePort()); // in again: adb prints the port
        Waits.waitFor(() -> bench.state().get("reverse").equals("ok"), 5000, "the port led down the cable again", bench.said);
        int count = adb.sent.size();
        bench.close();
        assertTrue(count >= 6, "every 60 ms here, every 5 s on the bench: " + count);
        for (String sent : adb.sent) assertEquals(command, sent, "the one command the keeper ever sends a phone (it is on PhoneGuard's list)");
        Thread.sleep(200);
        assertTrue(adb.sent.size() <= count + 1, "and none after it has ended");
    }

    @Test
    void aSecondKeeperForTheSameBoardIsRefusedByTheLockFile() throws Exception {
        bench.config.lockFile = dir.resolve("keeper.lock");
        bench.start();
        String holder = Files.readString(bench.config.lockFile);
        assertTrue(holder.startsWith("keeper pid " + ProcessHandle.current().pid() + ", board " + board.port().systemName() + ", control 127.0.0.1:" + bench.keeper.controlPort()), holder);

        FcKeeper.Config again = KeeperBench.quick();
        again.lockFile = bench.config.lockFile;
        again.console = line -> {
        };
        FcKeeper second = new FcKeeper(again, FcKeeper.madeUp(KeeperBench.armable()), null);
        IOException inUse = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, second::start);
        assertTrue(inUse.getMessage().startsWith("in use: a keeper has the board already (keeper pid "), inUse.getMessage());
        assertTrue(bench.state().boardUp(), "the first is not disturbed");
        assertFalse(second.end("never started").safe());

        bench.close();
        assertEquals("", Files.readString(bench.config.lockFile).trim(), "given back at the end");
        FcKeeper third = new FcKeeper(again, FcKeeper.madeUp(board), null).start(); // and the next keeper starts
        third.close();
    }

    @Test
    void aRealBoardsKeeperTakesEverythingFromTheHardwareFile() throws Exception {
        Path file = dir.resolve(Hardware.FILE);
        Files.writeString(file, "phone.ip=192.0.2.10\nboard.port=COM99\nboard.armAux=2\nbattery.cells=6\nbattery.cellFloor=3.6\nkeeper.port=47800\nkeeper.motorSeconds=120\n");
        Hardware bench = Hardware.read(file);
        assertTrue(bench.hasKeeper());
        assertFalse(bench.mayArm(), "arming is a word the file has to say, and does not as a rule");
        FcKeeper.Config c = new FcKeeper.Config();
        FcKeeper.forHardware(c, bench, dir);
        assertArrayEquals(new int[]{47800, 47801, 47802}, new int[]{c.controlPort, c.phonePort, c.setupPort}, "control, the phone's lease, Drone setup's");
        assertEquals(6, c.cells);
        assertEquals(3.6, c.cellFloor, 1e-9);
        assertEquals(2, c.armAux);
        assertEquals(120, c.budgetS);
        assertEquals(dir.resolve("keeper.lock"), c.lockFile);
        assertEquals(dir.resolve("keeper-armed.txt"), c.budgetFile);
        assertEquals(dir.resolve("keeper"), c.folder);
        assertTrue(c.strict && c.ceiling == Hardware.THROTTLE_CEILING && c.windowMaxS == 60 && c.heartbeatMs == 2000 && c.voltageLowMs == 2000);

        Files.writeString(file, "phone.ip=192.0.2.10\nboard.mayArm=true\n");
        Hardware armable = Hardware.read(file);
        assertTrue(armable.mayArm());
        assertFalse(armable.hasKeeper(), "no keeper.port: a bench with no board on this PC");
        assertEquals(600, armable.motorSecondsPerHour());
    }

    // >>> R3, second round
    @Test
    void aRealBoardsKeeperGuardsItsFailsafeAndItsOutputLimitUnlessTheHardwareFileSaysBothWords() throws Exception {
        Path file = dir.resolve(Hardware.FILE);
        String rig = "phone.ip=192.0.2.10\nboard.port=COM99\nbattery.cells=6\nbattery.cellFloor=3.5\nkeeper.port=47800\n";
        Files.writeString(file, rig);
        Hardware plain = Hardware.read(file);
        assertFalse(plain.letFailsafeRun() || plain.failsafeSeenOnSitl());
        assertEquals(15, plain.outputLimitMax(), "the motor test's own cap is 10 %: a little over it");
        FcKeeper.Config c = new FcKeeper.Config();
        assertFalse(c.failsafeEndsWindow || c.readSettings, "of itself (the made-up board, Betaflight as a program) the keeper lets a failsafe run and asks no CLI");
        assertEquals(0, c.outputLimitMax);
        assertEquals(2000, c.sticksStoppedMaxMs);
        FcKeeper.forHardware(c, plain, dir);
        assertTrue(c.failsafeEndsWindow, "on a real board the keeper ends a window before the board's failsafe can run");
        assertTrue(c.readSettings, "and reads the board's own settings first");
        assertEquals(15, c.outputLimitMax);
        assertFalse(c.failsafeSeenOnSitl);

        // let it run: only with the second word, that somebody saw on Betaflight as a program what this procedure does
        Files.writeString(file, rig + "board.letFailsafeRun=true\nboard.outputLimitMax=10\n");
        Hardware alone = Hardware.read(file);
        assertTrue(alone.letFailsafeRun());
        assertEquals(10, alone.outputLimitMax());
        IllegalStateException no = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> FcKeeper.forHardware(new FcKeeper.Config(), alone, dir));
        assertTrue(no.getMessage().contains("board.letFailsafeRun=true") && no.getMessage().contains("board.failsafeSeenOnSitl=true") && no.getMessage().contains(file.toString()),
                no.getMessage());
        Files.writeString(file, rig + "board.letFailsafeRun=true\nboard.failsafeSeenOnSitl=true\n");
        FcKeeper.Config let = new FcKeeper.Config();
        FcKeeper.forHardware(let, Hardware.read(file), dir);
        assertFalse(let.failsafeEndsWindow);
        assertTrue(let.failsafeSeenOnSitl && let.readSettings);
        assertEquals(15, let.outputLimitMax, "the output limit is asked for either way");
        // and what is no such word is said, not read as one
        for (String wrong : List.of("board.letFailsafeRun=yes\n", "board.failsafeSeenOnSitl=1\n", "board.outputLimitMax=0\n", "board.outputLimitMax=101\n")) {
            Files.writeString(file, rig + wrong);
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> Hardware.read(file), wrong).getMessage().contains(file.toString()));
        }
        // by hand (Betaflight as a program): the same words as arguments, and a wrong one is said
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        assertEquals(FcKeeper.EXIT_NO_START, FcKeeper.run(new String[]{"board=tcp:127.0.0.1:9", "failsafe=perhaps"}, new PrintStream(said, true, StandardCharsets.UTF_8)));
        assertTrue(said.toString(StandardCharsets.UTF_8).contains("failsafe=perhaps: failsafe=end (the keeper ends an arm window when the sticks stop) or failsafe=run"), said.toString(StandardCharsets.UTF_8));
    }
    // <<< R3, second round

    @Test
    void byHandTheKeeperSaysItsStateAndTakesNoSerialPortFromItsArguments() throws Exception {
        bench.start();
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(said, true, StandardCharsets.UTF_8);
        assertEquals(FcKeeper.EXIT_SAFE, FcKeeper.run(new String[]{"state", "control=" + bench.keeper.controlPort()}, out));
        assertTrue(said.toString(StandardCharsets.UTF_8).startsWith("OK board=up lease=none armed=0 fresh=1 locked=1 blocks=MSP window=0 tripped=-"), said.toString(StandardCharsets.UTF_8));
        said.reset();
        assertEquals(FcKeeper.EXIT_NO_START, FcKeeper.run(new String[]{"board=serial:COM99"}, out), "a real board's port is the hardware file's, never an argument's");
        assertTrue(said.toString(StandardCharsets.UTF_8).contains("by hand the keeper takes Betaflight as a program only"), said.toString(StandardCharsets.UTF_8));
        said.reset();
        assertEquals(FcKeeper.EXIT_NO_START, FcKeeper.run(new String[]{"dance"}, out));
        assertTrue(said.toString(StandardCharsets.UTF_8).contains("Not understood: dance"));
    }
}
