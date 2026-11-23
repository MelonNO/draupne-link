package de.droiddrone.bench;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * The facts of one bench's real hardware - the phone on the drone, the flight controller, its battery - for the runs
 * that reach them (gradlew benchHw / benchHwPhone). They are this PC's own and are read from a file outside the repo,
 * {@code %LOCALAPPDATA%\DraupneBench\hardware.properties}; a copy with made-up values to start from is
 * docs/hardware.example.properties. No such file: no hardware run - a scenario skips itself ({@link #present()}),
 * and {@link #load()} says what is missing and where it belongs.
 * <p>
 * Four things of the phone a run may also say itself, and then its word goes before the file's ({@link #RUNS_OWN}):
 * the run's own word first ({@code -Dbench.phone.adb=...}; gradlew benchHwPhone -Pphone.adb=...), then the
 * environment's (BENCH_PHONE_ADB), then the file's. What was taken from outside the file is said wherever this is
 * printed.
 */
public final class Hardware {
    public static final String FILE = "hardware.properties";
    /** The copy to start from, in the repo: made-up values only. */
    public static final String EXAMPLE = "docs/hardware.example.properties";
    /** The most throttle a hardware run's radio ever gives (a channel value): the motors turn, no more. */
    public static final int THROTTLE_CEILING = 1100;

    private static final Set<String> KEYS = Set.of("phone.ip", "phone.port", "phone.key", "phone.adb", "phone.path", "phone.package",
            // the phone's Wi-Fi as it normally is (the network-switch scenario puts the phone back to it)
            "phone.wifi",
            "board.port", "board.armAux", "battery.cells", "battery.cellFloor", "keeper.port",
            // >>> R3, the flight controller keeper
            "board.mayArm", "keeper.motorSeconds",
            // (second round: the board's own failsafe, and its output limit)
            "board.letFailsafeRun", "board.failsafeSeenOnSitl", "board.outputLimitMax");
    // <<< R3
    /**
     * What a run may say itself, or the environment for it, before the file (R5, the phone's rig): which phone on adb,
     * where it is reached, and which of DRAUPNE Flight's two packages is flown. As a system property "bench." + the
     * key; in the environment "BENCH_" + the key in capitals, with _ for the dot.
     */
    static final Set<String> RUNS_OWN = Set.of("phone.adb", "phone.ip", "phone.port", "phone.package");

    private final Path file;
    /** The keys whose values did not come from the file. */
    private final Set<String> outside;
    private final String phoneIp, phoneKey, phoneAdb, phonePackage, boardPort;
    private final int phonePort, armAux, cells, keeperPort, motorSeconds, outputLimitMax;
    private final boolean relayed, phoneWifi, mayArm, letFailsafeRun, failsafeSeenOnSitl;
    private final double cellFloor;

    private Hardware(Path file, Properties p, Set<String> outside) {
        this.file = file;
        this.outside = outside;
        Set<String> unknown = new TreeSet<>(p.stringPropertyNames());
        unknown.removeAll(KEYS);
        if (!unknown.isEmpty()) throw wrong("has " + unknown + ", which this bench does not know (a slip of the hand?). It knows " + new TreeSet<>(KEYS));

        String ip = text(p, "phone.ip");
        if (ip.isEmpty()) throw wrong("has no phone.ip: the phone's address on the way the run is to take (Wi-Fi, Tailscale, mobile data)");
        phonePort = number(p, "phone.port", de.droiddrone.common.UdpCommon.defaultPort, 1, 65535);
        String pair;
        try {
            pair = Fence.Reach.checked(ip + ":" + phonePort);
        } catch (IllegalArgumentException e) {
            throw wrong("phone.ip / phone.port: " + e.getMessage());
        }
        phoneIp = pair.substring(0, pair.indexOf(':'));
        phoneKey = text(p, "phone.key");
        if (phoneKey.length() > 16) throw wrong("phone.key is longer than the 16 characters a link key has");
        phoneAdb = text(p, "phone.adb");
        if (phoneAdb.chars().anyMatch(Character::isWhitespace)) throw wrong("phone.adb is no adb serial: \"" + phoneAdb + "\"");
        String app = text(p, "phone.package");
        if (!app.isEmpty() && !app.equals(BenchControl.PACKAGE) && !app.equals(PhoneRig.APP)) {
            throw wrong("phone.package is one of DRAUPNE Flight's two - the bench build (" + BenchControl.PACKAGE + ") or the released app (" + PhoneRig.APP
                    + ") - not \"" + app + "\"");
        }
        phonePackage = app.isEmpty() ? BenchControl.PACKAGE : app;
        String path = text(p, "phone.path");
        if (!path.isEmpty() && !path.equals("direct") && !path.equals("relay")) throw wrong("phone.path is direct or relay, not \"" + path + "\"");
        relayed = path.equals("relay");
        String wifi = text(p, "phone.wifi");
        if (!wifi.isEmpty() && !wifi.equals("on") && !wifi.equals("off")) throw wrong("phone.wifi is on or off, not \"" + wifi + "\"");
        phoneWifi = !wifi.equals("off");
        boardPort = text(p, "board.port");
        if (boardPort.chars().anyMatch(Character::isWhitespace)) throw wrong("board.port is no port's name: \"" + boardPort + "\"");
        armAux = number(p, "board.armAux", 1, 1, 4);
        cells = number(p, "battery.cells", 0, 1, 8);
        String floor = text(p, "battery.cellFloor");
        try {
            cellFloor = floor.isEmpty() ? 0 : Double.parseDouble(floor);
        } catch (NumberFormatException e) {
            throw wrong("battery.cellFloor is no number: \"" + floor + "\"");
        }
        if (!floor.isEmpty() && !(cellFloor >= 2.5 && cellFloor <= 4.2)) throw wrong("battery.cellFloor is volts per cell, 2.5 .. 4.2: " + floor);
        // >>> R3, the flight controller keeper: its control port, with the phone's and Drone setup's ports the two after it
        keeperPort = number(p, "keeper.port", 0, 1024, 65533);
        motorSeconds = number(p, "keeper.motorSeconds", 600, 0, 3600);
        String arm = text(p, "board.mayArm");
        if (!arm.isEmpty() && !arm.equals("true") && !arm.equals("false")) throw wrong("board.mayArm is true or false, not \"" + arm + "\"");
        mayArm = arm.equals("true");
        letFailsafeRun = truth(p, "board.letFailsafeRun");
        failsafeSeenOnSitl = truth(p, "board.failsafeSeenOnSitl");
        outputLimitMax = number(p, "board.outputLimitMax", 15, 1, 100);
        // <<< R3
    }

    // ------------------------------------------------------------------ where it is

    /** %LOCALAPPDATA%\DraupneBench\hardware.properties of the real user (a bench JVM's own LOCALAPPDATA is a sealed one). */
    public static Path file() {
        String local = System.getProperty("bench.realLocalAppData");
        if (local == null || local.isBlank()) local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) throw new IllegalStateException("No LOCALAPPDATA: where the hardware file would be is not known");
        return Path.of(local, "DraupneBench", FILE);
    }

    /** This PC has a hardware file: a hardware scenario runs. Without one it skips itself. */
    public static boolean present() {
        try {
            return Files.isRegularFile(file());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * This bench's hardware for this run: the file's, with the run's own word and the environment's before it where
     * a run has one ({@link #RUNS_OWN}). An IllegalStateException says what is missing or wrong, and where.
     */
    public static Hardware load() {
        return read(file(), System::getProperty, System::getenv);
    }

    /** The file alone. */
    public static Hardware read(Path file) {
        return read(file, key -> null, key -> null);
    }

    /** The file, and before it what the run's system properties, and then the environment, say of {@link #RUNS_OWN}. */
    static Hardware read(Path file, UnaryOperator<String> property, UnaryOperator<String> environment) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("No hardware file: " + file + " is not there. Copy " + EXAMPLE + " of the repo to that place and put in "
                    + "this bench's own phone and board. It stays on this PC - never into the repo.");
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("The hardware file " + file + " cannot be read: " + e.getMessage(), e);
        }
        Set<String> outside = new TreeSet<>();
        for (String key : RUNS_OWN) {
            String said = property.apply("bench." + key);
            if (said == null || said.isBlank()) said = environment.apply("BENCH_" + key.toUpperCase(Locale.ROOT).replace('.', '_'));
            if (said == null || said.isBlank()) continue;
            p.setProperty(key, said.trim());
            outside.add(key);
        }
        return new Hardware(file, p, outside);
    }

    private IllegalStateException wrong(String what) {
        return new IllegalStateException("The hardware file " + file + (outside.isEmpty() ? "" : " (with " + outside + " as the run or the environment says)")
                + " " + what + ". See " + EXAMPLE + ".");
    }

    private static String text(Properties p, String key) {
        String v = p.getProperty(key);
        return v == null ? "" : v.trim();
    }

    private int number(Properties p, String key, int unset, int min, int max) {
        String v = text(p, key);
        if (v.isEmpty()) return unset;
        try {
            int n = Integer.parseInt(v);
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) {
        }
        throw wrong(key + " is a number from " + min + " to " + max + ", not \"" + v + "\"");
    }

    /** A key that is true or false; left out: false. */
    private boolean truth(Properties p, String key) {
        String v = text(p, key);
        if (!v.isEmpty() && !v.equals("true") && !v.equals("false")) throw wrong(key + " is true or false, not \"" + v + "\"");
        return v.equals("true");
    }

    private IllegalStateException missing(String key, String what) {
        return new IllegalStateException("The hardware file " + file + " has no " + key + " (" + what + "). See " + EXAMPLE + ".");
    }

    // ------------------------------------------------------------------ the phone

    /** The phone's address as the PC reaches it, written out (never a name). */
    public String phoneIp() {
        return phoneIp;
    }

    /** The phone's link port (DRAUPNE Flight's own unless the file says another). */
    public int phonePort() {
        return phonePort;
    }

    /** The link key set in DRAUPNE Flight; "" when the file has none - the app's own default then. */
    public String phoneKey() {
        return phoneKey;
    }

    /** The phone's serial for adb (adb devices). */
    public String phoneAdb() {
        if (phoneAdb.isEmpty()) throw missing("phone.adb", "the phone's serial as adb devices lists it");
        return phoneAdb;
    }

    /**
     * Which DRAUPNE Flight a phone run flies - and may clear, stop and install over (R5, {@link PhoneGuard}): the bench
     * build ({@link BenchControl#PACKAGE}) unless the file, or the run, names the released app ({@link PhoneRig#APP}).
     */
    public String phonePackage() {
        return phonePackage;
    }

    /** What of this was not read from the file but said by the run or the environment ({@link #RUNS_OWN}); as a rule nothing. */
    public Set<String> saidOutsideTheFile() {
        return outside;
    }

    /** The way to the phone is one of Tailscale's relays (the file's phone.path=relay): the app is told so, as its own Tailscale would tell it. */
    public boolean relayed() {
        return relayed;
    }

    /**
     * The phone's Wi-Fi is normally on (phone.wifi; left out: on): a run that switches the phone's own network begins
     * only on a phone found so - Wi-Fi as this says, mobile data on, airplane mode off - and puts it back so.
     */
    public boolean phoneWifi() {
        return phoneWifi;
    }

    /** The one pair a hardware run's fence lets through. Throws in a JVM that is no hardware task's ({@link Fence.Reach}). */
    public Fence.Reach reach() {
        return Fence.Reach.of(phoneIp + ":" + phonePort);
    }

    /**
     * What the sealed app is told when it asks Tailscale who is on its network (the bench's door answers, the real
     * Tailscale is never asked): this PC, signed in, and one other device - the phone, online, at its address. The app
     * finds phones no other way; the address need not be a Tailscale one.
     */
    public String tailscaleStatus() {
        return "{\"BackendState\":\"Running\",\"Self\":{\"HostName\":\"bench-pc\",\"DNSName\":\"bench-pc.bench.ts.net.\","
                + "\"TailscaleIPs\":[\"127.0.0.1\"],\"Online\":true},\"Peer\":{\"hardware-phone\":{\"HostName\":\"" + PHONE_HOST + "\",\"OS\":\"android\","
                + "\"Online\":true,\"TailscaleIPs\":[\"" + phoneIp + "\"],\"CurAddr\":\"" + (relayed ? "" : phoneIp + ":41641") + "\","
                + "\"Relay\":\"" + (relayed ? "bench" : "") + "\"}}}";
    }

    /** The device name the sealed app files the phone under (in its hint: "... (hardware-phone) ..."). */
    public static final String PHONE_HOST = "hardware-phone";

    // ------------------------------------------------------------------ the board and its battery

    /** The flight controller's port on this PC, as the keeper opens it. */
    public String boardPort() {
        if (boardPort.isEmpty()) throw missing("board.port", "the flight controller's port on this PC");
        return boardPort;
    }

    /** The switch the board arms on (AUX1 unless the file says another): the one the radio's hands-off puts off. */
    public int armAux() {
        return armAux;
    }

    public int cells() {
        if (cells == 0) throw missing("battery.cells", "how many cells the pack has");
        return cells;
    }

    /** Volts per cell under which nothing is armed and a run ends. */
    public double cellFloor() {
        if (cellFloor == 0) throw missing("battery.cellFloor", "the volts per cell under which a run ends");
        return cellFloor;
    }

    /** The whole pack's floor in volts. */
    public double packFloor() {
        return cells() * cellFloor();
    }

    /** The keeper's control port, on this PC's loopback (127.0.0.1). */
    public int keeperPort() {
        if (keeperPort == 0) throw missing("keeper.port", "the keeper's control port on this PC");
        return keeperPort;
    }

    // >>> R3, the flight controller keeper (bench/hw/FcKeeper)

    /** The file names a keeper (keeper.port): a bench with a flight controller on this PC's USB. Without one nothing of a run is ever armed. */
    public boolean hasKeeper() {
        return keeperPort != 0;
    }

    /**
     * This session may arm the board (board.mayArm=true): the battery is in, the propellers are off, somebody decided
     * it. Left out or false - as the file is on every other day - no scenario asks the keeper for an arm window.
     */
    public boolean mayArm() {
        return mayArm;
    }

    /** How many seconds the board may be armed in any one hour (keeper.motorSeconds; 600 unless the file says another). */
    public int motorSecondsPerHour() {
        return motorSeconds;
    }

    /**
     * The board's own failsafe may run (board.letFailsafeRun=true): the keeper does not end an arm window when the
     * board's receiver is lost. For a real board the keeper takes that only together with {@link #failsafeSeenOnSitl()}.
     * Left out or false - the rule - the keeper ends the window itself, long before the board's failsafe_delay is over.
     */
    public boolean letFailsafeRun() {
        return letFailsafeRun;
    }

    /** Somebody has seen on Betaflight as a program what this board's failsafe procedure does (board.failsafeSeenOnSitl=true). */
    public boolean failsafeSeenOnSitl() {
        return failsafeSeenOnSitl;
    }

    /**
     * The most Betaflight's motor_output_limit may be, in per cent, for an arm window to open on this board
     * (board.outputLimitMax; 15 unless the file says another - the motor test's own cap is 10). gradlew boardCap writes
     * the limit to the board; a stock board has 100 and gets no window.
     */
    public int outputLimitMax() {
        return outputLimitMax;
    }

    /** The file this was read from: for whoever has to say where a word of it is wrong. */
    public Path source() {
        return file;
    }
    // <<< R3

    /** Without the key: this goes into films and logs. */
    @Override
    public String toString() {
        return "phone " + phoneIp + ":" + phonePort + (relayed ? " (relayed)" : "") + (boardPort.isEmpty() ? "" : ", board " + boardPort)
                + (cells == 0 ? "" : ", " + cells + " cells") + (cellFloor == 0 ? "" : ", floor " + cellFloor + " V a cell")
                + (outside.isEmpty() ? "" : " - " + outside + " not from the hardware file: the run's own word, or the environment's");
    }
}
