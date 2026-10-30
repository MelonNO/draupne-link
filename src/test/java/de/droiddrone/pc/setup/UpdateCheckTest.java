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
    }
}
