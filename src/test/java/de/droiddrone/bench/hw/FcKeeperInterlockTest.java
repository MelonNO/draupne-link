package de.droiddrone.bench.hw;

import de.droiddrone.common.MspFrames;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flight controller keeper's interlocks ({@link FcKeeper}), each set off on the made-up board with a stand-in
 * phone that sends real stick frames 250 times a second: the arm lock and its window, the throttle cap, the battery's
 * floor, the armed time, the heartbeat, armed outside a window, and the end. Nothing of the keeper goes near a real
 * board before these hold. Its rules' times are a test's here ({@link KeeperBench#quick}); the rules are the rig's.
 */
class FcKeeperInterlockTest {
    private static final int[] AT_REST = {1000, 1000, 1000, 1000};
    @TempDir
    Path dir;
    private final FakeFlightBoard board = KeeperBench.armable();
    private final KeeperBench bench = new KeeperBench(board);
    private StandInPhone phone;

    @AfterEach
    void end() {
        if (phone != null) phone.close();
        bench.close();
        BetaflightUsb.use(null);
    }

    @Test
    void theRulesAreTheRigsUnlessATestShortensTheirTimes() {
        FcKeeper.Config c = new FcKeeper.Config();
        assertEquals(2000, c.heartbeatMs, "no beat for 2 s");
        assertEquals(2000, c.voltageLowMs, "under the floor for 2 s");
        assertEquals(60, c.windowMaxS, "a window is 60 s at most");
        assertEquals(600, c.budgetS, "600 armed seconds an hour");
        assertEquals(1100, c.ceiling, "the throttle's ceiling");
        assertEquals(50, c.tickMs, "the watch looks twenty times a second");
        assertTrue(c.strict);
        c.windowMaxS = 61;
        assertThrows(IllegalArgumentException.class, () -> new FcKeeper(c, FcKeeper.madeUp(board), null), "no window longer than 60 s, whoever asks");
        FcKeeper.Config loose = new FcKeeper.Config();
        loose.strict = false;
        // (naming a port opens none: the keeper opens its board when it is started, and this one never is)
        assertThrows(IllegalArgumentException.class, () -> new FcKeeper(loose, FcKeeper.serial("NO-SUCH-PORT"), null), "strict mode stays on for a real board");
        assertThrows(IllegalArgumentException.class, () -> FcKeeper.tcp("192.0.2.1", 1), "a board over TCP is on this PC's loopback only");
        FcKeeper.Config noBattery = new FcKeeper.Config();
        noBattery.noBattery = true;
        noBattery.failsafeEndsWindow = true;
        noBattery.readSettings = true;
        noBattery.outputLimitMax = 15;
        assertThrows(IllegalArgumentException.class, () -> new FcKeeper(noBattery, FcKeeper.serial("NO-SUCH-PORT"), null), "a real board's battery is always read");
    }

    @Test
    void betaflightAsAProgramHasNoBatteryAndItsWindowOpensWithoutOne() throws Exception {
        bench.config.battery(0, 0);
        bench.config.noBattery = true; // what the SITL scenarios tell the keeper: the board reads 0 V, there is no pack
        board.battery(0, 0);
        bench.start().beat();
        assertTrue(bench.saidOf("no battery (Betaflight as a program): the battery's rules are left out"), bench.said.toString());
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=5000", bench.ask("OPEN 5"));
        bench.arm(phone);
        Thread.sleep(400); // longer than a low battery is let be in a window (0.3 s here): no battery is no low battery
        assertTrue(board.armed());
        assertNull(bench.state().tripped());
        assertTrue(bench.ask("CLOSE").startsWith("OK safe=1"));
    }

    @Test
    void theBoardIsLockedFromTheMomentItIsTakenAndSticksThatAskForArmDoNotArmIt() throws Exception {
        bench.start();
        assertTrue(board.armingLocked(), "the arming lock is the first thing the board is told");
        KeeperClient.State s = bench.state();
        assertEquals(Boolean.TRUE, s.locked());
        assertEquals(Boolean.FALSE, s.armed());
        assertEquals(Msp.ARMING_DISABLED_MSP_BIT, s.number("lockBit"), "where the lock shows was measured on this board");
        assertTrue(bench.saidOf("channel map AETR1234 (throttle is raw channel 3), ARM on AUX1 1700-2100"), bench.said.toString());
        assertTrue(bench.saidOf("; when its sticks stop: after failsafe_delay 15 (1.5 s) DROP (by MSP_FAILSAFE_CONFIG; its CLI was not asked) - the keeper lets it run "
                + "(not a real board); motors read [1000, 1000, 1000, 1000] (at rest); 16.40 V, 4.10 V a cell (4 cells), floor 3.50; nothing but the keeper's own lock "
                + "keeps it from arming."), bench.said.toString());

        phone = bench.phone().sticks(1000, 2000).startSticks(); // throttle down, ARM on: what arms a board that is not locked
        Waits.waitFor(() -> bench.sticksAtBoard() > 100, 5000, "a hundred stick frames at the board", bench.said);
        assertFalse(board.armed(), "locked: nothing arms");
        assertTrue(bench.saidOf("The sticks ask for ARM (the switch at 2000) and no arm window is open"), bench.said.toString());
        s = bench.state();
        assertEquals("phone", s.lease());
        assertEquals(1000, s.number("throttle"));
        assertEquals("1", s.get("armOn"));
        assertEquals(0, s.windowMs());
        assertNull(s.tripped());
    }

    @Test
    void aWindowLetsTheBoardArmAndClosingItLocksAndDisarms() throws Exception {
        bench.start();
        phone = bench.phone().sticks(1000, 2000).startSticks();
        Waits.waitFor(() -> bench.sticksAtBoard() > 10, 5000, "the board to have seen the ARM switch on under the lock", bench.said);
        assertTrue(bench.ask("OPEN 5").startsWith("NO no heartbeat"), "nothing opens for a rig that does not beat");
        bench.beat();
        assertTrue(bench.ask("OPEN 61").startsWith("NO a window is 1 to 60 s long"));
        assertTrue(bench.ask("OPEN 0").startsWith("NO a window is 1 to 60 s long"));
        // (the ARM switch was on while the lock held: the keeper says at once that this still holds the board)
        assertEquals("OK window=5000 cannotArm=ARMSWITCH", bench.ask("OPEN 5"));
        assertFalse(board.armingLocked(), "the lock is off inside the window");
        assertTrue(bench.ask("OPEN 5").startsWith("NO a window is open: close it first"), "a window is never made longer");
        // the ARM switch was on while the lock held: Betaflight wants it off once before it counts
        Thread.sleep(200);
        assertFalse(board.armed(), "a switch that was held on under the lock does not arm when the lock comes off");
        bench.arm(phone);
        Waits.waitFor(() -> Boolean.TRUE.equals(bench.state().armed()), 3000, "the keeper to know the board is armed", bench.said);
        assertTrue(bench.state().windowMs() > 0);
        bench.waitForSaid("THE BOARD IS ARMED (in the arm window");

        String closed = bench.ask("CLOSE");
        assertTrue(closed.startsWith("OK safe=1 locked=1 armed=0 atRest=1 motors=[1000,1000,1000,1000]"), closed);
        assertFalse(board.armed(), "the lock disarms");
        assertTrue(board.armingLocked());
        assertArrayEquals(AT_REST, board.motors());
        assertEquals(0, bench.state().windowMs());
        assertNull(bench.state().tripped(), "a window the rig closes is no trip");
        Thread.sleep(200);
        assertFalse(board.armed(), "and the sticks, still asking for ARM, do not arm it again");
        bench.waitForSaid("The board is disarmed, after");
    }

    @Test
    void aStickFrameAboveTheCeilingIsNotPassedOnAndEndsTheWindow() throws Exception {
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=20000", bench.ask("OPEN 20"));
        bench.arm(phone);
        phone.sticks(1100, 2000); // the ceiling itself passes
        long before = bench.sticksAtBoard();
        Waits.waitFor(() -> bench.sticksAtBoard() > before + 50, 5000, "stick frames at the ceiling to pass", bench.said);
        assertTrue(board.armed());
        assertNull(bench.state().tripped());

        phone.sticks(1101, 2000);
        bench.waitForSaid("THROTTLE CAP: a stick frame with throttle 1101, above the ceiling of 1100, was not passed on - the arm window is closed.");
        Waits.waitFor(() -> !board.armed() && board.armingLocked(), 3000, "the board locked and disarmed", bench.said);
        assertEquals(0, bench.state().windowMs());
        long after = bench.sticksAtBoard(), sent = phone.sticksSent();
        Waits.waitFor(() -> phone.sticksSent() > sent + 100, 5000, "a hundred more frames above the ceiling", bench.said);
        assertTrue(bench.sticksAtBoard() <= after + 2, "none of them reaches the board (" + after + " -> " + bench.sticksAtBoard() + ")");
        assertArrayEquals(AT_REST, board.motors());

        // tripped: no window until the rig says CLEAR - and then only with the throttle back under the ceiling
        phone.sticks(1000, 1000);
        assertTrue(bench.ask("OPEN 5").startsWith("NO the keeper has tripped (THROTTLE CAP"), bench.ask("STATE"));
        assertEquals("OK", bench.ask("CLEAR"));
        assertEquals("OK window=5000", bench.ask("OPEN 5"));
        bench.arm(phone);
        assertTrue(bench.ask("CLOSE").startsWith("OK safe=1"));
    }

    @Test
    void noWindowOpensUnderTheBatterysFloorAndTwoSecondsUnderItEndOne() throws Exception {
        bench.config.voltageLineMs = 150;
        board.battery(13.6, 4); // 3.4 V a cell, the floor is 3.5
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        String no = bench.ask("OPEN 10");
        assertTrue(no.startsWith("NO the battery is at 3.40 V a cell (13.60 V, 4 cells), under the floor of 3.50"), no);
        assertTrue(board.armingLocked());
        bench.waitForSaid("VOLTS: 13.60 V, 3.40 V a cell (4 cells) - UNDER the floor of 3.50, disarmed");

        board.battery(16.4, 4);
        Waits.waitFor(() -> bench.state().real("cell") > 4, 3000, "the keeper to see the fresh pack", bench.said);
        assertEquals("OK window=10000", bench.ask("OPEN 10"));
        bench.arm(phone);
        board.battery(13.6, 4); // it sags under the floor and stays there
        bench.waitForSaid("VOLTAGE: 13.60 V, 3.40 V a cell (4 cells) - UNDER the floor of 3.50 for 300 ms while an arm window was open - the arm window is closed.");
        Waits.waitFor(() -> !board.armed() && board.armingLocked(), 3000, "the board locked and disarmed", bench.said);
        assertNotNull(bench.state().tripped());
        assertArrayEquals(AT_REST, board.motors());
    }

    @Test
    void aKeeperThatIsToldNoBatteryOpensNoWindow() throws Exception {
        bench.config.battery(0, 0);
        bench.start().beat();
        assertTrue(bench.ask("OPEN 5").startsWith("NO no battery floor is known"), bench.ask("STATE"));
        assertTrue(board.armingLocked());
    }

    @Test
    void aBoardIn3dModeGetsNoWindowAndIsStoppedAtItsMiddle() throws Exception {
        board.features(board.featureMask() | Msp.FEATURE_3D); // there 1000 is full reverse and 1500 is stop
        BetaflightUsb.use(board.usb());
        try (BetaflightUsb fc = BetaflightUsb.open(board.port())) {
            fc.msp(Msp.SET_MOTOR, Msp.allMotors(1000));
        }
        bench.start().beat();
        assertArrayEquals(new int[]{1500, 1500, 1500, 1500}, board.motors(), "taking the board stops its motors where stop is");
        assertTrue(bench.ask("OPEN 5").startsWith("NO the board has 3D mode on"), bench.ask("STATE"));
        assertTrue(bench.ask("END").startsWith("OK safe=1 locked=1 armed=0 atRest=1 motors=[1500,1500,1500,1500]"));
    }

    @Test
    void aRigThatStopsBeatingLosesItsWindow() throws Exception {
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        bench.arm(phone);
        bench.stopBeating(); // the test JVM dies; the phone goes on sending sticks that ask for ARM
        bench.waitForSaid("HEARTBEAT: no beat from the rig for");
        Waits.waitFor(() -> !board.armed() && board.armingLocked(), 3000, "the board locked and disarmed", bench.said);
        assertArrayEquals(AT_REST, board.motors());
        bench.waitForSaid("After that: SAFE: arming locked, disarmed, motors read [1000, 1000, 1000, 1000] (at rest).");
        KeeperClient.State s = bench.state();
        assertEquals(0, s.windowMs());
        assertTrue(s.tripped().startsWith("HEARTBEAT"), s.toString());
        Thread.sleep(250);
        assertFalse(board.armed(), "the sticks still ask for ARM: it stays disarmed");
    }

    @Test
    void aBoardArmedOutsideAWindowIsLockedStoppedAndSaidLoudly() throws Exception {
        bench.start();
        board.arm(); // armed, however that came about - no window is open
        bench.waitForSaid("TRIP: ARMED OUTSIDE A WINDOW: the board says it is armed and no arm window is open");
        Waits.waitFor(() -> !board.armed() && board.armingLocked(), 3000, "the board locked and disarmed", bench.said);
        bench.waitForSaid("After that: SAFE: arming locked, disarmed");
        assertTrue(bench.state().tripped().startsWith("ARMED OUTSIDE A WINDOW"));
        assertTrue(bench.state().number("trips") >= 1);
        assertTrue(bench.rig.events(0).stream().anyMatch(e -> e.startsWith("TRIP ") && e.contains("ARMED OUTSIDE A WINDOW")), "the rig reads it from the control channel");
    }

    @Test
    void theWindowsOwnTimeEndsItArmedOrNot() throws Exception {
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=1000", bench.ask("OPEN 1"));
        Waits.waitFor(() -> bench.saidOf("ARM WINDOW CLOSED (its time was over). SAFE"), 4000, "the window to end by itself", bench.said);
        assertTrue(board.armingLocked());
        assertNull(bench.state().tripped(), "a window that ends disarmed is no trip");

        assertEquals("OK window=1000", bench.ask("OPEN 1"));
        bench.arm(phone);
        bench.waitForSaid("ARMED TIME: the arm window's time was over and the board was still armed - the arm window is closed.");
        Waits.waitFor(() -> !board.armed() && board.armingLocked(), 3000, "the board locked and disarmed", bench.said);
        assertTrue(bench.state().tripped().startsWith("ARMED TIME"));
    }

    @Test
    void theArmedSecondsOfAnHourAreABudgetKeptAcrossStarts() throws Exception {
        bench.config.budgetS = 1;
        bench.config.budgetFile = dir.resolve("armed.txt");
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        bench.arm(phone);
        bench.waitForSaid("ARMED TIME: this hour's 1 armed seconds are used up - the arm window is closed.");
        Waits.waitFor(() -> !board.armed() && board.armingLocked(), 3000, "the board locked and disarmed", bench.said);
        assertEquals("OK", bench.ask("CLEAR"));
        assertTrue(bench.ask("OPEN 5").startsWith("NO this hour's 1 armed seconds are used up"), bench.ask("STATE"));
        assertEquals(0, bench.state().number("budgetLeft"));
        phone.close();
        bench.close();

        // another keeper, the same hour: the armed second is not forgotten
        KeeperBench next = new KeeperBench(board);
        next.config.budgetS = 1;
        next.config.budgetFile = dir.resolve("armed.txt");
        try (next) {
            next.start().beat();
            assertTrue(next.ask("OPEN 5").startsWith("NO this hour's 1 armed seconds are used up"), next.ask("STATE"));
            assertTrue(next.saidOf("1 armed seconds an hour (") && !next.saidOf("armed seconds an hour (0 used)"), next.said.toString());
        }
    }

    @Test
    void makeSafeAndEndLeavesTheBoardLockedAtRestAndItsPortFree() throws Exception {
        // a tool that died left two motors on: Betaflight keeps what it was last told over USB
        BetaflightUsb.use(board.usb());
        try (BetaflightUsb fc = BetaflightUsb.open(board.port())) {
            byte[] p = Msp.allMotors(1000);
            Msp.putU16(p, 0, 1080);
            Msp.putU16(p, 4, 1100);
            fc.msp(Msp.SET_MOTOR, p);
        }
        assertArrayEquals(new int[]{1080, 1000, 1100, 1000}, board.motors());
        bench.start().beat();
        assertArrayEquals(AT_REST, board.motors(), "taking the board stops its motors");
        IOException inUse = assertThrows(IOException.class, () -> BetaflightUsb.open(board.port()));
        assertTrue(inUse.getMessage().contains("in use"), "while the keeper runs nobody else has the port: " + inUse.getMessage());

        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        bench.arm(phone);
        String ended = bench.ask("END");
        assertTrue(ended.startsWith("OK safe=1 locked=1 armed=0 atRest=1 motors=[1000,1000,1000,1000] SAFE: arming locked, disarmed"), ended);
        FcKeeper.Safe word = bench.keeper.awaitEnd();
        assertTrue(word.safe() && word.locked() && !word.armed() && word.atRest());
        assertFalse(bench.keeper.failed());
        assertFalse(board.armed());
        assertTrue(board.armingLocked(), "the lock stays on the board when the keeper has gone");
        assertArrayEquals(AT_REST, board.motors());
        Waits.waitFor(phone::ended, 3000, "the phone's connection to end with the keeper", bench.said);
        try (BetaflightUsb fc = BetaflightUsb.open(board.port())) { // the port is free again
            assertEquals("BTFL", fc.firmware());
            assertFalse(Msp.armed(fc.msp(Msp.STATUS, null)));
        }
        assertThrows(IOException.class, () -> new KeeperClient(bench.keeper.controlPort(), 500).state(), "its control channel has gone with it");
        assertTrue(bench.saidOf("THE KEEPER HAS ENDED, the port " + board.port().systemName() + " is let go. SAFE"), bench.said.toString());
    }

    @Test
    void aMotorValueSentWhileArmedIsStoppedOnceTheLockHasDisarmed() throws Exception {
        board.keepsMotorValuesWhileArmed = true; // as Betaflight's own code reads: kept, and out the moment it is disarmed
        bench.config.strict = false;             // the made-up board only: lets the stand-in say MSP_SET_MOTOR
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        bench.arm(phone);
        byte[] p = Msp.allMotors(1000);
        Msp.putU16(p, 2, 1090);
        phone.request(Msp.SET_MOTOR, p);
        Waits.waitFor(() -> phone.answersTo(Msp.SET_MOTOR) > 0, 3000, "the board to take the motor value", bench.said);
        String ended = bench.ask("END");
        assertTrue(ended.startsWith("OK safe=1 locked=1 armed=0 atRest=1"), ended);
        assertArrayEquals(AT_REST, board.motors(), "disarming let the value out; the keeper's STOP came after it");
    }

    @Test
    void aBoardOnWhichTheLockDoesNotShowGetsNoWindowAndIsNotCalledSafe() throws Exception {
        board.ignoreArmingDisable = true; // a firmware that answers MSP_ARMING_DISABLE and does nothing
        bench.start().beat();
        assertFalse(board.armingLocked());
        assertTrue(bench.saidOf("THE ARMING LOCK DOES NOT SHOW IN ITS STATUS: no arm window opens on this board"), bench.said.toString());
        assertEquals(-1, bench.state().number("lockBit"));
        assertTrue(bench.ask("OPEN 5").startsWith("NO the arming lock does not show in this board's status"), bench.ask("STATE"));
        String ended = bench.ask("END");
        assertTrue(ended.startsWith("NO safe=0 locked=0 armed=0 atRest=1"), ended);
        assertTrue(ended.contains("the arming lock was sent but does not show in this board's status"), ended);
        assertFalse(bench.keeper.awaitEnd().safe());
    }

    @Test
    void anErrorOfTheKeepersOwnEndsItWithTheBoardMadeSafe() throws Exception {
        boolean[] thrown = new boolean[1];
        bench.config.console = line -> {
            bench.said.add(line);
            if (line.contains("THE BOARD IS ARMED") && !thrown[0]) { // the watch's own line: an error on the keeper's main thread, armed, in a window
                thrown[0] = true;
                throw new IllegalStateException("made up, in the keeper's watch");
            }
        };
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        bench.arm(phone);
        FcKeeper.Safe word = bench.keeper.awaitEnd();
        assertTrue(bench.keeper.failed());
        assertTrue(word.safe(), word.text());
        assertFalse(board.armed());
        assertTrue(board.armingLocked());
        assertArrayEquals(AT_REST, board.motors());
        assertTrue(bench.saidOf("AN ERROR OF THE KEEPER'S OWN (keeperWatch): java.lang.IllegalStateException: made up, in the keeper's watch"), bench.said.toString());
        BetaflightUsb.use(board.usb());
        try (BetaflightUsb fc = BetaflightUsb.open(board.port())) {
            assertEquals("BTFL", fc.firmware(), "and the port is let go");
        }
    }

    @Test
    void aBoardThatRestartsIsLockedAgainTheMomentItIsBack() throws Exception {
        bench.start().beat();
        phone = bench.phone().sticks(1000, 2000).startSticks();
        Waits.waitFor(() -> bench.sticksAtBoard() > 20, 5000, "sticks at the board", bench.said);
        board.unplug(); // its USB out: the power gone, the lock with it
        Waits.waitFor(phone::ended, 3000, "the phone's connection to end with the board (as a USB port's)", bench.said);
        assertEquals("away", bench.state().get("board"));
        try (StandInPhone early = bench.phone()) {
            Waits.waitFor(early::ended, 3000, "a connection with no board behind it to be closed at once", bench.said);
            assertEquals(0, early.answers().size());
        }
        assertTrue(bench.ask("OPEN 5").startsWith("NO no board"));
        assertFalse(board.armingLocked(), "a restart clears the lock on the board");
        board.plugIn();
        Waits.waitFor(() -> bench.state().boardUp(), 5000, "the keeper to take the board again", bench.said);
        assertTrue(board.armingLocked(), "and the keeper sets it again before anybody gets a lease");
        try (StandInPhone again = bench.phone().sticks(1000, 2000).startSticks()) {
            long before = bench.sticksAtBoard();
            Waits.waitFor(() -> bench.sticksAtBoard() > before + 50, 5000, "sticks at the board again", bench.said);
            assertFalse(board.armed());
        }
    }

    @Test
    void aBoardThatGoesAwayInAWindowEndsTheWindow() throws Exception {
        bench.start().beat();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        assertEquals("OK window=30000", bench.ask("OPEN 30"));
        board.cutTakesPower = false; // a battery keeps it running: only its USB goes
        board.cutWire();
        bench.waitForSaid("TRIP: THE BOARD WENT AWAY while an arm window was open - the arm window is closed.");
        Waits.waitFor(() -> bench.state().boardUp() && board.armingLocked(), 5000, "the board taken again, locked", bench.said);
        assertEquals(0, bench.state().windowMs());
        assertTrue(bench.state().tripped().startsWith("THE BOARD WENT AWAY"));
    }

    @Test
    void theStickFramesAtTheBoardAreCountedAndFiledARowASecond() throws Exception {
        bench.folder(dir).start().beat();
        assertEquals("OK", bench.ask("SCENARIO cadence check"));
        phone = bench.phone().sticks(1000, 1000).startSticks();
        Waits.waitFor(() -> bench.sticksAtBoard() > 20, 5000, "sticks at the board", bench.said);
        assertEquals("OK", bench.ask("MARK"));
        Thread.sleep(2200);
        KeeperClient.State figures = KeeperClient.State.of(bench.ask("FIGURES"));
        // 250 a second were sent; a loaded PC is late now and then, never early by much
        assertTrue(figures.real("perSecond") > 120 && figures.real("perSecond") < 300, figures.toString());
        assertTrue(figures.number("frames") > 250, figures.toString());
        assertTrue(figures.real("maxGapMs") >= 4 && figures.real("p99GapMs") <= figures.real("maxGapMs"), figures.toString());
        assertTrue(figures.real("addedMaxMs") < 500, "what the keeper itself added: " + figures);
        assertEquals("OK", bench.ask("SCENARIO -"));
        bench.close();

        Path csv;
        try (var files = java.nio.file.Files.list(dir)) {
            csv = files.filter(f -> f.getFileName().toString().matches("board-\\d{8}-\\d{6}.*\\.csv")).findFirst().orElseThrow();
        }
        var rows = java.nio.file.Files.readAllLines(csv);
        assertEquals(MspTap.HEADER, rows.get(0));
        java.util.List<String> names = java.util.List.of(MspTap.HEADER.split(","));
        long withSticks = rows.stream().skip(1).map(r -> r.split(",", -1)).filter(c -> c.length == names.size() && !c[names.indexOf("stick_frames")].isEmpty()
                && Integer.parseInt(c[names.indexOf("stick_frames")]) > 100).count();
        assertTrue(withSticks >= 1, "a row a second with the second's stick frames: " + rows);
        String[] one = rows.stream().skip(1).map(r -> r.split(",", -1)).filter(c -> !c[names.indexOf("stick_frames")].isEmpty()
                && Integer.parseInt(c[names.indexOf("stick_frames")]) > 100).findFirst().orElseThrow();
        assertTrue(one[0].matches("\\d\\d:\\d\\d:\\d\\d\\.\\d{3}"), "the clock as the app's link log has it: " + one[0]);
        assertEquals("up", one[names.indexOf("board")]);
        assertEquals("phone", one[names.indexOf("lease")]);
        assertEquals("0", one[names.indexOf("armed")]);
        assertEquals("1", one[names.indexOf("locked")]);
        assertEquals("4.100", one[names.indexOf("cell_volts")]);
        assertEquals("1000", one[names.indexOf("throttle")]);
        assertEquals("cadence check", one[names.indexOf("scenario")]);
        assertTrue(rows.stream().anyMatch(r -> r.endsWith(",SCENARIO: cadence check")), "what the keeper said has rows of its own");

        var frames = java.nio.file.Files.readAllLines(dir.resolve("frames-cadence_check.log"));
        assertTrue(frames.stream().filter(l -> l.contains(" phone>board v2 < " + Msp.SET_RAW_RC + " len=16 held=") && l.contains(" thr=1000 arm=1000")).count() > 300,
                "every stick frame, with when it came and how long it was held");
        assertTrue(frames.stream().anyMatch(l -> l.contains(" board>phone v2 > " + Msp.STATUS + " ")), "the board's answers to the phone");
        assertTrue(frames.stream().anyMatch(l -> l.contains(" keeper>board v1 < " + Msp.STATUS + " ")), "and the keeper's own questions");
        assertTrue(frames.stream().anyMatch(l -> l.contains(" board>keeper v1 > " + Msp.STATUS + " ")));
        assertEquals(MspFrames.V2, phone.last(Msp.STATUS).version, "the phone gets its answers framed as it asked");
    }
}
