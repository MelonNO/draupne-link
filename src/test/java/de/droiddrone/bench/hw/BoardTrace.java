package de.droiddrone.bench.hw;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The board's own word over a stretch of time, as the keeper has it (its state line): armed or not, its arming blocks
 * - RXLOSS a tenth of a second after its last stick frame, FAILSAFE once its failsafe has reached stage 2 - the arm
 * window, and why the keeper ended one. Sampled ten times a second on a connection of its own ({@link #sample}); the
 * keeper reads the board's status four times a second outside a window and ten times in one. For the bad-network box
 * (R8): what the board said during a cut, and after it.
 */
final class BoardTrace {
    /** One look: when (epoch ms), and the keeper's state then. armed: null - not known. */
    record Sample(long atMs, Boolean armed, String blocks, long windowMs, String lastEnd) {
        boolean says(String block) {
            return Arrays.asList(blocks.split("\\+")).contains(block);
        }
    }

    private final List<Sample> samples = new CopyOnWriteArrayList<>();

    void add(long atMs, KeeperClient.State s) {
        samples.add(new Sample(atMs, s.armed(), s.get("blocks"), s.windowMs(), s.lastEnd()));
    }

    List<Sample> samples() {
        return List.copyOf(samples);
    }

    /** ms after fromMs of the first look at or after it in which the board named this block; -1: it did not. */
    long first(String block, long fromMs) {
        for (Sample s : samples) if (s.atMs() >= fromMs && s.says(block)) return s.atMs() - fromMs;
        return -1;
    }

    /** ms after fromMs of the last look in which the board named this block; -1: it did not. */
    long last(String block, long fromMs) {
        long last = -1;
        for (Sample s : samples) if (s.atMs() >= fromMs && s.says(block)) last = s.atMs() - fromMs;
        return last;
    }

    boolean said(String block, long fromMs) {
        return first(block, fromMs) >= 0;
    }

    /** The share of the looks from fromMs on in which the board named this block, in per cent. */
    int percent(String block, long fromMs) {
        int all = 0, with = 0;
        for (Sample s : samples) {
            if (s.atMs() < fromMs) continue;
            all++;
            if (s.says(block)) with++;
        }
        return all == 0 ? 0 : 100 * with / all;
    }

    boolean everArmed(long fromMs) {
        for (Sample s : samples) if (s.atMs() >= fromMs && Boolean.TRUE.equals(s.armed())) return true;
        return false;
    }

    /** ms after fromMs of the first look before untilMs that says disarmed after one that said armed; -1: it did not. */
    long disarmed(long fromMs, long untilMs) {
        boolean wasArmed = false;
        for (Sample s : samples) {
            if (s.atMs() >= untilMs) break;
            if (Boolean.TRUE.equals(s.armed())) wasArmed = true;
            else if (wasArmed && Boolean.FALSE.equals(s.armed()) && s.atMs() >= fromMs) return s.atMs() - fromMs;
        }
        return -1;
    }

    /** It said armed again after afterMs and before untilMs. */
    boolean armedAgain(long afterMs, long untilMs) {
        for (Sample s : samples) if (s.atMs() > afterMs && s.atMs() < untilMs && Boolean.TRUE.equals(s.armed())) return true;
        return false;
    }

    /** Why the keeper ended the window, as a look from fromMs on said it last; null: it did not (OPEN forgets the last window's). */
    String keeperEnded(long fromMs) {
        String end = null;
        for (Sample s : samples) if (s.atMs() >= fromMs && s.lastEnd() != null) end = s.lastEnd();
        return end;
    }

    /** The board's word on RXLOSS and FAILSAFE from fromMs on, for a row and the film. */
    String words(long fromMs) {
        long rx = first("RXLOSS", fromMs), fs = first("FAILSAFE", fromMs);
        if (rx < 0 && fs < 0) return "neither RXLOSS nor FAILSAFE";
        return (rx < 0 ? "no RXLOSS" : "RXLOSS at " + rx + " ms (last seen at " + last("RXLOSS", fromMs) + " ms)")
                + (fs < 0 ? ", no FAILSAFE" : ", FAILSAFE at " + fs + " ms");
    }

    /** Looks at the keeper's state every {@code everyMs} on a connection of its own, into this trace, until closed. */
    Sampler sample(int keeperPort, long everyMs) throws IOException {
        return new Sampler(new KeeperClient(keeperPort, 5000), everyMs);
    }

    final class Sampler implements AutoCloseable {
        private final KeeperClient keeper;
        private final Thread thread;
        private volatile boolean running = true;
        private volatile String failure;

        private Sampler(KeeperClient keeper, long everyMs) {
            this.keeper = keeper;
            look(); // one at once: the trace begins with the state of this moment
            thread = new Thread(() -> {
                while (running) {
                    try {
                        Thread.sleep(everyMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (running) look();
                }
            }, "benchBoardTrace");
            thread.setDaemon(true);
            thread.start();
        }

        private void look() {
            try {
                add(System.currentTimeMillis(), keeper.state());
            } catch (IOException e) {
                failure = e.getMessage();
            }
        }

        /** The last time the keeper did not answer a look; null: it always did. */
        String failure() {
            return failure;
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            look(); // and one at the end
            keeper.close();
        }
    }
}
