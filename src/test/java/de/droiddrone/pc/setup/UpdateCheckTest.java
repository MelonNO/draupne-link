package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckTest {
    @Test
    void versionsCompareNumberByNumber() {
        assertTrue(UpdateCheck.isNewer("1.0.6", "1.0.5"));
        assertTrue(UpdateCheck.isNewer("1.0.10", "1.0.9"), "not as text");
        assertTrue(UpdateCheck.isNewer("1.1", "1.0.9"));
        assertFalse(UpdateCheck.isNewer("1.0.5", "1.0.5"));
        assertFalse(UpdateCheck.isNewer("1.0.4", "1.0.5"));
        assertTrue(UpdateCheck.isNewer("2.3.0-pc.4", "2.3.0-pc.3"), "the phone app's versions");
        assertFalse(UpdateCheck.isNewer("2.3.0-pc.3", "2.3.0-pc.3"));
        assertFalse(UpdateCheck.isNewer("1.0.6", "dev"), "a development build never nags");
    }

    @Test
    void readsTheLatestReleaseFromGitHubsAnswer() {
        UpdateCheck.Release r = UpdateCheck.parse("""
                {"tag_name":"v1.0.6","name":"DroidDrone PC v1.0.6","html_url":"https://github.com/MelonNO/droiddrone-pc/releases/tag/v1.0.6",
                 "assets":[{"name":"DroidDrone-Flight-2.3.0-pc.4.apk"}]}""");
        assertEquals("1.0.6", r.version());
        assertEquals("https://github.com/MelonNO/droiddrone-pc/releases/tag/v1.0.6", r.pageUrl());
        assertFalse(r.canInstall(), "no installer in that release");
    }

    @Test
    void findsTheInstallerAndItsChecksum() {
        UpdateCheck.Release r = UpdateCheck.parse("""
                {"tag_name":"v1.0.8","html_url":"https://github.com/MelonNO/droiddrone-pc/releases/tag/v1.0.8","assets":[
                 {"name":"DroidDrone-PC-1.0.8-windows.zip","size":5,"digest":"sha256:%1$s",
                  "browser_download_url":"https://github.com/MelonNO/droiddrone-pc/releases/download/v1.0.8/DroidDrone-PC-1.0.8-windows.zip"},
                 {"name":"DroidDrone-PC-Setup-1.0.8.exe","size":89796608,"digest":"sha256:%2$s",
                  "browser_download_url":"https://github.com/MelonNO/droiddrone-pc/releases/download/v1.0.8/DroidDrone-PC-Setup-1.0.8.exe"}]}"""
                .formatted("0".repeat(64), "A8".repeat(32)));
        assertTrue(r.canInstall());
        assertEquals("https://github.com/MelonNO/droiddrone-pc/releases/download/v1.0.8/DroidDrone-PC-Setup-1.0.8.exe", r.installerUrl());
        assertEquals(89796608, r.installerSize());
        assertEquals("a8".repeat(32), r.installerSha256());
    }

    @Test
    void neverInstallsFromAnywhereElseOrWithoutAChecksum() {
        UpdateCheck.Release elsewhere = UpdateCheck.parse("""
                {"tag_name":"v9","html_url":"x","assets":[{"name":"DroidDrone-PC-Setup-9.exe","size":1,"digest":"sha256:%s",
                 "browser_download_url":"https://example.com/DroidDrone-PC-Setup-9.exe"}]}""".formatted("0".repeat(64)));
        assertFalse(elsewhere.canInstall());
        UpdateCheck.Release noDigest = UpdateCheck.parse("""
                {"tag_name":"v9","html_url":"x","assets":[{"name":"DroidDrone-PC-Setup-9.exe","size":1,"digest":null,
                 "browser_download_url":"https://github.com/MelonNO/droiddrone-pc/releases/download/v9/DroidDrone-PC-Setup-9.exe"}]}""");
        assertFalse(noDigest.canInstall());
    }

    @Test
    void helperScriptQuotesPathsSafely() {
        String s = SelfUpdate.script(java.nio.file.Path.of("C:\\Temp\\it's here\\Setup.exe"), 1234,
                java.nio.file.Path.of("C:\\Program Files\\DroidDrone PC\\DroidDrone PC.exe"), "ab12'; xyz");
        assertTrue(s.contains("Wait-Process -Id 1234"));
        assertTrue(s.contains("if (Get-Process -Id 1234) { exit }"), "still running after 60 s: nothing installed");
        assertTrue(s.contains("if ($h -ne 'ab12') {"), "checked again before running; only hex digits get into the script");
        assertTrue(s.contains("'C:\\Temp\\it''s here\\Setup.exe' -ArgumentList '/passive','/norestart' -Wait"), s);
        assertTrue(s.contains("$app = 'C:\\Program Files\\DroidDrone PC\\DroidDrone PC.exe'"), s);
    }
}
