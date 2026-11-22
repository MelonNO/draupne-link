package de.droiddrone.bench.phone;

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
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The phone's sending that stands still (F-039). In the night's first try-out the emulator's Wi-Fi kept part of what
 * the phone app sent and never let go of it: the link socket's send buffer filled up over a minute and a half and
 * stayed full, every sender of the app waited in its send for good, and the link was gone until the app was ended -
 * while the phone heard the PC all along.
 * <p>
 * Here the real app is flown by the real Windows app on a clean network for ten minutes at most, and the socket's
 * waiting bytes (Send-Q, as Android's own `ss` counts them) are read every second. The emulator fills the buffer by
 * itself. Each time the phone says it opened its socket anew, the link must be back within 20 s and the waiting bytes
 * gone; a link lost for half a minute fails the scenario. A run in which the buffer never fills proves nothing and
 * says so.
 * <p>
 * {@code BENCH_KEEP_APP=1} leaves the app on the phone as it is (to see what another build does).
 * <p>
 * The emulator's only - not for a real phone (no tag hwphone): what fills the socket is the emulator's made-up Wi-Fi,
 * which a real phone does not have; the waiting bytes are read with {@code ss}, which needs root; and the scans that
 * hurry it along ({@code cmd wifi start-scan}) are not on the list of what the bench sends to a real phone. Whether a
 * real modem ever holds the app's sending like that is a question for the field's link logs, not for this scenario.
 */
@Tag("emulator")
class PhoneSocketScenario {
    private static final Pattern SEND_Q = Pattern.compile("UNCONN\\s+\\d+\\s+(\\d+)\\s+\\S*:(\\d+)\\s");

    /** A line of the kernel's own socket tables: local address:port (hex), then tx_queue:rx_queue (hex). */
    private static final Pattern PROC_LINE = Pattern.compile("^\\s*\\d+:\\s+[0-9A-Fa-f]+:([0-9A-Fa-f]{4})\\s+\\S+\\s+\\S+\\s+([0-9A-Fa-f]+):[0-9A-Fa-f]+", Pattern.MULTILINE);

    /**
     * What waits to leave the app's link socket, bytes (-1: not readable). Read with ss; where ss lists no socket at
     * all (Android 8's, even as root, 2026-11-22) from the kernel's own tables, which say the same.
     */
    private static long sendQueue(PhoneRig phone, int port) {
        try {
            Matcher m = SEND_Q.matcher(phone.shell("ss -u -a -n 2>&1"));
            while (m.find()) if (Integer.parseInt(m.group(2)) == port) return Long.parseLong(m.group(1));
            Matcher p = PROC_LINE.matcher(phone.shell("cat /proc/net/udp /proc/net/udp6 2>/dev/null"));
            while (p.find()) if (Integer.parseInt(p.group(1), 16) == port) return Long.parseLong(p.group(2), 16);
            return -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Every datagram the wire passed on so far, to the phone or to the PC. */
    private static long sum(Wire wire, boolean toPhone) {
        long n = 0;
        for (int type = 0; type < 256; type++) n += toPhone ? wire.toPhone(type) : wire.toPc(type);
        return n;
    }

    @Test
    void aSendingThatStandsStillOnThePhoneIsFreedByItself() throws Exception {
        Assumptions.assumeTrue(PhoneRig.setUp(), "no Android emulator set up on this PC (docs/TESTING.md)");
        boolean keepApp = System.getenv("BENCH_KEEP_APP") != null;
        AppRig.scenario("phone-socket-stands-still").drones(0).run(rig -> {
            Ui ui = rig.ui;
            ui.autoShot(false);
            Pilot pilot = new Pilot(rig);
            PhoneRig phone = new PhoneRig(rig.film);
            phone.ensureRunning();
            phone.adb("root"); // for `ss`: another app's sockets are root's to see
            Ui.pause(3000);
            phone.stopTheOther(); // Q-006: the bench build flies here; the released app's package, if there, gives up the port
            if (!keepApp) phone.install(phone.appApk());
            phone.grantPermissions();
            phone.forceStop();
            phone.launch();
            if (phone.texts().contains("START")) phone.tap("START");
            int phonePort = PhoneRig.linkOnThePhone();
            int hostPort = phone.forwardUdp(phonePort);
            try (Wire wire = new Wire(InetAddress.getLoopbackAddress(), hostPort)) {
                wire.rule(BadLink.clean(39));
                rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
                rig.fleet.startHeartbeat();
                @SuppressWarnings("rawtypes")
                JComboBox phones = ui.all(JComboBox.class).get(0);
                ui.until("the phone in the PC's PHONE list", 60_000, () -> phones.getItemCount() > 0 && String.valueOf(phones.getItemAt(0)).contains("online"));
                String from = phone.now();
                pilot.connect();
                rig.waitForLog("Video: H.26", 60_000);
                Ui.pause(5000);
                ui.shot("flown on a clean network");
                rig.check(sendQueue(phone, phonePort) >= 0, "the phone's link socket can be read (port " + phonePort + ")");

                long t0 = System.currentTimeMillis(), end = t0 + 10 * 60_000L;
                StringBuilder timeline = new StringBuilder();
                int freed = 0;
                long anewSeen = 0, worstBackMs = 0, lostSince = -1, peakQueue = 0, lostLongest = 0, nextLine = 0, toPhone0 = sum(wire, true), toPc0 = sum(wire, false);
                boolean gaveUp = false;
                long nextScan = 0;
                int scans = 0;
                while (System.currentTimeMillis() < end && freed < 2) {
                    Ui.pause(1000);
                    long now = System.currentTimeMillis();
                    long queue = sendQueue(phone, phonePort);
                    peakQueue = Math.max(peakQueue, queue);
                    boolean ready = pilot.linkUp();
                    // The emulator's Wi-Fi keeps more of what is sent while the phone scans for networks (a minute and
                    // a half to a full socket then, a quarter of an hour without): from the third minute on the
                    // phone is made to scan every five seconds, until its socket has been full once
                    if (anewSeen == 0 && now - t0 > 180_000 && now >= nextScan) {
                        nextScan = now + 5000;
                        phone.shell("cmd wifi start-scan");
                        scans++;
                    }
                    lostSince = ready ? -1 : lostSince < 0 ? now : lostSince;
                    if (lostSince >= 0) lostLongest = Math.max(lostLongest, now - lostSince);
                    String log = phone.logSince(from);
                    long anew = log.lines().filter(l -> l.contains(PhoneWords.LINK_SOCKET)).count();
                    if (now >= nextLine || anew > anewSeen || !ready) {
                        nextLine = now + 5000;
                        timeline.append(String.format("%6.1f s  link %s  %6d B wait  wire: to the phone +%d, to the PC +%d  new sessions %d  socket opened anew %d%n", (now - t0) / 1000.0,
                                ready ? "READY" : "lost ", queue, sum(wire, true) - toPhone0, sum(wire, false) - toPc0, log.lines().filter(l -> l.contains(PhoneWords.NEW_SESSION_SAME_PC)).count(), anew));
                    }
                    if (anew > anewSeen) {
                        anewSeen = anew;
                        ui.shot("the phone opened its link socket anew (" + queue + " bytes wait now)");
                        long since = lostSince >= 0 ? lostSince : now;
                        boolean back;
                        try {
                            ui.until("the link back by itself", 20_000, () -> pilot.linkUp());
                            back = true;
                        } catch (Throwable t) {
                            back = false;
                        }
                        long backMs = System.currentTimeMillis() - since;
                        rig.check(back, "the socket opened anew: the link is back by itself" + (back ? ", " + backMs / 1000.0 + " s after the PC lost it" : " - not within 20 s"));
                        if (!back) break;
                        freed++;
                        worstBackMs = Math.max(worstBackMs, backMs);
                        lostSince = -1;
                        Ui.pause(3000);
                        long after = sendQueue(phone, phonePort);
                        rig.check(after < 30_000, "and what waited is gone: " + after + " bytes wait on the new socket");
                        ui.shot("the link back by itself");
                    } else if (lostSince >= 0 && now - lostSince >= 30_000) {
                        gaveUp = true;
                        ui.shot("the link lost for half a minute (" + queue + " bytes wait on the phone)");
                        rig.check(false, "the link was lost for 30 s and did not come back by itself: " + queue + " bytes wait on the phone's socket, opened anew " + anew + " time(s)");
                        break;
                    }
                }
                String log = phone.logSince(from);
                Files.writeString(rig.film.dir().resolve("phone-log.txt"), log, StandardCharsets.UTF_8);
                Files.writeString(rig.film.dir().resolve("standstill.txt"), timeline.toString(), StandardCharsets.UTF_8);
                log.lines().filter(l -> l.contains(PhoneWords.LINK_SOCKET)).forEach(l -> rig.film.remark("The phone: " + l.substring(l.indexOf(PhoneWords.LINK_SOCKET))));
                rig.film.remark("Flown for " + (System.currentTimeMillis() - t0) / 1000 + " s: the most that waited to leave the phone " + peakQueue + " bytes; the phone opened its socket anew "
                        + anewSeen + " time(s); the link came back by itself " + freed + " time(s), at the slowest " + worstBackMs / 1000.0 + " s after the PC lost it; the longest the PC was without its link "
                        + lostLongest / 1000.0 + " s; " + scans + " Wi-Fi scans asked for." + (anewSeen == 0 && !gaveUp ? " The emulator did not fill the socket this time: nothing is proven by this run." : ""));
                rig.check(!log.contains(PhoneWords.ANDROID_FATAL), "no crash in the phone app's log");
                if (pilot.linkUp()) pilot.standby();
            } finally {
                phone.close();
            }
        });
    }
}
