package de.droiddrone.bench.phone;

import de.droiddrone.bench.BadLink;
import de.droiddrone.bench.Film;
import de.droiddrone.bench.Hardware;
import de.droiddrone.bench.LinkBench;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.PhoneWords;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import de.droiddrone.bench.Watch;
import de.droiddrone.common.KnownPcs;
import de.droiddrone.common.TailscaleOnly;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.FcTelemetry;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.Pacer;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.BenchFleetAccess;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The phone app's hardening, checked on the phone itself - by hand until now (the security review's bench tests). Each
 * check is a test of its own with a film of its own, and each reads; none changes the phone's settings or its lock.
 * <ul>
 * <li><b>What the phone listens on</b> ({@link #whatThePhoneListensOn}): the kernel's own socket tables, read for the
 * app's uid. The released rule (Tailscale only, Q-006): every socket of the app that takes packets from anyone is on
 * the phone's Tailscale address - on a VPN's interface - or parked on its own loopback; none on every address, none on
 * another address of the phone. On a real phone the bench build holds that same rule (BenchNet widens it in the
 * emulator alone), its sockets opened by the same code as the released app's: checked as the released app's. In the
 * emulator the bench build takes every address by design - said, not judged - and the released build of this PC
 * ({@code flight-app\build.ps1}), started there beside it, is checked instead: without Tailscale it must be silent.</li>
 * <li><b>A WAKE from outside, behind the lock screen</b> ({@link #aWakeFromOutsideStaysBehindTheLockScreen}): with the
 * lock screen up, the app's WAKE is started from adb's shell - as any other app could - without the app's own token,
 * and once with a made-up one: the lock screen must still show with nothing in front of it, the window in front must
 * not be the app's, and the app's log must say it was opened as a plain start. The app's own WAKE (the PC's PILOT UAV
 * waking a sleeping phone) still opens it over the lock screen: {@code PhoneNightScenario} shows that every round. The
 * emulator's lock screen (a swipe) is put up by its sleep key; a real phone's lock is never touched - the bench has no
 * key for it - so a phone found unlocked is waited for while a hand locks it, and else the check is skipped by name.</li>
 * <li><b>Storage and backup</b> ({@link #noBackupOfTheAppAndNoClipInTheGallery}): no backup is started - it would send
 * the app's data off the phone. Read instead: Android's flags of each DRAUPNE Flight installed (no ALLOW_BACKUP), what
 * Android's backup holds or queues of it (nothing), and the gallery's index of videos, pictures and sound (nothing of
 * DRAUPNE's: its recordings and clips lie in folders the index leaves out). Only the lines that name the app are kept
 * of what Android says; of the gallery, only the count of what it lists and the entries of DRAUPNE's.</li>
 * <li><b>Floods</b> ({@link #aFloodOfNameQueriesHoldsNothingUp}): the name query every DRAUPNE Link's scan sends, with
 * the link key, again and again for {@code -Flood} minutes ({@link #floodMinutes}: BS6 asks for 30; a 3-minute try
 * without it) - at a made-up drone from {@value #DRONE_SOURCES} addresses of this PC's own loopback, and, where the run
 * has a phone, at the phone from one address of this PC (over Tailscale to a real phone). Each held to so many bytes a
 * second, sent and answered together - the phone's is our own phone's mobile data - and the totals go into the film.
 * A minute before, the flood, half a minute after; the PC's real link flies both all the while, its sticks hands off
 * (throttle down, ARM off) to the phone, with no picture asked of it. What must hold: the sticks' age as before (the
 * phone without a flight controller: the gap between stick packets arriving there), the link never lost, the memory
 * flat (the made-up drone's side: this PC's heap; the phone: Android's count of the app's), the threads capped, the
 * made-up drone's book of askers at its cap, the phone app's process the same.</li>
 * </ul>
 * On the emulator and on the real phone (the task benchHwPhone). On a real phone nothing is cleared, granted or
 * stopped here: the app on the phone is opened as a hand opens it ({@code am start}) and read.
 */
@Tag("emulator")
@Tag("hwphone")
class PhoneHardeningScenario {
    /** The kernel's tables of the sockets: what takes datagrams and connections. */
    static final List<String> TABLES = List.of("udp", "udp6", "tcp", "tcp6");
    /** The app's main screen, as adb names it after the package. */
    static final String MAIN_ACTIVITY = "de.droiddrone.flight.MainActivity";
    /** A token of the right length that is not the app's: the app makes its own at random and keeps it to itself. */
    static final String MADE_UP_TOKEN = "0123456789abcdef0123456789abcdef";
    /** How long a real phone found unlocked is waited for while a hand locks it (its power button). */
    static final long LOCK_BY_HAND_MS = 120_000;

    private final List<String> wrong = new ArrayList<>();
    private Film film;

    private void check(boolean ok, String what) {
        film.remark((ok ? "ok: " : "WRONG: ") + what);
        if (!ok) wrong.add(what);
    }

    private void say(String text) {
        film.remark(text);
    }

    private static Film film(String name) throws IOException {
        return new Film(Sandbox.runRoot().resolve("film").resolve(name + "-" + ProcessHandle.current().pid()), name);
    }

    /** The film closed with its verdict, the phone given back; the test fails with what went wrong. */
    private void finish(String name, PhoneRig phone) {
        try {
            if (phone != null) phone.close();
        } finally {
            film.close(wrong.isEmpty(), wrong);
        }
        if (!wrong.isEmpty()) fail(name + ": " + wrong.size() + " problem(s):\n - " + String.join("\n - ", wrong));
    }

    private void stopped(Throwable t, PhoneRig phone) {
        wrong.add("the scenario stopped: " + t);
        try {
            if (phone != null) phone.shot("phone: STOPPED - " + t);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The app open and its service running. The emulator: its newest bench build, as every emulator scenario has it,
     * started afresh. A real phone: the app that is there, opened as a hand opens it - nothing cleared, granted or stopped.
     */
    private static void openTheApp(PhoneRig phone) throws IOException {
        if (!phone.device()) {
            phone.installApp();
            phone.grantPermissions();
            phone.forceStop();
        }
        phone.launch();
        phone.startIfAsked(); // START, where it shows; behind a real phone's lock screen the service has to have started by itself
    }

    /** The app's sockets now, by its process's uid; null when it has no process. */
    static List<Sockets.Sock> socketsOf(PhoneRig phone, String app) {
        String pid = Sockets.firstPid(phone.shell("pidof " + app));
        if (pid.isEmpty()) return null;
        int uid = Sockets.uid(phone.shell("cat /proc/" + pid + "/status"));
        if (uid < 0) return null;
        List<Sockets.Sock> all = new ArrayList<>();
        for (String table : TABLES) all.addAll(Sockets.parse(table, phone.shell("cat /proc/net/" + table + " 2>/dev/null")));
        if (all.isEmpty()) throw new AssertionError("The phone's socket tables (/proc/net/udp, udp6, tcp, tcp6) could not be read");
        return Sockets.of(all, uid);
    }

    /** The app's sockets once it has one that takes datagrams (its link socket, parked or not), waited for so long. */
    private static List<Sockets.Sock> socketsOnceOpen(PhoneRig phone, String app, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            List<Sockets.Sock> mine = socketsOf(phone, app);
            if (mine != null && mine.stream().anyMatch(s -> !s.tcp() && s.listens()) || System.currentTimeMillis() > end) return mine;
            Ui.pause(1000);
        }
    }

    /** Every socket of the app, as the film says it: its kind of address and interface, never the phone's numbers. */
    private void list(String who, List<Sockets.Sock> mine, List<Sockets.Address> phoneAddresses) {
        StringBuilder s = new StringBuilder(who + ": " + mine.size() + " socket(s)");
        for (Sockets.Sock k : mine) s.append("; ").append(Sockets.said(k, Sockets.ifaceOf(phoneAddresses, k.local())));
        say(s + ".");
    }

    /**
     * BS1: the app's sockets as the kernel lists them, held to the released rule - or, for the bench build in the
     * emulator, said as what they are by design.
     */
    @Test
    void whatThePhoneListensOn() throws Exception {
        PhoneRig.Target target = PhoneRig.Target.ofRun();
        Assumptions.assumeTrue(target.setUp(), "no Android emulator set up on this PC (docs/TESTING.md)");
        film = film("phone-listens-on");
        PhoneRig phone = new PhoneRig(film, target);
        try {
            phone.ensureRunning();
            String app = target.app(), stamp = phone.now();
            openTheApp(phone);
            int linkPort = phone.device() && target.link() != null ? target.link().getPort() : PhoneRig.linkOnThePhone();
            List<Sockets.Sock> mine = socketsOnceOpen(phone, app, 30_000);
            if (mine == null) throw new AssertionError(app + " has no process on the phone: nothing to read");
            List<Sockets.Address> addresses = Sockets.addresses(phone.shell("ip -o addr show 2>&1"));
            String said = phone.appSaidSince(stamp);
            Files.writeString(film.dir().resolve("phone-log.txt"), said, StandardCharsets.UTF_8);
            String build = app + " " + phone.version();
            list("The app checked (" + build + (phone.device() ? ", a real phone" : ", the emulator") + ")", mine, addresses);
            if (addresses.isEmpty()) say("The phone's own addresses could not be read (ip -o addr show): the interfaces are not named.");

            if (!phone.device() && target.benchBuild()) {
                // the bench build in the emulator: every address, by design (BenchNet) - and so the reading is seen to work
                boolean wide = mine.stream().anyMatch(s -> !s.tcp() && s.listens() && s.localPort() == linkPort && s.where() == Sockets.Where.EVERY_ADDRESS);
                check(wide, "the bench build in the emulator takes every address at the link port " + linkPort + ", as BenchNet makes it there (the test rig's build "
                        + "only)" + (said.contains(PhoneWords.BENCH_NET_WIDE) ? " - its log says so" : "") + ": the reading finds the app's link socket");
                say("THE BENCH BUILD IN THE EMULATOR SAYS NOTHING OF THE RELEASED APP: it widens the link there on purpose. The released build is checked below.");
                theReleasedAppInTheEmulator(target, linkPort);
            } else {
                holdToTheReleasedRule("the app (" + build + ")", mine, addresses, linkPort, said, phone);
                if (phone.device()) {
                    say(target.benchBuild()
                            ? "Checked: the BENCH BUILD on a real phone. There it holds the released app's rule (BenchNet widens in the emulator alone"
                            + (said.contains(PhoneWords.BENCH_NET_TAILSCALE) ? ", and its log says so" : "") + "): its link and overwatch sockets are opened by "
                            + "the same code as the released app's (TailscaleOnly, LinkSocket). What it proves for the released app: its sources at this version; "
                            + "the released APK itself only where it runs on the phone too (below)."
                            : "Checked: THE RELEASED APP itself, on a real phone.");
                    // the other DRAUPNE Flight, where it happens to run: read as it is, never started or stopped here
                    String other = target.benchBuild() ? PhoneRig.APP : de.droiddrone.bench.BenchControl.PACKAGE;
                    List<Sockets.Sock> theirs = socketsOf(phone, other);
                    if (theirs == null) {
                        say(other + " does not run on the phone now: not read (the bench starts no other app).");
                    } else {
                        list("Also running: " + other, theirs, addresses);
                        holdToTheReleasedRule(other, theirs, addresses, linkPort, "", phone);
                    }
                }
            }
            phone.shot("phone: its sockets read");
        } catch (Throwable t) {
            stopped(t, phone);
        } finally {
            finish("phone-listens-on", phone);
        }
    }

    /**
     * BS8: the app's WAKE started from outside the app - adb's shell, as any other app on the phone could - with the
     * lock screen up: it must stay behind it.
     */
    @Test
    void aWakeFromOutsideStaysBehindTheLockScreen() throws Exception {
        PhoneRig.Target target = PhoneRig.Target.ofRun();
        Assumptions.assumeTrue(target.setUp(), "no Android emulator set up on this PC (docs/TESTING.md)");
        film = film("phone-wake-from-outside");
        PhoneRig phone = new PhoneRig(film, target);
        try {
            phone.ensureRunning();
            openTheApp(phone);
            String app = target.app();
            say("Checked: " + app + " " + phone.version() + (phone.device() ? " on a real phone" : " in the emulator") + ". Its main screen and the WAKE's "
                    + "handling are the released app's own code in either build (the bench build adds a receiver and its ways to a flight controller).");
            PhoneRig.Keyguard k = lockScreenUp(phone);
            if (k.lock() == PhoneRig.Lock.UNLOCKED) {
                if (phone.device()) {
                    phone.skip("BS8, a WAKE from outside behind the lock screen: the phone was not locked (the bench never locks a real phone itself) - " + k);
                } else {
                    check(false, "the emulator's lock screen came up for the check: " + k);
                }
                return;
            }
            say("The lock screen up for the check: " + k + ".");
            phone.shot("phone: locked, before a WAKE from outside");
            String[][] tries = {{"without a token", ""}, {"with a made-up token", " --es " + PhoneWords.WAKE_TOKEN_EXTRA + " " + MADE_UP_TOKEN}};
            for (String[] t : tries) {
                String w = "a WAKE from outside " + t[0];
                phone.shell("input keyevent KEYCODE_WAKEUP"); // the screen lit: the lock screen as a hand that picks the phone up sees it
                Ui.pause(800);
                String stamp = phone.now();
                String started = phone.shell("am start -n " + app + "/" + MAIN_ACTIVITY + " -a " + PhoneWords.WAKE_ACTION + t[1]);
                Ui.pause(3000);
                PhoneRig.Keyguard after = phone.keyguardAsItIs();
                PhoneRig.Reading screen = phone.reading();
                String log = phone.appSaidSince(stamp);
                check(!started.contains("Error"), w + ": Android took the start (" + started.lines().findFirst().orElse("") + ")");
                check(after.lock() != PhoneRig.Lock.UNLOCKED, w + ": the lock screen still shows (" + after + ")");
                check(!Boolean.TRUE.equals(after.occluded()), w + ": nothing is in front of the lock screen");
                check(!after.focus().startsWith(app + "/"), w + ": the window in front is not the app's" + (after.focus().isEmpty() ? "" : " (" + after.focus() + ")"));
                if (screen.read()) {
                    long apps = screen.nodes().stream().filter(n -> app.equals(n.app())).count();
                    check(apps == 0, w + ": nothing of the app's is on the screen as Android describes it (" + apps + " of its parts)");
                } else {
                    say(w + ": the screen could not be described (uiautomator: " + screen.error() + ") - the lock screen's state and the window in front say it.");
                }
                check(!log.contains(PhoneWords.OPENED_OVER_LOCK), w + ": the app did not open over the lock screen (its log)");
                check(log.contains(PhoneWords.WAKE_NOT_ITS_OWN), w + ": the app's log says it took it as a plain start, behind the lock screen");
                phone.shot("phone: after " + w);
            }
            say("The other half - the app's own WAKE, with its token, as the PC's PILOT UAV wakes a sleeping phone, still opens it over the lock screen - is "
                    + "PhoneNightScenario's: every round it wakes the phone so, and files what adb sees of it (TO LOOK AT, the first three rounds).");
        } catch (Throwable t) {
            stopped(t, phone);
        } finally {
            if (!phone.device()) {
                try {
                    phone.unlock(); // the emulator as the other scenarios find it: its lock screen swiped away
                } catch (Throwable ignored) {
                }
            }
            finish("phone-wake-from-outside", phone);
        }
    }

    /** The gallery's indexes: what a gallery app lists. */
    static final List<String> GALLERY = List.of("video", "images", "audio");

    /** BS9: Android's word on the app's backup, and the gallery's index - read; nothing is backed up or changed. */
    @Test
    void noBackupOfTheAppAndNoClipInTheGallery() throws Exception {
        PhoneRig.Target target = PhoneRig.Target.ofRun();
        Assumptions.assumeTrue(target.setUp(), "no Android emulator set up on this PC (docs/TESTING.md)");
        film = film("phone-backup-and-gallery");
        PhoneRig phone = new PhoneRig(film, target);
        try {
            phone.ensureRunning();
            if (!phone.device()) phone.installApp(); // the emulator's newest bench build, as its other scenarios have it
            // ---- what Android keeps of each DRAUPNE Flight on the phone
            List<String> installed = new ArrayList<>();
            for (String app : List.of(target.app(), target.benchBuild() ? PhoneRig.APP : de.droiddrone.bench.BenchControl.PACKAGE)) {
                if (phone.shell("pm path " + app).contains("package:")) installed.add(app);
            }
            check(installed.contains(target.app()), target.app() + " is on the phone");
            for (String app : installed) {
                String d = phone.shell("dumpsys package " + app);
                java.util.Set<String> flags = PhoneStore.flags(d, app);
                String who = app + " " + PhoneStore.versionName(d, app);
                if (flags.isEmpty()) {
                    check(false, who + ": Android's flags for it could be read (dumpsys package)");
                    continue;
                }
                check(!PhoneStore.allowsBackup(flags), who + ": Android holds it as an app that allows no backup - ALLOW_BACKUP is not among its flags " + flags);
            }
            say("Where its data may go on Android 12 and later - nothing to a cloud backup, nothing to a new phone - its data extraction rules say, and dumpsys "
                    + "does not show them: they are in the app's manifest, and NoBackupNoGalleryTest holds the manifest to them at every build.");
            // ---- what Android's backup holds or queues of it
            PhoneStore.Backup b = PhoneStore.backup(phone.shell("dumpsys backup"), installed);
            say("Android's backup on this phone: " + (b.enabled() == null ? "its state not said" : b.enabled() ? "ON" : "off")
                    + (Boolean.FALSE.equals(b.enabled()) ? " - so nothing would go now anyway; the flags above are what holds when it is on" : "") + ".");
            check(b.lines().isEmpty(), "Android's backup holds and queues nothing of DRAUPNE Flight" + (b.lines().isEmpty() ? " (dumpsys backup names it nowhere)"
                    : ": " + String.join(" | ", b.lines().subList(0, Math.min(5, b.lines().size())))));
            // ---- the gallery's index
            for (String kind : GALLERY) {
                String uri = "content://media/external/" + kind + "/media";
                int items = PhoneStore.count(phone.shell("content query --uri " + uri + " --projection _id 2>&1 | grep -c -e Row:"));
                String besides = phone.shell("content query --uri " + uri + " --projection _id 2>&1 | grep -v -e Row: | head -2").trim();
                if (!PhoneStore.answered(items, besides)) {
                    check(false, "the gallery's index of " + kind + " could be read: " + (besides.isEmpty() ? "no answer" : besides.lines().findFirst().orElse("")));
                    continue;
                }
                List<String> ours = PhoneStore.rows(phone.shell("content query --uri " + uri + " --projection _data 2>&1 | grep -i -e droiddrone -e draupne"));
                check(ours.isEmpty(), "the gallery's index of " + kind + " (" + items + " item(s) on the phone) lists nothing of DRAUPNE's"
                        + (ours.isEmpty() ? "" : ": " + ours.size() + " - " + String.join("; ", ours.subList(0, Math.min(5, ours.size())))));
            }
            say("What a gallery would list of DRAUPNE's are its recordings and overwatch clips, in its media folders (Android/media/<the app>/Video and "
                    + "Overwatch), which carry a .nomedia marker. A phone that never recorded lists none either way.");
        } catch (Throwable t) {
            stopped(t, phone);
        } finally {
            finish("phone-backup-and-gallery", phone);
        }
    }

    /**
     * The lock screen up, the screen lit. The emulator's is a swipe (PhoneRig.grantPermissions), and comes with its
     * sleep key. A real phone's lock is never touched - the bench sends no key that could - so one found unlocked is
     * waited for while a hand locks it; what is returned then says whether that happened.
     */
    private PhoneRig.Keyguard lockScreenUp(PhoneRig phone) {
        if (!phone.device()) {
            phone.shell("input keyevent KEYCODE_SLEEP");
            Ui.pause(1500);
            phone.shell("input keyevent KEYCODE_WAKEUP");
            Ui.pause(1500);
            return phone.keyguardAsItIs();
        }
        phone.shell("input keyevent KEYCODE_WAKEUP");
        Ui.pause(800);
        PhoneRig.Keyguard k = phone.keyguardAsItIs();
        if (k.lock() != PhoneRig.Lock.UNLOCKED) return k;
        say("The phone is unlocked. The bench never locks a real phone itself: lock it with its power button within " + LOCK_BY_HAND_MS / 60_000
                + " min and leave it so - the run waits for it, then goes on behind the lock screen.");
        long end = System.currentTimeMillis() + LOCK_BY_HAND_MS;
        while (System.currentTimeMillis() < end) {
            Ui.pause(5000);
            k = phone.keyguardAsItIs();
            if (k.lock() != PhoneRig.Lock.UNLOCKED) {
                say("Locked by hand: " + k + ".");
                return k;
            }
        }
        return k;
    }

    /**
     * The released rule (Q-006): whatever takes packets from anyone is on a Tailscale address of a VPN's interface, or
     * parked on the phone's own loopback; no TCP listener at all outside the phone.
     */
    private void holdToTheReleasedRule(String who, List<Sockets.Sock> mine, List<Sockets.Address> addresses, int linkPort, String said, PhoneRig phone) {
        List<Sockets.Sock> open = mine.stream().filter(Sockets.Sock::listens).toList();
        List<String> outside = new ArrayList<>();
        for (Sockets.Sock s : open) {
            if (s.where() == Sockets.Where.EVERY_ADDRESS || s.where() == Sockets.Where.OTHER) outside.add(Sockets.said(s, Sockets.ifaceOf(addresses, s.local())));
        }
        check(!open.isEmpty(), who + ": it has a socket that takes datagrams (its link socket, on Tailscale or parked) - " + open.size() + " open");
        check(outside.isEmpty(), who + ": nothing of it takes packets on every address of the phone or on an address other than Tailscale's or its own loopback"
                + (outside.isEmpty() ? "" : " - " + String.join("; ", outside)));
        for (Sockets.Sock s : open) {
            if (s.where() != Sockets.Where.TAILSCALE) continue;
            String iface = Sockets.ifaceOf(addresses, s.local());
            if (iface.isEmpty()) {
                say(who + ": port " + s.localPort() + " is on a Tailscale address whose interface ip did not list.");
            } else {
                check(TailscaleOnly.vpnName(iface), who + ": port " + s.localPort() + " is on the Tailscale address of a VPN's interface (" + iface
                        + "), not on a carrier's address of the same range");
            }
        }
        check(open.stream().noneMatch(s -> s.tcp() && s.where() != Sockets.Where.LOOPBACK), who + ": no TCP listener outside the phone itself");
        boolean onTailscale = open.stream().anyMatch(s -> !s.tcp() && s.localPort() == linkPort && s.where() == Sockets.Where.TAILSCALE);
        boolean atLinkPort = open.stream().anyMatch(s -> s.localPort() == linkPort);
        if (onTailscale) {
            say(who + ": its link port " + linkPort + " is open on the phone's Tailscale address, and nowhere else.");
        } else if (!atLinkPort) {
            String why = said.contains(PhoneWords.NO_TAILSCALE) ? " (its log: not on Tailscale)" : "";
            say(who + ": nothing listens at the link port " + linkPort + why + " - parked: the phone answers nobody now.");
            if (phone.device()) phone.skip("BS1 with Tailscale up (" + who + "): the link port on the phone's Tailscale address - the phone was not on Tailscale, "
                    + "so only the parked state was read");
        }
    }

    /**
     * The emulator: the released build of this PC beside the bench build. The emulator has no Tailscale, so the released
     * app must have nothing open but its parked socket on loopback. Stopped again afterwards: the emulator flies the
     * bench build.
     */
    private void theReleasedAppInTheEmulator(PhoneRig.Target target, int linkPort) throws IOException {
        Path apk;
        try {
            apk = PhoneRig.builtApk();
        } catch (IllegalStateException e) {
            check(false, "the released app in the emulator: no released build on this PC to check (" + e.getMessage() + ")");
            return;
        }
        PhoneRig released = new PhoneRig(film, target.flying(PhoneRig.APP));
        try {
            String said = released.installAnyVersion(apk);
            if (!said.contains("Success")) throw new IllegalStateException("The released app did not install: " + said);
            released.grantPermissions();
            released.forceStop();
            String stamp = released.now();
            released.launch();
            released.startIfAsked();
            List<Sockets.Sock> theirs = socketsOnceOpen(released, PhoneRig.APP, 30_000);
            if (theirs == null) {
                check(false, "the released app (" + apk.getFileName() + ") runs in the emulator: it has no process there");
                return;
            }
            List<Sockets.Address> addresses = Sockets.addresses(released.shell("ip -o addr show 2>&1"));
            String log = released.appSaidSince(stamp);
            String build = PhoneRig.APP + " " + released.version();
            list("THE RELEASED APP in the emulator (" + build + ", " + apk.getFileName() + ")", theirs, addresses);
            holdToTheReleasedRule("the released app in the emulator", theirs, addresses, linkPort, log, released);
            check(theirs.stream().filter(Sockets.Sock::listens).allMatch(s -> s.where() == Sockets.Where.LOOPBACK),
                    "the released app in the emulator, which has no Tailscale: all it has open is parked on the phone's own loopback");
            check(log.contains(PhoneWords.NO_TAILSCALE), "and its log says it answers nothing without Tailscale");
            released.shot("phone: the released app's sockets read");
        } finally {
            try {
                released.forceStop(); // the emulator flies the bench build: the released app is not left running beside it
            } finally {
                released.close();
            }
        }
    }

    // ------------------------------------------------------------------ BS6, a flood of name queries

    /** What the flood at the phone takes of the network, sent and answered together: it is our own phone, on mobile data. */
    static final long PHONE_BYTES_PER_SECOND = 4_000;
    /** At the made-up drone, on this PC's own loopback. */
    static final long DRONE_BYTES_PER_SECOND = 200_000;
    /** The made-up drone is asked from this many addresses: four times what its book of askers holds. */
    static final int DRONE_SOURCES = 4 * KnownPcs.BOOK_MAX;
    /** The sticks to the phone, a second, and their values: hands off - throttle down, ARM off - as a radio at rest. */
    static final int PHONE_STICK_HZ = 50;
    private static final short[] HANDS_OFF = {1500, 1500, 1500, 1000, 1000, 1500, 1000, 1000};
    static final long BEFORE_MS = 60_000, AFTER_MS = 30_000, PC_EVERY_MS = 15_000, PHONE_EVERY_MS = 30_000;
    /** What the sticks' 95th percentile may rise by beyond half again of before's: on this PC's loopback, over the phone's network. */
    static final long DRONE_STICK_SLACK_MS = 30, PHONE_STICK_SLACK_MS = 100;
    /** What a memory may grow by over the flood (a collection's saw, a cache filled once), MB; threads more than before. */
    static final long GROWTH_MB = 32, PC_MORE_THREADS = 4, PHONE_MORE_THREADS = 8;
    /** Tailscale's own wrapping of a datagram, about (WireGuard's head, its UDP and IP heads), bytes: for the film's estimate. */
    static final int TAILSCALE_WRAP = 60;

    /** The flood's minutes: bench.flood.min (BENCH_FLOOD_MIN, bench.ps1 -Flood) - BS6 asks for 30; left out, a 3-minute try. */
    static double floodMinutes() {
        try {
            double m = Double.parseDouble(System.getProperty("bench.flood.min", "").trim());
            return m > 0 ? m : 3;
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /** The sticks' age as the PC's screen shows it (OsdDataBuilder): the phone's word on its last write to the flight controller, aged since; -1 not known. */
    static long sticksAge(ControlLink link) {
        FcTelemetry.PhoneState ps = link.getTelemetry().getPhoneState();
        long now = MonoClock.ms();
        if (ps == null || now - ps.receivedAt() > 2500 || ps.rcWriteAgeMs() < 0) return -1;
        return ps.rcWriteAgeMs() + (now - ps.receivedAt());
    }

    /** The PC's link to the phone for the flood: no picture asked (no video on the phone's network), hands-off sticks, every byte through the wire counted. */
    private static final class LinkToThePhone implements AutoCloseable {
        final Wire wire;
        final ControlLink link;
        final AtomicLong bytes = new AtomicLong(), datagrams = new AtomicLong();
        private volatile boolean running = true;
        private final Thread sticks;

        LinkToThePhone(InetSocketAddress at, String key) throws IOException {
            wire = new Wire(at.getAddress(), at.getPort());
            wire.rule((toPhone, d, len) -> {
                bytes.addAndGet(len + Flood.HEAD);
                datagrams.incrementAndGet();
                return 0;
            });
            PcConfig config = PcConfig.defaults();
            config.set("key", key);
            config.set("rcRate", Integer.toString(PHONE_STICK_HZ));
            link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
            link.setEvents(line -> { });
            link.start();
            sticks = new Thread(() -> {
                Pacer pacer = new Pacer(PHONE_STICK_HZ);
                while (running) {
                    link.sendRc(HANDS_OFF.clone());
                    try {
                        pacer.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "benchPhoneSticks");
            sticks.setDaemon(true);
            sticks.start();
        }

        boolean ready() {
            return link.getState() == ControlLink.State.READY;
        }

        @Override
        public void close() {
            running = false;
            sticks.interrupt();
            link.close();
            wire.close();
        }
    }

    /** The sticks' figures and the links' losses, by phase: a stick age five times a second, the phones' own gap between stick packets once a second. */
    private static final class StickSampler implements AutoCloseable {
        volatile FloodWatch.Phase phase = FloodWatch.Phase.BEFORE;
        final FloodWatch.Series droneAge = new FloodWatch.Series("the made-up drone's sticks age"), droneGap = new FloodWatch.Series("the longest gap between stick packets at the made-up drone"),
                phoneAge = new FloodWatch.Series("the phone's sticks age"), phoneGap = new FloodWatch.Series("the longest gap between stick packets at the phone"),
                phoneLag = new FloodWatch.Series("the phone's stick lag");
        final java.util.EnumMap<FloodWatch.Phase, AtomicLong> droneLost = new java.util.EnumMap<>(FloodWatch.Phase.class),
                phoneLost = new java.util.EnumMap<>(FloodWatch.Phase.class);
        private final LinkBench drone;
        private final LinkToThePhone phone;
        private final Thread thread;
        private volatile boolean running = true;

        StickSampler(LinkBench drone, LinkToThePhone phone) {
            this.drone = drone;
            this.phone = phone;
            for (FloodWatch.Phase p : FloodWatch.Phase.values()) {
                droneLost.put(p, new AtomicLong());
                phoneLost.put(p, new AtomicLong());
            }
            thread = new Thread(this::run, "benchStickSampler");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            boolean droneWas = true, phoneWas = true;
            for (long tick = 0; running; tick++) {
                FloodWatch.Phase p = phase;
                droneAge.add(p, sticksAge(drone.link));
                boolean d = drone.ready();
                if (droneWas && !d) droneLost.get(p).incrementAndGet();
                droneWas = d;
                if (phone != null) {
                    phoneAge.add(p, sticksAge(phone.link));
                    boolean r = phone.ready();
                    if (phoneWas && !r) phoneLost.get(p).incrementAndGet();
                    phoneWas = r;
                }
                if (tick % 5 == 4) {
                    FcTelemetry.PhoneLink g = drone.link.getTelemetry().takePhoneLink();
                    if (g != null) droneGap.add(p, g.rcInGapMs());
                    if (phone != null) {
                        FcTelemetry.PhoneLink h = phone.link.getTelemetry().takePhoneLink();
                        if (h != null) phoneGap.add(p, h.rcInGapMs());
                        FcTelemetry.PhoneRcLag lag = phone.link.getTelemetry().getPhoneRcLag();
                        if (lag != null && lag.maxLagMs() >= 0) phoneLag.add(p, lag.maxLagMs());
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
        }
    }

    /** The slow figures, by phase: this PC's heap and threads, the made-up drone's book; the phone app's memory and threads. */
    private static final class Figures {
        final FloodWatch.Series pcHeap = new FloodWatch.Series("this PC's heap after a collection"), pcThreads = new FloodWatch.Series("this PC's threads"),
                book = new FloodWatch.Series("the made-up drone's book of askers"), phonePss = new FloodWatch.Series("the phone app's memory (PSS)"),
                phoneHeap = new FloodWatch.Series("its Java heap"), phoneThreads = new FloodWatch.Series("its threads");
        final List<String> phonePids = new ArrayList<>();
    }

    private static long mb(long kb) {
        return kb < 0 ? -1 : kb / 1024;
    }

    /**
     * So long in one phase, the slow figures taken as they fall due - the first one interval into it: a reading at the
     * very start of the flood is one from before it (on loopback this PC's heap rose by some 20 MB in the flood's first
     * seconds, then lay flat - 2026-11-24), and the memory's verdict compares the flood's first third with its last.
     */
    private static void watch(FloodWatch.Phase p, long ms, Figures f, LinkBench drone, PhoneRig phone, String app, String pid) {
        long start = System.currentTimeMillis(), end = start + ms, nextPc = start + PC_EVERY_MS, nextPhone = start + PHONE_EVERY_MS;
        while (System.currentTimeMillis() < end) {
            long now = System.currentTimeMillis();
            if (now >= nextPc) {
                nextPc = now + PC_EVERY_MS;
                f.pcHeap.add(p, Watch.usedMb());
                f.pcThreads.add(p, Thread.activeCount());
                f.book.add(p, BenchFleetAccess.askers(drone.drone));
            }
            if (phone != null && !pid.isEmpty() && now >= nextPhone) {
                nextPhone = now + PHONE_EVERY_MS;
                String meminfo = phone.shell("dumpsys meminfo " + app);
                f.phonePss.add(p, mb(FloodWatch.pssKb(meminfo)));
                f.phoneHeap.add(p, mb(FloodWatch.javaHeapKb(meminfo)));
                f.phoneThreads.add(p, Sockets.threads(phone.shell("cat /proc/" + pid + "/status")));
                String seen = Sockets.firstPid(phone.pid());
                if (!seen.equals(pid) && !f.phonePids.contains(seen)) f.phonePids.add(seen.isEmpty() ? "none" : seen);
            }
            Ui.pause(1000);
        }
    }

    /** The phone's link key: a real phone's from the hardware file where it says one; else the one a phone has from its first start. */
    private static String phoneKey(PhoneRig phone) throws IOException {
        if (phone.device() && Hardware.present()) {
            String k = Hardware.load().phoneKey();
            if (!k.isEmpty()) return k;
        }
        return PcConfig.defaults().getKey();
    }

    /**
     * BS6: the name queries of a PC's scan, flooded at a made-up drone from many addresses and at the phone from one, for
     * the run's minutes; the sticks, the links, the memory and the threads watched before, during and after.
     */
    @Test
    void aFloodOfNameQueriesHoldsNothingUp() throws Exception {
        PhoneRig.Target target = PhoneRig.Target.ofRun();
        film = film("phone-flood");
        double minutes = floodMinutes();
        PhoneRig phone = target.setUp() ? new PhoneRig(film, target) : null;
        LinkBench drone = null;
        LinkToThePhone toPhone = null;
        Flood atDrone = null, atPhone = null;
        StickSampler sticks = null;
        try {
            say("The flood: " + minutes + " min" + (minutes < 30 ? " - A TRY: BS6 asks for 30 (bench.ps1 -Flood 30)" : "") + "; a minute before it, half a minute after.");
            // ---- the made-up drone, flown by the PC's real link at the app's own stick rate
            drone = new LinkBench(BadLink.clean(66)).start(0);
            LinkBench d = drone;
            if (!LinkBench.within(40_000, d::ready)) throw new AssertionError("The PC's link to the made-up drone did not come up within 40 s");
            // ---- the phone, where the run has one: its app open, the PC's link to it
            String app = target.app(), pid = "", stamp = "", key = "";
            InetSocketAddress at = null;
            if (phone == null) {
                say("No phone in this run (no emulator set up on this PC): the made-up drone alone.");
            } else {
                phone.ensureRunning();
                openTheApp(phone);
                at = phone.linkAddress(); // the emulator: a forwarded port on loopback; a real phone: its Tailscale address
                key = phoneKey(phone);
                toPhone = new LinkToThePhone(at, key);
                LinkToThePhone l = toPhone;
                // a real phone may be found asleep from an earlier run: back at its next check, as PILOT UAV has it back
                boolean up = LinkBench.within(phone.device() ? 150_000 : 60_000, l::ready);
                check(up, "the PC's link to the phone is READY (" + app + " " + phone.version() + "; no picture asked of it: its sticks and its telemetry alone)");
                if (up) {
                    pid = Sockets.firstPid(phone.pid());
                    stamp = phone.now();
                } else {
                    toPhone.close();
                    toPhone = null;
                    say("The flood goes to the made-up drone alone: the phone was not reached.");
                }
            }
            sticks = new StickSampler(drone, toPhone);
            Figures f = new Figures();
            PhoneRig onPhone = toPhone == null ? null : phone;

            watch(FloodWatch.Phase.BEFORE, BEFORE_MS, f, drone, onPhone, app, pid);

            atDrone = new Flood("made-up-drone", Names.query(drone.config.getKey()), new InetSocketAddress(InetAddress.getLoopbackAddress(), drone.drone.port()),
                    Flood.loopbackSources(DRONE_SOURCES), DRONE_BYTES_PER_SECOND);
            // one source: the emulator's forwarded port on this PC's loopback, from loopback; a real phone from this PC's every
            // address (as the bench's wire speaks to it), the system choosing the way - Tailscale's
            if (toPhone != null) {
                List<InetAddress> one = at.getAddress().isLoopbackAddress() ? List.of(InetAddress.getLoopbackAddress()) : List.of();
                atPhone = new Flood("phone", Names.query(key), at, one, PHONE_BYTES_PER_SECOND);
            }
            sticks.phase = FloodWatch.Phase.DURING;
            atDrone.start();
            if (atPhone != null) atPhone.start();
            watch(FloodWatch.Phase.DURING, Math.round(minutes * 60_000), f, drone, onPhone, app, pid);
            atDrone.close();
            if (atPhone != null) atPhone.close();
            sticks.phase = FloodWatch.Phase.AFTER;
            watch(FloodWatch.Phase.AFTER, AFTER_MS, f, drone, onPhone, app, pid);
            sticks.close();

            judgeTheMadeUpDrone(atDrone, sticks, f, d);
            if (atPhone != null) judgeThePhone(phone, atPhone, toPhone, sticks, f, pid, stamp);
        } catch (Throwable t) {
            stopped(t, phone);
        } finally {
            for (AutoCloseable c : new AutoCloseable[]{sticks, atDrone, atPhone, toPhone, drone}) {
                try {
                    if (c != null) c.close();
                } catch (Exception ignored) {
                }
            }
            finish("phone-flood", phone);
        }
    }

    private void judgeTheMadeUpDrone(Flood flood, StickSampler sticks, Figures f, LinkBench drone) {
        FloodWatch.Phase during = FloodWatch.Phase.DURING, before = FloodWatch.Phase.BEFORE;
        long answers = flood.answers.get();
        say("AT THE MADE-UP DRONE: " + flood.sent.get() + " queries from " + flood.sources() + " addresses of this PC's loopback"
                + (flood.refused() > 0 ? " (" + flood.refused() + " could not be had: " + flood.refusedSaid() + ")" : "") + ", " + answers + " answers; "
                + FloodWatch.bytes(flood.bytes()) + " both ways, " + DRONE_BYTES_PER_SECOND / 1000 + " kB/s at most.");
        check(answers > 0, "the made-up drone took the keyed queries and answered them (" + answers + ")");
        check(flood.sources() > KnownPcs.BOOK_MAX, "they came from more addresses (" + flood.sources() + ") than its book of askers holds (" + KnownPcs.BOOK_MAX + ")");
        long book = FloodWatch.percentile(f.book.of(during), 100);
        check(book >= 0 && book <= KnownPcs.BOOK_MAX, "its book of the PCs that asked its name held " + KnownPcs.BOOK_MAX + " at most: " + book + " at the most");
        say(sticks.droneAge.said("ms") + ".");
        say(sticks.droneGap.said("ms") + ".");
        check(FloodWatch.unchanged(sticks.droneAge.of(before), sticks.droneAge.of(during), DRONE_STICK_SLACK_MS), "its sticks age during the flood as before it (95th "
                + "percentile " + FloodWatch.percentile(sticks.droneAge.of(during), 95) + " ms, before " + FloodWatch.percentile(sticks.droneAge.of(before), 95)
                + " ms; half again and " + DRONE_STICK_SLACK_MS + " ms let pass)");
        check(sticks.droneLost.get(during).get() == 0, "the PC's link to it was never lost during the flood (" + sticks.droneLost.get(during).get() + " time(s); "
                + drone.linkLosses.get() + " in the whole run)");
        say(f.pcHeap.said("MB") + ".");
        say(f.pcThreads.said("") + ".");
        long grew = FloodWatch.growth(f.pcHeap.of(during));
        check(grew <= GROWTH_MB, "this PC's memory - the made-up drone's side - flat over the flood: " + grew + " MB from its first third to its last (" + GROWTH_MB + " let pass)");
        check(FloodWatch.capped(f.pcThreads.of(before), f.pcThreads.of(during), PC_MORE_THREADS), "and its threads capped: " + FloodWatch.percentile(f.pcThreads.of(before), 100)
                + " before, " + FloodWatch.percentile(f.pcThreads.of(during), 100) + " at the most during it (the flood's own one and " + PC_MORE_THREADS + " more let pass)");
    }

    private void judgeThePhone(PhoneRig phone, Flood flood, LinkToThePhone link, StickSampler sticks, Figures f, String pid, String stamp) throws IOException {
        FloodWatch.Phase during = FloodWatch.Phase.DURING, before = FloodWatch.Phase.BEFORE;
        long answers = flood.answers.get();
        say("AT THE PHONE: " + flood.sent.get() + " queries from ONE address of this PC (" + (phone.device() ? "its Tailscale address: Tailscale carries them"
                : "the emulator sees them all come from its host") + "), " + answers + " answers.");
        check(answers > 0, "the phone took the keyed queries and answered them (" + answers + ")" + (answers == 0 ? " - with none answered the flood proves nothing" : ""));
        String now = Sockets.firstPid(phone.pid());
        check(now.equals(pid) && f.phonePids.isEmpty(), "the phone app's process the same throughout (" + pid + (f.phonePids.isEmpty() ? "" : ", then " + f.phonePids)
                + (now.equals(pid) ? "" : ", now " + (now.isEmpty() ? "none" : now)) + ")");
        String log = phone.shell("logcat -d -v time -T '" + stamp + "' -s " + de.droiddrone.bench.BenchControl.LOG_TAG + ":I AndroidRuntime:E");
        Files.writeString(film.dir().resolve("phone-log.txt"), log, StandardCharsets.UTF_8);
        // the app's own crash guard's word, or Android's crash handler naming the app (that tag speaks of every app on the phone)
        boolean ended = log.contains(PhoneWords.FATAL) || log.contains("Process: " + phone.app() + ",");
        check(!ended, "no error ended the phone app (its log since the flood's minute before)");
        List<String> dropped = log.lines().filter(l -> l.contains(PhoneWords.OLDEST_DROPPED)).toList();
        say(dropped.isEmpty() ? "The phone's capped queues and books never ran over (no line of theirs in its log)."
                : "The phone's capped queues and books said " + dropped.size() + " time(s) that they dropped their oldest, as they are made to: " + dropped.get(0).trim());
        say(f.phonePss.said("MB") + ".");
        say(f.phoneHeap.said("MB") + ".");
        say(f.phoneThreads.said("") + ".");
        long grew = FloodWatch.growth(f.phonePss.of(during));
        check(f.phonePss.of(during).size() >= 2 && grew <= GROWTH_MB, "the phone app's memory flat over the flood: " + grew + " MB from its first third to its last ("
                + f.phonePss.of(during).size() + " readings; " + GROWTH_MB + " let pass)");
        check(FloodWatch.capped(f.phoneThreads.of(before), f.phoneThreads.of(during), PHONE_MORE_THREADS), "and its threads capped: "
                + FloodWatch.percentile(f.phoneThreads.of(before), 100) + " before, " + FloodWatch.percentile(f.phoneThreads.of(during), 100) + " at the most during it ("
                + PHONE_MORE_THREADS + " more let pass)");
        say(sticks.phoneAge.said("ms") + ".");
        say(sticks.phoneGap.said("ms") + ".");
        say(sticks.phoneLag.said("ms") + ".");
        if (!sticks.phoneAge.of(before).isEmpty() && !sticks.phoneAge.of(during).isEmpty()) {
            check(FloodWatch.unchanged(sticks.phoneAge.of(before), sticks.phoneAge.of(during), PHONE_STICK_SLACK_MS), "the phone's sticks age (its writes to its flight "
                    + "controller) during the flood as before it: 95th percentile " + FloodWatch.percentile(sticks.phoneAge.of(during), 95) + " ms, before "
                    + FloodWatch.percentile(sticks.phoneAge.of(before), 95) + " ms (half again and " + PHONE_STICK_SLACK_MS + " ms let pass)");
        } else {
            say("The phone has no flight controller here: its sticks age is not known. The longest gap between stick packets arriving at the phone stands in for it.");
            check(FloodWatch.unchanged(sticks.phoneGap.of(before), sticks.phoneGap.of(during), PHONE_STICK_SLACK_MS), "the gap between stick packets arriving at the phone "
                    + "during the flood as before it: 95th percentile " + FloodWatch.percentile(sticks.phoneGap.of(during), 95) + " ms, before "
                    + FloodWatch.percentile(sticks.phoneGap.of(before), 95) + " ms (" + sticks.phoneGap.of(during).size() + " readings; half again and "
                    + PHONE_STICK_SLACK_MS + " ms let pass)");
        }
        check(sticks.phoneLost.get(during).get() == 0, "the PC's link to the phone was never lost during the flood (" + sticks.phoneLost.get(during).get() + " time(s))");
        long wrap = phone.device() ? (flood.sent.get() + flood.answers.get() + link.datagrams.get()) * TAILSCALE_WRAP : 0;
        say("WHAT THE RUN TOOK OF THE PHONE'S NETWORK: the flood " + FloodWatch.bytes(flood.bytes()) + " both ways (" + PHONE_BYTES_PER_SECOND / 1000
                + " kB/s at most), the link " + FloodWatch.bytes(link.bytes.get()) + " (sticks " + PHONE_STICK_HZ + " a second, the phone's telemetry, no picture) - "
                + "IP and UDP heads counted" + (phone.device() ? "; Tailscale's own wrapping adds about " + FloodWatch.bytes(wrap) + " to that, some "
                + FloodWatch.bytes(flood.bytes() + link.bytes.get() + wrap) + " in all" : "") + ".");
    }
}
