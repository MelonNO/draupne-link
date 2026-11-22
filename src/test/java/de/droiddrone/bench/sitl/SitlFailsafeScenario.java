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
 * once: the motors drop to rest and the board disarms within a tenth of a second, FAILSAFE among its arming blocks. The
 * rescue never runs a motor up. Sticks back with the ARM switch off: the failsafe lets go a second or two later.
 * Every change, with its time after the last stick frame, is in the film's failsafe-timeline.tsv.
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
        byte[] status = link.ask(Msp.STATUS, null, 1000);
        int[] motors = Arrays.copyOf(Msp.motors(link.ask(Msp.MOTOR, null, 1000)), 4);
        byte[] debug = link.ask(Sitl.DEBUG, null, 1000);
        int phase = debug.length >= 8 ? (short) Msp.u16(debug, 6) : -1;
        return new Look((System.nanoTime() - sinceNs) / 1e9, Msp.armed(status), Msp.armingDisableFlags(status), Msp.modeFlag(status, index(boxIds, GPS_RESCUE_BOX)),
                Msp.modeFlag(status, index(boxIds, FAILSAFE_BOX)), motors, phase >= 0 && phase < Sitl.PHASES.size() ? Sitl.PHASES.get(phase) : "?" + phase);
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
            String version;
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                version = Sitl.version(fc);
            }
            film.add("Betaflight " + version + " as a program on 127.0.0.1:" + Sitl.port(), null, "sitl", Sitl.folder());
            check(Sitl.startedVersion().isEmpty() || Sitl.startedVersion().equals(version), "the Betaflight that answers is the one the run started (" + Sitl.startedVersion() + "): " + version);
            film.add("set up as the drone is: " + String.join("; ", Sitl.likeTheDrone(100, film::remark)), null, "sitl", "saved; Betaflight restarted");

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
                Look rest = look(link, boxIds, System.nanoTime());
                check(rest.flags() == 0, "stick frames over MSP, ARM off: nothing keeps it from arming (" + Msp.armingBlocks(rest.flags()) + ")");
                link.sticks(1000, 2000);
                long until = System.currentTimeMillis() + 3000;
                Look armed = rest;
                while (!armed.armed() && System.currentTimeMillis() < until) armed = look(link, boxIds, System.nanoTime());
                check(armed.armed(), "the ARM switch on with the throttle down arms it");
                link.sticks(1080, 2000);
                Sitl.pause(1500);
                Look flying = look(link, boxIds, System.nanoTime());
                int held = Arrays.stream(flying.motors()).max().orElse(0);
                check(flying.armed() && Arrays.stream(flying.motors()).allMatch(m -> m > REST), "armed, throttle 1080: every motor turns " + Arrays.toString(flying.motors()));
                film.add("armed, throttle 1080: motors " + Arrays.toString(flying.motors()), null, "sitl", "");

                // ---- the sticks stop, as a receiver that lost its radio
                long cut = link.stopSticks();
                Double rxLoss = null, stage2 = null, disarmed = null, failsafeBlock = null;
                boolean heldThroughStage1 = true, armedThroughStage1 = true;
                int highest = 0;
                String last = "";
                long end = System.currentTimeMillis() + 27_000;
                while (System.currentTimeMillis() < end) {
                    Look l = look(link, boxIds, cut);
                    if (!l.key().equals(last)) {
                        timeline.add(l.line());
                        last = l.key();
                    }
                    for (int m : l.motors()) highest = Math.max(highest, m);
                    if (rxLoss == null && (l.flags() >> Msp.ARMING_DISABLED_RXLOSS_BIT & 1) != 0) rxLoss = l.s();
                    boolean inStage2 = l.rescue() || l.phase().equals("GPS_RESCUE") || l.phase().equals("LANDED") || l.phase().equals("RX_LOSS_MONITORING") || !l.armed();
                    if (stage2 == null && inStage2) stage2 = l.s();
                    if (stage2 == null) {
                        armedThroughStage1 &= l.armed();
                        for (int m : l.motors()) heldThroughStage1 &= Math.abs(m - held) <= 3;
                    }
                    if (disarmed == null && !l.armed()) disarmed = l.s();
                    if (failsafeBlock == null && (l.flags() >> Msp.ARMING_DISABLED_FAILSAFE_BIT & 1) != 0) failsafeBlock = l.s();
                    if (disarmed != null && l.s() > disarmed + 1.5) break;
                    Sitl.pause(40);
                }
                Look after = look(link, boxIds, cut);
                timeline.add(after.line());
                film.add("the sticks stopped " + String.format(Locale.ROOT, "%.1f", after.s()) + " s ago: " + after.line(), null, "sitl", "");
                check(rxLoss != null && rxLoss <= 0.5, "RXLOSS shows within half a second of the last stick frame: " + seconds(rxLoss));
                check(armedThroughStage1 && heldThroughStage1, "stage 1 (failsafe_delay): armed, every motor held where the throttle was (" + held + ") until stage 2");
                check(stage2 != null && stage2 >= 19.5 && stage2 <= 21.5, "stage 2 begins about 20 s (failsafe_delay 200) after the last stick frame: " + seconds(stage2));
                check(disarmed != null && stage2 != null && disarmed - stage2 <= 1.0, "GPS rescue with no GPS fix disarms at once: " + seconds(disarmed) + " (stage 2 at " + seconds(stage2) + ")");
                check(highest <= held + 3, "no motor ever ran above the held throttle after the sticks stopped (the rescue never ran the motors up): highest " + highest);
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
                                + "stage 2 (GPS rescue, no GPS fix) at %s; disarmed at %s, motors at rest; FAILSAFE block at %s; the rescue never ran a motor up (highest %d); "
                                + "sticks back with ARM off: free again after %s.", version, seconds(rxLoss), seconds(stage2), held, seconds(stage2), seconds(disarmed),
                        seconds(failsafeBlock), highest, seconds(free.s())));
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
