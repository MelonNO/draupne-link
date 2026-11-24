package de.droiddrone.bench.phone;

import de.droiddrone.bench.Film;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.PhoneWords;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import de.droiddrone.common.TailscaleOnly;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
}
