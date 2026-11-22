package de.droiddrone.bench.hw;

import de.droiddrone.bench.FakeRadio;
import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.HwGuard;
import de.droiddrone.testing.Ports;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hardware rig's guard in the test JVM ({@link HwGuard}) against a keeper on the made-up board: it is the rig's
 * heartbeat, it opens and closes arm windows with the radio's hands, and it stops the run's flying - hands off for
 * good, the keeper told, a stop file - when the keeper is silent or trips, when app and keeper disagree about armed,
 * on an error in the app, on a stick request the radio had to cut.
 */
class HwGuardTest {
    @TempDir
    Path dir;
    private final FakeFlightBoard board = KeeperBench.armable();
    private final KeeperBench bench = new KeeperBench(board);
    private final FakeRadio radio = new FakeRadio();
    private final List<String> film = new CopyOnWriteArrayList<>();
    private volatile Boolean appArmed;
    private volatile String appError;
    private HwGuard guard;
    private StandInPhone phone;

    private HwGuard guard() throws InterruptedException {
        return guard(300);
    }

    /** A guard as a hardware rig starts one, at a test's pace - and heard by the keeper before the test goes on. */
    private HwGuard guard(long disagreeMs) throws InterruptedException {
        return guard(disagreeMs, bench.keeper.controlPort());
    }

    private HwGuard guard(long disagreeMs, int keeperPort) throws InterruptedException {
        radio.armSwitch(1).ceiling(Hardware.THROTTLE_CEILING).handsOff(); // as a hardware rig starts
        guard = new HwGuard(keeperPort, radio, new HwGuard.Eyes() {
            @Override
            public Boolean appArmed() {
                return appArmed;
            }

            @Override
            public String appError() {
                return appError;
            }

            @Override
            public int clamped() {
                return radio.clamped();
            }
        }, dir.resolve(HwGuard.STOP_FILE), film::add);
        guard.stepMs = 20;
        guard.silentMs = 300;
        guard.disagreeMs = disagreeMs;
        HwGuard started = guard.named("guard test").start();
        Waits.waitFor(() -> started.keeperState() != null || started.tripped() != null, 3000, "the guard's first beat", film);
        return started;
    }

    @AfterEach
    void end() {
        if (guard != null) guard.close();
        if (phone != null) phone.close();
        bench.close();
    }

    private void waitForTrip(String words) throws InterruptedException {
        // (its line in the film is the last thing a stop does: the radio, the keeper and the stop file are done by then)
        Waits.waitFor(() -> film.stream().anyMatch(l -> l.startsWith("HARDWARE GUARD - THE RUN STOPS FLYING: ")), 5000, "the guard to stop the run (" + words + ")", film);
        assertTrue(guard.tripped().contains(words), guard.tripped());
        assertTrue(radio.isHandsOff());
        IllegalStateException forGood = assertThrows(IllegalStateException.class, radio::handsOn);
        assertTrue(forGood.getMessage().contains("the hardware guard: ") && forGood.getMessage().contains(words), forGood.getMessage());
        assertEquals(guard.tripped(), HwGuard.stopped(dir), "the stop file has the reason: no hardware scenario of this run starts after it");
        assertTrue(film.stream().anyMatch(l -> l.startsWith("HARDWARE GUARD - THE RUN STOPS FLYING: ") && l.contains(words)), film.toString());
        assertThrows(IllegalStateException.class, () -> guard.openWindow(5), "and no arm window is asked for any more");
    }

    @Test
    void aHealthyRunIsLeftAloneAndItsWindowsGoThroughTheGuard() throws Exception {
        bench.start();
        guard(2000); // (the app's word and the board's are changed one after the other here: no two seconds apart)
        phone = bench.phone().sticks(1000, 1000).startSticks();
        appArmed = false;
        Thread.sleep(500);
        assertNull(guard.tripped());
        assertNull(HwGuard.stopped(dir));
        assertTrue(radio.isHandsOff(), "hands off until a window is open");
        Waits.waitFor(() -> bench.state().get("scenario").equals("guard-test"), 3000, "the keeper to be told the scenario's name, for its frame log", bench.said);

        guard.openWindow(10); // the guard's own beat is the heartbeat the keeper asks for
        assertFalse(radio.isHandsOff(), "the keeper said yes: the hands are on");
        assertFalse(board.armingLocked());
        bench.arm(phone);
        appArmed = true; // the app hears it from the board too
        Waits.waitFor(() -> Boolean.TRUE.equals(guard.keeperState().armed()), 3000, "the keeper's word that it is armed", film);
        String closed = guard.closeWindow();
        assertTrue(closed.startsWith("OK safe=1 locked=1 armed=0"), closed);
        assertTrue(radio.isHandsOff() && board.armingLocked() && !board.armed());
        appArmed = false;
        assertNull(guard.tripped(), "armed in a window, both saying so, is what a run is for");
        // something the keeper finds meanwhile (here: a request the phone app never sends) is in the scenario's film at its end
        phone.request(Msp.SET_MOTOR, Msp.allMotors(1200));
        Waits.waitFor(() -> bench.keeper.findings().containsKey("strict-" + Msp.SET_MOTOR), 3000, "the keeper's finding", bench.said);
        String word = guard.finish();
        assertTrue(word.startsWith("OK safe=1 locked=1 armed=0 atRest=1"), word);
        assertTrue(film.stream().anyMatch(l -> l.startsWith("THE KEEPER, during this scenario - FINDING: From the phone's side, not passed on: MSP v2 < 214")), film.toString());
        assertTrue(film.stream().noneMatch(l -> l.contains("INFO")), "what the keeper merely did is in its own log: " + film);
        assertEquals("-", bench.state().get("scenario"), "and the keeper's frame log of it is closed");
        assertNull(HwGuard.stopped(dir));
    }

    @Test
    void aKeeperThatOpensNoWindowLeavesTheHandsOff() throws Exception {
        board.battery(13.0, 4); // under the floor
        bench.start();
        guard();
        IllegalStateException no = assertThrows(IllegalStateException.class, () -> guard.openWindow(10));
        assertTrue(no.getMessage().contains("the keeper says NO the battery is at 3.25 V a cell"), no.getMessage());
        assertTrue(radio.isHandsOff());
        assertNull(guard.tripped(), "a refusal is an answer, not a stop");
    }

    @Test
    void aSilentKeeperStopsTheRun() throws Exception {
        // a keeper that hangs: it answered five beats, and then - its connection still open - not one more
        try (ServerSocket hung = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            Thread keeper = new Thread(() -> {
                try (Socket s = hung.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    for (int beat = 0; in.readLine() != null; beat++) {
                        if (beat < 5) s.getOutputStream().write("OK board=up lease=none armed=0 fresh=1 locked=1 window=0 tripped=- ending=0\n".getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException e) {
                    // the test is over
                }
            }, "hungKeeper");
            keeper.setDaemon(true);
            keeper.start();
            guard(300, hung.getLocalPort());
            assertNull(guard.tripped(), "it answers: nothing to stop");
            waitForTrip("the keeper is silent: no answer on 127.0.0.1:" + hung.getLocalPort());
        }
        String file = Files.readString(dir.resolve(HwGuard.STOP_FILE));
        assertTrue(file.contains("delete the file to go on"), file);
    }

    @Test
    void aKeeperThatEndsUnderARunningScenarioStopsTheRun() throws Exception {
        bench.start();
        guard();
        bench.keeper.close(); // somebody ended it
        Waits.waitFor(() -> film.stream().anyMatch(l -> l.startsWith("HARDWARE GUARD - THE RUN STOPS FLYING: ")), 5000, "the guard to stop the run", film);
        assertTrue(guard.tripped().equals("the keeper is ending") || guard.tripped().startsWith("the keeper is silent"), guard.tripped());
        assertThrows(IllegalStateException.class, radio::handsOn);
    }

    @Test
    void aRunThatStartsWithNoKeeperThereIsStoppedWithinTheSecond() throws Exception {
        int nobody = Ports.freeTcp(); // a port nothing listens on: gradlew benchHw by hand, the keeper forgotten
        guard(300, nobody);
        waitForTrip("the keeper is silent: no answer on 127.0.0.1:" + nobody);
    }

    @Test
    void appAndKeeperDisagreeingAboutArmedStopTheRun() throws Exception {
        bench.start();
        guard();
        appArmed = true; // the app shows ARMED; the board says it is not
        waitForTrip("the app says the board is ARMED and the keeper, from the board's own answer, disarmed");
        Waits.waitFor(() -> bench.state().tripped() != null, 3000, "the keeper to be told", bench.said);
        assertTrue(bench.state().tripped().startsWith("THE RIG STOPPED IT: the rig's guard: the app says the board is ARMED"), bench.state().toString());
        assertTrue(bench.ask("OPEN 5").startsWith("NO the keeper has tripped"), "no window from the keeper either, until somebody says CLEAR");
    }

    @Test
    void aWordThatDiffersForAMomentIsNoDisagreement() throws Exception {
        bench.start();
        guard(2000);
        appArmed = true; // the app's row is a moment behind the board
        Thread.sleep(400);
        appArmed = false;
        Thread.sleep(400);
        appArmed = null; // and no word at all (no link) is no word against
        Thread.sleep(300);
        assertNull(guard.tripped());
    }

    @Test
    void anErrorInTheAppStopsTheRun() throws Exception {
        bench.start();
        guard();
        appError = "uncaught error on AWT-EventQueue-0: java.lang.IllegalStateException: made up\n\tat somewhere";
        waitForTrip("an error in the app that nobody caught: uncaught error on AWT-EventQueue-0: java.lang.IllegalStateException: made up");
    }

    @Test
    void aStickRequestTheRadioHadToCutStopsTheRun() throws Exception {
        bench.start();
        guard();
        guard.openWindow(10);
        radio.axis(FakeRadio.THROTTLE, 0.2); // 1600: a scenario that asks for more than the rig allows
        waitForTrip("a scenario asked the radio for more throttle than its ceiling of 1100: 1 request(s) cut");
        Waits.waitFor(() -> bench.state().windowMs() == 0 && board.armingLocked(), 3000, "the keeper's window closed and the lock set", bench.said);
    }

    @Test
    void aKeeperThatTripsStopsTheRunAndAGuardThatDiesLosesTheWindow() throws Exception {
        bench.start();
        guard();
        phone = bench.phone().sticks(1000, 1000).startSticks();
        guard.openWindow(30);
        bench.arm(phone);
        guard.close(); // the test JVM dies: nobody beats
        bench.waitForSaid("HEARTBEAT: no beat from the rig for");
        Waits.waitFor(() -> !board.armed() && board.armingLocked(), 3000, "the board locked and disarmed by the keeper alone", bench.said);

        film.clear();
        guard(); // the next scenario's guard hears that the keeper has tripped
        waitForTrip("the keeper stopped it: HEARTBEAT: no beat from the rig for");
    }

    @Test
    void theAppsOwnWordIsItsLinkLogsNewestRow() throws Exception {
        LocalTime now = LocalTime.of(12, 0, 5);
        assertNull(HwGuard.linkWord(dir.resolve("no-such-folder"), now));
        assertNull(HwGuard.linkWord(dir, now), "no link log: no word");
        String header = "time,link,ping_ms,rc_frames,rc_max_gap_ms,telemetry_packets,telemetry_max_gap_ms,fc_data_age_ms,rxloss,failsafe,armed,blockers,event";
        Files.writeString(dir.resolve("link-20261121-110000.csv"), header + "\n11:00:01.000,CONNECTED,20,25,40,5,200,100,0,0,1,,\n");
        Path log = dir.resolve("link-20261121-120000.csv");
        Files.writeString(log, header + "\n12:00:04.100,CONNECTED,20,25,40,5,200,100,0,0,0,MSP,\n");
        assertEquals(Boolean.FALSE, HwGuard.linkWord(dir, now), "the newest log's last row: disarmed (and the keeper's lock shows as the app's blocker MSP)");
        Files.writeString(log, header + "\n12:00:04.100,CONNECTED,20,25,40,5,200,100,0,0,0,MSP,\n12:00:04.200,CONNECTED,20,25,40,5,200,100,0,0,1,,\n12:00:04.3");
        assertEquals(Boolean.TRUE, HwGuard.linkWord(dir, now), "armed; a row being written is not read");
        Files.writeString(log, header + "\n12:00:04.200,CONNECTED,20,25,40,5,200,100,0,0,1,,\n12:00:04.300,,,,,,,,,,,,link lost\n");
        assertEquals(Boolean.TRUE, HwGuard.linkWord(dir, now), "an event's row says nothing of its own");
        Files.writeString(log, header + "\n12:00:04.200,CONNECTED,20,25,40,5,200,100,0,0,1,,\n12:00:04.300,CONNECTED,20,25,40,5,200,2100,,,,,\n");
        assertNull(HwGuard.linkWord(dir, now), "the flight controller not heard: no word");
        assertNull(HwGuard.linkWord(dir, LocalTime.of(12, 0, 9)), "a last row older than three seconds: no link any more");
        Files.writeString(log, header + "\n23:59:59.500,CONNECTED,20,25,40,5,200,100,0,0,1,,\n");
        assertEquals(Boolean.TRUE, HwGuard.linkWord(dir, LocalTime.of(0, 0, 0, 400_000_000)), "across midnight");
        Files.writeString(log, "time,link,throttle\n12:00:04.200,CONNECTED,1000\n");
        assertNull(HwGuard.linkWord(dir, now), "a log without the column: no word");
    }
}
