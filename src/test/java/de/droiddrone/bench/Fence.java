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
 * <p>
 * A hardware run (gradlew benchHw / benchHwPhone, docs/TESTING.md) puts the fence up with a {@link Reach}: the one real
 * phone's address and port, and nothing else outside this PC. Every other run has none.
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

    /**
     * What a hardware run may reach outside this PC: a short list of address-and-port pairs, each written out
     * ("192.0.2.10:6286") - no host name, no range, no multicast or broadcast address, no port 0. A packet or a
     * connection to exactly such a pair goes out, and one from exactly such a pair comes in; another port of the same
     * address is as closed as the rest of the world. Fixed once made.
     * <p>
     * It fails closed: a list with anything in it can only be made in a JVM started with {@code -Dbench.hardware=true},
     * which the tasks benchHw and benchHwPhone set and no other. In every other bench run the making itself throws -
     * before a fence with a hole in it exists.
     */
    public static final class Reach {
        /** The system property a hardware task starts its JVM with. */
        public static final String PROPERTY = "bench.hardware";
        /** One real phone is what a run is for: more pairs than this is a range by another name. */
        static final int MOST = 4;
        private static final Reach NONE = new Reach(List.of());
        private static final java.util.regex.Pattern PAIR =
                java.util.regex.Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3}):(\\d{1,5})");

        private final List<String> pairs;
        /** The same, apart: asked for every packet of a hardware run, so nothing is put together there. */
        private final String[] ips;
        private final int[] ports;

        private Reach(List<String> pairs) {
            this.pairs = pairs;
            ips = new String[pairs.size()];
            ports = new int[pairs.size()];
            for (int i = 0; i < ips.length; i++) {
                int colon = pairs.get(i).indexOf(':');
                ips[i] = pairs.get(i).substring(0, colon);
                ports[i] = Integer.parseInt(pairs.get(i).substring(colon + 1));
            }
        }

        /** Nothing outside this PC: what every run but a hardware run has. */
        public static Reach none() {
            return NONE;
        }

        /**
         * @param pairs each "a.b.c.d:port"
         * @throws IllegalStateException    when there is a pair and this JVM is not a hardware task's
         * @throws IllegalArgumentException for anything that is not one address and one port, written out
         */
        public static Reach of(String... pairs) {
            return of(List.of(pairs));
        }

        public static Reach of(java.util.Collection<String> pairs) {
            if (pairs.isEmpty()) return NONE;
            requireHardwareRun();
            if (pairs.size() > MOST) throw new IllegalArgumentException("A hardware run reaches " + MOST + " addresses at most, not " + pairs.size());
            List<String> checked = new ArrayList<>();
            for (String pair : pairs) {
                String p = checked(pair);
                if (!checked.contains(p)) checked.add(p);
            }
            return new Reach(List.copyOf(checked));
        }

        static boolean hardwareRun() {
            return "true".equals(System.getProperty(PROPERTY));
        }

        static void requireHardwareRun() {
            if (!hardwareRun()) {
                throw new IllegalStateException("This is no hardware run (no -D" + PROPERTY + "=true): the fence lets nothing out of this PC. "
                        + "Only gradlew benchHw and benchHwPhone (.\\bench.ps1 hw | phone) may reach a real phone.");
            }
        }

        /** The pair as the fence compares it, or an IllegalArgumentException that says what is wrong with it. */
        static String checked(String pair) {
            String p = pair == null ? "" : pair.trim();
            java.util.regex.Matcher m = PAIR.matcher(p);
            if (!m.matches()) {
                throw new IllegalArgumentException("Not an address and a port written out (like 192.0.2.10:6286 - no name, no range): \"" + pair + "\"");
            }
            int[] part = new int[4];
            for (int i = 0; i < 4; i++) {
                String digits = m.group(i + 1);
                part[i] = Integer.parseInt(digits);
                if (part[i] > 255 || digits.length() > 1 && digits.startsWith("0")) {
                    throw new IllegalArgumentException("Not an address: \"" + pair + "\"");
                }
            }
            int port = Integer.parseInt(m.group(5));
            if (port < 1 || port > 65535 || m.group(5).length() > 1 && m.group(5).startsWith("0")) {
                throw new IllegalArgumentException("Not a port (1 .. 65535): \"" + pair + "\"");
            }
            if (part[0] == 0 || part[0] >= 224) { // 0.x: no device's address; 224 and up: multicast, reserved, broadcast
                throw new IllegalArgumentException("Not one device's address (multicast, broadcast or none at all): \"" + pair + "\"");
            }
            return part[0] + "." + part[1] + "." + part[2] + "." + part[3] + ":" + port;
        }

        public boolean isEmpty() {
            return pairs.isEmpty();
        }

        /** The pairs, each "a.b.c.d:port"; cannot be changed. */
        public List<String> pairs() {
            return pairs;
        }

        /** Whether this address, as the JDK names it to the fence, at this port is one of the pairs. */
        boolean allows(String host, int port) {
            if (ips.length == 0 || host == null || port < 1) return false;
            String h = host.trim();
            if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
            if (h.regionMatches(true, 0, "::ffff:", 0, 7)) h = h.substring(7);
            for (int i = 0; i < ips.length; i++) if (ports[i] == port && ips[i].equals(h)) return true;
            return false;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Reach r && r.pairs.equals(pairs);
        }

        @Override
        public int hashCode() {
            return pairs.hashCode();
        }

        @Override
        public String toString() {
            return pairs.isEmpty() ? "nothing outside this PC" : String.join(", ", pairs);
        }
    }

    private static final List<Refusal> REFUSALS = new CopyOnWriteArrayList<>();
    private static volatile Fence up;

    private final List<String> writable = new ArrayList<>();
    private final Set<String> programs;
    private final Reach reach;
    private volatile boolean exitAllowed;

    private Fence(List<Path> writableRoots, Set<String> programs, Reach reach) {
        for (Path p : writableRoots) writable.add(norm(p.toAbsolutePath().normalize().toString()) + "\\");
        this.programs = programs;
        this.reach = reach;
    }

    /**
     * Puts the fence up.
     *
     * @param writableRoots the only folders anything may be written to or deleted from
     * @param programs      file names (lower case) of the programs that may be started
     */
    public static synchronized Fence up(List<Path> writableRoots, Set<String> programs) {
        return up(writableRoots, programs, Reach.none());
    }

    /**
     * Puts the fence up for a hardware run: as above, and the pairs of {@code reach} can be reached. A fence that is
     * up already is kept only when it lets through exactly the same - one with another reach is never taken for this
     * one (a hole nobody asked for, or a real phone that cannot be reached and nobody knows why).
     */
    public static synchronized Fence up(List<Path> writableRoots, Set<String> programs, Reach reach) {
        if (reach == null) throw new IllegalArgumentException("No reach: Reach.none() is the one that lets nothing out");
        if (!reach.isEmpty()) Reach.requireHardwareRun(); // again here: the list may have been made at another time
        if (up != null) {
            if (!up.reach.equals(reach)) {
                throw new IllegalStateException("A fence is up that reaches " + up.reach + ": it is not one that reaches " + reach);
            }
            return up;
        }
        Fence f = new Fence(writableRoots, programs, reach);
        System.setSecurityManager(f);
        up = f;
        return f;
    }

    /** What the fence that is up lets out of this PC ({@link Reach#none()} without a fence). */
    public static Reach reach() {
        Fence f = up;
        return f == null ? Reach.none() : f.reach;
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
        if (reach.allows(host, port)) return; // a hardware run's real phone, at its port (a lookup has no port: refused below)
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
        if (!loopback(host) && !reach.allows(host, port)) throw new Refused("The bench takes nothing from " + host);
    }

    @Override
    public void checkMulticast(java.net.InetAddress maddr) {
        refuse("network", "multicast " + maddr);
    }

    /** The older way of sending to a group (MulticastSocket.send with a ttl) asks here: as closed as the other. */
    @Override
    @Deprecated
    public void checkMulticast(java.net.InetAddress maddr, byte ttl) {
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
