package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static de.droiddrone.common.ScreenWake.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F-083 (a real phone, Samsung, Android 15, screen lock None, 2026-11-22): the app's reopen fired on time and the app
 * never came up. Its full-screen notification went out together with a bright wake lock; the wake lock lit the screen
 * before Android looked at the notification, and a lit screen with no lock screen gets a banner, not the app. With a
 * PIN the lock screen showed and the app opened. The tests marked "before" hold the old order to Android's rule.
 */
class ScreenWakeTest {
    private static final boolean ALLOWED = true, NOT_ALLOWED = false, HIGH = true, LOW = false;
    private static final boolean SCREEN_ON = true, SCREEN_OFF = false, LOCK_SCREEN = true, NO_LOCK_SCREEN = false;

    /** Android's rule for a full-screen intent (SystemUI, Android 14 and 15), as the app reads it. */
    @Test
    void androidOpensTheAppOnlyWithTheScreenOffOrALockScreenShowing() {
        assertTrue(opens(ALLOWED, HIGH, SCREEN_OFF, NO_LOCK_SCREEN), "screen off, no lock: the app opens");
        assertTrue(opens(ALLOWED, HIGH, SCREEN_OFF, LOCK_SCREEN));
        assertTrue(opens(ALLOWED, HIGH, SCREEN_ON, LOCK_SCREEN), "a lit lock screen: the app opens over it");
        assertFalse(opens(ALLOWED, HIGH, SCREEN_ON, NO_LOCK_SCREEN), "a lit, unlocked screen: only a banner");
        assertFalse(opens(NOT_ALLOWED, HIGH, SCREEN_OFF, LOCK_SCREEN), "full-screen notices not allowed (Android 14+): only a banner");
        assertFalse(opens(ALLOWED, LOW, SCREEN_OFF, LOCK_SCREEN), "a channel turned below high: no full screen");
    }

    @Test
    void beforeTheWakeLockLitTheScreenFirstAndWithNoLockScreenTheAppStayedABanner() {
        // before: notification and wake lock at once - Android met the screen the wake lock had lit
        assertFalse(opens(ALLOWED, HIGH, SCREEN_ON, NO_LOCK_SCREEN), "screen lock None: the lit screen is unlocked");
        assertTrue(opens(ALLOWED, HIGH, SCREEN_ON, LOCK_SCREEN), "a PIN: the lit screen is a lock screen - why it always worked there");
        // now: the notification meets the screen as it is - dark, after a sleep or the app's own lockNow
        assertTrue(opens(ALLOWED, HIGH, SCREEN_OFF, NO_LOCK_SCREEN));
    }

    @Test
    void theScreenIsLitOnlyWhenTheAppHasNotComeUpAndPostedAgainOnlyOverALockScreen() {
        assertEquals(Step.DONE, afterWait(true, LOCK_SCREEN), "the app came up: nothing more");
        assertEquals(Step.DONE, afterWait(true, NO_LOCK_SCREEN));
        assertEquals(Step.LIGHT_AND_POST_AGAIN, afterWait(false, LOCK_SCREEN), "held back (a phone maker's rule): lit, and posted over the lit lock screen");
        assertEquals(Step.LIGHT, afterWait(false, NO_LOCK_SCREEN), "no lock screen: a second notice is only a second banner");
        assertTrue(LIGHT_AFTER_MS >= 1500 && LIGHT_AFTER_MS <= 3000, "about 2 s: time for the app to come up, not long on a drone");
        assertTrue(POST_AGAIN_AFTER_MS > 0 && POST_AGAIN_AFTER_MS < 1000);
    }

    @Test
    void cameUpIsInFrontOrShownSinceTheNotice() {
        long posted = 50_000;
        assertTrue(cameUp(true, 0, posted), "in front now");
        assertTrue(cameUp(false, posted, posted));
        assertTrue(cameUp(false, posted + 900, posted), "came up after the notice, gone again (the screen off by itself)");
        assertFalse(cameUp(false, posted - 1, posted), "an earlier showing is not this one");
        assertFalse(cameUp(false, 0, posted), "never shown in this process");
    }

    @Test
    void theLogSaysWhatAndroidWillDo() {
        assertEquals("screen off, no lock screen - Android opens the app", said(ALLOWED, HIGH, SCREEN_OFF, NO_LOCK_SCREEN));
        assertEquals("screen on, lock screen - Android opens the app", said(ALLOWED, HIGH, SCREEN_ON, LOCK_SCREEN));
        assertEquals("screen on, no lock screen - only a banner", said(ALLOWED, HIGH, SCREEN_ON, NO_LOCK_SCREEN));
        assertEquals("screen off, lock screen, full-screen notices NOT allowed - only a banner", said(NOT_ALLOWED, HIGH, SCREEN_OFF, LOCK_SCREEN));
        assertEquals("screen off, lock screen, its channel below high - only a banner", said(ALLOWED, LOW, SCREEN_OFF, LOCK_SCREEN));
    }

    /**
     * The phone app cannot be run here: its sources are read, as WakeIntentTest does. Neither the reopen (after an
     * error, a kill asleep, an update) nor the service's wake after a sleep lights the screen with its notification:
     * both hand it to WakeNotice, whose wake lock comes in the step after the wait.
     */
    @Test
    void noWakeLockGoesOutWithTheNotification() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        Path flight = root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight");
        String replaced = text(flight.resolve("AppReplacedReceiver.java")), service = text(flight.resolve("DDService.java"));
        String notice = text(flight.resolve("WakeNotice.java"));
        for (String caller : new String[]{replaced, service}) {
            assertFalse(caller.contains("ACQUIRE_CAUSES_WAKEUP"), "no bright wake lock beside the notification");
            assertTrue(caller.contains("WakeNotice.post("));
        }
        assertFalse(replaced.contains("manager.notify("), "the reopen's notice is posted by WakeNotice only");
        assertFalse(service.contains("notify(WAKE_NOTIFICATION_ID"), "the wake's too (the service's own running notice is its own)");
        int posted = notice.indexOf("manager.notify(id, n);"), waited = notice.indexOf("ScreenWake.afterWait("), lit = notice.indexOf("ACQUIRE_CAUSES_WAKEUP");
        assertTrue(posted > 0 && waited > posted && lit > waited, "posted first, then the wait, then (maybe) the light");
        assertTrue(notice.contains("ScreenWake.LIGHT_AFTER_MS"));
        assertTrue(notice.contains("canUseFullScreenIntent()"), "Android 14+: whether the app may use full-screen notices, said in the log");
        String activity = text(flight.resolve("MainActivity.java"));
        String resume = activity.substring(activity.indexOf("protected void onResume(){"), activity.indexOf("protected void onPause(){"));
        assertTrue(resume.contains("cameUpAt = android.os.SystemClock.elapsedRealtime();") && resume.contains("inFront = true;"));
        assertTrue(activity.contains("WakeNotice.sayAllowed(this);"), "said at the start");
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
