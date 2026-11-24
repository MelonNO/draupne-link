package de.droiddrone.bench;

import java.util.List;

/**
 * The phone app's own words, in ONE place: what it writes on its screen, what it says in Android's log (tag DDLog) and
 * what the bench build answers when asked - everything the phone scenarios wait for, count or check. A fact the
 * screen shows can often be had a second way, from the log or from the bench build: a phone with a lock screen over
 * the app (as the phones on the drones have) is tested by those.
 * <p>
 * Each of them is the app's, not the bench's: {@link #ORIGINS} says where in the phone app's sources it is written,
 * and {@code PhoneRigWordsTest} reads those sources and fails when a line's words have drifted - before a scenario
 * waits a night for words the app no longer says.
 */
public final class PhoneWords {
    private PhoneWords() {
    }

    // ------------------------------------------------------------------ on its screen (res/values/strings.xml)

    /** The line under the app's name on its main screen ("2.3.0-pc.55 · for DRAUPNE Link"): the app's screen is up. */
    public static final String SCREEN_TITLE = "for DRAUPNE Link";
    /** The status card while a PC flies the phone. */
    public static final String SCREEN_CONNECTED = "Connected to PC";
    /** The status card of a phone with nothing on its USB. */
    public static final String SCREEN_NO_FC = "No flight controller on USB";
    /** The button that starts the service by hand (a start with everything allowed starts it by itself). */
    public static final String SCREEN_START = "START";

    // ------------------------------------------------------------------ in its log (tag DDLog)

    /** The service has its link socket and listens for a PC. */
    public static final String RECEIVER_STARTED = "Start receiver thread - OK";
    /** The link port could not be had (the other DRAUPNE Flight on the same phone holds it): the service stops itself. */
    public static final String LINK_PORT_TAKEN = "UDP initialize error.";
    private static final String POWER = "Power: ";
    /** STANDBY taken from the PC ("Power: Standby (the PC at ...)"). */
    public static final String STANDBY = POWER + "Standby";
    /** DEEP SLEEP taken: the countdown to it. */
    public static final String DEEP_SLEEP = POWER + "Deep sleep in";
    /** Going into deep sleep the app locked the screen itself (it is the phone's device admin for that). */
    public static final String SLEEP_LOCKED_SCREEN = "Deep sleep: screen locked";
    /** Asleep, the phone's own alarm woke it to check for a PC. */
    public static final String SLEEP_CHECK = "Sleep check alarm fired";
    /** The app put itself in front of the lock screen: woken by a PC, or opened again after it was ended. */
    public static final String OPENED_OVER_LOCK = "Opened to wake the phone";
    /** An error nobody caught, as the app's crash guard writes it down before the process ends. */
    public static final String FATAL = "FATAL on thread";
    /** ... and that it opens itself again. */
    public static final String OPENING_AGAIN = "Opening again";
    /** ... or that it has done so too often and stays down. */
    public static final String NOT_OPENED_AGAIN = "Not opened again";
    /** The new start says what ended the last one. */
    public static final String LAST_ENDED_BY = "The app was last ended by";
    /** A new session of the link, begun by a Connect from another address or port ... */
    public static final String NEW_SESSION = "New session from";
    /** ... or by the pilot's own PC connecting again. */
    public static final String NEW_SESSION_SAME_PC = "a new session";
    /** The link socket closed, stuck or opened anew (F-039). */
    public static final String LINK_SOCKET = "Link socket:";
    /** ... of those, the two that say it was closed, or that nothing left it though the PC was heard (a jammed socket), and it was opened anew. */
    public static final String LINK_SOCKET_CLOSED = LINK_SOCKET + " was closed - ";
    public static final String LINK_SOCKET_STUCK = LINK_SOCKET + " nothing left the phone for ";
    /** Q-006: the link socket parked, silent - the phone is not on Tailscale (never in the emulator's bench build) ... */
    public static final String NO_TAILSCALE = "no Tailscale - the phone answers nothing";
    /** ... or bound on its Tailscale address ... */
    public static final String ON_TAILSCALE = "on Tailscale, ";
    /** ... or that address could not be bound (the other DRAUPNE Flight holds the port): silent, tried again. */
    public static final String LINK_NOT_BOUND = "could not be bound";
    /** OVERWATCH taken: the camera watches for motion (the countdown over). */
    public static final String OVERWATCH_WATCHING = "Overwatch: watching (sensitivity";
    /** ... motion seen: the alert to the PC, an event begun. */
    public static final String OVERWATCH_MOTION = "Overwatch: MOTION - event";
    /** ... a piece of an event's clip recorded, waiting for the PC to fetch it (the end of its line). */
    public static final String OVERWATCH_CLIP_WAITS = " KB) waits for the PC";
    /** ... its floor (battery or heat) met: DEEP SLEEP instead, by itself (the end of its line). */
    public static final String OVERWATCH_TO_DEEP_SLEEP = " C) - deep sleep instead";
    /** The camera closed while a change of the picture's size was being set up (F-047), met and survived. */
    public static final String CAMERA_CLOSED_MEANWHILE = "the camera was closed meanwhile";
    /** The camera's session set up; the camera closed; the encoder started anew. */
    public static final String CAMERA_CONFIGURED = "onConfigured";
    public static final String CAMERA_CLOSED = "Camera device closed";
    public static final String ENCODER_STARTED = "onOutputFormatChanged";

    // ------------------------------------------------------------------ the bench build's answer (STATS)

    /** In the bench build's one line of figures: a PC is connected; its service runs; no flight controller (status 1). */
    public static final String STATS_PC_CONNECTED = "pc=connected";
    public static final String STATS_SERVICE_RUNNING = "service=running";
    public static final String STATS_SERIAL = "serialStatus=";

    // ------------------------------------------------------------------ Android's own, about the app

    /** Android's crash handler, on an error that ended an app. */
    public static final String ANDROID_FATAL = "FATAL EXCEPTION";
    /** Any line of either kind that says the app was ended by an error. */
    public static final String ANY_FATAL = "FATAL";
    /** The camera framework's own word in the error of F-047. */
    public static final String ANDROID_CAMERA_CLOSED = "CameraDevice was already closed";

    // ------------------------------------------------------------------ where the app writes them

    /**
     * @param words    what the bench looks for
     * @param file     the source file, under flight-app/
     * @param inSource what must stand in that file for the app to say those words: every piece of it
     */
    record Origin(String words, String file, List<String> inSource) {
        Origin(String words, String file, String... inSource) {
            this(words, file, List.of(inSource));
        }
    }

    private static final String MAIN = "overlay/Flight/src/main/java/de/droiddrone/flight/";
    private static final String STRINGS = "overlay/Flight/src/main/res/values/strings.xml";
    private static final String RECEIVER = "bench-overlay/Flight/src/bench/java/de/droiddrone/flight/BenchControlReceiver.java";

    static final List<Origin> ORIGINS = List.of(
            new Origin(SCREEN_TITLE, STRINGS, "<string name=\"version\">%1$s · " + SCREEN_TITLE + "</string>"),
            new Origin(SCREEN_CONNECTED, STRINGS, "<string name=\"status_connected\">" + SCREEN_CONNECTED + "</string>"),
            new Origin(SCREEN_NO_FC, STRINGS, "<string name=\"fc_status_no_device\">" + SCREEN_NO_FC + "</string>"),
            new Origin(RECEIVER_STARTED, MAIN + "Udp.java", "log(\"" + RECEIVER_STARTED + "\");"),
            new Origin(LINK_PORT_TAKEN, MAIN + "DDService.java", "log(\"" + LINK_PORT_TAKEN + "\");", "if (!udp.initialize()){"),
            new Origin(STANDBY, MAIN + "PowerMode.java", "log(\"" + POWER + "\" + text);", "event(\"Standby (\" + from + \")\");"),
            new Origin(DEEP_SLEEP, MAIN + "PowerMode.java", "log(\"" + POWER + "\" + text);", "event(\"Deep sleep in \" + deepIn / 1000"),
            new Origin(SLEEP_LOCKED_SCREEN, MAIN + "DDService.java", "dpm.lockNow();", "log(\"" + SLEEP_LOCKED_SCREEN + "\");"),
            new Origin(SLEEP_CHECK, MAIN + "PowerAlarmReceiver.java", "log(\"" + SLEEP_CHECK + "\");"),
            new Origin(OPENED_OVER_LOCK, MAIN + "MainActivity.java", "log(\"" + OPENED_OVER_LOCK + ": over the lock screen\");", "setShowWhenLocked(true);", "setTurnScreenOn(true);"),
            new Origin(FATAL, MAIN + "CrashGuard.java", "log(\"" + FATAL + " \" + thread.getName()"),
            new Origin(OPENING_AGAIN, MAIN + "CrashGuard.java", "log(\"" + OPENING_AGAIN + " in \" + CrashRestarts.DELAY_MS"),
            new Origin(NOT_OPENED_AGAIN, MAIN + "CrashGuard.java", "log(\"" + NOT_OPENED_AGAIN + ": \" + CrashRestarts.MAX"),
            new Origin(LAST_ENDED_BY, MAIN + "MainActivity.java", "log(\"" + LAST_ENDED_BY + ": \" + ended);"),
            new Origin(NEW_SESSION, MAIN + "Udp.java", "log(\"" + NEW_SESSION + " \" + packet.getAddress().getHostAddress()"),
            new Origin(NEW_SESSION_SAME_PC, MAIN + "Udp.java", "log(\"The pilot connects again (its link was lost): " + NEW_SESSION_SAME_PC + "\");"),
            new Origin(LINK_SOCKET, MAIN + "Udp.java", "\"" + LINK_SOCKET_CLOSED + "\"", "\"" + LINK_SOCKET_STUCK + "\""),
            new Origin(NO_TAILSCALE, MAIN + "Udp.java", "static final String NO_TAILSCALE = \"" + NO_TAILSCALE + "\";", "log(\"Link socket: \" + where(at));"),
            new Origin(ON_TAILSCALE, MAIN + "Udp.java", ": \"" + ON_TAILSCALE + "\" + at.address.getHostAddress() + \":\" + port;"),
            new Origin(LINK_NOT_BOUND, MAIN + "Udp.java", "\" " + LINK_NOT_BOUND + " (\" + e + \") - silent, tried again\""),
            new Origin(OVERWATCH_WATCHING, MAIN + "Overwatch.java", "log(\"" + OVERWATCH_WATCHING + " \" + sensitivity"),
            new Origin(OVERWATCH_MOTION, MAIN + "Overwatch.java", "log(\"" + OVERWATCH_MOTION + " \" + event.event()"),
            new Origin(OVERWATCH_CLIP_WAITS, MAIN + "Overwatch.java", "log(\"Overwatch: clip \" + c.file.getName() + \" (\" + c.size() / 1024 + \"" + OVERWATCH_CLIP_WAITS + "\");"),
            new Origin(OVERWATCH_TO_DEEP_SLEEP, MAIN + "Overwatch.java", "log(\"Overwatch: \" + PowerFloor.describe(reason) + \" (battery \" + battery + \" %, \" + tempDeci / 10.0 + \""
                    + OVERWATCH_TO_DEEP_SLEEP + "\");", "PowerMode.floorDrop(reason);"),
            new Origin(CAMERA_CLOSED_MEANWHILE, MAIN + "InternalCamera.java", "log(\"createCaptureRequest: " + CAMERA_CLOSED_MEANWHILE + ": \"+ e);"),
            new Origin(CAMERA_CONFIGURED, MAIN + "InternalCamera.java", "log(\"CameraCaptureSession - " + CAMERA_CONFIGURED + "\");"),
            new Origin(CAMERA_CLOSED, MAIN + "InternalCamera.java", "log(\"" + CAMERA_CLOSED + ".\");"),
            new Origin(ENCODER_STARTED, MAIN + "StreamEncoder.java", "log(\"encoderCallback - " + ENCODER_STARTED + "\");"),
            new Origin(STATS_PC_CONNECTED, RECEIVER, "\" pc=\" + (DDService.isConnected ? \"connected\" : \"none\")"),
            new Origin(STATS_SERVICE_RUNNING, RECEIVER, "\" service=\" + (DDService.isRunning ? \"running\" : \"stopped\")"),
            new Origin(STATS_SERIAL, RECEIVER, "\" " + STATS_SERIAL + "\" + DDService.getSerialPortStatus()"));
}
