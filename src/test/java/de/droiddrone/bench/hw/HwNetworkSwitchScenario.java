package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.PhoneNet;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.PhoneWords;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.SilentSound;
import de.droiddrone.bench.Ui;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

import static de.droiddrone.bench.hw.HwLinkMatrix.ms;
import static de.droiddrone.bench.hw.HwLinkMatrix.one;

/**
 * Real networks on the real phone: the phone's own network changed under the link - Wi-Fi off to mobile data and on
 * again, mobile data off while on Wi-Fi, airplane mode for 10 s and for 60 s, Wi-Fi off and on within 2 s - with the
 * real, sealed Windows app flying it and the sticks counted where they end: at the real flight controller, through the
 * keeper. The app reaches the phone at the hardware file's address itself (the fence's one way through), as a pilot's
 * PC does over Tailscale: no bench wire in between, the network under test is the real one. The phone app is the bench
 * build, told to reach its flight controller through the keeper as {@link BoardChainScenario} does it. Battery in or
 * out, <b>no propellers</b>; the phone on this PC's USB cable (adb over the network would be cut by its own switch:
 * {@code PhoneGuard} refuses that, and the scenario does not begin).
 * <ul>
 * <li><b>The phone as found.</b> Before anything is switched: airplane mode off, mobile data on, Wi-Fi as the hardware
 * file says ({@code phone.wifi}, left out: on), and the phone on that network, validated - else the scenario does not
 * begin, and says what is not so. Before its first switch it notes on the phone what to put back ({@link PhoneNet#NOTE})
 * and leaves {@value #SWITCHED_FILE} in the run's folder (bench.ps1 hw then ends with {@code gradlew phoneReset},
 * which puts the phone back by that note after a run that was killed). After every cell, and at the end whatever
 * happened, the phone is put back so and read back; the note goes once it is. A phone whose Wi-Fi is normally off has
 * its Wi-Fi cells skipped: the bench switches on no network the phone does not have.</li>
 * <li><b>Each cell</b> from a steady link: the phone on the network the cell begins from (Wi-Fi on comes from mobile
 * data), PILOT UAV, LINK READY, the pilot's sticks at the board, the picture, the phone's sound, 5 s steady - then the
 * switch, its orders on their times, every stream watched until each is back (10 s at least after the last order, 60 s
 * at most): the link (LINK READY), the picture (the app's link log), the pilot's sticks at the board (by their rate,
 * the keeper's figures), the sound (the phone's microphone as the app plays it) - when each went, how long it was away,
 * how long after the last order it was back; the largest stick gap at the board, the board's own RXLOSS and FAILSAFE,
 * nothing armed. Then which network the phone is on (dumpsys connectivity) before, during a long switch, after - the
 * cell's own premise checked (Wi-Fi off ends on mobile data) - and Tailscale's path as far as the phone's log shows it
 * (addresses left out), the phone app's new sessions (F-001: two at most), its link socket opened anew (F-039) or
 * parked for want of Tailscale (said), errors (said), a crash (failed).</li>
 * <li><b>armed</b> - only when the hardware file says {@code board.mayArm=true}: per cell, after its link part, the
 * phone back onto the cell's first network, one window of the keeper's ({@link HwLinkMatrix#WINDOW_S} s, throttle
 * minimum, PREARM then ARM), the same switch while the board is armed (its hold {@link HwLinkMatrix#ARMED_CUT_MAX_MS}
 * ms at most), the board held to {@link HwLinkMatrix#judge}; the scenario's ARM off in the window's last two seconds at
 * the latest. Never where the keeper lets the board's own failsafe run. Without the word the rows say "skipped".</li>
 * </ul>
 * A row a cell into matrix/{@value HwNetworkSwitch#TABLE}.tsv in the run's folder, each cell's log (its switch's
 * timeline, the board's word, Tailscale's lines, the phone app's log) in matrix/logs, plain check lines in the film.
 * {@code -Dbench.quick=true} (bench.ps1's {@code -Quick}): Wi-Fi off, Wi-Fi on, airplane mode 10 s.
 * <p>
 * Without a hardware file, or with one that names no keeper, it skips itself.
 */
@Tag("hw")
class HwNetworkSwitchScenario {
    private static final boolean QUICK = Boolean.getBoolean("bench.quick");
    /** How often the board's word is looked at (the keeper's state) while a switch is watched. */
    private static final long LOOK_MS = 100;
    /** In the run's folder from the first switch on: bench.ps1 hw ends with gradlew phoneReset, however the run ended. */
    static final String SWITCHED_FILE = "phone-network-switched.txt";

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    void thePhonesOwnNetworkChangesUnderTheLinkAndTheLinkThePictureTheSticksAtTheBoardAndTheSoundComeBack() throws Exception {
        Assumptions.assumeTrue(Hardware.present(), "This bench has no hardware set up: no " + Hardware.FILE
                + " in %LOCALAPPDATA%\\DraupneBench (start from " + Hardware.EXAMPLE + ")");
        Hardware bench = Hardware.load();
        Assumptions.assumeTrue(bench.hasKeeper(), "This bench's hardware file names no keeper (keeper.port): no flight controller on this PC's USB to test");
        PhoneRig.Target target = PhoneRig.Target.device();
        if (target.overTcp()) {
            throw new AssertionError("NOT BEGUN: adb comes to the phone over the network (" + target.serial() + "), and this scenario switches the phone's network "
                    + "off: it would cut the bench's own way to the phone, with nobody there to switch it on again. Put the phone on this PC's USB cable.");
        }
        KeeperClient.State atStart = HwLinkMatrixScenario.keeperReady(bench);
        List<HwNetworkSwitch.Cell> cells = HwNetworkSwitch.cells(QUICK);
        AppRig.scenario("hw-network-switch").hardware(bench).run(rig -> new Run(rig, bench, target, atStart).run(cells));
    }

    /** One run: the app, the phone, the keeper - the cells one after the other, the phone put back after each and at the end. */
    private static final class Run {
        private final AppRig rig;
        private final Ui ui;
        private final Hardware bench;
        private final PhoneRig.Target target;
        private final KeeperClient.State atStart;
        private final PhoneNet.Normal normal;
        private final Path matrix = Sandbox.runRoot().resolve("matrix");
        private KeeperClient keeper;
        private PhoneRig phone;
        private int cells, wrongCells;
        /** A switch was sent (or the note written): from here on the phone is put back at the end, whatever happens. */
        private boolean touched;
        /** What ends the run here: the phone's battery (PhoneRig.Stop), a refused command, a phone that could not be put back, the scenario's time. */
        private AssertionError halt;

        Run(AppRig rig, Hardware bench, PhoneRig.Target target, KeeperClient.State atStart) {
            this.rig = rig;
            this.ui = rig.ui;
            this.bench = bench;
            this.target = target;
            this.atStart = atStart;
            this.normal = PhoneNet.normal(bench.phoneWifi());
        }

        void run(List<HwNetworkSwitch.Cell> list) throws Exception {
            ui.autoShot(false);
            rig.film.remark(plan(list));
            try (KeeperClient k = rig.guard.keeper()) { // (the rig's guard has told the keeper the scenario's name: its frames are in frames-hw-network-switch.log)
                keeper = k;
                rig.check(rig.radio.isHandsOff() && rig.radio.ceiling() == Hardware.THROTTLE_CEILING, "the radio starts hands off, its throttle ceiling at " + Hardware.THROTTLE_CEILING);
                if (!target.benchBuild()) {
                    throw new AssertionError("phone.package names " + target.app() + ": only the bench build (flight-app\\build.ps1 -Bench) can reach a flight controller through the keeper");
                }
                try (PhoneRig p = new PhoneRig(rig.film, target)) {
                    phone = p;
                    try {
                        phone.ensureRunning(); // a real phone: its log followed into the film's folder, its battery looked at
                        asFound();
                        throughTheKeeper();
                        ui.until("the real phone, online, in the PHONE list", 90_000, () -> chosenPhone(ui).contains("online"));
                        rig.film.remark("The PHONE list: \"" + chosenPhone(ui).trim() + "\" - the real phone at the hardware file's address, no bench wire between: "
                                + "the network under test is the real one.");
                        KeeperClient.State s = state();
                        rig.film.remark("The board, by the keeper: failsafe " + s.get("failsafe") + " after failsafe_delay " + s.get("failsafeDelay") + "; the keeper ends an arm window "
                                + (s.number("stickLimitMs") > 0 ? "on RXLOSS, or " + s.number("stickLimitMs") + " ms after the last stick frame at the latest"
                                : s.number("stickLimitMs") == 0 ? "NOT by itself (board.letFailsafeRun)" : "- it has no failsafe_delay to go by: NO ARM WINDOW")
                                + "; " + BoardChainScenario.cannotArm(s));
                        for (HwNetworkSwitch.Cell c : list) {
                            cell(c);
                            goOn();
                        }
                        rig.film.remark("THE SWITCHES: " + cells + " cell(s), " + wrongCells + " with something wrong. Their table: " + matrix.resolve(HwNetworkSwitch.TABLE + ".tsv")
                                + "; each cell's log in " + matrix.resolve("logs") + ".");
                        rig.film.remark("The phone app is left with debug.draupne.fc=tcp:" + atStart.number("phonePort")
                                + " (gone with the phone's next restart; adb shell setprop debug.draupne.fc usb puts USB back).");
                    } finally {
                        asWeFoundIt(); // whatever happened - and then the rig's close switches on what it saw switched off, once more
                    }
                }
            } finally {
                rig.radio.handsOff();
            }
        }

        private void goOn() {
            if (halt != null) throw halt;
            if (Thread.currentThread().isInterrupted()) throw new AssertionError("The scenario was interrupted (its time was up?) after " + cells + " cell(s)");
        }

        private String plan(List<HwNetworkSwitch.Cell> list) {
            List<String> names = list.stream().map(c -> c.name() + (HwNetworkSwitch.skip(c, normal) == null ? "" : " (skipped: Wi-Fi normally off)")).toList();
            return "THE PHONE'S OWN NETWORK SWITCHED UNDER THE LINK" + (QUICK ? " - QUICK: a few cells only" : "") + ": " + list.size() + " cell(s) (" + String.join("; ", names)
                    + "), each from a steady link; about " + Math.round(HwNetworkSwitch.estimateMs(list, bench.mayArm()) / 60_000.0) + " min. The phone as found must be "
                    + normal.words() + " (phone.wifi in the hardware file says the Wi-Fi), on its network; it is put back so after every cell and at the end, whatever "
                    + "happens, and a note on the phone says so meanwhile (" + PhoneNet.NOTE + "; gradlew phoneReset puts it back by that note after a killed run). "
                    + (bench.mayArm() ? "THE ARMED PART IS ON (board.mayArm=true): per cell one window of the keeper's, " + HwLinkMatrix.WINDOW_S
                    + " s, throttle minimum, PREARM then ARM, the same switch while armed (its hold " + HwLinkMatrix.ARMED_CUT_MAX_MS / 1000 + " s at most)."
                    : "THE ARMED PART IS SKIPPED: the hardware file does not say board.mayArm=true - nothing is armed, and the rows say \"skipped\".");
        }

        // ------------------------------------------------------------------ the phone as found, and put back

        /**
         * The phone as found, before anything is switched: a note of a killed run first (the phone as that note says:
         * the note taken away; not so: NOT BEGUN - gradlew phoneReset), then the phone as it normally is, on its
         * network, validated - else NOT BEGUN, nothing switched.
         */
        private void asFound() {
            PhoneNet.State s = PhoneNet.read(phone::network);
            PhoneNet.Normal left = PhoneNet.Normal.ofNote(phone.network(PhoneNet.NOTE_READ));
            if (left != null) {
                String wrong = left.wrong(s);
                if (wrong != null) {
                    throw new AssertionError("NOT BEGUN: a run of the bench before this one switched the phone's network and did not get to put it back - its note on "
                            + "the phone says " + left.words() + ", and " + wrong + ". gradlew phoneReset puts it back as noted; then start again. Nothing was switched.");
                }
                phone.network(PhoneNet.NOTE_DELETE);
                rig.film.remark("A note of a run before this one was on the phone (" + left.words() + "), and the phone is so: the note taken away.");
            }
            String wrong = normal.wrong(s);
            if (wrong != null) {
                throw new AssertionError("NOT BEGUN: the phone is not as it normally is (" + normal.words() + "; phone.wifi in the hardware file says the Wi-Fi): " + wrong
                        + ". Nothing was switched. Put it so by hand - the bench puts back only what it switched itself - and start again.");
            }
            long until = System.currentTimeMillis() + HwNetworkSwitch.SETTLE_MS;
            while (!normal.settled(s) && System.currentTimeMillis() < until && !Thread.currentThread().isInterrupted()) {
                Ui.pause(2000);
                s = PhoneNet.read(phone::network);
            }
            if (!normal.settled(s)) {
                throw new AssertionError("NOT BEGUN: the phone's switches are as they normally are, but it is not on " + normal.net().words + ", validated, within "
                        + HwNetworkSwitch.SETTLE_MS / 1000 + " s: " + s.words() + ". Nothing was switched.");
            }
            rig.film.remark("The phone as found: " + s.words() + " - as it normally is.");
            if (s.data() == null) {
                rig.film.remark("Whether mobile data is on cannot be read on this phone (" + PhoneNet.DATA_SETTING + "): taken as on; putting back sends "
                        + PhoneNet.DATA_ON + " once, which does nothing to a phone whose data is on.");
            }
            if (!s.tailscale()) {
                rig.film.remark("WARNING: the phone has no Tailscale VPN up (dumpsys connectivity) - since 2.3.0-pc.58 the phone app answers over Tailscale only: "
                        + "the link will not come up.");
            }
        }

        /** Before the first switch: the file that tells bench.ps1 to end with phoneReset, and the note on the phone of what to put back. */
        private void touch() {
            if (touched) return;
            touched = true;
            try {
                Files.writeString(Sandbox.runRoot().resolve(SWITCHED_FILE), "The phone's own network was switched by HwNetworkSwitchScenario (" + LocalDateTime.now().withNano(0)
                        + "): bench.ps1 hw ends with gradlew phoneReset, which puts it back as the note on the phone (" + PhoneNet.NOTE + ") says, if the run did not "
                        + "get to it.\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                rig.film.remark("The run's folder did not take " + SWITCHED_FILE + " (" + e.getMessage() + "): after a killed run, gradlew phoneReset by hand.");
            }
            phone.network(PhoneNet.noteWrite(normal.wifi()));
        }

        /** The phone put back as found and read back (a minute at most); the run's time spent waiting meanwhile is not cut short by an interruption. */
        private PhoneNet.Back putBack() {
            boolean interrupted = Thread.interrupted();
            try {
                return PhoneNet.putBack(phone::network, normal, Ui::pause, System::currentTimeMillis, HwNetworkSwitch.SETTLE_MS, rig.film::remark);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /** The end, whatever happened: the phone put back as found, read back, the note taken away once it is. Never throws: what fails is a check. */
        private void asWeFoundIt() {
            if (!touched) return;
            try {
                PhoneNet.Back back = putBack();
                if (back.switches()) phone.network(PhoneNet.NOTE_DELETE);
                rig.check(back.switches(), "THE PHONE PUT BACK as found (" + normal.words() + "): " + back.words()
                        + (back.switches() ? "; the bench's note on the phone taken away" : " - gradlew phoneReset puts it back as the note on the phone says, or by hand"));
                if (back.switches() && !back.settled()) {
                    rig.film.remark("The phone's switches are back, but it was not on " + normal.net().words + " again within " + HwNetworkSwitch.SETTLE_MS / 1000
                            + " s - look at the phone.");
                }
            } catch (Throwable e) { // the phone gone from adb, the battery's stop: the rig's close switches on what it switched off, phoneReset follows the run
                rig.check(false, "THE PHONE COULD NOT BE PUT BACK here (" + e + "): the rig's close switches on what it switched off, and gradlew phoneReset "
                        + "(bench.ps1 hw runs it after this scenario) puts it back as the note on the phone says - or by hand");
            }
        }

        /**
         * The phone put back as found, then onto the network the cell begins from: the cell's own orders, and that network
         * validated (a minute at most). Null when it is not: said, and the run ends (a phone that is not back is not switched again).
         */
        private PhoneNet.State onto(Cell c, HwNetworkSwitch.Way way, List<HwNetworkSwitch.Order> before) {
            PhoneNet.Back back = putBack();
            if (!c.check(back.settled(), "the phone as found before the switch: " + back.words())) {
                if (halt == null) halt = new AssertionError("The phone is not back as found before " + c.name + ": " + back.words());
                return null;
            }
            if (before.isEmpty()) return back.state();
            touch();
            for (HwNetworkSwitch.Order o : before) phone.network(o.command());
            PhoneNet.Net want = way.net(normal);
            PhoneNet.State s = settleOn(want);
            if (!c.check(s.on() == want && s.validated(), "the phone onto " + want.words + " to begin from (" + String.join("; ", before.stream().map(HwNetworkSwitch.Order::command).toList())
                    + "): " + s.words())) {
                return null;
            }
            return s;
        }

        /** Read again every two seconds until the phone is on that network, validated, or a minute is over. */
        private PhoneNet.State settleOn(PhoneNet.Net want) {
            long until = System.currentTimeMillis() + HwNetworkSwitch.SETTLE_MS;
            PhoneNet.State s = PhoneNet.read(phone::network);
            while (!(s.on() == want && s.validated()) && System.currentTimeMillis() < until && !Thread.currentThread().isInterrupted()) {
                Ui.pause(2000);
                s = PhoneNet.read(phone::network);
            }
            return s;
        }

        /** The phone app's flight controller is the keeper's port down the cable (as BoardChainScenario sets it). */
        private void throughTheKeeper() {
            int phonePort = atStart.number("phonePort");
            ui.until("the keeper to have led the phone's port " + phonePort + " down the cable (adb reverse)", 30_000, () -> state().get("reverse").equals("ok"));
            phone.shell("setprop debug.draupne.fc tcp:" + phonePort);
            rig.check(phone.shell("getprop debug.draupne.fc").trim().equals("tcp:" + phonePort), "the phone app is told: its flight controller over TCP, port " + phonePort);
            phone.forceStop(); // it reads the choice when it next opens its flight controller's port: closed, and opened again
            phone.launch();
            ui.until("the phone app to connect to the keeper (the phone's lease)", 60_000, () -> state().lease().equals("phone"));
            rig.film.remark("The phone app has its flight controller through the keeper: " + state());
        }

        // ------------------------------------------------------------------ a cell

        private void cell(HwNetworkSwitch.Cell cell) {
            Cell c = new Cell(cell);
            String skip = HwNetworkSwitch.skip(cell, normal);
            if (skip != null) {
                rig.film.remark("---- " + c.name + " ---- skipped: " + skip + ".");
                c.row.put("verdict", "skipped").put("what", skip);
                write(c);
                return;
            }
            try {
                begin(c);
                if (onto(c, cell.from(), cell.before()) == null) return;
                LocalTime asked = LocalTime.now();
                long ready = connect(60_000);
                c.row.put("ready ms", ms(ready));
                if (!c.check(ready >= 0, "the link comes up: " + (ready < 0 ? "no LINK READY within 60 s of PILOT UAV" : "LINK READY " + ready + " ms after PILOT UAV"))) return;
                boolean sound = steady(c, asked);
                if (!c.wrong.isEmpty()) return; // no steady link: the switch would measure nothing
                PhoneNet.State before = PhoneNet.read(phone::network);
                c.row.put("phone before", before.words());
                String switchStamp = phone.now();
                phone.checkBattery(); // here, with the network as found: a cooling pause costs no measurement (the switch's own orders never wait for it)
                touch();

                Watched w = watch(c, cell.orders(), sound);
                c.row.put("switch ms", w.lastOrderAt() - w.start()).put("link lost at ms", w.link().wentAway() ? Long.toString(w.link().firstGoneAt() - w.start()) : "never")
                        .put("link gone ms", w.link().goneCell()).put("link back ms", w.link().backCell(w.lastOrderAt()))
                        .put("picture gone ms", w.picture().goneCell()).put("picture back ms", w.picture().backCell(w.lastOrderAt()))
                        .put("sticks gone ms", w.sticks().goneCell()).put("sticks back ms", w.sticks().backCell(w.lastOrderAt()))
                        .put("stick gap ms max", one(w.maxGapMs()))
                        .put("sound gone ms", sound ? w.sound().goneCell() : "not measured").put("sound back ms", sound ? w.sound().backCell(w.lastOrderAt()) : "-")
                        .put("board RXLOSS at ms", ms(w.rxlossAt())).put("board RXLOSS for ms", w.rxlossAt() < 0 ? "-" : Long.toString(w.rxlossFor()))
                        .put("board FAILSAFE", w.failsafe() ? "yes" : "no").put("board armed", w.armed() ? "ARMED" : "no")
                        .put("phone during", w.during() == null ? "-" : w.during());

                c.check(!w.link().notBack(), w.link().wentAway() ? "the link back by itself: " + w.link().words("LINK READY", w.lastOrderAt())
                        + (w.appLeftReady() ? "" : " (the app's link log never left READY)") : "the link held through the switch: LINK READY at every look");
                c.check(!w.picture().notBack(), w.picture().wentAway() ? "the picture back by itself: " + w.picture().words("the picture", w.lastOrderAt())
                        : "the picture never stopped (frames in every row of the app's link log through the switch)");
                c.check(!w.sticks().notBack(), w.sticks().words("the pilot's sticks at the board", w.lastOrderAt()) + "; the largest gap at the board "
                        + one(w.maxGapMs()) + " ms");
                if (sound) c.check(!w.sound().notBack(), w.sound().words("the phone's sound", w.lastOrderAt()));
                else c.note("the phone's sound is not measured in this cell: none came in the 8 s before the switch");
                c.check(!w.armed() && !ui.sees("FC: ARMED"), "nothing armed: the board says disarmed at every look, the app too");
                if (cell.orders().size() == 2 && cell.holdMs() < HwNetworkSwitch.FLAP_MAX_MS) {
                    long off = w.lastOrderAt() - w.start();
                    c.note((off <= HwNetworkSwitch.FLAP_MAX_MS ? "" : "NOT A QUICK FLAP: ") + "Wi-Fi was off for " + off + " ms (from its order to the end of the order "
                            + "that put it on: adb's own time included)");
                }
                c.note("the board's own word: " + w.boardWords() + "; " + w.link().words("the link", w.lastOrderAt()) + "; " + w.picture().words("the picture", w.lastOrderAt())
                        + "; " + w.sticks().words("the sticks", w.lastOrderAt()) + (sound ? "; " + w.sound().words("the sound", w.lastOrderAt()) : ""));

                // where the phone ended: the cell's own premise
                PhoneNet.Net want = cell.to().net(normal);
                PhoneNet.State after = settleOn(want);
                c.row.put("phone after", after.words());
                c.check(after.on() == want && after.validated(), "the phone ends on " + want.words + " as the switch means it to: " + after.words());
                tailscale(c, switchStamp);
                String said = phone.logSince(switchStamp);
                long sessions = count(said, PhoneWords.NEW_SESSION) + count(said, PhoneWords.NEW_SESSION_SAME_PC);
                c.row.put("phone new sessions", sessions).put("phone no Tailscale", count(said, PARKED));
                c.check(sessions <= HwNetworkSwitch.SESSIONS_AT_MOST, "the phone began " + sessions + " new session(s) for the switch (" + HwNetworkSwitch.SESSIONS_AT_MOST
                        + " at most: F-001, one when the link goes, one when it is back)");
                if (count(said, PARKED) > 0) {
                    c.note("the phone app parked its link socket for want of Tailscale " + count(said, PARKED) + " time(s) in the switch (Q-006: it answers on Tailscale only)");
                }

                if (!w.link().notBack()) armedPart(c, cell);
                else c.row.put("armed part", "not run: the link did not come back");
            } catch (Throwable t) {
                stopped(c, t);
            } finally {
                finish(c);
            }
        }

        /**
         * The link steady before the switch: the pilot's sticks at the board, the picture, the phone's sound (whether it
         * comes: said), then {@link HwNetworkSwitch#STEADY_MS} of LINK READY at every look. Returns whether the sound comes.
         */
        private boolean steady(Cell c, LocalTime asked) throws IOException {
            long sticks = sticksAtBoard(20_000);
            if (!c.check(sticks >= 0, "the pilot's sticks reach the board before the switch" + (sticks < 0 ? ": not within 20 s of LINK READY" : ", " + sticks + " ms after LINK READY"))) {
                return false;
            }
            long picture = waitRows(c, System.currentTimeMillis() + 20_000, rows -> AppLinkLog.first(rows, asked, AppLinkLog.Row::picture));
            if (!c.check(picture >= 0, "the picture comes before the switch" + (picture < 0 ? ": none in the app's link log within 20 s" : ""))) return false;
            long bytes = soundBytes(), until = System.currentTimeMillis() + 8000;
            boolean sound = false;
            while (!sound && System.currentTimeMillis() < until && !Thread.currentThread().isInterrupted()) {
                Ui.pause(100);
                sound = soundBytes() > bytes;
            }
            long end = System.currentTimeMillis() + HwNetworkSwitch.STEADY_MS;
            boolean held = true;
            while (System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted()) {
                held &= ui.has("LINK READY");
                Ui.pause(100);
            }
            c.check(held, "the link steady for " + HwNetworkSwitch.STEADY_MS / 1000 + " s before the switch: LINK READY at every look");
            ui.shot(c.name + ": a steady link, before the switch");
            return sound;
        }

        /**
         * The switch's orders on their times, and the four streams looked at every 50 ms (the sticks' rate and the sound
         * every 100 ms), the board's word every {@value #LOOK_MS} ms - until each stream is there again, at least
         * {@link HwNetworkSwitch#WATCH_MS} after the last order, at most {@link HwNetworkSwitch#BACK_MS}. A long switch's
         * phone is read half way through.
         */
        private Watched watch(Cell c, List<HwNetworkSwitch.Order> orders, boolean measureSound) throws IOException {
            keeper.order("MARK");
            BoardTrace trace = new BoardTrace();
            HwNetworkSwitch.Outage link = new HwNetworkSwitch.Outage(0), sticks = new HwNetworkSwitch.Outage(HwNetworkSwitch.STICKS_GONE_MS),
                    sound = new HwNetworkSwitch.Outage(HwNetworkSwitch.SOUND_GONE_MS);
            long hold = orders.get(orders.size() - 1).atMs();
            String during = null;
            LocalTime fromAt = LocalTime.now();
            long start = System.currentTimeMillis(), lastOrderAt = -1, lastAt = start, lastFrames = 0, bytes = soundBytes(), grewAt = start;
            int next = 0;
            try (BoardTrace.Sampler look = trace.sample(bench.keeperPort(), LOOK_MS)) {
                while (true) {
                    long t = System.currentTimeMillis();
                    while (next < orders.size() && t - start >= orders.get(next).atMs()) {
                        String order = orders.get(next).command();
                        phone.network(order); // past the guard as ever; never kept waiting by the battery watch
                        next++;
                        t = System.currentTimeMillis();
                        if (next == orders.size()) lastOrderAt = t;
                        c.log.append(String.format(Locale.ROOT, "%+7d ms  %s%n", t - start, order));
                        rig.film.remark(c.name + ": " + order + " (" + (t - start) + " ms into the switch).");
                    }
                    link.look(t, ui.has("LINK READY"));
                    if (t - lastAt >= 100) {
                        long frames = figures().number("frames");
                        sticks.look(t, HwLinkMatrix.pilotsSticks(frames - lastFrames, t - lastAt), lastAt);
                        lastFrames = frames;
                        lastAt = t;
                        if (measureSound) {
                            long b = soundBytes();
                            if (b > bytes) {
                                bytes = b;
                                grewAt = t;
                            }
                            sound.look(t, t - grewAt < HwNetworkSwitch.SOUND_GONE_MS, grewAt);
                        }
                    }
                    if (during == null && orders.size() > 1 && hold >= 5000 && next == 1 && t - start >= hold / 2) {
                        during = PhoneNet.read(phone::network).words();
                        c.log.append(String.format(Locale.ROOT, "%+7d ms  the phone: %s%n", System.currentTimeMillis() - start, during));
                    }
                    if (lastOrderAt >= 0) {
                        boolean there = !link.missing() && !sticks.missing() && (!measureSound || !sound.missing());
                        if (t - lastOrderAt >= HwNetworkSwitch.WATCH_MS && there) break;
                        if (t - lastOrderAt >= HwNetworkSwitch.BACK_MS) break;
                    }
                    if (Thread.currentThread().isInterrupted()) break; // an order still to come is the putting back's
                    Ui.pause(50);
                }
                long end = System.currentTimeMillis();
                pauseUntil(end + 3000); // the board's word after it: its RXLOSS gone again
                if (look.failure() != null) c.note("the keeper did not answer every look at the board: " + look.failure());
                if (lastOrderAt < 0) lastOrderAt = end;
                HwNetworkSwitch.Away linkAway = link.result(end), sticksAway = sticks.result(end), soundAway = sound.result(end);
                KeeperClient.State fig = figures();
                // the picture: from the app's link log, by its rows' own times (on its file within a second or two)
                Ui.pause(2000);
                List<AppLinkLog.Row> rows = AppLinkLog.read(rig.sandbox.logsDir(), c.startMs);
                HwNetworkSwitch.Away picture = pictureAway(rows, fromAt, start);
                while (picture.notBack() && System.currentTimeMillis() < lastOrderAt + HwNetworkSwitch.BACK_MS && !Thread.currentThread().isInterrupted()) {
                    Ui.pause(1000);
                    rows = AppLinkLog.read(rig.sandbox.logsDir(), c.startMs);
                    picture = pictureAway(rows, fromAt, start);
                }
                boolean leftReady = AppLinkLog.between(rows, fromAt, LocalTime.now()).stream().anyMatch(r -> !r.ready());
                long rxAt = trace.first("RXLOSS", start), rxLast = trace.last("RXLOSS", start);
                c.log.append("The board's word around the switch (t = its start), ").append(trace.samples().size()).append(" looks:\n").append(dump(trace, start));
                return new Watched(start, lastOrderAt, linkAway, picture, sticksAway, soundAway, fig.real("maxGapMs"), rxAt < 0 ? -1 : rxAt - start,
                        rxAt < 0 ? 0 : rxLast - rxAt, trace.said("FAILSAFE", start), trace.everArmed(0), during, leftReady, trace.words(start));
            }
        }

        /** The picture's coming and going in the app's link log from that moment on (each row a look, at its own time). */
        private static HwNetworkSwitch.Away pictureAway(List<AppLinkLog.Row> rows, LocalTime from, long fromMs) {
            HwNetworkSwitch.Outage o = new HwNetworkSwitch.Outage(0);
            long last = fromMs;
            for (AppLinkLog.Row r : rows) {
                long d = AppLinkLog.msBetween(from, r.at());
                if (d < 0) continue;
                last = fromMs + d;
                o.look(last, r.picture());
            }
            return o.result(last);
        }

        /** What a switch did, as watched. Times are the clock's ms; rxlossAt is from the switch's start, -1: none. */
        private record Watched(long start, long lastOrderAt, HwNetworkSwitch.Away link, HwNetworkSwitch.Away picture, HwNetworkSwitch.Away sticks,
                               HwNetworkSwitch.Away sound, double maxGapMs, long rxlossAt, long rxlossFor, boolean failsafe, boolean armed, String during,
                               boolean appLeftReady, String boardWords) {
        }

        /** Tailscale's own lines in the phone's log since the cell began, before the switch and after it: its path in words, no address in them. */
        private void tailscale(Cell c, String switchStamp) {
            try {
                List<String> lines = HwNetworkSwitch.tailscaleLines(phone.shell(PhoneNet.logSince(c.phoneFrom)));
                List<String> before = lines.stream().filter(l -> !HwNetworkSwitch.atOrAfter(l, switchStamp)).toList();
                List<String> after = lines.stream().filter(l -> HwNetworkSwitch.atOrAfter(l, switchStamp)).toList();
                c.row.put("tailscale before", HwNetworkSwitch.masked(HwNetworkSwitch.tailscalePath(before))).put("tailscale after", HwNetworkSwitch.masked(HwNetworkSwitch.tailscalePath(after)));
                c.log.append("\nTailscale's lines in the phone's log (addresses left out), ").append(lines.size()).append(":\n");
                for (String l : lines.subList(Math.max(0, lines.size() - 300), lines.size())) c.log.append(HwNetworkSwitch.masked(l)).append('\n');
            } catch (RuntimeException e) {
                c.note("the phone's log could not be read for Tailscale's lines: " + e.getMessage());
            }
        }

        // ------------------------------------------------------------------ the armed part

        /**
         * The armed part of a cell (board.mayArm=true): the phone back onto the cell's first network and the link steady,
         * one window of the keeper's, the board armed at throttle minimum, the same switch (its hold capped), the board
         * held to {@link HwLinkMatrix#judge}; ARM off, the window closed by the keeper's word. Skipped - and said - when
         * {@link HwNetworkSwitch#armedSkip} says so.
         */
        private void armedPart(Cell c, HwNetworkSwitch.Cell cell) throws IOException, InterruptedException {
            KeeperClient.State s = state();
            String skip = HwNetworkSwitch.armedSkip(bench.mayArm(), rig.guard.tripped(), s.number("stickLimitMs"));
            if (skip != null) {
                c.row.put("armed part", "skipped: " + skip);
                if (bench.mayArm()) c.note("THE ARMED PART IS SKIPPED: " + skip + ".");
                return;
            }
            if (onto(c, cell.from(), cell.before()) == null) {
                c.row.put("armed part", "not run: the phone was not on its first network again");
                return;
            }
            boolean ready = Waits.until(() -> ui.has("LINK READY"), 60_000);
            if (!c.check(ready && sticksAtBoard(20_000) >= 0, "armed part: the link up and the pilot's sticks at the board before the window")) return;
            List<HwNetworkSwitch.Order> orders = HwNetworkSwitch.armedOrders(cell);
            long hold = orders.get(orders.size() - 1).atMs();
            c.row.put("armed part", "ran");
            int arm = s.number("armAux"), prearm = s.number("prearmAux");
            if (!c.check(arm == bench.armAux(), "armed part: the board arms on AUX" + arm + " and the hardware file says board.armAux=" + bench.armAux()
                    + (arm == bench.armAux() ? "" : " - the radio's hands-off would put the wrong switch down: NOT ARMED"))) {
                return;
            }
            if (!c.check(prearm <= 4, "armed part: the board's PREARM switch is " + (prearm > 0 ? "AUX" + prearm : "none") + (prearm <= 4 ? "" : " - the made-up radio has four switches: NOT ARMED"))) {
                return;
            }
            try {
                rig.guard.openWindow(HwLinkMatrix.WINDOW_S);
            } catch (IllegalStateException no) {
                c.check(false, "armed part: NOT ARMED: " + no.getMessage());
                return;
            }
            long opened = System.currentTimeMillis(), lastCall = opened + (HwLinkMatrix.WINDOW_S - 2) * 1000L;
            BoardTrace trace = new BoardTrace();
            long switchOff = -1;
            try (BoardTrace.Sampler look = trace.sample(bench.keeperPort(), LOOK_MS)) {
                rig.radio.sticksAtRest();
                if (prearm > 0) rig.radio.aux(prearm, 1); // Betaflight arms only while PREARM is on, and wants it on first
                Ui.pause(700);                             // half a second of clean sticks before the switch counts
                rig.radio.aux(arm, 1);
                boolean armed = Waits.until(() -> Boolean.TRUE.equals(state().armed()), 4000);
                if (!c.check(armed, "armed part: ARM on at throttle minimum - the board itself says ARMED" + (armed ? ", " + (System.currentTimeMillis() - opened)
                        + " ms into the window" : " - it did not. " + BoardChainScenario.cannotArm(state())))) {
                    return;
                }
                keeper.order("MARK");
                Ui.pause(800);
                long cutAt = System.currentTimeMillis();
                rig.film.remark(c.name + ": ARMED - the switch, its hold " + hold / 1000 + " s.");
                // the switch's orders on their times; the scenario's own ARM off in the window's last two seconds at the latest,
                // if the board is armed then and the window open (one over with the board armed trips the keeper)
                int next = 0;
                long watchUntil = Math.max(cutAt + hold + 3000, lastCall + 500);
                while ((System.currentTimeMillis() < watchUntil || next < orders.size()) && !Thread.currentThread().isInterrupted()) {
                    long t = System.currentTimeMillis();
                    while (next < orders.size() && t - cutAt >= orders.get(next).atMs()) {
                        phone.network(orders.get(next).command());
                        c.log.append(String.format(Locale.ROOT, "armed %+7d ms  %s%n", System.currentTimeMillis() - cutAt, orders.get(next).command()));
                        next++;
                        t = System.currentTimeMillis();
                    }
                    if (switchOff < 0 && t >= lastCall) {
                        KeeperClient.State now = state();
                        if (now.windowMs() > 0 && !Boolean.FALSE.equals(now.armed()) && !rig.radio.isHandsOff()) {
                            rig.radio.aux(arm, -1);
                            switchOff = System.currentTimeMillis();
                            c.note("armed part: ARM off by the scenario " + (switchOff - cutAt) + " ms into the switch - the window's last two seconds");
                        }
                    }
                    Ui.pause(50);
                }
                boolean switchOn = switchOff < 0 && !rig.radio.isHandsOff();
                long until = switchOff >= 0 ? switchOff : System.currentTimeMillis();
                if (switchOff < 0 && !rig.radio.isHandsOff()) {
                    rig.radio.aux(arm, -1);
                    switchOff = System.currentTimeMillis();
                }
                boolean disarmedNow = Waits.until(() -> Boolean.FALSE.equals(state().armed()), 5000);
                c.check(disarmedNow, "armed part: at its end the board itself says disarmed");
                KeeperClient.State fig = figures(), st = state();
                long delay = st.number("failsafeDelay"); // Betaflight's tenths of a second; not known: its default, 1.5 s
                long disarmedMs = trace.disarmed(cutAt, until);
                HwLinkMatrix.ArmedFacts f = new HwLinkMatrix.ArmedFacts(fig.real("maxGapMs"), delay > 0 ? delay * 100L : 1500, st.number("stickLimitMs"),
                        trace.said("FAILSAFE", cutAt), trace.said("RXLOSS", cutAt), trace.keeperEnded(cutAt), disarmedMs,
                        disarmedMs >= 0 && trace.armedAgain(cutAt + disarmedMs, until), switchOn);
                HwLinkMatrix.ArmedVerdict v = HwLinkMatrix.judge(f);
                c.row.put("armed: stick gap ms", one(f.stickGapMs())).put("armed: board said", trace.words(cutAt)).put("armed: ended by", v.endedBy())
                        .put("armed: re-arm", f.rearmed() ? "RE-ARMED" : disarmedMs >= 0 ? "no" : "-");
                c.log.append("The board's word in the armed part (t = the switch's start), ").append(trace.samples().size()).append(" looks:\n").append(dump(trace, cutAt));
                for (String n : v.notes()) c.note("armed part: " + n);
                for (String w : v.wrong()) c.check(false, "armed part: " + w);
                if (v.wrong().isEmpty()) {
                    c.check(true, "armed part: the board held the rules for this switch - " + v.endedBy() + "; the largest stick gap " + one(f.stickGapMs()) + " ms; " + trace.words(cutAt));
                }
            } finally {
                rig.radio.handsOff();
                if (prearm > 0) rig.radio.aux(prearm, -1);
                String closed = rig.guard.closeWindow();
                c.check(closed.startsWith("OK"), "armed part: the window closed, the board locked and disarmed by the keeper's word: " + closed);
            }
        }

        // ------------------------------------------------------------------ a cell's frame

        /** One cell: its row, its checks (each a line in the film), its notes and its log. */
        private final class Cell {
            final String name;
            final HwLinkMatrix.Row row;
            final List<String> wrong = new ArrayList<>(), notes = new ArrayList<>();
            final StringBuilder log = new StringBuilder();
            final long startMs = System.currentTimeMillis();
            String phoneFrom, pid = "";

            Cell(HwNetworkSwitch.Cell cell) {
                this.name = cell.name();
                row = new HwLinkMatrix.Row(HwNetworkSwitch.COLUMNS).put("cell", name);
            }

            boolean check(boolean ok, String what) {
                rig.check(ok, name + ": " + what);
                if (!ok) wrong.add(what);
                return ok;
            }

            void note(String what) {
                notes.add(what);
                rig.film.remark(name + ": " + what);
            }
        }

        private void begin(Cell c) {
            rig.film.remark("---- " + c.name + " ----");
            phone.checkBattery(); // too hot: the wait is here, with the phone as found; too low: the run ends (PhoneRig.Stop)
            c.phoneFrom = phone.now();
            c.pid = phone.pid();
        }

        /** Whatever happened: the link ended as a pilot ends it, the phone put back as found, its log of the cell read, the row written. */
        private void finish(Cell c) {
            endLink(c);
            if (touched) {
                try {
                    PhoneNet.Back back = putBack();
                    c.row.put("phone put back", back.settled() ? "yes" : back.switches() ? "its switches; not on its network yet" : "NO");
                    c.check(back.switches(), "the phone put back as found after the cell: " + back.words());
                    if (!back.settled() && halt == null) halt = new AssertionError("The phone is not back on its network after " + c.name + ": " + back.words());
                } catch (PhoneRig.Stop | PhoneRig.Refused e) {
                    c.check(false, "the phone could not be put back after the cell: " + e.getMessage());
                    if (halt == null) halt = e;
                } catch (RuntimeException e) {
                    c.check(false, "the phone could not be put back after the cell: " + e);
                    if (halt == null) halt = new AssertionError("The phone could not be put back after " + c.name + ": " + e, e);
                }
            }
            try {
                if (c.phoneFrom != null) {
                    String log = phone.logSince(c.phoneFrom), pidNow = phone.pid();
                    if (c.row.get("phone new sessions").equals("-")) c.row.put("phone new sessions", count(log, PhoneWords.NEW_SESSION) + count(log, PhoneWords.NEW_SESSION_SAME_PC));
                    List<String> socket = log.lines().filter(l -> l.contains(PhoneWords.LINK_SOCKET_CLOSED) || l.contains(PhoneWords.LINK_SOCKET_STUCK)).toList();
                    List<String> errors = log.lines().filter(l -> l.contains("DDLog") && (l.toLowerCase(Locale.ROOT).contains("error") || l.toLowerCase(Locale.ROOT).contains("exception"))).toList();
                    c.row.put("phone socket anew", socket.size()).put("phone errors", errors.isEmpty() ? "0" : errors.size() + ": " + HwNetworkSwitch.masked(shorter(errors.get(0))));
                    if (!socket.isEmpty()) c.note("F-039 ON THE REAL PHONE: its link socket was closed or jammed and opened anew " + socket.size() + " time(s), the first: "
                            + HwNetworkSwitch.masked(shorter(socket.get(0))));
                    if (!errors.isEmpty()) c.note("the phone app's log has " + errors.size() + " line(s) of an error, the first: " + HwNetworkSwitch.masked(shorter(errors.get(0))));
                    boolean crash = log.contains(PhoneWords.FATAL) || log.contains(PhoneWords.ANDROID_FATAL);
                    c.check(!crash && !pidNow.isEmpty() && pidNow.equals(c.pid), "the phone app ran through the cell: " + (crash ? "A CRASH IN ITS LOG" : "no crash in its log")
                            + (pidNow.equals(c.pid) ? ", the same process" : " - ITS PROCESS WAS " + (c.pid.isEmpty() ? "none" : c.pid) + ", NOW " + (pidNow.isEmpty() ? "none" : pidNow)));
                    c.log.append("\nThe phone app's log in the cell:\n").append(log);
                }
            } catch (PhoneRig.Stop | PhoneRig.Refused e) {
                if (halt == null) halt = e;
            } catch (RuntimeException e) {
                c.note("the phone's log could not be read: " + e.getMessage());
            }
            c.row.put("verdict", c.wrong.isEmpty() ? "ok" : "WRONG").put("what", String.join("; ", c.wrong));
            cells++;
            if (!c.wrong.isEmpty()) wrongCells++;
            else rig.film.remark(c.name + ": every check held.");
            write(c);
        }

        private void write(Cell c) {
            try {
                HwLinkMatrix.append(matrix, HwNetworkSwitch.TABLE, c.row);
                Path logs = Files.createDirectories(matrix.resolve("logs"));
                String text = "Checks that went wrong: " + (c.wrong.isEmpty() ? "none" : String.join("; ", c.wrong)) + "\nSaid: " + String.join("; ", c.notes)
                        + "\n\nThe switch, its orders (t = its start):\n" + c.log;
                Files.writeString(logs.resolve((HwNetworkSwitch.TABLE + "-" + c.name).replaceAll("[^A-Za-z0-9._-]+", "_") + ".log"), text, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                rig.check(false, c.name + ": its row could not be written: " + e.getMessage());
            }
        }

        private void stopped(Cell c, Throwable t) {
            if (t instanceof PhoneRig.Stop || t instanceof PhoneRig.Refused) {
                halt = (AssertionError) t;
                c.check(false, "the run stops here: " + t.getMessage());
                return;
            }
            if (t instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                halt = new AssertionError("The scenario was interrupted (its time was up?) in " + c.name, t);
                c.check(false, "interrupted");
                return;
            }
            String why = t instanceof AssertionError ? String.valueOf(t.getMessage()) : t.toString();
            c.check(false, "the cell stopped: " + (why.length() > 400 ? why.substring(0, 400) + " ..." : why));
        }

        /** The link ended as a pilot ends it: STANDBY MODE - or STOP for a link that never came up. */
        private void endLink(Cell c) {
            try {
                if (ui.has("STANDBY MODE")) {
                    ui.click("STANDBY MODE");
                    long end = System.currentTimeMillis() + 2000;
                    while (System.currentTimeMillis() < end && !ui.has("Keep flying") && !ui.has("PILOT UAV")) Ui.pause(100);
                    if (ui.has("Keep flying") && Boolean.FALSE.equals(state().armed())) {
                        rig.film.remark(c.name + ": STANDBY MODE asked first (" + String.join(" / ", ui.texts().stream().filter(t -> t.contains("armed")).limit(2).toList())
                                + "); the board is disarmed by its own word: answered Standby mode.");
                        ui.click("Standby mode");
                    }
                } else {
                    Pilot.endLink(ui);
                }
                ui.waitForControl("PILOT UAV", 30_000);
            } catch (RuntimeException | AssertionError e) {
                c.check(false, "the link could not be ended as a pilot ends it (STANDBY MODE, or STOP): " + e.getMessage());
            }
        }

        // ------------------------------------------------------------------ helpers

        /** PILOT UAV on the phone chosen; ms to LINK READY, -1: not within maxMs. */
        private long connect(long maxMs) {
            ui.waitForControl("PILOT UAV", 30_000);
            long t0 = System.currentTimeMillis();
            ui.click("PILOT UAV");
            while (!ui.has("LINK READY")) {
                if (System.currentTimeMillis() - t0 > maxMs || Thread.currentThread().isInterrupted()) return -1;
                Ui.pause(50);
            }
            return System.currentTimeMillis() - t0;
        }

        /** A MARK at the keeper; ms until the board takes the pilot's sticks at their rate ({@link HwLinkMatrix#pilotsSticks}), -1: not within maxMs. */
        private long sticksAtBoard(long maxMs) throws IOException {
            keeper.order("MARK");
            long from = System.currentTimeMillis(), lastAt = from, lastFrames = 0;
            while (System.currentTimeMillis() - from <= maxMs && !Thread.currentThread().isInterrupted()) {
                Ui.pause(100);
                long now = System.currentTimeMillis(), frames = figures().number("frames");
                if (HwLinkMatrix.pilotsSticks(frames - lastFrames, now - lastAt)) return now - from;
                lastFrames = frames;
                lastAt = now;
            }
            return -1;
        }

        private static void pauseUntil(long epochMs) {
            for (long left = epochMs - System.currentTimeMillis(); left > 0 && !Thread.currentThread().isInterrupted(); left = epochMs - System.currentTimeMillis()) {
                Ui.pause(Math.min(left, 200));
            }
        }

        /** The app's link log read again and again (its rows reach the file within a second or two) until it says, or the time is up: -1. */
        private long waitRows(Cell c, long untilMs, ToLongFunction<List<AppLinkLog.Row>> find) {
            while (true) {
                long found = find.applyAsLong(AppLinkLog.read(rig.sandbox.logsDir(), c.startMs));
                if (found >= 0 || System.currentTimeMillis() > untilMs || Thread.currentThread().isInterrupted()) return found;
                Ui.pause(500);
            }
        }

        private KeeperClient.State state() {
            try {
                return keeper.state();
            } catch (IOException e) {
                throw new AssertionError("The keeper does not answer: " + e.getMessage());
            }
        }

        /** The stick frames at the board since the keeper's last MARK. */
        private KeeperClient.State figures() {
            try {
                return KeeperClient.State.of(keeper.order("FIGURES"));
            } catch (IOException e) {
                throw new AssertionError("The keeper does not answer: " + e.getMessage());
            }
        }

        /** A trace's looks, one a line: ms from t, armed, the blocks, the window, the keeper's end. */
        private static String dump(BoardTrace trace, long t) {
            StringBuilder s = new StringBuilder();
            for (BoardTrace.Sample x : trace.samples()) {
                s.append(String.format(Locale.ROOT, "%+7d ms  armed=%s  blocks=%s  window=%d%s%n", x.atMs() - t, x.armed() == null ? "?" : x.armed() ? "1" : "0", x.blocks(),
                        x.windowMs(), x.lastEnd() == null ? "" : "  lastEnd=" + x.lastEnd()));
            }
            return s.toString();
        }
    }

    /** What the app has played of the phone's microphone so far, in bytes (the bench's silent loudspeaker counts it: the app's "liveAudio" lines). */
    static long soundBytes() {
        long n = 0;
        for (SilentSound.Played p : SilentSound.played()) if ("liveAudio".equals(p.thread)) n += p.bytes;
        return n;
    }

    /** The phone app's link socket parked for want of Tailscale (its own line; not the one that says it was so before). */
    private static final String PARKED = PhoneWords.LINK_SOCKET + " " + PhoneWords.NO_TAILSCALE;

    private static long count(String text, String part) {
        long n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    private static String shorter(String line) {
        String s = line.strip();
        return s.length() > 200 ? s.substring(0, 200) + " ..." : s;
    }

    /** The PHONE list's chosen line: "hardware-phone   online", or "No phone seen yet". */
    private static String chosenPhone(Ui ui) {
        return String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem()));
    }
}
