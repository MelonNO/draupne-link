package de.droiddrone.pc.overwatch;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PowerFloor;
import de.droiddrone.pc.map.CameraFootprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The PC's overwatch hub with a made-up phone: what it answers to each packet, and what it keeps (1.0.47). */
class OverwatchHubTest {
    private static final int TOKEN = 0x51EE7;
    private static final long STARTED = 1_762_500_000_000L;

    @TempDir
    Path dir;

    private final List<byte[]> sent = new ArrayList<>();
    private final List<String> news = new ArrayList<>(), dropped = new ArrayList<>(), logs = new ArrayList<>();

    private OverwatchHub hub() {
        OverwatchHub hub = new OverwatchHub(new ClipIndex(dir), sent::add);
        hub.setListener(new OverwatchHub.Listener() {
            @Override
            public void onNews(String drone) {
                news.add(drone);
            }

            @Override
            public void onDropped(String drone, int reason, int batteryPercent, int tempDeci) {
                dropped.add(drone + ":" + reason);
            }

            @Override
            public void onClips(String drone) {
            }

            @Override
            public void log(String line) {
                logs.add(line);
            }
        });
        hub.watch("MobileAlpha", TOKEN, new CameraFootprint.Pose(59.9, 10.7, 120, 45, 0, 0, 10, 70, 40));
        return hub;
    }

    private static byte[] alert(int token, int event, int no) {
        return OverwatchProtocol.alert(new OverwatchProtocol.Alert(token, event, no, 0, STARTED, 0.4f, 0.4f, 0.1f, 0.2f, 5f, 70f,
                599_000_000, 107_000_000, 30, 80, "MobileAlpha"));
    }

    private void receive(OverwatchHub hub, byte[] p) throws Exception {
        hub.handleNow(p, InetAddress.getLoopbackAddress(), 40000);
    }

    private List<Integer> commandsSent() {
        List<Integer> out = new ArrayList<>();
        for (byte[] p : sent) {
            OverwatchProtocol.Command c = OverwatchProtocol.readCommand(p, p.length);
            if (c != null) {
                assertEquals(TOKEN, c.token, "every command carries the drone's token");
                out.add(c.cmd);
            }
        }
        return out;
    }

    /**
     * A CLIP packet cut short (a phone's datagram cut by the network, or a broken sender): ignored, no throw - the
     * hub's thread caught it, but skipped its tick and logged an index error for each one.
     */
    @Test
    void aCutClipPacketIsIgnoredWithoutAThrow() throws Exception {
        OverwatchHub hub = hub();
        receive(hub, alert(TOKEN, 1, 1));
        byte[] chunk = new byte[40];
        chunk[0] = OverwatchProtocol.CLIP;
        chunk[1] = (byte) (TOKEN >> 24);
        chunk[2] = (byte) (TOKEN >> 16);
        chunk[3] = (byte) (TOKEN >> 8);
        chunk[4] = (byte) TOKEN;
        chunk[6] = 1;
        chunk[7] = (byte) OverwatchProtocol.CLIP_CHUNK;
        for (int n = 5; n < OverwatchProtocol.CHUNK_DATA_AT; n++) {
            byte[] cut = java.util.Arrays.copyOf(chunk, n);
            assertDoesNotThrow(() -> receive(hub, cut), n + " bytes");
        }
    }

    @Test
    void aStrangersAlertIsNotAnswered() throws Exception {
        OverwatchHub hub = hub();
        receive(hub, alert(0x1234, 1, 0));
        assertTrue(sent.isEmpty());
        assertTrue(news.isEmpty());
        assertTrue(hub.views().isEmpty());
    }

    @Test
    void anAlertIsAcknowledgedOnceNewsAndThePreviewIsAskedFor() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 1, 0));
        receive(hub, alert(TOKEN, 1, 0)); // the ACK was lost: again
        assertEquals(List.of("MobileAlpha"), news, "one sound for one event");
        List<Integer> cmds = commandsSent();
        assertEquals(2, cmds.stream().filter(c -> c == OverwatchProtocol.CMD_ACK).count());
        assertTrue(cmds.contains(OverwatchProtocol.CMD_PREVIEW));
        assertEquals(1, hub.views().size());
        OverwatchHub.View v = hub.views().get(0);
        assertEquals(AlertCard.Shown.CARD, v.shown());
        assertFalse(v.ended());
        assertNotNull(v.pose());
        ClipIndex.Clip clip = hub.index().clips("MobileAlpha").get(0);
        assertEquals(STARTED, clip.startedAt());
        assertEquals(59.9, clip.lat(), 1e-9);
        assertEquals("45.0", clip.meta().getProperty("headingDeg"));
        // the card asks for the preview again every second while it shows
        sent.clear();
        hub.tickNow(1500);
        assertTrue(commandsSent().isEmpty());
        hub.tickNow(2000);
        assertEquals(List.of(OverwatchProtocol.CMD_PREVIEW), commandsSent());
        // dismissed: no more preview requests
        hub.dismiss("MobileAlpha"); // not started: runs at once
        sent.clear();
        hub.tickNow(5000);
        assertTrue(commandsSent().isEmpty());
    }

    /**
     * 1.0.64: the phone's next overwatch counts its events from 1 again. With this PC left open, "event 1, alert 0"
     * after an earlier event 1 was acknowledged (so the phone stopped sending) and treated as a repeat: no ring, no
     * card. Its start time tells them apart, under the same token or a new one.
     */
    @Test
    void theNextOverwatchsFirstAlertIsNewsThoughItsNumberRepeats() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 1, 0));
        hub.dismiss("MobileAlpha");
        assertEquals(1, news.size());
        // woken, flown, put to overwatch again: event 1 once more, an hour later
        hub.nowOverride = 3_600_000;
        receive(hub, alertAt(TOKEN, 1, 0, STARTED + 3_600_000));
        assertEquals(2, news.size(), "the second overwatch's first alert rings");
        assertEquals(AlertCard.Shown.CARD, hub.views().get(0).shown(), "and opens its card");
        assertEquals(2, hub.index().clips("MobileAlpha").size(), "an event of its own");
        receive(hub, alertAt(TOKEN, 1, 0, STARTED + 3_600_000)); // its resend: no news
        assertEquals(2, news.size());
        // and under a new token
        hub.watch("MobileAlpha", TOKEN + 1, null);
        receive(hub, alertAt(TOKEN + 1, 1, 0, STARTED + 7_200_000));
        assertEquals(3, news.size());
    }

    /** pc.50's phone tells an alert on after its event when no PC acknowledged it: news here, said to be late. */
    @Test
    void anAlertThatComesLateIsNewsAndSaysSo() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        hub.wallOverride = STARTED + 4 * 60_000 + 5000;
        receive(hub, alert(TOKEN, 7, 0));
        assertEquals(List.of("MobileAlpha"), news);
        assertTrue(commandsSent().contains(OverwatchProtocol.CMD_ACK));
        assertTrue(logs.stream().anyMatch(l -> l.contains("MOTION") && l.contains("4 min ago")), logs.toString());
        assertEquals("", OverwatchHub.lateText(STARTED, STARTED + 20_000), "a fresh one says nothing");
        assertTrue(OverwatchProtocol.missedDue(60_000, OverwatchProtocol.MISSED_EVERY_MS));
        assertFalse(OverwatchProtocol.missedDue(60_000, 5000), "every 15 s");
        assertFalse(OverwatchProtocol.missedDue(OverwatchProtocol.MISSED_FOR_MS, 60_000), "30 min at most");
        assertTrue(OverwatchProtocol.endedDue(10_000, 1000), "MOTION ENDED: every second at first");
        assertFalse(OverwatchProtocol.endedDue(60_000, 1000), "then every 5 s");
        assertTrue(OverwatchProtocol.endedDue(60_000, 5000));
        assertFalse(OverwatchProtocol.endedDue(OverwatchProtocol.ENDED_FOR_MS, 5000), "10 min in all");
    }

    /**
     * 1.0.64, the pilot: alerts go to every PC. A phone (pc.50) tells each PC that knows it; a PC that didn't set the
     * overwatch takes the alert up when it names a phone of its own, from that phone's address - and nothing else.
     */
    @Test
    void anAlertOfAnOverwatchAnotherPcSetIsTakenUpForAPhoneOfOurs() throws Exception {
        OverwatchHub hub = new OverwatchHub(new ClipIndex(dir), sent::add);
        hub.setListener(new OverwatchHub.Listener() {
            @Override
            public void onNews(String drone) {
                news.add(drone);
            }

            @Override
            public void onDropped(String drone, int reason, int batteryPercent, int tempDeci) {
            }

            @Override
            public void onClips(String drone) {
            }

            @Override
            public void log(String line) {
                logs.add(line);
            }
        });
        hub.nowOverride = 1000;
        int foreign = 0x7777;
        receive(hub, alert(foreign, 3, 0));
        assertTrue(news.isEmpty(), "no name map asked: not taken");
        hub.setOurPhone((name, from) -> false);
        receive(hub, alert(foreign, 3, 0));
        assertTrue(news.isEmpty() && sent.isEmpty(), "not a phone of ours: dropped without a word");
        hub.setOurPhone((name, from) -> name.equals("MobileAlpha") && from.isLoopbackAddress());
        receive(hub, alert(foreign, 3, 0));
        assertEquals(List.of("MobileAlpha"), news, "a phone of ours: the alert rings here too");
        OverwatchProtocol.Command ack = OverwatchProtocol.readCommand(sent.get(0), sent.get(0).length);
        assertEquals(OverwatchProtocol.CMD_ACK, ack.cmd);
        assertEquals(foreign, ack.token, "answered with that overwatch's token");
        assertEquals(1, hub.views().size());
        assertTrue(logs.stream().anyMatch(l -> l.contains("another PC")), logs.toString());
    }

    private static byte[] alertAt(int token, int event, int no, long startedAt) {
        return OverwatchProtocol.alert(new OverwatchProtocol.Alert(token, event, no, 0, startedAt, 0.4f, 0.4f, 0.1f, 0.2f, 5f, 70f,
                599_000_000, 107_000_000, 30, 80, "MobileAlpha"));
    }

    @Test
    void thePicturesBeforeTheAlertAreKeptAndShown() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 3, 0));
        BufferedImage img = new BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", jpeg);
        for (int i = 0; i < 3; i++) {
            ByteArrayOutputStream unit = new ByteArrayOutputStream();
            new java.io.DataOutputStream(unit).writeLong(STARTED - 5000 + i * 500L);
            unit.write(jpeg.toByteArray());
            for (byte[] p : OverwatchProtocol.fragments(TOKEN, 3, i, OverwatchProtocol.UNIT_PREROLL, unit.toByteArray())) receive(hub, p);
        }
        ClipIndex.Clip clip = hub.index().clips("MobileAlpha").get(0);
        assertEquals(3, clip.preRoll().size());
        assertNotNull(hub.views().get(0).picture(), "the card shows the latest picture until the live preview comes");
        // a fragment of another event is not this one's
        for (byte[] p : OverwatchProtocol.fragments(TOKEN, 99, 7, OverwatchProtocol.UNIT_PREROLL, new byte[20])) receive(hub, p);
        assertEquals(3, hub.index().clips("MobileAlpha").get(0).preRoll().size());
    }

    /** 1.0.52: the phone's boxes now (pc.40, with the live preview) reach the card; stale ones are dropped. */
    @Test
    void thePhonesBoxesNowReachTheCardAndGoStale() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 3, 0));
        assertNull(hub.views().get(0).boxes(), "none yet: an older phone never sends them");
        List<float[]> boxes = new ArrayList<>();
        boxes.add(new float[]{0.6f, 0.45f, 0.1f, 0.05f, 0});
        for (byte[] p : OverwatchProtocol.fragments(TOKEN, 3, 1, OverwatchProtocol.UNIT_BOXES, OverwatchProtocol.boxesUnit(STARTED + 2000, boxes))) receive(hub, p);
        float[][] got = hub.views().get(0).boxes();
        assertNotNull(got);
        assertEquals(0.6f, got[0][0], 1e-4);
        hub.nowOverride = 1000 + OverwatchHub.BOXES_FRESH_MS + 1;
        hub.tickNow(hub.nowOverride);
        assertNull(hub.views().get(0).boxes(), "not heard lately: no box rather than an old one");
        // another event's boxes are not this one's
        hub.nowOverride = 5000;
        for (byte[] p : OverwatchProtocol.fragments(TOKEN, 99, 2, OverwatchProtocol.UNIT_BOXES, OverwatchProtocol.boxesUnit(STARTED, boxes))) receive(hub, p);
        assertNull(hub.views().get(0).boxes());
    }

    @Test
    void theMotionsEndIsAcknowledgedAndTheCardShrinksLater() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 4, 0));
        sent.clear();
        hub.nowOverride = 31_000;
        receive(hub, OverwatchProtocol.clipEnded(TOKEN, 4, STARTED + 30_000, true));
        assertEquals(List.of(OverwatchProtocol.CMD_ENDED_ACK), commandsSent());
        assertTrue(hub.views().get(0).ended());
        hub.nowOverride = 31_000 + AlertCard.SHRINK_AFTER_MS;
        hub.tickNow(31_000 + AlertCard.SHRINK_AFTER_MS);
        assertEquals(AlertCard.Shown.BADGE, hub.views().get(0).shown());
        assertEquals(Long.toString(STARTED + 30_000), hub.index().clips("MobileAlpha").get(0).meta().getProperty("endedAt"));
    }

    @Test
    void theFullClipIsPulledOnlyWhileNoDroneHereIsArmedAndCheckedBeforeThePhoneMayDeleteIt() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 5, 0));
        receive(hub, OverwatchProtocol.clipEnded(TOKEN, 5, STARTED + 40_000, true));
        byte[] clip = new byte[OverwatchProtocol.FRAGMENT * 70 + 10];
        new Random(7).nextBytes(clip);
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(clip);
        receive(hub, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 5, clip.length, sha, 40_000, STARTED, true, 1280, 720)));
        hub.setFlying(true);
        sent.clear();
        for (long t = 2000; t < 30_000; t += 100) hub.tickNow(t);
        assertTrue(commandsSent().stream().noneMatch(c -> c == OverwatchProtocol.CMD_CLIP_GET), "armed: not one request");
        assertTrue(hub.views().get(0).clipLine().contains("paused"));
        hub.setFlying(false);
        for (int round = 0; round < 10; round++) {
            sent.clear();
            hub.tickNow(30_000 + round * 2000L);
            for (byte[] p : new ArrayList<>(sent)) {
                OverwatchProtocol.Command c = OverwatchProtocol.readCommand(p, p.length);
                if (c == null || c.cmd != OverwatchProtocol.CMD_CLIP_GET) continue;
                assertEquals(5, c.event);
                for (int i = 0; i < c.count; i++) {
                    long off = c.offset + (long) i * OverwatchProtocol.FRAGMENT;
                    int n = (int) Math.min(OverwatchProtocol.FRAGMENT, clip.length - off);
                    receive(hub, OverwatchProtocol.clipChunk(TOKEN, 5, off, clip, (int) off, n));
                }
            }
            if (commandsSent().contains(OverwatchProtocol.CMD_CLIP_DONE)) break;
        }
        assertTrue(commandsSent().contains(OverwatchProtocol.CMD_CLIP_DONE));
        ClipIndex.Clip c = hub.index().clips("MobileAlpha").get(0);
        assertNotNull(c.fullClip());
        assertArrayEquals(clip, Files.readAllBytes(c.fullClip()));
        assertEquals("full", c.meta().getProperty("clip"));
        assertEquals(40_000, c.durationMs());
        // the phone didn't hear the DONE and announces the clip again: told DONE at once, nothing fetched again
        sent.clear();
        receive(hub, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 5, clip.length, sha, 40_000, STARTED, true, 1280, 720)));
        assertEquals(List.of(OverwatchProtocol.CMD_CLIP_DONE), commandsSent());
        // 1.0.49: the DONE names the clip's start - an event's segments share its number
        assertEquals(STARTED, OverwatchProtocol.readCommand(sent.get(0), sent.get(0).length).clipStartedAt);
    }

    /** H4 (1.0.49): while a drone here may fly, the card asks for no preview - the uplink is the sticks'. */
    @Test
    void noPreviewIsAskedWhileADroneHereMayFly() throws Exception {
        OverwatchHub hub = hub();
        hub.setFlying(true);
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 2, 0));
        assertEquals(List.of("MobileAlpha"), news, "the alert itself still comes (quietly: the window decides)");
        assertTrue(commandsSent().contains(OverwatchProtocol.CMD_ACK));
        for (long t = 1100; t < 8000; t += 100) hub.tickNow(t);
        assertFalse(commandsSent().contains(OverwatchProtocol.CMD_PREVIEW));
        hub.setFlying(false);
        hub.tickNow(8100);
        assertTrue(commandsSent().contains(OverwatchProtocol.CMD_PREVIEW), "landed: the preview is asked for");
    }

    /** M10 (1.0.49): the checked clip's hash is kept: announced again, it is not read whole again. */
    @Test
    void aClipAnnouncedAgainIsNotHashedAgain() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 5, 0));
        byte[] clip = new byte[OverwatchProtocol.FRAGMENT * 3 + 5];
        new Random(3).nextBytes(clip);
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(clip);
        OverwatchProtocol.ClipInfo info = new OverwatchProtocol.ClipInfo(TOKEN, 5, clip.length, sha, 4000, STARTED, false, 640, 360);
        receive(hub, info(info));
        pull(hub, clip, 5);
        ClipIndex.Clip c = hub.index().clips("MobileAlpha").get(0);
        assertNotNull(c.fullClip());
        assertEquals(OverwatchHub.hex(sha), c.meta().getProperty(OverwatchHub.CHECKED_SHA));
        Files.write(c.fullClip(), new byte[clip.length]); // were it hashed again, it would no longer match
        sent.clear();
        receive(hub, info(info));
        assertEquals(List.of(OverwatchProtocol.CMD_CLIP_DONE), commandsSent(), "told DONE from the kept hash");
    }

    /** M10: a clip that comes damaged three times is given up, and isn't fetched again when announced again. */
    @Test
    void aClipThatKeepsComingDamagedIsGivenUp() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 6, 0));
        byte[] clip = new byte[OverwatchProtocol.FRAGMENT * 2];
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(new byte[]{1}); // never matches
        OverwatchProtocol.ClipInfo info = new OverwatchProtocol.ClipInfo(TOKEN, 6, clip.length, sha, 2000, STARTED, false, 640, 360);
        receive(hub, info(info));
        long t = 2000;
        for (int round = 0; round < 6; round++) t = pull(hub, clip, 6, t);
        assertTrue(dones().contains(0), "the phone is told: not had (ok 0)");
        assertNotNull(hub.index().clips("MobileAlpha").get(0).meta().getProperty(OverwatchHub.BAD_SHA));
        sent.clear();
        receive(hub, info(info));
        for (long u = t; u < t + 5000; u += 500) hub.tickNow(u);
        assertTrue(commandsSent().stream().noneMatch(c -> c == OverwatchProtocol.CMD_CLIP_GET), "not fetched a fourth time");
    }

    /** L11 (1.0.49): a removed event's clip arriving later doesn't bring the event back as a new one. */
    @Test
    void aRemovedEventStaysRemovedWhenItsClipComes() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 7, 0));
        String id = hub.index().clips("MobileAlpha").get(0).id();
        hub.index().hide("MobileAlpha", id);
        OverwatchHub later = hub(); // a restart: the hub knows nothing of the event but its files
        byte[] clip = new byte[100];
        receive(later, info(new OverwatchProtocol.ClipInfo(TOKEN, 7, clip.length, MessageDigest.getInstance("SHA-256").digest(clip), 1000, STARTED,
                false, 640, 360)));
        assertTrue(later.index().clips("MobileAlpha").isEmpty(), "still off the list");
        assertEquals(1, later.index().clips("MobileAlpha", true).size(), "one event, not a second one");
        later.close();
    }

    /**
     * M9 (1.0.49): the pilot's DISMISS never runs on the caller's thread (the Swing thread), even with the hub's
     * queue full of a preview's datagrams - before, it ran there, beside the hub's own thread on its plain HashMap.
     */
    @Test
    void aClickIsNeverRunOnTheCallersThreadEvenWithTheQueueFull() throws Exception {
        java.util.concurrent.CountDownLatch inNews = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        List<String> logThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
        OverwatchHub hub = new OverwatchHub(new ClipIndex(dir), sent::add);
        hub.setListener(new OverwatchHub.Listener() {
            @Override
            public void onNews(String drone) {
                inNews.countDown();
                try {
                    release.await(); // the hub's thread busy: its queue fills
                } catch (InterruptedException ignored) {
                }
            }

            @Override
            public void onDropped(String drone, int reason, int batteryPercent, int tempDeci) {
            }

            @Override
            public void onClips(String drone) {
            }

            @Override
            public void log(String line) {
                if (line.contains("dismissed")) logThreads.add(Thread.currentThread().getName());
            }
        });
        hub.watch("MobileAlpha", TOKEN, null);
        hub.start();
        try {
            byte[] a = alert(TOKEN, 9, 0);
            hub.offer(a, a.length, InetAddress.getLoopbackAddress(), 40000);
            assertTrue(inNews.await(5, java.util.concurrent.TimeUnit.SECONDS));
            byte[] stranger = alert(0x1234, 1, 0);
            for (int i = 0; i < 5000; i++) hub.offer(stranger, stranger.length, InetAddress.getLoopbackAddress(), 40000);
            assertTrue(hub.dropped() > 0, "the queue is full");
            hub.dismiss("MobileAlpha");
            assertTrue(logThreads.isEmpty(), "not run here, at once: " + logThreads);
            release.countDown();
            for (int i = 0; i < 100 && logThreads.isEmpty(); i++) Thread.sleep(50);
            assertEquals(List.of("overwatch"), logThreads, "run on the hub's own thread");
        } finally {
            release.countDown();
            hub.close();
        }
    }

    /** pc.38's 60 s segments: filed under the one event, each part kept and confirmed by its own start, played in order. */
    @Test
    void anEventsSegmentsAreOneEventWithItsPartsInOrder() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 12, 0));
        long t = 30_000;
        for (int part = 1; part <= 2; part++) {
            byte[] clip = new byte[OverwatchProtocol.FRAGMENT + 100 * part];
            new Random(part).nextBytes(clip);
            long segStart = STARTED + (part - 1) * 60_000L;
            receive(hub, info(new OverwatchProtocol.ClipInfo(TOKEN, 12, clip.length, MessageDigest.getInstance("SHA-256").digest(clip),
                    part == 1 ? 60_000 : 20_000, segStart, false, 640, 360, part, STARTED)));
            sent.clear();
            for (int round = 0; round < 5 && dones().isEmpty(); round++) t = pull(hub, clip, 12, t);
            assertEquals(List.of(1), dones(), "part " + part + " checked");
            OverwatchProtocol.Command done = null;
            for (byte[] p : sent) {
                OverwatchProtocol.Command c = OverwatchProtocol.readCommand(p, p.length);
                if (c != null && c.cmd == OverwatchProtocol.CMD_CLIP_DONE) done = c;
            }
            assertEquals(segStart, done.clipStartedAt, "the DONE names the segment, so the phone deletes exactly it");
        }
        List<ClipIndex.Clip> clips = hub.index().clips("MobileAlpha");
        assertEquals(1, clips.size(), "one event on the list, not one per segment");
        ClipIndex.Clip c = clips.get(0);
        assertEquals(2, c.parts().size());
        assertTrue(c.parts().get(1).getFileName().toString().endsWith("-part2.mp4"));
        assertEquals(80_000, c.durationMs(), "the parts added up");
        assertEquals(c.parts().get(0), c.fullClip());
    }

    private static byte[] info(OverwatchProtocol.ClipInfo info) {
        return OverwatchProtocol.clipInfo(info);
    }

    private List<Integer> dones() {
        List<Integer> out = new ArrayList<>();
        for (byte[] p : sent) {
            OverwatchProtocol.Command c = OverwatchProtocol.readCommand(p, p.length);
            if (c != null && c.cmd == OverwatchProtocol.CMD_CLIP_DONE) out.add((int) c.offset);
        }
        return out;
    }

    private void pull(OverwatchHub hub, byte[] clip, int event) throws Exception {
        long t = 30_000;
        for (int round = 0; round < 10 && !commandsSent().contains(OverwatchProtocol.CMD_CLIP_DONE); round++) t = pull(hub, clip, event, t);
    }

    /** One tick and the phone's answer to what it asked: the chunks. Returns the next tick's time. */
    private long pull(OverwatchHub hub, byte[] clip, int event, long t) throws Exception {
        int from = sent.size();
        hub.tickNow(t);
        for (byte[] p : new ArrayList<>(sent.subList(from, sent.size()))) {
            OverwatchProtocol.Command c = OverwatchProtocol.readCommand(p, p.length);
            if (c == null || c.cmd != OverwatchProtocol.CMD_CLIP_GET) continue;
            for (int i = 0; i < c.count; i++) {
                long off = c.offset + (long) i * OverwatchProtocol.FRAGMENT;
                if (off >= clip.length) break;
                int n = (int) Math.min(OverwatchProtocol.FRAGMENT, clip.length - off);
                receive(hub, OverwatchProtocol.clipChunk(TOKEN, event, off, clip, (int) off, n));
            }
        }
        return t + 2000;
    }

    @Test
    void aClipWhoseAlertNeverCameStillFindsItsPlace() throws Exception {
        OverwatchHub hub = hub();
        byte[] clip = new byte[100];
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(clip);
        receive(hub, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 8, clip.length, sha, 3000, STARTED + 99_000, false, 640, 360)));
        assertEquals(1, hub.index().clips("MobileAlpha").size());
        hub.close(); // lets go of the .part file
    }

    @Test
    void theFloorsDropIsToldOnceAndAcknowledged() throws Exception {
        OverwatchHub hub = hub();
        byte[] st = OverwatchProtocol.status(TOKEN, OverwatchProtocol.STATE_DROPPED, PowerFloor.REASON_BATTERY, 18, 310);
        receive(hub, st);
        receive(hub, st);
        assertEquals(List.of("MobileAlpha:" + PowerFloor.REASON_BATTERY), dropped);
        assertEquals(2, commandsSent().stream().filter(c -> c == OverwatchProtocol.CMD_STATUS_ACK).count());
    }

    /** Phone app pc.49: its overwatch camera gave no pictures and the phone opens it again - said once, and when it's back. */
    @Test
    void aBlindCameraIsToldOnceAndItsReturnOnce() throws Exception {
        OverwatchHub hub = hub();
        byte[] blind = OverwatchProtocol.status(TOKEN, OverwatchProtocol.STATE_BLIND, OverwatchProtocol.BLIND_LOST, 80, 300);
        receive(hub, blind);
        receive(hub, blind);
        assertEquals(List.of("MobileAlpha: overwatch camera stopped (camera taken away). Phone restarts it."), logs);
        byte[] back = OverwatchProtocol.status(TOKEN, OverwatchProtocol.STATE_WATCHING, 0, 80, 300);
        receive(hub, back);
        receive(hub, back);
        assertEquals("MobileAlpha: overwatch camera back.", logs.get(1));
        assertEquals(2, logs.size());
        assertTrue(dropped.isEmpty());
        assertEquals(4, commandsSent().stream().filter(c -> c == OverwatchProtocol.CMD_STATUS_ACK).count(), "each one acknowledged");
    }

    @Test
    void connectWakesThePhoneAndClosesTheCard() throws Exception {
        OverwatchHub hub = hub();
        hub.nowOverride = 1000;
        receive(hub, alert(TOKEN, 6, 0));
        sent.clear();
        hub.connect("MobileAlpha");
        assertTrue(commandsSent().contains(OverwatchProtocol.CMD_CONNECT));
        assertEquals(AlertCard.Shown.HIDDEN, hub.views().get(0).shown());
    }
}
