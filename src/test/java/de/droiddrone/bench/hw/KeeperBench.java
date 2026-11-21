package de.droiddrone.bench.hw;

import de.droiddrone.pc.setup.BetaflightPlan;
import de.droiddrone.testing.Waits;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * For the keeper's tests: a keeper on the made-up board, at a test's pace (its rules' times shortened, never its
 * rules), with the rig's end of its control channel, a heartbeat that can be stopped, and a stand-in for the phone.
 */
final class KeeperBench implements AutoCloseable {
    final FakeFlightBoard board;
    final FcKeeper.Config config = quick();
    /** Every line the keeper said. */
    final List<String> said = new CopyOnWriteArrayList<>();
    FcKeeper keeper;
    KeeperClient rig;
    /** The phone on adb, for a test of the reverse port; null: none, a plain TCP client stands in. */
    de.droiddrone.bench.PhoneAdb adb;
    private volatile boolean beating;
    private Thread beater;

    KeeperBench(FakeFlightBoard board) {
        this.board = board;
        config.console = said::add;
    }

    /** The keeper's rules with a test's times: a heartbeat of 0.4 s instead of 2, a low battery for 0.3 s instead of 2, a tick of 20 ms. */
    static FcKeeper.Config quick() {
        FcKeeper.Config c = new FcKeeper.Config().battery(4, 3.5);
        c.heartbeatMs = 400;
        c.voltageLowMs = 300;
        c.tickMs = 20;
        c.statusEveryMs = 40;
        c.voltsEveryMs = 80;
        c.reopenMs = 30;
        c.backMs = 4000;
        return c;
    }

    /** A board that arms on AUX1 from MSP's sticks, as Drone setup leaves one, with a 4-cell pack at 4.1 V a cell in. */
    static FakeFlightBoard armable() {
        FakeFlightBoard board = new FakeFlightBoard().setUp("aux 0 0 0 1700 2100 0 0").features(BetaflightPlan.mspReceiverMask(FakeFlightBoard.DEFAULT_FEATURES));
        board.battery(16.4, 4);
        return board;
    }

    KeeperBench folder(Path dir) {
        config.folder = dir;
        return this;
    }

    KeeperBench start() throws IOException, InterruptedException {
        return start(FcKeeper.madeUp(board));
    }

    KeeperBench start(FcKeeper.BoardSide side) throws IOException, InterruptedException {
        keeper = new FcKeeper(config, side, adb).start();
        rig = new KeeperClient(keeper.controlPort(), 15_000);
        Waits.waitFor(() -> state().boardUp(), 8000, "the keeper to take the board", said);
        return this;
    }

    KeeperClient.State state() {
        try {
            return rig.state();
        } catch (IOException e) {
            throw new AssertionError("the keeper does not answer: " + e, e);
        }
    }

    String ask(String command) {
        try {
            return rig.ask(command);
        } catch (IOException e) {
            throw new AssertionError("the keeper does not answer " + command + ": " + e, e);
        }
    }

    /** The rig's heartbeat, 50 times a second on a connection of its own, until {@link #stopBeating}. */
    KeeperBench beat() throws IOException {
        KeeperClient own = new KeeperClient(keeper.controlPort(), 5000);
        beating = true;
        beater = new Thread(() -> {
            try (own) {
                while (beating) {
                    own.beat();
                    Thread.sleep(20);
                }
            } catch (IOException | InterruptedException e) {
                // the keeper ended
            }
        }, "keeperBenchBeat");
        beater.setDaemon(true);
        beater.start();
        ask("BEAT");
        return this;
    }

    /** The rig dies: no beat from here on. */
    void stopBeating() throws InterruptedException {
        beating = false;
        if (beater != null) beater.join(2000);
    }

    /** A stand-in phone on the keeper's phone port. */
    StandInPhone phone() throws IOException {
        return new StandInPhone(keeper.phonePort());
    }

    /** The stand-in phone arms the board the way the app's pilot does: the switch off first, then on with the throttle down. */
    void arm(StandInPhone phone) throws InterruptedException {
        phone.sticks(1000, 1000);
        Thread.sleep(60);
        phone.sticks(1000, 2000);
        Waits.waitFor(board::armed, 5000, "the made-up board to arm in the window", said);
    }

    /** A line the keeper said has these words. */
    boolean saidOf(String words) {
        for (String line : said) if (line.contains(words)) return true;
        return false;
    }

    void waitForSaid(String words) throws InterruptedException {
        Waits.waitFor(() -> saidOf(words), 8000, "the keeper to say \"" + words + "\"", said);
    }

    /** How many stick frames the board has taken. */
    long sticksAtBoard() {
        return board.mspRequests().stream().filter(c -> c == Msp.SET_RAW_RC).count();
    }

    @Override
    public void close() {
        beating = false;
        if (rig != null) rig.close();
        if (keeper != null) keeper.close();
    }
}
