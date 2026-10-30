package de.droiddrone.pc.video;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts encoded frames back together from their UDP fragments (port of upstream Control FrameFragments and the
 * reassembly in Control Udp.processData). Latency first, like upstream: a frame is released as soon as its first and
 * last fragments are in - a missing middle fragment is not waited for (the decoder conceals it) - and older unfinished
 * frames are dropped the moment a newer one completes. Fragments of a frame older than (or the same as) the last one
 * released are dropped: on 4G packets can come reordered or twice, and a frame decoded out of turn smears the picture.
 * The phone numbers a restarted stream from 1, and its first frame is a key frame: key frame 1 starts afresh.
 * Not thread-safe: call from the receive thread only.
 */
public class FrameAssembler {
    /**
     * @param damaged     true if fragments were missing (zero-filled)
     * @param streamStart the first frame of a (re)started stream (key frame number 1)
     */
    public record Frame(byte[] data, boolean keyFrame, short number, boolean damaged, boolean streamStart) {}

    private static final int MAX_PENDING = 64;
    /** Frame numbers count 1..32767 and wrap; numbers within this distance behind a frame count as older. */
    private static final int ORDER_WINDOW = 1000;
    /** Bigger than any frame the phone sends; a size from a broken packet beyond it is not allocated. */
    static final int MAX_FRAME_BYTES = 8 << 20;

    private final Map<Short, Pending> pending = new HashMap<>();
    private long completedFrames;
    private long damagedFrames;
    private long lostFrames;
    /** The newest frame released, or null; nothing at or before it is put together again. */
    private Short lastReleased;
    /** Key frame 1 has come since the last {@link #startedAfresh()}: a new stream is under way. */
    private boolean streamRestarted;

    private static final class Pending {
        final boolean keyFrame;
        int size = -1; // unknown until the offset-0 fragment arrives
        int received;
        boolean startReceived;
        boolean endReceived;
        final Map<Integer, byte[]> fragments = new HashMap<>();

        Pending(boolean keyFrame) {
            this.keyFrame = keyFrame;
        }

        void put(int offset, byte[] data) {
            if (fragments.putIfAbsent(offset, data) != null) return;
            received += data.length;
            if (offset == 0) startReceived = true;
            if (size > 0 && offset + data.length == size) endReceived = true;
        }

        void setSize(int frameSize) {
            size = frameSize;
            for (var e : fragments.entrySet()) {
                if (e.getKey() + e.getValue().length == size) endReceived = true;
            }
        }

        boolean complete() {
            return size > 0 && received >= size;
        }

        byte[] assemble() {
            byte[] frame = new byte[size];
            for (var e : fragments.entrySet()) {
                int offset = e.getKey();
                byte[] data = e.getValue();
                if (offset >= 0 && offset + data.length <= size) System.arraycopy(data, 0, frame, offset, data.length);
            }
            return frame;
        }
    }

    /**
     * Adds one fragment.
     * @param frameSize total frame size, only known (> 0) on the fragment at offset 0
     * @return the finished frame, or null
     */
    public Frame put(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
        if (offset < 0 || length < 0 || (offset == 0 && (frameSize <= 0 || frameSize > MAX_FRAME_BYTES))) return null;
        if (offset > MAX_FRAME_BYTES) return null;
        if (keyFrame && frameNum == 1 && lastReleased != null && lastReleased != 1 && !pending.containsKey(frameNum)) {
            pending.clear(); // the phone restarted its stream: the old one's leftovers are useless
            lastReleased = null;
            streamRestarted = true;
        }
        if (lastReleased != null && (frameNum == lastReleased || isOlder(frameNum, lastReleased))) return null;
        byte[] data = new byte[length];
        System.arraycopy(buf, dataOffset, data, 0, length);
        if (offset == 0 && frameSize == length) { // whole frame in one packet
            dropOlderThan(frameNum, keyFrame);
            completedFrames++;
            lastReleased = frameNum;
            return new Frame(data, keyFrame, frameNum, false, keyFrame && frameNum == 1);
        }
        Pending frame = pending.get(frameNum);
        if (frame == null) {
            if (pending.size() >= MAX_PENDING) dropOldest();
            frame = new Pending(keyFrame);
            pending.put(frameNum, frame);
        }
        if (offset == 0) frame.setSize(frameSize);
        frame.put(offset, data);
        if (!frame.complete() && !(frame.startReceived && frame.endReceived)) return null;
        pending.remove(frameNum);
        dropOlderThan(frameNum, keyFrame);
        boolean damaged = !frame.complete();
        if (damaged) damagedFrames++; else completedFrames++;
        lastReleased = frameNum;
        return new Frame(frame.assemble(), frame.keyFrame, frameNum, damaged, frame.keyFrame && frameNum == 1);
    }

    /** Older frames can no longer be shown in order; a finished P-frame leaves pending key frames alone (as upstream). */
    private void dropOlderThan(short frameNum, boolean keyFrame) {
        List<Short> stale = new ArrayList<>();
        for (var e : pending.entrySet()) {
            if (isOlder(e.getKey(), frameNum) && (keyFrame || !e.getValue().keyFrame)) stale.add(e.getKey());
        }
        for (short n : stale) pending.remove(n);
        lostFrames += stale.size();
    }

    private void dropOldest() {
        Short oldest = null;
        for (short n : pending.keySet()) {
            if (oldest == null || isOlder(n, oldest)) oldest = n;
        }
        if (oldest != null) {
            pending.remove(oldest);
            lostFrames++;
        }
    }

    /** a is older than b, allowing for wrap-around of the 1..32767 counter. */
    static boolean isOlder(short a, short b) {
        int diff = (b - a + 32768) % 32768;
        return diff > 0 && diff < ORDER_WINDOW;
    }

    public void reset() {
        pending.clear();
        lastReleased = null;
        streamRestarted = false;
    }

    /**
     * A new stream configuration came: true if this assembler already saw the new stream start (its key frame 1), so
     * what it holds belongs to it; then it carries on. Otherwise it starts afresh.
     */
    public boolean startedAfresh() {
        boolean restarted = streamRestarted;
        streamRestarted = false;
        if (!restarted) reset();
        return restarted;
    }

    public long getCompletedFrames() {
        return completedFrames;
    }

    public long getDamagedFrames() {
        return damagedFrames;
    }

    public long getLostFrames() {
        return lostFrames;
    }
}
