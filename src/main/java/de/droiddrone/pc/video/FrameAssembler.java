package de.droiddrone.pc.video;

import de.droiddrone.common.UdpCommon;

import java.util.ArrayList;
import java.util.Arrays;
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
 * The phone numbers a restarted stream from 1, and its first frame is a key frame: key frame 1 starts afresh (but not
 * a late or doubled fragment of the key frame 1 just released, and after ORDER_RESET_MS without a frame the order is
 * forgotten). A frame number passed over (the frame lost whole, every packet of it) counts as lost once a newer frame is
 * released, so the pipeline asks for a key frame and the bitrate steps down (1.0.49).
 * <p>
 * Each fragment is copied once, straight into the frame-sized array the frame is handed over in (the offset-0 fragment
 * carries the size; the rare fragment that comes before it goes into a provisional array that grows). What came is
 * kept as a short list of offsets, not a map: fragments arrive in order nearly always, and a frame has a few dozen.
 * Not thread-safe: call from the receive thread only.
 */
public class FrameAssembler {
    /**
     * @param damaged     true if fragments were missing (zero-filled)
     * @param streamStart the first frame of a (re)started stream (key frame number 1)
     * @param arrivedMs   when the fragment that completed it came (MonoClock.ms), 0 if not given
     */
    public record Frame(byte[] data, boolean keyFrame, short number, boolean damaged, boolean streamStart, long arrivedMs) {
        public Frame(byte[] data, boolean keyFrame, short number, boolean damaged, boolean streamStart) {
            this(data, keyFrame, number, damaged, streamStart, 0);
        }
    }

    private static final int MAX_PENDING = 64;
    /** Frame numbers count 1..32767 and wrap; numbers within this distance behind a frame count as older. */
    private static final int ORDER_WINDOW = 1000;
    /**
     * The largest frame that is plausible: the biggest picture the phone sends (1920x1080) raw - YUV 4:2:0, 1.5 bytes
     * a pixel - and a little; no encoded frame is bigger. A broken packet claiming more is not allocated (1.0.49:
     * before, any size up to 8 MB was, per frame number). Not from the stream's config: a restarted stream's key
     * frame often comes before its config, and a bigger picture's would be dropped.
     */
    static final int PLAUSIBLE_FRAME_BYTES = 1920 * 1080 * 3 / 2 + (64 << 10);
    /** What the frames being put together may hold in all; beyond it the oldest go (a flood of broken sizes). */
    static final long PENDING_BUDGET = 4L * PLAUSIBLE_FRAME_BYTES;
    /**
     * A frame this far ahead of the last one released (plus MAX_FPS a second since it) can't be this stream's: a late
     * frame of the stream before a restart. AHEAD_NEAR_OLD_STREAM for a number near where the old stream stood
     * within ORDER_RESET_MS of the restart, else AHEAD_SLACK. Before 1.0.49 such a frame was released, and every
     * frame of the new stream after it counted as older - the picture froze until ORDER_RESET_MS.
     */
    static final int MAX_FPS = 120, AHEAD_SLACK = 300, AHEAD_NEAR_OLD_STREAM = 30;
    /** The provisional array for fragments that come before the size is known: a start, and how it grows. */
    private static final int PROVISIONAL_BYTES = 16 << 10;

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
    /**
     * No frame released for this long: the order is forgotten (lastReleased goes). After a long blackout the phone's
     * counter may stand anywhere, and a new frame that looks up to ORDER_WINDOW behind the last one was dropped for good.
     */
    static final long ORDER_RESET_MS = 3000;
    /** When lastReleased was set (the put's time; 0 = not known, tests without times). */
    private long lastReleasedAt;
    /**
     * The key frame 1 released last (the stream's start), for telling a late or doubled fragment of it from a new
     * stream's key frame 1: the same bytes (or a gap the released frame was zero-filled at) are the old frame's.
     */
    private byte[] frameOne;
    /** Key frame 1 has come since the last {@link #startedAfresh()}: a new stream is under way. */
    private boolean streamRestarted;
    /** The time of the last put, handed to the frames released by it (also the older ones flushed). */
    private long now;
    /**
     * The newest frame number accounted for: released, or counted lost (null: none since the order was last
     * forgotten). The numbers between it and the next one accounted for never came whole: lost (1.0.49 - before,
     * a frame lost with every packet of it was never counted, so no key frame was asked for: a one-packet P-frame at
     * the low tiers left the picture smeared until the next scheduled key frame, 2-10 s).
     */
    private Short lastAccounted;
    /** The old stream's last frame at a restart, and until when a frame near it is taken for one of it (see MAX_FPS). */
    private short oldStreamLast;
    private long oldStreamUntil;

    /**
     * The phone's packet payloads: the first carries FIRST_SEGMENT bytes of the frame, every later one SEGMENT (its
     * header counted as 13 and 9 bytes, upstream's sums). The parity packet (bad coverage mode) is the XOR of them all.
     */
    static final int FIRST_SEGMENT = UdpCommon.packetLength - 13;
    static final int SEGMENT = UdpCommon.packetLength - 9;
    /**
     * Group parity (phone app pc.26): the phone sends the XOR of every PARITY_GROUP packets of a frame as one
     * parity packet, so a frame survives one loss per group - a 40-packet key frame on a link losing one packet in
     * 20 was rarely saved by a single parity over all of it. Packet i of a frame is at FIRST_SEGMENT + (i - 1) *
     * SEGMENT (i > 0); group g holds packets g * PARITY_GROUP ... (g + 1) * PARITY_GROUP - 1.
     */
    public static final int PARITY_GROUP = 8;

    private static final class Pending {
        final boolean keyFrame;
        /** The first frame of a (re)started stream (judged when the first fragment came, see isStreamStart). */
        final boolean streamStart;
        /** The frame's parity packet (bad coverage mode), or null. */
        byte[] parity;
        /** The parity of each group of PARITY_GROUP packets (pc.26), by group; null until the first came. */
        byte[][] groupParity;
        int size = -1; // unknown until the offset-0 fragment arrives
        int received;
        boolean startReceived;
        boolean endReceived;
        /** The frame itself, fragments copied in place; sized to the frame once the size is known. */
        byte[] data = new byte[0];
        /** The fragments that came: offset and length each, count of them (duplicates are told by the offset). */
        int[] offsets = new int[16];
        int[] lengths = new int[16];
        int count;

        Pending(boolean keyFrame, boolean streamStart) {
            this.keyFrame = keyFrame;
            this.streamStart = streamStart;
        }

        private int indexOf(int offset) {
            for (int i = 0; i < count; i++) if (offsets[i] == offset) return i;
            return -1;
        }

        /** @return false if a fragment at this offset was in already (the same datagram twice) */
        boolean put(int offset, byte[] buf, int bufOffset, int length) {
            if (indexOf(offset) >= 0) return false;
            int end = offset + length;
            if (size > 0 && end > size) length = Math.max(0, size - offset); // beyond the frame: not the phone's; clipped
            if (data.length < offset + length) { // before the size is known: a provisional array that grows
                int grown = Math.max(offset + length, Math.max(PROVISIONAL_BYTES, data.length * 2));
                data = Arrays.copyOf(data, Math.min(grown, PLAUSIBLE_FRAME_BYTES + SEGMENT));
            }
            System.arraycopy(buf, bufOffset, data, offset, length);
            if (count == offsets.length) {
                offsets = Arrays.copyOf(offsets, count * 2);
                lengths = Arrays.copyOf(lengths, count * 2);
            }
            offsets[count] = offset;
            lengths[count] = length;
            count++;
            received += length;
            if (offset == 0) startReceived = true;
            if (size > 0 && end == size) endReceived = true;
            return true;
        }

        void setSize(int frameSize) {
            size = frameSize;
            if (data.length != size) data = Arrays.copyOf(data, size); // zero-fills what hasn't come
            for (int i = 0; i < count; i++) {
                if (offsets[i] + lengths[i] == size) endReceived = true;
            }
        }

        boolean complete() {
            return size > 0 && received >= size;
        }

        /** How many packets the phone splits this frame into (the size known). */
        int packets() {
            return size <= FIRST_SEGMENT ? 1 : 1 + (size - FIRST_SEGMENT + SEGMENT - 1) / SEGMENT;
        }

        static int offsetOf(int packet) {
            return packet == 0 ? 0 : FIRST_SEGMENT + (packet - 1) * SEGMENT;
        }

        int lengthOf(int packet) {
            return Math.min(packet == 0 ? FIRST_SEGMENT : SEGMENT, size - offsetOf(packet));
        }

        /** Every parity the phone sends for this frame has come (one in all, or one per group). */
        boolean parityIn() {
            if (parity != null) return true;
            if (groupParity == null || size <= 0) return false;
            int groups = (packets() + PARITY_GROUP - 1) / PARITY_GROUP;
            if (groupParity.length < groups) return false;
            for (int g = 0; g < groups; g++) if (groupParity[g] == null) return false;
            return true;
        }

        /**
         * With a parity in and exactly one packet of its span missing, rebuilds it: the parity XOR every packet of
         * the span that came. Each group's parity first (pc.26), then the one over the whole frame (pc.15).
         * @return true if the frame is whole now
         */
        boolean recover() {
            if (size <= 0 || complete()) return complete();
            int packets = packets();
            if (groupParity != null) {
                for (int g = 0; g < groupParity.length && g * PARITY_GROUP < packets; g++) {
                    if (groupParity[g] != null) recoverSpan(groupParity[g], g * PARITY_GROUP, Math.min(packets, (g + 1) * PARITY_GROUP));
                }
            }
            if (parity != null && !complete()) recoverSpan(parity, 0, packets);
            return complete();
        }

        /** @return true if the one missing packet of packets [from, to) was rebuilt from their parity */
        private boolean recoverSpan(byte[] parity, int from, int to) {
            int missing = -1;
            for (int p = from; p < to; p++) {
                int at = offsetOf(p);
                int got = indexOf(at);
                if (got < 0) {
                    if (missing >= 0) return false; // two or more lost: a single parity can't help
                    missing = p;
                } else if (lengths[got] != lengthOf(p)) {
                    return false; // not the phone's usual layout: leave it
                }
            }
            if (missing < 0) return false;
            byte[] rebuilt = Arrays.copyOf(parity, Math.max(parity.length, SEGMENT));
            for (int p = from; p < to; p++) {
                if (p == missing) continue;
                int off = offsetOf(p), n = Math.min(lengthOf(p), rebuilt.length);
                for (int j = 0; j < n; j++) rebuilt[j] ^= data[off + j];
            }
            put(offsetOf(missing), rebuilt, 0, lengthOf(missing));
            return true;
        }

        /** The frame's bytes: the array the fragments went into, when it is the frame's size (nearly always). */
        byte[] assemble() {
            return data.length == size ? data : Arrays.copyOf(data, size);
        }
    }

    /**
     * Adds one fragment.
     * @param frameSize total frame size, only known (> 0) on the fragment at offset 0
     * @return the finished frame, or null
     */
    public Frame put(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
        return put(keyFrame, frameNum, offset, frameSize, buf, dataOffset, length, 0);
    }

    /** @param now when the fragment came (MonoClock.ms), kept on the frames it releases */
    public Frame put(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length, long now) {
        this.now = now;
        if (offset < 0 || length < 0 || (offset == 0 && (frameSize <= 0 || frameSize > PLAUSIBLE_FRAME_BYTES))) return null;
        if (offset > PLAUSIBLE_FRAME_BYTES) return null;
        forgetOldOrder();
        boolean start = isStreamStart(keyFrame, frameNum) && !isFrameOneAgain(offset, frameSize, buf, dataOffset, length);
        if (start && lastReleased != null && lastReleased != 1) {
            pending.clear(); // the phone restarted its stream: the old one's leftovers are useless
            oldStreamLast = lastReleased;
            oldStreamUntil = now + ORDER_RESET_MS;
            lastReleased = null;
            lastAccounted = null;
            frameOne = null;
            streamRestarted = true;
        }
        if (releasedAlready(frameNum)) return null;
        if (!start && tooFarAhead(frameNum)) return null;
        if (offset == 0 && frameSize == length && !pending.containsKey(frameNum)) { // whole frame in one packet
            byte[] data = new byte[length];
            System.arraycopy(buf, dataOffset, data, 0, length);
            retireOlder(frameNum, keyFrame);
            completedFrames++;
            released(frameNum, keyFrame, data);
            return new Frame(data, keyFrame, frameNum, false, start, now);
        }
        Pending frame = pending(frameNum, keyFrame, start);
        if (offset == 0 && frame.size <= 0) {
            frame.setSize(frameSize);
            keepToBudget(frameNum);
        }
        frame.put(offset, buf, dataOffset, length);
        return finish(frameNum, frame, keyFrame);
    }

    /**
     * The parity packet of a frame (bad coverage mode, phone app 2.3.0-pc.15): rebuilds a single lost packet of it.
     * @return the frame, if that made it whole (or it was waiting for this), else null
     */
    public Frame putParity(boolean keyFrame, short frameNum, int frameSize, byte[] buf, int dataOffset, int length) {
        return putParity(keyFrame, frameNum, frameSize, buf, dataOffset, length, 0);
    }

    public Frame putParity(boolean keyFrame, short frameNum, int frameSize, byte[] buf, int dataOffset, int length, long now) {
        this.now = now;
        if (!parityWanted(frameNum, frameSize, length)) return null;
        Pending frame = parityFor(frameNum, keyFrame, frameSize);
        frame.parity = Arrays.copyOfRange(buf, dataOffset, dataOffset + length);
        if (frame.recover()) recoveredFrames++;
        return frame.received > 0 ? finish(frameNum, frame, keyFrame) : null;
    }

    /**
     * The parity of one group of a frame's packets (bad coverage mode, phone app 2.3.0-pc.26, see PARITY_GROUP):
     * rebuilds a single lost packet of that group.
     * @return the frame, if that made it whole (or it was waiting for this), else null
     */
    public Frame putParityGroup(boolean keyFrame, short frameNum, int frameSize, int group, byte[] buf, int dataOffset, int length, long now) {
        this.now = now;
        if (group < 0 || group > 255 || !parityWanted(frameNum, frameSize, length)) return null;
        Pending frame = parityFor(frameNum, keyFrame, frameSize);
        int groups = Math.max(group + 1, (frame.packets() + PARITY_GROUP - 1) / PARITY_GROUP);
        if (frame.groupParity == null) frame.groupParity = new byte[groups][];
        else if (frame.groupParity.length < groups) frame.groupParity = Arrays.copyOf(frame.groupParity, groups);
        frame.groupParity[group] = Arrays.copyOfRange(buf, dataOffset, dataOffset + length);
        if (frame.recover()) recoveredFrames++;
        return frame.received > 0 ? finish(frameNum, frame, keyFrame) : null;
    }

    /** A frame at or before the last one released: never put together again. */
    private boolean releasedAlready(short frameNum) {
        return lastReleased != null && (frameNum == lastReleased || isOlder(frameNum, lastReleased));
    }

    /**
     * The opening checks of a parity packet (either kind): a plausible size, a frame not released already and not too
     * far ahead. Forgets an old order first, as every put does.
     */
    private boolean parityWanted(short frameNum, int frameSize, int length) {
        if (frameSize <= 0 || frameSize > PLAUSIBLE_FRAME_BYTES || length <= 0) return false;
        forgetOldOrder();
        return !releasedAlready(frameNum) && !tooFarAhead(frameNum);
    }

    /** The frame a parity packet is for, its size set from the packet if no fragment told it yet. */
    private Pending parityFor(short frameNum, boolean keyFrame, int frameSize) {
        Pending frame = pending(frameNum, keyFrame, isStreamStart(keyFrame, frameNum));
        if (frame.size <= 0) {
            frame.setSize(frameSize);
            keepToBudget(frameNum);
        }
        return frame;
    }

    private Pending pending(short frameNum, boolean keyFrame, boolean streamStart) {
        Pending frame = pending.get(frameNum);
        if (frame == null) {
            if (pending.size() >= MAX_PENDING) dropOldest();
            frame = new Pending(keyFrame, streamStart);
            pending.put(frameNum, frame);
        }
        return frame;
    }

    /**
     * A key frame numbered 1 that isn't under way yet is the first frame of a (re)started stream - unless the last
     * frame released sits right under the top of the counter: then it is the counter wrapping (phone apps before
     * pc.26 wrap to 1; pc.26 wraps to 2).
     */
    private boolean isStreamStart(boolean keyFrame, short frameNum) {
        return keyFrame && frameNum == 1 && !pending.containsKey(frameNum)
                && (lastReleased == null || !isOlder(lastReleased, (short) 1));
    }

    /**
     * A fragment of the key frame 1 released a few frames ago, come late or twice (4G reorders and doubles): not a
     * restart. Before, it cleared what was pending and released frame 1 again after frame N - decoded out of turn, the
     * pictures after it smeared until the next key frame, unmarked. Told apart by what was released: the same bytes
     * at the same place (and at offset 0 the same size), or a gap the released frame was zero-filled at. A new stream's
     * key frame 1 differs there (two key frames are never the same bytes: the picture, and the slice header's id).
     */
    private boolean isFrameOneAgain(int offset, int frameSize, byte[] buf, int dataOffset, int length) {
        byte[] one = frameOne;
        if (one == null || lastReleased == null || !isOlder((short) 1, lastReleased)) return false;
        if (offset == 0 && frameSize != one.length) return false;
        if (offset + length > one.length) return false;
        boolean same = true, zero = true;
        for (int i = 0; i < length && (same || zero); i++) {
            byte b = one[offset + i];
            if (b != buf[dataOffset + i]) same = false;
            if (b != 0) zero = false;
        }
        return same || zero;
    }

    /** No frame released for ORDER_RESET_MS: nothing counts as older any more (see ORDER_RESET_MS). */
    private void forgetOldOrder() {
        if (lastReleased != null && now > 0 && lastReleasedAt > 0 && now - lastReleasedAt > ORDER_RESET_MS) {
            lastReleased = null;
            lastAccounted = null; // a blackout: the frames in it are not counted one by one (the gap asks for a key frame)
            frameOne = null;
        }
    }

    /**
     * Far ahead of the last frame released a moment ago - further than the phone can have numbered since (see
     * MAX_FPS): a late frame of the stream before a restart, dropped. Only with times (the receive path's puts).
     */
    private boolean tooFarAhead(short frameNum) {
        if (lastReleased == null || now <= 0 || lastReleasedAt <= 0) return false;
        int ahead = (frameNum - lastReleased + 32768) % 32768;
        boolean nearOldStream = now < oldStreamUntil && Math.min((frameNum - oldStreamLast + 32768) % 32768,
                (oldStreamLast - frameNum + 32768) % 32768) < AHEAD_SLACK;
        long since = Math.max(0, now - lastReleasedAt);
        return ahead > (nearOldStream ? AHEAD_NEAR_OLD_STREAM : AHEAD_SLACK) + since * MAX_FPS / 1000;
    }

    /** The newest frame released (and when; a key frame 1 is kept, see frameOne); the numbers skipped are lost. */
    private void released(short frameNum, boolean keyFrame, byte[] data) {
        account(frameNum, false);
        lastReleased = frameNum;
        lastReleasedAt = now;
        if (keyFrame && frameNum == 1) frameOne = data;
    }

    /**
     * Frame frameNum is released (lost = false) or lost: the numbers between the last one accounted for and it never
     * came - lost, each. Nothing is counted twice: a number at or before the last one accounted for already was.
     */
    private void account(short frameNum, boolean lost) {
        if (lastAccounted == null) {
            if (lost) lostFrames++;
            lastAccounted = frameNum;
            return;
        }
        if (!isOlder(lastAccounted, frameNum)) return; // counted already (or out of the window: unknown)
        lostFrames += skipped(lastAccounted, frameNum) + (lost ? 1 : 0);
        lastAccounted = frameNum;
    }

    /**
     * How many frame numbers lie between a and b (a older): the phone counts 1..32767 and wraps to 2 (pc.26; older
     * apps to 1), so 0 and 1 are never in between when the counter wrapped.
     */
    static int skipped(short a, short b) {
        int between = (b - a + 32768) % 32768 - 1;
        if (b < a) between -= b > 1 ? 2 : 1; // wrapped: past 0 (and 1)
        return Math.max(0, between);
    }

    private Frame finish(short frameNum, Pending frame, boolean keyFrame) {
        if (!frame.complete() && (frame.parity != null || frame.groupParity != null) && frame.recover()) recoveredFrames++;
        if (!frame.complete() && !(frame.startReceived && frame.endReceived)) return null;
        // bad coverage mode: an incomplete frame waits for its parity (right behind it) or a key frame's second copy;
        // if neither makes it whole, it goes out damaged when the next frame completes (retireOlder)
        if (!frame.complete() && waitForWholeKeyFrames && (frame.keyFrame || !frame.parityIn())) return null;
        pending.remove(frameNum);
        retireOlder(frameNum, keyFrame);
        boolean damaged = !frame.complete();
        if (damaged) damagedFrames++; else completedFrames++;
        Frame out = release(frame, frameNum, damaged);
        released(frameNum, frame.keyFrame, out.data());
        return out;
    }

    private Frame release(Pending frame, short frameNum, boolean damaged) {
        return new Frame(frame.assemble(), frame.keyFrame, frameNum, damaged, frame.streamStart, now);
    }

    /**
     * Frames older than frameNum can't wait any longer. A key frame needs nothing before it: they are lost. Before
     * any other frame, those whose beginning is in go out first, oldest first, damaged (see
     * {@link #takeFlushed}): the frames after them need them as reference. The rest are lost.
     */
    private void retireOlder(short frameNum, boolean keyFrame) {
        if (pending.isEmpty()) return;
        List<Short> older = new ArrayList<>();
        for (short n : pending.keySet()) if (isOlder(n, frameNum)) older.add(n);
        // oldest first: the furthest behind frameNum (wrap-around safe)
        older.sort((a, b) -> Integer.compare((frameNum - b + 32768) % 32768, (frameNum - a + 32768) % 32768));
        for (short n : older) {
            Pending p = pending.remove(n);
            if (!keyFrame && p.startReceived && p.size > 0) {
                damagedFrames++;
                Frame out = release(p, n, true);
                released(n, p.keyFrame, out.data());
                flushed.add(out);
            } else {
                account(n, true);
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

    /** What the frames being put together hold now, bytes (tests). */
    long pendingBytes() {
        long held = 0;
        for (Pending p : pending.values()) held += p.data.length;
        return held;
    }

    /** Over PENDING_BUDGET in all: the oldest frames (not this one) go, lost. */
    private void keepToBudget(short frameNum) {
        while (pending.size() > 1) {
            long held = 0;
            for (Pending p : pending.values()) held += p.data.length;
            if (held <= PENDING_BUDGET) return;
            Short oldest = null;
            for (short n : pending.keySet()) if (n != frameNum && (oldest == null || isOlder(n, oldest))) oldest = n;
            if (oldest == null) return;
            pending.remove(oldest);
            account(oldest, true);
        }
    }

    private void dropOldest() {
        Short oldest = null;
        for (short n : pending.keySet()) {
            if (oldest == null || isOlder(n, oldest)) oldest = n;
        }
        if (oldest != null) {
            pending.remove(oldest);
            account(oldest, true);
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
        lastAccounted = null;
        frameOne = null;
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
