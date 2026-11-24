package de.droiddrone.bench.phone;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.FleetRows;
import de.droiddrone.bench.PhoneRig;
import de.droiddrone.bench.PhoneWords;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.bench.hw.AppLinkLog;
import de.droiddrone.common.PacketIds;
import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.testing.Waits;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real phone among made-up drones in FLEET: three made-up drones and the real phone app - in the emulator, or this
 * bench's real phone (the task benchHwPhone) - through the bench's wire, in the app's fleet as PhoneNight has it. The
 * map window's FLEET panel is opened and the real phone's row found by its name; then what a pilot relies on there:
 * the row is there with its name, heard, its battery from its own answer; PILOT UAV - CONNECTED; DEEP SLEEP from the
 * LINK card (a check every minute) - ASLEEP; through two of its checks; PILOT UAV - back at its next check, CONNECTED;
 * STANDBY MODE - STANDBY. Every few seconds the made-up drones' rows are read too: they must say what they said at the
 * start, and none of them may arm; the app's link log must never say armed. The phone ends in STANDBY, as after the
 * other phone scenarios; nothing is queued for it (FLEET's own buttons and OUT OF ACTION are not pressed).
 * <p>
 * Why the LINK card's DEEP SLEEP and not FLEET's: a mode from FLEET tells the phone to check in at the PC's own link
 * port, at the address it saw - around the bench's wire (on the emulator that is this PC's loopback, so it still comes
 * from the wire's address; a real phone would check in straight from its own). The LINK card's sleep has it check in
 * where its link came from: the wire, as PhoneNight has proved for nights.
 * <p>
 * What the wire passes (FLEET's answers): the phone's name replies - its FLEET status, battery, mode, check interval -
 * reach the PC's name scan while no link is open: the scan is the only socket of the PC speaking through the wire then.
 * The wire has one way back, to the socket of the PC that spoke through it last; the PC speaks to a phone from three -
 * each scan's own (closed after a second and a half), the link's, the link port's answering one. So a sleeping phone's
 * check-in reaches the PC only while nothing but the link port spoke last: after the scan's once-a-minute question to
 * a resting phone it goes to that scan's closed socket. FLEET then calls the phone UNKNOWN once a check-in is late.
 * The scenario counts it all into the film - the phone's own checks (its log), the check-ins the wire took from the
 * phone and passed on, how often the PC heard the phone (its row's "heard") - and judges only what the wire carries:
 * ASLEEP or UNKNOWN while it sleeps, never awake.
 */
@Tag("emulator")
@Tag("hwphone")
class PhoneFleetScenario {
    /** How often the rows are read while the phone sleeps. */
    private static final long READ_EVERY_MS = 2000;

    /** The control with these words in the main window (FLEET in the map window has its own DEEP SLEEP, STANDBY ...). */
    private static Component inMain(AppRig rig, Ui ui, String words) {
        for (Component c : ui.all(words)) {
            if (Ui.edt(() -> SwingUtilities.getWindowAncestor(c)) == rig.main()) return c;
        }
        throw new AssertionError("The main window shows no \"" + words + "\"");
    }

    @SuppressWarnings("rawtypes")
    private static JComboBox inMain(AppRig rig, Ui ui, java.util.function.Predicate<JComboBox> which) {
        for (JComboBox c : ui.all(JComboBox.class)) {
            if (Ui.edt(() -> SwingUtilities.getWindowAncestor(c) == rig.main() && which.test(c))) return c;
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private static List<String> entries(JComboBox list) {
        return Ui.edt(() -> {
            List<String> e = new ArrayList<>();
            for (int i = 0; i < list.getItemCount(); i++) e.add(String.valueOf(list.getItemAt(i)));
            return e;
        });
    }

    private static String badge(Ui ui, String phone) {
        FleetRows.Row r = FleetRows.of(FleetRows.read(ui), phone);
        return r == null ? "" : r.badge();
    }

    @Test
    void theRealPhoneAmongMadeUpDronesInFleet() throws Exception {
        PhoneRig.Target target = PhoneRig.Target.ofRun(); // the emulator; in a hardware run this bench's real phone
        Assumptions.assumeTrue(target.setUp(), "no Android emulator set up on this PC (docs/TESTING.md)");
        AppRig.scenario("phone-fleet").drones(0).reach(target.reach()).run(rig -> {
            Ui ui = rig.ui;
            Pilot pilot = new Pilot(rig);
            rig.radio.sticksAtRest();
            rig.radio.aux(1, -1); // ARM off, all through
            long t0 = System.currentTimeMillis();
            PhoneRig phone = new PhoneRig(rig.film, target);
            phone.ensureRunning();
            phone.installApp(); // the emulator: the newest build; a real phone keeps the app it has
            phone.grantPermissions();
            phone.forceStop();
            phone.launch();
            phone.startIfAsked(); // START, where it shows; behind a real phone's lock screen the service has to have started by itself
            phone.checkBattery(); // a real phone: too hot, the wait is here; too low, the run stops
            String from = phone.now();
            InetSocketAddress at = phone.linkAddress(); // the emulator: a forwarded port on loopback; a real phone: its address
            try (Wire wire = new Wire(at.getAddress(), at.getPort())) {
                // the real phone first, alone: its own name is read before the made-up drones take theirs
                rig.fleet.announceExtra(new VirtualFleet.Drone("127.0.0.1", wire.port()));
                rig.fleet.startHeartbeat();
                @SuppressWarnings("rawtypes")
                JComboBox phones = inMain(rig, ui, c -> true); // the PHONE list: the main window's first list
                if (phones == null) throw new AssertionError("No PHONE list in the main window");
                ui.until("the phone in the PC's PHONE list", 60_000, () -> entries(phones).stream().anyMatch(e -> e.contains("online") && !PhoneFleet.nameOf(e).isEmpty()));
                String real = PhoneFleet.nameOf(entries(phones).stream().filter(e -> e.contains("online")).findFirst().orElse(""));
                rig.film.remark("The real phone is " + real + " (its own name, through the bench's wire).");

                // three made-up drones beside it, named as the fleet names its drones - never the phone's own name
                List<String> madeUp = PhoneFleet.madeUpNames(real, 3);
                for (String n : madeUp) rig.fleet.addAtLaunch(n, Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON);
                ui.until("the made-up drones online beside it", 60_000, () -> entries(phones).stream().filter(e -> e.contains("online")).count() >= 4);
                String mine = entries(phones).stream().filter(e -> PhoneFleet.nameOf(e).equalsIgnoreCase(real)).findFirst().orElseThrow();
                ui.choose(phones, mine); // PILOT UAV means the real phone
                rig.film.remark("PHONE list: " + entries(phones) + "; chosen: " + mine.trim());

                FleetRows.open(ui);
                ui.until("FLEET's four rows, each with its card", 30_000, () -> FleetRows.read(ui).size() >= 4);
                List<FleetRows.Row> rows = FleetRows.read(ui);
                rig.film.remark("FLEET at the start: " + rows);
                ui.shot("FLEET: the real phone " + real + " among three made-up drones");
                FleetRows.Row row = FleetRows.of(rows, real);
                rig.check(row != null, "FLEET has a row for the real phone, by its name: " + real);
                rig.check(row != null && PhoneFleet.ACTIVE.equals(row.badge()), "its row says " + PhoneFleet.ACTIVE + " (heard, awake): " + (row == null ? "-" : row.badge()));
                rig.check(row != null && row.heardAgoMs() >= 0 && row.heardAgoMs() < 30_000, "its row was heard within 30 s: " + (row == null ? "-" : row.heardAgoMs() + " ms"));
                rig.check(row != null && row.phonePercent() >= 0, "its row has the phone's battery, from the phone's own answer through the wire: "
                        + (row == null ? "-" : row.phonePercent() + " %"));
                rig.check(wire.toPc(PacketIds.NAME_REPLY) > 0, "the phone's FLEET answers (its name replies, with its status) went through the wire: "
                        + wire.toPc(PacketIds.NAME_REPLY));
                Map<String, String> first = new LinkedHashMap<>();
                for (String n : madeUp) {
                    FleetRows.Row r = FleetRows.of(rows, n);
                    rig.check(r != null && PhoneFleet.ACTIVE.equals(r.badge()), "the made-up drone " + n + " has its row, " + PhoneFleet.ACTIVE + ": " + r);
                    if (r != null) first.put(n, r.badge());
                }

                // what the made-up drones' rows did and whether anything armed, from here to the end
                Set<String> changes = new LinkedHashSet<>();
                Set<String> armed = new LinkedHashSet<>();
                Runnable look = () -> {
                    changes.addAll(PhoneFleet.changed(first, FleetRows.read(ui)));
                    for (String n : madeUp) if (rig.drone(n).readout().armed()) armed.add(n);
                };

                // ---- PILOT UAV: CONNECTED
                ui.choose(checkEvery(rig, ui), "1 min");
                pilot.connect();
                rig.check(Waits.until(() -> PhoneFleet.CONNECTED.equals(badge(ui, real)), 15_000), "PILOT UAV: its row says " + PhoneFleet.CONNECTED + " (" + badge(ui, real) + ")");
                look.run();
                ui.shot("FLEET: " + real + " flown from this PC");

                // ---- DEEP SLEEP from the LINK card, a check every minute: ASLEEP
                int presses = 0, linesBefore = rig.log().size();
                boolean asked = false;
                while (!asked) {
                    ui.until("the link up for DEEP SLEEP", 30_000, pilot::linkUp);
                    ui.click(inMain(rig, ui, "DEEP SLEEP"));
                    presses++;
                    int seen = linesBefore;
                    asked = Waits.until(() -> {
                        List<String> lines = rig.log();
                        return lines.subList(Math.min(seen, lines.size()), lines.size()).stream().anyMatch(l -> l.contains("DEEP SLEEP asked"));
                    }, 6000);
                    if (!asked && presses >= 6) throw new AssertionError("DEEP SLEEP pressed " + presses + " times and never taken");
                    if (!asked) Ui.pause(4000); // WAIT while the link is settling, as a pilot presses again
                }
                ui.until("the link ended, the phone asleep", 120_000, () -> ui.has("PILOT UAV"));
                rig.check(Waits.until(() -> PhoneFleet.ASLEEP.equals(badge(ui, real)), 20_000), "DEEP SLEEP: its row says " + PhoneFleet.ASLEEP + " (" + badge(ui, real) + ")");
                ui.shot("FLEET: " + real + " asleep");
                if (phone.sleepsOnBattery()) phone.onBattery(); // a real phone on its cable would not doze: pretended off it

                // ---- through two of its checks: the rows read every 2 s
                long helloFromPhone = wire.fromPhone(PacketIds.SLEEP_HELLO), helloToPc = wire.toPc(PacketIds.SLEEP_HELLO);
                List<Long> heard = new ArrayList<>();
                List<String> asleepBadges = new ArrayList<>();
                StringBuilder table = new StringBuilder("s\t" + real + "\theard s");
                for (String n : madeUp) table.append('\t').append(n);
                table.append('\n');
                long sleptAt = System.currentTimeMillis();
                while (System.currentTimeMillis() - sleptAt < 130_000) {
                    List<FleetRows.Row> now = FleetRows.read(ui);
                    FleetRows.Row r = FleetRows.of(now, real);
                    String b = r == null ? "(no row)" : r.badge();
                    asleepBadges.add(b);
                    heard.add(r == null ? -1 : r.heardAgoMs());
                    table.append((System.currentTimeMillis() - sleptAt) / 1000).append('\t').append(b).append('\t').append(r == null ? "" : r.heardAgoMs() / 1000);
                    for (String n : madeUp) {
                        FleetRows.Row m = FleetRows.of(now, n);
                        table.append('\t').append(m == null ? "(no row)" : m.badge());
                    }
                    table.append('\n');
                    look.run();
                    Ui.pause(READ_EVERY_MS);
                }
                Files.writeString(rig.film.dir().resolve("fleet-while-asleep.tsv"), table.toString(), StandardCharsets.UTF_8);
                ui.shot("FLEET: " + real + " after two of its checks");
                long checks = PhoneFleet.count(phone.logSince(from), PhoneWords.SLEEP_CHECK);
                rig.check(checks >= 1, "asleep for two minutes the phone woke itself to check at least once (" + checks + " time(s))");
                List<String> awake = asleepBadges.stream().filter(b -> !PhoneFleet.WHILE_ASLEEP.contains(b)).distinct().toList();
                rig.check(awake.isEmpty(), "while it slept its row said " + PhoneFleet.ASLEEP + " or " + PhoneFleet.UNKNOWN + ", never awake: "
                        + asleepBadges.stream().distinct().toList());
                long sent = wire.fromPhone(PacketIds.SLEEP_HELLO) - helloFromPhone, passed = wire.toPc(PacketIds.SLEEP_HELLO) - helloToPc;
                int heardAgain = PhoneFleet.heardAgain(heard);
                rig.film.remark("Asleep for " + (System.currentTimeMillis() - sleptAt) / 1000 + " s: the phone checked " + checks + " time(s) (its log); the wire took "
                        + sent + " check-in(s) from it and passed " + passed + " on to the PC's side; the PC heard the phone again " + heardAgain
                        + " time(s) (its row's HEARD). The wire hands them to the PC's socket that spoke through it last - a name scan's, closed by then,"
                        + " once the scan has asked the resting phone (see the scenario's notes). Its row said: " + asleepBadges.stream().distinct().toList()
                        + " (fleet-while-asleep.tsv).");
                if (asleepBadges.contains(PhoneFleet.UNKNOWN)) {
                    rig.film.remark("FLEET called the sleeping phone " + PhoneFleet.UNKNOWN + " when a check-in it did not hear was late - the bench's wire, not the"
                            + " phone: on a real network the check-in goes to the PC's link port.");
                }

                // ---- PILOT UAV: back at its next check, CONNECTED
                long pressed = System.currentTimeMillis();
                ui.click("PILOT UAV");
                ui.until("the sleeping phone back at its next check", 150_000, pilot::linkUp);
                long wokeS = (System.currentTimeMillis() - pressed) / 1000;
                rig.check(wokeS <= 95, "PILOT UAV had the sleeping phone back after " + wokeS + " s (it checks every minute)");
                if (phone.sleepsOnBattery()) phone.plugged();
                rig.check(Waits.until(() -> PhoneFleet.CONNECTED.equals(badge(ui, real)), 15_000), "woken: its row says " + PhoneFleet.CONNECTED + " again (" + badge(ui, real) + ")");
                look.run();
                ui.shot("FLEET: " + real + " back, flown from this PC");

                // ---- STANDBY MODE: STANDBY (the phone ends as after the other phone scenarios)
                Ui.pause(5000);
                pilot.standby();
                rig.check(Waits.until(() -> PhoneFleet.STANDBY.equals(badge(ui, real)), 45_000), "STANDBY MODE: its row says " + PhoneFleet.STANDBY + " (" + badge(ui, real) + ")");
                look.run();
                ui.shot("FLEET: " + real + " in standby");

                // ---- the made-up drones, nothing armed, the phone
                rig.check(changes.isEmpty(), "the made-up drones' rows said what they said at the start all through: " + (changes.isEmpty() ? "yes" : changes));
                rig.check(armed.isEmpty(), "no made-up drone armed by any of it" + (armed.isEmpty() ? "" : ": " + armed));
                List<AppLinkLog.Row> linkRows = AppLinkLog.read(rig.sandbox.logsDir(), t0);
                long armedRows = PhoneFleet.armed(linkRows);
                rig.check(armedRows == 0, "the app's link log never says armed (" + linkRows.size() + " rows, " + armedRows + " armed)");
                String log = phone.logSince(from);
                Files.writeString(rig.film.dir().resolve("phone-log.txt"), log, StandardCharsets.UTF_8);
                rig.check(!log.contains(PhoneWords.ANDROID_FATAL), "no crash in the phone app's log");
                rig.check(phone.serviceRunning(), "the phone app's service is running");
                rig.film.remark("FLEET at the end: " + FleetRows.read(ui));
            } finally {
                phone.close();
            }
        });
    }

    /** CHECK EVERY in the main window (FLEET's own list says EVERY 5M, not 5 min). */
    @SuppressWarnings("rawtypes")
    private static JComboBox checkEvery(AppRig rig, Ui ui) throws InterruptedException {
        JComboBox[] found = new JComboBox[1];
        // waited for: a row being laid out anew has no height for a moment, and the driver sees nothing of none
        Waits.until(() -> (found[0] = inMain(rig, ui, c -> c.getItemCount() == 4 && "5 min".equals(String.valueOf(c.getItemAt(3))))) != null, 10_000);
        if (found[0] == null) throw new AssertionError("No CHECK EVERY list showing");
        return found[0];
    }
}
