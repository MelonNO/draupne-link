package de.droiddrone.pc.fleet;

import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.MonoClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The virtual fleet as DRAUPNE Link sees it: a separate program (`gradlew run --args=fleet`, or the installed app with
 * the argument `fleet`) that plays several drones - phone and flight controller - on this PC, for trying the window
 * and the fleet functions without hardware ({@link FleetMain}).
 * <p>
 * How DRAUPNE Link finds them, opt-in and explicit: while it runs, the fleet program keeps a small file in the app's
 * data folder ({@link #FILE}) with a heartbeat and each virtual phone's address - every one on a loopback address of
 * its own (127.0.0.11, 127.0.0.12, ...) at a port of its own (two programs can't share the link port on one PC). The
 * name service asks those addresses who they are, as it asks the Tailscale peers; the link, the observation and the
 * name map send to the port the file gives. No file, or a heartbeat older than {@link #STALE_MS}: nothing here changes
 * anything - only loopback addresses are ever taken from the file, and a real phone is never on one.
 * <p>
 * A drone at a loopback address is virtual ({@link #isVirtual}): the PHONE list, the LINK card, the OSD and the map say
 * VIRTUAL wherever it shows, and its name map entry is never saved to the settings.
 */
public final class VirtualFleet {
    /** In the app's data folder (%APPDATA%\DraupneLink). */
    public static final String FILE = "virtual-fleet.txt";
    /** The fleet program writes its file this often... */
    public static final long HEARTBEAT_MS = 2000;
    /** ...and a file older than this is a fleet that ended without tidying up: ignored. */
    public static final long STALE_MS = 10_000;
    /**
     * Beside {@link #FILE}: DRAUPNE Link asks the fleet program to end (the tour's Overwatch demo needs the loopback
     * drones to itself). The fleet program looks for it every {@link #STOP_POLL_MS} and ends as its window's close does.
     */
    public static final String STOP_FILE = "virtual-fleet.stop";
    public static final long STOP_POLL_MS = 300;
    /** The first virtual phone's loopback address is 127.0.0.FIRST_HOST, the next one up, and so on. */
    public static final int FIRST_HOST = 11;

    /** One virtual phone: its loopback address and the port it listens on. */
    public record Drone(String ip, int port) {}

    private static final Object cacheLock = new Object();
    private static List<Drone> cached = List.of();
    private static long cachedAt;
    private static boolean everRead;
    /** The file is read at most this often: the name service and the link ask often. */
    static final long CACHE_MS = 1000;

    private VirtualFleet() {
    }

    /** The file the fleet program writes and DRAUPNE Link reads. */
    public static Path file() {
        return AppPaths.dataDir().resolve(FILE);
    }

    /**
     * An address on this PC's loopback network (127.x.x.x): only the virtual fleet can be there, never a phone. Read from
     * the text alone, no name lookup.
     */
    public static boolean isVirtual(String ip) {
        return ip != null && (ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"));
    }

    /** The virtual phones announced now (empty: no fleet running). */
    public static List<Drone> announced() {
        long now = MonoClock.ms();
        synchronized (cacheLock) {
            if (everRead && now - cachedAt < CACHE_MS) return cached;
            cached = read(file(), System.currentTimeMillis());
            cachedAt = now;
            everRead = true;
            return cached;
        }
    }

    /** As {@link #announced()}, but read from the file now, not up to {@link #CACHE_MS} old (a fleet just closed). */
    public static List<Drone> announcedNow() {
        synchronized (cacheLock) {
            everRead = false;
        }
        return announced();
    }

    /** The stop request's file. */
    public static Path stopFile() {
        return AppPaths.dataDir().resolve(STOP_FILE);
    }

    /** Asks a running fleet program to end (it takes its drones and its announcement with it). */
    public static void requestStop() throws IOException {
        requestStop(stopFile());
    }

    static void requestStop(Path stop) throws IOException {
        Files.createDirectories(stop.getParent());
        Files.writeString(stop, Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
    }

    /**
     * The fleet program's side: true once a stop was asked (the request is taken away). A request left from before the
     * program started (an older fleet that never read it) doesn't count: see {@link #clearStop}.
     */
    public static boolean stopRequested(Path stop) {
        try {
            return Files.deleteIfExists(stop);
        } catch (IOException e) {
            return Files.exists(stop);
        }
    }

    /** At the fleet program's start: an old request is not for this one. */
    public static void clearStop(Path stop) {
        try {
            Files.deleteIfExists(stop);
        } catch (IOException ignored) {
            // asked again if it matters
        }
    }

    /**
     * The port to send to for a phone at this address: the virtual phone's own port when the fleet announced one there,
     * else the link port - always the link port for an address that isn't loopback.
     */
    public static int portOf(String ip, int linkPort) {
        if (!isVirtual(ip)) return linkPort;
        for (Drone d : announced()) if (d.ip().equals(ip)) return d.port();
        return linkPort;
    }

    /**
     * What a fleet file says, if its heartbeat is fresh at nowMs: the loopback addresses in it (anything else is
     * dropped). Empty for no file, a stale or broken one.
     */
    static List<Drone> read(Path file, long nowMs) {
        List<String> lines;
        try {
            if (!Files.isRegularFile(file)) return List.of();
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        long heartbeat = -1;
        List<Drone> drones = new ArrayList<>();
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("heartbeat=")) {
                try {
                    heartbeat = Long.parseLong(line.substring("heartbeat=".length()).trim());
                } catch (NumberFormatException ignored) {
                    return List.of();
                }
            } else if (line.startsWith("drone=")) {
                Drone d = parse(line.substring("drone=".length()).trim());
                if (d != null && drones.size() < 32) drones.add(d);
            }
        }
        if (heartbeat < 0 || Math.abs(nowMs - heartbeat) > STALE_MS) return List.of();
        return List.copyOf(drones);
    }

    /** "127.0.0.11:51234" - only an IPv4 loopback address and a port. */
    static Drone parse(String s) {
        int colon = s.lastIndexOf(':');
        if (colon < 0) return null;
        String ip = s.substring(0, colon);
        if (!ip.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return null;
        for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255) return null;
        try {
            int port = Integer.parseInt(s.substring(colon + 1));
            return port > 0 && port < 65536 ? new Drone(ip, port) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The fleet program's heartbeat: the file written whole (a temporary file moved over it). */
    static void write(Path file, List<Drone> drones, long nowMs) throws IOException {
        StringBuilder sb = new StringBuilder("# DRAUPNE Link virtual fleet - written by the fleet program while it runs\n");
        sb.append("heartbeat=").append(nowMs).append('\n');
        for (Drone d : drones) sb.append("drone=").append(d.ip()).append(':').append(d.port()).append('\n');
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The fleet program ends: its file goes, and DRAUPNE Link stops asking at once. */
    static void withdraw(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // stale within STALE_MS anyway
        }
        synchronized (cacheLock) {
            everRead = false; // in this program (the tour's own fleet): gone at once, not up to CACHE_MS later
        }
    }
}
