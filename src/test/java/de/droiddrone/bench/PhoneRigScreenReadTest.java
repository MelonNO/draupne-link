package de.droiddrone.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A phone's screen that uiautomator cannot describe (the Android 11 emulator, 2026-11-22: "ERROR: could not get idle
 * state." on every read of the app's own screen, which changes every second). Until then the rig took it for an
 * empty screen and said nothing: the scenarios failed on "On it: []". Now uiautomator's own words go into the film,
 * and on the emulator what the lock-screen path already takes another way is taken so, the film told which way. A
 * real phone does as before but for the words in the film.
 */
class PhoneRigScreenReadTest {
    private static final String APP = BenchControl.PACKAGE, MAIN = "/de.droiddrone.flight.MainActivity";
    private static final String IDLE = "ERROR: could not get idle state.";
    private static final String STATS = "Broadcasting: Intent { act=de.droiddrone.flight.bench.STATS }\nBroadcast completed: result=1, "
            + "data=\"DRAUPNE-BENCH-FC stats: chosen=usb link=down serialStatus=1 service=running pc=connected pid=4711\"";
    private static final String SERVICE = "  * ServiceRecord{1 u0 " + APP + "/de.droiddrone.flight.DDService}\n    app=ProcessRecord{2 4711:" + APP + "/u0a30}";
    private static final String DUMP = "exec-out uiautomator dump /dev/tty";

    private final long[] now = {1_000_000};
    private final List<Long> pauses = new ArrayList<>();

    private PhoneRig rig(PhoneRig.Target target, RecordingAdb adb, Film film) {
        return new PhoneRig(film, target, adb, ms -> {
            pauses.add(ms);
            now[0] += ms;
        }, () -> now[0], film.dir());
    }

    private static String remarks(Film film) throws Exception {
        Path file = film.dir().resolve("remarks.txt");
        return Files.exists(file) ? Files.readString(file) : "";
    }

    /** The Android 11 emulator: the app up, its service running, a PC connected - and no read of its screen. */
    private static RecordingAdb anUnreadableEmulator() {
        return new RecordingAdb().says("exec-out uiautomator", IDLE + "\n").says("shell pidof", "4711\n").says("shell dumpsys window", RecordingAdb.window(false, null, APP + MAIN))
                .says("shell dumpsys activity services", SERVICE).says("shell am broadcast", STATS);
    }

    @Test
    void aScreenThatCannotBeReadIsSaidInUiautomatorsOwnWordsNotTakenForAnEmptyOne(@TempDir Path dir) throws Exception {
        RecordingAdb adb = new RecordingAdb().says("exec-out uiautomator", IDLE + "\r\n", IDLE + "\n", IDLE + "\n", IDLE + "\n", IDLE + "\n", IDLE + "\n",
                RecordingAdb.screen(APP, "2.3.0-pc.58 · for DRAUPNE Link"));
        Film film = new Film(dir.resolve("idle"), "idle");
        PhoneRig rig = rig(PhoneRig.Target.emulator(), adb, film);

        PhoneRig.Reading r = rig.reading();
        assertFalse(r.read());
        assertEquals(IDLE, r.error());
        assertEquals(List.of(), r.nodes());
        assertEquals(List.of(DUMP, DUMP, DUMP), adb.sent, "three tries, as before");
        assertEquals(List.of(700L, 700L, 700L), pauses);
        assertTrue(remarks(film).contains("The phone's screen could not be read (3 tries): uiautomator says \"" + IDLE + "\""), remarks(film));

        assertEquals(List.of(), rig.texts()); // the same words again: not said again
        assertEquals(1, remarks(film).split("could not be read", -1).length - 1, remarks(film));

        assertTrue(rig.sees("for DRAUPNE Link"), "read again");
        assertTrue(remarks(film).contains("The phone's screen could be read again, after 2 read(s) that failed"), remarks(film));

        assertEquals("(nothing)", PhoneRig.uiautomatorSaid(""));
        assertEquals("(nothing)", PhoneRig.uiautomatorSaid(null));
        assertEquals("Killed", PhoneRig.uiautomatorSaid("\n Killed \r\n"));
        assertEquals(303, PhoneRig.uiautomatorSaid("x".repeat(400)).length());
    }

    @Test
    void onTheEmulatorTheChecksTakeWhatTheLockScreenPathTakesAndTheFilmSaysWhichWay(@TempDir Path dir) throws Exception {
        RecordingAdb adb = anUnreadableEmulator();
        Film film = new Film(dir.resolve("a30"), "a30");
        PhoneRig rig = rig(PhoneRig.Target.emulator(), adb, film);

        // "for DRAUPNE Link" cannot be read: the app is up by its process and its window in front
        rig.untilAppIsUp(15_000);
        assertEquals(List.of(DUMP, DUMP, DUMP, "shell pidof " + APP, "shell dumpsys window"), adb.since(0));

        // START cannot be looked for: the service started by itself, by Android's word
        int from = adb.count();
        rig.startIfAsked();
        assertEquals(List.of(DUMP, DUMP, DUMP, "shell pidof " + APP, "shell dumpsys activity services " + APP), adb.since(from));
        assertFalse(adb.sent.stream().anyMatch(c -> c.startsWith("shell input")), "nothing pressed");

        // "Connected to PC" and "No flight controller on USB": the bench build's word
        List<String> checks = new ArrayList<>();
        rig.checkConnectedToPc((ok, what) -> checks.add((ok ? "ok: " : "WRONG: ") + what), "the phone's screen says: Connected to PC");
        rig.checkNoFlightController((ok, what) -> checks.add((ok ? "ok: " : "WRONG: ") + what), "it says there is no flight controller on USB");
        assertEquals(List.of("ok: the phone's screen says: Connected to PC - the screen could not be read, by the bench build's own word",
                "ok: it says there is no flight controller on USB - the screen could not be read, by the bench build's serial status"), checks);
        assertEquals("(the screen could not be read, the bench build's serial status) 1 = no flight controller", rig.flightControllerWord());
        adb.says("shell am broadcast", STATS.replace("pc=connected", "pc=none").replace("serialStatus=1", "serialStatus=5"));
        rig.checkConnectedToPc((ok, what) -> checks.add((ok ? "ok: " : "WRONG: ") + what), "after the cut");
        rig.checkNoFlightController((ok, what) -> checks.add((ok ? "ok: " : "WRONG: ") + what), "no FC");
        assertEquals("WRONG: after the cut - the screen could not be read, by the bench build's own word", checks.get(2), "the bench build saying no is a failed check");
        assertTrue(checks.get(3).startsWith("WRONG: no FC"), checks.get(3));

        assertEquals(6, rig.otherWays().size(), rig.otherWays().toString());
        assertEquals(List.of(), rig.skipped());
        rig.close();
        String said = remarks(film);
        assertTrue(said.contains("uiautomator says \"" + IDLE + "\"): the app is up - known by its process in Android's list and its window in front (" + APP + MAIN + ")"), said);
        assertTrue(said.contains("the service runs and listens for a PC (no START to press) - known by Android's list of living services"), said);
        assertTrue(said.contains("a PC is connected - known by the bench build's own word (pc=connected)"), said);
        assertTrue(said.contains("THE PHONE'S SCREEN COULD NOT BE READ: 6 fact(s) were taken another way"), said);
        rig.close();
        assertEquals(1, remarks(film).split("COULD NOT BE READ:", -1).length - 1, "listed once");
    }

    @Test
    void whatCannotBeHadAnotherWayFailsSayingWhyNeverPasses(@TempDir Path dir) throws Exception {
        // the app's process there, but another window in front (Android's permission question): not up, and the wait says why
        RecordingAdb asking = anUnreadableEmulator().says("shell dumpsys window", RecordingAdb.window(false, null,
                "com.android.permissioncontroller/com.android.packageinstaller.permission.ui.GrantPermissionsActivity"));
        Film film = new Film(dir.resolve("asking"), "asking");
        PhoneRig rig = rig(PhoneRig.Target.emulator(), asking, film);
        AssertionError notUp = assertThrows(AssertionError.class, () -> rig.untilAppIsUp(3000));
        assertTrue(notUp.getMessage().contains("for DRAUPNE Link") && notUp.getMessage().contains(IDLE) && notUp.getMessage().contains("its window in front"),
                notUp.getMessage());
        // the released app's package is a prefix of the bench build's: its window is not taken for the released app's
        RecordingAdb bench = anUnreadableEmulator();
        PhoneRig released = rig(PhoneRig.Target.emulator().flying(PhoneRig.APP), bench, new Film(dir.resolve("prefix"), "prefix"));
        assertThrows(AssertionError.class, () -> released.untilAppIsUp(1000));

        // no service by itself, and START cannot be looked for: the scenario stops and says so
        RecordingAdb down = anUnreadableEmulator().says("shell pidof", "");
        PhoneRig stopped = rig(PhoneRig.Target.emulator(), down, new Film(dir.resolve("down"), "down"));
        String said = assertThrows(AssertionError.class, stopped::startIfAsked).getMessage();
        assertTrue(said.contains("START cannot be looked for or pressed") && said.contains(IDLE), said);

        // the released app has no bench receiver: "Connected to PC" fails with the reason, nothing asked of a receiver
        List<String> checks = new ArrayList<>();
        released.checkConnectedToPc((ok, what) -> checks.add((ok ? "ok: " : "WRONG: ") + what), "connected");
        assertEquals(List.of("WRONG: connected (the phone's screen could not be read: uiautomator says \"" + IDLE
                + "\"), and the released app has no bench receiver to ask instead"), checks);
        assertFalse(bench.sent.stream().anyMatch(c -> c.contains("am broadcast")));
    }

    @Test
    void aRealPhoneDoesAsBeforeButForUiautomatorsWordsInTheFilm(@TempDir Path dir) throws Exception {
        // unlocked, the app's screen not readable: no other way is taken - it waits for the words, as before
        RecordingAdb adb = RecordingAdb.aPhone().says("exec-out uiautomator", IDLE).says("shell pidof", "4711");
        Film film = new Film(dir.resolve("phone"), "phone");
        PhoneRig rig = rig(PhoneRigGuardTest.phone(PhoneRigGuardTest.CABLE, APP, false), adb, film);
        int from = adb.count();
        AssertionError notUp = assertThrows(AssertionError.class, () -> rig.untilAppIsUp(600));
        assertTrue(notUp.getMessage().contains("for DRAUPNE Link"), notUp.getMessage());
        List<String> sent = adb.since(from);
        assertEquals(List.of(DUMP, DUMP, DUMP, "shell dumpsys window", "shell dumpsys lock_settings"), sent.subList(0, 5));
        assertFalse(sent.contains("shell pidof " + APP), "the app's process is behind a lock screen's way only: " + sent);
        assertTrue(remarks(film).contains("uiautomator says \"" + IDLE + "\""), remarks(film));
        assertEquals(List.of(), rig.otherWays());
    }
}
