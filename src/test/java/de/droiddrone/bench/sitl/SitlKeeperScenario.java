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
            check(Boolean.TRUE.equals(s.locked()) && Boolean.FALSE.equals(s.armed()), "locked from the moment it is taken, disarmed: " + s);
            check(s.number("lockBit") >= 0, "where the lock shows in Betaflight's status was measured: bit " + s.get("lockBit"));
            check(s.get("failsafe").equals("GPS-RESCUE") && s.number("failsafeDelay") == 200, "the board's failsafe as read: " + s.get("failsafe") + " after " + s.get("failsafeDelay"));
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
            check(within(8000, () -> !state(r).cannotArm().contains("RXLOSS") && state(r).number("throttle") == 1000), "the phone's stick frames reach the board: "
                    + state(rig).get("blocks"));
            Thread.sleep(500);
            StandInPhone p = phone;
            check(!arm(rig, p), "with no window the sticks that ask for ARM do not arm it");
            phone.sticks(1000, 1000);

            // ---- a window, sticks under the cap
            String open = rig.ask("OPEN 15");
            film.add("OPEN 15: " + open, null, "sitl", "");
            check(open.startsWith("OK window=15000"), "a window opens: " + open);
            check(arm(rig, phone), "in the window the ARM switch arms the board");
            phone.sticks(1080, 2000);
            Thread.sleep(2500);
            s = state(rig);
            check(Boolean.TRUE.equals(s.armed()) && s.tripped() == null && s.number("throttle") == 1080, "armed at throttle 1080, under the cap of 1100, nothing tripped: " + s);
            String closed = rig.ask("CLOSE");
            film.add("CLOSE: " + closed, null, "sitl", "");
            check(closed.startsWith("OK safe=1 locked=1 armed=0 atRest=1"), "CLOSE leaves it locked, disarmed, every motor at rest: " + closed);
            phone.sticks(1000, 1000);
            Thread.sleep(500);

            // ---- a stick frame over the cap
            check(rig.ask("OPEN 15").startsWith("OK window=15000") && arm(rig, phone), "a second window, armed again");
            phone.sticks(1150, 2000);
            check(within(3000, () -> Boolean.FALSE.equals(state(r).armed()) && state(r).tripped() != null), "a stick frame with throttle 1150 is not passed on: the window ends, the board disarmed");
            check(saidOf("THROTTLE CAP"), "the keeper says THROTTLE CAP");
            phone.sticks(1000, 1000);
            Thread.sleep(300);
            check(rig.ask("CLEAR").startsWith("OK"), "CLEAR, with the throttle back down");

            // ---- the sticks stop in a window: the keeper ends it before the board's own failsafe
            check(rig.ask("OPEN 15").startsWith("OK window=15000") && arm(rig, phone), "a third window, armed again");
            phone.sticks(1050, 2000);
            Thread.sleep(800);
            long stop = System.currentTimeMillis();
            phone.stopSticks();
            boolean ended = within(4000, () -> Boolean.FALSE.equals(state(r).armed()) && state(r).windowMs() == 0);
            long took = System.currentTimeMillis() - stop;
            check(ended, "sticks that stop in a window: the keeper ends it, the board disarmed, after " + took + " ms (its own failsafe would wait 20 s)");
            check(saidOf("RECEIVER LOST") || saidOf("STICKS STOPPED"), "the keeper says why: " + (saidOf("RECEIVER LOST") ? "RECEIVER LOST (the board's RXLOSS)" : "STICKS STOPPED"));

            // ---- the end
            String end = rig.ask("END");
            film.add("END: " + end, null, "sitl", "");
            check(end.startsWith("OK safe=1 locked=1 armed=0 atRest=1"), "END leaves the board locked, disarmed, every motor at rest: " + end);
            FcKeeper.Safe last = keeper.awaitEnd();
            check(last.safe(), "the keeper's own word at its end: " + last.text());
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
