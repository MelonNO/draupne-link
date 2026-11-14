package de.droiddrone.pc.fleet;

import de.droiddrone.common.MotionRecord;
import de.droiddrone.pc.PcConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static de.droiddrone.testing.Waits.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The files of a virtual drone's overwatch clips (draupne-fleet-clip-*.mkv): one for each event, deleted once the PC
 * has pulled it. A clip nobody pulled stayed for good - the drone stopped while the event was recorded, or with the
 * clip waiting for a PC that never asked - and hundreds of them piled up in the temp folder. A stopping drone now
 * takes its clips with it. The drone here is never started (no link, no tick): the detector's verdict is handed to
 * its overwatch as VirtualMotionWatch does, the clips go into the test's own folder.
 */
class OverwatchClipFilesTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private VirtualDrone drone;

    @AfterEach
    void tearDown() {
        if (drone != null) drone.stop();
    }

    /** A drone overwatching with no PC to call (none flew it), its clips in {@code dir}. */
    private VirtualOverwatch overwatching(Path dir) throws Exception {
        drone = new VirtualDrone(0, "MobileZulu", PcConfig.defaults().getKey(), InetAddress.getLoopbackAddress(), Fleet.DEFAULT_LAT,
                Fleet.DEFAULT_LON, 0, new FlightModel.Setup(), log::add);
        drone.overwatch.clipsIn(dir);
        drone.overwatch.configure(2, 0, 4711);
        drone.overwatch.begin(null);
        return drone.overwatch;
    }

    /**
     * What the detector reports of something travelling in the middle of the picture: an event starts, with its clip's
     * file, and its video runs for a moment. (How much of it is in the file can't be asked: the recorder writes a
     * cluster of seconds at a time, and Windows gives an open file's size as it was.)
     */
    private void motion(VirtualOverwatch overwatch, Path dir, int clipsThen) throws Exception {
        MotionRecord.Report report = new MotionRecord.Report(MotionRecord.FLAG_ACTIVE, 70,
                List.of(new MotionRecord.Box(1, 0.45f, 0.4f, 0.1f, 0.2f, 0, 0.9f, 0, MotionRecord.KIND_MOVING)));
        overwatch.onPicture(new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), report, System.currentTimeMillis());
        waitFor(() -> clips(dir).size() == clipsThen, 5000, "the event's clip file", log);
        Thread.sleep(700); // some ten pictures at the event video's 15 a second
    }

    private static List<Path> clips(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("draupne-fleet-clip-")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aClipStillBeingRecordedGoesWhenTheDroneStops(@TempDir Path dir) throws Exception {
        motion(overwatching(dir), dir, 1);
        drone.stop();
        // the video thread closes the file, then deletes it instead of keeping it for a PC
        waitFor(() -> clips(dir).isEmpty(), 20_000, "the clip's file deleted after the stop", log);
    }

    @Test
    void aClipThePcNeverPulledGoesWhenTheDroneStops(@TempDir Path dir) throws Exception {
        VirtualOverwatch overwatch = overwatching(dir);
        motion(overwatch, dir, 1);
        overwatch.end("woken"); // the event ends with it: its clip is finished and waits for the PC
        waitFor(() -> overwatch.status().contains("clip waiting"), 20_000, "the clip waiting for the PC", log);
        assertEquals(1, clips(dir).size(), "kept while it waits: " + log);
        drone.stop();
        assertEquals(List.of(), clips(dir), "deleted with the drone's stop: " + log);
    }

    /** A second event's clip takes the place of the first's, never pulled: the first goes then, the second with the stop. */
    @Test
    void aNewerClipTakesTheOldersPlace(@TempDir Path dir) throws Exception {
        VirtualOverwatch overwatch = overwatching(dir);
        motion(overwatch, dir, 1);
        overwatch.end("woken");
        waitFor(() -> overwatch.status().contains("clip waiting"), 20_000, "the first clip waiting for the PC", log);
        Path first = clips(dir).get(0);
        overwatch.begin(null);
        motion(overwatch, dir, 2);
        overwatch.end("woken");
        waitFor(() -> !Files.exists(first), 20_000, "the first clip deleted when the second waits", log);
        assertEquals(1, clips(dir).size(), "the newest only: " + log);
        drone.stop();
        assertEquals(List.of(), clips(dir), "deleted with the drone's stop: " + log);
    }
}
