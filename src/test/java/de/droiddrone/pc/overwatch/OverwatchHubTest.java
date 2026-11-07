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
    private final List<String> news = new ArrayList<>(), dropped = new ArrayList<>();

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
