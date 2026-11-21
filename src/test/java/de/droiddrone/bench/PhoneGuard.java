package de.droiddrone.bench;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * What the bench may send to a REAL phone - the same model as the phones that fly, with the released app on it. The
 * emulator is the bench's own and gets everything; a real phone gets what is on this list and nothing else: a command
 * the list does not know is refused like a forbidden one, with a message that says why. So the list below is also the
 * answer to "what can a hardware run do to my phone".
 * <ul>
 * <li><b>Read</b>: getprop, pidof, ps, ss, date '+...', wm size / density, uiautomator dump, screencap -p, logcat (read
 * or followed), dumpsys of battery, deviceidle, package, activity, meminfo, window and the other services named in
 * {@link #DUMPS}, pm path / list, appops get, settings get, cat of /sys and /proc, the airplane switch asked.</li>
 * <li><b>A hand's work</b>: input tap / swipe / keyevent / text, wm dismiss-keyguard, am start of the run's own app.</li>
 * <li><b>Changes to the app</b> - pm clear / grant / revoke, appops set, dpm set-active-admin / remove-active-admin,
 * am force-stop / kill / crash, install, uninstall, a broadcast to the bench receiver: only on the bench build
 * ({@link BenchControl#PACKAGE}); on the released app ({@link PhoneRig#APP}) only when the run was started with
 * "the released app may be changed"; on any other app never.</li>
 * <li><b>Changes to the phone</b>, each put back when the rig closes: dumpsys battery unplug / reset, dumpsys
 * deviceidle force-idle / unforce, adb reverse, logcat -G, setprop debug.draupne.fc tcp:&lt;port&gt; | usb (the bench
 * build's way to its flight controller; gone with the phone's next restart); and the network switched off (airplane mode on, svc wifi
 * disable, svc data disable) - that only while adb comes over the cable: over the network it would cut the bench's
 * own way to the phone, with nobody there to switch it on again.</li>
 * <li><b>Never</b>: adb root / su, locksettings, install -d (a version down), kill and rm (root's; the bench receiver
 * does that work), a file written on the phone, the clock set, and everything not named above.</li>
 * </ul>
 */
final class PhoneGuard {
    private PhoneGuard() {
    }

    /** What a command does to the phone. */
    enum Kind {
        READ, HAND, APP, PHONE
    }

    /** What the rig has to put back when it closes. */
    enum Undo {
        AIRPLANE, WIFI, DATA
    }

    /**
     * @param refusal null when the command may go; else why not
     * @param sets    switched off by this command: on again at the rig's close
     * @param clears  switched on again by this command itself
     * @param pretends the command pretends about the battery or the phone's idleness
     */
    record Verdict(Kind kind, String refusal, Set<Undo> sets, Set<Undo> clears, boolean pretends) {
        boolean allowed() {
            return refusal == null;
        }

        static Verdict ok(Kind kind) {
            return new Verdict(kind, null, Set.of(), Set.of(), false);
        }

        static Verdict no(String why) {
            return new Verdict(Kind.PHONE, why, Set.of(), Set.of(), false);
        }
    }

    /** The services whose dumpsys is read as it is (none of them takes an order this way). */
    static final Set<String> DUMPS = Set.of("package", "activity", "meminfo", "window", "power", "alarm", "connectivity", "input", "display",
            "thermalservice", "device_policy", "usb", "cpuinfo", "telephony.registry", "wifi", "netstats");

    /** R3: the bench build's own property (BenchFc.PROPERTY) - how its flight controller is reached. */
    static final String FC_PROPERTY = "debug.draupne.fc";

    private static final String ROOTS ="that is root's work, and a real phone has no root: PhoneRig.stopForgettingRestarts / endProcess / socketStats go through the bench build's receiver";

    /**
     * @param args      what follows {@code adb -s <serial>}
     * @param packageOf reads the package an APK installs (null: not readable)
     */
    static Verdict judge(PhoneRig.Target t, List<String> args, Function<Path, String> packageOf) {
        if (t.kind() == PhoneRig.Kind.EMULATOR) return Verdict.ok(Kind.PHONE); // the bench's own: everything
        if (args.isEmpty()) return Verdict.no("an empty command");
        String first = args.get(0);
        List<String> rest = args.subList(1, args.size());
        switch (first) {
            case "shell", "exec-out":
                return shell(t, String.join(" ", rest));
            case "logcat":
                return logcat(args, true);
            case "get-state", "get-serialno", "wait-for-device":
                return Verdict.ok(Kind.READ);
            case "root", "unroot":
                return Verdict.no("adb " + first + " is never sent to a real phone");
            case "emu": {
                String what = rest.isEmpty() ? "" : rest.get(0);
                PhoneRig.Capability need = what.equals("redir") ? PhoneRig.Capability.UDP_REDIR : what.equals("geo") ? PhoneRig.Capability.EMU_GPS
                        : what.equals("power") ? PhoneRig.Capability.EMU_BATTERY : null;
                if (need != null && t.can(need)) return Verdict.ok(Kind.PHONE);
                return Verdict.no("an emulator's console command: a real phone has no made-up GPS, battery or forwarded port");
            }
            case "reverse":
                if (rest.equals(List.of("--list"))) return Verdict.ok(Kind.READ);
                if (rest.equals(List.of("--remove-all"))) return Verdict.ok(Kind.PHONE);
                if (rest.size() == 2 && rest.get(0).equals("--remove") && tcp(rest.get(1))) return Verdict.ok(Kind.PHONE);
                if (rest.size() == 2 && tcp(rest.get(0)) && tcp(rest.get(1))) return Verdict.ok(Kind.PHONE);
                return Verdict.no("adb reverse takes a TCP port of the phone to a TCP port of this PC, nothing else");
            case "install": {
                List<String> flags = new ArrayList<>(), files = new ArrayList<>();
                for (String a : rest) (a.startsWith("-") ? flags : files).add(a);
                if (flags.contains("-d")) return Verdict.no("install -d takes an app a version down: never on a real phone");
                for (String f : flags) if (!f.equals("-r") && !f.equals("-t")) return Verdict.no("install " + f + " is not on the bench's list (-r and -t are)");
                if (files.size() != 1) return Verdict.no("install takes one APK");
                String app;
                try {
                    app = packageOf.apply(Path.of(files.get(0)));
                } catch (RuntimeException e) {
                    app = null;
                }
                if (app == null) return Verdict.no("the package this APK installs could not be read, so it is not known which app it would change");
                return app(t, app, "install");
            }
            case "uninstall": {
                String app = lastPlain(rest);
                return app == null ? Verdict.no("uninstall without a package") : app(t, app, "uninstall");
            }
            default:
                return Verdict.no("adb " + first + " is not on the list of what the bench sends to a real phone (PhoneGuard)");
        }
    }

    private static boolean tcp(String s) {
        return s.matches("tcp:\\d{1,5}");
    }

    /** The one app a command names: changed only if it is the bench build, or the released app with the run's word for it. */
    private static Verdict app(PhoneRig.Target t, String app, String what) {
        if (app.equals(BenchControl.PACKAGE)) return Verdict.ok(Kind.APP);
        if (app.equals(PhoneRig.APP)) {
            if (t.releasedAppMayBeChanged()) return Verdict.ok(Kind.APP);
            return Verdict.no(what + " would change the released app (" + PhoneRig.APP + "), and this run was not started with \"the released app may be changed\" "
                    + "(-PreleasedAppMayBeChanged=true): only the bench build (" + BenchControl.PACKAGE + ") is changed");
        }
        return Verdict.no(what + " on " + app + ": the bench changes DRAUPNE Flight and no other app");
    }

    // ------------------------------------------------------------------ a shell line

    private static Verdict shell(PhoneRig.Target t, String line) {
        List<List<String>> commands = new ArrayList<>();
        String problem = split(line, commands);
        if (problem != null) return Verdict.no(problem);
        if (commands.isEmpty()) return Verdict.no("an open shell: the bench sends commands that end");
        Kind kind = Kind.READ;
        Set<Undo> sets = EnumSet.noneOf(Undo.class), clears = EnumSet.noneOf(Undo.class);
        boolean pretends = false;
        for (List<String> words : commands) {
            Verdict v = simple(t, words);
            if (!v.allowed()) return v;
            if (v.kind().ordinal() > kind.ordinal()) kind = v.kind();
            sets.addAll(v.sets());
            clears.addAll(v.clears());
            pretends |= v.pretends();
        }
        return new Verdict(kind, null, sets, clears, pretends);
    }

    /**
     * A shell line as its simple commands, each as its words: cut at ; | & and line ends outside quotes. Returns why
     * the line cannot be judged (a $ or a backquote - what it would run is not written in it; a file written or read
     * by a redirection), null when it can.
     */
    static String split(String line, List<List<String>> out) {
        List<String> words = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        int n = line.length();
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (quote == '"' && (c == '$' || c == '`')) return "a $ or a backquote: what the phone would run is not written in the command";
                else w.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '$' || c == '`') {
                return "a $ or a backquote: what the phone would run is not written in the command";
            } else if (c == '\\') {
                if (i + 1 < n) w.append(line.charAt(++i));
                inWord = true;
            } else if (c == '<') {
                return "a file read into a command (<)";
            } else if (c == '>') {
                // the redirection's number is no word of the command
                if (inWord && w.length() > 0 && w.chars().allMatch(Character::isDigit)) {
                    w.setLength(0);
                    inWord = false;
                }
                if (i + 1 < n && line.charAt(i + 1) == '>') i++;
                int from = i + 1;
                while (from < n && (line.charAt(from) == ' ' || line.charAt(from) == '\t')) from++;
                int to = from;
                while (to < n && !Character.isWhitespace(line.charAt(to)) && ";|".indexOf(line.charAt(to)) < 0 && !(line.charAt(to) == '&' && to > from)) to++;
                String target = line.substring(from, to);
                if (!target.equals("&1") && !target.equals("&2") && !target.equals("/dev/null")) return "a file written on the phone (> " + target + ")";
                i = to - 1;
            } else if (Character.isWhitespace(c) && c != '\n') {
                if (inWord) words.add(w.toString());
                w.setLength(0);
                inWord = false;
            } else if (c == ';' || c == '|' || c == '&' || c == '\n') {
                if (inWord) words.add(w.toString());
                w.setLength(0);
                inWord = false;
                if (!words.isEmpty()) out.add(List.copyOf(words));
                words.clear();
                if (i + 1 < n && line.charAt(i + 1) == c && c != ';' && c != '\n') i++; // && and ||
            } else {
                w.append(c);
                inWord = true;
            }
        }
        if (quote != 0) return "a quote that is not closed";
        if (inWord) words.add(w.toString());
        if (!words.isEmpty()) out.add(List.copyOf(words));
        return null;
    }

    private static Verdict unknown(List<String> w) {
        return Verdict.no("\"" + String.join(" ", w.subList(0, Math.min(2, w.size()))) + "\" is not on the list of what the bench sends to a real phone (PhoneGuard): "
                + "put it there with its kind if a scenario needs it");
    }

    /** The last word that is no option: the package most commands end with. */
    private static String lastPlain(List<String> words) {
        for (int i = words.size() - 1; i >= 0; i--) if (!words.get(i).startsWith("-")) return words.get(i);
        return null;
    }

    /** The first word that is no option and no option's value ({@code --user 0}). */
    private static String firstPlain(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            String s = words.get(i);
            if (s.equals("--user")) {
                i++;
                continue;
            }
            if (!s.startsWith("-")) return s;
        }
        return null;
    }

    private static Verdict simple(PhoneRig.Target t, List<String> w) {
        String c = w.get(0);
        List<String> a = w.subList(1, w.size());
        switch (c) {
            case "su":
                return Verdict.no("su is never sent to a real phone");
            case "locksettings":
                return Verdict.no("locksettings changes the phone's own lock screen: never on a real phone");
            case "getprop":
                return a.size() <= 1 ? Verdict.ok(Kind.READ) : unknown(w);
            // >>> R3, the flight controller keeper: the one property the bench sets - which way the bench build reaches its
            // flight controller (flight-app/bench-overlay: tcp:<port> down the cable to the keeper, or usb). It is gone
            // with the phone's next restart, and the released app never reads it.
            case "setprop":
                if (a.size() == 2 && a.get(0).equals(FC_PROPERTY) && (a.get(1).equals("usb") || tcp(a.get(1)))) return Verdict.ok(Kind.PHONE);
                return Verdict.no("setprop " + String.join(" ", a) + ": the bench sets one property on a real phone, " + FC_PROPERTY + " to tcp:<port> or usb");
            // <<< R3
            case "pidof", "ps", "ss", "grep", "head", "tail", "wc", "sort", "uniq", "uptime", "id", "echo", "true":
                return Verdict.ok(Kind.READ);
            case "cat":
                for (String f : a) if (!f.startsWith("/sys/") && !f.startsWith("/proc/")) return Verdict.no("cat of " + f + ": the bench reads /sys and /proc only");
                return Verdict.ok(Kind.READ);
            case "date":
                for (String f : a) if (!f.startsWith("+")) return Verdict.no("date " + f + " could set the phone's clock: the bench only asks the time (date '+...')");
                return Verdict.ok(Kind.READ);
            case "uiautomator":
                return a.size() >= 1 && a.get(0).equals("dump") && (a.size() == 1 || a.get(1).equals("/dev/tty")) ? Verdict.ok(Kind.READ)
                        : Verdict.no("uiautomator: the bench only reads the screen (dump /dev/tty)");
            case "screencap":
                for (String f : a) if (!f.startsWith("-")) return Verdict.no("screencap into a file on the phone: the bench takes the picture over adb (screencap -p)");
                return Verdict.ok(Kind.READ);
            case "logcat":
                return logcat(w, false);
            case "dumpsys":
                return dumpsys(a, w);
            case "wm":
                if (a.size() == 1 && (a.get(0).equals("size") || a.get(0).equals("density"))) return Verdict.ok(Kind.READ);
                if (a.equals(List.of("dismiss-keyguard"))) return Verdict.ok(Kind.HAND);
                return Verdict.no("wm " + String.join(" ", a) + " would change the phone's screen: the bench only asks its size");
            case "input":
                return !a.isEmpty() && Set.of("tap", "swipe", "keyevent", "text").contains(a.get(0)) ? Verdict.ok(Kind.HAND) : unknown(w);
            case "am":
                return am(t, a, w);
            case "pm":
                return pm(t, a, w);
            case "appops":
                return appops(t, a, w);
            case "dpm": {
                if (a.isEmpty() || !(a.get(0).equals("set-active-admin") || a.get(0).equals("remove-active-admin"))) {
                    return Verdict.no("dpm " + (a.isEmpty() ? "" : a.get(0)) + ": the bench only lets the app lock the screen (set-active-admin) and takes that back");
                }
                String component = firstPlain(a.subList(1, a.size()));
                if (component == null || !component.contains("/")) return Verdict.no("dpm " + a.get(0) + " without the app's receiver");
                return app(t, component.substring(0, component.indexOf('/')), "dpm " + a.get(0));
            }
            case "cmd": {
                if (a.isEmpty()) return unknown(w);
                List<String> more = a.subList(1, a.size());
                switch (a.get(0)) {
                    case "connectivity":
                        if (more.equals(List.of("airplane-mode"))) return Verdict.ok(Kind.READ);
                        if (more.equals(List.of("airplane-mode", "disable"))) return new Verdict(Kind.PHONE, null, Set.of(), Set.of(Undo.AIRPLANE), false);
                        if (more.equals(List.of("airplane-mode", "enable"))) return networkOff(t, "airplane mode", Undo.AIRPLANE);
                        return unknown(w);
                    case "battery", "deviceidle":
                        return dumpsys(a, w);
                    case "package":
                        return pm(t, more, w);
                    case "activity":
                        return am(t, more, w);
                    case "appops":
                        return appops(t, more, w);
                    default:
                        return unknown(w);
                }
            }
            case "svc": {
                if (a.size() == 2 && (a.get(0).equals("wifi") || a.get(0).equals("data"))) {
                    Undo u = a.get(0).equals("wifi") ? Undo.WIFI : Undo.DATA;
                    if (a.get(1).equals("enable")) return new Verdict(Kind.PHONE, null, Set.of(), Set.of(u), false);
                    if (a.get(1).equals("disable")) return networkOff(t, a.get(0).equals("wifi") ? "Wi-Fi off" : "mobile data off", u);
                }
                return Verdict.no("svc " + String.join(" ", a) + ": the bench switches Wi-Fi and mobile data, nothing else of svc");
            }
            case "settings":
                return !a.isEmpty() && a.get(0).equals("get") ? Verdict.ok(Kind.READ) : Verdict.no("settings " + (a.isEmpty() ? "" : a.get(0)) + ": the bench only reads the phone's settings");
            case "kill", "killall", "pkill":
                return Verdict.no(c + ": " + ROOTS);
            case "rm", "mv", "cp", "dd", "chmod", "chown", "touch", "mkdir", "ln":
                return Verdict.no(c + ": the bench writes and deletes nothing among a real phone's files (the crash guard's file: " + ROOTS + ")");
            default:
                return unknown(w);
        }
    }

    /** The network switched off: only while adb itself does not come over it. */
    private static Verdict networkOff(PhoneRig.Target t, String what, Undo undo) {
        if (t.overTcp()) {
            return Verdict.no(what + " while adb itself comes over the network (" + t.serial() + "): it would cut the bench's own way to the phone, "
                    + "with nobody there to switch it on again. Over the USB cable it is allowed");
        }
        return new Verdict(Kind.PHONE, null, Set.of(undo), Set.of(), false);
    }

    private static Verdict logcat(List<String> w, boolean adbLevel) {
        Kind kind = Kind.READ;
        boolean ends = adbLevel; // adb's own logcat may go on: the rig's tap
        for (int i = 1; i < w.size(); i++) {
            String f = w.get(i);
            switch (f) {
                case "-d":
                    ends = true;
                    break;
                case "-v", "-T", "-t", "-b", "--pid":
                    i++;
                    break;
                case "-s":
                    break;
                case "-G":
                    kind = Kind.PHONE; // the log's size, until the phone restarts
                    i++;
                    break;
                case "-c", "--clear":
                    return Verdict.no("logcat -c would empty the log that says what ended the app");
                default:
                    if (f.startsWith("--pid=")) break;
                    if (f.startsWith("-")) return Verdict.no("logcat " + f + " is not on the bench's list (-d -v -T -t -b -s --pid -G are)");
                    // a filter: TAG:LEVEL
            }
        }
        if (!ends && kind == Kind.READ) return Verdict.no("logcat without -d never ends in a shell: the rig's own tap follows the log");
        return Verdict.ok(kind);
    }

    private static Verdict dumpsys(List<String> a, List<String> w) {
        if (a.isEmpty()) return Verdict.ok(Kind.READ);
        String service = a.get(0);
        List<String> more = a.subList(1, a.size());
        if (service.equals("battery")) {
            if (more.isEmpty() || more.get(0).equals("get")) return Verdict.ok(Kind.READ);
            if (more.equals(List.of("unplug"))) return new Verdict(Kind.PHONE, null, Set.of(), Set.of(), true);
            if (more.equals(List.of("reset"))) return Verdict.ok(Kind.PHONE);
            return Verdict.no("battery " + String.join(" ", more) + ": the bench pretends one thing about the battery - unplug - and takes it back with reset");
        }
        if (service.equals("deviceidle")) {
            if (more.isEmpty() || more.get(0).equals("get")) return Verdict.ok(Kind.READ);
            if (more.get(0).equals("force-idle") && more.size() <= 2) return new Verdict(Kind.PHONE, null, Set.of(), Set.of(), true);
            if (more.equals(List.of("unforce"))) return Verdict.ok(Kind.PHONE);
            return Verdict.no("deviceidle " + String.join(" ", more) + ": the bench forces the phone idle (force-idle) and takes it back (unforce), nothing else");
        }
        if (!DUMPS.contains(service)) return unknown(w);
        for (String m : more) {
            if (m.equals("write") || m.startsWith("--reset") || m.startsWith("--clear") || m.startsWith("--write") || m.startsWith("--enable") || m.startsWith("--disable")) {
                return Verdict.no("dumpsys " + service + " " + m + " is an order, not a look");
            }
        }
        return Verdict.ok(Kind.READ);
    }

    private static Verdict am(PhoneRig.Target t, List<String> a, List<String> w) {
        if (a.isEmpty()) return unknown(w);
        List<String> more = a.subList(1, a.size());
        switch (a.get(0)) {
            case "start": {
                int n = more.indexOf("-n");
                String component = n >= 0 && n + 1 < more.size() ? more.get(n + 1) : null;
                if (component == null || !component.startsWith(t.app() + "/")) return Verdict.no("am start opens the run's own app (" + t.app() + ") and nothing else");
                if (more.contains("-S")) return app(t, t.app(), "am start -S (it closes the app first)");
                return Verdict.ok(Kind.HAND);
            }
            case "force-stop", "kill", "crash": {
                String app = firstPlain(more);
                if (app == null || app.chars().allMatch(Character::isDigit)) return Verdict.no("am " + a.get(0) + " by the app's package, not by a number");
                return app(t, app, "am " + a.get(0));
            }
            case "broadcast": {
                int n = more.indexOf("-n"), act = more.indexOf("-a");
                String component = n >= 0 && n + 1 < more.size() ? more.get(n + 1) : null;
                String action = act >= 0 && act + 1 < more.size() ? more.get(act + 1) : null;
                if (!BenchControl.component(BenchControl.PACKAGE).equals(component) || action == null || !BenchControl.ACTIONS.contains(action)) {
                    return Verdict.no("am broadcast: the bench speaks to the bench build's own receiver (" + BenchControl.component(BenchControl.PACKAGE)
                            + ") with its own actions and to nobody else");
                }
                return Verdict.ok(Kind.APP);
            }
            default:
                return unknown(w);
        }
    }

    private static Verdict pm(PhoneRig.Target t, List<String> a, List<String> w) {
        if (a.isEmpty()) return unknown(w);
        List<String> more = a.subList(1, a.size());
        switch (a.get(0)) {
            case "path", "list":
                return Verdict.ok(Kind.READ);
            case "clear", "grant", "revoke", "uninstall": {
                String app = firstPlain(more);
                return app == null ? Verdict.no("pm " + a.get(0) + " without a package") : app(t, app, "pm " + a.get(0));
            }
            case "install":
                if (more.contains("-d")) return Verdict.no("install -d takes an app a version down: never on a real phone");
                return Verdict.no("pm install: an app goes onto a real phone through PhoneRig.install only - it reads the APK's package first");
            default:
                return unknown(w);
        }
    }

    private static Verdict appops(PhoneRig.Target t, List<String> a, List<String> w) {
        if (a.isEmpty()) return unknown(w);
        List<String> more = a.subList(1, a.size());
        switch (a.get(0)) {
            case "get":
                return Verdict.ok(Kind.READ);
            case "set", "reset": {
                String app = firstPlain(more);
                return app == null ? Verdict.no("appops " + a.get(0) + " without a package") : app(t, app, "appops " + a.get(0));
            }
            default:
                return unknown(w);
        }
    }
}
