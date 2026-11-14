package de.droiddrone.pc.fleet;

import de.droiddrone.common.Bytes;
import de.droiddrone.common.Fec;
import de.droiddrone.common.PacketIds;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.OlderPcLink;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.video.VideoPipeline;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.67, the stronger parity in the weak tiers, end to end on loopback: a virtual drone (the phone's rules: RS parity
 * once the PC's profile has bit 4 and VIDEO_FEC asks for some) and the PC's link and video pipeline, a wire between
 * them that loses chosen packets of chosen frames. Two lost in one block are mended (an older phone app's XOR groups
 * can't), a burst past the parity is a damaged frame and a key frame request as ever, an older phone app's XOR groups
 * still mend under the new PC, a new phone app under an older PC sends what it sent before, the normal tier's wire is
 * as it was, and the parity's share comes off the encoder while the step reported stays.
 */
class RsParityEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link;
    private Wire wire;
    private VideoPipeline video;

    @AfterEach
    void tearDown() {
        asking = false;
        System.out.println("packets a frame (packets=frames): plain " + new java.util.TreeMap<>(plainPackets) + ", key " + new java.util.TreeMap<>(keyPackets));
        if (link != null) link.close();
        if (video != null) video.close();
        if (drone != null) drone.stop();
        if (wire != null) wire.close();
    }

    private static PcConfig config(String tier) throws Exception {
        PcConfig config = PcConfig.defaults();
        config.set("cameraWidth", "1280"); // bad coverage mode: 640x360
        config.set("cameraHeight", "720");
        config.set(ControlLink.VIDEO_MODE_KEY, tier);
        return config;
    }

    private void start(String tier, boolean olderPhone, boolean olderPc) throws Exception {
        PcConfig config = config(tier);
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.setOlderApp(olderPhone);
        drone.start(0);
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
        video = new VideoPipeline(new VirtualDroneEndToEndTest.Sink(), () -> link == null ? -1 : link.getPing());
        link = olderPc ? new OlderPcLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0)
                : new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), 0);
        link.setVideoListener(video, true);
        link.start();
        video.attach(link);
        waitFor(() -> video.stats().shownFps() > 0, 20_000, "pictures on the PC", log);
    }

    /** The data packet's index in its frame (the phone's cut), from its offset. */
    private static int fragment(int offset) {
        return offset == 0 ? 0 : 1 + (offset - Fec.FIRST_SEGMENT) / Fec.SEGMENT;
    }

    /**
     * Loses data packets of video frames: of each frame the picker chooses (by number, kind and packets), the packets
     * it names. The frame's size comes with its first packet, which is never lost here.
     */
    private interface Picker {
        Set<Integer> lose(int frameNum, boolean keyFrame, int packets);
    }

    /** The frames hit, by number (each counted once). */
    private final Set<Integer> hit = ConcurrentHashMap.newKeySet();
    /** How many packets the frames had (packets: frames), for the test's own output. */
    private final Map<Integer, Integer> plainPackets = new ConcurrentHashMap<>(), keyPackets = new ConcurrentHashMap<>();

    private void lose(Picker picker) {
        Map<Integer, Integer> sizes = new ConcurrentHashMap<>();
        wire.rule((toPhone, d, len) -> {
            if (toPhone || len < 11 || (d[0] != UdpCommon.VideoFrame && d[0] != UdpCommon.KeyFrame)) return 0;
            int num = ((d[1] & 0xff) << 8) | (d[2] & 0xff), offset = Bytes.readInt(d, 3);
            if (offset == 0) {
                int size = Bytes.readInt(d, 7);
                if (sizes.put(num, size) == null) (d[0] == UdpCommon.KeyFrame ? keyPackets : plainPackets).merge(Fec.fragments(size), 1, Integer::sum);
                return 0;
            }
            Integer size = sizes.get(num);
            if (size == null) return 0;
            if (!picker.lose(num, d[0] == UdpCommon.KeyFrame, Fec.fragments(size)).contains(fragment(offset))) return 0;
            hit.add(num);
            return Wire.Rule.LOST;
        });
    }

    /**
     * Packets 1 and 2 of every key frame of three packets or more - of both copies where it goes twice: one XOR group
     * (packets 0-7) loses two, one RS block too (the last packet among them for a frame of three). Key frames, not
     * plain ones: the virtual drone's still picture makes plain frames of one packet and key frames of three at
     * 640x360, and a key frame is asked for every 1.6 s (keyFrames) on top of the 2 s ones.
     */
    private static Set<Integer> twoOfEachKeyFrame(int num, boolean keyFrame, int packets) {
        return keyFrame && packets >= 3 ? Set.of(1, 2) : Set.of();
    }

    private volatile boolean asking;

    /** A key frame asked for every 1.6 s (the link sends at most one request in 1.5 s). */
    private void keyFrames() {
        asking = true;
        Thread t = new Thread(() -> {
            while (asking && link != null) {
                link.requestKeyFrame();
                try {
                    Thread.sleep(1600);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "testKeyFrames");
        t.setDaemon(true);
        t.start();
    }

    /** Over 10 s with the loss on: the key frames hit, and how many of them were mended / damaged or lost. */
    private long[] tenSecondsOf(Picker picker) throws Exception {
        lose(picker);
        keyFrames();
        Thread.sleep(1000);
        VideoPipeline.Stats before = video.stats();
        int hitBefore = hit.size();
        Thread.sleep(10_000);
        VideoPipeline.Stats after = video.stats();
        asking = false;
        return new long[]{hit.size() - hitBefore, after.recovered() - before.recovered(),
                after.damaged() - before.damaged() + after.lost() - before.lost()};
    }

    @Test
    void twoLossesInOneGroupAreMended() throws Exception {
        start("ROBUST", false, false);
        waitFor(() -> drone.rsParity(true) >= 2, 10_000, "RS parity, two a block of a key frame at least", log);
        long[] r = tenSecondsOf(RsParityEndToEndTest::twoOfEachKeyFrame);
        System.out.printf("RS: %d key frames lost two packets in one block, %d mended, %d damaged or lost (m %d a block)%n",
                r[0], r[1], r[2], drone.rsParity(true));
        assertTrue(r[0] >= 4, "key frames hit: " + r[0]);
        assertTrue(r[1] >= r[0] - 1, "mended: " + r[1] + " of " + r[0]);
        assertTrue(r[2] <= 1, "damaged or lost: " + r[2]);
        assertTrue(wire.toPc(PacketIds.VIDEO_PARITY_RS) > 0);
        assertEquals(0, wire.toPc(PacketIds.VIDEO_PARITY_GROUP), "in place of the XOR groups");
        assertNull(video.stats().error());
    }

    /** The same loss under a phone app before pc.53 (the XOR groups, key frames twice): two lost in a group, damaged. */
    @Test
    void twoLossesInOneGroupAreNotMendedByTheXorGroups() throws Exception {
        start("ROBUST", true, false);
        waitFor(() -> wire.toPc(PacketIds.VIDEO_PARITY_GROUP) > 30, 10_000, "the XOR groups", log);
        long[] r = tenSecondsOf(RsParityEndToEndTest::twoOfEachKeyFrame);
        System.out.printf("XOR: %d key frames lost two packets in one group, %d mended, %d damaged or lost%n", r[0], r[1], r[2]);
        assertTrue(r[0] >= 4, "key frames hit: " + r[0]);
        assertTrue(r[2] >= r[0] - 1, "damaged or lost: " + r[2] + " of " + r[0]);
        assertEquals(0, wire.toPc(PacketIds.VIDEO_PARITY_RS), "an older phone app sends none");
    }

    /**
     * Two packets of a key frame lost and its parity in: mended. The same with all but its first parity packet lost too
     * - more lost than the parity that came can mend: the frame is damaged and the PC asks for a key frame, as ever.
     */
    @Test
    void aLossWithinTheParityIsMendedBeyondItAsksForAKeyFrame() throws Exception {
        start("ROBUST", false, false);
        waitFor(() -> drone.rsParity(true) >= 2, 10_000, "RS parity for key frames", log);
        Thread.sleep(1000);
        AtomicBoolean parityToo = new AtomicBoolean();
        Map<Integer, Boolean> chosen = new ConcurrentHashMap<>(); // the one key frame hit in each part
        Map<Integer, Integer> sizes = new ConcurrentHashMap<>();
        wire.rule((toPhone, d, len) -> {
            if (toPhone || len < 11) return 0;
            if (d[0] == PacketIds.VIDEO_PARITY_RS) {
                Boolean mine = chosen.get((int) Fec.frameNum(d) & 0xffff);
                return mine != null && mine && parityToo.get() && Fec.index(d) >= 1 ? Wire.Rule.LOST : 0;
            }
            if (d[0] != UdpCommon.KeyFrame) return 0;
            int num = ((d[1] & 0xff) << 8) | (d[2] & 0xff), offset = Bytes.readInt(d, 3);
            if (offset == 0) {
                sizes.put(num, Bytes.readInt(d, 7));
                if (Fec.fragments(sizes.get(num)) >= 3) chosen.computeIfAbsent(num, n -> chosen.values().stream().noneMatch(v -> v));
                return 0;
            }
            Boolean mine = chosen.get(num);
            int i = fragment(offset);
            return mine != null && mine && (i == 1 || i == 2) ? Wire.Rule.LOST : 0;
        });
        keyFrames();
        long recovered = video.stats().recovered(), damaged = video.stats().damaged();
        waitFor(() -> video.stats().recovered() > recovered, 8000, "the key frame mended", log);
        assertEquals(damaged, video.stats().damaged(), "two lost, two parity packets at least: whole");
        // the next key frame (the encoder's own, every 2 s: the test asks for none from here): two lost and its
        // parity but the first - beyond what came
        asking = false;
        Thread.sleep(1700);
        long requests = wire.toPhone(PacketIds.KEY_FRAME_REQUEST);
        long damaged2 = video.stats().damaged(), lost2 = video.stats().lost();
        parityToo.set(true);
        chosen.replaceAll((n, v) -> false);
        waitFor(() -> video.stats().damaged() + video.stats().lost() > damaged2 + lost2, 8000, "the key frame damaged", log);
        waitFor(() -> wire.toPhone(PacketIds.KEY_FRAME_REQUEST) > requests, 4000, "a key frame asked for", log);
    }

    /** A phone app before pc.53 under the new PC: bit 4 is read past, and its XOR groups mend one loss a group as ever. */
    @Test
    void anOlderPhonesGroupParityStillMends() throws Exception {
        start("ROBUST", true, false);
        waitFor(() -> wire.toPc(PacketIds.VIDEO_PARITY_GROUP) > 30, 10_000, "the XOR groups", log);
        long[] r = tenSecondsOf((num, keyFrame, packets) -> keyFrame && packets >= 3 ? Set.of(1) : Set.of());
        System.out.printf("XOR, one loss a group: %d key frames hit, %d mended, %d damaged or lost%n", r[0], r[1], r[2]);
        assertTrue(r[0] >= 4, "key frames hit: " + r[0]);
        assertTrue(r[1] >= r[0] - 1, "mended by the XOR groups: " + r[1] + " of " + r[0]);
        assertEquals(0, wire.toPc(PacketIds.VIDEO_PARITY_RS));
        assertTrue(wire.toPhone(PacketIds.VIDEO_FEC) > 0, "the new PC asked, the old app had no ear for it");
    }

    /** A new phone app under a PC before 1.0.67 in bad coverage mode: the XOR groups and key frames twice, nothing new. */
    @Test
    void aNewPhoneUnderAnOlderPcSendsWhatItSentBefore() throws Exception {
        start("ROBUST", false, true);
        wire.keep(true);
        AtomicBoolean twice = new AtomicBoolean();
        waitFor(() -> {
            Map<Integer, Integer> firsts = new HashMap<>();
            for (byte[] d : wire.sentToPc()) {
                if (d[0] != UdpCommon.KeyFrame || Bytes.readInt(d, 3) != 0) continue;
                int num = ((d[1] & 0xff) << 8) | (d[2] & 0xff);
                if (firsts.merge(num, 1, Integer::sum) == 2) twice.set(true);
            }
            return twice.get();
        }, 10_000, "a key frame sent twice", log);
        assertTrue(wire.toPc(PacketIds.VIDEO_PARITY_GROUP) > 0, "the XOR groups");
        assertEquals(0, wire.toPc(PacketIds.VIDEO_PARITY_RS), "no RS parity");
        assertEquals(0, wire.toPhone(PacketIds.VIDEO_FEC), "an older PC sends no VIDEO_FEC");
        for (byte[] d : wire.sentToPhone()) {
            if (d[0] == PacketIds.VIDEO_PROFILE) assertEquals(0, d[1] & 0xF8, "an older PC's profile: bits 0-2");
        }
        assertEquals(0, drone.rsParity(false));
        waitFor(() -> drone.encoderBitRate() == drone.bitRateNow() && link.getTelemetry().getPhoneTargetBitRate() == drone.bitRateNow(), 5000,
                "the encoder at the step, the step reported", log);
    }

    /** The normal tier: no RS parity, no VIDEO_FEC, no bit 4 - the wire as before (and FecPolicyTest: none outside the weak tiers). */
    @Test
    void theNormalTierIsAsBefore() throws Exception {
        start("NORMAL", false, false);
        wire.keep(true);
        Thread.sleep(3000);
        assertEquals(0, wire.toPhone(PacketIds.VIDEO_FEC));
        assertEquals(0, wire.toPc(PacketIds.VIDEO_PARITY_RS));
        assertEquals(0, wire.toPc(PacketIds.VIDEO_PARITY_GROUP));
        for (byte[] d : wire.sentToPhone()) {
            if (d[0] == PacketIds.VIDEO_PROFILE) assertEquals(0, d[1] & 16, "no bit 4");
        }
        assertEquals(0, drone.rsParity(false));
        waitFor(() -> drone.encoderBitRate() == drone.bitRateNow() && link.getTelemetry().getPhoneTargetBitRate() == drone.bitRateNow(), 5000,
                "the encoder at the step, the step reported", log);
    }

    /** The parity's share comes off the encoder's bitrate; the step - what the phone reports, what the PC counts by - stays. */
    @Test
    void theParityShareLowersTheEncoderNotTheReportedBitrate() throws Exception {
        start("ROBUST", false, false);
        waitFor(() -> drone.rsParity(false) > 0, 10_000, "RS parity asked for", log);
        waitFor(() -> drone.encoderBitRate() > 0 && drone.encoderBitRate() < drone.bitRateNow()
                && link.getTelemetry().getPhoneTargetBitRate() == drone.bitRateNow(), 15_000, "the encoder under the step, the step reported", log);
        int step = link.getTelemetry().getPhoneTargetBitRate();
        assertTrue(java.util.Arrays.stream(VirtualDrone.STEPS).anyMatch(s -> s == step), "the step's own figure reported: " + step);
        assertTrue(drone.encoderBitRate() >= step / 2, "never under half the step: " + drone.encoderBitRate() + " of " + step);
        System.out.printf("ROBUST: step %d bit/s reported, the encoder at %d (the parity's share %.0f %%)%n",
                step, drone.encoderBitRate(), 100.0 * (step - drone.encoderBitRate()) / step);
    }
}
