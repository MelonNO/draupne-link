/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.common;

/**
 * F-083: how the phone app puts itself in front from the background - after an error ended it, after it was ended
 * asleep, after an update the PC asked for (CrashGuard, AppReplacedReceiver), and when a PC wakes it from deep sleep
 * (DDService). The one way Android gives is a notification with a full-screen intent, and Android launches that only
 * while the screen is off, the phone dreams, or a lock screen shows; on a lit, unlocked screen it is a banner to tap
 * (SystemUI's rule, Android 14: NotificationInterruptStateProviderImpl.getFullScreenIntentDecision; Android 15:
 * FullScreenIntentDecisionProvider). From Android 14 the app must also be allowed full-screen notices
 * (NotificationManager.canUseFullScreenIntent), and the notice's channel must be of high importance.
 * <p>
 * Seen on a real phone (Samsung, Android 15, screen lock None, 2026-11-22): the app took a bright wake lock together
 * with the notification. The wake lock lit the screen before Android looked at the notification; with no lock screen
 * the lit screen was unlocked and the app stayed a banner. With a PIN the lit screen was a lock screen and the app
 * opened - which is why it always worked there.
 * <p>
 * So the notification goes first, with the screen as it is. Only when the app has not come up {@link #LIGHT_AFTER_MS}
 * later (a phone maker that holds the full-screen intent back) does the app light the screen itself; over a lock
 * screen the notification is then posted once more, which Android launches on a lit lock screen. With no lock screen
 * a second notice is only a second banner: the first stays to be tapped.
 * <p>
 * Pure: the phone asks it; the PC's tests check it.
 */
public final class ScreenWake {
    /** The app's time to come up after the notice, before the app lights the screen itself. */
    public static final long LIGHT_AFTER_MS = 2000;
    /** After the screen is lit: the lock screen's moment to come up before the notice is posted again. */
    public static final long POST_AGAIN_AFTER_MS = 300;

    /** What the app does {@link #LIGHT_AFTER_MS} after the notice. */
    public enum Step {
        /** The app came up: nothing more. */
        DONE,
        /** The screen lit; the notice left as it is, a banner to tap. */
        LIGHT,
        /** The screen lit, and the notice posted again: over a lit lock screen Android opens the app. */
        LIGHT_AND_POST_AGAIN
    }

    private ScreenWake() {
    }

    /**
     * Whether Android launches the notice's full-screen intent - opens the app - now.
     * @param allowed     the app may use full-screen notices (always so before Android 14)
     * @param important   the notice's channel is of high importance
     * @param interactive the screen is on
     * @param lockScreen  a lock screen shows (a swipe or a secure one)
     */
    public static boolean opens(boolean allowed, boolean important, boolean interactive, boolean lockScreen) {
        return allowed && important && (!interactive || lockScreen);
    }

    /** {@link #LIGHT_AFTER_MS} after the notice: what now. */
    public static Step afterWait(boolean cameUp, boolean lockScreen) {
        if (cameUp) return Step.DONE;
        return lockScreen ? Step.LIGHT_AND_POST_AGAIN : Step.LIGHT;
    }

    /**
     * Whether the app's main screen came up for the notice.
     * @param inFront  it is in front now
     * @param cameUpAt when it last came up (created, a WAKE, resumed; the uptime clock), 0 when never in this process
     * @param postedAt when the notice was posted (the same clock)
     */
    public static boolean cameUp(boolean inFront, long cameUpAt, long postedAt) {
        return inFront || (cameUpAt > 0 && cameUpAt >= postedAt);
    }

    /** For the log: the screen as the notice meets it, and what Android will do with it. */
    public static String said(boolean allowed, boolean important, boolean interactive, boolean lockScreen) {
        StringBuilder s = new StringBuilder(interactive ? "screen on" : "screen off").append(lockScreen ? ", lock screen" : ", no lock screen");
        if (!allowed) s.append(", full-screen notices NOT allowed");
        if (!important) s.append(", its channel below high");
        return s.append(opens(allowed, important, interactive, lockScreen) ? " - Android opens the app" : " - only a banner").toString();
    }
}
