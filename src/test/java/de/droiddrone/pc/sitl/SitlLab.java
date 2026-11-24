package de.droiddrone.pc.sitl;

import de.droiddrone.bench.BenchControl;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.pc.setup.ardu.ArduPlan;
import de.droiddrone.pc.setup.ardu.ArduSetupWriter;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * The SITL lab ({@code sitl-fleet.ps1}, {@code gradlew sitlFleet}): a whole fleet on this PC for trying DRAUPNE Link by
 * hand - and flying it - against real ArduPilot Copter. Two kinds of drone, both SITL set up as Drone setup sets up a
 * DRAUPNE drone (its plan written, then started again as after a power cycle):
 * <ul>
 *   <li><b>Phone drones</b>: the real phone app (the bench build, never released) in an Android emulator, its flight
 *       controller the SITL drone over TCP (BenchFc: {@code debug.draupne.fc}, {@code adb reverse}) - Connect, the
 *       radio's sticks and switches, camera, OSD, the fleet role, launches, hand-over: everything as on a drone.</li>
 *   <li><b>Fleet-only drones</b>: the phone's fleet role on the PC (SitlFleetPhone) - followers, reserves, DISPATCH; no
 *       pilot session.</li>
 * </ul>
 * Every drone at a loopback address of its own, announced as the virtual fleet is: the running DRAUPNE Link finds them
 * by itself (VIRTUAL). Sizes come from {@link SitlLabSettings} (this PC's file), so a stronger PC runs more. Only for
 * this PC's tries; never part of the app.
 */
public final class SitlLab {
    static final int FIRST_INSTANCE = 60, FIRST_HOST = 100;
    /**
     * Emulator console ports are even numbers in adb's own range (it finds emulators by itself on 5554 - 5585 only), the
     * console's and adb's (one up) both free now: another emulator of this PC (the bench's 5584, Android Studio's
     * 5554 ...) keeps its own. Phone k's is consolePorts[k].
     */
    static final int CONSOLE_LOWEST = 5554, CONSOLE_HIGHEST = 5584;
    private final int[] consolePorts = new int[SitlLabSettings.MAX_PHONES];
    private final java.util.Set<Integer> consolesTaken = new java.util.HashSet<>();

    /** The next console port free (and its adb port), not given to this lab before. */
    private synchronized int freeConsolePort() throws IOException {
        for (int p = CONSOLE_LOWEST; p < CONSOLE_HIGHEST; p += 2) {
            if (consolesTaken.contains(p) || !tcpFree(p) || !tcpFree(p + 1)) continue;
            consolesTaken.add(p);
            return p;
        }
        throw new IOException("no emulator port free in adb's range " + CONSOLE_LOWEST + " - " + CONSOLE_HIGHEST + ": fewer phones, or close other emulators");
    }

    private static boolean tcpFree(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private final SitlLabSettings settings;
    private final Path dir;
    private final List<ArduSitl> sitls = Collections.synchronizedList(new ArrayList<>());
    private final List<SitlFleetPhone> standIns = Collections.synchronizedList(new ArrayList<>());
    private final List<PhoneRelay> relays = Collections.synchronizedList(new ArrayList<>());
    private final List<PhoneRig> rigs = Collections.synchronizedList(new ArrayList<>());
    private final List<Process> emulators = Collections.synchronizedList(new ArrayList<>());
    private final Object portsLock = new Object();
    private Fleet fleet;

    private final Path fleetFile;
    private final List<ArduSitl> drones = new ArrayList<>();

    /** @param fleetFile where the drones are announced (VirtualFleet.file(): the running DRAUPNE Link's) */
    SitlLab(SitlLabSettings settings, Path dir, Path fleetFile) {
        this.settings = settings;
        this.dir = dir;
        this.fleetFile = fleetFile;
    }

    /** The SITL drones, the phone drones first. */
    List<ArduSitl> drones() {
        return drones;
    }

    /** Where DRAUPNE Link reaches each phone drone (its relay), in order. */
    List<InetSocketAddress> phoneAddresses() {
        List<InetSocketAddress> out = new ArrayList<>();
        for (PhoneRelay r : relays) out.add(r.address());
        out.sort(Comparator.comparingInt(a -> a.getAddress().getAddress()[3] & 0xff)); // phone 1, 2 ... (they come up in any order)
        return out;
    }

    /** args: the settings file, then name=value for this run (the script's switches). */
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "sitl-lab.settings");
        Map<String, String> overrides = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq > 0) overrides.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        SitlLabSettings s = SitlLabSettings.read(file, overrides);
        Path dir = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")), "DraupneBench", "sitl-lab");
        Files.createDirectories(dir);
        SitlLab lab = new SitlLab(s, dir, VirtualFleet.file());
        Runtime.getRuntime().addShutdownHook(new Thread(lab::close, "sitlLabClose"));
        try {
            lab.run();
        } catch (Exception e) {
            System.out.println("The lab did not start: " + e.getMessage());
            System.exit(1);
        }
        while (true) {
            Thread.sleep(VirtualFleet.STOP_POLL_MS);
            if (VirtualFleet.stopRequested(VirtualFleet.stopFile())) {
                System.out.println("DRAUPNE Link asked the fleet to close.");
                System.exit(0);
            }
        }
    }

    private static void say(String line) {
        System.out.println(line);
    }

    void run() throws Exception {
        if (System.getProperty("bench.root") == null) System.setProperty("bench.root", dir.toString()); // PhoneRig's folder
        if (!ArduSitl.installed()) throw new IOException("ArduPilot SITL isn't installed (" + ArduSitl.ENV + " or %LOCALAPPDATA%\\DroidDroneBuild\\sitl\\copter)");
        if (fleetFile.equals(VirtualFleet.file()) && !VirtualFleet.announcedNow().isEmpty()) throw new IOException("the virtual fleet runs already (Virtual fleet.bat, or another lab): close it first - they share one file");
        Path configFile = AppPaths.configFile();
        PcConfig config = PcConfig.load(Files.exists(configFile) ? configFile : null); // read only: never saved
        long freeMb = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize() >> 20;
        int cores = Runtime.getRuntime().availableProcessors();
        int phones = settings.phoneCount(freeMb, cores);
        Path apk = null;
        if (phones > 0) {
            if (!PhoneRig.setUp()) {
                say("No Android emulator set up on this PC (docs/TESTING.md, 'The emulator'): fleet-only drones alone.");
                phones = 0;
            } else {
                apk = apk();
                if (!de.droiddrone.common.SettingsCommon.key.equals(config.getKey())) {
                    say("NOTE: DRAUPNE Link's link key isn't the phone app's first one (" + de.droiddrone.common.SettingsCommon.key
                            + "): the emulator phones won't answer until it is the same.");
                }
            }
        }
        int total = phones + settings.fleetOnly();
        if (total == 0) throw new IOException("nothing to start: phones and fleetOnly are both 0");
        if (total > SitlLabSettings.MAX_DRONES) throw new IOException("at most " + SitlLabSettings.MAX_DRONES + " drones");
        double lat = settings.placeGiven() ? settings.lat() : Fleet.DEFAULT_LAT, lon = settings.placeGiven() ? settings.lon() : Fleet.DEFAULT_LON;
        double alt = Double.isNaN(settings.alt()) ? SitlFleetMain.groundAt(lat, lon) : settings.alt();
        say(String.format(Locale.ROOT, "SITL lab: %d phone drone%s (emulator %d MB, %d cores%s) + %d fleet-only, at %.6f, %.6f, ground %.0f m."
                        + " (free memory %d MB, %d cores)", phones, phones == 1 ? "" : "s", settings.emulatorMemoryMb(), settings.emulatorCores(),
                settings.emulatorWindows() ? ", windows" : "", settings.fleetOnly(), lat, lon, alt, freeMb, cores));
        fleet = new Fleet(config, fleetFile, line -> { });
        VirtualFleet.clearStop(VirtualFleet.stopFile());
        fleet.startHeartbeat();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(total, 8));
        List<Future<ArduSitl>> up = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            int n = i;
            double[] at = GeoLocal.offset(lat, lon, (i % 5) * settings.spacingM(), (i / 5) * -settings.spacingM());
            up.add(pool.submit(() -> sitl(FIRST_INSTANCE + n, at[0], at[1], alt)));
        }
        if (phones > 0) {
            labDevice(apk); // once per phone app build: the device every lab emulator starts from
            for (int k = 0; k < phones; k++) startPhoneEmulator(k); // a minute or two to boot
        }
        for (Future<ArduSitl> f : up) drones.add(f.get());
        say("SITL drones up and set up as DRAUPNE drones (" + total + ").");

        // fleet-only drones: the fleet role here
        for (int j = 0; j < settings.fleetOnly(); j++) {
            ArduSitl s = drones.get(phones + j);
            String name = SitlFleetMain.freeName(fleet, names);
            InetAddress bind = InetAddress.getByName("127.0.0." + (FIRST_HOST + phones + j));
            SitlFleetPhone p = new SitlFleetPhone(s, fleet.key(), bind);
            p.onOther(d -> SitlFleetMain.answerName(fleet.key(), name, p, d));
            standIns.add(p);
            names.add(name);
            fleet.announceExtra(new VirtualFleet.Drone(bind.getHostAddress(), p.address().getPort()));
            say(String.format(Locale.ROOT, "  %-14s fleet only    SITL %d   Mission Planner tcp 127.0.0.1:%d", name, s.instance, 5762 + 10 * s.instance));
        }

        // phone drones: the phone app in each emulator, its SITL drone its flight controller
        List<Future<String>> ready = new ArrayList<>();
        for (int k = 0; k < phones; k++) {
            int n = k;
            Path a = apk;
            ArduSitl s = drones.get(k);
            ready.add(pool.submit(() -> phone(n, s, a)));
        }
        for (Future<String> f : ready) say(f.get());
        pool.shutdown();
        say("Ready. Start DRAUPNE Link as usual: the drones show as VIRTUAL. The phone drones get their names from it"
                + " (PHONE list); Connect to one and fly it with the radio. Ctrl+C here ends everything.");
    }

    private final java.util.Set<String> names = Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** The newest bench build (or the settings' apk). */
    private Path apk() throws IOException {
        if (!settings.apk().isBlank()) {
            Path p = Path.of(settings.apk());
            if (!Files.isRegularFile(p)) throw new IOException("apk = " + p + ": no such file");
            return p;
        }
        Path folder = Path.of(System.getProperty("bench.build", "build"), "flight-bench"); // the tests run in a folder of their own
        if (!Files.isDirectory(folder)) throw new IOException("no bench build of the phone app: flight-app\\build.ps1 -Bench (the script does it when missing)");
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.toString().endsWith("-bench.apk")).max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IOException("no bench APK in " + folder + ": flight-app\\build.ps1 -Bench"));
        }
    }

    private static Path sdk() {
        return Path.of(System.getenv("LOCALAPPDATA"), "Android", "Sdk");
    }

    private static Path avdHome() {
        return Path.of(System.getenv("LOCALAPPDATA"), "DroidDroneBuild", "avd");
    }

    /** The lab's own Android device (the bench's is the bench's: it may be running, and then can't be shared). */
    static final String LAB_AVD = "draupne-lab";

    /**
     * The device every lab emulator starts from (read-only, several at once): made once from the bench device's
     * settings, and prepared - the phone app installed, its permissions granted - again whenever the app build changes.
     */
    private void labDevice(Path apk) throws Exception {
        Path home = avdHome(), avd = home.resolve(LAB_AVD + ".avd");
        if (!Files.exists(home.resolve(LAB_AVD + ".ini"))) {
            Files.createDirectories(avd);
            List<String> cfg = new ArrayList<>();
            for (String line : Files.readAllLines(home.resolve(PhoneRig.AVD + ".avd").resolve("config.ini"))) {
                String key = line.contains("=") ? line.substring(0, line.indexOf('=')).trim() : "";
                if (key.equals("disk.dataPartition.path") || key.equals("AvdId") || key.equals("avd.ini.displayname")) continue;
                cfg.add(line);
            }
            cfg.add("AvdId = " + LAB_AVD);
            cfg.add("avd.ini.displayname = " + LAB_AVD);
            Files.write(avd.resolve("config.ini"), cfg);
            Files.write(home.resolve(LAB_AVD + ".ini"), List.of("avd.ini.encoding=UTF-8", "path=" + avd, "target=android-34"));
            say("The lab's Android device made: " + avd);
        }
        String want = apk.getFileName() + " " + Files.size(apk) + " " + Files.getLastModifiedTime(apk).toMillis();
        Path marker = avd.resolve("draupne-lab.prepared");
        if (Files.exists(marker) && Files.readString(marker).trim().equals(want)) return;
        say("Preparing the lab's Android device for " + apk.getFileName() + " (once per phone app build, a few minutes) ...");
        int port = freeConsolePort();
        Process p = startEmulator(port, false);
        PhoneRig rig = rig("emulator-" + port, apk);
        untilBooted(rig, port);
        String said = rig.adb("install", "-r", "-t", apk.toString());
        if (!said.contains("Success")) throw new IOException("the phone app did not install on the lab's device: " + said);
        rig.grantPermissions();
        rig.adb("emu", "kill");
        if (!p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
        emulators.remove(p);
        Thread.sleep(3000); // its files let go
        Files.writeString(marker, want);
        say("The lab's Android device is ready.");
    }

    private PhoneRig rig(String serial, Path apk) {
        return new PhoneRig(null, new PhoneRig.Target(serial, PhoneRig.Kind.EMULATOR, BenchControl.PACKAGE, null,
                EnumSet.allOf(PhoneRig.Capability.class), true, sdk(), avdHome(), apk));
    }

    private void untilBooted(PhoneRig rig, int port) throws Exception {
        long end = System.currentTimeMillis() + 300_000;
        while (!rig.running()) {
            if (System.currentTimeMillis() > end) throw new IOException("emulator-" + port + " did not start within 5 minutes: see " + dir.resolve("emulator-" + port + ".log"));
            Thread.sleep(3000);
        }
        Thread.sleep(4000); // Android's own first seconds
    }

    /** Phone k's emulator (a free console port, consolePorts[k]), read-only: from the lab's device, nothing kept. */
    private void startPhoneEmulator(int k) throws IOException, InterruptedException {
        int port = consolePorts[k] = freeConsolePort();
        startEmulator(port, true, settings.emulatorWindows());
        // the next one only once this one runs: two copies of the device starting in the same moment both write its
        // shared files, and one of them stopped without a word before its hypervisor started (2026-11-24)
        Path log = dir.resolve("emulator-" + port + ".log");
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            if (Files.exists(log) && Files.readString(log, java.nio.charset.StandardCharsets.ISO_8859_1).contains("accelerator is operational")) break;
            Thread.sleep(500);
        }
        Thread.sleep(2000);
    }

    private Process startEmulator(int port, boolean readOnly) throws IOException {
        return startEmulator(port, readOnly, false);
    }

    private Process startEmulator(int port, boolean readOnly, boolean window) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(sdk().resolve("emulator").resolve("emulator.exe").toString(),
                "-avd", LAB_AVD, "-port", String.valueOf(port), "-memory", String.valueOf(settings.emulatorMemoryMb()),
                "-cores", String.valueOf(settings.emulatorCores()), "-no-audio", "-no-boot-anim", "-no-snapshot", "-no-metrics",
                "-gpu", "swiftshader_indirect", "-camera-back", "emulated", "-camera-front", "emulated"));
        if (readOnly) {
            cmd.add("-read-only");
            // a cache image of its own: read-only still writes the device's one (cache.img.qcow2), and a second copy of
            // the device running was refused it ("Permission denied") - QEMU ended at once
            Path cache = dir.resolve("cache-" + port + ".img");
            Files.deleteIfExists(cache);
            Files.deleteIfExists(Path.of(cache + ".qcow2")); // its overlay from an earlier run
            cmd.addAll(List.of("-cache", cache.toString()));
        }
        if (!window) cmd.add("-no-window");
        ProcessBuilder b = new ProcessBuilder(cmd);
        b.environment().put("ANDROID_SDK_ROOT", sdk().toString());
        b.environment().put("ANDROID_HOME", sdk().toString());
        b.environment().put("ANDROID_AVD_HOME", avdHome().toString());
        b.environment().put("ADB_MDNS", "0");
        b.redirectErrorStream(true).redirectOutput(dir.resolve("emulator-" + port + ".log").toFile());
        Process p = b.start();
        emulators.add(p);
        return p;
    }

    /**
     * What a real board has from its maker and SITL has not: the battery monitor on (voltage and current; ArduPilot's
     * defaults for most boards). Without it ArduPilot says the battery is not known, and the phone's launch check fails
     * closed (LOW BATTERY) - every DISPATCH and CALL RESERVE refused.
     */
    static final List<String> BOARD_DEFAULTS = List.of("BATT_MONITOR 4");

    /** One SITL drone: started, Drone setup's plan written, started again (as after a power cycle), calibrated, pre-arm checks passed. */
    private ArduSitl sitl(int instance, double lat, double lon, double alt) throws Exception {
        Files.deleteIfExists(ArduSitl.home().resolve("run" + instance).resolve("eeprom.bin"));
        ArduSitl s = ArduSitl.start(instance, lat, lon, alt, 1, BOARD_DEFAULTS);
        try {
            s.awaitReady(120_000);
            // 3 cells: SITL's battery is a 3S one (12.6 V) - a 4S plan would be in its low-voltage failsafe at once
            ArduPlan.Choices c = new ArduPlan.Choices(instance + 1, 3, 3300, 1, 2, ArduPlan.DEFAULT_MODES.clone(), 30, 10, 2.5, 1.5, true);
            Path backups = Files.createDirectories(dir.resolve("backups"));
            ArduSetupWriter.Result r = new ArduSetupWriter(s.fc, null).write(ArduPlan.plan(c, s.fc.readAllParams(60_000)), backups, java.time.LocalDateTime.now());
            if (!r.ok()) throw new IOException("SITL " + instance + ": Drone setup's plan not written: " + r.failure());
        } finally {
            s.close();
        }
        s = ArduSitl.start(instance, lat, lon, alt, 1, BOARD_DEFAULTS); // the eeprom kept: the plan in force, as after a power cycle
        sitls.add(s);
        s.awaitReady(120_000);
        if (new de.droiddrone.pc.setup.ardu.ArduTools(s.fc).simpleAccelCal() != 0) throw new IOException("SITL " + instance + ": accelerometer calibration refused");
        s.awaitPreArm(120_000);
        return s;
    }

    /** Phone k: its emulator booted, the bench app installed and started with SITL drone s for its flight controller, announced. */
    private String phone(int k, ArduSitl s, Path apk) throws Exception {
        int port = consolePorts[k];
        String serial = "emulator-" + port;
        PhoneRig rig = rig(serial, apk);
        rigs.add(rig);
        untilBooted(rig, port);
        int linkHost;
        synchronized (portsLock) { // first: it asks for root, which starts adb on the phone again (an adb reverse would be lost)
            linkHost = rig.forwardUdp(PhoneRig.linkOnThePhone());
        }
        if (!rig.shell("pm path " + BenchControl.PACKAGE).contains("package:")) { // the lab's device has it: else installed here
            String said = rig.adb("install", "-r", "-t", apk.toString());
            if (!said.contains("Success")) throw new IOException(serial + ": the phone app did not install: " + said);
        }
        rig.grantPermissions();
        s.handOver(); // the lab silent on ArduPilot's first port: the phone (second port) is the drone's only ground station, as in flight
        // BenchFc's TCP port on the phone's own loopback: the SITL drone's port number, carried to it by adb reverse
        rig.shell("setprop " + "debug.draupne.fc" + " tcp:" + s.secondPort());
        rig.adb("reverse", "tcp:" + s.secondPort(), "tcp:" + s.secondPort());
        rig.gps(s.homeLat, s.homeLon); // the phone lies on the drone
        rig.launch();
        rig.untilAppIsUp(90_000);
        rig.startIfAsked();
        InetAddress bind = InetAddress.getByName("127.0.0." + (FIRST_HOST + k));
        PhoneRelay relay = new PhoneRelay(bind, new InetSocketAddress(InetAddress.getLoopbackAddress(), linkHost));
        relays.add(relay);
        fleet.announceExtra(new VirtualFleet.Drone(bind.getHostAddress(), relay.address().getPort()));
        return String.format(Locale.ROOT, "  phone %-8s %s   SITL %d   at %s:%d   Mission Planner tcp 127.0.0.1:%d", (k + 1) + "", serial, s.instance,
                bind.getHostAddress(), relay.address().getPort(), 5763 + 10 * s.instance); // the second port is the phone's
    }

    /** Everything ended: the announcement first (DRAUPNE Link stops sending), then phones, emulators, SITL. */
    /** Phone k's app log (DRAUPNE Flight's own lines), the last lines; "" when there is none. */
    String phoneLog(int k, int lines) {
        String serial = "emulator-" + consolePorts[k];
        for (PhoneRig r : rigs) {
            if (!r.target().serial().equals(serial)) continue;
            try {
                List<String> all = r.shell("logcat -d -v time -s " + BenchControl.LOG_TAG + ":I").lines().toList();
                return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
            } catch (RuntimeException e) {
                return "(no log: " + e.getMessage() + ")";
            }
        }
        return "";
    }

    void close() {
        if (fleet != null) fleet.close();
        for (PhoneRelay r : relays) r.close();
        for (SitlFleetPhone p : standIns) p.close();
        for (PhoneRig r : rigs) {
            try { // each phone app's log kept for afterwards: %LOCALAPPDATA%\DraupneBench\sitl-lab\<serial>-app.log
                Files.writeString(dir.resolve(r.target().serial() + "-app.log"), r.shell("logcat -d -v time -s " + BenchControl.LOG_TAG + ":I"));
            } catch (IOException | RuntimeException ignored) {
                // the emulator is gone already
            }
            try {
                r.adb("emu", "kill");
            } catch (RuntimeException ignored) {
                // gone already
            }
        }
        for (Process p : emulators) {
            // its qemu is a child of its own: known now, while emulator.exe still lives (once it is gone, its children are not)
            List<ProcessHandle> kids = p.descendants().toList();
            try {
                p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // below
            }
            for (ProcessHandle h : kids) if (h.isAlive()) h.destroyForcibly(); // left over: a qemu outlived its emulator.exe once
            if (p.isAlive()) p.destroyForcibly();
        }
        for (ArduSitl s : sitls) s.close();
    }
}
