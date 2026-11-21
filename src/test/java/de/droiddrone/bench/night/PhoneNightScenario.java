package de.droiddrone.bench.night;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.BadLink;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.bench.Watch;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real phone app left running for hours ({@code BENCH_HOURS}) - in the Android emulator, with the real Windows
 * app and the bench's wire between them. The phone app has had the least testing of all: two scenarios of a minute
 * each. Here it does, round after round, what a phone on a drone does in a day: flown over a link that is poor in a
 * new way every minute with a cut in the middle; then put to DEEP SLEEP with a check every minute, left through at
 * least one check of its own, and woken with PILOT UAV; then STANDBY, and round again.
 * <p>
 * What must hold: the link comes back after every cut; the phone sleeps when told, wakes by its own alarm to check,
 * and PILOT UAV has it back within its check interval and a little; the app never crashes; and its memory does not
 * climb over the hours. One row a round goes to soak/phone-night.tsv. (No flight controller: the emulator has no USB.)
 * <p>
 * On a real phone too (the task benchHwPhone, {@code --tests *PhoneNight*} with BENCH_HOURS): the app that is on the
 * phone, the link to its real address. A phone on adb's cable charges and so never dozes: each round's sleep is
 * therefore spent pretended off the cable and forced into deep doze ({@code PhoneRig.onBattery} - what that does not
 * prove is written there), taken back once PILOT UAV has it again. Its battery is looked at before every round: hotter
 * than the bench's limit the scenario waits there, with the camera off; too low it stops.
 */
@Tag("night")
@Tag("hwphone")
class PhoneNightScenario {
    private static final Pattern PSS = Pattern.compile("TOTAL(?: PSS)?:?\\s+([\\d,]+)");

    private static long count(String text, String part) {
        long n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    /** The phone app's memory as Android counts it (PSS), in MB; -1 when it can't be read. */
    private static long phoneMb(PhoneRig phone) {
        try {
            Matcher m = PSS.matcher(phone.shell("dumpsys meminfo " + phone.app()));
            return m.find() ? Long.parseLong(m.group(1).replace(",", "")) / 1024 : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @SuppressWarnings("rawtypes")
    private static JComboBox checkEvery(Ui ui) {
        // waited for: a row being laid out anew has no height for a moment, and the driver sees nothing of none (2026-11-21)
        for (long until = System.currentTimeMillis() + 10_000; System.currentTimeMillis() < until; Ui.pause(200)) {
            for (JComboBox c : ui.all(JComboBox.class)) {
                if (Ui.edt(() -> c.getItemCount() == 4 && "5 min".equals(String.valueOf(c.getItemAt(3))))) return c;
            }
        }
        throw new AssertionError("No CHECK EVERY list showing");
    }

    @Test
    void theRealPhoneAppForHours() throws Exception {
        PhoneRig.Target target = PhoneRig.Target.ofRun();
        Assumptions.assumeTrue(target.setUp(), "no Android emulator set up on this PC (docs/TESTING.md)");
        long endAt = System.currentTimeMillis() + Math.max(8 * 60_000L, Night.runMs() - 5 * 60_000L);
        AppRig.scenario("night-phone").drones(0).reach(target.reach()).run(rig -> {
            Ui ui = rig.ui;
            ui.autoShot(false);
            Pilot pilot = new Pilot(rig);
            Random random = new Random(Long.getLong("bench.seed", 20261119L));
            PhoneRig phone = new PhoneRig(rig.film, target);
            phone.ensureRunning();
            phone.installApp(); // the emulator: the newest build; a real phone keeps the app it has
            phone.grantPermissions();
            phone.forceStop();
            phone.launch();
            if (phone.texts().contains("START")) phone.tap("START");
            phone.shell("logcat -G 16M"); // the night's lines are read round by round, by time; the buffer holds hours all the same
            java.net.InetSocketAddress link = phone.linkAddress(); // the emulator: a forwarded port on loopback; a real phone: its address
            BadLink clean = BadLink.clean(21);
            Night.Table table = new Night.Table("phone-night", "minutes", "round", "phone app MB", "PC heap MB", "PC threads", "woke after s",
                    "phone's own checks", "new sessions on the phone", "socket opened anew");
            try (Wire wire = new Wire(link.getAddress(), link.getPort())) {
                wire.rule(clean);
                rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
                rig.fleet.startHeartbeat();
                @SuppressWarnings("rawtypes")
                JComboBox phones = ui.all(JComboBox.class).get(0);
                ui.until("the phone in the PC's PHONE list", 60_000, () -> phones.getItemCount() > 0 && String.valueOf(phones.getItemAt(0)).contains("online"));
                long t0 = System.currentTimeMillis();
                int round = 0, failures = 0, failedInARow = 0, appRestarts = 0;
                long reopened = 0;
                long firstMb = -1, lastMb = -1, slowestWake = 0;
                while ((System.currentTimeMillis() < endAt || round < 1) && failures < 5) {
                    long wokeS = -1;
                    phone.checkBattery(); // a real phone: too hot, the wait is here, between rounds, with its camera off; too low, the night ends
                    String roundFrom = phone.now();
                    boolean roundFailed = false;
                    try {
                        // ---- flown: five minutes on a link that is poor in a new way each minute, a cut in the middle
                        wire.rule(clean);
                        ui.choose(checkEvery(ui), "1 min");
                        pilot.connect();
                        if (round == 0) rig.waitForLog("Video: H.26", 60_000);
                        Ui.pause(4000);
                        if (round == 0) {
                            ui.shot("the phone's camera picture on the PC");
                            phone.shot("phone: flown by the PC");
                        }
                        for (int minute = 0; minute < 5; minute++) {
                            BadLink poor = new BadLink("poor " + minute, random.nextLong()).delay(20 + random.nextInt(120)).jitter(random.nextInt(80))
                                    .loss(random.nextInt(10));
                            wire.rule(poor);
                            Ui.pause(25_000);
                            if (minute == 2) {
                                long cut = 3000 + random.nextInt(6000);
                                poor.cut(cut, random.nextInt(4) == 0 ? BadLink.Way.TO_PC : BadLink.Way.BOTH);
                                Ui.pause(cut + 1000);
                            }
                            Ui.pause(30_000);
                        }
                        wire.rule(clean);
                        int at = round + 1;
                        ui.until("round " + at + ": the link good again on a good network", 60_000, () -> pilot.linkUp());
                        rig.check(phone.sees("Connected to PC"), "round " + at + ": the phone's screen says Connected to PC after five poor minutes and a cut");

                        // ---- DEEP SLEEP, a check every minute; left through a check of its own; woken by PILOT UAV
                        // as a pilot presses it: a press the app does not take (its notice says why - WAIT while the
                        // link is coming back, as it does every minute on the emulator) is pressed again a little later
                        int presses = 0;
                        String refused = "";
                        while (true) {
                            int linesBefore = rig.log().size();
                            java.util.Set<String> before = new java.util.HashSet<>(ui.texts());
                            ui.until("round " + at + ": the link up for DEEP SLEEP", 30_000, pilot::linkUp);
                            ui.click("DEEP SLEEP");
                            presses++;
                            Ui.pause(400);
                            java.util.List<String> notice = new java.util.ArrayList<>(ui.texts());
                            notice.removeAll(before);
                            long end = System.currentTimeMillis() + 6000;
                            boolean asked = false;
                            while (!asked && System.currentTimeMillis() < end) {
                                java.util.List<String> lines = rig.log();
                                asked = lines.subList(Math.min(linesBefore, lines.size()), lines.size()).stream().anyMatch(l -> l.contains("DEEP SLEEP asked"));
                                if (!asked) Ui.pause(200);
                            }
                            if (asked) break;
                            refused += (refused.isEmpty() ? "" : "; ") + (notice.isEmpty() ? "no word" : String.join(" / ", notice.subList(0, Math.min(3, notice.size()))));
                            if (presses >= 6) throw new AssertionError("DEEP SLEEP pressed " + presses + " times and never taken: " + refused);
                            Ui.pause(4000);
                        }
                        if (presses > 1) rig.film.remark("Round " + at + ": DEEP SLEEP was taken at the " + presses + ". press; before that the app said: " + refused);
                        ui.until("round " + at + ": the link ended, the phone asleep", 120_000, () -> ui.has("PILOT UAV"));
                        if (phone.sleepsOnBattery()) phone.onBattery(); // a real phone on its cable would not doze: pretended off it
                        Ui.pause(100_000 + random.nextInt(40_000));
                        long alarms = count(phone.logSince(roundFrom), "Sleep check alarm fired");
                        rig.check(alarms >= 1, "round " + at + ": asleep for two minutes the phone woke itself to check at least once (" + alarms + " time(s))");
                        long pressed = System.currentTimeMillis();
                        ui.click("PILOT UAV");
                        ui.until("round " + at + ": the sleeping phone back at its next check", 150_000, () -> pilot.linkUp());
                        wokeS = (System.currentTimeMillis() - pressed) / 1000;
                        slowestWake = Math.max(slowestWake, wokeS);
                        rig.check(wokeS <= 95, "round " + at + ": PILOT UAV had the sleeping phone back after " + wokeS + " s (it checks every minute)");
                        if (phone.sleepsOnBattery()) phone.plugged();
                        Ui.pause(5000);
                        pilot.standby();
                        Ui.pause(5000);
                    } catch (Throwable t) {
                        failures++;
                        roundFailed = true;
                        String why = String.valueOf(t.getMessage());
                        rig.check(false, "round " + (round + 1) + " (" + (System.currentTimeMillis() - t0) / 60_000 + " min in) went wrong: " + (why.length() > 300 ? why.substring(0, 300) + " ..." : why));
                        try {
                            // what stood where: the datagrams by way through the bench's wire, the phone's sockets and threads
                            long toPc = 0, fromPhone = 0, toPhone = 0, fromPc = 0;
                            for (int type = 0; type < 256; type++) {
                                toPc += wire.toPc(type);
                                fromPhone += wire.fromPhone(type);
                                toPhone += wire.toPhone(type);
                                fromPc += wire.fromPc(type);
                            }
                            String pid = phone.shell("pidof " + phone.app()).trim();
                            String nl = System.lineSeparator();
                            Files.writeString(rig.film.dir().resolve("round-" + (round + 1) + "-wrong.txt"), "Round " + (round + 1) + ": " + why + nl + nl
                                    + "The wire so far: from the PC " + fromPc + ", on to the phone " + toPhone + "; from the phone " + fromPhone + ", on to the PC " + toPc + nl + nl
                                    + "The phone's UDP sockets (Send-Q: what waits to leave):" + nl + phone.socketStats() + nl + nl // ss as root; else the bench build's own word
                                    + "The phone app's threads:" + nl + phone.shell("ps -T -p " + pid + " -o TID,S,NAME 2>&1") + nl + nl
                                    + "Its log since the round began:" + nl + phone.logSince(roundFrom), StandardCharsets.UTF_8);
                            ui.shot("round " + (round + 1) + " went wrong");
                            phone.shot("phone: round " + (round + 1) + " went wrong");
                        } catch (Throwable ignored) {
                        }
                        wire.rule(clean);
                        if (phone.sleepsOnBattery()) phone.plugged(); // a round that went wrong asleep must not leave the next one dozing
                        long until = System.currentTimeMillis() + 6 * 60_000L;
                        while (!ui.has("PILOT UAV") && System.currentTimeMillis() < until) {
                            if (ui.has("STANDBY MODE")) ui.click("STANDBY MODE");
                            if (ui.has("Keep flying")) ui.click("Standby mode");
                            Ui.pause(5000);
                        }
                        if (!ui.has("PILOT UAV")) {
                            rig.check(false, "the PC could not be brought back to PILOT UAV after that");
                            break;
                        }
                    }
                    round++;
                    String log = phone.logSince(roundFrom);
                    long mb = phoneMb(phone);
                    if (firstMb < 0) firstMb = mb;
                    lastMb = mb;
                    long sessions = count(log, "a new session") + count(log, "New session from");
                    long anew = count(log, "Link socket:");
                    reopened += anew;
                    table.row((System.currentTimeMillis() - t0) / 60_000.0, round, mb, Watch.usedMb(), rig.watch.threads(), wokeS,
                            count(log, "Sleep check alarm fired"), sessions, anew);
                    if (anew > 0) {
                        rig.film.remark("Round " + round + ": the phone opened its link socket anew " + anew + " time(s) - nothing had left it for seconds though it heard the PC.");
                    }
                    // a cut one way gives a new session a second (F-001); hundreds mean the phone answers into nothing
                    rig.check(sessions <= 60, "round " + round + ": the phone began " + sessions + " new sessions (60 at most: a session a second for minutes means its answers reach nobody)");
                    rig.check(!log.contains("FATAL EXCEPTION"), "round " + round + ": no crash in the phone app's log");
                    rig.check(phone.serviceRunning(), "round " + round + ": the phone app's service is running");
                    if (log.contains("FATAL EXCEPTION")) break;
                    // two rounds wrong in a row: the app is started again, as its pilot would - said, and counted
                    failedInARow = roundFailed ? failedInARow + 1 : 0;
                    if (failedInARow >= 2) {
                        appRestarts++;
                        failedInARow = 0;
                        rig.check(false, "after round " + round + " the phone app had to be started again: two rounds in a row had gone wrong");
                        phone.forceStop();
                        phone.launch();
                        Ui.pause(3000);
                        if (phone.texts().contains("START")) phone.tap("START");
                        Ui.pause(15_000);
                    }
                }
                Files.writeString(rig.film.dir().resolve("phone-log.txt"), phone.log(), StandardCharsets.UTF_8);
                phone.shot("phone: at the end of the night");
                rig.film.remark(round + " rounds in " + (System.currentTimeMillis() - t0) / 60_000 + " min, " + failures + " gone wrong; the slowest wake " + slowestWake
                        + " s after PILOT UAV; the phone app's memory " + firstMb + " MB after the first round, " + lastMb + " MB after the last; its link socket opened anew "
                        + reopened + " time(s); the app started again " + appRestarts + " time(s).");
                rig.check(round - failures >= 1, "at least one whole round ran");
                if (round >= 6 && firstMb > 0 && lastMb > 0) {
                    rig.check(lastMb < firstMb * 1.5 + 60, "the phone app's memory does not climb over the rounds: " + firstMb + " MB, then " + lastMb + " MB");
                }
            } finally {
                phone.close();
            }
        });
    }
}
