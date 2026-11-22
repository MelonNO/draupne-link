package de.droiddrone.bench.phone;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.BadLink;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.PhoneWords;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.common.CrashRestarts;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The phone app ended by an error (F-047). In the bench's night of 2026-11-20 one exception on the camera's thread
 * ended the whole app, Android planned its service's restart for 77 minutes later, and the link stayed dead until
 * the bench started the app again - on a drone nobody can.
 * <p>
 * Here the real app is flown by the real Windows app and ended on purpose, the way Android ends an app with an error
 * ({@code am crash}); then, asleep with a check every minute, its process is killed outright, as a phone maker's
 * battery saver does. Each time it must be back by itself and the PC must have its link again without anyone
 * touching the phone. And an app that keeps ending is not opened for ever: after {@link CrashRestarts#MAX} times in
 * ten minutes it stays down.
 * <p>
 * {@code BENCH_KEEP_APP=1} leaves the app on the phone as it is.
 * <p>
 * On a real phone too (the task benchHwPhone), with the bench build: there is no root there, so the bench build's own
 * receiver throws the error, ends the process and forgets the crash guard's count ({@code BenchControl}). And a phone
 * on adb's cable charges, which keeps Android from dozing: while it sleeps here it is pretended off the cable and
 * forced into deep doze ({@code PhoneRig.onBattery} - what that does not prove is written there), taken back when it
 * has been woken.
 * <p>
 * Behind a real phone's secure lock screen nothing of it is lost - and it is the way a drone's phone meets it: the
 * new process, the link back, the picture, the app's log lines (what ended it, that it opens again, the sleep check's
 * alarm, "Opened to wake the phone", the limit), PILOT UAV reaching it. The app then puts itself in front of the lock
 * screen by itself; what adb can see of that - the window in front, whether the screen's description is the app's -
 * is filed in the film under TO LOOK AT and not judged: whether a phone of this make shows it to adb has to be seen
 * once. (A phone that starts unlocked is locked by the app itself at its first reopening or deep sleep.)
 */
@Tag("emulator")
@Tag("hwphone")
class PhoneCrashScenario {
    /**
     * Each error of the last part comes this long after the one before it. Android has a limit of its own, sooner than
     * the app's: an app that an error ends twice within two minutes is "a bad process", and nothing of it is started
     * any more, its alarm included, until a hand opens it (this scenario's first run, 2026-11-20).
     */
    static final long ERROR_SPACING_MS = 135_000;

    /**
     * How long to wait before the next error, the last one at {@code lastErrorAt}: counted from the LAST error. The
     * loop counted from the first error of the run, and once the parts before it had taken long its errors came close
     * together (the real phone's run of 2026-11-22 evening: the loop's first two 51 s apart).
     */
    static long waitBeforeError(long lastErrorAt, long now) {
        return Math.max(0, lastErrorAt + ERROR_SPACING_MS - now);
    }

    private static String pid(PhoneRig phone) {
        return phone.pid();
    }

    /** What the app (whichever of its processes) and Android's crash handler said since the stamp. */
    private static String said(PhoneRig phone, String stamp) {
        try {
            return phone.shell("logcat -d -v time -T '" + stamp + "' -s DDLog:I AndroidRuntime:E");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Waits for the app to be a new process; its pid, or "" when none came in time. */
    private static String newProcess(PhoneRig phone, String oldPid, long withinMs) {
        long end = System.currentTimeMillis() + withinMs;
        while (System.currentTimeMillis() < end) {
            String now = pid(phone);
            if (!now.isEmpty() && !now.equals(oldPid)) return now;
            Ui.pause(500);
        }
        return "";
    }

    @Test
    void anAppEndedByAnErrorOrKilledAsleepIsBackByItself() throws Exception {
        PhoneRig.Target target = PhoneRig.Target.ofRun();
        Assumptions.assumeTrue(target.setUp(), "no Android emulator set up on this PC (docs/TESTING.md)");
        boolean keepApp = System.getenv("BENCH_KEEP_APP") != null;
        AppRig.scenario("phone-crash-comes-back").drones(0).reach(target.reach()).run(rig -> {
            Ui ui = rig.ui;
            ui.autoShot(false);
            Pilot pilot = new Pilot(rig);
            PhoneRig phone = new PhoneRig(rig.film, target);
            phone.ensureRunning();
            phone.root(); // the emulator: to end a process outright, and to forget earlier restarts (adb root, then 3 s); a real phone has none
            if (!keepApp) phone.installApp();
            phone.grantPermissions();
            phone.stopForgettingRestarts(); // no restart of an earlier run counts
            phone.launch();
            phone.startIfAsked(); // START, where it shows; behind a real phone's lock screen the service has to have started by itself
            java.net.InetSocketAddress at = phone.linkAddress();
            try (Wire wire = new Wire(at.getAddress(), at.getPort())) {
                wire.rule(BadLink.clean(47));
                rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
                rig.fleet.startHeartbeat();
                @SuppressWarnings("rawtypes")
                JComboBox phones = ui.all(JComboBox.class).get(0);
                ui.until("the phone in the PC's PHONE list", 60_000, () -> phones.getItemCount() > 0 && String.valueOf(phones.getItemAt(0)).contains("online"));
                // CHECK EVERY on the screen (the driver sees nothing of no height since 2026-11-21: a row that is being
                // laid out anew, its card's folds moving, is waited for - one that never shows is a fault of the window)
                @SuppressWarnings("rawtypes")
                JComboBox[] found = {null};
                ui.until("CHECK EVERY on the screen", 10_000, () -> {
                    try {
                        found[0] = ui.first(JComboBox.class, c -> {
                            for (int i = 0; i < c.getItemCount(); i++) if ("1 min".equals(String.valueOf(c.getItemAt(i)))) return true;
                            return false;
                        });
                        return true;
                    } catch (Ui.NotPressable e) {
                        return false;
                    }
                });
                @SuppressWarnings("rawtypes")
                JComboBox every = found[0];
                ui.choose(every, "1 min");
                String from = phone.now();
                pilot.connect();
                rig.waitForLog("Video: H.26", 60_000);
                Ui.pause(5000);
                ui.shot("flown on a clean network");

                // ---- 1. ended by an error while it is flown
                String pid0 = pid(phone);
                rig.check(!pid0.isEmpty(), "the phone app runs (process " + pid0 + ")");
                // (a recording begins with the first picture of a link: "Recording to ...flight-...mkv")
                int recordingsBefore = (int) rig.log().stream().filter(l -> l.contains("Recording to") && l.contains(".mkv")).count();
                long t0 = System.currentTimeMillis();
                phone.crash(); // am crash; the bench build on a real phone throws the error itself
                String pid1 = newProcess(phone, pid0, 30_000);
                long openedMs = System.currentTimeMillis() - t0;
                rig.check(!pid1.isEmpty(), "ended by an error while flown, the app is a new process by itself" + (pid1.isEmpty() ? " - none within 30 s" : " after " + openedMs / 1000.0 + " s"));
                boolean back = false;
                try {
                    ui.until("the PC to lose the link", 15_000, () -> !pilot.linkUp());
                    ui.until("the link back by itself", 60_000, pilot::linkUp);
                    back = true;
                } catch (Throwable t) {
                    rig.film.remark("After the error: " + t.getMessage());
                }
                long backMs = System.currentTimeMillis() - t0;
                rig.check(back, "and the PC has its link again without anyone touching the phone" + (back ? ", " + backMs / 1000.0 + " s after the error" : " - not within a minute"));
                if (back) {
                    boolean picture = false;
                    long end = System.currentTimeMillis() + 30_000;
                    while (!picture && System.currentTimeMillis() < end) {
                        picture = rig.log().stream().filter(l -> l.contains("Recording to") && l.contains(".mkv")).count() > recordingsBefore;
                        Ui.pause(500);
                    }
                    rig.check(picture, "with a picture again");
                }
                ui.shot("after the app was ended by an error");
                String log1 = said(phone, from);
                rig.check(log1.contains(PhoneWords.FATAL) && log1.contains(PhoneWords.OPENING_AGAIN), "the phone's log says what happened and that it opens again");
                rig.check(log1.contains(PhoneWords.LAST_ENDED_BY), "and the new start says what ended the last one");
                // a real phone: opened again, the app puts itself in front of the lock screen - what of that adb can see is filed, not judged
                phone.lookOverTheLock("after the app was ended by an error and opened itself again");

                // ---- 2. killed outright while asleep (a check every minute): back at its next check's alarm
                if (back) {
                    Ui.pause(3000);
                    int asked = rig.log().size();
                    for (int presses = 0; presses < 4; presses++) {
                        if (!pilot.linkUp()) break;
                        ui.click("DEEP SLEEP");
                        Ui.pause(4000);
                        java.util.List<String> all = rig.log(); // once: it grows between two reads
                        if (all.subList(Math.min(asked, all.size()), all.size()).stream().anyMatch(l -> l.contains("DEEP SLEEP asked"))) break;
                    }
                    ui.until("the link ended, the phone asleep", 120_000, () -> ui.has("PILOT UAV"));
                    Ui.pause(8000); // its first alarm is set
                    if (phone.sleepsOnBattery()) phone.onBattery(); // a real phone on its cable would not doze: pretended off it
                    String asleepFrom = phone.now();
                    String pid2 = pid(phone);
                    long killedAt = System.currentTimeMillis();
                    phone.endProcess(pid2); // kill -9 as root; without root the bench build ends its own process
                    Ui.pause(1500);
                    rig.check(pid(phone).isEmpty(), "asleep, the app's process is killed outright (as a battery saver does): gone");
                    // (Android may start a process of its own for the service it lost - that one finds no settings and
                    // stops: what counts is the app opening itself at its alarm)
                    boolean opened = false;
                    long until = System.currentTimeMillis() + 100_000;
                    while (!opened && System.currentTimeMillis() < until) {
                        Ui.pause(2000);
                        opened = said(phone, asleepFrom).contains(PhoneWords.OPENED_OVER_LOCK);
                    }
                    long cameS = (System.currentTimeMillis() - killedAt) / 1000;
                    rig.check(opened, "within its check interval and a little the app is open again by itself" + (opened ? " (" + cameS + " s after it was killed)" : " - not within 100 s"));
                    // (by its own sleep check's alarm, or by Android starting the service it lost - whichever came
                    // first; in the night Android had planned that for 77 minutes later, here it took 39 s)
                    String log2 = said(phone, asleepFrom);
                    rig.film.remark("Killed asleep, it came back " + (log2.contains(PhoneWords.SLEEP_CHECK) ? "at its own sleep check's alarm" : "with Android's restart of its service") + ".");
                    Ui.pause(5000);
                    ui.click("PILOT UAV");
                    boolean reached = false;
                    try {
                        ui.until("the phone reached by PILOT UAV", 90_000, pilot::linkUp);
                        reached = true;
                    } catch (Throwable t) {
                        rig.film.remark("After the kill: " + t.getMessage());
                    }
                    rig.check(reached, "and PILOT UAV reaches it");
                    phone.lookOverTheLock("after it was killed asleep, came back and was woken by PILOT UAV");
                    if (phone.sleepsOnBattery()) phone.plugged();
                    ui.shot("after the app was killed asleep");
                    if (pilot.linkUp()) pilot.standby();
                }

                // ---- 3. not for ever: the app's own limit is CrashRestarts.MAX times in ten minutes. Android has one
                // of its own, sooner (ERROR_SPACING_MS): so the errors here come more than two minutes apart - each
                // after the last one, not after the first - and the app's own limit is what ends them
                String loopFrom = phone.now();
                // counted from nothing: the app closed, what it kept of the restarts above gone, opened again
                phone.stopForgettingRestarts();
                phone.launch();
                Ui.pause(4000);
                int opened = 0;
                String down = "";
                long lastError = t0;
                for (int n = 1; n <= CrashRestarts.MAX + 1; n++) {
                    long wait = waitBeforeError(lastError, System.currentTimeMillis());
                    if (wait > 0) Ui.pause(wait);
                    if (!phone.serviceRunning()) { // only a phone at work opens itself again
                        phone.unlock();
                        phone.launch();
                        Ui.pause(4000);
                    }
                    String before = pid(phone);
                    String stamp = phone.now();
                    long sinceLast = System.currentTimeMillis() - lastError;
                    lastError = System.currentTimeMillis();
                    phone.crash();
                    boolean again = false;
                    long until = System.currentTimeMillis() + 30_000;
                    while (!again && System.currentTimeMillis() < until) {
                        Ui.pause(1500);
                        again = said(phone, stamp).contains(PhoneWords.OPENED_OVER_LOCK);
                    }
                    if (n <= CrashRestarts.MAX) {
                        rig.check(again, "ended by an error the " + n + ". time (" + (lastError - t0) / 1000 + " s after the first, " + sinceLast / 1000
                                + " s after the last), it opens itself again");
                        if (again) opened++;
                    } else {
                        down = pid(phone);
                        rig.check(!again, "ended the " + n + ". time within ten minutes, it does not open itself again");
                        rig.check(said(phone, stamp).contains(PhoneWords.NOT_OPENED_AGAIN), "and its log says so");
                        rig.check(down.isEmpty() || down.equals(before) || !phone.serviceRunning(), "it stays down until a hand opens it");
                    }
                }
                rig.film.remark("Opened by itself " + opened + " time(s) after an error; the limit is " + CrashRestarts.MAX + " in ten minutes.");
                rig.check(said(phone, loopFrom).length() > 0, "the phone's log was read to the end");
                Files.writeString(rig.film.dir().resolve("phone-log.txt"), said(phone, from), StandardCharsets.UTF_8);
                rig.check(!said(phone, from).contains(PhoneWords.ANDROID_CAMERA_CLOSED), "no camera error among what ended it");
                // as found: the app open, nothing kept of this run's restarts
                phone.stopForgettingRestarts();
                phone.launch();
            } finally {
                phone.close();
            }
        });
    }
}
