/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * DRAUPNE Flight: the phone's queues and books with a cap - the answers' queue, the PCs that asked its name, the
 * session's unnumbered packets waiting for the buffer thread, the config updates. Each holds at most its cap; one more
 * drops the oldest, and that is said in one log line (at the first drop, then at most one a minute, with the count).
 * Before, each grew without a bound: a flood of keyed packets from many addresses grew the app until Android ended it -
 * the sticks with it.
 * <p>
 * Pure Java: the phone and the PC's tests use the same class.
 */
public final class DropOldest {
    /** At most one line this often after the first. */
    public static final long SAY_EVERY_MS = 60_000;

    private DropOldest() {
    }

    /** One line for an overflow. */
    public static final class Said {
        private final String what;
        private final int cap;
        private long dropped, droppedWhenSaid, saidAt;
        private boolean said;

        /** @param what what is full, for the line ("Answers"); cap its cap */
        public Said(String what, int cap) {
            this.what = what;
            this.cap = cap;
        }

        /**
         * One was dropped at now (any monotonic ms).
         * @return the line to log now, or null
         */
        public synchronized String dropped(long now) {
            dropped++;
            if (said && now - saidAt < SAY_EVERY_MS) return null;
            String line = what + ": over " + cap + " waiting - the oldest dropped (" + (dropped - droppedWhenSaid) + (said ? " since the last word)" : ")");
            said = true;
            saidAt = now;
            droppedWhenSaid = dropped;
            return line;
        }

        public synchronized long count() {
            return dropped;
        }
    }

    /**
     * A single worker thread (daemon, its name given) whose queue holds at most cap tasks: a task that finds it full
     * drops the oldest one waiting (said, see {@link Said}). Nothing that comes is refused.
     */
    public static ThreadPoolExecutor executor(String threadName, int cap, Consumer<String> log) {
        final Said said = new Said(threadName, cap);
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(cap), r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        }, (task, e) -> {
            if (e.isShutdown()) return;
            while (!e.getQueue().offer(task)) {
                if (e.getQueue().poll() != null) {
                    String line = said.dropped(System.nanoTime() / 1_000_000L);
                    if (line != null && log != null) log.accept(line);
                }
            }
        });
    }
}
