package de.droiddrone.bench.phone;

import com.sun.net.httpserver.HttpServer;
import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.BadLink;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.PhoneWords;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

/**
 * F-054, the first remote update of the phone app (the pilot, 2026-11-20: "emulator after the run, then one of my
 * phones"). UPDATE PHONE APP pressed on the real Windows app, the real phone app in the emulator downloads the newer
 * app, checks it and installs it - with no "Update this app?" from Android on a phone nobody can reach (the
 * permission F-054 added) - opens again by itself, and the PC has its link back and says the new version.
 * <p>
 * Two test builds of the phone app, each made by {@code flight-app\build.ps1 -UpdateTest} (the maintainer's
 * build-update-test.ps1 makes the pair and copies it to build/update-test): receiver.apk, the tree's code as it is -
 * the released app's package, version and key - with the one thing more this scenario needs: plain http to the
 * bench's own web server, and a remote update taken from there (bench-overlay/update-test, UpdateTestSource). A
 * released app takes a remote update only from the project's release downloads and refuses this scenario's address
 * (UpdateTestSourceTest holds both). sender.apk, the same one version up, labelled ...-test. The PC is told of the
 * sender by the bench's switch for it (ApkShare, {@code draupne.bench.phoneApp}); the file is served on this PC's
 * loopback, which the emulator reaches as 10.0.2.2.
 * <p>
 * On a real phone (the task benchHwPhone) the file is fetched through adb's cable instead: {@code adb reverse} leads
 * the phone's own 127.0.0.1 to this PC's server (through adb, on this PC's loopback: the fence needs no way through for
 * it). The same two builds serve - they take 127.0.0.1 as they take 10.0.2.2 - from build/update-test-hw
 * (build-update-test.ps1 -Hw puts them there too; receiver.apk, sender.apk, versions.txt), and it is skipped without
 * them. NOT WITH THE BENCH BUILD: that one is test-only (flight-app/bench-overlay), and Android lets nobody but
 * {@code adb install -t} install a test-only app - not the app's own updater either. So on a real phone this is a
 * scenario for the released app's package alone (phone.package), and for a run with the word "the released app may
 * be changed"; skipped otherwise. Nothing is installed a version down there ("install -d" is never sent): the phone
 * keeps the sender at the end - a test build that also takes an update from this PC over plain http, not for a
 * phone that flies - and the receiver must be at least as new as what is on the phone.
 */
@Tag("emulator")
@Tag("hwphone")
class PhoneUpdateScenario {

    private static String sha256(Path p) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format("%02x", b));
        return s.toString();
    }

    /** What Android shows in front now (its installer's "Update this app?" would be com.*.packageinstaller). */
    private static String inFront(PhoneRig phone) {
        try {
            String w = phone.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'");
            return w.trim().replaceAll("\\s+", " ");
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Test
    void thePhoneAppUpdatesFromThePcWithoutAQuestionOnThePhone() throws Exception {
        PhoneRig.Target ofRun = PhoneRig.Target.ofRun();
        // the emulator flies the bench build since Q-006; this scenario flies the released app's package there - its two
        // test builds take every address in the emulator, as the bench build does (bench-overlay/update-test, BenchNet)
        PhoneRig.Target target = ofRun.kind() == PhoneRig.Kind.EMULATOR ? ofRun.flying(PhoneRig.APP) : ofRun;
        Assumptions.assumeTrue(target.setUp(), "no Android emulator set up on this PC (docs/TESTING.md)");
        final String APP = target.app();
        if (target.kind() == PhoneRig.Kind.DEVICE) {
            // the update installs over the app the run flies: the released one, by the run's own word (the class comment)
            Assumptions.assumeFalse(target.benchBuild(), "the bench build is test-only: no app's own updater can install it - a run of the released app (phone.package="
                    + PhoneRig.APP + ") only");
            Assumptions.assumeTrue(target.releasedAppMayBeChanged(), "the update installs over the released app: a run with -PreleasedAppMayBeChanged=true only");
        }
        // the build folder, not %LOCALAPPDATA%: inside the bench's sandbox that is another folder. (A real phone's two
        // builds are other ones: see the class comment)
        Path dir = Path.of(System.getProperty("bench.build", "build")).resolve(target.kind() == PhoneRig.Kind.DEVICE ? "update-test-hw" : "update-test");
        Path receiver = dir.resolve("receiver.apk"), sender = dir.resolve("sender.apk");
        Assumptions.assumeTrue(Files.isRegularFile(receiver) && Files.isRegularFile(sender), "no test builds in " + dir + " (build-update-test.ps1)");
        // the two builds' names, written by build-update-test.ps1: the tree's, and one up
        Path versions = dir.resolve("versions.txt");
        Assumptions.assumeTrue(Files.isRegularFile(versions), "no versions.txt in " + dir + " (build-update-test.ps1)");
        List<String> names = Files.readAllLines(versions, StandardCharsets.UTF_8);
        String treeVersion = names.get(0).trim(), newVersion = names.get(1).trim();
        HttpServer web = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int[] served = {0};
        web.createContext("/", ex -> {
            byte[] body = Files.readAllBytes(sender);
            served[0]++;
            ex.getResponseHeaders().add("Content-Type", "application/vnd.android.package-archive");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        });
        web.start();
        // the emulator reaches this PC's loopback as 10.0.2.2; a real phone as its own 127.0.0.1, through adb reverse
        String url = target.thisPc(web.getAddress().getPort()) + "/DRAUPNE-Flight-" + newVersion + ".apk";
        // before the app's classes load (ApkShare reads it once)
        System.setProperty("draupne.bench.phoneApp", newVersion + "|" + sha256(sender) + "|" + url);
        try {
            AppRig.scenario("phone-remote-update").drones(0).reach(target.reach()).run(rig -> {
                Ui ui = rig.ui;
                ui.autoShot(false);
                Pilot pilot = new Pilot(rig);
                PhoneRig phone = new PhoneRig(rig.film, target);
                phone.ensureRunning();
                phone.reachesThisPc(web.getAddress().getPort()); // a real phone: adb reverse; the emulator needs nothing
                // over whatever is there: "-d" takes a lower version code (an earlier run's pc.55-test). The app can't be
                // uninstalled from the shell - it is the phone's device admin (screen lock), and only test admins go.
                // (A real phone is never taken down a version: there the receiver goes on only over an older or equal one.)
                phone.stopTheOther(); // the emulator: the bench build of the scenarios before gives up the link port
                phone.installAnyVersion(receiver);
                rig.check(phone.version().equals(treeVersion), "the phone runs " + treeVersion + ": " + phone.version());
                // "Install unknown apps" off before the app opens: Android ends an app whose switch is turned off (the
                // 2026-11-21 09:38 run did it while connected: the link was lost, the PC's question went nowhere)
                phone.shell("appops set " + APP + " REQUEST_INSTALL_PACKAGES default");
                phone.grantPermissions();
                phone.launch();
                phone.startIfAsked(); // START, where it shows; behind a real phone's lock screen the service has to have started by itself
                java.net.InetSocketAddress at = phone.linkAddress();
                try (Wire wire = new Wire(at.getAddress(), at.getPort())) {
                    wire.rule(BadLink.clean(54));
                    rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
                    rig.fleet.startHeartbeat();
                    @SuppressWarnings("rawtypes")
                    JComboBox phones = ui.all(JComboBox.class).get(0);
                    ui.until("the phone in the PC's PHONE list", 60_000, () -> phones.getItemCount() > 0 && String.valueOf(phones.getItemAt(0)).contains("online"));
                    pilot.connect();
                    rig.waitForLog("Video: H.26", 60_000);
                    ui.until("UPDATE PHONE APP offered", 30_000, () -> ui.has("UPDATE PHONE APP"));
                    boolean oldSaid = false;
                    try {
                        ui.until("the card's line about the old app", 5_000, () -> ui.sees("Old phone app"));
                        oldSaid = true;
                    } catch (Throwable t) {
                        rig.film.remark("The card: " + t.getMessage());
                    }
                    rig.check(oldSaid, "the PC says the phone app is older than the one it has");
                    ui.shot("an older phone app: UPDATE PHONE APP");
                    String from = phone.now();

                    // ---- A. "Install unknown apps" not allowed for the app (F-058, this scenario's first run: Android
                    // put its question up after the download): refused at once, the reason on the PC, nothing fetched
                    rig.check(!phone.shell("appops get " + APP + " REQUEST_INSTALL_PACKAGES").contains("allow"),"\"Install unknown apps\" off on the phone");
                    ui.click("UPDATE PHONE APP");
                    ui.until("the PC asks first", 10_000, () -> ui.sees(newVersion));
                    ui.click("Yes");
                    boolean said = false;
                    try {
                        ui.until("the phone's reason on the PC", 20_000, () -> ui.sees("Install unknown apps"));
                        said = true;
                    } catch (Throwable t) {
                        rig.film.remark("Not allowed to install: " + t.getMessage());
                    }
                    rig.check(said, "\"Install unknown apps\" not allowed: the PC says so at once");
                    rig.check(served[0] == 0, "and the phone fetched nothing (" + served[0] + " download(s))");
                    rig.check(!inFront(phone).contains("packageinstaller"), "and Android asks nothing on the phone");
                    ui.shot("not allowed to install: the PC says why");
                    phone.shot("not allowed to install: the phone");
                    ui.until("UPDATE PHONE APP again", 30_000, () -> ui.has("UPDATE PHONE APP") && Ui.edt(() -> ui.find("UPDATE PHONE APP").isEnabled()));

                    // ---- B. allowed once by hand, as on a drone's phone that is set up: the update goes through
                    String pidBefore = phone.pid();
                    phone.shell("appops set " + APP + " REQUEST_INSTALL_PACKAGES allow");
                    // Android 11 ends the app when its "Install unknown apps" is turned ON as well (2026-11-22, draupne-a30:
                    // the link was gone for ~100 s and the update asked meanwhile went nowhere); Android 12 on ends it only
                    // when the switch is turned off, Android 8 never. So the app is opened again here and the link waited
                    // for, on every Android - where the app was not ended that changes nothing. (The PC counts a link lost
                    // after 2 s without a word: the pause lets it see an ended app before the wait for the link begins.)
                    Ui.pause(4000);
                    String pidAfter = phone.pid();
                    boolean ended = pidBefore.isEmpty() || !pidAfter.equals(pidBefore);
                    rig.film.remark("\"Install unknown apps\" allowed while linked: " + (ended ? "Android ended the app (its process " + (pidBefore.isEmpty() ? "?" : pidBefore)
                            + " -> " + (pidAfter.isEmpty() ? "none" : pidAfter) + "), as Android 11 does" : "the app kept running (process " + pidAfter + ")")
                            + ". The app is opened again and the PC's link waited for before UPDATE PHONE APP, on every Android.");
                    phone.launch();
                    phone.startIfAsked(); // START, where it shows; behind a real phone's lock screen the service has to have started by itself
                    ui.until("the PC's link back after \"Install unknown apps\" was allowed", 120_000, pilot::linkUp);
                    ui.until("UPDATE PHONE APP offered again", 30_000, () -> ui.has("UPDATE PHONE APP") && Ui.edt(() -> ui.find("UPDATE PHONE APP").isEnabled()));
                    if (phone.device()) phone.reverseAgain(); // adb's way back to this PC's server, should the cable have been out
                    long t0 = System.currentTimeMillis();
                    ui.click("UPDATE PHONE APP");
                    ui.until("the PC asks first", 10_000, () -> ui.sees(newVersion));
                    ui.shot("the PC asks first");
                    ui.click("Yes");
                    // downloading, checking, installing: Android's question must not come, the new app must. Before
                    // Android 12 it comes all the same (no app may update itself without it there): the PC must say to
                    // tap Update on the phone, and on the emulator the tap is given, as by a pilot beside the phone
                    // (2026-11-22, the first run on Android 8). A real phone's installer is never tapped by the bench.
                    int sdk = -1;
                    try {
                        sdk = Integer.parseInt(phone.shell("getprop ro.build.version.sdk").trim());
                    } catch (RuntimeException e) {
                        rig.film.remark("The phone's Android version could not be read: " + e.getMessage());
                    }
                    boolean asks = sdk > 0 && sdk < 31;
                    if (asks) rig.film.remark("Android " + sdk + " (API) asks before an app updates itself, as every Android before 12 does: one tap on the phone.");
                    String question = "";
                    boolean updated = false, tapped = false;
                    long until = System.currentTimeMillis() + 240_000;
                    while (!updated && System.currentTimeMillis() < until) {
                        Ui.pause(2000);
                        String front = inFront(phone);
                        if (front.contains("packageinstaller") && question.isEmpty()) {
                            question = front;
                            phone.shot("Android's installer in front");
                        }
                        if (asks && !question.isEmpty() && !tapped && !phone.device()) {
                            String install = phone.texts().stream().filter(t -> t.equalsIgnoreCase("Install") || t.equalsIgnoreCase("Update")).findFirst().orElse(null);
                            if (install != null) {
                                ui.shot("Android asks on the phone: the PC's word for it");
                                phone.tap(install);
                                tapped = true;
                                rig.film.remark("Android's question answered on the phone: " + install);
                            }
                        }
                        updated = newVersion.equals(phone.version());
                    }
                    long installedS = (System.currentTimeMillis() - t0) / 1000;
                    rig.check(served[0] >= 1, "the phone downloaded the newer app from the address the PC sent (" + served[0] + " time(s))");
                    rig.check(updated, "the phone runs " + newVersion + (updated ? ", " + installedS + " s after UPDATE PHONE APP" + (tapped ? " and one tap on the phone" : "")
                            : " - not within 4 min: " + phone.version()));
                    if (asks) {
                        rig.check(!question.isEmpty(), "Android asked once on the phone, as before Android 12" + (question.isEmpty() ? ": it did not" : ""));
                        rig.check(rig.log().stream().anyMatch(l -> l.contains("tap Update on its screen")), "and the PC said to tap Update on the phone");
                    } else {
                        rig.check(question.isEmpty(), "Android asked nothing on the phone (\"Update this app?\")" + (question.isEmpty() ? "" : ": " + question));
                    }
                    boolean back = false;
                    try {
                        ui.until("the link back by itself", 120_000, pilot::linkUp);
                        back = true;
                    } catch (Throwable t) {
                        rig.film.remark("After the update: " + t.getMessage());
                    }
                    rig.check(back, "the app open again by itself and the PC's link back, nobody touching the phone");
                    if (back) {
                        ui.until("the PC to say the new version", 30_000, () -> rig.log().stream().anyMatch(l -> l.contains("Phone app updated to " + newVersion)));
                        // the card is refreshed once a second and folds the button away: a few seconds, not the moment
                        boolean gone = false;
                        long by = System.currentTimeMillis() + 10_000;
                        while (!gone && System.currentTimeMillis() < by) {
                            gone = !ui.has("UPDATE PHONE APP") || !ui.sees("Old phone app");
                            if (!gone) Ui.pause(500);
                        }
                        rig.check(gone, "no longer offered: the phone has the newest");
                    }
                    ui.shot("after the update");
                    phone.shot("the phone after the update");
                    String log = phone.logSince(from);
                    Files.writeString(rig.film.dir().resolve("phone-log.txt"), log, StandardCharsets.UTF_8);
                    rig.check(!log.contains(PhoneWords.ANDROID_FATAL), "no crash in the phone app's log");
                    if (pilot.linkUp()) pilot.standby();
                } finally {
                    if (phone.device()) {
                        // a real phone is never taken down a version: it keeps what the update left
                        try {
                            rig.film.remark("The phone keeps " + phone.version() + " of " + APP + " (a real phone is never installed a version down): "
                                    + "the update test's sender, which also takes an update from this PC over plain http - not for a phone that flies. "
                                    + "The released app is put back by hand before it does - uninstalled, then installed anew.");
                        } catch (RuntimeException | Error e) {
                            rig.film.remark("The phone's version at the end could not be read: " + e.getMessage());
                        }
                    } else {
                        // the released app's package back to the normal build in build/flight (a lower version code: "-d"),
                        // so that no test build of it lingers - and stopped: the next scenarios fly the bench build
                        // (Q-006: the normal build answers over Tailscale only, the emulator has none - it stays silent)
                        try {
                            String said = phone.installAnyVersion(PhoneRig.builtApk());
                            if (!said.contains("Success")) throw new IllegalStateException(said);
                            rig.check(phone.version().equals(treeVersion), "the normal phone app is back: " + phone.version());
                            phone.forceStop();
                        } catch (RuntimeException e) {
                            rig.film.remark("The normal phone app not put back: " + e.getMessage());
                        }
                    }
                    phone.close();
                }
            });
        } finally {
            web.stop(0);
            System.clearProperty("draupne.bench.phoneApp");
        }
    }
}
