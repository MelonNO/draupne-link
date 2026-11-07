package de.droiddrone.pc;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DRAUPNE names (1.0.35): every phone is MobileAlpha, MobileBravo ..., every PC PCAlpha, PCBravo ..., and the pilot
 * picks a phone by its name instead of an address. The name map here is what this PC has seen on its Tailscale
 * network: name, address, device, last seen; kept in the settings file (name.MobileAlpha=...). A PC names itself
 * (the first PC letter it has not seen); the pilot PC with the highest letter online is the master and gives each
 * unnamed phone the first free Mobile letter ({@link NameService}).
 * <p>
 * The packets, on the link port, each starting with the link key (another key: dropped without a word):
 * <pre>
 * NAME_QUERY  [0x73][key UTF]                                             who are you?
 * NAME_REPLY  [0x74][key UTF][kind u8: 0 phone, 1 PC][name UTF][device UTF][version UTF]   (name "" = none yet)
 * NAME_ASSIGN [0x75][key UTF][name UTF]                                   the master PC names a phone
 * </pre>
 */
public final class Names {
    public static final byte NAME_QUERY = 0x73, NAME_REPLY = 0x74, NAME_ASSIGN = 0x75;
    public static final String[] LETTERS = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India",
            "Juliett", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform",
            "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"};
    /** The settings key of this PC's own name. */
    public static final String PC_NAME_KEY = "pcName";
    /** The settings key of the phone the pilot chose (its name; the address is looked up in the map). */
    public static final String PHONE_NAME_KEY = "phoneName";
    private static final String PREFIX = "name.";

    public enum Kind {
        PHONE("Mobile"), PC("PC");

        public final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        public String name(int letter) {
            return prefix + LETTERS[letter];
        }
    }

    /** One device the PC has seen. */
    public record Entry(String name, Kind kind, String ip, String device, String host, String version, long lastSeen, boolean online) {
        public Entry withOnline(boolean on) {
            return new Entry(name, kind, ip, device, host, version, lastSeen, on);
        }

        /** A drone of the virtual fleet on this PC (a loopback address), never a real one. */
        public boolean isVirtual() {
            return de.droiddrone.pc.fleet.VirtualFleet.isVirtual(ip);
        }

        /** "Pixel 8 - 2.3.0-pc.28", what is known. */
        public String detail() {
            StringBuilder sb = new StringBuilder();
            if (device != null && !device.isEmpty()) sb.append(device);
            if (host != null && !host.isEmpty() && !host.equalsIgnoreCase(device)) sb.append(sb.length() > 0 ? "  (" + host + ")" : host);
            if (version != null && !version.isEmpty()) sb.append(sb.length() > 0 ? " - " : "").append(version);
            return sb.toString();
        }
    }

    /** What a NAME_REPLY said. */
    /**
     * @param position where the phone's drone is, from its reply (phone app pc.32 on), or null
     */
    public record Reply(Kind kind, String name, String device, String version, Position position) {
        public Reply(Kind kind, String name, String device, String version) {
            this(kind, name, device, version, null);
        }
    }

    /**
     * A phone's word on where its drone is (phone app pc.32 on, in its NAME_REPLY): from the FC's GPS or the phone's,
     * how old, armed (null = unknown), whether a PC flies it and which (its address, or "").
     */
    public record Position(boolean fromFc, double lat, double lon, int ageS, Boolean armed, boolean flown, String pilotIp) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Consumer<Map<String, String>> saver;

    /**
     * @param config the settings to read the map from (name.* keys)
     * @param saver  writes changed keys back (null: in memory only)
     */
    public Names(PcConfig config, Consumer<Map<String, String>> saver) {
        this.saver = saver;
        if (config != null) {
            for (String key : config.keys()) {
                if (!key.startsWith(PREFIX)) continue;
                Entry e = parse(key.substring(PREFIX.length()), config.getString(key));
                if (e != null) entries.put(e.name().toLowerCase(Locale.ROOT), e);
            }
        }
    }

    /** kind|ip|device|host|version|lastSeen */
    static Entry parse(String name, String value) {
        String[] p = value.split("\\|", -1);
        if (p.length < 6) return null;
        try {
            return new Entry(name, Kind.valueOf(p[0]), p[1], p[2], p[3], p[4], Long.parseLong(p[5]), false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String format(Entry e) {
        return e.kind().name() + "|" + nz(e.ip()) + "|" + nz(e.device()).replace('|', ' ') + "|" + nz(e.host()).replace('|', ' ')
                + "|" + nz(e.version()).replace('|', ' ') + "|" + e.lastSeen();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** MobileAlpha -> PHONE, PCAlpha -> PC, anything else null. */
    public static Kind kindOf(String name) {
        if (name == null) return null;
        for (Kind k : Kind.values()) {
            if (name.startsWith(k.prefix) && letterOf(name) >= 0) return k;
        }
        return null;
    }

    /** A device or version text from the network is cut to this many characters. */
    static final int MAX_TEXT = 64;

    /**
     * A valid DRAUPNE name of this kind (1.0.49: only these are taken from the network, filed or saved): the kind's
     * prefix and one of the 26 letters, exactly - MobileAlpha, PCZulu: printable ASCII, at most 16 characters (the
     * longest is 14); anything else in a NAME_REPLY or NAME_ASSIGN is dropped.
     */
    public static boolean isValidName(String name, Kind kind) {
        return name != null && kind != null && name.length() <= 16 && kindOf(name) == kind && letterOf(name) >= 0
                && name.chars().allMatch(c -> c > 0x20 && c < 0x7f);
    }

    /**
     * A text from the network made safe to show and save: control characters, line breaks and '|' (the map's field
     * separator) become spaces, trimmed, at most max characters.
     */
    static String printable(String s, int max) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char c = s.charAt(i);
            out.append(Character.isISOControl(c) || c == '|' || c == ' ' || c == ' ' ? ' ' : c);
        }
        return out.toString().trim();
    }

    /** The letter's index (Alpha 0 ... Zulu 25) of a name, -1 for a name that isn't one of ours. */
    public static int letterOf(String name) {
        if (name == null) return -1;
        for (Kind k : Kind.values()) {
            if (!name.startsWith(k.prefix)) continue;
            String rest = name.substring(k.prefix.length());
            for (int i = 0; i < LETTERS.length; i++) if (LETTERS[i].equalsIgnoreCase(rest)) return i;
        }
        return -1;
    }

    /** The first name of a kind not in the map and not in alsoTaken (Alpha first); null when all 26 are taken. */
    public synchronized String firstFree(Kind kind, Collection<String> alsoTaken) {
        for (int i = 0; i < LETTERS.length; i++) {
            String name = kind.name(i);
            if (entries.containsKey(name.toLowerCase(Locale.ROOT))) continue;
            boolean taken = false;
            for (String t : alsoTaken) if (name.equalsIgnoreCase(t)) taken = true;
            if (!taken) return name;
        }
        return null;
    }

    /**
     * A device answered: its entry made or brought up to date (online, seen now). A name that moved to another
     * address takes the new one - the newest claim wins - and the old address is returned so it can be said.
     * @return the address the name was at before, when it changed; else null
     */
    public synchronized String seen(Reply reply, String ip, String host) {
        if (reply.name() == null || reply.name().isEmpty()) return null;
        String key = reply.name().toLowerCase(Locale.ROOT);
        Entry old = entries.get(key);
        String moved = old != null && old.ip() != null && !old.ip().isEmpty() && !old.ip().equals(ip) ? old.ip() : null;
        // a drone of the virtual fleet (the name service asked it because the fleet announced it)
        boolean virtual = NameService.VIRTUAL_HOST.equals(host) && de.droiddrone.pc.fleet.VirtualFleet.isVirtual(ip);
        // a virtual drone never takes over the name of a real one: its claim on a name filed at a real address is dropped
        if (virtual && old != null && moved != null && !de.droiddrone.pc.fleet.VirtualFleet.isVirtual(old.ip())) return null;
        Entry e = new Entry(reply.name(), reply.kind(), ip, reply.device(), host, reply.version(), System.currentTimeMillis(), true);
        entries.put(key, e);
        if (!virtual) save(e); // a virtual drone is known while the fleet runs, never saved to the settings
        return moved;
    }

    /** After a scan: everything not among these addresses is offline (kept, with when it was last seen). */
    public synchronized void onlineOnly(Collection<String> onlineIps) {
        for (Map.Entry<String, Entry> m : entries.entrySet()) {
            Entry e = m.getValue();
            boolean on = e.ip() != null && onlineIps.contains(e.ip());
            if (on != e.online()) m.setValue(e.withOnline(on));
        }
    }

    public synchronized Entry get(String name) {
        return name == null ? null : entries.get(name.toLowerCase(Locale.ROOT));
    }

    /** The address of a name, or null when unknown. */
    public synchronized String ipOf(String name) {
        Entry e = get(name);
        return e == null || e.ip() == null || e.ip().isEmpty() ? null : e.ip();
    }

    /** The name at an address, or null. */
    public synchronized String nameAt(String ip) {
        if (ip == null) return null;
        for (Entry e : entries.values()) if (ip.equals(e.ip())) return e.name();
        return null;
    }

    /** The entries of a kind, online first, then by letter. */
    public synchronized List<Entry> list(Kind kind) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) if (e.kind() == kind) out.add(e);
        out.sort((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1) : Integer.compare(letterOf(a.name()), letterOf(b.name())));
        return out;
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    /**
     * A phone given another name (the name map's RENAME, 1.0.37): its entry moves to the new name, which must be a free
     * name of the same kind. The phone itself is told with NAME_ASSIGN ({@link NameService#assign}).
     * @return false when the old name is unknown or the new one is taken or not a name of that kind
     */
    public synchronized boolean rename(String from, String to) {
        Entry e = get(from);
        if (e == null || to == null || kindOf(to) != e.kind() || get(to) != null) return false;
        entries.remove(from.toLowerCase(Locale.ROOT));
        Entry moved = new Entry(to, e.kind(), e.ip(), e.device(), e.host(), e.version(), e.lastSeen(), e.online());
        entries.put(to.toLowerCase(Locale.ROOT), moved);
        if (saver != null) saver.accept(Map.of(PREFIX + e.name(), "", PREFIX + moved.name(), format(moved)));
        return true;
    }

    public synchronized void remove(String name) {
        if (name == null) return;
        Entry e = entries.remove(name.toLowerCase(Locale.ROOT));
        if (e != null && saver != null) saver.accept(Map.of(PREFIX + e.name(), ""));
    }

    private void save(Entry e) {
        if (saver != null) saver.accept(Map.of(PREFIX + e.name(), format(e)));
    }

    /** The PC letters online other than this PC's, for the master rule: the highest letter online is the master. */
    public synchronized boolean isMaster(String ownName) {
        int mine = letterOf(ownName);
        if (mine < 0) return false;
        for (Entry e : entries.values()) {
            if (e.kind() == Kind.PC && e.online() && !e.name().equalsIgnoreCase(ownName) && letterOf(e.name()) > mine) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ the packets

    public static byte[] query(String key) {
        return packet(NAME_QUERY, key, null);
    }

    public static byte[] assign(String key, String name) {
        return packet(NAME_ASSIGN, key, out -> out.writeUTF(name));
    }

    public static byte[] reply(String key, Kind kind, String name, String device, String version) {
        return packet(NAME_REPLY, key, out -> {
            out.writeByte(kind == Kind.PHONE ? 0 : 1);
            out.writeUTF(nz(name));
            out.writeUTF(nz(device));
            out.writeUTF(nz(version));
        });
    }

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] packet(byte code, String key, Body body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(code);
            out.writeUTF(key);
            if (body != null) body.write(out);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The key a query, reply or assign starts with; null for a packet too short. */
    public static String keyOf(byte[] d, int len) {
        if (len < 3) return null;
        int n = ((d[1] & 0xff) << 8) | (d[2] & 0xff);
        if (3 + n > len) return null;
        return new String(d, 3, n, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A NAME_REPLY's contents, or null when it isn't one for this key. */
    public static Reply parseReply(byte[] d, int len, String key) {
        if (len < 1 || d[0] != NAME_REPLY || !key.equals(keyOf(d, len))) return null;
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(d, 1, len - 1))) {
            in.readUTF();
            int kind = in.readUnsignedByte();
            String name = in.readUTF(), device = in.readUTF(), version = in.readUTF();
            Kind k = kind == 0 ? Kind.PHONE : kind == 1 ? Kind.PC : null;
            if (k == null || !name.isEmpty() && !isValidName(name, k)) return null; // not a DRAUPNE name: never filed
            return new Reply(k, name, printable(device, MAX_TEXT), printable(version, MAX_TEXT), readPosition(in));
        } catch (IOException e) {
            return null;
        }
    }

    /** The position block after the version (phone app pc.32), or null: none, no fix, or a version unknown here. */
    static Position readPosition(DataInputStream in) {
        try {
            if (in.available() < 1 || in.readUnsignedByte() != 1) return null;
            int source = in.readUnsignedByte();
            int lat = in.readInt(), lon = in.readInt();
            int age = in.readUnsignedShort();
            int armed = in.readUnsignedByte();
            boolean flown = in.readUnsignedByte() != 0;
            int n = in.readUnsignedByte();
            String pilot = "";
            if (n == 4 || n == 16) {
                byte[] a = new byte[n];
                in.readFully(a);
                pilot = java.net.InetAddress.getByAddress(a).getHostAddress();
            }
            if (source == 0) return null;
            return new Position(source == 1, lat / 1e7, lon / 1e7, age, armed == 2 ? null : armed == 1, flown, pilot);
        } catch (IOException e) {
            return null;
        }
    }

    /** A NAME_ASSIGN's name, or null when it isn't one for this key. */
    public static String parseAssign(byte[] d, int len, String key) {
        if (len < 1 || d[0] != NAME_ASSIGN || !key.equals(keyOf(d, len))) return null;
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(d, 1, len - 1))) {
            in.readUTF();
            String name = in.readUTF();
            return isValidName(name, Kind.PHONE) ? name : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** "2 min ago", "yesterday", for the name map. */
    public static String agoText(long lastSeen, long now) {
        long s = Math.max(0, (now - lastSeen) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return s / 60 + " min ago";
        if (s < 86400) return s / 3600 + " h ago";
        return s / 86400 + " d ago";
    }
}
