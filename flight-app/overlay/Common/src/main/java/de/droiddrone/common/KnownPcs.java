/*
 *  This file is part of DRAUPNE Flight, the phone app of DRAUPNE Link (based on DroidDrone).
 *
 *  It is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 *  version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details. You should have received a copy of the GNU General Public License along with it. If not, see
 *  <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DRAUPNE Flight pc.50: the PCs that asked this phone's name, and which of them the phone tells something by itself
 * (moved out of its Udp, unchanged) - by address (any key the caller likes), with when each last asked.
 * <ul>
 * <li>{@link #forNotice}: its sleep notice goes to the PCs that asked within {@link #NOTICE_MS}.</li>
 * <li>{@link #forAlerts} (the pilot: overwatch alerts go to "every pc"): its check-in and an overwatch alert go to the
 * PCs that asked in the last day - a sleeping phone is asked only round its check-ins, so the notice's two minutes
 * would mostly find nobody. The newest first.</li>
 * </ul>
 * Either way the PC this phone last flew with, or that set its mode, comes first, at its own port; the others at the
 * link port; {@link #MAX} in all, each address once. Pure: the caller passes its monotonic clock, the PC's tests check
 * it. The receive thread notes who asks while another thread asks whom to tell: the books are a concurrent map.
 */
public final class KnownPcs<K> {
    /** A PC that asked this phone's name hears its sleep notice for this long. */
    public static final long NOTICE_MS = 120_000;
    /** And of its check-ins and overwatch alerts for this long. */
    public static final long ALERT_MS = 24 * 3600_000L;
    /** The most PCs told at once, the first one counted. */
    public static final int MAX = 10;

    private final Map<K, Long> askedAt = new ConcurrentHashMap<>();
    private final long keepMs;
    private final boolean newestFirst;

    private KnownPcs(long keepMs, boolean newestFirst) {
        this.keepMs = keepMs;
        this.newestFirst = newestFirst;
    }

    /** The PCs that asked this phone's name lately, for its sleep notice. */
    public static <K> KnownPcs<K> forNotice() {
        return new KnownPcs<>(NOTICE_MS, false);
    }

    /** The PCs that asked this phone's name in the last day, for its check-ins and overwatch alerts: the newest first. */
    public static <K> KnownPcs<K> forAlerts() {
        return new KnownPcs<>(ALERT_MS, true);
    }

    /**
     * The most PCs a book keeps: far more than a fleet's PCs on any network (a handful; {@link #MAX} are told at once),
     * a few kilobytes. Before there was no bound, and every address that asked stayed a day (an IPv6 network has any
     * number of them).
     */
    public static final int BOOK_MAX = 256;

    /**
     * This PC asks the phone's name now.
     * @return true when the book was full and the PC that asked longest ago made room (the caller says so, DropOldest)
     */
    public boolean asked(K pc, long now) {
        boolean dropped = false;
        if (askedAt.size() >= BOOK_MAX && !askedAt.containsKey(pc)) {
            askedAt.values().removeIf(at -> now - at > keepMs); // the gone first
            if (askedAt.size() >= BOOK_MAX) {
                K oldest = null;
                long oldestAt = Long.MAX_VALUE;
                for (Map.Entry<K, Long> e : askedAt.entrySet()) {
                    if (e.getValue() < oldestAt) {
                        oldestAt = e.getValue();
                        oldest = e.getKey();
                    }
                }
                if (oldest != null) dropped = askedAt.remove(oldest) != null;
            }
        }
        askedAt.put(pc, now);
        return dropped;
    }

    /** How many PCs the book holds (tests). */
    public int size() {
        return askedAt.size();
    }

    /** Whether this PC asked the phone's name within the time this book keeps it (NameAssignGate). */
    public boolean askedWithin(K pc, long now) {
        Long at = pc == null ? null : askedAt.get(pc);
        return at != null && now - at <= keepMs;
    }

    /**
     * Whom to tell now, in order, each with the port to tell it at. The PCs that asked longer ago than this book keeps
     * them are forgotten here.
     * @param first     the PC this phone last flew with, or that set its mode; null: none yet
     * @param firstPort that PC's own port
     * @param linkPort  where the others are told
     */
    public LinkedHashMap<K, Integer> toTell(K first, int firstPort, int linkPort, long now) {
        LinkedHashMap<K, Integer> to = new LinkedHashMap<>();
        if (first != null) to.put(first, firstPort);
        askedAt.values().removeIf(at -> now - at > keepMs);
        if (newestFirst) {
            List<Map.Entry<K, Long>> newest = new ArrayList<>(askedAt.entrySet());
            newest.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<K, Long> e : newest) {
                if (to.size() >= MAX) break;
                to.putIfAbsent(e.getKey(), linkPort);
            }
        } else {
            for (K pc : askedAt.keySet()) {
                if (to.size() >= MAX) break;
                to.putIfAbsent(pc, linkPort);
            }
        }
        return to;
    }
}
