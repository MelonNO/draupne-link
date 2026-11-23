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

import java.util.ArrayList;
import java.util.List;

/**
 * DRAUPNE Flight (fleet RT-8, AP-9, AC-10): the uploads to an ArduPilot FC, one at a time - the PC's blocks (a route, a
 * fence, rally points: MissionBlock) and the phone's own rally point (a follower's exit plan).
 * <ul>
 *   <li>A block of the list being uploaded replaces it (the newer route wins). A block of another list waits its turn
 *       and is answered as UPLOADING 0 % meanwhile - before, it replaced the running one, so a route and a fence sent
 *       together restarted each other and both failed.</li>
 *   <li>The same block again is answered with its status, also after the upload ended and the phone's own rally upload
 *       took its place - before, the PC (which repeats a block until its status names it) started it anew, and the
 *       fleet report named the phone's own upload, so the dispatch never saw its route done.</li>
 *   <li>A PC block id seen before and since replaced (a late or replayed copy) is not uploaded again: the FC keeps
 *       the newer one.</li>
 * </ul>
 * Thread-safe: the receive thread offers blocks, the FC's reader thread answers, the 10 Hz thread ticks. Java 11.
 */
public final class MissionBlockQueue {
    /** Makes an upload (not started) that talks to the FC for one list. */
    public interface Maker {
        MissionUpload make(int missionType, List<MissionItem> items);
    }

    /** The phone's own upload's block (a follower's rally point): never the PC's. */
    public static final int OWN = -1;
    /** PC blocks whose outcome is remembered: a copy of one is answered with it, never uploaded again. */
    static final int KEPT = 32;

    private final Maker maker;
    private MissionUpload current;
    private int currentBlock = OWN;
    /** The current PC block's items and list, as {@link #key}. */
    private int currentKey;
    private final List<MissionBlock> waiting = new ArrayList<>();
    private List<MissionItem> ownWaiting, ownOnFc;
    /** The newest PC block taken (running, waiting or ended): what the fleet report names. */
    private int newestPc = OWN;
    /** Ended PC blocks, oldest first: {block, state, percent} and the reason. */
    private final java.util.LinkedHashMap<Integer, Object[]> ended = new java.util.LinkedHashMap<>();
    private int lastEnded = OWN;

    public MissionBlockQueue(Maker maker) {
        this.maker = maker;
    }

    /** A block from the PC (null ignored). @return true if its status should be said now (taken, waiting or known) */
    public synchronized boolean offer(MissionBlock b, long now) {
        if (b == null) return false;
        int id = b.blockId, key = key(b);
        // the same block again (its id and its items: two PCs' ids may meet): its status
        if (id == currentBlock && current != null && key == currentKey) return true;
        for (MissionBlock w : waiting) if (w.blockId == id && key(w) == key) return true;
        Object[] e = ended.get(id);
        if (e != null && (Integer) e[3] == key) return true; // ended (or replaced): its outcome, never uploaded again
        if (e != null) ended.remove(id); // the same id, other items: a new block
        newestPc = id;
        boolean busy = current != null && !current.done() && !current.failed();
        if (busy && (currentBlock == OWN || current.missionType() != b.missionType)) {
            for (int i = waiting.size() - 1; i >= 0; i--) {
                MissionBlock w = waiting.get(i);
                if (w.missionType == b.missionType) { // the newer of a list wins
                    end(w.blockId, MissionUpload.FAILED, 0, "replaced by a newer one");
                    waiting.remove(i);
                }
            }
            waiting.add(b);
            return true;
        }
        if (busy) { // the same list, a newer block: it replaces the running one
            end(currentBlock, MissionUpload.FAILED, current.percent(), "replaced by a newer one");
        }
        start(b, now);
        return true;
    }

    /** The phone's own rally points (AC-10): uploaded when they changed, after any upload under way. */
    public synchronized void ownRally(List<MissionItem> items, long now) {
        if (items == null || same(items, ownOnFc) || same(items, ownWaiting)) return;
        if (current != null && !current.done() && !current.failed()) {
            ownWaiting = items;
            return;
        }
        startOwn(items, now);
    }

    /**
     * Ten times a second: the upload's timeouts, then the next waiting one (the PC's before the phone's own).
     * @return true when an upload ended (see {@link #lastEnded}) or began
     */
    public synchronized boolean tick(long now) {
        lastEnded = OWN;
        if (current == null) return false;
        current.tick(now);
        if (!current.done() && !current.failed()) return false;
        boolean changed = false;
        if (currentBlock == OWN) {
            if (current.done() && current.missionType() == MissionUpload.TYPE_RALLY) ownOnFc = current.items();
        } else if (!ended.containsKey(currentBlock)) {
            end(currentBlock, current.state(), current.percent(), current.failed() ? current.reason() : "");
            lastEnded = currentBlock;
            changed = true;
        }
        if (!waiting.isEmpty()) {
            start(waiting.remove(0), now);
            return true;
        }
        if (ownWaiting != null) {
            List<MissionItem> o = ownWaiting;
            ownWaiting = null;
            startOwn(o, now);
            return true;
        }
        return changed;
    }

    /** The PC block whose upload ended in the last {@link #tick} (its status to be said), or {@link #OWN}. */
    public synchronized int lastEnded() {
        return lastEnded;
    }

    /** The upload talking to the FC now (its answers go to it), or null. */
    public synchronized MissionUpload current() {
        return current;
    }

    /** The current upload's block: a PC block id, or {@link #OWN}. */
    public synchronized int currentBlock() {
        return currentBlock;
    }

    /** The newest PC block's {block, state, percent} (block -1 before any): the fleet report's route. */
    public synchronized int[] pcStatus() {
        int[] s = status(newestPc);
        return s == null ? new int[]{OWN, 0, 0} : new int[]{newestPc, s[0], s[1]};
    }

    /** A PC block's {state, percent}: running, waiting (UPLOADING 0), or ended; null when not known here. */
    public synchronized int[] status(int block) {
        if (block == OWN) return null;
        if (block == currentBlock && current != null) return new int[]{current.state(), current.percent()};
        for (MissionBlock w : waiting) if (w.blockId == block) return new int[]{MissionUpload.UPLOADING, 0};
        Object[] e = ended.get(block);
        return e == null ? null : new int[]{(Integer) e[0], (Integer) e[1]};
    }

    /** Why a PC block failed ("" otherwise, or not known). */
    public synchronized String reason(int block) {
        if (block == currentBlock && current != null) return current.failed() ? current.reason() : "";
        Object[] e = ended.get(block);
        return e == null ? "" : (String) e[2];
    }

    private void start(MissionBlock b, long now) {
        keepEnded();
        MissionUpload u = maker.make(b.missionType, b.items);
        current = u;
        currentBlock = b.blockId;
        currentKey = key(b);
        u.start(now);
    }

    private void startOwn(List<MissionItem> items, long now) {
        keepEnded();
        MissionUpload u = maker.make(MissionUpload.TYPE_RALLY, items);
        current = u;
        currentBlock = OWN;
        u.start(now);
    }

    /** The PC upload being replaced (ended or not, ticked or not) keeps its outcome. */
    private void keepEnded() {
        if (current == null || currentBlock == OWN || ended.containsKey(currentBlock)) return;
        end(currentBlock, current.state(), current.percent(), current.failed() ? current.reason() : "");
    }

    private void end(int block, int state, int percent, String reason) {
        ended.remove(block);
        ended.put(block, new Object[]{state, percent, reason, block == currentBlock ? currentKey : keyOfWaiting(block)});
        if (ended.size() > KEPT) ended.remove(ended.keySet().iterator().next());
    }

    /** A block's items and list as one number: a copy has the same, another block under the same id (almost surely) not. */
    static int key(MissionBlock b) {
        return 31 * java.util.Arrays.hashCode(MissionItem.pack(b.items)) + b.missionType;
    }

    private int keyOfWaiting(int block) {
        for (MissionBlock w : waiting) if (w.blockId == block) return key(w);
        return 0;
    }

    private static boolean same(List<MissionItem> a, List<MissionItem> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).sameAs(b.get(i))) return false;
        return true;
    }
}
