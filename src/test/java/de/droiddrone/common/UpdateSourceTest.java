package de.droiddrone.common;

import de.droiddrone.pc.setup.ApkShare;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static de.droiddrone.common.UpdateSource.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A remote phone-app update is taken only from the project's own GitHub release downloads, and installed only when the
 * downloaded APK is this app and its own version code is newer than the installed one's - the version the PC names no
 * longer decides. Before, the phone fetched whatever address a REMOTE_UPDATE named and went by the version it claimed.
 */
class UpdateSourceTest {
    private static final String OWN = "de.droiddrone.flight.pc";

    @Test
    void thePcsPinnedAppAndTheLatestReleasesAssetAreFromTheReleases() {
        assertTrue(fromReleases(ApkShare.APK_URL), ApkShare.APK_URL);
        assertTrue(fromReleases(RELEASE_DOWNLOADS + "v1.0.71/DRAUPNE-Flight-2.3.0-pc.55.apk"), "the phone's own update check's asset");
        assertTrue(fromReleases(RELEASE_DOWNLOADS + "flight-2.3.0-pc.56-beta/DRAUPNE-Flight-2.3.0-pc.56-beta.apk"));
    }

    @Test
    void anythingElseIsNot() {
        for (String url : new String[]{null, "", RELEASE_DOWNLOADS, RELEASE_DOWNLOADS + "v1/", RELEASE_DOWNLOADS + "v1/x.zip",
                "http://github.com/MelonNO/draupne-link/releases/download/v1/x.apk", "https://github.com/MelonNO/other/releases/download/v1/x.apk",
                "https://github.com.example.net/MelonNO/draupne-link/releases/download/v1/x.apk",
                "https://user@github.com/MelonNO/draupne-link/releases/download/v1/x.apk",
                "https://github.com:8443/MelonNO/draupne-link/releases/download/v1/x.apk",
                "HTTPS://GITHUB.COM/MelonNO/draupne-link/releases/download/v1/x.apk",
                "http://10.0.2.2:8765/DRAUPNE-Flight-2.3.0-pc.55.apk",
                RELEASE_DOWNLOADS + "../../../somebody/else/releases/download/v1/x.apk", RELEASE_DOWNLOADS + "v1/../x.apk",
                RELEASE_DOWNLOADS + "v1/a/x.apk", RELEASE_DOWNLOADS + "v1/x.apk?y=1", RELEASE_DOWNLOADS + "v1/x.apk#y",
                RELEASE_DOWNLOADS + "v1/x%2e.apk", RELEASE_DOWNLOADS + "v1/x .apk", RELEASE_DOWNLOADS + "v1/x\n.apk",
                RELEASE_DOWNLOADS + "v1\\x.apk", RELEASE_DOWNLOADS + "/v1/x.apk", RELEASE_DOWNLOADS + "v1/.apk",
                RELEASE_DOWNLOADS + "v1/x.apk/", RELEASE_DOWNLOADS + "v1/" + "x".repeat(300) + ".apk"}) {
            assertFalse(fromReleases(url), String.valueOf(url));
        }
    }

    @Test
    void onlyANewerBuildOfThisAppIsInstalled() {
        assertTrue(newer(OWN, 2030056, OWN, 2030055));
        assertFalse(newer(OWN, 2030055, OWN, 2030055), "the same build");
        assertFalse(newer(OWN, 2030054, OWN, 2030055), "an older one, whatever version the PC named");
        assertFalse(newer("de.droiddrone.flight", 9_999_999, OWN, 2030055), "another app");
        assertFalse(newer(null, 2030056, OWN, 2030055), "not readable as an APK");
        assertFalse(newer(OWN, -1, OWN, 2030055), "no version code read");
    }

    /** The phone app cannot be run here: its sources are read, as UpdateWithdrawTest does. */
    @Test
    void thePhoneAsksBothBeforeTheDownloadAndBeforeTheInstall() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        Path flight = root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight");
        String remote = text(flight.resolve("RemoteUpdate.java")), updater = text(flight.resolve("AppUpdater.java"));
        String request = remote.substring(remote.indexOf("static void request(Context c, String version, String url, String sha256, Msp msp, Mavlink mavlink) {"));
        request = request.substring(0, request.indexOf("\n    }"));
        assertTrue(request.contains("if (!UpdateSource.fromReleases(url)) {"), "the address, before anything is fetched");
        assertTrue(request.indexOf("UpdateSource.fromReleases(url)") < request.indexOf("new Thread("));
        String install = updater.substring(updater.indexOf("static void downloadAndInstallRemote("));
        install = install.substring(0, install.indexOf("\n    }"));
        int check = install.indexOf("checkNewerBuild(c, apk);");
        assertTrue(check > install.indexOf("download(found, apk,") && check < install.indexOf("installing.run();"), "read from the file, after the download, before the installer");
        String newer = updater.substring(updater.indexOf("private static void checkNewerBuild(Context c, File apk) throws IOException {"));
        newer = newer.substring(0, newer.indexOf("\n    }"));
        assertTrue(newer.contains("getPackageArchiveInfo(apk.getPath(), 0)") && newer.contains("UpdateSource.newer("));
        // the project's own repository, as the phone's update check asks it
        String check2 = text(flight.resolve("UpdateCheck.java"));
        String repo = RELEASE_DOWNLOADS.substring("https://github.com/".length(), RELEASE_DOWNLOADS.indexOf("/releases/"));
        assertTrue(check2.contains("https://api.github.com/repos/" + repo + "/releases/latest"));
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
