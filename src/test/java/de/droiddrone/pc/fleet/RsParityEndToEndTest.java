package de.droiddrone.pc.fleet;

import de.droiddrone.common.Bytes;
import de.droiddrone.common.Fec;
import de.droiddrone.common.PacketIds;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import de.droiddrone.pc.OlderPcLink;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.video.VideoPipeline;
import de.droiddrone.testing.Ports;
import de.droiddrone.testing.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Predicate;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.71, the stronger parity in the weak tiers, end to end on loopback: a virtual drone (the phone's rules: RS parity
 * once the PC's profile has bit 4 and VIDEO_FEC asks for some) and the PC's link and video pipeline, a wire between
 * them that loses chosen packets of chosen frames. Two lost in one block are mended (an older phone app's XOR groups
 * can't), a burst past the parity is a damaged frame and a key frame request as ever, an older phone app's XOR groups
 * still mend under the new PC, a new phone app under an older PC sends what it sent before, the normal tier's wire is
 * as it was, and the parity's share comes off the encoder while the step reported stays. Spectators: one of 1.0.66
 * gets the XOR groups and key frames twice while it watches (the phone is told no RS), one of this version keeps the
 * RS parity and mends with it.
 */
class RsParityEndToEndTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;
    private ControlLink link;
    private Wire wire;
    private VideoPipeline video;
    /** The pilot PC's link port (spectators say HELLO there). */
    private int pilotPort;
    private ControlLink spectatorLink;
    private VideoPipeline spectatorVideo;
    private OlderSpectator olderSpectator;

    @AfterEach
    void tearDown() {
        asking = false;
        System.out.println("packets a frame (packets=frames): plain " + new java.util.TreeMap<>(plainPackets) + ", key " + new java.util.TreeMap<>(keyPackets));
        if (olderSpectator != null) olderSpectator.close();
        if (spectatorLink != null) spectatorLink.close();
        if (spectatorVideo != null) spectatorVideo.close();
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
        start(tier, olderPhone, olderPc, false);
    }

    /** @param spectators the pilot PC lets spectators watch (at {@link #pilotPort}) */
    private void start(String tier, boolean olderPhone, boolean olderPc, boolean spectators) throws Exception {
        PcConfig config = config(tier);
        drone = new VirtualDrone(0, "MobileZulu", config.getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT, Fleet.DEFAULT_LON, 0,
                new FlightModel.Setup(), log::add);
        drone.setOlderApp(olderPhone);
        drone.start(0);
        wire = new Wire(InetAddress.getLoopbackAddress(), drone.port());
        video = new VideoPipeline(new VirtualDroneEndToEndTest.Sink(), () -> link == null ? -1 : link.getPing());
        pilotPort = spectators ? Ports.free() : 0;
        link = olderPc ? new OlderPcLink(config, InetAddress.getLoopbackAddress(), wire.port(), pilotPort)
                : new ControlLink(config, InetAddress.getLoopbackAddress(), wire.port(), pilotPort);
        link.setVideoListener(video, true);
        if (spectators) {
            link.enableSpectators(true, 600);
            link.setEvents(line -> log.add("pilot: " + line));
        }
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

    /** A new phone app under a PC before 1.0.71 in bad coverage mode: the XOR groups and key frames twice, nothing new. */
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

    // ------------------------------------------------------------------ spectators (one of 1.0.66 mends no RS)

    /** The index of the first packet from that index on that the test names; -1 if none. */
    private static int first(List<byte[]> packets, int from, Predicate<byte[]> which) {
        for (int i = Math.max(0, from); i < packets.size(); i++) {
            if (which.test(packets.get(i))) return i;
        }
        return -1;
    }

    /** VIDEO_FEC 0 0: no RS parity. */
    private static boolean noRs(byte[] d) {
        return d.length >= 3 && d[0] == PacketIds.VIDEO_FEC && d[1] == 0 && d[2] == 0;
    }

    /** VIDEO_FEC asking for RS parity. */
    private static boolean rs(byte[] d) {
        return d.length >= 3 && d[0] == PacketIds.VIDEO_FEC && (d[1] != 0 || d[2] != 0);
    }

    private static boolean keyFrameRequest(byte[] d) {
        return d[0] == PacketIds.KEY_FRAME_REQUEST;
    }

    /**
     * A spectator PC of 1.0.66 (its HELLO without flags) watches a pilot PC of this version flying a pc.53 phone in
     * ROBUST: it mends the XOR groups only and wants the key frames twice. The phone is told no RS (VIDEO_FEC 0 0) at the
     * pilot's next tick after the join, the key frame the spectator starts at is asked for behind that, and what it is
     * passed is what a 1.0.66 pilot PC passed: the XOR groups and key frames twice, no RS. It leaves (BYE): the RS parity
     * is asked for again at the next tick.
     */
    @Test
    void anOlderSpectatorGetsTheXorGroupsAndKeyFramesTwiceUntilItLeaves() throws Exception {
        start("ROBUST", false, false, true);
        waitFor(() -> drone.rsParity(true) >= 2, 10_000, "RS parity", log);
        Thread.sleep(1600); // the start's key frame requests behind (one in 1.5 s at most): the join's own goes out
        wire.keep(true);
        long joinAt = System.nanoTime();
        olderSpectator = new OlderSpectator(config("ROBUST").getKey(), pilotPort);
        waitFor(() -> link.getSpectatorCount() == 1, 5000, "the older spectator watching", log);
        waitFor(() -> drone.rsParity(true) == 0 && drone.rsParity(false) == 0, 2000, "the phone told: no RS", log);
        long offMs = (System.nanoTime() - joinAt) / 1_000_000;
        // its first key frame: asked for behind the 0 0, so the phone sends it twice, with the XOR groups
        waitFor(() -> first(wire.sentToPhone(), 0, RsParityEndToEndTest::keyFrameRequest) >= 0, 2000, "its key frame asked for", log);
        List<byte[]> toPhone = wire.sentToPhone();
        int off = first(toPhone, 0, RsParityEndToEndTest::noRs), request = first(toPhone, 0, RsParityEndToEndTest::keyFrameRequest);
        assertTrue(off >= 0 && request > off, "VIDEO_FEC 0 0 at " + off + ", the key frame request at " + request);
        assertTrue(log.stream().anyMatch(l -> l.contains("older spectator watching")), "said in the pilot's log");

        Thread.sleep(300); // what the phone sent before it had the 0 0 has passed
        long from = System.currentTimeMillis();
        olderSpectator.counting = true;
        keyFrames();
        Thread.sleep(6000);
        asking = false;
        olderSpectator.counting = false;
        long to = System.currentTimeMillis();
        Thread.sleep(1000); // the last key frame's second copy
        OlderSpectator s = olderSpectator;
        long groups = s.counted.get(PacketIds.VIDEO_PARITY_GROUP & 0xff), rsPackets = s.counted.get(PacketIds.VIDEO_PARITY_RS & 0xff);
        Map<Integer, Integer> copies = new java.util.TreeMap<>();
        s.keyFrames.forEach((num, k) -> {
            if (k[1] >= from && k[1] <= to) copies.put(num, (int) k[0]);
        });
        long twice = copies.values().stream().filter(n -> n == 2).count();
        long noRsToPhone = wire.sentToPhone().stream().filter(RsParityEndToEndTest::noRs).count();
        System.out.printf("older spectator: RS off %d ms after its HELLO; passed on %d XOR group packets, %d RS; key frames (number=copies) %s%n",
                offMs, groups, rsPackets, copies);
        assertTrue(groups > 30, "the XOR groups passed on: " + groups);
        assertEquals(0, rsPackets, "no RS parity while it watches");
        assertTrue(twice >= 3 && twice == copies.size(), "every key frame twice: " + copies);
        assertEquals(0, drone.rsParity(true));
        assertTrue(first(wire.sentToPhone(), off + 1, RsParityEndToEndTest::rs) < 0, "no RS asked for while it watches");
        assertTrue(noRsToPhone >= 6, "the 0 0 at the change, its two copies, then once a second: " + noRsToPhone);

        int before = wire.sentToPhone().size();
        long leftAt = System.nanoTime();
        olderSpectator.close(); // BYE
        olderSpectator = null;
        waitFor(() -> link.getSpectatorCount() == 0, 2000, "the older spectator gone", log);
        waitFor(() -> drone.rsParity(true) >= 2, 2000, "RS parity again", log);
        long onMs = (System.nanoTime() - leftAt) / 1_000_000;
        long rsBefore = wire.toPc(PacketIds.VIDEO_PARITY_RS);
        waitFor(() -> wire.toPc(PacketIds.VIDEO_PARITY_RS) > rsBefore, 3000, "RS parity on the wire again", log);
        assertTrue(first(wire.sentToPhone(), before, RsParityEndToEndTest::rs) >= 0);
        assertTrue(log.stream().anyMatch(l -> l.contains("stronger parity on again")), "said in the pilot's log");
        System.out.printf("older spectator gone: RS on again %d ms after its BYE%n", onMs);
        assertNull(video.stats().error());
    }

    /**
     * A spectator PC of this version (a real link and video pipeline) says in its HELLO that it mends RS: the parity
     * stays, and the spectator mends with it what the pilot PC got gapped (two lost in a block of every key frame) - as
     * the pilot does. An older spectator joining beside it turns the RS off; once that one has left it comes back, the
     * newer one watching all along.
     */
    @Test
    void aNewSpectatorKeepsTheRsParityAndMendsWithIt() throws Exception {
        start("ROBUST", false, false, true);
        waitFor(() -> drone.rsParity(true) >= 2, 10_000, "RS parity", log);
        wire.keep(true);
        spectatorLink = new ControlLink(PcConfig.defaults(), InetAddress.getLoopbackAddress(), pilotPort, Ports.free());
        spectatorLink.enableSpectators(true, 300);
        spectatorLink.watchOnly(true);
        spectatorVideo = new VideoPipeline(new VirtualDroneEndToEndTest.Sink(), () -> -1);
        spectatorLink.setVideoListener(spectatorVideo, false);
        spectatorVideo.attach(spectatorLink);
        spectatorLink.start();
        waitFor(() -> link.getSpectatorCount() == 1, 5000, "the spectator watching", log);
        waitFor(() -> spectatorVideo.stats().shownFps() > 0, 10_000, "pictures on the spectator", log);

        lose(RsParityEndToEndTest::twoOfEachKeyFrame);
        keyFrames();
        Thread.sleep(1000);
        VideoPipeline.Stats before = spectatorVideo.stats();
        int hitBefore = hit.size();
        Thread.sleep(8000);
        VideoPipeline.Stats after = spectatorVideo.stats();
        asking = false;
        wire.clear();
        long hits = hit.size() - hitBefore, mended = after.recovered() - before.recovered();
        long broken = after.damaged() - before.damaged() + after.lost() - before.lost();
        System.out.printf("new spectator: %d key frames lost two packets in a block on the phone's way, %d mended here, %d damaged or lost%n",
                hits, mended, broken);
        assertEquals(-1, first(wire.sentToPhone(), 0, RsParityEndToEndTest::noRs), "the RS parity was never held back");
        assertTrue(drone.rsParity(true) >= 2);
        assertTrue(hits >= 4, "key frames hit: " + hits);
        assertTrue(mended >= hits - 1, "mended on the spectator: " + mended + " of " + hits);
        assertTrue(broken <= 1, "damaged or lost on the spectator: " + broken);

        // an older one beside it: no RS while it watches; gone, the RS comes back for the one left
        olderSpectator = new OlderSpectator(config("ROBUST").getKey(), pilotPort);
        waitFor(() -> link.getSpectatorCount() == 2, 5000, "two spectators", log);
        waitFor(() -> drone.rsParity(true) == 0, 2000, "the phone told: no RS", log);
        olderSpectator.close();
        olderSpectator = null;
        waitFor(() -> link.getSpectatorCount() == 1 && drone.rsParity(true) >= 2, 2000, "RS parity again, the newer spectator still watching", log);
        assertEquals(ControlLink.State.SPECTATING, spectatorLink.getState());
    }

    /**
     * A spectator PC of 1.0.66 on the wire, in that version's bytes: HELLO [0x63][key UTF] (no flags) to the pilot's
     * link port until WELCOME, then KEEP [0x68][0][0] to the stream port 4 a second, BYE [0x67] at the end. It counts
     * the phone's packets the pilot PC passes on (STREAM [0x66][seq i32][ms i32][packet]) by their first byte while
     * {@link #counting}, and each key frame's first packet - two: the key frame came twice.
     */
    private static final class OlderSpectator implements AutoCloseable {
        private final DatagramSocket socket;
        private final int pilotPort;
        private final byte[] hello;
        private volatile int streamPort = -1;
        private volatile boolean running = true;
        private final Thread thread;
        volatile boolean counting;
        final AtomicLongArray counted = new AtomicLongArray(256);
        /** Key frames by number: {copies of the first packet, when the first came (ms)}. */
        final Map<Integer, long[]> keyFrames = new ConcurrentHashMap<>();

        OlderSpectator(String key, int pilotPort) throws IOException {
            this.pilotPort = pilotPort;
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            socket.setSoTimeout(20);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(PacketIds.SPECTATE_HELLO);
            out.writeUTF(key);
            hello = bytes.toByteArray();
            thread = new Thread(this::run, "olderSpectator");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            byte[] buf = new byte[4096];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            long nextHello = 0, nextKeep = 0;
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    if (streamPort < 0 && now >= nextHello) {
                        send(hello, pilotPort);
                        nextHello = now + 500;
                    } else if (streamPort >= 0 && now >= nextKeep) {
                        send(new byte[]{PacketIds.SPECTATE_KEEP, 0, 0}, streamPort);
                        nextKeep = now + 250;
                    }
                    p.setLength(buf.length);
                    try {
                        socket.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    int len = p.getLength();
                    if (len >= 3 && buf[0] == PacketIds.SPECTATE_WELCOME) {
                        if (streamPort < 0) streamPort = ((buf[1] & 0xff) << 8) | (buf[2] & 0xff);
                    } else if (len > 9 && buf[0] == PacketIds.SPECTATE_STREAM) {
                        if (counting) counted.incrementAndGet(buf[9] & 0xff);
                        if (buf[9] == UdpCommon.KeyFrame && len >= 9 + 11 && Bytes.readInt(buf, 9 + 3) == 0) {
                            int num = ((buf[10] & 0xff) << 8) | (buf[11] & 0xff);
                            keyFrames.computeIfAbsent(num, n -> new long[]{0, now})[0]++;
                        }
                    }
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }

        private void send(byte[] d, int port) throws IOException {
            socket.send(new DatagramPacket(d, d.length, InetAddress.getLoopbackAddress(), port));
        }

        @Override
        public void close() {
            running = false;
            try {
                thread.join(1000);
                if (streamPort >= 0) send(new byte[]{PacketIds.SPECTATE_BYE}, streamPort);
            } catch (IOException | InterruptedException ignored) {
            }
            socket.close();
        }
    }
}
