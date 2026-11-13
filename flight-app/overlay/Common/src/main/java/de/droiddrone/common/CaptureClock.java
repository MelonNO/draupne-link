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

/**
 * DRAUPNE Flight: how old a picture is when its first packet leaves the phone - the phone's part of the pilot's
 * glass-to-glass figure (the PC's GlassToGlass adds the network and its own part). Pure: the phone hands it times it
 * read itself, the PC's tests check it.
 * <p>
 * The one time a frame carries from the camera to the sender is its presentation time: the camera stamps the picture
 * it puts on the encoder's input surface, and the encoder gives that stamp back with the encoded frame
 * (BufferInfo.presentationTimeUs). Which clock that stamp is on is the camera's business: Camera2 stamps with the
 * start of the exposure on elapsedRealtime or on a base of its own (SENSOR_INFO_TIMESTAMP_SOURCE), Android moves a
 * camera's elapsedRealtime stamps to the uptime clock on their way into a video encoder - so the encoder's stamp need
 * not even be the SENSOR_TIMESTAMP the capture callback saw - and a USB camera's pictures are stamped on the uptime
 * clock when they are queued (all of this from Android's sources, none of it measured on a phone yet). So the stamp
 * is tried, not trusted:
 * <ol>
 * <li>Against the phone's two clocks: the age of the picture is the send time minus the stamp, on the clock on which
 * that is not negative and no more than {@link #PLAUSIBLE_NS}; on both, the smaller (they differ by the time the
 * phone has slept since it started: on the wrong one the picture is from the future, or that much older). This is
 * exact - from the start of the exposure where the camera stamps that.</li>
 * <li>On neither - a base of the camera's own: from the {@link #stamped} pairs. Each says "a picture with this stamp
 * was here at this time"; the smallest difference of the last two {@link #BUCKET_NS} is the stamp's base against the
 * send clock plus the quickest the callback ever came, and the age is counted from there. It reads short by that
 * quickest callback - an exposure and a few milliseconds.</li>
 * <li>Otherwise there is no figure ({@link #UNKNOWN}), and the PC shows none.</li>
 * </ol>
 * What the phone stamps where (the line numbers are those of this branch):
 * <ul>
 * <li>InternalCamera.captureCallback.onCaptureStarted (InternalCamera.java:950), every picture:
 * {@code stamped(timestamp / 1000, SystemClock.elapsedRealtimeNanos())}. A USB camera has no such callback: its
 * pictures are on the uptime clock (rule 1), their age counted from when the picture was handed to the encoder - what
 * the camera and its cable took before that is not in the figure.</li>
 * <li>StreamEncoder.onOutputBufferAvailable (StreamEncoder.java:583) already keeps the BufferInfo with the frame
 * (line 598): Udp.videoSenderRun (Udp.java:2095) passes {@code buf.info.presentationTimeUs} on with the frame.</li>
 * <li>Udp.sendFragments (Udp.java:2347), before the frame's first packet (a key frame's first copy only):
 * {@code captureToSendMs(presentationTimeUs, SystemClock.elapsedRealtimeNanos(), System.nanoTime())} and
 * {@link PingEcho#at} with the same elapsedRealtimeNanos, sent as FRAME_TIME.</li>
 * <li>{@link #restart} where the stream starts (Udp.java:1045, the frame count set to 0): another camera may stamp on
 * another base.</li>
 * <li>Udp.receiverRun, a Ping taken (before buffer.addPacket, Udp.java:625; [10][toEndPoint u8][time i64], so the
 * stamp's low half is Bytes.readInt(d, 6)): {@link PingEcho#received} with SystemClock.elapsedRealtimeNanos();
 * {@link PingEcho#restart} with each new session (Udp.newSession).</li>
 * </ul>
 * The wire, as planned: {@code VIDEO_PROFILE} bit 3 (8) = the PC wants frame times; then {@code FRAME_TIME [0x53]
 * [frameNum i16][captureToSendMs u16][pingEcho u32][pingHoldMs u16]} right before each frame's first packet,
 * unnumbered, 11 bytes; 65535 in either u16 = not known. pingEcho is the low 32 bits of the stamp of the last Ping the
 * phone took (the PC's own clock, UdpSender.pingClockMs), pingHoldMs how long ago that was when this packet left.
 * <p>
 * {@link #stamped} comes from the camera's thread, {@link #captureToSendMs} from the video sender's: synchronized.
 */
public final class CaptureClock {
    /** No figure; on the wire as it is. */
    public static final int UNKNOWN = 0xFFFF;
    /** The most a figure says, ms. */
    public static final int MAX_MS = 0xFFFE;
    /** A picture older than this when it is sent isn't on the clock it was tried against (the encoder's queue holds 30 frames). */
    public static final long PLAUSIBLE_NS = 10_000_000_000L;
    /** The stamped pairs: the smallest difference of this long, and of as long before it. */
    public static final long BUCKET_NS = 10_000_000_000L;
    /** From the pairs, a picture may seem this much younger than the quickest callback allows before it counts as off. */
    static final long EARLY_NS = 50_000_000L;
    /** Where the last figure came from ({@link #source}). */
    public static final int NONE = 0, SEND_CLOCK = 1, OTHER_CLOCK = 2, STAMPS = 3;

    /** The smallest (time - stamp) of the pairs in this bucket and in the one before, ns. */
    private long offsetNow, offsetBefore;
    private boolean hasNow, hasBefore, bucketOpen;
    private long bucketAt;
    private volatile int source;

    /**
     * A picture with this presentation time was at the camera (or the encoder's input) at monoNs, on the clock the
     * send time is read on.
     */
    public synchronized void stamped(long presentationTimeUs, long monoNs) {
        roll(monoNs);
        long offset = monoNs - presentationTimeUs * 1000;
        if (!hasNow || offset - offsetNow < 0) {
            offsetNow = offset;
            hasNow = true;
        }
    }

    /** The bucket is full: it becomes the one before; nothing stamped for two of them and both are forgotten. */
    private void roll(long nowNs) {
        if (!bucketOpen) {
            bucketOpen = true;
            bucketAt = nowNs;
            return;
        }
        long age = nowNs - bucketAt;
        if (age < BUCKET_NS) return;
        hasBefore = hasNow && age < 2 * BUCKET_NS;
        offsetBefore = offsetNow;
        hasNow = false;
        bucketAt = nowNs;
    }

    /**
     * The milliseconds from the picture's capture to now, as its first packet is sent: 0..{@link #MAX_MS}, or
     * {@link #UNKNOWN}.
     * @param presentationTimeUs the encoded frame's stamp
     * @param sendNs             now, on the phone's own clock (elapsedRealtimeNanos; the one {@link #stamped} gets)
     * @param otherClockNs       now, on the other clock a stamp may be on (System.nanoTime: the uptime clock)
     */
    public synchronized int captureToSendMs(long presentationTimeUs, long sendNs, long otherClockNs) {
        long stampNs = presentationTimeUs * 1000;
        long age = sendNs - stampNs, other = otherClockNs - stampNs;
        boolean fits = age >= 0 && age <= PLAUSIBLE_NS, otherFits = other >= 0 && other <= PLAUSIBLE_NS;
        int from = SEND_CLOCK;
        if (otherFits && (!fits || other < age)) {
            age = other;
            from = OTHER_CLOCK;
            fits = true;
        }
        if (!fits) {
            roll(sendNs);
            if (hasNow || hasBefore) {
                long offset = !hasBefore || hasNow && offsetNow - offsetBefore < 0 ? offsetNow : offsetBefore;
                long sinceStamp = sendNs - stampNs - offset;
                if (sinceStamp >= -EARLY_NS && sinceStamp <= PLAUSIBLE_NS) {
                    age = Math.max(0, sinceStamp);
                    from = STAMPS;
                    fits = true;
                }
            }
        }
        source = fits ? from : NONE;
        if (!fits) return UNKNOWN;
        long ms = (age + 500_000) / 1_000_000;
        return ms > MAX_MS ? MAX_MS : (int) ms;
    }

    /** Where the last figure came from: {@link #SEND_CLOCK}, {@link #OTHER_CLOCK}, {@link #STAMPS}, or {@link #NONE} (for the phone's log, once per change). */
    public int source() {
        return source;
    }

    /** The stream starts (again): what the pairs said is forgotten. */
    public synchronized void restart() {
        hasNow = hasBefore = bucketOpen = false;
        source = NONE;
    }

    /**
     * The Ping a frame answers: the stamp of the last Ping the phone took and how long it has held it. The PC knows
     * when it stamped that Ping and when the frame came: the two ways together, less the hold (GlassToGlass). Pure,
     * as the clock above; {@link #received} on the receive thread, {@link #at} on the video sender's.
     */
    public static final class PingEcho {
        /** A Ping stamped earlier than the one held, come after it, is left out - unless the one held is this old (another PC's clock). */
        static final long REORDER_NS = 1_000_000_000L;
        private long stamp, atNs;
        private boolean any;

        /**
         * A Ping from the PC came at nowNs (the clock the send time is read on).
         * @param pingStampMs the Ping's time (its low 32 bits count)
         */
        public synchronized void received(long pingStampMs, long nowNs) {
            long low = pingStampMs & 0xFFFFFFFFL;
            if (any && (int) (low - stamp) < 0 && nowNs - atNs < REORDER_NS) return; // overtaken on the way: its lateness would count as the frame's
            stamp = low;
            atNs = nowNs;
            any = true;
        }

        /** A new session: its PC's clock may be another. */
        public synchronized void restart() {
            any = false;
        }

        /**
         * For a frame leaving at nowNs, both in one: {@link #echo} and {@link #holdMs} take it apart. No Ping yet, or
         * the last one held longer than {@link CaptureClock#MAX_MS}: the hold says {@link CaptureClock#UNKNOWN}.
         */
        public synchronized long at(long nowNs) {
            if (!any) return UNKNOWN;
            long held = (nowNs - atNs + 500_000) / 1_000_000;
            return stamp << 16 | (held < 0 ? 0 : held > MAX_MS ? UNKNOWN : held);
        }

        /** FRAME_TIME's pingEcho: the Ping's stamp, 32 bits. */
        public static long echo(long at) {
            return at >>> 16;
        }

        /** FRAME_TIME's pingHoldMs: 0..65534, or 65535 = not known. */
        public static int holdMs(long at) {
            return (int) (at & 0xFFFF);
        }
    }
}
