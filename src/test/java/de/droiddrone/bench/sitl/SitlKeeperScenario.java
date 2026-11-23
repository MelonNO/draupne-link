package de.droiddrone.bench.sitl;

import de.droiddrone.bench.Film;
import de.droiddrone.bench.Sandbox;
import de.droiddrone.bench.hw.FcKeeper;
import de.droiddrone.bench.hw.KeeperClient;
import de.droiddrone.bench.hw.StandInPhone;
import de.droiddrone.pc.setup.BetaflightUsb;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The flight controller keeper ({@code bench/hw/FcKeeper}) against Betaflight itself, as a program (SITL), set up as
 * the drone is and with the rig's output limit (motor_output_limit 15): the keeper as a real board's keeper is in
 * every rule but the battery (SITL has none) - its settings read in the board's CLI first, strict, its arming lock,
 * the window's end when the sticks stop. Then the rig's part: the lock holds; a window opens; made-up sticks under the
 * throttle cap arm the board and run its motors; CLOSE leaves it locked, disarmed, the motors at rest; a stick frame
 * over the cap is not passed on and ends a window; sticks that stop end one before the board's own failsafe; END
 * leaves the board safe.
 * <p>
 * Only SITL 4.4 and older can be locked: Betaflight 4.5 on ignores MSP's arming block when built as SITL (msp.c,
 * {@code #ifndef SIMULATOR_BUILD}). There the scenario checks only that the keeper sees its lock does not show and
 * opens no window (seen on 4.5.5, 2026-11-22: sticks that asked for ARM armed it outside any window, and the keeper
 * tripped and said NOT SAFE - it could not disarm it).
 */
@Tag("sitl")
class SitlKeeperScenario {
    private final List<String> wrong = new ArrayList<>();
    private final List<String> said = new CopyOnWriteArrayList<>();
    private Film film;
    private volatile boolean beating;

    private void check(boolean ok, String what) {
        film.remark((ok ? "ok: " : "WRONG: ") + what);
        if (!ok) wrong.add(what);
    }

    private static boolean within(long ms, BooleanSupplier ok) {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) return false;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private boolean saidOf(String words) {
        for (String line : said) if (line.contains(words)) return true;
        return false;
    }

    private static KeeperClient.State state(KeeperClient rig) {
        try {
            return rig.state();
        } catch (IOException e) {
            throw new IllegalStateException("the keeper does not answer: " + e.getMessage(), e);
        }
    }

    /** The pilot's way: the switch off first, then on with the throttle down. */
    private static boolean arm(KeeperClient rig, StandInPhone phone) throws InterruptedException {
        phone.sticks(1000, 1000);
        Thread.sleep(150);
        phone.sticks(1000, 2000);
        return within(4000, () -> Boolean.TRUE.equals(state(rig).armed()));
    }

    private int count(String words) {
        int n = 0;
        for (String line : said) if (line.contains(words)) n++;
        return n;
    }

    /** What a window is for: true when it went as asked. */
    private interface InWindow {
        boolean run() throws Exception;
    }

    /**
     * OPEN 15, the ARM switch up, and what the window is for. SITL drops its own receiver for a tenth of a second now
     * and then (RXLOSS with the stick frames sent on time: its gyro loop leaves its other tasks little time,
     * docs/TESTING.md, 2026-11-23) - and the keeper ends a window on the board's RXLOSS (RECEIVER LOST), as it must.
     * A window that went wrong that way, and only that way, is asked for again once the sticks reach the board, three
     * times at most; the film says so. @return whether it went as asked in the end
     */
    private boolean window(KeeperClient rig, StandInPhone phone, String what, InWindow body) throws Exception {
        for (int attempt = 1; ; attempt++) {
            phone.sticks(1000, 1000);
            calm(rig);
            int lostBefore = count("RECEIVER LOST");
            rig.ask("MARK"); // the keeper's figures of the stick frames it passes to the board, from here
            String open = rig.ask("OPEN 15");
            boolean ok = open.startsWith("OK window=15000") && arm(rig, phone) && body.run();
            KeeperClient.State s = state(rig);
            if (count("RECEIVER LOST") > lostBefore) {
                // whether the gap is ours (no frame passed to the board for 100 ms and more) or SITL's own
                film.remark(what + ": the stick frames the keeper passed to the board since the window was asked for (maxGapMs: the largest gap "
                        + "between two; Betaflight says RXLOSS after 100 ms): " + rig.ask("FIGURES"));
            }
            // SITL's receiver gap, and nothing else: the keeper ended the window for the board's RXLOSS, would not open one
            // while the board said RXLOSS or was slow to answer its status (the same gap, seen from the keeper's side), or
            // the board would not arm for the BADRX such a gap leaves at the ARM switch
            boolean slow = open.contains("does not answer");
            boolean gap = count("RECEIVER LOST") > lostBefore || open.contains("RXLOSS") || slow || !ok && s.get("blocks").contains("BADRX");
            if (ok || attempt == 5 || !gap) {
                if (!ok) film.remark(what + ": OPEN 15 said " + open + "; " + s);
                return ok;
            }
            film.remark(what + ": SITL's own receiver gap (" + (count("RECEIVER LOST") > lostBefore ? "the keeper ended the window: RECEIVER LOST" : open.contains("RXLOSS")
                    ? "no window while the board says RXLOSS" : slow ? "no window: the board was slow to answer" : "BADRX at the ARM switch") + ") - asked again ("
                    + attempt + " of 5)");
        }
    }

    /** Until the board has said neither RXLOSS nor BADRX for two seconds running (SITL's receiver gaps come and go), 20 s at most. */
    private static void calm(KeeperClient rig) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000, since = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            KeeperClient.State s = state(rig);
            String blocks = s.get("blocks");
            if (blocks.contains("RXLOSS") || blocks.contains("BADRX") || Boolean.TRUE.equals(s.armed())) since = System.currentTimeMillis();
            else if (System.currentTimeMillis() - since >= 2000) return;
            Thread.sleep(100);
        }
    }

    @Test
    void theKeeperOnBetaflightItselfLocksOpensAWindowUnderTheCapAndLeavesTheBoardSafe() throws Exception {
        String missing = Sitl.missing();
        Assumptions.assumeTrue(missing == null, missing);
        film = new Film(Sandbox.runRoot().resolve("film").resolve("sitl-keeper"), "sitl-keeper");
        FcKeeper keeper = null;
        KeeperClient rig = null;
        StandInPhone phone = null;
        Thread beater = null;
        try {
            film.add("a fresh board: " + Sitl.fresh(film::remark), null, "sitl", "");
            String version;
            try (BetaflightUsb fc = Sitl.open(film::remark)) {
                version = Sitl.version(fc);
            }
            film.add("Betaflight " + version + " as a program on 127.0.0.1:" + Sitl.port(), null, "sitl", Sitl.folder());
            film.add("set up as the drone is, with the rig's output limit: " + String.join("; ", Sitl.likeTheDrone(15, film::remark)), null, "sitl", "saved");
            BetaflightUsb.use(null); // the keeper has its own way to the board: FcKeeper.tcp

            // ---- the keeper, as a real board's in every rule but the battery
            FcKeeper.Config c = new FcKeeper.Config();
            c.noBattery = true;
            c.readSettings = true;
            c.failsafeEndsWindow = true;
            c.outputLimitMax = 15;
            c.folder = film.dir().resolve("keeper");
            c.console = said::add;
            keeper = new FcKeeper(c, FcKeeper.tcp(Sitl.HOST, Sitl.port()), null).start();
            rig = new KeeperClient(keeper.controlPort(), 15_000);
            KeeperClient r = rig;
            check(within(45_000, () -> state(r).boardUp() && saidOf("The board is taken")), "the keeper reads the board's settings in its CLI, and takes it (Betaflight restarted)");
            for (String line : said) if (line.contains("The board's own settings") || line.contains("The board is taken")) film.remark("keeper: " + line);
            KeeperClient.State s = state(rig);
            if (s.number("lockBit") < 0 && !version.startsWith("4.3.") && !version.startsWith("4.4.")) {
                // Betaflight 4.5 on, built as SITL, takes no MSP arming block (msp.c: "#ifndef SIMULATOR_BUILD // In simulator
                // mode we can safely arm with MSP link"): the keeper's lock cannot hold there. What is checked is that the
                // keeper sees so and opens no window; the windows themselves want SITL 4.4 or older. (No sticks are sent: with
                // nothing to block it, they would arm it.)
                film.remark("Betaflight " + version + " as a program takes no MSP arming block (its msp.c, #ifndef SIMULATOR_BUILD): the keeper's lock cannot "
                        + "show; checked that the keeper opens no window and says so - the windows want SITL 4.4 or older");
                check(s.locked() == null && Boolean.FALSE.equals(s.armed()), "the keeper does not take the lock for set when it does not show: locked=" + s.get("locked"));
                rig.ask("BEAT");
                String no = rig.ask("OPEN 15");
                check(no.startsWith("NO the arming lock does not show"), "no window on a board whose lock does not show: " + no);
                String end = rig.ask("END");
                film.add("END: " + end, null, "sitl", "");
                check(end.contains("armed=0") && end.contains("atRest=1") && end.contains("does not show"), "END: disarmed, every motor at rest, the lock said not to show: " + end);
                keeper.awaitEnd();
            } else {
                check(Boolean.TRUE.equals(s.locked()) && Boolean.FALSE.equals(s.armed()), "locked from the moment it is taken, disarmed: " + s);
                check(s.number("lockBit") >= 0, "where the lock shows in Betaflight's status was measured: bit " + s.get("lockBit"));
                String procedure = version.startsWith("4.3.") ? "DROP" : "GPS-RESCUE"; // 4.3's SITL has no GPS rescue: Sitl.likeTheDrone sets DROP
                check(s.get("failsafe").equals(procedure) && s.number("failsafeDelay") == 200, "the board's failsafe as read: " + s.get("failsafe") + " after "
                        + s.get("failsafeDelay") + " (as set up: " + procedure + " after 200)");
                check(s.number("outputLimit") == 15, "motor_output_limit read in every PID profile: at most " + s.get("outputLimit"));

                // ---- the rig's heartbeat and the phone's sticks
                KeeperClient own = new KeeperClient(keeper.controlPort(), 5000);
                beating = true;
                beater = new Thread(() -> {
                    try (own) {
                        while (beating) {
                            own.beat();
                            Thread.sleep(100);
                        }
                    } catch (IOException | InterruptedException e) {
                        // the keeper ended
                    }
                }, "sitlKeeperBeat");
                beater.setDaemon(true);
                beater.start();
                rig.ask("BEAT");
                phone = new StandInPhone(keeper.phonePort()).every(40).sticks(1000, 1000).startSticks(); // 25 a second: Betaflight takes 100 MSP requests a second in all
                check(within(8000, () -> !state(r).get("blocks").contains("RXLOSS") && state(r).number("throttle") == 1000), "the phone's stick frames reach the board: "
                        + state(rig).get("blocks"));
                Thread.sleep(500);
                StandInPhone p = phone;
                check(!arm(rig, p), "with no window the sticks that ask for ARM do not arm it");
                phone.sticks(1000, 1000);

                // ---- a window, sticks under the cap
                KeeperClient.State[] held = new KeeperClient.State[1];
                StandInPhone ph = phone;
                boolean first = window(rig, phone, "the first window", () -> {
                    ph.sticks(1080, 2000);
                    Thread.sleep(2500);
                    held[0] = state(r);
                    return Boolean.TRUE.equals(held[0].armed()) && held[0].tripped() == null && held[0].number("throttle") == 1080;
                });
                film.add("the first window: " + (first ? "armed at throttle 1080" : "not as asked"), null, "sitl", "");
                check(held[0] != null, "a window opens and the ARM switch arms the board in it");
                s = held[0] == null ? state(rig) : held[0];
                check(first, "armed at throttle 1080, under the cap of 1100, nothing tripped: " + s);
                String closed = rig.ask("CLOSE");
                film.add("CLOSE: " + closed, null, "sitl", "");
                check(closed.startsWith("OK safe=1 locked=1 armed=0 atRest=1"), "CLOSE leaves it locked, disarmed, every motor at rest: " + closed);
                phone.sticks(1000, 1000);
                Thread.sleep(500);

                // ---- a stick frame over the cap
                check(window(rig, phone, "the second window", () -> true), "a second window, armed again");
                phone.sticks(1150, 2000);
                check(within(3000, () -> Boolean.FALSE.equals(state(r).armed()) && state(r).tripped() != null), "a stick frame with throttle 1150 is not passed on: the window ends, the board disarmed");
                check(saidOf("THROTTLE CAP"), "the keeper says THROTTLE CAP");
                phone.sticks(1000, 1000);
                Thread.sleep(300);
                check(rig.ask("CLEAR").startsWith("OK"), "CLEAR, with the throttle back down");
                // the frames over the cap were not passed on, so the board has said RXLOSS: no window opens until sticks reach it again
                check(within(5000, () -> !state(r).get("blocks").contains("RXLOSS")), "the sticks reach the board again after the frames over the cap: "
                        + state(rig).get("blocks"));

                // ---- the sticks stop in a window: the keeper ends it before the board's own failsafe
                // (still armed after 0.8 s at 1050 - no receiver gap of SITL's first - so the end that follows is the stop's)
                check(window(rig, phone, "the third window", () -> {
                    ph.sticks(1050, 2000);
                    Thread.sleep(800);
                    return Boolean.TRUE.equals(state(r).armed()) && state(r).windowMs() > 0;
                }), "a third window, armed again");
                int lost = count("RECEIVER LOST"), stopped = count("STICKS STOPPED");
                long stop = System.currentTimeMillis();
                phone.stopSticks();
                boolean ended = within(4000, () -> Boolean.FALSE.equals(state(r).armed()) && state(r).windowMs() == 0);
                long took = System.currentTimeMillis() - stop;
                check(ended, "sticks that stop in a window: the keeper ends it, the board disarmed, after " + took + " ms (its own failsafe would wait 20 s)");
                boolean why = within(2000, () -> count("RECEIVER LOST") > lost || count("STICKS STOPPED") > stopped);
                check(why, "the keeper says why: " + (count("RECEIVER LOST") > lost ? "RECEIVER LOST (the board's RXLOSS)" : count("STICKS STOPPED") > stopped ? "STICKS STOPPED" : "nothing"));

                // ---- the end
                String end = rig.ask("END");
                film.add("END: " + end, null, "sitl", "");
                check(end.startsWith("OK safe=1 locked=1 armed=0 atRest=1"), "END leaves the board locked, disarmed, every motor at rest: " + end);
                FcKeeper.Safe last = keeper.awaitEnd();
                check(last.safe(), "the keeper's own word at its end: " + last.text());
            }
        } catch (Exception e) {
            wrong.add("the scenario broke off: " + e);
            film.remark("BROKE OFF: " + e);
        } finally {
            beating = false;
            if (phone != null) phone.close();
            if (rig != null) rig.close();
            if (keeper != null) keeper.close();
            if (beater != null) beater.join(2000);
            Files.write(film.dir().resolve("keeper-said.txt"), said, StandardCharsets.UTF_8);
            BetaflightUsb.use(null);
            film.close(wrong.isEmpty(), wrong);
        }
        if (!wrong.isEmpty()) fail("sitl-keeper: " + wrong.size() + " problem(s):\n - " + String.join("\n - ", wrong));
    }
}
