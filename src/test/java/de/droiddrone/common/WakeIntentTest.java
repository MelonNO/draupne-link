package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The main screen's WAKE is taken only from the app itself (its token), and the app is over the lock screen for the
 * showing it was woken for only. Before, any app could send WAKE and put it over the lock screen, where it stayed.
 */
class WakeIntentTest {
    @Test
    void aTokenIsThirtyTwoHexDigitsAndNewEachTime() {
        Random r = new Random(1);
        String a = WakeIntent.newToken(r), b = WakeIntent.newToken(r);
        assertTrue(a.matches("[0-9a-f]{32}"), a);
        assertNotEquals(a, b);
    }

    @Test
    void onlyTheKeptTokenWholeIsTheAppsOwn() {
        String kept = WakeIntent.newToken(new Random(2));
        assertTrue(WakeIntent.own(kept, kept));
        assertTrue(WakeIntent.own(kept, new String(kept.toCharArray())));
        assertFalse(WakeIntent.own(kept, null), "another app's WAKE: no token");
        assertFalse(WakeIntent.own(kept, ""));
        assertFalse(WakeIntent.own(kept, kept.substring(1)));
        assertFalse(WakeIntent.own(kept, kept + "0"));
        assertFalse(WakeIntent.own(kept, (kept.charAt(0) == '0' ? '1' : '0') + kept.substring(1)));
        assertFalse(WakeIntent.own(null, kept), "none kept: none is the app's own");
        assertFalse(WakeIntent.own("", ""));
    }

    @Test
    void overTheLockScreenForTheShowingItWasWokenFor() {
        WakeIntent.OverLock o = new WakeIntent.OverLock();
        assertFalse(o.stopped(), "never woken: nothing to clear");
        o.woken();
        assertTrue(o.on());
        assertFalse(o.stopped(), "stopped on the way, before it was shown (the screen still dark): kept for the showing");
        o.resumed();
        assertTrue(o.on());
        assertTrue(o.stopped(), "shown, then left: cleared");
        assertFalse(o.on());
        assertFalse(o.stopped(), "once");
        o.resumed();
        assertFalse(o.stopped(), "a plain showing after it: behind the lock, nothing to clear");
        o.woken(); // the next wake from a sleep
        o.resumed();
        assertTrue(o.stopped());
    }

    /** The phone app cannot be run here: its sources are read, as ArmedTouchTest does. */
    @Test
    void thePhonesWakeIsCheckedAndClearedAndItsOwnIntentsCarryTheToken() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        Path flight = root.resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight");
        String activity = text(flight.resolve("MainActivity.java")), service = text(flight.resolve("DDService.java"));
        String replaced = text(flight.resolve("AppReplacedReceiver.java"));
        String show = activity.substring(activity.indexOf("private void showOverLockScreen(Intent intent){"));
        show = show.substring(0, show.indexOf("\n    }"));
        int own = show.indexOf("WakeIntent.own(wakeToken(this), intent.getStringExtra(EXTRA_WAKE_TOKEN))");
        assertTrue(own > 0 && own < show.indexOf("setShowWhenLocked(true);"), "checked before it goes over the lock screen");
        assertTrue(show.contains("overLock.woken();"));
        String resume = activity.substring(activity.indexOf("protected void onResume(){"), activity.indexOf("protected void onPause(){"));
        assertTrue(resume.contains("overLock.resumed();"));
        String stop = activity.substring(activity.indexOf("protected void onStop(){"));
        stop = stop.substring(0, stop.indexOf("\n    }"));
        assertTrue(stop.contains("if (overLock.stopped()) {") && stop.contains("setShowWhenLocked(false);") && stop.contains("setTurnScreenOn(false);")
                && stop.contains("clearFlags("), "cleared again after the showing");
        // the app's own WAKEs: the service's after a sleep, and the one after an update or an error (CrashGuard's way)
        assertEquals(1, activity.split("\\.setAction\\(ACTION_WAKE\\)", -1).length - 1, "one place makes the WAKE intent");
        assertTrue(activity.contains(".putExtra(EXTRA_WAKE_TOKEN, wakeToken(c))"));
        assertTrue(service.contains("Intent intent = MainActivity.wakeIntent(this);"));
        assertTrue(replaced.contains("Intent open = MainActivity.wakeIntent(context);"));
        assertFalse(service.contains("setAction(MainActivity.ACTION_WAKE)") || replaced.contains("setAction(MainActivity.ACTION_WAKE)"));
        // the token in the app's own storage, made with SecureRandom
        String token = activity.substring(activity.indexOf("static String wakeToken(android.content.Context c) {"));
        token = token.substring(0, token.indexOf("\n    }"));
        assertTrue(token.contains("MODE_PRIVATE") && token.contains("WakeIntent.newToken(new java.security.SecureRandom())"));
    }

    private static String text(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
