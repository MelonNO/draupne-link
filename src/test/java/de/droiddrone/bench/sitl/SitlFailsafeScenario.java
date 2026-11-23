package de.droiddrone.bench.sitl;

import de.droiddrone.bench.Film;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.hw.Msp;
import de.droiddrone.pc.setup.BetaflightUsb;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * What the drone's own failsafe does when its sticks stop while it is armed - seen on Betaflight itself, as a program
 * (SITL), set up as the drone is: GPS rescue after failsafe_delay 200, rxfail holding the throttle and the ARM switch,
 * a GPS with no fix, arming without a fix allowed. This is what the keeper's word board.failsafeSeenOnSitl stands for
 * (docs/TESTING.md, "The keeper"): until it is seen, the keeper ends a real board's arm window itself when the sticks
 * stop.
 * <p>
 * Seen on 4.4.1 (2026-11-22): the stick frames stop; RXLOSS a tenth of a second later; for 20 s nothing else - armed,
 * every motor where the held throttle put it (stage 1); then stage 2, GPS rescue, which without a GPS fix gives up at
 * once and the board disarms within a tenth of a second, FAILSAFE among its arming blocks. In that tenth the rescue
 * may have run every motor up to its hover throttle (gps_rescue_throttle_hover 1275): seen in two runs of five, 1290
 * where 1100 was held, for no longer than one look (20 to 90 ms) - so around stage 2 the motors are read as fast as they answer, and the run-up, if
 * any, is said and timed: it has to be in stage 2 and over with the disarm within half a second. Sticks back with the
 * ARM switch off: the failsafe lets go two seconds later. Every change, with its time after the last stick frame, is
 * in the film's failsafe-timeline.tsv.
 */
@Tag("sitl")
class SitlFailsafeScenario {
    private static final int GPS_RESCUE_BOX = 46, FAILSAFE_BOX = 27, REST = 1000;
    private final List<String> wrong = new ArrayList<>();
    private Film film;

    private void check(boolean ok, String what) {
        film.remark((ok ? "ok: " : "WRONG: ") + what);
        if (!ok) wrong.add(what);
    }

    /** One look at the board. */
    private record Look(double s, boolean armed, int flags, boolean rescue, boolean failsafeMode, int[] motors, String phase) {
        String key() {
            return armed + " " + flags + " " + rescue + " " + failsafeMode + " " + Arrays.toString(motors) + " " + phase;
        }

        String line() {
            return String.format(Locale.ROOT, "%.2f\t%s\t%s\t%s\t%s", s, armed ? "ARMED" : "disarmed", Msp.armingBlocks(flags),
                    (rescue ? "GPS_RESCUE " : "") + (failsafeMode ? "FAILSAFE" : ""), Arrays.toString(motors) + "\t" + phase);
        }
    }

    private static Look look(SitlLink link, int[] boxIds, long sinceNs) throws Exception {
        return look(link, boxIds, sinceNs, true);
    }

    /** withPhase false: two questions instead of three (the phase reads "-"), for looking as often as can be. */
    private static Look look(SitlLink link, int[] boxIds, long sinceNs, boolean withPhase) throws Exception {
        byte[] status = link.ask(Msp.STATUS, null, 1000);
        int[] motors = Arrays.copyOf(Msp.motors(link.ask(Msp.MOTOR, null, 1000)), 4);
        if (!withPhase) {
            return new Look((System.nanoTime() - sinceNs) / 1e9, Msp.armed(status), Msp.armingDisableFlags(status), Msp.modeFlag(status, index(boxIds, GPS_RESCUE_BOX)),
                    Msp.modeFlag(status, index(boxIds, FAILSAFE_BOX)), motors, "-");
        }
        byte[] debug = link.ask(Sitl.DEBUG, null, 1000);
        int phase = debug.length >= 8 ? (short) Msp.u16(debug, 6) : -1;
        return new Look((System.nanoTime() - sinceNs) / 1e9, Msp.armed(status), Msp.armingDisableFlags(status), Msp.modeFlag(status, index(boxIds, GPS_RESCUE_BOX)),
                Msp.modeFlag(status, index(boxIds, FAILSAFE_BOX)), motors, phase >= 0 && phase < Sitl.PHASES.size() ? Sitl.PHASES.get(phase) : "?" + phase);
    }

    /** Looks until the board has said no arming block for a second running (8 s at most); the last look. */
    private static Look calm(SitlLink link, int[] boxIds) throws Exception {
        long end = System.currentTimeMillis() + 8000, since = System.currentTimeMillis();
        Look l = look(link, boxIds, System.nanoTime());
        while (System.currentTimeMillis() < end) {
            if (l.flags() != 0) since = System.currentTimeMillis();
            else if (System.currentTimeMillis() - since >= 1000) return l;
            Sitl.pause(100);
            l = look(link, boxIds, System.nanoTime());
        }
        return l;
    }

    private static int index(int[] ids, int id) {
        for (int i = 0; i < ids.length; i++) if (ids[i] == id) return i;
        return -1;
    }

    @Test
    void theDronesFailsafeOnBetaflightItselfHoldsForTwentySecondsThenDisarmsWithoutAFix() throws Exception {
        String missing = Sitl.missing();
        Assumptions.assumeTrue(missing == null, missing);
        film = new Film(Sandbox.runRoot().resolve("film").resolve("sitl-failsafe"), "sitl-failsafe");
        List<String> timeline = new ArrayList<>();
        timeline.add("s after the last stick frame\tarmed\tarming blocks\tmodes\tmotors\tfailsafe phase");
        try {
            film.add("a fresh board: " + Sitl.fresh(film::remark), null, "sitl", "");
            String version;
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                version = Sitl.version(fc);
            }
            film.add("Betaflight " + version + " as a program on 127.0.0.1:" + Sitl.port(), null, "sitl", Sitl.folder());
            check(Sitl.startedVersion().isEmpty() || Sitl.startedVersion().equals(version), "the Betaflight that answers is the one the run started (" + Sitl.startedVersion() + "): " + version);
            film.add("set up as the drone is: " + String.join("; ", Sitl.likeTheDrone(100, film::remark)), null, "sitl", "saved; Betaflight restarted");

            String hover;
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                fc.enterCli();
                hover = fc.command("get gps_rescue_throttle_hover").replaceAll("(?s).*?=\\s*(\\d+).*", "$1");
            } // left with "exit": Betaflight restarts
            int[] boxIds;
            String blocks;
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                blocks = Sitl.waitForBlocksOnly(fc, 1 << Msp.ARMING_DISABLED_RXLOSS_BIT, 20_000); // its boot grace goes after a few seconds
                byte[] ids = fc.msp(Msp.BOXIDS, null);
                boxIds = new int[ids.length];
                for (int i = 0; i < ids.length; i++) boxIds[i] = ids[i] & 0xff;
            }
            check(blocks.equals("RXLOSS"), "before any stick frame its one arming block is RXLOSS: " + blocks);
            check(index(boxIds, GPS_RESCUE_BOX) >= 0, "it offers the mode GPS RESCUE (built with the GPS, the GPS on UART3)");

            try (SitlLink link = new SitlLink()) {
                link.sticks(1000, 1000).startSticks();
                Sitl.pause(1500);
                Look rest = calm(link, boxIds);
                check(rest.flags() == 0, "stick frames over MSP, ARM off: nothing keeps it from arming (" + Msp.armingBlocks(rest.flags()) + ")");
                // SITL drops its own receiver for a tenth of a second now and then (docs/TESTING.md, 2026-11-23); one that comes
                // as the switch goes up leaves BADRX and ARMSWITCH: the switch down and up again, as a pilot would, 5 times at most
                Look armed = rest;
                for (int flip = 1; flip <= 5 && !armed.armed(); flip++) {
                    if (flip > 1) {
                        film.remark("The ARM switch up did not arm it (" + Msp.armingBlocks(armed.flags()) + "): down and up again (" + flip + " of 5).");
                        link.sticks(1000, 1000);
                        calm(link, boxIds);
                    }
                    link.sticks(1000, 2000);
                    long until = System.currentTimeMillis() + 3000;
                    do {
                        armed = look(link, boxIds, System.nanoTime());
                    } while (!armed.armed() && System.currentTimeMillis() < until);
                }
                check(armed.armed(), "the ARM switch on with the throttle down arms it");
                link.sticks(1080, 2000);
                Sitl.pause(1500);
                Look flying = look(link, boxIds, System.nanoTime());
                int held = Arrays.stream(flying.motors()).max().orElse(0);
                check(flying.armed() && Arrays.stream(flying.motors()).allMatch(m -> m > REST), "armed, throttle 1080: every motor turns " + Arrays.toString(flying.motors()));
                film.add("armed, throttle 1080: motors " + Arrays.toString(flying.motors()), null, "sitl", "");

                // ---- the sticks stop, as a receiver that lost its radio
                long cut = link.stopSticks();
                Double rxLoss = null, stage2 = null, disarmed = null, failsafeBlock = null, runUpFrom = null, runUpTo = null;
                boolean heldThroughStage1 = true, armedThroughStage1 = true;
                int highest = 0;
                String last = "";
                long end = System.currentTimeMillis() + 27_000;
                while (System.currentTimeMillis() < end) {
                    // around stage 2's start nothing but the status and the motors, as fast as they answer (about 20 ms a look)
                    double at = (System.nanoTime() - cut) / 1e9;
                    boolean close = at > 19.0 && at < 22.0;
                    Look l = look(link, boxIds, cut, !close);
                    if (!l.key().equals(last)) {
                        timeline.add(l.line());
                        last = l.key();
                    }
                    for (int m : l.motors()) highest = Math.max(highest, m);
                    if (l.armed() && Arrays.stream(l.motors()).anyMatch(m -> m > held + 3)) {
                        if (runUpFrom == null) runUpFrom = l.s();
                        runUpTo = l.s();
                    }
                    if (rxLoss == null && (l.flags() >> Msp.ARMING_DISABLED_RXLOSS_BIT & 1) != 0) rxLoss = l.s();
                    // stage 2 by Betaflight's word, or by the motors leaving the held throttle once failsafe_delay is nearly up
                    // (4.5.5 drops them to idle one look before its GPS RESCUE mode shows)
                    boolean moved = Arrays.stream(l.motors()).anyMatch(m -> Math.abs(m - held) > 3);
                    boolean inStage2 = l.rescue() || l.phase().equals("GPS_RESCUE") || l.phase().equals("LANDED") || l.phase().equals("RX_LOSS_MONITORING") || !l.armed()
                            || moved && l.s() >= 19.5;
                    if (stage2 == null && inStage2) stage2 = l.s();
                    if (stage2 == null) {
                        armedThroughStage1 &= l.armed();
                        for (int m : l.motors()) heldThroughStage1 &= Math.abs(m - held) <= 3;
                    }
                    if (disarmed == null && !l.armed()) disarmed = l.s();
                    if (failsafeBlock == null && (l.flags() >> Msp.ARMING_DISABLED_FAILSAFE_BIT & 1) != 0) failsafeBlock = l.s();
                    if (disarmed != null && l.s() > disarmed + 1.5) break;
                    if (!close) Sitl.pause(40);
                }
                Look after = look(link, boxIds, cut);
                timeline.add(after.line());
                film.add("the sticks stopped " + String.format(Locale.ROOT, "%.1f", after.s()) + " s ago: " + after.line(), null, "sitl", "");
                check(rxLoss != null && rxLoss <= 0.5, "RXLOSS shows within half a second of the last stick frame: " + seconds(rxLoss));
                check(armedThroughStage1 && heldThroughStage1, "stage 1 (failsafe_delay): armed, every motor held where the throttle was (" + held + ") until stage 2");
                check(stage2 != null && stage2 >= 19.5 && stage2 <= 21.5, "stage 2 begins about 20 s (failsafe_delay 200) after the last stick frame: " + seconds(stage2));
                check(disarmed != null && stage2 != null && disarmed - stage2 <= 1.0, "GPS rescue with no GPS fix disarms at once: " + seconds(disarmed) + " (stage 2 at " + seconds(stage2) + ")");
                String runUp = runUpFrom == null ? "no motor ever above the held throttle (highest " + highest + ")"
                        : String.format(Locale.ROOT, "THE RESCUE RAN THE MOTORS UP: to %d (gps_rescue_throttle_hover %s), seen from %.2f to %.2f s - each look is about 20 ms",
                        highest, hover, runUpFrom, runUpTo);
                film.remark(runUp);
                check(runUpFrom == null || stage2 != null && disarmed != null && runUpFrom >= stage2 - 0.01 && runUpTo < disarmed && disarmed - runUpFrom <= 0.5,
                        "a run-up of the motors, if any, only in stage 2 and over with the disarm within half a second: " + runUp);
                check(!after.armed() && Arrays.stream(after.motors()).allMatch(m -> m <= REST), "afterwards: disarmed, every motor at rest " + Arrays.toString(after.motors()));
                check(failsafeBlock != null, "FAILSAFE is among its arming blocks after it: " + Msp.armingBlocks(after.flags()));

                // ---- the sticks back, ARM off: the failsafe lets go
                link.sticks(1000, 1000).startSticks();
                long back = System.nanoTime();
                Look free = look(link, boxIds, back);
                while (free.s() < 8 && (free.flags() != 0 || !free.phase().equals("IDLE"))) {
                    Sitl.pause(100);
                    free = look(link, boxIds, back);
                }
                check(free.flags() == 0 && !free.armed() && free.phase().equals("IDLE"), "sticks back, ARM off: the failsafe lets go after " + seconds(free.s())
                        + " (" + Msp.armingBlocks(free.flags()) + ", phase " + free.phase() + ")");
                film.remark(String.format(Locale.ROOT, "SEEN on Betaflight %s as a program: the sticks stopped; RXLOSS after %s; stage 1 for %s, armed, motors held at %d; "
                                + "stage 2 (GPS rescue, no GPS fix) at %s; %s; disarmed at %s, motors at rest; FAILSAFE block at %s; sticks back with ARM off: free again after %s.",
                        version, seconds(rxLoss), seconds(stage2), held, seconds(stage2), runUp, seconds(disarmed), seconds(failsafeBlock), seconds(free.s())));
                link.stopSticks();
            }
        } catch (Exception e) {
            wrong.add("the scenario broke off: " + e);
            film.remark("BROKE OFF: " + e);
        } finally {
            Files.write(film.dir().resolve("failsafe-timeline.tsv"), timeline, StandardCharsets.UTF_8);
            BetaflightUsb.use(null);
            film.close(wrong.isEmpty(), wrong);
        }
        if (!wrong.isEmpty()) fail("sitl-failsafe: " + wrong.size() + " problem(s):\n - " + String.join("\n - ", wrong));
    }

    private static String seconds(Double s) {
        return s == null ? "never" : String.format(Locale.ROOT, "%.2f s", s);
    }
}
