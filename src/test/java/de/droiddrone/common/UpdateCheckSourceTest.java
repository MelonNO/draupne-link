package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone's own update check offers, and its tap downloads, an APK only from the project's release downloads
 * (UpdateSource) - before, whatever download address GitHub's answer named for the asset. (UpdateSourceTest: the rule.)
 */
class UpdateCheckSourceTest {
    /** The phone app cannot be run here: its sources are read, as UpdateWithdrawTest does. */
    @Test
    void theCheckOffersAndTheTapFetchesOnlyFromTheReleases() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        Path flight = root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight");
        String check = text(flight.resolve("UpdateCheck.java")), updater = text(flight.resolve("AppUpdater.java"));
        String newer = check.substring(check.indexOf("static Found newerThan(String current) {"));
        newer = newer.substring(0, newer.indexOf("\n    }"));
        int url = newer.indexOf("String url = a.getString(\"browser_download_url\");");
        int rule = newer.indexOf("if (!UpdateSource.fromReleases(url)) continue;");
        assertTrue(url > 0 && rule > url && rule < newer.indexOf("return new Found("), "the asset's address, asked before it is offered");
        assertTrue(newer.contains("return new Found(m.group(1), url, "));
        String tap = updater.substring(updater.indexOf("static void downloadAndInstall(Context c, UpdateCheck.Found found, Progress progress) throws IOException {"));
        tap = tap.substring(0, tap.indexOf("\n    }"));
        assertTrue(tap.indexOf("if (!UpdateSource.fromReleases(found.url))") >= 0
                && tap.indexOf("if (!UpdateSource.fromReleases(found.url))") < tap.indexOf("download(found, apk,"), "and the tap's download");
    }

    @Test
    void anyTextIsAnsweredWithoutAThrow() {
        Random r = new Random(3);
        String chars = "htps:/github.comMelonNOdraupne-linkreleasesdownload0123456789.-_?#%@\\ \né";
        for (int i = 0; i < 20_000; i++) {
            StringBuilder sb = new StringBuilder(r.nextBoolean() ? UpdateSource.RELEASE_DOWNLOADS : "");
            for (int n = r.nextInt(60); n > 0; n--) sb.append(chars.charAt(r.nextInt(chars.length())));
            if (r.nextBoolean()) sb.append(".apk");
            UpdateSource.fromReleases(sb.toString());
        }
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
