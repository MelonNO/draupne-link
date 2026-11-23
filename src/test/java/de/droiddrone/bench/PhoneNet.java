package de.droiddrone.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A real phone's own network, as adb shows it and as the bench switches it (the network-switch scenario,
 * {@code hw/HwNetworkSwitchScenario}): its three switches - airplane mode, Wi-Fi, mobile data - and the network
 * Android has made its default (Wi-Fi or mobile data, validated or not), with Tailscale's VPN beside it.
 * <p>
 * <b>The phone as found.</b> A phone that flies has airplane mode off and mobile data on, and its Wi-Fi as the hardware
 * file says ({@code phone.wifi}; left out: on). A scenario that switches its network begins only on a phone found so
 * ({@link Normal#wrong}), notes on the phone itself what it is to be put back to ({@link #NOTE}: found after a run that
 * was killed, from any PC, by {@link PhoneReset}), and puts it back whatever happens ({@link #putBack}) - only by the
 * three switches the bench itself has on its list ({@link PhoneGuard}), and switching off only while adb comes over the
 * cable. Nothing here changes a setting of the phone's own beside them.
 */
public final class PhoneNet {
    private PhoneNet() {
    }

    // ------------------------------------------------------------------ what is sent

    public static final String AIRPLANE = "cmd connectivity airplane-mode", AIRPLANE_ON = AIRPLANE + " enable", AIRPLANE_OFF = AIRPLANE + " disable";
    public static final String WIFI_ON = "svc wifi enable", WIFI_OFF = "svc wifi disable";
    public static final String DATA_ON = "svc data enable", DATA_OFF = "svc data disable";
    /** Android's own records of the two switches (read only): Wi-Fi 0 off, 1 on, 2 on in airplane mode, 3 off by airplane mode; mobile data 0 or 1. */
    public static final String WIFI_SETTING = "settings get global wifi_on", DATA_SETTING = "settings get global mobile_data";
    /** Which networks the phone has and which is its default (read only). */
    public static final String NETWORKS = "dumpsys connectivity";
    /**
     * The bench's note on the phone of what its network is to be put back to, written before the first switch and gone
     * once it is back: "wifi-on" or "wifi-off" (airplane mode off and mobile data on go without saying). Kept in the
     * phone's own settings table, like {@link PhoneGuard#STAY_ON_NOTE}, so that it outlives a killed run.
     */
    public static final String NOTE = "draupne_bench_net_was";
    public static final String NOTE_READ = "settings get global " + NOTE, NOTE_DELETE = "settings delete global " + NOTE;

    /** Every order a network switch may send, the putting back's among them. */
    public static final List<String> ORDERS = List.of(AIRPLANE_ON, AIRPLANE_OFF, WIFI_OFF, WIFI_ON, DATA_OFF, DATA_ON);
    /** Every read of the network. */
    public static final List<String> READS = List.of(AIRPLANE, WIFI_SETTING, DATA_SETTING, NETWORKS, NOTE_READ);

    /** The note's order: what the phone is to be put back to. */
    public static String noteWrite(boolean wifi) {
        return "settings put global " + NOTE + " " + noteValue(wifi);
    }

    static String noteValue(boolean wifi) {
        return wifi ? "wifi-on" : "wifi-off";
    }

    /** The phone's whole log since that moment of its clock (PhoneRig.now): Tailscale's own lines are in it, under whatever name it logs. */
    public static String logSince(String stamp) {
        return "logcat -d -v time -T '" + stamp + "'";
    }

    // ------------------------------------------------------------------ what the phone is on

    /** The network Android has made the phone's default. */
    public enum Net {
        WIFI("Wi-Fi"), MOBILE("mobile data"), NONE("no network"), NOT_KNOWN("not known");

        public final String words;

        Net(String words) {
            this.words = words;
        }
    }

    /**
     * The phone's network at one moment.
     *
     * @param airplane  airplane mode on; null: not readable
     * @param wifi      Wi-Fi switched on; null: not readable, or its setting says off while a Wi-Fi network is up
     * @param data      mobile data switched on; null: not readable (a phone that keeps it per SIM only), or its setting
     *                  says off while a mobile network is up
     * @param on        the default network
     * @param validated Android has found the internet on it
     * @param vpn       a VPN is up (Tailscale's, when {@link #tailscale})
     * @param tailscale that VPN is Tailscale's own
     * @param networks  each network the phone has, in its words ("Wi-Fi validated", "mobile data", "VPN Tailscale")
     */
    public record State(Boolean airplane, Boolean wifi, Boolean data, Net on, boolean validated, boolean vpn, boolean tailscale, List<String> networks) {
        /** In a few words, for a film and a table: "on Wi-Fi (validated); Wi-Fi on, mobile data on, airplane mode off; Tailscale up". */
        public String words() {
            return "on " + on.words + (on == Net.WIFI || on == Net.MOBILE ? validated ? " (validated)" : " (NOT validated)" : "") + "; Wi-Fi " + onOff(wifi) + ", mobile data "
                    + onOff(data) + ", airplane mode " + onOff(airplane) + "; " + (tailscale ? "Tailscale up" : vpn ? "a VPN up (not Tailscale's)" : "no Tailscale")
                    + (networks.isEmpty() ? "" : " [" + String.join(", ", networks) + "]");
        }

        private static String onOff(Boolean b) {
            return b == null ? "not known" : b ? "on" : "off";
        }
    }

    /** Read through this shell (a real phone's: {@link PhoneRig#network}); a read that fails is "not known". */
    public static State read(UnaryOperator<String> shell) {
        return parse(said(shell, AIRPLANE), said(shell, WIFI_SETTING), said(shell, DATA_SETTING), said(shell, NETWORKS));
    }

    private static String said(UnaryOperator<String> shell, String command) {
        try {
            String s = shell.apply(command);
            return s == null ? "" : s;
        } catch (RuntimeException e) { // the phone gone for a moment: not known (a refusal is no RuntimeException and goes on up)
            return "";
        }
    }

    /** From what the four reads printed. */
    public static State parse(String airplane, String wifiOn, String mobileData, String dumpsys) {
        String a = airplane == null ? "" : airplane.trim().toLowerCase(java.util.Locale.ROOT);
        Boolean air = a.contains("enabled") ? Boolean.TRUE : a.contains("disabled") ? Boolean.FALSE : null;
        String w = wifiOn == null ? "" : wifiOn.trim();
        Boolean wifi = w.equals("1") || w.equals("2") ? Boolean.TRUE : w.equals("0") || w.equals("3") ? Boolean.FALSE : null;
        String d = mobileData == null ? "" : mobileData.trim();
        Boolean data = d.equals("1") ? Boolean.TRUE : d.equals("0") ? Boolean.FALSE : null;
        Networks n = networks(dumpsys == null ? "" : dumpsys);
        // a setting that says off while that network is up and carries data is not the phone's whole word (a phone with
        // two SIMs keeps mobile data per SIM; a switch just sent): not known - never taken for off
        if (Boolean.FALSE.equals(wifi) && n.wifiUp) wifi = null;
        if (Boolean.FALSE.equals(data) && n.mobileUp) data = null;
        return new State(air, wifi, data, n.on, n.validated, n.vpn, n.tailscale, n.words);
    }

    private record Networks(Net on, boolean validated, boolean vpn, boolean tailscale, boolean wifiUp, boolean mobileUp, List<String> words) {
    }

    /** One network of the dump: its number, its transports, whether it is validated, whose VPN it is. */
    private record Agent(int id, String transports, boolean validated, boolean tailscale) {
        String words() {
            String t = transports.contains("VPN") ? "VPN" + (tailscale ? " Tailscale" : "") : transports.contains("WIFI") ? "Wi-Fi" : transports.contains("CELLULAR") ? "mobile data"
                    : transports.isEmpty() ? "a network" : transports.toLowerCase(java.util.Locale.ROOT);
            return t + (validated && !transports.contains("VPN") ? " validated" : "");
        }
    }

    private static final Pattern AGENT = Pattern.compile("NetworkAgentInfo\\s*(?:\\{\\s*network\\{(\\d+)}|\\[[^\\]]*?-\\s*(\\d+)\\s*])");
    private static final Pattern DEFAULT = Pattern.compile("Active default network:\\s*(\\d+)");

    /**
     * {@code dumpsys connectivity}, the networks part: each NetworkAgentInfo of "Current Networks" (Android 12 on:
     * {@code NetworkAgentInfo{network{101} ...}}; before: {@code NetworkAgentInfo [WIFI () - 101] ...}) with its
     * transports and capabilities, and "Active default network". The default network is never the VPN (Android's
     * default request wants NOT_VPN); a dump without that line takes Wi-Fi before mobile data, as Android does.
     */
    static Networks networks(String dump) {
        int from = dump.indexOf("Current Networks:");
        String part = from >= 0 ? dump.substring(from) : dump;
        if (from >= 0) {
            // to the next heading at the start of a line
            Matcher h = Pattern.compile("\\n(?=\\S)").matcher(part);
            if (h.find(1)) part = part.substring(0, h.start());
        }
        Map<Integer, Agent> agents = new LinkedHashMap<>();
        Matcher m = AGENT.matcher(part);
        List<int[]> at = new ArrayList<>();
        while (m.find()) at.add(new int[]{m.start(), Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2))});
        for (int i = 0; i < at.size(); i++) {
            String block = part.substring(at.get(i)[0], i + 1 < at.size() ? at.get(i + 1)[0] : part.length());
            Matcher t = Pattern.compile("Transports:\\s*([A-Z_|]+)").matcher(block);
            String transports = t.find() ? t.group(1) : block.contains("ni{WIFI") || block.contains("[WIFI") ? "WIFI"
                    : block.contains("ni{MOBILE") || block.contains("[MOBILE") ? "CELLULAR" : block.contains("ni{VPN") || block.contains("[VPN") ? "VPN" : "";
            Matcher caps = Pattern.compile("Capabilities:\\s*([A-Z_&]+)").matcher(block);
            boolean validated = caps.find() && List.of(caps.group(1).split("&")).contains("VALIDATED");
            boolean tailscale = transports.contains("VPN") && block.contains("com.tailscale");
            agents.putIfAbsent(at.get(i)[1], new Agent(at.get(i)[1], transports, validated, tailscale));
        }
        List<String> words = agents.values().stream().map(Agent::words).toList();
        boolean vpn = agents.values().stream().anyMatch(a -> a.transports().contains("VPN"));
        boolean tailscale = agents.values().stream().anyMatch(Agent::tailscale);
        Matcher d = DEFAULT.matcher(dump);
        Agent chosen = d.find() ? agents.get(Integer.parseInt(d.group(1))) : null;
        if (chosen == null || chosen.transports().contains("VPN")) { // (a VPN's transports name its underlying network's too: WIFI|VPN)
            chosen = agents.values().stream().filter(a -> a.transports().contains("WIFI") && !a.transports().contains("VPN")).findFirst()
                    .orElse(agents.values().stream().filter(a -> a.transports().contains("CELLULAR") && !a.transports().contains("VPN")).findFirst().orElse(null));
        }
        Net on;
        if (chosen == null) on = dump.isBlank() ? Net.NOT_KNOWN : Net.NONE;
        else on = chosen.transports().contains("WIFI") ? Net.WIFI : chosen.transports().contains("CELLULAR") ? Net.MOBILE : Net.NOT_KNOWN;
        boolean wifiUp = agents.values().stream().anyMatch(a -> a.transports().contains("WIFI") && !a.transports().contains("VPN"));
        boolean mobileUp = agents.values().stream().anyMatch(a -> a.transports().contains("CELLULAR") && !a.transports().contains("VPN"));
        return new Networks(on, chosen != null && chosen.validated(), vpn, tailscale, wifiUp, mobileUp, words);
    }

    // ------------------------------------------------------------------ the phone as found, and back to it

    /** A phone as it normally is: airplane mode off, mobile data on, Wi-Fi as {@code wifi} says. */
    public static Normal normal(boolean wifi) {
        return new Normal(wifi);
    }

    public record Normal(boolean wifi) {
        /** The network it is on once settled: Wi-Fi if its Wi-Fi is normally on, else mobile data. */
        public Net net() {
            return wifi ? Net.WIFI : Net.MOBILE;
        }

        /** What of its switches is not as normal, in words; null: nothing (a switch that cannot be read is not counted here). */
        public String wrong(State s) {
            List<String> w = new ArrayList<>();
            if (Boolean.TRUE.equals(s.airplane())) w.add("airplane mode is on");
            if (s.wifi() != null && s.wifi() != wifi) w.add("Wi-Fi is " + (s.wifi() ? "on" : "off") + " (normally " + (wifi ? "on" : "off") + ")");
            if (Boolean.FALSE.equals(s.data())) w.add("mobile data is off");
            return w.isEmpty() ? null : String.join(", ", w);
        }

        /** Its switches as normal and the phone on its normal network, validated: a switch may begin from here. */
        public boolean settled(State s) {
            return wrong(s) == null && s.on() == net() && s.validated();
        }

        /**
         * The orders that put back what is read as not normal. Airplane mode first and alone: while it is on, Wi-Fi is
         * airplane mode's (Android switches it off and on with it), so the rest is read again after it.
         */
        public List<String> orders(State s) {
            if (Boolean.TRUE.equals(s.airplane())) return List.of(AIRPLANE_OFF);
            List<String> o = new ArrayList<>();
            if (s.wifi() != null && s.wifi() != wifi) o.add(wifi ? WIFI_ON : WIFI_OFF);
            if (Boolean.FALSE.equals(s.data())) o.add(DATA_ON);
            return o;
        }

        /** The orders for what cannot be read: sent once, to be sure (each one does nothing on a phone that is so already). */
        public List<String> blind(State s) {
            List<String> o = new ArrayList<>();
            if (s.airplane() == null) o.add(AIRPLANE_OFF);
            if (s.wifi() == null && wifi) o.add(WIFI_ON); // a Wi-Fi normally off and not readable is not switched off on a guess
            if (s.data() == null) o.add(DATA_ON);
            return o;
        }

        public String noteValue() {
            return PhoneNet.noteValue(wifi);
        }

        /** "Wi-Fi on, mobile data on, airplane mode off". */
        public String words() {
            return "Wi-Fi " + (wifi ? "on" : "off") + ", mobile data on, airplane mode off";
        }

        /** The normal a note on the phone says; null when it says none. */
        public static Normal ofNote(String noted) {
            String n = noted == null ? "" : noted.trim();
            return n.equals("wifi-on") ? new Normal(true) : n.equals("wifi-off") ? new Normal(false) : null;
        }
    }

    /**
     * How a putting back ended.
     *
     * @param state    the phone as last read
     * @param sent     the orders sent, in their order
     * @param switches every switch is back as normal (or cannot be read, and was sent its order once)
     * @param settled  ... and the phone is on its normal network again, validated
     */
    public record Back(State state, List<String> sent, boolean switches, boolean settled) {
        public String words() {
            return (settled ? "back as found" : switches ? "its switches back as found, but it is not on its network yet" : "NOT back as found")
                    + " (" + (sent.isEmpty() ? "nothing had to be sent" : "sent: " + String.join("; ", sent)) + "): " + state.words();
        }
    }

    /**
     * The phone put back as normal: read, the orders for what is not so sent, read again every two seconds until its
     * switches are back and it is on its normal network, validated - or until maxMs. The note on the phone is not
     * touched here: the caller removes it once this says the switches are back.
     */
    public static Back putBack(UnaryOperator<String> shell, Normal normal, LongConsumer pause, LongSupplier clock, long maxMs, Consumer<String> say) {
        long end = clock.getAsLong() + maxMs;
        List<String> sent = new ArrayList<>();
        while (true) {
            State s = read(shell);
            List<String> orders = new ArrayList<>(normal.orders(s));
            for (String b : normal.blind(s)) if (!sent.contains(b) && !orders.contains(b)) orders.add(b);
            boolean due = clock.getAsLong() >= end;
            if (orders.isEmpty() && (normal.settled(s) || due)) return new Back(s, List.copyOf(sent), true, normal.settled(s));
            if (due) return new Back(s, List.copyOf(sent), false, false);
            for (String o : orders) {
                if (say != null) say.accept("The phone's network put back: " + o + " (" + s.words() + ")");
                shell.apply(o);
                sent.add(o);
            }
            pause.accept(2000);
        }
    }
}
