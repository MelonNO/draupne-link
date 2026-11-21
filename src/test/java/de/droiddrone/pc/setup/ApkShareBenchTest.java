package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The bench's switch for the phone app the PC hands out (F-054's emulator test): unset, the release pin stands. */
class ApkShareBenchTest {
    @Test
    void unsetThePinStands() {
        assertEquals("2.3.0-pc.52", ApkShare.bench(null, 0, "2.3.0-pc.52"));
        assertEquals("2.3.0-pc.52", ApkShare.bench("  ", 0, "2.3.0-pc.52"));
        if (System.getProperty("draupne.bench.phoneApp") == null) {
            assertTrue(ApkShare.APK_URL.startsWith("https://github.com/MelonNO/draupne-link/releases/download/flight-" + ApkShare.VERSION + "/"),
                    "outside the bench the app hands out the release's file: " + ApkShare.APK_URL);
            assertEquals(64, ApkShare.APK_SHA256.length());
        }
    }

    @Test
    void theBenchsTestBuildInItsPlace() {
        String s = "2.3.0-pc.55-test|abc123|http://10.0.2.2:5000/x.apk";
        assertEquals("2.3.0-pc.55-test", ApkShare.bench(s, 0, "pin"));
        assertEquals("abc123", ApkShare.bench(s, 1, "pin"));
        assertEquals("http://10.0.2.2:5000/x.apk", ApkShare.bench(s, 2, "pin"));
        assertEquals("pin", ApkShare.bench("only|two", 0, "pin"), "anything but all three: the pin");
        assertEquals("pin", ApkShare.bench("a||c", 1, "pin"), "an empty part: the pin");
    }
}
