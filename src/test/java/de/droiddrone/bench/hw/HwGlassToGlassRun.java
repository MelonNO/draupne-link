package de.droiddrone.bench.hw;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.PhoneWords;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.pc.gui.BenchAccess;
import de.droiddrone.pc.video.LatencyMeter;
import de.droiddrone.testing.Wire;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JPopupMenu;
import javax.swing.MenuSelectionManager;
import javax.swing.ToolTipManager;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The glass-to-glass table's run in the sealed app ({@link HwGlassToGlass}): on the real phone in
 * {@code HwGlassToGlassScenario}, on a made-up drone (and a made-up camera) in {@code HwGlassToGlassSelfTest}. The app
 * is driven as a pilot drives it - MENU &gt; VIDEO for the picture and RECORD VIDEO, MENU &gt; LINK for STANAG 4609,
 * TOOLS &gt; Latency test on and off, STILL, PILOT UAV, STANDBY MODE - with two things a pilot has no button for: the
 * codec (the hidden setting videoCodec, set in the app's settings as the pilot would type it into the file) and the
 * readings of the Latency test one by one ({@link MeterTap}: the screen shows their median only). The phone - or the
 * made-up drone - is reached through the bench's wire, which puts each cell's network on.
 * <p>
 * First of all the windows: the main window maximized on the main screen, the video in it, the app's other windows
 * closed, its tooltips off for the run (one under the pointer could lie over the code); the Latency test on for a
 * moment, and where its code is on the screen read off the painted view and said. The first cell then waits for the
 * camera's first reading: none, and the run says so plainly, a still of what the camera saw beside it, and skips the
 * rest - no failure of the app.
 */
final class HwGlassToGlassRun {
    /**
     * @param needScreen    the code must be on the main screen, whole, for the run to go on (the real camera); the self
     *                      test's made-up camera needs no screen, and its run goes on in quiet mode too
     * @param cameraCheckMs how long the first cell waits for the camera's first reading
     */
    record Options(boolean needScreen, long cameraCheckMs) {
        static Options real() {
            return new Options(true, HwGlassToGlass.CAMERA_CHECK_MS);
        }
    }

    /** MENU &gt; VIDEO's choices, in ControlLink.VideoMode's order. */
    private static final String[] PICTURES = Arrays.stream(ControlLink.VideoMode.values()).map(Enum::name).toArray(String[]::new);
    /** How often the meter's readings are looked at while a cell measures. */
    private static final long LOOK_MS = 200;

    private final AppRig rig;
    private final Ui ui;
    private final Wire wire;
    private final PhoneRig phone;
    private final Options options;
    private final Path matrix = Sandbox.runRoot().resolve("matrix");
    private Component view;
    private int cells, wrongCells, withoutReadings;
    private String skipped;
    /** What ends the run here: the phone's battery (PhoneRig.Stop), a command the phone's guard refused, the scenario's time. */
    private AssertionError halt;
    /** Each cell's row as it was written. */
    final List<HwLinkMatrix.Row> rows = new ArrayList<>();
    /** Where the code was on the screen, in words; null before the windows were placed. */
    String placement;

    /** @param phone the real phone behind the wire; null for a made-up drone */
    HwGlassToGlassRun(AppRig rig, Wire wire, PhoneRig phone, Options options) {
        this.rig = rig;
        this.ui = rig.ui;
        this.wire = wire;
        this.phone = phone;
        this.options = options;
    }

    /** The table, cell by cell: null when it ran, else why the rest was skipped (the camera does not see the code, the screen). */
    String run(List<HwGlassToGlass.Cell> plan) {
        ui.autoShot(false); // a picture per press would be the screen captured all through the measuring: the cells' own pictures only
        rig.film.remark(HwGlassToGlass.planWords(plan, Boolean.getBoolean("bench.quick")));
        boolean tips = Ui.edt(() -> ToolTipManager.sharedInstance().isEnabled());
        Ui.edt(() -> {
            ToolTipManager.sharedInstance().setEnabled(false);
            return null;
        });
        rig.film.remark("The app's tooltips are off for the run: one under the pointer could lie over the code.");
        try {
            String windows = placeTheWindows();
            if (windows != null && options.needScreen()) {
                skipped = "THE CAMERA CANNOT SEE THE CODE: " + windows + ". Nothing was measured, and this is no failure of the app - the table is skipped. "
                        + "To set up: " + HwGlassToGlass.SET_UP + ".";
                rig.film.remark(skipped);
                return skipped;
            }
            // the phone (or the made-up drone) behind the wire, announced as the virtual fleet announces its drones
            rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
            rig.fleet.startHeartbeat();
            try {
                ui.until("the phone, behind the bench's wire, online in the PHONE list", 90_000, () -> phones().stream().anyMatch(p -> p.contains("online")));
            } catch (AssertionError e) {
                rig.film.remark("The PHONE list has " + phones() + "; the fleet's announcement: " + announced() + "; the wire passed "
                        + wire.toPhone(de.droiddrone.common.PacketIds.NAME_QUERY) + " name question(s) on and " + wire.toPc(de.droiddrone.common.PacketIds.NAME_REPLY)
                        + " answer(s) back; the made-up fleet's last words: " + rig.fleetLog.subList(Math.max(0, rig.fleetLog.size() - 8), rig.fleetLog.size()));
                throw e;
            }
            choosePhone();
            rig.film.remark("The PHONE list: \"" + chosenPhone().trim() + "\" - " + (phone != null ? "the real phone" : "the made-up drone")
                    + " through the bench's wire (VIRTUAL: the app reaches it on this PC).");
            boolean first = true;
            for (HwGlassToGlass.Cell c : plan) {
                cell(c, first);
                first = false;
                if (skipped != null) break;
                goOn();
            }
            rig.film.remark("THE TABLE: " + cells + " cell(s), " + wrongCells + " with something wrong, " + withoutReadings + " without a reading of the code"
                    + (skipped != null ? "; THE REST SKIPPED" : "") + ". Its rows: " + matrix.resolve(HwGlassToGlass.TABLE + ".tsv") + "; each cell's readings, "
                    + "the app's own figures and the phone's log in " + matrix.resolve("logs") + ".");
            return skipped;
        } finally {
            Ui.edt(() -> {
                ToolTipManager.sharedInstance().setEnabled(tips);
                return null;
            });
        }
    }

    /** The next cell, unless something ended the run: the phone's battery, a refused command, the scenario's time. */
    private void goOn() {
        if (halt != null) throw halt;
        if (Thread.currentThread().isInterrupted()) throw new AssertionError("The scenario was interrupted (its time was up?) after " + cells + " cell(s)");
    }

    // ------------------------------------------------------------------ the windows

    static Rectangle mainScreen() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    /**
     * The main window maximized on the main screen with the video in it, the app's other windows closed (on the screen
     * only: in quiet mode nothing is moved onto it); the Latency test on for a moment and its code found on the painted
     * view. Null: the code is on the main screen, whole, nothing of the app's over it; else what is wrong.
     */
    private String placeTheWindows() {
        boolean desktop = ui.mode() == Ui.Mode.DESKTOP;
        JFrame main = rig.main();
        Rectangle screen = Ui.edt(HwGlassToGlassRun::mainScreen);
        if (desktop) {
            if (ui.has("Video back")) ui.click("Video back"); // a popped-out video back into the main window
            for (Window w : ui.windows()) if (w != main && w instanceof Frame) ui.closeWindow(w);
            Ui.edt(() -> {
                main.setExtendedState(Frame.NORMAL);
                main.setBounds(screen.x + 40, screen.y + 40, Math.max(800, screen.width - 80), Math.max(500, screen.height - 80));
                main.setExtendedState(Frame.MAXIMIZED_BOTH);
                main.toFront();
                return null;
            });
            ui.settle();
            Ui.pause(1000);
        }
        view = MeterTap.videoView(ui);
        if (view == null) {
            rig.check(false, "the app's video view is found among its windows");
            return "the app shows no video view";
        }
        latencyTest(true);
        Ui.pause(600);
        Rectangle inView = HwGlassToGlass.findCode(Ui.paint(view));
        Point at = Ui.edt(view::getLocationOnScreen);
        Rectangle code = inView == null ? null : new Rectangle(at.x + inView.x, at.y + inView.y, inView.width, inView.height);
        Map<String, Rectangle> others = new LinkedHashMap<>();
        Window home = Ui.edt(() -> javax.swing.SwingUtilities.getWindowAncestor(view));
        for (Window w : ui.windows()) { // the app's frames and dialogs: a menu or a pop-up is gone again by itself
            if (w == home || !(w instanceof Frame || w instanceof Dialog)) continue;
            String title = w instanceof Frame f ? f.getTitle() : ((Dialog) w).getTitle();
            others.put(title == null || title.isBlank() ? w.getClass().getSimpleName() : title, Ui.edt(() -> new Rectangle(w.getLocationOnScreen(), w.getSize())));
        }
        String problem = desktop ? HwGlassToGlass.placementProblem(screen, code, others)
                : "the bench is in quiet mode (BENCH_MODE=quiet, or a real drone is armed on this PC): its windows are parked off the screen, and a camera "
                + "would film nothing of the app";
        placement = code == null ? "the code could not be read off the painted view" : "the code a " + code.width + "-point square at " + code.x + "," + code.y
                + " (" + (desktop ? "the main screen " + HwGlassToGlass.where(screen) : "parked off the screen") + ")";
        rig.check(code != null, "the Latency test's code is read off the app's own video area as painted (a reader finds it, as the camera's would)");
        rig.film.remark("THE WINDOWS: " + (desktop ? "the main window maximized on the main screen, the video in it" : "left where quiet mode keeps them")
                + "; " + placement + "; the phone's own picture a thumbnail at the bottom right of the video. " + (problem == null
                ? "The phone's back camera must see the code whole." : "WRONG FOR A CAMERA: " + problem + "."));
        ui.shot("the windows as the camera is to see them: the Latency test's code on the left of the video");
        latencyTest(false);
        return problem;
    }

    // ------------------------------------------------------------------ a cell

    /** One cell: its row, its checks (each a line in the film), its notes and its log. */
    private final class Run {
        final HwGlassToGlass.Cell cell;
        final String name;
        final HwLinkMatrix.Row row;
        final List<String> wrong = new ArrayList<>(), notes = new ArrayList<>();
        final StringBuilder log = new StringBuilder();
        final long startMs = System.currentTimeMillis();
        String phoneFrom, pid = "", skippedFor;
        int logMark;
        boolean noReadings;
        StandInSpectator spectator;

        Run(HwGlassToGlass.Cell cell) {
            this.cell = cell;
            this.name = cell.name();
            row = HwGlassToGlass.row(cell);
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

    private void cell(HwGlassToGlass.Cell cell, boolean first) {
        Run r = new Run(cell);
        try {
            rig.film.remark("---- " + r.name + " ----");
            if (phone != null) {
                phone.checkBattery(); // too hot: the wait is here, with the link down; too low: the run ends (PhoneRig.Stop)
                r.phoneFrom = phone.now();
                r.pid = phone.pid();
            }
            picture(cell.picture());
            recording(cell.recording());
            stanag(cell.stanag());
            codec(cell.h264());
            wire.rule(cell.net());
            r.logMark = rig.log().size();
            long ready = connect(60_000);
            r.row.put("ready ms", HwLinkMatrix.ms(ready));
            if (!r.check(ready >= 0, "the link comes up: " + (ready < 0 ? "no LINK READY (nor FC LOST) within 60 s of PILOT UAV" : ready + " ms after PILOT UAV"))) return;
            List<String> streams = streamsSince(r.logMark, 30_000);
            if (!r.check(!streams.isEmpty(), "the picture comes: " + (streams.isEmpty() ? "no \"Video: H.26...\" in the app's log within 30 s" : streams.get(0)))) return;
            Ui.pause(cell.settleMs());
            if (cell.spectator()) {
                r.spectator = new StandInSpectator(InetAddress.getLoopbackAddress(), rig.config.getPort(), rig.config.getKey());
                boolean in = r.spectator.join(5000);
                r.check(in, "a stand-in spectator on this PC is let in by the app (\"Allow spectators\" on): " + r.spectator.words());
            }
            latencyTest(true);
            LatencyMeter meter = MeterTap.meter(view);
            if (first && !cameraSees(r, meter, !streams.isEmpty())) return;
            measure(r, meter);
        } catch (Throwable t) {
            stopped(r, t);
        } finally {
            finish(r);
        }
    }

    /** The first cell's look: a reading of the code within the time, or the run says the camera does not see it, with a still, and skips the rest. */
    private boolean cameraSees(Run r, LatencyMeter meter, boolean picture) {
        long t0 = System.currentTimeMillis();
        int seen = 0;
        while (System.currentTimeMillis() - t0 < options.cameraCheckMs() && !Thread.currentThread().isInterrupted()) {
            seen = MeterTap.readings(meter).size();
            if (seen > 0) break;
            Ui.pause(LOOK_MS);
        }
        long waited = System.currentTimeMillis() - t0;
        if (seen > 0) {
            rig.film.remark("THE CAMERA CHECK: the first reading of the code " + waited + " ms after the Latency test began - the camera sees it.");
            return true;
        }
        ui.shot("THE CAMERA DOES NOT SEE THE CODE - the screen as the camera was to see it");
        String skip = HwGlassToGlass.cameraCheck(0, waited, picture, still(r));
        skipped = skip;
        r.skippedFor = skip;
        rig.film.remark(skip);
        return false;
    }

    /** The cell's readings, look by look, for its time; then the Latency test ended, the app's own figures read beside them. */
    private void measure(Run r, LatencyMeter meter) throws IOException {
        HwGlassToGlass.Readings readings = new HwGlassToGlass.Readings();
        readings.base(MeterTap.readings(meter)); // what came before the measuring (the camera check's) is not the cell's
        List<Double> screens = new ArrayList<>();
        LocalTime from = LocalTime.now();
        long t0 = System.currentTimeMillis(), nextScreen = t0;
        while (System.currentTimeMillis() - t0 < r.cell.measureMs() && !Thread.currentThread().isInterrupted()) {
            Ui.pause(LOOK_MS);
            readings.take(MeterTap.readings(meter));
            if (System.currentTimeMillis() >= nextScreen) {
                double d = MeterTap.displayMs(view);
                if (!Double.isNaN(d)) screens.add(d);
                nextScreen += 1000;
            }
        }
        long measured = System.currentTimeMillis() - t0;
        LocalTime to = LocalTime.now();
        latencyTest(false); // the app's own test says its last 60 readings' median in the log
        if (r.spectator != null) {
            r.row.put("spectator packets", r.spectator.streamPackets());
            r.check(r.spectator.streamPackets() > 0, "the stand-in spectator gets the picture passed on: " + r.spectator.words());
            r.spectator.close();
            r.spectator = null;
        }
        Ui.pause(2000); // the link log's last rows of the stretch onto its file
        List<HwGlassToGlass.Estimate> est = HwGlassToGlass.between(HwGlassToGlass.readEstimates(rig.sandbox.logsDir(), r.startMs), from, to);
        double screen = HwGlassToGlass.Spread.of(screens).median();
        HwGlassToGlass.AppFigures app = HwGlassToGlass.appFigures(est, screen);
        HwGlassToGlass.Spread camera = HwGlassToGlass.Spread.of(readings.values);
        List<String> since = logSince(r.logMark);
        List<String> streams = HwGlassToGlass.streams(since);
        r.row.put("codec seen", HwGlassToGlass.codecs(streams)).put("size seen", streams.isEmpty() ? "-"
                : String.join(" then ", streams.stream().map(s -> s.substring(s.indexOf(' ') + 1)).toList()));
        HwGlassToGlass.put(r.row, camera, measured, app, screen);
        r.row.put("app's own test said", HwGlassToGlass.ownTestSaid(since));
        rig.film.remark(HwGlassToGlass.words(r.name, camera, measured, app, String.join(" then ", streams)));

        if (est.isEmpty()) r.note("the app's link log had no rows for the stretch (" + from + " on): its own figures are not known");
        else r.check(app.fpsMean() > 0, "the picture flows while measured: " + HwLinkMatrix.one(app.fpsMean()) + " frames a second in the mean of the app's link log");
        if (camera.n() == 0) {
            r.noReadings = true;
            r.note("NO READING of the code in " + Math.round(measured / 1000.0) + " s: the camera did not read it at this picture (" + r.row.get("size seen")
                    + ") - no figure, and no failure of the app");
        }
        if (!app.any() && !est.isEmpty()) r.note("the app had no figure of its own in its link log (g2g_ms blank: no FRAME_TIME from the phone app?)");
        if (readings.gaps > 0) {
            r.note("between " + readings.gaps + " pair(s) of the bench's looks the meter may have taken more than its last " + HwGlassToGlass.METER_KEEPS
                    + ": some readings are missing from the figures");
        }
        String asked = r.cell.h264() == null ? null : r.cell.h264() ? "H.264" : "H.265";
        String seen = HwGlassToGlass.codecs(streams);
        if (asked != null && !seen.equals(asked)) {
            r.note("the codec asked was " + asked + ", the stream came as " + seen + (seen.contains(asked) ? "" : ": the phone app (or this PC) does not offer " + asked));
        }
        r.log.append("The camera's readings, ms (").append(camera.n()).append("):\n");
        for (Double d : readings.values) r.log.append(String.format(Locale.ROOT, "%.1f%n", d));
        r.log.append("\nThe app's link log in the stretch: time, link, g2g_ms, g2g_max_ms, capture_to_send_ms, video_net_ms, video_res, video_rx_fps, ping_ms "
                + "(the view's screen time, ").append(HwLinkMatrix.one(screen)).append(" ms, is added to the figures in the row)\n");
        for (HwGlassToGlass.Estimate e : est) {
            r.log.append(e.at()).append('\t').append(e.link()).append('\t').append(e.g2gMs()).append('\t').append(e.g2gMaxMs()).append('\t').append(e.captureMs())
                    .append('\t').append(e.netMs()).append('\t').append(e.size()).append('\t').append(e.fps()).append('\t').append(e.pingMs()).append('\n');
        }
    }

    /** Whatever happened: the Latency test and the spectator ended, the link ended as a pilot ends it, the phone's log read, the row written. */
    private void finish(Run r) {
        if (r.spectator != null) r.spectator.close();
        try {
            if (view != null && testOn()) latencyTest(false);
        } catch (RuntimeException | AssertionError e) {
            r.check(false, "the Latency test is ended from the menu: " + e.getMessage());
        }
        endLink(r);
        if (phone != null && r.phoneFrom != null) {
            try {
                String log = phone.logSince(r.phoneFrom), pidNow = phone.pid();
                List<String> errors = log.lines().filter(l -> l.contains("DDLog") && (l.toLowerCase(Locale.ROOT).contains("error")
                        || l.toLowerCase(Locale.ROOT).contains("exception"))).toList();
                r.row.put("phone errors", errors.isEmpty() ? "0" : errors.size() + ": " + shorter(errors.get(0)));
                if (!errors.isEmpty()) r.note("the phone app's log has " + errors.size() + " line(s) of an error, the first: " + shorter(errors.get(0)));
                boolean crash = log.contains(PhoneWords.FATAL) || log.contains(PhoneWords.ANDROID_FATAL);
                r.check(!crash && !pidNow.isEmpty() && pidNow.equals(r.pid), "the phone app ran through the cell: " + (crash ? "A CRASH IN ITS LOG" : "no crash in its log")
                        + (pidNow.equals(r.pid) ? ", the same process" : " - ITS PROCESS WAS " + (r.pid.isEmpty() ? "none" : r.pid) + ", NOW " + (pidNow.isEmpty() ? "none" : pidNow)));
                r.log.append("\nThe phone app's log in the cell:\n").append(log);
            } catch (PhoneRig.Stop | PhoneRig.Refused e) {
                if (halt == null) halt = e;
            } catch (RuntimeException e) {
                r.note("the phone's log could not be read: " + e.getMessage());
            }
        }
        r.row.put("network", r.cell.net().name + " (" + r.cell.net().counts() + ")")
                .put("verdict", r.skippedFor != null ? "skipped" : !r.wrong.isEmpty() ? "WRONG" : r.noReadings ? "no reading" : "ok")
                .put("what", r.skippedFor != null ? r.skippedFor : String.join("; ", r.wrong));
        cells++;
        if (!r.wrong.isEmpty()) wrongCells++;
        else if (r.noReadings) withoutReadings++;
        else if (r.skippedFor == null) rig.film.remark(r.name + ": every check held.");
        try {
            HwLinkMatrix.append(matrix, HwGlassToGlass.TABLE, r.row);
            rows.add(r.row);
            Path logs = Files.createDirectories(matrix.resolve("logs"));
            String text = "Checks that went wrong: " + (r.wrong.isEmpty() ? "none" : String.join("; ", r.wrong)) + "\nSaid: " + String.join("; ", r.notes) + "\n\n" + r.log;
            Files.writeString(logs.resolve((HwGlassToGlass.TABLE + "-" + r.name).replaceAll("[^A-Za-z0-9._-]+", "_") + ".log"), text, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            rig.check(false, r.name + ": its row could not be written: " + e.getMessage());
        }
    }

    private void stopped(Run r, Throwable t) {
        if (t instanceof PhoneRig.Stop || t instanceof PhoneRig.Refused) {
            halt = (AssertionError) t;
            r.check(false, "the run stops here: " + t.getMessage());
            return;
        }
        if (t instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
            halt = new AssertionError("The scenario was interrupted (its time was up?) in " + r.name, t);
            r.check(false, "interrupted");
            return;
        }
        String why = t instanceof AssertionError ? String.valueOf(t.getMessage()) : t.toString();
        r.check(false, "the cell stopped: " + (why.length() > 400 ? why.substring(0, 400) + " ..." : why));
    }

    /**
     * The link ended as a pilot ends it, on the network as it is: STANDBY MODE - or STOP for a link that never came up.
     * A question first ("Keep flying"?) is answered Standby mode: nothing is armed in this run, the radio's hands are off.
     */
    private void endLink(Run r) {
        try {
            wire.rule(HwGlassToGlass.clean(0));
            if (ui.has("STANDBY MODE")) {
                ui.click("STANDBY MODE");
                long end = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < end && !ui.has("Keep flying") && !ui.has("PILOT UAV")) Ui.pause(100);
                if (ui.has("Keep flying")) {
                    if (!rig.radio.isHandsOff()) throw new AssertionError("STANDBY MODE asked whether to keep flying, and the radio's hands are not off: not answered");
                    rig.film.remark(r.name + ": STANDBY MODE asked first (" + String.join(" / ", ui.texts().stream().filter(t -> t.toLowerCase(Locale.ROOT)
                            .contains("armed")).limit(2).toList()) + "); nothing is armed in this run (the radio's hands are off): answered Standby mode.");
                    ui.click("Standby mode");
                }
            } else {
                Pilot.endLink(ui);
            }
            ui.waitForControl("PILOT UAV", 30_000);
        } catch (RuntimeException | AssertionError e) {
            r.check(false, "the link is ended as a pilot ends it (STANDBY MODE, or STOP): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ as a pilot does it

    /** PILOT UAV on the phone chosen; ms to the link up - LINK READY, or FC LOST for a phone with no flight controller - -1: not within maxMs. */
    private long connect(long maxMs) {
        ui.waitForControl("PILOT UAV", 30_000);
        long t0 = System.currentTimeMillis();
        ui.click("PILOT UAV");
        return timeTo(t0, maxMs, () -> ui.has("LINK READY") || ui.has("FC LOST"));
    }

    private static long timeTo(long fromMs, long maxMs, BooleanSupplier ok) {
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() - fromMs > maxMs || Thread.currentThread().isInterrupted()) return -1;
            Ui.pause(50);
        }
        return System.currentTimeMillis() - fromMs;
    }

    /**
     * The menu open and its rows showing (MENU first when its name does not show). Pressed again when it did not open:
     * a header button takes a press right after its menu closed for the one that closed it.
     */
    private void openMenu(String menu) {
        for (int tries = 0; tries < 3; tries++) {
            List<Component> top = ui.all(menu);
            if (top.isEmpty() || ui.whyNotPressable(top.get(0), null) != null) ui.click("MENU");
            ui.click(menu);
            Ui.pause(350);
            if (!ui.all(JPopupMenu.class).isEmpty()) return;
            Ui.pause(500);
        }
        throw new AssertionError("MENU > " + menu + " did not open in three presses");
    }

    /**
     * Every open menu closed, as Esc closes a menu - and only that: the bench's Esc would reach the window when the
     * menu has closed by itself meanwhile, and there Esc ends the Latency test.
     */
    private void closeMenus() {
        Ui.pause(150);
        Ui.edt(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            return null;
        });
        for (JPopupMenu m : ui.all(JPopupMenu.class)) {
            Ui.edt(() -> {
                m.setVisible(false);
                return null;
            });
        }
        ui.settle();
        Ui.pause(300); // a header button's next press is not taken for the one that closed its menu
    }

    private boolean testOn() {
        return MeterTap.meter(view) != null;
    }

    /** TOOLS &gt; Latency test, switched as asked. */
    private void latencyTest(boolean on) {
        if (testOn() == on) return;
        openMenu("TOOLS");
        ui.click("LATENCY TEST");
        closeMenus();
        ui.until("the Latency test " + (on ? "on" : "off"), 5000, () -> testOn() == on);
    }

    private ControlLink.VideoMode pictureNow() {
        return ControlLink.VideoMode.parse(rig.config.getString(ControlLink.VIDEO_MODE_KEY));
    }

    /** MENU &gt; VIDEO, the picture set to this choice (it takes from the next connection: the link is down here). */
    private void picture(ControlLink.VideoMode want) {
        if (pictureNow() == want) return;
        openMenu("VIDEO");
        Component row = null;
        for (Component c : ui.components()) {
            String t = Ui.edt(() -> Ui.textOf(c));
            if (c.getClass().getSimpleName().equals("ChoiceRow") && t != null && t.toUpperCase(Locale.ROOT).startsWith("VIDEO")) row = c;
        }
        if (row == null) throw new AssertionError("MENU > VIDEO has no row of picture choices");
        Component choiceRow = row;
        Point at = Ui.edt(() -> BenchAccess.choice(choiceRow, PICTURES, want.ordinal()));
        ui.clickAt(row, at.x, at.y);
        closeMenus();
        ui.until("MENU > VIDEO set to " + want, 5000, () -> pictureNow() == want);
    }

    private boolean recordingNow() {
        return !"false".equalsIgnoreCase(rig.config.getString("recordVideo"));
    }

    /** MENU &gt; VIDEO &gt; RECORD VIDEO as asked (every connection recorded while on: the app's default). */
    private void recording(boolean on) {
        if (recordingNow() == on) return;
        openMenu("VIDEO");
        ui.click("RECORD VIDEO");
        closeMenus();
        ui.until("RECORD VIDEO " + (on ? "on" : "off"), 5000, () -> recordingNow() == on);
    }

    private boolean stanagNow() {
        return "true".equalsIgnoreCase(rig.config.getString("stanag.enabled"));
    }

    /** MENU &gt; LINK &gt; STANAG 4609 STREAMS as asked (TOOLS &gt; Advanced shows the row: the run starts with it on). */
    private void stanag(boolean on) {
        if (stanagNow() == on) return;
        openMenu("LINK");
        ui.click("STANAG 4609 STREAMS");
        closeMenus();
        ui.until("STANAG 4609 STREAMS " + (on ? "on" : "off"), 5000, () -> stanagNow() == on);
    }

    /**
     * The codec the PC asks for: the setting videoCodec ("avc" H.264, else H.265), read at PILOT UAV - no menu has it,
     * a pilot types it into the settings file. Null: H.265, as the app has it unless told (ROBUST and SURVIVAL send H.264
     * whatever it says).
     */
    private void codec(Boolean h264) {
        String want = h264 != null && h264 ? "avc" : "hevc";
        String now = rig.config.getString("videoCodec");
        if (want.equals(now) || (want.equals("hevc") && (now == null || now.isEmpty()))) return;
        Ui.edt(() -> {
            rig.config.set("videoCodec", want);
            return null;
        });
        rig.film.remark("The codec asked from the next PILOT UAV: " + (want.equals("avc") ? "H.264" : "H.265") + " (the app's setting videoCodec - no menu has it).");
    }

    /** The app's log lines from that line on. */
    private List<String> logSince(int mark) {
        List<String> log = rig.log();
        return new ArrayList<>(log.subList(Math.min(mark, log.size()), log.size()));
    }

    /** The streams the app's log says began since that line ("H.265 1280x720"), waited for up to maxMs; none: empty. */
    private List<String> streamsSince(int mark, long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (true) {
            List<String> s = HwGlassToGlass.streams(logSince(mark));
            if (!s.isEmpty() || System.currentTimeMillis() > end || Thread.currentThread().isInterrupted()) return s;
            Ui.pause(500);
        }
    }

    /**
     * STILL pressed as a pilot does: the picture as the phone's camera sent it, saved by the app, copied beside the
     * table ({@code matrix\hw-g2g-camera-check.png}) and into the film. Its path, or null when none was saved.
     */
    private String still(Run r) {
        int mark = rig.log().size();
        try {
            ui.click("Still");
        } catch (RuntimeException | AssertionError e) {
            r.note("STILL could not be pressed: " + e.getMessage());
            return null;
        }
        long end = System.currentTimeMillis() + 15_000;
        String file = null;
        while (file == null && System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted()) {
            List<String> since = logSince(mark);
            file = HwGlassToGlass.stillSaved(since);
            if (file == null && since.stream().anyMatch(l -> l.contains("Still: no picture") || l.contains("Still failed"))) break;
            if (file == null) Ui.pause(500);
        }
        if (file == null) {
            r.note("the app saved no still: " + String.join(" / ", logSince(mark).stream().filter(l -> l.contains("Still")).toList()));
            return null;
        }
        try {
            Files.createDirectories(matrix);
            Path copy = matrix.resolve(HwGlassToGlass.TABLE + "-camera-check.png");
            Files.copy(Path.of(file), copy, StandardCopyOption.REPLACE_EXISTING);
            BufferedImage img = ImageIO.read(copy.toFile());
            if (img != null) rig.film.extra("what-the-camera-saw", img);
            return copy.toString();
        } catch (IOException | RuntimeException e) {
            r.note("the still " + file + " could not be copied: " + e.getMessage());
            return file;
        }
    }

    /** The PHONE list's chosen line: "MobileAlpha  VIRTUAL   online", or "No phone seen yet". */
    private String chosenPhone() {
        return String.valueOf(Ui.edt(() -> ui.all(JComboBox.class).get(0).getSelectedItem()));
    }

    /** Every line of the PHONE list. */
    private List<String> phones() {
        return Ui.edt(() -> {
            JComboBox<?> c = ui.all(JComboBox.class).get(0);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < c.getItemCount(); i++) out.add(String.valueOf(c.getItemAt(i)));
            return out;
        });
    }

    /** The phone behind the wire chosen in the PHONE list, as a pilot picks it - when it is not the chosen one already. */
    private void choosePhone() {
        if (chosenPhone().contains("online")) return;
        String online = phones().stream().filter(p -> p.contains("online")).findFirst().orElseThrow();
        JComboBox<?> list = Ui.edt(() -> ui.all(JComboBox.class).get(0));
        ui.choose(list, online);
        ui.until("the phone chosen in the PHONE list", 5000, () -> chosenPhone().contains("online"));
    }

    /** What the fleet announces to the app now (its file), for the film when the phone does not come. */
    private String announced() {
        try {
            return String.join(" | ", Files.readAllLines(de.droiddrone.pc.fleet.VirtualFleet.file(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return "(no file: " + e + ")";
        }
    }

    private static String shorter(String line) {
        String s = line.strip();
        return s.length() > 200 ? s.substring(0, 200) + " ..." : s;
    }
}
