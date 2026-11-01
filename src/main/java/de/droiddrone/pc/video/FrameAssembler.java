package de.droiddrone.pc.video;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts encoded frames back together from their UDP fragments (port of upstream Control FrameFragments and the
 * reassembly in Control Udp.processData). Latency first, like upstream: a frame is released as soon as its first and
 * last fragments are in - a missing middle fragment is not waited for (zero-filled: the decoder decodes up to the gap
 * and conceals the rest; cut off at the gap instead it gives the very same picture, measured).
 * <p>
 * When a newer frame completes, an older unfinished one whose beginning is in goes out first, damaged. Before, a key
 * frame that lost its <b>last</b> packet was dropped without a word and the frames after it decoded on top of the old
 * picture - measured worse than the damaged key frame (VideoTest). One without its beginning is lost. Fragments of a frame older than (or the same as) the last one
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
    /**
     * Bad coverage mode: the phone sends key frames twice, so a key frame with a gap isn't released at once - its second
     * copy usually fills it. It goes out when whole, or (damaged) when the next frame completes.
     */
    private boolean waitForWholeKeyFrames;
    /** Older frames released (damaged) because a newer one completed: decoded before it, see {@link #takeFlushed}. */
    private final List<Frame> flushed = new ArrayList<>();
    private long completedFrames;
    /** Frames made whole from their parity packet (bad coverage mode). */
    private long recoveredFrames;
    private long damagedFrames;
    private long lostFrames;
    /** The newest frame released, or null; nothing at or before it is put together again. */
    private Short lastReleased;
    /** Key frame 1 has come since the last {@link #startedAfresh()}: a new stream is under way. */
    private boolean streamRestarted;

    /**
     * The phone's packet payloads: the first carries FIRST_SEGMENT bytes of the frame, every later one SEGMENT (its
     * header counted as 13 and 9 bytes, upstream's sums). The parity packet (bad coverage mode) is the XOR of them all.
     */
    static final int FIRST_SEGMENT = de.droiddrone.common.UdpCommon.packetLength - 13;
    static final int SEGMENT = de.droiddrone.common.UdpCommon.packetLength - 9;

    private static final class Pending {
        final boolean keyFrame;
        /** The frame's parity packet (bad coverage mode), or null. */
        byte[] parity;
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

        /**
         * With the parity in and exactly one packet missing, rebuilds it: the parity XOR every packet that came.
         * @return true if the frame is whole now
         */
        boolean recover() {
            if (parity == null || size <= 0 || complete()) return complete();
            int missingAt = -1, missingLength = 0;
            for (int at = 0; at < size; ) {
                int length = Math.min(at == 0 ? FIRST_SEGMENT : SEGMENT, size - at);
                byte[] got = fragments.get(at);
                if (got == null) {
                    if (missingAt >= 0) return false; // two or more lost: a single parity can't help
                    missingAt = at;
                    missingLength = length;
                } else if (got.length != length) {
                    return false; // not the phone's usual layout: leave it
                }
                at += length;
            }
            if (missingAt < 0) return complete();
            byte[] rebuilt = java.util.Arrays.copyOf(parity, Math.max(parity.length, SEGMENT));
            for (var e : fragments.entrySet()) {
                byte[] d = e.getValue();
                for (int i = 0; i < d.length && i < rebuilt.length; i++) rebuilt[i] ^= d[i];
            }
            put(missingAt, java.util.Arrays.copyOf(rebuilt, missingLength));
            if (missingAt == 0) startReceived = true;
            return complete();
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
            retireOlder(frameNum, keyFrame);
            completedFrames++;
            lastReleased = frameNum;
            return new Frame(data, keyFrame, frameNum, false, keyFrame && frameNum == 1);
        }
        Pending frame = pending(frameNum, keyFrame);
        if (offset == 0) frame.setSize(frameSize);
        frame.put(offset, data);
        return finish(frameNum, frame, keyFrame);
    }

    /**
     * The parity packet of a frame (bad coverage mode, phone app 2.3.0-pc.15): rebuilds a single lost packet of it.
     * @return the frame, if that made it whole (or it was waiting for this), else null
     */
    public Frame putParity(boolean keyFrame, short frameNum, int frameSize, byte[] buf, int dataOffset, int length) {
        if (frameSize <= 0 || frameSize > MAX_FRAME_BYTES || length <= 0) return null;
        if (lastReleased != null && (frameNum == lastReleased || isOlder(frameNum, lastReleased))) return null;
        Pending frame = pending(frameNum, keyFrame);
        if (frame.size <= 0) frame.setSize(frameSize);
        frame.parity = java.util.Arrays.copyOfRange(buf, dataOffset, dataOffset + length);
        if (frame.recover()) recoveredFrames++;
        return frame.received > 0 ? finish(frameNum, frame, keyFrame) : null;
    }

    private Pending pending(short frameNum, boolean keyFrame) {
        Pending frame = pending.get(frameNum);
        if (frame == null) {
            if (pending.size() >= MAX_PENDING) dropOldest();
            frame = new Pending(keyFrame);
            pending.put(frameNum, frame);
        }
        return frame;
    }

    private Frame finish(short frameNum, Pending frame, boolean keyFrame) {
        if (!frame.complete() && frame.parity != null && frame.recover()) recoveredFrames++;
        if (!frame.complete() && !(frame.startReceived && frame.endReceived)) return null;
        // bad coverage mode: an incomplete frame waits for its parity (right behind it) or a key frame's second copy;
        // if neither makes it whole, it goes out damaged when the next frame completes (retireOlder)
        if (!frame.complete() && waitForWholeKeyFrames && (frame.keyFrame || frame.parity == null)) return null;
        pending.remove(frameNum);
        retireOlder(frameNum, keyFrame);
        boolean damaged = !frame.complete();
        if (damaged) damagedFrames++; else completedFrames++;
        lastReleased = frameNum;
        return release(frame, frameNum, damaged);
    }

    private Frame release(Pending frame, short frameNum, boolean damaged) {
        return new Frame(frame.assemble(), frame.keyFrame, frameNum, damaged, frame.keyFrame && frameNum == 1);
    }

    /**
     * Frames older than frameNum can't wait any longer. A key frame needs nothing before it: they are lost. Before
     * any other frame, those whose beginning is in go out first, oldest first, damaged (see
     * {@link #takeFlushed}): the frames after them need them as reference. The rest are lost.
     */
    private void retireOlder(short frameNum, boolean keyFrame) {
        List<Short> older = new ArrayList<>();
        for (short n : pending.keySet()) if (isOlder(n, frameNum)) older.add(n);
        // oldest first: the furthest behind frameNum (wrap-around safe)
        older.sort((a, b) -> Integer.compare((frameNum - b + 32768) % 32768, (frameNum - a + 32768) % 32768));
        for (short n : older) {
            Pending p = pending.remove(n);
            if (!keyFrame && p.startReceived && p.size > 0) {
                damagedFrames++;
                lastReleased = n;
                flushed.add(release(p, n, true));
            } else {
                lostFrames++;
            }
        }
    }

    /** Older frames released (damaged) by the last {@link #put}, to be decoded before the frame it returned. */
    public List<Frame> takeFlushed() {
        if (flushed.isEmpty()) return List.of();
        List<Frame> out = new ArrayList<>(flushed);
        flushed.clear();
        return out;
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

    public void setWaitForWholeKeyFrames(boolean on) {
        waitForWholeKeyFrames = on;
    }

    public void reset() {
        pending.clear();
        flushed.clear();
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

    public long getRecoveredFrames() {
        return recoveredFrames;
    }

    public long getDamagedFrames() {
        return damagedFrames;
    }

    public long getLostFrames() {
        return lostFrames;
    }
}
