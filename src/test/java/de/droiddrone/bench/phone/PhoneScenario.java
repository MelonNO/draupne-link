package de.droiddrone.bench.phone;

import de.droiddrone.bench.BadLink;
import de.droiddrone.bench.Film;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.PhoneWords;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.Ui;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The real phone app - the APK that would go onto a phone - in the Android emulator, and the PC's real link talking
 * to it through a network the bench can spoil: first start and its permission questions, the service listening, the
 * PC connecting, the emulated camera's picture arriving as real H.264 or H.265 from Android's own encoder, a weak
 * link, a cut and the way back, standby. No flight controller: the emulator has no USB (docs/TESTING.md).
 * <p>
 * On a real phone too (the task benchHwPhone): the app that is on the phone is taken back to its first start (only the
 * bench build, or the released app with the run's word for it - {@code PhoneGuard}); what a pilot grants by tapping is
 * granted by adb, because a phone maker's questions are worded their own way and are not tapped through - so "the first
 * start asks" is the emulator's check only; the link goes to the phone's real address, the bench's wire still between.
 * This scenario starts no Windows app and so has no fence up: nothing is sealed here but the PC's link itself, which
 * speaks to the wire on loopback. The wire's way to a real phone is open only in a hardware run's JVM all the same
 * ({@code PhoneRig.linkAddress} asks the fence's own rule).
 * <p>
 * Behind a real phone's secure lock screen (as the phones on the drones are; nobody enters its PIN) it still proves:
 * the app up after its data was cleared (its process), its service started by itself and listening (Android's list of
 * living services, the app's log), the PC's link READY, the real camera's picture arriving from behind the lock
 * screen, the weak link held, the cut and the way back, STANDBY taken (the app's log), no crash. Skipped there, as on
 * every real phone: the first start's questions. The words the film keeps of the phone's screen are the lock screen's.
 */
@Tag("emulator")
@Tag("hwphone")
class PhoneScenario {
    private final List<String> wrong = new ArrayList<>();
    private Film film;

    private void check(boolean ok, String what) {
        film.remark((ok ? "ok: " : "WRONG: ") + what);
        if (!ok) wrong.add(what);
    }

    private static boolean within(long ms, BooleanSupplier ok) {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) return false;
            Ui.pause(50);
        }
        return true;
    }

    @Test
    void theRealPhoneAppFirstStartThenThePcsLinkAndItsCamerasPicture() throws Exception {
        PhoneRig.Target target = PhoneRig.Target.ofRun(); // the emulator; in a hardware run this bench's real phone (skipped without its hardware file)
        film = new Film(Sandbox.runRoot().resolve("film").resolve("phone-app-" + ProcessHandle.current().pid()), "phone-app");
        List<String> pcLog = new CopyOnWriteArrayList<>();
        PhoneRig phone = new PhoneRig(film, target);
        Wire wire = null;
        ControlLink link = null;
        try {
            phone.ensureRunning();
            if (phone.device()) {
                phone.installApp(); // the app that is there, unless the run names an APK
            } else {
                Path apk = PhoneRig.builtApk();
                phone.install(apk);
                film.remark("Installed " + apk.getFileName() + "; the phone says version " + phone.version());
                check(apk.getFileName().toString().contains(phone.version()), "the installed app is the built one: " + phone.version());
            }

            // ---- the very first start: the app asks for what it needs, a pilot says yes
            phone.forceStop();
            phone.clearData();
            if (phone.device()) phone.grantPermissions(); // a real phone's questions are its maker's: granted as adb grants, not tapped
            phone.launch();
            phone.shot("phone: the very first start");
            if (phone.device()) {
                // a real phone's questions are its maker's, and no finger of the bench goes on them: granted by adb above
                phone.skip("the first start asks for the camera and what else it needs: on a real phone the questions are its maker's and no finger of the bench "
                        + "goes on them - what they ask for was granted by adb instead");
            } else {
                List<String> asked = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    List<String> texts = phone.texts();
                    String yes = texts.stream().filter(t -> t.equals("While using the app") || t.equals("Allow") || t.equals("Only this time")).findFirst().orElse(null);
                    if (yes == null) break;
                    asked.add(texts.stream().filter(t -> t.startsWith("Allow ")).findFirst().orElse("?"));
                    phone.tap(yes);
                }
                film.remark("The app asked: " + asked);
                check(!asked.isEmpty(), "the first start asks for the camera (and what else it needs)");
            }
            phone.untilAppIsUp(15_000); // its screen's "for DRAUPNE Link"; behind a real phone's lock screen: its process
            phone.startIfAsked();       // START, where it shows; behind a lock screen the service has to have started by itself
            Ui.pause(2000);
            phone.shot("phone: its screen after the first start");
            // (a real phone's USB is adb's cable - but over the network it may have a flight controller: said, not checked)
            if (phone.device()) film.remark("About its flight controller the phone says: " + phone.flightControllerWord());
            else check(phone.sees(PhoneWords.SCREEN_NO_FC), "it says there is no flight controller on USB");
            check(phone.serviceRunning(), "its service runs (it listens for a PC)");
            Files.writeString(film.dir().resolve("phone-screen-first-start.txt"), String.join("\n", phone.texts()), StandardCharsets.UTF_8);

            // ---- the PC's link, through a wire the bench holds
            java.net.InetSocketAddress at = phone.linkAddress(); // the emulator: a forwarded port on loopback; a real phone: its address
            wire = new Wire(at.getAddress(), at.getPort());
            BadLink net = BadLink.clean(7);
            wire.rule(net);
            PcConfig config = PcConfig.defaults();
            link = new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
            link.setEvents(pcLog::add);
            AtomicLong frames = new AtomicLong(), keyFrames = new AtomicLong();
            String[] video = new String[1];
            link.setVideoListener(new ControlLink.VideoListener() {
                @Override
                public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
                    video[0] = (hevc ? "H.265 " : "H.264 ") + width + "x" + height;
                }

                @Override
                public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
                    if (offset == 0) {
                        frames.incrementAndGet();
                        if (keyFrame) keyFrames.incrementAndGet();
                    }
                }
            }, true);
            link.start();
            ControlLink l = link;
            long t0 = System.currentTimeMillis();
            boolean ready = within(30_000, () -> l.getState() == ControlLink.State.READY);
            check(ready, "the PC's link is READY with the real phone app (" + (System.currentTimeMillis() - t0) + " ms)");
            check(within(40_000, () -> frames.get() > 20), "the phone's camera picture arrives: " + frames.get() + " frames, " + video[0]);
            Ui.pause(2000);
            phone.shot("phone: a PC is connected");
            Files.writeString(film.dir().resolve("phone-screen-connected.txt"), String.join("\n", phone.texts()), StandardCharsets.UTF_8);
            film.remark("Phone build as the PC reads it: " + link.getTelemetry().getPhonePcBuild() + "; ping " + link.getPing() + " ms; tier " + link.getTier());

            // ---- a weak link for 15 s: the link holds, the picture goes on
            BadLink weak = new BadLink("one bar", 7).delay(60).jitter(60).loss(8).bursts(200, 12, 70);
            wire.rule(weak);
            long before = frames.get();
            Ui.pause(15_000);
            check(link.getState() == ControlLink.State.READY, "on a one-bar link (8 % loss, 60 ms jitter) the link is still READY");
            check(frames.get() - before > 50, "and the picture goes on: " + (frames.get() - before) + " frames in 15 s");
            film.remark("Tier on the weak link: " + link.getTier() + ", ping " + link.getPing() + " ms, " + weak.counts());

            // ---- a cut of 8 s both ways, and the way back
            wire.rule(net);
            Ui.pause(2000);
            long sessions = link.getSessionStarts();
            net.cut(8000, BadLink.Way.BOTH);
            check(within(6000, () -> l.getState() != ControlLink.State.READY), "8 s without network: the PC counts the link lost");
            Ui.pause(6500);
            long backT0 = System.currentTimeMillis();
            check(within(30_000, () -> l.getState() == ControlLink.State.READY), "the link comes back by itself (" + (System.currentTimeMillis() - backT0) + " ms after the network)");
            long framesBack = frames.get();
            check(within(30_000, () -> frames.get() > framesBack + 20), "and the picture with it");
            film.remark("Sessions the PC started for the cut: " + (link.getSessionStarts() - sessions));
            phone.shot("phone: after the cut");

            // ---- STANDBY from the PC, and wake by connecting again
            link.sendPowerMode(ControlLink.POWER_STANDBY, 0);
            Ui.pause(4000);
            check(phone.log().contains(PhoneWords.STANDBY), "the phone's log says it took STANDBY from the PC");
            phone.shot("phone: STANDBY asked by the PC");
            // the STANDBY screen animates without end, and Android then gives no description of it: the picture above is for the eyes
            link.close();
            link = null;
            Ui.pause(2000);
            Files.writeString(film.dir().resolve("phone-log.txt"), phone.log(), StandardCharsets.UTF_8);
            Files.writeString(film.dir().resolve("pc-log.txt"), String.join("\n", pcLog), StandardCharsets.UTF_8);
            String log = phone.log();
            check(!log.contains(PhoneWords.ANDROID_FATAL), "no crash in the phone app's log");
        } catch (Throwable t) {
            wrong.add("the scenario stopped: " + t);
            try {
                phone.shot("phone: STOPPED - " + t);
                Files.writeString(film.dir().resolve("phone-log.txt"), phone.log(), StandardCharsets.UTF_8);
                Files.writeString(film.dir().resolve("pc-log.txt"), String.join("\n", pcLog), StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
        } finally {
            if (link != null) link.close();
            if (wire != null) wire.close();
            phone.close();
            film.close(wrong.isEmpty(), wrong);
        }
        if (!wrong.isEmpty()) fail("phone-app: " + wrong.size() + " problem(s):\n - " + String.join("\n - ", wrong));
    }
}
