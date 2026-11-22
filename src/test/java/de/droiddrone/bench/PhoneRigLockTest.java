package de.droiddrone.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real phone's lock screen (the first run on the phone, 2026-11-22: a PIN lock, as the phones on the drones have,
 * and the scenario stopped at it). Proved without a phone, a recorder where adb is: the rig tells unlocked, a swipe
 * and a secure lock screen apart and says which at each launch; behind a secure one a run takes the app's log, Android
 * and the bench build at their word where the screen cannot be read, lists what it could not check, and never sends
 * what could enter a PIN or change the lock; and a phone found unlocked is kept awake, its own setting put back.
 */
class PhoneRigLockTest {
    private static final String BENCH = BenchControl.PACKAGE, CABLE = PhoneRigGuardTest.CABLE;
    private static final String MAIN = "/de.droiddrone.flight.MainActivity";
    private static final String STATS = "Broadcasting: Intent { act=de.droiddrone.flight.bench.STATS }\nBroadcast completed: result=1, "
            + "data=\"DRAUPNE-BENCH-FC stats: chosen=usb link=down serialStatus=1 service=running pc=connected pid=4711\"";
    private static final String SERVICE = "  * ServiceRecord{1 u0 " + BENCH + "/de.droiddrone.flight.DDService}\n    app=ProcessRecord{2 4711:" + BENCH + "/u0a30}";
    private static final String LOG = "11-22 00:50:54.908 I/DDLog   ( 4711): Start serial init thread - OK\n11-22 00:50:54.911 I/DDLog   ( 4711): Start receiver thread - OK\n";

    private final long[] now = {1_000_000};
    private final List<Long> pauses = new ArrayList<>();

    @TempDir
    Path folder;

    private PhoneRig rig(PhoneRig.Target target, RecordingAdb adb, Film film) {
        return new PhoneRig(film, target, adb, ms -> {
            pauses.add(ms);
            now[0] += ms;
        }, () -> now[0], film == null ? folder : film.dir());
    }

    private static String remarks(Film film) throws Exception {
        Path file = film.dir().resolve("remarks.txt");
        return Files.exists(file) ? Files.readString(file) : "";
    }

    // ------------------------------------------------------------------ the three states

    @Test
    void theThreeStatesAreToldApartFromWhatThePhoneSays() {
        // the bench's phone on its first run, word for word: a PIN, the lock screen showing, the focus on the shade
        PhoneRig.Keyguard first = PhoneRig.Keyguard.read("  mShowingDream=false mDreamingLockscreen=true mDreamingSleepToken=null\n  isKeyguardShowing=true\n"
                + "  mCurrentFocus=Window{71c9f0e u0 NotificationShade}\n", "    CredentialType: PIN\n");
        assertEquals(PhoneRig.Lock.SECURE, first.lock());
        assertEquals("NotificationShade", first.focus());
        assertNull(first.occluded(), "whether an app is in front was not said");
        assertTrue(first.toString().contains("SECURE LOCK SCREEN"), first.toString());

        assertEquals(new PhoneRig.Keyguard(PhoneRig.Lock.SECURE, false, "NotificationShade"), PhoneRig.Keyguard.read(RecordingAdb.WINDOW_SECURE_LOCK, RecordingAdb.LOCK_SETTINGS_PIN));
        assertEquals(PhoneRig.Lock.SWIPE, PhoneRig.Keyguard.read(RecordingAdb.WINDOW_SECURE_LOCK, RecordingAdb.LOCK_SETTINGS_NONE).lock(), "the same lock screen with nothing to enter");
        PhoneRig.Keyguard open = PhoneRig.Keyguard.read(RecordingAdb.window(false, null, BENCH + MAIN), RecordingAdb.LOCK_SETTINGS_PIN);
        assertEquals(PhoneRig.Lock.UNLOCKED, open.lock(), "a phone with a PIN that is unlocked now");
        assertEquals(BENCH + MAIN, open.focus());
        for (String kind : List.of("PATTERN", "PASSWORD", "Pin", "PASSWORD_OR_PIN")) {
            assertEquals(PhoneRig.Lock.SECURE, PhoneRig.Keyguard.read(RecordingAdb.WINDOW_SECURE_LOCK, "CredentialType: " + kind).lock(), kind);
        }

        // the app in front of its lock screen, as when a PC has woken it
        PhoneRig.Keyguard over = PhoneRig.Keyguard.read(RecordingAdb.window(true, true, BENCH + MAIN), RecordingAdb.LOCK_SETTINGS_PIN);
        assertEquals(new PhoneRig.Keyguard(PhoneRig.Lock.SECURE, true, BENCH + MAIN), over);
        assertTrue(over.toString().contains("an app in front of it"), over.toString());

        // other Androids' words for the same facts
        String delegate = "    KeyguardServiceDelegate\n      showing=true\n      showingAndNotOccluded=true\n      inputRestricted=true\n      occluded=false\n      secure=%s\n";
        assertEquals(new PhoneRig.Keyguard(PhoneRig.Lock.SECURE, false, ""), PhoneRig.Keyguard.read(String.format(delegate, "true"), ""));
        assertEquals(PhoneRig.Lock.SWIPE, PhoneRig.Keyguard.read(String.format(delegate, "false"), "").lock());
        assertEquals(PhoneRig.Lock.SECURE, PhoneRig.Keyguard.read(String.format(delegate, "false"), "CredentialType: PIN").lock(), "the phone's own word on its lock goes first");
        assertEquals(PhoneRig.Lock.UNLOCKED, PhoneRig.Keyguard.read("  mKeyguardShowing=false\n      showingAndNotOccluded=true\n", "CredentialType: PIN").lock());
        assertEquals(PhoneRig.Lock.UNLOCKED, PhoneRig.Keyguard.read("      showingAndNotOccluded=false\n      showing=false\n", "").lock(), "showingAndNotOccluded is not showing");
        assertEquals(PhoneRig.Lock.SECURE, PhoneRig.Keyguard.read("  mDreamingLockscreen=true\n  mCurrentFocus=Window{1 u0 NotificationShade}\n", "").lock(), "no plainer word than these two");

        // a lock screen of which nobody says whether it is secure: taken as secure - nothing is sent to it
        assertEquals(PhoneRig.Lock.SECURE, PhoneRig.Keyguard.read("  isKeyguardShowing=true\n", "").lock());
        // and words that say nothing of it
        for (String nothing : new String[]{"", "error: device offline", "Can't find service: window", "  mCurrentFocus=Window{1 u0 NotificationShade}\n"}) {
            assertEquals(PhoneRig.Lock.NOT_KNOWN, PhoneRig.Keyguard.read(nothing, "CredentialType: PIN").lock(), nothing);
        }
        assertEquals(PhoneRig.Lock.NOT_KNOWN, PhoneRig.Keyguard.read(null, null).lock());
    }

    @Test
    void atEachLaunchTheFilmIsToldWhichOfTheThreeItIs(@TempDir Path dir) throws Exception {
        // unlocked: looked at, nothing sent to it
        RecordingAdb open = RecordingAdb.aPhone();
        Film film = new Film(dir.resolve("open"), "open");
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), open, film);
        rig.launch();
        assertEquals(PhoneRig.Lock.UNLOCKED, rig.lock());
        assertTrue(remarks(film).contains("the phone's screen at this launch: unlocked"), remarks(film));
        assertFalse(open.sent.contains("shell wm dismiss-keyguard"));

        // a swipe: sent away as a hand does, and then it is unlocked
        RecordingAdb swipe = RecordingAdb.aPhone().says("shell dumpsys lock_settings", RecordingAdb.LOCK_SETTINGS_NONE)
                .says("shell dumpsys window", RecordingAdb.window(true, false, "NotificationShade"), RecordingAdb.window(false, null, BENCH + MAIN));
        film = new Film(dir.resolve("swipe"), "swipe");
        rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), swipe, film);
        rig.launch();
        assertEquals(List.of("shell date '+%m-%d %H:%M:%S.000'", "shell input keyevent KEYCODE_WAKEUP", "shell dumpsys window", "shell dumpsys lock_settings",
                "shell wm dismiss-keyguard", "shell dumpsys window", "shell am start -n " + BENCH + MAIN), swipe.sent);
        assertEquals(PhoneRig.Lock.UNLOCKED, rig.lock());
        assertTrue(remarks(film).contains("nothing to enter was showing: swiped away"), remarks(film));

        // a secure one: woken, looked at - and left alone. The app is opened behind it
        RecordingAdb locked = RecordingAdb.aLockedPhone();
        film = new Film(dir.resolve("locked"), "locked");
        rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), locked, film);
        rig.launch();
        assertEquals(List.of("shell date '+%m-%d %H:%M:%S.000'", "shell input keyevent KEYCODE_WAKEUP", "shell dumpsys window", "shell dumpsys lock_settings",
                "shell am start -n " + BENCH + MAIN), locked.sent);
        assertEquals(PhoneRig.Lock.SECURE, rig.lock());
        assertTrue(remarks(film).contains("A SECURE LOCK SCREEN") && remarks(film).contains("the app is behind it"), remarks(film));
        rig.launch(); // and again at the next launch (the kind of lock is asked once: the bench never changes it)
        assertEquals(List.of("shell date '+%m-%d %H:%M:%S.000'", "shell input keyevent KEYCODE_WAKEUP", "shell dumpsys window", "shell am start -n " + BENCH + MAIN),
                locked.since(5));
        assertEquals(2, remarks(film).split("A SECURE LOCK SCREEN", -1).length - 1, "said at each launch");
        // only the word for the kind of lock is kept: what else the phone says there is not in the film
        assertFalse(remarks(film).contains("SP Handle") || remarks(film).contains("4f1a2b3c"), remarks(film));

        // the phone's words not understood: nothing is sent to its lock, and the film says that it is not known
        RecordingAdb odd = RecordingAdb.aPhone().says("shell dumpsys window", "Can't find service: window");
        film = new Film(dir.resolve("odd"), "odd");
        rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), odd, film);
        rig.launch();
        assertEquals(PhoneRig.Lock.NOT_KNOWN, rig.lock());
        assertFalse(odd.sent.contains("shell wm dismiss-keyguard"));
        assertTrue(remarks(film).contains("not known"), remarks(film));
    }

    // ------------------------------------------------------------------ a run behind a secure lock screen

    @Test
    void behindASecureLockScreenTheRunTakesTheLogsWordAndListsWhatItSkipped(@TempDir Path dir) throws Exception {
        RecordingAdb adb = RecordingAdb.aLockedPhone().says("shell pidof", "4711").says("shell dumpsys activity services", SERVICE)
                .says("shell logcat -d -v time -T", LOG).says("shell am broadcast", STATS).says("shell date", "11-22 00:50:52.000");
        Film film = new Film(dir.resolve("locked-run"), "locked-run");
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), adb, film);
        rig.launch();
        int from = adb.count();

        // "for DRAUPNE Link" cannot be read: the app is up by its process
        rig.untilAppIsUp(15_000);
        assertEquals(List.of("exec-out uiautomator dump /dev/tty", "shell dumpsys window", "shell pidof " + BENCH), adb.since(from));

        // START cannot be looked for or pressed: the service runs by Android's word and the app's own log
        from = adb.count();
        rig.startIfAsked();
        assertEquals(List.of("exec-out uiautomator dump /dev/tty", "shell dumpsys window", "shell pidof " + BENCH, "shell dumpsys activity services " + BENCH,
                "shell logcat -d -v time -T '11-22 00:50:52.000' -s DDLog:I"), adb.since(from));

        // "Connected to PC" cannot be read: the bench build says it
        List<String> checks = new ArrayList<>();
        rig.checkConnectedToPc((ok, what) -> checks.add((ok ? "ok: " : "WRONG: ") + what), "the phone's screen says: Connected to PC");
        assertEquals(List.of("ok: the phone's screen says: Connected to PC - behind the lock screen, by the bench build's own word"), checks);
        assertTrue(rig.flightControllerWord().contains("1 = no flight controller"), rig.flightControllerWord());
        assertFalse(rig.screenIsTheApps());

        // what only the screen could show is skipped by name
        rig.skip("the touch lock: LOCK SCREEN pressed, SCREEN LOCKED read, a touch ignored");
        // ... and the bench build saying no is a failed check, not a skipped one
        adb.says("shell am broadcast", STATS.replace("pc=connected", "pc=none"));
        rig.checkConnectedToPc((ok, what) -> checks.add((ok ? "ok: " : "WRONG: ") + what), "after the cut");
        assertEquals("WRONG: after the cut - behind the lock screen, by the bench build's own word", checks.get(1));

        // nothing of all this touched the lock screen: no finger, no key but WAKEUP, nothing typed, no dismissal
        for (String sent : adb.sent) {
            assertFalse(sent.contains("input tap") || sent.contains("input swipe") || sent.contains("input text") || sent.contains("dismiss-keyguard")
                    || sent.contains("locksettings") || sent.contains("keyevent") && !sent.endsWith("KEYCODE_WAKEUP"), sent);
        }
        // a finger on the lock screen's own buttons, or at a fixed place on it, is refused - its number pad is buttons too
        int before = adb.count();
        assertTrue(assertThrows(PhoneRig.Refused.class, () -> rig.tap("00")).getMessage().contains("com.android.systemui"));
        assertThrows(PhoneRig.Refused.class, () -> rig.hold("51", 800));
        assertThrows(PhoneRig.Refused.class, () -> rig.tapAt(540, 1200));
        assertThrows(PhoneRig.Refused.class, rig::scrollDown);
        assertFalse(adb.since(before).stream().anyMatch(c -> c.startsWith("shell input")), adb.since(before).toString());
        assertThrows(PhoneRig.Refused.class, () -> rig.shell("input text 1234"));
        assertThrows(PhoneRig.Refused.class, () -> rig.shell("input keyevent KEYCODE_1"));

        assertEquals(List.of("the touch lock: LOCK SCREEN pressed, SCREEN LOCKED read, a touch ignored"), rig.skipped());
        assertEquals(4, rig.otherWays().size(), rig.otherWays().toString());
        rig.close();
        String said = remarks(film);
        assertTrue(said.contains("ON THE REAL PHONE 1 CHECK(S) WERE SKIPPED in this run") && said.contains("the touch lock: LOCK SCREEN pressed"), said);
        assertTrue(said.contains("BEHIND THE LOCK SCREEN 4 fact(s) were taken another way") && said.contains("Start receiver thread - OK") && said.contains("pc=connected"), said);
        assertTrue(said.contains("SKIPPED in this run: the touch lock"), "said when it was skipped, too");
        rig.close();
        assertEquals(1, remarks(film).split("WERE SKIPPED", -1).length - 1, "listed once");
    }

    @Test
    void whatCannotBeKnownBehindTheLockScreenIsSkippedOrStopsTheScenarioNeverPassed(@TempDir Path dir) throws Exception {
        // the released app has no bench receiver: "Connected to PC" is skipped by name
        RecordingAdb adb = RecordingAdb.aLockedPhone().says("shell pidof", "4711").says("shell dumpsys activity services", SERVICE.replace(BENCH, PhoneRig.APP));
        Film film = new Film(dir.resolve("released"), "released");
        PhoneRig released = rig(PhoneRigGuardTest.phone(CABLE, PhoneRig.APP, false), adb, film);
        released.launch();
        List<String> checks = new ArrayList<>();
        released.checkConnectedToPc((ok, what) -> checks.add(what), "the phone's screen says: Connected to PC");
        assertEquals(List.of(), checks, "no verdict either way");
        assertEquals(1, released.skipped().size());
        assertTrue(released.skipped().get(0).startsWith("the phone's screen says: Connected to PC (a lock screen is over the app, and the released app has no bench receiver"), released.skipped().toString());
        assertTrue(released.flightControllerWord().contains("not known"));
        assertFalse(adb.sent.stream().anyMatch(c -> c.contains("am broadcast")), "and nothing is asked of a receiver that is not there");
        released.startIfAsked(); // its service runs: Android's word is enough, with or without the log's
        released.close();
        assertTrue(remarks(film).contains("1 CHECK(S) WERE SKIPPED"), remarks(film));

        // the service did not start by itself, and START cannot be pressed: the scenario stops and says so
        RecordingAdb down = RecordingAdb.aLockedPhone().says("shell pidof", "");
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), down, null);
        rig.launch();
        AssertionError stopped = assertThrows(AssertionError.class, rig::startIfAsked);
        assertTrue(stopped.getMessage().contains("START cannot be looked for or pressed") && stopped.getMessage().contains("SECURE LOCK SCREEN"), stopped.getMessage());
        // ... and the app is not "up" either: the wait ends as a wait for the screen's words does
        AssertionError notUp = assertThrows(AssertionError.class, () -> rig.untilAppIsUp(3000));
        assertTrue(notUp.getMessage().contains("for DRAUPNE Link") && notUp.getMessage().contains("its process"), notUp.getMessage());

        // the other DRAUPNE Flight holds the link port (the first night on the phone had it): said in those words
        RecordingAdb taken = RecordingAdb.aLockedPhone().says("shell pidof", "4711").says("shell dumpsys activity services", "").says("shell date", "11-22 00:47:20.000")
                .says("shell logcat -d -v time -T", "11-22 00:47:21.400 I/DDLog   (24585): socketInit error: java.net.BindException: bind failed: EADDRINUSE (Address already in use)\n"
                        + "11-22 00:47:21.401 I/DDLog   (24585): UDP initialize error.\n");
        PhoneRig second = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), taken, null);
        second.launch();
        assertTrue(assertThrows(AssertionError.class, second::startIfAsked).getMessage().contains("One of the two at a time"));
    }

    @Test
    void theAppInFrontOfTheLockScreenIsReadAsEverAndWhatIsSeenOfItIsFiled(@TempDir Path dir) throws Exception {
        // woken by a PC the app puts itself in front of its lock screen: the screen's description is the app's again
        RecordingAdb adb = RecordingAdb.aPhone().says("shell dumpsys window", RecordingAdb.window(true, true, BENCH + MAIN))
                .says("exec-out uiautomator", RecordingAdb.screen(BENCH, "DRAUPNE Flight BENCH", "2.3.0-pc.55-bench · for DRAUPNE Link", "Connected to PC"))
                .says("shell date", "11-22 01:09:00.000").says("shell logcat -d -v time -T", "11-22 01:10:00.100 I/DDLog   ( 4711): Deep sleep: screen locked\n11-22 01:12:00.300 I/DDLog   ( 4711): Opened to wake the phone: over the lock screen\n");
        Film film = new Film(dir.resolve("over"), "over");
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), adb, film);
        rig.launch();
        assertEquals(PhoneRig.Lock.SECURE, rig.lock());
        int from = adb.count();
        List<String> checks = new ArrayList<>();
        rig.checkConnectedToPc((ok, what) -> checks.add(ok + " " + what), "the phone's screen says: Connected to PC");
        assertEquals(List.of("true the phone's screen says: Connected to PC"), checks, "by the screen's own words");
        assertEquals(List.of("exec-out uiautomator dump /dev/tty"), adb.since(from), "the one look at the screen: no other way was needed");
        assertTrue(rig.screenIsTheApps());
        rig.untilAppIsUp(5000);
        assertEquals(List.of(), rig.otherWays());
        assertEquals(List.of(), rig.skipped());

        rig.lookOverTheLock("woken from its deep sleep by PILOT UAV");
        String said = remarks(film);
        assertTrue(said.contains("TO LOOK AT - the app in front of the lock screen, woken from its deep sleep by PILOT UAV"), said);
        assertTrue(said.contains("its log says \"Opened to wake the phone\"") && said.contains("the app locked the screen itself"), said);
        assertTrue(said.contains("an app in front of it") && said.contains("is the app's [DRAUPNE Flight BENCH"), said);

        // the same look when nothing came in front: reported as it is, never a failure
        RecordingAdb behind = RecordingAdb.aLockedPhone().says("shell logcat -d -v time -T", "");
        film = new Film(dir.resolve("not-over"), "not-over");
        PhoneRig still = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), behind, film);
        still.launch();
        still.lookOverTheLock("woken by PILOT UAV");
        assertTrue(remarks(film).contains("its log does not say \"Opened to wake the phone\"") && remarks(film).contains("is not the app's [00, 51"), remarks(film));
        assertEquals(List.of(), still.skipped());
    }

    // ------------------------------------------------------------------ the kind of lock, said at the start (F-083)

    /**
     * F-083: the app opening itself was tried on a phone with screen lock None (the evening of 2026-11-22) and failed;
     * with a PIN (the morning) it had worked. The film said "unlocked" both times - the kind of lock was asked of the
     * phone but not said. Now it is, at the start, whatever the screen shows.
     */
    @Test
    void theKindOfScreenLockIsSaidAtTheStartEvenWhenThePhoneIsFoundUnlocked(@TempDir Path dir) throws Exception {
        // a PIN, found unlocked: asked once, and said
        RecordingAdb pin = RecordingAdb.aPhone();
        Film film = new Film(dir.resolve("pin"), "pin");
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), pin, film);
        rig.ensureRunning();
        assertEquals(PhoneRig.Lock.UNLOCKED, rig.lock());
        assertEquals("PIN", rig.screenLock());
        assertTrue(remarks(film).contains("The phone's screen lock: PIN"), remarks(film));
        assertFalse(remarks(film).contains("NOT LIKE A DRONE'S PHONE"), remarks(film));
        assertEquals(1, pin.sent.stream().filter("shell dumpsys lock_settings"::equals).count(), "asked once");
        assertFalse(remarks(film).contains("SP Handle") || remarks(film).contains("4f1a2b3c"), "only the word for the kind is in the film");
        rig.close();

        // nothing to enter (None or a swipe), found unlocked: said, and that a drone's phone is not like it
        RecordingAdb none = RecordingAdb.aPhone().says("shell dumpsys lock_settings", RecordingAdb.LOCK_SETTINGS_NONE);
        film = new Film(dir.resolve("none"), "none");
        rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), none, film);
        rig.ensureRunning();
        assertEquals(PhoneRig.Lock.UNLOCKED, rig.lock());
        assertEquals("NONE", rig.screenLock());
        assertTrue(remarks(film).contains("The phone's screen lock: NONE") && remarks(film).contains("NOT LIKE A DRONE'S PHONE"), remarks(film));
        rig.close();

        // the phone does not say: said so, nothing guessed
        RecordingAdb odd = RecordingAdb.aPhone().says("shell dumpsys lock_settings", "Can't find service: lock_settings");
        film = new Film(dir.resolve("odd"), "odd");
        rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), odd, film);
        rig.ensureRunning();
        assertEquals("", rig.screenLock());
        assertTrue(remarks(film).contains("The phone's screen lock: not known"), remarks(film));
        rig.close();
    }

    // ------------------------------------------------------------------ a phone found unlocked is kept awake

    private static final String NOTE = "shell settings get global draupne_bench_stay_on_was", OWN = "shell settings get global stay_on_while_plugged_in";

    @Test
    void aPhoneFoundUnlockedIsKeptAwakeAndItsOwnSettingPutBackExactly(@TempDir Path dir) throws Exception {
        // its own setting 0: noted on the phone, stay-on for USB set, and 0 put back at close
        RecordingAdb adb = RecordingAdb.aPhone().says(NOTE, "null", "0").says(OWN, "0");
        Film film = new Film(dir.resolve("awake"), "awake");
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), adb, film);
        rig.ensureRunning();
        assertEquals(List.of("get-state", "shell getprop sys.boot_completed", "shell dumpsys battery", "shell input keyevent KEYCODE_WAKEUP", "shell dumpsys window",
                "shell dumpsys lock_settings", NOTE, OWN, "shell settings put global draupne_bench_stay_on_was 0", "shell svc power stayon usb"), adb.sent);
        assertTrue(remarks(film).contains("kept awake while on USB for this run") && remarks(film).contains("own setting (0)"), remarks(film));
        int from = adb.count();
        rig.close();
        assertEquals(List.of("shell dumpsys deviceidle unforce", "shell dumpsys battery reset", NOTE, "shell settings put global stay_on_while_plugged_in 0",
                "shell settings delete global draupne_bench_stay_on_was"), adb.since(from));

        // never set on this phone: back to "never set", not to a 0 that was not there
        adb = RecordingAdb.aPhone().says(NOTE, "null", "unset").says(OWN, "null");
        rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), adb, null);
        rig.ensureRunning();
        assertTrue(adb.sent.containsAll(List.of("shell settings put global draupne_bench_stay_on_was unset", "shell svc power stayon usb")), adb.sent.toString());
        from = adb.count();
        rig.close();
        assertEquals(List.of("shell dumpsys deviceidle unforce", "shell dumpsys battery reset", NOTE, "shell settings delete global stay_on_while_plugged_in",
                "shell settings delete global draupne_bench_stay_on_was"), adb.since(from));

        // it stays awake on USB already (the pilot's own choice: 2, or 3, 7, 15 with other chargers): nothing is touched.
        // Nor when what it is set to cannot be read: what would have to be put back is then not known
        for (String own : List.of("2", "3", "7", "15", "", "error: closed", "abc")) {
            adb = RecordingAdb.aPhone().says(OWN, own);
            rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), adb, null);
            rig.ensureRunning();
            assertFalse(adb.sent.stream().anyMatch(c -> c.contains("settings put") || c.contains("svc power")), own + ": " + adb.sent);
            from = adb.count();
            rig.close();
            assertEquals(List.of("shell dumpsys deviceidle unforce", "shell dumpsys battery reset", NOTE), adb.since(from), own);
        }

        // on mains only (1): USB is added for the run by the one word there is for it, and 1 comes back
        adb = RecordingAdb.aPhone().says(NOTE, "null", "1").says(OWN, "1");
        rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), adb, null);
        rig.ensureRunning();
        rig.close();
        assertTrue(adb.sent.contains("shell settings put global stay_on_while_plugged_in 1"), adb.sent.toString());

        // every one of these is on the guard's list for a real phone - and went past it
        assertEquals(List.of(), rig.refusals());
        for (String note : List.of("0", "1", "15", "unset")) {
            for (List<String> order : PhoneRig.stayOnBack(note)) {
                assertTrue(PhoneGuard.judge(PhoneRigGuardTest.phone(CABLE, PhoneRig.APP, false), order, p -> null).allowed(), order.toString());
            }
            assertEquals(2, PhoneRig.stayOnBack(note).size(), note);
        }
        for (String none : new String[]{"null", "", " ", "x", "99", "-1", null}) assertEquals(List.of(), PhoneRig.stayOnBack(none), "no note, nothing put back: " + none);
    }

    @Test
    void aRunThatWasKilledLeavesItsNoteAndTheNextRunOrThePhoneResetPutsThePhonesOwnValueBack() throws Exception {
        // the note of a killed run is the phone's own value (the setting itself is the bench's 2 by now): kept, not written over
        RecordingAdb adb = RecordingAdb.aPhone().says(NOTE, "0").says(OWN, "2");
        PhoneRig rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), adb, null);
        rig.ensureRunning();
        assertFalse(adb.sent.stream().anyMatch(c -> c.startsWith("shell settings put global draupne_bench_stay_on_was")), "the note is not written over: " + adb.sent);
        assertFalse(adb.sent.contains(OWN), "and what is set now is not taken for the phone's own");
        assertTrue(adb.sent.contains("shell svc power stayon usb"));
        rig.close();
        assertTrue(adb.sent.contains("shell settings put global stay_on_while_plugged_in 0"), adb.sent.toString());

        // found locked: nothing is kept awake - but a note a killed run left is still put back when this one closes
        adb = RecordingAdb.aLockedPhone().says(NOTE, "0");
        rig = rig(PhoneRigGuardTest.phone(CABLE, BENCH, false), adb, null);
        rig.ensureRunning();
        assertFalse(adb.sent.stream().anyMatch(c -> c.contains("svc power") || c.startsWith("shell settings")), "a locked phone is left to its timeout: " + adb.sent);
        rig.close();
        assertTrue(adb.sent.containsAll(List.of("shell settings put global stay_on_while_plugged_in 0", "shell settings delete global draupne_bench_stay_on_was")), adb.sent.toString());

        // gradlew phoneReset, after a run killed outright: the same, from the note on the phone
        List<String> said = new ArrayList<>();
        adb = RecordingAdb.aPhone().says(NOTE, "unset").says(OWN, "null").says("shell cmd connectivity airplane-mode", "disabled");
        assertTrue(PhoneReset.run(adb, said::add));
        assertEquals(List.of("get-state", "shell dumpsys deviceidle unforce", "shell dumpsys battery reset", NOTE, "shell settings delete global stay_on_while_plugged_in",
                "shell settings delete global draupne_bench_stay_on_was"), adb.sent.subList(0, 6));
        assertTrue(said.contains("done    shell settings delete global stay_on_while_plugged_in"), said.toString());
        assertTrue(said.stream().anyMatch(l -> l.startsWith("stay awake while plugged in: unset")), said.toString());

        // no note: the phone's setting is its own, and is only read
        said.clear();
        adb = RecordingAdb.aPhone().says(OWN, "2");
        assertTrue(PhoneReset.run(adb, said::add));
        assertFalse(adb.sent.stream().anyMatch(c -> c.contains("settings put") || c.contains("settings delete") || c.contains("svc power")), adb.sent.toString());
        assertTrue(said.contains("stay awake while plugged in: the bench has no note of having changed it - left as it is"), said.toString());
        assertTrue(said.stream().anyMatch(l -> l.startsWith("stay awake while plugged in: 2 ")), said.toString());
    }
}
