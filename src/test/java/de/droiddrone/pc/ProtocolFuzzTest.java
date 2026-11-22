package de.droiddrone.pc;

import de.droiddrone.common.ArmRanges;
import de.droiddrone.common.Bytes;
import de.droiddrone.common.DataReader;
import de.droiddrone.common.DetectionAreas;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.Fec;
import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.Observe;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PacketIds;
import de.droiddrone.common.PcAudio;
import de.droiddrone.common.RcPath;
import de.droiddrone.common.SessionGate;
import de.droiddrone.common.SleepWake;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.audio.AudioConfig;
import de.droiddrone.pc.video.FrameAssembler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every parser that reads a datagram from the network (any Tailscale peer can send one; a phone's can arrive cut
 * short) on random bytes and on every cut of a valid packet: it answers null / -1 / "not one", never throws. A throw
 * on a receive thread drops the packets behind it, or ends the thread.
 */
class ProtocolFuzzTest {
    static final String KEY = "key";
    private static final SessionGate GATE = new SessionGate(KEY);

    /** The parsers, each given (bytes, length). */
    static List<Object[]> parsers() {
        List<Object[]> p = new ArrayList<>();
        add(p, "Names.keyOf", (d, n) -> Names.keyOf(d, n));
        add(p, "Names.parseReply", (d, n) -> Names.parseReply(d, n, KEY));
        add(p, "Names.parseAssign", (d, n) -> Names.parseAssign(d, n, KEY));
        add(p, "Spectate.readPilotView", (d, n) -> Spectate.readPilotView(d, n));
        add(p, "Spectate.readKey", (d, n) -> Spectate.readKey(d, n));
        add(p, "Spectate.helloFlags", (d, n) -> Spectate.helloFlags(d, n));
        add(p, "Spectate.readPilotAt", (d, n) -> Spectate.readPilotAt(d, n));
        add(p, "Spectate.readPort", (d, n) -> Spectate.readPort(d, n));
        add(p, "Observe.decodeRequest", (d, n) -> Observe.decodeRequest(d, n));
        add(p, "Observe.keyOf", (d, n) -> Observe.keyOf(d, n));
        add(p, "Observe.decodeState", (d, n) -> Observe.decodeState(d, n));
        add(p, "PcAudio.decode", (d, n) -> PcAudio.decode(d, n));
        add(p, "FleetProtocol.parseRequest", (d, n) -> FleetProtocol.parseRequest(d, n, KEY));
        add(p, "FleetProtocol.Where.read", (d, n) -> FleetProtocol.Where.read(d, Math.min(1, n), n, KEY));
        add(p, "FleetProtocol.Status.read", (d, n) -> FleetProtocol.Status.read(d, Math.min(1, n), n));
        add(p, "OverwatchProtocol.isFromPhone", (d, n) -> OverwatchProtocol.isFromPhone(d, n));
        add(p, "OverwatchProtocol.readAlert", (d, n) -> OverwatchProtocol.readAlert(d, n));
        add(p, "OverwatchProtocol.readCommand", (d, n) -> OverwatchProtocol.readCommand(d, n));
        add(p, "OverwatchProtocol.readFragment", (d, n) -> OverwatchProtocol.readFragment(d, n));
        add(p, "OverwatchProtocol.clipType", (d, n) -> OverwatchProtocol.clipType(d, n));
        add(p, "OverwatchProtocol.readClipInfo", (d, n) -> OverwatchProtocol.readClipInfo(d, n));
        add(p, "OverwatchProtocol.readClipEnded", (d, n) -> OverwatchProtocol.readClipEnded(d, n));
        add(p, "OverwatchProtocol.readClipNone", (d, n) -> OverwatchProtocol.readClipNone(d, n));
        add(p, "OverwatchProtocol.readStatus", (d, n) -> OverwatchProtocol.readStatus(d, n));
        add(p, "OverwatchProtocol.readRequestTail", (d, n) -> OverwatchProtocol.readRequestTail(d, Math.min(1, n), n));
        add(p, "ArmRanges.parse", (d, n) -> ArmRanges.parse(java.util.Arrays.copyOf(d, n)));
        add(p, "AudioConfig.parse", (d, n) -> AudioConfig.parse(d, Math.min(3, n), Math.max(0, n - 3)));
        add(p, "ControlLink.SleepHello.read", (d, n) -> {
            if (n >= 6) ControlLink.SleepHello.read(d, n, 0); // the receive thread checks the 6 first
            return null;
        });
        // the sticks, the session, the video's packets, the telemetry's records, the detection areas (2026-11-22);
        // each from nothing here - the tests further down run one of each over a long stream, where what it took before counts
        add(p, "RcPath.take", (d, n) -> new RcPath().take(d, n, 1000));
        add(p, "RcPath.idleFrame", (d, n) -> RcPath.idleFrame(d, n));
        add(p, "SessionGate.keyMatches", (d, n) -> new SessionGate(KEY).keyMatches(d, n, 4) | new SessionGate(KEY).keyMatches(d, n, 1));
        add(p, "SessionGate.sessionId", (d, n) -> SessionGate.sessionId(d, n));
        add(p, "SessionGate.answeredById", (d, n) -> SessionGate.answeredById(d, n));
        add(p, "SessionGate.markAnswer", (d, n) -> {
            SessionGate.markAnswer(d.clone(), n);
            return null;
        });
        // the gate's three verdicts read the packet's first byte: both receive threads that ask it (the phone's Udp,
        // VirtualDrone) drop an empty datagram first, so it is never asked about one
        add(p, "SessionGate.fromPilot", (d, n) -> n < 1 ? null : new SessionGate(KEY).fromPilot(d, n, 1000));
        add(p, "SessionGate.fromPilot by id", (d, n) -> n < 1 ? null : new SessionGate(KEY).fromPilot(d, n, 1000, SessionGate.sessionId(d, n)));
        add(p, "SessionGate.fromOther", (d, n) -> n < 1 ? null : new SessionGate(KEY).fromOther(d, n, true, true, 1000));
        add(p, "SessionGate.fromOther by id", (d, n) -> n < 1 ? null : new SessionGate(KEY).fromOther(d, n, false, true, 1000, SessionGate.sessionId(d, n)));
        add(p, "SessionGate.noSession", (d, n) -> n < 1 ? null : new SessionGate(KEY).noSession(d, n, true, false, true, () -> true, 1000));
        add(p, "SessionGate.noSession by id", (d, n) -> n < 1 ? null
                : new SessionGate(KEY).noSession(d, n, false, false, false, () -> false, 1000, SessionGate.sessionId(d, n)));
        add(p, "Fec.valid", (d, n) -> Fec.valid(d, n));
        add(p, "ControlLink.deliverVideo", (d, n) -> {
            ControlLink.deliverVideo(into(new FrameAssembler(), new long[]{1000}), d, n);
            return null;
        });
        add(p, "FcTelemetry.parse", (d, n) -> {
            for (FcInfo fc : FLIGHT_CONTROLLERS) new FcTelemetry().parse(new DataReader(Arrays.copyOf(d, n), true), fc);
            return null;
        });
        add(p, "DetectionAreas.read", (d, n) -> DetectionAreas.read(new DataReader(Arrays.copyOf(d, n), true)));
        add(p, "DetectionAreas.parse", (d, n) -> DetectionAreas.parse(new String(d, 0, n, StandardCharsets.ISO_8859_1)));
        // the phone's receive thread, listening asleep: whether a packet keeps it awake (its key read first)
        add(p, "SleepWake.holdsAwake", (d, n) -> SleepWake.holdsAwake(d, n, GATE, true));
        return p;
    }

    /** No flight controller known yet, a Betaflight one, an INAV one: the telemetry's records read differently for each. */
    static final FcInfo[] FLIGHT_CONTROLLERS = {null, new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 5, 1, 0, 1, 46, 0),
            new FcInfo(FcInfo.FC_VARIANT_INAV, 7, 1, 0, 0, 2, 5, 0)};

    /** A link's video packets into a frame assembler, as the video pipeline hands them on; clock[0] is the time. */
    static ControlLink.VideoListener into(FrameAssembler a, long[] clock) {
        return new ControlLink.VideoListener() {
            @Override
            public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
            }

            @Override
            public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
                a.put(keyFrame, frameNum, offset, frameSize, buf, dataOffset, length, clock[0]);
                a.takeFlushed();
            }

            @Override
            public void onVideoParity(boolean keyFrame, short frameNum, int frameSize, byte[] buf, int dataOffset, int length) {
                a.putParity(keyFrame, frameNum, frameSize, buf, dataOffset, length, clock[0]);
                a.takeFlushed();
            }

            @Override
            public void onVideoParityGroup(boolean keyFrame, short frameNum, int frameSize, int group, byte[] buf, int dataOffset, int length) {
                a.putParityGroup(keyFrame, frameNum, frameSize, group, buf, dataOffset, length, clock[0]);
                a.takeFlushed();
            }

            @Override
            public void onVideoParityRs(byte[] d, int len) {
                a.putParityRs(d, len, clock[0]);
                a.takeFlushed();
            }
        };
    }

    interface Parser {
        Object parse(byte[] d, int len) throws Exception;
    }

    static void add(List<Object[]> p, String name, Parser f) {
        p.add(new Object[]{name, f});
    }

    /** Runs every parser over the input; the failures by name. */
    static void feed(byte[] d, int len, Consumer<String> failed) {
        for (Object[] p : parsers()) {
            try {
                ((Parser) p[1]).parse(d, len);
            } catch (Throwable t) {
                failed.accept(p[0] + " on " + len + " bytes [" + (len > 0 ? d[0] : "") + "...]: " + t);
            }
        }
    }

    @Test
    void randomDatagramsAreRefusedWithoutAThrow() {
        Random r = new Random(1);
        List<String> failures = new ArrayList<>();
        byte[] codes = {Names.NAME_QUERY, Names.NAME_REPLY, Names.NAME_ASSIGN, Spectate.PILOT_AT, Spectate.PILOT_VIEW,
                Spectate.HELLO, OverwatchProtocol.ALERT, OverwatchProtocol.PREVIEW, OverwatchProtocol.CLIP,
                OverwatchProtocol.STATUS, FleetProtocol.MODE, PcAudio.PACKET, 0x50, 0x51, 0x52, 0, (byte) 0xff,
                UdpCommon.Connect, UdpCommon.RcFrame, ControlLink.IDLE_STICKS, UdpCommon.VideoFrame, UdpCommon.KeyFrame,
                ControlLink.VIDEO_PARITY, ControlLink.VIDEO_PARITY_GROUP, ControlLink.VIDEO_PARITY_RS,
                PacketIds.SLEEP_ANSWER, PacketIds.SPECTATE_PILOT_QUERY};
        for (int i = 0; i < 20_000; i++) {
            byte[] d = new byte[r.nextInt(80)];
            r.nextBytes(d);
            if (d.length > 0 && r.nextBoolean()) d[0] = codes[r.nextInt(codes.length)];
            if (d.length > 2 && r.nextBoolean()) { // a plausible string length, so the reads get further in
                d[1] = 0;
                d[2] = (byte) Math.min(d.length - 3, KEY.length());
                if (d[2] == KEY.length()) System.arraycopy(KEY.getBytes(), 0, d, 3, KEY.length());
            } else if (d.length > 5 && r.nextInt(4) == 0) { // the same where a Connect has its key
                d[4] = 0;
                d[5] = (byte) Math.min(d.length - 6, KEY.length());
                if (d[5] == KEY.length()) System.arraycopy(KEY.getBytes(), 0, d, 6, KEY.length());
            } else if (d.length > 1 && r.nextInt(4) == 0) { // a count of channels (or of areas) that could be one
                d[1] = (byte) r.nextInt(20);
            }
            feed(d, d.length, failures::add);
            if (failures.size() > 20) break;
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void everyCutOfAValidPacketIsRefusedWithoutAThrow() throws Exception {
        List<byte[]> valid = new ArrayList<>();
        valid.add(Spectate.pilotView(new ControlLink.PilotView(true, true, false, false, true, false, "THROTTLE DOWN",
                "LINK BACK", "hint", new short[]{1500, 1500, 1000, 1500, 1000, 1000, 1000, 1000}, "MobileAlpha", true)));
        valid.add(Spectate.pilotAt(java.net.InetAddress.getByName("::1"), 6286));
        valid.add(Spectate.keyed(Spectate.HELLO, KEY));
        valid.add(Spectate.hello(KEY, Spectate.HELLO_MENDS_RS));
        valid.add(Observe.encodeStop(KEY));
        valid.add(Observe.encodeRequest(KEY, 640, 360, 15, 800, 3, 2));
        valid.add(FleetProtocol.request(KEY, new FleetProtocol.Request(7, 3, 1, 2, 0, 9, 6286)));
        valid.add(Observe.encodeState(1, 1280, 720, 30, 2));
        valid.add(PcAudio.encode(true, 32, true, true));
        valid.add(connect(KEY, 0x12345678L, true));
        valid.add(connect(KEY, -1, false));
        for (int tail : new int[]{0, 1, 2, 5}) valid.add(sticks(8, tail, 7, 300, 20));
        valid.add(sticks(16, 5, 65535, 65535, 255));
        byte[] idle = sticks(8, 0, 0, 0, 0);
        idle[0] = ControlLink.IDLE_STICKS;
        valid.add(idle);
        Random r = new Random(5);
        for (int kind = 0; kind < 12; kind++) {
            byte[] v = new byte[UdpCommon.packetLength + 64];
            valid.add(Arrays.copyOf(v, videoPacket(r, v, 100, kind)));
        }
        valid.add(DetectionAreas.of(List.of(
                new DetectionAreas.Area(0.1f, 0.2f, 0.3f, 0.4f, 2), new DetectionAreas.Area(0.5f, 0.5f, 0.2f, 0.2f, 0))).encode());
        valid.add(DetectionAreas.of(List.of(new DetectionAreas.Area(0.1f, 0.2f, 0.3f, 0.4f, 3))).toText().getBytes(StandardCharsets.ISO_8859_1));
        valid.add(telemetry(r, telemetryCodes(), 6));
        List<String> failures = new ArrayList<>();
        for (byte[] v : valid) {
            for (int n = 0; n <= v.length; n++) {
                byte[] cut = java.util.Arrays.copyOf(v, n);
                feed(cut, n, failures::add);
                feed(v, n, failures::add); // the length short of the array, as a reused receive buffer gives it
            }
        }
        assertEquals(List.of(), failures);
    }

    // ------------------------------------------------------------------ streams (2026-11-22): what was taken before counts

    /** [Connect][num i16][client type][key UTF][version i16], then [sessionId u32] (id under 0: none), then the answer's byte. */
    static byte[] connect(String key, long id, boolean answerByte) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[8 + k.length + (id < 0 ? 0 : 4) + (answerByte ? 1 : 0)];
        d[0] = UdpCommon.Connect;
        d[3] = 1;
        d[4] = (byte) (k.length >> 8);
        d[5] = (byte) k.length;
        System.arraycopy(k, 0, d, 6, k.length);
        d[7 + k.length] = 9;
        if (id >= 0) Bytes.putInt(d, 8 + k.length, (int) id);
        return d;
    }

    /** A stick frame, [24][count][count x i16], and the tail a PC's build adds: none, a byte, two, or five (seq, sent, max lag). */
    static byte[] sticks(int channels, int tail, int seq, int sentMs, int maxLagCs) {
        byte[] d = new byte[2 + channels * 2 + tail];
        d[0] = UdpCommon.RcFrame;
        d[1] = (byte) channels;
        for (int i = 0; i < channels; i++) {
            d[2 + i * 2] = (byte) (1500 >> 8);
            d[3 + i * 2] = (byte) 1500;
        }
        int at = 2 + channels * 2;
        if (tail == 1) d[at] = (byte) seq;
        if (tail >= 2) {
            d[at] = (byte) (seq >> 8);
            d[at + 1] = (byte) seq;
        }
        if (tail >= 5) {
            d[at + 2] = (byte) (sentMs >> 8);
            d[at + 3] = (byte) sentMs;
            d[at + 4] = (byte) maxLagCs;
        }
        return d;
    }

    /** The size the made-up phone gives frame number f: a few packets mostly, now and then a large one. */
    private static int sizeOf(int f) {
        return f % 13 == 0 ? 1 + (f * 7919) % FrameAssembler.PLAUSIBLE_FRAME_BYTES : 1 + (f * 7919) % 40_000;
    }

    private static final int FIRST = UdpCommon.packetLength - 13, NEXT = UdpCommon.packetLength - 9;

    /**
     * One video packet into d (the receive buffer, random bytes first): a fragment as the phone cuts it, one out of
     * place, one far into a frame whose start has not come, each of the three parities well formed for its frame or
     * not, or only a first byte that says video. @return its length
     */
    static int videoPacket(Random r, byte[] d, int base, int kind) {
        r.nextBytes(d);
        int f = r.nextInt(8) == 0 ? r.nextInt(65536) : 1 + Math.floorMod(base + r.nextInt(12) - 3, 32767);
        if (r.nextInt(60) == 0) f = 1;
        boolean key = r.nextInt(6) == 0;
        int size = r.nextInt(5) == 0 ? switch (r.nextInt(4)) {
            case 0 -> r.nextInt();
            case 1 -> FrameAssembler.PLAUSIBLE_FRAME_BYTES - r.nextInt(3);
            case 2 -> FrameAssembler.PLAUSIBLE_FRAME_BYTES + 1 + r.nextInt(1000);
            default -> 1 + r.nextInt(2000);
        } : sizeOf(f & 0xffff);
        d[1] = (byte) (f >> 8);
        d[2] = (byte) f;
        int packets = Math.max(1, Fec.fragments(Math.max(1, Math.min(size, FrameAssembler.PLAUSIBLE_FRAME_BYTES))));
        switch (kind) {
            case 0, 1, 2, 3 -> { // a fragment as the phone cuts it
                int i = r.nextInt(packets), offset = i == 0 ? 0 : FIRST + (i - 1) * NEXT;
                d[0] = key ? UdpCommon.KeyFrame : UdpCommon.VideoFrame;
                Bytes.putInt(d, 3, offset);
                if (offset == 0) Bytes.putInt(d, 7, size);
                return (offset == 0 ? 11 : 7) + Math.max(0, Math.min(i == 0 ? FIRST : NEXT, size - offset));
            }
            case 4 -> { // a fragment anywhere, of any length the buffer has
                d[0] = key ? UdpCommon.KeyFrame : UdpCommon.VideoFrame;
                int offset = r.nextInt(4) == 0 ? r.nextInt() : r.nextInt(FrameAssembler.PLAUSIBLE_FRAME_BYTES + 4000);
                Bytes.putInt(d, 3, offset);
                if (offset == 0) Bytes.putInt(d, 7, size);
                return r.nextInt(d.length + 1);
            }
            case 5 -> { // the first fragment, whatever size it names, as long as the buffer
                d[0] = key ? UdpCommon.KeyFrame : UdpCommon.VideoFrame;
                Bytes.putInt(d, 3, 0);
                Bytes.putInt(d, 7, size);
                return 11 + r.nextInt(d.length - 10);
            }
            case 6 -> { // far into a frame whose start has not come
                d[0] = UdpCommon.VideoFrame;
                Bytes.putInt(d, 3, FrameAssembler.PLAUSIBLE_FRAME_BYTES - r.nextInt(3 * NEXT));
                return 7 + r.nextInt(d.length - 6);
            }
            case 7 -> { // the parity over a whole frame
                d[0] = ControlLink.VIDEO_PARITY;
                Bytes.putInt(d, 3, size);
                d[7] = (byte) (key ? 1 : 0);
                return 8 + (r.nextInt(4) == 0 ? r.nextInt(d.length - 7) : Math.min(NEXT, d.length - 8));
            }
            case 8 -> { // a group's parity
                d[0] = ControlLink.VIDEO_PARITY_GROUP;
                Bytes.putInt(d, 3, size);
                d[7] = (byte) (key ? 1 : 0);
                d[8] = (byte) (r.nextInt(4) == 0 ? r.nextInt(256) : r.nextInt(1 + (packets + 7) / 8));
                return 9 + (r.nextInt(4) == 0 ? r.nextInt(d.length - 8) : Math.min(NEXT, d.length - 9));
            }
            case 9, 10 -> { // a block's Reed-Solomon parity, its header agreeing with itself
                d[0] = ControlLink.VIDEO_PARITY_RS;
                int s = Math.max(1, Math.min(size, 0xffffff)), n = Fec.fragments(s), blocks = Fec.blocks(n);
                if (blocks == 0) return r.nextInt(d.length + 1);
                int block = r.nextInt(blocks), k = Fec.blockK(n, block), m = 1 + r.nextInt(Fec.MAX_PARITY);
                Fec.putHeader(d, f, s, key, block, k, m, r.nextInt(m));
                return Fec.HEADER + Fec.shardLength(s, Fec.blockFirst(n, block), k);
            }
            default -> { // only its first byte says what it is
                byte[] kinds = {UdpCommon.VideoFrame, UdpCommon.KeyFrame, ControlLink.VIDEO_PARITY, ControlLink.VIDEO_PARITY_GROUP, ControlLink.VIDEO_PARITY_RS};
                d[0] = kinds[r.nextInt(kinds.length)];
                return r.nextInt(d.length + 1);
            }
        }
    }

    /**
     * A frame assembler under a long stream of video packets of every kind - fragments and the three parities, well
     * formed, cut short, out of place, made up: no throw, and what it holds for the frames being put together (their
     * own bytes and the parity kept for them) is inside its budget after every packet.
     */
    @Test
    void aStreamOfVideoPacketsNeverThrowsAndStaysInsideTheAssemblersBudget() {
        for (int seed = 1; seed <= 4; seed++) {
            Random r = new Random(seed);
            FrameAssembler a = new FrameAssembler();
            a.setWaitForWholeKeyFrames(seed % 2 == 0); // bad coverage mode: frames wait for their parity
            long[] clock = {seed == 4 ? 0 : 1000};     // one run without times, as a caller that gives none
            ControlLink.VideoListener to = into(a, clock);
            byte[] d = new byte[UdpCommon.packetLength + 64]; // the receive buffer: used again, the length short of it
            int base = 1 + r.nextInt(30_000);
            long most = 0;
            for (int i = 0; i < 120_000; i++) {
                int len = videoPacket(r, d, base, r.nextInt(12));
                if (r.nextInt(40) == 0) base = 1 + (base + r.nextInt(30)) % 32767; // the stream moves on
                if (clock[0] > 0) clock[0] += r.nextInt(3);
                try {
                    ControlLink.deliverVideo(to, d, len);
                } catch (Throwable t) {
                    throw new AssertionError("seed " + seed + ", packet " + i + ": kind " + d[0] + ", " + len + " bytes, frame "
                            + (((d[1] & 0xff) << 8) | (d[2] & 0xff)) + ", at " + Bytes.readInt(d, 3) + ", size " + Bytes.readInt(d, 7), t);
                }
                long held = a.pendingBytes();
                most = Math.max(most, held);
                if (held > FrameAssembler.PENDING_BUDGET) {
                    throw new AssertionError("seed " + seed + ", packet " + i + ": the frames being put together hold " + held
                            + " bytes, over the budget of " + FrameAssembler.PENDING_BUDGET);
                }
            }
            assertTrue(a.getCompletedFrames() + a.getDamagedFrames() > 500, "frames came out of it: the stream was one, mostly. Seed " + seed
                    + ": " + a.getCompletedFrames() + " whole, " + a.getDamagedFrames() + " damaged, " + a.getRecoveredFrames() + " mended");
            assertTrue(most > 100_000, "and it held frames meanwhile: " + most);
        }
    }

    /**
     * The frames whose start has not come grow inside the same budget: a fragment far into each of 64 frames - before,
     * each got an array of its own up to a whole frame's size, 200 MB in all, and the budget was only looked at when a
     * frame's size came.
     */
    @Test
    void framesWhoseStartHasNotComeGrowInsideTheBudgetToo() {
        FrameAssembler a = new FrameAssembler();
        byte[] part = new byte[UdpCommon.packetLength];
        for (int f = 10; f < 10 + 64; f++) {
            a.put(false, (short) f, FrameAssembler.PLAUSIBLE_FRAME_BYTES - 5000, 0, part, 0, part.length, 1000 + f);
            assertTrue(a.pendingBytes() <= FrameAssembler.PENDING_BUDGET, "after " + (f - 9) + " such frames: " + a.pendingBytes() + " bytes held");
        }
        // the newest of them was still being put together: its first fragment, with the last one in, lets it out
        // (the middle is not waited for)
        int last = 73, size = FrameAssembler.PLAUSIBLE_FRAME_BYTES - 5000 + part.length;
        FrameAssembler.Frame out = a.put(false, (short) last, 0, size, part, 0, FIRST, 2000);
        assertTrue(out != null && out.number() == last && out.data().length == size && out.damaged(), "frame " + last + " came out: " + out);
        assertTrue(a.pendingBytes() <= FrameAssembler.PENDING_BUDGET);
    }

    /**
     * A fragment longer than the phone cuts them, at the far end of a frame whose size is not known yet: taken as
     * far as a frame can reach, no throw. (Found by the stream above: an ArrayIndexOutOfBoundsException.)
     */
    @Test
    void aLongFragmentAtAFramesFarEndIsTakenWithoutAThrow() {
        byte[] d = new byte[UdpCommon.packetLength + 64];
        for (int back = 0; back < 3000; back += 7) {
            FrameAssembler a = new FrameAssembler();
            a.put(false, (short) 20, FrameAssembler.PLAUSIBLE_FRAME_BYTES - back, 0, d, 7, d.length - 7, 1000);
            assertTrue(a.pendingBytes() <= FrameAssembler.PLAUSIBLE_FRAME_BYTES + d.length);
        }
    }

    /**
     * The parity kept for the frames being put together counts to the budget as their own bytes do: every group's
     * parity (and some for groups they do not have) for each of 64 frames whose own packets never come - 18 MB of it
     * before, outside the count.
     */
    @Test
    void parityKeptForManyFramesIsInsideTheBudgetToo() {
        FrameAssembler a = new FrameAssembler();
        a.setWaitForWholeKeyFrames(true);
        byte[] parity = new byte[NEXT];
        long most = 0;
        for (int f = 10; f < 10 + 64; f++) {
            for (int group = 0; group < 256; group++) {
                a.putParityGroup(false, (short) f, FIRST + 15 * NEXT, group, parity, 0, parity.length, 1000 + f); // 16 packets: two groups
                most = Math.max(most, a.pendingBytes());
            }
        }
        assertTrue(most > 4_000_000, "the parity was kept: " + most);
        assertTrue(most <= FrameAssembler.PENDING_BUDGET, "the most held: " + most + " bytes, the budget " + FrameAssembler.PENDING_BUDGET);
    }

    /** One stick path under a long stream of stick frames of every layout, cut, doubled, made up: no throw. */
    @Test
    void aStreamOfStickFramesNeverThrows() {
        Random r = new Random(2);
        RcPath path = new RcPath();
        long now = 1000;
        byte[] buffer = new byte[256];
        for (int i = 0; i < 200_000; i++) {
            now += r.nextInt(50) == 0 ? r.nextInt(20_000) : r.nextInt(40);
            byte[] d = switch (r.nextInt(6)) {
                case 0 -> sticks(4 + r.nextInt(16), new int[]{0, 1, 2, 5}[r.nextInt(4)], r.nextInt(65536), r.nextInt(65536), r.nextInt(256));
                case 1 -> sticks(r.nextInt(40), r.nextInt(8), r.nextInt(65536), r.nextInt(65536), r.nextInt(256));
                case 2 -> sticks(8, 5, i, (int) (now & 0xffff), r.nextInt(4) * 5); // a stream in order, on time
                default -> {
                    byte[] any = new byte[r.nextInt(70)];
                    r.nextBytes(any);
                    if (any.length > 1 && r.nextBoolean()) any[1] = (byte) r.nextInt(24);
                    yield any;
                }
            };
            int len = r.nextInt(5) == 0 ? r.nextInt(d.length + 1) : d.length;
            try {
                int took = r.nextBoolean() ? path.take(d, len, now) : takeFromBuffer(path, buffer, d, len, now);
                if (took > 0) path.taken(now);
                RcPath.idleFrame(d, len);
                path.frame();
                path.lagMs();
                path.takeLagMaxMs();
                if (r.nextInt(5000) == 0) path.restart(now);
            } catch (Throwable t) {
                throw new AssertionError("frame " + i + ": " + len + " of " + d.length + " bytes " + Arrays.toString(Arrays.copyOf(d, Math.min(d.length, 12))), t);
            }
        }
    }

    private static int takeFromBuffer(RcPath path, byte[] buffer, byte[] d, int len, long now) {
        System.arraycopy(d, 0, buffer, 0, Math.min(d.length, buffer.length));
        return path.take(buffer, Math.min(len, buffer.length), now);
    }

    /** One session gate under a long stream of Connects and other packets, with and without ids, cut and made up: no throw. */
    @Test
    void aStreamOfPacketsAtTheSessionGateNeverThrows() {
        Random r = new Random(3);
        SessionGate gate = new SessionGate(KEY);
        SessionGate.Ids ids = new SessionGate.Ids(7);
        long now = 1000;
        for (int i = 0; i < 200_000; i++) {
            now += r.nextInt(100) == 0 ? r.nextInt(60_000) : r.nextInt(300);
            byte[] d = switch (r.nextInt(5)) {
                case 0 -> connect(r.nextInt(6) == 0 ? "other" : KEY, r.nextBoolean() ? r.nextInt() & 0xFFFFFFFFL : -1, r.nextBoolean());
                case 1 -> connect(KEY, ids.next(now, 500), false);
                case 2 -> new byte[]{UdpCommon.Ping, 1, 0, 0, 0, 0, 0, 0, 0, 0};
                default -> {
                    byte[] any = new byte[1 + r.nextInt(40)];
                    r.nextBytes(any);
                    if (r.nextBoolean()) any[0] = UdpCommon.Connect;
                    yield any;
                }
            };
            int len = r.nextInt(4) == 0 ? 1 + r.nextInt(d.length) : d.length;
            long id = SessionGate.sessionId(d, len);
            boolean a = r.nextBoolean(), b = r.nextBoolean(), c = r.nextBoolean();
            try {
                switch (r.nextInt(6)) {
                    case 0 -> gate.fromPilot(d, len, now);
                    case 1 -> gate.fromPilot(d, len, now, id);
                    case 2 -> gate.fromOther(d, len, a, b, now);
                    case 3 -> gate.fromOther(d, len, a, b, now, id);
                    case 4 -> gate.noSession(d, len, a, b, c, () -> a, now);
                    default -> gate.noSession(d, len, a, b, c, () -> b, now, id);
                }
                SessionGate.markAnswer(d, len);
                SessionGate.answeredById(d, len);
                gate.pilotAlive(a, now);
                ids.renewDue(now, now - r.nextInt(5000));
                ids.olderAnswer(id, now);
                if (r.nextInt(50) == 0) ids.sessionUp();
            } catch (Throwable t) {
                throw new AssertionError("packet " + i + ": " + len + " of " + d.length + " bytes " + Arrays.toString(Arrays.copyOf(d, Math.min(d.length, 16))), t);
            }
        }
    }

    /** Every record code the telemetry reads: FcCommon's and its own (the phone's), found by their type so a new one is in. */
    static short[] telemetryCodes() {
        List<Short> codes = new ArrayList<>();
        try {
            for (Class<?> c : new Class<?>[]{FcCommon.class, FcTelemetry.class}) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != short.class || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    codes.add(f.getShort(null));
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        codes.add((short) de.droiddrone.common.MotionRecord.CODE);
        codes.add((short) de.droiddrone.common.Observe.RECORD);
        short[] out = new short[codes.size()];
        for (int i = 0; i < out.length; i++) out[i] = codes.get(i);
        return out;
    }

    /** A telemetry packet's records: [code i16][what follows, made up] a few times. */
    static byte[] telemetry(Random r, short[] codes, int records) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int k = 0; k < records; k++) {
            int code = r.nextInt(8) == 0 ? r.nextInt(65536) : codes[r.nextInt(codes.length)];
            out.write(code >> 8);
            out.write(code);
            byte[] body = new byte[r.nextInt(r.nextInt(6) == 0 ? 300 : 40)];
            r.nextBytes(body);
            if (body.length > 1 && r.nextInt(3) == 0) { // a length that could be one
                body[0] = 0;
                body[1] = (byte) r.nextInt(body.length);
            }
            out.write(body, 0, body.length);
        }
        return out.toByteArray();
    }

    /** One telemetry per kind of flight controller under a long stream of records with made-up contents: no throw, and it still answers. */
    @Test
    void aStreamOfTelemetryRecordsNeverThrows() {
        short[] codes = telemetryCodes();
        assertTrue(codes.length > 20, "the record codes were found: " + codes.length);
        Random r = new Random(4);
        for (FcInfo fc : FLIGHT_CONTROLLERS) {
            FcTelemetry t = new FcTelemetry();
            for (int i = 0; i < 60_000; i++) {
                byte[] d = telemetry(r, codes, 1 + r.nextInt(5));
                try {
                    t.parse(new DataReader(d, true), fc);
                    t.getStatus();
                    t.getMotion().shown(MonoClock.ms());
                    t.getPhoneAppVersion();
                } catch (Throwable e) {
                    throw new AssertionError("packet " + i + ": " + Arrays.toString(Arrays.copyOf(d, Math.min(d.length, 24))), e);
                }
            }
        }
    }

    /** The detection areas from a block of bytes and from a settings line, made up: no throw, never more than the most there can be. */
    @Test
    void detectionAreasOfAnyBytesAndAnyTextAreReadWithoutAThrow() {
        Random r = new Random(6);
        String[] parts = {"0.1", "0.5", "1", "-3", "NaN", "Infinity", "1e40", "", " ", "high", "LOW", "off", "normal", "x", "0x1p3", "1,2"};
        for (int i = 0; i < 100_000; i++) {
            byte[] d = new byte[r.nextInt(100)];
            r.nextBytes(d);
            if (d.length > 0 && r.nextBoolean()) d[0] = (byte) r.nextInt(12);
            StringBuilder text = new StringBuilder();
            for (int k = r.nextInt(14); k > 0; k--) {
                for (int f = r.nextInt(8); f > 0; f--) text.append(parts[r.nextInt(parts.length)]).append(r.nextInt(5) == 0 ? "" : ",");
                text.append(r.nextInt(6) == 0 ? "" : ";");
            }
            try {
                DetectionAreas fromBytes = DetectionAreas.read(new DataReader(d, true));
                DetectionAreas fromText = DetectionAreas.parse(text.toString());
                for (DetectionAreas areas : new DetectionAreas[]{fromBytes, fromText}) {
                    if (areas == null) continue;
                    assertTrue(areas.list().size() <= DetectionAreas.MAX, "at most " + DetectionAreas.MAX + ": " + areas.list().size());
                    areas.at(r.nextFloat(), Float.NaN);
                    DetectionAreas.read(new DataReader(areas.encode(), true));
                    DetectionAreas.parse(areas.toText());
                }
            } catch (Throwable t) {
                throw new AssertionError("bytes " + Arrays.toString(d) + ", text \"" + text + "\"", t);
            }
        }
    }
}
