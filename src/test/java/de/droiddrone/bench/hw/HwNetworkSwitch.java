package de.droiddrone.bench.hw;

import de.droiddrone.bench.PhoneNet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real phone's own network switched under the link ({@code HwNetworkSwitchScenario}): its cells, its table's row,
 * how a stream's coming and going is read from looks at it, and what the phone's log shows of Tailscale's path -
 * everything of it that needs no phone and no board, so the bench's own tests hold it before it meets them.
 */
final class HwNetworkSwitch {
    private HwNetworkSwitch() {
    }

    // ------------------------------------------------------------------ the cells

    /** Which network a cell begins on and should end on: Wi-Fi, mobile data, or the phone's normal one (phone.wifi). */
    enum Way {
        WIFI, MOBILE, AS_FOUND;

        PhoneNet.Net net(PhoneNet.Normal normal) {
            return this == WIFI ? PhoneNet.Net.WIFI : this == MOBILE ? PhoneNet.Net.MOBILE : normal.net();
        }
    }

    /** One order of a switch: so many ms after the switch began, this to the phone ({@link PhoneNet#ORDERS}). */
    record Order(long atMs, String command) {
    }

    /**
     * One cell, from a steady link.
     *
     * @param before what puts the phone on the network the cell begins from (after it is put back as found)
     * @param orders the switch itself
     */
    record Cell(String name, Way from, List<Order> before, List<Order> orders, Way to) {
        /** From the switch's first order to its last. */
        long holdMs() {
            return orders.get(orders.size() - 1).atMs();
        }

        /** It switches Wi-Fi or needs the phone on it: not for a phone whose Wi-Fi is normally off. */
        boolean needsWifi() {
            return from == Way.WIFI || to == Way.WIFI || switchesWifi(before) || switchesWifi(orders);
        }

        private static boolean switchesWifi(List<Order> orders) {
            return orders.stream().anyMatch(o -> o.command().startsWith("svc wifi"));
        }
    }

    /** The flap's Wi-Fi off to on: on again within two seconds of going off, adb's own time included. */
    static final long FLAP_MS = 1500, FLAP_MAX_MS = 2000;
    /** The two airplane mode cells. */
    static final long AIRPLANE_SHORT_MS = 10_000, AIRPLANE_LONG_MS = 60_000;

    static final Cell WIFI_OFF = new Cell("Wi-Fi off (to mobile data)", Way.WIFI, List.of(), List.of(new Order(0, PhoneNet.WIFI_OFF)), Way.MOBILE);
    static final Cell WIFI_ON = new Cell("Wi-Fi on (back to Wi-Fi)", Way.MOBILE, List.of(new Order(0, PhoneNet.WIFI_OFF)), List.of(new Order(0, PhoneNet.WIFI_ON)), Way.WIFI);
    static final Cell DATA_OFF = new Cell("mobile data off on Wi-Fi", Way.WIFI, List.of(), List.of(new Order(0, PhoneNet.DATA_OFF)), Way.WIFI);
    static final Cell FLAP = new Cell("Wi-Fi off and on within 2 s", Way.WIFI, List.of(), List.of(new Order(0, PhoneNet.WIFI_OFF), new Order(FLAP_MS, PhoneNet.WIFI_ON)), Way.WIFI);

    static Cell airplane(long ms) {
        return new Cell("airplane mode " + ms / 1000 + " s", Way.AS_FOUND, List.of(), List.of(new Order(0, PhoneNet.AIRPLANE_ON), new Order(ms, PhoneNet.AIRPLANE_OFF)), Way.AS_FOUND);
    }

    /**
     * Wi-Fi off (to mobile data) and on (back to Wi-Fi), mobile data off on Wi-Fi, airplane mode for 10 and for 60 s,
     * Wi-Fi off and on within 2 s. Quick: the first two and airplane mode 10 s.
     */
    static List<Cell> cells(boolean quick) {
        if (quick) return List.of(WIFI_OFF, WIFI_ON, airplane(AIRPLANE_SHORT_MS));
        return List.of(WIFI_OFF, WIFI_ON, DATA_OFF, airplane(AIRPLANE_SHORT_MS), airplane(AIRPLANE_LONG_MS), FLAP);
    }

    /** Why a cell is not run on this phone; null: it is. A network the phone does not normally have is never switched on. */
    static String skip(Cell c, PhoneNet.Normal normal) {
        if (!normal.wifi() && c.needsWifi()) {
            return "the phone's Wi-Fi is normally off (phone.wifi=off): the bench switches on no network the phone does not have";
        }
        return null;
    }

    /** The armed part's switch: the cell's, its hold no longer than the armed part's cut may be (the window is 10 s). */
    static List<Order> armedOrders(Cell c) {
        return c.orders().stream().map(o -> new Order(Math.min(o.atMs(), HwLinkMatrix.ARMED_CUT_MAX_MS), o.command())).toList();
    }

    /**
     * Whether a cell's armed part runs: null - it does; else why not. Only on the hardware file's word
     * (board.mayArm=true), never after the rig's guard has stopped the run's flying, and never where the keeper lets the
     * board's own failsafe run: how long a switch keeps the sticks from the board is not known beforehand.
     */
    static String armedSkip(boolean mayArm, String guardStopped, int stickLimitMs) {
        if (!mayArm) return "the hardware file does not say board.mayArm=true";
        if (guardStopped != null) return "the hardware guard has stopped the run's flying (" + guardStopped + ")";
        if (stickLimitMs == 0) {
            return "the keeper lets the board's own failsafe run (board.letFailsafeRun): how long a switch keeps the sticks from the board is not known "
                    + "beforehand, and a long one would outlast the " + HwLinkMatrix.WINDOW_S + " s window with the board armed - the keeper trips on that";
        }
        return null;
    }

    /** F-001: one new session when the link goes, one when it is back. */
    static final long SESSIONS_AT_MOST = 2;

    // ------------------------------------------------------------------ its times

    /** The link steady this long before a switch. */
    static final long STEADY_MS = 5000;
    /** After the switch's last order every stream is watched at least this long: a link that drops late is still seen. */
    static final long WATCH_MS = 10_000;
    /** How long the link, the picture, the sticks and the sound get to be back after the switch's last order. */
    static final long BACK_MS = 60_000;
    /** How long the phone gets to be on the network a cell begins from, and to be back as found. */
    static final long SETTLE_MS = 60_000;
    /** A stream counts as gone from this long without it: the sticks' rate (a tenth of a second under it is jitter), the sound's frames. */
    static final long STICKS_GONE_MS = 300, SOUND_GONE_MS = 600;
    /** What a cell costs beside its switch's hold: the phone onto its network, PILOT UAV to steady, the watch, STANDBY, the putting back. */
    static final long START_MS = 90_000, CELL_MS = 90_000, BEFORE_MS = 30_000, ARMED_MS = 60_000;

    /** About how long the run takes (for the film). */
    static long estimateMs(List<Cell> cells, boolean armed) {
        long ms = START_MS;
        for (Cell c : cells) {
            ms += CELL_MS + c.holdMs() + (c.before().isEmpty() ? 0 : BEFORE_MS);
            if (armed) ms += ARMED_MS + Math.min(c.holdMs(), HwLinkMatrix.ARMED_CUT_MAX_MS) + (c.before().isEmpty() && c.to() == c.from() ? 0 : BEFORE_MS);
        }
        return ms;
    }

    // ------------------------------------------------------------------ the table

    static final String TABLE = "hw-netswitch";
    static final List<String> COLUMNS = List.of("cell", "phone before", "ready ms", "switch ms", "link lost at ms", "link gone ms", "link back ms",
            "picture gone ms", "picture back ms", "sticks gone ms", "sticks back ms", "stick gap ms max", "sound gone ms", "sound back ms",
            "board RXLOSS at ms", "board RXLOSS for ms", "board FAILSAFE", "board armed", "phone during", "phone after", "tailscale before", "tailscale after",
            "phone no Tailscale", "phone new sessions", "phone socket anew", "phone errors", "armed part", "armed: stick gap ms", "armed: board said",
            "armed: ended by", "armed: re-arm", "phone put back", "verdict", "what");

    // ------------------------------------------------------------------ a stream's coming and going

    /**
     * One stream - the link, the picture, the sticks at the board, the phone's sound - looked at again and again around
     * a switch: when it first went, how long it stayed away at most, when it was last back. A gap shorter than its
     * {@code goneMs} is no going (jitter); one still open at the end is "not back".
     */
    static final class Outage {
        private final long goneMs;
        private long since = -1, firstGone = -1, lastBack = -1, longest;
        private int times;

        Outage(long goneMs) {
            this.goneMs = goneMs;
        }

        /** A look at time t: there or not. */
        void look(long t, boolean ok) {
            look(t, ok, t);
        }

        /** ... and when it is not, since when (the sound's last frame, the sticks' last window that had them). */
        void look(long t, boolean ok, long notSince) {
            if (!ok) {
                if (since < 0) since = Math.min(t, notSince);
                return;
            }
            if (since < 0) return;
            long d = t - since;
            if (d >= goneMs) {
                times++;
                if (firstGone < 0) firstGone = since;
                longest = Math.max(longest, d);
                lastBack = t;
            }
            since = -1;
        }

        /** Away now, for at least its goneMs. */
        boolean away(long t) {
            return since >= 0 && t - since >= goneMs;
        }

        /** Away now, for however short a time. */
        boolean missing() {
            return since >= 0;
        }

        /** What it did, the watch ending at t. */
        Away result(long t) {
            boolean open = away(t);
            long first = firstGone >= 0 ? firstGone : open ? since : -1;
            return new Away(first, Math.max(longest, open ? t - since : 0), open ? -1 : lastBack, open, times + (open ? 1 : 0));
        }
    }

    /**
     * @param firstGoneAt when it first went (ms of the clock), -1: never
     * @param longestMs   its longest time away (an open one to the watch's end)
     * @param backAt      when it was last back, -1: never gone, or not back
     * @param notBack     away still at the watch's end
     * @param times       how often it went
     */
    record Away(long firstGoneAt, long longestMs, long backAt, boolean notBack, int times) {
        boolean wentAway() {
            return firstGoneAt >= 0;
        }

        /** "0" never gone; its longest time away; "not back". */
        String goneCell() {
            return !wentAway() ? "0" : notBack ? "not back" : Long.toString(longestMs);
        }

        /** "-" never gone; ms from the switch's last order to its being back for good; "none": not back. */
        String backCell(long lastOrderAt) {
            return !wentAway() ? "-" : notBack ? "none" : Long.toString(backAt - lastOrderAt);
        }

        /** "the picture: away 2300 ms at most, back 900 ms after the switch's last order". */
        String words(String what, long lastOrderAt) {
            if (!wentAway()) return what + ": never away";
            if (notBack) return what + ": NOT BACK (away " + longestMs + " ms at the end of the watch)";
            return what + ": away " + longestMs + " ms at most" + (times > 1 ? " (" + times + " times)" : "") + ", back " + (backAt - lastOrderAt) + " ms after the switch's last order";
        }
    }

    // ------------------------------------------------------------------ Tailscale, as far as the phone shows it

    /** A line of the phone's log ({@code logcat -v time}) of Tailscale's own Go side - not the phone app's (DDLog, DD), whose words are its own. */
    private static final Pattern TAILSCALE = Pattern.compile("(?i)magicsock|derp|netmon|linkchange|wgengine|ipnlocal|netcheck|portmapper|tailscale");
    private static final Pattern APP_TAG = Pattern.compile("^\\S+ \\S+ [VDIWEF]/(DDLog|DD)\\b");
    private static final Pattern NOW_USING = Pattern.compile("(?i)now using (\\S+)");
    private static final Pattern HOME = Pattern.compile("(?i)home is now (derp-\\d+)(?: \\((\\w+)\\))?");
    private static final Pattern CHANGE = Pattern.compile("(?i)link[- ]?change|rebind|network changed|major change");

    static List<String> tailscaleLines(String log) {
        List<String> out = new ArrayList<>();
        for (String l : log.split("\\R")) if (TAILSCALE.matcher(l).find() && !APP_TAG.matcher(l).find()) out.add(l);
        return out;
    }

    /**
     * Tailscale's path as far as its lines in the phone's log show it, no address in it: the last "now using" (a
     * direct path, its time), its home relay, how many network changes it saw. "not shown" when it said none of it.
     */
    static String tailscalePath(List<String> lines) {
        String direct = null, home = null;
        int changes = 0;
        for (String l : lines) {
            Matcher m = NOW_USING.matcher(l);
            if (m.find()) {
                String via = m.group(1).toLowerCase(Locale.ROOT);
                direct = (via.startsWith("derp") ? "relayed (" + via.replaceAll("[^a-z0-9-]", "") + ")" : "direct") + " since " + time(l);
            }
            Matcher h = HOME.matcher(l);
            if (h.find()) home = h.group(1) + (h.group(2) == null ? "" : " (" + h.group(2) + ")");
            if (CHANGE.matcher(l).find()) changes++;
        }
        if (direct == null && home == null && changes == 0) return "not shown in the phone's log (" + lines.size() + " line(s) of Tailscale's)";
        List<String> w = new ArrayList<>();
        if (direct != null) w.add(direct);
        if (home != null) w.add("home relay " + home);
        if (changes > 0) w.add(changes + " network change(s) seen");
        return String.join("; ", w);
    }

    /** The time of a {@code logcat -v time} line ("11-23 16:03:21.123"), or "?". */
    static String time(String line) {
        Matcher m = Pattern.compile("^(\\d\\d-\\d\\d )?(\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?)").matcher(line.strip());
        return m.find() ? m.group(2) : "?";
    }

    private static final Pattern V4 = Pattern.compile("(?<![\\w.])\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?!\\w|\\.\\d)");
    private static final Pattern V6 = Pattern.compile("\\[?(?<![\\w:])[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7}(?![\\w:])\\]?(?::\\d{1,5})?");

    /** The line with every address in it made "<address>": what goes into a table names no phone, PC or network. */
    static String masked(String line) {
        String s = V4.matcher(line).replaceAll("<address>");
        Matcher m = V6.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String a = m.group();
            boolean address = a.contains("::") || a.chars().filter(ch -> ch == ':').count() >= 5;
            m.appendReplacement(out, Matcher.quoteReplacement(address ? "<address>" : a));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** That moment of the phone's clock is at or after this one: both as {@code PhoneRig.now} and {@code logcat -v time} write them ("11-23 16:03:21.123"). */
    static boolean atOrAfter(String line, String stamp) {
        String l = line.strip();
        int n = Math.min(stamp.length(), 18);
        if (l.length() < n) return false;
        return l.substring(0, n).compareTo(stamp.substring(0, n)) >= 0;
    }
}
