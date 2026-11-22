package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.Ui;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * The first run on the whole real chain (the hardware rig, R3): the sealed app's made-up radio - the real network -
 * the bench build of the phone app - TCP down the USB cable - the flight controller keeper - the real board. It needs
 * the keeper running ({@code .\bench.ps1 hw} starts it before the suite; by hand: gradlew fcKeeper in a window of its
 * own), the phone on adb with the bench build installed, the board on this PC's USB, and no propellers.
 * <p>
 * What it does: the phone app told to reach its flight controller through the keeper and started; PILOT UAV; the app
 * shows the flight controller (not FC LOST); twenty seconds of sticks at rest, counted at the board - frames a second,
 * the largest gap, the 99th percentile - into the film. Nothing is armed, and no arm window is asked for -
 * <b>unless the hardware file says {@code board.mayArm=true}</b>, which belongs to a session with the battery in and
 * somebody deciding it: then one window of ten seconds, PREARM and ARM up at throttle minimum, armed as the board
 * itself says, ARM down, disarmed as the board says, the window closed. It ends hands off, locked, and safe by the
 * keeper's own word (the rig asks for that at every hardware scenario's end).
 * <p>
 * With the battery out the board would not arm whatever the keeper did (bidirectional DShot with no ESC answering:
 * Betaflight's RPMFILTER). The film says so in the board's own words - "cannot arm: RPMFILTER ..." - beside the board's
 * failsafe and output limit as the keeper read them, and none of it is a failure: without {@code board.mayArm} this
 * scenario ends green on a board that cannot arm. With it, no window opens on a real board whose motor_output_limit
 * is over the hardware file's {@code board.outputLimitMax} (gradlew boardCap writes it), and the keeper's own words
 * for that are the failed check's.
 * <p>
 * Without a hardware file, or with one that names no keeper, it skips itself.
 */
@Tag("hw")
class BoardChainScenario {
    @Test
    void theSticksReachTheRealBoardThroughTheWholeChainAndItEndsLocked() throws Exception {
        Assumptions.assumeTrue(Hardware.present(), "This bench has no hardware set up: no " + Hardware.FILE
                + " in %LOCALAPPDATA%\\DraupneBench (start from " + Hardware.EXAMPLE + ")");
        Hardware bench = Hardware.load();
        Assumptions.assumeTrue(bench.hasKeeper(), "This bench's hardware file names no keeper (keeper.port): no flight controller on this PC's USB to test");

        // the keeper up and the board taken, before anything else is started
        KeeperClient.State before;
        try (KeeperClient keeper = new KeeperClient(bench.keeperPort(), 5000)) {
            before = keeper.state();
            for (int i = 0; i < 60 && !before.boardUp(); i++) { // it may still be taking the board
                Ui.pause(500);
                before = keeper.state();
            }
        } catch (IOException e) {
            throw new AssertionError("No keeper answers on 127.0.0.1:" + bench.keeperPort() + " (" + e.getMessage() + "). Start it first: .\\bench.ps1 hw does, "
                    + "or gradlew fcKeeper in a window of its own.");
        }
        if (!before.boardUp()) throw new AssertionError("The keeper has no board (is it on USB, is its port the hardware file's board.port?): " + before);
        if (before.tripped() != null) throw new AssertionError("The keeper has tripped and was not cleared: " + before.tripped());
        if (!Boolean.FALSE.equals(before.armed()) || !Boolean.TRUE.equals(before.locked())) {
            throw new AssertionError("The board is not disarmed and locked before the scenario begins: " + before);
        }
        int phonePort = before.number("phonePort");
        KeeperClient.State atStart = before;

        AppRig.scenario("hw-board-chain").hardware(bench).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            try (KeeperClient keeper = rig.guard.keeper()) { // (the rig's guard has told the keeper the scenario's name: its frames are in frames-hw-board-chain.log)
                rig.film.remark("The keeper at the start: " + atStart);
                rig.check(rig.radio.isHandsOff() && rig.radio.ceiling() == Hardware.THROTTLE_CEILING, "the radio starts hands off, its throttle ceiling at " + Hardware.THROTTLE_CEILING);

                // the phone app's flight controller is the keeper's port, down the cable
                PhoneRig.Target target = PhoneRig.Target.device();
                if (!target.benchBuild()) {
                    throw new AssertionError("phone.package names " + target.app() + ": only the bench build (flight-app\\build.ps1 -Bench) can reach a flight controller through the keeper");
                }
                try (PhoneRig phone = new PhoneRig(rig.film, target)) {
                    ui.until("the keeper to have led the phone's port " + phonePort + " down the cable (adb reverse)", 30_000, () -> state(keeper).get("reverse").equals("ok"));
                    phone.shell("setprop debug.draupne.fc tcp:" + phonePort);
                    rig.check(phone.shell("getprop debug.draupne.fc").trim().equals("tcp:" + phonePort), "the phone app is told: its flight controller over TCP, port " + phonePort);
                    phone.forceStop(); // it reads the choice when it next opens its flight controller's port: closed, and opened again
                    phone.launch();
                    ui.until("the phone app to connect to the keeper (the phone's lease)", 60_000, () -> state(keeper).lease().equals("phone"));
                    rig.film.remark("The phone app has its flight controller through the keeper: " + state(keeper));
                    ui.shot("the phone app reaches the board through the keeper");

                    // the app finds the phone and connects, as in every hardware scenario
                    ui.until("the real phone, online, in the PHONE list", 90_000, () -> chosenPhone(ui).contains("online"));
                    ui.click("PILOT UAV");
                    ui.waitForControl("STANDBY MODE", 30_000);
                    ui.until("the link up", 60_000, pilot::linkUp);
                    ui.until("the app to show the flight controller: LINK READY, not FC LOST", 60_000, () -> ui.has("LINK READY"));
                    ui.shot("PILOT UAV: the link is up and the app has the real flight controller's word");
                    keeper.order("MARK");
                    ui.until("the app's sticks to reach the board (stick frames at the keeper)", 60_000, () -> figures(keeper).number("frames") >= 10);

                    // twenty seconds of sticks at rest, counted at the board
                    keeper.order("MARK");
                    Ui.pause(20_000);
                    KeeperClient.State figures = figures(keeper);
                    KeeperClient.State rest = state(keeper);
                    rig.film.remark(String.format(java.util.Locale.ROOT, "STICKS AT REST, 20 s, AT THE BOARD: %.1f frames a second (%d frames in %.1f s), largest gap %.1f ms, "
                                    + "99th percentile %.1f ms; the keeper itself held a frame %.2f ms at most, %.3f ms in the mean.", figures.real("perSecond"), figures.number("frames"),
                            figures.real("seconds"), figures.real("maxGapMs"), figures.real("p99GapMs"), figures.real("addedMaxMs"), figures.real("addedMeanMs")));
                    if (figures.real("maxGapMs") > 100) {
                        rig.film.remark("NOTE: a gap of more than 100 ms between two stick frames: Betaflight counts its receiver as lost for that moment (RXLOSS).");
                    }
                    rig.check(figures.number("frames") > 200, "stick frames reach the board for the whole twenty seconds: " + figures.number("frames"));
                    rig.check(rest.number("throttle") <= 1010 && rest.get("armOn").equals("0"), "at rest the board is sent throttle minimum and ARM off: throttle "
                            + rest.number("throttle") + ", the ARM switch at " + rest.number("arm"));
                    rig.check(Boolean.FALSE.equals(rest.armed()) && Boolean.TRUE.equals(rest.locked()) && rest.windowMs() == 0, "the board is disarmed and locked, no window open: " + rest);
                    rig.check(rest.number("findings") == atStart.number("findings"), "the keeper dropped nothing of what the phone app sent (strict mode's findings: "
                            + rest.number("findings") + ")");
                    rig.check(!ui.sees("FC: ARMED"), "the app shows nothing armed");
                    rig.film.remark("The board's arming blocks at rest, by Betaflight's names: " + rest.get("blocks") + " (MSP is the keeper's lock).");
                    // (second round) what holds the board beside the lock is the board's own word and no failure of this run: with the
                    // battery out and bidirectional DShot on, no ESC answers the flight controller, and Betaflight says RPMFILTER
                    rig.film.remark(cannotArm(rest));
                    rig.film.remark("The board's own settings, by the keeper: failsafe " + rest.get("failsafe") + " after failsafe_delay " + rest.get("failsafeDelay")
                            + "; the keeper ends an arm window " + (rest.number("stickLimitMs") > 0 ? rest.number("stickLimitMs") + " ms after the last stick frame at the latest"
                            : rest.number("stickLimitMs") == 0 ? "NOT by itself (board.letFailsafeRun)" : "- it has no failsafe_delay to go by: NO ARM WINDOW") + "; motor_output_limit "
                            + (rest.number("outputLimit") < 0 ? "NOT READ" : rest.number("outputLimit")) + ", the rig allows " + rest.number("outputLimitMax") + ".");

                    if (bench.mayArm()) armOnce(rig, keeper, bench);
                    else rig.film.remark("No arm window asked for: the hardware file does not say board.mayArm=true.");

                    pilot.standby();
                    ui.shot("STANDBY MODE: the link ended");
                    rig.film.remark("The phone app is left with debug.draupne.fc=tcp:" + phonePort + " (gone with the phone's next restart; adb shell setprop debug.draupne.fc usb puts USB back).");
                } finally {
                    rig.radio.handsOff();
                }
            }
        });
    }

    /** One arm window of ten seconds: PREARM and ARM up at throttle minimum, armed by the board's own word, ARM down, disarmed by its word, closed. */
    private static void armOnce(AppRig rig, KeeperClient keeper, Hardware bench) throws IOException, InterruptedException {
        KeeperClient.State s = state(keeper);
        int arm = s.number("armAux"), prearm = s.number("prearmAux");
        if (arm != bench.armAux()) {
            rig.check(false, "NOT ARMED: the board arms on AUX" + arm + " and the hardware file says board.armAux=" + bench.armAux()
                    + " - the radio's hands-off would put the wrong switch down");
            return;
        }
        if (prearm > 4) {
            rig.check(false, "NOT ARMED: the board's PREARM switch is AUX" + prearm + ", and the made-up radio has four switches");
            return;
        }
        try {
            rig.guard.openWindow(10);
        } catch (IllegalStateException no) {
            rig.check(false, "NOT ARMED: " + no.getMessage());
            return;
        }
        try {
            rig.radio.sticksAtRest();
            if (prearm > 0) rig.radio.aux(prearm, 1); // Betaflight arms only while PREARM is on, and wants it on first
            Ui.pause(700);                             // half a second of clean sticks before the switch counts
            rig.radio.aux(arm, 1);
            boolean armed = Waits.until(() -> Boolean.TRUE.equals(state(keeper).armed()), 6000);
            s = state(keeper);
            // (second round) a board that does not arm says why itself; and a window the keeper ended (sticks that stopped) says so too
            rig.check(armed, "ARM on at throttle minimum: the board itself says ARMED" + (armed ? "" : " - it did not. " + cannotArm(s)
                    + (s.lastEnd() == null ? "" : " The keeper ended the window itself: " + s.lastEnd() + ".") + " (" + s + ")"));
            if (armed) {
                rig.ui.shot("armed in the keeper's window: the board says so");
                Ui.pause(2000);
                rig.check(rig.ui.sees("FC: ARMED"), "the app shows the board armed");
                rig.check(state(keeper).number("throttle") <= Hardware.THROTTLE_CEILING, "the throttle the board was sent stayed at its minimum");
            }
            rig.radio.aux(arm, -1);
            boolean disarmed = Waits.until(() -> Boolean.FALSE.equals(state(keeper).armed()), 5000);
            rig.check(disarmed, "ARM off: the board itself says disarmed");
        } finally {
            rig.radio.handsOff();
            if (prearm > 0) rig.radio.aux(prearm, -1);
            String closed = rig.guard.closeWindow();
            rig.check(closed.startsWith("OK"), "the window closed, the board locked and disarmed by the keeper's word: " + closed);
        }
    }

    /**
     * What holds the board from arming beside the keeper's own lock, as the film says it: "cannot arm: RPMFILTER ..." -
     * the board's own word, never a failure of the keeper's or of the run.
     */
    static String cannotArm(KeeperClient.State s) {
        String blocks = s.cannotArm();
        if (blocks == null) return "Nothing but the keeper's own lock keeps the board from arming.";
        return "cannot arm: " + blocks + " - the board's own word beside the keeper's lock, and no failure of the keeper's"
                + (blocks.contains("RPMFILTER") ? "; RPMFILTER is bidirectional DShot with no ESC answering (the battery out, or an ESC that says nothing)" : "")
                + (blocks.contains("RXLOSS") ? "; RXLOSS is no stick frames reaching the board" : "") + ".";
    }

    private static KeeperClient.State state(KeeperClient keeper) {
        try {
            return keeper.state();
        } catch (IOException e) {
            throw new AssertionError("The keeper does not answer: " + e.getMessage());
        }
    }

    /** The stick frames at the board since the keeper's last MARK. */
    private static KeeperClient.State figures(KeeperClient keeper) {
        try {
            return KeeperClient.State.of(keeper.order("FIGURES"));
        } catch (IOException e) {
            throw new AssertionError("The keeper does not answer: " + e.getMessage());
        }
    }

    /** The PHONE list's chosen line: "MobileAlpha   online", or "No phone seen yet". */
    private static String chosenPhone(Ui ui) {
        return String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem()));
    }
}
