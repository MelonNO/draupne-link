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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DRAUPNE Flight: the sleep notice and the check-in hello leave from a thread of their own (F-056).
 * <p>
 * Found by the test bench (2026-11-21, the real app in the emulator for hours): the power modes' one thread sent the
 * notice itself, and a send waits while the link socket's send buffer is full. Once it stood in such a send for
 * 138 s: the countdown's "fall asleep" waited behind it, the camera ran and the screen stayed on, while the PC - told
 * by an earlier notice - showed the phone asleep and stopped calling; the socket's watch ({@link SendWatch}) opens it
 * anew only while the PC is heard, so nothing would have freed it before the battery was empty. Another time it stood
 * 5 s, and all three notices then went into the socket being opened anew: the PC was never told and said the phone
 * did not take DEEP SLEEP.
 * <p>
 * So a send here never holds its caller ({@link #offer}), and before the link closes for the sleep
 * {@link #lastChance} makes sure one notice left: the socket opened anew if a send stands in it, one more notice, a
 * bounded wait. Plain java.net: the PC's tests check it.
 */
public final class SleepSends {
    /** One notice or hello; true when it left the socket. */
    public interface Send {
        boolean send();
    }

    /** How long a send counts as just under way rather than standing in a full buffer, ms. */
    static final long STANDING_MS = 200;
    private final ExecutorService out;
    private final AtomicBoolean standing = new AtomicBoolean();
    private volatile boolean delivered;

    public SleepSends(String threadName) {
        out = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /** A new countdown: nothing told yet. */
    public void begin() {
        delivered = false;
    }

    /** Whether a notice left the socket since {@link #begin}. */
    public boolean delivered() {
        return delivered;
    }

    /**
     * Sent from the thread of its own; the caller goes on at once. Dropped when one still stands in its send: it would
     * only wait behind it.
     * @return whether it was taken
     */
    public boolean offer(Send s) {
        if (!standing.compareAndSet(false, true)) return false;
        try {
            out.execute(() -> {
                try {
                    if (s.send()) delivered = true;
                } catch (RuntimeException e) {
                    Log.log("Sleep notice: " + e);
                } finally {
                    standing.set(false);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            standing.set(false);
            return false;
        }
    }

    /**
     * Right before the link closes for the sleep: when no notice has left since {@link #begin}, the socket is opened
     * anew if a send stands in it (which lets that send go), and one more notice is sent - waited for at most
     * {@code waitMs}, so the sleep comes in any case.
     * @param link the link's socket, or null
     * @return whether a notice left
     */
    public boolean lastChance(LinkSocket link, Send s, long waitMs) {
        if (delivered) return true;
        long end = System.nanoTime() + waitMs * 1_000_000L;
        if (link != null && link.stuckMs() >= STANDING_MS) link.reopen();
        waitWhileStanding(end); // the send let go by the reopen leaves the thread free
        if (delivered) return true;
        if (!offer(s)) return false;
        waitWhileStanding(end);
        return delivered;
    }

    private void waitWhileStanding(long endNs) {
        while (standing.get() && System.nanoTime() < endNs) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The service ends: a send standing is the socket's to let go. */
    public void shutdown() {
        out.shutdownNow();
    }
}
