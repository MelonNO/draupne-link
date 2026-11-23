package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.BadLink;
import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.PhoneWords;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.testing.Waits;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

import static de.droiddrone.bench.hw.HwLinkMatrix.ms;
import static de.droiddrone.bench.hw.HwLinkMatrix.one;

/**
 * The bad-network box on the real phone (R8): the bad-link table ({@code link/LinkMatrixScenario}) - until now against a
 * made-up drone only - between the real, sealed Windows app and the one real phone, with the sticks counted where they
 * end: at the real flight controller, through the keeper. The bench's wire stands between the app and the phone (the
 * app reaches it on this PC's loopback, {@link AppRig.Plan#phoneThroughWire}; the wire alone speaks to the phone's
 * address, the one way the hardware run's fence has), so every kind of bad network and every cut comes on top of
 * whatever the real network does. The phone app is the bench build that is on the phone, told to reach its flight
 * controller through the keeper as {@link BoardChainScenario} does it. Battery in or out, <b>no propellers</b>.
 * <ul>
 * <li><b>steady</b> - the network bad in one way from before PILOT UAV (delay, jitter, loss, bursts, a narrow band;
 * {@link HwLinkMatrix#steady}): the link comes up; then, for 40 s, the stick frames at the board (the keeper's figures:
 * frames a second, the largest gap, the 99th percentile), the picture, the app's own link figures from its link log,
 * the board's own word (RXLOSS, armed) - and nothing armed; the network good again, LINK READY.</li>
 * <li><b>cuts</b> - a good network gone for 1, 3, 10 and 60 s, both ways and one way: the link back by itself and in
 * how long, the picture back and in how long, the pilot's sticks at the board again and in how long, the largest
 * stick gap at the board, what the board itself said (RXLOSS, FAILSAFE: written down - a disarmed board gets the
 * phone's idle sticks 300 ms into a cut until the phone counts the link lost, so its RXLOSS may be short), the
 * phone's new sessions (F-001's rule), and the phone app's log for a jammed socket (F-039: counted and said - a real
 * phone's figures are the open question) and for errors (said), a crash (failed).</li>
 * <li><b>armed</b> - only when the hardware file says {@code board.mayArm=true}: per cut cell, after its link part,
 * one window of the keeper's ({@link HwLinkMatrix#WINDOW_S} s, throttle minimum, PREARM then ARM), the same cut while
 * the board is armed, and the board held to {@link HwLinkMatrix#judge}: no failsafe for a short cut, a failsafe or
 * the keeper's end of the window for a long one, no re-arm. The scenario's own ARM off comes in the window's last
 * two seconds at the latest (a window over with the board armed is a trip of the keeper's). Without the word all of
 * that is skipped, the film says so, the rows say "skipped", and the run passes on the link parts.</li>
 * </ul>
 * A row a cell into matrix/hw-steady.tsv and matrix/hw-cuts.tsv in the run's folder, each cell's phone log and the
 * board's word beside them (matrix/logs), plain check lines in the film. {@code -Dbench.quick=true} (bench.ps1's
 * {@code -Quick}) runs two steady cells and two cuts, about five minutes; the whole table about 25 minutes, 30 with
 * the armed part.
 * <p>
 * Without a hardware file, or with one that names no keeper, it skips itself.
 */
@Tag("hw")
class HwLinkMatrixScenario {
    private static final long SEED = Long.getLong("bench.seed", 20261119L);
    private static final boolean QUICK = Boolean.getBoolean("bench.quick");
    /** How long the link, the picture and the sticks get to come back once the network is good again. */
    private static final long BACK_MS = 30_000;
    /** How often the board's word is looked at (the keeper's state) while a cell measures. */
    private static final long LOOK_MS = 100;

    @Test
    @Timeout(value = 100, unit = TimeUnit.MINUTES)
    void eachBadNetworkAndEachCutBetweenTheRealAppAndTheRealPhoneWithTheSticksCountedAtTheBoard() throws Exception {
        Assumptions.assumeTrue(Hardware.present(), "This bench has no hardware set up: no " + Hardware.FILE
                + " in %LOCALAPPDATA%\\DraupneBench (start from " + Hardware.EXAMPLE + ")");
        Hardware bench = Hardware.load();
        Assumptions.assumeTrue(bench.hasKeeper(), "This bench's hardware file names no keeper (keeper.port): no flight controller on this PC's USB to test");
        KeeperClient.State atStart = keeperReady(bench);
        List<HwLinkMatrix.Steady> steady = HwLinkMatrix.steady(SEED, QUICK);
        List<HwLinkMatrix.Cut> cuts = HwLinkMatrix.cuts(QUICK);
        AppRig.scenario("hw-link-matrix").hardware(bench).phoneThroughWire().run(rig -> new Box(rig, bench, atStart).run(steady, cuts));
    }

    /** The keeper up and the board taken, disarmed and locked, before anything else is started (as BoardChainScenario asks it). */
    private static KeeperClient.State keeperReady(Hardware bench) {
        KeeperClient.State s;
        try (KeeperClient keeper = new KeeperClient(bench.keeperPort(), 5000)) {
            s = keeper.state();
            for (int i = 0; i < 60 && !s.boardUp(); i++) { // it may still be taking the board
                Ui.pause(500);
                s = keeper.state();
            }
        } catch (IOException e) {
            throw new AssertionError("No keeper answers on 127.0.0.1:" + bench.keeperPort() + " (" + e.getMessage() + "). Start it first: .\\bench.ps1 hw does, "
                    + "or gradlew fcKeeper in a window of its own.");
        }
        if (!s.boardUp()) throw new AssertionError("The keeper has no board (is it on USB, is its port the hardware file's board.port?): " + s);
        if (s.tripped() != null) throw new AssertionError("The keeper has tripped and was not cleared: " + s.tripped());
        if (!Boolean.FALSE.equals(s.armed()) || !Boolean.TRUE.equals(s.locked())) {
            throw new AssertionError("The board is not disarmed and locked before the scenario begins: " + s);
        }
        return s;
    }

    /** One run of the box: the app, the phone, the wire, the keeper - and the cells one after the other. */
    private static final class Box {
        private final AppRig rig;
        private final Ui ui;
        private final Hardware bench;
        private final KeeperClient.State atStart;
        private final Path matrix = Sandbox.runRoot().resolve("matrix");
        private KeeperClient keeper;
        private PhoneRig phone;
        private Wire wire;
        private int cells, wrongCells;
        /** What ends the run here: the phone's battery (PhoneRig.Stop), a command the phone's guard refused, the scenario's time. */
        private AssertionError halt;

        Box(AppRig rig, Hardware bench, KeeperClient.State atStart) {
            this.rig = rig;
            this.ui = rig.ui;
            this.bench = bench;
            this.atStart = atStart;
        }

        void run(List<HwLinkMatrix.Steady> steady, List<HwLinkMatrix.Cut> cuts) throws Exception {
            ui.autoShot(false); // an hour of PILOT UAV and STANDBY: the pictures are the cells' own and the WRONG ones
            rig.film.remark(plan(steady, cuts));
            try (KeeperClient k = rig.guard.keeper()) { // (the rig's guard has told the keeper the scenario's name: its frames are in frames-hw-link-matrix.log)
                keeper = k;
                rig.check(rig.radio.isHandsOff() && rig.radio.ceiling() == Hardware.THROTTLE_CEILING, "the radio starts hands off, its throttle ceiling at " + Hardware.THROTTLE_CEILING);
                PhoneRig.Target target = PhoneRig.Target.device();
                if (!target.benchBuild()) {
                    throw new AssertionError("phone.package names " + target.app() + ": only the bench build (flight-app\\build.ps1 -Bench) can reach a flight controller through the keeper");
                }
                try (PhoneRig p = new PhoneRig(rig.film, target)) {
                    phone = p;
                    phone.ensureRunning(); // a real phone: its log followed into the film's folder, its battery looked at
                    throughTheKeeper();
                    InetSocketAddress at = phone.linkAddress(); // the hardware file's address: the fence's one way through
                    try (Wire w = new Wire(at.getAddress(), at.getPort())) {
                        wire = w;
                        wire.rule(BadLink.clean(SEED));
                        // announced as the virtual fleet announces its phones: an address on this PC and the wire's port
                        rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
                        rig.fleet.startHeartbeat();
                        ui.until("the real phone, behind the bench's wire, online in the PHONE list", 90_000, () -> chosenPhone(ui).contains("online"));
                        rig.film.remark("The PHONE list: \"" + chosenPhone(ui).trim() + "\" - the real phone through the bench's wire (VIRTUAL: the app reaches it on this PC).");
                        KeeperClient.State s = state();
                        rig.film.remark("The board, by the keeper: failsafe " + s.get("failsafe") + " after failsafe_delay " + s.get("failsafeDelay") + "; the keeper ends an arm window "
                                + (s.number("stickLimitMs") > 0 ? "on RXLOSS, or " + s.number("stickLimitMs") + " ms after the last stick frame at the latest"
                                : s.number("stickLimitMs") == 0 ? "NOT by itself (board.letFailsafeRun)" : "- it has no failsafe_delay to go by: NO ARM WINDOW")
                                + "; " + BoardChainScenario.cannotArm(s));
                        for (HwLinkMatrix.Steady c : steady) {
                            steadyCell(c);
                            goOn();
                        }
                        for (HwLinkMatrix.Cut c : cuts) {
                            cutCell(c);
                            goOn();
                        }
                        rig.film.remark("THE BOX: " + cells + " cell(s), " + wrongCells + " with something wrong. Its tables: " + matrix.resolve("hw-steady.tsv") + " and "
                                + matrix.resolve("hw-cuts.tsv") + "; each cell's phone log and the board's word in " + matrix.resolve("logs") + ".");
                        rig.film.remark("The phone app is left with debug.draupne.fc=tcp:" + atStart.number("phonePort")
                                + " (gone with the phone's next restart; adb shell setprop debug.draupne.fc usb puts USB back).");
                    }
                }
            } finally {
                rig.radio.handsOff();
            }
        }

        /** The next cell, unless something ended the run: the phone's battery, a refused command, the scenario's time. */
        private void goOn() {
            if (halt != null) throw halt;
            if (Thread.currentThread().isInterrupted()) throw new AssertionError("The scenario was interrupted (its time was up?) after " + cells + " cell(s)");
        }

        private String plan(List<HwLinkMatrix.Steady> steady, List<HwLinkMatrix.Cut> cuts) {
            List<String> s = steady.stream().map(c -> c.net().name).toList(), c = cuts.stream().map(HwLinkMatrix.Cut::name).toList();
            return "THE BAD-NETWORK BOX ON THE REAL PHONE (R8)" + (QUICK ? " - QUICK: a few cells only" : "") + ": " + steady.size() + " steady network(s) ("
                    + String.join("; ", s) + "), " + (steady.isEmpty() ? 0 : steady.get(0).measureMs() / 1000) + " s each once the sticks reach the board; "
                    + cuts.size() + " cut(s) on a good network (" + String.join("; ", c) + "); about "
                    + Math.round(HwLinkMatrix.estimateMs(steady, cuts, bench.mayArm()) / 60_000.0) + " min. "
                    + (bench.mayArm() ? "THE ARMED PART IS ON (board.mayArm=true): per cut cell one window of the keeper's, " + HwLinkMatrix.WINDOW_S
                    + " s, throttle minimum, PREARM then ARM, the cut while armed (" + HwLinkMatrix.ARMED_CUT_MAX_MS / 1000 + " s at most)."
                    : "THE ARMED PART IS SKIPPED: the hardware file does not say board.mayArm=true - every cell's link part runs, no arm window is asked "
                    + "for, nothing is armed, and the rows say \"skipped\".");
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

        // ------------------------------------------------------------------ steady

        private void steadyCell(HwLinkMatrix.Steady s) {
            Cell c = new Cell("hw-steady", s.name(), HwLinkMatrix.STEADY_COLUMNS);
            BadLink net = s.net();
            try {
                begin(c);
                wire.rule(net);
                LocalTime asked = LocalTime.now();
                long ready = connect(60_000);
                c.row.put("ready ms", ms(ready));
                if (!c.check(ready >= 0, "the link comes up on this network: " + (ready < 0 ? "no LINK READY within 60 s of PILOT UAV" : "LINK READY " + ready + " ms after PILOT UAV"))) {
                    return;
                }
                ui.shot(c.name + ": LINK READY");
                long sticks = sticksAtBoard(20_000);
                // measured all the same: how the sticks fare on this network is what the cell is for
                c.note(sticks < 0 ? "the pilot's sticks did not reach the board at their rate (80 a second or more for a tenth of a second) within 20 s of LINK READY"
                        : "the pilot's sticks at the board " + sticks + " ms after LINK READY");

                keeper.order("MARK");
                BoardTrace trace = new BoardTrace();
                long fromMs = System.currentTimeMillis();
                LocalTime from = LocalTime.now();
                try (BoardTrace.Sampler look = trace.sample(bench.keeperPort(), LOOK_MS)) {
                    Ui.pause(s.measureMs());
                }
                LocalTime to = LocalTime.now();
                KeeperClient.State fig = figures();
                Ui.pause(2000); // the link log's last rows of the stretch onto its file
                AppLinkLog.Figures app = AppLinkLog.figures(AppLinkLog.between(AppLinkLog.read(rig.sandbox.logsDir(), c.startMs), from, to));
                boolean armed = trace.everArmed(fromMs);
                c.row.put("ready %", app.readyPercent()).put("left READY", app.leftReady()).put("ping ms median", app.pingMedian()).put("ping ms max", app.pingMax())
                        .put("telemetry gap ms max", app.telemetryGapMax()).put("picture fps mean", one(app.videoFpsMean())).put("picture %", app.picturePercent())
                        .put("sticks/s at board", one(fig.real("perSecond"))).put("stick gap ms max", one(fig.real("maxGapMs")))
                        .put("stick gap ms p99", one(fig.real("p99GapMs"))).put("board RXLOSS %", trace.percent("RXLOSS", fromMs)).put("board armed", armed ? "ARMED" : "no");
                c.log.append("The board's word, ").append(trace.samples().size()).append(" looks:\n").append(dump(trace, fromMs));
                rig.film.remark(c.name + " - the app's own link figures over " + s.measureMs() / 1000 + " s: " + app.words() + ".");
                rig.film.remark(c.name + " - at the board: " + sticksWords(fig) + "; the board said RXLOSS in " + trace.percent("RXLOSS", fromMs) + " % of the keeper's looks.");
                c.check(fig.number("frames") > 0, "stick frames reach the board all along: " + fig.number("frames") + " in " + s.measureMs() / 1000 + " s, "
                        + one(fig.real("perSecond")) + " a second, the largest gap " + one(fig.real("maxGapMs")) + " ms, the 99th percentile " + one(fig.real("p99GapMs")) + " ms");
                c.check(app.pictureRows() > 0, "the picture flows: in " + app.picturePercent() + " % of the app's rows, " + one(app.videoFpsMean()) + " frames a second in the mean");
                c.check(!armed && app.appArmed() == 0 && !ui.sees("FC: ARMED"), "nothing armed: the board says disarmed at every look, the app too"
                        + (armed ? " - THE BOARD SAID ARMED" : "") + (app.appArmed() > 0 ? " - the app's log has ARMED in " + app.appArmed() + " row(s)" : ""));
                if (app.rows() == 0) c.note("the app's link log had no rows for the stretch (" + asked + " on): its figures are not known");

                net.heal(); // the network good again (its fixed delay stays: it is the path)
                long again = timeTo(System.currentTimeMillis(), BACK_MS, () -> ui.has("LINK READY"));
                c.row.put("ready again ms", ms(again));
                c.check(again >= 0, "the network good again: " + (again < 0 ? "no LINK READY within 30 s" : "LINK READY" + (again <= 100 ? ", as it was" : " " + again + " ms on")));
            } catch (Throwable t) {
                stopped(c, t);
            } finally {
                finish(c, net);
            }
        }

        // ------------------------------------------------------------------ cuts

        private void cutCell(HwLinkMatrix.Cut cut) {
            Cell c = new Cell("hw-cuts", cut.name(), HwLinkMatrix.CUT_COLUMNS);
            c.row.put("cut ms", cut.ms()).put("way", HwLinkMatrix.wayWords(cut.way()));
            BadLink net = BadLink.clean(SEED);
            try {
                begin(c);
                wire.rule(net);
                LocalTime asked = LocalTime.now();
                long ready = connect(30_000);
                c.row.put("ready ms", ms(ready));
                if (!c.check(ready >= 0, "the link comes up on a good network: " + (ready < 0 ? "no LINK READY within 30 s of PILOT UAV" : "LINK READY " + ready + " ms after PILOT UAV"))) {
                    return;
                }
                long sticks = sticksAtBoard(20_000);
                if (!c.check(sticks >= 0, "stick frames reach the board before the cut" + (sticks < 0 ? ": none within 20 s of LINK READY" : ""))) return;
                long picture = waitRows(c, System.currentTimeMillis() + 20_000, rows -> AppLinkLog.first(rows, asked, AppLinkLog.Row::picture));
                if (!c.check(picture >= 0, "the picture comes before the cut" + (picture < 0 ? ": none in the app's link log within 20 s" : ""))) return;
                Ui.pause(2000);

                String cutStamp = phone.now();
                keeper.order("MARK");
                BoardTrace trace = new BoardTrace();
                long cutAt, back, readyAgain = -1, sticksAgain = -1;
                LocalTime cutFrom, backAt;
                try (BoardTrace.Sampler look = trace.sample(bench.keeperPort(), LOOK_MS)) {
                    cutFrom = LocalTime.now();
                    cutAt = System.currentTimeMillis();
                    net.cut(cut.ms(), cut.way());
                    rig.film.remark(c.name + ": THE NETWORK IS CUT (" + HwLinkMatrix.wayWords(cut.way()) + ") for " + cut.ms() / 1000 + " s.");
                    pauseUntil(cutAt + cut.ms());
                    backAt = LocalTime.now();
                    back = System.currentTimeMillis();
                    // the link and the pilot's sticks, each timed from the network's return (a tenth of a second at the
                    // pilot's rate: the phone's own idle sticks may reach a disarmed board meanwhile, 20 a second)
                    long lastFrames = figures().number("frames"), lastAt = back;
                    while ((readyAgain < 0 || sticksAgain < 0) && System.currentTimeMillis() - back <= BACK_MS && !Thread.currentThread().isInterrupted()) {
                        long now = System.currentTimeMillis();
                        if (readyAgain < 0 && ui.has("LINK READY")) readyAgain = now - back;
                        if (sticksAgain < 0 && now - lastAt >= 100) {
                            long frames = figures().number("frames");
                            if (HwLinkMatrix.pilotsSticks(frames - lastFrames, now - lastAt)) sticksAgain = now - back;
                            lastFrames = frames;
                            lastAt = now;
                        }
                        Ui.pause(50);
                    }
                    pauseUntil(back + 5000); // the board's word after the cut: its RXLOSS gone again
                }
                KeeperClient.State fig = figures();
                // the picture: from the app's link log, by its rows' own times (on its file within a second or two)
                List<AppLinkLog.Row> rows = AppLinkLog.read(rig.sandbox.logsDir(), c.startMs), inCut = AppLinkLog.between(rows, cutFrom, backAt);
                // no row of the cut on the file: whether it stopped is not known, and it is waited for as if it had
                boolean pictureStopped = inCut.isEmpty() || inCut.stream().anyMatch(r -> !r.picture());
                if (inCut.isEmpty()) c.note("the app's link log has no row of the cut's time: the picture is taken for stopped");
                long pictureAgain = pictureStopped ? waitRows(c, back + BACK_MS, rs -> AppLinkLog.first(rs, backAt, AppLinkLog.Row::picture)) : 0;
                boolean left = AppLinkLog.between(rows, cutFrom, LocalTime.now()).stream().anyMatch(r -> !r.ready());
                long rxAt = trace.first("RXLOSS", cutAt), rxLast = trace.last("RXLOSS", cutAt);
                boolean failsafe = trace.said("FAILSAFE", cutAt), armed = trace.everArmed(0);
                c.row.put("left READY", left ? "yes" : "no").put("ready again ms", ms(readyAgain)).put("picture stopped", pictureStopped ? "yes" : "no")
                        .put("picture again ms", ms(pictureAgain)).put("sticks again ms", ms(sticksAgain)).put("stick gap ms max", one(fig.real("maxGapMs")))
                        .put("board RXLOSS at ms", ms(rxAt)).put("board RXLOSS for ms", rxAt < 0 ? "-" : Long.toString(rxLast - rxAt))
                        .put("board FAILSAFE", failsafe ? "yes" : "no").put("board armed", armed ? "ARMED" : "no");
                c.log.append("The board's word around the cut (t = the cut's start), ").append(trace.samples().size()).append(" looks:\n").append(dump(trace, cutAt));

                c.check(readyAgain >= 0, "the link back by itself: " + (readyAgain < 0 ? "no LINK READY within 30 s of the network" : "LINK READY " + readyAgain
                        + " ms after the network" + (left ? "" : " (the app's link log never left READY: the header said LINK STALLED, if anything)")));
                c.check(pictureAgain >= 0, pictureStopped ? "the picture back by itself: " + (pictureAgain < 0 ? "none within 30 s of the network" : pictureAgain + " ms after the network")
                        : "the picture never stopped (frames in every row of the app's link log through the cut)");
                c.check(sticksAgain >= 0, "the pilot's sticks reach the board again: " + (sticksAgain < 0 ? "not within 30 s of the network" : sticksAgain + " ms after the network")
                        + "; the largest gap at the board " + one(fig.real("maxGapMs")) + " ms");
                c.check(!armed, "nothing armed: the board says disarmed at every look");
                // the board's own word, written down: a disarmed board gets the phone's idle sticks 300 ms into a cut, until the
                // phone counts the link lost without a Disconnect (then none, and the drone beeps) - so its RXLOSS may be short
                String boardSaid = trace.words(cutAt);
                c.note("the board's own word: " + boardSaid + "; the largest stick gap at the board " + one(fig.real("maxGapMs")) + " ms"
                        + (cut.way() == BadLink.Way.TO_PC ? " (the phone heard the PC all through)" : ""));
                String during = phone.logSince(cutStamp);
                long sessions = count(during, PhoneWords.NEW_SESSION) + count(during, PhoneWords.NEW_SESSION_SAME_PC);
                c.row.put("phone new sessions", sessions);
                long most = HwLinkMatrix.sessionsAtMost(cut);
                c.check(sessions <= most, "the phone began " + sessions + " new session(s) for the cut (" + most + " at most: F-001, one when the link goes, one when it is back"
                        + (cut.way() == BadLink.Way.TO_PC ? ", one for each 4 s it heard the PC without being heard" : "") + ")");
                rig.film.remark(c.name + " - LINK READY " + ms(readyAgain) + " ms, the picture " + (pictureStopped ? ms(pictureAgain) + " ms" : "never stopped")
                        + ", the sticks at the board " + ms(sticksAgain) + " ms after the network; at the board " + sticksWords(fig) + "; the board said " + boardSaid + ".");

                if (readyAgain >= 0) armedPart(c, cut, net);
                else c.row.put("armed part", "not run: the link did not come back");
            } catch (Throwable t) {
                stopped(c, t);
            } finally {
                finish(c, net);
            }
        }

        /**
         * The armed part of a cut cell (board.mayArm=true): one window of the keeper's, the board armed at throttle
         * minimum, the same cut, the board held to {@link HwLinkMatrix#judge}; ARM off, the window closed by the
         * keeper's word. Skipped - and said - when {@link HwLinkMatrix#armedSkip} says so.
         */
        private void armedPart(Cell c, HwLinkMatrix.Cut cut, BadLink net) throws IOException, InterruptedException {
            KeeperClient.State s = state();
            String skip = HwLinkMatrix.armedSkip(bench.mayArm(), rig.guard.tripped(), s.number("stickLimitMs"), cut);
            if (skip != null) {
                c.row.put("armed part", "skipped: " + skip);
                if (bench.mayArm()) c.note("THE ARMED PART IS SKIPPED: " + skip + ".");
                return;
            }
            long armedCut = HwLinkMatrix.armedCutMs(cut);
            c.row.put("armed part", "ran").put("armed cut ms", armedCut);
            int arm = s.number("armAux"), prearm = s.number("prearmAux");
            if (!c.check(arm == bench.armAux(), "armed part: the board arms on AUX" + arm + " and the hardware file says board.armAux=" + bench.armAux()
                    + (arm == bench.armAux() ? "" : " - the radio's hands-off would put the wrong switch down: NOT ARMED"))) {
                return;
            }
            if (!c.check(prearm <= 4, "armed part: the board's PREARM switch is " + (prearm > 0 ? "AUX" + prearm : "none") + (prearm <= 4 ? "" : " - the made-up radio has four switches: NOT ARMED"))) {
                return;
            }
            if (!c.check(ui.has("LINK READY") && sticksAtBoard(10_000) >= 0, "armed part: the link up and stick frames at the board before the window")) return;
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
                // with the board's own failsafe let run nothing ends the window but its time: the cut and the way back
                // have to fit before the scenario's last call, or the board would still be armed when the time is over
                if (s.number("stickLimitMs") == 0 && System.currentTimeMillis() + 800 + armedCut + 1500 > lastCall) {
                    c.note("armed part: NO CUT - the arm took too much of the window to fit a " + armedCut / 1000 + " s cut before its last two seconds, "
                            + "with the board's own failsafe let run; ARM off");
                    rig.radio.aux(arm, -1);
                    c.check(Waits.until(() -> Boolean.FALSE.equals(state().armed()), 5000), "armed part: ARM off - the board itself says disarmed");
                    return;
                }
                keeper.order("MARK");
                Ui.pause(800);
                long cutAt = System.currentTimeMillis();
                net.cut(armedCut, cut.way());
                rig.film.remark(c.name + ": ARMED, THE NETWORK IS CUT (" + HwLinkMatrix.wayWords(cut.way()) + ") for " + armedCut / 1000 + " s.");
                // the cut, and three seconds after it with the switch as it is; the scenario's own ARM off in the window's last
                // two seconds at the latest, if the board is armed then and the window open (one over with the board armed trips the keeper)
                long watchUntil = cutAt + armedCut + 3000;
                while (System.currentTimeMillis() < watchUntil && !Thread.currentThread().isInterrupted()) {
                    if (switchOff < 0 && System.currentTimeMillis() >= lastCall) {
                        KeeperClient.State now = state();
                        if (now.windowMs() > 0 && !Boolean.FALSE.equals(now.armed()) && !rig.radio.isHandsOff()) {
                            rig.radio.aux(arm, -1);
                            switchOff = System.currentTimeMillis();
                            c.note("armed part: ARM off by the scenario " + (switchOff - cutAt) + " ms into the cut - the window's last two seconds");
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
                c.log.append("The board's word in the armed part (t = the cut's start), ").append(trace.samples().size()).append(" looks:\n").append(dump(trace, cutAt));
                for (String n : v.notes()) c.note("armed part: " + n);
                for (String w : v.wrong()) c.check(false, "armed part: " + w);
                if (v.wrong().isEmpty()) {
                    c.check(true, "armed part: the board held the rules for this cut - " + v.endedBy() + "; the largest stick gap " + one(f.stickGapMs()) + " ms; " + trace.words(cutAt));
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
            final String table, name;
            final HwLinkMatrix.Row row;
            final List<String> wrong = new ArrayList<>(), notes = new ArrayList<>();
            final StringBuilder log = new StringBuilder();
            final long startMs = System.currentTimeMillis();
            String phoneFrom, pid = "";

            Cell(String table, String name, List<String> columns) {
                this.table = table;
                this.name = name;
                row = new HwLinkMatrix.Row(columns).put("cell", name);
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
            phone.checkBattery(); // too hot: the wait is here, with the link down; too low: the run ends (PhoneRig.Stop)
            c.phoneFrom = phone.now();
            c.pid = phone.pid();
        }

        /** Whatever happened: the link ended as a pilot ends it, the phone's log of the cell read, the row written. */
        private void finish(Cell c, BadLink net) {
            endLink(c);
            try {
                if (c.phoneFrom != null) {
                    String log = phone.logSince(c.phoneFrom), pidNow = phone.pid();
                    if (c.row.get("phone new sessions").equals("-")) c.row.put("phone new sessions", count(log, PhoneWords.NEW_SESSION) + count(log, PhoneWords.NEW_SESSION_SAME_PC));
                    List<String> socket = log.lines().filter(l -> l.contains(PhoneWords.LINK_SOCKET_CLOSED) || l.contains(PhoneWords.LINK_SOCKET_STUCK)).toList();
                    List<String> errors = log.lines().filter(l -> l.contains("DDLog") && (l.toLowerCase(Locale.ROOT).contains("error") || l.toLowerCase(Locale.ROOT).contains("exception"))).toList();
                    c.row.put("phone socket anew", socket.size()).put("phone errors", errors.isEmpty() ? "0" : errors.size() + ": " + shorter(errors.get(0)));
                    if (!socket.isEmpty()) c.note("F-039 ON THE REAL PHONE: its link socket was closed or jammed and opened anew " + socket.size() + " time(s), the first: " + shorter(socket.get(0)));
                    if (!errors.isEmpty()) c.note("the phone app's log has " + errors.size() + " line(s) of an error, the first: " + shorter(errors.get(0)));
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
            c.row.put("network counts", net.counts()).put("verdict", c.wrong.isEmpty() ? "ok" : "WRONG").put("what", String.join("; ", c.wrong));
            cells++;
            if (!c.wrong.isEmpty()) wrongCells++;
            else rig.film.remark(c.name + ": every check held.");
            try {
                HwLinkMatrix.append(matrix, c.table, c.row);
                Path logs = Files.createDirectories(matrix.resolve("logs"));
                String text = "Checks that went wrong: " + (c.wrong.isEmpty() ? "none" : String.join("; ", c.wrong)) + "\nSaid: " + String.join("; ", c.notes) + "\n\n" + c.log;
                Files.writeString(logs.resolve((c.table + "-" + c.name).replaceAll("[^A-Za-z0-9._-]+", "_") + ".log"), text, StandardCharsets.UTF_8);
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

        /** The link ended as a pilot ends it, on a good network: STANDBY MODE - or STOP for a link that never came up. */
        private void endLink(Cell c) {
            try {
                wire.rule(BadLink.clean(SEED));
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
            return timeTo(t0, maxMs, () -> ui.has("LINK READY"));
        }

        /**
         * A MARK at the keeper; ms until the board takes the pilot's sticks - a tenth of a second at their rate
         * ({@link HwLinkMatrix#pilotsSticks}: not the phone's own idle sticks) - -1: not within maxMs.
         */
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

        private static long timeTo(long fromMs, long maxMs, java.util.function.BooleanSupplier ok) {
            while (!ok.getAsBoolean()) {
                if (System.currentTimeMillis() - fromMs > maxMs || Thread.currentThread().isInterrupted()) return -1;
                Ui.pause(50);
            }
            return System.currentTimeMillis() - fromMs;
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

        private static String sticksWords(KeeperClient.State fig) {
            return fig.number("frames") + " stick frames, " + one(fig.real("perSecond")) + " a second, the largest gap " + one(fig.real("maxGapMs")) + " ms, the 99th percentile "
                    + one(fig.real("p99GapMs")) + " ms";
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

    private static long count(String text, String part) {
        long n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    private static String shorter(String line) {
        String s = line.strip();
        return s.length() > 200 ? s.substring(0, 200) + " ..." : s;
    }

    /** The PHONE list's chosen line: "MobileAlpha  VIRTUAL   online", or "No phone seen yet". */
    private static String chosenPhone(Ui ui) {
        return String.valueOf(Ui.edt(() -> ui.all(javax.swing.JComboBox.class).get(0).getSelectedItem()));
    }
}
