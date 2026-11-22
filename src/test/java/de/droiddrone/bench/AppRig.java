package de.droiddrone.bench;

import de.droiddrone.pc.AppLog;
import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.Joystick;
import de.droiddrone.pc.JoystickService;
import de.droiddrone.pc.Outside;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.VirtualDrone;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.pc.gui.BenchAccess;
import de.droiddrone.pc.gui.MainWindow;
import de.droiddrone.pc.setup.BetaflightUsb;
import de.droiddrone.testing.Ports;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * The whole Windows app, started as its own main does it, in a sealed copy: a user of its own ({@link Sandbox}), the
 * fence up ({@link Fence}), a made-up radio, USB, internet and loudspeaker, and made-up drones to fly - then
 * {@code MainWindow.open}, the same call the real start makes. A scenario drives it through {@link #ui}; what goes
 * wrong unasked (errors, freezes, the fence's refusals) is collected and decides the verdict with the scenario's own
 * checks. One rig per JVM: the app keeps state in statics.
 */
public final class AppRig implements AutoCloseable {
    /** How the made-up pilot's PC is when the app starts. */
    public enum Start {
        /** No settings file: the very first start (the setup guide opens by itself). */
        FIRST_START,
        /** Setup done, the tour seen, the radio calibrated: a pilot's everyday start. */
        PILOT
    }

    public static final class Plan {
        private final String name;
        private Start start = Start.PILOT;
        private final Map<String, String> settings = new LinkedHashMap<>();
        private int drones;
        private int width, height;
        private Sandbox reopen;
        private boolean madeUpSaveNothing;
        private de.droiddrone.pc.fleet.FlightModel.Procedure failsafe;
        private int packMah;

        private Plan(String name) {
            this.name = name;
        }

        public Plan firstStart() {
            start = Start.FIRST_START;
            return this;
        }

        /** A setting written into the settings file before the start. */
        public Plan setting(String key, String value) {
            settings.put(key, value);
            return this;
        }

        /** What the made-up drones' Betaflight does when its sticks are lost in the air (Drop unless said). */
        /** The made-up drones' pack in mAh (1,500 unless said): a flight of hours needs one that lasts. */
        public Plan pack(int mah) {
            packMah = mah;
            return this;
        }

        public Plan failsafe(de.droiddrone.pc.fleet.FlightModel.Procedure procedure) {
            failsafe = procedure;
            return this;
        }

        /** This many made-up drones up and announced before the app starts. */
        public Plan drones(int count) {
            drones = count;
            return this;
        }

        public Plan size(int w, int h) {
            width = w;
            height = h;
            return this;
        }

        /** The same user as a rig before (the app closed or killed, now started again). */
        public Plan sameUserAs(Sandbox sandbox) {
            reopen = sandbox;
            return this;
        }

        /**
         * The made-up drones as the app treats them for a pilot: no flight, recording or last place of theirs is
         * saved (1.0.71). Left out, the bench asks the app to save theirs as a real drone's - it has no other drones
         * to test the saving with.
         */
        public Plan madeUpDronesSaveNothing() {
            madeUpSaveNothing = true;
            return this;
        }

        /** Files put into the made-up user's folders before the app starts (old settings, broken files, many flights). */
        public Plan before(Prepare prepare) {
            this.prepare = prepare;
            return this;
        }

        private Prepare prepare;
        private Hardware hardware;

        /**
         * A hardware run (gradlew benchHw, docs/TESTING.md): the app sealed off as ever - its own user, the fence up,
         * a made-up radio - but its link goes to this bench's one real phone, the only thing outside this PC the
         * fence then lets through ({@link Hardware#reach}), and the phone is in its PHONE list as a real phone is.
         * The radio has a throttle ceiling ({@link Hardware#THROTTLE_CEILING}) and starts with its hands off: nothing
         * arms until a scenario says {@link FakeRadio#handsOn()}. Before anything is started the rig makes sure no
         * other DRAUPNE Link runs on this PC and none has a link open ({@link HardwareRun}); it ends hands off.
         */
        public Plan hardware(Hardware bench) {
            if (bench == null) throw new IllegalArgumentException("No hardware: Hardware.load() reads this bench's");
            hardware = bench;
            return this;
        }

        private Fence.Reach reach = Fence.Reach.none();

        /**
         * A phone scenario's way through the fence (R5; gradlew benchHwPhone): the real phone's link port, to which
         * the bench's wire speaks - the app itself still speaks to the wire on loopback, and nothing else of a
         * hardware run is set up ({@link PhoneRig.Target#reach}). The emulator's reach is none, and then this plan is
         * what it always was. A plan that is a hardware run ({@link #hardware}) has that run's reach and takes no other.
         */
        public Plan reach(Fence.Reach phone) {
            if (phone == null) throw new IllegalArgumentException("No reach: Fence.Reach.none() is the one that lets nothing out");
            reach = phone;
            return this;
        }

        public AppRig start() throws Exception {
            return new AppRig(this);
        }

        /** Starts the app, runs the scenario, ends it: whatever went wrong - in the scenario or beside it - fails the test, the film written first. */
        public void run(Body body) throws Exception {
            AppRig rig = start();
            try {
                body.run(rig);
            } catch (Throwable t) {
                rig.stopped(t);
            }
            rig.finish();
        }
    }

    public interface Prepare {
        void files(Sandbox user) throws Exception;
    }

    public interface Body {
        void run(AppRig rig) throws Exception;
    }

    public static Plan scenario(String name) {
        return new Plan(name);
    }

    public final Sandbox sandbox;
    public final Film film;
    public final Watch watch;
    public final Census census = new Census();
    public final Ui ui;
    public final FakeRadio radio = new FakeRadio();
    public final FakeWeb web;
    public final BenchDoors doors;
    public final FakeUsb usb = new FakeUsb();
    public final PcConfig config;
    public final Fleet fleet;
    public final List<String> fleetLog = new CopyOnWriteArrayList<>();
    /** This bench's real phone and board in a hardware run ({@link Plan#hardware}); null in every other. */
    public final Hardware hardware;
    // >>> R3, the flight controller keeper
    /**
     * A hardware run's guard on a bench that has a keeper ({@link HwGuard}): it beats to the keeper, and stops the run's
     * flying when keeper and app disagree or either fails. Arm windows are opened and closed through it. Null in every
     * other run, and on a bench without a keeper - there the radio's hands are off for good from the start.
     */
    public HwGuard guard;
    private String keeperWord;
    // <<< R3
    private final String name;
    private final JoystickService service;
    private final Thread radioThread;
    private final List<String> checks = new ArrayList<>();
    private JFrame main;
    private java.awt.event.AWTEventListener smallScreen;
    private boolean closed;

    private AppRig(Plan plan) throws Exception {
        name = plan.name;
        hardware = plan.hardware;
        if (hardware != null) hardwareMayStart(plan); // before anything is made, sealed or started
        if (hardware != null && !plan.reach.isEmpty()) { // one way through the fence a run: never two put together
            throw new IllegalStateException("The hardware run \"" + plan.name + "\" was handed a phone scenario's reach as well: it has its own (Hardware.reach)");
        }
        System.setProperty("draupne.bench.madeUpSaves", Boolean.toString(!plan.madeUpSaveNothing));
        if (plan.reopen != null) {
            sandbox = Sandbox.reopen(plan.reopen.root(), plan.name);
        } else {
            sandbox = Sandbox.fresh(plan.name);
        }
        List<Path> writable = new ArrayList<>(List.of(Sandbox.runRoot()));
        if (System.getProperty("bench.build") != null) writable.add(Path.of(System.getProperty("bench.build")));
        String javacpp = System.getProperty("org.bytedeco.javacpp.cachedir");
        if (javacpp != null) writable.add(Path.of(javacpp));
        if (hardware == null) { // nothing outside this PC - but for a phone scenario's own reach (Plan.reach: none unless it says one)
            Fence.up(writable, Set.of("java.exe", "adb.exe", "emulator.exe", "wsl.exe"), plan.reach);
        } else { // the same fence with one way through it: the real phone's address at its link port
            Fence.up(writable, Set.of("java.exe", "adb.exe", "emulator.exe", "wsl.exe"), hardware.reach());
        }
        film = new Film(sandbox.film(), plan.name);

        // the stand-ins, before a line of the app runs
        web = new FakeWeb();
        doors = new BenchDoors(web);
        if (hardware != null) {
            // the app finds phones by asking Tailscale who is on its network: the bench's door answers with the real phone
            doors.tailscaleStatus(hardware.tailscaleStatus());
            // the made-up radio's hard limits, before the app can read it once: nothing arms until a scenario says handsOn()
            radio.armSwitch(hardware.armAux()).ceiling(Hardware.THROTTLE_CEILING).handsOff();
        }
        Outside.use(doors);
        Joystick.use(radio);
        BetaflightUsb.use(usb);
        if (System.getProperty("sun.java2d.d3d") == null) System.setProperty("sun.java2d.d3d", "false"); // as Main does

        Path configFile = AppPaths.configFile();
        if (plan.start == Start.PILOT && plan.reopen == null) {
            Map<String, String> s = new LinkedHashMap<>();
            s.put("pcName", "BENCH");
            // never the real link port: the pilot's own app may hold it - but a hardware run's phone listens on it, and
            // the app uses the one number for both ends (no other DRAUPNE Link runs then: HardwareRun)
            s.put("port", Integer.toString(hardware == null ? Ports.free() : hardware.phonePort()));
            s.put("setup.offered", "true");
            s.put("setup.done", "true");
            s.put("setup.pcDone", "true");
            s.put("setup.droneDone", "true");
            s.put("setup.phoneDone", "true");
            s.put("tour.offered", "true");
            s.put("tour.seen", BenchAccess.tourSeenAll());
            for (int axis = 0; axis < 8; axis++) {
                s.put("axis" + axis + ".min", "-1.0");
                s.put("axis" + axis + ".center", axis == FakeRadio.THROTTLE || axis >= FakeRadio.AUX1 ? "0.0" : "0.0");
                s.put("axis" + axis + ".max", "1.0");
            }
            if (hardware != null) {
                s.put("ip", hardware.phoneIp());
                if (!hardware.phoneKey().isEmpty()) s.put("key", hardware.phoneKey());
            }
            s.putAll(plan.settings);
            StringBuilder text = new StringBuilder();
            s.forEach((k, v) -> text.append(k).append('=').append(v).append("\r\n"));
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, text.toString(), StandardCharsets.UTF_8);
        }
        if (plan.prepare != null) plan.prepare.files(sandbox);
        AppLog.start(AppPaths.dataDir().resolve("logs"));
        watch = new Watch(); // after the app's log has put its own handler in: both get every uncaught error
        config = PcConfig.load(configFile);
        if (Files.exists(configFile)) {
            config.upgrade(configFile);
        } else {
            config.set("port", Integer.toString(Ports.free())); // a first start has the default port: the real app's
            plan.settings.forEach(config::set);
        }

        fleet = new Fleet(config, VirtualFleet.file(), fleetLog::add);
        if (plan.failsafe != null) fleet.setup().procedure = plan.failsafe;
        if (plan.packMah > 0) fleet.setup().capacityMah = plan.packMah;
        for (int i = 0; i < plan.drones; i++) fleet.addAtLaunch(fleet.freeName(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON);
        if (plan.drones > 0) fleet.startHeartbeat();

        ui = new Ui(film, census);
        int rcRate;
        try {
            rcRate = config.getRcRate();
        } catch (IllegalArgumentException e) {
            rcRate = ControlLink.DEFAULT_RC_RATE;
        }
        service = new JoystickService(config.getJoystickFilter(), rcRate);
        // a scenario at a window size is the app on a screen of that size: its dialogs have to fit it too
        String benchSize = System.getProperty("bench.size");
        int screenW = plan.width > 0 ? plan.width : benchSize != null ? Integer.parseInt(benchSize.split("x")[0]) : 0;
        int screenH = plan.width > 0 ? plan.height : benchSize != null ? Integer.parseInt(benchSize.split("x")[1]) : 0;
        BenchAccess.pretendScreen(screenW, screenH);
        if (screenW > 0) {
            // a window that opens maximized takes the real screen: on the small one it would be the small screen's size
            smallScreen = e -> {
                if (e.getID() == java.awt.event.WindowEvent.WINDOW_OPENED && e.getSource() instanceof java.awt.Frame f
                        && (f.getExtendedState() & java.awt.Frame.MAXIMIZED_BOTH) != 0) {
                    f.setExtendedState(java.awt.Frame.NORMAL);
                    f.setBounds(BenchAccess.screen());
                }
            };
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(smallScreen, java.awt.AWTEvent.WINDOW_EVENT_MASK);
        }
        SwingUtilities.invokeAndWait(() -> MainWindow.open(config, configFile, service));
        radioThread = new Thread(service::run, "benchRadio"); // the app's main thread: it polls the radio and sends the sticks
        radioThread.setDaemon(true);
        radioThread.start();
        ui.until("the main window", 20_000, () -> findMain() != null);
        main = Ui.edt(this::findMain);
        if (plan.width > 0) {
            Ui.edt(() -> {
                main.setExtendedState(JFrame.NORMAL);
                main.setSize(plan.width, plan.height);
                main.setLocation(Math.max(0, main.getX()), Math.max(0, main.getY()));
                return null;
            });
        } else if (screenW > 0) {
            Ui.edt(() -> {
                java.awt.Rectangle screen = BenchAccess.screen();
                main.setExtendedState(JFrame.NORMAL);
                main.setBounds(screen.x, screen.y, screenW, screenH);
                return null;
            });
        }
        // the start-up animation lies over the whole window and takes every press until it has run
        ui.until("the start-up animation to end", 30_000, () -> !main.getGlassPane().isVisible());
        ui.settle();
        watch.windowIsUp();
        film.remark("Mode: " + ui.mode().name().toLowerCase() + ". Real app: " + ArmedGuard.now() + ". User: " + sandbox.root());
        if (hardware != null) {
            film.remark("HARDWARE RUN: " + hardware + ". The fence lets this app reach " + Fence.reach() + " and nothing else outside this PC. The radio: "
                    + radio.limits() + ".");
            // >>> R3, the flight controller keeper: where a hardware rig starts, its guard starts
            if (hardware.hasKeeper()) {
                guard = new HwGuard(hardware.keeperPort(), radio, new HwGuard.Eyes() {
                    @Override
                    public Boolean appArmed() {
                        return HwGuard.linkWord(sandbox.logsDir(), java.time.LocalTime.now());
                    }

                    @Override
                    public String appError() {
                        for (Watch.Problem p : watch.problems()) if (p.kind().startsWith("uncaught error")) return p.toString();
                        return null;
                    }

                    @Override
                    public int clamped() {
                        return radio.clamped();
                    }
                }, Sandbox.runRoot().resolve(HwGuard.STOP_FILE), film::remark).named(name).start();
                film.remark("The hardware guard is up: it beats to the keeper on 127.0.0.1:" + hardware.keeperPort() + " twenty times a second.");
            } else {
                radio.handsOffForGood("this bench has no keeper (no keeper.port in the hardware file): nothing is armed in its runs");
                film.remark("No keeper on this bench (no keeper.port in the hardware file): the radio's hands are off for good.");
            }
            // <<< R3
        } else if (!Fence.reach().isEmpty()) {
            film.remark("A PHONE RUN: the fence lets the bench's wire reach " + Fence.reach() + " and nothing else outside this PC. The app itself speaks "
                    + "to the wire on this PC's loopback, as with the emulator.");
        }
    }

    /** A hardware run's preconditions ({@link HardwareRun}): everything in the way is said at once, and nothing is started. */
    private static void hardwareMayStart(Plan plan) {
        List<String> no = new ArrayList<>();
        HardwareRun.plan(no, plan.drones, plan.start == Start.FIRST_START, plan.prepare != null, plan.reopen != null, plan.settings.keySet());
        HardwareRun.thisPc(no, plan.hardware);
        // >>> R3: a hardware suite the guard has stopped stays stopped until somebody has looked
        String stopped = HwGuard.stopped(Sandbox.runRoot());
        if (stopped != null) {
            no.add("the hardware guard stopped this run's suite (" + stopped + "): look at the board and the keeper's log, then delete "
                    + Sandbox.runRoot().resolve(HwGuard.STOP_FILE));
        }
        // <<< R3
        if (!no.isEmpty()) throw new IllegalStateException("The hardware run \"" + plan.name + "\" does not start:\n - " + String.join("\n - ", no));
    }

    private JFrame findMain() {
        for (Window w : Window.getWindows()) if (w.isShowing() && BenchAccess.isMainWindow(w)) return (JFrame) w;
        return null;
    }

    /** The app's main window. */
    public JFrame main() {
        return main;
    }

    public VirtualDrone drone(int index) {
        return fleet.drones().get(index);
    }

    public VirtualDrone drone(String name) {
        for (VirtualDrone d : fleet.drones()) if (d.name().equalsIgnoreCase(name)) return d;
        throw new AssertionError("No made-up drone called " + name + ": " + fleet.drones().stream().map(VirtualDrone::name).toList());
    }

    // ------------------------------------------------------------------ the app's log and files

    /** Every line the app has logged so far (its own log file in the sandbox). */
    public List<String> log() {
        List<String> lines = new ArrayList<>();
        AppLog.drain(300);
        try (Stream<Path> files = Files.list(sandbox.logsDir())) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith("app-")).sorted().toList()) {
                lines.addAll(Files.readAllLines(f, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return lines;
    }

    public boolean logged(String part) {
        for (String line : log()) if (line.contains(part)) return true;
        return false;
    }

    public void waitForLog(String part, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!logged(part)) {
            if (System.currentTimeMillis() > end) {
                ui.shot("TIMED OUT after " + ms + " ms waiting for the log line \"" + part + "\"");
                List<String> log = log();
                throw new AssertionError("Timed out after " + ms + " ms waiting for the log line \"" + part + "\". The log's end:\n"
                        + String.join("\n", log.subList(Math.max(0, log.size() - 25), log.size())));
            }
            Ui.pause(100);
        }
    }

    /** The windows and everything on them as text, into the film's folder: for the report, and for whoever writes the next scenario. */
    public void dump(String name) {
        try {
            Files.writeString(film.dir().resolve("tree-" + name.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt"), ui.tree(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** The settings file as it is on disk now. */
    public Map<String, String> settingsOnDisk() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(sandbox.settingsFile())) return out;
        for (String line : Files.readAllLines(sandbox.settingsFile(), StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    // ------------------------------------------------------------------ checks and the verdict

    /** A check of the scenario's own that does not stop it: said in the film, counted in the verdict. */
    public void check(boolean ok, String what) {
        film.remark((ok ? "ok: " : "WRONG: ") + what);
        if (!ok) {
            checks.add(what);
            ui.shot("WRONG: " + what);
        }
    }

    /** The scenario could not go on: its failure stands first among the problems, with a picture of the moment. */
    public void stopped(Throwable t) {
        String text = t instanceof AssertionError ? String.valueOf(t.getMessage()) : t.toString();
        checks.add("the scenario stopped: " + (text.length() > 1500 ? text.substring(0, 1500) + " ..." : text));
        try {
            ui.shot("STOPPED: " + (text.length() > 200 ? text.substring(0, 200) + " ..." : text));
        } catch (RuntimeException ignored) {
        }
    }

    /** Everything that went wrong, asked or not: the scenario's failed checks, errors, freezes, the fence's refusals, errors in the app's log. */
    public List<String> problems() {
        List<String> all = new ArrayList<>(checks);
        for (Watch.Problem p : watch.problems()) all.add(p.toString());
        for (Fence.Refusal r : Fence.refusals()) all.add("the fence refused: " + r);
        for (String line : log()) {
            if (line.contains("UNCAUGHT") || line.contains("Exception in thread")) all.add("the app's log: " + (line.length() > 400 ? line.substring(0, 400) + " ..." : line));
        }
        // a bug already on the list (docs/BENCH-FINDINGS.md) does not fail the scenario again: it is said, with its number
        knownSeen.clear();
        all.removeIf(problem -> {
            for (Known k : known) {
                boolean is = true;
                for (String part : k.parts()) if (!problem.contains(part)) is = false;
                if (is) {
                    knownSeen.add(k.id() + ": " + (problem.length() > 160 ? problem.substring(0, 160).replace('\n', ' ') + " ..." : problem.replace('\n', ' ')));
                    return true;
                }
            }
            return false;
        });
        return all;
    }

    private record Known(String id, List<String> parts) {
    }

    private final List<Known> known = new ArrayList<>();
    private final List<String> knownSeen = new ArrayList<>();

    /**
     * A finding this scenario is known to meet (its number in docs/BENCH-FINDINGS.md): a problem whose text holds all
     * these parts is reported as that finding instead of failing the scenario. Bugs are listed, not fixed, until the
     * pilot says - and a listed one must not hide a new one.
     */
    public AppRig known(String id, String... parts) {
        known.add(new Known(id, List.of(parts)));
        return this;
    }

    /** Closes the app the way a pilot does - the window's X - and reports whether it went. */
    public boolean closeApp() {
        if (main == null || !Ui.edt(main::isDisplayable)) return true;
        SwingUtilities.invokeLater(() -> main.dispatchEvent(new WindowEvent(main, WindowEvent.WINDOW_CLOSING)));
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            if (!Ui.edt(main::isDisplayable)) return true;
            Ui.pause(100);
        }
        return false;
    }

    /** Ends the scenario: the app closed, the film written; fails if anything went wrong. */
    public void finish() {
        List<String> problems = end();
        if (!problems.isEmpty()) throw new AssertionError(name + ": " + problems.size() + " problem(s):\n - " + String.join("\n - ", problems));
    }

    private List<String> end() {
        if (closed) return List.of();
        closed = true;
        if (hardware != null) handsOffAtTheEnd(); // first of all, however the scenario ended
        try {
            ui.shot("the end");
        } catch (RuntimeException ignored) {
        }
        boolean wentAway = true;
        try {
            wentAway = closeApp();
        } catch (RuntimeException e) {
            wentAway = false;
        }
        List<String> problems = new ArrayList<>(problems());
        if (!wentAway) problems.add("the app did not close within 30 s of its window's X");
        if (hardware != null) hardwareVerdict(problems);
        for (String k : knownSeen) film.remark("KNOWN FINDING met again - " + k);
        film.remark("The window's slowest answer: " + watch.maxLagMs() + " ms. Threads " + watch.threadsAtStart() + " -> " + watch.threads()
                + ". Controls seen " + census.seenCount() + ", used " + census.usedCount() + ".");
        try {
            for (Window w : Window.getWindows()) {
                if (w.isDisplayable()) SwingUtilities.invokeLater(w::dispose);
            }
            service.stop();
            fleet.close();
            web.close();
        } catch (RuntimeException ignored) {
        }
        census.write(Sandbox.runRoot());
        watch.close();
        ui.close();
        film.close(problems.isEmpty(), problems);
        Outside.use(SHUT); // not null (the real world): threads of this app that are still fetching must reach nothing
        Joystick.use(null);
        BetaflightUsb.use(null);
        if (smallScreen != null) java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(smallScreen);
        BenchAccess.pretendScreen(0, 0);
        Fence.down();
        return problems;
    }

    /**
     * A hardware run's last move, before the app is closed (it is still sending the sticks): the radio's hands off for
     * good - ARM off, throttle down - and a second for the frames that say so to reach the flight controller.
     */
    private void handsOffAtTheEnd() {
        radio.handsOffForGood("the scenario has ended");
        Ui.pause(1000);
        // >>> R3, the flight controller keeper: the board made safe through the keeper - window closed, locked, STOP, the
        // motors read back - and its own word on it, before the app and its link are closed
        if (guard != null) {
            keeperWord = guard.finish();
            film.remark("The keeper's word at the end: " + keeperWord);
        }
        // <<< R3
    }

    /** What a hardware run adds to the verdict once the app is closed: the radio's limits, and the throttle the app really sent. */
    private void hardwareVerdict(List<String> problems) {
        String sent;
        try {
            int most = HardwareRun.mostThrottleSent(sandbox.logsDir());
            sent = most < 0 ? "no sticks were sent" : "the most throttle the app sent was " + most;
            if (most > radio.ceiling()) {
                problems.add("the app sent a throttle of " + most + ", above the radio's ceiling of " + radio.ceiling()
                        + ": the stick settings are no longer the rig's (a calibration, a channel map?)");
            }
        } catch (IOException e) {
            sent = "the throttle the app sent is not known";
            problems.add("the link log could not be read for the throttle sent: " + e.getMessage());
        }
        film.remark("The radio at the end: " + radio.limits() + ". By the link log " + sent + ".");
        // >>> R3, the flight controller keeper
        if (guard != null) {
            if (guard.tripped() != null) problems.add("the hardware guard stopped the run's flying: " + guard.tripped());
            if (keeperWord == null || !keeperWord.startsWith("OK")) {
                problems.add("the keeper did not say the board was left safe (locked, disarmed, every motor at rest): " + keeperWord);
            }
        }
        // <<< R3
    }

    /**
     * The doors after a scenario. What is left of its app - a thread still fetching the forest map's tiles - asks them,
     * not the real world: seen 2026-11-19, the next scenario of the same class was blamed for "the fence refused:
     * network cache.kartverket.no" of the one before.
     */
    private static final Outside.Doors SHUT = new Outside.Doors() {
        @Override
        public String url(String url) {
            return "http://127.0.0.1:9/shut"; // this PC, a port nothing listens on
        }

        @Override
        public de.droiddrone.pc.setup.Shell.Result run(List<String> command) {
            return new de.droiddrone.pc.setup.Shell.Result(1, "");
        }

        @Override
        public boolean start(List<String> command) {
            return true;
        }

        @Override
        public boolean browse(java.net.URI address) {
            return true;
        }

        @Override
        public boolean open(java.io.File folderOrFile) {
            return true;
        }
    };

    /** As {@link #finish} without failing: for a scenario that failed already and only needs the app gone and the film written. */
    @Override
    public void close() {
        end();
    }
}
