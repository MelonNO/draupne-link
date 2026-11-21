package de.droiddrone.bench;

import java.io.FileDescriptor;
import java.nio.file.Path;
import java.security.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The fence around a bench JVM (a Java security manager; JDK 21, started with -Djava.security.manager=allow). While
 * it is up, the app under test - and the bench itself - cannot
 * <ul>
 * <li>send to, connect to or look up anything but this PC's own loopback addresses (no real phone, no internet);</li>
 * <li>start a program that is not on the bench's short list;</li>
 * <li>write or delete a file outside the bench's own folders (never the pilot's flights, logs or settings);</li>
 * <li>end the JVM.</li>
 * </ul>
 * Each attempt is refused with a {@link Refused} and written down ({@link #refusals()}): a refusal is either a hole
 * in the bench's stand-ins or something the app does that nobody knew of - both worth a line in the report.
 */
@SuppressWarnings("removal")
public final class Fence extends SecurityManager {
    /** What was refused, and where in the app it came from. */
    public record Refusal(long atMs, String kind, String what, String thread, String where) {
        @Override
        public String toString() {
            return kind + " " + what + " [" + thread + "] at " + where;
        }
    }

    /** The refusal as the app's code meets it. */
    public static final class Refused extends SecurityException {
        public Refused(String message) {
            super(message);
        }
    }

    private static final List<Refusal> REFUSALS = new CopyOnWriteArrayList<>();
    private static volatile Fence up;

    private final List<String> writable = new ArrayList<>();
    private final Set<String> programs;
    private volatile boolean exitAllowed;

    private Fence(List<Path> writableRoots, Set<String> programs) {
        for (Path p : writableRoots) writable.add(norm(p.toAbsolutePath().normalize().toString()) + "\\");
        this.programs = programs;
    }

    /**
     * Puts the fence up.
     *
     * @param writableRoots the only folders anything may be written to or deleted from
     * @param programs      file names (lower case) of the programs that may be started
     */
    public static synchronized Fence up(List<Path> writableRoots, Set<String> programs) {
        if (up != null) return up;
        Fence f = new Fence(writableRoots, programs);
        System.setSecurityManager(f);
        up = f;
        return f;
    }

    /** Takes it down (the test JVM must be able to end). */
    public static synchronized void down() {
        Fence f = up;
        if (f == null) return;
        f.exitAllowed = true;
        System.setSecurityManager(null);
        up = null;
    }

    public static boolean isUp() {
        return up != null;
    }

    public static List<Refusal> refusals() {
        return List.copyOf(REFUSALS);
    }

    public static void forget() {
        REFUSALS.clear();
    }

    // ------------------------------------------------------------------ the rules

    @Override
    public void checkPermission(Permission perm) {
        // everything not named below is allowed: this is a fence around the outside world, not a sandbox for hostile code
    }

    @Override
    public void checkPermission(Permission perm, Object context) {
    }

    @Override
    public void checkConnect(String host, int port) {
        if (loopback(host)) return;
        // this PC asking for its own name's addresses (InetAddress.getLocalHost): refused - the app then gets loopback,
        // not this PC's real addresses - but not written down: every Java program does it
        String me = System.getenv("COMPUTERNAME");
        if (port < 0 && me != null && host != null && host.equalsIgnoreCase(me)) throw new Refused("The bench gives no addresses but loopback");
        refuse("network", host + (port >= 0 ? ":" + port : " (lookup)"));
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
        checkConnect(host, port);
    }

    @Override
    public void checkAccept(String host, int port) {
        // a packet from outside this PC reaching a bench socket: dropped by the JDK when this throws; not written down
        // (anything on the network may send one), only kept out
        if (!loopback(host)) throw new Refused("The bench takes nothing from " + host);
    }

    @Override
    public void checkMulticast(java.net.InetAddress maddr) {
        refuse("network", "multicast " + maddr);
    }

    @Override
    public void checkExec(String cmd) {
        String name = cmd == null ? "" : cmd.replace('/', '\\');
        name = name.substring(name.lastIndexOf('\\') + 1).toLowerCase(Locale.ROOT);
        if (programs.contains(name)) return;
        // File.canExecute and Files.isExecutable ask the same question as starting a program: only looking is allowed
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if (e.getClassName().equals("java.io.File") && e.getMethodName().equals("canExecute")) return;
            if (e.getClassName().startsWith("sun.nio.fs.") && e.getMethodName().equals("checkAccess")) return;
        }
        refuse("program", cmd);
    }

    @Override
    public void checkWrite(String file) {
        if (inside(file)) return;
        // File.canWrite and Files.isWritable ask the same question as writing: only looking is allowed (a save dialog
        // asks it of every folder in its side bar, the pilot's real Documents among them)
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if (e.getClassName().equals("java.io.File") && e.getMethodName().equals("canWrite")) return;
            if (e.getClassName().startsWith("sun.nio.fs.") && e.getMethodName().equals("checkAccess")) return;
        }
        refuse("write", file);
    }

    @Override
    public void checkWrite(FileDescriptor fd) {
    }

    @Override
    public void checkDelete(String file) {
        if (!inside(file)) refuse("delete", file);
    }

    @Override
    public void checkExit(int status) {
        if (!exitAllowed) refuse("exit", "System.exit(" + status + ")");
    }

    // ------------------------------------------------------------------

    static boolean loopback(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        int zone = h.indexOf('%');
        if (zone >= 0) h = h.substring(0, zone);
        if (h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) return true;
        if (h.startsWith("::ffff:")) h = h.substring(7);
        if (!h.startsWith("127.")) return false;
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit) || Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    private boolean inside(String file) {
        if (file == null) return false;
        String f;
        try {
            f = norm(Path.of(file).toAbsolutePath().normalize().toString());
        } catch (RuntimeException e) { // a name that is no path (a device, a pipe): not the pilot's files
            String lower = file.toLowerCase(Locale.ROOT);
            return lower.startsWith("\\\\.\\") || lower.equals("nul") || lower.equals("con");
        }
        if (f.equals("nul") || f.endsWith("\\nul") || f.startsWith("\\\\.\\")) return true;
        for (String root : writable) {
            if ((f + "\\").startsWith(root)) return true;
        }
        return false;
    }

    private static String norm(String path) {
        String p = path.replace('/', '\\').toLowerCase(Locale.ROOT);
        while (p.endsWith("\\")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private void refuse(String kind, String what) {
        String where = "?";
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement e : stack) { // the first frame of the app (or of the bench) under the JDK's
            String c = e.getClassName();
            if (c.startsWith("de.droiddrone.") && !c.equals(Fence.class.getName())) {
                where = c.substring(c.lastIndexOf('.') + 1) + "." + e.getMethodName() + ":" + e.getLineNumber();
                break;
            }
        }
        Refusal r = new Refusal(System.currentTimeMillis(), kind, what, Thread.currentThread().getName(), where);
        REFUSALS.add(r);
        throw new Refused("The test bench's fence: " + r);
    }
}
