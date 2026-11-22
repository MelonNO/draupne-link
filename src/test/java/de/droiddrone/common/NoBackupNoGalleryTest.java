package de.droiddrone.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone app's data goes to no backup and no device-to-device transfer (its settings hold the link key, the PC's
 * address, the phone's name), and its recordings and overwatch clips are not in the phone's gallery (a .nomedia in
 * each folder; the files stay where they were, the PC's clip download reads them as before).
 */
class NoBackupNoGalleryTest {
    @Test
    void aFolderIsMarkedOnceAndMadeIfItIsNotThere(@TempDir Path dir) throws Exception {
        File clips = dir.resolve("Overwatch").toFile();
        assertTrue(NoMedia.mark(clips), "made, with its marker");
        assertTrue(new File(clips, ".nomedia").isFile());
        assertFalse(NoMedia.mark(clips), "there already: nothing for the media index to look at again");
        assertFalse(NoMedia.mark(null));
        File file = dir.resolve("a file").toFile();
        assertTrue(file.createNewFile());
        assertFalse(NoMedia.mark(file), "not a folder");
    }

    @Test
    void theClipFolderWithItsMarkerStillHasItsClips(@TempDir Path dir) throws Exception {
        File folder = dir.resolve("Overwatch").toFile();
        assertTrue(folder.mkdirs());
        File clip = new File(folder, "20261122_101500_7_1.mp4");
        Files.write(clip.toPath(), new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p'});
        NoMedia.mark(folder);
        ClipStore store = new ClipStore(folder);
        List<String> said = new ArrayList<>();
        store.scan(said::add);
        assertFalse(said.stream().anyMatch(l -> l.contains(".nomedia")), "the marker is no clip: " + said);
        assertTrue(new File(folder, ".nomedia").isFile(), "and stays");
    }

    /** The phone app cannot be run here: its sources and its manifest are read, as ReleaseHasNoBenchCodeTest does. */
    @Test
    void thePhonesManifestAllowsNoBackupAndTheFoldersAreMarked() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        Path main = root.resolve("flight-app/overlay/Flight/src/main");
        String manifest = text(main.resolve("AndroidManifest.xml"));
        String application = manifest.substring(manifest.indexOf("<application"), manifest.indexOf(">", manifest.indexOf("<application")));
        assertTrue(application.contains("android:allowBackup=\"false\""), "no backup: " + application);
        assertTrue(application.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""), "and nothing to a new phone (Android 12 on)");
        String rules = text(main.resolve("res/xml/data_extraction_rules.xml"));
        for (String part : new String[]{"cloud-backup", "device-transfer"}) {
            String section = rules.substring(rules.indexOf("<" + part), rules.indexOf("</" + part + ">"));
            for (String domain : new String[]{"root", "file", "database", "sharedpref", "external"}) {
                assertTrue(section.contains("<exclude domain=\"" + domain + "\" path=\".\" />"), part + " leaves " + domain + " out");
            }
            assertFalse(section.contains("<include"), part + " takes nothing in");
        }
        // the two folders: overwatch's clips, and upstream's recordings (Mp4Recorder's own folder, "Video")
        Path flight = main.resolve("java/de/droiddrone/flight");
        String overwatch = text(flight.resolve("Overwatch.java")), service = text(flight.resolve("DDService.java"));
        String clips = overwatch.substring(overwatch.indexOf("static synchronized ClipStore clips(android.content.Context c) {"));
        clips = clips.substring(0, clips.indexOf("\n    }"));
        assertTrue(clips.contains("NoMedia.mark(store.dir())"), "the clip folder");
        String recorder = text(root.resolve("upstream/Flight/src/main/java/de/droiddrone/flight/Mp4Recorder.java"));
        assertTrue(recorder.contains("new File(context.getExternalMediaDirs()[0], \"Video\")"), "upstream's recordings' folder");
        assertTrue(service.contains("markNoMedia(new java.io.File(media[0], \"Video\"));"), "the recordings' folder");
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
