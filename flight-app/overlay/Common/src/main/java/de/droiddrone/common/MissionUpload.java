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
 * DRAUPNE fleet (PRD RT-8): a mission into ArduPilot by MAVLink's mission protocol, then read back item by item and
 * compared before START is offered. Upload: MISSION_COUNT, the FC asks for each item (MISSION_REQUEST_INT), each is
 * sent (MISSION_ITEM_INT), the FC ends with MISSION_ACK. Read-back: MISSION_REQUEST_LIST, the FC's MISSION_COUNT, each
 * item asked for and compared with what was sent, a MISSION_ACK to end it.
 * <p>
 * Pure: the messages go out through {@link Link} and come in by the on- methods; time is the caller's (monotonic ms),
 * {@link #tick} once in a while for the timeouts. Thread-safe (each call under its lock): on the phone the FC's answers come on its reader thread, the timeouts on
 * the 10 Hz thread and the blocks on the receive thread - they raced on the state and the retries before. Meant to run on the phone,
 * next to the flight controller: over USB an item's round trip is a few ms, over 4G 50 ms or more - 100 items there
 * would take 5 s each way (the phase 2 gate is under 5 s for all of it). The PC sends the phone the whole mission at
 * once ({@link MissionItem#pack}). Java 11.
 * <p>
 * Item 0 is home: ArduPilot keeps its own there and answers its home on read-back, so item 0 is sent as planned but
 * not compared.
 */
public final class MissionUpload {
    /** What goes to the flight controller. */
    public interface Link {
        void sendCount(int count);

        void sendItem(MissionItem item);

        void sendRequestList();

        void sendRequest(int seq);

        void sendAck(int type);
    }

    public static final int UPLOADING = 1, READING_BACK = 2, DONE = 3, FAILED = 4;
    /** MAV_MISSION_TYPE: the route (0), the geofence (1, AP-9), the rally points (2, AC-10). */
    public static final int TYPE_MISSION = 0, TYPE_FENCE = 1, TYPE_RALLY = 2;
    /** MAV_MISSION_ACCEPTED. */
    public static final int ACK_ACCEPTED = 0;
    /**
     * MAV_MISSION_INVALID_SEQUENCE: ArduPilot's answer to an item it didn't ask for (a resend after a silence, an answer to its
     * own repeated request) - it goes on with the upload, so this is no end of it.
     */
    public static final int ACK_INVALID_SEQUENCE = 13;
    /** No word from the FC for this long: the last message is sent again. */
    public static final long TIMEOUT_MS = 1000;
    /** Sent again this many times without an answer: failed. */
    public static final int MAX_RETRIES = 5;

    private final List<MissionItem> items;
    private final Link link;
    private final int missionType;
    private int state;
    private String reason = "";
    private long lastHeard;
    private int retries;
    private int highestSent = -1;
    private int readCount = -1;
    private int readNext;
    private final List<MissionItem> readBack = new ArrayList<>();
    private long startedAt;
    private long endedAt;

    public MissionUpload(List<MissionItem> items, Link link) {
        this(items, link, TYPE_MISSION);
    }

    /**
     * @param missionType which of the FC's lists: TYPE_MISSION, TYPE_FENCE or TYPE_RALLY - the Link sends it in every
     *                    message; only a mission's item 0 is the FC's own home (not compared), a fence's and the rally
     *                    points' items are all compared
     */
    public MissionUpload(List<MissionItem> items, Link link, int missionType) {
        if (items == null || items.isEmpty() && missionType == TYPE_MISSION) throw new IllegalArgumentException("no mission items");
        this.missionType = missionType;
        List<MissionItem> numbered = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) numbered.add(items.get(i).withSeq(i));
        this.items = numbered;
        this.link = link;
    }

    public synchronized void start(long now) {
        state = UPLOADING;
        startedAt = now;
        lastHeard = now;
        link.sendCount(items.size());
    }

    /** MISSION_REQUEST_INT (or the old MISSION_REQUEST) from the FC: it wants this item. */
    public synchronized void onRequest(int seq, long now) {
        // the read-back began on a silence after the last item, but the FC still asks: it hadn't got it (see tick)
        if (state == READING_BACK && readCount < 0 && seq >= 0 && seq < items.size()) state = UPLOADING;
        if (state != UPLOADING) return;
        if (seq < 0 || seq >= items.size()) {
            fail("the flight controller asked for item " + seq + " of " + items.size(), now);
            return;
        }
        heard(now);
        highestSent = Math.max(highestSent, seq);
        link.sendItem(items.get(seq));
    }

    /** MISSION_ACK from the FC. */
    public synchronized void onAck(int type, long now) {
        if (state != UPLOADING) return;
        if (type == ACK_INVALID_SEQUENCE) return; // not the end: the FC asks again for what it wants (or times out: CANCELLED)
        heard(now);
        if (type != ACK_ACCEPTED) {
            fail("the flight controller refused the mission (" + ackName(type) + ")", now);
            return;
        }
        if (highestSent != items.size() - 1) {
            fail("the flight controller ended the upload at item " + highestSent + " of " + items.size(), now);
            return;
        }
        state = READING_BACK;
        retries = 0;
        link.sendRequestList();
    }

    /** MISSION_COUNT from the FC, the read-back's first answer. */
    public synchronized void onCount(int count, long now) {
        if (state != READING_BACK || readCount >= 0) return;
        heard(now);
        if (count != items.size()) {
            fail("read back " + count + " items, sent " + items.size(), now);
            return;
        }
        readCount = count;
        readNext = 0;
        if (count == 0) { // an empty fence or rally list (cleared): nothing to read back
            link.sendAck(ACK_ACCEPTED);
            state = DONE;
            endedAt = now;
            return;
        }
        link.sendRequest(0);
    }

    /** MISSION_ITEM_INT from the FC during the read-back. */
    public synchronized void onItem(MissionItem item, long now) {
        if (state != READING_BACK || readCount < 0 || item == null || item.seq != readNext) return;
        heard(now);
        MissionItem sent = items.get(item.seq);
        if ((item.seq > 0 || missionType != TYPE_MISSION) && !sent.sameAs(item)) {
            fail("item " + item.seq + " reads back other than sent: " + sent.diff(item), now);
            return;
        }
        readBack.add(item);
        readNext++;
        if (readNext < readCount) {
            link.sendRequest(readNext);
        } else {
            link.sendAck(ACK_ACCEPTED);
            state = DONE;
            endedAt = now;
        }
    }

    /** Now and then (every 100 ms or so): a silent FC gets the last message again, up to {@link #MAX_RETRIES}. */
    public synchronized void tick(long now) {
        if (state != UPLOADING && state != READING_BACK) return;
        if (now - lastHeard < TIMEOUT_MS) return;
        if (++retries > MAX_RETRIES) {
            fail(state == UPLOADING ? "no answer from the flight controller during the upload"
                    : "no answer from the flight controller during the read-back", now);
            return;
        }
        lastHeard = now;
        if (state == UPLOADING) {
            if (highestSent < 0) {
                link.sendCount(items.size());
            } else if (highestSent == items.size() - 1) {
                // every item was asked for and sent: the FC's last MISSION_ACK was lost (or it still waits for the last
                // item). The read-back tells which - the route is there whole, or the count or an item differs
                state = READING_BACK;
                retries = 0;
                link.sendRequestList();
            } else {
                link.sendItem(items.get(highestSent)); // the FC asks again for what it lost; this wakes a lost answer
            }
        } else if (readCount < 0) {
            link.sendRequestList();
        } else {
            link.sendRequest(readNext);
        }
    }

    private void heard(long now) {
        lastHeard = now;
        retries = 0;
    }

    private void fail(String why, long now) {
        state = FAILED;
        reason = why;
        endedAt = now;
    }

    public synchronized int state() {
        return state;
    }

    public synchronized boolean done() {
        return state == DONE;
    }

    public synchronized boolean failed() {
        return state == FAILED;
    }

    /** Why it failed ("" otherwise). */
    public synchronized String reason() {
        return reason;
    }

    /** 0-100: items sent, then items read back, as one figure. */
    public synchronized int percent() {
        if (state == DONE) return 100;
        int n = items.size();
        if (n == 0) return 0; // an empty fence or rally list: nothing counted until it is done
        int done = (highestSent + 1) + (state == READING_BACK ? readNext : 0);
        return Math.min(99, done * 100 / (2 * n));
    }

    /** From start to done or failed, ms (-1 while it runs). */
    public synchronized long elapsedMs() {
        return state == DONE || state == FAILED ? endedAt - startedAt : -1;
    }

    public List<MissionItem> items() {
        return items;
    }

    /** Which of the FC's lists this upload is: TYPE_MISSION, TYPE_FENCE or TYPE_RALLY. */
    public int missionType() {
        return missionType;
    }

    /** MAV_MISSION_RESULT's names. */
    public static String ackName(int type) {
        switch (type) {
            case 0: return "ACCEPTED";
            case 1: return "ERROR";
            case 2: return "UNSUPPORTED FRAME";
            case 3: return "UNSUPPORTED";
            case 4: return "NO SPACE";
            case 5: return "INVALID";
            case 6: return "INVALID PARAM1";
            case 7: return "INVALID PARAM2";
            case 8: return "INVALID PARAM3";
            case 9: return "INVALID PARAM4";
            case 10: return "INVALID PARAM5 / X";
            case 11: return "INVALID PARAM6 / Y";
            case 12: return "INVALID PARAM7 / Z";
            case 13: return "INVALID SEQUENCE";
            case 14: return "DENIED";
            case 15: return "OPERATION CANCELLED";
            default: return "result " + type;
        }
    }
}
