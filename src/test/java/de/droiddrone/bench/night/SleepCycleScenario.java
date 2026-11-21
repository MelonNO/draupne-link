package de.droiddrone.bench.night;

import de.droiddrone.bench.AppRig;
import de.droiddrone.bench.Pilot;
import de.droiddrone.bench.Ui;
import de.droiddrone.bench.Watch;
import de.droiddrone.pc.fleet.VirtualDrone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Sleep and wake, for hours ({@code BENCH_HOURS}), in real time - the one thing no quick test can do: a phone put to
 * DEEP SLEEP or OVERWATCH listens for its PC for a few seconds every one, two or five minutes, and PILOT UAV reaches
 * it at its next check. Cycle after cycle on three made-up phones: connect, set CHECK EVERY, put it to sleep from
 * the link, leave it for some part of its interval, press PILOT UAV, and time how long the link takes to come.
 * <p>
 * What must hold: the phone is asleep within a minute of being told; nothing wakes it meanwhile; PILOT UAV has the
 * link within the check interval and a little; never a drone armed by any of it; and the app, idle for most of each
 * cycle, does not grow. One row a cycle goes to soak/sleep-cycles.tsv.
 */
@Tag("night")
class SleepCycleScenario {
    private static final int[] EVERY_MIN = {1, 2, 1, 5, 1, 2};

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

    private static final java.util.regex.Pattern SENT = java.util.regex.Pattern.compile("(DEEP SLEEP|OVERWATCH) asked: .*?(Always listening|Checks in every (\\d+) min)");

    /** The check interval the app says it sent with the last sleep it asked for, min (0: always listening); -1 when it said none. */
    private static int sent(List<String> log) {
        for (int i = log.size() - 1; i >= 0; i--) {
            java.util.regex.Matcher m = SENT.matcher(log.get(i));
            if (m.find()) return m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        }
        return -1;
    }

    @Test
    void sleepAndWakeForHours() throws Exception {
        long endAt = System.currentTimeMillis() + Math.max(6 * 60_000L, Night.runMs() - 3 * 60_000L);
        AppRig.scenario("night-sleep-cycles").drones(3).run(rig -> {
            Ui ui = rig.ui;
            ui.autoShot(false);
            Pilot pilot = new Pilot(rig);
            Random random = new Random(Long.getLong("bench.seed", 20261119L));
            Night.Table table = new Night.Table("sleep-cycles", "minutes", "cycle", "check every min", "asleep after s", "woke after s", "heap MB", "threads",
                    "slowest answer ms");
            @SuppressWarnings("rawtypes")
            JComboBox phones = ui.all(JComboBox.class).get(0);
            long t0 = System.currentTimeMillis();
            int cycle = 0, failures = 0, mismatches = 0;
            long slowestWake = 0;
            List<Long> heaps = new ArrayList<>();
            while ((System.currentTimeMillis() < endAt || cycle < 2) && failures < 6) {
                int every = EVERY_MIN[cycle % EVERY_MIN.length];
                if (endAt - System.currentTimeMillis() < (every + 2) * 60_000L) every = 1; // the last cycles are short ones
                boolean overwatch = cycle % 3 == 2;
                String what = (overwatch ? "OVERWATCH" : "DEEP SLEEP") + ", check every " + every + " min";
                try {
                    String entry = String.valueOf(Ui.edt(() -> phones.getItemAt(random.nextInt(Math.max(1, phones.getItemCount())))));
                    if (!entry.equals(String.valueOf(Ui.edt(phones::getSelectedItem)))) ui.choose(phones, entry);
                    String name = String.valueOf(Ui.edt(phones::getSelectedItem)).trim().split("\\s+")[0];
                    VirtualDrone drone = rig.drone(name);
                    rig.radio.handsOff();
                    rig.radio.aux(1, -1);
                    ui.choose(checkEvery(ui), every + " min");
                    pilot.connect();
                    Ui.pause(3000);

                    long told = System.currentTimeMillis();
                    ui.click(overwatch ? "OVERWATCH" : "DEEP SLEEP");
                    pilot.until(name + " to be asleep and its link ended", 90_000, () -> (overwatch ? drone.mode() == VirtualDrone.OVERWATCH : drone.asleep()) && ui.has("PILOT UAV"));
                    long asleepS = (System.currentTimeMillis() - told) / 1000;
                    // What the app sent must be what was chosen: up to F-046 CHECK EVERY kept one value for DEEP SLEEP
                    // and one for OVERWATCH, and a value chosen while the box showed the other mode's went to that
                    // mode (the first night's six red cycles, 43 of 90 in the second). The pilot's rule since
                    // 2026-11-20: one interval for both - so a difference is a fault now, not a remark.
                    int sent = sent(rig.log());
                    if (sent >= 0 && sent != every) {
                        mismatches++;
                        rig.check(false, "F-046: cycle " + (cycle + 1) + ": CHECK EVERY was set to " + every + " min, then " + (overwatch ? "OVERWATCH" : "DEEP SLEEP")
                                + " pressed: the app sent " + (sent == 0 ? "\"always listening\"" : sent + " min"));
                        every = Math.max(1, sent);
                    }
                    int mode = drone.mode();
                    rig.check(mode == (overwatch ? VirtualDrone.OVERWATCH : VirtualDrone.DEEP_SLEEP), "cycle " + (cycle + 1) + ": " + name + " is in the mode asked for ("
                            + what + "): mode " + mode);
                    rig.check(!drone.readout().armed(), "cycle " + (cycle + 1) + ": going to sleep armed nothing");

                    // left alone for some part of its interval, so PILOT UAV comes anywhere between two checks
                    Ui.pause(4000 + random.nextInt(Math.max(1, every * 60_000 - 8000)));
                    // (OVERWATCH listens all the time unless set otherwise: it is in its mode, not "asleep" between checks)
                    rig.check(overwatch ? drone.mode() == VirtualDrone.OVERWATCH : drone.asleep(), "cycle " + (cycle + 1) + ": " + name + " is still "
                            + (overwatch ? "in OVERWATCH" : "asleep") + " - nothing woke it");

                    long pressed = System.currentTimeMillis();
                    ui.click("PILOT UAV");
                    ui.until(name + " to wake at its next check (" + every + " min at most)", (every * 60L + 60) * 1000, () -> ui.has("LINK READY"));
                    long wokeS = (System.currentTimeMillis() - pressed) / 1000;
                    slowestWake = Math.max(slowestWake, wokeS);
                    rig.check(wokeS <= every * 60L + 30, "cycle " + (cycle + 1) + ": PILOT UAV had " + name + " back " + wokeS + " s after the press (" + what + ")");
                    Ui.pause(3000);
                    rig.check(!drone.asleep(), "cycle " + (cycle + 1) + ": " + name + " is awake once the link is up");
                    rig.check(!drone.readout().armed(), "cycle " + (cycle + 1) + ": waking armed nothing");
                    pilot.standby();
                    Ui.pause(1500);

                    cycle++;
                    long heap = Watch.usedMb();
                    heaps.add(heap);
                    table.row((System.currentTimeMillis() - t0) / 60_000.0, cycle, every, asleepS, wokeS, heap, rig.watch.threads(), rig.watch.maxLagMs());
                    if (cycle % 10 == 1) ui.shot("cycle " + cycle + " done (" + name + ", " + what + ", back after " + wokeS + " s)");
                } catch (Throwable t) {
                    // one cycle gone wrong is a finding, not the end of the night: said, the app brought back to
                    // "nothing connected", and on with the next
                    failures++;
                    rig.check(false, "cycle " + (cycle + 1) + " (" + what + ", " + (System.currentTimeMillis() - t0) / 60_000 + " min in) went wrong: " + t.getMessage());
                    try {
                        ui.shot("cycle " + (cycle + 1) + " went wrong");
                        long until = System.currentTimeMillis() + 8 * 60_000L;
                        while (!ui.has("PILOT UAV") && System.currentTimeMillis() < until) {
                            if (ui.has("STANDBY MODE")) ui.click("STANDBY MODE");
                            if (ui.has("Keep flying")) ui.click("Standby mode");
                            Ui.pause(5000);
                        }
                        if (!ui.has("PILOT UAV")) {
                            rig.check(false, "the app could not be brought back to PILOT UAV after that");
                            break;
                        }
                    } catch (Throwable stuck) {
                        rig.check(false, "the app could not be brought back after that: " + stuck.getMessage());
                        break;
                    }
                    cycle++;
                }
            }
            rig.film.remark(cycle + " sleep-and-wake cycles in " + (System.currentTimeMillis() - t0) / 60_000 + " min, " + failures
                    + " gone wrong; the slowest wake " + slowestWake + " s after PILOT UAV.");
            if (mismatches > 0) {
                rig.film.remark("F-046: in " + mismatches + " of " + cycle + " cycles the app sent another check interval than the one just chosen in CHECK EVERY.");
            }
            rig.check(cycle - failures >= 1, "at least one whole cycle ran");
            if (heaps.size() >= 24) {
                double perCycle = Night.slope(heaps.subList(4, heaps.size()));
                rig.film.remark(String.format("Memory in use: %+.0f KB a cycle over %d cycles.", perCycle * 1024, heaps.size() - 4));
                rig.check(perCycle * 1024 < 200, String.format("memory is level over the cycles: %+.0f KB a cycle", perCycle * 1024));
            }
            rig.check(rig.watch.threads() < rig.watch.threadsAtStart() + 120, "threads do not pile up: " + rig.watch.threadsAtStart() + " at the start, " + rig.watch.threads() + " now");
        });
    }
}
