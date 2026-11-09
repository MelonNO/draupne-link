package de.droiddrone.pc.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TourTracesTest {
    @Test
    void theTourIsTakenOffAndThePilotsOwnStays(@TempDir Path dir) throws Exception {
        Path videos = Files.createDirectories(dir.resolve("Videos"));
        Path clips = Files.createDirectories(dir.resolve("Overwatch"));
        Path tracks = Files.createDirectories(dir.resolve("Tracks"));
        Path lastPosition = tracks.resolve("last-position.txt");
        Files.writeString(lastPosition, "the pilot's last flight");
        Path ownVideo = Files.writeString(videos.resolve("flight-own.mkv"), "own");
        Path ownClip = Files.writeString(Files.createDirectories(clips.resolve("MobileAlpha")).resolve("own.mp4"), "own");
        // the pilot's own virtual fleet once had a MobileZulu: its old clip stays
        Path oldZulu = Files.createDirectories(clips.resolve("MobileZulu")).resolve("old.mp4");
        Files.writeString(oldZulu, "old");
        Files.setAttribute(oldZulu, "creationTime", java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        Files.setAttribute(ownVideo, "creationTime", java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));

        Map<String, String> settings = new HashMap<>();
        settings.put(de.droiddrone.pc.Names.PHONE_NAME_KEY, "MobileAlpha");
        settings.put("ip", "100.64.0.1");
        settings.put("audio.muted.MobileZulu", "false");
        settings.put("osd.layout", "mine");
        TourTraces t = new TourTraces(List.of("MobileZulu", "MobileYankee"), System.currentTimeMillis(), settings, lastPosition);

        assertTrue(t.isTour("mobilezulu"));
        assertFalse(t.isTour("MobileAlpha"));
        // the tour's flight: connected to Zulu, its files made
        t.connected(System.currentTimeMillis());
        Path log = Files.writeString(dir.resolve("link-tour.csv"), "x");
        t.note(log);
        Path tourVideo = Files.writeString(videos.resolve("flight-tour.mkv"), "tour");
        t.note(tourVideo); // as the recording's file listener does, by its exact path
        Path ownDuringTour = Files.writeString(videos.resolve("flight-own-same-time.mkv"), "own, not noted");
        Path tourClip = Files.writeString(clips.resolve("MobileZulu").resolve("event.mp4"), "tour");
        Files.writeString(lastPosition, "the tour's flight");
        Path track = Files.writeString(tracks.resolve("track-tour.gpx"), "tour");
        t.note(track);
        t.disconnected(System.currentTimeMillis());

        Map<String, String> now = new HashMap<>(settings);
        now.put(de.droiddrone.pc.Names.PHONE_NAME_KEY, "MobileZulu");
        now.put("ip", "127.0.0.11");
        now.put("audio.muted.MobileZulu", "true");
        now.put("names.MobileYankee", "virtual entry");
        now.put("osd.layout", "changed meanwhile by the pilot");
        Map<String, String> restore = t.settingsToRestore(now);
        assertEquals("MobileAlpha", restore.get(de.droiddrone.pc.Names.PHONE_NAME_KEY));
        assertEquals("100.64.0.1", restore.get("ip"));
        assertEquals("false", restore.get("audio.muted.MobileZulu"));
        assertEquals("", restore.get("names.MobileYankee"), "a key that came with the tour is taken off");
        assertFalse(restore.containsKey("osd.layout"), "the pilot's own settings are not touched");

        List<Path> gone = t.filesToDelete(videos, clips, System.currentTimeMillis());
        assertTrue(gone.containsAll(List.of(log, track, tourVideo, tourClip)), gone.toString());
        assertFalse(gone.contains(ownVideo));
        assertFalse(gone.contains(ownDuringTour), "only noted recordings go, never one by its time alone");
        assertFalse(gone.contains(ownClip));
        assertFalse(gone.contains(oldZulu), "a clip from before the tour stays");

        t.restoreLastPosition();
        assertEquals("the pilot's last flight", Files.readString(lastPosition));
    }

    @Test
    void withoutATourFlightNothingIsTakenFromVideosOrLastPosition(@TempDir Path dir) throws Exception {
        Path videos = Files.createDirectories(dir.resolve("Videos"));
        Path lastPosition = dir.resolve("last-position.txt");
        TourTraces t = new TourTraces(List.of("MobileZulu"), System.currentTimeMillis(), Map.of(), lastPosition);
        Path recent = Files.writeString(videos.resolve("flight-own-now.mkv"), "own, made during the tour");
        Files.writeString(lastPosition, "written by the pilot's own flight during the tour");
        assertTrue(t.filesToDelete(videos, null, System.currentTimeMillis()).isEmpty());
        assertTrue(Files.exists(recent));
        t.restoreLastPosition();
        assertTrue(Files.exists(lastPosition), "not the tour's: no tour flight happened");
    }
}
