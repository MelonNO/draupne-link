package de.droiddrone.bench;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The facts of one bench's real hardware - the phone on the drone, the flight controller, its battery - for the runs
 * that reach them (gradlew benchHw / benchHwPhone). They are this PC's own and are read from a file outside the repo,
 * {@code %LOCALAPPDATA%\DraupneBench\hardware.properties}; a copy with made-up values to start from is
 * docs/hardware.example.properties. No such file: no hardware run - a scenario skips itself ({@link #present()}),
 * and {@link #load()} says what is missing and where it belongs.
 */
public final class Hardware {
    public static final String FILE = "hardware.properties";
    /** The copy to start from, in the repo: made-up values only. */
    public static final String EXAMPLE = "docs/hardware.example.properties";
    /** The most throttle a hardware run's radio ever gives (a channel value): the motors turn, no more. */
    public static final int THROTTLE_CEILING = 1100;

    private static final Set<String> KEYS = Set.of("phone.ip", "phone.port", "phone.key", "phone.adb", "phone.path",
            "board.port", "board.armAux", "battery.cells", "battery.cellFloor", "keeper.port");

    private final Path file;
    private final String phoneIp, phoneKey, phoneAdb, boardPort;
    private final int phonePort, armAux, cells, keeperPort;
    private final boolean relayed;
    private final double cellFloor;

    private Hardware(Path file, Properties p) {
        this.file = file;
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
        String path = text(p, "phone.path");
        if (!path.isEmpty() && !path.equals("direct") && !path.equals("relay")) throw wrong("phone.path is direct or relay, not \"" + path + "\"");
        relayed = path.equals("relay");
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
        keeperPort = number(p, "keeper.port", 0, 1024, 65535);
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

    /** This bench's hardware. An IllegalStateException says what is missing or wrong, and where. */
    public static Hardware load() {
        return read(file());
    }

    public static Hardware read(Path file) {
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
        return new Hardware(file, p);
    }

    private IllegalStateException wrong(String what) {
        return new IllegalStateException("The hardware file " + file + " " + what + ". See " + EXAMPLE + ".");
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

    /** The way to the phone is one of Tailscale's relays (the file's phone.path=relay): the app is told so, as its own Tailscale would tell it. */
    public boolean relayed() {
        return relayed;
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

    /** Without the key: this goes into films and logs. */
    @Override
    public String toString() {
        return "phone " + phoneIp + ":" + phonePort + (relayed ? " (relayed)" : "") + (boardPort.isEmpty() ? "" : ", board " + boardPort)
                + (cells == 0 ? "" : ", " + cells + " cells") + (cellFloor == 0 ? "" : ", floor " + cellFloor + " V a cell");
    }
}
