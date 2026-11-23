package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Sandbox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HwDroneSetupScenario}'s own run before it meets the real board: the same {@link DroneSetupRun} - the board's
 * settings read through the keeper's lease, SETUP &gt; Set up a new drone in the sealed app, every row's words, the
 * motor test window read and closed, CONFIRM, the board read again and held to what Drone setup said, the keeper's
 * board at rest at the end - on the made-up board behind a real keeper that reads the board's settings in its CLI after
 * every lease, as on the real one. The board is one somebody set up and flew (a serial receiver, a GPS on UART2, ARM
 * on AUX3, GPS rescue as its failsafe, its cells forced, ANGLE at the top of AUX2 as on the pilot's 4.4.1 drone), the
 * battery out: CONFIRM has things to write, and the scenario's judge has every kind of difference to hold to Drone
 * setup's words. F-086: the top keeps its ANGLE. The made-up board has no attitude to read: CALIBRATE is not pressed
 * there (HwDroneSetupTest holds its difference to the judge).
 */
@Tag("benchself")
class HwDroneSetupSelfTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void theHardwareScenarioGoesThroughDroneSetupOnTheMadeUpBoardBehindARealKeeper() throws Exception {
        // its cells forced as a Drone setup before left them: the page takes that count itself, no hardware file's needed;
        // F-086: ANGLE at the top of AUX2, as on the pilot's 4.4.1 drone (the made-up board is 4.4.1 too)
        FakeFlightBoard board = FakeFlightBoard.flown().setUp("set failsafe_procedure = GPS-RESCUE", "set force_battery_cell_count = 4",
                "aux 5 1 1 1700 2100 0 0");
        try (KeeperBench keeper = new KeeperBench(board)) {
            keeper.config.readSettings = true; // as on the real board: after every lease its settings are read in its CLI, a restart
            keeper.start().beat();
            DroneSetupRun run = new DroneSetupRun(keeper.keeper.controlPort(), 0, Sandbox.runRoot().resolve("board-self"), new BoardPort.Timing(50, 30_000), false);
            run.before(s -> { });
            AppRig.Plan plan = AppRig.scenario("self-hw-drone-setup");
            run.settings().forEach(plan::setting);
            plan.run(run::run); // fails with every check that did not hold

            HwDroneSetup.Verdict v = run.verdict();
            assertNotNull(v, "the board was read again after Drone setup");
            assertEquals(List.of(), v.wrong(), "nothing Drone setup did not say: " + v.differences());
            assertFalse(run.written().isEmpty(), "a board not set up as Drone setup wants it: CONFIRM wrote");
            assertTrue(v.differences().stream().anyMatch(d -> d.line().contains("vbat_min_cell_voltage") && d.said()), v.differences().toString());
            assertTrue(v.differences().stream().anyMatch(d -> d.line().contains("feature RX_MSP") && d.said()), "the receiver switched over MSP: " + v.differences());
            assertTrue(v.differences().stream().anyMatch(d -> d.line().startsWith("MSP mode_ranges") && d.said()), v.differences().toString());
            assertTrue(board.saved().contains("set force_battery_cell_count = 4"), "the board's own forced count kept");
            assertTrue(v.differences().stream().noneMatch(d -> d.line().contains("force_battery_cell_count")), "CONFIRM forced no other count: " + v.differences());
            assertTrue(board.saved().contains("set mag_hardware = AUTO"), "its compass left on, as the board had it");
            // F-086: the top keeps its ANGLE on 4.4; the lists offered no GPS position hold, and said why
            assertTrue(board.saved().contains("aux 5 1 1 1700 2100 0 0"), "ANGLE still at the top of AUX2: " + board.saved());
            assertTrue(board.saved().lines().anyMatch(l -> l.matches("aux \\d+ 1 1 1300 1700 0 0")), "ANGLE at the middle");
            assertFalse(board.saved().lines().anyMatch(l -> l.matches("aux \\d+ 11 .*")), "no POS HOLD on 4.4");
            assertTrue(run.modeListsSeen().startsWith("offers Acro, Angle, GPS rescue - hint: ") && run.modeListsSeen().contains("2025.12"), run.modeListsSeen());
            // F-087: no GPS answered (battery out): not "Betaflight set up" - the rows that wait for it are named
            assertEquals("Not set: GPS, Compass, GPS rescue.", run.statusAfterConfirm());
            // F-088: BY HAND names the board's forced 4S, the count the page writes (it said 6)
            assertTrue(run.byHandWords().contains("set force_battery_cell_count = 4") && !run.byHandWords().contains("force_battery_cell_count = 6"),
                    run.byHandWords());
            KeeperClient.State s = keeper.state();
            assertTrue(s.boardUp() && !s.lease().equals("setup") && Boolean.TRUE.equals(s.locked()) && Boolean.FALSE.equals(s.armed()), s.toString());
            assertTrue(board.armingLocked(), "the keeper's lock is on the board again");
            assertEquals(0, board.dshotCommands().size(), "no DShot command reached the board");
        }
    }
}
