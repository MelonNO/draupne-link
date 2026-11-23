package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Film;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.gui.BenchAccess;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Waits;

import javax.swing.AbstractButton;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One go through the app's Drone setup on a flight controller behind the keeper, as a pilot does it - SETUP &gt; Set up
 * a new drone, the drone's two steps - with every row's words into the film, CONFIRM, and the board read before and
 * after and held to what Drone setup said it changed. {@link HwDroneSetupScenario} on the real board (battery out,
 * props off); {@link HwDroneSetupSelfTest} on the made-up board behind a real keeper. See HwDroneSetupScenario for
 * what is pressed and what never is.
 * <p>
 * Before the app starts ({@link #before}): the keeper has the board, disarmed and locked; the board's settings as they
 * are (BoardSnapshot: the MSP facts, diff all, dump all) into the run's folder; the questions a pilot answers for this
 * drone (GPS, its UART, the compass, the cells) answered as the board has them ({@link HwDroneSetup#choices}). In the
 * app ({@link #run}): the app's USB is the keeper's lease for Drone setup, every request on it written down
 * ({@link SetupTap}).
 */
final class DroneSetupRun {
    /** A real board behind the keeper: after each CLI visit it restarts, and the keeper reads its settings again before the next lease. */
    static final BoardPort.Timing THROUGH_KEEPER = new BoardPort.Timing(500, 90_000);
    static final String GUIDE = "New drone";
    /** Drone setup's first step when it rests: the round is over and nothing runs (BetaflightPage.status). */
    static final String SET_UP = "Betaflight set up - unplug the drone.", TO_CONFIRM = "Set everything, then Confirm.",
            NOT_ALL = "Some settings could not be set - see By hand";
    /** CONFIRM's write stopped and nothing was saved: the page says why under its settings. */
    private static final String NOTHING_WRITTEN = "Nothing was written";
    private static final List<String> RESTING = List.of(SET_UP, TO_CONFIRM, NOT_ALL);
    /** What says a setting is not as chosen after a write (DroneSetup's rows and footer). */
    private static final List<String> NOT_SET = List.of("Could not set it", "Not set -", "Not changed (", "Still not set", "No GPS found", "Needs a GPS",
            "Turning GPS on");
    /** Of those, what only says no GPS answered: with the battery out the GPS may have no power. */
    private static final List<String> NO_GPS = List.of("No GPS found", "Needs a GPS", "Turning GPS on");
    private static final BetaflightUsb.Port BOARD = new BetaflightUsb.Port(KeeperWire.PORT_NAME, KeeperWire.DESCRIPTION);
    private static final long READ_MS = 240_000, CONFIRM_MS = 360_000;

    static final String SKIPPED = "NOT PRESSED in this run, and why: the motor test's risk box, its sliders and its motors in the picture, REORDER MOTORS, "
            + "PROPS IN / OUT and the spin directions (they turn motors or need the battery; the motor test window is opened to read its rows and "
            + "closed again); READ ESCS and BEACON OFF (the ESCs' own memory through their passthrough - F-080 has its own probes and rules); "
            + "FIND GPS (it writes and saves a UART a try; a GPS answers only with power); CALIBRATE COMPASS and the orientation's CHECK IT "
            + "(the drone is turned by hand); USE MEASURED (no battery in); READ AGAIN / RETRY and DISCONNECT (they only read again, or free the "
            + "port). CALIBRATE (the accelerometer) is pressed only on a drone that reads still and flat within the app's own 3 degrees.";

    private final int keeperPort, fileCells;
    private final Path folder;
    private final BoardPort.Timing timing;
    /** The real board: its attitude is read by the live view (the made-up board has no MSP_ATTITUDE). */
    private final boolean realBoard;
    private int setupPort;
    private KeeperClient.State atStart;
    private BoardSnapshot before, after;
    private HwDroneSetup.Choices choices;

    private AppRig rig;
    private Ui ui;
    private Film film;
    private KeeperClient keeper;
    private SetupTap tap;
    private long t0;
    private boolean calibratePressed, confirmPressed;
    private final List<String> rows = new ArrayList<>();
    private List<String> written = List.of(), said = List.of();
    private HwDroneSetup.Verdict verdict;

    /**
     * @param keeperPort the keeper's control port
     * @param fileCells  the pack's cells as the hardware file has them (0: it names none): for a board that counts them itself
     * @param folder     where the snapshots go (the run's folder)
     * @param timing     how long the board may take to be the keeper's again after a CLI visit
     * @param realBoard  the real board ({@link HwDroneSetupScenario}); false: the made-up one, which has no attitude to read
     */
    DroneSetupRun(int keeperPort, int fileCells, Path folder, BoardPort.Timing timing, boolean realBoard) {
        this.keeperPort = keeperPort;
        this.fileCells = fileCells;
        this.folder = folder;
        this.timing = timing;
        this.realBoard = realBoard;
    }

    // ------------------------------------------------------------------ before the app

    /**
     * Before anything: the keeper has the board (taken, disarmed, locked, no window, not tripped); the board's settings
     * as they are, read through Drone setup's lease and filed in the run's folder; the choices from them. Fails with
     * why when any of it is not so.
     */
    void before(Consumer<String> say) throws IOException {
        try (KeeperClient k = new KeeperClient(keeperPort, 30_000)) {
            keeper = k;
            if (!within(60_000, () -> state().boardUp())) throw new AssertionError("The keeper has no board (on USB? its port the hardware file's board.port?): " + state());
            KeeperClient.State s = state();
            if (s.tripped() != null) throw new AssertionError("The keeper has tripped and was not cleared: " + s.tripped());
            if (!Boolean.FALSE.equals(s.armed()) || !Boolean.TRUE.equals(s.locked()) || s.windowMs() > 0) {
                throw new AssertionError("The board is not disarmed and locked, no window open, before the scenario begins: " + s);
            }
            atStart = s;
            setupPort = s.number("setupPort");
            if (setupPort <= 0) throw new AssertionError("The keeper names no port for Drone setup's lease: " + s);
            BetaflightUsb.use(KeeperWire.usb(setupPort)); // until the app's rig puts its own USB in
            before = BoardSnapshot.take(BOARD, new BoardSnapshot.Options(false, "before-drone-setup", timing), say);
            before.write(folder, folder);
            choices = HwDroneSetup.choices(before.dumpAll(), fileCells, BenchAccess.droneCellKeys());
            if (!within(timing.backMs(), this::keeperHasBoard)) throw new AssertionError("The keeper did not take the board back after the snapshot: " + state());
        } finally {
            keeper = null;
        }
    }

    /** The settings the app starts with: Drone setup's questions answered as the board has them. */
    Map<String, String> settings() {
        return choices.settings();
    }

    /** What the run found; null before the board was read after Drone setup. */
    HwDroneSetup.Verdict verdict() {
        return verdict;
    }

    /** The CLI commands the app's log says CONFIRM wrote. */
    List<String> written() {
        return written;
    }

    // ------------------------------------------------------------------ in the app

    void run(AppRig rig) throws Exception {
        this.rig = rig;
        ui = rig.ui;
        film = rig.film;
        t0 = System.currentTimeMillis();
        try (KeeperClient k = new KeeperClient(keeperPort, 30_000)) {
            keeper = k;
            film.remark("DRONE SETUP ON THE BOARD BEHIND THE KEEPER: SETUP > Set up a new drone, its two drone steps - every row's words below; "
                    + "CONFIRM; the board read again and held to what Drone setup said it changed; the keeper takes the board back.");
            film.remark("The keeper at the start: " + atStart);
            film.remark("The board's settings before anything (BoardSnapshot: MSP facts, diff all, dump all): " + before.folder() + " - " + before.version()
                    + "; diff all " + before.diffAll().lines().count() + " lines, dump all " + before.dumpAll().lines().count()
                    + " lines. To put them back: gradlew boardRestore -Psnapshot=\"" + before.folder() + "\" (the keeper ended first).");
            film.remark("The board's motors as the snapshot has them (no row of Drone setup's shows these but the motor test's): " + motors(before) + ".");
            film.remark("The questions a pilot answers for this drone, answered as its board has them: " + String.join("; ", choices.words())
                    + ". Everything else is the page's own default.");
            film.remark(SKIPPED);
            tap = new SetupTap(KeeperWire.usb(setupPort));
            BetaflightUsb.use(tap); // the app's USB: Drone setup's lease on the board, every request written down
            Throwable failed = null;
            try {
                stepOne();
                stepTwo();
                confirm();
            } catch (Throwable t) {
                failed = t;
            }
            try {
                closeGuide();
            } catch (Throwable t) {
                if (failed == null) failed = t;
            } finally {
                tap.unplug();
                write("drone-setup-tap.tsv", tap.lines(t0));
                write("drone-setup-rows.txt", rows);
            }
            try {
                afterwards(); // also after a failure: whatever reached the board is compared
            } catch (Throwable t) {
                if (failed == null) failed = t;
            }
            try {
                keeperTakesItBack();
            } catch (Throwable t) {
                if (failed == null) failed = t;
            }
            if (failed instanceof Exception e) throw e;
            if (failed instanceof Error e) throw e;
        } finally {
            keeper = null;
        }
    }

    // ------------------------------------------------------------------ the first step: Betaflight

    private void stepOne() {
        List<Component> setup = ui.all("SETUP");
        if (setup.isEmpty() || ui.whyNotPressable(setup.get(0), null) != null) ui.click("MENU"); // a narrow window folds its header into MENU
        ui.click("SETUP");
        ui.until("SETUP's menu to slide out", 8000, () -> ui.has("Set up a new drone"));
        ui.click("Set up a new drone");
        ui.until("the new drone's guide", 15_000, () -> ui.window(GUIDE) != null);
        boolean read = within(READ_MS, () -> resting() && confirmOffered() && tap.quietFor(4000));
        List<String> w = words("STEP 1 (Betaflight), read");
        if (!read) throw new AssertionError("Drone setup's first step did not read the board and rest within " + READ_MS / 1000 + " s: " + w);
        ui.shot("Drone setup's first step: the board read through the keeper, CONFIRM offered");
        String usb = HwDroneSetup.row(w, "Flight controller on USB");
        rig.check(usb.contains(KeeperWire.PORT_NAME) && usb.matches(".*\\d+\\.\\d+\\.\\d+.*"), "the USB row names the board's port and its Betaflight version: " + usb);
        film.remark("Before CONFIRM the page says it will write: " + plan(w));
        film.remark("BATTERY OUT: the keeper reads " + state().get("volts") + " V; the page's battery line: \"" + HwDroneSetup.row(w, "BATTERY") + "\"; its GPS line: \""
                + HwDroneSetup.row(w, "GPS ") + "\"; its ESC line: \"" + HwDroneSetup.row(w, "ESCS") + "\" - no failure by itself.");
        byHand();
    }

    /** BY HAND: the same settings for Betaflight Configurator, in a message of its own - read, and closed. */
    private void byHand() {
        Window guide = ui.window(GUIDE);
        AbstractButton b = button(guide, "BY HAND");
        if (b == null) {
            rig.check(false, "the USB row has its BY HAND button");
            return;
        }
        ui.click(b);
        ui.until("the by-hand words", 10_000, () -> ui.window("by hand") != null);
        Window d = ui.window("by hand");
        film.remark("BY HAND (" + Ui.textOf(b) + "): " + String.join(" ", Ui.edt(() -> HwDroneSetup.words(d))));
        ui.closeWindow(d);
        ui.until("the by-hand words to close", 10_000, () -> ui.window("by hand") == null);
    }

    // ------------------------------------------------------------------ the second step: motors and orientation

    private void stepTwo() {
        ui.click("NEXT");
        ui.until("the Motors and orientation step", 15_000, () -> ui.sees("Motors and props")); // a row of that step alone
        boolean live = within(60_000, () -> HwDroneSetup.attitude(reading()) != null);
        List<String> w = words("STEP 2 (Motors and orientation), the live view " + (live ? "following the board" : "WITHOUT A READING"));
        ui.shot("Drone setup's second step: the live view of the board");
        if (realBoard) rig.check(live, "the live view follows the board's attitude (MSP_ATTITUDE): " + reading());
        else if (!live) film.remark("The live view gave no attitude (" + reading() + "): the made-up board has no MSP_ATTITUDE.");
        calibrate();
        motorTest();
        film.remark("ORIENTATION: \"" + HwDroneSetup.row(w, "Orientation") + "\" - its CHECK IT is not pressed: the guided check wants the drone turned "
                + "by hand. What the board says of its attitude is the live view's line: " + reading());
    }

    /** CALIBRATE, on a drone that reads still and flat: Betaflight levels its accelerometer there and saves it. */
    private void calibrate() {
        String first = reading();
        double[] a = HwDroneSetup.attitude(first);
        if (a == null) {
            film.remark("CALIBRATE not pressed: no reading of the board's attitude (" + first + ").");
            return;
        }
        Ui.pause(1500);
        String second = reading();
        double[] b = HwDroneSetup.attitude(second);
        if (b == null) {
            film.remark("CALIBRATE not pressed: the live view's reading went (\"" + first + "\", then \"" + second + "\").");
            return;
        }
        if (!HwDroneSetup.flat(a) || !HwDroneSetup.flat(b)) {
            film.remark("CALIBRATE not pressed: the drone does not read flat (\"" + first + "\", then \"" + second + "\") - Betaflight would take this tilt for level.");
            return;
        }
        if (Math.abs(a[0] - b[0]) > 1 || Math.abs(a[1] - b[1]) > 1) {
            film.remark("CALIBRATE not pressed: the drone is not still (\"" + first + "\", then \"" + second + "\").");
            return;
        }
        calibratePressed = true;
        ui.click("Calibrate");
        boolean said = within(25_000, () -> {
            String row = HwDroneSetup.row(words(), "Accelerometer");
            return row.contains("Calibrated") || row.contains("Not flat") || row.contains("No answer") || row.contains("armed") || row.contains("lost");
        });
        String row = HwDroneSetup.row(words("STEP 2 after CALIBRATE"), "Accelerometer");
        ui.shot("CALIBRATE on the still, flat drone");
        rig.check(said && row.contains("Calibrated: reads flat."), "CALIBRATE on a drone that read flat (" + first + "): the board levels its accelerometer and reads flat "
                + "after it - " + row + "; MSP_ACC_CALIBRATION sent " + tap.msp(HwDroneSetup.ACC_CALIBRATION).size() + " time(s)");
    }

    /**
     * The motor test window, opened to read its rows - the board's version, its motor layout and props direction, the
     * battery, the motor protocol (spin directions are offered for DShot only), REORDER MOTORS - and closed: nothing in
     * it pressed, every motor value it sends STOP.
     */
    private void motorTest() {
        ui.click("Check now");
        ui.until("the motor test window", 15_000, () -> ui.window("Motor test") != null);
        Window mt = ui.window("Motor test");
        boolean found = within(90_000, () -> HwDroneSetup.row(Ui.edt(() -> HwDroneSetup.words(mt)), "Flight controller on USB").contains("[OK]"));
        within(10_000, () -> !HwDroneSetup.row(Ui.edt(() -> HwDroneSetup.words(mt)), "Battery").contains("[PENDING]"));
        List<String> w = Ui.edt(() -> HwDroneSetup.words(mt));
        for (String line : w) rows.add("MOTOR TEST (read only) - " + line);
        film.remark("MOTOR TEST, read only - " + String.join(" | ", w));
        ui.shot("the motor test window, read only: nothing in it pressed");
        rig.check(found, "the motor test window finds the board through the keeper: " + HwDroneSetup.row(w, "Flight controller on USB"));
        rig.check(w.stream().anyMatch(l -> l.contains("[ ] Propellers removed")) && w.stream().noneMatch(l -> l.contains("[x] Propellers removed")),
                "the motor test's risk box is not ticked: every motor value it sends is STOP");
        boolean dshot = w.stream().anyMatch(l -> l.contains("[As is]")), notDshot = w.stream().anyMatch(l -> l.contains("Needs DShot"));
        film.remark("MOTOR PROTOCOL: " + (dshot ? "DShot - the window offers each motor its spin direction, each at \"As is\" (not touched)"
                : notDshot ? "not DShot - the window says \"Needs DShot\" under SPIN DIRECTION" : "the window showed no spin directions")
                + "; MOTOR ORDER: REORDER MOTORS is the only way the window reads it, and it spins each motor - not pressed (the snapshot's order is above).");
        AbstractButton close = Ui.edt(() -> {
            for (Component c : all(mt)) if (c instanceof AbstractButton x && "close".equalsIgnoreCase(Ui.textOf(x))) return x;
            return null;
        });
        if (close == null) throw new AssertionError("The motor test has no Close button: " + w);
        // the window lies beside a setup guide that blocks the app's other windows but not this one (its modal
        // exclusion), which the bench's hands don't know: its Close is pressed on the Swing thread (as SitlArmedWritesScenario)
        Ui.edt(() -> {
            close.doClick();
            return null;
        });
        ui.until("the motor test to close", 15_000, () -> ui.window("Motor test") == null);
        rig.check(within(20_000, () -> !threadAlive("motor-test")), "the motor test has let go of the board");
        String motors = HwDroneSetup.row(words("STEP 2 after the motor test"), "Motors and props");
        rig.check(motors.contains("No motor turned"), "the Motors row says no motor was turned: " + motors);
        List<SetupTap.Event> set = tap.msp(Msp.SET_MOTOR);
        rig.check(set.stream().allMatch(e -> HwDroneSetup.stopOnly(e.text())), "every motor value the motor test sent was STOP: " + set.size() + " MSP_SET_MOTOR");
    }

    // ------------------------------------------------------------------ CONFIRM

    private void confirm() {
        ui.click("BACK");
        ui.until("the Betaflight step again", 15_000, () -> ui.sees("Betaflight settings") && !ui.sees("Motors and props"));
        boolean rests = within(READ_MS, () -> resting() && confirmOffered() && tap.quietFor(3000) && keeperHasBoard());
        List<String> w = words("STEP 1 again, before CONFIRM");
        if (!rests) throw new AssertionError("Drone setup's first step did not rest again with CONFIRM offered: " + w);
        if (choices.refuse() != null) {
            rig.check(false, "CONFIRM not pressed: " + choices.refuse());
            return;
        }
        String battery = HwDroneSetup.row(w, "BATTERY");
        if (battery.contains("Confirm sets")) { // the page would force a count the board does not have, nobody having chosen it
            rig.check(false, "CONFIRM not pressed: the page would force a cell count nobody chose (" + battery + ")");
            return;
        }
        film.remark("CONFIRM pressed; the page said it writes: " + plan(w));
        int asks = tap.asks();
        confirmPressed = true;
        ui.click("CONFIRM");
        rig.check(within(60_000, () -> tap.asks() > asks), "CONFIRM: the page goes to the board");
        boolean done = within(CONFIRM_MS, () -> resting() && (!ui.sees(TO_CONFIRM) || ui.sees(NOTHING_WRITTEN)) && tap.quietFor(5000) && keeperHasBoard());
        List<String> after = words("STEP 1 after CONFIRM");
        ui.shot("after CONFIRM");
        List<String> log = rig.log();
        written = HwDroneSetup.written(log);
        said = HwDroneSetup.said(log);
        String status = ui.sees(SET_UP) ? SET_UP : ui.sees(NOT_ALL) ? NOT_ALL : ui.sees(NOTHING_WRITTEN) ? NOTHING_WRITTEN + " (the write stopped)" : "neither";
        String usb = HwDroneSetup.row(after, "Flight controller on USB");
        List<String> notSet = new ArrayList<>();
        for (String l : after) for (String m : NOT_SET) if (l.contains(m) && !notSet.contains(l.strip())) notSet.add(l.strip());
        if (done && status.equals(SET_UP)) {
            rig.check(usb.contains("Done - unplug the drone."), "Drone setup says done: \"" + SET_UP + "\" - " + usb);
            // 1.0.71: a spent CONFIRM (SetupConfirm.done) leaves the page's pendingWrite true, so a row that is not as
            // chosen no longer keeps it from "done" - said here for the report, no failure of this run
            if (!notSet.isEmpty()) film.remark("SAYS DONE WHILE A ROW SAYS NOT SET (for the report): " + String.join(" | ", notSet));
        } else {
            boolean gpsAlone = done && status.equals(NOT_ALL) && !notSet.isEmpty() && notSet.stream().allMatch(l -> NO_GPS.stream().anyMatch(l::contains));
            if (gpsAlone) {
                film.remark("NOT DONE FOR THE GPS ALONE: \"" + NOT_ALL + "\" - no GPS answered (with the battery out it may have no power), so what needs "
                        + "a GPS waits for it: " + String.join(" | ", notSet) + ". No failure by itself with the battery out; all else is set.");
                rig.check(true, "Drone setup rests after CONFIRM, set but for what waits for a GPS");
            } else {
                rig.check(false, "Drone setup says done after CONFIRM: " + (done ? "it rests saying \"" + status + "\"" : "it did not rest within " + CONFIRM_MS / 1000 + " s")
                        + "; not set: " + notSet + "; " + usb);
            }
        }
        if (written.isEmpty() && tap.saves() == 0) {
            film.remark("CONFIRM WROTE NOTHING: the board was as the page's choices want it (set up once already): no backup, no save, no restart.");
        } else {
            film.remark("CONFIRM WROTE, in Drone setup's own words: " + (said.isEmpty() ? "(nothing said)" : String.join(" || ", said)) + "; the commands: " + written
                    + "; \"save\" " + tap.saves() + " time(s).");
            rig.check(!said.isEmpty() && rig.logged("Its settings before:"), "the app's log says what CONFIRM wrote and where the board's settings before it are kept");
        }
    }

    /** The page's own words for what CONFIRM writes: the lines to set on it, "Also sets", the footer. */
    private static String plan(List<String> w) {
        List<String> out = new ArrayList<>();
        for (String l : w) {
            String s = l.strip();
            if (s.contains("Set on Confirm") || s.contains("Confirm sets") || s.startsWith("Also sets:") || s.contains("Confirm writes it")) out.add(s);
        }
        return out.isEmpty() ? "nothing (no line to set on Confirm)" : String.join(" | ", out);
    }

    // ------------------------------------------------------------------ the end of the app's part

    /** The guide closed, as a pilot does (its Close); nothing of the app's holds the board any more. */
    private void closeGuide() {
        for (String title : new String[]{"Motor test", "by hand"}) {
            Window w = ui.window(title);
            if (w != null) Ui.edt(() -> {
                w.dispose();
                return null;
            });
        }
        Window guide = ui.window(GUIDE);
        if (guide != null) {
            AbstractButton close = button(guide, "Close");
            if (close != null) ui.click(close);
            else Ui.edt(() -> {
                guide.dispose();
                return null;
            });
            ui.until("the guide to close", 15_000, () -> ui.window(GUIDE) == null);
        }
        rig.check(within(30_000, () -> !threadAlive("motor-test") && !threadAlive("orientation-live") && tap.quietFor(2000) && keeperHasBoard()),
                "the app has let go of the board: no motor test, no live view, nothing sent for 2 s, the keeper has it again");
    }

    /**
     * The board read again through Drone setup's lease (the same snapshot code) and held to what Drone setup said:
     * each difference into the film, anything it did not say is wrong; nothing that turns a motor or talks to the
     * ESCs reached the board.
     */
    private void afterwards() throws IOException {
        within(timing.backMs(), this::keeperHasBoard);
        BetaflightUsb.use(KeeperWire.usb(setupPort)); // past the tap: the scenario's own read
        try {
            after = BoardSnapshot.take(BOARD, new BoardSnapshot.Options(false, "after-drone-setup", timing), film::remark);
            after.write(folder, null);
        } finally {
            BetaflightUsb.use(tap);
        }
        if (written.isEmpty()) written = HwDroneSetup.written(rig.log());
        if (said.isEmpty()) said = HwDroneSetup.said(rig.log());
        List<String> differences = before.differences(after);
        List<String> firmware = before.firmwareDifferences(after);
        rig.check(firmware.isEmpty(), "the same firmware before and after: " + (firmware.isEmpty() ? before.version() : firmware));
        verdict = HwDroneSetup.judge(differences, written, said, tap.cliWrites(), calibratePressed && !tap.msp(HwDroneSetup.ACC_CALIBRATION).isEmpty(),
                !tap.msp(Msp.SET_FEATURE_CONFIG).isEmpty());
        List<String> report = new ArrayList<>();
        report.add("THE BOARD AFTER DRONE SETUP against the snapshot before it (" + before.folder() + " -> " + after.folder() + "): " + differences.size()
                + " difference(s)" + (confirmPressed ? "" : " - CONFIRM was not pressed") + (calibratePressed ? "; CALIBRATE was pressed" : "") + ".");
        for (HwDroneSetup.Difference d : verdict.differences()) report.add("  " + d.words());
        for (String n : verdict.notes()) report.add("  NOTE: " + n);
        String defaults = before.defaultsNote(after);
        if (defaults != null) report.add("  NOTE: " + defaults);
        report.forEach(film::remark);
        write("drone-setup-compare.txt", report);
        for (String wrong : verdict.wrong()) rig.check(false, wrong);
        if (verdict.wrong().isEmpty()) {
            rig.check(true, "every difference on the board is one Drone setup said it made, and it named every command it wrote ("
                    + verdict.differences().size() + " difference(s), " + written.size() + " command(s))");
        }
        List<String> forbidden = HwDroneSetup.forbidden(tap.events(), calibratePressed);
        rig.check(forbidden.isEmpty(), "nothing that turns a motor, talks to the ESCs, or writes outside CONFIRM and CALIBRATE reached the board: "
                + (forbidden.isEmpty() ? "none" : forbidden));
        film.remark("The app asked for the board " + tap.asks() + " time(s) (" + tap.events().stream().filter(e -> e.kind().equals("refused")).count()
                + " not given: the keeper was taking it back after a restart); every request is in drone-setup-tap.tsv.");
    }

    /** The keeper has the board again - taken, locked, disarmed, no window - and says it is at rest. */
    private void keeperTakesItBack() throws IOException {
        boolean back = within(timing.backMs(), () -> {
            KeeperClient.State s = state();
            return s.boardUp() && !s.lease().equals("setup") && Boolean.FALSE.equals(s.armed()) && Boolean.TRUE.equals(s.locked()) && s.windowMs() == 0;
        });
        rig.check(back, "the keeper has the board back: taken, locked, disarmed, no window open: " + state());
        String safe = keeper.ask("SAFE");
        rig.check(safe.startsWith("OK"), "the keeper's word: locked, disarmed, every motor at rest - " + safe);
    }

    // ------------------------------------------------------------------ small helpers

    private KeeperClient.State state() {
        try {
            return keeper.state();
        } catch (IOException e) {
            throw new AssertionError("The keeper does not answer: " + e.getMessage());
        }
    }

    /** The board is the keeper's again: taken, and no lease of Drone setup's on it. */
    private boolean keeperHasBoard() {
        KeeperClient.State s = state();
        return s.boardUp() && !s.lease().equals("setup");
    }

    private boolean resting() {
        for (String s : RESTING) if (ui.sees(s)) return true;
        return false;
    }

    private boolean confirmOffered() {
        for (Component c : ui.all("CONFIRM")) if (c instanceof AbstractButton b && b.isEnabled()) return true;
        return false;
    }

    /** The live view's line under the 3D drone ("Betaflight: nose level, sides level"), or what it says instead. */
    private String reading() {
        for (String l : words()) {
            String s = l.strip();
            if (s.startsWith("Betaflight:")) return s;
        }
        return "no reading shown";
    }

    /** The guide's words now. */
    private List<String> words() {
        Window guide = ui.window(GUIDE);
        return guide == null ? List.of() : Ui.edt(() -> HwDroneSetup.words(guide));
    }

    /** The guide's words now, into the film and its rows file under this heading. */
    private List<String> words(String heading) {
        List<String> w = words();
        rows.add("---- " + heading);
        rows.addAll(w);
        film.remark(heading + " - " + String.join(" | ", w));
        return w;
    }

    private static AbstractButton button(Window w, String begins) {
        if (w == null) return null;
        return Ui.edt(() -> {
            for (Component c : all(w)) {
                if (c instanceof AbstractButton b && c.isShowing()) {
                    String t = Ui.textOf(b);
                    if (t != null && t.toUpperCase(Locale.ROOT).startsWith(begins.toUpperCase(Locale.ROOT))) return b;
                }
            }
            return null;
        });
    }

    private static List<Component> all(Container root) {
        List<Component> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Component c, List<Component> out) {
        if (!c.isVisible()) return;
        out.add(c);
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, out);
    }

    private static boolean threadAlive(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().equals(name)) return true;
        return false;
    }

    /**
     * Whether it is so within ms - the shared wait (Waits), looked at four times a second: a look that throws counts
     * as "not yet", the last look comes after the time is up. Interrupted (the scenario's time is up): false at once.
     */
    private static boolean within(long ms, BooleanSupplier ok) {
        try {
            return Waits.until(() -> {
                try {
                    return ok.getAsBoolean();
                } catch (RuntimeException | AssertionError e) {
                    return false;
                }
            }, ms, 250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The motors as a snapshot has them: protocol, bidirectional DShot, props direction, the motor order (MSP). */
    static String motors(BoardSnapshot s) {
        List<String> out = new ArrayList<>();
        for (String l : s.dumpAll().lines().map(String::strip).toList()) {
            for (String name : new String[]{"motor_pwm_protocol", "dshot_bidir", "yaw_motors_reversed", "motor_poles"}) {
                if (l.startsWith("set " + name + " ")) out.add(l.substring(4));
            }
        }
        String order = s.msp().get("motor_order");
        if (order != null && !order.startsWith("!")) {
            try {
                byte[] p = Msp.unhex(order);
                List<Integer> outputs = new ArrayList<>();
                for (int i = 1; i <= (p.length > 0 ? p[0] & 0xff : 0) && i < p.length; i++) outputs.add(p[i] & 0xff);
                out.add("motor order (MSP): motor 1 to " + outputs.size() + " on outputs " + outputs);
            } catch (IllegalArgumentException e) {
                out.add("motor order (MSP): " + order);
            }
        } else {
            out.add("motor order: not read (" + order + ")");
        }
        return String.join(", ", out);
    }

    private void write(String file, List<String> lines) {
        try {
            Files.write(film.dir().resolve(file), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            film.remark(file + " could not be written: " + e.getMessage());
        }
    }
}
