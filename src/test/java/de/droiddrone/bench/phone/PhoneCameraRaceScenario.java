package de.droiddrone.bench.phone;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.BadLink;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.pc.gui.BenchAccess;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.awt.Component;
import java.awt.Point;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The camera closed while a change of the picture's size is being set up (F-047): what ended the phone app in the
 * bench's night of 2026-11-20. There a failing link did it by chance, 38 minutes in: STANDBY was asked for while a
 * size change ran, and the camera was closed 10 ms after the change ended. Here that is done on purpose, twenty
 * times ({@code -Dbench.cameraRounds}): the picture's size is changed from the PC's MENU and, at once or up to a
 * sixth of a second later, the phone is put to STANDBY, which stops its camera.
 * <p>
 * The app must be the same process at the end as at the start, with no error in its log; each round PILOT UAV must
 * bring the picture back. How often the camera really was closed under the change is counted from the phone's own
 * log ("the camera was closed meanwhile"): a run that never met it says so.
 * <p>
 * It is a narrow moment: 52 rounds against the app as it was built on 2026-11-19 22:36 ({@code BENCH_KEEP_APP=1}
 * leaves the app on the phone as it is) did not end that app once. So this scenario does not prove the fix - the
 * night's stack trace is what the fix rests on; what it does is fly the phone through the size change and the stop
 * together, which nothing else in the bench did.
 */
@Tag("emulator")
class PhoneCameraRaceScenario {
    private static final String APP = "de.droiddrone.flight.pc";
    private static final String[] VIDEO = {"AUTO", "SHARP", "NORMAL", "ROBUST", "SURVIVAL"};

    private static String said(PhoneRig phone, String stamp) {
        try {
            return phone.shell("logcat -d -v time -T '" + stamp + "' -s DDLog:I AndroidRuntime:E");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    /** MENU > VIDEO, the picture set to this choice, the menu closed again. */
    private static void picture(Ui ui, int choice) {
        java.util.List<Component> top = ui.all("VIDEO");
        if (top.isEmpty() || ui.whyNotPressable(top.get(0), null) != null) ui.click("MENU");
        ui.click("VIDEO");
        Ui.pause(350);
        Component row = null;
        for (Component c : ui.components()) {
            String t = Ui.edt(() -> Ui.textOf(c));
            if (c.getClass().getSimpleName().equals("ChoiceRow") && t != null && t.toUpperCase().startsWith("VIDEO")) row = c;
        }
        if (row == null) throw new AssertionError("MENU > VIDEO has no row of picture choices");
        Component r = row;
        Point at = Ui.edt(() -> BenchAccess.choice(r, VIDEO, choice));
        ui.clickAt(row, at.x, at.y);
        ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
    }

    @Test
    void theCameraClosedWhileThePicturesSizeChangesDoesNotEndTheApp() throws Exception {
        Assumptions.assumeTrue(PhoneRig.setUp(), "no Android emulator set up on this PC (docs/TESTING.md)");
        boolean keepApp = System.getenv("BENCH_KEEP_APP") != null;
        AppRig.scenario("phone-camera-closed-under-a-size-change").drones(0).run(rig -> {
            Ui ui = rig.ui;
            ui.autoShot(false);
            Pilot pilot = new Pilot(rig);
            PhoneRig phone = new PhoneRig(rig.film);
            phone.ensureRunning();
            phone.adb("root");
            Ui.pause(3000);
            if (!keepApp) phone.install(PhoneRig.builtApk());
            phone.grantPermissions();
            phone.forceStop();
            phone.shell("rm -f /data/data/" + APP + "/shared_prefs/crashGuard.xml");
            phone.launch();
            if (phone.texts().contains("START")) phone.tap("START");
            int phonePort = PhoneRig.linkOnThePhone();
            int hostPort = phone.forwardUdp(phonePort);
            try (Wire wire = new Wire(InetAddress.getLoopbackAddress(), hostPort)) {
                wire.rule(BadLink.clean(53));
                rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
                rig.fleet.startHeartbeat();
                @SuppressWarnings("rawtypes")
                JComboBox phones = ui.all(JComboBox.class).get(0);
                ui.until("the phone in the PC's PHONE list", 60_000, () -> phones.getItemCount() > 0 && String.valueOf(phones.getItemAt(0)).contains("online"));
                String from = phone.now();
                pilot.connect();
                rig.waitForLog("Video: H.26", 60_000);
                Ui.pause(4000);
                String pid0 = phone.pid();
                rig.check(!pid0.isEmpty(), "the phone app runs (process " + pid0 + ")");

                int[] sizes = {3, 1, 4, 2}; // ROBUST, SHARP, SURVIVAL, NORMAL: each a size of its own
                int rounds = Integer.getInteger("bench.cameraRounds", 20), done = 0, ended = 0;
                for (int round = 0; round < rounds; round++) {
                    long gap = (round % 5) * 40L; // at once to 0.16 s after the change: the stop is then asked for while the size is being changed
                    try {
                        if (!pilot.linkUp()) {
                            pilot.connect();
                            Ui.pause(2500);
                        }
                        picture(ui, sizes[round % sizes.length]);
                        Ui.pause(gap);
                        pilot.standby(); // the phone stops camera and encoder
                        Ui.pause(1500);
                        done++;
                    } catch (Throwable t) {
                        rig.film.remark("Round " + (round + 1) + " (" + VIDEO[sizes[round % sizes.length]] + ", then STANDBY " + gap + " ms later): " + t.getMessage());
                        ui.key(java.awt.event.KeyEvent.VK_ESCAPE);
                    }
                    String now = phone.pid();
                    if (!now.equals(pid0)) {
                        ended++;
                        rig.check(false, "round " + (round + 1) + " (" + VIDEO[sizes[round % sizes.length]] + ", then STANDBY " + gap + " ms later): the phone app is "
                                + (now.isEmpty() ? "gone" : "another process (" + now + "): it was ended and came back"));
                        ui.shot("round " + (round + 1) + ": the phone app was ended");
                        long until = System.currentTimeMillis() + 30_000; // it opens itself again, or a hand does
                        while (!phone.serviceRunning() && System.currentTimeMillis() < until) Ui.pause(1000);
                        if (!phone.serviceRunning()) phone.launch();
                        Ui.pause(4000);
                        pid0 = phone.pid();
                    }
                    try {
                        if (!ui.has("PILOT UAV")) ui.waitForControl("PILOT UAV", 20_000);
                        pilot.connect();
                        Ui.pause(2500);
                    } catch (Throwable t) {
                        rig.film.remark("Round " + (round + 1) + ": PILOT UAV after it: " + t.getMessage());
                    }
                }
                String log = said(phone, from);
                Files.writeString(rig.film.dir().resolve("phone-log.txt"), log, StandardCharsets.UTF_8);
                int met = count(log, "the camera was closed meanwhile");
                rig.film.remark(done + " of " + rounds + " rounds done; the camera was closed under a size change " + met + " time(s) by the phone's own log; the app was ended "
                        + ended + " time(s)." + (met == 0 && ended == 0 ? " This run never met the moment: nothing is proven by it." : ""));
                rig.check(ended == 0, "the phone app was never ended: the same process all the way");
                rig.check(!log.contains("FATAL"), "no error that ends the app in the phone's log");
                rig.check(pilot.linkUp(), "and the link is up at the end");
                ui.shot("after " + rounds + " size changes, each followed by STANDBY");
                // as found: AUTO again
                try {
                    picture(ui, 0);
                } catch (Throwable ignored) {
                }
                if (pilot.linkUp()) pilot.standby();
            } finally {
                phone.close();
            }
        });
    }
}
