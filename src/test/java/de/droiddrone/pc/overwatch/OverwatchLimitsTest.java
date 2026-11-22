package de.droiddrone.pc.overwatch;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.pc.map.CameraFootprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What this PC keeps of what an overwatch phone sends, with a made-up phone: a clip announced larger than any clip
 * can be is refused; an event keeps so many pictures from before its alert and no more; and with the disk nearly
 * full nothing is written - the clip waits and comes when there is room again. Each is said in the log, the clip's
 * on the card too.
 */
class OverwatchLimitsTest {
    private static final int TOKEN = 0x51EE7;
    private static final long STARTED = 1_762_500_000_000L;
    private static final long ROOM = 500_000_000_000L;

    @TempDir
    Path dir;

    private final List<byte[]> sent = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private long free = ROOM;
    private final List<OverwatchHub> made = new ArrayList<>();

    /** Lets go of the clips' .part files, so the test's folder can be cleared away. */
    @org.junit.jupiter.api.AfterEach
    void closeHubs() {
        for (OverwatchHub hub : made) hub.close();
    }

    private OverwatchHub hub() {
        OverwatchHub hub = new OverwatchHub(new ClipIndex(dir), sent::add);
        hub.setListener(new OverwatchHub.Listener() {
            @Override
            public void onNews(String drone) {
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
        hub.freeBytesOverride = () -> free;
        made.add(hub);
        hub.watch("MobileAlpha", TOKEN, new CameraFootprint.Pose(59.9, 10.7, 120, 45, 0, 0, 10, 70, 40));
        hub.nowOverride = 1000;
        return hub;
    }

    private static byte[] alert(int event) {
        return OverwatchProtocol.alert(new OverwatchProtocol.Alert(TOKEN, event, 0, 0, STARTED, 0.4f, 0.4f, 0.1f, 0.2f, 5f, 70f,
                599_000_000, 107_000_000, 30, 80, "MobileAlpha"));
    }

    private void receive(OverwatchHub hub, byte[] p) {
        hub.handleNow(p, InetAddress.getLoopbackAddress(), 40000);
    }

    private List<OverwatchProtocol.Command> commands(int cmd) {
        List<OverwatchProtocol.Command> out = new ArrayList<>();
        for (byte[] p : sent) {
            OverwatchProtocol.Command c = OverwatchProtocol.readCommand(p, p.length);
            if (c != null && c.cmd == cmd) out.add(c);
        }
        return out;
    }

    private List<String> files(String ending) throws Exception {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(n -> n.endsWith(ending)).sorted().toList();
        }
    }

    private static byte[] jpeg(int seed) throws Exception {
        BufferedImage img = new BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB);
        img.setRGB(seed % 32, seed % 18, 0xffffff);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private void sendPicture(OverwatchHub hub, int event, int no, byte[] jpeg) throws Exception {
        ByteArrayOutputStream unit = new ByteArrayOutputStream();
        new DataOutputStream(unit).writeLong(STARTED - 60_000 + no * 500L);
        unit.write(jpeg);
        for (byte[] p : OverwatchProtocol.fragments(TOKEN, event, no, OverwatchProtocol.UNIT_PREROLL, unit.toByteArray())) receive(hub, p);
    }

    private void assertSaidOnce(String what) {
        List<String> said = logs.stream().filter(l -> l.contains(what)).toList();
        assertEquals(1, said.size(), "\"" + what + "\" said once in " + logs);
        assertTrue(said.get(0).length() <= 100, "a short line: " + said.get(0));
    }

    @Test
    void aClipAnnouncedLargerThanAnyClipCanBeIsRefused() throws Exception {
        OverwatchHub hub = hub();
        receive(hub, alert(5));
        receive(hub, OverwatchProtocol.clipEnded(TOKEN, 5, STARTED + 40_000, true));
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(new byte[]{1});
        // 4 GB less a byte: the most its packet can say (the size is 32 bits there); the phone's whole clip folder is 2 GB
        long announced = 0xFFFFFFFFL;
        assertTrue(announced > OverwatchHub.MAX_CLIP_BYTES);
        sent.clear();
        receive(hub, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 5, announced, sha, 40_000, STARTED, true, 1280, 720)));
        for (long t = 2000; t < 12_000; t += 100) hub.tickNow(t);
        assertEquals(List.of(), commands(OverwatchProtocol.CMD_CLIP_GET), "not a byte of it is asked for");
        List<OverwatchProtocol.Command> done = commands(OverwatchProtocol.CMD_CLIP_DONE);
        assertEquals(1, done.size(), "the phone is told at once");
        assertEquals(0, done.get(0).offset, "told it is not taken: the phone goes on to its next clip");
        assertEquals(List.of(), files(".part"), "nothing begun on disk");
        assertSaidOnce("clip refused");
        assertTrue(hub.views().get(0).clipLine().contains("refused"), "on the card: " + hub.views().get(0).clipLine());
        // announced again (the phone did not hear): told again, not said again, still nothing asked
        receive(hub, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 5, announced, sha, 40_000, STARTED, true, 1280, 720)));
        hub.tickNow(13_000);
        assertEquals(2, commands(OverwatchProtocol.CMD_CLIP_DONE).size());
        assertEquals(List.of(), commands(OverwatchProtocol.CMD_CLIP_GET));
        assertSaidOnce("clip refused");
        // a clip of the largest size a clip can have is taken
        sent.clear();
        receive(hub, alert(6));
        receive(hub, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 6, OverwatchHub.MAX_CLIP_BYTES, sha, 40_000, STARTED + 100_000, true, 1280, 720)));
        hub.tickNow(14_000);
        assertEquals(1, commands(OverwatchProtocol.CMD_CLIP_GET).size(), "at the limit it is fetched");
    }

    @Test
    void anEventKeepsSoManyPicturesFromBeforeItsAlertAndNoMore() throws Exception {
        OverwatchHub hub = hub();
        receive(hub, alert(3));
        for (int i = 0; i < OverwatchHub.MAX_PRE_ROLL_PICTURES + 30; i++) sendPicture(hub, 3, i, jpeg(i));
        assertEquals(OverwatchHub.MAX_PRE_ROLL_PICTURES, files(".jpg").size(), "as many as the limit, of " + (OverwatchHub.MAX_PRE_ROLL_PICTURES + 30) + " sent");
        assertEquals(OverwatchHub.MAX_PRE_ROLL_PICTURES, hub.views().get(0).preRolls());
        assertNotNull(hub.views().get(0).picture(), "the card shows the latest all the same");
        assertSaidOnce("pictures before the alert");
        // the next event has its own count
        receive(hub, OverwatchProtocol.alert(new OverwatchProtocol.Alert(TOKEN, 4, 0, 0, STARTED + 600_000, 0.4f, 0.4f, 0.1f, 0.2f, 5f, 70f,
                599_000_000, 107_000_000, 30, 80, "MobileAlpha")));
        for (int i = 0; i < 3; i++) sendPicture(hub, 4, i, jpeg(i));
        assertEquals(OverwatchHub.MAX_PRE_ROLL_PICTURES + 3, files(".jpg").size());
    }

    @Test
    void anEventsPicturesAreKeptUpToASizeInAll() throws Exception {
        OverwatchHub hub = hub();
        receive(hub, alert(3));
        // five pictures of 400 KB each (a picture's bytes, then filler): two fit in the event's megabyte
        byte[] big = new byte[400 * 1024];
        new Random(3).nextBytes(big);
        byte[] picture = jpeg(1);
        System.arraycopy(picture, 0, big, 0, picture.length);
        assertTrue(2 * big.length <= OverwatchHub.MAX_PRE_ROLL_BYTES && 3 * big.length > OverwatchHub.MAX_PRE_ROLL_BYTES);
        for (int i = 0; i < 5; i++) sendPicture(hub, 3, i, big);
        assertEquals(2, files(".jpg").size());
        assertSaidOnce("pictures before the alert");
    }

    @Test
    void withTheDiskNearlyFullNothingIsWrittenAndTheClipWaitsForRoom() throws Exception {
        OverwatchHub hub = hub();
        free = OverwatchHub.DISK_FLOOR_BYTES - 1;
        receive(hub, alert(5));
        for (int i = 0; i < 3; i++) sendPicture(hub, 5, i, jpeg(i));
        assertEquals(List.of(), files(".jpg"), "no picture kept");
        assertNotNull(hub.views().get(0).picture(), "the alert rings and its card shows the picture all the same");
        receive(hub, OverwatchProtocol.clipEnded(TOKEN, 5, STARTED + 40_000, true));
        byte[] clip = new byte[OverwatchProtocol.FRAGMENT * 20 + 10];
        new Random(7).nextBytes(clip);
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(clip);
        byte[] info = OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 5, clip.length, sha, 40_000, STARTED, true, 1280, 720));
        sent.clear();
        receive(hub, info);
        for (long t = 2000; t < 8000; t += 100) hub.tickNow(t);
        assertEquals(List.of(), commands(OverwatchProtocol.CMD_CLIP_GET), "nothing asked for while there is no room");
        assertEquals(List.of(), commands(OverwatchProtocol.CMD_CLIP_DONE), "and the phone keeps its clip: nothing said of it");
        assertEquals(List.of(), files(".part"));
        assertEquals(List.of(), files(".mp4"));
        assertSaidOnce("LOW DISK");
        assertTrue(hub.views().get(0).clipLine().contains("LOW DISK"), "on the card: " + hub.views().get(0).clipLine());

        // room again: the phone announces its clip again, as it does until it is fetched - and now it comes
        free = ROOM;
        hub.nowOverride = 10_000;
        hub.tickNow(10_000);
        receive(hub, info);
        for (int round = 0; round < 10 && commands(OverwatchProtocol.CMD_CLIP_DONE).isEmpty(); round++) {
            sent.clear();
            hub.nowOverride = 12_000 + round * 2000L;
            hub.tickNow(hub.nowOverride);
            for (OverwatchProtocol.Command c : commands(OverwatchProtocol.CMD_CLIP_GET)) {
                for (int i = 0; i < c.count; i++) {
                    long off = c.offset + (long) i * OverwatchProtocol.FRAGMENT;
                    int n = (int) Math.min(OverwatchProtocol.FRAGMENT, clip.length - off);
                    receive(hub, OverwatchProtocol.clipChunk(TOKEN, 5, off, clip, (int) off, n));
                }
            }
        }
        assertEquals(1, commands(OverwatchProtocol.CMD_CLIP_DONE).size());
        assertEquals(1, commands(OverwatchProtocol.CMD_CLIP_DONE).get(0).offset, "taken");
        assertArrayEquals(clip, Files.readAllBytes(hub.index().clips("MobileAlpha").get(0).fullClip()));
        assertSaidOnce("room again");
    }

    @Test
    void aClipThatWouldLeaveLessThanTheFloorWaitsToo() throws Exception {
        OverwatchHub hub = hub();
        receive(hub, alert(5));
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(new byte[]{1});
        long size = 300L << 20;
        free = OverwatchHub.DISK_FLOOR_BYTES + size - 1; // room now, not once the clip is here
        sent.clear();
        receive(hub, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 5, size, sha, 40_000, STARTED, true, 1280, 720)));
        hub.tickNow(2000);
        assertEquals(List.of(), commands(OverwatchProtocol.CMD_CLIP_GET));
        assertEquals(List.of(), files(".part"));
        assertTrue(hub.views().get(0).clipLine().contains("LOW DISK"), hub.views().get(0).clipLine());
        free = OverwatchHub.DISK_FLOOR_BYTES + size;
        hub.nowOverride = 5000;
        receive(hub, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 5, size, sha, 40_000, STARTED, true, 1280, 720)));
        hub.tickNow(5000);
        assertEquals(1, commands(OverwatchProtocol.CMD_CLIP_GET).size(), "it fits: fetched");
    }

    @Test
    void aClipUnderWayStopsBeingAskedForWhenTheDiskRunsLowAndGoesOnAfter() throws Exception {
        OverwatchHub hub = hub();
        receive(hub, alert(5));
        byte[] clip = new byte[OverwatchProtocol.FRAGMENT * 200];
        new Random(9).nextBytes(clip);
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(clip);
        receive(hub, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(TOKEN, 5, clip.length, sha, 40_000, STARTED, true, 1280, 720)));
        sent.clear();
        hub.tickNow(2000);
        OverwatchProtocol.Command first = commands(OverwatchProtocol.CMD_CLIP_GET).get(0);
        for (int i = 0; i < first.count; i++) {
            long off = first.offset + (long) i * OverwatchProtocol.FRAGMENT;
            receive(hub, OverwatchProtocol.clipChunk(TOKEN, 5, off, clip, (int) off, OverwatchProtocol.FRAGMENT));
        }
        long partSize = Files.size(Files.walk(dir).filter(p -> p.toString().endsWith(".part")).findFirst().orElseThrow());
        assertEquals((long) first.count * OverwatchProtocol.FRAGMENT, partSize);

        free = OverwatchHub.DISK_FLOOR_BYTES - 1;
        sent.clear();
        for (long t = 4000; t < 12_000; t += 100) {
            hub.nowOverride = t;
            hub.tickNow(t);
        }
        assertEquals(List.of(), commands(OverwatchProtocol.CMD_CLIP_GET), "no more asked for");
        // a chunk still on its way is not written either
        receive(hub, OverwatchProtocol.clipChunk(TOKEN, 5, partSize, clip, (int) partSize, OverwatchProtocol.FRAGMENT));
        assertEquals(partSize, Files.size(Files.walk(dir).filter(p -> p.toString().endsWith(".part")).findFirst().orElseThrow()));
        assertTrue(hub.views().get(0).clipLine().contains("LOW DISK"), hub.views().get(0).clipLine());
        assertSaidOnce("LOW DISK");

        free = ROOM;
        sent.clear();
        hub.nowOverride = 14_000;
        hub.tickNow(14_000);
        hub.nowOverride = 16_000;
        hub.tickNow(16_000);
        List<OverwatchProtocol.Command> again = commands(OverwatchProtocol.CMD_CLIP_GET);
        assertFalse(again.isEmpty(), "asked for again once there is room");
        assertEquals(partSize, again.get(0).offset, "from where it stopped");
    }
}
