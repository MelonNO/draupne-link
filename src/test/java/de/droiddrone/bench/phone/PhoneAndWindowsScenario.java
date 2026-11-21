package de.droiddrone.bench.phone;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.BadLink;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Both apps as they are shipped, together: the real Windows app's window and the real phone app in the emulator,
 * with the bench's wire between them. The phone is found and named by the PC, PILOT UAV is pressed with the mouse,
 * the phone's camera picture shows in the PC's window, the network goes bad and comes back, STANDBY MODE ends it.
 * The film has both: the PC's window and the phone's screen. (No flight controller: the emulator has no USB.)
 */
@Tag("emulator")
class PhoneAndWindowsScenario {
    @Test
    void thePcFindsThePhoneConnectsAndShowsItsCamera() throws Exception {
        AppRig.scenario("phone-and-windows").drones(0).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            PhoneRig phone = new PhoneRig(rig.film);
            phone.ensureRunning();
            phone.install(PhoneRig.builtApk());
            phone.grantPermissions();
            phone.forceStop();
            phone.launch();
            if (phone.texts().contains("START")) phone.tap("START");
            phone.shot("phone: waiting for a PC");
            int hostPort = phone.forwardUdp(PhoneRig.linkOnThePhone());
            BadLink net = BadLink.clean(11);
            try (Wire wire = new Wire(InetAddress.getLoopbackAddress(), hostPort)) {
                wire.rule(net);
                // announced as the virtual fleet announces its phones: an address on this PC and its own port
                rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
                rig.fleet.startHeartbeat();
                @SuppressWarnings("rawtypes")
                JComboBox phones = ui.all(JComboBox.class).get(0);
                ui.until("the phone in the PC's PHONE list", 40_000, () -> phones.getItemCount() > 0 && String.valueOf(phones.getItemAt(0)).contains("online"));
                String entry = String.valueOf(Ui.edt(phones::getSelectedItem));
                rig.film.remark("The PC's PHONE list: " + entry);
                ui.shot("the PC found the phone: " + entry.trim());
                phone.shot("phone: found and named by the PC");
                Files.writeString(rig.film.dir().resolve("phone-screen-named.txt"), String.join("\n", phone.texts()), StandardCharsets.UTF_8);

                pilot.connect();
                rig.waitForLog("Video: H.26", 40_000);
                Ui.pause(3000);
                ui.shot("the PC's window with the phone's camera picture");
                phone.shot("phone: flown by the PC");
                rig.check(phone.sees("Connected to PC"), "the phone's screen says: Connected to PC");
                rig.check(ui.sees("2.3.0-pc."), "the PC shows the phone app's version: " + ui.texts().stream().filter(t -> t.contains("2.3.0-pc.")).findFirst().orElse("-"));
                rig.check(ui.sees("PHONE BATTERY"), "the PC shows the phone's battery");

                // the network goes bad for 12 s, then away for 6 s, then is good again
                BadLink weak = new BadLink("one bar", 11).delay(60).jitter(60).loss(8).bursts(200, 12, 70);
                wire.rule(weak);
                Ui.pause(12_000);
                ui.shot("the PC's window on a one-bar link");
                rig.check(ui.has("LINK READY") || ui.sees("LINK"), "on a one-bar link the PC is still connected");
                wire.rule(net);
                net.cut(6000, BadLink.Way.BOTH);
                Ui.pause(7000);
                ui.shot("the PC's window after 6 s without network");
                ui.until("the link back", 40_000, () -> pilot.linkUp());
                Ui.pause(4000);
                ui.shot("the PC's window, the link back");

                pilot.standby();
                Ui.pause(3000);
                phone.shot("phone: after STANDBY MODE on the PC");
                String log = phone.log();
                Files.writeString(rig.film.dir().resolve("phone-log.txt"), log, StandardCharsets.UTF_8);
                rig.check(log.contains("Power: Standby"), "the phone took STANDBY from the PC's STANDBY MODE");
                rig.check(!log.contains("FATAL EXCEPTION"), "no crash in the phone app's log");
            } finally {
                phone.close();
            }
        });
    }

    /**
     * F-001, measured on the real phone app (2026-11-19 afternoon): the phone hears the PC, the PC hears nothing, for
     * 6 s. The made-up phone started a new session every second of it; what the real one does, and what a new session
     * costs it - its camera, its encoder - is counted here from its own log and written into the film's remarks and
     * {@code phone-log-one-way-cut.txt}. A measurement for the pilot's judgement: only a crash, or a link that does
     * not come back, fails it.
     */
    @Test
    void aLinkDeadFromThePhoneToThePcOnlyMeasuredOnTheRealPhoneApp() throws Exception {
        AppRig.scenario("phone-one-way-cut").drones(0).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            PhoneRig phone = new PhoneRig(rig.film);
            phone.ensureRunning();
            phone.install(PhoneRig.builtApk());
            phone.grantPermissions();
            phone.forceStop();
            phone.launch();
            if (phone.texts().contains("START")) phone.tap("START");
            int hostPort = phone.forwardUdp(PhoneRig.linkOnThePhone());
            BadLink net = BadLink.clean(12);
            try (Wire wire = new Wire(InetAddress.getLoopbackAddress(), hostPort)) {
                wire.rule(net);
                rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
                rig.fleet.startHeartbeat();
                @SuppressWarnings("rawtypes")
                JComboBox phones = ui.all(JComboBox.class).get(0);
                ui.until("the phone in the PC's PHONE list", 40_000, () -> phones.getItemCount() > 0 && String.valueOf(phones.getItemAt(0)).contains("online"));
                pilot.connect();
                rig.waitForLog("Video: H.26", 40_000);
                Ui.pause(6000);
                ui.shot("before the cut: the phone's camera picture");

                String before = phone.log();
                int pcLinesBefore = rig.log().size();
                long cutAt = System.currentTimeMillis();
                net.cut(6000, BadLink.Way.TO_PC);
                Ui.pause(5500);
                ui.shot("5 s into hearing nothing from the phone");
                Ui.pause(700);
                long heardAgain = System.currentTimeMillis();
                ui.until("the link back", 40_000, () -> pilot.linkUp());
                long backMs = System.currentTimeMillis() - heardAgain;
                Ui.pause(6000);
                ui.shot("the link back, 6 s on");
                phone.shot("phone: after the one-way cut");

                String after = phone.log();
                String lastBefore = before.isBlank() ? "" : before.substring(before.stripTrailing().lastIndexOf('\n') + 1).strip();
                int from = lastBefore.isEmpty() ? -1 : after.lastIndexOf(lastBefore);
                String during = from < 0 ? after : after.substring(from + lastBefore.length());
                java.util.List<String> own = during.lines().filter(l -> l.contains("DDLog")).toList();
                long sessions = own.stream().filter(l -> l.contains("a new session") || l.contains("New session from")).count();
                long cameraClosed = own.stream().filter(l -> l.contains("Camera device closed")).count();
                long cameraOpened = own.stream().filter(l -> l.toLowerCase().contains("onconfigured") || l.toLowerCase().contains("camera opened")).count();
                long encoder = own.stream().filter(l -> l.contains("onOutputFormatChanged")).count();
                Files.writeString(rig.film.dir().resolve("phone-log-one-way-cut.txt"), String.join("\n", own), StandardCharsets.UTF_8);
                java.util.List<String> all = rig.log(); // once: the log grows between two reads (an IndexOutOfBounds, 2026-11-21)
                java.util.List<String> pc = all.subList(Math.min(pcLinesBefore, all.size()), all.size());
                Files.writeString(rig.film.dir().resolve("pc-log-one-way-cut.txt"), String.join("\n", pc), StandardCharsets.UTF_8);
                rig.film.remark("F-001 on the real phone app - 6 s in which the phone heard the PC and the PC heard nothing: the phone started "
                        + sessions + " new session(s), closed its camera " + cameraClosed + " time(s), set a camera session up " + cameraOpened
                        + " time(s), its encoder started anew " + encoder + " time(s) (" + own.size() + " lines of its own log, in phone-log-one-way-cut.txt). "
                        + "LINK READY " + backMs + " ms after the PC heard it again; " + (System.currentTimeMillis() - cutAt) / 1000 + " s in all.");
                rig.check(!after.contains("FATAL EXCEPTION"), "no crash in the phone app's log");
                rig.check(phone.sees("Connected to PC"), "the phone's screen says: Connected to PC");
                pilot.standby();
            } finally {
                phone.close();
            }
        });
    }
}
