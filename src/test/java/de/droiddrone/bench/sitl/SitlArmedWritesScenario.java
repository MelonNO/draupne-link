package de.droiddrone.bench.sitl;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Film;
import de.droiddrone.bench.Ui;
import de.droiddrone.bench.hw.FcKeeper;
import de.droiddrone.bench.hw.Msp;
import de.droiddrone.common.MspFrames;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.pc.setup.MotorTest;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * F-077, F-078 and F-079 on Betaflight itself, as a program (SITL 4.4.1, the drone's version), armed - as each card
 * says under "Next". The app's USB is SITL's UART1 through a tap that writes down every MSP request and every CLI
 * line the app sends; a stand-in receiver on UART2 (MSP given to it here) sends the sticks 25 times a second and arms
 * and disarms the board as a radio would, and the rig's eyes on UART4 (MSP given to it too) ask Betaflight's status
 * about 8 times a second - beside the app, which has UART1. (Sticks and questions on one SITL port: the answers came
 * 100 to 600 ms late and more, and the stick frames with them - RXLOSS, then BADRX at the ARM switch; on a port of
 * their own the questions are answered in about 10 ms - seen 2026-11-23, 4.4.1.)
 * <ul>
 * <li>First the app's own setup code, no window: a motor test opened disarmed, the board armed, then PROPS OUT, a new
 * motor order, a spin direction, the CLI's entry - each refused, and Betaflight's yaw direction read back.</li>
 * <li>Then the app itself, SETUP &gt; Set up a new drone: Betaflight read disarmed; on the second step the motor test
 * opened, the board armed - PROPS IN / OUT grey, a press on PROPS OUT all the same asks and writes nothing, the yaw
 * direction read back; CALIBRATE says the board is armed; back on the first step CONFIRM pressed while armed - the
 * page says so, round after round, never "No answer" or "Replug", and no '#' goes out. Then disarmed: CONFIRM's write
 * goes through, with Betaflight's own CLI "status" (asked by the tap just before the "save") listing the arming block
 * MSP while the CLI is open, and the block gone after the restart.</li>
 * </ul>
 * While armed nothing that writes may reach the board on UART1: no '#', no MSP_SET_ARMING_DISABLED, no feature, mixer,
 * motor order, calibration, EEPROM write, reboot, passthrough or DShot command. SITL drives its motors by PWM only
 * (no DShot in its build): the window offers no spin direction there, and the app's DShot check refuses one before its
 * armed check is reached. The tap's own "status" before each "save" or "exit" only reads.
 * Needs SITL 4.4 (4.5 on leaves MSP's arming block out of SITL). The film has uart1-tap.tsv and uart2-status.tsv.
 */
@Tag("sitl")
class SitlArmedWritesScenario {
    private static final String ARMED = BetaflightUsb.ARMED;
    /** UART2's and UART4's TCP ports (Sitl.uart: SITL's own or the forwarder's): MSP is given to both here, the receiver's sticks and the rig's eyes. */
    private static final int UART2 = Sitl.uart(2), UART4 = Sitl.uart(4);
    /** Betaflight 4.4's arming block MSP among MSP_STATUS' arming-disable flags. */
    private static final int MSP_BLOCK = 1 << Msp.ARMING_DISABLED_MSP_BIT;
    private static final int MSP_ATTITUDE = 108;
    /** UART2 or UART4 not back this long while SITL runs: it could not open it again (see Receiver.restartThroughAnother). */
    private static final long NOT_BACK_MS = 10_000;
    /** The MSP requests that change a board: none may reach an armed one. */
    private static final Set<Integer> WRITES_V1 = Set.of(Msp.ARMING_DISABLE, Msp.SET_FEATURE_CONFIG, Msp.SET_MIXER_CONFIG,
            Sitl.ACC_CALIBRATION, Msp.EEPROM_WRITE, Msp.REBOOT, Msp.SET_PASSTHROUGH);
    private static final Set<Integer> WRITES_V2 = Set.of(Msp.SET_MOTOR_OUTPUT_REORDERING, Msp.SEND_DSHOT_COMMAND);

    /** The armed spells: from Betaflight's first "armed" to the moment the ARM switch went down. */
    private final List<long[]> armedSpells = new ArrayList<>();
    private long t0;

    @Test
    void anArmedBoardGetsNoWriteFromDroneSetupAndThePageSaysWhy() throws Exception {
        String missing = Sitl.missing();
        Assumptions.assumeTrue(missing == null, missing);
        String version;
        try (BetaflightUsb fc = Sitl.open(s -> { })) {
            version = Sitl.version(fc);
        } finally {
            BetaflightUsb.use(null);
        }
        // what F-077 to F-079 are about needs Betaflight's MSP arming lock, which 4.5 on leaves out of SITL (msp.c,
        // #ifndef SIMULATOR_BUILD), and GPS rescue, which 4.3's SITL leaves out: on those it is skipped, not failed
        Assumptions.assumeTrue(version.startsWith("4.4."), "Betaflight " + version + " as a program: this scenario wants 4.4 (4.5 on leaves MSP's "
                + "arming lock out of SITL; 4.3 has no GPS rescue there)");
        AppRig.scenario("sitl-armed-writes").run(this::run);
    }

    private void run(AppRig rig) throws Exception {
        Film film = rig.film;
        Ui ui = rig.ui;
        t0 = System.currentTimeMillis();
        Tap tap = new Tap();
        Receiver rx = null;
        try {
            // ---- the board: Betaflight as a program, fresh, set up as the drone is, MSP on UART2 and UART4 for the rig
            film.add("a fresh board: " + Sitl.fresh(film::remark), null, "sitl", "");
            String version;
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                version = Sitl.version(fc);
            }
            film.add("Betaflight " + version + " as a program: UART1 on " + Sitl.HOST + ":" + Sitl.port() + " is the app's USB, UART2 on :" + UART2
                    + " the stand-in receiver's sticks, UART4 on :" + UART4 + " the rig's eyes", null, "sitl", Sitl.folder());
            rig.check(Sitl.startedVersion().isEmpty() || Sitl.startedVersion().equals(version), "the Betaflight that answers is the one the run started ("
                    + Sitl.startedVersion() + "): " + version);
            if (!version.startsWith("4.4.")) {
                rig.check(false, "this scenario wants Betaflight 4.4 as a program (4.5 on leaves MSP's arming block out of SITL; 4.3 has no GPS rescue there): "
                        + version + " answers - .\\bench.ps1 sitl runs 4.4.1");
                return;
            }
            film.add("set up as the drone is: " + String.join("; ", Sitl.likeTheDrone(100, film::remark)), null, "sitl", "saved");
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                fc.enterCli();
                Sitl.taken(fc, "serial 1 1 115200 57600 0 115200");
                Sitl.taken(fc, "serial 3 1 115200 57600 0 115200");
                Sitl.taken(fc, "set yaw_motors_reversed = OFF");
                fc.saveAndReboot();
            }
            film.add("MSP on UART2 and UART4 (serial 1 1 ..., serial 3 1 ...) for the rig; props in (yaw_motors_reversed = OFF); saved", null, "sitl", "");

            BetaflightUsb.use(tap); // the app's USB from here on: UART1, through the tap
            rx = new Receiver();
            Receiver r = rx;
            rig.check(within(30_000, () -> r.last() != null && r.last().flags() == 0), "the stand-in receiver's sticks reach Betaflight on UART2 and nothing keeps it "
                    + "from arming: " + r.blocks());
            byte[] mixer = rx.ask(Msp.MIXER_CONFIG, 1500);
            rig.check(mixer.length >= 2 && mixer[0] == MotorTest.MIXER_QUADX && mixer[1] == 0, "before anything: Quad X, props in in Betaflight's memory (MSP_MIXER_CONFIG "
                    + Msp.hex(mixer) + ")");

            appCode(rig, tap, rx);
            droneSetup(rig, ui, tap, rx, version);

            // ---- the end: nothing of the app's on the board's port any more, then Betaflight read on its own
            tap.unplug();
            rig.check(within(20_000, () -> !threadAlive("motor-test") && !threadAlive("orientation-live") && tap.quietFor(3000)),
                    "the app has let go of the board (no motor test, no live view, nothing sent for 3 s)");
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                byte[] status = fc.msp(Msp.STATUS, null);
                int flags = Msp.armingDisableFlags(status);
                rig.check(!Msp.armed(status) && (flags & MSP_BLOCK) == 0, "at the end Betaflight is disarmed and has no arming block MSP: " + Msp.armingBlocks(flags));
                fc.enterCli();
                String yaw = fc.command("get yaw_motors_reversed");
                rig.check(yaw.contains("yaw_motors_reversed = OFF"), "at the end Betaflight's saved yaw direction is props in, as at the start: "
                        + yaw.lines().findFirst().orElse(yaw));
            } // "exit": Betaflight restarts
            BetaflightUsb.use(tap); // unplugged: whatever of the app still looks finds no board
        } finally {
            if (rx != null) {
                rx.throttle(1000);
                rx.arm(false);
                Ui.pause(600);
                rx.close();
                if (rx.restarts.get() > 0) film.remark("The rig restarted SITL " + rx.restarts.get() + " time(s) for UART2 or UART4 that did not come back (uart2-status.tsv).");
                Files.write(film.dir().resolve("uart2-status.tsv"), rx.timeline(), StandardCharsets.UTF_8);
            }
            Files.write(film.dir().resolve("uart1-tap.tsv"), tap.lines(), StandardCharsets.UTF_8);
            leaveAsFound(rig, tap);
        }
    }

    /**
     * The suite's scenarios share one SITL and its eeprom.bin: this one leaves Betaflight's own defaults behind, as a
     * fresh run starts it - not MSP on UART2 and UART4, nor what CONFIRM wrote (PREARM on AUX3, which no other
     * scenario's sticks raise), nor its accelerometer calibration (the next scenario's set-up does its own). The
     * app first lets go of the board: its windows closed, the tap unplugged, its threads ended.
     */
    private void leaveAsFound(AppRig rig, Tap tap) {
        tap.unplug();
        for (String title : new String[]{"Motor test", "New drone"}) {
            try {
                Window w = rig.ui.window(title);
                if (w != null) Ui.edt(() -> {
                    w.dispose();
                    return null;
                });
            } catch (RuntimeException ignored) {
                // closed already
            }
        }
        within(20_000, () -> !threadAlive("motor-test") && !threadAlive("orientation-live") && tap.quietFor(2000));
        try {
            String serial = Sitl.toDefaults(rig.film::remark);
            rig.check(serial.contains("serial 1 0 ") && serial.contains("serial 3 0 "), "the board is left with Betaflight's defaults for the next scenario "
                    + "(UART2 and UART4 without MSP again): " + serial.replaceAll("\\s+", " "));
        } catch (IOException e) {
            rig.check(false, "the board is left with Betaflight's defaults for the next scenario: " + e.getMessage());
        } finally {
            BetaflightUsb.use(tap); // unplugged: whatever of the app still looks finds no board
        }
    }

    // ------------------------------------------------------------------ the app's own setup code, no window

    private void appCode(AppRig rig, Tap tap, Receiver rx) throws Exception {
        Film film = rig.film;
        long from;
        try (MotorTest test = MotorTest.open(Sitl.BOARD)) {
            film.add("the app's setup code: a motor test opened while disarmed (" + test.info() + ")", null, "sitl", "");
            from = arm(rig, rx, "with the motor test open");
            String props = refusal(() -> test.setPropsOutAndRestart(true));
            rig.check(ARMED.equals(props), "armed: PROPS OUT (MotorTest.setPropsOutAndRestart) is refused with the fix's words: " + props);
            byte[] mixer = rx.ask(Msp.MIXER_CONFIG, 1500);
            rig.check(mixer.length >= 2 && mixer[1] == 0, "armed: after PROPS OUT Betaflight's yaw direction in its memory is still props in (MSP_MIXER_CONFIG "
                    + Msp.hex(mixer) + ")");
            String order = refusal(() -> test.setOutputOrderAndRestart(new int[]{2, 1, 4, 3}));
            rig.check(ARMED.equals(order), "armed: a new motor order (MotorTest.setOutputOrderAndRestart) is refused with the fix's words: " + order);
            String spin = refusal(() -> test.setDirection(1, true));
            rig.check(spin != null, "armed: a spin direction (MotorTest.setDirection) is refused, not done: " + spin
                    + (test.info().dshot() ? "" : " - SITL drives its motors by PWM only: the app's DShot check says no before its armed check is reached"));
        }
        try (BetaflightUsb fc = BetaflightUsb.open(Sitl.BOARD)) {
            rig.check(fc.armed(), "armed: the board says so on the app's USB (BetaflightUsb.armed)");
            String cli = refusal(fc::enterCli);
            rig.check(ARMED.equals(cli), "armed: entering Betaflight's CLI (BetaflightUsb.enterCli, the one door of every CLI write) is refused with the fix's words: " + cli);
        }
        long to = disarm(rig, rx);
        noWrites(rig, tap, from, to, "the app's setup code");
    }

    // ------------------------------------------------------------------ the app: SETUP > Set up a new drone

    private void droneSetup(AppRig rig, Ui ui, Tap tap, Receiver rx, String version) throws Exception {
        Film film = rig.film;
        List<Component> setup = ui.all("SETUP");
        if (setup.isEmpty() || ui.whyNotPressable(setup.get(0), null) != null) ui.click("MENU"); // a narrow window folds its header into MENU
        ui.click("SETUP");
        ui.until("SETUP's menu to slide out", 8000, () -> ui.has("Set up a new drone"));
        ui.click("Set up a new drone");
        ui.until("Drone setup's first step", 15_000, () -> ui.window("New drone") != null);
        ui.until("the first step to read Betaflight and offer CONFIRM", 150_000, () -> confirmOffered(ui));
        boolean rests = within(150_000, () -> tap.quietFor(8000) && !Ui.edt(() -> ui.buttons().contains("[NEXT]")));
        rig.check(rests, "disarmed: the first step has read Betaflight (" + tap.opens() + " visits to UART1) and rests, CONFIRM offered");
        ui.shot("Drone setup's first step: Betaflight read, CONFIRM offered");

        // ---- the second step: the motor test opened disarmed, then the board armed (F-077)
        ui.click("NEXT");
        ui.until("the Motors and orientation step", 10_000, () -> ui.sees("Motors and orientation"));
        long shown = System.currentTimeMillis();
        rig.check(within(20_000, () -> tap.asked(shown, MSP_ATTITUDE)), "the second step's live view follows Betaflight (MSP_ATTITUDE on UART1)");
        ui.click("Check now");
        ui.until("the motor test window", 10_000, () -> ui.window("Motor test") != null);
        Window motorTest = ui.window("Motor test");
        boolean found = within(20_000, () -> texts(motorTest).stream().anyMatch(t -> t.contains("Betaflight " + version)));
        rig.check(found, "the motor test finds Betaflight " + version + " while disarmed");
        Component props = Ui.edt(() -> first(motorTest, c -> c.getClass().getSimpleName().equals("DirectionSwitch")));
        rig.check(props != null && Ui.edt(props::isEnabled), "disarmed: PROPS IN / OUT can be pressed");
        long from = arm(rig, rx, "with Drone setup's motor test open");
        boolean grey = within(6000, () -> texts(motorTest).stream().anyMatch(t -> t.contains("ARMED - motor test stopped. Disarm."))
                && props != null && !Ui.edt(props::isEnabled));
        rig.check(grey, "armed: the motor test says ARMED - motor test stopped. Disarm. and PROPS IN / OUT is grey");
        ui.shot("armed: the motor test, PROPS IN / OUT grey");
        if (props != null) {
            Point outHalf = Ui.edt(() -> new Point(props.getWidth() * 3 / 4, props.getHeight() / 2));
            String why = ui.whyNotPressable(props, outHalf);
            rig.check("it is greyed".equals(why), "armed: a pilot cannot press PROPS OUT (" + why + ")");
            // a press all the same, straight into the switch: what a click that came through would do
            Ui.edt(() -> {
                props.dispatchEvent(new MouseEvent(props, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, outHalf.x, outHalf.y, 1, false, MouseEvent.BUTTON1));
                return null;
            });
            Ui.pause(1500);
            boolean asked = ui.window("Props in / out") != null;
            rig.check(!asked, "armed: a press on PROPS OUT all the same asks nothing (no \"Set Betaflight to props out?\")");
            if (asked) ui.click("No");
        }
        byte[] mixer = rx.ask(Msp.MIXER_CONFIG, 1500);
        rig.check(mixer.length >= 2 && mixer[1] == 0, "armed: after the press Betaflight's yaw direction in its memory is still props in (MSP_MIXER_CONFIG "
                + Msp.hex(mixer) + ")");
        AbstractButton reorder = Ui.edt(() -> (AbstractButton) first(motorTest, c -> c instanceof AbstractButton b && "reorder motors".equalsIgnoreCase(Ui.textOf(b))));
        rig.check(reorder != null && !Ui.edt(reorder::isEnabled), "armed: REORDER MOTORS is grey");
        List<Component> lists = Ui.edt(() -> all(motorTest, c -> c instanceof JComboBox));
        if (lists.isEmpty()) {
            film.remark("No spin direction lists in the motor test: SITL's motors are PWM, and the app offers a spin direction for DShot only. "
                    + "The direction's own armed check is the one MotorTest.setDirection makes (the app's setup code, above).");
        } else {
            rig.check(Ui.edt(() -> lists.stream().noneMatch(Component::isEnabled)), "armed: every spin direction list is grey");
        }
        AbstractButton close = Ui.edt(() -> (AbstractButton) first(motorTest, c -> c instanceof AbstractButton b && "close".equalsIgnoreCase(Ui.textOf(b))));
        if (close == null) throw new AssertionError("The motor test has no Close button");
        // the window lies beside a setup guide that blocks the app's other windows but not this one (its modal exclusion),
        // which the bench's hands don't know: its Close is pressed on the Swing thread
        Ui.edt(() -> {
            close.doClick();
            return null;
        });
        ui.until("the motor test to close", 10_000, () -> ui.window("Motor test") == null);
        rig.check(within(10_000, () -> !threadAlive("motor-test")), "the motor test has let go of the board");

        // ---- CALIBRATE on the armed board (F-079)
        long closed = System.currentTimeMillis();
        rig.check(within(20_000, () -> tap.asked(closed, MSP_ATTITUDE)), "the live view has the board again");
        ui.click("Calibrate");
        boolean said = within(15_000, () -> ui.sees(ARMED) || ui.sees("Calibrated: reads flat"));
        rig.check(said && ui.sees(ARMED) && !ui.sees("Calibrated: reads flat"), "armed: CALIBRATE says \"" + ARMED + "\" - not \"Calibrated: reads flat.\": "
                + lines(ui, "armed", "Calibrat"));
        ui.shot("armed: CALIBRATE");

        // ---- CONFIRM on the armed board (F-078)
        ui.click("BACK");
        ui.until("Drone setup's first step again, CONFIRM there", 10_000, () -> confirmOffered(ui));
        long pressed = System.currentTimeMillis();
        int opensBefore = tap.opens();
        ui.click("CONFIRM");
        Set<String> seen = new LinkedHashSet<>();
        long end = System.currentTimeMillis() + 100_000;
        int lastRound = -1;
        long lastRoundAt = 0;
        while (System.currentTimeMillis() < end) {
            seen.addAll(lines(ui, "armed", "No answer", "Replug", "running Betaflight", "Nothing written"));
            int rounds = tap.opens() - opensBefore;
            if (rounds != lastRound) {
                lastRound = rounds;
                lastRoundAt = System.currentTimeMillis();
            }
            if (rounds >= 4 && System.currentTimeMillis() - lastRoundAt > 3000) break;
            Ui.pause(700);
        }
        int rounds = tap.opens() - opensBefore;
        film.remark("While armed, after CONFIRM, the page said: " + seen);
        rig.check(rounds >= 3, "armed: after CONFIRM the page tried " + rounds + " times (3 is where it used to say Replug)");
        rig.check(seen.stream().anyMatch(s -> s.contains(ARMED)), "armed: after CONFIRM the page says \"" + ARMED + "\"");
        rig.check(seen.stream().noneMatch(s -> s.contains("No answer") || s.contains("Replug") || s.contains("running Betaflight")),
                "armed: never \"No answer from the flight controller - is it running Betaflight?\" or \"Replug the drone's USB.\"");
        ui.shot("armed: CONFIRM pressed - the page says why nothing is written");
        long to = disarm(rig, rx);
        film.remark("CONFIRM pressed at " + seconds(pressed) + ", armed until " + seconds(to) + ".");
        noWrites(rig, tap, from, to, "Drone setup: motor test, CALIBRATE, CONFIRM");

        // ---- disarmed: CONFIRM's write goes through, arming blocked while the CLI is open (F-078)
        boolean saved = within(150_000, () -> tap.firstAfter(to, "cli", "save") != null);
        rig.check(saved, "disarmed: CONFIRM writes and saves (the CLI's \"save\" on UART1)");
        if (saved) {
            Tap.Event save = tap.firstAfter(to, "cli", "save");
            List<Tap.Event> visit = tap.visit(save.conn());
            Tap.Event block = null, entry = null, status = null;
            for (Tap.Event e : visit) {
                if (block == null && e.kind().equals("msp") && e.code() == Msp.ARMING_DISABLE && e.text().equals("01")) block = e;
                if (entry == null && e.kind().equals("cli-entry")) entry = e;
                if (e.kind().equals("cli-status")) status = e;
            }
            rig.check(block != null && entry != null && block.atMs() <= entry.atMs(), "disarmed: CONFIRM blocks arming (MSP_SET_ARMING_DISABLED 1) before the CLI entry '#'");
            rig.check(status != null && blocks(status.text()).contains("MSP"), "disarmed: while the CLI is open Betaflight's own status lists the arming block MSP: "
                    + (status == null ? "not read" : status.text()));
            film.add("CONFIRM's visit to UART1: " + visit.size() + " requests and lines, \"save\" at " + seconds(save.atMs()), null, "sitl", status == null ? "" : status.text());
            Receiver r = rx;
            boolean free = within(30_000, () -> {
                Receiver.Seen s = r.firstAfter(save.atMs());
                return s != null && s.flags() != -1;
            });
            Receiver.Seen after = rx.firstAfter(save.atMs());
            rig.check(free && after != null && (after.flags() & MSP_BLOCK) == 0, "after the restart the arming block MSP is gone (the first status after it: "
                    + (after == null ? "none" : Msp.armingBlocks(after.flags())) + ")");
        }
        rig.check(within(30_000, () -> rig.logged("Its settings before:")), "disarmed: the app's log says what CONFIRM wrote and where the backup is");
        boolean settled = within(150_000, () -> tap.quietFor(8000));
        film.remark("After CONFIRM the page says: " + lines(ui, "SITL", "Nothing written", "Done", "armed"));
        rig.check(settled && !ui.sees("Nothing written"), "disarmed: CONFIRM's write went through and the page rests (no \"Nothing written\")");
        // every visit of the app to Betaflight's CLI: the block on while it was open
        List<String> statuses = tap.statuses();
        rig.check(!statuses.isEmpty() && statuses.stream().allMatch(s -> blocks(s).contains("MSP")), "every one of the app's " + statuses.size()
                + " visits to the CLI had the arming block MSP on while the CLI was open");
        Receiver.Seen last = rx.last();
        rig.check(last != null && last.flags() != -1 && (last.flags() & MSP_BLOCK) == 0, "the page at rest: no arming block MSP left behind ("
                + (last == null ? "?" : Msp.armingBlocks(last.flags())) + ")");
        ui.shot("disarmed: CONFIRM written");
        ui.click("Close");
        ui.until("Drone setup to close", 10_000, () -> ui.window("New drone") == null);
    }

    // ------------------------------------------------------------------ arming, as a radio does it

    /**
     * ARM up with the throttle down, armed by Betaflight's own word; then the throttle a little up (no auto-disarm). The
     * spell's start. SITL drops its receiver for a tenth of a second now and then (RXLOSS: its own gaps, the sticks
     * sent on time); one that comes while the switch is up leaves BADRX and ARMSWITCH, and the switch goes down and up
     * again, as a pilot would, once nothing but a passing RXLOSS holds the board.
     */
    private long arm(AppRig rig, Receiver rx, String when) {
        rx.throttle(1000);
        int passing = 1 << Msp.ARMING_DISABLED_RXLOSS_BIT;
        boolean armed = false;
        for (int flip = 1; flip <= 6 && !armed; flip++) {
            rx.arm(false);
            within(8000, () -> rx.last() != null && rx.last().flags() != -1 && (rx.last().flags() & ~passing) == 0);
            Ui.pause(300);
            rx.arm(true);
            armed = within(3000, () -> rx.last() != null && rx.last().armed());
            if (!armed) rig.film.remark("The ARM switch up did not arm Betaflight " + when + " (" + rx.blocks() + "): down and up again.");
        }
        if (!armed) throw new AssertionError("Betaflight did not arm " + when + " in six flips of the ARM switch: " + rx.blocks());
        rx.throttle(1050);
        long at = System.currentTimeMillis();
        armedSpells.add(new long[]{at, Long.MAX_VALUE});
        rig.film.add("ARMED " + when + " (the stand-in receiver: ARM up, throttle down, then 1050)", null, "sitl", rx.blocks());
        return at;
    }

    /** Throttle down, ARM down; disarmed by Betaflight's word. The spell's end: the moment the switch went down. */
    private long disarm(AppRig rig, Receiver rx) {
        long at = System.currentTimeMillis();
        armedSpells.get(armedSpells.size() - 1)[1] = at;
        rx.throttle(1000);
        rx.arm(false);
        boolean off = within(5000, () -> rx.last() != null && !rx.last().armed() && rx.last().atMs() > at);
        rig.check(off, "the ARM switch down disarms Betaflight: " + rx.blocks());
        return at;
    }

    /** Nothing that writes went to the board on UART1 between these times, and Betaflight stayed armed meanwhile. */
    private void noWrites(AppRig rig, Tap tap, long from, long to, String what) {
        List<Tap.Event> hashes = new ArrayList<>(), writes = new ArrayList<>();
        for (Tap.Event e : tap.between(from, to)) {
            if (e.kind().equals("cli-entry")) hashes.add(e);
            if (e.kind().equals("msp") && WRITES_V1.contains(e.code()) || e.kind().equals("msp2") && WRITES_V2.contains(e.code())) writes.add(e);
        }
        rig.check(hashes.isEmpty(), "while armed (" + what + ", " + seconds(from) + " to " + seconds(to) + ") the app never sent the CLI entry '#': " + hashes);
        rig.check(writes.isEmpty(), "while armed (" + what + ") no MSP write reached the board - arming block, feature, mixer, motor order, calibration, "
                + "EEPROM, reboot, passthrough, DShot: " + writes);
        rig.check(tap.between(from, to).stream().anyMatch(e -> e.kind().startsWith("msp")), "while armed (" + what + ") the app did talk to the board on UART1: "
                + tap.between(from, to).size() + " requests");
    }

    // ------------------------------------------------------------------ small helpers

    private interface Step {
        void run() throws Exception;
    }

    /** The message a refusal gave, or null when the step went through. */
    private static String refusal(Step step) {
        try {
            step.run();
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * Whether it is so within ms - the shared helper's wait (Waits), looked at ten times a second as before: a look that
     * throws (an answer not there yet, a window being laid out) counts as "not yet", and the last look comes after the
     * time is up. Interrupted (the scenario's time is up): false at once.
     */
    private static boolean within(long ms, BooleanSupplier ok) {
        try {
            return Waits.until(() -> {
                try {
                    return ok.getAsBoolean();
                } catch (RuntimeException e) {
                    return false;
                }
            }, ms, 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean threadAlive(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().equals(name)) return true;
        return false;
    }

    private static boolean confirmOffered(Ui ui) {
        for (Component c : ui.all("CONFIRM")) if (c instanceof AbstractButton b && b.isEnabled()) return true;
        return false;
    }

    /** The arming blocks in a CLI "status" line ("Arming disable flags: CLI MSP"), each as a word. */
    private static Set<String> blocks(String statusLine) {
        String s = statusLine.replaceFirst("(?s).*Arming disable flags:", "").trim();
        return s.isEmpty() ? Set.of() : Set.of(s.split("\\s+"));
    }

    /** What the showing windows say that holds any of these words - but the main window's LOG (its lines begin with the time). */
    private static List<String> lines(Ui ui, String... words) {
        List<String> out = new ArrayList<>();
        for (String t : ui.texts()) {
            if (t.matches("(?s)\\d\\d:\\d\\d:\\d\\d .*")) continue;
            for (String w : words) {
                if (t.toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT))) {
                    out.add(t);
                    break;
                }
            }
        }
        return out;
    }

    /** Every text in one window, plain (Swing thread taken here). */
    private static List<String> texts(Window w) {
        return Ui.edt(() -> {
            List<String> out = new ArrayList<>();
            for (Component c : all(w, x -> true)) {
                String t = Ui.textOf(c);
                if (t != null && !t.isBlank()) out.add(t);
            }
            return out;
        });
    }

    /** The showing parts of this window that are of a kind (on the Swing thread). */
    private static List<Component> all(Container root, Predicate<Component> kind) {
        List<Component> out = new ArrayList<>();
        collect(root, kind, out);
        return out;
    }

    private static void collect(Component c, Predicate<Component> kind, List<Component> out) {
        if (!c.isVisible()) return;
        if (kind.test(c)) out.add(c);
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, kind, out);
    }

    private static Component first(Container root, Predicate<Component> kind) {
        List<Component> found = all(root, kind);
        return found.isEmpty() ? null : found.get(0);
    }

    private String seconds(long atMs) {
        return String.format(Locale.ROOT, "%.1f s", (atMs - t0) / 1000.0);
    }

    // ------------------------------------------------------------------ UART1: the app's USB, through a tap

    /**
     * The bench's USB with SITL's UART1 on it, every connection through a tap: each MSP request the app sends (v1 and
     * v2, with its payload), each CLI line, the CLI's entry '#'. Before the app's "save" or "exit" in an open CLI the tap
     * asks Betaflight's "status" itself and keeps its arming blocks - a read, while the CLI is open (no MSP is answered
     * then). Unplugged, it has no port.
     */
    final class Tap implements BetaflightUsb.Usb {
        record Event(long atMs, int conn, String kind, int code, String text) {
        }

        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();
        private volatile boolean plugged = true;
        private volatile long lastAt = System.currentTimeMillis();

        @Override
        public List<BetaflightUsb.Port> ports() {
            return plugged ? List.of(Sitl.BOARD) : List.of();
        }

        @Override
        public BetaflightUsb.Wire open(BetaflightUsb.Port port) throws IOException {
            if (!plugged || !port.systemName().equals(Sitl.BOARD.systemName())) throw new IOException(port.systemName() + " is not there");
            BetaflightUsb.Wire wire = FcKeeper.tcp(Sitl.HOST, Sitl.port()).open();
            try {
                Sitl.clean(wire);
            } catch (IOException e) {
                wire.close();
                throw e;
            }
            int id = connections.incrementAndGet();
            add(id, "open", 0, "");
            return new TapWire(wire, id);
        }

        void unplug() {
            plugged = false;
        }

        private void add(int conn, String kind, int code, String text) {
            long now = System.currentTimeMillis();
            lastAt = now;
            events.add(new Event(now, conn, kind, code, text));
        }

        int opens() {
            return connections.get();
        }

        boolean quietFor(long ms) {
            return System.currentTimeMillis() - lastAt >= ms;
        }

        boolean asked(long since, int code) {
            for (Event e : events) if (e.atMs() >= since && e.kind().equals("msp") && e.code() == code) return true;
            return false;
        }

        List<Event> between(long from, long to) {
            List<Event> out = new ArrayList<>();
            for (Event e : events) if (e.atMs() >= from && e.atMs() <= to) out.add(e);
            return out;
        }

        Event firstAfter(long since, String kind, String text) {
            for (Event e : events) if (e.atMs() >= since && e.kind().equals(kind) && e.text().equals(text)) return e;
            return null;
        }

        List<Event> visit(int conn) {
            List<Event> out = new ArrayList<>();
            for (Event e : events) if (e.conn() == conn) out.add(e);
            return out;
        }

        List<String> statuses() {
            List<String> out = new ArrayList<>();
            for (Event e : events) if (e.kind().equals("cli-status")) out.add(e.text());
            return out;
        }

        List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add("s\tconnection\tkind\tcode\twhat\tarmed spell");
            for (Event e : events) {
                boolean inSpell = false;
                for (long[] spell : armedSpells) inSpell |= e.atMs() >= spell[0] && e.atMs() <= spell[1];
                out.add(String.format(Locale.ROOT, "%.3f\t%d\t%s\t%s\t%s\t%s", (e.atMs() - t0) / 1000.0, e.conn(), e.kind(),
                        e.kind().equals("msp2") ? "0x" + Integer.toHexString(e.code()) : Integer.toString(e.code()), e.text(), inSpell ? "ARMED" : ""));
            }
            return out;
        }

        private final class TapWire implements BetaflightUsb.Wire {
            private final BetaflightUsb.Wire wire;
            private final int id;
            private final MspFrames frames = new MspFrames();
            private boolean cliOpen;

            TapWire(BetaflightUsb.Wire wire, int id) {
                this.wire = wire;
                this.id = id;
            }

            @Override
            public int read(byte[] buf) throws IOException {
                return wire.read(buf);
            }

            @Override
            public int available() throws IOException {
                return wire.available();
            }

            @Override
            public void write(byte[] data) throws IOException {
                if (data.length > 0 && data[0] == MspFrames.START) {
                    for (MspFrames.Frame f : frames.feed(data, data.length)) {
                        if (f.type != MspFrames.REQUEST) continue;
                        add(id, f.version == MspFrames.V2 ? "msp2" : "msp", f.code, f.payload == null ? "" : Msp.hex(f.payload));
                    }
                } else {
                    String text = new String(data, StandardCharsets.US_ASCII);
                    if (text.equals("#")) {
                        add(id, "cli-entry", 0, "#");
                        cliOpen = true;
                    } else {
                        String line = text.strip();
                        boolean leaving = line.equals("save") || line.equals("exit");
                        if (cliOpen && leaving) add(id, "cli-status", 0, statusInCli());
                        add(id, "cli", 0, line);
                        if (leaving) cliOpen = false;
                    }
                }
                wire.write(data);
            }

            /** Betaflight's "status" in the open CLI, read to its prompt: the line of arming blocks. */
            private String statusInCli() {
                try {
                    wire.write("status\n".getBytes(StandardCharsets.US_ASCII));
                    StringBuilder said = new StringBuilder();
                    byte[] buf = new byte[2048];
                    long end = System.currentTimeMillis() + 2500, quietSince = System.currentTimeMillis();
                    while (System.currentTimeMillis() < end) {
                        int n = wire.read(buf);
                        if (n < 0) break;
                        if (n > 0) {
                            said.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                            quietSince = System.currentTimeMillis();
                        } else if (said.toString().endsWith("\n# ") && System.currentTimeMillis() - quietSince >= 100) {
                            break;
                        }
                    }
                    for (String l : said.toString().split("\\R")) if (l.startsWith("Arming disable flags:")) return l.trim();
                    return "no arming line in the status: " + said.toString().replaceAll("\\s+", " ").trim();
                } catch (IOException e) {
                    return "status not read: " + e.getMessage();
                }
            }

            @Override
            public boolean isOpen() {
                return wire.isOpen();
            }

            @Override
            public void close() {
                add(id, "close", 0, "");
                wire.close();
            }
        }
    }

    // ------------------------------------------------------------------ UART2 and UART4: the stand-in receiver, the rig's eyes

    /**
     * SITL's UART2 as a radio's receiver (MSP_SET_RAW_RC, 25 a second: SitlLink, nothing else asked there) and UART4 as
     * the rig's eyes: Betaflight's status about 8 times a second, each change into the timeline, and the rig's other
     * questions. Every restart of Betaflight (the app's CLI visits end in one) ends both connections: they are made
     * again at once, the sticks as they were. While the app has the CLI open Betaflight answers no MSP on any port and
     * takes no stick frame: then the status has a gap.
     */
    final class Receiver implements AutoCloseable {
        record Seen(long atMs, int connection, boolean armed, int flags) {
        }

        private final List<String> timeline = new CopyOnWriteArrayList<>();
        private final List<Seen> seen = new CopyOnWriteArrayList<>();
        private final Thread thread;
        private volatile boolean closed, armOn;
        private volatile int throttle = 1000;
        /** UART4, the eyes; and UART2, the sticks. */
        private volatile SitlLink link, sticks;
        private volatile Seen last;
        private int connections;

        Receiver() {
            timeline.add("s\tconnection\tarmed\tarming blocks");
            thread = new Thread(this::run, "sitlReceiver");
            thread.setDaemon(true);
            thread.start();
        }

        /** Both ports had Betaflight's word last at this time (or the start); the last restart this rig asked for. */
        private long workingAt = System.currentTimeMillis(), restartedAt;

        private void run() {
            while (!closed) {
                long at = System.currentTimeMillis();
                if (at - workingAt > NOT_BACK_MS && at - restartedAt > 30_000) {
                    restartedAt = at;
                    restartThroughAnother();
                }
                SitlLink s2, l;
                try {
                    s2 = new SitlLink(UART2);
                } catch (IOException e) {
                    Ui.pause(200);
                    continue;
                }
                try {
                    l = new SitlLink(UART4);
                } catch (IOException e) {
                    s2.close();
                    Ui.pause(200);
                    continue;
                }
                int n = ++connections;
                timeline.add(stamp() + "\t" + n + "\tconnected\t");
                s2.sticks(throttle, armOn ? 2000 : 1000).startSticks();
                sticks = s2;
                link = l;
                Ui.pause(150); // what SITL still had for the connection before it is let by
                boolean silent = false;
                while (!closed && !l.gone() && !s2.gone()) {
                    s2.sticks(throttle, armOn ? 2000 : 1000);
                    try {
                        byte[] s = l.ask(Msp.STATUS, null, 600);
                        workingAt = System.currentTimeMillis();
                        silent = false;
                        Seen now = new Seen(System.currentTimeMillis(), n, Msp.armed(s), Msp.armingDisableFlags(s));
                        Seen before = last;
                        seen.add(now);
                        last = now;
                        if (before == null || before.connection() != n || before.armed() != now.armed() || before.flags() != now.flags()) {
                            timeline.add(stamp() + "\t" + n + "\t" + (now.armed() ? "ARMED" : "disarmed") + "\t" + Msp.armingBlocks(now.flags()));
                        }
                    } catch (IOException e) {
                        if (l.gone() || s2.gone()) break;
                        if (!silent) timeline.add(stamp() + "\t" + n + "\tno answer\t(" + e.getMessage() + ")");
                        silent = true;
                    }
                    Ui.pause(100);
                }
                link = null;
                sticks = null;
                l.close();
                s2.close();
                timeline.add(stamp() + "\t" + n + "\tended\t(Betaflight restarted)");
            }
        }

        /**
         * UART2 or UART4 has not come back for NOT_BACK_MS: once after a restart SITL could not open UART2 again
         * ("bind port 5762 for UART2 failed!!" in its log, 2026-11-23) and never would until it restarts. Asked over
         * the port that answers - not of an armed board - with MSP's reboot: SITL starts again, that UART with it.
         */
        private void restartThroughAnother() {
            for (int port : new int[]{UART4, UART2, Sitl.port()}) {
                try (SitlLink r = new SitlLink(port)) {
                    Ui.pause(200);
                    if (r.gone()) continue;
                    byte[] s = r.ask(Msp.STATUS, null, 1500);
                    if (Msp.armed(s)) {
                        timeline.add(stamp() + "\t\tUART2 or UART4 not back for " + NOT_BACK_MS / 1000 + " s, the board ARMED: not restarted\t");
                        return;
                    }
                    try {
                        r.ask(Msp.REBOOT, null, 1500);
                    } catch (IOException restarting) {
                        // gone before its answer: it restarts
                    }
                    restarts.incrementAndGet();
                    timeline.add(stamp() + "\t\tUART2 or UART4 not back for " + NOT_BACK_MS / 1000 + " s (SITL could not open it again): MSP reboot over :" + port + "\t");
                    return;
                } catch (IOException e) {
                    // not this port
                }
            }
            timeline.add(stamp() + "\t\tUART2 or UART4 not back for " + NOT_BACK_MS / 1000 + " s, and no port took MSP's reboot\t");
        }

        /** How often the rig restarted SITL for a UART that did not come back. */
        final AtomicInteger restarts = new AtomicInteger();

        private String stamp() {
            return String.format(Locale.ROOT, "%.3f", (System.currentTimeMillis() - t0) / 1000.0);
        }

        void arm(boolean on) {
            armOn = on;
            SitlLink l = sticks;
            if (l != null) l.sticks(throttle, on ? 2000 : 1000);
            timeline.add(stamp() + "\t\tARM switch " + (on ? "up" : "down") + "\tthrottle " + throttle);
        }

        void throttle(int value) {
            throttle = value;
            SitlLink l = sticks;
            if (l != null) l.sticks(value, armOn ? 2000 : 1000);
        }

        Seen last() {
            return last;
        }

        /** The first status after this moment from a connection made after it (Betaflight restarted since). */
        Seen firstAfter(long atMs) {
            int connectionThen = -1;
            for (Seen s : seen) if (s.atMs() <= atMs) connectionThen = s.connection();
            for (Seen s : seen) if (s.atMs() > atMs && s.connection() != connectionThen) return s;
            return null;
        }

        String blocks() {
            Seen s = last;
            return s == null ? "no status from UART4 yet" : (s.armed() ? "armed, " : "disarmed, ") + "arming blocks " + Msp.armingBlocks(s.flags());
        }

        byte[] ask(int code, long ms) throws IOException {
            long end = System.currentTimeMillis() + ms + 5000;
            IOException failed = null;
            while (System.currentTimeMillis() < end) { // across a restart: the next connection
                SitlLink l = link;
                if (l == null) {
                    Ui.pause(100);
                    continue;
                }
                try {
                    return l.ask(code, null, ms);
                } catch (IOException e) {
                    failed = e;
                    Ui.pause(100);
                }
            }
            throw failed != null ? failed : new IOException("UART4 not connected");
        }

        List<String> timeline() {
            return timeline;
        }

        @Override
        public void close() {
            closed = true;
            SitlLink l = link, s2 = sticks;
            if (l != null) l.close();
            if (s2 != null) s2.close();
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
