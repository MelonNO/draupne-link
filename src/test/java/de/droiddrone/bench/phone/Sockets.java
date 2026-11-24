package de.droiddrone.bench.phone;

import de.droiddrone.common.TailscaleOnly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BS1, what the phone listens on: the kernel's own socket tables as Android prints them ({@code /proc/net/udp},
 * {@code udp6}, {@code tcp}, {@code tcp6} - readable from adb's shell without root, every app's sockets with the uid
 * that owns each), and {@code ip}'s list of the phone's addresses, to say on which interface a socket's address is.
 * Pure: {@code SocketsTest} holds it to lines as Android writes them.
 * <p>
 * A line of a table: {@code sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid timeout inode
 * ...}. An address is printed as the 32-bit words the kernel holds, each in the processor's own byte order - little
 * endian on every Android phone and on the emulator - so each word's four bytes are read backwards; a port is
 * printed as a plain hexadecimal number.
 * <p>
 * The film never gets an address of the phone's in numbers: only its kind (every address, loopback, Tailscale's, another
 * one) and the interface it is on ({@link #said}).
 */
final class Sockets {
    private Sockets() {
    }

    /** TCP's state "listening" in the tables. */
    static final int TCP_LISTEN = 0x0A;

    /** Where a socket takes packets: on every address of the phone, its own loopback, a Tailscale address, another one. */
    enum Where {
        EVERY_ADDRESS, LOOPBACK, TAILSCALE, OTHER
    }

    /**
     * One socket.
     *
     * @param table udp, udp6, tcp or tcp6
     * @param state the table's st: for TCP {@link #TCP_LISTEN} is listening; for UDP 07 is not connected, 01 connected to one peer
     */
    record Sock(String table, byte[] local, int localPort, byte[] remote, int remotePort, int state, int uid) {
        boolean tcp() {
            return table.startsWith("tcp");
        }

        /**
         * Whether it takes what anyone sends to its address: a TCP socket that listens; a UDP socket not connected to
         * one peer (a connected one takes datagrams from that peer only).
         */
        boolean listens() {
            return tcp() ? state == TCP_LISTEN : remotePort == 0 && allZero(remote);
        }

        Where where() {
            return Sockets.where(local);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Sock s && table.equals(s.table) && Arrays.equals(local, s.local) && localPort == s.localPort && Arrays.equals(remote, s.remote)
                    && remotePort == s.remotePort && state == s.state && uid == s.uid;
        }

        @Override
        public int hashCode() {
            return (table.hashCode() * 31 + Arrays.hashCode(local)) * 31 + localPort;
        }

        @Override
        public String toString() {
            return table + " " + where() + " port " + localPort + " st " + state + " uid " + uid;
        }
    }

    private static final Pattern LINE = Pattern.compile(
            "^\\s*\\d+:\\s+([0-9A-Fa-f]{8}|[0-9A-Fa-f]{32}):([0-9A-Fa-f]{4})\\s+([0-9A-Fa-f]{8}|[0-9A-Fa-f]{32}):([0-9A-Fa-f]{4})\\s+([0-9A-Fa-f]{2})\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s",
            Pattern.MULTILINE);

    /** Every socket of one table's text ({@code cat /proc/net/<table>}); the header and anything that is no socket's line are passed over. */
    static List<Sock> parse(String table, String text) {
        List<Sock> out = new ArrayList<>();
        Matcher m = LINE.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(new Sock(table, address(m.group(1)), Integer.parseInt(m.group(2), 16), address(m.group(3)), Integer.parseInt(m.group(4), 16),
                    Integer.parseInt(m.group(5), 16), Integer.parseInt(m.group(6))));
        }
        return out;
    }

    /** The sockets of one uid. */
    static List<Sock> of(List<Sock> all, int uid) {
        return all.stream().filter(s -> s.uid() == uid).toList();
    }

    /** An address as the tables print it: 8 or 32 hex digits, each word of 4 bytes in little-endian order. */
    static byte[] address(String hex) {
        byte[] a = new byte[hex.length() / 2];
        for (int word = 0; word < a.length / 4; word++) {
            long v = Long.parseLong(hex.substring(word * 8, word * 8 + 8), 16);
            for (int b = 0; b < 4; b++) a[word * 4 + b] = (byte) (v >>> (8 * b));
        }
        return a;
    }

    static boolean allZero(byte[] a) {
        for (byte b : a) if (b != 0) return false;
        return true;
    }

    /** An IPv4 address inside an IPv6 one (::ffff:a.b.c.d) as its four bytes; anything else as it is. */
    static byte[] plain(byte[] a) {
        if (a.length != 16) return a;
        for (int i = 0; i < 10; i++) if (a[i] != 0) return a;
        if ((a[10] & 0xff) != 0xff || (a[11] & 0xff) != 0xff) return a;
        return Arrays.copyOfRange(a, 12, 16);
    }

    static Where where(byte[] address) {
        byte[] a = plain(address);
        if (allZero(a)) return Where.EVERY_ADDRESS;
        if (a.length == 4 && (a[0] & 0xff) == 127) return Where.LOOPBACK;
        if (a.length == 16 && a[15] == 1 && allZero(Arrays.copyOf(a, 15))) return Where.LOOPBACK;
        if (a.length == 4 ? TailscaleOnly.inV4Range(a) : TailscaleOnly.inV6Range(a)) return Where.TAILSCALE;
        return Where.OTHER;
    }

    /** One of the phone's addresses, as {@code ip -o addr show} lists it. */
    record Address(String iface, byte[] address) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Address x && iface.equals(x.iface) && Arrays.equals(address, x.address);
        }

        @Override
        public int hashCode() {
            return iface.hashCode() * 31 + Arrays.hashCode(address);
        }
    }

    private static final Pattern IP_LINE = Pattern.compile("^\\s*\\d+:\\s+(\\S+?)(?:@\\S+)?\\s+inet(6?)\\s+([0-9A-Fa-f:.]+)(?:/\\d+)?", Pattern.MULTILINE);

    /** The phone's addresses from {@code ip -o addr show}: one line each, "12: tun0    inet 100.x.y.z/32 ...". */
    static List<Address> addresses(String ipOutput) {
        List<Address> out = new ArrayList<>();
        Matcher m = IP_LINE.matcher(ipOutput == null ? "" : ipOutput);
        while (m.find()) {
            byte[] a = numbers(m.group(3), !m.group(2).isEmpty());
            if (a != null) out.add(new Address(m.group(1), a));
        }
        return out;
    }

    /** An address written out (dotted, or IPv6 with :: once), as bytes - nothing looked up; null when it is none. */
    static byte[] numbers(String text, boolean v6) {
        try {
            if (!v6) {
                String[] p = text.split("\\.");
                if (p.length != 4) return null;
                byte[] a = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int n = Integer.parseInt(p[i]);
                    if (n < 0 || n > 255) return null;
                    a[i] = (byte) n;
                }
                return a;
            }
            String[] halves = text.split("::", -1);
            if (halves.length > 2) return null;
            List<String> head = groups(halves[0]), tail = halves.length == 2 ? groups(halves[1]) : List.of();
            int missing = 8 - head.size() - tail.size();
            if (missing < 0 || halves.length == 1 && missing != 0) return null;
            List<String> all = new ArrayList<>(head);
            for (int i = 0; i < missing; i++) all.add("0");
            all.addAll(tail);
            byte[] a = new byte[16];
            for (int i = 0; i < 8; i++) {
                int g = Integer.parseInt(all.get(i), 16);
                if (g < 0 || g > 0xffff) return null;
                a[2 * i] = (byte) (g >> 8);
                a[2 * i + 1] = (byte) g;
            }
            return a;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> groups(String s) {
        return s.isEmpty() ? List.of() : List.of(s.split(":"));
    }

    /** The interface that has this address ("" when none listed has it). An address inside IPv6 is compared as IPv4. */
    static String ifaceOf(List<Address> phone, byte[] address) {
        byte[] a = plain(address);
        for (Address x : phone) if (Arrays.equals(plain(x.address()), a)) return x.iface();
        return "";
    }

    /** What the film says of a socket: its kind of address and the interface, never the phone's own numbers. */
    static String said(Sock s, String iface) {
        boolean v6 = s.local().length == 16 && plain(s.local()).length == 16;
        String on = switch (s.where()) {
            case EVERY_ADDRESS -> "EVERY ADDRESS (" + (v6 ? "::" : "0.0.0.0") + ")";
            case LOOPBACK -> "the phone's own loopback (" + (v6 ? "::1" : "127.0.0.1") + ")";
            case TAILSCALE -> "a Tailscale address" + (iface.isEmpty() ? "" : " (on " + iface + ")");
            case OTHER -> "ANOTHER ADDRESS OF THE PHONE'S" + (iface.isEmpty() ? "" : " (on " + iface + ")");
        };
        String kind = s.tcp() ? (s.state() == TCP_LISTEN ? "TCP, listening" : "TCP, a connection") : s.listens() ? "UDP" : "UDP, connected to one peer";
        return kind + " on " + on + ", port " + s.localPort();
    }

    // ------------------------------------------------------------------ the process

    private static final Pattern UID = Pattern.compile("(?m)^Uid:\\s+(\\d+)"), THREADS = Pattern.compile("(?m)^Threads:\\s+(\\d+)"),
            RSS = Pattern.compile("(?m)^VmRSS:\\s+(\\d+)\\s*kB");

    /** The real uid in {@code /proc/<pid>/status}; -1 when it does not say. */
    static int uid(String status) {
        return number(UID, status);
    }

    /** The process's threads in {@code /proc/<pid>/status}; -1 when it does not say. */
    static int threads(String status) {
        return number(THREADS, status);
    }

    /** Its resident memory in {@code /proc/<pid>/status}, kB; -1 when it does not say. */
    static int rssKb(String status) {
        return number(RSS, status);
    }

    private static int number(Pattern p, String text) {
        Matcher m = p.matcher(text == null ? "" : text);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** The first pid of {@code pidof}'s answer; "" when it gave none. */
    static String firstPid(String pidof) {
        String s = pidof == null ? "" : pidof.trim();
        if (s.isEmpty() || !s.matches("[\\d\\s]+")) return "";
        return s.split("\\s+")[0];
    }
}
